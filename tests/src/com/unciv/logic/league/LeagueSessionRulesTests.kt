package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The flow of a session of Vox Populi, `ModConstants.leagueSessionRulesLikeVp` (CvLeague, V:CvVotingClasses.cpp): the host proposes first and the others after it (:2619, 2658, 4391, 8368),
 * what was on the table when the last session started cannot be enacted again, except a project (:7278-7288, 2820-2850), and the two yields of a session (:7292-7303, 7624, 8028).
 * Three civilizations: [a] founds it and is the host, [b] and [c] follow; every civilization gets its yields from a unique of its nation.
 */
@RunWith(GdxTestRunner::class)
class LeagueSessionRulesTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.leagueSessionRulesLikeVp = true
    }

    //region Helpers
    private fun cityTile() = game.tileMap.tileList[7 * nextCell++]

    private fun major(vararg uniques: String): Civilization {
        val civ = game.addCiv(*uniques)
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private fun meetAll() {
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    /** Three majors, the league founded by [a], which is the host; [a] has the most delegates by one so that it and [b] are the proposers when the constant is 2 */
    private fun threeCivs(aUniques: Array<String> = emptyArray(), bUniques: Array<String> = emptyArray()) {
        a = major(*aUniques)
        b = major(*bUniques)
        c = major()
        meetAll()
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        assertEquals("the founder is the host", a.civID, league.host)
    }

    private fun resolution(name: String): Resolution = game.ruleset.resolutions[name]!!

    private fun startSession() {
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue("the session should have started", league.inSession)
    }

    private fun abstainAll() {
        for (civ in gameInfo.civilizations.filter { it.isMajorCiv }) league.abstainRemaining(civ)
    }

    /** Everybody who may propose proposes the first thing that is legal and not [except] */
    private fun everyoneProposes(vararg except: String) {
        for (civ in listOf(a, b, c)) while (league.canPropose(civ)) {
            val resolution = league.getProposableResolutions(civ).first { it.name !in except }
            val choice = if (resolution.proposerDecision == com.unciv.models.ruleset.ResolutionDecision.None) null else league.getChoices(resolution, civ).first()
            league.proposeEnact(resolution, choice, civ)
        }
    }
    //endregion

    //region Host first
    @Test
    fun `the host proposes first and the others only after its proposals are used`() {
        threeCivs()
        assertTrue(league.hostProposing)
        assertTrue(league.canPropose(a))
        assertFalse("the second proposer waits for the host", league.canPropose(b))
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        assertFalse(league.hostProposing)
        assertTrue(league.canPropose(b))
        assertFalse("the host has used its one", league.canPropose(a))
    }

    @Test
    fun `without the switch the proposers propose in any order`() {
        constants.leagueSessionRulesLikeVp = false
        threeCivs()
        assertFalse(league.hostProposing)
        assertTrue(league.canPropose(a))
        assertTrue(league.canPropose(b))
    }

    @Test
    fun `with three proposers the host and the two others propose, with two only the host and one other`() {
        constants.leagueProposersPerSession = 3
        threeCivs()
        assertEquals("the host and both others", listOf(true, true, true), listOf(a, b, c).map { league.members.getValue(it.civID).proposalsLeft > 0 })
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        assertTrue(league.canPropose(b))
        assertTrue("c proposes too once the host is done", league.canPropose(c))
    }

    @Test
    fun `with two proposers the host and the largest other delegation propose`() {
        constants.leagueProposersPerSession = 2
        threeCivs(bUniques = arrayOf("[1] World Congress Delegates"))
        assertEquals(listOf(true, true, false), listOf(a, b, c).map { league.members.getValue(it.civID).proposalsLeft > 0 })
    }

    @Test
    fun `the others keep their right to propose while the host still proposes`() {
        threeCivs()
        // the turn of the league removes the right of whoever has nothing to propose; the others have plenty, but may not propose yet, which must not count against them
        league.doTurn()
        assertTrue(league.hostProposing)
        assertEquals("b still has its proposal", 1, league.members.getValue(b.civID).proposalsLeft)
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        assertTrue("and can use it once the host has", league.canPropose(b))
    }

    @Test
    fun `a host with nothing to propose does not keep the others from proposing`() {
        threeCivs()
        // Everything that can be proposed is on the table already, so nobody has anything left to put there: the engine takes the right to propose from whoever has nothing to propose
        // (a rule of its own, Vox Populi has none), and the host's must also end its turn, or the session would wait for proposals that nobody may make
        for (r in league.getProposableResolutions(a)) {
            val choices = if (r.proposerDecision == com.unciv.models.ruleset.ResolutionDecision.None) listOf(null) else league.getChoices(r, a)
            for (choice in choices) league.proposals.add(LeagueProposal(league.nextProposalId++, r.name, false, null, choice))
        }
        assertTrue("the fixture: a cannot propose anything now", league.getProposableResolutions(a).isEmpty())
        league.doTurn()
        assertEquals("a lost its right for having nothing to propose", 0, league.members.getValue(a.civID).proposalsLeft)
        assertFalse("and the others do not wait for it", league.hostProposing)
    }
    //endregion

    //region Last session's table
    /** Plays one regular session in which a proposes [first] and b proposes [second], nobody votes; the next session's proposers have their privileges again */
    private fun playSession(first: String, second: String) {
        league.proposeEnact(resolution(first), null, a)
        league.proposeEnact(resolution(second), null, b)
        startSession()
        abstainAll()
        league.doTurn()
        assertFalse(league.inSession)
    }

    @Test
    fun `what was on the table when a session started cannot be enacted in the next, a different one can`() {
        threeCivs()
        playSession("Standing Army Tax", "Cultural Heritage Sites")
        assertTrue("the host proposes again", league.canPropose(a))
        assertFalse(league.canProposeEnact(resolution("Standing Army Tax"), null, a))
        assertFalse(league.canProposeEnact(resolution("Cultural Heritage Sites"), null, a))
        assertTrue(league.canProposeEnact(resolution("Arts Funding"), null, a))
        league.proposeEnact(resolution("Arts Funding"), null, a)
        league.proposeEnact(resolution("Sciences Funding"), null, b)
        startSession()
        abstainAll()
        league.doTurn()
        assertTrue("two sessions ago is allowed again", league.canProposeEnact(resolution("Standing Army Tax"), null, a))
        assertFalse(league.canProposeEnact(resolution("Arts Funding"), null, a))
    }

    @Test
    fun `without the switch last session's proposals can be proposed again`() {
        constants.leagueSessionRulesLikeVp = false
        threeCivs()
        playSession("Standing Army Tax", "Cultural Heritage Sites")
        assertTrue(league.canProposeEnact(resolution("Standing Army Tax"), null, a))
        assertTrue(league.lastSessionProposals.isEmpty())
    }

    @Test
    fun `a table kept from a game saved with the switch on is ignored when it is off`() {
        constants.leagueSessionRulesLikeVp = false
        threeCivs()
        league.lastSessionProposals.add(LeagueProposalKey("Standing Army Tax", null, false))
        assertTrue(league.canProposeEnact(resolution("Standing Army Tax"), null, a))
    }

    @Test
    fun `the same resolution with another choice is not what was proposed last session`() {
        threeCivs()
        val embargo = resolution("Embargo")
        league.proposeEnact(embargo, c.civID, a)
        league.proposeEnact(resolution("Standing Army Tax"), null, b)
        startSession()
        abstainAll()
        league.doTurn()
        assertFalse("the same choice", league.canProposeEnact(embargo, c.civID, a))
        assertTrue("another choice", league.canProposeEnact(embargo, b.civID, a))
    }

    @Test
    fun `a project can be proposed again after last session's table held it, a resolution cannot`() {
        threeCivs()
        val project = resolution("World's Fair")
        assertTrue("the fixture: a project", project.isProject)
        assertTrue(league.canProposeEnact(project, null, a))
        league.lastSessionProposals.add(LeagueProposalKey(project.name, null, false))
        assertTrue("a project is not refused for having been on the table", league.canProposeEnact(project, null, a))
        league.lastSessionProposals.add(LeagueProposalKey("Standing Army Tax", null, false))
        assertFalse(league.canProposeEnact(resolution("Standing Army Tax"), null, a))
    }

    @Test
    fun `a repeal that was on last session's table is proposed again freely, and the resolution it targets cannot be enacted again`() {
        threeCivs()
        league.activeResolutions.add(ActiveResolution(1, "Standing Army Tax", a.civID, null, 0))
        league.setTransients(gameInfo)
        league.proposeRepeal(1, a)
        league.proposeEnact(resolution("Arts Funding"), null, b)
        startSession()
        abstainAll() // nobody votes, so the repeal fails and the resolution stays in effect
        league.doTurn()
        assertTrue("the repeal is on last session's table", league.lastSessionProposals.any { it.resolution == "Standing Army Tax" && it.repeal })
        assertTrue("it stays in effect", league.isActive("Standing Army Tax"))
        assertTrue("a repeal is not refused for having been proposed last session (V:CvVotingClasses.cpp:3166-3223 has no such check)", league.canProposeRepeal(1, a))
        // CanProposeEnact looks in both lists for the resolution and the choice: the repealed resolution is on one of them
        league.activeResolutions.clear()
        league.setTransients(gameInfo)
        assertFalse("not enacted again at once", league.canProposeEnact(resolution("Standing Army Tax"), null, a))
    }

    @Test
    fun `last session's table and who is proposing are saved with the league and copied by clone`() {
        threeCivs()
        playSession("Standing Army Tax", "Cultural Heritage Sites")
        val copy = league.clone()
        assertEquals(league.hostProposing, copy.hostProposing)
        assertEquals(setOf("Standing Army Tax", "Cultural Heritage Sites"), copy.lastSessionProposals.map { it.resolution }.toSet())
        assertEquals(league.lastSessionProposals.map { Triple(it.resolution, it.choice, it.repeal) }, copy.lastSessionProposals.map { Triple(it.resolution, it.choice, it.repeal) })
    }
    //endregion

    //region The yields
    @Test
    fun `a regular session start pays each member the delegates it has, in its capital`() {
        threeCivs(aUniques = arrayOf("Gain [100]% of the triggering amount as [Gold] <upon a World Congress session starting>"),
            bUniques = arrayOf("Gain [100]% of the triggering amount as [Gold] <upon a World Congress session starting>"))
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        league.proposeEnact(resolution("Cultural Heritage Sites"), null, b)
        startSession()
        assertEquals("a: 1 of the era and 1 as the host", 2, a.gold)
        assertEquals("b: 1 of the era", 1, b.gold)
    }

    @Test
    fun `without the switch a session start pays nothing`() {
        constants.leagueSessionRulesLikeVp = false
        threeCivs(aUniques = arrayOf("Gain [100]% of the triggering amount as [Gold] <upon a World Congress session starting>"))
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        league.proposeEnact(resolution("Cultural Heritage Sites"), null, b)
        startSession()
        assertEquals(0, a.gold)
    }

    @Test
    fun `a special session pays nothing`() {
        threeCivs(aUniques = arrayOf("Gain [100]% of the triggering amount as [Gold] <upon a World Congress session starting>"))
        league.members.values.forEach { it.proposalsLeft = 0 }
        for (civ in listOf(a, b, c)) civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Atomic era" }.name)
        league.doTurn()
        assertEquals("a special session is held", "Atomic era", league.currentSpecialSession)
        assertEquals(0, a.gold)
    }

    @Test
    fun `a proposal that passes pays its proposer and nobody else, and one that fails pays nothing`() {
        val gain = "Gain [7] [Gold] <upon a proposal of ours passing>"
        threeCivs(aUniques = arrayOf(gain), bUniques = arrayOf(gain))
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        league.proposeEnact(resolution("Cultural Heritage Sites"), null, b)
        startSession()
        val first = league.proposals.first { it.resolution == "Standing Army Tax" }
        // all of a's delegates for the first, which passes; b votes against its own proposal... nobody votes for the second, which fails
        league.vote(first.id, a, league.getDelegatesLeft(a), League.YES)
        league.vote(league.proposals.first { it.resolution == "Cultural Heritage Sites" }.id, b, league.getDelegatesLeft(b), League.NO)
        abstainAll()
        league.doTurn()
        assertEquals("the proposer of the proposal that passed", 7, a.gold)
        assertEquals("the proposal of b failed", 0, b.gold)
        assertEquals(0, c.gold)
    }

    @Test
    fun `the yield of a passed proposal is paid before the resolution takes effect`() {
        // This unique pays only while the resolution is in effect. If the yield is paid first, as Vox Populi does (V:CvVotingClasses.cpp:8028 before DoEnactResolution at 8030),
        // the resolution is not in effect yet and it pays nothing; paid after the enactment it would pay 7
        threeCivs(aUniques = arrayOf("Gain [7] [Gold] <upon a proposal of ours passing> <when the [Standing Army Tax] resolution is in effect>"))
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        league.proposeEnact(resolution("Cultural Heritage Sites"), null, b)
        startSession()
        league.vote(league.proposals.first { it.resolution == "Standing Army Tax" }.id, a, league.getDelegatesLeft(a), League.YES)
        abstainAll()
        league.doTurn()
        assertTrue("it passed and is in effect now", league.isActive("Standing Army Tax"))
        assertEquals("but it was not yet when the yield was paid", 0, a.gold)
    }

    @Test
    fun `a repeal that passes pays its proposer too`() {
        val gain = "Gain [7] [Gold] <upon a proposal of ours passing>"
        threeCivs(aUniques = arrayOf(gain))
        league.activeResolutions.add(ActiveResolution(1, "Standing Army Tax", b.civID, null, 0))
        league.setTransients(gameInfo)
        league.proposeRepeal(1, a)
        league.proposeEnact(resolution("Arts Funding"), null, b)
        startSession()
        league.vote(league.proposals.first { it.repeal }.id, a, league.getDelegatesLeft(a), League.YES)
        abstainAll()
        league.doTurn()
        assertFalse("the repeal passed", league.isActive("Standing Army Tax"))
        assertEquals(7, a.gold)
    }

    @Test
    fun `the proposals of the World Congress itself pay nobody`() {
        val gain = "Gain [7] [Gold] <upon a proposal of ours passing>"
        threeCivs(aUniques = arrayOf(gain), bUniques = arrayOf(gain))
        // The World Congress proposes the host election itself: it passes (a host election always does once anybody has voted) and the player proposals fail, so only it passes
        val hostElection = game.ruleset.resolutions.values.first { it.isHostElection }
        league.proposals.add(LeagueProposal(league.nextProposalId++, hostElection.name, false, null, null))
        league.proposeEnact(resolution("Standing Army Tax"), null, a)
        league.proposeEnact(resolution("Cultural Heritage Sites"), null, b)
        startSession()
        // a (2 delegates) and b (1): each votes No on the proposal of the other, and a puts its last delegate on b for the host
        league.vote(league.proposals.first { it.resolution == "Standing Army Tax" }.id, b, 1, League.NO)
        league.vote(league.proposals.first { it.resolution == "Cultural Heritage Sites" }.id, a, 1, League.NO)
        val election = league.proposals.first { it.resolution == hostElection.name }
        league.vote(election.id, a, league.getDelegatesLeft(a), b.civID)
        abstainAll()
        league.doTurn()
        assertEquals("the election passed: b is the host", b.civID, league.host)
        assertEquals("and it paid nobody", 0, a.gold + b.gold)
    }
    //endregion
}
