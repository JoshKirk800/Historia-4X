package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The rows of Vox Populi's diplomatic units with the texts the generator writes (`tools/rulegen`, slice P4.7b), through the engine: the units have their own type, Diplomacy, and a
 *  building's free promotion of that type (`Buildings.FreePromotion`, the Foreign Bureau's Diplomatic Immunity) goes to every such unit of the owner there is and to every new one
 *  (CvPlayer::ChangeFreePromotionCount, V:CvPlayer.cpp:40185-40215, V: is ref/vp-5.4.8/CvGameCoreDLL_Expansion2/), and raises their missions by its influence.
 */
@RunWith(GdxTestRunner::class)
class VpDiplomatPromotionRowsTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var capital: City
    private lateinit var other: City
    private lateinit var emissary: BaseUnit
    private lateinit var warrior: BaseUnit
    private val immunity = "Diplomatic Immunity"
    private val bureau = arrayOf("[Diplomacy] units gain the [$immunity] promotion", "All newly-trained [Diplomacy] units [in all cities] receive the [$immunity] promotion")

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.diplomaticMissionsLikeVp = true
        game.ruleset.unitTypes["Diplomacy"] = UnitType("Diplomacy", "Land")
        game.ruleset.unitPromotions[immunity] = Promotion().apply {
            name = immunity
            unitTypes = listOf("Diplomacy")
            uniques = arrayListOf("[10] Influence from diplomatic missions", "May enter foreign tiles without open borders")
        }
        emissary = game.createBaseUnit("Diplomacy", "Can undertake a diplomatic mission with City-State", "[40] Influence from diplomatic missions")
        warrior = game.createBaseUnit("Civilian")
        us = game.addCiv(isPlayer = true)
        capital = game.addCity(us, game.getTile(-6, 0), replacePalace = true)
        other = game.addCity(us, game.getTile(3, 0))
    }

    private fun unitAt(base: BaseUnit, x: Int, y: Int): MapUnit = game.addUnit(base.name, us, game.getTile(x, y))

    @Test
    fun `the building gives its promotion to the diplomatic units of every city there are, and not to the others`() {
        val home = unitAt(emissary, -5, 0)
        val away = unitAt(emissary, 4, 0).apply { originCityId = other.id }
        val civilian = unitAt(warrior, -4, 0)
        assertEquals(40, DiplomaticMission.baseOf(home))

        capital.cityConstructions.addBuilding(game.createBuilding(*bureau))

        assertTrue(home.promotions.promotions.contains(immunity))
        assertTrue("a unit of another city", away.promotions.promotions.contains(immunity))
        assertFalse(civilian.promotions.promotions.contains(immunity))
        assertEquals("the promotion's influence is added", 50, DiplomaticMission.baseOf(away))
    }

    @Test
    fun `a diplomatic unit trained in any city afterwards starts with it, another unit does not`() {
        capital.cityConstructions.addBuilding(game.createBuilding(*bureau))

        val trained = emissary.construct(other.cityConstructions, null)!!
        assertTrue(trained.promotions.promotions.contains(immunity))
        assertEquals(50, DiplomaticMission.baseOf(trained))
        assertFalse(warrior.construct(other.cityConstructions, null)!!.promotions.promotions.contains(immunity))
    }
}
