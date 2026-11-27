package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The forms the generator writes for Vox Populi's great-work yield tables (P5 S8a), CvCityBuildings::GetYieldFromGreatWorksTimes100: a global building's
 *  yield for each work of every city (V:CvPlayer.cpp:16181-16194), a belief's for each work of the cities that follow its religion
 *  (CvReligionBeliefs::GetGreatWorkYieldChange), and a Reformation belief's only in those cities (with `reformationBeliefsLikeVp`, as VP's ruleset has it).
 */
@RunWith(BaseTestRunner::class)
class GreatWorkYieldFormTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(6)
        civ = game.addCiv(isPlayer = true)
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 3)
        second = game.addCity(civ, game.getTile(HexCoord(5, 0)), initialPopulation = 4)
    }

    private fun City.build(vararg uniques: String) = game.createBuilding(*uniques).also { cityConstructions.addBuilding(it) }
    private fun City.total(stat: Stat): Float { cityStats.update(); return cityStats.finalStatList.values.sumOf { it[stat].toDouble() }.toFloat() }
    private fun City.placeArt(count: Int) {
        val gallery = build("Has [$count] [Art/Artifact] Great Work slots")
        repeat(count) { index ->
            greatWorks.place(GreatWorkInstance(game.createGreatWork(GreatWorkType.Art).name, civ.civName, "Ancient era", 1), GreatWorkSlot(this, gallery, index))
        }
    }

    @Test
    fun `a global building yields for each work of every city of its civilization`() {
        capital.placeArt(2)
        val gold = capital.total(Stat.Gold)
        val secondGold = second.total(Stat.Gold)

        second.build("[+2 Gold] from each Great Work")

        assertEquals("two works in the capital", gold + 4, capital.total(Stat.Gold), 1e-3f)
        assertEquals("no work in the building's city", secondGold, second.total(Stat.Gold), 1e-3f)
    }

    @Test
    fun `a follower belief yields for each work of the cities that follow its religion only`() {
        capital.placeArt(1)
        second.placeArt(1)
        val religion = game.addReligion(civ)
        capital.religion.addPressure(religion.name, 5000)
        val faith = capital.total(Stat.Faith)
        val secondFaith = second.total(Stat.Faith)

        religion.addBeliefs(listOf(game.createBelief(BeliefType.Follower, "[+1 Faith] from each [Art] Great Work [in all cities]")))

        assertEquals("the following city", faith + 1, capital.total(Stat.Faith), 1e-3f)
        assertEquals("a city of no religion", secondFaith, second.total(Stat.Faith), 1e-3f)
    }

    @Test
    fun `a reformation belief scoped to the following cities yields in those cities only`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = true
        capital.placeArt(1)
        second.placeArt(1)
        val religion = game.addReligion(civ)
        capital.religion.religionThisIsTheHolyCityOf = religion.name
        capital.religion.addPressure(religion.name, 5000)
        val culture = capital.total(Stat.Culture)
        val secondCulture = second.total(Stat.Culture)

        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder), game.createBelief(BeliefType.Reformation,
            "[+3 Culture] from each [Art] Great Work [in all cities] <in [in cities following this religion] cities>")))

        assertEquals("the following city", culture + 3, capital.total(Stat.Culture), 1e-3f)
        assertEquals("a city of no religion", secondCulture, second.total(Stat.Culture), 1e-3f)
    }
}
