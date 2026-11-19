package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvCity::GetIndividualPlotScore (the resource values x3, choke points and water area separators at AI_PLOT_VALUE_STRATEGIC_RESOURCE,
 *  the citadel and natural wonder terms) with CvPlot::IsChokePoint and CvPlot::IsWaterAreaSeparator, Release-5.4.8. Unciv's own tile price
 *  (by distance) is used, so a tile two away costs the base cost and scales the score by 1.
 */
@RunWith(GdxTestRunner::class)
class PlotScoreTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10, Constants.grassland)
        game.ruleset.modOptions.constants.plotAcquisitionLikeCivV = false
        civ = game.addCiv()
        city = game.addCity(civ, tile(0, 0))
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))
    private fun score(tile: Tile) = UseGoldAutomation.individualPlotScore(city, tile)
    private fun strategic() = game.ruleset.ai.define(AiDefine.AI_PLOT_VALUE_STRATEGIC_RESOURCE)
    private fun mountains(vararg at: Pair<Int, Int>) = at.forEach { game.setTileTerrain(HexCoord(it.first, it.second), Constants.mountain) }

    //region The resource values

    @Test
    fun `a strategic resource weighs 3 x 80 and a luxury 3 x 40, doubled when the civilization has none`() {
        assertEquals(80, strategic())
        val strategicResource = game.createResource().apply { resourceType = ResourceType.Strategic }
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury }
        tile(2, 0).setTileResource(strategicResource.name)
        tile(-2, 0).setTileResource(luxury.name)
        val plain = score(tile(0, 2))
        assertEquals(240, score(tile(2, 0)) - plain)
        assertEquals(240, score(tile(-2, 0)) - plain)
    }
    //endregion

    //region Choke points and water area separators

    @Test
    fun `a plot with two passable land neighbours that are not next to each other is a choke point`() {
        mountains(2 to 1, 2 to -1, 3 to 1, 1 to -1)
        assertTrue(PlotShape.isChokePoint(tile(2, 0)))
        assertFalse("open ground", PlotShape.isChokePoint(tile(-2, 0)))
        assertFalse("mountains are not choke points", PlotShape.isChokePoint(tile(2, 1)))
    }

    @Test
    fun `two passable neighbours next to each other make no choke point`() {
        // (2,1) and (3,1) are the open neighbours of (2,0), and next to each other
        mountains(1 to 0, 3 to 0, 2 to -1, 1 to -1)
        assertFalse(PlotShape.isChokePoint(tile(2, 0)))
    }

    @Test
    fun `a dead end is no choke point`() {
        // (2,0) keeps (1,0) and (3,0); (3,0) is walled in except for (2,0) and one more tile
        mountains(2 to 1, 2 to -1, 3 to 1, 1 to -1, 3 to -1, 4 to 1, 4 to 0)
        assertFalse(PlotShape.isChokePoint(tile(2, 0)))
    }

    @Test
    fun `a choke point is worth AI_PLOT_VALUE_STRATEGIC_RESOURCE more`() {
        mountains(2 to 1, 2 to -1, 3 to 1, 1 to -1)
        assertEquals(strategic(), score(tile(2, 0)) - score(tile(-2, 0)))
    }

    @Test
    fun `a land plot between two separate bodies of water is a water area separator`() {
        game.setTileTerrain(HexCoord(2, 1), Constants.ocean)
        game.setTileTerrain(HexCoord(2, -1), Constants.ocean)
        assertTrue(PlotShape.isWaterAreaSeparator(tile(2, 0), PlotShape.WaterAreas(game.tileMap)))

        // joined around the plot, they are one body
        for ((x, y) in listOf(3 to 1, 3 to 0, 3 to -1)) game.setTileTerrain(HexCoord(x, y), Constants.ocean)
        assertFalse(PlotShape.isWaterAreaSeparator(tile(2, 0), PlotShape.WaterAreas(game.tileMap)))
        assertFalse("water is no separator", PlotShape.isWaterAreaSeparator(tile(3, 0), PlotShape.WaterAreas(game.tileMap)))
    }
    //endregion

    //region Citadels and natural wonders

    private fun withStrategicResource(tile: Tile): Tile {
        val resource = game.createResource().apply { resourceType = ResourceType.Strategic }
        return tile.also { it.setTileResource(resource.name) }
    }

    @Test
    fun `a foreign great general within 3 tiles adds 50 percent for each neighbour of the plot that is ours`() {
        val target = withStrategicResource(tile(2, 0))
        val twin = withStrategicResource(tile(-2, 0))
        val plain = score(target)
        assertEquals("setup: the same score", plain, score(twin))
        game.addUnit("Great General", civ, twin)
        assertEquals("our own general counts for nothing", plain, score(twin))
        game.addUnit("Great General", game.addCiv(), target)
        // (1,0) is the only neighbour of (2,0) in the city's first ring: +50%
        assertEquals(plain * 3 / 2, score(target))
    }

    @Test
    fun `a natural wonder next to the land of someone else counts double`() {
        val wonder = tile(2, 0)
        wonder.naturalWonder = "El Dorado"
        wonder.setTerrainTransients()
        val alone = score(wonder)
        assertTrue(alone > 0)
        game.addCity(game.addCiv(), tile(4, 0)) // owns (3,0), a neighbour of the wonder
        assertEquals(alone * 2, score(wonder))
    }
    //endregion
}
