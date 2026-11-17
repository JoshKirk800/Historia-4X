package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.OperationalMoves
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import kotlin.math.abs

/**
 *  Decision-level checks of the operation and army port (CvAIOperation, CvArmyAI, the operation side of CvMilitaryAI) against the
 *  DLL formulas with the BNW XML values: the city demand for open slots, recruiting from the reserves, the state machine, launch and abort
 *  conditions, attack readiness with the land reserve, and the target score.
 */
@RunWith(GdxTestRunner::class)
class OperationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        enemyCity = game.addCity(enemy, game.getTile(HexCoord(8, 0)))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    /** A military unit of the civilization; on the first tile around the capital that has none unless [at] is given (one military unit per tile) */
    private fun unit(name: String, at: HexCoord? = null): MapUnit {
        val tile = at?.let { game.getTile(it) } ?: game.getTile(HexCoord(0, 0)).getTilesInDistance(4).first { it.militaryUnit == null && it.civilianUnit == null }
        return game.addUnit(name, civ, tile)
    }

    /** Puts [units] on separate tiles around [center] */
    private fun teleport(units: List<Pair<Int, MapUnit>>, center: HexCoord) {
        val tiles = game.getTile(center).getTilesInDistance(2).filter { it.militaryUnit == null || units.any { (_, u) -> u === it.militaryUnit } }.toList()
        for ((index, pair) in units.withIndex()) { pair.second.removeFromTile(); pair.second.putInTile(tiles[index]) }
    }

    private fun closeDefense() = AiOperationLogic.addOperation(civ, AiOperationType.CITY_CLOSE_DEFENSE, enemy)

    private fun slotCandidate(): ConstructionAutomation.Buildable? =
        ConstructionAutomation(city.cityConstructions).getBuildables().firstOrNull { it.slotOffer != null }

    private fun threatenSecondCity(): City {
        val second = game.addCity(civ, game.getTile(HexCoord(-3, 0)))
        civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addUnit("Warrior", enemy, game.getTile(HexCoord(-4, 0)))
        civ.ai.invalidateTurnCaches()
        return second
    }

    /** What Vox Populi's CheckUnitBuildSanity wants before a city trains a combat unit: a second building and a treasury that is not broke */
    private fun developCity() {
        city.cityConstructions.addBuilding("Monument")
        civ.addGold(100)
    }

    //region City demand (CvCity::GetUnitForOperation)

    @Test
    fun `a city offers the unit of the next open slot with the operation unit weight`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        developCity()
        closeDefense()
        val candidate = slotCandidate()
        assertNotNull(candidate)
        // Required slots COUNTER/DEFENSE and RANGED/DEFENSE: the last open one is built first; no archery yet, so the secondary AI DEFENSE
        assertEquals("Warrior", candidate!!.construction.name)
        assertEquals(UnitAiType.DEFENSE, candidate.slotOffer!!.unitAi)
        assertEquals(2, candidate.slotOffer!!.slot.slotId)
        val warrior = game.ruleset.units["Warrior"]!!
        // 5000 (Vox Populi's AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT) + 250 x (OFFENSE 4 + 0 skipped over) + the unit's own weight
        assertEquals(5000 + 250 * 4 + ProductionAi.unitWeight(city, warrior), candidate.weight)
    }

    @Test
    fun `every offer that is not taken raises the next one by the flavor multiplier`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        developCity()
        closeDefense()
        val first = slotCandidate()!!.weight
        assertEquals(1, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
        assertEquals(first + 250, slotCandidate()!!.weight)
        assertEquals(2, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
    }

    @Test
    fun `units for an attack army weigh 750 plus the offense flavor multiplier, without their own weight`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        developCity()
        civ.ai.military.requestArmy(bySea = false)
        civ.tech.addTechnology("Archery")
        val unit = MilitaryOperations.unitForArmy(city)!!
        assertEquals("Archer", unit.name)
        val buildables = ConstructionAutomation(city.cityConstructions).getBuildables()
        val army = buildables.first { it.construction.name == "Archer" && it.slotOffer == null }
        assertEquals(750 + 250 * 4, army.weight)
    }

    @Test
    fun `only the muster city offers a slot, takes it off the list, and gives it back when it builds something else`() {
        val second = game.addCity(civ, game.getTile(HexCoord(4, 0)))
        val operation = closeDefense()
        assertEquals(2, operation.slotsToBuild.size)
        val musterCity = game.getTile(operation.musterTile!!).getCity()!!
        val other = if (musterCity == city) second else city
        // CvCityStrategyAI::ChooseProduction and CvCity::GetUnitForOperation: bCitySameAsMuster
        assertNull(ops.nextUnitForOperationSlot(other))
        assertFalse(ops.isNextSlotMusteredIn(other))
        assertTrue(ops.isNextSlotMusteredIn(musterCity))
        val offer = ops.nextUnitForOperationSlot(musterCity)!!
        musterCity.cityConstructions.setCurrentConstruction("Warrior")
        ops.commitToBuild(musterCity, offer, 3)
        assertTrue(ops.isBuildingUnitForOperation(musterCity))
        assertEquals(1, operation.slotsToBuild.size)
        // The muster city is offered the other slot, not the same one
        assertTrue(ops.nextUnitForOperationSlot(musterCity)!!.slot != offer.slot)
        // The estimated arrival of the unit: the turns plus half the distance from the city to the army's rally point
        val army = operation.firstArmy!!
        val rally = game.gameInfo.tileMap[army.position!!]
        assertEquals(game.gameInfo.turns + 3 + musterCity.getCenterTile().aerialDistanceTo(rally) / 2, army.slots[offer.slot.slotId].estimatedTurn)
        musterCity.cityConstructions.setCurrentConstruction("Monument")
        ops.refreshCommitments()
        assertFalse(ops.isBuildingUnitForOperation(musterCity))
        assertEquals(2, operation.slotsToBuild.size)
    }

    @Test
    fun `the unit a city trained for a slot joins the army with the slot's unit AI, and the last one starts the gathering`() {
        val operation = closeDefense()
        val warrior = game.ruleset.units["Warrior"]!!
        for (index in 0 until 2) {
            val offer = ops.nextUnitForOperationSlot(city)!!
            city.cityConstructions.setCurrentConstruction("Warrior")
            ops.commitToBuild(city, offer, 2)
            assertEquals(OperationState.RECRUITING_UNITS, operation.state)
            val trained = unit(warrior.name)
            assertTrue(ops.finishedBuilding(trained, city))
            assertEquals(trained.id, operation.firstArmy!!.slots[offer.slot.slotId].unitId)
            assertEquals(UnitAiType.DEFENSE, UnitAiTypes.current(trained))
            assertEquals(index == 1, operation.state == OperationState.GATHERING_FORCES)
        }
        assertEquals(ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP, operation.firstArmy!!.state)
        // A unit trained for nothing is not part of any operation
        assertFalse(ops.finishedBuilding(unit("Warrior"), city))
    }

    @Test
    fun `the final unit bought for an operation fills the last slot, not the one its city committed to`() {
        civ.tech.addTechnology("Bronze Working")
        val operation = closeDefense()
        val offer = ops.nextUnitForOperationSlot(city)!!
        // The city trains a spearman for its slot, the very unit the purchase for the other (COUNTER) slot recommends
        val spearman = game.ruleset.units["Spearman"]!!
        city.cityConstructions.setCurrentConstruction(spearman.name)
        ops.commitToBuild(city, AiOperations.SlotOffer(offer.slot, spearman, UnitAiType.DEFENSE, true), 3)
        val last = operation.slotsToBuild.single()
        assertTrue(operation.hasOneMoreSlotToFill())
        civ.addGold(1000)
        assertTrue(AiOperationLogic.buyFinalUnit(civ, operation))
        // CvMilitaryAI::BuyEmergencyUnit creates the unit without bUseToSatisfyOperation: the city still trains its own one
        assertTrue(ops.isBuildingUnitForOperation(city))
        assertTrue(operation.slotsToBuild.isEmpty())
        assertEquals(-1, operation.firstArmy!!.slots[offer.slot.slotId].unitId)
        assertTrue(operation.firstArmy!!.slots[last.slotId].unitId != -1)
    }

    @Test
    fun `an idle city that chooses its production while the final unit is bought does not take the slot`() {
        civ.tech.addTechnology("Bronze Working")
        val other = game.addCity(civ, game.getTile(HexCoord(4, 0)))
        val operation = closeDefense()
        val offer = ops.nextUnitForOperationSlot(other)!!
        other.cityConstructions.setCurrentConstruction("Warrior")
        ops.commitToBuild(other, offer, 3)
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        civ.addGold(1000)
        assertTrue(AiOperationLogic.buyFinalUnit(civ, operation))
        assertTrue(operation.slotsToBuild.isEmpty())
        assertFalse(ops.isBuildingUnitForOperation(city))
    }
    //endregion

    //region Recruiting from the reserves

    @Test
    fun `an operation takes the free units that fit its slots and starts gathering when all required slots are filled`() {
        val warrior = unit("Warrior")
        val archer = unit("Archer", HexCoord(1, 0))
        val operation = closeDefense()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertTrue(operation.slotsToBuild.isEmpty())
        assertEquals(operation.firstArmy, ops.armyOf(warrior))
        assertEquals(operation.firstArmy, ops.armyOf(archer))
        assertTrue(ops.canTacticalAiInterrupt(warrior)) // assembling armies may be interrupted
    }

    @Test
    fun `a unit that may paradrop is no reserve, it is the tactical AI's for pillaging`() {
        val drop = game.createUnitPromotion("May Paradrop to [Land] tiles up to [5] tiles away")
        val walker = unit("Warrior", HexCoord(1, 0))
        val paratrooper = unit("Warrior", HexCoord(0, 1))
        paratrooper.promotions.addPromotion(drop.name, true)
        closeDefense()
        assertNotNull("the plain unit of the same kind is recruited", ops.armyOf(walker))
        assertNull(ops.armyOf(paratrooper))
    }

    @Test
    fun `scouts, wounded units and units that just finished an operation are no reserves`() {
        val explorer = unit("Warrior")
        UnitAiTypes.set(explorer, UnitAiType.EXPLORE)
        val wounded = unit("Archer", HexCoord(1, 0))
        wounded.health = 79
        val deployed = unit("Warrior", HexCoord(0, 1))
        ops.deployFromOperationTurns[deployed.id] = game.gameInfo.turns
        val operation = closeDefense()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(2, operation.slotsToBuild.size)
        assertNull(ops.armyOf(explorer)); assertNull(ops.armyOf(wounded)); assertNull(ops.armyOf(deployed))
        // The turn after the cooldown is over, AI_TACTICAL_MAP_TEMP_ZONE_TURNS (4) turns after the deploy turn, the unit is free again
        game.gameInfo.turns += 5
        assertTrue(AiOperationLogic.isFreeForOperation(civ, deployed, naval = false))
    }

    @Test
    fun `losing units of an operation that is still recruiting asks for them again, below half strength it aborts`() {
        val operation = closeDefense()
        ops.nextUnitForOperationSlot(city)
        val warrior = unit("Warrior")
        val archer = unit("Archer")
        ops.addUnit(operation.firstArmy!!, warrior, 0)
        ops.addUnit(operation.firstArmy!!, archer, 2)
        operation.slotsToBuild.clear()
        operation.state = OperationState.RECRUITING_UNITS
        warrior.destroy()
        ops.reconcileUnits()
        assertEquals(listOf(SlotRef(operation.id, operation.firstArmy!!.id, 0)), operation.slotsToBuild)
        // Gathering: the formation has 4 slots, half strength is 2 units
        operation.state = OperationState.GATHERING_FORCES
        operation.slotsToBuild.clear()
        archer.destroy()
        ops.reconcileUnits()
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.HALF_STRENGTH, operation.abortReason)
    }

    @Test
    fun `the AI does not scrap an obsolete unit that is in an army`() {
        val base = game.createBaseUnit("Sword")
        base.movement = 2
        base.strength = 1
        base.obsoleteTech = "Pottery"
        civ.tech.addTechnology("Pottery")
        val obsolete = unit(base.name)
        assertEquals(obsolete, civ.ai.military.findUnitToScrap(civ, isLand = true)?.first)
        // CvMilitaryAI::FindUnitToScrap: canUseForAIOperation - a unit in an army is skipped; the game forcing a disband (the deficit) does not look
        val operation = closeDefense()
        ops.nextUnitForOperationSlot(city)
        ops.addUnit(operation.firstArmy!!, obsolete, 0)
        assertNull(civ.ai.military.findUnitToScrap(civ, isLand = true))
        assertEquals(obsolete, civ.ai.military.findBestUnitToScrap(civ, isLand = true)?.first)
    }

    @Test
    fun `a city that chose a process also builds the unit of an open operation slot`() {
        closeDefense()
        civ.ai.military.numberOfTimesOpsBuildSkippedOver = 3
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Gold.name)
        ConstructionAutomation(city.cityConstructions).fallThroughToOperationUnit()
        // CvCityStrategyAI::ChooseProduction: the process case has no break; the unit goes in front, the process stays behind it
        assertEquals(listOf("Warrior", PerpetualConstruction.Gold.name), city.cityConstructions.constructionQueue)
        assertEquals("Warrior", ops.commitmentOf(city)?.unitName)
        assertEquals(0, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
    }

    @Test
    fun `a process with no slot open leaves the queue alone and still resets the skipped-over count`() {
        civ.ai.military.numberOfTimesOpsBuildSkippedOver = 3
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Gold.name)
        ConstructionAutomation(city.cityConstructions).fallThroughToOperationUnit()
        assertEquals(listOf(PerpetualConstruction.Gold.name), city.cityConstructions.constructionQueue)
        assertEquals(0, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
    }
    //endregion

    //region Operations against cities

    private fun fillAttackReserves(archers: Int = 1, warriors: Int = 2) {
        repeat(archers) { unit("Archer") }
        repeat(warriors) { unit("Warrior") }
    }

    private fun attack(type: AiOperationType = AiOperationType.BASIC_CITY_ATTACK) =
        AiOperationLogic.addOperation(civ, type, enemy, enemyCity, city)

    @Test
    fun `a city attack musters at the start city and heads for a few plots short of the target`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertEquals(city.getCenterTile().position, operation.musterTile)
        assertEquals(enemyCity.getCenterTile().position, operation.targetTile)
        // GetDeployRange 5 / 2 = 2 plots from the end of the route
        val goal = game.gameInfo.tileMap[operation.firstArmy!!.goalTile!!]
        assertEquals(2, goal.aerialDistanceTo(enemyCity.getCenterTile()))
        assertEquals("MUFORMATION_BASIC_CITY_ATTACK_FORCE", operation.firstArmy!!.formation)
    }

    @Test
    fun `an attack without the required units recruits, and only the required slots are built`() {
        val operation = attack()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        // Required: CITY_BOMBARD/RANGED, ATTACK/DEFENSE, ATTACK/COUNTER
        assertEquals(3, operation.slotsToBuild.size)
        val optional = operation.firstArmy!!.slots.count { it.estimatedTurn == ArmySlot.NOT_INCLUDED }
        assertEquals(4, optional)
    }

    @Test
    fun `progress from the muster to the target counts the steps of the route`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertEquals(0, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
        operation.state = OperationState.MOVING_TO_TARGET
        val army = operation.firstArmy!!
        // The goal is 6 steps from the muster: an army centered 3 steps along is half way
        val units = AiOperationLogic.armyUnits(civ, army)
        teleport(units, HexCoord(3, 0))
        assertEquals(50, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
        // Within 2 steps of the goal the army counts as there
        teleport(units, HexCoord(5, 0))
        assertEquals(100, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
        operation.state = OperationState.AT_TARGET
        assertEquals(100, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
    }

    @Test
    fun `an army that gathered moves out, and one within the deploy range of its target hands over and sets the war goal`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation)) // all at the muster point
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertEquals(ArmyState.MOVING_TO_DESTINATION, operation.firstArmy!!.state)
        teleport(AiOperationLogic.armyUnits(civ, operation.firstArmy!!), HexCoord(4, 0))
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.diplomacy.memoryOf(enemy).musteringForAttack)
        assertEquals(1, civ.ai.tactical.temporaryZones.size)
        // The sweep ends the operation as a success and marks its units as deployed
        val unitIds = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second.id }
        ops.sweep()
        assertTrue(ops.operations.isEmpty())
        assertTrue(unitIds.all { ops.deployFromOperationTurn(it) == game.gameInfo.turns })
    }

    /** Departure from CvArmyAI::GetFurthestUnitDistance: a general that keeps to the army's center by its own scoring does not hold the army back */
    @Test
    fun `an army gathers at its muster point whatever the distance of its general`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val general = unit("Great General")
        val operation = attack()
        val army = operation.firstArmy!!
        assertTrue("the general fills the support slot", AiOperationLogic.armyUnits(civ, army).any { it.second === general })
        val soldiers = AiOperationLogic.armyUnits(civ, army).filter { it.second !== general }
        teleport(soldiers, city.getCenterTile().position)
        general.removeFromTile()
        general.putInTile(game.getTile(HexCoord(-4, 0)))
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    /** Departure from CvArmyAI::GetCenterOfMass: a general left behind does not hold the army's center back */
    @Test
    fun `an army's center of mass is where its fighting units are, wherever its general stands`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val general = unit("Great General")
        val operation = attack()
        val army = operation.firstArmy!!
        val soldiers = AiOperationLogic.armyUnits(civ, army).filter { it.second !== general }
        teleport(soldiers, HexCoord(6, 0))
        general.removeFromTile()
        general.putInTile(game.getTile(HexCoord(-11, 0)))
        // all soldiers within 2 of (6,0); counting a general 17 tiles behind would put the center at least 3 tiles back
        val center = AiOperationLogic.centerOfMass(civ, army)!!
        assertTrue("${center.position}", center.aerialDistanceTo(game.getTile(HexCoord(6, 0))) <= 2)
    }

    @Test
    fun `an army unit with no path to its muster point stays where it is`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        val (_, stranded) = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).first()
        val island = game.getTile(HexCoord(-8, 0))
        for (neighbor in island.neighbors) game.setTileTerrain(neighbor.position, "Ocean") // no embarking without Optics
        stranded.removeFromTile(); stranded.putInTile(island)
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(island, stranded.getTile())
    }

    @Test
    fun `a city attack ends when the target city is no longer the enemy's`() {
        fillAttackReserves()
        val operation = attack()
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        enemyCity.moveToCiv(civ)
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
    }

    /** Vox Populi's failsafe in CvAIOperation::ShouldAbort: an operation older than 42 turns aborts */
    @Test
    fun `an operation still running 42 turns after it started times out`() {
        fillAttackReserves()
        val operation = attack()
        game.gameInfo.turns += AiOperationLogic.TIMEOUT_TURNS
        assertFalse("42 turns is not yet too long", AiOperationLogic.shouldAbort(civ, operation))
        game.gameInfo.turns++
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.TIMED_OUT, operation.abortReason)
    }

    @Test
    fun `an operation gathering at a target that was captured aborts`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        enemyCity.moveToCiv(civ)
        AiOperationLogic.checkOnTarget(civ, operation)
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.TARGET_ALREADY_CAPTURED, operation.abortReason)
    }

    @Test
    fun `the formation of a sneak attack is the early rush for a bold civilization in the first 50 turns`() {
        game.gameInfo.turns = 10
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 5)
        assertEquals("MUFORMATION_EARLY_RUSH", AiOperationLogic.formationName(civ, AiOperationType.SNEAK_CITY_ATTACK))
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 4)
        assertEquals("MUFORMATION_BASIC_CITY_ATTACK_FORCE", AiOperationLogic.formationName(civ, AiOperationType.SNEAK_CITY_ATTACK))
    }
    //endregion

    //region Attack readiness and launching (CvMilitaryAI::IsAttackReady, RequestSpecificAttack)

    @Test
    fun `an attack needs its land units to fit into the reserve beyond the mandatory one`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        civ.ai.military.updateBaseData(civ)
        val target = MilitaryTarget(city, enemyCity)
        // Three units fill the three required slots but all of them are needed for defense
        assertTrue(MilitaryOperations.landReservesAvailable(civ) < 3)
        assertFalse(MilitaryOperations.requestSpecificAttack(civ, target, 0))
        assertTrue(ops.operations.isEmpty())
        repeat(6) { unit("Warrior") }
        civ.ai.military.updateBaseData(civ)
        assertTrue(MilitaryOperations.landReservesAvailable(civ) >= 3)
        assertTrue(MilitaryOperations.requestSpecificAttack(civ, target, 0))
        assertEquals(1, ops.numOperationsOfType(AiOperationType.BASIC_CITY_ATTACK))
    }

    @Test
    fun `units in armies do not count as reserves`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        repeat(6) { unit("Warrior") }
        civ.ai.military.updateBaseData(civ)
        assertTrue(MilitaryOperations.requestSpecificAttack(civ, MilitaryTarget(city, enemyCity), 0))
        civ.ai.military.updateBaseData(civ)
        assertEquals(ops.operations.first().firstArmy!!.numSlotsFilled(), civ.ai.military.numLandUnitsInArmies)
        // The running attack is past recruiting: IsAttackReady says no to a second one of the same type
        assertFalse(MilitaryOperations.isAttackReady(civ, "MUFORMATION_BASIC_CITY_ATTACK_FORCE", AiOperationType.BASIC_CITY_ATTACK))
    }

    @Test
    fun `an attack that is one required unit short starts if the civilization is willing to build one`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        unit("Warrior")
        repeat(8) { unit("Archer") } // two fill the bombard slots, the others are spare land units for the reserve
        civ.ai.military.updateBaseData(civ)
        val fillable = MilitaryOperations.numberOfFillableSlots(civ, ops.formationDef("MUFORMATION_BASIC_CITY_ATTACK_FORCE")!!, false)
        assertEquals(1, fillable.required - fillable.filled) // the ATTACK/COUNTER slot
        assertTrue(fillable.landReservesUsed <= MilitaryOperations.landReservesAvailable(civ))
        assertFalse(MilitaryOperations.requestSpecificAttack(civ, MilitaryTarget(city, enemyCity), 0))
        assertTrue(MilitaryOperations.requestSpecificAttack(civ, MilitaryTarget(city, enemyCity), 1))
        val operation = ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)!!
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(1, operation.slotsToBuild.size)
    }

    @Test
    fun `a sneak attack goes at a potential attack target and asks for an army while not ready`() {
        // RequestCityAttack: without a potential attack target there is nothing to attack and no army to build
        assertFalse(MilitaryOperations.requestSneakAttack(civ, enemy))
        assertEquals(0, civ.ai.military.numLandAttacksRequested)
        civ.ai.military.updateAttackTargets(civ)
        assertEquals(listOf(enemyCity), MilitaryTargets.cityAttackTargets(civ, enemy, careful = false).map { it.targetCity })
        // Not ready: the land army for the first target is requested
        assertFalse(MilitaryOperations.requestSneakAttack(civ, enemy))
        assertEquals(1, civ.ai.military.numLandAttacksRequested)
        fillAttackReserves()
        repeat(6) { unit("Warrior") }
        civ.ai.military.updateBaseData(civ)
        assertTrue(MilitaryOperations.requestSneakAttack(civ, enemy))
        assertEquals(enemyCity.location, ops.firstOfType(AiOperationType.SNEAK_CITY_ATTACK)!!.targetTile)
    }

    @Test
    fun `at war a major attacks its preferred target unless the war goes badly`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        repeat(6) { unit("Warrior") }
        civ.ai.military.updateBaseData(civ)
        civ.ai.military.updateAttackTargets(civ)
        val targets = civ.ai.military.potentialAttackTargets
        assertTrue("preferred: ${targets.map { it.targetCity.name to it.preferred }}", targets.any { it.preferred && it.targetCity == enemyCity })
        // DoCityAttacks: troubled or worse (ours: defensive or worse) starts nothing
        civ.ai.warStates.warStates[enemy.civID] = WarState.Defensive
        MilitaryOperations.doCityAttacks(civ, enemy)
        assertTrue(ops.operations.isEmpty())
        civ.ai.warStates.warStates[enemy.civID] = WarState.Stalemate
        MilitaryOperations.doCityAttacks(civ, enemy)
        assertEquals(enemyCity.location, ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)?.targetTile)
    }
    //endregion

    //region The stops of UpdateOperations (Vox Populi's CvMilitaryAI::UpdateOperations, CheckLandDefenses)

    @Test
    fun `a peace treaty ends every operation against the enemy`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        attack()
        closeDefense()
        assertEquals(2, ops.operations.size)
        val diplomacy = civ.getDiplomacyManager(enemy)!!
        diplomacy.makePeace()
        diplomacy.trades.add(Trade().apply { ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10)) })
        assertTrue(diplomacy.turnsToPeaceTreaty() > 0)
        MilitaryOperations.updateOperations(civ)
        assertTrue(ops.operations.isEmpty())
    }

    @Test
    fun `at peace with war allowed an attack waits for its war`() {
        fillAttackReserves()
        val operation = attack()
        MilitaryOperations.updateOperations(civ)
        assertTrue(operation in ops.operations)
    }

    @Test
    fun `a war going badly ends the attacks on that enemy only`() {
        val other = neutralCiv()
        game.addCity(other, game.getTile(HexCoord(0, 8)))
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        civ.getDiplomacyManager(other)!!.declareWar()
        fillAttackReserves()
        val onEnemy = attack()
        val onOther = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, other, other.cities.first(), city)
        civ.ai.warStates.warStates[enemy.civID] = WarState.Stalemate
        civ.ai.warStates.warStates[other.civID] = WarState.Stalemate
        MilitaryOperations.updateOperations(civ)
        assertTrue(onEnemy in ops.operations && onOther in ops.operations)
        // CheckLandDefenses: defensive against one enemy stops our attacks on it
        civ.ai.warStates.warStates[enemy.civID] = WarState.Defensive
        MilitaryOperations.updateOperations(civ)
        assertFalse(onEnemy in ops.operations)
        assertTrue(onOther in ops.operations)
        // nearly defeated by one: our attacks on anyone
        civ.ai.warStates.warStates[enemy.civID] = WarState.NearlyDefeated
        MilitaryOperations.updateOperations(civ)
        assertFalse(onOther in ops.operations)
    }
    //endregion

    //region City defense operations (CvAIOperationCityCloseDefense)

    @Test
    fun `the defense operation targets the most threatened city, else the city nearest the enemy capital`() {
        val second = threatenSecondCity()
        civ.ai.invalidateTurnCaches()
        assertTrue(civ.ai.plotDanger.cityThreat(second) > 0)
        assertEquals(second, CityThreat.mostThreatenedCity(civ))
        assertEquals(second.getCenterTile().position, closeDefense().targetTile)
        // Without a threat: the city closest to the enemy's capital (the capital at (0, 0) is nearer to (8, 0) than (-3, 0))
        game.gameInfo.tileMap[HexCoord(-4, 0)].getUnits().toList().forEach { it.destroy() }
        civ.ai.invalidateTurnCaches()
        assertNull(CityThreat.mostThreatenedCity(civ))
        ops.operations.clear()
        assertEquals(city.getCenterTile().position, closeDefense().targetTile)
    }
    //endregion

    //region Target selection (CvMilitaryAI::ScoreTarget, EvaluateMilitaryApproaches, ShouldAttackBySea)

    private fun target(path: Int = 5, bySea: Boolean = false): MilitaryTarget {
        val target = MilitaryTarget(city, enemyCity)
        target.musterNearbyUnitPower = 100000
        target.targetNearbyUnitPower = 0
        target.pathLength = path
        target.attackBySea = bySea
        return target
    }

    private fun score(target: MilitaryTarget, type: AiOperationType = AiOperationType.BASIC_CITY_ATTACK) = MilitaryTargets.scoreTarget(civ, target, type)

    @Test
    fun `a shorter route scores 16, 8, 4 or 2 times the base`() {
        val short = score(target(9))
        assertTrue(abs(short - 2 * score(target(10))) <= 2)
        assertTrue(abs(score(target(10)) - 2 * score(target(15))) <= 2)
        assertTrue(abs(score(target(15)) - 2 * score(target(20))) <= 2)
        assertTrue(short > 0)
    }

    @Test
    fun `a muster city with an Airport doubles the score of a land target, not of one reached by sea`() {
        val land = score(target(25))
        val sea = score(target(25, bySea = true))
        assertTrue(land > 0 && sea > 0)
        city.cityConstructions.addBuilding("Airport")
        assertTrue("land ${score(target(25))} plain $land", abs(score(target(25)) - 2 * land) <= 2)
        assertEquals(sea, score(target(25, bySea = true)))
    }

    @Test
    fun `a sneak attack cubes the distance factor, a recapture and the original capital multiply the score`() {
        enemyCity.isOriginalCapital = false
        val plain = score(target())
        assertTrue("sneak ${score(target(9), AiOperationType.SNEAK_CITY_ATTACK)} plain ${score(target(9))}",
            score(target(9), AiOperationType.SNEAK_CITY_ATTACK) > 100 * score(target(9)))
        enemyCity.isOriginalCapital = true
        assertTrue("capital ${score(target())} plain $plain", abs(score(target()) - plain * 250 / 100) <= 2)
        enemyCity.isOriginalCapital = false
        enemyCity.foundingCivObject = civ
        assertTrue("recapture ${score(target())} plain $plain", abs(score(target()) - plain * 150 / 100) <= 2)
    }

    @Test
    fun `a city another operation already heads for scores a tenth`() {
        val plain = score(target())
        val operation = attack()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertTrue(abs(score(target()) - plain / 10) <= 2)
    }

    @Test
    fun `the force ratio is capped at ten times and weighs the score`() {
        val strong = target(); strong.musterNearbyUnitPower = 10_000_000
        val capped = score(strong)
        val weak = target(); weak.musterNearbyUnitPower = 1
        assertTrue(score(weak) < capped / 100)
        assertTrue(capped > 0)
    }

    @Test
    fun `approaches are counted by the unusable tiles around the city`() {
        val center = enemyCity.getCenterTile()
        assertEquals(CityAttackApproach.UNRESTRICTED, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, false))
        center.neighbors.take(3).forEach { game.setTileTerrain(it.position, "Coast") }
        // Water is blocked unless the attack comes by sea
        assertEquals(CityAttackApproach.NEUTRAL, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, false))
        assertEquals(CityAttackApproach.UNRESTRICTED, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, true))
        center.neighbors.drop(3).forEach { game.setTileTerrain(it.position, "Coast") }
        assertEquals(CityAttackApproach.NONE, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, false))
    }

    @Test
    fun `a land route more than twice the straight distance or none at all sends the attack by sea`() {
        civ.tech.addTechnology("Optics") // units can embark
        val short = target(); MilitaryTargets.shouldAttackBySea(civ, short, 10)
        assertFalse(short.attackBySea); assertEquals(10, short.pathLength)
        val long = target(); MilitaryTargets.shouldAttackBySea(civ, long, 17) // the cities are 8 apart
        assertTrue(long.attackBySea); assertEquals(8, long.pathLength)
        val none = target(); MilitaryTargets.shouldAttackBySea(civ, none, -1)
        assertTrue(none.attackBySea)
    }

    @Test
    fun `without embarkation a missing land route calls the attack off`() {
        val none = target(); MilitaryTargets.shouldAttackBySea(civ, none, -1)
        assertFalse(none.attackBySea); assertEquals(-1, none.pathLength)
    }

    @Test
    fun `the best attack target is found over the land route of the step finder`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        unit("Warrior")
        val (target, score) = MilitaryTargets.findBestAttackTarget(civ, AiOperationType.BASIC_CITY_ATTACK, enemy)!!
        assertEquals(enemyCity, target.targetCity)
        assertEquals(city, target.musterCity)
        assertFalse(target.attackBySea)
        assertEquals(8, target.pathLength)
        assertTrue(score > 0)
    }

    @Test
    fun `no revealed city of the enemy, no target`() {
        for (tile in game.gameInfo.tileMap.tileList) tile.setExplored(civ, false)
        assertNull(MilitaryTargets.findBestAttackTarget(civ, AiOperationType.BASIC_CITY_ATTACK, enemy))
    }
    //endregion

    //region Settlers

    /** Grassland everywhere, so that settle sites exist: the civilization values food and expansion */
    private fun fertileLand() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `a settler operation waits for an escort when it must, and a unit that finished building joins it`() {
        fertileLand()
        val settler = unit("Settler")
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        // No escort at hand and a single city: wait for one (boldness 0)
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertTrue(operation.escorted)
        assertEquals(listOf(SlotRef(operation.id, operation.firstArmy!!.id, 1)), operation.slotsToBuild)
        assertEquals(operation.firstArmy, ops.armyOf(settler))
        // The settler is lost: the operation aborts at once
        settler.destroy()
        ops.reconcileUnits()
        assertEquals(OperationAbortReason.LOST_CIVILIAN, operation.abortReason)
    }

    @Test
    fun `a settler that finds an escort among the reserves gathers with it`() {
        val warrior = unit("Warrior", HexCoord(1, 0))
        fertileLand()
        val settler = unit("Settler")
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertEquals(operation.firstArmy, ops.armyOf(warrior))
        assertEquals(1, ops.slotIndexOf(operation.firstArmy!!, warrior))
        // The escort comes to the settler, then both move on
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(settler.getTile(), warrior.getTile())
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    @Test
    fun `a quick colony needs no escort and moves at once`() {
        fertileLand()
        val settler = unit("Settler")
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.QUICK_COLONIZE, settler, -1)
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertFalse(operation.escorted)
        assertTrue(operation.slotsToBuild.isEmpty())
    }
    //endregion
}
