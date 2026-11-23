package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.PeaceTreatyType
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.league.League
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.OriginalHappinessModel
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Decision-level checks of the CvDealAI port against the DLL formulas with the BNW XML values */
@RunWith(GdxTestRunner::class)
class DealAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        // These are the formulas of CvDealAI::GetLuxuryResourceValue without MOD_BALANCE_VP (VpDealAiTests has the Vox Populi branch); the ruleset plays the Vox Populi one since content flip F1
        game.ruleset.modOptions.constants.dealLuxuryValueLikeVp = false
        game.makeHexagonalMap(12)
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        // Meeting chooses a first approach; these tests start from a neutral one
        for (x in listOf(a, b, c)) for (y in listOf(a, b, c)) if (x != y) x.ai.diplomacy.setApproach(y, MajorCivApproach.Neutral)
    }

    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun feelings(civ: Civilization, other: Civilization, approach: MajorCivApproach, opinion: MajorCivOpinion) {
        civ.ai.diplomacy.setApproach(other, approach)
        civ.ai.diplomacy.setOpinion(other, opinion)
    }

    private fun gold(amount: Int) = TradeOffer(Constants.flatGold, TradeOfferType.Gold, amount, -1)
    private fun gpt(amount: Int, turns: Int = 30) = TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, amount, turns)

    private fun signDoF(x: Civilization, y: Civilization) {
        x.getDiplomacyManager(y)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        y.getDiplomacyManager(x)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
    }

    //region Item values

    /** CvDealAI::GetLuxuryResourceValue's surcharge for the receiver's net gold, from the share [per] of it */
    private fun withGoldSurcharge(value: Int, netGold: Int, per: Double) =
        if (netGold > 0) value + minOf((0.25 + kotlin.math.sqrt(netGold / per)).toInt(), value) else value

    private fun giveLuxury(civ: Civilization, resource: String, copies: Int = 1) {
        civ.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [$copies] [$resource]"))
        civ.cache.updateCivResources()
    }

    /** A luxury's happiness: 2 in Vox Populi (Resources.Happiness of the luxuries, CvPlayer::GetHappinessFromLuxury), which the Brave New World ruleset plays; the original's was 4 */
    private fun luxuryBase(owner: Civilization, turns: Int, luxuryHappiness: Int = 2) =
        (luxuryHappiness + owner.getDifficulty().extraHappinessPerLuxury.toInt() + DiplomacyTurn.gameEraNumber(owner)) * turns / 2

    @Test
    fun `a luxury we receive is worth its owner's happiness plus the era, times the turns over two`() {
        val silk = game.ruleset.tileResources["Silk"]!!
        giveLuxury(b, "Silk")
        // (2 + extra happiness per luxury + era) x 30 / 2, and the quarter of sqrt(net gold / 4) of our own income
        assertEquals(withGoldSurcharge(luxuryBase(b, 30), DealAi.goldRate(a), 4.0), DealAi.getResourceValue(a, b, silk, 1, 30, false))
    }

    @Test
    fun `our last copy of a luxury costs three times as much`() {
        val silk = game.ruleset.tileResources["Silk"]!!
        giveLuxury(a, "Silk")
        val base = withGoldSurcharge(luxuryBase(a, 30), DealAi.goldRate(b), 3.0)
        assertEquals(base * 3, DealAi.getResourceValue(a, b, silk, 1, 30, true))
        giveLuxury(a, "Silk")
        assertEquals("a second copy is not dear", base, DealAi.getResourceValue(a, b, silk, 1, 30, true))
    }

    @Test
    fun `an unhappy empire keeps its last copy of a luxury`() {
        // The original's happiness model ([OriginalHappinessModel]): the one number of happiness; the needs model's approval rating is tested in the last region
        OriginalHappinessModel.use(game.ruleset)
        val silk = game.ruleset.tileResources["Silk"]!!
        giveLuxury(a, "Silk")
        a.stats.happiness = -1
        assertEquals(DealAi.NEVER, DealAi.getResourceValue(a, b, silk, 1, 30, true))
        giveLuxury(a, "Silk")
        assertTrue("not the last copy", DealAi.getResourceValue(a, b, silk, 1, 30, true) < DealAi.NEVER)
    }

    @Test
    fun `a luxury an unhappy empire receives is worth twice as much, three times when very unhappy`() {
        // The original's happiness model ([OriginalHappinessModel]); the needs model's tiers, with a fourth for a super unhappy buyer, are tested in the last region
        OriginalHappinessModel.use(game.ruleset)
        val silk = game.ruleset.tileResources["Silk"]!!
        giveLuxury(b, "Silk")
        val content = DealAi.getResourceValue(a, b, silk, 1, 30, false)
        a.stats.happiness = -1
        assertEquals(content * 2, DealAi.getResourceValue(a, b, silk, 1, 30, false))
        a.stats.happiness = -10
        assertEquals(content * 3, DealAi.getResourceValue(a, b, silk, 1, 30, false))
    }

    @Test
    fun `no opinion or approach scaling applies to the value of a resource we give`() {
        val silk = game.ruleset.tileResources["Silk"]!!
        giveLuxury(a, "Silk", 3)
        val neutral = DealAi.getResourceValue(a, b, silk, 1, 30, true)
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Enemy)
        assertEquals(neutral, DealAi.getResourceValue(a, b, silk, 1, 30, true))
    }

    @Test
    fun `an embassy is worth 50 whatever the approach, and an untrustworthy civ gets none`() {
        val standard = game.ruleset.speeds["Standard"]!!.dealDuration
        val base = 50 * game.gameInfo.speed.dealDuration / standard
        val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = game.gameInfo.speed)
        val trade = Trade()
        assertEquals(base, DealAi.getTradeItemValue(a, b, offer, false, true, trade))
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals("the approach does not move an embassy", base, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // a holds b for a backstabber (UntrustworthyAi)
        a.ai.diplomacy.memoryOf(b).untrustworthy = true
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        assertEquals(base, DealAi.getTradeItemValue(a, b, offer, false, true, trade))
    }

    @Test
    fun `gold is worth the same to everybody whatever the approach and the opinion`() {
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Enemy)
        val trade = Trade()
        assertEquals(100, DealAi.getTradeItemValue(a, b, gold(100), true, true, trade))
        assertEquals(100, DealAi.getTradeItemValue(a, b, gold(100), true, false, trade))
        assertEquals(100, DealAi.getTradeItemValue(a, b, gold(100), false, true, trade))
        trade.ourOffers.add(gold(100))
        assertEquals(-100, DealAi.getDealValue(a, b, trade, true).total)
    }

    @Test
    fun `gold per turn is worth its turns less the interest, and an AI does not give away its income`() {
        val turns = 30
        val interest = 85 * turns / game.gameInfo.speed.dealDuration
        // 5 x 30 x 100 / (100 + interest)
        assertEquals(5L * turns * 100 / (100 + interest), DealAi.getTradeItemValue(a, b, gpt(5, turns), false, false, Trade()).toLong())
        assertEquals("more than our net gold less 2", DealAi.NEVER, DealAi.getTradeItemValue(a, b, gpt(1000, turns), true, false, Trade()))
        a.stats.statsForNextTurn.gold = 1000f
        assertEquals(900L * turns * 100 / (100 + interest), DealAi.getTradeItemValue(a, b, gpt(900, turns), true, false, Trade()).toLong())
    }

    @Test
    fun `peace itself is worth 20 to everyone`() {
        val peace = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10)
        assertEquals(20, DealAi.getTradeItemValue(a, b, peace, false, true, Trade()))
        assertEquals(20, DealAi.getTradeItemValue(a, b, peace, true, true, Trade()))
    }

    @Test
    fun `an item in a deal is the average of the seller's price and the buyer's, each moved by its approach`() {
        val price = technologyPrice()
        val offer = technologyOffer()
        val trade = Trade()
        assertEquals("neutral on both sides", price, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // A Friendly seller asks 80% and a neutral buyer pays that
        feelings(a, b, MajorCivApproach.Friendly, MajorCivOpinion.Neutral)
        assertEquals(price * 80 / 100, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // A Friendly buyer (125%) of a Friendly seller (80%) pays the price
        feelings(b, a, MajorCivApproach.Friendly, MajorCivOpinion.Neutral)
        assertEquals(price * 80 / 100 * 125 / 100, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // A Guarded seller (125%) to a Friendly buyer (125%): 156% is more than the 125% the buyer will pay
        feelings(a, b, MajorCivApproach.Guarded, MajorCivOpinion.Neutral)
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // A Hostile buyer pays 50%, less than the 80% a Friendly seller asks
        feelings(a, b, MajorCivApproach.Friendly, MajorCivOpinion.Neutral)
        feelings(b, a, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // Valued alone the item is worth its price
        assertEquals(price, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
    }

    @Test
    fun `a human who asks for an item that the buyer does not pay for gets the seller's price`() {
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(human)
        val price = technologyPrice()
        feelings(a, human, MajorCivApproach.Guarded, MajorCivOpinion.Neutral)
        // A Guarded seller asks 125%, the human buyer pays no more than the price: not acceptable, so the human gets the asking price
        assertEquals(price * 125 / 100, DealAi.getTradeItemValue(a, human, technologyOffer(), true, true, Trade()))
    }

    @Test
    fun `the items of a peace deal are worth what the winner thinks of them`() {
        giveLuxury(a, "Silk")
        a.getDiplomacyManager(b)!!.declareWar()
        val silk = game.ruleset.tileResources["Silk"]!!
        val offer = TradeOffer("Silk", TradeOfferType.Luxury_Resource, 1, 30)
        val trade = Trade()
        // a surrenders: b, who gets the silk, is the winner and prices it: what b would pay for it
        a.ai.diplomacy.memoryOf(b).treatyWillingToOffer = PeaceTreatyType.Capitulation
        b.ai.diplomacy.memoryOf(a).treatyWillingToOffer = PeaceTreatyType.WhitePeace
        assertEquals(DealAi.getResourceValue(b, a, silk, 1, 30, false), DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        // A white peace has no winner: the average of the price a asks for its last silk (x3) and b's, which is more than b pays
        a.ai.diplomacy.memoryOf(b).treatyWillingToOffer = PeaceTreatyType.WhitePeace
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
    }

    @Test
    fun `open borders we give are priced by our approach, and refused when we plan war on them`() {
        val offer = openBordersOffer()
        val trade = Trade()
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(610, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
        feelings(a, b, MajorCivApproach.Guarded, MajorCivOpinion.Enemy)
        assertEquals("the opinion no longer matters", 610, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Neutral)
        assertEquals(210, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
        feelings(a, b, MajorCivApproach.Afraid, MajorCivOpinion.Neutral)
        assertEquals(310, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
        feelings(a, b, MajorCivApproach.Friendly, MajorCivOpinion.Neutral)
        assertEquals(60, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
        feelings(a, b, MajorCivApproach.War, MajorCivOpinion.Neutral)
        assertEquals("not to a civ we plan war on", DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
    }

    @Test
    fun `open borders we receive are priced by proximity, halved for a one-city civ`() {
        val offer = openBordersOffer()
        a.proximity[b.civID] = Proximity.Neighbors
        // Neutral: 10 + 50, x110% for neighbours; b has one city: half
        assertEquals(66 * 50 / 100, DealAi.getTradeItemValue(a, b, offer, false, false, Trade()))
        game.addCity(b, game.getTile(HexCoord(8, 3)))
        a.proximity[b.civID] = Proximity.Neighbors
        assertEquals(66, DealAi.getTradeItemValue(a, b, offer, false, false, Trade()))
        a.proximity[b.civID] = Proximity.Distant
        assertEquals(42, DealAi.getTradeItemValue(a, b, offer, false, false, Trade()))
        // Not from a civ we denounced
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.Denunciation, 10)
        assertEquals(-DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, false, false, Trade()))
    }

    @Test
    fun `a research agreement we grant costs 100 more per era they are ahead`() {
        fun research(fromMe: Boolean) =
            DealAi.getTradeItemValue(a, b, TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, 100, 30), fromMe, false, Trade())
        assertEquals(100, research(true))
        b.tech.addTechnology("Education") // Medieval: 2 eras ahead
        assertEquals(300, research(true))
        assertEquals(100, research(false))
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(3000, research(true))
        feelings(a, b, MajorCivApproach.Afraid, MajorCivOpinion.Neutral)
        assertEquals(240, research(true))
    }

    @Test
    fun `a defensive pact is worth its pact score when wanted, twice for the most valuable ally, and no era factor`() {
        val pact = TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, 1, 30)
        fun value(fromMe: Boolean) = DealAi.getTradeItemValue(a, b, pact, fromMe, false, Trade())
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Friend)
        assertEquals("not wanted", -DealAi.NEVER, value(false))
        assertEquals("not wanted", DealAi.NEVER, value(true))
        a.ai.diplomacy.memoryOf(b).wantsDefensivePact = true
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Average
        a.proximity[b.civID] = Proximity.Neighbors
        // loyalty 0 + average strength 10 + neighbours 5 + friend 10 + one landmass 5 = 30
        assertEquals(30, value(false))
        assertEquals("a positive score costs nothing to give", 0, value(true))
        // Vox Populi's era factor (MOD_BALANCE_VP) is not part of the Community Patch
        a.tech.addTechnology("Education")
        assertEquals(30, value(false))
        a.ai.diplomacy.mostValuableAlly = b.civID
        assertEquals(60, value(false))
        a.ai.diplomacy.mostValuableAlly = null
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Enemy)
        // 10 + 5 - 100 + 5 = -80: giving one costs twice that, receiving one is worth nothing
        assertEquals(160, value(true))
        assertEquals(0, value(false))
    }

    private fun peaceWith(third: Civilization) = TradeOffer(third.civID, TradeOfferType.PeaceProposal, 1, 10)
    private fun warOn(third: Civilization) = TradeOffer(third.civID, TradeOfferType.WarDeclaration, 1, -1)

    @Test
    fun `peace we make with a third party starts at 100 and is moved by our approach to both and the easy target`() {
        fun value(fromMe: Boolean) = DealAi.getTradeItemValue(a, b, peaceWith(c), fromMe, false, Trade())
        // From us: 100 + 50 per era (Ancient: 0), our approach to b Neutral x125%, c is not an easy target: half
        assertEquals(62, value(true))
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        // Hostile asker: x600%, half
        assertEquals(300, value(true))
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Neutral)
        feelings(a, c, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        // Hostile to the enemy: x500%, then x125%, half
        assertEquals(312, value(true))
        // Denounced either way: never
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.Denunciation, 10)
        assertEquals(DealAi.NEVER, value(true))
    }

    @Test
    fun `peace they make with a third party is worth little and only for the enemies we are neutral or friendly to`() {
        fun value() = DealAi.getTradeItemValue(a, b, peaceWith(c), false, false, Trade())
        // 100 + 5 per war score point, Neutral to c: x50%, x125% for a Neutral b, half as c is no easy target for b: 31
        assertEquals(31, value())
        feelings(a, c, MajorCivApproach.Friendly, MajorCivOpinion.Neutral)
        assertEquals(125, value())
        feelings(a, c, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals("we want them to keep fighting", -DealAi.NEVER, value())
    }

    @Test
    fun `war on a third party is refused unless we have a target and every reason for it`() {
        // No preferred attack target, no 'no new wars' to speak of: we do not accept paid wars on c
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, warOn(c), true, false, Trade()))
        assertEquals(-DealAi.NEVER, DealAi.getTradeItemValue(a, b, warOn(c), false, false, Trade()))
    }

    @Test
    fun `a capital is not for sale at any price, whoever asks`() {
        val city = a.cities.first()
        val offer = TradeOffer(city.id, TradeOfferType.City, 1, -1)
        val trade = Trade()
        trade.ourOffers.add(offer)
        // CvDealAI::GetTradeItemValue: the buyer's price from GetCityValueForDeal, not scaled by the seller's projection or doubled at peace
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, false, trade))
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, offer, true, true, trade))
        assertEquals(-DealAi.NEVER, DealAi.getTradeItemValue(b, a, offer, false, false, trade.reverse()))
    }
    //endregion

    //region Gold gate and acceptance

    @Test
    fun `lump gold needs a mutual Declaration of Friendship outside peace deals`() {
        a.addGold(500)
        val trade = Trade()
        trade.ourOffers.add(gold(100))
        val available = TradeLogic(a, b).ourAvailableOffers
        assertFalse(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(100), true, available))
        assertFalse(TradeEvaluation().isTradeValid(trade, a, b))
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        assertFalse(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(100), true, available))
        signDoF(a, b)
        assertTrue(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(100), true, available))
        assertTrue(TradeEvaluation().isTradeValid(trade, a, b))
        assertFalse(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(600), true, available))
    }

    @Test
    fun `lump gold is allowed in a peace deal without friendship`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.addGold(500)
        val trade = Trade()
        trade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10))
        trade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10))
        assertTrue(DealAi.isPossibleToTradeItem(trade, a, b, gold(100), true, TradeLogic(a, b).ourAvailableOffers))
    }

    private fun technologyOffer() = TradeOffer("Pottery", TradeOfferType.Technology, 1, -1)

    private fun technologyPrice() = kotlin.math.sqrt(game.ruleset.technologies["Pottery"]!!.cost.toDouble()).toInt() * 20

    @Test
    fun `between AIs each side must receive at least 75 percent of what it gives`() {
        signDoF(a, b)
        a.addGold(500)
        b.addGold(500)
        val price = technologyPrice()
        // a gives a technology (its price to both) and receives gold: at least 75% of the price
        val trade = Trade()
        trade.ourOffers.add(technologyOffer())
        trade.theirOffers.add(gold(price))
        assertTrue(DealAi.isAcceptableToBothAIs(a, b, trade))
        trade.theirOffers.clear()
        trade.theirOffers.add(gold(price * 70 / 100))
        assertFalse(DealAi.isAcceptableToBothAIs(a, b, trade))
    }

    @Test
    fun `an AI deal within 25 percent of its even-value size is accepted as it is, and the seller's approach moves the price`() {
        signDoF(a, b)
        val price = technologyPrice()
        val trade = Trade()
        trade.ourOffers.add(technologyOffer())
        trade.theirOffers.add(gold(price))
        val even = DealAi.getDealValue(a, b, trade, true)
        assertEquals(price, even.theyreOffering)
        assertEquals(price, even.imOffering)
        assertTrue(DealAi.isAcceptableBetweenAIs(a, b, trade))
        // A Friendly seller asks 80%, and a neutral buyer pays that
        feelings(a, b, MajorCivApproach.Friendly, MajorCivOpinion.Neutral)
        assertEquals(price * 80 / 100, DealAi.getDealValue(a, b, trade, true).imOffering)
    }

    @Test
    fun `a human deal without gold is acceptable within 25 value`() {
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(human)
        a.ai.diplomacy.setApproach(human, MajorCivApproach.Neutral)
        val trade = Trade()
        // They give an embassy (50) for open borders we give (Neutral: 10 + 200): 160 apart
        trade.theirOffers.add(TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = game.gameInfo.speed))
        trade.ourOffers.add(openBordersOffer())
        assertFalse(DealAi.isDealWithHumanAcceptable(a, human, trade).acceptable)
        // Friendly: 10 + 50 = 60, 10 apart
        a.ai.diplomacy.setApproach(human, MajorCivApproach.Friendly)
        assertTrue(DealAi.isDealWithHumanAcceptable(a, human, trade).acceptable)
    }
    //endregion

    //region Peace

    /** Makes [loser] lose [winner]'s war by about [score] percent of its empire (CvPlayer::GetWarScore); returns the score that results */
    private fun loseWar(loser: Civilization, winner: Civilization, score: Int): Int {
        if (!loser.isAtWarWith(winner)) loser.getDiplomacyManager(winner)!!.declareWar()
        val current = WarDamageAi.empireWarValue(loser)
        loser.ai.diplomacy.memoryOf(winner).warValueLost = (current * score + 199) / 200
        return abs(WarDamageAi.warScore(loser, winner))
    }

    private fun Trade.goldGiven(items: TradeOffersList) = items.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount }

    @Test
    fun `the loser's gold is the war score in percent whatever the treaty`() {
        a.addGold(300 - a.gold)
        val score = loseWar(a, b, 40)
        assertTrue("setup: $score", score in 39..41)
        for (treaty in listOf(PeaceTreatyType.Armistice, PeaceTreatyType.Settlement, PeaceTreatyType.Capitulation)) {
            val trade = Trade()
            DealAi.addItemsToDealForPeaceTreaty(trade, a, b, treaty, meSurrendering = true)
            assertEquals("$treaty", 300 * score / 100, trade.goldGiven(trade.ourOffers))
            assertEquals(0, trade.goldGiven(trade.theirOffers))
        }
    }

    @Test
    fun `the winner is the one who gets the items when we are the winner`() {
        b.addGold(200 - b.gold)
        val score = loseWar(b, a, 50)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.Settlement, meSurrendering = false)
        assertEquals(200 * score / 100, trade.goldGiven(trade.theirOffers))
        assertEquals(0, trade.goldGiven(trade.ourOffers))
    }

    @Test
    fun `the fill puts the peace in the deal for both and nothing below an armistice`() {
        loseWar(a, b, 40)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.Armistice, meSurrendering = true)
        assertTrue(trade.isPeaceTreaty())
        assertTrue(trade.theirOffers.any { it.type == TradeOfferType.Treaty && it.name == Constants.peaceTreaty })
        val white = Trade()
        DealAi.addItemsToDealForPeaceTreaty(white, a, b, PeaceTreatyType.WhitePeace, meSurrendering = true)
        assertTrue(white.ourOffers.isEmpty() && white.theirOffers.isEmpty())
    }

    /** a's second city, with two of b's warriors next to it and almost no hit points: the winner is about to take it */
    private fun endangeredCity(): City {
        a.getDiplomacyManager(b)!!.declareWar()
        val second = game.addCity(a, game.getTile(HexCoord(0, 4)))
        for (tile in second.getCenterTile().neighbors.take(2)) game.addUnit("Warrior", b, tile)
        second.health = 10
        return second
    }

    @Test
    fun `a loser above 25 offers its city about to fall and the percentages use the score less 25`() {
        a.addGold(1000 - a.gold)
        val second = endangeredCity()
        val score = loseWar(a, b, 60)
        assertTrue("setup: $score", score in 58..62)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.Armistice, meSurrendering = true)
        assertEquals(listOf(second.id), trade.ourOffers.filter { it.type == TradeOfferType.City }.map { it.name })
        assertEquals(1000 * (score - 25) / 100, trade.goldGiven(trade.ourOffers))
    }

    @Test
    fun `a loser at 25 or less keeps its city, and the capital is never offered`() {
        a.addGold(1000 - a.gold)
        endangeredCity()
        val score = loseWar(a, b, 24)
        assertTrue("setup: $score", score in 23..25)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.UnconditionalSurrender, meSurrendering = true)
        assertTrue(trade.ourOffers.none { it.type == TradeOfferType.City })
        assertEquals(1000 * score / 100, trade.goldGiven(trade.ourOffers))
    }

    @Test
    fun `luxuries go above 15 as the score's share of their value, most valuable first`() {
        a.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [1] [Wine]"))
        a.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [1] [Silk]"))
        a.cache.updateCivResources()
        assertEquals(1, a.getResourceAmount("Wine"))
        val score = loseWar(a, b, 60)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.Armistice, meSurrendering = true)
        // Two equal luxuries worth 2v: 60% of it covers one
        assertEquals("score $score", 1, trade.ourOffers.count { it.type == TradeOfferType.Luxury_Resource })
        loseWar(a, b, 14)
        val low = Trade()
        DealAi.addItemsToDealForPeaceTreaty(low, a, b, PeaceTreatyType.Armistice, meSurrendering = true)
        assertEquals(0, low.ourOffers.count { it.type == TradeOfferType.Luxury_Resource })
    }

    @Test
    fun `a loser with many cities gives a city worth its score fifth of its empire when it is not about to fall`() {
        // Seven cities in all (the capital and six around b's capital, a ring each at least 3 apart)
        val center = b.getCapital()!!.getCenterTile()
        val chosen = ArrayList<com.unciv.logic.map.tile.Tile>()
        for (tile in center.getTilesAtDistance(3)) if (chosen.none { it.aerialDistanceTo(tile) < 3 }) chosen.add(tile)
        assertTrue("setup: ${chosen.size}", chosen.size >= 6)
        val ring = chosen.take(6).map { game.addCity(a, it) }
        a.addGold(1000 - a.gold)
        val score = loseWar(a, b, 100)
        assertEquals(100, score)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.Armistice, meSurrendering = true)
        val offered = trade.ourOffers.filter { it.type == TradeOfferType.City }.map { it.name }
        // 20% of seven cities' value is more than one city, less than two
        assertEquals(1, offered.size)
        assertTrue(ring.any { it.id == offered.single() })
        assertEquals(1000, trade.goldGiven(trade.ourOffers))
    }

    @Test
    fun `AIs make peace only when what each offers covers what the other accepts`() {
        a.getDiplomacyManager(b)!!.declareWar()
        val ours = a.ai.diplomacy.memoryOf(b)
        val theirs = b.ai.diplomacy.memoryOf(a)
        ours.treatyWillingToOffer = PeaceTreatyType.WhitePeace
        ours.treatyWillingToAccept = PeaceTreatyType.WhitePeace
        theirs.treatyWillingToOffer = PeaceTreatyType.WhitePeace
        theirs.treatyWillingToAccept = PeaceTreatyType.Armistice
        assertFalse(DealAi.isOfferPeace(a, b, Trade()))
        ours.treatyWillingToOffer = PeaceTreatyType.Armistice
        val trade = Trade()
        assertTrue(DealAi.isOfferPeace(a, b, trade))
        assertTrue(trade.isPeaceTreaty())
    }
    //endregion

    //region Renewal

    @Test
    fun `an ended deal with a renewable item is kept for renewal, one with an embassy is not`() {
        val renewable = Trade()
        renewable.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 0))
        renewable.theirOffers.add(gold(50))
        DealAi.onDealEnded(a, b, renewable)
        assertEquals(1, a.ai.deals.expiredDeals[b.civID]!!.size)
        val embassy = Trade()
        embassy.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 0))
        embassy.theirOffers.add(TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = game.gameInfo.speed))
        DealAi.onDealEnded(a, b, embassy)
        assertEquals(1, a.ai.deals.expiredDeals[b.civID]!!.size)
        val goldOnly = Trade()
        goldOnly.ourOffers.add(gold(50))
        assertFalse(DealAi.isPotentiallyRenewable(goldOnly))
    }
    //endregion

    //region Culture and World Congress terms

    private fun placeTenTourism(civ: Civilization) {
        val capital = civ.getCapital()!!
        val gallery = game.createBuilding("Has [5] [Art/Artifact] Great Work slots")
        capital.cityConstructions.addBuilding(gallery)
        repeat(5) {
            val work = game.createGreatWork()
            capital.greatWorks.place(GreatWorkInstance(work.name, civ.civName, "Ancient era", 1), GreatWorkSlot(capital, gallery, it))
        }
    }

    private fun openBordersOffer() = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 30)

    /** A World Congress with c's proposal of an embargo on a (a strongly likes No, strongly dislikes Yes) */
    private fun embargoOnA(): League {
        a.tech.addTechnology("Printing Press")
        val league = League.tryFound(game.gameInfo)!!
        game.gameInfo.league = league
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(c.civID).proposalsLeft = 1
        league.proposeEnact(game.ruleset.resolutions["Embargo"]!!, a.civID, c)
        return league
    }

    private fun voteOffer(league: League, choice: String, votes: Int) =
        TradeOffer(VoteCommitmentItem.name(league.proposals.single().id, choice, false), TradeOfferType.VoteCommitment, votes, -1)

    @Test
    fun `votes we receive are priced by the turns, the votes and how much we like the choice`() {
        val league = embargoOnA()
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Neutral)
        league.members.getValue(b.civID).extraDelegates = 3
        val all = league.getDelegates(b)
        val turns = game.gameInfo.speed.dealDuration
        // Strong like of No: the turns x the votes x 10; a neutral alignment changes nothing
        assertEquals(10 * all * turns, DealAi.getTradeItemValue(a, b, voteOffer(league, League.NO, all), false, false, Trade()))
        assertEquals(10 * 2 * turns, DealAi.getTradeItemValue(a, b, voteOffer(league, League.NO, 2), false, false, Trade()))
        // A choice we do not like at least weakly is worth nothing
        assertEquals(0, DealAi.getTradeItemValue(a, b, voteOffer(league, League.YES, all), false, false, Trade()))
    }

    @Test
    fun `we do not give votes on what concerns us, but refuse a hated choice or a hostile civ elsewhere`() {
        val league = embargoOnA()
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Neutral)
        // The embargo is on a: it is not asked to vote on what concerns it personally
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, voteOffer(league, League.NO, 3), true, false, Trade()))
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, b, voteOffer(league, League.YES, 1), true, false, Trade()))
    }
    //endregion

    //region The needs model (cityNeedsLikeVp): luxuries

    /** A luxury on the centre tile of a new city of [owner], connected without an improvement */
    private fun giveLuxury(world: NeedsTestWorld, owner: Civilization): TileResource {
        // the Community Patch's luxury value, as in setUp (the world has its own ruleset), and a luxury whose monopoly gives no happiness: Furs, the first, give 6 with the lone copy
        // of these maps (both are Vox Populi's, in the ruleset since content flip F1)
        world.constants.dealLuxuryValueLikeVp = false
        val resource = world.game.ruleset.tileResources.values.first { it.resourceType == ResourceType.Luxury && it.globalMonopolyUniques.none { u -> "Happiness from a global monopoly" in u } }
        val city = world.addCity(1, of = owner)
        city.getCenterTile().tileResource = resource
        city.getCenterTile().resourceAmount = 1
        for (improvement in resource.getImprovements())
            world.game.ruleset.tileImprovements[improvement]?.techRequired?.let { owner.tech.addTechnology(it) }
        owner.cache.updateCivResources()
        return resource
    }

    /** What a buyer with [happy] happiness against [unhappy] unhappy citizens (approval min(200, happy x 100 / unhappy) / 2) is told a luxury of the seller is worth */
    private fun luxuryForBuyer(happy: Int, unhappy: Int): Int {
        val world = NeedsTestWorld()
        val seller = world.game.addCiv()
        val resource = giveLuxury(world, seller)
        if (happy > 0 || unhappy > 0) NeedsAiScenarios.addEmpireCity(world, world.civ, happy, unhappy)
        return DealResourceValues.luxuryValue(world.civ, seller, resource, 30, fromMe = false, netGoldOfReceiver = 0)!!
    }

    @Test
    fun `a luxury is worth more to a very unhappy buyer`() {
        val happy = luxuryForBuyer(20, 10)  // 100
        assertTrue(happy > 0)
        assertEquals("approval 45: unhappy", 2 * happy, luxuryForBuyer(9, 10))
        assertEquals("approval 33: very unhappy", 3 * happy, luxuryForBuyer(2, 3))
        assertEquals("approval 15: super unhappy", 4 * happy, luxuryForBuyer(3, 10))
    }

    @Test
    fun `a luxury gives the happiness the needs constants say`() {
        val world = NeedsTestWorld()
        val seller = world.game.addCiv()
        val resource = giveLuxury(world, seller)
        assertEquals(2, DealResourceValues.happinessFromLuxury(seller, resource))
        world.constants.needs = world.constants.needs.copy(happinessPerLuxury = 3)
        assertEquals(3, DealResourceValues.happinessFromLuxury(seller, resource))
        world.constants.cityNeedsLikeVp = false
        assertEquals("BNW's own", 4, DealResourceValues.happinessFromLuxury(seller, resource))
    }

    @Test
    fun `an unhappy empire keeps its last copy of a luxury, with the needs model too`() {
        val world = NeedsTestWorld()
        val buyer = world.game.addCiv()
        NeedsAiScenarios.addEmpireCity(world, world.civ, 6, 10)
        val resource = giveLuxury(world, world.civ)
        NeedsAiScenarios.refresh(world.civ)
        // 6 + 2 from the luxury against 10: approval 40
        assertEquals(40, world.civ.getApproval())
        assertTrue(world.civ.isEmpireUnhappy())
        assertEquals(null, DealResourceValues.luxuryValue(world.civ, buyer, resource, 30, fromMe = true, netGoldOfReceiver = 0))

        giveLuxury(world, world.civ)
        NeedsAiScenarios.refresh(world.civ)
        assertTrue("not the last copy", DealResourceValues.luxuryValue(world.civ, buyer, resource, 30, fromMe = true, netGoldOfReceiver = 0) != null)
    }

    @Test
    fun `the last copy of a luxury is not sold when losing its happiness would make the empire unhappy`() {
        val world = NeedsTestWorld()
        val buyer = world.game.addCiv()
        NeedsAiScenarios.addEmpireCity(world, world.civ, 9, 10)
        val resource = giveLuxury(world, world.civ)
        NeedsAiScenarios.refresh(world.civ)
        // 9 + 2 from the luxury: approval 55. Without the luxury 9 x 100 / 10 / 2 = 45
        assertEquals(55, world.civ.getApproval())
        assertFalse(world.civ.isEmpireUnhappy())
        assertEquals(null, DealResourceValues.luxuryValue(world.civ, buyer, resource, 30, fromMe = true, netGoldOfReceiver = 0))

        world.giveHappiness(world.civ.cities.first(), 2)
        NeedsAiScenarios.refresh(world.civ)
        assertTrue(DealResourceValues.luxuryValue(world.civ, buyer, resource, 30, fromMe = true, netGoldOfReceiver = 0)!! > 0)
    }

    //endregion
}
