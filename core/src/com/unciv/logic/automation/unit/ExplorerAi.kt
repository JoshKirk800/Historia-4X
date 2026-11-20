package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.strategy.ExplorationPlotSets
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsExoticGoods
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import java.util.BitSet
import java.util.PriorityQueue

/**
 *  Vox Populi's explorer targeting (CvHomelandAI::GetBestExploreTarget, IsValidExplorerEndTurnPlot, EconomicAIHelpers::
 *  IsHighValueExploreTarget, ShouldExplorerAvoid, IsUnfriendlyTerritory), read by [HomelandAi]'s ExecuteExplorerMoves.
 *
 *  The danger of a plot to the unit is [PlotDanger.get] (CvUnit::GetDanger): it lowers the heal rate of the unit that stays, refuses an end-turn plot
 *  with more than half the unit's health in danger and lowers the score of one with any. Inputs that are none here: scouting XP and the
 *  RECON_ONLY_ANCIENT_RUINS rule (off in BNW's rules: every unit can pop a goody hut); units free of city-state anger. The reachable plots
 *  are [Reach] (no explore cost on the paths).
 */
object ExplorerAi {
    /** CvUnit::MOVE_DENOMINATOR (GD MOVE_DENOMINATOR): moves are counted in 60ths */
    const val MOVE_DENOMINATOR = 60
    private const val RECON = "UNITCOMBAT_RECON"
    /** max(1, EMBARKED_VISIBILITY_RANGE (0 in BNW) + embark extra visibility (none in BNW)) */
    private const val EMBARKED_SIGHT = 1
    /** GetBestExploreTarget: each point of danger on a plot where the unit ends its turn with a move or less left costs this much score */
    private const val DANGER_SCORE_WEIGHT = 10

    /** A plot a unit reaches (ReachablePlot): within [pathLength] turns (1: this turn), with [movesLeft] 60ths of a move left there */
    class ReachablePlot(val tile: Tile, val pathLength: Int, val movesLeft: Int)

    /**
     *  CvPathFinder::GetPlotsInReach with MOVEFLAG_NO_ENEMY_TERRITORY for [unit], searched as far as [within] asks: one search by turns,
     *  then by moves left, over Unciv's movement costs (an unrevealed plot costs 1); a move may start with any movement left (all of it
     *  is spent when it costs more), a turn ends where the unit can stop and the next starts there with full movement; never through the
     *  territory of a civilization at war with it. VP's MAXIMIZE_EXPLORE cost, which prefers paths through unexplored sight, is not ported.
     *  The builders' flags (CvHomelandAI::ExecuteWorkerMoves: MOVEFLAG_AI_ABORT_IN_DANGER | MOVEFLAG_VISIBLE_ONLY):
     *  [visibleOnly] lets the unit enter a plot we do not see only from one we see (CvAStar.cpp: PathValid, "run through one unseen tile at the time");
     *  [abortInDanger] refuses a plot where the unit ends one of its first two turns and its danger ([PlotDanger.get] for the unit, CvUnit::GetDanger) is more
     *  than half its health (CvAStar.cpp: PathEndTurnCost, for a civilian, embarked or exploring unit; the costs it adds for lesser danger are not ported).
     */
    class Reach(private val unit: MapUnit, private val abortInDanger: Boolean = false, private val visibleOnly: Boolean = false) {
        private class Node(val tile: Tile, val turns: Int, val moves: Float)

        private val settled = LinkedHashMap<Tile, ReachablePlot>()
        private val bestSeen = HashMap<Tile, Node>()
        private val queue = PriorityQueue<Node>(compareBy<Node> { it.turns }.thenByDescending { it.moves })
        private val fullMovement = unit.getMaxMovement().toFloat()
        private val enemyTerritory: BitSet = BitSet().apply {
            for (tile in unit.civ.gameInfo.tileMap.values) {
                val owner = tile.getOwner()
                if (owner != null && unit.civ.isAtWarWith(owner)) set(tile.zeroBasedIndex)
            }
        }

        init { push(Node(unit.getTile(), 1, unit.currentMovement)) }

        private fun tooDangerous(tile: Tile) = unit.civ.ai.plotDanger.get(unit, tile) > unit.health / 2

        fun within(maxTurns: Int): List<ReachablePlot> {
            while (queue.isNotEmpty() && queue.peek().turns <= maxTurns) settle(queue.poll())
            return settled.values.filter { it.pathLength <= maxTurns }
        }

        private fun push(node: Node) {
            if (node.tile in settled) return
            val seen = bestSeen[node.tile]
            if (seen != null && (seen.turns < node.turns || seen.turns == node.turns && seen.moves >= node.moves)) return
            bestSeen[node.tile] = node
            queue.add(node)
        }

        private fun settle(node: Node) {
            val tile = node.tile
            if (tile in settled || bestSeen[tile] !== node) return
            settled[tile] = ReachablePlot(tile, node.turns, (node.moves * MOVE_DENOMINATOR).toInt().coerceAtLeast(0))
            val canStop = tile == unit.getTile() || unit.movement.canMoveTo(tile)
            for (neighbor in tile.neighbors) {
                if (neighbor in settled || enemyTerritory.get(neighbor.zeroBasedIndex)) continue
                val explored = unit.civ.hasExplored(neighbor)
                if (explored && !unit.movement.canPassThrough(neighbor)) continue
                if (visibleOnly && neighbor !in unit.civ.viewableTiles && tile !in unit.civ.viewableTiles) continue
                val cost = if (!explored) 1f else MovementCost.getMovementCostBetweenAdjacentTiles(unit, tile, neighbor)
                if (node.moves > Constants.minimumMovementEpsilon) {
                    val left = (node.moves - cost).coerceAtLeast(0f)
                    // Ending the turn here, within the first two turns
                    if (abortInDanger && node.turns <= 2 && left <= Constants.minimumMovementEpsilon && tooDangerous(neighbor)) continue
                    push(Node(neighbor, node.turns, left))
                } else if (canStop) {
                    val left = (fullMovement - cost).coerceAtLeast(0f)
                    if (abortInDanger && node.turns + 1 <= 2 && left <= Constants.minimumMovementEpsilon && tooDangerous(neighbor)) continue
                    push(Node(neighbor, node.turns + 1, left))
                }
            }
        }
    }

    /** Units.CombatClass UNITCOMBAT_RECON (the Scouts) */
    fun isRecon(unit: MapUnit): Boolean = unit.civ.gameInfo.ruleset.ai.unitAiTypesOf(unit.baseUnit.name)?.combatClass == RECON

    /**
     *  Whether [unit] explores only near its cities and no further than 5 turns: an AI land unit that is not Recon (GetBestExploreTarget,
     *  IsValidExplorerEndTurnPlot: "AI controlled non-explorers should not explore too far").
     *
     *  Deviation: only while the civilization has a Recon unit exploring. Vox Populi leaves the far exploring to the Recon unit every
     *  civilization starts with (EraChanges.sql: Ancient era StartingExploreUnits 1); Brave New World's start has none, so the
     *  free military unit that CvPlayer::initFreeUnits sets to explore is the only explorer until a Scout is trained, and kept at home
     *  the civilizations met nobody (met pairs at turn 25: 12 before the explorer port, 0 with it).
     */
    fun limitedToHome(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (!unit.baseUnit.isLandUnit || civ.isHuman() || isRecon(unit)) return false
        return civ.units.getCivUnits().any { it.baseUnit.isLandUnit && isRecon(it) && UnitAiTypes.current(it) == UnitAiType.EXPLORE }
    }

    /**
     *  CvHomelandAI::GetBestExploreTarget: the plot within [maxTurns] where [unit] best ends a turn exploring, null if none (an AI land
     *  unit that is [limitedToHome] looks no further than 5 turns).
     *  - a goody hut or empty barbarian camp ([isHighValueExploreTarget]) not next to an unseen camp: the nearest such (turns x 600 - moves
     *    left + the other explorers' penalty) wins over everything;
     *  - else, by score: the plot's exploration value ([ExplorationPlotSets.valueFor]) with the unit's sight there (a land unit: 1 on
     *    water, its land sight on land), +500 / +250 where exotic goods sell at 75% / 50% of the reward; a plot with no unrevealed
     *    neighbour must score more than 40 (sight below 3) or 100 (ships that cannot stay in the ocean always count); then
     *    + [ExplorationPlotSets.extraExploreValue] - the other explorers' penalty + 2 x moves left - 2 x turns x max moves; halved, or
     *    skipped with no move left, in the territory of a city-state that would mind ([isUnfriendlyTerritory]) when less than 2 moves are left;
     *  - staying to heal, when the unit has not moved, looks at most 3 turns ahead and heals 7 or more (or any with more than 30 damage):
     *    40 x the heal.
     *  The other explorers (our Scouts exploring; for a human, units on Explore) put 1000 / distance on every plot near them or their
     *  destination, and their plots are skipped.
     */
    fun bestExploreTarget(unit: MapUnit, maxTurns: Int, reach: Reach, plots: ExplorationPlotSets): Tile? {
        val civ = unit.civ
        val human = civ.isHuman()
        if (limitedToHome(unit) && maxTurns > 5) return null

        val others = civ.units.getCivUnits().filter { other ->
            other != unit && if (human) other.isExploring()
            else (UnitAiTypes.current(other) == UnitAiType.EXPLORE || UnitAiTypes.current(other) == UnitAiType.EXPLORE_SEA) && isRecon(other)
        }.map { if (it.isMoving()) it.getMovementDestination() else it.getTile() }

        val reachable = reach.within(maxTurns)
        if (reachable.isEmpty()) return null
        val danger = civ.ai.plotDanger
        val sight = unit.getVisibilityRange()
        val landSight = if (unit.isEmbarked()) 2 else sight
        val maxMoves = unit.getMaxMovement() * MOVE_DENOMINATOR

        var bestHighValue: Tile? = null
        var bestHighValueScore = Int.MAX_VALUE
        var best: Tile? = null
        var bestScore = Int.MIN_VALUE
        for (plot in reachable) {
            val tile = plot.tile
            if (tile == unit.getTile()) {
                if (isUnfriendlyTerritory(civ, tile)) continue
                if (unit.currentMovement >= unit.getMaxMovement() && maxTurns <= 3 && !human) {
                    val damage = 100 - unit.health
                    val healRate = minOf(unit.getHealAmountForCurrentTile(), damage) - danger.get(unit, tile)
                    if ((healRate >= 7 || healRate > 0 && damage > 30) && healRate * 40 > bestScore) {
                        best = tile
                        bestScore = healRate * 40
                    }
                }
                continue
            }
            if (!isValidExplorerEndTurnPlot(unit, tile)) continue

            var nearbyPenalty = 0
            if (others.any { it == tile }) continue
            for (other in others) nearbyPenalty += 1000 / other.aerialDistanceTo(tile)

            if (isHighValueExploreTarget(civ, tile) && !shouldExplorerAvoid(civ, tile)) {
                val score = plot.pathLength * 10 * MOVE_DENOMINATOR - plot.movesLeft + nearbyPenalty
                if (score < bestHighValueScore) {
                    bestHighValue = tile
                    bestHighValueScore = score
                }
                continue
            } else if (bestHighValue != null) continue

            val plotSight = when {
                !unit.baseUnit.isLandUnit -> sight
                tile.isWater -> EMBARKED_SIGHT
                else -> landSight
            }
            var plotScore = plots.valueFor(unit, tile, plotSight)
            if (unit.hasUnique(UniqueType.SellExoticGoods) && UnitActionsExoticGoods.canSell(unit, tile)) {
                val factor = UnitActionsExoticGoods.distanceFactor(unit, tile)
                if (factor >= 0.75f) plotScore += 500 else if (factor >= 0.5f) plotScore += 250
            }
            var minThreshold = 0
            if ((!unit.baseUnit.isWaterUnit || ExplorationPlotSets.canStayInOcean(unit)) && tile.neighbors.all { civ.hasExplored(it) })
                minThreshold = if (plotSight < 3) 40 else 100
            if (plotScore <= minThreshold) continue
            plotScore += ExplorationPlotSets.extraExploreValue(civ, tile, plotSight)

            var totalScore = plotScore - nearbyPenalty + plot.movesLeft * 2 - plot.pathLength * maxMoves * 2
            // Careful with plots that are too dangerous
            if (plot.movesLeft <= MOVE_DENOMINATOR) {
                val plotDanger = danger.get(unit, tile)
                if (plotDanger > unit.health / 2) continue
                if (plotDanger > 0) totalScore -= plotDanger * DANGER_SCORE_WEIGHT
            }
            if (plot.movesLeft < 2 * MOVE_DENOMINATOR && isUnfriendlyTerritory(civ, tile)) {
                if (plot.movesLeft == 0) continue
                totalScore /= 2
            }
            if (totalScore > bestScore) {
                best = tile
                bestScore = totalScore
            }
        }
        return bestHighValue ?: best
    }

    /**
     *  CvHomelandAI::IsValidExplorerEndTurnPlot: not the unit's own plot; revealed; water only for a land unit that can embark at all,
     *  water only for ships; not territory it may not stay in or of a civilization at war with it; not an unseen barbarian camp; for an AI
     *  land unit that is [limitedToHome], within 6 plots of one of our cities on its landmass; no visible enemy unit; a plot it can move to.
     *  (VP lets it end on a lone enemy civilian to capture it; capturing needs an attack here.)
     */
    fun isValidExplorerEndTurnPlot(unit: MapUnit, tile: Tile): Boolean {
        val civ = unit.civ
        if (unit.getTile() == tile || !civ.hasExplored(tile)) return false
        if (unit.baseUnit.isLandUnit && unit.cache.cannotEmbark && tile.isWater) return false
        if (unit.baseUnit.isWaterUnit && !tile.isWater) return false
        val owner = tile.getOwner()
        if (owner != null && owner != civ && (civ.isAtWarWith(owner) || !civ.diplomacyFunctions.canPassThroughTiles(owner))) return false
        if (tile.isBarbarianEncampment() && !tile.isVisible(civ)) return false
        if (limitedToHome(unit)
            && civ.cities.none { it.getCenterTile().getContinent() == tile.getContinent() && it.getCenterTile().aerialDistanceTo(tile) <= 6 })
            return false
        if (tile.isVisible(civ) && tile.getUnits().any { it.civ.isAtWarWith(civ) }) return false
        return unit.movement.canMoveTo(tile)
    }

    /**
     *  EconomicAIHelpers::IsHighValueExploreTarget: a goody hut (ancient ruins) without a visible enemy unit, or a visible barbarian camp
     *  with no defender
     */
    fun isHighValueExploreTarget(civ: Civilization, tile: Tile): Boolean {
        val improvement = tile.improvement?.let { civ.gameInfo.ruleset.tileImprovements[it] }
        if (improvement?.isAncientRuinsEquivalent() == true && !(tile.isVisible(civ) && tile.getUnits().any { it.civ.isAtWarWith(civ) }))
            return true
        return tile.isBarbarianEncampment() && tile.isVisible(civ) && tile.militaryUnit == null
    }

    /** EconomicAIHelpers::ShouldExplorerAvoid: a barbarian camp next to the plot and the plot not visible */
    fun shouldExplorerAvoid(civ: Civilization, tile: Tile): Boolean =
        !tile.isVisible(civ) && tile.neighbors.any { it.isBarbarianEncampment() && civ.hasExplored(it) }

    /** IsUnfriendlyTerritory (CvHomelandAI.cpp): for a major, the territory of a city-state it has met and is not friends with (VP's open borders) */
    fun isUnfriendlyTerritory(civ: Civilization, tile: Tile): Boolean {
        val owner = tile.getOwner() ?: return false
        if (owner == civ || !owner.isCityState || !civ.isMajorCiv || !civ.knows(owner)) return false
        return owner.getDiplomacyManager(civ)?.isRelationshipLevelGE(RelationshipLevel.Friend) != true
    }
}
