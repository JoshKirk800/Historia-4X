package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.automation.unit.EspionageAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyBonus
import com.unciv.models.ruleset.SpyBonusKind
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  A city-state's election under Vox Populi (CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15145-15386; CvGame::GetTurnsUntilMinorCivElection, V:CvGame.cpp:11676-11693; the streak,
 *  V:CvEspionageClasses.cpp:2902-2906) and BALANCE_NO_COUPS (CvPlayerEspionage::CanStageCoup, 3590-3598). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Only with
 *  [ModConstants.espionageLikeVp]. Three majors [a], [b], [c] and a city-state [cs]; each of [a] and [b] has a spy rigging in the capital of the city-state. The numbers are worked by hand.
 */
@RunWith(GdxTestRunner::class)
class RiggedElectionTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var cs: Civilization
    private lateinit var csCity: City
    private lateinit var spyA: Spy
    private lateinit var spyB: Spy
    private val addedBonuses = ArrayList<String>()

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(9)
        a = game.addCiv(isPlayer = true)
        b = game.addCiv()
        c = game.addCiv()
        cs = game.addCiv(cityStateType = "Cultured")
        game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        game.addCity(b, game.tileMap[0, 3], replacePalace = true)
        game.addCity(c, game.tileMap[0, -3], replacePalace = true)
        csCity = game.addCity(cs, game.tileMap[4, 0], replacePalace = true)
        for (civ in listOf(a, b, c)) {
            civ.diplomacyFunctions.makeCivilizationsMeet(cs)
            for (tile in game.tileMap.values) tile.setExplored(civ, true)
        }
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        game.gameInfo.turns = 30   // a multiple of the interval 15: an election turn
        spyA = rigger(a)
        spyB = rigger(b)
        c.espionageManager.addSpy()   // c owns a spy, in the hideout, so that every major has one
    }

    @After
    fun tearDown() {
        for (name in addedBonuses) game.ruleset.spyBonuses.remove(name)
    }

    private fun rigger(civ: Civilization): Spy {
        val spy = civ.espionageManager.addSpy()
        spy.moveTo(csCity)
        var guard = 0
        while (spy.action == SpyAction.Moving && guard++ < 20) civ.espionageManager.endTurn()
        assertEquals(SpyAction.RiggingElections, spy.action)
        return spy
    }

    private fun influence(civ: Civilization) = cs.getDiplomacyManager(civ)!!.getInfluence()
    private fun setInfluence(civ: Civilization, amount: Float) = cs.getDiplomacyManager(civ)!!.setInfluenceWithoutSideEffects(amount)
    private fun streak(civ: Civilization) = cs.getDiplomacyManager(civ)!!.consecutiveRiggings

    /** A civilization in era [n]: it learns a technology of the era (Ancient, Classical, Medieval) */
    private fun inEra(civ: Civilization, n: Int) {
        civ.tech.addTechnology(listOf("Agriculture", "Writing", "Education")[n])
        assertEquals(n, civ.getEraNumber())
    }

    /** The only rigger with votes is [winner]: every other spy has none, and every civilization is in the same era */
    private fun onlyHasVotes(winner: Spy, votes: Int = 100) {
        for (spy in listOf(spyA, spyB)) spy.spendNetworkPoints(spy.networkPoints)
        winner.addRiggingVotes(votes)
    }

    @Test
    fun `a rigging spy gathers its rank times 100 votes each turn`() {
        spyA.spendNetworkPoints(spyA.networkPoints)
        a.espionageManager.endTurn()
        assertEquals(100, spyA.networkPoints)
        a.espionageManager.endTurn()
        assertEquals("rank 1, so 100 more", 200, spyA.networkPoints)
        spyA.levelUpSpy(1)
        a.espionageManager.endTurn()
        assertEquals("rank 2 gathers 200", 400, spyA.networkPoints)
    }

    @Test
    fun `votes are not intel and earn no passive bonus`() {
        game.ruleset.spyBonuses["Early"] = SpyBonus().apply { name = "Early"; kind = SpyBonusKind.Passive; networkPoints = 50 }
        addedBonuses += "Early"
        spyA.spendNetworkPoints(spyA.networkPoints)
        repeat(5) { a.espionageManager.endTurn() }
        assertTrue("500 votes are stored", spyA.networkPoints >= 500)
        assertEquals("but the intel ledger is untouched, so no bonus is earned", 0, spyA.maxNetworkPoints)
        assertEquals(emptyList<SpyBonus>(), spyA.getBonuses())
    }

    @Test
    fun `nobody wins an election nobody gathered votes for`() {
        for (spy in listOf(spyA, spyB)) spy.spendNetworkPoints(spy.networkPoints)
        setInfluence(a, 20f); setInfluence(b, 20f); setInfluence(c, 20f)
        cs.cityStateFunctions.holdElectionsLikeVp()
        assertEquals(20f, influence(a), 0f)
        assertEquals(20f, influence(b), 0f)
        assertEquals(20f, influence(c), 0f)
    }

    @Test
    fun `the only spy with votes wins 30 times its era and the others lose 5 times the game's era`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)   // Classical: the era of everybody, so the game's era is 1 too
        for (civ in listOf(a, b, c)) setInfluence(civ, 20f)
        onlyHasVotes(spyA)

        cs.cityStateFunctions.holdElectionsLikeVp()

        assertEquals("30 x era 1 x no streak", 20f + 30f, influence(a), 0f)
        assertEquals("5 x game era 1, though b has a spy and c has none", 20f - 5f, influence(b), 0f)
        assertEquals("a major with no spy loses too (V:CvMinorCivAI.cpp:15215 loops over every major)", 20f - 5f, influence(c), 0f)
    }

    @Test
    fun `the winner's era and the game's era are different eras`() {
        inEra(a, 2); inEra(b, 0); inEra(c, 0)    // a Medieval, b and c Ancient: the game's era is (2 + 0 + 0) / 3 + 0.5 = 1
        for (civ in listOf(a, b, c)) setInfluence(civ, 20f)
        onlyHasVotes(spyA)

        cs.cityStateFunctions.holdElectionsLikeVp()

        assertEquals("the winner's own era, 2: 30 x 2", 20f + 60f, influence(a), 0f)
        assertEquals("the game's era, 1: 5 x 1", 20f - 5f, influence(b), 0f)
    }

    @Test
    fun `a loser cannot lose more than it has and one with no influence loses nothing`() {
        for (civ in listOf(a, b, c)) inEra(civ, 2)       // the game's era is 2: 5 x 2 = 10 to lose
        setInfluence(a, 0f); setInfluence(b, 4f); setInfluence(c, 0f)
        onlyHasVotes(spyA)

        cs.cityStateFunctions.holdElectionsLikeVp()

        assertEquals("b had 4 and could lose 10", 0f, influence(b), 0f)
        assertEquals("c had nothing to lose", 0f, influence(c), 0f)
    }

    @Test
    fun `a major at war with the city-state loses nothing`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)
        setInfluence(b, 30f)
        cs.getDiplomacyManager(b)!!.declareWar()
        onlyHasVotes(spyA)
        cs.cityStateFunctions.holdElectionsLikeVp()
        cs.getDiplomacyManager(b)!!.makePeace()
        cs.getDiplomacyManager(b)!!.otherCiv.getDiplomacyManager(cs)!!.makePeace()
        assertEquals("the stored 30 is not touched at war (the effective influence is -60)", 30f, influence(b), 0f)
    }

    @Test
    fun `the streak grows when every major owns a spy and makes the next win 40 percent bigger`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)
        for (civ in listOf(a, b, c)) setInfluence(civ, 0f)
        onlyHasVotes(spyA)
        cs.cityStateFunctions.holdElectionsLikeVp()
        assertEquals("a first win: 30", 30f, influence(a), 0f)
        assertEquals("every major owns a spy (c's is in the hideout), so the streak is 1", 1, streak(a))

        onlyHasVotes(spyA)
        cs.cityStateFunctions.holdElectionsLikeVp()
        assertEquals("the second: 30 x 140 / 100 = 42", 30f + 42f, influence(a), 0f)
        assertEquals(2, streak(a))
    }

    @Test
    fun `the streak does not grow when a major owns no spy at all`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)
        c.espionageManager.spyList.clear()      // c has none, dead or alive
        onlyHasVotes(spyA)
        cs.cityStateFunctions.holdElectionsLikeVp()
        assertEquals(0, streak(a))
    }

    @Test
    fun `a dead spy still counts as a spy owned`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)
        c.espionageManager.spyList.single().killSpy()
        onlyHasVotes(spyA)
        cs.cityStateFunctions.holdElectionsLikeVp()
        assertEquals("GetNumSpies is the number of spies owned, dead or alive (V:CvEspionageClasses.cpp:4270-4273)", 1, streak(a))
    }

    @Test
    fun `a lost election ends the streak`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)
        cs.getDiplomacyManager(b)!!.consecutiveRiggings = 3
        onlyHasVotes(spyA)
        cs.cityStateFunctions.holdElectionsLikeVp()
        assertEquals(0, streak(b))
    }

    @Test
    fun `a spy that leaves ends the streak, one that stays does not`() {
        cs.getDiplomacyManager(a)!!.consecutiveRiggings = 3
        spyA.moveTo(csCity)
        assertEquals("a move to the city it is in leaves nothing", 3, streak(a))
        spyA.moveTo(null)
        assertEquals("a spy that goes home ends it (V:CvEspionageClasses.cpp:2902-2906)", 0, streak(a))
    }

    @Test
    fun `Covert Action makes a win and the others' loss half as big again`() {
        val d = game.addCiv("[+50]% influence from rigged City-State elections")   // a civilization whose policies carry Covert Action
        game.addCity(d, game.tileMap[3, 3], replacePalace = true)
        d.diplomacyFunctions.makeCivilizationsMeet(cs)
        for (tile in game.tileMap.values) tile.setExplored(d, true)
        val spyD = rigger(d)
        for (civ in listOf(a, b, c, d)) inEra(civ, 1)
        for (civ in listOf(a, b, c, d)) setInfluence(civ, 100f)
        for (spy in listOf(spyA, spyB, spyD)) spy.spendNetworkPoints(spy.networkPoints)
        spyD.addRiggingVotes(100)

        cs.cityStateFunctions.holdElectionsLikeVp()

        assertEquals("30 x 150 / 100 = 45", 100f + 45f, influence(d), 0f)
        assertEquals("500 x 150 / 100 = 750 hundredths, 7.5, for the game's era 1", 100f - 7.5f, influence(b), 0.001f)
    }

    @Test
    fun `an election is held on the turns that are a multiple of the interval, in every city-state alike`() {
        val between = cs.cityStateFunctions.getTurnsBetweenElectionsLikeVp()
        assertEquals(15, between)
        game.gameInfo.turns = 30
        assertEquals(0, cs.cityStateFunctions.getTurnsUntilElectionLikeVp())
        game.gameInfo.turns = 31
        assertEquals(14, cs.cityStateFunctions.getTurnsUntilElectionLikeVp())
        game.gameInfo.turns = 44
        assertEquals(1, cs.cityStateFunctions.getTurnsUntilElectionLikeVp())
    }

    @Test
    fun `the interval follows the game speed in the original's integer order`() {
        val speed = game.gameInfo.speed
        val before = speed.modifier
        try {
            speed.modifier = 0.67f   // Quick: 15 x 67 / 100 = 10
            assertEquals(10, cs.cityStateFunctions.getTurnsBetweenElectionsLikeVp())
            speed.modifier = 1.5f    // Epic: 15 x 150 / 100 = 22
            assertEquals(22, cs.cityStateFunctions.getTurnsBetweenElectionsLikeVp())
            speed.modifier = 3f      // Marathon: 45
            assertEquals(45, cs.cityStateFunctions.getTurnsBetweenElectionsLikeVp())
        } finally { speed.modifier = before }
    }

    @Test
    fun `a spy that arrives learns the turns to the next election from the global schedule`() {
        game.gameInfo.turns = 36
        val fresh = c.espionageManager.spyList.single()
        fresh.moveTo(csCity)
        var guard = 0
        while (fresh.action == SpyAction.Moving && guard++ < 20) c.espionageManager.endTurn()
        assertEquals(SpyAction.RiggingElections, fresh.action)
        assertEquals("turn 36 is 9 turns before the election of turn 45", 9, fresh.turnsRemainingForAction)
    }

    @Test
    fun `the turn of a city-state holds the election on the election turn and not before`() {
        for (civ in listOf(a, b, c)) inEra(civ, 1)
        for (civ in listOf(a, b, c)) setInfluence(civ, 20f)
        onlyHasVotes(spyA)
        game.gameInfo.turns = 31          // not an election turn: nothing happens
        TurnManager(cs).startTurn()
        assertEquals(20f, influence(a), 0f)

        game.gameInfo.turns = 30          // an election turn
        TurnManager(cs).startTurn()
        assertEquals("the election was held: 30 x era 1", 20f + 30f, influence(a), 0f)
    }

    @Test
    fun `under the new model the old per-city-state countdown is gone, so the two schedules cannot both fire`() {
        cs.addFlag(com.unciv.logic.civilization.CivFlags.TurnsTillCityStateElection.name, 1)
        game.gameInfo.turns = 31
        TurnManager(cs).startTurn()
        assertFalse(cs.hasFlag(com.unciv.logic.civilization.CivFlags.TurnsTillCityStateElection.name))
    }

    @Test
    fun `without the espionage of Vox Populi the original's countdown still runs and holds its election`() {
        game.ruleset.modOptions.constants.espionageLikeVp = false
        for (civ in listOf(a, b, c)) setInfluence(civ, 20f)
        cs.addFlag(com.unciv.logic.civilization.CivFlags.TurnsTillCityStateElection.name, 3)
        game.gameInfo.turns = 31
        TurnManager(cs).startTurn()
        assertEquals("the flag counts down as it always did", 2, cs.flagsCountdown[com.unciv.logic.civilization.CivFlags.TurnsTillCityStateElection.name])
        assertEquals("and no election of the new model is held on an election turn of the new model", 20f, influence(a), 0f)
        game.gameInfo.turns = 30
        TurnManager(cs).startTurn()
        assertEquals(1, cs.flagsCountdown[com.unciv.logic.civilization.CivFlags.TurnsTillCityStateElection.name])
        assertEquals("30 is a multiple of 15, but the original's election follows its flag", 20f, influence(a), 0f)
    }

    @Test
    fun `no coup is possible with noCoups, and one is with it off`() {
        val ally = game.addCiv(cityStateType = "Maritime")
        val allyCity = game.addCity(ally, game.tileMap[-4, 0], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(ally)
        ally.allyCiv = b
        val spy = a.espionageManager.addSpy()
        spy.moveTo(allyCity)
        var guard = 0
        while (!spy.isSetUp() && guard++ < 20) a.espionageManager.endTurn()
        game.ruleset.modOptions.constants.noCoups = false
        assertTrue("without the switch a set up spy in a city-state allied to someone else can stage a coup", spy.canDoCoup())
        assertTrue("and the AI considers one", EspionageAutomation(a).isCoupCandidate(spy))
        game.ruleset.modOptions.constants.noCoups = true
        try {
            assertFalse("with noCoups no spy can stage a coup", spy.canDoCoup())
            assertFalse("and the AI does not consider one", EspionageAutomation(a).isCoupCandidate(spy))
        } finally { game.ruleset.modOptions.constants.noCoups = false }
    }
}
