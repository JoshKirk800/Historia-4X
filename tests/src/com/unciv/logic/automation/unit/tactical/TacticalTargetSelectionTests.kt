package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Target discovery after Vox Populi's CvTacticalAI::FindTacticalTargets. */
@RunWith(BaseTestRunner::class)
class TacticalTargetSelectionTests {
    @Test
    fun `an occupied barbarian camp remains a camp target as well as an enemy unit target`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        val civ = game.addCiv()
        val barbarians = game.addBarbarianCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
        val camp = game.getTile(HexCoord(3, 0))
        camp.setImprovementBasic(Constants.barbarianEncampment)
        val defender = game.addUnit("Warrior", barbarians, camp)
        civ.viewableTiles = game.tileMap.values.toSet()
        val targets = TacticalTargets.find(civ, null, civ.ai.plotDanger).all.filter { it.tile == camp }

        assertTrue("the defender can be attacked", targets.any { it.unit == defender })
        assertTrue("the camp remains an objective", targets.any { it.type == TacticalTargetType.BarbarianCamp })
    }
}
