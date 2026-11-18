package com.unciv.logic.automation.strategy

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Strategic strength after Vox Populi's CvPlayer::calculateMilitaryMight. */
@RunWith(GdxTestRunner::class)
class MilitaryMightTests {
    @Test
    fun `treasury does not change the assessed strength of identical armies`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        val poor = game.addCiv()
        val rich = game.addCiv()
        game.addCity(poor, game.getTile(HexCoord(-3, 0)))
        game.addCity(rich, game.getTile(HexCoord(3, 0)))
        game.addUnit("Warrior", poor, game.getTile(HexCoord(-2, 0)))
        game.addUnit("Warrior", rich, game.getTile(HexCoord(2, 0)))
        poor.addGold(-poor.gold)
        rich.addGold(10000 - rich.gold)

        assertEquals(poor.ai.militaryMight(), rich.ai.militaryMight())
    }

    @Test
    fun `naval units contribute their offensive strength without halving`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
        game.setTileTerrain(HexCoord(1, 0), "Coast")
        val ship = game.addUnit("Trireme", civ, game.getTile(HexCoord(1, 0)))
        civ.addGold(-civ.gold)

        assertEquals(maxOf(ship.baseUnit.strength, ship.baseUnit.rangedStrength), civ.ai.militaryMight())
    }
}
