package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's DoUpdateWarTargets: the war approach within the conflict budget */
@RunWith(GdxTestRunner::class)
class WarTargetTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        c = civ(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** The scores of the last final pass towards [other] (war and guarded), and the war approach it chose */
    private fun wantWar(other: Civilization, war: Int, guarded: Int) {
        val values = IntArray(MajorCivApproach.entries.size)
        values[MajorCivApproach.War.ordinal] = war
        values[MajorCivApproach.Guarded.ordinal] = guarded
        a.ai.diplomacy.memoryOf(other).approachValues = ArrayList(values.toList())
        a.ai.diplomacy.setApproach(other, MajorCivApproach.War)
    }

    private fun update() = WarTargetAi.updateWarTargets(a, listOf(b, c), game.ruleset.ai)

    @Test
    fun `a war at the conflict limit leaves no war approach for anyone else`() {
        a.getDiplomacyManager(b)!!.declareWar()
        wantWar(c, war = 900, guarded = 300)
        update()
        assertEquals(MajorCivApproach.Guarded, a.ai.diplomacy.approachTowards(c))
    }

    @Test
    fun `with room for one war the highest war score gets it`() {
        wantWar(b, war = 500, guarded = 300)
        wantWar(c, war = 900, guarded = 300)
        update()
        assertEquals(MajorCivApproach.War, a.ai.diplomacy.approachTowards(c))
        assertEquals("over the budget: the best approach but war", MajorCivApproach.Guarded, a.ai.diplomacy.approachTowards(b))
    }
}
