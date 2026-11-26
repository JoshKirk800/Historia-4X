package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What the missions of Vox Populi do to the city they are done in (CvCity::DoEventChoice, V:CvCity.cpp:6560-7126) and what stops a second one (CvCity::IsCityEventChoiceValid, V:CvCity.cpp:4059-4100,
 *  4191, 4212, 4412, 4721): through [SpyMissionResolver.perform], with an identify chance and a kill chance of 0 so that the spy is never found and lives, and the cost 0. The missions carry the texts
 *  the generator writes. Expiry is the city's own turn ([com.unciv.logic.city.managers.CityEspionageManager.endTurn]) and the civilization's ([com.unciv.models.ruleset.unique.endTurn]).
 */
@RunWith(GdxTestRunner::class)
class SpyMissionEffectTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var bCity: City
    private lateinit var spy: Spy
    private val missions = ArrayList<String>()
    private var productionCostModifier = 1f

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        productionCostModifier = game.gameInfo.speed.productionCostModifier
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        game.gameInfo.turns = 10
        a = game.addCiv(isPlayer = true)
        b = game.addCiv()
        game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, spy.action)
    }

    /** The speed of a TestGame is the ruleset's cached one, and a test that sets its production cost modifier left it so for every class that ran after (an upgrade cost
     *  of 45 where 65 was expected in `UnitPurchaseContentTests`): what it was in [setUp] comes back */
    @After
    fun tearDown() {
        for (name in missions) game.ruleset.spyMissions.remove(name)
        game.gameInfo.speed.productionCostModifier = productionCostModifier
    }

    private fun mission(name: String, duration: Int = 0, group: Int = 0, vararg uniques: String): SpyMission {
        val mission = SpyMission().apply {
            this.name = name; networkPoints = 0; identifyChance = 0; killChance = 0; cityFilter = "Capital"
            this.duration = duration; this.group = group
            this.uniques = ArrayList(uniques.toList())
        }
        game.ruleset.spyMissions[name] = mission
        missions += name
        return mission
    }

    /** the turn of the city and of its owner that ends [turns] turns of the game */
    private fun endTurns(turns: Int) = repeat(turns) {
        bCity.espionage.endTurn()
        a.temporaryUniques.endTurn()
        b.temporaryUniques.endTurn()
        game.gameInfo.turns++
    }

    // region Burgle the National Treasury (V:CvCity.cpp:6616-6630)

    @Test
    fun `a treasury is robbed by the percent of the whole of it, rounded down`() {
        b.addGold(1001 - b.gold)
        a.addGold(5 - a.gold)
        SpyMissionResolver.perform(spy, mission("Burgle", 15, 4, "Steal [35]% of the treasury of the target"))
        assertEquals("35% of 1001 is 350", 350, 1001 - b.gold)
        assertEquals(355, a.gold)
    }

    @Test
    fun `the percent is divided by the production cost modifier of the game speed`() {
        game.gameInfo.speed.productionCostModifier = 0.67f
        b.addGold(1000 - b.gold)
        SpyMissionResolver.perform(spy, mission("Burgle", 15, 4, "Steal [35]% of the treasury of the target"))
        assertEquals("35 x 100 / 67 = 52 percent", 520, 1000 - b.gold)
    }

    // endregion

    // region Infiltrate the Governor's Office (V:CvCity.cpp:6822-6843, V:CvPlayer.cpp:46236-46271)

    private fun infiltrate() = mission("Infiltrate", 15, 0, "Siphon [10]% of the [Gold] of the city for [15] turns")

    @Test
    fun `a siphon pays its owner and costs the city's owner the tenth of what the city yielded then`() {
        a.updateStatsForNextTurn(); b.updateStatsForNextTurn()
        val thiefBefore = a.stats.statsForNextTurn.gold
        val victimBefore = b.stats.statsForNextTurn.gold
        bCity.cityStats.currentCityStats.gold = 40f   // what the city yields at the moment of the mission
        SpyMissionResolver.perform(spy, infiltrate())
        assertEquals("the thief gets 4 a turn", 4f, a.stats.statsForNextTurn.gold - thiefBefore, 0.01f)
        assertEquals("the victim loses 4 a turn", -4f, b.stats.statsForNextTurn.gold - victimBefore, 0.01f)
    }

    @Test
    fun `a siphon ends after its turns and not before`() {
        bCity.cityStats.currentCityStats.gold = 40f
        SpyMissionResolver.perform(spy, infiltrate())
        repeat(14) { a.temporaryUniques.endTurn() }
        assertEquals("still there after 14 of 15", 1, a.temporaryUniques.size)
        a.temporaryUniques.endTurn()
        assertEquals(0, a.temporaryUniques.size)
    }

    @Test
    fun `the amount does not follow the city after the mission`() {
        bCity.cityStats.currentCityStats.gold = 40f
        SpyMissionResolver.perform(spy, infiltrate())
        bCity.cityStats.currentCityStats.gold = 400f
        assertTrue(a.temporaryUniques.any { it.unique == "[+4 Gold]" })
    }

    @Test
    fun `a city that yields nothing is not siphoned`() {
        bCity.cityStats.currentCityStats.gold = 0f
        SpyMissionResolver.perform(spy, infiltrate())
        assertTrue(a.temporaryUniques.isEmpty())
        assertTrue(b.temporaryUniques.isEmpty())
    }

    // endregion

    // region Poison the Water Supply and Assassinate the City Governor: the effects of the city that end by themselves (V:CvCity.cpp:3264-3293)

    @Test
    fun `an effect with turns is a unique of the city for as many turns`() {
        SpyMissionResolver.perform(spy, mission("Poison", 10, 1, "[-50]% [Food] [in this city] <for [10] turns>"))
        var food = 0
        bCity.forEachLocalMatchingUnique(UniqueType.StatPercentBonusCities) { food += it.params[0].toInt() }
        assertEquals(-50, food)
        endTurns(9)
        food = 0
        bCity.forEachLocalMatchingUnique(UniqueType.StatPercentBonusCities) { food += it.params[0].toInt() }
        assertEquals("on the ninth turn it still works", -50, food)
        endTurns(1)
        food = 0
        bCity.forEachLocalMatchingUnique(UniqueType.StatPercentBonusCities) { food += it.params[0].toInt() }
        assertEquals("on the tenth it is gone", 0, food)
    }

    @Test
    fun `a poisoned city makes less food for the turns and then the same again`() {
        bCity.cityStats.update()
        val before = bCity.cityStats.currentCityStats.food
        assertTrue("the city makes food to lose", before > 0f)
        SpyMissionResolver.perform(spy, mission("Poison", 10, 1, "[-50]% [Food] [in this city] <for [10] turns>"))
        bCity.cityStats.update()
        assertTrue("half of it less (rounding aside): $before to ${bCity.cityStats.currentCityStats.food}", bCity.cityStats.currentCityStats.food < before * 0.75f)
        repeat(10) { com.unciv.logic.city.managers.CityTurnManager(bCity).endTurn() }
        bCity.cityStats.update()
        assertEquals("and it is back", before, bCity.cityStats.currentCityStats.food, 0.01f)
    }

    @Test
    fun `a city whose governor was assassinated builds no building for the turns, and units still`() {
        val building = game.createBuilding()
        assertTrue(building.isBuildable(bCity.cityConstructions))
        SpyMissionResolver.perform(spy, mission("Assassinate", 10, 1, "Cannot build [All] buildings <for [10] turns>"))
        assertFalse(building.isBuildable(bCity.cityConstructions))
        endTurns(10)
        assertTrue(building.isBuildable(bCity.cityConstructions))
    }

    @Test
    fun `a mission that saps the city leaves it sapped for the turns`() {
        SpyMissionResolver.perform(spy, mission("Sabotage", 5, 1, "City is sapped <for [5] turns>"))
        assertTrue(bCity.getMatchingUniques(UniqueType.CitySapped).any())
        endTurns(5)
        assertFalse(bCity.getMatchingUniques(UniqueType.CitySapped).any())
    }

    // endregion

    // region Pillage (V:CvCity.cpp:7047-7122)

    private fun ownedTiles() = bCity.getTiles().filter { it != bCity.getCenterTile() }.toList()

    @Test
    fun `a tile whose improvement connects its resource is pillaged and an improvement on no resource is not`() {
        val tile = ownedTiles().first()
        tile.setTileResource("Iron")
        tile.setImprovement("Mine")
        val unrelated = ownedTiles()[1]
        unrelated.setImprovement("Farm")
        SpyMissionResolver.perform(spy, mission("Radicalize", 15, 4, "Pillage [100]% of the improved resource tiles in the city's tiles"))
        assertTrue(tile.improvementIsPillaged)
        assertFalse("a farm on no resource stays", unrelated.improvementIsPillaged)
    }

    @Test
    fun `roads are pillaged but never the road of the city centre`() {
        val tile = ownedTiles().first()
        tile.roadStatus = RoadStatus.Road
        val center = bCity.getCenterTile()
        center.roadStatus = RoadStatus.Road
        SpyMissionResolver.perform(spy, mission("Radicalize", 15, 4, "Pillage [100]% of the roads in the city's tiles"))
        assertTrue(tile.roadIsPillaged)
        assertFalse(center.roadIsPillaged)
    }

    @Test
    fun `a pillage of the roads leaves the improvement of the tile standing`() {
        val tile = ownedTiles().first()
        tile.setImprovement("Farm")
        tile.roadStatus = RoadStatus.Road
        SpyMissionResolver.perform(spy, mission("Radicalize", 15, 4, "Pillage [100]% of the roads in the city's tiles"))
        assertTrue(tile.roadIsPillaged)
        assertFalse(tile.improvementIsPillaged)
    }

    @Test
    fun `fortifications are the tagged improvements, not the centre`() {
        val fort = game.createTileImprovement("Fortification")
        val farm = ownedTiles()[1]
        farm.setImprovement("Farm")
        val tile = ownedTiles().first()
        tile.setImprovement(fort.name)
        SpyMissionResolver.perform(spy, mission("Sabotage", 5, 1, "Pillage [100]% of the [Fortification] improvements in the city's tiles"))
        assertTrue(tile.improvementIsPillaged)
        assertFalse(farm.improvementIsPillaged)
    }

    @Test
    fun `a chance under 100 is rolled for each tile, so some are pillaged and some are not`() {
        val tiles = ownedTiles().take(12)
        for (t in tiles) { t.setTileResource("Iron"); t.setImprovement("Mine") }
        SpyMissionResolver.perform(spy, mission("Radicalize", 15, 4, "Pillage [50]% of the improved resource tiles in the city's tiles"))
        val first = tiles.map { it.improvementIsPillaged }
        assertTrue("some were pillaged", first.any { it })
        assertTrue("some were not", first.any { !it })
    }

    // endregion

    // region What stops a mission (V:CvCity.cpp:4059-4100, 4191, 4212, 4412, 4721)

    @Test
    fun `a mission that still runs in the city is not done again, nor another of its group`() {
        val poison = mission("Poison", 10, 1, "[-50]% [Food] [in this city] <for [10] turns>")
        val sabotage = mission("Sabotage", 5, 1, "City is sapped <for [5] turns>")
        val burgle = mission("Burgle", 15, 4, "Steal [35]% of the treasury of the target")
        assertTrue(SpyMissionResolver.canWorkTowards(spy, poison))
        SpyMissionResolver.perform(spy, poison)
        // canWorkTowards leaves out the wait after the last mission, which is not what is under test
        assertFalse("the same mission", SpyMissionResolver.canWorkTowards(spy, poison))
        assertFalse("one of the same group", SpyMissionResolver.canWorkTowards(spy, sabotage))
        assertTrue("another group", SpyMissionResolver.canWorkTowards(spy, burgle))
    }

    @Test
    fun `the mission can be done again when its turns are over`() {
        val poison = mission("Poison", 10, 1, "[-50]% [Food] [in this city] <for [10] turns>")
        SpyMissionResolver.perform(spy, poison)
        endTurns(9)
        assertFalse("on the ninth turn it still runs", SpyMissionResolver.canWorkTowards(spy, poison))
        endTurns(1)
        assertTrue("on the tenth it is over", SpyMissionResolver.canWorkTowards(spy, poison))
    }

    @Test
    fun `a mission that needs no fresh water is refused beside a river and allowed away from one`() {
        val poison = mission("Poison", 10, 1, "Needs [no fresh water]", "[-50]% [Food] [in this city] <for [10] turns>")
        val center = bCity.getCenterTile()
        for (tile in center.neighbors + center) game.setTileTerrainAndFeatures(tile.position, "Plains")
        assertFalse(center.isAdjacentTo(com.unciv.Constants.freshWater))
        assertNull("away from fresh water it is allowed", SpyMissionResolver.unavailableReason(spy, poison))
        game.setTileTerrainAndFeatures(center.neighbors.first().position, "Lakes")
        assertTrue(center.isAdjacentTo(com.unciv.Constants.freshWater))
        assertNotNull("beside a lake it is refused", SpyMissionResolver.unavailableReason(spy, poison))
    }

    @Test
    fun `a mission that needs an unhappy owner is refused while the owner is happy`() {
        val unrest = mission("Unrest", 10, 3, "Needs [an unhappy owner]")
        // CvPlayer::IsEmpireUnhappy: the happiness under 0 in the happiness model of Brave New World (the approval rating of the other is the needs' own tests)
        game.ruleset.modOptions.constants.cityNeedsLikeVp = false
        b.stats.happiness = 5
        assertFalse(b.isEmpireUnhappy())
        assertNotNull(SpyMissionResolver.unavailableReason(spy, unrest))
        b.stats.happiness = -5
        assertTrue("the owner is unhappy", b.isEmpireUnhappy())
        assertNull(SpyMissionResolver.unavailableReason(spy, unrest))
    }

    @Test
    fun `a mission that needs no puppet is refused in a puppet`() {
        val kidnap = mission("Kidnap", 15, 4, "Needs [no puppet]")
        assertNull(SpyMissionResolver.unavailableReason(spy, kidnap))
        bCity.isPuppet = true
        assertNotNull(SpyMissionResolver.unavailableReason(spy, kidnap))
    }

    @Test
    fun `a mission that needs a trade connection is refused until a route reaches the city`() {
        val hijack = mission("Hijack", 10, 3, "Needs [a trade connection]")
        assertNotNull(SpyMissionResolver.unavailableReason(spy, hijack))
        // a route of ANOTHER civilization that ends in the city counts: the city is the start or the end of any route (V:CvCity.cpp:34705-34747)
        val third = game.addCiv()
        val far = game.addCity(third, game.tileMap[0, -4])
        val route = com.unciv.models.TradeRoute(far.location, bCity.location, com.unciv.models.TradeRouteKind.International, false, "Caravan",
            listOf(far.location, bCity.location), 2, 30, 0)
        route.setTransients(third)
        third.tradeRoutes.routes.add(route)
        assertNull(SpyMissionResolver.unavailableReason(spy, hijack))
    }

    @Test
    fun `a building the mission needs, or must not find, is a condition of the city`() {
        val temple = game.createBuilding().name
        val needs = mission("Pilfer", 15, 2, "Needs [the city] <in cities with a [$temple]>")
        val lacks = mission("Poison", 10, 1, "Needs [the city] <in cities without a [$temple]>")
        assertNotNull(SpyMissionResolver.unavailableReason(spy, needs))
        assertNull(SpyMissionResolver.unavailableReason(spy, lacks))
        bCity.cityConstructions.addBuilding(temple)
        assertNull(SpyMissionResolver.unavailableReason(spy, needs))
        assertNotNull(SpyMissionResolver.unavailableReason(spy, lacks))
    }

    // endregion

    // region The turn of the game (CvCity::DoTurn, V:CvCity.cpp:3264-3293): the city counts its effects down once a turn, by itself

    @Test
    fun `the turn of the city ends an effect after exactly the turns of the mission`() {
        SpyMissionResolver.perform(spy, mission("Poison", 10, 1, "[-50]% [Food] [in this city] <for [10] turns>"))
        repeat(9) { com.unciv.logic.city.managers.CityTurnManager(bCity).endTurn(); game.gameInfo.turns++ }
        assertEquals("after nine turns of the city", 1, bCity.missionEffects.size)
        assertTrue(bCity.activeMissions.containsKey("Poison"))
        com.unciv.logic.city.managers.CityTurnManager(bCity).endTurn(); game.gameInfo.turns++
        assertEquals("after ten", 0, bCity.missionEffects.size)
        assertFalse(bCity.activeMissions.containsKey("Poison"))
    }

    // endregion

    // region Saved state

    @Test
    fun `the effects and the active missions of a city survive a copy of the city`() {
        SpyMissionResolver.perform(spy, mission("Poison", 10, 1, "[-50]% [Food] [in this city] <for [10] turns>"))
        val copy = bCity.clone()
        assertEquals(1, copy.missionEffects.size)
        assertEquals(bCity.missionEffects[0].turnsLeft, copy.missionEffects[0].turnsLeft)
        assertEquals(bCity.activeMissions, copy.activeMissions)
        copy.missionEffects[0].turnsLeft = 1
        assertEquals("the copy is its own", 10, bCity.missionEffects[0].turnsLeft)
    }

    // endregion
}
