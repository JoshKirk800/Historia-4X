package com.unciv.logic.civilization

import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.quests.CityStateQuestRewards
import com.unciv.logic.civilization.managers.quests.QuestRewardKind
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

/**
 *  The rewards of city-state quests as Vox Populi has them with [ModConstants.cityStateQuestsLikeVp]: `CvMinorCivQuest::CalculateRewards` (V:CvMinorCivAI.cpp:329-895), `DoRewards`
 *  (896-1067), `GetQuestRewardModifier` (4949-4975). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The numbers are worked out by hand from the defines.
 */
@RunWith(BaseTestRunner::class)
class CityStateQuestRewardsTests {
    private val constants = ModConstants.CityStateQuestConstants()

    @Test
    fun `the era scaler grows by half of 100 for each era after the first, with the era numbered from 0`() {
        assertEquals(listOf(100, 100, 150, 200, 250), (0..4).map { CityStateQuestRewards.eraScalerPercent(constants, it) })
    }

    @Test
    fun `the modifier adds policies, pledge and personality, then the religion, and never goes below 0`() {
        fun modifier(increased: Int = 0, pledged: Boolean = false, personality: CityStatePersonality? = null, religion: Int = 0) =
            CityStateQuestRewards.modifierPercent(constants, increased, pledged, personality, religion)
        assertEquals(100, modifier())
        assertEquals(190, modifier(50, true, CityStatePersonality.Friendly))
        assertEquals(75, modifier(personality = CityStatePersonality.Hostile))
        assertEquals(0, modifier(increased = -200))
        assertEquals("(100 + 15) x 110 / 100", 126, modifier(pledged = true, religion = 10))
    }

    @Test
    fun `a Mercantile city-state pays 150 percent of the gold, a Maritime 125, cut off after each step`() {
        fun gold(type: String) = CityStateQuestRewards.calculate(constants, mapOf(QuestRewardKind.Gold to 100), 2, 0, 100, 100, type, "Friendly")[QuestRewardKind.Gold]
        assertEquals("100 x 150 / 100 = 150, x 150 / 100", 225, gold("Mercantile"))
        assertEquals("150 x 125 / 100 = 187.5", 187, gold("Maritime"))
        assertEquals(150, gold("Cultured"))
    }

    @Test
    fun `the type multiplier comes before the personality multiplier`() {
        // era 3: 50 x 200 / 100 = 100, x 150 / 100 = 150, x 80 / 100 = 120
        val culture = CityStateQuestRewards.calculate(constants, mapOf(QuestRewardKind.Culture to 50), 3, 0, 100, 100, "Cultured", "Neutral")
        assertEquals(120, culture[QuestRewardKind.Culture])
    }

    @Test
    fun `the speed scales the stats but not the influence, great person points or experience`() {
        val base = mapOf(QuestRewardKind.Influence to 40, QuestRewardKind.Gold to 10, QuestRewardKind.GreatPersonPoints to 10, QuestRewardKind.Experience to 10)
        val marathon = CityStateQuestRewards.calculate(constants, base, 0, 0, 100, 300, "", "Friendly")
        assertEquals(40, marathon[QuestRewardKind.Influence])
        assertEquals(30, marathon[QuestRewardKind.Gold])
        assertEquals(10, marathon[QuestRewardKind.GreatPersonPoints])
        assertEquals(10, marathon[QuestRewardKind.Experience])
    }

    @Test
    fun `happiness is the base and half the era, experience takes no era and the random share, juggernauts are the base`() {
        val base = mapOf(QuestRewardKind.Happiness to 1, QuestRewardKind.Experience to 10, QuestRewardKind.Juggernauts to 2, QuestRewardKind.Faith to 0)
        val rewards = CityStateQuestRewards.calculate(constants, base, 5, 4, 125, 100, "Militaristic", "Friendly")
        assertEquals("1 + 5 / 2", 3, rewards[QuestRewardKind.Happiness])
        assertEquals("(10 + 4) x 125 / 100 = 17, x 125 / 100 for Militaristic", 21, rewards[QuestRewardKind.Experience])
        assertEquals(2, rewards[QuestRewardKind.Juggernauts])
        assertTrue("a base of 0 is no reward", QuestRewardKind.Faith !in rewards)
    }

    @Test
    fun `a recalculation only raises, a partial quest halves all but the juggernauts`() {
        val old = mapOf(QuestRewardKind.Gold to 90, QuestRewardKind.Culture to 30)
        val new = mapOf(QuestRewardKind.Gold to 80, QuestRewardKind.Culture to 35, QuestRewardKind.Food to 5)
        assertEquals(mapOf(QuestRewardKind.Gold to 90, QuestRewardKind.Culture to 35, QuestRewardKind.Food to 5), CityStateQuestRewards.recalculated(old, new))
        assertEquals(mapOf(QuestRewardKind.Gold to 45, QuestRewardKind.Juggernauts to 2),
            CityStateQuestRewards.halved(mapOf(QuestRewardKind.Gold to 91, QuestRewardKind.Juggernauts to 2)))
    }

    // region the game

    private val game = TestGame()
    private val cityState: Civilization = game.addCiv(cityStateType = "Mercantile")
    private val major: Civilization = game.addCiv("City-State quest rewards are increased by [50]%")
    private val plain: Civilization = game.addCiv()

    private var originalInfluence = 0f

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        game.addCity(cityState, game.getTile(HexCoord(3, 0)))
        game.addCity(major, game.getTile(HexCoord(-3, 0)))
        game.addCity(plain, game.getTile(HexCoord(0, -3)))
        cityState.cityStatePersonality = CityStatePersonality.Neutral
        for (civ in listOf(major, plain)) civ.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val quest = game.ruleset.quests["Route"]!!
        originalInfluence = quest.influence
        quest.influence = 40f
        quest.gold = 60
        quest.culture = 20
        game.ruleset.modOptions.constants.cityStateQuestsLikeVp = true
    }

    /** The rulesets are cloned per game but the quests in them are shared: put back what the tests changed */
    @After
    fun tearDown() {
        game.ruleset.quests["Route"]!!.apply { gold = 0; culture = 0; influence = originalInfluence }
    }

    private fun assign(civ: Civilization) =
        cityState.questManager.run { assignNewQuest(game.ruleset.quests["Route"]!!, listOf(civ)); getAssignedQuestsFor(civ).single() }

    @Test
    fun `a quest freezes its rewards when it is assigned`() {
        val assigned = assign(plain)
        // Mercantile gold x 150 / 100; Neutral culture x 80 / 100; the standard speed is 100 percent
        assertEquals(40f, assigned.getInfluence(), 0.001f)
        assertEquals(mapOf(QuestRewardKind.Influence to 40, QuestRewardKind.Gold to 90, QuestRewardKind.Culture to 16), assigned.getRewards())
    }

    @Test
    fun `the policy of the civilization raises every reward`() {
        val assigned = assign(major)
        assertEquals(60f, assigned.getInfluence(), 0.001f)
        // gold 60 x 150 / 100 = 90, x 150 / 100
        assertEquals(135, assigned.getRewards()[QuestRewardKind.Gold])
    }

    @Test
    fun `a pledge to protect adds 15 percent to the rewards already assigned and ending it takes none away`() {
        val assigned = assign(plain)
        cityState.getDiplomacyManager(plain)!!.setInfluence(10f)
        cityState.cityStateFunctions.addProtectorCiv(plain)
        assertEquals(DiplomaticStatus.Protector, cityState.getDiplomacyManager(plain)!!.diplomaticStatus)
        // influence 40 x 115 / 100 = 46; gold (60 x 115 / 100 = 69) x 150 / 100 = 103
        assertEquals(46f, assigned.getInfluence(), 0.001f)
        assertEquals(103, assigned.getRewards()[QuestRewardKind.Gold])
        cityState.cityStateFunctions.removeProtectorCiv(plain, forced = true)
        assertEquals(103, assigned.getRewards()[QuestRewardKind.Gold])
    }

    @Test
    fun `paying the rewards early gives all but the influence and ends the personal quests`() {
        assign(plain)
        val goldBefore = plain.gold
        val cultureBefore = plain.policies.storedCulture
        cityState.questManager.payRewardsEarly(plain)
        assertEquals(goldBefore + 90, plain.gold)
        assertEquals(cultureBefore + 16, plain.policies.storedCulture)
        assertEquals(0f, cityState.getDiplomacyManager(plain)!!.getInfluence(), 0.001f)
        assertTrue(cityState.questManager.getAssignedQuestsFor(plain).none())
    }

    @Test
    fun `a heavy tribute gives gold and the early rewards, no worker`() {
        assign(plain)
        val goldBefore = plain.gold
        val unitsBefore = plain.units.getCivUnits().count()
        game.ruleset.modOptions.constants.cityStateBullyLikeVp = true
        assertTrue(cityState.cityStateFunctions.heavyTributeLikeVp())
        val tribute = cityState.cityStateFunctions.goldGainedByTribute(plain)
        cityState.cityStateFunctions.tributeWorker(plain)
        assertEquals(goldBefore + tribute + 90, plain.gold)
        assertEquals(unitsBefore, plain.units.getCivUnits().count())
        assertTrue(cityState.questManager.getAssignedQuestsFor(plain).none())
    }

    @Test
    fun `without the switch a quest has the influence alone`() {
        game.ruleset.modOptions.constants.cityStateQuestsLikeVp = false
        val assigned = assign(plain)
        assertTrue(assigned.rewards.isEmpty())
        assertEquals(40f, assigned.getInfluence(), 0.001f)
        assertEquals(false, cityState.cityStateFunctions.heavyTributeLikeVp())
    }

    // endregion
}
