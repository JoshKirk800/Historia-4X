package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.models.Religion
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's golden age, trade route and religion spread historic events: changeGoldenAgeTurns, CvPlayerTrade::MoveUnits, CityConvertsReligion */
@RunWith(GdxTestRunner::class)
class HistoricEventDispatchTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    /** Met by us; it knows [friend] but not [stranger] */
    private lateinit var met: Civilization
    private lateinit var friend: Civilization
    private lateinit var stranger: Civilization
    private lateinit var capital: City
    private lateinit var metCity: City

    private val landUnique = "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        civ = game.addCiv("[+4] Trade Route slots")
        met = game.addCiv()
        friend = game.addCiv()
        stranger = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero))
        metCity = game.addCity(met, game.getTile(HexCoord(0, 5)))
        game.addCity(friend, game.getTile(HexCoord(5, 0)))
        game.addCity(stranger, game.getTile(HexCoord(-5, 0)))
        for (other in listOf(met, friend, stranger)) civ.diplomacyFunctions.makeCivilizationsMeet(other)
        met.diplomacyFunctions.makeCivilizationsMeet(friend)
        game.ruleset.modOptions.constants.historicEventsLikeVp = true
        game.ruleset.modOptions.constants.historicEventDispatchLikeVp = true
        game.gameInfo.turns = 20
    }

    /** As in HistoricEventTests: with 10 turns, a fifth of 220 culture plus 80 tourism, 124 before the divisor */
    private fun giveHistory(vararg uniques: String) {
        capital.cityConstructions.addBuilding(game.createBuilding("[+20] Tourism [in this city]", *uniques))
        repeat(4) {
            civ.stats.statsForNextTurn = Stats(culture = 30f)
            civ.culture.recordYieldHistory()
        }
        civ.culture.addInstantYieldTotal(false, 210)
    }

    private fun influenceOn(other: Civilization) = civ.culture.influence[other.civID]

    @Test
    fun `a golden age gives a fifth to everyone met and counts`() {
        giveHistory("[10] turns of yields as tourism for golden ages")
        val expected = civ.culture.getInstantInfluenceOn(met, 124 / 5)
        civ.goldenAges.enterGoldenAge()
        assertEquals(expected, influenceOn(met))
        assertEquals(civ.culture.getInstantInfluenceOn(stranger, 124 / 5), influenceOn(stranger))
        assertEquals(1, civ.culture.historicEvents)
    }

    @Test
    fun `a golden age counts with turns even without tourism and not without turns`() {
        civ.goldenAges.enterGoldenAge()
        assertEquals(0, civ.culture.historicEvents)
        capital.cityConstructions.addBuilding(game.createBuilding("[10] turns of yields as tourism for golden ages"))
        civ.goldenAges.enterGoldenAge()
        assertEquals(0, influenceOn(met))
        assertEquals(1, civ.culture.historicEvents)
    }

    @Test
    fun `a land trade route that ends gives its destination all and those it met a third`() {
        giveHistory("[10] turns of yields as tourism for completed land trade routes <in this city>")
        civ.getDiplomacyManager(met)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val caravan = game.createBaseUnit("Civilian", landUnique)
        val route = civ.tradeRoutes.establish(game.addUnit(caravan.name, civ, capital.getCenterTile()), metCity, TradeRouteKind.International)
        assertNotNull(route)
        route!!.circuitsCompleted = route.circuitsToComplete
        val toMet = civ.culture.getInstantInfluenceOn(met, 124 / 15)
        val toFriend = civ.culture.getInstantInfluenceOn(friend, 124 / 15 / 3)

        civ.tradeRoutes.startTurn()

        assertEquals(toMet, influenceOn(met))
        assertEquals(toFriend, influenceOn(friend))
        assertEquals(0, influenceOn(stranger))
        assertEquals(1, civ.culture.historicEvents)
    }

    @Test
    fun `a sea trade route reads the sea turns of its city of origin`() {
        giveHistory("[10] turns of yields as tourism for completed sea trade routes <in this city>")
        val other = game.addCity(civ, game.getTile(HexCoord(3, -3)))
        civ.culture.onTradeRouteEnded(false, capital, metCity)
        civ.culture.onTradeRouteEnded(true, other, metCity)
        assertEquals("not a land route, and not from a city without the turns", 0, civ.culture.historicEvents)
        civ.culture.onTradeRouteEnded(true, capital, metCity)
        assertEquals(civ.culture.getInstantInfluenceOn(met, 124 / 15), influenceOn(met))
        assertEquals(1, civ.culture.historicEvents)
    }

    @Test
    fun `a trade route with turns but no tourism does not count`() {
        capital.cityConstructions.addBuilding(game.createBuilding("[10] turns of yields as tourism for completed land trade routes"))
        civ.culture.onTradeRouteEnded(false, capital, metCity)
        assertEquals(0, civ.culture.historicEvents)
    }

    @Test
    fun `a domestic trade route gives a third to everyone met only with the unique`() {
        giveHistory("[10] turns of yields as tourism for completed land trade routes")
        val other = game.addCity(civ, game.getTile(HexCoord(3, -3)))
        civ.culture.onTradeRouteEnded(false, capital, other)
        assertEquals(0, civ.culture.historicEvents)

        capital.cityConstructions.addBuilding(game.createBuilding("Domestic trade routes give tourism for completed trade routes"))
        civ.culture.onTradeRouteEnded(false, capital, other)
        assertEquals(civ.culture.getInstantInfluenceOn(stranger, 124 / 15 / 3), influenceOn(stranger))
        assertEquals(civ.culture.getInstantInfluenceOn(met, 124 / 15 / 3), influenceOn(met))
        assertEquals(1, civ.culture.historicEvents)
    }

    @Test
    fun `a trade route to a City-State gives a fifth to everyone met`() {
        giveHistory("[10] turns of yields as tourism for completed trade routes to City-States")
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val cityStateCity = game.addCity(cityState, game.getTile(HexCoord(0, -5)))
        civ.culture.onTradeRouteEnded(false, capital, cityStateCity)
        assertEquals(civ.culture.getInstantInfluenceOn(stranger, 124 / 5), influenceOn(stranger))
        assertEquals(1, civ.culture.historicEvents)
    }

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    @Test
    fun `a city adopting our state religion the first time gives a fifteenth to everyone met`() {
        giveHistory("[10] turns of yields as tourism for religion spread")
        val ours = game.addReligion(civ)
        ours.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        capital.religion.religionThisIsTheHolyCityOf = ours.name
        capital.follow(ours)
        assertEquals("the holy city adopting it is the first event", 1, civ.culture.historicEvents)
        val theirs = game.addReligion(met)
        theirs.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val before = influenceOn(stranger)
        val expected = civ.culture.getInstantInfluenceOn(stranger, 124 / 15)

        metCity.follow(ours)
        assertEquals(before + expected, influenceOn(stranger))
        assertEquals(2, civ.culture.historicEvents)

        metCity.follow(theirs)
        metCity.follow(ours)
        assertEquals("not the first time", 2, civ.culture.historicEvents)
    }

    @Test
    fun `without the switch nothing is dispatched`() {
        game.ruleset.modOptions.constants.historicEventDispatchLikeVp = false
        giveHistory("[10] turns of yields as tourism for golden ages", "[10] turns of yields as tourism for completed land trade routes")
        civ.goldenAges.enterGoldenAge()
        civ.culture.onTradeRouteEnded(false, capital, metCity)
        assertEquals(0, civ.culture.historicEvents)
        assertEquals(0, influenceOn(met))
    }
}
