package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvDiplomacyAI::DoUpdateWarStates (Release-5.4.8, CvDiplomacyAI.cpp 10646-10987) on built situations: the war score and
 *  the danger of each side's cities decide, with TROUBLED between defensive and stalemate. War states are compared by name where the old
 *  code (home front forces) has the type but not the value, and are read through [WarStateAi.doTurn].
 */
@RunWith(GdxTestRunner::class)
class WarStateUpdateTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        us = game.addCiv()
        them = game.addCiv()
        for (civ in listOf(us, them)) for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        game.addCity(us, game.tileMap[-7, 0])
        game.addCity(them, game.tileMap[7, 0])
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
    }

    /** A city taken from us three turns ago: no calm exception (VP's, after ten quiet turns) */
    private fun recentCapture() {
        game.gameInfo.turns = 20
        us.ai.diplomacy.memoryOf(them).lastCityCaptureTurn = 17
    }

    private fun state(): String? = WarStateAi.warState(us, them)?.name

    private fun update(): String? {
        WarStateAi.doTurn(us)
        return state()
    }

    /** [them] lost [percent] of the value of its empire to us: a war score of twice that for us, as VP doubles it */
    private fun theyLost(percent: Int) {
        them.ai.diplomacy.memoryOf(us).warValueLost = WarDamageAi.empireWarValue(them) * percent / 100
    }

    private fun weLost(percent: Int) {
        us.ai.diplomacy.memoryOf(them).warValueLost = WarDamageAi.empireWarValue(us) * percent / 100
    }

    /** The city in danger from its enemies: what it lost last turn is more than it has left (CvCity::isInDangerOfFalling) */
    private fun endangerCapital(civ: Civilization) {
        val capital = civ.getCapital()!!
        capital.damageTakenLastTurn = capital.health
    }

    //region The war score without any danger

    @Test
    fun `a lost war with no city in danger is troubled`() {
        weLost(15)
        val score = WarDamageAi.warScore(us, them)
        assertTrue("score $score", score <= WarIntentAi.warScoreThresholdNegative(us))
        assertEquals("Troubled", update())
        assertEquals("one troubled war does not make us lose overall", StateAllWars.Neutral, WarStateAi.stateAllWars(us))
    }

    @Test
    fun `a won war with no city in danger is offensive`() {
        theyLost(15)
        assertTrue(WarDamageAi.warScore(us, them) >= WarIntentAi.warScoreThresholdPositive(us))
        assertEquals("Offensive", update())
    }

    @Test
    fun `a quiet war is calm until a city was taken, then a stalemate`() {
        game.gameInfo.turns = 20
        assertEquals("Calm", update())
        recentCapture()
        assertEquals("Stalemate", update())
    }
    //endregion

    //region City danger

    @Test
    fun `our city in danger while we lead on points is troubled`() {
        recentCapture()
        endangerCapital(us)
        theyLost(10)
        val score = WarDamageAi.warScore(us, them)
        assertTrue("score $score", score > 0 && score < WarIntentAi.warScoreThresholdPositive(us))
        assertEquals("Troubled", update())
    }

    @Test
    fun `our city in danger and no lead is defensive, with a state of calm after ten quiet turns`() {
        recentCapture()
        endangerCapital(us)
        assertEquals("Defensive", update())
        game.gameInfo.turns = 40
        assertEquals("Calm", update())
    }

    @Test
    fun `their city about to fall is serious danger for them, a stalemate or, above the positive threshold, offensive`() {
        endangerCapital(them)
        assertEquals("Stalemate", update())
        theyLost(15)
        assertEquals("Offensive", update())
    }

    @Test
    fun `a war far ahead on points is nearly won, one far behind with a city in danger nearly lost`() {
        theyLost(30)
        assertEquals("NearlyWon", update())
        setUpAgain()
        recentCapture()
        endangerCapital(us)
        weLost(30)
        assertEquals("NearlyDefeated", update())
    }

    private fun setUpAgain() {
        them.ai.diplomacy.memoryOf(us).warValueLost = 0
        us.ai.diplomacy.memoryOf(them).warValueLost = 0
    }

    @Test
    fun `a civilization without cities is in serious danger, defensive and losing overall`() {
        val lone = game.addCiv()
        for (value in DiplomacyPersonality.entries) lone.ai.setDiplomacyValue(value, 5)
        game.addUnit("Warrior", lone, game.tileMap[0, 0])
        lone.diplomacyFunctions.makeCivilizationsMeet(them)
        lone.getDiplomacyManager(them)!!.declareWar()
        WarStateAi.doTurn(lone)
        assertEquals("Defensive", WarStateAi.warState(lone, them)?.name)
        assertEquals(StateAllWars.Losing, WarStateAi.stateAllWars(lone))
    }

    @Test
    fun `a civilization with no cities left is a serious danger to the other side, a stalemate`() {
        val lone = game.addCiv()
        game.addUnit("Warrior", lone, game.tileMap[1, 0])
        us.diplomacyFunctions.makeCivilizationsMeet(lone)
        us.getDiplomacyManager(lone)!!.declareWar()
        WarStateAi.doTurn(us)
        assertEquals("Stalemate", WarStateAi.warState(us, lone)?.name)
    }
    //endregion

    //region What troubled means to the readers

    @Test
    fun `a troubled war takes no new wars and is no phony war`() {
        us.ai.warStates.warStates[them.civID] = WarState.Troubled
        WarStateAi.updateBadShapeForWar(us, us.ai.warStates, game.ruleset.ai)
        assertTrue(WarStateAi.isNoNewWars(us))
        assertFalse(WarIntentAi.isPhonyWar(us, them))
    }

    @Test
    fun `a stalemate far away takes no new wars`() {
        us.ai.warStates.warStates[them.civID] = WarState.Stalemate
        us.addGold(1000) // not broke: a civilization with no gold and no income is in terrible shape for war
        us.proximity[them.civID] = Proximity.Distant
        them.proximity[us.civID] = Proximity.Distant
        WarStateAi.updateBadShapeForWar(us, us.ai.warStates, game.ruleset.ai)
        assertFalse(WarStateAi.isNoNewWars(us))
    }
    //endregion
}
