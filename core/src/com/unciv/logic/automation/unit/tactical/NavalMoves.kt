package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  The naval tactical moves outside the operations: CvTacticalAI::PlotNavalEscortMoves / ExecuteEscortEmbarkedMoves (Vox Populi 5.4.8). A warship
 *  that is not about to heal and can attack goes to the embarked land unit of ours (alone or not, moved or not) that it reaches this turn and that
 *  is in danger to protect it (the first one on a tie: an embarked unit cannot defend itself, so CvUnit::GetDanger is the maximum if enemies can
 *  attack its tile, [com.unciv.logic.automation.unit.PlotDanger], else none), shoots what it can on arrival
 *  ([TacticalSimulation.performRangedOpportunityAttack]) and is done for the turn.
 *
 *  Deviations: Unciv allows one military unit on a tile, so the warship takes a free water tile next to the embarked unit
 *  (MOVEFLAG_APPROX_TARGET_RING1) where the original may share its tile (CanStackUnitAtPlot, MOVEFLAG_IGNORE_DANGER). The original's first filter
 *  compares the plot distance with CvUnit::getMoves, which is in sixtieths of a movement point, so every embarked unit passes it; the filter
 *  that matters is its `TurnsToReachTarget(.., 1) <= 1`, the reach within this turn ([TacticalAttacks.turnsToReach] of at most 1).
 */
object NavalMoves {

    /** ExecuteEscortEmbarkedMoves: "can this unit get to the embarked unit in two moves?", with the original's maximum of one turn of movement */
    private const val MAX_ESCORT_TURNS = 1

    /** The embarked land units of ours (the original's vTargetUnits: every embarked unit of the player) */
    private fun embarkedUnits(ctx: TacticalContext): List<MapUnit> =
        ctx.civ.units.getCivUnits().filter { it.baseUnit.isLandUnit && it.isEmbarked() }.toList()

    /** The free water tiles next to [target] that [escort] may stand on, nearest to it first */
    private fun escortTiles(escort: MapUnit, target: Tile): List<Tile> =
        target.neighbors.filter { it.isWater && escort.movement.canMoveTo(it) }.sortedBy { it.aerialDistanceTo(escort.getTile()) }.toList()

    /** The tile [escort] goes to for [target]: the free water tile next to it that it reaches soonest within [MAX_ESCORT_TURNS], the nearest first; null if none */
    private fun escortPlace(escort: MapUnit, target: Tile): Tile? =
        escortTiles(escort, target).map { it to TacticalAttacks.turnsToReach(escort, it) }.filter { it.second <= MAX_ESCORT_TURNS }.minByOrNull { it.second }?.first

    /**
     *  CvTacticalAI::PlotNavalEscortMoves: for each warship still free that need not heal, the embarked unit to escort ([escortTarget]) and the move
     *  ([TacticalMoves.moveSwapping]: the ship goes to the tile, swapping with a ship of ours that holds it).
     */
    fun plotEscortEmbarkedMoves(ctx: TacticalContext) {
        val embarked = embarkedUnits(ctx)
        if (embarked.isEmpty()) return
        for (ship in ctx.liveUnits()) {
            if (!ship.baseUnit.isWaterUnit || ship.baseUnit.isAirUnit() || !ship.isMilitary() || !TacticalAttacks.canAttack(ship) || !ship.hasMovement()) continue
            if (TacticalHealing.shouldHeal(ctx.civ, ship, false)) continue
            // Other units have moved since the reach of this one was cached
            ship.movement.clearPathfindingCache()
            val (target, place) = escortTarget(ctx, ship, embarked) ?: continue
            ctx.log { "${ship.name} escorts the embarked unit at ${target.getTile().position} from ${place.position}" }
            if (!TacticalMoves.moveSwapping(ship, place)) continue
            // If we can shoot while doing this, do it
            if (!ship.isDestroyed && TacticalSimulation.performRangedOpportunityAttack(ctx, ship)) ctx.log { "${ship.name} escort opportunity range attack at ${ship.getTile().position}" }
            if (!ship.isDestroyed) {
                ship.currentMovement = 0f
                ctx.unitProcessed(ship)
            }
        }
    }

    /** The first embarked unit in the most danger that [ship] reaches this turn, and the tile the ship stands on to protect it */
    private fun escortTarget(ctx: TacticalContext, ship: MapUnit, embarked: List<MapUnit>): Pair<MapUnit, Tile>? {
        var best: Pair<MapUnit, Tile>? = null
        var highestDanger = -1
        for (target in embarked) {
            if (target.isDestroyed || !target.isEmbarked()) continue
            val place = escortPlace(ship, target.getTile()) ?: continue
            // CvUnit::GetDanger of a unit that cannot defend itself: the most there is where enemies can attack it
            val level = if (ctx.danger.get(target.getTile()) > 0) Int.MAX_VALUE else 0
            if (level > highestDanger) { highestDanger = level; best = target to place }
        }
        return best
    }
}
