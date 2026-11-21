package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.StartFairness
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UncivMapSizes
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [StartFairness]: a map where a civilization has no land, no room for cities or a far poorer start than the others is regenerated
 * with a derived seed, up to [StartFairness.MAX_ATTEMPTS] times. The checks are on hand-made maps of known land; the generator test
 * counts failing maps with and without [MapParameters.balancedStarts].
 */
@RunWith(BaseTestRunner::class)
class StartFairnessTests {
    private val game = TestGame()
    private val majors = game.ruleset.nations.values.filter { it.isMajorCiv }.map { it.name }
    private val cityStates = game.ruleset.nations.values.filter { it.isCityState }.map { it.name }

    @Before
    fun oceanMap() {
        game.makeHexagonalMap(22)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.ocean)
    }

    /** An island: every tile within [radius] of [center] */
    private fun island(center: HexCoord, radius: Int, terrain: String = Constants.grassland) {
        for (tile in game.tileMap.getTilesInDistance(center, radius).toList())
            game.setTileTerrain(tile.position, terrain)
    }

    private fun start(nation: String, at: HexCoord) {
        game.tileMap.addStartingLocation(nation, game.getTile(at))
    }

    private fun verdict(): StartFairness.Verdict {
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Assign)
        return StartFairness(game.tileMap, game.ruleset).evaluate()
    }

    @Test
    fun `civilizations on landmasses with room for cities and equal land pass`() {
        island(HexCoord.of(-10, 0), 7)
        island(HexCoord.of(10, 0), 7)
        start(majors[0], HexCoord.of(-10, 0))
        start(majors[1], HexCoord.of(10, 0))
        val result = verdict()
        Assert.assertEquals(emptyList<String>(), result.failures)
        Assert.assertEquals(0.0, result.deficit, 0.0)
    }

    @Test
    fun `a civilization sharing a small island with a city-state has too little land per player`() {
        island(HexCoord.of(-10, 0), 7)
        island(HexCoord.of(10, 10), 3) // 37 tiles for a civilization and a city-state
        start(majors[0], HexCoord.of(-10, 0))
        start(majors[1], HexCoord.of(10, 10))
        start(cityStates[0], HexCoord.of(11, 10))
        val failures = verdict().failures
        Assert.assertEquals(listOf(majors[1]), failures.filter { "land tiles per player" in it }.map { it.substringBefore(":") })
        Assert.assertTrue(failures.none { it.startsWith(majors[0]) })
    }

    @Test
    fun `a start with land but no legal site for another city fails on sites`() {
        island(HexCoord.of(-10, 0), 7)
        island(HexCoord.of(10, 0), 3) // 37 tiles for one civilization, all within the minimal city distance of its start
        start(majors[0], HexCoord.of(-10, 0))
        start(majors[1], HexCoord.of(10, 0))
        val failures = verdict().failures
        Assert.assertEquals(listOf("${majors[1]}: 0 city sites within 10 tiles, under 3"), failures)
    }

    @Test
    fun `a start far poorer than the others fails on fertility, a richer neighbour does not`() {
        island(HexCoord.of(-12, 0), 6)
        island(HexCoord.of(12, 0), 6)
        island(HexCoord.of(0, -12), 6, Constants.desert)
        start(majors[0], HexCoord.of(-12, 0))
        start(majors[1], HexCoord.of(12, 0))
        start(majors[2], HexCoord.of(0, -12))
        val failures = verdict().failures
        Assert.assertEquals(listOf(majors[2]), failures.filter { "fertility" in it }.map { it.substringBefore(":") })
        Assert.assertEquals(1, failures.size)
    }

    @Test
    fun `fewer than two civilizations are never judged`() {
        island(HexCoord.of(0, 0), 1)
        start(majors[0], HexCoord.of(0, 0))
        Assert.assertTrue(verdict().passes)
    }

    @Test
    fun `balanced starts leave fewer maps with an unbalanced start`() {
        val ruleset = game.ruleset
        fun generate(seed: Long, balanced: Boolean): Pair<StartFairness.Verdict, Int> {
            val gameInfo = GameInfo()
            gameInfo.ruleset = ruleset
            for (nation in ruleset.nations.values.filter { it.isMajorCiv }.take(4) + ruleset.nations.values.filter { it.isCityState }.take(4)) {
                val civ = Civilization(nation)
                civ.gameInfo = gameInfo
                gameInfo.civilizations.add(civ)
            }
            val parameters = MapParameters().apply {
                type = MapType.continentAndIslands
                mapSize = UncivMapSizes.Tiny
                this.seed = seed
                balancedStarts = balanced
            }
            val generator = MapGenerator(ruleset)
            val map = generator.generateMap(parameters, gameInfo = gameInfo)
            map.assignContinents(TileMap.AssignContinentsMode.Reassign)
            return Pair(StartFairness(map, ruleset).evaluate(), generator.startFairnessAttempts)
        }
        val seeds = (1L..14L)
        val unbalanced = seeds.count { !generate(it, false).first.passes }
        val balancedRuns = seeds.map { generate(it, true) }
        val balanced = balancedRuns.count { !it.first.passes }
        Assert.assertTrue("the unbalanced Tiny Continent and Islands maps are not all fair: $unbalanced of ${seeds.count()} fail", unbalanced > 0)
        Assert.assertTrue("balanced $balanced against unbalanced $unbalanced failing maps", balanced < unbalanced)
        // a balanced map is either fair or the best of all the attempts
        for ((verdict, attempts) in balancedRuns)
            Assert.assertTrue(verdict.passes || attempts == StartFairness.MAX_ATTEMPTS)
    }
}
