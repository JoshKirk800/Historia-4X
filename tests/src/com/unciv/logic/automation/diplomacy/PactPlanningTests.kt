package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Proximity
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
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

/** Vox Populi's defensive pact planning: CalculateDefensivePactLimit and the pact half of DoUpdatePlanningExchanges */
@RunWith(GdxTestRunner::class)
class PactPlanningTests {
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
        for (x in all) {
            x.tech.addTechnology("Chivalry") // Enables Defensive Pacts
            for (y in all) if (x != y) x.getDiplomacyManager(y)!!.setModifier(DiplomaticModifiers.SharedEmbassies, 0f)
        }
        game.gameInfo.turns = 60
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** A friend of [a] it means to keep, of [strength] and with this strategic friendly value */
    private fun friend(other: Civilization, friendlyValue: Int, strength: StrengthLevel) {
        a.getDiplomacyManager(other)!!.signDeclarationOfFriendship()
        a.ai.diplomacy.setApproach(other, MajorCivApproach.Friendly)
        a.ai.diplomacy.setOpinion(other, MajorCivOpinion.Friend)
        a.ai.diplomacy.memoryOf(other).doFCounter = 0
        val values = ArrayList(List(MajorCivApproach.entries.size) { 0 })
        values[MajorCivApproach.Friendly.ordinal] = friendlyValue
        a.ai.diplomacy.memoryOf(other).strategicApproachValues = values
        a.ai.warStates.militaryStrengths[other.civID] = strength
        a.proximity[other.civID] = Proximity.Close
    }

    @Test
    fun `two pacts for an AI, one more per ten other majors, none with fewer than two others`() {
        assertEquals(2, PactPlanningAi.defensivePactLimit(a))
        val small = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        small.makeHexagonalMap(8)
        val x = small.addCiv()
        small.addCity(x, small.getTile(HexCoord(0, 0)))
        small.addCity(small.addCiv(), small.getTile(HexCoord(5, 0)))
        assertEquals("one other major", 0, PactPlanningAi.defensivePactLimit(x))
    }

    @Test
    fun `pacts go to the most valuable friend first, then the best scores up to the limit`() {
        // Scores: loyalty 5 + strength + close 0 + friend 10 + one landmass 5
        friend(b, 10, StrengthLevel.Average) // 30
        friend(c, 30, StrengthLevel.Strong) // 40
        friend(d, 20, StrengthLevel.Powerful) // 60
        FriendshipPlanningAi.updateFriendships(a)
        val ai = a.ai.diplomacy
        assertEquals("the best strategic friendly value", c.civID, ai.mostValuableFriend)
        assertTrue(ai.memoryOf(c).wantsDefensivePact)
        assertTrue("the best pact score next", ai.memoryOf(d).wantsDefensivePact)
        assertFalse("over the limit of two", ai.memoryOf(b).wantsDefensivePact)
        assertEquals("a better score than the most valuable friend", d.civID, ai.mostValuableAlly)

        a.ai.diplomacy.setOpinion(d, MajorCivOpinion.Enemy)
        FriendshipPlanningAi.updateFriendships(a)
        assertFalse("not with an enemy", ai.memoryOf(d).wantsDefensivePact)
        assertTrue(ai.memoryOf(b).wantsDefensivePact)
        assertEquals("no better score than the most valuable friend's", c.civID, ai.mostValuableAlly)
    }
}
