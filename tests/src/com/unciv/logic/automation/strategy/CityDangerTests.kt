package com.unciv.logic.automation.strategy

import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's city danger (CvCity::IsInDangerFromPlayers, isInDangerOfFalling, the damage taken) and war allies (GetWarAllies) */
@RunWith(GdxTestRunner::class)
class CityDangerTests {
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
        for ((x, y) in listOf(a to b, a to c, b to c)) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    @Test
    fun `damage taken is smoothed at the turn's start, 80 percent of this turn and 20 of the last`() {
        val city = a.cities.first()
        city.damageTakenThisTurn = 50
        city.damageTakenLastTurn = 100
        CityTurnManager(city).startTurn()
        assertEquals(60, city.damageTakenLastTurn)
        assertEquals(0, city.damageTakenThisTurn)
    }

    @Test
    fun `a city falls when last turn's damage, a third more when careful, exceeds its hit points`() {
        val city = a.cities.first()
        city.health = 100
        city.damageTakenLastTurn = 90
        assertFalse(CityDangerAi.isInDangerOfFalling(city, extraCareful = false))
        assertTrue(CityDangerAi.isInDangerOfFalling(city, extraCareful = true))
    }

    @Test
    fun `an enemy army stronger than the city and its garrison endangers it, not a lone scout`() {
        b.getDiplomacyManager(a)!!.declareWar()
        val city = a.cities.first()
        city.damageTakenLastTurn = 10
        game.addUnit("Scout", b, game.getTile(HexCoord(2, 0)))
        assertFalse(CityDangerAi.isInDangerFromPlayers(city, listOf(b)))
        for (x in 1..3) game.addUnit("Swordsman", b, game.getTile(HexCoord(x, 1)))
        for (x in 1..3) game.addUnit("Catapult", b, game.getTile(HexCoord(x, 2)))
        assertTrue(CityDangerAi.isInDangerFromPlayers(city, listOf(b)))
        assertFalse("only the enemies asked about count", CityDangerAi.isInDangerFromPlayers(city, listOf(c)))
    }

    @Test
    fun `a defensive pact partner at war with them fights on our side, and on theirs in reverse`() {
        a.getDiplomacyManager(b)!!.declareWar()
        c.getDiplomacyManager(b)!!.declareWar()
        a.getDiplomacyManager(c)!!.signDefensivePact(30)
        assertEquals(listOf(c), WarAlliesAi.warAllies(a, b, includeMinors = false, reverse = false))
        assertEquals("b has no one at war with a on its side", emptyList<Civilization>(),
            WarAlliesAi.warAllies(a, b, includeMinors = false, reverse = true))
    }

    @Test
    fun `our capital about to fall weighs three times and is serious danger`() {
        a.getDiplomacyManager(b)!!.declareWar()
        val capital = a.cities.first()
        capital.health = 50
        capital.damageTakenLastTurn = 60
        val danger = CityDangerAi.comparativeDanger(a, b, listOf(a), listOf(b))
        assertEquals(3, danger.ours)
        assertTrue(danger.seriousUs)
        assertEquals(0, danger.theirs)
        assertFalse(danger.seriousThem)
    }
}
