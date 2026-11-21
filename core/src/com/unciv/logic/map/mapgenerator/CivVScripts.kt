package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 *  What a Civ V map script of the layered family sets apart from the generic pipeline of [CivVTerrainGenerator] and [CivVRiverGenerator]:
 *  the grid it makes the map on (`GetMapInitData`, with its world wrap), its latitude function and where the poles are, whether it has ice and which, its way
 *  with mountains on the coast, its terrain and feature code (the `GenerateTerrainAtPlot` and `AddFeatures` it replaces), its river code.
 *  The map types of the stock scripts that need none of that have no entry (they run the pipeline as it is).
 *
 *  What the scripts here do not get from the generic pipeline and Unciv does not have: their own start code (North_vs_South and West_vs_East divide the map into
 *  two rectangles for two teams, `GenerateRegions` method 3; Donut asks `ChooseLocations` for starts on the coast, `mustBeCoast`; Lakes, Highlands and Boreal leave
 *  `PlaceOilInTheSea` empty, so no oil in the water, which Vox Populi keeps): the generic Vox Populi start code of the branch runs for all of them. The overrides of
 *  `CanPlaceCityStateAt`, the luxury weights of Boreal and the strategic quantities that the stock scripts have, Vox Populi deletes (mapgen-plan.md section 10).
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
    val rivers: RiverCode = RiverCode.Default,
    /** `WrapX` of `GetMapInitData`: whether the grid wraps east to west (only Lakes wraps) */
    val wrapX: Boolean = false,
    /** Where the poles are: the equator in the middle row, or (Highlands) the south edge, flipped by a coin */
    val climate: Climate = Climate.Equator,
    val terrain: TerrainCode = TerrainCode.Standard,
    val features: FeatureCode = FeatureCode.Standard,
    val iceCode: IceCode = IceCode.Standard,
    /** Boreal replaces `FeatureGenerator:AddFeatures`, which is the function that takes the mountains off the coast: they stay, whatever the ruleset says */
    val keepsCoastalMountains: Boolean = false,
    /** The seeds of the ridge fields are kept apart by the 7 nodes of the original on its 128 columns, in cells ([CivVLayeredWorld.nodeSeedDistance]), not by 7 cells: the grids of these scripts are 16 to 104 wide */
    val ridgesOnNodeScale: Boolean = false
) {
    /** Which `GetRiverValueAtPlot` (and `AddRivers`) the script has */
    enum class RiverCode(
        /** `AddRivers` skips the plots in the first column, the last two, the first two rows and the last one */
        val keepsSourcesOffTheEdge: Boolean = false
    ) {
        Default,
        /**
         * Four_Corners, North_vs_South and Highlands: a plot with a neighbour off the map is worth only what the neighbours after it are (the sum restarts), so
         * rivers run for the edge. (Highlands and North_vs_South also give `DoRiver` a way off the top row and the left column, see [CivVRiverGenerator].)
         */
        FourCorners,
        /** InlandSea: the value grows with the distance to the centre (times 0 to 2), and `AddRivers` keeps the sources off the edge and gives each river a heading towards the centre */
        InlandSea(true),
        /** West_vs_East: InlandSea's value and edge rule, with the heading of the quarter of the map the source is in (two halves, not three thirds) */
        WestVsEast(true),
        /** Boreal: the value grows with the distance to a target column (drawn for each river) plus the distance to the top row, so rivers run north; sources off the edge, first heading north, north-east or north-west */
        Boreal(true)
    }

    /** `GetLatitudeAtPlot`: 0 at the equator in the middle row, or (Highlands) 0 at one edge and 1 at the other, the cold one drawn for each map */
    enum class Climate { Equator, ColdPole }

    /** `TerrainGenerator:GenerateTerrainAtPlot` */
    enum class TerrainCode {
        Standard,
        /** North_vs_South: the band below the all-grass latitude is desert, not grass */
        DesertAtTheEquator,
        /** Donut: no latitude, grass with the desert and plains fractals' bands */
        Bands,
        /** Boreal: snow, tundra and plains from three fractals, and a second pass of snow in the north */
        Boreal
    }

    /** `FeatureGenerator:AddFeatures` and the `Add...AtPlot` the script replaces */
    enum class FeatureCode {
        Standard,
        /** Donut: `AddJunglesAtPlot` does nothing */
        NoJungle,
        /** Boreal: no atolls, no marsh, no jungle, and `AdjustTerrainTypes` (the soft arctic rivers) before the features instead of after */
        Boreal
    }

    /** `FeatureGenerator:AddIceAtPlot` of a script that has ice */
    enum class IceCode {
        Standard,
        /** Lakes: the two top and the two bottom rows are always ice, wrap or not */
        TwoPolarRows,
        /** Boreal: the top row ice, the four rows below it ice at 70, 50, 30 and 20 % on ocean (not coast) */
        Boreal
    }

    /** The world size that has a grid of this [width] (the grid has been made by [grids]); null if the script has none */
    fun worldSizeOfGrid(width: Int): CivVWorldSize? = grids?.entries?.firstOrNull { it.value.first == width }?.key
}

/**
 *  The scripts that are ported as map types on their own grid, by type name: Four_Corners.lua and InlandSea.lua, Lakes.lua, North_vs_South.lua, West_vs_East.lua,
 *  Highlands.lua, Boreal.lua and Donut.lua. Vox Populi does not change the land of any of them (mapgen-plan.md section 10; its copies differ in the start, resource and
 *  wonder rules, in West_vs_East's start fallback, and in passing `lake_grain` as 0 where the stock scripts leave it nil).
 */
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

    /**
     *  Lakes.lua: a world wrapping east to west on a grid two sizes smaller than the player's, with ice on two rows at each pole, mountains off the coast.
     *  The terrain and the rivers are the generic ones (latitude as the base game's).
     */
    val lakes = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (24 to 16), CivVWorldSize.Tiny to (32 to 20), CivVWorldSize.Small to (40 to 24),
            CivVWorldSize.Standard to (52 to 32), CivVWorldSize.Large to (64 to 40), CivVWorldSize.Huge to (84 to 52)
        ),
        mountainsOffTheCoast = true,
        wrapX = true,
        iceCode = CivVScript.IceCode.TwoPolarRows,
        ridgesOnNodeScale = true
    )

    /** North_vs_South.lua: a small square, latitude 0.8 of the base game's, desert under the all-grass latitude, no ice, rivers that run for the edge */
    val northVsSouth = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (16 to 16), CivVWorldSize.Tiny to (24 to 24), CivVWorldSize.Small to (30 to 30),
            CivVWorldSize.Standard to (36 to 36), CivVWorldSize.Large to (44 to 44), CivVWorldSize.Huge to (56 to 56)
        ),
        latitude = { 0.8 * it },
        ice = false,
        rivers = CivVScript.RiverCode.FourCorners,
        terrain = CivVScript.TerrainCode.DesertAtTheEquator,
        ridgesOnNodeScale = true
    )

    /** West_vs_East.lua: a small rectangle, latitude 0.8 of the base game's, no ice, mountains off the coast, rivers that run for the centre */
    val westVsEast = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (20 to 14), CivVWorldSize.Tiny to (30 to 20), CivVWorldSize.Small to (36 to 24),
            CivVWorldSize.Standard to (44 to 28), CivVWorldSize.Large to (52 to 32), CivVWorldSize.Huge to (64 to 40)
        ),
        latitude = { 0.8 * it },
        ice = false,
        mountainsOffTheCoast = true,
        rivers = CivVScript.RiverCode.WestVsEast,
        ridgesOnNodeScale = true
    )

    /** Highlands.lua: the cold edge is the north or the south by a coin, latitude 0.8 of that, no ice, rivers that run for the edge */
    val highlands = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (32 to 20), CivVWorldSize.Tiny to (40 to 24), CivVWorldSize.Small to (52 to 32),
            CivVWorldSize.Standard to (64 to 40), CivVWorldSize.Large to (84 to 52), CivVWorldSize.Huge to (104 to 64)
        ),
        latitude = { 0.8 * it },
        ice = false,
        rivers = CivVScript.RiverCode.FourCorners,
        climate = CivVScript.Climate.ColdPole,
        ridgesOnNodeScale = true
    )

    /** Boreal.lua: its own terrain and ice, no marsh, jungle or atolls, mountains stay on the coast, rivers that run north */
    val boreal = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (32 to 20), CivVWorldSize.Tiny to (40 to 24), CivVWorldSize.Small to (52 to 32),
            CivVWorldSize.Standard to (64 to 40), CivVWorldSize.Large to (84 to 52), CivVWorldSize.Huge to (104 to 64)
        ),
        rivers = CivVScript.RiverCode.Boreal,
        terrain = CivVScript.TerrainCode.Boreal,
        features = CivVScript.FeatureCode.Boreal,
        iceCode = CivVScript.IceCode.Boreal,
        keepsCoastalMountains = true,
        ridgesOnNodeScale = true
    )

    /** Donut.lua (Vox Populi's copy is the same): a square, grass with bands of desert and plains and no latitude, no ice, no jungle, mountains off the coast */
    val donut = CivVScript(
        grids = mapOf(
            CivVWorldSize.Duel to (24 to 24), CivVWorldSize.Tiny to (36 to 36), CivVWorldSize.Small to (44 to 44),
            CivVWorldSize.Standard to (52 to 52), CivVWorldSize.Large to (64 to 64), CivVWorldSize.Huge to (80 to 80)
        ),
        ice = false,
        mountainsOffTheCoast = true,
        terrain = CivVScript.TerrainCode.Bands,
        features = CivVScript.FeatureCode.NoJungle
    )

    /** What every other type runs: the generic pipeline */
    val standard = CivVScript()

    fun of(type: String): CivVScript = when (type) {
        MapType.fourCornersLikeCivV -> fourCorners
        MapType.inlandSeaLikeCivV -> inlandSea
        MapType.lakesLikeCivV -> lakes
        MapType.northVsSouthLikeCivV -> northVsSouth
        MapType.westVsEastLikeCivV -> westVsEast
        MapType.highlandsLikeCivV -> highlands
        MapType.borealLikeCivV -> boreal
        MapType.donutLikeCivV -> donut
        else -> standard
    }

    /**
     *  `GetMapInitData`: the parameters of a map for a script that sets its own grid, or null if [parameters]'s type does not. The size the player chose
     *  (the number of tiles, Auto's included) says the world size (`CivVWorldSize.forTiles`) and the script's grid for that world size is the size of
     *  the map: a rectangle of Civ V's pointy hexes, with the world wrap the script sets (`WrapX`: Lakes wraps, the others do not), whatever shape and wrap
     *  were asked for. The parameters are copied, the player's are left as they are.
     */
    fun withOwnGrid(parameters: MapParameters): MapParameters? {
        val script = of(parameters.type)
        val grids = script.grids ?: return null
        val (width, height) = grids.getValue(CivVWorldSize.forTiles(parameters.numberOfTiles()))
        return parameters.clone().apply {
            shape = MapShape.rectangular
            worldWrap = script.wrapX
            mapSize = MapSize(width, height)
        }
    }
}

/** The Four_Corners option "buffer zones": what lies in the two strips through the middle */
enum class FourCornersBuffer { Ocean, Mountains, Random }

/**
 *  The Donut option "centre region": what fills the hole. The script's other values are not here: Desert (land like the ring, its terrain painted desert) and
 *  Random (one of the five); the map type uses the script's default, [Ocean].
 */
enum class DonutCentre { Hills, Mountains, Ocean, Standard }

/**
 *  The land of the scripts of the layered family that are ported (Four_Corners, InlandSea, Lakes, North_vs_South, West_vs_East, Donut), written as their
 *  `MultilayeredFractal:GeneratePlotsByRegion` is: whole-map shapes laid by hand on an all-land array, and one [CivVLayeredWorld.applyTectonics] (Donut makes its
 *  hills and peaks itself). None calls a fractal layer, so the layer operations of [CivVLayeredWorld] are not exercised by them. All set the grid
 *  ([CivVScripts.withOwnGrid]); the two regional scripts (Highlands, Boreal) are in [CivVRegionalScripts] and run on the same plot array.
 *
 *  What Lakes, North_vs_South, West_vs_East, Highlands, Boreal and Donut do that is not what they say, and what is done here:
 *  * `lake_grain` is not defined in North_vs_South and West_vs_East: the global is nil, `Fractal.Create` reads it with `lua_tointeger` (CvLuaFractal::lCreate), which
 *    makes it 0: the smoothest field, so the lakes are a few big blobs ([nilGrainLake]). Vox Populi passes 0.
 *  * Highlands, North_vs_South and West_vs_East call `GenerateCoasts({bExpandCoasts = false})`, Lakes, Boreal and Donut without arguments: all expand the coasts
 *    (`args.bExpandCoasts or true`), as the generic coast step does.
 *  * The `CanPlaceCityStateAt` of Highlands and Boreal tests `PlotTypes.PLOT_MOUNTAINS`, a name the game does not define (nil), so it never refuses a mountain; Vox Populi
 *    deletes both overrides (its own check refuses the outermost ring and ice), and so does the start code here.
 *  * The ridges of the hills and mountains fields are made on the map's cells, their seeds 7 nodes of the original's 128 columns apart as cells for the small grids
 *    of these scripts ([CivVScript.ridgesOnNodeScale]); the originals use the fields' nodes. Seeds 7 cells apart would not fit a map 16 or 24 cells wide (Four_Corners
 *    and InlandSea, which come from the earlier branch, keep 7 cells).
 *
 *  What Four_Corners and InlandSea do that is not what they say, and what is done here (the effective behaviour is ported):
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
        buffer: FourCornersBuffer = FourCornersBuffer.Ocean,
        donutCentre: DonutCentre = DonutCentre.Ocean
    ): CivVLayeredWorld.Result {
        val script = CivVScripts.of(type)
        val worldSize = script.worldSizeOfGrid(geometry.width) ?: CivVWorldSize.forTiles(geometry.width * geometry.height)
        val seedDistance = if (script.ridgesOnNodeScale) CivVLayeredWorld.nodeSeedDistance(geometry.width) else CivVLayeredWorld.MIN_SEED_DISTANCE
        val world = CivVLayeredWorld(geometry, wrapX, rng, worldSize, seedDistance)
        when (type) {
            MapType.fourCornersLikeCivV -> fourCorners(world, rng, worldAge, buffer)
            MapType.inlandSeaLikeCivV -> inlandSea(world, rng, worldAge)
            MapType.lakesLikeCivV -> lakes(world, rng, worldAge)
            MapType.northVsSouthLikeCivV -> northVsSouth(world, rng, worldAge)
            MapType.westVsEastLikeCivV -> westVsEast(world, rng, worldAge)
            MapType.donutLikeCivV -> donut(world, rng, worldSize, donutCentre)
            MapType.highlandsLikeCivV -> CivVRegionalScripts.highlands(world, rng, worldSize)
            MapType.borealLikeCivV -> CivVRegionalScripts.boreal(world, rng, worldAge, worldSize)
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

    /**
     *  Lakes.lua: land in all rows but the two at each pole (sea, a place for rivers to end and for the ice), then the lakes: a fractal across the whole map
     *  (wrapping east to west, its edges zero) whose cells at or above a percentile are sea. The size of the lakes is the script's option "bodies of water",
     *  here the default, random: small (the top 7 % of the fractal, grain 5), large (10 %, grain 4) or seas (15 %, grain 3), each a third of the maps.
     *  [CivVLayeredWorld.applyTectonics] follows with 1.2 times the plates. [lakeSize] (1 to 3) fixes the draw.
     *
     *  The Lua fills `iW * (iH - 2)` land plots and then writes the two top rows by index beyond the end of that table: the array has no hole, the result is land in
     *  rows 2 to height - 3, sea in the other four.
     */
    fun lakes(world: CivVLayeredWorld, rng: Random, worldAge: Int, lakeSize: Int? = null) {
        val width = world.width
        val height = world.height
        world.fill(CivVLayeredWorld.LAND)
        for (x in 0 until width) for (y in listOf(0, 1, height - 2, height - 1)) world.setPlot(x, y, CivVLayeredWorld.OCEAN)

        val size = lakeSize ?: (1 + rng.nextInt(3))
        val share = intArrayOf(93, 90, 85)[size - 1]
        val grain = intArrayOf(5, 4, 3)[size - 1]
        val lakes = CivVFractal(width, height, grain, true, false, rng, polar = true)
        val threshold = lakes.getHeightFromPercent(share)
        for (y in 1..height - 2) for (x in 0 until width)
            if (lakes.getHeight(x, y) >= threshold) world.setPlot(x, y, CivVLayeredWorld.OCEAN)

        world.applyTectonics(worldAge, adjustPlates = 1.2f)
    }

    /**
     *  The lakes of North_vs_South.lua and West_vs_East.lua: a fractal of 64 x 64 nodes, whose cells at or above the 92nd percentile are sea.
     *  Both scripts hand the grain to `Fractal.Create` as `lake_grain`, which no script defines: the global is nil, and the engine's `lua_tointeger` reads a nil as 0
     *  (Vox Populi passes 0). Grain 0 is the smoothest field there is: the first pass draws the four corners of the 64 x 64 nodes only (the stride is
     *  2 to the power of the smaller exponent less the grain), the other passes blend them with noise that halves each time, so the top 8 % is one lake, a blob in a
     *  corner or along an edge, not the scattered ponds of a grain of 3 to 5. [Bug of the originals, carried: the grain was meant to be defined.]
     */
    private fun nilGrainLake(world: CivVLayeredWorld, rng: Random) {
        val lakes = CivVFractal(world.width, world.height, LAKE_GRAIN_NIL, false, false, rng, fracXExp = 6, fracYExp = 6)
        val threshold = lakes.getHeightFromPercent(92)
        for (y in 0 until world.height) for (x in 0 until world.width)
            if (lakes.getHeight(x, y) >= threshold) world.setPlot(x, y, CivVLayeredWorld.OCEAN)
    }

    /** `lake_grain`, the undefined global of North_vs_South and West_vs_East: `lua_tointeger(nil)` is 0 */
    const val LAKE_GRAIN_NIL = 0

    /**
     *  North_vs_South.lua: land everywhere, [nilGrainLake], then [CivVLayeredWorld.applyTectonics]. Nothing divides north from south: the script's own
     *  start code splits the map into two rectangles for two teams (`GenerateRegions`, method 3), which Unciv's region division does not have.
     */
    fun northVsSouth(world: CivVLayeredWorld, rng: Random, worldAge: Int) {
        world.fill(CivVLayeredWorld.LAND)
        nilGrainLake(world, rng)
        world.applyTectonics(worldAge)
    }

    /**
     *  West_vs_East.lua: land everywhere but a strip of sea through the middle, four columns wide (width / 2 - 2 to + 1), except in the four rows around the middle
     *  where only the two middle columns are sea, so two landmasses that touch at no point; [nilGrainLake]; [CivVLayeredWorld.applyTectonics].
     */
    fun westVsEast(world: CivVLayeredWorld, rng: Random, worldAge: Int) {
        val width = world.width
        val height = world.height
        world.fill(CivVLayeredWorld.LAND)
        for (y in 0 until height) for (x in width / 2 - 2..width / 2 + 1) {
            if (y in height / 2 - 2..height / 2 + 1) {
                if (x == width / 2 || x == width / 2 - 1) world.setPlot(x, y, CivVLayeredWorld.OCEAN)
            } else world.setPlot(x, y, CivVLayeredWorld.OCEAN)
        }
        nilGrainLake(world, rng)
        world.applyTectonics(worldAge)
    }

    /**
     *  Donut.lua: a disc of land (radius 4 cells less than half the width) with a hole at the centre (half that radius) that is the option "centre region":
     *  hills, mountains or ocean (the script's default). Hills and mountains by two fractals of the world size's grain (3, 4, 4, 5, 5, 6), whose edges are zero: the
     *  top 4 % of the first are mountains, the 5 % below that and the bottom 4 % hills, and hills also where the second is in its 20th to 30th or 70th to 80th percentile.
     *  No tectonics, no world age. The terrain is by bands ([CivVScript.TerrainCode.Bands]).
     *
     *  The Lua asks `ChooseLocations` for starts on the coast (`mustBeCoast`), which Unciv's start finder has no flag for.
     */
    fun donut(world: CivVLayeredWorld, rng: Random, worldSize: CivVWorldSize, centre: DonutCentre = DonutCentre.Ocean) {
        val width = world.width
        val height = world.height
        val grain = when (worldSize) {
            CivVWorldSize.Duel -> 3; CivVWorldSize.Tiny, CivVWorldSize.Small -> 4; CivVWorldSize.Standard, CivVWorldSize.Large -> 5; CivVWorldSize.Huge -> 6
        }
        val terrain = CivVFractal(width, height, grain, false, false, rng, polar = true)
        val hillsThreshold = terrain.getHeightFromPercent(91)
        val peaksThreshold = terrain.getHeightFromPercent(96)
        val clumps = terrain.getHeightFromPercent(4)
        val hills = CivVFractal(width, height, grain, false, false, rng, polar = true)
        val bottom1 = hills.getHeightFromPercent(20)
        val top1 = hills.getHeightFromPercent(30)
        val bottom2 = hills.getHeightFromPercent(70)
        val top2 = hills.getHeightFromPercent(80)

        val centreX = width / 2
        val centreY = height / 2
        val radius = centreX - 4
        val holeRadius = radius / 2
        for (y in 0 until height) for (x in 0 until width) {
            val distance = if (x != centreX || y != centreY) sqrt(((x - centreX) * (x - centreX) + (y - centreY) * (y - centreY)).toDouble()) else 0.0
            val plot = when {
                distance > radius -> CivVLayeredWorld.OCEAN
                distance < holeRadius && centre != DonutCentre.Standard -> when (centre) {
                    DonutCentre.Hills -> CivVLayeredWorld.HILLS
                    DonutCentre.Mountains -> CivVLayeredWorld.MOUNTAIN
                    else -> CivVLayeredWorld.OCEAN
                }
                else -> {
                    val value = terrain.getHeight(x, y)
                    val hillsValue = hills.getHeight(x, y)
                    when {
                        value >= peaksThreshold -> CivVLayeredWorld.MOUNTAIN
                        value >= hillsThreshold || value <= clumps -> CivVLayeredWorld.HILLS
                        hillsValue in bottom1..top1 || hillsValue in bottom2..top2 -> CivVLayeredWorld.HILLS
                        else -> CivVLayeredWorld.LAND
                    }
                }
            }
            world.setPlot(x, y, plot)
        }
    }

    /** The distance of a cell to the centre of the map as InlandSea's `GetRiverValueAtPlot` measures it (|x - w / 2| + |y - h / 2|) */
    fun distanceToCentre(x: Int, y: Int, width: Int, height: Int) = abs(x - width / 2.0) + abs(y - height / 2.0)
}
