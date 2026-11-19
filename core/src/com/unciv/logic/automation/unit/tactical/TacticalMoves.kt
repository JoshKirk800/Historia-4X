package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  The target moves of CvTacticalAI that are still BNW's or shared, run by [TacticalAi] in Vox Populi's order of ProcessDominanceZones: each looks at the targets
 *  of one type and commands the units that fit them. Ported: PlotCivilianAttackMoves, PlotPillageMoves and
 *  the execution helpers they share (ExecuteMoveToTarget, ExecuteMoveToPlot, ExecuteMoveOfBlockingUnit, MoveToEmptySpaceNearTarget, FindNearbyTarget).
 *  The attacks on units and cities are [TacticalSimulation]'s (ExecuteDestroyEnemyUnits, ExecuteCaptureCityMoves): [destroyEnemyUnits] is what the
 *  postures' loops over the unit targets do. Garrisons, bastions, guarded improvements, ruins and camps are [GarrisonMoves] and [GuardMoves]; the units
 *  that are not in a fight come to the zones by [ReinforcementMoves]. Gone with Vox Populi's combat simulation and reinforcement: Brave New World's safe
 *  bombard and reposition moves (PlotRepositionMoves is no longer called by the original).
 */
object TacticalMoves {

    //region Attacking units

    /**
     *  The loop of the posture moves of CvTacticalAI (PlotAttritionAttacks, PlotExploitFlanksMoves, PlotSteamrollMoves, PlotSurgicalCityStrikeMoves,
     *  PlotHedgehogMoves, PlotCounterattackMoves, PlotBarbarianAttacks): ExecuteDestroyEnemyUnits for each unit target of the current targets that
     *  has not been attacked with at least [aggression] this turn, in the order of the targets. The barbarians' list runs it per target type ([type]).
     */
    fun destroyEnemyUnits(ctx: TacticalContext, aggression: AggressionLevel, type: TacticalTargetType? = null) {
        for (target in ctx.currentTargets) {
            if (!target.isUnitTarget || type != null && target.type != type || target.lastAggressionLevel >= aggression) continue
            TacticalSimulation.executeDestroyEnemyUnits(ctx, target, aggression)
        }
    }

    //endregion
    //region Civilians, camps, improvements, ruins

    /**
     *  CvTacticalAI::PlotCivilianAttackMoves for the targets of [type]: the strongest unit that can move onto the undefended unit captures it
     *  (ExecuteCivilianCapture: it moves there and is done for the turn).
     */
    fun plotCivilianAttackMoves(ctx: TacticalContext, type: TacticalTargetType) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
            val victim = target.unit ?: continue
            if (victim.isDestroyed || victim.civ == ctx.civ || victim.getTile() != target.tile) continue
            val striker = TacticalAttacks.findUnits(ctx, target.tile, 1, targetUndefended = true).firstOrNull() ?: continue
            ctx.log { "attacking $type ${victim.name}, ${target.tile.position}" }
            moveAndFinish(ctx, striker.unit, target.tile)
        }
    }

    /**
     *  CvTacticalAI::PlotPillageMoves for the targets of [type]: in the first pass a paratrooper that can drop on the tile does, and pillages
     *  (FindParatroopersWithinStrikingDistance, ExecuteParadropPillage: "because it looks cooler"), else the best unit (damaged ones, which heal by it, count triple)
     *  that can get to the tile with movement left goes there and pillages (ExecutePillage); in the second pass (for the next turn) the
     *  best unit within 2 turns moves towards it.
     *
     *  The original does not ask whether the paratrooper can pillage and does not look at danger on the tile or escort the unit; here one that
     *  may not pillage ([UnitActionsPillage.canPillage]) is left out.
     */
    fun plotPillageMoves(ctx: TacticalContext, type: TacticalTargetType, firstPass: Boolean) {
        val heal = ctx.rules.define(AiDefine.PILLAGE_HEAL_AMOUNT)
        for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
            val tile = target.tile
            if (!tile.canPillageTile()) continue
            if (firstPass) {
                val paratrooper = findParatroopers(ctx, tile).firstOrNull()
                if (paratrooper != null) {
                    ctx.log { "paratrooping in to pillage $type, ${tile.position}" }
                    val unit = paratrooper.unit
                    UnitActionsFromUniques.paradrop(unit, tile)
                    if (unit.getTile() == tile) UnitActionsPillage.getPillageAction(unit, tile)?.action?.invoke()
                    unit.currentMovement = 0f
                    ctx.unitProcessed(unit)
                    continue
                }
                val striker = TacticalAttacks.findUnits(ctx, tile, 0, heal, mustMoveThrough = true, willPillage = true).firstOrNull() ?: continue
                ctx.log { "pillaging $type, ${tile.position}" }
                val unit = striker.unit
                unit.movement.moveToTile(tile)
                if (unit.getTile() == tile) UnitActionsPillage.getPillageAction(unit, tile)?.action?.invoke()
                unit.currentMovement = 0f
                ctx.unitProcessed(unit)
            } else {
                // No one can reach it this turn, what about next turn?
                val strikers = TacticalAttacks.findUnits(ctx, tile, 2, heal, willPillage = true)
                if (strikers.isEmpty()) continue
                ctx.log { "moving towards $type for pillage, ${tile.position}" }
                moveToTarget(ctx, tile, emptyList(), strikers.map { MoveCandidate(it.unit, TacticalAttacks.turnsToReach(it.unit, tile)) })
            }
        }
    }

    /**
     *  CvTacticalAI::FindParatroopersWithinStrikingDistance: the units of the tactical AI that can drop on [tile] now ([TacticalAi.canParadrop],
     *  [UnitActionsFromUniques.canParadropAt]) and pillage it, strongest and healthiest first
     */
    fun findParatroopers(ctx: TacticalContext, tile: Tile): List<Striker> =
        ctx.liveUnits().filter {
            TacticalAi.canParadrop(it) && UnitActionsFromUniques.canParadropAt(it, tile) && UnitActionsPillage.canPillage(it, tile)
        }.map { Striker(it, TacticalAttacks.maxAttackStrength(it), it.health) }
            .sortedByDescending { it.attackPriority }

    //endregion
    //region Nearby targets

    /**
     *  CvTacticalAI::FindNearbyTarget: the target within [range] of the unit on its landmass that it reaches soonest (the tile it stands on first of all);
     *  tiles for which [excluded] is true (the original's pNoLikeUnit) are left out
     */
    fun findNearbyTarget(ctx: TacticalContext, unit: MapUnit, range: Int, targets: List<TacticalTarget>, excluded: ((Tile) -> Boolean)? = null): Tile? {
        var best: Tile? = null
        var bestValue = Int.MAX_VALUE
        val unitTile = unit.getTile()
        val near = ArrayList<Pair<TacticalTarget, Int>>()
        for (target in targets) {
            val distance = unitTile.aerialDistanceTo(target.tile)
            if (distance == 0) return target.tile
            if (distance < range && ctx.sameArea(unitTile, target.tile) && excluded?.invoke(target.tile) != true) near.add(target to distance)
        }
        // Only the nearest few are measured by path: it would be a path search for every target in range
        for ((target, _) in near.sortedBy { it.second }.take(MAX_REPOSITION_CANDIDATES)) {
            val turns = TacticalAttacks.turnsToReach(unit, target.tile)
            if (turns < bestValue) {
                best = target.tile
                bestValue = turns
            }
        }
        return best
    }

    private const val MAX_REPOSITION_CANDIDATES = 5

    //endregion
    //region Finding and moving units for a move

    /** A unit that can do a move, [turns] turns from it (CvTacticalUnit with its moves to target) */
    class MoveCandidate(val unit: MapUnit, val turns: Int)

    /**
     *  CvTacticalAI::ExecuteMoveToTarget: one unit goes to [tile] and is done for the turn (the one there fortifies): the first of the high
     *  priority units, unless the first regular one is nearer, else the first regular one.
     */
    fun moveToTarget(ctx: TacticalContext, tile: Tile, high: List<MoveCandidate>, regular: List<MoveCandidate>) {
        fun go(candidate: MoveCandidate): Boolean {
            val unit = candidate.unit
            if (unit.getTile() == tile && unit.canFortify()) unit.fortify()
            else if (candidate.turns < Int.MAX_VALUE) {
                unit.movement.tryHeadTowards(tile)
                unit.currentMovement = 0f
            } else return false
            ctx.unitProcessed(unit)
            return true
        }
        // Start with high priority list
        for (candidate in high) {
            // Don't move high priority unit if regular priority unit is closer
            if (regular.isNotEmpty() && regular.first().turns < candidate.turns) break
            if (go(candidate)) return
        }
        // Then regular priority
        for (candidate in regular) if (go(candidate)) return
    }

    /** CvTacticalAI::ExecuteMoveToPlot: [unit] goes to [tile] (or fortifies, or stays, if it is there) and is done for the turn */
    fun moveToPlot(ctx: TacticalContext, unit: MapUnit, tile: Tile) {
        if (unit.getTile() == tile) {
            if (unit.canFortify()) unit.fortify() else unit.currentMovement = 0f
        } else {
            unit.movement.moveToTile(tile)
            unit.currentMovement = 0f
        }
        ctx.unitProcessed(unit, unit.isMilitary())
    }

    /**
     *  The unit goes to [tile] as far as it gets this turn; where a unit of ours holds the tile and the two can change places, they do (the original
     *  pushes the unit out, CvUnit::PushBlockingUnitOutOfPlot). The unit keeps what movement is left.
     *  @return whether the unit got to the tile (or is on its way: the tile cannot be reached this turn)
     */
    fun moveSwapping(unit: MapUnit, tile: Tile): Boolean {
        if (unit.getTile() == tile) return true
        // The reach the unit had cached may be stale, and a swap needs the other unit to reach us too
        unit.movement.clearPathfindingCache()
        if (!unit.movement.canMoveTo(tile) && unit.movement.canUnitSwapTo(tile)) {
            unit.movement.swapMoveToTile(tile)
            return unit.getTile() == tile
        }
        return Deployment.headTowards(unit, tile)
    }

    /** ExecuteBarbarianCampMove, ExecuteCivilianCapture: the unit moves onto the tile (capturing what is on it) and is done for the turn */
    private fun moveAndFinish(ctx: TacticalContext, unit: MapUnit, tile: Tile) {
        unit.movement.moveToTile(tile)
        unit.currentMovement = 0f
        ctx.unitProcessed(unit)
    }

    /**
     *  CvTacticalAI::ExecuteMoveOfBlockingUnit: a unit of ours that stands where another unit wants to be moves to a neighbouring tile it can
     *  reach (not onto water from land, not onto an enemy unit or city) and is done for the turn. @return whether it moved
     */
    fun executeMoveOfBlockingUnit(ctx: TacticalContext, blocker: MapUnit): Boolean {
        if (blocker.civ != ctx.civ || !blocker.hasMovement()) return false
        val from = blocker.getTile()
        for (tile in from.neighbors) {
            // Don't embark for one of these moves
            if (!from.isWater && tile.isWater && blocker.baseUnit.isLandUnit) continue
            // Has to be somewhere we can move and be empty of other units/enemy cities
            if (TacticalTargets.visibleEnemyDefender(ctx.civ, tile) == null && !ctx.isEnemyCity(tile) && blocker.movement.canReach(tile)
                && blocker.movement.canMoveTo(tile)) {
                moveToPlot(ctx, blocker, tile)
                ctx.log { "moving blocking ${blocker.name} out of way, leaving ${from.position}, now at ${blocker.getTile().position}" }
                return true
            }
        }
        return false
    }

    /**
     *  CvTacticalAI::MoveToEmptySpaceNearTarget: the unit heads for the first tile next to [target] of its domain that holds no unit, and no city
     *  but a friendly one, that it can reach. @return whether it went
     */
    fun moveToEmptySpaceNearTarget(ctx: TacticalContext, unit: MapUnit, target: Tile): Boolean {
        val destination = emptyTileNextTo(unit, target) ?: return false
        unit.movement.tryHeadTowards(destination)
        return true
    }

    /** The first tile next to [target], on the unit's domain, with no unit and no city but our own, that it can reach */
    fun emptyTileNextTo(unit: MapUnit, target: Tile): Tile? {
        val land = !unit.baseUnit.isWaterUnit
        for (neighbor in target.neighbors) {
            if (neighbor.isWater == land) continue
            if (neighbor.militaryUnit != null) continue
            if (neighbor.isCityCenter() && neighbor.getOwner() != unit.civ) continue
            if (unit.movement.canReach(neighbor)) return neighbor
        }
        return null
    }

    //endregion
}
