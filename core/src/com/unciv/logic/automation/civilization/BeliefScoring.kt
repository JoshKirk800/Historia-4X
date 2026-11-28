package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.MultiFilter
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min

/** The sum of the original's yields: every [Stat] but Happiness */
private fun Stats.yieldSum(): Int = (this.sum() - this[Stat.Happiness]).toInt()

/**
 *  The fields of the original's CvBeliefEntry that CvReligionAI's belief scoring reads, derived from a belief's uniques for one civilization
 *  (the building of a building class is the civilization's own, as getCivilizationBuildings).
 *
 *  Unciv expresses each belief effect as a unique; [BeliefAiProfile.of] maps every unique type that carries one of the original's fields
 *  (the BNW beliefs use all of them). "Yields" are the original's yield types: every [Stat] but Happiness, which has its own fields.
 */
class BeliefAiProfile private constructor() {
    // CvBeliefEntry::GetMinPopulation / GetMinFollowers - from the `<in cities with at least [n] [Population / Followers ...]>` conditionals
    var minPopulation = 0
    var minFollowers = 0

    // ScoreBeliefAtPlot: terrain, feature, natural wonder and resource yield changes (tile filter -> yields),
    // "tiles without" variants, and improvement yield changes
    val tileYields = ArrayList<Pair<String, Stats>>()
    val tileWithoutYields = ArrayList<Triple<String, String, Stats>>()
    val improvementYields = ArrayList<Pair<List<TileImprovement>, Stats>>()

    // ScoreBeliefAtCity
    var cityGrowthModifier = 0
    var requiresPeace = false
    var plotCultureCostModifier = 0
    var cityRangeStrikeModifier = 0
    var friendlyHealChange = 0
    var wonderProductionModifier = 0
    /** Era index + 1 of the last era whose wonders the modifier covers (CvBeliefEntry::GetObsoleteEra); 0 = never obsolete */
    var obsoleteEra = 0
    var riverHappiness = 0
    var happinessPerCity = 0
    /** Building class (as the civilization's building) -> happiness (Belief_BuildingClassHappiness) */
    val buildingHappiness = LinkedHashMap<Building, Int>()
    val cityYield = Stats()
    val tradeRouteYield = Stats()
    /** All yields a belief gives specialists, named or of any kind: [namedSpecialistYields] and [anySpecialistYield] together */
    val specialistYield = Stats()
    /** Specialist name -> yields of Belief_SpecialistYieldChanges */
    val namedSpecialistYields = LinkedHashMap<String, Stats>()
    /** Belief_YieldChangeAnySpecialist: the yields a city with specialists gets (the unique's `<in cities with at least [1] [Specialists]>`) */
    val anySpecialistYield = Stats()
    /** Building class (as the civilization's building) -> yields (Belief_BuildingClassYieldChanges) */
    val buildingYields = LinkedHashMap<Building, Stats>()
    val worldWonderYield = Stats()
    val maxYieldModifierPerFollower = Stats()

    // ScoreBeliefForPlayer
    var faithFromKills = 0
    var maxDistance = 0
    var playerHappiness = 0
    var playerCultureModifier = 0
    /** Building classes made purchasable with Faith (Belief_BuildingClassFaithPurchase), as the civilization's buildings */
    val enabledBuildings = LinkedHashSet<Building>()
    /** Number of eras in which military units can be bought with Faith (Belief_EraFaithUnitPurchase) */
    var faithUnitPurchaseEras = 0
    var happinessPerFollowingCity = 0f
    var happinessPerXPeacefulForeignFollowers = 0
    var sciencePerOtherReligionFollower = 0
    var goldPerFollowingCity = 0f
    var goldPerXFollowers = 0
    var goldWhenCityAdopts = 0
    var cityStateMinimumInfluence = 0
    val yieldPerForeignCity = Stats()
    /** Followers per point of the yield (Belief_YieldChangePerXForeignFollowers), 0 = none */
    val yieldPerXForeignFollowers = Stats()
    var greatPersonExpendedFaith = 0
    var friendlyCityStateSpreadModifier = 0
    var combatModifierFriendlyCities = 0
    var combatModifierEnemyCities = 0
    var spreadDistanceModifier = 0
    var spreadStrengthModifier = 0
    var missionaryStrengthModifier = 0
    var missionaryCostModifier = 0
    var prophetStrengthModifier = 0
    var prophetCostModifier = 0
    var faithPurchaseAllGreatPeople = false
    var convertsBarbarians = false
    var cityStateInfluenceModifier = 0
    var inquisitorPressureRetention = 0
    var spyPressure = 0
    var faithBuildingTourism = 0

    companion object {
        private val worldWonderFilters = setOf("Wonder", "Wonders", "World Wonder", "World")

        private fun Stats.addYields(other: Stats, divisor: Float = 1f) {
            for ((stat, value) in other) if (stat != Stat.Happiness) add(stat, value / divisor)
        }

        private fun isForeignFilter(cityFilter: String) = cityFilter.contains("foreign", ignoreCase = true)

        fun of(belief: Belief, civ: Civilization): BeliefAiProfile {
            val profile = BeliefAiProfile()
            val builder = Builder(civ, profile)
            for (unique in belief.uniqueObjects) builder.add(unique)
            return profile
        }
    }

    private class Builder(val civ: Civilization, val profile: BeliefAiProfile) {
        val ruleset: Ruleset = civ.gameInfo.ruleset
        val state: GameContext = civ.state
        val prophet = civ.religionManager.getGreatProphetEquivalent()
        val missionary = ReligionAutomation.missionaryUnit(civ)

        /** The building classes matched by [filter], as this civilization's buildings */
        fun buildingClasses(filter: String): List<Building> =
            ruleset.buildings.values.asSequence()
                .filter { it.matchesFilter(filter, state) }
                .map { ruleset.buildings[it.replaces] ?: it }
                .distinct()
                .map { civ.getEquivalentBuilding(it) }
                .toList()

        @Readonly
        fun isEraComponent(filter: String) =
            filter == "any era" || filter.startsWith("pre-[") || filter.startsWith("post-[") || ruleset.eras.containsKey(filter)

        /** Belief_EraFaithUnitPurchase: the eras whose military units [unitFilter] lets buy with Faith */
        fun addFaithUnitPurchase(unitFilter: String, eras: MutableSet<String>) {
            val units = ruleset.units.values.filter { unit ->
                MultiFilter.multiFilter(unitFilter, { isEraComponent(it) || unit.matchesFilter(it, state, multiFilter = false) })
            }
            if (units.any { it.isGreatPerson }) profile.faithPurchaseAllGreatPeople = true
            if (units.none { it.isMilitary }) return
            for (era in ruleset.eras.values)
                if (MultiFilter.multiFilter(unitFilter, { !isEraComponent(it) || era.matchesSingleFilter(it, state) }))
                    eras.add(era.name)
        }

        val purchaseEras = HashSet<String>()

        fun add(unique: Unique) {
            for (modifier in unique.getModifiers(UniqueType.ConditionalPopulationFilter)) {
                val amount = modifier.params[0].toInt()
                val filter = modifier.params[1]
                when {
                    filter == "Population" -> profile.minPopulation = max(profile.minPopulation, amount)
                    filter.contains("Followers") -> profile.minFollowers = max(profile.minFollowers, amount)
                }
            }
            val bySpecialist = unique.getModifiers(UniqueType.ConditionalPopulationFilter)
                .any { it.params[1] == "Specialists" || ruleset.specialists.containsKey(it.params[1]) }

            when (unique.type) {
                UniqueType.StatsFromObject -> addStatsFromObject(unique.params[1], unique.stats)
                UniqueType.StatsFromTilesWithout ->
                    profile.tileWithoutYields.add(Triple(unique.params[1], unique.params[2], unique.stats))
                UniqueType.StatsFromNamedSpecialist -> addSpecialistYield(unique.params[1], unique.stats)
                UniqueType.StatsPerCity ->
                    if (bySpecialist) {
                        profile.specialistYield.addYields(unique.stats)
                        profile.anySpecialistYield.addYields(unique.stats)
                    } else {
                        profile.happinessPerCity += unique.stats.happiness.toInt()
                        profile.cityYield.addYields(unique.stats)
                    }
                UniqueType.StatsFromCitiesOnSpecificTiles -> {
                    profile.riverHappiness += unique.stats.happiness.toInt()
                    profile.cityYield.addYields(unique.stats)
                }
                UniqueType.Stats -> profile.playerHappiness += unique.stats.happiness.toInt()
                UniqueType.StatPercentBonus ->
                    if (unique.params[1] == Stat.Culture.name) profile.playerCultureModifier += unique.params[0].toInt()
                UniqueType.StatsFromTradeRoute -> profile.tradeRouteYield.addYields(unique.stats)
                UniqueType.StatPercentFromReligionFollowers -> {
                    val stat = Stat.safeValueOf(unique.params[1]) ?: return
                    profile.maxYieldModifierPerFollower[stat] = unique.params[2].toFloat()
                }
                UniqueType.GrowthPercentBonus -> {
                    profile.cityGrowthModifier += unique.params[0].toInt()
                    if (unique.getModifiers(UniqueType.ConditionalNotWar).isNotEmpty()) profile.requiresPeace = true
                }
                UniqueType.BorderGrowthPercentage -> profile.plotCultureCostModifier += unique.params[0].toInt()
                UniqueType.StrengthForCities -> profile.cityRangeStrikeModifier += unique.params[0].toInt()
                UniqueType.CityHealingUnits -> profile.friendlyHealChange += unique.params[1].toInt()
                UniqueType.PercentProductionWonders -> {
                    profile.wonderProductionModifier = max(profile.wonderProductionModifier, unique.params[0].toInt())
                    val era = ruleset.eras[unique.params[1]]
                    if (era != null) profile.obsoleteEra = max(profile.obsoleteEra, era.eraNumber + 1)
                }
                UniqueType.KillUnitPlunderNearCity -> {
                    profile.faithFromKills += unique.params[0].toInt()
                    profile.maxDistance = 4 // fixed in the unique's text
                }
                UniqueType.BuyBuildingsWithStat, UniqueType.BuyBuildingsByProductionCost ->
                    if (unique.params[1] == Stat.Faith.name) profile.enabledBuildings.addAll(buildingClasses(unique.params[0]))
                UniqueType.BuyBuildingsForAmountStat, UniqueType.BuyBuildingsIncreasingCost ->
                    if (unique.params[2] == Stat.Faith.name) profile.enabledBuildings.addAll(buildingClasses(unique.params[0]))
                UniqueType.BuyUnitsWithStat, UniqueType.BuyUnitsByProductionCost ->
                    if (unique.params[1] == Stat.Faith.name) addFaithUnitPurchase(unique.params[0], purchaseEras)
                UniqueType.BuyUnitsForAmountStat, UniqueType.BuyUnitsIncreasingCost ->
                    if (unique.params[2] == Stat.Faith.name) addFaithUnitPurchase(unique.params[0], purchaseEras)
                UniqueType.RestingPointOfCityStatesFollowingReligionChange, UniqueType.CityStateRestingPoint ->
                    profile.cityStateMinimumInfluence += unique.params[0].toInt()
                UniqueType.StatsFromGlobalCitiesFollowingReligion -> addPerFollowingCity(unique.stats, 1f, foreign = false)
                UniqueType.StatsFromGlobalCitiesFollowingReligionPer ->
                    addPerFollowingCity(unique.stats, unique.params[1].toFloat(), isForeignFilter(unique.params[2]))
                UniqueType.StatsFromGlobalFollowers -> addPerFollowers(unique.stats, unique.params[1].toInt(), isForeignFilter(unique.params[2]))
                UniqueType.StatsWhenSpreading -> profile.sciencePerOtherReligionFollower += unique.params[0].toInt()
                UniqueType.StatsWhenAdoptingReligion -> profile.goldWhenCityAdopts += unique.stats.gold.toInt()
                UniqueType.OneTimeGainStat ->
                    if (unique.params[1] == Stat.Faith.name && unique.getModifiers(UniqueType.TriggerUponExpendingUnit).isNotEmpty())
                        profile.greatPersonExpendedFaith += unique.params[0].toInt()
                UniqueType.NaturalReligionSpreadStrength ->
                    if (unique.params[1].contains("City-State"))
                        profile.friendlyCityStateSpreadModifier = max(profile.friendlyCityStateSpreadModifier, unique.params[0].toInt())
                    else profile.spreadStrengthModifier = max(profile.spreadStrengthModifier, unique.params[0].toInt())
                UniqueType.Strength -> {
                    val tiles = unique.getModifiers(UniqueType.ConditionalFightingInTiles).firstOrNull()?.params?.get(0) ?: return
                    when {
                        tiles.contains("Enemy") -> profile.combatModifierEnemyCities += unique.params[0].toInt()
                        tiles.contains("Friendly") -> profile.combatModifierFriendlyCities += unique.params[0].toInt()
                    }
                }
                UniqueType.ReligionSpreadDistance ->
                    profile.spreadDistanceModifier += unique.params[0].toInt() * 100 / civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_ADJACENT_CITY_DISTANCE)
                UniqueType.SpreadReligionStrength -> {
                    val unitFilter = unique.getModifiers(UniqueType.ConditionalOurUnit).firstOrNull()?.params?.get(0)
                    if (unitFilter != null && prophet != null && prophet.matchesFilter(unitFilter, state))
                        profile.prophetStrengthModifier += unique.params[0].toInt()
                    else profile.missionaryStrengthModifier += unique.params[0].toInt()
                }
                UniqueType.FaithCostOfGreatProphetChange -> profile.prophetCostModifier += unique.params[0].toInt()
                UniqueType.BuyUnitsDiscount -> if (unique.params[0] == Stat.Faith.name) {
                    if (missionary != null && missionary.matchesFilter(unique.params[1], state))
                        profile.missionaryCostModifier += unique.params[2].toInt()
                    else if (prophet != null && prophet.matchesFilter(unique.params[1], state))
                        profile.prophetCostModifier += unique.params[2].toInt()
                }
                UniqueType.ConvertAdjacentBarbarians -> profile.convertsBarbarians = true
                UniqueType.CityStateGoldGiftsProvideMoreInfluence -> profile.cityStateInfluenceModifier += unique.params[0].toInt()
                UniqueType.ReligionPressureRetention -> profile.inquisitorPressureRetention += unique.params[0].toInt()
                UniqueType.SpyReligionPressure -> profile.spyPressure += unique.params[0].toInt()
                UniqueType.FlatTourismFromFaithBuildings -> profile.faithBuildingTourism += unique.params[0].toInt()
                else -> {}
            }
            profile.faithUnitPurchaseEras = purchaseEras.size
        }

        fun addSpecialistYield(specialist: String, stats: Stats) {
            profile.specialistYield.addYields(stats)
            profile.namedSpecialistYields.getOrPut(specialist) { Stats() }.addYields(stats)
        }

        /** "[stats] from every [object]": world wonders, specialists, improvements, terrain/feature/resource, buildings - in that order */
        fun addStatsFromObject(filter: String, stats: Stats) {
            when {
                filter in worldWonderFilters -> profile.worldWonderYield.addYields(stats)
                ruleset.specialists.containsKey(filter) -> addSpecialistYield(filter, stats)
                ruleset.terrains.containsKey(filter) || ruleset.tileResources.containsKey(filter) ->
                    profile.tileYields.add(filter to stats)
                else -> {
                    val improvements = ruleset.tileImprovements.values.filter { it.matchesFilter(filter, state) }
                    if (improvements.isNotEmpty()) {
                        profile.improvementYields.add(improvements to stats)
                        return
                    }
                    val buildings = buildingClasses(filter)
                    if (buildings.isEmpty()) {
                        profile.tileYields.add(filter to stats)
                        return
                    }
                    for (building in buildings) {
                        if (stats.happiness != 0f)
                            profile.buildingHappiness[building] = (profile.buildingHappiness[building] ?: 0) + stats.happiness.toInt()
                        if (stats.yieldSum() != 0) profile.buildingYields.getOrPut(building) { Stats() }.addYields(stats)
                    }
                }
            }
        }

        /** HappinessPerFollowingCity, GoldPerFollowingCity, Belief_YieldChangePerForeignCity */
        fun addPerFollowingCity(stats: Stats, cities: Float, foreign: Boolean) {
            profile.happinessPerFollowingCity += stats.happiness / cities
            if (foreign) profile.yieldPerForeignCity.addYields(stats, cities)
            else {
                profile.goldPerFollowingCity += stats.gold / cities
                for ((stat, value) in stats)
                    if (stat != Stat.Happiness && stat != Stat.Gold) profile.yieldPerForeignCity.add(stat, value / cities)
            }
        }

        /** HappinessPerXPeacefulForeignFollowers, GoldPerXFollowers, Belief_YieldChangePerXForeignFollowers: followers per point */
        fun addPerFollowers(stats: Stats, followers: Int, foreign: Boolean) {
            for ((stat, value) in stats) {
                if (value <= 0f) continue
                val perPoint = (followers / value).toInt()
                when {
                    stat == Stat.Happiness -> profile.happinessPerXPeacefulForeignFollowers = perPoint
                    stat == Stat.Gold && !foreign -> profile.goldPerXFollowers = perPoint
                    else -> profile.yieldPerXForeignFollowers[stat] = perPoint.toFloat()
                }
            }
        }
    }
}

/**
 *  The data CvReligionAI::ScoreBelief computes over the map and the player once per decision: the plots it scores (revealed, unowned or
 *  ours, within 3 of one of our cities) with their multipliers, and the player-level inputs. With Vox Populi's AI
 *  ([ReligionAutomation.religionAiLikeVp]) the plots and their weights are [plotWeightsLikeVp]'s, the unowned ones only when
 *  [considerExpansion] (choosing a pantheon); [considerFutureTech] is ScoreBelief's bConsiderFutureTech, false only for a foreign religion.
 */
class BeliefScoringContext(val civ: Civilization, considerExpansion: Boolean = false, val considerFutureTech: Boolean = true) {
    private val withoutFeatureCache = HashMap<Tile, Tile>()
    val plots: List<Tile>
    val plotMultipliers: IntArray
    /** CvReligionAI::ScoreBeliefAtCity: 3 if Offense×2 + Happiness − Defense > 15, 1 if < 6, else 2 */
    val happinessMultiplier: Int
    /** CvGame::getCurrentEra: the average era of the living players */
    val gameEra: Int
    /** CvReligionAI::ScoreBeliefForPlayer: 100 × enhanced religions / the most religions the game can have */
    val religionsEnhancedPercent: Int

    init {
        if (ReligionAutomation.religionAiLikeVp(civ)) {
            val weights = plotWeightsLikeVp(civ, considerExpansion)
            plots = weights.map { it.first }
            plotMultipliers = IntArray(weights.size) { weights[it].second }
        } else {
            val ai = civ.gameInfo.ruleset.ai
            val worked = ai.define(AiDefine.RELIGION_BELIEF_SCORE_WORKED_PLOT_MULTIPLIER)
            val owned = ai.define(AiDefine.RELIGION_BELIEF_SCORE_OWNED_PLOT_MULTIPLIER)
            val unowned = ai.define(AiDefine.RELIGION_BELIEF_SCORE_UNOWNED_PLOT_MULTIPLIER)
            val tiles = LinkedHashSet<Tile>()
            for (city in civ.cities)
                for (tile in city.getCenterTile().getTilesInDistance(3)) {
                    val owner = tile.getOwner()
                    if ((owner == null || owner == civ) && tile.isExplored(civ)) tiles.add(tile)
                }
            plots = tiles.toList()
            plotMultipliers = IntArray(plots.size) { i ->
                val tile = plots[i]
                when {
                    tile.isWorked() -> worked
                    tile.getOwner() != null -> owned
                    else -> unowned
                }
            }
        }

        val needFactor = civ.ai.personality(Flavor.Offense) * 2 + civ.ai.personality(Flavor.Happiness) - civ.ai.personality(Flavor.Defense)
        happinessMultiplier = when {
            needFactor > 15 -> 3
            needFactor < 6 -> 1
            else -> 2
        }

        val living = civ.gameInfo.civilizations.filter { it.isAlive() && !it.isSpectator }
        gameEra = if (living.isEmpty()) 0 else living.sumOf { it.getAiEraNumber() } / living.size

        val enhanced = civ.gameInfo.civilizations.count { it.religionManager.religionState == ReligionState.EnhancedReligion }
        religionsEnhancedPercent = 100 * enhanced / max(1, civ.religionManager.maxNumberOfReligions())
    }

    /** [tile] with its last feature removed, cached (as PlotBuildScorer.withoutFeature) */
    fun withoutFeature(tile: Tile): Tile = withoutFeatureCache.getOrPut(tile) {
        val copy = tile.clone(addUnits = false)
        copy.setTerrainTransients()
        copy.terrainFeatures.lastOrNull()?.let { copy.removeTerrainFeature(it) }
        copy
    }

    private companion object {
        /**
         *  CvReligionAI::CalculatePlotWeightsForBeliefSelection (V:CvReligionClasses.cpp:7587-7804): every plot we have revealed, weighted
         *  - our city 10, another's 0
         *  - a mountain within the work range of our cities: 10 for each such city
         *  - ours: 10 if worked, else 8 within the work range of the city it belongs to
         *  - only when [considerExpansion], unowned within the exploration range of the capital or 3 of one of our cities: in the work range
         *    of our nearest city 6 next to an owned plot, else 3; outside it none if a city of a known major civilization near us is nearer,
         *    else 2 within half the exploration range beyond the capital's work range, else 1, × 4 on a mountain
         */
        fun plotWeightsLikeVp(civ: Civilization, considerExpansion: Boolean): List<Pair<Tile, Int>> {
            val capital = civ.getCapital() ?: return emptyList()
            val capitalTile = capital.getCenterTile()
            val explorationRange = if (considerExpansion) explorationRange(civ) else 0
            val workRange = capital.getWorkRange()
            val knownCitiesWithinReach = if (!considerExpansion) emptyList() else civ.gameInfo.civilizations
                .filter { it != civ && it.isMajorCiv && civ.knows(it) && hasFoundTerritory(civ, it) }
                .flatMap { it.cities }
                .filter { it.getCenterTile().aerialDistanceTo(capitalTile) < 2 * explorationRange }
            val weights = ArrayList<Pair<Tile, Int>>()
            for (tile in civ.gameInfo.tileMap.values) {
                if (!tile.isExplored(civ)) continue
                val owner = tile.getOwner()
                var weight = 0
                if (tile.isCityCenter()) weight = if (owner == civ) 10 else 0
                else {
                    val mountain = tile.baseTerrain == Constants.mountain
                    if (mountain) weight = 10 * civ.cities.count { it.getCenterTile().aerialDistanceTo(tile) <= it.getWorkRange() }
                    if (weight == 0 && owner == civ) {
                        val city = tile.getCity()
                        weight = when {
                            tile.isWorked() -> 10
                            city != null && city.getCenterTile().aerialDistanceTo(tile) <= city.getWorkRange() -> 8
                            else -> 0
                        }
                    } else if (weight == 0 && considerExpansion && owner == null) {
                        val distanceToCapital = tile.aerialDistanceTo(capitalTile)
                        if (distanceToCapital <= explorationRange || closestCity(civ, tile, 3) != null) {
                            val closest = closestCity(civ, tile, explorationRange)!!
                            val ourDistance = closest.getCenterTile().aerialDistanceTo(tile)
                            if (ourDistance <= closest.getWorkRange())
                                weight = if (tile.neighbors.any { it.getOwner() != null }) 6 else 3
                            else if (knownCitiesWithinReach.none { it.getCenterTile().aerialDistanceTo(tile) < ourDistance }) {
                                weight = if (distanceToCapital - workRange <= (explorationRange - workRange) / 2) 2 else 1
                                if (mountain) weight *= 4
                            }
                        }
                    }
                }
                if (weight > 0) weights.add(tile to weight)
            }
            return weights
        }

        /** CvPlayer::GetClosestCity without the landmass test: our city nearest to [tile] within [radius], the first of equals */
        private fun closestCity(civ: Civilization, tile: Tile, radius: Int): City? =
            civ.cities.filter { it.getCenterTile().aerialDistanceTo(tile) <= radius }.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

        /** CvTeam::IsHasFoundPlayersTerritory: we have revealed a plot [other] owns */
        private fun hasFoundTerritory(civ: Civilization, other: Civilization): Boolean =
            other.cities.any { city -> city.getTiles().any { it.isExplored(civ) } }

        /** The exploration range of CvReligionAI::CalculatePlotWeightsForBeliefSelection: 9, -3 with Tradition, else +1 with Honor, else +3
         *  with Liberty, else +2 for an expansionist and -2 for a smaller civilization (CvPlayerTraits::IsExpansionist, IsSmaller) */
        fun explorationRange(civ: Civilization): Int = 9 + when {
            civ.policies.isAdopted("Tradition") -> -3
            civ.policies.isAdopted("Honor") -> 1
            civ.policies.isAdopted("Liberty") -> 3
            else -> (if (civ.ai.hasTraitCategory(TraitCategory.Expansionist)) 2 else 0) -
                (if (civ.ai.hasTraitCategory(TraitCategory.Smaller)) 2 else 0)
        }
    }
}

/** CvReligionAI's belief scoring (CvReligionClasses.cpp) */
object BeliefScoring {

    /** CvReligionAI::ScoreBelief: 5, plus the plot scores × worked/owned/unowned multiplier, plus the city scores × the city multiplier,
     *  plus the player score; halved for a pantheon belief */
    fun scoreBelief(belief: Belief, context: BeliefScoringContext): Int {
        val civ = context.civ
        val profile = BeliefAiProfile.of(belief, civ)
        var score = 5
        val likeVp = ReligionAutomation.religionAiLikeVp(civ)
        for (i in context.plots.indices) {
            val tile = context.plots[i]
            val atPlot = if (likeVp) scoreBeliefAtPlotLikeVp(profile, tile, context) else scoreBeliefAtPlot(profile, tile, civ)
            score += atPlot * context.plotMultipliers[i]
        }
        val cityMultiplier = civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_BELIEF_SCORE_CITY_MULTIPLIER)
        for (city in civ.cities) score += scoreBeliefAtCity(profile, city, context) * cityMultiplier
        score += scoreBeliefForPlayer(profile, context)
        if (belief.type == BeliefType.Pantheon) score /= 2
        return score
    }

    /** CvReligionAI::ScoreBeliefAtPlot: the belief's yield changes on this plot; improvement changes count twice, and only on a resource plot
     *  that could take the improvement */
    fun scoreBeliefAtPlot(profile: BeliefAiProfile, tile: Tile, civ: Civilization): Int {
        var value = 0
        for ((filter, yields) in profile.tileYields)
            if (tile.matchesFilter(filter, civ)) value += yields.yieldSum()
        for ((filter, without, yields) in profile.tileWithoutYields)
            if (tile.matchesFilter(filter, civ) && !tile.matchesFilter(without, civ)) value += yields.yieldSum()
        if (tile.resource != null)
            for ((improvements, yields) in profile.improvementYields)
                for (improvement in improvements)
                    if (tile.improvementFunctions.canImprovementBeBuiltHere(improvement)) value += yields.yieldSum() * 2
        return value
    }

    /** Each yield VP's ScoreBeliefAtPlot looks at (through Faith) with the flavor and factor of CvReligionAI::ScoreYieldForReligionTimes100
     *  (V:CvReligionClasses.cpp:8350-8378) */
    private val vpPlotYieldFlavors = listOf(
        Stat.Food to (Flavor.Growth to 50), Stat.Production to (Flavor.Production to 50), Stat.Gold to (Flavor.Gold to 50),
        Stat.Science to (Flavor.Science to 80), Stat.Culture to (Flavor.Culture to 100), Stat.Faith to (Flavor.Religion to 110)
    )

    /** CvReligionAI::ScoreBeliefAtPlot (V:CvReligionClasses.cpp:8381-8399): each yield's [validPlotYieldTimes100] above 0, × the
     *  civilization's personality flavor for it × its factor / 10000 */
    fun scoreBeliefAtPlotLikeVp(profile: BeliefAiProfile, tile: Tile, context: BeliefScoringContext): Int {
        val plot = VpPlot(tile, context)
        var total = 0
        for ((stat, flavorAndFactor) in vpPlotYieldFlavors) {
            val value = validPlotYieldTimes100(profile, plot, stat)
            if (value <= 0) continue
            total += value * (context.civ.ai.personality(flavorAndFactor.first) * flavorAndFactor.second) / 10000
        }
        return total
    }

    private enum class PlotYieldKind { Terrain, Feature, NaturalWonder, Resource }

    private fun plotYieldKind(filter: String, ruleset: Ruleset): PlotYieldKind {
        val terrain = ruleset.terrains[filter]
        return when {
            terrain?.type == TerrainType.TerrainFeature -> PlotYieldKind.Feature
            terrain?.type == TerrainType.NaturalWonder || filter == "Natural Wonder" -> PlotYieldKind.NaturalWonder
            ruleset.tileResources.containsKey(filter) -> PlotYieldKind.Resource
            else -> PlotYieldKind.Terrain
        }
    }

    /**
     *  CvReligionAI::GetValidPlotYieldTimes100 (V:CvReligionClasses.cpp:7960-8347) for the plot yields Unciv's beliefs have: terrain × 100;
     *  terrain "without" a feature × that feature's [VpPlot.featureRemovalLikelihood] (VP's RequiresNoFeature, for the named feature only);
     *  feature × 100 × (100 - its removal likelihood) / 100; natural wonder and (visible) resource × 100; improvements by
     *  [VpPlot.improvementsTimes100].
     */
    private fun validPlotYieldTimes100(profile: BeliefAiProfile, plot: VpPlot, stat: Stat): Int {
        val tile = plot.tile
        val civ = plot.civ
        val ruleset = civ.gameInfo.ruleset
        var terrain = 0
        var resource = 0
        var wonder = 0
        val features = LinkedHashMap<String, Int>(2)
        for ((filter, stats) in profile.tileYields) {
            if (!tile.matchesFilter(filter, civ)) continue
            val amount = stats[stat].toInt()
            when (plotYieldKind(filter, ruleset)) {
                PlotYieldKind.Terrain -> terrain += amount
                PlotYieldKind.Feature -> features[filter] = (features[filter] ?: 0) + amount
                PlotYieldKind.NaturalWonder -> wonder += amount
                PlotYieldKind.Resource -> resource += amount
            }
        }
        var value = 0
        if (terrain > 0) value += terrain * 100
        for ((filter, without, stats) in profile.tileWithoutYields) {
            val yieldTimes100 = stats[stat].toInt() * 100
            if (yieldTimes100 <= 0 || !tile.matchesFilter(filter, civ)) continue
            val modifier = if (tile.matchesFilter(without, civ)) plot.featureRemovalLikelihood(without) else 100
            value += yieldTimes100 * modifier / 100
        }
        for ((feature, amount) in features)
            if (amount > 0) value += amount * 100 * (100 - plot.featureRemovalLikelihood(feature)) / 100
        if (tile.isNaturalWonder()) value += wonder * 100
        value += resource * 100
        value += plot.improvementsTimes100(profile, stat)
        return value
    }

    /** One plot of CvReligionAI::GetValidPlotYieldTimes100: what it would take to remove its features or build improvements on it */
    private class VpPlot(val tile: Tile, private val context: BeliefScoringContext) {
        val civ = context.civ
        private val ruleset = civ.gameInfo.ruleset
        private val removalLikelihoods = HashMap<String, Int>(2)
        private val legality = HashMap<TileImprovement, Boolean?>()

        /** With future tech, each improvement's change × [improvementConfidence]; without, the current improvement's × 100
         *  (V:CvReligionClasses.cpp:8264-8345) */
        fun improvementsTimes100(profile: BeliefAiProfile, stat: Stat): Int {
            var value = 0
            for ((improvements, stats) in profile.improvementYields) {
                val change = stats[stat].toInt()
                if (!context.considerFutureTech) {
                    if (improvements.any { it.name == tile.improvement }) value += change * 100
                    continue
                }
                if (change <= 0) continue
                for (improvement in improvements)
                    if (!improvement.isGreatImprovement()) value += change * improvementConfidence(improvement)
            }
            return value
        }

        /** 100 when built; else, if the plot can take it, 90 when its tech is known, 80 while researching it, 50 later; × 80 / 100 when the
         *  feature has to go */
        private fun improvementConfidence(improvement: TileImprovement): Int {
            if (tile.improvement == improvement.name) return 100
            if (!isOurs(improvement)) return 0
            val removesFeature = buildRemovesFeature(improvement) ?: return 0
            val tech = improvement.techRequired
            var confidence = when {
                tech == null || civ.tech.isResearched(tech) -> 90
                civ.tech.currentTechnologyName() == tech -> 80
                else -> 50
            }
            if (removesFeature) confidence = confidence * 80 / 100
            return confidence
        }

        /**
         *  How likely [feature] is to be removed (V:CvReligionClasses.cpp:7975-8124): 0 without future tech or a way to remove it;
         *  on a visible resource 0 once connected, else 75 if the last improvement the plot can take must remove it; without one 25,
         *  Tradition +15, a forest or jungle -20 for a civilization that moves through them as on roads, else +50 when our unique
         *  improvement must remove it.
         */
        fun featureRemovalLikelihood(feature: String): Int = removalLikelihoods.getOrPut(feature) {
            if (!context.considerFutureTech || feature !in tile.terrainFeatures) return@getOrPut 0
            if (!ruleset.tileImprovements.containsKey(Constants.remove + feature)) return@getOrPut 0
            if (tile.resource != null && civ.canSeeResource(tile.tileResource)) {
                if (tile.providesResources(civ)) return@getOrPut 0
                val last = ruleset.tileImprovements.values
                    .lastOrNull { isImprovementInfo(it) && isOurs(it) && buildRemovesFeature(it) != null }
                return@getOrPut if (last != null && buildRemovesFeature(last) == true) 75 else 0
            }
            var likelihood = 25
            if (civ.policies.isAdopted("Tradition")) likelihood += 15
            if (civ.nation.forestsAndJunglesAreRoads && (feature == Constants.forest || feature == Constants.jungle)) likelihood -= 20
            else {
                val unique = ruleset.tileImprovements.values
                    .firstOrNull { it.uniqueTo != null && isOurs(it) && buildRemovesFeature(it) != null }
                if (unique != null && buildRemovesFeature(unique) == true) likelihood += 50
            }
            likelihood
        }

        /** An improvement of the original's improvement table: no route, removal, order or Great Person improvement */
        private fun isImprovementInfo(improvement: TileImprovement) = !improvement.isRoad() && !improvement.isGreatImprovement()
            && !improvement.name.startsWith(Constants.remove) && improvement.name != Constants.cancelImprovementOrder
            && improvement.name != Constants.repair

        private fun isOurs(improvement: TileImprovement) = improvement.uniqueTo == null || civ.matchesFilter(improvement.uniqueTo!!)

        /** CvPlot::canHaveImprovement with the build's feature removal: false if the plot takes [improvement] as it is, true if only with
         *  its last feature removed, null if not at all. Tech, borders, civilization and resource stock do not count. */
        private fun buildRemovesFeature(improvement: TileImprovement): Boolean? {
            if (legality.containsKey(improvement)) return legality[improvement]
            val result = when {
                canHave(improvement, tile) -> false
                tile.terrainFeatures.isEmpty() -> null
                !ruleset.tileImprovements.containsKey(Constants.remove + tile.terrainFeatures.last()) -> null
                canHave(improvement, context.withoutFeature(tile)) -> true
                else -> null
            }
            legality[improvement] = result
            return result
        }

        private fun canHave(improvement: TileImprovement, plot: Tile) = plot.improvementFunctions
            .getImprovementBuildingProblems(improvement, GameContext(civ, tile = plot)).none { it !in plotIndependentProblems }

        companion object {
            private val plotIndependentProblems = setOf(
                ImprovementBuildingProblem.MissingTech, ImprovementBuildingProblem.WrongCiv, ImprovementBuildingProblem.OutsideBorders,
                ImprovementBuildingProblem.NotJustOutsideBorders, ImprovementBuildingProblem.MissingResources
            )
        }
    }

    /** CvReligionAI::ScoreBeliefAtCity */
    fun scoreBeliefAtCity(profile: BeliefAiProfile, city: City, context: BeliefScoringContext): Int {
        val civ = context.civ
        val population = city.population.population
        val happinessMultiplier = context.happinessMultiplier
        val minPop = profile.minPopulation
        val minFollowers = profile.minFollowers

        var value = profile.cityGrowthModifier / 3
        if (profile.requiresPeace) value /= 2
        value += (-profile.plotCultureCostModifier * 2) / 10
        value += (profile.cityRangeStrikeModifier / 10) * max(civ.ai.personality(Flavor.Defense), civ.ai.personality(Flavor.CityDefense))
        value += profile.friendlyHealChange / 10

        if (profile.obsoleteEra > 0) {
            if (profile.obsoleteEra > context.gameEra) value += profile.wonderProductionModifier * profile.obsoleteEra / 10
        } else value += profile.wonderProductionModifier / 3

        var temp = profile.riverHappiness * happinessMultiplier
        if (minPop > 0 && population >= minPop) temp *= 2
        value += temp

        temp = profile.happinessPerCity * happinessMultiplier
        if (minPop > 0 && population >= minPop) temp *= 2
        value += temp

        for (happiness in profile.buildingHappiness.values) {
            temp = happiness * happinessMultiplier
            if (minFollowers > 0 && population >= minFollowers) temp *= 2
            value += temp
        }

        for (stat in Stat.entries) {
            if (stat == Stat.Happiness) continue
            temp = profile.cityYield[stat].toInt()
            if (minPop > 0 && population >= minPop) temp *= 2
            value += temp

            temp = profile.tradeRouteYield[stat].toInt()
            if (minPop > 0 && population >= minPop) temp *= 2
            if (city.isConnectedToCapital()) temp *= 2
            value += temp

            temp = profile.specialistYield[stat].toInt()
            if (population >= 8) temp *= 3
            value += temp

            for ((building, yields) in profile.buildingYields) {
                temp = yields[stat].toInt()
                if (minFollowers > 0 && population < minFollowers) temp /= 2
                if (city.cityConstructions.isBuilt(building.name)) temp *= 2
                if (building.isNationalWonder) temp /= 2
                value += temp
            }

            value += profile.worldWonderYield[stat].toInt() * 3 / 2

            val perFollower = profile.maxYieldModifierPerFollower[stat].toInt()
            if (perFollower > 0) value += min(perFollower, population) / 2
        }
        return value
    }

    /** CvReligionAI::ScoreBeliefForPlayer: the personality flavors (Offense, Defense, Happiness, Culture, Gold, Great People, Science,
     *  Diplomacy, Expansion), world conquest, the share of enhanced religions and the barbarian strategy */
    fun scoreBeliefForPlayer(profile: BeliefAiProfile, context: BeliefScoringContext): Int {
        val civ = context.civ
        val ai = civ.ai
        val offense = ai.personality(Flavor.Offense)
        val defense = ai.personality(Flavor.Defense)
        val happiness = ai.personality(Flavor.Happiness)
        val culture = ai.personality(Flavor.Culture)
        val gold = ai.personality(Flavor.Gold)
        val greatPeople = ai.personality(Flavor.GreatPeople)
        val science = ai.personality(Flavor.Science)
        val diplomacy = ai.personality(Flavor.Diplomacy)
        val expansion = ai.personality(Flavor.Expansion)
        val worldConquest = ai.isGoingForWorldConquest()

        var value = 0
        // Pantheon
        if (profile.faithFromKills > 0) {
            var temp = profile.faithFromKills * profile.maxDistance * offense / 100
            if (worldConquest) temp *= 2
            value += temp
        }
        value += profile.playerHappiness * happiness
        value += profile.playerCultureModifier * culture

        // Follower: unlocked buildings, faith-purchasable units per era
        val personality = ai.personalityFlavorVector()
        for (building in profile.enabledBuildings) {
            val flavors = civ.gameInfo.ruleset.ai.flavorsOf(building)
            val producible = building.cost > 0 && !building.hasUnique(UniqueType.Unbuildable, GameContext.IgnoreConditionals)
            for (i in personality.indices) {
                var flavorValue = personality[i] * flavors[i]
                if (producible) flavorValue /= 10
                value += flavorValue
            }
        }
        if (profile.faithUnitPurchaseEras > 0) {
            var temp = offense + defense / 2
            if (worldConquest) temp *= 2
            value += temp * profile.faithUnitPurchaseEras
        }

        // Founder
        value += (profile.happinessPerFollowingCity * (happiness + 2 * offense - defense).toFloat()).toInt()
        if (profile.happinessPerXPeacefulForeignFollowers > 0 && defense > 0)
            value += (happiness * 10 * defense) / (profile.happinessPerXPeacefulForeignFollowers * defense)
        value += science * profile.sciencePerOtherReligionFollower / 5
        value += (profile.goldPerFollowingCity * gold).toInt()
        if (profile.goldPerXFollowers > 0) value += gold * 4 / profile.goldPerXFollowers
        value += gold * profile.goldWhenCityAdopts / 50
        value += diplomacy * profile.cityStateMinimumInfluence / 7
        for (stat in Stat.entries) {
            if (stat == Stat.Happiness) continue
            val perCity = profile.yieldPerForeignCity[stat].toInt()
            val perFollowers = profile.yieldPerXForeignFollowers[stat].toInt()
            if (stat == Stat.Culture) {
                value += perCity * 2 * culture
                if (perFollowers > 0) value += 10 * culture / perFollowers
            } else {
                value += perCity * 10
                if (perFollowers > 0) value += 50 / perFollowers
            }
        }

        // Enhancer
        value += greatPeople * profile.greatPersonExpendedFaith / 10
        value += diplomacy * profile.friendlyCityStateSpreadModifier / 20
        value += defense * profile.combatModifierFriendlyCities / 4
        value += offense * profile.combatModifierEnemyCities / 4

        // Timing: the share of religions already enhanced (these flavor addends apply to every belief, as in the original)
        val enhancedPercent = context.religionsEnhancedPercent
        when {
            enhancedPercent < 33 -> {
                value += 2 * expansion + profile.spreadDistanceModifier
                value += 2 * science + profile.spreadStrengthModifier
                value += profile.missionaryStrengthModifier * 2
                value += -profile.missionaryCostModifier * 2
                value += profile.prophetStrengthModifier / 4
                value += -profile.prophetCostModifier / 4
            }
            enhancedPercent < 66 -> {
                value += expansion + profile.spreadDistanceModifier / 2
                value += science + profile.spreadStrengthModifier / 2
                value += profile.prophetStrengthModifier / 2
                value += -profile.prophetCostModifier / 2
                value += profile.missionaryStrengthModifier
                value += -profile.missionaryCostModifier
            }
            else -> {
                value += (2 * expansion + profile.spreadDistanceModifier) / 4
                value += (2 * science + profile.spreadStrengthModifier) / 4
                value += profile.missionaryStrengthModifier / 2
                value += -profile.missionaryCostModifier / 2
                value += profile.prophetStrengthModifier
                value += -profile.prophetCostModifier
            }
        }

        // Reformation
        if (profile.faithPurchaseAllGreatPeople) value += unfinishedGreatPersonBranches(civ) * 6
        if (profile.convertsBarbarians)
            value += if (ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")) 40 else 15
        if (profile.cityStateInfluenceModifier > 0) value += profile.cityStateInfluenceModifier * diplomacy / 5
        if (profile.inquisitorPressureRetention > 0) value += profile.inquisitorPressureRetention / 2
        if (profile.spyPressure > 0) value += profile.spyPressure * 5
        if (profile.faithBuildingTourism > 0) value += profile.faithBuildingTourism * 20
        return value
    }

    /** The policy branches whose finishers unlock a Great Person for Faith (the original names them), still unfinished */
    private val greatPersonBranches = listOf("Tradition", "Honor", "Aesthetics", "Commerce", "Exploration", "Rationalism")

    /** ScoreBeliefForPlayer, FaithPurchaseAllGreatPeople: the branches of [greatPersonBranches] not finished (a finished branch is never
     *  blocked in Unciv, so the original's "or blocked" adds nothing) */
    private fun unfinishedGreatPersonBranches(civ: Civilization): Int {
        val branches = civ.gameInfo.ruleset.policyBranches
        return greatPersonBranches.count { name ->
            val branch = branches[name] ?: return@count false
            !branch.policies.all { civ.policies.isAdopted(it.name) }
        }
    }
}
