package com.unciv.models.ruleset

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.SupplyTable
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 CvPlayer::DoGreatPersonExpended (V:CvPlayer.cpp:28895-28899): expending a unit adds its SupplyCapBoost (0 on every unit) plus its MilitaryCapChange (+1 from the
 *  free promotion PROMOTION_MILITARY_TRADITION of the Great General, Great Admiral, Khan and Induna) to the player's supply for good; GetUnitSupplyBreakdown adds it to the subtotal
 *  before the empire size divisor (V:CvPlayer.cpp:16995-16997). Here it is the unit unique `Grants [amount] Unit Supply when expended`, read with
 *  `unitSupplyLikeVp` and `unitSupplyExpendedGreatPeopleLikeVp`.
 */
@RunWith(GdxTestRunner::class)
class ExpendedGreatPeopleSupplyTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val ruleset = game.ruleset

    @After
    fun restoreTable() = SupplyTable.restore(ruleset)

    private fun setup(switchOn: Boolean): Civilization {
        SupplyTable.communityPatch(ruleset)
        val constants = ruleset.modOptions.constants
        constants.unitSupplyLikeVp = true
        constants.unitSupplyExpendedGreatPeopleLikeVp = switchOn
        game.makeHexagonalMap(4, Constants.grassland)
        game.setTileTerrain(HexCoord(0, 3), Constants.ocean) // a Great Admiral can not stand on land
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)), initialPopulation = 1)
        return civ
    }

    /** Puts the unit on a free tile of its own domain, away from the city, and consumes it (as a Citadel or a Great Person's action does). */
    private fun expend(civ: Civilization, name: String) {
        val water = ruleset.units[name]!!.isWaterUnit
        val tile = game.tileMap.values.first {
            it.getUnits().none() && it.aerialDistanceTo(game.getTile(HexCoord(0, 0))) in 1..3 && it.isWater == water
        }
        game.addUnit(name, civ, tile).consume()
    }

    @Test
    fun `the ruleset plays the supply of expended great people as shipped`() {
        // no constant is set here: the value of ModOptions.json (flipped by the owner's go, 2026-11-27)
        assertTrue(ruleset.modOptions.constants.unitSupplyLikeVp)
        assertTrue(ruleset.modOptions.constants.unitSupplyExpendedGreatPeopleLikeVp)
        game.makeHexagonalMap(4, Constants.grassland)
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)), initialPopulation = 1)
        val tile = game.tileMap.values.first { it.getUnits().none() && it.aerialDistanceTo(game.getTile(HexCoord(0, 0))) in 1..3 }
        game.addUnit("Great General", civ, tile).consume()
        assertEquals(1, civ.unitSupplyFromExpendedGreatPeople)
    }

    @Test
    fun `only the Great General, the Great Admiral and the Khan grant supply when expended`() {
        val granting = ruleset.units.values.filter { it.supplyWhenExpended != 0 }.associate { it.name to it.supplyWhenExpended }
        assertEquals(mapOf("Great General" to 1, "Great Admiral" to 1, "Khan" to 1), granting)
    }

    @Test
    fun `expending a Great General adds one supply for good, each one again, and the breakdown shows it before the empire size divisor`() {
        val civ = setup(switchOn = true)
        val before = civ.stats.getUnitSupplyBreakdown()
        assertEquals(0, before.greatPeople)

        expend(civ, "Great General")
        assertEquals(1, civ.unitSupplyFromExpendedGreatPeople)
        val one = civ.stats.getUnitSupplyBreakdown()
        assertEquals(1, one.greatPeople)
        assertEquals("the grant is part of the subtotal the divisor works on", before.subtotal + 1, one.subtotal)
        assertEquals(one.subtotal * 100 / one.empireSizeDivisorPercent, one.afterEmpireSize)

        expend(civ, "Great Admiral")
        expend(civ, "Khan")
        assertEquals(3, civ.unitSupplyFromExpendedGreatPeople)
        assertEquals(before.subtotal + 3, civ.stats.getUnitSupplyBreakdown().subtotal)
    }

    @Test
    fun `a unit without the unique, and every unit with the switch off, grants nothing`() {
        val on = setup(switchOn = true)
        expend(on, "Warrior")
        assertEquals("a Warrior has no such unique", 0, on.unitSupplyFromExpendedGreatPeople)

        ruleset.modOptions.constants.unitSupplyExpendedGreatPeopleLikeVp = false
        val supplyBefore = on.stats.getUnitSupply()
        expend(on, "Great General")
        assertEquals("switch off: no counter", 0, on.unitSupplyFromExpendedGreatPeople)
        assertEquals("switch off: the supply is as before", supplyBefore, on.stats.getUnitSupply())
        assertEquals(0, on.stats.getUnitSupplyBreakdown().greatPeople)
    }

    @Test
    fun `the amounts of the uniques of a unit are added, not counted`() {
        val civ = setup(switchOn = true)
        val general = ruleset.units["Great General"]!!
        val modded = game.createBaseUnit(general.unitType, "Grants [3] Unit Supply when expended", "Grants [2] Unit Supply when expended")
        assertEquals(5, modded.supplyWhenExpended)
        expend(civ, modded.name)
        assertEquals(5, civ.unitSupplyFromExpendedGreatPeople)
        assertEquals(5, civ.stats.getUnitSupplyBreakdown().greatPeople)
    }

    @Test
    fun `a counter already earned is not read once the switch is off`() {
        val civ = setup(switchOn = true)
        expend(civ, "Great General")
        val withGrant = civ.stats.getUnitSupplyBreakdown().subtotal
        ruleset.modOptions.constants.unitSupplyExpendedGreatPeopleLikeVp = false
        assertEquals(withGrant - 1, civ.stats.getUnitSupplyBreakdown().subtotal)
    }
}
