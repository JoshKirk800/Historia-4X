package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    //region Potential war targets

    private fun potential(other: Civilization) = other.civID in a.ai.diplomacy.potentialWarTargets

    /** [a] sees the whole map, with the others close, so that it has attack targets on their cities, and a neutral opinion and approach (first contacts are friendly) */
    private fun seeTargets() {
        for (other in listOf(b, c)) {
            a.proximity[other.civID] = Proximity.Close
            a.ai.diplomacy.memoryOf(other).opinion = MajorCivOpinion.Neutral
            prefer(other, MajorCivApproach.Neutral)
        }
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        a.ai.military.updateAttackTargets(a)
    }

    /** The last final pass found [approach] the best towards [other] */
    private fun prefer(other: Civilization, approach: MajorCivApproach) {
        val values = IntArray(MajorCivApproach.entries.size)
        values[approach.ordinal] = 500
        a.ai.diplomacy.memoryOf(other).approachValues = ArrayList(values.toList())
    }

    @Test
    fun `a major with an attack target and nothing to spare it for is a potential war target`() {
        update()
        assertFalse("no attack target seen yet", potential(b))
        seeTargets()
        assertTrue(a.ai.military.havePossibleAttackTarget(b))
        update()
        assertTrue(potential(b))
        assertTrue(potential(c))
    }

    @Test
    fun `a war at the conflict budget leaves no other potential target, and none is one we are at war with`() {
        seeTargets()
        a.getDiplomacyManager(b)!!.declareWar()
        update()
        assertFalse(potential(b))
        assertFalse("the war uses the budget of 10 of the first eras", potential(c))
    }

    @Test
    fun `with no new wars only the target already chosen is potential`() {
        seeTargets()
        a.ai.warStates.noNewWars = true
        update()
        assertFalse(potential(b))
        assertFalse(potential(c))
        a.ai.warStates.noNewWars = false
        wantWar(c, war = 900, guarded = 300)
        update()
        assertTrue("chosen for war", potential(c))
        assertFalse("the war uses the budget", potential(b))
    }

    @Test
    fun `a friend, a feared player, a recent peace partner and an immense one are spared, a friend we cannot trust is not`() {
        seeTargets()
        val memory = a.ai.diplomacy.memoryOf(b)
        prefer(b, MajorCivApproach.Friendly)
        update()
        assertFalse("friendly", potential(b))
        prefer(b, MajorCivApproach.Afraid)
        update()
        assertFalse("afraid", potential(b))
        prefer(b, MajorCivApproach.Neutral)
        memory.opinion = MajorCivOpinion.Friend
        update()
        assertFalse("a friend in opinion", potential(b))
        memory.opinion = MajorCivOpinion.Neutral
        a.ai.diplomacy.mostValuableFriend = b.civID
        update()
        assertFalse("our most valuable friend", potential(b))
        a.ai.diplomacy.mostValuableFriend = null
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Immense
        update()
        assertFalse("immense", potential(b))
        a.ai.warStates.militaryStrengths.remove(b.civID)
        memory.numWarsFought = 1
        memory.turnMadePeace = game.gameInfo.turns
        update()
        assertFalse("a recent peace", potential(b))
        memory.numWarsFought = 0
        update()
        assertTrue(potential(b))

        memory.opinion = MajorCivOpinion.Friend
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true // b declared war on a friend of c's: untrustworthy
        update()
        assertTrue("an untrustworthy friend is spared by no one", potential(b))
    }

    //endregion
}
