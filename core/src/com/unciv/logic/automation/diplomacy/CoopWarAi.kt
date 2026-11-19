package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.MilitaryTargets
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  Cooperative wars of CvDiplomacyAI (Vox Populi's decisions, Release-5.4.8; D7): asking a partner to join a war (DoCoopWarStatement,
 *  DoTestCoopWarDesire with GetCoopWarDesireScore), the partner's answer (RespondToCoopWarRequest), the "soon" counter that triggers the war
 *  10 turns later (DoUpdateCoopWarStates) and the lock-in (IsLockedIntoCoopWar). The state is kept by the partner:
 *  `civ.ai.diplomacy.memoryOf(partner).coopWars[target]`; VP's preparing is [CoopWarState.Soon] and its ongoing [CoopWarState.Accepted].
 */
object CoopWarAi {

    fun state(civ: Civilization, partner: Civilization, target: Civilization): CoopWarState? =
        civ.ai.diplomacy.memories[partner.civID]?.coopWars?.get(target.civID)?.state

    fun counter(civ: Civilization, partner: Civilization, target: Civilization): Int =
        civ.ai.diplomacy.memories[partner.civID]?.coopWars?.get(target.civID)?.counter ?: -1

    fun entry(civ: Civilization, partner: Civilization, target: Civilization): CoopWarEntry =
        civ.ai.diplomacy.memoryOf(partner).coopWars.getOrPut(target.civID) { CoopWarEntry() }

    /** GetGlobalCoopWarAcceptedState: the furthest any partner has agreed to go against [target] */
    fun globalState(civ: Civilization, target: Civilization): CoopWarState? {
        var best: CoopWarState? = null
        for ((_, memory) in civ.ai.diplomacy.memories) {
            val state = memory.coopWars[target.civID]?.state ?: continue
            if (best == null || state > best) best = state
        }
        return best
    }

    /** GetGlobalCoopWarCounter: the most recent agreement against [target] */
    private fun globalCounter(civ: Civilization, target: Civilization): Int {
        var best = Int.MAX_VALUE
        for ((_, memory) in civ.ai.diplomacy.memories) {
            val counter = memory.coopWars[target.civID]?.counter ?: continue
            if (counter in 0 until best) best = counter
        }
        return best
    }

    /** CvDiplomacyAI::IsLockedIntoCoopWar: agreed (now or soon) with someone against [target] within COOP_WAR_LOCKED_TURNS */
    fun isLockedIntoCoopWar(civ: Civilization, target: Civilization, rules: AiRuleset): Boolean {
        val state = globalState(civ, target)
        if (state != CoopWarState.Accepted && state != CoopWarState.Soon) return false
        return globalCounter(civ, target) <= rules.define(AiDefine.COOP_WAR_LOCKED_TURNS)
    }

    private fun hasDefensivePact(civ: Civilization, other: Civilization) =
        civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    private fun canCrossOcean(civ: Civilization) = civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean

    /**
     *  CvDiplomacyAI::IsValidCoopWarTarget: [civ] and [target] are majors with cities, [civ] has met it, has no defensive pact or friendship
     *  with it and may declare war on it (or is at war with it already, when [atWarException]).
     */
    fun isValidCoopWarTarget(civ: Civilization, target: Civilization, atWarException: Boolean): Boolean {
        for (major in listOf(civ, target)) if (!major.isMajorCiv || !major.isAlive() || major.cities.isEmpty()) return false
        if (!civ.knows(target) || hasDefensivePact(civ, target) || OpinionAi.isDoFAccepted(civ, target)) return false
        return civ.getDiplomacyManager(target)?.canDeclareWar() == true || (atWarException && civ.isAtWarWith(target))
    }

    /**
     *  CvDiplomacyAI::CanRequestCoopWar: [civ] has a friendship with [ally] (there are no teams), no coop war with it against [target] is
     *  being prepared, and [target] is a valid target for both.
     */
    fun canRequestCoopWar(civ: Civilization, ally: Civilization, target: Civilization): Boolean =
        OpinionAi.isDoFAccepted(civ, ally) && state(civ, ally, target) != CoopWarState.Soon &&
            isValidCoopWarTarget(civ, target, false) && isValidCoopWarTarget(ally, target, false)

    /**
     *  CvDiplomacyAI::GetCoopWarDesireScore: how much [civ] wants to go to war with [ally] against [target], 0 for no interest. [allyRequest]
     *  is the ally having asked (a defensive pact of the ally with the target then does not matter). In order:
     *  - Nothing unless the target is a potential war target ([DiplomacyAi.potentialWarTargets]), we want new wars (or the target is
     *    endgame-aggressive or holds our capital), the ally is not about to win, both of us have an attack target on it (an AI ally), our war
     *    sanity allows it, the ally has no friendship with the target and did not break one with us within DOF_BROKEN_BACKSTAB_TIMER turns, the ally
     *    is no backstabber, and no peace treaty with the target is recent.
     *  - The ally: nothing for a war, hostile or guarded approach, an enemy or worse opinion; the approach (deceptive -5, neutral -2, afraid
     *    +2, friendly +5), opinion (competitor -5, neutral -2, favorable +2, friend +5, ally +10), friendship level (friends +2, allies +5,
     *    battle brothers +10), a defensive pact +5, having resurrected us +5.
     *  - The target: nothing for a friendly approach; the approach (war +1000, hostile +10, deceptive or guarded +5, afraid +2, neutral -5), opinion
     *    (unforgivable +25, enemy +10, competitor +5, neutral -2, favorable -5, friend -10), friendship level (untrustworthy +5, new +2, friends -5, allies -15);
     *    a coop war against it already planned +1000. Badness (see below): its opinion is ally, our friendship is battle brothers, it
     *    liberated us or recently liberated a city while we lost cities, we are in another coop war.
     *  - Our proximity to the target and the ally's (neighbors and close are worth 10 and 5, 5 and 2 for us across oceans; far -25 when either
     *    crosses oceans; else -50 across, -500 on land), a competitor (the biggest +25, another +10, none -10), a warmonger +10, going for or close to
     *    world conquest boldness x 2, diplomatic victory -DoF willingness (-25 more when close), endgame aggression +25 (+25 when close to a
     *    victory), a reckless expander +10, a wonder spammer +5, its warmonger threat (-2 to +10), the land dispute (-5 to +15), victory dispute and
     *    block levels (each -2: not ported, always none), a preferred attack target of ours +10 else -10.
     *  - The raw military strength of the target as the weaker of both: if the target is not an easy target and at least average for us, the weaker
     *    one gives +20 (pathetic) to -30 (immense), +15 when the ally has an easy target and a preferred attack target, and our common foe score with the
     *    ally; else -30 (pathetic), -20, -10 (poor), -15 more when it is an easy target and we have a preferred attack target.
     *  - The danger of the target to us (its conquered capitals, twice, as the original counts both the majors conquered and the capitals held,
     *    plus its proximity to us less 3), less its strength against us less 3 (+2 when easy with an attack target), plus the ally's target value less 3
     *    (+2 likewise), the common foe score if still above 0; times 5, 4, 2 or 1 by the proximities.
     *  - Nothing at 0 or less. Scaled by the target value (a competitor, an early game competitor, or an easy target with an attack target: 40% impossible to 300% cakewalk;
     *    else 75% average to 200%, and badness for a hard one), by the economic value of the closest of our cities to it (10 to 200 percent of the median, x2 for
     *    an original major capital, x3 or x4 when going for or close to world conquest), and 0 for badness unless we have an offensive operation against it or
     *    avoid exchanges with it.
     *  Not ported, as they have no input: the coop war agreement score (counted as 0), the victory dispute and block levels, vassals and teams.
     *  The OPINION_WEIGHT_COMMON_FOE_MAX / 25 divisors are 1, as the source takes the larger of that (negative) and 1.
     */
    fun coopWarDesireScore(civ: Civilization, ally: Civilization, target: Civilization, allyRequest: Boolean, rules: AiRuleset): Int {
        val ai = civ.ai.diplomacy
        if (target.civID !in ai.potentialWarTargets) return 0
        val endgameAgainstTarget = VictoryProgressAi.isEndgameAggressiveTo(civ, target, rules)
        if (WarStateAi.isNoNewWars(civ) && !endgameAgainstTarget && !OpinionAi.isCapitalCapturedBy(civ, target)) return 0
        if (VictoryProgressAi.isEndgameAggressiveTo(civ, ally, rules)) return 0
        if (!civ.ai.military.havePossibleAttackTarget(target)) return 0
        if (!ally.isHuman() && !ally.ai.military.havePossibleAttackTarget(target)) return 0
        if (!WarSanityAi.isWarSane(civ, target)) return 0
        if (OpinionAi.isDoFAccepted(ally, target)) return 0
        if (FriendshipPlanningAi.isDoFBrokenByUs(ally, target, rules.define(AiDefine.DOF_BROKEN_BACKSTAB_TIMER))) return 0
        if (!allyRequest && hasDefensivePact(ally, target)) return 0
        if (OpinionAi.isUntrustworthyFriend(ally)) return 0
        if (WarTargetAi.isRecentPeaceTreaty(civ, target, rules)) return 0

        var score = 0
        var badness = false
        score += when (ai.approachTowards(ally)) {
            MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Guarded -> return 0
            MajorCivApproach.Deceptive -> -5
            MajorCivApproach.Neutral -> -2
            MajorCivApproach.Afraid -> 2
            MajorCivApproach.Friendly -> 5
        }
        score += when (ai.opinionOf(ally)) {
            MajorCivOpinion.Unforgivable, MajorCivOpinion.Enemy -> return 0
            MajorCivOpinion.Competitor -> -5
            MajorCivOpinion.Neutral -> -2
            MajorCivOpinion.Favorable -> 2
            MajorCivOpinion.Friend -> 5
            MajorCivOpinion.Ally -> 10
        }
        score += when (ai.memoryOf(ally).doFLevel) {
            DoFLevel.Untrustworthy, DoFLevel.New -> 0
            DoFLevel.Friends -> 2
            DoFLevel.Allies -> 5
            DoFLevel.BattleBrothers -> 10
        }
        if (hasDefensivePact(civ, ally)) score += 5
        if (ai.memoryOf(ally).resurrectedUs) score += 5

        score += when (ai.approachTowards(target)) {
            MajorCivApproach.War -> 1000
            MajorCivApproach.Hostile -> 10
            MajorCivApproach.Deceptive, MajorCivApproach.Guarded -> 5
            MajorCivApproach.Afraid -> 2
            MajorCivApproach.Neutral -> -5
            MajorCivApproach.Friendly -> return 0
        }
        score += when (ai.opinionOf(target)) {
            MajorCivOpinion.Unforgivable -> 25
            MajorCivOpinion.Enemy -> 10
            MajorCivOpinion.Competitor -> 5
            MajorCivOpinion.Neutral -> -2
            MajorCivOpinion.Favorable -> -5
            MajorCivOpinion.Friend -> -10
            MajorCivOpinion.Ally -> { badness = true; 0 }
        }
        score += when (ai.memoryOf(target).doFLevel) {
            DoFLevel.Untrustworthy -> 5
            DoFLevel.New -> 2
            DoFLevel.Friends -> -5
            DoFLevel.Allies -> -15
            DoFLevel.BattleBrothers -> { badness = true; 0 }
        }
        if (WarSanityAi.isLiberator(civ, target, onlyMajorCities = true) ||
            (WarSanityAi.isCityRecentlyLiberatedBy(civ, target) && !endgameAgainstTarget && WarSanityAi.citiesLost(civ) > 0)) badness = true
        if (globalState(civ, target).let { it != null && it >= CoopWarState.Soon }) score += 1000
        // Planning a coop war against anyone already
        if (civ.gameInfo.civilizations.any { it.isMajorCiv && globalState(civ, it).let { state -> state != null && state >= CoopWarState.Soon } }) badness = true

        val ours = DiplomacyTurn.proximity(civ, target)
        score += if (canCrossOcean(civ)) when (ours) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 5
            DiplomacyTurn.PROXIMITY_CLOSE -> 2
            DiplomacyTurn.PROXIMITY_FAR -> -25
            else -> -50
        } else when (ours) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 10
            DiplomacyTurn.PROXIMITY_CLOSE -> 5
            else -> -500
        }
        val theirs = DiplomacyTurn.proximity(ally, target)
        score += if (canCrossOcean(ally)) when (theirs) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 10
            DiplomacyTurn.PROXIMITY_CLOSE -> 5
            DiplomacyTurn.PROXIMITY_FAR -> -25
            else -> -50
        } else when (theirs) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 10
            DiplomacyTurn.PROXIMITY_CLOSE -> 5
            else -> -500
        }

        val competitor = CompetitorAi.biggestCompetitor(civ) == target.civID
        val majorCompetitor = CompetitorAi.isMajorCompetitor(civ, target) || CompetitorAi.isEarlyGameCompetitor(civ, target, rules)
        score += if (competitor) 25 else if (majorCompetitor) 10 else -10
        if (civ.ai.hasTraitCategory(TraitCategory.Warmonger)) score += 10
        if (civ.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules))
            score += civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) * 2
        if (civ.ai.isGoingForDiploVictory()) {
            score -= civ.ai.diplomacyValue(DiplomacyPersonality.DoFWillingness)
            if (VictoryProgressAi.isCloseToDiploVictory(civ, rules)) score -= 25
        }
        if (endgameAgainstTarget) {
            score += 25
            if (VictoryProgressAi.isCloseToAnyVictoryCondition(civ, rules)) score += 25
        }
        if (WarStateAi.isPlayerRecklessExpander(civ, target)) score += 10
        if (ai.memoryOf(target).wonderSpammer) score += 5
        score += when (ai.warmongerThreat(target)) {
            AiThreat.None, AiThreat.Minor -> -2
            AiThreat.Major -> 2
            AiThreat.Severe -> 5
            AiThreat.Critical -> 10
        }
        score += when (ai.landDisputeLevel(target)) {
            DisputeLevel.None -> -5
            DisputeLevel.Weak -> 5
            DisputeLevel.Strong -> 10
            DisputeLevel.Fierce -> 15
        }
        score -= 2 // the victory dispute level, not ported: none
        score -= 2 // the victory block level, likewise

        val weHaveGoodTarget = civ.ai.military.havePreferredAttackTarget(target)
        val theyHaveGoodTarget = ally.isHuman() || ally.ai.military.havePreferredAttackTarget(target)
        score += if (weHaveGoodTarget) 10 else -10

        // Strengths as VP's arrays hold them: -1 is no strength assessed yet, which no case of the tables below matches
        val ourStrength = WarStateAi.rawMilitaryStrengthComparedToUs(civ, target)?.ordinal ?: -1
        val allyStrength = WarStateAi.rawMilitaryStrengthComparedToUs(ally, target)?.ordinal ?: -1
        val easy = WarStateAi.isEasyTarget(civ, target)
        val strongerOrEqual = !easy && ourStrength >= StrengthLevel.Average.ordinal
        val lowest = minOf(ourStrength, allyStrength)
        // GetCommonFoeScore / max(OPINION_WEIGHT_COMMON_FOE_MAX / 25, 1) and / 5: the maximum is negative, so both divisors are 1
        val commonFoeScore = OpinionAi.commonFoeScore(civ, ally, rules)
        val commonFoeMaximum = rules.define(AiDefine.OPINION_WEIGHT_COMMON_FOE_MAX)
        val commonFoe = commonFoeScore / maxOf(commonFoeMaximum / 25, 1)
        if (!strongerOrEqual) {
            score += when (lowest) {
                StrengthLevel.Pathetic.ordinal -> -30
                StrengthLevel.Weak.ordinal -> -20
                StrengthLevel.Poor.ordinal -> -10
                else -> 0
            }
            if (easy && weHaveGoodTarget) score -= 15
        } else {
            score += when (lowest) {
                StrengthLevel.Pathetic.ordinal -> 20
                StrengthLevel.Weak.ordinal -> 10
                StrengthLevel.Poor.ordinal -> 5
                StrengthLevel.Average.ordinal -> -5
                StrengthLevel.Strong.ordinal -> -10
                StrengthLevel.Powerful.ordinal -> -20
                StrengthLevel.Immense.ordinal -> -30
                else -> 0
            }
            if (WarStateAi.isEasyTarget(ally, target) && theyHaveGoodTarget) score += 15
            score += commonFoe
        }

        // Are we in danger of getting conquered by the target?
        var danger = 2 * VictoryProgressAi.capitalsConquered(target) + DiplomacyTurn.proximity(target, civ) - DiplomacyTurn.PROXIMITY_NEIGHBORS
        if (danger > 0) {
            danger -= (WarStateAi.militaryStrengthComparedToUs(civ, target)?.ordinal ?: -1) - StrengthLevel.Average.ordinal
            if (easy && weHaveGoodTarget) danger += 2
            danger += (WarStateAi.targetValue(ally, target)?.ordinal ?: -1) - TargetValue.Average.ordinal
            if (WarStateAi.isEasyTarget(ally, target) && theyHaveGoodTarget) danger += 2
            if (danger > 0) danger += commonFoeScore / maxOf(commonFoeMaximum / 5, 1)
            val targetToUs = DiplomacyTurn.proximity(target, civ)
            val allyToTarget = DiplomacyTurn.proximity(ally, target)
            val closeBoth = targetToUs >= DiplomacyTurn.PROXIMITY_CLOSE && allyToTarget >= DiplomacyTurn.PROXIMITY_CLOSE
            if (targetToUs == DiplomacyTurn.PROXIMITY_NEIGHBORS && allyToTarget >= DiplomacyTurn.PROXIMITY_CLOSE) score += danger * 5
            else if (closeBoth) score += danger * 4
            else if (targetToUs >= DiplomacyTurn.PROXIMITY_CLOSE || allyToTarget >= DiplomacyTurn.PROXIMITY_CLOSE) {
                if (canCrossOcean(civ) || canCrossOcean(ally)) score += danger * 2
            } else if (targetToUs == DiplomacyTurn.PROXIMITY_FAR && allyToTarget == DiplomacyTurn.PROXIMITY_FAR) {
                if (canCrossOcean(civ)) score += danger
            }
        }
        if (score <= 0) return 0

        // Scale by the target value
        val value = WarStateAi.targetValue(civ, target)
        if (majorCompetitor || competitor || (weHaveGoodTarget && easy)) {
            score *= when (value) {
                TargetValue.Impossible -> 40
                TargetValue.Bad -> 60
                TargetValue.Difficult -> 80
                TargetValue.Average -> 100
                TargetValue.Favorable -> 150
                TargetValue.Soft -> 200
                TargetValue.Cakewalk -> 300
                null -> 1
            }
        } else when (value) {
            TargetValue.Impossible, TargetValue.Bad, TargetValue.Difficult -> badness = true
            TargetValue.Average -> score *= 75
            TargetValue.Favorable -> score *= 125
            TargetValue.Soft -> score *= 150
            TargetValue.Cakewalk -> score *= 200
            null -> score *= 1
        }

        // Scale by the economic value of the closest of our cities to it, as a percentage of the median city
        val closest = MilitaryTargets.closestCityToUsByPlots(civ, target) ?: return 0
        val median = WarmongerAi.medianEconomicValue(civ.gameInfo)
        val localValue = maxOf(AttackTargetsAi.economicValue(closest, target), AttackTargetsAi.economicValue(closest, civ))
        var economic = (localValue * 100 / maxOf(1, median)).coerceIn(10, 200)
        if (LiberationAi.isOriginalMajorCapital(closest)) economic *= when {
            VictoryProgressAi.isCloseToWorldConquest(civ, rules) -> 4
            civ.ai.isGoingForWorldConquest() -> 3
            else -> 2
        }
        score = score * minOf(economic, 200) / 10000

        if (badness && !civ.ai.operations.hasAnyOffensiveOperationsAgainst(target) && !WarAlliesAi.avoidExchangesWithPlayer(civ, target, warOnly = true))
            return 0
        return score
    }

    /**
     *  CvDiplomacyAI::DoTestCoopWarDesire: the best target to ask [partner] to fight with us, null if none. Only a friend, when we want
     *  new wars, whose approach is better than guarded and opinion better than enemy and who is no backstabber; a target not asked about within
     *  30 turns of being asked, that [canRequestCoopWar] allows, that we are upset enough with (our biggest competitor, or an approach of deceptive
     *  or worse), and whose [coopWarDesireScore] is the best above COOP_WAR_DESIRE_THRESHOLD.
     */
    fun coopWarTarget(civ: Civilization, partner: Civilization, rules: AiRuleset): Civilization? {
        val ai = civ.ai.diplomacy
        if (!OpinionAi.isDoFAccepted(civ, partner) || WarStateAi.isNoNewWars(civ)) return null
        if (ai.approachTowards(partner) <= MajorCivApproach.Guarded || ai.opinionOf(partner) <= MajorCivOpinion.Enemy) return null
        if (OpinionAi.isUntrustworthyFriend(partner)) return null
        var best: Civilization? = null
        var bestScore = rules.define(AiDefine.COOP_WAR_DESIRE_THRESHOLD)
        for (target in civ.gameInfo.civilizations) {
            if (!target.isMajorCiv || target == partner) continue
            // 30 turn buffer after a rejection
            if (counter(civ, partner, target) in 0 until 30) continue
            if (!canRequestCoopWar(civ, partner, target)) continue
            if (CompetitorAi.biggestCompetitor(civ) != target.civID && ai.approachTowards(target) > MajorCivApproach.Deceptive) continue
            val score = coopWarDesireScore(civ, partner, target, allyRequest = false, rules)
            if (score > bestScore) {
                bestScore = score
                best = target
            }
        }
        return best
    }

    /**
     *  CvDiplomacyAI::RespondToCoopWarRequest to [asker]'s request that [civ] fight [target] with it (the AI asking an AI): rejected unless
     *  [asker] may ask ([canRequestCoopWar]) and [coopWarDesireScore] (for an ally request) reaches COOP_WAR_DESIRE_THRESHOLD; then the war is
     *  [CoopWarState.Accepted] at once (VP's ongoing) when we are close to the target (neighbors, or close when we cross oceans) and bold
     *  (boldness above 7, a warmonger, or competing for a world conquest) against a target value of favorable or better, or the target is an easy
     *  target with a preferred attack target, or endgame-aggressive and either of favorable or better, easy, or with a preferred attack target;
     *  else [CoopWarState.Soon] (VP's preparing). Not ported: VP's warning of the target when we refuse an agreement we find unacceptable
     *  (IsCoopWarRequestUnacceptable, DoWarnCoopWarTarget), and teammates.
     */
    fun willingToAgree(civ: Civilization, asker: Civilization, target: Civilization, rules: AiRuleset): CoopWarState {
        if (!canRequestCoopWar(asker, civ, target)) return CoopWarState.Rejected
        if (coopWarDesireScore(civ, asker, target, allyRequest = true, rules) < rules.define(AiDefine.COOP_WAR_DESIRE_THRESHOLD))
            return CoopWarState.Rejected
        val ai = civ.ai
        val bold = ai.diplomacyValue(DiplomacyPersonality.Boldness) > 7 || ai.hasTraitCategory(TraitCategory.Warmonger) ||
            (VictoryProgressAi.isCompetingForVictory(civ) && ai.isGoingForWorldConquest())
        val proximity = DiplomacyTurn.proximity(civ, target)
        val close = if (canCrossOcean(civ)) proximity >= DiplomacyTurn.PROXIMITY_CLOSE else proximity == DiplomacyTurn.PROXIMITY_NEIGHBORS
        val favorable = WarStateAi.targetValue(civ, target).let { it != null && it >= TargetValue.Favorable }
        val easyTarget = WarStateAi.isEasyTarget(civ, target)
        val goodTarget = ai.military.havePreferredAttackTarget(target)
        val ongoing = (bold && close && favorable) || (close && easyTarget && goodTarget) ||
            (close && VictoryProgressAi.isEndgameAggressiveTo(civ, target, rules) && (favorable || easyTarget || goodTarget))
        return if (ongoing) CoopWarState.Accepted else CoopWarState.Soon
    }

    /**
     *  DoSendStatementToPlayer(COOP_WAR_REQUEST) with an AI partner: [civ] asks [partner] to fight [target] with it. Accepted: both declare war
     *  now and are locked in for COOP_WAR_LOCKED_LENGTH turns; Soon: war in COOP_WAR_SOON_COUNTER turns (see [doCoopWarCounters]).
     */
    fun requestCoopWar(civ: Civilization, partner: Civilization, target: Civilization, rules: AiRuleset) {
        entry(civ, partner, target).counter = 0
        val partnerEntry = entry(partner, civ, target)
        partnerEntry.counter = 0
        val accepted = willingToAgree(partner, civ, target, rules)
        partnerEntry.state = accepted
        if (accepted != CoopWarState.Rejected) {
            // RespondToCoopWarRequest: each gives the other credit for the help (recent assist 300) and both plan war against the target
            partner.ai.diplomacy.changeRecentAssistValue(civ, 300)
            civ.ai.diplomacy.changeRecentAssistValue(partner, 300)
            WarTargetAi.setApproach(partner, target, MajorCivApproach.War)
            WarTargetAi.setApproach(civ, target, MajorCivApproach.War)
        }
        if (accepted == CoopWarState.Accepted) {
            WarAi.declareWar(civ, target)
            MilitaryOperations.requestBasicAttack(civ, target, 1)
            WarAi.declareWar(partner, target)
            MilitaryOperations.requestBasicAttack(partner, target, 1)
            val locked = rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH)
            civ.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += locked
            partner.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += locked
        }
        if (accepted != CoopWarState.Rejected) entry(civ, partner, target).state = accepted
    }

    /** What a human answers to an AI's request to join a war ([AlertType.CoopWarRequest], [AlertType.CoopWarNow]) */
    enum class HumanAnswer { Agree, Soon, Refuse }

    /**
     *  DoSendStatementToPlayer(COOP_WAR_REQUEST / COOP_WAR_TIME) with a human partner: the request is an alert, once at a time. A request
     *  [now] is the second one, to a human who said "soon".
     */
    fun askHuman(civ: Civilization, human: Civilization, target: Civilization, now: Boolean) {
        val type = if (now) AlertType.CoopWarNow else AlertType.CoopWarRequest
        val value = PopupAlert.pairValue(civ.civID, target.civID)
        if (human.popupAlerts.none { it.type == type && it.value == value }) human.popupAlerts.add(PopupAlert(type, value))
    }

    /**
     *  FROM_UI_DIPLO_EVENT_COOP_WAR_RESPONSE and COOP_WAR_NOW_RESPONSE: [human] answered [civ]'s request to fight [target]. The counter
     *  restarts whatever the answer (so a refusal is not asked again for 30 turns). Agreeing makes both declare war and locks them in for
     *  COOP_WAR_LOCKED_LENGTH turns; "soon" is the agreement the counter turns into a second request after COOP_WAR_SOON_COUNTER turns,
     *  which a refusal breaks (the broken coop war promise is held against the human).
     */
    fun onHumanAnswered(civ: Civilization, human: Civilization, target: Civilization, answer: HumanAnswer, now: Boolean) {
        val rules = civ.gameInfo.ruleset.ai
        val entry = entry(civ, human, target)
        entry.counter = 0
        when (answer) {
            HumanAnswer.Soon -> entry.state = CoopWarState.Soon
            HumanAnswer.Refuse -> if (now) {
                entry.state = null
                PromiseAi.markBroken(civ.ai.diplomacy.memoryOf(human), PromiseAi.PromiseKind.CoopWar, civ.gameInfo.turns)
            }
            HumanAnswer.Agree -> {
                entry.state = CoopWarState.Accepted
                if (target.isDefeated()) return
                WarAi.declareWar(civ, target)
                MilitaryOperations.requestBasicAttack(civ, target, 1)
                human.getDiplomacyManager(target)?.takeIf { it.canDeclareWar() }?.declareWar()
                val locked = rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH)
                civ.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += locked
                human.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += locked
            }
        }
    }

    /**
     *  The coop war part of CvDiplomacyAI::MakeWar: counters advance; AI partners who agreed to "soon" go to war at COOP_WAR_SOON_COUNTER.
     *  The wars are declared after the walk over the memories: declaring creates memories (ours of the target, the observers' events).
     */
    fun doCoopWarCounters(civ: Civilization, rules: AiRuleset) {
        var targets: ArrayList<Civilization>? = null
        for ((partnerId, memory) in civ.ai.diplomacy.memories) {
            val partner = civ.gameInfo.getCivilizationOrNull(partnerId) ?: continue
            if (!partner.isMajorCiv || !WarStateAi.isPlayerValid(civ, partner)) continue
            for ((targetId, entry) in memory.coopWars) {
                if (entry.counter < 0) continue
                val target = civ.gameInfo.getCivilizationOrNull(targetId) ?: continue
                if (!WarStateAi.isPlayerValid(civ, target)) continue
                entry.counter++
                if (!partner.isHuman() && entry.state == CoopWarState.Soon && entry.counter == rules.define(AiDefine.COOP_WAR_SOON_COUNTER)) {
                    entry.state = CoopWarState.Accepted
                    entry.counter = 0
                    (targets ?: ArrayList<Civilization>().also { targets = it }).add(target)
                }
            }
        }
        for (target in targets ?: return) {
            WarAi.declareWar(civ, target)
            MilitaryOperations.requestShowOfForce(civ, target)
            civ.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH)
        }
    }
}
