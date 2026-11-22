package com.unciv.dev

import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The economy rows [DecisionLog] writes for [FullGameSim]: AI_YIELD per living major civilization every 10th turn and AI_INSTANT for the instant yields it gained */
@RunWith(BaseTestRunner::class)
class EconomyRowsTests {
    private lateinit var game: TestGame
    private lateinit var file: File

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        file = File.createTempFile("decision-log", ".tsv").also { it.deleteOnExit() }
    }

    private fun rows(prefix: String) = file.readLines().filter { it.startsWith("$prefix\t") }.map { it.split('\t') }

    @Test
    fun `the sim writes an AI_YIELD row for every living major civ every 10th turn`() {
        val first = game.addCiv()
        val second = game.addCiv()
        game.addCity(first, game.getTile(HexCoord.Zero), initialPopulation = 3)
        game.addCity(second, game.getTile(HexCoord(3, 0)), initialPopulation = 2)
        game.addCiv() // no city and no unit: defeated, no row
        game.addCiv(cityStateType = "Cultured") // a city-state is not a major civilization, no row
        first.updateStatsForNextTurn()
        second.updateStatsForNextTurn()

        val log = DecisionLog(game.gameInfo, file)
        for (turn in 1..20) log.afterTurn(turn)

        val yields = rows("AI_YIELD")
        assertEquals(listOf("10", "10", "20", "20"), yields.map { it[1] })
        assertEquals(listOf(first.civName, second.civName, first.civName, second.civName), yields.map { it[2] })
        assertTrue(yields.all { it.size == 15 })
    }

    @Test
    fun `the header names the columns of both economy rows`() {
        DecisionLog(game.gameInfo, file)
        val header = file.readLines()
        assertTrue("# AI_YIELD\tturn\tname\tgold\tgoldPerTurn\tsciencePerTurn\tculturePerTurn\tfaith\tproduction\tfood\ttechs\tera\tpolicies\twonders\tbuildings" in header)
        assertTrue("# AI_INSTANT\tturn\tname\tevent\tstat\tamount" in header)
    }

    @Test
    fun `a row holds the treasury, what the civ makes in a turn, its cities' production and food, techs, era, policies, wonders and buildings`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 3)
        city.cityConstructions.addBuilding(game.createBuilding())
        city.cityConstructions.addBuilding(game.createBuilding())
        city.cityConstructions.addBuilding(game.createWonder())
        civ.addGold(123)
        civ.religionManager.storedFaith = 45
        civ.updateStatsForNextTurn()

        val row = EconomyColumns.rows(game.gameInfo, 30).lines().first { it.startsWith("AI_YIELD") }.split('\t')

        assertEquals(civ.civName, row[2])
        assertEquals("123", row[3])
        assertEquals(civ.stats.statsForNextTurn.gold.toInt().toString(), row[4])
        assertEquals(civ.stats.statsForNextTurn.science.toInt().toString(), row[5])
        assertEquals(civ.stats.statsForNextTurn.culture.toInt().toString(), row[6])
        assertEquals("45", row[7])
        assertEquals(city.cityStats.currentCityStats.production.toInt().toString(), row[8])
        assertEquals(city.cityStats.currentCityStats.food.toInt().toString(), row[9])
        assertEquals(civ.tech.researchedTechnologies.size.toString(), row[10])
        assertEquals(civ.getEraNumber().toString(), row[11])
        assertEquals(civ.policies.getAdoptedPolicies().size.toString(), row[12])
        assertEquals(city.cityConstructions.getBuiltBuildings().count { it.isAnyWonder() }.toString(), row[13]) // the wonder and the Palace, a national wonder
        assertTrue(row[13].toInt() >= 2)
        assertEquals(city.cityConstructions.getBuiltBuildings().count { !it.isAnyWonder() }.toString(), row[14])
        assertTrue(row[14].toInt() >= 2) // the two buildings, and the Palace and the rest of the city's start
    }

    @Test
    fun `an AI_INSTANT row has the cumulative total of each event and stat that is not 0, and a game with no instant yields has none`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))

        val quiet = DecisionLog(game.gameInfo, file)
        quiet.afterTurn(10)
        assertTrue(rows("AI_INSTANT").isEmpty())

        InstantYields.gain(civ, city, Stat.Gold, 7, Unique("Gain [7] [Gold] <upon population growth>"))
        InstantYields.gain(civ, city, Stat.Food, 3, Unique("Gain [3] [Food]"))
        InstantYields.gain(civ, city, Stat.Gold, 2, Unique("Gain [2] [Gold] <upon population growth>"))
        quiet.afterTurn(20)
        InstantYields.gain(civ, city, Stat.Gold, 1, Unique("Gain [1] [Gold] <upon population growth>"))
        quiet.afterTurn(30)

        val instant = rows("AI_INSTANT").map { it.drop(1) }
        val name = civ.civName
        assertEquals(listOf(
            listOf("20", name, "Direct", "Food", "3"), listOf("20", name, "TriggerUponPopulationGrowth", "Gold", "9"),
            listOf("30", name, "Direct", "Food", "3"), listOf("30", name, "TriggerUponPopulationGrowth", "Gold", "10"),
        ), instant)
    }
}
