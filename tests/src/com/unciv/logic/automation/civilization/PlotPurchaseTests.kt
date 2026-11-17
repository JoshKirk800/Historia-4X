package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvEconomicAI::DoPlotPurchases (Release-5.4.8): the treasury cushion, losing wars, saving for a tile the savings do not allow */
@RunWith(GdxTestRunner::class)
class PlotPurchaseTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10, Constants.grassland)
        civ = game.addCiv()
        city = game.addCity(civ, game.getTile(HexCoord(0, 0)))
    }

    @Test
    fun `with plenty of gold the AI buys one tile`() {
        civ.addGold(5000)
        UseGoldAutomation.doPlotPurchases(civ)
        assertEquals(1, civ.plotsBought)
        assertTrue(civ.gold < 5000)
    }

    @Test
    fun `below the treasury cushion plus 200 it buys nothing`() {
        civ.addGold(UseGoldAutomation.treasuryBuffer(civ) + 199)
        UseGoldAutomation.doPlotPurchases(civ)
        assertEquals(0, civ.plotsBought)
    }

    @Test
    fun `the cushion is the income taken off in the black and ten times the loss added in debt`() {
        // Ancient era: 50 x game speed x era 0
        civ.stats.statsForNextTurn = Stats(gold = 30f)
        assertEquals(0, UseGoldAutomation.treasuryBuffer(civ))
        civ.stats.statsForNextTurn = Stats(gold = -20f)
        assertEquals(-200, UseGoldAutomation.treasuryBuffer(civ))
    }

    @Test
    fun `only losing all wars stops the plot buying at war`() {
        civ.ai.militaryStrategies["MILITARYAISTRATEGY_AT_WAR"] = 0
        civ.addGold(5000)
        civ.ai.warStates.stateAllWars = StateAllWars.Losing
        UseGoldAutomation.doPlotPurchases(civ)
        assertEquals(0, civ.plotsBought)
        civ.ai.warStates.stateAllWars = StateAllWars.Neutral
        UseGoldAutomation.doPlotPurchases(civ)
        assertEquals(1, civ.plotsBought)
    }

    @Test
    fun `a city-state buys no plots`() {
        val minor = game.addCiv(cityStateType = "Militaristic")
        game.addCity(minor, game.getTile(HexCoord(6, 0)))
        minor.addGold(5000)
        UseGoldAutomation.doPlotPurchases(minor)
        assertEquals(0, minor.plotsBought)
    }

    @Test
    fun `the score of the best tile does not depend on what the civilization can pay`() {
        assertEquals(0, civ.gold)
        assertNotNull(UseGoldAutomation.buyPlotScore(city))
    }

    @Test
    fun `a tile the savings do not let go is saved for at priority 2`() {
        civ.addGold(5000)
        // a request that outranks any plot score takes the whole balance
        civ.ai.purchaseSavings.startSaveForPurchase(PurchaseType.Unit, 10000, 100000)
        UseGoldAutomation.doPlotPurchases(civ)
        assertEquals(0, civ.plotsBought)
        val request = civ.ai.purchaseSavings.requests[PurchaseType.Tile.name]!!
        assertEquals(2, request.priority)
        assertEquals(city.expansion.getGoldCostOfTile(UseGoldAutomation.buyPlotScore(city)!!.second), request.amount)
    }

    @Test
    fun `a civilization with no tile to buy saves for none`() {
        civ.addGold(5000)
        city.isPuppet = true
        UseGoldAutomation.doPlotPurchases(civ)
        assertEquals(0, civ.plotsBought)
        assertNull(civ.ai.purchaseSavings.requests[PurchaseType.Tile.name])
    }
}
