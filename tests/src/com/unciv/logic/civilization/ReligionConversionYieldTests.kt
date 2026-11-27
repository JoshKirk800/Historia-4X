package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Speed
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's yield of a city's first conversion to a religion: Papal Primacy's yields in the holy city, times (100 + C x C) / 100 for the cities following the
 * religion (CvCityReligions::CityConvertsReligion, V:CvReligionClasses.cpp:5581-5626; CvPlayer::doInstantYield's CONVERSION, V:CvPlayer.cpp:26701-26718).
 */
@RunWith(GdxTestRunner::class)
class ReligionConversionYieldTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var holyCity: City
    private lateinit var ours: Religion

    private val papalProduction = "Gain [1] [Production] <upon a city adopting our religion for the first time> <(modified by game speed)> " +
        "<for every [floor(50 * (100 + [Cities following our religion up to [25]] ^ 2) / 100)]>"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        holyCity = game.addCity(civ, game.getTile(0, 0), initialPopulation = 2)
        ours = game.addReligion(civ)
        ours.addBeliefs(listOf(game.createBelief(BeliefType.Founder, papalProduction)))
        holyCity.religion.religionThisIsTheHolyCityOf = ours.name
        civ.religionManager.invalidateStateReligion()
        holyCity.follow(ours)
        game.addCity(civ, game.getTile(6, 0)).follow(ours)
    }

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    private fun productionOf(city: City) = city.cityConstructions.productionOverflow + city.cityConstructions.inProgressConstructions.values.sum()

    @Test
    fun `a city's first conversion gives the holy city the yield times a hundredth more for the square of the cities following, the converted one too`() {
        val other = game.addCiv()
        val target = game.addCity(other, game.getTile(-6, 0), initialPopulation = 3)
        val before = productionOf(holyCity)

        target.follow(ours)

        assertEquals(50 * (100 + 3 * 3) / 100, productionOf(holyCity) - before)
    }

    @Test
    fun `a city that adopts our religion again gives nothing`() {
        val other = game.addCiv()
        val target = game.addCity(other, game.getTile(-6, 0), initialPopulation = 3)
        val theirs = game.addReligion(other)
        theirs.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        target.follow(ours)
        target.follow(theirs)
        val before = productionOf(holyCity)

        target.follow(ours)

        assertEquals(before, productionOf(holyCity))
    }

    @Test
    fun `the owner of a holy city whose religion is not its state religion gets nothing`() {
        val other = game.addCiv()
        val theirs = game.addReligion(other)
        val local = "Gain [5] [Production] <upon a city adopting our religion for the first time>"
        theirs.addBeliefs(listOf(game.createBelief(BeliefType.Founder), game.createBelief(BeliefType.Follower, local)))
        // we hold their holy city, but our state religion is the one we founded
        val theirHolyCity = game.addCity(civ, game.getTile(-6, 0), initialPopulation = 3)
        theirHolyCity.religion.religionThisIsTheHolyCityOf = theirs.name
        theirHolyCity.follow(theirs)
        assertEquals(ours, civ.religionManager.getStateReligion())
        val target = game.addCity(other, game.getTile(0, 6), initialPopulation = 3)
        val before = productionOf(theirHolyCity)

        target.follow(theirs)

        assertEquals(before, productionOf(theirHolyCity))
    }

    @Test
    fun `with instantYieldsCutTotalLikeVp the yield is changed by the speed once`() {
        game.ruleset.modOptions.constants.instantYieldsUseInstantYieldPercent = true
        game.ruleset.modOptions.constants.instantYieldsCutTotalLikeVp = true
        // a new Speed, the ruleset's one belongs to every other test
        val old = game.ruleset.speeds[Speed.DEFAULTFORSIMULATION]!!
        val speed = Speed().apply { name = old.name; turns = old.turns; startYear = old.startYear; instantYieldModifier = 0.75f }
        game.ruleset.speeds[speed.name] = speed
        game.setSpeed(speed.name)
        val target = game.addCity(civ, game.getTile(-6, 0), initialPopulation = 3)
        val before = productionOf(holyCity)

        target.follow(ours)

        assertEquals(54 * 75 / 100, productionOf(holyCity) - before)
    }
}
