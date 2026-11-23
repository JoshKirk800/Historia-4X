package com.unciv.logic.civilization.managers.quests

import com.unciv.models.ModConstants
import yairm210.purity.annotations.Readonly
import kotlin.math.sqrt
import kotlin.random.Random

/**
 *  When a city-state can give quests under [ModConstants.cityStateQuestsLikeVp]: CvMinorCivAI::GetFirstPossibleTurnForPersonalQuests and `ForGlobalQuests` (V:CvMinorCivAI.cpp:6226-6242),
 *  DoTestSeedGlobalQuestCountdown and `DoTestSeedQuestCountdownForPlayer` (7566-7660). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
object CityStateQuestSchedule {
    /**
     *  The first turn a quest can be given: the constant times the square root (whole) of the speed's great people percentage, over 10, cut off (4 on Standard, 6 on Marathon).
     *  [speedModifier] is the speed's multiplier, 1 on Standard
     */
    @Readonly
    fun firstPossibleTurn(constants: ModConstants.CityStateQuestConstants, speedModifier: Float): Int =
        constants.firstPossibleTurn * sqrt((speedModifier * 100).toInt().toDouble()).toInt() / 10

    /**
     *  The turns until the next quest: at the first possible turn 0 to [ModConstants.CityStateQuestConstants.firstTurnRandom] inclusive; otherwise the minimum between quests
     *  and 0 up to (not including) the random turns, which a hostile city-state multiplies; then the speed's percentage
     */
    fun countdown(constants: ModConstants.CityStateQuestConstants, global: Boolean, firstTime: Boolean, hostile: Boolean, speedModifier: Float, random: Random): Int {
        var turns: Int
        if (firstTime) turns = random.nextInt(constants.firstTurnRandom + 1)
        else {
            var span = if (global) constants.globalRandTurnsBetween else constants.personalRandTurnsBetween
            if (hostile) span = span * constants.hostileRandMultiplier / 100
            turns = (if (global) constants.globalMinTurnsBetween else constants.personalMinTurnsBetween) + if (span > 0) random.nextInt(span) else 0
        }
        turns = (turns * (speedModifier * 100).toInt()) / 100
        return turns
    }
}
