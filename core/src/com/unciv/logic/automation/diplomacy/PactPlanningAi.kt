package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  Vox Populi's defensive pact planning (Release-5.4.8; D7): the defensive pact half of CvDiplomacyAI::DoUpdatePlanningExchanges, after
 *  [FriendshipPlanningAi.updateFriendships], with CvPlayer::CalculateDefensivePactLimit, IsGoodChoiceForDefensivePact and
 *  ScoreDefensivePactChoice. Each turn an AI decides with whom it wants a pact ([MajorCivMemory.wantsDefensivePact]), which pacts it
 *  would let go ([MajorCivMemory.wantsToEndDefensivePact]: VP ends none early, they lapse) and its most valuable ally
 *  ([DiplomacyAi.mostValuableAlly]). Its pact offers ([DiplomacyAutomation]) and the pact's deal value read these.
 *
 *  As none (no input yet): teams and vassals, pacts that give World Congress votes, sanctions, the coop war agreement score, liberated
 *  or returned capitals and holy cities, the ignore-the-limit bonus, game options.
 */
object PactPlanningAi {

    /**
     *  CvPlayer::CalculateDefensivePactLimit: none with fewer than two other majors alive; else DEFENSIVE_PACT_LIMIT_BASE plus one per
     *  DEFENSIVE_PACT_LIMIT_SCALER other majors (an AI that does not trade with a human: the lower of those and the AI_ values), at most
     *  the other majors
     */
    fun defensivePactLimit(civ: Civilization, tradeWithHuman: Boolean = false): Int {
        if (!civ.isMajorCiv || !civ.isAlive()) return 0
        val rules = civ.gameInfo.ruleset.ai
        val human = civ.isHuman() || tradeWithHuman
        val base = if (human) rules.define(AiDefine.DEFENSIVE_PACT_LIMIT_BASE)
            else minOf(rules.define(AiDefine.DEFENSIVE_PACT_LIMIT_BASE), rules.define(AiDefine.AI_DEFENSIVE_PACT_LIMIT_BASE))
        val scaler = if (human) rules.define(AiDefine.DEFENSIVE_PACT_LIMIT_SCALER)
            else minOf(rules.define(AiDefine.DEFENSIVE_PACT_LIMIT_SCALER), rules.define(AiDefine.AI_DEFENSIVE_PACT_LIMIT_SCALER))
        if (base <= -1) return 0
        val others = civ.gameInfo.civilizations.count { it != civ && it.isMajorCiv && it.isAlive() }
        if (others < 2) return 0
        var limit = base
        if (scaler > 0) limit += others / scaler
        return minOf(limit, others)
    }

    private fun hasDefensivePact(civ: Civilization, other: Civilization): Boolean =
        civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** CvTeam::isDefensivePactTradingAllowedWithTeam: the technology and an embassy */
    private fun isTradingAllowedWith(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacyFunctions.canSignDefensivePact() && civ.diplomacyFunctions.meetsEmbassyRequirementFor(other)

    /**
     *  The defensive pact half of DoUpdatePlanningExchanges, given the friendship half's [acceptableFriends], [mostValuableFriend] and
     *  [bestFriendPossible]. No limit (fewer than two other majors): every pact unwanted. A civilization one of [valid] resurrected
     *  pacts with its liberator only. Otherwise the acceptable pacts are acceptable friends we are and stay friends with that are
     *  [isGoodChoiceForDefensivePact]; a pact we hold outside them we let go; the most valuable friend gets one first if there is room;
     *  then the best [scoreDefensivePactChoice] above 0 up to the limit, which also picks the most valuable ally.
     */
    fun updatePacts(civ: Civilization, valid: List<Civilization>, acceptableFriends: Set<Civilization>, mostValuableFriend: Civilization?,
                    bestFriendPossible: Boolean) {
        val ai = civ.ai.diplomacy
        val limit = defensivePactLimit(civ)
        val cancel = limit <= 0
        for ((id, memory) in ai.memories) {
            val other = civ.gameInfo.getCivilizationOrNull(id) ?: continue
            if (!other.isMajorCiv) continue
            if (other in valid && !cancel) continue
            memory.wantsDefensivePact = false
            memory.wantsToEndDefensivePact = hasDefensivePact(civ, other) && other.isAlive()
        }
        if (!civ.diplomacyFunctions.canSignDefensivePact() || cancel) return
        val coastal = numEffectiveCoastalCities(civ) > 1

        // Resurrected: a pact with our liberator, and none with anyone else
        val liberator = civ.gameInfo.civilizations.firstOrNull { it.isMajorCiv && ai.memories[it.civID]?.resurrectedUs == true }
        if (liberator != null && liberator.isAlive() && liberator.cities.isNotEmpty() && liberator.diplomacyFunctions.canSignDefensivePact() &&
            limit >= 1) {
            for ((id, memory) in ai.memories) {
                if (id == liberator.civID) continue
                val other = civ.gameInfo.getCivilizationOrNull(id) ?: continue
                memory.wantsToEndDefensivePact = hasDefensivePact(civ, other)
                memory.wantsDefensivePact = false
            }
            val memory = ai.memoryOf(liberator)
            memory.wantsDefensivePact = true
            memory.wantsToEndDefensivePact = false
            ai.mostValuableAlly = if (scoreDefensivePactChoice(civ, liberator, coastal) > 0) liberator.civID else null
            return
        }

        // Only acceptable friends, with a friendship we keep
        val acceptable = acceptableFriends.filter { other ->
            val memory = ai.memoryOf(other)
            OpinionAi.isDoFAccepted(civ, other) && memory.wantsDoF && !memory.wantsToEndDoF && isGoodChoiceForDefensivePact(civ, other)
        }.toSet()
        for (other in valid) {
            val memory = ai.memoryOf(other)
            val ours = hasDefensivePact(civ, other)
            memory.wantsDefensivePact = ours && other in acceptable
            memory.wantsToEndDefensivePact = ours && other !in acceptable
        }

        var pacts = civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() && hasDefensivePact(civ, it) }
        // The most valuable friend is exempt from the scoring: a pact with them whenever there is room
        var bestFriendScore = 0
        var mostValuableAlly: Civilization? = null
        if (mostValuableFriend != null && OpinionAi.isDoFAccepted(civ, mostValuableFriend) && mostValuableFriend in acceptable) {
            if (!hasDefensivePact(civ, mostValuableFriend)) {
                if (pacts < limit) {
                    ai.memoryOf(mostValuableFriend).wantsDefensivePact = true
                    pacts++
                    val score = scoreDefensivePactChoice(civ, mostValuableFriend, coastal)
                    if (score > bestFriendScore) {
                        bestFriendScore = score
                        mostValuableAlly = mostValuableFriend
                    }
                }
            } else {
                val score = scoreDefensivePactChoice(civ, mostValuableFriend, coastal)
                if (score > bestFriendScore) {
                    bestFriendScore = score
                    mostValuableAlly = mostValuableFriend
                }
            }
        }

        // The most valuable pacts by their scores
        val excluded = HashSet<Civilization>()
        while (true) {
            val candidate = acceptable.filter { it !in excluded }
                .map { it to scoreDefensivePactChoice(civ, it, coastal) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }?.first ?: break
            if ((mostValuableAlly == null || bestFriendScore > 0) && bestFriendPossible && candidate in acceptableFriends) {
                if (mostValuableAlly != null) {
                    // A better scoring ally who is not our most valuable friend
                    if (scoreDefensivePactChoice(civ, candidate, coastal) > bestFriendScore) {
                        bestFriendScore = 0
                        mostValuableAlly = candidate
                    }
                } else mostValuableAlly = candidate
            }
            val memory = ai.memoryOf(candidate)
            if (!hasDefensivePact(civ, candidate) && !memory.wantsDefensivePact && pacts + 1 <= limit) {
                memory.wantsDefensivePact = true
                pacts++
                if (pacts >= limit && bestFriendScore == 0 && (mostValuableAlly != null || !bestFriendPossible)) break
            }
            excluded.add(candidate)
        }
        ai.mostValuableAlly = mostValuableAlly?.civID
    }

    /**
     *  CvDiplomacyAI::IsGoodChoiceForDefensivePact: no exchanges to avoid with them, pacts allowed both ways, known long enough unless
     *  friends already, more than two majors alive, trustworthy, no denouncement either way, our approach and strategic approach better
     *  than deceptive, an opinion better than enemy, no wish to end our friendship, denounce them or backstab them, no recent peace treaty.
     */
    fun isGoodChoiceForDefensivePact(civ: Civilization, other: Civilization): Boolean {
        if (WarAlliesAi.avoidExchangesWithPlayer(civ, other)) return false
        if (!isTradingAllowedWith(civ, other) || !isTradingAllowedWith(other, civ)) return false
        if (FriendshipPlanningAi.isTooEarlyForDoF(civ, other) && !OpinionAi.isDoFAccepted(civ, other)) return false
        if (civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } <= 2) return false
        if (OpinionAi.isUntrustworthyFriend(civ, other)) return false
        if (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ)) return false
        val ai = civ.ai.diplomacy
        val strategic = ai.memoryOf(other).strategicApproach ?: MajorCivApproach.Neutral
        if (ai.approachTowards(other).ordinal <= MajorCivApproach.Deceptive.ordinal || strategic.ordinal <= MajorCivApproach.Deceptive.ordinal)
            return false
        if (ai.opinionOf(other) <= MajorCivOpinion.Enemy) return false
        if (ai.memoryOf(other).wantsToEndDoF || DiplomacyAutomation.isDenounceAcceptable(civ, other, bias = false) ||
            FriendshipPlanningAi.isEndDoFAcceptable(civ, other, ignoreCurrentDoF = true) || FriendshipPlanningAi.isDenounceFriendAcceptable(civ, other))
            return false
        return !WarTargetAi.isRecentPeaceTreaty(civ, other, civ.gameInfo.ruleset.ai)
    }

    /**
     *  CvDiplomacyAI::ScoreDefensivePactChoice: loyalty; their strength compared to ours (pathetic -60 .. immense +60); proximity
     *  (neighbours +5, far -20, distant -50); our opinion (unforgivable -1000, enemy -100, competitor -20, neutral -10, favourable +5,
     *  friend +10, ally +20); our capitals on one landmass +5, else -10 when both are coastal, -20 otherwise; half their common foe
     *  score; +50 when they resurrected us; -25 per city of ours they hold, else +25 per city they liberated. Never exactly 0 (-5).
     */
    fun scoreDefensivePactChoice(civ: Civilization, other: Civilization, coastal: Boolean): Int {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        val rules = civ.gameInfo.ruleset.ai
        var value = civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty)
        value += when (WarStateAi.militaryStrengthComparedToUs(civ, other)) {
            StrengthLevel.Pathetic -> -60
            StrengthLevel.Weak -> -40
            StrengthLevel.Poor -> -20
            StrengthLevel.Average -> 10
            StrengthLevel.Strong -> 20
            StrengthLevel.Powerful -> 40
            StrengthLevel.Immense -> 60
            null -> 0
        }
        value += when (DiplomacyTurn.proximity(civ, other)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 5
            DiplomacyTurn.PROXIMITY_CLOSE -> 0
            DiplomacyTurn.PROXIMITY_FAR -> -20
            else -> -50
        }
        value += when (ai.opinionOf(other)) {
            MajorCivOpinion.Unforgivable -> -1000
            MajorCivOpinion.Enemy -> -100
            MajorCivOpinion.Competitor -> -20
            MajorCivOpinion.Neutral -> -10
            MajorCivOpinion.Favorable -> 5
            MajorCivOpinion.Friend -> 10
            MajorCivOpinion.Ally -> 20
        }
        val ourCapital = civ.getCapital()
        val theirCapital = other.getCapital()
        if (ourCapital != null && theirCapital != null) {
            val shared = ourCapital.getCenterTile().getContinent() == theirCapital.getCenterTile().getContinent()
            value += when {
                shared -> 5
                coastal && numEffectiveCoastalCities(other) > 1 -> -10
                else -> -20
            }
        }
        value += OpinionAi.commonFoeScore(civ, other, rules) / -2
        if (memory.resurrectedUs) value += 50
        val ourCitiesTheyOwn = other.cities.count { it.foundingCivObject == civ }
        if (ourCitiesTheyOwn > 0) value -= ourCitiesTheyOwn * 25
        else if (memory.numCitiesLiberated > 0) value += memory.numCitiesLiberated * 25
        return if (value == 0) -5 else value
    }

    /** CvPlayer::GetNumEffectiveCoastalCities: cities next to a body of water of at least 10 tiles, not being razed, not puppets */
    fun numEffectiveCoastalCities(civ: Civilization): Int =
        civ.cities.count { !it.isBeingRazed && !it.isPuppet && WaterBodies.isCoastal(it, 10) }
}
