package com.unciv.dev

import com.unciv.logic.civilization.SupplyTable
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The unit supply rows [DecisionLog] writes (`phaseE/m6-supply-deep.md` section 5.2): AI_SUPPLY per living major civilization every 10th turn */
@RunWith(GdxTestRunner::class)
class SupplyRowsTests {
    private lateinit var game: TestGame
    private lateinit var file: File

    @After
    fun restoreTable() = SupplyTable.restore(game.ruleset)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        SupplyTable.communityPatch(game.ruleset) // the supply of 9 and the penalties below are derived from the Community Patch's table, not from the one the ruleset ships
        game.makeHexagonalMap(8)
        file = File.createTempFile("decision-log", ".tsv").also { it.deleteOnExit() }
    }

    /** [count] warriors of [civ], each on a tile of its own (a tile holds one military unit), none on a city */
    private fun addWarriors(civ: com.unciv.logic.civilization.Civilization, count: Int) {
        val free = game.tileMap.values.filter { it.getCity() == null && it.militaryUnit == null }.take(count)
        assertEquals("the map has room for $count units", count, free.size)
        for (tile in free) game.addUnit("Warrior", civ, tile)
    }

    /** The value of the column [name] of the AI_SUPPLY row of [civName] */
    private fun value(civName: String, name: String): String {
        val row = SupplyColumns.rows(game.gameInfo, 10).lines().filter { it.startsWith("AI_SUPPLY\t") }.map { it.split('\t') }.first { it[2] == civName }
        return row[3 + SupplyColumns.columns.indexOfFirst { it.name == name }]
    }

    @Test
    fun `the header names every column of the row`() {
        DecisionLog(game.gameInfo, file)
        val expected = "# AI_SUPPLY\tturn\tname\tsupply\tunitsToSupply\tdeficit\tproductionPenalty\tgrowthPenalty\tcities\tpuppets\ttechProgress\tdifficulty\tcitiesPart\tpopulationPart\t" +
            "subtotal\tempireDivisor\tafterEmpireSize\tbonusPercent\tbreakdownTotal\t" +
            "Barracks\tStable\tHarbor\tArmory\tMilitaryAcademy\tWalls\tCastle\tArsenal\tMilitaryBase\tSeaport"
        assertTrue(expected in file.readLines())
    }

    @Test
    fun `the sim writes a row for every living major civ every 10th turn`() {
        val first = game.addCiv()
        val second = game.addCiv()
        game.addCity(first, game.getTile(HexCoord.Zero))
        game.addCity(second, game.getTile(HexCoord(3, 0)))
        game.addCiv() // no city and no unit: defeated, no row
        game.addCiv(cityStateType = "Cultured") // a city-state is not a major civilization, no row

        val log = DecisionLog(game.gameInfo, file)
        for (turn in 1..20) log.afterTurn(turn)

        val written = file.readLines().filter { it.startsWith("AI_SUPPLY\t") }.map { it.split('\t') }
        assertEquals(listOf("10", "10", "20", "20"), written.map { it[1] })
        assertEquals(listOf(first.civName, second.civName, first.civName, second.civName), written.map { it[2] })
        assertTrue(written.all { it.size == 3 + SupplyColumns.columns.size })
    }

    @Test
    fun `an AI civ with one city has the supply of the difficulty and no deficit`() {
        // Prince: base 7, 3 per city, 50 percent of the population, +20 for the AI (Difficulties.json). Technology progress 1 percent takes 1 off the cities' 3
        // (3 * 99 / 100 = 2), the population is 1 * 50 / 100 = 0, so the subtotal is 7 + 2 + 0 = 9; the empire size divisor is 100 + 5 per city = 105,
        // 9 * 100 / 105 = 8, and 8 * 120 / 100 = 9
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        assertEquals(
            listOf("9", "0", "0", "0", "0", "1", "0", "1", "7", "2", "0", "9", "105", "8", "20", "9"),
            SupplyColumns.columns.take(16).map { value(civ.civName, it.name) }
        )
    }

    @Test
    fun `the deficit is the units over the supply and the penalties are 5 percent each`() {
        // 14 warriors against a supply of 9: 5 out of supply, -25 percent production and -25 percent growth
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        addWarriors(civ, 14)
        assertEquals(listOf("9", "14", "5", "-25", "-25"), listOf("supply", "unitsToSupply", "deficit", "productionPenalty", "growthPenalty").map { value(civ.civName, it) })
    }

    @Test
    fun `each penalty column has its own rate`() {
        // production 5 per unit (the ruleset's), growth 2 per unit: 5 out of supply are -25 and -10, so a column that shows the other one is seen
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        game.ruleset.modOptions.constants.unitSupplyConstants = game.ruleset.modOptions.constants.unitSupplyConstants.copy(growthPenaltyPerUnit = 2)
        addWarriors(civ, 14)
        assertEquals(listOf("-25", "-10"), listOf("productionPenalty", "growthPenalty").map { value(civ.civName, it) })
    }

    @Test
    fun `a unit that uses no supply is not counted`() {
        // a worker has no combat strength: 3 warriors and 2 workers are 3 units to supply, not 5
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        addWarriors(civ, 3)
        val free = game.tileMap.values.filter { it.getCity() == null && it.civilianUnit == null }.take(2)
        for (tile in free) game.addUnit("Worker", civ, tile)
        assertEquals(5, civ.units.getCivUnitsSize())
        assertEquals("3", value(civ.civName, "unitsToSupply"))
    }

    @Test
    fun `a civ under its supply has no deficit, not a negative one`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        addWarriors(civ, 3)
        assertEquals(listOf("9", "3", "0", "0", "0"), listOf("supply", "unitsToSupply", "deficit", "productionPenalty", "growthPenalty").map { value(civ.civName, it) })
    }

    @Test
    fun `the penalty stops at 70 percent`() {
        // 9 + 14 = 23 units: 14 out of supply would be -70, 15 would be -75; 24 units are 15 out of supply
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        addWarriors(civ, 24)
        assertEquals(listOf("15", "-70", "-70"), listOf("deficit", "productionPenalty", "growthPenalty").map { value(civ.civName, it) })
    }

    @Test
    fun `puppets are counted apart from the cities`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        game.addCity(civ, game.getTile(HexCoord(3, 0))).isPuppet = true
        game.addCity(civ, game.getTile(HexCoord(0, 3))).isPuppet = true
        assertEquals(listOf("3", "2"), listOf("cities", "puppets").map { value(civ.civName, it) })
        // two puppets do not count for the empire size divisor: 100 + 5 * 1
        assertEquals("105", value(civ.civName, "empireDivisor"))
    }

    @Test
    fun `a supply building is counted once per city that has it`() {
        val civ = game.addCiv()
        val first = game.addCity(civ, game.getTile(HexCoord.Zero))
        val second = game.addCity(civ, game.getTile(HexCoord(3, 0)))
        val third = game.addCity(civ, game.getTile(HexCoord(0, 3)))
        for (city in listOf(first, second, third)) city.cityConstructions.addBuilding("Barracks")
        second.cityConstructions.addBuilding("Walls")
        third.cityConstructions.addBuilding("Harbor")
        third.cityConstructions.addBuilding("Military Academy")
        val counts = SupplyColumns.supplyBuildings.associateWith { value(civ.civName, it.replace(" ", "")) }
        assertEquals(
            mapOf("Barracks" to "3", "Stable" to "0", "Harbor" to "1", "Armory" to "0", "Military Academy" to "1", "Walls" to "1", "Castle" to "0", "Arsenal" to "0", "Military Base" to "0", "Seaport" to "0"),
            counts
        )
    }

    @Test
    fun `every building of the columns is in the ruleset`() {
        // a renamed or removed building would count 0 for ever and look like a civ that never built it
        for (building in SupplyColumns.supplyBuildings) assertTrue(building, building in game.ruleset.buildings)
    }

    @Test
    fun `the breakdown total is the supply the game reads`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        game.addCity(civ, game.getTile(HexCoord(3, 0)))
        assertEquals(value(civ.civName, "supply"), value(civ.civName, "breakdownTotal"))
    }
}
