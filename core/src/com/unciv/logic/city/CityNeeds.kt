package com.unciv.logic.city

import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The needs of a city that a yield decides, and the one religion causes (the parameter type `need` of [UniqueType.NeedReduction]) */
enum class NeedType(val label: String) {
    /** Food before consumption plus production (CvCity::GetDistress) */
    Distress("Distress"),
    /** Gold (CvCity::GetPoverty) */
    Poverty("Poverty"),
    /** Science (CvCity::GetIlliteracy) */
    Illiteracy("Illiteracy"),
    /** Culture (CvCity::GetBoredom) */
    Boredom("Boredom"),
    /** The citizens that do not follow the city's majority religion (CvCity::GetUnhappinessFromReligiousUnrest) */
    ReligiousUnrest("Religious Unrest"),
}

/**
 *  What a city yields that its needs read, in hundredths, as the original reads it (CvCity::getYieldRateTimes100): the sums of
 *  [CityStats.finalStatList] before the growth modifiers and before the building maintenance, set by [CityStats.update].
 *
 *  @property foodBeforeConsumption CvCity::getFoodPerTurnBeforeConsumptionTimes100, the trade routes' food included
 *  @property foodConsumption CvCity::getFoodConsumptionTimes100
 *  @property production the production with the trade routes' and without the production penalty of unhappiness, which Distress does not read (departure from
 *          the source, where a penalty on the unit being built feeds back into Distress: CvCity::getYieldRateTimes100 includes the modifier of the item)
 *  @property productionWithoutTrade the production without the trade routes, what the medians read (`getYieldRateTimes100(YIELD_PRODUCTION, true)`)
 *  @property isFoodProduction the city builds something that turns food into production, so starving is no famine (CvCity::isFoodProduction)
 */
class NeedYields(
    val foodBeforeConsumption: Int = 0,
    val foodConsumption: Int = 0,
    val production: Int = 0,
    val productionWithoutTrade: Int = 0,
    val gold: Int = 0,
    val science: Int = 0,
    val culture: Int = 0,
    val isFoodProduction: Boolean = false,
)

/**
 *  The needs and the happiness of one city, Vox Populi's per-city happiness system (CvCity::GetUnhappinessAggregated,
 *  CvCity::GetLocalHappiness, CvCity::updateNetHappiness), only with [com.unciv.models.ModConstants.cityNeedsLikeVp].
 *  Owned by the real [CityStats] of the city, and never saved: everything is a function of the saved state and of the medians of
 *  [com.unciv.logic.GameInfo.needsMedians]. [CityStats.update] fills it, and [com.unciv.logic.civilization.CivNeeds] adds what only the
 *  civilization knows (the city's share of the empire's happiness and unhappiness) and has the city add it up again.
 *
 *  Needs are numbers of unhappy citizens. A yield need is ceil(population - yield / (median per citizen x need modifiers)), less the flat reductions
 *  of the city and the empire, within 0 and the population (CvCity::GetDistress and its siblings). Cities that count here are those of major civilizations;
 *  a puppet, a city in resistance or one being razed has no yield needs.
 *
 *  Departures from Vox Populi: the medians, the tech need modifier and the empire size modifier are not cached per city (VP refreshes them every turn and
 *  when the population changes) but read live; the median modifiers (`BasicNeedsMedianModifier` ... have no data in VP), `NeedsModifierFromAirUnits`,
 *  the trait modifiers of the needs (`WLTKDUnhappinessNeedsMod`, `NoConnectionUnhappiness`, `NoReligiousStrife`), the policy `NoUnhappyIsolation`,
 *  `GarrisonsOccupiedUnhappinessMod`, the population modifier of traits and `Building.Unhappiness` (no rows with data in VP) have no counterpart
 *  yet; the processes that lower a need modifier (`ProductionToYieldModifier` of PROCESS_FOOD, PROCESS_CULTURE: a yield modifier of the city
 *  in VP, not what Unciv's production-to-stat construction is) are not ported; no city gets the happiness of a one-city challenge;
 *  `GetMinimumFood` (a policy that makes a city not starve) does not exist.
 */
class CityNeeds(private val city: City) {
    //region Fields, all transient

    /** What the city yields, see [NeedYields] */
    var yields = NeedYields()
        private set

    var distress = 0
        private set
    var poverty = 0
        private set
    var illiteracy = 0
        private set
    var boredom = 0
        private set
    var religiousUnrest = 0
        private set
    /** CvCity::GetUnhappinessFromOccupation */
    var occupation = 0
        private set
    /** CvCity::GetUnhappinessFromFamine */
    var famine = 0
        private set
    /** CvCity::GetUnhappinessFromPillagedTiles */
    var pillaged = 0
        private set
    /** CvCity::GetUnhappinessFromIsolation */
    var isolation = 0
        private set
    /** CvCity::getUnhappinessFromSpecialists, the "urbanization" a city pays on top of the cap */
    var specialists = 0
        private set

    /** CvCity::m_iUnhappinessFromEmpire, the city's share of the empire's unhappiness, set by [com.unciv.logic.civilization.CivNeeds] */
    var unhappinessFromEmpire = 0
        internal set
    /** CvCity::m_iHappinessFromEmpire, the city's share of the empire's pool of happiness, set by [com.unciv.logic.civilization.CivNeeds] */
    var happinessFromEmpire = 0
        internal set

    /** The happiness of the city's buildings, policies, beliefs and the capital default, not the empire's share: CvCity::GetLocalHappiness(0, true) */
    var happinessWithoutEmpire = 0
        private set

    /** CvCity::GetUnhappinessAggregated */
    var aggregated = 0
        private set
    /** CvCity::m_iHappinessDelta: the local happiness less the aggregated unhappiness, or -population when the city is in resistance or being razed. Can be positive. */
    var delta = 0
        private set

    /** The unhappy citizens by source, the biggest first, without the sources that cost nothing (the cap on the population is not applied). Source names are the ones of the UI */
    var breakdown = LinkedHashMap<String, Int>()
        private set

    //endregion
    //region Conditions

    @Readonly private fun isActive() = city.civ.isMajorCiv

    /** CityStrategyAIHelpers::IsTestCityStrategy_IsPuppetAndAnnexable: a puppet of a civilization that may annex (not Venice's) */
    @Readonly
    fun isPuppetAndAnnexable() = city.isPuppet && !city.civ.hasUnique(UniqueType.MayNotAnnexCities)

    @Readonly private fun isRazingOrInResistance() = city.isBeingRazed || city.isInResistance()

    /** `IsOccupied() && !IsNoOccupiedUnhappiness()`: the city is an occupied one ([City.isOccupied], no puppet) without a Courthouse or the like */
    @Readonly private fun isOccupiedWithUnhappiness() = city.isOccupied() && !city.containsBuildingUnique(UniqueType.RemovesAnnexUnhappiness)

    /** CvPlayer::GetNumRealCities counts these: no puppet that may be annexed and no occupied city (that is, one with the extra unhappiness), but cities in resistance do count */
    @Readonly
    fun isRealCity() = !isPuppetAndAnnexable() && !isOccupiedWithUnhappiness()

    /**
     *  The cities that give happiness of their own and get a share of the empire's (CvCity::GetLocalHappiness and CvPlayer::DistributeHappinessToCities
     *  skip the others): every city of a major civilization but a puppet that may be annexed (a puppet of Venice is a city like any), one in resistance
     *  or being razed, and an occupied one.
     */
    @Readonly
    fun givesAndGetsHappiness() = isActive() && !isPuppetAndAnnexable() && !isRazingOrInResistance() && !isOccupiedWithUnhappiness()

    //endregion
    //region Need modifiers

    private val constants get() = city.civ.gameInfo.ruleset.modOptions.constants.needs

    /** CvPlayer::GetTechNeedModifier: +1 percent per technology above the median of the civilizations, -2 per technology below it (with the defaults) */
    @Readonly
    fun techNeedModifier(): Int {
        val techs = city.civ.tech.researchedTechnologies.size
        var percentResearched = techs * 100 / max(1, city.civ.gameInfo.ruleset.technologies.size)
        percentResearched = percentResearched * constants.techNeedPercentResearched / 100
        var difference = techs - city.civ.gameInfo.needsMedians.techs
        if (difference > 0) difference = difference * constants.techNeedPerTechAboveMedian / 100
        else if (difference < 0) difference = difference * -constants.techNeedPerTechBelowMedian / 100
        return percentResearched + difference
    }

    /**
     *  CvCity::GetEmpireSizeModifier: percent points the needs rise by for the size of the empire: per city but the capital and the
     *  puppets, and per citizen outside the puppets, scaled down on a large map by the world size's percent of cities that cause unhappiness
     *  (at most 100 percent, so a small map's 115 to 150 does not count more), then by the difficulty, within 0 and the cap.
     */
    @Readonly
    fun empireSizeModifier(): Int {
        val civ = city.civ
        val needs = constants
        var numPuppets = 0
        var citizens = 0
        for (other in civ.cities)
            if (other.isPuppet) numPuppets++ else citizens += other.population.population
        val byCities = max(0, (civ.cities.size - numPuppets - 1) * needs.empireSizeNeedPerCity / 100)
        val byPopulation = max(0, citizens * needs.empireSizeNeedPerPopulation / 1000)
        var modifier = byCities + byPopulation

        val worldPercent = civ.gameInfo.ruleset.ai.worldSizeForTiles(civ.gameInfo.tileMap.tileList.size).numCitiesUnhappinessPercent
        modifier = modifier * min(100, worldPercent) / 100

        val difficulty = civ.getDifficulty()
        val difficultyPercent = difficulty.empireSizeNeedModifier +
            if (civ.isHuman()) 0 else civ.gameInfo.getDifficulty().aiEmpireSizeNeedModifier
        modifier = modifier * (100 + difficultyPercent) / 100
        return modifier.coerceIn(0, needs.empireSizeNeedCap)
    }

    /** CvCity::GetReducedEmpireSizeModifier: [empireSizeModifier] less what the city's buildings and the empire's policies take off, within 0 and itself */
    @Readonly
    fun reducedEmpireSizeModifier(): Int {
        val full = empireSizeModifier()
        var reduction = 0
        city.forEachMatchingUnique(UniqueType.EmpireSizeNeedReduction) { unique ->
            if (city.matchesFilter(unique.params[1])) reduction += unique.params[0].toInt()
        }
        return (full - reduction).coerceIn(0, full)
    }

    /**
     *  CvCity::GetAllNeedsModifier (= GetTotalNeedModifierForYield, the modifiers of the yields being 0): percent points all needs of the city rise by:
     *  the capital, the tech lead, the city's size, the empire's size, and the difficulty's.
     */
    @Readonly
    fun allNeedsModifier(): Int {
        val civ = city.civ
        var modifier = if (city.isCapital()) constants.capitalNeedModifier else 0
        modifier += techNeedModifier()
        modifier += city.population.population * constants.citySizeNeedModifier / 100
        modifier += reducedEmpireSizeModifier()
        modifier += civ.getDifficulty().populationNeedModifier +
            if (civ.isHuman()) 0 else civ.gameInfo.getDifficulty().aiPopulationNeedModifier
        return modifier
    }

    @Readonly
    private fun globalMedian(type: NeedType): Int {
        val medians = city.civ.gameInfo.needsMedians
        return when (type) {
            NeedType.Distress -> medians.basic
            NeedType.Poverty -> medians.gold
            NeedType.Illiteracy -> medians.science
            NeedType.Boredom -> medians.culture
            NeedType.ReligiousUnrest -> 0
        }
    }

    /**
     *  CvCity::GetBasicNeedsMedian, GetGoldMedian, GetScienceMedian, GetCultureMedian: the median of the game (hundredths of a yield per citizen) raised by
     *  [allNeedsModifier], times the base modifier, in 32-bit floats as the source; 0 when it is not above 0 (then the need is 0).
     *
     *  Source quirk, not carried over: GetCultureMedian reads the Illiteracy base modifier, not the Boredom one (both 100 in Vox Populi).
     */
    @Readonly
    fun modifiedMedian(type: NeedType): Float = modifiedMedian(type, allNeedsModifier())

    @Readonly
    private fun modifiedMedian(type: NeedType, needsModifier: Int): Float {
        var median = globalMedian(type).toFloat()
        median *= (100 + needsModifier)
        median /= 100
        median *= max(constants.medianBaseModifier, 0)
        median /= 100
        return if (median <= 0f) 0f else median
    }

    //endregion
    //region The needs

    /** The flat reductions of [type] that the city's buildings and the empire's policies give together */
    @Readonly
    private fun reduction(type: NeedType): Int {
        var total = 0
        city.forEachMatchingUnique(UniqueType.NeedReduction) { unique ->
            if (unique.params[0] == type.label && city.matchesFilter(unique.params[2])) total += unique.params[1].toInt()
        }
        return total
    }

    /**
     *  CvCity::GetDistress, GetPoverty, GetIlliteracy, GetBoredom: the yield need of [type] (not religious unrest), in unhappy citizens.
     *  @param growth citizens added to the population: 1 is `bForceRecalc`, the need after the city's next growth
     *  @param extraYield100 yield added to the one the need reads, in hundredths (`iAssumedExtraYieldRate`, the AI weighs buildings with it)
     */
    @Readonly
    fun yieldNeed(type: NeedType, growth: Int = 0, extraYield100: Int = 0): Int {
        if (type == NeedType.ReligiousUnrest) return religiousUnrest()
        if (!isActive() || city.isPuppet || isRazingOrInResistance()) return 0
        return yieldNeed(type, growth, extraYield100, allNeedsModifier())
    }

    @Readonly
    private fun yieldNeed(type: NeedType, growth: Int, extraYield100: Int, needsModifier: Int): Int {
        val population = city.population.population + growth
        val median = modifiedMedian(type, needsModifier)
        if (median <= 0f) return 0 // also avoids a division by zero
        val yield100 = extraYield100 + when (type) {
            NeedType.Distress -> yields.foodBeforeConsumption + yields.production
            NeedType.Poverty -> yields.gold
            NeedType.Illiteracy -> yields.science
            else -> yields.culture
        }
        val raw = ceil(population - yield100 / median).toInt().coerceIn(0, population)
        return (raw - reduction(type)).coerceIn(0, population)
    }

    /** CvCity::getPotentialUnhappinessWithGrowthVal: the unhappy citizens the four yield needs add when the city grows by one citizen */
    @Readonly
    fun potentialUnhappinessWithGrowth() = listOf(NeedType.Distress, NeedType.Poverty, NeedType.Illiteracy, NeedType.Boredom)
        .sumOf { yieldNeed(it, growth = 1) - yieldNeed(it) }

    /**
     *  CvCity::GetUnhappinessFromReligiousUnrest: half an unhappy citizen (truncated) for each citizen that does not follow the majority religion, if
     *  that is a real religion and not a pantheon, raised by [allNeedsModifier], less the flat reductions, within 0 and the population.
     *  The source's comment says "rounded up", its cast truncates.
     */
    @Readonly
    fun religiousUnrest(): Int {
        val minorities = religiousMinorities()
        return if (minorities <= 0) 0 else religiousUnrest(minorities, allNeedsModifier())
    }

    /** The citizens that do not follow the majority religion, if it is a real religion, else 0 */
    @Readonly
    private fun religiousMinorities(): Int {
        if (!isActive() || city.isPuppet || isRazingOrInResistance() || !city.civ.gameInfo.isReligionEnabled()) return 0
        val majority = city.religion.getMajorityReligion() ?: return 0
        if (!majority.isMajorReligion()) return 0
        return city.population.population - city.religion.getFollowersOf(majority.name)
    }

    @Readonly
    private fun religiousUnrest(minorities: Int, needsModifier: Int): Int {
        var unhappiness = minorities * constants.unhappinessPerReligiousMinority
        unhappiness *= (100 + needsModifier)
        unhappiness /= 100
        return (unhappiness.toInt() - reduction(NeedType.ReligiousUnrest)).coerceIn(0, city.population.population)
    }

    /** CvCity::GetUnhappinessFromPillagedTiles: half an unhappy citizen (truncated) for each pillaged improvement on the city's tiles, at most the population */
    @Readonly
    private fun pillagedTiles(): Int {
        if (city.isPuppet || isRazingOrInResistance()) return 0
        val pillagedTiles = city.getTiles().count { it.improvementIsPillaged }
        return (pillagedTiles * constants.unhappinessPerPillagedTile).toInt().coerceIn(0, city.population.population)
    }

    /** CvCity::GetUnhappinessFromOccupation: the citizens of an occupied city, unless its Courthouse (or the like) says no, at most the population */
    @Readonly
    private fun occupationUnhappiness(): Int {
        if (city.isPuppet || !isOccupiedWithUnhappiness()) return 0
        val population = city.population.population
        return (population * constants.unhappinessPerOccupiedPopulation).toInt().coerceIn(0, population)
    }

    /** CvCity::GetUnhappinessFromFamine: the deficit of food before the growth modifiers, if the city does not turn food into production, at most the population */
    @Readonly
    private fun famineUnhappiness(): Int {
        if (city.isPuppet || isRazingOrInResistance()) return 0
        val difference = (yields.foodBeforeConsumption - yields.foodConsumption) / 100
        if (difference >= 0 || yields.isFoodProduction) return 0
        return (-difference * constants.unhappinessPerStarvingPop).toInt().coerceIn(0, city.population.population)
    }

    /**
     *  CvCity::GetUnhappinessFromIsolation: a third of the citizens (truncated) of a city that is not the capital and is not connected to it by a road, harbor
     *  or railroad, unless one of its trade routes goes to the capital or to a city that is connected to it.
     */
    @Readonly
    private fun isolationUnhappiness(): Int {
        if (city.isPuppet || isRazingOrInResistance() || city.isCapital() || city.isConnectedToCapital()) return 0
        val connected = city.civ.cities.filter { it.isCapital() || it.isConnectedToCapital() }
        val routes = city.civ.tradeRoutes.routes
        for (other in connected)
            if (routes.any { (it.origin == city.location && it.destination == other.location) || (it.origin == other.location && it.destination == city.location) })
                return 0
        val population = city.population.population
        return (population * constants.unhappinessPerIsolatedPop).toInt().coerceIn(0, population)
    }

    /**
     *  CvCity::getUnhappinessFromSpecialists: what the specialists cost, after the pop cap of the other sources: nothing in a puppet, else
     *  the specialists less the free ones ([UniqueType.NoUnhappinessFromSpecialists]), less the part the unique
     *  `[-50]% Unhappiness from [Specialists] ...` takes off, rounding up what is left (the original's `HalfSpecialistUnhappiness`, (n + 1) / 2),
     *  times [com.unciv.models.ModConstants.NeedsConstants.unhappinessPerSpecialist] / 100.
     */
    @Readonly
    private fun specialistUnhappiness(): Int = specialistUnhappiness(city.population.getNumberOfSpecialists())

    /** CvCity::getUnhappinessFromSpecialists(count): what [specialistCount] specialists would cost the city (the AI governor asks it for one more or one fewer) */
    @Readonly
    fun specialistUnhappiness(specialistCount: Int): Int {
        if (city.isPuppet) return 0
        var count = specialistCount
        if (count > 0) {
            var free = 0
            city.forEachMatchingUnique(UniqueType.NoUnhappinessFromSpecialists) { unique ->
                if (city.matchesFilter(unique.params[1])) free += unique.params[0].toInt()
            }
            if (free > 0) count -= min(free, count)
        }
        var remaining = count.toFloat()
        city.forEachMatchingUnique(UniqueType.UnhappinessFromPopulationTypePercentageChange) { unique ->
            if (unique.params[1] == "Specialists" && city.matchesFilter(unique.params[2])) remaining += unique.params[0].toFloat() / 100f * count
        }
        count = ceil(remaining).toInt().coerceAtLeast(0)
        return count * constants.unhappinessPerSpecialist / 100
    }

    //endregion
    //region Local happiness

    /** The happiness of the one-citizen steps of [UniqueType.HappinessPerCitizens], for a city with [population] citizens */
    @Readonly
    private fun happinessPerCitizens(population: Int): Int {
        var total = 0
        city.forEachMatchingUnique(UniqueType.HappinessPerCitizens) { unique ->
            if (city.matchesFilter(unique.params[2])) total += unique.params[0].toInt() * population / unique.params[1].toInt()
        }
        return total
    }

    /** CvCity::GetPotentialHappinessWithGrowthVal: the local happiness the city gains when it grows by one citizen (only what depends on the citizens) */
    @Readonly
    fun happinessWithGrowth(growth: Int = 1): Int {
        if (!givesAndGetsHappiness()) return 0
        val population = city.population.population
        return happinessPerCitizens(population + growth) - happinessPerCitizens(population)
    }

    /**
     *  CvCity::GetLocalHappiness without the empire's share, as [CityStats.happinessList]: the happiness of the buildings, of the specialists and
     *  of the tiles, of every policy and belief that gives the city some, and of the uniques of the needs ([UniqueType.HappinessFromSpecialists],
     *  [UniqueType.HappinessPerCitizens], [UniqueType.HappinessPerGreatWorks], [UniqueType.HappinessPerTradeRoute]), and the capital's default of the difficulty
     *  (CvCity::GetLocalHappiness: HappinessDefaultCapital). Nothing limits it to the population. A city that gives none (see [givesAndGetsHappiness])
     *  has the list, but [happinessWithoutEmpire] is 0.
     *
     *  @param statsFromBuildings the stats of the city's buildings, which hold their happiness (and what policies and beliefs give per building)
     *  @param statsFromSpecialists the stats of the city's specialists
     *  @param statsFromTiles the stats of the tiles
     *  @param statsFromUniquesBySource the stats the city gets from uniques, by source
     */
    fun updateLocalHappiness(
        statsFromBuildings: StatTreeNode,
        statsFromSpecialists: Stats,
        statsFromTiles: Stats,
        statsFromUniquesBySource: StatTreeNode
    ): LinkedHashMap<String, Float> {
        val list = LinkedHashMap<String, Float>()
        val civ = city.civ

        list["Buildings"] = statsFromBuildings.totalStats.happiness.toInt().toFloat()
        val fromSpecialists = statsFromSpecialists.happiness.toInt().toFloat()
        if (fromSpecialists > 0) list["Specialists"] = fromSpecialists
        list["Tile yields"] = statsFromTiles.happiness
        for ((source, stats) in statsFromUniquesBySource.children) {
            val happiness = stats.totalStats.happiness
            if (happiness != 0f) list[source] = (list[source] ?: 0f) + happiness
        }

        var perSpecialist = 0
        val specialistCount = city.population.getNumberOfSpecialists()
        city.forEachMatchingUnique(UniqueType.HappinessFromSpecialists) { unique ->
            if (city.matchesFilter(unique.params[2])) perSpecialist += unique.params[0].toInt() * min(specialistCount, unique.params[1].toInt())
        }
        if (perSpecialist != 0) list["Specialists (policies)"] = perSpecialist.toFloat()

        val perCitizens = happinessPerCitizens(city.population.population)
        if (perCitizens != 0) list["Citizens"] = perCitizens.toFloat()

        var perGreatWorks = 0
        city.forEachMatchingUnique(UniqueType.HappinessPerGreatWorks) { unique ->
            if (city.matchesFilter(unique.params[2]))
                perGreatWorks += unique.params[0].toInt() * (city.greatWorks.getNumWorks() / unique.params[1].toInt())
        }
        if (perGreatWorks != 0) list["Great Works"] = perGreatWorks.toFloat()

        var perTradeRoute = 0
        city.forEachMatchingUnique(UniqueType.HappinessPerTradeRoute) { unique ->
            if (city.matchesFilter(unique.params[1]))
                perTradeRoute += unique.params[0].toInt() * civ.tradeRoutes.routes.count { it.origin == city.location }
        }
        if (perTradeRoute != 0) list["Trade routes"] = perTradeRoute.toFloat()

        if (city.isCapital()) {
            val difficulty = civ.getDifficulty()
            val capital = difficulty.baseHappinessCapital + if (civ.isHuman()) 0 else civ.gameInfo.getDifficulty().aiBaseHappinessCapital
            if (capital != 0) list["Capital"] = capital.toFloat()
        }

        happinessWithoutEmpire = if (givesAndGetsHappiness()) list.values.sum().roundToInt() else 0
        return list
    }

    /** CvCity::GetLocalHappiness: [happinessWithoutEmpire] and the city's share of the empire's happiness; 0 for a city that gives and gets none */
    @Readonly
    fun localHappiness() = if (givesAndGetsHappiness()) happinessWithoutEmpire + happinessFromEmpire else 0

    //endregion
    //region Aggregating

    /**
     *  Computes the city's needs from what it yields, then adds them up ([aggregate]). Called by [CityStats.update] after the yields are known,
     *  with the empire's shares that [com.unciv.logic.civilization.CivNeeds] gave last time. A city of a civilization that is no major civilization has none.
     */
    fun update(yields: NeedYields) {
        this.yields = yields
        if (!isActive()) {
            distress = 0; poverty = 0; illiteracy = 0; boredom = 0; religiousUnrest = 0
            occupation = 0; famine = 0; pillaged = 0; isolation = 0; specialists = 0
            aggregate()
            return
        }
        val needsModifier = allNeedsModifier()
        val cityHasNoYieldNeeds = city.isPuppet || isRazingOrInResistance()
        distress = if (cityHasNoYieldNeeds) 0 else yieldNeed(NeedType.Distress, 0, 0, needsModifier)
        poverty = if (cityHasNoYieldNeeds) 0 else yieldNeed(NeedType.Poverty, 0, 0, needsModifier)
        illiteracy = if (cityHasNoYieldNeeds) 0 else yieldNeed(NeedType.Illiteracy, 0, 0, needsModifier)
        boredom = if (cityHasNoYieldNeeds) 0 else yieldNeed(NeedType.Boredom, 0, 0, needsModifier)
        val minorities = religiousMinorities()
        religiousUnrest = if (minorities <= 0) 0 else religiousUnrest(minorities, needsModifier)
        occupation = occupationUnhappiness()
        famine = famineUnhappiness()
        pillaged = pillagedTiles()
        isolation = isolationUnhappiness()
        specialists = specialistUnhappiness()
        aggregate()
    }

    /**
     *  CvCity::GetUnhappinessAggregated and CvCity::updateNetHappiness from the stored needs and shares. A city in resistance or being razed
     *  is unhappy as a whole and its delta is -population; a puppet has population / 4 unhappy citizens (and, for a Venice puppet, the other part of the
     *  local happiness), and a puppet that may be annexed has no delta; any other city adds its sources, caps the sum at the population (Vox Populi
     *  does not uncap it) and adds the specialists, whom the cap does not cover.
     */
    fun aggregate() {
        val population = city.population.population
        val sources = LinkedHashMap<String, Int>()
        if (!isActive()) {
            aggregated = 0
            delta = 0
        } else if (isRazingOrInResistance()) {
            aggregated = population
            delta = -population
            sources[if (city.isBeingRazed) "Razing" else "Resistance"] = population
        } else if (city.isPuppet) {
            aggregated = population / max(1, constants.puppetCitizensPerUnhappiness)
            delta = if (isPuppetAndAnnexable()) 0 else localHappiness() - aggregated
            sources["Puppet"] = aggregated
        } else {
            sources["Occupation"] = occupation
            sources["Empire"] = unhappinessFromEmpire
            sources["Isolation"] = isolation
            sources[NeedType.ReligiousUnrest.label] = religiousUnrest
            sources["Pillaged tiles"] = pillaged
            sources[NeedType.Poverty.label] = poverty
            sources[NeedType.Illiteracy.label] = illiteracy
            sources[NeedType.Boredom.label] = boredom
            sources["Starvation"] = famine
            sources[NeedType.Distress.label] = distress
            sources["Urbanization"] = specialists
            val sum = occupation + unhappinessFromEmpire + isolation + religiousUnrest + pillaged + poverty + illiteracy + boredom + famine + distress
            aggregated = min(sum, population) + specialists
            delta = localHappiness() - aggregated
        }
        breakdown = LinkedHashMap(sources.entries.filter { it.value > 0 }.sortedByDescending { it.value }.associate { it.key to it.value })
    }

    //endregion
    //region Effects

    /**
     *  The percent production of a [unit] changes by in this city because of unhappiness (CvCity::getProductionModifier(UnitTypes)): a combat unit
     *  `delta x -10 x -1` while the delta is below 0, a settler the same and the empire's penalty on top ([com.unciv.logic.civilization.CivNeeds.settlerPenalty]),
     *  at most -75. Other units are not affected. 0 or less.
     */
    @Readonly
    fun unitProductionPenalty(unit: BaseUnit): Int {
        if (!isActive()) return 0
        val needs = constants
        val penalty = when {
            unit.strength > 0 || unit.rangedStrength > 0 || unit.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals) ->
                min(0, delta * needs.localUnhappyCombatUnitPenalty * -1)
            unit.isCityFounder() ->
                min(0, delta * needs.localUnhappySettlerPenalty * -1) + city.civ.stats.needs.settlerPenalty
            else -> return 0
        }
        return max(penalty, needs.maxUnitProductionPenalty)
    }

    /**
     *  The percent the gold and faith price of [unit] rises by because of unhappiness (CvCity::GetPurchaseCost, the settler branch: the same number
     *  as the production penalty, flipped). Combat units pay no more for unhappiness. 0 or more.
     */
    @Readonly
    fun settlerPurchaseSurcharge(unit: BaseUnit): Int = if (unit.isCityFounder()) -unitProductionPenalty(unit) else 0

    /**
     *  The growth modifier of happiness in percent (CvCity::getGrowthMods): the delta times 2 (above 0) or 10 (below), while the empire is unhappy without
     *  a bonus and with [com.unciv.logic.civilization.CivNeeds.growthPenalty] added, within -100 and 100.
     */
    @Readonly
    fun growthModifier(): Int {
        if (!isActive()) return 0
        val needs = constants
        var modifier = if (delta > 0) delta * needs.localHappinessGrowthPerPoint else delta * needs.localUnhappinessGrowthPerPoint
        val civNeeds = city.civ.stats.needs
        if (civNeeds.isUnhappy()) {
            if (modifier > 0) modifier = 0
            modifier += civNeeds.growthPenalty
        }
        return modifier.coerceIn(-100, 100)
    }

    //endregion
}
