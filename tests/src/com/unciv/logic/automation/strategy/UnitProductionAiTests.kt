package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvUnitProductionAI::CheckUnitBuildSanity refusals ([UnitProductionAi.isRefused]) */
@RunWith(GdxTestRunner::class)
class UnitProductionAiTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.addGold(100)
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]
    private fun unit(name: String) = game.ruleset.units[name]!!

    /** A city with two buildings (the palace counts), and [income] gold a turn more (the soft supply cap reads the income) */
    private fun developedCity(x: Int = 0, y: Int = 0, income: Float = 0f): City =
        game.addCity(civ, tile(x, y)).apply {
            cityConstructions.addBuilding("Monument")
            if (cityConstructions.getBuiltBuildings().count() < 2) cityConstructions.addBuilding("Shrine")
            if (income != 0f) {
                cityConstructions.addBuilding(game.createBuilding().apply { gold = income })
                cityStats.update()
            }
        }

    private fun refreshMilitary() {
        civ.ai.military.updateBaseData(civ)
        civ.ai.military.updateDefenseState(civ)
    }

    @Test
    fun `a city with fewer than two buildings trains no unit`() {
        val city = game.addCity(civ, tile(0, 0))
        assertTrue(UnitProductionAi.isRefused(city, unit("Warrior"), forOperation = false))
        assertTrue(UnitProductionAi.isRefused(city, unit("Worker"), forOperation = false))
        city.cityConstructions.addBuilding("Monument")
        assertFalse(UnitProductionAi.isRefused(city, unit("Warrior"), forOperation = false))
    }

    @Test
    fun `no combat unit while the upkeep would bankrupt us within 9 turns, civilians still`() {
        val city = developedCity(income = 1000f)
        repeat(4) { game.addUnit("Warrior", civ, tile(1, it - 2)) }
        refreshMilitary()
        // a treasury of 0 and an average gold rate of 0 (none recorded yet): bankrupt now
        civ.addGold(-civ.gold)
        assertTrue(UnitProductionAi.isRefused(city, unit("Warrior"), forOperation = false))
        assertFalse(UnitProductionAi.isRefused(city, unit("Worker"), forOperation = false))
        // with a treasury, an income of 0 less the average unit upkeep leaves 9 turns or more
        civ.addGold(100)
        assertFalse(UnitProductionAi.isRefused(city, unit("Warrior"), forOperation = false))
    }

    @Test
    fun `land combat units stop at the recommended army, counting what other cities train`() {
        val city = developedCity(income = 1000f)
        val other = developedCity(4, 0)
        refreshMilitary()
        val recommended = civ.ai.military.recommendedLandArmySize
        assertTrue("recommended $recommended", recommended in 2..30)
        val free = game.tileMap.values.filter { !it.isCityCenter() }.iterator()
        repeat(recommended - 1) { game.addUnit("Warrior", civ, free.next()) }
        refreshMilitary()
        assertFalse("recommended ${civ.ai.military.recommendedLandArmySize}", UnitProductionAi.isRefused(city, unit("Warrior"), forOperation = false))
        // the other city's warrior in training makes it the recommended army (this city trains a worker)
        city.cityConstructions.setCurrentConstruction("Worker")
        other.cityConstructions.setCurrentConstruction("Warrior")
        val state = "recommended ${civ.ai.military.recommendedLandArmySize}, land ${civ.ai.military.numLandUnits}"
        assertTrue(state, UnitProductionAi.isRefused(city, unit("Warrior"), forOperation = true))
        // the city's own production does not count against itself
        assertFalse(state, UnitProductionAi.isRefused(other, unit("Warrior"), forOperation = false))
    }

    @Test
    fun `combat ships only in cities on a water body of more than 50 tiles or shared with another civilization`() {
        // an inland sea of 19 tiles next to the city: large enough for a navy, too small for an ocean
        for (t in tile(3, 0).getTilesInDistance(2)) game.setTileTerrain(t.position, Constants.coast)
        for (t in game.tileMap.values) t.setTerrainTransients()
        val city = developedCity(income = 1000f)
        refreshMilitary()
        val navy = civ.ai.military.recommendedNavySize
        assertTrue("recommended navy $navy", navy >= 1)
        assertFalse(UnitProductionAi.isConnectedToOcean(city))
        assertTrue(UnitProductionAi.isRefused(city, unit("Trireme"), forOperation = false))
        // a city of another civilization on the same water
        game.addCity(game.addCiv(), tile(6, 0))
        assertTrue(UnitProductionAi.isConnectedToOcean(city))
        assertFalse(UnitProductionAi.isRefused(city, unit("Trireme"), forOperation = false))
    }
}
