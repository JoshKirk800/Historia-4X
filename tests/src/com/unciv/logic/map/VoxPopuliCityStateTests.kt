package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.MinorCivPlacer
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's AssignStartingPlots:CanPlaceCityStateAt, PlaceCityStateInRegion and the last-chance pass of PlaceCityStates (`cityStatePlacementLikeVoxPopuli`) */
@RunWith(GdxTestRunner::class)
class VoxPopuliCityStateTests {

    private class Setup(val game: TestGame, val regions: List<Region>, val tileData: TileDataMap)

    /** A 40x40 map of [baseTerrain] (desert: yields nothing, so it is never too fertile), with the regions [regionsOf] makes of it */
    private fun setup(baseTerrain: String = "Desert", prepare: (TestGame) -> Unit = {}, regionsOf: (TestGame) -> List<Region> = { wholeMap(it) }): Setup {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 40, newWidth = 40, baseTerrain = baseTerrain)
        prepare(game)
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val regions = regionsOf(game)
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, regions.first(), game.ruleset)
        return Setup(game, regions, tileData)
    }

    private fun wholeMap(game: TestGame): List<Region> {
        val tiles = game.tileMap.values
        val minColumn = tiles.minOf { it.getColumn() }
        val minRow = tiles.minOf { it.getRow() }
        val rect = Rectangle(minColumn.toFloat(), minRow.toFloat(), (tiles.maxOf { it.getColumn() } - minColumn + 1).toFloat(), (tiles.maxOf { it.getRow() } - minRow + 1).toFloat())
        return listOf(Region(game.tileMap, rect).also { it.updateTiles(trim = false) })
    }

    private fun Setup.place(cityStates: Int): List<Tile> {
        val civs = (1..cityStates).map { game.addCiv(cityStateType = "Cultured") }
        MinorCivPlacer.placeMinorCivs(regions, game.tileMap, civs, tileData, game.ruleset)
        return game.tileMap.startingLocationsByNation.values.flatten()
    }

    @Test
    fun `no city state on the outermost ring of the map`() {
        val setup = setup()
        val starts = setup.place(8)
        assertEquals(8, starts.size)
        for (start in starts)
            assertEquals("city state at column ${start.getColumn()}, row ${start.getRow()} is on the edge of the map", 6, start.neighbors.count())
    }

    @Test
    fun `no city state next to ice`() {
        val setup = setup(prepare = { game ->
            for (tile in game.tileMap.values.toList())
                if (tile.getColumn() % 4 == 0 && tile.getRow() % 4 == 0) game.setTileFeatures(tile.position, "Ice")
        })
        val starts = setup.place(6)
        assertEquals(6, starts.size)
        for (start in starts)
            assertTrue("city state at column ${start.getColumn()}, row ${start.getRow()} is next to ice",
                start.neighbors.none { "Ice" in it.terrainFeatures })
    }

    @Test
    fun `no city state where the land around is too fertile`() {
        // The left half is grassland with wheat: 2 food and a bonus resource (+2) is 4 a tile (the +0.25 for fresh water does not apply: no river, no lake),
        // so 48 in all around a tile inside (6 at distance 1, and 12 at distance 2 that count half), over the 30 that Plot_GetFertilityInRange(2) allows.
        var richTiles = emptySet<Tile>()
        val setup = setup(prepare = { game ->
            val middle = game.tileMap.values.let { tiles -> (tiles.minOf { it.getColumn() } + tiles.maxOf { it.getColumn() }) / 2 }
            richTiles = game.tileMap.values.filter { it.getColumn() <= middle }.toSet()
            for (tile in richTiles) {
                game.setTileTerrain(tile.position, "Grassland")
                tile.setTileResource("Wheat")
            }
        })
        val starts = setup.place(4)
        assertEquals(4, starts.size)
        for (start in starts) {
            val fertility = start.getTilesAtDistance(1).count { it in richTiles } * 4.0 + start.getTilesAtDistance(2).count { it in richTiles } * 2.0
            assertTrue("city state at column ${start.getColumn()} has $fertility fertility around it", fertility <= 30.0)
        }
    }

    @Test
    fun `a city state that finds no free site in its region is discarded, not forced in`() {
        val setup = setup()
        // Every tile is within the impact of something else
        for (tile in setup.game.tileMap.values) setup.tileData[tile]!!.impacts[MapRegions.ImpactType.MinorCiv] = 5
        assertEquals(0, setup.place(1).size)
    }

    /** Two landmasses, each its own region: the one on the left is a single column along the edge of the map, where no city state may go */
    private fun edgeColumnAndContinent(): Setup = setup(
        baseTerrain = "Ocean",
        prepare = { game ->
            val minColumn = game.tileMap.values.minOf { it.getColumn() }
            for (tile in game.tileMap.values)
                game.setTileTerrain(tile.position, if (tile.getColumn() == minColumn || tile.getColumn() >= minColumn + 3) "Desert" else "Ocean")
        },
        regionsOf = { game ->
            val minColumn = game.tileMap.values.minOf { it.getColumn() }
            val maxColumn = game.tileMap.values.maxOf { it.getColumn() }
            val minRow = game.tileMap.values.minOf { it.getRow() }
            val maxRow = game.tileMap.values.maxOf { it.getRow() }
            fun continentOf(column: Int) = game.tileMap.values.first { it.getColumn() == column && it.isLand }.getContinent()
            listOf(
                Region(game.tileMap, Rectangle(minColumn.toFloat(), minRow.toFloat(), 1f, (maxRow - minRow + 1).toFloat()), continentOf(minColumn)),
                Region(game.tileMap, Rectangle((minColumn + 3).toFloat(), minRow.toFloat(), (maxColumn - minColumn - 2).toFloat(), (maxRow - minRow + 1).toFloat()), continentOf(maxColumn))
            ).onEach { it.updateTiles(trim = false) }
        }
    )

    @Test
    fun `a discarded city state gets a last chance on any free site of the map`() {
        val setup = edgeColumnAndContinent()
        val edgeContinent = setup.regions[0].continentID
        val starts = setup.place(2)
        assertEquals("both city states are placed", 2, starts.size)
        assertTrue("none on the edge column: ${starts.map { "${it.getColumn()},${it.getRow()} continent ${it.getContinent()}" }}, edge continent $edgeContinent, other ${setup.regions[1].continentID}, sizes ${setup.game.tileMap.continentSizes}",
            starts.none { it.getContinent() == edgeContinent })
    }

    @Test
    fun `the last chance respects the impact of the city states placed`() {
        val setup = edgeColumnAndContinent()
        val starts = setup.place(2)
        assertEquals(2, starts.size)
        assertTrue("both on the landmass with sites", starts.all { it.getContinent() == setup.regions[1].continentID })
        assertTrue("at least 5 tiles apart: ${starts.map { it.position }}", starts[0].aerialDistanceTo(starts[1]) >= 5)
    }
}
