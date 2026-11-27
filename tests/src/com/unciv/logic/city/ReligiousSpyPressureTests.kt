package com.unciv.logic.city

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Religion
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `religiousSpreadLikeVp`: the spies of CvGameReligions::SpreadReligionToOneCity (V:CvReligionClasses.cpp:413-443), AddSpyPressure (5186-5190),
 * DoSpyPressureErosion (5193-5212), and the share kept when the other religions are removed (ErodeOtherReligiousPressure, 4958-4965). The base pressure is 1000.
 */
@RunWith(GdxTestRunner::class)
class ReligiousSpyPressureTests {
    private lateinit var game: TestGame
    private lateinit var target: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        game.ruleset.modOptions.constants.religiousSpreadLikeVp = true
        game.gameInfo.speed.religiousPressureAdjacentCity = 1000
        target = game.addCity(game.addCiv(), game.getTile(0, 0))
    }

    private fun religion(founder: Civilization, vararg uniques: String): Religion {
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, *uniques)))
        return religion
    }

    /** A spy of [civ] in the target city, settled there unless [travelling]; [civ] gets the holy city of its religion 4 tiles away, so that it is its state religion */
    private fun spyOf(civ: Civilization, travelling: Boolean = false) {
        game.addCity(civ, game.getTile(4, 0)).religion.religionThisIsTheHolyCityOf = civ.religionManager.religion!!.name
        civ.religionManager.invalidateStateReligion()
        val spy = civ.espionageManager.addSpy()
        spy.moveTo(target)
        if (!travelling) spy.setAction(SpyAction.Surveillance)
    }

    private fun pressure(religion: Religion) = target.religion.getPressures()[religion.name]

    @Test
    fun `a spy adds its pressure where the religion is absent`() {
        val spyCiv = game.addCiv()
        val religion = religion(spyCiv, "Spies exert [2] times the natural religious pressure on the cities they occupy")
        spyOf(spyCiv)
        target.religion.endTurn()
        assertEquals(2000, pressure(religion))

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        target.religion.endTurn()
        assertEquals(4000, pressure(religion)) // without the switch it adds where the religion is present
    }

    @Test
    fun `without the switch a spy adds nothing where the religion is absent`() {
        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        val spyCiv = game.addCiv()
        val religion = religion(spyCiv, "Spies exert [2] times the natural religious pressure on the cities they occupy")
        spyOf(spyCiv)
        target.religion.endTurn()
        assertEquals(0, pressure(religion))
    }

    @Test
    fun `a travelling spy adds nothing`() {
        val spyCiv = game.addCiv()
        val religion = religion(spyCiv, "Spies exert [2] times the natural religious pressure on the cities they occupy")
        spyOf(spyCiv, travelling = true)
        target.religion.endTurn()
        assertEquals(0, pressure(religion))
    }

    @Test
    fun `a city-state's spy adds nothing`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val religion = religion(cityState, "Spies exert [2] times the natural religious pressure on the cities they occupy")
        spyOf(cityState)
        target.religion.endTurn()
        assertEquals(0, pressure(religion))
    }

    @Test
    fun `a spy erodes each other religion down to nothing and leaves pantheons and atheism alone`() {
        val spyCiv = game.addCiv()
        val religion = religion(spyCiv, "Spies erode [3] times the natural religious pressure of the other religions in the cities they occupy")
        val strong = religion(game.addCiv())
        val weak = religion(game.addCiv())
        val pantheon = game.addReligion(target.civ)
        pantheon.addBeliefs(listOf(game.createBelief(BeliefType.Pantheon)))
        target.religion.addPressure(religion.name, 1000)
        target.religion.addPressure(strong.name, 5000)
        target.religion.addPressure(weak.name, 2000)
        target.religion.addPressure(pantheon.name, 300)
        val atheism = target.religion.getPressures()[Constants.noReligionName]
        spyOf(spyCiv)

        target.religion.endTurn()
        assertEquals(1000, pressure(religion)) // its own religion is spared
        assertEquals(2000, pressure(strong))
        assertEquals(0, pressure(weak))
        assertEquals(300, pressure(pantheon))
        assertEquals(atheism, target.religion.getPressures()[Constants.noReligionName])

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        target.religion.endTurn()
        assertEquals(2000, pressure(strong))
    }

    @Test
    fun `the share kept when other religions are removed rounds up`() {
        val kept = religion(game.addCiv(), "[33]% of the pressure of this religion in a city remains when other religions are removed from it")
        val remover = religion(game.addCiv())
        target.religion.addPressure(kept.name, 1001)
        target.religion.removeAllPressuresExceptFor(remover.name)
        assertEquals(331, pressure(kept)) // 1001 less 67% of it rounded down, 670

        game.ruleset.modOptions.constants.religiousSpreadLikeVp = false
        target.religion.addPressure(kept.name, 1001 - 331)
        target.religion.removeAllPressuresExceptFor(remover.name)
        assertEquals(330, pressure(kept)) // 33% of 1001 rounded down
    }
}
