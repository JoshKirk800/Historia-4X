package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.CompetitorAi
import com.unciv.logic.automation.diplomacy.CoopWarAi
import com.unciv.logic.automation.diplomacy.CoopWarState
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.DoFLevel
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.OpinionTimers
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.VictoryPursuitAi
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.automation.diplomacy.WarSanityAi
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.CityStats.Companion.EXCESS_FOOD_TO_PRODUCTION
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.logic.league.LeagueProposal
import com.unciv.logic.league.VoteCommitment
import com.unciv.logic.league.getProjectCostPerPlayer
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * World Congress proposals, votes and vote commitments (CvLeagueAI, Vox Populi CvVotingClasses.cpp:10196-14450). No teams or vassals.
 */
object LeagueAutomation {
    /** CvLeagueAI::DoProposals and DoVotes stop after this many loops (CvVotingClasses.cpp:7449-7486) */
    const val MAX_LOOPS = 100
    // (VP's AllocateProposals takes the best proposal, not a weighted roll among the top ones of LeagueHelpers::AI_CHOOSE_PROPOSAL_FROM_TOP)
    /** The Congress memory [observer] keeps of [other] */
    private fun memoryOf(league: League, observer: String, other: String): LeagueAlignmentMemory? =
        league.members[observer]?.alignmentMemory?.getOrPut(other) { LeagueAlignmentMemory() }

    /** VP's IsSanctionAgainstUs: an embargo on [civId] (BNW's player embargo is VP's sanction) */
    private fun isSanctionAgainst(resolution: Resolution, choice: String?, civId: String) = resolution.embargoesPlayer && choice == civId

    /**
     * CvLeague::DoEnactProposalDiplomacy / DoRepealProposalDiplomacy (Vox Populi): every AI member but the proposer judges the
     * proposal as it enters the table. An embargo on it is the most disliked proposal (agreement value
     * OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_OVERWHELMING) and an attempted sanction; repealing one on it the most liked, and an
     * attempted unsanction unless the proposer ever sanctioned it. Otherwise the desire level of the proposal ([LeagueDesire.of] of our
     * score) above or below neutral makes it liked or disliked with the matching agreement value. The sanction's diplomacy towards
     * third parties (DoEnact/RepealSanctionsDiplomacy) and its assist values: none.
     */
    fun recordProposal(league: League, proposal: LeagueProposal) {
        val proposer = proposal.proposer ?: return
        val active = if (proposal.repeal) league.activeResolutions.firstOrNull { it.id == proposal.targetId } ?: return else null
        val resolution = league.gameInfo.ruleset.resolutions[active?.resolution ?: proposal.resolution] ?: return
        val rules = league.gameInfo.ruleset.ai
        val turn = league.gameInfo.turns
        for (observer in league.gameInfo.getAliveMajorCivs()) {
            if (observer.civID == proposer || observer.isHuman() || observer.civID !in league.members) continue
            val memory = memoryOf(league, observer.civID, proposer) ?: continue
            val sanction = isSanctionAgainst(resolution, active?.choice ?: proposal.proposerChoice, observer.civID)
            if (sanction && active == null) {
                memory.dislikedProposalTurn = turn
                memory.likedProposalTurn = -1
                memory.proposalAgreementValue = rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_OVERWHELMING)
                memory.sanctionedTurn = turn
                memory.unsanctionedTurn = -1
                continue
            }
            if (sanction) {
                memory.likedProposalTurn = turn
                memory.dislikedProposalTurn = -1
                memory.proposalAgreementValue = rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_OVERWHELMING)
                if (!memory.everSanctioned) {
                    memory.unsanctionedTurn = turn
                    memory.sanctionedTurn = -1
                }
                continue
            }
            val evaluator = LeagueEvaluator(observer, league)
            val desire = LeagueDesire.of(if (active != null) evaluator.scoreRepeal(active) else evaluator.scoreProposal(resolution, proposal.proposerChoice))
            val value = when (desire) {
                LeagueDesire.ALWAYS -> AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_OVERWHELMING
                LeagueDesire.STRONG_LIKE -> AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_STRONG
                LeagueDesire.LIKE -> AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL
                LeagueDesire.WEAK_LIKE -> AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_WEAK
                LeagueDesire.NEUTRAL -> continue
                LeagueDesire.WEAK_DISLIKE -> AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_WEAK
                LeagueDesire.DISLIKE -> AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL
                LeagueDesire.STRONG_DISLIKE -> AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_STRONG
                LeagueDesire.NEVER -> AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_OVERWHELMING
            }
            if (desire > LeagueDesire.NEUTRAL) {
                memory.likedProposalTurn = turn
                memory.dislikedProposalTurn = -1
            } else {
                memory.dislikedProposalTurn = turn
                memory.likedProposalTurn = -1
            }
            memory.proposalAgreementValue = rules.define(value)
        }
    }

    /**
     * CvVoterDecision::GetPercentContributionToOutcome (or, [against], GetPercentContributionAgainstOutcome): [voter]'s share of the
     * votes for (against) [outcome] and the share of its own delegates it gave to it; votes the other way are taken off (not for a
     * [host] election). 0 when none.
     */
    private fun contribution(league: League, proposal: LeagueProposal, voter: String, outcome: String, against: Boolean,
                             host: Boolean = false): Pair<Int, Int> {
        var total = 0
        var votes = 0
        for (vote in proposal.votes) {
            val forOutcome = vote.choice == outcome
            if (forOutcome != against) {
                total += vote.delegates
                if (vote.voter == voter) votes += vote.delegates
            } else if (vote.voter == voter && !host) votes -= vote.delegates
        }
        if (votes <= 0 || total <= 0) return 0 to 0
        val civ = league.gameInfo.getCivilizationOrNull(voter)
        val own = if (host || civ == null) 0 else votes * 100 / maxOf(league.getDelegates(civ), 1)
        return votes * 100 / total to own
    }

    /** VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_* by desire level, negative for the dislikes */
    private fun historyMultiplier(desire: LeagueDesire, rules: AiRuleset): Int = when (desire) {
        LeagueDesire.NEVER -> -rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_OVERWHELMING)
        LeagueDesire.STRONG_DISLIKE -> -rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_STRONG)
        LeagueDesire.DISLIKE -> -rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_STANDARD)
        LeagueDesire.WEAK_DISLIKE -> -rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_WEAK)
        LeagueDesire.NEUTRAL -> 0
        LeagueDesire.WEAK_LIKE -> rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_WEAK)
        LeagueDesire.LIKE -> rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_STANDARD)
        LeagueDesire.STRONG_LIKE -> rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_STRONG)
        LeagueDesire.ALWAYS -> rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_OVERWHELMING)
    }

    /**
     * CvLeague::FinishSession's diplomacy (Vox Populi): for each voter that helped the outcome (a tie counts as No) and each that voted
     * against it, every other AI member changes its voting history score of the voter by ((share of the voter's own delegates) x
     * VOTING_HISTORY_SCORE_PLAYER_VOTE_PERCENT_VALUE % + (share of the outcome's votes) x _OUTCOME_VOTE_PERCENT_VALUE %) x the
     * multiplier of its desire for the outcome (against: reversed; a proposer that dislikes its own outcome changes nothing); for
     * an embargo on that member, the multiplier is the overwhelming one and the voter's help or harm becomes an attempted sanction or
     * unsanction (ever: when it passed or a repeal failed). The proposer remembers who helped (support delta minus the share) or who
     * foiled (plus the share) its proposal; an elected host who voted for it with their share. VP stamps these one turn ahead, as its
     * opinions are next updated then; ours are updated in the session's turn, so the current turn is stamped.
     */
    fun recordSessionOutcome(league: League, proposal: LeagueProposal, decision: String?, passed: Boolean) {
        val resolution = league.gameInfo.ruleset.resolutions[proposal.resolution] ?: return
        val active = if (proposal.repeal) league.activeResolutions.firstOrNull { it.id == proposal.targetId } else null
        val sanctionTarget = if ((active?.let { league.gameInfo.ruleset.resolutions[it.resolution] } ?: resolution).embargoesPlayer)
            active?.choice ?: proposal.proposerChoice else null
        val outcome = decision ?: League.NO
        val rules = league.gameInfo.ruleset.ai
        val stamp = league.gameInfo.turns
        val proposer = proposal.proposer
        val host = !proposal.repeal && resolution.isHostElection
        // Whether the outcome sanctions the target (an embargo passed or its repeal failed) or lifts it
        val outcomeSanctions = proposal.repeal != passed
        val observers = league.gameInfo.getAliveMajorCivs().filter { !it.isHuman() && it.civID in league.members }
        val desires = HashMap<String, LeagueDesire>()
        fun desireOf(observer: Civilization) = desires.getOrPut(observer.civID) {
            LeagueDesire.of(LeagueEvaluator(observer, league).scoreOutcome(proposal, outcome))
        }
        for (voter in proposal.votes.map { it.voter }.distinct()) {
            for (against in listOf(false, true)) {
                val (share, own) = contribution(league, proposal, voter, outcome, against)
                if (share <= 0) continue
                for (observer in observers) {
                    if (observer.civID == voter) continue
                    val memory = memoryOf(league, observer.civID, voter) ?: continue
                    val multiplier = if (sanctionTarget == observer.civID) {
                        // Helping a sanction or harming its lifting is an attempted sanction, and the reverse an unsanction
                        if (outcomeSanctions != against) {
                            memory.sanctionedTurn = stamp
                            memory.unsanctionedTurn = -1
                            if (outcomeSanctions) memory.everSanctioned = true
                        } else {
                            memory.unsanctionedTurn = stamp
                            memory.sanctionedTurn = -1
                            if (!outcomeSanctions) memory.everUnsanctioned = true
                        }
                        rules.define(AiDefine.VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_OVERWHELMING) * (if (outcomeSanctions) -1 else 1)
                    } else historyMultiplier(desireOf(observer), rules)
                    if (multiplier < 0 && observer.civID == proposer) continue
                    val change = (own * rules.define(AiDefine.VOTING_HISTORY_SCORE_PLAYER_VOTE_PERCENT_VALUE) / 100 +
                        share * rules.define(AiDefine.VOTING_HISTORY_SCORE_OUTCOME_VOTE_PERCENT_VALUE) / 100) * multiplier / 100
                    val max = rules.define(AiDefine.VOTING_HISTORY_SCORE_MAX)
                    memory.votingHistoryScore = (memory.votingHistoryScore + if (against) -change else change).coerceIn(-max, max)
                }
                if (against || proposer == null || proposer == voter || sanctionTarget == proposer) continue
                // The proposer is grateful to those who helped it pass, angry at those who helped it fail
                val memory = memoryOf(league, proposer, voter) ?: continue
                val ai = league.gameInfo.getCivilizationOrNull(proposer) ?: continue
                if (passed) {
                    setProposalSupportDelta(ai, memory, -share, rules)
                    memory.supportedProposalTurn = stamp
                    memory.foiledProposalTurn = -1
                } else {
                    setProposalSupportDelta(ai, memory, share, rules)
                    memory.foiledProposalTurn = stamp
                    memory.supportedProposalTurn = -1
                }
            }
            if (host && passed && voter != outcome) {
                val (share, _) = contribution(league, proposal, voter, outcome, against = false, host = true)
                if (share > 0) memoryOf(league, outcome, voter)?.let {
                    it.supportedHostingValue = share
                    it.supportedHostingTurn = stamp
                }
            }
        }
    }

    /**
     * CvDiplomacyAI::SetProposalSupportDelta (Vox Populi): a new support (< 0) or opposition (> 0) share adds to what is left of the
     * previous one: its opinion weight faded by the turns passed over its duration, shortened by forgiveness (or
     * neediness) above 5 when the latest action reversed it, converted back to a share; -100..100.
     */
    private fun setProposalSupportDelta(civ: Civilization, memory: LeagueAlignmentMemory, value: Int, rules: AiRuleset) {
        val current = memory.proposalSupportDelta
        if (current == 0 || value == 0) {
            memory.proposalSupportDelta = value
            return
        }
        val foiledBefore = current > 0
        // VP's IsFoiledOurProposalAndThenSupportedUs / IsSupportedOurProposalAndThenFoiledUs
        val reversed = if (foiledBefore) memory.supportedProposalTurn > -1 else memory.foiledProposalTurn > -1
        var duration = OpinionTimers.adjustDuration(civ, rules.define(if (foiledBefore) AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL_NUM_TURNS
            else AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL_NUM_TURNS), civ.ai.diplomacyValue(if (foiledBefore)
            DiplomacyPersonality.WorkAgainstWillingness else DiplomacyPersonality.WorkWithWillingness))
        val turn = if (foiledBefore == reversed) memory.supportedProposalTurn else memory.foiledProposalTurn
        if (turn < 0) return
        if (reversed && (if (foiledBefore) value < 0 else value > 0)) {
            val mod = (civ.ai.diplomacyValue(if (foiledBefore) DiplomacyPersonality.Forgiveness else DiplomacyPersonality.Neediness) - 5) * 2 *
                DiplomacyTurn.opinionDurationPercent(civ) / 100
            if (mod > 0) duration -= mod
        }
        val left = 100 - (civ.gameInfo.turns - turn) * 100 / maxOf(duration, 1)
        val minWeight = if (foiledBefore) maxOf(0, rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL))
            else minOf(0, rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL))
        val maxWeight = if (foiledBefore) maxOf(0, rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL_MAX))
            else minOf(0, rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL_MAX))
        val difference = maxWeight - minWeight
        val perVote = maxOf(1, rules.define(AiDefine.OPINION_WEIGHT_PER_VOTE_PERCENT))
        val award = minOf(100, kotlin.math.abs(current) * perVote)
        val weight = (minWeight + difference * award / 100) * left / 100 - minWeight
        val leftValue = if ((weight <= 0 && foiledBefore) || (weight >= 0 && !foiledBefore)) 0
            else weight * 100 / maxOf(1, difference) / perVote
        memory.proposalSupportDelta = (value + leftValue).coerceIn(-100, 100)
    }

    /**
     * What a civilization does for the World Congress in its turn (CvGameLeagues::DoPlayerTurn, CvVotingClasses.cpp:6770-6825):
     * it proposes while it may, and in a session spends all its delegates, or gives them up if nothing is proposed.
     */
    fun automateLeague(civ: Civilization) {
        val league = civ.gameInfo.league ?: return
        if (!civ.isMajorCiv || !civ.isAlive()) return
        if (league.canPropose(civ)) doProposals(civ, league)
        if (league.inSession) {
            doVoteCommitments(civ, league) // Honor vote commitments made in deals before spending any other votes
            if (league.canVote(civ)) doVotes(civ, league)
        }
    }

    //region Alignment and diplomats

    /**
     * CvLeagueAI::EvaluateAlignment (Vox Populi, CvVotingClasses.cpp:10789-11225): how far our interests are aligned with [other]'s. War is WAR only when
     * we want to conquer them (unless [ignoreWar]), else -1 for a phony war and -2. Then: the ideologies (+2 the same, -2 different unless we ignore
     * the difference; doubled when either has World Ideology pressure), the state religions (+1 / -1, likewise with the World Religion's spread),
     * their support of our hosting (+1, +2 beyond -40), whether we liked (+1, +2 beyond OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL) or disliked their
     * proposals, their support of our proposals (-1 / -2 when they foiled them, +1 / +2 when they supported them; one less after the reverse),
     * sanctions and lifting them (-2 ever, -1 tried; +2 ever, +1 tried), the voting history score / (VOTING_HISTORY_SCORE_MAX / the alignment scaler),
     * a denunciation of us -2 else a friendship worth +1, +2 or +3 by its level unless they are untrustworthy or we want to end it, a defensive pact
     * +2; the bloc term (the mutual friends, defensive pacts and enemies they denounced, up to 4 / 3 / 2 by work-with willingness) less the enemy
     * term (their friends and pacts with our enemies, the friends they denounced, by work-against willingness); our city-state dispute with them,
     * the warmonger threat of them, -4 for a backstabber, +4 for a liberator (+2 when we compete for a victory, they are untrustworthy or we are at
     * war), the cities of ours they ever liberated, -1 per city of ours they captured, -2 for our biggest competitor. Below -8 ENEMY, -6..-8 HATRED,
     * -3..-5 RIVAL; a civilization that resurrected us is a LIBERATOR then, else 2 and less NEUTRAL, 5 FRIEND, 8 CONFIDANT, more ALLY.
     * Works without a World Congress too (the DLL evaluates alignment for diplomats before there is one), then without the Congress memories.
     * Teams and vassals (their terms, the same-master bonus), the coop war agreement score and a returned capital or holy city have no counterpart.
     */
    fun evaluateAlignment(civ: Civilization, other: Civilization, ignoreWar: Boolean = false): LeagueAlignment {
        if (!other.isAlive() || !other.isMajorCiv) return LeagueAlignment.NEUTRAL
        if (other == civ) return LeagueAlignment.SELF
        val rules = civ.gameInfo.ruleset.ai
        val diplomacy = civ.ai.diplomacy
        val memory = diplomacy.memoryOf(other)
        val league = civ.gameInfo.league
        val congress = league?.members?.get(civ.civID)?.alignmentMemory?.get(other.civID)
        val atWar = civ.isAtWarWith(other)
        var sum = 0
        if (atWar) {
            if (!ignoreWar && WarIntentAi.wantsToConquer(civ, other)) return LeagueAlignment.WAR
            sum -= if (WarIntentAi.isPhonyWar(civ, other)) 1 else 2
        }

        val ours = civ.getIdeology()
        val theirs = other.getIdeology()
        if (ours != null && theirs != null) {
            var mod = if (ours == theirs) 2 else if (!OpinionAi.isIgnoreIdeologyDifferences(civ, other)) -2 else 0
            if (league != null && (league.getIdeologyPressure(ours.name) > 0 || league.getIdeologyPressure(theirs.name) > 0)) mod *= 2
            sum += mod
        }

        val ourReligion = OpinionAi.stateReligion(civ)?.first
        val theirReligion = OpinionAi.stateReligion(other)?.first
        if (ourReligion != null && theirReligion != null) {
            var mod = if (ourReligion == theirReligion && !CompetitorAi.isHolyCityCapturedBy(civ, other)) 1
                else if (!OpinionAi.isIgnoreReligionDifferences(civ, other)) -1 else 0
            if (league != null && (league.getReligionSpreadPercent(theirReligion.name) > 0 || league.getReligionSpreadPercent(ourReligion.name) > 0)) mod *= 2
            sum += mod
        }

        if (congress != null) {
            val hosting = OpinionAi.supportedMyHostingScore(civ, congress, rules)
            if (hosting != 0) sum += if (hosting < -40) 2 else 1

            val agreement = congress.proposalAgreementValue
            if (agreement < 0) sum += if (agreement < rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL)) 2 else 1
            else if (agreement > 0) sum -= if (agreement > rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL)) 2 else 1

            val supported = OpinionAi.supportedOurProposalScore(civ, congress, rules)
            if (supported > 0) sum -= if (congress.supportedProposalTurn > -1 && congress.proposalSupportDelta > 0) 1 else 2
            else if (supported < 0) sum += if (congress.foiledProposalTurn > -1 && congress.proposalSupportDelta < 0) 1 else 2

            if (congress.everSanctioned) sum -= 2 else if (congress.sanctionedTurn != -1) sum -= 1
            if (congress.everUnsanctioned) sum += 2 else if (congress.unsanctionedTurn != -1) sum += 1

            // "This is intentionally powerful": it takes long and major support to reach +-10
            sum += congress.votingHistoryScore / max(rules.define(AiDefine.VOTING_HISTORY_SCORE_MAX) / rules.define(AiDefine.VOTING_HISTORY_SCORE_LEAGUE_ALIGNMENT_SCALER), 1)
        }

        val untrustworthy = OpinionAi.isUntrustworthyFriend(civ, other)
        if (OpinionAi.isDenouncedPlayer(other, civ)) sum -= 2
        else if (!untrustworthy && !memory.wantsToEndDoF && (OpinionAi.isDoFAccepted(civ, other) || memory.wantsDoF)) {
            sum += when (memory.doFLevel) {
                DoFLevel.Untrustworthy, DoFLevel.New -> 0
                DoFLevel.Friends -> 1
                DoFLevel.Allies -> 2
                DoFLevel.BattleBrothers -> 3
            }
        }
        if (!untrustworthy && hasDefensivePact(civ, other) && !memory.wantsToEndDefensivePact) sum += 2

        // Encourage bloc formation
        val workWith = civ.ai.diplomacyValue(DiplomacyPersonality.WorkWithWillingness)
        val workAgainst = civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)
        val maxBonus = if (workWith > 8) 4 else if (workWith < 4) 2 else 3
        sum += min(numMutualFriends(civ, other) + numMutualDefensePacts(civ, other) + numEnemiesDenounced(civ, other), maxBonus)
        val maxPenalty = if (workAgainst > 8) 4 else if (workAgainst < 4) 2 else 3
        sum -= min(numEnemyFriends(civ, other) + numEnemyDefensePacts(civ, other) + numFriendsDenounced(civ, other), maxPenalty)

        sum -= memory.minorCivDispute.ordinal
        sum -= diplomacy.warmongerThreat(other).ordinal

        if (untrustworthy) sum -= 4
        if (memory.liberatedCapital || memory.liberatedHolyCity || memory.resurrectedUs)
            sum += if (!VictoryProgressAi.isCompetingForVictory(civ) && !untrustworthy && !atWar) 4 else 2
        if (!atWar && memory.numCitiesEverLiberated > 0) sum += memory.numCitiesEverLiberated
        sum -= memory.numCitiesCapturedBy
        if (diplomacy.biggestCompetitor == other.civID) sum -= 2

        return when {
            sum < -8 -> LeagueAlignment.ENEMY
            sum < -5 -> LeagueAlignment.HATRED
            sum < -2 -> LeagueAlignment.RIVAL
            memory.resurrectedUs -> LeagueAlignment.LIBERATOR
            sum <= 2 -> LeagueAlignment.NEUTRAL
            sum <= 5 -> LeagueAlignment.FRIEND
            sum <= 8 -> LeagueAlignment.CONFIDANT
            else -> LeagueAlignment.ALLY
        }
    }

    internal fun hasDefensivePact(civ: Civilization, other: Civilization) =
        civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** The majors alive with cities that the counts of CvDiplomacyAI (GetNumMutualFriends and the like) go over; the friendship counts skip a human's view of a human */
    private fun blocMembers(civ: Civilization, skipHumans: Boolean) = civ.gameInfo.getAliveMajorCivs()
        .filter { it.cities.isNotEmpty() && !(skipHumans && civ.isHuman() && it.isHuman()) }

    /** GetNumMutualFriends: majors we and [other] both have a friendship with */
    private fun numMutualFriends(civ: Civilization, other: Civilization) =
        blocMembers(civ, true).count { OpinionAi.isDoFAccepted(civ, it) && OpinionAi.isDoFAccepted(other, it) }

    /** GetNumEnemyFriends: majors [other] is friends with that we denounced or are at war with */
    private fun numEnemyFriends(civ: Civilization, other: Civilization) = blocMembers(civ, true)
        .count { OpinionAi.isDoFAccepted(other, it) && (OpinionAi.isDenouncedPlayer(civ, it) || civ.isAtWarWith(it)) }

    /** GetNumMutualDefensePacts */
    private fun numMutualDefensePacts(civ: Civilization, other: Civilization) =
        blocMembers(civ, false).count { hasDefensivePact(civ, it) && hasDefensivePact(other, it) }

    /** GetNumEnemyDefensePacts: majors [other] has a defensive pact with that we denounced or are at war with */
    private fun numEnemyDefensePacts(civ: Civilization, other: Civilization) = blocMembers(civ, false)
        .count { hasDefensivePact(other, it) && (OpinionAi.isDenouncedPlayer(civ, it) || civ.isAtWarWith(it)) }

    /** GetNumFriendsDenounced: our friends [other] denounced */
    private fun numFriendsDenounced(civ: Civilization, other: Civilization) =
        blocMembers(civ, true).count { OpinionAi.isDoFAccepted(civ, it) && OpinionAi.isDenouncedPlayer(other, it) }

    /** GetNumEnemiesDenounced: majors we denounced or are at war with that [other] denounced too */
    private fun numEnemiesDenounced(civ: Civilization, other: Civilization) = blocMembers(civ, true)
        .count { (OpinionAi.isDenouncedPlayer(civ, it) || civ.isAtWarWith(it)) && OpinionAi.isDenouncedPlayer(other, it) }

    /**
     * CvLeagueAI::GetDiplomatUsefulnessAtCiv (CvVotingClasses.cpp:8242-8266): +1 if our diplomats give delegates (Globalization,
     * GetExtraVotesPerDiplomat), +1 if [other] is more than neutrally aligned with us. HIGH is never returned, as in the DLL.
     */
    fun getDiplomatUsefulnessAtCiv(civ: Civilization, other: Civilization): DiplomatUsefulness {
        var score = 0
        if (civ.tech.isResearched(League.GLOBALIZATION)) score++
        if (evaluateAlignment(civ, other) > LeagueAlignment.NEUTRAL) score++
        return when {
            score >= 2 -> DiplomatUsefulness.MEDIUM
            score >= 1 -> DiplomatUsefulness.LOW
            else -> DiplomatUsefulness.NONE
        }
    }

    //endregion
    //region Vote commitments

    /**
     * CvLeagueAI::GetDesiredVoteCommitments (Vox Populi, CvVotingClasses.cpp:10317-10495): the commitment [civ] would like [fromCiv] to make to it, one per
     * proposal. For an AI the choice it desires most, if more than neutrally; for a human the assumed ones (yes on its own proposals, else yes or no by
     * how much it likes the proposal without the global terms, itself as host or World Leader). The votes are the core votes of [fromCiv].
     */
    fun desiredVoteCommitments(civ: Civilization, fromCiv: Civilization): List<VoteCommitment> {
        val league = civ.gameInfo.league ?: return emptyList()
        val resolutions = civ.gameInfo.ruleset.resolutions
        val votes = league.getCoreVotes(fromCiv)
        val desired = ArrayList<VoteCommitment>()
        for (proposal in league.proposals) {
            if (proposal.onHold) continue
            val resolution = resolutions[proposal.resolution] ?: continue
            var choice: String? = null
            if (civ.isHuman()) {
                // Yes on its own proposals, else by its desire for the proposal without the global terms; itself as host or World Leader
                if (proposal.repeal || resolution.voterDecision == ResolutionDecision.YesNo) {
                    if (proposal.proposer == civ.civID) choice = League.YES
                    else {
                        val desire = LeagueDesire.of(LeagueEvaluator(civ, league).scoreOutcome(proposal, League.YES))
                        if (desire > LeagueDesire.NEUTRAL) choice = League.YES
                        else if (desire < LeagueDesire.NEUTRAL) choice = League.NO
                    }
                } else if (resolution.isHostElection || resolution.isWorldLeader) choice = civ.civID
            } else {
                var highest = LeagueDesire.NEUTRAL
                val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution.voterDecision
                for (option in league.getChoices(decision, fromCiv)) {
                    val desire = evaluateVoteForTrade(civ, proposal.id, option, votes, proposal.repeal)
                    if (desire > highest) {
                        choice = option
                        highest = desire
                    }
                }
            }
            if (choice != null) desired.add(VoteCommitment(civ.civID, proposal.id, choice, votes, !proposal.repeal))
        }
        return desired
    }

    /**
     * CvLeagueAI::EvaluateVoteForTrade (CvVotingClasses.cpp:7819-7863): how much [civ] likes [choice] on the proposal [proposalId] (an enactment,
     * or a repeal if [repeal]), whoever casts the votes. NEVER if the proposal is not on the table.
     */
    fun evaluateVoteForTrade(civ: Civilization, proposalId: Int, choice: String, @Suppress("UNUSED_PARAMETER") votes: Int, repeal: Boolean): LeagueDesire {
        val league = civ.gameInfo.league ?: return LeagueDesire.NEVER
        val proposal = league.proposals.firstOrNull { it.id == proposalId && it.repeal == repeal && !it.onHold } ?: return LeagueDesire.NEVER
        return LeagueDesire.of(LeagueEvaluator(civ, league).scoreVote(proposal, choice))
    }

    /**
     * CvLeagueAI::CanCommitVote (CvVotingClasses.cpp:7665-7752): whether [toCiv] can secure the votes of [civ] in a deal: there is a World Congress,
     * it is not in session, [toCiv] has a diplomat in our capital, we are a voting member, something is proposed and we have committed nothing yet.
     */
    fun canCommitVote(civ: Civilization, toCiv: Civilization): Boolean {
        val league = civ.gameInfo.league ?: return false
        if (league.inSession) return false
        if (toCiv.espionageManager.getActiveDiplomatFor(civ) == null) return false
        val member = league.members[civ.civID] ?: return false
        if (!civ.isMajorCiv || !civ.isAlive()) return false
        if (league.proposals.none { !it.onHold }) return false
        return member.voteCommitments.isEmpty()
    }

    /**
     * CvDeal::IsPossibleToTradeItem for TRADE_ITEM_VOTE_COMMITMENT: [civ] promises [toCiv] [votes] votes on [choice] of the proposal [proposalId]
     * (an enactment, or a repeal if [repeal]). Not while at war; [canCommitVote]; the proposal must be on the table (not on hold) and at least one vote and
     * at most the core votes of [civ] promised. The original checks the choice nowhere (a todo) and only asserts the votes.
     */
    fun isVoteCommitmentPossible(civ: Civilization, toCiv: Civilization, proposalId: Int, choice: String, votes: Int, repeal: Boolean): Boolean {
        if (civ.isAtWarWith(toCiv) || !canCommitVote(civ, toCiv)) return false
        val league = civ.gameInfo.league ?: return false
        if (votes < 1 || votes > league.getCoreVotes(civ)) return false
        val proposal = league.proposals.firstOrNull { it.id == proposalId && it.repeal == repeal && !it.onHold } ?: return false
        return choice in choicesOf(league, proposal, civ)
    }

    /** What the voters of [proposal] choose from: yes or no for a repeal */
    private fun choicesOf(league: League, proposal: LeagueProposal, civ: Civilization): List<String> {
        val decision = if (proposal.repeal) ResolutionDecision.YesNo else league.gameInfo.ruleset.resolutions[proposal.resolution]?.voterDecision
        return if (decision == null) emptyList() else league.getChoices(decision, civ)
    }

    /**
     * The vote commitments [civ] could make to [toCiv] in a deal, which the trade screen offers: all its core votes on each choice of each proposal on
     * the table, if [isVoteCommitmentPossible] allows any. (The original's Lua offers the same items.)
     */
    fun availableVoteCommitments(civ: Civilization, toCiv: Civilization): List<VoteCommitment> {
        val league = civ.gameInfo.league ?: return emptyList()
        if (civ.isAtWarWith(toCiv) || !canCommitVote(civ, toCiv)) return emptyList()
        val votes = league.getCoreVotes(civ)
        if (votes < 1) return emptyList()
        return league.proposals.filter { !it.onHold }.flatMap { proposal ->
            choicesOf(league, proposal, civ).map { VoteCommitment(toCiv.civID, proposal.id, it, votes, !proposal.repeal) }
        }
    }

    /** CvLeagueAI::AddVoteCommitment: a deal secured the votes of [civ] for [toCiv] */
    fun addVoteCommitment(civ: Civilization, toCiv: Civilization, proposalId: Int, choice: String, votes: Int, repeal: Boolean) {
        val member = civ.gameInfo.league?.members?.get(civ.civID) ?: return
        member.voteCommitments.add(VoteCommitment(toCiv.civID, proposalId, choice, votes, !repeal))
    }

    /** CvLeagueAI::CancelVoteCommitmentsToPlayer: the deal that made them ended (war, death) */
    fun cancelVoteCommitmentsToPlayer(civ: Civilization, toCiv: Civilization) {
        civ.gameInfo.league?.members?.get(civ.civID)?.voteCommitments?.removeAll { it.toCiv == toCiv.civID }
    }

    /**
     * CvLeagueAI::DoVoteCommitments (CvVotingClasses.cpp:7775-7816): votes as promised on the proposals on the table; keeps a commitment on a proposal
     * that is on hold, drops one whose proposal is gone. Humans' commitments are honored too.
     */
    fun doVoteCommitments(civ: Civilization, league: League) {
        val member = league.members[civ.civID] ?: return
        val iterator = member.voteCommitments.iterator()
        while (iterator.hasNext()) {
            val commitment = iterator.next()
            if (!league.canVote(civ)) continue
            val proposal = league.proposals.firstOrNull { it.id == commitment.proposalId && it.repeal == !commitment.enact }
            if (proposal != null && proposal.onHold) continue
            if (proposal != null) {
                val resolution = league.gameInfo.ruleset.resolutions[proposal.resolution]
                val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution?.voterDecision
                val votes = minOf(commitment.votes, league.getDelegatesLeft(civ))
                // A choice that is no longer valid (the civilization voted for died) is dropped like a proposal that is gone
                if (votes > 0 && decision != null && commitment.choice in league.getChoices(decision, civ))
                    league.vote(proposal.id, civ, votes, commitment.choice)
            }
            iterator.remove()
        }
    }

    //endregion

    //region Proposals

    private class ProposalConsideration(val resolution: Resolution?, val choice: String?, val repeal: ActiveResolution?)

    /** CvLeagueAI::DoProposals: one proposal per privilege (Vox Populi, CvVotingClasses.cpp:10302-10315) */
    private fun doProposals(civ: Civilization, league: League) {
        val evaluator = LeagueEvaluator(civ, league)
        var loops = 0
        while (league.canPropose(civ) && loops++ < MAX_LOOPS) {
            // The score of what we like, and (by how bad) of what we do not
            val liked = ArrayList<Pair<ProposalConsideration, Int>>()
            val disliked = ArrayList<Pair<ProposalConsideration, Int>>()
            fun consider(consideration: ProposalConsideration, score: Int) {
                if (score > 0) liked.add(consideration to score) else disliked.add(consideration to -score)
            }
            for (active in league.activeResolutions.toList()) {
                if (!league.canProposeRepeal(active.id, civ)) continue
                consider(ProposalConsideration(null, null, active), evaluator.scoreRepeal(active, civ.civID, global = true))
            }
            for (resolution in league.gameInfo.ruleset.resolutions.values) {
                val choices: List<String?> =
                    if (resolution.proposerDecision == ResolutionDecision.None) listOf(null)
                    else league.getChoices(resolution, civ)
                for (choice in choices) {
                    if (!league.canProposeEnact(resolution, choice, civ)) continue
                    consider(ProposalConsideration(resolution, choice, null), evaluator.scoreProposal(resolution, choice, civ.civID, global = true))
                }
            }
            val chosen = chooseProposal(liked, disliked) ?: return // nothing to propose, League.removeUselessPrivileges takes the right away next turn
            if (chosen.repeal != null) league.proposeRepeal(chosen.repeal.id, civ)
            else league.proposeEnact(chosen.resolution!!, chosen.choice, civ)
        }
    }

    /**
     * CvLeagueAI::AllocateProposals (Vox Populi, CvVotingClasses.cpp:14038-14199): the best proposal by score, the first of equals in the order of the
     * repeals, then the resolutions and their choices; if we like none, the least bad one (the last of the equally bad ones). The original halves
     * the score of a resolution proposed in the previous session, but compares the proposal's number with the resolution's (not ported: the
     * World Congress keeps no last session's proposals, and cannot propose them again).
     */
    private fun chooseProposal(liked: List<Pair<ProposalConsideration, Int>>, disliked: List<Pair<ProposalConsideration, Int>>): ProposalConsideration? {
        var best: Pair<ProposalConsideration, Int>? = null
        for (candidate in liked) if (best == null || candidate.second > best.second) best = candidate
        if (best != null) return best.first
        for (candidate in disliked) if (best == null || candidate.second <= best.second) best = candidate
        return best?.first
    }

    //endregion
    //region Votes

    private class VoteConsideration(val proposal: LeagueProposal, val choice: String, var weight: Int, var delegates: Int = 0)

    /**
     * CvLeagueAI::DoVotes and AllocateVotes (Vox Populi, CvVotingClasses.cpp:10278-10295, 11729-11886): each proposal weighs by the score of our best choice
     * on it (no negative weights; if nothing weighs anything all weigh 1000). The delegates go one at a time to the heaviest proposal (the first of equals
     * in the order of the table); each vote takes 1 / (the votes left, ours included) of that proposal's weight, so the delegates spread over the
     * proposals we like; once as many delegates are on it as all other members have left (at least the members) it takes no more. No chance is
     * involved and the delegates are not given up while a proposal is on the table. The original's weight bonus when an embargo on us is proposed of the
     * old rules is gone: the embargo is -1000 in the score itself.
     */
    private fun doVotes(civ: Civilization, league: League) {
        val onTable = league.proposals.filter { !it.onHold }
        if (onTable.isEmpty()) {
            league.abstainRemaining(civ)
            return
        }
        val evaluator = LeagueEvaluator(civ, league)
        val considerations = ArrayList<VoteConsideration>()
        for (proposal in onTable) {
            val (choice, score) = evaluator.findBestVoteChoice(proposal) ?: continue
            considerations.add(VoteConsideration(proposal, choice, score))
        }
        if (considerations.isEmpty()) {
            league.abstainRemaining(civ)
            return
        }
        if (considerations.sumOf { it.weight } <= 0) for (consideration in considerations) consideration.weight = 1000

        val votes = league.getDelegatesLeft(civ)
        val members = league.members.size + league.gameInfo.getAliveCityStates().size
        val othersCombined = max(members, league.members.entries.filter { it.key != civ.civID }.sumOf { it.value.delegatesLeft })
        for (spent in 0 until votes) {
            considerations.sortByDescending { it.weight } // stable
            val chosen = considerations.first()
            chosen.delegates++
            chosen.weight = max(0, chosen.weight - chosen.weight / max(1, votes - spent))
            // Enough to pass it whatever the others do
            if (chosen.delegates >= othersCombined) chosen.weight = 0
        }
        for (consideration in considerations)
            if (consideration.delegates > 0) league.vote(consideration.proposal.id, civ, consideration.delegates, consideration.choice)
        if (league.canVote(civ)) league.abstainRemaining(civ)
    }

    //endregion
}

/** How the civilization sees another one in the World Congress (CvLeagueAI::AlignmentLevels, CvVotingClasses.h:786-799) */
enum class LeagueAlignment { WAR, ENEMY, HATRED, RIVAL, NEUTRAL, FRIEND, CONFIDANT, ALLY, SELF, LEADER, LIBERATOR }

/** How much the civilization likes a choice (CvLeagueAI::DesireLevels, CvVotingClasses.h:759-771) */
enum class LeagueDesire {
    NEVER, STRONG_DISLIKE, DISLIKE, WEAK_DISLIKE, NEUTRAL, WEAK_LIKE, LIKE, STRONG_LIKE, ALWAYS;

    companion object {
        /** CvLeagueAI::EvaluateDesire (Vox Populi, CvVotingClasses.cpp:11678-11720; BNW's bands were 15, 45, 75 and 105) */
        fun of(rawScore: Int): LeagueDesire = when {
            rawScore < -2500 -> NEVER
            rawScore < -1000 -> STRONG_DISLIKE
            rawScore < -250 -> DISLIKE
            rawScore < -50 -> WEAK_DISLIKE
            rawScore <= 50 -> NEUTRAL
            rawScore <= 250 -> WEAK_LIKE
            rawScore <= 1000 -> LIKE
            rawScore <= 2500 -> STRONG_LIKE
            else -> ALWAYS
        }
    }
}

/** Where a diplomat helps in the World Congress (CvLeagueAI::DiplomatUsefulnessLevels) */
enum class DiplomatUsefulness { NONE, LOW, MEDIUM, HIGH }

/**
 * Scores what [civ] likes in the World Congress (CvLeagueAI::ScoreVoteChoice and friends, Vox Populi CvVotingClasses.cpp:11729-14253).
 * Positive is like. The scores are the integers of the original; a desire level is 50 to 2500 of them ([LeagueDesire]).
 *
 * ## Mapping layer: inputs of the original and what stands in for them
 * - The victories enabled: [VictoryPursuitAi.isVictoryEnabled] (the diplomatic, culture and space race victories). The game's era: [DiplomacyTurn.gameEraNumber].
 * - Production might: the production of the cities without what excess food adds (CvPlayer::calculateProductionMight, getRawProductionDifference
 *   with bIgnoreFood). Tech counts: the number of researched technologies of the living major civilizations (the teams of the original).
 * - Alignment: [LeagueAutomation.evaluateAlignment]. Opinion, approach, warmonger threat, target value, war state: the state of [com.unciv.logic.automation.diplomacy.DiplomacyAi]
 *   ([com.unciv.logic.automation.strategy.WarStateAi.targetValue] for the raw target value). Willingness: [DiplomacyPersonality.WorkWithWillingness]
 *   and [DiplomacyPersonality.WorkAgainstWillingness].
 * - Trade connections: routes between the two civilizations (from one only, or in either direction, as the original asks). Possible routes:
 *   [com.unciv.logic.civilization.managers.TradeRouteManager.getNumSlots].
 * - Wonders: the world wonders in the cities. Natural wonders and resources "in owned plots": the tiles of the cities. Empire unhappy: happiness below 0.
 * - Great person improvements: tiles with a great improvement; landmarks: tiles with the improvement [LANDMARK]. A unique great person unit: the
 *   civilization's equivalent of the base unit is another unit.
 * - Resolutions are found by effect (uniques and typed fields), except Nuclear Non-Proliferation, which has no effect field, and the projects: by name.
 *
 * ## Gaps
 * Teams and vassals, the prime league ally and competitor, the coop war agreement score, the value of our deals with
 * a civilization (embargo), the raw target value, monopolies, Nuclear Gandhi, the policies that give influence from trade routes with city-states,
 * the league bonus for civilizations behind in score (Arts and Sciences Funding), what Vox Populi does with the halved score of last session's proposals.
 */
class LeagueEvaluator(private val civ: Civilization, private val league: League) {
    companion object {
        const val LANDMARK = "Landmark"
        private const val WORLDS_FAIR = "World's Fair"
        private const val INTERNATIONAL_GAMES = "International Games"
        private const val INTERNATIONAL_SPACE_STATION = "International Space Station"
        private const val NUCLEAR_NON_PROLIFERATION = "Nuclear Non-Proliferation"
        private val artsyPersons = setOf("Great Artist", "Great Writer", "Great Musician")
        private val scienceyPersons = setOf("Great Scientist", "Great Engineer", "Great Merchant")
    }

    private val gameInfo = league.gameInfo
    private val rules = gameInfo.ruleset.ai

    private val ownedTiles by lazy { civ.cities.flatMap { it.getTiles().toList() } }

    //region Entry points

    /**
     * CvLeagueAI::ScoreProposal for an enactment (CvVotingClasses.cpp:14201-14226): our YES on a proposal by [proposer] (none: the alignment with the
     * proposer does not count; with [global] the AI's own proposals count their proposer as SELF), as a proposer sees it ([ScoreVoteChoiceYesNo] with bProposingToEnact)
     */
    fun scoreProposal(resolution: Resolution, choice: String?, proposer: String? = null, global: Boolean = false): Int =
        scoreYesNo(resolution, choice, proposer, League.YES, enact = true, global = global, proposing = true)

    /** CvLeagueAI::ScoreProposal for a repeal (CvVotingClasses.cpp:14228-14253): our YES on repealing, the effects score reversed */
    fun scoreRepeal(active: ActiveResolution, proposer: String? = null, global: Boolean = false): Int {
        val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: return 0
        return scoreYesNo(resolution, active.choice, proposer, League.YES, enact = false, global = global)
    }

    /**
     * CvLeagueAI::ScoreVoteChoice with bConsiderGlobal: how much we like [choice] on [proposal] (YES/NO, or the id of a civilization). For a vote on a
     * civilization (host, World Leader) the proposal's proposer does not matter, for a yes/no vote his alignment does.
     */
    fun scoreVote(proposal: LeagueProposal, choice: String): Int {
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return 0
        if (!proposal.repeal && isPlayerDecision(resolution)) {
            val candidate = gameInfo.getCivilizationOrNull(choice) ?: return 0
            return scorePlayerChoice(resolution, candidate)
        }
        return scoreYesNo(resolution, proposal.proposerChoice, proposal.proposer, choice, enact = !proposal.repeal, global = true)
    }

    /**
     * CvLeagueAI::EvaluateDesireForVoteOutcome (CvVotingClasses.cpp:11723-11726): how much we like the [outcome] of the vote on [proposal], with no
     * global terms. A vote on a civilization has no effects, so it scores 0.
     */
    fun scoreOutcome(proposal: LeagueProposal, outcome: String): Int {
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return 0
        return scoreYesNo(resolution, proposal.proposerChoice, proposal.proposer, outcome, enact = !proposal.repeal, global = false)
    }

    /** CvLeagueAI::ScoreVoteChoicePlayer is used for the host and the World Leader */
    private fun isPlayerDecision(resolution: Resolution) =
        resolution.voterDecision == ResolutionDecision.MajorCiv || resolution.voterDecision == ResolutionDecision.OtherMajorCiv

    /**
     * CvLeagueAI::FindBestVoteChoices (CvVotingClasses.cpp:11889-11991): the best of the choices with its score, `null` if there is none. No choice
     * weighs less than 0 and the first of equally scored choices wins (the original's stable sort): Yes before No, a civilization in the order of
     * the civilizations.
     */
    fun findBestVoteChoice(proposal: LeagueProposal): Pair<String, Int>? {
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return null
        val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution.voterDecision
        var best: Pair<String, Int>? = null
        for (choice in league.getChoices(decision, civ)) {
            val score = max(0, scoreVote(proposal, choice))
            if (best == null || score > best.second) best = choice to score
        }
        return best
    }

    //endregion
    //region Choosing a civilization

    /**
     * CvLeagueAI::ScoreVoteChoicePlayer (Vox Populi, CvVotingClasses.cpp:13902-14036): World Leader and Choose Host. The votes are the delegates of the
     * session (CalculateStartingVotesForMember), the percents those of the votes needed to win ([League.getVotesNeededForWorldLeader]). World Leader:
     * the leader of our team 100000 (none here), ourselves AI_WORLD_LEADER_BASE_WEIGHT_SELF + 30 per percent of our own votes, a liberator the base +
     * 30 per percent of theirs, an ally 25, a confidant 15, a friend 10 per percent of theirs but only if we like them, a rival -25, hatred -100, an
     * enemy or war -250. Choose Host: ourselves 200 per vote (+1), a liberator likewise their votes, an ally 150, a confidant 100, a friend 50 per
     * vote (+1) if liked, a rival -50, hatred -150, an enemy or war -300. The options that keep us from voting for other teams are off in Vox Populi.
     */
    fun scorePlayerChoice(resolution: Resolution, candidate: Civilization): Int {
        var score = 0
        val ourVotes = league.getDelegates(civ)
        val theirVotes = league.getDelegates(candidate)
        val needed = maxOf(1, league.getVotesNeededForWorldLeader())
        val theirPercent = theirVotes * 100 / needed
        val ourPercent = ourVotes * 100 / needed
        val alignment = LeagueAutomation.evaluateAlignment(civ, candidate)
        val liked = civ.ai.diplomacy.opinionOf(candidate) > MajorCivOpinion.Neutral
        if (resolution.isWorldLeader) {
            val base = rules.define(AiDefine.AI_WORLD_LEADER_BASE_WEIGHT_SELF)
            score += when (alignment) {
                LeagueAlignment.LEADER -> 100000
                LeagueAlignment.SELF -> max(base + 30 * ourPercent, 1)
                LeagueAlignment.LIBERATOR -> base + 30 * theirPercent
                LeagueAlignment.ALLY -> if (liked) 25 * theirPercent else 0
                LeagueAlignment.CONFIDANT -> if (liked) 15 * theirPercent else 0
                LeagueAlignment.FRIEND -> if (liked) 10 * theirPercent else 0
                LeagueAlignment.RIVAL -> -25 * theirPercent
                LeagueAlignment.HATRED -> -100 * theirPercent
                LeagueAlignment.ENEMY, LeagueAlignment.WAR -> -250 * theirPercent
                LeagueAlignment.NEUTRAL -> 0
            }
        }
        if (resolution.isHostElection) {
            score += when (alignment) {
                LeagueAlignment.LEADER -> 100000
                LeagueAlignment.SELF -> 200 * (ourVotes + 1)
                LeagueAlignment.LIBERATOR -> 200 * (theirVotes + 1)
                LeagueAlignment.ALLY -> if (liked) 150 * (theirVotes + 1) else 0
                LeagueAlignment.CONFIDANT -> if (liked) 100 * (theirVotes + 1) else 0
                LeagueAlignment.FRIEND -> if (liked) 50 * (theirVotes + 1) else 0
                LeagueAlignment.RIVAL -> -50 * (theirVotes + 1)
                LeagueAlignment.HATRED -> -150 * (theirVotes + 1)
                LeagueAlignment.ENEMY, LeagueAlignment.WAR -> -300 * (theirVotes + 1)
                LeagueAlignment.NEUTRAL -> 0
            }
        }
        return score
    }

    //endregion
    //region Yes/No resolutions

    /**
     * CvLeagueAI::ScoreVoteChoiceYesNo (Vox Populi, CvVotingClasses.cpp:12067-13899) for [choice] (yes or no) on a proposal to enact ([enact]) or repeal
     * [resolution] with the proposer's [target] choice: the score of YES to enact is the effects ([scoreEffects]) plus, when [proposing], the sanction
     * terms; a repeal flips it; with [global] the alignment with [proposerId] (SELF, LIBERATOR, LEADER 150, ALLY 90, CONFIDANT 65, FRIEND 40 to work-with
     * willingness; RIVAL -40, HATRED -65, ENEMY and WAR -90 to work-against willingness) is added; No flips it again; with [global] also what the other
     * voting majors, weighted by how much we like them, think of it (their own score without global terms, [forSelf] false), x16 / (10 + the voters).
     * [forSelf] is false when another civilization's evaluator scores the same proposal for us.
     */
    private fun scoreYesNo(resolution: Resolution, target: String?, proposerId: String?, choice: String, enact: Boolean, global: Boolean,
                           forSelf: Boolean = true, proposing: Boolean = false): Int {
        var score = scoreEffects(resolution, target, forSelf)
        if (proposing && resolution.embargoesPlayer && target != null) score += sanctionProposalScore(target)
        if (!enact) score = -score
        if (global && proposerId != null) score += proposerAlignmentScore(proposerId)
        if (choice == League.NO) score = -score
        if (global) score += globalScore(resolution, target, proposerId, choice, enact)
        return score
    }

    private fun proposerAlignmentScore(proposerId: String): Int {
        val proposer = gameInfo.getCivilizationOrNull(proposerId) ?: return 0
        val workWith = civ.ai.diplomacyValue(DiplomacyPersonality.WorkWithWillingness)
        val workAgainst = civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)
        return when (LeagueAutomation.evaluateAlignment(civ, proposer)) {
            LeagueAlignment.SELF, LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER -> 150
            LeagueAlignment.ALLY -> OpinionAi.adjustConditional(90, workWith)
            LeagueAlignment.CONFIDANT -> OpinionAi.adjustConditional(65, workWith)
            LeagueAlignment.FRIEND -> OpinionAi.adjustConditional(40, workWith)
            LeagueAlignment.RIVAL -> OpinionAi.adjustConditional(-40, workAgainst)
            LeagueAlignment.HATRED -> OpinionAi.adjustConditional(-65, workAgainst)
            LeagueAlignment.ENEMY, LeagueAlignment.WAR -> OpinionAi.adjustConditional(-90, workAgainst)
            LeagueAlignment.NEUTRAL -> 0
        }
    }

    /**
     * "How much will friends/enemies care about this?" (CvVotingClasses.cpp:13813-13896): for every other living major with cities that can vote, its good
     * and bad diplomacy score (800 per opinion step above or below neutral, 800 more for our most valuable friend or ally, 600 less for our biggest
     * competitor, 200 per level of the larger of our victory dispute and block levels with it, and by its alignment +-800 / 1600 / 2400, a
     * teammate, liberator or leader 10000 and no weighing) weighted by our work-with and
     * work-against willingness (/ 2000) times its own score of the same choice (/ 100). The prime league ally and competitor
     * have no counterpart.
     */
    private fun globalScore(resolution: Resolution, target: String?, proposerId: String?, choice: String, enact: Boolean): Int {
        val workWith = civ.ai.diplomacyValue(DiplomacyPersonality.WorkWithWillingness)
        val workAgainst = civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)
        var total = 0
        var voters = 1
        for (other in gameInfo.getAliveMajorCivs()) {
            if (other == civ || other.cities.isEmpty() || other.civID !in league.members) continue
            val steps = (civ.ai.diplomacy.opinionOf(other).ordinal - MajorCivOpinion.Neutral.ordinal) * 800
            var bad = if (steps < 0) steps else 0
            var good = if (steps > 0) steps else 0
            if (civ.ai.diplomacy.mostValuableFriend == other.civID || civ.ai.diplomacy.mostValuableAlly == other.civID) good += 800
            if (civ.ai.diplomacy.biggestCompetitor == other.civID) bad -= 600
            val memory = civ.ai.diplomacy.memoryOf(other)
            bad -= max(memory.victoryDisputeLevel.ordinal, memory.victoryBlockLevel.ordinal) * 200
            var maxCooperation = false
            when (LeagueAutomation.evaluateAlignment(civ, other)) {
                LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER -> { good = 10000; maxCooperation = true }
                LeagueAlignment.ALLY -> good += 2400
                LeagueAlignment.CONFIDANT -> good += 1600
                LeagueAlignment.FRIEND -> good += 800
                LeagueAlignment.RIVAL -> bad -= 800
                LeagueAlignment.HATRED -> bad -= 1600
                LeagueAlignment.ENEMY, LeagueAlignment.WAR -> bad -= 2400
                else -> Unit
            }
            var diplo = if (maxCooperation) good / 200 else good * workWith / 2000 + bad * workAgainst / 2000
            diplo *= LeagueEvaluator(other, league).scoreYesNo(resolution, target, proposerId, choice, enact, global = false, forSelf = false)
            total += diplo / 100
            voters++
        }
        return total * 16 / (10 + voters)
    }

    /**
     * The effects part of ScoreVoteChoiceYesNo (CvVotingClasses.cpp:12117-13682) for YES on enacting [resolution] with the proposer's [target] choice, as the
     * sum of what each effect is worth to us, [forSelf] being false when another civilization's evaluator scores it for us (the terms that only the civilization
     * itself pays are left out). Scores are large: a desire level is 50 to 2500 of them (see [LeagueDesire]).
     * Resolutions without a counterpart in the rules (Treasure Fleet, Wargames, United Nations, Casus Belli, Open Door, Decolonization, Spaceship Control
     * Committee, Cold War, Reduced Tourism, Sphere of Influence, the vassal resolutions) are not scored.
     */
    fun scoreEffects(resolution: Resolution, target: String?, forSelf: Boolean = true): Int {
        var score = 0

        if (resolution.isProject) score += scoreProject(resolution.name, forSelf)
        if (resolution.embargoesCityStates) score += scoreEmbargoCityStates(forSelf)
        if (resolution.embargoesPlayer) score += scoreEmbargoPlayer(target, forSelf)
        if (resolution.bansLuxuryHappiness) score += scoreBanLuxury(target, forSelf)

        val maintenancePercent = resolution.getMatchingUniques(UniqueType.UnitGoldMaintenancePercent).sumOf { it.params[0].toInt() }
        if (maintenancePercent != 0) score += scoreStandingArmyTax(maintenancePercent)

        if (resolution.hasUnique(UniqueType.TechKnownByAnyCivResearchSpeed)) score += scoreScholars()

        val cultureFrom = resolution.getMatchingUniques(UniqueType.StatsFromObject)
            .filter { it.stats[Stat.Culture] != 0f }.map { it.params[1] }.toSet()
        if ("World Wonder" in cultureFrom) score += 40 * numWonders + 20 * numGreatWorks - (if (forSelf) 75 * era else 0)
        if ("Natural Wonder" in cultureFrom) score += 200 * ownedTiles.count { it.isNaturalWonder() } - (if (forSelf) 100 else 0)

        if (resolution.name == NUCLEAR_NON_PROLIFERATION) score += scoreNuclearNonProliferation()

        if (resolution.votesForFollowingReligion != 0 || resolution.holyCityTourismPercent != 0 || resolution.religionSpreadStrengthPercent != 0)
            score += scoreWorldReligion(target, forSelf)
        if (resolution.votesForFollowingIdeology != 0 || resolution.ideologyPressure != 0) score += scoreWorldIdeology(target, forSelf)

        val greatPersonRates = resolution.getMatchingUniques(UniqueType.GreatPersonEarnedFaster)
        val artsyMod = greatPersonRates.filter { it.params[0] in artsyPersons }.sumOf { it.params[1].toInt() }
        val scienceyMod = greatPersonRates.filter { it.params[0] in scienceyPersons }.sumOf { it.params[1].toInt() }
        if (artsyMod != 0 || scienceyMod != 0) score += scoreFunding(artsyMod, scienceyMod)

        if ("Great Improvement" in cultureFrom || LANDMARK in cultureFrom) {
            val landmarks = ownedTiles.count { it.improvement == LANDMARK }
            score += 40 * greatImprovements + 50 * landmarks - (if (forSelf) 100 * era else 0)
        }
        return score
    }

    //region Inputs

    private val era by lazy { DiplomacyTurn.gameEraNumber(civ) }
    private val numWonders by lazy { civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().count { it.isWonder } } }
    private val numGreatWorks by lazy { civ.cities.sumOf { it.greatWorks.getNumWorks() } }
    private val greatImprovements by lazy {
        val improvements = gameInfo.ruleset.tileImprovements
        ownedTiles.count { tile -> tile.improvement?.let { improvements[it]?.isGreatImprovement() } == true }
    }
    private val diploVictoryEnabled by lazy { VictoryPursuitAi.isVictoryEnabled(civ, VictoryPursuit.Diplomacy) }
    private val cultureVictoryEnabled by lazy { VictoryPursuitAi.isVictoryEnabled(civ, VictoryPursuit.Culture) }
    private val scienceVictoryEnabled by lazy { VictoryPursuitAi.isVictoryEnabled(civ, VictoryPursuit.Science) }

    /** CvPlayer::GetNumEffectiveCities: cities, at least 1 (the cities not yet decided on are not tracked) */
    private fun numEffectiveCities() = max(1, civ.cities.size)

    /** The living major civilizations, the technologies each has and what the original's loops over the teams make of them */
    private class TechStats(val teams: Int, val total: Int, val most: Int, val ours: Int)

    private fun techStats(): TechStats {
        var teams = 0
        var total = 0
        var most = 0
        var ours = 0
        for (other in gameInfo.getAliveMajorCivs()) {
            teams++
            val techs = other.tech.researchedTechnologies.size
            if (techs <= 0) continue
            total += techs
            most = max(most, techs)
            if (other == civ) ours = techs
        }
        return TechStats(teams, total, most, ours)
    }

    /** CvPlayer::calculateProductionMight: the sum over the cities of their production without the excess food a settler turns into production */
    private fun productionMight(c: Civilization) = c.cities.sumOf { city ->
        val fromFood = city.cityStats.finalStatList[EXCESS_FOOD_TO_PRODUCTION]?.production ?: 0f
        (city.cityStats.currentCityStats.production - fromFood).toInt()
    }

    private fun routesFrom(a: Civilization, b: Civilization): Int = a.tradeRoutes.routes.count { it.getDestinationCiv() == b }

    private fun routesBetween(a: Civilization, b: Civilization): Int = routesFrom(a, b) + routesFrom(b, a)

    private fun isFriendOrAlly(a: Civilization, b: Civilization) = OpinionAi.isDoFAccepted(a, b) || LeagueAutomation.hasDefensivePact(a, b)

    /** The state of the coop war [partner] agreed to against [target] with [of] is at least being prepared (COOP_WAR_STATE_PREPARING) */
    private fun isPreparingCoopWar(of: Civilization, partner: Civilization, target: Civilization) =
        (CoopWarAi.state(of, partner, target) ?: CoopWarState.Rejected) >= CoopWarState.Soon

    private fun hasUniqueUnit(vararg baseUnits: String) =
        baseUnits.any { it in gameInfo.ruleset.units && civ.getEquivalentUnit(it).name != it }

    /** The other living majors with cities that we have met: the loops of the original over MAX_MAJOR_CIVS with these tests */
    private fun metMajors() = gameInfo.getAliveMajorCivs().filter { it != civ && it.cities.isNotEmpty() && civ.knows(it) }

    //endregion
    //region Effects

    /**
     * International projects (CvVotingClasses.cpp:12118-12442). Our production might against the highest: 80% of it can get gold (our share of winning
     * 100 / (the civilizations with more + 1)), 50% silver or a contribution of the share of the tier 2 threshold, 25% bronze or the tier 1 threshold.
     * The World's Fair: 30 per city and 2 per percent for gold, the turns for a policy (70 x the speed's culture percent / turns) for silver, 500 for
     * bronze, -400 for the proposer. International Games: by the lowest influence percent on others (10 to 50 when the culture victory is on), 200 for
     * silver, 100 per percent over 25 for bronze, -250 and 40 per percent under 25 for the proposer. The International Space Station: gold by our
     * technology over the mean (+10), silver 20 x that, bronze 150, -500 for the proposer, half without the space race. Another project: gold 2 per
     * percent, silver 150, bronze 100, -200 for the proposer.
     */
    private fun scoreProject(projectName: String, forSelf: Boolean): Int {
        val alive = gameInfo.getAliveMajorCivs()
        val ours = productionMight(civ)
        var higher = 0
        var highest = 0
        for (other in alive) {
            val might = productionMight(other)
            if (other != civ && might > ours) higher++
            highest = max(highest, might)
        }
        val mightPercent = ours * 100 / max(1, highest)
        val canGold = mightPercent >= 80
        val percentOfWinning = if (canGold) 100 / max(higher + 1, 1) else 0
        val constants = gameInfo.ruleset.modOptions.constants
        val cost = league.getProjectCostPerPlayer(projectName)
        val canSilver = mightPercent >= 50 || ours.toFloat() > constants.leagueProjectTier2Threshold * cost
        val canBronze = mightPercent >= 25 || ours.toFloat() > constants.leagueProjectTier1Threshold * cost
        var extra = 0
        when (projectName) {
            WORLDS_FAIR -> {
                if (canGold) extra += percentOfWinning * 30 * numEffectiveCities() / 100 + 2 * percentOfWinning
                if (canSilver) {
                    val culture = (civ.stats.statsForNextTurn.culture * 100).toInt()
                    val turnsForPolicy = civ.policies.getCultureNeededForNextPolicy() * 100 / max(1, culture)
                    extra += 70 * (gameInfo.speed.cultureCostModifier * 100).roundToInt() / max(1, turnsForPolicy)
                }
                if (canBronze) extra += 500
                if (forSelf) extra -= 400
            }
            INTERNATIONAL_GAMES -> {
                val lowest = VictoryProgressAi.lowestTourismInfluence(civ, rules)
                if (canGold) extra += if (cultureVictoryEnabled) percentOfWinning * (lowest - 15).coerceIn(10, 50) / 2 else 2 * percentOfWinning
                if (canSilver) extra += 200
                if (canBronze && cultureVictoryEnabled && lowest > 25) extra += 100 * min(lowest - 25, 50)
                if (forSelf) {
                    if (lowest <= 25) extra += 40 * max(lowest - 25, -25)
                    extra -= 250
                }
            }
            INTERNATIONAL_SPACE_STATION -> {
                val techs = techStats()
                val techPercent = max(0, techs.ours * techs.teams * 100 / max(1, techs.total) - 100)
                if (canGold) extra += percentOfWinning * (10 + techPercent)
                if (canSilver) extra += 20 * (10 + techPercent)
                if (canBronze) extra += 150
                if (forSelf) extra -= 500
                if (!scienceVictoryEnabled) extra /= 2
            }
            else -> {
                if (canGold) extra += 2 * percentOfWinning
                if (canSilver) extra += 150
                if (canBronze) extra += 100
                if (forSelf) extra -= 200
            }
        }
        return extra
    }

    /**
     * Embargo City-States (CvVotingClasses.cpp:12443-12499): -75 for each city-state we have a trade connection with, -100 per route we could no longer
     * fill (at most -1000) with the cities of the other civilizations not embargoed and our own as destinations, and +40 per era for the proposer.
     * The influence our policies give from trade routes with city-states has no counterpart.
     */
    private fun scoreEmbargoCityStates(forSelf: Boolean): Int {
        var partners = 0
        var civDestinations = 0
        for (other in gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
            if (other.isCityState) {
                if (routesBetween(civ, other) > 0) partners++
            } else if (!league.isTradeEmbargoed(civ, other)) civDestinations += other.cities.size
        }
        var extra = -75 * partners
        val deficit = civ.tradeRoutes.getNumSlots() - (civDestinations + civ.cities.size)
        if (deficit > 0) extra -= min(100 * deficit, 1000)
        if (forSelf) extra += 40 * era
        return extra
    }

    /**
     * Embargo (CvVotingClasses.cpp:12500-12563). On us: -1000 for the proposer's choice, and -100 per route to and -50 per opinion level above neutral of every other
     * major. On another major, for ourselves: its target value above average x100 and its warmonger threat x150 when our approach is below guarded,
     * +200 at war, -100 per opinion level above competitor, -75 per route to it. The value of our deals with them is not counted.
     */
    private fun scoreEmbargoPlayer(target: String?, forSelf: Boolean): Int {
        val victim = target?.let { gameInfo.getCivilizationOrNull(it) } ?: return 0
        val diplomacy = civ.ai.diplomacy
        var extra = 0
        if (victim == civ) {
            if (forSelf) extra -= 1000
            var tradeValue = 0
            for (other in gameInfo.getAliveMajorCivs()) {
                if (other == civ) continue
                tradeValue += routesFrom(civ, other) * 100
                val opinion = diplomacy.opinionOf(other)
                if (opinion > MajorCivOpinion.Neutral) extra -= (opinion.ordinal - MajorCivOpinion.Neutral.ordinal) * 50
            }
            extra -= tradeValue
        } else if (victim.isMajorCiv && forSelf) {
            if (diplomacy.approachTowards(victim) < MajorCivApproach.Guarded) {
                val value = WarStateAi.targetValue(civ, victim) ?: TargetValue.Average
                if (value > TargetValue.Average) extra += (value.ordinal - TargetValue.Average.ordinal) * 100
                extra += diplomacy.warmongerThreat(victim).ordinal * 150
            }
            if (civ.isAtWarWith(victim)) extra += 200
            extra -= (diplomacy.opinionOf(victim).ordinal - MajorCivOpinion.Competitor.ordinal) * 100
            extra -= routesFrom(civ, victim) * 75
        }
        return extra
    }

    /**
     * Ban Luxury (CvVotingClasses.cpp:12564-12597): -100 if we have the luxury (-150 more with an unhappy empire), -75 for each of our tiles with it,
     * +100 for the proposer. Monopolies have no counterpart.
     */
    private fun scoreBanLuxury(luxury: String?, forSelf: Boolean): Int {
        if (luxury == null) return 0
        var extra = 0
        if (civ.getResourceAmount(luxury) > 0) {
            extra -= 100
            if (civ.stats.happiness < 0) extra -= 150
        }
        extra -= 75 * ownedTiles.count { it.resource == luxury }
        if (forSelf) extra += 100
        return extra
    }

    /**
     * Standing Army Tax (CvVotingClasses.cpp:12598-12670), for a resolution that changes the unit maintenance by [factor] percent. A rise is also
     * what Vox Populi's Global Peace Accords is: the warmonger threat of the civilizations we have met (x150, at most 750) is for it, the wars
     * we do badly in (2 x the war state over stalemate - 1, x100, at most 500) and the targets we eye (the target value over average, x100, at
     * most 500) against. Then -half the percent our military might is above or below the mean of the civilizations we have met (at most 50) x [factor].
     * The target value we hold stands in for the raw one (without the reduction by boldness), which is not ported.
     */
    private fun scoreStandingArmyTax(factor: Int): Int {
        var extra = 0
        if (factor > 0) {
            var threat = 0
            var warStates = 0
            var warTargets = 0
            for (other in metMajors()) {
                threat += civ.ai.diplomacy.warmongerThreat(other).ordinal
                if (civ.isAtWarWith(other)) {
                    val state = WarStateAi.warState(civ, other)
                    if (state != null) warStates += 2 * (state.ordinal - WarState.Stalemate.ordinal) - 1
                } else {
                    val value = WarStateAi.targetValue(civ, other) ?: TargetValue.Average
                    if (value > TargetValue.Average) warTargets += value.ordinal - TargetValue.Average.ordinal
                }
            }
            extra += min(threat * 150, 750)
            extra -= min(warStates * 100, 500)
            extra -= min(warTargets * 100, 500)
        }
        var civs = 0
        var total = 0L
        var ours = 0L
        for (other in gameInfo.getAliveMajorCivs()) {
            if (other.cities.isEmpty() || (other != civ && !civ.knows(other))) continue
            civs++
            val might = other.ai.militaryMight()
            if (might <= 0) continue
            total += might
            if (other == civ) ours = might.toLong()
        }
        val mightPercent = (ours * civs * 100 / max(1L, total) - 100).toInt()
        return extra - mightPercent.coerceIn(-50, 50) * factor / 2
    }

    /**
     * Scholars in Residence (CvVotingClasses.cpp:12841-12891): -60 per percent (at most 10) our technologies are above the mean of the living majors, and 60
     * more for each of the technologies the best has more than we (0 to 10) over two; more by 10% for each city-state ally when it is a gain.
     */
    private fun scoreScholars(): Int {
        val techs = techStats()
        val techPercent = (techs.ours * techs.teams * 100 / max(1, techs.total) - 100).coerceIn(-10, 10)
        var extra = -techPercent * 60 + ((techs.most - techs.ours).coerceIn(0, 10) - 2) * 60
        if (extra > 0) extra = extra * (100 + gameInfo.getAliveCityStates().count { it.allyCiv == civ } * 10) / 100
        return extra
    }

    /**
     * Nuclear Non-Proliferation (CvVotingClasses.cpp:12924-12962): (8 - our nuclear flavor) x 20 for each step our approach to a major we have a strong
     * land dispute with is below deceptive. Nuclear Gandhi (-1000) has no counterpart.
     */
    private fun scoreNuclearNonProliferation(): Int {
        var dislikedNeighbours = 0
        for (other in gameInfo.getAliveMajorCivs()) {
            if (civ.ai.diplomacy.landDisputeLevel(other) < DisputeLevel.Strong) continue
            val approach = civ.ai.diplomacy.approachTowards(other)
            if (approach < MajorCivApproach.Deceptive) dislikedNeighbours += MajorCivApproach.Deceptive.ordinal - approach.ordinal
        }
        val nukeFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Nuke).coerceIn(0, 20)
        return (8 - nukeFlavor) * dislikedNeighbours * 20
    }

    /**
     * World Religion (CvVotingClasses.cpp:12963-13051), for the proposer's [religion]. If we founded one: the other majors with it in most cities x150 (for the one we
     * founded, against another when we propose; halved without the diplomatic victory), its holy city's tourism / 50 (a tenth without the culture victory)
     * and 10 per city that follows it likewise; and +150 (-150 for ourselves) if the chosen religion is that of most of our cities.
     */
    private fun scoreWorldReligion(religion: String?, forSelf: Boolean): Int {
        val target = religion?.let { gameInfo.religions[it] } ?: return 0
        var extra = 0
        val founded = civ.religionManager.religion?.takeIf { it.isMajorReligion() && it.foundingCiv == civ }
        if (founded != null) {
            val foundedTarget = founded.name == target.name
            val possibleExtraVotes = gameInfo.getAliveMajorCivs().count { it != civ && it.religionManager.isMajorityReligionForCiv(founded) }
            if (foundedTarget) extra += 150 * possibleExtraVotes else if (forSelf) extra -= 150 * possibleExtraVotes
            if (!diploVictoryEnabled) extra /= 2
            val holyCity = gameInfo.getCities().firstOrNull { it.religion.religionThisIsTheHolyCityOf == founded.name }
            var tourism = if (holyCity == null) 0 else min(50000, holyCity.civ.culture.getCityTourism(holyCity) * 100)
            if (!cultureVictoryEnabled) tourism /= 10
            if (foundedTarget) extra += tourism / 50 else if (forSelf) extra -= tourism / 50
            val following = gameInfo.getCities().count { it.religion.getMajorityReligionName() == founded.name } * 10
            if (foundedTarget) extra += following else if (forSelf) extra -= following
        }
        if (civ.religionManager.isMajorityReligionForCiv(target)) extra += 150 else if (forSelf) extra -= 150
        return extra
    }

    /** What the delegates we could have are worth towards the diplomatic victory (CvVotingClasses.cpp:13061-13093): 10000 at the votes needed, else by the percent of them */
    private fun scoreVoteRatio(forSelf: Boolean): Int {
        val ratio = league.getDelegates(civ) * 100 / max(1, league.getVotesNeededForWorldLeader())
        if (ratio >= 100) return 10000
        var extra = 0
        if (ratio >= 80) extra += 75 * (ratio - 80)
        if (ratio >= 60) extra += 100 * (ratio - 60)
        if (ratio >= 40) extra += 75 * (ratio - 40) else if (forSelf) extra -= 25 * (40 - ratio)
        return extra
    }

    /**
     * World Ideology (CvVotingClasses.cpp:13052-13145), for the proposer's [ideology]: the votes ratio when the diplomatic victory is on, and, with an
     * ideology of our own, the delegates it could give (1 + the majors with it, half without the diplomatic victory) x200 and 100 for every level
     * our influence on a major of another ideology is above theirs on us, for our ideology and against another one when we propose it.
     */
    private fun scoreWorldIdeology(ideology: String?, forSelf: Boolean): Int {
        var extra = 0
        if (diploVictoryEnabled) extra += scoreVoteRatio(forSelf)
        val ours = civ.getIdeology() ?: return extra
        var pressure = 0
        var potentialExtraVotes = 1
        for (other in gameInfo.getAliveMajorCivs()) {
            if (other == civ) continue
            val theirs = other.getIdeology() ?: continue
            if (theirs == ours) potentialExtraVotes++
            else {
                val difference = (civ.culture.getInfluenceLevel(other) ?: InfluenceLevel.Unknown).ordinal -
                    (other.culture.getInfluenceLevel(civ) ?: InfluenceLevel.Unknown).ordinal
                if (difference > 0) pressure += difference
            }
        }
        if (!diploVictoryEnabled) potentialExtraVotes = (potentialExtraVotes + 1) / 2
        val worth = potentialExtraVotes * 200 + pressure * 100
        if (ours.name == ideology) extra += worth else if (forSelf) extra -= worth
        return extra
    }

    /**
     * Arts Funding and Sciences Funding (CvVotingClasses.cpp:13146-13246): the great person rate change of the sciencey persons ([scienceMod], percent)
     * x 20 with a unique unit among them, x half our tiles with a great improvement, that of the artsy ([artsMod]) x 20 with a unique unit among them
     * and x half our great works. The league bonus for the poorer half of the civilizations (VP's score ranks) has no counterpart.
     */
    private fun scoreFunding(artsMod: Int, scienceMod: Int): Int {
        var extra = 0
        if (hasUniqueUnit("Great Scientist", "Great Engineer", "Great Merchant")) extra += scienceMod * 20
        extra += greatImprovements * scienceMod / 2
        if (hasUniqueUnit("Great Writer", "Great Artist", "Great Musician")) extra += artsMod * 20
        extra += numGreatWorks * artsMod / 2
        return extra
    }

    /**
     * Sanctions we propose (CvVotingClasses.cpp:13684-13760, against [targetId]): never against ourselves (-100000), not against a friend, ally, liberator
     * or partner of a coop war we are in (-10000, when we do not fear them). Else for each other major with cities: a friend of ours (not feared, or in a
     * coop war against the target) +100 if in a coop war against the target, -50 if it likes the target, +50 if it dislikes it (denounced either way or at
     * war), all by our work-with willingness; an enemy of ours (denounced either way, at war or our biggest competitor) that likes the target +50,
     * and -50 when it dislikes it unless the target is our biggest competitor and it is a worse enemy (or the biggest competitor), by our work-against
     * willingness. The prime league ally and competitor, and teams, are not ported.
     */
    private fun sanctionProposalScore(targetId: String): Int {
        val target = gameInfo.getCivilizationOrNull(targetId) ?: return 0
        if (target == civ) return -100000
        val diplomacy = civ.ai.diplomacy
        fun fears(other: Civilization) = diplomacy.approachTowards(other) <= MajorCivApproach.Afraid
        fun isLiberator(of: Civilization, other: Civilization) = WarSanityAi.isLiberator(of, other, false)
        fun dislikes(other: Civilization, victim: Civilization) = OpinionAi.isDenouncedPlayer(other, victim) ||
            OpinionAi.isDenouncedPlayer(victim, other) || other.isAtWarWith(victim)
        if (!fears(target) && (isFriendOrAlly(civ, target) || isLiberator(civ, target) ||
                (CoopWarAi.globalState(civ, target) ?: CoopWarState.Rejected) >= CoopWarState.Soon)) return -10000

        var score = 0
        val workWith = civ.ai.diplomacyValue(DiplomacyPersonality.WorkWithWillingness)
        val workAgainst = civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)
        for (other in gameInfo.getAliveMajorCivs()) {
            if (other.cities.isEmpty() || other == civ || other == target) continue
            val coopWar = isPreparingCoopWar(civ, other, target)
            val likesTarget = isFriendOrAlly(other, target) || isLiberator(other, target)
            if (!fears(other) && (isFriendOrAlly(civ, other) || isLiberator(civ, other) || coopWar)) {
                if (coopWar) score += OpinionAi.adjustConditional(100, workWith)
                else if (likesTarget) score -= OpinionAi.adjustConditional(50, workWith)
                else if (dislikes(other, target)) score += OpinionAi.adjustConditional(50, workWith)
            } else if (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ) || civ.isAtWarWith(other) ||
                diplomacy.biggestCompetitor == other.civID) {
                if (likesTarget) score += OpinionAi.adjustConditional(50, workAgainst)
                else if (dislikes(other, target) && diplomacy.biggestCompetitor != target.civID &&
                    (diplomacy.biggestCompetitor == other.civID || diplomacy.memoryOf(other).opinionWeight > diplomacy.memoryOf(target).opinionWeight))
                    score -= OpinionAi.adjustConditional(50, workAgainst)
            }
        }
        return score
    }

    //endregion
}
