package com.unciv.logic.map.tile

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The tile flags of Vox Populi's pantheons as the generator writes them: RequiresResource asks of an improvement row a visible resource the improvement makes
 *  valid (`improved resource`, CvPlot::calculateReligionImprovementYield, V:CvPlot.cpp:10549-10562) and of a terrain row a visible resource
 *  (CvPlot::calculateReligionNatureYield, V:CvPlot.cpp:10294-10351); RequiresNoImprovementFeature counts the worked tiles of a terrain without a feature, hills
 *  included (CvCityCitizens::SetWorkingPlot, V:CvCityCitizens.cpp:2268-2279).
 */
@RunWith(GdxTestRunner::class)
class ReligionTileFlagTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(3, "Plains")
        civ = game.addCiv()
        city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 6)
    }

    private fun tile(x: Int, y: Int, terrain: String, resource: String? = null, improvement: String? = null, vararg features: String): Tile {
        val tile = game.setTileTerrainAndFeatures(HexCoord(x, y), terrain, *features)
        tile.setTileResource(resource)
        if (improvement != null) tile.setImprovement(improvement)
        return tile
    }

    @Test
    fun `an improved resource is a visible resource that the unpillaged improvement makes valid`() {
        val farmOnWheat = tile(1, 0, "Plains", "Wheat", "Farm")
        assertTrue(farmOnWheat.matchesFilter("improved resource", civ))
        assertFalse("no civilization to see it", farmOnWheat.matchesFilter("improved resource", null))
        assertFalse("no resource", tile(0, 1, "Plains", null, "Farm").matchesFilter("improved resource", civ))
        assertFalse("an improvement that does not make it valid", tile(-1, 0, "Plains", "Wheat", "Mine").matchesFilter("improved resource", civ))
        assertFalse("no improvement", tile(1, 1, "Plains", "Wheat").matchesFilter("improved resource", civ))

        farmOnWheat.improvementIsPillaged = true
        assertFalse("pillaged", farmOnWheat.matchesFilter("improved resource", civ))

        val mineOnIron = tile(0, -1, "Plains", "Iron", "Mine")
        assertFalse("iron is not revealed", mineOnIron.matchesFilter("improved resource", civ))
        civ.tech.addTechnology(mineOnIron.tileResource!!.revealedBy!!)
        assertTrue(mineOnIron.matchesFilter("improved resource", civ))
    }

    @Test
    fun `a resource-gated pantheon gives its improvement and terrain rows only on resources, and its feature rows everywhere`() {
        val religion = game.addReligion(civ)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Pantheon,
            "[+1 Faith] from every [Farm] <in [improved resource] tiles>",
            "[+2 Faith] from every [Desert] <in [resource] tiles>",
            "[+3 Faith] from every [Oasis]")))
        city.religion.addPressure(religion.name, 5000)
        fun faith(tile: Tile) = tile.stats.getTileStats(city, civ).faith

        assertEquals(1f, faith(tile(1, 0, "Plains", "Wheat", "Farm")))
        assertEquals(0f, faith(tile(0, 1, "Plains", null, "Farm")))
        assertEquals(2f, faith(tile(-1, 0, "Desert", "Wheat")))
        assertEquals(0f, faith(tile(0, -1, "Desert")))
        assertEquals("the Oasis row is not gated", 3f, faith(tile(1, 1, "Desert", null, null, "Oasis")))
    }

    @Test
    fun `the featureless count takes the worked tiles of the terrain without a feature or hills, improved or not`() {
        city.clearWorkedTiles()
        for (worked in listOf(
            tile(1, 0, "Grassland", null, "Farm"),
            tile(0, 1, "Grassland"),
            tile(-1, 0, "Grassland", null, null, "Forest"),
            tile(0, -1, "Grassland", null, null, "Hill"),
            tile(1, 1, "Plains"),
        )) city.workTile(worked)

        assertEquals(2, Countables.getCountableAmount("Worked [{Grassland} {Featureless}] Tiles in this city", GameContext(civ, city = city)))
    }
}
