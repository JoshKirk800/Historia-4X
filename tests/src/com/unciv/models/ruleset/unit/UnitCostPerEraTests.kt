package com.unciv.models.ruleset.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  `Cost increases by [amount] for every era after the second`: Vox Populi's `Units.ProductionCostAddedPerEra`, added in GetGenericUnitProductionCost
 *  (V:CvGameCoreUtils.cpp:1296-1304, V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`) once for each era of the owner after its second, before any percentage. A unit with the unique
 *  is compared with a unit of the cost it should have and no unique, so the speed and the starting era multiply both alike.
 */
@RunWith(GdxTestRunner::class)
class UnitCostPerEraTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val civ = game.addCiv()

    private fun unit(cost: Int, vararg uniques: String) = game.createBaseUnit("Civilian", *uniques).apply { this.cost = cost }
    private fun setEra(eraNumber: Int) { civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == eraNumber } }
    private fun cost(unit: BaseUnit, owner: Civilization = civ) = unit.getProductionCost(owner, null)

    private val perEra = "Cost increases by [150] for every era after the second"

    @Test
    fun `nothing is added in the first two eras`() {
        val emissary = unit(100, perEra)
        val plain = unit(100)
        setEra(0)
        assertEquals(cost(plain), cost(emissary))
        setEra(1)
        assertEquals(cost(plain), cost(emissary))
    }

    @Test
    fun `the amount is added once for each era after the second`() {
        val emissary = unit(100, perEra)
        setEra(2)
        assertEquals(cost(unit(250)), cost(emissary))
        setEra(5)
        assertEquals(cost(unit(700)), cost(emissary))
    }

    @Test
    fun `the eras are counted as the original counts them, which has no ninth era`() {
        val emissary = unit(100, perEra)
        setEra(7)
        assertEquals(cost(unit(1000)), cost(emissary))
        setEra(8)
        assertEquals("Unciv's Future era is the original's last, the seventh", cost(unit(1000)), cost(emissary))
    }

    @Test
    fun `the amount is added before the percentages`() {
        val emissary = unit(100, perEra, "[-50]% production cost")
        setEra(3)
        // (100 + 150 x 2) / 2 = 200; after the percentage it would be 50 + 300 = 350
        assertEquals(cost(unit(400, "[-50]% production cost")), cost(emissary))
    }

    @Test
    fun `the uniques add up`() {
        val both = unit(100, perEra, "Cost increases by [75] for every era after the second")
        setEra(2)
        assertEquals(cost(unit(325)), cost(both))
    }
}
