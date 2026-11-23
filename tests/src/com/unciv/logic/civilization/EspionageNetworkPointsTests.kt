package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.IntrigueFunctions
import com.unciv.logic.civilization.managers.IntrigueType
import com.unciv.logic.automation.strategy.ArmyType
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
 *  Vox Populi's Network Points (`CvPlayerEspionage::CalcNetworkPointsPerTurn`, V:CvEspionageClasses.cpp:3262-3379; `CvCity::CalculateCitySecurity`, V:CvCity.cpp:25992-26085; the passive bonuses
 *  `CvCityEspionage::AddNetworkPoints`, V:CvEspionageClasses.cpp:7043-7153; `CvPlayerEspionage::CalcRequired(SPY_STATE_TRAVELLING)`, 3137-3182). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 *  Only with [ModConstants.espionageLikeVp]. [a] is a human with a spy; [b] is an AI civilization with a city 5 tiles away, [c] a city-state.
 */
@RunWith(GdxTestRunner::class)
class EspionageNetworkPointsTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var aCity: City
    private lateinit var bCity: City
    private lateinit var spy: Spy
    private val addedBonuses = ArrayList<String>()
    private var difficultyBefore = 0

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        a = game.addCiv("[+10] Network Points per turn for our spies in foreign cities", isPlayer = true)
        b = game.addCiv()
        c = game.addCiv(cityStateType = "Cultured")
        aCity = game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        game.addCity(c, game.tileMap[5, 0], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        spy = a.espionageManager.addSpy()
        difficultyBefore = game.gameInfo.getDifficulty().spySecurityModifier
    }

    @After
    fun tearDown() {
        game.gameInfo.getDifficulty().spySecurityModifier = difficultyBefore
        for (name in addedBonuses) game.ruleset.spyBonuses.remove(name)
    }

    private fun bonus(name: String, points: Int, kind: SpyBonusKind = SpyBonusKind.Passive, vararg uniques: String) {
        game.ruleset.spyBonuses[name] = SpyBonus().apply { this.name = name; this.kind = kind; networkPoints = points; this.uniques = ArrayList(uniques.toList()) }
        addedBonuses += name
    }

    /** The spy travels to [city] and arrives (the number of turns is the travel time) */
    private fun arrive(city: City, asDiplomat: Boolean = false) {
        spy.moveTo(city, asDiplomat)
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) a.espionageManager.endTurn()
        assertTrue(turns < 20)
    }

    @Test
    fun `a spy needs the base turns plus the proximity of the civilization and half with open borders`() {
        val constants = game.ruleset.modOptions.constants
        constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(10, 20, 30, 40))
        val expected = 1 + when (a.getProximity(b)) {
            Proximity.Neighbors -> 10
            Proximity.Close -> 20
            Proximity.Far -> 30
            Proximity.Distant -> 40
            Proximity.None -> 0
        }
        assertEquals(expected, a.espionageManager.getTravelTurns(bCity))
        assertEquals("an own city is 1 turn away whatever the table says", 1, a.espionageManager.getTravelTurns(aCity))

        a.getDiplomacyManager(b)!!.hasOpenBorders = true
        assertEquals("halved, whole turns", expected / 2, a.espionageManager.getTravelTurns(bCity))

        constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        assertEquals("never under 1", 1, a.espionageManager.getTravelTurns(bCity))
    }

    @Test
    fun `a spy travels for the turns, then gathers intel in a foreign major, counters spies at home and rigs a city-state`() {
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 2, travelTurnsByProximity = listOf(0, 0, 0, 0))
        spy.moveTo(bCity)
        assertEquals(SpyAction.Moving, spy.action)
        assertEquals(2, spy.turnsRemainingForAction)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.Moving, spy.action)
        a.espionageManager.endTurn()
        assertEquals("not the old establishing of a network", SpyAction.GatheringIntel, spy.action)

        spy.moveTo(aCity)
        repeat(2) { a.espionageManager.endTurn() }
        assertEquals(SpyAction.CounterIntelligence, spy.action)

        spy.moveTo(c.getCapital()!!)
        repeat(2) { a.espionageManager.endTurn() }
        assertEquals(SpyAction.RiggingElections, spy.action)
    }

    @Test
    fun `a diplomat schmoozes at once on arriving`() {
        a.getDiplomacyManager(b)!!.makePeace()
        arrive(bCity, asDiplomat = true)
        assertEquals(SpyAction.Schmoozing, spy.action)
        assertTrue(spy.isDiplomat)
    }

    @Test
    fun `network points are 50 plus the policy points less the security of the city`() {
        arrive(bCity)
        // the security of the city: 20 base, less 2 for its one citizen (the difficulties of Brave New World have no security modifier)
        val security = bCity.espionage.getSecurity()
        assertEquals(18, security)
        // (50 + 10) = 60, x (100 - 18) / 100 = 49 (whole numbers, as the original)
        assertEquals(49, a.espionageManager.networkPointsPerTurn(spy, bCity))

        a.espionageManager.endTurn()
        assertEquals(49, spy.networkPoints)
        a.espionageManager.endTurn()
        assertEquals(98, spy.networkPoints)
        assertEquals("the most ever stored follows", spy.networkPoints, spy.maxNetworkPoints)
    }

    @Test
    fun `a diplomat gathers the points without the security reduction`() {
        a.getDiplomacyManager(b)!!.makePeace()
        arrive(bCity, asDiplomat = true)
        assertEquals("Schmoozing is not reduced by the security (V:CvEspionageClasses.cpp:3354)", 60, a.espionageManager.networkPointsPerTurn(spy, bCity))
    }

    @Test
    fun `a spy that is not gathering or schmoozing gathers nothing`() {
        arrive(aCity)
        assertEquals(SpyAction.CounterIntelligence, spy.action)
        assertEquals(0, a.espionageManager.networkPointsPerTurn(spy, aCity))
    }

    @Test
    fun `the security is 20 less 2 for each citizen, between 0 and 100`() {
        game.gameInfo.getDifficulty().spySecurityModifier = 0
        val extra = b.getDifficulty().spySecurityModifier
        val difficulty = game.gameInfo.getDifficulty().spySecurityModifier
        val offset = extra + difficulty
        assertEquals(20 + offset - 2, bCity.espionage.getSecurity())
        bCity.population.setPopulation(4)
        assertEquals(20 + offset - 8, bCity.espionage.getSecurity())
        bCity.population.setPopulation(30)
        assertEquals("the floor is 0", 0, bCity.espionage.getSecurity())
        game.gameInfo.getDifficulty().spySecurityModifier = 500
        bCity.population.setPopulation(1)
        assertEquals("and the cap 100", 100, bCity.espionage.getSecurity())
        assertEquals("a city-state has none (-1)", -1, c.getCapital()!!.espionage.getSecurity())
    }

    @Test
    fun `the difficulty adds to the security of a human civilization once and to an AI civilization with the game's difficulty`() {
        val difficulty = game.gameInfo.getDifficulty()
        difficulty.spySecurityModifier = 10
        // the human: its own handicap is the chosen difficulty (CvCity.cpp:26008-26011)
        assertEquals(20 + a.getDifficulty().spySecurityModifier - 2, aCity.espionage.getSecurity())
        assertEquals("the human's difficulty is the chosen one, not counted twice", 20 + 10 - 2, aCity.espionage.getSecurity())
        // an AI: its own handicap and, on top, that of the game
        assertEquals(20 + b.getDifficulty().spySecurityModifier + 10 - 2, bCity.espionage.getSecurity())
    }

    @Test
    fun `the security reduces the points of a spy gathering intel in percent`() {
        arrive(bCity)
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(networkPointsReductionPerSecurity = 50)
        val security = bCity.espionage.getSecurity()
        // security x 50 / 100 percent are lost
        assertEquals(60 * (100 - security * 50 / 100) / 100, a.espionageManager.networkPointsPerTurn(spy, bCity))
    }

    @Test
    fun `the effectiveness uniques of the buildings count as percent of the points`() {
        arrive(bCity)
        game.gameInfo.getDifficulty().spySecurityModifier = 0
        bCity.cityConstructions.addBuilding("Constabulary")  // [-25]% enemy spy effectiveness
        val security = bCity.espionage.getSecurity()
        assertEquals(60 * 75 / 100 * (100 - security) / 100, a.espionageManager.networkPointsPerTurn(spy, bCity))
    }

    @Test
    fun `without the switch nothing is gathered and the old establishing of a network begins`() {
        game.ruleset.modOptions.constants.espionageLikeVp = false
        spy.moveTo(bCity)
        assertEquals(1, spy.turnsRemainingForAction)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.EstablishNetwork, spy.action)
        assertEquals(0, a.espionageManager.networkPointsPerTurn(spy, bCity))
    }

    @Test
    fun `a passive bonus is earned on the most points ever stored, not the current, and lost when the spy leaves`() {
        bonus("Surveillance I", 500, uniques = arrayOf("[5]% of the city's Science goes to the spy's owner"))
        arrive(bCity)
        spy.addNetworkPoints(499)
        assertTrue(spy.getBonuses().isEmpty())
        spy.addNetworkPoints(1)
        assertEquals(listOf("Surveillance I"), spy.getBonuses().map { it.name })

        spy.spendNetworkPoints(400)
        assertEquals(100, spy.networkPoints)
        assertEquals("spending leaves the maximum", 500, spy.maxNetworkPoints)
        assertEquals(1, spy.getBonuses().size)

        spy.moveTo(null)
        assertEquals(0, spy.networkPoints)
        assertEquals(0, spy.maxNetworkPoints)
        assertTrue(spy.getBonuses().isEmpty())
    }

    @Test
    fun `a diplomat has the bonuses of its kind only and a spy those of the passive table`() {
        bonus("Surveillance I", 0, uniques = arrayOf("[5]% of the city's Science goes to the spy's owner"))
        bonus("Schmoozing I", 0, SpyBonusKind.Diplomat, "[20]% of Congress votes can be traded with this civilization")
        a.getDiplomacyManager(b)!!.makePeace()
        arrive(bCity)
        assertEquals(listOf("Surveillance I"), spy.getBonuses().map { it.name })
        spy.moveTo(null)
        spy.moveTo(bCity, asDiplomat = true)
        assertEquals(listOf("Schmoozing I"), spy.getBonuses().map { it.name })
    }

    @Test
    fun `the passive science is the percent of the city's science, changed by the policy modifier`() {
        bonus("Surveillance I", 0, uniques = arrayOf("[5]% of the city's Science goes to the spy's owner"))
        bonus("Surveillance II", 100, uniques = arrayOf("[2]% of the city's Science goes to the spy's owner"))
        arrive(bCity)
        bCity.cityStats.currentCityStats.science = 200f
        spy.addNetworkPoints(0)
        assertEquals("5 percent of 200 science, times 100", 1000, a.espionageManager.getPassiveScienceTimes100())
        spy.addNetworkPoints(100)
        assertEquals("(5 + 2) percent", 1400, a.espionageManager.getPassiveScienceTimes100())

        game.ruleset.modOptions.constants.espionageLikeVp = false
        assertEquals(0, a.espionageManager.getPassiveScienceTimes100())
    }

    @Test
    fun `the vision of a spy gathering intel is the extra vision of its bonuses`() {
        bonus("Surveillance II", 100, uniques = arrayOf("[1] more tile(s) of vision around the city"))
        arrive(bCity)
        assertTrue("without a bonus it sees nothing of the city", a.espionageManager.getTilesVisibleViaSpies().none())
        spy.addNetworkPoints(100)
        val seen = a.espionageManager.getTilesVisibleViaSpies().toSet()
        assertEquals(bCity.getCenterTile().getTilesInDistance(1).toSet(), seen)
    }

    @Test
    fun `a diplomat's bonuses are read through the accessors of the manager`() {
        bonus("Schmoozing I", 0, SpyBonusKind.Diplomat, "[20]% of Congress votes can be traded with this civilization")
        bonus("Schmoozing II", 0, SpyBonusKind.Diplomat, "[20]% Gold on trade routes between us", "The spy reveals the true approaches of this civilization")
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        assertEquals(0, a.espionageManager.getDiplomatVoteTradePercent(b))
        arrive(bCity, asDiplomat = true)
        assertEquals(20, a.espionageManager.getDiplomatVoteTradePercent(b))
        assertEquals(20, a.espionageManager.getDiplomatTradeBonus(b))
        assertTrue(a.espionageManager.getDiplomatRevealsTrueApproaches(b))
    }

    @Test
    fun `a spy in the middle of the old theft is migrated to gathering intel when the switch is on`() {
        game.ruleset.modOptions.constants.espionageLikeVp = false
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.EstablishNetwork, spy.action)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        a.espionageManager.setTransients(a)
        assertEquals(SpyAction.GatheringIntel, spy.action)
    }

    @Test
    fun `the network points are part of the copy of a spy`() {
        arrive(bCity)
        spy.addNetworkPoints(30)
        val copy = a.espionageManager.clone().spyList.single()
        assertEquals(30, copy.networkPoints)
        assertEquals(30, copy.maxNetworkPoints)
    }

    @Test
    fun `a spy in a foreign city sees only the wonder in production when it gathers, a diplomat the rest of the intrigue`() {
        game.ruleset.buildings.values.first { it.isWonder }.name.let { bCity.cityConstructions.setCurrentConstruction(it) }
        b.ai.military.armyTypeBeingBuilt = ArmyType.Land
        arrive(bCity)
        IntrigueFunctions.uncoverWonder(spy)
        val types = a.espionageManager.intrigueMessages.map { it.type }
        assertEquals(listOf(IntrigueType.ConstructingWonder), types)
        assertFalse(IntrigueType.BuildingArmy in types)

        IntrigueFunctions.uncoverIntrigue(spy)
        assertTrue("the army in the making is the other half", IntrigueType.BuildingArmy in a.espionageManager.intrigueMessages.map { it.type })
    }

    @Test
    fun `Policy points of spies make a percent of the capital's culture, up to the cap`() {
        game.ruleset.modOptions.constants.spyPointsLikeVp = true
        val capitalCiv = game.addCiv("[+100 Culture] [in capital]", "[+3]% [Culture] [in capital] per [100] Spy Points ever accumulated, up to [30]%")
        val capital = game.addCity(capitalCiv, game.tileMap[-5, 0], replacePalace = true)
        capital.cityStats.update()
        val base = capital.cityStats.currentCityStats.culture
        assertTrue(base >= 100f)

        capitalCiv.espionageManager.spyPointsTotal = 250
        capital.cityStats.update()
        assertEquals("3 x 250 / 100 is 7 percent (whole numbers, as the original)", base * 1.07f, capital.cityStats.currentCityStats.culture, 0.05f)

        capitalCiv.espionageManager.spyPointsTotal = 5000
        capital.cityStats.update()
        assertEquals("the cap is 30 percent", base * 1.30f, capital.cityStats.currentCityStats.culture, 0.05f)

        game.ruleset.modOptions.constants.spyPointsLikeVp = false
        capital.cityStats.update()
        assertEquals("without the switch the points do not count", base, capital.cityStats.currentCityStats.culture, 0.05f)
    }
}
