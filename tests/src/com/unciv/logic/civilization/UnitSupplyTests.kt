package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Only the units with the original's MilitarySupport use unit supply (CvPlayer::GetNumUnitsToSupply counts CvPlayer::getNumMilitaryUnits,
 *  which CvUnit::init raises for a unit whose CvUnitEntry::IsMilitarySupport is set): the combat units, the Caravan, the Cargo Ship and the
 *  Work Boat; not the workers, settlers, Great People or religious units.
 */
@RunWith(BaseTestRunner::class)
class UnitSupplyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var freeTiles: Iterator<Tile>
    private lateinit var seaTiles: Iterator<Tile>

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(7, Constants.grassland)
        // a pond for the ships
        for (t in game.getTile(HexCoord(5, 0)).getTilesInDistance(1)) game.setTileTerrain(t.position, Constants.coast)
        for (t in game.tileMap.values) t.setTerrainTransients()
        civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
        freeTiles = game.tileMap.values.filter { !it.isCityCenter() && it.baseTerrain != Constants.coast }.iterator()
        seaTiles = game.tileMap.values.filter { it.baseTerrain == Constants.coast }.iterator()
    }

    private fun add(name: String): MapUnit {
        val water = game.ruleset.units[name]!!.isWaterUnit
        val tile = if (water) seaTiles.next() else freeTiles.next()
        return game.addUnit(name, civ, tile)
    }

    private fun toSupply() = civ.units.getNumUnitsToSupply()

    @Test
    fun `workers, settlers, Great People and religious units use no supply`() {
        val supply = civ.stats.getUnitSupply()
        val civilians = listOf("Worker", "Settler", "Great Scientist", "Great General", "Great Prophet", "Missionary", "Inquisitor", "Archaeologist")
        for (name in civilians) add(name)
        assertEquals(civilians.size, civ.units.getCivUnitsSize())
        assertEquals(0, toSupply())
        // not even far more of them than the supply: nothing is out of supply
        repeat(supply + 5) { add("Worker") }
        assertEquals(0, toSupply())
        assertEquals(0, civ.stats.getUnitSupplyDeficit())
        assertEquals(0f, civ.stats.getUnitSupplyProductionPenalty(), 0f)
    }

    @Test
    fun `combat units, the Caravan, the Cargo Ship and the Work Boat use supply`() {
        var expected = 0
        for (name in listOf("Warrior", "Archer", "Trireme", "Caravan", "Work Boats", "Cargo Ship")) {
            add(name)
            expected++
            assertEquals(name, expected, toSupply())
        }
    }

    @Test
    fun `the ruleset marks exactly the Caravan, the Cargo Ship and the Work Boat among the units without a combat strength`() {
        val marked = game.ruleset.units.values.filter { !it.isMilitary && it.usesUnitSupply }.map { it.name }.sorted()
        assertEquals(listOf("Caravan", "Cargo Ship", "Work Boats"), marked)
        assertTrue(game.ruleset.units.values.filter { it.isMilitary }.all { it.usesUnitSupply })
    }

    @Test
    fun `the unique marks a civilian unit as using supply`() {
        val marked = game.createBaseUnit("Civilian", "Uses unit supply")
        val unmarked = game.createBaseUnit("Civilian")
        assertTrue(marked.usesUnitSupply)
        assertFalse(unmarked.usesUnitSupply)
        game.addUnit(unmarked.name, civ, freeTiles.next())
        assertEquals(0, toSupply())
        val unit = game.addUnit(marked.name, civ, freeTiles.next())
        assertTrue(unit.usesUnitSupply())
        assertEquals(1, toSupply())
    }

    @Test
    fun `the deficit is the units that use supply beyond the supply, whatever else the civilization has`() {
        val supply = civ.stats.getUnitSupply()
        assertTrue("supply $supply", supply in 1..60)
        val warriors = ArrayList<MapUnit>()
        repeat(supply) { warriors += add("Warrior") }
        assertEquals(supply, toSupply())
        assertEquals("exactly at the supply", 0, civ.stats.getUnitSupplyDeficit())

        // a Caravan uses supply: the first one over
        add("Caravan")
        assertEquals(1, civ.stats.getUnitSupplyDeficit())
        assertEquals(-10f, civ.stats.getUnitSupplyProductionPenalty(), 0f)

        // workers do not
        repeat(10) { add("Worker") }
        assertEquals(1, civ.stats.getUnitSupplyDeficit())

        warriors += add("Warrior")
        assertEquals(2, civ.stats.getUnitSupplyDeficit())
        assertEquals(-20f, civ.stats.getUnitSupplyProductionPenalty(), 0f)

        // a unit that goes lowers the count again
        warriors.last().destroy()
        assertEquals(1, civ.stats.getUnitSupplyDeficit())
        assertEquals(supply + 1, toSupply())
    }

    @Test
    fun `a city loses production to the deficit of units that use supply, not to workers`() {
        val city = civ.cities.first()
        val supply = civ.stats.getUnitSupply()
        repeat(supply + 20) { add("Worker") }
        city.cityStats.update()
        assertFalse(city.cityStats.statPercentBonusTree.children.containsKey("Unit Supply"))

        repeat(supply + 1) { add("Warrior") }
        city.cityStats.update()
        assertEquals(-10f, city.cityStats.statPercentBonusTree.children["Unit Supply"]!!.totalStats.production, 0f)
    }
}
