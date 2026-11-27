package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.ReligionAction
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.ui.components.extensions.toPercent
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.Readonly
import java.lang.Integer.min
import kotlin.math.roundToInt
import kotlin.random.Random

class ReligionManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization

    var storedFaith = 0

    @Transient
    var religion: Religion? = null

    /** [getStateReligion] worked out once until [invalidateStateReligion]: the original keeps it in a field that CvPlayerReligions::UpdateStateReligion refreshes
     *  (V:CvReligionClasses.cpp:3733-3739 reads it, 3769-3823 sets it; called from CvPlayer::UpdateReligion, V:CvPlayer.cpp:10836-10854), the lookup of it here sorts
     *  the religions and scans the cities, and [forEachBeliefUniqueMap] asks it on every civilization-wide unique lookup. */
    @Transient @Cache @Volatile
    private var stateReligionCache: Religion? = null
    @Transient @Cache @Volatile
    private var stateReligionCached = false
    // You might ask why this is the transient variable, and not the one in GameInfo.
    // After all, filling a hashmap is much easier than later distributing its contents over multiple classes.
    // There is, however, a problem.
    // When founding a religion, the religion of your pantheon doesn't immediately disappear.
    // It just stops growing. Your new religion will then have to spread out from your holy city
    // and convert these cities. This means, that civilizations can have multiple active religions
    // in some cases. We only save one of them in this class to reduce the amount of logic necessary.
    // But the other one should still be _somewhere_. So our only option is to have the GameInfo
    // contain the master list, and the ReligionManagers retrieve it from there every time the game loads.

    var religionState = ReligionState.None
        private set

    // Counter containing the number of free beliefs types that this civ can add to its religion this turn
    // Uses String instead of BeliefType enum for serialization reasons
    var freeBeliefs: Counter<String> = Counter()

    // These cannot be transient, as saving and loading after using a great prophet but before
    // founding a religion would break :(
    private var foundingCityId: String? = null
    // Only used for keeping track of the city a prophet was used when founding a religion

    private var shouldChoosePantheonBelief: Boolean = false


    fun clone(): ReligionManager {
        val clone = ReligionManager()
        clone.foundingCityId = foundingCityId
        clone.shouldChoosePantheonBelief = shouldChoosePantheonBelief
        clone.storedFaith = storedFaith
        clone.religionState = religionState
        clone.freeBeliefs.putAll(freeBeliefs)
        return clone
    }

    /** Whether [setTransients] has run, for [forEachBeliefUniqueMap], which units ask while a game is loading. */
    @Readonly
    fun hasCivInfo() = ::civInfo.isInitialized

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        // Find our religion from the map of founded religions.
        // First check if there is any major religion
        religion = civInfo.gameInfo.religions.values.firstOrNull {
            it.foundingCiv == civInfo && it.isMajorReligion()
        }
        // If there isn't, check for just pantheons.
        if (religion != null) return
        religion = civInfo.gameInfo.religions.values.firstOrNull {
            it.foundingCiv == civInfo
        }
    }

    fun startTurn() {
        if (canGenerateProphet()) generateProphet()
        checkReformationBelief()
    }

    /** CvPlayerReligions::HasAddedReformationBelief: whether the religion we founded already has a Reformation belief */
    @Readonly
    fun hasAddedReformationBelief(): Boolean = religion?.getBeliefs(BeliefType.Reformation)?.any() == true

    /** The bonus Reformation belief of a policy with [UniqueType.BonusReformationBelief]: a civ that has founded a religion
     *  (CvReligionClasses.cpp:438, run at the start of every turn, and CvPlayer.cpp:21384 when the policy is adopted) may add one,
     *  once. It is offered as a free belief, like the ones of [UniqueType.OneTimeFreeBelief], and picked in the same screen. */
    fun checkReformationBelief() {
        if (!civInfo.gameInfo.isReligionEnabled() || !civInfo.isMajorCiv) return
        val founded = religion ?: return
        if (!founded.isMajorReligion()) return // no religion founded yet: asked again when it is
        if (!civInfo.hasUnique(UniqueType.BonusReformationBelief)) return
        if (hasAddedReformationBelief() || freeBeliefs[BeliefType.Reformation.name] > 0) return
        if (numberOfBeliefsAvailable(BeliefType.Reformation) == 0) return
        freeBeliefs.add(BeliefType.Reformation.name, 1)
        civInfo.addNotification("You can now add a Reformation belief to your religion!",
            Notification.NotificationCategory.Religion, NotificationIcon.Faith)
    }

    fun endTurn(faithFromNewTurn: Int) {
        storedFaith += faithFromNewTurn
    }

    @Readonly
    fun isMajorityReligionForCiv(religion: Religion): Boolean =
        civInfo.cities.count { it.religion.getMajorityReligion() == religion } > civInfo.cities.size / 2

    /**
     * This helper function makes it easy to interface the Counter<String> [freeBeliefs] with functions
     * that use Counter<BeliefType>
     */
    @Readonly
    fun freeBeliefsAsEnums(): Counter<BeliefType> {
        val toReturn = Counter<BeliefType>()
        for (entry in freeBeliefs.entries) {
            toReturn.add(BeliefType.valueOf(entry.key), entry.value)
        }
        return toReturn
    }

    @Readonly fun hasFreeBeliefs(): Boolean = freeBeliefs.sumValues() > 0

    @Readonly
    fun usingFreeBeliefs(): Boolean
        = (religionState == ReligionState.None && storedFaith < faithForPantheon()) // first pantheon is free
            || religionState == ReligionState.Pantheon // any subsequent pantheons before founding a religion
            || (religionState == ReligionState.Religion || religionState == ReligionState.EnhancedReligion) // any belief adding outside of great prophet use

    @Readonly
    fun faithForPantheon(additionalCivs: Int = 0): Int {
        val gameInfo = civInfo.gameInfo
        val numCivs = additionalCivs + gameInfo.civilizations.count { it.isMajorCiv && it.religionManager.religion != null }
        val cost = gameInfo.ruleset.modOptions.constants.pantheonBase + numCivs * gameInfo.ruleset.modOptions.constants.pantheonGrowth
        return (cost * gameInfo.speed.faithCostModifier).roundToInt()
    }

    /** Used for founding the pantheon and for each time the player gets additional pantheon beliefs
     * before forming a religion */
    @Readonly
    fun canFoundOrExpandPantheon(): Boolean {
        if (!civInfo.gameInfo.isReligionEnabled()) return false
        if (religionState > ReligionState.Pantheon) return false
        if (!civInfo.isMajorCiv) return false
        if (numberOfBeliefsAvailable(BeliefType.Pantheon) == 0)
            return false // no more available pantheons
        if (civInfo.gameInfo.civilizations.any { it.religionManager.religionState == ReligionState.EnhancedReligion }
            && civInfo.gameInfo.civilizations.count { it.religionManager.religionState >= ReligionState.Pantheon } >= maxNumberOfReligions()
        ) {
            return false
        }
        return (religionState == ReligionState.None && storedFaith >= faithForPantheon()) // earned pantheon
                || freeBeliefs[BeliefType.Pantheon.name] > 0 // free pantheon belief
    }

    private fun foundPantheon(beliefName: String, useFreeBelief: Boolean) {
        if (!useFreeBelief) {
            // paid for the initial pantheon using faith
            storedFaith -= faithForPantheon()
        }
        religion = Religion(beliefName, civInfo.gameInfo, civInfo, civInfo.gameInfo.turns)
        civInfo.gameInfo.religions[beliefName] = religion!!
        for (city in civInfo.cities)
            city.religion.addPressure(beliefName, 200 * city.population.population)

        val humanPlayers = civInfo.gameInfo.civilizations.filter { it.isHuman() && it != civInfo }
        for (civ in humanPlayers) {
            val text = if (civInfo in civ.getKnownCivs()) "[${civInfo.civName}]"
            else "[An unknown civilization]"

            civ.addNotification(text + " has founded pantheon [${beliefName}]!",
                ReligionAction(beliefName), Notification.NotificationCategory.Religion, NotificationIcon.Faith)
        }
    }

    @Readonly fun greatProphetsEarned(): Int =
        civInfo.civConstructions.boughtItemsWithIncreasingPrice[getGreatProphetEquivalent()?.name ?: ""]
        // Counter.get never returns null, but it needs the correct key type, which is non-nullable

    // https://www.reddit.com/r/civ/comments/2m82wu/can_anyone_detail_the_finer_points_of_great/
    // Game files (globaldefines.xml)
    @Readonly
    fun faithForNextGreatProphet(): Int {
        val greatProphetsEarned = greatProphetsEarned()

        var faithCost =
            (200 + 100 * greatProphetsEarned * (greatProphetsEarned + 1) / 2f) *
            civInfo.gameInfo.speed.faithCostModifier

        civInfo.forEachMatchingUnique(UniqueType.FaithCostOfGreatProphetChange) { unique ->
            faithCost *= unique.params[0].toPercent()
        }

        return faithCost.toInt()
    }

    @Readonly
    fun canGenerateProphet(ignoreFaithAmount: Boolean = false): Boolean {
        if (!civInfo.gameInfo.isReligionEnabled()) return false // No religion, no prophets
        if (religion == null || religionState == ReligionState.None) return false // First get a pantheon, then we'll talk about a real religion
        if (getGreatProphetEquivalent() == null) return false
        if (!ignoreFaithAmount && storedFaith < faithForNextGreatProphet()) return false
        if (!civInfo.isMajorCiv) return false
        if (civInfo.hasUnique(UniqueType.MayNotGenerateGreatProphet)) return false
        if (religionState == ReligionState.Pantheon && remainingFoundableReligions() == 0) return false // too many have been founded
        return true
    }

    @Readonly
    fun getGreatProphetEquivalent(): BaseUnit? {
        val baseUnit = civInfo.gameInfo.ruleset.units.values.firstOrNull { it.hasUnique(UniqueType.MayFoundReligion) }
        return if (baseUnit == null) null else civInfo.getEquivalentUnit(baseUnit)
    }

    private fun generateProphet() {
        val prophetUnit = getGreatProphetEquivalent() ?: return // No prophet units in this mod
        val prophetCost = faithForNextGreatProphet()
        val prophetSpawnChance = (5f + storedFaith - prophetCost) / 100f

        if (Random(civInfo.gameInfo.turns).nextFloat() < prophetSpawnChance) {
            val birthCity =
                if (religionState <= ReligionState.Pantheon) civInfo.getCapital()
                else {
                    val holyCity = civInfo.religionManager.getHolyCity()
                    if (holyCity != null && holyCity.civ == civInfo) holyCity // If we have a holy city, use it
                    else civInfo.getCapital() // default to capital
                }
            val prophet = civInfo.units.addUnit(prophetUnit, birthCity) ?: return
            if (birthCity != null) // e.g. mosque of djenne bonuses
                prophetUnit.addConstructionBonuses(prophet, birthCity.cityConstructions)
            prophet.religion = religion!!.name
            storedFaith -= prophetCost
            civInfo.civConstructions.boughtItemsWithIncreasingPrice.add(prophetUnit.name, 1)
        }
    }

    /** The most religions this game can have (the original's CvWorldInfo::getMaxActiveReligions) */
    @Readonly
    fun maxNumberOfReligions(): Int {
        val gameInfo = civInfo.gameInfo
        val ruleset = gameInfo.ruleset
        val multiplier = ruleset.modOptions.constants.religionLimitMultiplier
        val base = ruleset.modOptions.constants.religionLimitBase
        val civCount = gameInfo.civilizations.count { it.isMajorCiv }
        return min(ruleset.religions.size, base + (civCount * multiplier).toInt())
    }

    /** Calculates the number of religions that are already founded */
    @Readonly
    private fun foundedReligionsCount() = civInfo.gameInfo.civilizations.count {
        it.religionManager.religion != null && it.religionManager.religionState >= ReligionState.Religion
    }

    /** Calculates the amount of religions that can still be founded */
    @Readonly
    fun remainingFoundableReligions(): Int {
        // count the number of foundable religions left given defined ruleset religions and number of civs in game
        val maxNumberOfAdditionalReligions = maxNumberOfReligions() - foundedReligionsCount()

        val availableBeliefsToFound = min(
            numberOfBeliefsAvailable(BeliefType.Follower),
            numberOfBeliefsAvailable(BeliefType.Founder)
        )

        return min(maxNumberOfAdditionalReligions, availableBeliefsToFound)
    }

    /** Get info breaking down the reasons behind the result of [remainingFoundableReligions] */
    @Readonly
    fun remainingFoundableReligionsBreakdown() = sequence {
        val gameInfo = civInfo.gameInfo
        val ruleset = gameInfo.ruleset
        yield("Available religion symbols" to ruleset.religions.size)

        val multiplier = ruleset.modOptions.constants.religionLimitMultiplier
        val base = ruleset.modOptions.constants.religionLimitBase
        val civCount = gameInfo.civilizations.count { it.isMajorCiv }
        val hideCivCount = civInfo.shouldHideCivCount()
        if (hideCivCount) {
            val knownCivs = 1 + civInfo.getKnownCivs().count { it.isMajorCiv }
            val estimatedCivCount = (
                    gameInfo.gameParameters.minNumberOfPlayers.coerceAtLeast(knownCivs) +
                            gameInfo.gameParameters.maxNumberOfPlayers - 1
                    ) / 2 + 1
            val civsAndBase = base + (estimatedCivCount * multiplier).toInt()
            yield("Estimated number of civilizations * [$multiplier] + [$base]" to civsAndBase)
        } else {
            val civsAndBase = base + (civCount * multiplier).toInt()
            yield("Number of civilizations * [$multiplier] + [$base]" to civsAndBase)
        }

        yield("Religions already founded" to foundedReligionsCount())
        yield("Available founder beliefs" to numberOfBeliefsAvailable(BeliefType.Founder))
        yield("Available follower beliefs" to numberOfBeliefsAvailable(BeliefType.Follower))
    }

    @Readonly
    fun numberOfBeliefsAvailable(type: BeliefType): Int {
        val gameInfo = civInfo.gameInfo
        val numberOfBeliefs = gameInfo.ruleset.beliefs.values.count { type.includes(it.type) }
        return numberOfBeliefs - gameInfo.religions.flatMap { it.value.getBeliefs(type) }.filter { type.includes(it.type) }.distinct().count()
        // We need to do the distinct above, as pantheons and religions founded out of those pantheons might share beliefs
    }

    @Readonly
    fun getReligionWithBelief(belief: Belief): Religion? =
        civInfo.gameInfo.religions.values.firstOrNull { it.hasBelief(belief.name) }


    @Readonly
    fun mayFoundReligionAtAll(): Boolean {
        if (!civInfo.gameInfo.isReligionEnabled()) return false // No religion
        if (religionState >= ReligionState.Religion) return false // Already created a major religion

        if (!civInfo.isMajorCiv) return false // Only major civs may use religion

        if (remainingFoundableReligions() == 0)
            return false // Too bad, too many religions have already been founded

        return true
    }

    @Readonly
    fun mayFoundReligionHere(tile: Tile): Boolean {
        if (!mayFoundReligionAtAll()) return false
        if (!tile.isCityCenter()) return false
        if (tile.getCity()!!.isHolyCity()) return false
        // No double holy cities. Not sure if these were allowed in the base game
        return true
    }

    fun foundReligion(prophet: MapUnit) {
        if (!mayFoundReligionHere(prophet.getTile())) return // How did you do this?
        if (religionState == ReligionState.None) shouldChoosePantheonBelief = true
        religionState = ReligionState.FoundingReligion
        civInfo.religionManager.foundingCityId = prophet.getTile().getCity()!!.id
    }

    @Readonly
    fun mayEnhanceReligionAtAll(): Boolean {
        if (!civInfo.gameInfo.isReligionEnabled()) return false
        if (religion == null) return false // First found a pantheon
        if (religionState != ReligionState.Religion) return false // First found an actual religion
        if (!civInfo.isMajorCiv) return false // Only major civs

        if (numberOfBeliefsAvailable(BeliefType.Follower) == 0)
            return false // Mod maker did not provide enough follower beliefs

        if (numberOfBeliefsAvailable(BeliefType.Enhancer) == 0)
            return false // Mod maker did not provide enough enhancer beliefs

        return true
    }

    @Readonly
    fun mayEnhanceReligionHere(tile: Tile): Boolean {
        if (!mayEnhanceReligionAtAll()) return false
        if (!tile.isCityCenter()) return false
        return true
    }

    fun useProphetForEnhancingReligion(prophet: MapUnit) {
        val currentTile = prophet.getTile()
        if (!mayEnhanceReligionHere(currentTile)) return // How did you do this?
        religionState = ReligionState.EnhancingReligion

        val humanPlayers = civInfo.gameInfo.civilizations.filter { it.isHuman() && it != civInfo }
        val religion = civInfo.religionManager.religion!!
        
        for (civ in humanPlayers) {
            val text = if (civInfo in civ.getKnownCivs()) "[${civInfo.civName}]"
            else "[An unknown civilization]"

            civ.addNotification(text + " has enhanced [${religion.displayName}]!",
                ReligionAction(religion.displayName), Notification.NotificationCategory.Religion, NotificationIcon.Faith)
        }
    }

    /**
     * Unifies the selection of what beliefs are available for when a great prophet is expended. Also
     * accounts for the number of remaining beliefs of each type so that the player is not given a
     * larger number of beliefs to select than there are available for selection ([mayFoundReligionAtAll]
     * and [mayEnhanceReligionAtAll] only check if there is 1 founder/enhancer and 1 follower belief
     * available but the civ may be given more beliefs through uniques or a missing pantheon belief)
     */
    @Readonly
    private fun getBeliefsToChooseAtProphetUse(enhancingReligion: Boolean): Counter<BeliefType> {
        val action = if (enhancingReligion) "enhancing" else "founding"
        val beliefsToChoose: Counter<BeliefType> = Counter()

        // Counter of the number of available beliefs of each type
        val availableBeliefs = Counter<BeliefType>()
        for (type in BeliefType.entries) {
            if (type == BeliefType.None) continue
            availableBeliefs[type] = numberOfBeliefsAvailable(type)
        }

        // function to help with bookkeeping
        fun chooseBeliefToAdd(type: BeliefType, number: Int) {
            val numberToAdd = min(number, availableBeliefs[type])
            beliefsToChoose.add(type, numberToAdd)
            availableBeliefs[type] = availableBeliefs[type] - numberToAdd
            if (type != BeliefType.Any && type != BeliefType.Reformation) {
                // deduct from BeliefType.Any as well
                availableBeliefs[BeliefType.Any] = availableBeliefs[BeliefType.Any] - numberToAdd
            }
        }

        if (enhancingReligion) {
            chooseBeliefToAdd(BeliefType.Enhancer, 1)
        }
        else {
            chooseBeliefToAdd(BeliefType.Founder, 1)
            if (shouldChoosePantheonBelief)
                chooseBeliefToAdd(BeliefType.Pantheon, 1)
        }
        chooseBeliefToAdd(BeliefType.Follower, 1)

        civInfo.forEachMatchingUnique(UniqueType.FreeExtraBeliefs) { unique ->
            if (unique.params[2] == action) {
                val type = BeliefType.valueOf(unique.params[1])
                chooseBeliefToAdd(type, unique.params[0].toInt())
            }
        }
        civInfo.forEachMatchingUnique(UniqueType.FreeExtraAnyBeliefs) { unique ->
            if (unique.params[1] == action)
                chooseBeliefToAdd(BeliefType.Any, unique.params[0].toInt())
        }

        for (type in freeBeliefsAsEnums())
            chooseBeliefToAdd(type.key, type.value)

        return beliefsToChoose
    }

    @Readonly fun getBeliefsToChooseAtFounding(): Counter<BeliefType> = getBeliefsToChooseAtProphetUse(false)
    @Readonly fun getBeliefsToChooseAtEnhancing(): Counter<BeliefType> = getBeliefsToChooseAtProphetUse(true)

    fun chooseBeliefs(beliefs: List<Belief>, useFreeBeliefs: Boolean = false) {
        // Remove the free beliefs in case we had them
        // Must be done first in case when gain more later
        freeBeliefs.clear()

        if (religionState == ReligionState.None)
            foundPantheon(beliefs[0].name, useFreeBeliefs)  // makes religion non-null
        // add beliefs (religion exists at this point)
        religion!!.addBeliefs(beliefs)

        when (religionState) {
            ReligionState.None -> {
                religionState = ReligionState.Pantheon
                civInfo.forEachTriggeredUnique(UniqueType.TriggerUponFoundingPantheon, ignoreCities = false) { unique ->
                    UniqueTriggerActivation.triggerUnique(unique, civInfo)
                }
            }
            ReligionState.FoundingReligion -> {
                religionState = ReligionState.Religion
                civInfo.forEachTriggeredUnique(UniqueType.TriggerUponFoundingReligion, ignoreCities = false) { unique ->
                    UniqueTriggerActivation.triggerUnique(unique, civInfo)
                }
            }
            ReligionState.EnhancingReligion -> {
                religionState = ReligionState.EnhancedReligion
                civInfo.forEachTriggeredUnique(UniqueType.TriggerUponEnhancingReligion, ignoreCities = false) { unique ->
                    UniqueTriggerActivation.triggerUnique(unique, civInfo)
                }
            }
            else -> {}
        }

        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponAdoptingPolicyOrBelief, ignoreCities = false) { unique ->
            for (belief in beliefs)
                if (unique.getModifiers(UniqueType.TriggerUponAdoptingPolicyOrBelief).any { it.params[0] == belief.name})
                    UniqueTriggerActivation.triggerUnique(unique, civInfo,
                        triggerNotificationText = "due to adopting [${belief.name}]")
        }

        for (belief in beliefs) {
            for (unique in belief.uniqueObjects) {
                if (unique.hasTriggerConditional() || !unique.conditionalsApply(civInfo.state)) continue
                repeat(unique.getUniqueMultiplier(civInfo.state)) { UniqueTriggerActivation.triggerUnique(unique, civInfo) }
            }
        }

        checkReformationBelief() // a religion founded after the policy was adopted
        civInfo.updateStatsForNextTurn()  // a belief can have an immediate effect on stats
    }


    internal fun foundReligion(displayName: String, name: String) {
        val foundingTurn = religion?.foundingTurn // use the turn when we founded our pantheon
            ?: civInfo.gameInfo.turns
        val newReligion = Religion(name, civInfo.gameInfo, civInfo, foundingTurn)
        newReligion.displayName = displayName
        if (religion != null) {
            newReligion.addBeliefs(religion!!.getAllBeliefsOrdered().asIterable())
        }

        religion = newReligion
        civInfo.gameInfo.religions[name] = newReligion

        val holyCity = civInfo.cities.first { it.id == foundingCityId }
        holyCity.religion.religionThisIsTheHolyCityOf = newReligion.name
        holyCity.religion.addPressure(name, holyCity.population.population * 500)

        foundingCityId = null
        shouldChoosePantheonBelief = false

        for (unit in civInfo.units.getCivUnits())
            if (unit.hasUnique(UniqueType.ReligiousUnit) && unit.hasUnique(UniqueType.TakeReligionOverBirthCity))
                unit.religion = newReligion.name

        val humanPlayers = civInfo.gameInfo.civilizations.filter { it.isHuman() && it != civInfo }
        for (civ in humanPlayers) {
            if (civInfo in civ.getKnownCivs()) {
                if (civ.hasExplored(holyCity.getCenterTile()))
                    civ.addNotification("[${civInfo.civName}] has founded [$displayName] in [${holyCity.name}]!",
                        ReligionAction.withLocation(holyCity.location.toHexCoord(), name),
                        Notification.NotificationCategory.Religion, NotificationIcon.Faith)
                else civ.addNotification("[${civInfo.civName}] has founded [$displayName]!",
                    ReligionAction(name), Notification.NotificationCategory.Religion, NotificationIcon.Faith)
            }
            else civ.addNotification("[An unknown civilization] has founded [$displayName]!",
                ReligionAction(name), Notification.NotificationCategory.Religion, NotificationIcon.Faith)
        }
    }

    @Readonly
    fun maySpreadReligionAtAll(missionary: MapUnit): Boolean {
        if (!civInfo.isMajorCiv) return false // Only major civs
        if (!civInfo.gameInfo.isReligionEnabled()) return false // No religion, no spreading

        val religion = missionary.civ.gameInfo.religions[missionary.religion] ?: return false
        if (religion.isPantheon()) return false
        if (UnitActionModifiers.getUsableUnitActionUniques(missionary, UniqueType.CanSpreadReligion).none()) return false
        return true
    }

    @Readonly
    fun maySpreadReligionNow(missionary: MapUnit): Boolean {
        if (!maySpreadReligionAtAll(missionary)) return false
        if (missionary.getTile().getOwner() == null) return false
        if (missionary.currentTile.owningCity?.religion?.getMajorityReligion()?.name == missionary.religion)
            return false
        if (missionary.getTile().getCity()!!.religion.isProtectedByInquisitor(missionary.religion)) return false
        return true
    }

    /** The cities of anyone whose majority is [ofReligion], by default the religion we founded */
    @Readonly
    fun numberOfCitiesFollowingThisReligion(ofReligion: Religion? = religion): Int {
        if (ofReligion == null) return 0
        return civInfo.gameInfo.getCities()
            .count { it.religion.getMajorityReligion() == ofReligion }
    }

    /** As above, only the cities that match [cityFilter] (as seen by us) count: with `in foreign cities`, CvPlayerReligions::GetNumForeignCitiesFollowing */
    @Readonly
    fun numberOfCitiesFollowingThisReligion(cityFilter: String, ofReligion: Religion? = religion): Int {
        if (ofReligion == null) return 0
        return civInfo.gameInfo.getCities()
            .count { it.religion.getMajorityReligion() == ofReligion && it.matchesFilter(cityFilter, civInfo) }
    }

    @Readonly
    fun numberOfFollowersFollowingThisReligion(cityFilter: String, ofReligion: Religion? = religion): Int {
        if (ofReligion == null) return 0
        return civInfo.gameInfo.getCities()
            .filter { it.matchesFilter(cityFilter, civInfo) }
            .sumOf { it.religion.getFollowersOf(ofReligion.name) }
    }

    @Readonly
    fun getHolyCity(): City? {
        if (religion == null) return null
        return civInfo.gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion!!.name) }
    }

    /** The religion whose founder benefits apply to us, or null: the founder of a religion (not a pantheon) only has them
     *  while it still owns the holy city - CvGameReligions::IsEligibleForFounderBenefits. A holy city blocked by an
     *  inquisitor still counts. With [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp] it is the [getOwnedReligion], which may be conquered,
     *  and a blocked holy city counts for nobody.
     *
     *  The original asks for this at the effects of a belief that are tied to the player: yields per city or follower
     *  (see CivInfoStatsForNextTurn), the city-state resting point, and the strength bonus in cities following the religion
     *  ("in cities following our religion"). Without the switch what a belief does to how the religion spreads or what missionaries cost does not
     *  depend on it, so the beliefs are not hidden from [Civilization.getMatchingUniques]; with it, see [forEachBeliefUniqueMap]. */
    @Readonly
    fun getFounderBenefitsReligion(): Religion? =
        if (validityLikeVp()) getOwnedReligion() else getFoundedReligionWithHolyCity()

    /** The religion this civilization founded, while it owns the holy city of it; not a pantheon */
    @Readonly
    private fun getFoundedReligionWithHolyCity(): Religion? {
        val founded = religion ?: return null
        if (!founded.isMajorReligion()) return null
        return if (ownsHolyCityOf(founded)) founded else null
    }

    // Before setTransients gives this manager its civilization: as without the switch, like [forEachBeliefUniqueMap]
    @Readonly
    private fun validityLikeVp() = hasCivInfo() && civInfo.gameInfo.ruleset.modOptions.constants.religiousBeliefValidityLikeVp

    /** Whether [city] is the holy city of the religion named [religionName]. With [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp] a holy city that an
     *  inquisitor of another religion has stripped ([com.unciv.logic.city.managers.CityReligionManager.isBlockedHolyCity]) is not: CvCityReligions::RemoveOtherReligions
     *  clears the holy plot of the religion (V:CvReligionClasses.cpp:5246-5253), and the holy city of a religion is that plot (CvGameReligions::SetHolyCity, 1852-1873). */
    @Readonly
    private fun isHolyCityOf(city: City, religionName: String): Boolean =
        city.religion.religionThisIsTheHolyCityOf == religionName && !(city.religion.isBlockedHolyCity && validityLikeVp())

    /** Whether [city] is the holy city of the religion we have the founder benefits of ([getFounderBenefitsReligion]) and follows it: where VP's
     *  Enhancer and Founder beliefs asked with `bHolyCityOnly` work (CvReligionBeliefs::IsBeliefValid, V:CvBeliefClasses.cpp:2181-2216), as
     *  CvCity::UpdateSpecialReligionYields asks them of the city's majority religion (V:CvCity.cpp:22340-22347). */
    @Readonly
    fun isFollowingHolyCityOfOurReligion(city: City): Boolean {
        val ours = getFounderBenefitsReligion() ?: return false
        return isHolyCityOf(city, ours.name) && city.religion.getMajorityReligion() == ours
    }

    /** Whether [city] is the holy city of the religion we have the founder benefits of ([getFounderBenefitsReligion]) and has a majority religion, any one, a pantheon
     *  included: where VP's City of God works. CvCity::setPopulation fires INSTANT_YIELD_TYPE_BIRTH_HOLY_CITY for a city with a majority (V:CvCity.cpp:16608-16615), and
     *  doInstantYield asks the state religion's Founder belief with `bHolyCityOnly` (V:CvPlayer.cpp:26028-26035), valid only in that religion's holy city and while
     *  the owner holds it (CvReligionBeliefs::IsBeliefValid, V:CvBeliefClasses.cpp:2156-2197). */
    @Readonly
    fun isHolyCityOfOurReligionWithAnyMajority(city: City): Boolean {
        val ours = getFounderBenefitsReligion() ?: return false
        return isHolyCityOf(city, ours.name) && city.religion.getMajorityReligion() != null
    }

    /** Whether the holy city of [religion] is one of ours */
    @Readonly
    fun ownsHolyCityOf(religion: Religion): Boolean = civInfo.cities.any { isHolyCityOf(it, religion.name) }

    /** CvReligionBeliefs::IsBeliefValid's `bEligibleForFounderBenefits` (V:CvBeliefClasses.cpp:2155-2176): a pantheon (it has no holy city) for its founder, a religion for
     *  the owner of its holy city, whoever founded it ("don't care about founder, ownership counts!") */
    @Readonly
    fun isEligibleForFounderBenefits(religion: Religion): Boolean =
        if (religion.isPantheon()) religion.foundingCiv == civInfo else ownsHolyCityOf(religion)

    /** CvPlayerReligions::GetOwnedReligion (V:CvReligionClasses.cpp:3742-3759): the state religion while we own its holy city, which
     *  CvPlayerReligions::UpdateStateReligion (3769-3812) records when it picks the founded or a conquered religion; a pantheon is owned by nobody.
     *  Without [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp] it is the founded religion while we own its holy city. */
    @Readonly
    fun getOwnedReligion(): Religion? = if (validityLikeVp()) getOwnedReligionLikeVp() else getFoundedReligionWithHolyCity()

    @Readonly
    private fun getOwnedReligionLikeVp(): Religion? {
        val state = getStateReligionCached() ?: return null
        return if (state.isMajorReligion() && ownsHolyCityOf(state)) state else null
    }

    /** Whether [majority], a city's majority religion, is this civilization's own: the religion it owns (CvPlayerReligions::GetOwnedReligion, whatever
     *  [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp] says, since only Vox Populi rows use it), else the one it founded while it owns its holy
     *  city (CvGameReligions::GetReligionCreatedByPlayer, V:CvReligionClasses.cpp:2335-2355), or a pantheon while it founded one (V:CvCity.cpp:25440-25451):
     *  every pantheon there is the one RELIGION_PANTHEON, so a city following another civilization's pantheon counts too. */
    @Readonly
    fun isOwnPantheonOrReligion(majority: Religion?): Boolean {
        if (majority == null) return false
        val own = getOwnedReligionLikeVp() ?: getFoundedReligionWithHolyCity()
        return majority == own || majority.isPantheon() && getOwnPantheon() != null
    }

    /** The pantheon this civilization founded, which it keeps for good even when its major religion also carries the belief
     *  (CvGameReligions::GetPantheonCreatedByPlayer). */
    @Readonly
    private fun getOwnPantheon(): Religion? =
        civInfo.gameInfo.religions.values.filter { it.foundingCiv == civInfo && it.isPantheon() }
            .sortedWith(compareBy<Religion>({ it.foundingTurn }, { it.name })).firstOrNull()

    /** The religions in the order the original keeps them in CvGameReligions::m_CurrentReligions (order of founding), for ties. */
    @Readonly
    private fun religionsInFoundingOrder(): List<Religion> =
        civInfo.gameInfo.religions.values.sortedWith(compareBy<Religion>({ it.foundingTurn }, { it.name }))

    /** What the civilization's state religion is, as CvPlayerReligions::UpdateStateReligion settles it (Vox Populi, MOD_BALANCE_VP):
     *  1. the religion it founded, while it still owns its holy city; else
     *  2. of the holy cities of other major religions it owns (conquered), the religion with the most followers in its cities
     *     (CvPlayerReligions::GetNumDomesticFollowers), a tie going to the older religion; else
     *  3. the religion followed by most of its cities (CvPlayerReligions::ComputeMajority: a true majority; every pantheon counts as one religion,
     *     which for the owner is its own pantheon, or none).
     *  The original's forced override (SetStateReligionOverride, used only by scripts) is not modelled. */
    @Readonly
    fun getStateReligion(): Religion? {
        getFoundedReligionWithHolyCity()?.let { return it }

        val owned = religionsInFoundingOrder().filter { candidate -> candidate.isMajorReligion() && ownsHolyCityOf(candidate) }
        if (owned.isNotEmpty()) {
            var best = owned.first()
            var bestFollowers = civInfo.cities.sumOf { it.religion.getFollowersOf(best.name) }
            for (candidate in owned.drop(1)) {
                val followers = civInfo.cities.sumOf { it.religion.getFollowersOf(candidate.name) }
                if (followers > bestFollowers) { best = candidate; bestFollowers = followers }
            }
            return best
        }

        val numCities = civInfo.cities.size
        var pantheonCities = 0
        val cityCounts = Counter<String>()
        for (city in civInfo.cities) {
            val majority = city.religion.getMajorityReligion() ?: continue
            if (majority.isPantheon()) pantheonCities++ else cityCounts.add(majority.name, 1)
        }
        if (pantheonCities * 2 > numCities) return getOwnPantheon()
        val majority = cityCounts.entries.firstOrNull { it.value * 2 > numCities } ?: return null
        return civInfo.gameInfo.religions[majority.key]
    }

    /** [getStateReligion], worked out once until something that it reads changes ([invalidateStateReligion]). For the hot paths. */
    @Readonly
    fun getStateReligionCached(): Religion? {
        if (stateReligionCached) return stateReligionCache
        val state = getStateReligion()
        stateReligionCache = state
        stateReligionCached = true
        return state
    }

    /** Forgets the cached state religion: call it where an input of [getStateReligion] changes (a religion founded or given beliefs, the holy city of a religion
     *  set or lost, a city founded, conquered, destroyed, or its followers recomputed), as CvCity::UpdateReligion calls CvPlayer::UpdateReligion (V:CvCity.cpp:15188). */
    fun invalidateStateReligion() {
        stateReligionCached = false
        stateReligionCache = null
    }

    /** The holy city of [religion], whoever owns it, or null (a pantheon, or the city is gone, or stripped: [isHolyCityOf]): CvReligion::GetHolyCity */
    @Readonly
    fun getHolyCityOf(religion: Religion): City? = civInfo.gameInfo.getCities().firstOrNull { isHolyCityOf(it, religion.name) }

    /** Whether a belief of [stateReligion], the state religion of this civilization, counts in the way CvReligionBeliefs::IsBeliefValid (V:CvBeliefClasses.cpp:2151-2253)
     *  answers it for the readers that pass the holy city of the state religion as the city: CvUnit::healRate (the friendly heal change) and the religious combat bonuses of
     *  CvUnit::GetGenericMeleeStrengthModifier and GetMaxRangedCombatStrength ([com.unciv.logic.battle.ReligiousCombat]). So beliefs that act through cities need that city to be
     *  ours and to follow the religion, and a Reformation belief needs the holy city to follow it; its other condition, "the holy city is ours or the religion is our state
     *  religion" (2236), holds because the reader asks only about the state religion. */
    @Readonly
    fun isStateReligionBeliefValid(belief: Belief, stateReligion: Religion, holyCity: City? = getHolyCityOf(stateReligion)): Boolean =
        isBeliefValid(belief, stateReligion, holyCity, isStateReligion = true)

    /** CvReligionBeliefs::IsBeliefValid (V:CvBeliefClasses.cpp:2151-2317) with this civilization as the player: whether [belief] of [religion] works for us, asked with [city]
     *  (null where the reader passes none) and [holyCityOnly] (`bHolyCityOnly`: a founder-side or pantheon belief only in the holy city for its owner, a Reformation one only
     *  in the capital for the others). [isStateReligion]: the caller knows that [religion] is our state religion. A reader without a player (VP passes NO_PLAYER: valid,
     *  2153-2154) does not ask. NOT modelled: MOD_BALANCE_UNIQUE_BELIEFS_ONLY_FOR_CIV (2310-2314; no belief of the rulesets needs a civilization). */
    @Readonly
    fun isBeliefValid(belief: Belief, religion: Religion, city: City? = null, holyCityOnly: Boolean = false, isStateReligion: Boolean = false): Boolean {
        val eligible = isEligibleForFounderBenefits(religion)
        val isState = { isStateReligion || getStateReligionCached() == religion }
        val capital = { civInfo.getCapital(firstCityIfNoCapital = false) }
        when (belief.type) {
            BeliefType.Founder, BeliefType.Enhancer -> {
                if (!eligible) return false
                if (city != null && city.civ != civInfo) return false
                if (holyCityOnly && city != null && !isHolyCityOf(city, religion.name)) return false
            }
            BeliefType.Follower -> {
                if (city != null && (city.religion.getMajorityReligion() != religion || city.civ != civInfo)) return false
            }
            BeliefType.Reformation -> {
                if (city != null && city.religion.getMajorityReligion() != religion) return false
                if (!eligible && !isState()) return false
                if (holyCityOnly && city != null) {
                    if (eligible && !isHolyCityOf(city, religion.name)) return false
                    if (!eligible && capital() != city) return false
                }
            }
            BeliefType.Pantheon -> {
                if (!eligible && holyCityOnly && religion.isMajorReligion() && !isState()) return false
                if (city != null) {
                    val inReligion = getOwnPantheon()?.hasBelief(belief.name) == true && religion.hasBelief(belief.name) // IsPantheonBeliefInReligion
                    if (city.religion.getMajorityReligion() != religion && !inReligion) return false
                    if (city.civ != civInfo) return false
                    if (holyCityOnly) {
                        var asked = religion
                        if (religion.isPantheon()) {
                            if (capital() != city) return false
                            val parent = getStateReligionCached()?.takeIf { it.isMajorReligion() }
                            if (parent != null) asked = parent
                        }
                        if (asked.isMajorReligion()) {
                            if (eligible && !isHolyCityOf(city, asked.name)) return false
                            if (!eligible && capital() != city) return false
                        }
                    }
                }
            }
            else -> {}
        }
        return true
    }

    /** The uniques of [uniqueType] of the Founder, Enhancer and Reformation beliefs of [religion] that [isBeliefValid] accepts for us with [city] and [holyCityOnly] */
    @Readonly
    fun forEachValidFounderSideUnique(religion: Religion, uniqueType: UniqueType, gameContext: GameContext, city: City? = null, holyCityOnly: Boolean = false,
                                      op: (Unique) -> Unit) {
        for (belief in religion.getFounderSideBeliefs())
            if (belief.hasUnique(uniqueType) && isBeliefValid(belief, religion, city, holyCityOnly)) belief.forEachMatchingUnique(uniqueType, gameContext, op)
    }

    /** Whether a Reformation belief of [religion] works for this civilization, as the Reformation branch of CvReligionBeliefs::IsBeliefValid answers it
     *  (V:CvBeliefClasses.cpp:2229-2253, without `bHolyCityOnly`): the civilization owns the holy city of the religion or has it as its state religion
     *  ([getStateReligion]), and, when [city] is asked about, the majority religion of that city is [religion]. The caller asks only if
     *  [com.unciv.models.ModConstants.reformationBeliefsLikeVp] is on. [getStateReligion] is dear (it sorts the religions and scans the cities), so the
     *  owner of the holy city is asked first and the callers keep this off the hot paths. VP asks who owns the PLOT of the holy city
     *  (V:CvBeliefClasses.cpp:2157-2176), which it clears when the city is destroyed or stripped, so the owner of the holy CITY is the same; a stripped holy city counts for
     *  nobody only with [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp] ([isHolyCityOf]).
     *  Not [isStateReligionBeliefValid]: that one asks with the holy city as the city and treats a religion without a holy city as valid for everybody. */
    @Readonly
    fun reformationBeliefApplies(religion: Religion, city: City? = null): Boolean {
        if (city != null && city.religion.getMajorityReligion() != religion) return false
        if (ownsHolyCityOf(religion)) return true
        return getStateReligionCached() == religion
    }

    /** The founder-side belief uniques that every civilization-wide lookup of this civilization reads ([com.unciv.logic.civilization.Civilization.getMatchingUniques]
     *  and its siblings), as at most two maps. Without [com.unciv.models.ModConstants.reformationBeliefsLikeVp]: all founder-side uniques of the religion it founded.
     *  With it: the Founder and Enhancer uniques of that religion, plus the civilization-wide Reformation uniques ([Religion.reformationCivUniqueMap]; the ones scoped to
     *  the cities that follow the religion are the cities' own, see [com.unciv.logic.city.managers.CityReligionManager]) of EVERY religion for which
     *  [reformationBeliefApplies] holds (its own while it owns the holy city, the state religion, and any other religion whose holy city it owns, as IsBeliefValid asks it of each belief). Most games have no
     *  Reformation belief at all, and then this is as cheap as before: [reformationBeliefApplies], which asks the state religion and that is dear, is only run for
     *  the religions that have one.
     *  With [com.unciv.models.ModConstants.religiousBeliefValidityLikeVp], as VP's player readers ask the state religion with its holy city (CvPlayerReligions::GetCityStateInfluenceModifier,
     *  V:CvReligionClasses.cpp:3967-3981): the Founder and Enhancer uniques of the [getOwnedReligion] (none for a founder that lost its holy city), and with
     *  [com.unciv.models.ModConstants.reformationBeliefsLikeVp] the civilization-wide Reformation uniques of the state religion if its holy city follows it. */
    @Readonly
    inline fun forEachBeliefUniqueMap(op: (UniqueMap) -> Unit) {
        val founded = religion
        // While a game loads, the units ask for uniques before this manager has its civilization: that is as without the switch
        if (!hasCivInfo()) {
            if (founded != null) op(founded.founderBeliefUniqueMap)
            return
        }
        val constants = civInfo.gameInfo.ruleset.modOptions.constants
        if (constants.religiousBeliefValidityLikeVp) {
            val owned = getOwnedReligion()
            if (!constants.reformationBeliefsLikeVp) {
                if (owned != null) op(owned.founderBeliefUniqueMap)
                return
            }
            if (owned != null) op(owned.founderWithoutReformationUniqueMap)
            val state = getStateReligionCached()
            if (state != null && !state.reformationCivUniqueMap.isEmpty() && reformationBeliefApplies(state, getHolyCityOf(state))) op(state.reformationCivUniqueMap)
            return
        }
        if (!constants.reformationBeliefsLikeVp) {
            if (founded != null) op(founded.founderBeliefUniqueMap)
            return
        }
        if (founded != null) op(founded.founderWithoutReformationUniqueMap)
        for (candidate in civInfo.gameInfo.religions.values)
            if (!candidate.reformationCivUniqueMap.isEmpty() && reformationBeliefApplies(candidate)) op(candidate.reformationCivUniqueMap)
    }

    /** The first belief of the pantheon of this civilization, as CvGameReligions::GetBeliefInPantheon finds it: the first religion in founding order
     *  that is the pantheon founded by us, or is the (non-pantheon) state religion, gives its first belief. */
    @Readonly
    private fun getBeliefInPantheon(stateReligion: Religion?): Belief? {
        for (candidate in religionsInFoundingOrder()) {
            if (candidate.foundingCiv == civInfo && candidate.isPantheon()) return candidate.getAllBeliefsOrdered().firstOrNull()
            if (!candidate.isPantheon() && candidate == stateReligion) return candidate.getAllBeliefsOrdered().firstOrNull()
        }
        return null
    }

    /** The HP per turn [unit] heals in territory of this civilization through beliefs, as CvUnit::healRate adds them (MOD_BALANCE_VP) to its friendly heal:
     *  the state religion's beliefs, the belief of the second religion of the capital if the civilization may use it (secondary pantheon), and the
     *  civilization's own pantheon belief, which it keeps for good (MOD_BALANCE_PERMANENT_PANTHEONS) unless the state religion already has it.
     *  The caller checks that the tile is ours and that we have a capital. The belief effect that the original calls FriendlyHealChange is
     *  [UniqueType.CityHealingUnits] here. */
    @Readonly
    fun getBeliefHealingFor(unit: MapUnit): Int {
        val stateReligion = getStateReligion()
        var total = 0
        var secondary: Belief? = null
        if (stateReligion != null) {
            secondary = civInfo.getCapital(firstCityIfNoCapital = false)?.religion?.getSecondaryPantheonBelief()
            for (belief in stateReligion.getAllBeliefsOrdered())
                if (isStateReligionBeliefValid(belief, stateReligion)) total += healingOfBelief(belief, unit)
            if (secondary != null) total += healingOfBelief(secondary, unit)
        }

        if (civInfo.gameInfo.religions.values.none { it.foundingCiv == civInfo }) return total // CvGameReligions::HasCreatedPantheon
        val pantheonBelief = getBeliefInPantheon(stateReligion) ?: return total
        if (pantheonBelief == secondary) return total
        val ownPantheon = getOwnPantheon()
        val alreadyInReligion = stateReligion != null && ownPantheon?.hasBelief(pantheonBelief.name) == true && stateReligion.hasBelief(pantheonBelief.name)
        if (ownPantheon != null && !alreadyInReligion) total += healingOfBelief(pantheonBelief, unit)
        return total
    }

    @Readonly
    private fun healingOfBelief(belief: Belief, unit: MapUnit): Int =
        belief.uniqueMap.getMatchingUniques(UniqueType.CityHealingUnits, unit.cache.state)
            .filter { unit.matchesFilter(it.params[0]) }.sumOf { it.params[1].toInt() }

    @Readonly
    fun getMajorityReligion(): Religion? {
        // let's count for each religion (among those actually presents in civ's cities)
        val religionCounter = Counter<Religion>()
        for (city in civInfo.cities) {
            // if city's majority Religion is null, let's just continue to next loop iteration
            val cityMajorityReligion = city.religion.getMajorityReligion() ?: continue
            // if not yet continued to next iteration from previous line, let's add the Religion to religionCounter
            religionCounter.add(cityMajorityReligion, 1)
        }
        // let's get the max-counted Religion if there is one, null otherwise; if null, return null
        val maxReligionCounterEntry = religionCounter.maxByOrNull { it.value } ?: return null
        // if not returned null from prev. line, check if the maxReligion is in most of the cities
        return if (maxReligionCounterEntry.value > civInfo.cities.size / 2)
        // if maxReligionCounterEntry > half-cities-count we return the Religion of maxReligionCounterEntry
            maxReligionCounterEntry.key
        else
        // if maxReligionCounterEntry <= half-cities-count we just return null
            null
    }
}

enum class ReligionState : IsPartOfGameInfoSerialization {
    None,
    Pantheon,
    FoundingReligion, // Great prophet used, but religion has not yet been founded
    Religion,
    EnhancingReligion, // Great prophet used, but religion has not yet been enhanced
    EnhancedReligion;

    override fun toString(): String {
        // Yes, this is the ugliest way to convert camel case to human readable format I've seen as well, but it works.
        return super.toString()
            .map { letter -> (if (letter.isUpperCase()) " " else "") + letter.lowercase() }
            .joinToString("")
            .removePrefix(" ")
            .replaceFirstChar { it.uppercase() }
    }
}
