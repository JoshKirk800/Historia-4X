package com.unciv.logic.civilization

import com.unciv.logic.city.CityNeeds
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 *  The empire's side of Vox Populi's happiness system, only with [com.unciv.models.ModConstants.cityNeedsLikeVp]: the pool of happiness
 *  that is handed to the cities, the empire's unhappiness that is shared among them, and the approval rating that tells how the empire is doing
 *  (CvPlayer::DoUpdateTotalHappiness, DistributeHappinessToCities, DoUpdateTotalUnhappiness, CvCity::UpdateUnhappinessFromEmpire,
 *  CvPlayer::CalculateNetHappiness). Owned by [com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn], recomputed in
 *  [Civilization.updateStatsForNextTurn], never saved.
 *
 *  The stat Happiness of the civilization is the approval rating less the unhappy threshold ([Civilization.getHappiness]), so 0 is the line
 *  under which the empire is unhappy and the conditionals on the Happiness stat keep their meaning.
 *
 *  Departures from Vox Populi: the pool is the existing happiness breakdown of Unciv without its cities' part and without public opinion
 *  ([com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.getHappinessBreakdown]), which keeps "Transportation Upkeep" and the sources that Unciv has
 *  instead of VP's (religion, natural wonders, city-states, uniques); war weariness is a hook that is always 0 ([warWeariness], decision O2: the package of the
 *  military rules gives it its value); where the source goes by city id (ties in the distribution, who gets the remainder of the unhappiness) the order
 *  of [Civilization.cities] decides.
 */
class CivNeeds(private val civ: Civilization) {
    private val constants get() = civ.gameInfo.ruleset.modOptions.constants.needs

    /** CvPlayer::m_iHappiness: what the empire has to give its cities, and the entries it is made of (Unciv's happiness breakdown without the cities and public opinion) */
    var pool = 0
        private set
    var poolBreakdown: Map<String, Float> = emptyMap()
        private set

    /** CvPlayer::m_iUnhappiness: the unhappiness of the empire as a whole (public opinion, war weariness), shared among the cities */
    var unhappinessPool = 0
        private set

    /** The citizens' happiness and unhappiness of all cities added up (CvPlayer::GetHappinessFromCitizenNeeds, GetUnhappinessFromCitizenNeeds) */
    var happyCitizens = 0
        private set
    var unhappyCitizens = 0
        private set

    private var computedApproval: Int? = null

    /** CvPlayer::GetExcessHappiness: the approval rating in percent, 100 without unhappiness, else min(200, happiness x 100 / unhappiness) / 2; the unhappy threshold (50) for an empire with no needs */
    val approval: Int get() = computedApproval ?: constants.unhappyThreshold

    /** CvPlayer::GetUnhappinessFromWarWeariness: 0 until the military rules arrive (decision O2) */
    @Readonly
    @Suppress("FunctionOnlyReturningConstant")
    fun warWeariness() = 0

    //region Thresholds and effects

    /** CvPlayer::IsEmpireUnhappy */
    @Readonly fun isUnhappy() = approval < constants.unhappyThreshold
    /** CvPlayer::IsEmpireVeryUnhappy */
    @Readonly fun isVeryUnhappy() = approval < constants.veryUnhappyThreshold
    /** CvPlayer::IsEmpireSuperUnhappy */
    @Readonly fun isSuperUnhappy() = approval < constants.superUnhappyThreshold

    /** CvPlayer::GetUnhappinessGrowthPenalty: percent growth in every city, 2.5 per approval point under the unhappy threshold, within -100 and 0 */
    val growthPenalty: Int @Readonly get() =
        (constants.globalGrowthPenaltyPerPoint * (approval - constants.unhappyThreshold)).toInt().coerceIn(-100, 0)

    /** CvPlayer::GetUnhappinessSettlerCostPenalty: percent production of settlers in every city, 2.5 per approval point under the unhappy threshold, at least -75 */
    val settlerPenalty: Int @Readonly get() =
        (constants.globalSettlerPenaltyPerPoint * (approval - constants.unhappyThreshold)).toInt().coerceIn(constants.maxUnitProductionPenalty, 0)

    /** CvPlayer::GetUnhappinessCombatStrengthPenalty: percent combat strength of every unit: -20 when very unhappy, -10 when only unhappy, else 0 */
    val combatPenalty: Int @Readonly get() = when {
        !isUnhappy() -> 0
        isVeryUnhappy() -> constants.veryUnhappyMaxCombatPenalty
        else -> constants.veryUnhappyMaxCombatPenalty / 2
    }

    /**
     *  CvPlayer::GetHappinessForGAP: the golden age points a turn makes of happiness: the sum of the positive deltas of the cities, times (100 + the
     *  percentages of [UniqueType.GoldenAgePointsFromHappinessPercent]) / 100. Read by the golden age meter.
     */
    @Readonly
    fun goldenAgePointsFromHappiness(): Int {
        if (!civ.isMajorCiv) return 0
        val total = civ.cities.sumOf { max(0, it.cityStats.needs.delta) }
        var percent = 0
        civ.forEachMatchingUnique(UniqueType.GoldenAgePointsFromHappinessPercent) { percent += it.params[0].toInt() }
        return total * (100 + percent) / 100
    }

    //endregion
    //region Updating

    /**
     *  Computes the pool and the empire's unhappiness, hands both out to the cities, has every city add up again, and the approval rating. Takes what the cities'
     *  [CityNeeds] hold from their last update.
     *  @return whether what the cities have already read of the empire (their deltas, the growth and settler penalties) is out of date, so they have to update their stats
     */
    fun update(): Boolean {
        val cities = civ.cities
        val deltasBefore = cities.map { it.cityStats.needs.delta }
        val growthBefore = growthPenalty
        val settlerBefore = settlerPenalty

        if (!civ.isMajorCiv || cities.isEmpty()) {
            pool = 0
            poolBreakdown = emptyMap()
            unhappinessPool = 0
            happyCitizens = 0
            unhappyCitizens = 0
            computedApproval = null
            return false
        }

        poolBreakdown = civ.stats.getHappinessBreakdown()
        pool = poolBreakdown.values.sum().roundToInt()
        unhappinessPool = civ.culture.publicOpinion.unhappiness + warWeariness()
        distributeHappiness()
        distributeUnhappiness()

        var happy = 0
        var unhappy = 0
        for (city in cities) {
            val needs = city.cityStats.needs
            needs.aggregate()
            happy += needs.localHappiness()
            unhappy += needs.aggregated
        }
        happyCitizens = happy
        unhappyCitizens = unhappy
        computedApproval = if (unhappy == 0) 100 else min(200, happy * 100 / max(1, unhappy)) / 2

        return growthBefore != growthPenalty || settlerBefore != settlerPenalty ||
            cities.indices.any { deltasBefore[it] != cities[it].cityStats.needs.delta }
    }

    /**
     *  CvPlayer::DistributeHappinessToCities: a city of one gets all of the pool. Else every city that gives and gets happiness gets one point at a time, the
     *  city whose citizens exceed its own happiness the most first, until none has any need left; what is left goes round the cities by
     *  population, biggest first, until it is all given. Cities with the same need or population keep their order in the list of cities.
     *  A pool that is not above 0 gives nothing (the source does not stop on a pool of less than 0).
     */
    private fun distributeHappiness() {
        val cities = civ.cities
        if (cities.size == 1) {
            cities[0].cityStats.needs.happinessFromEmpire = pool
            return
        }

        val byNeed = ArrayList<Pair<CityNeeds, Int>>()
        val byPopulation = ArrayList<Pair<CityNeeds, Int>>()
        for (city in cities) {
            val needs = city.cityStats.needs
            needs.happinessFromEmpire = 0
            if (!needs.givesAndGetsHappiness()) continue
            val population = city.population.population
            val need = population - needs.happinessWithoutEmpire
            if (need > 0) byNeed.add(needs to need)
            byPopulation.add(needs to population)
        }
        var remaining = pool
        if (remaining <= 0 || byPopulation.isEmpty()) return

        byNeed.sortByDescending { it.second }
        byPopulation.sortByDescending { it.second }

        val weights = IntArray(byNeed.size) { byNeed[it].second }
        var allFull = byNeed.isEmpty()
        while (!allFull) {
            for (i in byNeed.indices) {
                if (weights[i] > 0) {
                    byNeed[i].first.happinessFromEmpire++
                    remaining--
                    weights[i]--
                }
                if (remaining == 0) return
            }
            allFull = weights.all { it <= 0 }
        }

        while (true) {
            for ((needs, _) in byPopulation) {
                needs.happinessFromEmpire++
                remaining--
                if (remaining == 0) return
            }
        }
    }

    /**
     *  CvCity::UpdateUnhappinessFromEmpire: the empire's unhappiness divided among the cities that are no puppets that may be annexed: each gets the whole
     *  number of times the real cities (those not occupied either, at least 1) go into it, and the first cities in the list get the rest one by one.
     */
    private fun distributeUnhappiness() {
        val recipients = civ.cities.map { it.cityStats.needs }.filter { !it.isPuppetAndAnnexable() }
        for (city in civ.cities) city.cityStats.needs.unhappinessFromEmpire = 0
        if (unhappinessPool == 0) return

        val realCities = max(1, recipients.count { it.isRealCity() })
        var remainder = unhappinessPool % realCities
        val share = unhappinessPool / realCities
        for (needs in recipients) {
            needs.unhappinessFromEmpire = share
            if (remainder > 0) {
                needs.unhappinessFromEmpire++
                remainder--
            }
        }
    }

    //endregion
}
