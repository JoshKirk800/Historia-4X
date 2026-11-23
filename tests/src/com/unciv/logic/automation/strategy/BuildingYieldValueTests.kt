package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.city.PurchaseRules
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What the yield shapes of Vox Populi's economy are worth to the AI: CityStrategyAIHelpers::GetBuildingYieldValue (V:CvCityStrategyAI.cpp:3407-4260,
 *  [BuildingYieldAi.yieldValue] with [BuildingInstantYieldAi]), CvBuildingProductionAI::CheckBuildingBuildSanity's investment term (V:CvBuildingProductionAI.cpp:828-835),
 *  CvUnitProductionAI::CheckUnitBuildSanity's purchase test. The city makes 10 of every yield and the civilization is in its first era, so an instant yield of n is worth
 *  max(n / 2, n / 10) and a flat yield of n is n x 100 / 10.
 */
@RunWith(GdxTestRunner::class)
class BuildingYieldValueTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        city = game.addCity(civ, game.tileMap[HexCoord(0, 0)], replacePalace = true, initialPopulation = 3)
        setRates()
    }

    /** The city makes 10 of every yield (what it makes is recomputed whenever a city or a building is added, so this comes last) */
    private fun setRates() {
        city.cityStats.currentCityStats.clear()
        for (stat in listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)) city.cityStats.currentCityStats[stat] = 10f
    }

    private fun value(stat: Stat, vararg uniques: String): Int =
        BuildingYieldAi.yieldValue(city, game.createBuilding(*uniques).apply { cost = 30 }, stat)

    //region Instant yields

    @Test
    fun `a construction yield is worth an instant yield of its amount`() {
        assertEquals(10, value(Stat.Gold, "Gain [20] [Gold] <upon constructing [Buildings]>"))
        assertEquals("another stat is not asked", 0, value(Stat.Science, "Gain [20] [Gold] <upon constructing [Buildings]>"))
        assertEquals("a technology likewise", 10, value(Stat.Gold, "Gain [20] [Gold] <upon discovering [All] technology>"))
    }

    @Test
    fun `a building's own instant yield is worth its amount`() {
        assertEquals(25, value(Stat.Gold, "Gain [50] [Gold]"))
    }

    @Test
    fun `a birth yield is worth the earlier the smaller the city, with its food and growth`() {
        city.cityStats.currentCityStats.food = 0f
        assertEquals("max(1, 50 - 3) + 3 + 0 food, divided by the rate 1", 50, value(Stat.Food, "Gain [3] [Food] <upon population growth>"))
        city.population.setPopulation(13)
        city.cityStats.currentCityStats.food = 0f
        assertEquals("50 - 13", 40, value(Stat.Food, "Gain [3] [Food] <upon population growth>"))
        city.population.setPopulation(60)
        city.cityStats.currentCityStats.food = 0f
        assertEquals("never less than 1: 1 + 3", 4, value(Stat.Food, "Gain [3] [Food] <upon population growth>"))
        city.population.setPopulation(13)
        city.cityStats.currentCityStats.food = 4f
        assertEquals("(37 + 3 + 4 food) over 2, not over the rate 4", 22, value(Stat.Food, "Gain [3] [Food] <upon population growth>"))
    }

    @Test
    fun `a border growth yield counts for border growth only`() {
        assertEquals(3, value(Stat.Gold, "Gain [6] [Gold] <upon acquiring a [All] tile through [border growth]>"))
        assertEquals(0, value(Stat.Gold, "Gain [6] [Gold] <upon acquiring a [All] tile through [purchase]>"))
    }

    @Test
    fun `a purchase yield is its percentage, for each city when it is the global table`() {
        val unique = "Gain [50]% of the triggering amount as [Gold] <upon buying something with [Gold]>"
        assertEquals("the city's table", 25, value(Stat.Gold, "$unique <in this city>"))
        assertEquals("the global table, in one city", 25, value(Stat.Gold, unique))
        game.addCity(civ, game.tileMap[HexCoord(4, 0)])
        setRates()
        assertEquals("the global table, in 2 cities: 100", 50, value(Stat.Gold, unique))
        assertEquals("a purchase with faith is the faith purchase yield: the same amount for a civilization that is not religious", 25,
            value(Stat.Gold, "Gain [50]% of the triggering amount as [Gold] <upon buying something with [Faith]>"))
        assertEquals("a purchase with another stat is none", 0, value(Stat.Gold, "Gain [50]% of the triggering amount as [Gold] <upon buying something with [Production]>"))
    }

    @Test
    fun `a pillage yield is its amount, ten times for the global table, a victory yield a fifth`() {
        assertEquals("1 of 2: max(1, 0)", 1, value(Stat.Gold, "Gain [2] [Gold] <upon pillaging a tile> <in this city>"))
        assertEquals("20, half of it", 10, value(Stat.Gold, "Gain [2] [Gold] <upon pillaging a tile>"))
        assertEquals("a fifth of 50", 5, value(Stat.Gold, "Gain [50] [Gold] <upon defeating a [Military] unit> <in this city>"))
        assertEquals(25, value(Stat.Gold, "Gain [50] [Gold] <upon defeating a [Military] unit>"))
    }

    @Test
    fun `a unit production yield counts for each domain this is the best military city of`() {
        val unique = "Gain [50]% of the triggering amount as [Gold] <upon completing a [All] unit>"
        city.cityConstructions.addBuilding(game.createBuilding("[+10 Production]"))
        city.cityStats.update()
        setRates()
        val domains = MilitaryCities.UnitDomain.entries.count { MilitaryCities.bestForDomain(civ, it) == city }
        assertEquals(domains * 50 / 2, value(Stat.Gold, unique))
        assertTrue("setup: the only city is the best of some domain", domains > 0)
    }

    @Test
    fun `a We Love The King Day yield is a modifier of a twentieth of the amount`() {
        // 400 / 20 = 20 percent points of the rate 10
        assertEquals(2, value(Stat.Gold, "Gain [400] [Gold] <upon We Love The King Day starting>"))
    }

    @Test
    fun `the start of a golden age is worth at least one flat yield`() {
        assertEquals("max(1, 3 x 5 x 0 / threshold) x 100 / 10", 10, value(Stat.Gold, "Gain [5] [Gold] <upon entering a Golden Age>"))
    }

    //endregion
    //region Per-turn shapes

    @Test
    fun `a process modifier is twice its percentage for the yield of the process the city runs`() {
        val unique = "[20]% yield from processes [in all cities]"
        assertEquals("no process", 0, value(Stat.Gold, unique))
        city.cityConstructions.editQueue { clear(); add(PerpetualConstruction.Gold.name) }
        assertEquals("40 percent points of 10", 4, value(Stat.Gold, unique))
        assertEquals("not for another yield", 0, value(Stat.Science, unique))
    }

    @Test
    fun `yield from yield is a flat yield of the percentage of the source yield`() {
        // 50% of the 20 production is 10 gold: a flat 10 over the rate 10 is 100
        city.cityStats.currentCityStats.production = 20f
        assertEquals(100, value(Stat.Gold, "[50]% of [Production] [in this city] added to [Gold]"))
        assertEquals(0, value(Stat.Science, "[50]% of [Production] [in this city] added to [Gold]"))
    }

    @Test
    fun `the capital's yield per citizens of the empire is a flat yield in the capital only`() {
        game.addCity(civ, game.tileMap[HexCoord(4, 0)], initialPopulation = 3)
        setRates()
        assertTrue(city.isCapital())
        assertEquals("6 citizens, one culture per 2: 3, times 100 over 10", 30, value(Stat.Culture, "Capital's [+1 Culture] per [2] citizens in the empire"))
        val other = civ.cities.first { it != city }
        assertEquals(0, BuildingYieldAi.yieldValue(other, game.createBuilding("Capital's [+1 Culture] per [2] citizens in the empire"), Stat.Culture))
    }

    //endregion
    //region Food in a small city, investments, cooldowns

    @Test
    fun `a building with a food yield is worth 10 times in a small city`() {
        city.cityStats.currentCityStats.food = 0f
        val granary = game.createBuilding().apply { cost = 30; food = 2f }
        val small = BuildingYieldAi.yieldTerms(city, granary).bonus
        city.population.setPopulation(12)
        val large = BuildingYieldAi.yieldTerms(city, granary).bonus
        assertTrue("setup: a granary is worth something", large > 0)
        assertEquals(10 * large, small)
    }

    @Test
    fun `a building that gold is invested in is worth 1000 more, not when the situation is ignored`() {
        game.ruleset.modOptions.constants.buildingInvestments = true
        // an army of more than 3 units a city: a tiny army takes 75 percent off the bonus
        for (i in 0 until 4) game.addUnit("Warrior", civ, game.tileMap[HexCoord(1 + i, 0)])
        val building = game.createBuilding().apply { cost = 100 }
        val plain = BuildingProductionAi.checkBuildingBuildSanity(city, building, 1000)
        val plainIgnoring = BuildingProductionAi.checkBuildingBuildSanity(city, building, 1000, ignoreSituational = true)
        city.cityConstructions.investedBuildings[building.name] = 20
        assertEquals(plain + 1000, BuildingProductionAi.checkBuildingBuildSanity(city, building, 1000))
        assertEquals(plainIgnoring, BuildingProductionAi.checkBuildingBuildSanity(city, building, 1000, ignoreSituational = true))
        game.ruleset.modOptions.constants.buildingInvestments = false
        assertEquals("without the constant nothing is invested", plain, BuildingProductionAi.checkBuildingBuildSanity(city, building, 1000))
    }

    @Test
    fun `a unit that is on cooldown is not bought`() {
        city.cityConstructions.addBuilding("Monument")
        civ.addGold(1000)
        val warrior = game.ruleset.units["Warrior"]!!
        assertTrue("setup: bought now", UnitProductionAi.checkUnitBuildSanity(city, warrior, 100, forOperation = false, forPurchase = true) > 0)
        game.ruleset.modOptions.constants.purchaseCooldownEnabled = true
        city.purchaseCooldowns.gold = 2
        assertEquals(ProductionSanityAi.SR_IMPOSSIBLE, UnitProductionAi.checkUnitBuildSanity(city, warrior, 100, forOperation = false, forPurchase = true))
        val worker = game.ruleset.units["Worker"]!!
        assertTrue("the military counter is not the civilian's", !PurchaseRules.isOnCooldown(city, worker, Stat.Gold))
        assertTrue(PurchaseRules.isOnCooldown(city, warrior, Stat.Gold))
        city.purchaseCooldowns.gold = 0
        assertTrue(UnitProductionAi.checkUnitBuildSanity(city, warrior, 100, forOperation = false, forPurchase = true) > 0)
    }

    //endregion
    //region Monopolies and trade routes (slice T6 of phaseE/vp-trade-plan.md)

    /** A luxury of which the only copy on the map is on a tile far away, and which the civilization owns through a building: a global monopoly */
    private fun hold(luxuryTile: HexCoord) {
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury; isMonopoly = true }
        game.tileMap[luxuryTile].setTileResource(luxury.name)
        city.cityConstructions.addBuilding(game.createBuilding("Provides [1] [${luxury.name}]"))
        civ.cache.updateCivResources()
    }

    @Test
    fun `a building with a yield for every monopoly is worth it for each global monopoly we hold`() {
        val unique = "[+1 Gold] [in this city] for every global monopoly"
        assertEquals("no monopoly held", 0, value(Stat.Gold, unique))
        hold(HexCoord(6, 0))
        hold(HexCoord(6, 1))
        setRates()
        assertEquals(2, civ.monopolies.globalMonopolyCount())
        // V:CvCityStrategyAI.cpp:3525-3527: 1 x 2 monopolies is a flat yield of 2 over the city's 10: 2 x 100 / 10
        assertEquals(20, value(Stat.Gold, unique))
        assertEquals("the same as 2 flat gold", value(Stat.Gold, "[+2 Gold]"), value(Stat.Gold, unique))
        assertEquals("another yield is not asked", 0, value(Stat.Science, unique))
    }

    @Test
    fun `the yield of a passing trade route counts only while a route passes the city`() {
        val unique = "[+4 Gold] while any trade route passes through this city"
        assertEquals("no route passes", 0, value(Stat.Gold, unique))
        val far = game.addCity(game.addCiv(), game.tileMap[HexCoord(0, 6)])
        val route = com.unciv.models.TradeRoute(far.location, city.location, com.unciv.models.TradeRouteKind.International, false, "Caravan",
            (6 downTo 0).map { game.tileMap[HexCoord(0, it)].position }, 2, 30, 0)
        route.setTransients(far.civ)
        far.civ.tradeRoutes.routes.add(route)
        far.civ.tradeRoutes.updateYields()
        setRates()
        // V:CvCityStrategyAI.cpp:3505-3508: the 4 over the city's 10
        assertEquals(40, value(Stat.Gold, unique))
    }

    @Test
    fun `a yield when a route ends is an instant yield of the city's table only`() {
        // V:CvCityStrategyAI.cpp:3826-3832: the building's own table, an instant yield of 20 is worth max(20 / 2, 20 / 10)
        assertEquals(10, value(Stat.Gold, "Gain [20] [Gold] <upon completing a [international] trade route> <in this city>"))
        assertEquals(10, value(Stat.Gold, "Gain [20] [Gold] <upon completing a [domestic] trade route> <in this city>"))
        assertEquals("the table of the whole civilization is not read", 0, value(Stat.Gold, "Gain [20] [Gold] <upon completing a [international] trade route>"))
    }

    //endregion
}
