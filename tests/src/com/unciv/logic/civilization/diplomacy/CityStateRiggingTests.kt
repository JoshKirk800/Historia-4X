package com.unciv.logic.civilization.diplomacy

import com.unciv.models.ModConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *  The numbers of a rigged election (CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15145-15386; CvPlayerEspionage::CalcPerTurn(SPY_STATE_RIG_ELECTION), V:CvEspionageClasses.cpp:3084-3108). Every
 *  expectation is worked by hand in the original's order: multiply, divide by 100, truncate, at each step, so an implementation that rounds at the end gives other numbers.
 *  V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
class CityStateRiggingTests {
    private val constants = ModConstants.EspionageConstants()   // 30 gained, +40 percent a streak, 5 lost, 100 votes a rank

    @Test
    fun `a winner of the first election in the first era gets 30`() {
        assertEquals(30, CityStateRigging.winnerInfluence(constants, modifier = 0, winnerEra = 1, streak = 0))
    }

    @Test
    fun `the influence of a winner grows with its era`() {
        assertEquals("30 x 3", 90, CityStateRigging.winnerInfluence(constants, 0, 3, 0))
    }

    @Test
    fun `an era under 1 counts as 1`() {
        assertEquals("the ancient era is 0 and still pays 30 (V:CvMinorCivAI.cpp:15228-15231)", 30, CityStateRigging.winnerInfluence(constants, 0, 0, 0))
    }

    @Test
    fun `Covert Action adds half to the influence of a winner before the era multiplies it`() {
        // 30 x 150 / 100 = 45, x 3 = 135
        assertEquals(135, CityStateRigging.winnerInfluence(constants, 50, 3, 0))
    }

    @Test
    fun `each election rigged in a row adds 40 percent of the base`() {
        // 30 x 3 = 90; x (100 + 40 x 2) / 100 = 162
        assertEquals(162, CityStateRigging.winnerInfluence(constants, 0, 3, 2))
        assertEquals("one in a row: 90 x 140 / 100", 126, CityStateRigging.winnerInfluence(constants, 0, 3, 1))
    }

    @Test
    fun `every step divides by 100 and truncates, so the order of the steps matters`() {
        // the original's order: 30 x 133 / 100 = 3990 / 100 = 39, then x 2 = 78. With the era first it would be 30 x 2 = 60, x 133 / 100 = 7980 / 100 = 79
        assertEquals(78, CityStateRigging.winnerInfluence(constants, 33, 2, 0))
    }

    @Test
    fun `a loser loses 5 x 100 times the game's era in hundredths`() {
        assertEquals("500 x 2", 1000, CityStateRigging.loserLossTimes100(constants, 0, gameEra = 2, influenceTimes100 = 2000))
    }

    @Test
    fun `a loser never loses more than it has`() {
        assertEquals("500 x 3 = 1500, but only 500 is there", 500, CityStateRigging.loserLossTimes100(constants, 0, 3, 500))
    }

    @Test
    fun `the modifier of the winner makes the others lose more`() {
        // 500 x 150 / 100 = 750, x 2 = 1500
        assertEquals(1500, CityStateRigging.loserLossTimes100(constants, 50, 2, 3000))
    }

    @Test
    fun `a major with no influence, or at war, loses nothing`() {
        assertEquals(0, CityStateRigging.loserLossTimes100(constants, 0, 3, 0))
        assertEquals("at war the effective influence is -60", 0, CityStateRigging.loserLossTimes100(constants, 0, 3, -6000))
    }

    @Test
    fun `a rigging spy gathers its rank times 100 votes a turn`() {
        assertEquals(100, CityStateRigging.votesPerTurn(constants, 1))
        assertEquals(200, CityStateRigging.votesPerTurn(constants, 2))
        assertEquals(300, CityStateRigging.votesPerTurn(constants, 3))
        assertEquals("the offensive strength of a trait, 0 in Vox Populi, adds to the 100", 250, CityStateRigging.votesPerTurn(constants, 2, offensiveStrengthPercent = 25))
    }
}
