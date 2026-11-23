package com.unciv.dev

import com.unciv.logic.map.HexCoord
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The trade rows [DecisionLog] writes (slice T0 of `phaseE/vp-trade-plan.md`): AI_TRADE and AI_MONOPOLY per living major civilization every 10th turn */
@RunWith(GdxTestRunner::class)
class TradeRowsTests {
    private lateinit var game: TestGame
    private lateinit var file: File

    private val landUnique = "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        file = File.createTempFile("decision-log", ".tsv").also { it.deleteOnExit() }
    }

    private fun rows(prefix: String) = file.readLines().filter { it.startsWith("$prefix\t") }.map { it.split('\t') }

    private fun rowOf(prefix: String, civName: String, turn: Int = 10) =
        TradeColumns.rows(game.gameInfo, turn).lines().filter { it.startsWith("$prefix\t") }.map { it.split('\t') }.first { it[2] == civName }

    @Test
    fun `the header names the columns of both trade rows`() {
        DecisionLog(game.gameInfo, file)
        val header = file.readLines()
        assertTrue("# AI_TRADE\tturn\tname\tinternational\tdomestic\tplundered\tgold\tscience\tculture\tpartners" in header)
        assertTrue("# AI_MONOPOLY\tturn\tname\tglobalMonopolies\tstrategicMonopolies\tresources" in header)
    }

    @Test
    fun `the sim writes both rows for every living major civ every 10th turn`() {
        val first = game.addCiv()
        val second = game.addCiv()
        game.addCity(first, game.getTile(HexCoord.Zero))
        game.addCity(second, game.getTile(HexCoord(3, 0)))
        game.addCiv() // no city and no unit: defeated, no row
        game.addCiv(cityStateType = "Cultured") // a city-state is not a major civilization, no row

        val log = DecisionLog(game.gameInfo, file)
        for (turn in 1..20) log.afterTurn(turn)

        for (prefix in listOf("AI_TRADE", "AI_MONOPOLY")) {
            val written = rows(prefix)
            assertEquals(prefix, listOf("10", "10", "20", "20"), written.map { it[1] })
            assertEquals(prefix, listOf(first.civName, second.civName, first.civName, second.civName), written.map { it[2] })
        }
        assertTrue(rows("AI_TRADE").all { it.size == 10 })
        assertTrue(rows("AI_MONOPOLY").all { it.size == 6 })
    }

    @Test
    fun `a civ with no routes and no monopoly resources has zeros`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        assertEquals(listOf("0", "0", "0", "0", "0", "0", "0"), rowOf("AI_TRADE", civ.civName).drop(3))
        assertEquals(listOf("0", "0", "-"), rowOf("AI_MONOPOLY", civ.civName).drop(3))
    }

    @Test
    fun `a route is counted by kind for its owner and brings its yields to both ends`() {
        val a = game.addCiv("[+4] Trade Route slots")
        val b = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        for (tile in game.tileMap.values) { tile.setExplored(a, true); tile.setExplored(b, true) }
        val origin = game.addCity(a, game.getTile(HexCoord.Zero))
        val destination = game.addCity(b, game.getTile(HexCoord(0, 5)))
        val caravan = game.createBaseUnit("Civilian", landUnique)
        val route = a.tradeRoutes.establish(game.addUnit(caravan.name, a, origin.getCenterTile()), destination, TradeRouteKind.International)
        assertNotNull(route)
        route!!.originYields.gold = 7.9f
        route.originYields.science = 3f
        route.originYields.culture = 1f
        route.destinationYields.gold = 4f

        val rowA = rowOf("AI_TRADE", a.civName).drop(3)
        assertEquals(listOf("1", "0", "0", "7", "3", "1", "1"), rowA)
        // the other end: no route of its own, the destination's yield, one partner
        assertEquals(listOf("0", "0", "0", "4", "0", "0", "1"), rowOf("AI_TRADE", b.civName).drop(3))
    }

    @Test
    fun `the plundered connections the manager remembers are counted`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        civ.tradeRoutes.plunderedConnections["0,0-0,5"] = 12
        civ.tradeRoutes.plunderedConnections["0,0-3,0"] = 30
        assertEquals("2", rowOf("AI_TRADE", civ.civName)[5])
    }

    @Test
    fun `the monopoly row lists the monopolies of the civ's manager`() {
        val civ = game.addCiv()
        val built = game.createTileImprovement().apply { terrainsCanBeBuiltOn = listOf("Plains") }
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Plains")
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury; isMonopoly = true; improvement = built.name }
        val strategic = game.createResource().apply { resourceType = ResourceType.Strategic; isMonopoly = true; improvement = built.name }
        val bonus = game.createResource().apply { resourceType = ResourceType.Bonus; isMonopoly = true; improvement = built.name }
        for (resource in listOf(luxury, strategic, bonus)) resource.setTransients(game.ruleset)

        fun place(resource: String, x: Int, y: Int, amount: Int = 0, ours: Boolean) {
            val tile = game.getTile(HexCoord(x, y))
            tile.setTileResource(resource)
            if (amount > 0) tile.resourceAmount = amount
            if (ours) { game.addTileToCity(city, tile); tile.setOwningCity(city); tile.setImprovement(built.name) }
        }
        // a luxury: three copies of four are ours (75 percent, more than half): a global monopoly
        place(luxury.name, 1, 0, ours = true); place(luxury.name, 0, 1, ours = true); place(luxury.name, -1, 1, ours = true); place(luxury.name, 6, 0, ours = false)
        // a strategic resource: 5 of 16 are ours (31 percent: more than a quarter, not more than half): a strategic monopoly
        place(strategic.name, 1, -1, 5, ours = true); place(strategic.name, 6, -1, 6, ours = false); place(strategic.name, 6, -2, 5, ours = false)
        // a bonus resource is never a global monopoly (a luxury or strategic one only) and not a strategic one
        place(bonus.name, 0, -1, ours = true)

        civ.cache.updateCivResources()
        val row = rowOf("AI_MONOPOLY", civ.civName).drop(3)
        assertEquals(listOf("1", "1", "${luxury.name},${strategic.name}:strategic"), row)
        assertEquals(listOf(luxury.name to true, strategic.name to false), TradeColumns.monopolies(civ).map { it.first to it.second })
    }
}
