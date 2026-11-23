package com.unciv.logic.civilization.diplomacy

import com.unciv.models.ModConstants
import yairm210.purity.annotations.Readonly

/**
 *  The numbers of a rigged election of a city-state (CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15145-15386) as pure functions, in the original's integer order: every step
 *  multiplies, then divides by 100 and truncates, so that the results are the original's and not a float's. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Only with
 *  [ModConstants.espionageLikeVp]. [modifier] is the percent the winner's policies add (`RigElectionInfluenceModifier`, Covert Action) and its traits (0 in Vox Populi).
 */
object CityStateRigging {
    /**
     *  The influence the winner gets, in whole points: [ModConstants.EspionageConstants.riggingInfluenceGained] times (100 + [modifier]) / 100, times the winner's own era (at least 1), times
     *  (100 + the percent per election in a row x [streak]) / 100 (V:CvMinorCivAI.cpp:15221-15235). [streak] is the number of elections rigged in a row BEFORE this one
     */
    @Readonly
    fun winnerInfluence(constants: ModConstants.EspionageConstants, modifier: Int, winnerEra: Int, streak: Int): Int {
        var value = constants.riggingInfluenceGained
        value *= 100 + modifier
        value /= 100
        value *= maxOf(winnerEra, 1)
        value *= 100 + constants.riggingConsecutiveModifierPercent * streak
        value /= 100
        return value
    }

    /**
     *  What a major that did not win loses, in hundredths of influence: [ModConstants.EspionageConstants.riggingInfluenceLost] x 100 x (100 + [modifier]) / 100 x the game's era, but never more than
     *  the influence it has ([influenceTimes100]); 0 for a major with no influence (V:CvMinorCivAI.cpp:15316-15326). The modifier is the WINNER's, as in the original
     */
    @Readonly
    fun loserLossTimes100(constants: ModConstants.EspionageConstants, modifier: Int, gameEra: Int, influenceTimes100: Int): Int {
        if (influenceTimes100 <= 0) return 0
        var amount = constants.riggingInfluenceLost * 100
        amount *= 100 + modifier
        amount /= 100
        amount *= gameEra
        return minOf(amount, influenceTimes100)
    }

    /** The votes a rigging spy adds each turn (CalcPerTurn(SPY_STATE_RIG_ELECTION), V:CvEspionageClasses.cpp:3099-3102): its rank (ours counts from 1, the original's `rank + 1`) times the votes per rank */
    @Readonly
    fun votesPerTurn(constants: ModConstants.EspionageConstants, rank: Int, offensiveStrengthPercent: Int = 0): Int =
        rank * (constants.riggingVotesPerRank + offensiveStrengthPercent)
}
