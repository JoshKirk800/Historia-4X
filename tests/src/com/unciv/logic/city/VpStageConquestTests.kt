package com.unciv.logic.city

import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  VP 5.4.8 conquest and capture behind the switch `conquestLikeVp`: the citizens a conquered city keeps (CvPlayer::acquireCity "Slaughtering time!",
 *  CITY_CAPTURE_POPULATION_PERCENT, CvPlayerCulture::GetInfluenceCityConquestReduction with BALANCE_CONQUEST_REDUCTION_BOOST), the resistance turns,
 *  the damage a conquered city keeps (CITY_CAPTURE_DAMAGE_PERCENT, CvCity::setDamage), the chance a building survives (CvPlayer::IsValidBuildingForPlayer) and
 *  the health and moves of a captured military unit (CvUnit::createCaptureUnit, COMBAT_CAPTURE_HEALTH).
 */
@RunWith(BaseTestRunner::class)
class VpStageConquestTests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private fun newGame(switchOn: Boolean = true): TestGame {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        setFlag(game, "conquestLikeVp", switchOn)
        setFlag(game, "cityNeedsLikeVp", false) // resistance is then the population (times the reduction), whatever the ruleset says
        // otherwise test crashes when puppetying city
        game.gameInfo.currentPlayerCiv = game.addCiv()
        game.gameInfo.currentPlayer = game.gameInfo.currentPlayerCiv.civID
        return game
    }

    /** A city of [population] citizens at the center of the map and a second one far away, so that [owner] is not defeated when the first is taken */
    private fun cityWithSpare(game: TestGame, owner: Civilization, population: Int): City {
        val city = game.addCity(owner, game.getTile(HexCoord.Zero), initialPopulation = population)
        game.addCity(owner, game.getTile(HexCoord(0, -6)))
        return city
    }

    private fun conquer(city: City, by: Civilization) = CityConquestFunctions(city).puppetCity(by)

    //region Population

    @Test
    fun `a conquered city keeps 76 percent of its citizens, rounded down and at least one`() {
        val constants = ModConstants.ConquestConstants()
        fun kept(population: Int, reduction: Int = 5) = CityConquestFunctions.retainedPopulationLikeVp(population, reduction, constants)
        assertEquals(1, kept(1))
        assertEquals(1, kept(2)) // 152 / 100
        assertEquals(2, kept(3)) // 228 / 100
        assertEquals(3, kept(4)) // 304 / 100
        assertEquals(7, kept(10)) // 760 / 100
        assertEquals(18, kept(25, reduction = 0)) // 75 percent: 1875 / 100
        assertEquals(19, kept(25)) // the boost of 5 gives 1 percent back: 1900 / 100
        assertEquals(75, kept(100, reduction = 0))
        assertEquals(76, kept(100))
    }

    @Test
    fun `the reduction gives back its share of what is lost, 100 keeps everybody`() {
        val constants = ModConstants.ConquestConstants()
        assertEquals(8, CityConquestFunctions.retainedPopulationLikeVp(10, 30, constants)) // 75 + 30 * 25 / 100 = 82 percent
        assertEquals(3, CityConquestFunctions.retainedPopulationLikeVp(4, 30, constants)) // 328 / 100
        assertEquals(10, CityConquestFunctions.retainedPopulationLikeVp(10, 100, constants))
        assertEquals(1, CityConquestFunctions.retainedPopulationLikeVp(1, 0, constants))
        val communityPatch = ModConstants.ConquestConstants(capturePopulationPercent = 50, conquestReductionBoost = 0)
        assertEquals(1, CityConquestFunctions.retainedPopulationLikeVp(2, 0, communityPatch))
        assertEquals(1, CityConquestFunctions.retainedPopulationLikeVp(3, 0, communityPatch))
        assertEquals(2, CityConquestFunctions.retainedPopulationLikeVp(4, 0, communityPatch))
        assertEquals(5, CityConquestFunctions.retainedPopulationLikeVp(10, 0, communityPatch))
        assertEquals(6, CityConquestFunctions.retainedPopulationLikeVp(10, 25, communityPatch)) // 50 + 25 * 50 / 100 = 62 percent
        // never more than all
        assertEquals(10, CityConquestFunctions.retainedPopulationLikeVp(10, 0, ModConstants.ConquestConstants(capturePopulationPercent = 150)))
    }

    @Test
    fun `the reduction by influence is the level's value plus the boost, within 0 to 100`() {
        val constants = ModConstants.ConquestConstants()
        fun reduction(level: InfluenceLevel?) = CityConquestFunctions.influenceReductionLikeVp(level, constants)
        assertEquals(5, reduction(null)) // not met, or no major civilization: the boost alone
        assertEquals(5, reduction(InfluenceLevel.Unknown))
        assertEquals(15, reduction(InfluenceLevel.Exotic))
        assertEquals(30, reduction(InfluenceLevel.Familiar))
        assertEquals(55, reduction(InfluenceLevel.Popular))
        assertEquals(80, reduction(InfluenceLevel.Influential))
        assertEquals(100, reduction(InfluenceLevel.Dominant)) // 105 limited
    }

    @Test
    fun `with the key a city of 4 keeps 3 citizens and resistance is reduced by the boost`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 4)
        conquer(city, attacker)
        assertEquals(3, city.population.population)
        assertEquals(3 * 95 / 100, city.getFlag(CityFlags.Resistance)) // between two major civilizations
    }

    @Test
    fun `without the key a city of 4 loses 2 citizens, 1 + 4 div 4, and resistance is the population`() {
        val game = newGame(switchOn = false)
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 4)
        conquer(city, attacker)
        assertEquals(2, city.population.population)
        assertEquals(2, city.getFlag(CityFlags.Resistance))
    }

    @Test
    fun `the boost reduces resistance only between two major civilizations`() {
        val game = newGame()
        val cityState = game.addCiv(cityStateType = "Cultured")
        val attacker = game.addCiv()
        val city = cityWithSpare(game, cityState, 4)
        conquer(city, attacker)
        assertEquals(3, city.population.population) // the population part reads the boost for every old owner
        assertEquals(3, city.getFlag(CityFlags.Resistance))
    }

    @Test
    fun `dominant influence keeps every citizen and leaves one turn of resistance`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 10)
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        defender.totalCultureForContests = 1000
        attacker.culture.addInfluence(defender, 2000)
        conquer(city, attacker)
        assertEquals(10, city.population.population)
        assertEquals(1, city.getFlag(CityFlags.Resistance)) // max(0, 1), CvPlayer.cpp ChangeResistanceTurns
    }

    @Test
    fun `the founder retaking its city kills nobody`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 10)
        conquer(city, attacker)
        assertEquals(7, city.population.population)
        city.removeFlag(CityFlags.Resistance)
        city.population.setPopulation(10)
        conquer(city, defender)
        assertEquals(10, city.population.population)
    }

    @Test
    fun `a city still in resistance is not slaughtered again`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val third = game.addCiv()
        val city = cityWithSpare(game, defender, 10)
        conquer(city, attacker)
        assertEquals(7, city.population.population)
        assertTrue(city.isInResistance())
        conquer(city, third)
        assertEquals(7, city.population.population)
    }

    //endregion
    //region City health

    @Test
    fun `a conquered city keeps its damage up to half of its maximum, rounded down, and one hit point`() {
        val constants = ModConstants.ConquestConstants()
        assertEquals(100, CityConquestFunctions.capturedCityHealthLikeVp(200, 199, constants))
        assertEquals(101, CityConquestFunctions.capturedCityHealthLikeVp(201, 200, constants)) // 201 * 50 / 100 = 100 damage at most
        assertEquals(170, CityConquestFunctions.capturedCityHealthLikeVp(200, 30, constants))
        assertEquals(200, CityConquestFunctions.capturedCityHealthLikeVp(200, 0, constants))
        assertEquals(1, CityConquestFunctions.capturedCityHealthLikeVp(1, 0, constants))
        assertEquals(1, CityConquestFunctions.capturedCityHealthLikeVp(2, 1, constants))
        val everything = ModConstants.ConquestConstants(cityCaptureDamagePercent = 100)
        assertEquals(1, CityConquestFunctions.capturedCityHealthLikeVp(200, 199, everything)) // maximum damage is one less than the maximum
    }

    @Test
    fun `a city taken at one hit point has half of its maximum, a city that lost 10 keeps the loss`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 6)
        val max = CityCombatant(city).getMaxHealth()
        city.health = max - 10
        conquer(city, attacker)
        val maxAfter = CityCombatant(city).getMaxHealth()
        assertEquals(10, maxAfter - city.health)

        val other = game.addCiv()
        val second = game.getTile(HexCoord(0, -6)).getCity()!! // the spare city
        second.health = 1
        conquer(second, other)
        val secondMax = CityCombatant(second).getMaxHealth()
        assertEquals(secondMax - secondMax * 50 / 100, second.health)
    }

    @Test
    fun `without the key a conquered city has half of its maximum whatever damage it had`() {
        val game = newGame(switchOn = false)
        // The legacy rule is the original's, with its fixed maximum of 200: the Brave New World ruleset switches Vox Populi's population-based maximum on, and the
        // citizens the capture takes then lower the half that the city was given before
        setFlag(game, "cityCombatLikeVp", false)
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 6)
        city.health = CityCombatant(city).getMaxHealth() - 10
        conquer(city, attacker)
        assertEquals(CityCombatant(city).getMaxHealth() / 2, city.health)
    }

    //endregion
    //region Buildings

    private val survives = "Has a [%d]%% chance of surviving when the city is captured"

    private fun addBuildings(game: TestGame, city: City, count: Int, vararg uniques: String): List<String> = List(count) {
        val building = game.createBuilding(*uniques)
        city.cityConstructions.addBuilding(building)
        building.name
    }

    @Test
    fun `buildings with a chance of 100 all survive, with a chance of 0 none, with the key`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 4)
        val safe = addBuildings(game, city, 40, survives.format(100))
        val doomed = addBuildings(game, city, 10, survives.format(0))
        conquer(city, attacker)
        val left = city.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
        assertTrue(safe.all { it in left })
        assertTrue(doomed.none { it in left })
    }

    @Test
    fun `a building without a unique survives with the constant chance`() {
        val game = newGame()
        game.ruleset.modOptions.constants.conquestConstants = ModConstants.ConquestConstants(buildingSurvivalPercent = 100)
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 4)
        val plain = addBuildings(game, city, 40)
        conquer(city, attacker)
        val left = city.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
        assertTrue(plain.all { it in left })

        val game0 = newGame()
        game0.ruleset.modOptions.constants.conquestConstants = ModConstants.ConquestConstants(buildingSurvivalPercent = 0)
        val city0 = cityWithSpare(game0, game0.addCiv(), 4)
        val plain0 = addBuildings(game0, city0, 40)
        conquer(city0, game0.addCiv())
        val left0 = city0.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
        assertTrue(plain0.none { it in left0 })
    }

    @Test
    fun `the destroyed unique wins over the chance`() {
        val game = newGame()
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 4)
        val doomed = addBuildings(game, city, 10, survives.format(100), "Destroyed when the city is captured")
        conquer(city, attacker)
        val left = city.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
        assertTrue(doomed.none { it in left })
    }

    @Test
    fun `without the key the chance is not read and 34 percent are destroyed`() {
        val game = newGame(switchOn = false)
        val defender = game.addCiv()
        val attacker = game.addCiv()
        val city = cityWithSpare(game, defender, 4)
        val buildings = addBuildings(game, city, 40, survives.format(100))
        conquer(city, attacker)
        val left = city.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
        assertFalse("40 buildings survive 34% removal each only with a chance of 1 in 10 million", buildings.all { it in left })
        assertTrue(buildings.any { it in left })
    }

    //endregion
    //region Captured units

    @Test
    fun `a captured unit has what the damage share leaves, rounded down`() {
        val constants = ModConstants.ConquestConstants()
        assertEquals(25, BattleUnitCapture.capturedUnitHealthLikeVp(100, constants)) // damage 75
        assertEquals(3, BattleUnitCapture.capturedUnitHealthLikeVp(10, constants)) // 10 * 75 / 100 = 7 damage
        assertEquals(1, BattleUnitCapture.capturedUnitHealthLikeVp(1, constants)) // 0 damage would keep 1; 1 * 75 / 100 = 0
        assertEquals(50, BattleUnitCapture.capturedUnitHealthLikeVp(100, ModConstants.ConquestConstants(combatCaptureHealthPercent = 50)))
        val asHealth = ModConstants.ConquestConstants(combatCaptureHealthIsDamage = false)
        assertEquals(75, BattleUnitCapture.capturedUnitHealthLikeVp(100, asHealth))
        assertEquals(7, BattleUnitCapture.capturedUnitHealthLikeVp(10, asHealth))
        assertEquals(125 - 93, BattleUnitCapture.capturedUnitHealthLikeVp(125, constants)) // 125 * 75 / 100 = 93 damage
        // setDamage limits the damage to the maximum; a unit with no hit points left would be dead, it keeps 1
        assertEquals(1, BattleUnitCapture.capturedUnitHealthLikeVp(100, ModConstants.ConquestConstants(combatCaptureHealthPercent = 150)))
    }

    /** The warrior's attack takes the unit of the other civilization (`When defeating [Military] unit ... recruit it`) and returns the unit it got */
    private fun captureWithAttack(game: TestGame): com.unciv.logic.map.mapunit.MapUnit {
        val attackerCiv = game.addCiv("When defeating a [Military] unit, earn [0] Gold and recruit it")
        val defenderCiv = game.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()
        val attacker = game.addUnit("Warrior", attackerCiv, game.getTile(0, 0))
        val defender = game.addUnit("Warrior", defenderCiv, game.getTile(1, 0))
        defender.health = 1
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))
        return attackerCiv.units.getCivUnits().first { it != attacker }
    }

    @Test
    fun `a captured warrior has 25 health and its moves but has made its attacks with the key`() {
        val game = newGame()
        val captured = captureWithAttack(game)
        assertEquals(25, captured.health)
        assertEquals(captured.getMaxMovement().toFloat(), captured.currentMovement)
        assertEquals(captured.maxAttacksPerTurn(), captured.attacksThisTurn)
    }

    @Test
    fun `a captured warrior has 50 health and no moves without the key`() {
        val game = newGame(switchOn = false)
        val captured = captureWithAttack(game)
        assertEquals(50, captured.health)
        assertEquals(0f, captured.currentMovement)
        assertEquals(0, captured.attacksThisTurn)
    }

    //endregion
}
