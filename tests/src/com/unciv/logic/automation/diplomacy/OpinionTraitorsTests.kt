package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's traitor opinion scores and backstabbing timers (CvDiplomacyAI, Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class OpinionTraitorsTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(6, 0)))
        game.addCity(c, game.getTile(HexCoord(0, 6)))
        for (civ in listOf(a, b, c)) for (other in listOf(a, b, c)) if (civ != other) civ.diplomacyFunctions.makeCivilizationsMeet(other)
    }

    @Test
    fun `a friend's denouncement weighs until it is forgiven after its turns`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        b.getDiplomacyManager(a)!!.denounce()
        val memory = a.ai.diplomacy.memoryOf(b)
        assertTrue(memory.friendDenouncedUs)
        assertEquals(rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ME_FRIENDS), OpinionAi.traitorScore(a, b, rules))

        val turns = rules.define(AiDefine.FRIEND_DENOUNCED_US_TURNS_UNTIL_FORGIVEN) * DiplomacyTurn.opinionDurationPercent(a) / 100
        game.gameInfo.turns += turns - 1
        OpinionAi.testOpinionModifiers(a, listOf(b), rules)
        assertTrue("a turn before: $turns turns", memory.friendDenouncedUs)
        game.gameInfo.turns += 1
        OpinionAi.testOpinionModifiers(a, listOf(b), rules)
        assertFalse(memory.friendDenouncedUs)
        assertEquals(0, OpinionAi.traitorScore(a, b, rules))
    }

    @Test
    fun `attacking a friend makes the attacker hated by that friend's friends, twice over`() {
        // b declared war on its friend c: b is untrustworthy
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true
        a.ai.diplomacy.memoryOf(b).untrustworthy = true // and a holds it for one (UntrustworthyAi)
        val each = rules.define(AiDefine.OPINION_WEIGHT_WAR_FRIEND_EACH)
        assertEquals(each, OpinionAi.traitorScore(a, b, rules))
        a.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        assertEquals(each * rules.define(AiDefine.OPINION_WEIGHT_BETRAYED_OUR_FRIEND_MULTIPLIER) / 100, OpinionAi.traitorScore(a, b, rules))
    }
}
