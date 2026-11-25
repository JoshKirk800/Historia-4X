package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What can be proposed in the World Congress of Vox Populi, `ModConstants.leagueSessionRulesLikeVp`, as data on the resolutions (CvLeague::CanProposeEnact, V:CvVotingClasses.cpp:2909-3150,
 * 3054-3068, and IsResolutionEffectsValid, 3237-3270): conflicts, the proposer who must be the ally, and a resolution that needs another in effect.
 * The resolutions are made here, never the shared ones changed: two that conflict, one that conflicts only for the same choice, one that needs the first in effect.
 * Three civilizations: [a] founds the league, [b] and [c] follow.
 */
@RunWith(GdxTestRunner::class)
class LeagueProposalRulesTests {
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
    private fun major(): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun resolution(name: String, vararg uniques: String, decision: ResolutionDecision = ResolutionDecision.None): Resolution {
        val made = Resolution().apply {
            this.name = name
            proposerDecision = decision
            this.uniques = arrayListOf(*uniques)
        }
        game.ruleset.resolutions[name] = made
        return made
    }

    private fun found() {
        a = major()
        b = major()
        c = major()
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        c.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        assertEquals("the founder is the host", c.civID, league.host)
        // The turn order is not what is tested: the host has proposed already, so the others propose, with a proposal each
        league.hostProposing = false
        for (member in league.members.values) member.proposalsLeft = 3
    }

    /** A resolution in effect, as if it had passed */
    private fun enact(name: String, choice: String? = null) {
        league.activeResolutions.add(ActiveResolution(league.nextProposalId++, name, null, choice, 0))
    }
    //endregion

    @Test
    fun `a resolution that names another cannot be proposed while the other is on the table, either way round`() {
        val war = resolution("Test War")
        val tax = resolution("Test Tax", "Cannot be proposed while [Test War] is in effect or proposed")
        found()
        assertTrue(league.canProposeEnact(war, null, a))
        league.proposeEnact(war, null, a)
        assertFalse("the one that names the other", league.canProposeEnact(tax, null, b))
        // the other way round: the one that is named is refused while the one that names it is on the table
        league.proposals.clear()
        league.proposeEnact(tax, null, a)
        assertFalse("the one that is named", league.canProposeEnact(war, null, b))
    }

    @Test
    fun `a resolution in effect conflicts too`() {
        resolution("Test War")
        val tax = resolution("Test Tax", "Cannot be proposed while [Test War] is in effect or proposed")
        found()
        enact("Test War")
        assertFalse(league.canProposeEnact(tax, null, a))
        assertTrue("a repeal of the one in effect is no enactment that conflicts", league.canProposeRepeal(league.activeResolutions.single().id, a))
    }

    @Test
    fun `a repeal on the table does not lift the conflict of the resolution that is still in effect`() {
        resolution("Test War")
        val tax = resolution("Test Tax", "Cannot be proposed while [Test War] is in effect or proposed")
        found()
        enact("Test War")
        league.proposeRepeal(league.activeResolutions.single().id, a)
        // V:CvVotingClasses.cpp:2909-2960 reads the enact proposals only; what is still in effect conflicts all the same
        assertFalse(league.canProposeEnact(tax, null, b))
    }

    @Test
    fun `unrelated resolutions do not conflict`() {
        val war = resolution("Test War")
        val tax = resolution("Test Tax", "Cannot be proposed while [Test War] is in effect or proposed")
        val other = resolution("Test Other")
        found()
        league.proposeEnact(war, null, a)
        assertTrue(league.canProposeEnact(other, null, b))
        assertFalse(league.canProposeEnact(tax, null, b))
    }

    @Test
    fun `the same choice conflicts and another choice does not`() {
        val door = resolution("Test Door", decision = ResolutionDecision.OtherMajorCiv)
        val sphere = resolution("Test Sphere", "Cannot be proposed while [Test Door] is in effect or proposed for the same choice", decision = ResolutionDecision.OtherMajorCiv)
        found()
        league.proposeEnact(door, c.civID, a)
        assertFalse("the same civilization", league.canProposeEnact(sphere, c.civID, b))
        assertTrue("another one", league.canProposeEnact(sphere, a.civID, b))
    }

    @Test
    fun `a same choice conflict also works from the resolution that is named`() {
        val door = resolution("Test Door", decision = ResolutionDecision.OtherMajorCiv)
        val sphere = resolution("Test Sphere", "Cannot be proposed while [Test Door] is in effect or proposed for the same choice", decision = ResolutionDecision.OtherMajorCiv)
        found()
        league.proposeEnact(sphere, c.civID, a)
        assertFalse(league.canProposeEnact(door, c.civID, b))
        assertTrue(league.canProposeEnact(door, a.civID, b))
    }

    @Test
    fun `the conflicts are ignored with the switch off`() {
        constants.leagueSessionRulesLikeVp = false
        val war = resolution("Test War")
        val tax = resolution("Test Tax", "Cannot be proposed while [Test War] is in effect or proposed")
        found()
        league.proposeEnact(war, null, a)
        assertTrue(league.canProposeEnact(tax, null, b))
    }

    @Test
    fun `only the ally of the chosen civilization can propose a resolution that says so`() {
        val sphere = resolution("Test Sphere", "Can only be proposed by the ally of the chosen civilization", decision = ResolutionDecision.OtherMajorCiv)
        found()
        c.allyCiv = a
        assertTrue("a is the ally of c", league.canProposeEnact(sphere, c.civID, a))
        assertFalse("b is not", league.canProposeEnact(sphere, c.civID, b))
        assertFalse("and the one who chose a civilization with no ally", league.canProposeEnact(sphere, b.civID, a))
    }

    @Test
    fun `the ally rule is ignored with the switch off`() {
        constants.leagueSessionRulesLikeVp = false
        val sphere = resolution("Test Sphere", "Can only be proposed by the ally of the chosen civilization", decision = ResolutionDecision.OtherMajorCiv)
        found()
        assertTrue(league.canProposeEnact(sphere, c.civID, a))
    }

    @Test
    fun `a resolution that needs another in effect cannot be proposed before it, and can after`() {
        resolution("Test Ideology")
        val leader = resolution("Test Leader", "Can only be proposed while [Test Ideology] is in effect")
        found()
        assertFalse(league.canProposeEnact(leader, null, a))
        enact("Test Ideology")
        assertTrue(league.canProposeEnact(leader, null, a))
    }

    @Test
    fun `a proposal that needs a resolution that is repealed drops off the table`() {
        resolution("Test Ideology")
        val leader = resolution("Test Leader", "Can only be proposed while [Test Ideology] is in effect")
        found()
        enact("Test Ideology")
        league.proposeEnact(leader, null, a)
        assertEquals(1, league.proposals.size)
        league.activeResolutions.clear()
        league.doTurn()
        assertTrue("what it needed is gone", league.proposals.none { it.resolution == leader.name })
    }

    @Test
    fun `the prerequisite is ignored with the switch off`() {
        constants.leagueSessionRulesLikeVp = false
        resolution("Test Ideology")
        val leader = resolution("Test Leader", "Can only be proposed while [Test Ideology] is in effect")
        found()
        assertTrue(league.canProposeEnact(leader, null, a))
    }
}
