package com.unciv.logic.automation.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 *  CvBuilderTaskingAI::UpdateFutureYields and GetFutureYields ([FutureYields]) and their use in ScorePlotBuild's potential score:
 *  the BNW rules' Improvement_TechYieldChanges (improvement uniques "after discovering") and Policy_ImprovementYieldChanges (policy
 *  uniques "from every [improvement]").
 */
@RunWith(GdxTestRunner::class)
class FutureYieldsTests {
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

    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!
    private fun column(tech: String) = game.ruleset.technologies[tech]!!.column!!.columnNumber
    private fun research(vararg techs: String) { for (tech in techs) civ.tech.techsResearched.add(tech) }
    private fun futureYields(vararg improvements: String) = FutureYields(civ).also { it.update(improvements.map(::improvement)) }

    @Test
    fun `a tech's yield counts less the further its column lies beyond the average of our latest and the earliest missing tech`() {
        val economics = column("Economics")
        assertTrue("setup: Economics is within reach of the weights", economics in 1..9)
        // Nothing researched: the latest column is 0 and the earliest missing is 0, so the average is 0
        assertEquals(1 * (100 - economics * 10), futureYields("Village")[improvement("Village"), Stat.Gold])

        // Everything before Economics researched: latest = economics - 1, earliest missing = economics, average = economics - 1
        for (tech in game.ruleset.technologies.values) if (tech.column!!.columnNumber < economics) research(tech.name)
        assertEquals(1 * 90, futureYields("Village")[improvement("Village"), Stat.Gold])
    }

    @Test
    fun `a tech ten or more columns away counts nothing and a researched one is not future`() {
        val academy = improvement("Academy")
        fun weight(tech: String) = max(100 - column(tech) * 10, 0)
        assertEquals("Scientific Theory and Atomic Theory, 2 science each", 2 * (weight("Scientific Theory") + weight("Atomic Theory")),
            futureYields("Academy")[academy, Stat.Science])
        assertEquals("setup: Atomic Theory is beyond the horizon of a civilization with nothing researched", 0, weight("Atomic Theory"))

        research("Economics")
        assertEquals("Economics is researched: no future gold", 0, futureYields("Village")[improvement("Village"), Stat.Gold])
    }

    @Test
    fun `an improvement or a yield without a future one has none`() {
        val futureYields = futureYields("Village")
        assertEquals(0, futureYields[improvement("Village"), Stat.Food])
        // Not part of the update: Academy is neither buildable nor on a plot of ours
        assertEquals(0, futureYields[improvement("Academy"), Stat.Science])
    }

    @Test
    fun `an improvement on one of our plots is considered without being a possible build`() {
        game.addCity(civ, game.tileMap[HexCoord(0, 0)])
        game.tileMap[HexCoord(1, 0)].setImprovement("Academy")
        val futureYields = FutureYields(civ).also { it.update(emptyList()) }
        assertTrue(futureYields[improvement("Academy"), Stat.Science] > 0)
    }

    @Test
    fun `the policies of an adopted branch that are not adopted give their yield at 80`() {
        val tradingPost = improvement("Village")
        // Economics is researched: only the policy remains
        research("Economics")
        assertEquals("no branch adopted", 0, futureYields("Village")[tradingPost, Stat.Gold])

        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Commerce"]!!)
        assertEquals("Commerce Complete: +1 gold from every Village", 1 * 80, futureYields("Village")[tradingPost, Stat.Gold])
    }

    @Test
    fun `tech and policy yields add up and go into the potential score at the price of the yield`() {
        game.addCity(civ, game.tileMap[HexCoord(0, 0)])
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Commerce"]!!)
        val tradingPost = improvement("Village")
        val scorer = PlotBuildScorer(civ)
        scorer.updateAllCityWorstPlots()
        scorer.futureYields.update(listOf(tradingPost))

        val economics = column("Economics")
        val gold = 1 * (100 - economics * 10) + 1 * 80
        assertEquals(gold, scorer.futureYields[tradingPost, Stat.Gold])
        val score = scorer.scorePlotBuild(game.tileMap[HexCoord(1, 0)], tradingPost, Build(tradingPost))
        assertEquals(gold * scorer.yieldBaseModifierTimes100(Stat.Gold) / 100, score.potentialScore)
    }
}
