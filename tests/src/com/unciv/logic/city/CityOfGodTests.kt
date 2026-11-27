package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's City of God (Belief_YieldPerHolyCityBirth): when the holy city grows, a yield for each city following the religion, up to a limit
 * (CvCity::setPopulation, V:CvCity.cpp:16608-16615; CvPlayer::doInstantYield, V:CvPlayer.cpp:26028-26035; CvReligionBeliefs::GetYieldPerHolyCityBirth,
 * V:CvBeliefClasses.cpp:4577-4597).
 */
@RunWith(GdxTestRunner::class)
class CityOfGodTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City
    private lateinit var ours: Religion
    private lateinit var foreign: Religion
    private lateinit var foreignCity: City

    private val holy = "in the holy city of our religion following any religion"
    private val cities = "Cities following our religion up to [3]"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(9)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(0, 0), initialPopulation = 3)
        second = game.addCity(civ, game.getTile(5, 0), initialPopulation = 3)
        ours = religion(civ, capital, "Gain [15] [Faith] <upon population growth> <in [$holy] cities> <for every [$cities]>")
        val other = game.addCiv()
        foreignCity = game.addCity(other, game.getTile(-5, 0), initialPopulation = 3)
        foreign = religion(other, foreignCity)
        foreignCity.follow(foreign)
        game.gameInfo.turns = 5 // a city grows for instant yields only after the turn it was founded
    }

    /** [founder]'s religion with a Founder belief of [uniques] and its holy city [holyCity] */
    private fun religion(founder: Civilization, holyCity: City, vararg uniques: String): Religion {
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, *uniques)))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        founder.religionManager.invalidateStateReligion()
        return religion
    }

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    private fun count() = Countables.getCountableAmount(cities, GameContext(civ))

    /** The Faith [city] brings its owner by growing one citizen above its highest */
    private fun faithOfGrowth(city: City): Int {
        val before = civ.religionManager.storedFaith
        city.population.addPopulation(1)
        return civ.religionManager.storedFaith - before
    }

    @Test
    fun `the cities following our religion are those of every civilization, up to the limit`() {
        capital.follow(ours)
        assertEquals(1, count())
        foreignCity.follow(ours)
        assertEquals(2, count()) // a foreign city counts
        second.follow(ours)
        game.addCity(civ, game.getTile(0, 5), initialPopulation = 3).follow(ours)
        assertEquals(3, count()) // four cities, the limit is 3
    }

    @Test
    fun `without a religion of our own the cities count none`() {
        capital.religion.religionThisIsTheHolyCityOf = null // no founder benefits without the holy city
        capital.follow(ours)
        assertEquals(0, count())
    }

    @Test
    fun `the holy city filter takes the holy city of our religion following any religion`() {
        assertFalse(capital.matchesFilter(holy)) // no majority yet
        capital.follow(foreign)
        assertTrue(capital.matchesFilter(holy)) // another religion's majority does
        second.follow(ours)
        assertFalse(second.matchesFilter(holy)) // not the holy city
    }

    @Test
    fun `the holy city of a religion we did not found is not ours`() {
        foreignCity.religion.religionThisIsTheHolyCityOf = null
        second.religion.religionThisIsTheHolyCityOf = foreign.name // as if we had conquered it
        second.follow(foreign)
        assertFalse(second.matchesFilter(holy)) // no founder benefits of it without religiousBeliefValidityLikeVp
    }

    @Test
    fun `City of God gains when the holy city grows, for each city following our religion`() {
        capital.follow(ours)
        foreignCity.follow(ours)
        assertEquals(30, faithOfGrowth(capital))
        assertEquals(0, faithOfGrowth(second))
    }

    @Test
    fun `City of God gains nothing for a growth below the highest population`() {
        capital.follow(ours)
        capital.population.addPopulation(-1)
        assertEquals(0, faithOfGrowth(capital))
    }
}
