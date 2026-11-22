package com.unciv.logic.city

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/**
 *  What Vox Populi's economy does to buying: the gold price formula behind constants, the price of a building, the cooldown after buying a unit, the building a unit
 *  needs, and the settler's price. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. A human civilization plays, so no AI percentage changes a cost.
 */
@RunWith(BaseTestRunner::class)
class PurchaseRulesTests {
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
    }

    private fun vpPrice() { constants.purchase = ModConstants.PurchaseConstants(goldPerProduction = 4.0, exponent = 0.88) }
    /** A new speed that is Standard but for these two (the ruleset's speeds are shared with the other tests) */
    private fun useSpeed(productionCostModifier: Float = 1f, hurryModifier: Float = 1f) {
        val speed = Speed().apply {
            name = "Purchase test"
            turns = game.gameInfo.speed.turns
            this.productionCostModifier = productionCostModifier
            this.hurryModifier = hurryModifier
        }
        game.ruleset.speeds[speed.name] = speed
        game.setSpeed(speed.name)
    }
    private fun unit(cost: Int = 100, strength: Int = 8, unitType: String = "Sword", uniques: List<String> = emptyList()): BaseUnit =
        game.createBaseUnit(unitType, *uniques.toTypedArray()).apply { this.cost = cost; this.strength = strength; movement = 2 }
    private fun allowed(unit: BaseUnit, stat: Stat = Stat.Gold) =
        city.cityConstructions.isConstructionPurchaseAllowed(unit, stat, unit.getStatBuyCost(city, stat)!!)
    /** Buys the unit, and takes it off the map again: the city's own tile is all it has, and the next one needs the room */
    private fun buy(unit: BaseUnit, stat: Stat = Stat.Gold): Boolean {
        val bought = city.cityConstructions.purchaseConstruction(unit, -1, false, stat)
        for (mapUnit in civ.units.getCivUnits().toList()) mapUnit.destroy()
        return bought
    }

    //region The price: V:CvGameCoreUtils.cpp:1265-1277, V:CvCity.cpp:12213-12275

    @Test
    fun `the gold price is (4 x production) ^ 0 88 times the speed hurry percent with the VP constants and (30 x production) ^ 0 75 with BNW's`() {
        val building = game.createBuilding().apply { cost = 60 }
        assertEquals("(30 x 60) ^ 0.75", 276, building.getBaseGoldCost(civ, city).toInt())

        vpPrice()
        assertEquals("(4 x 60) ^ 0.88", 124, building.getBaseGoldCost(civ, city).toInt())
        assertEquals((4.0 * 60).pow(0.88), building.getBaseGoldCost(civ, city), 1e-9)

        useSpeed(hurryModifier = 1.5f)
        assertEquals((4.0 * 60).pow(0.88) * 1.5, building.getBaseGoldCost(civ, city), 1e-9)
        assertEquals("a unit is priced by the same formula", (4.0 * 200).pow(0.88) * 1.5, unit(cost = 200).getBaseGoldCost(civ, city), 1e-9)
    }

    @Test
    fun `the Civilopedia price reads the constants as well`() {
        val building = game.createBuilding().apply { cost = 200 }
        assertEquals(680, building.getCivilopediaGoldCost()) // 6000 ^ 0.75 = 681.1 down to 10
        vpPrice()
        assertEquals(350, building.getCivilopediaGoldCost()) // 800 ^ 0.88 = 358.2: 350
        constants.purchase = ModConstants.PurchaseConstants(visibleDivisor = 25)
        assertEquals(675, building.getCivilopediaGoldCost())
    }

    @Test
    fun `a building's price is the base times hurry cost modifier times the policy modifier, halved by investments, rounded down to 10, at least 10`() {
        val building = game.createBuilding().apply { cost = 200; hurryCostModifier = 25 }
        val base = (30.0 * 200).pow(0.75)
        assertEquals((base * 1.25 / 10).toInt() * 10, building.getStatBuyCost(city, Stat.Gold))

        civ.policies.adopt(game.createPolicy("[Gold] cost of purchasing items in cities [-20]%"), true)
        assertEquals((base * 1.25 * 0.8 / 10).toInt() * 10, building.getStatBuyCost(city, Stat.Gold))

        constants.buildingInvestments = true
        constants.goldBuildingCostPercent = 50
        assertEquals("half of it", (base * 1.25 * 0.8 * 0.5 / 10).toInt() * 10, building.getStatBuyCost(city, Stat.Gold))

        vpPrice()
        val cheap = game.createBuilding().apply { cost = 1 } // (4 x 1) ^ 0.88 = 3.4, a quarter of it is under the divisor
        assertEquals("at least 10", 10, cheap.getStatBuyCost(city, Stat.Gold))
    }

    @Test
    fun `a price that a unique sets is neither halved by investments nor raised to 10`() {
        constants.buildingInvestments = true
        constants.goldBuildingCostPercent = 50
        val building = game.createBuilding("Can be purchased for [4] [Gold] [in all cities]").apply { cost = 200 }
        assertEquals(0, building.getStatBuyCost(city, Stat.Gold))
    }

    @Test
    fun `without the constants the price of a building is what it always was`() {
        val building = game.createBuilding().apply { cost = 180; hurryCostModifier = 25 }
        assertEquals((((30.0 * 180).pow(0.75) * 1.25) / 10f).toInt() * 10, building.getStatBuyCost(city, Stat.Gold))
        assertEquals("a building that costs nothing is free", 0, game.createBuilding().apply { cost = 0 }.getStatBuyCost(city, Stat.Gold))
    }

    //endregion
    //region Cooldowns: V:CvCity.cpp:2485-2503, 30786-30800, 30976-31000, 31100, 31121-31135

    @Test
    fun `a unit purchase sets the city's cooldown, which falls one per turn, and the city can not buy that kind of unit meanwhile`() {
        constants.purchaseCooldownEnabled = true
        val sword = unit().apply { purchaseCooldown = 3 }
        assertTrue("before", allowed(sword))

        assertTrue("bought", buy(sword))
        assertEquals(3, city.purchaseCooldowns.gold)
        assertFalse("on cooldown", allowed(sword))

        PurchaseRules.tickCooldowns(city)
        assertEquals(2, city.purchaseCooldowns.gold)
        repeat(2) { PurchaseRules.tickCooldowns(city) }
        assertEquals(0, city.purchaseCooldowns.gold)
        PurchaseRules.tickCooldowns(city)
        assertEquals("never below 0", 0, city.purchaseCooldowns.gold)
        assertTrue(allowed(sword))
    }

    @Test
    fun `without the constant there is no cooldown`() {
        val sword = unit().apply { purchaseCooldown = 3 }
        assertTrue("bought", buy(sword))
        assertEquals(0, city.purchaseCooldowns.gold)
        assertTrue("allowed again", allowed(sword))
    }

    @Test
    fun `civilian and military counters are separate, and the reduction of a building lets the city buy sooner`() {
        constants.purchaseCooldownEnabled = true
        val sword = unit().apply { purchaseCooldown = 5 }
        val worker = unit(strength = 0, unitType = "Civilian").apply { purchaseCooldown = 1 }
        assertTrue(buy(sword))
        assertEquals(5, city.purchaseCooldowns.gold)
        assertEquals(0, city.purchaseCooldowns.goldCivilian)
        assertTrue("the civilian counter is its own", allowed(worker))

        assertTrue(buy(worker))
        assertEquals(1, city.purchaseCooldowns.goldCivilian)
        assertFalse(allowed(worker))

        city.cityConstructions.addBuilding(game.createBuilding().apply { purchaseCooldownReductionCivilian = 3 })
        assertTrue("1 less 3 is not above 0", allowed(worker))
        assertFalse("the military counter has no reduction", allowed(sword))

        assertTrue(buy(worker))
        assertEquals("the cooldown less the reduction, as the source sets it", -2, city.purchaseCooldowns.goldCivilian)
    }

    @Test
    fun `a faith purchase sets the player's counter and the city's`() {
        constants.purchaseCooldownEnabled = true
        useSpeed(productionCostModifier = 1.5f)
        val missionary = unit(cost = 100, strength = 0, unitType = "Civilian").apply {
            globalFaithPurchaseCooldown = 5
            localFaithPurchaseCooldown = 2
        }
        val another = unit(cost = 100, strength = 0, unitType = "Civilian").apply { globalFaithPurchaseCooldown = 5 }
        val local = unit(cost = 100, strength = 8).apply { localFaithPurchaseCooldown = 2 }

        PurchaseRules.afterPurchase(city, missionary, Stat.Faith)

        assertEquals("5 x the speed's 150%, cut off", 7, civ.faithPurchaseCooldown)
        assertEquals(3, city.purchaseCooldowns.faithCivilian)
        assertEquals(0, city.purchaseCooldowns.faith)
        assertTrue(PurchaseRules.isOnCooldown(city, another, Stat.Faith))
        assertTrue(PurchaseRules.isOnCooldown(city, missionary, Stat.Faith))
        assertFalse("the military kind of the city is free", PurchaseRules.isOnCooldown(city, local, Stat.Faith))
        assertFalse("and gold is not held by faith", PurchaseRules.isOnCooldown(city, another, Stat.Gold))

        civ.faithPurchaseCooldown = 0
        city.purchaseCooldowns.faithCivilian = 0
        assertFalse(PurchaseRules.isOnCooldown(city, missionary, Stat.Faith))
    }

    @Test
    fun `the counters fall one per turn of the civilization and of the city`() {
        civ.faithPurchaseCooldown = 2
        city.purchaseCooldowns.gold = 1
        city.purchaseCooldowns.faith = 4

        TurnManager(civ).startTurn() // which starts the turn of its cities

        assertEquals(1, civ.faithPurchaseCooldown)
        assertEquals(0, city.purchaseCooldowns.gold)
        assertEquals(3, city.purchaseCooldowns.faith)
    }

    //endregion
    //region The building a unit needs: V:CvCity.cpp:30802-30815

    @Test
    fun `a unit with a purchase building requirement can not be bought without it`() {
        val barracks = game.createBuilding()
        val sword = unit(uniques = listOf("Can only be purchased in cities with [${barracks.name}]"))
        assertFalse(allowed(sword))
        assertEquals(barracks.name, PurchaseRules.missingPurchaseBuilding(city, sword))

        city.cityConstructions.addBuilding(barracks)

        assertTrue(allowed(sword))
        assertNull(PurchaseRules.missingPurchaseBuilding(city, sword))
    }

    @Test
    fun `buying the unit with faith does not need the building`() {
        val barracks = game.createBuilding()
        val sword = unit(uniques = listOf("Can only be purchased in cities with [${barracks.name}]"))
        assertFalse(PurchaseRules.isBlocked(city, sword, Stat.Faith))
        assertTrue(PurchaseRules.isBlocked(city, sword, Stat.Gold))
    }

    //endregion
    //region The settler's price: V:CvCity.cpp:11869-11899

    @Test
    fun `the settler gold price rises with the city's unhappiness over happiness and the empire's penalty, at most 75`() {
        val world = NeedsTestWorld()
        val settler = world.game.ruleset.units["Settler"]!!
        val warrior = world.game.ruleset.units["Warrior"]!!
        fun price(city: City, unit: BaseUnit, surcharge: Int) =
            (unit.getBaseBuyCost(city, Stat.Gold)!!.toDouble() * (100 + surcharge) / 100 / 10).toInt() * 10

        // one city, 5 specialists and 4 happiness: delta -1, approval 40, so the empire's penalty is 2.5 x 10 and the city's 10
        val city = world.addCity(5)
        world.build(city, "[+60 Food]")
        world.addSpecialists(city, 5)
        world.giveHappiness(city, 4)
        world.refresh()
        assertEquals(-1, world.needsOf(city).delta)
        assertEquals(35, world.needsOf(city).settlerPurchaseSurcharge(settler))
        assertEquals("the base price, then +35%, rounded down to 10", price(city, settler, 35), settler.getStatBuyCost(city, Stat.Gold))
        assertEquals("a combat unit pays nothing for unhappiness", price(city, warrior, 0), warrior.getStatBuyCost(city, Stat.Gold))

        // 14 citizens that cost against 4 that are happy: delta -10 is more than 75
        city.population.specialistAllocations.clear()
        world.addSpecialists(city, 14)
        city.population.addPopulation(9)
        world.refresh()
        assertEquals(-10, world.needsOf(city).delta)
        assertEquals(75, world.needsOf(city).settlerPurchaseSurcharge(settler))
        assertEquals(price(city, settler, 75), settler.getStatBuyCost(city, Stat.Gold))
    }

    //endregion
    //region Old saves

    @Test
    fun `old saves load with cooldowns 0`() {
        val saved = json().toJson(PurchaseCooldowns())
        val loaded = json().fromJson(PurchaseCooldowns::class.java, saved)
        assertEquals(0, loaded.gold + loaded.goldCivilian + loaded.faith + loaded.faithCivilian)

        val fromOldCity = json().fromJson(PurchaseCooldowns::class.java, "{}")
        assertEquals(0, fromOldCity.gold)
        city.purchaseCooldowns.goldCivilian = 4
        assertEquals(4, city.clone().purchaseCooldowns.goldCivilian)
        civ.faithPurchaseCooldown = 6
        assertEquals(6, civ.clone().faithPurchaseCooldown)
    }

    //endregion
}
