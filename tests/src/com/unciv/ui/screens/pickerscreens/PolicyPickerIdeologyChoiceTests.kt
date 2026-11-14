package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Policy
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.components.input.ActivationListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A human choosing an ideology in the policy screen, then a tenet of it: the confirmation, what it costs and what it does */
@RunWith(GdxTestRunner::class)
class PolicyPickerIdeologyChoiceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private var screen: PolicyPickerScreen? = null

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(4)
        civ = game.addCiv(isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        civ.tech.addTechnology("Refrigeration") // the first tech of the Modern era, before which Freedom needs three factories
        UiTestSupport.prepare(game)
        UiTestSupport.installWorldScreen(game, civ, UiTestSupport.hostScreen())
    }

    @After
    fun tearDown() {
        screen?.dispose()
    }

    private val firstIdeology get() = game.ruleset.policyBranches.values.first { it.isIdeology }

    private fun open(): PolicyPickerScreen {
        val picker = PolicyPickerScreen(civ, true, null, null)
        UiTestSupport.putOnScreenStack(picker)
        screen = picker
        return picker
    }

    /** The clickable boxes that show "Adopt" at the top of each branch, in the order of the branches */
    private fun adoptBoxes(picker: PolicyPickerScreen): List<Actor> =
        UiTestSupport.actors(picker.stage).filterIsInstance<Table>()
            .filter { it.listeners.any { listener -> listener is ActivationListener } && UiTestSupport.labelTexts(it) == listOf("Adopt") }

    private fun policyOf(button: Actor): Policy =
        button.javaClass.getMethod("getPolicy").apply { isAccessible = true }.invoke(button) as Policy

    private fun tenetButtons(picker: PolicyPickerScreen) =
        UiTestSupport.actors(picker.topTable).filter { it.javaClass.simpleName == "PolicyButton" }

    @Test
    fun `an ideology can be chosen once the era allows it, and the screen opens on the ideologies`() {
        assertTrue(civ.policies.canChooseIdeology())
        val picker = open()
        val labels = UiTestSupport.labelTexts(picker.topTable).map { it.uppercase() }
        assertTrue(labels.toString(), labels.containsAll(listOf("FREEDOM", "AUTOCRACY", "ORDER")))
        assertFalse(labels.toString(), "TRADITION" in labels)
        assertEquals(3, adoptBoxes(picker).size)
    }

    @Test
    fun `choosing an ideology asks first, costs no culture and adopts it`() {
        civ.policies.storedCulture = 10
        val picker = open()
        val ideology = firstIdeology

        UiTestSupport.tap(adoptBoxes(picker).first())
        val question = UiTestSupport.labelTexts(picker.stage).firstOrNull { "as your ideology" in it }
        assertTrue(UiTestSupport.labelTexts(picker.stage).toString(), question != null && ideology.name in question)
        assertNull("nothing happens before it is confirmed", civ.policies.getIdeology())

        UiTestSupport.tap(UiTestSupport.button(picker.stage, "Adopt"))
        assertEquals(ideology, civ.policies.getIdeology())
        assertEquals("no culture is paid", 10, civ.policies.storedCulture)
        assertFalse(civ.policies.canChooseIdeology())
    }

    @Test
    fun `with a free tenet the screen opens on the ideology and the tenet can be adopted`() {
        civ.policies.adopt(firstIdeology, false)
        civ.policies.freeTenets = 1
        val picker = open()
        assertTrue("on the ideologies", UiTestSupport.labelTexts(picker.topTable).map { it.uppercase() }.contains(firstIdeology.name.uppercase()))
        val tenet = tenetButtons(picker).first { civ.policies.canAdopt(policyOf(it)) && policyOf(it).isTenet }

        UiTestSupport.tap(tenet)
        assertFalse("the button to adopt it is enabled", picker.rightSideButton.isDisabled)
        UiTestSupport.tap(picker.rightSideButton)

        val name = policyOf(tenet).name
        val deadline = System.currentTimeMillis() + 5000  // the policy is adopted on another thread
        while (!civ.policies.isAdopted(name) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(civ.policies.isAdopted(name))
        assertEquals("the free tenet is used up", 0, civ.policies.freeTenets)
    }
}
