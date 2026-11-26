package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 building supply with `unitSupplyBuildingsLikeVp`: CvCity::processBuilding adds a building's CitySupplyFlat and CitySupplyModifier to its city
 *  (V:CvCity.cpp:13656-13657); CvPlayer::GetNumUnitsSuppliedByCities counts `(supply per city + the city's flat) x the city's share`, summed, / 100, then x 100 / the
 *  technology divisor (V:CvPlayer.cpp:17152-17153, :17179-17185); GetNumUnitsSuppliedByPopulation `citizens x share x (percent + the city's modifier) / 100` per city
 *  (V:CvPlayer.cpp:17206-17218).
 *
 *  A human on Prince has 5 + 2 per city + 50% of the citizens (`UnitSupplyTests`); no empire size divisor and no era reduction unless a test says so. The switches are set by
 *  reflection, so the file compiles against a parent without them. Every expected number is worked out by hand in the comment above it.
 */
@RunWith(BaseTestRunner::class)
class VpBuildingSupplyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.ruleset.modOptions.constants.unitSupplyLikeVp = false
        game.ruleset.modOptions.constants.unitSupplyConstants = ModConstants.UnitSupplyConstants()
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv(isPlayer = true)
        civ.tech.techsResearched.clear() // the start technologies of the ruleset would move the progress
    }

    private fun setFlag(name: String, on: Boolean) {
        val constants = game.ruleset.modOptions.constants
        constants.javaClass.getMethod(name, Boolean::class.javaPrimitiveType).invoke(constants, on)
    }

    private fun vpSupply(buildings: Boolean = true) {
        setFlag("setUnitSupplyLikeVp", true)
        setFlag("setUnitSupplyBuildingsLikeVp", buildings)
    }

    private fun setConstants(citiesTech: Int = 0, populationTech: Int = 0) {
        val constants = game.ruleset.modOptions.constants
        val type = Class.forName("com.unciv.models.ModConstants\$UnitSupplyConstants")
        val ints = Int::class.javaPrimitiveType
        val value = type.getConstructor(ints, ints, ints, ints, ints, ints, ints, ints).newInstance(0, citiesTech, populationTech, 0, 10, 70, 0, 70)
        constants.javaClass.getMethod("setUnitSupplyConstants", type).invoke(constants, value)
    }

    /** Know [percent] of the technologies: the progress is that percent (the divisor 100 + progress with a multiplier of 100) */
    private fun knowTechs(percent: Int) {
        val all = game.ruleset.technologies.keys.toList()
        civ.tech.techsResearched.addAll(all.take(all.size * percent / 100))
    }

    private fun cities(vararg population: Int) = population.mapIndexed { i, citizens ->
        val (x, y) = listOf(0 to 0, 4 to 0, -4 to 0)[i]
        game.addCity(civ, game.getTile(HexCoord(x, y)), initialPopulation = citizens)
    }

    private fun City.give(vararg uniques: String) = cityConstructions.addBuilding(game.createBuilding(*uniques))

    private fun breakdown(getter: String): Int {
        val method = civ.stats.javaClass.methods.first { it.name == "getUnitSupplyBreakdown" && it.parameterCount == 1 }
        val breakdown = method.invoke(civ.stats, true)
        return breakdown.javaClass.getMethod(getter).invoke(breakdown) as Int
    }

    @Test
    fun `a building's flat supply adds to the supply of its city and stacks`() {
        val cities = cities(4, 4, 4)
        vpSupply()
        // base 5, cities (2 + 2 + 2) = 6, citizens 3 x 4 x 50 / 100 = 6
        assertEquals(5 + 6 + 6, civ.stats.getUnitSupply())
        cities[2].give("[3] Unit Supply in this city")
        // cities 2 + 2 + (2 + 3) = 9
        assertEquals(5 + 9 + 6, civ.stats.getUnitSupply())
        cities[2].give("[1] Unit Supply in this city")
        // a second building of the city: 2 + 2 + (2 + 3 + 1) = 10
        assertEquals(5 + 10 + 6, civ.stats.getUnitSupply())
        assertEquals(10, breakdown("getCitiesNet"))
        assertEquals("the citizens' part is not touched by the flat", 6, breakdown("getPopulationNet"))
    }

    @Test
    fun `the flat supply goes through the puppet share and the technology divisor with the rest`() {
        val cities = cities(4, 4, 4)
        vpSupply()
        setConstants(citiesTech = 100, populationTech = 100)
        knowTechs(40)
        cities[1].isPuppet = true
        cities[1].give("[3] Unit Supply in this city")
        // progress 40, divisor 140. cities: (2 x 100 + (2 + 3) x 30 + 2 x 100) / 100 = 550 / 100 = 5, x 100 / 140 = 3 (3.57).
        // A flat outside the puppet share: (200 + 60 + 300 + 200) / 100 = 7 -> 5; a flat added after the divisor: 200 + 60 + 200 = 460 / 100 = 4, 4 x 100 / 140 = 2, + 3 = 5
        assertEquals(3, breakdown("getCitiesNet"))
        // citizens: 4 x 100 x 50 / 100 = 200, the puppet 4 x 30 x 50 / 100 = 60, 200: 460, x 100 / 140 = 328, / 100 = 3
        assertEquals(3, breakdown("getPopulationNet"))
        assertEquals(5 + 3 + 3, civ.stats.getUnitSupply())
    }

    @Test
    fun `a building's percent adds to the population percent of its own city only`() {
        val cities = cities(9, 9, 9)
        vpSupply()
        // citizens 9 x 100 x 50 / 100 = 450 each: 1350 / 100 = 13 (13.5)
        assertEquals(13, breakdown("getPopulationNet"))
        cities[1].give("[25]% Unit Supply from the citizens of this city")
        // the second city 9 x 100 x (50 + 25) / 100 = 675: 450 + 675 + 450 = 1575 / 100 = 15. A +25 on every city: 3 x 675 = 2025 / 100 = 20
        assertEquals(15, breakdown("getPopulationNet"))
        cities[1].give("[25]% Unit Supply from the citizens of this city")
        // two buildings add: 9 x 100 x 100 / 100 = 900: 450 + 900 + 450 = 1800 / 100 = 18
        assertEquals(18, breakdown("getPopulationNet"))
        assertEquals("the cities' part is not touched by the percent", 6, breakdown("getCitiesNet"))
    }

    @Test
    fun `a puppet's building percent is scaled by the puppet's share with its citizens`() {
        val cities = cities(8, 6, 6)
        vpSupply()
        setConstants(populationTech = 100)
        knowTechs(40)
        cities[1].isPuppet = true
        cities[1].give("[50]% Unit Supply from the citizens of this city")
        // divisor 140. citizens 8 x 100 x 50 / 100 = 400, the puppet 6 x 30 x (50 + 50) / 100 = 180, 6 x 100 x 50 / 100 = 300: 880 x 100 / 140 = 628, / 100 = 6.
        // Each wrong reading gives another number: without the percent 400 + 90 + 300 = 790 -> 5; the percent outside the share 790 + 6 x 100 x 50 / 100 = 1090 -> 7;
        // the percent on every city 800 + 180 + 600 = 1580 -> 11; the puppet share ignored 400 + 6 x 100 x 100 / 100 + 300 = 1300 -> 9
        assertEquals(6, breakdown("getPopulationNet"))
    }

    @Test
    fun `a building's player-wide percent applies to every city and adds to a city's own`() {
        val cities = cities(9, 9, 9)
        vpSupply()
        cities[2].give("[25]% Unit Supply from the citizens of all cities")
        // every city 9 x 100 x (50 + 25) / 100 = 675: 2025 / 100 = 20. On its own city only: 450 + 450 + 675 = 1575 -> 15; ignored: 13
        assertEquals(20, breakdown("getPopulationNet"))
        cities[1].give("[25]% Unit Supply from the citizens of this city")
        // the second city 9 x 100 x (50 + 25 + 25) / 100 = 900: 675 + 900 + 675 = 2250 / 100 = 22
        assertEquals(22, breakdown("getPopulationNet"))
        cities[0].give("[25]% Unit Supply from the citizens of all cities")
        // two player-wide buildings add, wherever they stand: 9 x 100 x (50 + 50) / 100 = 900 each, the second city 9 x 100 x 125 / 100 = 1125: 2925 / 100 = 29
        assertEquals(29, breakdown("getPopulationNet"))
        assertEquals("the cities' part is not touched", 6, breakdown("getCitiesNet"))
    }

    @Test
    fun `a puppet's share scales the player-wide percent too`() {
        val cities = cities(8, 6, 6)
        vpSupply()
        setConstants(populationTech = 100)
        knowTechs(40)
        cities[1].isPuppet = true
        cities[0].give("[50]% Unit Supply from the citizens of all cities")
        // divisor 140. 8 x 100 x 100 / 100 = 800, the puppet 6 x 30 x 100 / 100 = 180, 6 x 100 x 100 / 100 = 600: 1580 x 100 / 140 = 1128, / 100 = 11.
        // The puppet at full share: 800 + 600 + 600 = 2000 x 100 / 140 = 1428 -> 14; the percent ignored: 790 x 100 / 140 = 564 -> 5
        assertEquals(11, breakdown("getPopulationNet"))
    }

    @Test
    fun `without the building switch the player-wide percent is not read`() {
        cities(9, 9, 9)[0].give("[25]% Unit Supply from the citizens of all cities")
        vpSupply(buildings = false)
        assertEquals(13, breakdown("getPopulationNet"))
    }

    @Test
    fun `without the building switch the uniques are not read`() {
        val cities = cities(4, 4, 4)
        vpSupply(buildings = false)
        cities[0].give("[3] Unit Supply in this city", "[25]% Unit Supply from the citizens of this city")
        assertEquals(5 + 6 + 6, civ.stats.getUnitSupply())
    }

    @Test
    fun `without the supply switch the uniques are not read either`() {
        val cities = cities(4, 4, 4)
        setFlag("setUnitSupplyBuildingsLikeVp", true)
        cities[0].give("[3] Unit Supply in this city", "[25]% Unit Supply from the citizens of this city")
        assertEquals("the original's formula: 5 + 6 + 6", 5 + 6 + 6, civ.stats.getUnitSupply())
    }
}
