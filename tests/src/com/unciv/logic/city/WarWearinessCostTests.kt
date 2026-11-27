package com.unciv.logic.city

import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 makes a combat unit dearer to buy by the war weariness: CvPlayer::GetUnitCostIncreaseFromWarWeariness (V:CvPlayer.cpp:37106-37109) is the highest war
 *  weariness percent x 75 (0 or more) / 100, and CvCity::GetPurchaseCost and GetFaithPurchaseCost multiply a combat unit's price by (100 + it) / 100 before the price is cut to a
 *  multiple of the visible divisor (V:CvCity.cpp:11874-11883, 12175-12184). Here it is `unitCostWarWearinessLikeVp` with `warWearinessLikeVp`.
 *
 *  The empire's cached war value is set to 650, so a weariness w against an enemy is 100 w / 650 percent (58 -> 8, 130 -> 20, 260 -> 40, 2000 -> 100) and the increase is that x 75 / 100
 *  (8 -> 6, 20 -> 15, 40 -> 30, 100 -> 75). The gold price is `(4 x 100) ^ 0.88` = 194.9 with the divisor 1; the engine's price is a double that is cut once at the end, so 194.9 x 130 / 100 =
 *  253.37 is 253 (the source cuts the price first, then the percent: 194 x 130 / 100 = 252; that is the departure noted in [com.unciv.models.ruleset.unit.BaseUnitCost]).
 */
@RunWith(BaseTestRunner::class)
class WarWearinessCostTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var city: City
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        constants.warWearinessLikeVp = true
        constants.unitCostWarWearinessLikeVp = true
        constants.purchase = ModConstants.PurchaseConstants(goldPerProduction = 4.0, exponent = 0.88, visibleDivisor = 1)
        a = game.addCiv("May buy [Military] units for [200] [Faith] [in all cities]", isPlayer = true)
        b = game.addCiv()
        city = game.addCity(a, game.getTile(HexCoord.Zero), initialPopulation = 3)
        game.addCity(b, game.getTile(HexCoord(6, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.diplomacy.cachedWarValue = 650
    }

    private fun weary(weariness: Int) {
        a.ai.diplomacy.memoryOf(b).warWeariness = weariness
    }

    private fun unit(strength: Int = 8, rangedStrength: Int = 0, cost: Int = 100, vararg uniques: String): BaseUnit =
        game.createBaseUnit("Sword", *uniques).apply { this.cost = cost; this.strength = strength; this.rangedStrength = rangedStrength; movement = 2 }

    private fun gold(unit: BaseUnit) = unit.getStatBuyCost(city, Stat.Gold)!!
    private fun faith(unit: BaseUnit) = unit.getStatBuyCost(city, Stat.Faith)!!

    @Test
    fun `the increase is the weariness percent times 75 over 100, cut`() {
        weary(58)
        assertEquals("100 x 58 / 650 = 8, 8 x 75 / 100 = 6", 6, WarDamageAi.unitCostIncreasePercent(a))
        weary(130)
        assertEquals("20 x 75 / 100 = 15", 15, WarDamageAi.unitCostIncreasePercent(a))
        weary(260)
        assertEquals("40 x 75 / 100 = 30", 30, WarDamageAi.unitCostIncreasePercent(a))
        weary(2000)
        assertEquals("100 x 75 / 100 = 75", 75, WarDamageAi.unitCostIncreasePercent(a))
        constants.warWeariness = constants.warWeariness.copy(unitCostPercent = 10)
        assertEquals("100 x 10 / 100", 10, WarDamageAi.unitCostIncreasePercent(a))
        constants.warWeariness = constants.warWeariness.copy(unitCostPercent = -50)
        assertEquals("a negative share is 0", 0, WarDamageAi.unitCostIncreasePercent(a))
    }

    @Test
    fun `a combat unit's gold price rises by the increase, cut once at the end`() {
        val sword = unit()
        assertEquals("(4 x 100) ^ 0.88 = 194.9 cut", 194, gold(sword))
        weary(260)
        assertEquals("194.9 x 130 / 100 = 253.37 (252 if the price were cut first, 255 if 30 were 31)", 253, gold(sword))
        weary(2000)
        assertEquals("194.9 x 175 / 100 = 341.07", 341, gold(sword))
    }

    @Test
    fun `the faith price rises by the same percent`() {
        val sword = unit()
        assertEquals("the unique's flat price", 200, faith(sword))
        weary(260)
        assertEquals("200 x 130 / 100", 260, faith(sword))
        weary(130)
        assertEquals("200 x 115 / 100", 230, faith(sword))
        weary(58)
        assertEquals("200 x 106 / 100", 212, faith(sword))
    }

    @Test
    fun `the faith price of a unit without strength does not rise, that of a nuclear weapon does`() {
        // the unique that prices Military units does not see a unit without strength, so each is priced by its name
        val plain = unit(strength = 0)
        val nuke = unit(strength = 0, uniques = arrayOf("Nuclear weapon of Strength [50]"))
        val buyer = game.addCiv(
            "May buy [${plain.name}] units for [200] [Faith] [in all cities]",
            "May buy [${nuke.name}] units for [200] [Faith] [in all cities]", isPlayer = true
        )
        val buyerCity = game.addCity(buyer, game.getTile(HexCoord(-6, 0)), initialPopulation = 3)
        buyer.diplomacyFunctions.makeCivilizationsMeet(b)
        buyer.getDiplomacyManager(b)!!.declareWar()
        buyer.ai.diplomacy.cachedWarValue = 650
        buyer.ai.diplomacy.memoryOf(b).warWeariness = 260
        assertEquals("bCombat is false: no strength, no ranged strength, no nuclear weapon", 200, plain.getStatBuyCost(buyerCity, Stat.Faith))
        assertEquals("a nuclear weapon: 200 x 130 / 100", 260, nuke.getStatBuyCost(buyerCity, Stat.Faith))
    }

    @Test
    fun `a ranged unit pays it too, a civilian and a settler do not`() {
        val archer = unit(strength = 0, rangedStrength = 6)
        val worker = unit(strength = 0)
        val settler = game.ruleset.units["Settler"]!!
        val archerPlain = gold(archer)
        val workerPlain = gold(worker)
        val settlerPlain = gold(settler)
        weary(260)
        assertTrue("a ranged unit: ${gold(archer)} > $archerPlain", gold(archer) > archerPlain)
        assertEquals("a unit without strength", workerPlain, gold(worker))
        assertEquals("a settler", settlerPlain, gold(settler))
    }

    @Test
    fun `nothing changes without the switch of the cost or of the weariness, or for a civilization without a war`() {
        val sword = unit()
        weary(260)
        assertEquals(253, gold(sword))
        constants.unitCostWarWearinessLikeVp = false
        assertEquals(194, gold(sword))
        assertEquals(0, WarDamageAi.unitCostIncreasePercent(a))
        constants.unitCostWarWearinessLikeVp = true
        constants.warWearinessLikeVp = false
        assertEquals("no weariness without its own switch", 194, gold(sword))
        constants.warWearinessLikeVp = true
        assertEquals(253, gold(sword))
        assertEquals("b has no weariness, so it pays the plain price", 194, sword.getStatBuyCost(b.cities.first(), Stat.Gold)!!)
    }

    @Test
    fun `a war that cannot end counts for nothing, the weariness of one that can does`() {
        val sword = unit()
        weary(260)
        a.ai.diplomacy.memoryOf(b).turnsLockedIntoWar = 3
        assertEquals("locked into the war: left out", 194, gold(sword))
        a.ai.diplomacy.memoryOf(b).turnsLockedIntoWar = 0
        assertEquals(253, gold(sword))
    }
}
