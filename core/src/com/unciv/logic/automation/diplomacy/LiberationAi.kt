package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  Whether an AI that has just taken a city gives it back to the major civilization that founded it: Vox Populi's
 *  CvDiplomacyAI::DoPossibleMajorLiberation (Release-5.4.8; the successor of the original's DoPossibleMajorLiberation, called from
 *  CvPlayerAI::AI_conquerCity). The giving back itself is [City.liberateCity].
 *
 *  Not ported, as they have no input here: war weariness (the very unhappy and war weary AI that liberates a neutral founder), a coop
 *  war agreement score above 1 (the agreement score), policies that boost liberation (POLICYMOD_LIBERATION_BONUS: none in BNW), and the
 *  victory dispute and block levels of a culture victory (DoUpdateVictoryDisputeLevels / DoUpdateVictoryBlockLevels are not ported).
 */
object LiberationAi {

    /**
     *  CvDiplomacyAI::DoPossibleMajorLiberation: would [civ] (an AI) give [city] back to [founder] (a major other than the city's owner
     *  and [civ])? The city is still its previous owner's, as at the moment of the decision. Never when:
     *  - going (or close to) world conquest, for an original capital;
     *  - the city is the holy city of a religion other than our state religion;
     *  - the founder is alive and we hate it: an opinion of enemy or worse, a guarded or worse approach, or it is untrustworthy.
     *  Then yes when our empire is unhappy and the founder a friend or better. Otherwise a bad war decision (we are at war with the
     *  previous owner, losing - troubled or worse, or a war score of -10 or less - and the founder is dead or at war with it too)
     *  holds liberation back in the cases that say so below.
     *  A living founder is liberated when:
     *  - we have a defensive pact with it and both of us are at war with the previous owner, or we have a coop war against the previous
     *    owner (a major) being prepared or fought with it;
     *  - it liberated us before, or we liberated it before (a liberator of a capital or holy city, or one of more cities than it
     *    took: [WarSanityAi.isLiberator] with only major cities);
     *  - our opinion of it is ally or our friendship is allies or better;
     *  - it liberated a city of ours or we one of its, our opinion is favorable or better, and it is no bad war decision (else the next case still applies);
     *  - or, if we are friends (a declaration of friendship we do not want to end, one we want, a pact, an opinion of friend or an
     *    approach of friendly): we go for or are close to a diplomatic victory; or the opinion is friend and the approach friendly and
     *    it is no bad war decision; or we go for a culture victory, it is no bad war decision, we are not endgame aggressive to it, and
     *    (when close to the culture victory) its influence over us is popular and rising, or influential or better.
     *  A dead founder that is not human (a resurrection) is liberated when we go for or are close to a diplomatic victory, it liberated
     *  us or we it before, our friendship was allies or better, or else when it is no bad war decision.
     */
    fun wantsToLiberateMajor(civ: Civilization, city: City, founder: Civilization): Boolean {
        val rules = civ.gameInfo.ruleset.ai
        val ai = civ.ai
        val ours = ai.diplomacy
        val opinion = ours.opinionOf(founder)
        val approach = ours.approachTowards(founder)
        val memory = ours.memoryOf(founder)
        val oldOwner = city.civ

        if ((ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules)) && city.isOriginalCapital) return false
        val stateReligion = OpinionAi.stateReligion(civ)?.first
        val holyCityOf = city.religion.religionThisIsTheHolyCityOf
        if (stateReligion != null && holyCityOf != null && holyCityOf != stateReligion.name) return false

        val alive = founder.isAlive()
        if (alive && (opinion <= MajorCivOpinion.Enemy || approach <= MajorCivApproach.Guarded || OpinionAi.isUntrustworthyFriend(founder))) return false

        // An unhappy empire gives back a city to a friend
        if (civ.getHappiness() < 0 && opinion >= MajorCivOpinion.Friend) return true

        // The original's quirk: a war whose state is not assessed yet counts as a bad one
        val warState = WarStateAi.warState(civ, oldOwner)
        val badWarDecision = !oldOwner.isBarbarian && civ.isAtWarWith(oldOwner) &&
            (warState == null || warState <= WarState.Troubled || WarDamageAi.warScore(civ, oldOwner) <= -10) &&
            (!alive || founder.isAtWarWith(oldOwner))

        if (alive) {
            val defensivePact = civ.getDiplomacyManager(founder)?.diplomaticStatus == DiplomaticStatus.DefensivePact
            val coop = CoopWarAi.state(civ, founder, oldOwner)
            var liberate = false
            when {
                defensivePact && civ.isAtWarWith(oldOwner) && founder.isAtWarWith(oldOwner) -> liberate = true
                oldOwner.isMajorCiv && coop != null && coop >= CoopWarState.Soon -> liberate = true
                WarSanityAi.isLiberator(civ, founder, onlyMajorCities = true) -> liberate = true
                WarSanityAi.isLiberator(founder, civ, onlyMajorCities = true) -> liberate = true
                opinion == MajorCivOpinion.Ally || memory.doFLevel >= DoFLevel.Allies -> liberate = true
                (memory.numCitiesLiberated > 0 || founder.ai.diplomacy.memoryOf(civ).numCitiesLiberated > 0) &&
                    opinion >= MajorCivOpinion.Favorable -> liberate = !badWarDecision
            }
            if (liberate) return true
            // Friends
            if (!((OpinionAi.isDoFAccepted(civ, founder) && !memory.wantsToEndDoF) || memory.wantsDoF || defensivePact ||
                    opinion >= MajorCivOpinion.Friend || approach == MajorCivApproach.Friendly)) return false
            return when {
                ai.isGoingForDiploVictory() || VictoryProgressAi.isCloseToDiploVictory(civ, rules) -> true
                opinion >= MajorCivOpinion.Friend && approach == MajorCivApproach.Friendly -> !badWarDecision
                ai.isGoingForCultureVictory() -> !badWarDecision && !VictoryProgressAi.isEndgameAggressiveTo(civ, founder, rules) &&
                    (!VictoryProgressAi.isCloseToCultureVictory(civ, rules) || influenceIsEnough(civ, founder))
                else -> false
            }
        }
        // A civilization that is dead may be resurrected (not a human one: it decides for itself)
        if (founder.isHuman()) return false
        return when {
            ai.isGoingForDiploVictory() || VictoryProgressAi.isCloseToDiploVictory(civ, rules) -> true
            WarSanityAi.isLiberator(civ, founder, onlyMajorCities = true) -> true
            WarSanityAi.isLiberator(founder, civ, onlyMajorCities = true) -> true
            memory.doFLevel >= DoFLevel.Allies -> true
            else -> !badWarDecision
        }
    }

    /** Close to a culture victory, liberating is for a founder we already influence: popular and rising, or influential or better */
    private fun influenceIsEnough(civ: Civilization, founder: Civilization): Boolean {
        val level = civ.culture.getInfluenceLevel(founder) ?: InfluenceLevel.Unknown
        if (level >= InfluenceLevel.Influential) return true
        return level >= InfluenceLevel.Popular && civ.culture.getInfluenceTrend(founder) == InfluenceTrend.Rising
    }
}
