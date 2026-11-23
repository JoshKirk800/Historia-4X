package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Tribute as Vox Populi works it out (`CvMinorCivAI::CalculateBullyScore`, V:CvMinorCivAI.cpp:14015-14400, `GetBullyGoldAmount`, 13969-14012, `CanMajorBullyGold`, 14403-14470,
 *  `DoMajorBullyGold`, 14603-14750, `DoBulliedByMajorReaction`, 15025-15080), with the switch [ModConstants.cityStateBullyLikeVp]. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 *  Every expected number is worked out by hand from those lines.
 */
@RunWith(BaseTestRunner::class)
class CityStateBullyTests {
    private val constants = ModConstants.CityStateBullyConstants()

    private fun score(
        globalMilitaryPercent: Int = 0, bullyPower: Int = 0, cityStatePower: Int = 100, militaryModifier: Int = 0, policyModifier: Int = 0, turnsSinceBullied: Int? = null,
        trainPercent: Int = 100, hasOtherAlly: Boolean = false, otherProtectors: Int = 0, ignorePenalties: Boolean = false, hostile: Boolean = false, militaristic: Boolean = false
    ) = CityStateBully.score(
        constants, CityStateBully.Inputs(
            globalMilitaryPercent, bullyPower, cityStatePower, militaryModifier, policyModifier, turnsSinceBullied, trainPercent, hasOtherAlly, otherProtectors, ignorePenalties, hostile, militaristic
        )
    )

    @Test
    fun `the global military score is half of the share of the might, with no base reluctance`() {
        // V:14090-14108: 100 x might / total, x 50 / 100
        assertEquals(0, score(globalMilitaryPercent = 0))
        assertEquals(20, score(globalMilitaryPercent = 40))
        assertEquals(50, score(globalMilitaryPercent = 100))
    }

    @Test
    fun `the local military score is linear if the bully is weaker and square root if it is stronger`() {
        assertEquals("100 x 50 / 100", 50, score(bullyPower = 50, cityStatePower = 100))
        assertEquals("sqrt(10000 x 120 / 100) = 109, over the cap", 100, score(bullyPower = 120, cityStatePower = 100))
        assertEquals("sqrt(10000 x 100 / 100) = 100: not stronger: 100 x 100 / 100", 100, score(bullyPower = 100, cityStatePower = 100))
        assertEquals("a bully of no power", 0, score(bullyPower = 0, cityStatePower = 100))
    }

    @Test
    fun `the traits scale each military part and the policies the sum`() {
        // 20 + 50 = 70; the trait of the Zulu x150: 30 + 75 = 105, capped at 100
        assertEquals(100, score(globalMilitaryPercent = 40, bullyPower = 50, militaryModifier = 50))
        // 70 + 70 x 25 / 100 = 87
        assertEquals(87, score(globalMilitaryPercent = 40, bullyPower = 50, policyModifier = 25))
    }

    @Test
    fun `a tribute within 10 turns is refused and one within 30 costs 10 for each turn left`() {
        assertEquals(-1000, score(globalMilitaryPercent = 100, turnsSinceBullied = 9))
        assertEquals("10 turns later: -10 x (30 - 10) = -200", 50 - 200, score(globalMilitaryPercent = 100, turnsSinceBullied = 10))
        assertEquals("29 turns: -10", 50 - 10, score(globalMilitaryPercent = 100, turnsSinceBullied = 29))
        assertEquals("30 turns: nothing", 50, score(globalMilitaryPercent = 100, turnsSinceBullied = 30))
        assertEquals("never", 50, score(globalMilitaryPercent = 100, turnsSinceBullied = null))
        // Marathon: 3 times as many turns
        assertEquals(-1000, score(globalMilitaryPercent = 100, turnsSinceBullied = 29, trainPercent = 300))
        assertEquals(50 - 10 * (90 - 40), score(globalMilitaryPercent = 100, turnsSinceBullied = 40, trainPercent = 300))
    }

    @Test
    fun `an ally, each protector, a hostile and a militaristic city-state take off 15, 15, 10 and 10 unless the penalties are ignored`() {
        assertEquals(50 - 15, score(globalMilitaryPercent = 100, hasOtherAlly = true))
        assertEquals(50 - 30, score(globalMilitaryPercent = 100, otherProtectors = 2))
        assertEquals(50 - 20, score(globalMilitaryPercent = 100, hostile = true, militaristic = true))
        assertEquals("the Zulu ignore the ally and the protectors, not the personality", 50 - 10,
            score(globalMilitaryPercent = 100, hasOtherAlly = true, otherProtectors = 2, ignorePenalties = true, hostile = true))
    }

    @Test
    fun `tribute needs a score of 20 for gold and 100 for heavy tribute`() {
        assertFalse(CityStateBully.canBullyGold(constants, 0))
        assertFalse(CityStateBully.canBullyGold(constants, 19))
        assertTrue(CityStateBully.canBullyGold(constants, 20))
        assertFalse(CityStateBully.canBullyHeavy(constants, 99))
        assertTrue(CityStateBully.canBullyHeavy(constants, 100))
    }

    @Test
    fun `the gold grows with the game and the era, with the speed, the trait and the score`() {
        // half the game: 50 + 500 = 550; the third era: 550 x 3 + 550 / 5 = 1650 + 110 = 1760
        assertEquals(1760, CityStateBully.goldAmount(constants, 0.5f, 3, 100, 0, 100))
        // an era before the first counts as the first: 50 x 1 + 50 / 3 = 66
        assertEquals(66, CityStateBully.goldAmount(constants, 0f, 0, 100, 0, 100))
        // Marathon 200% and a trait of +50% and a score of 60%: 66 x 200 / 100 = 132, 132 x 150 / 100 = 198, x 60 / 100 = 118
        assertEquals(118, CityStateBully.goldAmount(constants, 0f, 1, 200, 50, 60))
        // a failed score takes nothing
        assertEquals(0, CityStateBully.goldAmount(constants, 0.5f, 3, 100, 0, -1000))
    }

    @Test
    fun `a tribute costs 30 influence and the points over the resting point`() {
        assertEquals("below the resting point change", -3000, CityStateBully.influenceChangeTimes100(constants, 5000, 1000))
        assertEquals("40 over the resting point of 10: -30 - 40", -7000, CityStateBully.influenceChangeTimes100(constants, 1000, 5000))
        assertEquals("a heavy tribute costs 60 and not the excess", -6000, CityStateBully.influenceChangeTimes100(constants, 1000, 5000, heavy = true))
    }

    // region the game

    private val game = TestGame()
    private val cityState: Civilization = game.addCiv(cityStateType = "Militaristic")
    private val other: Civilization = game.addCiv()

    @Before
    fun setUp() {
        game.makeHexagonalMap(8)
        game.addUnit("Warrior", other, game.getTile(HexCoord(0, 7)))
        game.addCity(cityState, game.getTile(HexCoord.Zero))
        other.diplomacyFunctions.makeCivilizationsMeet(cityState)
        game.ruleset.modOptions.constants.cityStateBullyLikeVp = true
        cityState.cityStatePersonality = CityStatePersonality.Neutral // the game rolls one
    }

    /** A major with a city and a unit far from the city-state (out of the range of its local power), who has met it */
    private fun newBully(vararg uniques: String): Civilization {
        val bully = game.addCiv(*uniques)
        game.addCity(bully, game.getTile(HexCoord(-7, 0)))
        game.addUnit("Warrior", bully, game.getTile(HexCoord(-7, 1)))
        bully.diplomacyFunctions.makeCivilizationsMeet(cityState)
        return bully
    }
    private fun Civilization.toBully() = cityState.getDiplomacyManager(this)!!

    @Test
    fun `the tribute table has no base value, the global share counts only the majors with cities, and the willingness is the score`() {
        val bully = newBully()
        val modifiers = cityState.cityStateFunctions.getTributeModifiers(bully, requireWholeList = true)
        assertNull(modifiers["Base value"])
        assertNull(modifiers["Influence below -30"])
        // the only major with a city and might: 100%, half of it; the Militaristic city-state -10
        assertEquals(50, modifiers["Military Rank"])
        assertEquals(-10, modifiers["Militaristic"])
        assertEquals(40, cityState.cityStateFunctions.getTributeWillingness(bully))
        assertTrue(cityState.cityStateFunctions.canBullyGold(bully))
        assertFalse("a score of 40 is no heavy tribute", cityState.cityStateFunctions.canBullyHeavy(bully))
    }

    @Test
    fun `a tribute pays the gold scaled by the score, costs the influence and cannot be repeated at once`() {
        val bully = newBully()
        game.gameInfo.turns = 0
        val gold = bully.gold
        bully.toBully().setInfluenceWithoutSideEffects(40f)

        cityState.cityStateFunctions.tributeGold(bully)

        // 50 + 0, era 1: 50 + 50 / 3 = 66, x 40 / 100 = 26
        assertEquals(gold + 26, bully.gold)
        // -30 and the 40 over the resting point change of 0: -70
        assertEquals(-30f, bully.toBully().getInfluence(), 0.001f)
        assertEquals(0, bully.toBully().turnLastBullied)
        game.gameInfo.turns = 5
        assertEquals("bullied this many turns ago: refused", -1000, cityState.cityStateFunctions.getTributeWillingness(bully))
        assertFalse(cityState.cityStateFunctions.canBullyGold(bully))
    }

    @Test
    fun `a tribute gives the yields of the demand`() {
        val bully = newBully("Gain [50]% of the triggering amount as [Culture] <upon demanding tribute from a City-State>")
        game.gameInfo.turns = 0
        val culture = bully.policies.storedCulture
        cityState.cityStateFunctions.tributeGold(bully)
        // 26 gold, 50% of it
        assertEquals(culture + 13, bully.policies.storedCulture)
    }

    @Test
    fun `a civilization that can bully friendly city-states loses no influence, and the shift moves a share between the civilizations`() {
        val bully = newBully(
            "Tribute demands cost no Influence and do not break our Pledges to Protect",
            "Demanding tribute raises our Influence with that City-State by [10]% and lowers the Influence of every other civilization by the same percentage"
        )
        game.gameInfo.turns = 0
        bully.toBully().setInfluenceWithoutSideEffects(40f)
        other.toBully().setInfluenceWithoutSideEffects(50f)
        cityState.cityStateFunctions.addProtectorCiv(bully)

        cityState.cityStateFunctions.tributeGold(bully)

        assertEquals("10% of 40 more", 44f, bully.toBully().getInfluence(), 0.001f)
        assertEquals("10% of 50 less", 45f, other.toBully().getInfluence(), 0.001f)
        assertTrue("the pledge stands", bully in cityState.cityStateFunctions.getProtectorCivs())
    }

    @Test
    fun `without the switch the tribute is Brave New World's`() {
        val bully = newBully()
        game.ruleset.modOptions.constants.cityStateBullyLikeVp = false
        val modifiers = cityState.cityStateFunctions.getTributeModifiers(bully, requireWholeList = true)
        assertEquals(-110, modifiers["Base value"])
    }

    // endregion
}
