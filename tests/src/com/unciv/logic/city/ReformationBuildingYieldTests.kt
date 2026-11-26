package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The building yields of a Reformation belief as `tools/rulegen` writes them (Belief_BuildingClassYieldChanges, scoped to the cities that follow the religion), read
 * with `reformationBeliefsLikeVp`: CvCity::UpdateReligion (V:CvCity.cpp:15052-15075) adds them in a city whose majority is the religion, each belief validated with
 * the city by CvReligionBeliefs::GetBuildingClassYieldChange and IsBeliefValid (V:CvBeliefClasses.cpp:3464-3482, 2229-2253): the city's owner owns the holy city or
 * has the religion as its state religion. The belief is Crusader Spirit's (VP's Evangelism): +2 Gold and +2 Culture from Barracks, Armory and Military Academy.
 */
@RunWith(GdxTestRunner::class)
class ReformationBuildingYieldTests {
    private lateinit var game: TestGame
    private lateinit var founder: Civilization
    private lateinit var other: Civilization
    private lateinit var holyCity: City
    private lateinit var religion: Religion

    private val classes = listOf("Barracks", "Armory", "Military Academy")
    private val bonus = Stats(gold = 2f, culture = 2f)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = true
        founder = game.addCiv()
        other = game.addCiv()
        holyCity = game.addCity(founder, game.getTile(0, 0), initialPopulation = 5)
        religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder),
            game.createBelief(BeliefType.Reformation, *classes.map { "[+2 Gold, +2 Culture] from every [$it] <in [in cities following this religion] cities>" }.toTypedArray())))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        holyCity.religion.addPressure(religion.name, 1000)
    }

    /** What the belief adds to [building] in [city]: its stats there less its stats in the ruleset */
    private fun beliefYield(building: String, city: City): Stats {
        val stats = game.ruleset.buildings[building]!!.getStats(city)
        stats.add(game.ruleset.buildings[building]!!.cloneStats().times(-1f))
        return stats
    }

    /** [Stats] has no [Any.equals]; its own `equals(Stats)` compares every stat */
    private fun assertStats(expected: Stats, actual: Stats, what: String = "") = assertTrue("$what expected $expected but was $actual", expected.equals(actual))

    private fun followingCity(civ: Civilization, x: Int, y: Int, followed: Religion = religion): City {
        val city = game.addCity(civ, game.getTile(x, y), initialPopulation = 5)
        city.religion.addPressure(followed.name, 1000)
        return city
    }

    private fun rivalReligion(): Religion {
        val rival = game.addReligion(game.addCiv())
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        return rival
    }

    @Test
    fun `each of the three classes gives 2 gold and 2 culture in a city of the holy city's owner that follows the religion`() {
        for (building in classes) assertStats(bonus, beliefYield(building, holyCity), building)
    }

    @Test
    fun `a unique building that replaces one of the classes gets the yield, another building does not`() {
        assertEquals("Barracks", game.ruleset.buildings["Ikanda"]!!.replaces)
        assertStats(bonus, beliefYield("Ikanda", holyCity))
        assertStats(Stats(), beliefYield("Walls", holyCity))
    }

    @Test
    fun `a city of the holy city's owner whose majority is another religion gets nothing`() {
        val elsewhere = followingCity(founder, 4, 0, rivalReligion())
        assertStats(Stats(), beliefYield("Barracks", elsewhere))
    }

    @Test
    fun `a city of a civilization with the religion as its state religion gets the yield`() {
        val city = followingCity(other, 3, -3)
        assertSame(religion, other.religionManager.getStateReligion())
        assertStats(bonus, beliefYield("Armory", city))
    }

    @Test
    fun `a city that follows the religion gets nothing when its owner has another state religion`() {
        val rival = rivalReligion()
        val city = followingCity(other, 3, -3)
        followingCity(other, -3, 3, rival)
        followingCity(other, -3, 0, rival)
        assertSame(religion, city.religion.getMajorityReligion())
        assertStats(Stats(), beliefYield("Barracks", city))
    }

    @Test
    fun `without the switch a city of a civilization that did not found the religion gets nothing`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        assertStats(Stats(), beliefYield("Barracks", followingCity(other, 3, -3)))
    }
}
