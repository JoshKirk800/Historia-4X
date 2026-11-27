package com.unciv.logic.civilization.diplomacy

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.civilization.Accomplishment
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's war victories: CvPlayer::DoWarVictoryBonuses from a peace deal, a third-party peace and an elimination */
@RunWith(GdxTestRunner::class)
class WarVictoryTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        a = game.addCiv("Gain [50] [Gold] <upon winning a war>")
        b = game.addCiv("Gain [50] [Gold] <upon winning a war>")
        c = game.addCiv("Gain [50] [Gold] <upon winning a war>")
        game.addCity(a, game.getTile(HexCoord(-6, 0)))
        game.addCity(b, game.getTile(HexCoord(0, 0)))
        game.addCity(c, game.getTile(HexCoord(6, 0)))
        for ((x, y) in listOf(a to b, a to c, b to c)) x.diplomacyFunctions.makeCivilizationsMeet(y)
        game.ruleset.modOptions.constants.warVictoryLikeVp = true
        game.ruleset.modOptions.constants.accomplishmentsLikeVp = true
        game.ruleset.modOptions.constants.historicEventsLikeVp = true
    }

    /** Makes [loser] lose its war with [winner] by about [score] (CvPlayer::GetWarScore) */
    private fun loseWar(loser: Civilization, winner: Civilization, score: Int) {
        if (!loser.isAtWarWith(winner)) loser.getDiplomacyManager(winner)!!.declareWar()
        val current = WarDamageAi.empireWarValue(loser)
        loser.ai.diplomacy.memoryOf(winner).warValueLost = (current * score + 199) / 200
    }

    private fun wins(civ: Civilization) = civ.accomplishments[Accomplishment.WarWon.name]

    private fun makePeace(us: Civilization, them: Civilization, theyGiveGold: Int = 0) {
        val logic = TradeLogic(us, them)
        logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.gameInfo.speed))
        logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.gameInfo.speed))
        if (theyGiveGold > 0) logic.currentTrade.theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, theyGiveGold, speed = game.gameInfo.speed))
        logic.acceptTrade()
    }

    @Test
    fun `the side whose war score says it won a peace deal gets the bonuses`() {
        loseWar(b, a, 40)
        val gold = a.gold
        makePeace(b, a)
        assertEquals(1, wins(a))
        assertEquals(0, wins(b))
        assertEquals(gold + 50, a.gold)
        assertEquals(1, a.culture.historicEvents)
    }

    @Test
    fun `between humans the war score after the cities of the deal changed hands counts`() {
        a.playerType = PlayerType.Human
        b.playerType = PlayerType.Human
        val second = game.addCity(b, game.getTile(HexCoord(0, 4)))
        second.population.setPopulation(10)
        loseWar(b, a, 20)
        assertEquals(false, WarVictory.hasWon(a, b))
        val logic = TradeLogic(a, b)
        logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.gameInfo.speed))
        logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.gameInfo.speed))
        logic.currentTrade.theirOffers.add(TradeOffer(second.id, TradeOfferType.City, speed = game.gameInfo.speed))
        logic.acceptTrade()
        assertEquals(1, wins(a))
    }

    @Test
    fun `a war score under 25 wins nothing`() {
        loseWar(b, a, 20)
        makePeace(a, b)
        assertEquals(0, wins(a))
        assertEquals(0, a.culture.historicEvents)
    }

    @Test
    fun `between humans the winner needs the other to give items alone`() {
        a.playerType = PlayerType.Human
        b.playerType = PlayerType.Human
        loseWar(b, a, 40)
        makePeace(b, a)
        assertEquals(0, wins(a))

        loseWar(b, a, 40)
        b.addGold(100)
        makePeace(a, b, theyGiveGold = 100)
        assertEquals(1, wins(a))
    }

    @Test
    fun `a peace brokered with a third civilization wins the war against it`() {
        loseWar(c, a, 40)
        val logic = TradeLogic(b, a)
        logic.currentTrade.theirOffers.add(TradeOffer(c.civID, TradeOfferType.PeaceProposal, 1, speed = game.gameInfo.speed))
        logic.acceptTrade()
        assertEquals(1, wins(a))
        assertEquals(false, a.isAtWarWith(c))
    }

    @Test
    fun `everyone at war with an eliminated major wins and only the killer of a city-state`() {
        loseWar(b, a, 0)
        loseWar(b, c, 0)
        DiplomacyEvents.onLastCityTaken(a, b)
        assertEquals(1, wins(a))
        assertEquals(1, wins(c))

        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        game.addCity(cityState, game.getTile(HexCoord(0, 6)))
        cityState.diplomacyFunctions.makeCivilizationsMeet(a)
        cityState.diplomacyFunctions.makeCivilizationsMeet(c)
        a.getDiplomacyManager(cityState)!!.declareWar()
        c.getDiplomacyManager(cityState)!!.declareWar()
        DiplomacyEvents.onLastCityTaken(a, cityState)
        assertEquals(2, wins(a))
        assertEquals(1, wins(c))
    }

    @Test
    fun `without the switch winning gives nothing`() {
        game.ruleset.modOptions.constants.warVictoryLikeVp = false
        loseWar(b, a, 40)
        val gold = a.gold
        makePeace(b, a)
        assertEquals(0, wins(a))
        assertEquals(gold, a.gold)

        loseWar(b, c, 0)
        DiplomacyEvents.onLastCityTaken(c, b)
        assertEquals(0, wins(c))
        assertEquals(0, c.culture.historicEvents)
    }
}
