package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The worker regions ([WorkerRegions], CvHomelandAI::PlanWorkerDistribution, IsWorkerAtAllocatedRegion, GetWorkerRegionTargetPlot) and the
 *  region move of [BuilderAssignment] (CvHomelandAI::ExecuteWorkerMoves).
 */
@RunWith(GdxTestRunner::class)
class WorkerRegionsTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    /** Two islands of radius 2 around (0,0) and (7,0), the rest is ocean (no embarking) with a city on each */
    private fun twoIslands(): Pair<City, City> {
        val a = tile(0, 0)
        val b = tile(7, 0)
        for (plot in game.tileMap.values) if (plot.aerialDistanceTo(a) > 2 && plot.aerialDistanceTo(b) > 2) game.setTileTerrain(plot.position, "Ocean")
        val west = game.addCity(civ, a)
        val east = game.addCity(civ, b)
        return west to east
    }

    private fun worker(plot: Tile): MapUnit = game.addUnit("Worker", civ, plot)

    private fun regions(): WorkerRegions {
        civ.viewableTiles = game.tileMap.values.toSet()
        return WorkerRegions(civ, PlannedRoutes(civ))
    }

    private fun WorkerRegions.of(city: City) = this.regions.single { city in it.cities }

    @Test
    fun `two islands are two regions and each worker belongs to the region it stands in`() {
        val (west, east) = twoIslands()
        val inWest = worker(tile(0, 0))
        val inEast = worker(tile(7, 0))
        val regions = regions()
        assertEquals(2, regions.regions.size)
        assertEquals(setOf(west), regions.of(west).cities)
        assertEquals(listOf(inWest), regions.of(west).workers)
        assertEquals(listOf(inEast), regions.of(east).workers)
        assertEquals(west.getCenterTile(), regions.targetPlot(inWest))
        assertEquals(east.getCenterTile(), regions.targetPlot(inEast))
        assertTrue(regions.isAtAllocatedRegion(inWest))
        assertFalse("the target plot of another region's city", regions.isAtAllocatedRegion(inWest, tile(7, 0)))
    }

    @Test
    fun `cities on one landmass are one region with the need of all its cities`() {
        game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(4, 0))
        civ.cities[0].ai.terrainImprovementNeed = 5
        civ.cities[1].ai.terrainImprovementNeed = 7
        worker(tile(0, 0))
        val regions = regions()
        assertEquals(1, regions.regions.size)
        assertEquals(2, regions.regions.single().cities.size)
        assertEquals("one worker, the whole need of the region taken away once: 12 - max(12 / 1, 1)", 0, regions.regions.single().need)
        assertEquals(1, regions.regions.single().wanted)
    }

    @Test
    fun `the region with the larger need is wanted more workers, the surplus of the other region moves over`() {
        val (west, east) = twoIslands()
        west.ai.terrainImprovementNeed = 30
        east.ai.terrainImprovementNeed = 0
        // all three workers start on the western island
        val workers = listOf(worker(tile(0, 0)), worker(tile(1, 0)), worker(tile(-1, 0)))
        for (plot in listOf(tile(0, 0), tile(1, 0), tile(-1, 0))) assertEquals("setup: owned by the western city", west, plot.getCity())
        val regions = regions()
        assertEquals("one each first, then the second goes to the larger need (30 - 10 > 0 - 10)", 2, regions.of(west).wanted)
        assertEquals(1, regions.of(east).wanted)
        assertEquals(2, regions.of(west).workers.size)
        assertEquals("the east region took the first worker of the west", listOf(workers[0]), regions.of(east).workers)
        assertEquals(east.getCenterTile(), regions.targetPlot(workers[0]))
    }

    @Test
    fun `a worker without a region, outside every city's territory, walks to the first city of the region it was given`() {
        val capital = game.addCity(civ, tile(0, 0))
        val far = game.tileMap.values.first { it.aerialDistanceTo(tile(0, 0)) == 5 }
        assertNull("setup: nobody's land", far.getCity())
        val unit = worker(far)
        val ai = BuilderTaskingAi(civ)
        val assignment = BuilderAssignment(civ, ai).also { civ.viewableTiles = game.tileMap.values.toSet() }
        assignment.assign(listOf(unit))
        assertEquals(capital.getCenterTile(), assignment.regions.targetPlot(unit))
        assertEquals("it is not in its region, so it is sent there", capital.getCenterTile(), assignment.regionTarget(unit))
        assertNull("and it picks no directive", assignment.allPicks.firstOrNull { it.unit == unit })
        assertNull("nor is it a leftover", assignment.leftoverCity(unit))
    }

    @Test
    fun `a worker within two plots of the region's territory counts as at its region`() {
        game.addCity(civ, tile(0, 0))
        val outside = game.tileMap.values.first { it.getCity() == null && it.neighbors.any { next -> next.neighbors.any { n -> n.getCity() != null } } }
        val unit = worker(outside)
        val regions = regions()
        assertTrue(regions.isAtAllocatedRegion(unit))
    }

    @Test
    fun `no regions at war, for a human or a city-state`() {
        game.addCity(civ, tile(0, 0))
        worker(tile(0, 0))
        assertEquals(1, regions().regions.size)

        val enemy = game.addCiv()
        game.addUnit("Warrior", enemy, tile(-8, 0))  // a civilization with nothing is defeated and no war
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        val atWar = regions()
        assertFalse(atWar.considerRegions)
        assertTrue(atWar.regions.isEmpty())
        val far = game.tileMap.values.first { it.aerialDistanceTo(tile(0, 0)) == 5 }
        val unit = worker(far)
        val assignment = BuilderAssignment(civ, BuilderTaskingAi(civ))
        assignment.assign(listOf(unit))
        assertNull("no region move at war", assignment.regionTarget(unit))

        val human = game.addCiv(isPlayer = true)
        game.addCity(human, tile(-5, 0))
        game.addUnit("Worker", human, tile(-5, 0))
        human.viewableTiles = game.tileMap.values.toSet()
        assertTrue(WorkerRegions(human, PlannedRoutes(human)).regions.isEmpty())
    }

    @Test
    fun `no workers, no regions`() {
        game.addCity(civ, tile(0, 0))
        assertTrue(regions().regions.isEmpty())
    }
}
