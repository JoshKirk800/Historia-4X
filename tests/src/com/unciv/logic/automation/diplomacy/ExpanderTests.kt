package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's DoUpdateRecklessExpanders and DoUpdateWonderSpammers */
@RunWith(GdxTestRunner::class)
class ExpanderTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(20)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(10, 0))
        c = civ(HexCoord(-10, 0))
        for (x in listOf(a, b, c)) for (y in listOf(a, b, c)) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        civ.ai.setGrandStrategyAndPursuit(null)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun others() = listOf(b, c)

    @Test
    fun `a close player with far more cities and land than the median expands recklessly`() {
        a.proximity[b.civID] = Proximity.Close
        a.ai.warStates.targetValues[b.civID] = TargetValue.Average
        ExpanderAi.updateRecklessExpanders(a, others(), game.ruleset.ai)
        assertFalse("one city", WarStateAi.isPlayerRecklessExpander(a, b))
        for (x in listOf(3, 6, 9, 12)) game.addCity(b, game.getTile(HexCoord(10, x)))
        ExpanderAi.updateRecklessExpanders(a, others(), game.ruleset.ai)
        assertTrue("5 cities against a median of 1", WarStateAi.isPlayerRecklessExpander(a, b))
        a.ai.warStates.targetValues[b.civID] = TargetValue.Difficult
        ExpanderAi.updateRecklessExpanders(a, others(), game.ruleset.ai)
        assertFalse("a difficult target is just strong", WarStateAi.isPlayerRecklessExpander(a, b))
    }

    @Test
    fun `a neighbour with far more wonders than the rest spams them`() {
        a.proximity[b.civID] = Proximity.Neighbors
        a.ai.diplomacy.wondersConstructed = 1
        c.ai.diplomacy.wondersConstructed = 1
        b.ai.diplomacy.wondersConstructed = 3
        ExpanderAi.updateWonderSpammers(a, others(), game.ruleset.ai)
        assertFalse("3 against a median of 1", a.ai.diplomacy.memoryOf(b).wonderSpammer)
        b.ai.diplomacy.wondersConstructed = 6
        ExpanderAi.updateWonderSpammers(a, others(), game.ruleset.ai)
        assertTrue(a.ai.diplomacy.memoryOf(b).wonderSpammer)
        a.proximity[b.civID] = Proximity.Far
        ExpanderAi.updateWonderSpammers(a, others(), game.ruleset.ai)
        assertFalse("too far to matter for a leader neither cultural nor conquering", a.ai.diplomacy.memoryOf(b).wonderSpammer)
    }
}
