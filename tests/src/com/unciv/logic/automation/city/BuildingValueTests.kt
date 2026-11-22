package com.unciv.logic.automation.city

import com.unciv.logic.automation.strategy.BuildingValueAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Edge cases of CityStrategyAIHelpers::GetBuildingBasicValue, GetBuildingPolicyValue and GetBuildingReligionValue (BNW rules) */
@RunWith(GdxTestRunner::class)
class BuildingValueTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0])
    }

    private fun belief(unique: String): Belief = game.ruleset.beliefs.values.first { unique in it.uniques }

    /** The religion state has a private setter; the values under test only read it */
    private fun setPantheon() {
        ReligionManager::class.java.getDeclaredField("religionState").apply { isAccessible = true }
            .set(civ.religionManager, ReligionState.Pantheon)
    }

    @Test
    fun `the Faith step multiplies the religion value so far by half the era modifier, then the sum by the modifier`() {
        val religion = game.addReligion(civ)
        religion.addBelief(belief("[+1 Culture] from every [Shrine]"))
        // Liturgical Drama of the original (on the Amphitheater) is not in the ruleset: the same effect on the Arena, made for the test
        religion.addBelief(game.createBelief(BeliefType.Follower, "[+1 Faith] from every [Arena] <in cities with at least [3] [Followers of this Religion]>"))
        setPantheon()
        // 15 eras left, doubled while a religion can still be founded after a pantheon
        val modifier = if (civ.religionManager.remainingFoundableReligions() > 0) 30 else 15
        // the Faith step multiplies by half the modifier whatever the earlier yields added (the original's loop order): the Shrine's
        // Culture too, the Arena's Faith; the value is then times the modifier
        assertEquals(1 * (modifier / 2) * modifier, BuildingValueAi.religionValue(city, game.ruleset.buildings["Shrine"]!!))
        assertEquals(1 * (modifier / 2) * modifier, BuildingValueAi.religionValue(city, game.ruleset.buildings["Arena"]!!))
        assertEquals(0, BuildingValueAi.religionValue(city, game.ruleset.buildings["Monument"]!!))
    }

    @Test
    fun `a building that other buildings need is worth 100 for each one whose technology is known`() {
        val prerequisite = game.createBuilding()
        game.createBuilding().apply { requiredBuilding = prerequisite.name; requiredTech = "Pottery" }
        game.createBuilding().apply { requiredBuilding = prerequisite.name; requiredTech = "Writing" }
        assertEquals(0, BuildingValueAi.basicValue(city, prerequisite))
        civ.tech.addTechnology("Pottery")
        assertEquals(100, BuildingValueAi.basicValue(city, prerequisite))
    }

    @Test
    fun `spies the building gives or levels are worth 100 each, the levels counting all the spies we have`() {
        val extraSpy = game.createBuilding("Gain an extra spy")
        assertEquals(100, BuildingValueAi.policyValue(city, extraSpy))
        civ.espionageManager.addSpy()
        civ.espionageManager.addSpy()
        // 1 new spy and the 2 we have, at +20% for the level
        val intelligence = game.createBuilding("Gain an extra spy", "Promotes all spies [1] time(s)")
        assertEquals(100 * 3 * 120 / 100, BuildingValueAi.policyValue(city, intelligence))
    }
}
