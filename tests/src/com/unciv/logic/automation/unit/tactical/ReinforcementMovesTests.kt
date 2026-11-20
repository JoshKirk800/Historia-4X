package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
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
 *  CvTacticalAI::PlotReinforcementMoves and PositionUnitsAroundTarget (Brave New World data): our city at (0, 0), an enemy warrior near it, and
 *  units of ours further out on the x = 0 and x = 1 columns.
 */
@RunWith(BaseTestRunner::class)
class ReinforcementMovesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        enemy = game.addCiv("Provides [100] [Iron]", "Provides [100] [Horses]")
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        city = game.addCity(civ, tile(0, 0))
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun unit(name: String, owner: Civilization, x: Int, y: Int): MapUnit = game.addUnit(name, owner, tile(x, y))

    private fun context(): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.find(civ, map))
    }

    private fun setPosture(posture: TacticalPosture) {
        civ.ai.tactical.postures = arrayListOf(ZonePosture().also {
            it.ownerId = civ.civName
            it.cityId = city.id
            it.posture = posture
        })
    }

    @Test
    fun `the recruit range is 8 plus 1 per 100 turns, plus 1 on a large and 2 on a huge map`() {
        val ctx = context()
        assertEquals(8, ReinforcementMoves.recruitRange(ctx))
        game.gameInfo.turns = 400
        assertEquals(12, ReinforcementMoves.recruitRange(TacticalContext(civ, ctx.map, ctx.danger, ctx.cache, emptyList(), ctx.targets)))
    }

    @Test
    fun `units near a city the enemy threatens come in, and a lone unit is left for the other moves`() {
        unit("Warrior", enemy, 0, 3)
        val first = unit("Swordsman", civ, 0, -5)
        val second = unit("Swordsman", civ, 1, -5)
        setPosture(TacticalPosture.Counterattack)
        val ctx = context()
        ReinforcementMoves.plotReinforcementMoves(ctx, ctx.map.zoneByCity(city, false)!!)
        assertTrue(first in ctx.cache.processedUnits && second in ctx.cache.processedUnits)
        assertTrue(first.getTile().aerialDistanceTo(city.getCenterTile()) < 5)
        assertTrue(second.getTile().aerialDistanceTo(city.getCenterTile()) < 5)
        assertFalse(first.getTile() == second.getTile())
    }

    @Test
    fun `a zone that withdraws gets no reinforcements`() {
        unit("Warrior", enemy, 0, 3)
        val first = unit("Swordsman", civ, 0, -5)
        val second = unit("Swordsman", civ, 1, -5)
        setPosture(TacticalPosture.Withdraw)
        val ctx = context()
        ReinforcementMoves.plotReinforcementMoves(ctx, ctx.map.zoneByCity(city, false)!!)
        assertEquals(tile(0, -5), first.getTile())
        assertEquals(tile(1, -5), second.getTile())
    }

    @Test
    fun `a zone we dominate with ranged strength and melee units needs nobody`() {
        unit("Swordsman", civ, 0, -1)
        val extra = unit("Swordsman", civ, 0, -5)
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!
        zone.dominance = TacticalDominance.Friendly
        zone.friendlyRangedStrength = 100
        zone.enemyRangedStrength = 0
        zone.friendlyMeleeStrength = 100
        ReinforcementMoves.plotReinforcementMoves(ctx, zone)
        assertEquals(tile(0, -5), extra.getTile())
    }

    @Test
    fun `the ranged dominance flag is friendly with none of theirs, and compares the strengths by the percentage otherwise`() {
        val zone = context().map.zoneByCity(city, false)!!
        zone.friendlyRangedStrength = 100
        zone.enemyRangedStrength = 0
        assertEquals(TacticalDominance.Friendly, zone.rangedDominance(100))
        zone.enemyRangedStrength = 50
        assertEquals("ratio 200 is not above 200", TacticalDominance.Even, zone.rangedDominance(100))
        zone.enemyRangedStrength = 49
        assertEquals(TacticalDominance.Friendly, zone.rangedDominance(100))
        zone.friendlyRangedStrength = 0
        zone.enemyRangedStrength = 10
        assertEquals("ratio 0 is below 0", TacticalDominance.Even, zone.rangedDominance(100))
        zone.rangedDominance(50).let { assertEquals(TacticalDominance.Enemy, it) }
    }
}
