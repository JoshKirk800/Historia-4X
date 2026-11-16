package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's friendship planning: DoUpdatePlanningExchanges (friendships), IsDoFAcceptable, IsEndDoFAcceptable, ending a DoF */
@RunWith(GdxTestRunner::class)
class FriendshipPlanningTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(20)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(10, 0))
        c = civ(HexCoord(-10, 0))
        d = civ(HexCoord(0, 10))
        val all = listOf(a, b, c, d)
        for (x in all) for (y in all) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
        game.gameInfo.turns = 60 // known for longer than the 50 turns of the Ancient era
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun strategicFriendlyValue(other: Civilization, value: Int) {
        val values = ArrayList(List(MajorCivApproach.entries.size) { 0 })
        values[MajorCivApproach.Friendly.ordinal] = value
        a.ai.diplomacy.memoryOf(other).strategicApproachValues = values
        a.ai.diplomacy.setApproach(other, MajorCivApproach.Friendly)
    }

    @Test
    fun `the friends we want are the best strategic friendly values up to the limit, the first the most valuable`() {
        strategicFriendlyValue(b, 30)
        strategicFriendlyValue(c, 20)
        strategicFriendlyValue(d, 10)
        FriendshipPlanningAi.updateFriendships(a)
        val memory = a.ai.diplomacy
        assertTrue(memory.memoryOf(b).wantsDoF)
        assertTrue(memory.memoryOf(c).wantsDoF)
        assertFalse("two friends at most with DoF willingness 5", memory.memoryOf(d).wantsDoF)
        assertEquals(b.civID, memory.mostValuableFriend)

        a.ai.setDiplomacyValue(DiplomacyPersonality.DoFWillingness, 7)
        FriendshipPlanningAi.updateFriendships(a)
        assertTrue("three with DoF willingness 7", memory.memoryOf(d).wantsDoF)

        a.ai.diplomacy.setApproach(b, MajorCivApproach.Hostile)
        FriendshipPlanningAi.updateFriendships(a)
        assertFalse("not while hostile", memory.memoryOf(b).wantsDoF)
        assertEquals(c.civID, memory.mostValuableFriend)
    }

    @Test
    fun `too early to know them, then a friendship only when we want one or are afraid`() {
        game.gameInfo.turns = 40
        a.ai.diplomacy.memoryOf(b).wantsDoF = true
        assertFalse("known 40 turns", DiplomacyAutomation.answerDoFRequest(a, b))
        game.gameInfo.turns = 60
        assertTrue(DiplomacyAutomation.answerDoFRequest(a, b))
        a.ai.diplomacy.memoryOf(b).wantsDoF = false
        assertFalse(DiplomacyAutomation.answerDoFRequest(a, b))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        assertTrue(DiplomacyAutomation.answerDoFRequest(a, b))
    }

    @Test
    fun `a friendship ends early on a low enough chance, never within 15 turns or for a friend`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship() // DoF level friends: +2
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Competitor)
        a.ai.setDiplomacyValue(DiplomacyPersonality.Loyalty, 0)
        a.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 12)
        a.ai.diplomacy.memoryOf(b).wonderDispute = DisputeLevel.Strong
        a.ai.diplomacy.memoryOf(b).doFCounter = 20
        // 10 + loyalty 0 + friends 2 - wonder competitiveness 12 = 0
        assertTrue(FriendshipPlanningAi.isEndDoFAcceptable(a, b))
        a.ai.setDiplomacyValue(DiplomacyPersonality.Loyalty, 1)
        assertFalse("a chance of 1", FriendshipPlanningAi.isEndDoFAcceptable(a, b))
        a.ai.setDiplomacyValue(DiplomacyPersonality.Loyalty, 0)
        a.ai.diplomacy.memoryOf(b).doFCounter = 15
        assertFalse("befriended 15 turns ago", FriendshipPlanningAi.isEndDoFAcceptable(a, b))
        a.ai.diplomacy.memoryOf(b).doFCounter = 20
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Friend)
        assertFalse(FriendshipPlanningAi.isEndDoFAcceptable(a, b))
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Enemy)
        a.ai.setDiplomacyValue(DiplomacyPersonality.Loyalty, 10)
        assertTrue("an enemy, whatever the chance", FriendshipPlanningAi.isEndDoFAcceptable(a, b))
    }

    @Test
    fun `ending a friendship breaks it for both, and its friends resent it`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        b.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        a.getDiplomacyManager(b)!!.endDeclarationOfFriendship()
        assertFalse(OpinionAi.isDoFAccepted(a, b))
        assertFalse(OpinionAi.isDoFAccepted(b, a))
        assertFalse("no denouncement", OpinionAi.isDenouncedPlayer(a, b))
        assertEquals(DoFLevel.Untrustworthy, a.ai.diplomacy.memoryOf(b).doFLevel)
        assertEquals(DoFLevel.Untrustworthy, b.ai.diplomacy.memoryOf(a).doFLevel)
        assertEquals(game.gameInfo.turns, b.ai.diplomacy.memoryOf(a).doFBrokenTurn)
        assertTrue(FriendshipPlanningAi.hasEndedFriendshipThisTurn(a))
        // Our assist values have VP's opposite sign: positive = resentment
        assertTrue(b.ai.diplomacy.memoryOf(a).recentAssistValue > 0)
        assertEquals("b's friend", 75, c.ai.diplomacy.memoryOf(a).recentAssistValue)
        assertEquals(0, d.ai.diplomacy.memoryOf(a).recentAssistValue)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        assertFalse("the one who broke it gets no friendship within 30 turns", FriendshipPlanningAi.isDoFAcceptable(a, b))
        b.ai.diplomacy.setApproach(a, MajorCivApproach.Afraid)
        assertTrue("the other may still accept one", FriendshipPlanningAi.isDoFAcceptable(b, a))
    }

    private fun exchangeDesireDelta(): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, MajorCivApproach.Neutral, game.ruleset.ai) } finally { ApproachAi.debugSink = null }
        val index = steps.indexOfFirst { it.first.startsWith("Exchange desires") }
        assertTrue(index >= 0)
        return IntArray(MajorCivApproach.entries.size) { steps[index + 1].second[it] - steps[index].second[it] }
    }

    @Test
    fun `a friendship we want draws the approach friendly, the most valuable friend more, unless we were deceiving them`() {
        assertEquals(0, exchangeDesireDelta()[MajorCivApproach.Friendly.ordinal])
        a.ai.diplomacy.memoryOf(b).wantsDoF = true
        assertEquals("2 x bias", 10, exchangeDesireDelta()[MajorCivApproach.Friendly.ordinal])
        a.ai.diplomacy.mostValuableFriend = b.civID
        val delta = exchangeDesireDelta()
        assertEquals("5 x bias", 25, delta[MajorCivApproach.Friendly.ordinal])
        assertTrue("war zeroed", delta[MajorCivApproach.War.ordinal] < 0)
        a.ai.diplomacy.memoryOf(b).strategicApproach = MajorCivApproach.Deceptive
        val deceiving = exchangeDesireDelta()
        assertEquals(0, deceiving[MajorCivApproach.Friendly.ordinal])
        assertEquals("3 x bias", 15, deceiving[MajorCivApproach.Deceptive.ordinal])
    }
}
