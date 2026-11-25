package com.unciv.logic.league

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.OverviewAction
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.Counter
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.tech.Era
import com.unciv.models.ruleset.tech.WorldCongressSessionInfo
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The World Congress of Brave New World, called League in the original (CvLeague/CvGameLeagues, CvVotingClasses.cpp).
 *
 * There is at most one, in [GameInfo.league], created by [tryFound] once the rules of [League.tryFound] are met.
 * It is ticked once per game turn by [doTurn], before the civilizations play their turn.
 *
 * Differences to the original that are intended:
 * - Membership is derived: every living civilization that is no barbarian or spectator is a member ([isMember]);
 *   [members] only holds the state of the major civilizations, which are the only ones that propose and vote.
 *   Effects of resolutions are derived from [activeResolutions] too, nothing is applied to or removed from a member.
 * - Sessions are the era uniques [UniqueType.WorldCongressSession]; see [getSessionInfo].
 * - Ties for a civilization, if the host is not among the tied, are decided for the one that is first in [GameInfo.civilizations]
 *   (the original picks an arbitrary one of them, CvVotingClasses.cpp:626-702)
 * - A proposer who could not propose anything at all loses the right to propose (see [removeUselessPrivileges]),
 *   so that a session cannot be blocked forever.
 */
class League : IsPartOfGameInfoSerialization {
    /** Once true always true: the World Congress is the United Nations and elects the World Leader */
    var unitedNations = false
    var inSession = false
    /** Era name of the special session that is held (CvLeague::m_eCurrentSpecialSession) */
    var currentSpecialSession: String? = null
    /** Era name whose session parameters govern while no special session is held (CvLeague::m_eLastSpecialSession) */
    var lastSpecialSession: String? = null
    /** Era name of the last special session that was triggered (CvGameLeagues::m_eLastEraTrigger), empty before the founding */
    var lastEraTrigger = ""
    /** Floors at 0 */
    var turnsUntilSession = Int.MAX_VALUE / 2
    /** Civ id of the host */
    var host = ""
    /** The id the next proposal gets (CvGameLeagues::m_iGeneratedIDCount, the first is 1) */
    var nextProposalId = 1
    /** Game turn at which a regular session was announced to be a few turns away (leagueSessionSoonWarningTurns), -1 for never */
    var sessionSoonTurn = -1
    /** Civ id to the state of this major civilization */
    var members = LinkedHashMap<String, LeagueMember>()
    /** All proposals on the table, the ones that are [LeagueProposal.onHold] too */
    var proposals = ArrayList<LeagueProposal>()
    var activeResolutions = ArrayList<ActiveResolution>()
    /** The outcomes of the last session that finished, in the order they were resolved, repeals first */
    var lastResults = ArrayList<LeagueResultEntry>()
    /** [GameInfo.turns] when [lastResults] were recorded, -1 for never */
    var lastResultsTurn = -1
    /** The League projects started by the World Congress, see `LeagueProjects.kt`; each is there once, running or complete */
    var projects = ArrayList<LeagueProject>()
    /** Only the host may propose until its proposals are used, then the others (`m_bHostProposing`, V:CvVotingClasses.cpp:2619, 2658, 4391, 8368). Only with `leagueSessionRulesLikeVp` */
    var hostProposing = false
    /** The proposals that were on the table when the last session started, which cannot be proposed (enacted) again except a project (`m_vLastTurnEnactProposals` and `...Repeal...`,
     *  V:CvVotingClasses.cpp:7278-7288, 2820-2850). Only with `leagueSessionRulesLikeVp` */
    var lastSessionProposals = ArrayList<LeagueProposalKey>()

    @Transient
    lateinit var gameInfo: GameInfo

    /** Uniques of the resolutions in effect, see [getActiveUniques] */
    @Transient
    private var uniqueMap = UniqueMap()
    /** Eras by [Era.eraNumber] */
    @Transient
    private var sortedEras: List<Era> = emptyList()

    fun clone(): League {
        val toReturn = League()
        toReturn.unitedNations = unitedNations
        toReturn.inSession = inSession
        toReturn.currentSpecialSession = currentSpecialSession
        toReturn.lastSpecialSession = lastSpecialSession
        toReturn.lastEraTrigger = lastEraTrigger
        toReturn.turnsUntilSession = turnsUntilSession
        toReturn.host = host
        toReturn.nextProposalId = nextProposalId
        toReturn.sessionSoonTurn = sessionSoonTurn
        for ((id, member) in members) toReturn.members[id] = member.clone()
        toReturn.proposals.addAll(proposals.map { it.clone() })
        toReturn.activeResolutions.addAll(activeResolutions.map { it.clone() })
        toReturn.lastResults.addAll(lastResults.map { it.clone() })
        toReturn.lastResultsTurn = lastResultsTurn
        toReturn.projects.addAll(projects.map { it.clone() })
        toReturn.hostProposing = hostProposing
        toReturn.lastSessionProposals.addAll(lastSessionProposals.map { it.clone() })
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        sortedEras = gameInfo.ruleset.eras.values.sortedBy { it.eraNumber }
        rebuildUniqueMap()
    }

    private fun rebuildUniqueMap() {
        val resolutions = gameInfo.ruleset.resolutions
        uniqueMap = UniqueMap(activeResolutions.asSequence()
            .mapNotNull { resolutions[it.resolution] }
            .flatMap { it.uniqueObjects })
    }

    //region Queries used by the effects (cheap, safe to call per city)

    @Readonly
    fun isActive(resolutionName: String): Boolean = activeResolutions.any { it.resolution == resolutionName }

    @Readonly
    fun getActiveResolutions(resolutionName: String): List<ActiveResolution> =
        activeResolutions.filter { it.resolution == resolutionName }

    /** The uniques of the resolutions in effect, they apply to every civilization (see `Civilization.getMatchingUniques`) */
    @Readonly
    fun getActiveUniques(): Sequence<Unique> = uniqueMap.getAllUniques()

    /** The same as [getActiveUniques], indexed for the unique gathering of a civilization */
    @Readonly
    fun getActiveUniqueMap(): UniqueMap = uniqueMap

    /** Whether [civ] belongs to the World Congress: every living civilization is a member, except barbarians and spectators (CvVotingClasses.cpp:6926-6942) */
    @Readonly
    fun isMember(civ: Civilization): Boolean = civ.isAlive() && !civ.isBarbarian && !civ.isSpectator

    /**
     * Whether no trade route between [a] and [b] can be established: symmetric, a route within a civilization is never embargoed.
     * A city-state embargo hits routes where either is a city-state, an embargo of a civilization those between two major civilizations
     * where either is the target (CvVotingClasses.cpp:3810-3841).
     */
    @Readonly
    fun isTradeEmbargoed(a: Civilization, b: Civilization): Boolean {
        if (a == b) return false
        val involvesCityState = a.isCityState || b.isCityState
        return activeResolutions.any { active ->
            val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: return@any false
            if (involvesCityState) resolution.embargoesCityStates
            else resolution.embargoesPlayer && (active.choice == a.civID || active.choice == b.civID)
        }
    }

    @Readonly
    fun isLuxuryBanned(resourceName: String): Boolean = activeResolutions.any { active ->
        active.choice == resourceName && gameInfo.ruleset.resolutions[active.resolution]?.bansLuxuryHappiness == true
    }

    /** Percent more tourism of [city] if it is the holy city of the religion that a World Religion in effect is about */
    @Readonly
    fun getHolyCityTourismPercent(city: City): Int = activeResolutions.sumOf { active ->
        val resolution = gameInfo.ruleset.resolutions[active.resolution]
        if (resolution != null && resolution.holyCityTourismPercent != 0 && city.isHolyCityOf(active.choice)) resolution.holyCityTourismPercent
        else 0
    }

    /** Percent more spread strength of [religionName] */
    @Readonly
    fun getReligionSpreadPercent(religionName: String): Int = activeResolutions.sumOf { active ->
        val resolution = gameInfo.ruleset.resolutions[active.resolution]
        if (resolution != null && active.choice == religionName) resolution.religionSpreadStrengthPercent else 0
    }

    /** Ideological pressure that [ideologyName] exerts on all civilizations because of a World Ideology in effect */
    @Readonly
    fun getIdeologyPressure(ideologyName: String): Int = activeResolutions.sumOf { active ->
        val resolution = gameInfo.ruleset.resolutions[active.resolution]
        if (resolution != null && active.choice == ideologyName) resolution.ideologyPressure else 0
    }

    //endregion
    //region Sessions: eras, parameters, countdown

    @Readonly
    private fun eraIndex(eraName: String?): Int = if (eraName == null) -1 else sortedEras.indexOfFirst { it.name == eraName }

    /** Indices into [sortedEras] of the eras that carry a session, ascending */
    @Readonly
    private fun sessionEraIndices(): List<Int> =
        sortedEras.indices.filter { sortedEras[it].getWorldCongressSession() != null }

    /**
     * The era that decides when special sessions are held (`LeagueHelpers::GetGameEraForTrigger`, CvVotingClasses.cpp:109-151) as an index into [sortedEras]:
     * the most advanced era of a living major civilization, one before it unless at least half of them are in it. -1 for none.
     */
    private fun getGameEraIndex(): Int {
        val eras = gameInfo.getAliveMajorCivs().map { eraIndex(it.getEra().name) }
        if (eras.isEmpty()) return -1
        val most = eras.max()
        val previous = if (most - 1 > -1) most - 1 else -1
        return if (eras.count { it == most } >= eras.size / 2) most else previous
    }

    /** The session era that governs when the game era is [gameEraIndex]: the last one at or before it, but at least the first with a session (CvVotingClasses.cpp:6858-6895) */
    private fun governingSessionEra(gameEraIndex: Int): Era? {
        val indices = sessionEraIndices()
        if (indices.isEmpty()) return null
        val clamped = gameEraIndex.coerceIn(indices.first(), indices.last())
        return sortedEras[indices.last { it <= clamped }]
    }

    /** The session parameters that apply now: those of the special session that is held, else those of the last one; fallback values of [com.unciv.models.ModConstants] if the era has none */
    fun getSessionInfo(): WorldCongressSessionInfo {
        val eraName = currentSpecialSession ?: lastSpecialSession
        val info = eraName?.let { gameInfo.ruleset.eras[it]?.getWorldCongressSession() }
        if (info != null) return info
        val constants = gameInfo.ruleset.modOptions.constants
        return WorldCongressSessionInfo(constants.leagueFallbackSessionInterval, constants.leagueFallbackDelegates, 0, 0)
    }

    /** Turns between regular sessions, scaled by the game speed with integer arithmetic (CvVotingClasses.cpp:1976-2008) */
    fun getSessionTurnInterval(): Int {
        val speedPercent = (gameInfo.speed.modifier * 100).roundToInt()
        return getSessionInfo().turnsBetween * speedPercent / 100
    }

    private fun resetTurnsUntilSession() {
        turnsUntilSession = getSessionTurnInterval()
    }

    /** Turns until the World Leader is voted for, 999 if it is not (CvLeague::GetTurnsUntilVictorySession, CvVotingClasses.cpp:2071-2140) */
    fun getTurnsUntilWorldLeaderSession(): Int {
        val worldLeader = gameInfo.ruleset.resolutions.values.firstOrNull { it.isWorldLeader } ?: return 999
        if (!worldLeader.isAvailableInGame(gameInfo) || hasWorldLeader()) return 999
        if (proposals.any { it.resolution == worldLeader.name && !it.onHold }) return turnsUntilSession
        val governing = (currentSpecialSession ?: lastSpecialSession)?.let { gameInfo.ruleset.eras[it] }
        if (governing?.isUnitedNations == true) return turnsUntilSession + getSessionTurnInterval()
        return 999
    }

    @Readonly
    private fun hasWorldLeader(): Boolean = gameInfo.civilizations.any { it.victoryManager.hasEverWonDiplomaticVote }

    //endregion
    //region Founding

    companion object {
        const val YES = "Yes"
        const val NO = "No"

        // The keys of [getDelegateSources], which the World Congress screen translates; in the order of CvLeague::CalculateStartingVotesForMember
        const val SOURCE_SESSION = "Delegates of the era"
        const val SOURCE_HOST = "Host of the World Congress"
        const val SOURCE_CITY_STATES = "Allied City-States"
        const val SOURCE_FAILED_VOTES = "Failed World Leader votes"
        const val SOURCE_DIPLOMATS = "Diplomats in capitals"
        const val SOURCE_BUILDINGS_AND_POLICIES = "Buildings, wonders and policies"
        const val SOURCE_IMPROVEMENTS = "Improvements of our religion"
        const val SOURCE_FAITH = "Cities following our religion"
        const val SOURCE_FRIENDSHIPS = "Declarations of Friendship"
        const val SOURCE_CAPITALS = "Conquered capitals"
        const val SOURCE_PACTS = "Defensive Pacts"
        const val SOURCE_GOLD = "Gold per turn"
        const val SOURCE_WORLD_RELIGION = "World Religion"
        const val SOURCE_WORLD_IDEOLOGY = "World Ideology"

        @Readonly
        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        @Readonly
        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        /**
         * The delegates each of [civ]'s diplomats in a capital gives: the sum of [UniqueType.WorldCongressDelegatesPerDiplomat] over its researched technologies
         * (CvPlayer::GetExtraVotesPerDiplomat, V:CvPlayer.cpp:29722, which CvTeam::processTech fills, V:CvTeam.cpp:8045). Brave New World's Globalization carries 1
         */
        @Readonly
        fun getDelegatesPerDiplomat(civ: Civilization): Int {
            var perDiplomat = 0
            civ.tech.techUniques.forEachMatchingUnique(UniqueType.WorldCongressDelegatesPerDiplomat, civ.state) { perDiplomat += it.params[0].toInt() }
            return perDiplomat
        }

        /**
         * What `GameInfo.nextTurn` does once per game turn: founds the World Congress if there is none yet, else lets it take its turn.
         * Rulesets without resolutions and games with World Congress disabled have none. The turn that founds it is no turn of the League (CvVotingClasses.cpp:6651-6700).
         * @return the World Congress to keep in [GameInfo.league]
         */
        fun doGameTurn(gameInfo: GameInfo): League? {
            if (gameInfo.gameParameters.noLeagues || gameInfo.ruleset.resolutions.isEmpty()) return null
            val existing = gameInfo.league ?: return tryFound(gameInfo)
            existing.doTurn()
            return existing
        }

        /**
         * Founds the World Congress if some living major civilization has a unique [UniqueType.AllowsWorldCongress],
         * has met every other living major civilization and more than one are alive. The first such civilization in [GameInfo.civilizations]
         * founds it and is the host. Returns `null` if the conditions are not met (CvGameLeagues::DoTurn, CvVotingClasses.cpp:6651-6700).
         */
        fun tryFound(gameInfo: GameInfo): League? {
            if (gameInfo.gameParameters.noLeagues || gameInfo.ruleset.resolutions.isEmpty()) return null
            val majors = gameInfo.getAliveMajorCivs()
            if (majors.size <= 1) return null
            val founder = majors.firstOrNull { civ ->
                civ.hasUnique(UniqueType.AllowsWorldCongress) && majors.all { it == civ || civ.knows(it) }
            } ?: return null
            val league = League()
            league.found(gameInfo, founder)
            return league
        }
    }

    /** `CvGameLeagues::FoundLeague` and `CvLeague::Init` (CvVotingClasses.cpp:6832-6924, 1864-1871): no vote is held, the founder is the host */
    private fun found(gameInfo: GameInfo, founder: Civilization) {
        setTransients(gameInfo)
        syncMembers()
        host = founder.civID
        val governing = governingSessionEra(getGameEraIndex())
        lastSpecialSession = governing?.name
        lastEraTrigger = governing?.name ?: ""
        if (governing?.isUnitedNations == true) unitedNations = true
        assignProposalPrivileges()
        resetTurnsUntilSession()
    }

    //endregion
    //region Per-turn tick

    /** Once per game turn (CvLeague::DoTurn, CvVotingClasses.cpp:1873-1901): sweeps what became invalid, then holds a special session, counts down to a session or finishes the running one */
    fun doTurn() {
        syncMembers()
        checkValid()
        ensureHost()
        removeUselessPrivileges()

        val special = getPendingSpecialSession()
        when {
            special != null && canStartSpecialSession() -> startSpecialSession(special)
            !inSession -> {
                turnsUntilSession = max(0, turnsUntilSession - 1)
                if (turnsUntilSession <= 0) checkStartSession()
                else if (turnsUntilSession == gameInfo.ruleset.modOptions.constants.leagueSessionSoonWarningTurns)
                    sessionSoonTurn = gameInfo.turns
            }
            else -> checkFinishSession()
        }
        checkProjects()
    }

    /** One row in [members] for each living major civilization, none for the others */
    private fun syncMembers() {
        val alive = gameInfo.getAliveMajorCivs()
        for (civ in alive) members.getOrPut(civ.civID) { LeagueMember() }
        members.keys.removeAll { id -> alive.none { it.civID == id } }
    }

    /** The session era that was reached and not yet held, the last one if several (CvVotingClasses.cpp:6689-6720) */
    private fun getPendingSpecialSession(): Era? {
        val gameEra = getGameEraIndex()
        val last = eraIndex(lastEraTrigger)
        if (gameEra <= last) return null
        return sessionEraIndices().lastOrNull { it > last && it <= gameEra }?.let { sortedEras[it] }
    }

    /** CvLeague::CanStartSpecialSession (CvVotingClasses.cpp:2169-2189) */
    private fun canStartSpecialSession(): Boolean =
        !inSession && proposals.none { it.onHold } && members.values.none { it.proposalsLeft > 0 || it.delegatesLeft > 0 }

    /** CvLeague::CheckStartSpecialSession and StartSpecialSession (CvVotingClasses.cpp:5254-5316) */
    private fun startSpecialSession(era: Era) {
        lastEraTrigger = era.name
        if (era.isUnitedNations) unitedNations = true
        val hostElection = gameInfo.ruleset.resolutions.values.firstOrNull { it.isHostElection }
        if (hostElection == null || !hostElection.isAvailableInGame(gameInfo)) {
            // No host to elect, so the session has nothing to decide: its parameters apply at once
            lastSpecialSession = era.name
            return
        }
        inSession = true
        currentSpecialSession = era.name
        for (proposal in proposals) proposal.onHold = true
        addAutomaticProposal(hostElection)
        assignStartingVotes()
        honorVoteCommitments()
        checkFinishSession()
    }

    /** CvLeague::CheckStartSession (CvVotingClasses.cpp:5224-5242): only if every proposer has proposed */
    private fun checkStartSession() {
        if (members.values.any { it.proposalsLeft > 0 }) return
        inSession = true
        val likeVp = gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp
        if (likeVp) {
            // StartSession: the proposals on the table are what the next session cannot propose again (V:CvVotingClasses.cpp:7278-7288); a special session leaves the table alone
            lastSessionProposals.clear()
            for (proposal in proposals) lastSessionProposals.add(LeagueProposalKey(proposal.resolution, proposal.proposerChoice, proposal.repeal))
        }
        assignStartingVotes()
        if (likeVp) fireSessionYields()
        honorVoteCommitments()
        checkFinishSession()
    }

    /** `INSTANT_YIELD_TYPE_DELEGATES` for every living major with delegates, in the capital, with the delegates as the amount (CvLeague::StartSession, V:CvVotingClasses.cpp:7292-7303) */
    private fun fireSessionYields() {
        for (civ in gameInfo.getAliveMajorCivs()) {
            val delegates = members[civ.civID]?.delegatesAtStart ?: continue
            if (delegates > 0)
                InstantYields.fire(UniqueType.TriggerUponWorldCongressSession, civ, civ.getCapital(), delegates, triggerNotificationText = "due to a session of the World Congress")
        }
    }

    /** CvLeague::AssignStartingVotes (CvVotingClasses.cpp:5514-5524): a snapshot, later changes do not alter this session */
    private fun assignStartingVotes() {
        for ((id, member) in members) {
            val civ = gameInfo.getCivilizationOrNull(id)
            member.delegatesLeft = if (civ == null) 0 else getDelegates(civ)
            member.delegatesAtStart = member.delegatesLeft
            member.abstained = 0
        }
    }

    /** CvGameLeagues::DoPlayerTurn: in a session every member first casts the votes it committed in deals (CvLeagueAI::DoVoteCommitments) */
    private fun honorVoteCommitments() {
        for (id in members.keys.toList()) {
            val civ = gameInfo.getCivilizationOrNull(id) ?: continue
            LeagueAutomation.doVoteCommitments(civ, this)
        }
    }

    /**
     * CvLeague::CheckFinishSession (CvVotingClasses.cpp:5318-5336): the session ends when nobody has delegates left.
     * If nothing is on the table everybody abstains (CvVotingClasses.cpp:6815-6826).
     */
    private fun checkFinishSession() {
        if (!inSession) return
        if (proposals.none { !it.onHold })
            for (member in members.values) {
                member.abstained += member.delegatesLeft
                member.delegatesLeft = 0
            }
        if (members.values.none { it.delegatesLeft > 0 }) finishSession()
    }

    //endregion
    //region Validity, host

    /** CvLeague::IsResolutionEffectsValid (CvVotingClasses.cpp:2592-2720) */
    private fun isResolutionEffectsValid(resolution: Resolution, choice: String?): Boolean {
        if (!resolution.isAvailableInGame(gameInfo)) return false
        if (resolution.embargoesCityStates && gameInfo.civilizations.none { it.isCityState }) return false
        if (resolution.embargoesPlayer) {
            val target = choice?.let { gameInfo.getCivilizationOrNull(it) }
            if (target == null || !target.isAlive() || target.cities.isEmpty()) return false
        }
        // a city-state to be chosen: the one chosen is a city-state, alive and has a city (V:CvVotingClasses.cpp:3383-3399, which asks it of Open Door and Sphere of Influence, the two
        // resolutions that choose one; any resolution that does is asked here, as the engine has no such effects to ask by). The original first asks that the game has ever had a
        // city-state (V:3372); that is no test here, as a choice that is a city-state says so already
        if (resolution.proposerDecision == ResolutionDecision.CityState) {
            val target = choice?.let { gameInfo.getCivilizationOrNull(it) }
            if (target == null || !target.isCityState || !target.isAlive() || target.cities.isEmpty()) return false
        }
        if (gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp
            && resolution.getMatchingUniques(UniqueType.ResolutionNeedsInEffect).any { !isActive(it.params[0]) }) return false
        return true
    }

    /**
     * What [resolution] with [choice] conflicts with, in effect or on the table as an enactment: its own [UniqueType.ResolutionConflictsWith] and
     * [UniqueType.ResolutionConflictsWithSameChoice], and those of the others that name it (V:CvVotingClasses.cpp:2909-3150 checks both directions by effect). Only with `leagueSessionRulesLikeVp`
     */
    private fun conflictsWithOthers(resolution: Resolution, choice: String?): Boolean {
        if (!gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp) return false
        val others = ArrayList<Pair<String, String?>>() // name, choice
        for (active in activeResolutions) others += active.resolution to active.choice
        for (proposal in proposals) if (!proposal.repeal) others += proposal.resolution to proposal.proposerChoice
        val resolutions = gameInfo.ruleset.resolutions
        return others.any { (otherName, otherChoice) ->
            val other = resolutions[otherName] ?: return@any false
            resolution.getMatchingUniques(UniqueType.ResolutionConflictsWith).any { it.params[0] == otherName }
                || other.getMatchingUniques(UniqueType.ResolutionConflictsWith).any { it.params[0] == resolution.name }
                || (choice == otherChoice && (
                    resolution.getMatchingUniques(UniqueType.ResolutionConflictsWithSameChoice).any { it.params[0] == otherName }
                    || other.getMatchingUniques(UniqueType.ResolutionConflictsWithSameChoice).any { it.params[0] == resolution.name }))
        }
    }

    /** CvLeague::CheckProposalsValid and CheckResolutionsValid (CvVotingClasses.cpp:5584-5625) */
    private fun checkValid() {
        val resolutions = gameInfo.ruleset.resolutions
        var changed = false
        for (active in activeResolutions.toList()) {
            val resolution = resolutions[active.resolution]
            if (resolution != null && isResolutionEffectsValid(resolution, active.choice)) continue
            activeResolutions.remove(active)
            changed = true
            LeagueEffects.onRepeal(this, active)
        }
        proposals.removeAll { proposal ->
            val resolution = resolutions[proposal.resolution]
            when {
                resolution == null -> true
                proposal.repeal -> activeResolutions.none { it.id == proposal.targetId }
                else -> !isResolutionEffectsValid(resolution, proposal.proposerChoice)
            }
        }
        if (changed) rebuildUniqueMap()
    }

    /** If the host died the member with the most delegates is the new one, the first in civ order on a tie (CvLeague::AssignNewHost, CvVotingClasses.cpp:5627-5643) */
    private fun ensureHost() {
        val current = gameInfo.getCivilizationOrNull(host)
        if (current != null && current.isMajorCiv && current.isAlive()) return
        var best: Civilization? = null
        var bestDelegates = -1
        for (civ in gameInfo.getAliveMajorCivs()) {
            val delegates = getDelegates(civ)
            if (delegates > bestDelegates) {
                best = civ
                bestDelegates = delegates
            }
        }
        host = best?.civID ?: ""
    }

    /** A proposer with nothing that can be proposed would block the session for good */
    private fun removeUselessPrivileges() {
        for ((id, member) in members) {
            if (member.proposalsLeft <= 0) continue
            val civ = gameInfo.getCivilizationOrNull(id) ?: continue
            if (!hasAnythingToPropose(civ)) {
                member.proposalsLeft = 0
                // Vox Populi's host is done only by proposing (DoProposeEnact, V:CvVotingClasses.cpp:2619); here it can also be done for having nothing to propose, and then the others must not wait for it
                if (id == host) hostProposing = false
            }
        }
    }

    //endregion
    //region Delegates

    /** Whether [civ] is a major civilization that takes part in the votes of the World Congress */
    @Readonly
    private fun canEverVote(civ: Civilization) = civ.isMajorCiv && civ.isAlive()

    /** CvLeague::GetCoreVotesForMember (CvVotingClasses.cpp:3194-3221): the delegates every civilization gets in the governing session */
    fun getCoreVotes(civ: Civilization): Int = if (canEverVote(civ)) getSessionInfo().civDelegates else 0

    /**
     * The votes [buyer] can buy from [seller] in a deal (CvLeague::GetPotentialVotesForMember, V:CvVotingClasses.cpp:3943-4014): what [seller] has in a session, less what it already promised
     * to anybody on the enactments on the table (its loop runs over the enact proposals only, so a commitment on a repeal is not subtracted), times the percent that [buyer]'s diplomat
     * in [seller]'s capital makes tradable (`DiplomatVoteTradePercent`, V:CvEspionageClasses.cpp:6991-6994), at least 1. 0 without such a diplomat or when nothing is left to buy.
     * That is Vox Populi's `MOD_BALANCE_VP` branch; the other divides by 5 less the diplomat's rank, which the diplomats of this engine do not have
     */
    fun getPotentialVotes(buyer: Civilization, seller: Civilization): Int {
        if (!canEverVote(seller)) return 0
        val promised = members[seller.civID]?.voteCommitments?.sumOf { commitment ->
            if (proposals.any { it.id == commitment.proposalId && !it.repeal }) commitment.votes else 0
        } ?: 0
        val votes = getDelegates(seller) - promised
        if (votes <= 0 || buyer.espionageManager.getActiveDiplomatFor(seller) == null) return 0
        return maxOf(1, votes * buyer.espionageManager.getDiplomatVoteTradePercent(seller) / 100)
    }

    /**
     * The delegates [civ] has in a session with the parameters of [getSessionInfo] (CvLeague::CalculateStartingVotesForMember, CvVotingClasses.cpp:3223-3359):
     * delegates of the era, those of failed World Leader votes, the host's, those of allied city-states, [UniqueType.WorldCongressDelegates],
     * Globalization's delegate per active diplomat, and those of the World Religion and World Ideology in effect.
     */
    fun getDelegates(civ: Civilization): Int {
        if (gameInfo.ruleset.modOptions.constants.leagueDelegatesLikeVp) return getDelegateSources(civ).values.sum()
        if (!canEverVote(civ)) return 0
        val session = getSessionInfo()
        var total = session.civDelegates + (members[civ.civID]?.extraDelegates ?: 0)
        if (civ.civID == host) total += session.hostDelegates
        total += session.cityStateDelegates * gameInfo.getAliveCityStates().count { it.allyCiv == civ }
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegates) { total += it.params[0].toInt() }
        total += getDelegatesPerDiplomat(civ) * gameInfo.getAliveMajorCivs().count { civ.espionageManager.getActiveDiplomatFor(it) != null }

        for (active in activeResolutions) {
            val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: continue
            if (resolution.votesForFollowingReligion != 0) {
                val religion = active.choice?.let { gameInfo.religions[it] }
                if (religion != null && civ.religionManager.isMajorityReligionForCiv(religion))
                    total += resolution.votesForFollowingReligion
            }
            if (resolution.votesForFollowingIdeology != 0 && active.choice != null && civ.getIdeology()?.name == active.choice)
                total += resolution.votesForFollowingIdeology
        }
        return total
    }

    /**
     * The delegates of [civ] by source, in the order of CvLeague::CalculateStartingVotesForMember (V:CvVotingClasses.cpp:4046-4383), only with
     * [com.unciv.models.ModConstants.leagueDelegatesLikeVp]; the sum is [getDelegates]. A source that gives nothing is not listed. The keys are what the World Congress screen shows.
     * Faith, friendships, original capitals and defensive pacts are those [refreshCachedSources] stored at the start of the civilization's last turn (CvPlayer::DoProcessVotes).
     */
    fun getDelegateSources(civ: Civilization): LinkedHashMap<String, Int> {
        val sources = LinkedHashMap<String, Int>()
        if (!canEverVote(civ)) return sources
        val session = getSessionInfo()
        val member = members[civ.civID]

        fun add(name: String, amount: Int) { if (amount != 0) sources[name] = amount }
        add(SOURCE_SESSION, session.civDelegates)
        if (civ.civID == host) add(SOURCE_HOST, session.hostDelegates)
        add(SOURCE_CITY_STATES, session.cityStateDelegates * gameInfo.getAliveCityStates().count { it.allyCiv == civ })
        add(SOURCE_FAILED_VOTES, member?.extraDelegates ?: 0)
        add(SOURCE_DIPLOMATS, getDelegatesPerDiplomat(civ) * gameInfo.getAliveMajorCivs().count { civ.espionageManager.getActiveDiplomatFor(it) != null })
        var flat = 0
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegates) { flat += it.params[0].toInt() }
        add(SOURCE_BUILDINGS_AND_POLICIES, max(flat, 0))
        add(SOURCE_IMPROVEMENTS, getDelegatesFromImprovements(civ))
        for ((name, amount) in member?.cachedSources.orEmpty()) add(name, amount)
        add(SOURCE_GOLD, getDelegatesFromGold(civ))
        add(SOURCE_WORLD_RELIGION, getDelegatesFromWorldReligion(civ))
        add(SOURCE_WORLD_IDEOLOGY, getDelegatesFromWorldIdeology(civ))
        return sources
    }

    /**
     * CvPlayer::DoProcessVotes (V:CvPlayer.cpp:24527-24599), at the start of [civ]'s turn: stores what faith, friendships, original capitals and defensive pacts give.
     * Only with [com.unciv.models.ModConstants.leagueDelegatesLikeVp]; otherwise it clears the member's cache. Faith is each building's `cities * 100 / N` in whole hundredths, summed,
     * and floored once; a civilization without a religion gets what its buildings give, as there is no state religion for Vox Populi's policy term (not built)
     */
    fun refreshCachedSources(civ: Civilization) {
        val member = members[civ.civID] ?: return
        member.cachedSources.clear()
        if (!gameInfo.ruleset.modOptions.constants.leagueDelegatesLikeVp || !canEverVote(civ)) return

        val followingCities = civ.religionManager.numberOfCitiesFollowingThisReligion()
        var faithHundredths = 0
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegatesPerReligionCities) { faithHundredths += followingCities * 100 / it.params[0].toInt() }
        var perFriendshipPair = 0
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegatesPerFriendships) { perFriendshipPair += it.params[0].toInt() }
        var perCapital = 0
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegatesPerCapitals) { perCapital += it.params[0].toInt() }
        var perPact = 0
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegatesPerDefensivePacts) { perPact += it.params[0].toInt() }

        val friendships = gameInfo.getAliveMajorCivs().count { it != civ && OpinionAi.isDoFAccepted(civ, it) }
        val pacts = gameInfo.getAliveMajorCivs().count { it != civ && civ.getDiplomacyManager(it)?.diplomaticStatus == DiplomaticStatus.DefensivePact }
        fun store(name: String, amount: Int) { if (amount > 0) member.cachedSources[name] = amount }
        store(SOURCE_FAITH, faithHundredths / 100)
        store(SOURCE_FRIENDSHIPS, friendships / 2 * perFriendshipPair)
        store(SOURCE_CAPITALS, VictoryProgressAi.capitalsConquered(civ) * perCapital)
        store(SOURCE_PACTS, pacts * perPact)
    }

    /**
     * CvPlayer::CalculateReligionVotesFromImprovements (V:CvPlayer.cpp:23818-23835): for each improvement the civilization owns, `1 / amount` of a delegate per improvement
     * for each [UniqueType.WorldCongressDelegatesPerImprovements] of its religion's beliefs, the fractions of all of them added exactly and truncated once. Read live
     */
    @Readonly
    private fun getDelegatesFromImprovements(civ: Civilization): Int {
        val religion = civ.religionManager.religion ?: return 0
        val perImprovement = HashMap<String, Int>() // improvement to the denominator of the sum of the fractions, see below
        val numerators = HashMap<String, Long>()
        for (belief in religion.getAllBeliefsOrdered()) {
            for (unique in belief.getMatchingUniques(UniqueType.WorldCongressDelegatesPerImprovements, GameContext.IgnoreConditionals)) {
                val improvement = unique.params[1]
                val amount = unique.params[0].toInt()
                val denominator = perImprovement[improvement] ?: 1
                val numerator = numerators[improvement] ?: 0L
                // numerator / denominator + 1 / amount, over the least common multiple
                val common = denominator / gcd(denominator, amount) * amount
                numerators[improvement] = numerator * (common / denominator) + common / amount
                perImprovement[improvement] = common
            }
        }
        if (numerators.isEmpty()) return 0
        val owned = HashMap<String, Int>()
        for (city in civ.cities) for (tile in city.getTiles()) {
            val improvement = tile.improvement ?: continue
            if (improvement in numerators && tile.getOwner() == civ) owned.merge(improvement, 1, Int::plus)
        }
        // The sum of numerator * owned / denominator over the improvements, as an exact fraction
        var denominator = 1L
        var numerator = 0L
        for ((improvement, count) in owned) {
            val d = perImprovement.getValue(improvement).toLong()
            val n = numerators.getValue(improvement) * count
            val common = denominator / gcd(denominator, d) * d
            numerator = numerator * (common / denominator) + n * (common / d)
            denominator = common
        }
        return (numerator / denominator).toInt()
    }

    /** CvVotingClasses.cpp:4230-4247: the gross gold per turn divided by the sum of [UniqueType.WorldCongressDelegatesPerGold], at most a quarter of the city-states originally in the game, but 1. Read live */
    @Readonly
    private fun getDelegatesFromGold(civ: Civilization): Int {
        var perVote = 0
        civ.forEachMatchingUnique(UniqueType.WorldCongressDelegatesPerGold) { perVote += it.params[0].toInt() }
        if (perVote <= 0) return 0
        val gross = getGrossGoldPerTurn(civ)
        if (gross <= 0) return 0
        return min(gross / perVote, max(gameInfo.civilizations.count { it.isCityState } / 4, 1))
    }

    /**
     * CvTreasury::CalculateGrossGold and CalculateGrossGoldTimes100 (V:CvTreasury.cpp:381-425): the income before costs, so the gold of the cities without the maintenance of their buildings, the trade and what
     * the uniques give, without the upkeep of units and roads. Vox Populi's list is longer (annexed city-states, espionage events) and some of it has no counterpart here
     */
    @Readonly
    private fun getGrossGoldPerTurn(civ: Civilization): Int {
        var gold = 0f
        for ((source, stats) in civ.stats.getStatMapForNextTurn()) {
            if (source == "Maintenance" || source == "Unit upkeep" || source == "Transportation upkeep") continue
            gold += stats.gold
        }
        return gold.toInt()
    }

    /** CvLeague::GetExtraVotesForFollowingReligion, with MOD_BALANCE_VP (V:CvVotingClasses.cpp:5319-5372): the first resolution that applies ends the sum */
    @Readonly
    private fun getDelegatesFromWorldReligion(civ: Civilization): Int {
        for (active in activeResolutions) {
            val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: continue
            if (resolution.votesForFollowingReligion == 0) continue
            val religion = active.choice?.let { gameInfo.religions[it] } ?: continue
            val followsIt = civ.religionManager.isMajorityReligionForCiv(religion)
            val holyCityOwner = gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion.name) }?.civ
            // "owns it" is GetOwnedReligion() == the religion: the state religion whose holy city the civilization holds, which is the only case that reaches the holy city branch below
            if (!followsIt && holyCityOwner != civ) continue
            if (holyCityOwner == civ) {
                val others = gameInfo.getAliveMajorCivs().count { it != civ && it.religionManager.isMajorityReligionForCiv(religion) }
                return resolution.votesForFollowingReligion + others
            }
            if (followsIt) return resolution.votesForFollowingReligion
        }
        return 0
    }

    /** CvLeague::GetExtraVotesForFollowingIdeology, with MOD_BALANCE_VP (V:CvVotingClasses.cpp:5413-5460): one more for every other civilization of that ideology, dead or alive */
    @Readonly
    private fun getDelegatesFromWorldIdeology(civ: Civilization): Int {
        var total = 0
        val own = civ.getIdeology()?.name ?: return 0
        for (active in activeResolutions) {
            val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: continue
            if (resolution.votesForFollowingIdeology == 0 || active.choice == null || own != active.choice) continue
            total += resolution.votesForFollowingIdeology + gameInfo.civilizations.count { it != civ && it.getIdeology()?.name == active.choice }
        }
        return total
    }

    /**
     * Delegates in a vote for the World Leader needed to win (CvGame::DoUpdateDiploVictory, SDK:CvGame.cpp:5053-5133 in the original, V:CvGame.cpp:5679-5761 in Vox Populi): `civs` is the number of major
     * civilizations, `cs` that of city-states, each counted by the `leagueWorldLeader...Weight` constants (1 if alive and 0.5 if defeated in Community Patch). With `leagueSessionRulesLikeVp` a city-state
     * whose original capital is gone is not counted at all. Clamped to what the host, all civilizations and all city-states have together in the United Nations.
     */
    fun getVotesNeededForWorldLeader(): Int {
        val constants = gameInfo.ruleset.modOptions.constants
        val unitedNations = sortedEras.firstOrNull { it.isUnitedNations }?.getWorldCongressSession()
        val perHost = unitedNations?.hostDelegates ?: 1
        val perCiv = unitedNations?.civDelegates ?: 1
        val perCityState = unitedNations?.cityStateDelegates ?: 1

        var civs = 0f
        var cityStates = 0f
        val capitalsFounded = if (constants.leagueSessionRulesLikeVp) gameInfo.getCities().filter { it.isOriginalCapital }.mapNotNull { it.foundingCivObject }.toSet() else emptySet()
        for (civ in gameInfo.civilizations) {
            if (civ.isMajorCiv) civs += if (civ.isAlive()) constants.leagueWorldLeaderCivAliveWeight else constants.leagueWorldLeaderCivDefeatedWeight
            else if (civ.isCityState) {
                // Only a city-state whose original capital still stands as a city counts (V:CvGame.cpp:5718-5731); a position fixed when the city-state founded it, so a city of any owner there counts
                if (constants.leagueSessionRulesLikeVp && civ !in capitalsFounded) continue
                cityStates += if (civ.isAlive()) constants.leagueWorldLeaderCityStateAliveWeight else constants.leagueWorldLeaderCityStateDefeatedWeight
            }
        }
        val civPortion = max(0f, constants.leagueWorldLeaderCivCoefficient * ln(civs) + constants.leagueWorldLeaderCivConstant)
        val cityStatePortion = max(0f, constants.leagueWorldLeaderCityStateCoefficient * ln(cityStates) + constants.leagueWorldLeaderCityStateConstant)
        var votes = floor(civPortion + cityStatePortion).toInt()
        votes = max(perHost + perCiv + 1, votes)
        votes = min(perHost + perCiv * civs.toInt() + perCityState * cityStates.toInt(), votes)
        return votes
    }

    //endregion
    //region Proposals

    /** Gives the proposal right to the host and the largest other delegations, clearing the rights of everybody else (CvLeague::AssignProposalPrivileges, CvVotingClasses.cpp:5535-5582) */
    private fun assignProposalPrivileges() {
        clearProposalPrivileges()
        val constants = gameInfo.ruleset.modOptions.constants
        val voters = gameInfo.getAliveMajorCivs().filter { it.civID in members }
        val slots = min(constants.leagueProposersPerSession, voters.size)
        var assigned = 0
        val hostCiv = voters.firstOrNull { it.civID == host }
        if (hostCiv != null && slots > 0) {
            members.getValue(hostCiv.civID).proposalsLeft = constants.leagueRegularHostProposals
            hostProposing = constants.leagueSessionRulesLikeVp // SetHostProposing(true), V:CvVotingClasses.cpp:8368
            assigned++
        }
        for (civ in voters.filter { it != hostCiv }.sortedByDescending { getDelegates(it) }) {
            if (assigned >= slots) break
            members.getValue(civ.civID).proposalsLeft = constants.leagueProposalsPerProposer
            assigned++
        }
    }

    private fun clearProposalPrivileges() {
        for (member in members.values) member.proposalsLeft = 0
        hostProposing = false // CvLeague::FinishSession clears both, V:CvVotingClasses.cpp:8284-8285
    }

    /**
     * Whether [civ] may put a proposal on the table now: it has proposals left and, with `leagueSessionRulesLikeVp`, it is the host while the host proposes
     * and one of the others once it is over (CvLeague::CanPropose, V:CvVotingClasses.cpp:4385-4399)
     */
    @Readonly
    fun canPropose(civ: Civilization): Boolean = hasProposal(civ, needsTurn = true)

    /** Whether [civ] has a proposal left and, if [needsTurn] and with `leagueSessionRulesLikeVp`, it is its turn to propose: [canPropose] is `hasProposal(civ, true)` */
    @Readonly
    private fun hasProposal(civ: Civilization, needsTurn: Boolean): Boolean {
        if (!civ.isMajorCiv || !civ.isAlive() || (members[civ.civID]?.proposalsLeft ?: 0) <= 0) return false
        return !needsTurn || !gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp || (civ.civID == host) == hostProposing
    }

    /** Whether [civ] has anything it could propose, whoever's turn it is: the question [removeUselessPrivileges] asks */
    private fun hasAnythingToPropose(civ: Civilization): Boolean =
        gameInfo.ruleset.resolutions.values.any { resolution ->
            val choices: List<String?> = if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else getChoices(resolution, civ)
            choices.any { canProposeEnact(resolution, it, civ, needsTurn = false) }
        } || activeResolutions.any { canProposeRepeal(it.id, civ, needsTurn = false) }

    @Readonly
    fun canVote(civ: Civilization): Boolean = civ.isMajorCiv && civ.isAlive() && (members[civ.civID]?.delegatesLeft ?: 0) > 0

    /** The choices a proposer can make for [resolution], civ ids or names of luxuries, religions or ideologies, see [getChoices] for a decision */
    fun getChoices(resolution: Resolution, civ: Civilization): List<String> = getChoices(resolution.proposerDecision, civ)

    /**
     * The values a member can choose for [decision] (CvLeague::GetChoicesForDecision, CvVotingClasses.cpp:2853-2931):
     * [YES] and [NO]; the living major civilizations, without [civ] for [ResolutionDecision.OtherMajorCiv];
     * luxuries that exist on the map; the religions founded by living major civilizations; the ideologies.
     */
    fun getChoices(decision: ResolutionDecision, civ: Civilization?): List<String> = when (decision) {
        ResolutionDecision.None -> emptyList()
        ResolutionDecision.YesNo -> listOf(YES, NO)
        ResolutionDecision.MajorCiv -> gameInfo.getAliveMajorCivs().map { it.civID }
        ResolutionDecision.OtherMajorCiv -> gameInfo.getAliveMajorCivs().filter { it != civ }.map { it.civID }
        ResolutionDecision.Luxury -> gameInfo.ruleset.tileResources.values
            .filter { it.resourceType == ResourceType.Luxury && it.name in gameInfo.tileMap.resources }
            .map { it.name }
        ResolutionDecision.Religion -> gameInfo.religions.values
            .filter { it.isMajorReligion() && it.foundingCiv.isMajorCiv && it.foundingCiv.isAlive() }
            .map { it.name }
        ResolutionDecision.Ideology -> gameInfo.ruleset.policyBranches.values.filter { it.isIdeology }.map { it.name }
        // the original lists the members that are city-states (V:CvVotingClasses.cpp:3666-3676); here the city-states are not members, so all of them, and the validity of a choice
        // (isResolutionEffectsValid) leaves out the dead ones and those with no city, as the original does
        ResolutionDecision.CityState -> gameInfo.civilizations.filter { it.isCityState }.map { it.civID }
    }

    /** The resolutions with at least one choice that [civ] can propose to enact now */
    fun getProposableResolutions(civ: Civilization): List<Resolution> = gameInfo.ruleset.resolutions.values.filter { resolution ->
        val choices: List<String?> =
            if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else getChoices(resolution, civ)
        choices.any { canProposeEnact(resolution, it, civ) }
    }

    /**
     * CvLeague::CanProposeEnact (CvVotingClasses.cpp:2406-2548). [proposer] `null` is the World Congress itself.
     * All of: a player proposes only what players may propose, is a member that may propose; [choice] is one of [getChoices] (`null` if there is none);
     * the resolution is valid ([isResolutionEffectsValid]); is not on the table and not in effect already (for [Resolution.exclusive] ones whatever the choice);
     * the technology it requires is known by any living civilization; the World Leader is not elected twice.
     */
    fun canProposeEnact(resolution: Resolution, choice: String?, proposer: Civilization?): Boolean =
        canProposeEnact(resolution, choice, proposer, needsTurn = true)

    /** [needsTurn] false: whether [proposer] could propose it if it were its turn to propose, which is what [removeUselessPrivileges] asks before the host has proposed */
    private fun canProposeEnact(resolution: Resolution, choice: String?, proposer: Civilization?, needsTurn: Boolean): Boolean {
        if (gameInfo.ruleset.resolutions[resolution.name] == null) return false
        if (proposer != null && (!resolution.playerProposable || !hasProposal(proposer, needsTurn))) return false
        // Not what was on the table last session, enactment or repeal, whatever the choice was made by whom; a project can always be tried again (V:CvVotingClasses.cpp:2820-2850)
        if (gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp && !resolution.isProject
            && lastSessionProposals.any { it.resolution == resolution.name && it.choice == choice }) return false
        if (resolution.proposerDecision == ResolutionDecision.None) {
            if (choice != null) return false
        } else if (choice == null || choice !in getChoices(resolution.proposerDecision, proposer)) return false
        if (!isResolutionEffectsValid(resolution, choice)) return false
        if (conflictsWithOthers(resolution, choice)) return false
        // The proposer must be the ally of what it chose, for a proposal of a civilization (V:CvVotingClasses.cpp:3054-3068)
        if (proposer != null && gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp
            && resolution.hasUnique(UniqueType.ResolutionProposerIsAllyOfChoice)
            && gameInfo.getCivilizationOrNull(choice ?: "")?.allyCiv != proposer) return false
        if (resolution.isWorldLeader && hasWorldLeader()) return false
        if (proposals.any { !it.repeal && it.resolution == resolution.name && (resolution.exclusive || it.proposerChoice == choice) }) return false
        if (activeResolutions.any { it.resolution == resolution.name && (resolution.exclusive || it.choice == choice) }) return false
        // A project runs once per game: not again while running or after it is complete (CvVotingClasses.cpp:2532-2545)
        if (resolution.isProject && getProject(resolution.name) != null) return false
        val tech = resolution.requiredTechAnyMember
        if (tech != null && gameInfo.civilizations.none { it.isAlive() && it.tech.isResearched(tech) }) return false
        return true
    }

    /** CvLeague::CanProposeRepeal (CvVotingClasses.cpp:2550-2590): [proposer] may propose, the resolution is in effect and no repeal of it is proposed */
    fun canProposeRepeal(activeResolutionId: Int, proposer: Civilization): Boolean = canProposeRepeal(activeResolutionId, proposer, needsTurn = true)

    /** As [canProposeEnact]'s: with [needsTurn] false it ignores whether the host is still proposing. A repeal is not refused for having been on the table last session (V:CvVotingClasses.cpp:3166-3223) */
    private fun canProposeRepeal(activeResolutionId: Int, proposer: Civilization, needsTurn: Boolean): Boolean =
        hasProposal(proposer, needsTurn)
            && activeResolutions.any { it.id == activeResolutionId && gameInfo.ruleset.resolutions[it.resolution]?.isProject != true } // a project is no resolution in effect
            && proposals.none { it.repeal && it.targetId == activeResolutionId }

    /** Puts [resolution] with [choice] on the table, costing [proposer] his proposal (CvLeague::DoProposeEnact, CvVotingClasses.cpp:2263-2304) */
    fun proposeEnact(resolution: Resolution, choice: String?, proposer: Civilization?) {
        require(canProposeEnact(resolution, choice, proposer)) { "[${resolution.name}] cannot be proposed with choice [$choice]" }
        val proposal = LeagueProposal(nextProposalId++, resolution.name, false, proposer?.civID, choice)
        proposals.add(proposal)
        if (proposer != null) LeagueAutomation.recordProposal(this, proposal)
        if (proposer != null) spendProposal(proposer)
    }

    /** One proposal of [proposer] is used; the host's last one hands the turn to propose to the others (V:CvVotingClasses.cpp:2619 and 2658) */
    private fun spendProposal(proposer: Civilization) {
        val member = members.getValue(proposer.civID)
        member.proposalsLeft--
        if (proposer.civID == host && member.proposalsLeft <= 0) hostProposing = false
    }

    /** Puts the repeal of an [ActiveResolution] on the table, costing [proposer] his proposal (CvLeague::DoProposeRepeal, CvVotingClasses.cpp:2306-2350) */
    fun proposeRepeal(activeResolutionId: Int, proposer: Civilization) {
        require(canProposeRepeal(activeResolutionId, proposer)) { "Resolution [$activeResolutionId] cannot be repealed" }
        val active = activeResolutions.first { it.id == activeResolutionId }
        val proposal = LeagueProposal(nextProposalId++, active.resolution, true, proposer.civID, active.choice, activeResolutionId)
        proposals.add(proposal)
        LeagueAutomation.recordProposal(this, proposal)
        spendProposal(proposer)
    }

    /** A proposal of the World Congress itself, which costs nobody anything (host election, World Leader) */
    private fun addAutomaticProposal(resolution: Resolution) {
        proposals.add(LeagueProposal(nextProposalId++, resolution.name, false, null, null))
    }

    //endregion
    //region Voting

    /** The delegates [civ] has still to spend in this session */
    @Readonly
    fun getDelegatesLeft(civ: Civilization): Int = members[civ.civID]?.delegatesLeft ?: 0

    /**
     * Spends [delegates] of [voter] on [choice] of a proposal on the table, any number can be split over proposals and choices (CvLeague::DoVoteEnact and DoVoteRepeal, CvVotingClasses.cpp:2204-2247).
     * [choice] is [YES] or [NO] for a vote that is about a resolution and the id of a living major civilization for one about a civilization.
     */
    fun vote(proposalId: Int, voter: Civilization, delegates: Int, choice: String) {
        val proposal = proposals.firstOrNull { it.id == proposalId && !it.onHold }
        require(inSession && proposal != null) { "Proposal [$proposalId] is not on the table of a session" }
        val member = members[voter.civID]
        require(member != null && canVote(voter)) { "[${voter.civID}] cannot vote" }
        require(delegates > 0 && delegates <= member.delegatesLeft) { "[${voter.civID}] has no [$delegates] delegates left" }
        val resolution = gameInfo.ruleset.resolutions.getValue(proposal.resolution)
        val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution.voterDecision
        require(choice in getChoices(decision, voter)) { "[$choice] is not a choice for [${proposal.resolution}]" }

        val existing = proposal.votes.firstOrNull { it.voter == voter.civID && it.choice == choice }
        if (existing != null) existing.delegates += delegates
        else proposal.votes.add(LeagueVote(voter.civID, delegates, choice))
        member.delegatesLeft -= delegates
    }

    /** Gives up the delegates [voter] has left (CvLeague::DoVoteAbstain, CvVotingClasses.cpp:2249-2262) */
    fun abstainRemaining(voter: Civilization) {
        val member = members[voter.civID] ?: return
        member.abstained += member.delegatesLeft
        member.delegatesLeft = 0
    }

    /** Gives up [delegates] of the delegates [voter] has left, no more than it has (Vox Populi's CvLeague::DoVoteAbstain with a number, V:CvVotingClasses.cpp:2588) */
    fun abstain(voter: Civilization, delegates: Int) {
        val member = members[voter.civID] ?: return
        if (!canVote(voter)) return
        val given = minOf(delegates, member.delegatesLeft)
        member.abstained += given
        member.delegatesLeft -= given
    }

    /** Delegates per choice of [proposal] (CvVoterDecision::ProcessVote) */
    fun getTally(proposal: LeagueProposal): Counter<String> {
        val tally = Counter<String>()
        for (vote in proposal.votes) tally.add(vote.choice, vote.delegates)
        return tally
    }

    /** Choices with the most delegates, if any were cast, all of them on a tie */
    private fun topChoices(tally: Counter<String>): List<String> {
        if (tally.isEmpty()) return emptyList()
        val top = tally.keys.maxOf { tally[it] }
        return tally.keys.filter { tally[it] == top }
    }

    /** The first [count] choices by delegates and those that tie with the last of them (CvVoterDecision::GetTopVotedChoices, CvVotingClasses.cpp:711-761) */
    private fun topVotedChoices(tally: Counter<String>, count: Int): List<String> {
        val sorted = tally.keys.sortedByDescending { tally[it] }
        if (sorted.size <= count || count <= 0) return if (count <= 0) emptyList() else sorted
        val threshold = tally[sorted[count - 1]]
        return sorted.filter { tally[it] >= threshold }
    }

    private fun civIndex(civID: String) = gameInfo.civilizations.indexOfFirst { it.civID == civID }

    /**
     * The choice that won the vote on [proposal], `null` for none (CvVoterDecision::GetDecision, CvVotingClasses.cpp:626-702): the most delegates win.
     * On a tie for a civilization the host wins if he is among the tied, else the first civilization in [GameInfo.civilizations];
     * on any other tie there is no decision.
     */
    fun getDecision(proposal: LeagueProposal): String? {
        val tally = getTally(proposal)
        val tied = topChoices(tally)
        if (tied.isEmpty()) return null
        if (tied.size == 1) return tied.first()
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution]
        if (proposal.repeal || resolution?.voterDecision != ResolutionDecision.MajorCiv) return null
        if (host in tied) return host
        return tied.minBy { civIndex(it) }
    }

    /** CvEnactProposal::IsPassed and CvRepealProposal::IsPassed (CvVotingClasses.cpp:1110-1155, 1691-1723) */
    private fun isPassed(proposal: LeagueProposal, resolution: Resolution, totalSessionVotes: Int): Boolean {
        if (totalSessionVotes == 0) return false // 0 of 0 votes is not a quorum
        if (proposal.repeal) return getDecision(proposal) == YES
        val tally = getTally(proposal)
        return when {
            resolution.isWorldLeader -> {
                val tied = topChoices(tally)
                tied.size == 1 && tally[tied.first()] >= getVotesNeededForWorldLeader()
            }
            resolution.isHostElection -> true
            resolution.voterDecision == ResolutionDecision.YesNo -> getDecision(proposal) == YES
            else -> false
        }
    }

    //endregion
    //region Resolving a session

    /**
     * CvLeague::FinishSession (CvVotingClasses.cpp:5338-5512): resolves the repeals, then the enactments, in the order they were proposed.
     * Failed World Leader votes give the leaders delegates, a passed one the Diplomatic Victory.
     * Then the proposals are cleared and, after a regular session, the next proposers chosen (or the World Leader proposed
     * if the World Congress is the United Nations and it was not on the table now) and the countdown reset.
     */
    private fun finishSession() {
        val resolutions = gameInfo.ruleset.resolutions
        val onTable = proposals.filter { !it.onHold }
        val totalSessionVotes = onTable.sumOf { proposal -> proposal.votes.sumOf { it.delegates } }
        val results = ArrayList<LeagueResultEntry>()
        var worldLeaderOnTable = false

        for (proposal in onTable.sortedBy { !it.repeal }) {
            val resolution = resolutions[proposal.resolution] ?: continue
            if (resolution.isWorldLeader) worldLeaderOnTable = true
            val passed = isPassed(proposal, resolution, totalSessionVotes)
            val decision = getDecision(proposal)
            LeagueAutomation.recordSessionOutcome(this, proposal, decision, passed)
            val tally = getTally(proposal)
            val about = if (!proposal.repeal && resolution.voterDecision == ResolutionDecision.MajorCiv) decision else proposal.proposerChoice
            results.add(LeagueResultEntry(resolution.name, about, passed, proposal.repeal, tally))
            if (passed) firePassedProposalYield(proposal)
            when {
                proposal.repeal -> if (passed) repeal(proposal)
                passed -> enact(proposal, resolution, decision)
                else -> giveLeadersBonus(resolution, tally)
            }
        }
        lastResults = results
        lastResultsTurn = gameInfo.turns
        if (results.isNotEmpty())
            for (civ in gameInfo.getAliveMajorCivs().filter { it.isHuman() })
                civ.popupAlerts.add(PopupAlert(AlertType.LeagueSessionResult, ""))

        val special = currentSpecialSession != null
        proposals.removeAll { !it.onHold }
        for (proposal in proposals) proposal.onHold = false
        clearProposalPrivileges()
        if (special) {
            lastSpecialSession = currentSpecialSession
            currentSpecialSession = null
        } else {
            val worldLeader = resolutions.values.firstOrNull { it.isWorldLeader }
            val governing = lastSpecialSession?.let { gameInfo.ruleset.eras[it] }
            if (governing?.isUnitedNations == true && worldLeader != null && !worldLeaderOnTable
                && worldLeader.isAvailableInGame(gameInfo) && !hasWorldLeader())
                addAutomaticProposal(worldLeader) // CvVotingClasses.cpp:5486-5500: instead of the proposals of the players
            else assignProposalPrivileges()
            resetTurnsUntilSession()
        }
        inSession = false
    }

    /**
     * `INSTANT_YIELD_TYPE_PROPOSAL` for the civilization that proposed [proposal], which has passed: in every city, before the resolution takes effect and not for the World Congress's own
     * proposals (CvLeague::FinishSession, V:CvVotingClasses.cpp:7624 and 8028). Only with `leagueSessionRulesLikeVp`
     */
    private fun firePassedProposalYield(proposal: LeagueProposal) {
        if (!gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp) return
        val proposer = proposal.proposer?.let { gameInfo.getCivilizationOrNull(it) } ?: return
        InstantYields.fire(UniqueType.TriggerUponPassingProposal, proposer, triggerNotificationText = "due to a proposal of ours passing")
    }

    /** CvLeague::DoEnactResolution (CvVotingClasses.cpp:5645-5676): only resolutions with ongoing effects are kept */
    private fun enact(proposal: LeagueProposal, resolution: Resolution, decision: String?) {
        when {
            resolution.isHostElection -> {
                val elected = decision?.let { gameInfo.getCivilizationOrNull(it) }
                if (elected != null && elected.isMajorCiv && elected.isAlive()) host = elected.civID
            }
            resolution.isWorldLeader -> {
                // The victory itself is found by the game as for the vote of Gods & Kings (GameInfo.processDiplomaticVictory)
                decision?.let { gameInfo.getCivilizationOrNull(it) }?.victoryManager?.hasEverWonDiplomaticVote = true
            }
            resolution.isProject -> startProject(resolution)
            else -> {
                val active = ActiveResolution(proposal.id, resolution.name, proposal.proposer, proposal.proposerChoice, gameInfo.turns)
                activeResolutions.add(active)
                rebuildUniqueMap()
                LeagueEffects.onEnact(this, active)
            }
        }
    }

    /** CvLeague::DoRepealResolution (CvVotingClasses.cpp:5678-5704) */
    private fun repeal(proposal: LeagueProposal) {
        val active = activeResolutions.firstOrNull { it.id == proposal.targetId } ?: return
        activeResolutions.remove(active)
        rebuildUniqueMap()
        LeagueEffects.onRepeal(this, active)
    }

    /** The civilizations leading a failed vote for a civilization get permanent delegates (CvVotingClasses.cpp:5420-5460) */
    private fun giveLeadersBonus(resolution: Resolution, tally: Counter<String>) {
        if (resolution.leadersVoteBonusOnFail <= 0 || resolution.voterDecision != ResolutionDecision.MajorCiv) return
        val count = gameInfo.ruleset.modOptions.constants.leagueLeadersVoteBonusCount
        for (id in topVotedChoices(tally, count))
            members[id]?.let { it.extraDelegates += resolution.leadersVoteBonusOnFail }
    }

    //endregion
    //region Notifications

    /** The notifications of the start of the turn of a human [civ]: it is wanted to propose, or a session is near */
    fun notifyStartTurn(civ: Civilization) {
        if (!civ.isMajorCiv || !civ.isAlive()) return
        if (canPropose(civ))
            civ.addNotification("Your proposal is wanted by the World Congress", OverviewAction(EmpireOverviewCategories.WorldCongress), NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
        if (sessionSoonTurn == gameInfo.turns && !inSession)
            civ.addNotification("World Congress session in [$turnsUntilSession] turns", OverviewAction(EmpireOverviewCategories.WorldCongress), NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
    }

    //endregion
}
