package com.unciv.logic.city

import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.ThemingBonus
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The theme yields of Vox Populi: Building_ThemingYieldBonus and Building_YieldChangesPerLocalTheme, which count the themed buildings of the city
 *  (CvCityBuildings::GetTotalNumThemedBuildings, V:CvBuildingClasses.cpp:6889-6904) */
@RunWith(GdxTestRunner::class)
class ThemedBuildingsTests {
    private lateinit var game: TestGame
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
    }

    /** A building with two art slots, themed by any two works when [themed] */
    private fun gallery(themed: Boolean, vararg uniques: String): Building {
        val building = game.createBuilding("Has [2] [Art/Artifact] Great Work slots", *uniques)
        if (themed) building.themingBonuses.add(ThemingBonus().apply { bonus = 5; name = "any" })
        city.cityConstructions.addBuilding(building)
        return building
    }

    private fun fill(building: Building, works: Int) {
        for (slot in 0 until works)
            city.greatWorks.place(GreatWorkInstance(game.createGreatWork().name, city.civ.civName, "Ancient era", 1), GreatWorkSlot(city, building, slot))
    }

    private fun themed(filter: String) =
        Countables.getCountableAmount("Themed [$filter] Buildings in this city", GameContext(city.civ, city = city))

    @Test
    fun `a building counts once its works make its theme, and only if it matches the filter`() {
        val first = gallery(themed = true)
        val second = gallery(themed = true)
        val unthemed = gallery(themed = false)
        fill(first, 1)
        fill(unthemed, 2)
        assertEquals("one work of two makes no theme, a building without a theme never counts", 0, themed("All"))
        fill(second, 2)
        assertEquals(1, themed("All"))
        assertEquals(1, themed(second.name))
        assertEquals(0, themed(first.name))
    }

    @Test
    fun `a yield for every themed building pays in the city`() {
        val salon = gallery(themed = true, "[+6 Science] [in this city] <for every [Themed [All] Buildings in this city]>")
        city.cityStats.update()
        val before = city.cityStats.currentCityStats.science
        fill(salon, 2)
        city.cityStats.update()
        assertEquals(before + 6f, city.cityStats.currentCityStats.science, 0.001f)
    }
}
