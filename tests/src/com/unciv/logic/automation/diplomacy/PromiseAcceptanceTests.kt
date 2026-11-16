package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's answers to promise requests: IsDontSettleAcceptable, IsStopSpyingAcceptable, IsStopDiggingAcceptable */
@RunWith(GdxTestRunner::class)
class PromiseAcceptanceTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(10, 0))
        civ(HexCoord(-10, 0))
        civ(HexCoord(0, 10))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        game.gameInfo.turns = 60
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Average
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    @Test
    fun `spying stops for those we do not guard against and who never robbed us`() {
        assertTrue(PromiseAi.isStopSpyingAcceptable(a, b))
        a.ai.diplomacy.memoryOf(b).numTimesRobbed = 1
        assertFalse("robbed", PromiseAi.isStopSpyingAcceptable(a, b))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        assertTrue("afraid: always", PromiseAi.isStopSpyingAcceptable(a, b))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Guarded)
        a.ai.diplomacy.memoryOf(b).numTimesRobbed = 0
        assertFalse("guarded", PromiseAi.isStopSpyingAcceptable(a, b))
    }

    @Test
    fun `settling stays away when our boldness x 10 is below the threshold`() {
        // (DONT_SETTLE_FLAVOR_BASE 8 - expansion 5) x 5 = 15, an average strength adds 0
        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 1)
        assertTrue(PromiseAi.isDontSettleAcceptable(a, b))
        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 2)
        assertFalse(PromiseAi.isDontSettleAcceptable(a, b))
        a.ai.diplomacy.memoryOf(b).brokenExpansionPromiseValue = 100
        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 1)
        assertFalse("a broken expansion promise", PromiseAi.isDontSettleAcceptable(a, b))
    }

    @Test
    fun `digging stops below a culture flavor of 7`() {
        a.ai.setPersonalityFlavor(Flavor.Culture, 6)
        assertTrue(PromiseAi.isStopDiggingAcceptable(a, b))
        a.ai.setPersonalityFlavor(Flavor.Culture, 7)
        assertFalse(PromiseAi.isStopDiggingAcceptable(a, b))
    }
}
