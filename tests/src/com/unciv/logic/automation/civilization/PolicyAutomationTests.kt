package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.city.NeedYields
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvPolicyAI / CvPlayerPolicies AI port against the DLL formulas (BNW XML values) */
@RunWith(GdxTestRunner::class)
class PolicyAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        civ = neutralCiv()
        game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        civ.stats.happiness = 0
    }

    /** A major whose personality flavors are all zero */
    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        return civ
    }

    private fun policy(name: String): Policy = game.ruleset.policies[name]!!
    private fun branch(name: String): PolicyBranch = game.ruleset.policyBranches[name]!!
    private fun weight(name: String) = PolicyAutomation.policyWeight(civ, policy(name))

    private fun reachEra(who: Civilization, era: String) {
        who.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == era }.name)
    }

    /** The weight [name] has in the pool of ChooseNextPolicy, null if it is not a candidate */
    private fun choiceWeight(name: String): Int? {
        val choices = PolicyAutomation.weighChoices(civ)
        val index = choices.candidates.indexOfFirst { it.name == name }
        return if (index < 0) null else choices.weights[index]
    }

    //region Policy weights (AddFlavorAsStrategies, AddFlavorWeights, PropagateWeights)
    @Test
    fun `a policy weighs its Policy_Flavors times the personality and grand strategy flavor, a quarter going to its prerequisite`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        // Legalism: CULTURE 20; Oligarchy is its prerequisite and has no culture
        assertEquals(140, weight("Legalism"))
        assertEquals(35, weight("Oligarchy"))
        // the culture grand strategy adds its FlavorMod CULTURE +2
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        PolicyAutomation.flavorUpdate(civ)
        // and its GOLD +1 weighs Monarchy (GOLD 10), a quarter of which goes to Legalism
        assertEquals(180 + 10 / 4, weight("Legalism"))
    }

    @Test
    fun `propagation passes on the propagated weight, prerequisite after prerequisite`() {
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 4)
        // Professional Army (MILITARY_TRAINING 15) = 60 requires Military Caste (none) requires Discipline (none)
        assertEquals(60, weight("Professional Army"))
        assertEquals(15, weight("Military Caste"))
        assertEquals(3, weight("Discipline"))
    }

    @Test
    fun `at war defense and city defense count 3 more`() {
        val enemy = game.addCiv()
        game.addCity(enemy, game.tileMap[0, 6], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        PolicyAutomation.flavorUpdate(civ)
        assertEquals(0, weight("Oligarchy"))
        civ.getDiplomacyManager(enemy)!!.declareWar()
        PolicyAutomation.flavorUpdate(civ)
        // Oligarchy: DEFENSE 10; Their Finest Hour: CITY_DEFENSE 44
        assertEquals(30, weight("Oligarchy"))
        assertEquals(132, weight("Their Finest Hour"))
    }

    @Test
    fun `an unhappy empire counts happiness 5 more`() {
        civ.stats.happiness = -1
        PolicyAutomation.flavorUpdate(civ)
        // Landed Elite: HAPPINESS 20
        assertEquals(100, weight("Landed Elite"))
    }

    @Test
    fun `losing money counts gold 5 more, not the gold flavor of the strategy`() {
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_LOSING_MONEY"] = 0
        PolicyAutomation.flavorUpdate(civ)
        // Oligarchy GOLD 10 = 50, plus Monarchy (GOLD 10 = 50) → 12 to Legalism → 3 to Oligarchy. The strategy's own GOLD +25 is not used
        assertEquals(53, weight("Oligarchy"))
    }

    @Test
    fun `weights stay those of the last flavor change until the strategies change`() {
        PolicyAutomation.flavorUpdate(civ)
        civ.stats.happiness = -1
        assertEquals("not recomputed for the unhappiness alone", 0, weight("Landed Elite"))
        civ.ai.setPersonalityFlavor(Flavor.Offense, 0) // any change of the flavors
        assertEquals(100, weight("Landed Elite"))
    }
    //endregion

    //region Choosing (ChooseNextPolicy, WeighPolicy, WeighBranch, IsBranchEffectiveInGame)
    @Test
    fun `no new branch is opened while one we opened can still be finished`() {
        civ.policies.storedCulture = 100000
        assertTrue(PolicyAutomation.weighChoices(civ).candidates.any { it.name == "Liberty" })
        civ.policies.freePolicies = 1
        civ.policies.adopt(branch("Tradition"))
        val candidates = PolicyAutomation.weighChoices(civ).candidates
        assertTrue(candidates.any { it.branch.name == "Tradition" })
        assertTrue("no branch: $candidates", candidates.none { it is PolicyBranch })
    }

    @Test
    fun `Patronage is worth nothing in a game without city-states`() {
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 7)
        reachEra(civ, "Classical era")
        civ.policies.storedCulture = 100000
        assertEquals(0, choiceWeight("Patronage"))
        game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        assertTrue((choiceWeight("Patronage") ?: 0) > 0)
    }

    @Test
    fun `with free tenets only tenets are candidates`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(branch("Order"))
        civ.policies.storedCulture = 100000
        civ.policies.freeTenets = 1
        val candidates = PolicyAutomation.weighChoices(civ).candidates
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.isTenet })
    }

    @Test
    fun `a free Settler is worth less food with each city we have, a free Worker more`() {
        fun food(name: String) = PolicyAttributeAi.weightPolicyAttributes(policy(name), PolicyAttributeAi.Inputs(civ))[PolicyAttributeAi.AttributeYield.Food.ordinal]
        assertEquals("Collective Rule, 1 city", 45, food("Collective Rule"))
        assertEquals("Citizenship, 1 city", 50, food("Citizenship"))
        game.addCity(civ, game.tileMap[4, 0])
        assertEquals("Collective Rule, 2 cities", 40, food("Collective Rule"))
        assertEquals("Citizenship, 2 cities", 100, food("Citizenship"))
    }
    //endregion

    //region Ideology (DoChooseIdeology)
    private val ideologies get() = PolicyAutomation.ideologiesOf(game.ruleset)!!

    private fun setPriorities(conquest: Int, unitedNations: Int, spaceship: Int, culture: Int) {
        civ.ai.grandStrategyPriorities[GrandStrategyAi.CONQUEST] = conquest
        civ.ai.grandStrategyPriorities[GrandStrategyAi.UNITED_NATIONS] = unitedNations
        civ.ai.grandStrategyPriorities[GrandStrategyAi.SPACESHIP] = spaceship
        civ.ai.grandStrategyPriorities[GrandStrategyAi.CULTURE] = culture
    }

    @Test
    fun `a clear victory preference rules out the ideology that does not support it`() {
        setPriorities(0, 0, 100, 0)
        val scores = PolicyAutomation.scoreIdeologies(civ, ideologies)
        // Freedom and Order support the space race; Autocracy's best (diplomacy, domination, culture: 0) loses by more than 25%
        assertEquals(0, scores[1])
        assertTrue("Freedom ${scores[0]}, Order ${scores[2]}", scores[0] > 0 && scores[2] > 0)
    }

    @Test
    fun `free tenets are expected for the first and second adopter, if the ideology is not of an era before the start`() {
        val freedom = branch("Freedom")
        assertEquals(2, PolicyAutomation.numFreeTenets(civ, freedom))
        val first = game.addCiv()
        game.addCity(first, game.tileMap[0, 6], replacePalace = true)
        first.policies.adopt(freedom)
        assertEquals(1, PolicyAutomation.numFreeTenets(civ, freedom))
        val second = game.addCiv()
        game.addCity(second, game.tileMap[0, -6], replacePalace = true)
        second.policies.adopt(freedom)
        assertEquals(0, PolicyAutomation.numFreeTenets(civ, freedom))
        assertEquals(2, PolicyAutomation.numFreeTenets(civ, branch("Order")))
        game.gameInfo.gameParameters.startingEra = "Modern era"
        assertEquals(0, PolicyAutomation.numFreeTenets(civ, branch("Order")))
    }

    @Test
    fun `our approach to a major with an ideology scores its ideology`() {
        val other = game.addCiv()
        game.addCity(other, game.tileMap[0, 6], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        other.policies.adopt(branch("Autocracy"))
        setPriorities(0, 0, 0, 0)
        fun delta(approach: MajorCivApproach): List<Int> {
            civ.ai.diplomacy.setApproach(other, MajorCivApproach.Neutral)
            val before = PolicyAutomation.scoreIdeologies(civ, ideologies)
            civ.ai.diplomacy.setApproach(other, approach)
            val after = PolicyAutomation.scoreIdeologies(civ, ideologies)
            return after.indices.map { after[it] - before[it] }
        }
        // Freedom, Autocracy, Order
        assertEquals(listOf(0, -500, 0), delta(MajorCivApproach.Hostile))
        assertEquals(listOf(0, -250, 0), delta(MajorCivApproach.Guarded))
        assertEquals(listOf(0, 150, 0), delta(MajorCivApproach.Afraid))
        assertEquals(listOf(0, 25, 0), delta(MajorCivApproach.Friendly))
        assertEquals("war counts nothing", listOf(0, 0, 0), delta(MajorCivApproach.War))
    }

    @Test
    fun `the public opinion an ideology would bring counts against it`() {
        val autocrat = game.addCiv()
        game.addCity(autocrat, game.tileMap[0, 6], replacePalace = true)
        civ.cities.first().population.setPopulation(25)
        civ.diplomacyFunctions.makeCivilizationsMeet(autocrat)
        civ.totalCultureForContests = 100
        autocrat.policies.adopt(branch("Autocracy"))
        autocrat.culture.addInfluence(civ, 200)
        assertEquals(InfluenceLevel.Dominant, autocrat.culture.getInfluenceLevel(civ))
        val opinion = civ.culture.publicOpinion
        // dissatisfaction 5 (Dominant over Unknown): 4 per city or 1 per 3 citizens
        assertEquals(8, opinion.hypotheticalUnhappiness(branch("Freedom")))
        assertEquals(8, opinion.hypotheticalUnhappiness(branch("Order")))
        assertEquals(0, opinion.hypotheticalUnhappiness(branch("Autocracy")))
        setPriorities(0, 0, 0, 0)
        // Two civilizations each holding its capital are both close to world conquest, which moves the approach set at contact;
        // a neutral one leaves the ideology scores to the public opinion
        civ.ai.diplomacy.memoryOf(autocrat).approach = MajorCivApproach.Neutral
        civ.stats.happiness = 0
        val content = PolicyAutomation.scoreIdeologies(civ, ideologies)
        civ.stats.happiness = -5
        val unhappy = PolicyAutomation.scoreIdeologies(civ, ideologies)
        // Unhappy and not the first ideology: (250 - the unhappiness it would bring) x 10
        assertEquals(listOf(242 * 10, 250 * 10, 242 * 10), unhappy.indices.map { unhappy[it] - content[it] })
    }
    //endregion

    //region Ideology switch (DoConsiderIdeologySwitch)
    private lateinit var autocrat: Civilization

    /** We follow Freedom (turn 200: never switched counts from turn -1), a dominant Autocracy makes 20 public opinion unhappiness (60 citizens / 3) */
    private fun unhappyWithFreedom() {
        game.gameInfo.turns = 200
        reachEra(civ, "Modern era")
        civ.policies.adopt(branch("Freedom"))
        autocrat = game.addCiv()
        game.addCity(autocrat, game.tileMap[0, 6], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(autocrat)
        civ.totalCultureForContests = 100
        autocrat.policies.adopt(branch("Autocracy"))
        autocrat.culture.addInfluence(civ, 200)
        civ.cities.first().population.setPopulation(60)
        civ.culture.publicOpinion.update()
        assertEquals(20, civ.culture.publicOpinion.unhappiness)
        civ.stats.happiness = -25
    }

    @Test
    fun `a super unhappy AI changes to the ideology its people prefer when that ends the super unhappiness`() {
        unhappyWithFreedom()
        // -19 + 20 - 0: very unhappy, but no revolt is coming
        civ.stats.happiness = -19
        assertFalse(PolicyAutomation.considerIdeologySwitch(civ))
        civ.stats.happiness = -20
        assertTrue(PolicyAutomation.considerIdeologySwitch(civ))
        assertEquals("Autocracy", civ.getIdeology()!!.name)
    }

    @Test
    fun `no switch within 30 turns of the last one`() {
        unhappyWithFreedom()
        civ.culture.publicOpinion.turnIdeologySwitch = game.gameInfo.turns - 30
        assertFalse(PolicyAutomation.considerIdeologySwitch(civ))
        civ.culture.publicOpinion.turnIdeologySwitch = game.gameInfo.turns - 31
        assertTrue(PolicyAutomation.considerIdeologySwitch(civ))
    }

    @Test
    fun `a very unhappy AI switches only with a city revolt coming, and not to the ideology a clear victory preference rules out`() {
        unhappyWithFreedom()
        civ.stats.happiness = -15
        val anarchy = game.ruleset.modOptions.constants.ideologyAnarchyTurns
        civ.addFlag(CivFlags.CityRevoltCountdown.name, 2 + anarchy)
        assertFalse("a revolt ${2 + anarchy} turns away", PolicyAutomation.considerIdeologySwitch(civ))
        civ.addFlag(CivFlags.CityRevoltCountdown.name, 3 + anarchy)
        setPriorities(100, 100, 126, 100) // science more than 25% ahead of each: not Autocracy
        assertFalse(PolicyAutomation.considerIdeologySwitch(civ))
        setPriorities(100, 100, 125, 100) // not more than 25%
        assertTrue(PolicyAutomation.considerIdeologySwitch(civ))
    }
    //endregion

    //region The needs model (cityNeedsLikeVp): CvPolicyAI::WeightPolicyAttributes

    private fun needsWeights(world: NeedsTestWorld, vararg uniques: String): IntArray =
        PolicyAttributeAi.weightPolicyAttributes(world.game.createPolicy(*uniques), PolicyAttributeAi.Inputs(world.civ))

    private val food = PolicyAttributeAi.AttributeYield.Food.ordinal

    @Test
    fun `a policy with a distress reduction is weighted 100 per point when the empire has distress`() {
        val world = NeedsTestWorld()
        val city = world.addCity(5)
        world.medians.basic = 500
        world.needsOf(city).update(NeedYields()) // 5 unhappy citizens of distress
        assertEquals(200, needsWeights(world, "Reduces [Distress] by [2] [in all cities]")[food])
        world.medians.gold = 500
        world.needsOf(city).update(NeedYields())
        assertEquals("poverty is weighted 40 a point", 80, needsWeights(world, "Reduces [Poverty] by [2] [in all cities]")[food])
        world.medians.gold = 0

        world.medians.basic = 0
        world.needsOf(city).update(NeedYields())
        assertEquals("no distress: half", 100, needsWeights(world, "Reduces [Distress] by [2] [in all cities]")[food])
        assertEquals("no poverty: 20 a point", 40, needsWeights(world, "Reduces [Poverty] by [2] [in all cities]")[food])
        assertEquals(100, needsWeights(world, "Reduces [Illiteracy] by [2] [in all cities]")[food])
        assertEquals(100, needsWeights(world, "Reduces [Religious Unrest] by [2] [in all cities]")[food])
    }

    @Test
    fun `specialists without unhappiness are weighted 10 a specialist, 25 in the capital`() {
        val world = NeedsTestWorld()
        world.addCity(5)
        assertEquals(20, needsWeights(world, "The first [2] specialists cause no unhappiness [in all cities]")[food])
        assertEquals(50, needsWeights(world, "The first [2] specialists cause no unhappiness [in capital]")[food])
    }

    @Test
    fun `golden age points from happiness are weighted by the positive deltas of the cities`() {
        val world = NeedsTestWorld()
        val city = NeedsAiScenarios.addEmpireCity(world, world.civ, 9, 2) // delta 7
        assertEquals(7, world.needsOf(city).delta)
        val gap = PolicyAttributeAi.AttributeYield.GoldenAgePoints.ordinal
        assertEquals(7 * 40 / 100, needsWeights(world, "[40]% golden age points from happiness")[gap])
    }

    //endregion
}
