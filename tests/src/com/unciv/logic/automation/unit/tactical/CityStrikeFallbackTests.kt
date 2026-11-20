package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvCity::getBestRangedStrikeTarget and the fallback at the end of CvTacticalAI::ReviewUnassignedUnits (Brave New World data): our city at (0, 0)
 *  shoots up to 2 tiles. The score of a target is its expected damage, plus 15 if that kills it and 10 for a city bombard unit; a civilian is worth 1
 *  and a great general or admiral 2 (10 more if killed).
 */
@RunWith(BaseTestRunner::class)
class CityStrikeFallbackTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(10)
        civ = game.addCiv()
        enemy = game.addCiv("Provides [100] [Iron]", "Provides [100] [Horses]")
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        city = game.addCity(civ, tile(0, 0))
        see()
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun see() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun foe(name: String, x: Int, y: Int, health: Int = 100): MapUnit {
        val unit = game.addUnit(name, enemy, tile(x, y))
        unit.health = health
        see()
        return unit
    }

    @Test
    fun `a target the shot kills is worth 15 more, so a nearly dead unit is shot before a healthy one`() {
        foe("Warrior", 2, 0)
        val dying = foe("Warrior", 0, 2, health = 5)
        assertEquals(dying.getTile(), TacticalAttacks.bestRangedStrikeTarget(city))
    }

    @Test
    fun `a city bombard unit is worth 10 more`() {
        foe("Warrior", 2, 0)
        val catapult = foe("Catapult", 0, 2)
        assertEquals(catapult.getTile(), TacticalAttacks.bestRangedStrikeTarget(city))
    }

    @Test
    fun `a civilian is worth 1 and a great general 2, so a soldier is shot first, a general before a worker and a worker last`() {
        val worker = foe("Worker", 1, 0)
        assertEquals("a civilian alone is worth shooting at", worker.getTile(), TacticalAttacks.bestRangedStrikeTarget(city))
        val general = foe("Great General", 0, 1)
        assertEquals(general.getTile(), TacticalAttacks.bestRangedStrikeTarget(city))
        val soldier = foe("Warrior", -2, 0)
        assertEquals(soldier.getTile(), TacticalAttacks.bestRangedStrikeTarget(city))
    }

    @Test
    fun `nothing is shot at beyond 2 tiles, by a city that has shot this turn or at an unseen unit`() {
        val far = foe("Warrior", 3, 0)
        assertNull(TacticalAttacks.bestRangedStrikeTarget(city))
        far.removeFromTile()
        val near = foe("Warrior", 2, 0)
        assertEquals(near.getTile(), TacticalAttacks.bestRangedStrikeTarget(city))
        city.attackedThisTurn = true
        assertNull(TacticalAttacks.bestRangedStrikeTarget(city))
        city.attackedThisTurn = false
        civ.viewableTiles = game.tileMap.values.filter { it != near.getTile() }.toSet()
        assertNull(TacticalAttacks.bestRangedStrikeTarget(city))
    }
}
