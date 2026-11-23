package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.city.NeedType
import com.unciv.logic.city.NeedYields
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteValue
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terms of Vox Populi's trade route scoring that read what the route rules and the route value put into the engine (slice T6 of `phaseE/vp-trade-plan.md`):
 * CvTradeAI::ScoreInternationalTR and ScoreInternalTR (V:CvTradeClasses.cpp:6538-7110, 7372-7585) and AddTradePathToCache (169-225), with `tradeRouteModelLikeVp` on.
 * Every number is worked out from the cited lines.
 */
@RunWith(GdxTestRunner::class)
class VpTradeScoreTermsTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var caravan: BaseUnit
    private lateinit var origin: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = true
        civA = game.addCiv()
        civB = game.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.getDiplomacyManager(civB)!!.makePeace()
        caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        for (tile in game.tileMap.values) tile.setExplored(civA, true)
        origin = game.addCity(civA, game.tileMap[0, 0])
    }

    private fun cityOf(civ: Civilization, y: Int) = game.addCity(civ, game.tileMap[0, y])

    private fun giveTechs(civ: Civilization, count: Int) {
        for (tech in game.ruleset.technologies.keys.filter { !civA.tech.isResearched(it) && !civB.tech.isResearched(it) }.take(count)) civ.tech.addTechnology(tech)
    }

    private fun setEra(civ: Civilization, number: Int) { civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == number } }

    //region Science and culture

    @Test
    fun `the AI's science estimate is the technology difference over 1_25, not the square root of the route`() {
        giveTechs(civB, 9)
        // V:CvTradeClasses.cpp:6721-6724: 9 x 100 / TRADE_ROUTE_SCIENCE_DIVISOR_TIMES100 (125) = 7, at least 1; era 0 does not multiply
        assertEquals(7, VpTradeScoreTerms.originScience(civA, civB))
        // the route's real yield is int(sqrt(9) x 200 / 125) = 4 (V:CvTradeClasses.cpp:2775-2780)
        assertEquals(4, TradeRouteValue.scaledDifference(9, 125))
        assertEquals("civB lacks nothing of ours", 0, VpTradeScoreTerms.destinationScience(civA, civB))
        setEra(civA, 2)
        assertEquals("times the era", 14, VpTradeScoreTerms.originScience(civA, civB))
        assertEquals("one technology is worth at least 1", 1, VpTradeScoreTerms.estimate(1, 125, civB))
    }

    @Test
    fun `the culture of the destination is multiplied by the era of the origin as the original does`() {
        for (i in 1..3) civA.policies.adopt(game.createPolicy(), true)
        setEra(civA, 2)
        assertEquals("we have 3 policies more than they: nothing for us", 0, VpTradeScoreTerms.originCulture(civA, civB))
        // 3 x 100 / 125 = 2, times the era of civA (2), not of civB (0): V:CvTradeClasses.cpp:6877
        assertEquals(4, VpTradeScoreTerms.destinationCulture(civA, civB))
    }

    //endregion
    //region Needs of the origin city

    @Test
    fun `a city short of gold, science or culture adds its unhappy citizens to what a route is worth`() {
        val world = NeedsTestWorld()
        world.addCity(4)
        val target = world.addCity(4)
        world.medians.basic = 300
        world.medians.gold = 300
        world.medians.science = 200
        world.medians.culture = 100
        world.needsOf(target).update(NeedYields(gold = 600, science = 200, culture = 0, foodBeforeConsumption = 99999))
        // the needs of CityNeedsTests' worked example: ceil(4 - 600 / 318) = 3, ceil(4 - 200 / 212) = 4, ceil(4 - 0 / 106) = 4
        assertEquals(3, VpTradeScoreTerms.needBonus(target, NeedType.Poverty))
        assertEquals(4, VpTradeScoreTerms.needBonus(target, NeedType.Illiteracy))
        assertEquals(4, VpTradeScoreTerms.needBonus(target, NeedType.Boredom))
        world.constants.cityNeedsLikeVp = false
        assertEquals("without the needs model nobody is short of anything", 0, VpTradeScoreTerms.needBonus(target, NeedType.Poverty))
    }

    //endregion
    //region What happens when a route ends

    @Test
    fun `the yields of the buildings when a route ends are known by kind`() {
        origin.cityConstructions.addBuilding(game.createBuilding("Gain [30] [Gold] <upon completing a [international] trade route> <in this city>"))
        origin.cityConstructions.addBuilding(game.createBuilding("Gain [5] [Gold] <upon completing a [any] trade route> <in this city>"))
        origin.cityConstructions.addBuilding(game.createBuilding("Gain [7] [Science] <upon completing a [domestic] trade route> <in this city>"))
        origin.cityConstructions.addBuilding(game.createBuilding("Gain [100] [Gold] <upon completing a [international] trade route>"))
        assertEquals("30 and the 5 for any kind; the building of the whole civilization is not the city's table", 35, VpTradeScoreTerms.routeEndYield(origin, Stat.Gold, true))
        assertEquals(5, VpTradeScoreTerms.routeEndYield(origin, Stat.Gold, false))
        assertEquals(0, VpTradeScoreTerms.routeEndYield(origin, Stat.Science, true))
        assertEquals(7, VpTradeScoreTerms.routeEndYield(origin, Stat.Science, false))
        assertEquals(listOf(Stat.Gold to 5, Stat.Science to 7), VpTradeScoreTerms.routeEndYields(origin, false))
    }

    @Test
    fun `an international route that ends with a yield scores more`() {
        val far = cityOf(civB, 6)
        giveTechs(civB, 3)
        val candidate = TradeRouteAutomation.getAvailableRoutes(civA).first { it.destination.city == far && it.destination.kind == TradeRouteKind.International }
        val before = VpInternationalTradeScore(civA, VpTradePathScore(civA)).score(candidate)
        assertTrue("setup: the route is worth sending", before > 0)
        origin.cityConstructions.addBuilding(game.createBuilding("Gain [3000] [Science] <upon completing a [international] trade route> <in this city>"))
        // 3000 per the route's turns (V:CvTradeClasses.cpp:6772-6775): the science term of the origin
        assertTrue(VpInternationalTradeScore(civA, VpTradePathScore(civA)).score(candidate) > before)
    }

    //endregion
    //region Passing routes

    @Test
    fun `a city of ours on the path that no route passes yet adds what its buildings give while one passes`() {
        val middle = cityOf(civA, 3)
        middle.cityConstructions.addBuilding(game.createBuilding("[+3 Gold, +2 Culture] while any trade route passes through this city"))
        val path = (0..6).map { game.tileMap[0, it] }
        // V:CvTradeClasses.cpp:205-210: all yields added up
        assertEquals(5, VpTradeScoreTerms.passingRouteScore(civA, path))
        assertEquals("not for a civilization whose city it is not", 0, VpTradeScoreTerms.passingRouteScore(civB, path))

        // a route of anybody over the city: it gets the yield already, and the path adds nothing (the original asks CvPlot::IsTradeUnitRoute)
        val other = cityOf(civB, 8)
        val route = TradeRoute(other.location, origin.location, TradeRouteKind.International, false, caravan.name, (8 downTo 0).map { game.tileMap[0, it].position }, 2, 30, 0)
        route.setTransients(civB)
        civB.tradeRoutes.routes.add(route)
        civB.tradeRoutes.updateYields()
        assertEquals(0, VpTradeScoreTerms.passingRouteScore(civA, path))
    }

    //endregion
}
