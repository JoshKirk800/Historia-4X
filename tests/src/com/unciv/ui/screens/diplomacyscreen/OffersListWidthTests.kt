package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.view.GameView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class OffersListWidthTests {
    @Test
    fun `an offer with a long text wraps instead of making the list scroll sideways`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(4)
        val us = game.addCiv(isPlayer = true)
        val other = game.addCiv()
        game.gameInfo.currentPlayerCiv = us
        UiTestSupport.prepare(game)
        val view = GameView(game.gameInfo, us)
        val longName = "Resolution to ban the trade of every luxury resource between the members of the World Congress"
        val offers = TradeOffersList().apply { add(TradeOffer(longName, TradeOfferType.Introduction, 1, 0)) }
        val maxLabelWidth = 300f
        val list = OffersListScroll("width", maxLabelWidth) {}
        list.update(offers, TradeOffersList(), ourCiv = view.civView, theirCiv = view.getForeignCivView(other))
        list.table.pack()
        // the label, the icon cell and the paddings add to the label's width
        assertTrue("list is ${list.table.prefWidth} wide", list.table.prefWidth <= maxLabelWidth + 60f)
    }
}
