package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.strategy.WarStateUpdateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  War weariness in the war state (CvDiplomacyAI::DoUpdateWarStates, V:CvDiplomacyAI.cpp:10818-10832): against the enemy the empire is wearier of, while it is very unhappy and
 *  that weariness would cost it unhappiness, a negative war score doubles and any other is cut to 75 percent (x 75 / 100, cut toward 0). The raw score still decides the
 *  "at least 75 either way" shortcut (V:CvDiplomacyAI.cpp:10852-10859).
 *
 *  Same empire as the unhappiness tests: [a] has 37 citizens and a cached war value of 650, so a weariness of 260 against [b] is 40 percent and 5 unhappiness (the weariness
 *  of [c] is smaller, so [b] is the wearier enemy).
 */
@RunWith(GdxTestRunner::class)
class WarStateWarWearinessTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        constants.warWearinessLikeVp = true
        constants.warStateWarWearinessLikeVp = true
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        for (spot in listOf(HexCoord(0, 4), HexCoord(4, 0), HexCoord(-4, 0), HexCoord(0, -4)))
            game.addCity(a, game.getTile(spot), initialPopulation = 9)
        game.addCity(b, game.getTile(HexCoord(8, 0)))
        game.addCity(c, game.getTile(HexCoord(-8, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        a.ai.diplomacy.cachedWarValue = 650
        a.ai.diplomacy.memoryOf(b).warWeariness = 260
        a.ai.diplomacy.memoryOf(c).warWeariness = 58
        setVeryUnhappy(true)
    }

    /** The test game has the needs model: very unhappy is the approval under the very unhappy threshold, which is put just over, or under, the approval the empire has */
    private fun setVeryUnhappy(very: Boolean) {
        val approval = a.getApproval()
        constants.needs = constants.needs.copy(veryUnhappyThreshold = if (very) approval + 1 else approval)
        assertEquals("premise: the empire is very unhappy exactly when asked to be (approval $approval)", very, a.isEmpireVeryUnhappy())
    }

    @Test
    fun `a negative score doubles and any other is cut to three quarters, cut toward 0`() {
        assertEquals(-80, WarDamageAi.warStateScore(a, b, -40))
        assertEquals(-2, WarDamageAi.warStateScore(a, b, -1))
        assertEquals(0, WarDamageAi.warStateScore(a, b, 0))
        assertEquals("30 exactly", 30, WarDamageAi.warStateScore(a, b, 40))
        assertEquals("3.75 cut to 3, not rounded to 4", 3, WarDamageAi.warStateScore(a, b, 5))
        assertEquals("74.25 cut to 74", 74, WarDamageAi.warStateScore(a, b, 99))
        assertEquals("56.25 cut to 56", 56, WarDamageAi.warStateScore(a, b, 75))
    }

    @Test
    fun `only the wearier enemy's score is scaled`() {
        assertEquals("c is the enemy a is less weary of", -40, WarDamageAi.warStateScore(a, c, -40))
        assertEquals(40, WarDamageAi.warStateScore(a, c, 40))
        a.ai.diplomacy.memoryOf(c).warWeariness = 900
        assertEquals("now c is the wearier enemy", -80, WarDamageAi.warStateScore(a, c, -40))
        assertEquals("and b is not", -40, WarDamageAi.warStateScore(a, b, -40))
    }

    @Test
    fun `the score stands while the empire is not very unhappy`() {
        setVeryUnhappy(false)
        assertEquals(-40, WarDamageAi.warStateScore(a, b, -40))
        assertEquals(40, WarDamageAi.warStateScore(a, b, 40))
    }

    @Test
    fun `the score stands when the weariness would cost no unhappiness`() {
        // 5 of 650 is under one percent: no unhappiness, though b is still the wearier enemy
        a.ai.diplomacy.memoryOf(b).warWeariness = 5
        a.ai.diplomacy.memoryOf(c).warWeariness = 1
        assertEquals(0, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
        assertEquals(b, WarDamageAi.highestWarWearinessPlayer(a))
        assertEquals(-40, WarDamageAi.warStateScore(a, b, -40))
        assertEquals(40, WarDamageAi.warStateScore(a, b, 40))
    }

    @Test
    fun `without the switch, or without war weariness, the score stands`() {
        constants.warStateWarWearinessLikeVp = false
        assertEquals(-40, WarDamageAi.warStateScore(a, b, -40))
        assertEquals(40, WarDamageAi.warStateScore(a, b, 40))
        constants.warStateWarWearinessLikeVp = true
        constants.warWearinessLikeVp = false
        assertEquals("the war weariness switch is the base of it", -40, WarDamageAi.warStateScore(a, b, -40))
    }

    /** The war score of [a] against [b]: b's loss less a's, as shares of each empire's value ([WarDamageAi.warScore]); set through what each lost */
    private fun setScoreOfA(lostByB: Int, lostByA: Int) {
        a.getDiplomacyManager(b)!!.declareWar()
        b.ai.diplomacy.memoryOf(a).warValueLost = lostByB
        a.ai.diplomacy.memoryOf(b).warValueLost = lostByA
    }

    private fun warStateOfA(): WarState? {
        WarStateUpdateAi.update(a, a.ai.warStates)
        return WarStateAi.warState(a, b)
    }

    @Test
    fun `a war that is only a little won is no longer won once weariness cuts the score`() {
        // A raw score a little over the positive threshold: 30 - DiploBalance. Three quarters of it is below the threshold
        val positive = WarIntentAi.warScoreThresholdPositive(a)
        // The loser's loss is twice its empire's value in percent: b's value is read, so the raw score can be set to positive + 3
        val wantedRaw = positive + 3
        val valueOfB = WarDamageAi.empireWarValue(b)
        assertTrue("premise: b has a value to lose", valueOfB > 0)
        setScoreOfA(lostByB = (wantedRaw * valueOfB + 199) / 200, lostByA = 0)
        val raw = WarDamageAi.warScore(a, b)
        assertTrue("premise: the raw score is above the threshold ($raw, $positive)", raw > positive)
        assertTrue("premise: three quarters of it is not ($raw, $positive)", raw * 75 / 100 <= positive)
        assertEquals("with weariness: the score is under the threshold, so calm", WarState.Calm, warStateOfA())
        constants.warStateWarWearinessLikeVp = false
        assertEquals("without: over the threshold, so offensive", WarState.Offensive, warStateOfA())
    }

    @Test
    fun `a score of at least 75 either way still decides on the raw score`() {
        val valueOfB = WarDamageAi.empireWarValue(b)
        // Raw 80 (a wins) is cut to 60: under 75, so only the raw score reaches the "at least 75" shortcut. A war this far won stays offensive, and nearly won
        // needs twice the positive threshold (60 or less with the scaled score), so the scaled 60 only just keeps that. (The raw-or-scaled choice of the shortcut
        // itself cannot change a state: three quarters of 75 and more is 56 and more, past any positive threshold under 56.)
        setScoreOfA(lostByB = 80 * valueOfB / 200 + 1, lostByA = 0)
        val raw = WarDamageAi.warScore(a, b)
        assertTrue("premise: the raw score is at least 75 ($raw)", raw >= 75)
        assertTrue("premise: the scaled score is not", WarDamageAi.warStateScore(a, b, raw) < 75)
        val positive = WarIntentAi.warScoreThresholdPositive(a)
        val scaled = WarDamageAi.warStateScore(a, b, raw)
        assertTrue("premise: the scaled score ($scaled) is at least twice the positive threshold ($positive)", scaled >= positive * 2)
        assertEquals("nearly won: the raw score shortcut makes it offensive, the scaled one still doubles the threshold", WarState.NearlyWon, warStateOfA())
    }

    @Test
    fun `against an enemy with no cities the cut score is a stalemate where the plain one is offensive`() {
        // A side with no cities is in serious danger, so the serious-danger rule decides: offensive above the positive threshold, else a stalemate.
        // Only a nomad (one that never held a capital) can be alive without a city: [d] has a unit and nothing else, so its value is a unit's and a raw score
        // of positive + 3 or more needs it to have lost about that share of it
        val d = game.addCiv()
        game.addUnit("Warrior", d, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(d)
        weary(d)
        assertEquals("premise: d has no cities and is alive", 0, d.cities.size)
        assertTrue("premise: d is alive", d.isAlive())
        val positive = WarIntentAi.warScoreThresholdPositive(a)
        val valueOfD = WarDamageAi.empireWarValue(d)
        assertTrue("premise: d has a value", valueOfD > 0)
        a.getDiplomacyManager(d)!!.declareWar()
        d.ai.diplomacy.memoryOf(a).warValueLost = ((positive + 4) * valueOfD + 199) / 200
        val raw = WarDamageAi.warScore(a, d)
        assertTrue("premise: the raw score is over the threshold ($raw, $positive)", raw > positive)
        assertTrue("premise: three quarters of it is not ($raw, $positive)", raw * 75 / 100 <= positive)
        WarStateUpdateAi.update(a, a.ai.warStates)
        assertEquals("with weariness: not offensive", WarState.Stalemate, WarStateAi.warState(a, d))
        constants.warStateWarWearinessLikeVp = false
        WarStateUpdateAi.update(a, a.ai.warStates)
        assertEquals("without: offensive", WarState.Offensive, WarStateAi.warState(a, d))
    }

    /** [a] is wearier of [against] than of anyone else, so its score against it is the one that is scaled */
    private fun weary(against: Civilization) {
        a.ai.diplomacy.memoryOf(against).warWeariness = 900
    }

    @Test
    fun `a war won by about twice the threshold is only offensive once weariness cuts the score under that line`() {
        // Nearly won is twice the positive threshold. A raw score just over it, under 75, is cut to under it
        val positive = WarIntentAi.warScoreThresholdPositive(a)
        val valueOfB = WarDamageAi.empireWarValue(b)
        setScoreOfA(lostByB = ((2 * positive + 2) * valueOfB + 199) / 200, lostByA = 0)
        val raw = WarDamageAi.warScore(a, b)
        assertTrue("premise: the raw score is at least twice the threshold, under 75 ($raw, $positive)", raw >= 2 * positive && raw < 75)
        assertTrue("premise: the cut score is under twice the threshold ($raw, $positive)", raw * 75 / 100 < 2 * positive)
        assertEquals("with weariness: offensive", WarState.Offensive, warStateOfA())
        constants.warStateWarWearinessLikeVp = false
        assertEquals("without: nearly won", WarState.NearlyWon, warStateOfA())
    }

    @Test
    fun `a war that is only a little lost is troubled instead of calm once weariness doubles the score`() {
        // With no city in danger and no capture, a score between the thresholds is a calm war; under the negative threshold the danger-compared rule makes it troubled.
        // A raw score a little past half way between them doubles to under the threshold (at exactly the threshold the war would still be calm)
        val negative = WarIntentAi.warScoreThresholdNegative(a)
        val valueOfA = WarDamageAi.empireWarValue(a)
        assertTrue("premise: a has a value to lose", valueOfA > 0)
        setScoreOfA(lostByB = 0, lostByA = (-(negative / 2 - 2) * valueOfA + 199) / 200)
        val raw = WarDamageAi.warScore(a, b)
        val doubled = WarDamageAi.warStateScore(a, b, raw)
        assertEquals("premise: doubled", raw * 2, doubled)
        assertTrue("premise: the raw score is between the threshold and 0 ($raw, $negative)", raw > negative && raw < 0)
        assertTrue("premise: the doubled one is under the threshold ($doubled, $negative)", doubled < negative)
        assertEquals("with weariness: troubled", WarState.Troubled, warStateOfA())
        constants.warStateWarWearinessLikeVp = false
        assertEquals("without: calm, the score between the thresholds with no danger and no capture", WarState.Calm, warStateOfA())
    }
}
