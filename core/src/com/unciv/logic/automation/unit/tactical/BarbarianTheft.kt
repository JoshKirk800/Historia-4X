package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.INonPerpetualConstruction
import kotlin.math.max
import kotlin.math.min

/**
 *  Barbarians that stand next to a city without a garrison rob it (Vox Populi, MOD_BALANCE_BARBARIAN_THEFT, which Vox Populi turns on): CvTacticalAI::
 *  ExecuteBarbarianTheft, the first of the barbarian attacks, with CvBarbarians::DoStealFromCity. A unit that can attack, next to a city of a
 *  player (not the barbarians') that has no garrison and more than 20 hit points left, pretends to attack it: if the unit would do damage to the city
 *  and not die of what it takes, the city pays them off. The unit takes the damage of a theft (1 up to 30 hit points, but not all it has), and the city
 *  loses, of one randomly chosen yield, up to as many turns of it as the damage the attack would do divided by 30 plus 1 to 4 - at most as many points
 *  as the unit has left: gold from the treasury (2 in 10), culture (2 in 10), science towards the current technology (2 in 10, if one is being
 *  researched), stored food (2 in 10), production of the city's current construction (2 in 10, only if it is some and the construction is not
 *  finished next turn); if the chosen yield gives nothing the unit does not steal, and attacks or goes on as it would. The unit's turn is over after
 *  a theft.
 *
 *  Not ported: CvBarbarians::DoTakeOverCityState (the barbarians take over a city-state whose horde quest was just won, the first call of the
 *  original's ExecuteBarbarianTheft): there are no city-state quests, so no city-state is ever "ready for take-over".
 *
 *  Deviations: the random numbers are Unciv's seeded ones of the barbarians' turn, not the original's pseudo random seeds; the damage the attack would
 *  do is the expected one (the mean of the roll, ignoring flanking), as for the other simulated attacks of the tactical AI.
 */
object BarbarianTheft {

    private const val MIN_CITY_HEALTH = 20
    private const val DAMAGE_PER_THEFT_TURN = 30
    private const val MAX_THEFT_DAMAGE = 31
    private const val THEFT_TURNS_ROLL = 4
    private const val YIELD_ROLLS = 10

    /** CvTacticalAI::ExecuteBarbarianTheft: each unit next to a city tries to steal from it; the thieves are done for the turn */
    fun executeTheft(ctx: TacticalContext) {
        val thieves = ArrayList<MapUnit>()
        for (unit in ctx.liveUnits()) {
            // The first city next to the unit (CvPlot::GetAdjacentCity)
            val city = unit.getTile().neighbors.firstNotNullOfOrNull { if (it.isCityCenter()) it.getCity() else null } ?: continue
            if (stealFromCity(ctx, unit, city)) thieves.add(unit)
        }
        // Done in two steps to keep the loop above safe
        for (unit in thieves) {
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
        }
    }

    /** CvBarbarians::DoStealFromCity: false if stealing is impossible and the unit should do something else instead */
    fun stealFromCity(ctx: TacticalContext, unit: MapUnit, city: City): Boolean {
        if (unit.isDestroyed || !TacticalAttacks.canAttack(unit)) return false
        // Do not steal from ourselves
        if (city.civ.isBarbarian) return false
        // Cannot steal if guarded
        if (city.getGarrison() != null) return false
        // If the city is very low on health, try to take it over outright; this also prevents attempts to steal from a 0 HP city
        if (city.health <= MIN_CITY_HEALTH) return false

        // Pretend the unit attacks this city: the city pays them off, so they do no damage
        val attacker = MapUnitCombatant(unit)
        val defender = CityCombatant(city)
        val tile = unit.getTile()
        val cityDamage = BattleDamage.calculateDamageToDefender(attacker, defender, tile, TacticalTargets.EXPECTED_RANDOMNESS, ignoreFlanking = true)
        val unitDamage = if (unit.baseUnit.isRanged()) 0
            else BattleDamage.calculateDamageToAttacker(attacker, defender, tile, TacticalTargets.EXPECTED_RANDOMNESS, ignoreFlanking = true)
        // Tried but failed
        if (cityDamage <= 0 || unitDamage >= unit.health || unit.health <= 1) return false

        val random = ctx.civ.state.stateBasedRandom("BarbarianTheft ${unit.id} ${city.id} ${ctx.turn}")
        // They get x turns' worth of yields
        val theftTurns = cityDamage / DAMAGE_PER_THEFT_TURN + random.nextInt(1, THEFT_TURNS_ROLL + 1)
        // But they lose some health in exchange
        val damageTaken = random.nextInt(1, max(2, min(unit.health, MAX_THEFT_DAMAGE)))
        val remainingHealth = unit.health - damageTaken
        val stats = city.cityStats.currentCityStats
        val owner = city.civ

        // Which yield is affected?
        val message: String
        when (random.nextInt(1, YIELD_ROLLS + 1)) {
            1, 2 -> {
                val gold = min((stats.gold * theftTurns).toInt(), remainingHealth)
                if (gold <= 0) return false
                owner.addGold(-gold)
                message = "Barbarians stole [$gold] Gold from [${city.name}]!"
            }
            3, 4 -> {
                val culture = min((stats.culture * theftTurns).toInt(), remainingHealth)
                if (culture <= 0) return false
                owner.policies.addCulture(-culture)
                message = "Barbarians stole [$culture] Culture from [${city.name}]!"
            }
            5, 6 -> {
                val current = owner.tech.currentTechnologyName() ?: return false
                val science = min((stats.science * theftTurns).toInt(), remainingHealth)
                if (science <= 0) return false
                owner.tech.techsInProgress[current] = max(0, owner.tech.researchOfTech(current) - science)
                message = "Barbarians stole [$science] Science from [${city.name}]!"
            }
            7, 8 -> {
                val food = min((stats.food * theftTurns).toInt(), remainingHealth)
                if (food <= 0) return false
                city.population.foodStored = max(0, city.population.foodStored - food)
                message = "Barbarians stole [$food] Food from [${city.name}]!"
            }
            else -> {
                val constructions = city.cityConstructions
                val name = constructions.currentConstructionName()
                if (constructions.getCurrentConstruction() !is INonPerpetualConstruction || constructions.getWorkDone(name) <= 0 ||
                    constructions.turnsToConstruction(name) < 2) return false
                val production = min((stats.production * theftTurns).toInt(), remainingHealth)
                if (production <= 0) return false
                constructions.inProgressConstructions[name] = max(0, constructions.getWorkDone(name) - production)
                message = "Barbarians stole [$production] Production from [${city.name}]!"
            }
        }
        unit.health -= damageTaken
        if (owner.isHuman()) owner.addNotification(message, city.location, NotificationCategory.War)
        ctx.log { "${unit.name} at ${tile.position} stole from ${city.name}: $message" }
        return true
    }
}
