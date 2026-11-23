package com.unciv.logic.civilization.diplomacy

import com.unciv.models.ModConstants
import yairm210.purity.annotations.Readonly
import kotlin.math.max

/**
 *  The tests of Vox Populi's pledge to protect a city-state as pure functions (CvMinorCivAI::CanMajorProtect, V:CvMinorCivAI.cpp:12367-12490, TestChangeProtectionFromMajor,
 *  11979-12145), used with [ModConstants.cityStateProtectionLikeVp]. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
object CityStateProtection {
    /** The military strength of a major for the pledge: its military might over the distance of its closest city to the city-state's capital (at least 1), cut off (V:12420-12432) */
    @Readonly
    fun strength(militaryMight: Int, distance: Int) = militaryMight / max(1, distance)

    /**
     *  Whether a major of [majorStrength] ([strength], or 1 if it is 0) is strong enough: not below the median of the [strengths] of all the living majors with cities, the element at
     *  half their number once sorted, which is the lower of the two when there is no single median (V:12434-12445)
     */
    @Readonly
    fun meetsMilitaryRequirement(strengths: List<Int>, majorStrength: Int): Boolean {
        if (strengths.isEmpty()) return true
        return max(majorStrength, 1) >= strengths.sorted()[strengths.size / 2]
    }

    /** What the yearly test of a pledge that no longer qualifies does next (V:12080-12140) */
    enum class Warning { Begin, Continue, Remind, End }

    /**
     *  The step of the test of a pledge that does not qualify, [turnsSinceWarning] turns after it began (0: not yet): after more than the warning turns of the constants (times the game
     *  speed's production cost percentage [trainPercent]) the pledge ends; at 0 the warning begins; every few turns after that there is a reminder
     */
    @Readonly
    fun warningStep(constants: ModConstants.CityStateProtectionConstants, turnsSinceWarning: Int, trainPercent: Int): Warning {
        val maxTurns = constants.warningTurns * max(1, trainPercent) / 100
        return when {
            turnsSinceWarning > maxTurns -> Warning.End
            turnsSinceWarning <= 0 -> Warning.Begin
            turnsSinceWarning % constants.reminderTurns == 0 -> Warning.Remind
            else -> Warning.Continue
        }
    }
}
