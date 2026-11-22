package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvDealAI::GetCityValueForDeal (Vox Populi): the price a buyer would pay and the cities it refuses */
@RunWith(GdxTestRunner::class)
class DealAiCityValueTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (x in listOf(a, b)) for (y in listOf(a, b)) if (x != y) x.ai.diplomacy.setApproach(y, MajorCivApproach.Neutral)
        // Cities founded this long ago may be traded
        game.gameInfo.turns = 100
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** A city of a that b would take and could hold: nearer to b's capital than to a's other city by the original's 3/2 rule */
    private fun cityNearB(): City = oldCity(HexCoord(5, 0))

    /** A city of a founded at the start of the game */
    private fun oldCity(at: HexCoord): City = game.addCity(a, game.getTile(at)).also { it.turnAcquired = 0 }

    @Test
    fun `a capital is never for sale unless the cities are only being compared`() {
        val capital = a.getCapital()!!
        assertNull(DealAi.getCityValueForDeal(capital, b))
        assertNotNull(DealAi.getCityValueForDeal(capital, b, forCityComparison = true))
    }

    @Test
    fun `a city the buyer would struggle to hold is refused, one it can hold is priced at least 729 per citizen`() {
        val far = oldCity(HexCoord(0, 4))
        assertNull("8 from b, 4 from a's capital", DealAi.getCityValueForDeal(far, b))
        assertNotNull("compared only", DealAi.getCityValueForDeal(far, b, forCityComparison = true))
        val near = cityNearB()
        val value = DealAi.getCityValueForDeal(near, b)
        assertNotNull(value)
        assertTrue(value!! >= 15000 * 70 / 100)
        near.population.addPopulation(30)
        assertTrue("the floor", DealAi.getCityValueForDeal(near, b)!! >= near.population.population * 729)
    }

    @Test
    fun `a puppet is worth 70 percent, a city being razed 50, each wonder adds 11 percent`() {
        val city = cityNearB()
        val base = DealAi.getCityValueForDeal(city, b)!!
        city.isPuppet = true
        assertEquals(base * 70 / 100, DealAi.getCityValueForDeal(city, b))
        city.isPuppet = false
        city.isBeingRazed = true
        assertEquals(base * 50 / 100, DealAi.getCityValueForDeal(city, b))
        city.isBeingRazed = false
        city.cityConstructions.addBuilding(game.createWonder())
        assertEquals(base * 111 / 100, DealAi.getCityValueForDeal(city, b))
    }

    @Test
    fun `an owner will not sell to a severe warmonger and asks double of a major one`() {
        val city = cityNearB()
        val base = DealAi.getCityValueForDeal(city, b)!!
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Minor
        assertEquals(base, DealAi.getCityValueForDeal(city, b))
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Major
        assertEquals(base * 2, DealAi.getCityValueForDeal(city, b))
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Severe
        assertNull(DealAi.getCityValueForDeal(city, b))
    }

    @Test
    fun `at war the buyer takes a city whatever the owner thinks of it`() {
        val city = cityNearB()
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Critical
        assertNull(DealAi.getCityValueForDeal(city, b))
        a.getDiplomacyManager(b)!!.declareWar()
        assertNotNull(DealAi.getCityValueForDeal(city, b))
    }

    @Test
    fun `an unhappy buyer buys no foreign city, and a new city is not sold`() {
        val city = cityNearB()
        assertNotNull(DealAi.getCityValueForDeal(city, b))
        // Unhappy with the needs model: 30 specialists in its capital are 30 unhappy citizens, against the happiness of its pool, which is far less
        val capital = b.getCapital()!!
        val originalPopulation = capital.population.population
        capital.population.setPopulation(30)
        capital.population.specialistAllocations.clear()
        capital.population.specialistAllocations.add("Scientist", 30)
        NeedsAiScenarios.refresh(b)
        assertTrue(b.isEmpireUnhappy())
        assertNull(DealAi.getCityValueForDeal(city, b))
        capital.population.specialistAllocations.clear()
        capital.population.setPopulation(originalPopulation)
        NeedsAiScenarios.refresh(b)
        assertTrue(!b.isEmpireUnhappy())
        game.gameInfo.turns = 10
        assertNull("founded or taken 10 turns ago", DealAi.getCityValueForDeal(city, b))
    }

    @Test
    fun `an owner with fewer than five cities does not sell its own city back to itself`() {
        val city = cityNearB()
        assertNull(DealAi.getCityValueForDeal(city, a))
    }
}
