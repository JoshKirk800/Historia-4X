package com.unciv.logic.city.managers

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.HistoricEvent
import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueMap.Companion.NO_UNIQUE_FILTER
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min

class CityReligionManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var city: City

    // This needs to be kept track of for the
    // "[Stats] when a city adopts this religion for the first time" unique
    private val religionsAtSomePointAdopted: HashSet<String> = hashSetOf()

    private val pressures: Counter<String> = Counter()
    // Cached because using `updateNumberOfFollowers` to get this value resulted in many calls
    @Transient
    private val followers: Counter<String> = Counter()
    /** With [com.unciv.models.ModConstants.religiousSpreadLikeVp]: the majority religion that [updateNumberOfFollowers] found, VP's m_eMajorityCityReligion */
    @Transient
    private var majorityLikeVp: String? = null

    @delegate:Transient
    private val pressureFromAdjacentCities: Int by lazy { city.civ.gameInfo.speed.religiousPressureAdjacentCity }

    var religionThisIsTheHolyCityOf: String? = null
    var isBlockedHolyCity = false

    init {
        clearAllPressures()
    }

    fun clone(): CityReligionManager {
        val toReturn = CityReligionManager()
        toReturn.city = city
        toReturn.religionsAtSomePointAdopted.addAll(religionsAtSomePointAdopted)
        toReturn.pressures.putAll(pressures)
        toReturn.followers.putAll(followers)
        toReturn.majorityLikeVp = majorityLikeVp
        toReturn.religionThisIsTheHolyCityOf = religionThisIsTheHolyCityOf
        toReturn.isBlockedHolyCity = isBlockedHolyCity
        return toReturn
    }

    fun setTransients(city: City) {
        this.city = city
        // We don't need to check for changes in the majority religion, and as this
        // loads in the religion, _of course_ the religion changes, but it shouldn't
        // have any effect
        updateNumberOfFollowers(false)
    }

    fun endTurn() {
        getAffectedBySurroundingCities()
    }

    /** CvCityReligions::GetSecondaryReligion: the major religion other than the majority one that has the most followers
     *  here, a tie going to the one with more pressure. Pantheons do not count. */
    @Readonly
    private fun getSecondaryReligion(majority: String): Religion? {
        var best: String? = null
        for ((name, count) in followers) {
            if (name == majority || count <= 0) continue
            if (city.civ.gameInfo.religions[name]?.isMajorReligion() != true) continue
            if (best == null || count > followers[best] || (count == followers[best] && pressures[name] > pressures[best]))
                best = name
        }
        return best?.let { city.civ.gameInfo.religions[it] }
    }

    /** CvCityReligions::GetSecondaryReligionPantheonBelief (V:CvReligionClasses.cpp:4541-4569): with [UniqueType.SecondaryReligionPantheon]
     *  the first Pantheon belief of the second most popular religion works in the city like one of the majority religion */
    @Readonly
    fun getSecondaryPantheonBelief(): Belief? {
        if (followers.size < 2) return null // cheap test first: this is asked for every unique lookup of a city
        val majority = getMajorityReligionName() ?: return null
        var enabled = false
        city.civ.forEachMatchingUnique(UniqueType.SecondaryReligionPantheon) { enabled = true }
        if (!enabled) return null
        return getSecondaryReligion(majority)?.getBeliefs(BeliefType.Pantheon)?.firstOrNull()
    }

    @Readonly
    @Deprecated(message = "forEachUnique is faster. If not viable, then this can still be used",
        replaceWith = ReplaceWith("forEachUnique"))
    fun getUniques(uniqueType: UniqueType): Sequence<Unique> {
        val majorityReligion = getMajorityReligion() ?: return emptySequence()
        var uniques = majorityReligion.followerBeliefUniqueMap.getUniques(uniqueType)
        val reformation = reformationMapOfMajority(majorityReligion)
        if (reformation != null) uniques += reformation.getUniques(uniqueType)
        val secondary = getSecondaryPantheonBelief() ?: return uniques
        return uniques + secondary.uniqueMap.getUniques(uniqueType)
    }

    /** With [com.unciv.models.ModConstants.reformationBeliefsLikeVp] the Reformation uniques of the majority religion of this city that are scoped to the cities that follow
     *  the religion ([Religion.reformationCityUniqueMap]) work in it for the owner of the city if
     *  [com.unciv.logic.civilization.managers.ReligionManager.reformationBeliefApplies] says so (V:CvBeliefClasses.cpp:2229-2253 asked with the city, as CvCity::UpdateReligion
     *  and the faith purchases do): the map to read in addition to the follower beliefs, or null. The other Reformation uniques come to the city with those of its civilization
     *  ([com.unciv.logic.civilization.managers.ReligionManager.forEachBeliefUniqueMap]). Cheap when no religion has such a belief. */
    @Readonly
    private fun reformationMapOfMajority(majority: Religion): UniqueMap? {
        if (majority.reformationCityUniqueMap.isEmpty()) return null
        if (!city.civ.religionManager.hasCivInfo() || !city.civ.gameInfo.ruleset.modOptions.constants.reformationBeliefsLikeVp) return null
        return if (city.civ.religionManager.reformationBeliefApplies(majority, city)) majority.reformationCityUniqueMap else null
    }

    @Readonly
    fun forEachMatchingUnique(uniqueType: UniqueType, gameContext: GameContext=city.state, op: (unique: Unique)->Unit)
        = forEachMatchingUnique(uniqueType, gameContext, NO_UNIQUE_FILTER, op)
    @Readonly
    fun forEachMatchingUnique(uniqueType: UniqueType, gameContext: GameContext=city.state, filter:(Unique)->Boolean, op: (unique: Unique)->Unit) {
        val majorityReligion = getMajorityReligion() ?: return
        majorityReligion.followerBeliefUniqueMap.forEachMatchingUnique(uniqueType, gameContext, filter, op)
        reformationMapOfMajority(majorityReligion)?.forEachMatchingUnique(uniqueType, gameContext, filter, op)
        getSecondaryPantheonBelief()?.uniqueMap?.forEachMatchingUnique(uniqueType, gameContext, filter, op)
    }

    @Readonly
    fun getAllUniques(): Sequence<Unique> {
        val majorityReligion = getMajorityReligion() ?: return emptySequence()
        var uniques = majorityReligion.followerBeliefUniqueMap.getAllUniques()
        val reformation = reformationMapOfMajority(majorityReligion)
        if (reformation != null) uniques += reformation.getAllUniques()
        val secondary = getSecondaryPantheonBelief() ?: return uniques
        return uniques + secondary.uniqueMap.getAllUniques()
    }

    @Readonly
    fun forEachUnique(filter:(Unique)->Boolean, op: (unique: Unique)->Unit) {
        val majorityReligion = getMajorityReligion() ?: return
        majorityReligion.followerBeliefUniqueMap.forEachUnique(filter, op)
        reformationMapOfMajority(majorityReligion)?.forEachUnique(filter, op)
        getSecondaryPantheonBelief()?.uniqueMap?.forEachUnique(filter, op)
    }

    /** CvCityReligions::GetNumReligionsWithFollowers (V:CvReligionClasses.cpp:4245-4260): the major religions with at least one follower in the city */
    @Readonly
    fun getNumberOfMajorReligionsWithFollowers(): Int =
        followers.count { it.value > 0 && city.civ.gameInfo.religions[it.key]?.isMajorReligion() == true }

    @Readonly fun getPressures(): Counter<String> = pressures.clone()

    private fun clearAllPressures() {
        pressures.clear()
        // We add pressure for following no religion
        // Basically used as a failsafe so that there is always some religion,
        // and we don't suddenly divide by 0 somewhere
        // Should be removed when updating the followers so it never becomes the majority religion,
        // `null` is used for that instead.
        pressures.add(Constants.noReligionName, 100)
    }

    fun addPressure(religionName: String, amount: Int, shouldUpdateFollowers: Boolean = true) {
        if (!city.civ.gameInfo.isReligionEnabled()) return // No religion, no pressures
        if (spreadLikeVp() && city.civ.gameInfo.religions[religionName]?.isMajorReligion() == true) {
            // CvCityReligions::AddReligiousPressure (V:CvReligionClasses.cpp:4896-4930): the pressure of a religion takes half as much (MOD_BALANCE_RESILIENT_PANTHEONS,
            // on in VP) from each pantheon in the city, down to 0
            for ((name, pressure) in pressures.toList()) // a copy, as the pressures change
                if (pressure > 0 && city.civ.gameInfo.religions[name]?.isPantheon() == true)
                    pressures[name] = max(0, pressure - amount / 2)
        }
        pressures.add(religionName, amount)

        if (shouldUpdateFollowers) updateNumberOfFollowers()
    }

    /** The pressure of a Spread Religion action that leaves the other religions in place (CvCityReligions::AddMissionarySpread, V:CvReligionClasses.cpp:4813-4821):
     *  first the religion with [UniqueType.ErodesOtherReligionPressure] takes that percentage (at most 100) of the pressure that each other religion HAS in the city
     *  away, then the pressure is added as with [addPressure]. The erosion is CvCityReligions::ErodeOtherReligiousPressure (V:CvReligionClasses.cpp:4932-4969) with
     *  retention off and atheism left alone: pantheons are eroded too, a religion without pressure is skipped, and the share is `percent * pressure / 100` in whole numbers.
     *  VP asks the erosion of the belief for the player responsible ([spreader], the owner of the missionary, 4817); with
     *  [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp] a belief counts only if [com.unciv.logic.civilization.managers.ReligionManager.isBeliefValid] accepts it
     *  for the spreader, without a city. Without a spreader every belief counts, as VP's IsBeliefValid does for NO_PLAYER (V:CvBeliefClasses.cpp:2153-2154). */
    fun addPressureFromSpreading(religionName: String, amount: Int, spreader: Civilization? = null) {
        if (!city.civ.gameInfo.isReligionEnabled()) return // No religion, no pressures
        var erosionPercent = 0
        val spreadReligion = city.civ.gameInfo.religions[religionName]
        if (spreadReligion != null && spreader != null && city.civ.gameInfo.ruleset.modOptions.constants.religiousBeliefValidityLikeVp)
            spreader.religionManager.forEachValidFounderSideUnique(spreadReligion, UniqueType.ErodesOtherReligionPressure, city.state) { erosionPercent += it.params[0].toInt() }
        else spreadReligion?.founderBeliefUniqueMap
            ?.forEachMatchingUnique(UniqueType.ErodesOtherReligionPressure, city.state) { erosionPercent += it.params[0].toInt() }
        if (erosionPercent > 0) {
            val reductionPercent = minOf(100, erosionPercent)
            for ((name, pressure) in pressures.toList()) { // a copy, as the pressures change
                if (name == religionName || name == Constants.noReligionName || pressure == 0) continue
                pressures.add(name, -(reductionPercent * pressure / 100))
            }
        }
        addPressure(religionName, amount)
    }

    /** Removes the pressure of all religions but [religion] and atheism. A religion with [UniqueType.ReligionPressureRetention] keeps
     *  that share of its pressure (CvCityReligions::RemoveOtherReligions, V:CvReligionClasses.cpp:5246-5259, and AddProphetSpread, 4825-4830) */
    fun removeAllPressuresExceptFor(religion: String) {
        val pressureFromThisReligion = pressures[religion]
        // Atheism is never removed
        val pressureFromAtheism = pressures[Constants.noReligionName]
        val retainedPressures = Counter<String>()
        for ((name, pressure) in pressures) {
            if (name == religion || name == Constants.noReligionName) continue
            val other = city.civ.gameInfo.religions[name] ?: continue
            if (!other.isMajorReligion()) continue
            var retention = 0
            // CvCityReligions::ErodeOtherReligiousPressure, which RemoveOtherReligions and AddProphetSpread call, asks GetInquisitorPressureRetention(the OWNER OF THE CITY)
            // (V:CvReligionClasses.cpp:4957; SimulateErodeOtherReligiousPressure, the same for the preview, 4999): with the switch a
            // Reformation belief of the religion that is kept only counts if the owner of this city owns its holy city or has it as the state religion
            // With religiousBeliefValidityLikeVp every founder-side belief is asked for the owner of the city without a city: a Founder or Enhancer belief only if that
            // owner owns the holy city of the religion
            if (city.civ.gameInfo.ruleset.modOptions.constants.religiousBeliefValidityLikeVp)
                city.civ.religionManager.forEachValidFounderSideUnique(other, UniqueType.ReligionPressureRetention, city.state) { retention += it.params[0].toInt() }
            else if (!city.civ.gameInfo.ruleset.modOptions.constants.reformationBeliefsLikeVp)
                other.founderBeliefUniqueMap.forEachMatchingUnique(UniqueType.ReligionPressureRetention, city.state) { retention += it.params[0].toInt() }
            else {
                other.founderWithoutReformationUniqueMap.forEachMatchingUnique(UniqueType.ReligionPressureRetention, city.state) { retention += it.params[0].toInt() }
                if (city.civ.religionManager.reformationBeliefApplies(other))
                    other.reformationBeliefUniqueMap.forEachMatchingUnique(UniqueType.ReligionPressureRetention, city.state) { retention += it.params[0].toInt() }
            }
            if (retention <= 0) continue
            // With religiousSpreadLikeVp the share taken away is what rounds down (V:CvReligionClasses.cpp:4958-4965), so the share kept rounds up
            if (spreadLikeVp()) retainedPressures.add(name, pressure - max(0, 100 - retention) * pressure / 100)
            else retainedPressures.add(name, pressure * retention.coerceAtMost(100) / 100)
        }
        clearAllPressures()
        pressures.add(religion, pressureFromThisReligion)
        for ((name, pressure) in retainedPressures) pressures.add(name, pressure)
        if (pressureFromAtheism != 0) pressures[Constants.noReligionName] = pressureFromAtheism
        updateNumberOfFollowers()
    }

    fun updatePressureOnPopulationChange(populationChangeAmount: Int) {
        val majorityReligion =
            if (getMajorityReligionName() != null) getMajorityReligionName()!!
            else Constants.noReligionName

        if (populationChangeAmount > 0) {
            addPressure(majorityReligion, 100 * populationChangeAmount)
        } else {
            updateNumberOfFollowers()
        }
    }

    private fun triggerReligionAdoption(newMajorityReligion: String) {
        val newMajorityReligionObject = city.civ.gameInfo.religions[newMajorityReligion]!!
        city.civ.addNotification("Your city [${city.name}] was converted to [${newMajorityReligionObject.getReligionDisplayName()}]!",
            city.location, NotificationCategory.Religion, NotificationIcon.Faith)

        if (newMajorityReligion in religionsAtSomePointAdopted) return

        val religionOwningCiv = newMajorityReligionObject.foundingCiv
        if (religionOwningCiv.hasUnique(UniqueType.StatsWhenAdoptingReligion)) {
            val statsGranted =
                religionOwningCiv.getMatchingUniques(UniqueType.StatsWhenAdoptingReligion).map { it.stats.times(if (!it.isModifiedByGameSpeed()) 1f else city.civ.gameInfo.speed.modifier) }
                .reduce { acc, stats -> acc + stats }

            for ((key, value) in statsGranted)
                religionOwningCiv.addStat(key, value.toInt())
            if (religionOwningCiv.hasExplored(city.getCenterTile()))
                religionOwningCiv.addNotification(
                    "You gained [$statsGranted] as your religion was spread to [${city.name}]",
                    city.location,
                    NotificationCategory.Religion,
                    NotificationIcon.Faith
                )
            else
                religionOwningCiv.addNotification(
                    "You gained [$statsGranted] as your religion was spread to an unknown city",
                    NotificationCategory.Religion,
                    NotificationIcon.Faith
                )
        }
        religionsAtSomePointAdopted.add(newMajorityReligion)

        // CvCityReligions::CityConvertsReligion (V:CvReligionClasses.cpp:5593-5626): a full religion's first adoption pays INSTANT_YIELD_TYPE_CONVERSION
        // to the owner of its holy city, in the holy city, if it is that owner's state religion
        if (!newMajorityReligionObject.isMajorReligion()) return
        val holyCity = city.civ.gameInfo.getCities().firstOrNull { it.isHolyCityOf(newMajorityReligion) } ?: return
        val controller = holyCity.civ
        if (controller.religionManager.getStateReligion()?.name != newMajorityReligion) return
        InstantYields.fire(UniqueType.TriggerUponCityAdoptingOurReligion, controller, holyCity,
            triggerNotificationText = "due to [${city.name}] adopting our religion")
        if (city.civ.gameInfo.ruleset.modOptions.constants.historicEventDispatchLikeVp) controller.culture.onHistoricEvent(HistoricEvent.ReligionSpread)
    }

    private fun updateNumberOfFollowers(checkForReligionAdoption: Boolean = true) {
        city.civ.religionManager.invalidateStateReligion() // CvCity::UpdateReligion ends in CvPlayer::UpdateReligion (V:CvCity.cpp:15188)
        val oldMajorityReligion =
            if (checkForReligionAdoption) getMajorityReligionName()
            else null

        val previousFollowers = followers.clone()
        followers.clear()
        majorityLikeVp = null

        if (city.population.population <= 0) return

        if (spreadLikeVp()) recomputeFollowersLikeVp()
        else divideFollowers()

        if (checkForReligionAdoption) {
            val newMajorityReligion = getMajorityReligionName()
            if (oldMajorityReligion != newMajorityReligion && newMajorityReligion != null) {
                triggerReligionAdoption(newMajorityReligion)
            }
            if (oldMajorityReligion != newMajorityReligion)
                city.civ.cache.updateCivResources() // follower uniques can provide resources
            if (followers != previousFollowers)
                city.cityStats.update()
        }
    }

    /** CvCityReligions::RecomputeFollowers and ComputeReligiousMajority (V:CvReligionClasses.cpp:5430-5520, 4443-4489), in whole numbers:
     *  - each religion gets its pressure divided by `total pressure / population` (atheism counts), the rest go one each to the largest remainders,
     *    first in pressure order; a citizen left with no remainder above 0 follows nothing;
     *  - the majority is the religion with the most followers (more pressure breaks a tie) if it has at least half the followers there are, atheists
     *    included; atheism with the most followers means no majority.
     *  VP keeps its religions sorted by pressure, the most first, atheism last; a tie in pressure keeps the order the religions came in here. */
    private fun recomputeFollowersLikeVp() {
        if (pressures.values.sum() <= 0) clearAllPressures() // VP's safety net: the atheism of one citizen
        // VP divides by zero if the pressure is below the population; one per follower is the least here
        val pressurePerFollower = max(1, pressures.values.sum() / city.population.population)
        val inOrder = pressures.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key == Constants.noReligionName }.thenByDescending { it.value })
        val remainders = IntArray(inOrder.size)
        var unassigned = city.population.population
        for ((index, entry) in inOrder.withIndex()) {
            val count = entry.value / pressurePerFollower
            followers.add(entry.key, count)
            unassigned -= count
            remainders[index] = entry.value - count * pressurePerFollower
        }
        repeat(unassigned) {
            var recipient = -1
            var largest = 0
            for (index in remainders.indices) if (remainders[index] > largest) {
                largest = remainders[index]
                recipient = index
            }
            if (recipient >= 0) {
                followers.add(inOrder[recipient].key, 1)
                remainders[recipient] = 0
            }
        }
        var most: String? = null
        var mostFollowers = -1
        var mostPressure = 0
        for (entry in inOrder) {
            val count = followers[entry.key]
            if (count > mostFollowers || (count == mostFollowers && entry.value > mostPressure)) {
                most = entry.key
                mostFollowers = count
                mostPressure = entry.value
            }
        }
        if (most != Constants.noReligionName && mostFollowers * 2 >= followers.values.sum()) majorityLikeVp = most
        followers.remove(Constants.noReligionName)
    }

    private fun divideFollowers() {
        val remainders = HashMap<String, Float>()
        val pressurePerFollower = pressures.values.sum() / city.population.population

        // First give each religion an approximate share based on pressure
        for ((religion, pressure) in pressures) {
            val followersOfThisReligion = (pressure.toFloat() / pressurePerFollower).toInt()
            followers.add(religion, followersOfThisReligion)
            remainders[religion] = pressure.toFloat() - followersOfThisReligion * pressurePerFollower
        }

        var unallocatedPopulation = city.population.population - followers.values.sum()

        // Divide up the remaining population
        while (unallocatedPopulation > 0) {
            val largestRemainder = remainders.maxByOrNull { it.value }
            if (largestRemainder == null) {
                followers.add(Constants.noReligionName, unallocatedPopulation)
                break
            }
            followers.add(largestRemainder.key, 1)
            remainders[largestRemainder.key] = 0f
            unallocatedPopulation -= 1
        }

        followers.remove(Constants.noReligionName)
    }

    @Readonly fun getNumberOfFollowers(): Counter<String> = followers.clone()
    @Readonly fun getFollowersOf(religion: String): Int = followers[religion]

    @Readonly
    fun getFollowersOfMajorityReligion(): Int {
        val majorityReligion = getMajorityReligionName() ?: return 0
        return followers[majorityReligion]
    }

    @Readonly
    fun getFollowersOfOtherReligionsThan(religion: String): Int {
        return followers.filterNot { it.key == religion }.values.sum()
    }

    /** CvCityReligions::GetFollowersOtherReligions without the pantheons (V:CvReligionClasses.cpp:4572-4593): the followers of the major religions other than [religion] */
    @Readonly
    fun getFollowersOfOtherMajorReligionsThan(religion: String): Int {
        val religions = city.civ.gameInfo.religions
        return followers.entries.sumOf { (name, amount) -> if (name != religion && religions[name]?.isMajorReligion() == true) amount else 0 }
    }

    /** Removes all pantheons except for the one founded by the current owner of the city
     *  Should be called whenever a city changes hands, e.g. conquering and trading
     */
    fun removeUnknownPantheons() {
        for (pressure in pressures.keys.toList()) {  // Copy the keys because we might modify
            if (pressure == Constants.noReligionName) continue
            val correspondingReligion = city.civ.gameInfo.religions[pressure]!!
            if (correspondingReligion.isPantheon()
                && correspondingReligion.foundingCiv != city.civ
            ) {
                pressures.remove(pressure)
            }
        }
        updateNumberOfFollowers()
    }

    @Readonly
    fun getMajorityReligionName(): String? {
        if (spreadLikeVp()) return majorityLikeVp
        if (followers.isEmpty()) return null
        val religionWithMaxPressure = followers.maxByOrNull { it.value }!!.key
        return when {
            religionWithMaxPressure == Constants.noReligionName -> null
            followers[religionWithMaxPressure] >= city.population.population / 2 -> religionWithMaxPressure
            else -> null
        }
    }

    @Readonly
    fun getMajorityReligion(): Religion? {
        val majorityReligionName = getMajorityReligionName() ?: return null
        return city.civ.gameInfo.religions[majorityReligionName]
    }

    private fun getAffectedBySurroundingCities() {
        if (!city.civ.gameInfo.isReligionEnabled()) return // No religion, no spreading
        if (spreadLikeVp()) return getAffectedBySurroundingCitiesLikeVp()
        // We don't update the amount of followers yet, as only the end result should matter
        // If multiple religions would become the majority religion due to pressure,
        // this will make it so we only receive a notification for the last one.
        // Also, doing it like this increases performance :D
        if (city.isHolyCity()) {
            addPressure(religionThisIsTheHolyCityOf!!,5 * pressureFromAdjacentCities, false)
        }

        // CvGameReligions::SpreadReligionToOneCity (CvReligionClasses.cpp:334): the spies of a religion with Underground Sect, first
        for ((religionName, times) in getSpyPressureTimes())
            if (pressures.containsKey(religionName)) // the original only adds to a presence that is already there
                addPressure(religionName, times * pressureFromAdjacentCities, false)

        val tradeConnections = getTradeConnections()
        for (otherCity in city.civ.gameInfo.getCities()) {
            if (otherCity == city) continue
            val (religionName, pressure) = getPressureFrom(otherCity, tradeConnections) ?: continue
            addPressure(religionName, pressure, false)
        }

        updateNumberOfFollowers()
    }

    /** CvGameReligions::SpreadReligionToOneCity (V:CvReligionClasses.cpp:358-445): the followers are recomputed after each source, so a source can flip the
     *  majority that the next one finds: the holy city pressure first, then for each civilization in turn the pressure of each of its cities, then that of
     *  its spy. Each flip notifies, as VP's CityConvertsReligion does. Not built: VP takes the cities to spread to in player order, the engine in turn order */
    private fun getAffectedBySurroundingCitiesLikeVp() {
        if (city.isHolyCity()) addPressure(religionThisIsTheHolyCityOf!!, 5 * pressureFromAdjacentCities) // AddHolyCityPressure, 5174-5183
        val tradeConnections = getTradeConnections()
        for (civ in city.civ.gameInfo.civilizations) {
            for (otherCity in civ.cities) {
                if (otherCity == city) continue
                val (religionName, pressure) = getPressureFrom(otherCity, tradeConnections) ?: continue
                addPressure(religionName, pressure)
            }
            // AddSpyPressure (5186-5190) adds where the religion is absent too, then DoSpyPressureErosion
            getSpyPressureTimes(civ)?.let { (religionName, times) -> addPressure(religionName, times * pressureFromAdjacentCities) }
            getSpyPressureTimes(civ, UniqueType.SpyReligionPressureErosion)?.let { (religionName, times) ->
                erodeOtherReligionsBySpy(religionName, times * pressureFromAdjacentCities) }
        }
    }

    /** CvCityReligions::DoSpyPressureErosion (V:CvReligionClasses.cpp:5193-5212): each major religion but [religionName] loses [amount], down to nothing */
    private fun erodeOtherReligionsBySpy(religionName: String, amount: Int) {
        for ((name, pressure) in pressures.toList()) { // a copy, as the pressures change
            if (name == religionName || pressure == 0 || city.civ.gameInfo.religions[name]?.isMajorReligion() != true) continue
            pressures.add(name, -min(amount, pressure))
        }
        updateNumberOfFollowers()
    }

    /** For each religion whose founder has a living spy in this city: how many times the natural pressure it adds, by [UniqueType.SpyReligionPressure] */
    @Readonly
    private fun getSpyPressureTimes(): Counter<String> {
        val result = Counter<String>()
        for (spyCiv in city.civ.gameInfo.civilizations) {
            val (religionName, times) = getSpyPressureTimes(spyCiv) ?: continue
            result.add(religionName, times)
        }
        return result
    }

    /** The religion that a living spy of [spyCiv] in this city spreads and how many times the natural pressure it adds by [uniqueType]
     *  ([UniqueType.SpyReligionPressure], or the erosion of [UniqueType.SpyReligionPressureErosion]). With [com.unciv.models.ModConstants.religiousSpreadLikeVp] only the
     *  spies of major civilizations count, and not while they travel (V:CvReligionClasses.cpp:413-414, 434) */
    @Readonly
    private fun getSpyPressureTimes(spyCiv: Civilization, uniqueType: UniqueType = UniqueType.SpyReligionPressure): Pair<String, Int>? {
        if (spreadLikeVp() && !spyCiv.isMajorCiv) return null
        val reformationLikeVp = city.civ.gameInfo.ruleset.modOptions.constants.reformationBeliefsLikeVp
        // CvPlayerReligions::GetSpyPressure (V:CvReligionClasses.cpp:4017-4031): the spy pressure of the STATE religion of the civilization, asked with its holy city.
        // With the switch the state religion gets the pressure and a Reformation belief counts for any civilization it applies to; without it the religion the civilization
        // founded does, with all its founder-side beliefs. DEPARTURE with the switch: the Founder and Enhancer beliefs are still those of the religion the civilization
        // FOUNDED (VP asks the state religion): no unique of that kind has SpyReligionPressure in the ruleset, only the Reformation belief Underground Sect.
        val religion = (if (reformationLikeVp) spyCiv.religionManager.getStateReligionCached()?.takeIf { it.isMajorReligion() }
            else spyCiv.religionManager.religion?.takeIf { it.isMajorReligion() }) ?: return null
        var times = 0
        if (reformationLikeVp) spyCiv.religionManager.forEachBeliefUniqueMap { map ->
            map.forEachMatchingUnique(uniqueType, spyCiv.state) { times += it.params[0].toInt() }
        } else religion.founderBeliefUniqueMap.forEachMatchingUnique(uniqueType, spyCiv.state) { times += it.params[0].toInt() }
        if (times <= 0) return null
        val spreadLikeVp = spreadLikeVp()
        return if (spyCiv.espionageManager.getSpiesInCity(city).any { it.isAlive() && !(spreadLikeVp && it.action == SpyAction.Moving) }) religion.name to times
            else null
    }

    /** The cities that a trade route of anyone links this city to, in either direction (CvGameTrade::IsCityConnectedToCity, CvTradeClasses.cpp:661),
     *  each with whether a route starts there and ends here (IsCityConnectedFromCityToCity, 691) */
    @Readonly
    private fun getTradeConnections(): Map<City, Boolean> {
        @LocalState val connections = HashMap<City, Boolean>()
        for (civ in city.civ.gameInfo.civilizations) {
            for (route in civ.tradeRoutes.routes) {
                val origin = route.getOriginCity() ?: continue
                val destination = route.getDestinationCity() ?: continue
                if (destination == city) connections[origin] = true
                else if (origin == city) connections.putIfAbsent(destination, false)
            }
        }
        return connections
    }

    /** The religion that [sender] spreads to this city naturally every turn and how much pressure that is, if any
     *  (CvGameReligions::GetAdjacentCityReligiousPressure, V:CvReligionClasses.cpp:2801-2950): the sender must be close enough, or be connected by a trade route
     *  in either direction; a city that is both makes the pressure only once */
    @Readonly
    private fun getPressureFrom(sender: City, tradeConnections: Map<City, Boolean>): Pair<String, Int>? {
        val majorityReligion = sender.religion.getMajorityReligion() ?: return null
        if (!majorityReligion.isMajorReligion()) return null
        val tradeConnection = tradeConnections[sender]
        if (spreadLikeVp()) {
            val connectedWithTrade = tradeConnection != null
            val distancePercent = if (connectedWithTrade) 0 else sender.religion.relativeDistancePercentLikeVp(majorityReligion, city)
            if (distancePercent >= 100) return null
            val pressure = sender.religion.pressureLikeVp(majorityReligion, city, connectedWithTrade, distancePercent)
            return if (pressure > 0) majorityReligion.name to pressure else null
        }
        if (tradeConnection == null
            && sender.getCenterTile().aerialDistanceTo(city.getCenterTile()) > sender.religion.getSpreadRange()) return null
        return majorityReligion.name to sender.religion.pressureAmountToAdjacentCities(city, tradeConnection == true)
    }

    /**
     * CvCityReligions::WouldExertTradeRoutePressureToward: the majority religion of this city and the pressure a trade route to [target] would
     * carry (the pressure of a route that starts here), `null` if this city has no major majority religion or [target] is close enough to be
     * pressured without a route. Same units as [getPressuresFromSurroundingCities].
     */
    @Readonly
    fun wouldExertTradeRoutePressureToward(target: City): Pair<String, Int>? {
        val majorityReligion = getMajorityReligion() ?: return null
        if (!majorityReligion.isMajorReligion()) return null
        if (spreadLikeVp()) {
            // V:CvReligionClasses.cpp:4759-4787: the pressure with a route less the pressure there is, which is the same if a route links the cities already
            val withRoute = pressureLikeVp(majorityReligion, target, connectedWithTrade = true, relativeDistancePercent = 0)
            val withoutRoute = if (target in getTradeConnections()) withRoute
                else pressureLikeVp(majorityReligion, target, connectedWithTrade = false,
                    relativeDistancePercent = relativeDistancePercentLikeVp(majorityReligion, target))
            return if (withRoute > withoutRoute) majorityReligion.name to withRoute - withoutRoute else null
        }
        if (city.getCenterTile().aerialDistanceTo(target.getCenterTile()) <= getSpreadRange()) return null
        return majorityReligion.name to pressureAmountToAdjacentCities(target, viaTradeRouteFromHere = true)
    }

    @Readonly
    private fun getSpreadRange(): Int {
        var spreadRange = 10

        city.forEachMatchingUnique(UniqueType.ReligionSpreadDistance) { unique ->
            spreadRange += unique.params[0].toInt()
        }

        val majorityReligion = getMajorityReligion()
        if (majorityReligion != null) {
            majorityReligion.foundingCiv.forEachMatchingUnique(UniqueType.ReligionSpreadDistance) { unique ->
                spreadRange += unique.params[0].toInt()
            }
        }

        return spreadRange
    }

    /** Doesn't update the pressures, only returns what they are if the update were to happen right now */
    @Readonly
    fun getPressuresFromSurroundingCities(): Counter<String> {
        val addedPressure = Counter<String>()
        if (city.isHolyCity()) {
            addedPressure[religionThisIsTheHolyCityOf!!] = 5 * pressureFromAdjacentCities
        }
        for ((religionName, times) in getSpyPressureTimes())
            if (pressures.containsKey(religionName))
                addedPressure.add(religionName, times * pressureFromAdjacentCities)
        val tradeConnections = getTradeConnections()
        for (otherCity in city.civ.gameInfo.getCities()) {
            if (otherCity == city) continue
            val (religionName, pressure) = getPressureFrom(otherCity, tradeConnections) ?: continue
            addedPressure.add(religionName, pressure)
        }
        return addedPressure
    }

    @Readonly
    fun isProtectedByInquisitor(fromReligion: String? = null): Boolean {
        for (tile in city.getCenterTile().getTilesInDistance(1)) {
            for (unit in listOf(tile.civilianUnit, tile.militaryUnit)) {
                if (unit?.religion != null
                    && (fromReligion == null || unit.religion != fromReligion)
                    && unit.hasUnique(UniqueType.PreventSpreadingReligion)
                ) return true
            }
        }
        return false
    }

    @Readonly
    private fun pressureAmountToAdjacentCities(pressuredCity: City, viaTradeRouteFromHere: Boolean = false): Int {
        var pressure = pressureFromAdjacentCities.toFloat()

        // A trade route that starts here (CvReligionClasses.cpp:1705): the trait of the civilization, Arabia
        if (viaTradeRouteFromHere)
            city.civ.forEachMatchingUnique(UniqueType.TradeRouteReligionPressure) { unique ->
                pressure *= unique.params[0].toPercent()
            }

        // Follower beliefs of this religion
        city.forEachMatchingUnique(UniqueType.NaturalReligionSpreadStrength) { unique ->
            if (pressuredCity.matchesFilter(unique.params[1]))
                pressure *= unique.params[0].toPercent()
        }

        // Founder beliefs of this religion
        val majorityReligion = getMajorityReligion()
        if (majorityReligion != null) {
            majorityReligion.foundingCiv.forEachMatchingUnique(UniqueType.NaturalReligionSpreadStrength) { unique ->
                if (pressuredCity.matchesFilter(unique.params[1]))
                    pressure *= unique.params[0].toPercent()
            }
            // World Religion (LR 4.3.10): the pressure of the chosen religion is stronger
            val spreadPercent = city.civ.gameInfo.league?.getReligionSpreadPercent(majorityReligion.name) ?: 0
            if (spreadPercent != 0) pressure *= (100 + spreadPercent) / 100f
        }

        return pressure.toInt()
    }

    @Readonly
    private fun spreadLikeVp() = city.civ.gameInfo.ruleset.modOptions.constants.religiousSpreadLikeVp

    /** With [com.unciv.models.ModConstants.religiousSpreadLikeVp], the uniques of [uniqueType] that change the natural spread of [religion], the majority of this city:
     *  those of its beliefs that [com.unciv.logic.civilization.managers.ReligionManager.isBeliefValid] accepts for the owner of this city, without a city
     *  (CvReligionBeliefs::GetSpreadStrengthModifier, V:CvBeliefClasses.cpp:2505-2521), their conditionals asked of the founder, whose technology doubles the strength
     *  (V:CvReligionClasses.cpp:2889-2904); and the uniques of this city that come from no belief, its buildings (CvCityReligions::GetReligiousPressureModifier) and its civilization */
    @Readonly
    private fun forEachSpreadUniqueLikeVp(religion: Religion, uniqueType: UniqueType, op: (Unique) -> Unit) {
        for (belief in religion.getAllBeliefsOrdered())
            if (belief.hasUnique(uniqueType) && city.civ.religionManager.isBeliefValid(belief, religion))
                belief.forEachMatchingUnique(uniqueType, religion.foundingCiv.state, op)
        city.forEachMatchingUnique(uniqueType) {
            if (it.sourceObjectType != UniqueTarget.FollowerBelief && it.sourceObjectType != UniqueTarget.FounderBelief) op(it)
        }
    }

    /**
     * CvGameReligions::IsCityConnectedToCity without a trade route (V:CvReligionClasses.cpp:560-631): how far [target] is from this city, in percent of the trade range of
     * this city, by the cheapest of the cached land and sea trade paths from here to there and from there to here; the trade path cost is SPath::iNormalizedDistanceRaw less
     * one (V:CvAStar.cpp:3419). The spread distance beliefs lengthen the range, ten percent a tile. [Int.MAX_VALUE] without a path.
     */
    @Readonly
    private fun relativeDistancePercentLikeVp(religion: Religion, target: City): Int {
        var distanceMod = 0
        forEachSpreadUniqueLikeVp(religion, UniqueType.ReligionSpreadDistance) { distanceMod += it.params[0].toInt() * 10 }
        var percent = Int.MAX_VALUE
        for (isSea in listOf(false, true)) {
            var maxDistance = (city.civ.tradeRoutes.getPotentialRange(city, isSea) ?: continue) * 100
            if (distanceMod > 0) maxDistance = maxDistance * (100 + distanceMod) / 100
            if (maxDistance <= 0) continue
            val there = city.civ.tradeRoutes.getPotentialPathCosts(city, isSea)[target.getCenterTile()]
            if (there != null) percent = min(percent, (there + 1) * 100 / maxDistance)
            val back = target.civ.tradeRoutes.getPotentialPathCosts(target, isSea)[city.getCenterTile()]
            if (back != null) percent = min(percent, (back + 1) * 100 / maxDistance)
        }
        return percent
    }

    /**
     * CvGameReligions::GetAdjacentCityReligiousPressure (V:CvReligionClasses.cpp:2801-2950): the natural pressure of [religion], the majority of this city, on [target],
     * with or without a trade route that links them, [relativeDistancePercent] away ([relativeDistancePercentLikeVp]) if without
     */
    @Readonly
    private fun pressureLikeVp(religion: Religion, target: City, connectedWithTrade: Boolean, relativeDistancePercent: Int): Int {
        var basePressure = pressureFromAdjacentCities
        var pressureMod = 0
        if (connectedWithTrade)
            city.civ.forEachMatchingUnique(UniqueType.TradeRouteReligionPressure) { pressureMod += it.params[0].toInt() }
        else {
            val pressurePercent = max(100 - relativeDistancePercent, 1)
            basePressure = basePressure * pressurePercent * pressurePercent / (100 * 100)
        }
        forEachSpreadUniqueLikeVp(religion, UniqueType.NaturalReligionSpreadStrength) {
            if (target.matchesFilter(it.params[1])) pressureMod += it.params[0].toInt()
        }
        pressureMod += city.civ.gameInfo.league?.getReligionSpreadPercent(religion.name) ?: 0
        return max(0, basePressure * (100 + pressureMod) / 100)
    }

    /** Calculates how much pressure this religion is lacking compared to the majority religion
     * That is, if we gain more than this, we'll be the majority */
    @Readonly
    fun getPressureDeficit(otherReligion: String?): Int {
        val pressures = getPressures()
        return (pressures[getMajorityReligionName()] ?: 0) - (pressures[otherReligion] ?: 0)
    }
}
