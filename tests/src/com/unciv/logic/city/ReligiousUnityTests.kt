package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's Religious Unity (Belief_YieldPerOtherReligionFollower): in the holy city, a yield for the followers that the other major religions have in the
 * owner's cities (CvCity::UpdateSpecialReligionYields, V:CvCity.cpp:22392-22402; CvCityReligions::GetFollowersOtherReligions, V:CvReligionClasses.cpp:4572-4593).
 */
@RunWith(GdxTestRunner::class)
class ReligiousUnityTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City
    private lateinit var ours: Religion
    private lateinit var foreign: Religion

    private val others = "Followers of other religions than the majority religion of this city in its owner's cities"
    private val holy = "in the holy city of our religion"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(9)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(0, 0), initialPopulation = 5)
        second = game.addCity(civ, game.getTile(5, 0), initialPopulation = 3)
        ours = majorReligion(civ, capital)
        val other = game.addCiv()
        val foreignHolyCity = game.addCity(other, game.getTile(-5, 0), initialPopulation = 4)
        foreign = majorReligion(other, foreignHolyCity)
        foreignHolyCity.follow(foreign)
    }

    /** [founder]'s religion with a belief of [type], with its holy city [holyCity] if any */
    private fun religion(founder: Civilization, type: BeliefType, vararg uniques: String, holyCity: City? = null): Religion {
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(type, *uniques)))
        if (holyCity != null) holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        founder.religionManager.invalidateStateReligion()
        return religion
    }

    private fun majorReligion(founder: Civilization, holyCity: City) = religion(founder, BeliefType.Founder, holyCity = holyCity)

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    private fun count(city: City) = Countables.getCountableAmount(others, GameContext(city.civ, city = city))

    @Test
    fun `the other religions' followers are those of the other major religions in the owner's cities, pantheons and foreign cities left out`() {
        capital.follow(ours)
        capital.religion.addPressure(foreign.name, 5000 * 2 / 3) // 2 of the 5 follow the foreign religion
        second.follow(foreign)
        game.addCity(civ, game.getTile(0, 5), initialPopulation = 4).follow(religion(civ, BeliefType.Pantheon))
        assertEquals(5, count(capital)) // 2 here, 3 in the second city; the foreign holy city's 4 and the pantheon's 4 do not count
        assertEquals(3, count(second)) // the majority there is the foreign religion: our 3 in the capital
    }

    @Test
    fun `a city without a majority counts none`() {
        second.follow(foreign)
        assertEquals(0, count(capital))
    }

    @Test
    fun `the holy city filter is the holy city of our religion while it follows it`() {
        capital.follow(ours)
        second.follow(ours)
        assertTrue(capital.matchesFilter(holy))
        assertFalse(second.matchesFilter(holy)) // not the holy city

        capital.follow(foreign)
        assertFalse(capital.matchesFilter(holy)) // the holy city follows another religion
    }

    @Test
    fun `the holy city of a religion we did not found is not ours`() {
        val conqueror = game.addCiv()
        val city = game.addCity(conqueror, game.getTile(0, -5))
        city.religion.religionThisIsTheHolyCityOf = ours.name
        capital.religion.religionThisIsTheHolyCityOf = null
        city.follow(ours)
        assertFalse(city.matchesFilter(holy)) // the conqueror has no founder benefits of it without religiousBeliefValidityLikeVp
        assertFalse(capital.matchesFilter(holy))
    }

    @Test
    fun `Religious Unity yields in the holy city for the other religions' followers`() {
        capital.follow(ours)
        second.follow(foreign)
        val production = capital.total(Stat.Production)
        val secondProduction = second.total(Stat.Production)
        ours.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, "[+0.2 Production] [$holy] <for every [$others]>")))
        assertEquals(production + 0.6f, capital.total(Stat.Production), 1e-3f) // 3 followers of the foreign religion in the second city
        assertEquals(secondProduction, second.total(Stat.Production), 1e-3f)
    }

    /** The city's base yield: the final one takes off a share of the surplus food of an unhappy civilization */
    private fun City.total(stat: Stat): Float {
        cityStats.update()
        return cityStats.baseStatTree.totalStats[stat]
    }
}
