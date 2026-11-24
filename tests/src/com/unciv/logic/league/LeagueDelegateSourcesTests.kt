package com.unciv.logic.league

import com.unciv.logic.automation.civilization.DiplomatUsefulness
import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The delegate sources of Vox Populi that the ruleset can now carry (CvLeague::CalculateStartingVotesForMember, V:CvVotingClasses.cpp:4046-4390):
 * the diplomat row now comes from the technologies' data instead of the name "Globalization", and the wonder and policy rows ride `<for every [n] [City-States originally in the game]>`.
 */
@RunWith(GdxTestRunner::class)
class LeagueDelegateSourcesTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var third: Civilization
    private lateinit var spy: Spy
    private lateinit var league: League
    private var nextCityStateTile = 0

    private val gameInfo get() = game.gameInfo

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        a = game.addCiv()
        b = game.addCiv()
        third = game.addCiv()
        game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        game.addCity(third, game.tileMap[0, -5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        spy = a.espionageManager.addSpy()
        league = League().also { it.setTransients(gameInfo); it.lastSpecialSession = "Renaissance era" }
        gameInfo.league = league
    }

    //region Helpers
    /** A spy of [a] that has become the diplomat in the capital of [b], as in DiplomatTests */
    private fun placeDiplomatIn(target: Civilization = b) {
        spy.moveTo(target.getCapital()!!, asDiplomat = true)
        a.espionageManager.endTurn()
        repeat(spy.turnsRemainingForAction) { a.espionageManager.endTurn() }
        assertEquals("the fixture must really have a diplomat in place", SpyAction.Schmoozing, spy.action)
        assertTrue(a.espionageManager.getActiveDiplomatFor(target) != null)
    }

    /**
     * A NEW technology of this test's ruleset that gives [amount] delegates per diplomat. Ruleset.clone copies the map entries and not the objects, so the
     * technologies of the shared ruleset must never be edited: that would change every later test of the run (and the lazily built unique map would stay frozen)
     */
    private fun newDiplomatTech(amount: Int, name: String): Technology {
        val tech = Technology()
        tech.name = name
        tech.column = game.ruleset.technologies["Pottery"]!!.column
        tech.uniques.add("[$amount] World Congress Delegates for every capital that hosts one of our diplomats")
        game.ruleset.technologies[name] = tech
        return tech
    }

    private fun researchWithDiplomatDelegates(civ: Civilization, name: String, amount: Int) {
        newDiplomatTech(amount, name)
        civ.tech.addTechnology(name)
    }

    /** Globalization of this test's ruleset as a copy that carries no unique at all, so that the shared object stays as the data has it */
    private fun replaceGlobalizationWithOneWithoutUniques() {
        val real = game.ruleset.technologies["Globalization"]!!
        val bare = Technology()
        bare.name = real.name
        bare.column = real.column
        bare.row = real.row
        bare.prerequisites = HashSet(real.prerequisites)
        game.ruleset.technologies["Globalization"] = bare
    }

    /**
     * [count] city-states, of which the first [defeated] are dead: Civilization.isDefeated is "no cities" once it has owned an original capital,
     * and a city-state that never had a city or a unit is dead too, so the dead ones simply get no city and the living ones get a capital
     */
    private fun addCityStates(count: Int, defeated: Int = 0) {
        repeat(count) { index ->
            val state = game.addCiv(cityStateType = "Cultured")
            if (index >= defeated) {
                // every city-state city on its own tile of the map, spaced so that no two are adjacent; 8 + 1 + 8 of them are needed at most
                game.addCity(state, game.tileMap.tileList[40 + 7 * nextCityStateTile++], replacePalace = true)
                assertTrue("a city-state with a capital is alive", state.isAlive())
            } else {
                assertTrue("a city-state without a city or a unit is defeated", state.isDefeated())
            }
        }
    }
    //endregion

    @Test
    fun `Brave New World's Globalization carries the diplomat delegate in its data`() {
        val globalization = game.ruleset.technologies["Globalization"]!!
        val uniques = globalization.getMatchingUniques(UniqueType.WorldCongressDelegatesPerDiplomat)
        assertEquals(1, uniques.count())
        assertEquals(1, uniques.first().params[0].toInt())
    }

    @Test
    fun `a diplomat gives the delegates of the technologies researched and nothing before`() {
        placeDiplomatIn()
        val none = league.getDelegates(a)
        assertEquals(0, League.getDelegatesPerDiplomat(a))
        a.tech.addTechnology("Globalization")
        assertEquals(1, League.getDelegatesPerDiplomat(a))
        assertEquals(none + 1, league.getDelegates(a))
    }

    @Test
    fun `the delegate per diplomat is data, so another technology and another amount count`() {
        placeDiplomatIn()
        val none = league.getDelegates(a)
        // Pottery is not Globalization: the old rule, which named that technology, could not give anything for it
        researchWithDiplomatDelegates(a, "Pottery", 3)
        assertEquals(3, League.getDelegatesPerDiplomat(a))
        assertEquals(none + 3, league.getDelegates(a))
    }

    @Test
    fun `the delegates of several researched technologies are added`() {
        placeDiplomatIn()
        val none = league.getDelegates(a)
        researchWithDiplomatDelegates(a, "Pottery", 2)
        researchWithDiplomatDelegates(a, "Agriculture", 5)
        assertEquals(7, League.getDelegatesPerDiplomat(a))
        assertEquals(none + 7, league.getDelegates(a))
    }

    @Test
    fun `a technology that is not researched gives nothing`() {
        placeDiplomatIn()
        newDiplomatTech(4, "Unresearched Diplomacy")
        assertEquals(0, League.getDelegatesPerDiplomat(a))
    }

    @Test
    fun `the delegates are given for each capital with a diplomat, and not without one`() {
        researchWithDiplomatDelegates(a, "Pottery", 2)
        val withoutDiplomat = league.getDelegates(a)
        placeDiplomatIn()
        assertEquals(withoutDiplomat + 2, league.getDelegates(a))
        spy.moveTo(null)
        assertEquals(withoutDiplomat, league.getDelegates(a))
    }

    @Test
    fun `the AI finds a diplomat useful by the data and not by the name Globalization`() {
        assertEquals(DiplomatUsefulness.NONE, LeagueAutomation.getDiplomatUsefulnessAtCiv(a, b))
        researchWithDiplomatDelegates(a, "Pottery", 1)
        assertTrue("a technology that is not Globalization now makes diplomats useful",
            LeagueAutomation.getDiplomatUsefulnessAtCiv(a, b) > DiplomatUsefulness.NONE)
    }

    @Test
    fun `Globalization without the unique no longer makes diplomats useful, with it they are`() {
        // The control first: researched with the data as shipped it DOES count, so the zero below is the removed unique and not a broken fixture
        b.tech.addTechnology("Globalization")
        assertEquals(1, League.getDelegatesPerDiplomat(b))
        assertTrue(LeagueAutomation.getDiplomatUsefulnessAtCiv(b, a) > DiplomatUsefulness.NONE)

        // The same technology, by the same name, without its unique, researched by a civilization that has not yet got it
        replaceGlobalizationWithOneWithoutUniques()
        a.tech.addTechnology("Globalization")
        assertTrue("the fixture's civilization did research Globalization", a.tech.isResearched("Globalization"))
        assertEquals(0, League.getDelegatesPerDiplomat(a))
        assertEquals(DiplomatUsefulness.NONE, LeagueAutomation.getDiplomatUsefulnessAtCiv(a, b))
    }

    @Test
    fun `City-States originally in the game counts the defeated ones and no major`() {
        addCityStates(count = 5, defeated = 2)
        val countable = "City-States originally in the game"
        assertEquals(5, com.unciv.models.ruleset.unique.Countables.getCountableAmount(countable, a.state))
    }

    /** A civilization whose nation carries the one-for-every-8 unique (a nation's uniques are read like a policy's), with a capital, met by everybody */
    private fun civWithDelegatePerEightCityStates(): Civilization {
        val civ = game.addCiv("[1] World Congress Delegates <for every [8] [City-States originally in the game]>")
        game.addCity(civ, game.tileMap.tileList[20], replacePalace = true)
        return civ
    }

    @Test
    fun `one delegate for every 8 city-states originally in the game divides in whole numbers`() {
        val c = civWithDelegatePerEightCityStates()
        val plain = third // the same era and no unique, so the difference is the unique alone
        addCityStates(count = 7)
        assertEquals("seven city-states are not yet one for every eight", league.getDelegates(plain), league.getDelegates(c))
        addCityStates(count = 1)
        assertEquals("the eighth city-state gives the first delegate", league.getDelegates(plain) + 1, league.getDelegates(c))
        addCityStates(count = 8)
        assertEquals("16 city-states give two", league.getDelegates(plain) + 2, league.getDelegates(c))
    }

    @Test
    fun `a defeated city-state still counts towards the delegates given per 8`() {
        val c = civWithDelegatePerEightCityStates()
        val plain = third
        addCityStates(count = 8, defeated = 3)
        assertEquals("three of the eight are dead", 5, gameInfo.getAliveCityStates().size)
        assertEquals("the delegate is still given with three of the eight dead", league.getDelegates(plain) + 1, league.getDelegates(c))
    }

    @Test
    fun `the Consulate of the ruleset gives one delegate for every 8 city-states of the game, not eight`() {
        val consulate = game.ruleset.buildings["Consulate"]!!
        val owner = third
        owner.getCapital()!!.cityConstructions.addBuilding(consulate)
        addCityStates(count = 7)
        val none = game.addCiv().also { game.addCity(it, game.tileMap.tileList[30], replacePalace = true) }
        assertEquals("7 city-states: none yet", league.getDelegates(none), league.getDelegates(owner))
        addCityStates(count = 1)
        assertEquals("8 city-states: one", league.getDelegates(none) + 1, league.getDelegates(owner))
        addCityStates(count = 8)
        assertEquals("16 city-states: two, not eight", league.getDelegates(none) + 2, league.getDelegates(owner))
    }

    @Test
    fun `the AI values a building by VP's delegate column, which is the 8 of one delegate for every 8 city-states`() {
        // CvCityStrategyAI.cpp:4586-4588: diplomatic value 200 / ExtraLeagueVotes. The flat form and the per-8 form are the same column, 8
        val flat = game.createBuilding("[8] World Congress Delegates")
        val perEight = game.createBuilding("[1] World Congress Delegates <for every [8] [City-States originally in the game]>")
        val perTwo = game.createBuilding("[1] World Congress Delegates <for every [2] [City-States originally in the game]>")
        val none = game.createBuilding()
        val value = { building: com.unciv.models.ruleset.Building ->
            com.unciv.logic.automation.strategy.BuildingValueAi.grandStrategyValue(a, null, building)
        }
        assertEquals("the fixture's buildings are worth nothing but the delegates", 0, value(none))
        assertTrue("a delegate column gives the building a value at all", value(flat) > 0)
        assertEquals("the per-8 form is the column 8, as the flat form", value(flat), value(perEight))
        assertTrue("a smaller divisor is worth more: 200 / 2 against 200 / 8", value(perTwo) > value(perEight))
    }

    @Test
    fun `the new unique types are valid for the targets that carry them`() {
        val expected = mapOf(
            UniqueType.WorldCongressDelegatesPerReligionCities to UniqueTarget.Global,
            UniqueType.WorldCongressDelegatesPerFriendships to UniqueTarget.Global,
            UniqueType.WorldCongressDelegatesPerCapitals to UniqueTarget.Global,
            UniqueType.WorldCongressDelegatesPerDefensivePacts to UniqueTarget.Global,
            UniqueType.WorldCongressDelegatesPerGold to UniqueTarget.Global,
            UniqueType.WorldCongressDelegatesPerDiplomat to UniqueTarget.Tech,
            UniqueType.WorldCongressDelegatesPerImprovements to UniqueTarget.FollowerBelief,
        )
        for ((type, target) in expected) {
            assertTrue("$type must be usable on $target", type.canAcceptUniqueTarget(target))
            assertTrue("$type must have a description for the documentation", type.docDescription != null)
        }
    }
}
