package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's war sanity: DoUpdateSaneDiplomaticTargets, IsWarWouldBankruptUs, CanBackstab, the defensive war allies of a new war */
@RunWith(GdxTestRunner::class)
class WarSanityTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        c = civ(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    @Test
    fun `a friend is no sane target, a stranger is`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        WarSanityAi.updateSaneTargets(a, game.ruleset.ai)
        assertFalse(WarSanityAi.isWarSane(a, b))
        assertTrue(WarSanityAi.isWarSane(a, c))
    }

    @Test
    fun `a war losing the gold that keeps us afloat is insane`() {
        a.stats.statsForNextTurn.gold = 5f
        assertFalse("nothing to lose", WarSanityAi.isWarWouldBankruptUs(a, b))
        val trade = Trade()
        trade.theirOffers.add(TradeOffer("Gold per turn", TradeOfferType.Gold_Per_Turn, 20, 30))
        a.getDiplomacyManager(b)!!.trades.add(trade)
        assertEquals(20, WarSanityAi.goldPerTurnLostFromWar(a, b))
        assertTrue("+5 becomes -15 with an empty treasury", WarSanityAi.isWarWouldBankruptUs(a, b))
        a.addGold(1000)
        assertFalse("1000 gold lasts over 30 turns at -15", WarSanityAi.isWarWouldBankruptUs(a, b))
    }

    @Test
    fun `a city-state allied with a friend is no sane target`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.allyCiv = b
        assertTrue(WarSanityAi.isSaneDiplomaticTarget(a, cityState, impulse = false, game.ruleset.ai))
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertFalse(WarSanityAi.isSaneDiplomaticTarget(a, cityState, impulse = false, game.ruleset.ai))
    }

    @Test
    fun `a new war brings in their defensive pact partners not yet fighting us`() {
        b.getDiplomacyManager(c)!!.signDefensivePact(30)
        assertEquals(listOf(c), WarAlliesAi.warAllies(a, b, includeMinors = false, reverse = true, defensive = true, newWarsOnly = true))
        assertEquals("offensive allies are at war already", emptyList<Civilization>(),
            WarAlliesAi.warAllies(a, b, includeMinors = false, reverse = true))
        a.getDiplomacyManager(c)!!.declareWar()
        assertEquals("already fighting us: no new war", emptyList<Civilization>(),
            WarAlliesAi.warAllies(a, b, includeMinors = false, reverse = true, defensive = true, newWarsOnly = true))
    }

    @Test
    fun `the gold rate is this turn's income plus the average of the last ten, which it then joins`() {
        val rules = game.ruleset.ai
        val memory = a.ai.warStates
        val income = a.stats.statsForNextTurn.gold.toInt()
        memory.goldIncomeHistory = arrayListOf(100, -50, 10)
        WarStateAi.updateAverageGoldRate(a, memory, rules)
        assertEquals(income + 20, WarStateAi.avgGoldRate(a))
        assertEquals(listOf(100, -50, 10, income), memory.goldIncomeHistory)
        memory.goldIncomeHistory = ArrayList(List(10) { 7 })
        WarStateAi.updateAverageGoldRate(a, memory, rules)
        assertEquals("ten turns kept", 10, memory.goldIncomeHistory.size)
        assertEquals(income, memory.goldIncomeHistory.last())
    }
}
