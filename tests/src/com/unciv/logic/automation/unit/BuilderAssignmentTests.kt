package com.unciv.logic.automation.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The pick of one builder of a pass over [BuilderTaskingAi.directives] by itself (a fresh assignment, nothing executed) */
internal fun BuilderTaskingAi.pickFor(unit: MapUnit): BuilderAssignment.Pick? = BuilderAssignment(civ, this).assign(listOf(unit)).firstOrNull()

/**
 *  The assignment of the builders ([BuilderAssignment]), against CvHomelandAI::ExecuteWorkerMoves, GetWeightedDirectives and
 *  CvBuilderTaskingAI::ExecuteWorkerMove: who takes what, the re-scoring of what is left for what is planned, the builders left over, and how
 *  [WorkerAutomation] plans once for all builders and carries out each one's pick.
 */
@RunWith(GdxTestRunner::class)
class BuilderAssignmentTests {
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
    private fun research(vararg techs: String) { for (tech in techs) civ.tech.techsResearched.add(tech) }
    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!

    /** Nothing but the capital at (0,0) and the plots [keep] is grassland: the rest is snow, where nothing can be built */
    private fun onlyPlots(vararg keep: Pair<Int, Int>) {
        val kept = keep.map { HexCoord(it.first, it.second) }.toSet() + HexCoord(0, 0)
        for (tile in game.tileMap.values) if (tile.position !in kept) game.setTileTerrain(tile.position, "Snow")
    }

    private fun directive(improvement: String, tile: Tile, score: PlotBuildScore, canBuild: Boolean = true) =
        BuilderDirective(BuilderDirective.Type.BuildImprovement, Build(improvement(improvement)), tile, false, canBuild, score)

    //region Picks

    @Test
    fun `two builders and two plots take one plot each, the one they stand on`() {
        research("Agriculture")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        val east = game.addUnit("Worker", civ, tile(1, 0))
        val west = game.addUnit("Worker", civ, tile(-1, 0))
        val ai = BuilderTaskingAi(civ)
        val picks = BuilderAssignment(civ, ai).assign(listOf(east, west))
        assertEquals(2, picks.size)
        assertEquals(tile(1, 0), picks.single { it.unit == east }.tile)
        assertEquals(tile(-1, 0), picks.single { it.unit == west }.tile)
        assertEquals(0, picks.single { it.unit == east }.moveTurns)
    }

    @Test
    fun `one good plot goes to the nearer builder, the other has no directive and is left over`() {
        research("Agriculture")
        onlyPlots(1 to 0)
        val capital = game.addCity(civ, tile(0, 0))
        val near = game.addUnit("Worker", civ, tile(0, 0))
        val far = game.addUnit("Worker", civ, tile(3, 0))  // two plots outside the city's land: still in its worker region
        val assignment = BuilderAssignment(civ, BuilderTaskingAi(civ))
        val picks = assignment.assign(listOf(far, near))
        assertEquals("the plot is taken once", 1, picks.size)
        assertEquals(near, picks.single().unit)
        assertEquals(tile(1, 0), picks.single().tile)
        assertNull(assignment.allPicks.firstOrNull { it.unit == far })
        assertEquals("the one without a directive goes to the city", capital, assignment.leftoverCity(far))
    }

    @Test
    fun `a farther plot with a much better score beats a near poor one by its weight, the walk counting as a turn`() {
        research("Agriculture")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        val worker = game.addUnit("Worker", civ, tile(1, 0))
        val ai = BuilderTaskingAi(civ)
        val near = directive("Farm", tile(1, 0), PlotBuildScore(100))
        val far = directive("Farm", tile(-1, 0), PlotBuildScore(100000))
        val pick = BuilderAssignment(civ, ai, listOf(near, far)).assign(listOf(worker)).single()
        assertEquals(tile(-1, 0), pick.tile)
        assertEquals("two plots away: two moves, none left", 1, pick.moveTurns)
        assertEquals(BuilderDirectives.directiveWeight(far, pick.buildTurns, pick.moveTurns), pick.weight)
    }

    @Test
    fun `the builder's reach is seven turns counted from 0, a plot in it is as many turns away as its path`() {
        research("Agriculture")
        onlyPlots(1 to 0)
        // Hills cost two moves: a worker walks a plot a turn
        for (plot in game.tileMap.values) if (plot.position != HexCoord(0, 0) && plot.position != HexCoord(1, 0)) game.setTileTerrain(plot.position, "Hill")
        game.addCity(civ, tile(0, 0))
        // At war no worker regions are planned (a builder this far from every city would first walk back to its region)
        val enemy = game.addCiv()
        game.addUnit("Warrior", enemy, tile(-9, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        civ.viewableTiles = game.tileMap.values.toSet()
        val worker = game.addUnit("Worker", civ, tile(9, 0))
        civ.viewableTiles = game.tileMap.values.toSet()  // putting a unit on the map recomputes what the civilization sees
        val reach = ExplorerAi.Reach(worker, abortInDanger = true, visibleOnly = true)
        assertFalse("the original's 7 turns from 0 are 7 from 1 here", reach.within(BuilderAssignment.BUILDER_MAX_TURNS).any { it.tile == tile(1, 0) })
        val pick = BuilderTaskingAi(civ).pickFor(worker)
        assertNotNull("seven turns away is in reach", pick)
        assertEquals(tile(1, 0), pick!!.tile)
        val plot = ExplorerAi.Reach(worker, abortInDanger = true, visibleOnly = true).within(BuilderAssignment.BUILDER_MAX_TURNS + 1).single { it.tile == tile(1, 0) }
        assertEquals("the 8th turn counted from 1 is the 7th from 0", BuilderAssignment.BUILDER_MAX_TURNS + 1, plot.pathLength)
        assertEquals("seven turns, and one more for a builder that ends with no moves", BuilderAssignment.BUILDER_MAX_TURNS + (if (plot.movesLeft == 0) 1 else 0), pick.moveTurns)
    }

    @Test
    fun `a plot with danger above the builder's health is skipped unless our defender there has at most half its health in danger`() {
        research("Agriculture")
        onlyPlots(1 to 0)
        game.addCity(civ, tile(0, 0))
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addUnit("Warrior", enemy, tile(2, 0))
        val worker = game.addUnit("Worker", civ, tile(0, 0))
        worker.health = 1
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val danger = civ.ai.plotDanger.get(worker, tile(1, 0))
        assertTrue("setup: the plot is in danger above the builder's health", danger > worker.health)
        assertNull(BuilderTaskingAi(civ).pickFor(worker))

        val archer = game.addUnit("Archer", civ, tile(1, 0))
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        assertEquals("with a defender the danger to it decides: $danger", civ.ai.plotDanger.get(archer, tile(1, 0)) <= archer.health / 2, BuilderTaskingAi(civ).pickFor(worker) != null)
    }

    //endregion
    //region Re-scoring

    @Test
    fun `a directive that cannot be built yet still takes the front and its plot's other builds count a third less`() {
        research("Agriculture")
        onlyPlots(1 to 0)
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0)
        val worker = game.addUnit("Worker", civ, plot)
        val ai = BuilderTaskingAi(civ)
        val later = directive("Trading post", plot, PlotBuildScore(100000), canBuild = false)
        val farm = directive("Farm", plot, PlotBuildScore(5000))
        val farmScore = ai.scorer.scorePlotBuild(plot, improvement("Farm"), farm.build)

        val picks = BuilderAssignment(civ, ai, listOf(later, farm)).assign(listOf(worker))
        val pick = picks.single()
        assertEquals("the one it can build now is built", "Farm", pick.build.improvement.name)
        assertEquals("⅔ of the base score of the farm now", farmScore.score * 2 / 3, pick.directive.plotBuildScore.score)
        assertEquals("and ⅓ of it more as potential", farmScore.potentialScore + farmScore.score / 3, pick.directive.plotBuildScore.potentialScore)
    }

    @Test
    fun `the first of two builds that can be built here drops the other, one build a plot`() {
        research("Agriculture", "Trade")
        onlyPlots(1 to 0)
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0)
        val first = game.addUnit("Worker", civ, plot)
        val second = game.addUnit("Worker", civ, tile(0, 0))
        val ai = BuilderTaskingAi(civ)
        val assignment = BuilderAssignment(civ, ai, listOf(directive("Farm", plot, PlotBuildScore(5000)), directive("Trading post", plot, PlotBuildScore(4000))))
        val picks = assignment.assign(listOf(first, second))
        assertEquals(1, picks.size)
        assertEquals("Farm", picks.single().build.improvement.name)
        assertTrue("nothing is left for the plot", assignment.remaining.none { it.tile == plot })
        assertEquals(improvement("Farm"), assignment.state.changedPlotImprovements[plot])
    }

    @Test
    fun `a second plot of the resource the first connects is scored again for what is planned`() {
        research("Bronze Working", "Mining")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        val east = tile(1, 0).also { it.setTileResource("Iron"); it.resourceAmount = 4 }
        val west = tile(-1, 0).also { it.setTileResource("Iron"); it.resourceAmount = 4 }
        val worker = game.addUnit("Worker", civ, east)
        val ai = BuilderTaskingAi(civ)
        fun ironDirective(plot: Tile) = ai.directives.single { it.tile == plot && it.build?.improvement?.name == "Mine" }
        val before = ironDirective(west).plotBuildScore

        val assignment = BuilderAssignment(civ, ai)
        val pick = assignment.assign(listOf(worker)).single()
        assertEquals(east, pick.tile)
        assertEquals("the plan counts the iron", east.resourceAmount, assignment.state.extraResources["Iron"])
        val after = assignment.remaining.single { it.tile == west && it.build?.improvement?.name == "Mine" }.plotBuildScore
        assertTrue("with iron to come the next plot is worth less: $before to $after", after.priorityScore < before.priorityScore)
        assertEquals("and what it lost is potential", before.score + before.potentialScore, after.score + after.potentialScore)
    }

    @Test
    fun `a plot next to the pick is scored again, a far one keeps its score`() {
        research("Agriculture")
        game.addCity(civ, tile(0, 0))
        val worker = game.addUnit("Worker", civ, tile(1, 0))
        val ai = BuilderTaskingAi(civ)
        val assignment = BuilderAssignment(civ, ai)
        val plot = assignment.assign(listOf(worker)).single().tile
        assertTrue("the plot is taken", assignment.remaining.none { it.tile == plot })
        for (open in assignment.remaining) {
            val original = ai.directives.first { it.tile == open.tile && it.build?.improvement == open.build?.improvement }
            assertEquals("a farm changes no yield around it", original.plotBuildScore, open.plotBuildScore)
        }
    }

    @Test
    fun `building a route gives the route directives next to it their full value`() {
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = 10)
        game.addCity(civ, tile(4, 0), initialPopulation = 10)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        val worker = game.addUnit("Worker", civ, capital.getCenterTile())
        val ai = BuilderTaskingAi(civ)
        val route = ai.directives.filter { it.build?.isRoute == true }
        assertTrue("setup: the connection is planned", route.size >= 3)
        val assignment = BuilderAssignment(civ, ai)
        val pick = assignment.assign(listOf(worker)).single()
        assertTrue(pick.build.isRoute)
        for (open in assignment.remaining.filter { it.build?.isRoute == true }) {
            val original = route.single { it.tile == open.tile && it.build?.improvement == open.build?.improvement }
            if (open.tile.aerialDistanceTo(pick.tile) == 1)
                assertEquals("next to the road: the potential is in the score", PlotBuildScore(original.plotBuildScore.score + original.plotBuildScore.potentialScore,
                    0, original.plotBuildScore.unusedYieldScore, original.plotBuildScore.scorePenalty), open.plotBuildScore)
        }
    }

    //endregion
    //region Leftovers and sentry points

    @Test
    fun `builders with nothing to do go to the city with the fewest builders, the closest first`() {
        onlyPlots()
        val capital = game.addCity(civ, tile(0, 0))
        val other = game.addCity(civ, tile(4, 0))
        val builders = listOf(tile(0, 0), tile(1, 0), tile(3, 0)).map { game.addUnit("Worker", civ, it) }
        val assignment = BuilderAssignment(civ, BuilderTaskingAi(civ))
        assertTrue(assignment.assign(builders).isEmpty())
        // The first: both cities have none, the capital is closer. The second: the other city has fewer. The third: one each, the other city is closer.
        assertEquals(capital, assignment.leftoverCity(builders[0]))
        assertEquals(other, assignment.leftoverCity(builders[1]))
        assertEquals(other, assignment.leftoverCity(builders[2]))
    }

    @Test
    fun `a pick next to another civilization's land is a sentry point weighted by its priority, one inside our land is none`() {
        research("Agriculture")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        game.addCity(game.addCiv(), tile(3, 0))
        assertTrue("setup: the plot between is theirs", tile(2, 0).getOwner() != civ && tile(2, 0).getOwner() != null)
        val east = game.addUnit("Worker", civ, tile(1, 0))
        val west = game.addUnit("Worker", civ, tile(-1, 0))
        val assignment = BuilderAssignment(civ, BuilderTaskingAi(civ))
        val picks = assignment.assign(listOf(east, west))
        assertEquals(2, picks.size)
        val sentry = assignment.sentryPoints.single()
        assertEquals(tile(1, 0), sentry.tile)
        assertEquals(HomelandTargetType.SentryPoint, sentry.type)
        assertEquals(picks.single { it.tile == tile(1, 0) }.directive.priorityScore, sentry.weight)
    }

    //endregion
    //region Work boats

    /** The plots [keep] are land except those in [water], which are coast with Fish (or without, [fish] false); the city is at (0,0) */
    private fun seaSetup(water: List<Pair<Int, Int>>, fish: Boolean = true, vararg land: Pair<Int, Int>) {
        research("Sailing", "Agriculture")
        onlyPlots(*(water + land).toTypedArray())
        for ((x, y) in water) {
            game.setTileTerrain(HexCoord(x, y), "Coast")
            if (fish) tile(x, y).setTileResource("Fish")
        }
        game.addCity(civ, tile(0, 0))
    }

    @Test
    fun `a work boat takes the water directive of its plot and two boats do not take the same plot`() {
        seaSetup(listOf(1 to 0, -1 to 0))
        val east = game.addUnit("Work Boats", civ, tile(1, 0))
        val west = game.addUnit("Work Boats", civ, tile(-1, 0))
        val picks = BuilderAssignment(civ, BuilderTaskingAi(civ)).assign(listOf(east, west))
        assertEquals(2, picks.size)
        assertEquals(tile(1, 0), picks.single { it.unit == east }.tile)
        assertEquals(tile(-1, 0), picks.single { it.unit == west }.tile)
        assertEquals("Fishing Boats", picks.first().build.improvement.name)
    }

    @Test
    fun `one water plot goes to one boat, the other has no directive and is left over`() {
        seaSetup(listOf(1 to 0, -1 to 0), fish = false)
        tile(1, 0).setTileResource("Fish")
        val capital = civ.cities.single()
        val east = game.addUnit("Work Boats", civ, tile(1, 0))
        val west = game.addUnit("Work Boats", civ, tile(-1, 0))
        val assignment = BuilderAssignment(civ, BuilderTaskingAi(civ))
        val picks = assignment.assign(listOf(east, west))
        assertEquals(east, picks.single().unit)
        assertNull(assignment.allPicks.firstOrNull { it.unit == west })
        assertEquals("a boat without a directive goes to the city with the fewest builders", capital, assignment.leftoverCity(west))
    }

    @Test
    fun `a worker never takes the fishing boats and a boat never takes the farm`() {
        seaSetup(listOf(-1 to 0), fish = true, 1 to 0)
        val worker = game.addUnit("Worker", civ, tile(1, 0))
        val boat = game.addUnit("Work Boats", civ, tile(-1, 0))
        val picks = BuilderAssignment(civ, BuilderTaskingAi(civ)).assign(listOf(worker, boat))
        assertEquals(2, picks.size)
        assertEquals("Farm", picks.single { it.unit == worker }.build.improvement.name)
        assertEquals("Fishing Boats", picks.single { it.unit == boat }.build.improvement.name)
    }

    @Test
    fun `a boat with no water resource has no pick even with land work to do`() {
        seaSetup(listOf(-1 to 0), fish = false, 1 to 0)
        val boat = game.addUnit("Work Boats", civ, tile(-1, 0))
        assertNull(BuilderTaskingAi(civ).pickFor(boat))
    }

    @Test
    fun `the first builder to move plans the boats too, and a boat is used up creating the improvement on its plot`() {
        seaSetup(listOf(1 to 0))
        val boat = game.addUnit("Work Boats", civ, tile(1, 0))
        val automation = civ.getWorkerAutomation()
        assertTrue(automation.executeWorkerMove(boat))
        assertEquals("Fishing Boats", tile(1, 0).improvement)
        assertTrue(boat.isDestroyed)
    }

    @Test
    fun `a boat with nothing to build moves towards the city`() {
        seaSetup(listOf(1 to 0, 2 to 0), fish = false)
        val boat = game.addUnit("Work Boats", civ, tile(2, 0))
        civ.getWorkerAutomation().automateWorkerAction(GameView(game.gameInfo, civ).getMapUnitView(boat))
        assertTrue(boat.getTile().aerialDistanceTo(tile(0, 0)) < 2)
    }

    //endregion
    //region WorkerAutomation

    @Test
    fun `the first builder to move plans every builder that can move and each carries out its own pick`() {
        research("Agriculture")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        val east = game.addUnit("Worker", civ, tile(1, 0))
        val west = game.addUnit("Worker", civ, tile(-1, 0))
        val automation = civ.getWorkerAutomation()

        assertTrue(automation.executeWorkerMove(east))
        assertEquals("both are planned", 2, automation.assignment.allPicks.size)
        assertEquals("Farm", tile(1, 0).improvementInProgress)
        assertNull("the other has not moved yet", tile(-1, 0).improvementInProgress)
        assertEquals(tile(1, 0).position, civ.assignedBuilderDirectives[east.id]?.position)

        assertTrue(automation.executeWorkerMove(west))
        assertEquals("Farm", tile(-1, 0).improvementInProgress)
        assertEquals("Farm", civ.assignedBuilderDirectives[west.id]?.improvement)
        assertNotNull(civ.assignedBuilderDirectives[east.id])
    }

    @Test
    fun `a builder that is building stays out of the pass and its plot is not taken`() {
        research("Agriculture")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        val busy = game.addUnit("Worker", civ, tile(1, 0))
        tile(1, 0).startWorkingOnImprovement(improvement("Farm"), civ, busy)
        assertTrue(WorkerAutomation.isBuilding(busy))
        val free = game.addUnit("Worker", civ, tile(0, 0))
        val automation = civ.getWorkerAutomation()
        assertTrue(automation.executeWorkerMove(busy))
        assertTrue(automation.executeWorkerMove(free))
        assertTrue("the builder at work is not planned", automation.assignment.allPicks.none { it.unit == busy })
        assertEquals(tile(-1, 0), automation.assignment.allPicks.single().tile)
    }

    @Test
    fun `a builder that comes later in the turn is planned by another pass over what is left`() {
        research("Agriculture")
        onlyPlots(1 to 0, -1 to 0)
        game.addCity(civ, tile(0, 0))
        val first = game.addUnit("Worker", civ, tile(1, 0))
        val automation = civ.getWorkerAutomation()
        assertTrue(automation.executeWorkerMove(first))
        assertEquals(tile(1, 0), automation.assignment.allPicks.single().tile)
        val late = game.addUnit("Worker", civ, tile(-1, 0))
        assertTrue(automation.executeWorkerMove(late))
        assertEquals(setOf(tile(1, 0), tile(-1, 0)), automation.assignment.allPicks.map { it.tile }.toSet())
    }

    @Test
    fun `the directives of the last turn are forgotten when the next turn is planned`() {
        research("Agriculture")
        onlyPlots(1 to 0)
        game.addCity(civ, tile(0, 0))
        val worker = game.addUnit("Worker", civ, tile(1, 0))
        civ.assignedBuilderDirectives[worker.id] = AssignedBuilderDirective(tile(5, 5).position, "Road")
        BuilderTaskingAi(civ)
        assertTrue(civ.assignedBuilderDirectives.isEmpty())
    }

    //endregion
}
