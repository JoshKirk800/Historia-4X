package com.unciv.logic.map.mapgenerator

import com.unciv.Constants
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 *  The terrain and features of the Civ V map scripts: TerrainGenerator.lua (the base terrain of land by latitude) and
 *  FeatureGenerator.lua (flood plains, oases, ice, marsh, jungle, forest), for rulesets that set `terrainGeneratorLikeCivV`.
 *
 *  It names the terrains and features of the Civ V rulesets. Mountains and hills stay as the elevation generator made them.
 *  Rivers have to be there before the features: flood plains need one, oases and (on the edge) tundra do not like them.
 *  The scripts' options for the temperature and the rainfall come from the map parameters (see [temperatureOption] and [rainfallOption]).
 *  A [script] of the layered family changes the latitude function (and where the poles are), takes the ice away or has its own, may take the mountains off the coast
 *  (or keep them there), and replaces the terrain of Donut and Boreal and the features of Boreal and Donut ([CivVScript.TerrainCode], [CivVScript.FeatureCode]).
 */
class CivVTerrainGenerator(private val map: TileMap, private val rng: Random, private val script: CivVScript = CivVScripts.standard) {

    companion object {
        private const val MARSH = "Marsh"
        private const val OASIS = "Oasis"
        private const val FLOOD_PLAINS = "Flood plains"
        private const val ATOLL = "Atoll"

        // TerrainGenerator.Create defaults
        private const val DESERT_PERCENT_NORMAL = 32
        private const val PLAINS_PERCENT = 50
        private const val DESERT_BOTTOM_LATITUDE = 0.2
        private const val TERRAIN_GRAIN = 3
        private const val TEMPERATURE_SHIFT = 0.1
        private const val DESERT_SHIFT = 16

        // FeatureGenerator.Create defaults
        private const val JUNGLE_GRAIN = 5
        private const val FOREST_GRAIN = 6
        private const val CLUMP_GRAIN = 3
        private const val MARSH_GRAIN = 4

        /** The options of the scripts (World Temperature, Rainfall), 1 cool/arid, 2 normal, 3 hot/wet, from the map parameters:
         *  [com.unciv.logic.map.MapParameters.temperatureShift] beyond +-0.15 and [com.unciv.logic.map.MapParameters.vegetationRichness]
         *  below 0.3 or above 0.5 (the defaults are 0 and 0.4) */
        fun temperatureOption(shift: Float) = if (shift <= -0.15f) 1 else if (shift >= 0.15f) 3 else 2
        fun rainfallOption(vegetationRichness: Float) = if (vegetationRichness < 0.3f) 1 else if (vegetationRichness > 0.5f) 3 else 2
    }

    private val temperature = temperatureOption(map.mapParameters.temperatureShift)
    private val rainfall = rainfallOption(map.mapParameters.vegetationRichness)

    // TerrainGenerator.Create: the latitudes and the share of desert move with the temperature
    private val desertPercent = DESERT_PERCENT_NORMAL + when (temperature) { 1 -> -DESERT_SHIFT; 3 -> DESERT_SHIFT; else -> 0 }
    private val snowLatitude = 0.75 + if (temperature == 3) TEMPERATURE_SHIFT * 0.5 else 0.0
    private val tundraLatitude = 0.6 + when (temperature) { 1 -> -TEMPERATURE_SHIFT * 1.5; 3 -> TEMPERATURE_SHIFT; else -> 0.0 }
    private val desertTopLatitude = 0.5 + when (temperature) { 1 -> -TEMPERATURE_SHIFT; 3 -> TEMPERATURE_SHIFT; else -> 0.0 }
    private val grassLatitude = 0.1 - if (temperature != 2) TEMPERATURE_SHIFT * 0.5 else 0.0

    // FeatureGenerator.Create: the rainfall moves jungle, forest, marsh and oases (an arid land has no jungle band to speak of)
    private val junglePercent = 65 + when (rainfall) { 1 -> -20; 3 -> 20; else -> 0 }
    private val jungleFactor = when (rainfall) { 1 -> 6; 3 -> 2; else -> 5 }
    private val forestPercent = 31 + when (rainfall) { 1 -> -15; 3 -> 15; else -> 0 }
    private val clumpHeight = 90 + when (rainfall) { 1 -> -5; 3 -> 5; else -> 0 }
    private val marshPercent = when (rainfall) { 1 -> 8 / 1.5; 3 -> 8 * 1.5; else -> 8.0 }
    private val oasisPercent = when (rainfall) { 1 -> 6 / 1.5; 3 -> 6 * 1.5; else -> 6.0 }

    private val noMountainsOnTheCoast = !script.keepsCoastalMountains &&
        (script.mountainsOffTheCoast || map.ruleset?.modOptions?.constants?.noMountainsOnTheCoast == true)

    private val tiles = map.values.toList()
    private val minColumn = tiles.minOf { it.getColumn() }
    private val minRow = tiles.minOf { it.getRow() }
    private val gridWidth = tiles.maxOf { it.getColumn() } - minColumn + 1
    private val gridHeight = tiles.maxOf { it.getRow() } - minRow + 1
    private val wrapX = map.mapParameters.worldWrap
    private val maxLatitude = map.maxLatitude.coerceAtLeast(1)
    private val worldSize = script.worldSizeOfGrid(gridWidth) ?: CivVWorldSize.forTiles(gridWidth * gridHeight)

    private fun x(tile: Tile) = tile.getColumn() - minColumn
    private fun y(tile: Tile) = tile.getRow() - minRow

    /** Highlands: which edge is the cold one, drawn for each map (`ShiftMultiplier`: 0 the north, 1 the south) */
    private val coldShift = if (script.climate == CivVScript.Climate.ColdPole) rng.nextInt(2) else 0

    /**
     *  The latitude before the script's function, with the terrain generator's [variation] (0 for the feature generator's): 0 at the equator and 1 at the poles,
     *  or for a script with [CivVScript.Climate.ColdPole] 0 at the warm edge and 1 at the cold one (`|row / height - shift|`).
     */
    private fun rawLatitude(tile: Tile, variation: Double = 0.0) = when (script.climate) {
        CivVScript.Climate.Equator -> (abs(tile.latitude).toDouble() / maxLatitude + variation).coerceIn(0.0, 1.0)
        CivVScript.Climate.ColdPole -> abs((y(tile).toDouble() / gridHeight + variation).coerceIn(0.0, 1.0) - coldShift)
    }

    /** The latitude of the feature generator: 0 at the equator, 1 at the poles (a script may bend it) */
    private fun latitude(tile: Tile) = script.latitude(rawLatitude(tile))

    private fun fractal(grain: Int) = CivVFractal(gridWidth, gridHeight, grain, wrapX, false, rng)

    /** TerrainGenerator:GenerateTerrain */
    fun generateBaseTerrain() {
        if (script.terrain == CivVScript.TerrainCode.Boreal) { generateBorealTerrain(); return }
        val deserts = fractal(TERRAIN_GRAIN)
        val desertTop = deserts.getHeightFromPercent(100)
        val desertBottom = deserts.getHeightFromPercent(maxOf(0, floor(100.0 - desertPercent).toInt()))
        val plains = fractal(TERRAIN_GRAIN)
        val plainsTop = plains.getHeightFromPercent(100)
        val plainsBottom = plains.getHeightFromPercent(maxOf(0, floor(100.0 - PLAINS_PERCENT).toInt()))
        val variation = fractal(TERRAIN_GRAIN)

        for (tile in tiles) {
            if (!tile.isLand) continue
            // Vox Populi's FeatureGenerator:AddFeatures (allow_mountains_on_coast is nil in the scripts, so false there): a mountain on the coast is hills
            val coastalMountain = noMountainsOnTheCoast && tile.baseTerrain == Constants.mountain && tile.neighbors.any { it.isWater }
            if (tile.isImpassible() && !coastalMountain) continue
            val lat = script.latitude(rawLatitude(tile, (128 - variation.getHeight(x(tile), y(tile))) / (255.0 * 5.0)))
            val terrain = when {
                script.terrain == CivVScript.TerrainCode.Bands -> bandTerrain(tile, deserts, desertBottom, desertTop, plains, plainsBottom, plainsTop)
                lat >= snowLatitude -> Constants.snow
                lat >= tundraLatitude -> Constants.tundra
                lat < grassLatitude -> if (script.terrain == CivVScript.TerrainCode.DesertAtTheEquator) Constants.desert else Constants.grassland
                else -> {
                    val desertValue = deserts.getHeight(x(tile), y(tile))
                    val plainsValue = plains.getHeight(x(tile), y(tile))
                    if (desertValue in desertBottom..desertTop && lat >= DESERT_BOTTOM_LATITUDE && lat < desertTopLatitude)
                        Constants.desert
                    else if (plainsValue in plainsBottom..plainsTop) Constants.plains
                    else Constants.grassland
                }
            }
            // Only the base terrain: the hills the elevation generator made stay
            val hill = tile.hasHill || coastalMountain
            tile.removeTerrainFeatures()
            tile.baseTerrain = terrain
            if (hill) tile.setTerrainFeatures(listOf(Constants.hill))
            tile.setTerrainTransients()
        }
    }

    /** Donut's `GenerateTerrainAtPlot` (the desert centre of its option is not ported): no latitude, desert where the desert fractal is in its band, plains in the plains band, else grass */
    private fun bandTerrain(tile: Tile, deserts: CivVFractal, desertBottom: Int, desertTop: Int, plains: CivVFractal, plainsBottom: Int, plainsTop: Int): String = when {
        deserts.getHeight(x(tile), y(tile)) in desertBottom..desertTop -> Constants.desert
        plains.getHeight(x(tile), y(tile)) in plainsBottom..plainsTop -> Constants.plains
        else -> Constants.grassland
    }

    /**
     *  Boreal's `GenerateTerrain`: snow where the first fractal (grain 3) is in its top 35 % (and on mountains, which are not a terrain here), tundra where the plains
     *  fractal (grain 4) is below its 91st percentile or on hills, plains in the rest; then in the northern 40 % of the rows another fractal (grain 2, 55 % of it snow) paints snow.
     *  There is no latitude, no desert, no grass and (the features' job) no marsh or jungle.
     */
    private fun generateBorealTerrain() {
        val snow = fractal(3)
        val snowNorth = CivVFractal(gridWidth, 1 + floor(gridHeight * 0.4).toInt(), 2, false, false, rng, fracXExp = 7, fracYExp = 6)
        val plains = fractal(4)
        val snowThreshold = snow.getHeightFromPercent(65)
        val snowNorthThreshold = snowNorth.getHeightFromPercent(45)
        val plainsThreshold = plains.getHeightFromPercent(91)
        val northFrom = gridHeight - floor(gridHeight * 0.4).toInt()
        val northOffset = floor(gridHeight * 0.6).toInt()
        for (tile in tiles) {
            if (!tile.isLand || tile.isImpassible()) continue
            val terrain = when {
                snow.getHeight(x(tile), y(tile)) >= snowThreshold -> Constants.snow
                plains.getHeight(x(tile), y(tile)) < plainsThreshold || tile.hasHill -> Constants.tundra
                else -> Constants.plains
            }
            val hill = tile.hasHill
            tile.removeTerrainFeatures()
            tile.baseTerrain = terrain
            if (hill) tile.setTerrainFeatures(listOf(Constants.hill))
            tile.setTerrainTransients()
        }
        for (tile in tiles) {
            if (!tile.isLand || tile.isImpassible() || y(tile) < northFrom) continue
            if (snowNorth.getHeight(x(tile), y(tile) - northOffset) < snowNorthThreshold) continue
            val hill = tile.hasHill
            tile.removeTerrainFeatures()
            tile.baseTerrain = Constants.snow
            if (hill) tile.setTerrainFeatures(listOf(Constants.hill))
            tile.setTerrainTransients()
        }
    }

    private val Tile.hasHill get() = Constants.hill in terrainFeatures

    /**
     *  FeatureGenerator:AddFeatures and AdjustTerrainTypes. The rivers have to be made before. Boreal's own `AddFeatures` has no atolls and no marsh or
     *  jungle, and softens the arctic rivers before the features, not after (it has no jungle, so the order only decides what a forest can grow on).
     */
    fun addFeatures() {
        val grainChange = worldSize.grainChange
        val boreal = script.features == CivVScript.FeatureCode.Boreal
        val jungleAllowed = script.features == CivVScript.FeatureCode.Standard
        val marshAllowed = !boreal
        if (boreal) adjustTerrainTypes() else addAtolls()
        val jungles = fractal(JUNGLE_GRAIN + grainChange)
        val forests = fractal(FOREST_GRAIN + grainChange)
        val clumps = fractal(CLUMP_GRAIN)
        val marsh = fractal(MARSH_GRAIN)
        val jungleBottom = jungles.getHeightFromPercent(((100 - junglePercent) / 2.0).toInt())
        val jungleTop = jungles.getHeightFromPercent(((100 + junglePercent) / 2.0).toInt())
        val jungleRange = (jungleTop - jungleBottom) * jungleFactor
        val forestLevel = forests.getHeightFromPercent(100 - forestPercent)
        val clumpLevel = clumps.getHeightFromPercent(clumpHeight)
        val marshLevel = marsh.getHeightFromPercent((100 - marshPercent).toInt())

        for (tile in tiles.sortedWith(compareBy({ y(it) }, { x(it) }))) {
            val lat = latitude(tile)
            if (tile.isWater) {
                if (script.ice) addIce(tile, lat)
                continue
            }
            if (tile.isImpassible()) continue
            val flat = !tile.hasHill
            val terrain = tile.baseTerrain
            val hasFeature = { tile.terrainFeatures.any { it != Constants.hill } }
            val river = tile.isAdjacentToRiver()

            if (terrain == Constants.desert && flat && river && !hasFeature())
                tile.addTerrainFeature(FLOOD_PLAINS)
            if (!hasFeature() && terrain == Constants.desert && flat && !tile.isAdjacentToCoast() && !river &&
                tile.neighbors.none { OASIS in it.terrainFeatures } && rng.nextInt(100) <= oasisPercent)
                tile.addTerrainFeature(OASIS)
            if (marshAllowed && !hasFeature() && terrain == Constants.grassland && flat && marsh.getHeight(x(tile), y(tile)) >= marshLevel)
                tile.addTerrainFeature(MARSH)
            if (jungleAllowed && !hasFeature() && terrain == Constants.grassland) {
                val height = jungles.getHeight(x(tile), y(tile))
                if (height <= jungleTop && height >= jungleBottom + jungleRange * lat)
                    tile.addTerrainFeature(Constants.jungle)
            }
            if (!hasFeature() && (terrain == Constants.grassland || terrain == Constants.plains || terrain == Constants.tundra) &&
                (forests.getHeight(x(tile), y(tile)) >= forestLevel || clumps.getHeight(x(tile), y(tile)) >= clumpLevel))
                tile.addTerrainFeature(Constants.forest)
        }

        if (!boreal) adjustTerrainTypes()
    }

    /** AdjustTerrainTypes: plains under the jungle, and softer arctic rivers */
    private fun adjustTerrainTypes() {
        for (tile in tiles) {
            if (tile.isWater || tile.isImpassible()) continue
            if (Constants.jungle in tile.terrainFeatures) tile.baseTerrain = Constants.plains
            else if (tile.isAdjacentToRiver()) {
                if (tile.baseTerrain == Constants.tundra) tile.baseTerrain = Constants.plains
                else if (tile.baseTerrain == Constants.snow) tile.baseTerrain = Constants.tundra
            }
            tile.setTerrainTransients()
        }
    }

    /**
     *  FeatureGenerator:AddAtolls: on a world with a big ocean (more than a quarter of the map), a few single-tile coast sites
     *  get an atoll: coast tiles with exactly one land neighbour that is not tundra, snow or ice, sorted by the size of that landmass
     *  (1-2, 3-7, 8-16, 17-40, 41-75 tiles; the sites by bigger landmasses are not used) and thinned out to a quarter, a fifth, ... of each class.
     *  The number of atolls is the world size's target plus a random number up to it (2 for Duel to 12 for Huge); a roll picks
     *  a class (40 % smallest, 25 %, 15 %, 10 %, 10 %) and falls back to the smaller classes when it is used up.
     */
    private fun addAtolls() {
        if (ATOLL !in map.ruleset!!.terrains) return
        // The biggest body of water (oceans and coast, no lakes) must be more than a quarter of the map
        val water = tiles.filter { it.isWater && it.baseTerrain != "Lakes" }.toHashSet()
        var biggest = 0
        val seen = HashSet<Tile>()
        for (start in water) {
            if (!seen.add(start)) continue
            var size = 1
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) for (n in queue.removeFirst().neighbors) if (n in water && seen.add(n)) { size++; queue.add(n) }
            biggest = maxOf(biggest, size)
        }
        if (biggest <= tiles.size / 4) return

        val classes = Array(5) { ArrayList<Tile>() } // alpha .. epsilon
        for (tile in tiles) {
            if (!tile.isWater || tile.baseTerrain != Constants.coast || tile.terrainFeatures.isNotEmpty()) continue
            val land = tile.neighbors.filter { !it.isWater }.toList()
            if (land.size != 1) continue
            val neighbour = land.first()
            if (neighbour.baseTerrain == Constants.tundra || neighbour.baseTerrain == Constants.snow || Constants.ice in neighbour.terrainFeatures) continue
            val area = if (neighbour.isImpassible() && Constants.hill !in neighbour.terrainFeatures) 0 else map.continentSizes[neighbour.getContinent()] ?: 0
            val index = when {
                area >= 76 -> continue
                area >= 41 -> 4
                area >= 17 -> 3
                area >= 8 -> 2
                area >= 3 -> 1
                area >= 1 -> 0
                else -> continue
            }
            classes[index].add(tile)
        }
        val shuffled = classes.map { it.shuffled(rng) }
        val limits = intArrayOf(4, 5, 4, 3, 4).mapIndexed { i, d -> (shuffled[i].size + d - 1) / d }.toIntArray()
        val used = IntArray(5)

        fun take(index: Int): Tile? {
            if (limits[index] <= 0) return null
            limits[index]--
            return shuffled[index].getOrNull(used[index]++)
        }
        val target = worldSize.atolls
        repeat(target + rng.nextInt(target)) {
            val roll = 1 + rng.nextInt(100)
            // The class the roll asks for, then each smaller one
            val first = when {
                roll <= 40 -> 0
                roll <= 65 -> 1
                roll <= 80 -> 2
                roll <= 90 -> 3
                else -> 4
            }
            val tile = (maxOf(first, 1) downTo 0).firstNotNullOfOrNull { take(it) } // a roll for the smallest class that finds it used up asks the next
            tile?.addTerrainFeature(ATOLL)
        }
    }

    /** Boreal's `AddIceAtPlot`: the top row is ice, the four rows below it are ice at 70, 50, 30 and 20 % where the water is ocean (coast stays open) */
    private fun addBorealIce(tile: Tile) {
        val fromTop = gridHeight - 1 - y(tile)
        if (fromTop == 0) { tile.addTerrainFeature(Constants.ice); return }
        val target = when (fromTop) { 1 -> 70; 2 -> 50; 3 -> 30; 4 -> 20; else -> return }
        if (tile.baseTerrain == Constants.ocean && rng.nextInt(100) < target) tile.addTerrainFeature(Constants.ice)
    }

    /** FeatureGenerator:AddIceAtPlot: always on the rows at the edge of a map that wraps, otherwise more the closer to the pole */
    private fun addIce(tile: Tile, lat: Double) {
        if (tile.baseTerrain != Constants.ocean && tile.baseTerrain != Constants.coast) return
        if (tile.terrainFeatures.isNotEmpty()) return
        when (script.iceCode) {
            CivVScript.IceCode.Boreal -> { addBorealIce(tile); return }
            CivVScript.IceCode.TwoPolarRows -> if (y(tile) < 2 || y(tile) >= gridHeight - 2) { tile.addTerrainFeature(Constants.ice); return }
            CivVScript.IceCode.Standard -> Unit
        }
        val onEdgeRow = script.iceCode == CivVScript.IceCode.Standard && map.mapParameters.shape == MapShape.rectangular && wrapX &&
            (y(tile) == 0 || y(tile) == gridHeight - 1)
        if (onEdgeRow) {
            tile.addTerrainFeature(Constants.ice)
            return
        }
        val roll = rng.nextInt(100) / 100.0
        if (roll < 8 * (lat - 0.875) || roll < 4 * (lat - 0.75))
            tile.addTerrainFeature(Constants.ice)
    }
}
