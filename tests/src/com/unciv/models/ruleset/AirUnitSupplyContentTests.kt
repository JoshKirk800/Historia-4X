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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 `Units.NoSupply` (UnitSweeps.sql: every unit of a combat class with `IsAerial = 1` except `UNITCOMBAT_NUKE`, plus the Fusta; `IsAerial` is set for fighters and bombers in
 *  CoreUnitChanges.sql and for missiles and nukes in UnitChanges.sql) in the Brave New World ruleset: such a unit is not counted by CvPlayer::GetNumUnitsToSupply and is not refused by
 *  CvCity::canTrain at the supply limit (CvUnit::isNoSupply, V:CvCity.cpp:8696-8703). Our Great War Bomber is VP's WWI Bomber (the same class, the same rule). VP's SPAD has no unit here.
 */
@RunWith(GdxTestRunner::class)
class AirUnitSupplyContentTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val ruleset = game.ruleset

    @After
    fun restoreTable() = SupplyTable.restore(ruleset)

    private val aerialNoSupply = listOf(
        "Triplane", "Great War Bomber", "Fighter", "Zero", "Bomber", "B17", "Jet Fighter", "Stealth Bomber", // fighters and bombers
        "Guided Missile", "Rocket Missile", // missiles
    )

    @Test
    fun `every aircraft and missile but the nuclear missile uses no supply, and so does the Fusta`() {
        for (name in aerialNoSupply + "Fusta") assertTrue(name, ruleset.units[name]!!.hasNoUnitSupply)
        assertFalse("VP: nukes do cost supply", ruleset.units["Nuclear Missile"]!!.hasNoUnitSupply)
        assertFalse("the Atomic Bomb is the other nuke", ruleset.units["Atomic Bomb"]!!.hasNoUnitSupply)
        // nothing else: no land or sea unit, and no helicopter, carries it
        val flagged = ruleset.units.values.filter { it.hasNoUnitSupply }.map { it.name }.toSet()
        assertEquals((aerialNoSupply + "Fusta").toSet(), flagged)
        // the kinds really are the aerial ones the sweep is by
        for (name in aerialNoSupply) assertTrue(name, ruleset.units[name]!!.isAirUnit())
    }

    @Test
    fun `an AI at its supply limit still trains a fighter and a bomber, and not a land unit`() {
        SupplyTable.communityPatch(ruleset)
        val constants = ruleset.modOptions.constants
        constants.unitSupplyLikeVp = true
        constants.unitSupplyAiAtLimitLikeVp = true
        game.makeHexagonalMap(8, Constants.grassland)
        val origin = game.getTile(HexCoord(0, 0))
        val tiles = game.tileMap.values.filter { it.aerialDistanceTo(origin) in 1..4 }.iterator()
        val civ: Civilization = game.addCiv(isPlayer = false)
        game.addCity(civ, game.getTile(HexCoord(7, 0)), initialPopulation = 1)
        repeat(civ.stats.getUnitSupply()) { game.addUnit("Warrior", civ, tiles.next()) }
        assertEquals("the civ is exactly at its supply", civ.stats.getUnitSupply(), civ.units.getNumUnitsToSupply())

        fun refusedForSupply(name: String) =
            ruleset.units[name]!!.getRejectionReasons(civ, civ.cities.first()).any { it.type == RejectionReasonType.AtUnitSupplyLimit }
        assertTrue("a Swordsman uses supply", refusedForSupply("Swordsman"))
        for (name in listOf("Fighter", "Bomber", "Triplane", "Jet Fighter", "Great War Bomber")) assertFalse(name, refusedForSupply(name))

        // and an aircraft (in the city: an air unit can not stand on open land) does not take a place in the supply
        val before = civ.units.getNumUnitsToSupply()
        game.addUnit("Fighter", civ, civ.cities.first().getCenterTile())
        assertEquals(1, civ.units.getCivUnits().count { it.name == "Fighter" })
        assertEquals("the Fighter is not counted", before, civ.units.getNumUnitsToSupply())
    }
}
