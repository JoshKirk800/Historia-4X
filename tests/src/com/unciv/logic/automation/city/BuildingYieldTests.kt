package com.unciv.logic.automation.city

import com.unciv.logic.automation.strategy.BuildingYieldAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/** Edge cases of the yield loop of CheckBuildingBuildSanity and of CityStrategyAIHelpers::GetBuildingYieldValue (BNW rules) */
@RunWith(GdxTestRunner::class)
class BuildingYieldTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0])
    }

    private fun rate(stat: Stat) = max(1, city.cityStats.currentCityStats[stat].toInt())

    @Test
    fun `a flat yield is worth its percentage of the city's rate, at least 1 per turn in the rate`() {
        val granary = game.createBuilding().apply { food = 2f }
        assertEquals(2 * 100 / rate(Stat.Food), BuildingYieldAi.yieldValue(city, granary, Stat.Food))
        assertEquals(0, BuildingYieldAi.yieldValue(city, granary, Stat.Gold))
    }

    @Test
    fun `a small city counts a food yield tenfold, and not at all when it avoids growth`() {
        val granary = game.createBuilding().apply { food = 2f }
        val value = BuildingYieldAi.yieldValue(city, granary, Stat.Food)
        assertEquals(value * 10, BuildingYieldAi.yieldTerms(city, granary).bonus)
        city.avoidGrowth = true
        assertEquals(0, BuildingYieldAi.yieldTerms(city, granary).bonus)
    }

    @Test
    fun `a gold yield is worth 10 more per gold lost each turn and then the maintenance does not refuse it`() {
        val market = game.createBuilding().apply { gold = 1f }
        val value = BuildingYieldAi.yieldValue(city, market, Stat.Gold)
        assertEquals(value, BuildingYieldAi.yieldTerms(city, market).bonus)
        assertFalse(BuildingYieldAi.yieldTerms(city, market).goodForGold)
        civ.stats.statsForNextTurn.gold = -3f
        val terms = BuildingYieldAi.yieldTerms(city, market)
        // the loss is added to the yield value and, as that is above 0, to the trait value too
        assertEquals(value + 30 + 30, terms.bonus)
        assertTrue(terms.goodForGold)
    }
}
