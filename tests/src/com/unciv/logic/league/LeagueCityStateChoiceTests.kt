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
 * A resolution whose proposer chooses a city-state (`RESOLUTION_DECISION_CITY_CSD`, V:CvVotingClasses.cpp:3666-3676 for the choices), as Open Door and Sphere of Influence of Vox Populi do:
 * which city-states can be chosen, when a choice is valid (V:3383-3399: the one chosen is a city-state, alive and has a city; also asked again every turn of what is on the table or in effect,
 * V:5584-5625), and what the rules of `-23`
 * do with one: the proposer who must be its ally (V:3054-3068) and the two resolutions that contradict each other for the same city-state (V:3025-3052, 3069-3092).
 * V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The resolutions are made here, never the shared ones changed. [a] founds the league with [b] and [c]; [near] and [far] are city-states.
 */
@RunWith(GdxTestRunner::class)
class LeagueCityStateChoiceTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var near: Civilization
    private lateinit var far: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.leagueSessionRulesLikeVp = true
    }

    private fun major(): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun cityState(): Civilization {
        val civ = game.addCiv(cityStateType = "Maritime")
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun resolution(name: String, vararg uniques: String, decision: ResolutionDecision = ResolutionDecision.CityState): Resolution {
        val made = Resolution().apply {
            this.name = name
            proposerDecision = decision
            this.uniques = arrayListOf(*uniques)
        }
        game.ruleset.resolutions[name] = made
        return made
    }

    private fun found(withCityStates: Boolean = true) {
        a = major()
        b = major()
        c = major()
        if (withCityStates) { near = cityState(); far = cityState() }
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        c.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        league.hostProposing = false
        for (member in league.members.values) member.proposalsLeft = 3
    }

    @Test
    fun `the choices are the city-states of the game and no major civilization`() {
        found()
        val choices = league.getChoices(ResolutionDecision.CityState, a)
        assertEquals(setOf(near.civID, far.civID), choices.toSet())
        assertEquals("no major among them", 0, choices.count { gameInfo.getCivilization(it).isMajorCiv })
    }

    @Test
    fun `a living city-state with a city can be chosen`() {
        val door = resolution("Test Door")
        found()
        assertTrue(league.canProposeEnact(door, near.civID, a))
        assertTrue(league.canProposeEnact(door, far.civID, a))
    }

    @Test
    fun `a major civilization cannot be chosen where a city-state is asked for`() {
        val door = resolution("Test Door")
        found()
        assertFalse("not in the list of choices", league.canProposeEnact(door, b.civID, a))
    }

    @Test
    fun `no choice and an unknown one cannot be proposed`() {
        val door = resolution("Test Door")
        found()
        assertFalse(league.canProposeEnact(door, null, a))
        assertFalse(league.canProposeEnact(door, "Nobody", a))
    }

    @Test
    fun `a defeated city-state cannot be chosen`() {
        val door = resolution("Test Door")
        found()
        // V:3389-3390 "Player is dead": a city-state that owned its original capital and has no city left is defeated
        near.hasEverOwnedOriginalCapital = true
        near.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertFalse("the setup: it is defeated", near.isAlive())
        assertFalse(league.canProposeEnact(door, near.civID, a))
        assertTrue("the other still can", league.canProposeEnact(door, far.civID, a))
    }

    @Test
    fun `a city-state that is defeated while it still has a city cannot be chosen`() {
        val door = resolution("Test Door")
        found()
        // the engine defeats a civilization that never owned its original capital when it has no unit left, whatever its cities (Civilization.isDefeated): the one case that
        // only the "alive" test of V:3389-3390 catches, as this one still has a city
        near.hasEverOwnedOriginalCapital = false
        near.units.getCivUnits().toList().forEach { it.destroy() }
        assertTrue("the setup: it has a city", near.cities.isNotEmpty())
        assertFalse("the setup: and is defeated", near.isAlive())
        assertFalse(league.canProposeEnact(door, near.civID, a))
        assertTrue(league.canProposeEnact(door, far.civID, a))
    }

    @Test
    fun `a city-state that is alive but has no city cannot be chosen`() {
        val door = resolution("Test Door")
        found()
        // V:3395-3396 "Player is alive but no cities left": one that never owned its original capital is alive as long as it has a unit
        near.hasEverOwnedOriginalCapital = false
        game.addUnit("Warrior", near, near.cities.single().getCenterTile())
        near.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue("the setup: it is alive", near.isAlive())
        assertTrue("the setup: with no city", near.cities.isEmpty())
        assertFalse(league.canProposeEnact(door, near.civID, a))
    }

    @Test
    fun `a game with no city-state has nothing to choose, and the resolution is not proposable`() {
        // V:3372 asks that the game ever had a city-state; here the list of choices is empty, which is the same outcome
        val door = resolution("Test Door")
        found(withCityStates = false)
        assertEquals("nothing to choose", emptyList<String>(), league.getChoices(ResolutionDecision.CityState, a))
        assertFalse(league.canProposeEnact(door, "Anyone", a))
        assertFalse(league.getProposableResolutions(a).contains(door))
    }

    @Test
    fun `a proposal for a city-state that is gone drops off the table and one for another stays`() {
        // CvLeague::CheckProposalsValid (V:5584-5625) asks the validity of the choice again every turn
        val door = resolution("Test Door")
        found()
        league.proposeEnact(door, near.civID, a)
        league.proposeEnact(door, far.civID, b)
        assertEquals(2, league.proposals.size)
        near.hasEverOwnedOriginalCapital = true
        near.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertFalse("the setup: near is defeated", near.isAlive())
        league.doTurn()
        assertEquals("only the one for far is left", listOf(far.civID), league.proposals.map { it.proposerChoice })
    }

    @Test
    fun `a resolution in effect for a city-state that is gone is repealed and one for another stays`() {
        // CvLeague::CheckResolutionsValid (V:5584-5625)
        val door = resolution("Test Door")
        found()
        league.activeResolutions.add(ActiveResolution(league.nextProposalId++, door.name, null, near.civID, 0))
        league.activeResolutions.add(ActiveResolution(league.nextProposalId++, door.name, null, far.civID, 0))
        near.hasEverOwnedOriginalCapital = true
        near.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertFalse("the setup: near is defeated", near.isAlive())
        league.doTurn()
        assertEquals(listOf(far.civID), league.activeResolutions.map { it.choice })
    }

    @Test
    fun `a major civilization named by a resolution that asks for a city-state is not valid`() {
        // the check of isResolutionEffectsValid by itself, as the turn's re-check reaches it with a choice that did not come from the list: V:3383 asks for a minor civilization
        val door = resolution("Test Door")
        found()
        league.activeResolutions.add(ActiveResolution(league.nextProposalId++, door.name, null, b.civID, 0))
        league.doTurn()
        assertTrue("b is a major civilization, not a city-state", league.activeResolutions.isEmpty())
    }

    @Test
    fun `the resolutions with a city-state to choose are listed as proposable when there is one to choose`() {
        val door = resolution("Test Door")
        found()
        assertTrue(league.getProposableResolutions(a).contains(door))
    }

    @Test
    fun `only the ally of the city-state can propose a resolution that says so`() {
        val sphere = resolution("Test Sphere", "Can only be proposed by the ally of the chosen civilization")
        found()
        near.allyCiv = a
        assertTrue("a is the ally of near", league.canProposeEnact(sphere, near.civID, a))
        assertFalse("b is not", league.canProposeEnact(sphere, near.civID, b))
        assertFalse("and nobody is the ally of far", league.canProposeEnact(sphere, far.civID, a))
    }

    @Test
    fun `two resolutions that contradict each other do so for the same city-state only`() {
        val door = resolution("Test Door")
        val sphere = resolution("Test Sphere", "Cannot be proposed while [Test Door] is in effect or proposed for the same choice")
        found()
        league.proposeEnact(door, near.civID, a)
        assertFalse("the same city-state", league.canProposeEnact(sphere, near.civID, b))
        assertTrue("another city-state", league.canProposeEnact(sphere, far.civID, b))
    }

    @Test
    fun `the same resolution can be proposed for two city-states but not twice for one`() {
        val door = resolution("Test Door")
        found()
        league.proposeEnact(door, near.civID, a)
        assertFalse("already on the table for it", league.canProposeEnact(door, near.civID, b))
        assertTrue(league.canProposeEnact(door, far.civID, b))
    }

    @Test
    fun `the choice of a city-state is carried to the active resolution`() {
        val door = resolution("Test Door")
        found()
        league.proposeEnact(door, near.civID, a)
        assertEquals(near.civID, league.proposals.single().proposerChoice)
    }
}
