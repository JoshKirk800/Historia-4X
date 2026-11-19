package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

/** One run of the search (the result of FindBestUnitAssignments): the plan, the units that spoiled it if there is none, and what it was made from */
class SimResult(val world: SimWorld, val assignments: List<SimAssignment>, val unusable: Set<MapUnit>, val completed: Int, val used: Int) {
    val isEmpty get() = assignments.isEmpty()
}

/** SComboMove (CvTacticalAI.h): one or two assignments of different units that are added together */
private class Combo(val a: SimAssignment) {
    var b: SimAssignment? = null
    val score get() = a.totalScore + (b?.totalScore ?: 0)
    fun add(move: SimAssignment): Boolean {
        if (b == null && a.unit != move.unit) { b = move; return true }
        return false
    }
}

/**
 *  The search over plans: CvTacticalAI.cpp's FindBestUnitAssignments (the loop over positions, breadth first for the first generations, then
 *  depth first), CvTacticalPosition::makeNextAssignments (children from the best moves of every unit, with swaps and chains where a unit is in
 *  the way), getPreferredAssignmentsForUnit (the legal moves, melee and ranged attacks, pillaging, staying, always blocked as the way out),
 *  dropSuperfluousUnits, isUnique (positions that only differ by the order of moves are dropped) and the choice of the best completed position.
 *  Deterministic: units come by id, plots by index, ties by the order of creation.
 *
 *  Deviations: the position ids that break ties are a running count (the original encodes the tree in a number that saturates); storage
 *  holds [MAX_POSITIONS] positions as the original; a position that revealed an enemy is complete, see [SimPosition].
 */
object TacticalSimSearch {

    /** TACTSIM_UNIQUENESS_CHECK_GENERATIONS: higher means check more siblings for permutations */
    private const val UNIQUENESS_CHECK_GENERATIONS = 3
    /** TACTSIM_BREADTH_FIRST_GENERATIONS: switch to depth first later */
    private const val BREADTH_FIRST_GENERATIONS = 3
    /** TACTSIM_MAX_UNITS: we have limited storage and time */
    private const val MAX_UNITS = 13
    /** The positions the original has room for */
    private const val MAX_POSITIONS = 6000

    /**
     *  FindBestUnitAssignments: the best combination of unit actions (move, attack, pillage...) that does maximal damage to the enemy while
     *  exposing us to minimal risk. [units] are used in id order; [target] is where the fight is.
     *  @param targetDistanceRelevant the units try to come closer to the target; @param returnToStart units end where they started (opportunity attacks);
     *  @param saveMovement movement the units must have left at the end
     */
    fun findBestUnitAssignments(
        ctx: TacticalContext, units: List<MapUnit>, target: Tile, aggression: AggressionLevel,
        targetDistanceRelevant: Boolean, returnToStart: Boolean = false, saveMovement: Float = 0f,
    ): SimResult {
        val ordered = units.distinctBy { it.id }.sortedBy { it.id }
        val world = SimWorld(ctx, target, ordered, aggression, targetDistanceRelevant, returnToStart, saveMovement)
        if (world.units.isEmpty()) return SimResult(world, emptyList(), emptySet(), 0, 0)
        val scoring = SimScoring(world)
        val search = Search(world, scoring)
        return search.run()
    }

    private class Search(val w: SimWorld, val scoring: SimScoring) {
        private var nextId = 2L
        private var positionCount = 1
        private val badUnits = LinkedHashMap<Int, Int>()
        private val root = SimPosition.root(w)

        fun run(): SimResult {
            root.startUnits(scoring)
            computeSafePlotCounts()
            dropSuperfluousUnits(root, MAX_UNITS)

            val completed = ArrayList<SimPosition>()
            var depthFirst = false
            var open = PriorityQueue(heapOrder(false)).also { it.add(root) }
            var used = 0
            while (open.isNotEmpty()) {
                val current = open.peek()
                // Initially breadth first, later depth first
                if (!depthFirst && current.generation > BREADTH_FIRST_GENERATIONS) {
                    depthFirst = true
                    val reordered = PriorityQueue(heapOrder(true))
                    reordered.addAll(open)
                    open = reordered
                    continue
                }
                open.poll()
                // Just pick the best move in depth first mode, allowing two moves per unit in case one is invalid
                makeNextAssignments(current, if (depthFirst) 1 else w.maxBranches, if (depthFirst) 2 else w.maxChoicesPerUnit, depthFirst, open, completed)
                used += current.children.size
                // At some point we have seen enough good positions to pick one
                if (completed.size > w.maxCompletedPositions) break
                // Out of resources: this typically happens when there are only invalid positions to be found
                if (positionCount >= MAX_POSITIONS) break
            }

            if (completed.isEmpty()) {
                // Bad but maybe recoverable: try again without the problematic units
                val unusable = badUnits.keys.mapTo(LinkedHashSet()) { w.units[it].unit }
                return SimResult(w, emptyList(), unusable, 0, used)
            }
            // The best one: highest total score, fewer assignments, then the older position
            completed.sortWith(compareByDescending<SimPosition> { it.totalScore }.thenBy { it.assignments.size }.thenBy { it.id })
            return SimResult(w, completed.first().assignments.toList(), emptySet(), completed.size, used)
        }

        /** PrPositionSortHeapGeneration: within a generation the best last round first, between generations the oldest (breadth) or the newest (depth) first */
        private fun heapOrder(depthFirst: Boolean) = Comparator<SimPosition> { l, r ->
            if (l.generation == r.generation) {
                if (l.scoreOverParent != r.scoreOverParent) r.scoreOverParent.compareTo(l.scoreOverParent) else l.id.compareTo(r.id)
            } else if (depthFirst) r.generation.compareTo(l.generation) else l.generation.compareTo(r.generation)
        }

        /** The units' safe plots, counted once at the start, on the plots they can reach (updateMoveAndAttackPlotsForUnit) */
        private fun computeSafePlotCounts() {
            val damage = root.damage
            for (index in w.units.indices) {
                val unit = w.units[index].unit
                val reach = w.reach(index, root.plot[index], root.moves[index], root.freed)
                var count = 0
                for (plot in reach.plots) if (w.danger(index, w.plots[plot], 0, damage) < unit.health) count++
                for (tile in reach.outside) if (w.danger(index, tile, 0, damage) < unit.health) count++
                w.safePlotCount[index] = count
            }
        }

        //region Preferred assignments
        private fun tempUnit(unit: SimUnitStats, assignment: SimAssignment): SimUnitStats {
            val copy = unit.copy()
            copy.selfDamage += assignment.selfDamage
            copy.moves = assignment.remainingMoves
            return copy
        }

        /**
         *  getPreferredAssignmentsForUnit: the best [maxCount] moves of a unit in [pos], best first. A move can be good or bad depending on what the
         *  other units end up doing: only moves that seem safe to dare are returned. There is always the "blocked" move last in the list.
         */
        fun preferredAssignments(pos: SimPosition, unit: Int, maxCount0: Int): List<SimAssignment> {
            var maxCount = maxCount0
            val stats = pos.stats(unit)
            val simUnit = w.units[unit]
            val mu = simUnit.unit
            val moves = ArrayList<SimAssignment>()
            val rangedAttacks = ArrayList<SimAssignment>()
            val damage = pos.damage
            val unitTile = w.plots[stats.plot]

            // Melee attacks on the enemies next to the unit; for a melee attack the unit first moves to a plot next to the enemy
            if (!simUnit.ranged) {
                for (enemyPlot in w.neighbors[stats.plot]) {
                    if (!pos.isEnemyPlot(enemyPlot)) continue
                    val attack = scoring.scorePlotForMeleeAttack(pos, stats, enemyPlot, damage)
                    if (!attack.isAcceptable) continue
                    val overlay = pos.damageWith(attack)
                    val temp = tempUnit(stats, attack)
                    // Where would we end the turn? Are we advancing or not?
                    val newPlot = if (attack.type == AssignmentType.MeleeKill) enemyPlot else stats.plot
                    if (attack.remainingMoves <= 0f && !pos.canProbablyEndTurnAfter(scoring, temp, newPlot, attack.type, overlay)) continue
                    // Also consider which plot we end up in
                    attack.addScoreFrom(scoring.scorePlotForMove(pos, temp, newPlot, EvalMode.Intermediate, overlay))
                    moves.add(attack)
                }
            }

            val reach = w.reach(unit, stats.plot, stats.moves, pos.freed)
            val oldDistance = if (w.targetDistanceRelevant) max(w.distanceToTarget(unitTile, simUnit.domain), MAX_DISTANCE) else 0
            for (k in reach.plots.indices) {
                val testPlot = reach.plots[k]
                val testTile = w.plots[testPlot]
                if (testPlot == stats.plot) {
                    // Pillaging as an intermediate step
                    val pillage = scoring.scorePlotForPillage(pos, stats, testPlot)
                    if (pillage != null && pillage.totalScore > 0 && pillage.isAcceptable) {
                        val overlay = pos.damageWith(pillage)
                        val temp = tempUnit(stats, pillage)
                        if (pillage.remainingMoves > 0f || pos.canProbablyEndTurnAfter(scoring, temp, testPlot, pillage.type, overlay)) {
                            // Continue with the same plot, maybe in the final analysis the pillage is skipped because the movement is needed
                            pillage.addScoreFrom(scoring.scorePlotForMove(pos, temp, testPlot, EvalMode.Intermediate, overlay))
                            moves.add(pillage)
                        }
                    }
                    // Ranged attacks
                    if (simUnit.ranged && stats.attacks > 0 && canShootNow(stats, mu)) {
                        for (enemyPlot in w.rangeAttackPlots(unit, stats.plot)) {
                            if (!pos.isEnemyPlot(enemyPlot)) continue
                            val shot = scoring.scorePlotForRangedAttack(pos, stats, enemyPlot, damage)
                            if (shot.totalScore <= 0 || !shot.isAcceptable) continue
                            val overlay = pos.damageWith(shot)
                            val temp = tempUnit(stats, shot)
                            // A catch-22: we want to allow dangerous attacks because a kill makes the unit safe, but that leads to many impossible positions
                            // (canProbablyEndTurnAfter considers near kills for the danger)
                            if (shot.remainingMoves <= 0f && !pos.canProbablyEndTurnAfter(scoring, temp, testPlot, shot.type, overlay)) continue
                            // Discourage attacks on cities with units that are not siege units if the real target is something else
                            if (pos.isEnemyCity(enemyPlot) && w.target != w.plots[enemyPlot] && mu.getRange() > 1 &&
                                com.unciv.logic.automation.strategy.UnitAiTypes.current(mu) != com.unciv.logic.automation.strategy.UnitAiType.CITY_BOMBARD)
                                shot.setScore(0, shot.bonusScore / 2, shot.damageDelta / 2)
                            shot.addScoreFrom(scoring.scorePlotForMove(pos, temp, testPlot, EvalMode.Intermediate, overlay))
                            rangedAttacks.add(shot)
                        }
                    }
                    if (rangedAttacks.isEmpty()) {
                        // What is the score for simply staying put? Intermediate, so we are less strict about danger: enemies may be killed in the course of the plan
                        val stay = scoring.scorePlotForMove(pos, stats, testPlot, EvalMode.Intermediate, damage)
                        val temp = tempUnit(stats, stay)
                        // Doesn't matter if we have movement left in this case
                        if (pos.canProbablyEndTurnAfter(scoring, temp, testPlot, stay.type, damage)) moves.add(stay)
                    }
                } else {
                    // Moving to a different plot
                    if (stats.last == AssignmentType.Move) continue
                    // Make sure we have a chance to execute this move: skip it if a unit that has finished is in the way
                    if (pos.isSimUnitBlocking(testPlot, simUnit.domain)) continue
                    val previousWasSwap = stats.last == AssignmentType.MoveSwap || stats.last == AssignmentType.MoveSwapReverse
                    var towardsTarget = 0
                    if (w.targetDistanceRelevant) {
                        // Try to move towards the target
                        val newDistance = max(w.distanceToTarget(testTile, simUnit.domain), MAX_DISTANCE)
                        if (newDistance > oldDistance) continue
                        if (oldDistance != Int.MAX_VALUE) towardsTarget = (oldDistance - newDistance) * 40
                    }
                    val temp = stats.copy()
                    temp.moves = reach.moves[k]
                    val move = scoring.scorePlotForMove(pos, temp, testPlot, EvalMode.Intermediate, damage)
                    temp.selfDamage += move.selfDamage
                    temp.moves = move.remainingMoves
                    // If the last assignment was a move, only move again if another unit wants to swap us out
                    if (previousWasSwap) move.type = AssignmentType.MoveDouble

                    // The unit may not be able to end the turn here
                    val skirmish = simUnit.ranged && stats.attacks > 0 && move.remainingMoves > 1f && simUnit.canMoveAfterAttacking
                    val frontline = !simUnit.ranged && move.remainingMoves > 0f && stats.attacks > 0 && pos.enemyDistance(testPlot) == 1
                    val canStay = !skirmish && !frontline && pos.canProbablyEndTurnAfter(scoring, temp, testPlot, move.type, damage)
                    if (skirmish || frontline || canStay) {
                        move.addScore(towardsTarget, 0, 0)
                        moves.add(move)
                    }
                }
            }

            // With a lot of targets to attack the unit should probably retreat: make sure at least one move and not only attacks is considered, but keep one attack
            if (rangedAttacks.size >= maxCount && maxCount > 1) {
                val best = rangedAttacks.sortedByDescending { it.totalScore }.take(maxCount - 1)
                rangedAttacks.clear()
                rangedAttacks.addAll(best)
            }
            moves.addAll(rangedAttacks)
            // In sorted order: the unlikely bad moves are not filtered out
            val sorted = moves.sortedByDescending { it.totalScore }.toMutableList()
            // Not more than requested unless there is a tie
            if (sorted.size > maxCount) {
                while (maxCount < sorted.size && sorted[maxCount].totalScore == sorted[maxCount - 1].totalScore) maxCount++
                while (sorted.size > maxCount) sorted.removeAt(sorted.size - 1)
            }
            // Always the do-nothing move: it need not be picked but it allows move-attack-flee for damaged units
            val blocked = SimAssignment(AssignmentType.Blocked, unit, stats.plot, stats.plot, stats.moves, stats.strategy, pos.previousPlotScore(unit))
            blocked.setScore(-10, 0, 0)
            sorted.add(blocked)
            return sorted
        }

        /** A ranged unit needs a movement point to shoot, and one more to set up if it must */
        private fun canShootNow(stats: SimUnitStats, unit: MapUnit): Boolean {
            if (stats.moves <= 0f) return false
            val setUp = unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MustSetUp) && !unit.isSetUpForSiege()
            return !setUp || stats.moves - 1f > SimPosition.EPSILON
        }
        //endregion

        //region Dropping units
        /** dropSuperfluousUnits: with many units only the ones with the best moves are considered, the others blocked */
        fun dropSuperfluousUnits(pos: SimPosition, maxUnits0: Int) {
            val maxUnits = min(maxUnits0, pos.available.size)
            // The best move of each unit
            for (unit in pos.available) pos.importance[unit] = preferredAssignments(pos, unit, 1).first().totalScore
            val sorted = pos.available.sortedByDescending { pos.importance[it] }
            // The extra units are considered blocked
            for (unit in sorted.drop(maxUnits)) {
                pos.available.remove(unit)
                val blocked = SimAssignment(AssignmentType.Blocked, unit, pos.plot[unit], pos.plot[unit], pos.moves[unit], w.units[unit].strategy, pos.previousPlotScore(unit))
                blocked.setScore(0, 0, 0)
                pos.assignments.add(blocked)
            }
            // The units that stay are in the order of their importance
            pos.available.sortByDescending { pos.importance[it] }
        }
        //endregion

        //region Children
        private fun isBlockedByOtherUnit(pos: SimPosition, move: SimAssignment, seaDomain: Boolean): Boolean {
            if (move.type != AssignmentType.Move && move.type != AssignmentType.MoveDouble) return false
            return pos.countBlockingUnits(move.to, seaDomain) != 0
        }

        /**
         *  makeNextAssignments: the children of [pos] from the best moves of its units, bundling a swap or a chain where another unit of the plan is in
         *  the way. Completed children go to [completed], the others to [open].
         */
        fun makeNextAssignments(
            pos: SimPosition, maxBranches: Int, maxChoicesPerUnit: Int, depthFirst: Boolean,
            open: PriorityQueue<SimPosition>, completed: MutableList<SimPosition>,
        ) {
            val choices = ArrayList<SimAssignment>()
            for (unit in pos.available) choices.addAll(preferredAssignments(pos, unit, maxChoicesPerUnit))
            // Important that the moves are ordered by quality instead of unit id
            val sortedChoices = choices.sortedByDescending { it.totalScore }

            // Combo moves: swaps should have the combined score of two moves; be generous when checking the candidates
            val combos = ArrayList<Combo>()
            val maxCandidates = max(maxBranches * 5, sortedChoices.size / 2)
            var i = 0
            while (i < min(sortedChoices.size, maxCandidates)) {
                val assignment = sortedChoices[i]
                i++
                val index = i - 1
                if (assignment.type == AssignmentType.MoveDouble) continue
                if (assignment.type != AssignmentType.Move) {
                    val combo = Combo(assignment)
                    // Bundle a following block or finish move of another unit to reduce the search depth
                    if (depthFirst && index + 1 < sortedChoices.size) {
                        val next = sortedChoices[index + 1]
                        if ((next.type == AssignmentType.FinishTemp || next.type == AssignmentType.Blocked || next.type == AssignmentType.Heal) && combo.add(next)) i++
                    }
                    combos.add(combo)
                    continue
                }
                // A move: the target plot may still be occupied
                val sea = w.units[assignment.unit].domain == SimDomain.Sea
                val blocks = pos.countBlockingUnits(assignment.to, sea)
                if (blocks == 0) { combos.add(Combo(assignment)); continue }
                if (blocks > 1) continue
                // One unit is in the way: find the best move that is not blocked for it
                val blocker = pos.firstBlockingUnit(assignment.to, sea)
                for ((j, other) in sortedChoices.withIndex()) {
                    if (other.unit != blocker) continue
                    if (other.type != AssignmentType.Move && other.type != AssignmentType.MoveDouble) continue
                    // Swap: the blocker wants to move into our current plot
                    if ((index < j || other.type == AssignmentType.MoveDouble) && assignment.from == other.to) {
                        // Do not swap out a unit that was just swapped in
                        if (pos.latestAssignment(other.unit)?.type == AssignmentType.MoveSwap) continue
                        // Only units that have done nothing yet are ready to swap
                        if (pos.last[other.unit] != AssignmentType.Initial) continue
                        val combo = Combo(assignment.copy(AssignmentType.MoveSwap))
                        combo.add(other.copy(AssignmentType.MoveSwapReverse))
                        combos.add(combo)
                        break
                    }
                    // Chain: the blocker can vacate to a free plot
                    if (!isBlockedByOtherUnit(pos, other, w.units[other.unit].domain == SimDomain.Sea)) {
                        val combo = Combo(other.copy(AssignmentType.Move))
                        combo.add(assignment)
                        combos.add(combo)
                        break
                    }
                }
            }

            // A stable sort: in case of ties the original order is maintained, so the result depends on the order the moves were made in, i.e. the order of the units
            combos.sortByDescending { it.score }

            for (combo in combos) {
                if (positionCount >= MAX_POSITIONS) break
                val child = pos.makeChild(nextId++)
                pos.children.add(child)
                val a = child.addAssignment(combo.a)
                val b = combo.b?.let { child.addAssignment(it) } ?: AddResult.Added
                // A restart cannot be added in the middle of a combo move: after it
                if (a == AddResult.AddedWithRestart || b == AddResult.AddedWithRestart) child.addRestart(combo.a.unit, combo.a.moveType)

                val consistent = a != AddResult.NotAdded && b != AddResult.NotAdded
                if (consistent && isUnique(child, UNIQUENESS_CHECK_GENERATIONS)) {
                    // Do we need to keep working on this one?
                    if (child.isEarlyFinish() || child.isExhausted || child.restarted) {
                        val (acceptable, problem) = child.addFinishMovesIfAcceptable(scoring, root, child.isEarlyFinish())
                        if (acceptable) {
                            completed.add(child)
                            positionCount++
                        } else {
                            // The position is illegal: not remembered; remember the bad unit so that the search can be done again without it
                            pos.children.remove(child)
                            if (problem != -1) badUnits.merge(problem, 1, Int::plus)
                        }
                    } else {
                        open.add(child)
                        positionCount++
                    }
                } else pos.children.remove(child)
                if (pos.children.size >= maxBranches) break
            }
        }
        //endregion

        //region Uniqueness
        /** CvTacticalPosition::isUnique: no position within [levels] generations up from [pos] and below it is equivalent to it */
        private fun isUnique(pos: SimPosition, levels: Int): Boolean {
            var start = pos
            var left = levels
            while (start.parent != null && left > 0) {
                start = start.parent!!
                left--
            }
            return !equivalentToAny(pos, start)
        }

        private fun equivalentToAny(ref: SimPosition, current: SimPosition): Boolean {
            for (child in current.children) if (equivalentToAny(ref, child)) return true
            return isEquivalent(ref, current)
        }

        /** positionIsEquivalent: simple permutations of the assignments (A.B to B.A, three and four steps) give the same position */
        private fun isEquivalent(ref: SimPosition, other: SimPosition): Boolean {
            if (ref === other) return false
            // The other may already have finish moves tacked on, so it may be larger
            if (ref.assignments.size > other.assignments.size) return false
            val first = ref.firstInterestingAssignment
            var refScore = 0
            var otherScore = 0
            for (i in first until ref.assignments.size) {
                refScore += ref.assignments[i].totalScore
                otherScore += other.assignments[i].totalScore
            }
            if (refScore != otherScore) return false

            val r = ref.assignments
            val o = other.assignments
            // Differences are expected at the end
            var a = Int.MAX_VALUE
            var b = Int.MAX_VALUE
            var c = Int.MAX_VALUE
            var d = Int.MAX_VALUE
            var mismatch = false
            for (i in r.size - 1 downTo first) {
                if (r[i].sameAs(o[i])) continue
                if (a == Int.MAX_VALUE) a = i
                else if (b == Int.MAX_VALUE) b = i
                else if (c == Int.MAX_VALUE) c = i
                else if (d == Int.MAX_VALUE) d = i
                // Two differences: are the elements flipped?
                if (a != Int.MAX_VALUE && b != Int.MAX_VALUE) {
                    if (c == Int.MAX_VALUE) {
                        if (r[a].sameAs(o[b]) && r[b].sameAs(o[a])) { a = Int.MAX_VALUE; b = Int.MAX_VALUE }
                    } else if (d == Int.MAX_VALUE) {
                        val cab = r[a].sameAs(o[c]) && r[b].sameAs(o[a]) && r[c].sameAs(o[b])
                        val bca = r[a].sameAs(o[b]) && r[b].sameAs(o[c]) && r[c].sameAs(o[a])
                        if (cab || bca) { a = Int.MAX_VALUE; b = Int.MAX_VALUE; c = Int.MAX_VALUE }
                    } else {
                        val dabc = r[a].sameAs(o[d]) && r[b].sameAs(o[a]) && r[c].sameAs(o[b]) && r[d].sameAs(o[c])
                        val cdab = r[a].sameAs(o[c]) && r[b].sameAs(o[d]) && r[c].sameAs(o[a]) && r[d].sameAs(o[b])
                        val bcda = r[a].sameAs(o[b]) && r[b].sameAs(o[c]) && r[c].sameAs(o[d]) && r[d].sameAs(o[a])
                        if (dabc || cdab || bcda) { a = Int.MAX_VALUE; b = Int.MAX_VALUE; c = Int.MAX_VALUE; d = Int.MAX_VALUE }
                        else { mismatch = true; break }
                    }
                }
            }
            // An unfinished mismatch
            if (a != Int.MAX_VALUE) mismatch = true
            return !mismatch
        }
        //endregion
    }
}
