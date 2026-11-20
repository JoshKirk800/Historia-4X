package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.BattleConstants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Who flees and where to, by the danger to the unit (Brave New World data, Vox Populi's CvHomelandAI::PlotMovesToSafety and MoveCivilianToSafety, with
 *  TacticalAIHelpers::FindSafestPlotInReach): a unit flees from a fifth of its health in danger, a combat unit only from all of it. A Warrior does 30
 *  damage to another and 40 to a civilian; a civilian is captured where an enemy can walk in.
 */
@RunWith(GdxTestRunner::class)
class CivilianSafetyTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(14)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    /** What the AI sees now: everything, for both sides */
    private fun see() {
        civ.viewableTiles = game.tileMap.values.toSet()
        enemy.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    @Test
    fun `a civilian flees from a fifth of its health in danger, 40 damage is more than 20`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(3))
        see()
        assertEquals(BattleConstants.DAMAGE_TO_CIVILIAN_UNIT, civ.ai.plotDanger.get(worker))
        assertTrue(CivilianSafety.shouldFlee(worker))
        assertFalse("nothing around: no danger", CivilianSafety.shouldFlee(game.addUnit("Worker", civ, tile(-8))))
    }

    @Test
    fun `the point of danger that fog adds is no reason to flee`() {
        // the Warrior is too far to attack; the hidden plot next to it makes the plots within two of that plot a point more dangerous
        val worker = game.addUnit("Worker", civ, tile(4))
        game.addUnit("Warrior", enemy, tile(8))
        civ.viewableTiles = game.tileMap.values.toSet() - tile(6)
        enemy.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        assertEquals(1, civ.ai.plotDanger.get(worker))
        assertFalse(CivilianSafety.shouldFlee(worker))
    }

    @Test
    fun `a combat unit flees only from danger that is all of its health`() {
        val warrior = game.addUnit("Warrior", civ, tile(0))
        // not next to it: nobody flanks, but all of them are within a move and a strike
        val ring = tile(0).getTilesAtDistance(2).toList()
        var below = 0
        var above = 0
        for (tile in ring.take(6)) {
            game.addUnit("Warrior", enemy, tile)
            see()
            val level = civ.ai.plotDanger.get(warrior)
            assertEquals("danger $level of ${warrior.health}", level >= warrior.health, CivilianSafety.shouldFlee(warrior))
            if (level < warrior.health) below++ else above++
        }
        assertTrue("one enemy is no mortal danger", below > 0)
        assertTrue("six are", above > 0)
    }

    @Test
    fun `a worker that cleans fallout in our land stays while the danger is below its health`() {
        val city = game.addCity(civ, tile(-1))
        val worker = game.addUnit("Worker", civ, tile(0))
        worker.getTile().setOwningCity(city)
        worker.getTile().addTerrainFeature("Fallout")
        game.addUnit("Warrior", enemy, tile(3))
        see()
        assertEquals(BattleConstants.DAMAGE_TO_CIVILIAN_UNIT, civ.ai.plotDanger.get(worker))
        assertFalse(CivilianSafety.shouldFlee(worker))
        worker.health = 30
        assertTrue("with more danger than health it flees", CivilianSafety.shouldFlee(worker))
    }

    @Test
    fun `a civilian in danger goes to our city, which is the safest place, and its turn is over`() {
        val city = game.addCity(civ, tile(-2))
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(3))
        see()
        assertTrue(CivilianSafety.fleeIfThreatened(worker, AutomationCache()))
        assertEquals(city.getCenterTile(), worker.getTile())
        assertEquals(0f, worker.currentMovement, 0f)
    }

    @Test
    fun `a civilian that is not in danger stays and keeps its movement`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(12))
        see()
        assertFalse(CivilianSafety.fleeIfThreatened(worker, AutomationCache()))
        assertEquals(tile(0), worker.getTile())
        assertTrue(worker.currentMovement > 0f)
    }

    @Test
    fun `a civilian that can be captured where it stands leaves for a plot where it cannot`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(2))
        see()
        assertEquals(Int.MAX_VALUE, civ.ai.plotDanger.get(worker))
        assertTrue(CivilianSafety.fleeIfThreatened(worker, AutomationCache()))
        assertTrue("it is not capturable where it went: ${worker.getTile().position}", civ.ai.plotDanger.get(worker) < Int.MAX_VALUE)
    }

    @Test
    fun `moving a civilian to safety goes into our city`() {
        val city = game.addCity(civ, tile(2))
        val worker = game.addUnit("Worker", civ, tile(0))
        see()
        assertTrue(CivilianSafety.moveToSafety(worker, AutomationCache()))
        assertEquals(city.getCenterTile(), worker.getTile())
    }
}
