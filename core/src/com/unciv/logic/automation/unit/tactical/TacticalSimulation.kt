package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.AirMoves
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.min

/**
 *  The tactical combat simulation and the routines that use it: how Vox Populi 5.4.8's CvTacticalAI decides who attacks what, from where, and where
 *  the other units stand. A plan ([SimResult]) is searched over hypothetical positions of our units and the enemies ([TacticalSimSearch],
 *  [SimPosition], [SimWorld], [SimScoring]) and then carried out on the real units step by step ([executeUnitAssignments]), which checks that every
 *  step finds the situation it was planned for and gives up for a new plan when it does not (an enemy the plan did not expect is revealed, a defender
 *  survives or dies unexpectedly): CvTacticalAI.cpp's FindAndExecuteBestUnitAssignments and ExecuteUnitAssignments.
 *
 *  The callers: ExecuteAttackWithUnits (with its air attack and spotter move), ExecuteDestroyEnemyUnits, ExecuteCaptureCityMoves (for the posture moves,
 *  [TacticalMoves] and barbarians), CheckForEnemiesNearArmy ([OperationalMoves]) and PerformRangedOpportunityAttack (garrisons, units that heal,
 *  withdraw or flee and units that arrive somewhere; its melee counterpart and KillLoneEnemyIfPossible are [OpportunityAttacks]).
 *
 *  What is not ported, and why:
 *  - PositionUnitsAroundTarget (the AL_LOW plan that places units around a friendly or distant target) belongs to the deployment code of the armies;
 *    the plan itself can place units without enemies (a target and [AggressionLevel.Low]) but nothing calls it for that;
 *  - the support pass (CvSupportPosition, AddSupportMoves: generals, admirals and siege towers) and citadels, repair fleet and the other powers
 *    (A_USE_POWER): the units that use them have their own moves ([Deployment.moveGreatGenerals]); their bonus is not in the plan;
 *  - air sweeps and naval embarkation plans (MS_EMBARKED);
 *  - what the real combat does that the plan cannot know (see [SimWorld]); a plan that goes wrong is made again, up to [MAX_ATTEMPTS] times.
 */
object TacticalSimulation {

    /** FindAndExecuteBestUnitAssignments makes up to four plans */
    private const val MAX_ATTEMPTS = 4
    /** The mean of the damage roll: expected damage is the original's combat damage without randomness */
    private const val EXPECTED_RANDOMNESS = TacticalTargets.EXPECTED_RANDOMNESS

    //region Making and carrying out plans
    /**
     *  TacticalAIHelpers::FindAndExecuteBestUnitAssignments: make a plan for [units] around [target] and carry it out; if it fails, again without the
     *  units that spoiled it or with the changed situation, up to four times.
     *  @return whether a plan was carried out completely
     */
    fun findAndExecuteBestUnitAssignments(ctx: TacticalContext, units: List<MapUnit>, target: Tile, aggression: AggressionLevel): Boolean {
        var current = units
        var success = false
        var attempts = 0
        do {
            attempts++
            val result = TacticalSimSearch.findBestUnitAssignments(ctx, current, target, aggression, targetDistanceRelevant = true)
            if (result.isEmpty) {
                if (result.unusable.isNotEmpty() && current.size > result.unusable.size) current = current.filter { it !in result.unusable }
                else break // give up
            } else {
                ctx.log { "tactsim around ${target.position} with $aggression: ${result.assignments.count { it.type != AssignmentType.Initial }} assignments " +
                    "from ${result.used} positions (${result.completed} complete)" }
                // Restarts happen when new enemies become visible
                success = executeUnitAssignments(ctx, result)
            }
        } while (!success && attempts < MAX_ATTEMPTS)
        return success
    }

    private fun isEnemyTile(civ: Civilization, tile: Tile): Boolean {
        val unit = tile.militaryUnit
        if (unit != null && unit.civ != civ && civ.isAtWarWith(unit.civ)) return true
        if (!tile.isCityCenter()) return false
        val owner = tile.getOwner() ?: return false
        return owner != civ && civ.isAtWarWith(owner)
    }

    /**
     *  TacticalAIHelpers::ExecuteUnitAssignments: take the assigned steps one by one and carry them out faithfully. A step can fail: a melee kill that
     *  does not happen, a unit that is not where the plan expects it, an enemy that was not seen. Then the rest of the plan is dropped (a restart step
     *  also ends it) and the caller makes a new one.
     */
    fun executeUnitAssignments(ctx: TacticalContext, result: SimResult): Boolean {
        val civ = ctx.civ
        val world = result.world
        val steps = result.assignments
        val finishedUnits = ArrayList<MapUnit>()
        // Enemies known before the plan starts: one that appears while it is carried out invalidates it
        val known = TacticalAi.visibleEnemyTiles(civ).toHashSet()

        fun revealsEnemy(unit: MapUnit): Boolean {
            var revealed = false
            for (tile in unit.viewableTiles) if (tile !in known && isEnemyTile(civ, tile)) { known.add(tile); revealed = true }
            return revealed
        }

        for ((index, step) in steps.withIndex()) {
            if (step.type == AssignmentType.Restart) return false // the previous move revealed a new enemy: make the plan again with the remaining units
            if (step.unit < 0 || step.unit >= world.units.size) continue
            val unit = world.units[step.unit].unit
            // Be extra careful with the unit here: capturing and liberating cities can destroy it
            if (unit.isDestroyed) continue
            val from = world.plots.getOrNull(step.from)
            val to = world.plots.getOrNull(step.to)
            var pre = false
            var post = false
            val hostileAtTarget = to != null && isEnemyTile(civ, to)

            when (step.type) {
                AssignmentType.Initial, AssignmentType.FinishTemp, AssignmentType.MoveDouble -> continue
                AssignmentType.Move, AssignmentType.MoveForced, AssignmentType.Capture -> {
                    pre = unit.hasMovement() && unit.getTile() == from && !hostileAtTarget && to != null
                    // What the plan did to the world so far (an enemy killed opens the way and takes its zone of control away) is not in the paths
                    // the AI cached for the unit. Even then the plan reached the tile with its own movement model: if the unit cannot get there
                    // with the real one, the plan is dropped.
                    if (pre) unit.movement.clearPathfindingCache()
                    pre = pre && unit.movement.getDistanceToTiles().containsKey(to!!)
                    if (pre) unit.movement.moveToTile(to!!)
                    post = unit.getTile() == to
                }
                AssignmentType.MoveSwap -> {
                    pre = unit.getTile() == from && !hostileAtTarget && to != null
                    if (pre) unit.movement.clearPathfindingCache()
                    pre = pre && unit.movement.canUnitSwapTo(to!!)
                    if (pre) unit.movement.swapMoveToTile(to!!)
                    post = unit.getTile() == to
                }
                AssignmentType.MoveSwapReverse -> {
                    // Just bookkeeping: always after the move that swapped this unit
                    pre = unit.getTile() == to
                    post = pre
                }
                AssignmentType.RangeAttack, AssignmentType.RangeKill -> {
                    val cityBefore = to!!.isCityCenter() && hostileAtTarget
                    val defenderBefore = Battle.getMapCombatantOfTile(to).takeIf { hostileAtTarget && !cityBefore }
                    pre = unit.getTile() == from && hostileAtTarget
                    val option = if (pre) TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, to, true), from) else null
                    if (option != null) Battle.moveAndAttack(MapUnitCombatant(unit), option) else pre = false
                    post = if (step.type == AssignmentType.RangeKill) !isEnemyTile(civ, to)
                        else (!cityBefore || isEnemyTile(civ, to)) && (defenderBefore == null || !defenderBefore.isDefeated() && isEnemyTile(civ, to))
                }
                AssignmentType.MeleeAttack -> {
                    pre = unit.getTile() == from && hostileAtTarget
                    val option = if (pre) TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, to!!, true), from) else null
                    if (option != null) Battle.moveAndAttack(MapUnitCombatant(unit), option) else pre = false
                    post = !unit.isDestroyed && unit.getTile() == from && isEnemyTile(civ, to!!)
                }
                AssignmentType.MeleeKill, AssignmentType.MeleeKillNoAdvance -> {
                    pre = unit.getTile() == from && hostileAtTarget
                    val option = if (pre) TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, to!!, true), from) else null
                    if (option != null) Battle.moveAndAttack(MapUnitCombatant(unit), option) else pre = false
                    // Because of the randomness in the combat it may happen that the defender is not killed
                    post = !unit.isDestroyed && !isEnemyTile(civ, to!!) &&
                        if (step.type == AssignmentType.MeleeKill) unit.getTile() == to else unit.getTile() == from
                }
                AssignmentType.Pillage -> {
                    pre = unit.getTile() == from && UnitActionsPillage.canPillage(unit, unit.getTile())
                    if (pre) UnitActionsPillage.getPillageAction(unit, unit.getTile())?.action?.invoke() else pre = false
                    post = pre
                }
                AssignmentType.Heal, AssignmentType.Finish -> {
                    // The difference to a blocked unit: nobody else may move it, unless it should heal; this lets civilians use it as cover
                    unit.fortifyIfCan()
                    if (unit.health >= 100 || !unit.hasMovement() || civ.isBarbarian) finishedUnits.add(unit)
                    pre = true
                    post = true
                }
                AssignmentType.Blocked -> {
                    // Not marked as processed: the unit can be used for other tasks
                    pre = true
                    post = true
                }
                AssignmentType.Restart -> return false
            }
            if (!pre || !post) {
                ctx.log { "tactsim: could not carry out ${step.type} of ${unit.name} from ${from?.position} to ${to?.position} (${if (!pre) "precondition" else "postcondition"})" }
                return false
            }
            // A move that reveals an enemy the plan does not expect: stop here
            val moved = step.type == AssignmentType.Move || step.type == AssignmentType.MoveForced || step.type == AssignmentType.Capture ||
                step.type == AssignmentType.MoveSwap || step.type == AssignmentType.MeleeKill
            if (moved && !unit.isDestroyed && revealsEnemy(unit) && steps.getOrNull(index + 1)?.type != AssignmentType.Restart) {
                ctx.log { "tactsim: ${unit.name} revealed an enemy the plan did not expect" }
                return false
            }
        }
        // Only now that it worked
        for (unit in finishedUnits) if (!unit.isDestroyed) ctx.unitProcessed(unit)
        return true
    }
    //endregion

    //region Units and targets
    /** CvUnit::IsCoveringFriendlyCivilian: the unit protects a finished civilian outside a city that enemies can reach */
    private fun isCoveringFriendlyCivilian(ctx: TacticalContext, unit: MapUnit): Boolean {
        val tile = unit.getTile()
        if (tile.isCityCenter()) return false
        for (other in tile.getUnits()) {
            if (other == unit || other.civ != unit.civ) continue
            if (!TacticalSafety.canDefend(other) && other in ctx.cache.processedUnits) return ctx.danger.get(tile) > 0
        }
        return false
    }

    /** CvPlot::getBestDefender for a plot of the enemy, as far as the damage numbers are concerned: the city, else the defending unit */
    private fun defenderOn(civ: Civilization, tile: Tile) = Battle.getMapCombatantOfTile(tile)?.takeIf {
        it.getCivInfo() != civ && civ.isAtWarWith(it.getCivInfo())
    }

    /**
     *  CvTacticalAI::FindUnitsWithinStrikingDistance: the units of this turn that can take part in an attack on [tile]: that can reach it
     *  (ranged: shoot it from where they can get to; melee: stand next to it this turn) or are within 2 tiles, ranged units (aircraft too, from
     *  where they are) only if they are expected to do some damage. Fighters kept for interception are not among them. Aircraft are no part of
     *  the plan ([SimWorld.strategyOf]), [executeAirAttack] sends them first.
     */
    fun findUnitsWithinStrikingDistance(ctx: TacticalContext, tile: Tile): List<MapUnit> {
        val civ = ctx.civ
        val isCity = tile.isCityCenter()
        val defender = defenderOn(civ, tile)
        val result = ArrayList<MapUnit>()
        for (unit in ctx.liveUnits()) {
            if (!unit.hasMovement() || !unit.isMilitary()) continue
            val air = unit.baseUnit.isAirUnit()
            if (air && !isAirStriker(ctx, unit)) continue
            // Do not pull barbarian units out of their camps to attack
            if (civ.isBarbarian && unit.getTile().isBarbarianEncampment()) continue
            // Some units cannot enter cities
            if (isCity && unit.hasUnique(UniqueType.CannotCaptureCities, checkCivInfoUniques = true)) continue
            val distance = unit.getTile().aerialDistanceTo(tile)
            // Do not bother if we are very far away
            if (!air && distance > unit.baseUnit.movement.coerceAtLeast(1) * 4) continue
            if (isCoveringFriendlyCivilian(ctx, unit)) continue
            var canReach = false
            if (air) canReach = distance <= unit.getRange()
            else if (unit.baseUnit.isRanged()) {
                // Can attack without moving, or from a tile it can get to (a unit that can only attack next turn is taken too)
                canReach = distance <= unit.getRange() || unit.movement.getDistanceToTiles().keys.any { it.aerialDistanceTo(tile) <= unit.getRange() }
            } else if (unit.baseUnit.isMelee()) canReach = distance <= 1 || tile.neighbors.any { it in unit.movement.getDistanceToTiles() }
            // Units that are very close are included even if they cannot do anything right now: the plan can move them out of the way
            if (!canReach && distance > 2) continue
            if (air && !canReach) continue

            if (unit.baseUnit.isRanged()) {
                // Will we do a significant amount of damage?
                val hitPoints = (defender?.takeIf { !isCity }?.getHealth() ?: 0)
                val damage = if (defender == null) 0 else BattleDamage.calculateDamageToDefender(MapUnitCombatant(unit), defender, unit.getTile(), EXPECTED_RANDOMNESS, ignoreFlanking = true)
                if (damage >= min(hitPoints / 20, 3)) result.add(unit)
            } else if (unit.baseUnit.isMelee()) result.add(unit)
        }
        return result
    }

    /** An aircraft that may strike this turn: combat ready (more than half health), not a fighter kept for interception, not a nuclear weapon */
    private fun isAirStriker(ctx: TacticalContext, unit: MapUnit) =
        unit.baseUnit.isAirUnit() && TacticalAttacks.canAttack(unit) && unit.hasMovement() && AirMoves.isCombatReady(unit) &&
            UnitAiTypes.defaultOf(unit.baseUnit, ctx.rules) != UnitAiType.DEFENSE_AIR

    /**
     *  CvTacticalAI::ComputeTotalExpectedDamage: what [units] can do to the defender or city on [tile] together, a rough estimate that leaves
     *  out only the most extreme suicides (the plan sorts out the details); with many melee units that cannot move after attacking, only
     *  three count, not all can attack at once.
     */
    fun computeTotalExpectedDamage(ctx: TacticalContext, tile: Tile, units: List<MapUnit>): Int {
        val civ = ctx.civ
        val defender = defenderOn(civ, tile) ?: return 0
        var total = 0
        var melee = 0
        for (unit in units) {
            val attacker = MapUnitCombatant(unit)
            val dealt = BattleDamage.calculateDamageToDefender(attacker, defender, unit.getTile(), EXPECTED_RANDOMNESS, ignoreFlanking = true)
            val taken = if (unit.baseUnit.isRanged()) 0 else BattleDamage.calculateDamageToAttacker(attacker, defender, unit.getTile(), EXPECTED_RANDOMNESS, ignoreFlanking = true)
            val accepted = if (defender is CityCombatant) dealt > taken || dealt * 2 > taken && unit.health - taken > 50
                else dealt > taken / 5 && unit.health > taken / 2
            if (!accepted) continue
            // With many melee units do not assume they all can be executed at once
            if (!unit.baseUnit.isRanged() && !unit.hasUnique(UniqueType.CanMoveAfterAttacking)) {
                if (melee < 3) total += dealt
                melee++
            } else total += dealt
        }
        return total
    }
    //endregion

    //region Attacks
    /**
     *  CvTacticalAI::ExecuteSpotterMove: if the target is not visible, a fast attacker or tank moves to where it sees it, keeping some movement to
     *  retreat. @return false if nobody can: the attack is called off
     */
    private fun executeSpotterMove(ctx: TacticalContext, units: List<MapUnit>, target: Tile): Boolean {
        if (target in ctx.civ.viewableTiles) return true
        var best: Triple<MapUnit, Tile, Float>? = null
        for (unit in units.sortedBy { it.id }) {
            if (!unit.hasMovement() || unit.baseUnit.isRanged()) continue
            val aiType = UnitAiTypes.current(unit)
            if (aiType != UnitAiType.FAST_ATTACK && aiType != UnitAiType.ATTACK_SEA && aiType != UnitAiType.ATTACK && aiType != UnitAiType.COUNTER) continue
            for ((tile, move) in unit.movement.getDistanceToTiles().entries.sortedBy { it.key.zeroBasedIndex }) {
                val left = unit.currentMovement - move.totalMovement
                // Some movement left to retreat
                if (left <= SimPosition.EPSILON) continue
                if (!unit.movement.canMoveTo(tile)) continue
                if (target !in ctx.civ.gameInfo.tileMap.getViewableTiles(tile.position, unit.getVisibilityRange())) continue
                if (best == null || left > best.third) best = Triple(unit, tile, left)
            }
        }
        val (unit, tile, _) = best ?: return false
        ctx.log { "${unit.name} moves to ${tile.position} to see ${target.position}" }
        unit.movement.moveToTile(tile)
        return true
    }

    /**
     *  CvTacticalAI::ExecuteAirAttack: aircraft first, whatever else is going on: each of them with more than 30 health strikes as often as it
     *  can at the best target near [target] in its range (damage, a kill, and what it takes in return; cities only if [target] is one).
     */
    private fun executeAirAttack(ctx: TacticalContext, target: Tile, aircraft: List<MapUnit>) {
        val civ = ctx.civ
        for (unit in aircraft) {
            if (unit.isDestroyed || !isAirStriker(ctx, unit)) continue
            var count = 0 // a failsafe
            while (unit.hasMovement() && unit.health > 30 && count < unit.maxAttacksPerTurn() && !TacticalAttacks.isOutOfAttacks(unit)) {
                count++
                var bestTile: Tile? = null
                var bestValue = Int.MIN_VALUE
                for (candidate in ctx.targets.all) {
                    if (!candidate.isUnitTarget && candidate.type != TacticalTargetType.City) continue
                    if (candidate.tile.aerialDistanceTo(target) > 3 || candidate.tile.aerialDistanceTo(unit.getTile()) > unit.getRange()) continue
                    val defender = defenderOn(civ, candidate.tile) ?: continue
                    if (defender is CityCombatant) {
                        // The city only if it is what we are after, and not one that is as good as taken
                        if (!target.isCityCenter() || defender.getHealth() <= 10) continue
                    }
                    if (TacticalAttacks.attackOptions(unit, candidate.tile, true).isEmpty()) continue
                    val attacker = MapUnitCombatant(unit)
                    val damage = BattleDamage.calculateDamageToDefender(attacker, defender, unit.getTile(), EXPECTED_RANDOMNESS)
                    val value = if (UnitAiTypes.current(unit) == UnitAiType.MISSILE_AIR) {
                        // Missiles do no damage to an ungarrisoned city
                        if (defender is CityCombatant) continue
                        damage + (if (defender.getHealth() <= damage) 21 else 0)
                    } else damage - candidate.tile.aerialDistanceTo(unit.getTile()) * 3 -
                        BattleDamage.calculateDamageToAttacker(attacker, defender, unit.getTile(), EXPECTED_RANDOMNESS)
                    if (value > bestValue) { bestValue = value; bestTile = candidate.tile }
                }
                val tile = bestTile ?: break
                val option = TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, tile, true), unit.getTile()) ?: break
                ctx.log { "air attack: ${unit.name} attacks ${tile.position}" }
                Battle.moveAndAttack(MapUnitCombatant(unit), option)
            }
            if (!unit.isDestroyed && TacticalAttacks.isOutOfAttacks(unit)) ctx.unitProcessed(unit)
        }
    }

    /**
     *  CvTacticalAI::ExecuteAttackWithUnits: evaluate many possible assignments of the units that can strike [target] and carry out the best one. It
     *  will not necessarily attack only the target when there are others around. Aircraft first, after the spotter move that tries to see the target.
     *  @return whether a plan was carried out (or the aircraft already killed the unit on the tile)
     */
    fun executeAttackWithUnits(ctx: TacticalContext, target: Tile, aggression: AggressionLevel, units: List<MapUnit>): Boolean {
        // Try to improve visibility
        if (!executeSpotterMove(ctx, units, target)) return false
        executeAirAttack(ctx, target, units.filter { it.baseUnit.isAirUnit() })
        // Did the air attack already kill the enemy?
        if (!target.isCityCenter() && defenderOn(ctx.civ, target) == null) return true
        ctx.log { "trying attack on ${target.position}, aggression $aggression" }
        return findAndExecuteBestUnitAssignments(ctx, units.filter { !it.isDestroyed && it.hasMovement() && it !in ctx.cache.processedUnits }, target, aggression)
    }

    /** CvTacticalAI::ExecuteAttackWithCities: the cities that can shoot the defender do; true if it is dead after one of the shots */
    private fun executeAttackWithCities(ctx: TacticalContext, target: Tile): Boolean {
        for (striker in TacticalAttacks.findCities(ctx, target)) {
            val defender = defenderOn(ctx.civ, target) ?: return true
            val city = striker.city
            if (!TacticalAttacks.canRangeStrikeAt(city, target)) continue
            ctx.log { "${city.name} bombards ${target.position}" }
            Battle.attackOrNuke(CityCombatant(city), AttackableTile(city.getCenterTile(), target, 0f, defender))
            if (defenderOn(ctx.civ, target) == null) return true
        }
        return false
    }

    /**
     *  CvTacticalAI::ExecuteDestroyEnemyUnits: attack the unit of [target] with [aggression]; the target is marked, whether the attack happens or
     *  not. Our cities that can shoot it do first; then the plan, if the units that can strike it are expected to do any damage.
     */
    fun executeDestroyEnemyUnits(ctx: TacticalContext, target: TacticalTarget, aggression: AggressionLevel): Boolean {
        // Mark the target no matter if the attack succeeds
        target.lastAggressionLevel = aggression
        val tile = target.tile
        if (defenderOn(ctx.civ, tile) == null || tile.isCityCenter()) return false
        // Might be able to hit or kill with a city
        if (executeAttackWithCities(ctx, tile)) return true
        // Now the real deal
        val units = findUnitsWithinStrikingDistance(ctx, tile)
        if (units.isNotEmpty() && computeTotalExpectedDamage(ctx, tile, units) > 0) return executeAttackWithUnits(ctx, tile, aggression, units)
        return false
    }

    /**
     *  CvTacticalAI::ExecuteCaptureCityMoves: against each enemy city not attacked at this aggression yet: not in a zone the enemy dominates unless
     *  the city is about to fall, not too early (the damage a turn must beat what the city heals before the siege is 13 turns old), with a melee
     *  unit to take it; the attack is [AggressionLevel.High], more careful ([AggressionLevel.Medium]) with up to two melee units.
     *  @return whether a plan was carried out for a city
     */
    fun executeCaptureCityMoves(ctx: TacticalContext): Boolean {
        val civ = ctx.civ
        var attacked = false
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.City)) {
            // The target is marked whether the attack happens or not: no better chance this turn
            if (target.lastAggressionLevel >= AggressionLevel.High) continue
            target.lastAggressionLevel = AggressionLevel.High
            val city = target.city ?: continue
            if (!target.isAlive(civ)) continue

            // First try the land zone: does it look good there? (Barbarians have no analysis map and attack wherever they are)
            if (!civ.isBarbarian) {
                var zone = ctx.map.zoneByCity(city, false)
                if (zone == null || zone.dominance == TacticalDominance.Enemy && !CityDangerAi.isInDangerOfFalling(city, false)) {
                    // Try again with the water zone
                    zone = ctx.map.zoneByCity(city, true)
                    if (zone == null || zone.dominance == TacticalDominance.Enemy && !CityDangerAi.isInDangerOfFalling(city, false)) {
                        ctx.log { "city of ${city.name} is in an enemy dominated zone, will not try to capture it, ${target.tile.position}" }
                        continue
                    }
                }
            }

            // Always recruit both naval and land based forces if available
            val units = findUnitsWithinStrikingDistance(ctx, target.tile)
            if (units.isEmpty()) continue
            val requiredDamage = city.health
            val expectedPerTurn = computeTotalExpectedDamage(ctx, target.tile, units)
            if (expectedPerTurn < requiredDamage) {
                // The actual siege will typically be longer because not all units attack the city each turn
                val maxSiegeTurns = 13
                // Community Patch uses 20 HP per turn; population healing belongs to full Vox Populi.
                val healRate = if (city.getCenterTile().isBlockaded()) 0 else CITY_HIT_POINTS_HEALED_PER_TURN
                if ((expectedPerTurn - healRate) * maxSiegeTurns < requiredDamage) {
                    ctx.log { "too early for attacking ${city.name}, required damage $requiredDamage, expected maximum damage per turn $expectedPerTurn" }
                    continue
                }
            }
            // See whether we have melee units for capturing
            val meleeCount = units.count { it.hasMovement() && !it.baseUnit.isRanged() }
            if (meleeCount == 0 && requiredDamage <= 1) {
                ctx.log { "no melee units to capture ${city.name}" }
                continue
            }
            ctx.log { "attempting capture of ${city.name}, required damage $requiredDamage, expected maximum damage per turn $expectedPerTurn" }
            // Be a bit more careful if we have few melee units
            attacked = executeAttackWithUnits(ctx, target.tile, if (meleeCount > 2) AggressionLevel.High else AggressionLevel.Medium, units) || attacked
            // Did it work? If so, no need for a temporary dominance zone if we had one here
            if (target.tile.getOwner() == civ) ctx.state.deleteTemporaryZone(target.tile)
        }
        return attacked
    }

    /** CITY_HIT_POINTS_HEALED_PER_TURN in Community Patch. */
    private const val CITY_HIT_POINTS_HEALED_PER_TURN = 20
    //endregion

    //region Opportunity attacks
    /**
     *  TacticalAIHelpers::PerformRangedOpportunityAttack: see whether the shooting unit can hit anything from where it is, with or without moving. A
     *  unit that can move after attacking, or may move, goes through the plan for itself (staying where it started if it may not move,
     *  keeping [saveMovement] movement); the others shoot the best target in range (a kill counts extra) without it.
     *  @return whether it attacked
     */
    fun performRangedOpportunityAttack(ctx: TacticalContext, unit: MapUnit, allowMovement: Boolean = false, saveMovement: Float = 0f): Boolean {
        if (unit.isDestroyed || !unit.baseUnit.isRanged() || !unit.hasMovement() || unit.baseUnit.isAirUnit()) return false
        // A garrison that the city needs stays where it is
        val city = unit.getTile().getCity()?.takeIf { unit.baseUnit.isLandUnit && unit.getTile().isCityCenter() && it.civ == unit.civ && it.getGarrison() == unit }
        val garrisoned = city != null && AiOperationLogic.needsGarrison(unit.civ, city)
        val mayMove = allowMovement && !garrisoned
        if (mayMove || unit.hasUnique(UniqueType.CanMoveAfterAttacking)) {
            val result = TacticalSimSearch.findBestUnitAssignments(ctx, listOf(unit), unit.getTile(), AggressionLevel.Low,
                targetDistanceRelevant = false, returnToStart = !mayMove, saveMovement = saveMovement)
            if (result.isEmpty) return false
            ctx.log { "performing ranged opportunity attack with ${unit.name} at ${unit.getTile().position}" }
            return executeUnitAssignments(ctx, result)
        }
        var bestTile: Tile? = null
        var bestDamage = 0
        for (tile in unit.getTile().getTilesInDistance(unit.getRange().coerceIn(1, 5)).sortedBy { it.zeroBasedIndex }) {
            if (tile == unit.getTile() || tile.isCityCenter()) continue
            val defender = defenderOn(ctx.civ, tile) ?: continue
            if (TacticalAttacks.attackOptions(unit, tile, true).isEmpty()) continue
            // Do not blindly attack the first one: how much damage can we do
            var damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(unit), defender, unit.getTile(), EXPECTED_RANDOMNESS)
            if (damage >= defender.getHealth()) damage += 30
            if (damage > bestDamage) { bestDamage = damage; bestTile = tile }
        }
        val tile = bestTile ?: return false
        val option = TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, tile, true), unit.getTile()) ?: return false
        ctx.log { "performing stationary ranged opportunity attack with ${unit.name} at ${unit.getTile().position} on ${tile.position}" }
        Battle.moveAndAttack(MapUnitCombatant(unit), option)
        return true
    }
    //endregion

    //region Armies
    /**
     *  CvTacticalAI::CheckForEnemiesNearArmy: if units of [army] (and those of ours next to them, whatever they are doing) are in contact with the
     *  enemy, they fight: the units that can attack or be attacked, at least half healthy, within 6 tiles of their center of mass, around the nearest
     *  enemy that can attack them, at [AggressionLevel.Medium]; not when it looks like a trap in the enemy's zone.
     *  @return whether a plan was carried out: the army then stays together, its target is its center of mass
     */
    fun checkForEnemiesNearArmy(ctx: TacticalContext, armyUnits: List<MapUnit>): Boolean {
        val civ = ctx.civ
        val enemies = ArrayList<MapUnit>()
        for (other in civ.gameInfo.civilizations)
            if (other != civ && other.isAlive() && civ.isAtWarWith(other))
                for (unit in other.units.getCivUnits())
                    if (unit.isMilitary() && !unit.baseUnit.isAirUnit() && unit.isVisibleTo(civ)) enemies.add(unit)
        val mine = civ.units.getCivUnits().filter { it.isMilitary() && !it.baseUnit.isAirUnit() }.toList()

        val initial = LinkedHashMap<Int, MapUnit>()
        for (unit in armyUnits.sortedBy { it.id }) {
            if (unit.isDestroyed || !unit.hasMovement() || unit in ctx.cache.processedUnits || unit.health < 50) continue
            // Can we attack somebody? Who can attack us?
            val canAttack = TacticalAttacks.canAttack(unit) && TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()).isNotEmpty()
            val attackers = enemies.filter { canThreaten(it, unit.getTile()) }
            if (!canAttack && attackers.isEmpty() && !unit.isCivilian()) continue
            // This unit can be attacked: remember it
            initial[unit.id] = unit
            // Now here is the trick: also include our non-army units that happen to be around
            for (attacker in attackers)
                for (friend in mine) if (!friend.isDestroyed && canThreaten(friend, attacker.getTile()) && friend !in ctx.cache.processedUnits && friend.hasMovement()) initial[friend.id] = friend
        }
        if (initial.isEmpty()) return false

        // The center of mass
        val ordered = initial.values.sortedBy { it.id }
        var x = 0
        var y = 0
        val enemyTiles = LinkedHashMap<Int, Tile>()
        for (unit in ordered) {
            x += unit.getTile().position.x
            y += unit.getTile().position.y
            for (attacker in enemies) if (canThreaten(attacker, unit.getTile())) enemyTiles[attacker.getTile().zeroBasedIndex] = attacker.getTile()
        }
        val tileMap = civ.gameInfo.tileMap
        val centerOfMass = tileMap.getOrNull((x * 100 / ordered.size + 50) / 100, (y * 100 / ordered.size + 50) / 100) ?: ordered.first().getTile()
        // The closest enemy plot to our center of mass
        val closest = enemyTiles.values.minWithOrNull(compareBy<Tile> { centerOfMass.aerialDistanceTo(it) }.thenBy { it.zeroBasedIndex }) ?: return false
        // Ignore units that are very far out: the plan will ignore the worst units if necessary
        val finalUnits = ordered.filter { centerOfMass.aerialDistanceTo(it.getTile()) < 7 }

        ctx.log { "performing opportunity attack with an army and friends: ${finalUnits.size} units around ${closest.position}" }
        // We probably did not see all enemy units: do not get drawn into the wrong fight
        val city = civ.gameInfo.civilizations.filter { it.isAlive() }.flatMap { it.cities }
            .minWithOrNull(compareBy<City> { it.getCenterTile().aerialDistanceTo(closest) }.thenBy { it.id })
        val zone = city?.let { ctx.map.zoneByCity(it, closest.isWater) }
        if (zone != null && zone.closestCity != null && zone.territory == TacticalTerritory.Enemy && zone.dominance == TacticalDominance.Enemy) return false

        return findAndExecuteBestUnitAssignments(ctx, finalUnits, closest, AggressionLevel.Medium)
    }

    /** Whether [unit] can attack [tile] this coming turn: within its movement and range, on its domain if it does not shoot ([UnitDanger.canThreaten]) */
    private fun canThreaten(unit: MapUnit, tile: Tile) = UnitDanger.canThreaten(unit, tile)
    //endregion
}
