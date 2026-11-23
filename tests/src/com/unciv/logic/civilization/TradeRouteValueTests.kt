package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.TradeRouteValue
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's trade route value (slice T3 of `phaseE/vp-trade-plan.md`): CvPlayerTrade::GetTradeConnectionValueTimes100 and the functions it sums
 * (V:CvTradeClasses.cpp:2747-4247), the route events (2548-2650, 2085-2150, 4965-5045), the per-city and per-tile effects of routes (V:CvCity.cpp:23328, 22495,
 * V:CvPlot.cpp:10716-10760) behind `ModConstants.tradeRouteModelLikeVp`. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 *
 * Every expected number is worked out by hand from the cited lines, in hundredths of a yield (VP's `Times100`). The test world has no city gold (a capital whose palace
 * is replaced, grassland around it), flat land (a land step costs 101: V:CvAStar's 100 + 1 for terrain that is not an oasis) and the Gods & Kings eras (0 to 8, whose number is
 * `GetCurrentEra`).
 */
@RunWith(GdxTestRunner::class)
class TradeRouteValueTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var caravan: BaseUnit
    private lateinit var cargoShip: BaseUnit

    private val landUnique = "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn"
    private val seaUnique = "Can establish trade routes with a base range of [20] tiles, travelling [4] tiles per turn"

    /** The ruleset's eras and speeds are shared by every test of the run: what a test changes is put back */
    private val restore = ArrayList<() -> Unit>()

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(16)
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = true
        civA = game.addCiv()
        civB = game.addCiv()
        meet(civA, civB)
        caravan = game.createBaseUnit("Civilian", landUnique)
        cargoShip = game.createBaseUnit("Civilian Water", seaUnique)
        for (tile in game.tileMap.values) {
            tile.setExplored(civA, true)
            tile.setExplored(civB, true)
        }
    }

    @After
    fun tearDown() {
        restore.forEach { it() }
    }

    private fun meet(a: Civilization, b: Civilization) {
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
    }

    private fun explore(civ: Civilization) { for (tile in game.tileMap.values) tile.setExplored(civ, true) }

    private fun cityAt(civ: Civilization, y: Int, population: Int = 1) = game.addCity(civ, game.tileMap[0, y], replacePalace = true, initialPopulation = population)

    private fun twoCities(distance: Int): Pair<City, City> = cityAt(civA, 0) to cityAt(civB, distance)

    private fun establish(origin: City, destination: City, unit: BaseUnit = caravan, kind: TradeRouteKind = TradeRouteKind.International): TradeRoute? {
        val mapUnit = game.addUnit(unit.name, origin.civ, origin.getCenterTile())
        val route = origin.civ.tradeRoutes.establish(mapUnit, destination, kind)
        if (route == null) mapUnit.destroy()
        return route
    }

    private fun setEra(civ: Civilization, number: Int) { civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == number } }

    private fun City.build(vararg uniques: String) = game.createBuilding(*uniques).also { cityConstructions.addBuilding(it); cityStats.update() }

    private fun City.addGold(gold: Float) {
        cityConstructions.addBuilding(game.createBuilding().also { it.gold = gold })
        cityStats.update()
    }

    private fun City.stats() = cityStats.currentCityStats.clone()

    private fun adopt(civ: Civilization, vararg uniques: String) = civ.policies.adopt(game.createPolicy(*uniques), true)

    private fun makeCoast() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.coast)
        game.setTileTerrain(game.tileMap[0, 0].position, Constants.desert)
    }

    /** Sets what [change] puts into an era or speed of the shared ruleset back when the test is over */
    private fun <T> temporarily(target: T, change: (T) -> Unit, undo: (T) -> Unit) {
        change(target)
        restore.add { undo(target) }
    }

    //region International gold: V:CvTradeClasses.cpp:3630-3720

    @Test
    fun `international land route gold at era 0 is 1_6 with no city gold and 7_2 at era 7`() {
        val (origin, destination) = twoCities(5)
        assertEquals("the test world has no city gold", 0f, origin.stats().gold, 0f)
        val route = establish(origin, destination)!!
        // GetTradeConnectionBaseValueTimes100: INTERNATIONAL_TRADE_BASE 80 x (era 0 + 2) = 160, modifier 100 (land 0), at least 100
        assertEquals(1.6f, route.originYields.gold, 0.001f)
        // 80 x (7 + 2) = 720
        setEra(civA, 7)
        civA.tradeRoutes.updateYields()
        assertEquals(7.2f, route.originYields.gold, 0.001f)
    }

    @Test
    fun `the destination of a route has no base gold, only what its buildings bring`() {
        val (origin, destination) = twoCities(5)
        val bare = establish(origin, destination)!!
        // GetTradeConnectionBaseValueTimes100 with bAsOriginPlayer false: only science and culture have a base
        assertEquals(0f, bare.destinationYields.gold, 0.001f)
        civA.tradeRoutes.routes.clear()
        civA.tradeRoutes.updateYields()
        // GetTradeConnectionTheirBuildingValueTimes100: the recipient bonus of the destination city x 100
        destination.build("[+2] Gold from incoming [International] trade routes <in this city>")
        val route = establish(origin, destination)!!
        assertEquals(2f, route.destinationYields.gold, 0.001f)
    }

    @Test
    fun `city gold per turn adds gold divided by 80 for each end`() {
        val (origin, destination) = twoCities(5)
        origin.addGold(40f)
        destination.addGold(80f)
        val route = establish(origin, destination)!!
        // GetTradeConnectionGPTValueTimes100: getYieldRateTimes100 / INTERNATIONAL_TRADE_CITY_GPT_DIVISOR 80: 4000 / 80 = 50 and 8000 / 80 = 100; 160 + 50 + 100 = 310
        assertEquals(3.1f, route.originYields.gold, 0.001f)
    }

    @Test
    fun `buildings of both ends add to the value before the modifiers`() {
        val (origin, destination) = twoCities(5)
        origin.build("[+2] Gold on [Land] trade routes <in this city>")
        destination.build("[+1] Gold on incoming [International] trade routes for their origin <in this city>")
        val route = establish(origin, destination)!!
        // 160 + 200 (GetTradeConnectionYourBuildingValueTimes100 and the policy bonus) + 100 (GetTradeConnectionTheirBuildingValueTimes100, the target bonus x 100)
        assertEquals(4.6f, route.originYields.gold, 0.001f)
    }

    @Test
    fun `a route by sea adds 25 percent, a route by land nothing`() {
        makeCoast()
        game.setTileTerrain(game.tileMap[0, 8].position, Constants.desert)
        val origin = cityAt(civA, 0)
        val destination = cityAt(civB, 8)
        // each city works a coast tile, which gives 1 gold
        origin.cityStats.update()
        destination.cityStats.update()
        assertEquals(1f, origin.stats().gold, 0f)
        assertEquals(1f, destination.stats().gold, 0f)
        val route = establish(origin, destination, cargoShip)!!
        assertTrue(route.isSea)
        // 160 + 100 / 80 + 100 / 80 = 162; GetDomainModifierTimes100: TRADE_ROUTE_BASE_SEA_MODIFIER 25: 162 x 125 / 100 = 202
        assertEquals(2.02f, route.originYields.gold, 0.001f)
    }

    @Test
    fun `a river city adds 25 percent to the gold of a land route for its own end`() {
        val (origin, destination) = twoCities(5)
        destination.build("[+2] Gold from incoming [International] trade routes <in this city>")
        origin.getCenterTile().hasBottomRiver = true
        origin.getCenterTile().forgetAdjacentToRiver()
        origin.cityStats.update()
        assertEquals("a river gives its city 1 gold", 1f, origin.stats().gold, 0f)
        val route = establish(origin, destination)!!
        // GetTradeConnectionRiverValueModifierTimes100, TRADE_ROUTE_RIVER_CITY_MODIFIER 25: origin (160 + 100 / 80) x 125 / 100 = 201, the destination (no river) 200 x 100 / 100
        assertEquals(2.01f, route.originYields.gold, 0.001f)
        assertEquals(2f, route.destinationYields.gold, 0.001f)

        destination.getCenterTile().hasBottomRiver = true
        destination.getCenterTile().forgetAdjacentToRiver()
        destination.cityStats.update()
        civA.tradeRoutes.updateYields()
        // the destination's river: 200 x 125 / 100 = 250; the origin's value has 1 gold more of the destination's: (160 + 1 + 1) x 125 / 100 = 202
        assertEquals(2.02f, route.originYields.gold, 0.001f)
        assertEquals(2.5f, route.destinationYields.gold, 0.001f)
    }

    @Test
    fun `open borders add 20 percent to the origin when both civilizations have them and 10 when one has, nothing to the destination`() {
        val (origin, destination) = twoCities(5)
        destination.build("[+2] Gold from incoming [International] trade routes <in this city>")
        val route = establish(origin, destination)!!
        assertEquals(1.6f, route.originYields.gold, 0.001f)

        civA.getDiplomacyManager(civB)!!.hasOpenBorders = true
        civA.tradeRoutes.updateYields()
        // GetTradeConnectionOpenBordersModifierTimes100: OPEN_BORDERS_MODIFIER_TRADE_GOLD 20 / 2 = 10 for one side: 160 x 110 / 100 = 176
        assertEquals(1.76f, route.originYields.gold, 0.001f)

        civB.getDiplomacyManager(civA)!!.hasOpenBorders = true
        civA.tradeRoutes.updateYields()
        // both: 160 x 120 / 100 = 192
        assertEquals(1.92f, route.originYields.gold, 0.001f)
        // the destination's modifiers (V:CvTradeClasses.cpp:4057-4062) do not include the open borders
        assertEquals(2f, route.destinationYields.gold, 0.001f)
    }

    @Test
    fun `a route is worth at least 1 gold to its origin`() {
        // a route to a city 5 tiles away where 10 is the longest: proximity takes 50 percent: 160 x 50 / 100 = 80, at least 100
        val origin = cityAt(civA, 0)
        val near = cityAt(civB, 5)
        cityAt(civB, 10)
        val route = establish(origin, near)!!
        assertEquals(1f, route.originYields.gold, 0.001f)
    }

    //endregion
    //region Resource diversity: V:CvTradeClasses.cpp:2958-3037

    /** Puts a resource on a tile next to [city], improved and owned by it, so that the city makes it; a new resource unless [resource] is given */
    private fun luxuryOnTile(city: City, y: Int, type: ResourceType = ResourceType.Luxury, resource: TileResource? = null): TileResource {
        val improvement = game.createTileImprovement().apply { terrainsCanBeBuiltOn = listOf(Constants.desert) }
        val made = resource ?: game.createResource().apply { resourceType = type; this.improvement = improvement.name }.also { it.setTransients(game.ruleset) }
        val tile = game.tileMap[1, y]
        tile.setTileResource(made.name)
        if (made.resourceType == ResourceType.Strategic) tile.resourceAmount = 2
        game.addTileToCity(city, tile)
        tile.setOwningCity(city)
        tile.setImprovement(made.improvement ?: improvement.name)
        assertTrue("the city makes ${made.name}", city.getResourcesGeneratedByCity().any { it.resource.name == made.name && it.amount > 0 })
        return made
    }

    @Test
    fun `a luxury only one city has adds 5 percent to the modifier, a strategic or bonus resource too`() {
        val (origin, destination) = twoCities(5)
        luxuryOnTile(origin, 0)
        val route = establish(origin, destination)!!
        // MOD_BALANCE_RESOURCE_MONOPOLIES: GetTradeConnectionResourceValueTimes100 is a modifier: TRADE_ROUTE_DIFFERENT_RESOURCE_VALUE 5, x (100 + 0) / 100:
        // 160 x 105 / 100 = 168
        assertEquals(1.68f, route.originYields.gold, 0.001f)
        luxuryOnTile(origin, 1, ResourceType.Strategic)
        luxuryOnTile(origin, -1, ResourceType.Bonus)
        civA.tradeRoutes.updateYields()
        // three resources: 160 x 115 / 100 = 184
        assertEquals(1.84f, route.originYields.gold, 0.001f)
    }

    @Test
    fun `a resource both cities have counts for nothing`() {
        val (origin, destination) = twoCities(5)
        val shared = luxuryOnTile(origin, 0)
        luxuryOnTile(destination, 5, resource = shared)
        val route = establish(origin, destination)!!
        // GetTradeConnectionResourceValueTimes100: IsHasResourceLocal is the same for both cities: no modifier, 160
        assertEquals(1.6f, route.originYields.gold, 0.001f)
        luxuryOnTile(destination, 6) // a luxury that only the destination has counts as much as one that only the origin has
        civA.tradeRoutes.updateYields()
        assertEquals(1.68f, route.originYields.gold, 0.001f)
    }

    @Test
    fun `a global monopoly of either owner doubles the value of that resource, and the origin's percentage scales it`() {
        val rich = game.addCiv("[+100]% Gold from resource diversity on trade routes")
        val origin = cityAt(rich, -8)
        val destination = cityAt(civB, -12)
        luxuryOnTile(origin, -8)
        // V:CvTradeClasses.cpp:3010-3032: (2 x 5 with a monopoly) x (100 + 100 trait) / 100 = 20
        assertEquals(20, TradeRouteValue.resourceModifier(origin, destination) { civ, _ -> civ == civB })
        assertEquals(20, TradeRouteValue.resourceModifier(origin, destination) { civ, _ -> civ == rich })
        // without one: 5 x 200 / 100 = 10
        assertEquals(10, TradeRouteValue.resourceModifier(origin, destination) { _, _ -> false })
    }

    //endregion
    //region Proximity: V:CvTradeClasses.cpp:3372-3418

    @Test
    fun `proximity takes the share a route is shorter than the longest, at most 50 percent, never adds`() {
        assertEquals("half as long", 50, TradeRouteValue.proximityReduction(505, 1010, 50))
        assertEquals("the longest", 0, TradeRouteValue.proximityReduction(1010, 1010, 50))
        assertEquals("9 tenths: 100 - 90000 / 1010 (89)", 11, TradeRouteValue.proximityReduction(900, 1010, 50))
        assertEquals("a fifth would be 80, cut to 50", 50, TradeRouteValue.proximityReduction(202, 1010, 50))
        assertEquals("longer than the longest: not a bonus", 0, TradeRouteValue.proximityReduction(1100, 1010, 50))
        assertEquals("no longest route known", 0, TradeRouteValue.proximityReduction(505, 0, 50))
    }

    private fun threeCities(): Triple<City, City, City> {
        val origin = cityAt(civA, 0)
        return Triple(origin, cityAt(civB, 5), cityAt(civB, 10))
    }

    @Test
    fun `a route half as long as the longest gives half the gold, the longest gives all`() {
        val (origin, near, far) = threeCities()
        setEra(civA, 7)
        // 5 steps cost 505, 10 cost 1010: the reduction is 100 - 505 x 100 / 1010 = 50, so 720 x 50 / 100 = 360
        val nearRoute = establish(origin, near)!!
        assertEquals(3.6f, nearRoute.originYields.gold, 0.001f)
        val farRoute = establish(origin, far)!!
        assertEquals(7.2f, farRoute.originYields.gold, 0.001f)
    }

    @Test
    fun `the Moroccan trait of either civilization takes the proximity away`() {
        val moroccan = game.addCiv("No proximity penalty on trade routes")
        meet(civA, moroccan)
        explore(moroccan)
        setEra(civA, 7)
        val origin = cityAt(civA, 0)
        val near = cityAt(moroccan, 5)
        cityAt(civB, 10)
        // the destination city's owner has the trait: V:CvTradeClasses.cpp:3390
        assertEquals(7.2f, establish(origin, near)!!.originYields.gold, 0.001f)

        val other = game.addCiv("No proximity penalty on trade routes")
        meet(other, civB)
        explore(other)
        setEra(other, 7)
        val ownOrigin = cityAt(other, -6)
        val ownNear = cityAt(civB, -11)
        cityAt(civB, -16)
        // the player's own trait: V:CvTradeClasses.cpp:3375
        assertEquals(7.2f, establish(ownOrigin, ownNear)!!.originYields.gold, 0.001f)
    }

    @Test
    fun `proximity does not change science or culture`() {
        val (origin, near, _) = threeCities()
        val techs = game.ruleset.technologies.keys.filter { it !in civB.tech.techsResearched }.take(9)
        for (tech in techs) civB.tech.addTechnology(tech)
        setEra(civA, 3)
        val route = establish(origin, near)!!
        assertEquals("the gold has the proximity: 400 x 50 / 100 = 200", 2f, route.originYields.gold, 0.001f)
        // 9 techs: int(sqrt(9) x 200 / 125) = 4, x era 3 = 12, in hundredths 1200 x 100 / 100: no distance for science (V:CvTradeClasses.cpp:3752)
        assertEquals(12f, route.originYields.science, 0.001f)
    }

    //endregion
    //region Science and culture: V:CvTradeClasses.cpp:2763-2900

    @Test
    fun `the difference scales by the square root, at least 1`() {
        assertEquals(0, TradeRouteValue.scaledDifference(0, 125))
        assertEquals("sqrt(1) x 1.6 = 1.6, cut to 1", 1, TradeRouteValue.scaledDifference(1, 125))
        assertEquals("sqrt(4) x 1.6 = 3.2", 3, TradeRouteValue.scaledDifference(4, 125))
        assertEquals("sqrt(9) x 1.6 = 4.8", 4, TradeRouteValue.scaledDifference(9, 125))
        assertEquals("sqrt(100) x 1.6", 16, TradeRouteValue.scaledDifference(100, 125))
    }

    @Test
    fun `science is the scaled number of techs the other knows and we lack times the era, and the destination gets it without the era`() {
        val (origin, destination) = twoCities(5)
        val techs = game.ruleset.technologies.keys.filter { it !in civB.tech.techsResearched }
        for (tech in techs.take(9)) civB.tech.addTechnology(tech)
        for (tech in techs.drop(9).take(4)) civA.tech.addTechnology(tech)
        setEra(civA, 3)
        val route = establish(origin, destination)!!
        // origin: they know 9 we lack: int(3 x 200 / 125) = 4, x our era 3 = 12 (V:CvTradeClasses.cpp:2775-2788)
        assertEquals(12f, route.originYields.science, 0.001f)
        // destination: we know 4 they lack: int(2 x 200 / 125) = 3, without the era (2846-2866)
        assertEquals(3f, route.destinationYields.science, 0.001f)
    }

    @Test
    fun `science of the first era is not multiplied`() {
        val (origin, destination) = twoCities(5)
        val techs = game.ruleset.technologies.keys.filter { it !in civB.tech.techsResearched }
        for (tech in techs.take(9)) civB.tech.addTechnology(tech)
        setEra(civA, 0)
        // V:CvTradeClasses.cpp:2781: only if the era is above 0
        assertEquals(4f, establish(origin, destination)!!.originYields.science, 0.001f)
    }

    @Test
    fun `culture is the same with the difference in policies`() {
        val (origin, destination) = twoCities(5)
        repeat(4) { adopt(civB, "[+0 Gold]") }
        setEra(civA, 3)
        val route = establish(origin, destination)!!
        // GetPolicyDifference: 4 more than ours (GetNumPoliciesOwned, no finishers): int(2 x 200 / 125) = 3, x era 3 = 9; the destination has none to gain
        assertEquals(9f, route.originYields.culture, 0.001f)
        assertEquals(0f, route.destinationYields.culture, 0.001f)
    }

    @Test
    fun `an ally city-state pays 2 gold and a friend 1 gold for each era, the ally one science`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        meet(civA, cityState)
        explore(civA)
        val origin = cityAt(civA, 0)
        val destination = cityAt(cityState, 5)
        setEra(civA, 3)
        cityState.getDiplomacyManager(civA)!!.setInfluence(80f)
        cityState.cityStateFunctions.updateAllyCivForCityState()
        assertEquals(civA, cityState.allyCiv)
        val ally = establish(origin, destination)!!
        // 80 x (3 + 2) = 400, + TRADE_ROUTE_CS_ALLY_GOLD 2 x 100 x era 3 = 600 (GetMinorCivGoldBonus): 1000
        assertEquals(10f, ally.originYields.gold, 0.001f)
        // GetTechDifference: TRADE_ROUTE_CS_ALLY_SCIENCE_DELTA 1: int(1 x 1.6) = 1, x era 3, no policy bump for a city-state
        assertEquals(3f, ally.originYields.science, 0.001f)
        assertEquals("a city-state gets nothing", 0f, ally.destinationYields.gold, 0f)

        civA.tradeRoutes.routes.clear()
        cityState.getDiplomacyManager(civA)!!.setInfluence(35f)
        cityState.cityStateFunctions.updateAllyCivForCityState()
        val friend = establish(origin, destination)!!
        // 400 + 1 x 100 x 3 = 700, and no science (TRADE_ROUTE_CS_FRIEND_SCIENCE_DELTA 0)
        assertEquals(7f, friend.originYields.gold, 0.001f)
        assertEquals(0f, friend.originYields.science, 0.001f)
    }

    //endregion
    //region Domestic routes: V:CvTradeClasses.cpp:4109-4235

    private fun withEraBonus(number: Int, food: Int, production: Int) {
        val era = game.ruleset.eras.values.first { it.eraNumber == number }
        val before = era.tradeRouteFoodBonus to era.tradeRouteProductionBonus
        temporarily(era, { it.tradeRouteFoodBonus = food; it.tradeRouteProductionBonus = production },
            { it.tradeRouteFoodBonus = before.first; it.tradeRouteProductionBonus = before.second })
    }

    @Test
    fun `a domestic food route at era 3 is the base and the era's bonus times the growth percent, a larger origin adds a third of the difference, and the tech share`() {
        withEraBonus(3, 400, 400)
        val origin = cityAt(civA, 0, population = 4)
        val destination = cityAt(civA, 4, population = 1)
        origin.build("Allows [Food] trade routes between your cities")
        for (tech in game.ruleset.technologies.keys.filter { it !in civA.tech.techsResearched }.take(39)) civA.tech.addTechnology(tech)
        setEra(civA, 3)
        val route = establish(origin, destination, kind = TradeRouteKind.Food)!!
        // TRADE_ROUTE_BASE_FOOD_VALUE 600 + Eras.TradeRouteFoodBonusTimes100 of era 3 (400) = 1000, x growth percent 100 / 100;
        // origin pop 4 above 1: max((4 - 1) / 3 x 100, 100) = 100: 1100; techs known x 100 / number of techs / 2 = the share, x (share + 100) / 100
        val known = civA.tech.techsResearched.size
        val total = game.ruleset.technologies.size
        val expected = 1100 * (known * 100 / total / 2 + 100) / 100
        assertTrue("some techs count", known * 100 / total / 2 > 0)
        assertEquals(expected / 100f, route.destinationYields.food, 0.001f)
        assertEquals("the origin gets nothing", 0f, route.originYields.food, 0f)
    }

    @Test
    fun `a domestic production route has no tech share, a building of the origin adds before the percentage`() {
        withEraBonus(3, 400, 400)
        val origin = cityAt(civA, 0, population = 4)
        val destination = cityAt(civA, 4, population = 1)
        origin.build("Allows [Production] trade routes between your cities")
        setEra(civA, 3)
        val first = establish(origin, destination, kind = TradeRouteKind.Production)!!
        // 600 + 400 = 1000, x (construct + train) / 2 = 100 percent, + 100 for the larger origin: 1100
        assertEquals(11f, first.destinationYields.production, 0.001f)

        origin.build("[+2 Production] on domestic trade routes that start in this city")
        civA.tradeRoutes.updateYields()
        // GetYieldFromInternalTR x 100: 1300
        assertEquals(13f, first.destinationYields.production, 0.001f)

        adopt(civA, "[+50]% yields from trade routes within your civilization")
        civA.tradeRoutes.updateYields()
        // x (100 + 50) / 100 = 1950
        assertEquals(19.5f, first.destinationYields.production, 0.001f)
    }

    @Test
    fun `a building of another city does not add to the routes of this one`() {
        withEraBonus(3, 400, 400)
        val origin = cityAt(civA, 0, population = 1)
        val second = cityAt(civA, 8, population = 1)
        val destination = cityAt(civA, 4, population = 1)
        origin.build("Allows [Production] trade routes between your cities")
        second.build("[+2 Production] on domestic trade routes that start in this city")
        setEra(civA, 3)
        // equal populations: 1000, and the other city's building is nothing to this route
        assertEquals(10f, establish(origin, destination, kind = TradeRouteKind.Production)!!.destinationYields.production, 0.001f)
    }

    @Test
    fun `Communalism gives 3 food on domestic routes for each era`() {
        val origin = cityAt(civA, 0)
        val destination = cityAt(civA, 4)
        origin.build("Allows [Food] trade routes between your cities")
        adopt(civA, "[+3 Food] on [Domestic] trade routes <for every [Era scaler]>")
        setEra(civA, 2)
        val route = establish(origin, destination, kind = TradeRouteKind.Food)!!
        // 600 + 3 x 100 x era 2 = 1200 (GetTradeConnectionPolicyValueTimes100), no era bonus in the test ruleset, equal populations, the first tech's share is 0
        assertEquals(12f, route.destinationYields.food, 0.001f)
    }

    //endregion
    //region Policies and traits that modify international yields

    @Test
    fun `a percentage of international yields changes gold, science and culture of the origin`() {
        val (origin, destination) = twoCities(5)
        adopt(civA, "[+25]% yields from international trade routes")
        // POLICYMOD_TRADE_MODIFIER 25: 160 x 125 / 100 = 200
        assertEquals(2f, establish(origin, destination)!!.originYields.gold, 0.001f)
    }

    @Test
    fun `a bonus on trade routes of the domain adds to the gold before the percentages`() {
        val (origin, destination) = twoCities(5)
        adopt(civA, "[+3] Gold on [Land] trade routes")
        // GetTradeConnectionPolicyValueTimes100: LandTradeRouteGoldChange x 1 (hundredths): 160 + 300 = 460
        assertEquals(4.6f, establish(origin, destination)!!.originYields.gold, 0.001f)
    }

    @Test
    fun `a bonus on a kind of route of the era adds to the gold of the origin`() {
        val (origin, destination) = twoCities(5)
        adopt(civA, "[+2 Gold] on [International] trade routes <for every [Era scaler]>")
        setEra(civA, 3)
        // 80 x 5 = 400 + 2 x 100 x era 3 = 600: 1000
        assertEquals(10f, establish(origin, destination)!!.originYields.gold, 0.001f)
    }

    //endregion
    //region Effects on cities and tiles

    @Test
    fun `Gumey gives its yields while any trade route's path crosses the city, own or foreign`() {
        val (origin, destination) = twoCities(8)
        val route = establish(origin, destination)!!
        val foreignOwner = game.addCiv()
        val own = game.addCity(civA, game.getTile(route.path[2]))
        val foreign = game.addCity(foreignOwner, game.getTile(route.path[5]))
        for (city in listOf(own, foreign)) {
            val before = city.stats()
            city.build("[+2 Production, +2 Gold, +2 Culture] while any trade route passes through this city")
            val after = city.stats()
            assertEquals(2f, after.production - before.production, 0.001f)
            assertEquals(2f, after.gold - before.gold, 0.001f)
            assertEquals(2f, after.culture - before.culture, 0.001f)
        }
        // a city off the route has nothing, and when the route is over nobody has anything
        val off = game.addCity(game.addCiv(), game.tileMap[3, 0])
        val offBefore = off.stats()
        off.build("[+2 Production, +2 Gold, +2 Culture] while any trade route passes through this city")
        assertEquals(offBefore.production, off.stats().production, 0.001f)

        val withRoute = own.stats()
        civA.tradeRoutes.routes.clear()
        civA.tradeRoutes.updateYields()
        own.cityStats.update()
        assertEquals(2f, withRoute.production - own.stats().production, 0.001f)
    }

    @Test
    fun `a city with several routes gets its yield for each of them, the ones that end in it too, and 2 more for a connection to the capital`() {
        val rich = game.addCiv("[+4] Trade Route slots")
        meet(rich, civB)
        explore(rich)
        explore(civB)
        rich.tech.addTechnology(game.ruleset.tileImprovements[RoadStatus.Road.name]?.techRequired!!)
        val capital = cityAt(rich, -6)
        val connected = cityAt(rich, -9)
        for (y in -9..-6) game.tileMap[0, y].roadStatus = RoadStatus.Road
        rich.cache.updateCitiesConnectedToCapital()
        assertTrue(connected.isConnectedToCapital())
        val east = cityAt(civB, -2)
        val west = cityAt(civB, -12)
        assertNotNull(establish(capital, east))
        assertNotNull(establish(capital, west))
        assertNotNull(establish(west, capital)) // a route of civB that ends here: V:CvTradeClasses.cpp:4729 counts the routes to and from the city

        capital.cityStats.update()
        connected.cityStats.update()
        val before = capital.stats().gold
        val connectedBefore = connected.stats().gold
        capital.build("[+2 Gold] for each trade route that starts in this city", "[+2 Gold] from each Trade Route")
        connected.cityStats.update()
        // 3 routes x 2 = 6 for the capital, which is not connected to itself; 0 routes and 2 for the connection for the other city
        assertEquals(6f, capital.stats().gold - before, 0.001f)
        assertEquals(2f, connected.stats().gold - connectedBefore, 0.001f)
    }

    @Test
    fun `an improvement gives yields on a road a route crosses, more on a railroad from era 4`() {
        val (origin, destination) = twoCities(8)
        val route = establish(origin, destination)!!
        val village = game.createTileImprovement(
            "[+1 Gold, +1 Production] if a trade route crosses this tile on a [Road]",
            "[+2 Gold, +2 Production] if a trade route crosses this tile on a [Railroad]")
        village.terrainsCanBeBuiltOn = listOf(Constants.desert)
        val owner = game.addCiv()
        val city = game.addCity(owner, game.tileMap[6, 6])
        val onRoute = game.getTile(route.path[4])
        val offRoute = game.tileMap[3, 0].takeIf { it !in route.getTiles() } ?: game.tileMap[4, 0]
        for (tile in listOf(onRoute, offRoute)) {
            game.addTileToCity(city, tile)
            tile.setOwningCity(city)
            tile.setImprovement(village.name)
        }
        setEra(owner, 3)
        // V:CvPlot.cpp:10733-10742: before era 4 the road's row
        assertEquals(1f, onRoute.stats.getTileStats(city, owner).gold, 0.001f)
        assertEquals(1f, onRoute.stats.getTileStats(city, owner).production, 0.001f)
        assertEquals("no route, no yield", 0f, offRoute.stats.getTileStats(city, owner).gold, 0.001f)
        setEra(owner, 4)
        assertEquals(2f, onRoute.stats.getTileStats(city, owner).gold, 0.001f)
        assertEquals(2f, onRoute.stats.getTileStats(city, owner).production, 0.001f)
        civA.tradeRoutes.routes.clear()
        civA.tradeRoutes.updateYields()
        assertEquals("the route is gone", 0f, onRoute.stats.getTileStats(city, owner).gold, 0.001f)
    }

    @Test
    fun `the condition on tiles of trade routes holds for the tiles a route crosses`() {
        val (origin, destination) = twoCities(8)
        val route = establish(origin, destination)!!
        val owner = game.addCiv()
        adopt(owner, "[+1 Gold] from [Grassland] tiles [in all cities] <on tiles of trade routes and their cities>")
        val city = game.addCity(owner, game.tileMap[6, 6])
        val onRoute = game.getTile(route.path[4])
        val offRoute = game.tileMap[5, 6]
        for (tile in listOf(onRoute, offRoute)) { game.addTileToCity(city, tile); tile.setOwningCity(city) }
        assertEquals(1f, onRoute.stats.getTileStats(city, owner).gold, 0.001f)
        assertEquals(0f, offRoute.stats.getTileStats(city, owner).gold, 0.001f)
    }

    //endregion
    //region Route events

    @Test
    fun `completing a route fires the yields of its kind for the origin, and for a domestic route the destination too`() {
        adopt(civA, "Gain [75] [Gold] <upon completing a [international] trade route>",
            "Gain [150] [Food] <upon completing a [domestic] trade route>", "Gain [3] [Gold] <upon completing a [any] trade route>")
        val (origin, destination) = twoCities(5)
        establish(origin, destination)
        assertEquals(0, civA.gold)
        var turns = 0
        while (civA.tradeRoutes.routes.isNotEmpty() && turns++ < 100) civA.tradeRoutes.startTurn()
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals("an international route: 75 and the 3 of any kind", 78, civA.gold)

        val home = cityAt(civA, -6)
        val field = cityAt(civA, -2)
        home.build("Allows [Food] trade routes between your cities")
        val foodBefore = home.population.foodStored to field.population.foodStored
        assertNotNull(establish(home, field, kind = TradeRouteKind.Food))
        turns = 0
        while (civA.tradeRoutes.routes.isNotEmpty() && turns++ < 100) civA.tradeRoutes.startTurn()
        // the 3 of every kind once for each end of a domestic route (V:CvTradeClasses.cpp:2609-2611 fires the event for the origin and for the destination), no 75
        assertEquals("a domestic route", 84, civA.gold)
        assertEquals("150 food for each end", 150, home.population.foodStored - foodBefore.first)
        assertEquals(150, field.population.foodStored - foodBefore.second)
    }

    @Test
    fun `a trade unit moving fires once for each move, not each step`() {
        adopt(civA, "Gain [4] [Gold] <upon a trade route unit moving>")
        val (origin, destination) = twoCities(8)
        establish(origin, destination) // the trader moves right away: V:CvTradeClasses.cpp:848
        assertEquals(4, civA.gold)
        repeat(3) { civA.tradeRoutes.startTurn() } // 2 steps each: V:CvTradeClasses.cpp:2085-2150 yields after the steps
        assertEquals(16, civA.gold)
    }

    @Test
    fun `plundering a route fires the plunderer's yields and gives the gold of the original's formula`() {
        val enemy = game.addCiv()
        enemy.diplomacyFunctions.makeCivilizationsMeet(civA)
        enemy.getDiplomacyManager(civA)!!.declareWar()
        val capital = cityAt(enemy, -12)
        setEra(enemy, 2)
        val (origin, destination) = twoCities(8)
        val route = establish(origin, destination)!!
        game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(route.path[3]),
            "Gain [4] [Gold] <upon plundering a trade route>", "Gain [4] [Production] <upon plundering a trade route>")
        val goldBefore = enemy.gold
        val productionBefore = capital.cityConstructions.productionOverflow + capital.cityConstructions.inProgressConstructions.values.sum()
        civA.tradeRoutes.startTurn()
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        // TRADE_ROUTE_BASE_PLUNDER_GOLD 100 x (100 + 0 for land) / 100 x instant yield percent 100 x era 2 / 100 = 200, and Razzia's 4
        assertEquals(204, enemy.gold - goldBefore)
        val production = capital.cityConstructions.productionOverflow + capital.cityConstructions.inProgressConstructions.values.sum()
        assertEquals(4, production - productionBefore)
    }

    @Test
    fun `plundering a route by sea uses the sea modifier of 25 percent and the era`() {
        makeCoast()
        game.setTileTerrain(game.tileMap[0, 8].position, Constants.desert)
        val origin = cityAt(civA, 0)
        val destination = cityAt(civB, 8)
        val route = establish(origin, destination, cargoShip)!!
        val enemy = game.addCiv()
        enemy.diplomacyFunctions.makeCivilizationsMeet(civA)
        enemy.getDiplomacyManager(civA)!!.declareWar()
        setEra(enemy, 2)
        val ship = game.createBaseUnit("Melee Water").also { it.strength = 10; it.movement = 4 }
        val tile = game.getTile(route.path[route.pathIndex])
        game.addUnit(ship.name, enemy, tile)
        val goldBefore = enemy.gold
        civA.tradeRoutes.plunder(civA.tradeRoutes.getPlunderableRoutes(tile.militaryUnit!!).single(), tile.militaryUnit!!)
        // 100 x 125 / 100 = 125, x 100 x era 2 / 100 = 250
        assertEquals(250, enemy.gold - goldBefore)
    }

    //endregion
    //region Duration and the old model

    @Test
    fun `the duration is 30 turns times the game speed's trade route percentage`() {
        val (origin, destination) = twoCities(7)
        val speed = game.gameInfo.speed
        val before = speed.tradeRouteSpeedModifier
        temporarily(speed, { it.tradeRouteSpeedModifier = 1.5f }, { it.tradeRouteSpeedModifier = before })
        val route = establish(origin, destination)!!
        // 30 x 150 / 100 = 45 turns: a circuit takes 7 x 2 / 2 = 7, int(45 / 7) = 6 circuits
        assertEquals(6, route.circuitsToComplete)
    }

    @Test
    fun `the range is multiplied by the percentage of the map's size, which the other percentages are added to`() {
        game.ruleset.modOptions.constants.worldTradeRouteDistance = listOf(
            com.unciv.models.ModConstants.WorldTradeRouteDistance(1, 50), com.unciv.models.ModConstants.WorldTradeRouteDistance(5000, 130))
        val origin = cityAt(civA, 0)
        val five = cityAt(civB, 5)
        val six = cityAt(civB, -6)
        // the map of radius 16 has 817 tiles: the first entry, 10 x 50 / 100 = 5 tiles; 5 flat steps cost 505 of the 599 allowed, 6 cost 606
        assertEquals(50, civA.tradeRoutes.getWorldRangePercent())
        val reached = { civA.tradeRoutes.getDestinations(origin, caravan).map { it.city } }
        assertTrue(five in reached())
        assertTrue(six !in reached())
        // V:CvTradeClasses.cpp:5381-5388: (10 + 0) x (50 + 50) / 100 = 10
        origin.build("[+50]% range for [Land] trade routes <in this city>")
        assertTrue(six in reached())
        // and without the model the table is not read
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = false
        assertEquals(100, civA.tradeRoutes.getWorldRangePercent())
    }

    @Test
    fun `with the flag off the numbers of Brave New World stay`() {
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = false
        val (origin, destination) = twoCities(5)
        val route = establish(origin, destination)!!
        // BNW: 1 gold base for each end, a route needs no era: the unchanged formula
        assertEquals(1f, route.originYields.gold, 0.001f)
        assertEquals(1f, route.destinationYields.gold, 0.001f)
        setEra(civA, 7)
        civA.tradeRoutes.updateYields()
        assertEquals("the era does not matter", 1f, route.originYields.gold, 0.001f)
    }

    //endregion
}
