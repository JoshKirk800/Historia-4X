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
    /** The continent grain when the script's roll of 7 comes out below 4, the other rolls get the other of 1 and 2 (Continents 2, Pangaea 1).
     *  Not used with a [fixedGrain]. */
    val grainOnLowRoll: Int,
    /** After the land is shifted, the landmass that was the biggest is pushed towards a randomly chosen pole, by 30 % to 100 % of the rows
     *  between it and the edge, an even number (Pangaea: against jungle dominating the map) */
    val poleShift: Boolean,
    /** The continent grain the script passes to `InitFractal` (Small Continents 3, Archipelago 4, Tiny Islands 5, Fractal 2: the default of `continent_grain`).
     *  A script that gives one rolls neither the grain nor the rift grain: its fractal has no rifts. null: the script rolls both ([grainOnLowRoll]). */
    val fixedGrain: Int? = null,
    /** The scripts' own `expansion_diceroll_table` for `GenerateCoasts` (Archipelago and Tiny Islands: 10, 4, 4); null: the call has no argument and the
     *  ruleset's dice are right. Only used where the ruleset widens the coast like Civ V ([com.unciv.models.ModConstants.coastExpansionDice] not empty). */
    val coastDice: List<Int>? = null,
    /** Whether the script offers the sea level and world age options. Fractal.lua has no options: its sea level is the normal one and its world age 4 billion years */
    val offersWorldOptions: Boolean = true
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

    /** SmallContinents.lua: `fractal_world:InitFractal{continent_grain = 3}`, no retry, no centre rift, no pole push; the sea level of the option 69/75/80 */
    val smallContinents = FractalLandRecipe(
        script = "SmallContinents.lua", mapType = MapType.smallContinentsLikeCivV,
        waterPercentLow = 69, waterPercentNormal = 75, waterPercentHigh = 80,
        extraMountains = 10, platesMultiplier = 1.5, tectonicIslands = true, centreRift = false,
        biggestLandmass = 0.0..1.0, grainOnLowRoll = 0, poleShift = false, fixedGrain = 3
    )

    /** Archipelago.lua (its only branch, the one the plan calls Islands): continent grain 4, `GenerateCoasts{expansion_diceroll_table = {10, 4, 4}}` */
    val archipelago = FractalLandRecipe(
        script = "Archipelago.lua", mapType = MapType.archipelagoLikeCivV,
        waterPercentLow = 72, waterPercentNormal = 78, waterPercentHigh = 83,
        extraMountains = 10, platesMultiplier = 2.0, tectonicIslands = true, centreRift = false,
        biggestLandmass = 0.0..1.0, grainOnLowRoll = 0, poleShift = false, fixedGrain = 4, coastDice = listOf(10, 4, 4)
    )

    /** TinyIslands.lua: continent grain 5, the same coast dice as Archipelago */
    val tinyIslands = FractalLandRecipe(
        script = "TinyIslands.lua", mapType = MapType.tinyIslandsLikeCivV,
        waterPercentLow = 73, waterPercentNormal = 78, waterPercentHigh = 82,
        extraMountains = 15, platesMultiplier = 2.5, tectonicIslands = true, centreRift = false,
        biggestLandmass = 0.0..1.0, grainOnLowRoll = 0, poleShift = false, fixedGrain = 5, coastDice = listOf(10, 4, 4)
    )

    /** Fractal.lua: `InitFractal{rift_grain = -1, has_center_rift = false, polar = true}` (the continent grain is the default, 2) and `GeneratePlotTypes`
     *  with its defaults: sea level 65/72/78 (no option: always the normal 72), no extra mountains, plates x1, no tectonic islands */
    val fractal = FractalLandRecipe(
        script = "Fractal.lua", mapType = MapType.fractalLikeCivV,
        waterPercentLow = 65, waterPercentNormal = 72, waterPercentHigh = 78,
        extraMountains = 0, platesMultiplier = 1.0, tectonicIslands = false, centreRift = false,
        biggestLandmass = 0.0..1.0, grainOnLowRoll = 0, poleShift = false, fixedGrain = 2, offersWorldOptions = false
    )

    val all = listOf(continents, pangaea, smallContinents, archipelago, tinyIslands, fractal)

    /** The recipe of a map type, null for the types that do not use one */
    fun forType(mapType: String) = all.firstOrNull { it.mapType == mapType }
}
