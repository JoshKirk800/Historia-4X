package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.Battle
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The tactical targets and the attacks that are not the combat simulation's (CvTacticalAI::FindTacticalTargets, FindUnitsWithinStrikingDistance,
 *  launching a series of attacks, safe and protected bombards, civilians, pillaging, garrisons) with Brave New World data. The simulation's
 *  attacks are [TacticalSimulationTests]'.
 */
@RunWith(BaseTestRunner::class)
class TacticalAttackTests {
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

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun context(vararg units: MapUnit): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList().ifEmpty { civ.units.getCivUnits().toList() }, TacticalTargets.find(civ, map, danger))
    }

    private fun target(ctx: TacticalContext, tile: Tile) = ctx.targets.all.first { it.tile == tile }

    private fun targetType(unit: MapUnit): TacticalTargetType {
        val ctx = context()
        return ctx.targets.all.first { it.unit == unit }.type
    }

    private fun neighbors(tile: Tile, count: Int) = tile.neighbors.take(count).toList()

    // region Targets

    @Test
    fun `units that can take one of our cities together are high priority targets, the ranged ones medium, units that can only hurt it medium, the rest low`() {
        val city = game.addCity(civ, tile(0))
        city.health = 30
        val horsemen = listOf(2, 3).map { game.addUnit("Horseman", enemy, tile(it)) }
        val archer = game.addUnit("Archer", enemy, tile(-2))
        val far = game.addUnit("Warrior", enemy, tile(12))
        for (horseman in horsemen) assertEquals(TacticalTargetType.HighPriorityUnit, targetType(horseman))
        assertEquals(TacticalTargetType.MediumPriorityUnit, targetType(archer))
        assertEquals(TacticalTargetType.LowPriorityUnit, targetType(far))

        city.health = 200 // now they hurt it but cannot take it
        for (horseman in horsemen) assertEquals(TacticalTargetType.MediumPriorityUnit, targetType(horseman))
        assertEquals(TacticalTargetType.LowPriorityUnit, targetType(far))
    }

    @Test
    fun `a unit on a citadel in its own land is a high priority target`() {
        val enemyCity = game.addCity(enemy, tile(8))
        tile(6).setOwningCity(enemyCity)
        val unit = game.addUnit("Warrior", enemy, tile(6))
        assertEquals(enemy, unit.getTile().getOwner())
        assertEquals(TacticalTargetType.LowPriorityUnit, targetType(unit))
        unit.getTile().setImprovement("Citadel")
        assertEquals(TacticalTargetType.HighPriorityUnit, targetType(unit))
    }

    @Test
    fun `civilians are sorted by what they are worth to us`() {
        val general = game.addUnit("Great General", enemy, tile(3))
        val settler = game.addUnit("Settler", enemy, tile(5))
        val worker = game.addUnit("Worker", enemy, tile(7))
        assertEquals(TacticalTargetType.VeryHighPriorityCivilian, targetType(general))
        assertEquals("settlers are high priority in the early game", TacticalTargetType.HighPriorityCivilian, targetType(settler))
        assertEquals("so are workers medium", TacticalTargetType.MediumPriorityCivilian, targetType(worker))

        game.gameInfo.turns = 400 // late in the game
        for (i in 1..5) game.addCity(enemy, tile(-3 * i)) // the settler's owner is no longer small
        assertEquals("a late settler of a big civ is only medium", TacticalTargetType.MediumPriorityCivilian, targetType(settler))
        assertEquals("a late worker is low", TacticalTargetType.LowPriorityCivilian, targetType(worker))
    }

    @Test
    fun `targets are sorted by damage, the most damaged unit first`() {
        val healthy = game.addUnit("Warrior", enemy, tile(5))
        val hurt = game.addUnit("Warrior", enemy, tile(7)).also { it.health = 40 }
        val units = context().targets.all.filter { it.isUnitTarget }.map { it.unit }
        assertEquals(listOf(hurt, healthy), units)
    }

    // endregion
    // region Cities

    private fun enemyCity(position: Int): City = game.addCity(enemy, tile(position))

    private fun melee(count: Int, around: Tile, name: String = "Swordsman") =
        around.neighbors.filter { it.militaryUnit == null && !it.isCityCenter() }.take(count).map { game.addUnit(name, civ, it) }.toList()

    // endregion
    // region Units: destroy and attrition

    private fun defender(position: Int, name: String = "Swordsman"): MapUnit = game.addUnit(name, enemy, tile(position))

    // endregion
    // region Launching attacks

    @Test
    fun `a series stops when the target is dead and the rest of the units stay free`() {
        val victim = defender(0, "Warrior")
        victim.health = 3
        val attackers = melee(3, victim.getTile(), "Swordsman")
        val ctx = context()
        val series = AttackSeries(target(ctx, victim.getTile()))
        for (attacker in attackers) series.addUnit(attacker, false, null)
        assertTrue(TacticalAttacks.launch(ctx, series))
        assertTrue(victim.isDestroyed)
        assertEquals("one attacked, the others were released", 1, attackers.count { it.attacksThisTurn > 0 })
        assertEquals(2, ctx.units.size)
    }

    // endregion
    // region Bombards

    @Test
    fun `a ranged unit already on a tile the enemy cannot attack bombards from there`() {
        val victim = defender(0, "Swordsman")
        val artillery = game.addUnit("Artillery", civ, tile(-3)) // range 3, the swordsman moves 2
        val ctx = context()
        val map = ctx.map
        assertTrue("the enemy reaches its neighbors", map.isSubjectToAttack(tile(-1)) && map.isSubjectToAttack(tile(-2)))
        assertFalse("but not what is 3 tiles from it", map.isSubjectToAttack(tile(-3)))

        assertTrue(TacticalBombards.safeBombardTarget(ctx, target(ctx, victim.getTile())))
        assertEquals(tile(-3), artillery.getTile())
        assertEquals(1, artillery.attacksThisTurn)
        assertTrue(victim.health < 100)
        assertTrue(artillery in ctx.cache.processedUnits)
    }

    @Test
    fun `a ranged unit moves to a tile the enemy cannot attack to bombard`() {
        // A ship in a small bay: the land around it is out of its reach
        val lake = tile(0).getTilesInDistance(1).toList()
        for (tile in lake) game.setTileTerrain(tile.position, "Coast")
        val victim = game.addUnit("Trireme", enemy, tile(0))
        val archer = game.addUnit("Archer", civ, tile(-3))
        val ctx = context(archer)
        val shore = victim.getTile().getTilesAtDistance(2).toList()
        assertTrue(shore.none { ctx.map.isSubjectToAttack(it) })

        assertTrue(TacticalBombards.safeBombardTarget(ctx, target(ctx, victim.getTile())))
        assertEquals("it moved to the shore", 2, archer.getTile().aerialDistanceTo(victim.getTile()))
        assertEquals(1, archer.attacksThisTurn)
        assertTrue(victim.health < 100)
    }

    @Test
    fun `a ranged unit that cannot get out of the enemy's reach does not make a safe bombard`() {
        val victim = defender(0, "Swordsman")
        val archer = game.addUnit("Archer", civ, tile(-2)) // range 2: every tile it shoots from can be attacked
        val ctx = context()
        assertTrue(ctx.map.isSubjectToAttack(archer.getTile()))
        assertFalse(TacticalBombards.safeBombardTarget(ctx, target(ctx, victim.getTile())))
        assertEquals(0, archer.attacksThisTurn)
        assertEquals(tile(-2), archer.getTile())
    }

    @Test
    fun `a ranged unit is used on a tile with a defender but not on one with only a civilian (IsExpectedToDamageWithRangedAttack)`() {
        val archer = game.addUnit("Archer", civ, tile(-1))
        game.addUnit("Worker", enemy, tile(0))
        game.addUnit("Swordsman", enemy, tile(1))
        val ctx = context()
        assertEquals(listOf(archer), TacticalAttacks.findUnits(ctx, tile(1), 1).map { it.unit })
        assertTrue("nobody to damage on the worker's tile", TacticalAttacks.findUnits(ctx, tile(0), 1).none { it.unit == archer })
    }

    /** A tile 2 from the target that two of the tiles next to the target touch, and the tile behind it for the archer */
    private fun coveredCell(target: Tile): Pair<Tile, Tile> {
        val cell = target.getTilesAtDistance(2).first { c -> c.neighbors.count { it.aerialDistanceTo(target) == 1 } == 2 }
        val behind = cell.neighbors.first { it.aerialDistanceTo(target) == 3 }
        return cell to behind
    }

    /** Tiles 2 from the target, next to the tiles that are between it and the covered cell: melee units there can take those this turn */
    private fun spearTiles(cell: Tile, target: Tile) =
        target.getTilesAtDistance(2).filter { it != cell && it.militaryUnit == null }.sortedBy { it.aerialDistanceTo(cell) }.toList()

    @Test
    fun `a protected bombard needs enough melee units to take the tiles the enemy would come through`() {
        val victim = defender(0, "Swordsman")
        val (cell, behind) = coveredCell(victim.getTile())
        val archer = game.addUnit("Archer", civ, behind)
        val ctx = context()
        val needed = cell.neighbors.count { !it.isWater && ctx.map.enemyCanMovePast(it) }
        assertEquals("the enemy comes through the two tiles between it and the cell", 2, needed)

        assertNull("no melee unit to cover", TacticalBombards.canCoverFromEnemy(ctx, cell, archer))
        val spearmen = (0..2).map { game.addUnit("Spearman", civ, spearTiles(cell, victim.getTile())[it]) }
        val ctx2 = context()
        val cover = TacticalBombards.canCoverFromEnemy(ctx2, cell, archer)!!
        assertEquals(needed, cover.numRequired)
        assertEquals("each covered tile gets another unit", needed, cover.blocks.map { it.unit }.toSet().size)
        assertEquals(needed, cover.blocks.map { it.tile }.toSet().size)
        assertTrue(cover.blocks.all { it.unit in spearmen })
    }

    @Test
    fun `a protected bombard covers the ranged unit with melee units and shoots`() {
        val victim = defender(0, "Swordsman")
        val (cell, behind) = coveredCell(victim.getTile())
        val archer = game.addUnit("Archer", civ, behind)
        val spearmen = (0..2).map { game.addUnit("Spearman", civ, spearTiles(cell, victim.getTile())[it]) }
        val ctx = context(archer, *spearmen.toTypedArray())
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(victim.getTile(), ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        assertTrue(TacticalBombards.executeOneProtectedBombard(ctx, target(ctx, victim.getTile())))
        assertEquals(1, archer.attacksThisTurn)
        assertEquals("it shot from a tile 2 from the target", 2, archer.getTile().aerialDistanceTo(victim.getTile()))
        assertTrue(victim.health < 100)
        val open = archer.getTile().neighbors.filter { !it.isWater && ctx.map.enemyCanMovePast(it) }.toList()
        assertTrue(open.isNotEmpty())
        assertTrue("every tile the enemy could come through is held by a unit that was sent there", open.all { tile ->
            tile.militaryUnit in spearmen && tile.militaryUnit in ctx.cache.processedUnits
        })
    }

    @Test
    fun `a mountain between hides the target from one side only, also when asked again`() {
        game.setTileTerrain(tile(1).position, Constants.mountain)
        val ctx = context()
        assertFalse(ctx.hasLineOfSight(tile(0), tile(2)))
        assertTrue("as far away on the other side, nothing between", ctx.hasLineOfSight(tile(4), tile(2)))
        assertFalse(ctx.hasLineOfSight(tile(0), tile(2)))
    }

    @Test
    fun `cover is assigned so that no unit is used twice even if the nearest unit fits two tiles`() {
        val first = tile(0)
        val second = tile(1)
        val a = game.addUnit("Spearman", civ, tile(5))
        val b = game.addUnit("Spearman", civ, tile(6))
        val choices = linkedMapOf(
            first to listOf(Candidate(a, 1, 1), Candidate(b, 1, 2)),
            second to listOf(Candidate(a, 1, 1)),
        )
        val blocks = TacticalBombards.assign(choices, canLeaveOpenings = false)!!
        assertEquals(setOf(a, b), blocks.map { it.unit }.toSet())
        assertEquals(b, blocks.first { it.tile == first }.unit)
        assertEquals(a, blocks.first { it.tile == second }.unit)
        assertNull("one tile has nobody left", TacticalBombards.assign(linkedMapOf(first to listOf(Candidate(a, 1, 1)), second to listOf(Candidate(a, 1, 1))), false))
    }

    // endregion
    // region Other moves

    @Test
    fun `an undefended settler is captured by the unit that can reach it`() {
        val settler = game.addUnit("Settler", enemy, tile(3))
        val unit = game.addUnit("Horseman", civ, tile(0))
        val ctx = context()
        TacticalMoves.plotCivilianAttackMoves(ctx, TacticalTargetType.HighPriorityCivilian)
        assertEquals(tile(3), unit.getTile())
        assertEquals(civ, tile(3).civilianUnit?.civ)
        assertTrue(unit in ctx.cache.processedUnits)
        assertNotEquals(enemy, settler.civ)
    }

    @Test
    fun `a unit in range pillages an enemy improvement and one that is hurt is preferred`() {
        val city = game.addCity(enemy, tile(6))
        val farm = tile(5)
        farm.setOwningCity(city)
        farm.setImprovement("Farm")
        // not next to the city: its zone of control would use up their movement
        val beside = farm.neighbors.filter { it.aerialDistanceTo(city.getCenterTile()) >= 2 }.toList()
        val healthy = game.addUnit("Warrior", civ, beside[0])
        val hurt = game.addUnit("Warrior", civ, beside[1]).also { it.health = 40 }
        val ctx = context()
        assertEquals(TacticalTargetType.Improvement, target(ctx, farm).type)
        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
        assertTrue(farm.improvementIsPillaged)
        assertEquals(farm, hurt.getTile())
        assertTrue(hurt in ctx.cache.processedUnits)
        assertFalse(healthy in ctx.cache.processedUnits)
    }

    @Test
    fun `a friendly city is a target only when a zone of somebody else touches it, with its border score as the sort key`() {
        val lone = game.addCity(civ, tile(-10))
        assertTrue("nobody around", context().targets.all.none { it.type == TacticalTargetType.CityToDefend })
        game.addCity(enemy, tile(0))
        val ctx = context()
        val target = ctx.targets.all.single { it.type == TacticalTargetType.CityToDefend }
        assertEquals(lone, target.city)
        assertTrue(target.aux > 0)
        assertEquals(CityThreat.borderScore(lone), target.aux)
    }

    @Test
    fun `a garrison is the ranged unit first, and a unit already in the city is no better`() {
        val city = game.addCity(civ, tile(0))
        game.addUnit("Warrior", civ, tile(2))
        val archer = game.addUnit("Archer", civ, tile(-2))
        val ctx = context()
        assertEquals(archer, UnitSelection.findUnitForThisMove(ctx, UnitMove.Garrison, city.getCenterTile(), 2))
    }

    @Test
    fun `a guard is never a ranged unit outside a citadel`() {
        val target = tile(4)
        val archer = game.addUnit("Archer", civ, tile(3))
        val spearman = game.addUnit("Spearman", civ, tile(1))
        val ctx = context()
        assertEquals(spearman, UnitSelection.findUnitForThisMove(ctx, UnitMove.Guard, target, 2))
        archer.destroy()
        spearman.destroy()
        assertNull(UnitSelection.findUnitForThisMove(context(), UnitMove.Guard, target, 2))
    }

    @Test
    fun `units for harassing come nearest first, and none from too far or of the wrong domain`() {
        val far = game.addUnit("Warrior", civ, tile(5))
        val near = game.addUnit("Warrior", civ, tile(2))
        val ctx = context()
        assertEquals(listOf(near, far), UnitSelection.findUnitsForHarassing(ctx, tile(0), 5, 0, 0, false, false, 2))
        assertEquals(listOf(near), UnitSelection.findUnitsForHarassing(ctx, tile(0), 5, 0, 0, false, false, 1))
        assertTrue(UnitSelection.findUnitsForHarassing(ctx, tile(0), 5, 0, 0, true, false, 2).isEmpty())
    }

    @Test
    fun `a garrison of a border city fights what is next to it without leaving, and is fortified and processed`() {
        val city = game.addCity(civ, tile(-10))
        game.addCity(enemy, tile(0))
        val garrison = game.addUnit("Swordsman", civ, city.getCenterTile())
        val victim = game.addUnit("Warrior", enemy, tile(-9))
        val ctx = context()
        GarrisonMoves.plotGarrisonMoves(ctx, 2)
        assertEquals(city.getCenterTile(), garrison.getTile())
        assertTrue("the garrison hit the enemy", victim.isDestroyed || victim.health < 100)
        assertTrue(garrison in ctx.cache.processedUnits)
        assertEquals(civ.gameInfo.turns, civ.ai.tactical.lastTurnGarrisonAssigned(city))
        assertTrue(garrison.isFortified())
    }

    // endregion
    // region Aircraft

    /** Our city at -6 holds the aircraft: a Bomber has range 10, so the enemy at 4 is in range and the one at 5 is not */
    private fun airBase() = game.addCity(civ, tile(-6)).getCenterTile()

    @Test
    fun `an aircraft with more than half its health strikes a defender in its range, and nothing else`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val near = defender(4)
        val far = defender(5)
        val worker = game.addUnit("Worker", enemy, tile(3))
        val nuke = game.addUnit("Atomic Bomb", civ, base)
        var ctx = context()
        assertEquals(listOf(bomber), TacticalAttacks.findUnits(ctx, near.getTile(), 1).map { it.unit })
        assertTrue("out of range", TacticalAttacks.findUnits(ctx, far.getTile(), 1).isEmpty())
        assertTrue("no defender to damage", TacticalAttacks.findUnits(ctx, worker.getTile(), 1).isEmpty())
        assertFalse("nuclear weapons have no attack of their own", nuke in TacticalAttacks.findUnits(ctx, near.getTile(), 1).map { it.unit })

        bomber.health = 51
        ctx = context()
        assertEquals("more than half is enough", listOf(bomber), TacticalAttacks.findUnits(ctx, near.getTile(), 1).map { it.unit })
        bomber.health = 50
        ctx = context()
        assertTrue("half is not", TacticalAttacks.findUnits(ctx, near.getTile(), 1).isEmpty())
    }

    @Test
    fun `aircraft are not asked to move onto a tile, pillage or take part when ranged units are left out`() {
        val base = airBase()
        game.addUnit("Bomber", civ, base)
        val victim = defender(4)
        val ctx = context()
        assertTrue(TacticalAttacks.findUnits(ctx, victim.getTile(), 1, noRanged = true).isEmpty())
        assertTrue(TacticalAttacks.findUnits(ctx, victim.getTile(), 1, willPillage = true).isEmpty())
        assertTrue(TacticalAttacks.findUnits(ctx, victim.getTile(), 1, targetUndefended = true).isEmpty())
        assertTrue(TacticalAttacks.findUnits(ctx, tile(2), 1, mustMoveThrough = true).isEmpty())
    }

    @Test
    fun `an aircraft that can strike is among the units that may attack a target, with the damage it would do`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val victim = defender(4)
        val ctx = context()
        assertEquals(listOf(bomber), TacticalSimulation.findUnitsWithinStrikingDistance(ctx, victim.getTile()))
        assertTrue(TacticalSimulation.computeTotalExpectedDamage(ctx, victim.getTile(), listOf(bomber)) > 0)
    }

    @Test
    fun `an aircraft destroys a unit in its range when the unit target is attacked and is done for the turn`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val victim = defender(4)
        victim.health = 10
        val ctx = context()
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Medium)
        assertTrue(victim.isDestroyed)
        assertEquals(1, bomber.attacksThisTurn)
        assertTrue(bomber in ctx.cache.processedUnits)
        assertEquals(base, bomber.getTile())
    }

    @Test
    fun `an aircraft is no candidate to be moved to a bombard tile, it has no combat strength of its own`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val ctx = context()
        assertTrue(TacticalBombards.findClosestUnits(ctx, base, 1, mustBeRanged = true, rangeRequired = 2).none { it.unit == bomber })
    }

    // endregion
    @Test
    fun `expected damage is the damage at the mean of the randomness`() {
        val victim = defender(0, "Swordsman")
        val attacker = melee(1, victim.getTile(), "Swordsman").single()
        val ctx = context()
        val defenderCombatant = Battle.getMapCombatantOfTile(victim.getTile())!!
        val expected = BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), defenderCombatant, attacker.getTile(), 0.5f, ignoreFlanking = true)
        assertEquals(expected, TacticalSimulation.computeTotalExpectedDamage(ctx, victim.getTile(), listOf(attacker)))
    }
}
