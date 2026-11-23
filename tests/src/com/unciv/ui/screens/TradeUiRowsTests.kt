package com.unciv.ui.screens

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.MonopolyNotices
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rows of the trade screens of slice T9 of `phaseE/vp-trade-plan.md`: the monopoly state and its texts ([MonopolyRows]), the monopoly notifications
 * ([MonopolyNotices], CvPlayer::CheckForMonopoly, V:CvPlayer.cpp:39255-39328), the details of a trade route ([TradeRouteRows]) and the rules of the deals ([TradeOfferRows]).
 * Everything is plain data, so nothing here needs a screen. The numbers come from the cited lines of Vox Populi and from the tests of the slices that put the data in.
 */
@RunWith(GdxTestRunner::class)
class TradeUiRowsTests {
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
        game.makeHexagonalMap(16)
        a = game.addCiv(isPlayer = true)
        b = game.addCiv(isPlayer = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        cityA = game.addCity(a, game.getTile(HexCoord(0, 0)), replacePalace = true)
        cityB = game.addCity(b, game.getTile(HexCoord(0, 5)), replacePalace = true)
        freeTiles = game.tileMap.values
            .filter { it.aerialDistanceTo(cityA.getCenterTile()) > 3 && it.aerialDistanceTo(cityB.getCenterTile()) > 3 }
            .sortedBy { it.position.x * 100 + it.position.y }
        for (tile in game.tileMap.values) { tile.setExplored(a, true); tile.setExplored(b, true) }
        // The rows list the monopoly resources of the rules: these tests make their own, and the ruleset has Vox Populi's since content flip F1 (the clone's own map)
        game.ruleset.tileResources.values.removeIf { it.isMonopoly }
    }

    private fun resource(global: List<String> = emptyList(), strategic: List<String> = emptyList(), type: ResourceType = ResourceType.Luxury): TileResource =
        game.createResource().apply {
            resourceType = type
            isMonopoly = true
            globalMonopolyUniques.addAll(global)
            strategicMonopolyUniques.addAll(strategic)
            setTransients(game.ruleset)
        }

    private fun place(resource: TileResource, copies: Int) {
        repeat(copies) {
            val tile = freeTiles[used++]
            tile.setTileResource(resource.name)
            if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount = 1
        }
    }

    private fun give(civ: Civilization, resource: TileResource, copies: Int) {
        civ.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [$copies] [${resource.name}]"))
        civ.cache.updateCivResources()
    }

    //region Monopoly rows

    @Test
    fun `without monopoly resources in the rules there are no rows`() {
        assertFalse(game.ruleset.hasMonopolyResources)
        assertTrue(MonopolyRows.rows(a).isEmpty())
        val plain = game.createResource().apply { resourceType = ResourceType.Luxury }
        assertTrue(MonopolyRows.tooltipLines(plain).isEmpty())
    }

    @Test
    fun `the row tells the share, the monopoly held and the civilization with the greatest share`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 3)
        var row = MonopolyRows.row(a, luxury)!!
        assertEquals(30, row.percent)
        assertFalse(row.global)
        assertFalse("a luxury has no strategic monopoly", row.strategic)
        assertEquals("No monopoly", MonopolyRows.state(row))
        // CvGame::m_aiGreatestMonopolyPlayer is only worked out when a monopoly changes (V:CvGame.cpp:14251-14301), so nobody is named yet
        assertEquals(listOf("No monopoly", "[30]% of the copies on the map"), MonopolyRows.lines(row))

        give(a, luxury, 3)
        row = MonopolyRows.row(a, luxury)!!
        assertEquals(60, row.percent)
        assertTrue(row.global)
        assertEquals("Global monopoly", MonopolyRows.state(row))
        assertEquals(a.civName, row.greatest)
        assertEquals(60, row.greatestPercent)
        assertEquals("the other civilization sees who is greatest and that it has none itself", a.civName, MonopolyRows.row(b, luxury)!!.greatest)
        assertEquals(0, MonopolyRows.row(b, luxury)!!.percent)
        assertEquals("Greatest share: [${a.civName}] ([60]%)", MonopolyRows.lines(row).last())
        assertEquals(listOf(luxury.name), MonopolyRows.rows(a).map { it.resource })
    }

    @Test
    fun `a strategic resource shows a strategic monopoly above a quarter, and a civilization we have not met is not named`() {
        val iron = resource(type = ResourceType.Strategic)
        place(iron, 10)
        give(a, iron, 3)
        val row = MonopolyRows.row(a, iron)!!
        assertTrue(row.strategic)
        assertFalse(row.global)
        assertEquals("Strategic monopoly", MonopolyRows.state(row))

        val stranger = game.addCiv()
        game.addCity(stranger, game.getTile(HexCoord(8, 8)), replacePalace = true)
        give(stranger, iron, 5)
        // V:CvGame.cpp:14270-14286: the greatest holder is the stranger; the viewer does not know it
        assertNull(MonopolyRows.row(a, iron)!!.greatest)
        assertEquals(listOf("Strategic monopoly", "[30]% of the copies on the map"), MonopolyRows.lines(MonopolyRows.row(a, iron)!!).filter { !it.startsWith("Greatest") })
    }

    @Test
    fun `the tooltip says what a global and a strategic monopoly give from the resource's own uniques`() {
        val iron = resource(global = listOf("[+3 Food] from each tile with this resource"), strategic = listOf("[+2 Gold] from each tile with this resource"), type = ResourceType.Strategic)
        assertEquals(
            listOf("A global monopoly gives:", "  [+3 Food] from each tile with this resource", "A strategic monopoly gives:", "  [+2 Gold] from each tile with this resource"),
            MonopolyRows.tooltipLines(iron)
        )
        val luxury = resource(global = listOf("[+3 Food] from each tile with this resource"))
        assertEquals(listOf("A global monopoly gives:", "  [+3 Food] from each tile with this resource"), MonopolyRows.tooltipLines(luxury))
    }

    //endregion
    //region Notifications

    private fun texts(civ: Civilization) = civ.notifications.map { it.text }

    @Test
    fun `a human is told when it gains a monopoly and the humans who know it too, and when it loses it`() {
        val luxury = resource()
        place(luxury, 10)
        give(a, luxury, 6)
        // V:CvPlayer.cpp:39255-39270: TXT_KEY_NOTIFICATION_MONOPOLY_GAINED to the civilization, ..._OTHER_PLAYER_... to the humans that have met it
        assertTrue("${texts(a)}", "You control over [50]% of the World's [${luxury.name}], and therefore have a Global Monopoly!" in texts(a))
        assertTrue("${texts(b)}", "[${a.civName}] has a Global Monopoly on [${luxury.name}]!" in texts(b))
        assertEquals("nobody else is told of its own gain", 0, texts(b).count { it.startsWith("You control") })
        // V:CvPlayer.cpp:39284: TXT_KEY_NOTIFICATION_MONOPOLY_LOST
        val building = a.getCapital()!!.cityConstructions.getBuiltBuildings().first { it.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ProvidesResources).any() }
        a.getCapital()!!.cityConstructions.removeBuilding(building)
        a.cache.updateCivResources()
        assertTrue("${texts(a)}", "You no longer have a Global Monopoly on [${luxury.name}]!" in texts(a))
    }

    @Test
    fun `an AI is told nothing and the notices are ordered global gain, strategic gain, losses`() {
        val luxury = resource()
        val ai = game.addCiv()
        game.addCity(ai, game.getTile(HexCoord(8, 8)), replacePalace = true)
        place(luxury, 10)
        give(ai, luxury, 6)
        assertTrue(ai.notifications.isEmpty())
        val notices = MonopolyNotices.notices(luxury, hadGlobal = false, hasGlobal = true, hadStrategic = true, hasStrategic = false, globalThreshold = 50, strategicThreshold = 25)
        assertEquals(2, notices.size)
        assertEquals("You control over [50]% of the World's [${luxury.name}], and therefore have a Global Monopoly!", notices[0].own)
        assertEquals("You no longer have a Strategic Monopoly on [${luxury.name}]!", notices[1].own)
        assertNull("a loss is told to the civilization only", notices[1].others)
    }

    //endregion
    //region Trade routes

    private val landUnique = "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn"

    private fun route(origin: City, destination: City): TradeRoute {
        val unit = game.createBaseUnit("Civilian", landUnique)
        val mapUnit = game.addUnit(unit.name, origin.civ, origin.getCenterTile())
        return origin.civ.tradeRoutes.establish(mapUnit, destination, TradeRouteKind.International)!!
    }

    @Test
    fun `the rows of a route are empty with the route model of Brave New World`() {
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = false   // the ruleset plays Vox Populi's model since content flip F1
        val route = route(cityA, cityB)
        assertTrue(TradeRouteRows.rows(route, a, true).isEmpty())
    }

    @Test
    fun `a route shows each yield, the proximity penalty, the route end and the passing yields`() {
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = true
        val far = game.addCity(b, game.getTile(HexCoord(0, 10)), replacePalace = false)
        val middle = game.addCity(a, game.getTile(HexCoord(0, 3)))
        middle.cityConstructions.addBuilding(game.createBuilding("[+3 Gold, +2 Culture] while any trade route passes through this city"))
        cityA.cityConstructions.addBuilding(game.createBuilding("Gain [30] [Gold] <upon completing a [international] trade route> <in this city>"))
        val route = route(cityA, cityB)
        val rows = TradeRouteRows.rows(route, a, true)

        // era 0: 80 x 2 = 160 hundredths of gold; a route half as long as the longest takes 50 percent of the modifier: 160 x 50 / 100 = 80, at least 100: 1.0
        val gold = rows.first { it.label == "Gold" }
        assertEquals("+${"%.1f".format(java.util.Locale.ROOT, route.originYields.gold)}", gold.value)
        assertEquals("the proximity penalty of V:CvTradeClasses.cpp:3405-3418", "-50%", rows.first { it.label == "Proximity penalty" }.value)
        assertTrue(rows.first { it.label == "Proximity penalty" }.isAlert)
        val end = rows.indexOfFirst { it.label == "When the route ends in [${cityA.name}]" }
        assertEquals("+30", rows[end + 1].value)
        assertEquals("Gold", rows[end + 1].label)
        val passing = rows.indexOfFirst { it.label == "While a route passes [${middle.name}]" }
        assertEquals(listOf("Gold" to "+3.0", "Culture" to "+2.0"), rows.subList(passing + 1, passing + 3).map { it.label to it.value })
        assertTrue("the far city is on no path", rows.none { it.label.contains(far.name) })

        // the destination's side: its own yields and the proximity of its own civilization, no route end of ours
        val theirs = TradeRouteRows.rows(route, b, false)
        assertTrue(theirs.none { it.label.startsWith("When the route ends") })
        assertEquals(route.destinationYields.gold == 0f, theirs.none { it.label == "Gold" })
    }

    @Test
    fun `a plundered connection and an enemy unit on the route are shown`() {
        game.ruleset.modOptions.constants.tradeRouteModelLikeVp = true
        val route = route(cityA, cityB)
        a.tradeRoutes.plunderedConnections[com.unciv.logic.civilization.managers.TradeRouteManager.plunderKey(cityA.location, cityB.location)] = game.gameInfo.turns
        assertTrue(TradeRouteRows.rows(route, a, true).any { it.label.startsWith("The connection was plundered lately") })
        a.getDiplomacyManager(b)!!.declareWar()
        val raider = game.addUnit("Warrior", b, route.getCurrentTile())
        assertTrue(raider.currentTile == route.getCurrentTile())
        val rows = TradeRouteRows.rows(route, a, true)
        assertTrue(rows.any { it.label.startsWith("An enemy unit stands on the route: it can be plundered for [") })
    }

    //endregion
    //region The rules of the deals

    private fun offer(type: TradeOfferType, name: String = "x", amount: Int = 1) = TradeOffer(name, type, amount, game.gameInfo.speed)

    @Test
    fun `items are marked permanent or temporary only where the rule is on`() {
        val gold = offer(TradeOfferType.Gold, "Gold", 100)
        val resource = offer(TradeOfferType.Luxury_Resource, "Silk")
        game.ruleset.modOptions.constants.dealsBlockTemporaryForPermanent = false   // the ruleset plays Vox Populi's rule since content flip F1
        assertNull(TradeOfferRows.permanenceNote(gold, a))
        game.ruleset.modOptions.constants.dealsBlockTemporaryForPermanent = true
        assertEquals("Permanent item", TradeOfferRows.permanenceNote(gold, a))
        assertEquals("Temporary item", TradeOfferRows.permanenceNote(resource, a))
        assertEquals("Temporary item", TradeOfferRows.permanenceNote(offer(TradeOfferType.Gold_Per_Turn), a))
        assertEquals("Permanent item", TradeOfferRows.permanenceNote(offer(TradeOfferType.City), a))
        assertNull("a peace treaty is neither", TradeOfferRows.permanenceNote(offer(TradeOfferType.Treaty, com.unciv.Constants.peaceTreaty), a))
    }

    @Test
    fun `a deal of a temporary item for a permanent one says why it cannot be proposed`() {
        val ai = game.addCiv()
        ai.diplomacyFunctions.makeCivilizationsMeet(a)
        val trade = Trade().apply {
            ourOffers.add(offer(TradeOfferType.Gold, "Gold", 100))
            theirOffers.add(offer(TradeOfferType.Luxury_Resource, "Silk"))
        }
        game.ruleset.modOptions.constants.dealsBlockTemporaryForPermanent = false   // the ruleset plays Vox Populi's rule since content flip F1
        assertTrue("the rule is off", TradeOfferRows.blockReasons(trade, ai, a).isEmpty())
        game.ruleset.modOptions.constants.dealsBlockTemporaryForPermanent = true
        // the AI gives the permanent gold for the human's temporary luxury: V:CvDealClasses.cpp:1368-1470 forbids it, the human's own permanent-for-temporary is the exception
        assertEquals(listOf("A temporary item cannot be traded for a permanent one"), TradeOfferRows.blockReasons(trade, ai, a))
        assertTrue("the same deal from the human's side", TradeOfferRows.blockReasons(trade.reverse(), a, ai).isNotEmpty())
        // two humans deal as they like (V:CvDealClasses.cpp:1368-1470)
        assertTrue(TradeOfferRows.blockReasons(trade, a, b).isEmpty())
    }

    @Test
    fun `a founder's city in a deal says that it cannot be sold in peacetime`() {
        val founded = game.addCity(a, game.getTile(HexCoord(0, 12)))
        val ai = game.addCiv()
        ai.diplomacyFunctions.makeCivilizationsMeet(a)
        val trade = Trade().apply { ourOffers.add(TradeOffer(founded.id, TradeOfferType.City, speed = game.gameInfo.speed)) }
        game.ruleset.modOptions.constants.dealNoPeacetimeSellingFoundedCities = false   // the ruleset plays Vox Populi's rule since content flip F1
        assertTrue(TradeOfferRows.blockReasons(trade, a, ai).isEmpty())
        game.ruleset.modOptions.constants.dealNoPeacetimeSellingFoundedCities = true
        assertEquals(listOf("The founder of [${founded.name}] cannot sell it in peacetime"), TradeOfferRows.blockReasons(trade, a, ai))
    }

    //endregion
}
