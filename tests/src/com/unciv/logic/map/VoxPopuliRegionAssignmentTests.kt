package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliContinentAssignment
import com.unciv.logic.map.mapgenerator.mapregions.VoxPopuliContinentAssignment.Result
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's AssignStartingPlots:GenerateRegions (continental method): civs settle a landmass in pairs */
@RunWith(GdxTestRunner::class)
class VoxPopuliRegionAssignmentTests {
    private fun deal(numCivs: Int, vararg fertility: Int): Map<Int, Int> {
        val result = VoxPopuliContinentAssignment.assign(fertility.withIndex().associate { it.index + 1 to it.value }, numCivs)
        return (result as Result.Continents).civsPerContinent
    }

    @Test
    fun `both civs of a duel share the best landmass even when another is almost as fertile`() {
        assertEquals(mapOf(1 to 2), deal(2, 100, 90))
        assertEquals(mapOf(2 to 2), deal(2, 90, 100))
    }

    @Test
    fun `an odd count ends on a landmass that already has civs`() {
        // civ 3 is the last civ and cannot open the second landmass
        assertEquals(mapOf(1 to 3), deal(3, 100, 90))
        // 5 civs: 2 + 2 on two landmasses, the last civ joins one of them, the third landmass stays empty
        val five = deal(5, 100, 100, 100)
        assertEquals(5, five.values.sum())
        assertEquals(setOf(1, 2), five.keys)
        assertEquals(mapOf(1 to 3, 2 to 2), five)
    }

    @Test
    fun `four civs fill two landmasses with two each`() {
        assertEquals(mapOf(1 to 2, 2 to 2), deal(4, 100, 90))
    }

    @Test
    fun `six and eight civs on equal landmasses come in pairs`() {
        for (civs in listOf(6, 8)) {
            val dealt = deal(civs, 100, 100, 100, 100)
            assertEquals(civs, dealt.values.sum())
            assertTrue("$civs civs: $dealt", dealt.values.all { it >= 2 })
        }
    }

    @Test
    fun `one landmass takes every civ, with no landmass to compare`() {
        assertSame(Result.BiggestLandmass, VoxPopuliContinentAssignment.assign(mapOf(1 to 100), 2))
        assertSame(Result.BiggestLandmass, VoxPopuliContinentAssignment.assign(mapOf(1 to 100, 2 to 10), 2))
    }

    @Test
    fun `a landmass under a quarter of the biggest is retried down to an eighth and the civs still share`() {
        // 24 is below 25% of 100 but reaches 22.5% on the first retry, and cannot hold a civ of its own
        assertEquals(mapOf(1 to 2), deal(2, 100, 24))
    }

    @Test
    fun `civs cannot be sent to a landmass the other civ left`() {
        val dealt = deal(2, 100, 60)
        assertFalse(dealt.containsKey(2))
        assertEquals(2, dealt[1])
    }

    private fun twoLandmassMap(vanilla: Boolean): MapRegions {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 20, newWidth = 20)
        for (tile in game.tileMap.values)
            game.setTileTerrain(tile.position, if (tile.getColumn() in -3..-2) "Ocean" else "Grassland") // 7 columns and 11 columns of land
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        game.ruleset.modOptions.constants.regionAssignmentLikeVoxPopuli = !vanilla
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, 2)
        return regions
    }

    @Test
    fun `on two landmasses of unequal fertility a duel shares the bigger one`() {
        val regions = twoLandmassMap(vanilla = false).regions
        assertEquals(2, regions.size)
        assertEquals(1, regions.map { it.continentID }.toSet().size)
    }

    @Test
    fun `without the flag the second civ goes to the smaller landmass`() {
        val regions = twoLandmassMap(vanilla = true).regions
        assertEquals(2, regions.size)
        assertEquals(2, regions.map { it.continentID }.toSet().size)
    }
}
