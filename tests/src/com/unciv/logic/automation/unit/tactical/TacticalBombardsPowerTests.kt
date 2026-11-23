package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CvTacticalAI::ScoreAssignments adds CvUnit::GetPower to the score of a candidate: of two units at the same distance the more powerful one is assigned */
@RunWith(GdxTestRunner::class)
class TacticalBombardsPowerTests {
    @Test
    fun theUnitWithTheHigherOriginalPowerIsAssigned() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        val civ = game.addCiv()
        val knight = game.addUnit("Knight", civ, game.getTile(HexCoord(0, 0)))
        val longswordsman = game.addUnit("Longswordsman", civ, game.getTile(HexCoord(1, 0)))
        // Vox Populi's table: Knight 117 (24^1.5) against Longswordsman 96 (21^1.5); the Longswordsman is listed first, so only the power picks the Knight
        assertTrue(knight.getAiPower() > longswordsman.getAiPower())

        val target = game.getTile(HexCoord(0, 3))
        val blocks = TacticalBombards.assign(
            mapOf(target to listOf(Candidate(longswordsman, 1, 3), Candidate(knight, 1, 3))), canLeaveOpenings = false)!!
        assertEquals(1, blocks.size)
        assertEquals(knight, blocks.single().unit)
    }
}
