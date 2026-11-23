package com.unciv.logic.civilization

import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.managers.quests.CityStateQuestSchedule
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  When city-states give quests as Vox Populi has it with [ModConstants.cityStateQuestsLikeVp]: `GetFirstPossibleTurnForPersonalQuests` (V:CvMinorCivAI.cpp:6226-6242),
 *  `DoTestSeedQuestCountdownForPlayer` and `DoTestSeedGlobalQuestCountdown` (7566-7660), `GetQuestWeight` (7421), `IsEnabledQuest` (6671). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
@RunWith(BaseTestRunner::class)
class CityStateQuestScheduleTests {
    private val constants = ModConstants.CityStateQuestConstants()

    @Test
    fun `the first possible turn is 4 times the square root of the speed over 10, cut off`() {
        assertEquals("Standard: sqrt(100) = 10", 4, CityStateQuestSchedule.firstPossibleTurn(constants, 1f))
        assertEquals("Marathon: sqrt(300) = 17, 4 x 17 / 10 = 6.8", 6, CityStateQuestSchedule.firstPossibleTurn(constants, 3f))
        assertEquals("Quick: sqrt(67) = 8, 4 x 8 / 10 = 3.2", 3, CityStateQuestSchedule.firstPossibleTurn(constants, 0.67f))
    }

    @Test
    fun `the first countdown is 0 with no random turns, and 0 to the random turns inclusive otherwise`() {
        assertEquals(0, CityStateQuestSchedule.countdown(constants, global = false, firstTime = true, hostile = false, speedModifier = 1f, random = Random(1)))
        val wide = constants.copy(firstTurnRandom = 20)
        val draws = (0 until 400).map { CityStateQuestSchedule.countdown(wide, global = true, firstTime = true, hostile = false, speedModifier = 1f, random = Random(it)) }
        assertTrue(draws.all { it in 0..20 })
        assertTrue("the end is reachable", 20 in draws)
    }

    @Test
    fun `later countdowns are the minimum and up to the random turns less one, a hostile city-state twice the span, then the speed`() {
        fun draws(global: Boolean, hostile: Boolean, speed: Float) = (0 until 600).map {
            CityStateQuestSchedule.countdown(constants, global, firstTime = false, hostile = hostile, speedModifier = speed, random = Random(it))
        }
        assertEquals(10..29, draws(global = false, hostile = false, speed = 1f).let { it.min()..it.max() })
        assertEquals("a hostile city-state: 10 + 0..39", 10..49, draws(global = false, hostile = true, speed = 1f).let { it.min()..it.max() })
        assertEquals(25..44, draws(global = true, hostile = false, speed = 1f).let { it.min()..it.max() })
        assertEquals("Marathon: x 3", 30..87, draws(global = false, hostile = false, speed = 3f).let { it.min()..it.max() })
    }

    // region the game

    private val game = TestGame()
    private val cityState: Civilization = game.addCiv(cityStateType = "Mercantile")
    private val major: Civilization = game.addCiv()
    private val era get() = major.getEra().name

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        game.addCity(cityState, game.getTile(HexCoord(3, 0)))
        game.addCity(major, game.getTile(HexCoord(-3, 0)))
        cityState.cityStatePersonality = CityStatePersonality.Neutral
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        game.ruleset.modOptions.constants.cityStateQuestsLikeVp = true
    }

    /** The rulesets are cloned per game but the quests in them are shared: put back what the tests changed */
    @After
    fun tearDown() {
        game.ruleset.quests["Route"]!!.apply { weightByEra.clear(); enabled = true }
    }

    private fun giveWeight(eraName: String, key: String = "Mercantile|Neutral", weight: Int = 7) {
        game.ruleset.quests["Route"]!!.weightByEra[eraName] = hashMapOf(key to weight)
    }

    private fun quests() = cityState.questManager.getAssignedQuestsFor(major).map { it.questName }.toList()

    @Test
    fun `at the first possible turn a quest with weight in the era is given`() {
        giveWeight(era)
        game.gameInfo.turns = 4
        cityState.questManager.endTurn()
        assertEquals(listOf("Route"), quests())
    }

    @Test
    fun `before the first possible turn nothing is given`() {
        giveWeight(era)
        game.gameInfo.turns = 3
        cityState.questManager.endTurn()
        assertTrue(quests().isEmpty())
    }

    @Test
    fun `a quest with weight 0 in the era, for the type or personality, or that is disabled is not given`() {
        giveWeight("Medieval era")
        game.gameInfo.turns = 4
        cityState.questManager.endTurn()
        assertTrue("another era", quests().isEmpty())

        giveWeight(era, key = "Cultured|Neutral")
        cityState.questManager.endTurn()
        assertTrue("another type", quests().isEmpty())

        giveWeight(era)
        game.ruleset.quests["Route"]!!.enabled = false
        cityState.questManager.endTurn()
        assertTrue("disabled", quests().isEmpty())
    }

    @Test
    fun `without the switch the first quest waits for turn 30 and the old weights`() {
        game.ruleset.modOptions.constants.cityStateQuestsLikeVp = false
        giveWeight(era)
        game.gameInfo.turns = 4
        cityState.questManager.endTurn()
        assertTrue(quests().isEmpty())
    }

    // endregion
}
