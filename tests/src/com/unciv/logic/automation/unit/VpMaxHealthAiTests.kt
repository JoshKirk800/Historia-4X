package com.unciv.logic.automation.unit

import com.unciv.logic.automation.unit.tactical.TacticalHealing
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The AI reads a unit's own maximum hit points, as Vox Populi's does (CvUnit::GetMaxHitPoints in CvTacticalAI::PlotHealMoves / shouldHeal,
 *  CvUnit::IsCombatReady-style half-health tests, CvUnit::GetDamageCombatModifier through GetBaseCombatStrengthConsideringDamage).
 *  With `unitMaxHitPointsLikeVp` and a promotion of `[+25] maximum hit points` a unit has 125 hit points; with the switch off the
 *  promotion is not read and every predicate is the one for 100.
 */
@RunWith(BaseTestRunner::class)
class VpMaxHealthAiTests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private class Setup(val game: TestGame, val unit: MapUnit)

    /** A Warrior that has the +25 maximum hit points promotion, on flat Grassland in neutral territory; the switch is [on] */
    private fun setup(on: Boolean): Setup {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        game.setTileTerrain(game.getTile(0, 0).position, "Grassland")
        setFlag(game, "unitMaxHitPointsLikeVp", on)
        val civ = game.addCiv(isPlayer = true)
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        unit.promotions.addPromotion(game.createUnitPromotion("[+25] maximum hit points").name, isFree = true)
        return Setup(game, unit)
    }

    @Test
    fun `aircraft readiness is more than half of the unit's own maximum`() {
        val s = setup(on = true)
        assertEquals(125, s.unit.getMaxHealth())
        // 62 of 125 is less than half (CvUnit damage 63 * 2 >= 125), though it is more than 50 hit points
        s.unit.health = 62
        assertFalse(AirMoves.isCombatReady(s.unit))
        s.unit.health = 63
        assertTrue(AirMoves.isCombatReady(s.unit))
    }

    @Test
    fun `aircraft readiness is unchanged with the switch off`() {
        val s = setup(on = false)
        s.unit.health = 50
        assertFalse(AirMoves.isCombatReady(s.unit))
        s.unit.health = 51
        assertTrue(AirMoves.isCombatReady(s.unit))
    }

    @Test
    fun `a unit heals when it has lost more than the acceptable damage of its maximum`() {
        val s = setup(on = true)
        val civ = s.unit.civ
        // CvUnit::shouldHeal: at 100 of 125 it has lost 25, more than the 20 accepted, though its health is "100"
        s.unit.health = 100
        assertTrue(TacticalHealing.shouldHeal(civ, s.unit, beforeAttacks = false))
        // 110 of 125: 15 lost
        s.unit.health = 110
        assertFalse(TacticalHealing.shouldHeal(civ, s.unit, beforeAttacks = false))
        // full health is full whatever the number
        s.unit.health = 125
        assertFalse(TacticalHealing.shouldHeal(civ, s.unit, beforeAttacks = false))
    }

    @Test
    fun `a unit at 100 of 125 fortifies to heal and one at 125 does not`() {
        val s = setup(on = true)
        s.unit.health = 125
        assertFalse(TacticalSafety.heal(s.unit))
        s.unit.health = 100
        assertTrue(TacticalSafety.heal(s.unit))
    }

    @Test
    fun `healing decisions are unchanged with the switch off`() {
        val s = setup(on = false)
        val civ = s.unit.civ
        s.unit.health = 100
        assertFalse(TacticalHealing.shouldHeal(civ, s.unit, beforeAttacks = false))
        assertFalse(TacticalSafety.heal(s.unit))
        s.unit.health = 79 // 21 lost
        assertTrue(TacticalHealing.shouldHeal(civ, s.unit, beforeAttacks = false))
    }

    @Test
    fun `the strength the AI assumes for a wounded unit follows its lost health`() {
        val s = setup(on = true)
        val rules = s.unit.civ.gameInfo.ruleset.ai
        s.unit.health = 125
        assertEquals(100, PlotDanger.valueConsideringDamage(100, s.unit, rules))
        // 25 lost: 25 x 33 / 100 = 8 per cent less
        s.unit.health = 100
        assertEquals(92, PlotDanger.valueConsideringDamage(100, s.unit, rules))
    }

    @Test
    fun `the strength the AI assumes is unchanged with the switch off`() {
        val s = setup(on = false)
        val rules = s.unit.civ.gameInfo.ruleset.ai
        s.unit.health = 100
        assertEquals(100, PlotDanger.valueConsideringDamage(100, s.unit, rules))
        s.unit.health = 70 // 30 lost: 9 per cent less
        assertEquals(91, PlotDanger.valueConsideringDamage(100, s.unit, rules))
    }
}
