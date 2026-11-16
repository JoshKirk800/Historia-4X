package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Part 6 of Vox Populi's SelectBestApproachTowardsMajorCiv: bankruptcy and dangerous neighbours, read through [ApproachAi.debugSink] */
@RunWith(GdxTestRunner::class)
class WarSituationApproachTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        c = civ(HexCoord(-8, 0))
        for (x in listOf(a, b, c)) for (y in listOf(a, b, c)) if (x != y) {
            if (!x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
            x.proximity[y.civID] = Proximity.Far
        }
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** What the stage starting with [stage] added to a's weights towards b */
    private fun stageDelta(stage: String): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, null, game.ruleset.ai) } finally { ApproachAi.debugSink = null }
        val index = steps.indexOfFirst { it.first.startsWith(stage) }
        assertTrue("stage $stage", index >= 0)
        return IntArray(MajorCivApproach.entries.size) { steps[index + 1].second[it] - steps[index].second[it] }
    }

    @Test
    fun `broke with nothing to gain from a war, we turn friendly`() {
        a.stats.statsForNextTurn.gold = 0f
        val delta = stageDelta("Bankruptcy")
        assertEquals("5 x bias", 25, delta[MajorCivApproach.Friendly.ordinal])
        assertEquals(-25, delta[MajorCivApproach.War.ordinal])
        a.addGold(100)
        assertEquals("with gold in the treasury, no worry", 0, stageDelta("Bankruptcy")[MajorCivApproach.Friendly.ordinal])
    }

    @Test
    fun `a powerful neighbour deters war and draws fear and guard`() {
        a.proximity[c.civID] = Proximity.Neighbors
        a.ai.warStates.militaryStrengths[c.civID] = StrengthLevel.Powerful
        val delta = stageDelta("Dangerous neighbours")
        // (proximity 2 + strength 2) x 100%: bias 5 x 4
        assertEquals(-20, delta[MajorCivApproach.War.ordinal])
        assertEquals(-20, delta[MajorCivApproach.Hostile.ordinal])
        assertEquals(20, delta[MajorCivApproach.Afraid.ordinal])
        assertEquals(20, delta[MajorCivApproach.Guarded.ordinal])
    }

    @Test
    fun `before the Renaissance, a far player out of our way draws friendliness`() {
        val delta = stageDelta("Early game strategy")
        // 2 x bias, 2 x more for a non-scientist, 1 x more for being far
        assertEquals(25, delta[MajorCivApproach.Friendly.ordinal])
        assertEquals(10, delta[MajorCivApproach.Neutral.ordinal])
        assertEquals("2 x, and 2 x more for being far", -20, delta[MajorCivApproach.Deceptive.ordinal])
        assertEquals(-10, delta[MajorCivApproach.War.ordinal])
    }

    @Test
    fun `a conqueror not yet close to victory seeks friendship with the strong`() {
        a.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Powerful
        a.ai.warStates.targetValues[b.civID] = TargetValue.Average
        val delta = stageDelta("Grand strategy: domination")
        // (powerful - average) x 2 = 4 times the bias; war also gains the flavors (boldness + work-against) / 2
        assertEquals(20, delta[MajorCivApproach.Friendly.ordinal])
        assertEquals(5 - 20, delta[MajorCivApproach.War.ordinal])
    }
}
