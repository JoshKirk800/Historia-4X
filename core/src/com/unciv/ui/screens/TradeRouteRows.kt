package com.unciv.ui.screens

import com.unciv.logic.automation.unit.VpTradeScoreTerms
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteValue
import com.unciv.models.TradeRoute
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly
import java.util.Locale

/**
 *  What the trade route screen tells about a route under Vox Populi's route model (slice T9 of `phaseE/vp-trade-plan.md`; CvPlayerTrade::GetTradeConnectionValueTimes100, the
 *  route events and the per-city route effects, V:CvTradeClasses.cpp:2606-4247, V:CvCity.cpp:23328): each yield of the route the viewer gets, the proximity penalty that took
 *  from the modifier of an international route, what the buildings of the origin give when the route ends, what the cities of the viewer on the path give while it passes, and
 *  a plunderer on the route. As plain data ([NeedsRow]) that the screens draw and tests read without a screen. Empty unless
 *  [com.unciv.models.ModConstants.tradeRouteModelLikeVp] is on, so the screens of Brave New World's rules stay as they were.
 *
 *  Not shown: tourism and golden age points of a route (P5 and open), the corporation's terms (no corporations, O1 of the plan).
 */
object TradeRouteRows {
    private const val PROXIMITY = "Proximity penalty"
    private const val ROUTE_END = "When the route ends in [city]"
    private const val PASSING = "While a route passes [city]"
    private const val PLUNDERER = "An enemy unit stands on the route: it can be plundered for [gold] [Gold]"
    private const val PLUNDERED = "The connection was plundered lately: a trade unit will not be sent again soon"

    private val yields = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)

    @Readonly private fun oneDecimal(amount: Float) = String.format(Locale.ROOT, "%.1f", amount)

    /** The yields of [stats] that are not 0, in the order of the stats of the route, as lines (`+2.4`) */
    @Readonly
    fun yieldRows(stats: Stats): List<NeedsRow> =
        yields.filter { stats[it] != 0f }.map { NeedsRow(it.name, (if (stats[it] > 0) "+" else "") + oneDecimal(stats[it]), indent = 1) }

    /**
     *  The rows of [route] for [viewer], the origin's owner if [isOwn] else the owner of the destination: the yields the viewer gets, the proximity penalty of an international
     *  route (what VP's `GetTradeConnectionDistanceValueModifierTimes100` takes off the modifier of the end the viewer is on), the yields of the buildings of the origin when the
     *  route ends (the viewer's own routes only), the yields of the viewer's cities on the path while a route passes, and a plundered connection or a plunderer on the way.
     */
    fun rows(route: TradeRoute, viewer: Civilization, isOwn: Boolean): List<NeedsRow> {
        if (!viewer.gameInfo.ruleset.modOptions.constants.tradeRouteModelLikeVp) return emptyList()
        val origin = route.getOriginCity() ?: return emptyList()
        val destination = route.getDestinationCity() ?: return emptyList()
        val result = ArrayList<NeedsRow>()
        result.addAll(yieldRows(if (isOwn) route.originYields else route.destinationYields))

        if (route.isInternational()) {
            val computing = if (isOwn) origin.civ else destination.civ
            val proximity = TradeRouteValue.proximityModifier(route, origin, destination, computing, origin.civ.tradeRoutes)
            if (proximity > 0) result.add(NeedsRow(PROXIMITY, "-$proximity%", indent = 1, isAlert = true))
        }
        if (isOwn) result.addAll(routeEndRows(origin, route.isInternational()))
        result.addAll(passingRows(route, viewer))

        if (isOwn) {
            val manager = viewer.tradeRoutes
            if (manager.wasPlunderedRecently(origin, destination)) result.add(NeedsRow(PLUNDERED, style = NeedsRow.Style.Note, isAlert = true))
            val plunderer = manager.getPlunderers(route).firstOrNull()
            if (plunderer != null) {
                val gold = TradeRouteValue.plunderGold(route, plunderer.civ)
                result.add(NeedsRow(PLUNDERER.replace("[gold]", "[$gold]"), style = NeedsRow.Style.Note, isAlert = true))
            }
        }
        return result
    }

    /** What the buildings of [origin] give when a route ends, each yield of the original's `Building_YieldFromInternationalTREnd` and `...InternalTREnd` that is above 0 (V:CvTradeClasses.cpp:2606-2650) */
    fun routeEndRows(origin: City, international: Boolean): List<NeedsRow> {
        val ends = VpTradeScoreTerms.routeEndYields(origin, international)
        if (ends.isEmpty()) return emptyList()
        val rows = arrayListOf(NeedsRow(ROUTE_END.replace("[city]", "[${origin.name}]"), style = NeedsRow.Style.Heading, indent = 1))
        for ((stat, amount) in ends) rows.add(NeedsRow(stat.name, "+$amount", indent = 2))
        return rows
    }

    /** What the cities of [viewer] on the path of [route] give while a route passes them (`Building_YieldChangesFromPassingTR`, V:CvCity.cpp:23328) */
    @Readonly
    fun passingRows(route: TradeRoute, viewer: Civilization): List<NeedsRow> {
        val rows = ArrayList<NeedsRow>()
        for (tile in route.getTiles()) {
            val city = tile.getCity()?.takeIf { it.civ == viewer && tile.isCityCenter() } ?: continue
            if (!city.cityConstructions.builtBuildingUniqueMap.hasUnique(UniqueType.StatsWhenTradeRoutePasses, GameContext.IgnoreConditionals)) continue
            val stats = Stats()
            city.forEachMatchingUnique(UniqueType.StatsWhenTradeRoutePasses, city.state, false) { stats.add(it.stats) }
            val lines = yieldRows(stats)
            if (lines.isEmpty()) continue
            rows.add(NeedsRow(PASSING.replace("[city]", "[${city.name}]"), style = NeedsRow.Style.Heading, indent = 1))
            for (line in lines) rows.add(line.copy(indent = 2))
        }
        return rows
    }
}
