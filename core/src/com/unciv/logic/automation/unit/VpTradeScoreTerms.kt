package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.city.NeedType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteValue
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max

/**
 *  The terms of Vox Populi's trade route scoring (CvTradeAI::ScoreInternationalTR and ScoreInternalTR, V:CvTradeClasses.cpp:6538-7110, 7372-7585, and AddTradePathToCache,
 *  169-225) that read what slice T3 and T4 of `phaseE/vp-trade-plan.md` put into the engine, in one place for [VpInternationalTradeScore], [VpInternalTradeScore] and
 *  [VpTradePathScore].
 *
 *  Not ported: the terms of the corporation (invulnerable routes, offices and franchises, the franchise score: O1 of the plan), the foreign-territory yields of a trait
 *  (`MOD_TRAITS_YIELD_FROM_ROUTE_MOVEMENT_IN_FOREIGN_TERRITORY`: Portugal's route movement yield is one trigger without a territory), the terrain yields of a trait
 *  (`iScoreFromTerrain`: no such shape), the production siphon of a trait, the tourism bonuses of buildings (P5), the policy bump of
 *  `GetExtraCultureandScienceTradeRoutes` (no row), `GetGoldInternalTrade` and the trade partner trait of the internal score (internal gold routes are not ported, O3).
 */
object VpTradeScoreTerms {
    private val ignoreConditionals = GameContext.IgnoreConditionals

    /** The yields the original loops over, besides happiness (it has none among the yields of its routes) */
    private val YIELDS = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)

    /**
     *  Whether the scores take the science and culture of a route from Vox Populi's own estimate: with the route model of Vox Populi the route's real yield is a square
     *  root of the difference and the AI's estimate (the difference divided by TRADE_ROUTE_SCIENCE_DIVISOR_TIMES100) is another formula, as in the original; without it
     *  the estimate is the route's yield, as Brave New World's rules have it
     */
    fun usesVpEstimate(civ: Civilization): Boolean = civ.gameInfo.ruleset.modOptions.constants.tradeRouteModelLikeVp

    /**
     *  The science or culture one side expects from [difference] (the technologies, or policies, the other has and it lacks), V:CvTradeClasses.cpp:6720-6745 and 6808-6835:
     *  `max(1, difference x 100 / divisor)` for a positive difference, times the era of [eraOwner] when that is above 0
     */
    fun estimate(difference: Int, divisorTimes100: Int, eraOwner: Civilization): Int {
        if (difference <= 0) return 0
        var adjusted = max(difference * 100 / divisorTimes100, 1)
        if (eraOwner.getAiEraNumber() > 0) adjusted *= eraOwner.getAiEraNumber()
        return adjusted
    }

    /** What [origin] expects of the science of a route to [destination]: the technologies it lacks (V:CvTradeClasses.cpp:6720-6745) */
    fun originScience(origin: Civilization, destination: Civilization): Int =
        estimate(TradeRouteValue.techDifference(origin, destination), vp(origin).scienceDivisorTimes100, origin)

    /** What [destination] gets of the science of a route from [origin]: its own lacking technologies, times its own era (V:CvTradeClasses.cpp:6770-6795) */
    fun destinationScience(origin: Civilization, destination: Civilization): Int =
        estimate(TradeRouteValue.techDifference(destination, origin), vp(origin).scienceDivisorTimes100, destination)

    /** What [origin] expects of the culture of a route to [destination]: the policies it lacks (V:CvTradeClasses.cpp:6808-6835) */
    fun originCulture(origin: Civilization, destination: Civilization): Int =
        estimate(TradeRouteValue.policyDifference(origin, destination), vp(origin).cultureDivisorTimes100, origin)

    /**
     *  What [destination] gets of the culture of a route from [origin]: its lacking policies, times the era of the ORIGIN, which is the original's own line
     *  (V:CvTradeClasses.cpp:6877 reads the origin owner where the science term, 6789, reads the destination owner)
     */
    fun destinationCulture(origin: Civilization, destination: Civilization): Int =
        estimate(TradeRouteValue.policyDifference(destination, origin), vp(origin).cultureDivisorTimes100, origin)

    /**
     *  A city that is short of a yield sends routes to get it (V:CvTradeClasses.cpp:6611-6617, 6753-6758 and 6842-6847, `MOD_BALANCE_VP`): the unhappy citizens of its
     *  [need] (poverty for gold, illiteracy for science, boredom for culture), only with the needs model
     */
    fun needBonus(city: City, need: NeedType): Int =
        if (city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) city.cityStats.needs.yieldNeed(need) else 0

    /** CvCity::GetUnhappinessFromFamine (V:CvTradeClasses.cpp:7430): the citizens [city] cannot feed, with the needs model, else 0 */
    fun famine(city: City): Int = if (city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) city.cityStats.needs.famine else 0

    /** CvCity::GetUnhappinessFromIsolation (V:CvTradeClasses.cpp:7514-7521): the citizens unhappy for lack of a connection to the capital, with the needs model, else 0 */
    fun isolation(city: City): Int = if (city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) city.cityStats.needs.isolation else 0

    /**
     *  CvCity::GetYieldFromInternationalTREnd and GetYieldFromInternalTREnd (the city table of buildings): what the buildings of [city] give of [stat] when a route
     *  ends, as `Gain [n] [stat] <upon completing a [international|domestic|any] trade route> <in this city>`
     */
    fun routeEndYield(city: City, stat: Stat, international: Boolean): Int {
        val kind = if (international) "international" else "domestic"
        var total = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.OneTimeGainStat, ignoreConditionals)) {
                if (!unique.isLocalEffect || unique.params[1] != stat.name) continue
                val trigger = unique.getModifiers(UniqueType.TriggerUponCompletingTradeRoute).firstOrNull() ?: continue
                if (trigger.params[0] == "any" || trigger.params[0] == kind) total += unique.params[0].toInt()
            }
        return total
    }

    /** All the yields [routeEndYield] gives, by [YIELDS] */
    fun routeEndYields(city: City, international: Boolean): List<Pair<Stat, Int>> =
        YIELDS.map { it to routeEndYield(city, it, international) }.filter { it.second > 0 }

    /**
     *  AddTradePathToCache's `iScoreFromPassingTR` (V:CvTradeClasses.cpp:205-210): for each city of [civ] on [path] that no route passes through now, what its buildings
     *  give while a route passes ([UniqueType.StatsWhenTradeRoutePasses]), all yields added up
     */
    fun passingRouteScore(civ: Civilization, path: List<Tile>): Int {
        var score = 0
        for (tile in path) {
            if (tile.getOwner() != civ || !tile.isCityCenter()) continue
            val city = tile.getCity() ?: continue
            if (TradeRouteValue.isOnRoute(civ.gameInfo, tile)) continue
            city.forEachMatchingUnique(UniqueType.StatsWhenTradeRoutePasses, city.state, false) { unique ->
                for (stat in YIELDS) score += unique.stats[stat].toInt()
            }
        }
        return score
    }

    private fun vp(civ: Civilization) = civ.gameInfo.ruleset.modOptions.constants.tradeRouteVp
}
