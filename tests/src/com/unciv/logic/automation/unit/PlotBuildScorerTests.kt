package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvBuilderTaskingAI::ScorePlotBuild and GetResourceWeight ([PlotBuildScorer], [resourceWeight]) with Vox Populi's prices
 *  (food 180, production 180, gold 90, science 240, culture 240, faith 240) and the BNW rules of our ruleset (luxury happiness 4).
 */
@RunWith(GdxTestRunner::class)
class PlotBuildScorerTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]
    private fun research(vararg techs: String) { for (tech in techs) civ.tech.techsResearched.add(tech) }
    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!
    private fun scorer() = PlotBuildScorer(civ).also { it.updateAllCityWorstPlots() }

    /** The city works exactly [worked], nothing else */
    private fun City.workOnly(worked: Tile) {
        clearWorkedTiles()
        workTile(worked)
    }

    @Test
    fun `the yield score prices every yield of the plot with the build`() {
        research("Agriculture")
        game.addCity(civ, tile(0, 0))
        val farm = improvement("Farm")
        val score = scorer().scorePlotBuild(tile(1, 0), farm, Build(farm))
        // A farm on grassland: 3 food at 180 each, nothing else
        assertEquals(3 * 180, score.score)
        assertEquals(0, score.potentialScore)
    }

    @Test
    fun `keeping the improvement scores what the plot yields now and 100 for not building`() {
        research("Agriculture")
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0).also { it.setImprovement("Farm") }
        assertEquals(3 * 180 + 100, scorer().scorePlotBuild(plot, improvement("Farm"), null).score)
        // A bare plot with nothing to keep has no inherent value, only its yields: 2 food
        assertEquals(2 * 180, scorer().scorePlotBuild(tile(0, 1), null, null).score)
    }

    @Test
    fun `a plot of a city being razed or outside our borders scores minus one`() {
        research("Agriculture")
        val city = game.addCity(civ, tile(0, 0))
        val farm = improvement("Farm")
        assertEquals(PlotBuildScore(-1), scorer().scorePlotBuild(tile(8, 0), farm, Build(farm)))
        city.isBeingRazed = true
        assertEquals(PlotBuildScore(-1), scorer().scorePlotBuild(tile(1, 0), farm, Build(farm)))
    }

    @Test
    fun `gold is smoothed for golden ages so that a golden age does not change the score`() {
        val city = game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0)
        val tradingPost = improvement("Trading post")
        val build = Build(tradingPost)
        val scorer = scorer()
        val projected = scorer.projectedYields(plot, build, city)
        assertTrue("setup: the trading post gives gold", projected[PlotBuildScorer.YIELDS.indexOf(Stat.Gold)] > 0)

        fun expected(goldAdjustment: Int): Int {
            var sum = 0
            for ((i, stat) in PlotBuildScorer.YIELDS.withIndex()) {
                val times100 = 100 * projected[i] + (if (stat == Stat.Gold) goldAdjustment else 0)
                sum += times100 * scorer.yieldBaseModifierTimes100(stat) / 100
            }
            return sum
        }
        val outside = scorer.scorePlotBuild(plot, tradingPost, build)
        assertEquals("not in a golden age: +40 for the gold of the golden age that is ahead", expected(40), outside.score)

        civ.goldenAges.enterGoldenAge(10)
        val inside = PlotBuildScorer(civ).scorePlotBuild(plot, tradingPost, build)
        // The tile stats include the golden age's gold now; the smoothing takes 60 back
        val goldenProjected = PlotBuildScorer(civ).projectedYields(plot, build, city)
        assertEquals(projected[PlotBuildScorer.YIELDS.indexOf(Stat.Gold)] + 1, goldenProjected[PlotBuildScorer.YIELDS.indexOf(Stat.Gold)])
        assertEquals(outside.score, inside.score)
    }

    @Test
    fun `a puppet city has its science and culture priced down by a quarter`() {
        val city = game.addCity(civ, tile(0, 0))
        val scorer = scorer()
        assertEquals(100, scorer.yieldCityModifierTimes100(city, Stat.Science))
        city.isPuppet = true
        assertEquals(75, scorer.yieldCityModifierTimes100(city, Stat.Science))
        assertEquals(75, scorer.yieldCityModifierTimes100(city, Stat.Culture))
        assertEquals(100, scorer.yieldCityModifierTimes100(city, Stat.Food))
        assertEquals(100, scorer.yieldCityModifierTimes100(city, Stat.Gold))
        assertEquals(180, scorer.yieldBaseModifierTimes100(Stat.Food))
        assertEquals(90, scorer.yieldBaseModifierTimes100(Stat.Gold))
        assertEquals(240, scorer.yieldBaseModifierTimes100(Stat.Culture))
        assertEquals(game.ruleset.ai.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_FAITH), scorer.yieldBaseModifierTimes100(Stat.Faith))
    }

    @Test
    fun `the yield is unused when the plot is no better than the worst plot every city works`() {
        research("Agriculture")
        val city = game.addCity(civ, tile(0, 0))
        val rich = tile(2, 0).also { city.expansion.takeOwnership(it); it.setTileResource("Wheat"); it.setImprovement("Farm") }
        val farm = improvement("Farm")
        val plot = tile(1, 0)

        // The city works only the farmed wheat (4 food): a plain farm (3 food) would feed nobody
        city.workOnly(rich)
        val unused = scorer().scorePlotBuild(plot, farm, Build(farm))
        assertTrue(unused.unusedYieldScore > 0)
        assertEquals(unused.score, unused.unusedYieldScore)
        assertEquals(unused.potentialScore / 3, unused.priorityScore)

        // The city works a bare grassland (2 food): the farm is an improvement for it
        city.workOnly(tile(-1, 0))
        val used = scorer().scorePlotBuild(plot, farm, Build(farm))
        assertEquals(0, used.unusedYieldScore)
        assertEquals(3 * 180, used.priorityScore)
    }

    @Test
    fun `connecting a luxury adds its weight, two thirds of it moves to the potential when plenty is available`() {
        research("Calendar")
        val city = game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0).also { it.setTileResource("Wine"); it.resourceAmount = 1 }
        val plantation = improvement("Plantation")
        val build = Build(plantation)
        val weight = 4 * 750 * 1

        val none = scorer().scorePlotBuild(plot, plantation, build)
        assertEquals(0, civ.getResourceAmount("Wine"))

        city.cityConstructions.addBuilding(game.createBuilding("Provides [1] [Wine]"))
        civ.cache.updateCivResources()
        assertEquals(1, civ.getResourceAmount("Wine"))
        val one = PlotBuildScorer(civ).scorePlotBuild(plot, plantation, build)
        assertEquals("one already: a third of the weight becomes potential", none.score - weight / 3, one.score)
        assertEquals(none.potentialScore + weight / 3, one.potentialScore)

        city.cityConstructions.addBuilding(game.createBuilding("Provides [1] [Wine]"))
        civ.cache.updateCivResources()
        assertEquals(2, civ.getResourceAmount("Wine"))
        val plenty = PlotBuildScorer(civ).scorePlotBuild(plot, plantation, build)
        assertEquals("plenty: two thirds", none.score - weight * 2 / 3, plenty.score)
        assertEquals(none.potentialScore + weight * 2 / 3, plenty.potentialScore)
    }

    @Test
    fun `the resource a plot's improvement connects does not count as available for the score of keeping it`() {
        research("Calendar")
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0).also { it.setTileResource("Wine"); it.resourceAmount = 1 }
        val plantation = improvement("Plantation")
        val before = scorer().scorePlotBuild(plot, plantation, Build(plantation))
        plot.setImprovement("Plantation")
        civ.cache.updateCivResources()
        assertEquals("setup: the plantation connects the wine", 1, civ.getResourceAmount("Wine"))
        val keep = scorer().scorePlotBuild(plot, plantation, null)
        // The same weight in the base score and nothing moved to the potential, though one wine is available
        assertEquals(before.potentialScore, keep.potentialScore)
        assertTrue(keep.score >= 4 * 750)
    }

    @Test
    fun `a plot with an antiquity site we can dig only takes the dig`() {
        research("Agriculture")
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0).also { it.setTileResource("Antiquity site") }
        val farm = improvement("Farm")
        assertTrue("not revealed yet: a plot like any other", scorer().scorePlotBuild(plot, farm, Build(farm)).maxScore > 0)

        research("Archaeology")
        assertEquals(PlotBuildScore(-1), scorer().scorePlotBuild(plot, farm, Build(farm)))
        val dig = improvement("Archaeological Dig")
        assertTrue(scorer().scorePlotBuild(plot, dig, Build(dig)).score != -1)
    }

    @Test
    fun `a chop that helps a rush adds the feature's production at the production price over 50`() {
        research("Agriculture", "Bronze Working")
        val city = game.addCity(civ, tile(0, 0))
        val forest = tile(1, 0).also { it.addTerrainFeature("Forest") }
        val farm = improvement("Farm")
        val build = Build(farm, improvement("Remove Forest"))

        city.cityConstructions.setCurrentConstruction("Manhattan Project")
        val rushing = scorer().also { assertTrue("setup: the build helps a rush", it.doesBuildHelpRush(forest, build)) }
        val production = rushing.chopProduction(forest, build)
        assertTrue(production > 0)
        val withRush = rushing.scorePlotBuild(forest, farm, build).score

        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Gold.name)
        val calm = scorer().also { assertTrue(!it.doesBuildHelpRush(forest, build)) }
        assertEquals(production * 180 / 50, withRush - calm.scorePlotBuild(forest, farm, build).score)
    }

    @Test
    fun `the weight of a luxury is its happiness times 750 times the quantity, a strategic one quantity plus one times 250`() {
        research("Bronze Working")
        fun resource(name: String) = game.ruleset.tileResources[name]!!
        assertEquals(4 * 750 * 2, resourceWeight(civ, resource("Gems"), 2))
        assertEquals("iron, an ancient resource", (3 + 1) * 250, resourceWeight(civ, resource("Iron"), 3))
        assertEquals("coal is revealed in the Industrial era: ten times", (1 + 1) * 250 * 10, resourceWeight(civ, resource("Coal"), 1))
        assertEquals("a bonus resource is worth nothing", 0, resourceWeight(civ, resource("Wheat"), 1))
    }

    @Test
    fun `a luxury the World Congress banned has no weight`() {
        val league = League().also { game.gameInfo.league = it }
        league.activeResolutions.add(ActiveResolution(1, "Ban Luxury", null, "Gems", 0))
        league.setTransients(game.gameInfo)
        assertEquals(0, resourceWeight(civ, game.ruleset.tileResources["Gems"]!!, 2))
        assertEquals(4 * 750, resourceWeight(civ, game.ruleset.tileResources["Wine"]!!, 1))
    }
}
