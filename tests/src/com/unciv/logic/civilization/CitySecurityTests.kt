package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The security of a city (`CvCity::CalculateCitySecurity`, V:CvCity.cpp:25992-26085): 20, less 2 for each citizen, plus what its buildings give (`GetSpySecurityModifier`, and
 *  population x `GetSpySecurityModifierPerXPop` / 360), plus what the owner's policies and wonders give for every city (`CvPlayer::GetSpySecurityModifier`, V:CvPlayer.cpp:16224), between 0
 *  and 100. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Only with [ModConstants.espionageLikeVp]. The uniques are the shapes the generator writes for those columns
 *  (`phaseE/vp-influence-plan.md`, 4.6): a flat `[+n] City Security [in this city]` from a building, `[in all cities]` from a policy or a wonder, a per-citizen term as
 *  `<for every [k] [[Population] in [in this city] Cities]>` with k = 360 / the row's value, and a per-building term as `<for every [[B] Buildings in this city]>`.
 *  [a] is the civilization whose city's security is read, [b] a civilization whose spy gathers intel there.
 */
@RunWith(GdxTestRunner::class)
class CitySecurityTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var city: City
    private lateinit var other: City
    private var difficultyBefore = 0

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        a = game.addCiv()
        b = game.addCiv()
        city = game.addCity(a, game.tileMap[0, 0], replacePalace = true, initialPopulation = 8)
        other = game.addCity(a, game.tileMap[0, 3], initialPopulation = 4)
        game.addCity(b, game.tileMap[0, 6], replacePalace = true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants()
        difficultyBefore = game.gameInfo.getDifficulty().spySecurityModifier
        game.gameInfo.getDifficulty().spySecurityModifier = 0
        a.getDifficulty().spySecurityModifier = 0
    }

    @After
    fun tearDown() {
        game.gameInfo.getDifficulty().spySecurityModifier = difficultyBefore
    }

    private fun add(target: City, vararg uniques: String) {
        val building = game.createBuilding(*uniques)
        target.cityConstructions.addBuilding(building)
    }

    @Test
    fun `without a source the security is 20 less 2 for each citizen`() {
        assertEquals("20 - 2 x 8", 4, city.espionage.getSecurity())
        assertEquals("20 - 2 x 4", 12, other.espionage.getSecurity())
    }

    @Test
    fun `a flat building term counts for the city that has the building only`() {
        add(city, "[+15] City Security [in this city]")
        assertEquals(4 + 15, city.espionage.getSecurity())
        assertEquals("the other city of the owner has none", 12, other.espionage.getSecurity())
    }

    @Test
    fun `a building with a flat and a per-citizen term gives 15 plus the citizens times 90 over 360`() {
        // Constabulary-like: SpySecurityModifier 15 and SpySecurityModifierPerXPop 90 (floor(8 x 90 / 360) = 2 at 8 citizens)
        add(city, "[+15] City Security [in this city]", "[+1] City Security [in this city] <for every [4] [[Population] in [in this city] Cities]>")
        assertEquals("4 + 15 + floor(8 x 90 / 360)", 4 + 15 + 2, city.espionage.getSecurity())
    }

    @Test
    fun `the per-citizen term floors as the original's integer division does`() {
        add(city, "[+1] City Security [in this city] <for every [4] [[Population] in [in this city] Cities]>")
        city.population.setPopulation(7)
        assertEquals("20 - 14 + floor(7 x 90 / 360) = 6 + floor(1.75)", 6 + 1, city.espionage.getSecurity())
        city.population.setPopulation(5)
        assertEquals("20 - 10 + floor(5 x 90 / 360) = 10 + floor(1.25)", 10 + 1, city.espionage.getSecurity())
        city.population.setPopulation(3)
        assertEquals("20 - 6 + floor(3 x 90 / 360) = 14 + floor(0.75)", 14, city.espionage.getSecurity())
    }

    @Test
    fun `a term of every city of the owner, from a wonder or a policy, counts in each of them`() {
        add(city, "[+10] City Security [in all cities]")
        assertEquals(4 + 10, city.espionage.getSecurity())
        assertEquals("a wonder in one city is felt in the other, as the player's modifier is (CvPlayer::GetSpySecurityModifier)", 12 + 10, other.espionage.getSecurity())
    }

    @Test
    fun `a policy adds its security to every city`() {
        val policy = game.createPolicy("[+12] City Security [in all cities]")
        val branch = game.createPolicyBranch(policy = policy)
        a.policies.freePolicies = 2   // the branch and its member: no culture is spent
        a.policies.adopt(branch)
        a.policies.adopt(policy)
        assertEquals(4 + 12, city.espionage.getSecurity())
        assertEquals(12 + 12, other.espionage.getSecurity())
    }

    @Test
    fun `a term for each building of a kind counts the buildings of that kind in the city`() {
        // Police State: +10 for each Police Station in the city
        val station = game.createBuilding()
        station.name = "Police Station"
        game.ruleset.buildings[station.name] = station
        add(city, "[+10] City Security [in this city] <for every [[Police Station] Buildings in this city]>")
        assertEquals("no station: nothing", 4, city.espionage.getSecurity())
        city.cityConstructions.addBuilding(station)
        assertEquals("one station: +10", 4 + 10, city.espionage.getSecurity())
    }

    @Test
    fun `security is kept between 0 and 100`() {
        add(city, "[+500] City Security [in this city]")
        assertEquals(100, city.espionage.getSecurity())
        add(other, "[-500] City Security [in this city]")
        assertEquals(0, other.espionage.getSecurity())
    }

    @Test
    fun `a city with 100 security gives an enemy spy gathering intel no points`() {
        add(city, "[+500] City Security [in this city]")
        val spy: Spy = b.espionageManager.addSpy()
        spy.moveTo(city)
        while (spy.action == SpyAction.Moving) b.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, spy.action)
        assertEquals(0, b.espionageManager.networkPointsPerTurn(spy, city))

        val second: Spy = b.espionageManager.addSpy()
        second.moveTo(other)
        while (second.action == SpyAction.Moving) b.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, second.action)
        assertEquals("a spy in the city with 12 security gathers 50 less its 12 percent: 50 x (100 - 12) / 100",
            44, b.espionageManager.networkPointsPerTurn(second, other))
    }
}
