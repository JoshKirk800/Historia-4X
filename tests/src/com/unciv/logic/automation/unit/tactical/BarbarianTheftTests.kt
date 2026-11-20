package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stats
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvTacticalAI::ExecuteBarbarianTheft with CvBarbarians::DoStealFromCity (Vox Populi, MOD_BALANCE_BARBARIAN_THEFT) with Brave New World data: a barbarian
 *  next to a city of ours at (0, 0) that has no garrison and more than 20 hit points left robs it of 1 to 4 turns plus the damage of the attack / 30 of
 *  one yield (10 of each here), at most as many points as the unit has left, and takes 1 to 30 damage for it.
 */
@RunWith(BaseTestRunner::class)
class BarbarianTheftTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var barbarians: Civilization
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(12)
        barbarians = game.addBarbarianCiv()
        barbarians.ai.setTransients(barbarians)
        game.gameInfo.barbarians.gameInfo = game.gameInfo
        civ = game.addCiv()
        game.setDifficulty("Prince")
        city = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        city.cityStats.currentCityStats = Stats(production = 10f, food = 10f, gold = 10f, science = 10f, culture = 10f)
        city.population.foodStored = 200
        civ.tech.techsToResearch.add("Pottery")
        city.cityConstructions.setCurrentConstruction("Factory")
        city.cityConstructions.inProgressConstructions["Factory"] = 60
        civ.addGold(1000 - civ.gold)
        civ.policies.storedCulture = 1000
        for (tile in game.tileMap.values) tile.setExplored(barbarians, true)
    }

    private fun thief(x: Int = 1, y: Int = 0): MapUnit = game.addUnit("Warrior", barbarians, game.getTile(HexCoord(x, y)))

    private fun context(vararg units: MapUnit): TacticalContext {
        barbarians.viewableTiles = game.tileMap.values.toSet()
        barbarians.ai.invalidateTurnCaches()
        return TacticalContext(barbarians, null, barbarians.ai.plotDanger, AutomationCache(), units.toList(), TacticalTargets.none())
    }

    /** What the city's owner has in the five yields a thief can take, in the order gold, culture, science, food, production */
    private fun holdings() = listOf(civ.gold, civ.policies.storedCulture, civ.tech.researchOfTech("Pottery"), city.population.foodStored,
        city.cityConstructions.getWorkDone("Factory"))

    private fun resetHoldings() {
        civ.addGold(1000 - civ.gold)
        civ.policies.storedCulture = 1000
        civ.tech.techsInProgress["Pottery"] = 1000
        city.population.foodStored = 200
        city.cityConstructions.inProgressConstructions["Factory"] = 60
    }

    @Test
    fun `a city with a garrison, one with 20 hit points or less, and a unit that cannot attack or has one hit point left steal nothing`() {
        val unit = thief()
        val ctx = context(unit)
        val before = holdings()
        // 21 hit points is more than 20 left: the only thing that stops a theft here is the garrison
        city.health = 21
        val worker = game.addUnit("Worker", barbarians, game.getTile(HexCoord(0, 1)))
        assertFalse("a worker cannot attack", BarbarianTheft.stealFromCity(context(worker), worker, city))
        unit.health = 1
        assertFalse("a unit with one hit point left is not a thief", BarbarianTheft.stealFromCity(ctx, unit, city))
        unit.health = 100
        city.health = 20
        assertFalse("20 hit points: take it over outright instead", BarbarianTheft.stealFromCity(ctx, unit, city))
        city.health = 200
        val garrison = game.addUnit("Warrior", civ, city.getCenterTile())
        assertTrue(city.getGarrison() == garrison)
        assertFalse(BarbarianTheft.stealFromCity(ctx, unit, city))
        assertEquals(100, unit.health)
        assertEquals(before, holdings())
    }

    @Test
    fun `a theft takes 1 to 4 turns plus the damage over 30 of one yield and 1 to 30 hit points from the thief, and every yield is taken in turn`() {
        val unit = thief()
        val ctx = context(unit)
        val cityDamage = BattleDamage.calculateDamageToDefender(
            MapUnitCombatant(unit), CityCombatant(city), unit.getTile(), TacticalTargets.EXPECTED_RANDOMNESS, ignoreFlanking = true)
        val minTurns = cityDamage / 30 + 1
        val maxTurns = cityDamage / 30 + 4
        val stolen = HashSet<Int>()
        var thefts = 0
        for (turn in 1..150) {
            game.gameInfo.turns = turn
            unit.health = 100
            resetHoldings()
            val before = holdings()
            if (!BarbarianTheft.stealFromCity(ctx, unit, city)) {
                assertEquals("nothing taken, nothing paid", 100, unit.health)
                assertEquals(before, holdings())
                continue
            }
            thefts++
            val taken = before.zip(holdings()).mapIndexedNotNull { index, (was, now) -> if (was != now) index to was - now else null }
            assertEquals("one yield per theft", 1, taken.size)
            val (yield, amount) = taken.single()
            stolen.add(yield)
            assertTrue("1 to 30 damage: ${unit.health}", unit.health in 70..99)
            assertTrue("$amount is 10 a turn for $minTurns to $maxTurns turns", amount % 10 == 0 && amount / 10 in minTurns..maxTurns)
        }
        assertTrue("it steals on most turns: $thefts", thefts > 100)
        assertEquals("gold, culture, science, food and production, 2 in 10 each", setOf(0, 1, 2, 3, 4), stolen)
    }

    @Test
    fun `a city with 21 hit points is still robbed`() {
        city.health = 21
        val unit = thief()
        val ctx = context(unit)
        var thefts = 0
        for (turn in 1..30) {
            game.gameInfo.turns = turn
            unit.health = 100
            if (BarbarianTheft.stealFromCity(ctx, unit, city)) thefts++
        }
        assertTrue("$thefts thefts", thefts > 0)
    }

    @Test
    fun `no more is stolen than the thief has hit points left`() {
        // 5 turns of 20 would be 100 and more: the hit points left of the thief cap what is taken
        city.cityStats.currentCityStats = Stats(production = 20f, food = 20f, gold = 20f, science = 20f, culture = 20f)
        val unit = thief()
        val ctx = context(unit)
        for (turn in 1..60) {
            game.gameInfo.turns = turn
            unit.health = 40
            resetHoldings()
            val before = holdings()
            if (!BarbarianTheft.stealFromCity(ctx, unit, city)) continue
            val taken = before.zip(holdings()).sumOf { (was, now) -> was - now }
            assertTrue("$taken stolen, ${unit.health} hit points left", taken in 1..unit.health)
        }
    }

    @Test
    fun `the thieves next to a city are done for the turn, a unit that is not next to one is left alone`() {
        val nextTo = thief()
        val far = thief(6, 0)
        val ctx = context(nextTo, far)
        for (turn in 1..20) {
            game.gameInfo.turns = turn
            BarbarianTheft.executeTheft(ctx)
            if (nextTo in ctx.cache.processedUnits) break
        }
        assertTrue(nextTo in ctx.cache.processedUnits)
        assertFalse("a thief has no movement left", nextTo.hasMovement())
        assertFalse(far in ctx.cache.processedUnits)
        assertTrue(far.hasMovement())
    }
}
