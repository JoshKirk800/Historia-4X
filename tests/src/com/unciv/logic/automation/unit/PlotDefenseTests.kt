package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.CityStateAi
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.WarFace
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What a builder makes of a plot's surroundings (Vox Populi): [PlotDefense.defenseBuildValue] (CvPlot::GetDefenseBuildValue), the theft
 *  likelihood (CvBuilderTaskingAI.cpp: GetPlotTheftLikelyhood) and their terms in [PlotBuildScorer.scorePlotBuild]: forts and citadels, Great Person
 *  improvements, the combat bonus improvement and the culture bomb.
 *
 *  The plot P is (0,0) on flat grassland. Our city is four plots south of it (so P is out of its work range and no yield is in the scores), the
 *  neighbour's far east; [border] gives P and some of its six neighbours to us and some of the twelve plots two away to the neighbour. A fort is
 *  worth at most 1680 (400 threat × 50% defense × 2, × 6 plots of ours × 70) and a citadel 4872 (400 × (100% × 2 + 6 × 30 / 2) × 420 / 100), as the
 *  original's comments say.
 */
@RunWith(GdxTestRunner::class)
class PlotDefenseTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var neighbour: Civilization
    private lateinit var ours: City
    private lateinit var theirs: City

    @Before
    fun setUp() = start()

    private fun start(vararg civUniques: String) {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv(*civUniques)
        neighbour = game.addCiv()
        for (tile in game.tileMap.values) {
            tile.setExplored(civ, true)
            tile.setExplored(neighbour, true)
        }
        ours = game.addCity(civ, tile(0, 4))
        theirs = game.addCity(neighbour, tile(7, 0))
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]
    private val p get() = tile(0, 0)
    private fun ring(n: Int) = p.getTilesAtDistance(n).toList()
    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!
    private fun claim(city: City, tiles: Iterable<Tile>) { for (tile in tiles) city.expansion.takeOwnership(tile) }

    /** P and the first [ownRing1] plots around it are ours, the first [enemyRing2] plots two away are the neighbour's */
    private fun border(ownRing1: Int = 6, enemyRing2: Int = 12) {
        claim(ours, listOf(p) + ring(1).take(ownRing1))
        claim(theirs, ring(2).take(enemyRing2))
    }

    private fun stance(approach: MajorCivApproach, strength: StrengthLevel) {
        civ.ai.diplomacy.memoryOf(neighbour).approach = approach
        civ.ai.warStates.militaryStrengths[neighbour.civID] = strength
    }

    private fun value(name: String, build: Boolean = true, state: SBuilderState = SBuilderState.Default): Int {
        val improvement = improvement(name)
        return PlotDefense.defenseBuildValue(civ, p, if (build) Build(improvement) else null, improvement, state)
    }

    /** The original's rough count of a plot's yields */
    private fun roughYields(tile: Tile): Int {
        val stats = tile.stats.getTileStats(null)
        return PlotBuildScorer.YIELDS.sumOf { stats[it].toInt() }
    }

    //region Defense build value

    @Test
    fun `a fort and a citadel are worth the maxima of the original's comments at a border with a strong neighbour`() {
        border()
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        assertEquals("400 × (2 × 50) / 100 = 400, × 6 × 70 / 100", 1680, value("Fort"))
        // 6 plots of ours each add 30 of damage: 400 × (2 × 100 + 180 / 2) / 100 = 1160
        assertEquals(4872, value("Citadel"))
    }

    @Test
    fun `the threat is our approach towards the neighbour times its strength compared to ours`() {
        border()
        // A fort: the threat × 100 / 100, × 6 × 70 / 100
        for ((approach, strength, threat) in listOf(
            Triple(MajorCivApproach.Neutral, StrengthLevel.Average, 100),
            Triple(MajorCivApproach.Hostile, StrengthLevel.Strong, 150 * 125 / 100),
            Triple(MajorCivApproach.Afraid, StrengthLevel.Weak, 200 * 25 / 100),
            Triple(MajorCivApproach.Friendly, StrengthLevel.Immense, 0),
        )) {
            stance(approach, strength)
            assertEquals("$approach, $strength", threat * 420 / 100, value("Fort"))
        }
    }

    @Test
    fun `a new improvement needs three plots of another major within two plots`() {
        border(enemyRing2 = 2)
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        assertEquals(0, value("Fort"))
        claim(theirs, ring(2).take(3))
        assertEquals(1680, value("Fort"))
    }

    @Test
    fun `an improvement that exists or is started needs no threat and treats our friends as guarded and strong`() {
        border()
        stance(MajorCivApproach.Friendly, StrengthLevel.Pathetic)
        assertEquals("a friend builds nothing new", 0, value("Fort"))
        // Guarded 200 × strong 125 / 100 = 250, × 50% × 2, × 6 plots of ours × 70
        assertEquals(250 * 420 / 100, value("Fort", build = false))
        assertEquals("a repair counts as standing", 250 * 420 / 100,
            PlotDefense.defenseBuildValue(civ, p, Build(improvement("Repair")), improvement("Fort")))
        p.queueImprovement("Fort", 3)
        assertEquals("a started build counts as standing", 250 * 420 / 100, value("Fort"))
    }

    @Test
    fun `land of nobody is unsafe for an improvement that stands`() {
        border(enemyRing2 = 0)
        assertEquals(0, value("Fort"))
        assertEquals(250 * 420 / 100, value("Fort", build = false))
    }

    @Test
    fun `structures within two plots and a city of ours take from the value`() {
        border()
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        val structure = ring(1)[1]
        structure.setImprovement("Fort")
        assertEquals("one structure: (400 - 50) × 420 / 100", 350 * 420 / 100, value("Fort"))
        assertEquals("a plan for another plot counts like the improvement it has", 300 * 420 / 100,
            value("Fort", state = SBuilderState(mapOf(ring(1)[2] to improvement("Fort")))))
        assertEquals("a plan to build nothing there falls back to the improvement it has", 350 * 420 / 100,
            value("Fort", state = SBuilderState(mapOf(structure to null))))
        structure.removeImprovement()
        assertEquals(1680, value("Fort"))
    }

    @Test
    fun `a city of ours near the plot takes 150 for each plot short of three`() {
        border(enemyRing2 = 0)
        claim(theirs, ring(2).drop(1))
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        game.addCity(civ, ring(2)[0])
        assertEquals("setup: the city is ours", civ, ring(2)[0].getOwner())
        // Two plots away: 150 × (3 - 2)
        assertEquals((400 - 150) * (6 * 70) / 100, value("Fort"))
    }

    @Test
    fun `damage that a plot next to the plot already takes from an improvement is not counted twice`() {
        border()
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        // A citadel on a plot next to P: it hurts the two neighbours of P that touch it, which then add nothing; the other three and the citadel's
        // own plot add 30 each
        val citadel = ring(1)[0]
        citadel.setImprovement("Citadel")
        val touching = ring(1).filter { it != citadel && it.aerialDistanceTo(citadel) == 1 }
        assertEquals(2, touching.size)
        val damage = 6 * 30 - touching.size * 30
        // The citadel is a structure with a defensive bonus: 50 less
        assertEquals((400 * (200 + damage / 2) / 100 - 50) * 420 / 100, value("Citadel"))
    }

    @Test
    fun `the terrain defends too, less the feature that the build removes`() {
        border()
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        game.setTileTerrain(p.position, "Hill")
        assertEquals("hills 25%: 400 × (2 × (25 + 50)) / 100 = 600", 600 * 420 / 100, value("Fort"))

        game.setTileTerrainAndFeatures(p.position, "Grassland", "Forest")
        assertEquals("a forest 25%", 600 * 420 / 100, value("Fort"))
        val chop = Build(improvement("Fort"), removeFirst = improvement("Remove Forest"))
        assertEquals("the forest that the build removes does not count", 1680,
            PlotDefense.defenseBuildValue(civ, p, chop, improvement("Fort")))
        assertEquals("a citadel removes removable features itself", 4872, value("Citadel"))
    }

    @Test
    fun `a culture bomb counts the land it takes as ours`() {
        border(ownRing1 = 3)
        claim(theirs, ring(1).drop(3))
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        // Built: the six neighbours are ours afterwards; three of them (30 of damage each) are ours now: 400 × (200 + 90 / 2) / 100 = 980
        assertEquals(980 * (6 * 70) / 100, value("Citadel"))
        // Standing: only the three that are ours count
        assertEquals(980 * (3 * 70) / 100, value("Citadel", build = false))
        // A fort takes nothing: 400 × (2 × 50) / 100, × 3 × 70
        assertEquals(400 * (3 * 70) / 100, value("Fort"))
    }

    //endregion
    //region The strategic value (shared with the route plan)

    @Test
    fun `the strategic value is the larger of the threat and the target value`() {
        border()
        for ((approach, strength, expected) in listOf(
            Triple(MajorCivApproach.Hostile, StrengthLevel.Strong, 187),
            Triple(MajorCivApproach.Guarded, StrengthLevel.Weak, 150),
            Triple(MajorCivApproach.Friendly, StrengthLevel.Immense, 0),
        )) {
            stance(approach, strength)
            assertEquals("$approach, $strength", expected, PlotDefense.strategicValue(civ, p))
        }
    }

    //endregion
    //region Theft

    private fun likelihood() = PlotDefense.theftLikelihood(civ, p)
    private fun showToUs(approach: MajorCivApproach) { neighbour.ai.diplomacy.memoryOf(civ).approach = approach }

    @Test
    fun `theft is impossible when all six neighbours are ours`() {
        border()
        showToUs(MajorCivApproach.War)
        assertEquals(TheftLikelihood.Impossible, likelihood())
    }

    @Test
    fun `how likely theft is follows the surface approach of the neighbour that owns a plot within two`() {
        border(ownRing1 = 5)
        for ((shown, expected) in listOf(
            MajorCivApproach.Hostile to TheftLikelihood.Certain,
            MajorCivApproach.Guarded to TheftLikelihood.Likely,
            MajorCivApproach.Neutral to TheftLikelihood.Possible,
            MajorCivApproach.Friendly to TheftLikelihood.Unlikely,
            MajorCivApproach.Deceptive to TheftLikelihood.Unlikely,  // it looks friendly
            MajorCivApproach.Afraid to TheftLikelihood.Unlikely,     // afraid of us: unlikely to steal
        )) {
            showToUs(shown)
            assertEquals(shown.name, expected, likelihood())
        }
        showToUs(MajorCivApproach.War)
        neighbour.ai.diplomacy.memoryOf(civ).warFace = WarFace.Hostile
        assertEquals("a war with a hostile face", TheftLikelihood.Certain, likelihood())
        neighbour.ai.diplomacy.memoryOf(civ).warFace = WarFace.Friendly
        assertEquals("a war with a friendly face", TheftLikelihood.Unlikely, likelihood())
    }

    @Test
    fun `the worst of two neighbours counts, and nobody within two plots counts for nothing`() {
        val third = game.addCiv()
        val thirdCity = game.addCity(third, tile(-7, 0))
        border(ownRing1 = 5)
        claim(thirdCity, ring(2).drop(6))
        showToUs(MajorCivApproach.Friendly)
        third.ai.diplomacy.memoryOf(civ).approach = MajorCivApproach.Guarded
        assertEquals(TheftLikelihood.Likely, likelihood())
        showToUs(MajorCivApproach.Hostile)
        assertEquals(TheftLikelihood.Certain, likelihood())

        // Nobody within two plots but an unowned plot next to P
        claim(ours, ring(2))
        assertEquals(TheftLikelihood.Unlikely, likelihood())
    }

    @Test
    fun `water next to the plot is nobody's and lets a neighbour two plots away steal`() {
        border()
        showToUs(MajorCivApproach.Hostile)
        assertEquals(TheftLikelihood.Impossible, likelihood())
        ours.expansion.relinquishOwnership(ring(1)[0])
        game.setTileTerrain(ring(1)[0].position, "Ocean")
        assertEquals(TheftLikelihood.Certain, likelihood())
    }

    @Test
    fun `a Great Person's improvement loses what the theft likelihood says, a culture bomb and other improvements nothing`() {
        claim(ours, listOf(p) + ring(1))
        val academy = improvement("Academy")
        val farm = improvement("Farm")
        fun score(improvement: TileImprovement) = PlotBuildScorer(civ).scorePlotBuild(p, improvement, Build(improvement)).score
        assertEquals("setup: no neighbour, no yield in range, nothing to lose", 0, score(academy))
        val farmScore = score(farm)

        claim(theirs, listOf(ring(1)[0]))
        stance(MajorCivApproach.Friendly, StrengthLevel.Average)  // nothing to defend
        for ((shown, penalty) in listOf(MajorCivApproach.Hostile to 1000, MajorCivApproach.Guarded to 500,
            MajorCivApproach.Neutral to 100, MajorCivApproach.Friendly to 40)) {
            showToUs(shown)
            assertEquals("$shown: -$penalty", -penalty, score(academy))
            assertEquals("a farm is not a Great Person's", farmScore, score(farm))
        }
    }

    //endregion
    //region The culture bomb

    private fun citadelScore(builder: Civilization = civ): Int =
        PlotBuildScorer(builder).scorePlotBuild(p, improvement("Citadel"), Build(improvement("Citadel"))).score

    @Test
    fun `a citadel starts at minus 2000 and takes no theft penalty`() {
        stance(MajorCivApproach.Friendly, StrengthLevel.Average)  // the defense of the plot is not under test
        claim(ours, listOf(p) + ring(1))
        showToUs(MajorCivApproach.Hostile)
        assertEquals(-2000, citadelScore())
        claim(theirs, listOf(ring(1)[0]))
        assertEquals("the hostile neighbour steals: only the bomb's terms", -2000 + (10 + roughYields(ring(1)[0])) * 4 * 10, citadelScore())
    }

    @Test
    fun `a plot of somebody else is worth more the less we like the owner`() {
        stance(MajorCivApproach.Friendly, StrengthLevel.Average)
        claim(ours, listOf(p) + ring(1))
        val enemy = ring(1)[0]
        claim(theirs, listOf(enemy))
        val plot = 10 + roughYields(enemy)
        assertEquals("opinion neutral: × 4 × 10", -2000 + plot * 4 * 10, citadelScore())
        civ.ai.diplomacy.memoryOf(neighbour).opinion = MajorCivOpinion.Enemy
        assertEquals("worse than neutral: × 8", -2000 + plot * 8 * 10, citadelScore())
        civ.ai.diplomacy.memoryOf(neighbour).opinion = MajorCivOpinion.Favorable
        assertEquals("better: × 1", -2000 + plot * 10, citadelScore())
    }

    @Test
    fun `the resource and the improvement of the plot add to what it is worth`() {
        stance(MajorCivApproach.Friendly, StrengthLevel.Average)
        claim(ours, listOf(p) + ring(1))
        val enemy = ring(1)[0]
        claim(theirs, listOf(enemy))
        enemy.setTileResource("Silk")
        assertEquals("a luxury we see: +50, +50 with an owner", -2000 + (10 + 100 + roughYields(enemy)) * 4 * 10, citadelScore())
        enemy.setTileResource(null)
        enemy.setImprovement("Fort")
        assertEquals("its defensive bonus", -2000 + (10 + 50 + roughYields(enemy)) * 4 * 10, citadelScore())
        enemy.setImprovement("Academy")
        assertEquals("a Great Person's improvement: +50", -2000 + (10 + 50 + roughYields(enemy)) * 4 * 10, citadelScore())
    }

    @Test
    fun `a plot nobody owns counts only after a plot of somebody else, in the order of the walk`() {
        stance(MajorCivApproach.Friendly, StrengthLevel.Average)
        claim(ours, listOf(p) + ring(1).drop(3))
        val first = ring(1)
        claim(theirs, listOf(first[2]))
        // The two plots before the neighbour's are nobody's and add nothing
        assertEquals(-2000 + (10 + roughYields(first[2])) * 4 * 10, citadelScore())
        // Its plot first: the two nobody's plots after it add 10 × 10 each
        theirs.expansion.relinquishOwnership(first[2])
        claim(theirs, listOf(first[0]))
        assertEquals(-2000 + (10 + roughYields(first[0])) * 4 * 10 + 2 * 10 * 10, citadelScore())
    }

    @Test
    fun `a city-state does not take from its friends, protectors or allies`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val csCity = game.addCity(cityState, tile(-7, 0))
        for (tile in game.tileMap.values) tile.setExplored(cityState, true)
        claim(csCity, listOf(p) + ring(1).drop(1))
        claim(theirs, listOf(ring(1)[0]))
        val stolen = (10 + roughYields(ring(1)[0])) * 4 * 10
        assertEquals("setup: a neighbour's plot is worth stealing", -2000 + stolen, citadelScore(cityState))

        val diplomacy = cityState.getDiplomacyManagerOrMeet(neighbour)
        diplomacy.setInfluence(35f)
        assertTrue(CityStateAi.isFriends(cityState, neighbour))
        assertEquals("a friend of the city-state: -10000", -2000 - 10000, citadelScore(cityState))
        diplomacy.setInfluence(0f)
        assertEquals(-2000 + stolen, citadelScore(cityState))
        cityState.cityStateFunctions.addProtectorCiv(neighbour)
        assertTrue(CityStateAi.isProtectedBy(cityState, neighbour))
        assertEquals("protected by the owner", -2000 - 10000, citadelScore(cityState))
    }

    //endregion
    //region The combat bonus improvement

    private fun moaiScore(): Int {
        claim(ours, p.getTilesInDistance(3).filter { !it.isCityCenter() }.toList())
        val moai = improvement("Moai")
        return PlotBuildScorer(civ).scorePlotBuild(p, moai, Build(moai)).score
    }

    @Test
    fun `the combat bonus improvement is worth twice its bonus for each plot of ours that it covers`() {
        start("[+10]% Strength <within [2] tiles of a [Moai]>")
        val nation = PlotDefense.combatBonusImprovement(civ)!!
        assertEquals("Moai", nation.improvement.name)
        assertEquals(2, nation.range)
        assertEquals(10, nation.bonus)
        // The 19 plots within two plots of the plot are ours
        assertEquals(19 * 10 * 2, moaiScore())
        start()
        assertEquals(0, moaiScore())
    }

    @Test
    fun `a plot that another moai covers already is not worth anything again, planned or built`() {
        start("[+10]% Strength <within [2] tiles of a [Moai]>")
        claim(ours, p.getTilesInDistance(4).filter { !it.isCityCenter() }.toList())
        val moai = improvement("Moai")
        val other = ring(2)[0]
        fun score(state: SBuilderState = SBuilderState.Default) = PlotBuildScorer(civ).scorePlotBuild(p, moai, Build(moai), state).score
        assertEquals(19 * 10 * 2, score())
        val uncovered = p.getTilesInDistance(2).count { covered -> covered.getTilesInDistance(2).none { near -> near == other } }
        assertTrue("setup: the other covers some of them", uncovered in 1..18)

        assertEquals("planned", uncovered * 10 * 2, score(SBuilderState(mapOf(other to moai))))
        other.setImprovement("Moai")
        assertEquals("built", uncovered * 10 * 2, score())
        other.improvementIsPillaged = true
        assertEquals("a pillaged one covers nothing", 19 * 10 * 2, score())
    }

    //endregion
    //region In the score

    @Test
    fun `a fort is worth its defense where a neighbour threatens and has half its yield where none does`() {
        game.addCity(civ, tile(0, 3))  // P is in its work range
        border()
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        val fort = improvement("Fort")
        val citadel = improvement("Citadel")
        val scorer = PlotBuildScorer(civ).also { it.updateAllCityWorstPlots() }
        assertTrue("setup: the plot is in the work range of a city", scorer.isWithinWorkRange(p))
        val food = scorer.projectedYields(p, Build(fort), ours)[PlotBuildScorer.YIELDS.indexOf(Stat.Food)]
        assertEquals(2, food)
        assertEquals("the yields and the defense", food * 180 + 1680, scorer.scorePlotBuild(p, fort, Build(fort)).score)

        stance(MajorCivApproach.Friendly, StrengthLevel.Average)
        assertEquals("nothing to defend: half the yields", food * 180 / 2, scorer.scorePlotBuild(p, fort, Build(fort)).score)
        assertEquals("a citadel keeps its yields", food * 180 - 2000, scorer.scorePlotBuild(p, citadel, Build(citadel)).score)
    }

    @Test
    fun `a fort or a citadel outside our borders is never worth building`() {
        stance(MajorCivApproach.Guarded, StrengthLevel.Immense)
        claim(theirs, ring(2))
        claim(ours, listOf(p) + ring(1))
        val scorer = PlotBuildScorer(civ)
        for (name in listOf("Fort", "Citadel")) {
            assertEquals("a neighbour's plot", PlotBuildScore(-1), scorer.scorePlotBuild(ring(2)[0], improvement(name), Build(improvement(name))))
            val nobodys = p.getTilesAtDistance(3).first { it.getOwner() == null }
            assertEquals("nobody's plot", PlotBuildScore(-1), scorer.scorePlotBuild(nobodys, improvement(name), Build(improvement(name))))
        }
    }

    //endregion
}
