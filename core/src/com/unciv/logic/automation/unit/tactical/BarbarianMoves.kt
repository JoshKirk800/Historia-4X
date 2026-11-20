package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.max

/**
 *  The barbarian-only moves of CvTacticalAI (Vox Populi, Release-5.4.8): PlotBarbarianCampDefense ([plotCampDefenseMoves]) and PlotBarbarianRoaming /
 *  ExecuteBarbarianRoaming with FindBestBarbarianLandTarget, FindBestBarbarianSeaTarget, FindNearbyTarget and FindBarbarianExploreTarget
 *  ([plotRoamingMoves]). The attack moves barbarians share with the other players are [TacticalMoves] and [TacticalSimulation];
 *  [com.unciv.logic.automation.civilization.BarbarianAutomation] orders them all as AssignBarbarianMoves does. Brave New World's PlotBarbarianMove
 *  with its land, passive and sea moves, trade route gank target and the escort of captured civilians to a camp are gone: Vox Populi's roaming
 *  sends the captured civilians back to a camp itself.
 *
 *  Deviations: the sea the ships reach is a flood at one movement point a tile that ignores units on the tiles; the path to a roaming target is not asked to avoid danger (AI_ABORT_IN_DANGER) or
 *  the unit's own stack (IGNORE_STACKING_SELF); a camp as a roaming target is an improvement that is entered but cannot be pillaged by its own
 *  owner (the original's PUSH_MISSION(PILLAGE) fails there, as [PillageRules.pillage] does), and a city to defend (the target of a barbarian's own
 *  city) is not an improvement, so nothing moves the unit there, as in the original; barbarians do not upgrade units (the original has no such
 *  move for them).
 */
object BarbarianMoves {

    /** m_iLandBarbarianRange: HandicapInfos.BarbarianLandTargetRange of the game's difficulty (the AI default handicap's 5 for a ruleset without the column), at least 1 */
    fun landRange(civ: Civilization): Int =
        max(1, civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, "BarbarianLandTargetRange") ?: DEFAULT_LAND_RANGE)

    /** m_iSeaBarbarianRange: HandicapInfos.BarbarianSeaTargetRange of the game's difficulty (10 by default), at least 1 */
    fun seaRange(civ: Civilization): Int =
        max(1, civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, "BarbarianSeaTargetRange") ?: DEFAULT_SEA_RANGE)

    private const val DEFAULT_LAND_RANGE = 5
    private const val DEFAULT_SEA_RANGE = 10

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

    //region Roaming

    /**
     *  CvTacticalAI::PlotBarbarianRoaming / ExecuteBarbarianRoaming: every barbarian unit that can still move heads for a target far away, after the
     *  attacks and the pillaging; the captured civilians too (they go back to a camp). A unit that ends its turn here is commanded, one that still
     *  can move is left to the moves to safety.
     *
     *  A land unit in a camp or a city stays. Else it goes for [findBestLandTarget]: a civilian or an improvement that no defender protects is
     *  entered, and pillaged if the unit has movement left there; towards a defended unit it goes to an empty tile next to it (within 12 turns), unless
     *  it is next to it already. A ship goes to an empty tile near its [findBestSeaTarget] the same way, attacks what it can reach from there and is
     *  done; there is no naval pillaging.
     */
    fun plotRoamingMoves(ctx: TacticalContext) {
        for (unit in ctx.liveUnits()) {
            if (unit.baseUnit.isAirUnit() || !TacticalAi.canUseForTacticalAi(unit, ctx.cache)) continue
            if (unit.baseUnit.isWaterUnit) roamAtSea(ctx, unit) else roamOnLand(ctx, unit)
        }
    }

    private fun roamOnLand(ctx: TacticalContext, unit: MapUnit) {
        val here = unit.getTile()
        if (here.isBarbarianEncampment() || here.isCityCenter()) {
            ctx.unitProcessed(unit)
            return
        }
        // Where to?
        val best = timeThis("AI.Barbarians.LandTarget") { findBestLandTarget(ctx, unit) } ?: return
        val isCombat = TacticalTargets.visibleEnemyDefender(ctx.civ, best) != null
        val isCivilian = hasVisibleCivilian(ctx, best)
        val isImprovement = best.improvement != null && !best.improvementIsPillaged
        unit.movement.clearPathfindingCache()
        ctx.log { "roaming: ${unit.name} at ${here.position} towards ${best.position}" }
        // Just move in if we can
        if ((isCivilian || isImprovement) && !isCombat) {
            timeThis("AI.Barbarians.RoamMove") { Deployment.headTowards(unit, best) }
            if (!unit.isDestroyed && unit.hasMovement() && unit.getTile() == best) PillageRules.pillage(unit)
        }
        // Move towards the target but do not hang around there; since we did not attack this turn, we continue roaming
        else if (isCombat && best.aerialDistanceTo(here) > 1)
            timeThis("AI.Barbarians.RoamMove") { EscortMoves.moveToEmptySpaceNearTarget(ctx, unit, best, false, ROAM_TURNS, fewCandidates = true) }
        // Hit and run, if you can
        if (!unit.isDestroyed && !unit.hasMovement()) ctx.unitProcessed(unit)
    }

    private fun roamAtSea(ctx: TacticalContext, unit: MapUnit) {
        val best = timeThis("AI.Barbarians.SeaTarget") { findBestSeaTarget(ctx, unit) } ?: return
        // No naval pillaging, it is just too annoying; as on land, a ship that is at its target does not end the turn but moves to safety
        val isCombat = TacticalTargets.visibleEnemyDefender(ctx.civ, best) != null
        if (isCombat && best.aerialDistanceTo(unit.getTile()) <= 1) return
        unit.movement.clearPathfindingCache()
        if (!timeThis("AI.Barbarians.RoamMove") { EscortMoves.moveToEmptySpaceNearTarget(ctx, unit, best, true, ROAM_TURNS, fewCandidates = true) }) return
        ctx.log { "roaming: ${unit.name} sails towards ${best.position}, now at ${unit.getTile().position}" }
        if (!unit.isDestroyed) OpportunityAttacks.perform(ctx, unit, true)
        if (!unit.isDestroyed) ctx.unitProcessed(unit)
    }

    /** CvPlot::isEnemyUnit(barbarians, not combat, visible): a unit of a player at war with us that is seen and cannot defend itself */
    private fun hasVisibleCivilian(ctx: TacticalContext, tile: Tile): Boolean =
        tile.getUnits().any { it.civ != ctx.civ && ctx.civ.isAtWarWith(it.civ) && it.isVisibleTo(ctx.civ) && !TacticalSafety.canDefend(it) }

    /**
     *  CvTacticalAI::FindBestBarbarianLandTarget: a unit that can defend itself looks at the offensive targets within the land target range
     *  ([findNearbyTarget]) and else explores ([findExploreTarget]); by default, and for the civilians (within 23 turns), it goes back to a camp.
     */
    fun findBestLandTarget(ctx: TacticalContext, unit: MapUnit): Tile? {
        var maxTurns = landRange(ctx.civ)
        val canDefend = TacticalSafety.canDefend(unit)
        var best: Tile? = null
        // Combat units look at all offensive targets within x turns
        if (canDefend) best = timeThis("AI.Barbarians.NearbyTarget") { findNearbyTarget(ctx, unit, maxTurns, true) } ?: timeThis("AI.Barbarians.Explore") { findExploreTarget(ctx, unit) }
        // By default go back to camp or so
        if (best == null) {
            if (!canDefend) maxTurns = CIVILIAN_ROAM_TURNS
            best = timeThis("AI.Barbarians.NearbyTarget") { findNearbyTarget(ctx, unit, maxTurns, false) }
        }
        return best
    }

    /**
     *  CvTacticalAI::FindNearbyTarget for a land unit: the target within [maxTurns] x 3 tiles that it gets to next to soonest by path, the nearest first
     *  (the tile it stands on first of all). Offensive: enemy units and cities (not where the unit would die attacking), improvements, trade units of its
     *  domain, civilians; else our camps (a barbarian's) and cities to defend. A tile a land unit would have to embark for is not one.
     *  The path is not asked to avoid danger or its own stack.
     */
    fun findNearbyTarget(ctx: TacticalContext, unit: MapUnit, maxTurns: Int, offensive: Boolean): Tile? {
        val here = unit.getTile()
        val maxDistance = maxTurns.toLong() * TILES_PER_TURN
        val candidates = ArrayList<Candidate>()
        val targets = ctx.targetLists.getOrPut(if (offensive) "roamOffensive" else "roamDefensive") { ctx.targets.all.filter { isRoamTarget(ctx, it, offensive) } }
        for (target in targets) {
            // Whether the target is of a type that matches, and whether the unit must also not die attacking it: that is worked out only for the
            // targets that pass the cheap tests below, and in the order of the distance, where the answer is needed (same result as testing them all)
            val suicideTest: Boolean
            if (offensive) when (target.type) {
                TacticalTargetType.LowPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.HighPriorityUnit, TacticalTargetType.City ->
                    suicideTest = true
                TacticalTargetType.Improvement, TacticalTargetType.ImprovementResource, TacticalTargetType.TradeUnitLand,
                TacticalTargetType.VeryHighPriorityCivilian, TacticalTargetType.HighPriorityCivilian, TacticalTargetType.MediumPriorityCivilian,
                TacticalTargetType.LowPriorityCivilian -> suicideTest = false
                else -> continue
            } else if (target.type == TacticalTargetType.CityToDefend || (ctx.civ.isBarbarian && target.type == TacticalTargetType.BarbarianCamp)) suicideTest = false
            else continue
            val tile = target.tile
            val distance = tile.aerialDistanceTo(here)
            if (distance > maxDistance) continue
            // Cannot do anything if we would need to embark
            if (unit.baseUnit.isLandUnit && tile.isWater && !unit.cache.canMoveOnWater) continue
            // Shortcut, may happen often
            if (tile == here) {
                if (suicideTest && isSuicideMeleeAttack(unit, tile)) continue
                return tile
            }
            candidates.add(Candidate(tile, distance, suicideTest))
        }
        // Second round: the nearest first (std::stable_sort, then the original's order for equal distances)
        unit.movement.clearPathfindingCache()
        for (candidate in candidates.sortedBy { it.distance }) {
            if (candidate.suicideTest && isSuicideMeleeAttack(unit, candidate.tile)) continue
            if (needsAnotherLandmass(unit, candidate.tile)) continue
            if (approachTurns(ctx, unit, candidate.tile, maxTurns) < Int.MAX_VALUE) return candidate.tile
        }
        return null
    }

    /** The target types FindNearbyTarget looks at: offensive (enemy units and cities, improvements, trade units and civilians) or defensive (camps of a barbarian, cities to defend) */
    private fun isRoamTarget(ctx: TacticalContext, target: TacticalTarget, offensive: Boolean) = if (offensive) when (target.type) {
        TacticalTargetType.LowPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.HighPriorityUnit, TacticalTargetType.City,
        TacticalTargetType.Improvement, TacticalTargetType.ImprovementResource, TacticalTargetType.TradeUnitLand,
        TacticalTargetType.VeryHighPriorityCivilian, TacticalTargetType.HighPriorityCivilian, TacticalTargetType.MediumPriorityCivilian,
        TacticalTargetType.LowPriorityCivilian -> true
        else -> false
    } else target.type == TacticalTargetType.CityToDefend || (ctx.civ.isBarbarian && target.type == TacticalTargetType.BarbarianCamp)

    /** Where [unit] stands, how long the tile takes to get next to, for the units of one type that stand together (they are many in a camp) */
    private data class ApproachKey(val unitName: String, val from: Tile, val target: Tile, val maxTurns: Int, val movement: Int)

    /** [EscortMoves.turnsToApproach] with few candidate tiles, remembered for the turn for units of one kind on one tile */
    private fun approachTurns(ctx: TacticalContext, unit: MapUnit, tile: Tile, maxTurns: Int): Int =
        ctx.memo.getOrPut(ApproachKey(unit.name, unit.getTile(), tile, maxTurns, unit.getMaxMovement())) {
            EscortMoves.turnsToApproach(ctx, unit, tile, maxTurns, fewCandidates = true)
        }

    /** A land unit on land cannot get to the land of another continent unless it can cross water: no path search needed */
    private fun needsAnotherLandmass(unit: MapUnit, tile: Tile): Boolean {
        val here = unit.getTile()
        return unit.baseUnit.isLandUnit && here.isLand && tile.isLand && here.getContinent() != tile.getContinent() &&
            !unit.civ.tech.unitsCanEmbark && !unit.cache.canMoveOnWater
    }

    private class Candidate(val tile: Tile, val distance: Int, val suicideTest: Boolean)

    /**
     *  TacticalAIHelpers::IsSuicideMeleeAttack: a melee unit (not one that shoots, not an aircraft) that would take at least what it has left, less 3 for
     *  luck, attacking the city or defender on [tile] from where it stands. No defender: no suicide.
     */
    private fun isSuicideMeleeAttack(unit: MapUnit, tile: Tile): Boolean {
        if (unit.baseUnit.isRanged() || unit.baseUnit.isAirUnit()) return false
        return timeThis("AI.Barbarians.Suicide") { wouldDieAttacking(unit, tile) }
    }

    private fun wouldDieAttacking(unit: MapUnit, tile: Tile): Boolean {
        val received = if (tile.isCityCenter()) {
            val city = tile.getCity() ?: return false
            BattleDamage.calculateDamageToAttacker(MapUnitCombatant(unit), CityCombatant(city), unit.getTile(), TacticalTargets.EXPECTED_RANDOMNESS, ignoreFlanking = true)
        } else {
            val defender = TacticalTargets.visibleEnemyDefender(unit.civ, tile) ?: return false
            SimWorld.damageOnUnit(unit, unit.getTile(), unit.health, defender, tile, defender.health, ignoreFlanking = false).second
        }
        // Add some margin for randomness
        return received + SUICIDE_MARGIN >= unit.health
    }

    /**
     *  CvTacticalAI::FindBestBarbarianSeaTarget: one flood of the sea the ship reaches within the sea target range in turns ([seaReach], the original's
     *  GetPlotsInReach), then the enemy unit (of the targets, in their order) whose plot the ship reaches soonest; else, to patrol, the water tile next to
     *  the second nearest barbarian camp that the sea touches (the nearest by distance is left out) that it reaches soonest; else a tile to explore
     *  ([findExploreTarget]). A target counts when the ship can get to the tile itself or to a tile next to it (an enemy ship holds its own), the first of
     *  equals wins.
     */
    fun findBestSeaTarget(ctx: TacticalContext, unit: MapUnit): Tile? {
        val unitTile = unit.getTile()
        val reach = seaReach(ctx, unit, seaRange(ctx.civ))
        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        // Loop through all unit targets to find the closest
        for (target in ctx.targets.all) {
            if (!target.isUnitTarget) continue
            val turns = reach.turnsNextTo(target.tile)
            if (turns < bestTurns) { bestTurns = turns; best = target.tile }
        }
        if (best != null) return best

        // No units to pick on, so sail to a tile adjacent to the second closest barbarian camp: this should result in patrolling behavior
        val camps = TacticalTargets.ofType(ctx.targets.all, TacticalTargetType.BarbarianCamp).filter { camp -> camp.tile.neighbors.any { isShallowWater(it) } }
        val nearestCamp = camps.minByOrNull { unitTile.aerialDistanceTo(it.tile) }
        for (camp in camps) {
            if (camp === nearestCamp) continue
            for (water in camp.tile.neighbors) {
                if (!isShallowWater(water)) continue
                val turns = reach.turnsTo(water)
                if (turns < bestTurns) { bestTurns = turns; best = water }
            }
        }
        return best ?: findExploreTarget(ctx, unit)
    }

    /** The turns (0: this turn) a ship needs to get to each tile of the sea it can reach within a range, [Int.MAX_VALUE] for the others */
    private class SeaReach(private val turns: IntArray) {
        fun turnsTo(tile: Tile): Int = turns[tile.zeroBasedIndex].let { if (it < 0) Int.MAX_VALUE else it }

        /** To the tile or to a tile next to it */
        fun turnsNextTo(tile: Tile): Int {
            var best = turnsTo(tile)
            for (neighbor in tile.neighbors) best = minOf(best, turnsTo(neighbor))
            return best
        }
    }

    private data class SeaReachKey(val unitName: String, val from: Tile, val movement: Int, val range: Int)

    /**
     *  CvTacticalAI's GetPlotsInReach for a ship: a flood over the water tiles the ship may enter (not impassable ones, nor ocean for a ship that cannot
     *  enter it) at one movement point a tile, as the sea costs; the tiles a ship reaches in [range] turns (its movement a turn) have their turn count.
     *  Other units on the tiles are not considered. One flood is made for the ships of a kind on one tile for the turn.
     */
    private fun seaReach(ctx: TacticalContext, unit: MapUnit, range: Int): SeaReach {
        val movement = unit.getMaxMovement().coerceAtLeast(1)
        val key = SeaReachKey(unit.name, unit.getTile(), movement, range)
        val cached = ctx.tables[key]
        if (cached is SeaReach) return cached
        val turns = IntArray(unit.civ.gameInfo.tileMap.tileList.size) { -1 }
        val steps = IntArray(turns.size) { -1 }
        val maxSteps = (range.toLong() + 1) * movement
        val canEnterOcean = !unit.cache.cannotEnterOceanTiles || unit.civ.tech.allUnitsCanEnterOcean
        val start = unit.getTile()
        steps[start.zeroBasedIndex] = 0
        turns[start.zeroBasedIndex] = 0
        var frontier = arrayListOf(start)
        var distance = 0
        while (frontier.isNotEmpty() && distance < maxSteps) {
            distance++
            val next = ArrayList<Tile>()
            for (tile in frontier) for (neighbor in tile.neighbors) {
                val index = neighbor.zeroBasedIndex
                if (steps[index] >= 0 || !neighbor.isWater || neighbor.isImpassible() || neighbor.isOcean && !canEnterOcean) continue
                steps[index] = distance
                turns[index] = (distance + movement - 1) / movement - 1
                next.add(neighbor)
            }
            frontier = next
        }
        // Reaching it takes more turns than the range: not a reachable tile
        for (index in turns.indices) if (turns[index] > range) turns[index] = -1
        return SeaReach(turns).also { ctx.tables[key] = it }
    }

    /** CvPlot::isAdjacentToShallowWater is asked of the camp: a water tile that is not deep ocean */
    private fun isShallowWater(tile: Tile) = tile.isWater && !tile.isOcean

    /**
     *  CvTacticalAI::FindBarbarianExploreTarget (Vox Populi): of the plots the unit reaches this turn without embarking, not its own,
     *  revealed and without a city, and not more dangerous than the unit has hit points, the one with the most around it (the plot and its neighbours): 3 for each not revealed, 2 for each
     *  revealed one that is owned and not visible; +100 for a plot of its own domain while embarked. The first best wins; none with
     *  nothing around it.
     */
    fun findExploreTarget(ctx: TacticalContext, unit: MapUnit): Tile? {
        val civ = ctx.civ
        val danger = civ.ai.plotDanger
        val unitTile = unit.getTile()
        var best: Tile? = null
        var bestValue = 0
        for (tile in unit.movement.getDistanceToTiles().keys) {
            if (tile == unitTile || !civ.hasExplored(tile) || tile.isCityCenter()) continue
            val nativeDomain = if (unit.baseUnit.isWaterUnit) tile.isWater else tile.isLand
            if (!nativeDomain && !unit.isEmbarked()) continue
            var value = 0
            for (around in tile.neighbors + tile) {
                if (!civ.hasExplored(around)) value += 3
                else if (!around.isVisible(civ) && around.getOwner() != null) value += 2
            }
            if (unit.isEmbarked() && nativeDomain) value += 100
            // The costly tests (the path, the danger of every enemy) only for a plot that would be the best: they only ever drop a plot
            if (value <= bestValue || !unit.movement.canMoveTo(tile)) continue
            // Even barbarians consider danger sometimes
            if (danger.get(unit, tile) > unit.health) continue
            best = tile
            bestValue = value
        }
        return best
    }

    private const val ROAM_TURNS = 12
    private const val CIVILIAN_ROAM_TURNS = 23
    private const val TILES_PER_TURN = 3
    private const val SUICIDE_MARGIN = 3
    //endregion
}
