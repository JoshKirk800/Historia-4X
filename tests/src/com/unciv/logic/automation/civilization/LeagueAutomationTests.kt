package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.DoFLevel
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryPursuitAi
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.city.CityStats
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The World Congress AI of Vox Populi (CvLeagueAI, CvVotingClasses.cpp:10196-14450): every expected number is worked out from its formulas */
@RunWith(GdxTestRunner::class)
class LeagueAutomationTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        a = major()
        b = major()
        c = major()
        game.addCity(game.addCiv(cityStateType = "Cultured"), cityTile(), replacePalace = true)
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    //region Helpers
    private fun cityTile() = game.tileMap.tileList[7 * nextCell++]

    private fun major(): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private val majors get() = gameInfo.civilizations.filter { it.isMajorCiv }

    private fun resolution(name: String) = game.ruleset.resolutions[name]!!

    private fun evaluator(civ: Civilization) = LeagueEvaluator(civ, league)

    private fun startSession() {
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue("session should have started", league.inSession)
    }

    private fun onTable() = league.proposals.filter { !it.onHold }

    /** What [from] remembers of [to] in the World Congress */
    private fun memory(from: Civilization, to: Civilization) =
        league.members.getValue(from.civID).alignmentMemory.getOrPut(to.civID) { LeagueAlignmentMemory() }

    /** The alignment of [from] with [to] when the memory is [setup] and the voting history score shifts the sum by [history] (240 of it is 1) */
    private fun alignment(from: Civilization = a, to: Civilization = b, history: Int = 0, setup: (LeagueAlignmentMemory) -> Unit = {}): LeagueAlignment {
        val memory = memory(from, to)
        setup(memory)
        memory.votingHistoryScore = history * 240
        return LeagueAutomation.evaluateAlignment(from, to)
    }

    private fun proposalOf(resolution: String, proposer: Civilization?, choice: String? = null, repeal: Boolean = false) =
        LeagueProposal(100 + league.proposals.size, resolution, repeal, proposer?.civID, choice, if (repeal) 5 else 0)
    //endregion

    //region Alignment (CvLeagueAI::EvaluateAlignment)
    @Test
    fun `alignment is self for oneself and neutral for no major civilization`() {
        assertEquals(LeagueAlignment.SELF, LeagueAutomation.evaluateAlignment(a, a))
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(a, gameInfo.civilizations.first { it.isCityState }))
    }

    @Test
    fun `the sum is cut into bands at 3, 6 and 9 and the voting history is a tenth per 240 points`() {
        val expected = mapOf(-10 to LeagueAlignment.ENEMY, -9 to LeagueAlignment.ENEMY, -8 to LeagueAlignment.HATRED, -6 to LeagueAlignment.HATRED,
            -5 to LeagueAlignment.RIVAL, -3 to LeagueAlignment.RIVAL, -2 to LeagueAlignment.NEUTRAL, 0 to LeagueAlignment.NEUTRAL,
            2 to LeagueAlignment.NEUTRAL, 3 to LeagueAlignment.FRIEND, 5 to LeagueAlignment.FRIEND, 6 to LeagueAlignment.CONFIDANT,
            8 to LeagueAlignment.CONFIDANT, 9 to LeagueAlignment.ALLY, 10 to LeagueAlignment.ALLY)
        for ((sum, level) in expected) assertEquals("sum $sum", level, alignment(history = sum))
        assertEquals("239 points are not yet a step", LeagueAlignment.NEUTRAL, run {
            memory(a, b).votingHistoryScore = 239
            LeagueAutomation.evaluateAlignment(a, b)
        })
    }

    @Test
    fun `a war is war only if we want to conquer them and else -1 for a phony war and -2`() {
        val toB = a.getDiplomacyManager(b)!!
        toB.diplomaticStatus = DiplomaticStatus.War
        b.getDiplomacyManager(a)!!.diplomaticStatus = DiplomaticStatus.War
        assertFalse(WarIntentAi.wantsToConquer(a, b))
        val sum = if (WarIntentAi.isPhonyWar(a, b)) -1 else -2
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 0))
        assertEquals(if (sum == -1) LeagueAlignment.NEUTRAL else LeagueAlignment.RIVAL, alignment(history = -1))
        assertEquals(LeagueAlignment.RIVAL, alignment(history = -2))

        a.ai.warStates.warStates[b.civID] = WarState.Offensive
        assertTrue(WarIntentAi.wantsToConquer(a, b))
        assertEquals(LeagueAlignment.WAR, alignment(history = 10))
    }

    @Test
    fun `a friendship adds 1, 2 or 3 by its level unless they are untrustworthy or we want to end it`() {
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        val memory = a.ai.diplomacy.memoryOf(b)
        val expected = mapOf(DoFLevel.Untrustworthy to LeagueAlignment.CONFIDANT, DoFLevel.New to LeagueAlignment.CONFIDANT,
            DoFLevel.Friends to LeagueAlignment.CONFIDANT, DoFLevel.Allies to LeagueAlignment.CONFIDANT, DoFLevel.BattleBrothers to LeagueAlignment.ALLY)
        for ((level, band) in expected) {
            memory.doFLevel = level
            assertEquals("$level and 6", band, alignment(history = 6))
        }
        memory.doFLevel = DoFLevel.Allies
        assertEquals("2 + 7", LeagueAlignment.ALLY, alignment(history = 7))
        memory.doFLevel = DoFLevel.Friends
        assertEquals("1 + 7", LeagueAlignment.CONFIDANT, alignment(history = 7))
        assertEquals("1 + 8", LeagueAlignment.ALLY, alignment(history = 8))
        memory.wantsToEndDoF = true
        assertEquals("a friendship we want to end counts nothing: 8", LeagueAlignment.CONFIDANT, alignment(history = 8))
    }

    @Test
    fun `a civilization that denounced us is minus 2 and a backstabber minus 4`() {
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.Denunciation, 20)
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 0))
        assertEquals("-2 - 1", LeagueAlignment.RIVAL, alignment(history = -1))
        b.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.Denunciation)

        assertEquals(LeagueAlignment.FRIEND, alignment(history = 4))
        a.ai.diplomacy.memoryOf(b).untrustworthy = true // a holds b for a backstabber (UntrustworthyAi.testUntrustworthyFriends)
        assertTrue(OpinionAi.isUntrustworthyFriend(a, b))
        assertEquals("4 - 4", LeagueAlignment.NEUTRAL, alignment(history = 4))
        assertEquals("-4", LeagueAlignment.RIVAL, alignment(history = 0))
    }

    @Test
    fun `a defensive pact adds 2`() {
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1))
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertEquals(LeagueAlignment.FRIEND, alignment(history = 1))
        a.ai.diplomacy.memoryOf(b).wantsToEndDefensivePact = true
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1))
    }

    @Test
    fun `friends of both add 1 and the friends of an enemy we denounced take 1`() {
        a.getDiplomacyManager(c)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        b.getDiplomacyManager(c)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertEquals("one mutual friend", LeagueAlignment.FRIEND, alignment(history = 2))
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1))

        a.getDiplomacyManager(c)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        a.getDiplomacyManager(c)!!.setFlag(DiplomacyFlags.Denunciation, 20)
        assertEquals("b is a friend of c, whom a denounced", LeagueAlignment.RIVAL, alignment(history = -2))
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = -1))
    }

    @Test
    fun `liking their proposals adds 1 or 2, disliking takes 1 or 2 by the strength`() {
        // OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL -30, _DISLIKED_ 30: beyond them it is 2
        assertEquals(LeagueAlignment.FRIEND, alignment(history = 1) { it.proposalAgreementValue = -31 })
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1) { it.proposalAgreementValue = -30 })
        assertEquals(LeagueAlignment.RIVAL, alignment(history = -1) { it.proposalAgreementValue = 31 })
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = -1) { it.proposalAgreementValue = 30 })
    }

    @Test
    fun `support of our hosting adds 1 or, past minus 40 of opinion, 2`() {
        val turn = gameInfo.turns
        val rules = game.ruleset.ai
        // A diplomat counts 150% of it (OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING_DIPLOMAT_MULTIPLIER)
        val diplomat = a.ai.isDiplomat() || a.ai.isSecondaryDiplomat() || a.ai.hasTraitCategory(com.unciv.models.ruleset.ai.TraitCategory.Diplomat)
        fun scaled(value: Int) = if (diplomat) value * 150 / 100 else value
        // OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING -20 towards _MAX -70 by 2% per percent of the votes: 50% is all of it, 5% a tenth: -20 - 5
        assertEquals(LeagueAlignment.FRIEND, alignment(history = 1) { it.supportedHostingTurn = turn; it.supportedHostingValue = 50 })
        assertEquals(scaled(-70), OpinionAi.supportedMyHostingScore(a, memory(a, b), rules))
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1) { it.supportedHostingTurn = turn; it.supportedHostingValue = 5 })
        assertEquals(scaled(-25), OpinionAi.supportedMyHostingScore(a, memory(a, b), rules))
        assertEquals(LeagueAlignment.FRIEND, alignment(history = 2) { it.supportedHostingTurn = turn; it.supportedHostingValue = 5 })
    }

    @Test
    fun `those who supported our proposals add 2 and who foiled them take 2, one less after the reverse`() {
        gameInfo.turns = 10 // a counter of -1 is "never"
        val turn = gameInfo.turns
        assertEquals(LeagueAlignment.FRIEND, alignment(history = 1) { it.supportedProposalTurn = turn; it.proposalSupportDelta = -50 })
        assertEquals("supported, after foiling: +1", LeagueAlignment.NEUTRAL, alignment(history = 1) {
            it.supportedProposalTurn = turn; it.foiledProposalTurn = turn - 1; it.proposalSupportDelta = -50
        })
        assertEquals(LeagueAlignment.RIVAL, alignment(history = -1) { it.foiledProposalTurn = turn; it.supportedProposalTurn = -1; it.proposalSupportDelta = 50 })
        assertEquals("foiled, after supporting: -1", LeagueAlignment.NEUTRAL, alignment(history = -1) {
            it.foiledProposalTurn = turn; it.supportedProposalTurn = turn - 1; it.proposalSupportDelta = 50
        })
    }

    @Test
    fun `a sanction against us takes 1 or 2 and lifting one adds 1 or 2`() {
        val turn = gameInfo.turns
        assertEquals("tried", LeagueAlignment.RIVAL, alignment(history = -2) { it.sanctionedTurn = turn })
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = -1) { it.sanctionedTurn = turn })
        assertEquals("one passed", LeagueAlignment.RIVAL, alignment(history = -1) { it.everSanctioned = true })
        assertEquals("lifting tried", LeagueAlignment.FRIEND, alignment(history = 2) { it.sanctionedTurn = -1; it.everSanctioned = false; it.unsanctionedTurn = turn })
        assertEquals("lifting passed", LeagueAlignment.FRIEND, alignment(history = 1) { it.everUnsanctioned = true })
    }

    @Test
    fun `disputes over city-states, the warmonger threat and captured cities take points`() {
        val memory = a.ai.diplomacy.memoryOf(b)
        memory.minorCivDispute = DisputeLevel.Fierce
        assertEquals("-3", LeagueAlignment.RIVAL, alignment(history = 0))
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1))
        memory.minorCivDispute = DisputeLevel.None

        memory.warmongerThreat = AiThreat.Severe
        assertEquals("-3", LeagueAlignment.RIVAL, alignment(history = 0))
        memory.warmongerThreat = AiThreat.None

        memory.numCitiesCapturedBy = 3
        assertEquals("-3", LeagueAlignment.RIVAL, alignment(history = 0))
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 1))
        memory.numCitiesCapturedBy = 0

        a.ai.diplomacy.biggestCompetitor = b.civID
        assertEquals("-2", LeagueAlignment.NEUTRAL, alignment(history = 0))
        assertEquals(LeagueAlignment.RIVAL, alignment(history = -1))
    }

    @Test
    fun `a liberator adds 4, the cities they liberated add one each and one that resurrected us is a liberator`() {
        val memory = a.ai.diplomacy.memoryOf(b)
        memory.numCitiesEverLiberated = 3
        assertEquals("3", LeagueAlignment.FRIEND, alignment(history = 0))
        memory.numCitiesEverLiberated = 0

        memory.liberatedCapital = true
        assertEquals("+2 while we compete for a victory", LeagueAlignment.NEUTRAL, alignment(history = 0))
        assertEquals("2 + 1", LeagueAlignment.FRIEND, alignment(history = 1))
        a.ai.diplomacy.memoryOf(c).resurrectedUs = true // c resurrected us: no competition, so +4
        assertEquals("4", LeagueAlignment.FRIEND, alignment(history = 0))
        assertEquals("4 + 2", LeagueAlignment.CONFIDANT, alignment(history = 2))
        a.ai.diplomacy.memoryOf(c).resurrectedUs = false
        memory.liberatedCapital = false

        memory.resurrectedUs = true
        assertEquals(LeagueAlignment.LIBERATOR, alignment(history = 0))
        memory.resurrectedUs = false
        assertEquals(LeagueAlignment.NEUTRAL, alignment(history = 0))
    }

    @Test
    fun `an embargo proposed against us is a sanction we do not forgive`() {
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        // -1 for the sanction and -2 for OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_OVERWHELMING (60) of a proposal we hate
        assertEquals(60, memory(a, b).proposalAgreementValue)
        assertEquals(LeagueAlignment.RIVAL, LeagueAutomation.evaluateAlignment(a, b))
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(c, b))
    }
    //endregion

    //region Desire levels
    @Test
    fun `desire levels follow the bands of EvaluateDesire`() {
        val expected = listOf(-2501 to LeagueDesire.NEVER, -2500 to LeagueDesire.STRONG_DISLIKE, -1001 to LeagueDesire.STRONG_DISLIKE,
            -1000 to LeagueDesire.DISLIKE, -251 to LeagueDesire.DISLIKE, -250 to LeagueDesire.WEAK_DISLIKE, -51 to LeagueDesire.WEAK_DISLIKE,
            -50 to LeagueDesire.NEUTRAL, 50 to LeagueDesire.NEUTRAL, 51 to LeagueDesire.WEAK_LIKE, 250 to LeagueDesire.WEAK_LIKE,
            251 to LeagueDesire.LIKE, 1000 to LeagueDesire.LIKE, 1001 to LeagueDesire.STRONG_LIKE, 2500 to LeagueDesire.STRONG_LIKE,
            2501 to LeagueDesire.ALWAYS)
        for ((score, desire) in expected) assertEquals("score $score", desire, LeagueDesire.of(score))
    }
    //endregion

    //region Effects (CvLeagueAI::ScoreVoteChoiceYesNo)
    @Test
    fun `an embargo on oneself is minus 1000`() {
        assertEquals(-1000, evaluator(a).scoreEffects(resolution("Embargo"), a.civID))
    }

    @Test
    fun `an embargo on a civilization we neither like nor fear is minus 100 per opinion level above competitor`() {
        // opinion Neutral (3) - Competitor (2) = 1: -100; no routes, no war, the approach is neutral
        assertEquals(-100, evaluator(a).scoreEffects(resolution("Embargo"), b.civID))
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Ally
        assertEquals(-400, evaluator(a).scoreEffects(resolution("Embargo"), b.civID))
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.War
        assertEquals("+200 at war", -200, evaluator(a).scoreEffects(resolution("Embargo"), b.civID))
        // an embargo on a city-state: nothing
        assertEquals(0, evaluator(a).scoreEffects(resolution("Embargo"), gameInfo.civilizations.first { it.isCityState }.civID))
    }

    @Test
    fun `an embargo on a civilization we are on bad terms with and fear to be strong adds its threat`() {
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Enemy
        a.ai.diplomacy.memoryOf(b).approach = com.unciv.models.ruleset.ai.MajorCivApproach.Hostile
        a.ai.diplomacy.memoryOf(b).warmongerThreat = AiThreat.Major
        // (Enemy 1 - Competitor 2) = -1 -> +100; threat Major 2 x 150 = 300
        assertEquals(400, evaluator(a).scoreEffects(resolution("Embargo"), b.civID))
    }

    @Test
    fun `nuclear non-proliferation is worth nothing to a civilization without neighbours it dislikes`() {
        assertEquals(0, evaluator(a).scoreEffects(resolution("Nuclear Non-Proliferation"), null))
        // a strong land dispute with b and a hostile approach (1 below deceptive): (8 - flavor) x 1 x 20
        a.ai.diplomacy.memoryOf(b).landDispute = DisputeLevel.Strong
        a.ai.diplomacy.memoryOf(b).approach = com.unciv.models.ruleset.ai.MajorCivApproach.Hostile
        val flavor = a.ai.personalityAndGrandStrategy(com.unciv.models.ruleset.ai.Flavor.Nuke).coerceIn(0, 20)
        assertEquals((8 - flavor) * 20, evaluator(a).scoreEffects(resolution("Nuclear Non-Proliferation"), null))
        // at war: 2 below deceptive
        a.ai.diplomacy.memoryOf(b).approach = com.unciv.models.ruleset.ai.MajorCivApproach.War
        assertEquals((8 - flavor) * 40, evaluator(a).scoreEffects(resolution("Nuclear Non-Proliferation"), null))
    }

    @Test
    fun `cultural heritage sites are worth 40 per wonder and 20 per great work less 75 per era to the proposer`() {
        assertEquals(0, evaluator(a).scoreEffects(resolution("Cultural Heritage Sites"), null))
    }

    @Test
    fun `natural heritage sites are worth 200 per natural wonder and the proposer pays 100`() {
        assertEquals(-100, evaluator(a).scoreEffects(resolution("Natural Heritage Sites"), null))
    }

    @Test
    fun `scholars in residence follow our technologies against the mean`() {
        // a has 1 technology, the others none: 3 civilizations, 1 technology in all, 1 for us: +200 percent, at most 10: -600;
        // the best has what we have: (0 - 2) x 60 = -120
        assertEquals(-720, evaluator(a).scoreEffects(resolution("Scholars In Residence"), null))
        // b has none: -100 percent, at least -10: +600; the best has 1 more: (1 - 2) x 60 = -60
        assertEquals(540, evaluator(b).scoreEffects(resolution("Scholars In Residence"), null))
        // 10% more for every city-state ally when it is a gain
        gameInfo.civilizations.first { it.isCityState }.allyCiv = b
        assertEquals(540 * 110 / 100, evaluator(b).scoreEffects(resolution("Scholars In Residence"), null))
    }

    @Test
    fun `the standing army tax is worth half the percent our might is under the mean to us and at most 50`() {
        // nobody has any might: 0 of 0, minus 100 percent, cut to -50: -(-50 x 25 / 2)
        assertEquals(625, evaluator(a).scoreEffects(resolution("Standing Army Tax"), null))
    }

    @Test
    fun `a luxury nobody holds is worth 100 to the proposer and each of our tiles with it costs 75`() {
        assertEquals(100, evaluator(a).scoreEffects(resolution("Ban Luxury"), "Wine"))
        a.cities.first().getCenterTile().setTileResource("Wine")
        assertEquals(100 - 75, evaluator(a).scoreEffects(resolution("Ban Luxury"), "Wine"))
    }

    @Test
    fun `arts funding and sciences funding are worth nothing without unique persons, improvements or works`() {
        assertEquals(0, evaluator(a).scoreEffects(resolution("Arts Funding"), null))
        assertEquals(0, evaluator(a).scoreEffects(resolution("Sciences Funding"), null))
    }

    @Test
    fun `historical landmarks cost the proposer 100 per era`() {
        assertEquals(0, evaluator(a).scoreEffects(resolution("Historical Landmarks"), null))
    }
    //endregion

    //region Frame (CvLeagueAI::ScoreVoteChoiceYesNo and ScoreProposal)
    @Test
    fun `an own proposal gets 150 for the alignment with the proposer and the other voters weigh nothing at neutral`() {
        val own = proposalOf("Natural Heritage Sites", a)
        assertEquals(-100 + 150, evaluator(a).scoreVote(own, League.YES))
        assertEquals(100 - 150, evaluator(a).scoreVote(own, League.NO))
        // no alignment term for a neutral proposer
        val theirs = proposalOf("Natural Heritage Sites", b)
        assertEquals(-100, evaluator(a).scoreVote(theirs, League.YES))
        assertEquals(100, evaluator(a).scoreVote(theirs, League.NO))
    }

    @Test
    fun `a repeal flips the effects`() {
        league.activeResolutions.add(com.unciv.logic.league.ActiveResolution(5, "Natural Heritage Sites", null, null, 0))
        val repeal = proposalOf("Natural Heritage Sites", b, repeal = true)
        assertEquals(100, evaluator(a).scoreVote(repeal, League.YES))
        assertEquals(-100, evaluator(a).scoreVote(repeal, League.NO))
        // repealing it is what we want to propose ourselves: 100 and 150 for the proposer
        assertEquals(100 + 150, evaluator(a).scoreVote(proposalOf("Natural Heritage Sites", a, repeal = true), League.YES))
    }

    @Test
    fun `the alignment with the proposer weighs 90, 65 and 40 by the work-with willingness and minus 40, 65 and 90 by work-against`() {
        val workWith = a.ai.diplomacyValue(com.unciv.models.ruleset.ai.DiplomacyPersonality.WorkWithWillingness)
        val workAgainst = a.ai.diplomacyValue(com.unciv.models.ruleset.ai.DiplomacyPersonality.WorkAgainstWillingness)
        val theirs = proposalOf("Natural Heritage Sites", b)
        val expected = mapOf(9 to (OpinionAi.adjustConditional(90, workWith)), 6 to OpinionAi.adjustConditional(65, workWith),
            3 to OpinionAi.adjustConditional(40, workWith), -3 to OpinionAi.adjustConditional(-40, workAgainst),
            -6 to OpinionAi.adjustConditional(-65, workAgainst), -9 to OpinionAi.adjustConditional(-90, workAgainst))
        for ((history, bonus) in expected) {
            memory(a, b).votingHistoryScore = history * 240
            assertEquals("alignment sum $history", -100 + bonus, evaluator(a).scoreVote(theirs, League.YES))
        }
        memory(a, b).votingHistoryScore = 0
    }

    @Test
    fun `the others' scores of the same vote weigh by how much we like them`() {
        val workWith = a.ai.diplomacyValue(com.unciv.models.ruleset.ai.DiplomacyPersonality.WorkWithWillingness)
        val proposal = proposalOf("Standing Army Tax", null)
        assertEquals("nobody to like or dislike", 625, evaluator(a).scoreVote(proposal, League.YES))
        // b, liked by one opinion level (800), scores the tax 625; c adds only a voter: x16 / (10 + 3)
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Favorable
        val fromB = 800 * workWith / 2000 * 625 / 100
        assertEquals(625 + fromB * 16 / 13, evaluator(a).scoreVote(proposal, League.YES))
        assertEquals(-625 - fromB * 16 / 13, evaluator(a).scoreVote(proposal, League.NO))
    }

    @Test
    fun `a vote on a civilization has no effects and the outcome scores 0`() {
        val election = proposalOf("Choose Host", null)
        assertEquals(0, evaluator(a).scoreOutcome(election, b.civID))
    }
    //endregion

    //region International projects
    /** Production of each civilization's cities, [fromFood] of it being what a settler makes of excess food */
    private fun setProduction(civ: Civilization, production: Float, fromFood: Float = 0f) {
        for (city in civ.cities) {
            city.cityStats.currentCityStats = Stats(production = production)
            city.cityStats.finalStatList.clear()
            if (fromFood > 0f) city.cityStats.finalStatList[CityStats.EXCESS_FOOD_TO_PRODUCTION] = Stats(production = fromFood)
        }
    }

    @Test
    fun `the world's fair is worth 30 per city and 2 per percent of winning for gold, 500 for bronze and the proposer pays 400`() {
        setProduction(a, 30f)
        setProduction(b, 20f)
        setProduction(c, 10f)
        // 100% of the highest production: gold with 100 of winning (nobody has more): 100 x 30 x 1 city / 100 + 2 x 100
        val turnsForPolicy = a.policies.getCultureNeededForNextPolicy() * 100 / 1 // no culture
        val silver = 70 * (gameInfo.speed.cultureCostModifier * 100).toInt() / maxOf(1, turnsForPolicy)
        assertEquals(30 + 200 + silver + 500 - 400, evaluator(a).scoreEffects(resolution("World's Fair"), null))
        // b: 67% of the highest, 1 civilization has more: no gold, silver and bronze
        assertEquals(silver + 500 - 400, evaluator(b).scoreEffects(resolution("World's Fair"), null))
        // c: 33%, bronze only
        assertEquals(500 - 400, evaluator(c).scoreEffects(resolution("World's Fair"), null))
    }

    @Test
    fun `the international space station is worth 10 plus our technology over the mean for gold, 20 times that for silver and 150 for bronze`() {
        setProduction(a, 30f)
        setProduction(b, 20f)
        setProduction(c, 10f)
        val spaceRace = VictoryPursuitAi.isVictoryEnabled(a, VictoryPursuit.Science)
        fun scaled(value: Int) = if (spaceRace) value else value / 2
        // the percent our technologies are over the mean of the three civilizations (VP: ours x civilizations x 100 / all technologies - 100)
        val counts = majors.associateWith { it.tech.researchedTechnologies.size }
        fun over(civ: Civilization) = maxOf(0, counts.getValue(civ) * counts.size * 100 / maxOf(1, counts.values.sum()) - 100)
        assertTrue("a is the only one with technologies or has most of them", over(a) > 0)
        assertEquals(scaled(100 * (10 + over(a)) + 20 * (10 + over(a)) + 150 - 500), evaluator(a).scoreEffects(resolution("International Space Station"), null))
        assertEquals(scaled(20 * (10 + over(b)) + 150 - 500), evaluator(b).scoreEffects(resolution("International Space Station"), null))
        assertEquals(scaled(150 - 500), evaluator(c).scoreEffects(resolution("International Space Station"), null))
    }

    @Test
    fun `international games pay silver 200 and the proposer 250, and bronze needs influence`() {
        setProduction(a, 30f)
        setProduction(b, 20f)
        setProduction(c, 10f)
        val culture = VictoryPursuitAi.isVictoryEnabled(a, VictoryPursuit.Culture)
        // no influence on anyone: the lowest percent is 0 (< 25): 40 x max(0 - 25, -25) = -1000 for the proposer
        val gold = if (culture) 100 * (0 - 15).coerceIn(10, 50) / 2 else 200
        assertEquals(gold + 200 - 1000 - 250, evaluator(a).scoreEffects(resolution("International Games"), null))
        assertEquals(-1000 - 250 + 200, evaluator(b).scoreEffects(resolution("International Games"), null))
        assertEquals(-1000 - 250, evaluator(c).scoreEffects(resolution("International Games"), null))
    }

    @Test
    fun `production that comes from excess food does not count as production might`() {
        // 30 production of which 25 from food is 5 of might against the 20 of the best: 25%, which is the bronze threshold (>= 25) and nothing more
        // (without the food it would be 30 of 30: gold)
        setProduction(a, 30f, fromFood = 25f)
        setProduction(b, 20f)
        setProduction(c, 10f)
        assertEquals(500 - 400, evaluator(a).scoreEffects(resolution("World's Fair"), null))
        setProduction(a, 30f)
        assertTrue(evaluator(a).scoreEffects(resolution("World's Fair"), null) > 500 - 400 + 200)
    }
    //endregion

    //region Choosing a civilization (CvLeagueAI::ScoreVoteChoicePlayer)
    private fun percentOfVotesNeeded(civ: Civilization) = league.getDelegates(civ) * 100 / maxOf(1, league.getVotesNeededForWorldLeader())

    @Test
    fun `a civilization votes for itself with 1000 and 30 per percent of the votes needed for the World Leader, and 200 per vote for the host`() {
        assertEquals(1000 + 30 * percentOfVotesNeeded(a), evaluator(a).scorePlayerChoice(resolution("World Leader"), a))
        assertEquals(200 * (league.getDelegates(a) + 1), evaluator(a).scorePlayerChoice(resolution("Choose Host"), a))
    }

    @Test
    fun `others are worth 25, 15 and 10 per percent for an ally, a confidant and a friend we like and minus 25, 100 and 250 for a rival, hatred and an enemy`() {
        val leader = resolution("World Leader")
        val percent = percentOfVotesNeeded(b)
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Favorable
        for ((history, factor) in mapOf(9 to 25, 6 to 15, 3 to 10, -3 to -25, -6 to -100, -9 to -250)) {
            memory(a, b).votingHistoryScore = history * 240
            assertEquals("alignment sum $history", factor * percent, evaluator(a).scorePlayerChoice(leader, b))
        }
        // not liked: friends are worth nothing
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Neutral
        for (history in listOf(9, 6, 3)) {
            memory(a, b).votingHistoryScore = history * 240
            assertEquals(0, evaluator(a).scorePlayerChoice(leader, b))
        }
    }

    @Test
    fun `the host is worth 150, 100 and 50 per vote and one more for a liked ally, confidant and friend and minus 50, 150 and 300 for the others`() {
        val host = resolution("Choose Host")
        val votes = league.getDelegates(b) + 1
        a.ai.diplomacy.memoryOf(b).opinion = MajorCivOpinion.Favorable
        for ((history, factor) in mapOf(9 to 150, 6 to 100, 3 to 50, -3 to -50, -6 to -150, -9 to -300)) {
            memory(a, b).votingHistoryScore = history * 240
            assertEquals("alignment sum $history", factor * votes, evaluator(a).scorePlayerChoice(host, b))
        }
    }

    @Test
    fun `a civilization that resurrected us is worth the World Leader base and 30 per percent, and the host 200 per vote`() {
        a.ai.diplomacy.memoryOf(b).resurrectedUs = true
        assertEquals(1000 + 30 * percentOfVotesNeeded(b), evaluator(a).scorePlayerChoice(resolution("World Leader"), b))
        assertEquals(200 * (league.getDelegates(b) + 1), evaluator(a).scorePlayerChoice(resolution("Choose Host"), b))
    }

    @Test
    fun `a civilization we want to conquer is worth minus 250 per percent for the World Leader and minus 300 per vote for the host`() {
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.War
        a.ai.warStates.warStates[b.civID] = WarState.Offensive
        assertEquals(-250 * percentOfVotesNeeded(b), evaluator(a).scorePlayerChoice(resolution("World Leader"), b))
        assertEquals(-300 * (league.getDelegates(b) + 1), evaluator(a).scorePlayerChoice(resolution("Choose Host"), b))
    }
    //endregion

    //region Proposals
    @Test
    fun `a civilization proposes the best resolution by score and then the next best`() {
        // Without production (the Fair is -250, the projects nobody's) and equal and neutral civilizations a proposer scores 150 (itself) over the effects:
        // the army tax 625 -> 775, scholars 540 for b (it is behind in technology) -> 690, then 150 for the first of the equally liked ones
        for (civ in listOf(a, b, c)) setProduction(civ, 0f)
        league.members.getValue(b.civID).proposalsLeft = 3
        LeagueAutomation.automateLeague(b)
        assertEquals(listOf("Standing Army Tax", "Scholars In Residence", "Embargo City-States"), league.proposals.map { it.resolution })
        assertTrue(league.proposals.all { it.proposer == b.civID })
        assertEquals(0, league.members.getValue(b.civID).proposalsLeft)
    }

    @Test
    fun `with nothing it likes a civilization proposes the least bad resolution`() {
        // the Fair and the others on the table: only the repeal of nothing and the Fair are legal for b: the Fair costs 400 and gains 150
        game.addCity(b, cityTile())
        for (civ in listOf(a, b, c)) setProduction(civ, 0f)
        league.members.getValue(b.civID).proposalsLeft = 1
        for (resolution in game.ruleset.resolutions.values) {
            if (resolution.name == "World's Fair") continue
            val choices = if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else league.getChoices(resolution, b)
            for (choice in choices) if (league.canProposeEnact(resolution, choice, b))
                league.proposals.add(LeagueProposal(league.nextProposalId++, resolution.name, false, b.civID, choice))
        }
        assertTrue(league.canProposeEnact(resolution("World's Fair"), null, b))
        assertEquals(-400 + 150, evaluator(b).scoreVote(LeagueProposal(999, "World's Fair", false, b.civID, null), League.YES))
        gameInfo.turns++
        LeagueAutomation.automateLeague(b)
        assertEquals("World's Fair", league.proposals.last().resolution)
    }

    @Test
    fun `nothing happens for a civilization without proposals or delegates`() {
        for (member in league.members.values) member.proposalsLeft = 0
        LeagueAutomation.automateLeague(c)
        assertTrue(league.proposals.isEmpty())
        assertFalse(league.inSession)
    }

    @Test
    fun `the civilizations never propose the same resolution twice`() {
        for (member in league.members.values) member.proposalsLeft = 2
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        assertEquals(6, league.proposals.size)
        val keys = league.proposals.map { it.resolution to it.proposerChoice }
        assertEquals(keys.size, keys.toSet().size)
        for (civ in majors) assertFalse(league.canPropose(civ))
    }

    @Test
    fun `an active resolution can be proposed for repeal`() {
        // the only thing legal for b is the repeal if everything else is on the table or in effect
        league.members.getValue(b.civID).proposalsLeft = 1
        for (resolution in game.ruleset.resolutions.values) {
            val choices = if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else league.getChoices(resolution, b)
            for (choice in choices) if (league.canProposeEnact(resolution, choice, b))
                league.activeResolutions.add(com.unciv.logic.league.ActiveResolution(league.nextProposalId++, resolution.name, null, choice, 0))
        }
        assertTrue(league.getProposableResolutions(b).isEmpty())
        LeagueAutomation.automateLeague(b)
        val proposal = league.proposals.single()
        assertTrue(proposal.repeal)
        assertTrue(league.activeResolutions.any { it.id == proposal.targetId })
    }
    //endregion

    //region Votes
    @Test
    fun `all delegates are spent in a session`() {
        for (member in league.members.values) member.proposalsLeft = 1
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        startSession()
        val delegates = majors.associateWith { league.getDelegatesLeft(it) }
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        for (civ in majors) {
            assertEquals(0, league.getDelegatesLeft(civ))
            val cast = onTable().sumOf { proposal -> proposal.votes.filter { it.voter == civ.civID }.sumOf { it.delegates } }
            assertEquals(delegates.getValue(civ), cast)
        }
        league.doTurn()
        assertFalse(league.inSession)
        assertEquals(3, league.lastResults.size)
    }

    @Test
    fun `delegates are given up if nothing is proposed`() {
        league.proposals.clear()
        for (member in league.members.values) member.proposalsLeft = 0
        league.inSession = true
        league.members.getValue(a.civID).delegatesLeft = 2
        LeagueAutomation.automateLeague(a)
        assertEquals(0, league.getDelegatesLeft(a))
        assertEquals(2, league.members.getValue(a.civID).abstained)
    }

    private fun embargoes(vararg targets: Pair<Civilization, Civilization>) {
        for (member in league.members.values) member.proposalsLeft = 0
        for ((proposer, target) in targets) {
            league.members.getValue(proposer.civID).proposalsLeft = 1
            league.proposeEnact(resolution("Embargo"), target.civID, proposer)
        }
    }

    @Test
    fun `a delegate goes to the heaviest proposal which then weighs a share less, ties to the first`() {
        // Two embargoes on civilizations we are neutral to: -100 for yes, so 100 for no on each
        embargoes(c to b, b to c)
        startSession()
        league.members.getValue(a.civID).delegatesLeft = 3
        LeagueAutomation.automateLeague(a)
        val (first, second) = onTable()
        // 100: first (67), second (50), first (0)
        assertEquals(mapOf(League.NO to 2), first.votes.filter { it.voter == a.civID }.associate { it.choice to it.delegates })
        assertEquals(mapOf(League.NO to 1), second.votes.filter { it.voter == a.civID }.associate { it.choice to it.delegates })
    }

    @Test
    fun `an embargo on the civilization gets its delegates and its No, a share of the delegates goes elsewhere`() {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.members.getValue(c.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        league.proposeEnact(resolution("Standing Army Tax"), null, c)
        startSession()
        league.members.getValue(a.civID).delegatesLeft = 4
        LeagueAutomation.automateLeague(a)
        val embargo = league.proposals.first { it.resolution == "Embargo" }
        val tax = league.proposals.first { it.resolution == "Standing Army Tax" }
        // weights 1000 for No on the embargo and 625 for Yes on the tax: 1000 (750), 750 (500), 625 (313), 500 (0)
        assertEquals(mapOf(League.NO to 3), embargo.votes.filter { it.voter == a.civID }.associate { it.choice to it.delegates })
        assertEquals(mapOf(League.YES to 1), tax.votes.filter { it.voter == a.civID }.associate { it.choice to it.delegates })
    }

    @Test
    fun `no more delegates go to a proposal than all others together could have left`() {
        embargoes(c to a)
        league.members.getValue(c.civID).proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), c.civID, b)
        startSession()
        // the embargo on a weighs 1000, the embargo on c 100; the others have 1 or 2 delegates each, the members count 4 (3 civilizations, 1 city-state)
        league.members.getValue(a.civID).delegatesLeft = 10
        for (civ in listOf(b, c)) league.members.getValue(civ.civID).delegatesLeft = 0
        LeagueAutomation.automateLeague(a)
        val (onUs, onC) = onTable()
        // 1000 -> 4 delegates (the cap of 4), then the other: 100 -> 6 delegates
        assertEquals(4, onUs.votes.filter { it.voter == a.civID }.sumOf { it.delegates })
        assertEquals(6, onC.votes.filter { it.voter == a.civID }.sumOf { it.delegates })
    }

    @Test
    fun `a civilization votes for itself in the World Leader vote`() {
        league.proposals.clear()
        for (member in league.members.values) member.proposalsLeft = 0
        league.proposals.add(LeagueProposal(league.nextProposalId++, "World Leader", false, null, null))
        league.inSession = true
        for (member in league.members.values) member.delegatesLeft = 3
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        val votes = league.proposals.single().votes
        for (civ in majors) assertEquals(listOf(civ.civID), votes.filter { it.voter == civ.civID }.map { it.choice })
    }
    //endregion

    //region Hook
    @Test
    fun `the turn automation takes part in the World Congress`() {
        league.members.getValue(b.civID).proposalsLeft = 1
        NextTurnAutomation.automateCivMoves(b, tradeAndChangeState = false)
        assertEquals(1, league.proposals.count { it.proposer == b.civID })
    }
    //endregion

    //region End to end
    @Test
    fun `the congress runs through several sessions with automated civilizations`() {
        val sessions = HashSet<Int>()
        var votesCast = 0
        for (turn in 1..200) {
            gameInfo.turns++
            gameInfo.league = League.doGameTurn(gameInfo)
            val league = gameInfo.league!!
            for (civ in majors) LeagueAutomation.automateLeague(civ)

            // The AI leaves nothing undone
            if (league.inSession) for (civ in majors) assertEquals("turn $turn", 0, league.getDelegatesLeft(civ))
            else for (civ in majors)
                if (league.canPropose(civ))
                    assertTrue("turn $turn: $civ could still propose", league.getProposableResolutions(civ).isEmpty()
                        && league.activeResolutions.none { league.canProposeRepeal(it.id, civ) })
            if (league.lastResultsTurn >= 0 && sessions.add(league.lastResultsTurn))
                for (result in league.lastResults) votesCast += result.tally.values.sum()
        }
        val league = gameInfo.league!!
        assertTrue("sessions held: $sessions", sessions.size >= 3)
        assertTrue(votesCast > 0)
        // What passed is in effect and valid
        for (active in league.activeResolutions) {
            val resolution = game.ruleset.resolutions[active.resolution]
            assertTrue(resolution != null && resolution.playerProposable)
        }
    }
    //endregion

    //region Proposals and sessions in the diplomacy (CvLeague::DoEnactProposalDiplomacy, FinishSession)
    @Test
    fun `a passed proposal makes the voters for it its supporters`() {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("World's Fair"), null, b)
        startSession()
        val fair = onTable().single()
        for (civ in majors) league.vote(fair.id, civ, league.getDelegatesLeft(civ), League.YES)
        league.doTurn()
        assertEquals(gameInfo.turns, memory(b, a).supportedProposalTurn)
        assertEquals(-1, memory(b, a).foiledProposalTurn)
        assertTrue(memory(b, a).proposalSupportDelta < 0)
    }

    @Test
    fun `a failed proposal makes the voters against it its foilers by their share of the votes against`() {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), c.civID, b)
        startSession()
        val embargo = onTable().single()
        league.vote(embargo.id, a, league.getDelegatesLeft(a), League.NO)
        league.vote(embargo.id, b, league.getDelegatesLeft(b), League.YES)
        league.vote(embargo.id, c, league.getDelegatesLeft(c), League.NO)
        val share = league.getDelegates(a) * 100 / (league.getDelegates(a) + league.getDelegates(c))
        league.doTurn()
        assertEquals(gameInfo.turns, memory(b, a).foiledProposalTurn)
        assertEquals(-1, memory(b, a).supportedProposalTurn)
        assertEquals(share, memory(b, a).proposalSupportDelta)
        assertTrue(OpinionAi.supportedOurProposalScore(b, memory(b, a), game.ruleset.ai) > 0)
    }

    @Test
    fun `the proposer weighs the support it got by the share of the votes each supporter cast`() {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("World's Fair"), null, b)
        startSession()
        val fair = onTable().single()
        for (civ in majors) league.vote(fair.id, civ, league.getDelegatesLeft(civ), League.YES)
        val share = league.getDelegates(a) * 100 / majors.sumOf { league.getDelegates(it) }
        league.doTurn()
        val memory = league.members.getValue(b.civID).alignmentMemory.getValue(a.civID)
        assertEquals(-share, memory.proposalSupportDelta)
        // OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL (-10) towards _MAX (-60) by 2% per percent of the vote
        val rules = game.ruleset.ai
        val expected = -10 + (-60 + 10) * minOf(100, share * 2) / 100
        val score = OpinionAi.supportedOurProposalScore(b, memory, rules)
        assertTrue("support share $share: $score", score == expected || score == expected * 167 / 100)
    }

    @Test
    fun `those who vote for what we dislike lose voting history, the proposer's own outcome changes nothing for it`() {
        for (civ in listOf(a, b, c)) setProduction(civ, 0f)
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("World's Fair"), null, b)
        startSession()
        val fair = onTable().single()
        for (civ in majors) league.vote(fair.id, civ, league.getDelegatesLeft(civ), League.YES)
        val share = league.getDelegates(a) * 100 / majors.sumOf { league.getDelegates(it) }
        league.doTurn()
        // Without production the Fair is -400 for everybody (a dislike, VOTING_HISTORY_SCORE_DESIRE_MULTIPLIER_STANDARD 200 as a minus): c's history of a
        // changes by (100% of a's delegates x 100% + its share x 200%) x -200%; b, the proposer, keeps it
        assertEquals(-((100 + share * 2) * 2), memory(c, a).votingHistoryScore)
        assertEquals(0, memory(b, a).votingHistoryScore)
    }

    @Test
    fun `an attempted embargo on us counts half until one passes`() {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        val memory = league.members.getValue(a.civID).alignmentMemory.getValue(b.civID)
        val rules = game.ruleset.ai
        val attempted = OpinionAi.sanctionedUsScore(a, memory, rules)
        assertTrue("attempted: $attempted", attempted > 0)
        memory.everSanctioned = true
        assertEquals(attempted * 2, OpinionAi.sanctionedUsScore(a, memory, rules))
    }
    //endregion

    //region Vote commitments (CvLeagueAI vote trading)
    private fun proposeEmbargoOnA(): LeagueProposal {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        return onTable().single()
    }

    @Test
    fun `an AI wants the votes of another civilization on the choice it desires most`() {
        val embargo = proposeEmbargoOnA()
        // An embargo on itself scores -1000 for yes; b's proposal made it our rival, whose alignment adds -40 by work-against willingness before
        // No flips the score: yes is -1000 + bonus (under -1000: a strong dislike), no 1000 - bonus (a strong like)
        val bonus = OpinionAi.adjustConditional(-40, a.ai.diplomacyValue(com.unciv.models.ruleset.ai.DiplomacyPersonality.WorkAgainstWillingness))
        assertTrue(bonus < 0)
        assertEquals(-1000 + bonus, evaluator(a).scoreVote(embargo, League.YES))
        assertEquals(1000 - bonus, evaluator(a).scoreVote(embargo, League.NO))
        assertEquals(LeagueDesire.STRONG_DISLIKE, LeagueAutomation.evaluateVoteForTrade(a, embargo.id, League.YES, 1, repeal = false))
        assertEquals(LeagueDesire.STRONG_LIKE, LeagueAutomation.evaluateVoteForTrade(a, embargo.id, League.NO, 1, repeal = false))
        assertEquals("not on the table as a repeal", LeagueDesire.NEVER, LeagueAutomation.evaluateVoteForTrade(a, embargo.id, League.NO, 1, repeal = true))

        val desired = LeagueAutomation.desiredVoteCommitments(a, c).single()
        assertEquals(a.civID, desired.toCiv)
        assertEquals(embargo.id, desired.proposalId)
        assertEquals(League.NO, desired.choice)
        assertTrue(desired.enact)
        assertTrue(league.getCoreVotes(c) > 0)
        assertEquals("the core votes of the civilization asked", league.getCoreVotes(c), desired.votes)
    }

    @Test
    fun `a human is assumed to vote yes on its own proposal and else by its desire without the global terms`() {
        val embargo = proposeEmbargoOnA()
        // a human a: -1000 for the embargo on it: a dislike, so no
        a.playerType = com.unciv.logic.civilization.PlayerType.Human
        assertEquals(League.NO, LeagueAutomation.desiredVoteCommitments(a, c).single().choice)
        // b is the proposer: yes
        b.playerType = com.unciv.logic.civilization.PlayerType.Human
        assertEquals(League.YES, LeagueAutomation.desiredVoteCommitments(b, c).single { it.proposalId == embargo.id }.choice)
        // c scores the embargo on a at -100 (opinion neutral, 1 above competitor): a weak dislike (under -50), so it assumes No
        c.playerType = com.unciv.logic.civilization.PlayerType.Human
        assertEquals(League.NO, LeagueAutomation.desiredVoteCommitments(c, a).single().choice)
    }

    @Test
    fun `committed votes are cast when the session starts and the commitment is used up`() {
        val embargo = proposeEmbargoOnA()
        // A proposal that is not on the table: dropped (first, as a member with no votes left keeps what it could not honor, as in the DLL)
        LeagueAutomation.addVoteCommitment(c, b, 999, League.YES, 1, repeal = false)
        LeagueAutomation.addVoteCommitment(c, b, embargo.id, League.YES, 1, repeal = false)
        startSession()
        assertEquals(1, embargo.votes.filter { it.voter == c.civID && it.choice == League.YES }.sumOf { it.delegates })
        assertTrue(league.members.getValue(c.civID).voteCommitments.isEmpty())
    }

    @Test
    fun `war cancels the commitments to the enemy`() {
        val embargo = proposeEmbargoOnA()
        LeagueAutomation.addVoteCommitment(c, b, embargo.id, League.YES, 1, repeal = false)
        LeagueAutomation.addVoteCommitment(c, a, embargo.id, League.NO, 1, repeal = false)
        c.getDiplomacyManager(b)!!.declareWar()
        assertEquals(listOf(a.civID), league.members.getValue(c.civID).voteCommitments.map { it.toCiv })
    }
    //endregion
}
