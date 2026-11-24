package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.SpyMission
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The instant yields of a spy's mission (CvPlayerEspionage::ProcessSpyMissionResult, V:CvEspionageClasses.cpp:1586-1669; CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15296-15300;
 *  CvPlayer::doInstantYield, V:CvPlayer.cpp:25909-26228) as [InstantYields.spyMissionPerformed], [InstantYields.spyOutcome] and [InstantYields.riggingWon] play them. V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. [civ] has three cities, [capital], [second] and [third]; the uniques are the texts the generator writes for the five building tables and for
 *  Double Agents. Gold is what the tests read.
 */
@RunWith(BaseTestRunner::class)
class SpyEventYieldTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City
    private lateinit var third: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 2)
        second = game.addCity(civ, game.getTile(HexCoord(4, 0)), initialPopulation = 2)
        third = game.addCity(civ, game.getTile(HexCoord(-4, 0)), initialPopulation = 2)
        game.gameInfo.turns = 10
    }

    private fun City.build(vararg uniques: String): Building = game.createBuilding(*uniques).also { cityConstructions.addBuilding(it) }
    private fun gold(trigger: String, amount: Int = 10) = "Gain [$amount] [Gold] <upon $trigger> <in this city>"
    private fun adopt(vararg uniques: String) = civ.policies.adopt(game.createPolicy(*uniques), true)

    private val killing = "[killing] a foreign spy in one of our cities"
    private val identifying = "[identifying] a foreign spy in one of our cities"
    private val identifyingLived = "[identifying without killing] a foreign spy in one of our cities"
    private val killingOrIdentifying = "[killing or identifying] a foreign spy in one of our cities"

    // region what became of the spy: V:CvEspionageClasses.cpp:1616-1627

    @Test
    fun `a spy that was killed and not identified pays the killing and the either, nothing else`() {
        capital.build(gold(killing, 1), gold(identifying, 2), gold(identifyingLived, 4), gold(killingOrIdentifying, 8))
        InstantYields.spyOutcome(civ, capital, killed = true, identified = false)
        assertEquals("killing 1 + killing or identifying 8", 9, civ.gold)
    }

    @Test
    fun `a spy that was identified and lived pays identifying, identifying without killing and the either, not the killing`() {
        capital.build(gold(killing, 1), gold(identifying, 2), gold(identifyingLived, 4), gold(killingOrIdentifying, 8))
        InstantYields.spyOutcome(civ, capital, killed = false, identified = true)
        assertEquals("identifying 2 + without killing 4 + either 8", 14, civ.gold)
    }

    @Test
    fun `a spy that was both killed and identified pays everything but identifying without killing`() {
        capital.build(gold(killing, 1), gold(identifying, 2), gold(identifyingLived, 4), gold(killingOrIdentifying, 8))
        InstantYields.spyOutcome(civ, capital, killed = true, identified = true)
        // the original's `else if` (V:CvEspionageClasses.cpp:1622) leaves out the table of the spy that lived. `identifying` is the trigger of the focus's identified yields and of Double Agents,
        // which are paid for every identification, killed or not (:1596-1603, :1663-1669)
        assertEquals("killing 1 + identifying 2 + either 8, and no 'identifying without killing' 4", 11, civ.gold)
    }

    @Test
    fun `a spy that was neither killed nor identified pays nobody`() {
        capital.build(gold(killing, 1), gold(identifying, 2), gold(identifyingLived, 4), gold(killingOrIdentifying, 8))
        InstantYields.spyOutcome(civ, capital, killed = false, identified = false)
        assertEquals(0, civ.gold)
    }

    @Test
    fun `the outcomes are a pure rule of two facts`() {
        assertTrue(SpyOutcome.Killing.appliesTo(killed = true, identified = false))
        assertFalse(SpyOutcome.Killing.appliesTo(killed = false, identified = true))
        assertTrue(SpyOutcome.Identifying.appliesTo(killed = true, identified = true))
        assertTrue(SpyOutcome.IdentifyingWithoutKilling.appliesTo(killed = false, identified = true))
        assertFalse(SpyOutcome.IdentifyingWithoutKilling.appliesTo(killed = true, identified = true))
        assertTrue(SpyOutcome.KillingOrIdentifying.appliesTo(killed = true, identified = false))
        assertTrue(SpyOutcome.KillingOrIdentifying.appliesTo(killed = false, identified = true))
        assertFalse(SpyOutcome.KillingOrIdentifying.appliesTo(killed = false, identified = false))
    }

    // endregion

    // region which city is paid: V:CvPlayer.cpp:25928-25932

    @Test
    fun `the yields of an outcome are paid in the city of the spy only`() {
        capital.build(gold(killingOrIdentifying, 5))
        second.build(gold(killingOrIdentifying, 7))
        InstantYields.spyOutcome(civ, second, killed = true, identified = false)
        assertEquals("only the building of the city the spy was in", 7, civ.gold)
    }

    @Test
    fun `a mission pays every city of its owner what its own buildings say, once each`() {
        capital.build(gold("performing a spy mission", 3))
        second.build(gold("performing a spy mission", 5))
        // third has no such building
        InstantYields.spyMissionPerformed(civ)
        assertEquals("3 + 5, a city without the building pays nothing", 8, civ.gold)
    }

    @Test
    fun `a rigged election pays every city of the winner what its own buildings say`() {
        capital.build(gold("winning a City-State election with our spy", 4))
        third.build(gold("winning a City-State election with our spy", 6))
        InstantYields.riggingWon(civ)
        assertEquals(10, civ.gold)
    }

    @Test
    fun `a mission pays for the owner of the spy, an outcome for the owner of the city, never the other way`() {
        val other = game.addCiv()
        val otherCity = game.addCity(other, game.getTile(HexCoord(0, 4)), initialPopulation = 2)
        capital.build(gold("performing a spy mission", 3), gold(killingOrIdentifying, 100))
        otherCity.build(gold(killingOrIdentifying, 7), gold("performing a spy mission", 1000))

        InstantYields.spyMissionPerformed(civ)
        InstantYields.spyOutcome(other, otherCity, killed = true, identified = false)

        assertEquals("the spy's owner got the attack yield and nothing of the outcome", 3, civ.gold)
        assertEquals("the city's owner got the outcome yield and nothing of the attack", 7, other.gold)
    }

    // endregion

    // region the pass yields: the counterspy focus and Double Agents

    @Test
    fun `Double Agents pays for every identification, killed or not, but not for a spy that got away`() {
        adopt(gold(identifying, 125).replace(" <in this city>", ""))   // the policy's text has no city: the civilization's yield
        InstantYields.spyOutcome(civ, capital, killed = false, identified = true)
        assertEquals(125, civ.gold)
        InstantYields.spyOutcome(civ, capital, killed = true, identified = true)
        assertEquals("a killed and identified spy counts too", 250, civ.gold)
        InstantYields.spyOutcome(civ, capital, killed = true, identified = false)
        assertEquals("killed but not identified: nothing", 250, civ.gold)
    }

    @Test
    fun `the yields of a counterspy focus are paid in the city that has the focus, while its spy counters spies there`() {
        game.ruleset.modOptions.constants.espionageLikeVp = true
        game.gameInfo.gameParameters.espionageEnabled = true
        val focus = SpyMission().apply {
            name = "Test focus"; isFocus = true; cityFilter = "All"
            uniques = arrayListOf(gold(killing, 50))
        }
        game.ruleset.spyMissions[focus.name] = focus
        try {
            val spy = civ.espionageManager.addSpy()
            spy.moveTo(capital)
            var guard = 0
            while (spy.action == SpyAction.Moving && guard++ < 20) civ.espionageManager.endTurn()
            assertEquals(SpyAction.CounterIntelligence, spy.action)
            assertTrue(spy.chooseFocus(focus))

            InstantYields.spyOutcome(civ, capital, killed = true, identified = false)
            assertEquals("the focus of the spy in the capital pays in the capital", 50, civ.gold)

            InstantYields.spyOutcome(civ, second, killed = true, identified = false)
            assertEquals("a city without the spy pays nothing for it", 50, civ.gold)

            spy.moveTo(null)
            InstantYields.spyOutcome(civ, capital, killed = true, identified = false)
            assertEquals("and the focus is gone with the spy", 50, civ.gold)
        } finally {
            game.ruleset.spyMissions.remove(focus.name)
        }
    }

    // endregion

    // region scaling and the real texts: V:CvPlayer.cpp:27167, 27181

    @Test
    fun `the generated text is scaled by the era as the original scales every spy yield`() {
        capital.build("Gain [100] [Gold] <upon $killing> <in this city> <(modified by game speed)> <for every [Era scaler]>")
        val era = game.ruleset.eras.values.first { it.eraNumber == 2 }
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == era.name }.name)
        assertEquals(2, civ.getEraNumber())
        InstantYields.spyOutcome(civ, capital, killed = true, identified = false)
        assertEquals("100 x era 2 at the standard speed", 200, civ.gold)
    }

    @Test
    fun `a civilization that is a city-state or a barbarian is paid nothing`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val csCity = game.addCity(cityState, game.getTile(HexCoord(0, -4)), initialPopulation = 2)
        csCity.build(gold(killingOrIdentifying, 50))
        InstantYields.spyOutcome(cityState, csCity, killed = true, identified = false)
        assertEquals("V:CvPlayer.cpp:25912", 0, cityState.gold)
    }

    // endregion
}
