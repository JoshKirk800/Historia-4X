package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The spy AI of the Network Points model (CvEspionageAI::DoTurn and PerformSpyMissions, V:CvEspionageClasses.cpp:7723-7800 and 7999-8181; EvaluateDefensiveSpies, 10338-10363). V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Only with [ModConstants.espionageLikeVp]; with it off the original's AI is unchanged. These tests say what the loop does with a mission that can be done, waits for or cannot be done; which of
 *  several missions scores best is [EspionageScoringTests]'. [a] is an AI civilization, [b] another whose capital it spies on.
 */
@RunWith(GdxTestRunner::class)
class VpEspionageAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCity: City
    private lateinit var bCity: City
    private lateinit var ai: EspionageAutomation
    private val addedMissions = ArrayList<String>()

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        game.gameInfo.turns = 50
        a = game.addCiv()
        b = game.addCiv()
        aCity = game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        ai = EspionageAutomation(a)
    }

    @After
    fun tearDown() {
        for (name in addedMissions) game.ruleset.spyMissions.remove(name)
    }

    private fun steal(points: Int = 100): SpyMission {
        val mission = SpyMission().apply {
            name = "Steal a Technology"
            networkPoints = points
            cityFilter = "Capital"
            identifyChance = 0
            killChance = 0
            uniques = arrayListOf("Steal [1] random technology/technologies the target knows and we can research")
        }
        game.ruleset.spyMissions[mission.name] = mission
        addedMissions += mission.name
        return mission
    }

    /** A spy of [a] that gathers intel in [bCity] */
    private fun gathering(): Spy {
        val spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, spy.action)
        return spy
    }

    @Test
    fun `an AI spy with the points and something to steal does Steal a Technology`() {
        steal(100)
        b.tech.addTechnology("Pottery")
        val spy = gathering()
        spy.addNetworkPoints(100)
        val before = a.tech.researchedTechnologies.size

        ai.automateSpies()

        assertEquals("the technology was stolen", before + 1, a.tech.researchedTechnologies.size)
        assertEquals("the spy did a mission this turn", 50, spy.lastMissionTurn)
        assertNull("the next mission is chosen afresh", spy.targetMission)
    }

    @Test
    fun `a spy that has not the points yet keeps its mission and keeps working`() {
        steal(1000)
        b.tech.addTechnology("Pottery")
        val spy = gathering()
        spy.addNetworkPoints(50)
        val before = a.tech.researchedTechnologies.size

        ai.automateSpies()

        assertEquals("nothing stolen", before, a.tech.researchedTechnologies.size)
        assertEquals("it works towards the mission", "Steal a Technology", spy.targetMission)
        assertEquals("and stays", SpyAction.GatheringIntel, spy.action)
        assertFalse("it is not sent home for being short of points", spy.evaluateReassignment)
    }

    @Test
    fun `a spy that must wait after its last mission works towards the next and does not act`() {
        steal(100)
        b.tech.addTechnology("Pottery")
        val spy = gathering()
        spy.finishMission(game.gameInfo.turns)    // did a mission this very turn
        spy.addNetworkPoints(100)
        val before = a.tech.researchedTechnologies.size

        ai.automateSpies()

        assertEquals("no mission during the cooldown", before, a.tech.researchedTechnologies.size)
        assertEquals("the mission it did before is the last one", 50, spy.lastMissionTurn)
        assertEquals("it works towards the next, as it is not refused for waiting", "Steal a Technology", spy.targetMission)
    }

    @Test
    fun `a spy whose mission can no longer be done drops it and does nothing`() {
        steal(100)
        // b has nothing a lacks: there is no technology to steal, so the mission cannot be done at all
        val spy = gathering()
        spy.addNetworkPoints(100)
        spy.targetMission = "Steal a Technology"

        ai.automateSpies()

        assertNull("it gave the mission up", spy.targetMission)
        assertEquals("and did nothing", 0, spy.lastMissionTurn)
    }

    @Test
    fun `a spy with no mission to work towards gives none a target and does nothing`() {
        val spy = gathering()      // the ruleset has no missions
        ai.automateSpies()
        assertNull(spy.targetMission)
        assertEquals(0, spy.lastMissionTurn)
    }

    @Test
    fun `a spy making slow progress does a mission it can do even if another scores better`() {
        // the mission it works towards: out of reach (1000 points, 50 stored), scoring 28 in this setup, above the 20 that is kept
        val expensive = steal(1000)
        // one it can do now but that does nothing, so it scores 0 and does not replace the target: only the slow-progress rule picks it
        val idle = SpyMission().apply { name = "Idle"; networkPoints = 10; cityFilter = "All"; identifyChance = 0; killChance = 0 }
        game.ruleset.spyMissions[idle.name] = idle
        addedMissions += idle.name
        b.tech.addTechnology("Pottery")
        game.ruleset.modOptions.constants.espionage =
            game.ruleset.modOptions.constants.espionage.copy(networkPointsBase = 5)   // a rate of 4, at most 15: slow progress
        val spy = gathering()
        spy.addNetworkPoints(50)
        spy.targetMission = expensive.name
        assertEquals("the setup of the scores", 4, a.espionageManager.networkPointsPerTurn(spy, bCity))
        assertEquals(28, EspionageScoring.missionScore(a, spy, bCity, expensive))
        assertEquals(0, EspionageScoring.missionScore(a, spy, bCity, idle))

        ai.automateSpies()

        assertEquals("the mission that could be done was done, by the spy, this turn", 50, spy.lastMissionTurn)
        assertNull("and the target it could not reach is dropped", spy.targetMission)
    }

    @Test
    fun `a spy keeps a mission that scores 20 and gives one up that scores less, as the original does`() {
        // the setup of the scores in EspionageScoringTests: 41 points a turn, one technology to steal, 1000 points score 20 and 1500 points score 9 (V:8063, the floor of 20)
        b.tech.addTechnology("Pottery")
        val kept = steal(1000)
        val spy = gathering()
        spy.targetMission = kept.name
        assertEquals(20, EspionageScoring.missionScore(a, spy, bCity, kept))
        ai.automateSpies()
        assertEquals("a mission that scores 20 is kept", "Steal a Technology", spy.targetMission)
        assertFalse(spy.evaluateReassignment)

        game.ruleset.spyMissions["Steal a Technology"]!!.networkPoints = 1500
        assertEquals(9, EspionageScoring.missionScore(a, spy, bCity, kept))
        ai.automateSpies()
        assertNull("one that scores 9 is given up", spy.targetMission)
        assertEquals("the spy did nothing and it is not at its post for a mission", 0, spy.lastMissionTurn)
    }

    @Test
    fun `a spy replaces the mission it works towards by one that scores better and does it`() {
        steal(1000)                                  // out of reach: scores 28
        val cheap = SpyMission().apply {
            name = "Cheap"; networkPoints = 10; cityFilter = "All"; identifyChance = 0; killChance = 0
            uniques = arrayListOf("Steal [1] random technology/technologies the target knows and we can research")
        }
        game.ruleset.spyMissions[cheap.name] = cheap
        addedMissions += cheap.name
        b.tech.addTechnology("Pottery")
        game.ruleset.modOptions.constants.espionage = game.ruleset.modOptions.constants.espionage.copy(networkPointsBase = 5)
        val spy = gathering()
        spy.addNetworkPoints(50)
        spy.targetMission = "Steal a Technology"
        val before = a.tech.researchedTechnologies.size

        ai.automateSpies()

        assertEquals("the cheaper mission scores 208 against 28, replaced the target and was done", before + 1, a.tech.researchedTechnologies.size)
        assertEquals(50, spy.lastMissionTurn)
        assertNull(spy.targetMission)
    }

    @Test
    fun `slow progress and nothing else to do gives the mission up`() {
        steal(100000)
        b.tech.addTechnology("Pottery")
        game.ruleset.modOptions.constants.espionage = game.ruleset.modOptions.constants.espionage.copy(networkPointsBase = 1)
        val spy = gathering()
        spy.targetMission = "Steal a Technology"

        ai.automateSpies()

        assertNull("slow, and nothing else to do: the unreachable mission is given up", spy.targetMission)
        assertEquals("and nothing was done", 0, spy.lastMissionTurn)
    }

    @Test
    fun `only one spy acts in a turn, as in the original loop`() {
        steal(100)
        b.tech.addTechnology("Pottery")
        b.tech.addTechnology("Mining")
        val otherCity = game.addCity(b, game.tileMap[3, 5])
        val first = gathering()
        val second = a.espionageManager.addSpy().also { it.moveTo(otherCity); a.espionageManager.endTurn() }
        // the second city is no capital: use a mission that any city allows so both spies can act
        game.ruleset.spyMissions["Steal a Technology"]!!.cityFilter = "All"
        first.addNetworkPoints(100)
        second.addNetworkPoints(100)
        val before = a.tech.researchedTechnologies.size

        ai.automateSpies()

        assertEquals("one technology, not two", before + 1, a.tech.researchedTechnologies.size)
        assertEquals(1, listOf(first, second).count { it.lastMissionTurn == 50 })
    }

    @Test
    fun `an AI spy that arrives to counter spies chooses a focus and keeps it`() {
        val sentinel = SpyMission().apply { name = "Sentinel"; isFocus = true; cityFilter = "All" }
        val other = SpyMission().apply { name = "Other"; isFocus = true; cityFilter = "All" }
        game.ruleset.spyMissions[sentinel.name] = sentinel
        game.ruleset.spyMissions[other.name] = other
        addedMissions += listOf(sentinel.name, other.name)
        val spy = a.espionageManager.addSpy()
        spy.moveTo(aCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, spy.action)
        assertFalse("an AI does not wait for a choice", spy.focusPending)
        assertNull(spy.focus)

        ai.automateSpies()
        val chosen = spy.focus
        assertNotNull("it chose one", chosen)
        assertEquals(chosen, aCity.espionage.getFocus()?.name)

        game.gameInfo.turns += 20
        ai.automateSpies()
        assertEquals("it keeps the focus it has", chosen, spy.focus)
    }

    @Test
    fun `an AI spy with no focus to choose from is taken out of the city and sent out again`() {
        // TriggerSpyFocusSetup: no best mission (none to choose from) extracts the spy (V:1531); it is then unassigned, and its owner's defense needs a spy in this city, so it travels there again
        val spy = a.espionageManager.addSpy()
        spy.moveTo(aCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, spy.action)
        ai.automateSpies()
        assertNull(spy.focus)
        assertEquals("it left and set out again, instead of staying with no focus", SpyAction.Moving, spy.action)
    }

    @Test
    fun `no coup is considered in the Network Points model`() {
        val cityState = game.addCiv(cityStateType = "Maritime")
        val csCity = game.addCity(cityState, game.tileMap[0, -5])
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.allyCiv = b
        val spy = a.espionageManager.addSpy()
        spy.moveTo(csCity)
        repeat(5) { a.espionageManager.endTurn() }

        repeat(30) { ai.automateSpies() }

        assertFalse("no coup in 30 turns", spy.action == SpyAction.Coup)
    }

    @Test
    fun `with the switch off the original AI is unchanged`() {
        game.ruleset.modOptions.constants.espionageLikeVp = false
        steal(100)
        b.tech.addTechnology("Pottery")
        val cityState = game.addCiv(cityStateType = "Maritime")
        val csCity = game.addCity(cityState, game.tileMap[0, -5])
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.allyCiv = b
        val spy = a.espionageManager.addSpy()
        spy.moveTo(csCity)
        var guard = 0
        while (!spy.isSetUp() && guard++ < 20) a.espionageManager.endTurn()

        assertTrue("the coup candidate rule of the original still holds", ai.isCoupCandidate(spy))
        // a spy of the original's model that gathers nothing: no mission is chosen and none is done without the switch
        val gatherer = a.espionageManager.addSpy()
        gatherer.moveTo(bCity)
        guard = 0
        while (!gatherer.isSetUp() && guard++ < 20) a.espionageManager.endTurn()
        assertNull(gatherer.targetMission)
        assertEquals(0, gatherer.lastMissionTurn)
        assertFalse("the original reconsiders a spy that watches a civilization, which the new model does not", gatherer.action == SpyAction.GatheringIntel)
    }
}
