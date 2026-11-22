package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.transients.CapitalConnectionsFinder
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.EnumSet

/**
 *  The route plan of CvBuilderTaskingAI ([PlannedRoutes]): the three purposes with Vox Populi's values on flat grassland, where a straight line
 *  of plots between two cities is the only cheapest path. Values are in hundredths: a connection of two cities of population 10 yields 11.5
 *  gold (BNW's 0.15 per capital citizen, 1.1 per citizen of the city, -1), the movement bonus of a flat path is 300, every plot to build costs 10.
 */
@RunWith(GdxTestRunner::class)
class PlannedRoutesTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() = start()

    private fun start(vararg civUniques: String) {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv(*civUniques)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]
    private fun research(vararg techs: String) { for (tech in techs) civ.tech.techsResearched.add(tech) }
    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!

    /** Capital at (0,0) and a city [distance] plots east, both of [population]; no city is connected to the capital yet */
    private fun twoCities(population: Int, distance: Int = 4): Pair<City, City> {
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = population)
        val other = game.addCity(civ, tile(distance, 0), initialPopulation = population)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        return capital to other
    }

    private fun line(from: Int, to: Int) = (from..to).map { tile(it, 0) }

    private fun PlannedRoutes.valueOf(route: PlannedRoutes.PlannedRoute) = additiveValueOf(route) + nonAdditiveValueOf(route)

    //region Values

    @Test
    fun `a road between two cities is planned for the capital connection and as a shortcut`() {
        val (_, other) = twoCities(10)
        val routes = PlannedRoutes(civ)
        val route = routes.plannedRoutes().single()
        assertEquals(RoadStatus.Road, route.route)
        assertEquals(setOf(RoutePurpose.ConnectCapital, RoutePurpose.Shortcut), routes.purposesOf(route))
        assertEquals(line(1, 3), routes.plotsOf(route))

        val gold = other.cityStats.getTradeRouteGoldTimes100()
        assertEquals("setup: 10 × 0.15 + 10 × 1.1 - 1", 1150, gold)
        assertEquals("the gold of the connection, less 10 for each of the 3 plots to build", gold - 3 * 10, routes.additiveValueOf(route))
        assertEquals("the movement bonus of flat land (the integer ratio 1 of the way with and without the road × 300), less 10 per plot",
            300 - 3 * 10, routes.nonAdditiveValueOf(route))
    }

    @Test
    fun `a city that is connected to the capital adds a twentieth of its connection value to the shortcut`() {
        val (capital, other) = twoCities(10)
        civ.cache.citiesConnectedToCapitalToMediums = mapOf(capital to EnumSet.of(CapitalConnectionsFinder.CapitalConnectionMedium.Start))
        val routes = PlannedRoutes(civ)
        val route = routes.plannedRoutes().single()
        // the capital's own share is not counted
        assertEquals(300 + other.cityStats.getTradeRouteGoldTimes100() / 20 - 3 * 10, routes.nonAdditiveValueOf(route))
    }

    @Test
    fun `a connection that yields nothing is still planned as a shortcut`() {
        val (_, other) = twoCities(1)
        assertTrue("setup: it yields less than the 30 the plots cost", other.cityStats.getTradeRouteGoldTimes100() in 1..29)
        val routes = PlannedRoutes(civ)
        val route = routes.plannedRoutes().single()
        assertEquals(setOf(RoutePurpose.Shortcut), routes.purposesOf(route))
        assertEquals(0, routes.additiveValueOf(route))
        assertEquals(270, routes.nonAdditiveValueOf(route))
    }

    @Test
    fun `rough land earns a larger movement bonus`() {
        twoCities(10)
        for (plot in line(1, 3)) game.setTileTerrain(plot.position, "Hill")
        val routes = PlannedRoutes(civ)
        // 2 movement points a plot without the road (the cap of a unit with two points), the city costs 1; the road halves a point
        assertEquals(120, routes.moveCostWithRoute(tile(0, 0), tile(1, 0), RoadStatus.None, RoadStatus.None))
        assertEquals(60, routes.moveCostWithRoute(tile(3, 0), tile(4, 0), RoadStatus.None, RoadStatus.None))
        assertEquals(30, routes.moveCostWithRoute(tile(0, 0), tile(1, 0), RoadStatus.Road, RoadStatus.Road))
        assertEquals("not adjacent", 0, routes.moveCostWithRoute(tile(0, 0), tile(2, 0), RoadStatus.Road, RoadStatus.Road))
        // (420 + 420 + 1) / (120 + 120 + 1) = 3
        val route = routes.plannedRoutes().single()
        assertEquals(3 * 300 - 3 * 10, routes.nonAdditiveValueOf(route))
    }

    @Test
    fun `a railroad is planned first and beats the road by its movement bonus and the production of the city`() {
        val (_, other) = twoCities(10)
        research("Railroads")
        val routes = PlannedRoutes(civ)
        assertEquals(setOf(RoadStatus.Road, RoadStatus.Railroad), routes.plannedRoutes().map { it.route }.toSet())
        val railroad = routes.plannedRoutes().single { it.route == RoadStatus.Railroad }
        val production = (other.cityStats.baseStatTree.totalStats.production * 100).toInt() * game.ruleset.ai.define(AiDefine.INDUSTRIAL_ROUTE_PRODUCTION_MOD) / 100
        assertEquals(other.cityStats.getTradeRouteGoldTimes100() + production - 3 * 10, routes.additiveValueOf(railroad))
        // (240 + 240 + 1) / (24 + 24 + 1) = 9: a railroad point costs a tenth
        assertEquals(9 * 300 - 3 * 10, routes.nonAdditiveValueOf(railroad))

        assertEquals(3, routes.directives.size)
        assertTrue(routes.directives.all { it.type == BuilderDirective.Type.BuildRoute && it.build!!.improvement.name == "Railroad" })
    }

    @Test
    fun `strategic routes go to plots on the border that a strong neighbour threatens`() {
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0))
        val neighbour = game.addCiv()
        game.addCity(neighbour, tile(6, 0))
        for (plot in line(2, 4)) capital.expansion.takeOwnership(plot)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        civ.ai.warStates.militaryStrengths[neighbour.civID] = StrengthLevel.Average
        civ.ai.diplomacy.memoryOf(neighbour).approach = MajorCivApproach.Friendly
        assertEquals("setup: a friend is no concern", 0, PlotDefense.strategicValue(civ, tile(4, 0)))
        assertTrue(PlannedRoutes(civ).plannedRoutes().isEmpty())

        civ.ai.diplomacy.memoryOf(neighbour).approach = MajorCivApproach.Neutral
        assertEquals("neutral and equal: 100", 100, PlotDefense.strategicValue(civ, tile(4, 0)))
        assertEquals("the neighbour's land is two plots away", 100, PlotDefense.strategicValue(civ, tile(3, 0)))
        assertEquals("and three away", 0, PlotDefense.strategicValue(civ, tile(2, 0)))

        val routes = PlannedRoutes(civ)
        val route = routes.plannedRoutes().single { setOf(it.first, it.second) == setOf(tile(0, 0), tile(4, 0)) }
        assertEquals(setOf(RoutePurpose.Strategic), routes.purposesOf(route))
        assertEquals(line(1, 3), routes.plotsOf(route))
        assertEquals("the bonus × 100 / 100 less 10 per plot of the path including the plot itself", 300 - 4 * 10, routes.nonAdditiveValueOf(route))
        assertEquals(0, routes.additiveValueOf(route))
        // The plot a route shares with a nearer strategic plot gets the larger value: the route to the farther plot (260 + 25 for 3 plots
        // to build, less 300 of maintenance) does not pay, the one to the nearer plot (270 + 50 - 200) does
        assertEquals(RoadStatus.Road to 270, routes.bestRouteTypeAndValue(tile(2, 0)))
        assertNull("only the plot of the route that does not pay", routes.bestRouteTypeAndValue(tile(3, 0)))
        assertTrue("it is planned all the same, and counts for the path finder", routes.isRoutePlanned(tile(3, 0), RoadStatus.Road, RoutePurpose.Strategic))
        assertTrue(routes.isRoutePlanned(tile(2, 0), RoadStatus.Road, RoutePurpose.Strategic))
        assertFalse(routes.isRoutePlanned(tile(2, 0), RoadStatus.Road, RoutePurpose.Shortcut))
    }

    @Test
    fun `the strategic value is the larger of the threat and the target value from the approach and the strength`() {
        game.addCity(civ, tile(0, 0))
        val neighbour = game.addCiv()
        game.addCity(neighbour, tile(3, 0))
        val plot = tile(1, 0)
        fun value(approach: MajorCivApproach, strength: StrengthLevel): Int {
            civ.ai.diplomacy.memoryOf(neighbour).approach = approach
            civ.ai.warStates.militaryStrengths[neighbour.civID] = strength
            return PlotDefense.strategicValue(civ, plot)
        }
        assertEquals("hostile and strong: 150 × 1.25 to defend, 200 × 0.5 to attack", 187, value(MajorCivApproach.Hostile, StrengthLevel.Strong))
        assertEquals("guarded and weak: 200 × 0.25 to defend, 100 × 1.5 to attack", 150, value(MajorCivApproach.Guarded, StrengthLevel.Weak))
        assertEquals("afraid of a pathetic one: 200 × 0.1", 20, value(MajorCivApproach.Afraid, StrengthLevel.Pathetic))
        assertEquals("a friend is no concern", 0, value(MajorCivApproach.Friendly, StrengthLevel.Immense))
    }

    @Test
    fun `a capital connection goes over the water between harbors`() {
        for (plot in game.tileMap.values) if (plot.position.x in 1..4) game.setTileTerrain(plot.position, "Ocean")
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = 10)
        val port = game.addCity(civ, tile(5, 0), initialPopulation = 10)
        val inland = game.addCity(civ, tile(8, 0), initialPopulation = 10)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        val finder = CapitalConnectionsFinder(civ)
        assertTrue("no harbors, no link", finder.harborNeighbors(capital).isEmpty())

        fun capitalConnections() = PlannedRoutes(civ).let { routes -> routes.plannedRoutes().filter { RoutePurpose.ConnectCapital in routes.purposesOf(it) } }
        assertTrue("setup: without a link the capital reaches no city by road", capitalConnections().isEmpty())

        // Vox Populi's building that connects cities over water is the Lighthouse (AllowsWaterRoutes), not the Harbor
        capital.cityConstructions.addBuilding("Lighthouse")
        port.cityConstructions.addBuilding("Lighthouse")
        assertEquals(listOf(port), CapitalConnectionsFinder(civ).harborNeighbors(capital))
        assertEquals(listOf(capital), CapitalConnectionsFinder(civ).harborNeighbors(port))
        assertTrue(CapitalConnectionsFinder(civ).harborNeighbors(inland).isEmpty())
        val routes = PlannedRoutes(civ)
        val toInland = routes.plannedRoutes().single { RoutePurpose.ConnectCapital in routes.purposesOf(it) }
        assertEquals("the road starts at the port the water leads to", setOf(port.getCenterTile(), inland.getCenterTile()), setOf(toInland.first, toInland.second))
        assertEquals(line(6, 7), routes.plotsOf(toInland))
    }

    //endregion
    //region Choice between routes

    @Test
    fun `a route with its maintenance above its value is not built, and the bonus for a nearly finished one tips it`() {
        val (_, other) = twoCities(10)
        val base = PlannedRoutes(civ).let { routes -> routes.plannedRoutes().single().let { routes.valueOf(it) } }
        // 3 plots, each costing the maintenance: a hair above the value and the +25 of a route with 3 plots missing is all that keeps it
        val maintenance = (base + 2) / 3
        assertEquals("setup: 3 plots", 3, other.getCenterTile().aerialDistanceTo(tile(0, 0)) - 1)

        fun directives(builtPlots: Int, perPlotTimes100: Int): Int {
            start("[+${perPlotTimes100 - 100}]% maintenance on road & railroads")
            twoCities(10)
            for (plot in line(1, 3).take(builtPlots)) plot.roadStatus = RoadStatus.Road
            return PlannedRoutes(civ).directives.size
        }
        assertEquals("the value plus 25 outweighs it by a little", 3, directives(0, maintenance))
        assertEquals("more maintenance and it does not", 0, directives(0, maintenance + 9))
        assertEquals("one plot built: the value grows by 10 and the completion bonus to 50", 2, directives(1, maintenance + 9))
        assertEquals("the same maintenance is cheaper for the default policies", 3, directives(0, 100))
    }

    @Test
    fun `a plot with no route next to it has a fifth of its score as potential score`() {
        val (capital, _) = twoCities(10)
        assertEquals("setup: the city centers have the road", RoadStatus.Road, capital.getCenterTile().roadStatus)
        val routes = PlannedRoutes(civ)
        val value = routes.valueOf(routes.plannedRoutes().single())
        val byTile = routes.directives.associateBy { it.tile }
        assertEquals(PlotBuildScore(value), byTile.getValue(tile(1, 0)).plotBuildScore)
        assertEquals(PlotBuildScore(value * 4 / 5, value / 5), byTile.getValue(tile(2, 0)).plotBuildScore)
        assertEquals(PlotBuildScore(value), byTile.getValue(tile(3, 0)).plotBuildScore)
        // the priority score a plot is known by is that of the full score
        assertEquals(RoadStatus.Road to value, routes.bestRouteTypeAndValue(tile(2, 0)))
        assertNull(routes.bestRouteTypeAndValue(tile(2, 1)))
    }

    @Test
    fun `a pillaged road is repaired and a built one is no directive`() {
        twoCities(10)
        for (plot in line(1, 3)) plot.roadStatus = RoadStatus.Road
        tile(2, 0).roadIsPillaged = true
        val directive = PlannedRoutes(civ).directives.single()
        assertEquals(BuilderDirective.Type.RepairRoute, directive.type)
        assertEquals(tile(2, 0), directive.tile)
        assertTrue(directive.build!!.isRepair)
    }

    //endregion
    //region Readers

    private fun scoreOf(improvementName: String, plot: Tile, build: Boolean = true): PlotBuildScore {
        val improvement = improvement(improvementName)
        return PlotBuildScorer(civ).scorePlotBuild(plot, improvement, if (build) Build(improvement) else null)
    }

    @Test
    fun `an improvement on a plot of a planned road loses 100, a Great Person's improvement 1000 to build and nothing to keep`() {
        research("Agriculture")
        val (capital, _) = twoCities(10)
        val plot = tile(2, 0)
        capital.expansion.takeOwnership(plot)
        civ.tech.techsResearched.remove("The Wheel")
        val without = scoreOf("Farm", plot)
        val withoutAcademy = scoreOf("Academy", plot)
        val withoutKept = scoreOf("Academy", plot, build = false)
        research("The Wheel")

        assertEquals(without.score - 100, scoreOf("Farm", plot).score)
        assertEquals(without.priorityScore - 100, scoreOf("Farm", plot).priorityScore)
        assertEquals(withoutAcademy.score - 1000, scoreOf("Academy", plot).score)
        assertEquals(withoutKept.score, scoreOf("Academy", plot, build = false).score)
        // a plot off the road has no penalty
        val off = tile(1, 1)
        capital.expansion.takeOwnership(off)
        assertNull(PlannedRoutes(civ).bestRouteTypeAndValue(off))
    }

    @Test
    fun `a plot that is only on a strategic route costs an improvement nothing`() {
        research("The Wheel", "Agriculture")
        val capital = game.addCity(civ, tile(0, 0))
        val neighbour = game.addCiv()
        game.addCity(neighbour, tile(6, 0))
        for (plot in line(2, 4)) capital.expansion.takeOwnership(plot)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        civ.ai.warStates.militaryStrengths[neighbour.civID] = StrengthLevel.Average
        val plot = tile(2, 0)
        val routes = PlannedRoutes(civ)
        assertEquals("setup: a route is planned there", RoadStatus.Road, routes.bestRouteTypeAndValue(plot)?.first)
        val farm = improvement("Farm")
        val withRoute = PlotBuildScorer(civ, routes).scorePlotBuild(plot, farm, Build(farm))
        civ.tech.techsResearched.remove("The Wheel")
        val noRoute = PlotBuildScorer(civ, PlannedRoutes(civ)).scorePlotBuild(plot, farm, Build(farm))
        assertEquals(noRoute, withRoute)
    }

    @Test
    fun `plot buying values a plot on a planned route like a strategic resource`() {
        val (_, _) = twoCities(10)
        val city = civ.getCapital()!!
        val onRoute = tile(2, 0)
        val offRoute = tile(2, 1)
        // The two plots have the same nature yields and the same distance to the city's nearest dispute: none
        assertEquals(onRoute.stats.getTileStats(null, civ).toString(), offRoute.stats.getTileStats(null, civ).toString())
        val cost = city.expansion.getGoldCostOfTile(onRoute)
        assertEquals("setup: both plots cost the same", cost, city.expansion.getGoldCostOfTile(offRoute))
        val onScore = UseGoldAutomation.individualPlotScore(city, onRoute)
        val offScore = UseGoldAutomation.individualPlotScore(city, offRoute)
        val baseCost = UseGoldAutomation.baseTileCost(city)
        // AI_PLOT_VALUE_STRATEGIC_RESOURCE (Vox Populi's 80) more value, scaled by the base cost over the tile's cost, rounded down
        val bonus = game.ruleset.ai.define(AiDefine.AI_PLOT_VALUE_STRATEGIC_RESOURCE) * baseCost / cost
        assertTrue("$onScore - $offScore against $bonus", onScore > offScore && onScore - offScore in (bonus - 1)..(bonus + 1))
    }

    @Test
    fun `the route a builder works on is timed as a whole`() {
        val (_, other) = twoCities(10)
        val worker = game.addUnit("Worker", civ, tile(0, 0))
        val routes = PlannedRoutes(civ)
        val road = improvement("Road")
        val total = line(1, other.getCenterTile().position.x - 1).sumOf { road.getTurnsToBuild(civ, worker, it) }
        assertEquals(total, routes.totalRouteBuildTime(worker, tile(2, 0)))
        // a plot already built leaves the others
        tile(1, 0).roadStatus = RoadStatus.Road
        assertEquals(total - road.getTurnsToBuild(civ, worker, tile(1, 0)), PlannedRoutes(civ).totalRouteBuildTime(worker, tile(2, 0)))
        assertNull("a plot off the plan", routes.totalRouteBuildTime(worker, tile(2, 1)))
    }

    //endregion
}
