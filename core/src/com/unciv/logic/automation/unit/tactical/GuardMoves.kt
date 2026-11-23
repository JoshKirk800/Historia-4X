package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  CvTacticalAI::PlotBastionMoves, PlotGuardImprovementMoves and PlotGrabGoodyMoves of Vox Populi 5.4.8 (Community Patch and Vox Populi share them), with
 *  the unit choice of [UnitSelection.findUnitForThisMove] and [UnitSelection.findUnitsForHarassing]:
 *  - bastions (targets [TacticalTargetType.DefensiveBastion], only those close to a contested border, [AiOperationLogic.isCloseToContestedBorder]) and
 *    improvements to defend ([TacticalTargetType.ImprovementToDefend]): the best guard within two turns goes there and is done for the turn; if it gets there
 *    this turn and is healthy it upgrades ([UseGoldAutomation.upgradeNow]) and then fortifies;
 *  - ancient ruins: the best unit within 6 turns (11 when no major is at war with us, to clear islands too) goes there; it is done if it has no movement left;
 *  - barbarian camps, occupied or not: up to three land units within the same range (embarking allowed) go towards an empty camp, next to it first, then onto it
 *    when nobody defends it (ExecuteBarbarianCampMove); against an occupied camp nothing here moves a unit, the camp is an attack target of the combat
 *    simulation.
 *
 *  Deviations: the original's positioning of the units around an occupied camp (PositionUnitsAroundTarget, when more than one unit is a good attacker) is not done; the targets
 *  of bastions and improvements are [TacticalTargets]'; the flags of the original's moves (safe embark, abort in danger) are not modelled, a unit simply heads for
 *  the tile.
 */
object GuardMoves {

    private const val GUARD_TURNS = 2
    private const val GOODY_RANGE_AT_WAR = 6
    private const val GOODY_RANGE_AT_PEACE = 11
    private const val CAMP_UNITS = 3

    /** CvTacticalAI::PlotBastionMoves([turnsAway]) */
    fun plotBastionMoves(ctx: TacticalContext, turnsAway: Int) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.DefensiveBastion)) {
            if (!AiOperationLogic.isCloseToContestedBorder(ctx.civ, ctx.map, target.tile)) continue
            guard(ctx, target.tile, turnsAway)?.let { ctx.log { "bastion ${target.tile.position}, priority ${target.aux}, turns away $turnsAway" } }
        }
    }

    /** CvTacticalAI::PlotGuardImprovementMoves([turnsAway]) */
    fun plotGuardImprovementMoves(ctx: TacticalContext, turnsAway: Int) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.ImprovementToDefend))
            guard(ctx, target.tile, turnsAway)?.let { ctx.log { "guard improvement ${target.tile.position}, turns away $turnsAway" } }
    }

    /** The guard that goes to [tile]: null if there is none or it does not get there this turn (it is on its way, the original logs only when it arrives) */
    private fun guard(ctx: TacticalContext, tile: Tile, turnsAway: Int): MapUnit? {
        // Grab units that make sense for this move type
        val unit = UnitSelection.findUnitForThisMove(ctx, UnitMove.Guard, tile, turnsAway) ?: return null
        val arrived = headFor(unit, tile)
        ctx.unitProcessed(unit)
        // Move may fail if the plot is already occupied (can happen if another unit moved there during this turn)
        if (!arrived) return null
        if (unit.health >= unit.getMaxHealth()) UseGoldAutomation.upgradeNow(unit)?.let { ctx.unitProcessed(it) }
        unit.takeUnless { it.isDestroyed }?.fortifyIfCan()
        return unit
    }

    /**
     *  CvTacticalAI::PlotGrabGoodyMoves: ruins first, then barbarian camps.
     */
    fun plotGrabGoodyMoves(ctx: TacticalContext) {
        // Allow a fairly big range so we can clear islands as well unless we're at war and need the units otherwise
        val range = if (ctx.civ.getCivsAtWarWith().any { it.isMajorCiv }) GOODY_RANGE_AT_WAR else GOODY_RANGE_AT_PEACE
        // Ruins first
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.AncientRuins)) {
            val unit = UnitSelection.findUnitForThisMove(ctx, UnitMove.Goody, target.tile, range) ?: continue
            headFor(unit, target.tile)
            // Can use this unit for other stuff if it can still move
            if (!unit.isDestroyed && !unit.hasMovement()) ctx.unitProcessed(unit)
            ctx.log { "moving ${unit.name} to grab a goody, ${target.tile.position}" }
        }
        // Then barb camps, occupied or not
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.BarbarianCamp)) {
            val units = UnitSelection.findUnitsForHarassing(ctx, target.tile, range, 0, 0, false, false, CAMP_UNITS)
            if (units.isEmpty()) continue
            ctx.log { "trying to remove barbarian camp, ${target.tile.position}" }
            executeBarbarianCampMove(ctx, target.tile, units)
        }
    }

    /**
     *  CvTacticalAI::ExecuteBarbarianCampMove for a camp nobody defends (a camp with an enemy unit is for the combat simulation): each unit heads next to
     *  the camp and, if it can still move and no combat unit stands there, onto it; the camp then is cleared and the first unit that reaches it is the last
     *  to move. Units that cannot move any more are done.
     */
    private fun executeBarbarianCampMove(ctx: TacticalContext, camp: Tile, units: List<MapUnit>) {
        if (TacticalTargets.visibleEnemyDefender(ctx.civ, camp) != null) return
        for (unit in units) {
            if (unit.isDestroyed) continue
            // Try to get into position
            val ring = camp.neighbors.filter { unit.baseUnit.isWaterUnit == it.isWater }.minByOrNull { unit.getTile().aerialDistanceTo(it) }
            if (unit.getTile().aerialDistanceTo(camp) > 1 && ring != null) headFor(unit, ring)
            if (unit.hasMovement()) {
                // Capture the camp if it still exists - if there is an enemy then we'll attack later
                if (camp.militaryUnit == null) headFor(unit, camp)
            } else ctx.unitProcessed(unit)
            if (unit.getTile() == camp) {
                ctx.unitProcessed(unit)
                break
            }
        }
    }

    /** ExecuteMoveToPlot without marking the unit processed: [unit] heads for [tile] over a reach no other unit's move has made stale; true if it is there */
    private fun headFor(unit: MapUnit, tile: Tile): Boolean {
        unit.movement.clearPathfindingCache()
        if (unit.getTile() != tile && !unit.movement.canMoveTo(tile)) return false
        Deployment.headTowards(unit, tile)
        return unit.getTile() == tile
    }
}
