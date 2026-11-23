package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager.nextTurn
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.Speed
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The influence of a major with a city-state as Vox Populi changes it each turn (`CvMinorCivAI::GetFriendshipChangePerTurnTimes100`, V:CvMinorCivAI.cpp:10393-10562, the resting point
 *  `GetFriendshipAnchorWithMajor`, 10664-10752, the ally `DoFriendshipChangeEffects`, 11348-11489), with the switch [ModConstants.cityStateInfluenceLikeVp]. V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Every expected number is worked out by hand from those lines, in hundredths where the source is.
 */
@RunWith(BaseTestRunner::class)
class CityStateInfluenceTests {
    private val constants = ModConstants.CityStateInfluenceConstants()

    private fun change(
        current: Int, resting: Int = 0, shift: Int = 0, recoveryPercent: Int = 100, drop: Int = 100, damagedProtected: Boolean = false,
        decayPercent: Int = 100, stopsDecay: Boolean = false, trainPercent: Int = 100, ally: Boolean = false
    ) = CityStateInfluence.changePerTurnTimes100(
        constants,
        CityStateInfluence.Change(current * 100, resting * 100, shift, recoveryPercent, drop, damagedProtected, decayPercent, stopsDecay, trainPercent, ally)
    )

    @Test
    fun `at the resting point only the shift changes it`() {
        assertEquals(0, change(current = 20, resting = 20))
        assertEquals(50, change(current = 20, resting = 20, shift = 50))
    }

    @Test
    fun `below the resting point influence recovers by 1 per turn, with the religion and the trait percentages, and never above it`() {
        assertEquals(100, change(current = 5, resting = 20))
        assertEquals("shared religion +50%", 150, change(current = 5, resting = 20, recoveryPercent = 150))
        assertEquals("Greece recovers at twice the rate", 200, change(current = 5, resting = 20, recoveryPercent = 200))
        // 0.5 below the resting point: the recovery is cut off at the delta (V:CvMinorCivAI.cpp:10542-10555)
        assertEquals(50, CityStateInfluence.changePerTurnTimes100(constants, CityStateInfluence.Change(1950, 2000, 0, 100, 100, false, 100, false, 100, false)))
    }

    @Test
    fun `above the resting point influence decays by 1, 1,5 for a hostile city-state and 2 for an aggressor`() {
        assertEquals(-100, change(current = 40, resting = 20))
        assertEquals(-150, change(current = 40, resting = 20, drop = 150))
        assertEquals(-200, change(current = 40, resting = 20, drop = 200))
    }

    @Test
    fun `decay grows with the points over the resting point`() {
        // 50 or less over: the base only; 75 over: (7500 - 5000) / -50 = -50 more
        assertEquals(-100, CityStateInfluence.changePerTurnTimes100(constants, CityStateInfluence.Change(5000, 0, 0, 100, 100, false, 100, false, 100, false)))
        assertEquals(-150, CityStateInfluence.changePerTurnTimes100(constants, CityStateInfluence.Change(7500, 0, 0, 100, 100, false, 100, false, 100, false)))
        // 80 over: 3000 / -50 = -60
        assertEquals(-160, CityStateInfluence.changePerTurnTimes100(constants, CityStateInfluence.Change(8000, 0, 0, 100, 100, false, 100, false, 100, false)))
        // 100 over: (100/100)^1.5 x -100 = -100 more, 200 over: 2^1.5 = 2.828 -> -282, 300 over: 3^1.5 = 5.196 -> -519, cut toward zero
        assertEquals(-200, change(current = 100))
        assertEquals(-382, change(current = 200))
        assertEquals(-619, change(current = 300))
    }

    @Test
    fun `a damaged capital triples the decay towards its protector`() {
        assertEquals(-300, change(current = 40, resting = 20, damagedProtected = true))
        assertEquals("the exponent term is tripled with the rest", -600, change(current = 100, damagedProtected = true))
    }

    @Test
    fun `the decay percentage scales a decay and the shift is added after it`() {
        assertEquals("Greece: -33% decay", -67, change(current = 40, resting = 20, decayPercent = 67))
        assertEquals("a decay percentage under 0 is 0", 0, change(current = 40, resting = 20, decayPercent = -50))
        // +0.5 a turn from a policy on top of the decay of 1: -0.5
        assertEquals(-50, change(current = 40, resting = 20, shift = 50))
    }

    @Test
    fun `an ally at war or under the Cold War embargo loses nothing`() {
        assertEquals(0, change(current = 70, resting = 0, stopsDecay = true, ally = true))
        assertEquals("only the ally", -200, change(current = 100, resting = 0, stopsDecay = true, ally = false))
        assertEquals("only a decay", 100, change(current = 5, resting = 20, stopsDecay = true, ally = true))
    }

    @Test
    fun `the game speed divides the change by its percentage`() {
        assertEquals("Marathon: -100 x 100 / 300 = -33", -33, change(current = 40, resting = 20, trainPercent = 300))
        assertEquals("Quick: 100 x 100 / 67 = 149", 149, change(current = 5, resting = 20, trainPercent = 67))
    }

    @Test
    fun `recovery never carries above the resting point but the shift does`() {
        // 0.05 below the resting point, a shift of 1: 100 + 100 = 200, the recovery part is cut to the 5 left, the shift stays
        assertEquals(105, CityStateInfluence.changePerTurnTimes100(constants, CityStateInfluence.Change(1995, 2000, 100, 100, 100, false, 100, false, 100, false)))
    }

    @Test
    fun `the ally stays until another civilization has more influence`() {
        val influence = mapOf("A" to 7000, "B" to 7000, "C" to 0)
        // B reaches the ally's influence: equal is not more
        assertEquals("A", CityStateInfluence.allyAfter("A", "B", 6900, 7000, true, { influence[it]!! }, listOf("A", "B", "C"), null, false))
        // B passes A
        assertEquals("B", CityStateInfluence.allyAfter("A", "B", 7000, 7100, true, { if (it == "B") 7100 else influence[it]!! }, listOf("A", "B", "C"), null, false))
        // nobody is ally yet
        assertEquals("C", CityStateInfluence.allyAfter(null, "C", 5900, 6100, true, { if (it == "C") 6100 else 0 }, listOf("A", "B", "C"), null, false))
        // the ally falls under the threshold
        assertNull(CityStateInfluence.allyAfter("A", "A", 6100, 5900, false, { if (it == "A") 5900 else 5000 }, listOf("A", "B"), null, false))
        // the ally loses influence but stays above the threshold, and another one is now higher
        assertEquals("B", CityStateInfluence.allyAfter("A", "A", 7000, 6500, true, { if (it == "A") 6500 else 6800 }, listOf("A", "B"), null, false))
        // a permanent ally and no ally override everything
        assertEquals("C", CityStateInfluence.allyAfter("A", "B", 6000, 9000, true, { 9000 }, listOf("A", "B", "C"), "C", false))
        assertNull(CityStateInfluence.allyAfter("A", "B", 6000, 9000, true, { 9000 }, listOf("A", "B", "C"), "C", true))
    }

    // region the game

    private val game = TestGame()
    private val major: Civilization = game.addCiv("Resting point for Influence with City-States we are the Ally of is increased by [100] while we are at war")
    private val other: Civilization = game.addCiv()
    private val cityState: Civilization = game.addCiv(cityStateType = "Militaristic")

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        game.addUnit("Warrior", major, game.getTile(HexCoord(-3, 0)))
        game.addUnit("Warrior", other, game.getTile(HexCoord(0, 3)))
        game.addCity(cityState, game.getTile(HexCoord.Zero))
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        other.diplomacyFunctions.makeCivilizationsMeet(cityState)
        game.ruleset.modOptions.constants.cityStateInfluenceLikeVp = true
        cityState.cityStatePersonality = CityStatePersonality.Neutral // the game rolls one
    }

    private val toMajor get() = cityState.getDiplomacyManager(major)!!
    private fun influenceAfterATurn(): Float { toMajor.nextTurn(); return toMajor.getInfluence() }

    @Test
    fun `a turn applies the change in hundredths`() {
        toMajor.setInfluenceWithoutSideEffects(130f)
        // 130 over the resting point 0: 1.3^1.5 x -100 = -148.2 -> -148, with the base -100: 130 - 2.48
        assertEquals(127.52f, influenceAfterATurn(), 0.001f)
    }

    @Test
    fun `without the switch the turn is Brave New World's flat decay of 1`() {
        game.ruleset.modOptions.constants.cityStateInfluenceLikeVp = false
        toMajor.setInfluenceWithoutSideEffects(130f)
        assertEquals(129f, influenceAfterATurn(), 0.001f)
    }

    @Test
    fun `a protector raises the resting point by 5 and a wary city-state lowers it by 20`() {
        assertEquals(0f, toMajor.getCityStateInfluenceRestingPoint(), 0f)
        cityState.cityStateFunctions.addProtectorCiv(major)
        assertEquals("Vox Populi's MINOR_FRIENDSHIP_ANCHOR_MOD_PROTECTED, not the 10 of the old code", 5f, toMajor.getCityStateInfluenceRestingPoint(), 0f)
        toMajor.setFlag(DiplomacyFlags.WaryOf, 10)
        assertEquals(-15f, toMajor.getCityStateInfluenceRestingPoint(), 0f)
    }

    @Test
    fun `an ally at war lifts its resting point by the policy`() {
        val enemy = game.addCiv()
        game.addUnit("Warrior", enemy, game.getTile(HexCoord(3, 0)))
        toMajor.setInfluence(70f)
        assertSame(major, cityState.allyCiv)
        assertEquals(0f, toMajor.getCityStateInfluenceRestingPoint(), 0f)

        major.diplomacyFunctions.makeCivilizationsMeet(enemy)
        major.getDiplomacyManager(enemy)!!.declareWar()
        assertEquals(100f, toMajor.getCityStateInfluenceRestingPoint(), 0f)

        // somebody who is not the ally gets nothing
        assertEquals(0f, cityState.getDiplomacyManager(other)!!.getCityStateInfluenceRestingPoint(), 0f)
    }

    @Test
    fun `the game speed slows the change`() {
        val old = game.ruleset.speeds[Speed.DEFAULTFORSIMULATION]!!
        val marathon = Speed().apply { name = old.name; turns = old.turns; startYear = old.startYear; modifier = 3f; productionCostModifier = 3f }
        game.ruleset.speeds[marathon.name] = marathon
        game.setSpeed(marathon.name)
        toMajor.setInfluenceWithoutSideEffects(30f)
        // -100 x 100 / 300 = -33
        assertEquals(29.67f, influenceAfterATurn(), 0.001f)
    }

    @Test
    fun `a hostile city-state decays faster`() {
        toMajor.setInfluenceWithoutSideEffects(40f)
        cityState.cityStatePersonality = CityStatePersonality.Hostile
        assertEquals(38.5f, influenceAfterATurn(), 0.001f)
    }

    @Test
    fun `a protector whose city-state has a damaged capital loses influence three times as fast`() {
        cityState.cityStateFunctions.addProtectorCiv(major)
        toMajor.setInfluenceWithoutSideEffects(40f)
        assertEquals(39f, influenceAfterATurn(), 0.001f)

        cityState.getCapital()!!.health = 100
        toMajor.setInfluenceWithoutSideEffects(40f)
        assertEquals(37f, influenceAfterATurn(), 0.001f)
    }

    @Test
    fun `an ally is replaced only by a civilization with more influence`() {
        toMajor.setInfluence(70f)
        assertSame(major, cityState.allyCiv)

        cityState.getDiplomacyManager(other)!!.setInfluence(70f)
        assertSame("equal is not more", major, cityState.allyCiv)

        cityState.getDiplomacyManager(other)!!.setInfluence(71f)
        assertSame(other, cityState.allyCiv)
    }

    // endregion
}
