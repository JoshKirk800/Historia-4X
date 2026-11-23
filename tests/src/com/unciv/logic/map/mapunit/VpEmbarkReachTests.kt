package com.unciv.logic.map.mapunit

import com.unciv.UncivGame
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm.AStarPathfinding
import com.unciv.testing.TestGame
import com.unciv.testing.TestRunnerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.runners.Parameterized.UseParametersRunnerFactory

/**
 * Staged `embarkAwarePathingLikeVp`: a path search spends, in each turn, the movement of the state the unit is in where the turn starts
 * (CvAStar.cpp: `UnitPathCacheData::baseMoves(bEmbarked)` read as `m_iStartMovesForTurn = baseMoves(needsEmbarkation(plot))` in `PathCost` and
 * `NodeAddedToPath`; CvUnit::baseMoves for the embarked value; CvUnitMovement::GetCostsForMove ends the turn when a unit embarks or disembarks).
 * Both the classic search and the A* pathing map, so the tests run with both. The path of a search has one tile per turn plus the destination,
 * so its size is the number of turns.
 */
@RunWith(Parameterized::class)
@UseParametersRunnerFactory(TestRunnerFactory::class)
class VpEmbarkReachTests(private val pathfindingAlgorithm: PathfindingAlgorithm) {
    companion object {
        @Suppress("unused")
        @Parameters
        @JvmStatic
        fun parameters() = TestRunnerFactory.Parameters.pathfinding
    }

    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private fun setInt(game: TestGame, name: String, value: Int) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Int::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private class World(val game: TestGame, val unit: MapUnit, val destination: Tile) {
        val movement: UnitMovement get() = unit.movement
    }

    /**
     * A warrior (2 movement; embarked 3 where the test says so) on the tile (0, 0) with the destination (destX, 0) in a row of tiles.
     * Everything else is water if the unit [startsOnWater] is false (a crossing), and land if it is true (leaving the water).
     */
    private fun world(destX: Int, startsOnWater: Boolean, flag: Boolean, embarkedMovement: Int = 3): World {
        UncivGame.Current.settings.useAStarPathfinding = (pathfindingAlgorithm == AStarPathfinding)
        val game = TestGame("Enables embarkation for land units", baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        setInt(game, "embarkedUnitMovement", embarkedMovement)
        setFlag(game, "embarkAwarePathingLikeVp", flag)
        for (tile in game.tileMap.values)
            game.setTileTerrain(tile.position, if (startsOnWater) "Grassland" else "Coast")
        game.setTileTerrain(game.getTile(0, 0).position, if (startsOnWater) "Coast" else "Grassland")
        game.setTileTerrain(game.getTile(destX, 0).position, "Grassland")
        val civ = game.addCiv()
        civ.tech.unitsCanEmbark = true
        civ.tech.embarkedUnitsCanEnterOcean = true
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        return World(game, unit, game.getTile(destX, 0))
    }

    private fun turns(w: World) = w.movement.getShortestPath(w.destination).size

    @Test
    fun `turns that start on water move an embarked budget across a crossing`() {
        val fixed = turns(world(destX = 7, startsOnWater = false, flag = false))
        val aware = turns(world(destX = 7, startsOnWater = false, flag = true))
        assertTrue("fixed $fixed, embark aware $aware", aware < fixed)
    }

    @Test
    fun `turns that start on land use the land budget after leaving the water`() {
        val fixed = turns(world(destX = 6, startsOnWater = true, flag = false))
        val aware = turns(world(destX = 6, startsOnWater = true, flag = true))
        assertTrue("fixed $fixed, embark aware $aware", aware > fixed)
    }

    @Test
    fun `the switch changes nothing while the embarked and land budgets are equal`() {
        // the default embarked movement is 2, the warrior's own: both states have the same budget
        for ((destX, startsOnWater) in listOf(7 to false, 6 to true)) {
            val off = turns(world(destX = destX, startsOnWater = startsOnWater, flag = false, embarkedMovement = 2))
            val on = turns(world(destX = destX, startsOnWater = startsOnWater, flag = true, embarkedMovement = 2))
            assertEquals("destination $destX", off, on)
        }
    }

    @Test
    fun `a path found before the unit became embarked is not reused`() {
        val w = world(destX = 5, startsOnWater = false, flag = true)
        w.movement.clearPathfindingCache() // arms the per-unit caches of the classic search
        val onLand = turns(w)
        // The unit stays on its tile with its movement, but the tile is water now: the unit is embarked
        w.game.setTileTerrain(w.game.getTile(0, 0).position, "Coast")
        assertTrue(w.unit.isEmbarked())
        val embarked = turns(w)
        assertTrue("on land $onLand, embarked $embarked", embarked < onLand)
    }
}
