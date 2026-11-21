package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.city.City
import com.unciv.logic.city.NeedYields
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvBuildingProductionAI::CheckBuildingBuildSanity (BNW XML values): the gates and the bonus terms that decide a plain building */
@RunWith(GdxTestRunner::class)
class BuildingSanityTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        city = game.addCity(civ, game.tileMap[0, 0])
    }

    private fun addWarriors(count: Int) {
        val tiles = listOf(game.tileMap[1, 0], game.tileMap[0, 1], game.tileMap[1, 1], game.tileMap[2, 0], game.tileMap[2, 1])
        for (i in 0 until count) game.addUnit("Warrior", civ, tiles[i])
    }

    @Test
    fun `a plain building is worth its normalised weight plus 200 per era`() {
        val building = game.createBuilding().apply { cost = 30 }
        assertEquals(ProductionSanityAi.normalise(1000) + 200, ProductionSanityAi.buildingSanity(city, building, 1000))
    }

    @Test
    fun `a tiny army takes 75 percent off the bonus so far, not off the era`() {
        val building = game.createBuilding().apply { cost = 30; happiness = 2f }
        // no military unit (at most 3 per city): the 20 of two happiness points lose 75%
        assertEquals(ProductionSanityAi.normalise(1000) + 5 + 200, ProductionSanityAi.buildingSanity(city, building, 1000))
        addWarriors(4)
        assertEquals(ProductionSanityAi.normalise(1000) + 20 + 200, ProductionSanityAi.buildingSanity(city, building, 1000))
    }

    @Test
    fun `a city about to fall builds nothing`() {
        val building = game.createBuilding().apply { cost = 30; culture = 1f }
        city.damageTakenLastTurn = city.health + 1
        assertEquals(ProductionSanityAi.SR_STRATEGY, ProductionSanityAi.buildingSanity(city, building, 1000))
    }

    @Test
    fun `a wonder is refused in a small city that is not the capital`() {
        val wonder = game.createWonder().apply { cost = 100; culture = 1f }
        val other = game.addCity(civ, game.tileMap[3, 0])
        assertEquals(ProductionSanityAi.SR_STRATEGY, ProductionSanityAi.buildingSanity(other, wonder, 1000))
        assertTrue(ProductionSanityAi.buildingSanity(city, wonder, 1000) > 0)
    }

    @Test
    fun `military experience counts the experience the city already has`() {
        addWarriors(4)
        val unique = "New [Military] units start with [15] XP [in this city]"
        val plain = game.createBuilding().apply { cost = 30 }
        val barracks = game.createBuilding(unique).apply { cost = 30 }
        val academy = game.createBuilding(unique).apply { cost = 30 }
        // the first one finds no experience in the city to count; the second counts the first one's 15 in each of the three domains
        assertEquals(0, ProductionSanityAi.buildingSanity(city, barracks, 1000) - ProductionSanityAi.buildingSanity(city, plain, 1000))
        city.cityConstructions.addBuilding(barracks.name)
        assertEquals(45, ProductionSanityAi.buildingSanity(city, academy, 1000) - ProductionSanityAi.buildingSanity(city, plain, 1000))
    }

    @Test
    fun `going for the spaceship keeps Aluminum for the parts and two buildings per core city`() {
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        val aluminumBuilding = game.createBuilding().apply { cost = 30; culture = 1f; requiredResource = "Aluminum" }
        assertEquals(0, SpaceshipProductionAi.aluminumStillNeededForSpaceship(civ))
        assertEquals(emptyList<City>(), SpaceshipProductionAi.coreCities(civ))
        civ.ai.currentVictoryPursuit = VictoryPursuit.Science
        assertEquals("one for each of the six parts", 6, SpaceshipProductionAi.aluminumStillNeededForSpaceship(civ))
        assertEquals(listOf(city), SpaceshipProductionAi.coreCities(civ))
        assertEquals(2, SpaceshipProductionAi.aluminumStillNeededForCoreCities(civ))
        city.cityConstructions.addBuilding("Spaceship Factory")
        assertEquals("the factory's Aluminum is spent", 1, SpaceshipProductionAi.aluminumStillNeededForCoreCities(civ))
        assertEquals(ProductionSanityAi.SR_STRATEGY, ProductionSanityAi.buildingSanity(city, aluminumBuilding, 1000))
    }

    //region The needs model (cityNeedsLikeVp)

    /** The bonus a building has over a plain one in [city], without the situational terms (CvBuildingProductionAI::CheckBuildingBuildSanity, MOD_BALANCE_VP) */
    private fun worth(world: NeedsTestWorld, city: City, vararg uniques: String, happiness: Float = 0f): Int {
        val building = world.game.createBuilding(*uniques).apply { cost = 30; this.happiness = happiness }
        val plain = world.game.createBuilding().apply { cost = 30 }
        return BuildingProductionAi.checkBuildingBuildSanity(city, building, 1000, ignoreSituational = true) -
            BuildingProductionAi.checkBuildingBuildSanity(city, plain, 1000, ignoreSituational = true)
    }

    @Test
    fun `a building with a poverty reduction is worth its reduction times the city's poverty and nothing in a city without poverty`() {
        val world = NeedsTestWorld()
        val capital = world.addCity(5)
        world.medians.gold = 500
        val needs = world.needsOf(capital)
        val reduction = "Reduces [Poverty] by [2] [in this city]"

        needs.update(NeedYields())
        assertEquals("5 unhappy citizens of poverty: 2 of them go, 100 each", 200, worth(world, capital, reduction))
        needs.update(NeedYields(gold = 3000)) // the capital's median is 500 x 125 %: ceil(5 - 3000 / 625) = 1 left
        assertEquals("only one citizen is unhappy", 100, worth(world, capital, reduction))
        world.medians.gold = 0
        needs.update(NeedYields())
        assertEquals("no poverty, no worth", 0, worth(world, capital, reduction))
        assertEquals("a need that is not there is not reduced either", 0, worth(world, capital, "Reduces [Boredom] by [2] [in this city]"))
    }

    @Test
    fun `a global reduction is worth its points in every city that has the need`() {
        val world = NeedsTestWorld()
        val capital = world.addCity(5)
        val other = world.addCity(5)
        world.medians.basic = 500
        world.needsOf(capital).update(NeedYields())
        world.needsOf(other).update(NeedYields(foodBeforeConsumption = 100000)) // no distress
        assertEquals("only the capital has distress", 100, worth(world, capital, "Reduces [Distress] by [1] [in all cities]"))
        world.needsOf(other).update(NeedYields())
        assertEquals(200, worth(world, capital, "Reduces [Distress] by [1] [in all cities]"))
    }

    @Test
    fun `a building that lessens poverty may be built while gold is short`() {
        val world = NeedsTestWorld()
        val capital = world.addCity(5)
        world.medians.gold = 500
        world.needsOf(capital).update(NeedYields())
        val costly = world.game.createBuilding("Reduces [Poverty] by [1] [in this city]").apply { cost = 30; maintenance = 1000 }
        val plain = world.game.createBuilding().apply { cost = 30; maintenance = 1000 }
        assertEquals("bankrupts us", ProductionSanityAi.SR_MAINTENANCE, BuildingProductionAi.checkBuildingBuildSanity(capital, plain, 1000, ignoreSituational = true))
        assertTrue(BuildingProductionAi.checkBuildingBuildSanity(capital, costly, 1000, ignoreSituational = true) > 0)
    }

    @Test
    fun `a building that reduces the empire size modifier is worth the city's modifier times the reduction times 10`() {
        val world = NeedsTestWorld()
        world.addCity(5)
        val second = world.addCity(5)
        world.addCity(5)
        world.addCity(5)
        assertEquals(17, world.needsOf(second).reducedEmpireSizeModifier())
        assertEquals(17 * 5 * 10, worth(world, second, "Reduces the empire size need modifier by [5] [in this city]"))
        world.build(second, "Reduces the empire size need modifier by [100] [in this city]")
        assertEquals("nothing left to reduce", 0, worth(world, second, "Reduces the empire size need modifier by [5] [in this city]"))
    }

    @Test
    fun `building happiness is worth 10 per point, times 4 for each level of empire unhappiness`() {
        // Approval 100, 45 and 38 (unhappy, the latter is above the very unhappy threshold 35), 33 and 25 (very unhappy, the latter is above the
        // super unhappy threshold 20), 15 (super unhappy): happy x 100 / unhappy / 2
        for ((happy, unhappy, factor) in listOf(Triple(20, 10, 1), Triple(9, 10, 4), Triple(7, 9, 4), Triple(2, 3, 16), Triple(5, 10, 16), Triple(3, 10, 64))) {
            val world = NeedsTestWorld()
            NeedsAiScenarios.addEmpireCity(world, world.civ, happy, unhappy)
            val capital = world.civ.cities.single()
            assertEquals("worth of 2 happiness at approval ${world.civ.getApproval()}", 2 * 10 * factor, worth(world, capital, happiness = 2f))
        }
    }

    //endregion
}
