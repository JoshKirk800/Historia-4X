package com.unciv.models.ruleset

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.tech.Era
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The production cost rules of Vox Populi that are not data: the world wonder cost increase and the discount of buildings of earlier eras
 *  (CvPlayer::getProductionNeeded, V:CvPlayer.cpp:15320-15496, `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`). A human civilization plays, so no AI percentage
 *  changes a cost.
 */
@RunWith(BaseTestRunner::class)
class BuildingCostRulesTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 2)
    }

    /** The ruleset's eras and speeds are shared with the other tests: what a test changes is put back */
    private val changedEras = ArrayList<Pair<Era, Int>>()
    private fun setLaterEraMod(era: Era, percent: Int) {
        changedEras.add(era to era.laterEraBuildingConstructMod)
        era.laterEraBuildingConstructMod = percent
    }
    @After
    fun restoreEras() { for ((era, percent) in changedEras) era.laterEraBuildingConstructMod = percent }

    private fun useSpeed(productionCostModifier: Float) {
        val speed = Speed().apply { name = "Cost test"; turns = game.gameInfo.speed.turns; this.productionCostModifier = productionCostModifier }
        game.ruleset.speeds[speed.name] = speed
        game.setSpeed(speed.name)
    }

    private fun setEra(number: Int) { civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == number } }
    private fun techOfEra(number: Int): String {
        val era = game.ruleset.eras.values.first { it.eraNumber == number }
        return game.ruleset.technologies.values.first { it.era() == era.name }.name
    }
    private fun building(cost: Int = 200, era: Int? = null) = game.createBuilding().apply { this.cost = cost; requiredTech = era?.let { techOfEra(it) } }
    private fun wonder(cost: Int = 200, era: Int? = null) = game.createWonder().apply { this.cost = cost; requiredTech = era?.let { techOfEra(it) } }
    private fun own(wonder: Building) = city.cityConstructions.addBuilding(wonder)

    //region V:CvPlayer.cpp:15337-15380, 15492

    @Test
    fun `the production cost rises 25 15 10 5 percent per owned world wonder by era distance`() {
        constants.worldWonderCostIncrease = listOf(25, 15, 10, 5)
        setEra(3)
        val target = wonder(1000)
        assertEquals("no wonder owned", 1000, target.getProductionCost(civ, city))

        own(wonder(era = 3)) // this era: 25
        assertEquals(1250, target.getProductionCost(civ, city))
        own(wonder(era = 2)) // one era ago: 15
        assertEquals(1400, target.getProductionCost(civ, city))
        own(wonder(era = 1)) // two eras ago: 10
        assertEquals(1500, target.getProductionCost(civ, city))
        own(wonder(era = 0)) // three: 5
        assertEquals(1550, target.getProductionCost(civ, city))
        own(wonder(era = 0)) // and every earlier one is 5
        assertEquals(1600, target.getProductionCost(civ, city))
    }

    @Test
    fun `only world wonders raise the cost, and only world wonders count`() {
        constants.worldWonderCostIncrease = listOf(25, 15, 10, 5)
        setEra(2)
        val national = game.createWonder().apply { cost = 200; isWonder = false; isNationalWonder = true; requiredTech = techOfEra(2) }
        own(national)
        own(building(era = 2))
        val noTech = wonder()
        own(noTech)
        assertEquals("a wonder with no technology adds nothing", 1000, wonder(1000).getProductionCost(civ, city))

        own(wonder(era = 2))
        assertEquals("not for a building", 400, building(400).getProductionCost(civ, city))
        assertEquals("not for a national wonder", 400, national.apply { cost = 400 }.getProductionCost(civ, city))
        assertEquals(1250, wonder(1000).getProductionCost(civ, city))
    }

    @Test
    fun `the wonders of every city of the civilization count`() {
        constants.worldWonderCostIncrease = listOf(25, 15, 10, 5)
        setEra(1)
        val other = game.addCity(civ, game.getTile(HexCoord(4, 0)), initialPopulation = 1)
        other.cityConstructions.addBuilding(wonder(era = 1))
        own(wonder(era = 0))

        assertEquals("25 and 15", 1400, wonder(1000).getProductionCost(civ, city))
    }

    @Test
    fun `a wonder of a later era than the civilization's counts as the earliest`() {
        constants.worldWonderCostIncrease = listOf(25, 15, 10, 5)
        setEra(1)
        own(wonder(era = 3))
        assertEquals(1050, wonder(1000).getProductionCost(civ, city))
    }

    @Test
    fun `without the constant nothing changes`() {
        setEra(3)
        own(wonder(era = 3))
        assertEquals(1000, wonder(1000).getProductionCost(civ, city))
    }

    //endregion
    //region V:CvPlayer.cpp:15385-15455

    @Test
    fun `buildings of earlier eras cost 2 percent less per era`() {
        for (era in game.ruleset.eras.values) setLaterEraMod(era, -2)
        setEra(3)

        assertEquals("this era: no discount", 1000, building(1000, era = 3).getProductionCost(civ, city))
        assertEquals("one era", 980, building(1000, era = 2).getProductionCost(civ, city))
        assertEquals("three eras", 940, building(1000, era = 0).getProductionCost(civ, city))
        assertEquals("a building without technology has them all", 940, building(1000).getProductionCost(civ, city))
        assertEquals("a technology of a later era is not an earlier one", 1000, building(1000, era = 5).getProductionCost(civ, city))
    }

    @Test
    fun `the eras add the modifiers of each of them`() {
        val eras = game.ruleset.eras.values.sortedBy { it.eraNumber }
        setLaterEraMod(eras[0], -5)
        setLaterEraMod(eras[1], -10)
        setLaterEraMod(eras[2], 20)
        setEra(3)

        assertEquals("-5 -10 +20", 1050, building(1000, era = 0).getProductionCost(civ, city))
        assertEquals("-10 +20", 1100, building(1000, era = 1).getProductionCost(civ, city))
        assertEquals("+20, and wonders get the discount too", 1200, wonder(1000, era = 2).getProductionCost(civ, city))
    }

    @Test
    fun `the discount comes before the wonder increase and both with the speed`() {
        for (era in game.ruleset.eras.values) setLaterEraMod(era, -10)
        constants.worldWonderCostIncrease = listOf(50)
        setEra(2)
        own(wonder(era = 2)) // the owned wonder: +50
        useSpeed(1.5f)

        // 1000 x 1.5 speed x 0.9 (era 1 to 2: one era of -10) x 1.5 wonder increase
        assertEquals(2025, wonder(1000, era = 1).getProductionCost(civ, city))
    }

    @Test
    fun `without the modifiers of the eras buildings cost what they did`() {
        setEra(5)
        assertEquals(1000, building(1000, era = 0).getProductionCost(civ, city))
        assertEquals(1000, building(1000).getProductionCost(civ, city))
    }

    //endregion
}
