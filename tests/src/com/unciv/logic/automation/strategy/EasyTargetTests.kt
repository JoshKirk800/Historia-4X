package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvDiplomacyAI::DoUpdateEasyTargets (Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class EasyTargetTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    /** Three majors; a and b close to each other */
    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = game.addCiv()
        b = game.addCiv()
        val c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(6, 0)))
        game.addCity(c, game.getTile(HexCoord(-12, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        // Neighbours (the map-size rule of Civilization.updateProximity would call two cities 6 tiles apart on this map farther)
        a.proximity[b.civID] = Proximity.Neighbors
        b.proximity[a.civID] = Proximity.Neighbors
    }

    private fun update() = WarStateAi.updateEasyTargets(a, a.ai.warStates, game.ruleset.ai)

    @Test
    fun `nobody we are at peace with is an easy target, as in VP's code`() {
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Pathetic
        update()
        assertFalse(WarStateAi.isEasyTarget(a, b))
    }

    @Test
    fun `a war nearly won makes them an easy target`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.warStates.warStates[b.civID] = WarState.NearlyWon
        update()
        assertTrue(WarStateAi.isEasyTarget(a, b))
    }

    @Test
    fun `a pathetic army in a stalemate makes them an easy target, a defensive war never`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Pathetic
        a.ai.warStates.rawMilitaryStrengths[b.civID] = StrengthLevel.Pathetic // held back (no new wars or deterrents), the raw values count
        a.ai.warStates.warStates[b.civID] = WarState.Stalemate
        a.ai.warStates.targetValues[b.civID] = TargetValue.Average // not a bad target, which VP's table rejects first
        a.ai.warStates.rawTargetValues[b.civID] = TargetValue.Average
        update()
        assertTrue(WarStateAi.isEasyTarget(a, b))
        a.ai.warStates.warStates[b.civID] = WarState.Defensive
        update()
        assertFalse(WarStateAi.isEasyTarget(a, b))
    }
}
