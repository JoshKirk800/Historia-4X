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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The countables of Vox Populi's follower yields: Belief_YieldPerXFollowersLocal (CvReligionBeliefs::GetLocalFollowerYield, V:CvBeliefClasses.cpp:4878-4900),
 * Belief_YieldPerXFollowers and Belief_YieldFromKnownPantheons (CvCity::UpdateSpecialReligionYields, V:CvCity.cpp:22352-22364, 22427-22431;
 * CvGameReligions::GetNumFollowers, GetNumPantheonsCreated, V:CvReligionClasses.cpp:2215-2236, 2031-2049).
 */
@RunWith(GdxTestRunner::class)
class ReligiousFollowerYieldsTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City
    private lateinit var pantheon: Religion

    private val local = "Followers of the majority religion in this city"
    private val owner = "Followers of the majority religion of this city in its owner's cities"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(0, 0), initialPopulation = 5)
        second = game.addCity(civ, game.getTile(5, 0), initialPopulation = 3)
        pantheon = religion(civ, BeliefType.Pantheon)
    }

    private fun religion(founder: Civilization, type: BeliefType): Religion {
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(type)))
        return religion
    }

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
    }

    private fun count(countable: String, city: City) = Countables.getCountableAmount(countable, GameContext(city.civ, city = city))

    @Test
    fun `the local followers are those of the city's majority religion in the city`() {
        capital.follow(pantheon)
        assertEquals(5, count(local, capital))
        assertEquals(0, count(local, second)) // no majority

        capital.religion.addPressure(religion(game.addCiv(), BeliefType.Founder).name, 5000 * 2 / 3)
        assertEquals(3, count(local, capital)) // 3 of 5 citizens, the other 2 follow the other religion
    }

    @Test
    fun `the owner's followers are those of the city's majority religion in all cities of the owner, not abroad`() {
        capital.follow(pantheon)
        second.follow(pantheon)
        val foreign = game.addCity(game.addCiv(), game.getTile(-5, 0), initialPopulation = 4)
        foreign.follow(pantheon)
        assertEquals(8, count(owner, capital))
        assertEquals(4, count(owner, foreign)) // its owner's cities: only itself

        second.follow(religion(game.addCiv(), BeliefType.Founder))
        assertEquals(5, count(owner, capital))
    }

    @Test
    fun `the pantheon founders are the major civilizations with a pantheon or religion, each once, up to the cap`() {
        val countable = "Civilizations that founded a pantheon or religion up to [8]"
        game.addReligion(civ) // a second religion of the same founder
        religion(game.addCiv(), BeliefType.Founder)
        religion(game.addCiv(cityStateType = "Cultured"), BeliefType.Pantheon)
        assertEquals(2, count(countable, capital))
        assertEquals(1, count("Civilizations that founded a pantheon or religion up to [1]", capital))
    }

    @Test
    fun `follower beliefs yield per followers in the cities following the religion`() {
        capital.follow(pantheon)
        second.follow(pantheon)
        val food = capital.total(Stat.Food)
        val gold = capital.total(Stat.Gold)
        val secondFood = second.total(Stat.Food)
        pantheon.addBeliefs(listOf(game.createBelief(BeliefType.Pantheon,
            "[+1 Food] [in cities following this religion] <for every [2] [$local]>",
            "[+0.25 Gold] [in cities following this religion] <for every [$owner]>")))

        assertEquals(food + 2f, capital.total(Stat.Food), 1e-3f) // 5 followers / 2, rounded down
        assertEquals(gold + 2f, capital.total(Stat.Gold), 1e-3f) // 8 followers of the owner * 0.25
        assertEquals(secondFood + 1f, second.total(Stat.Food), 1e-3f)
    }

    /** The city's base yield: the final one takes off a share of the surplus food of an unhappy civilization */
    private fun City.total(stat: Stat): Float {
        cityStats.update()
        return cityStats.baseStatTree.totalStats[stat]
    }
}
