package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.automation.unit.SettlerAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The strategies and routines around the settle plot search of Vox Populi (Release-5.4.8): EconomicAIHelpers::IsTestStrategy_EarlyExpansion,
 *  _EnoughExpansion, CityStrategyAIHelpers::IsTestCityStrategy_EnoughSettlers, _NewContinentFeeder, _PocketCity and CvEconomicAI::DisbandUselessSettlers.
 *  The map is plains: a capital on a site with two wheat (found value 15720, the reference 54% of it = 8488) and nothing worth settling anywhere else, unless
 *  a test adds a second site with wheat.
 */
@RunWith(GdxTestRunner::class)
class SettlingStrategyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Plains")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        addWheat(tile(0, 0))
        capital = game.addCity(civ, tile(0, 0))
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    /** Two Wheat (a bonus resource: +1 food) at the edge of the work range of [site]: resources enough for a site (found value 15720 on plains) */
    private fun addWheat(site: Tile, remove: Boolean = false) {
        for (tile in site.getTilesAtDistance(3).take(2)) tile.setTileResource(if (remove) null else "Wheat")
    }

    /** A site far from the cities these tests found (the capital's and those of [addBarrenCities]) */
    private val goodSite get() = tile(6, 6)

    private fun activate(strategy: String) { civ.ai.economicStrategies[strategy] = 0 }

    /** Another city of ours on barren land (no reference value, no site worth settling nearby) */
    private fun addBarrenCities(count: Int) {
        val spots = listOf(tile(-9, 0), tile(9, 0), tile(0, 9), tile(0, -9), tile(-9, -9))
        for (index in 0 until count) game.addCity(civ, spots[index])
    }

    private fun nextTurn() { game.gameInfo.turns++ } // the found values and the best plot of a turn are kept for the turn

    @Test
    fun `there is no good settle plot where nothing but the capital's site was worth much, and one where a site is`() {
        assertEquals(15720 * 54 / 100, SettleAreas.cityQualityReference(game.gameInfo))
        nextTurn()
        assertFalse(SettleAreas.haveGoodSettlePlot(civ, -1))
        addWheat(goodSite)
        nextTurn()
        assertTrue(SettleAreas.haveGoodSettlePlot(civ, -1))
        // the landmass: the whole map is one
        assertTrue(SettleAreas.haveGoodSettlePlot(civ, tile(0, 0).getContinent()))
        assertFalse(SettleAreas.haveGoodSettlePlot(civ, 12345))
    }

    @Test
    fun `early expansion is always on for the first 3 cities, then while the best plot is in our sphere or far from the neighbors`() {
        civ.ai.economicStrategies.clear()
        // 1 city founded
        assertTrue(EconomicMilitaryStrategyTests.earlyExpansion(civ))
        addBarrenCities(2)
        // 3 cities founded, no neighbor, a settle site close to our cities
        addWheat(goodSite)
        nextTurn()
        assertEquals(3, civ.citiesCreated)
        val best = SettleAreas.bestSettlePlot(civ, null, null)
        assertNotNull(best)
        assertTrue(EconomicMilitaryStrategyTests.earlyExpansion(civ))
        // never in the late game (turn 169 elapsed is the last)
        game.gameInfo.turns = 170
        assertFalse(EconomicMilitaryStrategyTests.earlyExpansion(civ))
    }

    @Test
    fun `early expansion is off in an unhappy empire and without a settle plot`() {
        addBarrenCities(2)
        nextTurn()
        // 3 cities founded, but nothing worth settling: the best plot is none
        assertEquals(null, SettleAreas.bestSettlePlot(civ, null, null))
        assertFalse(EconomicMilitaryStrategyTests.earlyExpansion(civ))
    }

    @Test
    fun `enough expansion is never with one city, and with more when no plot is good, under losing wars or early or expanding`() {
        // A single city never has enough
        assertFalse(EconomicMilitaryStrategyTests.enoughExpansion(civ))
        addBarrenCities(1)
        nextTurn()
        // No good plot: enough
        assertTrue(EconomicMilitaryStrategyTests.enoughExpansion(civ))
        // ... unless the strategies that want more are on
        activate(EconomicMilitaryStrategyTests.EARLY_EXPANSION)
        assertFalse(EconomicMilitaryStrategyTests.enoughExpansion(civ))
        civ.ai.economicStrategies.clear()
        activate("ECONOMICAISTRATEGY_EXPAND_TO_OTHER_CONTINENTS")
        assertFalse(EconomicMilitaryStrategyTests.enoughExpansion(civ))
        // Losing wars: always enough, even with expansion wanted
        civ.ai.militaryStrategies[EconomicMilitaryStrategyTests.LOSING_WARS] = 0
        assertTrue(EconomicMilitaryStrategyTests.enoughExpansion(civ))
        civ.ai.militaryStrategies.clear()
        civ.ai.economicStrategies.clear()
        // A good plot: not enough
        addWheat(goodSite)
        nextTurn()
        assertFalse(EconomicMilitaryStrategyTests.enoughExpansion(civ))
    }

    private fun addSettler(at: Tile) = game.addUnit("Settler", civ, at).also { UnitAiTypes.set(it, UnitAiType.SETTLE) }

    @Test
    fun `enough settlers are more than one, or one when no found-city operation exists`() {
        fun enough(city: City = capital): Boolean {
            nextTurn()
            return CityStrategyAi.isEnoughSettlers(city)
        }
        assertFalse(enough())
        val first = addSettler(tile(1, 0))
        // An idle settler (no found-city operation) is enough
        assertTrue(enough())
        AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, first)
        assertFalse("a settler with an operation to go to is not yet enough", enough())
        addSettler(tile(1, 1))
        assertTrue("two settlers are enough", enough())
        // A city-state never wants settlers
        val cityState = game.addCiv(cityStateType = "Cultured")
        val stateCity = game.addCity(cityState, tile(10, 10))
        assertTrue(CityStrategyAi.isEnoughSettlers(stateCity))
    }

    @Test
    fun `a settler a city is training is not counted for that city`() {
        val second = game.addCity(civ, tile(8, 0))
        val settler = addSettler(tile(1, 0))
        AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler)
        second.cityConstructions.setCurrentConstruction("Settler")
        nextTurn()
        // 2 settlers (one a unit, one being trained): the capital sees 2, enough; the city training one sees 1, and an operation exists
        assertTrue(CityStrategyAi.isEnoughSettlers(capital))
        assertFalse(CityStrategyAi.isEnoughSettlers(second))
    }

    @Test
    fun `a city of 7 citizens on another landmass than the capital's feeds new continents when it has a good plot`() {
        // an island of radius 3 at (-10, 10)...
        for (tile in game.tileMap.values) if (tile.aerialDistanceTo(tile(8, 8)) in 1..2) game.setTileTerrain(tile.position, "Ocean")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val island = game.addCity(civ, tile(8, 8))
        val def = game.ruleset.ai.cityStrategies.first { it.name == CityStrategyAi.NEW_CONTINENT_FEEDER }
        island.population.setPopulation(7)
        nextTurn()
        // The island has no good plot yet
        assertFalse(CityStrategyAi.isNewContinentFeeder(island, def))
        for (tile in tile(10, 9).getTilesAtDistance(3).take(2)) { tile.setTileResource("Wheat") }
        nextTurn()
        val feeder = CityStrategyAi.isNewContinentFeeder(island, def)
        assertEquals(SettleAreas.haveGoodSettlePlot(civ, tile(8, 8).getContinent()), feeder)
        // 6 citizens: no
        island.population.setPopulation(6)
        assertFalse(CityStrategyAi.isNewContinentFeeder(island, def))
        // The capital's landmass shares: no
        capital.population.setPopulation(7)
        assertFalse(CityStrategyAi.isNewContinentFeeder(capital, def))
    }

    @Test
    fun `a city with no route to the capital on its landmass is a pocket city`() {
        val city = game.addCity(civ, tile(8, 0))
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        assertFalse("the capital itself is never one", CityStrategyAi.isPocketCity(capital))
        // Open land between the cities: a route could be built
        assertFalse(CityStrategyAi.isPocketCity(city))
        // A wall of land another civilization owns (and we are not at war with it) between them
        val other = game.addCiv()
        val wallCity = game.addCity(other, tile(7, 7))
        for (tile in game.tileMap.values) if (tile.position.x == 4) tile.setOwningCity(wallCity)
        assertTrue(CityStrategyAi.isPocketCity(city))
    }

    @Test
    fun `a useless settler is scrapped when there is enough expansion, more than 2 settlers and no good plot`() {
        addBarrenCities(4) // 5 cities founded: the early game rule (4 cities or fewer by turn 150) does not apply
        activate("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")
        for (position in listOf(tile(1, 0), tile(1, 1), tile(2, 1))) addSettler(position)
        nextTurn()
        fun settlers() = civ.units.getCivUnits().count { it.name == "Settler" }
        // 3 settlers, nothing worth settling: one goes
        civ.ai.economic.disbandUselessSettlers(civ)
        assertEquals(2, settlers())
        // Two settlers are ok
        civ.ai.economic.disbandUselessSettlers(civ)
        assertEquals(2, settlers())
        // A good plot, three settlers: they stay
        addSettler(tile(2, 2))
        addWheat(goodSite)
        nextTurn()
        civ.ai.economic.disbandUselessSettlers(civ)
        assertEquals(3, settlers())
        // Not without ENOUGH_EXPANSION
        addWheat(goodSite, remove = true)
        civ.ai.economicStrategies.clear()
        nextTurn()
        civ.ai.economic.disbandUselessSettlers(civ)
        assertEquals(3, settlers())
    }

    @Test
    fun `the settler scrapped is one no operation moves, and none is before 5 cities or turn 151`() {
        activate("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")
        val settlers = listOf(tile(1, 0), tile(1, 1), tile(2, 1)).map { addSettler(it) }
        nextTurn()
        // 1 city founded and turn 1: the early game
        civ.ai.economic.disbandUselessSettlers(civ)
        assertEquals(3, civ.units.getCivUnits().count())
        // One settler goes to a site (there is a good plot while it does), the others are loose
        addWheat(goodSite)
        nextTurn()
        AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settlers[0])
        assertFalse(SettlerAutomation.isLooseSettler(settlers[0]))
        assertTrue(SettlerAutomation.isLooseSettler(settlers[1]))
        // Then the site is taken: no good plot. At turn 151 the first loose settler is scrapped, the one with its operation stays
        addWheat(goodSite, remove = true)
        game.gameInfo.turns = 151
        civ.ai.economic.disbandUselessSettlers(civ)
        assertEquals(setOf(settlers[0], settlers[2]), civ.units.getCivUnits().toSet())
    }
}
