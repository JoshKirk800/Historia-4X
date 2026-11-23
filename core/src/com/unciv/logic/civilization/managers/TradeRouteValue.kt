package com.unciv.logic.civilization.managers

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TradeRoutePathfinder
import com.unciv.logic.map.tile.Tile
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The value of a trade route in Vox Populi (CvPlayerTrade::GetTradeConnectionValueTimes100 and the functions it sums, V:CvTradeClasses.cpp:2747-4247, Release-5.4.8),
 * what a route does to the cities and tiles it passes (V:CvCity.cpp:22495, 23328; V:CvPlot.cpp:10716-10760, 10955-10975) and what a plundered route pays (V:CvTradeClasses.cpp:4995-5030).
 * Read by [TradeRouteManager] only with [com.unciv.models.ModConstants.tradeRouteModelLikeVp]; the per-city and per-tile effects are read when a ruleset has the uniques.
 * All amounts are hundredths of a yield, as VP's `Times100`, unless the name says otherwise. The functions are named after VP's; the state they read is the route, its two
 * cities and the civilizations' uniques, the constants are [com.unciv.models.ModConstants.TradeRouteVpConstants].
 *
 * The international route (the origin's side, gold): `(base + gpt of both cities + the policies' and our buildings' bonus + their buildings' bonus + influence + city-state gold)
 * x (100 - proximity + sea + resources + river + open borders + the percentage of the origin's policies) / 100`, at least 100; the destination's gold (no base: only its buildings'
 * recipient bonus and influence) x (100 - proximity + sea + river); science and culture the scaled difference of techs and policies times the origin's era. The domestic route: the base
 * and the policies' bonus and the era's bonus times the start era's percentage, plus a third of what the origin is larger, food times the share of the techs known, plus the origin's
 * buildings, times the percentage of the policies; the destination city only.
 *
 * Departures and what is not ported (each is a row-less or shape-less table of Vox Populi, or a system we have not): a corporation's route terms and franchises (owner decision O1:
 * no corporations), the espionage "diplomat" modifier (no schmoozing), `Trait_YieldChangesIncomingTradeRoute`, `Trait_TradeRouteYieldChange` and the faith, tourism and golden age
 * point yields of a route (no rows in Vox Populi 5.4.8, or P5), the exclusive-destination bonus (`INTERNATIONAL_TRADE_EXCLUSIVE_CONNECTION` is 0), `POLICYMOD_TRADE_CAPITAL_MODIFIER`
 * and the internal capital modifier (no rows), `ExtraCultureandScienceTradeRoutes` (no rows), the shared ideology bonus (`SharedIdeologyTradeGoldChange`: the ideology branches of the
 * ruleset have no unique for it yet), `ConquestOfTheWorld` (no rows), `TRTurnMod`, the high seas raiders' triple plunder, and internal gold and wonder resource routes (empty tables, O1).
 * The beliefs' and policies' route yield changes ([UniqueType.StatsOnTradeRouteDomain]) are read for gold, culture and science of an international route and for food and production of
 * a domestic one, which is all the original's beliefs give (V:CvTradeClasses.cpp:3254-3266).
 */
object TradeRouteValue {

    /** The yields of a route: [origin] for the city it starts in, [destination] for the city it ends in */
    class Yields(val origin: Stats = Stats(), val destination: Stats = Stats())

    /**
     * The numbers of the two ends of [route], none for a route whose cities are gone.
     * [hasGlobalMonopoly] is CvPlayer::HasGlobalMonopoly as the route value asks it (V:CvTradeClasses.cpp:3010); the monopolies are slice T2 of `phaseE/vp-trade-plan.md`
     * (`Civilization.monopolies`), so until that is merged the default says that nobody has one, and when it is the manager hands `owner.monopolies.hasGlobalMonopoly(resource)` over.
     */
    @Readonly
    fun compute(route: TradeRoute, manager: TradeRouteManager, hasGlobalMonopoly: (Civilization, String) -> Boolean = { _, _ -> false }): Yields {
        val origin = route.getOriginCity() ?: return Yields()
        val destination = route.getDestinationCity() ?: return Yields()
        return if (route.isInternational()) international(route, origin, destination, manager, hasGlobalMonopoly) else domestic(route, origin, destination)
    }

    //region International

    @Readonly
    private fun international(route: TradeRoute, origin: City, destination: City, manager: TradeRouteManager, hasGlobalMonopoly: (Civilization, String) -> Boolean): Yields {
        val originCiv = origin.civ
        val destinationCiv = destination.civ
        val vp = vp(originCiv)
        val bothMajor = originCiv.isMajorCiv && destinationCiv.isMajorCiv

        // The origin's side, V:CvTradeClasses.cpp:3639-3727
        val originProximity = proximityModifier(route, origin, destination, originCiv, manager)
        val goldBase = originGoldBase(route, origin, destination, bothMajor)
        val goldModifier = originGoldModifier(route, origin, destination, originProximity, hasGlobalMonopoly)
        val minorCivGold = minorCivGoldBonus(originCiv, destinationCiv)
        val influenceGold = influenceGoldBonus(originCiv, destinationCiv)
        val gold = max(100, (goldBase + minorCivGold + influenceGold) * goldModifier / 100) / 100f

        // Science, culture and faith share the modifiers that are not about gold, V:CvTradeClasses.cpp:3730-3772
        val otherModifier = 100 + percentOfOrigin(originCiv) // no distance for culture and science, the corporation's modifier is not ported
        val science = (originScience(route, origin, destination) * otherModifier / 100) / 100f
        val culture = (originCulture(route, origin, destination) * otherModifier / 100) / 100f

        // A city-state a Feitoria siphons pays food or production through the gold formula, V:CvTradeClasses.cpp:3765-3896
        var production = 0f
        var food = 0f
        if (destinationCiv.isCityState && isSiphoned(originCiv, destinationCiv)) {
            val ally = destinationCiv.allyCiv == originCiv
            val friend = !ally && isFriend(destinationCiv, originCiv)
            val productionDivisor = if (ally) 150 else if (friend) 200 else 250
            val foodDivisor = if (ally) 200 else if (friend) 300 else 400
            // the production's sum has the influence boost, the food's has not, neither has the city-state's gold
            production = max(100, (goldBase + influenceGold) * goldModifier / productionDivisor) / 100f
            food = max(100, goldBase * goldModifier / foodDivisor) / 100f
        }

        // The destination's side, V:CvTradeClasses.cpp:4023-4108; a city-state gets nothing of the routes to it (it has no tech or policy difference, V:CvTradeClasses.cpp:1912)
        var destinationGold = 0f
        var destinationScience = 0f
        var destinationCulture = 0f
        if (destinationCiv.isMajorCiv) {
            val destinationProximity = proximityModifier(route, origin, destination, destinationCiv, manager)
            val theirBuildings = if (bothMajor) tradeRouteGold(destination, route, UniqueType.TradeRouteGoldForDestination) else 0
            val destinationValue = theirBuildings + influenceGoldBonus(destinationCiv, originCiv)
            val modifier = 100 - destinationProximity + domainModifier(route, vp) + riverModifier(route, destination, vp)
            destinationGold = (destinationValue * modifier / 100) / 100f
            // no base and no era for the destination's science and culture (V:CvTradeClasses.cpp:2846-2900), and no distance
            destinationScience = scaledDifference(techDifference(destinationCiv, originCiv), vp.scienceDivisorTimes100).toFloat()
            destinationCulture = scaledDifference(policyDifference(destinationCiv, originCiv), vp.cultureDivisorTimes100).toFloat()
        }
        return Yields(Stats(production = production, food = food, gold = gold, science = science, culture = culture),
            Stats(gold = destinationGold, science = destinationScience, culture = destinationCulture))
    }

    /** CvPlayerTrade::GetTradeConnectionBaseValueTimes100 and the bonuses added to it before the modifier (gpt, policies, buildings), without influence and city-state gold */
    @Readonly
    private fun originGoldBase(route: TradeRoute, origin: City, destination: City, bothMajor: Boolean): Int {
        var value = base(origin.civ) + gpt(origin) + gpt(destination)
        // GetTradeConnectionPolicyValueTimes100 and GetTradeConnectionYourBuildingValueTimes100: what the civilization's policies and the origin's buildings give a route
        value += tradeRouteGold(origin, route, UniqueType.TradeRouteGold)
        value += routeYieldChange(route, origin, Stat.Gold)
        // GetTradeConnectionTheirBuildingValueTimes100: the destination's buildings, not for a city-state
        if (bothMajor) value += tradeRouteGold(destination, route, UniqueType.TradeRouteGoldForOrigin)
        return value
    }

    @Readonly
    private fun originGoldModifier(route: TradeRoute, origin: City, destination: City, proximity: Int, hasGlobalMonopoly: (Civilization, String) -> Boolean): Int {
        val vp = vp(origin.civ)
        return 100 - proximity + domainModifier(route, vp) + resourceModifier(origin, destination, hasGlobalMonopoly) + riverModifier(route, origin, vp) +
            openBordersModifier(origin.civ, destination.civ, vp) + percentOfOrigin(origin.civ)
    }

    /** V:CvTradeClasses.cpp:2751-2762: INTERNATIONAL_TRADE_BASE x (era + 2) */
    @Readonly
    fun base(originCiv: Civilization): Int = vp(originCiv).internationalTradeBase * (originCiv.getEraNumber() + 2)

    /** CvPlayerTrade::GetTradeConnectionGPTValueTimes100: the city's gold per turn (without what trade brings) times 100, divided by INTERNATIONAL_TRADE_CITY_GPT_DIVISOR */
    @Readonly
    fun gpt(city: City): Int = city.civ.tradeRoutes.getGoldTimes100(city) / max(1, vp(city.civ).cityGoldDivisor)

    /** The unique of [city] that adds [type] gold, in hundredths, to the routes that match its filter */
    @Readonly
    private fun tradeRouteGold(city: City, route: TradeRoute, type: UniqueType): Int {
        var gold = 0
        city.forEachMatchingUnique(type) { if (route.matchesFilter(it.params[1])) gold += it.params[0].toInt() * 100 }
        return gold
    }

    /** POLICYMOD_TRADE_MODIFIER: what the origin's policies add to the modifier of every international yield (V:CvTradeClasses.cpp:3705-3707) */
    @Readonly
    private fun percentOfOrigin(originCiv: Civilization): Int {
        var percent = 0
        originCiv.forEachMatchingUnique(UniqueType.InternationalTradeRouteYieldPercent) { percent += it.params[0].toInt() }
        return percent
    }

    /** GetDomainModifierTimes100: TRADE_ROUTE_BASE_SEA_MODIFIER and ..._LAND_MODIFIER (V:CvTradeClasses.cpp:1126-1134) */
    @Readonly
    private fun domainModifier(route: TradeRoute, vp: com.unciv.models.ModConstants.TradeRouteVpConstants) = if (route.isSea) vp.seaModifier else vp.landModifier

    /** CvPlayerTrade::GetTradeConnectionRiverValueModifierTimes100: a river city's end of a land route's gold (V:CvTradeClasses.cpp:3421-3456) */
    @Readonly
    private fun riverModifier(route: TradeRoute, city: City, vp: com.unciv.models.ModConstants.TradeRouteVpConstants) =
        if (!route.isSea && city.getCenterTile().isAdjacentToRiver()) vp.riverModifier else 0

    /**
     * CvPlayerTrade::GetTradeConnectionOpenBordersModifierTimes100: OPEN_BORDERS_MODIFIER_TRADE_GOLD for the origin when both civilizations let the other in, half of it when one does.
     * The original gives the destination nothing (the `!bAsOriginPlayer` halving in V:CvTradeClasses.cpp:3527 is never called for it).
     */
    @Readonly
    private fun openBordersModifier(originCiv: Civilization, destinationCiv: Civilization, vp: com.unciv.models.ModConstants.TradeRouteVpConstants): Int {
        val originOpens = originCiv.getDiplomacyManager(destinationCiv)?.hasOpenBorders == true
        val destinationOpens = destinationCiv.getDiplomacyManager(originCiv)?.hasOpenBorders == true
        return when {
            originOpens && destinationOpens -> vp.openBordersModifier
            originOpens || destinationOpens -> vp.openBordersModifier / 2
            else -> 0
        }
    }

    /** CvPlayerTrade::GetMinorCivGoldBonus: TRADE_ROUTE_CS_ALLY_GOLD (2) or ..._FRIEND_GOLD (1) x 100, times the era when that is above 0 (V:CvTradeClasses.cpp:3097-3135) */
    @Readonly
    private fun minorCivGoldBonus(originCiv: Civilization, destinationCiv: Civilization): Int {
        if (!destinationCiv.isCityState) return 0
        val vp = vp(originCiv)
        val gold = when {
            destinationCiv.allyCiv == originCiv -> vp.cityStateAllyGold * 100
            isFriend(destinationCiv, originCiv) -> vp.cityStateFriendGold * 100
            else -> return 0
        }
        return if (gold > 0 && originCiv.getEraNumber() > 0) gold * originCiv.getEraNumber() else gold
    }

    /** CvMinorCivAI::IsFriends: the city-state's friendship with [civ] is at least that of a friend */
    @Readonly
    private fun isFriend(cityState: Civilization, civ: Civilization) =
        cityState.getDiplomacyManager(civ)?.isRelationshipLevelGE(com.unciv.logic.civilization.diplomacy.RelationshipLevel.Friend) == true

    /**
     * CvPlayerCulture::GetInfluenceTradeRouteGoldBonus: BALANCE_GOLD_INFLUENCE_LEVEL_EXOTIC to ..._DOMINANT, 200, 400, 600, 800 and 1000 hundredths for the influence of [from] on [on],
     * only for a major civilization (V:CvCultureClasses.cpp:4027-4060, the defines in `(1) Community Patch/Database Changes/Defines/CoreDefineChanges.sql:600-604`)
     */
    @Readonly
    private fun influenceGoldBonus(from: Civilization, on: Civilization) = if (on.isMajorCiv) 200 * influenceSteps(from, on) else 0

    /** CvPlayerCulture::GetInfluenceTradeRouteScienceBonus: 2, 4, 6, 8 and 10 for Exotic to Dominant (VP: `(2) Vox Populi/Database Changes/DefineChanges.sql:418-422`), whole science */
    @Readonly
    private fun influenceScienceBonus(from: Civilization, on: Civilization) = if (on.isMajorCiv) 2 * influenceSteps(from, on) else 0

    /** The influence level of [from] on [on] counted from Unknown: 0 for Unknown, 1 for Exotic, ... 5 for Dominant */
    @Readonly
    private fun influenceSteps(from: Civilization, on: Civilization) = from.culture.getInfluenceLevel(on)?.ordinal ?: 0

    //endregion
    //region Resource diversity

    /**
     * CvPlayerTrade::GetTradeConnectionResourceValueTimes100 with the monopoly option on, a modifier added to 100 (V:CvTradeClasses.cpp:2958-3037): TRADE_ROUTE_DIFFERENT_RESOURCE_VALUE
     * (5) for each resource, of any type, that exactly one of the two cities makes, twice that when either owner has a global monopoly of it; times 100 plus the origin's
     * [UniqueType.TradeRouteResourceGoldPercent] (the trait of Carthage, the buildings' diversity modifier) divided by 100 when it is positive, else divided by it.
     */
    @Readonly
    fun resourceModifier(origin: City, destination: City, hasGlobalMonopoly: (Civilization, String) -> Boolean): Int {
        val vp = vp(origin.civ)
        val ours = madeBy(origin)
        val theirs = madeBy(destination)
        var value = 0
        for (resource in (ours - theirs) + (theirs - ours)) {
            val monopoly = hasGlobalMonopoly(origin.civ, resource) || hasGlobalMonopoly(destination.civ, resource)
            value += (if (monopoly) 2 else 1) * vp.differentResourceValue
        }
        var percent = 100
        origin.forEachMatchingUnique(UniqueType.TradeRouteResourceGoldPercent) { percent += it.params[0].toInt() }
        return if (value > 0) value * percent / 100 else value * 100 / max(1, percent)
    }

    /** CvCity::IsHasResourceLocal(resource, true) for every resource: the resources the city has copies of */
    @Readonly
    private fun madeBy(city: City): Set<String> = city.getResourcesGeneratedByCity().filter { it.amount > 0 }.map { it.resource.name }.toSet()

    //endregion
    //region Proximity

    /**
     * CvPlayerTrade::GetTradeConnectionDistanceValueModifierTimes100's last lines (V:CvTradeClasses.cpp:3405-3418): the percent a route that costs [pathCost] to walk is worth less than
     * the longest route [longest] its origin could have, `100 - pathCost x 100 / longest` cut to 0 to [maximum]; 0 for a route that is as long as that or longer, and without a longest route
     */
    @Readonly
    fun proximityReduction(pathCost: Int, longest: Int, maximum: Int): Int {
        if (pathCost <= 0) return 0
        val reduction = 100 - pathCost * 100 / max(1, longest)
        return max(0, min(maximum, reduction))
    }

    /**
     * CvPlayerTrade::GetTradeConnectionDistanceValueModifierTimes100 for the civilization [computing] (the owner of the end that the value is for, V:CvTradeClasses.cpp:3372-3418):
     * none between a civilization's own cities, none when [computing] or the owner of the destination is a major civilization with [UniqueType.NoTradeRouteProximityPenalty].
     */
    @Readonly
    fun proximityModifier(route: TradeRoute, origin: City, destination: City, computing: Civilization, manager: TradeRouteManager): Int {
        if (origin.civ == destination.civ) return 0
        if (computing.isMajorCiv && computing.hasUnique(UniqueType.NoTradeRouteProximityPenalty)) return 0
        if (destination.civ.isMajorCiv && destination.civ.hasUnique(UniqueType.NoTradeRouteProximityPenalty)) return 0
        val cost = TradeRoutePathfinder.pathCost(origin.civ, route.getTiles(), route.isSea)
        return proximityReduction(cost, manager.getLongestPotentialRoute(origin, route.isSea), vp(origin.civ).proximityMax)
    }

    //endregion
    //region Science, culture, policies

    /** CvGameTrade::GetTechDifference: the technologies of [other] that [civ] lacks, 1 for the ally of a city-state and 0 for its other friends (V:CvTradeClasses.cpp:1909-1950) */
    @Readonly
    fun techDifference(civ: Civilization, other: Civilization): Int {
        if (civ.isCityState || civ.isBarbarian || other.isBarbarian) return 0
        if (other.isCityState) return if (other.allyCiv == civ) max(0, vp(civ).allyScienceDelta) else 0
        return other.tech.techsResearched.count { it !in civ.tech.techsResearched }
    }

    /** CvGameTrade::GetPolicyDifference: how many more policies, openers counted and finishers not, [other] has than [civ] (V:CvTradeClasses.cpp:1995-2035) */
    @Readonly
    fun policyDifference(civ: Civilization, other: Civilization): Int {
        if (civ.isCityState || civ.isBarbarian || other.isBarbarian) return 0
        if (other.isCityState) return if (other.allyCiv == civ) max(0, vp(civ).allyCultureDelta) else 0
        return policiesOwned(other) - policiesOwned(civ)
    }

    /** CvPlayerPolicies::GetNumPoliciesOwned(true, false, true): the policies adopted, the starting policy of each branch included, its completion not */
    @Readonly
    private fun policiesOwned(civ: Civilization) =
        civ.policies.getAdoptedPolicies().count { !com.unciv.models.ruleset.Policy.isBranchCompleteByName(it) }

    /** `max(1, int(sqrt(difference) x 200 / divisor))` for a difference above 0, else 0 (V:CvTradeClasses.cpp:2775-2780) */
    @Readonly
    fun scaledDifference(difference: Int, divisorTimes100: Int): Int {
        if (difference <= 0) return 0
        return max(1, (sqrt(difference.toFloat()) * 200f / divisorTimes100).toInt())
    }

    /** The origin's science: V:CvTradeClasses.cpp:2763-2820 (the part for the origin player), whole science times 100 */
    @Readonly
    private fun originScience(route: TradeRoute, origin: City, destination: City): Int {
        val originCiv = origin.civ
        var adjusted = scaledDifference(techDifference(originCiv, destination.civ), vp(originCiv).scienceDivisorTimes100)
        if (adjusted > 0 && originCiv.getEraNumber() > 0) adjusted *= originCiv.getEraNumber()
        adjusted += influenceScienceBonus(originCiv, destination.civ)
        return adjusted * 100 + routeYieldChange(route, origin, Stat.Science)
    }

    /** The origin's culture: V:CvTradeClasses.cpp:2822-2845 */
    @Readonly
    private fun originCulture(route: TradeRoute, origin: City, destination: City): Int {
        val originCiv = origin.civ
        var adjusted = scaledDifference(policyDifference(originCiv, destination.civ), vp(originCiv).cultureDivisorTimes100)
        if (adjusted > 0 && originCiv.getEraNumber() > 0) adjusted *= originCiv.getEraNumber()
        return adjusted * 100 + routeYieldChange(route, origin, Stat.Culture)
    }

    /**
     * CvPlayerTrade::GetTradeConnectionPolicyValueTimes100's route yield changes: what the [UniqueType.StatsOnTradeRouteDomain] uniques of [origin] (its civilization's, its religion's
     * and its buildings') add to [stat] of [route], in hundredths. Gold, culture and science of an international route and food and production of a domestic one only
     * (V:CvTradeClasses.cpp:3254-3266).
     */
    @Readonly
    fun routeYieldChange(route: TradeRoute, origin: City, stat: Stat): Int {
        val international = route.isInternational()
        val applies = if (international) stat == Stat.Gold || stat == Stat.Culture || stat == Stat.Science else stat == Stat.Food || stat == Stat.Production
        if (!applies) return 0
        var change = 0f
        origin.forEachMatchingUnique(UniqueType.StatsOnTradeRouteDomain) { if (route.matchesFilter(it.params[1])) change += it.stats[stat] * 100 }
        return change.roundToInt()
    }

    //endregion
    //region Domestic

    /** V:CvTradeClasses.cpp:4109-4235: what a food or production route brings the city it ends in */
    @Readonly
    private fun domestic(route: TradeRoute, origin: City, destination: City): Yields {
        val civ = origin.civ
        val vp = vp(civ)
        val isFood = route.kind == TradeRouteKind.Food
        val stat = if (isFood) Stat.Food else Stat.Production
        val era = civ.getEra()
        val startEra = civ.gameInfo.getStartingEra()

        var value = (if (isFood) vp.baseFoodValue else vp.baseProductionValue) + routeYieldChange(route, origin, stat)
        value += if (isFood) era.tradeRouteFoodBonus else era.tradeRouteProductionBonus
        value = value * (if (isFood) startEra.growthPercent else (startEra.constructPercent + startEra.trainPercent) / 2) / 100

        // A larger origin sends more, a third of the difference in citizens (at least 1); food also grows with the technologies known
        val originPopulation = origin.population.population
        val destinationPopulation = destination.population.population
        if (originPopulation > destinationPopulation)
            value += max((originPopulation - destinationPopulation) / 3 * 100, 100)
        if (isFood) {
            val techProgress = civ.tech.techsResearched.size * 100 / max(1, civ.gameInfo.ruleset.technologies.size) / 2
            value = value * (techProgress + 100) / 100
        }

        // The origin's buildings (Building_YieldFromInternalTR), before the modifiers
        origin.forEachMatchingUnique(UniqueType.StatsOnDomesticRoutesFromCity, origin.state, false) { value += (it.stats[stat] * 100).roundToInt() }

        var modifier = 100 + domainModifier(route, vp)
        civ.forEachMatchingUnique(UniqueType.DomesticTradeRouteYieldPercent) { modifier += it.params[0].toInt() }
        value = value * modifier / 100

        return Yields(destination = if (isFood) Stats(food = value / 100f) else Stats(production = value / 100f))
    }

    //endregion
    //region Siphon

    /** CvMinorCivAI::IsSiphoned: [civ] has an unpillaged improvement that siphons luxuries on a tile of [cityState] */
    @Readonly
    fun isSiphoned(civ: Civilization, cityState: Civilization): Boolean = cityState.cities.any { city ->
        city.getTiles().any { tile ->
            tile.getOwner() == cityState && tile.improvementBuilder == civ.civID && !tile.improvementIsPillaged &&
                tile.tileImprovement?.hasUnique(UniqueType.SiphonsLuxuriesFromCityState) == true
        }
    }

    //endregion
    //region Cities and tiles

    /**
     * What the routes do to [city] besides their own yields (V:CvCity.cpp): [UniqueType.StatsWhenTradeRoutePasses] while the path of any route goes over its tile
     * (`GetYieldFromPassingTR`, V:CvCity.cpp:23328) and [UniqueType.StatsPerTradeRouteOfCity] for each route from or to it (`Belief_YieldPerActiveTR`, V:CvCity.cpp:22495).
     * Base yields, which the city's percentages apply to.
     */
    @Readonly
    fun getCityStats(city: City): Stats {
        val stats = Stats()
        if (city.cityConstructions.builtBuildingUniqueMap.hasUnique(UniqueType.StatsWhenTradeRoutePasses, GameContext.IgnoreConditionals) && isOnRoute(city.civ.gameInfo, city.getCenterTile()))
            city.forEachMatchingUnique(UniqueType.StatsWhenTradeRoutePasses, city.state, false) { stats.add(it.stats) }
        var routes = -1
        city.forEachMatchingUnique(UniqueType.StatsPerTradeRouteOfCity) {
            if (routes < 0) routes = numberOfRoutes(city)
            stats.add(it.stats.times(routes))
        }
        return stats
    }

    /** CvPlayerTrade::GetNumberOfTradeRoutesCity: the routes that start in [city] and the routes that end in it, of any civilization */
    @Readonly
    fun numberOfRoutes(city: City): Int = city.civ.gameInfo.civilizations.sumOf { owner ->
        owner.tradeRoutes.routes.count { it.origin == city.location || it.destination == city.location }
    }

    /** CvPlot::IsTradeUnitRoute: the path of a route of any civilization goes over [tile] (V:CvTradeClasses.cpp:1376-1402) */
    @Readonly
    fun isOnRoute(gameInfo: GameInfo, tile: Tile): Boolean = gameInfo.civilizations.any { tile.position in it.tradeRoutes.getRouteTiles() }

    /**
     * CvPlot::calculateImprovementYield's route part (V:CvPlot.cpp:10733-10742): the stats [improvement] on [tile] gives while a route crosses it, those of its
     * [UniqueType.ImprovementStatsOnRoute] that name the railroad from the fourth era of [observingCiv] and the road before.
     */
    @Readonly
    fun getImprovementRouteStats(tile: Tile, improvement: TileImprovement, observingCiv: Civilization, context: GameContext): Stats? {
        if (!improvement.hasUnique(UniqueType.ImprovementStatsOnRoute, GameContext.IgnoreConditionals) || !isOnRoute(observingCiv.gameInfo, tile)) return null
        val ruleset = tile.ruleset
        val route = (if (observingCiv.getEraNumber() >= RAILROAD_ERA) ruleset.railroadImprovement else ruleset.roadImprovement)?.name ?: return null
        val stats = Stats()
        improvement.forEachMatchingUnique(UniqueType.ImprovementStatsOnRoute, context) { if (it.params[1] == route) stats.add(it.stats) }
        return stats
    }

    /** The era from which the railroad's yield replaces the road's: `GetCurrentEra() >= 4`, V:CvPlot.cpp:10735 */
    private const val RAILROAD_ERA = 4

    //endregion
    //region Plunder

    /**
     * CvPlayerTrade::PlunderTradeRoute's gold (V:CvTradeClasses.cpp:5002-5013): TRADE_ROUTE_BASE_PLUNDER_GOLD x (100 + the route's domain modifier) / 100 x the game speed's
     * instant yield percent x the plunderer's era (at least 1) / 100, whole gold
     */
    @Readonly
    fun plunderGold(route: TradeRoute, plunderer: Civilization): Int {
        val constants = plunderer.gameInfo.ruleset.modOptions.constants
        val gold = constants.tradeRoutePlunderGold * (100 + domainModifier(route, constants.tradeRouteVp)) / 100
        val instantYieldPercent = (plunderer.gameInfo.speed.instantYieldModifier * 100).roundToInt()
        return gold * instantYieldPercent * max(1, plunderer.getEraNumber()) / 100
    }

    //endregion

    @Readonly
    private fun vp(civ: Civilization) = civ.gameInfo.ruleset.modOptions.constants.tradeRouteVp
}
