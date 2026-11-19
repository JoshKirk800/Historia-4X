package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's opportunity attacks and the moves that use them (Brave New World data): TacticalAIHelpers::PerformOpportunityAttack,
 *  KillLoneEnemyIfPossible, CvUnit::isProjectedToDieNextTurn, CvUnit::shouldHeal, CvTacticalAI::PlotHealMoves / ExecuteMovesToSafestPlot.
 */
@RunWith(BaseTestRunner::class)
class TacticalOpportunityTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        enemy = game.addCiv("Provides [100] [Iron]", "Provides [100] [Horses]")
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun context(vararg units: MapUnit): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList().ifEmpty { civ.units.getCivUnits().toList() }, TacticalTargets.find(civ, map, danger))
    }

    // region Damage taken per turn

    @Test
    fun `a unit is projected to die when last turn's damage exceeds its health`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        MapUnitCombatant(unit).takeDamage(60)
        assertEquals(60, unit.damageTakenThisTurn)
        assertFalse("this turn's damage is not last turn's", unit.isProjectedToDieNextTurn())
        unit.flipDamageReceivedPerTurn()
        assertEquals(0, unit.damageTakenThisTurn)
        assertEquals(60, unit.damageTakenLastTurn)
        assertTrue(unit.isProjectedToDieNextTurn()) // 60 > 40 hit points left
        unit.healBy(30)
        assertFalse(unit.isProjectedToDieNextTurn()) // 70 hit points
    }

    // endregion
    // region Opportunity attacks

    @Test
    fun `a strong unit kills the only enemy that can hit it, a weak one does not`() {
        val strong = game.addUnit("Swordsman", civ, tile(0))
        val victim = game.addUnit("Warrior", enemy, tile(1))
        victim.health = 20
        assertTrue(OpportunityAttacks.killLoneEnemyIfPossible(strong, victim))
        assertTrue(victim.isDestroyed)

        val weak = game.addUnit("Warrior", civ, tile(6))
        weak.health = 30
        val brute = game.addUnit("Swordsman", enemy, tile(7))
        assertFalse(OpportunityAttacks.killLoneEnemyIfPossible(weak, brute))
        assertFalse(brute.isDestroyed)
    }

    @Test
    fun `a melee garrison attacks an adjacent enemy without leaving its city`() {
        val city = game.addCity(civ, tile(0), initialPopulation = 1)
        val garrison = game.addUnit("Swordsman", civ, city.getCenterTile())
        val victim = game.addUnit("Warrior", enemy, tile(1))
        val ctx = context(garrison)
        assertTrue(OpportunityAttacks.perform(ctx, garrison))
        assertTrue("the enemy was hit", victim.isDestroyed || victim.health < 100)
        assertEquals("the garrison stays in the city", city.getCenterTile(), garrison.getTile())
    }

    @Test
    fun `a melee unit does not make a suicidal opportunity attack`() {
        val weak = game.addUnit("Warrior", civ, tile(0))
        weak.health = 20
        val brute = game.addUnit("Swordsman", enemy, tile(1))
        val ctx = context(weak)
        assertFalse(OpportunityAttacks.perform(ctx, weak, allowMovement = true))
        assertEquals(100, brute.health)
        assertEquals(tile(0), weak.getTile())
    }

    // endregion
    // region Healing

    @Test
    fun `a unit heals when it lost more than 20 hit points, or 50 if it fights well wounded`() {
        val unit = game.addUnit("Warrior", civ, tile(-12))
        game.addCity(civ, tile(-14), initialPopulation = 1)
        civ.cache.updateViewableTiles()
        unit.health = 81
        assertFalse(TacticalHealing.shouldHeal(civ, unit, false))
        unit.health = 79
        assertTrue(TacticalHealing.shouldHeal(civ, unit, false))
        assertFalse("before the attacks only a badly hurt unit heals", TacticalHealing.shouldHeal(civ, unit, true))
        unit.health = 12
        assertTrue(TacticalHealing.shouldHeal(civ, unit, true))
    }

    @Test
    fun `a unit next to an enemy heals only if it is projected to die`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 30
        game.addUnit("Archer", enemy, tile(1))
        game.addUnit("Swordsman", enemy, tile(-1))
        context(unit)
        assertFalse(TacticalHealing.shouldHeal(civ, unit, false))
        unit.damageTakenLastTurn = 80
        civ.ai.invalidateTurnCaches()
        context(unit)
        assertTrue(TacticalHealing.shouldHeal(civ, unit, false))
    }

    @Test
    fun `a hurt unit with nothing around heals where it stands keeping its movement and is marked processed`() {
        val unit = game.addUnit("Warrior", civ, tile(-12))
        unit.health = 60
        val ctx = context(unit)
        TacticalHealing.plotHealMoves(ctx, false)
        assertEquals(tile(-12), unit.getTile())
        assertTrue("it keeps its movement, or it would never heal", unit.currentMovement > 0f)
        assertTrue(unit in ctx.cache.processedUnits)
        assertTrue(unit.isFortified())
    }

    // endregion
    // region Safety

    @Test
    fun `a unit that must flee goes to a city of ours and is processed`() {
        val city = game.addCity(civ, tile(-2), initialPopulation = 1)
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 40
        game.addUnit("Swordsman", enemy, tile(2))
        game.addUnit("Archer", enemy, tile(3))
        val ctx = context(unit)
        SafestPlot.executeMovesToSafestPlot(ctx, unit)
        assertEquals(city.getCenterTile(), unit.getTile())
        assertTrue(unit in ctx.cache.processedUnits)
    }

    @Test
    fun `units flee only when the danger is above their hit points or they are projected to die`() {
        val city = game.addCity(civ, tile(-2), initialPopulation = 1)
        val brave = game.addUnit("Swordsman", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(2))
        val ctx = context(brave)
        assertTrue("a lone warrior does 100 damage to nobody", civ.ai.unitDanger.get(brave) <= 100)
        SafestPlot.plotMovesToSafety(ctx, true)
        assertEquals(tile(0), brave.getTile())
        assertNull(city.getCenterTile().militaryUnit)

        brave.damageTakenLastTurn = 200
        val ctx2 = context(brave)
        SafestPlot.plotMovesToSafety(ctx2, true)
        assertNotEquals("projected to die: it flees", tile(0), brave.getTile())
    }

    @Test
    fun `a garrison does not flee`() {
        val city = game.addCity(civ, tile(0), initialPopulation = 1)
        val garrison = game.addUnit("Warrior", civ, city.getCenterTile())
        garrison.damageTakenLastTurn = 200
        game.addUnit("Swordsman", enemy, tile(2))
        val ctx = context(garrison)
        SafestPlot.plotMovesToSafety(ctx, true)
        assertEquals(city.getCenterTile(), garrison.getTile())
    }

    @Test
    fun `fleeing to a tile held by a unit that cannot swap back falls back instead of throwing`() {
        // the best plot is a city of ours held by a unit with no movement left: the two cannot change places
        val city = game.addCity(civ, tile(-2), initialPopulation = 1)
        val holder = game.addUnit("Warrior", civ, city.getCenterTile())
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 40
        game.addUnit("Swordsman", enemy, tile(2))
        game.addUnit("Archer", enemy, tile(3))
        val ctx = context(unit)
        // a stale reach: the unit looked at the map before the holder used its movement up
        unit.movement.getDistanceToTiles()
        holder.currentMovement = 0f
        SafestPlot.executeMovesToSafestPlot(ctx, unit)
        assertTrue(unit in ctx.cache.processedUnits)
        assertEquals("the holder is where it was", city.getCenterTile(), holder.getTile())
        assertNotEquals("the unit went somewhere else", city.getCenterTile(), unit.getTile())
    }

    // endregion
}
