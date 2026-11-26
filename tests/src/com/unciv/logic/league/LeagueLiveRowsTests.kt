package com.unciv.logic.league

import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.unit.EspionageAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.models.ruleset.validation.RulesetValidator
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Open Door and Sphere of Influence rows of the LIVE Brave New World ruleset (Resolutions.json; Vox Populi NewResolutions.xml RESOLUTION_OPEN_DOOR and
 * RESOLUTION_SPHERE_OF_INFLUENCE, file lines 29-36 and 11-18), not rows made in the test: they load and pass the validator, and with `leagueSessionRulesLikeVp` an AI proposes Open Door
 * through `automateLeague`, the session votes, `League.doTurn` enacts it, every civilization has 40 influence with the City-State, it has no ally and the AI spy in it leaves.
 */
@RunWith(GdxTestRunner::class)
class LeagueLiveRowsTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var cs: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val majors get() = listOf(a, b, c)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        game.ruleset.modOptions.constants.cityStateInfluenceLikeVp = true
    }

    private fun major(): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun found() {
        a = major(); b = major(); c = major()
        cs = game.addCiv(cityStateType = "Maritime")
        game.addCity(cs, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        for (x in majors) x.diplomacyFunctions.makeCivilizationsMeet(cs)
        a.tech.addTechnology("Printing Press")
        a.tech.addTechnology("Economics")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    @Test
    fun `the live ruleset has both rows and they pass the validator`() {
        val bnw = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val door = bnw.resolutions["Open Door"]!!
        val sphere = bnw.resolutions["Sphere of Influence"]!!
        assertTrue(door.opensDoor && !door.putsInSphere)
        assertTrue(sphere.putsInSphere && !sphere.opensDoor)
        for (row in listOf(door, sphere)) {
            assertEquals(ResolutionDecision.CityState, row.proposerDecision)
            assertEquals(ResolutionDecision.YesNo, row.voterDecision)
            assertTrue(row.playerProposable)
            assertFalse(row.isProject)
        }
        assertEquals("Economics", door.requiredTechAnyMember)
        assertEquals("Steam Power", sphere.requiredTechAnyMember)
        assertTrue(sphere.hasUnique(com.unciv.models.ruleset.unique.UniqueType.ResolutionProposerIsAllyOfChoice))
        assertEquals(listOf("Sphere of Influence"), door.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ResolutionConflictsWithSameChoice).map { it.params[0] }.toList())
        assertEquals(listOf("Open Door", "Sphere of Influence"), bnw.resolutions.keys.toList().takeLast(2))
        val errors = RulesetValidator.create(bnw, true).getErrorList()
        val ours = errors.filter { "Open Door" in it.text || "Sphere of Influence" in it.text }
        assertTrue(ours.joinToString { it.text }, ours.isEmpty())
        assertFalse(errors.getErrorText(true), errors.any { it.errorSeverityToReport == RulesetErrorSeverity.Error })
    }

    @Test
    fun `with the switch off an enacted Open Door or Sphere of Influence changes nothing`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = false
        found()
        cs.getDiplomacyManager(a)!!.setInfluenceWithoutSideEffects(10f)
        for (name in listOf("Open Door", "Sphere of Influence")) {
            val active = ActiveResolution(league.nextProposalId++, name, a.civID, cs.civID, 0)
            league.activeResolutions.add(active)
            league.setTransients(gameInfo)
            LeagueEffects.onEnact(league, active)
        }
        assertEquals(10f, cs.getDiplomacyManager(a)!!.getInfluence(), 0.001f)
        assertFalse(cs.noAlly)
        assertNull(cs.permanentAllyCiv)
    }

    @Test
    fun `an AI proposes Open Door for a City-State it wants to conquer, the session enacts it and the spy in that City-State leaves`() {
        found()
        for (civ in majors) civ.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Conquest
        // Standing Army Tax is already in effect from an earlier session. In a fresh game of this setup with a Bully approach (a probe, not asserted) the host scored the Tax 775,
        // the World's Fair 484 and Open Door 480, so Open Door only gets picked once the better ones are taken, as in a later session; Conquest is worth more still
        league.activeResolutions.add(ActiveResolution(league.nextProposalId++, "Standing Army Tax", null, null, 0))
        league.setTransients(gameInfo)

        val spy = c.espionageManager.addSpy()
        val csCity = cs.cities.first()
        spy.moveTo(csCity)
        var guard = 0
        while (!spy.isSetUp() && guard++ < 20) c.espionageManager.endTurn()
        assertTrue(spy.isSetUp())
        c.ai.setGrandStrategyAndPursuit(null)
        game.addCity(c, game.tileMap[0, -3])
        assertTrue(csCity in EspionageAutomation(c).buildMinorCityList())
        EspionageAutomation(c).automateSpies()
        assertSame("nothing flags the spy before the proposal", csCity, spy.getCityOrNull())

        // proposals: the host first, then the largest other delegation (leagueProposersPerSession); the choice is the one City-State
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        val proposal = league.proposals.single { it.resolution == "Open Door" }
        assertEquals(cs.civID, proposal.proposerChoice)
        assertFalse(proposal.repeal)
        assertFalse("not proposed twice", league.proposals.count { it.resolution == "Open Door" } > 1)
        assertFalse("an Open Door on the table takes the City-State off the spy list", csCity in EspionageAutomation(c).buildMinorCityList())

        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue(league.inSession)
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        for (civ in majors) assertEquals(0, league.getDelegatesLeft(civ))
        assertTrue("the delegates voted for it", league.proposals.single { it.resolution == "Open Door" }.votes.any { it.choice == League.YES })
        assertTrue(league.activeResolutions.none { it.resolution == "Open Door" })
        league.doTurn()

        val active = league.activeResolutions.single { it.resolution == "Open Door" }
        assertEquals(cs.civID, active.choice)
        for (civ in majors) assertEquals(40f, cs.getDiplomacyManager(civ)!!.getInfluence(), 0.001f)
        assertTrue(cs.noAlly)
        assertNull(cs.allyCiv)
        assertFalse(csCity in EspionageAutomation(c).buildMinorCityList())
        EspionageAutomation(c).automateSpies()
        assertTrue("the spy has left the City-State", spy.getCityOrNull() != csCity)
    }
}
