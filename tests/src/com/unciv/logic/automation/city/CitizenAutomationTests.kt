package com.unciv.logic.automation.city

import com.unciv.Constants
import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.OriginalHappinessModel
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvCityCitizens port (CitizenAutomation) against the DLL formulas with the BNW XML values */
@RunWith(GdxTestRunner::class)
class CitizenAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        civ = game.addCiv("[+20 Happiness] [in all cities]")
    }

    /** A grassland city whose six neighbours are [neighbours], with farms if [farms] */
    private fun city(at: HexCoord, neighbours: String, farms: Boolean, population: Int, owner: Civilization = civ): City {
        game.setTileTerrainAndFeatures(at, Constants.grassland)
        for (tile in game.getTile(at).neighbors) {
            game.setTileTerrainAndFeatures(tile.position, neighbours)
            if (farms) tile.setImprovementBasic("Farm")
        }
        return game.addCity(owner, game.getTile(at), initialPopulation = population)
    }

    /** A capital far away, so that the next city is an ordinary one */
    private fun addCapital() = city(HexCoord(-6, 0), Constants.desert, false, 1)

    private fun valuation(city: City, focus: CityFocus, excessFood: Float, happiness: Int = 10): CitizenAutomation.Valuation {
        city.setCityFocus(focus)
        city.civ.stats.happiness = happiness
        return CitizenAutomation.Valuation(city).also { it.excessFood = excessFood }
    }

    private val mixedTile = Stats(food = 2f, production = 1f, gold = 1f, science = 1f, culture = 1f, faith = 1f)
    /** Production 12 + gold 8 + science 7 + culture 15 + faith 5 (AI_CITIZEN_VALUE_*) */
    private val nonFoodValue = 47

    private fun writers(city: City) = city.population.specialistAllocations["Writer"]

    @Test
    fun `plot value weighs each yield and halves food once the surplus reaches 2`() {
        val city = city(HexCoord.Zero, Constants.desert, false, 6)
        assertEquals(14 * 2 / 2 + nonFoodValue, valuation(city, CityFocus.NoFocus, 2f).plotValue(mixedTile, true))
        assertEquals(14 * 2 * 8 + nonFoodValue, valuation(city, CityFocus.NoFocus, 1f).plotValue(mixedTile, true))
    }

    @Test
    fun `a focus triples its yield and a non-food focus only wants to stop starving`() {
        val city = city(HexCoord.Zero, Constants.desert, false, 6)
        assertEquals(14 + 12 + 8 + 7 * 3 + 15 + 5, valuation(city, CityFocus.ScienceFocus, 0f).plotValue(mixedTile, true))
        assertEquals(14 * 2 * 8 + 12 + 8 + 7 * 3 + 15 + 5, valuation(city, CityFocus.ScienceFocus, -1f).plotValue(mixedTile, true))
        assertEquals(14 + 12 + 8 + 7 + 15 * 3 + 5, valuation(city, CityFocus.CultureFocus, 0f).plotValue(mixedTile, true))
        // Growth focuses double both yields, then halve food past +2 like no focus; the food focus keeps food whole
        assertEquals(14 * 2 * 2 / 2 + 12 + 8 * 2 + 7 + 15 + 5, valuation(city, CityFocus.GoldGrowthFocus, 2f).plotValue(mixedTile, true))
        assertEquals(14 * 2 * 3 + nonFoodValue, valuation(city, CityFocus.FoodFocus, 2f).plotValue(mixedTile, true))
    }

    @Test
    fun `small cities quadruple food and an unhappy empire values no surplus food`() {
        val small = city(HexCoord.Zero, Constants.desert, false, 3)
        assertEquals(14 * 2 / 2 * 4 + nonFoodValue, valuation(small, CityFocus.NoFocus, 2f).plotValue(mixedTile, true))

        // An unhappy empire (needs model, CvCityCitizens::IsAvoidGrowth): approval 0 for 6 unhappy specialists and no happiness, so the city's delta is below -1
        val world = NeedsTestWorld()
        val large = NeedsAiScenarios.addEmpireCity(world, world.civ, 0, 6, population = 6)
        assertTrue(world.civ.isEmpireUnhappy())
        large.setCityFocus(CityFocus.NoFocus)
        val unhappy = CitizenAutomation.Valuation(large).also { it.excessFood = 0f }
        assertEquals(nonFoodValue, unhappy.plotValue(mixedTile, true))
        // Looking for the worst worked tile ignores the growth wish: food keeps its base value
        assertEquals(14 * 2 + nonFoodValue, unhappy.plotValue(mixedTile, false))
    }

    @Test
    fun `specialist value is its yields plus three per Great Person point`() {
        val city = city(HexCoord.Zero, Constants.desert, false, 6)
        val specialists = game.ruleset.specialists
        fun value(name: String, focus: CityFocus) =
            valuation(city, focus, 2f).specialistValue(specialists[name]!!, city.cityStats.getStatsOfSpecialist(name))
        assertEquals(3 * 15 + 3 * 3, value("Writer", CityFocus.NoFocus))
        assertEquals(3 * 7 + 9, value("Scientist", CityFocus.NoFocus))
        // Vox Populi's specialist yields: the Engineer makes 4 production (2 in Brave New World), the Merchant 5 gold (2)
        assertEquals(4 * 12 + 9, value("Engineer", CityFocus.NoFocus))
        assertEquals(5 * 8 + 9, value("Merchant", CityFocus.NoFocus))
        assertEquals(3 * 15 * 3 + 9, value("Writer", CityFocus.CultureFocus))
        assertEquals(4 * 12 * 3 + 9, value("Engineer", CityFocus.ProductionFocus))
    }

    @Test
    fun `half specialist unhappiness adds 5 to a specialist, 10 while the empire is unhappy`() {
        // The original's happiness model ([OriginalHappinessModel]); with the needs model the unhappiness a specialist adds is scored by its citizens (see below)
        OriginalHappinessModel.use(game.ruleset)
        val owner = game.addCiv("[+20 Happiness] [in all cities]", "[-50]% Unhappiness from [Specialists] [in all cities]")
        val city = city(HexCoord.Zero, Constants.desert, false, 6, owner)
        val writer = game.ruleset.specialists["Writer"]!!
        val stats = city.cityStats.getStatsOfSpecialist("Writer")
        assertEquals(54 + 5, valuation(city, CityFocus.NoFocus, 2f).specialistValue(writer, stats))
        assertEquals(54 + 10, valuation(city, CityFocus.NoFocus, 2f, happiness = -1).specialistValue(writer, stats))
    }

    @Test
    fun `citizens take food tiles until the surplus reaches 2, then culture specialists`() {
        val city = city(HexCoord.Zero, Constants.grassland, true, 6)
        city.cityConstructions.addBuilding("Writers' Guild")
        city.reassignPopulation()
        assertTrue(civ.getHappiness() >= 0)
        // Farms (3 food) are worth 3*14*8 = 336 while short of +2 food, then 21; a Writer is worth 54
        assertEquals(2, writers(city))
        assertEquals(4, city.workedTiles.size)
        assertTrue(city.cityStats.excessFood >= 2f)
    }

    @Test
    fun `losing a citizen frees the worst tile before any specialist`() {
        val city = city(HexCoord.Zero, Constants.grassland, true, 6)
        city.cityConstructions.addBuilding("Writers' Guild")
        city.reassignPopulation()
        city.population.addPopulation(-1)
        assertEquals(2, writers(city))
        assertEquals(3, city.workedTiles.size)
    }

    @Test
    fun `a building with more slots is staffed first when values are equal`() {
        val city = city(HexCoord.Zero, Constants.desert, false, 1)
        val artistHouse = game.createBuilding()
        artistHouse.specialistSlots.add("Artist", 1)
        city.cityConstructions.addBuilding(artistHouse) // first in the city, 1 slot
        city.cityConstructions.addBuilding("Writers' Guild") // 2 slots: 54 + 10%
        city.reassignPopulation()
        assertEquals(1, writers(city))
        assertEquals(0, city.population.specialistAllocations["Artist"])
    }

    @Test
    fun `a specialist without the focused yield loses to an unemployed citizen`() {
        val city = city(HexCoord.Zero, Constants.desert, false, 1)
        city.cityConstructions.addBuilding("Writers' Guild")
        city.setCityFocus(CityFocus.ProductionFocus)
        city.reassignPopulation()
        // A Writer yields no Production, an unemployed citizen 1: the Writer is worth more than a desert tile, so the citizen idles
        assertEquals(0, writers(city))
        assertEquals(0, city.workedTiles.size)
        assertEquals(1, city.population.getFreePopulation())
    }

    @Test
    fun `a money deficit takes gold focus, with growth while food is the most deficient yield`() {
        addCapital()
        val city = city(HexCoord(6, 0), Constants.grassland, true, 6)
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_LOSING_MONEY"] = 0
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.GoldFocus, city.getCityFocus())
        city.ai.mostDeficientYield = Stat.Production
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.GoldFocus, city.getCityFocus())
        city.ai.mostDeficientYield = Stat.Food
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.GoldGrowthFocus, city.getCityFocus())
    }

    @Test
    fun `a city of more than 3 citizens building a world wonder takes production focus`() {
        addCapital()
        val city = city(HexCoord(6, 0), Constants.grassland, true, 6)
        city.cityConstructions.setCurrentConstruction(game.createWonder().name)
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.ProductionFocus, city.getCityFocus())
        city.population.setPopulation(3)
        CitizenAutomation.doTurn(city)
        assertEquals("a city of 3 builds without the focus", CityFocus.NoFocus, city.getCityFocus())
    }

    @Test
    fun `the specialization's yield chooses the focus, food shortage adds growth, the default specialization none`() {
        addCapital()
        val city = city(HexCoord(6, 0), Constants.grassland, true, 6)
        city.ai.changeSpecialization("CITYSPECIALIZATION_PRODUCTION_WONDER")
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.ProductionFocus, city.getCityFocus())
        city.ai.mostDeficientYield = Stat.Food
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.ProductionGrowthFocus, city.getCityFocus())
        city.ai.defaultSpecialization = "CITYSPECIALIZATION_PRODUCTION_WONDER"
        CitizenAutomation.doTurn(city)
        assertEquals(CityFocus.NoFocus, city.getCityFocus())
    }

    @Test
    fun `a puppet we may annex takes no focus and may grow`() {
        addCapital()
        val puppet = city(HexCoord(6, 0), Constants.grassland, true, 6)
        puppet.isPuppet = true
        puppet.setCityFocus(CityFocus.GoldFocus)
        puppet.avoidGrowth = true
        CitizenAutomation.doTurn(puppet)
        assertEquals(CityFocus.NoFocus, puppet.getCityFocus())
        assertFalse(puppet.avoidGrowth)
    }

    //region The needs model (cityNeedsLikeVp): CvCityCitizens::IsAvoidGrowth, ScoreYieldChange

    @Test
    fun `a city avoids growth when the empire is unhappy and its delta is below minus one, and a very unhappy AI avoids growth everywhere`() {
        // Approval 40: happy 8 x 100 / unhappy 10 / 2. The city's delta is 8 - 10
        var world = NeedsTestWorld()
        var city = NeedsAiScenarios.addEmpireCity(world, world.civ, 8, 10)
        assertTrue(world.civ.isEmpireUnhappy())
        assertEquals(-2, world.needsOf(city).delta)
        assertTrue("unhappy and under -1", CitizenAutomation.isAvoidGrowth(city))
        // 4 against 5: the same approval, a delta of -1
        world = NeedsTestWorld()
        city = NeedsAiScenarios.addEmpireCity(world, world.civ, 4, 5)
        assertTrue(world.civ.isEmpireUnhappy())
        assertEquals(-1, world.needsOf(city).delta)
        assertFalse("unhappy, but the city is not that unhappy", CitizenAutomation.isAvoidGrowth(city))
        city.avoidGrowth = true
        assertTrue("the city's own flag", CitizenAutomation.isAvoidGrowth(city))

        // A very unhappy AI (approval 30) avoids it in every city, a human one only where the delta is under -1
        for (human in listOf(false, true)) {
            world = NeedsTestWorld()
            val owner = if (human) world.civ else world.game.addCiv()
            NeedsAiScenarios.addEmpireCity(world, owner, 3, 5)
            val happyCity = NeedsAiScenarios.addEmpireCity(world, owner, 0, 0)
            assertTrue(owner.isEmpireVeryUnhappy())
            assertEquals("a third of its 3 citizens are unhappy for the lack of a road", -1, world.needsOf(happyCity).delta)
            assertEquals("a city of a ${if (human) "human" else "AI"} empire that is very unhappy", !human, CitizenAutomation.isAvoidGrowth(happyCity))
        }
    }

    @Test
    fun `a happy empire's city does not avoid growth`() {
        val world = NeedsTestWorld()
        val city = NeedsAiScenarios.addEmpireCity(world, world.civ, 20, 10)
        assertFalse(world.civ.isEmpireUnhappy())
        assertFalse(CitizenAutomation.isAvoidGrowth(city))
    }

    /** What one more specialist in [city] changes in the value of a Writer: the score of the unhappiness it adds, against the same specialist in a city that has some for free */
    private fun specialistUnhappinessScore(world: NeedsTestWorld, city: City): Int {
        val writer = world.game.ruleset.specialists["Writer"]!!
        val stats = city.cityStats.getStatsOfSpecialist("Writer")
        val value = CitizenAutomation.Valuation(city).specialistValue(writer, stats)
        world.build(city, "The first [20] specialists cause no unhappiness [in this city]")
        return value - CitizenAutomation.Valuation(city).specialistValue(writer, stats)
    }

    @Test
    fun `a specialist that would add unhappiness in an empire close to unhappy scores minus 15000`() {
        // Approval 50 with 3 specialists (3 unhappy, 3 happy); a fourth makes 3 x 100 / 4 / 2 = 37, within 5 of the unhappy threshold
        val world = NeedsTestWorld()
        val city = NeedsAiScenarios.addEmpireCity(world, world.civ, 3, 3, population = 8)
        assertEquals(50, world.civ.getApproval())
        assertEquals(-15000, specialistUnhappinessScore(world, city))
    }

    @Test
    fun `a specialist that would make the empire very unhappy scores minus 50000, one in a happy empire and a happy city minus 2000`() {
        var world = NeedsTestWorld()
        var city = NeedsAiScenarios.addEmpireCity(world, world.civ, 2, 3, population = 8) // 33, a fourth: 25
        assertEquals(-50000, specialistUnhappinessScore(world, city))

        world = NeedsTestWorld()
        city = NeedsAiScenarios.addEmpireCity(world, world.civ, 20, 3, population = 8)
        assertEquals("20 happy against 4 unhappy: all is well", -2000, specialistUnhappinessScore(world, city))

        // The other city is happy for the empire, this one has more unhappy citizens than happiness
        world = NeedsTestWorld()
        city = NeedsAiScenarios.addEmpireCity(world, world.civ, 0, 3, population = 8)
        NeedsAiScenarios.addEmpireCity(world, world.civ, 40, 0)
        assertEquals(-5000, specialistUnhappinessScore(world, city))
    }

    @Test
    fun `a specialist that costs no unhappiness is not scored`() {
        val world = NeedsTestWorld("The first [5] specialists cause no unhappiness [in all cities]")
        val city = NeedsAiScenarios.addEmpireCity(world, world.civ, 3, 3, population = 8)
        assertEquals(0, specialistUnhappinessScore(world, city))
    }

    //endregion
}
