package com.unciv.logic.map.mapgenerator

import kotlin.random.Random

/** The flags the scripts hand to `Fractal.Create`: whether the field wraps along x or y and whether its edges that do not wrap are zero */
data class CivVFractalFlags(val wrapX: Boolean = false, val wrapY: Boolean = false, val polar: Boolean = false) {
    companion object {
        /** MultilayeredFractal.Create: `iRoundFlags`, no flags */
        val round = CivVFractalFlags()
        val horizontal = CivVFractalFlags(wrapX = true)
        val vertical = CivVFractalFlags(wrapY = true)
        val both = CivVFractalFlags(wrapX = true, wrapY = true)
        val polarEdges = CivVFractalFlags(polar = true)
    }
}

/**
 *  The arguments of one layer, the names and defaults of the `args` of MultilayeredFractal:GenerateFractalLayer (and the two layers
 *  that share them). The rectangle is [regionWidth] x [regionHeight] cells with its south-west cell at ([westX], [southY]).
 *
 *  `bShift` is not an argument: the originals read `args.bShift or true`, which is true whatever is passed, so every layer is shifted.
 *  [worldAge] is in billions of years as in [MapParameters][com.unciv.logic.map.MapParameters] (3 young, 4 normal, 5 old; the scripts' option 1, 2, 3).
 */
class CivVLayerArgs(
    val regionWidth: Int,
    val regionHeight: Int,
    val westX: Int,
    val southY: Int,
    /** The share of the region's fractal that is sea; for a water layer the share that is left unchanged */
    val waterPercent: Int = 55,
    val grain: Int = 1,
    val hillsGrain: Int = 3,
    val flags: CivVFractalFlags = CivVFractalFlags.round,
    val fracXExp: Int = 6,
    val fracYExp: Int = 5,
    /** 1 to 3 cuts rifts (`Fractal.CreateRifts`); anything else none */
    val riftGrain: Int = -1,
    val worldAge: Int = 4
)

/**
 *  MultilayeredFractal.lua: the plots of the whole map are a land/water array that the operations of a script change one after the other
 *  ([fractalLayer], [fractalLayerWithoutHills], [waterLayer], any plot the script sets itself, and one [applyTectonics] at the end for hills and
 *  mountains); the script's own code only builds the list. It starts all sea.
 *
 *  A layer is a fractal on a rectangle of its own ([CivVLayerArgs]) that is shifted so the rectangle's edge runs through its emptiest
 *  columns and rows ([shiftRegionPlots]), and then laid on the map: its land overwrites what is there, its sea is ignored (a water layer is the opposite:
 *  its land is the sea it cuts into the map).
 *
 *  What the originals do and this port carries on purpose (each found in the source, none of them looks intended):
 *  * `bShift = args.bShift or true` is true whatever the script passes (every layer is shifted);
 *  * `ShiftRegionPlotsBy` reads and writes the region's array, whose first element is 1, with indices that start at 0, and runs one column and
 *    one row too far ([CivVRegionShift.shiftRegionPlotsBy] does the same): the shift is right except in two columns, where a cell takes the cell of
 *    the row before or after, and for one cell that reads the empty element 0 (a nil);
 *  * the peaks of a layer with hills are `PlotTypes.PLOT_PEAK`, a name the game does not define, so the plot is nil: it counts as land
 *    in the shift and is written to the map as nil, which the game reads as 0, a mountain (`[INFERENCE]`, see mapgen-plan.md section 10);
 *  * [applyTectonics] computes the fractal grain of the world size (3, 3, 4, 4, 5, 5) and does not use it: the fractals use `grain_amount`, 3 at every size.
 *
 *  Cells outside the map are not written (the originals would write into the next row).
 *
 *  @param geometry the grid of the map (the ridges of the tectonics follow its steps), [wrapX] whether the map wraps (the flags of the hills
 *  and mountains fractals, `Map.GetFractalFlags`), [worldSize] the world size the player chose (the number of plates and nothing else)
 */
class CivVLayeredWorld(
    private val geometry: HexGeometry,
    private val wrapX: Boolean,
    private val rng: Random,
    private val worldSize: CivVWorldSize,
    /** The least distance in cells between two seeds of a ridge field: `CvFractal::ridgeBuilder` keeps 7 nodes of its 128 x 64 apart, see [nodeSeedDistance] */
    private val seedDistance: Int = MIN_SEED_DISTANCE
) {
    companion object {
        const val MIN_SEED_DISTANCE = 7

        /**
         *  The 7 nodes of the original's ridge builder, which lays its seeds on 128 columns of nodes whatever the size of the map, as cells of a map [width] cells wide:
         *  about 0.05 cells per node column, at least 1. A grid of 16 or 24 columns (the small scripts) has no room for six seeds 7 cells apart.
         */
        fun nodeSeedDistance(width: Int) = maxOf(1, Math.round(MIN_SEED_DISTANCE * width / (1 shl CivVFractal.DEFAULT_X_EXP).toDouble()).toInt())

        /** PlotTypes: mountain 0, hills 1, land 2, ocean 3 */
        const val MOUNTAIN = 0
        const val HILLS = 1
        const val LAND = 2
        const val OCEAN = 3

        private const val NIL = CivVRegionShift.NIL

        /** World age 1 (3 billion years, young) .. 3 (5 billion, old) of the scripts' option from the age in billions of years */
        private fun ageOption(worldAge: Int) = when { worldAge <= 3 -> 1; worldAge == 4 -> 2; else -> 3 }
    }

    val width = geometry.width
    val height = geometry.height

    /** The plots of the map, `y * width + x` */
    private val world = IntArray(width * height) { OCEAN }

    /** The region being built, as the Lua array: the cell (x, y) is element `y * regionWidth + x + 1`, element 0 is empty */
    private var region = IntArray(0)

    fun plotAt(x: Int, y: Int) = world[y * width + x]

    fun setPlot(x: Int, y: Int, plot: Int) {
        world[y * width + x] = plot
    }

    /** `table.fill(PlotTypes.PLOT_LAND, iW * iH)`: every plot the same */
    fun fill(plot: Int) = world.fill(plot)

    class Result(val land: Array<BooleanArray>, val hills: Array<BooleanArray>, val mountains: Array<BooleanArray>)

    fun result() = Result(
        Array(width) { x -> BooleanArray(height) { y -> plotAt(x, y) != OCEAN } },
        Array(width) { x -> BooleanArray(height) { y -> plotAt(x, y) == HILLS } },
        Array(width) { x -> BooleanArray(height) { y -> plotAt(x, y) == MOUNTAIN } }
    )

    // region Layers

    /** The land/water fractal of a layer: with rifts of their own if [CivVLayerArgs.riftGrain] is 1 to 3 */
    private fun continentsFractal(args: CivVLayerArgs): CivVFractal {
        val w = args.regionWidth
        val h = args.regionHeight
        val rifts = if (args.riftGrain in 1..3) CivVFractal(w, h, args.riftGrain, false, false, rng, fracXExp = args.fracXExp, fracYExp = args.fracYExp) else null
        return CivVFractal(w, h, args.grain, args.flags.wrapX, args.flags.wrapY, rng, args.flags.polar, rifts, args.fracXExp, args.fracYExp)
    }

    /** GenerateFractalLayer: land, hills and mountains from fractals of the region (the fractals of the hills wrap as the map does, not as the layer's flags say) */
    fun fractalLayer(args: CivVLayerArgs) {
        region = IntArray(args.regionWidth * args.regionHeight + args.regionWidth + 2) { OCEAN }
        val continents = continentsFractal(args)
        val hills = CivVFractal(args.regionWidth, args.regionHeight, args.hillsGrain, wrapX, false, rng, fracXExp = args.fracXExp, fracYExp = args.fracYExp)
        val peaks = CivVFractal(args.regionWidth, args.regionHeight, args.hillsGrain + 1, wrapX, false, rng, fracXExp = args.fracXExp, fracYExp = args.fracYExp)

        val (adjustment, mountains) = when (ageOption(args.worldAge)) { 3 -> 3 to 25; 1 -> 8 to 35; else -> 5 to 30 }
        val waterThreshold = continents.getHeightFromPercent(args.waterPercent)
        val hillsBottom1 = hills.getHeightFromPercent(25 - adjustment)
        val hillsTop1 = hills.getHeightFromPercent(25 + adjustment)
        val hillsBottom2 = hills.getHeightFromPercent(75 - adjustment)
        val hillsTop2 = hills.getHeightFromPercent(75 + adjustment)
        val peakThreshold = peaks.getHeightFromPercent(mountains)

        for (x in 0 until args.regionWidth) for (y in 0 until args.regionHeight) {
            if (continents.getHeight(x, y) <= waterThreshold) continue
            val hillValue = hills.getHeight(x, y)
            region[y * args.regionWidth + x + 1] =
                if (hillValue in hillsBottom1..hillsTop1 || hillValue in hillsBottom2..hillsTop2) {
                    if (peaks.getHeight(x, y) <= peakThreshold) NIL else HILLS
                } else LAND
        }
        CivVRegionShift.shiftRegionPlots(region, args.regionWidth, args.regionHeight)
        layOnWorld(args, water = false)
    }

    /** GenerateFractalLayerWithoutHills: the land only, for a script that follows its layers with [applyTectonics] */
    fun fractalLayerWithoutHills(args: CivVLayerArgs) {
        region = IntArray(args.regionWidth * args.regionHeight + args.regionWidth + 2) { OCEAN }
        landOnly(args)
        CivVRegionShift.shiftRegionPlots(region, args.regionWidth, args.regionHeight)
        layOnWorld(args, water = false)
    }

    /**
     *  GenerateWaterLayer: a sea in a larger land, or a bay. The layer is made like a land layer (its share of "water" is the share of the region
     *  left unchanged: the rest is the sea) and where it has land the map becomes ocean.
     */
    fun waterLayer(args: CivVLayerArgs) {
        region = IntArray(args.regionWidth * args.regionHeight + args.regionWidth + 2) { OCEAN }
        landOnly(args)
        CivVRegionShift.shiftRegionPlots(region, args.regionWidth, args.regionHeight)
        layOnWorld(args, water = true)
    }

    private fun landOnly(args: CivVLayerArgs) {
        val continents = continentsFractal(args)
        val waterThreshold = continents.getHeightFromPercent(args.waterPercent)
        for (x in 0 until args.regionWidth) for (y in 0 until args.regionHeight)
            if (continents.getHeight(x, y) > waterThreshold) region[y * args.regionWidth + x + 1] = LAND
    }

    /** The loop that ends every layer: land of the region overwrites the map (a water layer's land makes it ocean) */
    private fun layOnWorld(args: CivVLayerArgs, water: Boolean) {
        for (x in 0 until args.regionWidth) {
            val worldX = x + args.westX
            for (y in 0 until args.regionHeight) {
                val plot = region[y * args.regionWidth + x + 1]
                if (plot == OCEAN) continue
                val worldY = y + args.southY
                if (worldX !in 0 until width || worldY !in 0 until height) continue
                world[worldY * width + worldX] = if (water) OCEAN else if (plot == NIL) MOUNTAIN else plot
            }
        }
    }

    // endregion

    // region Tectonics

    /**
     *  ApplyTectonics: hills and mountains over the whole map from two ridge fields, as the Continents script does it ([CivVContinentsLandmass]):
     *  the land the layers made stays (only [tectonicIslands] adds land: along the ridges of the mountains field in the sea, a peak, hills or flat land).
     *  Land becomes a mountain where the mountains field is at or above its (97 - adjustment - [extraMountains]) percentile (a pass, hills, where the
     *  hills field is high there), hills in the foothills (above the 91 - 2 x adjustment - [extraMountains] percentile) and where the hills field is in its two bands
     *  (28 and 72 +- adjustment), flat land elsewhere. The age sets the adjustment (old 2, young 5, normal 3: `world_age_old`, `world_age_new`,
     *  `world_age_normal`) and the number of plates (x 0.75 old, x 1.5 young).
     *
     *  The ridges are biased (a weakness, noise and a favoured direction per seed) when the map wraps, as `Map.GetFractalFlags` says; on a map that does not
     *  wrap the flags are empty and the Voronoi diagram is plain.
     */
    fun applyTectonics(
        worldAge: Int = 4,
        extraMountains: Int = 0,
        grainAmount: Int = 3,
        adjustPlates: Float = 1f,
        tectonicIslands: Boolean = false,
        fracXExp: Int = CivVFractal.DEFAULT_X_EXP,
        fracYExp: Int = CivVFractal.DEFAULT_Y_EXP
    ) {
        var plateFactor = adjustPlates
        val adjustment = when (ageOption(worldAge)) {
            3 -> { plateFactor *= 0.75f; 2 }
            1 -> { plateFactor *= 1.5f; 5 }
            else -> 3
        }
        val hillsBottom1 = 28 - adjustment
        val hillsTop1 = 28 + adjustment
        val hillsBottom2 = 72 - adjustment
        val hillsTop2 = 72 + adjustment
        val hillsNearMountains = 91 - adjustment * 2 - extraMountains
        val mountains = 97 - adjustment - extraMountains
        val numPlates = worldSize.plates * plateFactor

        val hillsBase = CivVFractal(width, height, grainAmount, wrapX, false, rng, fracXExp = fracXExp, fracYExp = fracYExp)
        val mountainsBase = CivVFractal(width, height, grainAmount, wrapX, false, rng, fracXExp = fracXExp, fracYExp = fracYExp)
        val hillsField = ridgeField(hillsBase, numPlates.toInt(), wrapX, 1, 2)
        val mountainsField = ridgeField(mountainsBase, (numPlates * 2 / 3).toInt(), wrapX, 6, 1)

        val bottom1 = percent(hillsField, hillsBottom1)
        val top1 = percent(hillsField, hillsTop1)
        val bottom2 = percent(hillsField, hillsBottom2)
        val top2 = percent(hillsField, hillsTop2)
        val nearMountains = percent(mountainsField, hillsNearMountains)
        val mountainThreshold = percent(mountainsField, mountains)
        val passThreshold = percent(hillsField, hillsNearMountains)
        val mountain100 = percent(mountainsField, 100)
        val mountain99 = percent(mountainsField, 99)
        val mountain97 = percent(mountainsField, 97)
        val mountain95 = percent(mountainsField, 95)

        for (x in 0 until width) for (y in 0 until height) {
            val mountainValue = mountainsField[x][y]
            if (plotAt(x, y) == OCEAN) {
                if (!tectonicIslands) continue
                when (mountainValue) {
                    mountain100 -> setPlot(x, y, MOUNTAIN)
                    mountain99 -> setPlot(x, y, HILLS)
                    mountain97, mountain95 -> setPlot(x, y, LAND)
                }
                continue
            }
            val hillValue = hillsField[x][y]
            setPlot(x, y, when {
                mountainValue >= mountainThreshold -> if (hillValue >= passThreshold) HILLS else MOUNTAIN
                mountainValue >= nearMountains -> HILLS
                hillValue in bottom1..top1 || hillValue in bottom2..top2 -> HILLS
                else -> LAND
            })
        }
    }

    /** CvFractal::getHeightFromPercent on a field of the map's cells */
    fun percent(field: Array<IntArray>, percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        val total = width * height
        var lower = 0
        var upper = 255
        var estimate = 255 * p / 100
        while (estimate != lower) {
            var below = 0
            for (column in field) for (v in column) if (v < estimate) below++
            if (100 * below / total > p) { upper = estimate; estimate = (upper + lower) / 2 }
            else { lower = estimate; estimate = (upper + lower) / 2 }
        }
        return estimate
    }

    /**
     *  CvFractal::ridgeBuilder on the cells of [geometry] (as [CivVContinentsLandmass] does it): [numSeeds] (at least 3) random cells, each with a
     *  weakness, a favoured direction and its strength; a cell's value is how near its closest seed is compared to the second closest (255 x closest / second
     *  closest: low in the middle of a region, high on the borders between regions: ridges), blended with [base] as
     *  (ridge x [blendRidge] + base x [blendFract]) / (sum). [biased] adds the seeds' weakness, a little noise and the direction bias to the distance.
     */
    fun ridgeField(base: CivVFractal, numSeeds: Int, biased: Boolean, blendRidge: Int, blendFract: Int): Array<IntArray> {
        class Seed(val x: Int, val y: Int, val weakness: Int, val direction: Int, val strength: Int)
        val seeds = ArrayList<Seed>()
        repeat(maxOf(numSeeds, 3)) {
            var x = rng.nextInt(width)
            var y = rng.nextInt(height)
            val weakness = maxOf(0, rng.nextInt(7) - 3)
            val direction = rng.nextInt(6)
            val strength = maxOf(0, rng.nextInt(8) - 4)
            var tries = 0
            while (seeds.any { geometry.distance(x, y, it.x, it.y) < seedDistance } && tries++ < 200) { x = rng.nextInt(width); y = rng.nextInt(height) }
            seeds.add(Seed(x, y, weakness, direction, strength))
        }
        fun estimateDirection(dx: Double, dy: Double): Int {
            var best = 0.0
            var index = -1
            for ((i, d) in geometry.directions.withIndex()) {
                val dot = dx * d.first + dy * d.second
                if (dot > best) { best = dot; index = i }
            }
            return index
        }
        return Array(width) { x ->
            IntArray(height) { y ->
                val (cx, cy) = geometry.center(x, y)
                var closest = Int.MAX_VALUE
                var nextClosest = Int.MAX_VALUE
                for (seed in seeds) {
                    var distance = geometry.distance(x, y, seed.x, seed.y)
                    if (biased) {
                        distance += seed.weakness
                        distance += rng.nextInt(3)
                        val (sx, sy) = geometry.center(seed.x, seed.y)
                        val direction = estimateDirection(sx - cx, sy - cy)
                        if (direction == seed.direction) distance -= seed.strength
                        else if (direction == (seed.direction + 3) % 6) distance += seed.strength
                        distance = maxOf(1, distance)
                    }
                    if (distance < closest) { nextClosest = closest; closest = distance }
                    else if (distance < nextClosest) nextClosest = distance
                }
                // (two seeds on one cell, which the 200 tries leave on a map too small for them, would divide by zero: the original has no such case)
                val ridge = (255 * closest) / maxOf(nextClosest, 1)
                (ridge * blendRidge + base.getHeight(x, y) * blendFract) / maxOf(blendRidge + blendFract, 1)
            }
        }
    }

    // endregion
}

/**
 *  MultilayeredFractal:ShiftRegionPlots and its helpers on the region's array, as the Lua code has it: the cell (x, y) of a region [width] x [height] is
 *  element `y * width + x + 1` (element 0 is empty), a plot is [CivVLayeredWorld.OCEAN] or something else, and [NIL] is a nil, which counts as land.
 *  The array must have `width * height + width + 2` elements.
 */
object CivVRegionShift {
    const val NIL = -1

    /** ShiftRegionPlots: the group of columns, and the group of rows, with the least land (see [determineShift]) becomes the edge of the region */
    fun shiftRegionPlots(region: IntArray, width: Int, height: Int) {
        val shiftX = determineShift(region, width, height, columns = true)
        val shiftY = determineShift(region, width, height, columns = false)
        shiftRegionPlotsBy(region, shiftX, shiftY, width, height)
    }

    /**
     *  DetermineXShift (and DetermineYShift for rows): the land in each column is counted, a group is a column with the
     *  max(1, a tenth of the region) columns on each side of it (wrapping), and the first group with the least land (it must have less than all
     *  of it: a full region keeps the group of column 1) gives the shift, the 0-based index of its middle column.
     */
    fun determineShift(region: IntArray, width: Int, height: Int, columns: Boolean): Int {
        val lines = if (columns) width else height
        val across = if (columns) height else width
        val totals = IntArray(lines) { line ->
            (0 until across).count { other ->
                val x = if (columns) line else other
                val y = if (columns) other else line
                region[y * width + x + 1] != CivVLayeredWorld.OCEAN
            }
        }
        val radius = maxOf(1, lines / 10)
        var bestValue = across * (2 * radius + 1)
        var bestGroup = 1
        for (index in 1..lines) {
            var sum = 0
            for (current in index - radius..index + radius) {
                var i = Math.floorMod(current, lines)
                if (i == 0) i = lines
                sum += totals[i - 1]
            }
            if (sum < bestValue) { bestValue = sum; bestGroup = index }
        }
        return bestGroup - 1
    }

    /**
     *  ShiftRegionPlotsBy, literally: only when a shift is positive, the element at index `width * dy + dx` takes the one at `width * sy + sx`, with
     *  sx = (dx + xShift) mod width and sy = (dy + yShift) mod height, for dx up to the width and dy up to the height (both included).
     *  The indices start at 0 and the array's first cell is element 1: the cell (x, y) gets the cell (x + xShift, y + yShift) of the
     *  region, except in two columns: where the source column wraps it takes the cell of the row before, and in the last column the cell of the row
     *  after; the one cell that would read element 0 gets a nil.
     */
    fun shiftRegionPlotsBy(region: IntArray, xShift: Int, yShift: Int, width: Int, height: Int) {
        if (xShift <= 0 && yShift <= 0) return
        val buffer = IntArray(region.size) { NIL }
        for (i in 1..width * height + 1) buffer[i] = region[i]
        for (destY in 0..height) for (destX in 0..width) {
            val sourceX = (destX + xShift) % width
            val sourceY = (destY + yShift) % height
            region[width * destY + destX] = buffer[width * sourceY + sourceX]
        }
    }
}
