package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.GrandStrategyGuess
import com.unciv.logic.automation.strategy.GuessConfidence
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's DoUpdateTechBlockLevels, DoUpdatePolicyBlockLevels, DoUpdateVictoryDisputeLevels and DoUpdateVictoryBlockLevels
 *  (CvDiplomacyAI.cpp, Release-5.4.8). Four majors on the Deity difficulty (VP's HandicapInfos: TechBlockMod 5, TechBlockPercent 150,
 *  PolicyBlockMod 3, VictoryDisputeMod 3, VictoryBlockMod 5), every personality value 5, all in the Renaissance (era 3, so the game's
 *  era is 3); a is the AI that judges. The expected numbers are worked out from the source in each test.
 */
@RunWith(GdxTestRunner::class)
class VictoryLevelsTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization
    private val ancientTechs = listOf("Pottery", "Animal Husbandry", "Archery", "Mining", "Masonry", "Sailing")
    private val branches = listOf("Tradition", "Liberty", "Honor", "Piety", "Patronage")

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        rules = game.ruleset.ai
        game.gameInfo.difficulty = "Deity"
        game.gameInfo.turns = 200
        game.gameInfo.gameParameters.victoryTypes.addAll(listOf("Domination", "Scientific", "Cultural", "Diplomatic"))
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(10, 0))
        c = civ(HexCoord(-10, 0))
        d = civ(HexCoord(0, 10))
        for (x in listOf(a, b, c, d)) for (y in listOf(a, b, c, d)) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
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

    private fun memory(of: Civilization) = a.ai.diplomacy.memoryOf(of)

    //region Tech block

    private fun techLead(by: Int, civ: Civilization = b) = ancientTechs.take(by).forEach { civ.tech.addTechnology(it) }

    private fun techBlock(): BlockLevel {
        VictoryLevelsAi.updateTechBlockLevels(a, rules)
        return memory(b).techBlockLevel
    }

    @Test
    fun `a lead in techs is weighed by the diplomatic balance plus the difficulty's mod, weak from 10, strong from 20, fierce from 30`() {
        techLead(1)
        assertEquals("1 x 5 + 5 = 10", BlockLevel.Weak, techBlock())
        techLead(3)
        assertEquals("3 x 5 + 5 = 20", BlockLevel.Strong, techBlock())
        techLead(6)
        assertEquals("6 x 5 + 5 = 35", BlockLevel.Fierce, techBlock())
    }

    @Test
    fun `no lead, or ours, is no block`() {
        assertEquals("tied: 0 is not a lead", BlockLevel.None, techBlock())
        techLead(3, a)
        assertEquals("we are ahead", BlockLevel.None, techBlock())
    }

    @Test
    fun `a research agreement or a defensive pact takes one off the lead, unless they are untrustworthy`() {
        techLead(3)
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.ResearchAgreement, 10)
        assertEquals("(3 - 1) x 5 + 5 = 15", BlockLevel.Weak, techBlock())
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertEquals("(3 - 2) x 5 + 5 = 10", BlockLevel.Weak, techBlock())
        memory(b).untrustworthy = true
        assertEquals("3 x 5 + 5 = 20: no allowance for a backstabber", BlockLevel.Strong, techBlock())
    }

    @Test
    fun `a scientist adds one, and no space race takes one off`() {
        techLead(4)
        assertEquals("4 x 5 + 5 = 25", BlockLevel.Strong, techBlock())
        a.ai.primaryVictoryPursuit = VictoryPursuit.Science
        assertEquals("(4 + 1) x 5 + 5 = 30", BlockLevel.Fierce, techBlock())
        a.ai.primaryVictoryPursuit = null
        game.gameInfo.gameParameters.victoryTypes.remove("Scientific")
        assertEquals("(4 - 1) x 5 + 5 = 20", BlockLevel.Strong, techBlock())
    }

    @Test
    fun `nobody competing for victory has no block, and a human's shadow AI keeps its levels`() {
        techLead(6)
        memory(b).techBlockLevel = BlockLevel.Weak
        memory(c).resurrectedUs = true // resurrected by another civilization: not competing
        assertEquals(BlockLevel.None, techBlock())
        memory(c).resurrectedUs = false
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.getTile(HexCoord(6, 6)))
        human.diplomacyFunctions.makeCivilizationsMeet(b)
        human.ai.diplomacy.memoryOf(b).techBlockLevel = BlockLevel.Strong
        VictoryLevelsAi.updateTechBlockLevels(human, rules)
        assertEquals(BlockLevel.Strong, human.ai.diplomacy.memoryOf(b).techBlockLevel)
    }
    //endregion

    //region Policy block

    /** [civ] owns [count] policies: the openers of the first [count] branches */
    private fun adopt(civ: Civilization, count: Int) {
        for (name in branches.take(count)) {
            if (civ.policies.isAdopted(name)) continue
            civ.policies.freePolicies++
            civ.policies.adopt(game.ruleset.policyBranches[name]!!)
        }
    }

    private fun policyBlock(): BlockLevel {
        VictoryLevelsAi.updatePolicyBlockLevels(a, rules)
        return memory(b).policyBlockLevel
    }

    @Test
    fun `the policies owned count the openers of the branches`() {
        assertEquals(0, VictoryLevelsAi.numPoliciesOwned(a))
        adopt(a, 2)
        assertEquals(2, VictoryLevelsAi.numPoliciesOwned(a))
    }

    @Test
    fun `a lead in policies is weak from 8, strong from 16 and fierce from 24`() {
        adopt(b, 2)
        assertEquals("2 x 5 + 3 = 13", BlockLevel.Weak, policyBlock())
        adopt(b, 3)
        assertEquals("3 x 5 + 3 = 18", BlockLevel.Strong, policyBlock())
        adopt(b, 4)
        assertEquals("4 x 5 + 3 = 23", BlockLevel.Strong, policyBlock())
        adopt(b, 5)
        assertEquals("5 x 5 + 3 = 28", BlockLevel.Fierce, policyBlock())
    }

    @Test
    fun `a tie is no lead`() {
        adopt(a, 2)
        adopt(b, 2)
        assertEquals(BlockLevel.None, policyBlock())
    }

    @Test
    fun `we never mind the policies of a player that is behind us`() {
        adopt(a, 3)
        adopt(b, 2)
        assertEquals("2 against 3", BlockLevel.None, policyBlock())
    }

    @Test
    fun `with at most one policy of ours a lead of one is disregarded`() {
        adopt(a, 1)
        adopt(b, 2)
        assertEquals("1 x 5 + 3 = 8 would be weak", BlockLevel.None, policyBlock())
        adopt(b, 3)
        assertEquals("a lead of two: 2 x 5 + 3 = 13", BlockLevel.Weak, policyBlock())
    }

    @Test
    fun `a cultural leader adds one to the lead, no culture victory takes one off`() {
        adopt(b, 2)
        a.ai.primaryVictoryPursuit = VictoryPursuit.Culture
        assertEquals("(2 + 1) x 5 + 3 = 18", BlockLevel.Strong, policyBlock())
        a.ai.primaryVictoryPursuit = VictoryPursuit.Domination
        adopt(b, 3)
        game.gameInfo.gameParameters.victoryTypes.remove("Cultural")
        assertEquals("(3 - 1) x 5 + 3 = 13", BlockLevel.Weak, policyBlock())
    }
    //endregion

    //region Victory dispute

    private fun serious(strategy: String) {
        a.ai.setGrandStrategyAndPursuit(strategy)
        a.ai.grandStrategyPriorities[strategy] = 600
    }

    private fun guess(strategy: String?, confidence: GuessConfidence?, of: Civilization = b) {
        a.ai.grandStrategyGuesses[of.civID] = GrandStrategyGuess(strategy, confidence)
    }

    private fun dispute(): DisputeLevel {
        VictoryLevelsAi.updateVictoryDisputeLevels(a, rules)
        return memory(b).victoryDisputeLevel
    }

    @Test
    fun `players going for our victory are weighed by the confidence of the guess, the era, our competitiveness and the difficulty`() {
        serious(GrandStrategyAi.SPACESHIP)
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Positive)
        assertEquals("(25 - (6 - 3)) x 5 + 3 = 113", DisputeLevel.Fierce, dispute())
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Likely)
        assertEquals("(15 - 3) x 5 + 3 = 63", DisputeLevel.Strong, dispute())
        a.ai.setDiplomacyValue(DiplomacyPersonality.VictoryCompetitiveness, 3)
        assertEquals("(15 - 3) x 3 + 3 = 39", DisputeLevel.Weak, dispute())
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Unsure)
        a.ai.setDiplomacyValue(DiplomacyPersonality.VictoryCompetitiveness, 5)
        assertEquals("(5 - 3) x 5 + 3 = 13", DisputeLevel.None, dispute())
    }

    @Test
    fun `no dispute with another grand strategy or no guess, nor with an ally in peace`() {
        serious(GrandStrategyAi.SPACESHIP)
        guess(GrandStrategyAi.CULTURE, GuessConfidence.Positive)
        assertEquals(DisputeLevel.None, dispute())
        guess(null, null)
        assertEquals(DisputeLevel.None, dispute())
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Positive)
        memory(b).opinion = MajorCivOpinion.Ally
        assertEquals("an ally is no rival while at peace", DisputeLevel.None, dispute())
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals("at war, the ally is one", DisputeLevel.Fierce, dispute())
    }

    @Test
    fun `no levels in the first 150 turns, and none unless we are serious about victory`() {
        serious(GrandStrategyAi.SPACESHIP)
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Positive)
        memory(b).victoryDisputeLevel = DisputeLevel.Weak
        game.gameInfo.turns = 150
        assertEquals("turn 150: not updated", DisputeLevel.Weak, dispute())
        game.gameInfo.turns = 151
        assertEquals(DisputeLevel.Fierce, dispute())
        a.ai.grandStrategyPriorities[GrandStrategyAi.SPACESHIP] = 500
        assertEquals("a priority of 500 is not above 500", DisputeLevel.None, dispute())
    }
    //endregion

    //region Victory block

    private fun block(): BlockLevel {
        VictoryLevelsAi.updateVictoryBlockLevels(a, rules)
        return memory(b).victoryBlockLevel
    }

    @Test
    fun `a player going for another victory is weighed by the confidence of the guess plus our competitiveness, meanness, balance and the era`() {
        serious(GrandStrategyAi.SPACESHIP)
        guess(GrandStrategyAi.CULTURE, GuessConfidence.Positive)
        assertEquals("20 + (5 + 5 + 5 + 3) + 5 = 43", BlockLevel.Fierce, block())
        guess(GrandStrategyAi.CULTURE, GuessConfidence.Likely)
        assertEquals("15 + 18 + 5 = 38", BlockLevel.Strong, block())
        guess(GrandStrategyAi.CULTURE, GuessConfidence.Unsure)
        assertEquals("5 + 18 + 5 = 28", BlockLevel.Weak, block())
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Positive)
        assertEquals("the same victory is a dispute, not a block", BlockLevel.None, block())
    }

    @Test
    fun `a friend is no block while we are at peace`() {
        serious(GrandStrategyAi.SPACESHIP)
        guess(GrandStrategyAi.CULTURE, GuessConfidence.Positive)
        memory(b).opinion = MajorCivOpinion.Friend
        assertEquals(BlockLevel.None, block())
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(BlockLevel.Fierce, block())
    }

    @Test
    fun `a conqueror is no block before it holds a third of the majors' capitals`() {
        serious(GrandStrategyAi.CULTURE)
        guess(GrandStrategyAi.CONQUEST, GuessConfidence.Positive)
        assertEquals("20 + 18 + 5", BlockLevel.Fierce, block())
        c.cities.first().moveToCiv(b) // 1 major conquered, a third of 4 majors ever rounded down is 1
        assertEquals("no severe warmonger threat and a third conquered: no block", BlockLevel.None, block())
        memory(b).warmongerThreat = AiThreat.Severe
        assertEquals("a severe threat stays a block", BlockLevel.Fierce, block())
    }

    @Test
    fun `a diplomatic rival is no block without a World Congress`() {
        serious(GrandStrategyAi.CULTURE)
        guess(GrandStrategyAi.UNITED_NATIONS, GuessConfidence.Positive)
        assertEquals(BlockLevel.None, block())
    }

    @Test
    fun `a spaceship rival is a block when it leads in techs, has a project or a strong tech block, else not`() {
        serious(GrandStrategyAi.CULTURE)
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Positive)
        techLead(3)
        assertEquals("ahead of all 3 others: 20 + 18 + 5", BlockLevel.Fierce, block())
        for (civ in listOf(a, c, d)) techLead(4, civ)
        assertEquals("behind all 3 others: 0 of 3 / 2 = 1", BlockLevel.None, block())
        memory(b).techBlockLevel = BlockLevel.Strong
        assertEquals("a strong tech block counts as the race", BlockLevel.Fierce, block())
        memory(b).techBlockLevel = BlockLevel.None
        guess(GrandStrategyAi.SPACESHIP, GuessConfidence.Unsure)
        assertEquals("not racing", BlockLevel.None, block())
        b.ai.apolloProgramBuilt = true
        assertEquals("the Apollo Program counts as a project: 5 + 10 + 18 + 5", BlockLevel.Strong, block())
    }

    @Test
    fun `a cultural rival that influences nobody and is no spammer is a block, and one that is influential on two is not`() {
        serious(GrandStrategyAi.SPACESHIP)
        guess(GrandStrategyAi.CULTURE, GuessConfidence.Positive)
        assertEquals(BlockLevel.Fierce, block())
        for (target in listOf(c, d)) {
            target.totalCultureForContests = 100
            b.culture.addInfluence(target, 100)
        }
        assertEquals("influential on 2 civilizations, no spammer, no strong policy block", BlockLevel.None, block())
        memory(b).wonderSpammer = true
        assertEquals("a wonder spammer is still a block", BlockLevel.Fierce, block())
    }
    //endregion
}
