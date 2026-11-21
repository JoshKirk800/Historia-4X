package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.unciv.UncivGame
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.audio.MusicController
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Civ V's pointy-hex grid of any size, wrapping in x, laid on Unciv's hex coordinates so that every plot has the same six neighbours:
 * a Civ V plot (x, y) is the hex-space point (q, r) = (x - y / 2, y), and Unciv's (x, y) = (q + r, r), shifted to the centre of the map.
 * The neighbours of a Civ V plot are (q ± 1, r), (q, r ± 1), (q + 1, r - 1) and (q - 1, r + 1); those of an Unciv tile (x ± 1, y),
 * (x, y ± 1) and ±(1, 1), which is what the map of a Civ V game needs. The world wraps by (width, 0) ([TileMap.wrapVector]).
 *
 * The leftmost tile gets x = -[xOffset], chosen so that the tile matrix is as wide as the check of TileMap.setTransients wants
 * (2 * xOffset to 2 * xOffset + 3 columns): 83 for 128 x 80, 42 for 66 x 42.
 */
class CivVMapGrid(val width: Int, val height: Int) {
    private val xOffset = (width + height / 2 - 2) / 2
    private val yOffset = height / 2

    fun hexOf(x: Int, y: Int) = HexCoord.of(x + (y + 1) / 2 - xOffset, y - yOffset)

    /** The Civ V plot (x, y) of a tile */
    fun civVOf(position: HexCoord): Pair<Int, Int> {
        val y = position.y + yOffset
        return Math.floorMod(position.x - (y + 1) / 2 + xOffset, width) to y
    }

    /** The plot next to (x, y) in the hex-space direction (dq, dr), wrapped in x, or null off the map's top or bottom */
    fun neighborOf(x: Int, y: Int, dq: Int, dr: Int): Pair<Int, Int>? {
        val newY = y + dr
        if (newY !in 0 until height) return null
        val q = x - (y shr 1) + dq
        return Math.floorMod(q + (newY shr 1), width) to newY
    }

    companion object {
        /** The grid of a map built by [BnwMapClone] */
        fun of(mapSize: MapSize) = CivVMapGrid(mapSize.width, mapSize.height)
    }
}

/**
 * Builds the Unciv map of a Civ V game (Brave New World, or Community Patch) of any map size from the dump of its first turn (`tools/extract_bnw_map.py`, `map.json`): the terrain,
 * features, resources with their amounts, rivers, lakes, natural wonders, ancient ruins, and the start of every civilization and city-state,
 * on [CivVMapGrid]. Skipped unless BNW_CLONE_OUT is set. Environment: BNW_CLONE_JSON = the dump, BNW_CLONE_OUT = the map file to write,
 * BNW_CLONE_NAME = the name of the map (default "BNW clone").
 *
 * A hill is Unciv's Hill feature on the terrain the dump gives for the plot, a mountain plot is Unciv's Mountain. What the dump does not hold:
 * the personalities of the city-states.
 */
@RunWith(GdxTestRunner::class)
class BnwMapClone {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            Assume.assumeTrue("Set BNW_CLONE_OUT to run", System.getenv("BNW_CLONE_OUT") != null)
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f; citySoundsVolume = 0f; voicesVolume = 0f }
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = MusicController()
            RulesetCache.loadRulesets(noMods = true)
        }

        val terrains = mapOf(
            "TERRAIN_GRASS" to "Grassland", "TERRAIN_PLAINS" to "Plains", "TERRAIN_DESERT" to "Desert", "TERRAIN_TUNDRA" to "Tundra",
            "TERRAIN_SNOW" to "Snow", "TERRAIN_COAST" to "Coast", "TERRAIN_OCEAN" to "Ocean",
        )
        private const val PLOT_MOUNTAIN = 0
        private const val PLOT_HILLS = 1
        val features = mapOf(
            "FEATURE_ICE" to "Ice", "FEATURE_JUNGLE" to "Jungle", "FEATURE_MARSH" to "Marsh", "FEATURE_OASIS" to "Oasis",
            "FEATURE_FLOOD_PLAINS" to "Flood plains", "FEATURE_FOREST" to "Forest", "FEATURE_ATOLL" to "Atoll", "FEATURE_FALLOUT" to "Fallout",
        )
        val naturalWonders = mapOf(
            "FEATURE_CRATER" to "Barringer Crater", "FEATURE_FUJI" to "Mount Fuji", "FEATURE_MESA" to "Grand Mesa", "FEATURE_REEF" to "Great Barrier Reef",
            "FEATURE_VOLCANO" to "Krakatoa", "FEATURE_GIBRALTAR" to "Rock of Gibraltar", "FEATURE_GEYSER" to "Old Faithful",
            "FEATURE_FOUNTAIN_YOUTH" to "Fountain of Youth", "FEATURE_POTOSI" to "Cerro de Potosi", "FEATURE_EL_DORADO" to "El Dorado",
            "FEATURE_SRI_PADA" to "Sri Pada", "FEATURE_MT_SINAI" to "Mount Sinai", "FEATURE_MT_KAILASH" to "Mount Kailash",
            "FEATURE_ULURU" to "Uluru", "FEATURE_LAKE_VICTORIA" to "Lake Victoria", "FEATURE_KILIMANJARO" to "Mount Kilimanjaro",
            "FEATURE_SOLOMONS_MINES" to "King Solomon's Mines",
        )
        val resources = mapOf(
            "RESOURCE_IRON" to "Iron", "RESOURCE_HORSE" to "Horses", "RESOURCE_COAL" to "Coal", "RESOURCE_OIL" to "Oil",
            "RESOURCE_ALUMINUM" to "Aluminum", "RESOURCE_URANIUM" to "Uranium", "RESOURCE_WHEAT" to "Wheat", "RESOURCE_COW" to "Cattle",
            "RESOURCE_SHEEP" to "Sheep", "RESOURCE_DEER" to "Deer", "RESOURCE_BANANA" to "Bananas", "RESOURCE_FISH" to "Fish",
            "RESOURCE_STONE" to "Stone", "RESOURCE_WHALE" to "Whales", "RESOURCE_PEARLS" to "Pearls", "RESOURCE_GOLD" to "Gold Ore",
            "RESOURCE_SILVER" to "Silver", "RESOURCE_GEMS" to "Gems", "RESOURCE_MARBLE" to "Marble", "RESOURCE_IVORY" to "Ivory",
            "RESOURCE_FUR" to "Furs", "RESOURCE_DYE" to "Dyes", "RESOURCE_SPICES" to "Spices", "RESOURCE_SILK" to "Silk",
            "RESOURCE_SUGAR" to "Sugar", "RESOURCE_COTTON" to "Cotton", "RESOURCE_WINE" to "Wine", "RESOURCE_INCENSE" to "Incense",
            "RESOURCE_COPPER" to "Copper", "RESOURCE_SALT" to "Salt", "RESOURCE_CRAB" to "Crab", "RESOURCE_TRUFFLES" to "Truffles",
            "RESOURCE_BISON" to "Bison", "RESOURCE_CITRUS" to "Citrus", "RESOURCE_COCOA" to "Cocoa", "RESOURCE_NUTMEG" to "Nutmeg",
            "RESOURCE_CLOVES" to "Cloves", "RESOURCE_PEPPER" to "Pepper",
        )
        val nations = mapOf(
            "CIVILIZATION_IROQUOIS" to "Iroquois", "CIVILIZATION_POLYNESIA" to "Polynesia", "CIVILIZATION_EGYPT" to "Egypt",
            "CIVILIZATION_ROME" to "Rome", "CIVILIZATION_INDONESIA" to "Indonesia", "CIVILIZATION_AUSTRIA" to "Austria",
            "CIVILIZATION_SPAIN" to "Spain", "CIVILIZATION_JAPAN" to "Japan", "CIVILIZATION_OTTOMAN" to "The Ottomans",
            "CIVILIZATION_KOREA" to "Korea", "CIVILIZATION_ETHIOPIA" to "Ethiopia", "CIVILIZATION_PERSIA" to "Persia",
            "CIVILIZATION_SONGHAI" to "Songhai", "CIVILIZATION_ASSYRIA" to "Assyria", "CIVILIZATION_ENGLAND" to "England",
            "CIVILIZATION_ZULU" to "Zulus", "CIVILIZATION_HUNS" to "The Huns", "CIVILIZATION_POLAND" to "Poland", "CIVILIZATION_GREECE" to "Greece",
        )
        val cityStateNames = mapOf("MINOR_CIV_MBANZA_KONGO" to "M'Banza-Kongo")

        /** The nation of a civilization type that is not in [nations]: its name in title case when the ruleset has a nation of that name (CIVILIZATION_GERMANY: Germany) */
        fun civilizationName(type: String, ruleset: com.unciv.models.ruleset.Ruleset): String =
            type.removePrefix("CIVILIZATION_").split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                .also { require(it in ruleset.nations) { "nation $type: no nation named $it" } }

        fun cityStateName(type: String): String = cityStateNames[type]
            ?: type.removePrefix("MINOR_CIV_").split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private class Plot(val plotType: Int, val terrain: String, val feature: String?, val resource: String?, val improvement: String?,
                       val riverMask: Int, val lake: Boolean, val resourceQuantity: Int)

    private fun types(root: JsonValue, kind: String): Map<Int, String> =
        root.get("types").get(kind).map { it.name.toInt() to it.asString() }.toMap()

    @Test
    fun build() {
        val root = JsonReader().parse(Gdx.files.absolute(System.getenv("BNW_CLONE_JSON") ?: error("Set BNW_CLONE_JSON")))
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        require(root.getBoolean("wrapX") && !root.getBoolean("wrapY")) { "not a map wrapping in x only" }
        val grid = CivVMapGrid(root.getInt("width"), root.getInt("height"))
        val fields = root.get("fields").map { it.asString() }
        val terrainTypes = types(root, "terrain")
        val featureTypes = types(root, "feature")
        val resourceTypes = types(root, "resource")
        val improvementTypes = types(root, "improvement")
        fun at(row: JsonValue, field: String) = row.getInt(fields.indexOf(field))

        val plots = ArrayList<Plot>(grid.width * grid.height)
        for (row in root.get("plots")) plots.add(Plot(
            at(row, "plotType"), terrainTypes[at(row, "terrain")]!!, featureTypes[at(row, "feature")], resourceTypes[at(row, "resource")],
            improvementTypes[at(row, "improvement")], at(row, "riverMask"), at(row, "lake") != 0, at(row, "resourceQty")))
        require(plots.size == grid.width * grid.height) { "plots: ${plots.size}" }
        fun plot(x: Int, y: Int) = plots[y * grid.width + x]

        val map = TileMap(plots.size)
        map.mapParameters = MapParameters().apply {
            name = System.getenv("BNW_CLONE_NAME") ?: "BNW clone"
            type = MapType.continentsLikeCivV
            shape = MapShape.rectangular
            mapSize = MapSize(grid.width, grid.height)
            worldWrap = true
            civVGridWrap = true
            baseRuleset = ruleset.name
            noRuins = false
        }
        for (y in 0 until grid.height) for (x in 0 until grid.width)
            map.tileList.add(Tile().apply { position = grid.hexOf(x, y); baseTerrain = "Plains" })
        map.setTransients(ruleset)
        fun tile(x: Int, y: Int) = map[grid.hexOf(x, y)]

        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            val plot = plot(x, y)
            val tile = tile(x, y)
            val baseName = when {
                plot.plotType == PLOT_MOUNTAIN -> "Mountain"
                plot.lake -> "Lakes"
                else -> terrains[plot.terrain] ?: error("terrain ${plot.terrain}")
            }
            tile.setBaseTerrain(ruleset.terrains[baseName] ?: error("no terrain $baseName"))
            val featureNames = ArrayList<String>()
            if (plot.plotType == PLOT_HILLS) featureNames.add("Hill")
            plot.feature?.let { feature -> features[feature]?.let { featureNames.add(it) } ?: require(feature in naturalWonders) { "feature $feature" } }
            tile.setTerrainFeatures(featureNames)
            tile.setTerrainTransients()
        }

        verifyGeometry(map, grid)

        // Rivers: a plot's river flags are the edges to its south-west (NE of river), east (W of river) and south-east (NW of river) neighbours
        var rivers = 0
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            val mask = plot(x, y).riverMask
            for ((bit, direction) in listOf(1 to (0 to -1), 2 to (1 to 0), 4 to (1 to -1))) {
                if (mask and bit == 0) continue
                val (nx, ny) = grid.neighborOf(x, y, direction.first, direction.second) ?: continue
                if (tile(x, y).setConnectedByRiver(tile(nx, ny), true)) rivers++
            }
        }

        var ruins = 0
        val skippedResources = java.util.TreeMap<String, Int>()
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            val plot = plot(x, y)
            val tile = tile(x, y)
            plot.resource?.let {
                if (it !in resources) { // a resource of Vox Populi that Brave New World's ruleset does not have (amber, maize, rice, coffee, ...): left off the map, counted in the summary
                     skippedResources.merge(it, 1, Int::plus); return@let }
                val resource = ruleset.tileResources[resources[it] ?: error("resource $it")] ?: error("no resource ${resources[it]}")
                tile.setTileResource(resource, majorDeposit = true)
                tile.resourceAmount = if (resource.resourceType == com.unciv.models.ruleset.tile.ResourceType.Strategic) plot.resourceQuantity else 0
            }
            if (plot.improvement == "IMPROVEMENT_GOODY_HUT") { tile.improvement = "Ancient ruins"; ruins++ }
            else require(plot.improvement == null) { "improvement ${plot.improvement}" }
            val wonder = plot.feature?.let { naturalWonders[it] }
            if (wonder != null) {
                val terrain = ruleset.terrains[wonder] ?: error("no natural wonder $wonder")
                tile.naturalWonder = wonder
                tile.tileResource = null
                tile.improvement = null
                tile.removeTerrainFeatures()
                ruleset.terrains[terrain.turnsInto]?.let { tile.setBaseTerrain(it) }
                tile.setTerrainTransients()
            }
        }

        // Starts: the civilizations 0..11 and the city-states from 22 on
        val starts = root.get("starts")
        val players = root.get("players")
        var civilizations = 0
        var cityStates = 0
        for (start in starts) {
            val player = players.get(start.name).let { listOf(it.getString(0), it.getString(3)) }
            val nation = if (player[0] == "CIVILIZATION_MINOR") cityStateName(player[1]).also { cityStates++ }
                else (nations[player[0]] ?: civilizationName(player[0], ruleset)).also { civilizations++ }
            require(nation in ruleset.nations) { "no nation $nation" }
            map.startingLocations.add(TileMap.StartingLocation(grid.hexOf(start.getInt(0), start.getInt(1)), nation, TileMap.StartingLocation.Usage.Player))
        }

        FileConversions.writeJson(Gdx.files.absolute(System.getenv("BNW_CLONE_OUT")), map, zip = false)
        File(System.getenv("BNW_CLONE_OUT") + ".summary.txt").writeText(summary(map, ruleset, rivers, ruins, civilizations, cityStates) +
            if (skippedResources.isEmpty()) "" else "resources left off (no counterpart in the ruleset): $skippedResources\n")
    }

    /** Every tile has exactly the neighbours its Civ V plot has (the wrap included), and the distance of Unciv is the number of steps between them */
    private fun verifyGeometry(map: TileMap, grid: CivVMapGrid) {
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, -1 to 1, 0 to -1, 1 to -1)
        val expected = HashMap<Tile, List<Tile>>()
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            val tile = map[grid.hexOf(x, y)]
            val neighbors = directions.mapNotNull { (dq, dr) -> grid.neighborOf(x, y, dq, dr)?.let { map[grid.hexOf(it.first, it.second)] } }
            require(tile.neighbors.toSet() == neighbors.toSet()) { "neighbours of ($x, $y): ${tile.neighbors.map { grid.civVOf(it.position) }} not ${neighbors.map { grid.civVOf(it.position) }}" }
            expected[tile] = neighbors
        }
        val (w, h) = grid.width to grid.height
        for (source in listOf(0 to 0, w - 1 to 0, 0 to h - 1, w / 2 to h / 2, w * 5 / 128 to h * 17 / 80, w * 120 / 128 to h * 60 / 80, w / 2 - 1 to h - 1)) {
            val start = map[grid.hexOf(source.first, source.second)]
            val steps = HashMap<Tile, Int>().apply { put(start, 0) }
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val tile = queue.removeFirst()
                for (neighbor in expected[tile]!!) if (steps.putIfAbsent(neighbor, steps[tile]!! + 1) == null) queue.addLast(neighbor)
            }
            for (tile in map.tileList) require(start.aerialDistanceTo(tile) == steps[tile]) { "distance from $source to ${grid.civVOf(tile.position)}: ${start.aerialDistanceTo(tile)}, steps ${steps[tile]}" }
        }
    }

    private fun summary(map: TileMap, ruleset: Ruleset, rivers: Int, ruins: Int, civilizations: Int, cityStates: Int): String {
        val text = StringBuilder()
        text.appendLine("tiles ${map.tileList.size}, land ${map.tileList.count { it.isLand }}, river edges $rivers, ancient ruins $ruins, majors $civilizations, city-states $cityStates")
        text.appendLine("terrain " + map.tileList.groupingBy { it.baseTerrain }.eachCount().toSortedMap())
        text.appendLine("features " + map.tileList.flatMap { it.terrainFeatures }.groupingBy { it }.eachCount().toSortedMap())
        text.appendLine("natural wonders " + map.tileList.mapNotNull { it.naturalWonder }.groupingBy { it }.eachCount().toSortedMap())
        text.appendLine("resources " + map.tileList.mapNotNull { it.resource }.groupingBy { it }.eachCount().toSortedMap())
        text.appendLine("hills " + map.tileList.count { "Hill" in it.terrainFeatures } + ", mountains " + map.tileList.count { it.baseTerrain == "Mountain" })
        text.appendLine("water types " + ruleset.terrains.values.filter { it.type == TerrainType.Water }.map { it.name })
        return text.toString()
    }
}
