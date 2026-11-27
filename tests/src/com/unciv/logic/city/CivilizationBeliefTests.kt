package com.unciv.logic.city

import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  A belief of one civilization as the generator writes Vox Populi's Celtic pantheons (`Beliefs.CivilizationType` with MOD_BALANCE_UNIQUE_BELIEFS_ONLY_FOR_CIV):
 *  every unique ends in `<for [civ] Civilizations>`, so it works only where that civilization is the one asked, the owner of the city
 *  (CvReligionBeliefs::IsBeliefValid, V:CvBeliefClasses.cpp:2310-2314).
 */
@RunWith(GdxTestRunner::class)
class CivilizationBeliefTests {
    @Test
    fun `a belief of one civilization works in its cities, not in a city of another civilization following the same religion`() {
        val game = TestGame()
        game.makeHexagonalMap(7)
        val celts = game.addCiv()
        val other = game.addCiv()
        val religion = game.addReligion(celts)
        val celticBelief = game.createBelief(BeliefType.Pantheon, "[+2 Culture] [in cities following this religion] <for [${celts.civName}] Civilizations>")
        val anyBelief = game.createBelief(BeliefType.Pantheon, "[+2 Culture] [in cities following this religion]")
        religion.addBeliefs(listOf(celticBelief, anyBelief))
        val celtic = game.addCity(celts, game.getTile(0, 0), initialPopulation = 3)
        val foreign = game.addCity(other, game.getTile(5, 0), initialPopulation = 3)
        for (city in listOf(celtic, foreign)) city.religion.addPressure(religion.name, 1000)
        fun City.from(belief: String) = getMatchingUniques(UniqueType.StatsPerCity).count { it.sourceObjectName == belief }

        assertEquals(1, celtic.from(celticBelief.name))
        assertEquals(0, foreign.from(celticBelief.name))
        assertEquals("a belief for every civilization", 1, foreign.from(anyBelief.name))
    }
}
