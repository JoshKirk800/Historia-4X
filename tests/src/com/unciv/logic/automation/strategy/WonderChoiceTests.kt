package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.unit.GreatPeopleDirective
import com.unciv.logic.automation.unit.GreatPersonAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 *  CvWonderProductionAI::ChooseWonder of Vox Populi through its caller CvCitySpecializationAI::DoTurn (the next wonder desired and its weight):
 *  each wonder is weighed in the city that is the best for it, run through CheckBuildingBuildSanity without the situational terms, and picked
 *  among those within CityProductionChoiceCutoffThreshold (90) percent of the best. Wonders are created with raw weights; in the second city
 *  (a non-capital of population 1) no wonder passes the sanity check, so the capital is the best city for all of them.
 */
@RunWith(GdxTestRunner::class)
class WonderChoiceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(7)
        civ = neutralCiv()
        capital = game.addCity(civ, game.tileMap[0, 0])
        game.addCity(civ, game.tileMap[4, 0])
        game.gameInfo.turns = 30
    }

    private fun neutralCiv(): Civilization {
        val newCiv = game.addCiv()
        for (flavor in Flavor.entries) newCiv.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) newCiv.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) newCiv.ai.setMajorCivApproachBias(approach, 0)
        return newCiv
    }

    /** A wonder anyone can build, with the raw wonder weight [weight] of [owner] */
    private fun wonder(weight: Int, owner: Civilization = civ): Building {
        val wonder = game.createWonder()
        wonder.cost = 100
        owner.ai.wonderWeights[wonder.name] = weight
        return wonder
    }

    /** The weight ChooseWonder gives [wonder]: in the city it is the best for, the raw weight reweighted by the turns that city needs, sanity checked */
    private fun weightOf(wonder: Building): Int {
        val city = civ.cities.single { BuildingProductionAi.isBestForWonder(it, wonder) }
        val turns = max(1, wonder.getProductionCost(civ, city) / max(1, ProductionSanityAi.rawProductionTimes100(city) / 100))
        val reweighted = WonderProductionAi.reweightByTurnsLeft(civ, WonderProductionAi.weight(civ, wonder), turns)
        return BuildingProductionAi.checkBuildingBuildSanity(city, wonder, reweighted, ignoreSituational = true)
    }

    @Test
    fun `the capital is the best city of the wonders, the second city is refused them`() {
        val wonder = wonder(90000)
        assertTrue(BuildingProductionAi.isBestForWonder(capital, wonder))
        assertTrue(weightOf(wonder) > 500) // the sanity check adds 500 to the weight of the best city
    }

    @Test
    fun `a wonder no city may sanely build is not wanted`() {
        // The only city of this civilization is no capital and has one citizen: BNW's wonder city weighed wonders there all the same
        val lone = neutralCiv()
        val city = game.addCity(lone, game.tileMap[-4, 0])
        city.cityConstructions.removeBuilding("Palace")
        wonder(100000, lone)
        val engineer = game.addUnit("Great Engineer", lone, city.getCenterTile())
        engineer.turnCreated = game.gameInfo.turns
        assertEquals(GreatPeopleDirective.None, GreatPersonAutomation.directiveEngineer(engineer))
    }

    @Test
    fun `the total weight is the sum of the sanity checked weights of the wonders in their best cities`() {
        val strong = wonder(90000)
        val weak = wonder(9000)
        val expected = weightOf(strong) + weightOf(weak)
        CitySpecializationAi.doTurn(civ)
        assertEquals(expected, civ.ai.citySpecialization.nextWonderWeight)
    }

    @Test
    fun `the next wonder is picked among those within 90 percent of the best, not among the best two`() {
        val strong = wonder(90000)
        val weak = wonder(9000)
        assertTrue("the weaker wonder weighs under 90 percent of the stronger one", weightOf(weak) * 100 < weightOf(strong) * 90)
        // BNW rolled among the two best by their weights: the weaker one a ninth of the time. It never comes up now.
        val chosen = (30 until 90).map {
            game.gameInfo.turns = it
            CitySpecializationAi.doTurn(civ)
            civ.ai.citySpecialization.nextWonderDesired
        }.toSet()
        assertEquals(setOf(strong.name), chosen)
    }
}
