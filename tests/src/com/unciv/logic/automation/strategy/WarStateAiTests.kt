package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvDiplomacyAI::GetStateAllWars and the queries; the war states themselves are in [WarStateUpdateTests] */
@RunWith(GdxTestRunner::class)
class WarStateAiTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        us = game.addCiv()
        them = game.addCiv()
        game.addCity(us, game.tileMap[-7, 0])
        game.addCity(them, game.tileMap[7, 0])
        us.diplomacyFunctions.makeCivilizationsMeet(them)
    }

    @Test
    fun `no war, no war state`() {
        WarStateAi.doTurn(us)
        assertNull(WarStateAi.warState(us, them))
        assertEquals(StateAllWars.Neutral, WarStateAi.stateAllWars(us))
        assertEquals(0, WarStateAi.numberCivsAtWarWith(us))
    }

    @Test
    fun `a war is counted and its turns too`() {
        us.getDiplomacyManager(them)!!.declareWar()
        WarStateAi.doTurn(us)
        assertEquals(1, WarStateAi.numberCivsAtWarWith(us))
        assertEquals(1, WarStateAi.turnsAtWar(us, them))
    }
}
