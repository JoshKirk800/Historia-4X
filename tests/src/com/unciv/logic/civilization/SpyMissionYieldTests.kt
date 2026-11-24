package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.models.ModConstants
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  A mission done through [SpyMissionResolver.perform] pays the instant yields of the spy events (V:CvEspionageClasses.cpp:1586-1669): the spy's owner [a] in every city for any mission, the owner
 *  of the city [b] in that city for what became of the spy. With an identify chance and a kill chance of 0 or 100 the outcome does not depend on the rolls. The buildings carry the
 *  texts the generator writes. Gold is what the tests read.
 */
@RunWith(GdxTestRunner::class)
class SpyMissionYieldTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCity: City
    private lateinit var bCity: City
    private lateinit var spy: Spy
    private val missions = ArrayList<String>()

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
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.ruleset.modOptions.constants.espionage = ModConstants.EspionageConstants(travelBaseTurns = 1, travelTurnsByProximity = listOf(0, 0, 0, 0))
        spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.GatheringIntel, spy.action)
        aCity.cityConstructions.addBuilding(game.createBuilding("Gain [3] [Gold] <upon performing a spy mission> <in this city>"))
        for ((trigger, gold) in listOf("[killing]" to 10, "[identifying]" to 20, "[identifying without killing]" to 40, "[killing or identifying]" to 80))
            bCity.cityConstructions.addBuilding(game.createBuilding("Gain [$gold] [Gold] <upon $trigger a foreign spy in one of our cities> <in this city>"))
    }

    @After
    fun tearDown() {
        for (name in missions) game.ruleset.spyMissions.remove(name)
    }

    /** A mission with no cost and no effect; [identify] and [kill] are 0 or 100 */
    private fun mission(identify: Int, kill: Int): SpyMission {
        val mission = SpyMission().apply {
            name = "Test mission $identify/$kill"; networkPoints = 0; identifyChance = identify; killChance = kill; cityFilter = "Capital"
        }
        game.ruleset.spyMissions[mission.name] = mission
        missions += mission.name
        return mission
    }

    @Test
    fun `a mission nobody noticed pays its owner the attack yield and the other nothing`() {
        SpyMissionResolver.perform(spy, mission(identify = 0, kill = 0))
        assertEquals(3, a.gold)
        assertEquals(0, b.gold)
    }

    @Test
    fun `a spy that was identified and lived pays the city's owner identifying, identifying without killing and either`() {
        SpyMissionResolver.perform(spy, mission(identify = 100, kill = 0))
        assertEquals("the attack is paid for every result", 3, a.gold)
        assertEquals("20 + 40 + 80, not the killing 10", 140, b.gold)
    }

    @Test
    fun `a spy that was killed and not identified pays the city's owner killing and either`() {
        SpyMissionResolver.perform(spy, mission(identify = 0, kill = 100))
        assertEquals("the attack is paid even for a spy that died", 3, a.gold)
        assertEquals("10 + 80", 90, b.gold)
    }

    @Test
    fun `a spy that was killed and identified pays the city's owner everything but identifying without killing`() {
        SpyMissionResolver.perform(spy, mission(identify = 100, kill = 100))
        assertEquals(3, a.gold)
        assertEquals("10 + 20 + 80", 110, b.gold)
    }
}
