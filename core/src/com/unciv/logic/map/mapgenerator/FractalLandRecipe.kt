package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.MapType
import yairm210.purity.annotations.Readonly

/** The options of the Civ V scripts for the sea level (Low, Normal, High), the index `Map.GetCustomOption(4)` gives the scripts */
enum class SeaLevel { Low, Normal, High }

/**
 *  What differs between the landmass steps of the Civ V scripts that draw one [CivVFractal] and cut it at a sea level (Continents.lua,
 *  Pangaea.lua, SmallContinents.lua, Archipelago.lua, TinyIslands.lua, ...: the scripts copy each other's `GeneratePlotTypes` and change
 *  only these numbers and two or three branches). [CivVFractalLandmass] is the one pipeline that runs them.
 *
 *  Fractal grain by world size, the plates of the ridge fields, the hills and mountain percentiles and the world age options are the same
 *  in every copy and live in [CivVFractalLandmass] and [CivVWorldSize].
 */
data class FractalLandRecipe(
    /** The Lua file the numbers are from, and the [MapType] that runs it */
    val script: String,
    val mapType: String,
    /** The share of the fractal's cells that is water, by the script's sea level option */
    val waterPercentLow: Int,
    val waterPercentNormal: Int,
    val waterPercentHigh: Int,
    /** Mountains and foot hills take this many percentiles more of the mountain field (`extra_mountains`) */
    val extraMountains: Int,
    /** Multiplies the plate counts of the ridge fields (`adjust_plates`), on top of the world age's */
    val platesMultiplier: Double,
    /** Ocean cells on the highest ridge percentiles of the mountain field become a peak (100th), hills (99th) or flat land (97th, 95th): `tectonic_islands` */
    val tectonicIslands: Boolean,
    /** A wandering rift from the south edge to the north edge splits the land before the biggest landmass is measured (`has_center_rift`).
     *  The land is shifted to the emptiest edge first, in the same attempt. Without it the land is measured as cut and shifted only when it is finished. */
    val centreRift: Boolean,
    /** The script draws another fractal until the biggest landmass is this share of the land (Continents: at most 58 %, Pangaea: at least 84 %) */
    val biggestLandmass: ClosedFloatingPointRange<Double>,
    /** The continent grain when the script's roll of 7 comes out below 4, the other rolls get the other of 1 and 2 (Continents 2, Pangaea 1) */
    val grainOnLowRoll: Int,
    /** After the land is shifted, the landmass that was the biggest is pushed towards a randomly chosen pole, by 30 % to 100 % of the rows
     *  between it and the edge, an even number (Pangaea: against jungle dominating the map) */
    val poleShift: Boolean
) {
    @Readonly
    fun waterPercent(seaLevel: SeaLevel) = when (seaLevel) {
        SeaLevel.Low -> waterPercentLow
        SeaLevel.Normal -> waterPercentNormal
        SeaLevel.High -> waterPercentHigh
    }

    /** The scripts' test: [biggest] of [total] land tiles in the biggest landmass */
    @Readonly
    fun accepts(biggest: Int, total: Int) = biggest >= total * biggestLandmass.start && biggest <= total * biggestLandmass.endInclusive
}

object FractalLandRecipes {
    /** Continents.lua: `ContinentsFractalWorld:GeneratePlotTypes` */
    val continents = FractalLandRecipe(
        script = "Continents.lua", mapType = MapType.continentsLikeCivV,
        waterPercentLow = 67, waterPercentNormal = 72, waterPercentHigh = 76,
        extraMountains = 0, platesMultiplier = 1.0, tectonicIslands = false, centreRift = true,
        biggestLandmass = 0.0..0.58, grainOnLowRoll = 2, poleShift = false
    )

    /** Pangaea.lua: `PangaeaFractalWorld:GeneratePlotTypes` */
    val pangaea = FractalLandRecipe(
        script = "Pangaea.lua", mapType = MapType.pangaeaLikeCivV,
        waterPercentLow = 63, waterPercentNormal = 68, waterPercentHigh = 73,
        extraMountains = 6, platesMultiplier = 1.3, tectonicIslands = true, centreRift = false,
        biggestLandmass = 0.84..1.0, grainOnLowRoll = 1, poleShift = true
    )

    val all = listOf(continents, pangaea)

    /** The recipe of a map type, null for the types that do not use one */
    fun forType(mapType: String) = all.firstOrNull { it.mapType == mapType }
}
