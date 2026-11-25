package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The delegates needed to be elected World Leader (CvGame::DoUpdateDiploVictory, V:CvGame.cpp:5679-5760): what a living and a defeated civilization and city-state count (four `Defines` rows,
 * here constants), and, with `ModConstants.leagueSessionRulesLikeVp`, that a city-state counts only if its original capital is still a city.
 * The result is `floor(1.443 ln(civs) + 7 + 16.023 ln(cityStates) - 13.758)` between 2+4+1 and `2 + 4 * civs + 2 * cityStates` (the Atomic era's United Nations: 4 delegates per civilization, 2 for the host and
 * per city-state), `civs` and `cityStates` being the weighted counts. Each expected number is worked out in the test.
 */
@RunWith(GdxTestRunner::class)
class LeagueVotesNeededTests {
    private lateinit var game: TestGame
    private var cell = 0

    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        cell = 0
    }

    //region Helpers
    private fun tile() = game.tileMap.tileList[7 * cell++]

    private fun majors(count: Int) = List(count) { game.addCiv().also { game.addCity(it, tile(), replacePalace = true) } }

    private fun cityStates(count: Int) = List(count) { game.addCiv(cityStateType = "Cultured").also { game.addCity(it, tile(), replacePalace = true) } }

    /** A city-state that has fallen: it founded a capital, which [conqueror] took, so it is defeated and its original capital still stands */
    private fun fallenCityState(conqueror: Civilization): Civilization {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile()).moveToCiv(conqueror)
        return cityState
    }

    private fun needed() = League().also { it.setTransients(game.gameInfo) }.getVotesNeededForWorldLeader()
    //endregion

    @Test
    fun `a living civilization and a living city-state count their own weight`() {
        majors(4)
        cityStates(8)
        // 4 civilizations: 1.443 ln 4 + 7 = 9.00; 8 city-states: 16.023 ln 8 - 13.758 = 19.56; floor 28, the clamp is 2 + 16 + 16 = 34
        assertEquals(28, needed())
        constants.leagueWorldLeaderCivAliveWeight = 2f
        // 8 counted civilizations: 1.443 ln 8 + 7 = 10.00, so 29.56
        assertEquals(29, needed())
        constants.leagueWorldLeaderCivAliveWeight = 1f
        constants.leagueWorldLeaderCityStateAliveWeight = 1.5f
        // 12 counted city-states: 16.023 ln 12 - 13.758 = 26.06, so 35.06
        assertEquals(35, needed())
    }

    @Test
    fun `a defeated civilization counts its own weight`() {
        val civs = majors(4)
        cityStates(8)
        val defeated = game.addCiv()
        game.addCity(defeated, tile()).moveToCiv(civs[0]) // it founded a capital and lost it, so it is defeated
        // 4 living and 1 defeated at the default half: 4.5 civilizations, 1.443 ln 4.5 + 7 = 9.17, with 19.56 floor 28, no different from 4
        assertEquals(28, needed())
        constants.leagueWorldLeaderCivDefeatedWeight = 4f
        // 8 counted civilizations: 1.443 ln 8 + 7 = 10.00, so 29.56
        assertEquals(29, needed())
    }

    @Test
    fun `a defeated city-state counts its own weight`() {
        val civs = majors(4)
        cityStates(8)
        fallenCityState(civs[0])
        // 8 living and 1 defeated one, at the default half: 8.5 city-states, 16.023 ln 8.5 - 13.758 = 20.53, so 29.53
        assertEquals(29, needed())
        constants.leagueWorldLeaderCityStateDefeatedWeight = 4f
        // 12 city-states: 26.06, so 35
        assertEquals(35, needed())
    }

    @Test
    fun `the weights of Vox Populi together`() {
        constants.leagueWorldLeaderCivAliveWeight = 1.5f
        constants.leagueWorldLeaderCivDefeatedWeight = 0.75f
        constants.leagueWorldLeaderCityStateAliveWeight = 1.34f
        constants.leagueWorldLeaderCityStateDefeatedWeight = 0.75f
        val civs = majors(4)
        cityStates(8)
        game.addCity(game.addCiv(), tile()).moveToCiv(civs[0])
        fallenCityState(civs[1])
        // 4 living and 1 defeated civilization: 6.75, 1.443 ln 6.75 + 7 = 9.76; 8 living and 1 defeated city-state: 11.47, 16.023 ln 11.47 - 13.758 = 25.33; 35.09 floored, under the clamp of 2 + 24 + 22 = 48
        // (with a living civilization counted 1 it is 34, with a living city-state counted 1 it is 30, with a defeated city-state counted half 34)
        assertEquals(35, needed())
    }

    @Test
    fun `with the switch off a city-state without an original capital counts all the same`() {
        majors(4)
        cityStates(8)
        game.addCiv(cityStateType = "Cultured") // never founded a city, no unit: defeated
        // 8.5 city-states as above
        assertEquals(29, needed())
    }

    @Test
    fun `with the switch on a city-state without an original capital is not counted`() {
        constants.leagueSessionRulesLikeVp = true
        majors(4)
        cityStates(8)
        game.addCiv(cityStateType = "Cultured")
        assertEquals("as if it was not there", 28, needed())
    }

    @Test
    fun `with the switch on a defeated city-state whose capital still stands counts`() {
        constants.leagueSessionRulesLikeVp = true
        val civs = majors(4)
        cityStates(8)
        fallenCityState(civs[0])
        assertEquals("8.5 city-states", 29, needed())
    }

    @Test
    fun `with the switch on a city-state that was married or bought out is not counted`() {
        constants.leagueSessionRulesLikeVp = true
        val civs = majors(4)
        cityStates(8)
        val gone = fallenCityState(civs[0])
        // what CityStateFunctions does at a buyout or a marriage, so that it can never be liberated
        game.gameInfo.getCities().filter { it.foundingCivObject == gone }.forEach {
            it.foundingCivObject = null
            it.isOriginalCapital = false
        }
        assertEquals(28, needed())
    }
}
