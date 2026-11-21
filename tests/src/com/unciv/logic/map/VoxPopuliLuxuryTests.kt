package com.unciv.logic.map

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's AssignStartingPlots.lua against Brave New World's: 2 luxuries at each start instead of 1 (and no extra one for a poor
 *  region), ivory as a special case of at least 2; and AddGoodies' ancient ruins per landmass rounded up. Measured on real Vox Populi
 *  Duel maps: ivory 2 and marble 2 on all four, 15-25 luxuries, 8-9 ruins.
 */
@RunWith(GdxTestRunner::class)
class VoxPopuliLuxuryTests {
    private class Duel(val ivory: Int, val marble: Int, val luxuries: Int, val luxuriesNearStarts: Double)

    private fun generateDuels(viaVoxPopuli: Boolean, seeds: LongRange): List<Duel> {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val constants = ruleset.modOptions.constants
        val before = constants.startLuxuriesLikeVoxPopuli
        constants.startLuxuriesLikeVoxPopuli = viaVoxPopuli
        try {
            return seeds.map { seed ->
                val gameInfo = GameInfo()
                gameInfo.ruleset = ruleset
                for (nation in ruleset.nations.values.filter { it.isMajorCiv }.take(2) + ruleset.nations.values.filter { it.isCityState }.take(4)) {
                    val civ = Civilization(nation); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
                }
                val params = MapParameters().apply {
                    this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(40, 24); worldWrap = true; shape = MapShape.rectangular
                    balancedStarts = false
                }
                val map = MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo)
                val majorStarts = map.startingLocations.filter { ruleset.nations[it.nation]!!.isMajorCiv }.map { map[it.position]!! }
                val luxuryTiles = map.values.filter { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury }
                Duel(
                    map.values.count { it.resource == "Ivory" }, map.values.count { it.resource == "Marble" }, luxuryTiles.size,
                    majorStarts.map { start -> luxuryTiles.count { start.aerialDistanceTo(it) <= 3 } }.average()
                )
            }
        } finally {
            constants.startLuxuriesLikeVoxPopuli = before
        }
    }

    @Test
    fun `Vox Populi places more luxuries near the starts and at least two ivory`() {
        val seeds = 1L..12L
        val vp = generateDuels(true, seeds)
        val bnw = generateDuels(false, seeds)
        assertTrue("ivory with the Vox Populi rule: ${vp.map { it.ivory }}", vp.all { it.ivory >= 2 })
        assertTrue("marble with the Vox Populi rule: ${vp.map { it.marble }}", vp.all { it.marble >= 2 })
        assertTrue("ivory with the BNW rule has no such guarantee: ${bnw.map { it.ivory }}", bnw.any { it.ivory < 2 })
        assertTrue(
            "luxuries within 3 of a start, Vox Populi ${vp.map { it.luxuriesNearStarts }.average()} against ${bnw.map { it.luxuriesNearStarts }.average()}",
            vp.map { it.luxuriesNearStarts }.average() > bnw.map { it.luxuriesNearStarts }.average()
        )
        // the real Vox Populi Duel maps have 15 to 25 luxuries
        assertTrue("luxuries ${vp.map { it.luxuries }}", vp.map { it.luxuries }.average() in 15.0..27.0)
    }

    @Test
    fun `a landmass gets a ruin for every 40 land tiles rounded up`() {
        // the Lua places one while the count is below (tiles + 20) / 40 in floating point
        assertEquals(1, MapGenerator.ruinsWanted(3, 40))
        assertEquals(1, MapGenerator.ruinsWanted(19, 40))
        assertEquals(1, MapGenerator.ruinsWanted(20, 40)) // exactly 1.0
        assertEquals(2, MapGenerator.ruinsWanted(21, 40))
        assertEquals(4, MapGenerator.ruinsWanted(135, 40)) // 3.875: a floor makes 3
        assertEquals(4, MapGenerator.ruinsWanted(140, 40)) // exactly 4.0
        assertEquals(5, MapGenerator.ruinsWanted(141, 40))
        assertEquals(67, MapGenerator.ruinsWanted(2648, 40)) // 66.7
    }
}
