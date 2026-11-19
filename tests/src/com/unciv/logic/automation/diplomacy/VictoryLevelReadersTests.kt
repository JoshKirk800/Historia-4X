package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DealDiplomacyValues
import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What reads the victory dispute, victory block, tech block and policy block levels (CvDiplomacyAI.cpp, Release-5.4.8): the four opinion
 *  scores (GetTechBlockLevelScore ...), the approach (part 4 bVictoryConcern, part 5 VICTORY ISSUES, TECH BLOCK, POLICY BLOCK, part 7 war
 *  bonus, the dogpile), the competitors, the friendship and denouncement chances, the promise gate, the denounce weight, open borders and
 *  the war mobilization. Four majors on the Deity difficulty (TechBlockPercent and PolicyBlockPercent 150, VictoryDisputePercent and
 *  VictoryBlockPercent 150), every personality value and approach bias 5, all in the Renaissance (era 3) in the second half of the game.
 */
@RunWith(GdxTestRunner::class)
class VictoryLevelReadersTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        rules = game.ruleset.ai
        game.gameInfo.difficulty = "Deity"
        game.gameInfo.turns = 200
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(12, 0)) // far: no opportunity attack, no deception
        c = civ(HexCoord(-10, 0))
        d = civ(HexCoord(0, 10))
        for (x in listOf(a, b, c, d)) for (y in listOf(a, b, c, d)) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        a.ai.warStates.militaryStrengths[b.civID] = StrengthLevel.Average
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        civ.ai.setGrandStrategyAndPursuit(null)
        civ.ai.primaryVictoryPursuit = VictoryPursuit.Domination // not the scientist or cultural leader the personality may pick
        civ.ai.secondaryVictoryPursuit = null
        game.addCity(civ, game.getTile(at))
        civ.tech.addTechnology("Printing Press")
        return civ
    }

    private fun memory(of: Civilization = b) = a.ai.diplomacy.memoryOf(of)

    //region Opinion scores

    @Test
    fun `the tech block score is for a scientist only, 20 at a strong block in the Renaissance and minus 22 for none`() {
        memory().techBlockLevel = BlockLevel.Strong
        assertEquals("not a scientist", 0, OpinionAi.techBlockLevelScore(a, b, rules))
        a.ai.primaryVictoryPursuit = VictoryPursuit.Science
        // 20 x diplomatic balance 5 x TechBlockPercent 150 / max(500, 1000 - 125 x (3 - 1)) = 15000 / 750
        assertEquals(20, OpinionAi.techBlockLevelScore(a, b, rules))
        memory().techBlockLevel = BlockLevel.Fierce
        assertEquals(30 * 5 * 150 / 750, OpinionAi.techBlockLevelScore(a, b, rules))
        memory().techBlockLevel = BlockLevel.Weak
        assertEquals(10, OpinionAi.techBlockLevelScore(a, b, rules))
        memory().techBlockLevel = BlockLevel.None
        // (-5 - neediness 5) x era 3 / 2 = -15, x 5 x 150 / 500 = -22 (the source truncates toward zero)
        assertEquals(-22, OpinionAi.techBlockLevelScore(a, b, rules))
    }

    @Test
    fun `the policy block score is for a cultural leader only, on the same scale`() {
        memory().policyBlockLevel = BlockLevel.Strong
        a.ai.primaryVictoryPursuit = VictoryPursuit.Science
        assertEquals("a scientist does not mind policies", 0, OpinionAi.policyBlockLevelScore(a, b, rules))
        a.ai.primaryVictoryPursuit = VictoryPursuit.Culture
        assertEquals(20, OpinionAi.policyBlockLevelScore(a, b, rules))
        memory().policyBlockLevel = BlockLevel.None
        assertEquals(-22, OpinionAi.policyBlockLevelScore(a, b, rules))
    }

    @Test
    fun `the victory dispute score is 30 plus 4 per era of theirs at a strong dispute, times competitiveness times 150 percent over 500`() {
        assertEquals("no dispute and no competitor: nothing", 0, OpinionAi.victoryDisputeLevelScore(a, b, rules))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertEquals((30 + 3 * 4) * 5 * 150 / 500, OpinionAi.victoryDisputeLevelScore(a, b, rules))
        assertEquals(63, OpinionAi.victoryDisputeLevelScore(a, b, rules))
        memory().victoryDisputeLevel = DisputeLevel.Fierce
        assertEquals((40 + 12) * 5 * 150 / 500, OpinionAi.victoryDisputeLevelScore(a, b, rules))
        memory().victoryBlockLevel = BlockLevel.Fierce
        assertEquals("the block is not above the dispute: both count... the dispute does", 78, OpinionAi.victoryDisputeLevelScore(a, b, rules))
    }

    @Test
    fun `the victory block score is 30 plus 4 per era of theirs at a strong block and the dispute score does not stack`() {
        memory().victoryBlockLevel = BlockLevel.Strong
        assertEquals(63, OpinionAi.victoryBlockLevelScore(a, b, rules))
        assertEquals("a block above the dispute: the dispute scores nothing", 0, OpinionAi.victoryDisputeLevelScore(a, b, rules))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertEquals("a dispute at the block's level: the block scores nothing", 0, OpinionAi.victoryBlockLevelScore(a, b, rules))
        assertEquals(63, OpinionAi.victoryDisputeLevelScore(a, b, rules))
    }

    @Test
    fun `the scores are nothing when we do not compete for victory`() {
        memory().victoryDisputeLevel = DisputeLevel.Fierce
        c.ai.diplomacy.memoryOf(a).resurrectedUs = true // not a: another's resurrection... a was resurrected by c
        a.ai.diplomacy.memoryOf(c).resurrectedUs = true
        assertEquals(0, OpinionAi.victoryDisputeLevelScore(a, b, rules))
        assertEquals(0, OpinionAi.victoryBlockLevelScore(a, b, rules))
    }

    @Test
    fun `the opinion weight adds the four scores`() {
        val before = OpinionAi.opinionWeight(a, b, rules)
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertEquals(before + 63, OpinionAi.opinionWeight(a, b, rules))
    }
    //endregion

    //region Approach

    /** The weights the stage [name] of [ApproachAi.approachWeights] adds, until the stage that follows [next] */
    private fun change(name: String, next: String): IntArray {
        val steps = ArrayList<Pair<String, IntArray>>()
        ApproachAi.debugSink = { stage, w -> steps.add(stage to w.copyOf()) }
        try { ApproachAi.approachWeights(a, b, null, rules) } finally { ApproachAi.debugSink = null }
        val from = steps.indexOfFirst { it.first.startsWith(name) }
        val to = steps.indexOfFirst { it.first.startsWith(next) }
        return IntArray(7) { steps[to].second[it] - steps[from].second[it] }
    }

    private fun victoryIssues() = change("Victory issues (part 5)", "Tech and policy block (part 5)")
    private fun blocks() = change("Tech and policy block (part 5)", "nukes")
    private val war = MajorCivApproach.War.ordinal
    private val hostile = MajorCivApproach.Hostile.ordinal
    private val deceptive = MajorCivApproach.Deceptive.ordinal
    private val guarded = MajorCivApproach.Guarded.ordinal
    private val friendly = MajorCivApproach.Friendly.ordinal
    private val neutral = MajorCivApproach.Neutral.ordinal

    @Test
    fun `victory issues without a dispute make us friendlier, with one more guarded`() {
        // bias 5 x their era 3 x competitiveness 5 x the larger of the difficulty's two percents 150 = 11250
        assertEquals(11250 / 500, victoryIssues()[friendly])
        assertEquals(11250 / 500, victoryIssues()[neutral])
        memory().victoryDisputeLevel = DisputeLevel.Weak
        assertEquals(11250 / 1000, victoryIssues()[guarded])
        assertEquals(11250 / 1000, victoryIssues()[deceptive])
        assertEquals(0, victoryIssues()[war])
        memory().victoryBlockLevel = BlockLevel.Strong // the larger level counts
        assertEquals(11250 / 750, victoryIssues()[war])
        assertEquals(11250 / 750, victoryIssues()[guarded])
        assertEquals(0, victoryIssues()[friendly])
        memory().victoryDisputeLevel = DisputeLevel.Fierce
        assertEquals(11250 / 500, victoryIssues()[hostile])
        assertEquals(11250 / 500, victoryIssues()[war])
        assertEquals(0, victoryIssues()[deceptive])
    }

    @Test
    fun `victory issues count after turn 150 only`() {
        game.gameInfo.turns = 150
        assertEquals(0, victoryIssues()[friendly])
    }

    @Test
    fun `a strong tech block adds war, guard and hostility, 5 each at a multiplier of 1, and a scientist doubles it`() {
        memory().techBlockLevel = BlockLevel.Strong
        // balance 5 x multiplier 1 x 150 x 100 / max(500, 1000 - 125 x 2) = 100: bias 5 x 100 / 100
        assertEquals(5, blocks()[war])
        assertEquals(5, blocks()[guarded])
        assertEquals(5, blocks()[hostile])
        assertEquals("deception does not apply to a far player", 0, blocks()[deceptive])
        a.ai.primaryVictoryPursuit = VictoryPursuit.Science
        assertEquals("multiplier 2", 10, blocks()[war])
    }

    @Test
    fun `a scientist leans friendly towards a player that is no tech threat, and the fierce block of a scientist rises by one`() {
        a.ai.primaryVictoryPursuit = VictoryPursuit.Science
        // (balance 5 + neediness 5) x multiplier 2 x 150 / 10 = 300: bias 5 x 300 / 100
        assertEquals(15, blocks()[friendly])
        assertEquals(15, blocks()[neutral])
        memory().techBlockLevel = BlockLevel.Fierce
        // multiplier 2 + 1: balance 5 x 3 x 150 x 100 / 750 = 300
        assertEquals(15, blocks()[war])
        assertEquals(15, blocks()[hostile])
        assertEquals(0, blocks()[friendly])
    }

    @Test
    fun `a weak policy block of a cultural leader adds war too, a strong one guard and hostility`() {
        a.ai.primaryVictoryPursuit = VictoryPursuit.Culture
        memory().policyBlockLevel = BlockLevel.Weak
        // multiplier 2 (cultural): balance 5 x 2 x 150 x 100 / 750 = 200: bias 5 x 200 / 100
        assertEquals(10, blocks()[war])
        assertEquals(10, blocks()[guarded])
        assertEquals(10, blocks()[deceptive])
        memory().policyBlockLevel = BlockLevel.Strong
        assertEquals(10, blocks()[hostile])
    }

    @Test
    fun `the blocks do not count before the Ancient era has passed for both, or when we do not compete for victory`() {
        memory().techBlockLevel = BlockLevel.Strong
        a.ai.diplomacy.memoryOf(c).resurrectedUs = true
        assertEquals(0, blocks()[war])
    }

    @Test
    fun `a strong victory dispute or block, a fierce tech or policy block are a victory concern`() {
        assertFalse(ApproachAi.victoryConcern(a, b, rules))
        memory().victoryDisputeLevel = DisputeLevel.Weak
        assertFalse(ApproachAi.victoryConcern(a, b, rules))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertTrue(ApproachAi.victoryConcern(a, b, rules))
        memory().victoryDisputeLevel = DisputeLevel.None
        memory().victoryBlockLevel = BlockLevel.Strong
        assertTrue(ApproachAi.victoryConcern(a, b, rules))
        memory().victoryBlockLevel = BlockLevel.None
        memory().techBlockLevel = BlockLevel.Strong
        assertFalse("a strong tech block is a concern for the spaceship only", ApproachAi.victoryConcern(a, b, rules))
        a.ai.setGrandStrategyAndPursuit(com.unciv.logic.automation.strategy.GrandStrategyAi.SPACESHIP)
        assertTrue(ApproachAi.victoryConcern(a, b, rules))
        a.ai.setGrandStrategyAndPursuit(null)
        memory().techBlockLevel = BlockLevel.Fierce
        assertTrue(ApproachAi.victoryConcern(a, b, rules))
        memory().techBlockLevel = BlockLevel.None
        memory().policyBlockLevel = BlockLevel.Fierce
        assertTrue(ApproachAi.victoryConcern(a, b, rules))
    }
    //endregion

    //region Competitors

    @Test
    fun `a strong victory dispute or block makes a major competitor, a weak one does not`() {
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertFalse(CompetitorAi.isMajorCompetitor(a, b))
        memory().victoryDisputeLevel = DisputeLevel.Weak
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertFalse(CompetitorAi.isMajorCompetitor(a, b))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertTrue(CompetitorAi.isMajorCompetitor(a, b))
        memory().victoryDisputeLevel = DisputeLevel.None
        memory().victoryBlockLevel = BlockLevel.Strong
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertTrue(CompetitorAi.isMajorCompetitor(a, b))
    }

    @Test
    fun `a strong policy block makes a competitor for a culture victory, a strong tech block for a spaceship`() {
        memory().policyBlockLevel = BlockLevel.Strong
        memory().techBlockLevel = BlockLevel.Strong
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertFalse("we go for neither", CompetitorAi.isMajorCompetitor(a, b))
        a.ai.setGrandStrategyAndPursuit(com.unciv.logic.automation.strategy.GrandStrategyAi.CULTURE)
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertTrue(CompetitorAi.isMajorCompetitor(a, b))
        a.ai.setGrandStrategyAndPursuit(com.unciv.logic.automation.strategy.GrandStrategyAi.SPACESHIP)
        memory().techBlockLevel = BlockLevel.None
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertFalse("a spaceship race with no tech block", CompetitorAi.isMajorCompetitor(a, b))
        memory().techBlockLevel = BlockLevel.Strong
        CompetitorAi.updateMajorCompetitors(a, listOf(b, c, d), rules)
        assertTrue(CompetitorAi.isMajorCompetitor(a, b))
    }

    @Test
    fun `a strong tech or policy block makes an early-game competitor`() {
        // an early game: our era is at most the Medieval
        val early = game.addCiv()
        game.addCity(early, game.getTile(HexCoord(8, 8)))
        early.diplomacyFunctions.makeCivilizationsMeet(b)
        val ours = early.ai.diplomacy.memoryOf(b)
        assertFalse(CompetitorAi.isEarlyGameCompetitor(early, b, rules))
        ours.techBlockLevel = BlockLevel.Weak
        assertFalse(CompetitorAi.isEarlyGameCompetitor(early, b, rules))
        ours.techBlockLevel = BlockLevel.Strong
        assertTrue(CompetitorAi.isEarlyGameCompetitor(early, b, rules))
        ours.techBlockLevel = BlockLevel.None
        ours.policyBlockLevel = BlockLevel.Strong
        assertTrue(CompetitorAi.isEarlyGameCompetitor(early, b, rules))
    }
    //endregion

    //region Friendship, promises, denouncing

    @Test
    fun `a victory dispute and block take our competitiveness off the chance to end a friendship`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship() // friends: +2
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Competitor)
        a.ai.setDiplomacyValue(DiplomacyPersonality.Loyalty, 0)
        a.ai.setDiplomacyValue(DiplomacyPersonality.VictoryCompetitiveness, 6)
        memory().doFCounter = 20
        // 10 + loyalty 0 + friends 2 = 12 against 0
        assertFalse(FriendshipPlanningAi.isEndDoFAcceptable(a, b))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertFalse("12 - 6 = 6", FriendshipPlanningAi.isEndDoFAcceptable(a, b))
        memory().victoryBlockLevel = BlockLevel.Strong
        assertTrue("12 - 6 - 6 = 0", FriendshipPlanningAi.isEndDoFAcceptable(a, b))
    }

    @Test
    fun `a promise is refused to a player in a strong victory dispute or block with us`() {
        game.gameInfo.turns = 60
        assertTrue(PromiseAi.isStopSpyingAcceptable(a, b))
        memory().victoryDisputeLevel = DisputeLevel.Weak
        assertTrue(PromiseAi.isStopSpyingAcceptable(a, b))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertFalse(PromiseAi.isStopSpyingAcceptable(a, b))
        memory().victoryDisputeLevel = DisputeLevel.None
        memory().victoryBlockLevel = BlockLevel.Strong
        assertFalse(PromiseAi.isStopSpyingAcceptable(a, b))
    }

    @Test
    fun `a strong victory dispute and a strong block weigh 4 each on denouncing`() {
        a.ai.setDiplomacyValue(DiplomacyPersonality.DenounceWillingness, 6)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Hostile)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Enemy)
        assertEquals("6 + hostile 6 + enemy 5", 17, DiplomacyAutomation.denounceWeight(a, b, bias = false))
        memory().victoryDisputeLevel = DisputeLevel.Weak
        assertEquals(17, DiplomacyAutomation.denounceWeight(a, b, bias = false))
        memory().victoryDisputeLevel = DisputeLevel.Strong
        assertEquals(21, DiplomacyAutomation.denounceWeight(a, b, bias = false))
        memory().victoryBlockLevel = BlockLevel.Fierce
        assertEquals(25, DiplomacyAutomation.denounceWeight(a, b, bias = false))
    }
    //endregion

    //region Open borders, war mobilization

    @Test
    fun `we give no open borders to a victory rival that is popular with us, familiar and rising or influential`() {
        assertTrue(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        a.totalCultureForContests = 100
        b.culture.addInfluence(a, 70) // 70 percent: popular (60), the trend is not falling
        assertTrue("popular, but no dispute or block", DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        memory().victoryDisputeLevel = DisputeLevel.Weak
        assertFalse(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        memory().victoryDisputeLevel = DisputeLevel.None
        memory().victoryBlockLevel = BlockLevel.Weak
        assertFalse(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
        a.ai.diplomacy.memoryOf(c).resurrectedUs = true // not competing for victory
        assertTrue(DealDiplomacyValues.isWillingToGiveOpenBordersToPlayer(a, b))
    }

    @Test
    fun `war mobilization weighs 5 for a victory block above weak and 5 for a victory dispute above weak`() {
        val base = EconomicMilitaryStrategyTests.warMobilizationWeight(a)
        memory().victoryBlockLevel = BlockLevel.Weak
        memory().victoryDisputeLevel = DisputeLevel.Weak
        assertEquals(base, EconomicMilitaryStrategyTests.warMobilizationWeight(a))
        memory().victoryBlockLevel = BlockLevel.Strong
        assertEquals(base + 5, EconomicMilitaryStrategyTests.warMobilizationWeight(a))
        memory().victoryDisputeLevel = DisputeLevel.Fierce
        assertEquals(base + 10, EconomicMilitaryStrategyTests.warMobilizationWeight(a))
    }
    //endregion
}
