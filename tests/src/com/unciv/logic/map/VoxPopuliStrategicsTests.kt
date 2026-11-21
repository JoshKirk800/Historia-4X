package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.VoxPopuliStrategics
import com.unciv.logic.map.mapgenerator.resourceplacement.VoxPopuliStrategics.Strategic
import com.unciv.logic.map.mapgenerator.resourceplacement.VpPlotList
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  The strategic resources as the AssignStartingPlots.lua of Vox Populi places them (PlaceStrategicAndBonusResources, ProcessResourceList,
 *  AddModernMinorStrategicsToCityStates, PlaceSmallQuantitiesOfStrategics, PlaceOilInTheSea): the plot lists, the count per list, the impact layers
 *  of the resources, the stages after the lists and the Duel map as a whole.
 */
@RunWith(GdxTestRunner::class)
class VoxPopuliStrategicsTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun land(height: Int, width: Int, terrain: String, vararg features: String): TileMap {
        game.makeRectangularMap(newHeight = height, newWidth = width, baseTerrain = terrain)
        for (tile in game.tileMap.values) game.setTileTerrainAndFeatures(tile.position, terrain, *features)
        return game.tileMap
    }

    private fun placement(map: TileMap, civs: Int = 0, seed: Int = 1): VoxPopuliStrategics {
        val tileData = TileDataMap(map.values.size)
        for (tile in map.values) tileData[tile] = MapGenTileData(tile, null, game.ruleset)
        return VoxPopuliStrategics(map, List(civs) { Region(map, Rectangle(), -1) }, tileData, game.ruleset, Random(seed))
    }

    private fun count(map: TileMap, resource: String) = map.values.count { it.resource == resource }
    private fun units(map: TileMap, resource: String) = map.values.filter { it.resource == resource }.sumOf { it.resourceAmount }

    private fun lists(terrain: String, vararg features: String): Set<VpPlotList> {
        val map = land(3, 3, terrain, *features)
        return VpPlotList.of(map.values.first()).toSet()
    }

    // region: the data and the flag

    @Test
    fun `Brave New World places its strategics as Vox Populi does, Gods and Kings does not`() {
        assertTrue(game.ruleset.modOptions.constants.strategicPlacementLikeVoxPopuli)
        RulesetCache.loadRulesets(noMods = true)
        assertTrue(!RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.modOptions.constants.strategicPlacementLikeVoxPopuli)
    }

    @Test
    fun `the deposit amounts are those of GetMajorStrategicResourceQuantityValues and GetSmallStrategicResourceQuantityValues`() {
        // sparse, default, abundant = the Lua's small, standard, large
        val major = mapOf("Uranium" to listOf(2, 2, 4), "Horses" to listOf(2, 3, 6), "Oil" to listOf(3, 5, 8),
            "Iron" to listOf(2, 3, 6), "Coal" to listOf(2, 4, 7), "Aluminum" to listOf(3, 7, 10))
        val small = mapOf("Uranium" to listOf(1, 1, 2), "Horses" to listOf(1, 2, 2), "Oil" to listOf(1, 2, 2),
            "Iron" to listOf(1, 2, 2), "Coal" to listOf(1, 2, 2), "Aluminum" to listOf(2, 3, 3))
        for ((name, amounts) in major) game.ruleset.tileResources[name]!!.majorDepositAmount.let {
            assertEquals(name, amounts, listOf(it.sparse, it.default, it.abundant))
        }
        for ((name, amounts) in small) game.ruleset.tileResources[name]!!.minorDepositAmount.let {
            assertEquals(name, amounts, listOf(it.sparse, it.default, it.abundant))
        }
    }

    @Test
    fun `the 35 lists of the Lua are 8 for iron, 4 horses, 4 coal, 5 oil, 3 aluminum and 11 uranium`() {
        val perResource = VoxPopuliStrategics.rules.groupingBy { it.resource }.eachCount()
        assertEquals(35, VoxPopuliStrategics.rules.size)
        assertEquals(mapOf(Strategic.Horses to 4, Strategic.Iron to 8, Strategic.Coal to 4, Strategic.Oil to 5, Strategic.Aluminum to 3, Strategic.Uranium to 11),
            perResource)
        // A few of them, with their tiles per deposit
        fun rule(resource: Strategic, list: VpPlotList) = VoxPopuliStrategics.rules.single { it.resource == resource && it.list == list }
        assertEquals(24, rule(Strategic.Horses, VpPlotList.FlatGrassNoFeature).tilesPerDeposit)
        assertEquals(17, rule(Strategic.Iron, VpPlotList.FlatSnow).tilesPerDeposit)
        assertEquals(350, rule(Strategic.Uranium, VpPlotList.FlatTundraForest).tilesPerDeposit)
        assertEquals(0.7, rule(Strategic.Horses, VpPlotList.FlatTundraNoFeature).quantityFactor, 0.0)
    }

    // endregion

    // region: the plot lists

    @Test
    fun `a hill with no feature is on the hills lists by its terrain, a snow hill is on land and hills only`() {
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Hills, VpPlotList.HillsNoFeature, VpPlotList.HillsPlainsGrassNoFeature,
            VpPlotList.HillsDesertTundraPlainsNoFeature), lists("Plains", "Hill"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Hills, VpPlotList.HillsNoFeature, VpPlotList.HillsPlainsGrassNoFeature), lists("Grassland", "Hill"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Hills, VpPlotList.HillsNoFeature, VpPlotList.HillsDesertTundraPlainsNoFeature), lists("Desert", "Hill"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Hills), lists("Snow", "Hill"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Hills, VpPlotList.HillsForest, VpPlotList.PlainsGrassForest), lists("Plains", "Hill", "Forest"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Hills, VpPlotList.HillsJungle), lists("Plains", "Hill", "Jungle"))
    }

    @Test
    fun `flat land is on the lists of its terrain and feature, forests on plains and grass share one list with the hills`() {
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatPlainsNoFeature, VpPlotList.FlatPlainsGrassNoFeature,
            VpPlotList.FlatTundraPlainsGrassNoFeature, VpPlotList.FlatPlainsDryNoFeature), lists("Plains"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatGrassNoFeature, VpPlotList.FlatPlainsGrassNoFeature,
            VpPlotList.FlatTundraPlainsGrassNoFeature, VpPlotList.FlatGrassDryNoFeature), lists("Grassland"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatTundraNoFeature, VpPlotList.FlatDesertTundraNoFeature,
            VpPlotList.FlatTundraPlainsGrassNoFeature), lists("Tundra"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatDesertNoFeature, VpPlotList.FlatDesertTundraNoFeature), lists("Desert"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatSnow), lists("Snow"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatForest, VpPlotList.PlainsGrassForest, VpPlotList.FlatPlainsGrassForest), lists("Grassland", "Forest"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatForest, VpPlotList.FlatTundraForest), lists("Tundra", "Forest"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatJungle), lists("Plains", "Jungle"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.Marsh), lists("Grassland", "Marsh"))
        assertEquals(setOf(VpPlotList.Land, VpPlotList.FlatDesertWet), lists("Desert", "Flood plains"))
        assertEquals(setOf(VpPlotList.Land), lists("Desert", "Oasis"))
        assertEquals(emptySet<VpPlotList>(), lists("Mountain"))
    }

    // endregion

    // region: the count per list, the floor, the impact layers

    @Test
    fun `a list yields ceil of its size over the tiles per deposit`() {
        val map = land(30, 30, "Plains")
        val n = map.values.count()
        val placement = placement(map)
        placement.prepare()
        placement.placeMajorDeposits()
        assertEquals(Math.ceil(n / 36.0).toInt(), count(map, "Horses")) // flat plains 36
        assertEquals(Math.ceil(n / 80.0).toInt(), count(map, "Iron")) // flat plains and grass 80
        assertEquals(Math.ceil(n / 80.0).toInt() , count(map, "Coal")) // flat plains 80
        assertEquals(Math.ceil(n / 50.0).toInt(), count(map, "Aluminum")) // flat plains 50
        assertEquals(Math.ceil(n / 300.0).toInt(), count(map, "Uranium")) // flat plains and grass 300
        assertEquals(0, count(map, "Oil"))
    }

    @Test
    fun `every non-empty list gives a deposit whatever the size of the map`() {
        val map = land(3, 3, "Plains")
        val placement = placement(map)
        placement.prepare()
        placement.placeMajorDeposits()
        for (resource in listOf("Horses", "Iron", "Coal", "Aluminum", "Uranium")) assertEquals(resource, 1, count(map, resource))
        assertEquals(0, count(map, "Oil"))
    }

    @Test
    fun `deposits of one resource keep apart by their impact radius and those of another resource ignore them`() {
        val map = land(30, 30, "Plains")
        val placement = placement(map)
        placement.prepare()
        placement.placeMajorDeposits()
        val horses = map.values.filter { it.resource == "Horses" }
        val aluminum = map.values.filter { it.resource == "Aluminum" }
        assertTrue(horses.size >= 20)
        // The radius of horses is 1 to 2 and there is room: none next to another (the ripple of a deposit is at least 1)
        for (a in horses) for (b in horses) if (a != b) assertTrue(a.aerialDistanceTo(b) >= 2)
        // Aluminum has its own layer, so it is not kept away from horses
        assertTrue(aluminum.any { a -> horses.any { it.aerialDistanceTo(a) <= 2 } })
    }

    @Test
    fun `a deposit has the amount of its resource varied by a fifth`() {
        val map = land(30, 30, "Plains")
        val placement = placement(map)
        placement.prepare()
        placement.placeMajorDeposits()
        val iron = map.values.filter { it.resource == "Iron" }.map { it.resourceAmount }
        assertTrue(iron.all { it in 2..4 }) // 3 x 0.8 = 2, 3 x 1.2 = 4 when rounded
        assertTrue(map.values.filter { it.resource == "Uranium" }.all { it.resourceAmount == 2 }) // 2 x 0.8 and 2 x 1.2 both round to 2
    }

    @Test
    fun `quantity variance rounds as TryPlacingOneResource does`() {
        assertEquals(4, VoxPopuliStrategics.varied(3.0, 3))
        assertEquals(2, VoxPopuliStrategics.varied(3.0, 0))
        assertEquals(3, VoxPopuliStrategics.varied(3.0, 1))
        assertEquals(3, VoxPopuliStrategics.varied(3.0, 2))
        assertEquals(8, VoxPopuliStrategics.varied(7.0, 3))
        assertEquals(6, VoxPopuliStrategics.varied(7.0, 0))
        assertEquals(2, VoxPopuliStrategics.varied(2.1, 1)) // horses on tundra: 3 x 0.7
        assertEquals(3, VoxPopuliStrategics.varied(2.1, 3))
    }

    @Test
    fun `no deposit is put on a start or a natural wonder`() {
        val map = land(3, 3, "Plains")
        val start = map.values.first()
        map.addStartingLocation(game.ruleset.nations.values.first { it.isMajorCiv }.name, start)
        val placement = placement(map)
        placement.prepare()
        placement.placeMajorDeposits()
        placement.placeSmallDeposits()
        assertNull(start.resource)
    }

    // endregion

    // region: city states, small deposits, sea oil, top-ups

    @Test
    fun `the city state dice give nothing on 0 and 1 and coal, oil or aluminum on 2, 3 and 4`() {
        assertNull(VoxPopuliStrategics.cityStateLists(0))
        assertNull(VoxPopuliStrategics.cityStateLists(1))
        assertEquals(Strategic.Coal, VoxPopuliStrategics.cityStateLists(2)!!.first)
        assertEquals(Strategic.Oil, VoxPopuliStrategics.cityStateLists(3)!!.first)
        assertEquals(Strategic.Aluminum, VoxPopuliStrategics.cityStateLists(4)!!.first)
    }

    @Test
    fun `2 in 5 city states get no strategic, on plains the other 3 in 5 get coal or aluminum`() {
        val cityState = game.ruleset.nations.values.first { it.isCityState }.name
        var withDeposit = 0
        val trials = 300
        for (seed in 1..trials) {
            val map = land(9, 9, "Plains")
            map.addStartingLocation(cityState, map[4, 4])
            val placement = placement(map, seed = seed)
            placement.prepare()
            placement.placeCityStateDeposits()
            val placed = map.values.filter { it.resource != null }
            if (placed.isNotEmpty()) {
                withDeposit++
                assertEquals(1, placed.size)
                assertTrue(placed.single().resource in setOf("Coal", "Aluminum")) // plains have no oil list
                assertTrue(placed.single().resourceAmount == 2 || placed.single().resourceAmount == 3) // small deposits: coal 2, aluminum 3
            }
        }
        // coal (1 in 5) and aluminum (1 in 5) have a list on plains, oil (1 in 5) has none, 2 in 5 roll nothing: 2 in 5 of the trials
        assertTrue("$withDeposit of $trials", withDeposit in 90..150)
    }

    @Test
    fun `small deposits are drawn by the die tables of the terrain`() {
        fun small(terrain: String, vararg features: String, roll: Int): Strategic? {
            val map = land(3, 3, terrain, *features)
            return VoxPopuliStrategics.smallDepositFor(map.values.first()) { roll }
        }
        assertEquals(Strategic.Uranium, small("Plains", "Hill", roll = 0))
        assertEquals(Strategic.Iron, small("Plains", "Hill", roll = 3))
        assertEquals(Strategic.Coal, small("Plains", "Hill", roll = 6))
        assertEquals(Strategic.Aluminum, small("Plains", "Hill", roll = 9))
        assertEquals(Strategic.Coal, small("Grassland", "Hill", roll = 9))
        assertEquals(Strategic.Aluminum, small("Desert", "Hill", roll = 9))
        assertEquals(Strategic.Uranium, small("Grassland", "Jungle", roll = 0))
        assertEquals(Strategic.Oil, small("Grassland", "Marsh", roll = 4))
        assertEquals(Strategic.Uranium, small("Plains", "Hill", "Jungle", roll = 4)) // a hill with jungle is always uranium
        assertEquals(Strategic.Coal, small("Plains", "Forest", roll = 4))
        assertEquals(Strategic.Iron, small("Desert", "Forest", roll = 4))
        assertEquals(Strategic.Horses, small("Plains", roll = 6))
        assertEquals(Strategic.Horses, small("Tundra", roll = 8))
        assertEquals(Strategic.Oil, small("Tundra", roll = 9))
        assertEquals(Strategic.Oil, small("Snow", roll = 9))
        assertEquals(Strategic.Oil, small("Desert", roll = 5))
        assertNull(small("Desert", "Flood plains", roll = 0))
        assertNull(small("Desert", "Oasis", roll = 0))
    }

    @Test
    fun `a snow hill gets no small deposit though it counts as land`() {
        val map = land(8, 8, "Snow", "Hill")
        val placement = placement(map)
        placement.prepare()
        placement.placeSmallDeposits()
        assertTrue(map.values.none { it.resource != null })
    }

    @Test
    fun `small deposits are one per 30 tiles of land where the layers have no impact`() {
        val map = land(30, 30, "Plains") // 900 tiles of land: 30 small deposits; no impact has been placed
        val placement = placement(map)
        placement.prepare()
        placement.placeSmallDeposits()
        val strategic = map.values.filter { it.tileResource?.resourceType == ResourceType.Strategic }
        assertEquals(30, strategic.size)
        // Flat plains: uranium, iron, coal, horses or aluminum, in small amounts
        assertTrue(strategic.all { it.resource in setOf("Uranium", "Iron", "Coal", "Horses", "Aluminum") })
        assertTrue(strategic.all { it.resourceAmount in 1..4 })
    }

    @Test
    fun `the sea gets a fifth of the oil units on land, in deposits of 2 and of 3`() {
        val map = land(40, 40, "Coast")
        val oil = game.ruleset.tileResources["Oil"]!!
        // Islands of one tile, far enough apart for the ripples of the deposits in the sea (up to 7) not to reach the next island
        val landTiles = map.values.filter { it.getColumn() % 12 == 0 && it.getRow() % 12 == 0 }.take(6)
        for (tile in landTiles) {
            game.setTileTerrain(tile.position, "Tundra")
            tile.setTileResource(oil, majorDeposit = true)
            tile.resourceAmount = 5
        }
        val placement = placement(map)
        placement.prepare()
        placement.placeSeaOil()
        val sea = map.values.filter { it.isWater && it.resource == "Oil" }
        // 30 units: 30 / 5 / 2 = 3 deposits of 2 and 30 / 5 / 3 = 2 deposits of 3
        assertEquals(3, sea.count { it.resourceAmount == 2 })
        assertEquals(2, sea.count { it.resourceAmount == 3 })
        assertEquals(5, sea.size)
    }

    @Test
    fun `the top-ups add small deposits until there are 4 units of horses per civ and give up where no list has a tile`() {
        val map = land(10, 10, "Plains")
        val placement = placement(map, civs = 3)
        placement.prepare()
        placement.placeMajorDeposits() // 100 tiles: 3 deposits of horses of about 3 units
        val before = units(map, "Horses")
        placement.topUpDeposits()
        assertTrue("horses ${units(map, "Horses")}", units(map, "Horses") >= 4 * 3)
        assertTrue(units(map, "Horses") >= before)
        // Iron has no list on this map but plains and grass: the loop adds only what fits and ends
        assertTrue(count(map, "Oil") == 0)
    }

    // endregion

    // region: a Duel map

    @Test
    fun `a Duel map of Brave New World has about the strategics of the Vox Populi Duel maps`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        for (seed in 1L..4L) {
            val gameInfo = GameInfo()
            gameInfo.ruleset = ruleset
            for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(2) + ruleset.nations.values.filter { it.isCityState }.take(4)) {
                val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
            }
            val params = MapParameters().apply {
                this.seed = seed; type = MapType.continentsLikeCivV; shape = MapShape.rectangular; mapSize = MapSize(MapSize.auto); balancedStarts = true
            }
            val map = MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo)
            val tiles = map.values.filter { it.tileResource?.resourceType == ResourceType.Strategic }
            val perResource = tiles.groupingBy { it.resource!! }.eachCount()
            // The real maps have 45 to 54: 27 and fewer were the deposits per terrain
            assertTrue("seed $seed: ${tiles.size} $perResource", tiles.size in 36..60)
            for (name in listOf("Iron", "Horses", "Coal", "Oil", "Aluminum", "Uranium"))
                assertTrue("seed $seed: $name in $perResource", (perResource[name] ?: 0) >= 3)
            // Sea oil is in deposits of 2 and 3, the others have the amounts of the resource
            assertTrue(tiles.all { it.resourceAmount > 0 })
            assertTrue(map.values.filter { it.isWater && it.resource == "Oil" }.all { it.resourceAmount in 2..3 })
        }
    }

    // endregion
}
