package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The unit supply an AI major civilization or a city-state has comes from the AI and city-state columns of the difficulty the game is played
 *  at, as in CvPlayer::GetUnitSupplyBreakdown, GetNumUnitsSuppliedByHandicap, ...ByCities and ...ByPopulation (Community Patch): (7 + 3 per city
 *  + half the citizens) x (100 + the level's bonus percent) / 100. A human has the player settings of the level.
 */
@RunWith(BaseTestRunner::class)
class AiUnitSupplyTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, Constants.grassland)
        game.setDifficulty("Prince")
    }

    private fun tile(x: Int, y: Int) = game.getTile(HexCoord(x, y))

    private fun addCities(civ: Civilization, population: Int, vararg positions: Pair<Int, Int>) {
        for ((x, y) in positions) game.addCity(civ, tile(x, y), initialPopulation = population)
    }

    @Test
    fun `an AI major on Prince with 3 cities of 4 citizens has 7 plus 9 plus 6, plus 20 percent`() {
        val ai = game.addCiv()
        addCities(ai, 4, 0 to 0, 4 to 0, -4 to 0)
        assertEquals(12, ai.cities.sumOf { it.population.population })
        assertEquals(7, ai.stats.getBaseUnitSupply())
        assertEquals(9, ai.stats.getUnitSupplyFromCities())
        assertEquals(6, ai.stats.getUnitSupplyFromPop())
        assertEquals((7 + 9 + 6) * 120 / 100, ai.stats.getUnitSupply())
        assertEquals(26, ai.stats.getUnitSupply())
    }

    @Test
    fun `a human on Prince has the player settings 5 plus 2 per city plus half the citizens, without a bonus`() {
        val human = game.addCiv(isPlayer = true)
        addCities(human, 4, 0 to 0, 4 to 0, -4 to 0)
        assertEquals(5 + 6 + 6, human.stats.getUnitSupply())
    }

    @Test
    fun `a human at Settler has 10 plus 3 per city, an AI at Settler still 7 plus 3 per city`() {
        game.setDifficulty("Settler")
        val human = game.addCiv(isPlayer = true)
        val ai = game.addCiv()
        addCities(human, 2, 0 to 0)
        addCities(ai, 2, 4 to 0)
        assertEquals(10 + 3 + 1, human.stats.getUnitSupply())
        assertEquals(7 + 3 + 1, ai.stats.getUnitSupply())
    }

    @Test
    fun `the AI's bonus percent is the one of the difficulty the game is played at, not of its own handicap level`() {
        val ai = game.addCiv()
        addCities(ai, 2, 0 to 0)
        assertEquals("AI Default", ai.getDifficulty().name)
        // Settler, Chieftain, Warlord, Prince, King, Emperor, Immortal, Deity: the Community Patch's AIUnitSupplyBonusPercent
        val bonuses = listOf(0, 0, 10, 20, 30, 30, 40, 50)
        val levels = game.ruleset.difficulties.values.filter { it.isSelectable }.map { it.name }
        assertEquals(listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity"), levels)
        for ((level, bonus) in levels.zip(bonuses)) {
            game.setDifficulty(level)
            assertEquals(level, bonus, game.gameInfo.getDifficulty().aiUnitSupplyBonusPercent)
            assertEquals(level, (7 + 3 + 1) * (100 + bonus) / 100, ai.stats.getUnitSupply())
        }
    }

    @Test
    fun `the AI's own handicap level has no supply and every level has the Community Patch's 7, 3 and 50 for AIs and city-states`() {
        val aiDefault = game.ruleset.difficulties["AI Default"]!!
        assertEquals(0, aiDefault.unitSupplyBase)
        assertEquals(0, aiDefault.unitSupplyPerCity)
        for (level in game.ruleset.difficulties.values.filter { it.isSelectable }) {
            assertEquals(level.name, listOf(7, 3, 50), listOf(level.aiUnitSupplyBase, level.aiUnitSupplyPerCity, level.aiUnitSupplyPopulationPercent))
            assertEquals(level.name, listOf(7, 3, 50),
                listOf(level.cityStateUnitSupplyBase, level.cityStateUnitSupplyPerCity, level.cityStateUnitSupplyPopulationPercent))
            assertEquals(level.name, level.aiUnitSupplyBonusPercent, level.cityStateUnitSupplyBonusPercent)
        }
    }

    @Test
    fun `a city-state has the city-state columns of the difficulty, not the Prince player settings of its own level`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(0, 0), replacePalace = true, initialPopulation = 2)
        assertEquals((7 + 3 + 1) * 120 / 100, cityState.stats.getUnitSupply())
        game.setDifficulty("Deity")
        assertEquals((7 + 3 + 1) * 150 / 100, cityState.stats.getUnitSupply())
    }

    @Test
    fun `the unit supply of the uniques is part of what the bonus percent increases`() {
        val ai = game.addCiv("[3] Unit Supply")
        addCities(ai, 2, 0 to 0)
        // the level's 7 and the unique's 3 are the base, then 3 for the city and 1 for the two citizens: 14, plus 20 percent
        assertEquals(7 + 3, ai.stats.getBaseUnitSupply())
        assertEquals(3, ai.stats.getUnitSupplyFromCities())
        assertEquals(1, ai.stats.getUnitSupplyFromPop())
        assertEquals((10 + 3 + 1) * 120 / 100, ai.stats.getUnitSupply())
    }
}
