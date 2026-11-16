package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's timed opinion deeds (CvDiplomacyAI::AdjustTimedModifier, TestOpinionModifiers and the deed scores, Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class OpinionDeedsTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        a = game.addCiv()
        b = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(6, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    @Test
    fun `a standard term fades with the share of its duration gone, and is gone at its end`() {
        assertEquals(100, OpinionTimers.adjust(100, 50, 10, 10, OpinionTimers.Type.Standard))
        assertEquals(50, OpinionTimers.adjust(100, 50, 10, 35, OpinionTimers.Type.Standard))
        assertEquals("never below 1 before the end", 1, OpinionTimers.adjust(10, 50, 10, 59, OpinionTimers.Type.Standard))
        assertEquals(0, OpinionTimers.adjust(100, 50, 10, 60, OpinionTimers.Type.Standard))
    }

    @Test
    fun `stacked and diminishing terms fade towards half their stacks`() {
        // 3 stacks of 20, half the duration gone: towards 1 stack (20), half way: 40
        assertEquals(40, OpinionTimers.adjust(60, 50, 0, 25, OpinionTimers.Type.Stacked, 3))
        // -10 then -5 three times (4 stacks), half way towards 2 stacks (-15): -20
        assertEquals(-20, OpinionTimers.adjust(-25, 50, 0, 25, OpinionTimers.Type.Diminishing, 4, -10))
    }

    @Test
    fun `stacks halve once per duration passed`() {
        assertEquals(2, OpinionTimers.decayStacks(8, 0, 120, 50))
        assertEquals(8, OpinionTimers.decayStacks(8, 0, 49, 50))
    }

    @Test
    fun `a taken capital is held against the taker after it changes hands again`() {
        DiplomacyEvents.onCityConquered(b, a, a.cities.first())
        // the city never moved here: only the memory of the capture counts
        assertEquals(160, OpinionAi.capitalCapturedScore(a, b, game.ruleset.ai))
        assertEquals("no credit for liberating cities of a capital taker", 0, OpinionAi.liberatedCitiesScore(a, b, game.ruleset.ai))
    }

    @Test
    fun `robberies fade with forgiveness and their count halves when the duration has passed`() {
        a.ai.setDiplomacyValue(DiplomacyPersonality.Forgiveness, 5)
        DiplomacyEvents.onRobbed(a, b)
        DiplomacyEvents.onRobbed(a, b)
        val memory = a.ai.diplomacy.memoryOf(b)
        assertEquals(40, OpinionAi.robbedScore(a, b, game.ruleset.ai))
        val duration = OpinionTimers.adjustDuration(a, 50, 5, invert = true)
        game.gameInfo.turns += duration
        OpinionAi.testOpinionModifiers(a, listOf(b), game.ruleset.ai)
        assertEquals("halved", 1, memory.numTimesRobbed)
        assertEquals("a new stamp", game.gameInfo.turns, memory.robbedTurn)
        assertEquals(20, OpinionAi.robbedScore(a, b, game.ruleset.ai))
    }
}
