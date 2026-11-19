package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  Part 6 of Vox Populi's SelectBestApproachTowardsMajorCiv (Release-5.4.8; D7): EARLY GAME STRATEGY and the domination half of
 *  GRAND STRATEGY WEIGHT. On our scale: VP's personality bias is ours x 100, VP's flat values / 100.
 *  As none (no input): the coop war agreement score, strategic trade partners.
 */
object ApproachGrandStrategyAi {
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal
    private val FRIENDLY = MajorCivApproach.Friendly.ordinal
    private val NEUTRAL = MajorCivApproach.Neutral.ordinal

    /**
     *  EARLY GAME STRATEGY, before the Renaissance. An early-game competitor close to us draws war and hostility (2 x bias; deceptive
     *  when deception applies), twice that for a bold or mean (above 7) or conquering leader (a Domination primary victory pursuit or
     *  the warmonger trait), and war and hostility 2 x more after a
     *  denouncement either way. Anyone else staying out of our way draws friendliness and neutrality (2 x) over deception, neutrality
     *  2 x more for a scientist (a Science primary pursuit) and friendliness 2 x more for the others, and by the closer proximity:
     *  close, war, hostility and deception 1 x less; far, friendliness 1 x more and those 2 x less; distant, friendliness 2 x and
     *  those 4 x less.
     */
    fun addEarlyGameWeights(civ: Civilization, other: Civilization, deception: Boolean, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        if (civ.getEra().eraNumber >= rules.define(AiDefine.RENAISSANCE_ERA)) return
        if (CompetitorAi.isEarlyGameCompetitor(civ, other, rules)) {
            if (DiplomacyTurn.proximity(civ, other) < DiplomacyTurn.PROXIMITY_CLOSE) return
            fun aggression(times: Int, deceptive: Boolean) {
                w[WAR] += bias(MajorCivApproach.War) * times
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * times
                if (deceptive && deception) w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * times
            }
            aggression(2, deceptive = true)
            if (civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 7 || civ.ai.diplomacyValue(DiplomacyPersonality.Meanness) > 7 ||
                civ.ai.isConqueror() || civ.ai.hasTraitCategory(TraitCategory.Warmonger)) aggression(2, deceptive = true)
            if (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ)) aggression(2, deceptive = false)
            return
        }
        w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
        w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 2
        w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * 2
        if (civ.ai.isScientist()) w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 2
        else w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
        val (friendly, lower) = when (maxOf(DiplomacyTurn.proximity(civ, other), DiplomacyTurn.proximity(other, civ))) {
            DiplomacyTurn.PROXIMITY_CLOSE -> 0 to 1
            DiplomacyTurn.PROXIMITY_FAR -> 1 to 2
            DiplomacyTurn.PROXIMITY_DISTANT -> 2 to 4
            else -> 0 to 0
        }
        w[FRIENDLY] += bias(MajorCivApproach.Friendly) * friendly
        for (i in intArrayOf(WAR, HOSTILE, DECEPTIVE)) w[i] -= bias(MajorCivApproach.entries[i]) * lower
    }

    /**
     *  GRAND STRATEGY WEIGHT, domination. Competing for victory, from the Medieval era (or close to world conquest, with 4 majors
     *  alive or fewer, or holding another major's original capital), going for world conquest or holding such a capital: war by our
     *  boldness and work-against willingness and hostility by meanness and denounce willingness (VP's x 50 flat: / 2 here); war and
     *  hostility 2 x bias and deception (when it applies) 1 x per capital we hold. Against one holding another's capital: close to
     *  world conquest, war and hostility 5 x per capital they hold; else, if they keep their own, war and guard 1 x per capital and
     *  friendliness 5 x less 1 x per capital; an easy target 5 x war more. Close to world conquest, war and hostility 5 x, war 5 x more
     *  against an easy target. Not yet close, friendship with the strong: towards one above average military strength friendliness,
     *  neutrality and guard up and war, hostility and deception down by 2 x their strength over average (+1 with a defensive pact, +1
     *  as a difficult target or worse, +2 when we and they backstabbed friends and they are not untrustworthy); towards a weaker one
     *  the opposite by 2 x their strength under average. Open borders from them without a land dispute: friendliness 2 x when we
     *  think them favourable or better, else neutrality 2 x.
     */
    fun addDominationWeights(civ: Civilization, other: Civilization, deception: Boolean, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        if (!VictoryProgressAi.isCompetingForVictory(civ)) return
        val conquered = VictoryProgressAi.numMajorsConquered(civ)
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        val majorsAlive = civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() }
        if (civ.getEra().eraNumber < rules.define(AiDefine.MEDIEVAL_ERA) && !closeToConquest && majorsAlive > 4 && conquered <= 0) return
        if (!civ.ai.isGoingForWorldConquest() && conquered <= 0) return
        val ai = civ.ai
        w[WAR] += (ai.diplomacyValue(DiplomacyPersonality.Boldness) + ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)) * 50 / 100
        w[HOSTILE] += (ai.diplomacyValue(DiplomacyPersonality.Meanness) + ai.diplomacyValue(DiplomacyPersonality.DenounceWillingness)) * 50 / 100
        if (conquered > 0) {
            w[WAR] += bias(MajorCivApproach.War) * conquered * 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * conquered * 2
            if (deception) w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * conquered
        }
        val easy = WarStateAi.isEasyTarget(civ, other)
        val theirCapitals = VictoryProgressAi.capitalsConquered(other)
        if (theirCapitals > 0) {
            if (closeToConquest) {
                w[WAR] += bias(MajorCivApproach.War) * 5 * theirCapitals
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * 5 * theirCapitals
            } else if (!VictoryProgressAi.hasLostCapital(other)) {
                w[WAR] += bias(MajorCivApproach.War) * theirCapitals
                w[GUARDED] += bias(MajorCivApproach.Guarded) * theirCapitals
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 5 - bias(MajorCivApproach.Friendly) * theirCapitals
            }
            if (easy) w[WAR] += bias(MajorCivApproach.War) * 5
        }
        if (closeToConquest) {
            w[WAR] += bias(MajorCivApproach.War) * 5
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 5
            if (easy) w[WAR] += bias(MajorCivApproach.War) * 5
        } else {
            val strength = WarStateAi.rawMilitaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
            var factor = (strength.ordinal - StrengthLevel.Average.ordinal) * 2
            if (strength > StrengthLevel.Average) {
                if (civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact) factor++
                // They are a difficult target for now: aim to build up our strength
                if ((WarStateAi.rawTargetValue(civ, other) ?: TargetValue.Average) <= TargetValue.Difficult) factor++
                if (WarSanityAi.isBackstabber(civ) && OpinionAi.weDeclaredWarOnFriendCount(other) > 0 && !OpinionAi.isUntrustworthyFriend(other))
                    factor += 2
            }
            // Above average: friendship with the strong; below: the opposite (factor < 0)
            for (i in intArrayOf(FRIENDLY, NEUTRAL, GUARDED)) w[i] += bias(MajorCivApproach.entries[i]) * factor
            for (i in intArrayOf(WAR, HOSTILE, DECEPTIVE)) w[i] -= bias(MajorCivApproach.entries[i]) * factor
        }
        if (civ.getDiplomacyManager(other)?.hasOpenBorders == true && ai.diplomacy.memoryOf(other).landDispute == DisputeLevel.None) {
            if (ai.diplomacy.opinionOf(other) >= MajorCivOpinion.Favorable) w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
            else w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 2
        }
    }
}
