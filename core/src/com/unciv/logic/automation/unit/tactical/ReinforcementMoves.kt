package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  Bringing units to the zones that need them, the second pass of CvTacticalAI::ProcessDominanceZones: PlotReinforcementMoves (also the early one a
 *  hedgehog zone asks for) and PositionUnitsAroundTarget, which places the units it finds around the city of the zone: first with the combat
 *  simulation at [AggressionLevel.Low] ([TacticalSimulation.planAndExecute], after the spotter move), then the units that are still far from
 *  the target, by their own path - a land unit embarks only if we have a warship among them and the zone it ends the turn in is ours.
 *
 *  Deviations: the units the original finds on the plots of the step finder around the city (PT_ARMY_MIXED, no player: any terrain, foreign land
 *  too, up to [recruitRange]) are found on the plots within [recruitRange] steps of the city that are not impassable; units of an army are left
 *  to the army (the original's armies have moved by now and are processed), as are great generals and admirals;
 *  a far-out unit's path is the one to the nearest of three plots within two of the target (MOVEFLAG_APPROX_TARGET_RING2, as
 *  [AiOperationLogic.turnsToReach]) at most [recruitRange] turns long, and the first plot of it is what the danger and embarkation tests look at; the
 *  original asks the zone's neighbors of the zone owner's map, here it is ours; the third round of PositionUnitsAroundTarget, which flees with the units
 *  of an army that did not move, has no units here.
 */
object ReinforcementMoves {

    /** CvTacticalAI::GetRecruitRange: AI_TACTICAL_RECRUIT_RANGE, plus one per 100 turns of a 400-turn game, plus one on a large and two on a huge map */
    fun recruitRange(ctx: TacticalContext): Int {
        var range = ctx.recruitRange + 4 * ctx.turn / MAX_TURNS
        when (ctx.civ.gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller()) {
            MapSize.Predefined.Large -> range += 1
            MapSize.Predefined.Huge -> range += 2
            else -> Unit
        }
        return range
    }

    /** The turns of the original's game after which the range stops growing: max(400, the game's maximum), a game of ours has no maximum */
    private const val MAX_TURNS = 400

    /** The most steps from the city a unit may be found, as [recruitRange] */
    private fun plotsInReach(target: Tile, range: Int): Map<Tile, Int> {
        val reached = HashMap<Tile, Int>()
        reached[target] = 0
        var frontier = listOf(target)
        for (steps in 1..range) {
            val next = ArrayList<Tile>()
            for (tile in frontier) for (neighbor in tile.neighbors) {
                if (neighbor in reached || neighbor.isImpassible()) continue
                reached[neighbor] = steps
                next.add(neighbor)
            }
            frontier = next
        }
        return reached
    }

    /** CvUnit::canUseForTacticalAI for the units reinforcements are made of: a combat unit that can move, was not used and is not an explorer or a carrier */
    private fun canUse(ctx: TacticalContext, unit: MapUnit): Boolean {
        if (unit.isDestroyed || unit.civ != ctx.civ || !unit.hasMovement() || unit in ctx.cache.processedUnits) return false
        if (!unit.isMilitary() || unit.baseUnit.isAirUnit()) return false
        return when (UnitAiTypes.current(unit)) {
            UnitAiType.EXPLORE, UnitAiType.EXPLORE_SEA, UnitAiType.CARRIER_SEA, UnitAiType.ICBM, UnitAiType.MISSILE_CARRIER_SEA -> false
            else -> true
        }
    }

    /**
     *  TacticalAIHelpers::GetTargetsInRange (not civilians, kill or not): the enemy units and cities the unit can attack from where it can get
     *  this turn (ranged units: shoot), and the barbarian camps with no unit it can enter.
     */
    private fun targetsInRange(ctx: TacticalContext, unit: MapUnit): List<Tile> {
        unit.movement.clearPathfindingCache()
        val reach = unit.movement.getDistanceToTiles()
        val result = ArrayList<Tile>()
        for (attackable in TargetHelper.getAttackableEnemies(unit, reach)) {
            val tile = attackable.tileToAttack
            if (tile.isCityCenter() || TacticalTargets.visibleEnemyDefender(ctx.civ, tile) != null) result.add(tile)
        }
        for (tile in reach.keys)
            if (tile.improvement == Constants.barbarianEncampment && tile.militaryUnit == null) result.add(tile)
        return result
    }

    /**
     *  CvTacticalAI::PlotReinforcementMoves: close the units of the surroundings in on the city of [zone], unless the zone is given up (a posture
     *  to withdraw), has no city (wilderness) or is a neutral city without enemies; we do not need more when we dominate it with units that shoot
     *  (unless it has no melee unit to take the city, then only melee units come); a zone the enemy dominates far from our land is out of reach.
     *  The units come from the plots within [recruitRange] steps of the city that are not in a zone of ours that needs them (an enemy-dominated zone
     *  that is not withdrawing, except for a land unit in a city that is not about to fall), do not carry a civilian covering and are not hurt (the
     *  heal of before the attacks in our land), skipping those with something else to attack beyond [AiOperationLogic.MAX_TARGET_DISTANCE] of the city.
     *  A lone unit is for the pillage moves and the like: there must be at least two units in the zone with the ones coming.
     */
    fun plotReinforcementMoves(ctx: TacticalContext, zone: DominanceZone) {
        val civ = ctx.civ
        // Sometimes there is nothing to do...
        if (ctx.postureOf(zone) == TacticalPosture.Withdraw) return
        // Do not try to reinforce wilderness zones
        val city = zone.closestCity ?: return
        // Do not try to reinforce neutral zones if there are no enemies
        if (city.civ != civ && zone.totalEnemyUnitCount == 0) return

        // Sometimes we do not need further reinforcement
        var needMeleeOnly = false
        if (zone.dominance == TacticalDominance.Friendly && zone.rangedDominance(RANGED_DOMINANCE_PERCENT) == TacticalDominance.Friendly) {
            if (zone.friendlyMeleeStrength == 0) needMeleeOnly = true // get in melee units to capture a city
            else return
        }
        // Sometimes it is pointless, too far out
        if (!zone.hasNeighborZone(civ) && zone.dominance == TacticalDominance.Enemy) return

        val target = city.getCenterTile()
        val plots = plotsInReach(target, recruitRange(ctx))
        val candidates = ArrayList<MapUnit>()
        var alreadyInZone = 0
        val inReach = ctx.allUnits.filter { !it.isDestroyed && it.civ == civ && it.getTile() in plots }.sortedWith(compareBy({ it.getTile().zeroBasedIndex }, { it.id }))
        for (unit in inReach) {
            if (!canUse(ctx, unit) || ctx.civ.ai.operations.armyOf(unit) != null) continue
            val tile = unit.getTile()
            val unitZone = ctx.map.zoneOf(tile)
            if (unitZone != null && unitZone != zone) {
                // We should not pull units from zones that need them
                if (unitZone.dominance != TacticalDominance.Friendly && ctx.postureOf(unitZone) != TacticalPosture.Withdraw) {
                    val cityHere = tile.getCity()?.takeIf { tile.isCityCenter() }
                    if (cityHere == null || CityDangerAi.isInDangerOfFalling(cityHere, false) || unit.baseUnit.isWaterUnit)
                        if (!unit.isEmbarked()) continue // cannot fight if embarked, so we can take it
                }
            }
            // Skip ranged if we only need melee
            if (needMeleeOnly && unit.baseUnit.isRanged()) continue
            val aiType = UnitAiTypes.current(unit)
            // Do not move siege units into enemy dominated zones... wait until we have some cover
            if (aiType == UnitAiType.CITY_BOMBARD && zone.dominance == TacticalDominance.Enemy) continue
            // Do not send generals and admirals across the map in an uncontrolled and potentially unescorted manner
            if (Deployment.isGeneralOrAdmiral(unit)) continue
            // Conversely, do not leave a unit that covers a civilian out in the cold
            if (TacticalSafety.isCoveringFriendlyCivilian(ctx, unit)) {
                Deployment.executeMoveToPlot(ctx, unit, tile)
                continue
            }
            // Proper domain of unit? Note that coastal cities have two zones, so we will call this method twice
            if (!(if (zone.isWater) unit.baseUnit.isWaterUnit else unit.baseUnit.isLandUnit)) continue
            // Do not use near-dead units to attack...
            if (TacticalHealing.shouldHeal(civ, unit, zone.territory == TacticalTerritory.Friendly)) continue
            // Do not run away if there is other work to do (the attacks will be made by the posture moves)
            if (targetsInRange(ctx, unit).any { it.aerialDistanceTo(target) > AiOperationLogic.MAX_TARGET_DISTANCE }) continue
            candidates.add(unit)
            if (unitZone == zone) alreadyInZone++
        }

        // If we only have a single unit to work with in total, this is a case for pillage moves or the like
        if (zone.totalFriendlyUnitCount + candidates.size - alreadyInZone < 2) return
        val usable = candidates.filter { !it.isDestroyed && it.hasMovement() && it !in ctx.cache.processedUnits }
        if (usable.isNotEmpty()) positionUnitsAroundTarget(ctx, usable, target)
    }

    /** The dominance percentage GetRangedDominanceFlag is asked with: the ranged units must be at least as strong */
    private const val RANGED_DOMINANCE_PERCENT = 100

    /**
     *  CvTacticalAI::PositionUnitsAroundTarget: place [units] around [target], which can be friendly, neutral or hostile. First the spotter move
     *  that tries to see it, then the plan of the combat simulation at [AggressionLevel.Low]; then the units that it left unprocessed and
     *  that are farther from the target than [AiOperationLogic.MAX_TARGET_DISTANCE] steps (or all of them if no plan was carried out) move in,
     *  siege units after the others, as long as the first plot of their path is not more dangerous than half their hit points (none for siege units).
     *  @return whether a plan of the simulation was carried out
     */
    fun positionUnitsAroundTarget(ctx: TacticalContext, units: List<MapUnit>, target: Tile): Boolean {
        // Try to improve visibility; if the target is too far away this may fail, in which case we chance it
        TacticalSimulation.executeSpotterMove(ctx, units, target)
        ctx.log { "seeking defensive positioning around ${target.position}" }

        // First round: in case there are enemies around, do a combat simulation
        val outcome = TacticalSimulation.planAndExecute(ctx, units.filter { !it.isDestroyed && it.hasMovement() }, target, AggressionLevel.Low)

        // Sometimes the simulation cannot use all units, for example if they are too far out
        val farOut = ArrayList<MapUnit>()
        var haveNavalEscort = false
        for (unit in units) {
            if (unit.isDestroyed) continue
            // Also include the units we already moved
            if (unit.baseUnit.isWaterUnit && TacticalAttacks.canAttack(unit)) haveNavalEscort = true
            if (unit in ctx.cache.processedUnits) continue
            // Do not end the turn: we may want to shuffle them around later
            if (outcome.success && outcome.distanceToTarget(unit, unit.getTile()) <= AiOperationLogic.MAX_TARGET_DISTANCE) continue
            farOut.add(unit)
        }

        // Second round: move in as long as there is no danger and we are still far away. Siege units go last, so they have a better chance of cover
        for (unit in farOut.sortedBy { if (UnitAiTypes.current(it) == UnitAiType.CITY_BOMBARD) 1 else 0 }) {
            if (unit.isDestroyed || unit in ctx.cache.processedUnits) continue
            moveFarUnit(ctx, unit, target, haveNavalEscort)
        }
        return outcome.success
    }

    /** One unit of the second round of [positionUnitsAroundTarget]: along its path toward [target] if the first plot of it is safe enough */
    private fun moveFarUnit(ctx: TacticalContext, unit: MapUnit, target: Tile, haveNavalEscort: Boolean) {
        // The plan has moved units: what this one reaches is not what was cached
        unit.movement.clearPathfindingCache()
        val approach = approach(unit, target) ?: return
        if (approach.path.size > recruitRange(ctx)) return
        val firstStop = approach.path.first()

        // We are not here to fight or flee, let other moves take over; generals and siege should not even be in fog danger
        val dangerLimit = if (TacticalAttacks.canAttack(unit) && UnitAiTypes.current(unit) != UnitAiType.CITY_BOMBARD) unit.health / 2 else 0
        if (ctx.civ.ai.plotDanger.get(unit, firstStop) > dangerLimit) return

        // Embark only when it is safe, and only with a naval escort
        if (unit.baseUnit.isLandUnit && !unit.isEmbarked() && firstStop.isWater) {
            val zoneThere = ctx.map.zoneOf(firstStop)
            if (!haveNavalEscort || zoneThere != null && zoneThere.dominance != TacticalDominance.Friendly) return
        }
        ctx.log { "${unit.name} moves from ${unit.getTile().position} toward ${target.position}, first stop ${firstStop.position}" }
        TacticalMoves.moveSwapping(unit, firstStop)
        unit.currentMovement = 0f
        ctx.unitProcessed(unit, unit.isMilitary())
    }

    /** A unit's way to the target: the path that ends within two plots of it (the plot it ends the first turn on is the first of the path) */
    private class Approach(val path: List<Tile>)

    /**
     *  MOVEFLAG_APPROX_TARGET_RING2: the shortest of the paths to the three plots within two of [target] that are nearest to the unit (as
     *  [AiOperationLogic.turnsToReach]); null if the unit is already within two plots of the target, or has no path.
     */
    private fun approach(unit: MapUnit, target: Tile): Approach? {
        val here = unit.getTile()
        if (here.aerialDistanceTo(target) <= RING_TWO) return null
        var best: List<Tile>? = null
        val candidates = target.getTilesInDistanceRange(1..RING_TWO).filter { unit.movement.canPassThrough(it) }.sortedBy { it.aerialDistanceTo(here) }.take(RING_CANDIDATES)
        for (candidate in candidates) {
            val path = unit.movement.getShortestPath(candidate)
            if (path.isNotEmpty() && (best == null || path.size < best.size)) best = path
        }
        return best?.let { Approach(it) }
    }

    private const val RING_TWO = 2
    private const val RING_CANDIDATES = 3
}
