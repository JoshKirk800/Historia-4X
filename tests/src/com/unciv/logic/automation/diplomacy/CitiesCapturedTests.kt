package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's count of the cities a player captured from us (CvPlayer::acquireCity: CvDiplomacyAI::ChangeNumCitiesCapturedBy for the
 *  loser, SetLastCityCaptureTurn for the conqueror) and its use in the liberation term of SelectBestApproachTowardsMajorCiv part 2.
 */
@RunWith(GdxTestRunner::class)
class CitiesCapturedTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(6, 0))
        civ(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        game.gameInfo.turns = 40
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    @Test
    fun `the loser counts the cities the conqueror took, the conqueror remembers when`() {
        val city = game.addCity(a, game.getTile(HexCoord(0, 3)))
        DiplomacyEvents.onCityConquered(b, a, city)
        assertEquals(1, a.ai.diplomacy.memoryOf(b).numCitiesCapturedBy)
        assertEquals(40, b.ai.diplomacy.memoryOf(a).lastCityCaptureTurn)
        assertEquals("only the loser counts", 0, b.ai.diplomacy.memoryOf(a).numCitiesCapturedBy)
    }

    @Test
    fun `liberating cities earns friendship only beyond the cities they captured from us`() {
        val memory = a.ai.diplomacy.memoryOf(b)
        memory.numCitiesLiberated = 3
        memory.numCitiesCapturedBy = 2
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, null, rules) } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith("Liberated cities") }
        val friendly = MajorCivApproach.Friendly.ordinal
        // liberated 3 less max(0 of ours they own, 2 captured) = 1: friendly + bias x 1 x 2
        assertEquals(5 * 1 * 2, steps[i + 1].second[friendly] - steps[i].second[friendly])
    }
}
