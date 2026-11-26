package com.unciv.logic.automation.unit

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The decisions of CvEspionageAI: target numbers, the three city lists, coups, and the city-state plan */
@RunWith(GdxTestRunner::class)
class EspionageAutomationTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCapital: City
    private lateinit var bCapital: City
    private lateinit var ai: EspionageAutomation

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        a = game.addCiv()
        b = game.addCiv()
        aCapital = game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCapital = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        ai = EspionageAutomation(a)
    }

    private fun addSpies(count: Int): List<Spy> = List(count) { a.espionageManager.addSpy() }

    private fun setUpIn(spy: Spy, city: City) {
        spy.moveTo(city)
        var guard = 0
        while (!spy.isSetUp() && guard++ < 20) a.espionageManager.endTurn()
        assertTrue(spy.isSetUp())
    }

    // region diplomatic modifier
    @Test
    fun `offense score multiplier is 1 at war and 10 at plain peace`() {
        assertEquals(10L, ai.diplomaticModifier(b))
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(1L, ai.diplomaticModifier(b))
    }

    @Test
    fun `friends research partners and promises make spying on them less attractive`() {
        val ours = a.getDiplomacyManager(b)!!
        ours.setFlag(DiplomacyFlags.ResearchAgreement, 10)
        assertEquals(50L, ai.diplomaticModifier(b))
        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        assertEquals(2500L, ai.diplomaticModifier(b))
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.AgreedToNotSendSpies, 10)
        assertEquals(250_000L, ai.diplomaticModifier(b))
    }

    @Test
    fun `a denunciation halves the multiplier and replaces the friendship factor`() {
        val ours = a.getDiplomacyManager(b)!!
        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        ours.setFlag(DiplomacyFlags.Denunciation, 10)
        assertEquals(5L, ai.diplomaticModifier(b))
    }
    // endregion

    // region steal target priority
    @Test
    fun `offense score is the turns to steal at the remembered potential times the diplomatic multiplier`() {
        a.espionageManager.lastBasePotential[bCapital.id] = 3000
        // 60000 / 3000 = 20 turns, x10 at peace
        assertEquals(200L, ai.offenseScore(bCapital, 60000))
    }

    @Test
    fun `without a remembered potential the offense score falls back on population`() {
        bCapital.population.setPopulation(4)
        // 60000 / (4 * 100) = 150 turns, x10
        assertEquals(1500L, ai.offenseScore(bCapital, 60000))
    }

    @Test
    fun `a civilization out of technologies this turn is the worst target and an earlier turn scales the score`() {
        a.espionageManager.lastBasePotential[bCapital.id] = 3000
        game.gameInfo.turns = 40
        a.espionageManager.civOutOfTechTurn[b.civID] = 40
        assertEquals(Int.MAX_VALUE.toLong(), ai.offenseScore(bCapital, 60000))
        a.espionageManager.civOutOfTechTurn[b.civID] = 25
        assertEquals(200L * 25, ai.offenseScore(bCapital, 60000))
    }

    @Test
    fun `offense list puts the city that is quickest to rob first`() {
        val slow = game.addCity(b, game.tileMap[0, 6])
        val fast = game.addCity(b, game.tileMap[0, 7])
        a.espionageManager.lastBasePotential[bCapital.id] = 30
        a.espionageManager.lastBasePotential[slow.id] = 10
        a.espionageManager.lastBasePotential[fast.id] = 90
        val list = ai.buildOffenseCityList()
        assertEquals(listOf(fast, bCapital, slow), list)
    }

    @Test
    fun `offense list ignores unexplored cities and our own`() {
        val hidden = game.addCity(b, game.tileMap[0, 7])
        hidden.getCenterTile().setExplored(a, false)
        assertEquals(listOf(bCapital), ai.buildOffenseCityList())
    }

    @Test
    fun `offense list leaves out a friend and one we promised not to spy on`() {
        val ours = a.getDiplomacyManager(b)!!
        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        assertEquals(emptyList<City>(), ai.buildOffenseCityList())
        ours.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.AgreedToNotSendSpies, 10)
        assertEquals(emptyList<City>(), ai.buildOffenseCityList())
        b.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.AgreedToNotSendSpies)
        assertEquals(listOf(bCapital), ai.buildOffenseCityList())
        ours.declareWar()
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.AgreedToNotSendSpies, 10)
        assertEquals("at war, no promise holds", listOf(bCapital), ai.buildOffenseCityList())
    }
    // endregion

    // region defensive placement
    @Test
    fun `defense list guards the city where a thief would steal fastest`() {
        val rich = game.addCity(a, game.tileMap[0, -3])
        aCapital.cityStats.currentCityStats.science = 4f
        rich.cityStats.currentCityStats.science = 30f
        assertEquals(listOf(rich, aCapital), ai.buildDefenseCityList())
    }

    @Test
    fun `a spy that must defend goes to the best defense city`() {
        val rich = game.addCity(a, game.tileMap[0, -3])
        aCapital.cityStats.currentCityStats.science = 4f
        rich.cityStats.currentCityStats.science = 30f
        val spy = addSpies(1).single()
        ai.automateSpies()
        spy.let { assertSame(rich, it.getCityOrNull()) }
        assertEquals(SpyAction.Moving, spy.action)
    }
    // endregion

    // region spy numbers
    @Test
    fun `without a grand strategy a third of the spies sit in city-states and the rest guard when we lead in technologies`() {
        a.ai.setGrandStrategyAndPursuit(null)
        game.addCity(a, game.tileMap[0, -3])
        addSpies(3)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(1, targets.cityState)
        assertEquals(0, targets.offensive)
        assertEquals(2, targets.defensive)
        assertEquals(0, targets.diplomat)
    }

    @Test
    fun `lagging in technologies puts the spies on offense`() {
        game.addCity(b, game.tileMap[0, 6])
        for (tech in game.ruleset.technologies.keys.filter { !b.tech.isResearched(it) }.take(3)) b.tech.addTechnology(tech)
        addSpies(2)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(2, targets.offensive)
        assertEquals(0, targets.defensive)
    }

    @Test
    fun `a spaceship strategy keeps spies out of city-states`() {
        a.ai.setGrandStrategyAndPursuit(GrandStrategyAi.SPACESHIP)
        game.addCity(a, game.tileMap[0, -3])
        addSpies(3)
        assertEquals(0, ai.findTargetSpyNumbers().cityState)
    }

    @Test
    fun `spies are never more numerous than the cities they can work in`() {
        addSpies(5)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(1, targets.defensive)
        assertEquals(1, targets.offensive)
    }

    @Test
    fun `a diplomatic strategy with nobody worth a diplomat spends the spies elsewhere`() {
        a.ai.setGrandStrategyAndPursuit(GrandStrategyAi.UNITED_NATIONS)
        addSpies(1)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(0, targets.diplomat)
        assertEquals(1, targets.defensive)
    }
    // endregion

    // region coups
    private fun cityStateAllied(ally: Civilization?, y: Int): City {
        val cityState = game.addCiv(cityStateType = "Maritime")
        val city = game.addCity(cityState, game.tileMap[0, y])
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.allyCiv = ally
        return city
    }

    @Test
    fun `a set up spy considers a coup in a city-state allied to someone else`() {
        val city = cityStateAllied(b, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, city)
        assertTrue(ai.isCoupCandidate(spy))
    }

    @Test
    fun `no coup in an unallied city-state or in one allied to us`() {
        val free = cityStateAllied(null, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, free)
        assertFalse(ai.isCoupCandidate(spy))
        free.civ.allyCiv = a
        assertFalse(ai.isCoupCandidate(spy))
    }

    @Test
    fun `no coup against the ally of a civilization we have a declaration of friendship with`() {
        val city = cityStateAllied(b, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, city)
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        assertFalse(ai.isCoupCandidate(spy))
    }

    @Test
    fun `a spy still on its way is no coup candidate`() {
        val city = cityStateAllied(b, -5)
        val spy = addSpies(1).single()
        spy.moveTo(city)
        assertFalse(ai.isCoupCandidate(spy))
    }
    // endregion

    // region open door and sphere of influence
    // CvEspionageAI::BuildMinorCityList skips a city-state with an Open Door in force (IsNoAlly, V:CvEspionageClasses.cpp:9962), with a permanent ally (9965) and with an
    // enactment of either on the table for it (9976-9991); EvaluateMinorCivSpies (10423-10512, called by DoTurn at 7750) flags a spy in such a city-state to be reassigned
    // (10433, 10447, 10489-10509), and a flagged spy that is not in the top of its role's list leaves (the engine's automateSpies).
    private fun door(): Resolution = game.ruleset.resolutions.getOrPut("Test Door") {
        Resolution().apply {
            name = "Test Door"
            proposerDecision = ResolutionDecision.CityState
            uniques = arrayListOf("Opens the door of the chosen City-State to every civilization")
        }
    }

    private fun sphere(): Resolution = game.ruleset.resolutions.getOrPut("Test Sphere") {
        Resolution().apply {
            name = "Test Sphere"
            proposerDecision = ResolutionDecision.CityState
            uniques = arrayListOf("Puts the chosen City-State in the sphere of influence of the proposer")
        }
    }

    private fun onTheTable(resolution: String, choice: String?, repeal: Boolean = false) {
        val league = game.gameInfo.league ?: League().also { game.gameInfo.league = it; it.setTransients(game.gameInfo) }
        league.proposals.add(LeagueProposal(100 + league.proposals.size, resolution, repeal, b.civID, choice))
    }

    private fun listed(city: City) = city in ai.buildMinorCityList()

    @Test
    fun `an ordinary city-state is on the list and one with an open door or a permanent ally is not`() {
        val plain = cityStateAllied(null, -5)
        val opened = cityStateAllied(null, -6)
        val spheres = cityStateAllied(null, -7)
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        assertTrue(listed(plain))
        assertTrue(listed(opened))
        assertTrue(listed(spheres))
        opened.civ.noAlly = true
        spheres.civ.permanentAllyCiv = b
        assertTrue(listed(plain))
        assertFalse("an open door in force", listed(opened))
        assertFalse("a sphere of influence in force", listed(spheres))
    }

    @Test
    fun `a city-state with an open door or a sphere on the table for it is not on the list, a repeal or another city-state's proposal does not count`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        door(); sphere()
        val one = cityStateAllied(null, -5)
        val two = cityStateAllied(null, -6)
        val three = cityStateAllied(null, -7)
        onTheTable("Test Door", one.civ.civID)
        onTheTable("Test Sphere", two.civ.civID)
        onTheTable("Test Door", three.civ.civID, repeal = true)
        assertFalse("an open door proposed", listed(one))
        assertFalse("a sphere proposed", listed(two))
        assertTrue("a repeal is no enactment (VP reads m_vEnactProposals)", listed(three))
    }

    @Test
    fun `a proposal of a resolution with neither effect, or without the switch, leaves the city-state on the list`() {
        game.ruleset.resolutions["Plain"] = Resolution().apply { name = "Plain"; proposerDecision = ResolutionDecision.CityState }
        door()
        val one = cityStateAllied(null, -5)
        val two = cityStateAllied(null, -6)
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        onTheTable("Plain", one.civ.civID)
        onTheTable("Test Door", two.civ.civID)
        assertTrue("a plain resolution", listed(one))
        assertFalse(listed(two))
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = false
        assertTrue("the switch is off", listed(two))
    }

    private fun spyInCityStateWithOneCityOfOurs(): Pair<Spy, City> {
        a.ai.setGrandStrategyAndPursuit(null)
        game.addCity(a, game.tileMap[0, -3])
        val city = cityStateAllied(null, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, city)
        return spy to city
    }

    @Test
    fun `a spy in an ordinary city-state stays, in one with an open door or a sphere it leaves`() {
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        ai.automateSpies()
        assertSame("nothing flags it", city, spy.getCityOrNull())
        city.civ.noAlly = true
        ai.automateSpies()
        assertTrue("an open door in force sends it away", spy.getCityOrNull() != city)
    }

    @Test
    fun `a city-state whose permanent ally is us is left off the list too`() {
        val city = cityStateAllied(null, -5)
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        assertTrue(listed(city))
        city.civ.permanentAllyCiv = a
        assertFalse("V:CvEspionageClasses.cpp:9965 has no exception for our own sphere", listed(city))
    }

    @Test
    fun `a spy in the city of a major civilization is not flagged by a proposal that names that civilization`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        door()
        a.ai.setGrandStrategyAndPursuit(null)
        game.addCity(a, game.tileMap[0, -3])
        val spy = addSpies(1).single()
        setUpIn(spy, bCapital)
        ai.automateSpies()
        val before = spy.getCityOrNull()
        onTheTable("Test Door", b.civID) // never valid (the choice is a major), but in the list by hand
        ai.automateSpies()
        assertSame("what a city-state's door does not reach a major's city", before, spy.getCityOrNull())
    }

    @Test
    fun `a spy leaves a city-state with a sphere of influence in force`() {
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        city.civ.permanentAllyCiv = b
        ai.automateSpies()
        assertTrue(spy.getCityOrNull() != city)
    }

    @Test
    fun `a spy leaves a city-state that has an open door proposed and stays when the proposal is a repeal or for another`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        door()
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        onTheTable("Test Door", city.civ.civID, repeal = true)
        onTheTable("Test Door", b.civID)
        ai.automateSpies()
        assertSame("neither a repeal nor a proposal for another civilization flags it", city, spy.getCityOrNull())
        onTheTable("Test Door", city.civ.civID)
        ai.automateSpies()
        assertTrue(spy.getCityOrNull() != city)
    }

    // CvEspionageAI::EvaluateMinorCivSpies also flags a spy in a city-state at war with its owner (V:CvEspionageClasses.cpp:10460-10472); BuildMinorCityList already leaves such a
    // city-state off its list (9951-9955), so the flagged spy is pulled out. The engine had no other place that does it. Behind leagueSessionRulesLikeVp, like the proposal test.
    @Test
    fun `a spy leaves a city-state that we went to war with, with the switch on`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        ai.automateSpies()
        assertSame("at peace it stays", city, spy.getCityOrNull())
        a.getDiplomacyManager(city.civ)!!.declareWar()
        assertTrue(a.isAtWarWith(city.civ))
        ai.automateSpies()
        assertTrue("at war it leaves", spy.getCityOrNull() != city)
    }

    @Test
    fun `without the switch a spy stays in a city-state at war`() {
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        a.getDiplomacyManager(city.civ)!!.declareWar()
        ai.automateSpies()
        assertSame(city, spy.getCityOrNull())
    }

    @Test
    fun `a war with a major civilization or with another city-state does not move a spy out of a city-state at peace`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        val other = cityStateAllied(null, -8)
        a.getDiplomacyManager(b)!!.declareWar()
        a.getDiplomacyManager(other.civ)!!.declareWar()
        ai.automateSpies()
        assertSame(city, spy.getCityOrNull())
    }

    @Test
    fun `a war between the city-state and another civilization is no war of ours, the spy stays`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        b.diplomacyFunctions.makeCivilizationsMeet(city.civ)
        b.getDiplomacyManager(city.civ)!!.declareWar()
        assertTrue(city.civ.isAtWar())
        assertFalse(a.isAtWarWith(city.civ))
        ai.automateSpies()
        assertSame(city, spy.getCityOrNull())
    }

    @Test
    fun `a spy in a city-state at war leaves even though the city-state would hold a city-state slot at peace`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        a.ai.setGrandStrategyAndPursuit(null)
        game.addCity(a, game.tileMap[0, -3])
        val city = cityStateAllied(null, -5)
        val spies = addSpies(3)
        setUpIn(spies[0], city)
        assertEquals("three spies leave one for the city-states", 1, ai.findTargetSpyNumbers().cityState)
        a.getDiplomacyManager(city.civ)!!.declareWar()
        ai.automateSpies()
        assertTrue("the war empties the slot: the city-state is off the list", spies[0].getCityOrNull() != city)
    }

    @Test
    fun `peace after the war brings no flag, a spy stays`() {
        game.ruleset.modOptions.constants.leagueSessionRulesLikeVp = true
        val (spy, city) = spyInCityStateWithOneCityOfOurs()
        val dip = a.getDiplomacyManager(city.civ)!!
        dip.declareWar()
        dip.makePeace()
        assertFalse(a.isAtWarWith(city.civ))
        ai.automateSpies()
        assertSame(city, spy.getCityOrNull())
    }
    // endregion

    // region city-state plan
    @Test
    fun `before the United Nations countdown the plan is to play normal`() {
        val plan = ai.getCityStatePlan()
        assertEquals(EspionageAutomation.CityStatePlanKind.PlayNormal, plan.kind)
    }

    @Test
    fun `a rival allied with half of the city-states is attacked`() {
        repeat(2) { cityStateAllied(b, -4 - it) }
        a.espionageManager.unCountdownStarted = true
        val plan = ai.getCityStatePlan()
        assertEquals(EspionageAutomation.CityStatePlanKind.AttackToPreventDefeat, plan.kind)
        assertSame(b, plan.diploThreat)
    }

    @Test
    fun `the civilization allied with half of the city-states defends its lead`() {
        repeat(2) { cityStateAllied(b, -4 - it) }
        b.espionageManager.unCountdownStarted = true
        assertEquals(EspionageAutomation.CityStatePlanKind.DefendForWin, EspionageAutomation(b).getCityStatePlan().kind)
    }

    @Test
    fun `a quarter of the city-states makes us collect votes`() {
        repeat(4) { cityStateAllied(if (it == 0) b else null, -4 - it) }
        a.espionageManager.unCountdownStarted = true
        assertEquals(EspionageAutomation.CityStatePlanKind.CollectVotes, ai.getCityStatePlan().kind)
    }

    @Test
    fun `the city-state to attack is the one allied to the threat with the least influence`() {
        val city = cityStateAllied(b, -5)
        val plan = EspionageAutomation.CityStatePlan(EspionageAutomation.CityStatePlanKind.AttackToPreventDefeat, b)
        val allied = ai.minorCityScore(city.civ, plan, 0)
        val other = cityStateAllied(null, -6)
        assertTrue(allied > ai.minorCityScore(other.civ, plan, 0))
        assertEquals(100L, ai.minorCityScore(other.civ, plan, 0))
    }
    // endregion
}
