package com.unciv.logic.automation.diplomacy

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

/** Vox Populi's CvDiplomacyAI::DoMakeWarOnPlayer: when an army in place declares the war */
@RunWith(GdxTestRunner::class)
class WarDeclarationTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(10, 0))
        c = civ(HexCoord(-10, 0))
        for (x in listOf(a, b, c)) for (y in listOf(a, b, c)) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
        game.gameInfo.turns = 60
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun armyInPlace() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        a.ai.diplomacy.memoryOf(b).musteringForAttack = true
    }

    @Test
    fun `an army in place declares the war we want, even with a demand under way`() {
        armyInPlace()
        a.ai.diplomacy.demandTarget = b.civID
        a.ai.diplomacy.memoryOf(b).warGoal = WarGoal.Demand
        WarAi.doMakeWarOnPlayer(a, b, game.ruleset.ai)
        assertTrue(a.isAtWarWith(b))
        assertFalse("no longer mustering", a.ai.diplomacy.memoryOf(b).musteringForAttack)
    }

    @Test
    fun `no war while a coop war against them is still being prepared`() {
        armyInPlace()
        CoopWarAi.entry(a, c, b).state = CoopWarState.Soon
        WarAi.doMakeWarOnPlayer(a, b, game.ruleset.ai)
        assertFalse(a.isAtWarWith(b))
    }

    @Test
    fun `a show of force in place makes the demand ready`() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Guarded)
        a.ai.diplomacy.memoryOf(b).musteringForAttack = true
        a.ai.diplomacy.demandTarget = b.civID
        WarAi.doMakeWarOnPlayer(a, b, game.ruleset.ai)
        assertFalse(a.isAtWarWith(b))
        assertTrue(a.ai.diplomacy.demandReady)
    }
}
