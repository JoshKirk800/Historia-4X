package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.files.UncivFiles
import com.unciv.models.Counter
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.screens.basescreen.BaseScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The screens in which a human picks a pantheon, founds a religion, enhances it and adds a Reformation belief: what they offer and what OK does */
@RunWith(GdxTestRunner::class)
class ReligionPickerScreenTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private var screen: BaseScreen? = null

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(5)
        civ = game.addCiv(isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        UiTestSupport.prepare(game)
    }

    @After
    fun tearDown() {
        screen?.dispose()
    }

    private fun <T : BaseScreen> shown(picker: T): T {
        screen = picker
        UiTestSupport.installWorldScreen(game, civ, UiTestSupport.hostScreen())  // the OK button is only enabled in the player's turn
        UiTestSupport.putOnScreenStack(picker)
        return picker
    }

    private val manager get() = civ.religionManager

    private fun setState(state: ReligionState) {
        ReligionManager::class.java.getDeclaredField("religionState").apply { isAccessible = true }.set(manager, state)
    }

    /** The button of the belief [name] in the lists of a picker (its nameLabel is the first label) */
    private fun beliefButton(picker: BaseScreen, name: String): Button =
        UiTestSupport.actors(picker.stage).filterIsInstance<Button>().first { button -> UiTestSupport.labelTexts(button).firstOrNull() == name || UiTestSupport.labelTexts(button).getOrNull(1) == name }

    /** The belief buttons that can be pressed */
    private fun pressable(picker: BaseScreen): List<Button> =
        UiTestSupport.actors(picker.stage).filterIsInstance<Button>()
            .filter { it.touchable == Touchable.enabled && !it.isDisabled && UiTestSupport.labelTexts(it).size >= 2 && it.name == null }

    //region Pantheon
    @Test
    fun `the pantheon picker lists the pantheon beliefs, and the chosen one is adopted`() {
        manager.storedFaith = manager.faithForPantheon()
        assertTrue(manager.canFoundOrExpandPantheon())
        val picker = shown(PantheonPickerScreen(civ))
        val belief = game.ruleset.beliefs.values.first { it.type == BeliefType.Pantheon }
        assertTrue("the OK button waits for a choice", picker.rightSideButton.isDisabled)

        UiTestSupport.tap(beliefButton(picker, belief.name))
        val texts = UiTestSupport.labelTexts(picker.rightSideButton)
        assertEquals(listOf("Follow ${belief.name}"), texts)
        UiTestSupport.tap(picker.rightSideButton)

        assertEquals(ReligionState.Pantheon, manager.religionState)
        assertTrue(manager.religion!!.hasBelief(belief.name))
    }

    @Test
    fun `a pantheon belief another civilization follows is not offered`() {
        val other = game.addCiv()
        val taken = game.ruleset.beliefs.values.first { it.type == BeliefType.Pantheon }
        game.addReligion(other).addBeliefs(listOf(taken))
        manager.storedFaith = manager.faithForPantheon()
        val picker = shown(PantheonPickerScreen(civ))
        val button = beliefButton(picker, taken.name)
        assertEquals(Touchable.disabled, button.touchable)
    }
    //endregion

    //region Founding, enhancing, reforming
    private fun pressIconNameAndBeliefs(picker: ReligiousBeliefsPickerScreen, types: List<String>) {
        val icon = UiTestSupport.actors(picker.stage).filterIsInstance<Button>()
            .first { it.touchable == Touchable.enabled && !it.isDisabled && UiTestSupport.labelTexts(it).isEmpty() && it.listeners.any { l -> l is ActivationListener } }
        UiTestSupport.tap(icon)
        for (type in types) {
            UiTestSupport.tap(UiTestSupport.actors(picker.stage).filterIsInstance<Button>()
                .first { button -> UiTestSupport.labelTexts(button).any { it == "Choose a $type belief!" } })
            UiTestSupport.tap(pressable(picker).first { UiTestSupport.labelTexts(it).firstOrNull() == type })
        }
    }

    @Test
    fun `founding a religion needs an icon and all the beliefs, then OK founds it`() {
        val pantheon = game.ruleset.beliefs.values.first { it.type == BeliefType.Pantheon }
        manager.chooseBeliefs(listOf(pantheon), useFreeBeliefs = true)
        manager.foundReligion(game.addUnit("Great Prophet", civ, civ.getCapital()!!.getCenterTile()))
        assertEquals(ReligionState.FoundingReligion, manager.religionState)
        val toChoose = manager.getBeliefsToChooseAtFounding()
        assertTrue(toChoose.toString(), toChoose.sumValues() >= 2)
        val picker = shown(ReligiousBeliefsPickerScreen(civ, toChoose, pickIconAndName = true))
        assertTrue("nothing chosen yet", picker.rightSideButton.isDisabled)
        val types = toChoose.flatMap { entry -> List(entry.value) { entry.key.name } }

        pressIconNameAndBeliefs(picker, types)

        assertFalse("everything chosen", picker.rightSideButton.isDisabled)
        UiTestSupport.tap(picker.rightSideButton)
        assertEquals(ReligionState.Religion, manager.religionState)
        assertEquals("the pantheon and the new ones", 1 + types.size, manager.religion!!.getAllBeliefsOrdered().count())
    }

    @Test
    fun `enhancing a religion adds the follower and enhancer beliefs`() {
        val religion = game.addReligion(civ)
        religion.addBeliefs(listOf(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder }))
        setState(ReligionState.EnhancingReligion)
        val toChoose = manager.getBeliefsToChooseAtEnhancing()
        assertTrue(toChoose.toString(), toChoose.sumValues() >= 1)
        val picker = shown(ReligiousBeliefsPickerScreen(civ, toChoose, pickIconAndName = false))
        val before = religion.getAllBeliefsOrdered().count()
        val types = toChoose.flatMap { entry -> List(entry.value) { entry.key.name } }
        for (type in types) {
            UiTestSupport.tap(UiTestSupport.actors(picker.stage).filterIsInstance<Button>()
                .first { button -> UiTestSupport.labelTexts(button).any { it == "Choose a $type belief!" } })
            UiTestSupport.tap(pressable(picker).first { UiTestSupport.labelTexts(it).firstOrNull() == type })
        }
        assertTrue(UiTestSupport.labelTexts(picker.rightSideButton).toString(), UiTestSupport.labelTexts(picker.rightSideButton).single().startsWith("Enhance"))
        UiTestSupport.tap(picker.rightSideButton)
        assertEquals(ReligionState.EnhancedReligion, manager.religionState)
        assertEquals(before + types.size, religion.getAllBeliefsOrdered().count())
    }

    @Test
    fun `a Reformation belief is picked from the Reformation beliefs and added`() {
        val religion = game.addReligion(civ)
        religion.addBeliefs(listOf(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder }))
        setState(ReligionState.EnhancedReligion)
        val picker = shown(ReligiousBeliefsPickerScreen(civ, Counter<BeliefType>().apply { add(BeliefType.Reformation, 1) }, pickIconAndName = false))
        UiTestSupport.tap(UiTestSupport.actors(picker.stage).filterIsInstance<Button>()
            .first { button -> UiTestSupport.labelTexts(button).any { it == "Choose a Reformation belief!" } })
        val offered = pressable(picker)
        assertTrue("only Reformation beliefs", offered.isNotEmpty())
        UiTestSupport.tap(offered.first())
        assertEquals(listOf("Add a Reformation belief to ${religion.getReligionDisplayName()}"), UiTestSupport.labelTexts(picker.rightSideButton))
        UiTestSupport.tap(picker.rightSideButton)
        assertEquals(1, religion.getAllBeliefsOrdered().count { it.type == BeliefType.Reformation })
        assertNotNull(manager.religion)
    }
    //endregion
}
