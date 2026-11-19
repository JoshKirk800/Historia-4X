package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  Part 6 of Vox Populi's SelectBestApproachTowardsMajorCiv (Release-5.4.8; D7): THEY'RE CLOSE TO WINNING and the dogpile (is this
 *  player already in a war with someone who isn't us?). On our scale: VP's personality bias is ours x 100.
 *  As none (no input): an active unique unit, the league's prime competitor, the victory dispute and block levels
 *  (not ported: BNW reads them nowhere else).
 */
object ApproachWinningAi {
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal
    private val AFRAID = MajorCivApproach.Afraid.ordinal
    private val FRIENDLY = MajorCivApproach.Friendly.ordinal

    /**
     *  THEY'RE CLOSE TO WINNING: one close to world conquest draws war, hostility and guard (5 x bias). A conqueror of other majors'
     *  capitals close to us, by the game's era (the rounded average of the living majors'): war and guard 3 x per capital in the
     *  Ancient era, 2 x up to the Classical, 1 x later; as much war and hostility again against an easy target, and 5 x (Ancient), 3 x
     *  (Classical), 3 x (later, also for a conqueror or when we go for world conquest) more when we are close to world conquest or
     *  have the warmonger trait. Endgame aggression against them: war and hostility 5 x for each of their close science, diplomatic
     *  and culture victories
     *  and once more when we are close to any victory; war 5 x more against an easy target.
     */
    fun addCloseToWinningWeights(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        fun warAndHostile(times: Int) {
            w[WAR] += bias(MajorCivApproach.War) * times
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * times
        }
        if (VictoryProgressAi.isCloseToWorldConquest(other, rules)) {
            warAndHostile(5)
            w[GUARDED] += bias(MajorCivApproach.Guarded) * 5
        }
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        val easy = WarStateAi.isEasyTarget(civ, other)
        val danger = VictoryProgressAi.capitalsConquered(other)
        val closest = maxOf(DiplomacyTurn.proximity(civ, other), DiplomacyTurn.proximity(other, civ))
        if (danger > 0 && closest >= DiplomacyTurn.PROXIMITY_CLOSE) {
            val gameEra = gameEra(civ)
            val warBonus = closeToConquest || civ.ai.hasTraitCategory(TraitCategory.Warmonger)
            val (times, bonus, bonusApplies) = when {
                gameEra <= rules.define(AiDefine.ANCIENT_ERA) -> Triple(3, 5, warBonus)
                gameEra <= rules.define(AiDefine.CLASSICAL_ERA) -> Triple(2, 3, warBonus)
                else -> Triple(1, 3, warBonus || civ.ai.isConqueror() || civ.ai.isGoingForWorldConquest())
            }
            w[WAR] += bias(MajorCivApproach.War) * times * danger
            w[GUARDED] += bias(MajorCivApproach.Guarded) * times * danger
            if (easy) {
                warAndHostile(times * danger)
                if (bonusApplies) warAndHostile(bonus)
            }
        }
        if (VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) {
            if (VictoryProgressAi.scienceVictoryProgress(other) >= rules.define(AiDefine.CLOSE_TO_SCIENCE_VICTORY_THRESHOLD)) warAndHostile(5)
            if (VictoryProgressAi.diplomaticVictoryProgress(other) >= rules.define(AiDefine.CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD)) warAndHostile(5)
            if (VictoryProgressAi.cultureVictoryProgress(other, rules) >= rules.define(AiDefine.CLOSE_TO_CULTURE_VICTORY_THRESHOLD)) warAndHostile(5)
            if (VictoryProgressAi.isCloseToAnyVictoryCondition(civ, rules)) warAndHostile(5)
            if (easy) w[WAR] += bias(MajorCivApproach.War) * 5
        }
    }

    /**
     *  The dogpile, when we want an opportunity attack on [other]: each major at war with them is a reason to join when they are a
     *  competitor of ours (major, early-game, or a conqueror of capitals), or when they lose that war (their war damage from it 80 or
     *  more and above the attacker's: fear and guard 5 x less; their war state offensive or better: 3 x less; a close attacker winning:
     *  2 x less). Outside the strategic pass, an attacker suffering (40 or more war damage, more than ours) that we befriend (a friend
     *  or ally, or a liberator, trustworthy and approached better than guarded) draws war and hostility by our loyalty (half of it
     *  unless they are powerful); a competitor of ours under their attack makes us friendlier towards them by our victory
     *  competitiveness (in full when it is our biggest competitor or one we are endgame-aggressive to, half and a quarter when we like
     *  them less than [other]), unless they are untrustworthy, denounced us, took our cities or the war goes well for us. A powerful
     *  close attacker that is no friend stops the dogpile. Each reason adds the larger victory concern (none here) plus the attacker's
     *  war state against them on VP's scale, our own war state when we fight them (less 4): doubled for close attackers of an early
     *  or capital competitor (x 2.5 when all three are before the Renaissance), halved (or nothing before our Renaissance) when the
     *  attacker is not close. A competitor under attack with no friend under attack stops the dogpile too. The bonus adds war and
     *  hostility by the bias, through the work-against willingness, halved.
     */
    fun addDogpileWeights(civ: Civilization, other: Civilization, strategic: Boolean, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        if (!ApproachAi.wantsOpportunityAttack(civ, other, rules)) return
        val ai = civ.ai.diplomacy
        val danger = VictoryProgressAi.capitalsConquered(other)
        val earlyGameCompetitor = CompetitorAi.isEarlyGameCompetitor(civ, other, rules)
        val endgameAggressive = VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
        val ourStrength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        val renaissance = rules.define(AiDefine.RENAISSANCE_ERA)
        val competitiveness = civ.ai.diplomacyValue(DiplomacyPersonality.VictoryCompetitiveness)
        var bonus = 0
        var friendUnderAttack = false
        var competitorUnderAttack = false
        for (attacker in civ.gameInfo.civilizations) {
            if (attacker == civ || attacker == other || !attacker.isMajorCiv || !WarStateAi.isPlayerValid(civ, attacker) ||
                !WarStateAi.isPlayerValid(other, attacker) || !attacker.isAtWarWith(other)) continue
            val attackerClose = DiplomacyTurn.proximity(attacker, other) >= DiplomacyTurn.PROXIMITY_CLOSE
            var thinking = CompetitorAi.isMajorCompetitor(civ, other) || earlyGameCompetitor || danger > 0
            val theirDamage = WarDamageAi.warDamageValue(other, attacker)
            val attackerDamage = WarDamageAi.warDamageValue(attacker, other)
            fun lessFearAndGuard(times: Int) {
                thinking = true
                w[AFRAID] -= bias(MajorCivApproach.Afraid) * times
                w[GUARDED] -= bias(MajorCivApproach.Guarded) * times
            }
            when {
                theirDamage >= 80 && theirDamage > attackerDamage -> lessFearAndGuard(5)
                (WarStateAi.warState(other, attacker) ?: WarState.Calm) >= WarState.Offensive -> lessFearAndGuard(3)
                attackerClose && (WarStateAi.warState(attacker, other) ?: WarState.Calm) >= WarState.Offensive -> lessFearAndGuard(2)
                !strategic && !endgameAggressive && attackerDamage >= 40 && attackerDamage > theirDamage -> {
                    val keyPartner = ai.mostValuableFriend == attacker.civID || ai.mostValuableAlly == attacker.civID
                    val befriended = (isFriendOrAlly(civ, attacker) || keyPartner || WarSanityAi.isLiberator(civ, attacker, onlyMajorCities = false)) &&
                        !OpinionAi.isUntrustworthyFriend(attacker) &&
                        ApproachAi.VP_ORDER.indexOf(ai.approachTowards(attacker)) > ApproachAi.VP_ORDER.indexOf(MajorCivApproach.Guarded)
                    if (befriended) {
                        friendUnderAttack = true
                        val loyalty = civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty)
                        // VP's own order: below powerful takes half the loyalty, so its "below average" case never applies
                        if (ourStrength < StrengthLevel.Powerful) {
                            w[WAR] += bias(MajorCivApproach.War) * loyalty / 2
                            w[HOSTILE] += bias(MajorCivApproach.Hostile) * loyalty / 2
                            thinking = true
                        }
                    } else if (!OpinionAi.isUntrustworthyFriend(other) && !OpinionAi.isDenouncedPlayer(other, civ) &&
                        ai.memoryOf(other).numCitiesCapturedBy <= 0 && WarDamageAi.warScore(civ, other) < WarIntentAi.warScoreThresholdPositive(civ) &&
                        (!civ.isAtWarWith(other) || WarIntentAi.isPhonyWar(civ, other, ignoreCurrentApproach = true) ||
                            WarDamageAi.warScore(civ, other) > WarIntentAi.warScoreThresholdNegative(civ) / 2)) {
                        if (CompetitorAi.biggestCompetitor(civ) == attacker.civID || VictoryProgressAi.isEndgameAggressiveTo(civ, attacker, rules)) {
                            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * competitiveness
                            w[WAR] -= bias(MajorCivApproach.War) * competitiveness * 2
                            w[HOSTILE] -= bias(MajorCivApproach.Hostile) * competitiveness
                            competitorUnderAttack = true
                        } else if (ai.opinionOf(attacker) < ai.opinionOf(other) && CompetitorAi.biggestCompetitor(civ) != other.civID) {
                            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * competitiveness / 2
                            w[WAR] -= bias(MajorCivApproach.War) * competitiveness / 4
                            w[HOSTILE] -= bias(MajorCivApproach.Hostile) * competitiveness / 4
                            competitorUnderAttack = true
                        }
                    }
                }
            }
            // If we can't take them, we don't want them attacking us after finishing with this guy
            if (thinking && attackerClose && (WarStateAi.militaryStrengthComparedToUs(civ, attacker) ?: StrengthLevel.Average) >= StrengthLevel.Powerful &&
                !isFriendOrAlly(civ, attacker)) return
            if (!thinking) continue
            var bonusMod = vpWarState(WarStateAi.warState(attacker, other))
            if (civ.isAtWarWith(other)) bonusMod += vpWarState(WarStateAi.warState(civ, other)) - 4
            if (bonusMod > 0) {
                if (attackerClose && (earlyGameCompetitor || danger > 0)) {
                    if (other.getEra().eraNumber < renaissance && attacker.getEra().eraNumber < renaissance) bonusMod = bonusMod * 5 / 2
                    else bonusMod *= 2
                } else if (!attackerClose) {
                    bonusMod = if (civ.getEra().eraNumber < renaissance) 0 else bonusMod / 2
                }
            }
            bonus += bonusMod
        }
        // Let's let our enemy fall before we take them on
        if (!friendUnderAttack && competitorUnderAttack) return
        if (bonus <= 0) return
        val against = civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)
        w[WAR] += PeaceAi.adjustConditionalModifier(bias(MajorCivApproach.War) * bonus, against) / 2
        w[HOSTILE] += PeaceAi.adjustConditionalModifier(bias(MajorCivApproach.Hostile) * bonus, against) / 2
    }

    /** CvDiplomacyAI::IsFriendOrAlly: a DoF or a defensive pact */
    private fun isFriendOrAlly(civ: Civilization, other: Civilization): Boolean =
        OpinionAi.isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** Our war state on VP's WarStateTypes scale (which has troubled between defensive and stalemate); none = NO_WAR_STATE_TYPE, -1 */
    private fun vpWarState(state: WarState?): Int = when (state) {
        null -> -1
        WarState.NearlyDefeated -> 0
        WarState.Defensive -> 1
        WarState.Stalemate -> 3
        WarState.Calm -> 4
        WarState.Offensive -> 5
        WarState.NearlyWon -> 6
    }

    /** CvGame::getCurrentEra: the rounded average era of the living majors */
    fun gameEra(civ: Civilization): Int {
        val majors = civ.gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() }
        if (majors.isEmpty()) return 0
        return (majors.sumOf { it.getEra().eraNumber }.toFloat() / majors.size + 0.5f).toInt()
    }
}
