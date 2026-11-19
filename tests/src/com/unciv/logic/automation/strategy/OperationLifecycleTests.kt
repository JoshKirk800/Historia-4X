package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.OperationalMoves
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The lifecycle of Vox Populi's military operations (Release-5.4.8): CvAIOperation::Move and DoTurn (ShouldAbort before and after the army
 *  moves, ReleaseAllUnits), CvAIOperationMilitary::VerifyOrAdjustTarget (also the pillage raid's) and CheckTransitionToNextStage (the
 *  muster point where the units are, the jig that is up, OnSuccess), UnitWasRemoved and DeleteArmyAI, the deletion of an operation that
 *  ends while it is made (CvPlayer::addAIOperation), and CvUnit::TurnsToReachTarget with the flags of the operations' recruiting
 *  (MOVEFLAG_APPROX_TARGET_RING2), which lets a besieged city recruit. BNW rules and data, desert land on a hexagonal map of radius 30: our
 *  city at (0, 0), the enemy's at (8, 0).
 */
@RunWith(GdxTestRunner::class)
class OperationLifecycleTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City
    private lateinit var luxury: TileResource
    private lateinit var strategic: TileResource

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(30)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, tile(0))
        enemyCity = game.addCity(enemy, tile(8))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        luxury = game.createResource().apply { resourceType = ResourceType.Luxury }
        strategic = game.createResource().apply { resourceType = ResourceType.Strategic }
        see()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))

    /** Putting units and cities on the map changes what we see and what the tactical map holds: afterwards we see all of it, and the maps are made again */
    private fun see() {
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun war() = civ.getDiplomacyManager(enemy)!!.declareWar()

    /** A military unit of ours: on the first free tile around the capital unless [at] is given (one military unit per tile) */
    private fun unit(name: String, at: HexCoord? = null): MapUnit {
        val tile = at?.let { game.getTile(it) } ?: tile(0).getTilesInDistance(4).first { it.militaryUnit == null && it.civilianUnit == null }
        return game.addUnit(name, civ, tile)
    }

    /** The units of the basic city attack's required slots: an archer and two warriors */
    private fun attackReserves() {
        unit("Archer")
        repeat(2) { unit("Warrior") }
        see()
    }

    private fun attack() = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)

    private fun unitsOf(operation: AiOperation) = AiOperationLogic.armyUnits(civ, operation.firstArmy!!)

    /** Puts [units] on separate tiles around [center] */
    private fun teleport(units: List<Pair<Int, MapUnit>>, center: HexCoord) {
        val tiles = game.getTile(center).getTilesInDistance(2).filter { it.militaryUnit == null || units.any { (_, u) -> u === it.militaryUnit } }.toList()
        for ((index, pair) in units.withIndex()) { pair.second.removeFromTile(); pair.second.putInTile(tiles[index]) }
    }

    /** [resource] on the next free tile around [city]'s center, worked by the city */
    private fun place(city: City, resource: TileResource) {
        val tile = city.getCenterTile().neighbors.first { it.resource == null }
        tile.setTileResource(resource.name)
        city.workedTiles.add(tile.position)
    }

    //region CvAIOperationMilitary::VerifyOrAdjustTarget, asked by ShouldAbort

    @Test
    fun `an attack ends when anyone but the enemy owns its target, with the reason the target was captured`() {
        attackReserves()
        val operation = attack()
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        enemyCity.moveToCiv(neutralCiv())
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.TARGET_ALREADY_CAPTURED, operation.abortReason)
    }

    @Test
    fun `an attack whose target nobody owns any more goes on, only a new owner ends it`() {
        attackReserves()
        val operation = attack()
        enemyCity.getCenterTile().setOwningCity(null)
        assertNotEquals(enemy, enemyCity.getCenterTile().getOwner())
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
    }

    @Test
    fun `an operation that ends with the army's move releases its units and is not checked on its target`() {
        war()
        attackReserves()
        val operation = attack()
        val units = unitsOf(operation).map { it.second }
        assertTrue(units.isNotEmpty() && units.all { ops.armyOf(it) != null })
        enemyCity.moveToCiv(civ)
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.TARGET_ALREADY_CAPTURED, operation.abortReason)
        assertTrue("CvArmyAI::ReleaseAllUnits", units.all { ops.armyOf(it) == null })
        assertTrue(operation.lastTurnMoved < game.gameInfo.turns)
    }

    @Test
    fun `an operation with no army is over, no units`() {
        attackReserves()
        val operation = attack()
        operation.armies.clear()
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.NO_UNITS, operation.abortReason)
    }

    @Test
    fun `the pillage raid looks for its best target every time, and ends when it has none`() {
        val north = game.addCity(enemy, tile(0, 8))
        place(enemyCity, luxury)
        unit("Horseman", HexCoord(-2, 0))
        see()
        assertTrue(MilitaryOperations.requestPillageAttack(civ, enemy))
        val operation = ops.firstOfType(AiOperationType.PILLAGE_ENEMY)!!
        assertEquals(enemyCity.getCenterTile().position, operation.targetTile)
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        // the other city works more: it is the target and the army's goal now
        place(north, luxury)
        place(north, strategic)
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(north.getCenterTile().position, operation.targetTile)
        assertEquals(north.getCenterTile().position, operation.firstArmy!!.goalTile)
        // nothing is left to pillage
        enemyCity.workedTiles.clear()
        north.workedTiles.clear()
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.NO_TARGET, operation.abortReason)
    }
    //endregion

    //region CvAIOperationMilitary::CheckTransitionToNextStage

    @Test
    fun `an army that has gathered makes the place where its units are its muster point and moves out`() {
        attackReserves()
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertEquals(city.getCenterTile().position, operation.musterTile)
        teleport(unitsOf(operation), HexCoord(2, 0))
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertNotEquals(city.getCenterTile().position, operation.musterTile)
        assertTrue("the muster point is where one of the units is", unitsOf(operation).any { it.second.getTile().position == operation.musterTile })
        assertEquals(operation.musterTile, operation.firstArmy!!.position)
    }

    @Test
    fun `at peace an army seen by the enemy on its way is discovered and is in place, the diplomatic AI is told`() {
        attackReserves()
        val operation = attack()
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        teleport(unitsOf(operation), HexCoord(1, 0))
        enemy.viewableTiles = emptySet()
        assertFalse("not in place, not seen", AiOperationLogic.checkOnTarget(civ, operation))
        assertFalse(civ.ai.diplomacy.memoryOf(enemy).musteringForAttack)
        enemy.viewableTiles = game.tileMap.values.toSet()
        assertTrue("more than 2 units are in the enemy's sight", AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.diplomacy.memoryOf(enemy).musteringForAttack)
        assertTrue(civ.ai.tactical.isInFocusArea(enemyCity.getCenterTile()))
    }

    @Test
    fun `at war an army that the enemy sees is not discovered`() {
        war()
        attackReserves()
        val operation = attack()
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        teleport(unitsOf(operation), HexCoord(1, 0))
        enemy.viewableTiles = game.tileMap.values.toSet()
        assertFalse(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertFalse(civ.ai.diplomacy.memoryOf(enemy).musteringForAttack)
    }
    //endregion

    //region UnitWasRemoved, DeleteArmyAI

    /** A formation with 2 required slots (the city-state attack's): CvAIOperation::UnitWasRemoved ends it below 2 units, though 2 / 2 is 1 */
    @Test
    fun `an army of a formation with two required slots that is down to one unit aborts`() {
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.CITY_STATE_ATTACK, enemy, enemyCity, city)
        val warrior = unit("Warrior")
        val archer = unit("Archer")
        ops.addUnit(operation.firstArmy!!, warrior, 0)
        ops.addUnit(operation.firstArmy!!, archer, 2)
        operation.slotsToBuild.clear()
        operation.state = OperationState.GATHERING_FORCES
        warrior.destroy()
        ops.reconcileUnits()
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.HALF_STRENGTH, operation.abortReason)
    }
    //endregion

    //region CvPlayer::addAIOperation

    @Test
    fun `an operation that has no unit once it is made is deleted, and no statistic remembers it`() {
        // three units that cannot walk to the muster point (islands, and no embarking): the units at hand would do, but no unit is recruited
        val homes = listOf(tile(-6, 0), tile(-6, 3), tile(-6, 6))
        for (home in homes) for (neighbor in home.neighbors) game.setTileTerrain(neighbor.position, "Ocean")
        game.addUnit("Archer", civ, homes[0])
        game.addUnit("Warrior", civ, homes[1])
        game.addUnit("Warrior", civ, homes[2])
        see()
        assertFalse(MilitaryOperations.requestShowOfForce(civ, enemy))
        assertTrue(ops.operations.isEmpty())
        assertEquals(0, ops.statsByType[AiOperationType.BASIC_CITY_ATTACK]?.get(0) ?: 0)
        assertTrue(ops.statsAbortReasons.isEmpty())
        assertEquals(0, ops.statsLaunched)
    }

    @Test
    fun `an operation that ends in its making leaves no army and no abort behind`() {
        val far = game.addCity(enemy, tile(24))
        see()
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, far)
        assertEquals(OperationAbortReason.NO_MUSTER, operation.abortReason)
        assertTrue(ops.operations.isEmpty())
        assertTrue(ops.statsAbortReasons.isEmpty())
    }
    //endregion

    //region CvUnit::TurnsToReachTarget with MOVEFLAG_APPROX_TARGET_RING2: a besieged city recruits

    private fun besiege() {
        for (neighbor in city.getCenterTile().neighbors) game.addUnit("Warrior", enemy, neighbor)
    }

    @Test
    fun `the turns to a city whose neighbors the enemy holds are the turns to a plot 2 from it`() {
        war()
        besiege()
        val far = game.addUnit("Warrior", civ, tile(-5, 0))
        val near = game.addUnit("Warrior", civ, tile(-2, 0))
        see()
        assertFalse("the city itself cannot be reached", far.movement.canReach(city.getCenterTile()))
        assertEquals("3 plots at 2 moves a turn", 2, AiOperationLogic.turnsToReach(far, city.getCenterTile()))
        assertEquals("within 2 plots already", 0, AiOperationLogic.turnsToReach(near, city.getCenterTile()))
    }

    @Test
    fun `a city defense for a besieged city recruits the units that can come, as Vox Populi's does`() {
        war()
        besiege()
        val reserves = listOf(HexCoord(-6, 0), HexCoord(-6, 1), HexCoord(-6, 2), HexCoord(-7, 0)).map { game.addUnit("Warrior", civ, game.getTile(it)) }
        see()
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.CITY_CLOSE_DEFENSE, enemy, city)
        assertNotEquals(OperationState.ABORTED, operation.state)
        assertNotNull(operation.firstArmy)
        assertTrue(reserves.any { ops.armyOf(it) == operation.firstArmy })
    }
    //endregion
}
