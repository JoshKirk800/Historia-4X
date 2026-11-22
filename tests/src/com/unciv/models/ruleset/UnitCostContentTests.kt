package com.unciv.models.ruleset

import com.unciv.logic.city.PurchaseRules
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Unit costs of content flip A (`phaseE/rulegen.md` "Content flip A", D19: a cost is converted with the purchase curve that prices it). The units that Vox Populi 5.4.8 has a
 *  namesake of, with a cost (`Units.Cost` of `ref/db/vp.sqlite`), cost that in the Brave New World ruleset, and nothing else of a unit changes; the other units keep their cost.
 */
@RunWith(GdxTestRunner::class)
class UnitCostContentTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val ruleset = game.ruleset

    /** name|Vox Populi's cost of the units of the ruleset that have a namesake there with a cost */
    private val vpCosts = listOf(
        "Worker|80",
        "Archaeologist|450",
        "Caravan|90",
        "Scout|90",
        "Pathfinder|40",
        "Warrior|40",
        "Maori Warrior|135",
        "Jaguar|160",
        "Brute|40",
        "Archer|70",
        "Bowman|70",
        "Slinger|40",
        "Atlatlist|110",
        "Skirmisher|100",
        "Work Boats|40",
        "Cargo Ship|140",
        "Trireme|120",
        "Quinquereme|120",
        "Dromon|100",
        "Galley|70",
        "Chariot Archer|55",
        "War Chariot|55",
        "Horse Archer|100",
        "Spearman|70",
        "Hoplite|70",
        "Persian Immortal|70",
        "Pictish Warrior|70",
        "Horseman|90",
        "Cataphract|175",
        "Catapult|100",
        "Siege Tower|100",
        "Ballista|100",
        "Composite Bowman|110",
        "Swordsman|100",
        "Legion|100",
        "Mohawk Warrior|100",
        "Kris Swordsman|100",
        "Pikeman|135",
        "Impi|300",
        "Landsknecht|275",
        "Galleass|175",
        "Great Galleass|250",
        "Knight|175",
        "Camel Archer|175",
        "Conquistador|160",
        "Naresuan's Elephant|175",
        "Mandekalu Cavalry|175",
        "Crossbowman|160",
        "Chu-Ko-Nu|160",
        "Longbowman|160",
        "Trebuchet|175",
        "Longswordsman|160",
        "Samurai|160",
        "Berserker|135",
        "Caravel|160",
        "Nau|160",
        "Turtle Ship|185",
        "Musketman|325",
        "Musketeer|300",
        "Janissary|325",
        "Minuteman|325",
        "Tercio|300",
        "Sea Beggar|350",
        "Frigate|375",
        "Ship of the Line|375",
        "Lancer|350",
        "Winged Hussar|350",
        "Hakkapeliitta|350",
        "Cannon|350",
        "Gatling Gun|700",
        "Rifleman|900",
        "Carolean|625",
        "Cavalry|800",
        "Berber Cavalry|800",
        "Cossack|800",
        "Hussar|350",
        "Ironclad|900",
        "Artillery|1300",
        "Submarine|1300",
        "Foreign Legion|550",
        "Triplane|800",
        "Infantry|1300",
        "Carrier|1800",
        "Battleship|1800",
        "Machine Gun|950",
        "Bazooka|1800",
        "Anti-Aircraft Gun|950",
        "Landship|1000",
        "Destroyer|1300",
        "Fighter|1400",
        "Zero|1400",
        "Bomber|850",
        "B17|1500",
        "Paratrooper|950",
        "XCOM Squad|2600",
        "Tank|1800",
        "Atomic Bomb|3500",
        "Rocket Artillery|2250",
        "Mobile SAM|1800",
        "Guided Missile|1200",
        "Nuclear Missile|5600",
        "Helicopter Gunship|2250",
        "Nuclear Submarine|2500",
        "Mechanized Infantry|1800",
        "Missile Cruiser|2500",
        "Modern Armor|2500",
        "Jet Fighter|2100",
        "Giant Death Robot|3000",
        "Stealth Bomber|2200",
        "SS Booster|8000",
        "SS Cockpit|8000",
        "SS Engine|8000",
        "SS Stasis Chamber|8000"
    )

    @Test
    fun `the units that VP has a cost for cost what VP's cost, without a hurry modifier`() {
        for (line in vpCosts) {
            val (name, cost) = line.split("|")
            val unit = ruleset.units[name]!!
            assertEquals("$name cost", cost.toInt(), unit.cost)
            assertEquals("$name hurry cost modifier", 0, unit.hurryCostModifier)
        }
        assertEquals(113, vpCosts.size)
    }

    @Test
    fun `the units without a VP cost keep their Brave New World cost`() {
        val kept = mapOf("Settler" to 106, "War Elephant" to 70, "Marine" to 400, "Panzer" to 375, "Keshik" to 120, "Companion Cavalry" to 75)
        assertEquals(kept, kept.mapValues { ruleset.units[it.key]!!.cost })
    }

    @Test
    fun `units are priced at their own VP cost under the VP purchase curve`() {
        // Vox Populi's curve (ModOptions): (4 x production) ^ 0.88; with Brave New World's 40 production an Archer would cost 87 gold, with its own 70 it costs 142
        val constants = ruleset.modOptions.constants
        val speed = game.gameInfo.speed
        assertEquals(142.0, PurchaseRules.baseGoldPrice(ruleset.units["Archer"]!!.cost, constants, speed), 1.0)
        assertTrue(ruleset.units["Archer"]!!.cost > 40)
        // and a Tank (1800 production, Brave New World: 375) costs 2480 gold
        assertEquals(2480.0, PurchaseRules.baseGoldPrice(ruleset.units["Tank"]!!.cost, constants, speed), 1.0)
    }
}
