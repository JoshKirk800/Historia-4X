package com.unciv.logic.automation

import kotlin.random.Random

/** The picks of Vox Populi's CvWeightedVector used by more than one AI */
object WeightedChoice {
    /**
     *  CvWeightedVector::ChooseAbovePercentThreshold: a weighted pick among the [weights] at least [percent]% of the highest; a uniform
     *  pick when none is positive, a weighted pick among all at 0%. Returns the index.
     */
    fun chooseAbovePercentThreshold(weights: List<Int>, percent: Int, random: Random): Int {
        val highest = weights.maxOrNull() ?: 0
        if (highest <= 0) return random.nextInt(weights.size)
        val cutoff = highest * percent.coerceIn(0, 100) / 100
        val eligible = weights.indices.filter { weights[it] >= cutoff }
        var roll = random.nextInt(eligible.sumOf { maxOf(0, weights[it]) }.coerceAtLeast(1))
        for (index in eligible) {
            roll -= maxOf(0, weights[index])
            if (roll < 0) return index
        }
        return eligible.last()
    }
}
