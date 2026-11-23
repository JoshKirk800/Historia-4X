package com.unciv.logic.city.managers

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi 5.4.8 CvCity::GetMaxHitPoints, updateStrengthValue, getStrengthValue and doTurn. */
@RunWith(BaseTestRunner::class)
class VpCityCombatTests {
    private val game = TestGame()
    private lateinit var city: City

    @Before fun setup() {
        game.makeHexagonalMap(4)
        game.ruleset.modOptions.constants.cityCombatLikeVp = true
        val civ = game.addCiv()
        city = game.addCity(civ, game.getTile(0, 0), replacePalace = true)
    }

    @Test fun maximumAndPopulationChangesPreserveDamage() {
        assertEquals(258, CityCombatant(city).getMaxHealth())
        city.health = 200
        city.population.addPopulation(1)
        assertEquals(266, CityCombatant(city).getMaxHealth())
        assertEquals(208, city.health)
        city.population.addPopulation(-1)
        assertEquals(200, city.health)
    }

    @Test fun cityStrikeKeepsBuildingsAndDoesNotApplyZeroAsMultiplier() {
        val combatant = CityCombatant(city)
        val before = combatant.getAttackingStrength(null)
        city.cityConstructions.addBuilding("Walls")
        assertEquals(5, before)
        assertEquals(before + 5, combatant.getAttackingStrength(null))
    }

    @Test fun strikeModifierIsAddedToBaseHundredPercent() {
        val building = game.createBuilding("[+50]% City ranged strike strength")
        city.cityConstructions.addBuilding(building)
        assertEquals(8, CityCombatant(city).getAttackingStrength(null))
    }

    @Test fun healingUsesPopulationAndPeaceMultiplier() {
        city.health = 100
        CityTurnManager(city).endTurn()
        assertEquals(127, city.health)
    }

    @Test fun garrisonStrengthDoesNotBoostStrikeOrDependOnHealth() {
        val garrison = game.addDefaultMeleeUnitWithUniques(city.civ, city.getCenterTile())
        val combatant = CityCombatant(city)
        val withGarrison = combatant.getDefendingStrength(null)
        val strike = combatant.getAttackingStrength(null)
        garrison.health = 1
        assertEquals(withGarrison, combatant.getDefendingStrength(null))
        assertEquals(strike, combatant.getAttackingStrength(null))
        assertEquals(5, strike)
    }

    @Test fun healingUsesRecentDamageToSuppressPeaceMultiplier() {
        city.damageTakenLastTurn = 1
        assertEquals(9, CityTurnManager(city).getHealPerTurn())
    }

    @Test fun foundedCityStartsAtConfiguredMaximum() {
        val second = game.addCity(city.civ, game.getTile(3, 0))
        assertEquals(CityCombatant(second).getMaxHealth(), second.health)
    }

    @Test fun wonderDefenseCountsWorldWondersFractionally() {
        val building = game.createBuilding("[1] city strength per [2] world wonders")
        city.cityConstructions.addBuilding(building)
        val wonder = game.createWonder()
        city.cityConstructions.addBuilding(wonder)
        assertEquals(6, CityCombatant(city).getDefendingStrength(null))
        val wonder2 = game.createWonder()
        city.cityConstructions.addBuilding(wonder2)
        assertEquals(6, CityCombatant(city).getDefendingStrength(null))
    }

    @Test fun garrisonAndPriorAttacksAddStrikeModifiers() {
        city.cityConstructions.addBuilding(game.createBuilding(
            "[+20]% City ranged strike strength with a garrison",
            "[+10]% city strike strength per previous attack on target"))
        val enemy = game.addCiv()
        val defender = game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(2, 0))
        defender.timesAttackedBy[city.civ.civID] = 2
        game.addDefaultMeleeUnitWithUniques(city.civ, city.getCenterTile())
        val strength = CityCombatant(city).getAttackingStrength(com.unciv.logic.battle.MapUnitCombatant(defender))
        assertEquals(7, strength)
    }

    @Test fun stationedAircraftIncreaseBothStrengths() {
        city.cityConstructions.addBuilding(game.createBuilding("[3] city strength per stationed air unit"))
        game.addUnit("Fighter", city.civ, city.getCenterTile())
        val combatant = CityCombatant(city)
        assertEquals(8, combatant.getDefendingStrength(null))
        assertEquals(8, combatant.getAttackingStrength(null))
    }

    @Test fun nearbyUnitDefenseOnlyAffectsDefense() {
        game.addDefaultMeleeUnitWithUniques(city.civ, game.getTile(1, 0), "[+40]% defense for this and adjacent cities")
        val combatant = CityCombatant(city)
        assertEquals(7, combatant.getDefendingStrength(null))
        assertEquals(5, combatant.getAttackingStrength(null))
    }

    @Test fun defenseProcessAccumulatesButDoesNotBoostStrike() {
        val constants = game.ruleset.modOptions.constants.cityCombat
        constants.defenseProcesses = hashMapOf("Defense" to com.unciv.models.ruleset.CityDefenseProcess(10, 1, 15))
        city.cityConstructions.addToQueue("Defense")
        city.cityStats.currentCityStats.production = 20f
        val combatant = CityCombatant(city)
        val strike = combatant.getAttackingStrength(null)
        assertEquals(7, combatant.getDefendingStrength(null))
        city.defenseProcessTurns = 10
        assertEquals(8, combatant.getDefendingStrength(null))
        assertEquals(strike, combatant.getAttackingStrength(null))
    }

    @Test fun adjacentEnemySuppressesSafeHealing() {
        val enemy = game.addCiv()
        city.civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        city.civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(1, 0))
        assertEquals(9, CityTurnManager(city).getHealPerTurn())
    }

    @Test fun completeLandBlockadeStopsHealing() {
        val enemy = game.addCiv()
        city.civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        city.civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in city.getCenterTile().neighbors)
            game.addDefaultMeleeUnitWithUniques(enemy, tile)
        assertEquals(0, CityTurnManager(city).getHealPerTurn())
    }

    @Test fun flatDamageReductionIsLocalAndStagedOff() {
        city.cityConstructions.addBuilding(game.createBuilding("Cities take [2] less damage from attacks"))
        val combatant = CityCombatant(city)
        assertEquals(2, combatant.getFlatDamageReduction())
        game.ruleset.modOptions.constants.cityCombatLikeVp = false
        assertEquals(0, combatant.getFlatDamageReduction())
    }

    @Test fun switchOffKeepsOldMaximum() {
        game.ruleset.modOptions.constants.cityCombatLikeVp = false
        assertEquals(200, CityCombatant(city).getMaxHealth())
    }
}
