package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.ApproachAi
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvPlayer::DoTestEmpireInBadShapeForWar (Release-5.4.8) and its use in the opportunity-attack wish */
@RunWith(GdxTestRunner::class)
class BadShapeForWarTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(4, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.addGold(100)
    }

    private fun update() = WarStateAi.updateBadShapeForWar(a, a.ai.warStates, rules)

    @Test
    fun `at peace and solvent, in good shape and free to start wars`() {
        update()
        assertFalse(WarStateAi.isInTerribleShapeForWar(a))
        assertFalse(WarStateAi.isNoNewWars(a))
    }

    @Test
    fun `losing all wars is terrible shape and no new wars`() {
        a.ai.warStates.stateAllWars = StateAllWars.Losing
        update()
        assertTrue(WarStateAi.isInTerribleShapeForWar(a))
        assertTrue(WarStateAi.isNoNewWars(a))
    }

    @Test
    fun `a stalemate with a neighbour takes no new wars without being terrible shape`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.warStates.warStates[b.civID] = WarState.Stalemate
        update()
        assertFalse(WarStateAi.isInTerribleShapeForWar(a))
        assertTrue(WarStateAi.isNoNewWars(a))
    }

    @Test
    fun `doing badly against a powerful neighbour is terrible shape`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.warStates.warStates[b.civID] = WarState.Defensive
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Powerful
        update()
        assertTrue(WarStateAi.isInTerribleShapeForWar(a))
    }

    @Test
    fun `taking no new wars ends the wish for an opportunity attack`() {
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Enemy // a close enemy: a reason for an opportunity attack
        assertTrue(ApproachAi.wantsOpportunityAttack(a, b, rules))
        a.ai.warStates.noNewWars = true
        assertFalse(ApproachAi.wantsOpportunityAttack(a, b, rules))
    }
}
