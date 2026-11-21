package com.unciv.logic.map.mapgenerator

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 *  The land of the two "regional" scripts of Civ V that make their plots in the script itself, with fractals and ridges and no layer of
 *  MultilayeredFractal: Highlands.lua (`GeneratePlotTypes`) and Boreal.lua (`GeneratePlotTypes`). Both fill the map with land first and cut sea, hills and
 *  mountains out of it; they are written on a [CivVLayeredWorld] (the plot array, the ridge field and the percentile it keeps), and neither calls its layers.
 *
 *  What both do that is not what the code says, and what is done here:
 *  * The fractals are made without flags and the scripts build ridges into them (`BuildRidges`) on the fractal's own 128 x 64 nodes; here the ridge field is the map's
 *    cells ([CivVLayeredWorld.ridgeField], as [CivVLayeredWorld.applyTectonics] has it): the Voronoi regions follow the real hexes and the percentile is of the cells.
 *    Neither script wraps, so the ridges are not biased.
 *  * `PlotTypes.PLOT_PEAK` (Highlands) is a name the game does not define: the plot is nil, read as a mountain (the same assumption as in the layered engine).
 *  * `GenerateCoasts({bExpandCoasts = false})` (Highlands) reads `args.bExpandCoasts or true`: the coasts are expanded, as the generic step does.
 */
object CivVRegionalScripts {

    /** A field of the map's cells with its own percentile: a fractal as it is, or one the ridges have been built into */
    private class Field(private val world: CivVLayeredWorld, private val fractal: CivVFractal, private val ridged: Array<IntArray>?) {
        fun at(x: Int, y: Int) = ridged?.get(x)?.get(y) ?: fractal.getHeight(x, y)
        fun threshold(percent: Int) = if (ridged != null) world.percent(ridged, percent) else fractal.getHeightFromPercent(percent)
    }

    private fun fractalField(world: CivVLayeredWorld, rng: Random, grain: Int, fracXExp: Int = CivVFractal.DEFAULT_X_EXP, fracYExp: Int = CivVFractal.DEFAULT_Y_EXP) =
        CivVFractal(world.width, world.height, grain, false, false, rng, fracXExp = fracXExp, fracYExp = fracYExp)

    private fun ridged(world: CivVLayeredWorld, fractal: CivVFractal, plates: Int, blendRidge: Int, blendFract: Int) =
        Field(world, fractal, world.ridgeField(fractal, plates, false, blendRidge, blendFract))

    /**
     *  Highlands.lua: all land, then the lakes, the hills and the peaks. Three fractals of the map (the terrain's, the lakes' and the mountain passes') whose
     *  grains come from the script's options, here the defaults, all three random: the mountain pattern (a grain by world size, from a table the options
     *  index in the wrong order, see below), the mountain density (peaks from the 70th, 77th or 83rd percentile of the terrain fractal, hills from the 40th, 45th or 50th)
     *  and the lakes (the top 8, 10 or 15 % of a fractal of grain 5, 4 or 3). Ridges are built into the passes' fractal (1 of ridge to 2 of fractal), and for the
     *  first pattern (ridgelines) into the terrain's as well (6 to 1), with the plates of the world size, doubled for ridgelines and ranges.
     *  A cell is a lake, or a peak (a hill if the passes' field is at or above its 85th percentile: a pass through the range), or a hill, or flat land.
     *
     *  Bug carried: the grain table is `{ridgelines, clustered, ranges, scattered}` but the options are listed ridgelines, scattered, ranges, clusters, so the option
     *  "scattered" gets the grain of clusters and "clusters" that of scattered (the plate multipliers `{2, 1, 2, 1}` fit the option order). With a random draw it makes no
     *  difference to the maps, only to the names; a fixed [pattern] (1 to 4, the option's index) is read as the script reads it.
     *
     *  Nothing is left at the edge: there is no sea round the map. The script also rolls the cold pole (north or south), see [CivVScript.Climate.ColdPole]; its
     *  `CanPlaceCityStateAt` keeps city-states off the outer ring (Vox Populi's own start code does the same, and Vox Populi deletes the override).
     */
    fun highlands(world: CivVLayeredWorld, rng: Random, worldSize: CivVWorldSize, pattern: Int? = null, density: Int? = null, lakeSize: Int? = null) {
        val width = world.width
        val height = world.height
        world.fill(CivVLayeredWorld.LAND)

        val patternOption = pattern ?: (1 + rng.nextInt(4))
        val peakOption = density ?: (1 + rng.nextInt(3))
        val lakeOption = lakeSize ?: (1 + rng.nextInt(3))

        // {ridgelines, clustered, ranges, scattered}
        val grains = when (worldSize) {
            CivVWorldSize.Duel, CivVWorldSize.Tiny -> intArrayOf(4, 5, 4, 3)
            else -> intArrayOf(5, 6, 5, 4)
        }
        val grain = grains[patternOption - 1]
        val peaks = intArrayOf(70, 77, 83)[peakOption - 1]
        val hills = intArrayOf(40, 45, 50)[peakOption - 1]
        val lakes = intArrayOf(8, 10, 15)[lakeOption - 1]
        val lakeGrain = intArrayOf(5, 4, 3)[lakeOption - 1]
        val plates = ceil(worldSize.plates * intArrayOf(2, 1, 2, 1)[patternOption - 1].toDouble()).toInt()

        val terrainFractal = fractalField(world, rng, grain)
        val lakesFractal = fractalField(world, rng, lakeGrain)
        val passFractal = fractalField(world, rng, grain)
        val passes = ridged(world, passFractal, plates, 1, 2)
        val terrain = if (patternOption == 1) ridged(world, terrainFractal, plates, 6, 1) else Field(world, terrainFractal, null)
        val lakesField = Field(world, lakesFractal, null)

        val lakesThreshold = lakesField.threshold(100 - lakes)
        val hillsThreshold = terrain.threshold(hills)
        val peaksThreshold = terrain.threshold(peaks)
        val passThreshold = passes.threshold(85)

        for (x in 0 until width) for (y in 0 until height) {
            val value = terrain.at(x, y)
            val plot = when {
                lakesField.at(x, y) >= lakesThreshold -> CivVLayeredWorld.OCEAN
                value >= peaksThreshold -> if (passes.at(x, y) >= passThreshold) CivVLayeredWorld.HILLS else CivVLayeredWorld.MOUNTAIN
                value >= hillsThreshold -> CivVLayeredWorld.HILLS
                else -> CivVLayeredWorld.LAND
            }
            world.setPlot(x, y, plot)
        }
    }

    /**
     *  Boreal.lua: land, with a sea along the top (north) edge whose rim is a shallow arc: at each column `floor(h / 10 - |x - w / 2| / (w / 2) x h / 10)` rows (up to
     *  a tenth of the height in the middle, none at the sides) plus 0 to 3 for a rough coast, from row h - 2 down; the top row is always sea. Lakes in the top 6 % of a
     *  fractal of grain 2; then hills and mountains from two ridge fields as the layered engine's tectonics make them, with the script's own thresholds: mountains from
     *  the (95 - adjustment) percentile of the mountains' field (a hill where the hills' field is at or above its 85th: a pass), hills from the (88 - 2 x adjustment)
     *  percentile and in the hills' field's two bands (28 and 72 percent, give or take the adjustment). The adjustment is 2 (old), 3 or 5 (young) with 0.75 or 1.5 times
     *  the plates; the mountains' field has 0.75 of the hills' plates; both fractals have the grain of the world size (3, 3, 4, 4, 5, 5).
     *
     *  Bug carried: `iHillsClumps` (the bottom `1 + adjustment` percent of the mountains' field) is computed and used nowhere.
     */
    fun boreal(world: CivVLayeredWorld, rng: Random, worldAge: Int, worldSize: CivVWorldSize) {
        val width = world.width
        val height = world.height
        world.fill(CivVLayeredWorld.LAND)

        for (x in 0 until width) {
            val yShift = floor(height / 10.0 - abs(x - width / 2.0) / (width / 2.0) * (height / 10.0)).toInt()
            val startY = height - 2 - yShift - rng.nextInt(4)
            for (y in maxOf(startY, 0) until height) world.setPlot(x, y, CivVLayeredWorld.OCEAN)
        }

        var plateFactor = 1.0
        val adjustment = when {
            worldAge <= 3 -> { plateFactor = 1.5; 5 }
            worldAge == 4 -> 3
            else -> { plateFactor = 0.75; 2 }
        }
        val hillsBottom1 = 28 - adjustment
        val hillsTop1 = 28 + adjustment
        val hillsBottom2 = 72 - adjustment
        val hillsTop2 = 72 + adjustment
        val hillsNearMountains = 88 - adjustment * 2
        val mountains = 95 - adjustment
        val plates = worldSize.plates * plateFactor

        val lakesField = Field(world, fractalField(world, rng, 2), null)
        val hillsFractal = fractalField(world, rng, worldSize.continentsGrain)
        val mountainsFractal = fractalField(world, rng, worldSize.continentsGrain)
        val hills = ridged(world, hillsFractal, plates.toInt(), 1, 2)
        val mountainsField = ridged(world, mountainsFractal, (plates * 0.75).toInt(), 6, 1)

        val lakesThreshold = lakesField.threshold(94)
        val bottom1 = hills.threshold(hillsBottom1)
        val top1 = hills.threshold(hillsTop1)
        val bottom2 = hills.threshold(hillsBottom2)
        val top2 = hills.threshold(hillsTop2)
        val nearMountains = mountainsField.threshold(hillsNearMountains)
        val mountainThreshold = mountainsField.threshold(mountains)
        val passThreshold = hills.threshold(85)

        for (x in 0 until width) for (y in 0 until height) {
            if (world.plotAt(x, y) != CivVLayeredWorld.LAND) continue
            if (lakesField.at(x, y) >= lakesThreshold) { world.setPlot(x, y, CivVLayeredWorld.OCEAN); continue }
            val mountainValue = mountainsField.at(x, y)
            val hillValue = hills.at(x, y)
            when {
                mountainValue >= mountainThreshold ->
                    world.setPlot(x, y, if (hillValue >= passThreshold) CivVLayeredWorld.HILLS else CivVLayeredWorld.MOUNTAIN)
                mountainValue >= nearMountains -> world.setPlot(x, y, CivVLayeredWorld.HILLS)
                hillValue in bottom1..top1 || hillValue in bottom2..top2 -> world.setPlot(x, y, CivVLayeredWorld.HILLS)
            }
        }
    }
}
