package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
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
 * A civilization that becomes a member of the World Congress while resolutions are in effect has their effects done for it (CvLeague::AddMember, V:CvVotingClasses.cpp:3823-3827,
 * called by CvGameLeagues::DoPlayerAliveStatusChanged when a player is alive again, V:9683-9699): for Open Door that is V:1425-1440 for the new member, which meets the City-State and has
 * 40 influence with it. The League adds the row in its turn (`doTurn`), so a civilization that a liberated city resurrected is a member from the next turn. V: is
 * `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`; the resolutions are made here. [a], [b] and [c] are majors, [cs] and [other] City-States; the cities are 7 tiles apart.
 */
@RunWith(GdxTestRunner::class)
class LeagueMemberJoinTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var cs: Civilization
    private lateinit var other: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants
    private val door = "Opens the door of the chosen City-State to every civilization"
    private val sphere = "Puts the chosen City-State in the sphere of influence of the proposer"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.leagueSessionRulesLikeVp = true
        constants.cityStateInfluenceLikeVp = true
    }

    private fun placed(civ: Civilization): Civilization {
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun resolution(name: String, unique: String): Resolution {
        val made = Resolution().apply {
            this.name = name
            proposerDecision = ResolutionDecision.CityState
            uniques = arrayListOf(unique)
        }
        game.ruleset.resolutions[name] = made
        return made
    }

    /** [meet]: whether [c] knows the City-State */
    private fun found(meet: Boolean = true) {
        a = placed(game.addCiv()); b = placed(game.addCiv()); c = placed(game.addCiv())
        cs = placed(game.addCiv(cityStateType = "Maritime"))
        other = placed(game.addCiv(cityStateType = "Maritime"))
        val majors = listOf(a, b, c)
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        for (x in listOf(a, b)) { x.diplomacyFunctions.makeCivilizationsMeet(cs); x.diplomacyFunctions.makeCivilizationsMeet(other) }
        if (meet) { c.diplomacyFunctions.makeCivilizationsMeet(cs); c.diplomacyFunctions.makeCivilizationsMeet(other) }
        c.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        league.hostProposing = false
    }

    private fun set(state: Civilization, civ: Civilization, amount: Float) = state.getDiplomacyManager(civ)!!.setInfluenceWithoutSideEffects(amount)
    private fun of(state: Civilization, civ: Civilization) = state.getDiplomacyManager(civ)!!.getInfluence()

    private var nextId = 100
    private fun enact(name: String, proposer: Civilization, choice: Civilization): ActiveResolution {
        val active = ActiveResolution(nextId++, name, proposer.civID, choice.civID, gameInfo.turns)
        league.activeResolutions.add(active)
        league.setTransients(gameInfo)
        LeagueEffects.onEnact(league, active)
        return active
    }

    /** [civ] loses its cities and is out of the League after a turn */
    private fun defeat(civ: Civilization) {
        civ.hasEverOwnedOriginalCapital = true
        civ.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue(civ.isDefeated())
        league.doTurn()
        assertFalse("a defeated civilization is no member", civ.civID in league.members)
    }

    /** a liberated city gives [civ] a city again (it is alive) and a turn of the League makes it a member */
    private fun resurrect(civ: Civilization) {
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        assertFalse(civ.isDefeated())
        assertFalse("not yet a member before the League's turn", civ.civID in league.members)
        league.doTurn()
    }

    @Test
    fun `a civilization that is resurrected while open door is in force has 40 influence with the City-State`() {
        resolution("Test Door", door)
        found()
        set(cs, c, 85f)
        enact("Test Door", a, cs)
        assertEquals(40f, of(cs, c), 0.001f)
        defeat(c)
        // the influence a defeated civilization keeps is what it had; the door is in force and it was set before it was defeated
        set(cs, c, 12f)
        resurrect(c)
        assertTrue(c.civID in league.members)
        assertEquals("the door sets it, up", 40f, of(cs, c), 0.001f)
    }

    @Test
    fun `the door sets the influence down too`() {
        resolution("Test Door", door)
        found()
        enact("Test Door", a, cs)
        defeat(c)
        set(cs, c, 150f)
        resurrect(c)
        assertEquals(40f, of(cs, c), 0.001f)
    }

    @Test
    fun `a resurrected civilization that does not know the City-State meets it`() {
        resolution("Test Door", door)
        found()
        enact("Test Door", a, cs)
        defeat(c)
        // after the defeat and the resurrection of c, the City-State is met only through the door
        c.diplomacy.remove(cs.civID)
        cs.diplomacy.remove(c.civID)
        assertFalse(c.knows(cs))
        resurrect(c)
        assertTrue(c.knows(cs))
        assertEquals(40f, of(cs, c), 0.001f)
    }

    @Test
    fun `a member that was there all along is not touched by the League's sync`() {
        resolution("Test Door", door)
        found()
        enact("Test Door", a, cs)
        set(cs, b, 90f)
        league.doTurn()
        assertEquals("b is not a new member", 90f, of(cs, b), 0.001f)
    }

    @Test
    fun `every open door in force is done for the civilization that joins, each for its own City-State`() {
        resolution("Test Door", door)
        found()
        enact("Test Door", a, cs)
        enact("Test Door", a, other)
        defeat(c)
        set(cs, c, 5f); set(other, c, 77f)
        resurrect(c)
        assertEquals(40f, of(cs, c), 0.001f)
        assertEquals(40f, of(other, c), 0.001f)
    }

    @Test
    fun `an open door that stopped being in force is not done for a civilization that joins`() {
        resolution("Test Door", door)
        found()
        val active = enact("Test Door", a, cs)
        defeat(c)
        league.activeResolutions.remove(active)
        LeagueEffects.onRepeal(league, active)
        set(cs, c, 12f)
        resurrect(c)
        assertEquals("no door, no 40", 12f, of(cs, c), 0.001f)
    }

    @Test
    fun `no door effect for a civilization that joins when the switch is off`() {
        resolution("Test Door", door)
        found()
        enact("Test Door", a, cs)
        defeat(c)
        set(cs, c, 12f)
        constants.leagueSessionRulesLikeVp = false
        resurrect(c)
        assertEquals(12f, of(cs, c), 0.001f)
    }

    @Test
    fun `a civilization that joins while only a sphere is in force is not changed and the permanent ally stays`() {
        resolution("Test Sphere", sphere)
        found()
        enact("Test Sphere", a, cs)
        assertEquals(a, cs.permanentAllyCiv)
        defeat(c)
        set(cs, c, 12f)
        resurrect(c)
        assertEquals("the sphere names no member but its proposer", 12f, of(cs, c), 0.001f)
        assertEquals(a, cs.permanentAllyCiv)
    }

    @Test
    fun `the City-State that the door is on is not changed by a joining civilization, it keeps no ally`() {
        resolution("Test Door", door)
        found()
        set(cs, b, 90f)
        enact("Test Door", a, cs)
        assertNull(cs.allyCiv)
        defeat(c)
        resurrect(c)
        assertTrue(cs.noAlly)
        assertNull(cs.allyCiv)
    }

    @Test
    fun `onJoin does not touch a dead City-State, a major as the choice or a defeated civilization`() {
        resolution("Test Door", door)
        found()
        // a door on the dead City-State: the League would drop it in its turn, onJoin itself is asked directly here
        enact("Test Door", a, other)
        other.hasEverOwnedOriginalCapital = true
        other.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue(other.isDefeated())
        set(other, c, 12f)
        LeagueEffects.onJoin(league, c)
        assertEquals("a dead City-State is not touched", 12f, of(other, c), 0.001f)
        // a door whose choice is a major civilization (never valid, but in force by hand): nothing for a major
        league.activeResolutions.clear()
        league.activeResolutions.add(ActiveResolution(900, "Test Door", a.civID, b.civID, gameInfo.turns))
        b.getDiplomacyManager(c)!!.setInfluenceWithoutSideEffects(12f)
        LeagueEffects.onJoin(league, c)
        assertEquals("a major is no City-State: what c has with b is not set", 12f, of(b, c), 0.001f)
        // a defeated civilization is not given the influence
        league.activeResolutions.clear()
        league.activeResolutions.add(ActiveResolution(901, "Test Door", a.civID, cs.civID, gameInfo.turns))
        defeat(c)
        set(cs, c, 12f)
        LeagueEffects.onJoin(league, c)
        assertEquals("a defeated civilization is not a member", 12f, of(cs, c), 0.001f)
    }

    @Test
    fun `a civilization that is defeated and has no cities is not given the door's influence`() {
        resolution("Test Door", door)
        found()
        enact("Test Door", a, cs)
        defeat(c)
        set(cs, c, 12f)
        league.doTurn()
        assertEquals("still defeated: not a member, not touched", 12f, of(cs, c), 0.001f)
    }
}
