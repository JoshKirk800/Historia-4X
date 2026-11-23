package com.unciv.logic.battle

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  VP 5.4.8 Units.IsMounted and UnitPromotions.MountedOnly, staged behind `mountedUnitFlagLikeVp`.
 *  - CvUnit::unitCombatModifier, GetMaxAttackStrength, GetMaxDefenseStrength, GetMaxRangedCombatStrength ("hack: mounted units can have secondary
 *    combat class"): a target whose unit entry is IsMounted but whose combat class is not Mounted also takes the modifiers against the Mounted class.
 *  - CvUnit::canAcquirePromotion: a MountedOnly promotion is refused to a unit that is not IsMounted.
 */
@RunWith(BaseTestRunner::class)
class VpMountedFlagTests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private class Setup(val game: TestGame, val ours: com.unciv.logic.civilization.Civilization, val theirs: com.unciv.logic.civilization.Civilization)

    private fun setup(flag: Boolean): Setup {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        for ((x, y) in listOf(0 to 0, 1 to 0)) game.setTileTerrain(game.getTile(x, y).position, "Grassland")
        val ours = game.addCiv(isPlayer = true)
        val theirs = game.addCiv(isPlayer = true)
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.getDiplomacyManager(theirs)!!.declareWar()
        setFlag(game, "mountedUnitFlagLikeVp", flag)
        return Setup(game, ours, theirs)
    }

    /** A Sword-type unit of strength 10 that is flagged `Is a mounted unit`, so its own combat class is not Mounted */
    private fun Setup.flaggedUnit(tile: com.unciv.logic.map.tile.Tile): com.unciv.logic.map.mapunit.MapUnit {
        val base = game.createBaseUnit("Sword", "Is a mounted unit")
        base.movement = 2
        base.strength = 10
        return game.addUnit(base.name, theirs, tile)
    }

    @Test
    fun `flagged unit takes the bonus against Mounted when attacked and when attacking`() {
        val s = setup(true)
        val spearman = MapUnitCombatant(s.game.addUnit("Spearman", s.ours, s.game.getTile(0, 0)))
        val flagged = MapUnitCombatant(s.flaggedUnit(s.game.getTile(1, 0)))
        // GetMaxAttackStrength: 11 x (100 + 50) / 100, the +50% vs the Mounted class also against the flagged unit
        assertEquals(16.5f, BattleDamage.getAttackingStrength(spearman, flagged, spearman.getTile()), 0.001f)
        // GetMaxDefenseStrength: the Spearman defends with the same bonus against the flagged attacker
        assertEquals(16.5f, BattleDamage.getDefendingStrength(flagged, spearman, flagged.getTile()), 0.001f)
    }

    @Test
    fun `flagged unit does not take the bonus with the switch off`() {
        val s = setup(false)
        val spearman = MapUnitCombatant(s.game.addUnit("Spearman", s.ours, s.game.getTile(0, 0)))
        val flagged = MapUnitCombatant(s.flaggedUnit(s.game.getTile(1, 0)))
        assertEquals(11f, BattleDamage.getAttackingStrength(spearman, flagged, spearman.getTile()), 0.001f)
        assertEquals(11f, BattleDamage.getDefendingStrength(flagged, spearman, flagged.getTile()), 0.001f)
    }

    @Test
    fun `unflagged unit of another class and a real Mounted unit are matched as before`() {
        val s = setup(true)
        val spearman = MapUnitCombatant(s.game.addUnit("Spearman", s.ours, s.game.getTile(0, 0)))
        val warrior = MapUnitCombatant(s.game.addUnit("Warrior", s.theirs, s.game.getTile(1, 0)))
        assertEquals(11f, BattleDamage.getAttackingStrength(spearman, warrior, spearman.getTile()), 0.001f)
        val horseman = MapUnitCombatant(s.game.addUnit("Horseman", s.theirs, s.game.getTile(0, 1)))
        assertEquals(16.5f, BattleDamage.getAttackingStrength(spearman, horseman, spearman.getTile()), 0.001f)
    }

    @Test
    fun `mounted only promotion is refused to a unit that is not flagged`() {
        val s = setup(true)
        val promotion = s.game.createUnitPromotion("Only for mounted units")
        promotion.unitTypes = listOf("Sword", "Melee")
        val flagged = s.flaggedUnit(s.game.getTile(0, 0))
        val warrior = s.game.addUnit("Warrior", s.theirs, s.game.getTile(1, 0))
        assertTrue(promotion.name in flagged.promotions.getAvailablePromotions().map { it.name }.toList())
        assertFalse(promotion.name in warrior.promotions.getAvailablePromotions().map { it.name }.toList())
    }

    @Test
    fun `mounted only promotion is not read with the switch off`() {
        val s = setup(false)
        val promotion = s.game.createUnitPromotion("Only for mounted units")
        promotion.unitTypes = listOf("Sword", "Melee")
        val warrior = s.game.addUnit("Warrior", s.theirs, s.game.getTile(1, 0))
        assertTrue(promotion.name in warrior.promotions.getAvailablePromotions().map { it.name }.toList())
    }
}
