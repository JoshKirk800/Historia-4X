package com.unciv.logic.civilization.managers

import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

/** What became of the spy that did a mission (CvSpyResult: SPY_RESULT_DETECTED is the spy that was not detected) */
enum class SpyMissionResult { Undetected, Identified, Killed, KilledUnidentified }

/**
 *  Doing a mission with a spy (CvPlayerEspionage::ProcessSpyMissionResult, V:CvEspionageClasses.cpp:1556-1785, CvCity::DoEventChoice, V:CvCity.cpp:6490-6640 and
 *  CvCity::CanDoEventChoice, V:CvCity.cpp:4590-4770). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Only with [com.unciv.models.ModConstants.espionageLikeVp].
 *
 *  The Network Points are paid first and always; then the spy is identified and killed by two rolls of its own, and the effects of the mission happen whether or not the spy
 *  lives. The spy that lives waits for [EspionageManager.getMissionCooldownLeft] turns before its next mission. The foci of the owner's counterspies lower the chance to be identified, and
 *  the focus of the city's counterspy can stop a mission. Not read yet: the yields of the instant-yield events of spies.
 */
object SpyMissionResolver {
    /**
     *  Why [spy] cannot do [mission] now, or null if it can (CvCity::CanDoEventChoice): the spy gathers intel in the city of another major civilization, has the rank, has waited for its
     *  last mission, has the Network Points, its owner has the technology, the city is what the mission wants, and there is something for the effects to do.
     */
    @Readonly
    fun unavailableReason(spy: Spy, mission: SpyMission): String? {
        val civ = spy.civInfo
        val constants = civ.gameInfo.ruleset.modOptions.constants
        if (!constants.espionageLikeVp) return "Missions need the espionage of Vox Populi"
        if (mission.isFocus) return "This is a focus, not a mission"
        val city = spy.getCityOrNull() ?: return "The spy is in the hideout"
        if (spy.action != SpyAction.GatheringIntel) return "The spy is not gathering intel"
        if (city.civ == civ || !city.civ.isMajorCiv) return "Only in the cities of other major civilizations"
        if (spy.getEffectiveRank() < mission.requiredSpyRank) return "Needs a spy of rank [${mission.requiredSpyRank}]"
        val cooldown = civ.espionageManager.getMissionCooldownLeft(spy)
        if (cooldown > 0) return "The spy must wait [$cooldown] more turns"
        val cost = mission.getNetworkPointsNeeded(civ.gameInfo.speed)
        if (cost > 0 && spy.networkPoints < cost) return "Needs [$cost] Network Points, [${spy.networkPoints}] stored"
        val tech = mission.requiredTech
        if (tech != null && !civ.tech.isResearched(tech)) return "Needs the technology [$tech]"
        if (!city.matchesFilter(mission.cityFilter, civ)) return "Only in [${mission.cityFilter}] cities"
        // the focus of the city's counterspy stops some missions (CvCity::CanDoEventChoice, V:CvCity.cpp:4125-4180)
        val blocking = city.espionage.getFocus()?.name
        if (blocking != null && blocking in mission.blockedBy) return "Blocked by the focus [$blocking]"
        for (unique in mission.getMatchingUniques(UniqueType.StealTechnologies))
            if (civ.espionageManager.getTechsToSteal(city.civ).size < unique.params[0].toInt()) return "There is no technology to steal"
        return null
    }

    /**
     *  Why [spy], which counters spies in an own city, cannot choose [focus] now (CvCity::CanDoEventChoice for a counterspy mission), or null if it can: it is a focus, the spy is in the state
     *  `CounterIntelligence` in an own city, its owner has the technology and the city is what the focus wants
     */
    @Readonly
    fun unavailableFocusReason(spy: Spy, focus: SpyMission): String? {
        val civ = spy.civInfo
        if (!civ.gameInfo.ruleset.modOptions.constants.espionageLikeVp) return "Foci need the espionage of Vox Populi"
        if (!focus.isFocus) return "This is no focus"
        val city = spy.getCityOrNull() ?: return "The spy is in the hideout"
        if (spy.action != SpyAction.CounterIntelligence || city.civ != civ) return "The spy is not countering spies in one of our cities"
        val tech = focus.requiredTech
        if (tech != null && !civ.tech.isResearched(tech)) return "Needs the technology [$tech]"
        if (!city.matchesFilter(focus.cityFilter, civ)) return "Only in [${focus.cityFilter}] cities"
        return null
    }

    /** The missions [spy] can do now; the foci a spy countering spies can choose are [availableFoci] */
    @Readonly
    fun availableMissions(spy: Spy): List<SpyMission> =
        spy.civInfo.gameInfo.ruleset.spyMissions.values.filter { unavailableReason(spy, it) == null }

    /** The foci [spy] can choose now */
    @Readonly
    fun availableFoci(spy: Spy): List<SpyMission> =
        spy.civInfo.gameInfo.ruleset.spyMissions.values.filter { unavailableFocusReason(spy, it) == null }

    /**
     *  [spy] does [mission] (null if it cannot: [unavailableReason]). The rolls are seeded by the city, the civilization, the mission and the number of missions that civilization
     *  has done against the city's owner, so that loading a saved game and doing it again gives the same result.
     */
    fun perform(spy: Spy, mission: SpyMission): SpyMissionResult? {
        if (unavailableReason(spy, mission) != null) return null
        val civ = spy.civInfo
        val city = spy.getCity()
        val owner = city.civ
        val manager = civ.espionageManager
        val turn = civ.gameInfo.turns

        spy.spendNetworkPoints(mission.getNetworkPointsNeeded(civ.gameInfo.speed))

        val done = manager.spyActionsDone[owner.civID] ?: 0
        val seed = city.location.x * 31 + city.location.y + civ.civID.hashCode() * 17 + mission.name.hashCode() * 7 + done
        // the foci of our counterspies make us harder to find, in every city (CvPlayer::GetSpyIdentificationChance)
        val identifyChance = (mission.identifyChance - manager.getIdentifyChanceReduction()).coerceIn(0, 100)
        val identified = Random(seed xor 0x5a5a5a).nextInt(1, 101) <= identifyChance
        val killed = Random(seed xor 0x3c3c3c).nextInt(1, 101) <= mission.killChance.coerceIn(0, 100)
        val result = when {
            killed -> if (identified) SpyMissionResult.Killed else SpyMissionResult.KilledUnidentified
            identified -> SpyMissionResult.Identified
            else -> SpyMissionResult.Undetected
        }

        manager.spyActionsDone[owner.civID] = done + 1
        owner.espionageManager.timesCityRobbed.merge(city.id, 1, Int::plus)
        if (identified) {
            // the memory of the victim's AI, as CvPlayerEspionage::ProcessSpyMissionResult records it
            DiplomacyEvents.onRobbed(owner, civ)
            DiplomacyEvents.onSpyCaught(owner, civ)
            owner.getDiplomacyManager(civ)?.setFlag(DiplomacyFlags.DiscoveredSpiesInOurCities, 30, true)
        }
        if (killed) DiplomacyEvents.onSpyKilled(owner, civ)

        // the effects of the mission happen whether or not the spy lives (V:CvCity.cpp:6616-6640)
        val random = Random(seed xor 0x777777)
        for (unique in mission.getMatchingUniques(UniqueType.StealTechnologies)) {
            val stealable = manager.getTechsToSteal(owner).sorted().toMutableList()
            repeat(minOf(unique.params[0].toInt(), stealable.size)) {
                val tech = stealable.removeAt(random.nextInt(stealable.size))
                civ.tech.addTechnology(tech)
                spy.addNotification("Your spy [${spy.name}] stole the Technology [$tech] from [${city.name}]!")
                val who = if (identified) "A spy from [${civ.civName}]" else "An unidentified spy"
                owner.addNotification("$who stole the Technology [$tech] from [${city.name}]!", city.location, NotificationCategory.Espionage, NotificationIcon.Spy)
            }
        }

        when (result) {
            SpyMissionResult.Killed, SpyMissionResult.KilledUnidentified -> {
                spy.addNotification(
                    if (identified) "Your spy [${spy.name}] was identified and killed by [${owner.civName}] doing [${mission.name}] in [${city.name}]!"
                    else "Your spy [${spy.name}] was killed doing [${mission.name}] in [${city.name}]!")
                owner.addNotification(
                    if (identified) "A spy from [${civ.civName}] was found and killed doing [${mission.name}] in [${city.name}]!"
                    else "A spy was found and killed doing [${mission.name}] in [${city.name}]!",
                    city.location, NotificationCategory.Espionage, NotificationIcon.Spy)
                spy.killSpy()
            }
            SpyMissionResult.Identified -> {
                spy.addNotification("Your spy [${spy.name}] was identified by [${owner.civName}] doing [${mission.name}] in [${city.name}]!")
                owner.addNotification("A spy from [${civ.civName}] did [${mission.name}] in [${city.name}]!",
                    city.location, NotificationCategory.Espionage, NotificationIcon.Spy)
                spy.finishMission(turn)
            }
            SpyMissionResult.Undetected -> {
                spy.addNotification("Your spy [${spy.name}] was not detected doing [${mission.name}] in [${city.name}]!")
                spy.finishMission(turn)
            }
        }
        return result
    }
}
