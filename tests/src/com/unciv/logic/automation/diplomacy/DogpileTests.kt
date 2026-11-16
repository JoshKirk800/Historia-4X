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

/** Part 6 of Vox Populi's SelectBestApproachTowardsMajorCiv, the dogpile: a friend suffering in a war with them */
@RunWith(GdxTestRunner::class)
class DogpileTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(12, 0))
        c = civ(HexCoord(-12, 0))
        civ(HexCoord(0, 12))
        for (x in listOf(a, b, c)) for (y in listOf(a, b, c)) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
        game.gameInfo.turns = 40
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** What the dogpile stage adds towards [b], by approach */
    private fun dogpile(): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, null, game.ruleset.ai) } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith("Dogpile") }
        return IntArray(steps[i].second.size) { steps[i + 1].second[it] - steps[i].second[it] }
    }

    @Test
    fun `our most valuable friend suffering in a war with them draws war, as a friend would`() {
        c.getDiplomacyManager(b)!!.declareWar()
        c.ai.diplomacy.memoryOf(b).warValueLost = 1_000_000 // the most war damage
        a.ai.diplomacy.setApproach(c, MajorCivApproach.Friendly)
        val base = dogpile()[MajorCivApproach.War.ordinal]
        a.ai.diplomacy.mostValuableFriend = c.civID
        // loyalty 5 x bias 5 / 2, our strength below powerful
        assertEquals(12, dogpile()[MajorCivApproach.War.ordinal] - base)
    }
}
