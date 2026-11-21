package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.CivVFractalLandmass
import com.unciv.logic.map.mapgenerator.CivVWorldSize
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
