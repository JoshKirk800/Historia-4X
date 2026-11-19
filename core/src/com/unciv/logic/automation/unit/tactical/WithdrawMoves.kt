package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  CvTacticalAI::PlotWithdrawMoves and ExecuteWithdrawMoves of Vox Populi 5.4.8 (Community Patch and Vox Populi share them): the units in a dominance
 *  zone whose posture is withdrawal leave for the neighbouring zone of ours that seems safest, or fall back to the nearest city.
 *
 *  Deviations: a neighbouring zone's destination is its city (the original takes the average of the zone's plots); the nearest city of ours is by
 *  distance on the unit's landmass or sea (GetClosestCityByPathLength); the path to the neighbouring zone must not be longer than 12 turns and does
 *  not avoid danger (MOVEFLAG_AI_ABORT_IN_DANGER has no counterpart).
 */
object WithdrawMoves {

    /** ExecuteWithdrawMoves: how many turns to a neighbouring zone's city are still worth it */
    private const val MAX_TURNS_TO_ZONE = 12
    private const val RANGED_RETREAT_KEY = 30
    private const val MELEE_RETREAT_KEY = 20

    /**
     *  PlotWithdrawMoves for [zone]: the units of the tactical AI (not a healthy one that was deployed from an operation lately) that stand in the zone
     *  - except in a zone that is not ours, those on one of our citadels and those on our land with no enemy territory next to it - withdraw, the ranged and
     *  slow ones first.
     *  @return the units that moved
     */
    fun plotWithdrawMoves(ctx: TacticalContext, zone: DominanceZone): List<MapUnit> {
        val civ = ctx.civ
        val recent = ctx.rules.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS)
        class Withdrawing(val unit: MapUnit, val key: Int)
        val withdrawing = ArrayList<Withdrawing>()
        for (unit in ctx.liveUnits()) {
            if (!unit.hasMovement() || unit.baseUnit.isAirUnit()) continue
            // Recent, healthy deployments need to attack!
            if (civ.ai.deployFromOperationTurn(unit) + recent >= ctx.turn && unit.health > MAX_HEALTH / 2) continue
            // Am I in the current dominance zone? Units in other dominance zones need to fend for themselves, depending on their own posture
            if (ctx.map.zoneOf(unit.getTile())?.id != zone.id) continue
            // However, zones might overlap borders, so double check that we don't give up our border forts
            if (zone.territory != TacticalTerritory.Friendly) {
                val tile = unit.getTile()
                if (unit.baseUnit.isLandUnit && ctx.map.isPlayerCitadel(tile, civ)) continue
                if (tile.isFriendlyTerritory(civ) && tile.neighbors.none { it.isEnemyTerritory(civ) }) continue
            }
            // We will later sort by "attack strength" so fake it: ranged and slow units should retreat first
            withdrawing.add(Withdrawing(unit, (if (unit.baseUnit.isRanged()) RANGED_RETREAT_KEY else MELEE_RETREAT_KEY) - unit.baseUnit.movement))
        }
        if (withdrawing.isEmpty()) return emptyList()
        // Melee units should retreat last
        return executeWithdrawMoves(ctx, withdrawing.sortedByDescending { it.key }.map { it.unit })
    }

    /**
     *  ExecuteWithdrawMoves: each unit goes to the neighbouring zone of its own domain with a city that is the best by hospitality (7 in our land, 3 in
     *  neutral land, 7 if we dominate it, 3 if it is even) x 100 / (turns to reach + 1); else towards our nearest city. It shoots from where it ends
     *  up and is done. A unit that finds no way goes to the [SafestPlot] after pillaging if that is worth a lot ([PillageRules.shouldPillage], conservative).
     */
    private fun executeWithdrawMoves(ctx: TacticalContext, units: List<MapUnit>): List<MapUnit> {
        val civ = ctx.civ
        val moved = ArrayList<MapUnit>()
        for (unit in units) {
            if (unit.isDestroyed) continue
            // Allow withdraw to neighboring tactical zone which seems safe
            val zone = ctx.map.zoneOf(unit.getTile()) ?: continue
            var bestScore = 0
            var target: Tile? = null
            for (next in zone.neighbors) {
                val city = next.closestCity ?: continue
                if (next.isWater != unit.baseUnit.isWaterUnit) continue
                val turns = TacticalAttacks.turnsToReach(unit, city.getCenterTile())
                if (turns == Int.MAX_VALUE || turns > MAX_TURNS_TO_ZONE) continue
                val score = hospitality(next) * 100 / (turns + 1)
                if (score > bestScore) {
                    bestScore = score
                    target = city.getCenterTile()
                }
            }
            if (target == null) {
                // Just go towards nearest city
                val home = if (civ.isCityState) civ.getCapital()?.getCenterTile()
                    else civ.cities.filter { ctx.sameArea(it.getCenterTile(), unit.getTile()) }.minByOrNull { it.getCenterTile().aerialDistanceTo(unit.getTile()) }?.getCenterTile()
                target = home
            }
            if (target != null && TacticalMoves.moveToEmptySpaceNearTarget(ctx, unit, target)) {
                TacticalSimulation.performRangedOpportunityAttack(ctx, unit, false)
                ctx.unitProcessed(unit)
                moved.add(unit)
                AiDecisionLog.log(civ) { "Tactical: ${unit.name} withdrew from ${zone.id} towards ${target.position}" }
            } else {
                if (PillageRules.shouldPillage(unit, unit.getTile(), conservative = true)) PillageRules.pillage(unit)
                // Now move all units which didn't find a path to a city
                SafestPlot.executeMovesToSafestPlot(ctx, unit)
                moved.add(unit)
            }
        }
        return moved
    }

    /** CvTacticalDominanceZone::getHospitalityScore: 7 for a zone in our territory, 3 in neutral territory; 7 for one we dominate, 3 for an even one */
    fun hospitality(zone: DominanceZone): Int {
        var score = 0
        when (zone.territory) {
            TacticalTerritory.Friendly -> score += 7
            TacticalTerritory.Neutral -> score += 3
            else -> Unit
        }
        when (zone.dominance) {
            TacticalDominance.Friendly -> score += 7
            TacticalDominance.Even -> score += 3
            else -> Unit
        }
        return score
    }

    private const val MAX_HEALTH = 100
}
