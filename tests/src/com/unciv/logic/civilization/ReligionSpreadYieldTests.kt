package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Speed
import com.unciv.models.UnitActionType
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's yields of a religion's spread: Pilgrimage's Food for every spread and Tourism on the owner of a foreign city (CvUnit::DoSpreadReligion,
 * V:CvUnit.cpp:11345-11396; CvPlayer::doInstantYield's SPREAD and F_SPREAD, V:CvPlayer.cpp:26772-26823), each times one more than a number of followers.
 */
@RunWith(GdxTestRunner::class)
class ReligionSpreadYieldTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var holyCity: City
    private lateinit var ours: Religion

    private val food = "Gain [15] [Food] <upon spreading a religion to a city> <for every [Triggering amount]>"
    private val abroadGold = "Gain [7] [Gold] <upon spreading a religion to a city of another civilization> <for every [Triggering amount]>"
    private val tourism = "[15] Tourism towards the owner of the city of the event <(modified by game speed)> " +
        "<upon spreading a religion to a city of another civilization> <for every [Triggering amount]>"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        holyCity = game.addCity(civ, game.getTile(0, 0), initialPopulation = 2)
        ours = game.addReligion(civ)
        ours.addBeliefs(listOf(game.createBelief(BeliefType.Founder, food, abroadGold, tourism)))
        holyCity.religion.religionThisIsTheHolyCityOf = ours.name
        civ.religionManager.invalidateStateReligion()
    }

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    private fun spreadInto(city: City): MapUnit {
        val missionary = game.addUnit("Missionary", civ, city.getCenterTile().neighbors.first())
        missionary.religion = ours.name
        assertTrue(UnitActions.invokeUnitAction(missionary, UnitActionType.SpreadReligion))
        return missionary
    }

    /** A major civilization we have met, with a city of 4 citizens at (-6, 0) that follows a religion of its own */
    private fun foreignCityOfAnotherReligion(): Pair<Civilization, City> {
        val other = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val city = game.addCity(other, game.getTile(-6, 0), initialPopulation = 4)
        val theirs = game.addReligion(other)
        theirs.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        city.follow(theirs)
        return other to city
    }

    @Test
    fun `a spread at home gives Food in the holy city for one more than the followers won, and nothing of the foreign spread`() {
        val target = game.addCity(civ, game.getTile(6, 0), initialPopulation = 4)
        // some citizens follow ours already, the most another religion
        val theirs = game.addReligion(game.addCiv())
        theirs.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        target.religion.addPressure(theirs.name, 3000)
        target.religion.addPressure(ours.name, 1000)
        val followersBefore = target.religion.getFollowersOf(ours.name)
        assertTrue(followersBefore > 0)
        val foodBefore = holyCity.population.foodStored
        val goldBefore = civ.gold

        spreadInto(target)

        val won = target.religion.getFollowersOf(ours.name) - followersBefore
        assertTrue(won > 0)
        assertEquals(15 * (won + 1), holyCity.population.foodStored - foodBefore)
        assertEquals(goldBefore, civ.gold)
    }

    @Test
    fun `a spread abroad counts the followers of other religions before it and gives Tourism on the owner with the modifiers towards it`() {
        val (other, target) = foreignCityOfAnotherReligion()
        // a religion shared by most cities of both: the 25 percent of tourismModifierSharedReligion
        game.addCity(civ, game.getTile(6, 0)).follow(ours)
        holyCity.follow(ours)
        game.addCity(other, game.getTile(-6, 4)).follow(ours)
        game.addCity(other, game.getTile(-6, -4)).follow(ours)
        assertEquals(25, civ.culture.getTourismModifierToward(other))
        val goldBefore = civ.gold

        spreadInto(target)

        assertEquals(7 * (4 + 1), civ.gold - goldBefore) // the 4 citizens of the other religion before the spread
        assertEquals(5 * (15 * 125 / 100), civ.culture.getInfluence(other)) // each of the 5 copies cut by itself
    }

    @Test
    fun `a spread into a city-state gives the other yields of the foreign spread but no Tourism`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        civ.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val target = game.addCity(cityState, game.getTile(-6, 0), initialPopulation = 4)
        val goldBefore = civ.gold

        spreadInto(target)

        assertEquals(7, civ.gold - goldBefore) // no followers of other religions: max(1, 0 + 1)
        assertEquals(0, civ.culture.getInfluence(cityState))
    }

    private fun useQuickSpeed() {
        game.ruleset.modOptions.constants.instantYieldsUseInstantYieldPercent = true
        // a new Speed, the ruleset's one belongs to every other test
        val old = game.ruleset.speeds[Speed.DEFAULTFORSIMULATION]!!
        val speed = Speed().apply { name = old.name; turns = old.turns; startYear = old.startYear; instantYieldModifier = 0.75f; cultureCostModifier = 0.67f }
        game.ruleset.speeds[speed.name] = speed
        game.setSpeed(speed.name)
    }

    @Test
    fun `the Tourism of a spread abroad is changed by the instant yield and the culture speed, each copy cut by itself`() {
        useQuickSpeed()
        val (other, target) = foreignCityOfAnotherReligion()

        spreadInto(target)

        // 15 x 75 / 100 = 11, then 11 x 67 / 100 = 7, for each of the 5
        assertEquals(35, civ.culture.getInfluence(other))
    }

    @Test
    fun `with instantYieldsCutTotalLikeVp the Tourism of a spread abroad is cut once`() {
        useQuickSpeed()
        game.ruleset.modOptions.constants.instantYieldsCutTotalLikeVp = true
        val (other, target) = foreignCityOfAnotherReligion()

        spreadInto(target)

        // 75 x 75 / 100 = 56, then 56 x 67 / 100 = 37
        assertEquals(37, civ.culture.getInfluence(other))
    }
}
