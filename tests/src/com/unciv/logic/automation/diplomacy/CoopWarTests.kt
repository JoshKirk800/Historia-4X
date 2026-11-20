package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's coop war decisions (CvDiplomacyAI, Release-5.4.8): [CoopWarAi.coopWarDesireScore] (GetCoopWarDesireScore),
 *  [CoopWarAi.coopWarTarget] (DoTestCoopWarDesire) and [CoopWarAi.willingToAgree] (RespondToCoopWarRequest). a is the AI asked or
 *  asking, b its friend and ally, c the target, all met, neighbours on land, with the same one city each.
 */
@RunWith(GdxTestRunner::class)
class CoopWarTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private var threshold: Float? = null

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        rules = game.ruleset.ai
        threshold = rules.defines[AiDefine.COOP_WAR_DESIRE_THRESHOLD.name]
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(6, 6)) // each within the army range of the others, 6 tiles apart
        c = neutralCiv(HexCoord(6, 0))
        for ((x, y) in listOf(a to b, a to c, b to c)) x.diplomacyFunctions.makeCivilizationsMeet(y)
        makeFriends(a, b)
        for (civ in listOf(a, b)) civ.proximity[c.civID] = Proximity.Neighbors
        c.proximity[a.civID] = Proximity.Neighbors
        val ours = a.ai.diplomacy
        ours.potentialWarTargets.add(c.civID)
        ours.memoryOf(b).approach = MajorCivApproach.Friendly
        ours.memoryOf(b).opinion = MajorCivOpinion.Friend
        ours.memoryOf(c).approach = MajorCivApproach.Hostile
        ours.memoryOf(c).opinion = MajorCivOpinion.Neutral
        ours.biggestCompetitor = c.civID
        for (civ in listOf(a, b)) civ.ai.warStates.rawMilitaryStrengths[c.civID] = StrengthLevel.Average
        a.ai.warStates.targetValues[c.civID] = TargetValue.Average
        seeTargets(a)
        seeTargets(b)
    }

    /** The tests that move the threshold must not leave it moved, in case the rulesets share their defines */
    @After
    fun tearDown() {
        threshold?.let { rules.defines[AiDefine.COOP_WAR_DESIRE_THRESHOLD.name] = it } ?: rules.defines.remove(AiDefine.COOP_WAR_DESIRE_THRESHOLD.name)
    }

    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun makeFriends(x: Civilization, y: Civilization) {
        x.getDiplomacyManager(y)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        y.getDiplomacyManager(x)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
    }

    /** [civ] sees the whole map and works out its attack targets */
    private fun seeTargets(civ: Civilization) {
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        civ.ai.military.updateAttackTargets(civ)
    }

    private fun desire(allyRequest: Boolean = false) = CoopWarAi.coopWarDesireScore(a, b, c, allyRequest, rules)

    /** The term of a preferred attack target of ours: +10, else -10 */
    private fun attackTargetTerm() = if (a.ai.military.havePreferredAttackTarget(c)) 10 else -10

    private fun ourMemory(of: Civilization) = a.ai.diplomacy.memoryOf(of)

    /**
     *  The score of the fixture before the scaling: ally approach friendly +5, opinion friend +5; target approach hostile +10, opinion neutral
     *  -2, friendship level new +2; proximity of both on land 10 + 10; the biggest competitor +25; threat none -2, land dispute none -5,
     *  victory dispute and block levels -4; the attack target term; the strength of the target average for both -5
     */
    private fun raw() = 5 + 5 + 10 - 2 + 2 + 10 + 10 + 25 - 2 - 5 - 4 + attackTargetTerm() - 5

    /** The scaling of the fixture: the target value for a competitor (average 100%), then the economic value of its closest city: its original
     *  capital, the median city, x2 */
    private fun scaled(raw: Int, valueFactor: Int = 100) = raw * valueFactor * 200 / 10000

    //region GetCoopWarDesireScore

    @Test
    fun `the desire score adds the terms of the ally, the target, the proximity, the competitor, the strengths, and scales them`() {
        assertEquals(scaled(raw()), desire())
        assertTrue("above what a coop war must beat or not, as the terms say", raw() in 20..80)
    }

    @Test
    fun `a target value scales the score of a competitor from 40 to 300 percent`() {
        for ((value, factor) in listOf(TargetValue.Impossible to 40, TargetValue.Bad to 60, TargetValue.Difficult to 80, TargetValue.Favorable to 150,
            TargetValue.Soft to 200, TargetValue.Cakewalk to 300)) {
            a.ai.warStates.targetValues[c.civID] = value
            assertEquals("$value", scaled(raw(), factor), desire())
        }
    }

    @Test
    fun `a target we plan war on is worth a thousand more, a friendly one nothing`() {
        ourMemory(c).approach = MajorCivApproach.War
        assertEquals(scaled(raw() + 990), desire()) // war +1000 for hostile +10
        ourMemory(c).approach = MajorCivApproach.Friendly
        assertEquals(0, desire())
    }

    @Test
    fun `a target that is no competitor needs an average value or better, and a hard one is a badness`() {
        a.ai.diplomacy.biggestCompetitor = null
        val noCompetitor = raw() - 35 // the competitor +25 becomes -10
        assertEquals(noCompetitor * 75 * 200 / 10000, desire())
        a.ai.warStates.targetValues[c.civID] = TargetValue.Favorable
        assertEquals(noCompetitor * 125 * 200 / 10000, desire())
        a.ai.warStates.targetValues[c.civID] = TargetValue.Difficult
        assertEquals("badness: no offensive operation against it", 0, desire())
    }

    @Test
    fun `a target we like is a badness, not a coop war`() {
        ourMemory(c).opinion = MajorCivOpinion.Ally
        assertEquals(0, desire())
    }

    @Test
    fun `nothing is wanted against a player that is no potential war target or when we want no new wars`() {
        a.ai.diplomacy.potentialWarTargets.clear()
        assertEquals(0, desire())
        a.ai.diplomacy.potentialWarTargets.add(c.civID)
        assertTrue(desire() > 0)
        a.ai.warStates.noNewWars = true
        assertEquals(0, desire())
    }

    @Test
    fun `nothing is wanted without an attack target for both of us, or against an insane target`() {
        a.ai.diplomacy.insaneTargets.add(c.civID)
        assertEquals(0, desire())
        a.ai.diplomacy.insaneTargets.clear()
        assertTrue(desire() > 0)
        for (tile in game.tileMap.values) tile.setExplored(a, false)
        a.ai.military.updateAttackTargets(a)
        assertFalse(a.ai.military.havePossibleAttackTarget(c))
        assertEquals("we have none", 0, desire())
    }

    @Test
    fun `nothing is wanted when the ally is a friend of the target, was betrayed by it, or has a pact with it, unless it asks`() {
        makeFriends(b, c)
        assertEquals("the ally's friend", 0, desire())
        b.getDiplomacyManager(c)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        c.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertTrue(desire() > 0)

        c.ai.diplomacy.memoryOf(b).doFBrokenTurn = game.gameInfo.turns
        assertEquals("the target's friendship with the ally broken just now", 0, desire())
        game.gameInfo.turns += rules.define(AiDefine.DOF_BROKEN_BACKSTAB_TIMER)
        assertTrue(desire() > 0)

        b.getDiplomacyManager(c)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        c.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertEquals("a pact of the ally with the target", 0, desire())
        assertTrue("which does not matter when it asks us", desire(allyRequest = true) > 0)
    }

    @Test
    fun `nothing is wanted with an untrustworthy ally, after a recent peace, or with an ally we are hostile to or hate`() {
        a.ai.diplomacy.memoryOf(b).untrustworthy = true // a holds b for a backstabber
        assertEquals("untrustworthy", 0, desire())
        a.ai.diplomacy.memoryOf(b).untrustworthy = false

        ourMemory(c).numWarsFought = 1
        ourMemory(c).turnMadePeace = game.gameInfo.turns
        assertEquals("a recent peace with the target", 0, desire())
        ourMemory(c).numWarsFought = 0

        ourMemory(b).approach = MajorCivApproach.Guarded
        assertEquals(0, desire())
        ourMemory(b).approach = MajorCivApproach.Friendly
        ourMemory(b).opinion = MajorCivOpinion.Enemy
        assertEquals(0, desire())
    }

    @Test
    fun `the strength of the target as the weaker of both sides turns the terms to a weak or a strong target`() {
        // Not stronger or equal for us (weak): the weaker of both counts, -20 for weak against -5 for average
        a.ai.warStates.rawMilitaryStrengths[c.civID] = StrengthLevel.Weak
        assertEquals(scaled(raw() + 5 - 20), desire())
        // Stronger or equal for us: pathetic for the ally +20, immense -30
        a.ai.warStates.rawMilitaryStrengths[c.civID] = StrengthLevel.Strong
        b.ai.warStates.rawMilitaryStrengths[c.civID] = StrengthLevel.Pathetic
        assertEquals(scaled(raw() + 5 + 20), desire())
        b.ai.warStates.rawMilitaryStrengths[c.civID] = StrengthLevel.Immense
        a.ai.warStates.rawMilitaryStrengths[c.civID] = StrengthLevel.Immense
        assertEquals(scaled(raw() + 5 - 30), desire())
    }

    //endregion

    //region DoTestCoopWarDesire and RespondToCoopWarRequest

    @Test
    fun `the target to ask a friend to fight is the one that scores above the threshold, for a friend with no objection`() {
        assertEquals(c, CoopWarAi.coopWarTarget(a, b, rules))
        rules.defines["COOP_WAR_DESIRE_THRESHOLD"] = (desire() + 1).toFloat()
        assertNull("at the threshold is not above it", CoopWarAi.coopWarTarget(a, b, rules))
        rules.defines["COOP_WAR_DESIRE_THRESHOLD"] = desire().toFloat()
        assertNull(CoopWarAi.coopWarTarget(a, b, rules))
        rules.defines["COOP_WAR_DESIRE_THRESHOLD"] = (desire() - 1).toFloat()
        assertEquals(c, CoopWarAi.coopWarTarget(a, b, rules))
    }

    @Test
    fun `a coop war is not asked of one that is no friend, or has an approach of guarded or worse, or when we want no new wars`() {
        a.ai.warStates.noNewWars = true
        assertNull(CoopWarAi.coopWarTarget(a, b, rules))
        a.ai.warStates.noNewWars = false
        ourMemory(b).approach = MajorCivApproach.Guarded
        assertNull(CoopWarAi.coopWarTarget(a, b, rules))
        ourMemory(b).approach = MajorCivApproach.Friendly
        a.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertNull(CoopWarAi.coopWarTarget(a, b, rules))
    }

    @Test
    fun `a target is asked about only if it is our biggest competitor or we are deceptive or worse towards it, and not within 30 turns of a rejection`() {
        a.ai.diplomacy.biggestCompetitor = null
        ourMemory(c).approach = MajorCivApproach.Hostile
        ourMemory(c).opinion = MajorCivOpinion.Enemy
        a.ai.warStates.targetValues[c.civID] = TargetValue.Soft
        assertEquals(c, CoopWarAi.coopWarTarget(a, b, rules))
        ourMemory(c).approach = MajorCivApproach.Guarded
        assertNull("only upset enough with a competitor or at deceptive and worse", CoopWarAi.coopWarTarget(a, b, rules))
        ourMemory(c).approach = MajorCivApproach.Hostile
        CoopWarAi.entry(a, b, c).counter = 12
        assertNull("rejected 12 turns ago", CoopWarAi.coopWarTarget(a, b, rules))
        CoopWarAi.entry(a, b, c).counter = 30
        assertEquals(c, CoopWarAi.coopWarTarget(a, b, rules))
    }

    @Test
    fun `a request is answered rejected below the threshold and not made unless valid, prepared when wanted, started at once when bold and close to a good target`() {
        ourMemory(c).approach = MajorCivApproach.War
        // a answers b's request
        assertEquals(CoopWarState.Soon, CoopWarAi.willingToAgree(a, b, c, rules))
        rules.defines["COOP_WAR_DESIRE_THRESHOLD"] = desire(allyRequest = true) + 1f
        assertEquals(CoopWarState.Rejected, CoopWarAi.willingToAgree(a, b, c, rules))
        rules.defines["COOP_WAR_DESIRE_THRESHOLD"] = 40f

        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 8)
        assertEquals("bold and close, but the target is of average value", CoopWarState.Soon, CoopWarAi.willingToAgree(a, b, c, rules))
        a.ai.warStates.targetValues[c.civID] = TargetValue.Favorable
        assertEquals(CoopWarState.Accepted, CoopWarAi.willingToAgree(a, b, c, rules))
        a.proximity[c.civID] = Proximity.Close
        assertEquals("not close to it on land", CoopWarState.Soon, CoopWarAi.willingToAgree(a, b, c, rules))

        a.getDiplomacyManager(c)!!.diplomaticStatus = DiplomaticStatus.War
        c.getDiplomacyManager(a)!!.diplomaticStatus = DiplomaticStatus.War
        assertEquals("a war we are at already is no valid target", CoopWarState.Rejected, CoopWarAi.willingToAgree(a, b, c, rules))
    }

    @Test
    fun `an accepted request makes both plan war on the target and credit each other with the help`() {
        ourMemory(c).approach = MajorCivApproach.War
        CoopWarAi.requestCoopWar(b, a, c, rules)
        assertEquals(CoopWarState.Soon, CoopWarAi.state(b, a, c))
        assertEquals(CoopWarState.Soon, CoopWarAi.state(a, b, c))
        for ((civ, other) in listOf(a to b, b to a)) {
            assertTrue("${civ.civName} credits ${other.civName}", civ.ai.diplomacy.memoryOf(other).recentAssistValue > 0)
            assertEquals(MajorCivApproach.War, civ.ai.diplomacy.approachTowards(c))
        }
    }

    //endregion

    //region Valid targets

    @Test
    fun `a coop war target must be a met major we may declare war on with no friendship or pact, and the request needs a friendship`() {
        assertTrue(CoopWarAi.isValidCoopWarTarget(a, c, atWarException = false))
        assertTrue(CoopWarAi.canRequestCoopWar(a, b, c))
        makeFriends(a, c)
        assertFalse("a friend", CoopWarAi.isValidCoopWarTarget(a, c, atWarException = false))
        a.getDiplomacyManager(c)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        c.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertTrue(CoopWarAi.isValidCoopWarTarget(a, c, atWarException = false))

        a.getDiplomacyManager(c)!!.diplomaticStatus = DiplomaticStatus.War
        c.getDiplomacyManager(a)!!.diplomaticStatus = DiplomaticStatus.War
        assertFalse(CoopWarAi.isValidCoopWarTarget(a, c, atWarException = false))
        assertTrue("a war already under way, when it is allowed", CoopWarAi.isValidCoopWarTarget(a, c, atWarException = true))

        a.getDiplomacyManager(c)!!.diplomaticStatus = DiplomaticStatus.Peace
        c.getDiplomacyManager(a)!!.diplomaticStatus = DiplomaticStatus.Peace
        CoopWarAi.entry(a, b, c).state = CoopWarState.Soon
        assertFalse("already prepared", CoopWarAi.canRequestCoopWar(a, b, c))
        CoopWarAi.entry(a, b, c).state = null
        a.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertFalse("no friendship with the ally", CoopWarAi.canRequestCoopWar(a, b, c))
    }

    //endregion

    @Test
    fun `the victory dispute and block levels of the target move the desire by minus 2 for none and plus 2, 5 and 10`() {
        val base = desire()
        assertEquals(scaled(raw()), base)
        ourMemory(c).victoryDisputeLevel = DisputeLevel.Strong
        assertEquals("-2 becomes +5", scaled(raw() + 7), desire())
        ourMemory(c).victoryBlockLevel = BlockLevel.Fierce
        assertEquals("-2 becomes +10", scaled(raw() + 7 + 12), desire())
    }
}
