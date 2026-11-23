package com.unciv.logic.civilization

import com.unciv.json.json
import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.logic.civilization.managers.SpyMissionResult
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.SpyMission
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's spy missions (`CvPlayerEspionage::ProcessSpyMissionResult`, V:CvEspionageClasses.cpp:1556-1785; `CvCity::DoEventChoice`, V:CvCity.cpp:6490-6640; `CvCity::CanDoEventChoice`,
 *  V:CvCity.cpp:4590-4770; `CvCityEspionage::DoMission`, V:CvEspionageClasses.cpp:7239-7252; `CvInfos.cpp: CvModEventCityChoiceInfo::GetNetworkPointsNeededScaled`, 11508-11517). V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The mission here is Steal a Technology as the generator writes it: 2000 points at standard speed scaled by the speed, identify 5, kill 95, capital only.
 */
@RunWith(GdxTestRunner::class)
class SpyMissionTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var bCity: City
    private lateinit var spy: Spy
    private lateinit var steal: SpyMission

    private fun mission(name: String = "Steal a Technology", points: Int = 2000, identify: Int = 0, kill: Int = 0, vararg uniques: String) =
        SpyMission().apply {
            this.name = name
            networkPoints = points
            identifyChance = identify
            killChance = kill
            cityFilter = "Capital"
            this.uniques = ArrayList(uniques.toList())
        }

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.gameInfo.gameParameters.espionageEnabled = true
        game.makeHexagonalMap(8)
        game.gameInfo.turns = 10   // 0 is "never" for the turn of the last mission
        a = game.addCiv(isPlayer = true)
        b = game.addCiv()
        game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        steal = mission(uniques = arrayOf("Steal [1] random technology/technologies the target knows and we can research"))
        game.ruleset.spyMissions[steal.name] = steal
        spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, spy.action)
        b.tech.addTechnology("Pottery")
        spy.addNetworkPoints(2000)
    }

    @Test
    fun `the cost scales with the speed in whole numbers`() {
        val scaled = mission().apply { networkPointsScaledBySpeed = true }
        assertEquals(2000, scaled.getNetworkPointsNeeded(Speed().apply { productionCostModifier = 1f }))
        assertEquals("2000 x 67 / 100", 1340, scaled.getNetworkPointsNeeded(Speed().apply { productionCostModifier = 0.67f }))
        assertEquals(3000, scaled.getNetworkPointsNeeded(Speed().apply { productionCostModifier = 1.5f }))
        assertEquals("without the scaling it is the number", 2000, mission().getNetworkPointsNeeded(Speed().apply { productionCostModifier = 0.67f }))
    }

    @Test
    fun `a spy with the points in the capital of a civilization with a technology it lacks can do the mission`() {
        assertNull(SpyMissionResolver.unavailableReason(spy, steal))
        assertEquals(listOf(steal), SpyMissionResolver.availableMissions(spy))
    }

    @Test
    fun `the mission is refused for each condition it has`() {
        spy.spendNetworkPoints(1)
        assertTrue("the points: 1999 of 2000", SpyMissionResolver.unavailableReason(spy, steal)!!.startsWith("Needs [2000] Network Points"))
        spy.addNetworkPoints(1)

        steal.requiredSpyRank = 2
        assertTrue(SpyMissionResolver.unavailableReason(spy, steal)!!.startsWith("Needs a spy of rank [2]"))
        steal.requiredSpyRank = 1

        steal.requiredTech = "Writing"
        assertTrue(SpyMissionResolver.unavailableReason(spy, steal)!!.startsWith("Needs the technology"))
        steal.requiredTech = null

        a.tech.addTechnology("Pottery")
        assertEquals("nothing is left to steal", "There is no technology to steal", SpyMissionResolver.unavailableReason(spy, steal))
    }

    @Test
    fun `a mission for the capital is refused in another city`() {
        val second = game.addCity(b, game.tileMap[0, 6])
        spy.moveTo(second)
        repeat(2) { a.espionageManager.endTurn() }
        spy.addNetworkPoints(2000)
        assertEquals(SpyAction.GatheringIntel, spy.action)
        assertTrue(SpyMissionResolver.unavailableReason(spy, steal)!!.startsWith("Only in [Capital] cities"))
    }

    @Test
    fun `a spy that is not gathering intel or not in a foreign major does no mission`() {
        spy.moveTo(null)
        assertEquals("The spy is in the hideout", SpyMissionResolver.unavailableReason(spy, steal))
        spy.moveTo(a.getCapital()!!)
        repeat(2) { a.espionageManager.endTurn() }
        assertEquals(SpyAction.CounterIntelligence, spy.action)
        assertEquals("The spy is not gathering intel", SpyMissionResolver.unavailableReason(spy, steal))
    }

    @Test
    fun `without the switch there are no missions`() {
        game.ruleset.modOptions.constants.espionageLikeVp = false
        assertEquals("Missions need the espionage of Vox Populi", SpyMissionResolver.unavailableReason(spy, steal))
        assertNull(SpyMissionResolver.perform(spy, steal))
    }

    @Test
    fun `an undetected spy steals one technology, pays the points and waits for the next mission`() {
        val result = SpyMissionResolver.perform(spy, steal)
        assertEquals(SpyMissionResult.Undetected, result)
        assertTrue("the technology the civilization had", a.tech.isResearched("Pottery"))
        assertEquals("the points are paid", 0, spy.networkPoints)
        assertEquals("the most ever stored stays", 2000, spy.maxNetworkPoints)
        assertTrue(spy.isAlive())
        assertEquals(game.gameInfo.turns, spy.lastMissionTurn)
        val wait = (15 * game.gameInfo.speed.modifier).toInt()
        assertEquals("15 turns times the speed (the turns between city-state elections)", wait, a.espionageManager.getMissionCooldownLeft(spy))

        spy.addNetworkPoints(2000)
        b.tech.addTechnology("Animal Husbandry")
        assertTrue(SpyMissionResolver.unavailableReason(spy, steal)!!.startsWith("The spy must wait"))
        game.gameInfo.turns += wait - 1
        assertTrue("one turn short", SpyMissionResolver.unavailableReason(spy, steal)!!.startsWith("The spy must wait [1]"))
        game.gameInfo.turns += 1
        assertNull(SpyMissionResolver.unavailableReason(spy, steal))
    }

    @Test
    fun `a killed spy has paid, still steals the technology and is dead`() {
        steal.killChance = 100
        val result = SpyMissionResolver.perform(spy, steal)
        assertEquals("not identified: the identify chance is 0", SpyMissionResult.KilledUnidentified, result)
        assertEquals(SpyAction.Dead, spy.action)
        assertTrue("the effects of the mission happen whether or not the spy lives (V:CvCity.cpp:6616-6640)", a.tech.isResearched("Pottery"))
        assertEquals(0, spy.networkPoints)
        assertEquals("a dead spy has nothing in the city", 0, spy.maxNetworkPoints)
    }

    @Test
    fun `an identified and killed spy is the result Killed`() {
        steal.killChance = 100
        steal.identifyChance = 100
        assertEquals(SpyMissionResult.Killed, SpyMissionResolver.perform(spy, steal))
        assertEquals(SpyAction.Dead, spy.action)
    }

    @Test
    fun `an identified spy that lives is remembered by the victim`() {
        steal.identifyChance = 100
        assertEquals(SpyMissionResult.Identified, SpyMissionResolver.perform(spy, steal))
        assertTrue(spy.isAlive())
        val memory = b.ai.diplomacy.memoryOf(a)
        assertEquals(1, memory.numTimesRobbed)
        assertEquals(game.gameInfo.turns, memory.turnLastSpyCaught)
        assertTrue(b.getDiplomacyManager(a)!!.hasFlag(DiplomacyFlags.DiscoveredSpiesInOurCities))
        assertTrue("the spy's owner is told that it was identified", a.notifications.any { "was identified by [${b.civName}]" in it.text })
    }

    @Test
    fun `an undetected spy leaves no memory`() {
        SpyMissionResolver.perform(spy, steal)
        assertEquals(0, b.ai.diplomacy.memoryOf(a).numTimesRobbed)
        assertFalse(b.getDiplomacyManager(a)!!.hasFlag(DiplomacyFlags.DiscoveredSpiesInOurCities))
        assertTrue(a.notifications.any { "stole the Technology [Pottery]" in it.text })
    }

    @Test
    fun `the missions done and the times the city was robbed are counted`() {
        SpyMissionResolver.perform(spy, steal)
        assertEquals(1, a.espionageManager.spyActionsDone[b.civID])
        assertEquals(1, b.espionageManager.timesCityRobbed[bCity.id])
        val copy = a.espionageManager.clone()
        assertEquals(1, copy.spyActionsDone[b.civID])
        assertEquals(spy.lastMissionTurn, copy.spyList.single().lastMissionTurn)
    }

    @Test
    fun `a mission steals as many technologies as it says, as far as there are`() {
        val two = mission("Steal two", uniques = arrayOf("Steal [2] random technology/technologies the target knows and we can research"))
        game.ruleset.spyMissions[two.name] = two
        assertTrue("one technology only: the mission is refused", SpyMissionResolver.unavailableReason(spy, two)!!.startsWith("There is no technology"))
        b.tech.addTechnology("Animal Husbandry")
        b.tech.addTechnology("Mining")
        assertNull(SpyMissionResolver.unavailableReason(spy, two))
        SpyMissionResolver.perform(spy, two)
        assertEquals(2, listOf("Pottery", "Animal Husbandry", "Mining").count { a.tech.isResearched(it) })
    }

    @Test
    fun `the spy of an AI civilization thinks again about its post after a mission`() {
        val ai = game.addCiv()
        game.addCity(ai, game.tileMap[5, 0], replacePalace = true)
        ai.diplomacyFunctions.makeCivilizationsMeet(b)
        for (tile in game.tileMap.values) tile.setExplored(ai, true)
        val aiSpy = ai.espionageManager.addSpy()
        aiSpy.moveTo(bCity)
        ai.espionageManager.endTurn()
        aiSpy.addNetworkPoints(2000)
        SpyMissionResolver.perform(aiSpy, steal)
        assertTrue(aiSpy.evaluateReassignment)
        assertFalse("not for a human", spy.evaluateReassignment)
    }

    @Test
    fun `the rolls give the same result for the same city, civilization, mission and count`() {
        steal.identifyChance = 50
        steal.killChance = 50
        val first = SpyMissionResolver.perform(spy, steal)
        // the same position again: as if loaded from a save and done again
        a.espionageManager.spyActionsDone.clear()
        spy.addNetworkPoints(2000)
        game.gameInfo.turns += 100
        b.tech.addTechnology("Animal Husbandry")
        if (!spy.isAlive()) return
        assertEquals(first, SpyMissionResolver.perform(spy, steal))
    }

    @Test
    fun `the missions of the generated file load`() {
        val text = """[{"name": "Steal a Technology", "networkPoints": 2000, "identifyChance": 5, "killChance": 95, "networkPointsScaledBySpeed": true, "cityFilter": "Capital",
            "uniques": ["Steal [1] random technology/technologies the target knows and we can research"]}]"""
        val loaded = json().fromJson(Array<SpyMission>::class.java, text).single()
        assertEquals(2000, loaded.networkPoints)
        assertEquals(95, loaded.killChance)
        assertTrue(loaded.networkPointsScaledBySpeed)
        assertEquals("Capital", loaded.cityFilter)
        assertNotNull(loaded.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.StealTechnologies).firstOrNull())
    }
}
