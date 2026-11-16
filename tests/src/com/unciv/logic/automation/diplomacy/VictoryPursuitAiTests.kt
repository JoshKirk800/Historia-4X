package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's victory pursuits (CvDiplomacyAI::SelectDefaultVictoryPursuits, DoUpdateCurrentVictoryPursuit; Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class VictoryPursuitAiTests {
    private lateinit var game: TestGame
    private lateinit var greece: Civilization
    private lateinit var mongolia: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.gameInfo.gameParameters.victoryTypes.addAll(listOf("Domination", "Scientific", "Cultural", "Diplomatic"))
        greece = game.addCiv(game.ruleset.nations["Greece"]!!)
        mongolia = game.addCiv(game.ruleset.nations["Mongolia"]!!)
        game.addCity(greece, game.getTile(HexCoord(0, 0)))
        game.addCity(mongolia, game.getTile(HexCoord(8, 0)))
    }

    private fun IntArray.of(pursuit: VictoryPursuit) = this[pursuit.ordinal]

    @Test
    fun `a lone primary hint weighs 16, a primary with an open secondary 10 and the secondary 6`() {
        val none = VictoryPursuitAi.victoryScores(mongolia, null, null)
        val alone = VictoryPursuitAi.victoryScores(mongolia, VictoryPursuit.Domination, null)
        assertEquals(16, alone.of(VictoryPursuit.Domination) - none.of(VictoryPursuit.Domination))
        val both = VictoryPursuitAi.victoryScores(mongolia, VictoryPursuit.Diplomacy, VictoryPursuit.Domination)
        assertEquals(10, both.of(VictoryPursuit.Diplomacy) - none.of(VictoryPursuit.Diplomacy))
        assertEquals(6, both.of(VictoryPursuit.Domination) - none.of(VictoryPursuit.Domination))
    }

    @Test
    fun `a closed victory costs its pursuit 20, and a closed primary hands the primary weight to an open secondary`() {
        val open = VictoryPursuitAi.victoryScores(mongolia, null, null)
        game.gameInfo.gameParameters.victoryTypes.remove("Diplomatic")
        val closed = VictoryPursuitAi.victoryScores(mongolia, null, null)
        assertEquals(-20, closed.of(VictoryPursuit.Diplomacy) - open.of(VictoryPursuit.Diplomacy))
        val hinted = VictoryPursuitAi.victoryScores(mongolia, VictoryPursuit.Diplomacy, VictoryPursuit.Domination)
        assertEquals(10, hinted.of(VictoryPursuit.Domination) - closed.of(VictoryPursuit.Domination))
        assertEquals(10, hinted.of(VictoryPursuit.Diplomacy) - closed.of(VictoryPursuit.Diplomacy))
    }

    @Test
    fun `fewer city-states than majors cost the diplomatic pursuit 10, half as many or fewer 20`() {
        fun diplomacy() = VictoryPursuitAi.victoryScores(mongolia, null, null).of(VictoryPursuit.Diplomacy)
        game.addCiv(cityStateType = "Maritime")
        val one = diplomacy()  // 1 of 2 majors: half
        game.addCiv(cityStateType = "Maritime")
        val two = diplomacy()  // as many as the majors: no penalty
        game.addCiv()
        val threeMajors = diplomacy()  // 2 for 3 majors: fewer, more than half
        assertEquals(20, two - one)
        assertEquals(10, two - threeMajors)
    }

    @Test
    fun `an AI not serious about victory goes for its primary pursuit, close to world conquest for domination`() {
        game.addCity(game.addCiv(), game.getTile(HexCoord(-8, 0)))  // three original capitals: holding one of them is not close
        mongolia.ai.primaryVictoryPursuit = VictoryPursuit.Science
        VictoryPursuitAi.updateCurrentVictoryPursuit(mongolia)
        assertEquals(VictoryPursuit.Science, mongolia.ai.currentVictoryPursuit)
        greece.cities.first().moveToCiv(mongolia)
        VictoryPursuitAi.updateCurrentVictoryPursuit(mongolia)
        assertEquals(VictoryPursuit.Domination, mongolia.ai.currentVictoryPursuit)
    }

    @Test
    fun `a human takes the leader's hints`() {
        val human = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
        VictoryPursuitAi.selectDefaultVictoryPursuits(human)
        assertEquals(VictoryPursuit.Diplomacy, human.ai.primaryVictoryPursuit)
        assertEquals(VictoryPursuit.Domination, human.ai.secondaryVictoryPursuit)
        assertEquals(VictoryPursuit.Diplomacy, human.ai.currentVictoryPursuit)
    }
}
