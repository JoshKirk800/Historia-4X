package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  Decision-level checks of CvPlayerAI::AI_doTurnUnitsPost / CvUnit::AI_promote / CvUnit::AI_promotionValue (PromotionAi.kt) against the
 *  DLL formulas with the BNW XML values (Shock 15/15 open, Drill 15/15 rough, Accuracy 15 open ranged, Barrage 15 rough ranged, ...).
 *  The rating adds a roll of 0 to 14 to every positive value, so values are checked as the formula up to 14 more.
 */
@RunWith(GdxTestRunner::class)
class PromotionAiTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 3)
        civ.ai.setPersonalityFlavor(Flavor.Ranged, 4)
        civ.ai.setPersonalityFlavor(Flavor.Air, 2)
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
    }

    private fun unit(name: String, at: HexCoord = HexCoord(1, 0)): MapUnit = game.addUnit(name, civ, game.getTile(at))

    private fun value(unit: MapUnit, promotion: String): Int =
        PromotionAi.promotionValue(unit, game.ruleset.unitPromotions[promotion]!!, Random(7))

    private fun assertValue(formula: Int, actual: Int, message: String) =
        assertTrue("$message: $formula to ${formula + 14}, was $actual", actual in formula..formula + 14)

    @Test
    fun `open and rough terrain promotions follow the unit's defensive bonus`() {
        // A swordsman (unit AI attack) fortifies: Shock = open attack and defense 15 + flavor x 2 each, Drill's rough terrain doubled
        val swordsman = unit("Swordsman")
        assertValue(15 + 5 * 2 + 15 + 3 * 2, value(swordsman, "Shock I"), "Shock I")
        assertValue(15 * 2 + 5 * 2 + 15 * 2 + 3 * 2, value(swordsman, "Drill I"), "Drill I")
        // A horseman has no defensive bonus: the open terrain is doubled instead
        val horseman = unit("Horseman", HexCoord(0, 1))
        assertValue(15 * 2 + 5 * 2 + 15 * 2 + 3 * 2, value(horseman, "Shock I"), "Shock I of a horseman")
        assertValue(15 + 5 * 2 + 15 + 3 * 2, value(horseman, "Drill I"), "Drill I of a horseman")
    }

    @Test
    fun `what the unit has makes the next tier worth more by twice its extra`() {
        val swordsman = unit("Swordsman")
        swordsman.promotions.addPromotion("Shock I", isFree = true)
        // 15 x (100 + 15 x 2) / 100 = 19 in both, open attack and open defense
        assertValue(19 + 5 * 2 + 19 + 3 * 2, value(swordsman, "Shock II"), "Shock II")
    }

    @Test
    fun `ranged promotions need a ranged unit and use the ranged flavor`() {
        val archer = unit("Archer")
        // The row of AI.json (open 15 / rough 15, + ranged flavor x 2) plus the term of Vox Populi's promotions, which the ruleset now has: Accuracy I has
        // `<vs units at or below half health>` +10: 10 x (defense 3 + 2 x offense 5) x 0.25 (a shooter) = 32; Barrage I `<vs units above half health>` +10:
        // 10 x (defense 3 + 2 x city defense 0) x 0.45 = 13 (the test of Vox Populi's terms below has them alone)
        assertValue(15 + 4 * 2 + 32, value(archer, "Accuracy I"), "Accuracy I")
        assertValue(15 * 2 + 4 * 2 + 13, value(archer, "Barrage I"), "Barrage I")
        val swordsman = unit("Swordsman", HexCoord(0, 1))
        // The row of AI.json rates nothing for a unit that cannot shoot; what stays is the health term, 10 x (3 + 2 x 5) x 0.4 for a melee unit
        assertValue(52, value(swordsman, "Accuracy I"), "Accuracy I of a melee unit")
    }

    @Test
    fun `Vox Populi's terms rate what the uniques of a promotion do, those with a switch only while it is on`() {
        // CvUnit::AI_promotionValue (VP 5.4.8) for promotions with no row in AI.json: only the terms of PromotionAi.vpUniqueValue
        val belowHalf = game.createUnitPromotion("[+10]% Strength <vs units at or below half health>")
        val archer = unit("Archer")
        val swordsman = unit("Swordsman", HexCoord(0, 1))
        // AttackBelowEqual50HealthMod x (defense 3 + 2 x offense 5) x 0.25 for a shooter, 0.4 for the others
        assertValue(32, value(archer, belowHalf.name), "below half health, a shooter")
        assertValue(52, value(swordsman, belowHalf.name), "below half health")

        val hitPoints = game.createUnitPromotion("[+25] maximum hit points")
        // MaxHitPointsChange: a shooter half of 25 x (offense 5 + defense 3 + city defense 0), the others 25 x (2 x offense 5 + defense 3)
        assertValue(100, value(archer, hitPoints.name), "hit points, a shooter")
        assertValue(325, value(swordsman, hitPoints.name), "hit points")
        game.ruleset.modOptions.constants.unitMaxHitPointsLikeVp = false
        assertEquals("the switch off: not read", 0, value(swordsman, hitPoints.name))
    }

    @Test
    fun `a heal is rated only for a unit that has lost half its health`() {
        val swordsman = unit("Swordsman")
        assertEquals("not damaged: not a promotion it can take", 0, value(swordsman, "Heal Instantly"))
        swordsman.health = 55
        assertEquals("damaged less than half", 0, value(swordsman, "Heal Instantly"))
        swordsman.health = 50
        assertValue(1000, value(swordsman, "Heal Instantly"), "half health")
    }

    @Test
    fun `a unit takes the best promotion and again while it has the experience`() {
        val swordsman = unit("Swordsman")
        swordsman.promotions.XP = swordsman.promotions.xpForNextNPromotions(2)
        PromotionAi.promoteUnits(civ)
        // Drill I (rough terrain doubled) first; then Cover I: 33 ranged defense, doubled for unit AI attack and again for a unit with 2 moves, + 3 x 2
        assertEquals(setOf("Drill I", "Cover I"), swordsman.promotions.promotions)
        // 33 x (100 + 33 x 2) / 100 = 54 for the next tier, doubled twice
        assertValue(54 * 2 * 2 + 3 * 2, value(swordsman, "Cover II"), "Cover II")
        assertEquals(0, swordsman.promotions.XP)
    }

    @Test
    fun `a promotion the original rates nothing of is not taken`() {
        // A submarine that has Wolfpack I may take Wolfpack II or Torpedo I (plain attack bonuses the rating does not read) or Mobility (rated by its extra move; in
        // Vox Populi's promotions Targeting I is no longer a submarine's)
        game.setTileTerrain(HexCoord(2, 0), "Ocean")
        val submarine = unit("Submarine", HexCoord(2, 0))
        submarine.promotions.addPromotion("Wolfpack I", isFree = true)
        assertEquals(0, value(submarine, "Wolfpack I"))
        assertEquals(0, value(submarine, "Wolfpack II"))
        assertEquals(0, value(submarine, "Torpedo I"))
        submarine.promotions.XP = 1000
        NextTurnAutomation.applyPromotions(GameView(game.gameInfo, civ).getMapUnitView(submarine))
        assertTrue("Mobility" in submarine.promotions.promotions)
        assertTrue(submarine.promotions.promotions.none { it.startsWith("Wolfpack") && it != "Wolfpack I" || it.startsWith("Torpedo") })
    }

    @Test
    fun `a damaged unit heals instead of promoting only when half its health is gone`() {
        val swordsman = unit("Swordsman")
        swordsman.promotions.XP = 10
        swordsman.health = 55
        NextTurnAutomation.applyPromotions(GameView(game.gameInfo, civ).getMapUnitView(swordsman))
        assertEquals("not worth a heal yet: Drill I", true, "Drill I" in swordsman.promotions.promotions)
        assertEquals(55, swordsman.health)

        val hurt = unit("Swordsman", HexCoord(0, 1))
        hurt.promotions.XP = 10
        hurt.health = 40
        NextTurnAutomation.applyPromotions(GameView(game.gameInfo, civ).getMapUnitView(hurt))
        assertEquals(90, hurt.health)
        assertTrue(hurt.promotions.promotions.isEmpty())
    }

    @Test
    fun `interception is rated for a unit that can intercept`() {
        val fighter = unit("Fighter", HexCoord(0, 0))
        // 33 x 100 / 100 + air flavor x 2; the interception chance of the fighter itself is not what the rating adds to
        assertValue(33 + 2 * 2, value(fighter, "Interception I"), "Interception I")
        assertValue(33 + 2 * 2, value(fighter, "Dogfighting I"), "Dogfighting I")
    }
}
