package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvDealAI::GetStrategicResourceValue and GetStrategicResourceItemList (Vox Populi, Community Patch part) */
@RunWith(GdxTestRunner::class)
class DealResourceValueTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    private val iron get() = game.ruleset.tileResources["Iron"]!!

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        for (x in listOf(a, b)) for (y in listOf(a, b)) if (x != y) x.ai.diplomacy.setApproach(y, MajorCivApproach.Neutral)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** Bronze Working reveals Iron, Iron Working trains the Swordsman */
    private fun learnIron() {
        a.tech.addTechnology("Bronze Working")
        a.tech.addTechnology("Iron Working")
    }

    private fun giveResource(civ: Civilization, resource: String, copies: Int) {
        civ.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [$copies] [$resource]"))
        civ.cache.updateCivResources()
    }

    private fun baseValue(civ: Civilization) =
        DealResourceValues.oneGptValue(civ, false) * game.ruleset.eras.size * DealResourceValues.techProgressPercent(civ) / 100

    /** The strongest unit that needs [resource] and that [civ] could train now: the head of the list of a buyer without buildings to build */
    private fun strongestUnitPower(civ: Civilization, resource: String) = game.ruleset.units.values
        .filter { civ.getEquivalentUnit(it.name) == it && it.getResourceRequirementsPerTurn()[resource] > 0 }
        .filter { unit -> unit.requiredTechs().all { civ.tech.isResearched(it) } && unit.techsAtWhichNoLongerAvailable().none { civ.tech.isResearched(it) } }
        .filter { !it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.SpaceshipPart) }
        .maxOf { it.getAiPower() }

    @Test
    fun `a resource we cannot see is never traded and a buyer with five copies pays nothing`() {
        assertNull(DealResourceValues.strategicValue(a, b, iron, 1, 30, false, 0))
        learnIron()
        giveResource(a, "Iron", 9)
        assertNotNull(DealResourceValues.strategicValue(a, b, iron, 1, 30, true, 0))
        assertEquals("a buyer with nine copies", 0, DealResourceValues.strategicValue(a, b, iron, 1, 30, false, 0))
    }

    @Test
    fun `a seller keeps what it needs and charges four and eight times the base for the last two copies it can spare`() {
        learnIron()
        val needed = DealResourceValues.strategicResourceItemList(a, iron, 30, true, 100, false).size
        assertTrue("setup: a unit needs iron", needed > 0)
        giveResource(a, "Iron", needed + 6)
        val base = baseValue(a)
        // Six copies beyond what the list needs: leaving 5, 4, 3 beyond costs the base each, the next (2 left) four times the
        // base, the one after (1 left) eight times
        assertEquals(base, DealResourceValues.strategicValue(a, b, iron, 1, 30, true, 0))
        assertEquals(3 * base + 4 * base, DealResourceValues.strategicValue(a, b, iron, 4, 30, true, 0))
        assertEquals(3 * base + 4 * base + 8 * base, DealResourceValues.strategicValue(a, b, iron, 5, 30, true, 0))
        assertNull("never the last of what it can spare", DealResourceValues.strategicValue(a, b, iron, 6, 30, true, 0))
        assertNull("nor the copies it needs", DealResourceValues.strategicValue(a, b, iron, needed + 6, 30, true, 0))
    }

    @Test
    fun `uranium costs twice as much and is not sold to a civ that nuked us`() {
        a.tech.addTechnology("Atomic Theory")
        giveResource(a, "Uranium", 60)
        val uranium = game.ruleset.tileResources["Uranium"]!!
        val base = baseValue(a)
        assertEquals(2 * base, DealResourceValues.strategicValue(a, b, uranium, 1, 30, true, 0))
        b.ai.diplomacy.memoryOf(a).numTimesNuked = 1
        assertEquals("they nuked us, not the other way round", 2 * base, DealResourceValues.strategicValue(a, b, uranium, 1, 30, true, 0))
        a.ai.diplomacy.memoryOf(b).numTimesNuked = 1
        assertNull(DealResourceValues.strategicValue(a, b, uranium, 1, 30, true, 0))
    }

    @Test
    fun `a buyer pays the weight of the units it could train for each copy, never under the base or over ten times it`() {
        learnIron()
        val list = DealResourceValues.strategicResourceItemList(a, iron, 30, false, 0, false)
        assertTrue(list.isNotEmpty())
        // Three entries a unit, each 10% less than the one before, the highest first
        assertEquals(list.sortedDescending(), list)
        val power = strongestUnitPower(a, "Iron")
        assertEquals(power * 30 / 50, list.first())
        val base = baseValue(a)
        assertEquals(minOf(maxOf(base, list[0]), 10 * base), DealResourceValues.strategicValue(a, b, iron, 1, 30, false, 0))
        // The net gold of the buyer raises it by sqrt / 2, at most a fifth
        val raised = list[0] + minOf((0.5 * kotlin.math.sqrt(400.0)).toInt(), list[0] / 5)
        assertEquals(minOf(maxOf(base, raised), 10 * base), DealResourceValues.strategicValue(a, b, iron, 1, 30, false, 400))
    }

    @Test
    fun `a buyer at war values what its units can do twice over`() {
        learnIron()
        val peace = DealResourceValues.strategicResourceItemList(a, iron, 30, false, 0, true)
        a.getDiplomacyManager(b)!!.declareWar()
        val war = DealResourceValues.strategicResourceItemList(a, iron, 30, false, 0, true)
        assertEquals(strongestUnitPower(a, "Iron") * 2 * 30 / 50, war.first())
        assertTrue(war.first() > peace.first())
    }

    @Test
    fun `a buyer counts the buildings it could build with the resource by their production weight and the city's economic value`() {
        a.tech.addTechnology("Industrialization")
        a.ai.setPersonalityFlavor(Flavor.Production, 100)
        val capital = a.getCapital()!!
        capital.cityConstructions.addBuilding("Workshop")
        val factory = game.ruleset.buildings["Factory"]!!
        val coal = game.ruleset.tileResources["Coal"]!!
        // The weight of the building / 100, x economic value x one GPT's value / 100000, x turns / 50
        val expected = (ProductionAi.buildingWeight(capital, factory) / 100).toLong() * AttackTargetsAi.economicValue(capital, a) *
            DealResourceValues.oneGptValue(a, false) / 100000 * 30 / 50
        assertTrue("setup: $expected", expected > 0)
        assertEquals(listOf(expected.toInt()), DealResourceValues.strategicResourceItemList(a, coal, 30, false, 0, false))
        capital.cityConstructions.addBuilding("Factory")
        assertTrue("built already", DealResourceValues.strategicResourceItemList(a, coal, 30, false, 0, false).isEmpty())
    }
}
