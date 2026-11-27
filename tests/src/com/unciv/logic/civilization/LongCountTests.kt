package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.MayaCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's Long Count yields: CvPlayerTraits::IsEndOfMayaLongCount and CvPlayerTraits::SetUnitBaktun */
@RunWith(GdxTestRunner::class)
class LongCountTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero))
        game.ruleset.modOptions.constants.longCountYieldsLikeVp = true
    }

    private fun chooseForTheLongCount(unitName: String) {
        val manager = civ.greatPeople
        manager.freeGreatPeople += 1
        manager.mayaLimitedFreeGP += 1
        manager.longCountGPPool.add(unitName)
        assertEquals(unitName, manager.chooseFreeGreatPerson(unitName)?.name)
    }

    private fun scienceOfBuildings() = capital.cityStats.baseStatTree.children["Buildings"]!!.totalStats.science

    @Test
    fun `a great person chosen for the Long Count yields for every time it was chosen`() {
        capital.cityConstructions.addBuilding(game.createBuilding("[+1 Science] [in this city] <for every [Long Count choices of [Great Scientist]]>"))
        val before = scienceOfBuildings()
        chooseForTheLongCount("Great Scientist")
        chooseForTheLongCount("Great Engineer")
        chooseForTheLongCount("Great Scientist")
        assertEquals(2, civ.greatPeople.longCountChoices["Great Scientist"])
        assertEquals("the city updates its yields", before + 2, scienceOfBuildings())
    }

    @Test
    fun `a free great person that is not for the Long Count is not counted`() {
        civ.greatPeople.freeGreatPeople = 1
        civ.greatPeople.chooseFreeGreatPerson("Great Scientist")
        assertTrue(civ.greatPeople.longCountChoices.isEmpty())
    }

    @Test
    fun `without longCountYieldsLikeVp the choice is not counted`() {
        game.ruleset.modOptions.constants.longCountYieldsLikeVp = false
        chooseForTheLongCount("Great Scientist")
        assertTrue(civ.greatPeople.longCountChoices.isEmpty())
    }

    @Test
    fun `a copy of the game keeps the choices`() {
        chooseForTheLongCount("Great Scientist")
        val copy = game.gameInfo.clone().getCivilization(civ.civName)
        assertEquals(1, copy.greatPeople.longCountChoices["Great Scientist"])
    }

    /** Calls the start of the turn of the Long Count for each turn until a new b'ak'tun begins (the Great Person pool, empty at first, is filled), the gold that call gave */
    private fun goldAtTheEndOfABaktun(): Int {
        for (turn in 1..2000) {
            game.gameInfo.turns = turn
            val goldBefore = civ.gold
            MayaCalendar.startTurnForMaya(civ)
            if (civ.greatPeople.longCountGPPool.isNotEmpty()) return civ.gold - goldBefore
        }
        throw AssertionError("no new b'ak'tun in 2000 turns")
    }

    @Test
    fun `the end of a baktun gives each city the yield of its buildings once`() {
        val second = game.addCity(civ, game.getTile(HexCoord(4, 0)))
        for (city in listOf(capital, second))
            city.cityConstructions.addBuilding(game.createBuilding("Gain [10] [Gold] <upon the end of a b'ak'tun> <in this city>"))
        assertEquals(20, goldAtTheEndOfABaktun())
    }

    @Test
    fun `without longCountYieldsLikeVp the end of a baktun gives nothing`() {
        game.ruleset.modOptions.constants.longCountYieldsLikeVp = false
        capital.cityConstructions.addBuilding(game.createBuilding("Gain [10] [Gold] <upon the end of a b'ak'tun> <in this city>"))
        assertEquals(0, goldAtTheEndOfABaktun())
    }
}
