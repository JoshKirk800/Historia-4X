package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  Vox Populi's CvDiplomacyAI::SelectBestApproachTowardsMajorCiv (Release-5.4.8), parts 2 (friendship), 3 (provocations) and 12 (zero-out
 *  conditions) as far as their inputs exist. Personalities are flat (every value 5, every approach bias 5) and the random part is 0.
 */
@RunWith(GdxTestRunner::class)
class ApproachFriendshipTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private val war = MajorCivApproach.War.ordinal
    private val hostile = MajorCivApproach.Hostile.ordinal
    private val deceptive = MajorCivApproach.Deceptive.ordinal
    private val guarded = MajorCivApproach.Guarded.ordinal
    private val afraid = MajorCivApproach.Afraid.ordinal
    private val friendly = MajorCivApproach.Friendly.ordinal

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        rules.defines["APPROACH_RANDOM_PERCENT"] = 0f
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(5, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        civ(HexCoord(-9, 0)) // a third major: two alone would be a duel (no friendly, no deceptive)
        game.gameInfo.turns = 50
        a.ai.diplomacy.memoryOf(b).turnMet = 1
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun weights() = ApproachAi.approachWeights(a, b, null, rules)

    /** The weights before and after the section of [approachWeights] whose stage name starts with [prefix] */
    private fun section(prefix: String): Pair<IntArray, IntArray> {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { weights() } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith(prefix) }
        return steps[i].second to steps[i + 1].second
    }
    private fun select(strategic: Boolean) = ApproachAi.selectApproach(a, b, strategic, listOf(b), rules, Random(0))
    private fun memory() = a.ai.diplomacy.memoryOf(b)

    @Test
    fun `a declaration of friendship adds the friendly bias times loyalty and ends every bad approach`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        val (before, after) = section("Declaration of friendship")
        assertEquals(5 * 5, after[friendly] - before[friendly]) // bias 5 x max(loyalty 5, 5)
        for (i in listOf(war, hostile, deceptive, guarded, afraid)) assertEquals(0, after[i])
    }

    @Test
    fun `a research agreement counts more for one going for a spaceship`() {
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.ResearchAgreement, 30)
        val (before, after) = section("Research agreement")
        assertEquals(5 * 3, after[friendly] - before[friendly])
        a.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        val (before2, after2) = section("Research agreement")
        assertEquals(5 * 5, after2[friendly] - before2[friendly])
    }

    @Test
    fun `denouncing them or being denounced raises war and ends friendly and deceptive`() {
        a.getDiplomacyManager(b)!!.denounce()
        val (before, after) = section("Denouncements")
        assertEquals(5 * 2, after[war] - before[war])
        assertEquals(5 * 2, after[hostile] - before[hostile])
        assertEquals(0, after[friendly])
        assertEquals(0, after[deceptive])
        b.getDiplomacyManager(a)!!.denounce() // and they denounced us: war and guarded
        val (before2, after2) = section("Denouncements")
        assertEquals(5 * 4, after2[war] - before2[war])
        assertEquals(5 * 2, after2[guarded] - before2[guarded])
    }

    @Test
    fun `they hold our capital and we want it back`() {
        a.cities.first().moveToCiv(b)
        game.addCity(a, game.getTile(HexCoord(-5, 0))) // a lives on
        val (before, after) = section("Vengeance")
        for (i in listOf(war, hostile, guarded)) assertEquals(5 * 10, after[i] - before[i])
        assertEquals(0, after[friendly])
        assertEquals(0, after[deceptive])
    }

    @Test
    fun `after a recent peace treaty war and hostile are zero in the final pass, and count as zero last turn`() {
        val diplomacy = a.getDiplomacyManager(b)!!
        diplomacy.declareWar()
        diplomacy.makePeace()
        memory().numWarsFought = 1
        memory().turnMadePeace = game.gameInfo.turns - 1
        memory().opinionWeight = 60 // they are disliked: without the treaty war would score
        memory().approachValues = arrayListOf(9000, 9000, 0, 0, 0, 0, 0)
        select(strategic = false)
        assertEquals("no curve from last turn's war", 0, memory().approachValues[war])
        assertEquals(0, memory().approachValues[hostile])
    }

    @Test
    fun `no friendship with one we denounced, whatever the scores`() {
        a.getDiplomacyManager(b)!!.denounce()
        memory().opinionWeight = -300 // liked a lot all the same
        memory().approachValues.clear()
        val approach = select(strategic = false)
        assertTrue("$approach", approach != MajorCivApproach.Friendly && approach != MajorCivApproach.Deceptive)
    }
}
