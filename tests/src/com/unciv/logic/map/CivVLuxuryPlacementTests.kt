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

/** AssignStartingPlots.lua luxuries on a Huge Continents map of 12 civs and 24 city states */
@RunWith(GdxTestRunner::class)
class CivVLuxuryPlacementTests {

    private fun hugeMap(seed: Long): Pair<TileMap, com.unciv.models.ruleset.Ruleset> {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(12) + ruleset.nations.values.filter { it.isCityState }.take(24)) {
            val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
        }
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(128, 80); worldWrap = true; shape = MapShape.rectangular
        }
        return MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo) to ruleset
    }

    @Test
    fun `data of the Brave New World luxuries has the weights of the script`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val cocoa = ruleset.tileResources["Cocoa"]!!
        assertEquals(listOf("35", "5", "10", "10"), cocoa.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ResourceWeighting,
            com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals).map { it.params[0] }.toList())
        assertEquals("15", ruleset.tileResources["Whales"]!!.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.LuxuryWeightingForCityStates).first().params[0])
    }

    /** A Standard map (80 x 52) of 8 civs and 16 city states */
    private fun standardMap(seed: Long, onlyKind: String? = null): Pair<TileMap, com.unciv.models.ruleset.Ruleset> {
        RulesetCache.loadRulesets(noMods = true)
        var ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        if (onlyKind != null) {
            // A copy of the ruleset (its objects are shared, its lists are not) in which [onlyKind] is the only luxury that is assigned (marble and ivory are special cases, and stay)
            ruleset = ruleset.clone()
            ruleset.tileResources.values.removeAll { it.resourceType == ResourceType.Luxury && it.name != onlyKind && it.name != "Marble" && it.name != "Ivory" }
        }
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(8) + ruleset.nations.values.filter { it.isCityState }.take(16)) {
            val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
        }
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(80, 52); worldWrap = true; shape = MapShape.rectangular
        }
        return MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo) to ruleset
    }

    private val newLuxuries = listOf("Amber", "Jade", "Lapis Lazuli", "Coral", "Coffee", "Tobacco", "Tea", "Perfume", "Olives")

    @Test
    fun `the new luxuries have the weights of the script of Vox Populi`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        fun weights(name: String): Map<String, String> = ruleset.tileResources[name]!!.getMatchingUniques(
            com.unciv.models.ruleset.unique.UniqueType.ResourceWeighting, com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals)
            .associate { it.modifiers.first().params[0] to it.params[0] }
        fun cityState(name: String) = ruleset.tileResources[name]!!.getMatchingUniques(
            com.unciv.models.ruleset.unique.UniqueType.LuxuryWeightingForCityStates).first().params[0]
        // AssignStartingPlots.lua luxury_region_weights (V:(2) Vox Populi/Core Files/Overrides/AssignStartingPlots.lua:481-790), 1 tundra ... 8 hybrid
        assertEquals(mapOf("Tundra" to "35", "Desert" to "10", "Hill" to "10", "Hybrid" to "30"), weights("Amber"))
        assertEquals(mapOf("Tundra" to "10", "Desert" to "15", "Hill" to "20", "Hybrid" to "30"), weights("Jade"))
        assertEquals(mapOf("Tundra" to "10", "Desert" to "35", "Hill" to "10", "Hybrid" to "30"), weights("Lapis Lazuli"))
        assertEquals(mapOf("Tundra" to "10", "Jungle" to "10", "Forest" to "8", "Desert" to "10", "Hill" to "6", "Plains" to "11", "Grassland" to "14", "Hybrid" to "20"),
            weights("Coral"))
        assertEquals(mapOf("Plains" to "40", "Grassland" to "20", "Hybrid" to "5"), weights("Coffee"))
        assertEquals(mapOf("Plains" to "40", "Grassland" to "20", "Hybrid" to "5"), weights("Olives"))
        for (name in listOf("Tobacco", "Tea", "Perfume")) assertEquals(name, mapOf("Plains" to "15", "Grassland" to "50", "Hybrid" to "5"), weights(name))
        // luxury_city_state_weights
        assertEquals(listOf("10", "10", "10", "10", "5", "5", "5", "5", "5"), newLuxuries.map { cityState(it) })
    }

    private fun onTerrainOfItsKind(tile: com.unciv.logic.map.tile.Tile, ruleset: com.unciv.models.ruleset.Ruleset) =
        ruleset.tileResources[tile.resource!!]!!.terrainsCanBeFoundOn.any { it == tile.baseTerrain || it in tile.terrainFeatures }

    @Test
    fun `the luxuries on a Standard map total about what Vox Populi's script places and stand on terrain of their kind`() {
        // Not that a given kind is present: with the script's counts (8 regional kinds at most, 3 for the city states, 5 random, marble and ivory) about 17 of the 29 kinds are on
        // a map, so a kind is absent from a map more often than not and no fixed number of maps makes its presence certain (see the next test, which forces it)
        for (seed in 71L..78L) {
            val (map, ruleset) = standardMap(seed)
            val luxuries = map.values.filter { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury }
            assertTrue("seed $seed: luxuries ${luxuries.size}", luxuries.size in 50..150)
            assertTrue("seed $seed: no luxury under an old name", luxuries.none { it.resource == "Gold Ore" || it.resource == "Spices" })
            for (tile in luxuries.filter { it.resource in newLuxuries })
                assertTrue("seed $seed: ${tile.resource} on ${tile.baseTerrain} ${tile.terrainFeatures}", onTerrainOfItsKind(tile, ruleset))
        }
    }

    /**
     *  Deterministic where a count of the kinds on random maps is not: with [newLuxuries] and the renamed Gold and Cinnamon each the only luxury kind that can be assigned
     *  (the ruleset has no other, marble and ivory aside), the kind is a regional, city-state or random one on every map, so it is placed (the random kinds get at least 3 tiles)
     *  whatever the seed. Nothing is left to chance but the terrain, and the kinds stand on terrain of their own kind, under their new names.
     */
    @Test
    fun `every new luxury, and the renamed ones, is placed on a Standard map when it is the only kind there is`() {
        for (kind in newLuxuries + listOf("Gold", "Cinnamon")) {
            val (map, ruleset) = standardMap(71L, onlyKind = kind)
            val placed = map.values.filter { it.resource == kind }
            assertTrue("$kind: ${placed.size} tiles", placed.size >= 3)
            for (tile in placed) assertTrue("$kind on ${tile.baseTerrain} ${tile.terrainFeatures}", onTerrainOfItsKind(tile, ruleset))
            val luxuryKinds = map.values.mapNotNull { it.resource }.filter { ruleset.tileResources[it]!!.resourceType == ResourceType.Luxury }.toSet()
            assertEquals("$kind: only it, marble and ivory", setOf(kind), luxuryKinds - setOf("Marble", "Ivory"))
        }
    }

    @Test
    fun `the number of luxury kinds placed anywhere is Vox Populi's by world size, not what the kinds leave`() {
        // GetRandomLuxuriesTargetNumber: 4 on Duel ... 5 on Standard ... 9 on Huge. The kinds of a map are those of its regions (at most one each), 3 of the city states,
        // marble and ivory (special cases) and the random ones; so on a Standard map of 8 civs there are at most 8 + 3 + 2 + 5 = 18 kinds, however many the ruleset has
        for (seed in 71L..76L) {
            val (map, ruleset) = standardMap(seed)
            val kinds = map.values.mapNotNull { it.resource }.filter { ruleset.tileResources[it]!!.resourceType == ResourceType.Luxury }.toSet()
            assertTrue("seed $seed: ${kinds.size} kinds $kinds", kinds.size <= 18)
        }
    }

    @Test
    fun `the bonus resources of Vox Populi are in the ruleset`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        for (name in listOf("Maize", "Rice", "Figs")) assertEquals(name, ResourceType.Bonus, ruleset.tileResources[name]!!.resourceType)
        assertEquals("Agriculture", ruleset.tileResources["Maize"]!!.revealedBy)
        assertEquals("Agriculture", ruleset.tileResources["Rice"]!!.revealedBy)
        assertEquals(null, ruleset.tileResources["Figs"]!!.revealedBy)
    }

    @Test
    fun `most luxury kinds are on the map, marble is topped up to 3 in 4 civs and every start and city state has one near`() {
        for (seed in 61L..63L) {
            val (map, ruleset) = hugeMap(seed)
            val luxuries = map.values.filter { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury }
            val kinds = luxuries.map { it.resource!! }.toSet()
            // 20 kinds are assigned or left out: 1 of them left out on Huge, and a kind may find no tile (12 civs: 8 to regions at most, 3 to city states, the rest random)
            assertTrue("seed $seed: kinds $kinds", kinds.size >= 17)
            assertTrue("seed $seed: marble ${luxuries.count { it.resource == "Marble" }}", luxuries.count { it.resource == "Marble" } >= 9)

            val starts = map.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isMajorCiv }.values.map { it.first() }
            val citySates = map.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isCityState }.values.map { it.first() }
            assertEquals(12, starts.size)
            for (start in starts) assertTrue("seed $seed: no luxury within 3 of a start",
                start.getTilesInDistanceRange(1..3).any { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury })
            val withLuxury = citySates.count { cs -> cs.getTilesInDistanceRange(1..2).any { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury } }
            assertTrue("seed $seed: $withLuxury of ${citySates.size} city states have a luxury", withLuxury >= citySates.size * 5 / 6)
        }
    }

    @Test
    fun `luxuries are not placed on starts and the luxuries total about a hundred and thirty`() {
        val (map, ruleset) = hugeMap(64L)
        val total = map.values.count { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury }
        assertTrue("luxuries $total", total in 100..170)
        val starts = map.startingLocationsByNation.values.flatten()
        assertTrue(starts.none { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury })
    }
}
