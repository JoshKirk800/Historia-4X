package com.unciv.logic.automation.diplomacy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvDiplomacyAI::DoUpdateLandDisputeLevels and CvCity::GetContestedPlotScore (Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class LandDisputeTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var ourCity: City
    private lateinit var theirCity: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10, Constants.grassland)
        a = civ()
        b = civ()
        ourCity = game.addCity(a, game.getTile(HexCoord(0, 0)))
        theirCity = game.addCity(b, game.getTile(HexCoord(6, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.proximity[b.civID] = Proximity.Close
    }

    /** Boldness 5, expansion 5, no trait, no grand strategy or pursuit: not bold, so in the Ancient era the score counts double */
    private fun civ(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        civ.ai.setGrandStrategyAndPursuit(null)
        return civ
    }

    private fun theyTake(x: Int, y: Int) = theirCity.expansion.takeOwnership(game.getTile(HexCoord(x, y)))

    private fun level(): DisputeLevel {
        DisputeAi.updateLandDisputes(a, listOf(b), game.ruleset.ai)
        return a.ai.diplomacy.memoryOf(b).landDispute
    }

    @Test
    fun `their tiles in our first ring weigh 3, the second 2, beyond 1, and only tiles that yield`() {
        theyTake(3, 0)
        assertEquals(1, DisputeAi.contestedPlotScore(ourCity, b))
        theyTake(2, 0)
        assertEquals(3, DisputeAi.contestedPlotScore(ourCity, b))
        theyTake(1, 0)
        assertEquals(6, DisputeAi.contestedPlotScore(ourCity, b))
        game.setTileTerrain(HexCoord(1, 0), Constants.mountain)
        assertEquals("a mountain yields nothing", 3, DisputeAi.contestedPlotScore(ourCity, b))
    }

    @Test
    fun `in the Ancient era the contested score doubles to weak, strong and fierce`() {
        assertEquals(DisputeLevel.None, level())
        theyTake(3, 0)
        assertEquals("1 x 2", DisputeLevel.Weak, level())
        theyTake(1, 0)
        assertEquals("4 x 2", DisputeLevel.Fierce, level())
        a.proximity[b.civID] = Proximity.Far
        assertEquals("far players have no land dispute", DisputeLevel.None, level())
    }

    @Test
    fun `a bold leader counts contested land half again more`() {
        a.tech.addTechnology("Civil Service") // the Medieval era: +25%
        theyTake(1, 0)
        theyTake(2, 0)
        assertEquals("5 x 1.25", DisputeLevel.Strong, level())
        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 8)
        assertEquals("5 x 1.75", DisputeLevel.Fierce, level())
    }
}
