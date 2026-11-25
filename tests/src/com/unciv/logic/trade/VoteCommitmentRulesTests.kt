package com.unciv.logic.trade

import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.civilization.VoteCommitmentItem
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyBonus
import com.unciv.models.ruleset.SpyBonusKind
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.TradeOfferRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vote commitments in deals with `ModConstants.permanentVoteCommitments` (`MOD_BALANCE_PERMANENT_VOTE_COMMITMENTS`): a permanent item (V:CvDealClasses.cpp:1343, 1362, 1423, 1440, 1461), not cancelled
 * by a war (5895), abstained when the proposal is gone (V:CvVotingClasses.cpp:10660, 10689), and for the votes that the buyer's diplomat can buy (3943-4014), not for the core votes.
 * The human [a] buys the votes of the AI [b] on c's embargo against a, as in `VoteCommitmentOffersTests`.
 */
@RunWith(GdxTestRunner::class)
class VoteCommitmentRulesTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.permanentVoteCommitments = true
        a = major(isPlayer = true)
        b = major("[8] World Congress Delegates") // 9 delegates and 1 core vote, so that a percent of them is not the core votes
        c = major()
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    //region Helpers
    private fun major(vararg uniques: String, isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(*uniques, isPlayer = isPlayer)
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun proposeEmbargoOnA(): LeagueProposal {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(c.civID).proposalsLeft = 1
        league.proposeEnact(game.ruleset.resolutions["Embargo"]!!, a.civID, c)
        return league.proposals.single()
    }

    /** What a diplomat with [percent] makes tradable: a bonus of its own, none of the ruleset's changed */
    private fun diplomatBonus(percent: Int) {
        game.ruleset.spyBonuses["Vote trade $percent"] = SpyBonus().apply {
            name = "Vote trade $percent"
            kind = SpyBonusKind.Diplomat
            networkPoints = 0
            uniques = arrayListOf("[$percent]% of Congress votes can be traded with this civilization")
        }
    }

    /** a's diplomat schmoozes in b's capital */
    private fun sendDiplomat() {
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        a.espionageManager.addSpy().moveTo(b.getCapital()!!, asDiplomat = true)
        repeat(20) { if (a.espionageManager.getActiveDiplomatFor(b) == null) a.espionageManager.endTurn() }
        assertTrue(a.espionageManager.getActiveDiplomatFor(b) != null)
    }

    private fun offer(proposal: LeagueProposal, choice: String, votes: Int) =
        TradeOffer(VoteCommitmentItem.name(proposal.id, choice, false), TradeOfferType.VoteCommitment, votes, gameInfo.speed)

    private fun promise(votes: Int = 1): LeagueProposal {
        val proposal = proposeEmbargoOnA()
        LeagueAutomation.addVoteCommitment(b, a, proposal.id, League.NO, votes, repeal = false)
        return proposal
    }
    //endregion

    @Test
    fun `a vote commitment is a permanent item with the option and neither without it`() {
        val item = offer(LeagueProposal(1, "Embargo", false, null, null), League.NO, 1)
        assertEquals(TradeEvaluation.Companion.Permanence.Permanent, TradeEvaluation.permanence(item, true))
        assertNull(TradeEvaluation.permanence(item, false))
    }

    @Test
    fun `with the option a vote commitment is not given for a temporary item and not taken for one`() {
        constants.dealsBlockTemporaryForPermanent = true
        val proposal = proposeEmbargoOnA()
        val votes = offer(proposal, League.NO, 1)
        val goldPerTurn = TradeOffer("Gold per turn", TradeOfferType.Gold_Per_Turn, 5, gameInfo.speed)
        // two AIs, so that the rule for a human who gives the permanent item for the AI's temporary one does not apply: b gives votes (permanent) for c's gold per turn (temporary)
        assertTrue(TradeEvaluation.blocksTemporaryForPermanent(votes, b, c, listOf(goldPerTurn)))
        // and the other way round: c gives gold per turn (temporary) and gets votes (permanent)
        assertTrue(TradeEvaluation.blocksTemporaryForPermanent(goldPerTurn, c, b, listOf(votes)))
        // a permanent item for a permanent one is fine
        assertFalse(TradeEvaluation.blocksTemporaryForPermanent(votes, b, c, listOf(TradeOffer("Gold", TradeOfferType.Gold, 5, gameInfo.speed))))
        constants.permanentVoteCommitments = false
        assertFalse("without the option it is neither, and nothing blocks", TradeEvaluation.blocksTemporaryForPermanent(votes, b, c, listOf(goldPerTurn)))
        assertFalse(TradeEvaluation.blocksTemporaryForPermanent(goldPerTurn, c, b, listOf(votes)))
    }

    @Test
    fun `the trade screen calls a vote commitment a permanent item only with both rules on`() {
        val item = offer(LeagueProposal(1, "Embargo", false, null, null), League.NO, 1)
        assertNull("the permanence is not shown while the deals do not block temporary for permanent", TradeOfferRows.permanenceNote(item, b))
        constants.dealsBlockTemporaryForPermanent = true
        assertEquals("Permanent item", TradeOfferRows.permanenceNote(item, b))
        constants.permanentVoteCommitments = false
        assertNull("and a vote commitment is neither without the option", TradeOfferRows.permanenceNote(item, b))
    }

    @Test
    fun `without the option a war cancels the commitments`() {
        constants.permanentVoteCommitments = false
        promise()
        a.getDiplomacyManager(b)!!.declareWar()
        assertTrue("cancelled", league.members.getValue(b.civID).voteCommitments.isEmpty())
    }

    @Test
    fun `with the option a war leaves the commitments`() {
        promise()
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(1, league.members.getValue(b.civID).voteCommitments.size)
    }

    /**
     * b promised a its vote on an embargo against d, and d then has no city: the engine drops the proposal when the session starts (CvLeague::CheckProposalsValid), while another proposal keeps
     * the session open. Returns b's delegates at the start, which the commitment is read against
     */
    private fun sessionAfterTheTargetFell(votes: Int): Int {
        val d = major()
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(c.civID).proposalsLeft = 2
        league.proposeEnact(game.ruleset.resolutions["Embargo"]!!, d.civID, c)
        league.proposeEnact(game.ruleset.resolutions["Standing Army Tax"]!!, null, c)
        val embargo = league.proposals.first { it.resolution == "Embargo" }
        LeagueAutomation.addVoteCommitment(b, a, embargo.id, League.NO, votes, repeal = false)
        for (city in d.cities.toList()) city.moveToCiv(a)
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue("the session is open", league.inSession)
        assertTrue("the embargo is gone", league.proposals.none { it.resolution == "Embargo" })
        return league.members.getValue(b.civID).delegatesAtStart
    }

    @Test
    fun `with the option a commitment whose proposal is gone abstains the votes it promised`() {
        val delegates = sessionAfterTheTargetFell(votes = 1)
        assertTrue(delegates >= 2)
        assertEquals("one delegate of b is given up, not kept for something else", delegates - 1, league.getDelegatesLeft(b))
        assertEquals(1, league.members.getValue(b.civID).abstained)
        assertTrue(league.members.getValue(b.civID).voteCommitments.isEmpty())
    }

    @Test
    fun `without the option a commitment whose proposal is gone only disappears`() {
        constants.permanentVoteCommitments = false
        val delegates = sessionAfterTheTargetFell(votes = 1)
        assertEquals(delegates, league.getDelegatesLeft(b))
        assertEquals(0, league.members.getValue(b.civID).abstained)
        assertTrue(league.members.getValue(b.civID).voteCommitments.isEmpty())
    }

    @Test
    fun `a promise larger than the delegates left abstains only those left`() {
        val delegates = sessionAfterTheTargetFell(votes = 99)
        assertEquals(0, league.getDelegatesLeft(b))
        assertEquals(delegates, league.members.getValue(b.civID).abstained)
    }

    @Test
    fun `the votes that can be bought are the seller's delegates times the diplomat's percent, at least one`() {
        proposeEmbargoOnA()
        diplomatBonus(50)
        sendDiplomat()
        val delegates = league.getDelegates(b)
        assertTrue(delegates >= 2)
        assertEquals(maxOf(1, delegates * 50 / 100), league.getPotentialVotes(a, b))
        // a percent that rounds to nothing is one vote
        game.ruleset.spyBonuses.clear()
        diplomatBonus(1)
        assertEquals(1, league.getPotentialVotes(a, b))
    }

    @Test
    fun `no votes can be bought without a diplomat in the capital of the seller`() {
        proposeEmbargoOnA()
        diplomatBonus(50)
        assertEquals(0, league.getPotentialVotes(a, b))
    }

    @Test
    fun `what the seller already promised is not for sale again`() {
        val proposal = proposeEmbargoOnA()
        diplomatBonus(100)
        sendDiplomat()
        val delegates = league.getDelegates(b)
        assertEquals(delegates, league.getPotentialVotes(a, b))
        LeagueAutomation.addVoteCommitment(b, c, proposal.id, League.NO, 1, repeal = false)
        assertEquals(delegates - 1, league.getPotentialVotes(a, b))
        LeagueAutomation.addVoteCommitment(b, c, proposal.id, League.YES, delegates - 1, repeal = false)
        assertEquals("nothing is left to buy", 0, league.getPotentialVotes(a, b))
    }

    @Test
    fun `a commitment on a repeal is not subtracted, as the loop of Vox Populi runs over the enactments`() {
        league.activeResolutions.add(ActiveResolution(league.nextProposalId++, "Standing Army Tax", null, null, 0))
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(c.civID).proposalsLeft = 1
        league.proposeRepeal(league.activeResolutions.single().id, c)
        val repeal = league.proposals.single { it.repeal }
        diplomatBonus(100)
        sendDiplomat()
        val delegates = league.getDelegates(b)
        LeagueAutomation.addVoteCommitment(b, c, repeal.id, League.NO, 1, repeal = true)
        assertEquals(delegates, league.getPotentialVotes(a, b))
    }

    @Test
    fun `the offers of the trade screen carry the votes that can be bought, not the core votes`() {
        val proposal = proposeEmbargoOnA()
        diplomatBonus(50)
        sendDiplomat()
        val bought = league.getPotentialVotes(a, b)
        assertTrue("the test needs a number that is not the core votes", bought != league.getCoreVotes(b))
        val offered = TradeLogic(a, b).theirAvailableOffers.filter { it.type == TradeOfferType.VoteCommitment }
        assertEquals(2, offered.size)
        for (item in offered) {
            assertEquals(bought, item.amount)
            assertEquals(proposal.id, VoteCommitmentItem.parse(item)!!.proposalId)
        }
        constants.permanentVoteCommitments = false
        for (item in TradeLogic(a, b).theirAvailableOffers.filter { it.type == TradeOfferType.VoteCommitment })
            assertEquals("without the option, the core votes", league.getCoreVotes(b), item.amount)
    }

    @Test
    fun `the votes an AI asks for are the ones that can be bought`() {
        proposeEmbargoOnA()
        diplomatBonus(50)
        sendDiplomat()
        val bought = league.getPotentialVotes(a, b)
        val desired = LeagueAutomation.desiredVoteCommitments(a, b)
        assertTrue(desired.isNotEmpty())
        assertTrue(desired.all { it.votes == bought })
    }

    @Test
    fun `with the option a deal can carry more votes than the core votes, without it not`() {
        val proposal = proposeEmbargoOnA()
        diplomatBonus(500)
        sendDiplomat()
        val tooMany = league.getCoreVotes(b) + 1
        val trade = Trade().apply { theirOffers.add(offer(proposal, League.NO, tooMany)) }
        assertTrue(TradeEvaluation().isTradeValid(trade, a, b))
        constants.permanentVoteCommitments = false
        assertFalse(TradeEvaluation().isTradeValid(trade, a, b))
    }
}
