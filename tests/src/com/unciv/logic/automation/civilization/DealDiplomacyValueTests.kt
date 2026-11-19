package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.automation.strategy.AttackTarget
import com.unciv.logic.automation.strategy.MilitaryAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetArmyType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
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

/** CvDealAI::GetOpenBordersValue, GetThirdPartyPeaceValue, GetThirdPartyWarValue and the open borders predicates of CvDiplomacyAI (Community Patch) */
@RunWith(GdxTestRunner::class)
class DealDiplomacyValueTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        c = civ(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        for (x in listOf(a, b, c)) for (y in listOf(a, b, c)) if (x != y) x.ai.diplomacy.setApproach(y, MajorCivApproach.Neutral)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun openBordersOffer() = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 30)

    //region Open borders

    @Test
    fun `an AI does not offer an item to a human who would pay less than it asks`() {
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(human)
        a.ai.diplomacy.setApproach(human, MajorCivApproach.Guarded)
        val offer = TradeOffer("Pottery", TradeOfferType.Technology, 1, -1)
        val asked = DealAi.getTradeItemValue(a, human, offer, true, true, Trade())
        assertTrue(asked in 1 until DealAi.NEVER)
        assertEquals(DealAi.NEVER, DealAi.getTradeItemValue(a, human, offer, true, true, Trade(), isAiOffer = true))
    }

    @Test
    fun `we give no open borders to a civ that culture-bombed us or either side denounced, nor to one we plan war on`() {
        assertTrue(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        a.ai.diplomacy.memoryOf(b).numTimesCultureBombed = 1
        assertFalse(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        a.ai.diplomacy.memoryOf(b).numTimesCultureBombed = 0
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.Denunciation, 10)
        assertFalse(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        b.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.Denunciation)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        assertFalse(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
    }

    @Test
    fun `we give open borders to a Guarded neighbour no stronger than average, and always to a friend`() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Guarded)
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Strong
        assertFalse(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Average
        assertTrue(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Strong
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        assertTrue(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
    }

    @Test
    fun `we want open borders from a civ with more than one and a half times our cities, not from one we denounced`() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Guarded)
        // One city each: three against two
        assertTrue(DealDiplomacyValues.isWantsOpenBordersWithPlayer(a, b))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        assertTrue(DealDiplomacyValues.isWantsOpenBordersWithPlayer(a, b))
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.Denunciation, 10)
        assertFalse(DealDiplomacyValues.isWantsOpenBordersWithPlayer(a, b))
    }

    @Test
    fun `open borders we give cost 5 percent less for each enemy of ours they fight`() {
        // c is at war with both of us: one enemy of ours is fought by b
        c.getDiplomacyManager(a)!!.declareWar()
        c.getDiplomacyManager(b)!!.declareWar()
        a.ai.diplomacy.setApproach(c, MajorCivApproach.War)
        assertEquals(1, a.ai.diplomacy.numOurEnemiesAtWarWith(b))
        assertEquals(210 * 95 / 100, DealDiplomacyValues.openBordersValue(a, b, true))
    }

    //endregion

    //region Third parties

    @Test
    fun `peace we make with a third party we are winning against costs 15 per point of war score`() {
        a.getDiplomacyManager(c)!!.declareWar()
        val current = WarDamageAi.empireWarValue(c)
        c.ai.diplomacy.memoryOf(a).warValueLost = (current * 20 + 199) / 200
        val score = WarDamageAi.warScore(a, c)
        assertTrue("setup: $score", score in 19..21)
        // (100 + 15 per point) x125% for the asker, half: c is no easy target
        assertEquals((100 + score * 15) * 125 / 100 / 2, DealDiplomacyValues.thirdPartyPeaceValue(a, b, c, true))
    }

    private fun preferTarget(declarer: Civilization, target: Civilization) {
        val muster = declarer.getCapital()!!
        val attack = AttackTarget(muster, muster.getCenterTile(), target.getCapital()!!, 5, TargetArmyType.Land, 100)
        attack.preferred = true
        val field = MilitaryAi::class.java.getDeclaredField("potentialAttackTargets")
        field.isAccessible = true
        field.set(declarer.ai.military, listOf(attack))
    }

    @Test
    fun `a war we are paid to declare needs a preferred target, is scaled by WarmongerHate and refused when we fight a major`() {
        a.proximity[c.civID] = Proximity.Close
        assertNull("no preferred attack target", DealDiplomacyValues.thirdPartyWarValue(a, b, c, true))
        preferTarget(a, c)
        a.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 4)
        val four = DealDiplomacyValues.thirdPartyWarValue(a, b, c, true)
        assertNotNull(four)
        a.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 8)
        assertEquals("a basic unit's cost x WarmongerHate", four!! * 2, DealDiplomacyValues.thirdPartyWarValue(a, b, c, true))
        // x0.5 of the hate when they declare it for us: half as much
        preferTarget(b, c)
        b.proximity[c.civID] = Proximity.Close
        assertNotNull(DealDiplomacyValues.thirdPartyWarValue(a, b, c, false))
        // At war with a major that we are not winning against
        a.getDiplomacyManager(b)!!.declareWar()
        assertNull(DealDiplomacyValues.thirdPartyWarValue(a, b, c, true))
    }

    //endregion
}
