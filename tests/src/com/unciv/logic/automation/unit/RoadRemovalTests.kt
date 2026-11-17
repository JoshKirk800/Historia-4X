package com.unciv.logic.automation.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvBuilderTaskingAI::AddRemoveRouteDirective (a road we pay for where no route is planned is removed, weighted by its maintenance, tenfold
 *  when the treasury loses more than 10 gold a turn) and CvUnit::DoFinishBuildIfSafe (a route with one turn left is finished at the start of
 *  the turn), with BNW's maintenance of 1 gold for a road and 2 for a railroad.
 */
@RunWith(GdxTestRunner::class)
class RoadRemovalTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        // Snow takes no improvement, so that no improvement directive beats the removal of a road on it (FilterOnlyBestImprovements)
        for (position in listOf(HexCoord(2, 1), HexCoord(0, 5)) + (1..8).map { HexCoord(it, 0) }) game.setTileTerrain(position, "Snow")
        civ = game.addCiv()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        civ.tech.techsResearched.addAll(listOf("Agriculture", "The Wheel"))
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]
    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!

    /** A capital at (0,0) owning the plots of the line up to [reach] */
    private fun capital(reach: Int = 3) = game.addCity(civ, tile(0, 0), initialPopulation = 3).also { city ->
        for (x in 1..reach) city.expansion.takeOwnership(tile(x, 0))
    }

    private fun removals(tile: Tile) = BuilderTaskingAi(civ).directives.filter { it.type == BuilderDirective.Type.RemoveRoad && it.tile == tile }

    //region Directive

    @Test
    fun `a road of ours where no route is planned is removed, weighted by its maintenance`() {
        capital()
        tile(1, 0).roadStatus = RoadStatus.Road
        val directive = removals(tile(1, 0)).single()
        assertEquals("Remove Road", directive.build!!.improvement.name)
        assertEquals("1 gold of maintenance in hundredths", PlotBuildScore(100), directive.plotBuildScore)
        assertTrue(directive.canBuild)

        tile(1, 0).roadStatus = RoadStatus.Railroad
        val rail = removals(tile(1, 0)).single()
        assertEquals("Remove Railroad", rail.build!!.improvement.name)
        assertEquals("a railroad costs 2", PlotBuildScore(200), rail.plotBuildScore)
    }

    @Test
    fun `the weight is tenfold when the treasury loses more than 10 gold a turn`() {
        capital()
        tile(1, 0).roadStatus = RoadStatus.Road
        civ.stats.statsForNextTurn.gold = -10f
        assertEquals(100, removals(tile(1, 0)).single().plotBuildScore.score)
        civ.stats.statsForNextTurn.gold = -10.5f
        assertEquals(1000, removals(tile(1, 0)).single().plotBuildScore.score)
    }

    @Test
    fun `a road that is part of a planned route is kept, one beside it is not`() {
        val capital = capital(3)
        game.addCity(civ, tile(4, 0), initialPopulation = 3)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        val aside = tile(2, 1)
        capital.expansion.takeOwnership(aside)
        for (x in 1..3) tile(x, 0).roadStatus = RoadStatus.Road
        aside.roadStatus = RoadStatus.Road

        val ai = BuilderTaskingAi(civ)
        assertNotNull("setup: the plan wants the line", ai.routes.bestRouteTypeAndValue(tile(2, 0)))
        for (x in 1..3) assertTrue(removals(tile(x, 0)).isEmpty())
        assertEquals(1, removals(aside).size)
    }

    @Test
    fun `a pillaged road, a road of somebody else and a road far from our land are left`() {
        val capital = capital(3)
        tile(1, 0).roadStatus = RoadStatus.Road
        tile(1, 0).roadIsPillaged = true
        assertTrue("a pillaged road costs nothing", removals(tile(1, 0)).isEmpty())

        val other = game.addCiv()
        val foreign = tile(0, 5)
        game.addCity(other, tile(0, 6))
        other.cache.updateViewableTiles()
        foreign.setRoadStatus(RoadStatus.Road, other)
        assertTrue(removals(foreign).isEmpty())

        // a road on land nobody owns that we built: ours to pay for when it is next to our land, else it is left
        tile(4, 0).setRoadStatus(RoadStatus.Road, civ)
        tile(8, 0).setRoadStatus(RoadStatus.Road, civ)
        tile(5, 0).setRoadStatus(RoadStatus.Road, other)
        assertEquals("setup", setOf(tile(4, 0).position, tile(8, 0).position), civ.neutralRoads)
        assertTrue(capital.getTiles().none { it == tile(4, 0) })
        assertEquals(1, removals(tile(4, 0)).size)
        assertTrue("not next to our land", removals(tile(8, 0)).isEmpty())
        assertTrue("built by somebody else", removals(tile(5, 0)).isEmpty())
    }

    @Test
    fun `a human who keeps automation from replacing improvements keeps every road`() {
        capital()
        tile(1, 0).roadStatus = RoadStatus.Road
        civ.playerType = PlayerType.Human
        val settings = com.unciv.UncivGame.Current.settings
        val before = settings.automatedWorkersReplaceImprovements
        try {
            settings.automatedWorkersReplaceImprovements = false
            assertTrue(removals(tile(1, 0)).isEmpty())
            settings.automatedWorkersReplaceImprovements = true
            assertEquals(1, removals(tile(1, 0)).size)
        } finally {
            settings.automatedWorkersReplaceImprovements = before
        }
    }

    @Test
    fun `a worker removes the road and the plot is free of it after the work`() {
        // nothing else to improve, so that the road is the best thing to do
        for (plot in game.tileMap.values) game.setTileTerrain(plot.position, "Snow")
        capital()
        val plot = tile(1, 0)
        plot.roadStatus = RoadStatus.Road
        val worker = game.addUnit("Worker", civ, plot)
        val automation = civ.getWorkerAutomation()
        assertTrue(automation.executeWorkerMove(worker))
        assertEquals("Remove Road", plot.improvementInProgress)
        repeat(2) { UnitTurnManager(worker).endTurn() }
        assertEquals(RoadStatus.None, plot.roadStatus)
    }

    //endregion
    //region Finish early

    private fun building(improvement: String, turns: Int, claimed: Boolean = true, x: Int = 1): Pair<Tile, com.unciv.logic.map.mapunit.MapUnit> {
        if (civ.cities.isEmpty()) capital(4)
        val plot = tile(x, 0)
        val worker = game.addUnit("Worker", civ, plot)
        plot.queueImprovement(improvement, turns)
        if (claimed) worker.builderTarget = plot.position
        return plot to worker
    }

    @Test
    fun `a road with one turn left is finished at the start of the turn and the worker keeps its moves`() {
        val (plot, worker) = building("Road", 1)
        WorkerAutomation.finishBuildIfSafe(worker)
        assertEquals(RoadStatus.Road, plot.roadStatus)
        assertNull(plot.improvementInProgress)
        assertTrue(worker.hasMovement())
    }

    @Test
    fun `the turn start of the unit does it`() {
        val (plot, worker) = building("Railroad", 1)
        UnitTurnManager(worker).startTurn()
        assertEquals(RoadStatus.Railroad, plot.roadStatus)
    }

    @Test
    fun `a route with more turns left, an improvement, an unclaimed plot and a worker without moves are left`() {
        val (plot, worker) = building("Road", 2)
        WorkerAutomation.finishBuildIfSafe(worker)
        assertEquals(RoadStatus.None, plot.roadStatus)
        assertEquals("Road", plot.improvementInProgress)

        val (farm, farmer) = building("Farm", 1, x = 2)
        WorkerAutomation.finishBuildIfSafe(farmer)
        assertNull("only routes are finished early", farm.improvement)

        val (unclaimed, stranger) = building("Road", 1, claimed = false, x = 3)
        WorkerAutomation.finishBuildIfSafe(stranger)
        assertEquals(RoadStatus.None, unclaimed.roadStatus)

        val (tired, tiredWorker) = building("Road", 1, x = 4)
        tiredWorker.currentMovement = 0f
        WorkerAutomation.finishBuildIfSafe(tiredWorker)
        assertEquals(RoadStatus.None, tired.roadStatus)
    }

    @Test
    fun `a human's builder is finished early only while automated`() {
        val (plot, worker) = building("Road", 1)
        civ.playerType = PlayerType.Human
        WorkerAutomation.finishBuildIfSafe(worker)
        assertEquals(RoadStatus.None, plot.roadStatus)
        worker.automated = true
        WorkerAutomation.finishBuildIfSafe(worker)
        assertEquals(RoadStatus.Road, plot.roadStatus)
    }

    @Test
    fun `a plot in danger is not finished early`() {
        val (plot, worker) = building("Road", 1)
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addUnit("Warrior", enemy, tile(2, 0))
        assertTrue("setup", civ.ai.plotDanger.get(plot) > 0)
        WorkerAutomation.finishBuildIfSafe(worker)
        assertEquals(RoadStatus.None, plot.roadStatus)
    }

    //endregion
}
