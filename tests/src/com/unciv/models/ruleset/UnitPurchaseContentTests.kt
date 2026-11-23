package com.unciv.models.ruleset

import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The unit purchase data and upgrade prices of Vox Populi 5.4.8 in the Brave New World ruleset (`phaseE/rulegen.md` "Content flip A"): `Units.PurchaseCooldown` and
 *  `GlobalFaithPurchaseCooldown` (CvCity::Purchase, V:CvCity.cpp:31100, 31121-31135), `Unit_BuildingClassPurchaseRequireds` (CvCity::IsCanPurchase, V:CvCity.cpp:30802-30815),
 *  the buildings' `PurchaseCooldownReduction` (only the Piazza San Marco, which waits for a package), and the upgrade price of option BALANCE_UNIT_UPGRADE_COST
 *  (V:CvGameCoreUtils.cpp:1361-1379) with UNIT_UPGRADE_COST_DISCOUNT_MAX -99 (`DefineChanges.sql:212`).
 */
@RunWith(GdxTestRunner::class)
class UnitPurchaseContentTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val ruleset = game.ruleset

    @Test
    fun `military units wait one turn after a purchase, workers and caravans five`() {
        for (name in listOf("Warrior", "Archer", "Swordsman", "Tank", "Great Artist")) assertEquals(name, 1, ruleset.units[name]!!.purchaseCooldown)
        for (name in listOf("Worker", "Work Boats", "Caravan", "Cargo Ship")) assertEquals(name, 5, ruleset.units[name]!!.purchaseCooldown)
        val military = ruleset.units.values.filter { it.strength > 0 || it.rangedStrength > 0 }
        assertTrue(military.all { it.purchaseCooldown <= 1 })
        // 131 units of the 145 before the M4 activation, plus the 13 VP units it added (Barbarian Horseman, Field Gun, Fleet Destroyer, Sensor Combat Ship, Liburna, Cruiser,
        // Dreadnought, Attack Submarine, Rocket Missile, Iron Chariot, Fusta, Licorne, Inquisition), each with Units.PurchaseCooldown 1 in ref/db/vp.sqlite
        assertEquals(131 + 13, ruleset.units.values.count { it.purchaseCooldown > 0 })
    }

    @Test
    fun `great people have the faith cooldown of five turns`() {
        for (name in listOf("Great Artist", "Great Writer", "Great Musician", "Great Scientist", "Great Merchant", "Great Engineer", "Great Prophet", "Great General", "Great Admiral"))
            assertEquals(name, 5, ruleset.units[name]!!.globalFaithPurchaseCooldown)
        assertTrue(ruleset.units.values.all { it.localFaithPurchaseCooldown == 0 })
        assertEquals(0, ruleset.units["Warrior"]!!.globalFaithPurchaseCooldown)
    }

    @Test
    fun `a unit is bought only in a city with the building of its class`() {
        fun needs(name: String) = ruleset.units[name]!!.uniques.filter { it.startsWith("Can only be purchased in cities with [") }
        assertEquals(listOf("Can only be purchased in cities with [Barracks]"), needs("Swordsman"))
        assertEquals(listOf("Can only be purchased in cities with [Lighthouse]"), needs("Trireme"))
        assertEquals(listOf("Can only be purchased in cities with [Military Academy]"), needs("Tank"))
        assertEquals(listOf("Can only be purchased in cities with [Caravansary]"), needs("Caravan"))
        assertEquals(2, needs("Fighter").size)
        assertEquals(emptyList<String>(), needs("Warrior") + needs("Archer"))
        // 94 units before the M4 activation, plus the 10 added VP units that have a row in Unit_BuildingClassPurchaseRequireds of ref/db/vp.sqlite (Field Gun, Fleet Destroyer,
        // Sensor Combat Ship, Liburna, Cruiser, Dreadnought, Attack Submarine, Rocket Missile, Fusta, Licorne; not Barbarian Horseman, Iron Chariot, Inquisition)
        assertEquals(94 + 10, ruleset.units.values.count { unit -> unit.uniques.any { it.startsWith("Can only be purchased in cities with [") } })
        // every named building is one of the ruleset
        val named = ruleset.units.values.flatMap { it.uniques }.filter { it.startsWith("Can only be purchased in cities with [") }.map { it.substringAfter("[").dropLast(1) }
        assertEquals(emptyList<String>(), named.filter { it !in ruleset.buildings })
    }

    @Test
    fun `the Piazza San Marco and its cooldown reduction wait for their package`() {
        assertFalse("Piazza San Marco" in ruleset.buildings)
        assertTrue(ruleset.buildings.values.all { it.purchaseCooldownReduction == 0 && it.purchaseCooldownReductionCivilian == 0 })
    }

    @Test
    fun `an upgrade costs the difference of the purchase prices, with the VP discount limit`() {
        val constants = ruleset.modOptions.constants
        assertTrue(constants.unitUpgradeCostFromPurchasePrice)
        assertEquals(-99, constants.unitUpgradeCostDiscountMax)
        assertFalse(ModConstants().unitUpgradeCostFromPurchasePrice)
        game.makeHexagonalMap(2)
        val civ = game.addCiv(isPlayer = true)   // a human: the AI handicap does not scale the price
        val archer = game.addUnit("Archer", civ, game.getTile(HexCoord.Zero))
        // Archer 70 and Composite Bowman 110 production: (4 x 70) ^ 0.88 = 142 and (4 x 110) ^ 0.88 = 211 gold, 69 apart, in steps of 5 (Brave New World's formula: 10 + 2 x 40 = 90)
        assertEquals(65, archer.upgrade.getCostOfUpgrade(ruleset.units["Composite Bowman"]!!))
    }
}
