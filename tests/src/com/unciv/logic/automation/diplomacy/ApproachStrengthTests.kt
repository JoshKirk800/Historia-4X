package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
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
 *  Vox Populi's CvDiplomacyAI::SelectBestApproachTowardsMajorCiv (Release-5.4.8), part 5: WARMONGER THREAT and STRENGTH COMPARED TO US.
 *  Personalities are flat (every value 5, every approach bias 5); each test reads the section's own change.
 */
@RunWith(GdxTestRunner::class)
class ApproachStrengthTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private val war = MajorCivApproach.War.ordinal
    private val hostile = MajorCivApproach.Hostile.ordinal
    private val guarded = MajorCivApproach.Guarded.ordinal
    private val afraid = MajorCivApproach.Afraid.ordinal
    private val friendly = MajorCivApproach.Friendly.ordinal
    private val neutral = MajorCivApproach.Neutral.ordinal

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(5, 0))
        civ(HexCoord(-9, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.ai.diplomacy.memoryOf(b).economicStrength = StrengthLevel.Average
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Average
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** The change made by the part 5 section of [ApproachAi.approachWeights] */
    private fun change(): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, null, rules) } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith("Warmonger threat and strength") }
        return IntArray(7) { steps[i + 1].second[it] - steps[i].second[it] }
    }

    @Test
    fun `a severe warmonger is met with war and hostility by our warmonger hate`() {
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Severe
        a.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 8)
        val change = change()
        // severe: war and hostile + bias x hate (5 x 8); the average military strength adds neutral and friendly, not war
        assertEquals(5 * 8, change[war])
        assertEquals(5 * 8, change[hostile])
        assertEquals(5 * 8, change[guarded])
        assertEquals(5 * (11 - 8) / 2, change[afraid])
    }

    @Test
    fun `a weak neighbour invites war, an immense one fear`() {
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Major // no threat terms on war
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Weak
        // military weak: war + bias x 4 with an opportunity attack, x 2 without; nothing from the average economy
        val opportunity = ApproachAi.wantsOpportunityAttack(a, b, rules)
        assertEquals(if (opportunity) 5 * 4 else 5 * 2, change()[war])
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Immense
        val immense = change()
        assertEquals(-5 * 6, immense[war])
        assertEquals(5 * 8, immense[afraid])
    }

    @Test
    fun `a rich player is feared only when also strong`() {
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Major
        a.ai.diplomacy.memoryOf(b).economicStrength = StrengthLevel.Powerful
        val richOnly = change()
        assertEquals(0, richOnly[war]) // average military: no war change
        assertEquals(5 * 2 + 5 * 2, richOnly[friendly]) // average military + powerful economy
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Powerful
        val richAndStrong = change()
        assertEquals(-5 * 4 - 5 * 4, richAndStrong[war])
        assertEquals(5 * 4 + 5 * 4, richAndStrong[afraid])
    }

    @Test
    fun `a minor warmonger who did not provoke us is met with friendliness`() {
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Minor
        val change = change()
        // minor: neutral and friendly + bias x hate / 2 (5 x 5 / 2 = 12), plus the average strength terms
        assertEquals(12 + 5 * 2 + 5, change[neutral])
        assertEquals(12 + 5 * 2 + 5, change[friendly])
    }
}
