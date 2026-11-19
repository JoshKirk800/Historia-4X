package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.ceil

/**
 *  The attacks a unit makes because an enemy happens to be in reach, outside of a plan: TacticalAIHelpers::PerformOpportunityAttack (the melee
 *  half; the ranged half, PerformRangedOpportunityAttack, is [TacticalSimulation.performRangedOpportunityAttack]) and
 *  TacticalAIHelpers::KillLoneEnemyIfPossible. Vox Populi 5.4.8 (CvTacticalAI.cpp) uses them for garrisons, units that heal or flee, the camp
 *  defenders of the barbarians, and units that arrive somewhere with movement to spare.
 *
 *  Deviations: a unit that must not leave its fortification only makes a killing attack with the movement to come back ([MOVES_TO_ADVANCE_AND_RETURN]);
 *  "can the unit attack the plot this turn" is [TacticalAttacks.attackOptions] (Unciv's path finder) where the original asks its
 *  path finder for the turns to reach the plot; a melee unit that kills advances in Unciv's rules and is walked back to where it started if it
 *  has movement left (the original's second move order); CvPlot::isFortification is a city or one of our citadels ([TacticalMap.isPlayerCitadel];
 *  Unciv has no "no follow up" improvement flag).
 */
object OpportunityAttacks {

    /** A killing blow counts like this much damage in the score, and the threshold a plan must beat grows by [SCORE_STEP] for each enemy found */
    private const val KILL_BONUS = 30
    private const val SCORE_STEP = 1000
    private const val MAX_HEALTH = 100

    /** KillLoneEnemyIfPossible: the damage must beat the enemy's hit points by this margin for randomness, and the unit must keep more than [SAFE_HEALTH_AFTER_FIGHT] */
    private const val KILL_MARGIN = 3
    private const val SAFE_HEALTH_AFTER_FIGHT = 23
    /** A melee kill advances in Unciv's rules: a unit that must stay in its fortification only kills if it can advance and walk back (3 movement points) */
    private const val MOVES_TO_ADVANCE_AND_RETURN = 3f

    private class Candidate(val tile: Tile, val score: Int)

    /**
     *  TacticalAIHelpers::PerformOpportunityAttack: see whether [unit] can usefully attack something around it. A unit that shoots goes through
     *  [TacticalSimulation.performRangedOpportunityAttack]. A melee unit looks at the tiles it can reach if [allowMovement], else only at those
     *  next to it, and at each that holds a visible enemy unit (never a city) it works out the attack: no suicide, a kill only when the unit
     *  may advance (a unit that does not move must stand in a fortification to kill), not when it would be left badly hurt next to other
     *  enemies, and a unit that is almost unhurt counts on healing up. The score is 1000 x damage dealt / (damage received + 10), and the
     *  best one is attacked if it beats 1000 for every enemy found, so that a single good attack is made and a cluster of enemies is avoided.
     *  An undefended civilian is captured, if the unit is safe there or can return.
     *  @return whether the unit attacked
     */
    fun perform(ctx: TacticalContext, unit: MapUnit, allowMovement: Boolean = false): Boolean {
        if (unit.isDestroyed || !TacticalAttacks.canAttack(unit) || !unit.hasMovement()) return false
        // The reach the unit has cached may be stale: other units have moved
        unit.movement.clearPathfindingCache()
        // For ranged units we have a readymade method
        if (unit.baseUnit.isRanged()) return TacticalSimulation.performRangedOpportunityAttack(ctx, unit, allowMovement)

        val civ = ctx.civ
        val origin = unit.getTile()
        val danger = civ.ai.unitDanger
        // Where can we go: the tiles next to us, or all those we reach this turn and attack from
        val candidates = if (allowMovement) origin.getTilesInDistance(ceil(unit.currentMovement).toInt() + 1).toList() else origin.neighbors.toList()

        val targets = ArrayList<Candidate>()
        var threshold = 0
        for (tile in candidates) {
            if (tile.isCityCenter()) continue
            val enemy = TacticalTargets.visibleEnemyDefender(civ, tile)
            if (enemy != null) {
                // Attack an enemy?
                if (TacticalAttacks.isOutOfAttacks(unit) || attackOption(unit, tile, allowMovement) == null) continue
                var (dealt, received) = SimWorld.damageOnUnit(unit, origin, unit.health, enemy, tile, enemy.health, ignoreFlanking = false)
                // No suicide
                if (received >= unit.health) continue
                if (dealt >= enemy.health) {
                    // A bonus for a kill, but no suicide
                    dealt += KILL_BONUS
                    received -= minOf(healedByKilling(unit), MAX_HEALTH - unit.health + received)
                    if (!allowMovement && (!isFortification(ctx, origin) || unit.currentMovement < MOVES_TO_ADVANCE_AND_RETURN)) continue
                }
                // We have just a single attacker, avoid enemy clusters
                if (received * 4 >= (unit.health - received) * 3 && numEnemyUnitsAdjacent(ctx, tile) > 0) continue
                // If the attacker is (almost) unhurt, we can be a bit more aggressive and assume we'll heal up next turn
                val damage = MAX_HEALTH - unit.health
                var healRate = TacticalSafety.actualHealRate(unit, origin)
                if (damage < healRate / 2 && received < MAX_HEALTH / 2) {
                    if (healRate > damage + received) healRate = damage + received
                    received -= healRate
                }
                if (received <= 0) {
                    dealt += received + 1
                    received = 0
                }
                targets.add(Candidate(tile, SCORE_STEP * dealt / (received + 10)))
                // Increase the threshold for each new enemy we find
                threshold += SCORE_STEP
            } else if (isUndefendedEnemy(ctx, tile)) {
                // Maybe capture a civilian: if we are safe there, or can come back
                val turns = TacticalAttacks.turnsToReach(unit, tile)
                if (turns > 1 || !unit.movement.canMoveTo(tile)) continue
                val safe = danger.get(unit, tile) == 0 && danger.get(unit) == 0
                if (safe || turns == 0) targets.add(Candidate(tile, 10 - origin.aerialDistanceTo(tile)))
            }
        }
        // Nothing to do?
        if (targets.isEmpty()) return false
        // The original sorts ascending (stable) and takes the last: of equal scores, the one found last
        val best = targets.fold(targets.first()) { best, candidate -> if (candidate.score >= best.score) candidate else best }
        // We will never do attacks with negative scores!
        if (best.score < threshold) return false

        ctx.log { "performing melee opportunity attack on ${best.tile.position} with ${unit.name} at ${origin.position}" }
        val option = attackOption(unit, best.tile, allowMovement)
        if (option != null) Battle.moveAndAttack(MapUnitCombatant(unit), option)
        else if (unit.movement.canReachInCurrentTurn(best.tile) && unit.movement.canMoveTo(best.tile)) unit.movement.moveToTile(best.tile)
        // Try to move back to the original plot (if we advanced)
        if (!unit.isDestroyed && unit.hasMovement() && unit.getTile() != origin) {
            unit.movement.clearPathfindingCache()
            if (unit.movement.canReachInCurrentTurn(origin) && unit.movement.canMoveTo(origin)) unit.movement.moveToTile(origin)
        }
        return true
    }

    /**
     *  TacticalAIHelpers::KillLoneEnemyIfPossible: [unit] attacks [enemy], the only one that can hit it, if that is expected to kill it with a
     *  margin for randomness (3 more damage than it has hit points) and leave the unit with more than 23 hit points. Aircraft do not. A unit that
     *  shoots hits from where it stands if it can, else moves to a tile from which it can shoot and does; a melee unit moves in if it can
     *  attack this turn.
     *  @return whether the unit attacked
     */
    fun killLoneEnemyIfPossible(unit: MapUnit, enemy: MapUnit): Boolean {
        if (unit.isDestroyed || enemy.isDestroyed) return false
        // Aircraft are different
        if (unit.baseUnit.isAirUnit() || enemy.baseUnit.isAirUnit()) return false
        if (!TacticalAttacks.canAttack(unit)) return false
        unit.movement.clearPathfindingCache()

        // See how the attack would go
        val (dealt, received) = SimWorld.damageOnUnit(unit, unit.getTile(), unit.health, enemy, enemy.getTile(), enemy.health, ignoreFlanking = false)
        // Is it worth it? (take into account some randomness ...)
        if (dealt - KILL_MARGIN <= enemy.health || unit.health - received <= SAFE_HEALTH_AFTER_FIGHT) return false

        // Can we attack directly (a shooter from where it stands), or must it move first?
        val shoots = unit.baseUnit.isRanged()
        var option = TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, enemy.getTile(), shoots), unit.getTile())
        if (option == null && shoots) option = TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, enemy.getTile(), false), null)
        if (option == null) return false
        Battle.moveAndAttack(MapUnitCombatant(unit), option)
        return true
    }

    /** The way [unit] attacks what is on [tile]: from where it stands unless it may [allowMovement] */
    private fun attackOption(unit: MapUnit, tile: Tile, allowMovement: Boolean) =
        TacticalAttacks.bestOption(TacticalAttacks.attackOptions(unit, tile, !allowMovement), unit.getTile())

    /** CvUnit::getHPHealedIfDefeatEnemy: what killing a unit heals */
    private fun healedByKilling(unit: MapUnit): Int {
        var heal = 0
        unit.forEachMatchingUnique(UniqueType.HealsAfterKilling, checkCivInfoUniques = true) { heal += it.params[0].toInt() }
        return heal
    }

    /** CvPlot::isFortification: a city, or one of our citadels (see the class comment) */
    private fun isFortification(ctx: TacticalContext, tile: Tile): Boolean =
        tile.isCityCenter() || !ctx.civ.isBarbarian && ctx.map.isPlayerCitadel(tile, ctx.civ)

    /** CvPlot::GetNumEnemyUnitsAdjacent(team, NO_DOMAIN): the visible combat units next to [tile] of civilizations at war with us, not embarked */
    private fun numEnemyUnitsAdjacent(ctx: TacticalContext, tile: Tile): Int =
        tile.neighbors.count { neighbor -> TacticalTargets.visibleEnemyDefender(ctx.civ, neighbor)?.let { !it.isEmbarked() } == true }

    /** CvPlot::isEnemyUnit(player, false, true): a visible unit of someone at war with us stands there that cannot fight back (a civilian, an embarked unit) */
    private fun isUndefendedEnemy(ctx: TacticalContext, tile: Tile): Boolean =
        listOfNotNull(tile.militaryUnit, tile.civilianUnit).any {
            it.civ != ctx.civ && ctx.civ.isAtWarWith(it.civ) && it.isVisibleTo(ctx.civ) && !TacticalSafety.canDefend(it)
        }
}
