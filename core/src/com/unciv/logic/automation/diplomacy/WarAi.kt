package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach

/**
 *  War-making of CvDiplomacyAI: MakeWar (targets by war projection and proximity²), DoMakeWarOnPlayer (one war with majors at a time,
 *  WarGoal PREPARE, the sneak attack / city-state attack operation of [MilitaryOperations], declaring war when that operation is 85% of
 *  the way to its target or has mustered at it), DeclareWar, DoMakePeaceWithMinors.
 */
object WarAi {

    /** The share of the way to its target a show of force must have covered before the demand is made (BNW's CvDiplomacyAI::DoTestDemandReady) */
    const val PERCENT_TO_DECLARE_WAR = 85

    /**
     *  CvDiplomacyAI::MakeWar (Vox Populi, D7): every valid target in turn, ranked by its strategic war score (a major) or its target
     *  value + 1 on VP's scale (a city-state; none counts 0), times (1 + proximity) squared; ties in player order.
     */
    fun makeWar(civ: Civilization, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val targets = civ.gameInfo.civilizations.filter { (it.isMajorCiv || it.isCityState) && WarStateAi.isPlayerValid(civ, it) }
            .map { target ->
                var weight = if (target.isMajorCiv)
                    ai.memoryOf(target).strategicApproachValues.getOrNull(MajorCivApproach.War.ordinal) ?: 0
                else vpTargetValue(ai.targetValue(target)) + 1
                // Square the distance enum to make it crucial
                val proximity = 1 + DiplomacyTurn.proximity(civ, target)
                weight *= proximity
                weight *= proximity
                target to weight
            }.sortedByDescending { it.second }
        for ((target, _) in targets) doMakeWarOnPlayer(civ, target, rules)
        CoopWarAi.doCoopWarCounters(civ, rules)
    }

    /** MilitaryAIHelpers::IsTestStrategy_AtWar: at war with at least one major */
    fun isAtWarWithAMajor(civ: Civilization): Boolean = civ.getCivsAtWarWith().any { it.isMajorCiv }

    /**
     *  CvDiplomacyAI::DoMakeWarOnPlayer (Vox Populi, D7). At war: nothing to do. When we may not declare war on [target] (not valid, a
     *  peace treaty): no army counts as in place and our offensive operations against them stop. We want to attack a major we have
     *  the war approach towards (a city-state: our conquest approach while at war with no major, BNW's stand-in for VP's city-state war
     *  target) and want a show of force against our demand target, the attack first. An army in place ([MajorCivMemory.musteringForAttack],
     *  set when an attack reaches its target or is discovered on the way) declares the war - on a major once no coop war against them
     *  is still being prepared - or makes the demand ready; with neither wish our operations against them stop. Without one we request
     *  the attack (the war goal Prepare) or the show of force, or else stop any operation against them. VP's requests are its
     *  RequestCityAttack (three units to build, careful without new wars); ours are still BNW's sneak and city-state attacks.
     */
    fun doMakeWarOnPlayer(civ: Civilization, target: Civilization, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(target)
        if (civ.isAtWarWith(target)) {
            memory.musteringForAttack = false
            return
        }
        if (!WarStateAi.isPlayerValid(civ, target) || civ.getDiplomacyManager(target)?.canDeclareWar() != true) {
            memory.musteringForAttack = false
            stopOffensiveOperations(civ, target, OperationAbortReason.TARGET_NOT_VALID)
            return
        }
        val wantToAttack: Boolean
        val wantShowOfForce: Boolean
        if (target.isCityState) {
            wantToAttack = !isAtWarWithAMajor(civ) && civ.ai.minorCivApproaches[target.civID] == MinorCivApproach.Conquest
            wantShowOfForce = false
        } else {
            // Vox Populi's bWantToAttack: the war approach, which its war targets (WarTargetAi) give within the conflict budget
            wantToAttack = ai.approachTowards(target) == MajorCivApproach.War
            wantShowOfForce = ai.demandTarget == target.civID
        }
        if (memory.musteringForAttack) {
            memory.musteringForAttack = false
            when {
                // Not before the allies of a coop war against them are ready
                wantToAttack -> if (target.isCityState || CoopWarAi.globalState(civ, target) != CoopWarState.Soon) declareWar(civ, target)
                wantShowOfForce -> ai.demandReady = true
                else -> stopOffensiveOperations(civ, target, OperationAbortReason.DIPLO_OPINION_CHANGE)
            }
            return
        }
        when {
            wantToAttack -> {
                memory.warGoal = WarGoal.Prepare
                if (target.isCityState) {
                    if (MilitaryOperations.cityStateAttackOperation(civ, target) == null) MilitaryOperations.requestCityStateAttack(civ, target)
                } else if (MilitaryOperations.sneakAttackOperation(civ, target) == null) MilitaryOperations.requestSneakAttack(civ, target)
            }
            wantShowOfForce -> if (MilitaryOperations.showOfForceOperation(civ, target) == null) MilitaryOperations.requestShowOfForce(civ, target)
            else -> {
                stopOffensiveOperations(civ, target, OperationAbortReason.DIPLO_OPINION_CHANGE)
                if (memory.warGoal == WarGoal.Prepare) memory.warGoal = null
            }
        }
    }

    /** CvPlayer::StopAllLandOffensiveOperationsAgainstPlayer and StopAllSeaOffensiveOperationsAgainstPlayer */
    private fun stopOffensiveOperations(civ: Civilization, target: Civilization, reason: OperationAbortReason) {
        for (operation in civ.ai.operations.offensiveOperationsAgainst(target)) civ.ai.operations.kill(operation, reason)
    }

    /** CvDiplomacyAI::DeclareWar */
    fun declareWar(civ: Civilization, target: Civilization) {
        val diplomacy = civ.getDiplomacyManager(target) ?: return
        if (civ.isAtWarWith(target)) return
        diplomacy.declareWar(DeclareWarReason(WarType.DirectWar))
    }

    /** CvDiplomacyAI::DoMakePeaceWithMinors: a war goal of PEACE long enough, not locked into the war */
    fun makePeaceWithMinors(civ: Civilization) {
        val ai = civ.ai.diplomacy
        for (minor in civ.getCivsAtWarWith().filter { it.isCityState && WarStateAi.isPlayerValid(civ, it) }) {
            if (!ai.isWantsPeaceWith(minor) || ai.memoryOf(minor).turnsLockedIntoWar != 0) continue
            // CvMinorCivAI::IsPeaceBlocked: a city-state allied to someone at war with us stays at war
            val ally = minor.allyCiv
            if (ally != null && ally.isAtWarWith(civ)) continue
            civ.getDiplomacyManager(minor)!!.makePeace()
            DiplomacyEvents.onPeace(civ, minor)
        }
    }

    /** BNW's target value on VP's TargetValueTypes scale (VP adds Difficult and Cakewalk); none = NO_TARGET_VALUE, -1 */
    private fun vpTargetValue(value: TargetValue?): Int = when (value) {
        null -> -1
        TargetValue.Impossible -> 0
        TargetValue.Bad -> 1
        TargetValue.Average -> 3
        TargetValue.Favorable -> 4
        TargetValue.Soft -> 5
    }
}
