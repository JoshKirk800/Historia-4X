package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.quests.QuestManager
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Decision-level checks of the city-state part of the CvDiplomacyAI port (approaches, protection, gifts, tribute) against the DLL formulas */
@RunWith(GdxTestRunner::class)
class CityStateAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var cs: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = game.addCiv()
        for (flavor in Flavor.entries) a.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) a.ai.setDiplomacyValue(value, 0)
        for (approach in MinorCivApproach.entries) a.ai.minorCivApproachBiases[approach.name] = 5
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        cs = game.addCiv(cityStateType = "Cultured")
        cs.cityStatePersonality = CityStatePersonality.Neutral
        game.addCity(cs, game.getTile(HexCoord(6, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(cs)
    }

    private fun influence(value: Float) = cs.getDiplomacyManager(a)!!.setInfluence(value)

    //region Approaches (Vox Populi's SelectBestApproachTowardsMinorCiv)

    @Test
    fun `without a possible attack target neither war nor bullying is chosen`() {
        a.ai.minorCivApproachBiases[MinorCivApproach.Conquest.name] = 30
        a.ai.minorCivApproachBiases[MinorCivApproach.Bully.name] = 30
        a.ai.minorCivApproachBiases[MinorCivApproach.Friendly.name] = 1
        assertTrue(a.ai.military.potentialAttackTargets.isEmpty())
        val approach = CityStateAi.selectBestApproach(a, cs)
        assertTrue("got $approach", approach == MinorCivApproach.Ignore || approach == MinorCivApproach.Friendly)
    }

    @Test
    fun `with no weight at all the approach is neutral`() {
        for (approach in MinorCivApproach.entries) a.ai.minorCivApproachBiases[approach.name] = 0
        assertEquals(MinorCivApproach.Ignore, CityStateAi.selectBestApproach(a, cs))
    }

    @Test
    fun `a human player's approach is friendly with friends and neutral otherwise`() {
        a.playerType = PlayerType.Human
        CityStateAi.updateMinorCivApproaches(a)
        assertEquals(MinorCivApproach.Ignore, a.ai.minorCivApproaches[cs.civID])
        influence(30f)
        CityStateAi.updateMinorCivApproaches(a)
        assertEquals(MinorCivApproach.Friendly, a.ai.minorCivApproaches[cs.civID])
    }
    //endregion

    //region Protection

    @Test
    fun `a friendly AI pledges and an unfriendly one withdraws once the lock ends`() {
        CityStateAi.updateMinorCivProtection(a, cs, MinorCivApproach.Friendly)
        assertTrue(CityStateAi.isProtectedBy(cs, a))
        // Locked for 10 turns after pledging
        CityStateAi.updateMinorCivProtection(a, cs, MinorCivApproach.Ignore)
        assertTrue(CityStateAi.isProtectedBy(cs, a))
        cs.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.RecentlyPledgedProtection)
        CityStateAi.updateMinorCivProtection(a, cs, MinorCivApproach.Bully)
        assertFalse(CityStateAi.isProtectedBy(cs, a))
    }
    //endregion

    //region Gifts

    @Test
    fun `gift size is small for a quick boost, else large, else medium`() {
        assertEquals(250, CityStateAi.giftAmount(1500, true, 250, 500, 1000))
        assertEquals(1000, CityStateAi.giftAmount(1500, false, 250, 500, 1000))
        assertEquals(500, CityStateAi.giftAmount(900, false, 250, 500, 1000))
        assertEquals(0, CityStateAi.giftAmount(400, false, 250, 500, 1000))
        assertEquals(0, CityStateAi.giftAmount(200, true, 250, 500, 1000))
    }

    @Test
    fun `only friendly city-states get gifts`() {
        assertNull(CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Ignore, CityStateAi.GoldGift(cs)))
        assertNull(CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Bully, CityStateAi.GoldGift(cs)))
        assertTrue(CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs)) != null)
    }

    @Test
    fun `an ally is gifted only when close to losing the alliance, with a quick boost`() {
        influence(75f)
        cs.cityStateFunctions.updateAllyCivForCityState()
        assertEquals(0, CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs)))
        influence(62f)
        val gift = CityStateAi.GoldGift(cs)
        val value = CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, gift)!!
        assertTrue(value >= 100 + 250)
        assertTrue(gift.quickBoost)
    }

    @Test
    fun `a city-state we bullied recently is worth 100 less`() {
        val before = CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs))!!
        cs.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.Bullied, 20)
        assertEquals(before - 100, CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs)))
    }

    @Test
    fun `a diplomatic AI pledges to and gifts a friendly city-state the large gift`() {
        a.ai.setPersonalityFlavor(Flavor.Diplomacy, 6)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        a.addGold(1000 - a.gold)
        val influenceBefore = cs.getDiplomacyManager(a)!!.getInfluence()
        val expectedGain = cs.cityStateFunctions.influenceGainedByGift(a, 1000)
        CityStateAi.contactMinorCivs(a)
        assertTrue(CityStateAi.isProtectedBy(cs, a))
        assertEquals(0, a.gold)
        assertEquals(influenceBefore + expectedGain, cs.getDiplomacyManager(a)!!.getInfluence(), 0.01f)
    }

    @Test
    fun `without enough gold for a gift the AI saves for it`() {
        a.ai.setPersonalityFlavor(Flavor.Diplomacy, 6)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        a.addGold(100 - a.gold)
        CityStateAi.contactMinorCivs(a)
        assertEquals(100, a.gold)
        assertTrue(a.ai.purchaseSavings.isSavingForThisPurchase(com.unciv.logic.automation.strategy.PurchaseType.MinorCivGift))
    }
    //endregion

    //region Tribute

    private fun intimidate() {
        val tiles = cs.getCapital()!!.getCenterTile().getTilesAtDistance(2).filter { it.getOwner() == null }.take(3).toList()
        for (tile in tiles) game.addUnit("Musketman", a, tile)
    }

    @Test
    fun `gold tribute is wanted from a Bully city-state that is not a friend`() {
        intimidate()
        val value = CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Bully)!!
        assertTrue(value > 100)
        assertNull(CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Friendly))
        a.ai.deals.bulliedMinors.add(cs.civID)
        assertEquals(value + 20, CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Bully))
        influence(30f)
        assertEquals(0, CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Bully))
    }

    @Test
    fun `no worker is demanded from a city-state below size 4`() {
        intimidate()
        assertNull(CityStateAi.bullyUnitValue(a, cs, MinorCivApproach.Bully))
    }

    @Test
    fun `a gold-minded AI takes tribute from a Bully city-state`() {
        intimidate()
        a.ai.setPersonalityFlavor(Flavor.Gold, 6)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Bully
        val goldBefore = a.gold
        CityStateAi.contactMinorCivs(a)
        assertEquals(goldBefore + cs.cityStateFunctions.goldGainedByTribute(), a.gold)
        assertTrue(cs.civID in a.ai.deals.bulliedMinors)
    }
    //endregion

    //region Initial flavors

    @Test
    fun `a Hostile city-state's defense flavors move by up to 2, a Friendly one's go to 10`() {
        val flavors = IntArray(Flavor.count) { 5 }
        cs.cityStatePersonality = CityStatePersonality.Hostile
        CityStateAi.pickInitialFlavors(cs, flavors, Random(1))
        for (flavor in arrayOf(Flavor.CityDefense, Flavor.Defense, Flavor.Offense)) assertTrue(flavors[flavor.ordinal] in 3..7)
        assertEquals(5, flavors[Flavor.Growth.ordinal])
        val friendlyFlavors = IntArray(Flavor.count) { 5 }
        cs.cityStatePersonality = CityStatePersonality.Friendly
        CityStateAi.pickInitialFlavors(cs, friendlyFlavors, Random(1))
        for (flavor in arrayOf(Flavor.CityDefense, Flavor.Defense, Flavor.Offense)) assertEquals(10, friendlyFlavors[flavor.ordinal])
    }
    //endregion

    //region Route wish

    private fun giveRouteQuest() {
        val json = "{\"assignedQuests\":[{\"questName\":\"Route\",\"assigner\":\"${cs.civID}\",\"assignee\":\"${a.civID}\",\"assignedOnTurn\":1}]}"
        cs.questManager = json().fromJson(QuestManager::class.java, json)
        cs.questManager.setTransients(cs)
    }

    private fun contactWithApproach(approach: MinorCivApproach) {
        a.ai.minorCivApproaches[cs.civID] = approach
        CityStateAi.contactMinorCivs(a)
    }

    @Test
    fun `a friendly AI wants to connect to a neighbouring city-state that gave it a route quest`() {
        giveRouteQuest()
        assertEquals(Proximity.Neighbors, a.getProximity(cs))
        contactWithApproach(MinorCivApproach.Friendly)
        assertTrue(cs.civID in a.ai.wantsRouteToMinors)
    }

    @Test
    fun `no route wish without the route quest, with another approach, or when the city-state is not a neighbour`() {
        contactWithApproach(MinorCivApproach.Friendly)
        assertFalse("no quest", cs.civID in a.ai.wantsRouteToMinors)
        giveRouteQuest()
        contactWithApproach(MinorCivApproach.Bully)
        assertFalse("Bully", cs.civID in a.ai.wantsRouteToMinors)
        a.proximity[cs.civID] = Proximity.Close
        contactWithApproach(MinorCivApproach.Friendly)
        assertFalse("not a neighbour", cs.civID in a.ai.wantsRouteToMinors)
    }

    @Test
    fun `the route wish is dropped when the approach changes, and kept while at war with the city-state`() {
        giveRouteQuest()
        contactWithApproach(MinorCivApproach.Friendly)
        assertTrue(cs.civID in a.ai.wantsRouteToMinors)
        contactWithApproach(MinorCivApproach.Ignore)
        assertFalse(cs.civID in a.ai.wantsRouteToMinors)

        contactWithApproach(MinorCivApproach.Friendly)
        a.getDiplomacyManager(cs)!!.diplomaticStatus = DiplomaticStatus.War
        contactWithApproach(MinorCivApproach.Ignore)
        assertTrue("the original skips the update at war", cs.civID in a.ai.wantsRouteToMinors)
    }
    //endregion
}
