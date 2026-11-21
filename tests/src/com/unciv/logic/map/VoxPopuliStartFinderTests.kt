package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.RegionStartFinder
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliStartFinder
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliStartFinder.RingCounts
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliStartFinder.Scored
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's AssignStartingPlots:FindStart, EvaluateCandidatePlot and IterateThroughCandidatePlotList */
@RunWith(GdxTestRunner::class)
class VoxPopuliStartFinderTests {

    private fun ring(food: Int = 0, prod: Int = 0, good: Int = 0, junk: Int = 0, river: Int = 0, tilesOnMap: Int) = RingCounts(food, prod, good, junk, river, tilesOnMap)

    /** Rings that meet the minimums: 1 food, 1 production and 3 good tiles, then 4, 1 and 6, then 4, 3 and 8 in all */
    private fun fullRings(vararg changes: (Int) -> RingCounts?) = listOf(
        ring(food = 2, prod = 1, good = 3, tilesOnMap = 6),
        ring(food = 3, prod = 0, good = 3, tilesOnMap = 12),
        ring(food = 0, prod = 2, good = 2, tilesOnMap = 18),
    ).mapIndexed { index, default -> changes.firstNotNullOfOrNull { it(index) } ?: default }

    @Test
    fun `each tile beyond a score table adds one`() {
        val ringOneFood = listOf(0, 8, 14, 18, 20)
        assertEquals(18, VoxPopuliStartFinder.tableScore(ringOneFood, 3))
        assertEquals(20, VoxPopuliStartFinder.tableScore(ringOneFood, 4))
        assertEquals(21, VoxPopuliStartFinder.tableScore(ringOneFood, 5))
        assertEquals(23, VoxPopuliStartFinder.tableScore(ringOneFood, 7))
        // the third ring's table is {0}: food and production count for what they are
        assertEquals(5, VoxPopuliStartFinder.tableScore(listOf(0), 5))
    }

    @Test
    fun `a start is scored by its rings and eligible with the production of Vox Populi`() {
        val result = VoxPopuliStartFinder.evaluate(fullRings(), baseScore = 0, closeStartPenalty = 0)
        // ring 1: food 14, prod 10, good 3x2 = 6 -> 30; ring 2 (food 5, prod 1, good 6): 25 + 10 + 12 = 47; ring 3 (food 5, prod 3, good 8): 5 + 3 + 8 = 16
        assertEquals(30 + 47 + 16, result.score)
        assertTrue(result.meetsMinimums)
    }

    @Test
    fun `fewer than 3 production tiles in the three rings is not eligible, 2 and none are in Brave New World`() {
        val rings = fullRings({ if (it == 2) ring(food = 0, prod = 1, good = 2, tilesOnMap = 18) else null })
        assertFalse(VoxPopuliStartFinder.evaluate(rings, 0, 0).meetsMinimums)
        val noProduction = fullRings({ if (it == 0) ring(food = 2, prod = 0, good = 3, tilesOnMap = 6) else null })
        assertFalse("production 1 in the first ring", VoxPopuliStartFinder.evaluate(noProduction, 0, 0).meetsMinimums)
    }

    @Test
    fun `junk has no cap, it only costs score`() {
        val clean = VoxPopuliStartFinder.evaluate(fullRings(), 0, 0)
        val junky = VoxPopuliStartFinder.evaluate(fullRings({ if (it == 0) ring(food = 2, prod = 1, good = 3, junk = 6, tilesOnMap = 6) else null }), 0, 0)
        assertTrue("12 junk tiles in all", junky.meetsMinimums)
        // 6 junk tiles weigh -3 in each of the first two rings and -2 in the third
        assertEquals(clean.score - 6 * 3 - 6 * 3 - 6 * 2, junky.score)
    }

    @Test
    fun `tiles off the map count as junk`() {
        val full = VoxPopuliStartFinder.evaluate(fullRings(), 0, 0)
        val atEdge = VoxPopuliStartFinder.evaluate(fullRings({ if (it == 0) ring(food = 2, prod = 1, good = 3, tilesOnMap = 4) else null }), 0, 0)
        assertEquals(full.score - 2 * 3 - 2 * 3 - 2 * 2, atEdge.score)
    }

    @Test
    fun `production beyond twice the food is capped and the cap stays for the next rings`() {
        val capped = VoxPopuliStartFinder.evaluate(fullRings({ if (it == 0) ring(food = 1, prod = 5, good = 3, tilesOnMap = 6) else null }), 0, 0)
        val alreadyTwice = VoxPopuliStartFinder.evaluate(fullRings({ if (it == 0) ring(food = 1, prod = 2, good = 3, tilesOnMap = 6) else null }), 0, 0)
        assertEquals(alreadyTwice.score, capped.score)
    }

    @Test
    fun `a ripple makes a start ineligible and takes its share of the score`() {
        val free = VoxPopuliStartFinder.evaluate(fullRings(), baseScore = 80, closeStartPenalty = 0)
        val rippled = VoxPopuliStartFinder.evaluate(fullRings(), baseScore = 80, closeStartPenalty = 57)
        assertTrue(free.meetsMinimums)
        assertFalse(rippled.meetsMinimums)
        assertEquals(free.score - Math.floorDiv(free.score * 57, 100), rippled.score)
    }

    @Test
    fun `the best candidate is the first of the highest scores, of the kind asked for, above the floor`() {
        val a = Scored("a", 100, true)
        val b = Scored("b", 120, true)
        val c = Scored("c", 120, true)
        val d = Scored("d", 500, false)
        assertSame(b, VoxPopuliStartFinder.best(listOf(a, b, c, d), true, -5000))
        assertSame(d, VoxPopuliStartFinder.best(listOf(a, b, c, d), false, -5000))
        assertNull("nothing above the floor", VoxPopuliStartFinder.best(listOf(Scored("e", -60, false)), false, -50))
        assertNull("a score of 0 is not above a floor of 0", VoxPopuliStartFinder.best(listOf(Scored("f", 0, false)), false, 0))
    }

    /** A map of plains in which every other tile has a forest, so that any tile far from the edge has what a start needs; one tile of sea in
     *  the middle, and a river four columns from it. The only tiles at the sea are the six around it, the only ones at the river are two. */
    private fun startOnSeaOrRiver(flag: Boolean): Tile {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.ruleset.modOptions.constants.startFinderLikeVoxPopuli = flag
        game.makeRectangularMap(newHeight = 30, newWidth = 30, baseTerrain = "Plains")
        val tiles = game.tileMap.values
        val middleColumn = (tiles.minOf { it.getColumn() } + tiles.maxOf { it.getColumn() }) / 2
        val middleRow = (tiles.minOf { it.getRow() } + tiles.maxOf { it.getRow() }) / 2
        for (tile in tiles) {
            game.setTileTerrain(tile.position, "Plains")
            if ((tile.getColumn() + tile.getRow()) % 2 == 0) game.setTileFeatures(tile.position, "Forest")
        }
        val sea = tiles.first { it.getColumn() == middleColumn && it.getRow() == middleRow }
        game.setTileTerrainAndFeatures(sea.position, "Coast")
        tiles.first { it.getColumn() == middleColumn + 4 && it.getRow() == middleRow }.hasBottomRiver = true
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, 1)
        val region = regions.regions.single()
        val tileData = TileDataMap(tiles.size)
        for (tile in tiles) tileData[tile] = MapGenTileData(tile, region, game.ruleset)

        RegionStartFinder.findStart(region, tileData)
        return game.tileMap[region.startPosition!!]
    }

    @Test
    fun `a start by the sea scores 80 and wins over one by a river`() {
        val start = startOnSeaOrRiver(flag = true)
        assertTrue("start at ${start.position}", start.isAdjacentToCoast())
    }

    @Test
    fun `without the flag a start by a river is preferred to one by the sea`() {
        val start = startOnSeaOrRiver(flag = false)
        assertTrue("start at ${start.position}", start.isAdjacentToRiver() && !start.isAdjacentToCoast())
    }
}
