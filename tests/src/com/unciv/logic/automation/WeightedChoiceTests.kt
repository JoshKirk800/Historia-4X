package com.unciv.logic.automation

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class WeightedChoiceTests {
    @Test
    fun `the pick is weighted among the choices within the cutoff of the best, or uniform when nothing weighs`() {
        val weights = listOf(10, 50, 40, 0)
        assertEquals(setOf(1), (0 until 200).map { WeightedChoice.chooseAbovePercentThreshold(weights, 90, Random(it)) }.toSet())
        assertEquals(setOf(1, 2), (0 until 200).map { WeightedChoice.chooseAbovePercentThreshold(weights, 75, Random(it)) }.toSet())
        val uniform = (0 until 200).map { WeightedChoice.chooseAbovePercentThreshold(listOf(0, 0, 0), 90, Random(it)) }.toSet()
        assertEquals(setOf(0, 1, 2), uniform)
    }
}
