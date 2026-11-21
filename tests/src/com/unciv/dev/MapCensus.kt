package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.MapSaver
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.StartFairness
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.audio.MusicController
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What generated maps look like, to compare map types, sizes and the generator against each other and against a real map: for each seed one
 * map (generated as a new game does, with the fairness check) or one premade map file, measured, one `seed<TAB>metric<TAB>value` line per
 * metric. `tools/mapgen_census.py` runs it and tabulates. Skipped unless the environment variable CENSUS_OUT is set.
 *
 * Environment: CENSUS_OUT = the file written; CENSUS_SEEDS = `1-20` or `3,5,8` (default 1-10); CENSUS_CIVS (2) and CENSUS_CITYSTATES (4);
 * CENSUS_TYPE (default Continents (Civ V)); CENSUS_MAPSIZE = Auto (default: the size follows the players, see `CivVWorldSizes`), Tiny, Small, Medium,
 * Large or Huge, or CENSUS_MAPWIDTH and CENSUS_MAPHEIGHT together; CENSUS_WORLD_WRAP (any value); CENSUS_WATER = water threshold (0);
 * CENSUS_BALANCED = 0 switches the fairness check off; CENSUS_RULESET (default Civ V - Brave New World);
 * CENSUS_MAP_FILE = a map file (see [BnwMapClone]) measured instead of generating, once, as seed 0.
 * CENSUS_SAVE_DIR = a folder that also gets each generated map as `map-<seed>.map`, with its starting locations, to play games on.
 *
 * Metrics: tile counts by terrain, feature, resource and resource kind; landmasses; per civilization on its landmass the land per player, the
 * sites for more cities within 10 tiles and the start's fertility ([StartFairness.measure], so the check and the census agree); the distances
 * between starts; whether the map passes the fairness check and how many attempts it took.
 */
@RunWith(GdxTestRunner::class)
class MapCensus {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            Assume.assumeTrue("Set CENSUS_OUT to run", System.getenv("CENSUS_OUT") != null)
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f; citySoundsVolume = 0f; voicesVolume = 0f }
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = MusicController()
            RulesetCache.loadRulesets(noMods = true)
        }

        /** Sites are counted up to this many, to tell a start with room from one with plenty */
        private const val SITE_CAP = 12
    }

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private fun seeds(): List<Long> = env("CENSUS_SEEDS", "1-10").split(',').flatMap { part ->
        if ('-' in part) part.substringBefore('-').trim().toLong()..part.substringAfter('-').trim().toLong() else listOf(part.trim().toLong())
    }.toList()

    @Test
    fun census() {
        val ruleset = RulesetCache[env("CENSUS_RULESET", BaseRuleset.Civ_V_BNW.fullName)]!!
        val out = StringBuilder()
        val mapFile = System.getenv("CENSUS_MAP_FILE")
        if (mapFile != null) {
            val map = MapSaver.loadMap(Gdx.files.absolute(mapFile))
            map.ruleset = ruleset
            map.setTransients(ruleset)
            map.assignContinents(TileMap.AssignContinentsMode.Ensure) // a premade map carries no continent sizes
            measure(0, map, ruleset, 1, true, out)
        } else {
            for (seed in seeds()) {
                val (map, attempts, passes) = generate(ruleset, seed)
                measure(seed, map, ruleset, attempts, passes, out)
                System.getenv("CENSUS_SAVE_DIR")?.let { dir ->
                    File(dir).mkdirs()
                    map.mapParameters.name = "${env("CENSUS_TYPE", MapType.continentsLikeCivV)} $seed"
                    // listOf(x) is an immutable singleton class that the map file would name and the loader cannot create
                    for (tile in map.values) tile.setTerrainFeatures(ArrayList(tile.terrainFeatures))
                    FileConversions.writeJson(Gdx.files.absolute("$dir/map-$seed.map"), map, zip = false)
                }
            }
        }
        File(System.getenv("CENSUS_OUT")).writeText(out.toString())
    }

    private fun generate(ruleset: Ruleset, seed: Long): Triple<TileMap, Int, Boolean> {
        val majors = ruleset.nations.values.filter { it.isMajorCiv }.take(env("CENSUS_CIVS", "2").toInt())
        val minors = ruleset.nations.values.filter { it.isCityState }.take(env("CENSUS_CITYSTATES", "4").toInt())
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (nation in majors + minors) {
            val civ = Civilization(nation)
            civ.gameInfo = gameInfo
            gameInfo.civilizations.add(civ)
        }
        val parameters = MapParameters().apply {
            type = env("CENSUS_TYPE", MapType.continentsLikeCivV)
            val width = env("CENSUS_MAPWIDTH", "").toIntOrNull()
            val height = env("CENSUS_MAPHEIGHT", "").toIntOrNull()
            shape = MapShape.rectangular // Civ V's maps are
            if (width != null && height != null) {
                mapSize = MapSize(width, height)
            } else {
                mapSize = when (env("CENSUS_MAPSIZE", "Auto")) {
                    "Tiny" -> MapSize.Tiny
                    "Small" -> MapSize.Small
                    "Medium" -> MapSize.Medium
                    "Large" -> MapSize.Large
                    "Huge" -> MapSize.Huge
                    else -> MapSize(MapSize.auto)
                }
            }
            worldWrap = System.getenv("CENSUS_WORLD_WRAP") != null
            waterThreshold = env("CENSUS_WATER", "0").toFloat()
            balancedStarts = env("CENSUS_BALANCED", "1") != "0"
            this.seed = seed
        }
        val generator = MapGenerator(ruleset)
        val map = generator.generateMap(parameters, GameParameters().apply { numberOfCityStates = minors.size }, gameInfo)
        return Triple(map, generator.startFairnessAttempts, StartFairness(map, ruleset).evaluate().passes)
    }

    private fun measure(seed: Long, map: TileMap, ruleset: Ruleset, attempts: Int, passes: Boolean, out: StringBuilder) {
        fun put(key: String, value: Number) { out.append(seed).append('\t').append(key).append('\t').append(value).append('\n') }
        val tiles = map.values.toList()
        val land = tiles.filter { it.isLand }
        put("tiles", tiles.size)
        put("land", land.size)
        put("land%", 100.0 * land.size / tiles.size)
        put("width", map.mapParameters.mapSize.width)
        put("height", map.mapParameters.mapSize.height)
        val counts = HashMap<String, Int>()
        fun add(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        for (tile in tiles) {
            add("base:${tile.baseTerrain}")
            for (feature in tile.terrainFeatures) add("feature:$feature")
            if (tile.isLand && tile.terrainFeatures.isEmpty()) add("bare:${tile.baseTerrain}")
            tile.resource?.let {
                add("resource:$it")
                add("kind:${ruleset.tileResources[it]?.resourceType}")
            }
            tile.improvement?.let { add("improvement:$it") }
            if (tile.naturalWonder != null) add("naturalWonders")
            if (tile.hasBottomRiver || tile.hasBottomLeftRiver || tile.hasBottomRightRiver) add("riverTiles")
        }
        for ((key, value) in counts.toSortedMap()) put(key, value)
        // by share of land, for comparing maps of different sizes
        for (terrain in listOf("Snow", "Tundra", "Desert", "Plains", "Grassland", "Mountain")) {
            put("land%:$terrain", 100.0 * (counts["base:$terrain"] ?: 0) / land.size.coerceAtLeast(1))
        }
        for (feature in listOf("Hill", "Forest", "Jungle", "Marsh")) {
            put("land%:$feature", 100.0 * (counts["feature:$feature"] ?: 0) / land.size.coerceAtLeast(1))
        }

        val landmasses = map.continentSizes.values.sortedDescending()
        put("landmasses", landmasses.size)
        put("landmasses>=10", landmasses.count { it >= 10 })
        put("biggestLandmass%", if (land.isEmpty()) 0.0 else 100.0 * landmasses.firstOrNull().let { it ?: 0 } / land.size)

        val fairness = StartFairness(map, ruleset)
        val measures = fairness.measure(SITE_CAP)
        val starts = map.startingLocations.mapNotNull { location -> map[location.position]?.let { Pair(it, location.nation) } }
        val majorStarts = starts.filter { ruleset.nations[it.second]?.isMajorCiv == true }
        val minorStarts = starts.filter { ruleset.nations[it.second]?.isCityState == true }
        put("majors", majorStarts.size)
        put("cityStates", minorStarts.size)
        put("players", starts.size)
        put("land/player", land.size.toDouble() / starts.size.coerceAtLeast(1))
        if (measures.isNotEmpty()) {
            put("onLandmass:land/player:min", measures.minOf { it.landPerPlayer })
            put("onLandmass:land/player:mean", measures.map { it.landPerPlayer }.average())
            put("sites:min", measures.minOf { it.sites })
            put("sites:mean", measures.map { it.sites }.average())
            put("fertility:mean", measures.map { it.fertility }.average())
            put("fertility:ratio:min", measures.minOf { it.fertilityRatio })
            put("fertility:ratio:max", measures.maxOf { it.fertilityRatio })
            put("startsOnLandmass:max", measures.maxOf { it.startsOnLandmass })
            put("majorsOnSeparateLandmasses", measures.map { it.tile.getContinent() }.distinct().size)
        }
        if (majorStarts.size >= 2) {
            val between = majorStarts.flatMapIndexed { i, a -> majorStarts.drop(i + 1).map { a.first.aerialDistanceTo(it.first) } }
            put("distance:majors:min", between.min())
            put("distance:majors:mean", between.average())
            put("distance:majors:nearest:mean", majorStarts.map { a -> majorStarts.filter { it !== a }.minOf { a.first.aerialDistanceTo(it.first) } }.average())
        }
        if (minorStarts.isNotEmpty() && majorStarts.isNotEmpty())
            put("distance:cityState:toNearestMajor:mean", minorStarts.map { c -> majorStarts.minOf { c.first.aerialDistanceTo(it.first) } }.average())
        put("fairnessPasses", if (passes) 1 else 0)
        put("fairnessAttempts", attempts)
    }
}
