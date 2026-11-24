package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Specialist
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The two counterspy focuses of Vox Populi the generator writes whole (`SpyMissions.json`, rows 93 and 97 of `CityEventChoices`, `IsCounterSpyMission`): Establish a Counter-Espionage Network and
 *  Double-Cross Foreign Agents, with the texts the generator writes, through the engine. The other three wait (Inspect City Defenses for the yields of its improvements, Hunt Down Heretics for
 *  the pressure, Deploy Checkpoints for the median modifiers and tourism). A spy that was found out pays its city's owner the identify row, one that was killed the kill row, and one that was both
 *  both (V:CvEspionageClasses.cpp:1594-1613: two independent ifs).
 */
@RunWith(GdxTestRunner::class)
class VpCounterspyFociTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCity: City
    private lateinit var bCity: City
    private lateinit var homeSpy: Spy
    private lateinit var raider: Spy
    private lateinit var network: SpyMission
    private lateinit var doubleCross: SpyMission
    private val addedMissions = ArrayList<String>()
    private var addedSpecialist = false

    private fun scaled(trigger: String, stat: String, amount: Int) =
        "Gain [$amount] [$stat] <upon [$trigger] a foreign spy in one of our cities> <in this city> <(modified by game speed)> <for every [Era scaler]>"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        game.gameInfo.turns = 10
        a = game.addCiv(isPlayer = true)
        b = game.addCiv()
        aCity = game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (tile in game.tileMap.values) { tile.setExplored(a, true); tile.setExplored(b, true) }
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        // the rows of CityEventChoices 93 and 97 as the generator writes them
        network = add("Establish a Counter-Espionage Network", "Agriculture", npReduction = 20, uniques = listOf(
            scaled("identifying", "Science", 50), scaled("identifying", "Culture", 50), scaled("killing", "Science", 150), scaled("killing", "Culture", 150),
            "[-1 Gold, -1 Science, -1 Culture] from every [Civil Servant] [in this city]"))
        doubleCross = add("Double-Cross Foreign Agents", "Agriculture", identifyReduction = 20, filter = "Capital", uniques = listOf(
            scaled("identifying", "Gold", 100), scaled("killing", "Gold", 150), "[-30] City Security [in this city]"))
        if (game.ruleset.specialists["Civil Servant"] == null) {
            game.ruleset.specialists["Civil Servant"] = Specialist().apply { name = "Civil Servant"; science = 3f; gold = 1f }
            addedSpecialist = true
        }
        a.tech.addTechnology("Agriculture")
        // the yields are multiplied by the era number of the owner, at least 1: in the first era that would not show, so the owner is in the Medieval era (2)
        a.tech.addTechnology("Theology")
        assertEquals(2, a.getAiEraNumber())
        homeSpy = a.espionageManager.addSpy()
        raider = b.espionageManager.addSpy()
    }

    @After
    fun tearDown() {
        for (name in addedMissions) game.ruleset.spyMissions.remove(name)
        if (addedSpecialist) game.ruleset.specialists.remove("Civil Servant")
    }

    private fun add(name: String, tech: String, uniques: List<String>, npReduction: Int = 0, identifyReduction: Int = 0, filter: String = "All"): SpyMission {
        val mission = SpyMission().apply {
            this.name = name; isFocus = true; requiredTech = tech; counterspyNetworkPointsReduction = npReduction
            identifyChanceReductionGlobal = identifyReduction; cityFilter = filter; this.uniques = ArrayList(uniques)
        }
        game.ruleset.spyMissions[name] = mission
        addedMissions += name
        return mission
    }

    private fun counter(focus: SpyMission) {
        homeSpy.moveTo(aCity)
        var turns = 0
        while (homeSpy.action == SpyAction.Moving && turns++ < 20) a.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, homeSpy.action)
        assertTrue(homeSpy.chooseFocus(focus))
    }

    /** a mission of [raider] in [aCity] with an identify chance and a kill chance of 0 or 100 and no cost */
    private fun raid(identify: Int, kill: Int) {
        raider.moveTo(aCity)
        var turns = 0
        while (raider.action == SpyAction.Moving && turns++ < 20) b.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, raider.action)
        val mission = SpyMission().apply { name = "Raid $identify/$kill"; networkPoints = 0; identifyChance = identify; killChance = kill; cityFilter = "All" }
        game.ruleset.spyMissions[mission.name] = mission
        addedMissions += mission.name
        assertNotNull(SpyMissionResolver.perform(raider, mission))
    }

    // region Establish a Counter-Espionage Network (row 93: 20 points of the rate, tech Agriculture, science and culture on identify and kill, Civil Servants lose 1)

    @Test
    fun `the network takes 20 network points a turn for each rank from the spies that gather intel`() {
        // a's spy gathers intel in b's city; b's spy counters spies there, first with no focus and then with the network. The security takes a percent of the rest, so it is taken away
        bCity.cityConstructions.addBuilding(game.createBuilding("[-1000] City Security [in this city]"))
        val intel = a.espionageManager.addSpy()
        intel.moveTo(bCity)
        var turns = 0
        while (intel.action == SpyAction.Moving && turns++ < 20) a.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, intel.action)
        val without = a.espionageManager.networkPointsPerTurn(intel, bCity)
        assertTrue("there are points to lose: $without", without > 20)

        b.tech.addTechnology("Agriculture")
        val defender = raider   // b's spy
        defender.moveTo(bCity)
        turns = 0
        while (defender.action == SpyAction.Moving && turns++ < 20) b.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, defender.action)
        assertEquals("a spy that counters without a focus takes nothing", without, a.espionageManager.networkPointsPerTurn(intel, bCity))
        assertTrue(defender.chooseFocus(network))

        assertEquals("with the security of the city at 0 the whole of the rate is the points", 0, bCity.espionage.getSecurity())
        assertEquals("20 points less for each rank of the counterspy", without - 20 * defender.getEffectiveRank(), a.espionageManager.networkPointsPerTurn(intel, bCity))
    }

    @Test
    fun `a Civil Servant of the city with the network gives one less of gold, science and culture`() {
        val before = aCity.cityStats.getStatsOfSpecialist("Civil Servant")
        counter(network)
        val after = aCity.cityStats.getStatsOfSpecialist("Civil Servant")
        val delta = Stats().apply { add(after); add(before * -1f) }
        assertEquals(-1f, delta.gold, 0f)
        assertEquals(-1f, delta.science, 0f)
        assertEquals(-1f, delta.culture, 0f)
        assertEquals("nothing else changes", 0f, delta.production + delta.food + delta.faith, 0f)
    }

    @Test
    fun `the Civil Servant of another city of the owner keeps its yield`() {
        val other = game.addCity(a, game.tileMap[3, 0])
        val before = other.cityStats.getStatsOfSpecialist("Civil Servant")
        counter(network)
        val after = other.cityStats.getStatsOfSpecialist("Civil Servant")
        assertEquals(before.gold, after.gold, 0f)
        assertEquals(before.science, after.science, 0f)
        assertEquals(before.culture, after.culture, 0f)
    }

    // endregion

    // region the yields on a spy that was found out or killed (V:CvEspionageClasses.cpp:1594-1613)

    @Test
    fun `a spy that was identified and lived pays the identify row of the network, science and culture`() {
        counter(network)
        val before = reserves()
        raid(identify = 100, kill = 0)
        val paid = reserves() - before
        assertEquals("science 50 scaled by the era and the speed", expectedGain(50), paid.science)
        assertEquals("culture 50", expectedGain(50), paid.culture)
        assertEquals("and no gold", 0, paid.gold)
    }

    @Test
    fun `a spy that was killed and not identified pays the kill row of the network`() {
        counter(network)
        val before = reserves()
        raid(identify = 0, kill = 100)
        val paid = reserves() - before
        assertEquals("science 150", expectedGain(150), paid.science)
        assertEquals("culture 150", expectedGain(150), paid.culture)
    }

    @Test
    fun `a spy that was killed and identified pays both rows`() {
        counter(network)
        val before = reserves()
        raid(identify = 100, kill = 100)
        val paid = reserves() - before
        assertEquals("50 + 150 of culture: two independent rows", expectedGain(50) + expectedGain(150), paid.culture)
        assertEquals("50 + 150 of science", expectedGain(50) + expectedGain(150), paid.science)
    }

    @Test
    fun `a spy nobody noticed pays nothing`() {
        counter(network)
        val before = reserves()
        raid(identify = 0, kill = 0)
        assertEquals(before, reserves())
    }

    @Test
    fun `without the focus a found out spy pays the owner nothing`() {
        val before = reserves()
        raid(identify = 100, kill = 0)
        assertEquals(before, reserves())
    }

    // endregion

    @Test
    fun `Burgle the National Treasury is refused in a city whose counterspy has chosen the network and allowed otherwise`() {
        // the mission as the generator writes it: blocked by the focus of the name its row's linker has (COUNTERSPY_NETWORK)
        val burgle = SpyMission().apply {
            name = "Burgle the National Treasury"; networkPoints = 0; cityFilter = "Capital"; duration = 15; group = 4
            blockedBy = arrayListOf("Establish a Counter-Espionage Network")
            uniques = arrayListOf("Steal [35]% of the treasury of the target")
        }
        game.ruleset.spyMissions[burgle.name] = burgle
        addedMissions += burgle.name
        val thief = a.espionageManager.addSpy()
        thief.moveTo(bCity)
        var turns = 0
        while (thief.action == SpyAction.Moving && turns++ < 20) a.espionageManager.endTurn()
        assertNull("no counterspy, no block", SpyMissionResolver.unavailableReason(thief, burgle))

        b.tech.addTechnology("Agriculture")
        raider.moveTo(bCity)
        turns = 0
        while (raider.action == SpyAction.Moving && turns++ < 20) b.espionageManager.endTurn()
        assertEquals(SpyAction.CounterIntelligence, raider.action)
        assertNull("a counterspy with no focus chosen blocks nothing", SpyMissionResolver.unavailableReason(thief, burgle))
        assertTrue(raider.chooseFocus(network))
        assertEquals("Blocked by the focus [${network.name}]", SpyMissionResolver.unavailableReason(thief, burgle))
    }

    // region Double-Cross Foreign Agents (row 97: identify chance 20 lower for the spies of the owner, security 30 lower, capital only, gold 100 and 150)

    @Test
    fun `the double-cross can be chosen in the capital only`() {
        val other = game.addCity(a, game.tileMap[3, 0])
        val spy = a.espionageManager.addSpy()
        spy.moveTo(other)
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) a.espionageManager.endTurn()
        assertNotNull("in a city that is no capital", SpyMissionResolver.unavailableFocusReason(spy, doubleCross))
        assertNull("in the capital", run {
            val capitalSpy = a.espionageManager.addSpy()
            capitalSpy.moveTo(aCity)
            turns = 0
            while (capitalSpy.action == SpyAction.Moving && turns++ < 20) a.espionageManager.endTurn()
            SpyMissionResolver.unavailableFocusReason(capitalSpy, doubleCross)
        })
    }

    @Test
    fun `the double-cross lowers the security of its city by 30 and the identify chance of the owner's spies by 20`() {
        // the security is kept between 0 and its maximum (V:CvCity.cpp:26085), so a city that has little would hide the 30: give it 50 more first
        aCity.cityConstructions.addBuilding(game.createBuilding("[+50] City Security [in this city]"))
        val securityBefore = aCity.espionage.getSecurity()
        assertTrue("room to fall: $securityBefore", securityBefore >= 30)
        val reductionBefore = a.espionageManager.getIdentifyChanceReduction()
        counter(doubleCross)
        assertEquals(securityBefore - 30, aCity.espionage.getSecurity())
        assertEquals(reductionBefore + 20, a.espionageManager.getIdentifyChanceReduction())
    }

    @Test
    fun `a spy found out in the double-crossing city pays 100 gold`() {
        counter(doubleCross)
        val before = reserves()
        raid(identify = 100, kill = 0)
        assertEquals(expectedGain(100), (reserves() - before).gold)
    }

    @Test
    fun `a spy killed in the double-crossing city pays 150 gold`() {
        counter(doubleCross)
        val before = reserves()
        raid(identify = 0, kill = 100)
        assertEquals(expectedGain(150), (reserves() - before).gold)
    }

    // endregion

    /** What the yields of an instant gain end up in: the stored culture, the science that waits for a technology to research (nothing is being researched) and the gold */
    private data class Reserves(val culture: Int, val science: Int, val gold: Int) {
        operator fun minus(other: Reserves) = Reserves(culture - other.culture, science - other.science, gold - other.gold)
    }

    private fun reserves() = Reserves(a.policies.storedCulture, a.tech.getOverflowScience(), a.gold)

    /**
     *  The generator's two modifiers on [base]: `<(modified by game speed)>` and `<for every [Era scaler]>`, the latter being `max(1, era number)` of the owner (Countables.EraScaler, which
     *  `V:CvPlayer::doInstantYield` multiplies by). Both are read from the engine, so a modifier that is dropped changes the number
     */
    private fun expectedGain(base: Int): Int {
        val speed = game.gameInfo.speed.modifier
        val era = a.getAiEraNumber().coerceAtLeast(1)
        return (base * speed).toInt() * era
    }
}
