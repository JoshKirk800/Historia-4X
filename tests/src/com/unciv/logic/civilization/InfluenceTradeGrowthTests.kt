package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.models.TradeRouteKind
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's growth from influence over trade partners (CvCity::UpdateGrowthFromTourism) with the ModConstants `influenceTradeGrowthLikeVp` */
@RunWith(GdxTestRunner::class)
class InfluenceTradeGrowthTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var origin: City
    private lateinit var partner: City

    private val landUnique = "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(12)
        game.ruleset.modOptions.constants.influenceTradeGrowthLikeVp = true
        civA = game.addCiv()
        civB = game.addCiv()
        meet(civA, civB)
        for (tile in game.tileMap.values) tile.setExplored(civA, true)
        origin = game.addCity(civA, game.tileMap[0, 0], replacePalace = true)
        partner = game.addCity(civB, game.tileMap[0, 5], replacePalace = true)
        civB.totalCultureForContests = 1000
    }

    private fun meet(a: Civilization, b: Civilization) {
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
    }

    private fun establish(from: City, to: City, kind: TradeRouteKind = TradeRouteKind.International) {
        val caravan = game.createBaseUnit("Civilian", landUnique)
        assertNotNull(from.civ.tradeRoutes.establish(game.addUnit(caravan.name, from.civ, from.getCenterTile()), to, kind))
    }

    /** Our influence on B at [percent] of its lifetime culture (Exotic from 10, Familiar 30, Popular 60, Influential 100, Dominant 200) */
    private fun influenceAt(percent: Int) {
        civA.culture.influence.clear()
        civA.culture.addInfluence(civB, percent * 10)
    }

    private fun growth(city: City = origin) = civA.tradeRoutes.getInfluenceGrowthPercent(city)

    @Test
    fun `each route to a major gives five percent growth for each influence level over its owner`() {
        establish(origin, partner)
        for ((percent, expected) in listOf(0 to 0, 10 to 5, 30 to 10, 60 to 15, 100 to 20, 200 to 25)) {
            influenceAt(percent)
            assertEquals("at $percent%", expected, growth())
        }
        // A second route from the same city counts again, one from another city only there
        val second = game.addCity(civB, game.tileMap[5, 0])
        establish(origin, second)
        val other = game.addCity(civA, game.tileMap[-5, 0])
        assertEquals(50, growth())
        assertEquals(0, growth(other))
    }

    @Test
    fun `routes to City-States, routes at home and routes of others give none`() {
        influenceAt(200)
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        meet(civA, cityState)
        // even a City-State our influence would dominate (tourism never reaches one, so the influence is set as such)
        cityState.totalCultureForContests = 1000
        civA.culture.influence.add(cityState.civID, 2000)
        establish(origin, game.addCity(cityState, game.tileMap[0, -5]))
        assertEquals(InfluenceLevel.Dominant, civA.culture.getInfluenceLevel(cityState))
        origin.cityConstructions.addBuilding(game.createBuilding("Allows [Food] trade routes between your cities"))
        establish(origin, game.addCity(civA, game.tileMap[-5, 0]), TradeRouteKind.Food)
        // B's route to us: the growth is the origin's, from its owner's influence
        for (tile in game.tileMap.values) tile.setExplored(civB, true)
        establish(partner, origin)
        assertEquals(0, growth())
    }

    @Test
    fun `the growth is a bonus to the food of the city, only with the switch`() {
        establish(origin, partner)
        influenceAt(30)
        origin.cityStats.update()
        val stats = origin.cityStats.finalStatList
        val entry = stats["[Tourism] ([Growth])"]
        assertNotNull(entry)
        val baseFood = stats.filterKeys { it != "[Tourism] ([Growth])" }.values.sumOf { it.food.toDouble() }.toFloat()
        assertEquals(baseFood * 0.10f, entry!!.food, 0.001f)

        game.ruleset.modOptions.constants.influenceTradeGrowthLikeVp = false
        origin.cityStats.update()
        assertNull(origin.cityStats.finalStatList["[Tourism] ([Growth])"])
    }
}
