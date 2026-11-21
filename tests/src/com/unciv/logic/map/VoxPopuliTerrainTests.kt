package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's FeatureGenerator:AddFeatures removes the mountains of the coast (the scripts call it without an argument, which is 'do not allow'
 *  there and 'allow' in Brave New World; a lake is not water of the coast): on its Duel maps 0.9 to 4.5 % of the land is mountain, against 5 % when the coast keeps them.
 */
@RunWith(GdxTestRunner::class)
class VoxPopuliTerrainTests {
    private class Duel(val mountains: Int, val coastalMountains: Int, val land: Int)

    private fun generateDuels(noMountainsOnTheCoast: Boolean, seeds: LongRange): List<Duel> {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val constants = ruleset.modOptions.constants
        val before = constants.noMountainsOnTheCoast
        constants.noMountainsOnTheCoast = noMountainsOnTheCoast
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
                // a natural wonder that is a mountain (Sri Pada) is placed after the terrain and stays
                val mountains = map.values.filter { it.baseTerrain == Constants.mountain && it.naturalWonder == null }
                Duel(mountains.size, mountains.count { tile ->
                    // a natural wonder placed later can turn tiles around it into coast (Great Barrier Reef): that does not undo the rule
                    tile.neighbors.any { it.isWater && it.baseTerrain != "Lakes" } && tile.getTilesInDistance(3).none { it.naturalWonder != null }
                }, map.values.count { it.isLand })
            }
        } finally {
            constants.noMountainsOnTheCoast = before
        }
    }

    @Test
    fun `no mountain is left next to water with the Vox Populi rule`() {
        val duels = generateDuels(true, 1L..12L)
        assertEquals("mountains on the coast: ${duels.map { it.coastalMountains }}", 0, duels.sumOf { it.coastalMountains })
        assertTrue("some mountains inland: ${duels.map { it.mountains }}", duels.sumOf { it.mountains } > 12)
    }

    @Test
    fun `Brave New World's rule keeps coastal mountains and makes more mountains`() {
        val vp = generateDuels(true, 1L..12L)
        val bnw = generateDuels(false, 1L..12L)
        assertTrue("coastal mountains: ${bnw.map { it.coastalMountains }}", bnw.sumOf { it.coastalMountains } > 12)
        val vpShare = vp.sumOf { it.mountains }.toDouble() / vp.sumOf { it.land }
        val bnwShare = bnw.sumOf { it.mountains }.toDouble() / bnw.sumOf { it.land }
        assertTrue("mountain share of land: Vox Populi $vpShare, Brave New World $bnwShare", vpShare < bnwShare * 0.7)
    }
}
