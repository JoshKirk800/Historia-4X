package com.unciv.dev

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The happiness rows [DecisionLog] writes for [FullGameSim]: AI_HAPPY per living major civilization and AI_CITYHAPPY per city, every 10th turn */
@RunWith(BaseTestRunner::class)
class HappinessRowsTests {
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
    fun `the sim writes an AI_HAPPY row for every living major civ every 10th turn`() {
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

        val happy = rows("AI_HAPPY")
        assertEquals(listOf("10", "10", "20", "20"), happy.map { it[1] })
        assertEquals(listOf(first.civName, second.civName, first.civName, second.civName), happy.map { it[2] })
        assertEquals(first.getHappiness().toString(), happy[0][3])
        assertEquals(second.getHappiness().toString(), happy[1][3])
        assertTrue(happy.all { it.size == 7 })
        assertEquals(listOf(10, 10, 20, 20), rows("AI_CITYHAPPY").map { it[1].toInt() })
    }

    @Test
    fun `the header names the columns of both happiness rows`() {
        DecisionLog(game.gameInfo, file)
        val header = file.readLines()
        assertTrue("# AI_HAPPY\tturn\tname\thappiness\tpool\tlocalHappinessSum\tlocalUnhappinessSum" in header)
        assertTrue("# AI_CITYHAPPY\tturn\tname\tcity\tpopulation\tlocalHappiness\tlocalUnhappiness\tdelta" in header)
    }

    @Test
    fun `a city row holds its population, local happiness and the unhappiness of its size, and the civ row sums them`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 4)
        city.cityStats.update()
        civ.updateStatsForNextTurn()
        val unhappiness = -(city.cityStats.happinessList["Cities"]!! + city.cityStats.happinessList["Population"]!!)

        val text = HappinessColumns.rows(game.gameInfo, 30).lines().filter { it.isNotEmpty() }.map { it.split('\t') }
        val cityRow = text.single { it[0] == "AI_CITYHAPPY" }
        assertEquals(city.name, cityRow[3])
        assertEquals("4", cityRow[4])
        assertEquals(city.cityStats.localHappiness, cityRow[5].toFloat(), 0.01f)
        assertEquals(unhappiness, cityRow[6].toFloat(), 0.01f)
        assertEquals(city.cityStats.localHappiness - unhappiness, cityRow[7].toFloat(), 0.01f)
        val civRow = text.single { it[0] == "AI_HAPPY" }
        assertEquals(unhappiness, civRow[6].toFloat(), 0.01f)
    }
}
