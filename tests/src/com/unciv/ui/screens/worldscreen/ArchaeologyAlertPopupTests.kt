package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.tile.ArchaeologyRecord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ArtifactClasses
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The popup that asks a human what to do with the finds of a completed archaeological dig */
@RunWith(GdxTestRunner::class)
class ArchaeologyAlertPopupTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var other: Civilization
    private lateinit var city: City
    private lateinit var tile: Tile
    private lateinit var world: WorldScreen

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(8)
        us = game.addCiv(isPlayer = true)
        other = game.addCiv()
        game.gameInfo.currentPlayerCiv = us
        city = game.addCity(us, game.tileMap[0, 0], replacePalace = true)
        game.addCity(other, game.tileMap[0, 6], replacePalace = true)
        us.diplomacyFunctions.makeCivilizationsMeet(other)
        UiTestSupport.prepare(game)
        world = UiTestSupport.installWorldScreen(game, us, UiTestSupport.hostScreen())
        tile = game.tileMap[0, -3]
        tile.archaeologyRecord = ArchaeologyRecord(ArtifactClasses.MeleeBattle, "Ancient era", other.civName, us.civName, null)
        tile.setImprovement("Archaeological Dig")
    }

    private fun popup() = AlertPopup(world, PopupAlert(AlertType.ArchaeologyChoice, "${tile.position.x.toInt()},${tile.position.y.toInt()}"))

    private fun texts(popup: AlertPopup) = UiTestSupport.labelTexts(popup)

    @Test
    fun `the popup says what was found and offers a Landmark when no gallery has room`() {
        val popup = popup()
        val texts = texts(popup)
        assertTrue(texts.toString(), texts.any { it.startsWith("The excavation has found:") && "Ancient" in it })
        assertTrue(texts.toString(), "Build a Landmark" in texts)
        assertTrue(texts.toString(), texts.none { it.startsWith("Take the artifact") })
    }

    @Test
    fun `with a free slot each civilization the artifact is attributed to is offered`() {
        city.cityConstructions.addBuilding(game.createBuilding("Has [2] [Art/Artifact] Great Work slots"))
        val texts = texts(popup())
        assertTrue(texts.toString(), "Take the artifact, attributed to ${other.civName}" in texts)
        assertTrue(texts.toString(), "Take the artifact, attributed to ${us.civName}" in texts)
    }

    @Test
    fun `choosing the artifact puts the Great Work into the city and ends the dig`() {
        city.cityConstructions.addBuilding(game.createBuilding("Has [2] [Art/Artifact] Great Work slots"))
        val popup = popup()
        UiTestSupport.tap(UiTestSupport.button(popup, "Take the artifact, attributed to ${other.civName}"))
        assertEquals(1, city.greatWorks.getWorks().count())
        assertFalse("the dig is over", tile.improvement == "Archaeological Dig")
    }

    @Test
    fun `choosing a Landmark builds it on the tile`() {
        val popup = popup()
        UiTestSupport.tap(UiTestSupport.button(popup, "Build a Landmark"))
        assertEquals("Landmark", tile.improvement)
    }
}
