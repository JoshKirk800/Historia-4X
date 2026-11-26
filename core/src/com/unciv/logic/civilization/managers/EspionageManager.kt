package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.city.managers.CityEspionageManager
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.devconsole.ConsoleCivCommands
import yairm210.purity.annotations.Readonly


class EspionageManager : IsPartOfGameInfoSerialization {

    var spyList = ArrayList<Spy>()
    val erasSpyEarnedFor = LinkedHashSet<String>()

    /** The game turn our first spy arrived (CvEspionageAI::m_iTurnEspionageStarted), -1 before; read by espionage strategy tests */
    var turnEspionageStarted = -1
    /** How often a foreign spy stole a technology in each of our cities, by [City.id] (CvCityEspionage::m_aiNumTimesCityRobbed) */
    var timesCityRobbed = HashMap<String, Int>()
    /** The steal rate x100 our spy saw when surveillance was established in a foreign city, by [City.id] (CvCityEspionage::m_aiLastBasePotential) */
    var lastBasePotential = HashMap<String, Int>()
    /** AI memory: the turn each civilization (by civID) we had surveillance on last had nothing left to steal (CvEspionageAI::m_aiCivOutOfTechTurn) */
    var civOutOfTechTurn = HashMap<String, Int>()
    /** AI memory: whether the United Nations countdown already made every spy reconsider its post (CvEspionageAI::m_bUNCountdownStarted) */
    var unCountdownStarted = false
    /** The intrigue we know, uncovered by our spies or told to us (CvPlayerEspionage::m_aIntrigueNotificationMessages) */
    var intrigueMessages = ArrayList<IntrigueMessage>()
    /**
     *  The spy points below the threshold (CvPlayer::m_iSpyPoints); below 0 the civilization is in debt, which the next points pay first (V:CvPlayer.cpp:23314-23326).
     *  Only with [com.unciv.models.ModConstants.spyPointsLikeVp]; 0 in a save from before.
     */
    var spyPoints = 0
    /** Every spy point ever gained, never reduced by making a spy (CvPlayer::m_iSpyPointsTotal, V:CvPlayer.cpp:23286-23290) */
    var spyPointsTotal = 0
    /** How many missions our spies did against each civilization (by civID), which seeds the rolls of the next (CvPlayerEspionage::m_aiNumSpyActionsDone). Only with [com.unciv.models.ModConstants.espionageLikeVp] */
    var spyActionsDone = HashMap<String, Int>()

    @Transient
    lateinit var civInfo: Civilization

    /**
     *  Counts the changes of what our spies counter in our cities: a spy that starts or stops countering spies, moves, or chooses a focus (see [Spy.setAction], [Spy.chooseFocus]). A city
     *  derives its counterspy focus from the spies and keeps it until this number differs from the one it saw ([com.unciv.logic.city.managers.CityEspionageManager.getFocus]), so a
     *  lookup of the city's uniques is a comparison of two numbers and nothing is stored that could go out of step. Not saved: a loaded game derives everything again.
     */
    @Transient
    var focusVersion = 0

    /**
     * Part of the nextTurnAction of MoveSpies.
     * We need to store if the player has clicked the button already
     */
    @Transient
    var dismissedShouldMoveSpies = false

    fun clone(): EspionageManager {
        val toReturn = EspionageManager()
        spyList.mapTo(toReturn.spyList) { it.clone() }
        toReturn.erasSpyEarnedFor.addAll(erasSpyEarnedFor)
        toReturn.turnEspionageStarted = turnEspionageStarted
        toReturn.timesCityRobbed.putAll(timesCityRobbed)
        toReturn.lastBasePotential.putAll(lastBasePotential)
        toReturn.civOutOfTechTurn.putAll(civOutOfTechTurn)
        toReturn.unCountdownStarted = unCountdownStarted
        toReturn.spyPoints = spyPoints
        toReturn.spyPointsTotal = spyPointsTotal
        toReturn.spyActionsDone.putAll(spyActionsDone)
        intrigueMessages.mapTo(toReturn.intrigueMessages) { it.clone() }
        return toReturn
    }

    private companion object {
        /** CvEspionageClasses.cpp: iIntrigueTurnsValid */
        const val INTRIGUE_TURNS_VALID = 5
    }

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        for (spy in spyList) {
            spy.setTransients(civInfo)
        }
        // Saves from before the field existed: espionage started at the latest now
        if (turnEspionageStarted == -1 && spyList.isNotEmpty()) turnEspionageStarted = civInfo.gameInfo.turns
        if (civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp) migrateToNetworkPoints()
    }

    /**
     *  A game saved under Brave New World's espionage and loaded with [com.unciv.models.ModConstants.espionageLikeVp]: a spy that was establishing a network, observing or stealing
     *  gathers Network Points from nothing, one making introductions schmoozes, one preparing a coup rigs the election. A spy that counters spies stays
     */
    private fun migrateToNetworkPoints() {
        for (spy in spyList) when (spy.action) {
            SpyAction.EstablishNetwork, SpyAction.Surveillance, SpyAction.StealingTech -> spy.setAction(SpyAction.GatheringIntel)
            SpyAction.MakingIntroductions -> spy.setAction(SpyAction.Schmoozing)
            SpyAction.Coup -> spy.setAction(SpyAction.RiggingElections)
            else -> {}
        }
    }

    fun endTurn() {
        for (spy in spyList.toList()) {
            val city = spy.getCityOrNull()
            // Razing removes the city even for ordinary spies. War, capture or a new capital ends a diplomat's post.
            if ((city == null && !spy.isIdle() && spy.isAlive()) ||
                (spy.isDiplomat && (city == null || !spy.canMoveTo(city, asDiplomat = true))))
                spy.moveTo(null)
            spy.endTurn()
        }
    }

    //region Intrigue
    /**
     * CvPlayerEspionage::AddIntrigueMessage: remember a piece of intrigue found out by [discoverer] ([civInfo] or an AI that told us), unless we
     * know it already: a message of the same source, target and type within the last 30 turns (for Deception and the armies being built that
     * is all that is compared, for the others the construction and the city too). A human gets the notification if [notify].
     * Returns whether the message was added.
     */
    fun addIntrigueMessage(
        discoverer: Civilization, source: Civilization, target: Civilization?, targetHidden: Boolean, construction: String?,
        type: IntrigueType, city: City?, notify: Boolean, spy: Spy?
    ): Boolean {
        val turn = civInfo.gameInfo.turns
        val cityLocation = city?.location
        for (known in intrigueMessages) {
            if (known.source != source.civID || known.target != target?.civID || known.targetHidden != targetHidden || known.type != type) continue
            val withinTimer = turn - known.turn < 30
            if (type == IntrigueType.Deception || type == IntrigueType.BuildingArmy || type == IntrigueType.BuildingAmphibiousArmy) {
                if (withinTimer) return false
            } else if (known.construction == construction && known.cityLocation == cityLocation && withinTimer) return false
        }
        val message = IntrigueMessage()
        message.discoverer = discoverer.civID
        message.source = source.civID
        message.target = target?.civID
        message.targetHidden = targetHidden
        message.construction = construction
        message.type = type
        message.cityLocation = cityLocation
        message.turn = turn
        if (discoverer == civInfo && spy != null) message.spyName = spy.name
        intrigueMessages.add(message)
        if (notify) IntrigueFunctions.notify(civInfo, message)
        return true
    }

    /** CvPlayerEspionage::GetRecentIntrigueInfo: the first message about [target] that is not shared yet and at most 5 turns old (iIntrigueTurnsValid) */
    @Readonly
    fun getRecentIntrigueInfo(target: Civilization): IntrigueMessage? =
        intrigueMessages.firstOrNull { !it.shared && it.target == target.civID && it.turn >= civInfo.gameInfo.turns - INTRIGUE_TURNS_VALID }

    /** CvPlayerEspionage::HasRecentIntrigueAbout */
    @Readonly fun hasRecentIntrigueAbout(target: Civilization) = getRecentIntrigueInfo(target) != null

    /** CvPlayerEspionage::HasSharedIntrigue: we found this out and told [target] about it within the last 30 turns */
    @Readonly
    fun hasSharedIntrigue(target: Civilization, source: Civilization, type: IntrigueType): Boolean =
        intrigueMessages.any {
            it.shared && it.discoverer == civInfo.civID && it.target == target.civID && it.source == source.civID && it.type == type &&
                civInfo.gameInfo.turns - it.turn <= 30
        }

    /** CvPlayerEspionage::MarkRecentIntrigueAsShared: [target] knows now what we found out of this kind. Returns the number of messages marked. */
    fun markRecentIntrigueAsShared(target: Civilization, source: Civilization, type: IntrigueType): Int {
        var count = 0
        for (message in intrigueMessages) {
            if (message.shared || message.discoverer != civInfo.civID || message.target != target.civID ||
                message.source != source.civID || message.type != type) continue
            message.shared = true
            count++
        }
        return count
    }
    //endregion

    @Readonly
    fun getSpyName(): String {
        val rng = civInfo.state.stateBasedRandom("EspianageManager.getSpyName")
        val usedSpyNames = spyList.map { it.name }.toHashSet()
        val validSpyNames = civInfo.nation.spyNames.filter { it !in usedSpyNames }
        return validSpyNames.randomOrNull(rng)
            ?: "Spy ${spyList.size + 1}" // +1 as non-programmers count from 1
    }

    fun addSpy(): Spy {
        val spyName = getSpyName()
        val newSpy = Spy(spyName, getStartingSpyRank())
        newSpy.setTransients(civInfo)
        spyList.add(newSpy)
        if (turnEspionageStarted == -1) turnEspionageStarted = civInfo.gameInfo.turns
        return newSpy
    }

    /**
     *  The spy points that make a spy (CvGame::initSpyThreshold, V:CvGame.cpp:10722-10751): `100 x ratio / (city-states + multiplier x majors)` of all the players the game
     *  started with, at least one of each, between the minimum and the maximum of [com.unciv.models.ModConstants.espionage]. Civilizations that were defeated stay in the list.
     */
    @Readonly
    fun spyThreshold(): Int {
        val constants = civInfo.gameInfo.ruleset.modOptions.constants.espionage
        val players = civInfo.gameInfo.civilizations
        val minors = maxOf(1, players.count { it.isCityState })
        val majors = maxOf(1, players.count { it.isMajorCiv })
        val threshold = 100 * constants.spyToPlayerRatio / (minors + constants.majorPlayerMultiplier * majors)
        return threshold.coerceIn(constants.thresholdMin, maxOf(constants.thresholdMin, constants.thresholdMax))
    }

    /**
     *  CvPlayer::CreateSpies for spy points (V:CvPlayer.cpp:23292-23338, only with [com.unciv.models.ModConstants.spyPointsLikeVp]): adds [points] (below 0: a debt) to the points
     *  and the total and makes a spy every time they reach [spyThreshold]. Nothing without espionage in the game. Returns the number of spies made.
     */
    fun gainSpyPoints(points: Int): Int {
        if (!civInfo.gameInfo.isEspionageEnabled()) return 0
        val threshold = maxOf(1, spyThreshold())
        spyPointsTotal += points
        spyPoints += points
        var created = 0
        while (spyPoints >= threshold) {
            val spy = addSpy()
            spy.addNotification("We have recruited [${spy.name}] as a spy!")
            spyPoints -= threshold
            created++
        }
        if (created == 0 && points > 0 && civInfo.isHuman())
            civInfo.addNotification("We received [$points] spy points ([$spyPoints] of [$threshold])", NotificationCategory.Espionage, NotificationIcon.Spy)
        return created
    }

    /** `Gain an extra spy` with spy points: CreateSpies(1, false), one threshold of points, which is one spy and leaves what was already collected (V:CvPlayer.cpp:23312-23314) */
    fun gainExtraSpyAsPoints() { gainSpyPoints(maxOf(1, spyThreshold())) }

    @Readonly
    fun getTilesVisibleViaSpies(): Sequence<Tile> {
        return spyList.asSequence()
            .filter { it.isSetUp() }
            .flatMap { spy ->
                val likeVp = civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp
                val city = spy.getCityOrNull() ?: return@flatMap emptySequence()
                // Vox Populi: a spy gathering intel sees only what its passive bonuses show (SpyExtraVision), every other set up spy one ring (V:CvEspionageClasses.cpp:3401-3430)
                val radius = if (likeVp && spy.action == SpyAction.GatheringIntel) spy.getBonuses().sumOf { b -> b.getMatchingUniques(UniqueType.SpyExtraVision).sumOf { it.params[0].toInt() } } else 1
                if (radius <= 0) emptySequence() else city.getCenterTile().getTilesInDistance(radius)
            }
    }

    /**
     *  The turns [spy] must wait for its next mission (CvPlayerEspionage::GetNumTurnsSpyActiveMissionsBlocked, V:CvEspionageClasses.cpp:2844-2856): the constant's turns times the game
     *  speed (CvGame::GetTurnsBetweenMinorCivElections, V:CvGame.cpp:11667-11674) less the turns since the mission; 0 for a spy that never did one
     */
    @Readonly
    fun getMissionCooldownLeft(spy: Spy): Int {
        if (spy.lastMissionTurn == 0) return 0
        val constants = civInfo.gameInfo.ruleset.modOptions.constants.espionage
        val turns = (constants.missionCooldownTurns * civInfo.gameInfo.speed.modifier).toInt()
        return maxOf(0, turns + spy.lastMissionTurn - civInfo.gameInfo.turns)
    }

    /**
     *  The turns a spy needs to reach [city] (CvPlayerEspionage::CalcRequired(SPY_STATE_TRAVELLING), V:CvEspionageClasses.cpp:3137-3182), only with
     *  [com.unciv.models.ModConstants.espionageLikeVp]: the base plus the turns of the proximity of the city's owner to us (none for an own city), halved, at least 1, when
     *  the city's owner gives us open borders
     */
    @Readonly
    fun getTravelTurns(city: City): Int {
        val constants = civInfo.gameInfo.ruleset.modOptions.constants.espionage
        var turns = constants.travelBaseTurns
        if (city.civ != civInfo) {
            val index = when (civInfo.getProximity(city.civ)) {
                Proximity.Neighbors -> 0
                Proximity.Close -> 1
                Proximity.Far -> 2
                Proximity.Distant -> 3
                Proximity.None -> -1
            }
            turns += constants.travelTurnsByProximity.getOrElse(index) { 0 }
            if (civInfo.getDiplomacyManager(city.civ)?.hasOpenBorders == true) turns = maxOf(turns / 2, 1)
        }
        return maxOf(turns, 1)
    }

    /**
     *  The Network Points [spy] gathers a turn in [city] (CvPlayerEspionage::CalcNetworkPointsPerTurn, V:CvEspionageClasses.cpp:3262-3379), only with
     *  [com.unciv.models.ModConstants.espionageLikeVp]; 0 unless the spy gathers intel or schmoozes. The base; the rank (ours is the original's plus 1); 15 for each level of cultural influence over the
     *  city's owner above Exotic; the technologies the owner is ahead by; the points of policies and of the state religion's beliefs ([UniqueType.EspionageNetworkPoints]); the spy effectiveness
     *  uniques of Brave New World's buildings and policies, which count as percent of what the spy gathers (`SpyEffectiveness`, `EnemySpyEffectiveness`; Vox Populi has no such column); at least 0;
     *  and for a spy gathering intel, less the security of the city ([CityEspionageManager.getSecurity]) as percent. A spy gathering intel also loses the points the focus of the city's counterspy
     *  takes for each of its ranks ([com.unciv.models.ruleset.SpyMission.counterspyNetworkPointsReduction]); that comes before the points are kept at 0 and before the security, so the focus
     *  takes the points the spy would have gathered, and the security then takes its percent of what is left.
     */
    @Readonly
    fun networkPointsPerTurn(spy: Spy, city: City): Int {
        if (spy.action != SpyAction.GatheringIntel && spy.action != SpyAction.Schmoozing) return 0
        return networkPointsPerTurn(spy, city, spy.action == SpyAction.GatheringIntel)
    }

    /**
     *  The same for [spy] that gathers intel ([gathering]) or schmoozes, and for no spy at all (CalcNetworkPointsPerTurn with `iSpyIndex` -1, which the AI asks when it chooses a city to send a spy to):
     *  without a spy there is no rank term and no loss to the counterspy of the city, "only calculate if we actually have a spy in the city, not if we calculate NP per turn for AI city selection"
     *  (V:CvEspionageClasses.cpp:3321-3322), and the security of the city still takes its percent
     */
    @Readonly
    fun networkPointsPerTurn(spy: Spy?, city: City, gathering: Boolean): Int {
        if (!civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp) return 0
        val constants = civInfo.gameInfo.ruleset.modOptions.constants.espionage
        var points = constants.networkPointsBase
        if (spy != null) points += (spy.getEffectiveRank() - 1) * constants.networkPointsPerSpyRank
        val level = civInfo.culture.getInfluenceLevel(city.civ)?.ordinal ?: 0
        points += maxOf(0, (level - 1) * constants.networkPointsPerInfluenceLevel)
        val techsAhead = city.civ.tech.researchedTechnologies.size - civInfo.tech.researchedTechnologies.size
        points += maxOf(0, minOf(techsAhead, constants.networkPointsMaxTechDifference)) * constants.networkPointsPerTechBehind
        points += civInfo.getMatchingUniques(UniqueType.EspionageNetworkPoints).sumOf { it.params[0].toInt() }
        // the counterspy of the city loses a spy that gathers intel its focus's points for each of its ranks, before the points are kept at 0 (V:CvEspionageClasses.cpp:3315-3333)
        if (gathering && spy != null) {
            val counter = city.espionage.counterspy()
            val reduction = city.espionage.getFocus()?.counterspyNetworkPointsReduction ?: 0
            if (counter != null && reduction != 0) points -= reduction * counter.getEffectiveRank()
        }
        val effectiveness = getSpyEffectivenessPercent(city)
        if (effectiveness != 100) points = points * effectiveness / 100
        points = maxOf(points, 0)
        if (gathering) {
            val security = city.espionage.getSecurity()
            if (points > 0 && security > 0) points = points * (100 - security * constants.networkPointsReductionPerSecurity / 100) / 100
        }
        return points
    }

    /**
     *  The percent points the chance of our spies to be identified falls by: the sum of the identify reduction of the focus of the counterspy in each of our own cities
     *  (CvPlayer::GetSpyIdentificationChance, V:CvPlayer.cpp:23342-23358; a focus like Double-Cross Foreign Agents is felt by every spy of ours in every foreign city)
     */
    @Readonly
    fun getIdentifyChanceReduction(): Int {
        if (!civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp || spyList.isEmpty()) return 0
        var total = 0
        for (spy in spyList) {
            if (spy.action != SpyAction.CounterIntelligence) continue
            val focusName = spy.focus ?: continue
            total += civInfo.gameInfo.ruleset.spyMissions[focusName]?.identifyChanceReductionGlobal ?: 0
        }
        return total
    }

    /** The percent that the city owner's [UniqueType.EnemySpyEffectiveness] and our [UniqueType.SpyEffectiveness] make of what a spy of ours does in [city] */
    @Readonly
    private fun getSpyEffectivenessPercent(city: City): Int {
        var percent = 100
        for (unique in city.getMatchingUniques(UniqueType.EnemySpyEffectiveness, city.state, includeCivUniques = true))
            if (city.matchesFilter(unique.params[1])) percent += unique.params[0].toInt()
        for (unique in civInfo.getMatchingUniques(UniqueType.SpyEffectiveness))
            if (city.matchesFilter(unique.params[1], civInfo)) percent += unique.params[0].toInt()
        return percent
    }

    /**
     *  The science the passive bonuses of our spies bring in a turn, times 100 (CvPlayer::GetSciencePerTurnFromPassiveSpyBonusesTimes100, V:CvPlayer.cpp:35264-35291): for each spy
     *  in a city the percent of the city's science its bonuses give ([UniqueType.SpyStealsSciencePercent]), summed, changed by [UniqueType.PassiveSpyBonusPercent]. Only with
     *  [com.unciv.models.ModConstants.espionageLikeVp]
     */
    @Readonly
    fun getPassiveScienceTimes100(): Int {
        if (!civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp) return 0
        var total = 0
        for (spy in spyList) {
            if (spy.isDiplomat) continue
            val city = spy.getCityOrNull() ?: continue
            val percent = spy.getBonuses().sumOf { b -> b.getMatchingUniques(UniqueType.SpyStealsSciencePercent).sumOf { it.params[0].toInt() } }
            total += (city.cityStats.currentCityStats.science * 100).toInt() * percent / 100
        }
        val modifier = civInfo.getMatchingUniques(UniqueType.PassiveSpyBonusPercent).sumOf { it.params[0].toInt() }
        return total * (100 + modifier) / 100
    }

    /** What the bonuses of our diplomat in [other]'s capital say, summed; 0 without an active diplomat */
    @Readonly
    private fun sumDiplomatBonus(other: Civilization, type: UniqueType): Int =
        getActiveDiplomatFor(other)?.getBonuses()?.sumOf { b -> b.getMatchingUniques(type).sumOf { it.params[0].toInt() } } ?: 0

    /** The percent of Congress votes our diplomat in [other]'s capital makes tradable with [other] (`SpyPassiveBonusesDiplomat.VoteTradePercent`, read by the World Congress) */
    @Readonly fun getDiplomatVoteTradePercent(other: Civilization): Int = sumDiplomatBonus(other, UniqueType.DiplomatVoteTradePercent)

    /** The percent gold more on trade routes between us and [other] our diplomat there makes (`TradeRouteGoldBonus`, V:CvTradeClasses.cpp:3483) */
    @Readonly fun getDiplomatTradeBonus(other: Civilization): Int = sumDiplomatBonus(other, UniqueType.DiplomatTradeRouteGoldPercent)

    /** Whether our diplomat in [other]'s capital sees through what [other] shows (`RevealTrueApproaches`) */
    @Readonly
    fun getDiplomatRevealsTrueApproaches(other: Civilization): Boolean =
        getActiveDiplomatFor(other)?.getBonuses()?.any { it.hasUnique(UniqueType.DiplomatRevealsTrueApproaches) } == true

    @Readonly
    fun getTechsToSteal(otherCiv: Civilization): Set<String> {
        val techsToSteal = mutableSetOf<String>()
        for (tech in otherCiv.tech.techsResearched) {
            if (civInfo.tech.isResearched(tech)) continue
            if (!civInfo.tech.canBeResearched(tech)) continue
            techsToSteal.add(tech)
        }
        return techsToSteal
    }

    /**
     * How fast a spy without rank steals in [city]: CvPlayerEspionage::CalcPerTurn(SPY_STATE_GATHERING_INTEL, city, -1).
     * The city's science x100 with the percentages of the city owner against spies ([UniqueType.EnemySpyEffectiveness]) and of this
     * civilization's spies ([UniqueType.SpyEffectiveness]) summed as in the original; at least 1. The game speed factor of the original
     * is left out: Unciv puts it into the cost of the theft instead.
     */
    @Readonly
    fun getBaseStealRateTimes100(city: City): Int {
        val percent = getSpyEffectivenessPercent(city)
        val science = (city.cityStats.currentCityStats.science * 100).toInt()
        return maxOf(science * percent / 100, 1)
    }

    @Readonly fun getSpiesInCity(city: City): List<Spy> = spyList.filter { it.getCityOrNull() == city }

    @Readonly fun getStartingSpyRank(): Int = 1 + civInfo.getMatchingUniques(UniqueType.SpyStartingLevel).sumOf { it.params[0].toInt() }

    /**
     * Returns a list of all cities with our spies in them.
     * The list needs to be stable across calls on the same turn.
     */
    @Readonly fun getCitiesWithOurSpies(): List<City> = spyList.filter { it.isSetUp() }.mapNotNull { it.getCityOrNull() }

    @Readonly fun getSpyAssignedToCity(city: City): Spy? = spyList.firstOrNull { it.getCityOrNull() == city }

    /** Only a schmoozing diplomat in the target's *current* capital is active (CvPlayerEspionage::IsMyDiplomatVisitingThem). */
    @Readonly
    fun getActiveDiplomatFor(other: Civilization): Spy? {
        if (!other.isMajorCiv || other == civInfo || other.isDefeated() || civInfo.isAtWarWith(other)) return null
        val capital = other.getCapital() ?: return null
        return getSpyAssignedToCity(capital)?.takeIf { it.isDiplomat && it.action == com.unciv.models.SpyAction.Schmoozing }
    }

    /**
     * Determines whether the NextTurnAction MoveSpies should be shown or not
     * @return true if there are spies waiting to be moved
     */
    @Readonly fun shouldShowMoveSpies(): Boolean = !dismissedShouldMoveSpies && spyList.any { it.isIdle() }
            && civInfo.gameInfo.getCities().any { civInfo.hasExplored(it.getCenterTile()) && getSpyAssignedToCity(it) == null }

    @Readonly fun getIdleSpies(): List<Spy> = spyList.filter { it.isIdle() }

    /**
     * Takes all spies away from their cities.
     * Called when the civ is destroyed.
     */
    fun removeAllSpies() {
        spyList.forEach { it.moveTo(null) }
    }

    /**
     * Recalls all spies stationed in any of [otherCiv]'s cities to the hideout.
     *
     * Called when [otherCiv] is destroyed via console [`civ remove`][ConsoleCivCommands].
     * Not using [CityEspionageManager.removeAllPresentSpies], so no notifications.
     */
    internal fun recallAllSpiesFrom(otherCiv: Civilization) {
        val toRecall = spyList.filter { it.getCityOrNull() in otherCiv.cities }
        for (spy in toRecall)
            spy.moveTo(null)
    }
}
