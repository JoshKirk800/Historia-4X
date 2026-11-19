package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvDiplomacyAI::DoUpdateMajorCompetitors and IsEarlyGameCompetitor (Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class CompetitorTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    /** Four majors: VP's danger threshold (a third of the majors ever, rounded down) is 0 below four, making every major a competitor */
    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        d = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(10, 0)))
        game.addCity(c, game.getTile(HexCoord(-10, 0)))
        game.addCity(d, game.getTile(HexCoord(0, 10)))
        for (other in listOf(b, c, d)) a.diplomacyFunctions.makeCivilizationsMeet(other)
    }

    private fun update() = CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)

    @Test
    fun `a far major with no quarrel is no competitor`() {
        update()
        assertFalse(CompetitorAi.isMajorCompetitor(a, b))
    }

    @Test
    fun `the last other major alive is a competitor`() {
        c.cities.first().destroyCity(overrideSafeties = true)
        d.cities.first().destroyCity(overrideSafeties = true)
        update()
        assertTrue(CompetitorAi.isMajorCompetitor(a, b))
    }

    @Test
    fun `an unforgivable major is a competitor, unless it lost its capital`() {
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Unforgivable
        update()
        assertTrue(CompetitorAi.isMajorCompetitor(a, b))
        b.cities.first().moveToCiv(c)
        update()
        assertFalse(CompetitorAi.isMajorCompetitor(a, b))
    }

    @Test
    fun `a denunciation makes an early-game competitor, until the Renaissance`() {
        assertFalse(CompetitorAi.isEarlyGameCompetitor(a, b, rules))
        b.getDiplomacyManager(a)!!.denounce()
        assertTrue(CompetitorAi.isEarlyGameCompetitor(a, b, rules))
        a.tech.addTechnology("Printing Press") // Renaissance era (3)
        assertFalse(CompetitorAi.isEarlyGameCompetitor(a, b, rules))
    }

    @Test
    fun `a close easy target spamming wonders competes early with a conqueror or cultural leader, not with a scientist`() {
        a.proximity[b.civID] = Proximity.Close
        a.ai.warStates.easyTargets.add(b.civID)
        a.ai.diplomacy.memoryOf(b).wonderSpammer = true
        a.ai.secondaryVictoryPursuit = null
        a.ai.primaryVictoryPursuit = VictoryPursuit.Science
        assertFalse("scientist", CompetitorAi.isEarlyGameCompetitor(a, b, rules))
        a.ai.primaryVictoryPursuit = VictoryPursuit.Culture
        assertTrue("cultural", CompetitorAi.isEarlyGameCompetitor(a, b, rules))
        a.ai.primaryVictoryPursuit = VictoryPursuit.Domination
        assertTrue("conqueror", CompetitorAi.isEarlyGameCompetitor(a, b, rules))
        a.proximity[b.civID] = Proximity.Far
        assertFalse("far", CompetitorAi.isEarlyGameCompetitor(a, b, rules))
    }

    private fun biggest(): String? {
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        CompetitorAi.updateBiggestCompetitor(a, listOf(b, c, d), rules)
        return CompetitorAi.biggestCompetitor(a)
    }

    /** Strategic approach values by MajorCivApproach ordinal: [war] for war, nothing else */
    private fun strategic(other: Civilization, approach: MajorCivApproach, war: Int) {
        val memory = a.ai.diplomacy.memoryOf(other)
        memory.strategicApproach = approach
        memory.strategicApproachValues = ArrayList(MajorCivApproach.entries.map { if (it == MajorCivApproach.War) war else 0 })
    }

    @Test
    fun `without a reason, the biggest competitor is the highest strategic war value that is not friendly`() {
        strategic(b, MajorCivApproach.Neutral, 300)
        strategic(c, MajorCivApproach.Friendly, 900)
        strategic(d, MajorCivApproach.Neutral, 100)
        assertEquals(b.civID, biggest())
    }

    @Test
    fun `the holder of our capital is the biggest competitor whatever the approach values`() {
        strategic(b, MajorCivApproach.Neutral, 900)
        strategic(c, MajorCivApproach.Neutral, 100)
        strategic(d, MajorCivApproach.Neutral, 100)
        game.addCity(a, game.getTile(HexCoord(3, 3)))
        a.cities.first { it.isOriginalCapital }.moveToCiv(c)
        assertEquals(c.civID, biggest())
    }
}
