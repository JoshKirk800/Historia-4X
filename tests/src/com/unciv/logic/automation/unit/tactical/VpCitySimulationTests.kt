package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** VP CvUnit::getMeleeCombatDamageCity: the simulated garrison absorbs damage until destroyed. */
@RunWith(BaseTestRunner::class)
class VpCitySimulationTests {
    @Test fun meleeProjectionUsesHypotheticalHealthForOverkill() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val us = game.addCiv()
        val them = game.addCiv()
        game.ruleset.modOptions.constants.combatHealthStrengthLikeVp = true
        val attacker = game.addUnit("Warrior", us, game.getTile(0, 0))
        val defender = game.addUnit("Warrior", them, game.getTile(0, 1))
        val attack = SimCombatant(attacker, attacker.getTile(), 100)
        val defense = SimCombatant(defender, defender.getTile(), 1)
        val dealt = BattleDamage.calculateDamageToDefender(attack, defense, attacker.getTile(), 0.5f, ignoreFlanking = false)
        val taken = BattleDamage.calculateDamageToAttacker(attack, defense, attacker.getTile(), 0.5f, ignoreFlanking = false)
        val expected = BattleDamage.resolveMeleeDeaths(attack, defense, dealt, taken)
        assertEquals(expected, SimWorld.damageOnUnit(attacker, attacker.getTile(), 100, defender, defender.getTile(), 1, false))
        assertTrue(expected.second < taken)
    }

    @Test fun simulatedGarrisonDamageUsesTheSharedSplitAndPriorDamage() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        val us = game.addCiv()
        val them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
        game.ruleset.modOptions.constants.cityCombatLikeVp = true
        val city = game.addCity(them, game.getTile(0, 0))
        val garrison = game.addUnit("Warrior", them, city.getCenterTile())
        val attacker = game.addUnit("Warrior", us, game.getTile(0, 1))
        us.viewableTiles = game.tileMap.values.toSet()
        us.ai.invalidateTurnCaches()
        val map = us.ai.tactical.map
        val ctx = TacticalContext(us, map, us.ai.plotDanger, AutomationCache(), listOf(attacker), TacticalTargets.find(us, map))
        val world = SimWorld(ctx, city.getCenterTile(), listOf(attacker), AggressionLevel.Medium, false, false, 0f)
        val (cityDamage, _, garrisonDamage) = world.damageOnCity(attacker, attacker.getTile(), attacker.health, city, 0)
        val expected = BattleDamage.calculateDamageToDefenderAndGarrison(
            SimCombatant(attacker, attacker.getTile(), attacker.health), CityCombatant(city), attacker.getTile(), 0.5f,
            ignoreFlanking = true, garrisonMaxHealth = 100)
        assertEquals(expected.first, cityDamage)
        assertEquals(expected.second.coerceAtMost(garrison.health), garrisonDamage)
        assertTrue(garrisonDamage > 0)
        val (_, _, afterDeath) = world.damageOnCity(attacker, attacker.getTile(), attacker.health, city, garrison.health)
        assertEquals(0, afterDeath)
    }
}
