package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 CvCity::canTrain (V:CvCity.cpp:8696-8703) with `unitSupplyAiAtLimitLikeVp`: a city whose production the AI decides trains and buys no combat unit that uses supply
 *  once `GetNumUnitsToSupply() < GetNumUnitsSupplied()` is false, that is AT the supply and not only over it. The numbers are the civ's own: its supply is read from the game
 *  ([Civilization.stats]) and the units are added up to it, so the strict comparison is tested at supply - 1, supply and supply + 1 whatever the difficulty table says.
 */
@RunWith(BaseTestRunner::class)
class VpUnitSupplyAtLimitTests {
    private lateinit var game: TestGame
    private lateinit var freeTiles: Iterator<Tile>

    @After
    fun restoreTable() = SupplyTable.restore(game.ruleset)

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        SupplyTable.communityPatch(game.ruleset)
        val constants = game.ruleset.modOptions.constants
        constants.unitSupplyLikeVp = true
        constants.unitSupplyAiAtLimitLikeVp = true
        game.makeHexagonalMap(8, Constants.grassland)
        val origin = game.getTile(HexCoord(0, 0))
        freeTiles = game.tileMap.values.filter { it.aerialDistanceTo(origin) in 1..4 }.iterator()
    }

    /** The cities of the civs of a test are far from each other (the hexagonal map has radius 8) and from the tiles the units stand on, which are within 4 of the origin */
    private val cityTiles = listOf(HexCoord(7, 0), HexCoord(-7, 0), HexCoord(0, 7), HexCoord(0, -7), HexCoord(7, -7), HexCoord(-7, 7)).iterator()

    /** A civ with one city of one citizen, and so many warriors that [extra] units are over (negative: under) its supply */
    private fun civWithUnits(isPlayer: Boolean, extra: Int, cityState: String? = null): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer, cityStateType = cityState)
        game.addCity(civ, game.getTile(cityTiles.next()), initialPopulation = 1)
        val units = civ.stats.getUnitSupply() + extra
        repeat(units) { game.addUnit("Warrior", civ, freeTiles.next()) }
        assertEquals(units, civ.units.getNumUnitsToSupply())
        return civ
    }

    private fun reasons(civ: Civilization, unitName: String) =
        game.ruleset.units[unitName]!!.getRejectionReasons(civ, civ.cities.first()).map { it.type }.toList()

    private fun atLimit(civ: Civilization, unitName: String = "Warrior") = RejectionReasonType.AtUnitSupplyLimit in reasons(civ, unitName)

    @Test
    fun `an AI trains below its supply, and not at it nor over it`() {
        val below = civWithUnits(false, -1)
        assertFalse("one unit under the supply: 5 < 6 is true", atLimit(below))

        val at = civWithUnits(false, 0)
        assertTrue("the units to supply are as many as the supply: VP's '< ' is false", atLimit(at))

        val over = civWithUnits(false, 1)
        assertTrue(atLimit(over))
    }

    @Test
    fun `a human player is not held by it`() {
        val human = civWithUnits(true, 0)
        assertFalse(atLimit(human))
        assertFalse(atLimit(civWithUnits(true, 3)))
    }

    @Test
    fun `a city-state is the AI's`() {
        assertTrue(atLimit(civWithUnits(false, 0, cityState = "Cultured")))
    }

    @Test
    fun `a unit that uses no supply or has no strength is still trained at the limit`() {
        val civ = civWithUnits(false, 0)
        assertTrue(atLimit(civ, "Warrior"))
        assertFalse("a Worker has no combat strength", atLimit(civ, "Worker"))
        assertFalse("a Settler has none", atLimit(civ, "Settler"))

        val noSupply = game.createBaseUnit(game.createUnitType().name, "Does not use unit supply")
        noSupply.strength = 10
        assertFalse("a combat unit with the unique does not use supply", atLimit(civ, noSupply.name))

        val ranged = game.createBaseUnit()
        ranged.rangedStrength = 10
        assertTrue("a ranged strength alone counts, as GetRangedCombat() > 0 does", atLimit(civ, ranged.name))
    }

    @Test
    fun `without either switch the AI is held only by the limit over the supply`() {
        val civ = civWithUnits(false, 0)
        assertTrue(atLimit(civ))

        game.ruleset.modOptions.constants.unitSupplyAiAtLimitLikeVp = false
        assertFalse(atLimit(civ))

        game.ruleset.modOptions.constants.unitSupplyAiAtLimitLikeVp = true
        game.ruleset.modOptions.constants.unitSupplyLikeVp = false
        // the original's supply is another number: put the civ AT it, so that only the missing switch of Vox Populi's supply keeps it from being held
        val original = civ.stats.getUnitSupply()
        while (civ.units.getNumUnitsToSupply() < original) game.addUnit("Warrior", civ, freeTiles.next())
        assertEquals(original, civ.units.getNumUnitsToSupply())
        assertFalse("the check belongs to the supply of Vox Populi", atLimit(civ))
    }

    /** A barbarian's supply is Int.MAX_VALUE with Vox Populi's supply, so it is never at a limit; the barbarian exemption of CvCity::canTrain is a second guard that no state reaches (mutant L4 is equivalent) */
    @Test
    fun `barbarians are never held`() {
        val barbarian = game.addBarbarianCiv()
        game.addCity(barbarian, game.getTile(HexCoord(0, 0)), initialPopulation = 1)
        repeat(30) { game.addUnit("Warrior", barbarian, freeTiles.next()) }
        assertFalse(atLimit(barbarian))
    }

    @Test
    fun `the AI at its limit cannot buy a combat unit either`() {
        val civ = civWithUnits(false, 0)
        val city = civ.cities.first()
        assertFalse(game.ruleset.units["Warrior"]!!.canBePurchasedWithStat(city, Stat.Gold))
        assertTrue("a worker can be", game.ruleset.units["Worker"]!!.canBePurchasedWithStat(city, Stat.Gold))
    }

    @Test
    fun `an upgrade is not training and is not held`() {
        val civ = civWithUnits(false, 0)
        val better = game.createBaseUnit(game.createUnitType().name)
        better.strength = 20
        assertTrue("the upgrade target would be refused as a new unit", atLimit(civ, better.name))
        val warrior = civ.units.getCivUnits().first()
        assertEquals(UpgradeAvailability.Available, warrior.upgrade.canUpgrade(better))
    }
}
