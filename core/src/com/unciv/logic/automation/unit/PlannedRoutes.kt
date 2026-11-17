package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.transients.CapitalConnectionsFinder
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import java.util.EnumSet
import java.util.PriorityQueue
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** CvBuilderTaskingAI's RoutePurpose: why a route is planned */
enum class RoutePurpose {
    /** PURPOSE_CONNECT_CAPITAL: a city to the capital (gold, and what the connection brings) */
    ConnectCapital,
    /** PURPOSE_SHORTCUT: two cities of ours, for the movement bonus */
    Shortcut,
    /** PURPOSE_STRATEGIC: a city to a plot on the border or a fort of ours that is threatened by a neighbour */
    Strategic
}

/**
 *  The route plan of CvBuilderTaskingAI (Vox Populi): UpdateRoutePlots, the planned routes it collects, GetRouteDirectives and the queries
 *  the scoring of improvements and the buying of plots read. One instance per civilization and turn (held by [WorkerAutomation]); it plans on
 *  its first use ([ensurePlanned]), the original plans once per turn in the homeland AI's PlanImprovements and keeps the result in the save.
 *
 *  What is planned (UpdateRoutePlots, for the railroad and then the road when the civilization has the tech, the best route being buildable):
 *  - [RoutePurpose.Shortcut]: every pair of our cities less than 10 plots apart, in order of population, by the cheapest build path
 *    (CvBuilderTaskingAI::ConnectCitiesForShortcuts). Its value is the movement bonus of the path ([pathValues]), plus a twentieth of the
 *    connection value of each end that is not the capital when one of them is connected to the capital, less 10 per plot still to build.
 *  - [RoutePurpose.ConnectCapital]: our capital to every other city that is to be connected (ConnectCitiesToCapital): its connection value
 *    ([capitalConnectionValue]: the connection's gold ×100 and, for the railroad, the city's production × INDUSTRIAL_ROUTE_PRODUCTION_MOD)
 *    less 10 per plot to build; a city-state capital with a route quest has the flat MINOR_CIV_ROUTE_QUEST_WEIGHT instead. Its path may cross
 *    water between harbors.
 *  - [RoutePurpose.Strategic]: a city of ours to a plot on our border, or with a defensive improvement of at least 20%, less than 6 plots
 *    away and with a [PlotDefense.strategicValue] of at least 50 (ConnectPointsForStrategy): the movement bonus × that value / 100, less 10 per plot.
 *  The plots of a path become planned routes between the cities on it (AddRoutePlots): a capital connection adds its value to the route's
 *  additive value, the other purposes take the largest. [directives] then picks, for each pair of end plots, the road or the railroad (the
 *  value, +100 - 25 per missing plot of a route that is nearly built, less the maintenance of its plots), gives every plot of it the best
 *  value of the routes through it (additive + non-additive) and offers a build or repair directive for it, its score the value. A plot
 *  with no route next to it takes a fifth of it as potential score. [bestRouteTypeAndValue] and [isRoutePlanned] are what ScorePlotBuild
 *  and CvCity::GetBuyPlotScore read.
 *
 *  Unciv mapping and deviations:
 *  - Movement costs come from Unciv's movement rules for a land unit without promotions ([moveCostWithRoute]): the road or railroad cost of the
 *    civilization, a river crossing taking the whole turn, the terrain's cost with Unciv's hills rule, in the original's 60ths of a movement point.
 *  - The path finder is the original's step finder with BuildRouteCost and BuildRouteValid ([findPath]), a Dijkstra search whose cost
 *    depends on the plan made so far (so the order of planning matters, and is the original's); a path may be at most twice the distance long.
 *    A harbor of ours links to our other harbors on the same water (CapitalConnectionsFinder.harborNeighbors), the original's wormholes
 *    of PT_BUILD_ROUTE_MIXED for a capital connection; the original's river routes (MOD_BALANCE_RIVER_CITY_CONNECTIONS, a route may jump
 *    along a river) are none: no route is ever planned by river.
 *  - The route's maintenance per plot is Unciv's (Road 1 and Railroad 2 gold, the original's 1 and 3) times the policies' modifier
 *    ([com.unciv.models.ruleset.unique.UniqueType.RoadMaintenance]); the connection gold is [com.unciv.logic.city.CityStats.getTradeRouteGoldTimes100].
 *  - The road a nation with "forests and jungles are roads" has on its forests and jungles in friendly territory is the original's
 *    GetSameRouteBenefitFromTrait ([sameRouteBenefitFromTrait]); the original's alternative trait applies it to any forest.
 *  - Who is connected: a non-human major connects the capitals of the city-states that gave it a route quest and that it wants to route to
 *    ([com.unciv.logic.automation.strategy.CivAi.wantsRouteToMinors], CvDiplomacyAI::IsWantToRouteConnectToMinor), never for a human. A city-state's
 *    builders connect to the non-human majors that it gave a route quest (BNW's rule, kept; the original's city-states plan no such route).
 *    A quest connection's value is the flat weight less 10 per plot to build, not divided by the plots as BNW's was.
 *  - The time to build a route without a unit ([BuilderDirectives.turnsToBuild]) is the improvement's turns at game speed (the original's total
 *    build time at work rate 1), used only to prefer the quicker of two routes through a plot.
 *  - Not planned or planned without these inputs: the yields of villages and plots of connected cities (Improvement_RouteYieldChanges,
 *    GetCityConnectionPlotYield, the connected village bonus of the path cost), isolation unhappiness (GetPotentialHappinessFromConnection),
 *    traits' trade route yields (GetYieldChangeTradeRoute), the flat production and the
 *    coaling station / sorting office terms of an industrial connection, foreign city connections for yields (YieldChangesPerLandConnectedCity,
 *    ConnectForeignCitiesForYields), the scenario connections (CITY_CONNECTIONS_CONNECT_TO_CAPITAL is 1), vassals, manual route planning
 *    (ROAD_PLANNING_*) and the planned-route state the user interface shows (m_plotRoutePurposes, IsMainRoutePlot). The plan is not saved:
 *    it is made again on the first use of a turn.
 */
class PlannedRoutes(val civ: Civilization) {
    private val ruleset = civ.gameInfo.ruleset
    private val rules = ruleset.ai

    /** CvBuilderTaskingAI::PlotPair: the two end plots of a planned route, the one with the lower plot index first */
    private data class Ends(val first: Tile, val second: Tile) : Comparable<Ends> {
        override fun compareTo(other: Ends): Int =
            compareValuesBy(this, other, { it.first.zeroBasedIndex }, { it.second.zeroBasedIndex })
    }

    /** CvBuilderTaskingAI::PlannedRoute: the route (road or railroad) between two end plots, [first] having the lower plot index */
    class PlannedRoute private constructor(val first: Tile, val second: Tile, val route: RoadStatus) : Comparable<PlannedRoute> {
        override fun compareTo(other: PlannedRoute): Int =
            compareValuesBy(this, other, { it.first.zeroBasedIndex }, { it.second.zeroBasedIndex }, { it.route })
        override fun equals(other: Any?) = other is PlannedRoute && first === other.first && second === other.second && route == other.route
        override fun hashCode() = 31 * (31 * first.zeroBasedIndex + second.zeroBasedIndex) + route.ordinal

        companion object {
            /** The route of type [route] between the plots [a] and [b], in either order */
            fun of(a: Tile, b: Tile, route: RoadStatus) =
                if (a.zeroBasedIndex < b.zeroBasedIndex) PlannedRoute(a, b, route) else PlannedRoute(b, a, route)
        }
    }

    /** m_anyRoutePlanned entry: [tile] is on the path of a [purpose] route of type [route] (also one that had no value) */
    private data class PathPlot(val route: RoadStatus, val tile: Tile, val purpose: RoutePurpose)

    private val plannedRouteAdditiveValues = TreeMap<PlannedRoute, Int>()
    private val plannedRouteNonAdditiveValues = TreeMap<PlannedRoute, Int>()
    private val plannedRoutePlots = TreeMap<PlannedRoute, List<Tile>>()
    private val plannedRoutePurposes = TreeMap<PlannedRoute, EnumSet<RoutePurpose>>()
    private val anyRoutePlanned = HashSet<PathPlot>()
    private val bestRouteTypeAndValue = HashMap<Tile, Pair<RoadStatus, Int>>()
    private val bestRouteForPlot = HashMap<Tile, PlannedRoute>()

    /** The plan of the turn: the directives of GetRouteDirectives, by plot index (the original's m_directives share of it) */
    val directives: List<BuilderDirective>
        get() {
            ensurePlanned()
            return plannedDirectives
        }
    private var plannedDirectives: List<BuilderDirective> = emptyList()
    private var planned = false
    /** The best route of the turn (CvPlayer::getBestRoute), read by every step of the path finder */
    private var planBestRoute = RoadStatus.None

    /** The strategic values of the plots asked this plan (CvPlot::GetStrategicValue), and the harbor links of our cities */
    private val strategicValues = HashMap<Tile, Int>()
    private val harborLinks = HashMap<City, List<City>>()
    private val connectionsFinder by lazy { CapitalConnectionsFinder(civ) }

    /** The policies' modifier of route maintenance in percent (CvPlayer::GetImprovementGoldMaintenanceMod: 100 + the modifier) */
    private val maintenancePercent = run {
        var factor = 1f
        civ.forEachMatchingUnique(UniqueType.RoadMaintenance) { factor *= it.params[0].toPercent() }
        (factor * 100).roundToInt()
    }

    //region Queries

    /** CvPlayer::getBestRoute, except for a human who turned off automated road building */
    fun bestRoute(): RoadStatus =
        if (civ.isHuman() && !UncivGame.Current.settings.autoBuildingRoads
            && UncivGame.Current.worldScreen?.autoPlay?.isAutoPlayingAndFullAutoPlayAI() == false) RoadStatus.None
        else civ.tech.getBestRoadAvailable()

    /** Plans the turn if that is not done yet */
    fun ensurePlanned() {
        if (!planned) update()
    }

    /**
     *  CvBuilderTaskingAI::GetBestRouteTypeAndValue: the route planned on [tile] and the priority score of its directive (before the
     *  down-priority of a plot with no route next to it), null (NO_ROUTE, -1) when no route is planned there or no builder may consider it.
     */
    fun bestRouteTypeAndValue(tile: Tile): Pair<RoadStatus, Int>? {
        ensurePlanned()
        return bestRouteTypeAndValue[tile]
    }

    /** CvBuilderTaskingAI::IsRoutePlanned: [tile] lies on a [route] planned for [purpose], whatever the value of that route */
    fun isRoutePlanned(tile: Tile, route: RoadStatus, purpose: RoutePurpose): Boolean {
        ensurePlanned()
        return PathPlot(route, tile, purpose) in anyRoutePlanned
    }

    /** The routes planned this turn (those with a value), by their end plots and type */
    fun plannedRoutes(): List<PlannedRoute> {
        ensurePlanned()
        return plannedRoutePlots.keys.toList()
    }

    /** The purposes a planned route is for */
    fun purposesOf(route: PlannedRoute): Set<RoutePurpose> = plannedRoutePurposes[route].orEmpty()

    /** The plots of a planned route between its end plots (the cities on the way are not among them) */
    fun plotsOf(route: PlannedRoute): List<Tile> = plannedRoutePlots[route].orEmpty()

    /** The capital-connection value of a planned route, which adds up over the routes with the same end plots (m_plannedRouteAdditiveValues) */
    fun additiveValueOf(route: PlannedRoute) = plannedRouteAdditiveValues[route] ?: 0

    /** The value of a planned route for the other purposes, of which the largest counts (m_plannedRouteNonAdditiveValues) */
    fun nonAdditiveValueOf(route: PlannedRoute) = plannedRouteNonAdditiveValues[route] ?: 0

    /**
     *  CvBuilderTaskingAI::GetTotalRouteBuildTime: the turns [unit] needs for the remaining plots of the best planned route through [tile]
     *  (CvBuilderTaskingAI::GetRouteBuildTime), null (INT_MAX) when no route is planned there.
     */
    fun totalRouteBuildTime(unit: MapUnit, tile: Tile): Int? {
        ensurePlanned()
        val route = bestRouteForPlot[tile] ?: return null
        return routeBuildTime(route, unit).takeIf { it >= 0 }
    }

    //endregion
    //region Planning

    /** CvBuilderTaskingAI::UpdateRoutePlots and GetRouteDirectives: plans the routes of the turn again */
    fun update() = timeThis("AI.RoutePlan") { plan() }

    private fun plan() {
        plannedRouteAdditiveValues.clear()
        plannedRouteNonAdditiveValues.clear()
        plannedRoutePlots.clear()
        plannedRoutePurposes.clear()
        anyRoutePlanned.clear()
        bestRouteTypeAndValue.clear()
        bestRouteForPlot.clear()
        strategicValues.clear()
        harborLinks.clear()
        plannedDirectives = emptyList()
        planned = true
        if (civ.isBarbarian) return

        // If there are fewer than 2 cities, there is nothing to connect
        val toConnect = plotsToConnect()
        if (toConnect.size < 2) return
        planBestRoute = bestRoute()
        if (planBestRoute == RoadStatus.None) return

        // Our cities by population, the largest first (stable): the order shortcuts are planned in
        val shortcutCities = toConnect.mapNotNull { cityOn(it) }.filter { it.civ == civ }.sortedByDescending { it.population.population }

        // The original loops over the builds from the last: the railroad before the road
        for (route in arrayOf(RoadStatus.Railroad, RoadStatus.Road)) {
            val improvement = route.improvement(ruleset) ?: continue
            if (improvement.techRequired != null && !civ.tech.isResearched(improvement.techRequired!!)) continue

            // Connect cities for the movement bonus
            for (i in shortcutCities.indices) for (j in i + 1 until shortcutCities.size)
                connectCitiesForShortcuts(shortcutCities[i], shortcutCities[j], route)

            // Connect cities to the capital and plan strategic routes after the shortcut routes have been planned
            for (i in toConnect.indices) for (j in i + 1 until toConnect.size) {
                val firstCity = cityOn(toConnect[i])
                val secondCity = cityOn(toConnect[j])
                if (firstCity == null && secondCity == null) continue  // we do not go from a fort to a fort
                if (secondCity == null) {
                    connectPointsForStrategy(firstCity!!, toConnect[j], route)
                    continue
                }
                if (firstCity == null) {
                    connectPointsForStrategy(secondCity, toConnect[i], route)
                    continue
                }
                if (firstCity.civ == civ && firstCity.isCapital()) connectCitiesToCapital(firstCity, secondCity, route)
                else if (secondCity.civ == civ && secondCity.isCapital()) connectCitiesToCapital(secondCity, firstCity, route)
            }
        }
        plannedDirectives = buildDirectives()
    }

    private fun cityOn(tile: Tile): City? = if (tile.isCityCenter()) tile.getCity() else null

    /**
     *  CvCityConnections::UpdatePlotsToConnect: our cities, then our strategic plots - land plots that can be walked on, not owned by a city
     *  being razed, that border a plot that is not ours and can be walked on (any plot nobody owns, water included) or carry an improvement
     *  giving 20% defense or more - then the capitals of the cities of other civilizations we connect ([connectableCapitals]).
     */
    private fun plotsToConnect(): List<Tile> {
        val plots = ArrayList<Tile>()
        for (city in civ.cities) plots.add(city.getCenterTile())

        val own = civ.cities.flatMap { it.getTiles().toList() }.sortedBy { it.zeroBasedIndex }
        for (tile in own) {
            if (tile.isWater || tile.isCityCenter()) continue
            if (tile.getCity()?.isBeingRazed == true) continue
            if (tile.isImpassible()) continue
            if (tile.neighbors.any { !it.isImpassible() && it.getOwner() != civ }) {
                plots.add(tile)
            } else {
                val improvement = tile.tileImprovement ?: continue
                // Citadels and forts
                if (improvement.getMatchingUniques(UniqueType.DefensiveBonus).sumOf { it.params[0].toInt() } >= STRATEGIC_DEFENSE_MODIFIER) plots.add(tile)
            }
        }

        for (capital in connectableCapitals()) plots.add(capital.getCenterTile())
        return plots
    }

    /**
     *  The capitals of the other civilizations we connect to (CvCityConnections::UpdatePlotsToConnect, the quests): never for a human major; a
     *  city-state's builders go to the non-human majors it gave a route quest; a non-human major's go to the city-states it wants to connect
     *  to (CvDiplomacyAI::IsWantToRouteConnectToMinor) that gave it one.
     */
    private fun connectableCapitals(): List<City> {
        if (civ.isHuman()) return emptyList()
        return civ.getKnownCivs().filter {
            when {
                civ.isCityState -> it.isMajorCiv && !it.isHuman() && hasRouteQuest(civ, it)
                civ.isMajorCiv -> it.isCityState && it.isAlive() && it.civID in civ.ai.wantsRouteToMinors && hasRouteQuest(it, civ)
                else -> false
            }
        }.mapNotNull { it.getCapital() }.toList()
    }

    private fun hasRouteQuest(minor: Civilization, major: Civilization) =
        minor.questManager.getAssignedQuestsFor(major).any { it.questName == QuestName.Route.value }

    //endregion
    //region Connections

    /**
     *  CvBuilderTaskingAI::ConnectCitiesToCapital: the route from our capital [capital] to [target] by a path that may cross the water between
     *  harbors. The value is the target's connection value ([capitalConnectionValue]) - the flat quest weight for a city of another civilization -
     *  less 10 for every plot of the path that lacks [route] or has it pillaged.
     */
    private fun connectCitiesToCapital(capital: City, target: City, route: RoadStatus) {
        if (target.isBeingRazed) return
        val path = findPath(capital.getCenterTile(), target.getCenterTile(), route, RoutePurpose.ConnectCapital, Int.MAX_VALUE, mixed = true) ?: return

        var roadsNeeded = 0
        for (tile in path) {
            if (tile.isCityCenter()) continue
            if (!sameRouteBenefitFromTrait(tile, route) && (tile.roadStatus < route || tile.roadIsPillaged)) roadsNeeded++
        }

        // for quests we might be targeting a city state: normal considerations do not apply
        var connectionValue =
            if (target.civ != capital.civ) rules.define(AiDefine.MINOR_CIV_ROUTE_QUEST_WEIGHT)
            else capitalConnectionValue(target, route)
        connectionValue -= roadsNeeded * 10
        addRoutePlots(capital.getCenterTile(), target.getCenterTile(), route, connectionValue, path, RoutePurpose.ConnectCapital)
    }

    /** GetCapitalConnectionValue: what connecting [city] (or keeping it connected) is worth, ×100: the gold it yields, and for the railroad a share of its production */
    private fun capitalConnectionValue(city: City, route: RoadStatus): Int {
        var value = city.cityStats.getTradeRouteGoldTimes100()
        if (route == RoadStatus.Railroad) {
            val baseProductionTimes100 = (city.cityStats.baseStatTree.totalStats.production * 100).toInt()
            value += baseProductionTimes100 * rules.define(AiDefine.INDUSTRIAL_ROUTE_PRODUCTION_MOD) / 100
        }
        return value
    }

    /** CvBuilderTaskingAI::ConnectCitiesForShortcuts: cities of one owner, neither being razed, less than 10 plots apart */
    private fun connectCitiesForShortcuts(city1: City, city2: City, route: RoadStatus) {
        if (city1.civ != city2.civ) return
        if (city1.isBeingRazed || city2.isBeingRazed) return
        val distance = city1.getCenterTile().aerialDistanceTo(city2.getCenterTile())
        if (distance >= SHORTCUT_MAX_DISTANCE) return

        // The path tends to re-use planned routes, unless a new path is much shorter
        val path = findPath(city1.getCenterTile(), city2.getCenterTile(), route, RoutePurpose.Shortcut, distance * 2, mixed = false) ?: return
        val (movementBonus, roadsNeeded) = pathValues(path, route)
        var value = movementBonus

        // Add a small part of the capital connection bonus, it's good to have some redundancy
        if (city1.isConnectedToCapital() || city2.isConnectedToCapital()) {
            if (!city1.isCapital()) value += capitalConnectionValue(city1, route) / 20
            if (!city2.isCapital()) value += capitalConnectionValue(city2, route) / 20
        }
        value -= roadsNeeded * 10
        addRoutePlots(city1.getCenterTile(), city2.getCenterTile(), route, value, path, RoutePurpose.Shortcut)
    }

    /**
     *  CvBuilderTaskingAI::ConnectPointsForStrategy: the route from [origin] to [target], a plot of ours less than 6 plots away whose
     *  [PlotDefense.strategicValue] is at least 50: the movement bonus of the path scaled by that value, less 10 per plot to build.
     */
    private fun connectPointsForStrategy(origin: City, target: Tile, route: RoadStatus) {
        if (origin.civ != target.getOwner()) return
        if (origin.isBeingRazed) return
        val distance = origin.getCenterTile().aerialDistanceTo(target)
        if (distance >= STRATEGIC_MAX_DISTANCE) return
        val defensiveValue = strategicValues.getOrPut(target) { PlotDefense.strategicValue(civ, target) }
        if (defensiveValue < STRATEGIC_MIN_VALUE) return

        val path = findPath(origin.getCenterTile(), target, route, RoutePurpose.Strategic, distance * 2, mixed = false) ?: return
        val (movementBonus, roadsNeeded) = pathValues(path, route)
        val value = movementBonus * defensiveValue / 100 - roadsNeeded * 10
        addRoutePlots(origin.getCenterTile(), target, route, value, path, RoutePurpose.Strategic)
    }

    /**
     *  CvBuilderTaskingAI::AddRoutePlots: records the plots between the end plots of [path] as on a [purpose] route of [route] and, when the
     *  [value] is positive, as a planned route between each two of our cities on it. A shortcut or strategic route through a city of ours
     *  is not planned (its parts are connections of their own). Only the plots between the cities are the route's, not the cities or the end plot.
     */
    private fun addRoutePlots(start: Tile, target: Tile, route: RoadStatus, value: Int, path: List<Tile>, purpose: RoutePurpose) {
        for (i in 1 until path.size - 1) anyRoutePlanned.add(PathPlot(route, path[i], purpose))
        if (value <= 0) return

        if (purpose != RoutePurpose.ConnectCapital)
            for (i in 1 until path.size - 1) if (path[i].isCityCenter() && path[i].getOwner() == civ) return

        var segmentStart = start
        val routePlots = ArrayList<Tile>()
        for (i in 1 until path.size - 1) {
            val tile = path[i]
            if (tile.isCityCenter()) {
                if (tile.getOwner() == civ) {
                    if (routePlots.isNotEmpty()) register(PlannedRoute.of(segmentStart, tile, route), value, purpose, ArrayList(routePlots))
                    segmentStart = tile
                    routePlots.clear()
                }
                continue
            }
            routePlots.add(tile)
        }
        if (routePlots.isNotEmpty()) register(PlannedRoute.of(segmentStart, target, route), value, purpose, routePlots)
    }

    private fun register(planned: PlannedRoute, value: Int, purpose: RoutePurpose, plots: List<Tile>) {
        if (purpose == RoutePurpose.ConnectCapital) plannedRouteAdditiveValues[planned] = (plannedRouteAdditiveValues[planned] ?: 0) + value
        else plannedRouteNonAdditiveValues[planned] = max(plannedRouteNonAdditiveValues[planned] ?: 0, value)
        plannedRoutePurposes.getOrPut(planned) { EnumSet.noneOf(RoutePurpose::class.java) }.add(purpose)
        plannedRoutePlots[planned] = plots
    }

    //endregion
    //region Values of a path

    /**
     *  CvBuilderTaskingAI::GetPathValues: the movement bonus of building [route] along [path], and the plots of it still to build.
     *  A unit with two movement points walks the path both ways with and without the route; the bonus is ((without + 1) / (with + 1), as
     *  integers) × 300. The original's village and plot yield bonuses of a connected city are none.
     */
    private fun pathValues(path: List<Tile>, route: RoadStatus): Pair<Int, Int> {
        var forwardWith = 0
        var backwardWith = 0
        var forwardWithout = 0
        var backwardWithout = 0
        var roadsNeeded = 0
        val cap = 2 * MOVE_DENOMINATOR  // a unit with two movement points
        for (i in 1 until path.size) {
            val tile = path[i]
            val previous = path[i - 1]
            forwardWith += min(cap, moveCostWithRoute(previous, tile, route, route))
            backwardWith += min(cap, moveCostWithRoute(tile, previous, route, route))
            forwardWithout += min(cap, moveCostWithRoute(previous, tile, RoadStatus.None, RoadStatus.None))
            backwardWithout += min(cap, moveCostWithRoute(tile, previous, RoadStatus.None, RoadStatus.None))

            // don't count the cities themselves
            if (tile.isCityCenter()) continue
            if (!sameRouteBenefitFromTrait(tile, route) && (tile.roadStatus != route || tile.roadIsPillaged)) roadsNeeded++
        }
        val movementBonus = ((forwardWithout + backwardWithout + 1) / (forwardWith + backwardWith + 1)) * 300
        return movementBonus to roadsNeeded
    }

    /**
     *  CvBuilderTaskingAI::GetMoveCostWithRoute: the cost, in 60ths of a movement point, of a land unit moving from [from] to the plot [to] next to
     *  it when they have [fromRoute] and [toRoute] (a forest or jungle of an Iroquois-like nation counts as a road). Two plots with a route cost the
     *  larger of the two route costs, across a river only with bridges (a railroad needs none, as in Unciv's movement); entering a city costs 1;
     *  else the cost of the terrain of [to] with Unciv's hills rule, a river crossing taking the whole turn. 0 when the plots are not adjacent.
     */
    fun moveCostWithRoute(from: Tile, to: Tile, fromRoute: RoadStatus, toRoute: RoadStatus): Int {
        if (from.aerialDistanceTo(to) != 1) return 0

        val riverCrossing = from.isAdjacentToRiver() && to.isAdjacentToRiver() && from.isConnectedByRiver(to)
        val fakeFrom = fromRoute == RoadStatus.None && woodlandRoad(from)
        val fakeTo = toRoute == RoadStatus.None && woodlandRoad(to)
        val effectiveFrom = if (fakeFrom) RoadStatus.Road else fromRoute
        val effectiveTo = if (fakeTo) RoadStatus.Road else toRoute

        val bridged = !riverCrossing || civ.tech.roadsConnectAcrossRivers
        if (effectiveFrom != RoadStatus.None && effectiveTo != RoadStatus.None
            && (bridged || (effectiveFrom == RoadStatus.Railroad && effectiveTo == RoadStatus.Railroad)))
            return max(routeMoveCost(effectiveFrom), routeMoveCost(effectiveTo))

        if (to.isCityCenter() && bridged) return MOVE_DENOMINATOR

        val terrainCost =
            if (civ.nation.ignoreHillMovementCost && to.isHill()) 1f
            else if (riverCrossing) 100f  // Rivers take the entire turn to cross
            else to.lastTerrain.movementCost + MovementCost.featureOnHillsExtraCost(to)
        return (terrainCost * MOVE_DENOMINATOR).roundToInt()
    }

    private fun routeMoveCost(route: RoadStatus): Int {
        val movement = if (route == RoadStatus.Railroad) RoadStatus.Railroad.movement else civ.tech.movementSpeedOnRoads
        return (movement * MOVE_DENOMINATOR).roundToInt()
    }

    /** A forest or jungle in our friendly territory, which a nation with "forests and jungles are roads" has a road on (the trait of CvPlayer::GetSameRouteBenefitFromTrait) */
    private fun woodlandRoad(tile: Tile) =
        civ.nation.forestsAndJunglesAreRoads && (Constants.forest in tile.terrainFeatures || Constants.jungle in tile.terrainFeatures) && tile.isFriendlyTerritory(civ)

    /** CvPlayer::GetSameRouteBenefitFromTrait: [tile] has the road already for [civ] by its nation's trait; only for a road, never needs building */
    fun sameRouteBenefitFromTrait(tile: Tile, route: RoadStatus) = route == RoadStatus.Road && woodlandRoad(tile)

    //endregion
    //region Path finding

    private class Node(val tile: Tile, val cost: Int, val turns: Int, val parent: Node?, val order: Int)

    /**
     *  CvStepFinder for PT_BUILD_ROUTE (and PT_BUILD_ROUTE_MIXED when [mixed]): the cheapest path from [start] to [dest] by [buildRouteCost] over the
     *  plots [isBuildRouteValid] accepts, as a list of plots from [start] to [dest]. The search is depth limited as the original's iMaxTurns:
     *  a path has fewer than [maxTurns] steps. A mixed path may jump from a harbor city of ours to another over water.
     */
    private fun findPath(start: Tile, dest: Tile, route: RoadStatus, purpose: RoutePurpose, maxTurns: Int, mixed: Boolean): List<Tile>? {
        val known = HashMap<Tile, Node>()
        val open = PriorityQueue(compareBy<Node>({ it.cost }, { it.order }))
        var order = 0
        val first = Node(start, 0, 0, null, order++)
        known[start] = first
        open.add(first)

        while (open.isNotEmpty()) {
            val node = open.poll()
            if (known[node.tile] !== node) continue  // a cheaper way to this plot was found
            if (node.tile == dest) return generateSequence(node) { it.parent }.map { it.tile }.toList().asReversed()
            // We would have to go further than we may
            if (node.turns + 1 >= maxTurns) continue

            val children = if (mixed) node.tile.neighbors + harborLinksOf(node.tile) else node.tile.neighbors
            for (next in children) {
                if (next === node.parent?.tile) continue
                if (!isBuildRouteValid(node.tile, next, route)) continue
                val cost = node.cost + buildRouteCost(next, route, purpose)
                val old = known[next]
                if (old != null && cost >= old.cost) continue
                val child = Node(next, cost, node.turns + 1, node, order++)
                known[next] = child
                open.add(child)
            }
        }
        return null
    }

    /** AddCityConnectionHarborConnections: the centers of the harbor cities of ours that the city of ours on [tile] reaches over water, none for a city being razed */
    private fun harborLinksOf(tile: Tile): Sequence<Tile> {
        val city = cityOn(tile)?.takeIf { it.civ == civ && !it.isBeingRazed } ?: return emptySequence()
        return harborLinks.getOrPut(city) { connectionsFinder.harborNeighbors(city) }.asSequence().filter { !it.isBeingRazed }.map { it.getCenterTile() }
    }

    /**
     *  CvAStar.cpp: BuildRouteValid. [route] must be buildable; the plot must be seen (a city-state needs no sight), be land, passable, and not
     *  belong to another civilization of the same kind (two majors, two city-states); one that is safe for a route (or whose parent is),
     *  and one that has the route anyway through the nation's trait is always valid.
     */
    private fun isBuildRouteValid(parent: Tile, tile: Tile, route: RoadStatus): Boolean {
        if (route > planBestRoute) return false
        if (!civ.isCityState && !tile.isExplored(civ)) return false
        if (tile.isWater) return false
        if (tile.isImpassible()) return false

        val owner = tile.getOwner()
        if (owner != null && owner != civ && owner.isCityState == civ.isCityState) return false
        if ((owner == null || owner == civ) && sameRouteBenefitFromTrait(tile, route)) return true

        // if the plot and its parent are both too far from our borders, don't build here
        return isPlotSafeForRoute(tile, true) || isPlotSafeForRoute(parent, true)
    }

    /**
     *  CvPlayer::IsPlotSafeForRoute: our plots, and with [includeAdjacent] the plots next to ours, the plots of city-states not at war with us, and
     *  with [includeAdjacent] the plots next to those.
     */
    private fun isPlotSafeForRoute(tile: Tile, includeAdjacent: Boolean): Boolean {
        val owner = tile.getOwner()
        if (owner == civ) return true
        if (includeAdjacent && tile.neighbors.any { it.getOwner() == civ }) return true
        if (owner != null && owner.isCityState && !owner.isAtWarWith(civ)) return true
        if (includeAdjacent && tile.neighbors.any { val adjacent = it.getOwner(); adjacent != null && adjacent.isCityState && !adjacent.isAtWarWith(civ) }) return true
        return false
    }

    /**
     *  CvAStar.cpp: BuildRouteCost (the village bonus of the original is none): a city costs nothing; a plot of the planned routes is cheap -
     *  for a capital connection the plots of its own planned route nearly free - then a plot that has the route 80 (of 120), one being built 80,
     *  one with a lesser route or a pillaged one 100, else 120; one more where another major's border is a danger.
     */
    private fun buildRouteCost(tile: Tile, route: RoadStatus, purpose: RoutePurpose): Int {
        // cities have a free road we can use
        if (tile.isCityCenter()) return 0

        val sameTrait = sameRouteBenefitFromTrait(tile, route)
        var plannedShortcut = sameTrait || isPlanned(tile, route, RoutePurpose.Shortcut)
        if (!plannedShortcut && route == RoadStatus.Road) plannedShortcut = isPlanned(tile, RoadStatus.Railroad, RoutePurpose.Shortcut)
        var plannedCapital = sameTrait || (purpose == RoutePurpose.ConnectCapital && isPlanned(tile, route, RoutePurpose.ConnectCapital))
        if (!plannedCapital && route == RoadStatus.Road && purpose == RoutePurpose.ConnectCapital)
            plannedCapital = isPlanned(tile, RoadStatus.Railroad, RoutePurpose.ConnectCapital)
        val buildProgress = tile.improvementInProgress == route.name

        // if we only care about reaching the destination, and no other bonuses, heavily reuse existing planned roads and features
        if (purpose == RoutePurpose.ConnectCapital) {
            if (plannedCapital) {
                return when {
                    sameTrait -> 0
                    tile.roadStatus >= route || buildProgress -> 1
                    else -> 5
                }
            }
            if (plannedShortcut) return 20
        }

        var cost = when {
            sameTrait -> PATH_BASE_COST / 3
            plannedShortcut -> PATH_BASE_COST * 5 / 12  // we are planning to build a road here: a discount
            tile.roadStatus >= route && !tile.roadIsPillaged -> PATH_BASE_COST * 8 / 12  // there is a road here already
            buildProgress -> PATH_BASE_COST * 8 / 12  // we are building one here
            tile.roadStatus >= RoadStatus.Road || sameRouteBenefitFromTrait(tile, RoadStatus.Road) || tile.roadIsPillaged -> PATH_BASE_COST * 10 / 12
            else -> PATH_BASE_COST
        }
        if (!sameTrait && !isPlotSafeForRoute(tile, false) && tile.neighbors.any { val owner = it.getOwner(); owner != null && owner != civ && !owner.isCityState })
            cost += 1
        return max(cost, 0)
    }

    /** IsRoutePlanned for the path finder, which runs while the plan is being made and reads the partial plan */
    private fun isPlanned(tile: Tile, route: RoadStatus, purpose: RoutePurpose) = PathPlot(route, tile, purpose) in anyRoutePlanned

    //endregion
    //region Directives

    /**
     *  CvBuilderTaskingAI::GetRouteDirectives: for each pair of end plots the best type - the road or the railroad, by the value plus the
     *  completion bonus (+100 - 25 per missing plot when at most 3 are missing) less the maintenance of its plots - then, for each plot of the
     *  chosen routes, the highest additive and non-additive value over the routes through it, the best route type and the shortest of the routes
     *  of that type ([bestRouteForPlot]); a directive for each plot the builders may consider.
     */
    private fun buildDirectives(): List<BuilderDirective> {
        // Loop through all planned routes and calculate the additive and non-additive values for them
        val plannedRouteTypeValues = TreeMap<Ends, HashMap<RoadStatus, IntArray>>()
        for (plannedRoute in plannedRoutePurposes.keys) {
            val additive = plannedRouteAdditiveValues[plannedRoute] ?: 0
            val nonAdditive = plannedRouteNonAdditiveValues[plannedRoute] ?: 0
            if (additive > 0 || nonAdditive > 0) {
                val values = plannedRouteTypeValues.getOrPut(Ends(plannedRoute.first, plannedRoute.second)) { HashMap() }.getOrPut(plannedRoute.route) { IntArray(2) }
                values[0] += additive
                values[1] = max(values[1], nonAdditive)
            }
        }

        // Loop through each pair of terminal plots and find the best route type and build value for this particular plot pair
        val bestRouteValues = TreeMap<Ends, IntArray>()
        val bestRouteType = HashMap<Ends, RoadStatus>()
        for ((ends, byRoute) in plannedRouteTypeValues) {
            val road = PlannedRoute.of(ends.first, ends.second, RoadStatus.Road)
            val railroad = PlannedRoute.of(ends.first, ends.second, RoadStatus.Railroad)
            var roadValue = byRoute[RoadStatus.Road]?.let { it[0] + it[1] } ?: 0
            var railroadValue = byRoute[RoadStatus.Railroad]?.let { it[0] + it[1] } ?: 0

            if (roadValue > 0) {
                // If the road is completed, increase its value
                val missing = routeMissingTiles(road)
                if (missing <= 3) roadValue += 100 - 25 * missing
                // The maintenance of the plots that need a road
                val maintenanceTiles = plannedRoutePlots[road].orEmpty().count { !sameRouteBenefitFromTrait(it, RoadStatus.Road) }
                roadValue -= maintenanceTiles * routeMaintenanceTimes100(RoadStatus.Road)
            }
            if (railroadValue > 0) {
                val missing = routeMissingTiles(railroad)
                if (missing <= 3) railroadValue += 100 - 25 * missing
                railroadValue -= plannedRoutePlots[railroad].orEmpty().size * routeMaintenanceTimes100(RoadStatus.Railroad)
            }

            if (roadValue > 0 || railroadValue > 0) {
                val type = if (roadValue > railroadValue) RoadStatus.Road else RoadStatus.Railroad
                bestRouteType[ends] = type
                bestRouteValues[ends] = byRoute[type] ?: IntArray(2)
            }
        }

        // Loop through all the best routes and give their respective plots the best value out of all possible paths
        val plotValues = HashMap<Tile, IntArray>()
        val plotBestRouteType = HashMap<Tile, RoadStatus>()
        val plotShortestRouteBuildTime = HashMap<Tile, Int>()
        for ((ends, newValues) in bestRouteValues) {
            val route = bestRouteType.getValue(ends)
            val plannedRoute = PlannedRoute.of(ends.first, ends.second, route)
            for (plot in plannedRoutePlots[plannedRoute].orEmpty()) {
                // the original's map starts a plot at ROUTE_ROAD
                val current = plotBestRouteType.getOrPut(plot) { RoadStatus.Road }
                if (route >= current) {
                    val shortest = if (route == current) plotShortestRouteBuildTime[plot] ?: Int.MAX_VALUE else Int.MAX_VALUE
                    val buildTime = routeBuildTime(plannedRoute, null)
                    if (buildTime < shortest || route > current) {
                        plotShortestRouteBuildTime[plot] = buildTime
                        plotBestRouteType[plot] = route
                        bestRouteForPlot[plot] = plannedRoute
                    }
                }
                // Two routes going through the same plot, but with different destinations don't have additive value properties
                val old = plotValues.getOrPut(plot) { IntArray(2) }
                old[0] = max(newValues[0], old[0])
                old[1] = max(newValues[1], old[1])
            }
        }

        // Finally, add build directives to the all the chosen plots
        val out = ArrayList<BuilderDirective>()
        for (plot in plotValues.keys.sortedBy { it.zeroBasedIndex }) {
            val values = plotValues.getValue(plot)
            val value = values[0] + values[1]
            if (value <= 0) continue
            if (!BuilderDirectives.shouldAnyBuilderConsiderPlot(civ, plot)) continue
            addRouteOrRepairDirective(out, plot, plotBestRouteType.getValue(plot), PlotBuildScore(value))
        }
        return out
    }

    /** CvBuilderTaskingAI::GetRouteMissingTiles: the plots of [planned] that lack the route or have it pillaged (a plot of the trait excepted) */
    private fun routeMissingTiles(planned: PlannedRoute): Int =
        plannedRoutePlots[planned].orEmpty().count { !sameRouteBenefitFromTrait(it, planned.route) && (it.roadStatus < planned.route || it.roadIsPillaged) }

    /**
     *  CvBuilderTaskingAI::GetRouteBuildTime: the turns [unit] (without one, the improvement's turns) needs for the plots of [planned] that
     *  do not have its route - the repair for a plot with the route pillaged - or -1 if the route is not planned.
     */
    private fun routeBuildTime(planned: PlannedRoute, unit: MapUnit?): Int {
        val plots = plannedRoutePlots[planned] ?: return -1
        val build = planned.route.improvement(ruleset) ?: return -1
        val repair = ruleset.tileImprovements[Constants.repair]
        var total = 0
        for (plot in plots) {
            if (sameRouteBenefitFromTrait(plot, planned.route)) continue
            if (plot.roadStatus == planned.route && !plot.roadIsPillaged) continue
            val improvement = if (plot.roadStatus == planned.route) repair ?: continue else build
            total += BuilderDirectives.turnsToBuild(civ, unit, plot, improvement)
        }
        return total
    }

    /** The maintenance of a plot of [route] in hundredths of gold: the route's upkeep × (100 + the policies' modifier) (GetGoldMaintenance × (100 + GetImprovementGoldMaintenanceMod)) */
    fun routeMaintenanceTimes100(route: RoadStatus) = route.upkeep * maintenancePercent

    /**
     *  CvBuilderTaskingAI::AddRouteOrRepairDirective: records [route] and the score as what ScorePlotBuild reads for [tile]; a plot with no
     *  route next to it (a route, unpillaged, or one of the trait) has a fifth of its score moved to the potential score (the original sets it
     *  back when a route is planned next to it); then the repair of a pillaged [route] or the build of it where it is not.
     */
    private fun addRouteOrRepairDirective(out: MutableList<BuilderDirective>, tile: Tile, route: RoadStatus, score: PlotBuildScore) {
        bestRouteTypeAndValue[tile] = route to score.priorityScore

        val anyAdjacentRoute = tile.neighbors.any { it.getUnpillagedRoad() != RoadStatus.None || sameRouteBenefitFromTrait(it, RoadStatus.Road) }
        val shown = if (anyAdjacentRoute) score else PlotBuildScore(score.score * 4 / 5, score.score / 5 + score.potentialScore)

        if (tile.roadStatus == route && tile.roadIsPillaged) addRepairRouteDirective(out, tile, route, shown)
        else if (tile.roadStatus != route) addRouteDirective(out, tile, route, shown)
    }

    /**
     *  CvBuilderTaskingAI::AddRouteDirective: a directive to build [route] on [tile] if the civilization can. Not for a plot with the trait's
     *  road, nor to replace an unpillaged better route that is not ours to pay for (a city-state's, or built by another).
     */
    private fun addRouteDirective(out: MutableList<BuilderDirective>, tile: Tile, route: RoadStatus, score: PlotBuildScore) {
        if (sameRouteBenefitFromTrait(tile, route)) return
        if (tile.roadStatus > route && !tile.roadIsPillaged && (civ.isCityState || tile.getRoadOwner() != civ)) return

        // can we even build the desired route
        val improvement = route.improvement(ruleset) ?: return
        if (!tile.improvementFunctions.canBuildImprovement(improvement, GameContext(civ, tile = tile))) return
        out.add(BuilderDirective(BuilderDirective.Type.BuildRoute, Build(improvement), tile, false, true, score))
    }

    /** CvBuilderTaskingAI::AddRepairRouteDirective: the repair of a pillaged route, if it is worth something */
    private fun addRepairRouteDirective(out: MutableList<BuilderDirective>, tile: Tile, route: RoadStatus, score: PlotBuildScore) {
        if (tile.roadStatus == RoadStatus.None || !tile.roadIsPillaged) return
        if (sameRouteBenefitFromTrait(tile, route)) return
        val repair = ruleset.tileImprovements[Constants.repair] ?: return
        if (score.maxScore > 0) out.add(BuilderDirective(BuilderDirective.Type.RepairRoute, Build(repair), tile, false, true, score))
    }

    //endregion

    companion object {
        /** MOVE_DENOMINATOR: a movement point in the original's units */
        private const val MOVE_DENOMINATOR = 60
        /** PATH_BASE_COST (CvAStar.cpp): the cost of a plot that has no route */
        private const val PATH_BASE_COST = 120
        /** The shortcut routes are planned between cities less than this many plots apart (ConnectCitiesForShortcuts) */
        private const val SHORTCUT_MAX_DISTANCE = 10
        /** The strategic routes go to plots less than this many plots from the city (ConnectPointsForStrategy) */
        private const val STRATEGIC_MAX_DISTANCE = 6
        /** ... and to plots of at least this strategic value */
        private const val STRATEGIC_MIN_VALUE = 50
        /** The defense modifier in percent from which an improvement makes its plot worth connecting (CvCityConnections::UpdatePlotsToConnect) */
        private const val STRATEGIC_DEFENSE_MODIFIER = 20
    }
}
