package com.unciv.logic.city

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.managers.ResourceShortage
import com.unciv.logic.civilization.managers.TradeRouteManager
import com.unciv.logic.civilization.managers.TradeRouteValue
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.StatMap
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import com.unciv.utils.DebugUtils
import yairm210.purity.annotations.InternalState
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import kotlin.math.min
import kotlin.math.roundToInt

class StatTreeNode {
    val children = LinkedHashMap<String, StatTreeNode>()
    private var innerStats: Stats? = null

    fun setInnerStat(stat: Stat, value: Float) {
        if (innerStats == null) innerStats = Stats()
        innerStats!![stat] = value
    }

    private fun addInnerStats(stats: Stats) {
        if (innerStats == null) innerStats = stats.clone() // Copy the stats instead of referencing them
        else innerStats!!.add(stats) // What happens if we add 2 stats to the same leaf?
    }

    fun addStats(newStats: Stats?, vararg hierarchyList: String) {
        if (newStats == null) return
        if (newStats.isEmpty()) return
        if (hierarchyList.isEmpty()) {
            addInnerStats(newStats)
            return
        }
        val childName = hierarchyList.first()
        if (!children.containsKey(childName))
            children[childName] = StatTreeNode()
        children[childName]!!.addStats(newStats, *hierarchyList.drop(1).toTypedArray())
    }

    fun add(otherTree: StatTreeNode) {
        if (otherTree.innerStats != null) addInnerStats(otherTree.innerStats!!)
        for ((key, value) in otherTree.children) {
            if (!children.containsKey(key)) children[key] = value
            else children[key]!!.add(value)
        }
    }

    fun clone() : StatTreeNode {
        val new = StatTreeNode()
        new.innerStats = this.innerStats?.clone()
        new.children.putAll(this.children.mapValues { it.value.clone() })
        return new
    }

    val totalStats: Stats
        get() {
            val toReturn = Stats()
            if (innerStats != null) toReturn.add(innerStats!!)
            for (child in children.values) toReturn.add(child.totalStats)
            return toReturn
        }
}

/** Holds and calculates [Stats] for a city.
 *
 * No field needs to be saved, all are calculated on the fly,
 * so its field in [City] is @Transient and no such annotation is needed here.
 */
class CityStats(val city: City) {
    companion object {
        /** The [finalStatList] entry of the production that a settler-like construction makes of the excess food */
        const val EXCESS_FOOD_TO_PRODUCTION = "Excess food to production"
    }

    //region Fields, Transient

    var baseStatTree = StatTreeNode()

    var statPercentBonusTree = StatTreeNode()

    // Computed from baseStatList and statPercentBonusList - this is so the players can see a breakdown
    var finalStatList = LinkedHashMap<String, Stats>()

    /** With [ModConstants.cityNeedsLikeVp]: the sources of the city's local happiness (without the empire's share, see [CityNeeds.updateLocalHappiness]) */
    var happinessList = LinkedHashMap<String, Float>()

    /** The happiness of the city's buildings, policies and beliefs that [ModConstants.localHappinessCappedByPopulation] limits to the population
     *  (CvCity::GetLocalHappiness). Without that constant: the happiness of the buildings, nothing is limited.
     *  With [ModConstants.cityNeedsLikeVp]: the whole local happiness including the empire's share, [CityNeeds.localHappiness], which nothing limits. */
    var localHappiness = 0f

    /** The city's needs and happiness of Vox Populi's model, only filled with [ModConstants.cityNeedsLikeVp] and only by the city's own stats ([City.cityStats]),
     *  not by the stats that are calculated to see what a construction would give */
    val needs = CityNeeds(city)

    var statsFromTiles = Stats()

    var currentCityStats: Stats = Stats()  // This is so we won't have to calculate this multiple times - takes a lot of time, especially on phones

    /** Food produced minus food eaten, before growth modifiers and food-to-production conversion: the original's
     *  `getYieldRateTimes100(YIELD_FOOD) - foodConsumption() * 100` (in whole food), read by the citizen governor ([com.unciv.logic.automation.city.CitizenAutomation]) */
    var excessFood = 0f
        private set

    //endregion
    //region Pure Functions

    @Readonly
    private fun getStatsFromTradeRoute(): Stats {
        val capitalForTradeRoutePurposes = city.civ.getCapital()!!
        if (city != capitalForTradeRoutePurposes && city.isConnectedToCapital()) return getTradeRouteStats(capitalForTradeRoutePurposes)
        return Stats()
    }

    /** The stats of this city's connection to [capital], whether or not it is connected */
    @Readonly
    private fun getTradeRouteStats(capital: City): Stats {
        val stats = Stats()
        stats.gold = capital.population.population * 0.15f + city.population.population * 1.1f - 1 // Calculated by http://civilization.wikia.com/wiki/Trade_route_(Civ5)
        city.forEachMatchingUnique(UniqueType.StatsFromTradeRoute) { unique ->
            stats.add(unique.stats)
        }
        val percentageStats = Stats()
        city.forEachMatchingUnique(UniqueType.StatPercentFromTradeRoutes) { unique ->
            percentageStats[Stat.valueOf(unique.params[1])] += unique.params[0].toFloat()
        }
        for ((stat) in stats) {
            stats[stat] *= percentageStats[stat].toPercent()
        }
        return stats
    }

    /**
     *  CvTreasury::GetCityConnectionRouteGoldTimes100: the gold this city's connection to the capital yields or would yield, in hundredths
     *  (0 when it would not yield any); 0 for the capital. The float gold of [getTradeRouteStats] is rounded, not truncated.
     */
    @Readonly
    fun getTradeRouteGoldTimes100(): Int {
        val capital = city.civ.getCapital() ?: return 0
        if (city == capital) return 0
        val gold = getTradeRouteStats(capital).gold
        return if (gold <= 0f) 0 else (gold * 100).roundToInt()
    }

    @Readonly
    private fun getStatsFromProduction(production: Float): Stats? {
        val currentName = city.cityConstructions.currentConstructionName() // Once: the queue can change between two reads
        if (Stat.isStat(currentName)) {
            val stats = Stats()
            val stat = Stat.valueOf(currentName)
            stats[stat] = production * getStatConversionRate(stat)
            return stats
        }
        return null
    }

    @Readonly
    fun getStatConversionRate(stat: Stat): Float {
        var conversionRate = 1 / 4f
        val conversionUnique = city.civ.getMatchingUniques(UniqueType.ProductionToStatConversionBonus).firstOrNull { it.params[0] == stat.name }
        if (conversionUnique != null) {
            conversionRate *= conversionUnique.params[1].toPercent()
        }
        // CvCity::GetBaseYieldRateFromProcessTimes100: the process modifier of the buildings (`Building_YieldFromProcessModifier`) adds percentage points to the conversion
        city.forEachMatchingUnique(UniqueType.ProcessYieldPercent) { unique ->
            if (city.matchesFilter(unique.params[1])) conversionRate += unique.params[0].toInt() / 100f
        }
        return conversionRate
    }

    @Readonly
    private fun getStatPercentBonusesFromRailroad(): Stats? {
        val railroadImprovement = city.getRuleset().railroadImprovement
            ?: return null // for mods
        val techEnablingRailroad = railroadImprovement.techRequired
        // If we conquered enemy cities connected by railroad, but we don't yet have that tech,
        // we shouldn't get bonuses, it's as if the tracks are laid out but we can't operate them.
        if ( (techEnablingRailroad == null || city.civ.tech.isResearched(techEnablingRailroad))
                && (city.isCapital() || isConnectedToCapital(RoadStatus.Railroad)))
            return Stats(production = 25f)
        return null
    }

    /**
     *  The percent bonuses of a puppet city (BNW's PUPPET_SCIENCE_MODIFIER and PUPPET_CULTURE_MODIFIER, -25); read by the builder AI's yield prices.
     *  With [com.unciv.models.ModConstants.cityNeedsLikeVp] none: Vox Populi sets those two to 0 and a puppet's yields are multiplied
     *  by [com.unciv.models.ModConstants.NeedsConstants.puppetYieldMultiplier] instead ([getPuppetYieldMultiplier]).
     */
    @Readonly
    fun getStatPercentBonusesFromPuppetCity(): Stats? {
        if (!city.isPuppet || city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) return null
        return Stats(science = -25f, culture = -25f)
    }

    /**
     *  CvCity::getYieldModifierMultiplicative: with [com.unciv.models.ModConstants.cityNeedsLikeVp] a puppet city keeps 30 percent of its gold,
     *  science, culture and faith (`PUPPET_YIELD_AND_SUPPLY_MODIFIER_MULTIPLICATIVE` of Vox Populi, V:CvCity.cpp:23039-23047; it applies to tourism and golden age points too,
     *  which Unciv's cities do not make as yields). The share is taken of the yield after the percent bonuses and before the trade routes
     *  (CvCity::getYieldRateTimes100 multiplies before it adds the trade yield). 1 for any other city.
     */
    @Readonly
    private fun getPuppetYieldMultiplier(): Float {
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        if (!city.isPuppet || !constants.cityNeedsLikeVp) return 1f
        return constants.needs.puppetYieldMultiplier / 100f
    }

    @Readonly
    fun getGrowthBonus(totalFood: Float): StatMap {
        val growthSources = StatMap()
        // "[amount]% growth [cityFilter]"
        city.forEachMatchingUnique(UniqueType.GrowthPercentBonus, city.state) { unique: Unique ->
            if (!city.matchesFilter(unique.params[1])) return@forEachMatchingUnique

            growthSources.add(
                unique.getSourceNameForUser(),
                Stats(food = unique.params[0].toFloat() / 100f * totalFood)
            )
        }
        return growthSources
    }

    @Readonly
    fun hasExtraAnnexUnhappiness(): Boolean {
        if (city.civ == city.foundingCivObject || city.isPuppet) return false
        return !city.containsBuildingUnique(UniqueType.RemovesAnnexUnhappiness)
    }

    @Readonly
    fun getStatsOfSpecialist(specialistName: String): Stats {
        val specialist = city.getRuleset().specialists[specialistName]
            ?: return Stats()
        @LocalState val stats = specialist.cloneStats()
        city.forEachMatchingUnique(UniqueType.StatsFromSpecialist, city.state) { unique: Unique ->
            if (city.matchesFilter(unique.params[1]))
                stats.add(unique.stats)
        }
        city.forEachMatchingUnique(UniqueType.StatsFromNamedSpecialist, city.state) { unique: Unique ->
            if (unique.params[1] == specialistName && city.matchesFilter(unique.params[2]))
                stats.add(unique.stats)
        }
        city.forEachMatchingUnique(UniqueType.StatsFromObject, city.state) { unique: Unique ->
            if (unique.params[1] == specialistName)
                stats.add(unique.stats)
        }
        return stats
    }

    @Readonly
    private fun getStatsFromSpecialists(specialists: Counter<String>): Stats {
        val stats = Stats()
        for ((key, value) in specialists.filter { it.value > 0 }.toList()) // avoid concurrent modification when calculating construction costs
            stats.add(getStatsOfSpecialist(key) * value)
        return stats
    }


    @Readonly
    private fun getStatsFromUniquesBySource(): StatTreeNode {
        @LocalState val sourceToStats = StatTreeNode()

        val cityStateStatsMultipliers = city.civ.getMatchingUniques(UniqueType.BonusStatsFromCityStates).toList()

        fun addUniqueStats(unique: Unique) {
            val stats = unique.stats.clone()
            if (unique.sourceObjectType==UniqueTarget.CityState)
                for (multiplierUnique in cityStateStatsMultipliers)
                    stats[Stat.valueOf(multiplierUnique.params[1])] *= multiplierUnique.params[0].toPercent()
            sourceToStats.addStats(stats, unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        city.forEachMatchingUnique(UniqueType.StatsPerCity) { unique ->
            if (city.matchesFilter(unique.params[1]))
                addUniqueStats(unique)
        }

        // "[stats] per [amount] population [cityFilter]"
        city.forEachMatchingUnique(UniqueType.StatsPerPopulation) { unique ->
            if (city.matchesFilter(unique.params[2])) {
                val amountOfEffects = (city.population.population / unique.params[1].toInt()).toFloat()
                sourceToStats.addStats(unique.stats.times(amountOfEffects), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
            }
        }

        // CvCity::getBaseYieldRateTimes100 adds GetYieldPerPopTimes100 times population (V:CvCity.cpp:23489-23491).
        city.forEachMatchingUnique(UniqueType.StatsPerPopulationProportional) { unique ->
            if (city.matchesFilter(unique.params[2])) {
                val amount = city.population.population.toFloat() / unique.params[1].toInt()
                sourceToStats.addStats(unique.stats.times(amount), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
            }
        }

        // CvCity::getBaseYieldRateTimes100 adds the empire-population yield in the building's own city (V:CvCity.cpp:23494-23496).
        var empirePopulation = -1
        city.forEachMatchingUnique(UniqueType.StatsPerEmpirePopulationProportional) { unique ->
            if (city.matchesFilter(unique.params[2])) {
                if (empirePopulation < 0) empirePopulation = city.civ.cities.sumOf { it.population.population }
                val amount = empirePopulation.toFloat() / unique.params[1].toInt()
                sourceToStats.addStats(unique.stats.times(amount), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
            }
        }

        // "[stats] for each major religion with followers in the city [cityFilter]" (Candi)
        city.forEachMatchingUnique(UniqueType.StatsPerReligionWithFollowers) { unique ->
            if (city.matchesFilter(unique.params[1]))
                sourceToStats.addStats(unique.stats.times(city.religion.getNumberOfMajorReligionsWithFollowers()),
                    unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        city.forEachMatchingUnique(UniqueType.StatsFromCitiesOnSpecificTiles) { unique ->
            if (city.getCenterTile().matchesTerrainFilter(unique.params[1], city.civ))
                addUniqueStats(unique)
        }

        // "[stats] [cityFilter] for every global monopoly" (CvPlayer::SetHasGlobalMonopoly: Building_YieldChangesPerMonopoly, V:CvPlayer.cpp:39010-39030)
        val globalMonopolies = city.civ.monopolies.globalMonopolyCount()
        if (globalMonopolies > 0) city.forEachMatchingUnique(UniqueType.StatsPerGlobalMonopoly) { unique ->
            if (city.matchesFilter(unique.params[1]))
                sourceToStats.addStats(unique.stats.times(globalMonopolies.toFloat()), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        // "[stats] from each [greatWorkType] Great Work [cityFilter]" (CvCityBuildings::GetYieldFromGreatWorksTimes100)
        city.forEachMatchingUnique(UniqueType.StatsFromGreatWorkType) { unique ->
            if (!city.matchesFilter(unique.params[2])) return@forEachMatchingUnique
            val type = GreatWorkType.entries.firstOrNull { it.name == unique.params[1] } ?: return@forEachMatchingUnique
            val numberOfWorks = city.greatWorks.getNumWorks(type)
            if (numberOfWorks > 0)
                sourceToStats.addStats(unique.stats.times(numberOfWorks.toFloat()), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        // "Capital's [stats] per [positiveAmount] citizens in the empire" (CvPlot::calculateYield, CvPlayer::GetCapitalYieldPerPopChangeEmpire):
        // the population times the yield per citizen in whole numbers, which is the whole numbers of citizens per amount
        if (city.isCapital()) city.forEachMatchingUnique(UniqueType.CapitalStatsPerEmpirePopulation) { unique ->
            val steps = city.civ.cities.sumOf { it.population.population } / unique.params[1].toInt()
            if (steps > 0)
                sourceToStats.addStats(unique.stats.times(steps.toFloat()), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        return sourceToStats
    }

    @Pure
    private fun getStatPercentBonusesFromGoldenAge(isGoldenAge: Boolean): Stats? {
        if (!isGoldenAge) return null
        return Stats(production = 20f, culture = 20f)
    }

    @Readonly
    private fun getStatsPercentBonusesFromUniquesBySource(currentConstruction: IConstruction): StatTreeNode {
        @LocalState val sourceToStats = StatTreeNode()

        fun addUniqueStats(unique: Unique, stat: Stat, amount: Float) {
            val stats = Stats()
            stats.add(stat, amount)
            sourceToStats.addStats(stats, unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        city.forEachMatchingUnique(UniqueType.StatPercentBonus) { unique -> 
            addUniqueStats(unique, Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }


        city.forEachMatchingUnique(UniqueType.StatPercentBonusCities) { unique ->
            if (city.matchesFilter(unique.params[2]))
                addUniqueStats(unique, Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }

        // "[relativeAmount]% [stat] [cityFilter] per [positiveAmount] Spy Points ever accumulated, up to [positiveAmount]%" (CvCity::getBaseYieldRateModifier, Policy_YieldModifierFromActiveSpies):
        // whole steps of the points over the second amount, never more than the cap
        if (city.getRuleset().modOptions.constants.spyPointsLikeVp) city.forEachMatchingUnique(UniqueType.StatPercentPerSpyPoints) { unique ->
            if (!city.matchesFilter(unique.params[2])) return@forEachMatchingUnique
            val percent = minOf(unique.params[4].toInt(), unique.params[0].toInt() * city.civ.espionageManager.spyPointsTotal / unique.params[3].toInt())
            if (percent != 0) addUniqueStats(unique, Stat.valueOf(unique.params[1]), percent.toFloat())
        }

        val uniquesToCheck = productionUniquesToCheck(currentConstruction)

        for (unique in uniquesToCheck) {
            if (constructionMatchesFilter(currentConstruction, unique.params[1])
                && city.matchesFilter(unique.params[2])
            )
                addUniqueStats(unique, Stat.Production, unique.params[0].toFloat())
        }


        // "[relativeAmount]% Production of Wonders in cities with this resource" (CvCity::GetWonderProductionModifierFromLocalResources)
        if (currentConstruction is Building) {
            val resourcePercent = ResourceProductionModifiers.wonderPercent(city, currentConstruction)
            if (resourcePercent != 0) sourceToStats.addStats(Stats(production = resourcePercent.toFloat()), "Resources")
        }

        city.forEachMatchingUnique(UniqueType.StatPercentFromReligionFollowers) { unique ->
            addUniqueStats(unique, Stat.valueOf(unique.params[1]),
                min(
                    unique.params[0].toFloat() * city.religion.getFollowersOfMajorityReligion(),
                    unique.params[2].toFloat()
                ))
        }

        if (currentConstruction is Building
            && city.civ.getCapital()?.cityConstructions?.isBuilt(currentConstruction.name) == true
        ) {
            city.forEachMatchingUnique(UniqueType.PercentProductionBuildingsInCapital) { unique ->
                addUniqueStats(unique, Stat.Production, unique.params[0].toFloat())
            }
        }

        return sourceToStats
    }

    /** The production bonus uniques that depend on what the city builds: those of units, wonders or other buildings, none for a process */
    @Readonly
    private fun productionUniquesToCheck(currentConstruction: IConstruction): Sequence<Unique> =
        when {
            currentConstruction is BaseUnit ->
                city.getMatchingUniques(UniqueType.PercentProductionUnits)
            currentConstruction is Building && currentConstruction.isAnyWonder() ->
                city.getMatchingUniques(UniqueType.PercentProductionWonders)
            currentConstruction is Building && !currentConstruction.isAnyWonder() ->
                city.getMatchingUniques(UniqueType.PercentProductionBuildings)
            else -> emptySequence() // Science/Gold production
        }

    /**
     *  Everything of [construction] that [update] reads: two constructions with equal signatures give the city the same stats, so the
     *  production it would make building either is one calculation ([CityConstructions.withProductionCache]). Its kind, whether each
     *  production bonus unique that applies to its kind matches it in this city, whether the capital has it built (the bonus of
     *  [UniqueType.PercentProductionBuildingsInCapital]) and whether it converts food to production.
     */
    @Readonly
    fun constructionSignature(construction: IConstruction): List<Any> {
        val signature = ArrayList<Any>()
        signature.add(when {
            construction is BaseUnit -> 0
            construction is Building && construction.isAnyWonder() -> 1
            construction is Building -> 2
            else -> 3
        })
        for (unique in productionUniquesToCheck(construction))
            signature.add(constructionMatchesFilter(construction, unique.params[1]) && city.matchesFilter(unique.params[2]))
        signature.add(if (construction is Building) ResourceProductionModifiers.wonderPercent(city, construction) else 0)
        signature.add(construction is Building && city.civ.getCapital()?.cityConstructions?.isBuilt(construction.name) == true)
        signature.add(construction is INonPerpetualConstruction && construction.hasUnique(UniqueType.ConvertFoodToProductionWhenConstructed))
        if (city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) // what unhappiness takes off the production of a unit depends on the kind of unit
            signature.add(if (construction is BaseUnit) city.cityStats.needs.unitProductionPenalty(construction) else 0)
        return signature
    }

    @Readonly
    private fun getStatPercentBonusesFromUnitSupply(): Stats? {
        val supplyDeficit = city.civ.stats.getUnitSupplyDeficit()
        if (supplyDeficit > 0)
            return Stats(production = city.civ.stats.getUnitSupplyProductionPenalty())
        return null
    }

    /** The production percent of a settler or combat unit that unhappiness takes away, with [ModConstants.cityNeedsLikeVp] (CvCity::getProductionModifier(UnitTypes), see [CityNeeds.unitProductionPenalty]) */
    @Readonly
    private fun getStatPercentBonusesFromUnhappiness(currentConstruction: IConstruction): Stats? {
        if (currentConstruction !is BaseUnit || !city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) return null
        val penalty = city.cityStats.needs.unitProductionPenalty(currentConstruction)
        return if (penalty == 0) null else Stats(production = penalty.toFloat())
    }

    @Readonly
    private fun constructionMatchesFilter(construction: IConstruction, filter: String): Boolean {
        val state = city.state
        if (construction is Building) return construction.matchesFilter(filter, state)
        if (construction is BaseUnit) return construction.matchesFilter(filter, state)
        return false
    }

    @Readonly
    fun isConnectedToCapital(roadType: RoadStatus): Boolean {
        if (city.civ.cities.size < 2) return false // first city!

        // Railroad, or harbor from railroad
        return if (roadType == RoadStatus.Railroad)
                city.isConnectedToCapital {
                    mediums ->
                    mediums.any { it.roadType == RoadStatus.Railroad }
                }
            else city.isConnectedToCapital()
    }

    @Readonly
    fun getRoadTypeOfConnectionToCapital(): RoadStatus {
        return city.civ.cache.citiesConnectedToCapitalToMediums[city]?.maxOfOrNull { it.roadType }
            ?: RoadStatus.None
    }

    @Readonly
    private fun getBuildingMaintenanceCosts(): Float {
        // Same here - will have a different UI display.
        var buildingsMaintenance = city.cityConstructions.getMaintenanceCosts() // this is AFTER the bonus calculation!
        // CvTreasury::GetBuildingGoldMaintenance: the copies of short resources that buildings were refunded make all of them cost more
        val extraPercent = ResourceShortage.extraMaintenancePercent(city.civ)
        if (extraPercent != 0) buildingsMaintenance = buildingsMaintenance * (100 + extraPercent) / 100f
        // CvTreasury::GetBuildingGoldMaintenance: a human pays the building cost percentage of the handicap, the others the AI one of the chosen difficulty
        buildingsMaintenance *= if (city.civ.isHuman()) city.civ.getDifficulty().buildingCostModifier
            else city.civ.gameInfo.getDifficulty().aiBuildingMaintenanceModifier
        buildingsMaintenance *= city.civ.gameInfo.getStartingEra().buildingMaintenancePercent / 100f

        return buildingsMaintenance
    }

    //endregion
    //region State-Changing Methods

    fun updateTileStats():Unit = timeThis("updateTileStats") {
        val stats = Stats()
        val workedTiles = city.tilesInRange.asSequence()
            .filter {
                city.location.toHexCoord() == it.position
                        || city.isWorked(it)
                        || it.owningCity == city && (it.getUnpillagedTileImprovement()
                    ?.hasUnique(UniqueType.TileProvidesYieldWithoutPopulation, it.stateThisTile) == true
                        || it.terrainHasUnique(UniqueType.TileProvidesYieldWithoutPopulation, it.stateThisTile))
            }
        for (tile in workedTiles) {
            if (tile.isBlockaded() && city.isWorked(tile)) {
                city.stopWorkingTile(tile)
                city.shouldReassignPopulation = true
                continue
            }
            val tileStats = tile.stats.getTileStats(city, city.civ)
            stats.add(tileStats)
        }
        statsFromTiles = stats
    }


    /** The city's own stats ([City.cityStats]), as opposed to those [CityConstructions.productionForConstructionExact] calculates for another construction */
    @Readonly
    private fun isOwnStats() = city.cityStats === this

    // needs to be a separate function because we need to know the global happiness state
    // in order to determine how much food is produced in a city!
    fun updateCityHappiness(statsFromBuildings: StatTreeNode,
                            statsFromSpecialists: Stats = getStatsFromSpecialists(city.population.getNewSpecialists()),
                            statsFromUniquesBySource: StatTreeNode = getStatsFromUniquesBySource()) {
        val civInfo = city.civ
        if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) {
            // Vox Populi's model: the city's own happiness here, its needs once the yields are known (updateFinalStatList). Nothing of the original's
            // "Cities", "Population" and "Occupied City" unhappiness, and nothing limits the happiness to the population
            if (isOwnStats()) {
                happinessList = needs.updateLocalHappiness(statsFromBuildings, statsFromSpecialists, statsFromTiles, statsFromUniquesBySource)
                localHappiness = needs.localHappiness().toFloat()
            }
            return
        }
        val newHappinessList = LinkedHashMap<String, Float>()
        // The original multiplies twice for an AI as well: the AI player's own handicap (Chieftain, AI_HANDICAP: CvPlayer::getHandicapInfo) gives the
        // city count and population part (NumCitiesUnhappinessMod, PopulationUnhappinessMod 60%), then the game's handicap scales the total
        // by AIUnhappinessPercent (CvPlayer::GetUnhappiness). Here [Civilization.getDifficulty] is the AI's level and
        // aiUnhappinessModifier the game difficulty's percentage. (Unciv's "Prince" AI level is its own, the original has no such exception.)
        var unhappinessModifier = civInfo.getDifficulty().unhappinessModifier
        if (!civInfo.isHuman())
            unhappinessModifier *= civInfo.gameInfo.getDifficulty().aiUnhappinessModifier

        var unhappinessFromCity = -3f // -3 happiness per city
        if (hasExtraAnnexUnhappiness())
            unhappinessFromCity -= 2f

        var uniqueUnhappinessModifier = 0f
        civInfo.forEachMatchingUnique(UniqueType.UnhappinessFromCitiesPercentage) { unique ->
            uniqueUnhappinessModifier += unique.params[0].toFloat()
        }

        // The larger the map, the less the number of cities weighs: the world row's percent, at most 100 as Vox Populi's CvCity empire size need
        // takes it (a small map's 115 to 150 is for the policy AI only), the row of the map's tile count
        val mapSizeModifier = minOf(100, civInfo.gameInfo.ruleset.ai.worldSizeForTiles(civInfo.gameInfo.tileMap.tileList.size).numCitiesUnhappinessPercent) / 100f
        newHappinessList["Cities"] = unhappinessFromCity * unhappinessModifier * uniqueUnhappinessModifier.toPercent() * mapSizeModifier

        var unhappinessFromCitizens = city.population.population.toFloat()

        city.forEachMatchingUnique(UniqueType.UnhappinessFromPopulationTypePercentageChange) { unique ->
            if (city.matchesFilter(unique.params[2]))
                unhappinessFromCitizens += (unique.params[0].toFloat() / 100f) * city.population.getPopulationFilterAmount(unique.params[1])
        }

        if (hasExtraAnnexUnhappiness())
            unhappinessFromCitizens *= civInfo.gameInfo.ruleset.modOptions.constants.annexedCityCitizenUnhappinessFactor

        if (unhappinessFromCitizens < 0) unhappinessFromCitizens = 0f

        newHappinessList["Population"] = -unhappinessFromCitizens * unhappinessModifier

        val annexedFlatUnhappiness = civInfo.gameInfo.ruleset.modOptions.constants.annexedCityFlatUnhappiness
        if (hasExtraAnnexUnhappiness() && annexedFlatUnhappiness != 0f) newHappinessList["Occupied City"] = -annexedFlatUnhappiness //annexed city

        val happinessFromSpecialists = statsFromSpecialists.happiness.toInt().toFloat()
        if (happinessFromSpecialists > 0) newHappinessList["Specialists"] = happinessFromSpecialists

        val happinessFromBuildings = statsFromBuildings.totalStats.happiness.toInt().toFloat()
        newHappinessList["Buildings"] = happinessFromBuildings

        newHappinessList["Tile yields"] = statsFromTiles.happiness

        val happinessBySource = statsFromUniquesBySource
        for ((source, stats) in happinessBySource.children)
            if (stats.totalStats.happiness != 0f) {
                if (!newHappinessList.containsKey(source)) newHappinessList[source] = 0f
                newHappinessList[source] = newHappinessList[source]!! + stats.totalStats.happiness
            }

        if (civInfo.gameInfo.ruleset.modOptions.constants.localHappinessCappedByPopulation) {
            val local = calculateLocalHappiness()
            val limit = getLocalHappinessLimit()
            localHappiness = min(local, limit)
            if (local > limit) newHappinessList["Population limit on local happiness"] = limit - local
        } else localHappiness = happinessFromBuildings

        // we don't want to modify the existing happiness list because that leads
        // to concurrency problems if we iterate on it while changing
        happinessList = newHappinessList
    }

    /**
     * CvCity::GetLocalHappiness before its cap: the base happiness of the buildings (not those that say their happiness is unmodded, the
     * original's UnmoddedHappiness of Circus Maximus, Notre Dame...), the happiness policies and beliefs give for each building of a kind,
     * the garrison policy, and the beliefs' happiness per city and on rivers. Everything else of Unciv's happiness is civilization-wide there.
     */
    @Readonly
    private fun calculateLocalHappiness(): Float {
        var local = 0f
        val buildings = city.cityConstructions.getBuiltBuildings().toList()
        for (building in buildings)
            if (!building.hasUnique(UniqueType.HappinessNotLimitedByPopulation)) local += building.happiness.toInt()

        fun isPolicyOrBelief(unique: Unique) = unique.sourceObjectType == UniqueTarget.Policy
            || unique.sourceObjectType == UniqueTarget.FollowerBelief || unique.sourceObjectType == UniqueTarget.FounderBelief

        city.forEachMatchingUnique(UniqueType.StatsFromObject) { unique ->
            if (unique.stats.happiness <= 0f || !isPolicyOrBelief(unique)) return@forEachMatchingUnique
            for (building in buildings)
                if (building.matchesFilter(unique.params[1], city.state)) local += unique.stats.happiness.toInt()
        }
        city.forEachMatchingUnique(UniqueType.StatsFromBuildings) { unique ->
            if (unique.stats.happiness <= 0f || !isPolicyOrBelief(unique)) return@forEachMatchingUnique
            for (building in buildings)
                if (!building.isWonder && building.matchesFilter(unique.params[1], city.state)) local += unique.stats.happiness.toInt()
        }
        city.forEachMatchingUnique(UniqueType.StatsPerCity) { unique ->
            if (unique.stats.happiness <= 0f || !city.matchesFilter(unique.params[1])) return@forEachMatchingUnique
            // The garrison policy (HappinessPerGarrisonedUnit) and the beliefs (HappinessPerCity)
            if (unique.sourceObjectType == UniqueTarget.FollowerBelief
                || unique.sourceObjectType == UniqueTarget.Policy && unique.params[1] in garrisonCityFilters)
                local += unique.stats.happiness.toInt()
        }
        city.forEachMatchingUnique(UniqueType.StatsFromCitiesOnSpecificTiles) { unique ->
            if (unique.stats.happiness > 0f && unique.sourceObjectType == UniqueTarget.FollowerBelief
                && city.getCenterTile().matchesTerrainFilter(unique.params[1], city.civ)) // RiverHappiness
                local += unique.stats.happiness.toInt()
        }
        return local
    }

    /** The population, times 2/3 rounded for a civilization with a trait that changes the unhappiness of the number of cities (India) */
    @Readonly
    private fun getLocalHappinessLimit(): Float {
        val population = city.population.population
        var changesCityUnhappiness = false
        city.civ.forEachMatchingUnique(UniqueType.UnhappinessFromCitiesPercentage) { if (it.params[0].toFloat() != 0f) changesCityUnhappiness = true }
        return if (changesCityUnhappiness) ((population * 20 + 15) / 30).toFloat() else population.toFloat()
    }

    private val garrisonCityFilters = setOf("in all cities with a garrison", "Garrisoned")

    private fun updateBaseStatList(statsFromBuildings: StatTreeNode, statsFromSpecialists: Stats,
                                   statsFromUniquesBySource: StatTreeNode) {
        val newBaseStatTree = StatTreeNode()

        // We don't edit the existing baseStatList directly, in order to avoid concurrency exceptions
        val newBaseStatList = StatMap()

        newBaseStatTree.addStats(Stats(
            science = city.population.population * city.getRuleset().modOptions.constants.sciencePerPopulation,
            production = city.population.getFreePopulation().toFloat()
        ), "Population")
        newBaseStatList["Tile yields"] = statsFromTiles
        newBaseStatList["Specialists"] = statsFromSpecialists
        newBaseStatList["Trade routes"] = getStatsFromTradeRoute()
        TradeRouteValue.getCityStats(city).let { if (!it.isEmpty()) newBaseStatList["Trade route effects"] = it } // routes passing and routes of the city
        newBaseStatList["Great Works"] = city.greatWorks.getStats()
        newBaseStatTree.children["Buildings"] = statsFromBuildings

        for ((source, stats) in newBaseStatList)
            newBaseStatTree.addStats(stats, source)

        newBaseStatTree.add(statsFromUniquesBySource)
        baseStatTree = newBaseStatTree
    }
    
    @Readonly
    private fun getStatPercentBonusList(currentConstruction: IConstruction): StatTreeNode = timeThis("CityStats.getStatPercentBonusList") {
        @LocalState val newStatsBonusTree = StatTreeNode()

        newStatsBonusTree.addStats(getStatPercentBonusesFromGoldenAge(city.civ.goldenAges.isGoldenAge()),"Golden Age")
        newStatsBonusTree.addStats(getStatPercentBonusesFromRailroad(), "Railroad")
        newStatsBonusTree.addStats(getStatPercentBonusesFromPuppetCity(), "Puppet City")
        newStatsBonusTree.addStats(getStatPercentBonusesFromUnitSupply(), "Unit Supply")
        newStatsBonusTree.addStats(getStatPercentBonusesFromUnhappiness(currentConstruction), "Unhappiness")
        newStatsBonusTree.add(getStatsPercentBonusesFromUniquesBySource(currentConstruction))
        newStatsBonusTree.addStats(city.civ.monopolies.cityPercentBonuses(city), "Monopolies")
        
        for (building in city.cityConstructions.getBuiltBuildings())
            newStatsBonusTree.addStats(building.getStatPercentageBonuses(city),
                "Buildings", building.name)


        if (DebugUtils.SUPERCHARGED) {
            val stats = Stats()
            for (stat in Stat.entries) stats[stat] = 10000f
            newStatsBonusTree.addStats(stats, "Supercharged")
        }
        return newStatsBonusTree
    }
    
    private fun updateStatPercentBonusList(currentConstruction: IConstruction){
        statPercentBonusTree = getStatPercentBonusList(currentConstruction)
    }

    fun update(currentConstruction: IConstruction = city.cityConstructions.getCurrentConstruction(),
               updateTileStats:Boolean = true,
               updateCivStats:Boolean = true,
               calculateGrowthModifiers:Boolean = true): Unit = timeThis<Unit>("CityStats.update") {

        if (updateTileStats) updateTileStats()

        // We need to compute Tile yields before happiness

        val statsFromBuildings = city.cityConstructions.getStats() // this is performance heavy, so calculate once
        // Also performance-heavy, and previously computed twice with identical inputs (once inside
        // updateBaseStatList, once inside updateCityHappiness) - only baseStatTree is assigned between
        // the two, and neither reads it. Compute once and pass down.
        val statsFromSpecialists = getStatsFromSpecialists(city.population.getNewSpecialists())
        val statsFromUniquesBySource = getStatsFromUniquesBySource()
        // updateBaseStatList merges the stats of the uniques that a building is the source of into statsFromBuildings, whose happiness
        // the needs' local happiness adds up itself from the sources, so they get the buildings' own stats
        val statsFromBuildingsForHappiness = if (city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) statsFromBuildings.clone() else statsFromBuildings
        updateBaseStatList(statsFromBuildings, statsFromSpecialists, statsFromUniquesBySource)
        updateCityHappiness(statsFromBuildingsForHappiness, statsFromSpecialists, statsFromUniquesBySource)
        updateStatPercentBonusList(currentConstruction)

        updateFinalStatList(currentConstruction, calculateGrowthModifiers) // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        val newCurrentCityStats = Stats()
        for (stat in finalStatList.values) newCurrentCityStats.add(stat)
        currentCityStats = newCurrentCityStats

        if (updateCivStats) city.civ.updateStatsForNextTurn()
    }

    private fun updateFinalStatList(currentConstruction: IConstruction, calculateGrowthModifiers: Boolean = true) {
        val newFinalStatList = StatMap() // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        for ((key, value) in baseStatTree.children)
            newFinalStatList[key] = value.totalStats.clone()

        val statPercentBonusesSum = statPercentBonusTree.totalStats
        val baseStatsTotal = baseStatTree.totalStats

        val needsModel = city.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp
        val productionBeforeBonuses = if (needsModel) newFinalStatList.values.map { it.production }.sum() else 0f

        for (entry in newFinalStatList.values)
            entry.production *= statPercentBonusesSum.production.toPercent()

        // Trade routes are added to the yield after the modifiers (CvCity::getYieldRateTimes100), so they are not part of the loops here
        val statsFromTradeRoutes = city.civ.tradeRoutes.getStatsForCity(city)

        // We only add the 'extra stats from production' AFTER we calculate the production INCLUDING BONUSES
        val statsFromProduction = getStatsFromProduction(newFinalStatList.values.map { it.production }.sum() + statsFromTradeRoutes.production)
        if (statsFromProduction != null && !statsFromProduction.isEmpty()) {
            baseStatTree = StatTreeNode().apply {
                children.putAll(baseStatTree.children)
                addStats(statsFromProduction, "Production")
            } // concurrency-safe addition
            newFinalStatList["Construction"] = statsFromProduction
        }

        for (entry in newFinalStatList.values) {
            entry.gold *= statPercentBonusesSum.gold.toPercent()
            entry.culture *= statPercentBonusesSum.culture.toPercent()
            entry.food *= statPercentBonusesSum.food.toPercent()
            entry.faith *= statPercentBonusesSum.faith.toPercent()
        }

        // AFTER we've gotten all the gold stats figured out, only THEN do we plonk that gold into Science
        if (city.getRuleset().modOptions.hasUnique(UniqueType.ConvertGoldToScience)) {
            val amountConverted = (newFinalStatList.values.sumOf { it.gold.toDouble() }
                    * city.civ.tech.goldPercentConvertedToScience).toInt().toFloat()
            if (amountConverted > 0) // Don't want you converting negative gold to negative science yaknow
                newFinalStatList["Gold -> Science"] = Stats(science = amountConverted, gold = -amountConverted)
        }
        for (entry in newFinalStatList.values) {
            entry.science *= statPercentBonusesSum.science.toPercent()
        }
        // CvCity::GetPostModifierYieldRateTimes100: a yield converted to another is the first yield as the sources add it up, before the bonuses, and is not modified
        // by them; the puppet's share, the growth modifiers and the food eaten (below) come after it
        city.forEachMatchingUnique(UniqueType.StatPercentAddedToStat) { unique ->
            val from = Stat.safeValueOf(unique.params[1]) ?: return@forEachMatchingUnique
            val to = Stat.safeValueOf(unique.params[3]) ?: return@forEachMatchingUnique
            if (!city.matchesFilter(unique.params[2])) return@forEachMatchingUnique
            val amount = baseStatsTotal[from] * unique.params[0].toInt() / 100f
            if (amount != 0f) newFinalStatList.add(unique.getSourceNameForUser(), Stats().apply { this[to] = amount })
        }

        val puppetMultiplier = getPuppetYieldMultiplier()
        if (puppetMultiplier != 1f) for (entry in newFinalStatList.values) {
            entry.gold *= puppetMultiplier
            entry.science *= puppetMultiplier
            entry.culture *= puppetMultiplier
            entry.faith *= puppetMultiplier
        }
        if (!statsFromTradeRoutes.isEmpty()) newFinalStatList[TradeRouteManager.STAT_SOURCE] = statsFromTradeRoutes

        for ((unique, statToBeRemoved) in city.getMatchingUniques(UniqueType.NullifiesStat)
            .map { it to Stat.valueOf(it.params[0]) }
            .distinct()
        ) {
            val removedAmount = newFinalStatList.values.sumOf { it[statToBeRemoved].toDouble() }

            newFinalStatList.add(
                unique.getSourceNameForUser(),
                Stats().apply { this[statToBeRemoved] = -removedAmount.toFloat() }
            )
        }

        /* Okay, food calculation is complicated.
        First we see how much food we generate. Then we apply production bonuses to it.
        Up till here, business as usual.
        Then, we deduct food eaten (from the total produced).
        Now we have the excess food, to which "growth" modifiers apply
        Some policies have bonuses for growth only, not general food production. */

        val foodEaten = calcFoodEaten()
        // Vox Populi's needs read the yields at this point: before the food is eaten and the growth modifiers, before the maintenance of the buildings
        if (needsModel && isOwnStats())
            updateNeeds(newFinalStatList, foodEaten, productionBeforeBonuses, statsFromTradeRoutes, currentConstruction)

        // The "Population" entry has no stats, and so is not in the tree, while the citizens give no science (sciencePerPopulation 0) and none of them is unemployed
        newFinalStatList.getOrPut("Population") { Stats() }.food -= foodEaten

        var totalFood = newFinalStatList.values.map { it.food }.sum()
        excessFood = totalFood

        // Apply growth modifier only when positive food
        if (totalFood > 0 && calculateGrowthModifiers) {
            // Since growth bonuses are special, (applied afterwards) they will be displayed separately in the user interface as well.
            // All bonuses except We Love The King do apply even when unhappy
            val growthBonuses = getGrowthBonus(totalFood)
            for (growthBonus in growthBonuses) {
                newFinalStatList.add("[${growthBonus.key}] ([Growth])", growthBonus.value)
            }
            // CvCity::getGrowthMods: the delta of the city's happiness over its unhappiness and the empire's unhappiness count too
            if (needsModel) {
                val happinessGrowth = city.cityStats.needs.growthModifier()
                if (happinessGrowth != 0) newFinalStatList.add("Happiness", Stats(food = happinessGrowth / 100f * totalFood))
            }
            if (city.isWeLoveTheKingDayActive() && (needsModel || city.civ.getHappiness() >= 0)) {
                // We Love The King Day +25%, only if not unhappy (the original's Vox Populi: whatever the happiness)
                val weLoveTheKingFood = Stats(food = totalFood / 4)
                newFinalStatList.add("We Love The King Day", weLoveTheKingFood)
            }
            // The growth modifiers add up to -100% at the most (CvCity::getGrowthMods), no city shrinks of them
            if (needsModel) {
                val foodAfterModifiers = newFinalStatList.values.map { it.food }.sum()
                if (foodAfterModifiers < 0) newFinalStatList.add("Growth modifiers limit", Stats(food = -foodAfterModifiers))
            }
            // recalculate only when all applied - growth bonuses are not multiplicative
            // bonuses can allow a city to grow even with -100% unhappiness penalty, this is intended
            totalFood = newFinalStatList.values.map { it.food }.sum()
        }

        val buildingsMaintenance = getBuildingMaintenanceCosts() // this is AFTER the bonus calculation!
        newFinalStatList["Maintenance"] = Stats(gold = -buildingsMaintenance.toInt().toFloat())

        if (canConvertFoodToProduction(totalFood, currentConstruction)) {
            newFinalStatList[EXCESS_FOOD_TO_PRODUCTION] =
                Stats(production = getProductionFromExcessiveFood(totalFood), food = -totalFood)
        }

        val growthNullifyingUnique = city.getMatchingUniques(UniqueType.NullifiesGrowth).firstOrNull()
        if (growthNullifyingUnique != null) {
            // Does not nullify negative growth (starvation)
            val currentGrowth = newFinalStatList.values.sumOf { it[Stat.Food].toDouble() }
            if (currentGrowth > 0)
                newFinalStatList.add(
                    growthNullifyingUnique.getSourceNameForUser(),
                    Stats(food = -currentGrowth.toFloat())
                )
        }

        if (city.isInResistance())
            newFinalStatList.clear()  // NOPE

        // Anarchy after a change of ideology: no income, only the costs
        val inAnarchy = city.civ.hasFlag(CivFlags.Anarchy.name)
        if (inAnarchy) for (entry in newFinalStatList.values) {
            entry.production = 0f
            entry.science = 0f
            entry.culture = 0f
            entry.faith = 0f
            entry.gold = minOf(entry.gold, 0f)
        }

        if (!inAnarchy && newFinalStatList.values.map { it.production }.sum() < 1)  // Minimum production for things to progress
            newFinalStatList["Production"] = Stats(production = 1f)
        finalStatList = newFinalStatList
    }

    /**
     *  Gives the city's [needs] what the city yields: the sums of [finalStatList] as they are before the food is eaten and before the maintenance of the
     *  buildings are deducted (CvCity::getYieldRateTimes100, in whole hundredths), the production with the trade routes' part told apart for the medians.
     *  Not the production penalty of unhappiness on a unit under construction, which Distress does not read ([NeedYields.production]).
     *  @param productionBeforeBonuses the production of the sources before the production bonuses (and the penalty) multiplied it
     *  @param statsFromTradeRoutes what the city's trade routes give, already in [finalStatList]
     */
    private fun updateNeeds(finalStatList: StatMap, foodEaten: Float, productionBeforeBonuses: Float, statsFromTradeRoutes: Stats, currentConstruction: IConstruction) {
        val unitPenalty = if (currentConstruction is BaseUnit) needs.unitProductionPenalty(currentConstruction) else 0
        val production = finalStatList.values.map { it.production }.sum() - productionBeforeBonuses * unitPenalty / 100f
        fun hundredths(value: Float) = (value * 100f).roundToInt()
        needs.update(NeedYields(
            foodBeforeConsumption = hundredths(finalStatList.values.map { it.food }.sum()),
            foodConsumption = hundredths(foodEaten),
            production = hundredths(production),
            productionWithoutTrade = hundredths(production - statsFromTradeRoutes.production),
            gold = hundredths(finalStatList.values.map { it.gold }.sum()),
            science = hundredths(finalStatList.values.map { it.science }.sum()),
            culture = hundredths(finalStatList.values.map { it.culture }.sum()),
            isFoodProduction = currentConstruction is INonPerpetualConstruction
                && currentConstruction.hasUnique(UniqueType.ConvertFoodToProductionWhenConstructed)
        ))
        localHappiness = needs.localHappiness().toFloat()
    }

    @Readonly
    fun canConvertFoodToProduction(food: Float, currentConstruction: IConstruction): Boolean {
        return (food > 0
            && currentConstruction is INonPerpetualConstruction
            && currentConstruction.hasUnique(UniqueType.ConvertFoodToProductionWhenConstructed))
    }

    /**
     * Calculate the conversion of the excessive food to production when
     * [UniqueType.ConvertFoodToProductionWhenConstructed] is at front of the build queue
     * @param food is amount of excess Food generates this turn
     * See for details: https://civilization.fandom.com/wiki/Settler_(Civ5)
     * @see calcFoodEaten as well for Food consumed this turn
     */
    @Pure
    fun getProductionFromExcessiveFood(food : Float): Float {
        return if (food >= 4.0f ) 2.0f + (food / 4.0f).toInt()
          else if (food >= 2.0f ) 2.0f
          else if (food >= 1.0f ) 1.0f
        else 0.0f
    }

    @Readonly
    private fun calcFoodEaten(): Float {
        var foodEatenBySpecialists = 2f * city.population.getNumberOfSpecialists()
        var foodEaten = city.population.population.toFloat() * 2 - foodEatenBySpecialists

        foodEaten += foodEatenBySpecialists

        city.forEachMatchingUnique(UniqueType.FoodConsumptionByPopulation) { unique ->
            if (city.matchesFilter(unique.params[2])) {
                val foodEatenByPopulationFilter = 2f * city.population.getPopulationFilterAmount(unique.params[1])
                foodEaten -= foodEatenByPopulationFilter * (1f - unique.params[0].toPercent())
            }
        }
        
        return foodEaten
    }

    //endregion
}
