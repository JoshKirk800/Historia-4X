package com.unciv.ui.screens.worldscreen.unit.actions

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The unit action buttons of the world screen: a military unit far from a known city-state can be sent to it as a gift */
@RunWith(GdxTestRunner::class)
class GiftUnitActionTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var cityState: Civilization
    private lateinit var warrior: MapUnit
    private lateinit var table: UnitActionsTable

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(8)
        us = game.addCiv(isPlayer = true)
        cityState = game.addCiv(cityStateType = "Cultured")
        game.gameInfo.currentPlayerCiv = us
        game.addCity(us, game.getTile(-6, 0), replacePalace = true)
        game.addCity(cityState, game.getTile(5, 0), replacePalace = true)
        us.diplomacyFunctions.makeCivilizationsMeet(cityState)
        warrior = game.addUnit("Warrior", us, game.getTile(0, 0))
        UiTestSupport.prepare(game)
        table = UnitActionsTable(UiTestSupport.installWorldScreen(game, us, UiTestSupport.hostScreen()))
    }

    /** The table shows a few buttons per page: pages through until one shows [text] */
    private fun findButton(text: String): Button? {
        table.update(warrior)
        repeat(10) {
            UiTestSupport.actors(table).filterIsInstance<Button>().firstOrNull { UiTestSupport.labelTexts(it).contains(text) }?.let { return it }
            table.changePage(1, warrior)
        }
        return null
    }

    @Test
    fun `the button to gift the unit to a known city-state is shown and works`() {
        val gift = findButton("Gift unit to ${cityState.civName}")
        assertNotNull(UiTestSupport.actors(table).map { UiTestSupport.labelTexts(it) }.toString(), gift)
        assertFalse(gift!!.isDisabled)

        UiTestSupport.tap(gift)

        assertTrue("the unit has left", warrior.isDestroyed || warrior !in us.units.getCivUnits())
    }

    @Test
    fun `a city-state that has not been met gets no button`() {
        val stranger = game.addCiv(cityStateType = "Cultured")
        game.addCity(stranger, game.getTile(0, 5), replacePalace = true)
        findButton("Gift unit to ${cityState.civName}")
        assertTrue(UiTestSupport.actors(table).filterIsInstance<Button>().none { UiTestSupport.labelTexts(it).contains("Gift unit to ${stranger.civName}") })
    }
}
