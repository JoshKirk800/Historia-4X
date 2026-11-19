package com.unciv.logic.automation.diplomacy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.HexCoord
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's religion opinion score (CvDiplomacyAI::GetReligionScore, IsIgnoreReligionDifferences, Release-5.4.8) */
@RunWith(GdxTestRunner::class)
class OpinionBeliefsTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        for (civ in listOf(a, b, c)) for (other in listOf(a, b, c)) if (civ != other) civ.diplomacyFunctions.makeCivilizationsMeet(other)
        // A religion flavor above 7: the era's religion emphasis + 1
        for (civ in listOf(a, b, c)) civ.ai.setPersonalityFlavor(Flavor.Religion, 8)
    }

    /** A founded religion (a Founder belief) whose holy city is [civ]'s capital at [tile], followed there */
    private fun found(civ: Civilization, tile: HexCoord): Religion {
        val city = game.addCity(civ, game.getTile(tile))
        val religion = game.addReligion(civ)
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        city.religion.religionThisIsTheHolyCityOf = religion.name
        follow(city, religion)
        return religion
    }

    private fun follow(city: City, religion: Religion) {
        city.population.setPopulation(3)
        city.religion.addPressure(religion.name, 5000)
    }

    @Test
    fun `two founders differ unless differences are set aside, and following a founder's religion is a bonus`() {
        val ours = found(a, HexCoord(0, 0))
        found(b, HexCoord(6, 0))
        val eraMod = rules.eraDiplomacy(DiplomacyTurn.gameEraName(a)).diploEmphasisReligion + 1
        // A founder counts as religious (VP's dynamic trait): one more
        val founderMod = eraMod + 1
        assertEquals(DiplomaticModifiers.DifferentReligion to 5 * founderMod, OpinionAi.religionScore(a, b, rules))
        // They resurrected us: the penalty is set aside
        a.ai.diplomacy.memoryOf(b).resurrectedUs = true
        assertNull(OpinionAi.religionScore(a, b, rules))

        // c founded nothing and follows a's religion: its state religion is a's owned one
        follow(game.addCity(c, game.getTile(HexCoord(0, 6))), ours)
        assertEquals(DiplomaticModifiers.WeAdoptedTheirReligion to -4 * eraMod, OpinionAi.religionScore(c, a, rules))
        assertEquals(DiplomaticModifiers.TheyAdoptedOurReligion to -8 * founderMod, OpinionAi.religionScore(a, c, rules))
        // Converting c's cities ends the bonus of c's adoption
        c.ai.diplomacy.memoryOf(a).negativeReligiousConversionPoints = 1
        assertNull(OpinionAi.religionScore(c, a, rules))
    }
}
