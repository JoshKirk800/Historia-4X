package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  Part 6 of Vox Populi's SelectBestApproachTowardsMajorCiv (Release-5.4.8; D7), STRATEGIC DIPLOMACY, the war situation: bankruptcy,
 *  the loss of our capital, the wars we fight right now and dangerous neighbours. On our scale: VP's personality bias is ours x 100.
 *  War weariness (BNW has none) counts as none; VP's troubled war state, which we lack, as none.
 */
object ApproachPart6Ai {
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal
    private val AFRAID = MajorCivApproach.Afraid.ordinal
    private val FRIENDLY = MajorCivApproach.Friendly.ordinal
    private val NEUTRAL = MajorCivApproach.Neutral.ordinal

    /**
     *  BANKRUPTCY: broke (no gold, no income), a war that would bring us gold draws war and hostility (the larger of bias + gold and
     *  20 x bias x gold), one that would not friendliness and neutrality (5 x bias) and less war, hostility and deception. Having lost
     *  our capital: guard up towards one whose capital or holy city we took (10 x bias); otherwise, unless they took one of ours or
     *  are untrustworthy, friendliness over deception by their record (10 x bias without captures or wars on us, 5 without captures,
     *  else 2), by proximity (5 neighbours, 2 close) and by the other majors' original capitals we hold (2 each).
     */
    fun addBankruptcyWeights(civ: Civilization, other: Civilization, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        if (civ.gold <= 0 && WarStateAi.avgGoldRate(civ) <= 0) {
            val lost = WarSanityAi.goldPerTurnLostFromWar(civ, other)
            if (lost < 0) {
                val gain = -lost
                w[WAR] += maxOf(bias(MajorCivApproach.War) + gain, 20 * bias(MajorCivApproach.War) * gain)
                w[HOSTILE] += maxOf(bias(MajorCivApproach.Hostile) + gain, 20 * bias(MajorCivApproach.Hostile) * gain)
            } else {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 5
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 5
                w[WAR] -= bias(MajorCivApproach.War) * 5
                w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 5
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * 5
            }
        }
        if (!VictoryProgressAi.hasLostCapital(civ)) return
        if (OpinionAi.isCapitalCapturedBy(other, civ) || CompetitorAi.isHolyCityCapturedBy(other, civ)) {
            w[GUARDED] += bias(MajorCivApproach.Guarded) * 10
            return
        }
        val everCapturedKeyCity = OpinionAi.isCapitalCapturedBy(civ, other) || CompetitorAi.isHolyCityCapturedBy(civ, other)
        if (everCapturedKeyCity || OpinionAi.isUntrustworthyFriend(other)) return
        val memory = civ.ai.diplomacy.memoryOf(other)
        fun friendlier(times: Int) {
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * times
            w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * times
        }
        friendlier(when {
            memory.numCitiesCapturedBy <= 0 && memory.numWarsDeclaredOnUs <= 0 -> 10
            memory.numCitiesCapturedBy <= 0 -> 5
            else -> 2
        })
        when (maxOf(DiplomacyTurn.proximity(civ, other), DiplomacyTurn.proximity(other, civ))) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> friendlier(5)
            DiplomacyTurn.PROXIMITY_CLOSE -> friendlier(2)
        }
        val capitals = VictoryProgressAi.capitalsConquered(civ)
        if (capitals > 0) friendlier(capitals * 2)
    }

    /**
     *  AT WAR RIGHT NOW: for each other major we fight, friendliness (2 x bias) towards one fighting it too; friendliness and neutrality
     *  (2 x and 1 x bias) when they are our neighbour, and again when the enemy is. WAR STATE: unless we take no new wars, a war going
     *  better than troubled against an easy target, a player in terrible shape for war, or a phony war is ignored; any other war
     *  lowers war and hostility (5 x bias), more when it goes badly: a stalemate draws neutrality (2 x bias, war and hostility 1 x
     *  less), a defensive war friendliness (2 x), nearly defeated 5 x, both times their attack multiplier (2 when we are their easy
     *  target).
     */
    fun addAtWarWeights(civ: Civilization, other: Civilization, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val noNewWars = WarStateAi.isNoNewWars(civ)
        val theirAttackMultiplier = if (WarStateAi.isEasyTarget(other, civ)) 2 else 1
        val theyAreNeighbours = DiplomacyTurn.proximity(other, civ) == DiplomacyTurn.PROXIMITY_NEIGHBORS
        for (enemy in civ.gameInfo.civilizations) {
            if (enemy == other || enemy == civ || !enemy.isMajorCiv || !WarStateAi.isPlayerValid(civ, enemy) || !civ.isAtWarWith(enemy)) continue
            if (other.isAtWarWith(enemy)) w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
            if (theyAreNeighbours) {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
                w[NEUTRAL] += bias(MajorCivApproach.Neutral)
            }
            if (DiplomacyTurn.proximity(enemy, civ) == DiplomacyTurn.PROXIMITY_NEIGHBORS) {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
                w[NEUTRAL] += bias(MajorCivApproach.Neutral)
            }
            val state = WarStateAi.warState(civ, enemy)
            // Better than VP's troubled: stalemate or better (we have no troubled)
            if (!noNewWars && state != null && state >= WarState.Stalemate) {
                if (WarStateAi.isEasyTarget(civ, enemy) || WarStateAi.isInTerribleShapeForWar(enemy)) continue
                if (WarIntentAi.isPhonyWar(civ, enemy, ignoreCurrentApproach = true)) continue
            }
            w[WAR] -= bias(MajorCivApproach.War) * 5
            w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 5
            val (friendly, neutral, lower) = when (state) {
                WarState.Stalemate -> Triple(0, 2, 1)
                WarState.Defensive -> Triple(2 * theirAttackMultiplier, 0, 2 * theirAttackMultiplier)
                WarState.NearlyDefeated -> Triple(5 * theirAttackMultiplier, 0, 5 * theirAttackMultiplier)
                else -> Triple(0, 0, 0)
            }
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * friendly
            w[NEUTRAL] += bias(MajorCivApproach.Neutral) * neutral
            w[WAR] -= bias(MajorCivApproach.War) * lower
            w[HOSTILE] -= bias(MajorCivApproach.Hostile) * lower
        }
    }

    /**
     *  DANGEROUS NEIGHBORS: each major that may threaten us or be our target ([isPotentialMilitaryTargetOrThreat]; [other] too, as in
     *  VP), unless (taking new wars) an easy target, one we are winning against, or one in terrible shape for war, deters war and
     *  hostility and draws fear and guard by (proximity 2 for neighbours else 1, + their military strength over average, + the
     *  cities they took from us less those we took from them) x 100%, halved when not neighbours and halved again when we fight
     *  [other] but not them.
     */
    fun addDangerousNeighbourWeights(civ: Civilization, other: Civilization, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val noNewWars = WarStateAi.isNoNewWars(civ)
        for (third in civ.gameInfo.civilizations) {
            if (third == civ || !third.isMajorCiv || !WarStateAi.isPlayerValid(civ, third) || !isPotentialMilitaryTargetOrThreat(civ, third)) continue
            if (!noNewWars) {
                val state = WarStateAi.warState(civ, third)
                if (WarStateAi.isEasyTarget(civ, third) || (state != null && state >= WarState.Offensive) || WarStateAi.isInTerribleShapeForWar(third))
                    continue
            }
            val neighbours = DiplomacyTurn.proximity(civ, third) == DiplomacyTurn.PROXIMITY_NEIGHBORS ||
                DiplomacyTurn.proximity(third, civ) == DiplomacyTurn.PROXIMITY_NEIGHBORS
            val proximityFactor = if (neighbours) 2 else 1
            val strengthFactor = (WarStateAi.militaryStrengthComparedToUs(civ, third) ?: StrengthLevel.Average).ordinal - StrengthLevel.Average.ordinal
            val capturedFactor = civ.ai.diplomacy.memoryOf(third).numCitiesCapturedBy - third.ai.diplomacy.memoryOf(civ).numCitiesCapturedBy
            var deterrence = (proximityFactor + strengthFactor + capturedFactor) * 100
            if (deterrence <= 0) continue
            if (!neighbours) deterrence /= 2
            if (civ.isAtWarWith(other) && !civ.isAtWarWith(third)) deterrence /= 2
            w[WAR] -= bias(MajorCivApproach.War) * deterrence / 100
            w[HOSTILE] -= bias(MajorCivApproach.Hostile) * deterrence / 100
            w[AFRAID] += bias(MajorCivApproach.Afraid) * deterrence / 100
            w[GUARDED] += bias(MajorCivApproach.Guarded) * deterrence / 100
        }
    }

    /**
     *  CvDiplomacyAI::IsPotentialMilitaryTargetOrThreat (from the approach selection): a major with cities that nuked us or took our
     *  capital or holy city; or, close to us either way or aggressively posted (medium or more): stronger than us (military above
     *  average, economy above strong, or a severe warmonger) and no friend or ally, one who took cities from us, or one we would go to
     *  war with ([WarAlliesAi.avoidExchangesWithPlayer], war only, our own approach aside); and when stronger or at least poor, one of
     *  them or those who would fight us on their side is: untrustworthy to us (them), ready to fight a defensive pact partner of theirs
     *  we would go to war with, visibly guarded or worse towards us, denounced either way, a past aggressor against us (not a friend),
     *  one we took cities, the capital or the holy city from, one to whom we are untrustworthy, or one whose cities we hold.
     */
    fun isPotentialMilitaryTargetOrThreat(civ: Civilization, other: Civilization): Boolean {
        if (!other.isMajorCiv || !other.isAlive() || other.cities.isEmpty()) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (memory.numTimesNuked > 0 || OpinionAi.isCapitalCapturedBy(civ, other) || CompetitorAi.isHolyCityCapturedBy(civ, other)) return true
        val friends = OpinionAi.isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact
        if (DiplomacyTurn.proximity(civ, other) < DiplomacyTurn.PROXIMITY_CLOSE && DiplomacyTurn.proximity(other, civ) < DiplomacyTurn.PROXIMITY_CLOSE &&
            memory.militaryPosture < AggressivePosture.Medium) return false
        val military = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        val stronger = military > StrengthLevel.Average || (memory.economicStrength ?: StrengthLevel.Average) > StrengthLevel.Strong ||
            civ.ai.diplomacy.warmongerThreat(other) >= AiThreat.Severe
        if (stronger && !friends) return true
        if (memory.numCitiesCapturedBy > 0) return true
        if (WarAlliesAi.avoidExchangesWithPlayer(civ, other, warOnly = true, ignoreSelfApproach = true)) return true
        if (!stronger && military < StrengthLevel.Poor) return false
        for (player in WarAlliesAi.warAllies(civ, other, includeMinors = false, reverse = true) + other) {
            if (player == other && OpinionAi.isUntrustworthyFriend(other)) return true
            val ours = civ.ai.diplomacy.memoryOf(player)
            if (player.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact) {
                if (WarAlliesAi.avoidExchangesWithPlayer(civ, player, warOnly = true, ignoreSelfApproach = true)) return true
                continue
            }
            val towardsUs = player.ai.diplomacy.approachTowards(civ, hideTrueFeelings = true)
            if (ApproachAi.VP_ORDER.indexOf(towardsUs) <= ApproachAi.VP_ORDER.indexOf(MajorCivApproach.Guarded)) return true
            if (OpinionAi.isDenouncedPlayer(civ, player) || OpinionAi.isDenouncedPlayer(player, civ)) return true
            if (!friends && ours.numWarsDeclaredOnUs > 0) return true
            if (player.ai.diplomacy.memoryOf(civ).numCitiesCapturedBy > 0) return true
            if (OpinionAi.isCapitalCapturedBy(player, civ) || CompetitorAi.isHolyCityCapturedBy(player, civ)) return true
            if (OpinionAi.isUntrustworthyFriend(civ)) return true
            if (civ.cities.any { it.foundingCivObject == player }) return true
        }
        return false
    }
}
