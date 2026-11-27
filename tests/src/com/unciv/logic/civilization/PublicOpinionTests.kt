package com.unciv.logic.civilization

import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.PublicOpinionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Milestone
import com.unciv.models.ruleset.Victory
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.OriginalHappinessModel
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class PublicOpinionTests {
    private lateinit var game: TestGame
    private lateinit var me: Civilization
    private lateinit var freedomCiv: Civilization
    private lateinit var autocracyCiv: Civilization
    private lateinit var orderCiv: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        me = game.addCiv()
        freedomCiv = game.addCiv()
        autocracyCiv = game.addCiv()
        orderCiv = game.addCiv()
        game.addCity(me, game.tileMap[0, 0], replacePalace = true).population.setPopulation(25)
        game.addCity(freedomCiv, game.tileMap[0, 6], replacePalace = true)
        game.addCity(autocracyCiv, game.tileMap[0, -6], replacePalace = true)
        game.addCity(orderCiv, game.tileMap[6, 0], replacePalace = true)
        for (other in listOf(freedomCiv, autocracyCiv, orderCiv)) {
            me.diplomacyFunctions.makeCivilizationsMeet(other)
            for (third in listOf(freedomCiv, autocracyCiv, orderCiv)) if (third != other) other.diplomacyFunctions.makeCivilizationsMeet(third)
            other.totalCultureForContests = 100
        }
        me.totalCultureForContests = 100
        for ((civ, branch) in listOf(freedomCiv to "Freedom", autocracyCiv to "Autocracy", orderCiv to "Order")) adopt(civ, branch)
    }

    private fun adopt(civ: Civilization, branch: String) {
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        civ.policies.adopt(game.ruleset.policyBranches[branch]!!)
    }

    private val percentOfLevel = mapOf(
        InfluenceLevel.Unknown to 0, InfluenceLevel.Exotic to 10, InfluenceLevel.Familiar to 30,
        InfluenceLevel.Popular to 60, InfluenceLevel.Influential to 100, InfluenceLevel.Dominant to 200)

    /** [from] has made so much tourism towards [to] that its level is [level] (with the 100 culture each has made) */
    private fun influence(from: Civilization, to: Civilization, level: InfluenceLevel) {
        from.culture.addInfluence(to, percentOfLevel[level]!!)
        assertEquals(level, from.culture.getInfluenceLevel(to))
    }

    private fun updateOpinion() = me.culture.publicOpinion.apply { update() }

    @Test
    fun `without an ideology there is no opinion`() {
        influence(freedomCiv, me, InfluenceLevel.Dominant)
        val opinion = updateOpinion()
        assertEquals(PublicOpinionType.Content, opinion.type)
        assertEquals(0, opinion.unhappiness)
        assertNull(opinion.preferredIdeology)
    }

    @Test
    fun `with an ideology and no influence the people are content`() {
        adopt(me, "Freedom")
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `the people are content when their ideology presses as much as all others together`() {
        adopt(me, "Freedom")
        influence(freedomCiv, me, InfluenceLevel.Familiar) // 2 for Freedom
        influence(autocracyCiv, me, InfluenceLevel.Exotic) // 1
        influence(orderCiv, me, InfluenceLevel.Exotic)      // 1
        val opinion = updateOpinion()
        assertEquals("2 against 1 and 1", PublicOpinionType.Content, opinion.type)
        assertEquals(0, opinion.unhappiness)

        influence(autocracyCiv, me, InfluenceLevel.Familiar) // 1 -> 2
        assertEquals("2 against 2 and 1 leaves 1", PublicOpinionType.Dissidents, updateOpinion().type)
        assertEquals(1, opinion.dissatisfaction)
    }

    @Test
    fun `a plurality of the others is not enough, it is the sum that counts`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Exotic)
        assertEquals(1, updateOpinion().dissatisfaction)
        assertEquals("Autocracy", me.culture.publicOpinion.preferredIdeology)
    }

    @Test
    fun `influence of an ideology is only what its civilizations lead us by, and nothing for the mutual`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Popular)
        influence(me, autocracyCiv, InfluenceLevel.Popular)
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
        influence(me, orderCiv, InfluenceLevel.Dominant)
        influence(orderCiv, me, InfluenceLevel.Familiar)
        assertEquals("what we lead by gives no pressure", PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `a civilization without an ideology presses for nothing`() {
        val plain = game.addCiv()
        game.addCity(plain, game.tileMap[-6, 0], replacePalace = true)
        me.diplomacyFunctions.makeCivilizationsMeet(plain)
        plain.totalCultureForContests = 100
        adopt(me, "Freedom")
        influence(plain, me, InfluenceLevel.Dominant)
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `who has not met us presses for nothing`() {
        val stranger = game.addCiv()
        game.addCity(stranger, game.tileMap[-6, 0], replacePalace = true)
        adopt(stranger, "Order")
        adopt(me, "Freedom")
        assertNull(stranger.culture.getInfluenceLevel(me))
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `the type goes by what is missing, and the unhappiness by the type`() {
        adopt(me, "Freedom")
        // 25 inhabitants in 1 city
        influence(autocracyCiv, me, InfluenceLevel.Familiar) // 2
        var opinion = updateOpinion()
        assertEquals(PublicOpinionType.Dissidents, opinion.type)
        assertEquals("1 per 10 inhabitants, 1 per city: the greater", 2, opinion.unhappiness)

        influence(orderCiv, me, InfluenceLevel.Exotic) // 3
        opinion = updateOpinion()
        assertEquals(PublicOpinionType.CivilResistance, opinion.type)
        assertEquals("1 per 5, 2 per city", 5, opinion.unhappiness)

        influence(orderCiv, me, InfluenceLevel.Popular) // adds up to 60 more: Dominant at 70? keep to levels by what is there
        opinion = updateOpinion()
        assertEquals(PublicOpinionType.RevolutionaryWave, opinion.type)
        assertEquals("1 per 3 inhabitants, 4 per city", 8, opinion.unhappiness)
    }

    @Test
    fun `many small cities are counted by the city`() {
        adopt(me, "Freedom")
        me.cities.first().population.setPopulation(3)
        game.addCity(me, game.tileMap[3, 0]).population.setPopulation(3)
        game.addCity(me, game.tileMap[-3, 0]).population.setPopulation(3)
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        assertEquals("3 cities, 9 inhabitants", 3, updateOpinion().unhappiness)
    }

    private fun preferredWhenTied(own: String, first: String, second: String): String? {
        setUp()
        adopt(me, own)
        for (name in listOf(first, second)) influence(listOf(freedomCiv, autocracyCiv, orderCiv).first { it.getIdeology()!!.name == name }, me, InfluenceLevel.Exotic)
        return updateOpinion().preferredIdeology
    }

    @Test
    fun `a tie of the other ideologies goes as in the original`() {
        assertEquals("Order", preferredWhenTied("Freedom", "Autocracy", "Order"))
        assertEquals("Freedom", preferredWhenTied("Autocracy", "Freedom", "Order"))
        assertEquals("Autocracy", preferredWhenTied("Order", "Freedom", "Autocracy"))
    }

    @Test
    fun `the strongest of the others is preferred, and the civilization behind it is the biggest influence`() {
        adopt(me, "Order")
        influence(freedomCiv, me, InfluenceLevel.Exotic)
        influence(autocracyCiv, me, InfluenceLevel.Popular)
        val opinion = updateOpinion()
        assertEquals("Autocracy", opinion.preferredIdeology)
        assertEquals(autocracyCiv.civID, opinion.biggestInfluence)
    }

    @Test
    fun `the unhappiness is part of the happiness of a human, and of an AI changed by the difficulty`() {
        OriginalHappinessModel.use(game.ruleset)   // with the needs model it is the empire's unhappiness that the cities share, not an entry of the pool
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        updateOpinion()
        assertEquals(-2f, me.stats.getHappinessBreakdown()["Public Opinion"]!!, 0.001f)
    }

    @Test
    fun `the opinion is worked out every turn after the influence is added`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        assertEquals(PublicOpinionType.Content, me.culture.publicOpinion.type) // not before the turn
        me.culture.endTurn()
        assertEquals(PublicOpinionType.Dissidents, me.culture.publicOpinion.type)
    }

    @Test
    fun `changing to the preferred ideology works out the opinion again`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        updateOpinion()
        val preferred = game.ruleset.policyBranches[me.culture.publicOpinion.preferredIdeology!!]!!
        me.policies.switchIdeology(preferred)
        assertEquals(preferred, me.getIdeology())
        assertEquals(PublicOpinionType.Content, me.culture.publicOpinion.type)
        assertEquals(game.gameInfo.turns, me.culture.publicOpinion.turnIdeologySwitch)
    }

    @Test
    fun `a copy of the game keeps the opinion`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        influence(orderCiv, me, InfluenceLevel.Exotic)
        updateOpinion()
        val copy = game.gameInfo.clone()
        val opinion = copy.getCivilization(me.civName).culture.publicOpinion
        assertEquals(PublicOpinionType.CivilResistance, opinion.type)
        assertEquals(5, opinion.unhappiness)
        assertEquals(me.culture.publicOpinion.preferredIdeology, opinion.preferredIdeology)
        assertTrue(opinion.pressure.isNotEmpty())
    }

    @Test
    fun `the culture victory of Vox Populi wants an ideology and content people`() {
        val milestone = Milestone("Follow an ideology with a content populace", Victory())
        updateOpinion()
        assertFalse("no ideology", milestone.hasBeenCompletedBy(me))
        adopt(me, "Freedom")
        updateOpinion()
        assertTrue(milestone.hasBeenCompletedBy(me))
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        updateOpinion()
        assertFalse("dissidents", milestone.hasBeenCompletedBy(me))
    }

    private fun likeVp() {
        game.ruleset.modOptions.constants.publicOpinionLikeVp = true
        game.setSpeed("Standard")
    }

    /** The influence of [from] on us grew this turn more than our culture did */
    private fun rising(from: Civilization) {
        me.culture.rememberLastTurn() // our 100 culture
        from.culture.lastTurnInfluence.clear()
    }

    /** The influence of [from] on us grew this turn less than our culture did */
    private fun falling(from: Civilization) {
        me.culture.rememberLastTurn()
        from.culture.lastTurnInfluence.add(me.civID, 1000)
    }

    @Test
    fun `with Vox Populi an influence from Popular on is raised by the trends, and below Popular is as it is or nothing for a choice`() {
        likeVp()
        influence(autocracyCiv, me, InfluenceLevel.Popular)
        rising(autocracyCiv)
        influence(freedomCiv, me, InfluenceLevel.Popular)
        falling(freedomCiv)
        influence(orderCiv, me, InfluenceLevel.Familiar)
        rising(orderCiv)
        val opinion = me.culture.publicOpinion
        var pressure = opinion.getPressureOnUs().first
        assertEquals("Popular rising, ours on them static and raised by 1", 3 - 1, pressure["Autocracy"])
        assertEquals("Popular falling, raised by 2", 5 - 1, pressure["Freedom"])
        assertEquals("Familiar is not raised", 2, pressure["Order"])
        pressure = opinion.getPressureOnUs(hypothetical = true).first
        assertEquals(0, pressure["Order"])
        assertEquals(4, pressure["Freedom"])
    }

    @Test
    fun `with Vox Populi an ideology few follow, and one just chosen, press for themselves`() {
        likeVp()
        game.gameInfo.turns = 5
        adopt(me, "Freedom")
        val opinion = me.culture.publicOpinion
        assertEquals(5, opinion.turnIdeologyAdopted)
        // 4 civilizations follow an ideology, 2 Freedom: (4 - 2 + 1) / 3 ideologies, and 30 turns on Standard speed less those since, in tens rounded up
        assertEquals(1 + 3, updateOpinion().pressure["Freedom"])
        game.gameInfo.turns = 20
        assertEquals(1 + 2, updateOpinion().pressure["Freedom"])
        game.gameInfo.turns = 35
        assertEquals(1, updateOpinion().pressure["Freedom"])
        assertEquals(PublicOpinionType.Content, opinion.type)

        // after a change the turns count from it, from the next opinion on
        me.policies.switchIdeology(game.ruleset.policyBranches["Autocracy"]!!)
        assertEquals(5, opinion.turnIdeologyAdopted)
        assertEquals(1, opinion.pressure["Autocracy"])
        assertEquals(1 + 3, updateOpinion().pressure["Autocracy"])
    }

    @Test
    fun `with Vox Populi the first of the strongest others is preferred, and the unhappiness grows with the dissatisfaction`() {
        likeVp()
        adopt(me, "Freedom")
        game.gameInfo.turns = 40
        // 25 inhabitants in 1 city, 1 pressure for Freedom that few follow
        influence(autocracyCiv, me, InfluenceLevel.Dominant) // 5 + 1 static, less our 0 + 1
        var opinion = updateOpinion()
        assertEquals(4, opinion.dissatisfaction)
        assertEquals(PublicOpinionType.CivilResistance, opinion.type)
        assertEquals("1.8 per city, 1 per 6 inhabitants", 4, opinion.unhappiness)
        assertEquals("Autocracy", opinion.preferredIdeology)
        assertEquals(autocracyCiv.civID, opinion.biggestInfluence)

        influence(orderCiv, me, InfluenceLevel.Dominant)
        opinion = updateOpinion()
        assertEquals(9, opinion.dissatisfaction)
        assertEquals("tied: Autocracy comes before Order in the ruleset", "Autocracy", opinion.preferredIdeology)
        assertEquals("2.8 per city, 1 per inhabitant", 25, opinion.unhappiness)
    }

    @Test
    fun `with Vox Populi the biggest influence goes by the levels as they are, not raised by the trends`() {
        likeVp()
        val secondAutocracy = game.addCiv()
        game.addCity(secondAutocracy, game.tileMap[-6, 0], replacePalace = true)
        me.diplomacyFunctions.makeCivilizationsMeet(secondAutocracy)
        secondAutocracy.totalCultureForContests = 100
        adopt(secondAutocracy, "Autocracy")
        adopt(me, "Freedom")
        game.gameInfo.turns = 40
        influence(autocracyCiv, me, InfluenceLevel.Popular)
        rising(autocracyCiv) // 3 - 1 with the trends
        influence(secondAutocracy, me, InfluenceLevel.Popular)
        falling(secondAutocracy) // 5 - 1 with the trends
        val opinion = updateOpinion()
        assertEquals("Autocracy", opinion.preferredIdeology)
        assertEquals("3 over 0 for both: the first", autocracyCiv.civID, opinion.biggestInfluence)
    }

    @Test
    fun `with Vox Populi the unhappiness of another ideology leaves out who is below Popular and the time since the adoption`() {
        likeVp()
        me.cities.first().population.setPopulation(36)
        adopt(me, "Freedom") // the turn of the adoption: 3 for Freedom in the opinion itself
        influence(orderCiv, me, InfluenceLevel.Popular) // 4 - 1
        influence(freedomCiv, me, InfluenceLevel.Familiar) // left out
        val autocracy = game.ruleset.policyBranches["Autocracy"]!!
        // 3 for Order less 1 for Autocracy that only 1 of 4 follow: 1.4 per city, 1 per 8 inhabitants
        assertEquals(4, me.culture.publicOpinion.hypotheticalUnhappiness(autocracy))
        assertEquals(0, me.culture.publicOpinion.hypotheticalUnhappiness(game.ruleset.policyBranches["Order"]!!))
    }

    @Test
    fun `with Vox Populi many small cities are counted by the city`() {
        likeVp()
        adopt(me, "Freedom")
        game.gameInfo.turns = 40
        me.cities.first().population.setPopulation(3)
        game.addCity(me, game.tileMap[3, 0]).population.setPopulation(3)
        game.addCity(me, game.tileMap[-3, 0]).population.setPopulation(3)
        influence(autocracyCiv, me, InfluenceLevel.Dominant)
        val opinion = updateOpinion()
        assertEquals(4, opinion.dissatisfaction)
        assertEquals("1.8 for each of 3 cities", 5, opinion.unhappiness)
    }
}
