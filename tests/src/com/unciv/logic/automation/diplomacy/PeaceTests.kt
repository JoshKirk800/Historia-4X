package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's peace willingness (CvDiplomacyAI::DoUpdatePeaceTreatyWillingness, GetPeaceBlockReason, Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class PeaceTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    /** Three majors far apart; b declared war on a */
    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(30)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        val c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(-25, 0)))
        game.addCity(b, game.getTile(HexCoord(25, 0)))
        game.addCity(c, game.getTile(HexCoord(0, 25)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        b.getDiplomacyManager(a)!!.declareWar()
        a.ai.diplomacy.memoryOf(b).approach = MajorCivApproach.Neutral
        a.ai.warStates.warStates[b.civID] = WarState.Calm
    }

    private fun warFor(turns: Int) { a.ai.warStates.turnsAtWar[b.civID] = turns }

    @Test
    fun `no peace with a major before 5 turns of war`() {
        warFor(4)
        assertTrue(PeaceAi.isPeaceBlocked(a, b, rules))
        warFor(5)
        assertFalse(PeaceAi.isPeaceBlocked(a, b, rules))
    }

    @Test
    fun `a war whose progress is gone after 40 turns ends in peace, on white peace terms when nobody is ahead`() {
        warFor(41)
        a.ai.diplomacy.memoryOf(b).warProgress = -10
        PeaceAi.update(a, listOf(b), rules)
        assertTrue(a.ai.diplomacy.isWantsPeaceWith(b))
        assertEquals(PeaceTreatyType.WhitePeace, a.ai.diplomacy.treatyWillingToOffer(b))
        assertEquals(PeaceTreatyType.WhitePeace, a.ai.diplomacy.treatyWillingToAccept(b))
    }

    @Test
    fun `a young war still going well is not given up`() {
        warFor(10)
        a.ai.diplomacy.memoryOf(b).warProgress = 80
        PeaceAi.update(a, listOf(b), rules)
        assertFalse(a.ai.diplomacy.isWantsPeaceWith(b))
        assertEquals(PeaceTreatyType.None, a.ai.diplomacy.treatyWillingToOffer(b))
    }
}
