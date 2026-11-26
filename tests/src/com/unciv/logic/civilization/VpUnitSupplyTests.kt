package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 unit supply with `unitSupplyLikeVp` (CvPlayer::GetUnitSupplyBreakdown, GetNumUnitsSuppliedByHandicap/Cities/Population,
 *  calculateUnitProductionMaintenanceMod, calculateUnitGrowthMaintenanceMod, CvUnit::isNoSupply, CvCity::canTrain).
 *  A human on Prince has 5 + 2 per city + half the citizens (see [UnitSupplyTests]); 3 cities of 4 citizens give 5 + 6 + 6 = 17.
 *  The switches and constants are set by reflection so the file compiles against the source without them.
 */
@RunWith(BaseTestRunner::class)
class VpUnitSupplyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var freeTiles: Iterator<Tile>

    @After
    fun restoreTable() = SupplyTable.restore(game.ruleset)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        SupplyTable.communityPatch(game.ruleset) // the numbers below are derived from the Community Patch's table, not from the one the ruleset ships
        // the BNW ruleset has the switch on and VP's constants since the M4 activation; each test starts from the original's formula and
        // switches on (setSwitch) and sets the constants it tests
        game.ruleset.modOptions.constants.unitSupplyLikeVp = false
        game.ruleset.modOptions.constants.unitSupplyConstants = ModConstants.UnitSupplyConstants()
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv(isPlayer = true)
        val origin = game.getTile(HexCoord(0, 0))
        freeTiles = game.tileMap.values.filter { it.aerialDistanceTo(origin) in 1..3 }.iterator()
    }

    private fun setSwitch(on: Boolean) {
        val constants = game.ruleset.modOptions.constants
        constants.javaClass.getMethod("setUnitSupplyLikeVp", Boolean::class.javaPrimitiveType).invoke(constants, on)
    }

    /** All the fields of ModConstants.UnitSupplyConstants (defaults: the original's numbers) */
    private fun setConstants(
        baseReductionPerEra: Int = 0, citiesTech: Int = 0, populationTech: Int = 0, empirePerCity: Int = 0,
        productionPerUnit: Int = 10, maxProduction: Int = 70, growthPerUnit: Int = 0, maxGrowth: Int = 70
    ) {
        val constants = game.ruleset.modOptions.constants
        val type = Class.forName("com.unciv.models.ModConstants\$UnitSupplyConstants")
        val ints = Int::class.javaPrimitiveType
        val value = type.getConstructor(ints, ints, ints, ints, ints, ints, ints, ints)
            .newInstance(baseReductionPerEra, citiesTech, populationTech, empirePerCity, productionPerUnit, maxProduction, growthPerUnit, maxGrowth)
        constants.javaClass.getMethod("setUnitSupplyConstants", type).invoke(constants, value)
    }

    private fun growthPenalty() = civ.stats.javaClass.getMethod("getUnitSupplyGrowthPenalty").invoke(civ.stats) as Int

    /** A getter of the breakdown: CivInfoStatsForNextTurn.getUnitSupplyBreakdown().<getter> */
    private fun breakdown(getter: String): Int {
        val method = civ.stats.javaClass.methods.first { it.name == "getUnitSupplyBreakdown" && it.parameterCount == 1 }
        val breakdown = method.invoke(civ.stats, true)
        return breakdown.javaClass.getMethod(getter).invoke(breakdown) as Int
    }

    private fun addCities(population: Int, vararg positions: Pair<Int, Int>): List<City> =
        positions.map { (x, y) -> game.addCity(civ, game.getTile(HexCoord(x, y)), initialPopulation = population) }

    private fun threeCities() = addCities(4, 0 to 0, 4 to 0, -4 to 0)

    private fun setEra(name: String) { civ.tech.era = game.ruleset.eras[name]!! }

    private fun knowAllTechs() { civ.tech.techsResearched.addAll(game.ruleset.technologies.keys) }

    private fun add(name: String): MapUnit = game.addUnit(name, civ, freeTiles.next())

    private fun toSupply() = civ.units.getNumUnitsToSupply()

    @Test
    fun `with the switch off the supply and the production penalty are the original's whatever the constants say`() {
        threeCities()
        setConstants(baseReductionPerEra = 100, citiesTech = 100, populationTech = 100, empirePerCity = 5, productionPerUnit = 5, maxProduction = 35, growthPerUnit = 5)
        setEra("Medieval era")
        knowAllTechs()
        assertEquals(5 + 6 + 6, civ.stats.getUnitSupply())
        repeat(17 + 9) { add("Warrior") }
        assertEquals(9, civ.stats.getUnitSupplyDeficit())
        assertEquals(-70f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
        assertEquals(0, growthPenalty())
        add("Warrior")
        assertEquals(-70f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
    }

    @Test
    fun `the unique takes a unit out of supply only with the switch, and a promotion adds and removes it`() {
        threeCities()
        val promotion = game.createUnitPromotion("Does not use unit supply")
        val warriors = List(3) { add("Warrior") }
        assertEquals(3, toSupply())

        warriors[0].promotions.addPromotion(promotion.name, true)
        assertEquals("switch off: the unique is not read", 3, toSupply())

        setSwitch(true)
        warriors[0].promotions.removePromotion(promotion.name)
        assertEquals(3, toSupply())
        warriors[0].promotions.addPromotion(promotion.name, true)
        assertEquals("a promotion with the unique", 2, toSupply())
        warriors[1].promotions.addPromotion(promotion.name, true)
        assertEquals(1, toSupply())
        warriors[0].promotions.removePromotion(promotion.name)
        assertEquals("the count is made again when a promotion goes", 2, toSupply())
    }

    @Test
    fun `a unit type with the unique uses no supply, a civilian that uses supply too`() {
        threeCities()
        setSwitch(true)
        val noSupply = game.createBaseUnit("Melee", "Does not use unit supply").apply { strength = 5 }
        val plain = game.createBaseUnit("Melee").apply { strength = 5 }
        val caravanLike = game.createBaseUnit("Civilian", "Uses unit supply", "Does not use unit supply")
        game.addUnit(plain.name, civ, freeTiles.next())
        assertEquals(1, toSupply())
        game.addUnit(noSupply.name, civ, freeTiles.next())
        assertEquals(1, toSupply())
        game.addUnit(caravanLike.name, civ, freeTiles.next())
        assertEquals(1, toSupply())
    }

    @Test
    fun `an era takes the hundredths of a unit of the constant off the base supply`() {
        threeCities()
        setSwitch(true)
        setConstants(baseReductionPerEra = 100)
        setEra("Ancient era")
        assertEquals(5 + 6 + 6, civ.stats.getUnitSupply())
        setEra("Classical era")
        assertEquals("one era: 5 - 100/100", 4 + 6 + 6, civ.stats.getUnitSupply())
        setEra("Medieval era")
        assertEquals("two eras", 3 + 6 + 6, civ.stats.getUnitSupply())
        setConstants(baseReductionPerEra = 50)
        assertEquals("two eras of half a unit: 5 - 2 x 50 / 100", 4 + 6 + 6, civ.stats.getUnitSupply())
        setConstants(baseReductionPerEra = 100000)
        assertEquals("never below 0 for the base", 0 + 6 + 6, civ.stats.getUnitSupply())
    }

    @Test
    fun `the technologies known divide the supply of the cities and of the citizens`() {
        threeCities()
        setSwitch(true)
        setConstants(citiesTech = 100, populationTech = 100)
        civ.tech.techsResearched.clear() // the test civilization starts with the technologies of the ruleset's start
        assertEquals("no technology: divisor 100", 5 + 6 + 6, civ.stats.getUnitSupply())
        knowAllTechs()
        // all technologies: progress 100, divisor 100 + 100 x 100 / 100 = 200: cities 6 x 100 / 200 = 3, citizens 600 x 100 / 200 / 100 = 3
        assertEquals(5 + 3 + 3, civ.stats.getUnitSupply())
        assertEquals(6, breakdown("getCitiesGross"))
        assertEquals(3, breakdown("getCitiesNet"))
        assertEquals(6, breakdown("getPopulationGross"))
        assertEquals(3, breakdown("getPopulationNet"))
        assertEquals(5, breakdown("getDifficultyGross"))
        assertEquals(100, breakdown("getTechProgressPercent"))
        assertEquals(200, breakdown("getCitiesTechDivisorPercent"))
        setConstants(citiesTech = 50, populationTech = 0)
        // divisor 150: cities 600 / 150 = 4, citizens untouched
        assertEquals(5 + 4 + 6, civ.stats.getUnitSupply())
    }

    @Test
    fun `each city but the puppets makes the divisor of the empire size 5 percent bigger`() {
        threeCities()
        setSwitch(true)
        setConstants(empirePerCity = 5)
        // subtotal 17, divisor 100 + 3 x 5 = 115: 1700 / 115 = 14
        assertEquals(14, civ.stats.getUnitSupply())
        assertEquals(115, breakdown("getEmpireSizeDivisorPercent"))
        assertEquals(17, breakdown("getSubtotal"))
        setConstants(empirePerCity = 0)
        assertEquals(17, civ.stats.getUnitSupply())
    }

    @Test
    fun `a puppet city gives its share of the supply of the city and of its citizens`() {
        val cities = threeCities()
        setSwitch(true)
        cities[2].isPuppet = true
        // cities: (2 x 100 + 2 x 100 + 2 x 30) / 100 = 4, citizens: (4 x 100 x 50 / 100 x 2 + 4 x 30 x 50 / 100) / 100 = 460 / 100 = 4
        assertEquals(5 + 4 + 4, civ.stats.getUnitSupply())
        // the puppet is not an effective city: 2 x 5 = 10, 1300 / 110 = 11
        setConstants(empirePerCity = 5)
        assertEquals(110, breakdown("getEmpireSizeDivisorPercent"))
        assertEquals(1300 / 110, civ.stats.getUnitSupply())
    }

    @Test
    fun `without the switch a puppet city gives the whole supply`() {
        val cities = threeCities()
        cities[2].isPuppet = true
        assertEquals(5 + 6 + 6, civ.stats.getUnitSupply())
    }

    @Test
    fun `the penalties per unit over the supply are capped, and the cap stops the training of combat units`() {
        addCities(1, 0 to 0)
        setSwitch(true)
        setConstants(productionPerUnit = 5, maxProduction = 70, growthPerUnit = 5, maxGrowth = 70)
        // 5 + 2 + 1 x 100 x 50 / 100 / 100 = 7
        assertEquals(7, civ.stats.getUnitSupply())
        repeat(7 + 3) { add("Warrior") }
        assertEquals(3, civ.stats.getUnitSupplyDeficit())
        assertEquals(-15f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
        assertEquals(-15, growthPenalty())
        assertFalse(cannotTrainForSupply("Warrior"))

        repeat(10) { add("Warrior") } // 13 over
        assertEquals(-65f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
        assertEquals(-65, growthPenalty())
        assertFalse(cannotTrainForSupply("Warrior"))

        add("Warrior") // 14 over: 70 = the cap
        assertEquals(-70f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
        assertTrue("70 / 5 = 14 units over", cannotTrainForSupply("Warrior"))
        assertFalse("a unit that uses no supply is still trained", cannotTrainForSupply("Worker"))

        repeat(5) { add("Warrior") } // 19 over: capped
        assertEquals(-70f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
        assertEquals(-70, growthPenalty())

        setConstants(productionPerUnit = 5, maxProduction = 70, growthPerUnit = 3, maxGrowth = 40)
        assertEquals("19 x 3 = 57, capped at 40", -40, growthPenalty())
    }

    @Test
    fun `the growth penalty takes its share of the food of a city`() {
        val city = addCities(1, 0 to 0).single()
        setSwitch(true)
        setConstants(productionPerUnit = 5, growthPerUnit = 5)
        repeat(7 + 3) { add("Warrior") }
        assertEquals(-15, growthPenalty())
        city.cityStats.update()
        val food = city.cityStats.excessFood
        assertTrue("food $food", food > 0f)
        val entry = city.cityStats.finalStatList["[Unit Supply] ([Growth])"]
        assertEquals(-0.15f * food, entry!!.food, 0.001f)
    }

    @Test
    fun `without the switch there is no growth penalty and no limit on the training`() {
        addCities(1, 0 to 0)
        setConstants(productionPerUnit = 5, growthPerUnit = 5)
        repeat(7 + 20) { add("Warrior") }
        assertEquals(0, growthPenalty())
        assertEquals(-70f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
        assertFalse(cannotTrainForSupply("Warrior"))
    }

    @Test
    fun `barbarians have no supply limit`() {
        val barbarian = game.addBarbarianCiv()
        setSwitch(true)
        val breakdown = barbarian.stats.javaClass.methods.first { it.name == "getUnitSupplyBreakdown" && it.parameterCount == 1 }.invoke(barbarian.stats, false)
        assertEquals(Int.MAX_VALUE, breakdown.javaClass.getMethod("getTotal").invoke(breakdown))
        assertEquals(Int.MAX_VALUE, barbarian.stats.getUnitSupply())
        assertEquals(0, barbarian.stats.getUnitSupplyDeficit())
    }

    private fun cannotTrainForSupply(unitName: String): Boolean {
        val city = civ.cities.first()
        val unit = game.ruleset.units[unitName]!!
        return unit.getRejectionReasons(civ, city).any { it.type.name == "OutOfUnitSupply" }
    }
}
