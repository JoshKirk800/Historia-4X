package com.unciv.logic.city

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ProductionCacheTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5, Constants.grassland)
    }

    @Test
    fun `the production remembered per signature is the production of every construction on its own`() {
        val civ = game.addCiv()
        val capital = game.addCity(civ, game.tileMap[HexCoord(0, 0)], initialPopulation = 4)
        val city = game.addCity(civ, game.tileMap[HexCoord(4, 0)], initialPopulation = 6)
        capital.cityConstructions.addBuilding("Barracks")
        city.cityConstructions.addBuilding(game.createBuilding(
            "[+50]% Production when constructing [Military] units [in this city]",
            "[+40]% Production when constructing [Wonder] wonders [in this city]",
            "[+25]% Production when constructing [Culture] buildings [in this city]",
            "[+10]% Production towards any buildings that already exist in the Capital"
        ))
        city.cityStats.update()

        val names = (game.ruleset.units.keys + game.ruleset.buildings.keys).toList()
        val alone = names.associateWith { city.cityConstructions.productionForConstructionExact(it) }
        assertTrue("the test needs constructions that differ: ${alone.values.toSet()}", alone.values.toSet().size > 3)

        val cached = city.cityConstructions.withProductionCache {
            val first = names.associateWith { city.cityConstructions.productionForConstructionExact(it) }
            val again = names.associateWith { city.cityConstructions.productionForConstructionExact(it) }
            assertEquals("a second look gives the same", first, again)
            first
        }
        for (name in names) assertEquals(name, alone[name]!!, cached[name]!!, 0f)
    }

    @Test
    fun `nothing is remembered after the block, so a change of the city shows`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.tileMap[HexCoord(0, 0)], initialPopulation = 6)
        val unit = game.ruleset.units.keys.first { game.ruleset.units[it]!!.isMilitary }
        val before = city.cityConstructions.withProductionCache { city.cityConstructions.productionForConstructionExact(unit) }
        city.cityConstructions.addBuilding(game.createBuilding("[+100]% Production when constructing [Military] units [in this city]"))
        city.cityStats.update()
        val after = city.cityConstructions.productionForConstructionExact(unit)
        assertTrue("before $before, after $after", after > before)
    }
}
