package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rules around Vox Populi's trade routes and resource deals (slice T4 of `phaseE/vp-trade-plan.md`): the destination restriction
 * (V:CvTradeClasses.cpp:665-685), the slots (5427-5483), the deals that do not trade temporary for permanent items (V:CvDealClasses.cpp:1368-1470) and the
 * city a founder does not sell in peacetime (649), the yields of luxuries exported and imported (V:CvPlayer.cpp:18078-18130).
 * V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Land steps cost 101 in the test world, as in [TradeRouteValueTests].
 */
@RunWith(GdxTestRunner::class)
class TradeRouteRulesTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var caravan: BaseUnit

    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(16)
        civA = game.addCiv()
        civB = game.addCiv()
        meet(civA, civB)
        caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        explore(civA)
        explore(civB)
    }

    private fun meet(a: Civilization, b: Civilization) {
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
    }

    private fun explore(civ: Civilization) { for (tile in game.tileMap.values) tile.setExplored(civ, true) }

    private fun cityAt(civ: Civilization, y: Int) = game.addCity(civ, game.tileMap[0, y], replacePalace = true)

    private fun establish(origin: City, destination: City): TradeRoute? {
        val mapUnit = game.addUnit(caravan.name, origin.civ, origin.getCenterTile())
        val route = origin.civ.tradeRoutes.establish(mapUnit, destination, TradeRouteKind.International)
        if (route == null) mapUnit.destroy()
        return route
    }

    //region Destination restriction: V:CvTradeClasses.cpp:665-685

    @Test
    fun `a second route of the same owner to the same city is refused, from another city too`() {
        constants.tradeRouteModelLikeVp = true
        val first = cityAt(civA, 0)
        val second = game.addCity(civA, game.tileMap[0, 3])
        val destination = cityAt(civB, 6)
        assertNotNull(establish(first, destination))
        assertNull(establish(second, destination))
        assertNull("not even for the same two cities", establish(first, destination))
        // a different city of theirs is another matter
        assertNotNull(establish(second, game.addCity(civB, game.tileMap[0, 8])))
    }

    @Test
    fun `without the model of Vox Populi a second route to the same city is allowed`() {
        val first = cityAt(civA, 0)
        val second = game.addCity(civA, game.tileMap[0, 3])
        val destination = cityAt(civB, 6)
        assertNotNull(establish(first, destination))
        assertNotNull(establish(second, destination))
    }

    @Test
    fun `Venice is not restricted, nor are the routes to it`() {
        constants.tradeRouteModelLikeVp = true
        val venice = game.addCiv("No destination restriction on trade routes")
        meet(venice, civB)
        meet(venice, civA)
        explore(venice)
        // the origin is Venice: two of its cities send to the same city
        val veniceFirst = cityAt(venice, -8)
        val veniceSecond = game.addCity(venice, game.tileMap[0, -11])
        val target = cityAt(civB, -14)
        assertNotNull(establish(veniceFirst, target))
        assertNotNull(establish(veniceSecond, target))

        // the destination is Venice: two cities of civ A send to it
        val first = cityAt(civA, 0)
        val second = game.addCity(civA, game.tileMap[0, 3])
        val venetian = game.addCity(venice, game.tileMap[0, 6])
        assertNotNull(establish(first, venetian))
        assertNotNull(establish(second, venetian))
    }

    //endregion
    //region Slots and range

    @Test
    fun `Mercantilism's slots add before Venice's percentage`() {
        // V:CvTradeClasses.cpp:5427-5483: the sum (here 1 + 2) times (100 + NumTradeRoutesModifier 100) / 100
        val venice = game.addCiv("[+1] Trade Route slots", "[+2] Trade Route slots", "[+100]% Trade Route slots")
        assertEquals(6, venice.tradeRoutes.getNumSlots())
        assertEquals(3, game.addCiv("[+1] Trade Route slots", "[+2] Trade Route slots").tradeRoutes.getNumSlots())
    }

    @Test
    fun `the world's range percentage is 100 without a table, and the monopoly seam says nobody has one`() {
        constants.tradeRouteModelLikeVp = true
        assertEquals(100, civA.tradeRoutes.getWorldRangePercent())
        assertFalse(civA.tradeRoutes.hasGlobalMonopoly(civA, "Iron"))
    }

    //endregion
    //region Temporary against permanent: V:CvDealClasses.cpp:1368-1470

    private fun offer(type: TradeOfferType, name: String = "x", amount: Int = 1) = TradeOffer(name, type, amount, game.gameInfo.speed)

    private val luxury get() = offer(TradeOfferType.Luxury_Resource, "Silk")
    private val gold get() = offer(TradeOfferType.Gold, "Gold", 100)

    private fun blocked(offer: TradeOffer, from: Civilization, to: Civilization, receiverGives: List<TradeOffer>) =
        TradeEvaluation.blocksTemporaryForPermanent(offer, from, to, receiverGives)

    @Test
    fun `a deal that gives resources for gold is refused, gold for gold is not`() {
        constants.dealsBlockTemporaryForPermanent = true
        assertTrue("temporary for permanent", blocked(luxury, civA, civB, listOf(gold)))
        assertTrue("and the other way", blocked(gold, civB, civA, listOf(luxury)))
        assertFalse("gold for gold", blocked(gold, civA, civB, listOf(gold)))
        assertFalse("resource for resource", blocked(luxury, civA, civB, listOf(offer(TradeOfferType.Strategic_Resource, "Iron"))))
        assertFalse("a peace treaty is neither", blocked(offer(TradeOfferType.Treaty, com.unciv.Constants.peaceTreaty), civA, civB, listOf(gold)))
        for (temporary in listOf(TradeOfferType.Gold_Per_Turn, TradeOfferType.Agreement, TradeOfferType.Embassy))
            assertTrue("$temporary is temporary", blocked(offer(temporary), civA, civB, listOf(gold)))
        for (permanent in listOf(TradeOfferType.City, TradeOfferType.Technology))
            assertTrue("$permanent is permanent", blocked(offer(permanent), civA, civB, listOf(luxury)))
    }

    @Test
    fun `nothing is refused without the rule`() {
        assertFalse(blocked(luxury, civA, civB, listOf(gold)))
    }

    @Test
    fun `a human may give gold for the AI's resources with the option on, and not between AIs`() {
        constants.dealsBlockTemporaryForPermanent = true
        val human = game.addCiv(isPlayer = true)
        // the human gives the permanent item, the AI the temporary one: allowed with MOD_DEALAI_HUMAN_PERMANENT_FOR_AI_TEMPORARY
        assertFalse(blocked(gold, human, civA, listOf(luxury)))
        assertFalse(blocked(luxury, civA, human, listOf(gold)))
        // the AI cannot give the permanent item for the human's temporary one, nor the human the temporary for the AI's permanent
        assertTrue(blocked(gold, civA, human, listOf(luxury)))
        assertTrue(blocked(luxury, human, civA, listOf(gold)))
        // between AIs nothing changes
        assertTrue(blocked(gold, civA, civB, listOf(luxury)))

        constants.dealHumanPermanentForAiTemporary = false
        assertTrue("the option off", blocked(gold, human, civA, listOf(luxury)))
        // two humans deal as they like
        val other = game.addCiv(isPlayer = true)
        assertFalse(blocked(gold, human, other, listOf(luxury)))
    }

    //endregion
    //region The founder's city: V:CvDealClasses.cpp:649

    @Test
    fun `a founder cannot sell a city he founded outside a peace deal`() {
        cityAt(civA, 0)
        val founded = game.addCity(civA, game.tileMap[0, 3])
        cityAt(civB, 6)
        val cityOffer = TradeOffer(founded.id, TradeOfferType.City, speed = game.gameInfo.speed)
        val asked = Trade().apply { ourOffers.add(cityOffer) }
        val offered = { TradeLogic(civA, civB).ourAvailableOffers.any { it.type == TradeOfferType.City } }
        assertTrue(offered())
        assertTrue(TradeEvaluation().isTradeValid(asked, civA, civB))

        constants.dealNoPeacetimeSellingFoundedCities = true
        assertFalse("not offered", offered())
        assertFalse("not valid", TradeEvaluation().isTradeValid(asked, civA, civB))
        civA.getDiplomacyManager(civB)!!.declareWar()
        assertTrue("at war, where a peace deal could take it, it is offered", offered())
        civA.getDiplomacyManager(civB)!!.makePeace()

        // a city the buyer got from the founder can be sold on
        founded.moveToCiv(civB)
        val bought = Trade().apply { ourOffers.add(TradeOffer(founded.id, TradeOfferType.City, speed = game.gameInfo.speed)) }
        assertTrue(TradeEvaluation().isTradeValid(bought, civB, civA))
    }

    //endregion
    //region Netherlands: V:CvPlayer.cpp:18078-18130

    private fun luxuryNamed(): String = game.createResource().also { it.resourceType = ResourceType.Luxury; it.setTransients(game.ruleset) }.name

    private fun sellTo(buyer: Civilization, seller: Civilization, vararg offers: TradeOffer) {
        val trade = Trade().apply { for (offer in offers) ourOffers.add(offer) }
        seller.getDiplomacyManager(buyer)!!.trades.add(trade)
        buyer.getDiplomacyManager(seller)!!.trades.add(trade.reverse())
        buyer.cache.updateCivResources()
    }

    @Test
    fun `the Netherlands get gold for each luxury exported and culture for each imported, times the era`() {
        val dutch = game.addCiv("[+3 Gold] for each luxury resource exported <for every [Era scaler]>", "[+2 Culture] for each luxury resource imported <for every [Era scaler]>")
        meet(dutch, civA)
        cityAt(dutch, -8)
        cityAt(civA, 0)
        val first = luxuryNamed()
        val second = luxuryNamed()
        val strategic = game.createResource().also { it.resourceType = ResourceType.Strategic; it.setTransients(game.ruleset) }.name
        dutch.tech.era = game.ruleset.eras.values.first { it.eraNumber == 2 }
        assertEquals("nothing is traded", 0f, dutch.tradeRoutes.getResourceTradeStats(dutch).gold, 0f)

        sellTo(civA, dutch, offer(TradeOfferType.Luxury_Resource, first), offer(TradeOfferType.Luxury_Resource, second), offer(TradeOfferType.Strategic_Resource, strategic))
        // two luxuries exported (the strategic one is no luxury): 3 x 2 x era 2 = 12
        assertEquals(12f, dutch.tradeRoutes.getResourceTradeStats(dutch).gold, 0.001f)
        assertEquals(12f, dutch.stats.getStatMapForNextTurn()["Resource trade"]!!.gold, 0.001f)
        assertEquals("nothing is imported", 0f, dutch.tradeRoutes.getResourceTradeStats(dutch).culture, 0f)

        val importer = game.addCiv("[+2 Culture] for each luxury resource imported <for every [Era scaler]>")
        meet(importer, dutch)
        cityAt(importer, 8)
        importer.tech.era = game.ruleset.eras.values.first { it.eraNumber == 2 }
        sellTo(importer, dutch, offer(TradeOfferType.Luxury_Resource, first), offer(TradeOfferType.Luxury_Resource, second))
        // two luxuries imported: 2 x 2 x era 2 = 8
        assertEquals(8f, importer.tradeRoutes.getResourceTradeStats(importer).culture, 0.001f)
        // the Dutch have none of their own to import
        assertEquals(0f, dutch.tradeRoutes.getResourceTradeStats(dutch).culture, 0f)
    }

    //endregion
}
