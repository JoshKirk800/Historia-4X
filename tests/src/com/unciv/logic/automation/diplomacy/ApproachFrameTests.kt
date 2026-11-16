package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
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
import kotlin.random.Random

/**
 *  The frame of Vox Populi's CvDiplomacyAI::SelectBestApproachTowardsMajorCiv (Release-5.4.8) around the approach weights: the two passes
 *  of DoUpdateMajorCivApproaches, prioritization (part 9), opinion as a percentage (part 13), the approach curve (part 14) and the
 *  selection (part 15). The random part (part 11) is set to 0 so that the numbers are exact.
 */
@RunWith(GdxTestRunner::class)
class ApproachFrameTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private val war = MajorCivApproach.War.ordinal
    private val friendly = MajorCivApproach.Friendly.ordinal
    private val neutral = MajorCivApproach.Neutral.ordinal

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        rules.defines["APPROACH_RANDOM_PERCENT"] = 0f
        // Part 10's proximity multipliers at 100% and its conquest variants as the plain ones: the frame alone
        for (proximity in listOf("NEIGHBORS", "CLOSE", "FAR", "DISTANT")) {
            rules.defines["APPROACH_MULTIPLIER_PROXIMITY_$proximity"] = 100f
            rules.defines["APPROACH_WAR_PROXIMITY_$proximity"] = 100f
        }
        for (target in listOf("IMPOSSIBLE", "BAD", "AVERAGE", "FAVORABLE", "SOFT"))
            rules.defines["CONQUEST_WAR_MULTIPLIER_TARGET_$target"] = rules.defines["MAJOR_WAR_MULTIPLIER_TARGET_$target"]!!
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        c = civ(HexCoord(-8, 0))
        for ((x, y) in listOf(a to b, a to c, b to c)) x.diplomacyFunctions.makeCivilizationsMeet(y)
        game.gameInfo.turns = 10 // met on an earlier turn: not the first update
        for (other in listOf(b, c)) a.ai.diplomacy.memoryOf(other).turnMet = 1
    }

    /** A major with one city, a flat personality (every value 5) and approach biases of 5 */
    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        civ.ai.primaryVictoryPursuit = null
        civ.ai.secondaryVictoryPursuit = null
        civ.ai.setGrandStrategyAndPursuit(null)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun select(other: Civilization, strategic: Boolean, valid: List<Civilization> = listOf(other)) =
        ApproachAi.selectApproach(a, other, strategic, valid, rules, Random(0))

    private fun memory(other: Civilization) = a.ai.diplomacy.memoryOf(other)

    /** [a] sees the whole map and works out its attack targets: without a possible target, part 12 zeroes war and hostility */
    private fun withAttackTargets() {
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        a.ai.military.updateAttackTargets(a)
    }

    /** Part 14: round(0.3 x this turn + 0.7 x last turn), at least one step towards this turn */
    private fun curved(value: Int, last: Int): Int {
        var average = (0.5f + value * 0.3f + last * 0.7f).toInt()
        if (average == last && value != last) average += if (value > last) 1 else -1
        return average
    }

    @Test
    fun `the strategic pass records the scores, the final pass moves 30 percent of the way from last turn's`() {
        val last = listOf(0, 0, 0, 3000, 0, 9000, 1000)
        memory(b).approachValues = ArrayList(last)
        select(b, strategic = true)
        val strategic = memory(b).strategicApproachValues.toList()
        assertEquals("the strategic pass leaves the curve alone", last, memory(b).approachValues.toList())
        select(b, strategic = false)
        val expected = strategic.indices.map { curved(strategic[it], last[it]) }
        assertEquals(expected, memory(b).approachValues.toList())
    }

    @Test
    fun `an approach weighs less towards a player who ranks below another for it, less so for a bold leader`() {
        // b ranks above c for war in the strategic pass, so c, second, loses the war bias (5 x 100) x rank 1, scaled by the war modifier.
        // War was last turn's approach towards c (each time, as a selection replaces it): a war score high enough for the whole
        // reduction to show. The scores are compared raw, without the curve.
        fun warScore(valid: List<Civilization>): Int {
            a.ai.diplomacy.setApproach(c, MajorCivApproach.War)
            memory(c).approachValues.clear()
            select(c, strategic = false, valid = valid)
            return memory(c).approachValues[war]
        }
        withAttackTargets()
        memory(b).strategicApproachValues = arrayListOf(9000, 0, 0, 0, 0, 0, 0)
        memory(c).strategicApproachValues = arrayListOf(100, 0, 0, 0, 0, 0, 0)
        // war modifier = bias 5 + boldness 5 = 10: no scaling, the whole 500
        assertEquals(500, warScore(listOf(c)) - warScore(listOf(b, c)))

        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 9) // modifier 14: (14 - 10) x 10 = +40%, the reduction / 1.4
        // Part 10 then halves war for a bad target and doubles it in aggressive mode: within 1 of the reduction after truncation
        assertEquals(500.0 * 100 / 140, (warScore(listOf(c)) - warScore(listOf(b, c))).toDouble(), 1.0)
    }

    @Test
    fun `before crossing oceans there is no war on a player we are not close to`() {
        withAttackTargets()
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        a.proximity[b.civID] = Proximity.Far
        b.proximity[a.civID] = Proximity.Far
        select(b, strategic = true)
        assertEquals(0, memory(b).strategicApproachValues[war])
        a.proximity[b.civID] = Proximity.Close
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        select(b, strategic = true)
        assertTrue(memory(b).strategicApproachValues[war] > 0)
    }

    @Test
    fun `without a possible attack target on their cities there is no war or hostility`() {
        a.proximity[b.civID] = Proximity.Close
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        select(b, strategic = true)
        assertEquals(0, memory(b).strategicApproachValues[war])
        assertEquals(0, memory(b).strategicApproachValues[MajorCivApproach.Hostile.ordinal])
        withAttackTargets()
        assertTrue(a.ai.military.havePossibleAttackTarget(b))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        select(b, strategic = true)
        assertTrue(memory(b).strategicApproachValues[war] > 0)
    }

    @Test
    fun `a preferred attack target raises our attack multiplier, our exposure to them theirs`() {
        assertEquals(1 to 1, ApproachAi.attackMultipliers(a, b))
        withAttackTargets()
        assertTrue(a.ai.military.havePreferredAttackTarget(b))
        assertTrue(a.ai.military.isExposedToEnemy(a, null, b))
        assertEquals(2 to 2, ApproachAi.attackMultipliers(a, b))
    }

    @Test
    fun `a disliked player raises war by the opinion weight percent and lowers friendly`() {
        memory(b).approachValues.clear()
        select(b, strategic = false)
        val neutralOpinion = memory(b).approachValues.toList()
        memory(b).opinionWeight = 60 // above the competitor threshold
        memory(b).approachValues.clear()
        select(b, strategic = false)
        val disliked = memory(b).approachValues.toList()
        // war modifier 10: x (100 + 10 + 60)%; friendly: / (100 - 10 + 60)%
        assertEquals(neutralOpinion[war] * 170 / 100, disliked[war])
        assertEquals(neutralOpinion[friendly] * 100 / 150, disliked[friendly])
    }

    @Test
    fun `a liked player is approached as friend, neutral one below it, as far as the opinion permits`() {
        // Opinion -60: friendly x (100 + 10 + 60)%, neutral lowered towards one below it, at most to / (100 - 10 + 60)%: never below it
        memory(b).opinionWeight = -60
        select(b, strategic = true)
        val mild = memory(b).strategicApproachValues
        assertTrue(mild[neutral] >= mild[friendly] - 1)
        // Opinion -300: friendly leads the bad approaches, and neutral is aimed one below it
        memory(b).opinionWeight = -300
        assertEquals(MajorCivApproach.Friendly, select(b, strategic = true))
        val strong = memory(b).strategicApproachValues
        assertEquals(strong[friendly] - 1, strong[neutral])
    }

    @Test
    fun `on the first update the approach is never worse than neutral unless they provoked us`() {
        memory(b).turnMet = game.gameInfo.turns
        memory(b).opinionWeight = 200 // they would be at war with us
        assertEquals(MajorCivApproach.Neutral, select(b, strategic = false))
        b.getDiplomacyManager(a)!!.denounce() // provoked: the choice stands
        memory(b).turnMet = game.gameInfo.turns
        assertTrue(select(b, strategic = false) != MajorCivApproach.Neutral)
    }
}
