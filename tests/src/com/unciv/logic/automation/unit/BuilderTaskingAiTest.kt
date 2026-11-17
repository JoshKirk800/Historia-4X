package com.unciv.logic.automation.unit

import com.unciv.json.json
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.quests.QuestManager
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
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
 *  Decision-level checks of the planned routes and the claims of [BuilderTaskingAi] against the DLL formulas with the BNW XML values
 *  (LUXURY_RESOURCE 750, luxury happiness 4, road maintenance 1, railroad 2). The improvement directives are in [BuilderDirectiveTests],
 *  their scores in [PlotBuildScorerTests].
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

    /**
     *  Capital at (0,0) and a city 4 plots east, both of [population], a worker at the capital. The land is tundra, which takes none of the improvements
     *  of the first techs, so that nothing but the roads is worth building.
     */
    private fun twoCities(population: Int, distance: Int = 4): Pair<City, City> {
        for (plot in game.tileMap.values) game.setTileTerrain(plot.position, "Tundra")
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = population)
        val other = game.addCity(civ, tile(distance, 0), initialPopulation = population)
        game.addUnit("Worker", civ, capital.getCenterTile())
        return capital to other
    }

    private fun roadPlots(capital: City, other: City): List<Tile> =
        MapPathing.getRoadPath(civ, capital.getCenterTile(), other.getCenterTile())!!.filter { !it.isCityCenter() }

    @Test
    fun `a road is flagged when the connection gold pays its maintenance`() {
        val (capital, other) = twoCities(10)
        val plots = roadPlots(capital, other)
        val gold = other.cityStats.getTradeRouteGold().toInt()
        assertTrue("setup: profitable", gold - plots.size * 1 >= 0)
        val ai = BuilderTaskingAi(civ)
        val expected = gold * 100 / (plots.size * (1 + 1)) * plots.size / plots.size
        assertEquals(plots.toSet(), ai.routePlots.keys)
        for (plot in plots) {
            assertEquals(expected, ai.routePlots[plot]!!.value)
            assertEquals(RoadStatus.Road, ai.routePlots[plot]!!.route)
        }
    }

    @Test
    fun `a road that would not pay for itself is not built`() {
        twoCities(1)
        assertTrue(BuilderTaskingAi(civ).routePlots.isEmpty())
    }

    @Test
    fun `a profitable railroad gets the highest value`() {
        val (capital, other) = twoCities(10)
        research("Railroads")
        val ai = BuilderTaskingAi(civ)
        for (plot in roadPlots(capital, other)) {
            assertEquals(Short.MAX_VALUE.toInt(), ai.routePlots[plot]!!.value)
            assertEquals(RoadStatus.Railroad, ai.routePlots[plot]!!.route)
        }
    }

    @Test
    fun `a route directive scores the route value and is weighed by distance and build time like any directive`() {
        val (capital, other) = twoCities(10)
        val worker = capital.getCenterTile().civilianUnit!!
        val ai = BuilderTaskingAi(civ)
        val picked = ai.evaluateBuilder(worker)
        assertNotNull(picked)
        picked!!
        assertEquals(BuilderDirective.Type.BuildRoute, picked.directive.type)
        val distance = worker.getTile().aerialDistanceTo(picked.tile)
        assertEquals("the nearest road plot wins: its weight is the largest", roadPlots(capital, other).minOf { it.aerialDistanceTo(worker.getTile()) }, distance)
        assertEquals(PlotBuildScore(ai.routePlots[picked.tile]!!.value), picked.directive.plotBuildScore)
        val roadTurns = improvement("Road").getTurnsToBuild(civ, worker, picked.tile)
        assertEquals(roadTurns, picked.buildTurns)
        assertEquals(distance, picked.moveTurns)
        assertEquals(BuilderDirectives.directiveWeight(picked.directive, roadTurns, distance), picked.weight)
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
        return cityState to cityStateCapital
    }

    @Test
    fun `an AI major that wants the connection flags the road to the capital of a city-state with a route quest`() {
        val (cityState, cityStateCapital) = routeQuestCityState()
        civ.ai.wantsRouteToMinors.add(cityState.civID)
        val plots = roadPlots(civ.getCapital()!!, cityStateCapital)
        val ai = BuilderTaskingAi(civ)
        assertTrue(plots.isNotEmpty())
        assertEquals(plots.toSet(), ai.routePlots.keys)
        val expected = game.ruleset.ai.define(AiDefine.MINOR_CIV_ROUTE_QUEST_WEIGHT) / plots.size
        for (plot in plots) assertEquals(expected, ai.routePlots[plot]!!.value)
    }

    @Test
    fun `an AI major builds no road to a city-state it does not want to connect to`() {
        routeQuestCityState()
        assertTrue(BuilderTaskingAi(civ).routePlots.isEmpty())
    }

    @Test
    fun `a city-state without a route quest for the major gets no road`() {
        val (cityState, _) = routeQuestCityState()
        civ.ai.wantsRouteToMinors.add(cityState.civID)
        cityState.questManager = QuestManager().also { it.setTransients(cityState) }
        assertTrue(BuilderTaskingAi(civ).routePlots.isEmpty())
    }

    @Test
    fun `a human's builders do not connect city-states on the human's behalf`() {
        val (cityState, _) = routeQuestCityState()
        civ.ai.wantsRouteToMinors.add(cityState.civID)
        civ.playerType = PlayerType.Human
        assertTrue(BuilderTaskingAi(civ).routePlots.isEmpty())
    }
}
