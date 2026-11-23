package com.unciv.logic.automation.unit

import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.SpaceshipProductionAi
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteCandidate
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat

/**
 * Vox Populi's scoring of the routes between our own cities (Release-5.4.8, CvTradeClasses.cpp): the target cities that
 * CvTradeAI::GetPrioritizedTradeRoutes picks for production and for food, and CvTradeAI::ScoreInternalTR with its wrappers
 * ScoreProductionTR and ScoreFoodTR.
 *
 * One instance serves one prioritisation pass for [civ]: the target sets, the cities' base yields and the siege tests are worked out when
 * first asked for and kept; the caller loops over every available route and takes the positive scores. [pathScore] is shared with the
 * international scorer, so the political risk of the owners is read once (AddTradePathToCache's danger, CvTradeAI::ApplyTradeRouteModifier).
 *
 * Mapping of Unciv's [TradeRouteKind]: [TradeRouteKind.Production] is TRADE_CONNECTION_PRODUCTION, [TradeRouteKind.Food] is
 * TRADE_CONNECTION_FOOD; an international route scores 0 here (ScoreInternationalTR is the other scorer).
 *
 * Production targets (GetPrioritizedTradeRoutes, PRODUCTION): the wonder city (CvCitySpecializationAI::GetWonderBuildCity,
 * [WonderProductionAi.wonderBuildCity]), every city building a world wonder (getMaxGlobalInstances() == 1 is [Building.isWonder]) or a spaceship
 * part (isProductionSpaceshipPart), and, for a civilization going for and close to a spaceship victory, the cities of
 * CvPlayer::GetCoreCitiesForSpaceshipProduction ([SpaceshipProductionAi.coreCities]). Without such a priority target (the wonder city alone
 * is none), the cities whose base production is below the average of all our cities.
 *
 * Food targets (same function, FOOD): none while the empire is unhappy (CvPlayer::IsEmpireUnhappy, [EconomicMilitaryStrategyTests.isEmpireUnhappy]);
 * else the cities whose base food is below the average.
 *
 * What the data and the needs model give ([VpTradeScoreTerms]): the x400 of a starving destination (CvCity::GetUnhappinessFromFamine, with the needs model) in
 * place of the x20 of a food focus, the doubling of a connection to the capital of a city that is isolated (GetUnhappinessFromIsolation), what the buildings of
 * both ends give when the route ends per turn of its length (GetYieldFromInternalTREnd), and what our cities on the path give while a route passes (iScoreFromPassingTR).
 *
 * None, because VP's input is not in the engine (each of them is a zero or unchanged term here):
 *  - the Utopia Project (PROJECT_UTOPIA_PROJECT: target city and the x150 of ScoreInternalTR), and corporations
 *    (GetFoundedCorporation / IsTradeRoutesInvulnerable; O1 of the plan, no corporations): routes are never invulnerable, so a city under siege at either end scores 0 and the
 *    inner checks for the invulnerable case (isInDangerOfFalling, IsInEnemyDominatedZone) cannot be reached;
 *  - the famine clause of the food targets (GetPrioritizedTradeRoutes);
 *  - wonder resource routes (TRADE_CONNECTION_WONDER_RESOURCE, MOD_TRADE_WONDER_RESOURCE_ROUTES, ScoreWonderTR) and internal gold routes
 *    (TRADE_CONNECTION_GOLD_INTERNAL, ScoreGoldInternalTR): not ported (O3 of the plan: inactive in Vox Populi);
 *  - trait terms: IsTradeRouteProductionSiphon, GetGoldInternalTrade, IsConquestOfTheWorld (x5 in a golden age), the path's iScoreFromTerrain (no terrain-yield trait).
 *
 * Mapping of the inputs that exist in a different form:
 *  - getBaseYieldRateTimes100(YIELD_FOOD / YIELD_PRODUCTION): the city's base stats before percentage modifiers
 *    ([com.unciv.logic.city.CityStats.baseStatTree]; trade route yields are added after the modifiers in Unciv, as in the original), x100;
 *  - CITY_AI_FOCUS_TYPE_FOOD / _PRODUCTION: [CityFocus.FoodFocus] / [CityFocus.ProductionFocus] (the mixed focuses are not those types);
 *  - CalculateBaseNetGold: [com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.statsForNextTurn]'s gold, whole;
 *  - IsAtWar and GetNumberCivsAtWarWith(true): the civilizations we are at war with, barbarians not counted, city-states counted;
 *  - CvCity::isUnderSiege: [CityDangerAi.isUnderSiege]; GetCachedTradePathInfo's iPathLength: the number of tiles of the route's path;
 *  - CheckTradeConnectionWasPlundered: [com.unciv.logic.civilization.managers.TradeRouteManager.wasPlunderedRecently];
 *  - GetTradeRouteTurns (internal yields at the end of a route): the turns of the route a trade unit would start ([com.unciv.models.TradeRoute.getTurnsLeft] of the preview).
 */
class VpInternalTradeScore(private val civ: Civilization, private val pathScore: VpTradePathScore) {

    private val manager = civ.tradeRoutes
    private val wonderCity: City? by lazy { WonderProductionAi.wonderBuildCity(civ) }

    private val baseFood = HashMap<City, Int>()
    private val baseProduction = HashMap<City, Int>()
    private val underSiege = HashMap<City, Boolean>()
    private val productionMultipliers = HashMap<City, Int>()

    /** IsGoingForSpaceshipVictory && IsCloseToSpaceshipVictory, the condition for the cities of GetCoreCitiesForSpaceshipProduction */
    private val coreSpaceshipCities: Set<City> by lazy {
        if (civ.ai.isGoingForSpaceshipVictory() && VictoryProgressAi.isCloseToSpaceshipVictory(civ, civ.gameInfo.ruleset.ai))
            SpaceshipProductionAi.coreCities(civ).toSet()
        else emptySet()
    }

    /** The war-time multiplier of ScoreInternalTR: 1 at peace, else the number of enemies (at least 2) */
    private val warMultiplier: Int by lazy {
        val enemies = civ.getCivsAtWarWith().count { !it.isBarbarian }
        if (enemies > 0) maxOf(2, enemies) else 1
    }

    private val netGold: Int by lazy { civ.stats.statsForNextTurn.gold.toInt() }

    /** The cities production routes are wanted in, see the class comment (GetPrioritizedTradeRoutes, PRODUCTION) */
    val productionTargets: Set<City> by lazy { findProductionTargets() }

    /** The cities food routes are wanted in, see the class comment (GetPrioritizedTradeRoutes, FOOD) */
    val foodTargets: Set<City> by lazy { findFoodTargets() }

    private fun baseTimes100(city: City, stat: Stat): Int =
        (city.cityStats.baseStatTree.totalStats[stat] * 100).toInt()

    private fun baseFood100(city: City) = baseFood.getOrPut(city) { baseTimes100(city, Stat.Food) }
    private fun baseProduction100(city: City) = baseProduction.getOrPut(city) { baseTimes100(city, Stat.Production) }

    private fun isUnderSiege(city: City) = underSiege.getOrPut(city) { CityDangerAi.isUnderSiege(city) }

    /** CvCity::isProductionSpaceshipPart */
    private fun isProducingSpaceshipPart(city: City): Boolean =
        (city.cityConstructions.getCurrentConstruction() as? INonPerpetualConstruction)?.hasUnique(UniqueType.SpaceshipPart) == true

    /** CvCity::getProductionBuilding is a world wonder (building class with one global instance) */
    private fun isBuildingWorldWonder(city: City): Boolean =
        (city.cityConstructions.getCurrentConstruction() as? Building)?.isWonder == true

    private fun findProductionTargets(): Set<City> {
        val targets = LinkedHashSet<City>()
        wonderCity?.let { targets.add(it) }
        var foundPriorityTarget = false
        for (city in civ.cities) {
            if (isBuildingWorldWonder(city) || isProducingSpaceshipPart(city) || city in coreSpaceshipCities) {
                targets.add(city)
                foundPriorityTarget = true
            }
        }
        if (!foundPriorityTarget && civ.cities.isNotEmpty()) {
            val average = civ.cities.sumOf { baseProduction100(it) } / civ.cities.size
            for (city in civ.cities) if (baseProduction100(city) < average) targets.add(city)
        }
        return targets
    }

    private fun findFoodTargets(): Set<City> {
        if (EconomicMilitaryStrategyTests.isEmpireUnhappy(civ) || civ.cities.isEmpty()) return emptySet()
        val average = civ.cities.sumOf { baseFood100(it) } / civ.cities.size
        return civ.cities.filterTo(LinkedHashSet()) { baseFood100(it) < average }
    }

    /**
     * ScoreProductionTR and ScoreFoodTR (through ScoreInternalTR) for one connection: 0 if it is no food or production route, its destination
     * is no target of that kind, a city at either end is under siege, or the connection was plundered recently. The result may be
     * negative or 0 for an unwanted route; the caller keeps the positive scores only, as GetPrioritizedTradeRoutes does.
     */
    fun score(candidate: TradeRouteCandidate): Int {
        val destination = candidate.destination
        val kind = destination.kind
        if (kind != TradeRouteKind.Food && kind != TradeRouteKind.Production) return 0
        val origin = candidate.origin
        val destinationCity = destination.city
        if (origin.civ != civ || destinationCity.civ != civ) return 0

        // ScoreInternalTR: not going to a target from our list
        val targets = if (kind == TradeRouteKind.Food) foodTargets else productionTargets
        if (destinationCity !in targets) return 0

        // War near the origin or the destination: no corporation here, so no exception
        if (isUnderSiege(origin) || isUnderSiege(destinationCity)) return 0
        if (manager.wasPlunderedRecently(origin, destinationCity)) return 0

        val path = destination.path
        val distance = path.size / 5

        var score: Long = if (kind == TradeRouteKind.Food) foodScore(origin, destinationCity) else productionScore(origin, destinationCity)

        // Direct connection to the capital through a trade route
        if (VpTradeScoreTerms.isolation(destinationCity) > 0 && origin.isCapital()) score *= 2
        if (VpTradeScoreTerms.isolation(origin) > 0 && destinationCity.isCapital()) score *= 2

        if (netGold <= 0) score += netGold * 10L
        score -= pathScore.partnerYield * 10L

        // Instant yields when the trade route ends?
        score += routeEndScore(candidate, origin, destinationCity)

        // Internal routes are preferred at war
        score *= warMultiplier
        score += VpTradeScoreTerms.passingRouteScore(civ, path)

        score /= maxOf(distance, 1)
        return pathScore.apply(score, path).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     *  TRADE_CONNECTION_FOOD of ScoreInternalTR: the food surplus of the origin over the destination, much more for a destination that focuses on food (x20) or
     *  starves (x400, with the needs model)
     */
    private fun foodScore(origin: City, destination: City): Long {
        var score = ((baseFood100(origin) + origin.population.population) - (baseFood100(destination) + destination.population.population)) / 100L
        val famine = VpTradeScoreTerms.famine(destination) > 0
        if (famine) score *= 400
        else if (destination.getCityFocus() == CityFocus.FoodFocus) score *= 20
        return score
    }

    /**
     *  What the buildings of the two ends give when the route ends (`GetYieldFromInternalTREnd` of both cities, V:CvTradeClasses.cpp:7558-7562) per turn of the route,
     *  all yields added up. The route's length is that of the route a trade unit would start.
     */
    private fun routeEndScore(candidate: TradeRouteCandidate, origin: City, destination: City): Long {
        val yields = VpTradeScoreTerms.routeEndYields(origin, false).toMap()
        val destinationYields = VpTradeScoreTerms.routeEndYields(destination, false).toMap()
        if (yields.isEmpty() && destinationYields.isEmpty()) return 0
        val unitType = manager.getTradeUnitType(candidate.isSea) ?: return 0
        val turns = maxOf(1, manager.preview(unitType, origin, candidate.destination)?.getTurnsLeft() ?: return 0)
        return (yields.keys + destinationYields.keys).sumOf { ((yields[it] ?: 0) + (destinationYields[it] ?: 0)) / turns }.toLong()
    }

    /**
     * TRADE_CONNECTION_PRODUCTION of ScoreInternalTR: the production surplus of the origin over the destination, x100 for a spaceship part there,
     * x50 for a core spaceship city, else x20 for a world wonder, x2 for the wonder city; x20 more for any of these or a production focus
     */
    private fun productionScore(origin: City, destination: City): Long {
        val difference = (baseProduction100(origin) - baseProduction100(destination)) / 100L
        return difference * productionMultiplier(destination)
    }

    private fun productionMultiplier(destination: City): Int = productionMultipliers.getOrPut(destination) {
        var multiplier = 1
        var isTarget = false
        if (isProducingSpaceshipPart(destination)) {
            multiplier *= 100
            isTarget = true
        }
        if (!isTarget && destination in coreSpaceshipCities) {
            multiplier *= 50
            isTarget = true
        }
        if (!isTarget && isBuildingWorldWonder(destination)) {
            multiplier *= 20
            isTarget = true
        }
        if (!isTarget && destination == wonderCity) {
            multiplier *= 2
            isTarget = true
        }
        if (isTarget || destination.getCityFocus() == CityFocus.ProductionFocus) multiplier *= 20
        multiplier
    }
}
