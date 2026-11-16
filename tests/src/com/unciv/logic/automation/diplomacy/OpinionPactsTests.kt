package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's friendship and pact opinion scores (CvDiplomacyAI::GetDOF*Score, Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class OpinionPactsTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        d = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(6, 0)))
        game.addCity(c, game.getTile(HexCoord(0, 6)))
        game.addCity(d, game.getTile(HexCoord(-6, 0)))
        for (civ in listOf(a, b, c, d)) for (other in listOf(a, b, c, d)) if (civ != other) civ.diplomacyFunctions.makeCivilizationsMeet(other)
        for (civ in listOf(a, b, c, d)) for (personality in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(personality, 5)
    }

    @Test
    fun `shared friends count in full then less, with the most valuable friend on top, scaled by work-with willingness`() {
        for (friend in listOf(c, d)) {
            a.getDiplomacyManager(friend)!!.signDeclarationOfFriendship()
            b.getDiplomacyManager(friend)!!.signDeclarationOfFriendship()
        }
        // -20 for the first, -8 for the second
        assertEquals(-28, OpinionAi.dofWithAnyFriendScore(a, b, rules))
        // Our most valuable friend among them, no most valuable ally: the stronger of the two weights (-20)
        a.ai.diplomacy.mostValuableFriend = c.civID
        assertEquals(-48, OpinionAi.dofWithAnyFriendScore(a, b, rules))
        a.ai.setDiplomacyValue(DiplomacyPersonality.WorkWithWillingness, 10)
        assertEquals("x 1.5 at 10", -72, OpinionAi.dofWithAnyFriendScore(a, b, rules))
    }

    @Test
    fun `friendship with those we denounced counts more when it is our biggest competitor, scaled by neediness`() {
        a.getDiplomacyManager(c)!!.denounce()
        b.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        assertEquals(20, OpinionAi.dofWithAnyEnemyScore(a, b, rules))
        a.ai.diplomacy.biggestCompetitor = c.civID
        assertEquals(40, OpinionAi.dofWithAnyEnemyScore(a, b, rules))
        a.ai.setDiplomacyValue(DiplomacyPersonality.Neediness, 1)
        assertEquals("x 0.5 at 1", 20, OpinionAi.dofWithAnyEnemyScore(a, b, rules))
    }

    @Test
    fun `a past friendship level counts once the friendship is over, scaled by inverted DoF willingness`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertEquals(-30, OpinionAi.dofAcceptedScore(a, b, rules))
        a.ai.diplomacy.mostValuableFriend = b.civID
        assertEquals(-50, OpinionAi.dofAcceptedScore(a, b, rules))
        a.ai.setDiplomacyValue(DiplomacyPersonality.DoFWillingness, 1)
        assertEquals("inverted: x 1.5 at 1", -75, OpinionAi.dofAcceptedScore(a, b, rules))

        a.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        a.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.Allies
        assertEquals(-30, OpinionAi.dofAcceptedScore(a, b, rules))
    }
}
