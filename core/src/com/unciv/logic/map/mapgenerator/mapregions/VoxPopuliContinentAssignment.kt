package com.unciv.logic.map.mapgenerator.mapregions

import kotlin.math.floor
import kotlin.math.min

/**
 * How Vox Populi's `AssignStartingPlots:GenerateRegions` (continental method) deals the civs to the landmasses,
 * `(2) Vox Populi/Core Files/Overrides/AssignStartingPlots.lua`. It differs from Brave New World's, which gives each civ the landmass
 * with the best fertility / (1 + civs already there), in:
 *  - landmasses with less than 25% of the biggest landmass's fertility are ignored (12.5% after the retries below);
 *  - only the [numCivs] most fertile landmasses count, and each of them can take `floor(fertility / fertilityPerCiv)` civs,
 *    where `fertilityPerCiv` is 70% (down to 50%) of the fertility of those landmasses divided by the civs;
 *  - a civ that opens a landmass is always followed by one more civ on the same landmass, and the last civ cannot open a landmass.
 *    So civs live on a landmass in pairs: on Duel both civs share one.
 *
 * The Lua tells land areas (contiguous land without mountains) from landmasses (contiguous land); a continent of the map here is a landmass
 * and counts as its one area. The Lua also zeroes the civ counts of landmasses that can hold only one civ, but never reads them afterwards,
 * so this does not either.
 */
object VoxPopuliContinentAssignment {
    /** What [assign] decided */
    sealed class Result {
        /** Civs per continent id, in the order the continents received their first civ */
        class Continents(val civsPerContinent: LinkedHashMap<Int, Int>) : Result()
        /** Fewer than two landmasses are fertile enough even at 12.5%: all civs on the biggest landmass (the `BIGGEST_LANDMASS` method) */
        object BiggestLandmass : Result()
        /** The landmasses cannot hold the civs even at 50% fertility per civ: rectangular division of the whole map (the `RECTANGULAR` method) */
        object Rectangular : Result()
    }

    private const val maxLandmassRetries = 5
    private const val maxFertilityPerCivRetries = 4

    /** @param fertility the total start placement fertility of each continent id (continents without fertile land may be absent) */
    fun assign(fertility: Map<Int, Int>, numCivs: Int): Result {
        val land = fertility.filterValues { it > 0 }
        if (land.isEmpty() || numCivs <= 0) return Result.BiggestLandmass
        val biggest = land.values.max()
        var retryLandmass = 0
        var retryFertilityPerCiv = 0
        while (true) {
            val minimumFertility = biggest * (0.25 - 0.025 * retryLandmass)
            // ids ascending so ties resolve the same on each run
            val candidates = land.entries.filter { it.value >= minimumFertility }.sortedBy { it.key }
            if (candidates.size <= 1) {
                if (retryLandmass < maxLandmassRetries) {
                    retryLandmass++
                    continue
                }
                return Result.BiggestLandmass
            }

            val bestAreas = candidates.sortedByDescending { it.value }.take(min(candidates.size, numCivs)).map { it.key }
            val relevantFertility = bestAreas.sumOf { land[it]!! }
            val fertilityPerCiv = relevantFertility * (0.7 - 0.05 * retryFertilityPerCiv) / numCivs

            // The Lua stops at the first landmass that cannot hold a civ; later ones still take part in the dealing below
            var totalFertileAreas = 0
            for (area in bestAreas) {
                val holds = floor(land[area]!! / fertilityPerCiv).toInt()
                totalFertileAreas += holds
                if (holds == 0) break
            }
            if (totalFertileAreas < numCivs) {
                if (retryFertilityPerCiv < maxFertilityPerCivRetries) {
                    retryFertilityPerCiv++
                    continue
                }
                return Result.Rectangular
            }

            return Result.Continents(deal(bestAreas, land, numCivs, fertilityPerCiv))
        }
    }

    private fun deal(bestAreas: List<Int>, land: Map<Int, Int>, numCivs: Int, fertilityPerCiv: Double): LinkedHashMap<Int, Int> {
        val remaining = bestAreas.associateWith { land[it]!!.toDouble() }.toMutableMap()
        val civsPerArea = LinkedHashMap<Int, Int>()
        val inhabited = HashSet<Int>()
        var openLandmass: Int? = null // the landmass the last civ opened: it gets the next civ
        for (civ in 1..numCivs) {
            var bestArea = -1
            var bestFertility = 0.0
            for (area in bestAreas) {
                val fertilityLeft = remaining[area]!!
                if (fertilityLeft <= bestFertility) continue
                if (openLandmass != null && area != openLandmass) continue
                if (civ == numCivs && area !in inhabited) continue
                bestArea = area
                bestFertility = fertilityLeft
            }
            if (bestArea == -1) bestArea = bestAreas.first() // the Lua's failsafe

            civsPerArea[bestArea] = (civsPerArea[bestArea] ?: 0) + 1
            openLandmass = if (inhabited.add(bestArea)) bestArea else null
            remaining[bestArea] = remaining[bestArea]!! - fertilityPerCiv
        }
        return civsPerArea
    }
}
