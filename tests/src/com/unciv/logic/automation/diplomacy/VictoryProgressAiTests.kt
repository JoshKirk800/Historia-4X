package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Domination progress after Vox Populi's CvDiplomacyAI::GetDominationVictoryProgress and IsCloseToWorldConquest (Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class VictoryProgressAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.gameInfo.gameParameters.victoryTypes.add("Domination")
        a = civWithCapital(HexCoord(0, 0))
        b = civWithCapital(HexCoord(8, 0))
        c = civWithCapital(HexCoord(0, 8))
        d = civWithCapital(HexCoord(-8, 0))
    }

    private fun civWithCapital(position: HexCoord): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(position))
        return civ
    }

    @Test
    fun `progress is the share of the original capitals held, rounded down`() {
        assertEquals("one of four capitals and no might anywhere", 25, VictoryProgressAi.dominationVictoryProgress(a))
        b.cities.first().moveToCiv(a)
        assertEquals("a capture counts at once", 50, VictoryProgressAi.dominationVictoryProgress(a))
        c.cities.first().moveToCiv(a)
        assertEquals(75, VictoryProgressAi.dominationVictoryProgress(a))
    }

    @Test
    fun `progress is the share of the military might when that is larger`() {
        for (x in 1..3) game.addUnit("Swordsman", a, game.getTile(HexCoord(x, -1)))
        game.addUnit("Swordsman", b, game.getTile(HexCoord(9, 0)))
        val total = listOf(a, b, c, d).sumOf { it.ai.militaryMight() }
        assertEquals(a.ai.militaryMight() * 100 / total, VictoryProgressAi.dominationVictoryProgress(a))
        assertTrue("three of four equal units", VictoryProgressAi.dominationVictoryProgress(a) >= 74)
        assertEquals("b's capital share is larger than its might share", 25, VictoryProgressAi.dominationVictoryProgress(b))
    }

    @Test
    fun `a razed capital counts for no one and a civilization without cities adds no might`() {
        game.addUnit("Swordsman", d, game.getTile(HexCoord(-7, 0)))
        game.addUnit("Swordsman", a, game.getTile(HexCoord(1, 0)))
        d.cities.first().destroyCity(overrideSafeties = true)
        assertEquals("one of three standing capitals; d's unit is not counted, so a holds all the might", 100,
            VictoryProgressAi.dominationVictoryProgress(a))
    }

    @Test
    fun `no progress when no open victory is won by capturing the capitals`() {
        b.cities.first().moveToCiv(a)
        game.gameInfo.gameParameters.victoryTypes.remove("Domination")
        assertEquals(0, VictoryProgressAi.dominationVictoryProgress(a))
    }

    @Test
    fun `close to world conquest from the threshold of 50 up`() {
        assertEquals(50, game.ruleset.ai.define(AiDefine.CLOSE_TO_DOMINATION_VICTORY_THRESHOLD))
        assertFalse("25", VictoryProgressAi.isCloseToWorldConquest(a, game.ruleset.ai))
        b.cities.first().moveToCiv(a)
        assertEquals(50, VictoryProgressAi.dominationVictoryProgress(a))
        assertTrue("exactly the threshold", VictoryProgressAi.isCloseToWorldConquest(a, game.ruleset.ai))
        assertFalse(VictoryProgressAi.isCloseToWorldConquest(c, game.ruleset.ai))
    }
}
