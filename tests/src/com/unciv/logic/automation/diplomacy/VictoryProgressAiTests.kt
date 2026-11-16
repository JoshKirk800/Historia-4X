package com.unciv.logic.automation.diplomacy

import com.unciv.logic.VictoryData
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.League
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Domination progress and endgame aggression after Vox Populi's CvDiplomacyAI (Release-5.4.8): GetDominationVictoryProgress,
 *  IsCloseToWorldConquest, DoUpdateCompetingForVictory, IsEndgameAggressiveTo */
@RunWith(GdxTestRunner::class)
class VictoryProgressAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.gameInfo.gameParameters.victoryTypes.add("Domination")
        a = civWithCapital(HexCoord(0, 0))
        b = civWithCapital(HexCoord(8, 0))
        c = civWithCapital(HexCoord(0, 8))
        d = civWithCapital(HexCoord(-8, 0))
    }

    private fun civWithCapital(position: HexCoord): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(position))
        return civ
    }

    @Test
    fun `progress is the share of the original capitals held, rounded down`() {
        assertEquals("one of four capitals and no might anywhere", 25, VictoryProgressAi.dominationVictoryProgress(a))
        b.cities.first().moveToCiv(a)
        assertEquals("a capture counts at once", 50, VictoryProgressAi.dominationVictoryProgress(a))
        c.cities.first().moveToCiv(a)
        assertEquals(75, VictoryProgressAi.dominationVictoryProgress(a))
    }

    @Test
    fun `progress is the share of the military might when that is larger`() {
        for (x in 1..3) game.addUnit("Swordsman", a, game.getTile(HexCoord(x, -1)))
        game.addUnit("Swordsman", b, game.getTile(HexCoord(9, 0)))
        val total = listOf(a, b, c, d).sumOf { it.ai.militaryMight() }
        assertEquals(a.ai.militaryMight() * 100 / total, VictoryProgressAi.dominationVictoryProgress(a))
        assertTrue("three of four equal units", VictoryProgressAi.dominationVictoryProgress(a) >= 74)
        assertEquals("b's capital share is larger than its might share", 25, VictoryProgressAi.dominationVictoryProgress(b))
    }

    @Test
    fun `a razed capital counts for no one and a civilization without cities adds no might`() {
        game.addUnit("Swordsman", d, game.getTile(HexCoord(-7, 0)))
        game.addUnit("Swordsman", a, game.getTile(HexCoord(1, 0)))
        d.cities.first().destroyCity(overrideSafeties = true)
        assertEquals("one of three standing capitals; d's unit is not counted, so a holds all the might", 100,
            VictoryProgressAi.dominationVictoryProgress(a))
    }

    @Test
    fun `no progress when no open victory is won by capturing the capitals`() {
        b.cities.first().moveToCiv(a)
        game.gameInfo.gameParameters.victoryTypes.remove("Domination")
        assertEquals(0, VictoryProgressAi.dominationVictoryProgress(a))
    }

    @Test
    fun `close to world conquest from the threshold of 50 up`() {
        assertEquals(50, game.ruleset.ai.define(AiDefine.CLOSE_TO_DOMINATION_VICTORY_THRESHOLD))
        assertFalse("25", VictoryProgressAi.isCloseToWorldConquest(a, game.ruleset.ai))
        b.cities.first().moveToCiv(a)
        assertEquals(50, VictoryProgressAi.dominationVictoryProgress(a))
        assertTrue("exactly the threshold", VictoryProgressAi.isCloseToWorldConquest(a, game.ruleset.ai))
        assertFalse(VictoryProgressAi.isCloseToWorldConquest(c, game.ruleset.ai))
    }
    private fun meetAll() {
        for (x in listOf(a, b, c, d)) for (y in listOf(a, b, c, d)) if (x != y && !x.knows(y)) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    @Test
    fun `endgame aggression towards a civilization follows its progress across the threshold`() {
        meetAll()
        val rules = game.ruleset.ai
        assertFalse(VictoryProgressAi.isEndgameAggressiveTo(c, a, rules))
        b.cities.first().moveToCiv(a)
        assertTrue("a holds half the capitals", VictoryProgressAi.isEndgameAggressiveTo(c, a, rules))
        assertTrue(VictoryProgressAi.isEndgameAggressiveTo(d, a, rules))
        assertFalse("never towards itself", VictoryProgressAi.isEndgameAggressiveTo(a, a, rules))
        assertFalse("not towards those far from victory", VictoryProgressAi.isEndgameAggressiveTo(a, c, rules))
        a.cities.first { it.foundingCivObject == b }.moveToCiv(b)
        assertFalse("back under the threshold", VictoryProgressAi.isEndgameAggressiveTo(c, a, rules))
    }

    @Test
    fun `only a civilization that competes for victory is endgame aggressive, and only towards those it has met`() {
        val rules = game.ruleset.ai
        assertTrue(VictoryProgressAi.isCompetingForVictory(b))
        game.addCity(b, game.getTile(HexCoord(10, 2)))
        b.cities.first { it.isOriginalCapital }.moveToCiv(a)
        assertFalse("b lost its original capital", VictoryProgressAi.isCompetingForVictory(b))
        assertFalse("c has not met a", VictoryProgressAi.isEndgameAggressiveTo(c, a, rules))
        meetAll()
        assertFalse(VictoryProgressAi.isEndgameAggressiveTo(b, a, rules))
        d.ai.diplomacy.memoryOf(c).resurrectedUs = true
        assertFalse("d was resurrected", VictoryProgressAi.isEndgameAggressiveTo(d, a, rules))
        game.gameInfo.victoryData = VictoryData(a.civName, "Domination", game.gameInfo.turns)
        assertFalse("nobody competes once somebody won", VictoryProgressAi.isEndgameAggressiveTo(c, a, rules))
    }
    @Test
    fun `science progress needs the spaceship, every tech alone stays at 78 and the Apollo Program makes it close`() {
        game.gameInfo.gameParameters.victoryTypes.add("Scientific")
        val rules = game.ruleset.ai
        for (tech in game.ruleset.technologies.keys) a.tech.addTechnology(tech)
        assertEquals(78, VictoryProgressAi.scienceVictoryProgress(a))
        assertFalse(VictoryProgressAi.isCloseToAnyVictoryCondition(a, rules))
        a.cities.first().cityConstructions.addBuilding("Apollo Program")
        // one of seven steps: the Apollo Program and the six parts
        assertEquals(78 + 21 / 7, VictoryProgressAi.scienceVictoryProgress(a))
        assertTrue(VictoryProgressAi.isCloseToAnyVictoryCondition(a, rules))
        game.gameInfo.gameParameters.victoryTypes.remove("Scientific")
        assertEquals(0, VictoryProgressAi.scienceVictoryProgress(a))
    }

    @Test
    fun `culture progress is the lowest influence over the other living majors, close from 60`() {
        game.gameInfo.gameParameters.victoryTypes.add("Cultural")
        val rules = game.ruleset.ai
        meetAll()
        for (other in listOf(b, c, d)) {
            other.totalCultureForContests = 1000
            a.culture.addInfluence(other, 700)
        }
        assertEquals(70, VictoryProgressAi.cultureVictoryProgress(a, rules))
        assertTrue(VictoryProgressAi.isCloseToAnyVictoryCondition(a, rules))
        d.totalCultureForContests = 2000
        assertEquals("the weakest hold sets it", 35, VictoryProgressAi.cultureVictoryProgress(a, rules))
        assertFalse(VictoryProgressAi.isCloseToAnyVictoryCondition(a, rules))
    }

    @Test
    fun `diplomatic progress is our share of the votes needed, at most 59 before the United Nations`() {
        game.gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        meetAll()
        a.tech.addTechnology("Printing Press")
        assertEquals("no World Congress", 0, VictoryProgressAi.diplomaticVictoryProgress(a))
        val league = League.tryFound(game.gameInfo)!!
        game.gameInfo.league = league
        val needed = league.getVotesNeededForWorldLeader()
        league.members[a.civID]!!.extraDelegates = needed / 2 - league.getDelegates(a)
        assertEquals(needed / 2 * 100 / needed, VictoryProgressAi.diplomaticVictoryProgress(a))
        league.members[a.civID]!!.extraDelegates += needed
        assertEquals("all the votes, but no United Nations yet", 59, VictoryProgressAi.diplomaticVictoryProgress(a))
        assertTrue(VictoryProgressAi.isCloseToAnyVictoryCondition(a, game.ruleset.ai))
        league.unitedNations = true
        assertEquals(79, VictoryProgressAi.diplomaticVictoryProgress(a))
    }
}
