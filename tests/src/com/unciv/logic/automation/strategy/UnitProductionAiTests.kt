package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvUnitProductionAI::CheckUnitBuildSanity ([UnitProductionAi.checkUnitBuildSanity]): refusals and the weights of the bonuses */
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

    private fun isRefused(city: City, unit: BaseUnit, forOperation: Boolean = false) =
        ProductionSanityAi.unitSanity(city, unit, 100, forOperation) <= 0

    @Test
    fun `a city with fewer than two buildings trains no unit`() {
        val city = game.addCity(civ, tile(0, 0))
        assertTrue(isRefused(city, unit("Warrior")))
        assertTrue(isRefused(city, unit("Worker")))
        city.cityConstructions.addBuilding("Monument")
        assertFalse(isRefused(city, unit("Warrior")))
    }

    @Test
    fun `no combat unit while the upkeep would bankrupt us within 9 turns, civilians still`() {
        val city = developedCity(income = 1000f)
        repeat(4) { game.addUnit("Warrior", civ, tile(1, it - 2)) }
        refreshMilitary()
        // a treasury of 0 and an average gold rate of 0 (none recorded yet): bankrupt now
        civ.addGold(-civ.gold)
        assertTrue(isRefused(city, unit("Warrior")))
        assertFalse(isRefused(city, unit("Worker")))
        // with a treasury, an income of 0 less the average unit upkeep leaves 9 turns or more
        civ.addGold(100)
        assertFalse(isRefused(city, unit("Warrior")))
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
        assertFalse("recommended ${civ.ai.military.recommendedLandArmySize}", isRefused(city, unit("Warrior")))
        // the other city's warrior in training makes it the recommended army (this city trains a worker)
        city.cityConstructions.setCurrentConstruction("Worker")
        other.cityConstructions.setCurrentConstruction("Warrior")
        val state = "recommended ${civ.ai.military.recommendedLandArmySize}, land ${civ.ai.military.numLandUnits}"
        assertTrue(state, isRefused(city, unit("Warrior"), forOperation = true))
        // the city's own production does not count against itself
        assertFalse(state, isRefused(other, unit("Warrior")))
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
        assertTrue(isRefused(city, unit("Trireme")))
        // a city of another civilization on the same water
        game.addCity(game.addCiv(), tile(6, 0))
        assertTrue(UnitProductionAi.isConnectedToOcean(city))
        assertFalse(isRefused(city, unit("Trireme")))
    }

    @Test
    fun `a settler weighs 23 per point of the expansion flavor and 10 more per time settlers were skipped over`() {
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 10)
        val city = developedCity(income = 1000f)
        repeat(3) { game.addUnit("Warrior", civ, tile(1, it - 1)) } // more than 2 land units per city: no tiny army
        refreshMilitary()
        val settler = unit("Settler")
        // sqrti(10 x 100) = 31, EXPANSION 10 x 23
        assertEquals(31 + 10 * 23, ProductionSanityAi.unitSanity(city, settler, 100, false))
        civ.ai.military.numberOfTimesSettlerBuildSkippedOver = 3
        assertEquals(31 + (10 + 3 * 10) * 23, ProductionSanityAi.unitSanity(city, settler, 100, false))
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_ENOUGH_EXPANSION"] = 0
        assertEquals(ProductionSanityAi.SR_STRATEGY, ProductionSanityAi.unitSanity(city, settler, 100, false))
    }

    @Test
    fun `an occupied city weighs a fifth, a puppet trains nothing`() {
        val city = developedCity(income = 1000f)
        val normal = ProductionSanityAi.unitSanity(city, unit("Warrior"), 100, false)
        assertTrue(normal > 5)
        city.foundingCivObject = game.addCiv()
        assertEquals(normal / 5, ProductionSanityAi.unitSanity(city, unit("Warrior"), 100, false))
        city.isPuppet = true
        assertEquals(ProductionSanityAi.SR_IMPOSSIBLE, ProductionSanityAi.unitSanity(city, unit("Warrior"), 100, false))
    }

    @Test
    fun `the AI builds spaceship parts only through its spaceship planner`() {
        val city = developedCity(income = 1000f)
        assertEquals(ProductionSanityAi.SR_STRATEGY, ProductionSanityAi.unitSanity(city, unit("SS Booster"), 100, false))
    }

    @Test
    fun `work boats only where a resource in our water needs one, weighed by the boats needed`() {
        for (t in game.tileMap.values) t.setExplored(civ, true)
        val city = developedCity(income = 1000f)
        civ.tech.addTechnology("Sailing")
        val water = tile(1, 0)
        game.setTileTerrain(water.position, Constants.coast)
        water.setTerrainTransients()
        assertEquals(city, water.getCity())
        assertEquals(ProductionSanityAi.SR_USELESS, ProductionSanityAi.unitSanity(city, unit("Work Boats"), 100, false))
        water.setTileResource("Fish")
        // 1 boat x (population 1 + era 0) x 1000 on top of sqrti(1000) = 31, less 300 for a civilian water unit without a navy (no ships, no effective coastal city)
        assertEquals(31 + 1000 - 300, ProductionSanityAi.unitSanity(city, unit("Work Boats"), 100, false))
    }

    @Test
    fun `settler skips start over when nothing is chosen`() {
        civ.ai.military.numberOfTimesSettlerBuildSkippedOver = 5
        val city = developedCity()
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        ConstructionAutomation(city.cityConstructions).chooseNextConstruction()
        assertEquals(0, civ.ai.military.numberOfTimesSettlerBuildSkippedOver)
    }
}
