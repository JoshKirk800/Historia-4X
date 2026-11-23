package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 air basing, land air defence and interception, staged behind `airBasingLikeVp`, `airDefenseLikeVp` and `airInterceptionLikeVp`:
 *  - CvPlot::countNumAirUnits, CvUnit::canRebaseAt / getRebaseRange, CvCity::GetMaxAirUnits (BASE_CITY_AIR_STACKING 2, AIR_UNIT_REBASE_RANGE_MULTIPLIER 500)
 *  - CvUnit::GetAirStrikeDefenseDamage and CvCity::GetAirStrikeDefenseDamage (MOD_BALANCE_AIR_UNIT_CHANGES): Units.BaseLandAirDefense, UnitPromotions.LandAirDefenseBonus
 *  - CvPlot::GetBestInterceptor / GetInterceptorCount, CvUnit::interceptionProbability / evasionProbability / GetInterceptionDamage, CvUnitCombat::GenerateAirCombatInfo
 */
@RunWith(BaseTestRunner::class)
class VpStageAirTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization
    private lateinit var ourCity: City
    private lateinit var theirCity: City
    private val ourTile get() = game.getTile(0, 0)
    private val theirTile get() = game.getTile(3, 0)

    private fun setSwitch(name: String, value: Boolean) {
        val constants = game.ruleset.modOptions.constants
        constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType).invoke(constants, value)
    }

    private fun vpBasing(capacity: Int = 2, multiplier: Int = 500) {
        setSwitch("airBasingLikeVp", true)
        game.ruleset.modOptions.constants.airBasing = ModConstants.AirBasingConstants(capacity, multiplier)
    }

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        us = game.addCiv()
        them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
        ourCity = game.addCity(us, ourTile)
        theirCity = game.addCity(them, theirTile)
    }

    private fun tileAtDistance(from: Tile, distance: Int) = game.tileMap.tileList.first { it.aerialDistanceTo(from) == distance }

    //region Capacity

    @Test
    fun `a city with two slots is full with two aircraft and free with one`() {
        vpBasing(capacity = 2)
        val farCity = game.addCity(us, tileAtDistance(ourTile, 6))
        val first = game.addUnit("Fighter", us, ourTile)
        game.addUnit("Bomber", us, ourTile)
        val mover = game.addUnit("Fighter", us, farCity.getCenterTile())

        assertEquals(2, ourCity.getMaxAirUnits())
        assertEquals(2, ourCity.getAirUnitSlotsUsed())
        assertFalse(mover.movement.canMoveTo(ourTile))

        first.destroy()
        assertEquals(1, ourCity.getAirUnitSlotsUsed())
        assertTrue(mover.movement.canMoveTo(ourTile))
    }

    @Test
    fun `without the switch the city keeps its six slots and counts every untransported aircraft once`() {
        game.ruleset.modOptions.constants.airBasing = ModConstants.AirBasingConstants(2, 500) // not read
        val farCity = game.addCity(us, tileAtDistance(ourTile, 6))
        val heavy = game.createBaseUnit("Fighter", "Counts [3] against the air unit capacity")
        game.addUnit(heavy.name, us, ourTile)
        game.addUnit("Fighter", us, ourTile)
        val mover = game.addUnit("Fighter", us, farCity.getCenterTile())

        assertEquals(6, ourCity.getMaxAirUnits())
        assertEquals(2, ourCity.getAirUnitSlotsUsed())
        assertTrue(mover.movement.canMoveTo(ourTile))
    }

    @Test
    fun `an aircraft uses its Counts amount of slots, and none when it counts zero`() {
        vpBasing(capacity = 2)
        val farCity = game.addCity(us, tileAtDistance(ourTile, 6))
        val heavy = game.createBaseUnit("Fighter", "Counts [2] against the air unit capacity")
        val free = game.createBaseUnit("Fighter", "Counts [0] against the air unit capacity")
        game.addUnit("Fighter", us, ourTile)
        val nothing = game.addUnit(free.name, us, ourTile)
        assertEquals(0, nothing.airUnitCapacityCost())
        assertEquals("a unit that counts 0 uses no slot", 1, ourCity.getAirUnitSlotsUsed())

        val plain = game.addUnit("Fighter", us, farCity.getCenterTile())
        assertEquals(1, plain.airUnitCapacityCost()) // an air unit without the unique counts 1, as every air unit of VP's data has AirUnitCap 1
        assertTrue(plain.movement.canMoveTo(ourTile)) // 1 slot in use of 2

        // a unit enters while the slots in use are fewer than the capacity, however many it then takes (CvUnit::canRebaseAt)
        val heavyUnit = game.addUnit(heavy.name, us, ourTile)
        assertEquals(2, heavyUnit.airUnitCapacityCost())
        assertEquals(3, ourCity.getAirUnitSlotsUsed())
        assertFalse(heavyUnit.isTransported)
        assertFalse(plain.movement.canMoveTo(ourTile))
        // ... even a unit that counts 0 is refused by a full city
        assertFalse(game.addUnit(free.name, us, farCity.getCenterTile()).movement.canMoveTo(ourTile))
    }

    @Test
    fun `transported aircraft and the aircraft of another civilization use no slot`() {
        vpBasing(capacity = 2)
        val carried = game.addUnit("Fighter", us, ourTile)
        carried.isTransported = true
        val foreign = game.addUnit("Fighter", them, theirTile)
        ourTile.airUnits.add(foreign)

        assertEquals(0, ourCity.getAirUnitSlotsUsed())
    }

    @Test
    fun `an overfull city after the switch is turned on keeps every aircraft and refuses new ones`() {
        val farCity = game.addCity(us, tileAtDistance(ourTile, 6))
        val inCity = List(3) { game.addUnit("Fighter", us, ourTile) } // legal with the six slots of the original
        val mover = game.addUnit("Fighter", us, farCity.getCenterTile())
        assertTrue(mover.movement.canMoveTo(ourTile))

        vpBasing(capacity = 2)

        assertEquals(3, ourCity.getAirUnitSlotsUsed())
        assertEquals(3, ourTile.airUnits.size)
        for (unit in inCity) {
            assertFalse(unit.isTransported)
            assertFalse("a unit already there is not moved or destroyed", unit.isDestroyed)
        }
        assertFalse(mover.movement.canMoveTo(ourTile))
        // one leaves: 2 of 2 slots, still no room
        inCity[0].destroy()
        assertFalse(mover.movement.canMoveTo(ourTile))
        inCity[1].destroy()
        assertTrue(mover.movement.canMoveTo(ourTile))
    }

    @Test
    fun `an aircraft already in a city may stay when the city is exactly full`() {
        vpBasing(capacity = 2)
        val first = game.addUnit("Fighter", us, ourTile)
        game.addUnit("Fighter", us, ourTile)

        // CvUnit::canRebaseAt does not count the unit itself
        assertTrue(first.movement.canMoveTo(ourTile))
    }

    @Test
    fun `the rebase range is the range times the multiplier, the attack range stays`() {
        val base = game.createBaseUnit("Fighter")
        base.range = 2
        base.movement = 1
        val unit = game.addUnit(base.name, us, ourTile)
        assertEquals(4, unit.getAirUnitMovementRange())

        vpBasing(multiplier = 500)
        assertEquals(10, unit.getAirUnitMovementRange())
        assertEquals(2, unit.getRange())
        assertTrue(unit.movement.canReachInCurrentTurn(tileAtDistance(ourTile, 10)))
        assertFalse(unit.movement.canReachInCurrentTurn(tileAtDistance(ourTile, 11)))

        game.ruleset.modOptions.constants.airBasing = ModConstants.AirBasingConstants(2, 250)
        assertEquals(5, unit.getAirUnitMovementRange()) // rounded down
    }

    @Test
    fun `without the switch the rebase range is twice the range`() {
        val base = game.createBaseUnit("Fighter")
        base.range = 2
        base.movement = 1
        game.ruleset.modOptions.constants.airBasing = ModConstants.AirBasingConstants(2, 500) // not read
        val unit = game.addUnit(base.name, us, ourTile)

        assertEquals(4, unit.getAirUnitMovementRange())
        assertTrue(unit.movement.canReachInCurrentTurn(tileAtDistance(ourTile, 4)))
        assertFalse(unit.movement.canReachInCurrentTurn(tileAtDistance(ourTile, 5)))
    }

    //endregion
    //region Land air defence

    private fun bomber(vararg uniques: String): MapUnit {
        val base = game.createBaseUnit("Bomber", *uniques)
        base.strength = 45
        base.rangedStrength = 45
        base.range = 6
        return game.addUnit(base.name, us, ourTile)
    }

    private fun defender(vararg uniques: String, tile: Tile = game.getTile(2, 0)): MapUnit {
        val base = game.createBaseUnit("Sword", *uniques)
        base.strength = 8
        return game.addUnit(base.name, them, tile)
    }

    private fun damageToBomber(attacker: MapUnit, target: ICombatant, randomness: Float) =
        BattleDamage.calculateDamageToAttacker(MapUnitCombatant(attacker), target, randomnessFactor = randomness)

    @Test
    fun `a unit with air defense 15 does 15 plus the random 0 to 4 to a bomber`() {
        setSwitch("airDefenseLikeVp", true)
        val bomber = bomber()
        val target = MapUnitCombatant(defender("[15] air defense"))

        assertEquals(15, damageToBomber(bomber, target, 0f))
        assertEquals(17, damageToBomber(bomber, target, 0.5f))
        assertEquals(19, damageToBomber(bomber, target, 0.999f))
    }

    @Test
    fun `the air defense of the unit and of its promotions are added`() {
        setSwitch("airDefenseLikeVp", true)
        val promotion = game.createUnitPromotion("[15] air defense") // PROMOTION_AIR_DEFENSE_1: LandAirDefenseBonus 15
        val unit = defender("[5] air defense")
        unit.promotions.addPromotion(promotion.name, true)

        assertEquals(20, damageToBomber(bomber(), MapUnitCombatant(unit), 0f))
    }

    @Test
    fun `a unit with no air defense still does the random part`() {
        setSwitch("airDefenseLikeVp", true)
        val target = MapUnitCombatant(defender())
        assertEquals(0, damageToBomber(bomber(), target, 0f))
        assertEquals(4, damageToBomber(bomber(), target, 0.99f))
    }

    @Test
    fun `the attacker's interception reduction and damage taken change the damage of a unit`() {
        setSwitch("airDefenseLikeVp", true)
        val target = MapUnitCombatant(defender("[20] air defense"))

        // CvUnit::GetAirStrikeDefenseDamage: 20 x (100 - 50) / 100
        assertEquals(10, damageToBomber(bomber("Damage taken from interception reduced by [50]%"), target, 0f))
        // reductions are summed to -150: never below 0
        assertEquals(0, damageToBomber(bomber("Damage taken from interception reduced by [150]%"), target, 0f))
        // (20 + 0) x (100 + 50) / 100
        assertEquals(30, damageToBomber(bomber("[+50]% damage taken"), target, 0f))
        // the random part comes before the damage taken modifier: (20 + 4) x 150 / 100
        assertEquals(36, damageToBomber(bomber("[+50]% damage taken"), target, 0.99f))
    }

    @Test
    fun `a city does the air defense of its buildings plus 0 to 9, with no minimum`() {
        setSwitch("airDefenseLikeVp", true)
        val attacker = game.addUnit("Bomber", them, theirTile)
        val target = CityCombatant(ourCity)
        assertEquals("no building: nothing, where the strength rule has a minimum", 0, damageToBomber(attacker, target, 0f))

        ourCity.cityConstructions.addBuilding(game.createBuilding("[10] air defense"))
        ourCity.cityConstructions.addBuilding(game.createBuilding("[15] air defense"))
        assertEquals(25, damageToBomber(attacker, target, 0f))
        assertEquals(34, damageToBomber(attacker, target, 0.99f))
    }

    @Test
    fun `the interception reduction applies to a city's air defense too`() {
        setSwitch("airDefenseLikeVp", true)
        ourCity.cityConstructions.addBuilding(game.createBuilding("[20] air defense"))
        val attacker = game.addUnit(game.createBaseUnit("Bomber", "Damage taken from interception reduced by [25]%").name, them, theirTile)

        assertEquals(15, damageToBomber(attacker, CityCombatant(ourCity), 0f))
    }

    @Test
    fun `without the switch the air defense unique is not read`() {
        val bomber = bomber()
        val plain = MapUnitCombatant(defender())
        val armed = MapUnitCombatant(defender("[15] air defense", tile = game.getTile(2, 1)))
        assertEquals(damageToBomber(bomber, plain, 0f), damageToBomber(bomber, armed, 0f))
    }

    @Test
    fun `a bombing mission damages the bomber by the air defense of its target`() {
        setSwitch("airDefenseLikeVp", true)
        val bomber = bomber()
        val target = defender("[15] air defense")

        Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))

        assertTrue("15 to 19", bomber.health in 81..85)
        assertTrue(target.health < 100)
    }

    //endregion
    //region Interception

    /** A fighter of [civ] that intercepts with [chance] at [health] (like AirInterceptionChanceTests, with the strengths of the numbers below) */
    private fun fighter(civ: Civilization, tile: Tile, chance: Int, health: Int = 100, vararg uniques: String): MapUnit {
        val base = game.createBaseUnit("Fighter", "[$chance]% chance to intercept air attacks", *uniques)
        base.strength = 45
        base.rangedStrength = 45
        base.range = 8
        base.interceptRange = 8
        val unit = game.addUnit(base.name, civ, tile)
        unit.health = health
        return unit
    }

    private fun best(attacker: MapUnit, tile: Tile = game.getTile(2, 0)) =
        AirInterception.bestInterceptorLikeVp(us, MapUnitCombatant(attacker), tile)

    @Test
    fun `the best interceptor has the highest chance times health, an air unit's health counting twice`() {
        setSwitch("airInterceptionLikeVp", true)
        val weakButSure = fighter(them, theirTile, 100, health = 50) // 100 x 50% (chance x health), x 50% again for an air unit = 25
        val steady = fighter(them, theirTile, 40) // 40
        val choice = best(bomber())

        assertEquals(50, weakButSure.interceptionProbability())
        assertSame(steady, choice.interceptor)
        assertEquals(2, choice.count)
    }

    @Test
    fun `Damage when intercepting raises the interceptor's value, the nearest of equals is picked`() {
        setSwitch("airInterceptionLikeVp", true)
        val farCity = game.addCity(them, game.getTile(6, 0))
        val strong = fighter(them, theirTile, 50, 100, "[+100]% Damage when intercepting") // 45 x 200% x 50
        val plain = fighter(them, farCity.getCenterTile(), 80) // 45 x 80
        assertSame(strong, best(bomber()).interceptor)

        strong.destroy()
        plain.destroy()
        val near = fighter(them, theirTile, 70)
        fighter(them, farCity.getCenterTile(), 70)
        assertSame("equal value: the one closer to the target plot", near, best(bomber()).interceptor)
    }

    @Test
    fun `an interceptor that has made its interception or has no chance is not counted`() {
        setSwitch("airInterceptionLikeVp", true)
        fighter(them, theirTile, 50)
        val spent = fighter(them, theirTile, 50)
        spent.attacksThisTurn = 1 // already made its interception
        game.addUnit(game.createBaseUnit("Fighter").name, them, theirTile) // no chance to intercept at all

        assertEquals(1, best(bomber()).count)
        assertEquals(1, AirInterception.bestInterceptorLikeVp(us, null, game.getTile(2, 0)).count)
    }

    @Test
    fun `the defender may be the interceptor`() {
        setSwitch("airInterceptionLikeVp", true)
        val base = game.createBaseUnit("Sword", "[100]% chance to intercept air attacks")
        base.strength = 8
        base.interceptRange = 4
        val guard = game.addUnit(base.name, them, game.getTile(2, 0))

        assertSame(guard, best(bomber(), game.getTile(2, 0)).interceptor)
    }

    @Test
    fun `a land unit intercepts with its chance times its health too`() {
        val base = game.createBaseUnit("Sword", "[80]% chance to intercept air attacks")
        base.interceptRange = 4
        val guard = game.addUnit(base.name, them, game.getTile(2, 0))
        guard.health = 50

        assertEquals(80, guard.currentInterceptChance()) // the original rule keeps the chance for a land unit
        assertEquals(40, guard.interceptionProbability())
    }

    @Test
    fun `nobody intercepts over the land of a third civilization they are not at war with`() {
        setSwitch("airInterceptionLikeVp", true)
        val third = game.addCiv()
        val thirdCity = game.addCity(third, game.getTile(-3, 0))
        val interceptor = fighter(them, theirTile, 100)

        assertNull(best(bomber(), thirdCity.getCenterTile()).interceptor)

        them.diplomacyFunctions.makeCivilizationsMeet(third)
        them.getDiplomacyManager(third)!!.declareWar()
        assertSame(interceptor, best(bomber(), thirdCity.getCenterTile()).interceptor)
    }

    @Test
    fun `a civilization the attacker is not at war with does not intercept`() {
        setSwitch("airInterceptionLikeVp", true)
        val bystander = game.addCiv()
        game.addCity(bystander, game.getTile(-3, 0))
        fighter(bystander, game.getTile(-3, 0), 100)
        val enemyFighter = fighter(them, theirTile, 30)

        val choice = best(bomber())
        assertSame(enemyFighter, choice.interceptor)
        assertEquals(1, choice.count)
    }

    @Test
    fun `a certain interception damages the attacker and ends the attack, the target is not hit`() {
        setSwitch("airInterceptionLikeVp", true)
        val bomber = bomber()
        val target = defender()
        val interceptor = fighter(them, theirTile, 100)

        val dealt = Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))

        assertTrue(bomber.health < 100)
        assertEquals(100, target.health)
        assertEquals(100 - bomber.health, dealt.defenderDealt)
        assertEquals(1, interceptor.attacksThisTurn)
        assertEquals(1, bomber.attacksThisTurn)
        assertTrue("2 experience for the interceptor", interceptor.promotions.XP > 0)
    }

    @Test
    fun `without the switch the attack goes on after the interception`() {
        val bomber = bomber()
        val target = defender()
        fighter(them, theirTile, 100)

        Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))

        assertTrue(target.health < 100)
    }

    @Test
    fun `an interceptor with no chance left still takes its shot and the attack goes on`() {
        setSwitch("airInterceptionLikeVp", true)
        val bomber = bomber()
        val target = defender()
        val weak = fighter(them, theirTile, 50, health = 1) // 50 x 1 / 100 = 0

        assertEquals(0, weak.interceptionProbability())
        Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))

        assertEquals("CvPlot::GetBestInterceptor still picks it when nothing is better", 1, weak.attacksThisTurn)
        assertTrue(target.health < 100)
    }

    @Test
    fun `an attacker that evades is not intercepted, though the interceptor takes its shot`() {
        setSwitch("airInterceptionLikeVp", true)
        val bomber = bomber("[100]% chance to evade interception")
        val target = defender()
        val interceptor = fighter(them, theirTile, 100)

        assertEquals(100, bomber.evasionProbability())
        Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))

        assertEquals(1, interceptor.attacksThisTurn)
        assertEquals(0, interceptor.promotions.XP)
        assertTrue(target.health < 100)
    }

    private fun groundInterceptor(): MapUnit {
        val base = game.createBaseUnit("Sword", "[100]% chance to intercept air attacks")
        base.strength = 45
        base.interceptRange = 4
        return game.addUnit(base.name, them, game.getTile(2, 0))
    }

    @Test
    fun `a sweep over a ground interceptor costs the sweeper half the interception damage`() {
        setSwitch("airInterceptionLikeVp", true)
        val sweeper = game.addUnit("Fighter", us, ourTile)
        val guard = groundInterceptor()
        val full = AirInterception.interceptionDamageLikeVp(guard, MapUnitCombatant(sweeper), game.getTile(2, 0), BattleDamage.getRandomness(MapUnitCombatant(sweeper)))

        AirInterception.airSweep(MapUnitCombatant(sweeper), game.getTile(2, 0))

        assertTrue(full > 1)
        assertEquals(100 - full * 50 / 100, sweeper.health) // AIR_SWEEP_INTERCEPTION_DAMAGE_MOD -50
        assertEquals(100, guard.health)
        assertEquals(1, guard.attacksThisTurn)
    }

    @Test
    fun `without the switch a ground interceptor does nothing to a sweeper`() {
        val sweeper = game.addUnit("Fighter", us, ourTile)
        val guard = groundInterceptor()

        AirInterception.airSweep(MapUnitCombatant(sweeper), game.getTile(2, 0))

        assertEquals(100, sweeper.health)
        assertEquals(1, guard.attacksThisTurn)
    }

    @Test
    fun `the interception damage is VP's damage math with the bonus on the strength and the summed reductions as modifier`() {
        setSwitch("airInterceptionLikeVp", true)
        val interceptor = fighter(them, theirTile, 100, 100, "[+25]% Damage when intercepting")
        val plain = bomber()
        val reduced = bomber("Damage taken from interception reduced by [50]%", "Damage taken from interception reduced by [25]%")
        val tile = game.getTile(2, 0)

        for (attacker in listOf(plain, reduced)) {
            val combatant = MapUnitCombatant(attacker)
            val attack = BattleDamage.getAttackingStrength(MapUnitCombatant(interceptor), combatant, tile) * 125 / 100
            val defence = BattleDamage.getDefendingStrength(MapUnitCombatant(interceptor), combatant, tile)
            val percent = if (attacker == plain) 100 else 25 // -50 and -25 add up to -75, the original's product would leave 37.5
            val expected = BattleDamage.vpDamageMath((attack * 100).toInt(), (defence * 100).toInt(), 0.4f, 2400, 1200, percent)
            assertEquals(expected, AirInterception.interceptionDamageLikeVp(interceptor, combatant, tile, 0.4f))
        }
        assertEquals(-75, reduced.interceptionDefenseDamageModifier())
    }

    //endregion
}
