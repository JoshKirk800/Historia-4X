package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

/**
 *  Decision-level checks of the CvTechAI / CvPlayerTechs port against Vox Populi's formulas (CvPlayerTechs::AddFlavorAsStrategies and
 *  SetGSPriorities, CvTechAI::AddFlavorWeights and PropagateWeights), with BNW Technology_Flavors and the defines (TECH_WEIGHT_PROPAGATION_PERCENT 50,
 *  TECH_PRIORITY_UNIQUE_ITEM 25 of VP, AI_RESEARCH_WEIGHT_BASE_MOD 0.15, AI_RESEARCH_WEIGHT_MOD_PER_TURN_LEFT 0.025 of the research choice, still BNW's).
 *  The weights are the output of a second implementation of the C++ on the same data (phaseE/vp-tech-wonder.md).
 */
@RunWith(GdxTestRunner::class)
class TechAutomationTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        game.setSpeed("Standard") // 500 turns: game progress p = turn × 1000 / 450 (the Prince's 90%), at least 100
        // SetGSPriorities adds one for each building a tech unlocks that is worth something: leave it to the tests that look at it
        game.ruleset.buildings.values.removeAll { it.requiredTech != null }
    }

    /** A civ (knowing Agriculture, as [TestGame.addCiv] gives) whose personality is zero except [flavors] */
    private fun civWith(vararg flavors: Pair<Flavor, Int>, nation: String? = null): Civilization {
        val civ = if (nation == null) game.addCiv() else game.addCiv(game.ruleset.nations[nation]!!)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for ((flavor, value) in flavors) civ.ai.setPersonalityFlavor(flavor, value)
        return civ
    }

    /** Gives [civ] a city at the map center, so it is alive and its tech costs are real; returns the civ */
    private fun withCity(civ: Civilization): Civilization {
        game.addCity(civ, game.getTile(0, 0))
        return civ
    }

    private fun weight(civ: Civilization, tech: String) = TechAutomation.weightOf(civ, tech)

    @Test
    fun `each visible nearby resource we cannot improve yet adds its score to the priority of the tech that lets us improve it`() {
        // Gold 10 -> blend 10. Animal Husbandry allows the Pasture that improves Cattle. Two Cattle tiles of our city at distance 1 on grassland:
        // each scores (3 - 1) + 1 (none of the resource yet) + 1 (the Pasture fits the tile) + 1 (we cannot build it yet) = 5, added to the priority 1
        // that every one of the 38 flavors adds to the weight again: 38 x 10 = 380 more, and the share of it that goes down to Agriculture
        val civ = withCity(civWith(Flavor.Gold to 10))
        TechAutomation.rebuildWeights(civ)
        // (the numbers of this class are from phaseE/vp_tech_ref.py on the Vox Populi tech tree and flavors of the ruleset)
        assertEquals(237, weight(civ, "Animal Husbandry"))
        assertEquals(417, weight(civ, "Pottery"))
        assertEquals(603, weight(civ, "Agriculture"))
        val cattle = game.ruleset.tileResources["Cattle"]!!
        for (position in listOf(game.getTile(1, 0).position, game.getTile(0, 1).position)) {
            game.setTileTerrain(position, "Grassland")
            game.tileMap[position].setTileResource(cattle)
        }
        TechAutomation.rebuildWeights(civ)
        assertEquals(237 + 380, weight(civ, "Animal Husbandry"))
        assertEquals(417, weight(civ, "Pottery"))
        assertEquals(603 + 190, weight(civ, "Agriculture"))
    }

    @Test
    fun `tech weight sums its flavors times the blend of the current flavors scaled to 10 at the largest, raised to 2 at least`() {
        // Offense 4, Nuke 2: current flavors scale to 10 and 5; at turn 0 (p = 100) the blends are (10x900 + 4x100)/1000 = 9 and (5x900 + 2x100)/1000 = 4;
        // Nuke 4 + (10 - 4)/4 = 5, and the 36 flavors at 0 get 10/4 = 2: every tech adds its priorities (1 + 1 + 1) once for each of the 38 flavors.
        // Expected values from an independent re-implementation of CvTechAI::AddFlavorWeights and PropagateWeights (phaseE/vp-tech-wonder.md)
        val civ = civWith(Flavor.Offense to 4, Flavor.Nuke to 2)
        TechAutomation.rebuildWeights(civ)
        assertEquals(366, weight(civ, "Pottery"))
        assertEquals(243, weight(civ, "Calendar"))
        assertEquals(299, weight(civ, "Theology"))
        assertEquals(426, weight(civ, "Military Strategy"))
        assertEquals(378, weight(civ, "Mining"))
        assertEquals(273, weight(civ, "Writing"))
        assertEquals("a tech far ahead gets nothing: it is no start of a weight and nothing in reach needs it", 0, weight(civ, "The Internet"))
    }

    @Test
    fun `a tech's weight is added to itself and a share of it, split evenly among the prerequisites, goes down to each`() {
        // Religion 10 -> blend 10 for it and 2 for the rest. Theology [Drama and Poetry, Philosophy, Currency], Philosophy [Sailing, Writing, Mathematics], Writing [Trade, Calendar],
        // Calendar [Trapping, The Wheel], Trade [Pottery, Trapping], Pottery [Agriculture]: BNW passed half to each prerequisite, VP half in all, divided by their number
        val civ = civWith(Flavor.Religion to 10)
        TechAutomation.rebuildWeights(civ)
        assertEquals(416, weight(civ, "Theology"))
        assertEquals(248, weight(civ, "Calendar"))
        assertEquals(292, weight(civ, "Writing"))
        assertEquals(370, weight(civ, "Pottery"))
        assertEquals(546, weight(civ, "Agriculture"))
        assertEquals(311, weight(civ, "Mining"))
    }

    @Test
    fun `techs unlocking our unique units get 25 more for each flavor, and what they pass on follows`() {
        // MilitaryTraining 10 -> blend 10; Bronze Working unlocks the Greek Hoplite, Trade the Companion Cavalry (the original's Horseback Riding): 38 x 25 = 950 more
        val plain = civWith(Flavor.MilitaryTraining to 10)
        val greece = civWith(Flavor.MilitaryTraining to 10, nation = "Greece")
        TechAutomation.rebuildWeights(plain)
        TechAutomation.rebuildWeights(greece)
        assertEquals(254, weight(plain, "Bronze Working"))
        assertEquals(254 + 950, weight(greece, "Bronze Working"))
        assertEquals(315, weight(plain, "Trade"))
        // 113 more than the reference's 315 + 950: Greece's leader has trait categories, whose priorities for the combat units that Trade unlocks (four of Vox Populi's data)
        // the reference does not model
        assertEquals(315 + 950 + 113, weight(greece, "Trade"))
        assertEquals("the prerequisite of Bronze Working gets its share of each pass", 356, weight(plain, "Mining"))
        assertEquals(850, weight(greece, "Mining"))
    }

    @Test
    fun `flavors drift from current needs to personality and grand strategy as the game progresses`() {
        // Religion 2 only: the current flavor scales to 10, the 37 others to 0 and get (10 - 0) / 4 on top of their blend, / 8 once p is over 500;
        // p = turn x 1000 / 450 (Prince: AIConstructPercent and AITrainPercent 100 shorten the 500 turns to 90%), at least 100 and at most 900
        val civ = civWith(Flavor.Religion to 2)
        fun theologyAt(turn: Int): Int {
            game.gameInfo.turns = turn
            TechAutomation.rebuildWeights(civ)
            return weight(civ, "Theology")
        }
        assertEquals(401, theologyAt(0))     // p clamped up to 100
        assertEquals(265, theologyAt(250))   // p = 555
        assertEquals(235, theologyAt(480))   // p = 1066, clamped to 900
        // a grand strategy that lists Religion among its own flavors and mods it by 1: the personality 2 + 1 = 3 is moved halfway up to the
        // strategy's 10, (10 + 3 + 1) / 2 = 7 (GetPersonalityAndGrandStrategy with bBoostGSMainFlavor), the blend is (10 x 100 + 7 x 900) / 1000 = 7
        val grandStrategies = game.ruleset.ai.grandStrategies
        grandStrategies[grandStrategies.indexOfFirst { it.name == GrandStrategyAi.CULTURE }] =
            GrandStrategyDef().apply { name = GrandStrategyAi.CULTURE; flavors = linkedMapOf("Religion" to 10); flavorMods = linkedMapOf("Religion" to 1) }
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        assertEquals(295, theologyAt(480))
    }

    @Test
    fun `a tech that allows embarking gets 10 more for each flavor on an island start`() {
        val civ = civWith(Flavor.Offense to 4, Flavor.Nuke to 2)
        game.ruleset.ai.economicStrategies.clear() // no flavors of the strategy itself
        civ.ai.economicStrategies[EconomicMilitaryStrategyTests.ISLAND_START] = 0
        TechAutomation.rebuildWeights(civ)
        // Fishing is the tech that enables embarkation in Vox Populi (the original's Optics): its own 257 + 38 x 10 and a share of it for Pottery
        assertEquals("Fishing itself: 257 + 38 x 10", 637, weight(civ, "Fishing"))
        assertEquals("Pottery, its prerequisite, gets its share", 556, weight(civ, "Pottery"))
        assertEquals("a tech unrelated to it keeps its weight", 243, weight(civ, "Calendar"))
    }

    /** Pottery, Military Strategy, Masonry, Writing and Calendar weigh this much for each trait category with Gold 10 (and the focus it gives) */
    @Test
    fun `the grand strategy priority counts the flavors a trait category cares about and what the tech unlocks`() {
        // No tech is needed by a unit (the buildings were left out in setUp) but these: a founder (Pottery, 10 - 0 cities), a combat unit
        // (Military Strategy) and a building worth something to a civilization (Masonry; asked without a city, 2 delegates: 200 / 2 diplomacy points)
        game.ruleset.units.values.removeAll { it.requiredTech != null }
        game.createBaseUnit(unitType = game.createUnitType().name, "Founds a new city").requiredTech = "Pottery"
        game.createBaseUnit().apply { strength = 8; requiredTech = "Military Strategy" }
        game.createBuilding("[2] World Congress Delegates").requiredTech = "Masonry"
        val expected = linkedMapOf(
            // the first row is also what phaseE/vp_tech_ref.py gives with the founder on Pottery (+10) and the delegates building on Masonry (+1); the others add the
            // trait category's priorities and are recorded from this implementation (the reference does not model the categories)
            null to listOf(797, 325, 396, 289, 247),
            "Diplomat" to listOf(833, 328, 434, 331, 289),     // Diplomacy, Gold, Espionage
            "Warmonger" to listOf(797, 526, 483, 292, 294),    // the military flavors, and the combat unit of Military Strategy
            "Tourism" to listOf(835, 374, 516, 373, 374),      // Culture, Great People, Wonder
            "Nerd" to listOf(797, 325, 396, 331, 289),         // Science, Spaceship
            "Smaller" to listOf(837, 369, 437, 289, 288),      // Growth, Naval Growth, City Defense
            "Religious" to listOf(797, 325, 396, 330, 253),    // Religion, Great People
            "Expansionist" to listOf(873, 331, 399, 289, 250), // Infrastructure, Expansion
        )
        val all = linkedMapOf<String?, List<Int>>()
        for (category in expected.keys) {
            val nation = Nation().apply { name = "Nation $category"; cities = arrayListOf("The Capital") }
            if (category != null) {
                val personality = Personality().apply { name = "Test $category"; traitCategories = linkedMapOf(category to 1) }
                game.ruleset.personalities[personality.name] = personality
                nation.personality = personality.name
            }
            game.ruleset.nations[nation.name] = nation
            val civ = game.addCiv(nation)
            for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
            civ.ai.setPersonalityFlavor(Flavor.Gold, 10)
            TechAutomation.rebuildWeights(civ)
            all[category] = listOf("Pottery", "Military Strategy", "Masonry", "Writing", "Calendar").map { weight(civ, it) }
        }
        assertEquals(expected, all)
    }

    @Test
    fun `research weights are divided by turns to the power of 0_15 plus 0_025 per turn`() {
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10))
        civ.stats.statsForNextTurn = Stats(science = 7f)
        civ.tech.techsInProgress["Pottery"] = 20
        TechAutomation.rebuildWeights(civ)
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue(candidates.size >= 4)
        assertTrue("research time must differ between candidates",
            candidates.map { civ.tech.costOfTech(it.first.name) - civ.tech.researchOfTech(it.first.name) }.toSet().size > 1)
        for ((tech, reweighted) in candidates) {
            val turns = ceil((civ.tech.costOfTech(tech.name) - civ.tech.researchOfTech(tech.name)) / 7.0).toInt()
            val expected = (weight(civ, tech.name) / turns.toDouble().pow(0.15 + 0.025 * turns)).toInt()
            assertEquals(tech.name, expected, reweighted)
        }
        assertEquals(candidates.sortedByDescending { it.second }, candidates)
    }

    @Test
    fun `research weights are multiplied instead of divided while the science overflow is more than twice the science`() {
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10))
        civ.stats.statsForNextTurn = Stats(science = 7f)
        civ.tech.techsInProgress["Pottery"] = 20
        TechAutomation.rebuildWeights(civ)
        civ.tech.addScience(14)   // no current research: goes to the overflow; 14 is exactly twice the science, not more
        assertEquals(14, civ.tech.getOverflowScience())
        for ((tech, reweighted) in TechAutomation.researchCandidates(civ, freeTech = false)) {
            val turns = maxOf(1, ceil(civ.tech.remainingScienceToTech(tech.name) / 7.0).toInt())
            assertEquals(tech.name, (weight(civ, tech.name) / turns.toDouble().pow(0.15 + 0.025 * turns)).toInt(), reweighted)
        }
        civ.tech.addScience(1)    // 15 > 2 x 7: CvTechAI::ReweightByCost now wants the expensive techs
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue(candidates.size >= 4)
        for ((tech, reweighted) in candidates) {
            val turns = maxOf(1, ceil(civ.tech.remainingScienceToTech(tech.name) / 7.0).toInt())
            assertEquals(tech.name, (weight(civ, tech.name) * turns.toDouble().pow(0.15 + 0.025 * turns)).toInt(), reweighted)
        }
    }

    @Test
    fun `the choice is weighted among the best two candidates only`() {
        game.setDifficulty("Prince") // HandicapInfos.TechNumOptionsConsidered 2
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10, Flavor.Production to 10))
        civ.stats.statsForNextTurn = Stats(science = 5f)
        TechAutomation.rebuildWeights(civ)
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue("needs a third positive candidate", candidates.count { it.second > 0 } >= 3 && candidates[1].second > candidates[2].second)
        val chosen = (0 until 200).map { TechAutomation.chooseNextTech(civ, freeTech = false, Random(it))!! }.toSet()
        assertEquals(setOf(candidates[0].first, candidates[1].first), chosen)
    }

    @Test
    fun `free techs ignore research time, take the best weight and reset the research queue`() {
        val civ = withCity(civWith(Flavor.Religion to 10)) // Pottery weighs 370, the best of the researchable techs (Mining 311, Animal Husbandry 229)
        civ.stats.statsForNextTurn = Stats() // no science: every research weight divides to 0
        TechAutomation.rebuildWeights(civ)
        assertNull(TechAutomation.chooseNextTech(civ, freeTech = false, Random(0)))
        // the choice is random among the best two (TechNumOptionsConsidered), by weight: Pottery 370 and Mining 311, a free tech is not reweighted by research time
        val chosen = (0 until 50).map { TechAutomation.chooseNextTech(civ, freeTech = true, Random(it))?.name }.toSet()
        assertEquals(setOf("Pottery", "Mining"), chosen)

        civ.stats.statsForNextTurn = Stats(science = 5f)
        civ.tech.freeTechs = 1
        civ.tech.techsToResearch.add("Military Strategy")
        TechAutomation.chooseTechToResearch(civ)
        assertTrue("one of the best two, Pottery 370 and Mining 311", civ.tech.isResearched("Pottery") || civ.tech.isResearched("Mining"))
        assertEquals(0, civ.tech.freeTechs)
        // the queue was cleared by the free tech, then refilled from the best two research candidates
        val bestTwo = TechAutomation.researchCandidates(civ, freeTech = false).take(2).map { it.first.name }
        assertEquals(bestTwo.toString(), 2, bestTwo.size)
        assertTrue(civ.tech.techsToResearch.toString(), civ.tech.techsToResearch.single() in bestTwo)
    }

    @Test
    fun `a civilization without science but with research overflow still chooses a tech`() {
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10))
        civ.stats.statsForNextTurn = Stats(science = 0f)
        TechAutomation.rebuildWeights(civ)
        civ.tech.addScience(14)   // overflow pending and no science: the research time is unbounded and the weights are multiplied
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue(candidates.size >= 4)
        assertTrue("no negative weight", candidates.all { it.second >= 0 })
        // used to throw "Random range is empty" when the two best weights added up beyond Int.MAX_VALUE
        assertTrue(TechAutomation.chooseNextTech(civ, freeTech = false, kotlin.random.Random(1)) != null)
    }
}
