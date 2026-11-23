package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.AirMoves
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  One unit that can strike a target (CvTacticalUnit): [attackStrength] and [healthPercent] rank it ([attackPriority], highest first).
 */
class Striker(
    val unit: MapUnit,
    val attackStrength: Int,
    val healthPercent: Int,
    /** The tile it would attack from: the one it stands on if it shoots or is adjacent, else the cheapest it can move to */
    val attackFrom: Tile? = null,
) {
    val attackPriority get() = attackStrength * healthPercent
    override fun toString() = unit.name
}

/** One city that can shoot a target (CvTacticalCity) */
class CityStriker(val city: City) {
    var expectedTargetDamage = 0
}

/** One attack of a series (CvQueuedAttack): a city's or a unit's, with the tile a melee unit was assigned to attack from */
class QueuedAttack(val unit: MapUnit?, val city: City?, val ranged: Boolean, val attackFrom: Tile?)

/**
 *  A series of attacks on one target (CvTacticalAI::QueueAttack / LaunchAttack / CombatResolved): cities and ranged units first, melee
 *  units after. [TacticalAttacks.launch] makes the attacks one after the other; when the target is gone the rest of the series is
 *  released, and those units are still the tactical AI's.
 */
class AttackSeries(val target: TacticalTarget) {
    val attacks = ArrayList<QueuedAttack>()

    fun addCity(city: City) { attacks.add(QueuedAttack(null, city, true, null)) }
    fun addUnit(unit: MapUnit, ranged: Boolean, attackFrom: Tile?) { attacks.add(QueuedAttack(unit, null, ranged, attackFrom)) }
    fun contains(unit: MapUnit) = attacks.any { it.unit == unit }
    fun isEmpty() = attacks.isEmpty()
}

/**
 *  How the tactical AI finds what can strike a tile (CvTacticalAI): which of our units and cities can strike it, for the moves that are not
 *  the combat simulation's ([TacticalSimulation]: pillaging, capturing civilians and camps, plundering, bombards), and the series of attacks
 *  those moves launch.
 *
 *  Sources: CvTacticalAI::FindUnitsWithinStrikingDistance, FindCitiesWithinStrikingDistance, ComputeTotalExpectedBombardDamage,
 *  QueueAttack / LaunchAttack / CombatResolved.
 *
 *  Deviations:
 *  - "can reach the target in N turns" is Unciv's reach: a unit that can attack (melee: move beside the target with movement left; ranged:
 *    shoot from where it stands) or, for targets without an enemy on them, move onto the tile this turn (0 turns: with movement left)
 *    or along its path (more turns). The original's ignore-units path finder has no counterpart;
 *  - the fire support of the defender's ranged units is not part of the expected damage (Unciv has no such rule);
 *  - the series is made one attack at a time as the fights resolve (they do at once in Unciv, so there is no queue to wait on): a
 *    unit's melee move up to its attack tile happens when its turn in the series comes, not when the series is planned, so units whose
 *    attack is no longer needed do not move;
 *  - an attacker whose attack turns out to be impossible when its turn comes (the target is hidden, it is blocked) simply stays unused;
 *  - an aircraft strikes through [Battle.moveAndAttack] like any shooter (the original sends it on a move-to mission): interception is Unciv's
 *    [com.unciv.logic.battle.AirInterception], and the damage its strike costs it is not part of the expected damage, as in the original.
 */
object TacticalAttacks {

    private const val EPSILON = Constants.minimumMovementEpsilon

    //region Reach

    /** CvUnit::GetMaxAttackStrength with no target: what a unit that shoots fights with, else its attack in the open */
    fun maxAttackStrength(unit: MapUnit): Int =
        if (unit.baseUnit.isRanged()) unit.baseUnit.strength else MapUnitCombatant(unit).getAttackingStrength(null)

    /**
     *  CvUnit::IsCanAttack: units that fight, aircraft by their ranged strike. Nuclear weapons have no attack strength in the original (the nuke
     *  operation sets them off), so they never are strikers.
     */
    fun canAttack(unit: MapUnit) = unit.isMilitary() && !unit.isNuclearWeapon() && !unit.hasUnique(UniqueType.CannotAttack)

    /** CvUnit::isOutOfAttacks */
    fun isOutOfAttacks(unit: MapUnit) = unit.attacksThisTurn >= unit.maxAttacksPerTurn()

    /** CvUnit::IsCityAttackOnly: siege units that only attack cities (the unique "Can only attack [City] units") */
    fun isCityAttackOnly(unit: MapUnit) = unit.getMatchingUniques(UniqueType.CanOnlyAttackUnits).any { it.params[0] == "City" }

    /** The ways [unit] can attack what is on [tile] now: from where it stands if [stayOnTile] (it shoots), else after moving as far as it may */
    fun attackOptions(unit: MapUnit, tile: Tile, stayOnTile: Boolean): List<AttackableTile> {
        if (!unit.hasMovement()) return emptyList()
        // A unit that must set up to shoot needs a movement point for it and one to shoot with
        if (stayOnTile && unit.hasUnique(UniqueType.MustSetUp) && !unit.isSetUpForSiege() && unit.currentMovement - 1f <= EPSILON) return emptyList()
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), listOf(tile), stayOnTile)
    }

    /** The best way to attack from [options]: from [preferred] if it is one, else the one that leaves the most movement */
    fun bestOption(options: List<AttackableTile>, preferred: Tile?): AttackableTile? =
        options.firstOrNull { it.tileToAttackFrom == preferred } ?: options.maxByOrNull { it.movementLeftAfterMovingToAttackTile }

    /**
     *  CanReachInXTurns / TurnsToReachTarget for a tile we want to move onto: 0 turns means this turn with movement left, 1 means this turn
     *  (the unit stops there), more is the number of turns along its path. [Int.MAX_VALUE] if it cannot get there.
     */
    fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (tile == unit.getTile()) return 0
        val reachable = unit.movement.getDistanceToTiles()[tile]
        if (reachable != null) return if (reachable.totalMovement < unit.currentMovement - EPSILON) 0 else 1
        val path = unit.movement.getShortestPath(tile)
        return if (path.isEmpty()) Int.MAX_VALUE else maxOf(path.size, 2)
    }

    /** Whether [unit] can move onto [tile] and get there within [turns] turns (see [turnsToReach]); it must be able to enter the tile this turn if it can reach it this turn */
    private fun canMoveOnto(unit: MapUnit, tile: Tile, turns: Int): Boolean {
        val needed = turnsToReach(unit, tile)
        if (needed > turns) return false
        return needed >= 2 || tile == unit.getTile() || unit.movement.canMoveTo(tile)
    }

    //endregion
    //region Finding strikers

    /**
     *  CvTacticalAI::FindUnitsWithinStrikingDistance: the units of the tactical AI that can strike [tile] within [turns] turns, strongest
     *  and healthiest first (ranged ones before all others).
     *  @param preferredDamage when pillaging: units with more damage than this count three times as strong (they want to heal)
     *  @param noRanged leave out units that shoot; @param navalOnly only ships; @param mustMoveThrough they must be able to get there with
     *  movement to spare;
     *  @param willPillage the unit pillages the tile (shooters do not); @param targetUndefended the tile has nobody to fight, units just move
     *  onto it (half strength)
     *
     *  An aircraft strikes only as a shooter: more than half its health ([AirMoves.isCombatReady]), the target within its range (line of sight is
     *  no matter), a defender it is expected to damage. The original's other ways to a target (move onto it, move beside it) path-find, which it
     *  never does for aircraft, so aircraft are left out when [noRanged], [willPillage] or [targetUndefended] ask for those.
     */
    fun findUnits(
        ctx: TacticalContext, tile: Tile, turns: Int, preferredDamage: Int = 0, noRanged: Boolean = false, navalOnly: Boolean = false,
        mustMoveThrough: Boolean = false, willPillage: Boolean = false, targetUndefended: Boolean = false,
    ): List<Striker> {
        val isCityTarget = tile.isCityCenter()
        val hasEnemy = Battle.getMapCombatantOfTile(tile)?.let { it.getCivInfo() != ctx.civ && ctx.civ.isAtWarWith(it.getCivInfo()) } == true
        val result = ArrayList<Striker>()
        for (unit in ctx.liveUnits()) {
            if (navalOnly && !unit.baseUnit.isWaterUnit) continue
            if (!canAttack(unit) || isOutOfAttacks(unit) || !unit.hasMovement()) continue
            val air = unit.baseUnit.isAirUnit()
            if (air && (noRanged || willPillage || targetUndefended)) continue
            if (!isCityTarget && isCityAttackOnly(unit)) continue
            if (willPillage && !UnitActionsPillage.canPillage(unit, tile)) continue
            val ranged = unit.baseUnit.isRanged()
            // Cannot strike from farther than it moves in the turns and shoots
            val distance = unit.getTile().aerialDistanceTo(tile)
            if (distance > unit.getMaxMovement() * maxOf(turns, 1) + (if (ranged) unit.getRange() else 1)) continue

            var strength = maxAttackStrength(unit)
            val health = unit.health
            if (preferredDamage > 0 && unit.getMaxHealth() - health > preferredDamage) strength *= 3

            if (targetUndefended) {
                if (canMoveOnto(unit, tile, turns)) result.add(Striker(unit, strength / 2, health))
            } else if (!noRanged && !willPillage && ranged) {
                // Want ranged units to attack first, so inflate this; they take no damage from bombarding, so they show as fully healthy
                if (hasEnemy && (!air || AirMoves.isCombatReady(unit)) && isExpectedToDamageWithRangedAttack(unit, tile) && attackOptions(unit, tile, true).isNotEmpty())
                    result.add(Striker(unit, 100 * unit.baseUnit.rangedStrength, 100, unit.getTile()))
            } else if (hasEnemy && !willPillage) {
                val option = attackOptions(unit, tile, false).let { bestOption(it, null) }
                if (option != null) result.add(Striker(unit, strength, health, option.tileToAttackFrom))
            } else if (mustMoveThrough) {
                if (canMoveOnto(unit, tile, 0)) result.add(Striker(unit, strength, health))
            } else if (canMoveOnto(unit, tile, turns)) result.add(Striker(unit, strength, health))
        }
        // std::stable_sort: in the order we'd like them to attack
        return result.sortedByDescending { it.attackPriority }
    }

    /** CvCity::canRangeStrikeAt: the city can shoot what is on [tile] */
    fun canRangeStrikeAt(city: City, tile: Tile): Boolean =
        city.canBombard() && city.getCenterTile().aerialDistanceTo(tile) <= city.getBombardRange()
            && tile.isVisible(city.civ) && TargetHelper.containsAttackableEnemy(tile, CityCombatant(city))

    /** CvTacticalAI::FindCitiesWithinStrikingDistance: our cities that can shoot [tile] this turn */
    fun findCities(ctx: TacticalContext, tile: Tile): List<CityStriker> =
        ctx.civ.cities.filter { canRangeStrikeAt(it, tile) }.map { CityStriker(it) }

    /**
     *  CvCity::getBestRangedStrikeTarget: the tile of the unit [city] shoots at when it shoots at anything: of the enemy units it sees within its
     *  bombard range (at most 5 tiles; never a city, as CvCity::canRangeStrikeAt) the one with the best score: the expected damage, 15 more for a kill,
     *  10 more for a city bombard unit; a civilian is worth 1, a great general or admiral 2 (10 more if killed). The first of the best wins (the
     *  original walks the rings around the city, this the tiles by index); null if the city cannot shoot or nothing it can shoot would be damaged.
     */
    fun bestRangedStrikeTarget(city: City): Tile? {
        if (!city.canBombard()) return null
        val cityCombatant = CityCombatant(city)
        val rules = city.civ.gameInfo.ruleset.ai
        var bestScore = 0
        var best: Tile? = null
        for (tile in TargetHelper.getBombardableTiles(city).sortedBy { it.zeroBasedIndex }) {
            if (tile.isCityCenter() || city.getCenterTile().aerialDistanceTo(tile) > MAX_STRIKE_RING) continue
            val target = Battle.getMapCombatantOfTile(tile) as? MapUnitCombatant ?: continue
            val unit = target.unit
            var damage = BattleDamage.calculateDamageToDefender(cityCombatant, target, city.getCenterTile(), TacticalTargets.EXPECTED_RANDOMNESS)
            if (damage <= 0) continue
            var bonus = 0
            // It is good to kill units
            if (damage >= unit.health) bonus += KILL_BONUS
            // It is good to damage city bombard units
            if (UnitAiTypes.defaultOf(unit.baseUnit, rules) == UnitAiType.CITY_BOMBARD) bonus += BOMBARD_UNIT_BONUS
            // It is generally not useful to bombard civilians
            else if (!unit.isMilitary()) {
                if (TacticalTargets.isVeryHighPriorityCivilian(unit)) {
                    // Killing great generals and admirals is good though
                    if (damage >= unit.health) bonus = GREAT_PERSON_KILL_BONUS else damage = GREAT_PERSON_DAMAGE
                } else damage = CIVILIAN_DAMAGE
            }
            if (damage + bonus > bestScore) { bestScore = damage + bonus; best = tile }
        }
        return best
    }

    /**
     *  The fallback of CvTacticalAI::ReviewUnassignedUnits: a city that can still shoot at a unit - whose target the tactical targets suppressed - does
     *  now, at the [bestRangedStrikeTarget].
     */
    fun cityFallbackShots(ctx: TacticalContext) {
        for (city in ctx.civ.cities.toList()) {
            val tile = bestRangedStrikeTarget(city) ?: continue
            val defender = Battle.getMapCombatantOfTile(tile) ?: continue
            ctx.log { "${city.name} shoots at ${tile.position} (fallback)" }
            Battle.attackOrNuke(CityCombatant(city), AttackableTile(city.getCenterTile(), tile, 0f, defender))
        }
    }

    private const val MAX_STRIKE_RING = 5
    private const val KILL_BONUS = 15
    private const val BOMBARD_UNIT_BONUS = 10
    private const val GREAT_PERSON_KILL_BONUS = 10
    private const val GREAT_PERSON_DAMAGE = 2
    private const val CIVILIAN_DAMAGE = 1

    /**
     *  CvTacticalAI::IsExpectedToDamageWithRangedAttack: [unit] shooting from where it stands is expected (the mean of the randomness) to
     *  do damage to the city on [tile] or else to the defender there, the military unit that can defend. A tile with only a civilian or an
     *  embarked unit has no defender, so no damage is expected and no ranged unit is used on it.
     */
    fun isExpectedToDamageWithRangedAttack(unit: MapUnit, tile: Tile): Boolean {
        val defender: ICombatant = if (tile.isCityCenter()) CityCombatant(tile.getCity()!!)
            else MapUnitCombatant(TacticalTargets.visibleEnemyDefender(unit.civ, tile) ?: return false)
        return BattleDamage.calculateDamageToDefender(MapUnitCombatant(unit), defender, unit.getTile(), TacticalTargets.EXPECTED_RANDOMNESS) > 0
    }

    //endregion
    //region Expected damage

    /** CvTacticalAI::ComputeTotalExpectedBombardDamage: what [cities] do to [defender] by shooting at it */
    fun computeTotalExpectedBombardDamage(defender: ICombatant, cities: List<CityStriker>): Int {
        var total = 0
        for (striker in cities) {
            striker.expectedTargetDamage = BattleDamage.calculateDamageToDefender(
                CityCombatant(striker.city), defender, striker.city.getCenterTile(), TacticalTargets.EXPECTED_RANDOMNESS)
            total += striker.expectedTargetDamage
        }
        return total
    }

    //endregion
    //region Launching attacks

    /**
     *  CvTacticalAI::LaunchAttack and CombatResolved: make the attacks of [series] one after the other. When the target is gone (killed, or
     *  the city taken) the follow-up attacks are dropped, their units are free for other moves. A unit that has been in a fight is
     *  done for the turn, unless it still can attack or move after attacking.
     *  @return whether an attack was made
     */
    fun launch(ctx: TacticalContext, series: AttackSeries): Boolean {
        val target = series.target
        var attacked = false
        for (queued in series.attacks) {
            if (!target.isAlive(ctx.civ)) break
            val defender = Battle.getMapCombatantOfTile(target.tile) ?: break
            val city = queued.city
            if (city != null) {
                if (!city.canBombard()) continue
                ctx.log { "${city.name} bombards ${target.tile.position}" }
                Battle.attackOrNuke(CityCombatant(city), AttackableTile(city.getCenterTile(), target.tile, 0f, defender))
                attacked = true
                continue
            }
            val unit = queued.unit!!
            if (unit.isDestroyed || unit.civ != ctx.civ || !unit.canAttack()) continue
            val option = bestOption(attackOptions(unit, target.tile, queued.ranged), queued.attackFrom) ?: continue
            ctx.log { "${unit.name} at ${unit.getTile().position} attacks ${target.tile.position} from ${option.tileToAttackFrom.position}" }
            val attacksBefore = unit.attacksThisTurn
            Battle.moveAndAttack(MapUnitCombatant(unit), option)
            if (unit.isDestroyed || unit.attacksThisTurn > attacksBefore) attacked = true
            // CombatResolved: an attacker that can still attack or move after attacking is released to be processed again
            val released = !unit.isDestroyed && unit.hasMovement() && (unit.canAttack() || unit.hasUnique(UniqueType.CanMoveAfterAttacking))
            if (!released) ctx.unitProcessed(unit, queued.ranged)
        }
        return attacked
    }

    //endregion
}
