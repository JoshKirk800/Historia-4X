package com.unciv.logic.automation.diplomacy

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

/** Part 7 of Vox Populi's SelectBestApproachTowardsMajorCiv: UNIQUE UNITS, read through [ApproachAi.debugSink] */
@RunWith(GdxTestRunner::class)
class UniqueUnitApproachTests {
    private lateinit var game: TestGame
    private lateinit var greece: Civilization
    private lateinit var other: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        greece = flat(game.addCiv(game.ruleset.nations["Greece"]!!), HexCoord(0, 0))
        other = flat(game.addCiv(), HexCoord(8, 0))
        greece.diplomacyFunctions.makeCivilizationsMeet(other)
        greece.proximity[other.civID] = Proximity.Far
        other.proximity[greece.civID] = Proximity.Far
    }

    private fun flat(civ: Civilization, at: HexCoord): Civilization {
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun delta(): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(greece, other, null, game.ruleset.ai) } finally { ApproachAi.debugSink = null }
        val index = steps.indexOfFirst { it.first.startsWith("Unique units") }
        assertTrue(index >= 0)
        return IntArray(MajorCivApproach.entries.size) { steps[index + 1].second[it] - steps[index].second[it] }
    }

    @Test
    fun `with the hoplite coming deceive, with its tech hurry, with it in the field strike`() {
        greece.tech.techsToResearch.add("Bronze Working")
        assertEquals("researching its tech: deception 2 x bias", 10, delta()[MajorCivApproach.Deceptive.ordinal])
        greece.tech.addTechnology("Bronze Working")
        val hurry = delta()
        assertEquals("the tech without the unit: deception 5 x", 25, hurry[MajorCivApproach.Deceptive.ordinal])
        assertEquals("and war 2 x", 10, hurry[MajorCivApproach.War.ordinal])
        game.addUnit("Hoplite", greece, greece.cities.first().getCenterTile())
        val strike = delta()
        assertEquals("in the field: war 3 x", 15, strike[MajorCivApproach.War.ordinal])
        assertEquals(15, strike[MajorCivApproach.Hostile.ordinal])
        assertEquals(0, strike[MajorCivApproach.Deceptive.ordinal])
    }
}
