package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The pledge to protect a city-state as Vox Populi has it with [ModConstants.cityStateProtectionLikeVp]: `CanMajorProtect` (V:CvMinorCivAI.cpp:12367-12490), `CanMajorWithdrawProtection`
 *  (12498-12520), `DoChangeProtectionFromMajor` (12291-12365) and `TestChangeProtectionFromMajor` (11979-12145). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
@RunWith(BaseTestRunner::class)
class CityStateProtectionTests {
    private val constants = ModConstants.CityStateProtectionConstants()

    @Test
    fun `the military requirement is the median of might over distance, the lower of two, and equal is enough`() {
        // [1, 3, 5, 9]: the element at 4 / 2 = 2 is 5
        assertFalse(CityStateProtection.meetsMilitaryRequirement(listOf(9, 1, 5, 3), 4))
        assertTrue(CityStateProtection.meetsMilitaryRequirement(listOf(9, 1, 5, 3), 5))
        assertTrue("nobody to compare with", CityStateProtection.meetsMilitaryRequirement(emptyList(), 1))
        assertEquals("the strength is the might over the distance, whole", 12, CityStateProtection.strength(100, 8))
        assertEquals("on the capital's tile the distance is 1", 100, CityStateProtection.strength(100, 0))
    }

    @Test
    fun `the warning begins at once, counts up, reminds every 4 turns, and ends the pledge after 12 turns`() {
        fun step(turns: Int, bad: Boolean = false) = CityStateProtection.warningStep(constants, turns, 100)
        assertEquals(CityStateProtection.Warning.Begin, step(0))
        assertEquals(CityStateProtection.Warning.Continue, step(1))
        assertEquals(CityStateProtection.Warning.Continue, step(3))
        assertEquals(CityStateProtection.Warning.Remind, step(4))
        assertEquals(CityStateProtection.Warning.Remind, step(12))
        assertEquals(CityStateProtection.Warning.End, step(13))
        // Marathon: 12 x 300 / 100 = 36 turns
        assertEquals(CityStateProtection.Warning.Remind, CityStateProtection.warningStep(constants, 36, 300))
        assertEquals(CityStateProtection.Warning.End, CityStateProtection.warningStep(constants, 37, 300))
    }

    // region the game

    private val game = TestGame()
    private val cityState: Civilization = game.addCiv(cityStateType = "Cultured")
    private val major: Civilization = game.addCiv()

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        game.addCity(cityState, game.getTile(HexCoord.Zero))
        game.addCity(major, game.getTile(HexCoord(-5, 0)))
        game.addUnit("Warrior", major, game.getTile(HexCoord(-5, 1)))
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        game.ruleset.modOptions.constants.cityStateProtectionLikeVp = true
        cityState.cityStatePersonality = CityStatePersonality.Neutral
    }

    private val toMajor get() = cityState.getDiplomacyManager(major)!!
    private fun makeAlly() { toMajor.setInfluence(70f); assertTrue(cityState.allyCiv == major) }

    @Test
    fun `a major cannot pledge below 0 influence`() {
        makeAlly()
        toMajor.setInfluenceWithoutSideEffects(-1f)
        assertFalse(cityState.cityStateFunctions.otherCivCanPledgeProtection(major))
        toMajor.setInfluence(10f)
        // 10, neither ally, nor connected, nor able to make a route
        assertFalse(cityState.cityStateFunctions.otherCivCanPledgeProtection(major))
    }

    @Test
    fun `the ally can pledge and cannot withdraw for 50 turns`() {
        makeAlly()
        game.gameInfo.turns = 3
        assertTrue(cityState.cityStateFunctions.otherCivCanPledgeProtection(major))
        cityState.cityStateFunctions.addProtectorCiv(major)
        assertEquals(DiplomaticStatus.Protector, toMajor.diplomaticStatus)
        assertEquals(3, toMajor.turnLastPledged)

        game.gameInfo.turns = 52
        assertFalse(cityState.cityStateFunctions.otherCivCanWithdrawProtection(major))
        game.gameInfo.turns = 53
        assertTrue(cityState.cityStateFunctions.otherCivCanWithdrawProtection(major))
        cityState.cityStateFunctions.removeProtectorCiv(major)
        assertEquals("a withdrawal costs nothing", 70f, toMajor.getInfluence(), 0.001f)
        assertEquals(DiplomaticStatus.Peace, toMajor.diplomaticStatus)
    }

    @Test
    fun `a pledge that fails its test is broken after 12 turns, costs 20 influence for the era and locks the pledge for 20 turns`() {
        makeAlly()
        cityState.cityStateFunctions.addProtectorCiv(major)
        toMajor.setInfluence(10f) // no longer the ally

        // the first test starts the warning at 1; it counts to 12 and ends the pledge at 13
        game.gameInfo.turns = 60
        repeat(13) { cityState.cityStateFunctions.testProtection(major) }
        assertEquals(DiplomaticStatus.Protector, toMajor.diplomaticStatus)
        cityState.cityStateFunctions.testProtection(major)

        assertEquals(DiplomaticStatus.Peace, toMajor.diplomaticStatus)
        assertEquals("era 1: -20", -10f, toMajor.getInfluence(), 0.001f)
        assertEquals(60, toMajor.turnLastPledgeBroken)
        makeAlly()
        game.gameInfo.turns = 79
        assertFalse(cityState.cityStateFunctions.otherCivCanPledgeProtection(major))
        game.gameInfo.turns = 80
        assertTrue(cityState.cityStateFunctions.otherCivCanPledgeProtection(major))
    }

    @Test
    fun `the warning stops when the major qualifies again`() {
        makeAlly()
        cityState.cityStateFunctions.addProtectorCiv(major)
        toMajor.setInfluence(10f)
        repeat(3) { cityState.cityStateFunctions.testProtection(major) }
        assertEquals(3, toMajor.turnsSincePledgeWarning)
        makeAlly()
        cityState.cityStateFunctions.testProtection(major)
        assertEquals(0, toMajor.turnsSincePledgeWarning)
        assertEquals(DiplomaticStatus.Protector, toMajor.diplomaticStatus)
    }

    @Test
    fun `influence below 0 ends the pledge at once with the same penalty`() {
        makeAlly()
        cityState.cityStateFunctions.addProtectorCiv(major)
        toMajor.setInfluenceWithoutSideEffects(-5f)
        cityState.cityStateFunctions.testProtection(major)
        assertEquals(DiplomaticStatus.Peace, toMajor.diplomaticStatus)
        assertEquals(-25f, toMajor.getInfluence(), 0.001f)
    }

    @Test
    fun `without the switch the pledge is Brave New World's`() {
        game.ruleset.modOptions.constants.cityStateProtectionLikeVp = false
        toMajor.setInfluence(10f)
        assertTrue(cityState.cityStateFunctions.otherCivCanPledgeProtection(major))
    }

    // endregion
}
