package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvCityStrategyAI::PrecalcYieldStats (Release-5.4.8): the expected yield per citizen is 0.5 food, 1 production, 1.5 gold,
 *  2.5 science, 2.5 culture and 2.5 faith (AI_CITYSTRATEGY_YIELD_DEFICIENT_*), against the city's yield x 100 per citizen.
 */
@RunWith(GdxTestRunner::class)
class YieldDeficiencyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0], initialPopulation = 4)
    }

    /** The city makes [stats] a turn */
    private fun precalc(stats: Stats) {
        city.cityStats.currentCityStats = stats
        CityStrategyAi.precalcYieldStats(city)
    }

    /** Exactly the expected yield of a city of 4 */
    private fun expected() = Stats(food = 2f, production = 4f, gold = 6f, science = 10f, culture = 10f, faith = 10f)

    @Test
    fun `a city yielding exactly the expected has no deficient or abundant yield`() {
        precalc(expected())
        assertNull(city.ai.mostDeficientYield)
        assertNull(city.ai.mostAbundantYield)
    }

    @Test
    fun `the yield furthest below the expected is the most deficient, the one furthest above the most abundant`() {
        // production 100 above, gold 25 below, science 150 below
        precalc(expected().apply { production = 8f; gold = 5f; science = 4f })
        assertEquals(Stat.Science, city.ai.mostDeficientYield)
        assertEquals(Stat.Production, city.ai.mostAbundantYield)
    }

    @Test
    fun `a tie goes to the later yield when deficient and to the earlier one when abundant`() {
        // science, culture and faith 250 below, gold 150, production 100, food 50: nothing above
        precalc(Stats())
        assertEquals(Stat.Faith, city.ai.mostDeficientYield)
        assertNull(city.ai.mostAbundantYield)
        // science and culture 100 above, nothing below
        precalc(expected().apply { science = 14f; culture = 14f })
        assertEquals(Stat.Science, city.ai.mostAbundantYield)
        assertNull(city.ai.mostDeficientYield)
    }

    @Test
    fun `the yield per citizen is taken in whole hundredths`() {
        // 2.03 food for 4 citizens is 50 per citizen, 1.6 is 40
        precalc(expected().apply { food = 2.03f })
        assertNull(city.ai.mostDeficientYield)
        precalc(expected().apply { food = 1.6f })
        assertEquals(Stat.Food, city.ai.mostDeficientYield)
    }

    @Test
    fun `the gold of the original has no building maintenance`() {
        city.cityStats.finalStatList["Maintenance"] = Stats(gold = -3f)
        precalc(expected().apply { gold = 3f }) // 3 left after the buildings: 6 made
        assertNull(city.ai.mostDeficientYield)
        city.cityStats.finalStatList.clear()
        precalc(expected().apply { gold = 3f })
        assertEquals(Stat.Gold, city.ai.mostDeficientYield)
    }

    @Test
    fun `a city strategy turn works the yields out and a conquered city forgets them`() {
        city.cityStats.currentCityStats = Stats(production = 4f)
        city.ai.doTurn()
        assertEquals(Stat.Faith, city.ai.mostDeficientYield)
        city.ai.reset()
        assertNull(city.ai.mostDeficientYield)
    }
}
