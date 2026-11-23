package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The engine forms of four columns of Vox Populi's UnitPromotions (5.4.8), all behind the default-off switches or unique forms they need:
 *  - `CombatPercent`: CvUnit::GetGenericMeleeStrengthModifier and CvUnit::GetMaxRangedCombatStrength both start with `getExtraCombatPercent()`
 *    for attack and defence, melee and ranged, against units and cities. That is `[+n]% Strength`, which exists.
 *  - `RangedAttackModifier`: CvUnit::GetMaxRangedCombatStrength adds `GetRangedAttackModifier()` only when `bAttacking`: `<when making a ranged attack>`.
 *  - `AttackAbove50HealthMod`, `AttackBelowEqual50HealthMod`, `AttackFullyHealedMod`: CvUnit::GetMaxAttackStrength (melee) and
 *    CvUnit::GetMaxRangedCombatStrength add them by the health of the unit that is *attacked* (the defender), not that of the attacker:
 *    `pDefender->getDamage() + iAssumeExtraOtherDamage > 0` is the wounded test, else fully healed; melee: `damage < GetMaxHitPoints() / 2` above 50%,
 *    ranged: `damage < (GetMaxHitPoints() + 1) / 2`. Cities are no `pDefender`/`pOtherUnit`, so none of the three applies against a city.
 *  - `MaxHitPointsChange`: CvUnit::GetMaxHitPoints = base + change; CvUnit::changeMaxHitPointsChange leaves the damage as it is;
 *    CvUnit::setDamage clamps to the maximum; CvUnit::GetDamageCombatModifier and CvUnit::getCombatDamage-style ratios use the damage.
 */
@RunWith(BaseTestRunner::class)
class VpPromotionStrengthFormsTests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    /** Max health of a unit through the combatant, which exists on every source version (100 with the switch off) */
    private fun maxHealth(unit: MapUnit) = MapUnitCombatant(unit).getMaxHealth()

    private class Fight(val game: TestGame, val ours: Civilization, val theirs: Civilization)

    /** Our civilization has [ourUniques]; (0, 0), (1, 0), (-1, 0) and (0, 1) are flat Grassland, whose defence modifier is 0 */
    private fun fight(vararg ourUniques: String): Fight {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        // The Brave New World ruleset switches every one of these on: each test starts from the default (off) of ModConstants and switches on what it tests
        for (switch in listOf("combatHealthStrengthLikeVp", "unitMaxHitPointsLikeVp", "unitHealingLikeVp", "stateReligionHealingLikeVp", "cityCombatLikeVp", "enemyCombatAurasLikeVp"))
            setFlag(game, switch, false)
        game.makeHexagonalMap(3)
        for ((x, y) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1)) game.setTileTerrain(game.getTile(x, y).position, "Grassland")
        val ours = game.addCiv(*ourUniques, isPlayer = true)
        val theirs = game.addCiv(isPlayer = true)
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.getDiplomacyManager(theirs)!!.declareWar()
        return Fight(game, ours, theirs)
    }

    private fun attack(attacker: MapUnitCombatant, defender: ICombatant) =
        BattleDamage.getAttackingStrength(attacker, defender, attacker.getTile())

    private fun defend(attacker: MapUnitCombatant, defender: ICombatant) =
        BattleDamage.getDefendingStrength(attacker, defender, attacker.getTile())

    //region CombatPercent: `[+n]% Strength`

    @Test
    fun `combat percent applies to attack and defence of melee and ranged units and against cities`() {
        val f = fight("[+10]% Strength")
        val warrior = MapUnitCombatant(f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)))
        val enemy = MapUnitCombatant(f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0)))
        // GetMaxAttackStrength: 8 x (100 + 10) / 100
        assertEquals(8.8f, attack(warrior, enemy), 0.001f)
        // GetMaxDefenseStrength: the same modifier defends, the enemy's own is 100
        assertEquals(8f, attack(enemy, warrior), 0.001f)
        assertEquals(8.8f, defend(enemy, warrior), 0.001f)
        // GetMaxRangedCombatStrength: 7 x 110 / 100, as attacker and as defender against a ranged attack
        val archer = MapUnitCombatant(f.game.addUnit("Archer", f.ours, f.game.getTile(-1, 0)))
        val enemyArcher = MapUnitCombatant(f.game.addUnit("Archer", f.theirs, f.game.getTile(0, 1)))
        assertEquals(7.7f, attack(archer, enemy), 0.001f)
        assertEquals(7.7f, defend(enemyArcher, archer), 0.001f)
        assertEquals(7f, defend(archer, enemyArcher), 0.001f)
    }

    @Test
    fun `combat percent applies against a city`() {
        val f = fight("[+10]% Strength")
        val city = f.game.addCity(f.theirs, f.game.getTile(1, 0))
        val warrior = MapUnitCombatant(f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)))
        val archer = MapUnitCombatant(f.game.addUnit("Archer", f.ours, f.game.getTile(-1, 0)))
        assertEquals(8.8f, attack(warrior, CityCombatant(city)), 0.001f)
        assertEquals(7.7f, attack(archer, CityCombatant(city)), 0.001f)
    }

    //endregion

    //region RangedAttackModifier: `<when making a ranged attack>`

    @Test
    fun `ranged attack modifier applies only when a ranged unit attacks`() {
        val f = fight("[+10]% Strength <when making a ranged attack>")
        val archer = MapUnitCombatant(f.game.addUnit("Archer", f.ours, f.game.getTile(-1, 0)))
        val warrior = MapUnitCombatant(f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)))
        val enemy = MapUnitCombatant(f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0)))
        // CvUnit::GetMaxRangedCombatStrength: bAttacking adds GetRangedAttackModifier(): 7 x 110 / 100
        assertEquals(7.7f, attack(archer, enemy), 0.001f)
        // a melee attack never reads it (GetMaxAttackStrength has no ranged attack modifier)
        assertEquals(8f, attack(warrior, enemy), 0.001f)
        // the defender branch of GetMaxRangedCombatStrength does not add it, even against a ranged attack
        val enemyArcher = MapUnitCombatant(f.game.addUnit("Archer", f.theirs, f.game.getTile(0, 1)))
        assertEquals(7f, defend(enemyArcher, archer), 0.001f)
    }

    @Test
    fun `ranged attack modifier also applies against a city and sums with other modifiers`() {
        val f = fight("[+10]% Strength <when making a ranged attack>", "[-20]% Strength <when making a ranged attack>", "[+5]% Strength")
        val city = f.game.addCity(f.theirs, f.game.getTile(1, 0))
        val archer = MapUnitCombatant(f.game.addUnit("Archer", f.ours, f.game.getTile(-1, 0)))
        // 7 x (100 + 10 - 20 + 5) / 100
        assertEquals(6.65f, attack(archer, CityCombatant(city)), 0.001f)
    }

    //endregion

    //region Attack mods by the health of the attacked unit

    private val healthMods = arrayOf(
        "[+10]% Strength <vs units above half health>",
        "[+30]% Strength <vs units at or below half health>",
        "[+25]% Strength <vs fully healed units>")

    private fun meleeAgainstHealth(warrior: MapUnitCombatant, target: MapUnit, health: Int): Float {
        target.health = health
        return attack(warrior, MapUnitCombatant(target))
    }

    @Test
    fun `melee attack is stronger by the health of the defender`() {
        val f = fight(*healthMods)
        val warrior = MapUnitCombatant(f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)))
        val target = f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0))
        // GetMaxAttackStrength: no damage: fully healed (+25) and above half (+10)
        assertEquals(10.8f, meleeAgainstHealth(warrior, target, 100), 0.001f)
        // damage 1 is wounded: above half only
        assertEquals(8.8f, meleeAgainstHealth(warrior, target, 99), 0.001f)
        // damage 49 < 100 / 2: above half
        assertEquals(8.8f, meleeAgainstHealth(warrior, target, 51), 0.001f)
        // damage 50 is not below 50: the at-or-below mod
        assertEquals(10.4f, meleeAgainstHealth(warrior, target, 50), 0.001f)
        assertEquals(10.4f, meleeAgainstHealth(warrior, target, 1), 0.001f)
    }

    @Test
    fun `the health of the attacker is not read and the mods do not apply against cities or when defending`() {
        val f = fight(*healthMods)
        val warrior = f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0))
        val enemy = MapUnitCombatant(f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0)))
        warrior.health = 40
        assertEquals(10.8f, attack(MapUnitCombatant(warrior), enemy), 0.001f)
        // a city is no pDefender: none of the three
        val city = f.game.addCity(f.theirs, f.game.getTile(-1, 0))
        assertEquals(8f, attack(MapUnitCombatant(warrior), CityCombatant(city)), 0.001f)
        // GetMaxDefenseStrength has none of them
        assertEquals(8f, defend(enemy, MapUnitCombatant(warrior)), 0.001f)
    }

    @Test
    fun `ranged attack uses the same health tests`() {
        val f = fight(*healthMods)
        val archer = MapUnitCombatant(f.game.addUnit("Archer", f.ours, f.game.getTile(-1, 0)))
        val target = f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0))
        // 7 x 135 / 100
        assertEquals(9.45f, attack(archer, MapUnitCombatant(target)), 0.001f)
        target.health = 51 // damage 49 < (100 + 1) / 2
        assertEquals(7.7f, attack(archer, MapUnitCombatant(target)), 0.001f)
        target.health = 50 // damage 50 is not below 50
        assertEquals(9.1f, attack(archer, MapUnitCombatant(target)), 0.001f)
    }

    @Test
    fun `health tests follow the hypothetical health of the combatant`() {
        val f = fight(*healthMods)
        val warrior = MapUnitCombatant(f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)))
        val target = f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0))
        val hypothetical = object : MapUnitCombatant(target) { override fun getHealth() = 40 }
        assertEquals(10.4f, attack(warrior, hypothetical), 0.001f)
        assertEquals(10.8f, attack(warrior, MapUnitCombatant(target)), 0.001f)
    }

    @Test
    fun `half health rounds differently for melee and ranged attackers with an odd maximum`() {
        val f = fight(*healthMods)
        setFlag(f.game, "unitMaxHitPointsLikeVp", true)
        val promotion = f.game.createUnitPromotion("[+25] maximum hit points")
        val target = f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0))
        target.promotions.addPromotion(promotion.name, isFree = true)
        assertEquals(125, maxHealth(target))
        assertEquals(125, target.health)
        val warrior = MapUnitCombatant(f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)))
        val archer = MapUnitCombatant(f.game.addUnit("Archer", f.ours, f.game.getTile(-1, 0)))
        // damage 62: melee tests 62 < 125 / 2 = 62 (false: at or below), ranged tests 62 < (125 + 1) / 2 = 63 (true: above)
        target.health = 63
        assertEquals(10.4f, attack(warrior, MapUnitCombatant(target)), 0.001f)
        assertEquals(7.7f, attack(archer, MapUnitCombatant(target)), 0.001f)
        // fully healed is measured against the unit's own maximum
        target.health = 125
        assertEquals(10.8f, attack(warrior, MapUnitCombatant(target)), 0.001f)
        target.health = 124
        assertEquals(8.8f, attack(warrior, MapUnitCombatant(target)), 0.001f)
    }

    //endregion

    //region MaxHitPointsChange: `[+n] maximum hit points`

    private fun promoted(game: TestGame, unit: MapUnit, change: Int): MapUnit {
        val promotion = game.createUnitPromotion("[+$change] maximum hit points")
        unit.promotions.addPromotion(promotion.name, isFree = true)
        return unit
    }

    @Test
    fun `maximum hit points stay 100 while the switch is off`() {
        val f = fight()
        val unit = promoted(f.game, f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)), 25)
        assertEquals(100, maxHealth(unit))
        assertEquals(100, unit.health)
        unit.health = 60
        unit.healBy(100)
        assertEquals(100, unit.health)
        assertEquals(0, unit.getHealAmountForCurrentTile())
    }

    @Test
    fun `promotion raises the maximum and keeps the damage`() {
        val f = fight()
        setFlag(f.game, "unitMaxHitPointsLikeVp", true)
        val unit = f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0))
        unit.health = 60 // damage 40
        val promotion = f.game.createUnitPromotion("[+25] maximum hit points")
        unit.promotions.addPromotion(promotion.name, isFree = true)
        // CvUnit::changeMaxHitPointsChange changes the maximum only: damage 40 of 125
        assertEquals(125, maxHealth(unit))
        assertEquals(85, unit.health)
        unit.healBy(100)
        assertEquals(125, unit.health)
        // a second promotion adds up (the change is a sum)
        promoted(f.game, unit, 15)
        assertEquals(140, maxHealth(unit))
        assertEquals(140, unit.health)
        unit.promotions.removePromotion(promotion.name)
        assertEquals(115, maxHealth(unit))
        assertEquals(115, unit.health)
    }

    @Test
    fun `damage and healing are clamped to the maximum`() {
        val f = fight()
        setFlag(f.game, "unitMaxHitPointsLikeVp", true)
        val city = f.game.addCity(f.ours, f.game.getTile(-1, 0))
        val unit = promoted(f.game, f.game.addUnit("Warrior", f.ours, city.getCenterTile()), 25)
        unit.takeDamage(30)
        assertEquals(95, unit.health)
        unit.takeDamage(-500) // CvUnit::setDamage: range(damage, 0, GetMaxHitPoints())
        assertEquals(125, unit.health)
        assertEquals(0, unit.getHealAmountForCurrentTile())
        // a unit at 100 of 125 still heals (at least the 25 of a city), though it is at 100
        unit.health = 100
        assertTrue(unit.getHealAmountForCurrentTile() >= 25)
    }

    @Test
    fun `wounded test and strength penalty use the lost health`() {
        val f = fight()
        setFlag(f.game, "unitMaxHitPointsLikeVp", true)
        setFlag(f.game, "combatHealthStrengthLikeVp", true)
        val unit = promoted(f.game, f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)), 25)
        val enemy = MapUnitCombatant(f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0)))
        assertFalse(unit.matchesFilter("Wounded"))
        assertEquals(8f, attack(MapUnitCombatant(unit), enemy), 0.001f)
        unit.health = 100 // 25 of 125 lost
        assertTrue(unit.matchesFilter("Wounded"))
        // CvUnit::GetDamageCombatModifier: 25 x 33 / 100 = 8 per cent less strength
        assertEquals(7.36f, attack(MapUnitCombatant(unit), enemy), 0.001f)
    }

    @Test
    fun `damage dealt by a wounded unit follows its lost health, not its health out of 100`() {
        val f = fight()
        setFlag(f.game, "unitMaxHitPointsLikeVp", true)
        val unit = promoted(f.game, f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)), 25)
        val enemy = MapUnitCombatant(f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0)))
        val attacker = MapUnitCombatant(unit)
        // full health: equal strengths, the roll 24 + 12 x 0.5 = 30
        assertEquals(30, BattleDamage.calculateDamageToDefender(attacker, enemy, attacker.getTile(), 0.5f))
        // 25 of 125 lost: a third of it, 8 per cent: 30 x 0.92 = 27.6
        unit.health = 100
        assertEquals(27, BattleDamage.calculateDamageToDefender(attacker, enemy, attacker.getTile(), 0.5f))
    }

    @Test
    fun `damage dealt is unchanged by a promotion while the switch is off`() {
        val f = fight()
        val unit = promoted(f.game, f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0)), 25)
        val enemy = MapUnitCombatant(f.game.addUnit("Warrior", f.theirs, f.game.getTile(1, 0)))
        val attacker = MapUnitCombatant(unit)
        assertEquals(30, BattleDamage.calculateDamageToDefender(attacker, enemy, attacker.getTile(), 0.5f))
        unit.health = 70 // 30 lost: 9 per cent less, 30 x 0.91 = 27.3
        assertEquals(27, BattleDamage.calculateDamageToDefender(attacker, enemy, attacker.getTile(), 0.5f))
    }

    @Test
    fun `power and garrison absorption follow the maximum`() {
        val f = fight()
        setFlag(f.game, "unitMaxHitPointsLikeVp", true)
        setFlag(f.game, "cityCombatLikeVp", true)
        val plain = f.game.addUnit("Warrior", f.ours, f.game.getTile(0, 0))
        val strong = promoted(f.game, f.game.addUnit("Warrior", f.ours, f.game.getTile(-1, 0)), 25)
        // CvUnit::GetPower: x current / maximum hit points
        assertEquals(plain.getAiPower(), strong.getAiPower())

        val city = f.game.addCity(f.theirs, f.game.getTile(1, 0))
        val garrison = promoted(f.game, f.game.addUnit("Warrior", f.theirs, city.getCenterTile()), 25)
        assertEquals(125, garrison.health)
        val attacker = MapUnitCombatant(plain)
        val defender = CityCombatant(city)
        // CvUnit::GetRangeCombatDamage: the garrison takes damage x 2 x garrison maximum / (city maximum + 2 x garrison maximum), 125 not 100
        val (cityDamage, garrisonShare) = BattleDamage.calculateDamageToDefenderAndGarrison(attacker, defender)
        assertTrue(garrisonShare > 0)
        assertEquals((cityDamage + garrisonShare) * 250 / (defender.getMaxHealth() + 250), garrisonShare)
    }

    //endregion
}
