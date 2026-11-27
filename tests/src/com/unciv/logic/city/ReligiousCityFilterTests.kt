package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
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
 * The city filters of Vox Populi's Piety finisher (Policy_ReligionYieldMod, CvCity::getBaseYieldRateTimes100, V:CvCity.cpp:23480-23487) and of
 * Trait_YieldFromOwnPantheon (CvCity::GetBaseYieldRateFromReligionTimes100, V:CvCity.cpp:25440-25455).
 */
@RunWith(GdxTestRunner::class)
class ReligiousCityFilterTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var pantheon: Religion

    private val state = "in cities following our state religion"
    private val own = "in cities following our own pantheon or religion"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(0, 0), initialPopulation = 4)
        pantheon = religion(civ, BeliefType.Pantheon)
    }

    private fun religion(founder: Civilization, type: BeliefType): Religion {
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(type)))
        return religion
    }

    /** [founder]'s major religion, with its holy city [holyCity] */
    private fun majorReligion(founder: Civilization, holyCity: City): Religion {
        val religion = religion(founder, BeliefType.Founder)
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        founder.religionManager.invalidateStateReligion()
        return religion
    }

    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    @Test
    fun `a city following the own pantheon is the owner's own but follows no state religion`() {
        capital.follow(pantheon)
        assertTrue(capital.matchesFilter(own))
        assertFalse(capital.matchesFilter(state))
    }

    @Test
    fun `a city following the owner's major religion follows both, and the pantheon stays the owner's own`() {
        val second = game.addCity(civ, game.getTile(5, 0), initialPopulation = 3)
        val major = majorReligion(civ, capital)
        capital.follow(major)
        second.follow(pantheon)
        assertTrue(capital.matchesFilter(state))
        assertTrue(capital.matchesFilter(own))
        assertTrue(second.matchesFilter(own))
        assertFalse(second.matchesFilter(state))
    }

    @Test
    fun `a city following another civilization's religion matches neither`() {
        majorReligion(civ, game.addCity(civ, game.getTile(5, 0))) // the state religion: founded, its holy city ours
        val other = game.addCiv()
        val major = majorReligion(other, game.addCity(other, game.getTile(-5, 0)))
        capital.follow(major)
        assertFalse(capital.matchesFilter(state))
        assertFalse(capital.matchesFilter(own))
    }

    @Test
    fun `another civilization's pantheon is the owner's own while the owner founded a pantheon, as Vox Populi has one pantheon`() {
        val foreignPantheon = religion(game.addCiv(), BeliefType.Pantheon)
        capital.follow(foreignPantheon)
        assertTrue(capital.matchesFilter(own))

        val withoutPantheon = game.addCiv()
        val city = game.addCity(withoutPantheon, game.getTile(-5, 0))
        majorReligion(withoutPantheon, city)
        city.religion.addPressure(foreignPantheon.name, 5000)
        city.religion.removeAllPressuresExceptFor(foreignPantheon.name)
        assertFalse(city.matchesFilter(own))
    }

    @Test
    fun `the founded religion is not the owner's own once its holy city is lost, but stays the state religion`() {
        val other = game.addCiv()
        val major = majorReligion(civ, game.addCity(other, game.getTile(-5, 0))) // founded by us, its holy city theirs
        capital.follow(major)
        assertTrue(capital.matchesFilter(state)) // the religion most of our cities follow
        assertFalse(capital.matchesFilter(own))
    }

    @Test
    fun `a city without a majority matches neither`() {
        assertFalse(capital.matchesFilter(own))
        assertFalse(capital.matchesFilter(state))
    }

    @Test
    fun `a city following the state religion it conquered follows the state religion and is the owner's own`() {
        val other = game.addCiv()
        val major = majorReligion(other, capital) // the holy city of another civilization's religion, now ours
        capital.follow(major)
        assertTrue(capital.matchesFilter(state))
        assertTrue(capital.matchesFilter(own))
    }

    @Test
    fun `the Piety finisher yields in the cities following the state religion`() {
        val major = majorReligion(civ, capital)
        capital.cityStats.update()
        val gold = capital.cityStats.baseStatTree.totalStats[Stat.Gold]
        val policy = game.createPolicy("[+3 Gold] [$state]")
        game.createPolicyBranch(policy = policy)
        civ.policies.freePolicies++
        civ.policies.adopt(policy)
        capital.cityStats.update()
        assertEquals(gold, capital.cityStats.baseStatTree.totalStats[Stat.Gold], 1e-3f)
        capital.follow(major)
        capital.cityStats.update()
        assertEquals(gold + 3f, capital.cityStats.baseStatTree.totalStats[Stat.Gold], 1e-3f)
    }
}
