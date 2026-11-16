package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvDiplomacyAI::SelectBestApproachTowardsMajorCiv (Release-5.4.8), part 7, VOX POPULI CHANGES: the difficulty bonus and
 *  the war bonus, with the VP handicap columns of the Deity difficulty (AggressionIncrease 16, VictoryDispute/BlockPercent 150).
 *  Personalities are flat (every value 5, every approach bias 5); each test reads the section's own change.
 */
@RunWith(GdxTestRunner::class)
class ApproachWarBonusTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private val war = MajorCivApproach.War.ordinal
    private val hostile = MajorCivApproach.Hostile.ordinal

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        rules = game.ruleset.ai
        game.gameInfo.difficulty = "Deity"
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(12, 0)) // far enough for no opportunity attack
        civ(HexCoord(-12, 0))
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

    /** The change made by the part 7 section of [ApproachAi.approachWeights] */
    private fun change(): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, null, rules) } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith("Difficulty and war bonuses") }
        return IntArray(7) { steps[i + 1].second[it] - steps[i].second[it] }
    }

    private fun opportunity() = ApproachAi.wantsOpportunityAttack(a, b, rules)

    @Test
    fun `nothing for a player we view neutrally, without warlike policies`() {
        assertEquals(0, change()[war])
    }

    @Test
    fun `the difficulty raises war against a player we view as an enemy`() {
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Enemy
        val bonus = 16 - 1 * 2 // AggressionIncrease less 2 per step above unforgivable
        val expected = 5 * bonus / 2 + if (opportunity()) 5 * bonus else 0
        assertEquals(expected, change()[war])
        assertEquals(expected, change()[hostile])
    }

    @Test
    fun `Honor and Autocracy make war better, scaled by boldness and the difficulty`() {
        a.policies.freePolicies += 2
        a.policies.adopt(a.gameInfo.ruleset.policyBranches["Honor"]!!)
        val honor = if (opportunity()) 3 else 2
        assertEquals(5 * honor * 5 * 150 / 500, change()[war])
        a.policies.adopt(a.gameInfo.ruleset.policyBranches["Autocracy"]!!, branchCompletion = true)
        val autocracy = if (opportunity()) 6 else 3
        assertEquals((5 * honor + 5 * autocracy) * 5 * 150 / 500, change()[war])
    }
}
