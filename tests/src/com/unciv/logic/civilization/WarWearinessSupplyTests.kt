package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 CvPlayer::GetUnitSupplyBreakdown ends with the war weariness (V:CvPlayer.cpp:17089-17099): a major's supply before it is `max(0, supply)`, the reduction is
 *  GetSupplyReductionFromWarWeariness (V:CvPlayer.cpp:37101-37104: the highest war weariness percent x 34, kept between 0 and 100, / 100), and the total is `before x (100 - reduction) / 100`,
 *  each step cut. Here it is `unitSupplyWarWearinessLikeVp` with `unitSupplyLikeVp` and `warWearinessLikeVp`.
 *
 *  `a` has one size-1 original capital, worth (175 + 150) x 2 = 650 (see [com.unciv.logic.automation.diplomacy.WarWearinessTests]), so a weariness w against an enemy is
 *  100 w / 650 percent (58 -> 8, 130 -> 20, 260 -> 40, 2000 -> 100), and its reduction is that x 34 / 100 (8 -> 2, 20 -> 6, 40 -> 13, 100 -> 34). The supply before the
 *  weariness is made 40 by the grant of expended great people, which is read back from the breakdown rather than assumed.
 */
@RunWith(GdxTestRunner::class)
class WarWearinessSupplyTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @After
    fun restoreTable() = SupplyTable.restore(game.ruleset)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        SupplyTable.communityPatch(game.ruleset)
        val constants = game.ruleset.modOptions.constants
        constants.unitSupplyLikeVp = true
        constants.unitSupplyExpendedGreatPeopleLikeVp = true
        constants.warWearinessLikeVp = true
        constants.unitSupplyWarWearinessLikeVp = true
        game.makeHexagonalMap(12, Constants.grassland)
        a = game.addCiv(isPlayer = true)
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)), initialPopulation = 1)
        game.addCity(b, game.getTile(HexCoord(8, 0)))
        game.addCity(c, game.getTile(HexCoord(-8, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        a.getDiplomacyManager(b)!!.declareWar()
        a.unitSupplyFromExpendedGreatPeople = 40
        WarDamageAi.doTurn(a) // caches the empire's value, 650
    }

    private fun weary(against: Civilization, weariness: Int) {
        a.ai.diplomacy.memoryOf(against).warWeariness = weariness
    }

    /** The supply before the weariness, as the breakdown has it: a human's own supply and the grant of 40, so at least 40 */
    private val before get() = a.stats.getUnitSupplyBreakdown().beforeWarWeariness

    @Test
    fun `the weariness takes a percent of the supply, cut at each step`() {
        // with a reduction of 2 the total is N x 98 / 100, with 3 (a rounded up 2.72) N x 97 / 100, with 14 (a rounded up 13.6) N x 86 / 100: the supply N must tell them apart
        val n = before
        assertTrue("N = $n tells the reductions 2 and 3, and 13 and 14, apart", n * 98 / 100 != n * 97 / 100 && n * 87 / 100 != n * 86 / 100)
        weary(b, 58)
        val breakdown = a.stats.getUnitSupplyBreakdown()
        assertEquals("100 x 58 / 650 = 8", 8, WarDamageAi.highestWarWearinessPercent(a))
        assertEquals("8 x 34 / 100 = 2 (2.72), not 3", 2, breakdown.warWearinessPercent)
        assertEquals("N x 98 / 100", n * 98 / 100, breakdown.total)
        assertEquals(n * 98 / 100, a.stats.getUnitSupply())

        weary(b, 260)
        assertEquals("40 x 34 / 100 = 13 (13.6), not 14", 13, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
        assertEquals("N x 87 / 100", n * 87 / 100, a.stats.getUnitSupplyBreakdown().total)
    }

    @Test
    fun `the most weary opponent takes 34 percent of the supply and no more`() {
        val n = before
        weary(b, 2000)
        val breakdown = a.stats.getUnitSupplyBreakdown()
        assertEquals("the percent is capped at 100, and 100 x 34 / 100", 34, breakdown.warWearinessPercent)
        assertEquals("N x 66 / 100", n * 66 / 100, breakdown.total)
    }

    @Test
    fun `the greatest weariness of the wars that can end counts`() {
        a.getDiplomacyManager(c)!!.declareWar()
        weary(b, 130)
        weary(c, 260)
        assertEquals(13, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
        a.ai.diplomacy.memoryOf(c).turnsLockedIntoWar = 3
        assertEquals("the locked war with c is left out: 20 x 34 / 100 = 6 (6.8)", 6, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
    }

    @Test
    fun `the share of the percent is the constant, kept between 0 and 100`() {
        val n = before
        weary(b, 260)
        val constants = game.ruleset.modOptions.constants
        constants.warWeariness = constants.warWeariness.copy(supplyReductionPercent = 100)
        assertEquals("the whole percent: 100 x 260 / 650 = 40", 40, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
        assertEquals("N x 60 / 100", n * 60 / 100, a.stats.getUnitSupplyBreakdown().total)
        constants.warWeariness = constants.warWeariness.copy(supplyReductionPercent = 150)
        assertEquals("150 is read as 100", 40, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
        constants.warWeariness = constants.warWeariness.copy(supplyReductionPercent = -20)
        assertEquals("-20 is read as 0", 0, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
        assertEquals(n, a.stats.getUnitSupplyBreakdown().total)
    }

    @Test
    fun `nothing is taken without the switch of the supply or of the weariness`() {
        val n = before
        weary(b, 260)
        assertEquals("N x 87 / 100", n * 87 / 100, a.stats.getUnitSupplyBreakdown().total)
        game.ruleset.modOptions.constants.unitSupplyWarWearinessLikeVp = false
        assertEquals(n, a.stats.getUnitSupplyBreakdown().total)
        assertEquals(0, a.stats.getUnitSupplyBreakdown().warWearinessPercent)
        assertEquals("the supply before the weariness is shown all the same", n, a.stats.getUnitSupplyBreakdown().beforeWarWeariness)
        game.ruleset.modOptions.constants.unitSupplyWarWearinessLikeVp = true
        game.ruleset.modOptions.constants.warWearinessLikeVp = false
        assertEquals("no weariness without its own switch", n, a.stats.getUnitSupplyBreakdown().total)
    }

    @Test
    fun `the weariness comes after the difficulty's bonus, which the supply before it has`() {
        // b is an AI major: the bonus of the difficulty is a percent of what the cities give, and the weariness takes its share of the sum
        val difficulty = game.gameInfo.getDifficulty()
        val saved = difficulty.aiUnitSupplyBonusPercent
        try {
            difficulty.aiUnitSupplyBonusPercent = 50
            b.unitSupplyFromExpendedGreatPeople = 40 // a is already at war with b since setUp
            WarDamageAi.doTurn(b) // caches b's value, 650
            b.ai.diplomacy.memoryOf(a).warWeariness = 260
            val breakdown = b.stats.getUnitSupplyBreakdown()
            assertTrue("the supply tells the bonus from none", breakdown.afterEmpireSize * 150 / 100 != breakdown.afterEmpireSize)
            assertEquals("after the empire size x 150 / 100", breakdown.afterEmpireSize * 150 / 100, breakdown.beforeWarWeariness)
            assertEquals(13, breakdown.warWearinessPercent)
            assertEquals("then x 87 / 100", breakdown.beforeWarWeariness * 87 / 100, breakdown.total)
        } finally {
            difficulty.aiUnitSupplyBonusPercent = saved
        }
    }

    @Test
    fun `the supply before the weariness is shown while the weariness is taken`() {
        val n = before
        weary(b, 260)
        val breakdown = a.stats.getUnitSupplyBreakdown()
        assertEquals("the field is what the supply was before", n, breakdown.beforeWarWeariness)
        assertTrue("and the total is less", breakdown.total < breakdown.beforeWarWeariness)
    }

    @Test
    fun `the total is never below 0`() {
        a.unitSupplyFromExpendedGreatPeople = -1000
        weary(b, 2000)
        val breakdown = a.stats.getUnitSupplyBreakdown()
        assertEquals(0, breakdown.beforeWarWeariness)
        assertEquals(0, breakdown.total)
    }

    @Test
    fun `a city-state does not lose supply to a weariness`() {
        val cityState = game.addCiv(cityStateType = "Militaristic")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        cityState.unitSupplyFromExpendedGreatPeople = 40
        cityState.diplomacyFunctions.makeCivilizationsMeet(b)
        cityState.getDiplomacyManager(b)!!.declareWar()
        WarDamageAi.doTurn(cityState)
        cityState.ai.diplomacy.memoryOf(b).warWeariness = 260
        val breakdown = cityState.stats.getUnitSupplyBreakdown()
        assertEquals(0, breakdown.warWearinessPercent)
        assertEquals(breakdown.beforeWarWeariness, breakdown.total)
    }
}
