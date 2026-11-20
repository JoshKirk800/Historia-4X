package com.unciv.logic.automation.unit

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.automation.unit.tactical.SimCombatant
import com.unciv.logic.battle.BattleConstants
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
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
 *  Vox Populi's CvDangerPlots (Brave New World data): the damage a tile, a unit and a city would take from the enemies that can reach them
 *  (CvDangerPlotContents::GetDanger), what a civilian risks, the fog, the citadels, the units that vanished and the air units' interceptions;
 *  and what Vox Populi's tactical map makes of a capital's surroundings (CityStrategyAIHelpers::IsTestCityStrategy_CapitalUnderThreat).
 *  A Warrior (8) against a unit as strong as itself on flat land does 30 damage: DoDamageMath's 2400 plus half of 1200, at a ratio of 1.
 */
@RunWith(BaseTestRunner::class)
class PlotDangerTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
    }

    private fun tile(y: Int) = game.tileMap[0, y]
    private val everything get() = game.tileMap.values.toSet()

    /** The danger map as the AI sees the world now: everything is visible to both sides */
    private fun danger(previouslyKnown: Set<Int> = emptySet()): PlotDanger {
        civ.viewableTiles = everything
        enemy.viewableTiles = everything
        return PlotDanger(civ, previouslyKnown)
    }

    private val civilianDamage = BattleConstants.DAMAGE_TO_CIVILIAN_UNIT

    @Test
    fun `a civilization we are at peace with is no danger, whatever its units are`() {
        val other = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        game.addUnit("Warrior", other, tile(4))
        val worker = game.addUnit("Worker", civ, tile(0))
        val danger = danger()
        assertEquals(0, danger.get(tile(3)))
        assertEquals(0, danger.get(worker, tile(3)))
        assertTrue(danger.possibleAttackers(tile(3)).isEmpty())
    }

    @Test
    fun `the danger of a tile is what each enemy that can reach it does to a unit as strong as itself`() {
        game.addUnit("Warrior", enemy, tile(4))
        val danger = danger()
        assertEquals(30, danger.get(tile(3)))
        // Warriors have two moves and strike next to where they stop: three tiles away is in reach, four is not
        assertEquals(30, danger.get(tile(1)))
        assertEquals(0, danger.get(tile(0)))
        game.addUnit("Warrior", enemy, tile(5))
        assertEquals("two Warriors", 60, danger().get(tile(3)))
    }

    @Test
    fun `a wounded attacker does less`() {
        val warrior = game.addUnit("Warrior", enemy, tile(4))
        warrior.health = 40
        val wounded = danger().get(tile(3))
        assertTrue("$wounded", wounded in 1..29)
    }

    @Test
    fun `the fixed damage of a tile is the terrain's 20, whatever the unit, with no attacker near`() {
        game.addUnit("Warrior", enemy, tile(-8))
        val mountain = game.setTileTerrain(tile(7).position, "Mountain")
        val danger = danger()
        assertEquals(20, danger.get(mountain, fixedDamageOnly = true))
        assertEquals(20, danger.get(mountain))
        assertEquals(0, danger.get(tile(3), fixedDamageOnly = true))
    }

    @Test
    fun `an enemy citadel next to the plot does twice its damage there, none on its own plot`() {
        val city = game.addCity(enemy, tile(11))
        val citadel = tile(7)
        citadel.setOwningCity(city)
        citadel.setImprovementBasic("Citadel")
        val danger = danger()
        // Adjacent enemy units ending their turn take [30] damage, and the danger counts it twice
        assertEquals(60, danger.get(tile(6), fixedDamageOnly = true))
        assertEquals(60, danger.get(tile(8), fixedDamageOnly = true))
        assertEquals(0, danger.get(citadel, fixedDamageOnly = true))
        val warrior = game.addUnit("Warrior", civ, tile(0))
        assertEquals("the damage of a unit that is not known is the same", 60, danger.get(warrior, tile(6)))
    }

    @Test
    fun `a citadel of ours or a pillaged one is no danger`() {
        val ours = game.addCity(civ, tile(11))
        val citadel = tile(7)
        citadel.setOwningCity(ours)
        citadel.setImprovementBasic("Citadel")
        assertEquals("our own citadel", 0, danger().get(tile(6), fixedDamageOnly = true))
        citadel.setOwningCity(game.addCity(enemy, tile(-11)))
        assertEquals(60, danger().get(tile(6), fixedDamageOnly = true))
        citadel.improvementIsPillaged = true
        assertEquals("pillaged", 0, danger().get(tile(6), fixedDamageOnly = true))
    }

    @Test
    fun `a civilian is captured where an enemy can walk in, and takes 40 damage where it can only be hit`() {
        game.addUnit("Warrior", enemy, tile(4))
        val worker = game.addUnit("Worker", civ, tile(0))
        val danger = danger()
        assertEquals("two moves away: the Warrior walks in", Int.MAX_VALUE, danger.get(worker, tile(2)))
        assertEquals("three away: it strikes next to where it stops", civilianDamage, danger.get(worker, tile(1)))
        assertEquals(0, danger.get(worker, tile(0)))
        assertEquals("the plot of an enemy unit is the maximum", Int.MAX_VALUE, danger.get(worker, tile(4)))
    }

    @Test
    fun `an enemy whose civilization cannot see the plot does only four fifths of the damage`() {
        game.addUnit("Warrior", enemy, tile(4))
        val worker = game.addUnit("Worker", civ, tile(0))
        val ours = game.addUnit("Warrior", civ, tile(3))
        val seen = danger()
        assertEquals(30, seen.get(ours, tile(3)))
        assertEquals(civilianDamage, seen.get(worker, tile(1)))
        civ.viewableTiles = everything
        enemy.viewableTiles = everything - setOf(tile(3), tile(1))
        val unseen = PlotDanger(civ)
        assertEquals(30 * 80 / 100, unseen.get(ours, tile(3)))
        assertEquals(civilianDamage * 80 / 100, unseen.get(worker, tile(1)))
    }

    @Test
    fun `a civilian in our city is safe until the city is about to fall`() {
        val city = game.addCity(civ, tile(0))
        game.addUnit("Warrior", enemy, tile(2))
        val worker = game.addUnit("Worker", civ, city.getCenterTile())
        val danger = danger()
        assertEquals(0, danger.get(worker, city.getCenterTile()))
        city.damageTakenLastTurn = city.health + 1
        assertEquals(Int.MAX_VALUE, danger.get(worker, city.getCenterTile()))
    }

    @Test
    fun `a unit in our city takes its share of the city's damage, twice the damage over the city's hit points`() {
        val city = game.addCity(civ, tile(0))
        val enemyWarrior = game.addUnit("Warrior", enemy, tile(1))
        val garrison = game.addUnit("Warrior", civ, city.getCenterTile())
        val danger = danger()
        val cityDamage = BattleDamage.calculateDamageToDefender(
            MapUnitCombatant(enemyWarrior), CityCombatant(city), enemyWarrior.getTile(), 0.5f, ignoreFlanking = true)
        assertTrue(cityDamage > 0)
        assertEquals(cityDamage, danger.get(city))
        assertEquals(cityDamage * 2 * 100 / CityCombatant(city).getMaxHealth(), danger.get(garrison, city.getCenterTile()))
        assertEquals("nobody can reach a city nobody is near", 0, danger().get(game.addCity(civ, tile(-10))))
    }

    @Test
    fun `hidden plots next to an enemy unit add a point to the plots near them`() {
        game.addUnit("Warrior", enemy, tile(4))
        val worker = game.addUnit("Worker", civ, tile(0))
        assertEquals("nothing hidden", civilianDamage, danger().get(worker, tile(7)))
        civ.viewableTiles = everything - tile(5)
        enemy.viewableTiles = everything
        val danger = PlotDanger(civ)
        // the plot hidden next to the Warrior could hold another enemy, which hits the plots within two of it
        assertEquals(civilianDamage + 1, danger.get(worker, tile(7)))
        assertEquals("three away from the hidden plot nothing is added", 0, danger.get(worker, tile(9)))
    }

    @Test
    fun `a unit that moved out of sight still counts the next time, but is forgotten after that`() {
        game.addUnit("Warrior", enemy, tile(4))
        val first = danger()
        assertEquals(1, first.knownUnits.size)
        civ.viewableTiles = everything - tile(4)
        assertEquals("unseen and not remembered", 0, PlotDanger(civ).get(tile(3)))
        val second = PlotDanger(civ, first.knownUnits)
        assertEquals("seen last time: it counts where it is", 30, second.get(tile(3)))
        assertTrue("and is not known next turn", second.knownUnits.isEmpty())
        assertEquals(0, PlotDanger(civ, second.knownUnits).get(tile(3)))
    }

    @Test
    fun `an AI civilization counts the units in the revealed enemy city it cannot see, a human does not`() {
        val city = game.addCity(enemy, tile(8))
        game.addUnit("Warrior", enemy, city.getCenterTile())
        civ.viewableTiles = everything - city.getCenterTile()
        assertEquals(30, PlotDanger(civ).get(tile(7)))
        val human = game.addCiv(isPlayer = true)
        human.diplomacyFunctions.makeCivilizationsMeet(enemy)
        human.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(human, true)
        human.viewableTiles = everything - city.getCenterTile()
        assertEquals(0, PlotDanger(human).get(tile(7)))
    }

    @Test
    fun `an enemy city hits what is within its bombard range, not its own plot, and a civilian takes the flat damage from it`() {
        val city = game.addCity(enemy, tile(8))
        val worker = game.addUnit("Worker", civ, tile(0))
        val warrior = game.addUnit("Warrior", civ, tile(-1))
        val danger = danger()
        val range = city.getBombardRange()
        assertEquals(civilianDamage, danger.get(worker, tile(8 - range)))
        assertEquals(0, danger.get(worker, tile(8 - range - 1)))
        val expected = BattleDamage.calculateDamageToDefender(CityCombatant(city), SimCombatant(warrior, tile(8 - range), 100), city.getCenterTile(), 0.5f)
        assertEquals(expected, danger.get(warrior, tile(8 - range)))
    }

    @Test
    fun `an air unit takes the damage of the interceptor it meets, nothing without one, and a missile nothing at all`() {
        val home = game.addCity(civ, tile(0))
        val bomber = game.addUnit("Bomber", civ, home.getCenterTile())
        val missile = game.addUnit("Guided Missile", civ, home.getCenterTile())
        val danger = danger()
        assertEquals("no interceptor", 0, danger.airUnitDamage(bomber, tile(0)))
        val fighter = game.addUnit("Fighter", enemy, game.addCity(enemy, tile(3)).getCenterTile())
        assertTrue(fighter.canIntercept(tile(0)))
        val withFighter = danger()
        val expected = BattleDamage.calculateDamageToDefender(
            MapUnitCombatant(fighter), SimCombatant(bomber, tile(0), bomber.health), tile(0), 0.5f, fullHealthScale = true)
        assertTrue(expected > 0)
        assertEquals(expected, withFighter.airUnitDamage(bomber, tile(0)))
        assertEquals("GetDanger of an air unit is its air damage", expected, withFighter.get(bomber, tile(0)))
        assertEquals("out of its interception range", 0, withFighter.airUnitDamage(bomber, tile(-8)))
        assertEquals(0, withFighter.airUnitDamage(missile, tile(0)))
    }

    @Test
    fun `an air sweep draws no fire from ground interceptors but the fighter's, and an intercepting fighter fears the best sweep`() {
        val fighter = game.addUnit("Fighter", civ, game.addCity(civ, tile(0)).getCenterTile())
        val enemyFighter = game.addUnit("Fighter", enemy, game.addCity(enemy, tile(3)).getCenterTile())
        val danger = danger()
        val sweepDamage = danger.airUnitDamage(fighter, tile(0), AirAction.Sweep)
        assertTrue("air to air: the enemy fighter hurts the sweeper", sweepDamage > 0)
        val intercepted = danger.airUnitDamage(fighter, tile(0), AirAction.Intercept)
        assertTrue("the enemy fighter can sweep over our interceptor", intercepted > 0)
        enemyFighter.destroy()
        val none = danger()
        assertEquals(0, none.airUnitDamage(fighter, tile(0), AirAction.Sweep))
        assertEquals(0, none.airUnitDamage(fighter, tile(0), AirAction.Intercept))
    }

    /** An enemy unit on every tile within two plots of [city]: the enemy dominates its zone, whatever the city is worth */
    private fun surround(city: City) {
        for (tile in city.getCenterTile().getTilesInDistance(2)) if (tile != city.getCenterTile()) game.addUnit("Warrior", enemy, tile)
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    @Test
    fun `a city just founded is about to fall when twice its danger is more than its hit points, an older one only by the damage it took`() {
        val city = game.addCity(civ, tile(0))
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        assertFalse(CityDangerAi.isInDangerOfFalling(city, false))
        surround(city)
        assertTrue("danger ${civ.ai.plotDanger.get(city)} of ${city.health}", civ.ai.plotDanger.get(city) * 2 > city.health)
        assertTrue(CityDangerAi.isInDangerOfFalling(city, false))
        city.turnAcquired = game.gameInfo.turns - 5
        assertFalse("an old city is judged by last turn's damage", CityDangerAi.isInDangerOfFalling(city, false))
        city.damageTakenLastTurn = city.health + 1
        assertTrue(CityDangerAi.isInDangerOfFalling(city, false))
    }

    @Test
    fun `a city nobody can reach has no danger, one with an enemy next to it has some`() {
        val city = game.addCity(civ, tile(0))
        game.addUnit("Warrior", enemy, tile(6))
        assertEquals(0, danger().get(city))
        game.addUnit("Warrior", enemy, tile(1))
        assertTrue(danger().get(city) > 0)
    }

    @Test
    fun `a capital is under threat when the enemy dominates a zone around it, not while the zone is ours`() {
        val capital = game.addCity(civ, tile(0))
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        assertFalse("alone with its city, the zone is ours", CityThreat.isCapitalUnderThreat(capital))
        surround(capital)
        assertTrue(CityThreat.isCapitalUnderThreat(capital))
    }

    @Test
    fun `a city that is not a capital is never the capital under threat`() {
        game.addCity(civ, tile(0))
        val other = game.addCity(civ, tile(8))
        surround(other)
        assertFalse(CityThreat.isCapitalUnderThreat(other))
    }

    @Test
    fun `the capital under threat strategy becomes active when the test holds`() {
        val capital = game.addCity(civ, tile(0))
        surround(capital)
        capital.ai.doTurn()
        assertTrue(capital.ai.isCityStrategyActive("AICITYSTRATEGY_CAPITAL_UNDER_THREAT"))
    }

    @Test
    fun `tiles are looked up by view as by tile`() {
        game.addUnit("Warrior", enemy, tile(4))
        val danger = danger()
        val tile: Tile = tile(2)
        val view = com.unciv.view.GameView(game.gameInfo, civ).tileMapView.getTile(tile)!!
        assertEquals(danger.get(tile), danger.get(view))
    }
}
