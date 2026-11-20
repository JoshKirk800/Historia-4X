package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What the safety and heal moves share (Brave New World data): which units can defend, what puts a unit under ranged attack, and healing
 *  (PlotHealMoves). Fleeing is in [com.unciv.logic.automation.unit.CivilianSafetyTests].
 */
@RunWith(BaseTestRunner::class)
class TacticalSafetyTests {
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
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    // region Who can defend

    @Test
    fun `a hurt unit is under ranged attack from archers that do more damage than it heals but not from melee units`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 80
        val melee = game.addUnit("Warrior", enemy, tile(2))
        assertFalse(TacticalSafety.isUnderEnemyRangedAttack(unit))
        melee.destroy()
        game.addUnit("Archer", enemy, tile(2))
        civ.viewableTiles = game.tileMap.values.toSet()
        assertTrue(TacticalSafety.isUnderEnemyRangedAttack(unit))
    }

    @Test
    fun `a worker cannot defend itself and a warrior can`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        assertFalse(TacticalSafety.canDefend(worker))
        assertTrue(TacticalSafety.canDefend(game.addUnit("Warrior", civ, tile(6))))
    }

    // endregion
    // region Healing

    @Test
    fun `a hurt unit outside a city fortifies to heal and keeps its movement, so that it does heal at the end of the turn`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 60
        assertTrue(TacticalSafety.heal(unit))
        assertTrue(unit.isFortified())
        assertFalse("a unit with its movement used up counts as having moved and does not heal", unit.hasUnitMovedThisTurn())
        UnitTurnManager(unit).endTurn()
        assertEquals(70, unit.health)
    }

    @Test
    fun `a healthy unit, one in a city, and one under ranged fire do not heal`() {
        val healthy = game.addUnit("Warrior", civ, tile(0))
        assertFalse(TacticalSafety.heal(healthy))
        val city = game.addCity(civ, tile(6))
        val inCity = game.addUnit("Warrior", civ, city.getCenterTile())
        inCity.health = 50
        assertFalse(TacticalSafety.heal(inCity))
        val shot = game.addUnit("Warrior", civ, tile(-6))
        shot.health = 50
        game.addUnit("Archer", enemy, tile(-4))
        civ.viewableTiles = game.tileMap.values.toSet()
        assertFalse(TacticalSafety.heal(shot))
    }

    // endregion
}
