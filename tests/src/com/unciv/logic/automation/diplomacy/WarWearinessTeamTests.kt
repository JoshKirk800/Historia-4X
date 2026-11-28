package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The two questions that Vox Populi's AI asks of its war weariness: whom it is wearier of (CvPlayer::GetHighestWarWearinessPlayer, V:CvPlayer.cpp:37064-37099) and how
 *  much unhappiness the weariness of one enemy would cause (CvPlayer::GetUnhappinessFromWarWearinessWithTeam, V:CvPlayer.cpp:37117-37142). Nothing calls them yet.
 *
 *  The empire's cached war value is 650, so a weariness w against an enemy is 100 w / 650 percent (58 -> 8, 130 -> 20, 260 -> 40). [a] has 37 citizens (a capital of 1 and
 *  four cities of 9): 260 gives 40 x 37 x 34 / 10000 = 5 (5.03; 4 if cut after the citizens' share or after the cap's, 6 if rounded up), 58 gives 8 x 37 x 34 / 10000 = 1
 *  (1.0064; 0, 0 and 2).
 */
@RunWith(GdxTestRunner::class)
class WarWearinessTeamTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        constants.warWearinessLikeVp = true
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        for (spot in listOf(HexCoord(0, 4), HexCoord(4, 0), HexCoord(-4, 0), HexCoord(0, -4)))
            game.addCity(a, game.getTile(spot), initialPopulation = 9)
        game.addCity(b, game.getTile(HexCoord(8, 0)))
        game.addCity(c, game.getTile(HexCoord(-8, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        a.ai.diplomacy.cachedWarValue = 650
    }

    private fun weary(against: Civilization, weariness: Int) {
        a.ai.diplomacy.memoryOf(against).warWeariness = weariness
    }

    @Test
    fun `the wearier enemy is the one with the greatest raw weariness`() {
        weary(b, 100)
        weary(c, 400)
        assertSame(c, WarDamageAi.highestWarWearinessPlayer(a))
        weary(b, 500)
        assertSame("raw weariness decides, whoever the war is with", b, WarDamageAi.highestWarWearinessPlayer(a))
    }

    @Test
    fun `an equal weariness keeps the first and a weariness of 0 is nobody`() {
        assertNull(WarDamageAi.highestWarWearinessPlayer(a))
        weary(b, 250)
        weary(c, 250)
        assertSame("b is met first: the later equal one does not replace it", b, WarDamageAi.highestWarWearinessPlayer(a))
    }

    @Test
    fun `a war that cannot end is passed over but a peace with a lock is not`() {
        weary(b, 100)
        weary(c, 400)
        a.getDiplomacyManager(c)!!.declareWar()
        a.ai.diplomacy.memoryOf(c).turnsLockedIntoWar = 3
        assertSame("the locked war with c is left out", b, WarDamageAi.highestWarWearinessPlayer(a))
        a.getDiplomacyManager(c)!!.makePeace()
        assertSame("at peace the lock does not matter", c, WarDamageAi.highestWarWearinessPlayer(a))
    }

    @Test
    fun `only a met major counts and only with the switch`() {
        val unmet = game.addCiv()
        game.addCity(unmet, game.getTile(HexCoord(0, 8)))
        weary(unmet, 900)
        assertNull("never met", WarDamageAi.highestWarWearinessPlayer(a))
        val cityState = game.addCiv(cityStateType = "Militaristic")
        game.addCity(cityState, game.getTile(HexCoord(5, 5)))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        weary(cityState, 900)
        assertNull("a city-state is not a major", WarDamageAi.highestWarWearinessPlayer(a))
        weary(b, 100)
        assertSame(b, WarDamageAi.highestWarWearinessPlayer(a))
        constants.warWearinessLikeVp = false
        assertNull(WarDamageAi.highestWarWearinessPlayer(a))
    }

    @Test
    fun `the percent is still that of the wearier enemy`() {
        weary(b, 130)
        weary(c, 260)
        assertEquals(40, WarDamageAi.highestWarWearinessPercent(a))
        assertSame(c, WarDamageAi.highestWarWearinessPlayer(a))
    }

    @Test
    fun `the unhappiness is the enemy's own percent of the citizens times 34 over 100, in one product cut once`() {
        weary(b, 260)
        weary(c, 58)
        assertEquals("40 x 37 x 34 / 10000 = 5.03: not 4 and not 6", 5, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
        assertEquals("8 x 37 x 34 / 10000 = 1.0064: not 0 and not 2, and not b's", 1, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, c))
        weary(b, 2000)
        assertEquals("the percent stops at 100: 100 x 37 x 34 / 10000 = 12.58", 12, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
    }

    @Test
    fun `a cap of the constant, 0 or more`() {
        weary(b, 650)
        constants.warWeariness = constants.warWeariness.copy(populationPercentCap = 50)
        assertEquals("100 x 37 x 50 / 10000 = 18.5", 18, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
        constants.warWeariness = constants.warWeariness.copy(populationPercentCap = -20)
        assertEquals(0, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
    }

    @Test
    fun `a dead or non-major enemy and no switch give nothing`() {
        val dead = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(dead)
        weary(dead, 650)
        assertTrue("the test needs a dead civilization", dead.isDefeated())
        assertEquals("its weariness fades whether or not there is peace", 0, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, dead))
        val cityState = game.addCiv(cityStateType = "Militaristic")
        game.addCity(cityState, game.getTile(HexCoord(5, 5)))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        weary(cityState, 650)
        assertEquals(0, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, cityState))
        weary(b, 650)
        assertEquals(12, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
        constants.warWearinessLikeVp = false
        assertEquals(0, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
    }

    @Test
    fun `the unhappiness counts the enemy even when peace cannot be made`() {
        weary(b, 260)
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.diplomacy.memoryOf(b).turnsLockedIntoWar = 3
        assertNull("the player pick passes the locked war over", WarDamageAi.highestWarWearinessPlayer(a))
        assertEquals("but the unhappiness of that enemy is asked for by name", 5, WarDamageAi.unhappinessFromWarWearinessWithTeam(a, b))
    }
}
