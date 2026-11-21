package com.unciv.logic.map

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Auto" map size follows Brave New World's world table (CIV5Worlds.xml: DefaultPlayers, DefaultMinorCivs, GridWidth, GridHeight).
 * Against the grids of that file, read from the install: Duel 2/4 on 40x24, Tiny 4/8 on 56x36, Small 6/12 on 66x42, Standard 8/16 on 80x52,
 * Large 10/20 on 104x64, Huge 12/24 on 128x80.
 */
@RunWith(BaseTestRunner::class)
class CivVWorldSizesTests {
    private lateinit var ruleset: Ruleset

    @Before
    fun loadRuleset() {
        RulesetCache.loadRulesets(noMods = true)
        ruleset = RulesetCache.getVanillaRuleset()
    }

    /** The map the generator makes for Auto with this many civilizations and city-states; the empty type, so no land is generated */
    private fun autoMap(majors: Int, minors: Int, shape: String = MapShape.rectangular): TileMap {
        val majorNations = ruleset.nations.values.filter { it.isMajorCiv }
        val minorNations = ruleset.nations.values.filter { it.isCityState }
        Assume.assumeTrue(majorNations.size >= majors && minorNations.size >= minors)
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (nation in majorNations.take(majors) + minorNations.take(minors)) {
            val civ = Civilization(nation)
            civ.gameInfo = gameInfo
            gameInfo.civilizations.add(civ)
        }
        val parameters = MapParameters()
        parameters.type = MapType.empty
        parameters.shape = shape
        parameters.mapSize = MapSize(MapSize.auto)
        parameters.seed = 1
        return MapGenerator(ruleset).generateMap(parameters, gameInfo = gameInfo)
    }

    private fun gridOf(majors: Int, minors: Int) = autoMap(majors, minors).mapParameters.mapSize.let { Pair(it.width, it.height) }

    @Test
    fun `the default numbers of players get the grid of their Civ V world size`() {
        Assert.assertEquals(Pair(40, 24), gridOf(2, 4))
        Assert.assertEquals(Pair(56, 36), gridOf(4, 8))
        Assert.assertEquals(Pair(66, 42), gridOf(6, 12))
        Assert.assertEquals(Pair(80, 52), gridOf(8, 16))
        Assert.assertEquals(Pair(104, 64), gridOf(10, 20))
        Assert.assertEquals(Pair(128, 80), gridOf(12, 24))
    }

    @Test
    fun `the generated rectangular map has the tiles of the grid`() {
        Assert.assertEquals(128 * 80, autoMap(12, 24).tileList.size)
    }

    @Test
    fun `a game between two sizes is interpolated`() {
        // 9 civilizations and 18 city-states are worth 9 default civilizations: halfway from Standard to Large
        Assert.assertEquals(Pair(92, 58), gridOf(9, 18))
    }

    @Test
    fun `a city-state weighs less than a civilization, and fewer of them make a smaller map`() {
        val (withoutWidth, withoutHeight) = gridOf(4, 0)
        val (withWidth, withHeight) = gridOf(4, 8)
        Assert.assertTrue("4 civilizations alone need less than the default game", withoutWidth * withoutHeight < withWidth * withHeight)
        // but not the 160 tiles per player of the whole table: the civilizations keep their share of land
        Assert.assertTrue(withoutWidth * withoutHeight / 4 >= 300)
        Assert.assertEquals(0, withoutWidth % 2)
    }

    @Test
    fun `above Huge and below Duel the tiles of a civilization stay those of the nearest size`() {
        val (hugeWidth, hugeHeight) = gridOf(14, 28)
        val perCivilization = hugeWidth * hugeHeight / 14.0
        Assert.assertEquals(128 * 80 / 12.0, perCivilization, 128 * 80 / 12.0 * 0.03)
        Assert.assertEquals(0, hugeWidth % 2)
        val (duelWidth, duelHeight) = gridOf(2, 0)
        Assert.assertTrue("a world wrap needs 32 columns", duelWidth >= 32)
        Assert.assertTrue(duelHeight >= 20)
    }

    @Test
    fun `a hexagonal Auto map has the area of the rectangular one`() {
        val hexagonal = autoMap(12, 24, MapShape.hexagonal).tileList.size
        Assert.assertEquals(128 * 80.0, hexagonal.toDouble(), 128 * 80 * 0.02)
    }

    @Test
    fun `the rules read the world row of the map's tiles`() {
        val ai = ruleset.ai
        // Vox Populi's Worlds: the unhappiness percent 150, 125, 115, 100, 80, 60 for Duel to Huge
        val tilesAndPercent = listOf(1499 to 150, 1500 to 125, 2499 to 125, 2500 to 115, 3499 to 115, 3500 to 100, 5499 to 100,
            5500 to 80, 7999 to 80, 8000 to 60, 10240 to 60)
        for ((tiles, percent) in tilesAndPercent)
            Assert.assertEquals("$tiles tiles", percent, ai.worldSizeForTiles(tiles).numCitiesUnhappinessPercent)
        Assert.assertEquals("Standard", ai.worldSizeForTiles(4160).name)
        // Vox Populi: 5% of the cost for each city on every size, and 100% research
        for (row in ai.effectiveWorldSizes()) {
            Assert.assertEquals(row.name, 5f, row.numCitiesPolicyCostMod, 0f)
            Assert.assertEquals(row.name, 5f, row.numCitiesTechCostMod, 0f)
            Assert.assertEquals(row.name, 100, row.researchPercent)
        }
    }
}
