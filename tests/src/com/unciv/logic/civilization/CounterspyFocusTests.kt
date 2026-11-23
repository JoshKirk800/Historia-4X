package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The counterspy focus: what a spy that counters spies in an own city chooses to do there (`CvPlayerEspionage::TriggerSpyFocusSetup`, V:CvEspionageClasses.cpp:1478-1553;
 *  `CvCity::DoEventChoice`, V:CvCity.cpp:6519-6557; `CvCityEspionage::HasCounterSpy`/`GetCounterSpyFocus`, 7289-7316; `CalcNetworkPointsPerTurn`, 3262-3379;
 *  `CvPlayerEspionage::GetNumTurnsSpyMovementBlocked`, 2829-2842; `CvPlayer::GetSpyIdentificationChance`, V:CvPlayer.cpp:23342-23358). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 *  Only with [ModConstants.espionageLikeVp]. [a] is a human civilization with a city, [b] an AI civilization 5 tiles away whose city its spy gathers intel in.
 */
@RunWith(GdxTestRunner::class)
class CounterspyFocusTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCity: City
    private lateinit var bCity: City
    private lateinit var homeSpy: Spy
    private val addedMissions = ArrayList<String>()
    private var difficultyBefore = 0

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
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        for (tile in game.tileMap.values) tile.setExplored(b, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        difficultyBefore = game.gameInfo.getDifficulty().spySecurityModifier
        homeSpy = a.espionageManager.addSpy()
    }

    @After
    fun tearDown() {
        game.gameInfo.getDifficulty().spySecurityModifier = difficultyBefore
        for (name in addedMissions) game.ruleset.spyMissions.remove(name)
    }

    private fun focus(name: String, vararg uniques: String, npReduction: Int = 0, identifyReduction: Int = 0, tech: String? = null, filter: String = "All"): SpyMission {
        val mission = SpyMission().apply {
            this.name = name
            isFocus = true
            counterspyNetworkPointsReduction = npReduction
            identifyChanceReductionGlobal = identifyReduction
            requiredTech = tech
            cityFilter = filter
            this.uniques = ArrayList(uniques.toList())
        }
        game.ruleset.spyMissions[name] = mission
        addedMissions += name
        return mission
    }

    private fun arrive(spy: Spy, city: City) {
        spy.moveTo(city)
        var turns = 0
        while (spy.action == SpyAction.Moving && turns++ < 20) spy.civInfo.espionageManager.endTurn()
        assertTrue(turns < 20)
    }

    /** [homeSpy] counters spies in [aCity] and has chosen [chosen] */
    private fun counter(chosen: SpyMission? = null) {
        arrive(homeSpy, aCity)
        assertEquals(SpyAction.CounterIntelligence, homeSpy.action)
        if (chosen != null) assertTrue(homeSpy.chooseFocus(chosen))
    }

    private fun strike(city: City): Int {
        var total = 0
        city.forEachMatchingUnique(UniqueType.CityRangeStrikeModifier, GameContext(civInfo = city.civ, city = city)) { total += it.params[0].toInt() }
        return total
    }

    @Test
    fun `the uniques of the focus act on the city only while its spy counters spies there`() {
        val sentinel = focus("Sentinel", "[+30]% City ranged strike strength")
        assertEquals("no spy, no focus", 0, strike(aCity))

        arrive(homeSpy, aCity)
        assertEquals("arrived, none chosen", 0, strike(aCity))
        assertTrue(homeSpy.chooseFocus(sentinel))
        assertEquals(30, strike(aCity))
        assertEquals("another city of the owner is not affected", 0, strike(game.addCity(a, game.tileMap[3, 0])))
        assertEquals("the other civilization's city is not affected", 0, strike(bCity))
        assertSame(sentinel, aCity.espionage.getFocus())

        homeSpy.moveTo(null)
        assertEquals("the spy left", 0, strike(aCity))
        assertNull(aCity.espionage.getFocus())
        assertNull("and the focus is gone with it", homeSpy.focus)
    }

    @Test
    fun `a focus changed by choosing another one takes effect and leaves nothing of the first`() {
        val sentinel = focus("Sentinel", "[+30]% City ranged strike strength")
        val watch = focus("Watch", "[+7]% City ranged strike strength")
        counter(sentinel)
        assertEquals(30, strike(aCity))
        game.gameInfo.turns += 10
        assertTrue(homeSpy.chooseFocus(watch))
        assertEquals("the cache follows the choice at once", 7, strike(aCity))
        val changedTurn = homeSpy.focusChangedTurn
        game.gameInfo.turns += 3
        assertTrue(homeSpy.chooseFocus(watch))
        assertEquals("choosing the same focus again changes nothing, not even the wait before the spy moves", changedTurn, homeSpy.focusChangedTurn)
    }

    @Test
    fun `a spy that is killed, or whose city is lost, no longer counters anything`() {
        val sentinel = focus("Sentinel", "[+30]% City ranged strike strength")
        counter(sentinel)
        assertEquals(30, strike(aCity))
        homeSpy.killSpy()
        assertEquals(0, strike(aCity))
        assertNull(aCity.espionage.counterspy())

        homeSpy = a.espionageManager.addSpy()
        counter(sentinel)
        assertEquals(30, strike(aCity))
        aCity.espionage.removeAllPresentSpies(com.unciv.logic.city.managers.SpyFleeReason.CityCaptured)
        assertEquals("every way a spy leaves bumps the version, so the city cannot keep a stale focus", 0, strike(aCity))
    }

    @Test
    fun `nothing is read without the espionage of Vox Populi`() {
        val sentinel = focus("Sentinel", "[+30]% City ranged strike strength", npReduction = 40)
        counter(sentinel)
        assertEquals(30, strike(aCity))
        game.ruleset.modOptions.constants.espionageLikeVp = false
        homeSpy.let { a.espionageManager.focusVersion++ }
        assertNull(aCity.espionage.counterspy())
        assertEquals(0, strike(aCity))
    }

    @Test
    fun `the focus of a city-state or a city without a spy is none`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val csCity = game.addCity(cityState, game.tileMap[5, 0], replacePalace = true)
        assertNull(csCity.espionage.counterspy())
        assertNull(csCity.espionage.getFocus())
        assertNull(bCity.espionage.getFocus())
    }

    @Test
    fun `a human spy waits for its focus and cannot move until it is chosen, then for the turns after`() {
        val sentinel = focus("Sentinel", "[+30]% City ranged strike strength")
        arrive(homeSpy, aCity)
        assertTrue(homeSpy.focusPending)
        assertFalse("it cannot go to another city while the choice waits", homeSpy.canMoveTo(bCity))
        assertTrue("but it stays where it is", homeSpy.canMoveTo(aCity))

        assertTrue(homeSpy.chooseFocus(sentinel))
        assertFalse(homeSpy.focusPending)
        assertFalse("chosen on turn 10: blocked for 5 turns", homeSpy.canMoveTo(bCity))
        assertEquals(5, homeSpy.getMovementBlockedTurns())
        game.gameInfo.turns = 13
        assertEquals(2, homeSpy.getMovementBlockedTurns())
        assertFalse(homeSpy.canMoveTo(bCity))
        game.gameInfo.turns = 15
        assertEquals(0, homeSpy.getMovementBlockedTurns())
        assertTrue(homeSpy.canMoveTo(bCity))
    }

    @Test
    fun `an AI spy does not wait for a choice`() {
        val ai = b.espionageManager.addSpy()
        arrive(ai, bCity)
        assertFalse(ai.focusPending)
        assertNull(ai.focus)
    }

    @Test
    fun `a focus needs its technology, its city and a spy countering spies at home`() {
        val tech = game.ruleset.technologies.keys.first { it !in a.tech.techsResearched }
        val needsTech = focus("Needs tech", tech = tech)
        val capitalOnly = focus("Capital only", filter = "Capital")
        arrive(homeSpy, aCity)
        assertTrue(SpyMissionResolver.unavailableFocusReason(homeSpy, needsTech)!!.startsWith("Needs the technology"))
        assertFalse(homeSpy.chooseFocus(needsTech))
        assertNull(homeSpy.focus)
        a.tech.addTechnology(tech)
        assertNull(SpyMissionResolver.unavailableFocusReason(homeSpy, needsTech))

        val second = a.espionageManager.addSpy()
        val outerCity = game.addCity(a, game.tileMap[3, 0])
        arrive(second, outerCity)
        assertTrue("not a capital", SpyMissionResolver.unavailableFocusReason(second, capitalOnly)!!.startsWith("Only in [Capital]"))
        assertNull("the capital", SpyMissionResolver.unavailableFocusReason(homeSpy, capitalOnly))

        val abroad = a.espionageManager.addSpy()
        arrive(abroad, bCity)
        assertEquals(SpyAction.GatheringIntel, abroad.action)
        assertTrue(SpyMissionResolver.unavailableFocusReason(abroad, capitalOnly)!!.startsWith("The spy is not countering spies"))
    }

    @Test
    fun `a focus is never a mission and a mission is never a focus`() {
        val sentinel = focus("Sentinel")
        val steal = SpyMission().apply { name = "Plain mission"; cityFilter = "All" }
        game.ruleset.spyMissions[steal.name] = steal
        addedMissions += steal.name

        val abroad = a.espionageManager.addSpy()
        arrive(abroad, bCity)
        assertTrue(SpyMissionResolver.unavailableReason(abroad, sentinel)!!.startsWith("This is a focus"))
        assertFalse(sentinel in SpyMissionResolver.availableMissions(abroad))
        assertTrue(steal in SpyMissionResolver.availableMissions(abroad))

        arrive(homeSpy, aCity)
        assertEquals(listOf(sentinel), SpyMissionResolver.availableFoci(homeSpy))
        assertTrue(SpyMissionResolver.unavailableFocusReason(homeSpy, steal)!!.startsWith("This is no focus"))
    }

    @Test
    fun `a spy gathering intel loses the points of the focus for each rank of the counterspy before the points are kept at 0`() {
        // 100 points before anything is taken: only the base counts (no rank, influence, technology or policy points), so the arithmetic below is exact
        game.ruleset.modOptions.constants.espionage = game.ruleset.modOptions.constants.espionage.copy(
            networkPointsBase = 100, networkPointsPerSpyRank = 0, networkPointsPerInfluenceLevel = 0, networkPointsPerTechBehind = 0)
        val spyOnB = a.espionageManager.addSpy()
        arrive(spyOnB, bCity)
        val raw = 100
        // the security of b's city takes its percent of what is left, after the focus has taken its points (CalcNetworkPointsPerTurn: the reduction, max 0, then the security)
        val security = bCity.espionage.getSecurity()
        val perSecurity = game.ruleset.modOptions.constants.espionage.networkPointsReductionPerSecurity
        fun afterSecurity(points: Int) = if (points > 0 && security > 0) points * (100 - security * perSecurity / 100) / 100 else maxOf(points, 0)
        val rate = a.espionageManager.networkPointsPerTurn(spyOnB, bCity)
        assertEquals("no focus yet: the base less the security", afterSecurity(raw), rate)
        assertTrue("there are points to lose", rate > 0)

        // b counters spies in its own city: a spy of b, rank 1
        val counterspy = b.espionageManager.addSpy()
        arrive(counterspy, bCity)
        val trap = focus("Trap", npReduction = 20)
        assertTrue(counterspy.chooseFocus(trap))
        assertSame(counterspy, bCity.espionage.counterspy())
        assertEquals("rank 1: 20 points less, then the security", afterSecurity(raw - 20), a.espionageManager.networkPointsPerTurn(spyOnB, bCity))
        assertTrue("and it is less than without the focus", a.espionageManager.networkPointsPerTurn(spyOnB, bCity) < rate)

        counterspy.levelUpSpy(1)
        assertEquals("rank 2: 40 points less", afterSecurity(raw - 40), a.espionageManager.networkPointsPerTurn(spyOnB, bCity))

        trap.counterspyNetworkPointsReduction = 100000
        assertEquals("never under 0", 0, a.espionageManager.networkPointsPerTurn(spyOnB, bCity))
    }

    @Test
    fun `a spy that only schmoozes is not touched by the reduction`() {
        val counterspy = b.espionageManager.addSpy()
        arrive(counterspy, bCity)
        assertTrue(counterspy.chooseFocus(focus("Trap", npReduction = 20)))
        val schmoozer = a.espionageManager.addSpy()
        schmoozer.moveTo(bCity, asDiplomat = true)
        while (schmoozer.action == SpyAction.Moving) a.espionageManager.endTurn()
        val withFocus = a.espionageManager.networkPointsPerTurn(schmoozer, bCity)
        counterspy.moveTo(null)
        assertEquals(a.espionageManager.networkPointsPerTurn(schmoozer, bCity), withFocus)
    }

    @Test
    fun `the focuses of our counterspies lower the chance to be identified, in every city, and add up`() {
        assertEquals(0, a.espionageManager.getIdentifyChanceReduction())
        val first = focus("Cover", identifyReduction = 25)
        counter(first)
        assertEquals(25, a.espionageManager.getIdentifyChanceReduction())

        val outerCity = game.addCity(a, game.tileMap[3, 0])
        val second = a.espionageManager.addSpy()
        arrive(second, outerCity)
        assertTrue(second.chooseFocus(focus("Double", identifyReduction = 10)))
        assertEquals("the foci of different cities add up", 35, a.espionageManager.getIdentifyChanceReduction())

        homeSpy.moveTo(null)
        assertEquals("a spy that left counters nothing", 10, a.espionageManager.getIdentifyChanceReduction())
        assertEquals("the other civilization is not helped", 0, b.espionageManager.getIdentifyChanceReduction())
    }

    @Test
    fun `a focus stops the missions that list it in the city that has it`() {
        val shield = focus("Shield")
        val mission = SpyMission().apply { name = "Burgle"; cityFilter = "All"; blockedBy = arrayListOf("Shield") }
        game.ruleset.spyMissions[mission.name] = mission
        addedMissions += mission.name

        val spyOnB = a.espionageManager.addSpy()
        arrive(spyOnB, bCity)
        assertNull(SpyMissionResolver.unavailableReason(spyOnB, mission))

        val counterspy = b.espionageManager.addSpy()
        arrive(counterspy, bCity)
        assertTrue(counterspy.chooseFocus(shield))
        assertEquals("Blocked by the focus [Shield]", SpyMissionResolver.unavailableReason(spyOnB, mission))
        assertNull("a mission that does not list it is not stopped", SpyMissionResolver.unavailableReason(spyOnB, mission.also { it.blockedBy.clear() }))
    }

    @Test
    fun `City Security from the focus raises the security of the city and the Counterspy rank counts the spy`() {
        val before = aCity.espionage.getSecurity()
        val otherBefore = bCity.espionage.getSecurity()
        val guard = focus("Guard", "[+15] City Security [in all cities]")
        counter(guard)
        assertEquals(before + 15, aCity.espionage.getSecurity())
        assertEquals("another civilization's city does not get it", otherBefore, bCity.espionage.getSecurity())

        assertEquals(1, UniqueTypeCounter.rank(aCity))
        homeSpy.levelUpSpy(1)
        assertEquals(2, UniqueTypeCounter.rank(aCity))
        homeSpy.moveTo(null)
        assertEquals(0, UniqueTypeCounter.rank(aCity))
    }

    @Test
    fun `a spy keeps its focus when the game is cloned`() {
        val sentinel = focus("Sentinel")
        counter(sentinel)
        game.gameInfo.turns += 1
        val copy = a.espionageManager.clone()
        val cloned = copy.spyList.first { it.name == homeSpy.name }
        assertEquals("Sentinel", cloned.focus)
        assertEquals(homeSpy.focusChangedTurn, cloned.focusChangedTurn)
        assertEquals(homeSpy.focusPending, cloned.focusPending)
        assertNotNull(cloned.focus)
    }

    /** The countable `Counterspy rank` as a unique's condition would read it */
    private object UniqueTypeCounter {
        fun rank(city: City): Int =
            com.unciv.models.ruleset.unique.Countables.CounterspyRank.eval("Counterspy rank", GameContext(civInfo = city.civ, city = city)) ?: -1
    }
}
