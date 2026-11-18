package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CvEconomicAI::DoHurry: spending and preservation of higher-priority savings. */
@RunWith(GdxTestRunner::class)
class HurryPurchaseTests {
    @Test
    fun `economic turn buys a useful construction when the treasury permits`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.tileMap[0, 0])
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        civ.ai.setPersonalityFlavor(Flavor.Culture, 10)
        civ.addGold(5000)
        civ.ai.economic.doTurnAfterStrategies(civ)
        assertTrue(city.cityConstructions.isBuilt("Monument"))
        assertTrue(civ.gold < 5000)
    }

    @Test
    fun `economic turn preserves gold reserved for other purchases`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.tileMap[0, 0])
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        civ.ai.setPersonalityFlavor(Flavor.Culture, 10)
        civ.addGold(5000)
        civ.ai.purchaseSavings.startSaveForPurchase(PurchaseType.UnitUpgrade, 3000, 10000)
        civ.ai.economic.doTurnAfterStrategies(civ)
        assertEquals(false, city.cityConstructions.isBuilt("Monument"))
    }
}
