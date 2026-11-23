package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

/**
 * The deal AI of slice T6 of `phaseE/vp-trade-plan.md`: the luxury value of Vox Populi's `MOD_BALANCE_VP` branch (CvDealAI::GetLuxuryResourceValue, V:CvDealAI.cpp:1290-1590,
 * [VpLuxuryValue]) and the AI that does not propose what the rules of the deals of slice T4 forbid (V:CvDealAI.cpp:4535-4711). Every number is worked out from the cited lines:
 * one GPT is [DealResourceValues.oneGptValue], the scaled one that times the number of eras and the share of technologies known over 100.
 */
@RunWith(GdxTestRunner::class)
class VpDealAiTests {
    //region The luxury value

    /** A world with a human civilization [NeedsTestWorld.civ] with the needs model and the luxury value of Vox Populi, and a seller with a luxury on its capital */
    private class LuxuryWorld(vararg civUniques: String) {
        val world = NeedsTestWorld(*civUniques)
        val buyer: Civilization = world.civ
        val seller: Civilization = world.game.addCiv()
        val resource: TileResource

        init {
            world.constants.dealLuxuryValueLikeVp = true
            resource = world.game.ruleset.tileResources.values.first { it.resourceType == ResourceType.Luxury }
            give(seller)
        }

        /** A luxury on the centre tile of a new city of [owner], connected without an improvement */
        fun give(owner: Civilization) {
            val city = world.addCity(1, of = owner)
            city.getCenterTile().tileResource = resource
            city.getCenterTile().resourceAmount = 1
            for (improvement in resource.getImprovements())
                world.game.ruleset.tileImprovements[improvement]?.techRequired?.let { owner.tech.addTechnology(it) }
            owner.cache.updateCivResources()
        }

        val oneGpt get() = DealResourceValues.oneGptValue(buyer, false)
        val scaled get() = oneGpt * world.game.ruleset.eras.size * DealResourceValues.techProgressPercent(buyer) / 100
        val base get() = scaled + oneGpt

        fun buying(netGold: Int = 0) = DealResourceValues.luxuryValue(buyer, seller, resource, 30, fromMe = false, netGoldOfReceiver = netGold)
        fun selling() = DealResourceValues.luxuryValue(buyer, seller, resource, 30, fromMe = true, netGoldOfReceiver = 0)
    }

    @Test
    fun `the last copy of an unhappy empire is not for sale and another copy is worth one GPT plus the scaled one`() {
        val w = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(w.world, w.buyer, 6, 10)
        w.give(w.buyer)
        NeedsAiScenarios.refresh(w.buyer)
        assertTrue("setup: 6 + 2 against 10 is approval 40", w.buyer.isEmpireUnhappy())
        // V:CvDealAI.cpp:1308-1312: INT_MAX
        assertNull(w.selling())
        w.give(w.buyer)
        NeedsAiScenarios.refresh(w.buyer)
        // not the last copy: the base, OneGPTScaled + OneGPT (V:CvDealAI.cpp:1302-1304)
        assertEquals(w.base, w.selling())
    }

    @Test
    fun `the last copy of a happy empire is worth the base plus the scaled GPT for each of its happiness and 2 more`() {
        val w = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(w.world, w.buyer, 40, 10)
        w.give(w.buyer)
        NeedsAiScenarios.refresh(w.buyer)
        val happiness = DealResourceValues.happinessFromLuxury(w.buyer, w.resource) + DealResourceValues.extraHappinessPerLuxury(w.buyer)
        // V:CvDealAI.cpp:1329: OneGPTScaled x (happiness + 2)
        assertEquals(w.base + w.scaled * (happiness + 2), w.selling())
    }

    @Test
    fun `a buyer pays 3 scaled GPT more when unhappy, 6 when very unhappy and 12 when super unhappy`() {
        val w = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(w.world, w.buyer, 20, 10)
        assertEquals("a happy buyer pays the base", w.base, w.buying())
        // the approval ratings of DealAiTests' worked examples (45, 33 and 15), V:CvDealAI.cpp:1418-1425
        val unhappy = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(unhappy.world, unhappy.buyer, 9, 10)
        assertEquals(unhappy.base + 3 * unhappy.scaled, unhappy.buying())
        val veryUnhappy = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(veryUnhappy.world, veryUnhappy.buyer, 2, 3)
        assertEquals(veryUnhappy.base + 6 * veryUnhappy.scaled, veryUnhappy.buying())
        val superUnhappy = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(superUnhappy.world, superUnhappy.buyer, 3, 10)
        assertEquals(superUnhappy.base + 12 * superUnhappy.scaled, superUnhappy.buying())
    }

    @Test
    fun `the receiver's net gold raises the price by half its square root, up to a fifth`() {
        val w = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(w.world, w.buyer, 20, 10)
        // V:CvDealAI.cpp:1587-1591: int(0.5 x sqrt(100)) = 5, at most a fifth of the value
        assertEquals(w.base + min(5, w.base / 5), w.buying(100))
        assertEquals("2 gold per turn: int(0.5 x 1.41) = 0", w.base, w.buying(2))
    }

    @Test
    fun `a trait that pays for exported luxuries makes the seller's price lower by one GPT per yield and era`() {
        val w = LuxuryWorld("[+3 Gold] for each luxury resource exported <for every [Era scaler]>")
        NeedsAiScenarios.addEmpireCity(w.world, w.buyer, 40, 10)
        w.give(w.buyer)
        w.give(w.buyer)
        NeedsAiScenarios.refresh(w.buyer)
        // V:CvDealAI.cpp:1335-1366: 3 gold x one GPT x max(1, era 0), while nothing of it is exported yet
        assertEquals(w.base - 3 * w.oneGpt, w.selling())
    }

    @Test
    fun `the Community Patch value is kept without the flag`() {
        val w = LuxuryWorld()
        NeedsAiScenarios.addEmpireCity(w.world, w.buyer, 20, 10)
        w.world.constants.dealLuxuryValueLikeVp = false
        assertNotNull(w.buying())
        assertTrue("(happiness + game era) x turns / 2 is not one GPT plus the scaled one", w.buying() != w.base)
    }

    //endregion
    //region The AI does not propose what the rules forbid

    /** [us] gives [offered] to [them], who have the gold and the income to pay, and the AI balances the deal */
    private fun equalized(forbid: Boolean, offered: TradeOffer): Trade {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.dealsBlockTemporaryForPermanent = forbid
        val us = game.addCiv()
        val them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        val capital = game.addCity(us, game.getTile(com.unciv.logic.map.HexCoord(0, 0)))
        game.addCity(them, game.getTile(com.unciv.logic.map.HexCoord(6, 0)))
        capital.cityConstructions.addBuilding(game.createBuilding("Provides [3] [Silk]"))
        us.cache.updateCivResources()
        us.tech.addTechnology("Pottery")
        them.addGold(1000)
        val trade = Trade().apply { ourOffers.add(offered) }
        DealAi.doEqualizeDealWithAI(us, them, trade)
        return trade
    }

    private fun types(trade: Trade) = trade.theirOffers.map { it.type }

    @Test
    fun `an AI that gives a resource does not ask for gold for it with the rule on`() {
        val silk = TradeOffer("Silk", TradeOfferType.Luxury_Resource, 1, 30)
        // setup: the gold per turn of the other side pays for it, and without the rule so does gold when that is not enough or not possible
        assertTrue(TradeOfferType.Gold_Per_Turn in types(equalized(false, silk)))
        // V:CvDealAI.cpp:4535: IsPossibleToTradeItem(gold) is false while we give something temporary
        assertTrue(TradeOfferType.Gold !in types(equalized(true, silk)))
        assertTrue("gold per turn is temporary too", TradeOfferType.Gold_Per_Turn in types(equalized(true, silk)))
    }

    @Test
    fun `an AI that gives a technology does not ask for gold per turn for it with the rule on`() {
        val tech = TradeOffer("Pottery", TradeOfferType.Technology, 1, -1)
        assertTrue("setup: without the rule the gold per turn is asked first", TradeOfferType.Gold_Per_Turn in types(equalized(false, tech)))
        // V:CvDealAI.cpp:4643: IsPossibleToTradeItem(gold per turn) is false while we give something permanent
        val withRule = types(equalized(true, tech))
        assertTrue(TradeOfferType.Gold_Per_Turn !in withRule)
        assertTrue("the gold of the other side does", TradeOfferType.Gold in withRule)
    }

    //endregion
}
