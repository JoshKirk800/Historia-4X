package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Nuclear might after Vox Populi's CvPlayer::calculateNuclearMight (Release-5.4.8). */
@RunWith(GdxTestRunner::class)
class NuclearMightTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = civWithCapital(HexCoord(0, 0))
        b = civWithCapital(HexCoord(8, 0))
        c = civWithCapital(HexCoord(-8, 0))
        game.addUnit("Infantry", b, game.getTile(HexCoord(9, 0)))
        game.addUnit("Fighter", b, game.getTile(HexCoord(8, 0)))
    }

    private fun civWithCapital(position: HexCoord): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(position))
        return civ
    }

    private fun nuke(name: String, civ: Civilization = a) = game.addUnit(name, civ, civ.cities.first().getCenterTile())

    @Test
    fun `no nuclear weapons, no nuclear might`() {
        assertEquals(0, NuclearMight.of(a, b))
        assertEquals(0, NuclearMight.of(a, null))
    }

    @Test
    fun `a weapon counts the strongest land and air units it would destroy, a missile more than a bomb`() {
        nuke("Atomic Bomb")
        val infantry = b.units.getCivUnits().first { it.name == "Infantry" }.baseUnit.strength
        val fighter = b.units.getCivUnits().first { it.name == "Fighter" }.baseUnit.rangedStrength
        // blast radius 2: 18 tiles around the target; damage level 1 counts each twice
        assertEquals(infantry * 3 + fighter * 12 + infantry * 18 * 2, NuclearMight.of(a, b))
        val bomb = NuclearMight.of(a, b)
        a.units.getCivUnits().toList().forEach { it.destroy() }
        nuke("Nuclear Missile")
        assertTrue("level 2 counts the blast three times", NuclearMight.of(a, b) > bomb)
    }

    @Test
    fun `the might against one civilization looks at its units only`() {
        nuke("Atomic Bomb")
        assertEquals("c has no units", 0, NuclearMight.of(a, c))
        game.addUnit("Mechanized Infantry", c, game.getTile(HexCoord(-9, 0)))
        assertTrue("the global might takes the strongest of anyone's", NuclearMight.of(a, null) > NuclearMight.of(a, b))
    }

    @Test
    fun `bomb shelters reduce the might against their owner`() {
        nuke("Atomic Bomb")
        val unprotected = NuclearMight.of(a, b)
        // Vox Populi's Strategic Defense System has the Bomb Shelter's 75% (the Bomb Shelter is not in the ruleset)
        b.cities.first().cityConstructions.addBuilding("Strategic Defense System")
        val shelters = NuclearMight.Shelters.of(b)
        assertEquals(NuclearMight.Shelters(100, 75, 0), shelters)
        assertTrue(NuclearMight.of(a, b) < unprotected)
    }

    @Test
    fun `military might counts nuclear weapons by what they destroy, not as units`() {
        val before = a.ai.militaryMight()
        nuke("Atomic Bomb")
        game.gameInfo.turns++
        assertEquals(before + NuclearMight.of(a, null), a.ai.militaryMight())
        assertEquals(NuclearMight.of(a, null), a.ai.nuclearMight())
    }

    @Test
    fun `with equal conventional forces the side with nuclear weapons is the stronger`() {
        game.addUnit("Infantry", a, game.getTile(HexCoord(1, 0)))
        game.addUnit("Fighter", a, game.getTile(HexCoord(0, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        WarStateAi.doTurn(b)
        val withoutWeapons = b.ai.warStates.militaryStrengths[a.civID]!!
        nuke("Atomic Bomb")
        game.gameInfo.turns++
        WarStateAi.doTurn(b)
        assertTrue("$withoutWeapons, then ${b.ai.warStates.militaryStrengths[a.civID]}",
            b.ai.warStates.militaryStrengths[a.civID]!! > withoutWeapons)
    }
}
