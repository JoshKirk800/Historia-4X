package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.RegionStartFinder
import com.unciv.logic.map.mapgenerator.mapregions.StartRecipes
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The start settings of SmallContinents.lua, Archipelago.lua and TinyIslands.lua (`StartPlotSystem`): rectangular division, must be coast, the AI map hint */
@RunWith(GdxTestRunner::class)
class IslandStartRecipeTests {
    /** The column and row of [tile] counted from the south west corner of the map: the map's own columns start below 0 */
    private fun relative(game: TestGame, tile: Tile) =
        Pair(tile.getColumn() - game.tileMap.values.minOf { it.getColumn() }, tile.getRow() - game.tileMap.values.minOf { it.getRow() })

    /** A sea of coast tiles with [land] columns x rows of plains (every other tile a forest, which gives a start food and production) */
    private fun makeGame(type: String, width: Int, height: Int, land: (Int, Int) -> Boolean): TestGame {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.ruleset.modOptions.constants.startFinderLikeVoxPopuli = true
        game.ruleset.modOptions.constants.regionAssignmentLikeVoxPopuli = true
        game.makeRectangularMap(newHeight = height, newWidth = width, baseTerrain = "Coast")
        game.tileMap.mapParameters.type = type
        for (tile in game.tileMap.values) {
            val (column, row) = relative(game, tile)
            if (!land(column, row)) {
                game.setTileTerrain(tile.position, "Coast")
                continue
            }
            game.setTileTerrain(tile.position, "Plains")
            if ((column + row) % 2 == 0) game.setTileFeatures(tile.position, "Forest")
        }
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        return game
    }

    /** One start for one civ, as `MapRegions.assignRegions` would find it, and the regions that were cut */
    private fun findStart(game: TestGame): Pair<Tile, MapRegions> {
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, 1)
        val region = regions.regions.single()
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, region, game.ruleset)
        RegionStartFinder.findStart(region, tileData)
        return game.tileMap[region.startPosition!!] to regions
    }

    @Test
    fun `the three scripts carry the settings of their StartPlotSystem`() {
        val archipelago = StartRecipes.forType(MapType.archipelagoLikeCivV)!!
        assertEquals(listOf(1, 2, 3), archipelago.minimums().food)
        assertEquals(listOf(1, 1, 1), archipelago.minimums().prod)
        val tinyIslands = StartRecipes.forType(MapType.tinyIslandsLikeCivV)!!
        assertEquals(listOf(1, 2, 2), tinyIslands.minimums().food)
        assertEquals(listOf(1, 1, 1), tinyIslands.minimums().prod)
        val smallContinents = StartRecipes.forType(MapType.smallContinentsLikeCivV)!!
        assertEquals("nothing is lowered in SmallContinents.lua", listOf(1, 4, 4), smallContinents.minimums().food)
        assertEquals(listOf(1, 1, 3), smallContinents.minimums().prod)
        assertTrue(listOf(archipelago, tinyIslands, smallContinents).all { it.mustBeCoast })
        assertEquals(null, StartRecipes.forType(MapType.continentsLikeCivV))
    }

    @Test
    fun `the AI map hint is naval and offshore for Archipelago and Tiny Islands and offshore for Small Continents`() {
        fun hint(type: String): Int {
            val game = makeGame(type, 4, 4) { _, _ -> true }
            return SettleAreas.aiMapHint(game.gameInfo)
        }
        assertEquals(1 + 4, hint(MapType.archipelagoLikeCivV))
        assertEquals(1 + 4, hint(MapType.tinyIslandsLikeCivV))
        assertEquals(4, hint(MapType.smallContinentsLikeCivV))
        assertEquals(0, hint(MapType.continentsLikeCivV))
    }

    @Test
    fun `a rectangular recipe cuts the whole map even when one landmass holds all the land`() {
        // A single landmass is 100 % of the land: the generic rule (biggest landmass under 25 %) would not divide the whole map
        val rectangular = findStart(makeGame(MapType.archipelagoLikeCivV, 20, 20) { _, _ -> true }).second.regions
        assertEquals(-1, rectangular.single().continentID)
        val continental = findStart(makeGame(MapType.continentsLikeCivV, 20, 20) { _, _ -> true }).second.regions
        assertTrue("a continents map keeps the landmass", continental.single().continentID != -1)
    }

    /** The start of a region that covers the whole map and ignores landmasses, as the rectangular division makes it: 40 x 30 sea with a small island in
     *  the middle (11 x 11) and a big one in the south west corner (13 x 13) */
    private fun startOfWholeMapRegion(type: String): Pair<Int, Int> {
        val big = { column: Int, row: Int -> column in 1..13 && row in 1..13 }
        val small = { column: Int, row: Int -> column in 15..25 && row in 10..20 }
        val game = makeGame(type, 40, 30) { column, row -> big(column, row) || small(column, row) }
        val region = Region(game.tileMap, Rectangle(-20f, -20f, 41f, 41f), -1)
        region.affectedByWorldWrap = false
        region.updateTiles()
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, region, game.ruleset)
        RegionStartFinder.findStart(region, tileData)
        val (column, row) = relative(game, game.tileMap[region.startPosition!!])
        assertTrue("start at column $column, row $row is on an island", big(column, row) || small(column, row))
        return Pair(column, row)
    }

    @Test
    fun `a rectangular region starts on its most fertile landmass, though a poorer one lies in the middle`() {
        val (column, row) = startOfWholeMapRegion(MapType.archipelagoLikeCivV)
        assertTrue("start at column $column, row $row", column <= 13)
    }

    @Test
    fun `a recipe that must be coast starts on the sea though inland sites are as good`() {
        // 30 x 30 land with one sea tile off the middle; the tiles around it are the only ones by the sea
        val sea = { column: Int, row: Int -> column == 7 && row == 15 }
        val (inland, _) = findStart(makeGame(MapType.continentsLikeCivV, 30, 30) { column, row -> !sea(column, row) })
        assertFalse("without the recipe the start is in the middle, at ${inland.position}", inland.isAdjacentToCoast())
        val (coastal, _) = findStart(makeGame(MapType.smallContinentsLikeCivV, 30, 30) { column, row -> !sea(column, row) })
        assertTrue("start at ${coastal.position}", coastal.isAdjacentToCoast())
    }
}
