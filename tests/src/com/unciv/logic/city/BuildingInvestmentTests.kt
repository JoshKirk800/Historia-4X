package com.unciv.logic.city

import com.unciv.json.json
import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 *  Building investments (`MOD_BALANCE_BUILDING_INVESTMENTS`, Vox Populi): buying a building with gold keeps it in the queue and cuts its production cost.
 *  V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`: CvCity::SetBuildingInvestment (V:CvCity.cpp:11661-11700), getProductionNeeded (11507-11510),
 *  PurchaseBuilding (31308-31330), IsCanPurchase (30757-30766). A human civilization plays, so no AI percentage changes a cost.
 */
@RunWith(BaseTestRunner::class)
class BuildingInvestmentTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City
    private val constructions get() = city.cityConstructions
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 3)
        game.gameInfo.turns = 10
        civ.addGold(100000)
        constants.buildingInvestments = true
        constants.goldBuildingCostPercent = 50
        game.gameInfo.currentPlayerCiv = civ
        city.cityStats.update()
    }

    private fun building(cost: Int = 200): Building = game.createBuilding().apply { this.cost = cost }
    private fun wonder(cost: Int = 200): Building = game.createWonder().apply { this.cost = cost }
    private fun production() = city.cityStats.currentCityStats.production.roundToInt()
    /** The gold price: half of the formula's, rounded down to 10 */
    private fun halfPrice(cost: Int) = ((30.0 * cost).pow(0.75) * 0.5 / 10).toInt() * 10
    private fun allowed(building: Building) = constructions.isConstructionPurchaseAllowed(building, Stat.Gold, building.getStatBuyCost(city, Stat.Gold)!!)

    @Test
    fun `buying a building with gold invests the building stays in the queue at the front the price is half the production cost falls by 50 percent`() {
        val warrior = game.ruleset.units["Warrior"]!!
        constructions.addToQueue(warrior)
        val building = building()
        val gold = civ.gold
        assertEquals(halfPrice(200), building.getStatBuyCost(city, Stat.Gold))
        assertEquals(200, building.getProductionCost(civ, city))

        assertTrue(constructions.purchaseConstruction(building, -1, false))

        assertFalse("it is not built", constructions.isBuilt(building.name))
        assertEquals("the half price is paid", halfPrice(200), gold - civ.gold)
        assertEquals("it is at the front, the unit behind it", listOf(building.name, warrior.name), constructions.constructionQueue)
        assertEquals(100, constructions.investedBuildings[building.name])
        assertEquals("half of 200", 100, building.getProductionCost(civ, city))
        assertEquals("what is built stays as it was", 200, building.getProductionCostBeforeInvestment(civ, city))
        assertEquals(100, constructions.getRemainingWork(building.name))
    }

    @Test
    fun `an investment in a world wonder takes off 25 percent`() {
        val wonder = wonder()
        constructions.purchaseConstruction(wonder, -1, false)

        assertEquals(50, constructions.investedBuildings[wonder.name])
        assertEquals(150, wonder.getProductionCost(civ, city))
    }

    @Test
    fun `an investment never takes the cost below one more production than the city has put into it`() {
        val building = building()
        constructions.inProgressConstructions[building.name] = 150

        constructions.purchaseConstruction(building, -1, false)

        assertEquals("200 - max(100, 151)", 49, constructions.investedBuildings[building.name])
        assertEquals(151, building.getProductionCost(civ, city))

        // all of it done already: no reduction at all
        val done = building()
        constructions.inProgressConstructions[done.name] = 300
        constructions.purchaseConstruction(done, -1, false)
        assertEquals(0, constructions.investedBuildings[done.name])
        assertEquals(200, done.getProductionCost(civ, city))
    }

    @Test
    fun `the investment baseline and the wonder divisor are constants`() {
        constants.buildingInvestmentBaseline = -40
        constants.buildingInvestmentWonderDivisor = 4
        val building = building(300)
        val wonder = wonder(300)
        constructions.purchaseConstruction(building, -1, false)
        constructions.purchaseConstruction(wonder, -1, false)

        assertEquals("300 x 60 / 100 = 180", 120, constructions.investedBuildings[building.name])
        assertEquals("-40 / 4 = -10: 300 x 90 / 100", 30, constructions.investedBuildings[wonder.name])
    }

    @Test
    fun `the finished building drops its investment and the production cost reads it only while it is built`() {
        val building = building()
        constructions.purchaseConstruction(building, -1, false)
        constructions.inProgressConstructions[building.name] = 100

        constructions.constructIfEnough()

        assertTrue(constructions.isBuilt(building.name))
        assertTrue(constructions.investedBuildings.isEmpty())
    }

    //region Refusals: V:CvCity.cpp:12225-12232, 30757-30766

    @Test
    fun `a second investment in the same building is refused, and one when one turn is left`() {
        val building = building()
        assertTrue(allowed(building))
        constructions.purchaseConstruction(building, -1, false)
        assertFalse("invested already", allowed(building))
        assertFalse(PurchaseRules.canInvest(city, building))

        val last = building()
        val perTurn = production()
        assertTrue(perTurn > 0)
        constructions.inProgressConstructions[last.name] = 200 - perTurn // one more turn of production finishes it
        assertFalse("finished next turn anyway", allowed(last))
        constructions.inProgressConstructions[last.name] = 200 - perTurn - 1
        assertTrue("two turns left", allowed(last))
    }

    @Test
    fun `the overflow of the city counts as production put into the building`() {
        val building = building()
        constructions.productionOverflow = 200 - production()
        assertFalse(allowed(building))
        constructions.productionOverflow -= 1
        assertTrue(allowed(building))
    }

    @Test
    fun `faith is not an investment, and without the constant a purchase builds`() {
        val building = building()
        assertFalse(PurchaseRules.isBlocked(city, building, Stat.Faith))

        constants.buildingInvestments = false
        constants.goldBuildingCostPercent = 100
        assertEquals(((30.0 * 200).pow(0.75) / 10).toInt() * 10, building.getStatBuyCost(city, Stat.Gold))
        constructions.purchaseConstruction(building, -1, false)
        assertTrue(constructions.isBuilt(building.name))
        assertTrue(constructions.investedBuildings.isEmpty())
    }

    //endregion
    //region Old saves, selling, capture: V:CvCity.cpp:11663-11669, CvBuildingClasses.cpp:5980-5983

    @Test
    fun `old saves load with no investments`() {
        val loaded = json().fromJson(CityConstructions::class.java, "{}")
        assertTrue(loaded.investedBuildings.isEmpty())

        constructions.investedBuildings["A building"] = 12
        val written = json().toJson(constructions)
        assertEquals(mapOf("A building" to 12), json().fromJson(CityConstructions::class.java, written).investedBuildings)
        assertEquals(mapOf("A building" to 12), constructions.clone().investedBuildings)
    }

    @Test
    fun `the investment is dropped when the building is sold`() {
        val building = building()
        constructions.addBuilding(building)
        constructions.investedBuildings[building.name] = 40

        constructions.removeBuilding(building)

        assertTrue(constructions.investedBuildings.isEmpty())
    }

    @Test
    fun `the investment is dropped when the city is captured`() {
        val building = building()
        constructions.purchaseConstruction(building, -1, false)
        city.purchaseCooldowns.gold = 3
        assertEquals(100, constructions.investedBuildings[building.name])
        val conqueror = game.addCiv()
        game.gameInfo.currentPlayerCiv = game.addCiv()
        game.gameInfo.currentPlayer = game.gameInfo.currentPlayerCiv.civID

        CityConquestFunctions(city).puppetCity(conqueror)

        assertEquals(conqueror, city.civ)
        assertTrue(city.cityConstructions.investedBuildings.isEmpty())
        assertEquals("nor does the new owner wait for the old one's cooldown", 0, city.purchaseCooldowns.gold)
    }

    //endregion
}
