package com.unciv.logic.city

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Religion
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `religiousSpreadLikeVp`: followers and majority as CvCityReligions::RecomputeFollowers and ComputeReligiousMajority make them (V:CvReligionClasses.cpp:5430-5520,
 * 4443-4489), recomputed after each source of pressure (SpreadReligionToOneCity, 358-445), and the pressure of a religion displacing pantheons (AddReligiousPressure, 4896-4930).
 */
@RunWith(GdxTestRunner::class)
class ReligiousFollowersTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.religiousSpreadLikeVp = true
    }

    private fun majorReligion(founder: Civilization): Religion {
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        return religion
    }

    private fun setSwitch(on: Boolean, city: City) {
        game.ruleset.modOptions.constants.religiousSpreadLikeVp = on
        city.religion.setTransients(city) // the followers again, under the other rule
    }

    @Test
    fun `atheists with the most followers leave the city without a majority`() {
        val civ = game.addCiv()
        val religion = majorReligion(civ)
        val city = game.addCity(civ, game.getTile(0, 0), initialPopulation = 3)
        // A third of the pressure: per follower is half the atheism, so the religion has 1 follower and atheism 2
        city.religion.addPressure(religion.name, city.religion.getPressures()[Constants.noReligionName] / 2)
        assertEquals(1, city.religion.getFollowersOf(religion.name))
        assertNull(city.religion.getMajorityReligionName())

        setSwitch(false, city)
        assertEquals(religion.name, city.religion.getMajorityReligionName()) // 1 of a population of 3, half rounded down
    }

    @Test
    fun `atheists tied in followers with more pressure leave the city without a majority`() {
        val civ = game.addCiv()
        val religion = majorReligion(civ)
        val city = game.addCity(civ, game.getTile(0, 0), initialPopulation = 2)
        // 2 less than the atheism: per follower is the atheism less 1, so atheism 1 and the religion 1 by its remainder
        city.religion.addPressure(religion.name, city.religion.getPressures()[Constants.noReligionName] - 2)
        assertEquals(1, city.religion.getFollowersOf(religion.name))
        assertNull(city.religion.getMajorityReligionName())

        setSwitch(false, city)
        assertEquals(religion.name, city.religion.getMajorityReligionName())
    }

    @Test
    fun `an equal remainder goes to a religion before atheism`() {
        val civ = game.addCiv()
        val religion = majorReligion(civ)
        val city = game.addCity(civ, game.getTile(0, 0))
        // Equal pressures for 1 citizen: both have 0 followers and the same remainder; VP sorts atheism last
        city.religion.addPressure(religion.name, city.religion.getPressures()[Constants.noReligionName])
        assertEquals(religion.name, city.religion.getMajorityReligionName())
    }

    @Test
    fun `more pressure breaks a tie in followers`() {
        val civ = game.addCiv()
        val weaker = majorReligion(civ)
        val stronger = majorReligion(game.addCiv())
        val city = game.addCity(civ, game.getTile(0, 0), initialPopulation = 2)
        city.religion.addPressure(weaker.name, 100_000)
        city.religion.addPressure(stronger.name, 100_001)
        city.religion.addPressure(Constants.noReligionName, -city.religion.getPressures()[Constants.noReligionName]) // no atheists
        // Per follower 100 000: 1 each
        assertEquals(1, city.religion.getFollowersOf(weaker.name))
        assertEquals(1, city.religion.getFollowersOf(stronger.name))
        assertEquals(stronger.name, city.religion.getMajorityReligionName())

        setSwitch(false, city)
        assertEquals(weaker.name, city.religion.getMajorityReligionName()) // the engine takes the first it counted
    }

    @Test
    fun `the pressure of a religion takes half as much from a pantheon down to nothing`() {
        val civ = game.addCiv()
        val pantheon = game.addReligion(civ)
        pantheon.addBeliefs(listOf(game.createBelief(BeliefType.Pantheon)))
        val religion = majorReligion(game.addCiv())
        val city = game.addCity(civ, game.getTile(0, 0))
        city.religion.addPressure(pantheon.name, 1000)

        city.religion.addPressure(religion.name, 400)
        assertEquals(800, city.religion.getPressures()[pantheon.name])
        city.religion.addPressure(religion.name, 4000)
        assertEquals(0, city.religion.getPressures()[pantheon.name])

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        city.religion.addPressure(pantheon.name, 1000)
        city.religion.addPressure(religion.name, 400)
        assertEquals(1000, city.religion.getPressures()[pantheon.name])
    }

    @Test
    fun `a pantheon's own pressure displaces no pantheon`() {
        val civ = game.addCiv()
        val pantheon = game.addReligion(civ)
        pantheon.addBeliefs(listOf(game.createBelief(BeliefType.Pantheon)))
        val city = game.addCity(civ, game.getTile(0, 0))
        city.religion.addPressure(pantheon.name, 1000)
        city.religion.addPressure(pantheon.name, 400)
        assertEquals(1400, city.religion.getPressures()[pantheon.name])
    }

    /** The first founder's city 4 tiles from a target of 1 citizen, then the second founder's city with a trade route to it and +100% spread; returns the gold the
     *  first founder got from the target adopting its religion, after asserting that the second religion ends the turn as the majority */
    private fun goldOfFirstReligionHeldBetweenSources(): Int {
        game.gameInfo.speed.religiousPressureAdjacentCity = 1000
        val adoptionUnique = "[+50 Gold] when a city adopts this religion for the first time"
        val firstFounder = game.addCiv(adoptionUnique)
        val secondFounder = game.addCiv(adoptionUnique)
        val first = majorReligion(firstFounder)
        val second = majorReligion(secondFounder)
        second.addBeliefs(listOf(game.createBelief(BeliefType.Follower, "[+100]% Natural religion spread [in all cities]")))
        game.addCity(firstFounder, game.getTile(0, -4)).religion.addPressure(first.name, 5000)
        val secondSender = game.addCity(secondFounder, game.getTile(4, 4))
        secondSender.religion.addPressure(second.name, 5000)
        val target = game.addCity(game.addCiv(), game.getTile(0, 0))
        val route = TradeRoute(secondSender.location, target.location, TradeRouteKind.International, false, "Caravan",
            listOf(secondSender.location, target.location), 2, 30, 0)
        route.setTransients(secondFounder)
        secondFounder.tradeRoutes.routes.add(route)
        val goldBefore = firstFounder.gold

        target.religion.endTurn()
        assertEquals(second.name, target.religion.getMajorityReligionName())
        return firstFounder.gold - goldBefore
    }

    @Test
    fun `a religion that holds the majority between two sources is adopted`() {
        // The first sends 360 (40% of the range), more than the atheism of 1 citizen, and holds the city until the second sends 2000
        assertEquals(50, goldOfFirstReligionHeldBetweenSources())
    }

    @Test
    fun `without the switch the followers are counted once at the end of the turn`() {
        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        assertEquals(0, goldOfFirstReligionHeldBetweenSources())
    }
}
