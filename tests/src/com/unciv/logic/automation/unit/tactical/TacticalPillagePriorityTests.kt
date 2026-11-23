package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Resource pillage priorities after Vox Populi's CvTacticalAI::FindTacticalTargets. */
@RunWith(BaseTestRunner::class)
class TacticalPillagePriorityTests {
    @Test
    fun `a single raider pillages a strategic resource before a luxury`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        val civ = game.addCiv()
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        val home = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        val enemyCity = game.addCity(enemy, game.getTile(HexCoord(0, 8)))
        val candidates = game.tileMap.tileList.filter {
            it.aerialDistanceTo(home.getCenterTile()) in 3..5 && it.aerialDistanceTo(enemyCity.getCenterTile()) in 3..5
        }.take(2)
        val luxury = candidates[0]
        val strategic = candidates[1]
        for (tile in candidates) tile.setOwningCity(enemyCity)
        luxury.setTileResource("Gold")
        luxury.setImprovement("Mine")
        strategic.setTileResource("Iron")
        strategic.setImprovement("Mine")
        val raider = game.addUnit("Paratrooper", civ, home.getCenterTile())
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        val ctx = TacticalContext(civ, map, danger, AutomationCache(), listOf(raider), TacticalTargets.find(civ, map))

        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, true)

        assertTrue("the strategic resource is pillaged", strategic.improvementIsPillaged)
        assertFalse("the luxury is left for a later unit", luxury.improvementIsPillaged)
    }
}
