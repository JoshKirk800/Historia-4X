package com.unciv.logic.automation.unit.tactical

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TacticalDominanceRegressionTests {
    @Test
    fun `an undefended city has friendly dominance from its own strength`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val city = game.addCity(civ, game.tileMap[0, 0], initialPopulation = 1)
        civ.viewableTiles = game.tileMap.values.toSet()
        val zone = civ.ai.tactical.map.zoneOf(city.getCenterTile())!!
        assertEquals(TacticalDominance.Friendly, zone.dominance)
    }
}
