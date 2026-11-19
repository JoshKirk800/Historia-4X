package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  A civilian and its escort (CvTacticalAI::PlotArmyMovesEscort of Vox Populi): meeting up, danger, a replaced escort, a lone civilian, and
 *  the operation that has no path. BNW data, grassland.
 */
@RunWith(GdxTestRunner::class)
class EscortMovesTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        game.addCity(civ, tile(0, 0))
        game.addCity(enemy, tile(8, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun unit(name: String, x: Int, y: Int, owner: Civilization = civ): MapUnit = game.addUnit(name, owner, tile(x, y))

    /** A settler at (-1, 0) with a warrior (1, 0) that the operation has taken as its escort: the army waits for them to meet */
    private fun escortedSettler(): Triple<AiOperation, MapUnit, MapUnit> {
        val warrior = unit("Warrior", 1, 0)
        val settler = unit("Settler", -1, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertEquals(warrior, AiOperationLogic.armyUnits(civ, operation.firstArmy!!)[1].second)
        return Triple(operation, settler, warrior)
    }

    private fun newTurn(operation: AiOperation, vararg units: MapUnit) {
        operation.lastTurnMoved = -1
        for (unit in units) unit.currentMovement = unit.getMaxMovement().toFloat()
        civ.ai.invalidateTurnCaches()
    }

    /** Ocean two hexes deep around [island]: nothing on foot gets there */
    private fun surround(island: Tile) {
        for (tile in island.getTilesInDistance(2)) if (tile != island) game.setTileTerrain(tile.position, "Ocean")
        civ.ai.invalidateTurnCaches()
    }

    // region Meeting up

    @Test
    fun `a civilian in danger while waiting goes to the safest plot in reach, which is our city, and the escort stays where it is`() {
        val (operation, settler, warrior) = escortedSettler()
        unit("Warrior", -3, 0, enemy) // two hexes from the settler: it can walk up and capture it
        civ.ai.invalidateTurnCaches()
        assertTrue(civ.ai.plotDanger.isUnderImmediateThreat(settler.getTile()))

        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)

        assertEquals(tile(0, 0), settler.getTile())
        assertTrue(settler in cache.processedUnits)
        assertEquals("the escort is not asked to come", tile(1, 0), warrior.getTile())
        assertNotEquals(OperationState.ABORTED, operation.state)
    }

    @Test
    fun `when the muster plot is not where the civilian stands both walk to it`() {
        val (operation, settler, warrior) = escortedSettler()
        val muster = tile(-3, 0)
        operation.musterTile = muster.position
        operation.firstArmy!!.position = muster.position
        val before = warrior.getTile().aerialDistanceTo(muster)

        OperationalMoves.moveOperation(civ, operation, AutomationCache())

        assertEquals(muster, settler.getTile())
        assertTrue("the escort came closer", warrior.getTile().aerialDistanceTo(muster) < before)
    }

    @Test
    fun `another of our military units on the muster plot takes the escort's place, and the operation moves out with it`() {
        val (operation, settler, warrior) = escortedSettler()
        val army = operation.firstArmy!!
        val garrison = unit("Warrior", -1, 0) // stands where the settler waits
        assertNull(ops.armyOf(garrison))

        OperationalMoves.moveOperation(civ, operation, AutomationCache())

        assertEquals("the unit on the plot is the escort now", garrison.id, army.slots[1].unitId)
        assertNull("the old escort left the army", ops.armyOf(warrior))
        assertTrue("replacing an escort does not tell the operation it lost one", operation.escorted)
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertEquals(ArmyState.MOVING_TO_DESTINATION, army.state)
        assertEquals("they move together", settler.getTile(), garrison.getTile())
    }

    // endregion
    // region Moving

    @Test
    fun `a lone civilian walks to the end of its first turn on its path`() {
        val settler = unit("Settler", -1, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.QUICK_COLONIZE, settler, -1)
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        val target = AiOperationLogic.targetTile(civ, operation)!!
        val expected = settler.movement.getShortestPath(target).first()
        civ.ai.invalidateTurnCaches()

        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)

        assertEquals(expected, settler.getTile())
        assertTrue(settler in cache.processedUnits)
    }

    @Test
    fun `a lone civilian does not end its turn where an enemy can reach it`() {
        val settler = unit("Settler", -1, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.QUICK_COLONIZE, settler, -1)
        val target = AiOperationLogic.targetTile(civ, operation)!!
        val start = settler.getTile()
        val endOfTurn = settler.movement.getShortestPath(target).first()
        // two hexes from where the settler would stop, three or more from where it stands: only the stop is in danger
        val enemyTile = endOfTurn.getTilesAtDistance(2).first { it.aerialDistanceTo(start) >= 3 && it.militaryUnit == null && !it.isCityCenter() }
        game.addUnit("Warrior", enemy, enemyTile)
        civ.viewableTiles = game.tileMap.values.toSet() // adding units narrowed our view to what our units see
        civ.ai.invalidateTurnCaches()
        assertTrue("stop ${endOfTurn.position}, enemy ${enemyTile.position}, start ${start.position}, danger ${civ.ai.plotDanger.get(endOfTurn)}",
            civ.ai.plotDanger.isUnderImmediateThreat(endOfTurn))
        assertFalse("start danger ${civ.ai.plotDanger.get(start)}", civ.ai.plotDanger.isUnderImmediateThreat(start))

        OperationalMoves.moveOperation(civ, operation, AutomationCache())

        assertNotEquals(endOfTurn, settler.getTile())
        assertFalse("wherever it is, nobody can capture it there", civ.ai.plotDanger.isUnderImmediateThreat(settler.getTile()))
    }

    @Test
    fun `a lone civilian takes a free defender of ours standing where it stops as its escort`() {
        val settler = unit("Settler", -1, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        val army = operation.firstArmy!!
        // no escort was to be had: the operation goes alone
        operation.escorted = false
        operation.slotsToBuild.clear()
        army.state = ArmyState.MOVING_TO_DESTINATION
        operation.state = OperationState.MOVING_TO_TARGET
        val target = AiOperationLogic.targetTile(civ, operation)!!
        val stop = settler.movement.getShortestPath(target).first()
        val defender = game.addUnit("Warrior", civ, stop)
        settler.movement.clearPathfindingCache()
        assertEquals(stop, settler.movement.getShortestPath(target).first())
        civ.ai.invalidateTurnCaches()

        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)

        assertEquals(defender.id, army.slots[1].unitId)
        assertTrue(operation.escorted)
        assertEquals(stop, settler.getTile())
        assertTrue(defender in cache.processedUnits)
    }

    // endregion
    // region No path

    @Test
    fun `a lone civilian with no way to its target aborts the operation`() {
        val settler = unit("Settler", -1, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.QUICK_COLONIZE, settler, -1)
        val island = tile(-9, 0)
        surround(island)
        operation.targetTile = island.position
        operation.firstArmy!!.goalTile = island.position

        OperationalMoves.moveOperation(civ, operation, AutomationCache())

        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.LOST_PATH, operation.abortReason)
    }

    @Test
    fun `a civilian and escort with no way to the target abort the operation and are left to the rest of the AI`() {
        val (operation, settler, warrior) = escortedSettler()
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        val island = tile(-9, 0)
        surround(island)
        operation.targetTile = island.position
        operation.firstArmy!!.goalTile = island.position
        newTurn(operation, settler, warrior)

        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)

        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.LOST_PATH, operation.abortReason)
        assertFalse(settler in cache.processedUnits)
        assertFalse(warrior in cache.processedUnits)
    }
    // endregion
}
