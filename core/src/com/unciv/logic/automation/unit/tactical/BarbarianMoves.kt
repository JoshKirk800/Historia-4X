package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.MilitaryAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.max

/**
 *  The barbarian-only moves of CvTacticalAI: PlotBarbarianMove / ExecuteBarbarianMoves with FindBestBarbarianLandMove,
 *  FindPassiveBarbarianLandMove, FindBestBarbarianSeaMove and FindBarbarianExploreTarget, PlotCampDefenseMoves, and
 *  PlotBarbarianCivilianEscortMove / ExecuteBarbarianCivilianEscortMove. The attack moves barbarians share with the other players are
 *  [TacticalMoves]; [com.unciv.logic.automation.civilization.BarbarianAutomation] orders them all by AI_TACTICAL_BARBARIAN_PRIORITY_*.
 *
 *  Deviations: a sea move looks at the 5 nearest targets only (as [TacticalMoves.findNearbyTarget]);
 *  the camp-adjacent search of FindBestBarbarianSeaMove compares `distance < moves * bestValue` with the best value starting at the largest
 *  Int, which overflows in the original for ships that move more than one tile (it then never finds a plot): here it is the intent, any
 *  such plot within the sea range x 3; barbarians do not upgrade units (the original has no such move for them).
 */
object BarbarianMoves {

    /** HandicapInfos.BarbarianLandTargetRange of the game's difficulty (the AI default handicap's 5 for a ruleset without the column) */
    fun landRange(civ: Civilization): Int =
        civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, "BarbarianLandTargetRange") ?: DEFAULT_LAND_RANGE

    /** HandicapInfos.BarbarianSeaTargetRange of the game's difficulty (10 by default) */
    fun seaRange(civ: Civilization): Int =
        civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, "BarbarianSeaTargetRange") ?: DEFAULT_SEA_RANGE

    private const val DEFAULT_LAND_RANGE = 5
    private const val DEFAULT_SEA_RANGE = 10

    /** The target types FindNearbyTarget looks at for AI_TACTICAL_TARGET_NONE: units, cities and improvements of the enemy */
    private val HUNTED = setOf(
        TacticalTargetType.HighPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.LowPriorityUnit,
        TacticalTargetType.City, TacticalTargetType.Improvement
    )

    //region Camps

    /**
     *  CvTacticalAI::PlotBarbarianCampDefense: for each camp, its barbarian defender - which upgrades for free if it is healthy and can, else a shooter
     *  shoots what it can without leaving the camp, else a melee unit first captures an unprotected civilian next to the camp if it can be back this turn, and
     *  attacks an enemy next to it without leaving - stays and is done. A camp without a defender gets the nearest barbarian land unit within 5 turns
     *  ([UnitSelection.findUnitsForHarassing]) that is not in a camp itself, which goes there.
     */
    fun plotCampDefenseMoves(ctx: TacticalContext) {
        for (target in TacticalTargets.ofType(ctx.targets.all, TacticalTargetType.BarbarianCamp)) {
            val camp = target.tile
            val defender = camp.militaryUnit?.takeIf { it.civ == ctx.civ && !it.isDestroyed }
            if (defender != null) {
                val upgraded = if (defender.health >= MAX_HEALTH) UseGoldAutomation.upgradeNow(defender, free = true) else null
                if (upgraded != null) {
                    ctx.unitProcessed(upgraded)
                    continue
                }
                if (defender.baseUnit.isRanged()) {
                    // Don't leave camp
                    TacticalSimulation.performRangedOpportunityAttack(ctx, defender)
                } else {
                    defender.movement.clearPathfindingCache() // other units have moved since its reach was cached
                    // Capture unprotected civilians if we can return to camp in the same turn
                    val prey = camp.neighbors.firstOrNull { n ->
                        TacticalTargets.visibleEnemyDefender(ctx.civ, n) == null && n.getUnits().any { it.civ != ctx.civ && ctx.civ.isAtWarWith(it.civ) && !TacticalSafety.canDefend(it) }
                            && defender.movement.canMoveTo(n) && TacticalAttacks.turnsToReach(defender, n) == 0
                    }
                    if (prey != null && defender.movement.canReachInCurrentTurn(prey)) {
                        defender.movement.moveToTile(prey)
                        defender.movement.clearPathfindingCache()
                        if (defender.hasMovement() && defender.getTile() != camp && defender.movement.canReachInCurrentTurn(camp)) defender.movement.moveToTile(camp)
                    }
                    // Melee may attack but never leave camp (because they won't return then)
                    if (!defender.isDestroyed) OpportunityAttacks.perform(ctx, defender, false)
                }
                if (!defender.isDestroyed) ctx.unitProcessed(defender)
            } else {
                val unit = UnitSelection.findUnitsForHarassing(ctx, camp, CAMP_DEFENSE_TURNS, 0, 0, false, false, 1).firstOrNull() ?: continue
                ctx.log { "moving to protect camp, ${camp.position}" }
                if (Deployment.headTowards(unit, camp)) ctx.unitProcessed(unit)
            }
        }
    }

    private const val MAX_HEALTH = 100
    private const val CAMP_DEFENSE_TURNS = 5
    //endregion

    //region Land and sea moves

    /**
     *  CvTacticalAI::PlotBarbarianMove / ExecuteBarbarianMoves: every unit still free ends its turn here. A land unit heads for the tile
     *  [findBestLandMove] (aggressive) or [findPassiveLandMove] found, to an empty tile next to it; a ship keeps its destination from the
     *  turns before while it can still get there, else takes [findBestSeaMove]'s.
     */
    fun executeMoves(ctx: TacticalContext, aggressive: Boolean) {
        val state = ctx.state
        for (unit in ctx.liveUnits()) {
            if (!unit.hasMovement() || unit.baseUnit.isAirUnit()) continue
            if (!unit.baseUnit.isWaterUnit) {
                val best = if (aggressive) findBestLandMove(ctx, unit) else findPassiveLandMove(ctx, unit)
                if (best != null && TacticalMoves.moveToEmptySpaceNearTarget(ctx, unit, best))
                    ctx.log { "${unit.name} moved to an empty space near target ${best.position}, now at ${unit.getTile().position}" }
                else ctx.log { "no target for ${unit.name} at ${unit.getTile().position}" }
            } else {
                // Do I still have a destination from a previous turn? Compute a new one if not or if I am there
                val current = state.unitDestinations[unit.id]?.let { ctx.civ.gameInfo.tileMap.getOrNull(it.x, it.y) }
                val best = if (current != null && current != unit.getTile() && unit.movement.canReach(current)) current else findBestSeaMove(ctx, unit)
                if (best != null && unit.movement.canReach(best)) {
                    state.unitDestinations[unit.id] = best.position
                    if (best != unit.getTile()) unit.movement.tryHeadTowards(best)
                    ctx.log { "moving ${unit.name} to new position ${best.position}, now at ${unit.getTile().position}" }
                } else {
                    state.unitDestinations.remove(unit.id)
                    ctx.log { "no target for ${unit.name} at ${unit.getTile().position}" }
                }
            }
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
        }
    }

    /**
     *  CvTacticalAI::FindBestBarbarianLandMove: a unit, city or improvement of the enemy within the land target range that it reaches soonest;
     *  else the trade route to sit on ([findGankTradeRouteTarget]); else the best tile to explore towards.
     */
    fun findBestLandMove(ctx: TacticalContext, unit: MapUnit): Tile? =
        TacticalMoves.findNearbyTarget(ctx, unit, landRange(ctx.civ), ctx.targets.all.filter { it.type in HUNTED })
            ?: findGankTradeRouteTarget(ctx, unit) ?: findExploreTarget(ctx, unit)

    /** CvTacticalAI::FindPassiveBarbarianLandMove: the nearest barbarian camp, else the best tile to explore towards */
    fun findPassiveLandMove(ctx: TacticalContext, unit: MapUnit): Tile? {
        val camp = TacticalTargets.ofType(ctx.targets.all, TacticalTargetType.BarbarianCamp).minByOrNull { unit.getTile().aerialDistanceTo(it.tile) }
        return camp?.tile ?: findExploreTarget(ctx, unit)
    }

    /**
     *  CvTacticalAI::FindBestBarbarianSeaMove: the enemy unit within the sea target range on the unit's water that it reaches soonest; else
     *  a water tile next to a barbarian camp other than the nearest one that it reaches soonest; else the best tile to explore towards.
     */
    fun findBestSeaMove(ctx: TacticalContext, unit: MapUnit): Tile? {
        val unitTile = unit.getTile()
        val range = seaRange(ctx.civ)
        TacticalMoves.findNearbyTarget(ctx, unit, range, ctx.targets.all.filter { it.isUnitTarget })?.let { return it }
        findGankTradeRouteTarget(ctx, unit)?.let { return it }

        // No units to pick on, so sail to a tile adjacent to the second closest barbarian camp
        val camps = TacticalTargets.ofType(ctx.targets.all, TacticalTargetType.BarbarianCamp)
        val nearest = camps.minByOrNull { unitTile.aerialDistanceTo(it.tile) }
        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        val moves = max(1, unit.baseUnit.movement)
        for (camp in camps) {
            if (camp === nearest) continue
            for (water in camp.tile.neighbors) {
                if (!water.isWater) continue
                val distance = unitTile.aerialDistanceTo(water)
                // Drop the plots further than the best found so far could be
                if (distance.toLong() >= moves.toLong() * bestTurns || distance >= range * 3) continue
                val turns = TacticalAttacks.turnsToReach(unit, water)
                if (turns < bestTurns) { bestTurns = turns; best = water }
            }
        }
        return best ?: findExploreTarget(ctx, unit)
    }

    /**
     *  CvTacticalAI::FindBarbarianGankTradeRouteTarget: of the revealed tiles within the movement the unit has left, on its landmass or sea,
     *  that it can reach in a turn, the one most trade routes (of any civilization, the unit's own tile left out) cross; the first of
     *  the best wins, and a tile that no route crosses is not one.
     */
    fun findGankTradeRouteTarget(ctx: TacticalContext, unit: MapUnit): Tile? {
        val range = unit.currentMovement.toInt()
        if (range <= 0) return null
        val routesThrough = HashMap<Tile, Int>()
        for (civ in ctx.civ.gameInfo.civilizations)
            for (route in civ.tradeRoutes.routes)
                for (tile in route.getTiles()) routesThrough.merge(tile, 1, Int::plus)
        if (routesThrough.isEmpty()) return null
        val unitTile = unit.getTile()
        var best: Tile? = null
        var bestValue = 0
        for (tile in unitTile.getTilesInDistance(range)) {
            if (tile == unitTile || !ctx.civ.hasExplored(tile) || !ctx.sameArea(unitTile, tile)) continue
            val value = routesThrough[tile] ?: continue
            if (value <= bestValue || TacticalAttacks.turnsToReach(unit, tile) > 1) continue
            best = tile
            bestValue = value
        }
        return best
    }

    /**
     *  CvTacticalAI::FindBarbarianExploreTarget (Vox Populi): of the plots the unit reaches this turn without embarking, not its own,
     *  revealed and without a city, the one with the most around it (the plot and its neighbours): 3 for each not revealed, 2 for each
     *  revealed one that is owned and not visible; +100 for a plot of its own domain while embarked. The first best wins; none with
     *  nothing around it. VP's danger check (a plot more dangerous than the unit's hit points) is not ported.
     */
    fun findExploreTarget(ctx: TacticalContext, unit: MapUnit): Tile? {
        val civ = ctx.civ
        val unitTile = unit.getTile()
        var best: Tile? = null
        var bestValue = 0
        for (tile in unit.movement.getDistanceToTiles().keys) {
            if (tile == unitTile || !civ.hasExplored(tile) || tile.isCityCenter()) continue
            val nativeDomain = if (unit.baseUnit.isWaterUnit) tile.isWater else tile.isLand
            if (!nativeDomain && !unit.isEmbarked() || !unit.movement.canMoveTo(tile)) continue
            var value = 0
            for (around in tile.neighbors + tile) {
                if (!civ.hasExplored(around)) value += 3
                else if (!around.isVisible(civ) && around.getOwner() != null) value += 2
            }
            if (unit.isEmbarked() && nativeDomain) value += 100
            if (value > bestValue) {
                best = tile
                bestValue = value
            }
        }
        return best
    }
    //endregion

    //region Captured civilians

    /** The tile has a civilian of ours of the same type as [civilian] (CvPlot::getNumFriendlyUnitsOfType) */
    private fun hasLikeCivilian(civilian: MapUnit, tile: Tile) = tile.civilianUnit?.let { it != civilian && it.civ == civilian.civ && it.name == civilian.name } == true

    /**
     *  CvTacticalAI::PlotBarbarianCivilianEscortMove / ExecuteBarbarianCivilianEscortMove: every unit that cannot attack (the civilians the barbarians
     *  captured) goes to the nearest camp that has no civilian of its type yet, with the military unit of ours standing with it as escort:
     *  both move to where the civilian's path ends this turn, the escort taking over the camp's defense if it is stronger than the defender
     *  there (who is moved out of the way), and the pair stays put when the escort cannot keep up, the way is blocked or the path is lost.
     */
    fun executeCivilianEscortMoves(ctx: TacticalContext) {
        val camps = TacticalTargets.ofType(ctx.targets.all, TacticalTargetType.BarbarianCamp)
        for (civilian in ctx.liveUnits()) {
            if (TacticalAttacks.canAttack(civilian) || !civilian.hasMovement()) continue
            val target = TacticalMoves.findNearbyTarget(ctx, civilian, Int.MAX_VALUE, camps) { hasLikeCivilian(civilian, it) } ?: continue
            escortToCamp(ctx, civilian, target)
        }
    }

    /**
     *  ExecuteMoveToPlot for a unit that may not get there this turn (the original's move mission goes on over turns): it moves there if it
     *  can this turn, else heads for it, and its turn is over.
     */
    private fun moveSafely(ctx: TacticalContext, unit: MapUnit, tile: Tile) {
        if (unit.getTile() == tile || unit.movement.getDistanceToTiles().containsKey(tile) && unit.movement.canMoveTo(tile)) TacticalMoves.moveToPlot(ctx, unit, tile)
        else {
            if (unit.movement.canReach(tile)) unit.movement.tryHeadTowards(tile)
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
        }
    }

    private fun finish(ctx: TacticalContext, vararg units: MapUnit?) {
        for (unit in units) if (unit != null) { unit.currentMovement = 0f; ctx.unitProcessed(unit) }
    }

    private fun escortToCamp(ctx: TacticalContext, civilian: MapUnit, target: Tile) {
        val current = civilian.getTile()
        if (current == target) { finish(ctx, civilian); return }
        val escort = current.militaryUnit?.takeIf { it.civ == civilian.civ && it != civilian && it.hasMovement() }

        val path = civilian.movement.getShortestPath(target)
        // Handle case of no path found at all for civilian
        if (path.isEmpty()) {
            ctx.log { "retargeting civilian escort (path lost to target), ${target.position}" }
            finish(ctx, civilian, escort)
            return
        }
        val civilianMove = path.first()
        fun together(to: Tile, what: String) {
            if (escort != null) moveSafely(ctx, escort, to)
            moveSafely(ctx, civilian, to)
            ctx.log { "$what: ${civilian.name} ${if (escort != null) "with escort ${escort.name} " else ""}to ${to.position}" }
        }

        if (civilianMove == target) {
            // Can we reach our target this turn? See which defender is stronger
            val defender = target.militaryUnit?.takeIf { it.civ == civilian.civ }
            if (escort != null && (defender == null || escort.getAiPower() > defender.getAiPower())) {
                if (defender == null || TacticalMoves.executeMoveOfBlockingUnit(ctx, defender)) together(target, "escort takes over camp defense")
                else { moveSafely(ctx, civilian, target); ctx.log { "moving ${civilian.name} to camp, ${target.position}" } }
            } else {
                moveSafely(ctx, civilian, target)
                ctx.log { "moving ${civilian.name} to camp, ${target.position}" }
            }
            return
        }
        // Can't reach the target and don't have an escort
        if (escort == null) {
            moveSafely(ctx, civilian, civilianMove)
            ctx.log { "moving ${civilian.name} without escort to target, ${civilian.getTile().position}" }
            return
        }
        // See if the escort can move to the same location in one turn
        if (TacticalAttacks.turnsToReach(escort, civilianMove) <= 1 && escort.movement.canMoveTo(civilianMove)) { together(civilianMove, "escort keeps up"); return }
        val blocking = civilianMove.militaryUnit?.takeIf { it.civ == civilian.civ && it != escort }
        if (blocking == null || !blocking.hasMovement()) {
            // No blocking unit, or one that ends its turn there (somewhere the civilian can go and the escort cannot): find a path for the escort
            val escortPath = escort.movement.getShortestPath(target)
            if (escortPath.isEmpty()) {
                ctx.log { "retargeting civilian escort (path lost to target), ${target.position}" }
                finish(ctx, civilian, escort)
                return
            }
            val escortMove = escortPath.first()
            // See if the civilian can move to the same location in one turn
            if (TacticalAttacks.turnsToReach(civilian, escortMove) <= 1 && civilian.movement.canMoveTo(escortMove) && escort.movement.canMoveTo(escortMove))
                together(escortMove, "civilian keeps up with escort")
            else {
                ctx.log { "retargeting civilian escort, too many blocking units" }
                finish(ctx, civilian, escort)
            }
        } else if (TacticalMoves.executeMoveOfBlockingUnit(ctx, blocking)) together(civilianMove, "blocking unit made room")
        else {
            ctx.log { "retargeting civilian escort, could not move blocking unit" }
            finish(ctx, civilian, escort)
        }
    }
    //endregion
}
