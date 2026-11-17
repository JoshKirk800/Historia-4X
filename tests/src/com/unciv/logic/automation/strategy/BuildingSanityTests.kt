package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
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
        val building = game.createBuilding().apply { cost = 30; culture = 1f }
        assertEquals(ProductionSanityAi.normalise(1000) + 200, ProductionSanityAi.buildingSanity(city, building, 1000))
    }

    @Test
    fun `a tiny army takes 75 percent off the bonus so far, not off the era`() {
        val building = game.createBuilding().apply { cost = 30; culture = 1f; happiness = 2f }
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
        val plain = game.createBuilding().apply { cost = 30; culture = 1f }
        val barracks = game.createBuilding(unique).apply { cost = 30 }
        val academy = game.createBuilding(unique).apply { cost = 30 }
        // the first one finds no experience in the city to count; the second counts the first one's 15 in each of the three domains
        assertEquals(0, ProductionSanityAi.buildingSanity(city, barracks, 1000) - ProductionSanityAi.buildingSanity(city, plain, 1000))
        city.cityConstructions.addBuilding(barracks.name)
        assertEquals(45, ProductionSanityAi.buildingSanity(city, academy, 1000) - ProductionSanityAi.buildingSanity(city, plain, 1000))
    }
}
