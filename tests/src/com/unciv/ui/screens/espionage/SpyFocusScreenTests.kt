package com.unciv.ui.screens.espionage

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/**
 *  A human chooses the counterspy focus of a spy on the espionage screen, through the real screen and its buttons: the Focus button of a spy that counters spies in an own city (only with the espionage of
 *  Vox Populi), the popup with the focuses of the ruleset, the choice, and the focuses that cannot be chosen yet with their reason. A spy that has just arrived waits for the choice ([Spy.focusPending]).
 */
@RunWith(GdxTestRunner::class)
class SpyFocusScreenTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var other: Civilization
    private lateinit var ownCity: City
    private lateinit var foreignCity: City
    private lateinit var spy: Spy
    private lateinit var world: WorldScreen
    private var screen: EspionageScreen? = null
    private val added = ArrayList<String>()

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(6)
        us = game.addCiv(isPlayer = true)
        other = game.addCiv()
        game.gameInfo.currentPlayerCiv = us
        game.gameInfo.gameParameters.espionageEnabled = true
        game.gameInfo.turns = 10
        ownCity = game.addCity(us, game.tileMap[0, 0], replacePalace = true)
        foreignCity = game.addCity(other, game.tileMap[0, 4], replacePalace = true)
        us.diplomacyFunctions.makeCivilizationsMeet(other)
        us.getDiplomacyManager(other)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(us, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        spy = us.espionageManager.addSpy()
        UiTestSupport.prepare(game)
        world = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(world.canChangeState).thenReturn(true)
    }

    @After
    fun tearDown() {
        screen?.dispose()
        for (name in added) game.ruleset.spyMissions.remove(name)
    }

    private fun focus(name: String, tech: String? = null, vararg uniques: String): SpyMission {
        val mission = SpyMission().apply { this.name = name; isFocus = true; requiredTech = tech; this.uniques = ArrayList(uniques.toList()) }
        game.ruleset.spyMissions[name] = mission
        added += name
        return mission
    }

    /** the spy has arrived in [city] and counters spies there; its owner is asked for a focus */
    private fun arrive(city: City) {
        spy.moveTo(city)
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) us.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, spy.action)
    }

    private fun open(): EspionageScreen = EspionageScreen(us, world).also { screen = it }

    private fun focusButton(s: EspionageScreen) = UiTestSupport.actors(s.stage).filterIsInstance<TextButton>().firstOrNull { UiTestSupport.labelTexts(it).contains("Focus") }

    private fun texts(s: EspionageScreen) = UiTestSupport.labelTexts(s.stage)

    @Test
    fun `a spy that has arrived in an own city has a Focus button, and choosing a focus ends the wait`() {
        val watch = focus("Watch")
        arrive(ownCity)
        assertTrue("the spy waits for its choice", spy.focusPending)
        val s = open()
        val button = focusButton(s)
        assertTrue("there is a Focus button", button != null)
        UiTestSupport.tap(button!!)
        assertTrue("the popup lists the focus: ${texts(s)}", texts(s).contains(watch.name))
        UiTestSupport.tap(UiTestSupport.button(s.stage, watch.name))
        assertEquals(watch.name, spy.focus)
        assertFalse("the wait is over", spy.focusPending)
    }

    @Test
    fun `the popup says which focus is chosen and which cannot be chosen yet and why`() {
        focus("Watch")
        focus("Guard", tech = "Gunpowder")   // the owner does not have it
        arrive(ownCity)
        val s = open()
        UiTestSupport.tap(focusButton(s)!!)
        val guard = UiTestSupport.button(s.stage, "Guard")
        assertTrue("a focus that needs a technology is disabled", guard.isDisabled)
        assertTrue("with its reason: ${texts(s)}", texts(s).any { "Gunpowder" in it })
        assertFalse(UiTestSupport.button(s.stage, "Watch").isDisabled)
        assertTrue(texts(s).contains("No focus chosen yet"))
    }

    @Test
    fun `a chosen focus is named in the popup`() {
        val watch = focus("Watch")
        arrive(ownCity)
        assertTrue(spy.chooseFocus(watch))
        val s = open()
        UiTestSupport.tap(focusButton(s)!!)
        assertTrue(texts(s).any { it.contains("Now:") && it.contains("Watch") })
    }

    @Test
    fun `a spy that waits with nothing to choose is told so, and it can still go home`() {
        focus("Guard", tech = "Gunpowder")
        arrive(ownCity)
        assertTrue(spy.focusPending)
        val s = open()
        UiTestSupport.tap(focusButton(s)!!)
        assertTrue("it says none can be chosen: ${texts(s)}", texts(s).any { "None can be chosen yet" in it })
        spy.moveTo(null)
        assertNull("the hideout is the way out", spy.getCityOrNull())
        assertFalse(spy.focusPending)
    }

    @Test
    fun `a ruleset with no focuses says so`() {
        arrive(ownCity)
        val s = open()
        UiTestSupport.tap(focusButton(s)!!)
        assertTrue(texts(s).any { "no counterspy focuses" in it })
    }

    @Test
    fun `a spy still travelling to an own city has no Focus button until it arrives and counters spies`() {
        focus("Watch")
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 3, travelTurnsByProximity = listOf(0, 0, 0, 0))
        spy.moveTo(ownCity)
        assertEquals(SpyAction.Moving, spy.action)
        assertNull("on the way", focusButton(open().also { screen = it }))
        screen?.dispose()
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) us.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, spy.action)
        assertTrue("there", focusButton(open()) != null)
    }

    @Test
    fun `there is no Focus button without the espionage of Vox Populi`() {
        focus("Watch")
        arrive(ownCity)
        game.ruleset.modOptions.constants.espionageLikeVp = false
        assertNull(focusButton(open()))
    }

    @Test
    fun `a spy gathering intel in a foreign city has Missions and no Focus button`() {
        focus("Watch")
        spy.moveTo(foreignCity)
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) us.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, spy.action)
        val s = open()
        assertNull(focusButton(s))
        assertTrue(UiTestSupport.actors(s.stage).filterIsInstance<TextButton>().any { UiTestSupport.labelTexts(it).contains("Missions") })
    }

    @Test
    fun `a spectator cannot choose the focus of the spies of the civilization it watches`() {
        focus("Watch")
        arrive(ownCity)
        Mockito.`when`(world.canChangeState).thenReturn(false)
        assertNull(focusButton(open()))
    }

    @Test
    fun `the missions popup lists missions and not focuses`() {
        focus("Watch")
        val steal = SpyMission().apply { name = "A mission"; networkPoints = 0; cityFilter = "All" }
        game.ruleset.spyMissions[steal.name] = steal
        added += steal.name
        spy.moveTo(foreignCity)
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) us.espionageManager.endTurn()
        val s = open()
        UiTestSupport.tap(UiTestSupport.actors(s.stage).filterIsInstance<TextButton>().first { UiTestSupport.labelTexts(it).contains("Missions") })
        val all = texts(s)
        assertTrue("the mission is listed: $all", all.any { it.startsWith("A mission") })
        assertFalse("the focus is not: $all", all.any { it.startsWith("Watch") })
    }
}
