package com.unciv.models.ruleset

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The economy constants that are configurable since `parity/vp-constants`, each with the numbers of Brave New World as default, and the values Vox Populi has
 *  (DefineChanges.sql) that the Brave New World ruleset's ModOptions.json sets now: the maintenance divisor, the science of each citizen, the growth threshold,
 *  the great person thresholds and the culture cost of the next tile.
 */
@RunWith(GdxTestRunner::class)
class VpConstantsTests {

    private fun bnwGame(size: Int = 6): TestGame {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(size)
        game.setSpeed("Standard")
        game.setDifficulty("Prince")
        return game
    }

    private val ruleset get() = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset.modOptions.constants

    // region the ruleset

    @Test
    fun `the Brave New World ruleset has the values of Vox Populi`() {
        val constants = ruleset
        assertEquals(5f, constants.unitMaintenanceExponentDivisor, 0f)
        assertEquals(0f, constants.sciencePerPopulation, 0f)
        assertEquals(ModConstants.CityGrowthConstants(base = 15, multiplier = 12f, exponent = 2.22f), constants.cityGrowth)
        assertEquals(150, constants.greatPersonThresholdBase)
        assertEquals(250, constants.greatPersonThresholdIncrease)
        assertEquals(ModConstants.BorderCultureConstants(first = 20, laterPlotMultiplier = 12, exponent = 1.35), constants.borderCulture)
    }

    @Test
    fun `the defaults of the constants are the numbers of Brave New World`() {
        val defaults = ModConstants()
        assertEquals(1f, defaults.sciencePerPopulation, 0f)
        assertEquals(ModConstants.CityGrowthConstants(base = 15, multiplier = 8f, exponent = 1.5f), defaults.cityGrowth)
        assertEquals(100, defaults.greatPersonThresholdBase)
        assertEquals(100, defaults.greatPersonThresholdIncrease)
        assertEquals(ModConstants.BorderCultureConstants(first = 15, laterPlotMultiplier = 10, exponent = 1.1), defaults.borderCulture)
    }

    @Test
    fun `Gods and Kings keeps its numbers`() {
        val constants = TestGame(baseRuleset = BaseRuleset.Civ_V_GnK).ruleset.modOptions.constants
        assertEquals(7f, constants.unitMaintenanceExponentDivisor, 0f)
        assertEquals(1f, constants.sciencePerPopulation, 0f)
        assertEquals(ModConstants().cityGrowth, constants.cityGrowth)
        assertEquals(100, constants.greatPersonThresholdBase)
        assertEquals(100, constants.greatPersonThresholdIncrease)
        assertEquals(ModConstants().borderCulture, constants.borderCulture)
    }

    // endregion

    // region unit maintenance: (paid x 0.5 x (1 + 8 x progress)) ^ (1 + progress / divisor), progress = turn / turns of the speed

    private fun upkeepOfWarriors(divisor: Float?, turn: Int, units: Int): Int {
        val game = bnwGame(4)
        divisor?.let { game.ruleset.modOptions.constants.unitMaintenanceExponentDivisor = it }
        val civ = game.addCiv(isPlayer = true)
        game.addCity(civ, game.tileMap[0, 0])
        assertEquals(500, game.gameInfo.speed.numTotalTurns())
        game.gameInfo.turns = turn
        repeat(units) { game.addUnit("Warrior", civ, null) }
        return -civ.stats.getStatMapForNextTurn()["Unit upkeep"]!!.gold.toInt()
    }

    @Test
    fun `the maintenance divisor of the ruleset is Vox Populi's 5`() {
        // 25 ^ (1 + 0.5 / 5) = 25 ^ 1.1 = 34.5 and 15.6 ^ (1 + 0.2 / 5) = 17.4
        assertEquals(34, upkeepOfWarriors(null, 250, 10))
        assertEquals(17, upkeepOfWarriors(null, 100, 12))
    }

    @Test
    fun `a divisor of 7 gives the maintenance of Brave New World`() {
        // 25 ^ (1 + 0.5 / 7) = 31.5 and 15.6 ^ (1 + 0.2 / 7) = 16.9
        assertEquals(31, upkeepOfWarriors(7f, 250, 10))
        assertEquals(16, upkeepOfWarriors(7f, 100, 12))
    }

    @Test
    fun `the divisor is the one of the constants`() {
        // 25 ^ (1 + 0.5 / 10) = 25 ^ 1.05 = 29.3, and at the start of the game the exponent is 1 whatever the divisor is
        assertEquals(29, upkeepOfWarriors(10f, 250, 10))
        assertEquals(upkeepOfWarriors(5f, 0, 10), upkeepOfWarriors(10f, 0, 10))
    }

    // endregion

    // region the science of each citizen

    private fun populationScience(sciencePerPopulation: Float?, population: Int): Float? {
        val game = bnwGame()
        sciencePerPopulation?.let { game.ruleset.modOptions.constants.sciencePerPopulation = it }
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = population)
        city.cityStats.update()
        return city.cityStats.finalStatList["Population"]?.science
    }

    @Test
    fun `each citizen gives the science of the constant`() {
        assertEquals(4f, populationScience(1f, 4)!!, 0.001f)
        assertEquals(2f, populationScience(0.5f, 4)!!, 0.001f)
        assertEquals(12f, populationScience(2f, 6)!!, 0.001f)
    }

    @Test
    fun `the ruleset gives no science for citizens and a city whose citizens all work does not break`() {
        assertEquals(0f, populationScience(null, 4) ?: 0f, 0f)
        // a city of 1 with its citizen on a tile has no stats from its population at all: the food it eats is still booked there
        val game = bnwGame()
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 1)
        city.cityStats.update()
        assertEquals(-2f, city.cityStats.finalStatList["Population"]!!.food, 0.001f)
    }

    // endregion

    // region the growth threshold: base + int((p - 1) x multiplier) + floor((p - 1) ^ exponent), Standard speed and a human civilization

    private fun foodToGrow(growth: ModConstants.CityGrowthConstants?, population: Int): Int {
        val game = bnwGame()
        growth?.let { game.ruleset.modOptions.constants.cityGrowth = it }
        val civ: Civilization = game.addCiv(isPlayer = true)
        val city: City = game.addCity(civ, game.getTile(HexCoord.Zero))
        assertEquals(1f, game.gameInfo.speed.modifier, 0f)
        assertEquals(100, game.gameInfo.getStartingEra().growthPercent)
        city.population.setPopulation(population)
        return city.population.getFoodToNextPopulation()
    }

    private val bnwGrowth = ModConstants.CityGrowthConstants(base = 15, multiplier = 8f, exponent = 1.5f)

    @Test
    fun `the default growth is the one of Brave New World`() {
        // 15 + 8 x (p - 1) + floor((p - 1) ^ 1.5)
        assertEquals(15, foodToGrow(bnwGrowth, 1))
        assertEquals(55, foodToGrow(bnwGrowth, 5)) // 15 + 32 + 8
        assertEquals(114, foodToGrow(bnwGrowth, 10)) // 15 + 72 + 27
    }

    @Test
    fun `the ruleset grows like Vox Populi`() {
        // 15 + 12 x (p - 1) + floor((p - 1) ^ 2.22)
        assertEquals(15, foodToGrow(null, 1))
        assertEquals(84, foodToGrow(null, 5)) // 15 + 48 + floor(21.7)
        assertEquals(254, foodToGrow(null, 10)) // 15 + 108 + floor(131.3)
    }

    @Test
    fun `the multiplier is cut to a whole number before the exponent is added and the base is added`() {
        // 20 + int(2 x 10.5) + floor(2 ^ 1.5 = 2.83), and with the exponent 2: 20 + 21 + 4
        assertEquals(43, foodToGrow(ModConstants.CityGrowthConstants(base = 20, multiplier = 10.5f, exponent = 1.5f), 3))
        assertEquals(45, foodToGrow(ModConstants.CityGrowthConstants(base = 20, multiplier = 10.5f, exponent = 2f), 3))
    }

    // endregion

    // region great people: (base + increase x the Great People of the group) x speed x starting era

    private fun required(base: Int?, increase: Int?, gained: List<String>, unit: String): Int {
        val game = bnwGame()
        base?.let { game.ruleset.modOptions.constants.greatPersonThresholdBase = it }
        increase?.let { game.ruleset.modOptions.constants.greatPersonThresholdIncrease = it }
        val civ = game.addCiv(isPlayer = true)
        game.addCity(civ, game.getTile(HexCoord.Zero))
        assertEquals(1f, game.gameInfo.speed.modifier, 0f)
        assertEquals(100, game.gameInfo.getStartingEra().greatPeoplePercent)
        for (name in gained) civ.greatPeople.countGreatPersonGained(game.ruleset.units[name]!!)
        return civ.greatPeople.getPointsRequiredForGreatPerson(unit)
    }

    @Test
    fun `the ruleset has Vox Populi's thresholds, 150 and 250 more for each Great Person of the kind`() {
        assertEquals(150, required(null, null, emptyList(), "Great Scientist"))
        assertEquals(400, required(null, null, listOf("Great Scientist"), "Great Scientist"))
        assertEquals(650, required(null, null, listOf("Great Scientist", "Great Scientist"), "Great Scientist"))
    }

    @Test
    fun `the thresholds are those of the constants`() {
        assertEquals(100, required(100, 100, emptyList(), "Great Scientist"))
        assertEquals(200, required(100, 100, listOf("Great Scientist"), "Great Scientist"))
        assertEquals(80, required(80, 30, emptyList(), "Great Engineer"))
        assertEquals(170, required(80, 30, listOf("Great Engineer", "Great Engineer", "Great Engineer"), "Great Engineer"))
    }

    @Test
    fun `the Great People of each kind count apart, as with the separate counters of Vox Populi`() {
        val gained = listOf("Great Scientist", "Great Scientist")
        assertEquals(650, required(null, null, gained, "Great Scientist"))
        for (other in listOf("Great Merchant", "Great Engineer", "Great Writer", "Great Artist", "Great Musician"))
            assertEquals(other, 150, required(null, null, gained, other))
        assertEquals(400, required(null, null, listOf("Great Merchant"), "Great Merchant"))
        val units = bnwGame().ruleset.units
        fun group(unit: String) = units[unit]!!.getMatchingUniques(UniqueType.GPPointPool).single().params[0]
        assertEquals("the Merchant of Venice is in the group of the Great Merchant it replaces", group("Great Merchant"), group("Merchant of Venice"))
    }

    @Test
    fun `no two of the six Great People kinds share a group`() {
        val game = bnwGame()
        val civ = game.addCiv(isPlayer = true)
        val kinds = listOf("Great Scientist", "Great Merchant", "Great Engineer", "Great Writer", "Great Artist", "Great Musician")
        val groups = kinds.map { civ.greatPeople.getPoolKey(it) }
        assertEquals(groups.toString(), kinds.size, groups.toSet().size)
        assertFalse("" in groups)
        assertNotEquals(civ.greatPeople.getPoolKey("Great Prophet"), civ.greatPeople.getPoolKey("Great Scientist"))
    }

    // endregion

    // region the culture cost of the next tile: first + int((level x multiplier) ^ exponent), x the speed, rounded down to 5 (cultureCostLikeCivV)

    private fun cultureCost(border: ModConstants.BorderCultureConstants?, level: Int): Int {
        val game = bnwGame()
        border?.let { game.ruleset.modOptions.constants.borderCulture = it }
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        assertEquals(1f, game.gameInfo.speed.cultureCostModifier, 0f)
        city.expansion.cultureLevel = level
        return city.expansion.getCultureToNextTile()
    }

    @Test
    fun `the default cost of a tile is the one of Brave New World`() {
        val bnw = ModConstants.BorderCultureConstants()
        // 15, 15 + int(12.6) = 27, 15 + int(73.7) = 88, 15 + int(158.5) = 173
        assertEquals(listOf(15, 25, 85, 170), listOf(0, 1, 5, 10).map { cultureCost(bnw, it) })
    }

    @Test
    fun `the ruleset's cost of a tile is Vox Populi's`() {
        // 20, 20 + int(12 ^ 1.35 = 28.6) = 48, 20 + int(60 ^ 1.35 = 251.4) = 271, 20 + int(120 ^ 1.35 = 641.7) = 661
        assertEquals(listOf(20, 45, 270, 660), listOf(0, 1, 5, 10).map { cultureCost(null, it) })
    }

    @Test
    fun `the cost is made of the three numbers`() {
        // 10 + int((3 x 5) ^ 2) = 235, and 10 + int((4 x 5) ^ 2) = 410
        val border = ModConstants.BorderCultureConstants(first = 10, laterPlotMultiplier = 5, exponent = 2.0)
        assertEquals(235, cultureCost(border, 3))
        assertEquals(410, cultureCost(border, 4))
    }

    // endregion
}
