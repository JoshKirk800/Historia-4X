package com.unciv.logic.automation.diplomacy

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's TestUntrustworthyFriends / TestOnePlayerUntrustworthyFriend (CvDiplomacyAI.cpp, Release-5.4.8): whom a, the observer,
 *  holds for a backstabber. Four majors alive (danger: half of the majors ever dead, or the betrayer conqueror of a third of them, is
 *  2 and 1 here: neither holds), every personality value 5 (loyalty 5: tolerance 3 broken agreements, 1 denounced friend, 0 wars on friends).
 */
@RunWith(GdxTestRunner::class)
class UntrustworthyTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        rules = game.ruleset.ai
        game.gameInfo.turns = 100
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(10, 0))
        c = civ(HexCoord(-10, 0))
        d = civ(HexCoord(0, 10))
        for (x in listOf(a, b, c, d)) for (y in listOf(a, b, c, d)) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        civ.ai.setGrandStrategyAndPursuit(null)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun memory(of: Civilization) = a.ai.diplomacy.memoryOf(of)

    private fun verdict(observer: Civilization = a, other: Civilization = b): Boolean {
        UntrustworthyAi.testUntrustworthyFriends(observer, rules)
        return OpinionAi.isUntrustworthyFriend(observer, other)
    }

    private fun friends(x: Civilization, y: Civilization) {
        x.getDiplomacyManager(y)!!.signDeclarationOfFriendship()
    }

    @Test
    fun `nobody is untrustworthy until they do something`() {
        assertFalse(verdict())
        assertFalse(verdict(other = c))
    }

    @Test
    fun `a player that took or holds our capital or holy city is, unless it resurrected us and took neither`() {
        memory(b).capturedCapital = true
        assertTrue(verdict())
        memory(b).capturedCapital = false
        memory(b).capturedHolyCity = true
        assertTrue(verdict())
        memory(b).resurrectedUs = true
        assertTrue("resurrected us, but did take our holy city", verdict())
        memory(b).capturedHolyCity = false
        assertFalse("resurrected us and took nothing", verdict())
    }

    @Test
    fun `a player that nuked us is`() {
        memory(b).numTimesNuked = 1
        assertTrue(verdict())
    }

    @Test
    fun `a player that betrayed us personally is, whatever the kind of betrayal`() {
        val betrayals = listOf<(MajorCivMemory) -> Unit>(
            { it.friendDenouncedUs = true }, { it.friendDeclaredWarOnUs = true }, { it.resurrectorAttackedUs = true },
            { it.brokenMilitaryPromise = true }, { it.brokenAttackCityStatePromise = true })
        for (betray in betrayals) {
            val memory = MajorCivMemory()
            betray(memory)
            a.ai.diplomacy.memories[b.civID] = memory
            assertTrue(verdict())
            a.ai.diplomacy.memories[b.civID] = MajorCivMemory()
            assertFalse(verdict())
        }
    }

    @Test
    fun `a broken friendship counts for 50 turns of opinion duration, for 10 once we like them again`() {
        val timeout = 50 * DiplomacyTurn.opinionDurationPercent(a) / 100
        memory(b).doFBrokenTurn = 100 - (timeout - 1)
        assertTrue("one turn before it is forgiven", verdict())
        memory(b).doFBrokenTurn = 100 - timeout
        assertFalse("forgiven", verdict())
        memory(b).doFBrokenTurn = 100 - 9
        memory(b).opinion = MajorCivOpinion.Friend
        assertTrue("9 turns ago, friends again", verdict())
        memory(b).doFBrokenTurn = 100 - 10
        assertFalse("10 turns ago, friends again", verdict())
    }

    @Test
    fun `betraying one of our friends is enough, a betrayal of a stranger needs more than our loyalty tolerates`() {
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true // b declared war on c
        assertTrue("a war on a stranger: 1 is above the 0 a loyalty of 5 tolerates", verdict())
        a.ai.setDiplomacyValue(DiplomacyPersonality.Loyalty, 3)
        assertFalse("a loyalty of 3 tolerates 1", verdict())
        friends(a, c)
        assertTrue("our friend: no tolerance", verdict())
    }

    @Test
    fun `the verdict is each observer's own`() {
        memory(b).friendDenouncedUs = true // b denounced a, its friend
        assertTrue(verdict())
        assertFalse("d saw a betrayed, which it tolerates (1 denounced friend at a loyalty of 5)", verdict(d, b))
    }

    @Test
    fun `two friends denounced is more than a loyalty of 5 tolerates, one is not`() {
        c.ai.diplomacy.memoryOf(b).friendDenouncedUs = true
        assertFalse("1 is not above 1", verdict())
        d.ai.diplomacy.memoryOf(b).friendDenouncedUs = true
        assertTrue("2 is above 1", verdict())
        a.ai.setDiplomacyValue(DiplomacyPersonality.Forgiveness, 10)
        assertFalse("a forgiving leader tolerates 2", verdict())
    }

    @Test
    fun `4 broken promises to others are more than the 3 a loyalty of 5 tolerates, a forgiving leader tolerates 5`() {
        for (other in listOf(c, d)) {
            other.ai.diplomacy.memoryOf(b).brokenMilitaryPromise = true
            other.ai.diplomacy.memoryOf(b).brokenAttackCityStatePromise = true
        }
        assertTrue("4 agreements, 3 tolerated", verdict())
        a.ai.setDiplomacyValue(DiplomacyPersonality.Forgiveness, 10)
        assertFalse("3 + 2 tolerated", verdict())
    }

    @Test
    fun `betrayals between us and a player we already fell out with are ignored`() {
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true
        assertTrue(verdict())
        memory(c).doFBrokenTurn = 90 // c and we broke a friendship: its fate is no concern
        assertFalse(verdict())
    }

    @Test
    fun `a backstabber ignores betrayals of those that are not its friends, an enemy or a rival is no concern either`() {
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true
        d.ai.diplomacy.memoryOf(a).friendDeclaredWarOnUs = true // d remembers a declaring war on a friend: a is a backstabber
        assertFalse("a backstabber", verdict())
        friends(a, c)
        assertTrue("but a friend is a friend", verdict())
        d.ai.diplomacy.memoryOf(a).friendDeclaredWarOnUs = false
        a.getDiplomacyManager(c)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        c.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertTrue(verdict())
        memory(c).opinion = MajorCivOpinion.Unforgivable
        assertFalse("we do not care about c", verdict())
        memory(c).opinion = MajorCivOpinion.Neutral
        memory(c).warmongerThreat = AiThreat.Critical
        assertFalse("nor about a critical warmonger", verdict())
        memory(c).warmongerThreat = AiThreat.None
        a.ai.diplomacy.biggestCompetitor = c.civID
        assertFalse("nor about our biggest competitor", verdict())
    }

    @Test
    fun `a human's shadow AI holds only those that harmed it, not those that harmed others`() {
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.getTile(HexCoord(6, 6)))
        for (x in listOf(a, b, c, d)) human.diplomacyFunctions.makeCivilizationsMeet(x)
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true
        friends(human, c)
        assertFalse(verdict(human, b))
        human.ai.diplomacy.memoryOf(b).numTimesNuked = 1
        assertTrue(verdict(human, b))
    }

    @Test
    fun `the diplomacy turn sets the flag`() {
        memory(b).friendDeclaredWarOnUs = true
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        DiplomacyTurn.doTurn(a)
        assertEquals(true, memory(b).untrustworthy)
    }
}
