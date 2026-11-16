package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's war progress score (CvDiplomacyAI::DoUpdateWarProgressScores and the events that change it, Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class WarProgressTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = game.addCiv()
        b = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(8, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.declareWar()
    }

    private fun progress(civ: Civilization, other: Civilization) = civ.ai.diplomacy.memoryOf(other).warProgress

    @Test
    fun `a war starts at 100 for both, a kill counts 20 for the killer and -10 for the loser`() {
        assertEquals(100, progress(a, b))
        assertEquals(100, progress(b, a))
        WarProgressAi.onUnitKilled(b, a)
        assertEquals(120, progress(a, b))
        assertEquals(90, progress(b, a))
    }

    @Test
    fun `each turn the score fades faster against a stronger enemy`() {
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Powerful
        WarProgressAi.doTurn(a, listOf(b))
        assertEquals(95, progress(a, b))
    }

    @Test
    fun `taking their original capital counts twice`() {
        WarProgressAi.onCityConquered(a, b, b.cities.first())
        assertEquals(300, progress(a, b))
        assertEquals(0, progress(b, a))
    }
}
