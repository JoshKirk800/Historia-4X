package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The conquest endgame terms of the approach weights after Vox Populi's CvDiplomacyAI::SelectBestApproachTowardsMajorCiv (Release-5.4.8;
 *  `phaseE/vp-conquest-endgame-plan.md` step 4): what each stage adds, read through [ApproachAi.debugSink]. VP's personality bias is ours × 100.
 */
@RunWith(GdxTestRunner::class)
class ConquestApproachTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.gameInfo.gameParameters.victoryTypes.addAll(listOf("Domination", "Scientific"))
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        c = civ(HexCoord(0, 8))
        d = civ(HexCoord(-8, 0))
        for (x in listOf(a, b, c, d)) for (y in listOf(a, b, c, d)) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    /** A major with a city at [position]; its personality is fixed (flavors and diplomacy values 5): the leader's is randomized per game id */
    private fun civ(position: HexCoord): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(position))
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for ((approach, bias) in MajorCivApproach.entries.zip(listOf(7, 6, 5, 4, 3, 2, 1))) civ.ai.setMajorCivApproachBias(approach, bias)
        return civ
    }

    /** What the stage named [stage] added to [civ]'s weights towards [other], by approach */
    private fun stageDelta(civ: Civilization, other: Civilization, stage: String): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(civ, other, null, game.ruleset.ai) } finally { ApproachAi.debugSink = null }
        val index = steps.indexOfFirst { it.first.startsWith(stage) }
        assertTrue("stage $stage", index >= 0)
        return IntArray(MajorCivApproach.entries.size) { steps[index + 1].second[it] - steps[index].second[it] }
    }

    private fun IntArray.of(approach: MajorCivApproach) = this[approach.ordinal]

    private fun nuke(civ: Civilization) = game.addUnit("Atomic Bomb", civ, civ.cities.first().getCenterTile())

    @Test
    fun `others turn on a civilization close to world conquest`() {
        b.cities.first().moveToCiv(a)
        // Far apart: only the close-to-conquest term, not the one for conquerors close to us
        c.proximity[a.civID] = Proximity.Far
        a.proximity[c.civID] = Proximity.Far
        val delta = stageDelta(c, a, "They are close to winning")
        assertEquals("War: bias x 5", 35, delta.of(MajorCivApproach.War))
        assertEquals("Hostile: bias x 5", 30, delta.of(MajorCivApproach.Hostile))
        assertEquals("Guarded: bias x 5", 20, delta.of(MajorCivApproach.Guarded))
        assertEquals(0, delta.of(MajorCivApproach.Friendly))
    }

    @Test
    fun `a conqueror of capitals next to us in the Ancient era draws war and guard`() {
        b.cities.first().moveToCiv(a)
        c.proximity[a.civID] = Proximity.Neighbors
        val delta = stageDelta(c, a, "They are close to winning")
        // close to world conquest: bias x 5; one capital conquered, close by, in the Ancient era: bias x 3
        assertEquals(35 + 21, delta.of(MajorCivApproach.War))
        assertEquals(20 + 12, delta.of(MajorCivApproach.Guarded))
    }

    @Test
    fun `a conqueror close to victory wants the rest, more so from those holding other capitals`() {
        a.ai.grandStrategy = GrandStrategyAi.CONQUEST
        b.cities.first().moveToCiv(a)
        val flat = (a.ai.diplomacyValue(DiplomacyPersonality.Boldness) + a.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)) / 2
        // flavors, the war bias x 2 for b's capital we hold, x 5 for being close
        assertEquals(flat + 14 + 35, stageDelta(a, c, "Grand strategy: domination").of(MajorCivApproach.War))
        assertEquals("the war bias doubled by being close", 14, stageDelta(a, c, "Conquest bias").of(MajorCivApproach.War))
        c.cities.first().moveToCiv(d)
        // d holds another's capital: the war bias x 5 more for it (we must take all of them)
        assertEquals(flat + 14 + 35 + 35, stageDelta(a, d, "Grand strategy: domination").of(MajorCivApproach.War))
    }

    @Test
    fun `weapons on both sides call for caution only, not a frozen peace`() {
        nuke(a)
        nuke(b)
        val delta = stageDelta(a, b, "nukes")
        assertEquals("War untouched", 0, delta.of(MajorCivApproach.War))
        assertTrue("Guarded: bias x 2, or nothing when b is unlikely to use them", delta.of(MajorCivApproach.Guarded) in listOf(0, 8))
        assertEquals(0, delta.of(MajorCivApproach.Neutral))
    }

    @Test
    fun `weapons on our side only raise war only when an opportunity attack is wanted`() {
        nuke(a)
        assertFalse(ApproachAi.wantsOpportunityAttack(a, b, game.ruleset.ai))
        assertEquals(0, stageDelta(a, b, "nukes").of(MajorCivApproach.War))
        c.cities.first().moveToCiv(a)
        d.cities.first().moveToCiv(a)
        assertTrue("close to world conquest", ApproachAi.wantsOpportunityAttack(a, b, game.ruleset.ai))
        assertEquals("bias x 8", 56, stageDelta(a, b, "nukes").of(MajorCivApproach.War))
    }

    @Test
    fun `being nuked raises war, hostility, caution and fear`() {
        a.ai.diplomacy.memoryOf(b).numTimesNuked = 2
        val delta = stageDelta(a, b, "nukes")
        assertEquals(7, delta.of(MajorCivApproach.War))
        assertEquals(6, delta.of(MajorCivApproach.Hostile))
        assertEquals(8, delta.of(MajorCivApproach.Guarded))
        assertEquals(6, delta.of(MajorCivApproach.Afraid))
    }

    @Test
    fun `in a duel a civilization close to world conquest goes for war decisively`() {
        c.cities.first().moveToCiv(a)
        d.cities.first().moveToCiv(a)
        game.gameInfo.civilizations.remove(c)
        game.gameInfo.civilizations.remove(d)
        val delta = stageDelta(a, b, "Duel")
        assertEquals("bias x 5 for competing, 1000 for being close", 35 + 1000, delta.of(MajorCivApproach.War))
    }

    @Test
    fun `the leader with weapons on both sides picks war, and the others do not befriend it`() {
        a.ai.grandStrategy = GrandStrategyAi.CONQUEST
        b.cities.first().moveToCiv(a)
        game.gameInfo.civilizations.remove(b)
        for (civ in listOf(a, c, d)) nuke(civ)
        val rules = game.ruleset.ai
        val leader = ApproachAi.approachWeights(a, c, null, rules)
        assertEquals("a holds 2 of 3 capitals", MajorCivApproach.War.ordinal, leader.indices.maxByOrNull { leader[it] })
        for (other in listOf(c, d)) {
            val weights = ApproachAi.approachWeights(other, a, null, rules)
            assertNotEquals("${other.civName} towards a", MajorCivApproach.Friendly.ordinal, weights.indices.maxByOrNull { weights[it] })
        }
    }
    @Test
    fun `close to world conquest, a hostile approach far away is enough to prepare a war`() {
        val rules = game.ruleset.ai
        a.proximity[c.civID] = Proximity.Far
        val values = IntArray(MajorCivApproach.entries.size)
        values[MajorCivApproach.War.ordinal] = 500
        values[MajorCivApproach.Hostile.ordinal] = 800
        a.ai.diplomacy.memoryOf(c).approachValues = ArrayList(values.toList())
        a.ai.diplomacy.setApproach(c, MajorCivApproach.Hostile)
        WarTargetAi.updateWarTargets(a, listOf(b, c, d), rules)
        assertEquals("not close yet: Hostile far away is no war target", MajorCivApproach.Hostile, a.ai.diplomacy.approachTowards(c))
        b.cities.first().moveToCiv(a)
        WarTargetAi.updateWarTargets(a, listOf(c, d), rules)
        assertEquals(MajorCivApproach.War, a.ai.diplomacy.approachTowards(c))
        WarAi.doMakeWarOnPlayer(a, c, rules)
        assertEquals(WarGoal.Prepare, a.ai.diplomacy.memoryOf(c).warGoal)
    }
}
