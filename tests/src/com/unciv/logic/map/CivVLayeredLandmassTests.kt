package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.map.mapgenerator.CivVFractalFlags
import com.unciv.logic.map.mapgenerator.CivVLayerArgs
import com.unciv.logic.map.mapgenerator.CivVLayeredScripts
import com.unciv.logic.map.mapgenerator.CivVLayeredWorld
import com.unciv.logic.map.mapgenerator.CivVRegionShift
import com.unciv.logic.map.mapgenerator.CivVScripts
import com.unciv.logic.map.mapgenerator.CivVWorldSize
import com.unciv.logic.map.mapgenerator.FourCornersBuffer
import com.unciv.logic.map.mapgenerator.HexGeometry
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.PointyHexGeometry
import com.unciv.logic.map.mapgenerator.UncivHexGeometry
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.UncivMapSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  MultilayeredFractal.lua (the layer operations, the region shift, ApplyTectonics) and the two scripts written on it that are map types:
 *  Four_Corners.lua and InlandSea.lua.
 */
@RunWith(GdxTestRunner::class)
class CivVLayeredLandmassTests {

    private val ocean = CivVLayeredWorld.OCEAN
    private val land = CivVLayeredWorld.LAND

    private fun world(width: Int, height: Int, seed: Int, wrapX: Boolean = false, size: CivVWorldSize = CivVWorldSize.Standard) =
        CivVLayeredWorld(PointyHexGeometry(width, height, wrapX), wrapX, Random(seed), size)

    private fun CivVLayeredWorld.count(predicate: (Int) -> Boolean) =
        (0 until width).sumOf { x -> (0 until height).count { y -> predicate(plotAt(x, y)) } }

    private fun CivVLayeredWorld.share(predicate: (Int) -> Boolean) = count(predicate).toDouble() / (width * height)

    // region The layer operations

    @Test
    fun `a water layer turns the share it was not given into sea and leaves the given share as it was`() {
        val shares = (1..12).map { seed ->
            val world = world(100, 60, seed)
            world.fill(land)
            // "70 % unchanged": the layer is made like a land of 30 %, and where it has land the map becomes ocean
            world.waterLayer(CivVLayerArgs(100, 60, 0, 0, waterPercent = 70, grain = 2))
            val sea = world.share { it == ocean }
            assertTrue("seed $seed: sea share $sea", sea in 0.15..0.45)
            sea
        }
        assertEquals("mean sea share", 0.30, shares.average(), 0.04)
    }

    @Test
    fun `a water layer is cut into its rectangle only`() {
        val world = world(100, 60, 3)
        world.fill(land)
        world.waterLayer(CivVLayerArgs(30, 20, 40, 10, waterPercent = 40, grain = 2))
        var inside = 0
        for (x in 0 until 100) for (y in 0 until 60) {
            val isInside = x in 40 until 70 && y in 10 until 30
            if (world.plotAt(x, y) == ocean) {
                assertTrue("sea outside the rectangle at $x,$y", isInside)
                inside++
            }
        }
        assertTrue("sea inside: $inside of ${30 * 20}", inside in 200..500) // 60 % of the layer is sea
    }

    @Test
    fun `a fractal layer without hills has the land its water percent leaves, only inside its rectangle, and ignores its own sea`() {
        val shares = (1..12).map { seed ->
            val world = world(100, 60, seed)
            world.fractalLayerWithoutHills(CivVLayerArgs(100, 60, 0, 0, waterPercent = 60, grain = 2))
            val landShare = world.share { it == land }
            // the shift of the original leaves one nil, which is written as a mountain (see CivVLayeredWorld): at most one cell is neither
            assertTrue("seed $seed: only land and ocean", world.count { it != land && it != ocean } <= 1)
            assertTrue("seed $seed: land share $landShare", landShare in 0.25..0.55)
            landShare
        }
        assertEquals("mean land share", 0.40, shares.average(), 0.04)

        val onFilled = world(100, 60, 4)
        onFilled.fill(land)
        onFilled.fractalLayerWithoutHills(CivVLayerArgs(50, 30, 10, 10, waterPercent = 60, grain = 2))
        assertEquals("a land layer never makes sea", 0, onFilled.count { it == ocean })

        val inRect = world(100, 60, 5)
        inRect.fractalLayerWithoutHills(CivVLayerArgs(40, 20, 30, 20, waterPercent = 55, grain = 1))
        for (x in 0 until 100) for (y in 0 until 60)
            if (x !in 30 until 70 || y !in 20 until 40) assertEquals("land outside the rectangle at $x,$y", ocean, inRect.plotAt(x, y))
    }

    @Test
    fun `a fractal layer with hills has hills and mountains on part of its land`() {
        val hills = ArrayList<Double>()
        val mountains = ArrayList<Double>()
        for (seed in 1..8) {
            val world = world(100, 60, seed)
            world.fractalLayer(CivVLayerArgs(100, 60, 0, 0, waterPercent = 60, grain = 2, hillsGrain = 3))
            val landCount = world.count { it != ocean }.toDouble()
            hills.add(world.count { it == CivVLayeredWorld.HILLS } / landCount)
            mountains.add(world.count { it == CivVLayeredWorld.MOUNTAIN } / landCount)
        }
        // Two bands of 2 x 5 percentiles each: a fifth of the land is high, 30 % of it (the "peaks") reads as mountain
        assertEquals("hills share of land", 0.14, hills.average(), 0.05)
        assertEquals("mountain share of land", 0.06, mountains.average(), 0.04)
    }

    @Test
    fun `later layers overwrite what is there with their land`() {
        val world = world(60, 40, 6)
        world.fractalLayerWithoutHills(CivVLayerArgs(60, 40, 0, 0, waterPercent = 90, grain = 2)) // a little land
        val before = world.count { it == land }
        world.fractalLayerWithoutHills(CivVLayerArgs(60, 40, 0, 0, waterPercent = 50, grain = 2))
        assertTrue("land grew from $before to ${world.count { it == land }}", world.count { it == land } > before)
    }

    // endregion

    // region The shift

    /** A region as the Lua code holds it: element y * width + x + 1, element 0 empty, an extra row of room */
    private fun region(width: Int, height: Int, isLand: (Int, Int) -> Boolean): IntArray {
        val region = IntArray(width * height + width + 2) { ocean }
        for (x in 0 until width) for (y in 0 until height) if (isLand(x, y)) region[y * width + x + 1] = land
        return region
    }

    private fun IntArray.isLandAt(width: Int, x: Int, y: Int) = this[y * width + x + 1] != ocean

    @Test
    fun `the shift puts the middle of the emptiest group of columns at the edge of the region`() {
        // Columns 10 to 15 are sea: the first group of 5 columns with no land is the one around column 12
        val region = region(24, 10) { x, _ -> x !in 10..15 }
        assertEquals(12, CivVRegionShift.determineShift(region, 24, 10, columns = true))
        CivVRegionShift.shiftRegionPlots(region, 24, 10)
        for (x in listOf(22, 23, 0, 1, 2, 3)) for (y in 0 until 10) assertFalse("land at $x,$y", region.isLandAt(24, x, y))
        for (x in 4..21) for (y in 0 until 10) assertTrue("no land at $x,$y", region.isLandAt(24, x, y))
    }

    @Test
    fun `the shift of rows works the same way`() {
        val region = region(10, 24) { _, y -> y !in 10..15 }
        assertEquals(12, CivVRegionShift.determineShift(region, 10, 24, columns = false))
        CivVRegionShift.shiftRegionPlots(region, 10, 24)
        for (y in listOf(22, 23, 0, 1, 2, 3)) for (x in 0 until 10) assertFalse("land at $x,$y", region.isLandAt(10, x, y))
    }

    @Test
    fun `a region that is all land keeps its first group and is not shifted`() {
        val region = region(20, 12) { _, _ -> true }
        assertEquals(0, CivVRegionShift.determineShift(region, 20, 12, columns = true))
        assertEquals(0, CivVRegionShift.determineShift(region, 20, 12, columns = false))
        val copy = region.copyOf()
        CivVRegionShift.shiftRegionPlots(region, 20, 12)
        assertTrue(copy.contentEquals(region))
    }

    @Test
    fun `a shift by columns moves the land to the left and one by rows moves it down`() {
        val byColumns = region(8, 6) { x, y -> x == 5 && y == 3 }
        CivVRegionShift.shiftRegionPlotsBy(byColumns, 2, 0, 8, 6)
        assertTrue("moved to 3,3", byColumns.isLandAt(8, 3, 3))
        // the other cell that is not ocean is the nil the shift of the original leaves, where it reads the empty element 0
        assertEquals(1, (0 until 8).sumOf { x -> (0 until 6).count { y -> byColumns[y * 8 + x + 1] == land } })
        assertEquals(CivVRegionShift.NIL, byColumns[0 * 8 + 5 + 1])

        val byRows = region(8, 6) { x, y -> x == 5 && y == 3 }
        CivVRegionShift.shiftRegionPlotsBy(byRows, 0, 2, 8, 6)
        assertTrue("moved to 5,1", byRows.isLandAt(8, 5, 1))
    }

    @Test
    fun `the original's index slip takes the row before where the column of the source wraps`() {
        // The loops count from 0 over an array that starts at 1: with a shift of 2 on a region 8 wide, the column 5 takes the cell of column 7
        // of the row before, and the last column the cell of column 1 of the row after (the code that does the same on a 0-based array would not)
        val region = region(8, 6) { x, y -> x == 7 && y == 2 }
        CivVRegionShift.shiftRegionPlotsBy(region, 2, 0, 8, 6)
        assertTrue("column 5 takes the row before: land at 5,3", region.isLandAt(8, 5, 3))
        assertFalse("not at 5,2", region.isLandAt(8, 5, 2))

        val last = region(8, 6) { x, y -> x == 1 && y == 4 }
        CivVRegionShift.shiftRegionPlotsBy(last, 2, 0, 8, 6)
        assertTrue("the last column takes the row after: land at 7,3", last.isLandAt(8, 7, 3))
    }

    @Test
    fun `no shift when both shifts are zero`() {
        val region = region(8, 6) { x, y -> x == 5 && y == 3 }
        val copy = region.copyOf()
        CivVRegionShift.shiftRegionPlotsBy(region, 0, 0, 8, 6)
        assertTrue(copy.contentEquals(region))
    }

    // endregion

    // region ApplyTectonics

    @Test
    fun `tectonics keeps the sea and the land and gives the land hills and mountains`() {
        val hills = ArrayList<Double>()
        val mountains = ArrayList<Double>()
        for (seed in 1..5) {
            val world = world(100, 60, seed)
            world.fractalLayerWithoutHills(CivVLayerArgs(100, 60, 0, 0, waterPercent = 40, grain = 2))
            val seaBefore = world.count { it == ocean }
            world.applyTectonics()
            assertEquals("seed $seed: the sea is the same", seaBefore, world.count { it == ocean })
            val landCount = world.count { it != ocean }.toDouble()
            hills.add(world.count { it == CivVLayeredWorld.HILLS } / landCount)
            mountains.add(world.count { it == CivVLayeredWorld.MOUNTAIN } / landCount)
        }
        // a 6 % mountain percentile less the passes, foothills of 9 % and two bands of 12 % of the hills field
        assertEquals("hills share of land", 0.19, hills.average(), 0.07)
        assertEquals("mountain share of land", 0.05, mountains.average(), 0.03)
    }

    @Test
    fun `tectonic islands add land to the sea and without them there is none`() {
        val world = world(100, 60, 2)
        world.applyTectonics(tectonicIslands = true)
        assertTrue("land in a world of sea", world.count { it != ocean } > 0)
        val none = world(100, 60, 2)
        none.applyTectonics(tectonicIslands = false)
        assertEquals(0, none.count { it != ocean })
    }

    @Test
    fun `a young world has more mountains than an old one`() {
        fun mountains(age: Int) = (1..4).sumOf { seed ->
            val world = world(100, 60, seed)
            world.fill(land)
            world.applyTectonics(worldAge = age)
            world.count { it == CivVLayeredWorld.MOUNTAIN }
        }
        assertTrue("young ${mountains(3)}, old ${mountains(5)}", mountains(3) > mountains(5))
    }

    // endregion

    // region Four Corners

    private fun fourCorners(width: Int, seed: Int, buffer: FourCornersBuffer = FourCornersBuffer.Ocean) =
        CivVLayeredScripts.generate(MapType.fourCornersLikeCivV, PointyHexGeometry(width, width, false), false, Random(seed), 4, buffer)

    private fun insideDisc(x: Int, y: Int, width: Int, radius: Double): Boolean {
        val centre = (width - 1) / 2.0
        return (x - centre) * (x - centre) + (y - centre) * (y - centre) <= radius * radius
    }

    /** The number of landmasses by flood fill over the steps of the geometry */
    private fun countLandmasses(geometry: HexGeometry, isLand: (Int, Int) -> Boolean): Int {
        val seen = HashSet<Pair<Int, Int>>()
        var count = 0
        for (x in 0 until geometry.width) for (y in 0 until geometry.height) {
            if (!isLand(x, y) || (x to y) in seen) continue
            count++
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(x to y)
            seen.add(x to y)
            while (queue.isNotEmpty()) {
                val (cx, cy) = queue.removeFirst()
                for (n in geometry.neighbors(cx, cy)) if (isLand(n.first, n.second) && seen.add(n)) queue.add(n)
            }
        }
        return count
    }

    @Test
    fun `four corners is land with two strips of sea four wide and a flat land disc of radius 5 and a half in the middle`() {
        for (width in listOf(16, 24, 36, 56)) for (seed in 1..2) {
            val result = fourCorners(width, seed)
            for (x in 0 until width) for (y in 0 until width) {
                val inStrip = x in width / 2 - 2..width / 2 + 1 || y in width / 2 - 2..width / 2 + 1
                val where = "$width x $width seed $seed at $x,$y"
                when {
                    insideDisc(x, y, width, 5.5) -> {
                        assertTrue("disc is land: $where", result.land[x][y])
                        assertFalse("disc has no hills: $where", result.hills[x][y])
                        assertFalse("disc has no mountains: $where", result.mountains[x][y])
                    }
                    inStrip -> assertFalse("strip is sea: $where", result.land[x][y])
                    else -> assertTrue("corner is land: $where", result.land[x][y])
                }
            }
        }
    }

    @Test
    fun `the disc joins the four corners into one landmass`() {
        for (width in listOf(16, 24, 36)) {
            val geometry = PointyHexGeometry(width, width, false)
            val result = fourCorners(width, 3)
            assertEquals("landmasses at $width", 1, countLandmasses(geometry) { x, y -> result.land[x][y] })
        }
    }

    @Test
    fun `without the disc the strips would split the corners`() {
        // The corner quadrants of a 36 grid are 16 wide: the strips alone leave four landmasses
        val geometry = PointyHexGeometry(36, 36, false)
        assertEquals(4, countLandmasses(geometry) { x, y -> x !in 16..19 && y !in 16..19 })
    }

    @Test
    fun `the mountain buffer fills the strips with mountains and widens the disc`() {
        for (width in listOf(24, 36)) {
            val result = fourCorners(width, 4, FourCornersBuffer.Mountains)
            for (x in 0 until width) for (y in 0 until width) {
                val inStrip = x in width / 2 - 2..width / 2 + 1 || y in width / 2 - 2..width / 2 + 1
                assertTrue("land everywhere at $x,$y", result.land[x][y])
                if (insideDisc(x, y, width, 6.5)) assertFalse("flat disc at $x,$y", result.mountains[x][y] || result.hills[x][y])
                else if (inStrip) assertTrue("mountain strip at $x,$y", result.mountains[x][y])
            }
        }
    }

    @Test
    fun `a random buffer is the ocean or the mountains`() {
        val kinds = (1..12).map { seed ->
            val result = fourCorners(24, seed, FourCornersBuffer.Random)
            // the far end of a strip: sea or mountain
            if (result.land[12][0]) "mountains" else "ocean"
        }.toSet()
        assertEquals(setOf("mountains", "ocean"), kinds)
    }

    @Test
    fun `four corners on the tiles of a map is the same cells`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val params = MapParameters().apply { seed = 3; type = MapType.fourCornersLikeCivV; mapSize = MapSize(80, 52) }
        val map = MapGenerator(ruleset).generateMap(params)
        assertEquals(36, map.mapParameters.mapSize.width)
        val geometry = UncivHexGeometry(map)
        assertEquals("columns", 36, geometry.width)
        assertEquals("rows", 36, geometry.height)
        for (tile in map.values) {
            val (x, y) = geometry.cellOf(tile)
            val inStrip = x in 16..19 || y in 16..19
            if (inStrip && !insideDisc(x, y, 36, 5.5)) assertTrue("strip tile at $x,$y is water", tile.isWater)
            // AddLakes turns about one inland tile in 160 into a lake
            if (!inStrip) assertTrue("corner tile at $x,$y is land", tile.isLand || tile.baseTerrain == "Lakes")
        }
        map.assignContinents(TileMap.AssignContinentsMode.Reassign)
        assertEquals(1, map.continentSizes.size)
    }

    // endregion

    // region Inland Sea

    private fun inlandSea(width: Int, height: Int, seed: Int) =
        CivVLayeredScripts.generate(MapType.inlandSeaLikeCivV, PointyHexGeometry(width, height, false), false, Random(seed), 4)

    @Test
    fun `inland sea has land all round, a sea in the middle and an oval of sea at the centre`() {
        for ((width, height) in listOf(24 to 16, 52 to 32, 84 to 52)) for (seed in 1..3) {
            val result = inlandSea(width, height, seed)
            val where = "$width x $height seed $seed"
            // the rectangle of sea starts at 18 % and 28 % of the map: outside it everything is land
            val westX = Math.floor(width * 0.18).toInt() - 1
            val southY = Math.floor(height * 0.28).toInt() - 1
            for (x in 0 until width) for (y in 0 until height)
                if (x < westX || y < southY) assertTrue("land outside the sea's rectangle at $x,$y: $where", result.land[x][y])
            // the oval
            val centreX = width / 2.0 - 1
            val centreY = height / 2.0 - 1
            for (x in 0 until width) for (y in 0 until height) {
                val dx = (x - centreX) / (centreX / 2)
                val dy = (y - centreY) / (centreY * 0.35)
                if (dx * dx + dy * dy <= 1) assertFalse("oval is sea at $x,$y: $where", result.land[x][y])
            }
            val sea = result.land.sumOf { column -> column.count { !it } } / (width * height).toDouble()
            assertTrue("sea share $sea: $where", sea in 0.12..0.45)
        }
    }

    @Test
    fun `the sea of inland sea is mostly one body of water that holds its centre`() {
        for (seed in 1..6) {
            val geometry = PointyHexGeometry(52, 32, false)
            val result = inlandSea(52, 32, seed)
            val seaCells = (0 until 52).sumOf { x -> (0 until 32).count { y -> !result.land[x][y] } }
            val centreBody = countBody(geometry, 25, 15) { x, y -> !result.land[x][y] }
            assertTrue("seed $seed: the centre body is $centreBody of $seaCells", centreBody >= seaCells * 0.7)
        }
    }

    private fun countBody(geometry: HexGeometry, startX: Int, startY: Int, isMember: (Int, Int) -> Boolean): Int {
        val seen = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(startX to startY)
        seen.add(startX to startY)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for (n in geometry.neighbors(x, y)) if (isMember(n.first, n.second) && seen.add(n)) queue.add(n)
        }
        return seen.size
    }

    @Test
    fun `inland sea on the tiles of a map has no tundra, snow or ice and no mountain on the coast`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        var landTiles = 0
        for (seed in 1L..3L) {
            val params = MapParameters().apply { this.seed = seed; type = MapType.inlandSeaLikeCivV; mapSize = MapSize(128, 80) }
            val map = MapGenerator(ruleset).generateMap(params)
            assertEquals(84, map.mapParameters.mapSize.width)
            assertEquals(52, map.mapParameters.mapSize.height)
            assertFalse(map.mapParameters.worldWrap)
            for (tile in map.values) {
                assertTrue("${tile.baseTerrain} at ${tile.position}", tile.baseTerrain != Constants.snow && tile.baseTerrain != Constants.tundra)
                assertFalse("ice at ${tile.position}", Constants.ice in tile.terrainFeatures)
                // (a lake that AddLakes makes later does not count: the original asks for an ocean of 10 tiles or more; nor does a natural
                // wonder that turns a tile of the sea into a mountain, such as Krakatoa)
                if (tile.baseTerrain == Constants.mountain && tile.naturalWonder == null)
                    assertTrue("coastal mountain at ${tile.position}", tile.neighbors.none { it.isWater && it.baseTerrain != "Lakes" })
                if (tile.isLand) landTiles++
            }
        }
        assertTrue(landTiles > 0)
    }

    // endregion

    // region The grid the script sets

    @Test
    fun `the world size of the player's size picks the grid of the script`() {
        val expected = mapOf(
            MapType.fourCornersLikeCivV to listOf(16 to 16, 24 to 24, 30 to 30, 36 to 36, 44 to 44, 56 to 56),
            MapType.inlandSeaLikeCivV to listOf(24 to 16, 32 to 20, 40 to 24, 52 to 32, 64 to 40, 84 to 52)
        )
        // 960, 2016, 2772, 4160, 6656 and 10240 tiles: Duel to Huge
        val sizes = listOf(40 to 24, 56 to 36, 66 to 42, 80 to 52, 104 to 64, 128 to 80)
        for ((type, grids) in expected) for ((index, size) in sizes.withIndex()) {
            val parameters = MapParameters().apply { this.type = type; mapSize = MapSize(size.first, size.second); worldWrap = true }
            val own = CivVScripts.withOwnGrid(parameters)!!
            assertEquals("$type $size", grids[index], own.mapSize.width to own.mapSize.height)
            assertEquals(MapShape.rectangular, own.shape)
            assertFalse("no world wrap", own.worldWrap)
            assertEquals(type, own.type)
        }
    }

    @Test
    fun `the player's parameters are left alone and the other types have no grid of their own`() {
        val parameters = MapParameters().apply { type = MapType.fourCornersLikeCivV; shape = MapShape.hexagonal; worldWrap = true; mapSize = MapSize.Huge }
        CivVScripts.withOwnGrid(parameters)
        assertEquals(MapShape.hexagonal, parameters.shape)
        assertTrue(parameters.worldWrap)
        assertEquals(MapSize.Huge.width, parameters.mapSize.width)
        for (type in listOf(MapType.continentsLikeCivV, MapType.pangaea, MapType.fourCorners, MapType.innerSea))
            assertNull(type, CivVScripts.withOwnGrid(parameters.clone().apply { this.type = type }))
    }

    @Test
    fun `the world size is read back from the width of the grid`() {
        for (size in CivVWorldSize.entries) {
            assertEquals(size, CivVScripts.fourCorners.worldSizeOfGrid(CivVScripts.fourCorners.grids!!.getValue(size).first))
            assertEquals(size, CivVScripts.inlandSea.worldSizeOfGrid(CivVScripts.inlandSea.grids!!.getValue(size).first))
        }
    }

    @Test
    fun `the latitude functions of the two scripts`() {
        assertEquals(0.0, CivVScripts.fourCorners.latitude(0.0), 1e-9)
        assertEquals(0.8, CivVScripts.fourCorners.latitude(1.0), 1e-9)
        assertEquals(0.07, CivVScripts.inlandSea.latitude(0.0), 1e-9)
        assertEquals(0.59, CivVScripts.inlandSea.latitude(1.0), 1e-9)
        assertFalse(CivVScripts.fourCorners.ice)
        assertFalse(CivVScripts.inlandSea.ice)
        assertTrue(CivVScripts.standard.ice)
    }

    @Test
    fun `a map whatever shape was asked for is made on the script's grid`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val params = MapParameters().apply {
            seed = 5; type = MapType.fourCornersLikeCivV; shape = MapShape.hexagonal; worldWrap = true; mapSize = UncivMapSizes.Small
        }
        val map = MapGenerator(ruleset).generateMap(params)
        // Unciv's old Small is 33 x 21 = 693 tiles: the Duel world size, so 16 x 16
        assertEquals(16 * 16, map.values.size)
        assertEquals(MapShape.rectangular, map.mapParameters.shape)
        assertFalse(map.mapParameters.worldWrap)
        assertEquals(16, map.mapParameters.mapSize.width)
        assertEquals(16, map.mapParameters.mapSize.height)
        // the caller's parameters are as they were
        assertEquals(MapShape.hexagonal, params.shape)
        assertTrue(params.worldWrap)
    }

    // endregion
}
