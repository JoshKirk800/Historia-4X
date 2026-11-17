package com.unciv.logic.automation.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The per-turn directive list of [BuilderTaskingAi] and its selection and filters ([BuilderDirectives]), against CvBuilderTaskingAI.cpp
 *  (GetImprovementDirectives, AddImprovingPlotsDirective, AddRepairImprovementDirective, FilterOnlyBestImprovements,
 *  UpdateGreatPersonDirectives) and CvHomelandAI.cpp (GetDirectiveWeight, IsBestDirectiveForPlot).
 */
@RunWith(GdxTestRunner::class)
class BuilderDirectiveTests {
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

    private fun directive(
        improvement: String?, tile: Tile, score: PlotBuildScore, canBuild: Boolean = true,
        type: BuilderDirective.Type = BuilderDirective.Type.BuildImprovement, scrubsFallout: Boolean = false
    ) = BuilderDirective(
        type, improvement?.let { Build(improvement(it)) }, tile, improvement != null && improvement(improvement).isGreatImprovement(),
        canBuild, score, scrubsFallout
    )

    //region Weights and filters

    @Test
    fun `the priority score counts a third of the potential and loses the penalty and the unused yield`() {
        val score = PlotBuildScore(score = 300, potentialScore = 100, unusedYieldScore = 20, scorePenalty = 7)
        assertEquals(300 + 33 - 7 - 20, score.priorityScore)
        assertEquals(400, score.maxScore)
    }

    @Test
    fun `the directive weight is the score over ten squared over one plus build and move turns`() {
        val plot = tile(1, 0)
        val farm = directive("Farm", plot, PlotBuildScore(score = 1000))
        assertEquals(100 * 100 / (1 + 3 + 2), BuilderDirectives.directiveWeight(farm, 3, 2))
        assertEquals(10000, BuilderDirectives.directiveWeight(farm, 0, 0))
        // A negative score is made worse by the time
        val bad = directive("Farm", plot, PlotBuildScore(score = -300))
        assertEquals(-(30 * 30) * (1 + 3 + 2), BuilderDirectives.directiveWeight(bad, 3, 2))
    }

    @Test
    fun `a score beyond the square root of the maximum divides first and stops at the maximum`() {
        val plot = tile(1, 0)
        val large = directive("Farm", plot, PlotBuildScore(score = 600_000))
        // 60000 / (1 + 4) first, then times 60000
        assertEquals(60000 / 5 * 60000, BuilderDirectives.directiveWeight(large, 3, 1))
        val huge = directive("Farm", plot, PlotBuildScore(score = Int.MAX_VALUE / 2))
        assertEquals(Int.MAX_VALUE, BuilderDirectives.directiveWeight(huge, 0, 0))
    }

    @Test
    fun `a Great Person directive weighs a hundred times its score less a hundred per turn of travel`() {
        val plot = tile(1, 0)
        val academy = directive("Academy", plot, PlotBuildScore(score = 800, scorePenalty = 300))
        assertTrue(academy.isGreatPerson)
        assertEquals(100 * (500 - 100 * 2), BuilderDirectives.directiveWeight(academy, 9, 2))
        // No division by the build time, and a penalty above the score makes it negative but it still weighs
        val shadowed = directive("Academy", plot, PlotBuildScore(score = 100, scorePenalty = 250))
        assertEquals(100 * (-150 - 100 * 1), BuilderDirectives.directiveWeight(shadowed, 0, 1))
    }

    @Test
    fun `only the best build of a plot is kept, a tie drops both and a Great Person's improvement stays`() {
        val plot = tile(1, 0)
        val farm = directive("Farm", plot, PlotBuildScore(score = 300))
        val mine = directive("Mine", plot, PlotBuildScore(score = 200, potentialScore = 150))
        val academy = directive("Academy", plot, PlotBuildScore(score = 10))
        val kept = BuilderDirectives.filterOnlyBestImprovements(listOf(farm, mine, academy))
        assertEquals("the mine's 350 beats the farm's 300", listOf(mine, academy), kept)

        val tied = directive("Mine", plot, PlotBuildScore(score = 300))
        assertEquals(listOf(academy), BuilderDirectives.filterOnlyBestImprovements(listOf(farm, tied, academy)))
        // A build that cannot be done yet stands in nobody's way
        val ahead = directive("Mine", plot, PlotBuildScore(score = 900), canBuild = false)
        assertEquals(listOf(farm, ahead), BuilderDirectives.filterOnlyBestImprovements(listOf(farm, ahead)).sortedBy { it.plotBuildScore.score })
    }

    @Test
    fun `a build must score more than the improvement kept on the plot`() {
        val plot = tile(1, 0).also { it.setImprovement("Farm") }
        val keep = BuilderDirective(BuilderDirective.Type.KeepImprovement, null, plot, false, true, PlotBuildScore(score = 400 + 100))
        val worse = directive("Mine", plot, PlotBuildScore(score = 450))
        val better = directive("Mine", plot, PlotBuildScore(score = 600))
        assertEquals("keeping scores 100 more: the equal-ish build goes", listOf(keep), BuilderDirectives.filterOnlyBestImprovements(listOf(keep, worse)))
        assertEquals(listOf(better), BuilderDirectives.filterOnlyBestImprovements(listOf(keep, better)))
        assertFalse(BuilderDirectives.isBestDirectiveForPlot(keep, listOf(keep, better)))
    }

    @Test
    fun `a directive is the best for its plot unless a buildable one of the same kind has a higher maximum score`() {
        val plot = tile(1, 0)
        val farm = directive("Farm", plot, PlotBuildScore(score = 300))
        val mine = directive("Mine", plot, PlotBuildScore(score = 500))
        assertFalse(BuilderDirectives.isBestDirectiveForPlot(farm, listOf(farm, mine)))
        assertTrue(BuilderDirectives.isBestDirectiveForPlot(mine, listOf(farm, mine)))
        // Not when the better one cannot be built yet, nor when it is a Great Person's
        assertTrue(BuilderDirectives.isBestDirectiveForPlot(farm, listOf(farm, directive("Mine", plot, PlotBuildScore(score = 500), canBuild = false))))
        assertTrue(BuilderDirectives.isBestDirectiveForPlot(farm, listOf(farm, directive("Academy", plot, PlotBuildScore(score = 500)))))
        // A route and an improvement do not compete
        val road = BuilderDirective(BuilderDirective.Type.BuildRoute, Build(improvement("Road")), plot, false, true, PlotBuildScore(score = 5000))
        assertTrue(BuilderDirectives.isBestDirectiveForPlot(farm, listOf(farm, road)))
        // Fallout is scrubbed first, whatever the scores
        val scrub = BuilderDirective(BuilderDirective.Type.RemoveFeature, Build(improvement("Remove Forest")), plot, false, true, PlotBuildScore(score = 20000), scrubsFallout = true)
        assertFalse(BuilderDirectives.isBestDirectiveForPlot(mine, listOf(mine, scrub)))
        assertTrue(BuilderDirectives.isBestDirectiveForPlot(scrub, listOf(mine, scrub)))
    }

    @Test
    fun `a Great Person directive gets the best score of the other builds on its plot as penalty`() {
        val plot = tile(1, 0)
        val farm = directive("Farm", plot, PlotBuildScore(score = 300))
        val mine = directive("Mine", plot, PlotBuildScore(score = 200, potentialScore = 300, unusedYieldScore = 10))  // priority 290
        val academy = directive("Academy", plot, PlotBuildScore(score = 700))
        val other = directive("Academy", tile(2, 0), PlotBuildScore(score = 700))
        val updated = BuilderDirectives.updateGreatPersonDirectives(listOf(farm, mine, academy, other))
        assertEquals(300, updated[2].plotBuildScore.scorePenalty)
        assertEquals(400, updated[2].priorityScore)
        assertEquals("alone on its plot: no penalty", 0, updated[3].plotBuildScore.scorePenalty)
        assertEquals(farm.plotBuildScore, updated[0].plotBuildScore)
    }

    //endregion
    //region The list a turn plans

    private fun directivesOn(ai: BuilderTaskingAi, tile: Tile) = ai.directives.filter { it.tile == tile }

    @Test
    fun `a pillaged improvement gets a repair directive scored as the repaired improvement`() {
        research("Agriculture")
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0).also { it.setImprovement("Farm"); it.improvementIsPillaged = true }
        val whole = tile(-1, 0).also { it.setImprovement("Farm") }
        val ai = BuilderTaskingAi(civ)
        val repair = directivesOn(ai, plot).single { it.type == BuilderDirective.Type.RepairImprovement }
        assertEquals("Repair", repair.build!!.improvement.name)
        assertTrue(repair.canBuild)
        assertEquals(ai.scorer.scorePlotBuild(plot, improvement("Farm"), Build(improvement("Repair"))), repair.plotBuildScore)
        assertTrue(repair.plotBuildScore.maxScore > 0)
        // A plot whose improvement is not pillaged is kept, not repaired
        assertTrue(directivesOn(ai, whole).any { it.type == BuilderDirective.Type.KeepImprovement })
        assertTrue(directivesOn(ai, whole).none { it.type == BuilderDirective.Type.RepairImprovement })
    }

    @Test
    fun `a build whose tech is one era away is planned but cannot be built, its feature is chopped ahead`() {
        research("Mining")  // the forest can go
        civ.tech.techsResearched.remove("Agriculture")  // the farm is the next tech (the first one a civilization gets)
        game.addCity(civ, tile(0, 0))
        val forest = tile(1, 0).also { it.addTerrainFeature("Forest") }
        val ai = BuilderTaskingAi(civ)
        val onPlot = directivesOn(ai, forest)

        val farm = onPlot.single { it.build?.improvement?.name == "Farm" }
        assertFalse("Agriculture is not known", farm.canBuild)
        assertEquals("Remove Forest", farm.build!!.removeFirst!!.name)
        assertTrue(farm.plotBuildScore.maxScore > 0)

        val chop = onPlot.single { it.type == BuilderDirective.Type.RemoveFeature }
        assertEquals("Remove Forest", chop.build!!.improvement.name)
        assertTrue(chop.canBuild)
        val farmScore = farm.plotBuildScore
        assertEquals(
            PlotBuildScore(farmScore.score / 3, farmScore.potentialScore + farmScore.score * 2 / 3 - 1, farmScore.unusedYieldScore, farmScore.scorePenalty),
            chop.plotBuildScore
        )
    }

    @Test
    fun `a build more than one era ahead is not planned`() {
        val dutch = game.addCiv(game.ruleset.nations["The Netherlands"]!!)
        for (tile in game.tileMap.values) tile.setExplored(dutch, true)
        game.addCity(dutch, tile(0, 0))
        val marsh = tile(1, 0).also { it.addTerrainFeature("Marsh") }
        val polder = improvement("Polder")
        val polderEra = polder.techRequired!!.let { game.ruleset.technologies[it]!!.era(game.ruleset)!!.eraNumber }
        assertTrue("setup: Guilds is two eras ahead of the first tech", polderEra >= dutch.getAiEraNumber() + 2)
        assertTrue(directivesOn(BuilderTaskingAi(dutch), marsh).none { it.build?.improvement == polder })

        dutch.tech.addTechnology("Mathematics")
        assertTrue("setup: the era advanced", polderEra <= dutch.getAiEraNumber() + 1)
        val planned = directivesOn(BuilderTaskingAi(dutch), marsh).filter { it.build?.improvement == polder }
        assertEquals(1, planned.size)
        assertFalse(planned.single().canBuild)

        dutch.tech.addTechnology("Guilds")
        assertTrue(directivesOn(BuilderTaskingAi(dutch), marsh).single { it.build?.improvement == polder }.canBuild)
    }

    @Test
    fun `a Polder beats a Farm on a marsh that can be removed`() {
        val dutch = game.addCiv(game.ruleset.nations["The Netherlands"]!!)
        for (tile in game.tileMap.values) tile.setExplored(dutch, true)
        for (tech in listOf("Agriculture", "Masonry", "Currency", "Guilds", "Mathematics")) dutch.tech.addTechnology(tech)
        game.addCity(dutch, tile(0, 0))
        val marsh = tile(1, 0).also { it.addTerrainFeature("Marsh") }
        val ai = BuilderTaskingAi(dutch)
        val onPlot = directivesOn(ai, marsh)
        val polder = onPlot.single { it.build?.improvement?.name == "Polder" }
        assertTrue(polder.canBuild)
        // After "Remove Marsh" the farm is a worse plot: the filter leaves only the polder
        assertTrue("the farm directive was filtered", onPlot.none { it.build?.improvement?.name == "Farm" })

        marsh.setImprovement("Polder")
        val kept = BuilderTaskingAi(dutch)
        assertTrue(directivesOn(kept, marsh).any { it.type == BuilderDirective.Type.KeepImprovement })
        assertTrue("a plot with a good improvement is not rebuilt", directivesOn(kept, marsh).none { it.type == BuilderDirective.Type.BuildImprovement && it.canBuild })
    }

    @Test
    fun `a Great Person's improvement is never replaced, only repaired`() {
        research("Agriculture", "Bronze Working", "Mining")
        game.addCity(civ, tile(0, 0))
        val special = tile(1, 0).also { it.setImprovement("Academy") }
        assertTrue(directivesOn(BuilderTaskingAi(civ), special).none { it.type == BuilderDirective.Type.BuildImprovement })
        special.improvementIsPillaged = true
        assertTrue("a pillaged one is repaired", directivesOn(BuilderTaskingAi(civ), special).any { it.type == BuilderDirective.Type.RepairImprovement })
    }

    @Test
    fun `a Brazilwood camp is what a builder takes on a jungle that can be removed`() {
        val brazil = game.addCiv(game.ruleset.nations["Brazil"]!!)
        for (tile in game.tileMap.values) tile.setExplored(brazil, true)
        for (tech in listOf("Agriculture", "Bronze Working", "Machinery", "Mathematics", "The Wheel", "Construction", "Engineering")) brazil.tech.addTechnology(tech)
        val city = game.addCity(brazil, tile(0, 0))
        // Only the jungle plot is workable
        for (plot in game.tileMap.values) if (plot.position != HexCoord(0, 0) && plot.position != HexCoord(1, 0)) game.setTileTerrain(plot.position, "Mountain")
        val jungle = tile(1, 0).also { it.addTerrainFeature("Jungle") }
        val worker = game.addUnit("Worker", brazil, city.getCenterTile())
        val ai = BuilderTaskingAi(brazil)
        val camp = directivesOn(ai, jungle).singleOrNull { it.build?.improvement?.name == "Brazilwood Camp" }
        assertNotNull("the camp is planned", camp)
        assertTrue(camp!!.canBuild)
        assertEquals(camp, ai.evaluateBuilder(worker)?.directive)
    }

    @Test
    fun `scrubbing fallout scores the define and comes first`() {
        research("Agriculture", "Bronze Working")
        game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0).also { it.addTerrainFeature("Fallout") }
        val ai = BuilderTaskingAi(civ)
        val scrub = directivesOn(ai, plot).single { it.scrubsFallout }
        assertEquals(PlotBuildScore(20000), scrub.plotBuildScore)
        assertEquals(scrub, ai.directives.first())
    }

    @Test
    fun `the builder takes the directive of the best weight, a near plot beating an equal far one`() {
        research("Agriculture")
        game.addCity(civ, tile(0, 0))
        // Everything but two plots is mountain: the two equal grassland plots are 1 and 2 away from the worker
        for (plot in game.tileMap.values) if (plot.position != HexCoord(0, 0) && plot.position != HexCoord(1, 0) && plot.position != HexCoord(-1, 0)) game.setTileTerrain(plot.position, "Mountain")
        val worker = game.addUnit("Worker", civ, tile(1, 0))
        val ai = BuilderTaskingAi(civ)
        assertTrue(directivesOn(ai, tile(1, 0)).isNotEmpty() && directivesOn(ai, tile(-1, 0)).isNotEmpty())
        val picked = ai.evaluateBuilder(worker)!!
        assertEquals(tile(1, 0), picked.tile)
        assertEquals(0, picked.moveTurns)
        // The same plots, the worker on the other side
        worker.removeFromTile()
        worker.putInTile(tile(-1, 0))
        assertEquals(tile(-1, 0), BuilderTaskingAi(civ).evaluateBuilder(worker)!!.tile)
    }

    @Test
    fun `a Great Person takes its improvement's directive by the Great Person weight`() {
        research("Agriculture")
        val capital = game.addCity(civ, tile(0, 0))
        val scientist = game.addUnit("Great Scientist", civ, capital.getCenterTile())
        val ai = BuilderTaskingAi(civ)
        val picked = ai.evaluateBuilder(scientist)
        assertNotNull(picked)
        picked!!
        assertEquals("Academy", picked.build.improvement.name)
        assertTrue(picked.directive.isGreatPerson)
        assertEquals(100 * (picked.directive.priorityScore - 100 * picked.moveTurns), picked.weight)
        // A worker never takes it
        val worker = game.addUnit("Worker", civ, tile(0, 1))
        assertTrue(ai.evaluateBuilder(worker)?.directive?.isGreatPerson != true)
        assertTrue(civ.getWorkerAutomation().executeWorkerMove(scientist))
    }

    //endregion
}
