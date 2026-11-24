package com.unciv.logic.civilization.managers

import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.TemporaryUnique
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt
import kotlin.random.Random

/** What became of the spy that did a mission (CvSpyResult: SPY_RESULT_DETECTED is the spy that was not detected) */
enum class SpyMissionResult { Undetected, Identified, Killed, KilledUnidentified }

/** What a mission can need of the city besides its filters, the parameter of [UniqueType.MissionNeeds] */
enum class SpyMissionRequirement(val label: String) {
    TheCity("the city"),
    NoFreshWater("no fresh water"),
    TradeConnection("a trade connection"),
    UnhappyOwner("an unhappy owner"),
    NoPuppet("no puppet");

    /** Whether [city], the target of [spy], meets it (CvCity::IsCityEventChoiceValid, V:CvCity.cpp:4191 fresh water, 4212 not a puppet, 4412 trade connection, 4721 unhappy owner) */
    @Readonly
    fun isMet(spy: Spy, city: com.unciv.logic.city.City): Boolean = when (this) {
        TheCity -> true
        NoFreshWater -> !city.getCenterTile().isAdjacentTo(com.unciv.Constants.freshWater)
        // CvCity::HasTradeRouteFromAnyCity and HasTradeRouteToAnyCity (V:CvCity.cpp:34705-34747): the city is the start or the end of any route of any civilization
        TradeConnection -> spy.civInfo.gameInfo.civilizations.any { civ -> civ.tradeRoutes.routes.any { it.origin == city.location || it.destination == city.location } }
        UnhappyOwner -> city.civ.isEmpireUnhappy()
        NoPuppet -> !city.isPuppet
    }

    companion object {
        @Readonly fun fromLabel(label: String) = entries.firstOrNull { it.label == label }
    }
}

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
    fun unavailableReason(spy: Spy, mission: SpyMission): String? = refusal(spy, mission, ignoreWaiting = false)

    /**
     *  Whether [spy] can work towards [mission] in the city it is in: every condition of [unavailableReason] except the two that time takes care of, the wait after its last mission and
     *  the Network Points it has stored. What the AI asks (CvEspionageAI::GetMissionScore is -1 for a mission whose requirements are not met, not for one that waits for points), so it needs
     *  no reading of the wording of a reason.
     */
    @Readonly
    fun canWorkTowards(spy: Spy, mission: SpyMission): Boolean = refusal(spy, mission, ignoreWaiting = true) == null

    @Readonly
    private fun refusal(spy: Spy, mission: SpyMission, ignoreWaiting: Boolean): String? {
        val civ = spy.civInfo
        val constants = civ.gameInfo.ruleset.modOptions.constants
        if (!constants.espionageLikeVp) return "Missions need the espionage of Vox Populi"
        if (mission.isFocus) return "This is a focus, not a mission"
        val city = spy.getCityOrNull() ?: return "The spy is in the hideout"
        if (spy.action != SpyAction.GatheringIntel) return "The spy is not gathering intel"
        if (city.civ == civ || !city.civ.isMajorCiv) return "Only in the cities of other major civilizations"
        if (spy.getEffectiveRank() < mission.requiredSpyRank) return "Needs a spy of rank [${mission.requiredSpyRank}]"
        if (!ignoreWaiting) {
            val cooldown = civ.espionageManager.getMissionCooldownLeft(spy)
            if (cooldown > 0) return "The spy must wait [$cooldown] more turns"
            val cost = mission.getNetworkPointsNeeded(civ.gameInfo.speed)
            if (cost > 0 && spy.networkPoints < cost) return "Needs [$cost] Network Points, [${spy.networkPoints}] stored"
        }
        val tech = mission.requiredTech
        if (tech != null && !civ.tech.isResearched(tech)) return "Needs the technology [$tech]"
        if (!city.matchesFilter(mission.cityFilter, civ)) return "Only in [${mission.cityFilter}] cities"
        // the focus of the city's counterspy stops some missions (CvCity::CanDoEventChoice, V:CvCity.cpp:4125-4180)
        val blocking = city.espionage.getFocus()?.name
        if (blocking != null && blocking in mission.blockedBy) return "Blocked by the focus [$blocking]"
        // a mission that still runs here, or one of its group, is not done again (V:CvCity.cpp:4059-4100, "active" is a duration above 0)
        if (city.activeMissions.isNotEmpty()) {
            val missions = civ.gameInfo.ruleset.spyMissions
            val turn = civ.gameInfo.turns
            for ((name, endTurn) in city.activeMissions) {
                if (endTurn <= turn) continue
                if (name == mission.name) return "[$name] is still going on in this city"
                if (mission.group != 0 && missions[name]?.group == mission.group) return "[$name] is still going on in this city"
            }
        }
        // every `Needs` must hold: its requirement, and its own conditionals with the target city (a conditional that fails is the requirement not met, not the requirement gone)
        for (unique in mission.getMatchingUniques(UniqueType.MissionNeeds, GameContext.IgnoreConditionals)) {
            val needs = SpyMissionRequirement.fromLabel(unique.params[0]) ?: continue
            if (!needs.isMet(spy, city) || !unique.conditionalsApply(city.state)) return "Needs [${needs.label}]"
        }
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
     *  What [mission] does to the city [spy] is in (CvCity::DoEventChoice, V:CvCity.cpp:6560-7126): its treasury and siphon effects, the pillage, and every unique with `<for [N] turns>` as an effect of
     *  the city that ends by itself. The mission is active in the city for its [SpyMission.duration] (V:CvCity.cpp:3264-3293). The technology it steals is done by [perform].
     */
    private fun applyEffects(spy: Spy, mission: SpyMission, random: Random) {
        val civ = spy.civInfo
        val city = spy.getCity()
        val owner = city.civ
        val turn = civ.gameInfo.turns
        for (unique in mission.uniqueObjects) {
            when (unique.type) {
                UniqueType.StealTreasury -> {
                    // the percent is divided by the speed's production cost modifier (V:CvCity.cpp:6616-6630), the gold is the whole treasury of the city's owner
                    val percent = unique.params[0].toInt() * 100 / (civ.gameInfo.speed.productionCostModifier * 100).roundToInt()
                    val stolen = owner.gold * percent / 100
                    owner.addGold(-stolen)
                    civ.addGold(stolen)
                    spy.addNotification("Your spy [${spy.name}] stole [$stolen] Gold from [${owner.civName}]!")
                    owner.addNotification("Gold was stolen from our treasury: [$stolen]!", city.location, NotificationCategory.Espionage, NotificationIcon.Spy)
                }
                UniqueType.SiphonYield -> siphon(unique, civ, city, Stat.valueOf(unique.params[1]), unique.params[0].toInt(), unique.params[2].toInt())
                UniqueType.PillageAroundCity -> pillage(city, unique.params[0].toInt(), random, skipCenter = true) { tile ->
                    tile.getUnpillagedTileImprovement()?.matchesFilter(unique.params[1], tile.stateThisTile) == true && tile.canPillageTileImprovement()
                }
                UniqueType.PillageResourcesAroundCity -> pillage(city, unique.params[0].toInt(), random, skipCenter = false) { tile ->
                    val resource = tile.tileResource
                    resource != null && tile.canPillageTileImprovement() && tile.getUnpillagedTileImprovement()!!.name in resource.getImprovements()
                }
                UniqueType.PillageRoadsAroundCity -> pillage(city, unique.params[0].toInt(), random, skipCenter = true, road = true) { it.canPillageRoad() }
                else -> {
                    val turns = unique.getModifiers(UniqueType.ConditionalTimedUnique).firstOrNull()?.params?.get(0)?.toIntOrNull() ?: continue
                    city.missionEffects.add(TemporaryUnique(unique, turns))
                }
            }
        }
        if (mission.duration > 0) city.activeMissions[mission.name] = turn + mission.duration
        if (city.missionEffects.isNotEmpty()) civ.cache.updateCivResources()
    }

    /**
     *  A siphon (V:CvCity.cpp:6822-6843, CvPlayer::AddEspionageEvent): the amount the city yields now, a whole number, is paid to [civ] and taken from the city's owner every turn for [turns]
     *  turns, as a flat temporary `[stats]` of each civilization that neither side's modifiers change afterwards
     */
    private fun siphon(source: Unique, civ: Civilization, city: City, stat: Stat, percent: Int, turns: Int) {
        val amount = ((city.cityStats.currentCityStats[stat] * 100).toInt() * percent / 10000)
        if (amount <= 0) return
        civ.temporaryUniques.add(TemporaryUnique("[+$amount ${stat.name}]", source, turns))
        city.civ.temporaryUniques.add(TemporaryUnique("[-$amount ${stat.name}]", source, turns))
        civ.updateStatsForNextTurn()
        city.civ.updateStatsForNextTurn()
    }

    /**
     *  Pillages the tiles of [city] that [matches], each with [chance] percent, rolled for each unless it is 100 (V:CvCity.cpp:7047-7122), in the order of their position so that the rolls are the
     *  same on every machine. [skipCenter]: the center tile stays as it is, which is so for roads and fortifications but not for resources. [road] pillages the road and not the improvement
     */
    private fun pillage(city: City, chance: Int, random: Random, skipCenter: Boolean, road: Boolean = false, matches: (Tile) -> Boolean) {
        for (tile in city.getTiles().sortedWith(compareBy({ it.position.x }, { it.position.y })).toList()) {
            if (skipCenter && tile.isCityCenter()) continue
            if (!matches(tile)) continue
            if (chance < 100 && random.nextInt(1, 101) > chance) continue
            if (road) tile.setRoadPillaged() else tile.setPillaged()
        }
    }

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

        // The instant yields of the result (V:CvEspionageClasses.cpp:1586-1637, 1663-1669), while the spy is still in the city: the city's owner is paid in that city for a spy that was found out
        // (its buildings' tables, its counterspy focus and Double Agents, one trigger filtered by what became of the spy), and the spy's owner in every city for any mission
        InstantYields.spyOutcome(owner, city, killed, identified)
        InstantYields.spyMissionPerformed(civ)

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

        applyEffects(spy, mission, random)

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
