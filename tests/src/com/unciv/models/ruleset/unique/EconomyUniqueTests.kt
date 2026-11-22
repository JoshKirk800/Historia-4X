package com.unciv.models.ruleset.unique

import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The per-turn shapes, the purchase requirement and the constants of the yield economy (`phaseE/vp-yields-plan.md` 4.3 and 4.6): the texts, and that they validate */
@RunWith(BaseTestRunner::class)
class EconomyUniqueTests {

    private val shapes = mapOf(
        "[50]% yield from processes [in all cities]" to UniqueType.ProcessYieldPercent,
        "[25]% of [Production] [in this city] added to [Gold]" to UniqueType.StatPercentAddedToStat,
        "[+3 Culture] from each [Art] Great Work [in this city]" to UniqueType.StatsFromGreatWorkType,
        "Capital's [+2 Gold] per [5] citizens in the empire" to UniqueType.CapitalStatsPerEmpirePopulation,
    )

    private fun errorsOf(game: TestGame, objectName: String) =
        game.ruleset.getErrorList().filter { it.text.contains(objectName) && it.errorSeverityToReport >= RulesetErrorSeverity.Warning }.map { it.text }

    @Test
    fun `the shapes have their texts`() {
        for ((text, type) in shapes) assertEquals(text, type, Unique(text).type)
        assertEquals(UniqueType.ImprovementStatsPerAdjacent, Unique("[+1 Gold] from every [2] adjacent [Forest]").type)
        assertEquals(UniqueType.CanOnlyBePurchasedInCitiesWith, Unique("Can only be purchased in cities with [Barracks]").type)
        assertEquals("[] from each [] Great Work []", UniqueType.StatsFromGreatWorkType.placeholderText)
        assertEquals("[]% of [] [] added to []", UniqueType.StatPercentAddedToStat.placeholderText)
        assertEquals("Capital's [] per [] citizens in the empire", UniqueType.CapitalStatsPerEmpirePopulation.placeholderText)
    }

    @Test
    fun `the shapes of a building and a policy are valid`() {
        val game = TestGame()
        val building = game.createBuilding(*shapes.keys.toTypedArray()).apply { cost = 100 }
        val policy = game.createPolicy(*shapes.keys.toTypedArray())
        assertEquals(emptyList<String>(), errorsOf(game, building.name))
        assertEquals(emptyList<String>(), errorsOf(game, policy.name))
    }

    @Test
    fun `an improvement may have the adjacent yield and a unit the building it needs`() {
        val game = TestGame()
        val improvement = game.createTileImprovement("[+1 Gold] from every [2] adjacent [Forest]", "[+1 Culture] from every [1] adjacent [Hill]")
        val unit = game.createBaseUnit("Sword", "Can only be purchased in cities with [Barracks]").apply { cost = 100 }
        // an improvement that nothing can build on has an error of its own
        assertEquals(emptyList<String>(), errorsOf(game, improvement.name).filter { "adjacent" in it || "from every" in it })
        assertEquals(emptyList<String>(), errorsOf(game, unit.name))
    }

    @Test
    fun `the parameters are checked`() {
        val game = TestGame()
        val building = game.createBuilding(
            "[+3 Culture] from each [Sculpture] Great Work [in this city]",
            "Capital's [+2 Gold] per [0] citizens in the empire",
            "[50]% of [Wibble] [in this city] added to [Gold]"
        ).apply { cost = 100 }
        val errors = errorsOf(game, building.name)
        assertTrue(errors.toString(), errors.any { "Sculpture" in it })
        assertTrue(errors.toString(), errors.any { "[0]" in it })
        assertTrue(errors.toString(), errors.any { "Wibble" in it })
        val unit = game.createBaseUnit("Sword", "Can only be purchased in cities with [No such building]").apply { cost = 100 }
        assertTrue(errorsOf(game, unit.name).any { "No such building" in it })
    }

    @Test
    fun `the constants default to Brave New World's numbers`() {
        val constants = ModConstants()
        assertEquals(ModConstants.PurchaseConstants(), constants.purchase)
        assertEquals(30.0, constants.purchase.goldPerProduction, 0.0)
        assertEquals(0.75, constants.purchase.exponent, 0.0)
        assertEquals(10, constants.purchase.visibleDivisor)
        assertEquals(false, constants.buildingInvestments)
        assertEquals(-50, constants.buildingInvestmentBaseline)
        assertEquals(2, constants.buildingInvestmentWonderDivisor)
        assertEquals(100, constants.goldBuildingCostPercent)
        assertEquals(emptyList<Int>(), constants.worldWonderCostIncrease)
        assertEquals(false, constants.purchaseCooldownEnabled)
    }

    @Test
    fun `the constants merge per field`() {
        val parent = ModConstants()
        val mod = ModConstants().apply {
            buildingInvestments = true
            worldWonderCostIncrease = listOf(25, 15, 10, 5)
            purchase = ModConstants.PurchaseConstants(4.0, 0.88)
        }
        parent.merge(mod)
        assertEquals(true, parent.buildingInvestments)
        assertEquals(listOf(25, 15, 10, 5), parent.worldWonderCostIncrease)
        assertEquals(4.0, parent.purchase.goldPerProduction, 0.0)
        assertEquals(false, parent.purchaseCooldownEnabled)
    }

    @Test
    fun `the Brave New World ruleset has no data yet for the speed hurry modifier and the buildings' cooldown reductions`() {
        // The constants, the eras, the buildings' costs and the unit cooldowns are Vox Populi's since content flip A (BuildingYieldContentTests, UnitPurchaseContentTests)
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        assertTrue(game.ruleset.speeds.values.all { it.hurryModifier == 1f })
        assertTrue(game.ruleset.buildings.values.all { it.purchaseCooldownReduction == 0 && it.purchaseCooldownReductionCivilian == 0 })
    }
}
