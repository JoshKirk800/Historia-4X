package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.map.mapgenerator.CivVLayeredScripts
import com.unciv.logic.map.mapgenerator.CivVLayeredWorld
import com.unciv.logic.map.mapgenerator.CivVRegionalScripts
import com.unciv.logic.map.mapgenerator.CivVScript
import com.unciv.logic.map.mapgenerator.CivVScripts
import com.unciv.logic.map.mapgenerator.CivVWorldSize
import com.unciv.logic.map.mapgenerator.DonutCentre
import com.unciv.logic.map.mapgenerator.HexGeometry
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.PointyHexGeometry
import com.unciv.logic.map.mapgenerator.isCoastalLandLikeCivV
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 *  The scripts of the layered family and the two regional scripts that are map types after Four Corners and Inland Sea: Lakes.lua, North_vs_South.lua,
 *  West_vs_East.lua, Highlands.lua, Boreal.lua and Donut.lua. The geometry of each land step (the shares and shapes the scripts' constants give) on the scripts' own
 *  grid, then the terrain, features and grid on the tiles of a generated map.
 */
@RunWith(GdxTestRunner::class)
class CivVLayerScriptsTests {

    private fun generate(type: String, width: Int, height: Int, seed: Int, wrapX: Boolean = false, age: Int = 4, centre: DonutCentre = DonutCentre.Ocean) =
        CivVLayeredScripts.generate(type, PointyHexGeometry(width, height, wrapX), wrapX, Random(seed), age, donutCentre = centre)

    private fun world(width: Int, height: Int, seed: Int, wrapX: Boolean = false, size: CivVWorldSize = CivVWorldSize.Standard, seedDistance: Int = CivVLayeredWorld.MIN_SEED_DISTANCE) =
        CivVLayeredWorld(PointyHexGeometry(width, height, wrapX), wrapX, Random(seed), size, seedDistance)

    private fun CivVLayeredWorld.Result.cells(width: Int, height: Int, predicate: (Int, Int) -> Boolean) =
        (0 until width).sumOf { x -> (0 until height).count { y -> predicate(x, y) } }

    /** The sizes of the connected bodies of cells that [isMember], by flood fill over the steps of the geometry */
    private fun bodySizes(geometry: HexGeometry, isMember: (Int, Int) -> Boolean): List<Int> {
        val seen = HashSet<Pair<Int, Int>>()
        val sizes = ArrayList<Int>()
        for (x in 0 until geometry.width) for (y in 0 until geometry.height) {
            if (!isMember(x, y) || (x to y) in seen) continue
            var size = 0
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(x to y)
            seen.add(x to y)
            while (queue.isNotEmpty()) {
                val (cx, cy) = queue.removeFirst()
                size++
                for (n in geometry.neighbors(cx, cy)) if (isMember(n.first, n.second) && seen.add(n)) queue.add(n)
            }
            sizes.add(size)
        }
        return sizes
    }

    private fun countBodies(geometry: HexGeometry, isMember: (Int, Int) -> Boolean) = bodySizes(geometry, isMember).size

    private fun ruleset() = RulesetCache.loadRulesets(noMods = true).let { RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!! }

    private fun map(type: String, seed: Long, size: MapSize): TileMap {
        val params = MapParameters().apply { this.seed = seed; this.type = type; mapSize = size }
        return MapGenerator(ruleset()).generateMap(params)
    }

    // region Lakes

    @Test
    fun `lakes is land in all rows but two at each pole, the lakes cut at the share of the size of the bodies of water`() {
        val width = 52
        val height = 32
        for ((size, share) in listOf(1 to 0.07, 2 to 0.10, 3 to 0.15)) {
            val seaShares = (1..8).map { seed ->
                val world = world(width, height, seed, wrapX = true)
                CivVLayeredScripts.lakes(world, Random(seed), 4, size)
                val result = world.result()
                for (x in 0 until width) for (y in listOf(0, 1, height - 2, height - 1))
                    assertFalse("size $size seed $seed: the pole rows are sea at $x,$y", result.land[x][y])
                val rows = height - 4
                result.cells(width, height) { x, y -> y in 2 until height - 2 && !result.land[x][y] }.toDouble() / (width * rows)
            }
            assertEquals("size $size: lake share of the land rows", share, seaShares.average(), 0.035)
        }
    }

    @Test
    fun `the three sizes of lakes are drawn when none is asked for`() {
        // seas have the biggest bodies of water, small lakes the smallest
        fun largest(seed: Int): Int {
            val geometry = PointyHexGeometry(52, 32, true)
            val result = generate(MapType.lakesLikeCivV, 52, 32, seed, wrapX = true)
            var best = 0
            val seen = HashSet<Pair<Int, Int>>()
            for (x in 0 until 52) for (y in 2 until 30) {
                if (result.land[x][y] || (x to y) in seen) continue
                var size = 0
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(x to y)
                seen.add(x to y)
                while (queue.isNotEmpty()) {
                    val (cx, cy) = queue.removeFirst()
                    size++
                    for (n in geometry.neighbors(cx, cy)) if (n.second in 2 until 30 && !result.land[n.first][n.second] && seen.add(n)) queue.add(n)
                }
                best = maxOf(best, size)
            }
            return best
        }
        val sizes = (1..30).map { largest(it) }
        assertTrue("some map has only small bodies of water: $sizes", sizes.min() <= 12)
        assertTrue("some map has a sea: $sizes", sizes.max() >= 25)
    }

    // endregion

    // region North vs South and West vs East

    @Test
    fun `the lake grain of north vs south and west vs east is the nil that the engine reads as 0, the smoothest fractal`() {
        assertEquals(0, CivVLayeredScripts.LAKE_GRAIN_NIL)
        // grain 0: four random corners, the rest a blend: the top 8 % of the cells is a few big lakes (and crumbs at their edges), not a dozen ponds
        for (type in listOf(MapType.northVsSouthLikeCivV, MapType.westVsEastLikeCivV)) for (seed in 1..8) {
            val (width, height) = if (type == MapType.northVsSouthLikeCivV) 36 to 36 else 44 to 28
            val geometry = PointyHexGeometry(width, height, false)
            val result = generate(type, width, height, seed)
            val sizes = bodySizes(geometry) { x, y -> !result.land[x][y] && !(type == MapType.westVsEastLikeCivV && x in width / 2 - 2..width / 2 + 1) }
            assertTrue("$type seed $seed: bodies of water $sizes", sizes.size <= 8 && sizes.max() >= 10)
        }
    }

    @Test
    fun `north vs south is all land and a lake of 8 percent`() {
        val shares = (1..10).map { seed ->
            val result = generate(MapType.northVsSouthLikeCivV, 36, 36, seed)
            result.cells(36, 36) { x, y -> !result.land[x][y] }.toDouble() / (36 * 36)
        }
        assertEquals("sea share", 0.08, shares.average(), 0.03)
    }

    @Test
    fun `west vs east has two landmasses divided by a strip of sea that is four columns wide outside the middle rows`() {
        for ((width, height) in listOf(20 to 14, 44 to 28, 64 to 40)) for (seed in 1..4) {
            val geometry = PointyHexGeometry(width, height, false)
            val result = generate(MapType.westVsEastLikeCivV, width, height, seed)
            for (y in 0 until height) {
                val middleRow = y in height / 2 - 2..height / 2 + 1
                for (x in width / 2 - 2..width / 2 + 1) {
                    val where = "$width x $height seed $seed at $x,$y"
                    if (x == width / 2 || x == width / 2 - 1 || !middleRow) assertFalse("sea: $where", result.land[x][y])
                }
            }
            // no land cell of the west half is next to a land cell of the east half
            val west = countBodies(geometry) { x, y -> result.land[x][y] && x < width / 2 }
            val joined = countBodies(geometry) { x, y -> result.land[x][y] }
            assertTrue("$width x $height seed $seed: $joined landmasses against $west in the west alone", joined >= 2 && west >= 1)
            for (x in 0 until width) for (y in 0 until height) if (result.land[x][y] && x == width / 2 - 1)
                fail("land in the column width / 2 - 1")
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    @Test
    fun `the strip of west vs east leaves land in the middle rows beside it`() {
        // the two middle columns are the only sea there: the columns either side stay land unless the lake covers them
        var landCells = 0
        var total = 0
        for (seed in 1..10) {
            val result = generate(MapType.westVsEastLikeCivV, 44, 28, seed)
            for (y in 12..15) for (x in listOf(20, 23)) { total++; if (result.land[x][y]) landCells++ }
        }
        assertTrue("land beside the narrow strip: $landCells of $total", landCells >= total * 0.8)
    }

    // endregion

    // region Highlands

    @Test
    fun `highlands has no sea round the edge and cuts the lakes, hills and peaks at the percentiles of the options`() {
        val width = 64
        val height = 40
        val cells = width * height.toDouble()
        class Shares(val sea: Double, val mountains: Double, val hills: Double)
        // (pattern, density, lake size): the first options is 70th percentile peaks, 40th hills and the top 8 % of the lakes' fractal; the second 83rd, 50th and 15 %
        fun shares(pattern: Int, density: Int, lakeSize: Int): Shares {
            val seas = ArrayList<Double>()
            val mountains = ArrayList<Double>()
            val hills = ArrayList<Double>()
            var edgeLand = 0
            var edgeCells = 0
            for (seed in 1..8) {
                val world = world(width, height, seed)
                CivVRegionalScripts.highlands(world, Random(seed), CivVWorldSize.Standard, pattern, density, lakeSize)
                val result = world.result()
                seas.add(result.cells(width, height) { x, y -> !result.land[x][y] } / cells)
                mountains.add(result.cells(width, height) { x, y -> result.mountains[x][y] } / cells)
                hills.add(result.cells(width, height) { x, y -> result.hills[x][y] } / cells)
                for (x in 0 until width) for (y in 0 until height) if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    edgeCells++
                    if (result.land[x][y]) edgeLand++
                }
            }
            assertTrue("($pattern, $density, $lakeSize): edge land $edgeLand of $edgeCells", edgeLand > edgeCells * 0.8)
            return Shares(seas.average(), mountains.average(), hills.average())
        }

        // the cells read the fractal's nodes by interpolation, which draws them towards the middle of the field: the tails (peaks) are a little thinner
        // than the percentile says, the band between hills and peaks thicker
        val dense = shares(2, 1, 1)
        assertEquals("sea share (8 % lakes)", 0.08, dense.sea, 0.03)
        assertTrue("mountain share ${dense.mountains}", dense.mountains in 0.12..0.24)
        assertTrue("hill share ${dense.hills}", dense.hills in 0.32..0.50)
        val thin = shares(2, 3, 3)
        assertEquals("sea share (15 % lakes)", 0.15, thin.sea, 0.035)
        assertTrue("fewer peaks and more lakes: ${thin.mountains} against ${dense.mountains}", thin.mountains < dense.mountains * 0.8)
        assertTrue("the ridges of the first pattern are in the terrain's fractal, whose peaks keep their share", shares(1, 1, 1).mountains in 0.10..0.26)
    }

    @Test
    fun `the grains of highlands are read in the order of the table, so scattered and clusters are swapped`() {
        // the option index picks the grain: 1 ridgelines 5, 2 (scattered) 6 as clusters, 3 ranges 5, 4 (clusters) 4 as scattered, by the world size 64 x 40
        // (observable as the number of separate mountain bodies: a finer grain makes more of them)
        fun bodies(pattern: Int) = (1..6).sumOf { seed ->
            val world = world(64, 40, seed)
            CivVRegionalScripts.highlands(world, Random(seed), CivVWorldSize.Standard, pattern, 2, 2)
            val result = world.result()
            countBodies(PointyHexGeometry(64, 40, false)) { x, y -> result.mountains[x][y] }
        }
        assertTrue("option 4 (grain 4) has fewer mountain bodies than option 2 (grain 6): ${bodies(4)} against ${bodies(2)}", bodies(4) < bodies(2))
    }

    // endregion

    // region Boreal

    @Test
    fun `boreal has a sea along the top that is deeper in the middle, and land below it`() {
        val width = 64
        val height = 40
        for (seed in 1..6) {
            val result = generate(MapType.borealLikeCivV, width, height, seed)
            for (x in 0 until width) {
                val yShift = floor(height / 10.0 - abs(x - width / 2.0) / (width / 2.0) * (height / 10.0)).toInt()
                for (y in height - 2 - yShift until height)
                    assertFalse("seed $seed: sea at $x,$y", result.land[x][y])
            }
            // the rim is rough by 0 to 3 rows: below it there is land
            val landColumns = (0 until width).count { x -> (0..3).any { y -> result.land[x][y] } }
            assertTrue("seed $seed: land at the south of $landColumns columns of $width", landColumns >= width * 0.8)
            // the sea runs down from the top row: at least 2 + a tenth of the height rows in the middle, at most 5 at the sides (a lake may add to a side)
            fun depth(x: Int) = (height - 1 downTo 0).takeWhile { y -> !result.land[x][y] }.size
            assertTrue("seed $seed: sea ${depth(width / 2)} rows deep at the middle", depth(width / 2) >= 2 + height / 10)
            assertTrue("seed $seed: sea ${(0..5).map { depth(it) }} rows deep at the west edge", (0..5).count { depth(it) <= 5 } >= 3)
        }
    }

    @Test
    fun `boreal lakes, hills and mountains below the sea`() {
        val width = 64
        val height = 40
        val seas = ArrayList<Double>()
        val mountains = ArrayList<Double>()
        for (seed in 1..8) {
            val result = generate(MapType.borealLikeCivV, width, height, seed)
            // rows from the bottom up to where even the lowest rim cannot reach: height - 2 - 4 - 3
            val rows = 0 until height - 9
            seas.add(result.cells(width, height) { _, y -> y in rows }.let { _ -> result.cells(width, height) { x, y -> y in rows && !result.land[x][y] }.toDouble() / (width * rows.count()) })
            mountains.add(result.cells(width, height) { x, y -> y in rows && result.mountains[x][y] }.toDouble() / (width * rows.count()))
        }
        // the top 6 % of the lake fractal
        assertEquals("lake share", 0.06, seas.average(), 0.03)
        // the (95 - 3) = 92nd percentile of the mountains' field and a seventh less for the passes
        assertEquals("mountain share", 0.08 * 0.85, mountains.average(), 0.035)
    }

    // endregion

    // region Donut

    private fun distanceFromCentre(x: Int, y: Int, width: Int, height: Int): Double {
        val cx = width / 2
        val cy = height / 2
        return if (x != cx || y != cy) sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()) else 0.0
    }

    @Test
    fun `donut is a ring of land with an ocean hole, on every grid`() {
        for ((size, grid) in CivVScripts.donut.grids!!) for (seed in 1..2) {
            val (width, height) = grid
            val radius = width / 2 - 4
            val holeRadius = radius / 2
            val result = generate(MapType.donutLikeCivV, width, height, seed)
            var ring = 0
            for (x in 0 until width) for (y in 0 until height) {
                val distance = distanceFromCentre(x, y, width, height)
                val where = "$size seed $seed at $x,$y"
                when {
                    distance > radius -> assertFalse("outside the ring is sea: $where", result.land[x][y])
                    distance < holeRadius -> assertFalse("the hole is sea: $where", result.land[x][y])
                    else -> { ring++; assertTrue("the ring is land: $where", result.land[x][y]) }
                }
            }
            assertEquals("$size: land cells", ring, result.cells(width, height) { x, y -> result.land[x][y] })
            assertEquals("$size: one ring", 1, countBodies(PointyHexGeometry(width, height, false)) { x, y -> result.land[x][y] })
        }
    }

    @Test
    fun `the centre of the donut is hills, mountains or ocean by the option, and land that is not the hole's for the standard option`() {
        val width = 36
        val height = 36
        val holeRadius = (width / 2 - 4) / 2
        for ((centre, check) in listOf<Pair<DonutCentre, (CivVLayeredWorld.Result, Int, Int) -> Boolean>>(
            DonutCentre.Hills to { r, x, y -> r.land[x][y] && r.hills[x][y] },
            DonutCentre.Mountains to { r, x, y -> r.land[x][y] && r.mountains[x][y] },
            DonutCentre.Ocean to { r, x, y -> !r.land[x][y] },
            DonutCentre.Standard to { r, x, y -> r.land[x][y] }
        )) {
            val result = generate(MapType.donutLikeCivV, width, height, 3, centre = centre)
            for (x in 0 until width) for (y in 0 until height)
                if (distanceFromCentre(x, y, width, height) < holeRadius) assertTrue("$centre at $x,$y", check(result, x, y))
        }
    }

    @Test
    fun `donut has hills and peaks by its percentiles and no world age`() {
        val width = 52
        val height = 52
        val mountains = ArrayList<Double>()
        val hills = ArrayList<Double>()
        for (seed in 1..8) {
            val result = generate(MapType.donutLikeCivV, width, height, seed)
            val ring = result.cells(width, height) { x, y -> result.land[x][y] }.toDouble()
            mountains.add(result.cells(width, height) { x, y -> result.mountains[x][y] } / ring)
            hills.add(result.cells(width, height) { x, y -> result.hills[x][y] } / ring)
            val young = generate(MapType.donutLikeCivV, width, height, seed, age = 3)
            val old = generate(MapType.donutLikeCivV, width, height, seed, age = 5)
            for (x in 0 until width) for (y in 0 until height) {
                assertEquals(young.hills[x][y], old.hills[x][y])
                assertEquals(young.mountains[x][y], old.mountains[x][y])
            }
        }
        // the top 4 % of a fractal that is zero at the edge, so its highest cells are in the middle, where the hole is: the ring has a part of them (0.5 % to 4 %
        // of its land by size); 5 % + 4 % of hills and two bands of 10 %
        assertTrue("mountain share ${mountains.average()}", mountains.average() in 0.001..0.06)
        assertTrue("hill share ${hills.average()}", hills.average() in 0.18..0.42)
    }

    // endregion

    // region Ridges on a small grid

    @Test
    fun `the seeds of the ridges are 7 nodes of the original's 128 columns apart, as cells`() {
        assertEquals(1, CivVLayeredWorld.nodeSeedDistance(16))
        assertEquals(1, CivVLayeredWorld.nodeSeedDistance(24))
        assertEquals(2, CivVLayeredWorld.nodeSeedDistance(36))
        assertEquals(4, CivVLayeredWorld.nodeSeedDistance(64))
        assertEquals(6, CivVLayeredWorld.nodeSeedDistance(104))
        for (script in listOf(CivVScripts.lakes, CivVScripts.northVsSouth, CivVScripts.westVsEast, CivVScripts.highlands, CivVScripts.boreal))
            assertTrue(script.ridgesOnNodeScale)
        assertFalse(CivVScripts.fourCorners.ridgesOnNodeScale)
        assertFalse(CivVScripts.inlandSea.ridgesOnNodeScale)
    }

    @Test
    fun `a ridge field on a grid too small for its seeds does not divide by zero`() {
        // 30 plates on 16 x 16 cells at 7 cells apart: the 200 tries leave seeds on one cell
        for (seed in 1..30) {
            val world = world(16, 16, seed, size = CivVWorldSize.Huge)
            world.fill(CivVLayeredWorld.LAND)
            world.applyTectonics()
            assertTrue(world.percent(world.ridgeField(com.unciv.logic.map.mapgenerator.CivVFractal(16, 16, 3, false, false, Random(seed)), 30, false, 1, 2), 50) >= 0)
        }
    }

    // endregion

    // region The grids and the script's own rules

    @Test
    fun `the world size of the player's size picks the grid of each script, and only lakes wraps`() {
        val expected = mapOf(
            MapType.lakesLikeCivV to listOf(24 to 16, 32 to 20, 40 to 24, 52 to 32, 64 to 40, 84 to 52),
            MapType.northVsSouthLikeCivV to listOf(16 to 16, 24 to 24, 30 to 30, 36 to 36, 44 to 44, 56 to 56),
            MapType.westVsEastLikeCivV to listOf(20 to 14, 30 to 20, 36 to 24, 44 to 28, 52 to 32, 64 to 40),
            MapType.highlandsLikeCivV to listOf(32 to 20, 40 to 24, 52 to 32, 64 to 40, 84 to 52, 104 to 64),
            MapType.borealLikeCivV to listOf(32 to 20, 40 to 24, 52 to 32, 64 to 40, 84 to 52, 104 to 64),
            MapType.donutLikeCivV to listOf(24 to 24, 36 to 36, 44 to 44, 52 to 52, 64 to 64, 80 to 80)
        )
        // 960, 2016, 2772, 4160, 6656 and 10240 tiles: Duel to Huge
        val sizes = listOf(40 to 24, 56 to 36, 66 to 42, 80 to 52, 104 to 64, 128 to 80)
        for ((type, grids) in expected) for ((index, size) in sizes.withIndex()) {
            val parameters = MapParameters().apply { this.type = type; mapSize = MapSize(size.first, size.second); worldWrap = type != MapType.lakesLikeCivV }
            val own = CivVScripts.withOwnGrid(parameters)!!
            assertEquals("$type $size", grids[index], own.mapSize.width to own.mapSize.height)
            assertEquals(MapShape.rectangular, own.shape)
            assertEquals("$type wraps: ${type == MapType.lakesLikeCivV}", type == MapType.lakesLikeCivV, own.worldWrap)
            assertEquals(CivVScripts.of(type).worldSizeOfGrid(grids[index].first), CivVWorldSize.entries[index])
        }
        assertNull(CivVScripts.withOwnGrid(MapParameters().apply { type = MapType.boreal }))
        assertNull(CivVScripts.withOwnGrid(MapParameters().apply { type = MapType.lakes }))
    }

    @Test
    fun `the latitude, ice and terrain code of each script`() {
        assertEquals(0.8, CivVScripts.northVsSouth.latitude(1.0), 1e-9)
        assertEquals(0.8, CivVScripts.westVsEast.latitude(1.0), 1e-9)
        assertEquals(0.8, CivVScripts.highlands.latitude(1.0), 1e-9)
        assertEquals(1.0, CivVScripts.lakes.latitude(1.0), 1e-9)
        assertEquals(1.0, CivVScripts.donut.latitude(1.0), 1e-9)
        assertEquals(CivVScript.Climate.ColdPole, CivVScripts.highlands.climate)
        assertEquals(CivVScript.TerrainCode.DesertAtTheEquator, CivVScripts.northVsSouth.terrain)
        assertEquals(CivVScript.TerrainCode.Standard, CivVScripts.westVsEast.terrain)
        assertEquals(CivVScript.TerrainCode.Bands, CivVScripts.donut.terrain)
        assertEquals(CivVScript.TerrainCode.Boreal, CivVScripts.boreal.terrain)
        assertTrue(CivVScripts.lakes.ice)
        assertEquals(CivVScript.IceCode.TwoPolarRows, CivVScripts.lakes.iceCode)
        assertEquals(CivVScript.IceCode.Boreal, CivVScripts.boreal.iceCode)
        for (script in listOf(CivVScripts.northVsSouth, CivVScripts.westVsEast, CivVScripts.highlands, CivVScripts.donut)) assertFalse(script.ice)
        assertTrue(CivVScripts.boreal.keepsCoastalMountains)
        assertTrue(CivVScripts.lakes.mountainsOffTheCoast && CivVScripts.westVsEast.mountainsOffTheCoast && CivVScripts.donut.mountainsOffTheCoast)
        assertFalse(CivVScripts.northVsSouth.mountainsOffTheCoast || CivVScripts.highlands.mountainsOffTheCoast)
    }

    // endregion

    // region On the tiles of a map

    private fun rowOf(map: TileMap, tile: com.unciv.logic.map.tile.Tile) = tile.getRow() - map.values.minOf { it.getRow() }

    @Test
    fun `lakes on the tiles wraps, has water in the pole rows and ice on them`() {
        val map = map(MapType.lakesLikeCivV, 4, MapSize(80, 52))
        assertEquals(52, map.mapParameters.mapSize.width)
        assertEquals(32, map.mapParameters.mapSize.height)
        assertTrue(map.mapParameters.worldWrap)
        var iceRows = 0
        for (tile in map.values) {
            val row = rowOf(map, tile)
            if (row < 2 || row >= 30) {
                assertTrue("water in the pole row $row at ${tile.position}", tile.isWater)
                // the ice goes on the ocean and coast tiles that have no feature (an atoll or a natural wonder may be there)
                if (tile.terrainFeatures.isEmpty() && (tile.baseTerrain == Constants.ocean || tile.baseTerrain == Constants.coast)) fail("a pole row tile without ice at ${tile.position}")
                if (Constants.ice in tile.terrainFeatures) iceRows++
            }
        }
        assertTrue("ice in the pole rows: $iceRows", iceRows > 100)
    }

    @Test
    fun `the equator of north vs south is desert where the others have grass`() {
        var desert = 0
        var grass = 0
        for (seed in 1L..6L) {
            val map = map(MapType.northVsSouthLikeCivV, seed, MapSize(80, 52))
            val height = map.mapParameters.mapSize.height
            for (tile in map.values) {
                val row = rowOf(map, tile)
                if (!tile.isLand || tile.isImpassible() || abs(row - height / 2.0) > height * 0.05) continue
                if (tile.baseTerrain == Constants.desert) desert++
                if (tile.baseTerrain == Constants.grassland) grass++
            }
            assertFalse("seed $seed: ice", map.values.any { Constants.ice in it.terrainFeatures })
        }
        assertTrue("desert $desert against grass $grass in the band round the equator", desert > grass * 3)
    }

    @Test
    fun `donut has no snow, tundra, jungle or ice, and its terrain has no latitude`() {
        for (seed in 1L..3L) {
            val map = map(MapType.donutLikeCivV, seed, MapSize(80, 52))
            assertEquals(52, map.mapParameters.mapSize.width)
            for (tile in map.values) {
                assertTrue("${tile.baseTerrain} at ${tile.position}", tile.baseTerrain != Constants.snow && tile.baseTerrain != Constants.tundra)
                assertFalse(Constants.jungle in tile.terrainFeatures)
                assertFalse(Constants.ice in tile.terrainFeatures)
            }
            val terrains = map.values.filter { it.isLand && !it.isImpassible() }.map { it.baseTerrain }.toSet()
            assertTrue("grass, plains and desert: $terrains", terrains.containsAll(listOf(Constants.grassland, Constants.plains, Constants.desert)))
        }
    }

    @Test
    fun `highlands has its cold pole at the north or the south by a coin and no ice`() {
        val coldNorth = ArrayList<Boolean>()
        for (seed in 1L..10L) {
            val map = map(MapType.highlandsLikeCivV, seed, MapSize(80, 52))
            val height = map.mapParameters.mapSize.height
            fun cold(from: Int, to: Int) = map.values.count { rowOf(map, it) in from until to && (it.baseTerrain == Constants.tundra || it.baseTerrain == Constants.snow) }
            val north = cold(height - 10, height)
            val south = cold(0, 10)
            assertTrue("seed $seed: tundra and snow in the ten northern rows $north, southern $south", abs(north - south) > 20)
            coldNorth.add(north > south)
            assertFalse(map.values.any { Constants.ice in it.terrainFeatures })
        }
        assertTrue("both poles are drawn: $coldNorth", coldNorth.contains(true) && coldNorth.contains(false))
    }

    @Test
    fun `boreal has only snow, tundra and plains, no marsh, jungle or atolls, ice on the sea at the top`() {
        for (seed in 1L..4L) {
            val map = map(MapType.borealLikeCivV, seed, MapSize(80, 52))
            val height = map.mapParameters.mapSize.height
            var iceTop = 0
            for (tile in map.values) {
                if (tile.isLand && !tile.isImpassible())
                    assertTrue("seed $seed: ${tile.baseTerrain} at ${tile.position}", tile.baseTerrain in listOf(Constants.snow, Constants.tundra, Constants.plains))
                assertFalse(Constants.jungle in tile.terrainFeatures)
                assertFalse("Marsh" in tile.terrainFeatures)
                assertFalse("Atoll" in tile.terrainFeatures)
                if (Constants.ice in tile.terrainFeatures) {
                    assertTrue("ice in row ${rowOf(map, tile)}", rowOf(map, tile) >= height - 5)
                    iceTop++
                }
            }
            assertTrue("seed $seed: ice at the top: $iceTop", iceTop >= map.mapParameters.mapSize.width)
            // the four rows below the top are ice on part of the ocean (70, 50, 30 and 20 %), none of the coast
            val below = map.values.filter { rowOf(map, it) in height - 5 until height - 1 }
            val iceBelow = below.count { Constants.ice in it.terrainFeatures }
            assertTrue("seed $seed: ice in the rows below the top: $iceBelow of ${below.count { it.baseTerrain == Constants.ocean || Constants.ice in it.terrainFeatures }}", iceBelow > 0)
            assertTrue("seed $seed: no ice on the coast", below.none { Constants.ice in it.terrainFeatures && it.baseTerrain == Constants.coast })
        }
    }

    @Test
    fun `a land tile that touches only a lake is not coastal, one that touches the sea is`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 12, newWidth = 12, baseTerrain = "Grassland")
        val inland = game.tileMap.values.first { tile -> tile.neighbors.count() == 6 && tile.neighbors.all { n -> n.neighbors.count() == 6 } }
        assertFalse(inland.isCoastalLandLikeCivV())
        val neighbors = inland.neighbors.toList()
        game.setTileTerrain(neighbors[0].position, "Lakes")
        assertFalse("a lake is not a coast", inland.isCoastalLandLikeCivV())
        game.setTileTerrain(neighbors[1].position, "Coast")
        assertTrue("the sea is", inland.isCoastalLandLikeCivV())
        assertFalse("water is not coastal land", neighbors[1].isCoastalLandLikeCivV())
    }

    @Test
    fun `mountains stay beside small lakes where the sea has none, in highlands and lakes`() {
        // Highlands has no sea, only lakes: with the BNW ruleset's noMountainsOnTheCoast every mountain beside a lake was a hill before
        var besideLake = 0
        for (seed in 1L..6L) {
            val map = map(MapType.highlandsLikeCivV, seed, MapSize(80, 52))
            besideLake += map.values.count { it.baseTerrain == Constants.mountain && it.neighbors.any { n -> n.baseTerrain == "Lakes" } }
        }
        assertTrue("mountains beside a lake in Highlands: $besideLake", besideLake > 20)
        // Lakes takes the mountains off the coast of the sea (bodies of ten tiles and more) and keeps them beside the small lakes
        var seaCoast = 0
        var lakeShore = 0
        for (seed in 1L..6L) {
            val map = map(MapType.lakesLikeCivV, seed, MapSize(80, 52))
            for (tile in map.values) if (tile.baseTerrain == Constants.mountain && tile.naturalWonder == null) {
                if (tile.neighbors.any { it.isWater && it.baseTerrain != "Lakes" }) seaCoast++
                if (tile.neighbors.any { it.baseTerrain == "Lakes" }) lakeShore++
            }
        }
        assertEquals("mountains on the coast of the sea", 0, seaCoast)
        assertTrue("mountains beside a lake in Lakes: $lakeShore", lakeShore > 5)
    }

    @Test
    fun `a map of highlands is about a fifth mountains`() {
        // 17 to 30 % of the cells are peaks, thinned by the passes and the lakes and by the cells' interpolation (the land step), and none of them goes to the coast
        val shares = (1L..8L).map { seed ->
            val map = map(MapType.highlandsLikeCivV, seed, MapSize(80, 52))
            val land = map.values.filter { it.isLand }
            land.count { it.baseTerrain == Constants.mountain }.toDouble() / land.size
        }
        assertEquals("mountain share of the land", 0.19, shares.average(), 0.06)
    }

    @Test
    fun `every new type is in the type list and makes its own hills and mountains`() {
        for (type in listOf(MapType.lakesLikeCivV, MapType.northVsSouthLikeCivV, MapType.westVsEastLikeCivV, MapType.highlandsLikeCivV, MapType.borealLikeCivV, MapType.donutLikeCivV)) {
            assertTrue(type, type in MapType.allValues)
            assertTrue(type, type in MapType.ownElevation)
        }
        // Unciv's own Lakes and Boreal are no longer offered, but they stay types that generate
        assertTrue(MapType.lakes in MapType.uncivValues && MapType.boreal in MapType.uncivValues)
        assertFalse(MapType.lakes in MapType.allValues || MapType.boreal in MapType.allValues)
        assertFalse(MapType.lakes in MapType.ownElevation || MapType.boreal in MapType.ownElevation)
    }

    // endregion
}
