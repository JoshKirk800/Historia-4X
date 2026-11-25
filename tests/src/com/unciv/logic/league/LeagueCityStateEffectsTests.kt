package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.validation.RulesetValidator
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
 * The effects of Open Door and Sphere of Influence (CvActiveResolution::DoEffects, V:CvVotingClasses.cpp:1425-1454, and the same function's removal, V:1921-1929), as the uniques
 * `Opens the door of the chosen City-State to every civilization` and `Puts the chosen City-State in the sphere of influence of the proposer` behind `leagueSessionRulesLikeVp`.
 * V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The resolutions are made here, never the shared ones changed. [a] founds the league with [b] and [c]; [cs] is the City-State.
 * The ally rule is that of Vox Populi (`cityStateInfluenceLikeVp`): 60 influence or more and the most, a permanent ally whatever the influence, none with `noAlly`.
 */
@RunWith(GdxTestRunner::class)
class LeagueCityStateEffectsTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var cs: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants
    private val door = "Opens the door of the chosen City-State to every civilization"
    private val sphere = "Puts the chosen City-State in the sphere of influence of the proposer"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.leagueSessionRulesLikeVp = true
        constants.cityStateInfluenceLikeVp = true
    }

    private fun major(): Civilization {
        val civ = game.addCiv()
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

    /** [meet]: whether [c] knows the City-State */
    private fun found(meet: Boolean = true) {
        a = major(); b = major(); c = major()
        cs = game.addCiv(cityStateType = "Maritime")
        game.addCity(cs, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        val majors = listOf(a, b, c)
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.diplomacyFunctions.makeCivilizationsMeet(cs)
        b.diplomacyFunctions.makeCivilizationsMeet(cs)
        if (meet) c.diplomacyFunctions.makeCivilizationsMeet(cs)
        c.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        league.hostProposing = false
        for (member in league.members.values) member.proposalsLeft = 3
    }

    private fun set(civ: Civilization, amount: Float) = cs.getDiplomacyManager(civ)!!.setInfluenceWithoutSideEffects(amount)
    private fun of(civ: Civilization) = cs.getDiplomacyManager(civ)!!.getInfluence()

    private var nextId = 100
    private fun enact(name: String, proposer: Civilization? = a, choice: String? = cs.civID): ActiveResolution {
        val active = ActiveResolution(nextId++, name, proposer?.civID, choice, gameInfo.turns)
        league.activeResolutions.add(active)
        league.setTransients(gameInfo)
        LeagueEffects.onEnact(league, active)
        return active
    }

    private fun repeal(active: ActiveResolution) {
        league.activeResolutions.remove(active)
        LeagueEffects.onRepeal(league, active)
    }

    // ---- Open Door ----

    @Test
    fun `open door sets the influence of every living major civilization to 40, up or down`() {
        resolution("Test Door", door)
        found()
        set(a, 90f); set(b, 10f); set(c, -20f)
        enact("Test Door")
        assertEquals(40f, of(a), 0.001f)
        assertEquals(40f, of(b), 0.001f)
        assertEquals(40f, of(c), 0.001f)
    }

    @Test
    fun `open door makes the civilizations that did not know the City-State meet it`() {
        resolution("Test Door", door)
        found(meet = false)
        assertFalse(c.knows(cs))
        enact("Test Door")
        assertTrue(c.knows(cs))
        assertEquals(40f, of(c), 0.001f)
    }

    @Test
    fun `open door takes the alliance away and nobody can be the ally while it is in effect`() {
        resolution("Test Door", door)
        found()
        set(a, 90f); cs.allyCiv = a
        enact("Test Door")
        assertTrue(cs.noAlly)
        assertNull(cs.allyCiv)
        cs.getDiplomacyManager(b)!!.addInfluence(100f)
        assertEquals(140f, of(b), 0.001f)
        assertNull("above 60 and the most, and still no ally", cs.allyCiv)
    }

    @Test
    fun `a defeated civilization is not touched by open door`() {
        resolution("Test Door", door)
        found()
        set(c, 10f)
        c.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue(c.isDefeated())
        enact("Test Door")
        assertEquals(10f, of(c), 0.001f)
        assertEquals(40f, of(a), 0.001f)
    }

    @Test
    fun `when open door is repealed the City-State can have an ally again and the influence stays`() {
        resolution("Test Door", door)
        found()
        set(b, 90f)
        val active = enact("Test Door")
        assertNull(cs.allyCiv)
        repeal(active)
        assertFalse(cs.noAlly)
        assertEquals("what the door set is not undone", 40f, of(b), 0.001f)
        cs.getDiplomacyManager(b)!!.addInfluence(30f)
        assertEquals(b, cs.allyCiv)
    }

    @Test
    fun `when open door stops being valid because its City-State is defeated the flag is cleared`() {
        resolution("Test Door", door)
        found()
        enact("Test Door")
        assertTrue(cs.noAlly)
        cs.hasEverOwnedOriginalCapital = true
        cs.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        league.doTurn()
        assertTrue("the resolution is dropped", league.activeResolutions.isEmpty())
        assertFalse(cs.noAlly)
    }

    // ---- Sphere of Influence ----

    @Test
    fun `sphere of influence makes the proposer the ally whatever the influence`() {
        resolution("Test Sphere", sphere)
        found()
        set(a, 0f); set(b, 80f); cs.allyCiv = b
        enact("Test Sphere")
        assertEquals(a, cs.permanentAllyCiv)
        assertEquals(a, cs.allyCiv)
        assertEquals("the influence is not changed", 0f, of(a), 0.001f)
    }

    @Test
    fun `with a sphere another civilization cannot take the alliance, however high its influence`() {
        resolution("Test Sphere", sphere)
        found()
        enact("Test Sphere")
        cs.getDiplomacyManager(b)!!.addInfluence(200f)
        assertEquals(a, cs.allyCiv)
    }

    @Test
    fun `sphere of influence makes peace between the City-State and the proposer when they are at war`() {
        resolution("Test Sphere", sphere)
        found()
        a.getDiplomacyManager(cs)!!.declareWar()
        assertTrue(a.isAtWarWith(cs))
        enact("Test Sphere")
        assertFalse(a.isAtWarWith(cs))
        assertEquals(a, cs.allyCiv)
    }

    @Test
    fun `at peace no peace is signed again`() {
        resolution("Test Sphere", sphere)
        found()
        // notifications are only kept for a human player; one that knows both the proposer and the City-State is told of a peace treaty between them
        val observer = game.addCiv(isPlayer = true)
        game.addCity(observer, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        observer.diplomacyFunctions.makeCivilizationsMeet(a)
        observer.diplomacyFunctions.makeCivilizationsMeet(cs)
        a.getDiplomacyManager(cs)!!.declareWar()
        val before = observer.notifications.size
        enact("Test Sphere")
        assertTrue("the setup: the peace that ends the war is announced to the observer", observer.notifications.size > before)
        val afterWar = observer.notifications.size
        enact("Test Sphere")
        assertEquals("at peace nothing is signed again", afterWar, observer.notifications.size)
    }

    @Test
    fun `a proposer that is not alive gets nothing from sphere of influence`() {
        resolution("Test Sphere", sphere)
        found()
        a.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue(a.isDefeated())
        enact("Test Sphere")
        assertNull(cs.permanentAllyCiv)
    }

    @Test
    fun `an automatic proposal has no proposer and no sphere`() {
        resolution("Test Sphere", sphere)
        found()
        enact("Test Sphere", proposer = null)
        assertNull(cs.permanentAllyCiv)
    }

    @Test
    fun `when sphere of influence is repealed the permanent ally ends and the ally is the one the influence says`() {
        resolution("Test Sphere", sphere)
        found()
        set(a, 0f); set(b, 80f)
        val active = enact("Test Sphere")
        assertEquals(a, cs.allyCiv)
        repeal(active)
        assertNull(cs.permanentAllyCiv)
        cs.cityStateFunctions.updateAllyCivForCityState()
        assertEquals("b has the most from 60 on", b, cs.allyCiv)
    }

    // ---- both ----

    @Test
    fun `the effects are only with the switch of the league`() {
        resolution("Test Door", door)
        resolution("Test Sphere", sphere)
        found()
        constants.leagueSessionRulesLikeVp = false
        set(a, 90f)
        enact("Test Door")
        enact("Test Sphere")
        assertFalse(cs.noAlly)
        assertNull(cs.permanentAllyCiv)
        assertEquals(90f, of(a), 0.001f)
    }

    @Test
    fun `a resolution with neither unique changes nothing`() {
        resolution("Test Plain")
        found()
        set(a, 90f)
        enact("Test Plain")
        assertFalse(cs.noAlly)
        assertNull(cs.permanentAllyCiv)
        assertEquals(90f, of(a), 0.001f)
    }

    @Test
    fun `a choice that is no City-State does nothing`() {
        resolution("Test Door", door)
        resolution("Test Sphere", sphere)
        found()
        enact("Test Door", choice = b.civID)
        enact("Test Sphere", choice = b.civID)
        assertFalse(cs.noAlly)
        assertNull(cs.permanentAllyCiv)
    }

    @Test
    fun `one run through the League itself, proposed, voted, enacted, then repealed`() {
        resolution("Test Sphere", sphere)
        found()
        set(a, 0f); set(b, 80f)
        // the session starts when nobody has a proposal left: only a proposes
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(a.civID).proposalsLeft = 1
        league.proposeEnact(gameInfo.ruleset.resolutions["Test Sphere"]!!, cs.civID, a)
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue(league.inSession)
        val proposal = league.proposals.first { it.resolution == "Test Sphere" }
        league.vote(proposal.id, a, league.getDelegatesLeft(a), "Yes")
        for (civ in listOf(b, c)) league.abstainRemaining(civ)
        league.doTurn()
        assertEquals(a, cs.permanentAllyCiv)
        assertEquals(a, cs.allyCiv)

        val active = league.activeResolutions.single()
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.hostProposing = false
        league.proposeRepeal(active.id, b)
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue(league.inSession)
        val repeal = league.proposals.first { it.repeal }
        league.vote(repeal.id, b, league.getDelegatesLeft(b), "Yes")
        league.abstainRemaining(a)
        league.abstainRemaining(c)
        league.doTurn()
        assertTrue(league.activeResolutions.isEmpty())
        assertNull(cs.permanentAllyCiv)
    }

    // ---- the ruleset validator ----

    @Test
    fun `a resolution of these effects that does not choose a City-State is an error of the ruleset`() {
        val wrong = resolution("Wrong Door", door, decision = ResolutionDecision.MajorCiv)
        val right = resolution("Right Sphere", sphere)
        val errors = RulesetValidator.create(game.ruleset, true).getErrorList()
        assertTrue(errors.any { "Wrong Door" in it.text && "CityState" in it.text })
        assertFalse(errors.any { "Right Sphere" in it.text && "CityState" in it.text })
        assertTrue(wrong.opensDoor && right.putsInSphere)
    }
}
