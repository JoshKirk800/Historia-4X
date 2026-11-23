package com.unciv.logic.civilization.diplomacy

import com.unciv.models.ModConstants
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.sqrt

/**
 *  Vox Populi's demand for tribute from a city-state as pure functions of their inputs (CvMinorCivAI::CalculateBullyScore, V:CvMinorCivAI.cpp:14015-14400, GetBullyGoldAmount,
 *  13969-14012, CanMajorBullyGold and CanMajorBullyUnit, 14403-14520, DoMajorBullyGold, 14603-14750), used with [ModConstants.cityStateBullyLikeVp]. V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The integer arithmetic is the source's, in the same order.
 */
object CityStateBully {
    /**
     *  What [terms] needs of the game: [globalMilitaryPercent] the percent that the civilization's military might is of the might of all living majors with cities (`100 x might / max(1, total)`),
     *  [bullyPower] and [cityStatePower] the unit power of the demanding civilization and of the city-state, its capital's included, near the city-state's capital (the latter at least 1),
     *  [militaryModifier] the percent that the traits add to both military parts (the Zulu's 50), [policyModifier] the percent of the sum that the policies add, [turnsSinceBullied] the
     *  turns since the civilization's last tribute from it (null: never), [trainPercent] the game speed's production cost percentage, [hasOtherAlly] whether another civilization is its ally,
     *  [otherProtectors] the other civilizations that protect it, [ignorePenalties] the trait of the Zulu, the [hostile] personality and [militaristic] type of the city-state and
     *  whether it was [recentlyLiberated] (V:14230-14246).
     */
    class Inputs(
        val globalMilitaryPercent: Int, val bullyPower: Int, val cityStatePower: Int, val militaryModifier: Int, val policyModifier: Int, val turnsSinceBullied: Int?,
        val trainPercent: Int, val hasOtherAlly: Boolean, val otherProtectors: Int, val ignorePenalties: Boolean, val hostile: Boolean, val militaristic: Boolean,
        val recentlyLiberated: Boolean = false
    )

    /** The terms of the score by their names (the table of the tribute screen), their sum capped is [score]. A tribute too soon after the last is the only term, -1000 (V:14056-14070) */
    @Readonly
    fun terms(constants: ModConstants.CityStateBullyConstants, i: Inputs): LinkedHashMap<String, Int> {
        val terms = LinkedHashMap<String, Int>()
        val train = max(1, i.trainPercent)
        val blockedTurns = constants.blockedTurns * train / 100
        val turnsSince = i.turnsSinceBullied
        if (turnsSince != null && turnsSince < max(blockedTurns, 1)) {
            terms["Bullied very recently"] = -1000
            return terms
        }

        // the global military score: the share of the might, half of it (V:14090-14108)
        val global = i.globalMilitaryPercent * constants.globalMilitaryShare / 100 * (100 + i.militaryModifier) / 100
        terms["Military Rank"] = global

        // the local one: the power ratio in percent, linear if the bully is weaker and the square root of it if it is stronger (V:14122-14156)
        val ours = max(i.cityStatePower, 1)
        val ratio = if (i.bullyPower > ours) sqrt((10000L * i.bullyPower / ours).toDouble()).toInt() else 100 * i.bullyPower / ours
        val local = ratio * (100 + i.militaryModifier) / 100
        terms["Military near City-State"] = local

        // the policies, on the two military parts (V:14170-14180)
        val policies = (global + local) * i.policyModifier / 100
        if (policies != 0) terms["From Uniques"] = policies

        if (i.recentlyLiberated) terms["Recently liberated"] = -constants.liberatedPenalty
        if (turnsSince != null) {
            val cooldown = constants.cooldownTurns * train / 100
            if (turnsSince < cooldown) terms["Recently paid tribute"] = (cooldown - turnsSince) * -constants.recentlyBulliedPerTurn
        }
        if (!i.ignorePenalties) {
            if (i.hasOtherAlly) terms["Has Ally"] = -constants.allyPenalty
            if (i.otherProtectors > 0) terms["Has Protector"] = -constants.protectorPenalty * i.otherProtectors
        }
        if (i.hostile) terms["Hostile"] = -constants.hostilePenalty
        if (i.militaristic) terms["Militaristic"] = -constants.militaristicPenalty
        return terms
    }

    /** CvMinorCivAI::CalculateBullyScore: the sum of the [terms], at most the cap (V:14395-14398) */
    @Readonly
    fun score(constants: ModConstants.CityStateBullyConstants, i: Inputs): Int = scoreOf(constants, terms(constants, i))

    /** The score of the [terms] of [terms] */
    @Readonly
    fun scoreOf(constants: ModConstants.CityStateBullyConstants, terms: Map<String, Int>): Int = terms.values.sum().coerceAtMost(constants.scoreCap)

    /** CvMinorCivAI::CanMajorBullyGold with MOD_BALANCE_HEAVY_TRIBUTE: above 0 and at the gold threshold (V:14438-14440) */
    @Readonly
    fun canBullyGold(constants: ModConstants.CityStateBullyConstants, score: Int) = score > 0 && score >= constants.goldThreshold

    /** CvMinorCivAI::CanMajorBullyUnit: the heavy tribute threshold (V:14515-14518) */
    @Readonly
    fun canBullyHeavy(constants: ModConstants.CityStateBullyConstants, score: Int) = score >= constants.heavyThreshold

    /**
     *  CvMinorCivAI::GetBullyGoldAmount (V:13969-14012): the base and what the game's progress ([progress], 0 to 1, the turns over the speed's turns) adds, times the era of the
     *  demanding civilization (at least 1) plus a share of itself, times the speed's instant yield percentage, the trait's [valueModifier] and the [score] in percent; never below 0
     */
    @Readonly
    fun goldAmount(constants: ModConstants.CityStateBullyConstants, progress: Float, era: Int, instantYieldPercent: Int, valueModifier: Int, score: Int): Int {
        var gold = constants.goldBase + (progress.coerceIn(0f, 1f) * constants.goldGrowth).toInt()
        val eraNumber = max(era, 1)
        gold = gold * eraNumber + gold / (eraNumber + 2)
        gold = gold * instantYieldPercent / 100
        gold = gold * (100 + valueModifier) / 100
        gold = gold * score / 100
        return max(gold, 0)
    }

    /**
     *  The change of the influence a demand makes, in hundredths (V:14659-14667, 14976): the drop of the demand, and for gold what the civilization has over its resting point change
     *  ([restingChangeTimes100], what a Great Diplomat or a liberation adds to the resting point) as well. The drop of a heavy tribute is its own.
     */
    @Readonly
    fun influenceChangeTimes100(constants: ModConstants.CityStateBullyConstants, restingChangeTimes100: Int, currentTimes100: Int, heavy: Boolean = false): Int {
        if (heavy) return -constants.dropHeavyTimes100
        var change = -constants.dropGoldTimes100
        if (currentTimes100 >= restingChangeTimes100) change -= currentTimes100 - restingChangeTimes100
        return change
    }
}
