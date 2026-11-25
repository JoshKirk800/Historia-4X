package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueEffects
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the World Congress AI makes of Open Door and Sphere of Influence for a City-State: the two blocks of CvLeagueAI::ScoreVoteChoiceYesNo
 * (Vox Populi, V:CvVotingClasses.cpp:13271-13336 and 13583-13682). Every expected number is worked out by hand from the source, with the original's
 * truncation at each step, written beside the assertion. The pure arithmetic ([LeagueCityStateScoring]) is tested on inputs chosen so that a term in
 * the wrong order or with the wrong sign gives another number; the evaluator ([LeagueEvaluator]) once for what it reads from the game.
 * CIV_APPROACH_WAR 0, HOSTILE 1, NEUTRAL 5, FRIENDLY 6, with GUARDED 3 as the pivot (V:CvDiplomacyAIEnums.h:28-36).
 */
@RunWith(GdxTestRunner::class)
class LeagueCityStateScoringTests {
    private val friendly = MinorCivApproach.Friendly
    private val neutral = MinorCivApproach.Ignore
    private val hostile = MinorCivApproach.Bully
    private val war = MinorCivApproach.Conquest

    // ---- Open Door: the arithmetic ----

    private fun door(influence: Int = 45, threshold: Int = 30, approach: MinorCivApproach = neutral, proximity: Proximity = Proximity.Far,
                     lacked: Int = 0, allyInfluence: Int? = null, target: TargetValue = TargetValue.Average) =
        LeagueCityStateScoring.openDoor(influence, threshold, approach, proximity, lacked, allyInfluence, target)

    @Test
    fun `open door counts against the want to ally the City-State, which it forbids`() {
        // friendly: (6 - 3) x 150 = 450; neighbours +250 = 700; 2 resources +200 = 900; no ally to scale by; influence 10 is under 30: +20; 20 - 900
        assertEquals(-880, door(influence = 10, approach = friendly, proximity = Proximity.Neighbors, lacked = 2))
        // neutral: (5 - 3) x 150 = 300, distant and far add nothing, influence 45 is over the threshold: -300
        assertEquals(-300, door(approach = neutral, proximity = Proximity.Far))
        assertEquals(-300, door(approach = neutral, proximity = Proximity.Distant))
    }

    @Test
    fun `open door takes the ally's influence into the want in two truncating steps`() {
        // friendly 450 + close 100 + 1 resource 100 = 650; the ally has 50, we 37: 37 x 75 = 2775, / 50 = 55, then 650 x 55 = 35750, / 100 = 357; one step would give 360
        assertEquals(-357, door(influence = 37, approach = friendly, proximity = Proximity.Close, lacked = 1, allyInfluence = 50))
        // 450 + 100 + 100 = 650 again; the ally has 41, we 17 (under 30: +13): 17 x 75 = 1275, / 41 = 31; 650 x 31 = 20150, / 100 = 201; 13 - 201 = -188; one step 189
        assertEquals(-188, door(influence = 17, approach = friendly, proximity = Proximity.Close, lacked = 1, allyInfluence = 41))
    }

    @Test
    fun `open door adds the proximity and the resources before the ally's influence scales them`() {
        // 450 + 250 + 300 (3 resources) = 1000; the ally has 100, we 60: 60 x 75 = 4500, / 100 = 45; 1000 x 45 = 45000, / 100 = 450
        assertEquals(-450, door(influence = 60, approach = friendly, proximity = Proximity.Neighbors, lacked = 3, allyInfluence = 100))
        // scaling only the 450 and adding 550 after would give 450 x 45 / 100 + 550 = 752
        assertTrue(door(influence = 60, approach = friendly, proximity = Proximity.Neighbors, lacked = 3, allyInfluence = 100) != -752)
    }

    @Test
    fun `open door does not scale by an ally influence of zero or less`() {
        // max(1, 0) = 1: 450 + 0 = 450 x (60 x 75 = 4500) = 2,025,000, / 100 = 20250
        assertEquals(-20250, door(influence = 60, approach = friendly, proximity = Proximity.Far, allyInfluence = 0))
    }

    @Test
    fun `open door is wanted when the approach is hostile, more when the target is easy`() {
        // hostile: (1 - 3) x 150 = -300, proximity and resources do not count below 0; influence 20: +10; 10 + 300 = 310; average target adds nothing
        assertEquals(310, door(influence = 20, approach = hostile, proximity = Proximity.Close, lacked = 3, target = TargetValue.Average))
        assertEquals(310, door(influence = 20, approach = hostile, proximity = Proximity.Close, lacked = 3, target = TargetValue.Difficult))
        // favorable is 1 above average: -300 - 150 = -450; 10 + 450 = 460
        assertEquals(460, door(influence = 20, approach = hostile, target = TargetValue.Favorable))
        // war 0: -450; a cakewalk is 3 above average: -450 - 450 = -900; at war our influence is -60: the friend term is 90, clamped to 100 -> 90; 90 + 900 = 990
        assertEquals(990, door(influence = -60, approach = war, proximity = Proximity.Neighbors, target = TargetValue.Cakewalk))
    }

    @Test
    fun `open door friend term is the distance to the threshold, at most 100, and only at or under it`() {
        assertEquals(100 - 300, door(influence = -90, threshold = 30)) // 120 away: clamped to 100
        assertEquals(30 - 300, door(influence = 0, threshold = 30))
        assertEquals(0 - 300, door(influence = 30, threshold = 30)) // at the threshold: 0 away
        assertEquals(0 - 300, door(influence = 31, threshold = 30)) // over it: nothing
    }

    // ---- Sphere of Influence: the arithmetic ----

    private fun sphere(us: Boolean, weAreAlly: Boolean = false, influence: Int = 10, highestOther: Int = 0, proximity: Proximity = Proximity.Far,
                       approach: MinorCivApproach = neutral, allyInfluence: Int? = null, diploVictory: Boolean = false) =
        LeagueCityStateScoring.sphere(us, weAreAlly, influence, highestOther, proximity, approach, allyInfluence, diploVictory)

    @Test
    fun `sphere for ourselves is worth 600 when we are not the ally, and the proximity on top`() {
        assertEquals(100 + 500 + 400, sphere(us = true, proximity = Proximity.Neighbors))
        assertEquals(100 + 500 + 200, sphere(us = true, proximity = Proximity.Close))
        assertEquals(100 + 500 + 200, sphere(us = true, proximity = Proximity.Distant)) // the hard-to-control City-State counts here
        assertEquals(100 + 500, sphere(us = true, proximity = Proximity.Far))
        assertEquals(100 + 500, sphere(us = true, proximity = Proximity.None))
    }

    @Test
    fun `sphere for ourselves as the ally is worth how contended the City-State is`() {
        // 100 + clamp(500 - (80 - 30), 0, 500) = 450 + close 200
        assertEquals(750, sphere(us = true, weAreAlly = true, influence = 80, highestOther = 30, proximity = Proximity.Close))
        // 80 against 590: 500 - (-510) = 1010, clamped to 500
        assertEquals(800, sphere(us = true, weAreAlly = true, influence = 80, highestOther = 590, proximity = Proximity.Close))
        // 700 against 0: 500 - 700 = -200, floored at 0
        assertEquals(100, sphere(us = true, weAreAlly = true, influence = 700, highestOther = 0, proximity = Proximity.Far))
    }

    @Test
    fun `sphere for another civilization costs what we would want of the City-State, 150 more per step of friendliness`() {
        // friendly: 150 + (6 - 3) x 150 = 600, over 150 so neighbours add 400 = 1000; we are the ally: 1000 x 150 / 100
        assertEquals(-1500, sphere(us = false, weAreAlly = true, approach = friendly, proximity = Proximity.Neighbors))
        // neutral: 150 + 2 x 150 = 450, close +200 = 650; x 150 / 100 = 975 (975.0)
        assertEquals(-975, sphere(us = false, weAreAlly = true, approach = neutral, proximity = Proximity.Close))
        // hostile is under the pivot: 150 flat, so the proximity does not count and distant never does: 150 x 150 / 100 = 225
        assertEquals(-225, sphere(us = false, weAreAlly = true, approach = hostile, proximity = Proximity.Neighbors))
        assertEquals(-225, sphere(us = false, weAreAlly = true, approach = hostile, proximity = Proximity.Distant))
        // distant counts nothing for another civilization, unlike for ourselves
        assertEquals(-675, sphere(us = false, weAreAlly = true, approach = neutral, proximity = Proximity.Distant)) // 450 x 150 / 100
    }

    @Test
    fun `sphere for another civilization is scaled by our share of the ally's influence, truncated and clamped`() {
        // friendly: 150 + 450 = 600, close +200 = 800; the ally has 90, we 33: 33 x 100 / 90 = 36 (36.67); 800 x 36 / 100 = 288 (288.0)
        assertEquals(-288, sphere(us = false, influence = 33, approach = friendly, proximity = Proximity.Close, allyInfluence = 90))
        // we have more than the ally: 70 x 100 / 40 = 175, clamped to 100: all 800
        assertEquals(-800, sphere(us = false, influence = 70, approach = friendly, proximity = Proximity.Close, allyInfluence = 40))
        // neutral 450 + 200 = 650; 3 x 100 / 7 = 42 (42.86); 650 x 42 / 100 = 273; a share kept as a fraction would give 278
        assertEquals(-273, sphere(us = false, influence = 3, approach = neutral, proximity = Proximity.Close, allyInfluence = 7))
        // the ally's influence of 0: max(1, 0) = 1: 5 x 100 = 500, clamped to 100
        assertEquals(-650, sphere(us = false, influence = 5, approach = neutral, proximity = Proximity.Close, allyInfluence = 0))
        // negative influence of ours: -20 x 100 / 50 = -40, clamped to 0
        assertEquals(0, sphere(us = false, influence = -20, approach = neutral, proximity = Proximity.Close, allyInfluence = 50))
    }

    @Test
    fun `sphere for another civilization with no ally costs nothing`() {
        assertEquals(0, sphere(us = false, approach = friendly, proximity = Proximity.Neighbors, allyInfluence = null))
    }

    @Test
    fun `sphere is doubled with the diplomatic victory, after the terms are added`() {
        assertEquals(1500, sphere(us = true, weAreAlly = true, influence = 80, highestOther = 30, proximity = Proximity.Close, diploVictory = true))
        assertEquals(-3000, sphere(us = false, weAreAlly = true, approach = friendly, proximity = Proximity.Neighbors, diploVictory = true))
        assertEquals(0, sphere(us = false, diploVictory = true))
    }

    // ---- The evaluator: what it reads from the game ----

    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var cs: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants
    private val doorUnique = "Opens the door of the chosen City-State to every civilization"
    private val sphereUnique = "Puts the chosen City-State in the sphere of influence of the proposer"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.leagueSessionRulesLikeVp = true
        constants.cityStateInfluenceLikeVp = true
    }

    private fun major(): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun resolution(name: String, unique: String): Resolution {
        val made = Resolution().apply {
            this.name = name
            proposerDecision = ResolutionDecision.CityState
            uniques = arrayListOf(unique)
        }
        game.ruleset.resolutions[name] = made
        return made
    }

    private fun found() {
        a = major(); b = major(); c = major()
        cs = game.addCiv(cityStateType = "Maritime")
        game.addCity(cs, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        val majors = listOf(a, b, c)
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        for (x in majors) x.diplomacyFunctions.makeCivilizationsMeet(cs)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    private fun set(civ: Civilization, amount: Float) = cs.getDiplomacyManager(civ)!!.setInfluenceWithoutSideEffects(amount)
    private fun evaluator(civ: Civilization) = LeagueEvaluator(civ, league)

    @Test
    fun `the evaluator scores open door from the influence, the approach, the proximity, the ally and the target value`() {
        resolution("Test Door", doorUnique)
        found()
        val door = game.ruleset.resolutions["Test Door"]!!
        // a: influence 37 (over the threshold 30: no friend term), friendly, close, no resource lacked, and b is the ally with 50; the target value
        // (soft) is read only below 0, so it does not count for a friendly approach. Desire 450 + close 100 = 550; 37 x 75 / 50 = 55; 550 x 55 / 100 = 302
        set(a, 37f); set(b, 50f); cs.allyCiv = b
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        a.proximity[cs.civID] = Proximity.Close
        a.ai.warStates.rawTargetValues[cs.civID] = TargetValue.Soft
        assertEquals(-302, evaluator(a).scoreEffects(door, cs.civID))
        // b is the ally itself, so the want is not scaled: friendly and close, 450 + 100
        b.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        b.proximity[cs.civID] = Proximity.Close
        assertEquals(-550, evaluator(b).scoreEffects(door, cs.civID))
    }

    @Test
    fun `the evaluator counts the resources the City-State has that we lack`() {
        resolution("Test Door", doorUnique)
        found()
        set(a, 45f)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        a.proximity[cs.civID] = Proximity.Far // meeting made it neighbours (the cities are 7 apart); far adds nothing
        val without = evaluator(a).scoreEffects(game.ruleset.resolutions["Test Door"]!!, cs.civID)
        assertEquals(-450, without) // friendly 450; no ally, so no scaling; 45 is over the threshold
        val iron = game.createBuilding("Provides [1] [Iron]")
        cs.cities.first().cityConstructions.addBuilding(iron)
        cs.cache.updateCivResources()
        assertEquals(1, MinorCivHelpers.numResourcesMajorLacks(cs, a))
        assertEquals(-550, evaluator(a).scoreEffects(game.ruleset.resolutions["Test Door"]!!, cs.civID)) // 450 + 100
    }

    @Test
    fun `the evaluator scores sphere by who it makes the ally, the proposer of an enactment and the current ally of a repeal`() {
        resolution("Test Sphere", sphereUnique)
        found()
        val sphere = game.ruleset.resolutions["Test Sphere"]!!
        set(a, 80f); set(b, 30f); cs.allyCiv = a
        a.proximity[cs.civID] = Proximity.Close
        // the game has the diplomatic victory, so every Sphere score is doubled (V:13676-13679)
        // enacting for ourselves (a proposes) as the ally: 100 + (500 - (80 - 30)) + close 200 = 750, x 2
        assertEquals(1500, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = a.civID))
        // b proposes it for b: for a that is the other branch; a is the ally, neutral: 150 + 2 x 150 = 450, no proximity bonus at far: 450 x 150 / 100 = 675, x 2
        a.proximity[cs.civID] = Proximity.Far
        assertEquals(-1350, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = b.civID))
        // repealing it: the affected one is the current ally, whoever proposed it: for a, a as the ally: 100 + 450 = 550 (far), x 2
        assertEquals(1100, evaluator(a).scoreEffects(sphere, cs.civID, enact = false, proposerId = b.civID))
    }

    @Test
    fun `sphere is not doubled when the diplomatic victory is off in the game`() {
        resolution("Test Sphere", sphereUnique)
        found()
        gameInfo.gameParameters.victoryTypes.remove("Diplomatic")
        set(a, 80f); set(b, 30f); cs.allyCiv = a
        a.proximity[cs.civID] = Proximity.Close
        assertEquals(750, evaluator(a).scoreEffects(game.ruleset.resolutions["Test Sphere"]!!, cs.civID, enact = true, proposerId = a.civID))
    }

    @Test
    fun `the contender for sphere is the strongest other major, defeated or not`() {
        resolution("Test Sphere", sphereUnique)
        found()
        val sphere = game.ruleset.resolutions["Test Sphere"]!!
        set(a, 80f); set(b, 10f); set(c, 70f); cs.allyCiv = a
        a.proximity[cs.civID] = Proximity.Far
        // 100 + (500 - (80 - 70)) = 590, x 2 for the diplomatic victory; b's 10 is not the highest
        assertEquals(1180, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = a.civID))
        c.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue(c.isDefeated())
        // VP loops over every major slot: the defeated civilization's last influence still counts
        assertEquals(1180, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = a.civID))
        // with c's influence gone the highest of the others is b's 10: 100 + (500 - (80 - 10)) = 530, x 2
        set(c, 0f)
        assertEquals(1060, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = a.civID))
    }

    @Test
    fun `the evaluator is not changed without the switch, for a plain resolution or for a choice that is not a City-State`() {
        resolution("Test Door", doorUnique)
        resolution("Test Sphere", sphereUnique)
        found()
        set(a, 10f); cs.allyCiv = b
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        a.proximity[cs.civID] = Proximity.Neighbors
        val door = game.ruleset.resolutions["Test Door"]!!
        val sphere = game.ruleset.resolutions["Test Sphere"]!!
        // Open Door: influence 10 is 20 under the threshold: +20; friendly 450 + neighbours 250 = 700; the ally b has 0, max(1, 0) = 1: 10 x 75 / 1 = 750;
        // 700 x 750 = 525000, / 100 = 5250; 20 - 5250
        assertEquals(-5230, evaluator(a).scoreEffects(door, cs.civID))
        // Sphere for ourselves (a proposes), b is the ally and we are not: 100 + 500 + neighbours 400, x 2 for the diplomatic victory
        assertEquals(2000, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = a.civID))
        assertEquals("a major is not a City-State", 0, evaluator(a).scoreEffects(door, b.civID))
        assertEquals("a major is not a City-State", 0, evaluator(a).scoreEffects(sphere, b.civID, enact = true, proposerId = a.civID))
        assertEquals("no choice", 0, evaluator(a).scoreEffects(door, null))
        assertEquals("a resolution without either effect", 0, evaluator(a).scoreEffects(Resolution().apply { name = "Plain" }, cs.civID))
        constants.leagueSessionRulesLikeVp = false
        assertEquals("switch off", 0, evaluator(a).scoreEffects(door, cs.civID))
        assertEquals("switch off", 0, evaluator(a).scoreEffects(sphere, cs.civID, enact = true, proposerId = a.civID))
    }

    @Test
    fun `an AI that wants to bully a City-State likes open door and one that wants to ally it dislikes it`() {
        resolution("Test Door", doorUnique)
        found()
        for (civ in listOf(a, b)) { civ.proximity[cs.civID] = Proximity.Neighbors; set(civ, 10f) }
        val door = game.ruleset.resolutions["Test Door"]!!
        // a is hostile (1): (1 - 3) x 150 = -300; the proximity and the resources do not count under 0; a soft target is 2 above average: -300 - 300 = -600;
        // influence 10 is 20 under the threshold: +20; 20 + 600 = 620
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Bully
        a.ai.warStates.rawTargetValues[cs.civID] = TargetValue.Soft
        assertEquals(620, evaluator(a).scoreProposal(door, cs.civID, a.civID))
        // b is friendly: 450 + neighbours 250 = 700, no ally to scale by; 20 - 700
        b.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        assertEquals(-680, evaluator(b).scoreProposal(door, cs.civID, b.civID))
    }

    @Test
    fun `protective, which a save never keeps, is the friendly approach`() {
        // friendly 450, as for Friendly; a Protective at 5 (neutral) would be 300
        assertEquals(-450, door(approach = MinorCivApproach.Protective))
    }

    @Test
    fun `without an evaluated approach the AI is neutral towards the City-State`() {
        resolution("Test Door", doorUnique)
        found()
        set(a, 45f)
        a.proximity[cs.civID] = Proximity.Far
        // neutral: (5 - 3) x 150 = 300; far adds nothing, no ally; 45 is over the threshold
        assertEquals(-300, evaluator(a).scoreEffects(game.ruleset.resolutions["Test Door"]!!, cs.civID))
    }

    @Test
    fun `the contest for sphere starts at 0, so rivals under 0 do not make the ally's lead bigger`() {
        resolution("Test Sphere", sphereUnique)
        found()
        gameInfo.gameParameters.victoryTypes.remove("Diplomatic")
        set(a, 80f); set(b, -20f); set(c, -30f); cs.allyCiv = a
        a.proximity[cs.civID] = Proximity.Far
        // 100 + (500 - (80 - 0)) = 520; with the highest of the others taken as -20 it would be 500 (clamped)
        assertEquals(520, evaluator(a).scoreEffects(game.ruleset.resolutions["Test Sphere"]!!, cs.civID, enact = true, proposerId = a.civID))
    }

    @Test
    fun `a proposal and a repeal of sphere are scored through the public entries with the proposer and the direction`() {
        resolution("Test Sphere", sphereUnique)
        found()
        val sphere = game.ruleset.resolutions["Test Sphere"]!!
        set(a, 10f); set(b, 50f); cs.allyCiv = b
        a.proximity[cs.civID] = Proximity.Close
        // a proposes it for a, who is not the ally: (100 + 500 + close 200) x 2 for the diplomatic victory
        assertEquals(1600, evaluator(a).scoreProposal(sphere, cs.civID, a.civID))
        // for b's gain, a (neutral, close, b is the ally with 50 against our 10: 10 x 100 / 50 = 20): 450 + close 200 = 650; 650 x 20 / 100 = 130; x 2
        assertEquals(-260, evaluator(a).scoreProposal(sphere, cs.civID, b.civID))
        // repealing it with b the ally: the affected one is b, not the proposer a: the other branch again, -260, and a repeal is the reverse: +260
        val active = ActiveResolution(7, "Test Sphere", a.civID, cs.civID, gameInfo.turns)
        assertEquals(260, evaluator(a).scoreRepeal(active, a.civID))
    }

    @Test
    fun `repealing open door is scored as the enactment reversed, with the influence it has set`() {
        resolution("Test Door", doorUnique)
        found()
        set(a, 20f)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
        a.proximity[cs.civID] = Proximity.Close
        val door = game.ruleset.resolutions["Test Door"]!!
        val active = ActiveResolution(1, "Test Door", a.civID, cs.civID, gameInfo.turns)
        league.activeResolutions.add(active)
        league.setTransients(gameInfo)
        // before the door is enacted: influence 20 is 10 under the threshold: +10; friendly 450 + close 100 = 550, no ally; 10 - 550
        assertEquals(-540, evaluator(a).scoreProposal(door, cs.civID, a.civID))
        // enacted: our influence is 40 (over the threshold: no friend term) and nobody is the ally: -550; repealing it is worth the opposite, +550
        LeagueEffects.onEnact(league, active)
        assertEquals(-550, evaluator(a).scoreProposal(door, cs.civID, a.civID))
        assertEquals(550, evaluator(a).scoreRepeal(active, a.civID))
    }
}
