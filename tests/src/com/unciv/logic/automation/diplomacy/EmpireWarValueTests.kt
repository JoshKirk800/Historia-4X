package com.unciv.logic.automation.diplomacy

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class EmpireWarValueTests {
    @Test
    fun `the value of an empire is its cities and the value of each unit against the strongest unit of its domain`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5, Constants.grassland)
        val civ = game.addCiv()
        game.addCity(civ, game.tileMap[HexCoord(0, 0)], initialPopulation = 3)
        civ.tech.addTechnology("Military Strategy")
        civ.tech.addTechnology("Sailing")
        val units = mapOf("Warrior" to 1, "Archer" to 2, "Trireme" to 4, "Fighter" to 0, "Great General" to 3)
        game.setTileTerrain(HexCoord(4, 0), Constants.coast)
        for ((name, x) in units) game.addUnit(name, civ, game.tileMap[HexCoord(x, 0)])
        game.addUnit("Warrior", civ, game.tileMap[HexCoord(1, 1)])

        val civUnits = civ.units.getCivUnits().toList()
        val perUnit = civUnits.sumOf { if (it.baseUnit.getAiPower() > 0) WarDamageAi.unitWarValue(it.baseUnit, civ) else 0 }
        assertTrue("the test needs military units that are worth something: $perUnit", perUnit > 0)
        assertEquals(civ.cities.sumOf { WarDamageAi.cityWarValue(it) } + perUnit, WarDamageAi.empireWarValue(civ))
    }
}
