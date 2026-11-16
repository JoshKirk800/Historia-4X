package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's aggressors (CvTeam::DoDeclareWar), IsWantsToConquer and IsPhonyWar (Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class PhonyWarTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    /** Three majors far apart (with two, VP always wants to conquer) */
    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(30)
        a = game.addCiv()
        b = game.addCiv()
        val c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(-25, 0)))
        game.addCity(b, game.getTile(HexCoord(25, 0)))
        game.addCity(c, game.getTile(HexCoord(0, 25)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    /** b declares war on a; a's view, after its next approach update: a calm war against a bad target, approach neutral */
    private fun quietWar() {
        b.getDiplomacyManager(a)!!.declareWar()
        a.ai.diplomacy.memoryOf(b).approach = MajorCivApproach.Neutral
        a.ai.warStates.warStates[b.civID] = WarState.Calm
        a.ai.warStates.targetValues[b.civID] = TargetValue.Bad
    }

    @Test
    fun `the declarer is the aggressor, the victim is not, and peace ends it`() {
        quietWar()
        assertTrue(b.ai.diplomacy.memoryOf(a).aggressor)
        assertFalse(a.ai.diplomacy.memoryOf(b).aggressor)
        b.getDiplomacyManager(a)!!.makePeace()
        assertFalse(b.ai.diplomacy.memoryOf(a).aggressor)
    }

    @Test
    fun `a calm war with a far bad target nobody fights is phony`() {
        quietWar()
        assertFalse(WarIntentAi.wantsToConquer(a, b))
        assertTrue(WarIntentAi.isPhonyWar(a, b))
    }

    @Test
    fun `a war in which they took a city of ours is never phony`() {
        quietWar()
        a.ai.diplomacy.memoryOf(b).numCitiesCapturedBy = 1
        assertFalse(WarIntentAi.isPhonyWar(a, b))
    }
}
