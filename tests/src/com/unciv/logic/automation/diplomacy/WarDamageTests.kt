package com.unciv.logic.automation.diplomacy

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

/** Vox Populi's war damage bookkeeping (CvPlayer::ApplyWarDamage, GetWarScore, the decays; CvCity::GetWarValue; Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class WarDamageTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(8, 0)))
        game.addCity(c, game.getTile(HexCoord(-8, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
    }

    @Test
    fun `a size-1 original capital is worth twice the city weight and its citizen`() {
        assertEquals((175 + 150) * 2, WarDamageAi.cityWarValue(a.cities.first()))
    }

    @Test
    fun `war damage moves the military rating, credits the winner with a common foe, and is the loser's loss`() {
        c.getDiplomacyManager(b)!!.declareWar()
        WarDamageAi.applyWarDamage(a, b, 300)
        assertEquals(1300, WarDamageAi.militaryRating(a))
        assertEquals(700, WarDamageAi.militaryRating(b))
        assertEquals(300, b.ai.diplomacy.memoryOf(a).warValueLost)
        assertTrue("c, at war with b and not with a, likes a for it", c.ai.diplomacy.memoryOf(a).commonFoeValue > 0)
    }

    @Test
    fun `the war score is the damage done less the damage taken, as shares of each empire`() {
        a.getDiplomacyManager(b)!!.declareWar()
        WarDamageAi.applyWarDamage(a, b, 100)
        val score = WarDamageAi.warScore(a, b)
        assertEquals(WarDamageAi.warDamageValue(b, a), score)
        assertEquals(-score, WarDamageAi.warScore(b, a))
        assertTrue(score > 0)
    }

    @Test
    fun `what was lost fades by a 50th a turn at war and a 10th at peace`() {
        b.ai.diplomacy.memoryOf(a).warValueLost = 1000
        b.ai.diplomacy.memoryOf(c).warValueLost = 1000
        b.getDiplomacyManager(a)!!.declareWar()
        WarDamageAi.doTurn(b)
        assertEquals(980, b.ai.diplomacy.memoryOf(a).warValueLost)
        assertEquals(900, b.ai.diplomacy.memoryOf(c).warValueLost)
    }
}
