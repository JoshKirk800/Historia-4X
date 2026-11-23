package com.unciv.logic.civilization

import com.unciv.logic.battle.BattleConstants
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.ResourceShortage
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BALANCE_RESOURCE_SHORTAGE_UNIT_HEALING and BALANCE_RESOURCE_SHORTAGE_BUILDING_REFUND (ModConstants `resourceShortageLikeVp`):
 * CvUnit::GetStrategicResourceCombatPenalty (0), CvUnit::canHeal and IsCannotHeal (no healing while short of what the unit needs),
 * CvCity::DoTurn (a building uses up the shortage, 2 percent of building maintenance per copy) and CvTreasury::GetBuildingGoldMaintenance.
 */
@RunWith(BaseTestRunner::class)
class ResourceShortageTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        game.setDifficulty("Prince") // a human of this level pays 100 % of the building cost
        civ = game.addCiv(isPlayer = true)
        enemy = game.addCiv()
        city = game.addCity(civ, game.getTile(HexCoord.Zero), true)
    }

    private fun flag(on: Boolean) {
        game.ruleset.modOptions.constants.resourceShortageLikeVp = on
    }

    private fun strengthModifier(unit: MapUnit): Int {
        val defender = game.addUnit("Warrior", enemy, game.getTile(-2, 0))
        return BattleDamage.getAttackModifiers(MapUnitCombatant(unit), MapUnitCombatant(defender), unit.getTile())["Missing resource"]
    }

    @Test
    fun `a unit that needs Iron does not heal while its civilization is short of Iron and keeps full strength`() {
        flag(true)
        val swordsman = game.addUnit("Swordsman", civ, city.getCenterTile())
        val worker = game.addUnit("Worker", civ, city.getCenterTile())
        swordsman.health = 50
        worker.health = 50
        civ.cache.updateCivResources()
        assertEquals("1 Iron used, none supplied", -1, civ.getCivResourcesByName()["Iron"])

        assertEquals("the unit that needs Iron does not heal", 0, swordsman.getHealAmountForCurrentTile())
        assertEquals("a unit that needs nothing heals in a city as before", 25, worker.getHealAmountForCurrentTile())
        assertEquals("CvUnit::GetStrategicResourceCombatPenalty is 0", 0, strengthModifier(swordsman))

        city.cityConstructions.addBuilding(game.createBuilding("Provides [1] [Iron]"))
        civ.cache.updateCivResources()
        assertEquals("with the Iron it needs the unit heals", 25, swordsman.getHealAmountForCurrentTile())
    }

    @Test
    fun `a building that uses Iron is refunded the shortage and adds 2 percent to building maintenance per refunded copy`() {
        flag(true)
        val forge = game.createBuilding("Consumes [2] [Iron]")
        forge.maintenance = 100
        city.cityConstructions.addBuilding(forge)
        val mine = game.createBuilding("Provides [1] [Iron]")
        city.cityConstructions.addBuilding(mine)
        fun maintenance(): Int {
            civ.cache.updateCivResources()
            ResourceShortage.refundBuildings(civ)
            city.cityStats.update()
            return -city.cityStats.finalStatList["Maintenance"]!!.gold.toInt()
        }

        // CvCity::DoTurn: 2 Iron used, 1 supplied: the shortage is 1, min(1, 2) copies refunded x 2 percent
        assertEquals(102, maintenance())
        // no Iron at all: min(2, 2) copies, 4 percent
        city.cityConstructions.removeBuilding(mine)
        assertEquals(104, maintenance())
        // enough Iron: no shortage, the penalty is set back to 0 (SetExtraBuildingMaintenance(0))
        city.cityConstructions.addBuilding(game.createBuilding("Provides [2] [Iron]"))
        assertEquals(100, maintenance())
    }

    @Test
    fun `the flag off keeps BNW's penalty`() {
        flag(false)
        val swordsman = game.addUnit("Swordsman", civ, city.getCenterTile())
        swordsman.health = 50
        civ.cache.updateCivResources()
        assertEquals("1 used, none supplied: the whole malus", BattleConstants.MISSING_RESOURCES_MALUS, strengthModifier(swordsman))
        assertTrue("the unit heals in a city whatever it lacks", swordsman.getHealAmountForCurrentTile() > 0)

        val forge = game.createBuilding("Consumes [2] [Iron]")
        forge.maintenance = 100
        city.cityConstructions.addBuilding(forge)
        civ.cache.updateCivResources()
        ResourceShortage.refundBuildings(civ)
        city.cityStats.update()
        assertEquals("no refund, no extra maintenance", -100, city.cityStats.finalStatList["Maintenance"]!!.gold.toInt())
    }
}
