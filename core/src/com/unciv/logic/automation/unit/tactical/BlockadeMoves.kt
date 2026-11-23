package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.NAVAL_PLOT_BLOCKADE_RANGE
import com.unciv.logic.map.tile.Tile
import kotlin.math.max

/**
 *  Naval blockades, the part of CvTacticalAI that disrupts the use of the enemy's water: the blockade points of FindTacticalTargets with
 *  PrioritizeNavalTargetsAndAddToMainList, CvPlot::GetSeaBlockadeScore, and PlotBlockadeMoves with ExecuteNavalBlockadeMove.
 *
 *  A revealed water tile in the land of a player at war with us, less than 4 tiles from his nearest city, that no enemy can attack
 *  (no danger on it, we want to stay for a while) is a blockade point. Its weight is its sea blockade score ([seaBlockadeScore]: the water of
 *  the same sea around it that is the enemy's counts more, with resources, features and improvements) less our distance to it from our nearest city
 *  (at least 1), doubled if the city that owns it is damaged or under siege (to support the troops). Only the 10 best points are kept, and those
 *  with an improvement on them (a harbor's, a work boat's), whatever their weight.
 *
 *  Each point in turn (the ones of the highest weight first) gets the one naval unit of the tactical AI with at least half its hit points that can be
 *  there within 4 turns, with no more danger on the way than it has health ([UnitSelection.findUnitsForHarassing]): it pillages if that suits it
 *  where it stands, goes to the point (or next to it if a unit holds it), attacks what it can reach and pillages again, and its turn is over.
 *
 *  Deviations: the range of the blockade score is the ruleset's NAVAL_PLOT_BLOCKADE_RANGE, [NAVAL_PLOT_BLOCKADE_RANGE] (2 as in Brave New World; the rule
 *  that decides what a ship blockades, where Vox Populi has 1); the enemy's distance to a city is a plot distance as in the original, ours (its
 *  "distance by path length" from our nearest city) too, there is no distance map by path here;
 *  "no possible attacker on the tile" is no danger on it ([PlotDanger]); a city-state's city distance is half the distance to its capital, as in the
 *  original. Barbarians have no blockade moves (AssignBarbarianMoves has none).
 */
object BlockadeMoves {

    /** The blockade points further than this from the owner's nearest city are not wanted: we cannot stay out of danger there */
    private const val MAX_CITY_DISTANCE = 4
    private const val MAX_KEPT_POINTS = 10
    private const val TURNS_AWAY = 4
    /** MAX_HIT_POINTS / 2, a global define and so an absolute number of hit points (CvTacticalAI.cpp:1313) */
    private const val MIN_HEALTH = 50

    /**
     *  CvPlot::GetSeaBlockadeScore: for each tile of the same sea within [NAVAL_PLOT_BLOCKADE_RANGE] of [tile] (itself included), 1; and 1 more
     *  for each of resource we know, feature and improvement if the tile is in the land of a player at war with [civ].
     */
    fun seaBlockadeScore(civ: Civilization, map: TacticalMap, tile: Tile): Int {
        var score = 0
        val area = map.areaOf(tile)
        for (around in tile.getTilesInDistance(NAVAL_PLOT_BLOCKADE_RANGE)) {
            if (!around.isWater || map.areaOf(around) != area) continue
            val owner = around.getOwner()
            if (owner != null && civ.isAtWarWith(owner)) {
                score++
                if (civ.canSeeResource(around.tileResource)) score++
                if (around.terrainFeatures.isNotEmpty()) score++
                if (around.improvement != null) score++
            }
            score++
        }
        return score
    }

    /** CvPlayer::GetCityDistanceInPlots of the player that owns the water tile: a major's is the distance to its nearest city, a city-state's half that to its capital */
    private fun cityDistanceInPlots(owner: Civilization, tile: Tile): Int {
        if (owner.isMajorCiv) return owner.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: Int.MAX_VALUE
        return owner.getCapital()?.let { it.getCenterTile().aerialDistanceTo(tile) / 2 } ?: Int.MAX_VALUE
    }

    /**
     *  The blockade points of FindTacticalTargets (the "enemy water plots" branch) and PrioritizeNavalTargetsAndAddToMainList: the best 10 by
     *  weight and every one with an improvement, most valuable first.
     */
    fun findPoints(civ: Civilization, map: TacticalMap, danger: PlotDanger): List<TacticalTarget> {
        val candidates = ArrayList<TacticalTarget>()
        for (tile in civ.gameInfo.tileMap.tileList) {
            if (!tile.isWater || tile.isImpassible() || !tile.isExplored(civ)) continue
            val city = tile.getCity() ?: continue
            val owner = city.civ
            if (owner == civ || !civ.isAtWarWith(owner)) continue
            // We want to stay for a while, so stay out of danger as far as possible
            if (cityDistanceInPlots(owner, tile) >= MAX_CITY_DISTANCE || danger.get(tile) > 0) continue
            // Try to stay away from land, and prefer close targets
            val ourDistance = civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 0
            var weight = max(1, seaBlockadeScore(civ, map, tile) - ourDistance)
            // Try to support the troops
            if (city.health < city.getMaxHealth() || CityDangerAi.isUnderSiege(city)) weight *= 2
            candidates.add(TacticalTarget(TacticalTargetType.BlockadePoint, tile, map.zoneOf(tile)?.id ?: -1, owner, aux = weight))
        }
        // std::stable_sort by aux data, the highest first
        val sorted = candidates.sortedByDescending { it.aux }
        return sorted.filterIndexed { index, target -> target.tile.improvement != null || index < MAX_KEPT_POINTS }
    }

    /** CvTacticalAI::PlotBlockadeMoves: a ship for each blockade point of the current targets, if one can get there safely */
    fun plotBlockadeMoves(ctx: TacticalContext) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.BlockadePoint)) {
            // See what units we have who can reach target this turn
            val candidates = UnitSelection.findUnitsForHarassing(ctx, target.tile, TURNS_AWAY, MIN_HEALTH, -1, true, false, 1)
            if (candidates.isEmpty()) continue
            // Queue the best one up to blockade it
            executeNavalBlockadeMove(ctx, target.tile, candidates)
        }
    }

    /**
     *  CvTacticalAI::ExecuteNavalBlockadeMove: the first of [units] that has a path to [target] and no more danger there and at the end of its first turn
     *  than it has health goes; one is enough.
     */
    private fun executeNavalBlockadeMove(ctx: TacticalContext, target: Tile, units: List<MapUnit>) {
        val danger = ctx.civ.ai.plotDanger
        for (unit in units) {
            if (unit.isDestroyed || unit in ctx.cache.processedUnits || !unit.hasMovement()) continue
            // See if we can harass the enemy first
            if (PillageRules.shouldPillage(unit, unit.getTile())) PillageRules.pillage(unit)
            // Safety check
            if (danger.get(unit, target) > unit.health) continue
            // Make sure we have a valid path
            unit.movement.clearPathfindingCache()
            val firstStop = unit.movement.getShortestPath(target).firstOrNull() ?: continue
            if (danger.get(unit, firstStop) > unit.health) continue

            ctx.log { "naval blockade at ${target.position} with ${unit.name} at ${unit.getTile().position}" }
            // MOVEFLAG_APPROX_TARGET_RING1: next to the point if something holds it
            val destination = if (unit.getTile() == target || unit.movement.canMoveTo(target)) target
                else TacticalMoves.emptyTileNextTo(unit, target) ?: target
            TacticalMoves.moveSwapping(unit, destination)
            // See if we can harass the enemy now
            if (!unit.isDestroyed) OpportunityAttacks.perform(ctx, unit, true)
            if (!unit.isDestroyed && PillageRules.shouldPillage(unit, unit.getTile(), conservative = false, ignoreMovement = true)) PillageRules.pillage(unit)
            if (!unit.isDestroyed) ctx.unitProcessed(unit)
            // One is enough?
            break
        }
    }
}
