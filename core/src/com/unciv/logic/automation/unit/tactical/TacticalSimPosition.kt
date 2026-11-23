package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import java.util.BitSet
import kotlin.math.max
import kotlin.math.sqrt

/** What [SimPosition.addAssignment] did (CvBasePosition::AddAssignmentResult) */
enum class AddResult { Added, AddedWithRestart, NotAdded }

/**
 *  One state of the plan (CvTacticalPosition): where the units of the simulation stand, what they have left, which enemies are alive and
 *  what damage has been dealt, with the list of assignments that led there. A position is made from its parent by adding assignments
 *  ([addAssignment]); the plots and enemies themselves do not change and are the [world]'s.
 *
 *  Deviations: the total score is the sum of the units' latest plot scores plus ten times the damage and bonus, as the original's comment
 *  describes it (its code sums the plot score a unit had before the assignment just added, and never counts the sparsity bonus of unmoved units);
 *  the score over the parent (the order of the open positions within a generation) is the change of those, without the second
 *  subtraction of the unit's previous plot score the original makes; a position that revealed an enemy ([restarted]) is complete at once, its
 *  remaining units are planned again after the restart.
 */
class SimPosition private constructor(val world: SimWorld, val parent: SimPosition?, val id: Long) {
    val unitCount = world.units.size
    val plot = IntArray(unitCount)
    val moves = FloatArray(unitCount)
    val attacks = IntArray(unitCount)
    val selfDamage = IntArray(unitCount)
    val last = Array(unitCount) { AssignmentType.Initial }
    val importance = IntArray(unitCount)
    val plotScore = IntArray(unitCount)
    /** The plot score of the unit's latest assignment of any kind (CvBasePosition::getLatestAssignment's score), what a new assignment is measured against */
    private val prevScore = IntArray(unitCount)
    val available = ArrayList<Int>()
    val notQuiteFinished = ArrayList<Int>()
    val finished = ArrayList<Int>()
    /** Enemy units first, then enemy cities, as [SimWorld.allAlive] */
    val alive: BitSet = BitSet()
    val enemyDamage: IntArray = IntArray(world.enemies.size)
    val cityDamage: IntArray = IntArray(world.cities.size)
    val garrisonDamage: IntArray = IntArray(world.cities.size)
    val freed = BitSet()
    private val clearedCamps = BitSet()
    private val capturedCivilians = BitSet()
    private val pillaged = BitSet()
    val assignments = ArrayList<SimAssignment>()
    val children = ArrayList<SimPosition>()
    val generation: Int = (parent?.generation ?: -1) + 1
    var damageDelta = 0
        private set
    var bonusScore = 0
        private set
    var totalScore = 0
        private set
    var scoreOverParent = 0
        private set
    var killedEnemies = 0
        private set
    var firstInterestingAssignment = 0
    var restarted = false
        private set

    private fun copyFrom(other: SimPosition) {
        other.plot.copyInto(plot); other.moves.copyInto(moves); other.attacks.copyInto(attacks); other.selfDamage.copyInto(selfDamage)
        other.last.copyInto(last); other.importance.copyInto(importance); other.plotScore.copyInto(plotScore); other.prevScore.copyInto(prevScore)
        available.addAll(other.available); notQuiteFinished.addAll(other.notQuiteFinished); finished.addAll(other.finished)
        alive.or(other.alive)
        other.enemyDamage.copyInto(enemyDamage); other.cityDamage.copyInto(cityDamage); other.garrisonDamage.copyInto(garrisonDamage)
        freed.or(other.freed); clearedCamps.or(other.clearedCamps); capturedCivilians.or(other.capturedCivilians); pillaged.or(other.pillaged)
        assignments.addAll(other.assignments)
        damageDelta = other.damageDelta; bonusScore = other.bonusScore; totalScore = other.totalScore
        killedEnemies = other.killedEnemies; firstInterestingAssignment = other.firstInterestingAssignment
    }

    /** CvTacticalPosition::initFromParent */
    fun makeChild(id: Long): SimPosition = SimPosition(world, this, id).also { it.copyFrom(this) }

    //region Reading
    val damage get() = DamageOverlay(enemyDamage, cityDamage, garrisonDamage)

    /** A copy of the damage dealt with that of [assignment] added (GetNextPosition) */
    fun damageWith(assignment: SimAssignment): DamageOverlay {
        val enemy = enemyDamage.copyOf()
        val city = cityDamage.copyOf()
        val garrison = garrisonDamage.copyOf()
        for ((index, amount) in assignment.unitDamage) enemy[index] += amount
        if (assignment.cityIndex >= 0) {
            city[assignment.cityIndex] += assignment.cityDamage
            garrison[assignment.cityIndex] += assignment.garrisonDamage
        }
        return DamageOverlay(enemy, city, garrison)
    }

    fun stats(unit: Int) = SimUnitStats(unit, plot[unit], moves[unit], attacks[unit], selfDamage[unit], importance[unit], last[unit], world.units[unit].strategy)

    val numEnemies get() = world.originalEnemies - killedEnemies
    val haveEnemies get() = world.originalEnemies > 0

    /** CvTacticalPosition::isEarlyFinish: all enemies are gone */
    fun isEarlyFinish(extraKill: Boolean = false) = world.originalEnemies > 0 && world.originalEnemies == killedEnemies + (if (extraKill) 1 else 0)

    val isExhausted get() = available.isEmpty()

    /** CvTacticalPosition::getAggressionBias: avoids extreme ratios, below 1 means we are fewer but keep attacking */
    val aggressionBias: Float get() = max(0.9f, sqrt(world.ourOriginalUnits / max(1, world.originalEnemies).toFloat()))

    fun enemyUnitAt(p: Int): Int {
        val e = world.enemyIndexAt(p)
        return if (e >= 0 && alive[e]) e else -1
    }

    fun enemyCityAt(p: Int): Int {
        val c = world.cityIndexAt(p)
        return if (c >= 0 && alive[world.enemies.size + c]) c else -1
    }

    /** CvTacticalPlot::isEnemy for the plot: an enemy unit or city that is still alive */
    fun isEnemyPlot(p: Int) = enemyUnitAt(p) >= 0 || enemyCityAt(p) >= 0
    fun isEnemyCity(p: Int) = enemyCityAt(p) >= 0
    fun isEnemyCivilian(p: Int) = world.hasEnemyCivilian(p) && !capturedCivilians[p]
    fun hasPillageAssignment(p: Int) = pillaged[p]
    /** A barbarian camp on the plot that no unit of the plan has entered yet */
    fun isCampOpen(p: Int) = world.hasCamp(p) && !clearedCamps[p]

    val field: EnemyField get() = world.fieldFor(alive)

    fun enemyDistance(p: Int, domain: SimDomain = SimDomain.Both) = field.distance[domain.ordinal][p]
    fun rangedEnemyDistance(p: Int, domain: SimDomain = SimDomain.Both) = field.rangedDistance[domain.ordinal][p]
    fun adjacentEnemies(p: Int, domain: SimDomain = SimDomain.Both) = field.adjacent[domain.ordinal][p]

    private fun domainMatches(unit: Int, domain: SimDomain) = domain == SimDomain.Both || world.units[unit].domain == domain

    /** CvTacticalPlot::getNumAdjacentFriendlies: combat units of ours next to the plot, those of the plan where they are now; [ignorePlot]: the unit there is not counted */
    fun adjacentFriendlies(p: Int, domain: SimDomain, ignorePlot: Int = -1): Int {
        if (ignorePlot >= 0 && world.neighbors[p].contains(ignorePlot)) return adjacentFriendliesAll(p, domain) - 1
        return adjacentFriendliesAll(p, domain)
    }

    private fun adjacentFriendliesAll(p: Int, domain: SimDomain): Int {
        var count = world.nonSimFriendliesAround(p, domain)
        for (unit in 0 until unitCount)
            if (domainMatches(unit, domain) && world.neighbors[p].contains(plot[unit])) count++
        return count
    }

    /** Whether a unit of the plan has finished its turn on the plot (CvTacticalPlot::isCombatEndTurn) */
    fun isCombatEndTurn(p: Int): Boolean {
        if (world.isFriendlyDefenderEndTurn(p)) return true
        for (unit in notQuiteFinished + finished) if (plot[unit] == p && endsTurn(unit)) return true
        return false
    }

    /** setCombatUnitEndTurn is called for a finished unit that is not blocked and will not run off to heal */
    private fun endsTurn(unit: Int) = last[unit] != AssignmentType.Blocked &&
        (moves[unit] <= EPSILON || world.civ.isBarbarian || world.units[unit].unit.let { it.health >= it.getMaxHealth() })

    /** CvTacticalPlot::getNumAdjacentFriendliesEndTurn: units that have finished their turn next to the plot */
    fun adjacentFriendliesEndTurn(p: Int, domain: SimDomain): Int {
        var count = world.nonSimFriendliesEndTurnAround(p, domain)
        for (unit in notQuiteFinished + finished)
            if (endsTurn(unit) && domainMatches(unit, domain) && world.neighbors[p].contains(plot[unit])) count++
        return count
    }

    /** CvTacticalPlot::IsSimUnitBlocking: a unit of the plan that has used up its moves stands on the plot */
    fun isSimUnitBlocking(p: Int, domain: SimDomain): Boolean {
        for (unit in notQuiteFinished + finished) if (plot[unit] == p && (domain == SimDomain.Both || world.units[unit].domain == domain)) return true
        return false
    }

    /** CvTacticalPlot::hasCoverFromOtherUnits */
    fun hasCoverFromOtherUnits(p: Int): Boolean {
        val both = enemyDistance(p)
        if (both < 2) return false
        if (both > 3) return true
        val domain = if (world.plots[p].isWater) SimDomain.Sea else SimDomain.Land
        val mine = enemyDistance(p, domain)
        for (other in world.neighbors[p])
            if (enemyDistance(other, domain) < mine && !isCombatEndTurn(other)) return false
        return true
    }

    /** CvTacticalPosition::countBlockingUnitsAtPlot: units of the plan on the plot of the same kind of domain (land or sea) */
    fun countBlockingUnits(p: Int, seaDomain: Boolean): Int = (0 until unitCount).count { plot[it] == p && (world.units[it].domain == SimDomain.Sea) == seaDomain }

    fun firstBlockingUnit(p: Int, seaDomain: Boolean): Int = (0 until unitCount).firstOrNull { plot[it] == p && (world.units[it].domain == SimDomain.Sea) == seaDomain } ?: -1

    fun latestAssignment(unit: Int): SimAssignment? = assignments.lastOrNull { it.unit == unit }

    fun initialAssignment(unit: Int): SimAssignment? = assignments.firstOrNull { it.unit == unit && it.type == AssignmentType.Initial }

    fun hasAssignmentOfType(unit: Int, type: AssignmentType): Boolean {
        for (i in firstInterestingAssignment until assignments.size) if (assignments[i].unit == unit && assignments[i].type == type) return true
        return false
    }

    /** What the plan has so far given the unit's plot: its latest assignment's plot score (CvBasePosition::getLatestAssignment) */
    fun previousPlotScore(unit: Int) = prevScore[unit]
    //endregion

    //region Building the root
    /** CvTacticalPosition::addAvailableUnit and addInitialAssignments: the units start on their plots */
    fun startUnits(scoring: SimScoring) {
        for ((index, simUnit) in world.units.withIndex()) {
            plot[index] = world.plotOf(simUnit.unit.getTile())
            moves[index] = simUnit.startMoves
            attacks[index] = simUnit.startAttacks
            available.add(index)
        }
        alive.set(0, world.enemies.size + world.cities.size)
        // First pass: scores are off, as officially we do not know where our units are yet; second pass fixes it up with the right number of adjacent units
        val initial = ArrayList<SimAssignment>()
        for (index in 0 until unitCount) {
            val stats = stats(index)
            stats.moves = 0f
            val score = scoring.scorePlotForMove(this, stats, plot[index], EvalMode.Initial, damage)
            val assignment = SimAssignment(AssignmentType.Initial, index, plot[index], plot[index], moves[index], stats.strategy, previousPlotScore(index))
            assignment.setScoreFrom(score)
            assignments.add(assignment)
            initial.add(assignment)
        }
        for (index in 0 until unitCount) {
            val stats = stats(index)
            stats.moves = 0f
            val score = scoring.scorePlotForMove(this, stats, plot[index], EvalMode.Initial, damage)
            initial[index].setScoreFrom(score)
            prevScore[index] = initial[index].plotScore
        }
        firstInterestingAssignment = assignments.size
    }
    //endregion

    //region Adding assignments
    private fun updateScore(unit: Int, newPlotScore: Int, oldPlotScore: Int, damage: Int, bonus: Int) {
        scoreOverParent += bonus + newPlotScore - oldPlotScore + damage
        damageDelta += damage
        bonusScore += bonus
        plotScore[unit] = newPlotScore
        recomputeTotal()
    }

    private fun recomputeTotal() {
        totalScore = (damageDelta + bonusScore) * 10 + plotScore.sum()
    }

    /** CvTacticalPosition::addAssignment: the consequences of one step of the plan; [AddResult.NotAdded] if the unit is not where the step says it is */
    fun addAssignment(a: SimAssignment): AddResult {
        val unit = a.unit
        if (a.type == AssignmentType.MoveDouble || unit !in available || plot[unit] != a.from) return AddResult.NotAdded

        last[unit] = a.type
        assignments.add(a)
        prevScore[unit] = a.plotScore

        var affectsScore = true
        var endOfSim = false
        var newTiles = 0
        var newEnemies = 0
        var cityCaptured = false
        when (a.type) {
            AssignmentType.Initial -> affectsScore = false
            AssignmentType.MoveForced, AssignmentType.MoveSwapReverse, AssignmentType.Move, AssignmentType.MoveSwap, AssignmentType.Capture -> {
                moves[unit] = a.remainingMoves
                plot[unit] = a.to
                val reveal = world.reveal(unit, a.to)
                newTiles = reveal[0]
                newEnemies = reveal[1]
                // A move that reveals enemies may be followed by more moves
                if (newEnemies > 0 && a.type == AssignmentType.Move) last[unit] = AssignmentType.MoveForced
                if (world.hasCamp(a.to) && !clearedCamps[a.to]) { clearedCamps.set(a.to); killedEnemies++ }
                capturedCivilians.set(a.to)
                applyDamage(a)
            }
            AssignmentType.RangeAttack, AssignmentType.MeleeAttack -> {
                moves[unit] = a.remainingMoves
                attacks[unit]--
                selfDamage[unit] += a.selfDamage
                applyDamage(a)
            }
            AssignmentType.RangeKill -> {
                moves[unit] = a.remainingMoves
                attacks[unit]--
                applyDamage(a)
                killEnemyAt(a.to)
                freed.set(a.to)
            }
            AssignmentType.MeleeKill, AssignmentType.MeleeKillNoAdvance -> {
                moves[unit] = a.remainingMoves
                attacks[unit]--
                selfDamage[unit] += a.selfDamage
                if (a.type == AssignmentType.MeleeKill) {
                    plot[unit] = a.to
                    val reveal = world.reveal(unit, a.to)
                    newTiles = reveal[0]
                    newEnemies = reveal[1]
                }
                applyDamage(a)
                cityCaptured = enemyCityAt(a.to) >= 0
                killEnemyAt(a.to)
                if (world.hasCamp(a.to) && !clearedCamps[a.to]) { clearedCamps.set(a.to); killedEnemies++ }
                capturedCivilians.set(a.to)
                freed.set(a.to)
            }
            AssignmentType.Pillage -> {
                moves[unit] = a.remainingMoves
                pillaged.set(a.to)
                applyDamage(a)
            }
            AssignmentType.Finish, AssignmentType.Heal, AssignmentType.FinishTemp -> endOfSim = true
            AssignmentType.Blocked -> { affectsScore = false; endOfSim = true }
            AssignmentType.Restart -> affectsScore = false
            AssignmentType.MoveDouble -> return AddResult.NotAdded
        }
        if (moves[unit] < EPSILON) moves[unit] = 0f

        if (affectsScore) {
            // When in doubt, increasing our visibility is good
            val extra = if (a.remainingMoves > 0f) newTiles else 0
            updateScore(unit, a.plotScore, a.oldPlotScore, a.damageDelta, a.bonusScore + extra)
        }

        // Are we done with this unit or can it do further moves?
        if (moves[unit] <= 0f || endOfSim) {
            available.remove(unit)
            notQuiteFinished.add(unit)
        }

        // A move that reveals an enemy or the capture of a city: the plan has to be made again from there
        if ((newEnemies > 0 || cityCaptured) && available.isNotEmpty()) return AddResult.AddedWithRestart
        return AddResult.Added
    }

    private fun applyDamage(a: SimAssignment) {
        for ((index, amount) in a.unitDamage) enemyDamage[index] += amount
        if (a.cityIndex >= 0) {
            cityDamage[a.cityIndex] += a.cityDamage
            garrisonDamage[a.cityIndex] += a.garrisonDamage
        }
    }

    private fun killEnemyAt(p: Int) {
        val e = enemyUnitAt(p)
        if (e >= 0) { alive.clear(e); killedEnemies++ }
        val c = enemyCityAt(p)
        if (c >= 0) { alive.clear(world.enemies.size + c); killedEnemies++ }
    }

    /** The restart assignment a revealing step is followed by; the position is complete (see the class comment) */
    fun addRestart(unit: Int, moveType: MoveStrategy) {
        val restart = SimAssignment(AssignmentType.Restart, unit, -1, -1, 0f, moveType, previousPlotScore(unit))
        restart.setScore(0, 0, 0)
        assignments.add(restart)
        prevScore[unit] = 0
        restarted = true
    }
    //endregion

    //region Checking a plan
    /**
     *  CvTacticalPosition::canProbablyEndTurnAfterAssignment: filters out the no-chance-in-hell moves; not the final check, the situation can
     *  still change as the plan goes on.
     */
    fun canProbablyEndTurnAfter(
        scoring: SimScoring, unit: SimUnitStats, atPlot: Int, type: AssignmentType, overlay: DamageOverlay = damage,
    ): Boolean {
        val simUnit = world.units[unit.index]
        if (!TacticalSafety.canDefend(simUnit.unit)) return isCombatEndTurn(atPlot)
        // With nowhere to flee to, we can just as well stay
        if (world.safePlotCount[unit.index] == 0) return true
        var danger = world.danger(unit.index, world.plots[atPlot], unit.selfDamage, overlay)
        danger /= max(1, world.aggression.ordinal)
        return scoring.scoreCombatUnitTurnEnd(
            this, unit, type, atPlot, danger, SimDomain.Both, unit.selfDamage, EvalMode.Final, available.size > 1, true
        ) != Int.MAX_VALUE
    }

    /** CvTacticalPosition::isKillOrImprovedPosition: although the plan picks positive moves, sometimes there are only bad choices */
    fun isKillOrImprovedPosition(root: SimPosition): Boolean {
        // A kill is always good; it also messes up the enemy distance, so before and after cannot be compared then. A restart means new enemies
        for (i in firstInterestingAssignment until assignments.size)
            if (assignments[i].type.isKill || assignments[i].type == AssignmentType.Restart) return true

        var positive = 0
        var negative = 0
        var before = 0
        var after = 0
        var attacksWithoutMove = 0
        var healing = 0
        for (i in firstInterestingAssignment until assignments.size) {
            val move = assignments[i]
            when (move.type) {
                AssignmentType.Finish -> {
                    val initial = initialAssignment(move.unit) ?: continue
                    val simUnit = world.units[move.unit]
                    val initialPlot = initial.from
                    val finalPlot = move.from
                    val distanceBefore = world.distanceToTarget(world.plots[initialPlot], simUnit.domain)
                    val distanceAfter = world.distanceToTarget(world.plots[finalPlot], simUnit.domain)
                    // Only if both are reachable: if either is unreachable, they cannot be compared
                    if (distanceBefore != Int.MAX_VALUE && distanceAfter != Int.MAX_VALUE) { before += distanceBefore; after += distanceAfter }
                    val domain = if (simUnit.strategy == MoveStrategy.FirstLine) (if (world.plots[finalPlot].isWater) SimDomain.Sea else SimDomain.Land) else SimDomain.Both
                    val initialDistance = root.enemyDistance(initialPlot, domain)
                    val finalDistance = enemyDistance(finalPlot, domain)
                    val stayingInCitadel = initialPlot == finalPlot && enemyDistance(finalPlot) < 3 && world.isOurCitadel(world.plots[finalPlot]) &&
                        simUnit.domain == SimDomain.Land
                    when (simUnit.strategy) {
                        MoveStrategy.FirstLine ->
                            if (stayingInCitadel) positive++
                            else if (initialDistance != 1 && finalDistance == 1) positive++
                            else if (initialDistance == 1 && finalDistance != 1) negative++
                        MoveStrategy.SecondLine ->
                            if (stayingInCitadel) positive++
                            else if (initialDistance != 2 && finalDistance == 2) positive++
                            else if (initialDistance == 2 && finalDistance != 2) negative++
                        MoveStrategy.ThirdLine -> {
                            // Third line is a bit different, ignore citadels
                            if (initialDistance == 1 && finalDistance > 1) positive++
                            if (initialDistance > 1 && finalDistance == 1) negative++
                        }
                        MoveStrategy.None -> Unit
                    }
                }
                AssignmentType.RangeAttack, AssignmentType.MeleeAttack -> attacksWithoutMove++
                AssignmentType.Heal -> healing++
                else -> Unit
            }
        }
        val movingTowardsTarget = world.targetDistanceRelevant && after < before
        return if (haveEnemies)
            // Staying in place and bombarding is fine, so is healing
            negative < positive || negative == positive && (movingTowardsTarget || attacksWithoutMove > 0 || healing > 1 || healing == root.unitCount)
        else movingTowardsTarget || healing > 0
    }

    /**
     *  CvTacticalPosition::addFinishMovesIfAcceptable: see whether all the plots where our units would end their turn are acceptable, a check
     *  deferred because in the beginning it is not clear how many enemies we can eliminate.
     *  @return (whether the position is acceptable, the unit that cannot stay where it is, -1 if there is none or the plan is just not an improvement)
     */
    fun addFinishMovesIfAcceptable(scoring: SimScoring, root: SimPosition, earlyFinish: Boolean): Pair<Boolean, Int> {
        // A few "bad" units are allowed, depending on how well it goes
        val lossThreshold = world.defaultUnitLossThreshold + killedEnemies
        var potentialLosses = 0
        val finishedHere = ArrayList<SimAssignment>()
        for (unit in notQuiteFinished) {
            if (initialAssignment(unit) == null) return false to -1
            // A blocked unit that has movement left can flee, let's assume that is fine
            if (last[unit] == AssignmentType.Blocked && moves[unit] > 0f) continue

            val stats = stats(unit)
            val atPlot = plot[unit]
            if (world.returnToStart && world.plotOf(world.units[unit].unit.getTile()) != atPlot) return false to -1
            if (moves[unit] < world.saveMovement) return false to -1

            val next = scoring.scorePlotForMove(this, stats, atPlot, EvalMode.Final, damage)
            var accept = next.isAcceptable
            if (!accept) {
                // Second chance: put the unit in danger if we can afford it and it is not one of our champions
                if (world.units[unit].unit.promotions.XP < world.medianUnitXp && potentialLosses < lossThreshold) {
                    potentialLosses++
                    accept = true
                }
            }
            if (!accept) return false to unit
            // The unit ends its turn, unless it is blocked: then others may use it for other tasks
            if (last[unit] != AssignmentType.Blocked) {
                next.remainingMoves = moves[unit]
                next.type = AssignmentType.Finish
                finishedHere.add(next)
            }
        }
        // A sparse plan is better: a bonus for the units that did not move, especially when the enemy is gone
        val sparsity = if (isEarlyFinish()) 123 else 45
        val untouched = available.filter { last[it] == AssignmentType.Initial }

        // Scores look good and the target was killed, we're done; second chance: a kill, a healed unit or a better arrangement
        val saved = assignments.size
        assignments.addAll(finishedHere)
        if (earlyFinish || isKillOrImprovedPosition(root)) {
            for (unit in untouched) plotScore[unit] += sparsity
            recomputeTotal()
            finished.addAll(notQuiteFinished)
            notQuiteFinished.clear()
            return true to -1
        }
        // The finish moves stay out of an unacceptable plan
        while (assignments.size > saved) assignments.removeAt(assignments.size - 1)
        return false to -1
    }
    //endregion

    companion object {
        const val EPSILON = Constants.minimumMovementEpsilon

        fun root(world: SimWorld): SimPosition = SimPosition(world, null, 1L)
    }
}
