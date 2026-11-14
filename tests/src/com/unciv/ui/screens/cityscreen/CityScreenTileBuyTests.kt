package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.view.GameView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Buying a tile in the city screen: the price on the button, the confirmation, and what happens to the gold and the tile */
@RunWith(GdxTestRunner::class)
class CityScreenTileBuyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City
    private lateinit var view: GameView
    private var screen: CityScreen? = null

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(6)
        civ = game.addCiv(isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        UiTestSupport.prepare(game)
        TileSetCache.loadTileSetConfigs()
        UiTestSupport.installWorldScreen(game, civ, UiTestSupport.hostScreen())
        view = GameView(game.gameInfo, civ)
    }

    @After
    fun tearDown() {
        screen?.dispose()
    }

    /** A tile the city does not own and may buy */
    private fun buyable(): Tile = city.getCenterTile().getTilesAtDistance(2).first { city.expansion.canBuyTile(it) }

    private fun open(selected: Tile): CityScreen =
        CityScreen(view.getCityView(city), null, view.getTile(selected)).also { screen = it }

    @Test
    fun `a tile next to the city offers its price, which the gold covers`() {
        val tile = buyable()
        val price = city.expansion.getGoldCostOfTile(tile)
        civ.addGold(price + 5 - civ.gold)
        val city = open(tile)

        val buy = UiTestSupport.actors(city.stage).filterIsInstance<TextButton>().firstOrNull { UiTestSupport.labelTexts(it).any { text -> text.startsWith("Buy for") } }
        assertNotNull(UiTestSupport.labelTexts(city.stage).toString(), buy)
        assertEquals(listOf("Buy for $price gold"), UiTestSupport.labelTexts(buy!!))
        assertFalse(buy.isDisabled)
    }

    @Test
    fun `without the gold the button is disabled`() {
        val tile = buyable()
        civ.addGold(-civ.gold)
        val screen = open(tile)
        val buy = UiTestSupport.actors(screen.stage).filterIsInstance<TextButton>().first { UiTestSupport.labelTexts(it).any { text -> text.startsWith("Buy for") } }
        assertTrue(buy.isDisabled)
    }

    @Test
    fun `buying asks first, then the city owns the tile and the gold is spent`() {
        val tile = buyable()
        val price = city.expansion.getGoldCostOfTile(tile)
        civ.addGold(price + 5 - civ.gold)
        val screen = open(tile)

        UiTestSupport.tap(UiTestSupport.actors(screen.stage).filterIsInstance<TextButton>().first { UiTestSupport.labelTexts(it).any { text -> text.startsWith("Buy for") } })
        val texts = UiTestSupport.labelTexts(screen.stage)
        assertTrue(texts.toString(), texts.any { "Would you like to purchase Tile for $price" in it })
        assertTrue("not before it is confirmed", tile.getOwner() == null)

        UiTestSupport.tap(UiTestSupport.button(screen.stage, "Purchase"))
        val deadline = System.currentTimeMillis() + 5000  // the purchase is made on another thread
        while (tile.getOwner() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(civ, tile.getOwner())
        assertEquals(city, tile.getCity())
        assertEquals(5, civ.gold)
    }
}
