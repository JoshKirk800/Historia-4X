package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Naval blockades (CvTacticalAI FindTacticalTargets' enemy water plots, PrioritizeNavalTargetsAndAddToMainList, CvPlot::GetSeaBlockadeScore,
 *  PlotBlockadeMoves, ExecuteNavalBlockadeMove) with Brave New World data. A sea of Coast around the enemy's island (land within 2 of its city at
 *  (0, 0)); our city is on an island at (-11, 0). The score counts the water within 2 of a tile ([com.unciv.logic.map.tile.NAVAL_PLOT_BLOCKADE_RANGE],
 *  19 tiles in open sea).
 */
@RunWith(BaseTestRunner::class)
class BlockadeMovesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var enemyCity: City
    private lateinit var ourCity: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(14)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Coast")
        for (island in listOf(tile(0, 0) to 2, tile(-11, 0) to 1))
            for (tile in island.first.getTilesInDistance(island.second)) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        ourCity = game.addCity(civ, tile(-11, 0))
        enemyCity = game.addCity(enemy, tile(0, 0))
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun context(): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.find(civ, map, danger))
    }

    private fun points(ctx: TacticalContext = context()) = ctx.targets.all.filter { it.type == TacticalTargetType.BlockadePoint }

    private fun ring(distance: Int): List<Tile> = tile(0, 0).getTilesAtDistance(distance).toList()

    private fun own(vararg tiles: Tile) { for (tile in tiles) enemyCity.expansion.takeOwnership(tile) }

    /** The weight of a blockade point by the original's formula: its score less our distance to it, at least 1, doubled for a damaged city */
    private fun weight(tile: Tile, damaged: Boolean = false): Int {
        val score = BlockadeMoves.seaBlockadeScore(civ, civ.ai.tactical.map, tile)
        return maxOf(1, score - ourCity.getCenterTile().aerialDistanceTo(tile)) * if (damaged) 2 else 1
    }

    // region The score of a tile

    @Test
    fun `an open sea tile scores one for each of the 19 tiles within 2, the enemy's tiles score more`() {
        val at = game.tileMap.values.first { t -> t.aerialDistanceTo(tile(0, 0)) > 6 && t.aerialDistanceTo(tile(-11, 0)) > 4 && t.getTilesInDistance(2).count() == 19 && t.getTilesInDistance(2).all { it.isWater } }
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        assertEquals(19, at.getTilesInDistance(2).count { it.isWater })
        assertEquals("nobody's water: 1 for each tile", 19, BlockadeMoves.seaBlockadeScore(civ, map, at))
        val owned = at.neighbors.take(3).toList()
        own(*owned.toTypedArray())
        assertEquals("one more for each tile of a player at war with us", 22, BlockadeMoves.seaBlockadeScore(civ, map, at))
        owned[0].setImprovementBasic("Fishing Boats")
        assertEquals("and one for its improvement", 23, BlockadeMoves.seaBlockadeScore(civ, map, at))
        owned[1].setTileResource("Fish")
        assertEquals("and one for a resource we know", 24, BlockadeMoves.seaBlockadeScore(civ, map, at))
        // what is on the water of nobody's or of a friend does not count
        at.setImprovementBasic("Fishing Boats")
        at.neighbors.last { it !in owned }.setImprovementBasic("Fishing Boats")
        assertEquals(24, BlockadeMoves.seaBlockadeScore(civ, map, at))
    }

    @Test
    fun `land and water of another sea do not count`() {
        // a tile next to the enemy's island has the island's land within 2
        val at = tile(3, 0)
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val water = at.getTilesInDistance(2).count { it.isWater }
        assertTrue(water < 19)
        assertEquals(water, BlockadeMoves.seaBlockadeScore(civ, civ.ai.tactical.map, at))
    }

    // endregion
    // region The blockade points

    @Test
    fun `a water tile of the enemy less than 4 tiles from his city is a blockade point of the weight of its score less our distance`() {
        val at = tile(3, 0)
        own(at, tile(4, 0))
        val found = points()
        assertEquals("the tile 4 tiles from the city is too far", listOf(at), found.map { it.tile })
        assertEquals(weight(at), found.single().aux)
        assertEquals(enemy, found.single().targetCiv)
    }

    @Test
    fun `a damaged city doubles the weight, land and water that is not the enemy's make no point`() {
        val at = tile(3, 0)
        own(at, tile(1, 0)) // land of the enemy's: no point
        val healthy = points().single()
        enemyCity.health = 150
        assertEquals("to support the troops", weight(at, damaged = true), points().single().aux)
        assertEquals(2 * healthy.aux, points().single().aux)
        enemyCity.health = 200
        civ.getDiplomacyManager(enemy)!!.makePeace()
        assertTrue("at peace there is no one to blockade", points().isEmpty())
    }

    @Test
    fun `a tile that an enemy ship can attack is no blockade point`() {
        own(*ring(3).toTypedArray())
        game.addUnit("Trireme", enemy, tile(3, 0))
        val found = points().map { it.tile }
        assertFalse("its own tile", tile(3, 0) in found)
        assertTrue("far from the ship", tile(-3, 0) in found)
    }

    @Test
    fun `only the 10 best points are kept, and those with an improvement whatever their weight`() {
        own(*ring(3).toTypedArray())
        val candidates = ring(3)
        assertEquals(18, candidates.size)
        val kept = points().map { it.tile }
        assertEquals(10, kept.size)
        val dropped = candidates - kept.toSet()
        assertTrue("every kept point weighs at least what any dropped one does", kept.minOf { weight(it) } >= dropped.maxOf { weight(it) })

        val lowest = dropped.minBy { weight(it) }
        lowest.setImprovementBasic("Fishing Boats")
        val withImprovement = points().map { it.tile }
        assertEquals(11, withImprovement.size)
        assertTrue(lowest in withImprovement)
    }

    // endregion
    // region The moves

    private fun onePoint(): Tile = tile(3, 0).also { own(it) }

    private fun ship(x: Int, health: Int = 100): MapUnit = game.addUnit("Trireme", civ, tile(x, 0)).also { it.health = health }

    @Test
    fun `a ship that gets there within 4 turns goes to the blockade point and is done`() {
        val target = onePoint()
        val ship = ship(9)
        val start = ship.getTile().aerialDistanceTo(target)
        val ctx = context()
        BlockadeMoves.plotBlockadeMoves(ctx)
        assertTrue("sailed towards the point", ship.getTile().aerialDistanceTo(target) < start)
        assertTrue(ship in ctx.cache.processedUnits)
    }

    @Test
    fun `one ship is enough for a point, the one that gets there soonest`() {
        val target = onePoint()
        val near = ship(7)
        val far = ship(11)
        val ctx = context()
        BlockadeMoves.plotBlockadeMoves(ctx)
        assertTrue(near in ctx.cache.processedUnits)
        assertFalse(far in ctx.cache.processedUnits)
        assertEquals(11, far.getTile().position.x)
        assertTrue(near.getTile().aerialDistanceTo(target) < 4)
    }

    @Test
    fun `a ship with less than half its hit points is not sent, one with exactly half is`() {
        onePoint()
        val weak = ship(9, health = 49)
        var ctx = context()
        BlockadeMoves.plotBlockadeMoves(ctx)
        assertFalse(weak in ctx.cache.processedUnits)
        assertEquals(9, weak.getTile().position.x)
        weak.health = 50
        ctx = context()
        BlockadeMoves.plotBlockadeMoves(ctx)
        assertTrue(weak in ctx.cache.processedUnits)
    }

    // endregion
}
