package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager.nextTurn
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What gives and takes influence besides the turn, as Vox Populi has it, with [ModConstants.cityStateInfluenceLikeVp]: a gifted unit (`CvMinorCivAI::GetFriendshipFromUnitGift`,
 *  V:CvMinorCivAI.cpp:15500-15545), the barbarian killed, the units in the borders (11694-11772), the liberation (`DoLiberationByMajor`, 11878-11965), the resting point change of a
 *  major (10664-10752), the jerk turns (16235-16258), the influence per unit gifted (10401-10437) and the great people expended (`CvPlayer.cpp:28977-29000`). V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
@RunWith(BaseTestRunner::class)
class CityStateInfluenceEventsTests {
    private val constants = ModConstants.CityStateInfluenceConstants()

    @Test
    fun `a unit gift is 15, doubled by a proxy war, with the policy's extra, and 1 less for each unit the city-state has, at most 7, unless there is a proxy war`() {
        assertEquals(15, CityStateInfluence.unitGiftInfluence(constants, greatPerson = false, proxyWar = false, policyExtra = 0, cityStateMilitaryUnits = 0))
        assertEquals(12, CityStateInfluence.unitGiftInfluence(constants, false, false, 0, 3))
        assertEquals("at most half of the 15 rounded down: 7", 8, CityStateInfluence.unitGiftInfluence(constants, false, false, 0, 20))
        assertEquals("a proxy war doubles it and takes nothing for the units", 30, CityStateInfluence.unitGiftInfluence(constants, false, true, 0, 20))
        assertEquals("the extra of Arsenal of Democracy comes before the units", 15 + 40 - 3, CityStateInfluence.unitGiftInfluence(constants, false, false, 40, 3))
        assertEquals("a great person gives what the trait says: nothing in Vox Populi", 0, CityStateInfluence.unitGiftInfluence(constants, true, false, 40, 0))
    }

    @Test
    fun `a liberator gets the highest influence of the others and its own, over by 105 for each era, at least 70`() {
        // era 2: 210. the highest other is 40: 4000 + 21000 = 25000; the liberator's own 10: 1000 + 21000
        assertEquals(25000, CityStateInfluence.liberationInfluenceTimes100(constants, 4000, 1000, 2, false))
        assertEquals("its own influence can be the higher", 21000 + 9000, CityStateInfluence.liberationInfluenceTimes100(constants, 4000, 9000, 2, false))
        assertEquals("from the barbarians: half of it", 10500 + 4000, CityStateInfluence.liberationInfluenceTimes100(constants, 4000, 1000, 2, true))
        assertEquals("an era before the first counts as the first, and it is always enough to be ally: 70", 7000, CityStateInfluence.liberationInfluenceTimes100(constants, -6000, -6000, 0, true))
    }

    // region the game

    private val game = TestGame()
    private val cityState: Civilization = game.addCiv(cityStateType = "Militaristic")
    private val major: Civilization = game.addCiv()

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        game.addUnit("Warrior", major, game.getTile(HexCoord(-4, 0)))
        game.addCity(cityState, game.getTile(HexCoord.Zero))
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        game.ruleset.modOptions.constants.cityStateInfluenceLikeVp = true
        cityState.cityStatePersonality = CityStatePersonality.Neutral // the game rolls one
    }

    private val toMajor get() = cityState.getDiplomacyManager(major)!!

    @Test
    fun `a killed barbarian gives 15`() {
        cityState.cityStateFunctions.threateningBarbarianKilledBy(major)
        assertEquals(15f, toMajor.getInfluence(), 0.001f)
    }

    @Test
    fun `every military unit in the borders takes 5`() {
        // the units arrive first, the city-state's borders grow over them
        for (x in 3..5) {
            val tile = game.getTile(HexCoord(x, 0))
            game.addUnit("Warrior", major, tile)
            tile.setOwningCity(cityState.getCapital()!!)
        }
        cityState.cityStateFunctions.updateDiplomaticRelationshipForCityState()
        assertEquals(-15f, toMajor.getInfluence(), 0.001f)
    }

    @Test
    fun `the resting point change of a major is part of its resting point`() {
        toMajor.restingPointChange = 100f
        assertEquals(100f, toMajor.getCityStateInfluenceRestingPoint(), 0f)
    }

    @Test
    fun `attacking a city-state makes it a jerk for 50 turns, which lowers the resting point by 20`() {
        cityState.cityStateFunctions.cityStateAttacked(major)
        assertEquals(50, toMajor.getFlag(DiplomacyFlags.JerkTurns))
        assertTrue(toMajor.getCityStateInfluenceRestingPoint() <= -20f)
    }

    @Test
    fun `a military unit that a major gifted gives it influence each turn`() {
        val giver = game.addCiv("[1] Influence per turn with City-States for each of their military units we gifted")
        game.addUnit("Warrior", giver, game.getTile(HexCoord(0, 4)))
        giver.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val unit = game.addUnit("Warrior", cityState, game.getTile(HexCoord(1, 0)))
        unit.giftedBy = giver.civID

        val toGiver = cityState.getDiplomacyManager(giver)!!
        toGiver.nextTurn()

        assertEquals(1f, toGiver.getInfluence(), 0.001f)
    }

    @Test
    fun `a unit gift is worth the rule of Vox Populi`() {
        val baseUnit = game.ruleset.units["Warrior"]!!
        assertEquals(15f, cityState.cityStateFunctions.unitGiftInfluence(major, baseUnit), 0.001f)
        game.addUnit("Warrior", cityState, game.getTile(HexCoord(1, 0)))
        game.addUnit("Warrior", cityState, game.getTile(HexCoord(2, 0)))
        assertEquals("1 less for each unit of the city-state", 13f, cityState.cityStateFunctions.unitGiftInfluence(major, baseUnit), 0.001f)
        game.ruleset.modOptions.constants.cityStateInfluenceLikeVp = false
        assertEquals("Brave New World's 5", 5f, cityState.cityStateFunctions.unitGiftInfluence(major, baseUnit), 0.001f)
    }

    @Test
    fun `gold gifts can be switched off`() {
        assertTrue(cityState.cityStateFunctions.goldGiftsAllowed())
        game.ruleset.modOptions.constants.cityStateGoldGifts = false
        assertFalse(cityState.cityStateFunctions.goldGiftsAllowed())
    }

    @Test
    fun `an expended great person gives influence with every city-state met, if a policy says so`() {
        val owner = game.addCiv("Gain [10] Influence with all City-States we have met <upon expending a [Great Person] unit>")
        game.addUnit("Warrior", owner, game.getTile(HexCoord(0, 4)))
        owner.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val unit = game.addUnit("Great Scientist", owner, game.getTile(HexCoord(0, 4)))

        unit.consume()

        assertEquals(10f, cityState.getDiplomacyManager(owner)!!.getInfluence(), 0.001f)
    }

    // endregion
}
