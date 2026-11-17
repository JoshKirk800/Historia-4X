package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteManager
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The decisions of CvTradeAI: candidate connections, their scores, their order, and the choice of a trade unit */
@RunWith(GdxTestRunner::class)
class TradeRouteAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var caravan: BaseUnit
    private lateinit var origin: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        civA = game.addCiv("[+4] Trade Route slots")
        civB = game.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.getDiplomacyManager(civB)!!.makePeace()
        caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        for (tile in game.tileMap.values) tile.setExplored(civA, true)
        origin = game.addCity(civA, game.tileMap[0, 0])
    }

    /** Gives civB technologies we lack: with equal cities the gold delta is 0 and only the technology delta makes a connection worth taking */
    private fun giveCivBTechs() {
        for (tech in game.ruleset.technologies.keys.filter { !civB.tech.isResearched(it) }.take(3)) civB.tech.addTechnology(tech)
    }

    private fun cityOf(civ: Civilization, y: Int) = game.addCity(civ, game.tileMap[0, y])

    private fun candidateTo(destination: City) =
        TradeRouteAutomation.getAvailableRoutes(civA).first { it.destination.city == destination && it.destination.kind == TradeRouteKind.International }

    @Test
    fun `candidates are the connections in range that can be started`() {
        val near = cityOf(civB, 6)
        val far = cityOf(civB, 12)
        val destinations = TradeRouteAutomation.getAvailableRoutes(civA).map { it.destination.city }
        assertTrue(near in destinations)
        assertFalse(far in destinations)
    }

    @Test
    fun `a connection already carrying a route is still a candidate`() {
        val near = cityOf(civB, 6)
        val unit = game.addUnit(caravan.name, civA, origin.getCenterTile())
        civA.tradeRoutes.establish(unit, near, TradeRouteKind.International)!!
        assertTrue(TradeRouteAutomation.getAvailableRoutes(civA).any { it.destination.city == near })
    }

    @Test
    fun `an international route with a gain scores positive and a plundered one scores nothing`() {
        val near = cityOf(civB, 6)
        giveCivBTechs()
        val danger = PlotDanger(civA)
        val candidate = candidateTo(near)
        assertTrue(TradeRouteAutomation.scoreInternational(civA, candidate, danger) > 0)
        civA.tradeRoutes.plunderedConnections[TradeRouteManager.plunderKey(origin.location, near.location)] = game.gameInfo.turns
        assertEquals(0, TradeRouteAutomation.scoreInternational(civA, candidate, danger))
    }

    @Test
    fun `routes through enemy land are penalised by a thousand per tile`() {
        val near = cityOf(civB, 6)
        val danger = PlotDanger(civA)
        val path = listOf(game.tileMap[0, 10].also { it.setOwningCity(near) })
        val atPeace = TradeRouteAutomation.dangerSum(civA, path, danger)
        civA.getDiplomacyManager(civB)!!.declareWar()
        val atWar = TradeRouteAutomation.dangerSum(civA, path, PlotDanger(civA))
        assertEquals(1000, atWar - atPeace)
    }

    @Test
    fun `a longer path through unseen tiles is more dangerous`() {
        val path = List(3) { game.tileMap[0, it + 10] }
        assertEquals(1 + 3, TradeRouteAutomation.dangerSum(civA, path, PlotDanger(civA)))
    }

    @Test
    fun `technology difference counts what the other knows and we lack, and is zero with city-states`() {
        val techs = game.ruleset.technologies.keys.filter { !civB.tech.isResearched(it) }.take(3)
        for (tech in techs) civB.tech.addTechnology(tech)
        assertEquals(3, TradeRouteAutomation.techDifference(civA, civB))
        assertEquals(0, TradeRouteAutomation.techDifference(civB, civA))
        val cityState = game.addCiv(cityStateType = "Maritime")
        assertEquals(0, TradeRouteAutomation.techDifference(civA, cityState))
    }

    @Test
    fun `a trade unit takes the higher scoring international connection`() {
        val low = cityOf(civB, 6)
        val high = cityOf(civB, -6)
        giveCivBTechs()
        high.cityConstructions.addBuilding(game.createBuilding("[+10] Gold on incoming [International] trade routes for their origin <in this city>"))
        val danger = PlotDanger(civA)
        val lowScore = TradeRouteAutomation.scoreInternational(civA, candidateTo(low), danger)
        val highScore = TradeRouteAutomation.scoreInternational(civA, candidateTo(high), danger)
        assertTrue(lowScore > 0 && highScore > lowScore)
        val unit = game.addUnit(caravan.name, civA, origin.getCenterTile())

        TradeRouteAutomation.automateTradeUnit(unit)

        assertEquals(high.location, civA.tradeRoutes.routes.single().destination)
    }

    @Test
    fun `a better food connection is not displaced by an international quota`() {
        val foreign = cityOf(civB, 6)
        val domestic = cityOf(civA, -5)
        origin.population.addPopulation(1)
        origin.cityConstructions.removeBuilding("Palace")
        foreign.cityConstructions.removeBuilding("Palace")
        origin.cityConstructions.addBuilding(game.createBuilding("Allows [Food] trade routes between your cities"))
        foreign.cityConstructions.addBuilding(game.createBuilding("[+1] Gold on incoming [International] trade routes for their origin <in this city>"))
        civB.tech.addTechnology(game.ruleset.technologies.keys.first { !civB.tech.isResearched(it) })
        origin.cityStats.update()
        foreign.cityStats.update()
        val danger = PlotDanger(civA)
        val available = TradeRouteAutomation.getAvailableRoutes(civA)
        val food = available.first { it.origin == origin && it.destination.city == domestic && it.destination.kind == TradeRouteKind.Food }
        val gold = available.first { it.origin == origin && it.destination.city == foreign && it.destination.kind == TradeRouteKind.International }
        val goldScore = TradeRouteAutomation.scoreInternational(civA, gold, danger)
        val foodScore = TradeRouteAutomation.scoreFood(civA, food, domestic, danger)
        assertTrue(civA.getHappiness() >= 0)
        assertTrue("food=$foodScore, gold=$goldScore", goldScore > 0 && foodScore > goldScore)
        val unit = game.addUnit(caravan.name, civA, origin.getCenterTile())

        TradeRouteAutomation.automateTradeUnit(unit)

        val route = civA.tradeRoutes.routes.single()
        assertEquals(domestic.location, route.destination)
        assertEquals(TradeRouteKind.Food, route.kind)
    }

}
