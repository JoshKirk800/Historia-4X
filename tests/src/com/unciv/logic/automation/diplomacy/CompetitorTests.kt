package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
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
}
