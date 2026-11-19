package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  What Vox Populi's AI means to do in a war it is in (CvDiplomacyAI, Release-5.4.8; D7): whether it wants to conquer the enemy
 *  ([wantsToConquer]) and whether the war is a phony one ([isPhonyWar]). Our war states lack VP's TROUBLED, between defensive and
 *  stalemate: defensive or worse stands for it.
 */
object WarIntentAi {

    /** CvDiplomacyAI::GetWarscoreThresholdPositive: WARSCORE_THRESHOLD_POSITIVE (25) + 5 - DiploBalance */
    fun warScoreThresholdPositive(civ: Civilization) = 25 + 5 - civ.ai.diplomacyValue(DiplomacyPersonality.DiploBalance)

    /** CvDiplomacyAI::GetWarscoreThresholdNegative: WARSCORE_THRESHOLD_NEGATIVE (-25) + 5 - Meanness */
    fun warScoreThresholdNegative(civ: Civilization) = -25 + 5 - civ.ai.diplomacyValue(DiplomacyPersonality.Meanness)

    /**
     *  CvDiplomacyAI::IsWantsToConquer: at war with [other], yes when only two majors are alive, the war goes offensive or better, they
     *  hold our capital or holy city, or we are endgame-aggressive towards them; no when we are in terrible shape for war; yes against an
     *  easy target with a preferred attack target ([com.unciv.logic.automation.strategy.MilitaryAi.havePreferredAttackTarget]).
     *  Otherwise, if we started or wanted the war ([MajorCivMemory.aggressor]): yes unless they are an impossible target, or a bad one
     *  without a preferred attack target, where only a major competitor of a conquering civilization, or a coop war against them, keeps
     *  us at it. If we did not: going for conquest with them close, no for an impossible target or a bad one without a preferred
     *  attack target; else no for a bad target, or an average one without a preferred attack target; yes for the rest.
     */
    fun wantsToConquer(civ: Civilization, other: Civilization): Boolean {
        if (!civ.isAtWarWith(other)) return false
        val rules = civ.gameInfo.ruleset.ai
        if (civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } == 2) return true
        if ((WarStateAi.warState(civ, other) ?: WarState.Calm) >= WarState.Offensive) return true
        val holyCity = civ.religionManager.religion?.takeIf { it.foundingCivName == civ.civName }
        if (OpinionAi.isCapitalCapturedBy(civ, other) || (holyCity != null && other.cities.any { it.isHolyCityOf(holyCity.name) })) return true
        if (VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) return true
        if (WarStateAi.isInTerribleShapeForWar(civ)) return false
        val target = WarStateAi.targetValue(civ, other) ?: TargetValue.Average
        val goodTarget = civ.ai.military.havePreferredAttackTarget(other)
        val aggressive = civ.ai.hasTraitCategory(TraitCategory.Warmonger) ||
            (VictoryProgressAi.isCompetingForVictory(civ) && civ.ai.isGoingForWorldConquest())
        if (WarStateAi.isEasyTarget(civ, other) && goodTarget) return true
        val badTarget = target == TargetValue.Impossible || (target == TargetValue.Bad && !goodTarget)
        if (civ.ai.diplomacy.memoryOf(other).aggressor) {
            if (badTarget) {
                if (CompetitorAi.isMajorCompetitor(civ, other) && aggressive) return true
                val coop = CoopWarAi.globalState(civ, other)
                return coop == CoopWarState.Soon || coop == CoopWarState.Accepted
            }
            return true
        }
        if (aggressive && DiplomacyTurn.proximity(civ, other) >= DiplomacyTurn.PROXIMITY_CLOSE) return !badTarget
        return !(target <= TargetValue.Bad || (target <= TargetValue.Average && !goodTarget))
    }

    /**
     *  CvDiplomacyAI::IsPhonyWar: a war with [other] that nobody fights. Not when our approach is war (unless [ignoreCurrentApproach]),
     *  they took cities from us, the war goes offensive or better or defensive or worse, the war score is past our thresholds, we want to
     *  conquer them, we run an offensive operation against them, cities on either side are threatened, they are our neighbours, or (close)
     *  their posture is medium or more or they are powerful or more, or (farther) their posture is high or they are immense.
     */
    fun isPhonyWar(civ: Civilization, other: Civilization, ignoreCurrentApproach: Boolean = false): Boolean {
        if (!civ.isAtWarWith(other)) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (!ignoreCurrentApproach && memory.approach == MajorCivApproach.War) return false
        if (memory.numCitiesCapturedBy > 0) return false
        val state = WarStateAi.warState(civ, other) ?: WarState.Calm
        if (state >= WarState.Offensive || state <= WarState.Defensive) return false
        val score = WarDamageAi.warScore(civ, other)
        if (score <= warScoreThresholdNegative(civ) || score >= warScoreThresholdPositive(civ)) return false
        if (wantsToConquer(civ, other)) return false
        if (civ.ai.operations.hasAnyOffensiveOperationsAgainst(other)) return false
        if (CityDangerAi.numberOfThreatenedCities(civ, other) > 0 || CityDangerAi.numberOfThreatenedCities(other, civ) > 0) return false
        val proximity = DiplomacyTurn.proximity(civ, other)
        val posture = memory.militaryPosture
        val strength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        return when {
            proximity == DiplomacyTurn.PROXIMITY_NEIGHBORS -> false
            proximity == DiplomacyTurn.PROXIMITY_CLOSE -> posture < AggressivePosture.Medium && strength < StrengthLevel.Powerful
            else -> posture < AggressivePosture.High && strength < StrengthLevel.Immense
        }
    }
}
