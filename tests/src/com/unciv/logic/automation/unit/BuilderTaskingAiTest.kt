package com.unciv.logic.automation.unit

import com.unciv.json.json
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.quests.QuestManager
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Decision-level checks of how the route directives of [PlannedRoutes] are taken and the claims of [BuilderTaskingAi], against the DLL formulas
 *  with the BNW XML values (road maintenance 1, railroad 2). The improvement directives are in [BuilderDirectiveTests], their scores in
 *  [PlotBuildScorerTests], the plan itself in [PlannedRoutesTests].
 */
@RunWith(GdxTestRunner::class)
class BuilderTaskingAiTest {
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

    /** Capital at (0,0) and a city 4 plots east, both of [population], a worker at the capital; no city is connected to the capital yet */
    private fun twoCities(population: Int, distance: Int = 4): Pair<City, City> {
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = population)
        val other = game.addCity(civ, tile(distance, 0), initialPopulation = population)
        game.addUnit("Worker", civ, capital.getCenterTile())
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        return capital to other
    }

    /** The plots between the two cities of [twoCities], which lie on a straight line */
    private fun roadPlots(other: City): List<Tile> = (1 until other.getCenterTile().position.x.toInt()).map { tile(it, 0) }

    @Test
    fun `a route directive scores the route value and is weighed by the time of the whole route like any directive`() {
        val (_, other) = twoCities(10)
        val plots = roadPlots(other)
        val worker = civ.units.getCivUnits().single()
        val ai = BuilderTaskingAi(civ)
        val picked = ai.evaluateBuilder(worker)
        assertNotNull(picked)
        picked!!
        assertEquals(BuilderDirective.Type.BuildRoute, picked.directive.type)
        assertEquals("a plot next to a city, at full score and one step away, is the best", plots.first(), picked.tile)

        val route = ai.routes.plannedRoutes().single()
        assertEquals(PlotBuildScore(ai.routes.additiveValueOf(route) + ai.routes.nonAdditiveValueOf(route)), picked.directive.plotBuildScore)
        val routeTurns = plots.sumOf { improvement("Road").getTurnsToBuild(civ, worker, it) }
        assertEquals("the build time is that of all the plots of the planned route", routeTurns, picked.buildTurns)
        assertEquals(worker.getTile().aerialDistanceTo(picked.tile), picked.moveTurns)
        assertEquals(BuilderDirectives.directiveWeight(picked.directive, routeTurns, picked.moveTurns), picked.weight)
    }

    @Test
    fun `a plot another builder was sent to is skipped on later turns and the target is set by the move`() {
        val (capital, _) = twoCities(10)
        val worker = capital.getCenterTile().civilianUnit!!
        val first = BuilderTaskingAi(civ).evaluateBuilder(worker)!!
        // The same decision on the next turn (a new instance), after another builder started walking to that plot
        val claimer = game.addUnit("Worker", civ, tile(0, 3))
        claimer.builderTarget = first.tile.position
        assertTrue(BuilderTaskingAi(civ).evaluateBuilder(worker)!!.tile != first.tile)
        // Once that builder is sent elsewhere the plot is free again
        claimer.builderTarget = null
        assertEquals(first.tile, BuilderTaskingAi(civ).evaluateBuilder(worker)!!.tile)

        assertTrue(civ.getWorkerAutomation().executeWorkerMove(worker))
        assertEquals(first.tile.position, worker.builderTarget)
    }

    @Test
    fun `a builder with the road already built there gets no route directive`() {
        val (_, other) = twoCities(10)
        val flagged = roadPlots(other)
        for (plot in flagged) plot.roadStatus = RoadStatus.Road
        val after = BuilderTaskingAi(civ)
        assertTrue(after.directives.none { it.type == BuilderDirective.Type.BuildRoute })
        assertTrue("the plan still has the plots", flagged.all { after.routes.bestRouteTypeAndValue(it)?.first == RoadStatus.Road })
    }

    /** The capital at (0,0) with a worker, a city-state capital [distance] plots east that gave [civ] a route quest */
    private fun routeQuestCityState(distance: Int = 4): Pair<Civilization, City> {
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = 5)
        game.addUnit("Worker", civ, capital.getCenterTile())
        val cityState = game.addCiv(cityStateType = "Cultured")
        cityState.tech.techsResearched.add("The Wheel")  // its city center gets a road only then
        val cityStateCapital = game.addCity(cityState, tile(distance, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(cityState)
        for ((from, to) in listOf(civ to cityState, cityState to civ)) from.getDiplomacyManager(to)!!.hasOpenBorders = true  // workers may only enter a city-state's land with open borders
        val json = "{\"assignedQuests\":[{\"questName\":\"Route\",\"assigner\":\"${cityState.civID}\",\"assignee\":\"${civ.civID}\",\"assignedOnTurn\":1}]}"
        cityState.questManager = json().fromJson(QuestManager::class.java, json)
        cityState.questManager.setTransients(cityState)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        return cityState to cityStateCapital
    }

    @Test
    fun `an AI major that wants the connection plans the road to the capital of a city-state with a route quest at the flat quest weight`() {
        val (cityState, cityStateCapital) = routeQuestCityState()
        civ.ai.wantsRouteToMinors.add(cityState.civID)
        val ai = BuilderTaskingAi(civ)
        val route = ai.routes.plannedRoutes().single()
        assertEquals(setOf(civ.getCapital()!!.getCenterTile(), cityStateCapital.getCenterTile()), setOf(route.first, route.second))
        assertEquals(setOf(RoutePurpose.ConnectCapital), ai.routes.purposesOf(route))
        assertEquals(3, ai.routes.plotsOf(route).size)
        // The weight is flat, less 10 for each of the 3 plots to build: it is not divided among the plots as in BNW
        assertEquals(game.ruleset.ai.define(AiDefine.MINOR_CIV_ROUTE_QUEST_WEIGHT) - 3 * 10, ai.routes.additiveValueOf(route))
        assertEquals(0, ai.routes.nonAdditiveValueOf(route))
        assertTrue(ai.directives.any { it.type == BuilderDirective.Type.BuildRoute })
    }

    @Test
    fun `an AI major builds no road to a city-state it does not want to connect to`() {
        routeQuestCityState()
        assertTrue(PlannedRoutes(civ).plannedRoutes().isEmpty())
    }

    @Test
    fun `a city-state without a route quest for the major gets no road`() {
        val (cityState, _) = routeQuestCityState()
        civ.ai.wantsRouteToMinors.add(cityState.civID)
        cityState.questManager = QuestManager().also { it.setTransients(cityState) }
        assertTrue(PlannedRoutes(civ).plannedRoutes().isEmpty())
    }

    @Test
    fun `a human's builders do not connect city-states on the human's behalf`() {
        val (cityState, _) = routeQuestCityState()
        civ.ai.wantsRouteToMinors.add(cityState.civID)
        civ.playerType = PlayerType.Human
        assertTrue(PlannedRoutes(civ).plannedRoutes().isEmpty())
    }
}
