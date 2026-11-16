package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization

/**
 *  Vox Populi's timed opinion modifiers (CvDiplomacyAI::AdjustModifierDuration, AdjustTimedModifier, and the stack decay of
 *  TestOpinionModifiers' checkAndUpdateStackingOpinionModifier): a deed's opinion term fades over its duration, and its count halves
 *  each time the duration passes.
 */
object OpinionTimers {

    /** CvDiplomacyAI's TimedModifierTypes */
    enum class Type { Standard, Stacked, Diminishing }

    /**
     *  CvDiplomacyAI::AdjustModifierDuration: [duration] lengthened by a personality value of 6 to 10 (+4% each) and shortened by one of
     *  1 to 4 (+5% steps from 80%), the other way round when [invert]ed; scaled by the game speed's OpinionDurationPercent; at least 1
     */
    fun adjustDuration(civ: Civilization, duration: Int, personality: Int, invert: Boolean = false): Int {
        if (duration <= 0) return 1
        val percent = when (personality) {
            1 -> if (invert) 120 else 80
            2 -> if (invert) 115 else 85
            3 -> if (invert) 110 else 90
            4 -> if (invert) 105 else 95
            6 -> if (invert) 96 else 104
            7 -> if (invert) 92 else 108
            8 -> if (invert) 88 else 112
            9 -> if (invert) 84 else 116
            10 -> if (invert) 80 else 120
            else -> 100
        }
        return maxOf(duration * percent / 100 * DiplomacyTurn.opinionDurationPercent(civ) / 100, 1)
    }

    /**
     *  CvDiplomacyAI::AdjustTimedModifier: [value], set on [turnStamp], [turn] - [turnStamp] turns into [duration]. Standard (or one
     *  stack): fades with the share of the duration gone; stacked: towards the value of half the stacks; diminishing: the same, the first
     *  stack worth [firstStackValue]. Never below 1 in size before the duration has passed; 0 once it has.
     */
    fun adjust(value: Int, duration: Int, turnStamp: Int, turn: Int, type: Type, stacks: Int = 1, firstStackValue: Int = 0): Int {
        if (value == 0 || turnStamp < 0 || stacks < 0) return value
        val length = maxOf(duration, 1)
        val passed = turn - turnStamp
        if (passed <= 0) return value
        val durationPercent = passed * 100 / length
        val left = 100 - durationPercent
        if (passed >= length || left <= 0) return 0
        fun atLeastOne(v: Int) = if (value < 0) minOf(-1, v) else maxOf(1, v)
        val kind = when {
            stacks == 1 -> Type.Standard
            type == Type.Diminishing && firstStackValue == 0 -> Type.Stacked
            else -> type
        }
        return when (kind) {
            Type.Standard -> atLeastOne(value * left / 100)
            Type.Stacked -> {
                val valueToReduceTo = stacks / 2 * (value / stacks)
                atLeastOne(value - (value - valueToReduceTo) * durationPercent / 100)
            }
            Type.Diminishing -> {
                val remainingValue = value - firstStackValue
                val remainingStacks = stacks - 1
                val stacksToReduceTo = stacks / 2
                if (remainingStacks == 1 || stacksToReduceTo == 1)
                    atLeastOne(value - remainingValue * durationPercent / 100)
                else {
                    val valueToReduceTo = firstStackValue + (stacksToReduceTo - 1) * (remainingValue / remainingStacks)
                    atLeastOne(value - (value - valueToReduceTo) * durationPercent / 100)
                }
            }
        }
    }

    /** checkAndUpdateStackingOpinionModifier: [stacks] halve once per [duration] passed since [turnStamp] */
    fun decayStacks(stacks: Int, turnStamp: Int, turn: Int, duration: Int): Int {
        var left = stacks
        var passed = turn - turnStamp
        while (passed >= duration && left > 0) {
            left /= 2
            passed -= duration
        }
        return left
    }
}
