package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.Religion
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `religiousSpreadLikeVp`: natural pressure as CvGameReligions::SpreadReligionToOneCity spreads it (V:CvReligionClasses.cpp:358-445). A city reaches another along a trade route
 * in either direction, or along a cached trade path under 100% of the sender's trade range (IsCityConnectedToCity, 534-632); without a route the pressure falls with the
 * square of that percentage, and the percentages add up (GetAdjacentCityReligiousPressure, 2801-2950).
 *
 * The map is grassland without roads: a trade step costs 101, so a path of n tiles costs 101n and is (101n + 1)% of a range of 10 tiles; the base pressure is set to 1000.
 */
@RunWith(GdxTestRunner::class)
class ReligiousSpreadTests {
    private lateinit var game: TestGame
    private lateinit var founder: Civilization
    private lateinit var religion: Religion

    private val strengthUnique = "[+100]% Natural religion spread [in all cities]"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.religiousSpreadLikeVp = true
        game.gameInfo.speed.religiousPressureAdjacentCity = 1000
        founder = game.addCiv()
        religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder))) // a major religion
        // The founder owns the holy city, which follows nothing and so spreads nothing
        val holyCity = game.addCity(founder, game.getTile(8, 0))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
    }

    /** A city of [owner] at ([x], [y]) whose majority is the religion */
    private fun sender(owner: Civilization, x: Int, y: Int): City {
        val city = game.addCity(owner, game.getTile(x, y))
        city.religion.addPressure(religion.name, 5000)
        return city
    }

    private fun receiver(x: Int, y: Int, owner: Civilization = game.addCiv()) = game.addCity(owner, game.getTile(x, y))

    private fun pressureOn(city: City) = city.religion.getPressuresFromSurroundingCities()[religion.name]

    private fun addRoute(from: City, to: City) {
        val route = TradeRoute(from.location, to.location, TradeRouteKind.International, false, "Caravan", listOf(from.location, to.location), 2, 30, 0)
        route.setTransients(from.civ)
        from.civ.tradeRoutes.routes.add(route)
    }

    @Test
    fun `pressure falls with the square of the share of the trade range`() {
        sender(founder, 0, 0)
        // 4 tiles: 405% / 10 -> 40%, 1000 * 60 * 60 / 10000
        assertEquals(360, pressureOn(receiver(0, 4)))
    }

    @Test
    fun `a city ten tiles away is out of reach and one eight tiles away gets little`() {
        sender(founder, 0, -5)
        val tenAway = receiver(0, 5)
        val eightAway = receiver(0, 3)
        assertEquals(0, pressureOn(tenAway)) // 1011 of 1000: 101%
        assertEquals(40, pressureOn(eightAway)) // 809 of 1000: 80%, 1000 * 20 * 20 / 10000

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        assertEquals(1000, pressureOn(tenAway)) // within the 10 tiles of the engine, the whole base
    }

    @Test
    fun `the path cost counts one more than its steps`() {
        sender(founder, 0, -5)
        // 9 tiles: 909 + 1 of 1000 is 91%, 1000 * 9 * 9 / 10000 (90% would give 10)
        assertEquals(8, pressureOn(receiver(0, 4)))
    }

    @Test
    fun `a path of exactly the range is out of reach`() {
        game.gameInfo.speed.religiousPressureAdjacentCity = 10000 // so that 1% would give 1
        sender(founder, 0, -6)
        for (y in -5..-2) game.getTile(0, y).roadStatus = RoadStatus.Road
        // 4 road steps of 50 and 8 plain steps of 101 to 12 tiles away: 1008 + 1 of 1000 is 100%
        assertEquals(0, pressureOn(receiver(0, 6)))
    }

    @Test
    fun `a spread distance tile lengthens the range by ten percent`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Follower, "Religion naturally spreads to cities [+3] tiles away")))
        sender(founder, 0, -5)
        // 1011 of 1300: 77%, 1000 * 23 * 23 / 10000
        assertEquals(52, pressureOn(receiver(0, 5)))
    }

    @Test
    fun `a trade route in either direction carries the whole base raised by the sender's trade pressure once`() {
        val arabia = game.addCiv("[+100]% religious pressure along trade routes that start in our cities")
        val from = sender(arabia, 0, -6)
        val to = receiver(0, 6) // 12 tiles: no path
        addRoute(to, from)
        assertEquals(2000, pressureOn(to))
        addRoute(from, to)
        assertEquals(2000, pressureOn(to))
    }

    @Test
    fun `the trade pressure of the sender also counts for a route that starts in the receiver`() {
        val arabia = game.addCiv("[+100]% religious pressure along trade routes that start in our cities")
        val from = sender(arabia, 0, -6)
        val to = receiver(0, 6)
        addRoute(to, from)
        assertEquals(2000, pressureOn(to))

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        assertEquals(1000, pressureOn(to))
    }

    @Test
    fun `spread strength percentages add up before they divide`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Follower, "[+50]% Natural religion spread [in all cities]")))
        val civ = game.addCiv("[+50]% Natural religion spread [in all cities]")
        sender(civ, 0, 0)
        val to = receiver(0, 4)
        assertEquals(720, pressureOn(to)) // 360 * (100 + 50 + 50) / 100

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        assertEquals(2250, pressureOn(to)) // 1000 * 1.5 * 1.5
    }

    @Test
    fun `a Founder belief strengthens the spread of the holy city's owner`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, strengthUnique)))
        sender(founder, 0, 0)
        assertEquals(720, pressureOn(receiver(0, 4)))
    }

    @Test
    fun `a Founder belief does not strengthen the spread of a foreign city that follows the religion`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, strengthUnique)))
        sender(game.addCiv(), 0, 0)
        val to = receiver(0, 4)
        assertEquals(360, pressureOn(to))

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        assertEquals(2000, pressureOn(to))
    }

    @Test
    fun `city-states cache no trade paths, so two of their cities do not reach each other`() {
        sender(game.addCiv(cityStateType = "Cultured"), 0, 0)
        val cityState = receiver(0, 4, game.addCiv(cityStateType = "Cultured"))
        assertEquals(0, pressureOn(cityState))
        val major = receiver(4, 4) // 4 tiles from the sender: the major's path back reaches it
        assertEquals(360, pressureOn(major))

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        assertEquals(1000, pressureOn(cityState))
    }

    @Test
    fun `a trade route would add the whole base less the pressure without one`() {
        val from = sender(founder, 0, 0)
        val to = receiver(0, 4)
        assertEquals(religion.name to 640, from.religion.wouldExertTradeRoutePressureToward(to))
        addRoute(to, from)
        assertNull(from.religion.wouldExertTradeRoutePressureToward(to))

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        to.civ.tradeRoutes.routes.clear()
        assertNull(from.religion.wouldExertTradeRoutePressureToward(to)) // within the 10 tiles of the engine
    }
}
