package com.unciv.logic.civilization

import com.unciv.models.ruleset.ai.WorldSizeDef
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The cost of the next policy follows CvPlayerPolicies::GetNextPolicyCost: whole-number steps, and the highest
 *  number of cities counts (not the current one), without puppets and cities being razed. Expected values are what
 *  that integer arithmetic gives for 25 + (3n)^2.01 plus Vox Populi's cost per city: 5% for each city and 0.2% for each pair of cities. */
@RunWith(BaseTestRunner::class)
class PolicyCostTests {
    private val game = TestGame()
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        civ = game.addCiv()
    }

    /** Changes a copy of the world row of the map's tiles in this game's ruleset, the cached ruleset's row stays as it is */
    private fun changeWorldRow(change: WorldSizeDef.() -> Unit) {
        val rows = game.ruleset.ai.worldSizes
        val row = game.ruleset.ai.worldSizeForTiles(game.tileMap.tileList.size)
        rows[rows.indexOf(row)] = WorldSizeDef().also {
            it.name = row.name
            it.numCitiesPolicyCostMod = row.numCitiesPolicyCostMod
            it.researchPercent = row.researchPercent
            it.change()
        }
    }

    private fun cost(adoptedPolicies: Int) = civ.policies.getPolicyCultureCost(adoptedPolicies)

    private fun foundCities(count: Int) = listOf(game.getTile(0, 0), game.getTile(3, 0), game.getTile(-3, 0), game.getTile(0, 3),
        game.getTile(0, -3), game.getTile(3, -3))
        .take(count).map { game.addCity(civ, it) }

    @Test
    fun `every step truncates to whole numbers`() {
        foundCities(1)
        // 25 + 24^2.01 = 619.6, +5% of 619 = 30.95: truncated 619 + 30 = 649 -> 645, where rounding to nearest gives 620 + 31 = 651 -> 650
        assertEquals(645, cost(8))
    }

    @Test
    fun `a civilization without cities pays the cost of one`() {
        assertEquals(25, cost(0))
        assertEquals(500, cost(7))
    }

    @Test
    fun `each city adds a percentage of the cost`() {
        foundCities(4)
        assertEquals(125, cost(3)) // 107 + 21.2%
    }

    @Test
    fun `losing a city does not make policies cheaper`() {
        val cities = foundCities(4)
        civ.policies.endTurn(0) // the turn change is where the highest count is recorded
        cities.last().destroyCity()

        assertEquals(3, civ.cities.size)
        assertEquals(125, cost(3))
    }

    @Test
    fun `a city being razed does not count`() {
        val cities = foundCities(4)
        cities.last().isBeingRazed = true

        assertEquals(120, cost(3)) // 3 cities: 15.6%
    }

    @Test
    fun `a puppet does not count`() {
        val cities = foundCities(4)
        cities.last().isPuppet = true

        assertEquals(120, cost(3))
    }

    @Test
    fun `the cost grows faster than the number of cities`() {
        foundCities(6)
        // 5% of 6 cities and 0.2% of their 15 pairs: 33% of 107 is 35, where 30% (a cost linear in the cities) gives 32 -> 135
        assertEquals(140, cost(3))
    }

    @Test
    fun `the percent per city is that of the world row of the map's tiles`() {
        foundCities(4)
        changeWorldRow { numCitiesPolicyCostMod = 10f }
        assertEquals(150, cost(3)) // 107 + 41.2%
    }
}
