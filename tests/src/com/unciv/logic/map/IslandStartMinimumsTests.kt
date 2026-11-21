package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.StartMinimums
import com.unciv.logic.map.mapgenerator.mapregions.StartRecipes
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliStartFinder
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliStartFinder.RingCounts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lowered minimums of `ChooseLocations` in Archipelago.lua and TinyIslands.lua, as `EvaluateCandidatePlot` applies them */
class IslandStartMinimumsTests {
    private fun ring(food: Int, prod: Int, good: Int, tilesOnMap: Int) = RingCounts(food, prod, good, junk = 0, river = 0, tilesOnMap = tilesOnMap)

    /** 2 food, 1 production and 3 good tiles in the first ring; 2 food, 1 production and 6 good by the second; 3 food, 1 production and 8 good in all: a poor island's site */
    private val poorSite = listOf(ring(2, 1, 3, 6), ring(0, 0, 3, 12), ring(1, 0, 2, 18))

    @Test
    fun `a poor site fails Vox Populi's minimums`() {
        assertFalse(VoxPopuliStartFinder.evaluate(poorSite, 80, 0).meetsMinimums)
    }

    @Test
    fun `the minimums of Archipelago lower the food of the second and third ring and the production of the third`() {
        // The site has 2 food by the second ring and 3 in all, and 1 production in all: enough for Archipelago's 2, 3 and 1
        assertTrue(VoxPopuliStartFinder.evaluate(poorSite, 80, 0, StartRecipes.archipelago.minimums()).meetsMinimums)
        // One more food tile needed in the third ring and it is short
        val moreFood = StartMinimums(listOf(1, 2, 4), listOf(1, 1, 1))
        assertFalse(VoxPopuliStartFinder.evaluate(poorSite, 80, 0, moreFood).meetsMinimums)
    }

    @Test
    fun `Tiny Islands needs 2 food in the third ring where Archipelago needs 3`() {
        val site = listOf(ring(2, 1, 3, 6), ring(0, 0, 3, 12), ring(0, 0, 2, 18)) // 2 food in all
        assertTrue(VoxPopuliStartFinder.evaluate(site, 80, 0, StartRecipes.tinyIslands.minimums()).meetsMinimums)
        assertFalse(VoxPopuliStartFinder.evaluate(site, 80, 0, StartRecipes.archipelago.minimums()).meetsMinimums)
    }

    @Test
    fun `a site with no production in the outer rings fails Tiny Islands' minimums`() {
        val site = listOf(ring(2, 0, 3, 6), ring(0, 0, 3, 12), ring(0, 0, 2, 18)) // no production in the first ring
        assertFalse(VoxPopuliStartFinder.evaluate(site, 80, 0, StartRecipes.tinyIslands.minimums()).meetsMinimums)
    }
}
