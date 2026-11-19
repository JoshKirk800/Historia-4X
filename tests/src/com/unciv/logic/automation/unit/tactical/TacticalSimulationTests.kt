package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The tactical combat simulation (Vox Populi's CvTacticalAI.cpp FindBestUnitAssignments / ExecuteUnitAssignments and their callers) with Brave
 *  New World data. The scenarios are made so that the plan the simulation has to find is the only reasonable one; the units' health is set from
 *  the damage the code itself expects ([BattleDamage] at the mean of the randomness), with a margin for the real combat, whose randomness is
 *  fixed by the turn and the tile.
 */
@RunWith(BaseTestRunner::class)
class TacticalSimulationTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        // supplied, so that the enemy's Swordsmen and Horsemen are not weakened by missing resources (CvUnit::GetStrategicResourceCombatPenalty)
        enemy = game.addCiv("Provides [100] [Iron]", "Provides [100] [Horses]")
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int): Tile = game.tileMap[0, y]

    private fun context(vararg units: MapUnit): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList().ifEmpty { civ.units.getCivUnits().toList() }, TacticalTargets.find(civ, map, danger))
    }

    private fun meanDamage(attacker: MapUnit, defender: MapUnit) =
        BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), MapUnitCombatant(defender), attacker.getTile(), 0.5f, ignoreFlanking = true)

    private fun plan(ctx: TacticalContext, units: List<MapUnit>, target: Tile, level: AggressionLevel) =
        TacticalSimSearch.findBestUnitAssignments(ctx, units, target, level, targetDistanceRelevant = true)

    /** A melee unit next to a weak enemy that it kills, and an archer that shoots a second one from where it cannot be reached: the unit set the plans are made for */
    private fun twoVictims(): Triple<List<MapUnit>, MapUnit, MapUnit> {
        val swordsman = game.addUnit("Swordsman", civ, tile(-1))
        val archer = game.addUnit("Archer", civ, tile(5))
        val first = game.addUnit("Warrior", enemy, tile(0))
        val second = game.addUnit("Warrior", enemy, tile(3))
        // Each unit can reach only one of them; the margin covers the randomness of the real combat (a quarter below the mean, at most)
        first.health = maxOf(1, meanDamage(swordsman, first) * 3 / 4)
        second.health = maxOf(1, meanDamage(archer, second) * 3 / 4)
        return Triple(listOf(swordsman, archer), first, second)
    }

    // region The plan

    @Test
    fun `units that each can only reach one of two enemies kill both, the melee unit taking the tile it killed on`() {
        val (units, first, second) = twoVictims()
        val ctx = context()
        assertTrue(TacticalSimulation.findAndExecuteBestUnitAssignments(ctx, units, first.getTile(), AggressionLevel.Medium))
        assertTrue("the melee unit's enemy is dead", first.isDestroyed)
        assertTrue("so is the one the archer shot", second.isDestroyed)
        assertEquals("the swordsman advanced onto the tile it cleared", tile(0), units[0].getTile())
        assertEquals("the archer shot from where it stood", tile(5), units[1].getTile())
    }

    @Test
    fun `a plan whose first step finds the situation changed is not carried out, and nobody has done anything`() {
        val swordsman = game.addUnit("Swordsman", civ, tile(-1))
        val victim = game.addUnit("Warrior", enemy, tile(0))
        victim.health = 5
        val ctx = context()
        val result = plan(ctx, listOf(swordsman), victim.getTile(), AggressionLevel.Medium)
        assertTrue("there is a plan to kill it", result.assignments.any { it.type == AssignmentType.MeleeKill })

        victim.destroy() // somebody else was quicker
        assertFalse(TacticalSimulation.executeUnitAssignments(ctx, result))
        assertEquals(0, swordsman.attacksThisTurn)
        assertEquals(tile(-1), swordsman.getTile())
        assertTrue(swordsman !in ctx.cache.processedUnits)
    }

    @Test
    fun `a defender that survives where the plan expected a kill ends the plan, the unit stays where it was`() {
        val swordsman = game.addUnit("Swordsman", civ, tile(-1))
        val victim = game.addUnit("Warrior", enemy, tile(0))
        victim.health = 5
        val ctx = context()
        val result = plan(ctx, listOf(swordsman), victim.getTile(), AggressionLevel.Medium)
        assertTrue(result.assignments.any { it.type == AssignmentType.MeleeKill })

        victim.health = 100 // healed in the meantime: the attack will not kill it
        assertFalse(TacticalSimulation.executeUnitAssignments(ctx, result))
        assertFalse(victim.isDestroyed)
        assertEquals("the attack was made, the unit did not advance", tile(-1), swordsman.getTile())
    }

    @Test
    fun `a move the real movement cannot make drops the plan instead of throwing, the unit stays where it is`() {
        val warrior = game.addUnit("Warrior", civ, tile(-3)) // two movement points
        val ctx = context()
        val world = SimWorld(ctx, tile(0), listOf(warrior), AggressionLevel.Medium, targetDistanceRelevant = false, returnToStart = false, saveMovement = 0f)
        val far = tile(1) // four tiles away
        assertTrue("the scenario has a step beyond the unit's reach", far.aerialDistanceTo(warrior.getTile()) > warrior.currentMovement)
        val step = SimAssignment(AssignmentType.Move, 0, world.plotOf(warrior.getTile()), world.plotOf(far), 0f, MoveStrategy.FirstLine, 0)

        assertFalse(TacticalSimulation.executeUnitAssignments(ctx, SimResult(world, listOf(step), emptySet(), 1, 1)))
        assertEquals(tile(-3), warrior.getTile())
        assertTrue(warrior !in ctx.cache.processedUnits)
    }

    @Test
    fun `a unit moves through the tile of an enemy killed earlier in the plan, whatever paths the AI cached for it before`() {
        val victim = game.addUnit("Warrior", enemy, tile(0))
        victim.health = 5
        val killer = game.addUnit("Swordsman", civ, victim.getTile().neighbors.first { it != tile(-1) && it != tile(1) })
        val rider = game.addUnit("Horseman", civ, tile(-1))
        val beyond = tile(2)
        val ctx = context()
        // The enemy is in the way, with its zone of control: the paths of the rider, cached as a unit that has moved this turn has them, do not
        // lead beyond it
        rider.movement.clearPathfindingCache()
        assertFalse(rider.movement.getDistanceToTiles().containsKey(beyond))
        val world = SimWorld(ctx, victim.getTile(), listOf(killer, rider), AggressionLevel.Medium, targetDistanceRelevant = false, returnToStart = false, saveMovement = 0f)
        val killerIndex = world.units.indexOfFirst { it.unit == killer }
        val riderIndex = world.units.indexOfFirst { it.unit == rider }
        val steps = listOf(
            SimAssignment(AssignmentType.MeleeKill, killerIndex, world.plotOf(killer.getTile()), world.plotOf(victim.getTile()), 1f, MoveStrategy.FirstLine, 0),
            SimAssignment(AssignmentType.Move, riderIndex, world.plotOf(rider.getTile()), world.plotOf(beyond), 1f, MoveStrategy.FirstLine, 0),
        )

        assertTrue(TacticalSimulation.executeUnitAssignments(ctx, SimResult(world, steps, emptySet(), 1, 2)))
        assertTrue(victim.isDestroyed)
        assertEquals(beyond, rider.getTile())
    }

    // endregion
    // region Cities
    /** An enemy city with [count] units of ours next to it. */
    private fun besiegedCity(unitName: String, count: Int): Pair<City, List<MapUnit>> {
        val city = game.addCity(enemy, tile(0), replacePalace = true, initialPopulation = 4)
        val units = city.getCenterTile().neighbors.filter { it.militaryUnit == null && !it.isCityCenter() }.take(count).map { game.addUnit(unitName, civ, it) }.toList()
        return city to units
    }

    private fun siegeContext(city: City, dominance: TacticalDominance = TacticalDominance.Even): TacticalContext {
        val ctx = context()
        ctx.map.zoneByCity(city, false)!!.dominance = dominance
        return ctx
    }

    @Test
    fun `a city is not attacked while it is too early, and is when the units do more than it has hit points`() {
        val (city, units) = besiegedCity("Warrior", 1)
        var ctx = siegeContext(city)
        var expected = TacticalSimulation.computeTotalExpectedDamage(ctx, city.getCenterTile(), TacticalSimulation.findUnitsWithinStrikingDistance(ctx, city.getCenterTile()))
        val healed = 20
        val full = city.health
        assertTrue("expected=$expected healed=$healed full=$full", expected > 0 && (expected - healed) * 13 < full && expected < full)

        TacticalSimulation.executeCaptureCityMoves(ctx)
        assertEquals("too early", full, city.health)
        assertTrue(units.all { it.attacksThisTurn == 0 })

        val more = city.getCenterTile().neighbors.filter { it.militaryUnit == null && !it.isCityCenter() }.take(3).map { game.addUnit("Swordsman", civ, it) }.toList()
        ctx = siegeContext(city)
        expected = TacticalSimulation.computeTotalExpectedDamage(ctx, city.getCenterTile(), TacticalSimulation.findUnitsWithinStrikingDistance(ctx, city.getCenterTile()))
        city.health = maxOf(2, expected / 2) // what the units do in a turn is enough
        val before = city.health
        TacticalSimulation.executeCaptureCityMoves(ctx)
        assertTrue("the units attacked", city.health != before || city.civ == civ)
        assertTrue((units + more).any { it.attacksThisTurn > 0 })
    }

    @Test
    fun `a city in a zone the enemy dominates is attacked only if it is about to fall`() {
        val (city, units) = besiegedCity("Swordsman", 4)
        city.health = 2
        city.damageTakenLastTurn = 0
        TacticalSimulation.executeCaptureCityMoves(siegeContext(city, TacticalDominance.Enemy))
        assertTrue("not hurt lately: left alone", units.all { it.attacksThisTurn == 0 })
        assertEquals(enemy, city.civ)

        city.damageTakenLastTurn = 50
        TacticalSimulation.executeCaptureCityMoves(siegeContext(city, TacticalDominance.Enemy))
        assertTrue("about to fall: attacked", units.any { it.attacksThisTurn > 0 } || city.civ == civ)
    }

    // endregion
    // region Movement

    @Test
    fun `a unit takes an undefended enemy settler by moving onto it`() {
        val settler = game.addUnit("Settler", enemy, tile(3))
        val horseman = game.addUnit("Horseman", civ, tile(0))
        val ctx = context()
        val result = plan(ctx, listOf(horseman), settler.getTile(), AggressionLevel.Medium)
        assertTrue("the plan captures it", result.assignments.any { it.type == AssignmentType.Capture })
        assertTrue(TacticalSimulation.executeUnitAssignments(ctx, result))
        assertEquals(tile(3), horseman.getTile())
        assertEquals(civ, tile(3).civilianUnit?.civ)
        assertNotEquals(enemy, settler.civ)
    }

    // endregion
    // region Restarts

    @Test
    fun `a move that shows an enemy nobody saw ends the plan with a restart`() {
        val scout = game.addUnit("Warrior", civ, tile(2))
        val other = game.addUnit("Warrior", civ, tile(1))
        game.addUnit("Warrior", enemy, tile(5))
        val ctx = context()
        // Nobody sees the tile of the hidden enemy
        civ.viewableTiles = game.tileMap.values.toSet() - tile(5)
        val world = SimWorld(ctx, tile(2), listOf(scout, other), AggressionLevel.Medium, targetDistanceRelevant = false, returnToStart = false, saveMovement = 0f)
        val index = world.units.indexOfFirst { it.unit == scout }
        val from = world.plotOf(tile(2))
        val to = world.plotOf(tile(3))
        assertEquals("nothing new to see from where it is", 0, world.reveal(index, from)[1])
        assertEquals("one enemy to see from the next tile", 1, world.reveal(index, to)[1])

        val root = SimPosition.root(world)
        root.startUnits(SimScoring(world))
        val move = SimAssignment(AssignmentType.Move, index, from, to, 1f, MoveStrategy.FirstLine, 0)
        move.setScore(0, 0, 0)
        assertEquals(AddResult.AddedWithRestart, root.addAssignment(move))
    }

    // endregion
    // region Callers

    @Test
    fun `the targets are marked with the aggression they were attacked with, and attacked again only with more`() {
        val victim = game.addUnit("Swordsman", enemy, tile(0))
        game.addUnit("Archer", civ, tile(-2))
        val ctx = context()
        val target = ctx.targets.all.first { it.unit == victim }
        assertEquals(AggressionLevel.None, target.lastAggressionLevel)
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Low)
        assertEquals(AggressionLevel.Low, target.lastAggressionLevel)
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Low)
        assertEquals("the same level does nothing new", AggressionLevel.Low, target.lastAggressionLevel)
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Medium)
        assertEquals(AggressionLevel.Medium, target.lastAggressionLevel)
    }

    @Test
    fun `an army in contact with the enemy fights it with the simulation and says so`() {
        val victim = game.addUnit("Warrior", enemy, tile(0))
        victim.health = 5
        val swordsman = game.addUnit("Swordsman", civ, tile(-1))
        val ctx = context()
        assertTrue(TacticalSimulation.checkForEnemiesNearArmy(ctx, listOf(swordsman)))
        assertTrue(victim.isDestroyed)
        assertTrue(swordsman in ctx.cache.processedUnits)
        assertFalse("an army that sees nobody does not fight", TacticalSimulation.checkForEnemiesNearArmy(context(), listOf(game.addUnit("Swordsman", civ, tile(9)))))
    }

    @Test
    fun `a barbarian next to a city of ours that is not the capital is attacked in the tactical turn, though the zone strengths leave barbarians out`() {
        val barbarians = game.addBarbarianCiv()
        game.addCity(civ, tile(-9)) // the capital
        game.addCity(civ, tile(0))
        val intruder = game.addUnit("Warrior", barbarians, tile(1))
        intruder.health = 5
        game.addUnit("Swordsman", civ, tile(2))
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        assertEquals("barbarians are no part of the strengths", 0, civ.ai.tactical.map.zoneOf(tile(0))!!.overallEnemyStrength)

        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), AutomationCache())
        assertTrue(intruder.isDestroyed)
    }

    // endregion
    // region Damage

    @Test
    fun `the flanking of the units around a defender is part of the damage unless it is asked to be left out`() {
        val defender = game.addUnit("Swordsman", enemy, tile(0))
        val attacker = game.addUnit("Swordsman", civ, tile(-1))
        val attackerCombatant = MapUnitCombatant(attacker)
        val defenderCombatant = MapUnitCombatant(defender)
        fun damage(ignoreFlanking: Boolean) = BattleDamage.calculateDamageToDefender(attackerCombatant, defenderCombatant, attacker.getTile(), 0.5f, ignoreFlanking = ignoreFlanking)

        val alone = damage(false)
        game.addUnit("Swordsman", civ, defender.getTile().neighbors.first { it != attacker.getTile() })
        assertTrue("a second unit next to the defender adds the flanking bonus", damage(false) > alone)
        assertEquals("left out, it is as if it was not there", alone, damage(true))
    }

    // endregion
}
