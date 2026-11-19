package com.unciv.logic.automation.unit

import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvDangerPlots as the unit AI reads it (Brave New World data): the danger value of a tile, its low "within one move" bit
 *  (IsUnderImmediateThreat) and the city threat value of the danger map (GetCityDanger); what Vox Populi's tactical map makes of a
 *  capital's surroundings (CityStrategyAIHelpers::IsTestCityStrategy_CapitalUnderThreat).
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
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]
    private fun strength(unit: String) = game.ruleset.units[unit]!!.strength

    /** The danger map as the AI sees the world now: everything is visible */
    private fun danger(): PlotDanger {
        civ.viewableTiles = game.tileMap.values.toSet()
        return PlotDanger(civ)
    }

    @Test
    fun `a unit that can reach the tile in one move gives its strength times 100 and the immediate threat bit`() {
        game.addUnit("Warrior", enemy, tile(4))
        val danger = danger()
        // Warrior: 2 moves, so tile(2) is one turn away
        assertEquals(strength("Warrior") * 100 or 1, danger.get(tile(2)))
        assertTrue(danger.isUnderImmediateThreat(tile(2)))
        // the tile it stands on too, but not the ones it cannot reach
        assertEquals(strength("Warrior") * 100 or 1, danger.get(tile(4)))
        assertEquals(0, danger.get(tile(1)))
        assertFalse(danger.isUnderImmediateThreat(tile(1)))
    }

    @Test
    fun `a ranged unit threatens its moves plus its range but not immediately beyond its moves`() {
        val archer = game.ruleset.units["Archer"]!!
        game.addUnit("Archer", enemy, tile(8))
        val danger = danger()
        val reach = archer.movement + archer.range
        val far = tile(8 - reach)
        // two turns away: the strength halves and the immediate bit is cleared
        assertEquals(archer.strength * 100 / 2 and 1.inv(), danger.get(far))
        assertFalse(danger.isUnderImmediateThreat(far))
        assertEquals(0, danger.get(tile(8 - reach - 1)))
        // within the moves it is immediate again
        assertTrue(danger.isUnderImmediateThreat(tile(8 - archer.movement)))
    }

    @Test
    fun `a wounded unit counts for less`() {
        val warrior = game.addUnit("Warrior", enemy, tile(4))
        warrior.health = 40
        val multiplier = game.ruleset.ai.define(AiDefine.WOUNDED_DAMAGE_MULTIPLIER)
        val full = strength("Warrior")
        val expected = full - full * ((100 - 40) * multiplier / 100) / 100
        assertTrue("a wounded unit is weaker", expected < full)
        assertEquals(expected * 100 or 1, danger().get(tile(3)))
    }

    @Test
    fun `a civilization we are not at war with is a danger scaled by our approach to it, a neutral one a tenth`() {
        val other = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        game.addUnit("Warrior", other, tile(4))
        val neutral = game.ruleset.ai.defineFloat(AiDefine.AI_DANGER_MAJOR_APPROACH_NEUTRAL)
        assertEquals((strength("Warrior") * 100 * neutral).toInt() or 1, danger().get(tile(3)))
        civ.getDiplomacyManager(other)!!.declareWar()
        assertEquals(strength("Warrior") * 100 or 1, danger().get(tile(3)))
    }

    @Test
    fun `an enemy city gives its strength times 100 within its attack range, never immediate`() {
        val city = game.addCity(enemy, tile(8))
        val cityStrength = CityCombatant(city).getCityStrength()
        val range = game.ruleset.ai.define(AiDefine.CITY_ATTACK_RANGE)
        val danger = danger()
        assertEquals(cityStrength * 100 and 1.inv(), danger.get(tile(8 - range)))
        assertFalse(danger.isUnderImmediateThreat(tile(8 - range)))
        assertEquals(0, danger.get(tile(8 - range - 1)))
    }

    @Test
    fun `the threat of a city is the danger of all tiles within the home front range`() {
        val city = game.addCity(civ, tile(0))
        val enemyTile = tile(7)
        game.addUnit("Warrior", enemy, enemyTile)
        val range = game.ruleset.ai.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        val moves = game.ruleset.units["Warrior"]!!.movement
        // every tile within the Warrior's reach has the same value, so the city sees the number of such tiles inside its range
        val tilesInBoth = city.getCenterTile().getTilesInDistance(range).count { it.aerialDistanceTo(enemyTile) <= moves }
        assertTrue("the scenario should put some tiles in both ranges", tilesInBoth > 0)
        val danger = danger()
        assertEquals(tilesInBoth * (strength("Warrior") * 100 or 1), danger.cityThreat(city))
    }

    /** An enemy unit on every tile within two plots of [city]: the enemy dominates its zone, whatever the city is worth */
    private fun surround(city: City) {
        for (tile in city.getCenterTile().getTilesInDistance(2)) if (tile != city.getCenterTile()) game.addUnit("Warrior", enemy, tile)
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
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
