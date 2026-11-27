package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.Religion
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's Inquisition (BELIEF_RELIGIOUS_TEXTS): happiness for each spy in a city (CvPlayer::GetHappinessFromReligion, V:CvPlayer.cpp:21891-21915) and cheaper
 * Inquisitors in the cities that follow the religion (CvCity::GetFaithPurchaseCost, V:CvCity.cpp:12134-12153).
 */
@RunWith(GdxTestRunner::class)
class ReligionSpyHappinessTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var holyCity: City
    private lateinit var ours: Religion

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        holyCity = game.addCity(civ, game.getTile(0, 0), initialPopulation = 3)
        ours = game.addReligion(civ)
        ours.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[2] Happiness for each of our spies in a city",
            "[Faith] cost of purchasing [Inquisitor] units [-33]% <in cities following our religion>")))
        holyCity.religion.religionThisIsTheHolyCityOf = ours.name
        holyCity.religion.addPressure(ours.name, 5000)
        civ.religionManager.invalidateStateReligion()
    }

    private fun religionHappiness() = civ.stats.getHappinessBreakdown()["Religion"] ?: 0f

    @Test
    fun `each spy in a city gives the happiness, at home or abroad, and none for a spy that moves, is in the hideout or is dead`() {
        val other = game.addCiv()
        val foreignCity = game.addCity(other, game.getTile(6, 0))
        civ.espionageManager.addSpy().apply { moveTo(holyCity); setAction(SpyAction.CounterIntelligence) }
        civ.espionageManager.addSpy().apply { moveTo(foreignCity); setAction(SpyAction.EstablishNetwork, 3) }
        civ.espionageManager.addSpy().moveTo(foreignCity) // still on its way
        civ.espionageManager.addSpy() // in the hideout
        civ.espionageManager.addSpy().apply { moveTo(foreignCity); killSpy() }

        assertEquals(2 * 2f, religionHappiness(), 0f)
    }

    @Test
    fun `Inquisitors cost less faith in a city that follows our religion only`() {
        val other = game.addCity(civ, game.getTile(6, 0), initialPopulation = 3)
        // an Inquisitor is bought in a city of a major religion only
        val theirs = game.addReligion(game.addCiv())
        theirs.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        other.religion.addPressure(theirs.name, 5000)
        val inquisitor = game.ruleset.units["Inquisitor"]!!

        val following = inquisitor.getStatBuyCost(holyCity, Stat.Faith)!!
        val notFollowing = inquisitor.getStatBuyCost(other, Stat.Faith)!!

        assertEquals(notFollowing * 67 / 100 / 10 * 10, following) // rounded down to the visible divisor, 10
    }
}
