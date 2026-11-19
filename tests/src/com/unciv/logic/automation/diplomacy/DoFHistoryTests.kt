package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's DoF levels (SetDoFAccepted, DoDenouncePlayer) and the DOF HISTORY part of SelectBestApproachTowardsMajorCiv */
@RunWith(GdxTestRunner::class)
class DoFHistoryTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        civ(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    @Test
    fun `each declaration of friendship raises both sides one level`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertEquals(DoFLevel.Friends, a.ai.diplomacy.memoryOf(b).doFLevel)
        assertEquals(DoFLevel.Friends, b.ai.diplomacy.memoryOf(a).doFLevel)
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertEquals(DoFLevel.Allies, b.ai.diplomacy.memoryOf(a).doFLevel)
    }

    @Test
    fun `denouncing a friend makes both untrustworthy and the victim remembers when`() {
        game.gameInfo.turns = 30
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        a.getDiplomacyManager(b)!!.denounce()
        assertEquals(DoFLevel.Untrustworthy, a.ai.diplomacy.memoryOf(b).doFLevel)
        assertEquals(DoFLevel.Untrustworthy, b.ai.diplomacy.memoryOf(a).doFLevel)
        assertEquals(30, b.ai.diplomacy.memoryOf(a).doFBrokenTurn)
    }

    @Test
    fun `a betrayed friendship draws war, guard and hostility by era and diplomacy`() {
        b.ai.diplomacy.memoryOf(a).doFLevel = DoFLevel.Untrustworthy
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(b, a, null, game.ruleset.ai) } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith("DoF history") }
        // bias 5 x era 1 x multiplier 1
        for (approach in listOf(MajorCivApproach.War, MajorCivApproach.Guarded, MajorCivApproach.Hostile))
            assertEquals(approach.name, 5, steps[i + 1].second[approach.ordinal] - steps[i].second[approach.ordinal])
    }
}
