package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's CvMilitaryAI::DisbandObsoleteUnits ([MilitaryAi.disbandObsoleteUnits]) */
@RunWith(GdxTestRunner::class)
class DisbandUnitsTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        game.gameInfo.turns = 30
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    /** A sea of 19 tiles around (4, 0) */
    private fun makeSea() {
        for (t in tile(4, 0).getTilesInDistance(2)) game.setTileTerrain(t.position, Constants.coast)
        for (t in game.tileMap.values) t.setTerrainTransients()
    }

    private fun count(name: String) = civ.units.getCivUnits().count { it.name == name }

    @Test
    fun `a combat ship on water no one else uses is scrapped, one sharing it with another civilization's city is kept`() {
        makeSea()
        game.addCity(civ, tile(0, 0))
        game.addUnit("Trireme", civ, tile(4, 0))
        civ.ai.military.disbandObsoleteUnits(civ)
        assertEquals(0, count("Trireme"))

        game.addUnit("Trireme", civ, tile(4, 0))
        game.addCity(game.addCiv(), tile(7, 0))
        civ.ai.military.disbandObsoleteUnits(civ)
        assertEquals(1, count("Trireme"))
    }

    @Test
    fun `obsolete units are scrapped only when more than one over the supply`() {
        game.addCity(civ, tile(0, 0))
        val base = game.createBaseUnit("Sword")
        base.movement = 2
        base.strength = 1
        base.obsoleteTech = "Pottery"
        civ.tech.addTechnology("Pottery")
        val supply = civ.stats.getUnitSupply()
        assertTrue("supply $supply", supply in 1..60)
        val free = game.tileMap.values.filter { !it.isCityCenter() }.iterator()
        repeat(supply + 1) { game.addUnit(base.name, civ, free.next()) }
        civ.ai.military.disbandObsoleteUnits(civ)
        assertEquals("one over the supply", supply + 1, count(base.name))

        game.addUnit(base.name, civ, free.next())
        civ.ai.military.disbandObsoleteUnits(civ)
        assertEquals("two over the supply: one scrapped", supply + 1, count(base.name))
    }

    @Test
    fun `units are kept in the first 25 turns`() {
        makeSea()
        game.addCity(civ, tile(0, 0))
        game.addUnit("Trireme", civ, tile(4, 0))
        game.gameInfo.turns = 25
        civ.ai.military.disbandObsoleteUnits(civ)
        assertEquals(1, count("Trireme"))
    }
}
