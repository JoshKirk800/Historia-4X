package com.unciv.logic.automation.unit

import com.unciv.UncivGame
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvHomelandAI::PlotHealMoves for the civilians no earlier homeland move takes: a hurt worker waits to heal */
@RunWith(BaseTestRunner::class)
class CivilianHealTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var mine: Tile

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = true
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        for (improvement in listOf("Farm", "Mine")) civ.tech.techsResearched.add(game.ruleset.tileImprovements[improvement]!!.techRequired!!)
        civ.tech.techsResearched.add(game.ruleset.tileResources["Iron"]!!.revealedBy!!)
        game.addCity(civ, game.tileMap[0, 0])
        mine = game.tileMap[1, 1] // an iron tile inside the borders: the one thing a worker has to do
        mine.setTileResource("Iron")
    }

    private fun automate(unit: MapUnit, cache: AutomationCache = AutomationCache()) =
        CivilianUnitAutomation.automateCivilianUnit(GameView(civ.gameInfo, civ).getMapUnitView(unit), cache)

    @Test
    fun `a worker with full health goes to work`() {
        val worker = game.addUnit("Worker", civ, mine)
        automate(worker)
        assertNotNull(mine.improvementInProgress)
    }

    @Test
    fun `a hurt worker outside a city waits where it is to heal, keeping its movement, for no other move takes it`() {
        val worker = game.addUnit("Worker", civ, mine)
        worker.health = 60
        val cache = AutomationCache()
        automate(worker, cache)
        assertNull(mine.improvementInProgress)
        assertEquals(mine, worker.getTile())
        assertTrue(worker in cache.processedUnits)
        assertFalse("it did not use its movement: it heals at the end of the turn", worker.hasUnitMovedThisTurn())
    }
}
