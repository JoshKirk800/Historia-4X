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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SelectBestApproachTowardsMajorCiv part 4, KEY PLAYERS (Vox Populi, Release-5.4.8): what each competitor term adds on its own */
@RunWith(GdxTestRunner::class)
class KeyPlayersTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    /** Four majors (below four, VP makes every major a major competitor), far apart, all values 5 */
    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        rules = game.ruleset.ai
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(12, 0))
        c = civ(HexCoord(-12, 0))
        civ(HexCoord(0, 12))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
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

    /** What part 4 adds towards [other], by approach */
    private fun keyPlayers(other: Civilization, strategic: Boolean = false): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
        try { ApproachAi.approachWeights(a, other, null, rules, strategic) } finally { ApproachAi.debugSink = null }
        val i = steps.indexOfFirst { it.first.startsWith("Key players") }
        return IntArray(steps[i].second.size) { steps[i + 1].second[it] - steps[i].second[it] }
    }

    @Test
    fun `our biggest competitor after the Classical era draws war, hostility, guard and deception, in the final pass only`() {
        a.tech.addTechnology("Chivalry") // Medieval era
        a.ai.diplomacy.biggestCompetitor = b.civID
        val added = keyPlayers(b)
        for (approach in listOf(MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Guarded, MajorCivApproach.Deceptive))
            assertEquals(approach.name, 5 * 5, added[approach.ordinal])
        assertTrue(keyPlayers(b, strategic = true).all { it == 0 })
    }

    @Test
    fun `a player at war with our biggest competitor is our friend`() {
        a.ai.diplomacy.biggestCompetitor = b.civID
        c.getDiplomacyManager(b)!!.declareWar()
        // friendly bias 5 x WorkAgainstWillingness 5 / 2
        assertEquals(12, keyPlayers(c)[MajorCivApproach.Friendly.ordinal])
    }

    @Test
    fun `a major competitor draws deception and guard`() {
        a.ai.diplomacy.memoryOf(b).majorCompetitor = true
        val added = keyPlayers(b)
        assertEquals(5 * 3, added[MajorCivApproach.Deceptive.ordinal])
        assertEquals(5 * 2, added[MajorCivApproach.Guarded.ordinal])
        assertEquals(0, added[MajorCivApproach.War.ordinal])
    }

    @Test
    fun `one who denounced or fights our most valuable friend draws war, more while we are friends`() {
        b.getDiplomacyManager(c)!!.denounce()
        val base = keyPlayers(b)
        a.ai.diplomacy.mostValuableFriend = c.civID
        val notFriends = keyPlayers(b)
        // WorkWithWillingness 5 x bias 5 / 8
        assertEquals(3, notFriends[MajorCivApproach.War.ordinal] - base[MajorCivApproach.War.ordinal])
        assertEquals(3, notFriends[MajorCivApproach.Deceptive.ordinal] - base[MajorCivApproach.Deceptive.ordinal])
        a.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        val friends = keyPlayers(b)
        assertEquals("/ 4 while friends", 6, friends[MajorCivApproach.War.ordinal] - base[MajorCivApproach.War.ordinal])
        assertEquals(6, friends[MajorCivApproach.Hostile.ordinal] - base[MajorCivApproach.Hostile.ordinal])
    }

    @Test
    fun `our most valuable friend draws friendship, all of it while we are friends`() {
        a.ai.diplomacy.mostValuableFriend = b.civID
        val notFriends = keyPlayers(b)
        assertEquals("5 x bias", 25, notFriends[MajorCivApproach.Friendly.ordinal])
        assertTrue("war halved", notFriends[MajorCivApproach.War.ordinal] < 0)
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        val friends = keyPlayers(b)
        assertEquals("10 x bias", 50, friends[MajorCivApproach.Friendly.ordinal])
    }
}
