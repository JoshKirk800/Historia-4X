package com.unciv.logic.automation.unit

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What a spy mission or a counterspy focus is worth to an AI civilization ([EspionageScoring]: CvEspionageAI::GetMissionScore and its helpers, V:CvEspionageClasses.cpp:8393-9383; V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`). The expected numbers are worked out by hand from the original's formulas with the inputs of the setup, which the tests assert first; none is read
 *  off the scorer. [a] is the civilization that scores and [b] another whose capital it spies on: a stranger to [a] with a neutral approach, a diplomatic balance of 5, the first era, so that
 *  we mind them for half of what a mission takes from them (50 + the modifier of a neutral approach, -15) and a spy of [a] gathers 41 Network Points a turn there ([rate]).
 *
 *  The price of a mission of C points with no kill chance, for a spy with none stored: the numerator is `2000 + turnsLost * rate` where turnsLost is the travel (1) plus the respawn (5),
 *  the denominator `max(cooldown 15 * rate, C)`; the ratio is a whole number; the score before it is multiplied by that and halved, and at the end times `rate / 50` (V:9265-9342).
 */
@RunWith(GdxTestRunner::class)
class EspionageScoringTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCity: City
    private lateinit var bCity: City
    private val added = ArrayList<String>()

    private val rate get() = a.espionageManager.networkPointsPerTurn(null, bCity, gathering = true)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        game.gameInfo.turns = 50
        a = game.addCiv()
        b = game.addCiv()
        aCity = game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0), respawnTurns = 5)
    }

    @After
    fun tearDown() {
        for (name in added) game.ruleset.spyMissions.remove(name)
    }

    private fun mission(name: String, points: Int, vararg uniques: String, kill: Int = 0, identify: Int = 0): SpyMission {
        val mission = SpyMission().apply {
            this.name = name; networkPoints = points; cityFilter = "All"; killChance = kill; identifyChance = identify
            this.uniques = arrayListOf(*uniques)
        }
        game.ruleset.spyMissions[name] = mission
        added += name
        return mission
    }

    private fun focus(name: String, vararg uniques: String, reduction: Int = 0, identifyReduction: Int = 0): SpyMission {
        val mission = SpyMission().apply {
            this.name = name; isFocus = true; cityFilter = "All"
            counterspyNetworkPointsReduction = reduction; identifyChanceReductionGlobal = identifyReduction
            this.uniques = arrayListOf(*uniques)
        }
        game.ruleset.spyMissions[name] = mission
        added += name
        return mission
    }

    private fun score(mission: SpyMission, spy: Spy? = null, city: City = bCity) = EspionageScoring.missionScore(a, spy, city, mission)

    /** What the setup of the tests has to be for their numbers to hold */
    private fun assertSetup(approach: MajorCivApproach = MajorCivApproach.Neutral) {
        assertEquals("the approach to them: with no memory of them a neutral one", approach, a.ai.diplomacy.approachTowards(b))
        assertEquals("a balance of 5 scales the modifier by 100%", 5, a.ai.diplomacyValue(DiplomacyPersonality.DiploBalance))
        assertEquals("the points a spy gathers: 50 less the security of the city", 41, rate)
    }

    /** The yields of a mission are divided by the era number, by 1 in the first eras (0 and 1): the tests of yields need one of those (V:8573, max(1, GetCurrentEra())) */
    private fun assertEarlyEra() = assertTrue("the era of the owner is 0 or 1", b.getAiEraNumber() <= 1)

    private val steal = "Steal [1] random technology/technologies the target knows and we can research"

    /** [b] knows Pottery and [a] does not: the one technology to steal, 25 of benefit (the score of the original asks how many there are) */
    private fun givePottery() {
        b.tech.addTechnology("Pottery")
        assertEquals("one technology to steal", 1, a.espionageManager.getTechsToSteal(b).size)
    }

    // region the price of a mission against what it does
    @Test
    fun `a steal that costs little scores its benefit times the number of such missions`() {
        givePottery()
        assertSetup()
        // one technology to steal: 25 (min(1 * 25, 75), V:8878). Numerator 2000 + 6 * 41 = 2246; denominator max(15 * 41 = 615, 100) = 615; the ratio is 3.
        // 25 * 3 = 75; halved 37; no identification; 37 * 41 / 50 = 30
        assertEquals(30, score(mission("Cheap", 100, steal)))
    }

    @Test
    fun `a dearer mission scores less in steps of the whole ratio`() {
        givePottery()
        assertSetup()
        // 1000 points: denominator 1000, 2246 / 1000 = 2; 25 * 2 = 50; 25; 25 * 41 / 50 = 20
        assertEquals(20, score(mission("Dear", 1000, steal)))
        // 1500 points: 2246 / 1500 = 1; 25; 12; 12 * 41 / 50 = 9
        assertEquals(9, score(mission("Dearer", 1500, steal)))
        // 3000 points: 2246 / 3000 = 0, nothing is left
        assertEquals(0, score(mission("Dearest", 3000, steal)))
    }

    @Test
    fun `the benefit of technologies grows with how many there are to steal but stops at three`() {
        // all of the four need Agriculture, which both have: what is left to steal is the four
        a.tech.addTechnology("Agriculture")
        for (tech in listOf("Agriculture", "Pottery", "Trapping", "The Wheel", "Animal Husbandry")) b.tech.addTechnology(tech)
        assertSetup()
        assertEquals("four technologies to steal", 4, a.espionageManager.getTechsToSteal(b).size)
        // min(4 * 25, 75) = 75; 75 * 3 = 225; 112; 112 * 41 / 50 = 91 (uncapped it would be 100: 300; 150; 150 * 41 / 50 = 123)
        assertEquals(91, score(mission("Cheap", 100, steal)))
        // three of them are as much as four: the cap is reached at three
        a.tech.addTechnology("Pottery")
        assertEquals("three technologies to steal", 3, a.espionageManager.getTechsToSteal(b).size)
        assertEquals(91, score(mission("Cheap3", 100, steal)))
        // two are 50: 50 * 3 = 150; 75; 75 * 41 / 50 = 61
        a.tech.addTechnology("Trapping")
        assertEquals("two technologies to steal", 2, a.espionageManager.getTechsToSteal(b).size)
        assertEquals(61, score(mission("Cheap2", 100, steal)))
    }

    @Test
    fun `a mission that does nothing is worth nothing`() {
        assertSetup()
        assertEquals(0, score(mission("Idle", 100)))
    }

    @Test
    fun `the kill chance makes a mission dearer by the points lost while the spy is away`() {
        givePottery()
        assertSetup()
        // kill chance 100: the effective cost is 100 + 41 * 6 * 100 / 100 = 346, which is under 15 * 41 = 615, so the denominator is the same and the score is 30
        assertEquals(30, score(mission("Risky", 100, steal, kill = 100)))
        // 1000 points and a kill chance of 100: 1000 + 246 = 1246; 2246 / 1246 = 1; 25; 12; 9 (against 20 for the same mission without the kill chance)
        assertEquals(9, score(mission("DearRisky", 1000, steal, kill = 100)))
    }

    @Test
    fun `points already earned for the mission count a fifth, if it scores at all`() {
        givePottery()
        val spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        spy.addNetworkPoints(500)
        assertSetup()
        // 1000 points: leftover max(0, 500 - 1000) = 0, the ratio 2; 50; 25; plus the progress 500 * 100 / 100 / 5 = 100: 125; 125 * 41 / 50 = 102 (against 20 without the points)
        assertEquals(102, score(mission("Dear", 1000, steal), spy))
        // 3000 points scores 0 before the progress is looked at, and then the progress is not added
        assertEquals(0, score(mission("Dearest", 3000, steal), spy))
    }

    @Test
    fun `points beyond what the mission costs make a dearer one the better choice`() {
        givePottery()
        val spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        spy.addNetworkPoints(1700)
        assertSetup()
        // 100 points with 1700 stored: leftover 1600, denominator 1600 + 615 = 2215, 2246 / 2215 = 1; 25; 12; plus 1700 / 5 = 340: 352; 352 * 41 / 50 = 288
        assertEquals(288, score(mission("Cheap", 100, steal), spy))
    }
    // endregion

    // region what the owner loses and how much we mind
    @Test
    fun `the treasury stolen is a benefit to us and a loss to the owner of which we count what we mind`() {
        b.addGold(1500)
        assertSetup()
        assertEarlyEra()
        val take = mission("Take", 100, "Steal [20]% of the treasury of the target")
        // benefit: 1500 * 20 / 100 = 300, / era 1, / 15 = 20. The loss to b is the same 20 (as -20); we mind them for 35 in 100 (50 - 15), so -20 * 35 / 100 = -7 is taken off our score,
        // that is, 7 is added: hurting them is a gain (V:9259, iScore -= iEnemyScoring). 20 + 7 = 27; * 3 = 81; 40; 40 * 41 / 50 = 32
        assertEquals(32, score(take))
    }

    @Test
    fun `a civilization we are at war with is hurt for more and the same mission scores more`() {
        b.addGold(1500)
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals("the points a spy gathers do not depend on the war", 41, rate)
        assertEarlyEra()
        val take = mission("Take", 100, "Steal [20]% of the treasury of the target")
        // at war the modifier is 40 and we mind them for 50 + 40 = 90 in 100 (V:8264): the loss to b of -20 counts -18, which is added to the benefit of 20: 38
        // 38 * 3 = 114; 57; 57 * 41 / 50 = 46 (against 32 when the approach is neutral)
        assertEquals(46, score(take))
    }
    // endregion

    /** The treasury mission of the tests, 20 for us and -20 for the owner at an early era and 1500 gold: the score is the benefit plus what we count of the loss, times 3, halved, times 41 / 50 */
    private fun takeScore(approach: MajorCivApproach = MajorCivApproach.Neutral): Int {
        b.addGold(1500)
        assertSetup(approach)
        assertEarlyEra()
        return score(mission("Take", 100, "Steal [20]% of the treasury of the target"))
    }

    @Test
    fun `a civilization we are trying to stop from winning is hurt for twice as much as the loss`() {
        a.ai.diplomacy.memoryOf(b).victoryDisputeLevel = com.unciv.logic.automation.diplomacy.DisputeLevel.Fierce
        // we mind them for 200 in 100 (V:9244): the loss -20 counts -40, added to 20: 60; * 3 = 180; 90; 90 * 41 / 50 = 73
        assertEquals(73, takeScore())
    }

    @Test
    fun `our biggest competitor is hurt for as much as the loss`() {
        a.ai.diplomacy.biggestCompetitor = b.civID
        // 100 in 100 (V:9248): -20 counts -20, 20 + 20 = 40; * 3 = 120; 60; 60 * 41 / 50 = 49
        assertEquals(49, takeScore())
    }

    @Test
    fun `an ally by a defensive pact is not hurt for the benefit of hurting him`() {
        a.getDiplomacyManager(b)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.DefensivePact
        // we mind them for min(35, 0) = 0 (V:9254): the loss counts nothing, the score is the benefit of 20 alone; * 3 = 60; 30; 30 * 41 / 50 = 24 (against 32 with a neutral approach)
        assertEquals(24, takeScore())
    }

    @Test
    fun `a friend is hurt for less`() {
        a.ai.diplomacy.memoryOf(b).approach = MajorCivApproach.Friendly
        // friendly: 50 - 30 = 20 in 100: the loss counts -4, 24; * 3 = 72; 36; 36 * 41 / 50 = 29
        assertEquals(29, takeScore(MajorCivApproach.Friendly))
    }

    @Test
    fun `a civilization we denounced is hurt for more`() {
        a.getDiplomacyManager(b)!!.denounce()
        // denounced: 33; neutral: -15; 50 + 18 = 68 in 100: the loss counts -13 (20 * 68 / 100), 33; * 3 = 99; 49; 49 * 41 / 50 = 40
        assertEquals(40, takeScore())
    }

    @Test
    fun `a diplomatic balance scales how much a war changes what we mind`() {
        a.getDiplomacyManager(b)!!.declareWar()
        // the same war as above (40), with a balance of 10 the modifier is 40 * (100 + 10 * 5) / 100 = 60: 110 in 100: the loss counts -22, 42; * 3 = 126; 63; 63 * 41 / 50 = 51
        a.ai.setDiplomacyValue(DiplomacyPersonality.DiploBalance, 10)
        b.addGold(1500)
        assertEquals(41, rate)
        assertEarlyEra()
        assertEquals(51, score(mission("Take", 100, "Steal [20]% of the treasury of the target")))
    }

    @Test
    fun `being identified costs us what we mind the owner less, which the original computes and forgets`() {
        b.addGold(1500)
        assertSetup()
        assertEarlyEra()
        // 100 in 100 chance of being identified: (200 - 35) / 4 = 41 off 40 (the score before it, as in the treasury test: 27 * 3 = 81, 40) leaves nothing.
        // The original takes max(0, iScore) after computing iScore - 41 and so keeps 40, and 40 * 41 / 50 = 32 (V:9294-9298); here the penalty is taken
        assertEquals(0, score(mission("Found", 100, "Steal [20]% of the treasury of the target", identify = 100)))
        // 20 in 100: (165 / 4 = 41) * 20 / 100 = 8; 40 - 8 = 32; 32 * 41 / 50 = 26
        assertEquals(26, score(mission("Found20", 100, "Steal [20]% of the treasury of the target", identify = 20)))
    }

    @Test
    fun `a wonder in production is a loss to the owner that makes a mission that stops it better`() {
        givePottery()
        assertSetup()
        val both = mission("Both", 100, steal, "Cannot build [All] buildings <for [5] turns>")
        // nothing in production: 25 * 3 = 75; 37; 37 * 41 / 50 = 30
        assertEquals(30, score(both))
        // a World Wonder in production: the loss -100 counts -35, added: 25 + 35 = 60; * 3 = 180; 90; 90 * 41 / 50 = 73
        val wonder = game.ruleset.buildings.values.first { it.isWonder }.name
        bCity.cityConstructions.setCurrentConstruction(wonder)
        assertEquals(73, score(both))
    }
    // endregion

    @Test
    fun `sapping a city at war is worth more with our border on it`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.warStates.targetValues[b.civID] = TargetValue.Favorable
        assertEquals(41, rate)
        val sap = mission("Sap", 100, "City is sapped <for [5] turns>")
        fun ours() = bCity.getCenterTile().getTilesInDistanceRange(1..4).count { it.getOwner() == a }
        // the border of our own city reaches one tile within 4 of theirs: min(50, 1 tile * 5 turns * 3) = 15; * 3 = 45; 22; 22 * 41 / 50 = 18
        assertEquals(1, ours())
        assertEquals(18, score(sap))
        // two more: min(50, 3 * 5 * 3 = 45) = 45; * 3 = 135; 67; 67 * 41 / 50 = 54
        val near = bCity.getCenterTile().getTilesInDistanceRange(1..4).filter { it.getOwner() == null }.take(2).toList()
        for (tile in near) aCity.expansion.takeOwnership(tile)
        assertEquals(3, ours())
        assertEquals(54, score(sap))
        // and no more than 50 for the border however many tiles there are: 4 tiles would be 60
        for (tile in bCity.getCenterTile().getTilesInDistanceRange(1..4).filter { it.getOwner() == null }.take(1).toList()) aCity.expansion.takeOwnership(tile)
        assertEquals(4, ours())
        // 50; * 3 = 150; 75; 75 * 41 / 50 = 61
        assertEquals(61, score(sap))
    }

    @Test
    fun `sapping a city is of no use against a target that is not a good one`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.warStates.targetValues[b.civID] = TargetValue.Average
        val near = bCity.getCenterTile().getTilesInDistanceRange(1..4).filter { it.getOwner() == null }.take(2).toList()
        for (tile in near) aCity.expansion.takeOwnership(tile)
        assertEquals("our border is on the city", 3, bCity.getCenterTile().getTilesInDistanceRange(1..4).count { it.getOwner() == a })
        // an average target is not worth sapping for, whatever the border: nothing
        assertEquals(0, score(mission("Sap", 100, "City is sapped <for [5] turns>")))
    }

    @Test
    fun `stopping a city that builds the wonder we build is worth most when the pause lets us finish first`() {
        givePottery()
        val both = mission("Both", 100, steal, "Cannot build [All] buildings <for [5] turns>")
        val wonder = game.ruleset.buildings.values.first { it.isWonder }.name
        bCity.cityConstructions.setCurrentConstruction(wonder)
        aCity.cityConstructions.setCurrentConstruction(wonder)
        val spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        assertSetup()
        // a spy that sees the screen of the city (V:8905): the bonus is a new object of this test
        val surveillance = com.unciv.models.ruleset.SpyBonus().apply {
            name = "Surveillance Test"; kind = com.unciv.models.ruleset.SpyBonusKind.Passive; networkPoints = 0
            uniques = arrayListOf("Reveals the city screen to the spy's owner")
        }
        game.ruleset.spyBonuses[surveillance.name] = surveillance
        try {
            assertEquals("the spy sees the city screen", true, spy.getBonuses().any { it.name == surveillance.name })
            val theirs = bCity.cityConstructions.turnsToConstruction(wonder)
            val ours = aCity.cityConstructions.turnsToConstruction(wonder)
            // the cities are alike: we finish on the very turn they would, which the pause of 5 turns puts before them (ours >= theirs and ours < theirs + 5): +500 (V:8932)
            assertEquals("the cities need as many turns", theirs, ours)
            // 25 + 500 for the benefit, the loss of -100 counting -35: 560; * 3 = 1680; 840; 840 * 41 / 50 = 688. The progress of the spy is 0: nothing is added
            assertEquals(688, score(both, spy))
            // we finish before them without the pause (ours < theirs): none of the three conditions holds and the race adds nothing: 73, as without a spy that sees the city
            aCity.cityConstructions.productionOverflow = 100000
            assertEquals("we need fewer turns", true, aCity.cityConstructions.turnsToConstruction(wonder) < theirs)
            assertEquals(73, score(both, spy))
            // they would finish long before us, more than the pause: ours >= theirs + 5 fails the first and the tie needs the last turn, the third condition cannot hold: 73
            aCity.cityConstructions.productionOverflow = 0
            bCity.cityConstructions.productionOverflow = 100000
            assertEquals("they need fewer turns", true, bCity.cityConstructions.turnsToConstruction(wonder) + 5 < ours)
            assertEquals(73, score(both, spy))
        } finally {
            game.ruleset.spyBonuses.remove(surveillance.name)
        }
    }

    // region a focus
    @Test
    fun `a focus that takes points from spies is worth them in the capital only`() {
        val network = focus("Network", reduction = 20)
        assertEquals(20, score(network, city = aCity))
        val other = game.addCity(a, game.tileMap[3, 0])
        assertEquals(0, score(network, city = other))
    }

    @Test
    fun `a focus that lowers our security is worth the fall, twice over in the capital`() {
        val loose = focus("Loose", "[-30] City Security [in this city]")
        val security = aCity.espionage.getSecurity()
        assertTrue("the setup has some security to lose", security in 1..29)
        // the security falls to 0 at most: (0 - security) in the capital counts twice
        assertEquals(-2 * security, score(loose, city = aCity))
        val other = game.addCity(a, game.tileMap[3, 0])
        assertEquals(-other.espionage.getSecurity(), score(loose, city = other))
    }

    @Test
    fun `a focus that costs the specialists of the city their yields is scored by each specialist`() {
        // -1 Gold, -1 Science, -1 Culture from every Civil Servant: -3 for each one of the two
        val servants = focus("Servants", "[-1 Gold, -1 Science, -1 Culture] from every [Civil Servant] [in this city]")
        aCity.population.specialistAllocations["Civil Servant"] = 2
        assertEquals(-6, score(servants, city = aCity))
    }

    @Test
    fun `a focus is worth what it blocks only when there is evidence that spies are at work in the city`() {
        a.addGold(1500)
        val shield = focus("Shield")
        val take = mission("Take", 100, "Steal [20]% of the treasury of the target")
        take.blockedBy.add(shield.name)
        // what the mission would take from us: 1500 * 20 / 100 = 300, / 1 for the era, / 15 = 20
        // no spy of another civilization anywhere: nothing is worth blocking
        assertEquals(0, score(shield, city = aCity))
        val theirSpy = b.espionageManager.addSpy()
        theirSpy.moveTo(bCity)
        b.espionageManager.endTurn()
        a.espionageManager.turnEspionageStarted = 40
        // there are spies of b, but none in our city: no evidence
        assertEquals(0, score(shield, city = aCity))
        // one in our city, and the city has not been robbed: 20 * (100 + 0) / 100 = 20
        theirSpy.moveTo(aCity)
        b.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, theirSpy.action)
        assertEquals(20, score(shield, city = aCity))
        // robbed 3 times in the 10 turns since we began (the turn is 50; the speed is standard): 20 * (100 + 100 * 15 * 3 / 10) / 100 = 20 * 550 / 100 = 110
        a.espionageManager.timesCityRobbed[aCity.id] = 3
        assertEquals(110, score(shield, city = aCity))
    }

    @Test
    fun `a focus that blocks nothing the others can do is worth only what it does`() {
        a.addGold(1500)
        val shield = focus("Shield", reduction = 20)
        mission("Take", 100, "Steal [20]% of the treasury of the target")      // not blocked by the focus
        val theirSpy = b.espionageManager.addSpy()
        theirSpy.moveTo(aCity)
        b.espionageManager.endTurn()
        // the reduction of the capital: 20; the mission is not blocked by this focus: nothing more
        assertEquals(20, score(shield, city = aCity))
    }

    @Test
    fun `a focus that makes our spies harder to find is worth it for each spy that gathers intel`() {
        val cover = focus("Cover", identifyReduction = 20)
        assertEquals("no spy of ours: nothing to protect", 0, score(cover, city = aCity))
        val gatherer = a.espionageManager.addSpy()
        gatherer.moveTo(bCity)
        val counter = a.espionageManager.addSpy()
        counter.moveTo(aCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, gatherer.action)
        assertEquals(SpyAction.CounterIntelligence, counter.action)
        // one spy gathers intel: 1 * 20; the counterspy is not one of those
        assertEquals(20, score(cover, city = aCity))
    }

    @Test
    fun `a focus is no mission and a mission is no focus`() {
        val network = focus("Network", reduction = 20)
        assertEquals(-1, score(network, city = bCity))
        assertEquals(-1, score(mission("Idle", 100), city = aCity))
    }

    @Test
    fun `a city-state has no missions to score`() {
        val cityState = game.addCiv(cityStateType = "Maritime")
        val csCity = game.addCity(cityState, game.tileMap[0, -5])
        assertEquals(-1, score(mission("Idle", 100), city = csCity))
    }

    @Test
    fun `a counterspy takes the best scoring focus and the first of equals`() {
        val weak = focus("Weak", reduction = 10)
        val strong = focus("Strong", reduction = 20)
        val same = focus("Same", reduction = 20)
        val spy = a.espionageManager.addSpy()
        spy.moveTo(aCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, spy.action)
        EspionageAutomation(a).automateSpies()
        assertEquals("the best of 10, 20 and 20, and the first of those", strong.name, spy.focus)
        assertTrue(weak.name != spy.focus && same.name != spy.focus)
    }
    // endregion

    // region the points a spy gathers
    @Test
    fun `a counterspy takes points from a spy that gathers intel there but not from a question without a spy`() {
        val network = focus("Network", reduction = 20)
        val counter = b.espionageManager.addSpy()
        counter.moveTo(bCity)
        b.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, counter.action)
        assertTrue(counter.chooseFocus(network))
        val security = bCity.espionage.getSecurity()
        val gatherer = a.espionageManager.addSpy()
        val rank = counter.getEffectiveRank()
        // without a spy: 50 less the security in percent, and no reduction of the counterspy (V:3321-3322)
        assertEquals(50 * (100 - security) / 100, a.espionageManager.networkPointsPerTurn(null, bCity, gathering = true))
        // with one: the focus takes 20 for each rank of the counterspy off the 50 first, then the security
        assertEquals((50 - 20 * rank) * (100 - security) / 100, a.espionageManager.networkPointsPerTurn(gatherer, bCity, gathering = true))
    }
    // endregion
}
