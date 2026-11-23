package com.unciv.logic.civilization

import com.unciv.models.ModConstants
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's spy points (`CvPlayer::CreateSpies` with `MOD_BALANCE_SPY_POINTS`, V:CvPlayer.cpp:23292-23338, the threshold `CvGame::initSpyThreshold`, V:CvGame.cpp:10722-10767). V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. A spy comes from the points a building, wonder or policy gives (`Gain [n] Spy Points`); the points below the threshold are kept, and a negative
 *  amount is a debt that the next points pay first. Only with [ModConstants.spyPointsLikeVp].
 */
@RunWith(BaseTestRunner::class)
class SpyPointsTests {
    private val game = TestGame()
    private val civ = game.addCiv()
    private val other = game.addCiv()

    @Before
    fun setUp() {
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(3)
        game.ruleset.modOptions.constants.spyPointsLikeVp = true
    }

    private fun trigger(text: String, target: Civilization = civ) = UniqueTriggerActivation.triggerUnique(Unique(text), target)
    private val manager get() = civ.espionageManager

    @Test
    fun `100 points create one spy and 99 do not`() {
        assertTrue(trigger("Gain [99] Spy Points"))
        assertEquals(0, manager.spyList.size)
        assertEquals(99, manager.spyPoints)

        assertTrue(trigger("Gain [1] Spy Points"))
        assertEquals(1, manager.spyList.size)
        assertEquals(0, manager.spyPoints)
        assertEquals("the total is never reduced by creating a spy (V:CvPlayer.cpp:23286-23290)", 100, manager.spyPointsTotal)
    }

    @Test
    fun `60 points twice create one spy and keep the remainder`() {
        trigger("Gain [60] Spy Points")
        trigger("Gain [60] Spy Points")
        assertEquals(1, manager.spyList.size)
        assertEquals(20, manager.spyPoints)
        assertEquals(120, manager.spyPointsTotal)
    }

    @Test
    fun `more points than the threshold create several spies`() {
        trigger("Gain [250] Spy Points")
        assertEquals(2, manager.spyList.size)
        assertEquals(50, manager.spyPoints)
    }

    @Test
    fun `a negative amount is a debt that the next points pay first and creates no spy`() {
        trigger("Gain [-50] Spy Points")
        assertEquals(-50, manager.spyPoints)
        assertEquals(0, manager.spyList.size)

        trigger("Gain [100] Spy Points")
        assertEquals("100 - 50 = 50 is below the threshold", 0, manager.spyList.size)
        assertEquals(50, manager.spyPoints)
    }

    @Test
    fun `Gain an extra spy gives one spy whatever the threshold and keeps the points`() {
        trigger("Gain [30] Spy Points")
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(thresholdMin = 40, thresholdMax = 40)

        assertTrue(trigger("Gain an extra spy"))

        assertEquals(1, manager.spyList.size)
        assertEquals("CreateSpies(1, false) adds one threshold of points (40) to the 30: 70 - 40", 30, manager.spyPoints)
        assertEquals(70, manager.spyPointsTotal)
    }

    @Test
    fun `the threshold is the ratio over the players, clamped to the bounds`() {
        // 2 majors (civ, other) and one city-state: 100 x 1 / (1 + 2 x 2) = 20
        game.addCiv(cityStateType = "Cultured")
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(spyToPlayerRatio = 1, thresholdMin = 10, thresholdMax = 100)
        assertEquals(20, manager.spyThreshold())

        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(spyToPlayerRatio = 1, thresholdMin = 33, thresholdMax = 100)
        assertEquals("clamped up to the minimum", 33, manager.spyThreshold())

        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(spyToPlayerRatio = 20, thresholdMin = 33, thresholdMax = 100)
        assertEquals("100 x 20 / 5 = 400, clamped down to the maximum", 100, manager.spyThreshold())

        // Vox Populi's own numbers have equal bounds: the threshold is 100 for every map size
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants()
        assertEquals(100, manager.spyThreshold())
    }

    @Test
    fun `without the switch Gain an extra spy is one spy and spy points do nothing`() {
        game.ruleset.modOptions.constants.spyPointsLikeVp = false

        assertTrue(trigger("Gain an extra spy"))
        assertEquals(1, manager.spyList.size)
        assertEquals(0, manager.spyPointsTotal)

        assertFalse("Gain [n] Spy Points is a rule of the switch", trigger("Gain [100] Spy Points"))
        assertEquals(1, manager.spyList.size)
        assertEquals(0, manager.spyPoints)
    }

    @Test
    fun `no spy points without espionage in the game`() {
        game.gameInfo.gameParameters.espionageEnabled = false
        assertFalse(trigger("Gain [100] Spy Points"))
        assertEquals(0, manager.spyList.size)
        assertEquals(0, manager.spyPointsTotal)
    }

    @Test
    fun `spy points are part of the saved state and its copy`() {
        trigger("Gain [-30] Spy Points")
        val copy = manager.clone()
        assertEquals(-30, copy.spyPoints)
        assertEquals(-30, copy.spyPointsTotal)
        assertEquals("a manager that was never written to (an old save) has none", 0, other.espionageManager.spyPoints)
    }

    @Test
    fun `a killed spy comes back after the respawn turns of the constants`() {
        val spy = manager.addSpy()
        spy.killSpy()
        assertEquals(SpyAction.Dead, spy.action)
        assertEquals("Brave New World's 5 turns are the default", 5, spy.turnsRemainingForAction)

        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(respawnTurns = 10)
        val second = manager.addSpy()
        second.killSpy()
        assertEquals("BALANCE_SPY_RESPAWN_TIMER (V DB 10)", 10, second.turnsRemainingForAction)
    }
}
