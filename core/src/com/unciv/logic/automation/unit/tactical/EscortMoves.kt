package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.max

/**
 *  The movement of a civilian and its escort, Vox Populi's CvTacticalAI::PlotArmyMovesEscort (Release-5.4.8; its log lines call it
 *  "SingleHexOperationMoves", the BNW name [OperationalMoves] used to port): the escorted operations (settlers) meet at the muster plot, then
 *  the escort leads the way and the civilian follows, one hex stack, or they split up when they have to. With its routines SwitchEscort,
 *  ExecuteMoveToPlot, MoveToEmptySpaceNearTarget and TacticalAIHelpers::FindSafestPlotInReach ([SafestPlot.find]). Community Patch and Vox
 *  Populi share the function: it has no MOD_BALANCE_VP branch.
 *
 *  Reading of the unit's path flags (CvUnit::MOVEFLAG_*), which Unciv's path finder has no counterpart for: the path is
 *  [UnitMovement.getShortestPath][com.unciv.logic.map.mapunit.movement.UnitMovement.getShortestPath], whose steps are the tiles where each turn ends,
 *  and the flags are checked on it:
 *  - NO_ENEMY_TERRITORY: no step in a tile owned by a civilization at war with us, unless the unit stands in one (the original drops the
 *    flag then, CvAStar's InitializeNextSearch);
 *  - AI_ABORT_IN_DANGER: none of the first two stops may be a tile where the civilian's danger ([PlotDanger.get] for the unit: [Int.MAX_VALUE] where it
 *    can be captured) is more than half its health, as the original's path finder refuses them. The flag is used where a refused path is a plain
 *    "no": forcing the stack forward. For a civilian travelling alone it is NOT a reason to abort: the original's path finder goes round such a plot
 *    (or ends the turn before it), where the one shortest path here has no way round, so the civilian takes the safe plot nearest to its target
 *    ([detourOrWait]) or waits; only a missing path is LOST_PATH (the study of `parity/vp-danger` lost 84% of the quick colonizers' operations to it);
 *  - APPROX_TARGET_RING1 / RING2: the path ends at the nearest tile within 1 / 2 hexes of the target that the unit can enter (the cheapest of the
 *    eight nearest candidates), APPROX_TARGET_NATIVE_DOMAIN excludes tiles of the other domain;
 *  - DONT_STACK_WITH_NEUTRAL needs nothing: Unciv's units never enter a tile held by a unit of another civilization;
 *  - CONTINUE_TO_CLOSEST_PLOT only changes the moves of squads in the original (CvUnit::ComputePath), which Community Patch has not.
 *
 *  Deviations:
 *  - the path finder's danger costs (a path avoids plots with danger when it can) are not modelled, only the refusal of the stops above;
 *    the same goes for the rerouting around enemy territory: a path that touches it is no path, and the stack splits or the operation aborts;
 *  - in the waiting branch where the civilian is at the muster plot and the escort cannot reach it in time, the original compares the escort with
 *    the replacement SwitchEscort returned to abort the operation (LOST_PATH); the replacement never is the escort, so the abort cannot happen
 *    and is not written;
 *  - ExecuteMoveToPlot's pillaging, its ranged opportunity attack and the pushing of a blocking unit out of the way are left out (the stack
 *    moves are of units that are not to fight; the units that block are not pushed, the unit heads for the nearest tile it can enter);
 *    a unit that is at its tile holds position (fortifies if it can) and is done; the moves are not zeroed: a settler that arrives keeps the
 *    movement to found its city ([AiOperationLogic.checkOnTarget]);
 *  - the escort slot of a formation without one (Quick Colonizer in the BNW data) cannot take the escort grabbed on the way; a grabbed escort
 *    also makes the operation escorted again ([AiOperation.escorted] is a flag here, the original's IsEscorted counts the units); the
 *    defender that cannot join (no free slot) is not marked processed;
 *  - the operation's muster plot and the army's position are set together (the original's army position is the operation's muster plot while
 *    the army waits);
 *  - when SwitchEscort replaces the escort while the army waits, the stack that moves on in the same call is the civilian and the new escort;
 *    the original goes on with the old one, which has left the army, and leaves the new one standing.
 */
object EscortMoves {

    private const val NO_ENEMY_TERRITORY = 1
    private const val APPROX_RING1 = 2
    private const val APPROX_RING2 = 4
    private const val ABORT_IN_DANGER = 8
    private const val NATIVE_DOMAIN = 16

    /** CvAStar.cpp PathEndTurnCost: AI_ABORT_IN_DANGER refuses a path that ends one of its first two turns on a plot with more than half the unit's health in danger */
    private const val ABORT_IN_DANGER_TURNS = 2

    /** The turns the paths to the common plot of the stack may take (GeneratePath's iMaxTurns of 5) */
    private const val STACK_PATH_MAX_TURNS = 5

    /** MoveToEmptySpaceNearTarget's turns for the escort that stays close to its civilian, and for additional escorts */
    private const val ESCORT_CLOSE_TURNS = 12
    private const val EXTRA_ESCORT_CLOSE_TURNS = 23

    /** The plots near a target that approximate paths try: each costs a path search */
    private const val MAX_APPROXIMATE_CANDIDATES = 8

    /** The tiles where each turn of a path ends; empty for a unit that already is where it wants to be */
    private class EscortPath(val stops: List<Tile>) {
        /** CvUnit::GetPathEndFirstTurnPlot: null when there is nothing to walk */
        val firstStop: Tile? get() = stops.firstOrNull()

        /** The turns CvUnit::GeneratePath reports: 0 when the path ends this turn */
        val turns: Int get() = max(stops.size - 1, 0)
    }

    /**
     *  CvTacticalAI::PlotArmyMovesEscort: [operation]'s first army is a civilian (slot order: the first unit) with an escort (the next) and
     *  possibly more escorts. Meeting up when the army waits for units, then moving to the target.
     */
    fun plotArmyMovesEscort(ctx: TacticalContext, operation: AiOperation) {
        val civ = ctx.civ
        val army = operation.firstArmy ?: return
        val units = AiOperationLogic.armyUnits(civ, army)
        // the unit to be escorted is always the first one, the second would be the first escort, the rest additional escorts
        val civilian = units.firstOrNull()?.second ?: return
        var escort = units.getOrNull(1)?.second
        val extraEscorts = units.drop(2).map { it.second }
        // No civilian? that's a problem
        if (!civilian.isCivilian()) return

        // ESCORT AND CIVILIAN MEETING UP
        if (army.state == ArmyState.WAITING_FOR_UNITS_TO_REINFORCE || army.state == ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP)
            if (!meetUp(ctx, operation, army, civilian, escort)) return
            else escort = AiOperationLogic.armyUnits(civ, army).getOrNull(1)?.second

        // MOVING TO TARGET ... or really close
        if (army.state == ArmyState.MOVING_TO_DESTINATION)
            if (!moveToTarget(ctx, operation, army, civilian, escort)) return

        // move any additional escorts near the civilian
        for (unit in extraEscorts) {
            moveToEmptySpaceNearTarget(ctx, unit, civilian.getTile(), null, EXTRA_ESCORT_CLOSE_TURNS)
            ctx.log { "moving additional escorting ${unit.name} to civilian ${civilian.name} for operation, ${civilian.getTile().position}" }
            processed(ctx, unit)
        }
    }

    /** The waiting half of [plotArmyMovesEscort]; false where the original returns */
    private fun meetUp(ctx: TacticalContext, operation: AiOperation, army: AiArmy, civilian: MapUnit, escort: MapUnit?): Boolean {
        val civ = ctx.civ
        // Check to make sure escort can get to civilian
        val muster = AiOperationLogic.musterTile(civ, operation) ?: return true
        // check if the civilian is in danger
        if (ctx.danger.get(civilian) > 0) {
            // try to move to safety
            val betterPlot = SafestPlot.find(ctx, civilian, allowEmbark = true)?.tile
            if (betterPlot != null) {
                executeMoveToPlot(ctx, civilian, betterPlot)
                return false
            }
        }

        if (escort == null) {
            // no escort
            if (civilian.getTile() == muster) AiOperationLogic.checkOnTarget(civ, operation)
            // continue moving. if this should fail, we just freeze and wait for better times
            else if (ctx.danger.get(civilian, muster) < Int.MAX_VALUE) executeMoveToPlot(ctx, civilian, muster)
            return true
        }

        // civilian and escort have not yet met up
        if (civilian.getTile() == muster) {
            // civilian is already there
            if (muster.getUnits().any { it.isMilitary() }) {
                // another military unit is blocking our escort ... find another muster plot
                val newEscort = switchEscort(ctx, army, civilian, muster, escort)
                if (newEscort != null) AiOperationLogic.checkOnTarget(civ, operation)
                else {
                    // Let's have them move forward, see if that clears things up.
                    val target = AiOperationLogic.targetTile(civ, operation) ?: return true
                    executeMoveToPlot(ctx, civilian, target, true, APPROX_RING1 or ABORT_IN_DANGER)
                    executeMoveToPlot(ctx, escort, target, true, APPROX_RING1)
                    // try again next turn
                    operation.musterTile = civilian.getTile().position
                    army.position = civilian.getTile().position
                    ctx.log { "SingleHexOperationMoves: Forced movement to get things going." }
                }
            } else {
                // move escort towards civilian
                if (executeMoveToPlot(ctx, escort, civilian.getTile()) > 0) {
                    // d'oh. escort cannot reach us
                    switchEscort(ctx, army, civilian, civilian.getTile(), escort)
                    return false
                }
                processed(ctx, civilian)
            }
        } else {
            // both must move
            executeMoveToPlot(ctx, civilian, muster)
            executeMoveToPlot(ctx, escort, muster, true, if (escort.movement.canMoveTo(muster)) 0 else APPROX_RING1)
        }
        if (operation.state != OperationState.ABORTED)
            ctx.log { "Moving escorting ${escort.name} to civilian for operation, civilian ${civilian.getTile().position}, ${escort.getTile().position}" }
        return true
    }

    /** The moving half of [plotArmyMovesEscort]; false where the original returns (the operation finished) */
    private fun moveToTarget(ctx: TacticalContext, operation: AiOperation, army: AiArmy, civilian: MapUnit, escort: MapUnit?): Boolean {
        val civ = ctx.civ
        AiOperationLogic.checkOnTarget(civ, operation)
        if (operation.state == OperationState.SUCCESSFUL_FINISH) return false
        val target = AiOperationLogic.targetTile(civ, operation) ?: return false

        // avoid plots where our escort cannot follow (DONT_STACK_WITH_NEUTRAL) if necessary and possible: see the class comment
        var flags = NO_ENEMY_TERRITORY

        // the escort leads the way
        var pathFound = false
        var continueOperation = true
        var log = ""
        if (escort != null) {
            // the target plot may be a city, so we need to check if the escort can actually go there
            // but the civilian uses the same flags so dump the escort when we're already there
            if (!escort.movement.canMoveTo(target) && escort.getTile().aerialDistanceTo(target) > 1) flags = flags or APPROX_RING1

            val escortPath = generatePath(ctx, escort, target, flags)
            if (escortPath != null) {
                var commonPlot = escortPath.firstStop
                // need to check if civilian can enter because of unrevealed tiles in path
                if (commonPlot != null && civilian.movement.canMoveTo(commonPlot)) {
                    val civilianPath = generatePath(ctx, civilian, commonPlot, flags, STACK_PATH_MAX_TURNS)
                    if (civilianPath != null) {
                        pathFound = true
                        // escort seems to be faster than the civilian, slow down
                        if (civilianPath.turns > 0) commonPlot = civilianPath.firstStop
                        // we know they can stack
                        if (commonPlot != null) {
                            executeMoveToPlot(ctx, escort, commonPlot)
                            executeMoveToPlot(ctx, civilian, commonPlot)
                        }
                        log = "${civilian.name} now at ${civilian.getTile().position}. Moving towards ${target.position} with escort ${escort.name}. escort leading."
                    }
                }
            } else {
                // civilian leads the way since escort seems to be blocked
                // but maybe we can at least find a way for this turn
                val civilianPath = generatePath(ctx, civilian, target, flags)
                val commonPlot = civilianPath?.firstStop
                if (commonPlot != null) {
                    if (!escort.movement.canMoveTo(commonPlot)) flags = flags or APPROX_RING1
                    val escortToCommon = generatePath(ctx, escort, commonPlot, flags, STACK_PATH_MAX_TURNS)
                    if (escortToCommon != null) {
                        pathFound = true
                        // civilian seems to be faster than the escort, slow down
                        val stop = if (escortToCommon.turns > 0) escortToCommon.firstStop ?: commonPlot else commonPlot
                        // we know they can stack
                        executeMoveToPlot(ctx, escort, stop)
                        executeMoveToPlot(ctx, civilian, stop)
                    } else {
                        // our escort can't move into the next path plot. maybe it's blocked by a friendly unit?
                        val newEscort = switchEscort(ctx, army, civilian, commonPlot, escort)
                        if (newEscort != null) {
                            executeMoveToPlot(ctx, civilian, commonPlot)
                            hold(newEscort)
                            processed(ctx, newEscort)
                        }
                    }
                }
            }

            if (!pathFound) {
                // we have a problem, apparently civilian and escort must split up
                if (executeMoveToPlot(ctx, civilian, target, false, flags) == Int.MAX_VALUE) {
                    civ.ai.operations.abort(operation, OperationAbortReason.LOST_PATH)
                    log = "${civilian.name} stuck at ${civilian.getTile().position}, cannot find safe path to target. aborting."
                    continueOperation = false
                }
                // try to stay close
                else if (executeMoveToPlot(ctx, escort, civilian.getTile(), false) == Int.MAX_VALUE) {
                    moveToEmptySpaceNearTarget(ctx, escort, civilian.getTile(), civilian.getTile().isWater, ESCORT_CLOSE_TURNS)
                    log = "${civilian.name} at ${civilian.getTile().position} separated from escort ${escort.name} at ${escort.getTile().position}"
                } else log = "${civilian.name} at ${civilian.getTile().position} had an issue moving to its target but it was resolved"
            }
        } else {
            // no escort
            val civilianPath = generatePath(ctx, civilian, target, flags)
            if (civilianPath != null) {
                val turnTarget = civilianPath.firstStop
                if (turnTarget != null) {
                    if (isPathTooDangerous(ctx, civilian, civilianPath)) {
                        // The way the path takes is hit: Vox Populi's path finder (AI_ABORT_IN_DANGER) goes round or stops earlier; the path here is the one
                        // shortest, so the civilian goes on by the safe plot nearest to its target, or waits. That is no lost path.
                        log = detourOrWait(ctx, civilian, target)
                    } else {
                        // maybe we can find ourselves an escort!
                        grabEscort(ctx, operation, army, civilian, turnTarget)
                        executeMoveToPlot(ctx, civilian, turnTarget)
                        log = "${civilian.name} now at ${civilian.getTile().position}. Moving normally towards ${target.position} without escort."
                    }
                }
            } else if (moveToEmptySpaceNearTarget(ctx, civilian, target, false, Int.MAX_VALUE)) {
                log = "${civilian.name} now at ${civilian.getTile().position}. Moving to empty space near target ${target.position} without escort."
            } else {
                civ.ai.operations.abort(operation, OperationAbortReason.LOST_PATH)
                log = "${civilian.name} at ${civilian.getTile().position}. Aborted operation. No path to target for civilian."
            }
        }

        // now we're done, if the operation was cancelled, let the units be handled by Homeland AI
        if (continueOperation) {
            processed(ctx, civilian)
            if (escort != null) processed(ctx, escort)
        }
        if (log.isNotEmpty()) ctx.log { log }
        return true
    }

    /**
     *  The escort-less branch's "we can find ourselves an escort": the best defender of ours that stands where the civilian ends its turn,
     *  in no army and of the civilian's domain, takes the escort slot. Only a unit that joins is marked processed.
     */
    private fun grabEscort(ctx: TacticalContext, operation: AiOperation, army: AiArmy, civilian: MapUnit, turnTarget: Tile) {
        val operations = ctx.civ.ai.operations
        val defender = turnTarget.militaryUnit?.takeIf { it.civ == ctx.civ } ?: return
        if (operations.armyOf(defender) != null || !sameDomain(defender, civilian)) return
        if (army.slots.getOrNull(1)?.unitId != -1) return
        operations.addUnit(army, defender, 1)
        operation.escorted = true
        processed(ctx, defender)
        ctx.log { "SingleHexOperationMoves: Grabbed an escort along the way." }
    }

    /**
     *  The file's SwitchEscort: the best defender of ours on [newEscortPlot] takes the place of [escort] when it is in no army, of the
     *  civilian's domain and no city bombarder. The old escort leaves the army without the operation being told (RemoveUnit(bTempOnly)).
     *  @return the new escort, null if nobody took the place
     */
    private fun switchEscort(ctx: TacticalContext, army: AiArmy, civilian: MapUnit, newEscortPlot: Tile, escort: MapUnit): MapUnit? {
        val operations = ctx.civ.ai.operations
        val defender = newEscortPlot.militaryUnit?.takeIf { it.civ == ctx.civ } ?: return null
        // Maybe we just make this guy our new escort, eh?
        if (operations.armyOf(defender) != null || !sameDomain(defender, civilian) || UnitAiTypes.current(defender) == UnitAiType.CITY_BOMBARD) return null
        val slot = operations.removeFromArmyTemporarily(escort)
        if (slot < 0) return null
        operations.addUnit(army, defender, slot)
        ctx.log { "SingleHexOperationMoves: Switched escort to get things going." }
        return defender
    }

    private fun sameDomain(a: MapUnit, b: MapUnit) =
        a.baseUnit.isWaterUnit == b.baseUnit.isWaterUnit && a.baseUnit.isAirUnit() == b.baseUnit.isAirUnit()

    //region Moving a unit

    private fun processed(ctx: TacticalContext, unit: MapUnit) = ctx.unitProcessed(unit, unit.isMilitary())

    /** A unit that is where it wants to be skips its turn (MISSION_SKIP): a unit that can fortify does */
    private fun hold(unit: MapUnit) {
        if (unit.canFortify()) unit.fortify()
    }

    /**
     *  CvTacticalAI::ExecuteMoveToPlot: [unit] goes towards [target] as far as it gets this turn, and is marked processed if [setProcessed].
     *  @return the original's "turns remaining": the path's turns less one, [Int.MAX_VALUE] if the unit cannot go there
     */
    private fun executeMoveToPlot(ctx: TacticalContext, unit: MapUnit, target: Tile, setProcessed: Boolean = true, flags: Int = 0): Int {
        var result = Int.MAX_VALUE
        val approximate = flags and (APPROX_RING1 or APPROX_RING2) != 0
        if (unit.getTile() == target) {
            // Unit already at target plot
            result = 0
            hold(unit)
        } else if (approximate || unit.movement.canMoveTo(target)) {
            val path = generatePath(ctx, unit, target, flags)
            if (path != null) {
                path.firstStop?.let { Deployment.headTowards(unit, it) }
                result = path.turns - 1
            }
        }
        if (setProcessed) processed(ctx, unit)
        return result
    }

    /**
     *  CvTacticalAI::MoveToEmptySpaceNearTarget: [unit] heads for [target] or a plot near it avoiding the plots of its own units when it
     *  cannot enter [target]; tried again with more leeway (one more ring) when there is no path. [domainIsWater] is the domain asked
     *  for (null: any), the target plot's own makes the approximate plot one of the unit's domain.
     *  @return whether the unit went (or is as near as it gets) within [maxTurns]
     */
    private fun moveToEmptySpaceNearTarget(
        ctx: TacticalContext,
        unit: MapUnit,
        target: Tile,
        domainIsWater: Boolean?,
        maxTurns: Int,
    ): Boolean {
        if (unit.getTile() == target) return false
        var flags = 0
        // can we move there directly? if not try to move to an adjacent plot
        if (!unit.movement.canMoveTo(target)) flags = flags or APPROX_RING1
        if (domainIsWater == target.isWater) flags = flags or NATIVE_DOMAIN

        var path = generatePath(ctx, unit, target, flags)
        // if not possible, try again with more leeway
        if (path == null) {
            flags = if (flags and APPROX_RING1 != 0) (flags and APPROX_RING1.inv()) or APPROX_RING2 else flags or APPROX_RING1
            path = generatePath(ctx, unit, target, flags)
        }
        if (path == null || path.turns > maxTurns) return false
        path.firstStop?.let { Deployment.headTowards(unit, it) }
        return true
    }

    /** CvUnit::GeneratePath with [flags] (see the class comment): null if there is no path, or one of more than [maxTurns] turns */
    private fun generatePath(ctx: TacticalContext, unit: MapUnit, target: Tile, flags: Int, maxTurns: Int = Int.MAX_VALUE): EscortPath? {
        val civ = ctx.civ
        val start = unit.getTile()
        val ring = when {
            flags and APPROX_RING2 != 0 -> 2
            flags and APPROX_RING1 != 0 -> 1
            else -> 0
        }
        // we're already there (at least approximately)!
        if (start == target || start.aerialDistanceTo(target) <= ring) return EscortPath(emptyList())

        val stops = (if (ring == 0) exactPath(unit, target) else approximatePath(unit, target, ring, flags and NATIVE_DOMAIN != 0)) ?: return null
        if (stops.size - 1 >= maxTurns) return null
        if (flags and NO_ENEMY_TERRITORY != 0 && !start.isEnemyTerritory(civ) && stops.any { it.isEnemyTerritory(civ) }) return null
        if (flags and ABORT_IN_DANGER != 0 && unit.isCivilian() && isPathTooDangerous(ctx, unit, EscortPath(stops))) return null
        return EscortPath(stops)
    }

    /** CvAStar.cpp PathEndTurnCost with AI_ABORT_IN_DANGER: one of the first two turns of the path ends where more than half the unit's health is in danger */
    private fun isPathTooDangerous(ctx: TacticalContext, unit: MapUnit, path: EscortPath) =
        path.stops.take(ABORT_IN_DANGER_TURNS).any { ctx.danger.get(unit, it) > unit.health / 2 }

    /**
     *  A civilian travelling alone whose shortest path ends a turn in danger: it goes to the reachable plot, safe (danger at most half its health), that
     *  is nearest to [target] and nearer than where it stands; with none it stays where it is if that is safe, else it flees ([SafestPlot.find]).
     *  @return the log line
     */
    private fun detourOrWait(ctx: TacticalContext, civilian: MapUnit, target: Tile): String {
        val here = civilian.getTile()
        val limit = civilian.health / 2
        // What the civilian reaches changes as other units move
        civilian.movement.clearPathfindingCache()
        val best = civilian.movement.getDistanceToTiles().keys
            .filter { it != here && it.aerialDistanceTo(target) < here.aerialDistanceTo(target) && civilian.movement.canMoveTo(it) && ctx.danger.get(civilian, it) <= limit }
            .minWithOrNull(compareBy<Tile>({ it.aerialDistanceTo(target) }, { ctx.danger.get(civilian, it) }, { it.zeroBasedIndex }))
        if (best != null) {
            Deployment.headTowards(civilian, best)
            return "${civilian.name} goes round the danger on its way to ${target.position}, now at ${civilian.getTile().position}."
        }
        if (ctx.danger.get(civilian) > limit) {
            SafestPlot.find(ctx, civilian, allowEmbark = true)?.tile?.takeIf { it != here }?.let { Deployment.headTowards(civilian, it) }
            return "${civilian.name} flees from the danger on its way to ${target.position}, now at ${civilian.getTile().position}."
        }
        return "${civilian.name} waits at ${here.position} for the danger on its way to ${target.position} to pass."
    }

    private fun exactPath(unit: MapUnit, target: Tile): List<Tile>? {
        if (!unit.movement.canMoveTo(target)) return null
        return unit.movement.getShortestPath(target).ifEmpty { null }
    }

    /** The shortest of the paths to the tiles within [ring] of [target] that the unit can enter, the nearest ones first */
    private fun approximatePath(unit: MapUnit, target: Tile, ring: Int, nativeDomainOnly: Boolean): List<Tile>? {
        val start = unit.getTile()
        val candidates = target.getTilesInDistance(ring)
            .filter { it != start && unit.movement.canMoveTo(it) && (!nativeDomainOnly || unit.baseUnit.isWaterUnit == it.isWater) }
            .sortedWith(compareBy<Tile>({ start.aerialDistanceTo(it) }, { target.aerialDistanceTo(it) }))
            .take(MAX_APPROXIMATE_CANDIDATES)
        var best: List<Tile>? = null
        for (candidate in candidates) {
            val path = unit.movement.getShortestPath(candidate)
            if (path.isEmpty() || (best != null && path.size >= best.size)) continue
            best = path
            if (path.size == 1) break
        }
        return best
    }
    //endregion
}
