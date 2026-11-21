package com.unciv.logic.city

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.ai.WorldSizeDef
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Unhappiness from the number of cities is scaled by the world row of the map's tiles, by at most 100% (Vox Populi's CvCity takes
 *  min(100, NumCitiesUnhappinessPercent)): a map of 19 tiles plays by the Duel row, whose percent these tests change. */
@RunWith(BaseTestRunner::class)
class CityCountUnhappinessTests {
    private val game = TestGame()

    private fun citiesUnhappiness(percent: Int, annexed: Boolean = false): Float {
        game.makeHexagonalMap(2)
        val rows = game.ruleset.ai.worldSizes
        val row = game.ruleset.ai.worldSizeForTiles(game.tileMap.tileList.size)
        rows[rows.indexOf(row)] = WorldSizeDef().also { it.name = row.name; it.numCitiesUnhappinessPercent = percent }
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), true)
        if (annexed) city.foundingCivObject = game.addCiv() // taken from another civilization, not a puppet
        city.cityStats.update()
        return city.cityStats.happinessList["Cities"]!!
    }

    @Test
    fun `the unhappiness per city shrinks on larger maps`() {
        val standard = citiesUnhappiness(100)
        assertEquals("Large", standard * 0.8f, citiesUnhappiness(80), 0.001f)
        assertEquals("Huge", standard * 0.6f, citiesUnhappiness(60), 0.001f)
    }

    @Test
    fun `a percent above 100 does not make the unhappiness per city grow`() {
        val standard = citiesUnhappiness(100)
        assertEquals("Duel", standard, citiesUnhappiness(150), 0.001f)
        assertEquals("Tiny", standard, citiesUnhappiness(125), 0.001f)
    }

    @Test
    fun `an annexed city is scaled the same way`() {
        val standard = citiesUnhappiness(100, annexed = true)
        assertTrue("annexed costs more: $standard", standard < citiesUnhappiness(100))
        assertEquals(standard * 0.6f, citiesUnhappiness(60, annexed = true), 0.001f)
    }
}
