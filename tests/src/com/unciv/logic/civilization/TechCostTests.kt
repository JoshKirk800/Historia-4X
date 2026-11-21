package com.unciv.logic.civilization

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.ai.WorldSizeDef
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The cost of a technology follows Vox Populi: the world row's research percent, and for the cities that are no puppets
 *  5% each plus 0.2% for each pair (CvPlayerTechs::GetResearchCityModifierTimes100). */
@RunWith(BaseTestRunner::class)
class TechCostTests {
    private val game = TestGame()
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        civ = game.addCiv()
    }

    private val sites = listOf(HexCoord.Zero, HexCoord.of(3, 0), HexCoord.of(-3, 0), HexCoord.of(0, 3), HexCoord.of(0, -3), HexCoord.of(3, -3))

    /** Founds cities until the civilization has [count] */
    private fun foundCities(count: Int) = sites.drop(civ.cities.size).take(count - civ.cities.size).map { game.addCity(civ, game.getTile(it)) }

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

    private fun cost() = civ.tech.costOfTech("Education")

    /** The cost of a civilization with one city, which is what 5% more is: a civilization without cities or units is defeated, and the cost infinite */
    private fun costOfOneCity(): Float {
        foundCities(1)
        return cost() / 1.05f
    }

    @Test
    fun `cities add a percentage each and a smaller one for each pair`() {
        val base = costOfOneCity()
        assertTrue(base > 20)
        foundCities(6)
        assertEquals(base * 1.33f, cost().toFloat(), 1.01f) // 6 * 5% + 15 * 0.2%; linear in the cities it would be 1.30
    }

    @Test
    fun `a puppet does not count`() {
        val base = costOfOneCity()
        foundCities(2).single().isPuppet = true
        assertEquals(base * 1.05f, cost().toFloat(), 1.01f)
    }

    @Test
    fun `the research percent is that of the world row of the map's tiles`() {
        foundCities(1)
        val before = cost()
        changeWorldRow { researchPercent = 130 }
        assertEquals(before * 1.3f, cost().toFloat(), 1.01f)
    }
}
