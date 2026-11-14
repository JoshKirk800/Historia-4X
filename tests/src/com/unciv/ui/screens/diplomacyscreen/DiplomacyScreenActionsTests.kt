package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.IntrigueType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.screens.leaguescreens.LeagueUiFixture
import com.unciv.view.GameView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What a human sees and can do on the diplomacy screen of another civilization, with the espionage and World Congress features of Brave New World */
@RunWith(GdxTestRunner::class)
class DiplomacyScreenActionsTests {
    private var screen: DiplomacyScreen? = null

    @After
    fun tearDown() {
        screen?.dispose()
    }

    private fun open(game: TestGame, us: Civilization, other: Civilization): DiplomacyScreen {
        UiTestSupport.installWorldScreen(game, us, UiTestSupport.hostScreen())
        val view = GameView(game.gameInfo, us)
        return DiplomacyScreen(view.civView, view.getForeignCivView(other)).also { screen = it }
    }

    //region Share intrigue
    /** [us] met [plotter] and [target]; [target] is an AI major that [us] could warn about [plotter] */
    private class Intrigue(val game: TestGame) {
        val us: Civilization
        val target: Civilization
        val plotter: Civilization

        init {
            UncivGame.Current.files = UncivFiles(Gdx.files)
            game.makeHexagonalMap(8)
            us = game.addCiv(isPlayer = true)
            target = game.addCiv()
            plotter = game.addCiv()
            game.gameInfo.currentPlayerCiv = us
            game.addCity(us, game.tileMap[0, 0], replacePalace = true)
            game.addCity(target, game.tileMap[0, 5], replacePalace = true)
            game.addCity(plotter, game.tileMap[0, -5], replacePalace = true)
            for (civ in listOf(target, plotter)) us.diplomacyFunctions.makeCivilizationsMeet(civ)
            target.diplomacyFunctions.makeCivilizationsMeet(plotter)
            us.getDiplomacyManager(target)!!.makePeace()
            UiTestSupport.prepare(game)
        }

        fun uncover() {
            us.espionageManager.addIntrigueMessage(us, plotter, target, false, null, IntrigueType.Deception, null, false, null)
        }
    }

    private fun shareButtonText(f: Intrigue) = "Share intrigue with ${f.target.civName}"

    @Test
    fun `uncovered intrigue can be shared with the civilization it is aimed at`() {
        val f = Intrigue(TestGame(baseRuleset = BaseRuleset.Civ_V_BNW))
        f.uncover()
        val diplomacy = open(f.game, f.us, f.target)
        val share = UiTestSupport.button(diplomacy.stage, shareButtonText(f))
        assertFalse(share.isDisabled)

        UiTestSupport.tap(share)

        assertFalse("shared once", f.us.espionageManager.hasRecentIntrigueAbout(f.target))
        assertEquals(1, f.target.ai.diplomacy.memoryOf(f.us).numTimesIntrigueShared)
        val texts = UiTestSupport.labelTexts(diplomacy.stage)
        assertTrue(texts.toString(), texts.any { "Thank you for bringing this ${f.plotter.civName} plot to our attention" in it })
    }

    @Test
    fun `without uncovered intrigue there is nothing to share`() {
        val f = Intrigue(TestGame(baseRuleset = BaseRuleset.Civ_V_BNW))
        val diplomacy = open(f.game, f.us, f.target)
        assertTrue(UiTestSupport.labelTexts(diplomacy.stage).none { "Share intrigue" in it })
    }

    @Test
    fun `intrigue cannot be shared with a civilization at war with us`() {
        val f = Intrigue(TestGame(baseRuleset = BaseRuleset.Civ_V_BNW))
        f.uncover()
        f.us.getDiplomacyManager(f.target)!!.declareWar()
        val diplomacy = open(f.game, f.us, f.target)
        assertTrue(UiTestSupport.labelTexts(diplomacy.stage).none { "Share intrigue" in it })
    }
    //endregion

    //region Vote commitments in a trade
    @Test
    fun `the trade list offers the votes of a civilization whose capital our diplomat visits, each choice of each proposal`() {
        val f = LeagueUiFixture()
        UiTestSupport.prepare(f.game)
        f.league.proposeEnact(f.resolution("Standing Army Tax"), null, f.a)
        for (tile in f.game.tileMap.values) tile.setExplored(f.a, true)
        val spy = f.a.espionageManager.addSpy()
        spy.moveTo(f.b.getCapital()!!, asDiplomat = true)
        repeat(20) { if (f.a.espionageManager.getActiveDiplomatFor(f.b) == null) f.a.espionageManager.endTurn() }

        val view = GameView(f.game.gameInfo, f.a)
        val trade = TradeLogic(f.a, f.b)
        val votes = trade.theirAvailableOffers.filter { it.type == TradeOfferType.VoteCommitment }
        assertEquals(votes.toString(), 2, votes.size)
        assertTrue(votes.all { it.amount == f.league.getCoreVotes(f.b) && it.amount > 0 })

        val picked = ArrayList<TradeOffer>()
        val list = OffersListScroll("votes", 400f) { picked.add(it) }
        list.update(trade.theirAvailableOffers, trade.ourAvailableOffers, ourCiv = view.civView, theirCiv = view.getForeignCivView(f.b))
        val texts = UiTestSupport.labelTexts(list)
        assertTrue(texts.toString(), "Vote commitments" in texts)
        val yes = "Vote Yes on Standing Army Tax"
        assertTrue(texts.toString(), texts.any { it.startsWith(yes) })

        UiTestSupport.tap(UiTestSupport.actors(list).filterIsInstance<Button>()
            .first { button -> UiTestSupport.labelTexts(button).any { it.startsWith(yes) } })
        assertEquals("all the votes at one tap", votes.first().amount, picked.single().amount)
        assertEquals(TradeOfferType.VoteCommitment, picked.single().type)
    }
    //endregion
}
