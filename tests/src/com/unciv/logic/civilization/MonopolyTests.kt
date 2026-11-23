package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.MonopolyManager
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueEffects
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Monopolies (slice T2 of `phaseE/vp-trade-plan.md`; CvPlayer::CheckForMonopoly, V:CvPlayer.cpp:38870-39400 and the effects that read them).
 * Every expected number is computed by hand from the cited lines of the original.
 */
@RunWith(GdxTestRunner::class)
class MonopolyTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var cityA: City
    private lateinit var cityB: City
    private var freeTiles = emptyList<Tile>()
    private var used = 0

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        a = game.addCiv()
        b = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        cityA = game.addCity(a, game.getTile(HexCoord(0, 0)))
        cityB = game.addCity(b, game.getTile(HexCoord(3, 0)))
        freeTiles = game.tileMap.values
            .filter { it.aerialDistanceTo(cityA.getCenterTile()) > 3 && it.aerialDistanceTo(cityB.getCenterTile()) > 3 }
            .sortedBy { it.position.x * 100 + it.position.y }
    }

    //region helpers
    private fun resource(
        type: ResourceType = ResourceType.Luxury,
        global: List<String> = emptyList(),
        strategic: List<String> = emptyList(),
    ): TileResource = game.createResource().apply {
        resourceType = type
        isMonopoly = true
        globalMonopolyUniques.addAll(global)
        strategicMonopolyUniques.addAll(strategic)
    }

    /** [copies] tiles of the map, none of them near a city, carry [resource] (a strategic resource one copy each) */
    private fun place(resource: TileResource, copies: Int) {
        repeat(copies) {
            val tile = freeTiles[used++]
            tile.setTileResource(resource.name)
            if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount = 1
        }
    }

    /** [copies] more copies of [resource] for [civ], as if from its tiles */
    private fun give(civ: Civilization, resource: TileResource, copies: Int) {
        civ.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [$copies] [${resource.name}]"))
        civ.cache.updateCivResources()
    }

    private fun globalOf(civ: Civilization, resource: TileResource) = civ.monopolies.hasGlobalMonopoly(resource.name)
    private fun strategicOf(civ: Civilization, resource: TileResource) = civ.monopolies.hasStrategicMonopoly(resource.name)

    private fun enactBan(resource: TileResource): ActiveResolution {
        val league = game.gameInfo.league ?: League().also { game.gameInfo.league = it }
        val active = ActiveResolution(league.activeResolutions.size + 1, "Ban Luxury", null, resource.name, game.gameInfo.turns)
        league.activeResolutions.add(active)
        league.setTransients(game.gameInfo)
        LeagueEffects.onEnact(league, active)
        return active
    }

    private fun repealBan(active: ActiveResolution) {
        val league = game.gameInfo.league!!
        league.activeResolutions.remove(active)
        league.setTransients(game.gameInfo)
        LeagueEffects.onRepeal(league, active)
    }
    //endregion

    @Test
    fun `a civilization with 6 of 10 copies has a global monopoly, with 5 of 10 it has none`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 5)
        // 5 x 100 > 10 x 50 is false
        assertFalse(globalOf(a, luxury))
        // V:CvPlayer.cpp:39364-39383: 50 + 10 > 50, and no extra copy is no gain
        assertTrue(a.monopolies.wouldGain(luxury, 1))
        assertFalse(a.monopolies.wouldGain(luxury, 0))
        give(a, luxury, 1)
        // 6 x 100 > 10 x 50
        assertTrue(globalOf(a, luxury))
        assertFalse("nothing of it for the other", globalOf(b, luxury))
        assertFalse("a luxury has no strategic monopoly", strategicOf(a, luxury))
    }

    @Test
    fun `the greatest holder keeps it at exactly the greatest share only while above 50 percent`() {
        val luxury = resource()
        place(luxury, 20)
        // 10 of 20 is the greatest share, exactly the threshold: owned x 100 >= total x 50 holds, owned x 100 > total x 50 does not
        give(a, luxury, 10)
        a.monopolies.checkAll()
        assertEquals(a.civID, game.gameInfo.greatestMonopolyPlayer[luxury.name])
        assertFalse(globalOf(a, luxury))
        // 11 of 20, the greatest share 55: 1100 >= 20 x 55 and 1100 > 20 x 50
        give(a, luxury, 1)
        assertTrue(globalOf(a, luxury))
        // b owns 12 of 20 (copies that a trade made owned twice count for both): b is the greatest with 60. a at 55 would need more than 60
        give(b, luxury, 12)
        a.monopolies.checkAll(refreshGreatest = false)
        game.gameInfo.greatestMonopolyPlayer[luxury.name] = b.civID
        a.monopolies.checkAll(refreshGreatest = false)
        assertFalse("not the greatest: 1100 > 20 x 60 is false", globalOf(a, luxury))
        b.monopolies.checkAll(refreshGreatest = false)
        assertTrue("the greatest: 1200 >= 20 x 60 and 1200 > 20 x 50", globalOf(b, luxury))
        // a fresh look finds b the greatest
        a.monopolies.checkAll()
        assertEquals(b.civID, game.gameInfo.greatestMonopolyPlayer[luxury.name])
    }

    @Test
    fun `copies sold in a deal still count as owned`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 6)
        assertTrue(globalOf(a, luxury))
        // a sells all six: owned = total (0 after the export) + export (6)
        val logic = TradeLogic(a, b)
        logic.currentTrade.ourOffers.add(TradeOffer(luxury.name, TradeOfferType.Luxury_Resource, 6, game.gameInfo.speed))
        logic.acceptTrade()
        assertEquals("nothing left to use", 0, a.getCivResourceSupply().sumBy(luxury.name))
        assertEquals("the buyer has them", 6, b.getCivResourceSupply().sumBy(luxury.name))
        assertTrue("a still owns what it sold", globalOf(a, luxury))
        assertFalse("imports are not owned copies", globalOf(b, luxury))
    }

    @Test
    fun `a strategic resource gives a strategic monopoly above 25 percent and a global one above 50`() {
        val strategic = resource(ResourceType.Strategic)
        place(strategic, 10)
        give(a, strategic, 2)
        // 200 > 250 is false
        assertFalse(strategicOf(a, strategic))
        give(a, strategic, 1)
        // 300 > 250, 300 > 500 is false
        assertTrue(strategicOf(a, strategic))
        assertFalse(globalOf(a, strategic))
        give(a, strategic, 2)
        // 500 > 500 is false
        assertTrue(strategicOf(a, strategic))
        assertFalse(globalOf(a, strategic))
        give(a, strategic, 1)
        // 600 > 500: both
        assertTrue(strategicOf(a, strategic))
        assertTrue(globalOf(a, strategic))
        assertEquals(60, a.monopolies.percent(strategic))
        // b owns none of the 10: 2 more copies are 20 percent, not over 25; 3 are 30
        assertFalse(b.monopolies.wouldGain(strategic, 2))
        assertTrue(b.monopolies.wouldGain(strategic, 3))
    }

    @Test
    fun `a banned luxury gives no monopoly and the ban's lifting restores it`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 6)
        assertTrue(globalOf(a, luxury))
        val ban = enactBan(luxury)
        assertFalse("V:CvVotingClasses.cpp:1592", globalOf(a, luxury))
        assertFalse(a.monopolies.wouldGain(luxury, 5))
        repealBan(ban)
        assertTrue("V:CvVotingClasses.cpp:1818", globalOf(a, luxury))
    }

    @Test
    fun `city-state resources count only with the policy`() {
        val luxury = resource()
        place(luxury, 10)
        val cityState = game.addCiv(cityStateType = "Mercantile")
        game.addCity(cityState, game.getTile(HexCoord(-3, 0)))
        cityState.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [4] [${luxury.name}]"))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.allyCiv = a
        give(a, luxury, 2)
        assertEquals("the ally's copies are supplied", 6, a.getCivResourceSupply().sumBy(luxury.name))
        // 2 owned of 10
        assertFalse(globalOf(a, luxury))
        cityA.cityConstructions.addBuilding(game.createBuilding("City-State resources count towards monopolies"))
        a.cache.updateCivResources()
        // 2 + 4 owned of 10 with the policy
        assertTrue(globalOf(a, luxury))
        assertEquals(60, a.monopolies.percent(luxury))
    }

    @Test
    fun `monopoly happiness is 6 for Furs plus MonopolyModFlat, added to the flat luxury happiness`() {
        val furs = resource(global = listOf("[6] Happiness from a global monopoly"))
        place(furs, 10)
        give(a, furs, 3)
        val without = a.stats.getHappinessBreakdown()
        assertEquals(0f, without["Resource monopolies"] ?: 0f, 0f)
        val luxuryHappiness = without["Luxury resources"]!!
        give(a, furs, 3)
        val with = a.stats.getHappinessBreakdown()
        assertEquals("the luxury itself is unchanged", luxuryHappiness, with["Luxury resources"]!!, 0f)
        assertEquals(6f, with["Resource monopolies"]!!, 0f)
        cityA.cityConstructions.addBuilding(game.createBuilding("[+2] to the flat bonus of each monopoly"))
        assertEquals("6 + 2", 8f, a.stats.getHappinessBreakdown()["Resource monopolies"]!!, 0f)
        // the copies are all sold: the luxury is not owned any more (CvPlayer::GetHappinessFromLuxury is 0), the monopoly happiness with it
        val logic = TradeLogic(a, b)
        logic.currentTrade.ourOffers.add(TradeOffer(furs.name, TradeOfferType.Luxury_Resource, 6, game.gameInfo.speed))
        logic.acceptTrade()
        assertTrue(globalOf(a, furs))
        assertEquals(0f, a.stats.getHappinessBreakdown()["Resource monopolies"] ?: 0f, 0f)
    }

    @Test
    fun `the golden age is 25 percent longer with a Gold monopoly, +5 more with Indonesia's percentage`() {
        val gold = resource(global = listOf("[+25]% Golden Age length"))
        place(gold, 10)
        assertEquals("no monopoly", 20, a.goldenAges.calculateGoldenAgeLength(20))
        give(a, gold, 6)
        // 20 x (100 + 25) / 100
        assertEquals(25, a.goldenAges.calculateGoldenAgeLength(20))
        cityA.cityConstructions.addBuilding(game.createBuilding("[+5]% to the percentage bonus of each monopoly"))
        // V:CvPlayer.cpp:25281-25290: 25 + 5
        assertEquals(26, a.goldenAges.calculateGoldenAgeLength(20))
    }

    @Test
    fun `a tile with Crab gives +3 food for the owner with a monopoly, +2 more with the flat modifier, nothing when the owner has none`() {
        val crab = resource(global = listOf("[+3 Food, -1 Gold] from each tile with this resource"))
        place(crab, 4)
        val tile = freeTiles[0]
        tile.setOwningCity(cityA)
        val before = tile.stats.getTileStats(cityA, a)
        give(a, crab, 1)
        assertEquals("1 of 4 is no monopoly", before.food, tile.stats.getTileStats(cityA, a).food, 0f)
        give(a, crab, 2)
        val monopoly = tile.stats.getTileStats(cityA, a)
        assertEquals(before.food + 3, monopoly.food, 0f)
        // the tile's total is never under 0, so the yield of the monopoly itself shows the base without and with the modifier
        val yield = a.monopolies.tileYield(crab)!!
        assertEquals(3f, yield.food, 0f)
        assertEquals("a negative base", -1f, yield.gold, 0f)
        assertEquals("a base of 0", 0f, yield.production, 0f)
        cityA.cityConstructions.addBuilding(game.createBuilding("[+2] to the flat bonus of each monopoly"))
        val flat = tile.stats.getTileStats(cityA, a)
        assertEquals(before.food + 3 + 2, flat.food, 0f)
        val flatYield = a.monopolies.tileYield(crab)!!
        assertEquals(5f, flatYield.food, 0f)
        assertEquals("V:CvPlot.cpp:11135-11141: only a positive base gets the flat bonus", -1f, flatYield.gold, 0f)
        assertEquals(0f, flatYield.production, 0f)
        assertEquals("no monopoly, no yield", null, b.monopolies.tileYield(crab))
        assertEquals(before.food, tile.stats.getTileStats(cityB, b).food, 0f)
    }

    @Test
    fun `Salt gives +10 percent food in every city, +5 with the percentage modifier`() {
        val salt = resource(global = listOf("[+10]% [Food] [in all cities]"))
        place(salt, 10)
        fun foodPercent() = cityA.cityStats.also { it.update() }.statPercentBonusTree.totalStats[Stat.Food]
        val before = foodPercent()
        give(a, salt, 6)
        assertEquals(before + 10f, foodPercent(), 0f)
        assertEquals("the other civilization has none", 0f, cityB.cityStats.also { it.update() }.statPercentBonusTree.totalStats[Stat.Food], 0f)
        cityA.cityConstructions.addBuilding(game.createBuilding("[+5]% to the percentage bonus of each monopoly"))
        assertEquals("10 + 5, V:CvPlayer.cpp:38980-38985", before + 15f, foodPercent(), 0f)
    }

    @Test
    fun `Doelen gives +1 gold, science, culture per global monopoly`() {
        val one = resource(global = listOf("[+1 Gold] from each tile with this resource"))
        val two = resource(global = listOf("[+1 Gold] from each tile with this resource"))
        place(one, 4)
        place(two, 4)
        cityA.cityConstructions.addBuilding(game.createBuilding("[+1 Gold, +1 Science, +1 Culture] [in all cities] for every global monopoly"))
        fun base() = cityA.cityStats.also { it.update() }.baseStatTree.totalStats
        val before = base()
        give(a, one, 3)
        val first = base()
        assertEquals(before.gold + 1, first.gold, 0f)
        assertEquals(before.science + 1, first.science, 0f)
        assertEquals(before.culture + 1, first.culture, 0f)
        give(a, two, 3)
        val second = base()
        assertEquals("two monopolies", before.gold + 2, second.gold, 0f)
        assertEquals(before.culture + 2, second.culture, 0f)
    }

    @Test
    fun `Horses give attack, Iron defense, Aluminum healing, Oil experience, Coal at sea with a strategic monopoly only`() {
        val horses = resource(ResourceType.Strategic, global = listOf("[+10]% Strength <when attacking>"))
        val iron = resource(ResourceType.Strategic, global = listOf("[+10]% Strength <when defending>"))
        val aluminum = resource(ResourceType.Strategic, global = listOf("[5] HP when healing"))
        val oil = resource(ResourceType.Strategic, global = listOf("New [Military] units start with [2] XP [in all cities]"))
        val coal = resource(ResourceType.Strategic, strategic = listOf("[+10]% Strength <when attacking> <for [Water] units>",
            "[+10]% Strength <when defending> <for [Water] units>"))
        val all = listOf(horses, iron, aluminum, oil, coal)
        for (resource in all) place(resource, 10)
        fun strengths() = a.getMatchingUniques(UniqueType.Strength, GameContext.IgnoreConditionals).map { it.text }.toSet()
        assertTrue(strengths().isEmpty())
        for (resource in listOf(horses, iron, aluminum, oil)) give(a, resource, 6)
        assertEquals(setOf("[+10]% Strength <when attacking>", "[+10]% Strength <when defending>"), strengths())
        assertEquals(listOf("5"), a.getMatchingUniques(UniqueType.Heal, GameContext.IgnoreConditionals).map { it.params[0] }.toList())
        assertEquals(listOf("2"), a.getMatchingUniques(UniqueType.UnitStartingExperience, GameContext.IgnoreConditionals).map { it.params[1] }.toList())
        // Coal: 3 of 10 is a strategic monopoly only, and its uniques are in the strategic list
        give(a, coal, 3)
        assertTrue(strategicOf(a, coal))
        assertFalse(globalOf(a, coal))
        assertEquals(4, strengths().size)
        // a monopoly lost takes its uniques with it
        val logic = TradeLogic(a, b)
        logic.currentTrade.ourOffers.add(TradeOffer(coal.name, TradeOfferType.Strategic_Resource, 3, game.gameInfo.speed))
        logic.acceptTrade()
        assertTrue("sold copies are still owned", strategicOf(a, coal))
        assertEquals(4, strengths().size)
        b.cache.updateCivResources()
        assertFalse("b imports, owns nothing", strategicOf(b, coal))
    }

    @Test
    fun `a building that needs a monopoly of any of the listed resources is unbuildable without one`() {
        val first = resource()
        val second = resource()
        place(first, 10)
        place(second, 10)
        val any = game.createBuilding().apply { requiredMonopolyAnyOf = listOf(first.name, second.name) }
        val all = game.createBuilding().apply { requiredMonopolies = listOf(first.name, second.name) }
        fun rejected(building: com.unciv.models.ruleset.Building) =
            building.getRejectionReasons(cityA.cityConstructions).any { it.type == RejectionReasonType.RequiresMonopoly }
        assertTrue(rejected(any))
        assertTrue(rejected(all))
        give(a, second, 6)
        assertFalse("one of the listed", rejected(any))
        assertTrue("not all of them", rejected(all))
        give(a, first, 6)
        assertFalse(rejected(all))
        enactBan(second)
        assertFalse("the monopoly of second is gone with the ban, first still counts", rejected(any))
        enactBan(first)
        assertTrue("all of them: a banned luxury does not count", rejected(all))
        // V:CvCity.cpp:9962-9985 (the plan reads this as 'banned does not count'): the banned ones are skipped, and a list of only banned ones asks for nothing
        assertFalse(rejected(any))
    }

    @Test
    fun `monopolies are recomputed at the start of every turn and after a trade`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 5)
        assertFalse(globalOf(a, luxury))
        // a copy of nobody's vanishes from the map: 5 of 9 is more than half, but nothing has triggered the check yet
        freeTiles[0].tileResource = null
        game.gameInfo.invalidateResourceCopies()
        assertFalse(globalOf(a, luxury))
        TurnManager(a).startTurn()
        assertTrue("recomputed at the start of the turn", globalOf(a, luxury))

        // a trade: the buyer's side is recalculated too, and a copy sold does not lose the monopoly
        val logic = TradeLogic(a, b)
        logic.currentTrade.ourOffers.add(TradeOffer(luxury.name, TradeOfferType.Luxury_Resource, 5, game.gameInfo.speed))
        logic.acceptTrade()
        assertTrue(globalOf(a, luxury))
        assertFalse(globalOf(b, luxury))
    }

    @Test
    fun `old saves load with no monopoly and recompute`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 6)
        val clone = a.monopolies.clone()
        assertEquals(setOf(luxury.name), clone.hasGlobalMonopoly)
        // a save from before the manager had none of the fields: a fresh manager, then the first turn finds it again
        a.monopolies = MonopolyManager().also { it.setTransients(a) }
        assertFalse(globalOf(a, luxury))
        TurnManager(a).startTurn()
        assertTrue(globalOf(a, luxury))
    }

    @Test
    fun `the copies on the map count tiles nobody has seen`() {
        val luxury = resource()
        val strategic = resource(ResourceType.Strategic)
        place(luxury, 10)
        val tile = freeTiles[used++]
        tile.setTileResource(strategic.name)
        tile.resourceAmount = 3
        val other = freeTiles[used++]
        other.setTileResource(strategic.name)
        other.resourceAmount = 4
        assertTrue("setup: nobody has explored them", freeTiles.take(used).none { it.isExplored(a) || it.isExplored(b) })
        assertEquals("a luxury is one copy a tile", 10, game.gameInfo.resourceCopiesOnMap(luxury.name))
        assertEquals("a strategic resource its quantity", 7, game.gameInfo.resourceCopiesOnMap(strategic.name))
        assertEquals(0, game.gameInfo.resourceCopiesOnMap(Constants.cityStates))
        give(a, luxury, 6)
        assertTrue(globalOf(a, luxury))
    }
}
