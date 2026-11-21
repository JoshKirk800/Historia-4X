package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.CivVFractalLandmass
import com.unciv.logic.map.mapgenerator.CivVWorldSize
import com.unciv.logic.map.mapgenerator.FractalLandRecipe
import com.unciv.logic.map.mapgenerator.FractalLandRecipes
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.PointyHexGeometry
import com.unciv.logic.map.mapgenerator.SeaLevel
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/** The recipes of the Civ V land step (Continents.lua, Pangaea.lua): their constants, the retry until the biggest landmass has the right share, the pole push, the tectonic islands */
@RunWith(GdxTestRunner::class)
class FractalLandRecipeTests {
    private val duel = PointyHexGeometry(40, 24, true)
    private val standard = PointyHexGeometry(80, 52, true)

    private fun count(cells: Array<BooleanArray>) = cells.sumOf { column -> column.count { it } }

    @Test
    fun `Continents is the recipe of Continents lua`() {
        val r = FractalLandRecipes.continents
        assertEquals(MapType.continentsLikeCivV, r.mapType)
        assertEquals(listOf(67, 72, 76), SeaLevel.entries.map { r.waterPercent(it) })
        assertEquals(0, r.extraMountains)
        assertEquals(1.0, r.platesMultiplier, 0.0)
        assertFalse(r.tectonicIslands)
        assertTrue(r.centreRift)
        assertEquals(0.0..0.58, r.biggestLandmass)
        assertEquals(2, r.grainOnLowRoll)
        assertFalse(r.poleShift)
    }

    @Test
    fun `Pangaea is the recipe of Pangaea lua`() {
        val r = FractalLandRecipes.pangaea
        assertEquals(MapType.pangaeaLikeCivV, r.mapType)
        assertEquals(listOf(63, 68, 73), SeaLevel.entries.map { r.waterPercent(it) })
        assertEquals(6, r.extraMountains)
        assertEquals(1.3, r.platesMultiplier, 0.0)
        assertTrue(r.tectonicIslands)
        assertFalse(r.centreRift)
        assertEquals(0.84..1.0, r.biggestLandmass)
        assertEquals(1, r.grainOnLowRoll)
        assertTrue(r.poleShift)
    }

    @Test
    fun `the map types find their recipes and Pangaea (Civ V) is offered`() {
        assertEquals("Pangaea (Civ V)", MapType.pangaeaLikeCivV)
        assertTrue(MapType.pangaeaLikeCivV in MapType.allValues)
        assertEquals(FractalLandRecipes.pangaea, FractalLandRecipes.forType(MapType.pangaeaLikeCivV))
        assertEquals(FractalLandRecipes.continents, FractalLandRecipes.forType(MapType.continentsLikeCivV))
        assertNull(FractalLandRecipes.forType(MapType.pangaea))
    }

    @Test
    fun `the retry criterion is at most 58 percent for Continents and at least 84 percent for Pangaea`() {
        val continents = FractalLandRecipes.continents
        assertTrue(continents.accepts(57, 100))
        assertFalse(continents.accepts(59, 100))
        assertTrue(continents.accepts(0, 0))
        val pangaea = FractalLandRecipes.pangaea
        assertTrue(pangaea.accepts(85, 100))
        assertFalse(pangaea.accepts(83, 100))
        assertTrue(pangaea.accepts(100, 100))
    }

    @Test
    fun `Pangaea draws fractals until its biggest landmass is 84 percent of the land, Continents until it is at most 58`() {
        var retried = 0
        for (seed in 1..40) {
            val pangaea = CivVFractalLandmass(duel, true, Random(seed), FractalLandRecipes.pangaea)
            pangaea.generate()
            assertTrue("seed $seed: ${pangaea.biggestLandmassShare} after ${pangaea.attempts} fractals", pangaea.biggestLandmassShare >= 0.84)
            if (pangaea.attempts > 1) retried++
            val continents = CivVFractalLandmass(duel, true, Random(seed), FractalLandRecipes.continents)
            continents.generate()
            assertTrue("seed $seed: ${continents.biggestLandmassShare} after ${continents.attempts} fractals", continents.biggestLandmassShare <= 0.58)
        }
        assertTrue("the first fractal was good enough every time: the criterion does nothing", retried in 1..39)
    }

    @Test
    fun `a criterion no fractal meets ends after the attempt limit with the last one`() {
        val never = FractalLandRecipes.pangaea.copy(biggestLandmass = 1.1..2.0)
        val landmass = CivVFractalLandmass(duel, true, Random(3), never)
        val result = landmass.generate()
        assertEquals(CivVFractalLandmass.MAX_ATTEMPTS, landmass.attempts)
        assertTrue(count(result.land) > 0)
    }

    @Test
    fun `the plates of the ridges blended into the land fractal are those of FractalWorld InitFractal`() {
        assertEquals(listOf(4, 8, 16, 20, 24, 32), CivVWorldSize.entries.map { it.landPlates })
    }

    @Test
    fun `ridges blended into the land fractal roughen the coasts into small islands`() {
        // Without the blend a Continents map of this size has 2 landmasses of at most 3 tiles on average, with it 7 or 8
        val small = (1..20).map { seed ->
            val land = CivVFractalLandmass(standard, true, Random(seed), FractalLandRecipes.continents).generate().land
            landmassSizes(land).count { it <= 3 }
        }
        assertTrue("landmasses of at most 3 tiles: ${small.average()} on average", small.average() > 4.5)
    }

    private fun landmassSizes(land: Array<BooleanArray>): List<Int> {
        val seen = Array(standard.width) { BooleanArray(standard.height) }
        val sizes = ArrayList<Int>()
        for (x in 0 until standard.width) for (y in 0 until standard.height) {
            if (!land[x][y] || seen[x][y]) continue
            var size = 0
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(x to y)
            seen[x][y] = true
            while (queue.isNotEmpty()) {
                val (cx, cy) = queue.removeFirst()
                size++
                for ((nx, ny) in standard.neighbors(cx, cy)) if (land[nx][ny] && !seen[nx][ny]) { seen[nx][ny] = true; queue.add(nx to ny) }
            }
            sizes.add(size)
        }
        return sizes
    }

    @Test
    fun `the sea level option moves the water share`() {
        fun land(level: SeaLevel) = (1..6).sumOf { seed ->
            count(CivVFractalLandmass(standard, true, Random(seed), FractalLandRecipes.pangaea, level).generate().land)
        }
        val low = land(SeaLevel.Low)
        val normal = land(SeaLevel.Normal)
        val high = land(SeaLevel.High)
        assertTrue("land $low (low sea), $normal (normal), $high (high sea)", low > normal && normal > high)
    }

    @Test
    fun `tectonic islands add land in the sea along the ridges`() {
        val recipe = FractalLandRecipes.pangaea
        var with = 0
        var without = 0
        for (seed in 1..20) {
            val a = CivVFractalLandmass(standard, true, Random(seed), recipe).generate()
            val b = CivVFractalLandmass(standard, true, Random(seed), recipe.copy(tectonicIslands = false)).generate()
            with += count(a.land); without += count(b.land)
        }
        assertTrue("land $with with islands, $without without", with > without + 100)
    }

    @Test
    fun `extra mountains turn more of the ridges into mountains`() {
        fun mountains(extra: Int) = (1..10).sumOf { seed ->
            count(CivVFractalLandmass(standard, true, Random(seed), FractalLandRecipes.pangaea.copy(extraMountains = extra)).generate().mountains)
        }
        val six = mountains(6)
        val none = mountains(0)
        assertTrue("mountains $six with 6 extra, $none with none", six > none * 1.3)
    }

    @Test
    fun `the biggest landmass is pushed to a pole by an even number of rows, the land otherwise as it was`() {
        val recipe = FractalLandRecipes.pangaea
        val shifts = ArrayList<Int>()
        for (seed in 1..40) {
            val pushed = CivVFractalLandmass(standard, true, Random(seed), recipe)
            val after = pushed.generate()
            val unpushed = CivVFractalLandmass(standard, true, Random(seed), recipe.copy(poleShift = false))
            val plain = unpushed.generate()
            assertEquals("never pushed without the flag", 0, unpushed.poleShiftRows)
            val rows = pushed.poleShiftRows
            shifts.add(rows)
            if (rows != 0) {
                assertTrue("seed $seed: $rows rows", rows % 2 == 0 && abs(rows) >= 2)
                assertTrue("seed $seed: $rows rows are more than there are", abs(rows) < standard.height)
            }
            for (x in 0 until standard.width) for (y in 0 until standard.height) {
                val source = y - rows
                val expected = if (source in 0 until standard.height) plain.land[x][source] else false
                assertEquals("seed $seed: ($x,$y) pushed by $rows", expected, after.land[x][y])
            }
        }
        assertTrue("pushed north in ${shifts.count { it > 0 }}, south in ${shifts.count { it < 0 }} of 40", shifts.any { it > 0 } && shifts.any { it < 0 })
    }

    @Test
    fun `the push brings the land nearer to the pole it goes to`() {
        val recipe = FractalLandRecipes.pangaea
        var checked = 0
        for (seed in 1..40) {
            val pushed = CivVFractalLandmass(standard, true, Random(seed), recipe)
            val after = pushed.generate()
            val plain = CivVFractalLandmass(standard, true, Random(seed), recipe.copy(poleShift = false)).generate()
            if (pushed.poleShiftRows == 0) continue
            checked++
            fun meanRow(cells: Array<BooleanArray>) =
                (0 until standard.width).sumOf { x -> (0 until standard.height).filter { cells[x][it] }.sum() } / count(cells).toDouble()
            val moved = meanRow(after.land) - meanRow(plain.land)
            if (pushed.poleShiftRows > 0) assertTrue("seed $seed: north, land moved $moved rows", moved > 0.5 * pushed.poleShiftRows)
            else assertTrue("seed $seed: south, land moved $moved rows", moved < 0.5 * pushed.poleShiftRows)
        }
        assertTrue("$checked pushed maps", checked >= 10)
    }

    @Test
    fun `Small Continents, Archipelago, Tiny Islands and Fractal are the recipes of their scripts`() {
        val small = FractalLandRecipes.smallContinents
        assertEquals(MapType.smallContinentsLikeCivV, small.mapType)
        assertEquals(listOf(69, 75, 80), SeaLevel.entries.map { small.waterPercent(it) })
        assertEquals(10, small.extraMountains)
        assertEquals(1.5, small.platesMultiplier, 0.0)
        assertEquals(3, small.fixedGrain)
        assertNull(small.coastDice)

        val archipelago = FractalLandRecipes.archipelago
        assertEquals(MapType.archipelagoLikeCivV, archipelago.mapType)
        assertEquals(listOf(72, 78, 83), SeaLevel.entries.map { archipelago.waterPercent(it) })
        assertEquals(10, archipelago.extraMountains)
        assertEquals(2.0, archipelago.platesMultiplier, 0.0)
        assertEquals(4, archipelago.fixedGrain)
        assertEquals(listOf(10, 4, 4), archipelago.coastDice)

        val tiny = FractalLandRecipes.tinyIslands
        assertEquals(MapType.tinyIslandsLikeCivV, tiny.mapType)
        assertEquals(listOf(73, 78, 82), SeaLevel.entries.map { tiny.waterPercent(it) })
        assertEquals(15, tiny.extraMountains)
        assertEquals(2.5, tiny.platesMultiplier, 0.0)
        assertEquals(5, tiny.fixedGrain)
        assertEquals(listOf(10, 4, 4), tiny.coastDice)

        for (island in listOf(small, archipelago, tiny)) {
            assertTrue(island.script, island.tectonicIslands)
            assertFalse(island.script, island.centreRift)
            assertFalse(island.script, island.poleShift)
            assertTrue(island.script, island.offersWorldOptions)
            assertTrue(island.script, island.accepts(1, 100) && island.accepts(100, 100))
        }

        val fractal = FractalLandRecipes.fractal
        assertEquals(MapType.fractalLikeCivV, fractal.mapType)
        assertEquals(listOf(65, 72, 78), SeaLevel.entries.map { fractal.waterPercent(it) })
        assertEquals(0, fractal.extraMountains)
        assertEquals(1.0, fractal.platesMultiplier, 0.0)
        assertEquals(2, fractal.fixedGrain)
        assertFalse(fractal.tectonicIslands)
        assertFalse(fractal.centreRift)
        assertFalse(fractal.poleShift)
        assertFalse(fractal.offersWorldOptions)
        assertNull(fractal.coastDice)
    }

    @Test
    fun `the new map types find their recipes and make their own elevation, and the island types that cannot pass the fairness check are not offered`() {
        val types = listOf("Small Continents (Civ V)", "Archipelago (Civ V)", "Tiny Islands (Civ V)", "Fractal (Civ V)")
        assertEquals(types, listOf(MapType.smallContinentsLikeCivV, MapType.archipelagoLikeCivV, MapType.tinyIslandsLikeCivV, MapType.fractalLikeCivV))
        assertTrue(MapType.smallContinentsLikeCivV in MapType.allValues && MapType.fractalLikeCivV in MapType.allValues)
        assertTrue(MapType.archipelagoLikeCivV !in MapType.allValues && MapType.tinyIslandsLikeCivV !in MapType.allValues)
        for (type in types) {
            assertTrue(type, type in MapType.ownElevation)
            assertEquals(type, FractalLandRecipes.forType(type)?.mapType)
        }
        assertNull(FractalLandRecipes.forType(MapType.archipelago))
        assertNull(FractalLandRecipes.forType(MapType.smallContinents))
        assertNull(FractalLandRecipes.forType(MapType.fractal))
    }

    @Test
    fun `the land share follows the script's water percent and the recipes without a criterion draw one fractal`() {
        for ((recipe, expected) in listOf(
            FractalLandRecipes.smallContinents to 0.25, FractalLandRecipes.archipelago to 0.22,
            FractalLandRecipes.tinyIslands to 0.22, FractalLandRecipes.fractal to 0.28
        )) {
            val shares = (1..8).map { seed ->
                val landmass = CivVFractalLandmass(standard, true, Random(seed), recipe)
                val land = count(landmass.generate().land)
                assertEquals("${recipe.script} seed $seed", 1, landmass.attempts)
                land / (standard.width * standard.height).toDouble()
            }
            // the interpolated cells have fewer extremes than the nodes the percent is taken from, so the share is somewhat under the script's
            assertTrue("${recipe.script}: land ${shares.average()} for $expected", shares.average() in expected - 0.07..expected + 0.01)
        }
    }

    @Test
    fun `more grain makes more and smaller landmasses`() {
        fun landmasses(grain: Int) = (1..10).map { seed ->
            landmassSizes(CivVFractalLandmass(standard, true, Random(seed), FractalLandRecipes.smallContinents.copy(fixedGrain = grain)).generate().land).size
        }.average()
        val coarse = landmasses(2)
        val fine = landmasses(5)
        assertTrue("landmasses $coarse at grain 2, $fine at grain 5", fine > coarse * 1.5)
    }

    @Test
    fun `Fractal has no sea level or world age option and Small Continents has`() {
        fun cells(recipe: FractalLandRecipe, level: SeaLevel, age: Int) = CivVFractalLandmass(standard, true, Random(5), recipe, level, age).generate().let {
            listOf(it.land, it.hills, it.mountains).map { array -> array.map { column -> column.toList() } }
        }
        assertEquals(cells(FractalLandRecipes.fractal, SeaLevel.Normal, 4), cells(FractalLandRecipes.fractal, SeaLevel.High, 3))
        assertEquals(cells(FractalLandRecipes.fractal, SeaLevel.Normal, 4), cells(FractalLandRecipes.fractal, SeaLevel.Low, 5))
        assertTrue(cells(FractalLandRecipes.smallContinents, SeaLevel.Normal, 4) != cells(FractalLandRecipes.smallContinents, SeaLevel.High, 4))
        assertTrue(cells(FractalLandRecipes.smallContinents, SeaLevel.Normal, 4) != cells(FractalLandRecipes.smallContinents, SeaLevel.Normal, 3))
    }

    @Test
    fun `the hills and mountains fields are read from the 128 by 64 nodes, so a Duel map has few tectonic island cells`() {
        // The islands are the ocean cells at exactly the 100th, 99th, 97th and 95th percentile of the mountain field. Made per map cell a Duel map
        // had 6.4 of them on average over seeds 1 to 100 and a Standard map 22.6, the nodes with interpolation give 4.8 and 14.6
        val recipe = FractalLandRecipes.pangaea
        val cells = (1..100).map { seed ->
            val with = count(CivVFractalLandmass(duel, true, Random(seed), recipe).generate().land)
            val without = count(CivVFractalLandmass(duel, true, Random(seed), recipe.copy(tectonicIslands = false)).generate().land)
            with - without
        }
        assertTrue("island cells on a Duel map: ${cells.average()} on average", cells.average() in 3.0..5.7)
        // On a bigger map the nodes are the same, the cells are more: the islands follow the number of cells
        val standardCells = (1..100).map { seed ->
            count(CivVFractalLandmass(standard, true, Random(seed), recipe).generate().land) -
                count(CivVFractalLandmass(standard, true, Random(seed), recipe.copy(tectonicIslands = false)).generate().land)
        }
        assertTrue("island cells on a Standard map: ${standardCells.average()}, on Duel ${cells.average()}", standardCells.average() in 10.0..18.0)
    }

    @Test
    fun `the generator counts the island cells the mountain field made`() {
        val landmass = CivVFractalLandmass(duel, true, Random(4), FractalLandRecipes.pangaea)
        val result = landmass.generate()
        val plain = CivVFractalLandmass(duel, true, Random(4), FractalLandRecipes.pangaea.copy(tectonicIslands = false))
        val without = plain.generate()
        assertEquals(count(result.land) - count(without.land), landmass.tectonicIslandCells)
        assertEquals(0, plain.tectonicIslandCells)
    }

    @Test
    fun `generated maps of the new types have the land of their script and the island scripts widen the coast with their own dice`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        fun generate(type: String, seed: Long) = MapGenerator(ruleset).generateMap(MapParameters().apply {
            this.seed = seed; this.type = type; shape = MapShape.rectangular; mapSize = MapSize(100, 64); worldWrap = true
        })
        // A coast tile with no land within 3 tiles took three widening passes: only the dice {10, 4, 4} have a third
        fun farCoast(map: TileMap) = map.values.count { tile ->
            tile.baseTerrain == "Coast" && tile.getTilesInDistance(3).none { it.isLand }
        }
        for ((type, water) in listOf(
            MapType.smallContinentsLikeCivV to 75, MapType.archipelagoLikeCivV to 78, MapType.tinyIslandsLikeCivV to 78, MapType.fractalLikeCivV to 72
        )) {
            var far = 0
            for (seed in 1L..6L) {
                val map = generate(type, seed)
                val share = map.values.count { it.isLand } / map.values.size.toDouble()
                assertTrue("$type seed $seed: land share $share against $water % water", share in (100 - water) / 100.0 - 0.08..(100 - water) / 100.0 + 0.02)
                assertTrue("$type seed $seed: hills", map.values.any { it.isLand && it.terrainFeatures.contains("Hill") })
                far += farCoast(map)
            }
            // (6 maps of each: the dice {10, 4, 4} gave Archipelago 64 and Tiny Islands 35 such tiles, the ruleset's {4, 4} none on Pangaea)
            if (type == MapType.archipelagoLikeCivV || type == MapType.tinyIslandsLikeCivV) assertTrue("$type: $far coast tiles three tiles from land", far > 8)
            else assertTrue("$type: $far coast tiles three tiles from land", far <= 2)
        }
    }

    @Test
    fun `a generated Pangaea (Civ V) map has about a third land, mostly one landmass, hills and mountains`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        for (seed in 1L..3L) {
            val params = MapParameters().apply {
                this.seed = seed; type = MapType.pangaeaLikeCivV; shape = MapShape.rectangular
                mapSize = MapSize(100, 64); worldWrap = true
            }
            val map = MapGenerator(ruleset).generateMap(params)
            val land = map.values.filter { it.isLand }
            val share = land.size / map.values.size.toDouble()
            assertTrue("seed $seed: land share $share", share in 0.25..0.38)
            map.assignContinents(TileMap.AssignContinentsMode.Reassign)
            val biggest = map.continentSizes.values.max()
            assertTrue("seed $seed: biggest landmass $biggest of ${land.size}", biggest >= land.size * 0.7)
            assertTrue("seed $seed: mountains", land.count { it.baseTerrain == "Mountain" } > land.size / 40)
            assertTrue("seed $seed: hills", land.count { it.terrainFeatures.contains("Hill") } > land.size / 8)
        }
    }
}
