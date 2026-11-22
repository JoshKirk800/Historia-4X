package com.unciv.ui.screens.cityscreen

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.NeedsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What the city screen tells about buying with Vox Populi's investments, cooldowns and unit requirements ([ConstructionPurchaseRows], built from the rules of
 *  [com.unciv.logic.city.PurchaseRules]; V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`: CvCity::SetBuildingInvestment V:CvCity.cpp:11661-11700, IsCanPurchase 30757-30821).
 *  A human civilization plays, so no AI percentage changes a cost.
 */
@RunWith(BaseTestRunner::class)
class ConstructionPurchaseRowsTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 3)
        game.gameInfo.turns = 10
        civ.addGold(100000)
        game.gameInfo.currentPlayerCiv = civ
        city.cityStats.update()
    }

    private fun building(cost: Int = 200): Building = game.createBuilding().apply { this.cost = cost }
    private fun unit(strength: Int = 8, unitType: String = "Sword", uniques: List<String> = emptyList()): BaseUnit =
        game.createBaseUnit(unitType, *uniques.toTypedArray()).apply { cost = 100; this.strength = strength; movement = 2 }
    private fun List<NeedsRow>.labels() = map { it.label }

    //region Investments

    @Test
    fun `the city screen shows the investment price and the reduced production cost`() {
        constants.buildingInvestments = true
        constants.goldBuildingCostPercent = 50
        val building = building()
        val price = building.getStatBuyCost(city, Stat.Gold)!!

        val before = ConstructionPurchaseRows.rows(city, building)
        assertEquals("the price of the investment and the cost it brings, 50% of 200", listOf(
            "Investing [$price] [Gold] lowers the production cost from [200] to [100]"), before.labels())
        assertTrue("the gold button is an investment", ConstructionPurchaseRows.isInvestment(city, building, Stat.Gold))
        assertFalse("not with faith", ConstructionPurchaseRows.isInvestment(city, building, Stat.Faith))

        assertTrue(city.cityConstructions.purchaseConstruction(building, -1, false))

        val after = ConstructionPurchaseRows.rows(city, building)
        assertEquals(listOf("Investment", "Invested: production cost [100] instead of [200]"), after.labels())
        assertEquals("what the investment took off", "-100", after.first().value)
        assertEquals("the second investment is refused, and the tooltip says why",
            "Invested: production cost [100] instead of [200]", ConstructionPurchaseRows.blockReason(city, building, Stat.Gold))
    }

    @Test
    fun `a building that is finished next turn anyway says no investment is possible`() {
        constants.buildingInvestments = true
        constants.goldBuildingCostPercent = 50
        val building = building(cost = 5)
        city.cityConstructions.inProgressConstructions[building.name] = 4
        city.cityStats.update()

        assertEquals(listOf("The building is finished next turn anyway: no investment possible"), ConstructionPurchaseRows.rows(city, building).labels())
        assertEquals("The building is finished next turn anyway: no investment possible", ConstructionPurchaseRows.blockReason(city, building, Stat.Gold))
    }

    @Test
    fun `without the investments a building says nothing and its gold button buys it`() {
        val building = building()
        assertTrue(ConstructionPurchaseRows.rows(city, building).isEmpty())
        assertFalse(ConstructionPurchaseRows.isInvestment(city, building, Stat.Gold))
        assertNull(ConstructionPurchaseRows.blockReason(city, building, Stat.Gold))
    }

    //endregion
    //region Cooldowns and required buildings

    @Test
    fun `a cooldown shows its turns`() {
        constants.purchaseCooldownEnabled = true
        val sword = unit()
        assertTrue("nothing before the first purchase", ConstructionPurchaseRows.rows(city, sword).isEmpty())

        city.purchaseCooldowns.gold = 3

        assertEquals(3, ConstructionPurchaseRows.cooldownTurnsLeft(city, sword, Stat.Gold))
        val rows = ConstructionPurchaseRows.rows(city, sword)
        assertEquals(listOf("Purchase cooldown with [Gold]: [3] turns left"), rows.labels())
        assertTrue("it is something to wait for", rows.single().isAlert)
        assertEquals("Purchase cooldown with [Gold]: [3] turns left", ConstructionPurchaseRows.blockReason(city, sword, Stat.Gold))

        city.cityConstructions.addBuilding(game.createBuilding().apply { purchaseCooldownReduction = 1 })
        assertEquals("a building's reduction counts off", 2, ConstructionPurchaseRows.cooldownTurnsLeft(city, sword, Stat.Gold))

        val worker = unit(strength = 0, unitType = "Civilian")
        assertEquals("the civilian counter is its own", 0, ConstructionPurchaseRows.cooldownTurnsLeft(city, worker, Stat.Gold))
        assertTrue(ConstructionPurchaseRows.rows(city, worker).isEmpty())

        city.purchaseCooldowns.gold = 0
        assertTrue("it is over", ConstructionPurchaseRows.rows(city, sword).isEmpty())
    }

    @Test
    fun `a faith cooldown shows the longer of the civilization's and the city's turns`() {
        constants.purchaseCooldownEnabled = true
        val missionary = unit(strength = 0, unitType = "Civilian").apply { globalFaithPurchaseCooldown = 5; localFaithPurchaseCooldown = 2 }
        civ.faithPurchaseCooldown = 4
        city.purchaseCooldowns.faithCivilian = 2

        assertEquals(4, ConstructionPurchaseRows.cooldownTurnsLeft(city, missionary, Stat.Faith))
        assertEquals(listOf("Purchase cooldown with [Faith]: [4] turns left"), ConstructionPurchaseRows.rows(city, missionary).labels())
    }

    @Test
    fun `a unit that needs a building says which, and the reason is gone once it is built`() {
        val barracks = game.createBuilding()
        val sword = unit(uniques = listOf("Can only be purchased in cities with [${barracks.name}]"))

        assertEquals(listOf("Can only be purchased in cities with [${barracks.name}]"), ConstructionPurchaseRows.rows(city, sword).labels())
        assertEquals("Can only be purchased in cities with [${barracks.name}]", ConstructionPurchaseRows.blockReason(city, sword, Stat.Gold))
        assertNull("faith needs no building", ConstructionPurchaseRows.blockReason(city, sword, Stat.Faith))

        city.cityConstructions.addBuilding(barracks)
        assertTrue(ConstructionPurchaseRows.rows(city, sword).isEmpty())
        assertNull(ConstructionPurchaseRows.blockReason(city, sword, Stat.Gold))
    }

    //endregion
}
