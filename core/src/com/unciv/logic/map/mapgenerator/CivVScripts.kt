package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 *  What a Civ V map script of the layered family sets apart from the generic pipeline of [CivVTerrainGenerator] and [CivVRiverGenerator]:
 *  the grid it makes the map on (`GetMapInitData`), its latitude function, whether it has ice, its way with mountains on the coast, its river code.
 *  The map types of the stock scripts that need none of that have no entry (they run the pipeline as it is).
 */
class CivVScript(
    /** The grid (width, height) of each world size that `GetMapInitData` returns, which is also the world size the chosen size is mapped to; null: the size is the player's */
    val grids: Map<CivVWorldSize, Pair<Int, Int>>? = null,
    /** `TerrainGenerator:GetLatitudeAtPlot` after the variation fractal and the clamp, and `FeatureGenerator:GetLatitudeAtPlot`: 0 at the equator, 1 at the poles */
    val latitude: (Double) -> Double = { it },
    /** `FeatureGenerator:AddIceAtPlot` (the two scripts replace it with nothing) */
    val ice: Boolean = true,
    /** `featuregen:AddFeatures(false)`: the mountains on the coast become hills even where the ruleset does not ask for it */
    val mountainsOffTheCoast: Boolean = false,
    val rivers: RiverCode = RiverCode.Default
) {
    /** Which `GetRiverValueAtPlot` (and `AddRivers`) the script has */
    enum class RiverCode {
        Default,
        /** Four_Corners: a plot with a neighbour off the map is worth only what the neighbours after it are (the sum restarts), so rivers run for the edge */
        FourCorners,
        /** InlandSea: the value grows with the distance to the centre (times 0 to 2), and `AddRivers` keeps the sources off the edge and gives each river a heading towards the centre */
        InlandSea
    }

    /** The world size that has a grid of this [width] (the grid has been made by [grids]); null if the script has none */
    fun worldSizeOfGrid(width: Int): CivVWorldSize? = grids?.entries?.firstOrNull { it.value.first == width }?.key
}

/** The layered-family scripts that are ported as map types, by type name (Four_Corners.lua and InlandSea.lua) */
object CivVScripts {
    val fourCorners = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (16 to 16), CivVWorldSize.Tiny to (24 to 24), CivVWorldSize.Small to (30 to 30),
            CivVWorldSize.Standard to (36 to 36), CivVWorldSize.Large to (44 to 44), CivVWorldSize.Huge to (56 to 56)
        ),
        latitude = { 0.8 * it },
        ice = false,
        rivers = CivVScript.RiverCode.FourCorners
    )

    val inlandSea = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (24 to 16), CivVWorldSize.Tiny to (32 to 20), CivVWorldSize.Small to (40 to 24),
            CivVWorldSize.Standard to (52 to 32), CivVWorldSize.Large to (64 to 40), CivVWorldSize.Huge to (84 to 52)
        ),
        latitude = { 0.07 + 0.52 * it },
        ice = false,
        mountainsOffTheCoast = true,
        rivers = CivVScript.RiverCode.InlandSea
    )

    /** What every other type runs: the generic pipeline */
    val standard = CivVScript()

    fun of(type: String): CivVScript = when (type) {
        MapType.fourCornersLikeCivV -> fourCorners
        MapType.inlandSeaLikeCivV -> inlandSea
        else -> standard
    }

    /**
     *  `GetMapInitData`: the parameters of a map for a script that sets its own grid, or null if [parameters]'s type does not. The size the player chose
     *  (the number of tiles, Auto's included) says the world size (`CivVWorldSize.forTiles`) and the script's grid for that world size is the size of
     *  the map: a rectangle of Civ V's pointy hexes without world wrap, whatever shape and wrap were asked for. The parameters
     *  are copied, the player's are left as they are.
     */
    fun withOwnGrid(parameters: MapParameters): MapParameters? {
        val grids = of(parameters.type).grids ?: return null
        val (width, height) = grids.getValue(CivVWorldSize.forTiles(parameters.numberOfTiles()))
        return parameters.clone().apply {
            shape = MapShape.rectangular
            worldWrap = false
            mapSize = MapSize(width, height)
        }
    }
}

/** The Four_Corners option "buffer zones": what lies in the two strips through the middle */
enum class FourCornersBuffer { Ocean, Mountains, Random }

/**
 *  The land of the two scripts of the layered family that are ported, written as their `MultilayeredFractal:GeneratePlotsByRegion` is:
 *  whole-map shapes laid by hand on an all-land array and one [CivVLayeredWorld.applyTectonics]. Neither calls a fractal layer, so the
 *  layer operations of [CivVLayeredWorld] are not exercised by them. Both set the grid ([CivVScripts.withOwnGrid]) and make no world wrap.
 *
 *  What the scripts do that is not what they say, and what is done here (the effective behaviour is ported):
 *  * Four_Corners calls `GenerateCoasts({bExpandCoasts = false})` and InlandSea `GenerateCoasts()`: the function reads `args.bExpandCoasts or true`, which is
 *    true whatever is passed, so both expand the coasts (the generic coast step of the pipeline does).
 *  * Four_Corners builds `args = {method = 2, resources = res}` and then calls `GenerateRegions()` without it: the region method is the default (2, by
 *    continent: the corners are one landmass, so one region division) and the resource option is not passed (the standard setting; Vox Populi passes it).
 *    InlandSea passes both (method 1, the biggest landmass: here the regions go by continent, and the islands of the sea are too small to take a civilization).
 *  * Four_Corners calls `AddFeatures()` without the argument that keeps mountains off the coast, InlandSea `AddFeatures(false)`: the ruleset constant
 *    `noMountainsOnTheCoast` (Vox Populi reads the missing argument as false) decides for Four Corners, InlandSea always takes them off.
 *  * `table.fill` is called with three arguments (the table first) in MultilayeredFractal and with two in the scripts (`table.fill(value, count)`): the engine's
 *    function is not among the files we have, so what a three-argument call does is not verified; [CivVLayeredWorld] fills the region with ocean as the call is meant to.
 *  * The Four_Corners option "Team Setting" is not read anywhere in the script (nothing to port). The option "buffer zones" has no place in Unciv's map
 *    parameters: [fourCorners] has it, the map type uses the script's default (ocean).
 */
object CivVLayeredScripts {
    fun generate(
        type: String,
        geometry: HexGeometry,
        wrapX: Boolean,
        rng: Random,
        worldAge: Int,
        buffer: FourCornersBuffer = FourCornersBuffer.Ocean
    ): CivVLayeredWorld.Result {
        val script = CivVScripts.of(type)
        val worldSize = script.worldSizeOfGrid(geometry.width) ?: CivVWorldSize.forTiles(geometry.width * geometry.height)
        val world = CivVLayeredWorld(geometry, wrapX, rng, worldSize)
        when (type) {
            MapType.fourCornersLikeCivV -> fourCorners(world, rng, worldAge, buffer)
            MapType.inlandSeaLikeCivV -> inlandSea(world, rng, worldAge)
            else -> throw IllegalArgumentException("$type is not a layered Civ V script")
        }
        return world.result()
    }

    /**
     *  Four_Corners.lua: land everywhere but two strips of sea, four plots wide, through the middle of the map (columns
     *  width / 2 - 2 to + 1 and the same rows), hills and mountains by [CivVLayeredWorld.applyTectonics] (the strips stay sea), then either nothing or
     *  [FourCornersBuffer.Mountains] in the strips (the option's second value; the first, ocean, is its default; Random rolls one of the two), and
     *  at the very end the bridge: flat land (no hills, no mountains) in the disc of radius 5.5 (6.5 with the mountain buffer) around the centre, which
     *  joins the four corners into one landmass.
     */
    fun fourCorners(world: CivVLayeredWorld, rng: Random, worldAge: Int, buffer: FourCornersBuffer) {
        val width = world.width
        val height = world.height
        fun strips(plot: Int) {
            for (y in 0 until height) for (x in width / 2 - 2..width / 2 + 1) world.setPlot(x, y, plot)
            for (x in 0 until width) for (y in height / 2 - 2..height / 2 + 1) world.setPlot(x, y, plot)
        }
        world.fill(CivVLayeredWorld.LAND)
        strips(CivVLayeredWorld.OCEAN)
        world.applyTectonics(worldAge)

        val mountains = when (buffer) {
            FourCornersBuffer.Ocean -> false
            FourCornersBuffer.Mountains -> true
            FourCornersBuffer.Random -> rng.nextInt(2) == 1
        }
        if (mountains) strips(CivVLayeredWorld.MOUNTAIN)

        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val axis = if (mountains) 6.5 else 5.5
        val axisSquared = axis * axis
        for (x in 0 until width) for (y in 0 until height) {
            val dx = x - centerX
            val dy = y - centerY
            if (dx * dx / axisSquared + dy * dy / axisSquared <= 1) world.setPlot(x, y, CivVLayeredWorld.LAND)
        }
    }

    /**
     *  InlandSea.lua: land everywhere, then the sea: a fractal (grain 1 or 2, its edges at zero so it leaves a rim of land) on the rectangle from 18 % to
     *  82 % of the width and 28 % to 72 % of the height, whose cells at or above the 47th percentile are sea, and an ellipse (half a quarter of the width
     *  by 0.35 of half the height) at the centre that is sea, so there is one body of water; then [CivVLayeredWorld.applyTectonics].
     */
    fun inlandSea(world: CivVLayeredWorld, rng: Random, worldAge: Int) {
        val width = world.width
        val height = world.height
        world.fill(CivVLayeredWorld.LAND)

        val westX = floor(width * 0.18).toInt() - 1
        val eastX = ceil(width * 0.82).toInt() - 1
        val seaWidth = eastX - westX
        val southY = floor(height * 0.28).toInt() - 1
        val northY = ceil(height * 0.72).toInt() - 1
        val seaHeight = northY - southY
        val grain = 1 + rng.nextInt(2)
        val sea = CivVFractal(seaWidth, seaHeight, grain, false, false, rng, polar = true)
        val seaThreshold = sea.getHeightFromPercent(47)
        for (regionY in 0 until seaHeight) for (regionX in 0 until seaWidth) {
            val x = regionX + westX
            val y = regionY + southY
            if (sea.getHeight(regionX, regionY) >= seaThreshold && x in 0 until width && y in 0 until height)
                world.setPlot(x, y, CivVLayeredWorld.OCEAN)
        }

        val centerX = width / 2.0 - 1
        val centerY = height / 2.0 - 1
        val xAxis = centerX / 2
        val yAxis = centerY * 0.35
        for (x in 0 until width) for (y in 0 until height) {
            val dx = x - centerX
            val dy = y - centerY
            if (dx * dx / (xAxis * xAxis) + dy * dy / (yAxis * yAxis) <= 1) world.setPlot(x, y, CivVLayeredWorld.OCEAN)
        }
        world.applyTectonics(worldAge)
    }

    /** The distance of a cell to the centre of the map as InlandSea's `GetRiverValueAtPlot` measures it (|x - w / 2| + |y - h / 2|) */
    fun distanceToCentre(x: Int, y: Int, width: Int, height: Int) = abs(x - width / 2.0) + abs(y - height / 2.0)
}
