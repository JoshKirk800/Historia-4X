package com.unciv.logic.automation.unit.tactical

/**
 *  eAggressionLevel (CvTacticalAI.h): how much damage the units may take for what they deal. The order matters, the original indexes its tables
 *  and divides danger by it ([ordinal]; None is 0): [Low] attacks only when it does more than it takes, [Medium] accepts a little more,
 *  [High] survives a counterattack, [Braveheart] does not care.
 */
enum class AggressionLevel { None, Low, Medium, High, Braveheart }

/**
 *  eUnitAssignmentType (CvTacticalAI.h): what one unit does in one step of a plan. Not simulated: USE_POWER (citadels, repair fleet; the units
 *  that use them are the support units, see [TacticalSimulation]), WAIT (never produced by the original either).
 */
enum class AssignmentType {
    Initial, Move, MeleeAttack, MeleeKill, RangeAttack, RangeKill, Finish, Blocked, Pillage, Capture, MoveForced, Restart,
    MeleeKillNoAdvance, MoveSwap, MoveSwapReverse, MoveDouble, FinishTemp, Heal;

    /** isKillAssignment */
    val isKill get() = this == MeleeKill || this == MeleeKillNoAdvance || this == RangeKill
    /** IsAttackMove */
    val isAttack get() = this == MeleeAttack || this == MeleeKill || this == MeleeKillNoAdvance || this == RangeAttack || this == RangeKill
}

/**
 *  eUnitMovementStrategy (CvTacticalAI.h): where in the formation a unit wants to be. The original's support and embarked strategies
 *  belong to units this simulation does not move (generals, admirals, siege towers, embarked units on their way to a target, see [TacticalSimulation]).
 */
enum class MoveStrategy { None, FirstLine, SecondLine, ThirdLine }

/** eUnitMoveEvalMode (CvTacticalAI.h) */
enum class EvalMode { Initial, Intermediate, Final }

/** eTactPlotDomain (CvTacticalAI.h): the kind of unit a plot's enemy distance or neighbor count is about */
enum class SimDomain { Both, Land, Sea }

/**
 *  STacticalAssignment (CvTacticalAI.h): one step of a plan. [unit] is the index in [SimWorld.units], [from] and [to] are plot indices in
 *  [SimWorld.plots]; [unitDamage] is the damage dealt per enemy unit index and [cityDamage] the damage dealt to the enemy city [cityIndex].
 *  The score is `plotScore - oldPlotScore + (bonusScore + damageDelta) x 10`, each part clamped to a short as the original does.
 */
class SimAssignment(
    var type: AssignmentType,
    val unit: Int,
    val from: Int,
    val to: Int,
    var remainingMoves: Float,
    val moveType: MoveStrategy,
    val oldPlotScore: Int,
) {
    var unitDamage: Map<Int, Int> = emptyMap()
    var cityIndex = -1
    var cityDamage = 0
    var selfDamage = 0
    var plotScore = 0
        private set
    var bonusScore = 0
        private set
    var damageDelta = IMPOSSIBLE
        private set
    var totalScore = 0
        private set

    init { setImpossible() }

    val isAcceptable get() = damageDelta != IMPOSSIBLE

    fun setScore(plot: Int, bonus: Int, damage: Int) {
        plotScore = clamp(plot)
        bonusScore = clamp(bonus)
        damageDelta = clamp(damage)
        totalScore = clamp(plotScore - oldPlotScore + (bonusScore + damageDelta) * 10)
    }

    fun setScoreFrom(other: SimAssignment) = setScore(other.plotScore, other.bonusScore, other.damageDelta)

    fun addScore(plot: Int, bonus: Int, damage: Int) = setScore(plotScore + plot, bonusScore + bonus, damageDelta + damage)

    fun addScoreFrom(other: SimAssignment) = addScore(other.plotScore, other.bonusScore, other.damageDelta)

    fun setImpossible() = setScore(0, 0, IMPOSSIBLE)

    fun copy(newType: AssignmentType = type): SimAssignment {
        val result = SimAssignment(newType, unit, from, to, remainingMoves, moveType, oldPlotScore)
        result.unitDamage = unitDamage
        result.cityIndex = cityIndex
        result.cityDamage = cityDamage
        result.selfDamage = selfDamage
        result.plotScore = plotScore
        result.bonusScore = bonusScore
        result.damageDelta = damageDelta
        result.totalScore = totalScore
        return result
    }

    /** STacticalAssignment::operator==: what the equivalence of two positions compares */
    fun sameAs(other: SimAssignment) = totalScore == other.totalScore && unit == other.unit && from == other.from && to == other.to &&
        remainingMoves == other.remainingMoves && moveType == other.moveType && type == other.type

    override fun toString() = "unit $unit $type $from -> $to ($totalScore)"

    companion object {
        /** TACTICAL_COMBAT_IMPOSSIBLE_SCORE */
        const val IMPOSSIBLE = -1000

        private fun clamp(value: Int) = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}

/** SUnitStats (CvTacticalAI.h): what the simulation knows about one unit at one point of a plan */
class SimUnitStats(
    val index: Int,
    var plot: Int,
    var moves: Float,
    var attacks: Int,
    var selfDamage: Int,
    var importance: Int,
    var last: AssignmentType,
    val strategy: MoveStrategy,
) {
    fun copy() = SimUnitStats(index, plot, moves, attacks, selfDamage, importance, last, strategy)
}
