package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.OperationalMoves
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The naval operations and the nukes (CvAIOperation: CvAINavalOperation and its subclasses, CvAINavalEscortedOperation, CvAIOperationNavalAttack,
 *  CvAIOperationNukeAttack; CvMilitaryAI: RequestCityAttack's naval army (our pure naval attack), the naval part of UpdateOperations, RequestNukeAttack and its trigger;
 *  CvTacticalAI: PlotArmyMovesCombat on water) with the BNW XML values and the CP formations. A channel of coast water separates our city
 *  at x=0 from the enemy's at x=14.
 */
@RunWith(GdxTestRunner::class)
class NavalOperationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(20)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        for (x in 1..13) for (y in -6..6) game.tileMap.getOrNull(x, y)?.let { game.setTileTerrain(it.position, "Coast") }
        city = game.addCity(civ, tile(0))
        enemyCity = game.addCity(enemy, tile(14))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        civ.tech.unitsCanEmbark = true
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))
    private fun tile(position: HexCoord) = game.getTile(position)

    /** Putting units on the map recomputes what we see: after placing them, we see all of it again */
    private fun seeAll() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun ship(name: String, x: Int, y: Int = 0): MapUnit = game.addUnit(name, civ, tile(x, y))

    /** The four required ships of the pure naval formation: two that attack at sea, two assault ships */
    private fun fleet(): List<MapUnit> = listOf(ship("Quinquereme", 2, 0), ship("Quinquereme", 2, 1), ship("Galleass", 3, 0), ship("Galleass", 3, 1))

    /** An attack target of Vox Populi's naval army: ships alone from our port to the enemy city */
    private fun navalTarget() = MilitaryTarget(city, enemyCity).apply { attackBySea = true; pureNaval = true }

    private fun requestNavalAttack(numUnitsWillingToBuild: Int) = MilitaryOperations.requestSpecificAttack(civ, navalTarget(), numUnitsWillingToBuild)

    // region Pure naval city attack

    /** CvAIOperationCityAttackNaval::Init: the fleet musters on the coastal water next to our port and its goal is the coastal water next to the enemy city */
    @Test
    fun `a pure naval attack starts when all but the number of units we are willing to build of the required slots are filled`() {
        val ships = fleet()
        assertTrue(requestNavalAttack(0))
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        // the ships stand within the gather tolerance of the muster, so SetUpArmy's second transition takes the fleet straight on to move
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertTrue(ships.all { ops.armyOf(it) != null })
        val target = tile(operation.targetTile!!)
        assertTrue("the goal is water next to the enemy city, not the city: ${target.position}", target.isWater && target.aerialDistanceTo(enemyCity.getCenterTile()) <= 2)
        assertEquals(target.position, operation.firstArmy!!.goalTile)
        // SetMusterPlot: the army has gathered where its ships were, within the tolerance of one another
        assertEquals(city.id, operation.startCityId)
        // no city trains units for it: its area is the water at the muster
        assertEquals(-1, operation.area)
    }

    @Test
    fun `one missing ship is built when willing, two missing are too many`() {
        val ships = listOf(ship("Quinquereme", 2, 0), ship("Quinquereme", 2, 1), ship("Galleass", 3, 0))
        assertFalse("one short and not willing", requestNavalAttack(0))
        assertNull(ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK))
        assertTrue(requestNavalAttack(1))
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(1, operation.slotsToBuild.size)
        val muster = tile(operation.musterTile!!)
        assertTrue("while recruiting it musters on water next to our port: ${muster.position}", muster.isWater && muster.aerialDistanceTo(city.getCenterTile()) <= 2)
        assertEquals(3, ships.count { ops.armyOf(it) != null })
        // the one more slot is bought in the start city
        assertTrue(operation.hasOneMoreSlotToFill())
        ops.kill(operation, OperationAbortReason.KILLED)
        ships[2].destroy()
        assertFalse("two short", requestNavalAttack(1))
    }

    /** CvAIOperationMilitary::CheckTransitionToNextStage: within the deploy range of its goal the fleet has arrived; OnSuccess focuses the tactical AI on the city */
    @Test
    fun `a pure naval attack ends in success once the fleet is within the deploy range of its goal, the tactical AI gets a zone and a focus there`() {
        val ships = fleet()
        requestNavalAttack(0)
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        val goal = tile(army.goalTile!!)
        val places = goal.getTilesInDistance(2).filter { it.isWater && it.militaryUnit == null }.toList()
        for ((ship, place) in ships.zip(places)) { ship.removeFromTile(); ship.putInTile(place) }
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.temporaryZones.any { it.position == enemyCity.getCenterTile().position })
        assertTrue(civ.ai.tactical.isInFocusArea(enemyCity.getCenterTile()))
    }

    @Test
    fun `a pure naval attack is called off when its city is no longer the enemy's`() {
        fleet()
        requestNavalAttack(0)
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        enemyCity.moveToCiv(civ)
        // CvAIOperationMilitary::VerifyOrAdjustTarget: owned by anyone but the enemy
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.TARGET_ALREADY_CAPTURED, operation.abortReason)
    }
    // endregion

    // region Superiority (CvAIOperationNavalSuperiority, CvMilitaryAI::CheckSeaDefenses)

    /** The required slots of the naval squadron: two ships that attack at sea and an assault ship, none bound to the coast (Vox Populi asks for ships that can enter the ocean) */
    private fun squadron(x: Int, y: Int = 0): List<MapUnit> = listOf(ship("Ironclad", x, y), ship("Ironclad", x, y + 1), ship("Frigate", x + 1, y))

    private fun losing() {
        civ.ai.warStates.warStates[enemy.civID] = WarState.Defensive
    }

    @Test
    fun `a war going badly sends a squadron to the threatened coastal city, from the water next to our nearest port`() {
        losing()
        val ships = squadron(7)
        seeAll()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY)!!
        assertEquals(enemy.civID, operation.enemyCivId)
        assertTrue("all three required ships are in it", ships.all { ops.armyOf(it) != null })
        val target = game.gameInfo.tileMap[operation.targetTile!!]
        assertTrue("it defends the water by the city", target.isWater && target.aerialDistanceTo(city.getCenterTile()) <= 2)
        // the army gathers where its ships are, so the muster point is water, not necessarily by the port
        assertTrue("the squadron musters on water", game.gameInfo.tileMap[operation.musterTile!!].isWater)
        assertEquals(city.id, operation.startCityId)
    }

    @Test
    fun `a war that is not going badly starts no squadron`() {
        civ.ai.warStates.warStates[enemy.civID] = WarState.Stalemate
        squadron(7)
        seeAll()
        MilitaryOperations.updateOperations(civ)
        assertNull(ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY))
    }

    /** PreconditionsAreMet is asked without a muster plot: the ships must be able to enter the ocean */
    @Test
    fun `ships that cannot enter the ocean are no squadron`() {
        losing()
        listOf(ship("Quinquereme", 7, 0), ship("Quinquereme", 7, 1), ship("Galleass", 8, 0))
        seeAll()
        MilitaryOperations.updateOperations(civ)
        assertNull(ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY))
    }

    @Test
    fun `a squadron that is already running near the coastal city is not doubled`() {
        losing()
        squadron(7)
        squadron(7, 3)
        seeAll()
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.NAVAL_SUPERIORITY))
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.NAVAL_SUPERIORITY))
    }

    @Test
    fun `a squadron that is in place at the coastal water succeeds and the city is a focus of the tactical AI`() {
        losing()
        val ships = squadron(7)
        seeAll()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY)!!
        val army = operation.firstArmy!!
        val goal = game.gameInfo.tileMap[army.goalTile!!]
        // Within 3 plots of the goal: the squadron has arrived (Vox Populi's deploy range)
        val places = goal.getTilesInDistance(1).filter { it.isWater && it.militaryUnit == null }.toList()
        for ((index, ship) in ships.withIndex()) {
            ship.removeFromTile(); ship.putInTile(places[index])
        }
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.isInFocusArea(city.getCenterTile()))
    }

    @Test
    fun `a squadron ends when no coastal city is left to defend`() {
        game.addCity(civ, tile(-6)) // an inland city, so that the port is not our last one
        losing()
        squadron(7)
        seeAll()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY)!!
        // The port is lost: no threatened coastal city to sail to
        city.moveToCiv(enemy)
        civ.ai.invalidateTurnCaches()
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.NO_TARGET, operation.abortReason)
    }
    // endregion

    // region Invasions

    /**
     *  One unit for each required slot of the formation [formationName] (with the CP data), the first unit of the ruleset's units that may take
     *  the slot's unit AI, its secondary one if none takes the primary: land units on land by our port, ships on the water east of it
     */
    private fun forceFor(formationName: String, leaveOut: Int = 0): List<MapUnit> {
        val rules = game.ruleset.ai
        val candidates = game.ruleset.units.values.filter {
            (it.isLandUnit || it.isWaterUnit) && (it.strength > 0 || it.rangedStrength > 0) && it.uniqueTo == null && !it.isGreatPerson &&
                it.name !in listOf("Atomic Bomb", "Nuclear Missile", "Guided Missile", "Missile Cruiser")
        }
        val land = ArrayDeque(listOf(tile(0, 1), tile(0, -1), tile(-1, 0), tile(-1, 1), tile(-1, -1), tile(-2, 0), tile(-2, 1), tile(-2, -1), tile(0, 2), tile(0, -2)))
        val sea = ArrayDeque((2..3).flatMap { x -> (-2..2).map { y -> tile(x, y) } })
        val units = ArrayList<MapUnit>()
        for (slot in civ.ai.operations.formationDef(formationName)!!.slots.filter { it.required }.dropLast(leaveOut)) {
            fun fits(unitAi: String, unit: com.unciv.models.ruleset.unit.BaseUnit) =
                UnitAiType.ofXmlName(unitAi)?.let { UnitAiTypes.canHave(unit, it, rules) } == true
            val base = candidates.firstOrNull { fits(slot.primaryUnitAi, it) } ?: candidates.first { fits(slot.secondaryUnitAi, it) }
            units += game.addUnit(base.name, civ, if (base.isWaterUnit) sea.removeFirst() else land.removeFirst())
        }
        seeAll()
        return units
    }

    private fun invade(type: AiOperationType = AiOperationType.NAVAL_ATTACK): AiOperation =
        AiOperationLogic.addOperation(civ, type, enemy, enemyCity, city)

    /** CvAIOperationCityAttackCombined::Init: the army musters on the coastal water next to our port and its goal is the coastal water next to the enemy city */
    @Test
    fun `an invasion takes ships and land units that can embark, musters on the water next to our port and goes to the water next to the enemy city`() {
        val force = forceFor("MUFORMATION_NAVAL_INVASION", leaveOut = 1)
        val operation = invade()
        assertEquals("one required slot is empty", OperationState.RECRUITING_UNITS, operation.state)
        assertTrue(force.all { ops.armyOf(it) != null })
        val muster = tile(operation.musterTile!!)
        assertTrue("it musters on water next to our port: ${muster.position}", muster.isWater && muster.aerialDistanceTo(city.getCenterTile()) <= 2)
        val target = tile(operation.targetTile!!)
        assertTrue("it goes to the water next to the enemy city: ${target.position}", target.isWater && target.aerialDistanceTo(enemyCity.getCenterTile()) <= 2)
        assertEquals(target.position, operation.firstArmy!!.goalTile)
        assertEquals(city.id, operation.startCityId)
        assertEquals("no city lies on the water: its area is none", -1, operation.area)
        assertEquals("MUFORMATION_NAVAL_INVASION", operation.firstArmy!!.formation)
    }

    /** SetUpArmy: an army with all it needs gathers, and where its units already stand within the gather tolerance of one another it goes straight on to move */
    @Test
    fun `an invasion with all its required units is under way at once`() {
        val force = forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        assertTrue("under way: ${operation.state}", operation.state == OperationState.GATHERING_FORCES || operation.state == OperationState.MOVING_TO_TARGET)
        assertTrue(force.all { ops.armyOf(it) != null })
    }

    @Test
    fun `land units that cannot embark do not fill an invasion`() {
        civ.tech.unitsCanEmbark = false
        val force = forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertTrue(force.filter { it.baseUnit.isLandUnit }.all { ops.armyOf(it) == null })
        assertTrue(force.filter { it.baseUnit.isWaterUnit }.all { ops.armyOf(it) != null })
        assertEquals(force.count { it.baseUnit.isLandUnit }, operation.slotsToBuild.size)
    }

    /** Vox Populi (GrabUnitsFromTheReserves): ships that cannot enter the ocean are no recruits when muster point and target are on different waters */
    @Test
    fun `a coast-bound ship does not fill an invasion across two bodies of water, an ocean-going one does`() {
        for (y in -6..6) game.tileMap.getOrNull(7, y)?.let { game.setTileTerrain(it.position, "Grassland") } // a strip of land splits the sea
        civ.ai.invalidateTurnCaches()
        val coastal = ship("Quinquereme", 2, 0)
        val ocean = ship("Frigate", 2, 1)
        val operation = invade()
        assertNull("a Quinquereme cannot enter the ocean", ops.armyOf(coastal))
        assertNotNull(ops.armyOf(ocean))
        ops.kill(operation, OperationAbortReason.KILLED)
        for (y in -6..6) game.tileMap.getOrNull(7, y)?.let { game.setTileTerrain(it.position, "Coast") }
        civ.ai.invalidateTurnCaches()
        invade()
        assertNotNull("on one sea the formation takes it", ops.armyOf(coastal))
    }

    /** Puts the units of [force] on the water tiles of column x [x] (and the next column), the first on y=0, the others around */
    private fun sail(force: List<MapUnit>, x: Int) {
        val places = listOf(0, 1, -1, 2, -2).flatMap { y -> listOf(tile(x, y), tile(x + 1, y)) }
        val moving = force.zip(places)
        for ((unit, _) in moving) unit.removeFromTile()
        for ((unit, place) in moving) unit.putInTile(place)
        seeAll()
    }

    private fun centerDistance(operation: AiOperation, to: Tile): Int =
        AiOperationLogic.centerOfMass(civ, operation.firstArmy!!, sea = true)!!.aerialDistanceTo(to)

    /** Vox Populi's PlotArmyMovesCombat (CvTacticalAI.cpp 2517) moves the combined army: ExecuteGatherMoves puts every unit around the target of the turn, ships and embarked land units alike */
    @Test
    fun `an invasion on the move puts ships and the land units that embark on water around the turn target`() {
        val force = forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        seeAll()
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertTrue("every unit stays in reach of the others: ${force.map { it.name + it.getTile().position }}", force.all { unit -> force.any { it !== unit && it.getTile().aerialDistanceTo(unit.getTile()) <= 6 } })
        for (unit in force.filter { it.baseUnit.isWaterUnit }) assertTrue(unit.name, unit.getTile().isWater)
        assertTrue("land units embarked with the fleet", force.any { it.baseUnit.isLandUnit && it.getTile().isWater })
    }

    @Test
    fun `the army sails the sea route to the coastal water next to the enemy city`() {
        val force = forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        // everyone embarks and sails: put the force on water at our end of the channel
        sail(force, 1)
        val goal = tile(army.goalTile!!)
        val before = centerDistance(operation, goal)
        val (turnTarget, _) = OperationalMoves.computeCenterOfMassForTurn(civ, operation, army)!!
        assertTrue("the turn target is on the sea route: ${turnTarget.position}", turnTarget.isWater && turnTarget.aerialDistanceTo(goal) < before)
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals("on its way", OperationState.MOVING_TO_TARGET, operation.state)
        assertTrue("the force advanced: $before -> ${centerDistance(operation, goal)}", centerDistance(operation, goal) < before)
        assertTrue(force.filter { it.baseUnit.isWaterUnit }.all { it.getTile().isWater })
    }

    /** CvAIOperationMilitary::CheckTransitionToNextStage: within the deploy range of the goal the army has arrived; the city is a focus of the tactical AI, a naval invasion zone lets the landing units close on it */
    @Test
    fun `an invasion within the deploy range of its goal succeeds, with a naval invasion zone and a focus area at the city`() {
        val force = forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        sail(force, 11)
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.temporaryZones.single { it.position == enemyCity.getCenterTile().position }.navalInvasion)
        assertTrue(civ.ai.tactical.isInFocusArea(enemyCity.getCenterTile()))
    }

    @Test
    fun `an invasion still far from its goal keeps moving`() {
        val force = forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        sail(force, 1)
        assertFalse(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    /** CvTacticalAI::CheckForEnemiesNearArmy: ships in contact with the enemy fleet fight it with the combat simulation instead of sailing on */
    @Test
    fun `a fleet in contact with an enemy ship attacks it instead of sailing on`() {
        val ships = fleet()
        requestNavalAttack(0)
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        // closer to our city than to theirs: the original does not follow a contact into the water zone of the enemy's city
        val victim = game.addUnit("Trireme", enemy, tile(5, 0))
        seeAll()
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertTrue("the enemy ship was attacked: health ${victim.health}", victim.isDestroyed || victim.health < 100)
    }

    /** A fleet of ranged ships takes no city, so the want of a melee unit that ends a land army's march does not end its sail */
    @Test
    fun `a fleet of ranged ships goes on sailing`() {
        val ships = fleet()
        requestNavalAttack(0)
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        // the two Quinquereme, the fleet's melee ships, leave: two Galleass remain
        ops.removeFromArmy(ships[0])
        ops.removeFromArmy(ships[1])
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        seeAll()
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertNotEquals(OperationAbortReason.HALF_STRENGTH, operation.abortReason)
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    /** MilitaryAIHelpers::GetCurrentBestFormationTypeForCombinedAttack and ...ForPureNavalAttack: the late-game formations come after the Medieval era */
    @Test
    fun `every combined army and pure naval fleet uses the late game formation after the Medieval era`() {
        for (type in listOf(AiOperationType.NAVAL_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK, AiOperationType.CITY_STATE_NAVAL_ATTACK))
            assertEquals("MUFORMATION_NAVAL_INVASION", AiOperationLogic.formationName(civ, type))
        assertEquals("MUFORMATION_PURE_NAVAL_CITY_ATTACK", AiOperationLogic.formationName(civ, AiOperationType.PURE_NAVAL_CITY_ATTACK))
        for (each in listOf(civ, enemy)) each.tech.era = game.ruleset.eras["Industrial era"]!!
        for (type in listOf(AiOperationType.NAVAL_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK, AiOperationType.CITY_STATE_NAVAL_ATTACK))
            assertEquals("a city-state's too", "MUFORMATION_LATE_GAME_INVASION_FORCE", AiOperationLogic.formationName(civ, type))
        assertEquals("MUFORMATION_LATE_GAME_PURE_NAVAL_FORCE", AiOperationLogic.formationName(civ, AiOperationType.PURE_NAVAL_CITY_ATTACK))
        assertTrue(AiOperationType.NAVAL_ATTACK.isNavalInvasion && AiOperationType.NAVAL_ATTACK.mixedLandNaval)
    }

    /** CvAIOperationCityAttackCombined::Init: the goal is coastal water the original does not path to, so closed borders and the defender's ships do not hide it */
    @Test
    fun `an invasion's goal is the water next to the city even inside closed borders where the defender's ships stand`() {
        civ.getDiplomacyManager(enemy)!!.makePeace()
        // closed borders three tiles out, as a grown city has them, and its fleet on the waters next to it
        for (tile in enemyCity.getCenterTile().getTilesInDistance(3)) if (tile.getOwner() == null) enemyCity.expansion.takeOwnership(tile)
        for (water in enemyCity.getCenterTile().neighbors.filter { it.isWater }) game.addUnit("Quinquereme", enemy, water)
        forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade(AiOperationType.NAVAL_SNEAK_ATTACK)
        assertNotEquals(OperationState.ABORTED, operation.state)
        val goal = tile(operation.targetTile!!)
        assertTrue("goal ${goal.position}", goal.isWater && goal.aerialDistanceTo(enemyCity.getCenterTile()) <= 2)
        assertEquals(goal.position, operation.firstArmy!!.goalTile)
    }

    /** CvPlayer::GetClosestCoastalCityPair: for a target that is no coastal city the closest coastal cities of us and the enemy are used */
    @Test
    fun `a pure naval attack on an inland city goes for the closest pair of coastal cities`() {
        val inland = game.addCity(enemy, tile(16, 0))
        assertFalse(inland.isCoastal())
        forceFor("MUFORMATION_PURE_NAVAL_CITY_ATTACK")
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.PURE_NAVAL_CITY_ATTACK, enemy, inland, city)
        assertNotEquals(OperationState.ABORTED, operation.state)
        val goal = tile(operation.targetTile!!)
        assertTrue("the water by the coastal city at x=14, not by the inland one: ${goal.position}", goal.isWater && goal.aerialDistanceTo(enemyCity.getCenterTile()) <= 2)
    }

    /** CvAIOperation::CheckTransitionToNextStage's naval arrival: the enemy city is what the Intrigue and the tactical AI see as the target */
    @Test
    fun `the city an invasion goes for is found from the water it sails to`() {
        forceFor("MUFORMATION_NAVAL_INVASION")
        val operation = invade()
        assertEquals(enemyCity, AiOperationLogic.attackedCity(civ, operation))
    }
    // endregion

    // region Escorting embarked units

    private fun escortContext(vararg ships: com.unciv.logic.map.mapunit.MapUnit): com.unciv.logic.automation.unit.tactical.TacticalContext {
        seeAll()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return com.unciv.logic.automation.unit.tactical.TacticalContext(
            civ, map, danger, AutomationCache(), ships.toList(), com.unciv.logic.automation.unit.tactical.TacticalTargets.find(civ, map))
    }

    @Test
    fun `a warship goes beside the embarked unit that the enemy can attack, whether or not it has moved`() {
        val safe = game.addUnit("Warrior", civ, tile(3, 0))
        val endangered = game.addUnit("Warrior", civ, tile(3, 4))
        safe.currentMovement = 0f
        game.addUnit("Trireme", enemy, tile(5, 5)) // threatens the one at (3, 4)
        val escort = ship("Quinquereme", 5, 2)
        val ctx = escortContext(escort)
        com.unciv.logic.automation.unit.tactical.NavalMoves.plotEscortEmbarkedMoves(ctx)
        assertEquals(1, escort.getTile().aerialDistanceTo(endangered.getTile()))
        assertTrue(escort in ctx.cache.processedUnits)
    }

    @Test
    fun `the first embarked unit goes first when none is in danger, and a warship that has to heal stays`() {
        val first = game.addUnit("Warrior", civ, tile(3, 0))
        val second = game.addUnit("Warrior", civ, tile(3, 4))
        val hurt = ship("Quinquereme", 5, 2)
        hurt.health = 30
        val ctx = escortContext(hurt)
        com.unciv.logic.automation.unit.tactical.NavalMoves.plotEscortEmbarkedMoves(ctx)
        assertEquals("it needs to heal", tile(5, 2), hurt.getTile())
        assertFalse(hurt in ctx.cache.processedUnits)

        val escort = ship("Quinquereme", 5, 1)
        val ctx2 = escortContext(escort)
        com.unciv.logic.automation.unit.tactical.NavalMoves.plotEscortEmbarkedMoves(ctx2)
        assertEquals("the first embarked unit of the list is the one it protects", 1, escort.getTile().aerialDistanceTo(first.getTile()))
        assertTrue(second.getTile().aerialDistanceTo(escort.getTile()) > 1)
    }

    // endregion

    // region Nukes

    private fun atWarWithNukes() = game.addUnit("Atomic Bomb", civ, tile(0)).also { civ.ai.militaryStrategies[MilitaryOperations.AT_WAR] = 1 }

    @Test
    fun `a nuke is launched at an enemy that nuked us or that we nuked`() {
        atWarWithNukes()
        assertFalse(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        enemy.ai.diplomacy.memoryOf(civ).numTimesNuked = 1 // we were nuked by them... as the original reads it: they nuked us
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        enemy.ai.diplomacy.memoryOf(civ).numTimesNuked = 0
        civ.ai.diplomacy.memoryOf(enemy).numTimesNuked = 1
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    @Test
    fun `without nukes or at peace there is no launch`() {
        civ.ai.diplomacy.memoryOf(enemy).numTimesNuked = 1
        assertFalse("no nukes", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        atWarWithNukes()
        civ.getDiplomacyManager(enemy)!!.makePeace()
        assertFalse("at peace", MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    /** DoNuke reads the military strength without our boldness (VP's GetRawMilitaryStrengthComparedToUs) */
    private fun setStrength(level: StrengthLevel) {
        civ.ai.warStates.militaryStrengths[enemy.civID] = level
        civ.ai.warStates.rawMilitaryStrengths[enemy.civID] = level
    }

    /** CvMilitaryAI::DoNuke (Vox Populi): a war we will surely lose launches without a roll */
    @Test
    fun `a nuke is launched without a roll when the enemy is immense or we are nearly defeated`() {
        atWarWithNukes()
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 0)
        setStrength(StrengthLevel.Immense)
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        setStrength(StrengthLevel.Average)
        civ.ai.warStates.warStates[enemy.civID] = WarState.NearlyDefeated
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    /** CvMilitaryAI::DoNuke (Vox Populi): the reasons to roll, each enough on its own; without one there is no launch */
    @Test
    fun `a war that goes badly, a hated enemy, a conquest or an enemy close to victory rolls for the nuke`() {
        atWarWithNukes()
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 10) // 0..9 is always at most 10
        setStrength(StrengthLevel.Strong)
        civ.ai.warStates.warStates[enemy.civID] = WarState.Stalemate
        civ.ai.diplomacy.setOpinion(enemy, com.unciv.logic.automation.diplomacy.MajorCivOpinion.Competitor)
        assertFalse("no reason to roll", MilitaryOperations.shouldLaunchNuke(civ, enemy))

        setStrength(StrengthLevel.Powerful)
        assertTrue("powerful enemy", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        setStrength(StrengthLevel.Strong)

        civ.ai.warStates.warStates[enemy.civID] = WarState.Troubled
        assertTrue("troubled war", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        civ.ai.warStates.warStates[enemy.civID] = WarState.Defensive
        assertTrue("defensive war", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        civ.ai.warStates.warStates[enemy.civID] = WarState.Stalemate

        civ.ai.diplomacy.setOpinion(enemy, com.unciv.logic.automation.diplomacy.MajorCivOpinion.Enemy)
        assertTrue("an enemy in our opinion", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        civ.ai.diplomacy.setOpinion(enemy, com.unciv.logic.automation.diplomacy.MajorCivOpinion.Competitor)

        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertTrue("going for world conquest", MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    /** CvFlavorManager::GetPersonalityFlavorForDiplomacy: the roll is won when 0..9 is at most the flavor, which is at least 1 */
    @Test
    fun `the roll uses the use nuke flavor on a scale of 1 to 10`() {
        atWarWithNukes()
        setStrength(StrengthLevel.Powerful)
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 0)
        assertEquals(1, MilitaryOperations.useNukeFlavorForDiplomacy(civ))
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 14)
        assertEquals(10, MilitaryOperations.useNukeFlavorForDiplomacy(civ))
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 0)
        val launches = (1..200).count { game.gameInfo.turns = it; MilitaryOperations.shouldLaunchNuke(civ, enemy) }
        assertTrue("about one turn in five rolls 0 or 1: $launches of 200", launches in 20..70)
    }

    @Test
    fun `the nuke goes for the enemy city with the best blast value in range`() {
        // Atomic Bomb range 10; the nuke in our city reaches both of these cities, not the far one
        val nuke = game.addUnit("Atomic Bomb", civ, tile(0))
        val big = game.addCity(enemy, tile(-1, 8))
        val small = game.addCity(enemy, tile(0, 7))
        big.population.setPopulation(8)
        small.population.setPopulation(3)
        val probe = AiOperation().also { it.type = AiOperationType.NUKE_ATTACK; it.enemyCivId = enemy.civID }
        val best = NavalOperationLogic.findBestNukeTarget(civ, probe)!!
        assertEquals(nuke, best.first)
        assertEquals("big ${NavalOperationLogic.nukeCityValue(civ, nuke, big)} small ${NavalOperationLogic.nukeCityValue(civ, nuke, small)} chose ${best.second.getCenterTile().position}", big, best.second)
    }

    @Test
    fun `a blast that would hit a third party or our own units scores low, and a capital counts double`() {
        val nuke = game.addUnit("Atomic Bomb", civ, tile(0))
        val clean = NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity)
        val bystander = neutralCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(bystander)
        val bystanderCity = game.addCity(bystander, tile(16, 0))
        game.addUnit("Warrior", bystander, bystanderCity.getCenterTile())
        assertTrue("a third party's unit within the blast: -1000", NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity) < clean - 900)
        // our own unit within the blast costs 2, doubled with the rest of the value in a capital
        val withBystander = NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity)
        val ours = game.addUnit("Warrior", civ, tile(14, 1))
        val withOurs = NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity)
        assertEquals("$clean $withBystander $withOurs", 4, withBystander - withOurs)
    }

    @Test
    fun `a nuke operation uses the nuke at once and ends in success`() {
        val nuke = game.addUnit("Atomic Bomb", civ, tile(0))
        game.addCity(enemy, tile(0, 7)).population.setPopulation(6)
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NUKE_ATTACK, enemy)
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue("the bomb self-destructs", nuke.isDestroyed)
    }

    @Test
    fun `a nuke operation with no nuke in range of an enemy city aborts`() {
        game.addCity(civ, tile(-13))
        game.addUnit("Atomic Bomb", civ, tile(-13))
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NUKE_ATTACK, enemy)
        assertEquals(OperationState.ABORTED, operation.state)
    }
    // endregion
}
