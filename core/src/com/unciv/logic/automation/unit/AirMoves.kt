package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.tactical.TacticalAi
import com.unciv.logic.automation.unit.tactical.TacticalAttacks
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.battle.AirInterception
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  The air moves of the original: CvMilitaryAI::WillAirUnitRebase, GetNumEnemyAirUnitsInRange and GetBestAirSweepTarget; the tactical moves
 *  ExecuteAirSweep with FindAirUnitsToAirSweep (the sweep before an attack) and PlotAirPatrolMoves; CvHomelandAI::PlotAircraftMoves / ExecuteAircraftMoves (rebasing
 *  to the base with the highest plot danger, with the carriers' free slots). Aircraft strikes are not here: combat-ready aircraft are strikers of
 *  [com.unciv.logic.automation.unit.tactical.TacticalAttacks] like any shooter, and what the tactical AI leaves of an aircraft is only [plotAircraftMove].
 *
 *  Deviations, with the reason: Unciv has no air patrol action - interception is automatic for a fighter that still has its movement -
 *  so the patrol move only keeps the fighter where it is, unmoved, for the turn; a carrier's free slot is [MapUnit.canTransport] (the
 *  CarryAirUnits uniques of the ruleset, which carry what the original's SpecialUnit_CarrierUnitAI says), rebasing needs the target within
 *  [MapUnit.getAirUnitMovementRange] and [com.unciv.logic.map.mapunit.movement.UnitMovement.canMoveTo]; CvUnit::canAirSweepAt is the sweep range.
 *  Not here: Brave New World's stand-alone PlotAirSweepMoves (Vox Populi has none: its ExecuteAirSweepMoves is never called, fighters sweep in the attack plans).
 */
object AirMoves {

    /** CvMilitaryAI::GetNumEnemyAirUnitsInRange: an enemy air unit counts within its own range, at most this many tiles, plus half the asking unit's */
    private const val MAX_ACCEPTABLE_RANGE = 12

    /** The share of hit points a base may lose before the aircraft in it want to leave (MAX_HIT_POINTS / 5) */
    private const val BASE_DAMAGE_DIVISOR = 5

    /** Half the hit points: an aircraft with this many or fewer is not combat ready (CvUnit::getDamage() * 2 < MAX_HIT_POINTS) */
    private const val HALF_HEALTH = 50

    /** An aircraft with more than half its hit points: the tactical AI recruits it, uses it for strikes and sweeps, and the homeland AI rebases it */
    fun isCombatReady(unit: MapUnit) = unit.health > HALF_HEALTH

    /** CvUnit::IsAirSweepCapable */
    fun isAirSweepCapable(unit: MapUnit) = unit.hasUnique(UniqueType.CanAirsweep)

    /** CvUnit::canAirDefend: the unit intercepts */
    fun canAirDefend(unit: MapUnit) = unit.interceptChance() > 0

    /** CvUnit::canAirSweep: a sweeper that may still attack */
    fun canAirSweep(unit: MapUnit) = unit.baseUnit.isAirUnit() && isAirSweepCapable(unit) && unit.canAttack()

    /** CvUnit::canAirSweepAt: a sweeper that may still attack, [tile] is seen, in the fighter's range, and not the one it is on */
    fun canAirSweepAt(unit: MapUnit, tile: Tile) =
        canAirSweep(unit) && tile.isVisible(unit.civ) && tile != unit.getTile() && unit.getTile().aerialDistanceTo(tile) <= unit.getRange()

    /** The enemy's air units that [civ] counts: at war with it, alive */
    private fun enemyAirUnits(civ: Civilization): Sequence<MapUnit> =
        civ.gameInfo.civilizations.asSequence().filter { it != civ && it.isAlive() && civ.isAtWarWith(it) }
            .flatMap { it.units.getCivUnits() }.filter { it.baseUnit.isAirUnit() }

    /**
     *  CvMilitaryAI::GetNumEnemyAirUnitsInRange (Vox Populi): the air units of players at war with [civ] that are within min(their range, 12) plus half
     *  of [range] tiles of [center] (the caller's range, the fighter's own: fighters stay closer to the bombers that can reach them): fighters (units that
     *  sweep or intercept) if [countFighters], the rest if [countBombers]. Seen or not, as the original.
     */
    fun numEnemyAirUnitsInRange(civ: Civilization, center: Tile, range: Int, countFighters: Boolean, countBombers: Boolean): Int {
        var result = 0
        for (unit in enemyAirUnits(civ)) {
            if (center.aerialDistanceTo(unit.getTile()) > minOf(unit.getRange(), MAX_ACCEPTABLE_RANGE) + range / 2) continue
            if (isAirSweepCapable(unit) || canAirDefend(unit)) { if (countFighters) result++ } else if (countBombers) result++
        }
        return result
    }

    /**
     *  CvMilitaryAI::GetBestAirSweepTarget: the visible city of a player at war with us within the fighter's range that it can sweep and that
     *  holds the most fighters (units that sweep or intercept); null if none holds any.
     */
    fun bestAirSweepTarget(civ: Civilization, fighter: MapUnit): Tile? {
        var best: Tile? = null
        var bestCount = 0
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (city in other.cities) {
                val tile = city.getCenterTile()
                if (!tile.isVisible(civ) || fighter.getTile().aerialDistanceTo(tile) > fighter.getRange() || !canAirSweepAt(fighter, tile)) continue
                val fighters = tile.airUnits.count { isAirSweepCapable(it) || canAirDefend(it) }
                if (fighters > bestCount) { bestCount = fighters; best = tile }
            }
        }
        return best
    }

    /** Whether a base (city or carrier) has lost more than a fifth of its hit points */
    private fun cityInDanger(city: com.unciv.logic.city.City) = city.getMaxHealth() - city.health > city.getMaxHealth() / BASE_DAMAGE_DIVISOR
    private fun carrierDamaged(carrier: MapUnit) = 100 - carrier.health > 100 / BASE_DAMAGE_DIVISOR

    /** The carrier [unit] is on, if it is on one */
    private fun carrierOf(unit: MapUnit): MapUnit? = if (unit.isTransported) unit.getTile().getUnits().firstOrNull { it != unit && it.isTransportTypeOf(unit) } else null

    /** CvUnit::canRebaseAt: [tile] is within the unit's rebase range and it may land there (a city, or a carrier with a free slot) */
    private fun canRebaseAt(unit: MapUnit, tile: Tile) =
        unit.getTile().aerialDistanceTo(tile) <= unit.getAirUnitMovementRange() && unit.movement.canMoveTo(tile)

    /**
     *  CvMilitaryAI::WillAirUnitRebase: an aircraft wants to leave when its base is in danger (a city that lost over a fifth of its hit points,
     *  or a damaged carrier) or, for a fighter, when there are no enemy air units nearby and no city to sweep; and there is somewhere
     *  to go (a healthy carrier with a free slot, in an operation or not, or a healthy city, in range).
     */
    fun willAirUnitRebase(unit: MapUnit): Boolean {
        val civ = unit.civ
        val tile = unit.getTile()
        var needsToMove = false
        val city = tile.getCity()
        if (tile.isCityCenter() && city != null) { if (cityInDanger(city)) needsToMove = true }
        else carrierOf(unit)?.let { if (carrierDamaged(it)) needsToMove = true }
        // Is this a fighter that doesn't have any useful missions nearby
        if (canAirDefend(unit) || canAirSweep(unit)) {
            if (numEnemyAirUnitsInRange(civ, tile, unit.getRange(), countFighters = true, countBombers = true) == 0 && bestAirSweepTarget(civ, unit) == null) needsToMove = true
        }
        if (!needsToMove) return false
        return civ.units.getCivUnits().any { carrier -> usableCarrier(unit, carrier) } ||
            civ.cities.any { !cityInDanger(it) && (it.getCenterTile() == tile || canRebaseAt(unit, it.getCenterTile())) }
    }

    /** A healthy carrier with a free slot that [unit] can rebase to */
    private fun usableCarrier(unit: MapUnit, carrier: MapUnit): Boolean {
        if (carrier == unit || carrierDamaged(carrier) || !carrier.baseUnit.isWaterUnit) return false
        return carrier.canTransport(unit) && canRebaseAt(unit, carrier.getTile())
    }

    /**
     *  CvHomelandAI::ExecuteAircraftMoves for one aircraft: it rebases to the base it can reach with the highest plot danger (>= 0, the last of
     *  equals): a healthy carrier with a free slot (+5000 for a carrier in an operation) or a healthy city that is not being razed; staying put if
     *  its own base is the most dangerous. The unit's turn ends. @return the tile it went to (its own if it stays)
     */
    fun rebaseTarget(unit: MapUnit): Tile {
        val civ = unit.civ
        val danger = civ.ai.plotDanger
        val here = unit.getTile()
        var best: Tile? = null
        var mostDangerous = 0
        // first look for open carrier slots
        for (carrier in civ.units.getCivUnits()) {
            if (!usableCarrier(unit, carrier)) continue
            var value = danger.get(carrier.getTile())
            if (civ.ai.operations.armyOf(carrier) != null) value += CARRIER_IN_OPERATION_BONUS
            if (value >= mostDangerous) { mostDangerous = value; best = carrier.getTile() }
        }
        for (city in civ.cities) {
            val target = city.getCenterTile()
            if (cityInDanger(city) || city.isBeingRazed) continue
            if (target != here && !canRebaseAt(unit, target)) continue
            val value = danger.get(target)
            if (value >= mostDangerous) { mostDangerous = value; best = target }
        }
        return best ?: here
    }

    private const val CARRIER_IN_OPERATION_BONUS = 5000

    /**
     *  CvHomelandAI::PlotAircraftMoves: an aircraft with more than half its health that will rebase does so ([rebaseTarget]) and is done for the
     *  turn. @return whether the aircraft was commanded
     */
    fun plotAircraftMove(unit: MapUnit): Boolean {
        if (!isCombatReady(unit) || !unit.hasMovement() || !willAirUnitRebase(unit)) return false
        val target = rebaseTarget(unit)
        if (target != unit.getTile()) unit.movement.moveToTile(target)
        AiDecisionLog.log(unit.civ) { "Air: ${unit.name} rebases to ${target.position}" }
        unit.currentMovement = 0f
        return true
    }

    /**
     *  Vox Populi's rebasing of a nuclear weapon at war (Release-5.4.8; D7, in place of BNW's [plotAircraftMove], which only leaves a base in
     *  danger, so a nuke in a safe city never comes within range of an enemy city and the nuke operation finds none to launch). In VP,
     *  CvTacticalAI::ShouldRebase is always true for a nuke at war (its city targets never pass the combat unit test), and
     *  CvHomelandAI::ExecuteAircraftMoves takes it to the base with the best [nukeBaseScore] where it fits (HomelandAIHelpers::IsGoodUnitMix,
     *  [isGoodUnitMix]): directly if in range, else the first hop towards it. It stays when its own base scores as high, and then keeps its
     *  movement: our operations run at the start of the turn (CivAi.doTurn), before the units get their movement back, and the nuke attack
     *  needs a nuke that can still move.
     *  Departures: only enemy cities count as targets (VP's comment "for nukes we want only cities"; its code adds 8 per enemy unit too),
     *  the range is the nuke's own (VP assumes 7 for any aircraft), the first hop is the base in reach nearest to the chosen one (VP asks its
     *  air rebase path finder), and a city's border score is not added. At peace the nuke moves as before.
     *  @return whether the nuke was commanded (it rebased and its turn is over)
     */
    fun plotNukeMove(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (!civ.isAtWar() || !unit.baseUnit.isAirUnit()) return plotAircraftMove(unit)
        if (!unit.hasMovement()) return false
        val here = unit.getTile()
        val range = unit.getRange()
        val enemyCities = civ.gameInfo.civilizations.filter { it != civ && civ.isAtWarWith(it) }
            .flatMap { it.cities }.filter { it.getCenterTile().isExplored(civ) }
        val bases = civ.cities.map { it.getCenterTile() } +
            civ.units.getCivUnits().filter { it != unit && it.baseUnit.isWaterUnit && it.canTransport(unit) }.map { it.getTile() }
        val scores = bases.distinct().associateWith { nukeBaseScore(civ, it, range, enemyCities) }
        val hereScore = scores[here] ?: nukeBaseScore(civ, here, range, enemyCities)
        var destination: Tile? = null
        for ((base, score) in scores.entries.sortedByDescending { it.value }) {
            if (score < 0) continue
            if (hereScore >= score) break // apparently we're already in the best possible base
            if (!isGoodUnitMix(base, unit)) continue
            if (canRebaseAt(unit, base)) { destination = base; break }
            // the first hop: a usable base in reach that is nearer to it than we are
            val hop = scores.entries.filter { (tile, value) -> value >= 0 && tile != here && canRebaseAt(unit, tile) && isGoodUnitMix(tile, unit) }
                .map { it.key }.filter { it.aerialDistanceTo(base) < here.aerialDistanceTo(base) }
                .minByOrNull { it.aerialDistanceTo(base) }
            if (hop != null) { destination = hop; break }
        }
        if (destination == null) return false
        unit.movement.moveToTile(destination)
        AiDecisionLog.log(civ) { "Air: ${unit.name} rebases to ${destination.position} for combat (score ${scores[destination]})" }
        unit.currentMovement = 0f
        return true
    }

    /**
     *  HomelandAIHelpers::ScoreAirBase for a nuke: -1 for a base that may be lost (a city in danger, taken less than 3 turns ago, or being
     *  razed below 3 population; a damaged carrier), else 1 plus 20 for each enemy city within [range] that is not in danger itself.
     */
    fun nukeBaseScore(civ: Civilization, base: Tile, range: Int, enemyCities: List<com.unciv.logic.city.City>): Int {
        val city = base.getCity()?.takeIf { base.isCityCenter() && it.civ == civ }
        if (city != null) {
            if (cityInDanger(city)) return -1
            if (civ.gameInfo.turns - city.turnAcquired < RECENTLY_ACQUIRED_TURNS) return -1
            if (city.isBeingRazed && city.population.population < RAZED_MIN_POPULATION) return -1
        } else {
            val carrier = base.getUnits().firstOrNull { it.civ == civ && it.baseUnit.isWaterUnit } ?: return -1
            if (carrierDamaged(carrier)) return -1
        }
        return 1 + enemyCities.count { base.aerialDistanceTo(it.getCenterTile()) <= range && !cityInDanger(it) } * ENEMY_CITY_SCORE
    }

    /** HomelandAIHelpers::IsGoodUnitMix for an offensive aircraft: fewer offensive aircraft on [base] than its defensive ones plus 3 */
    fun isGoodUnitMix(base: Tile, unit: MapUnit): Boolean {
        var offensive = 0
        var defensive = 0
        for (other in base.airUnits) {
            if (other == unit) continue
            when (UnitAiTypes.current(other)) {
                UnitAiType.DEFENSE_AIR -> defensive++
                UnitAiType.ATTACK_AIR, UnitAiType.ICBM, UnitAiType.MISSILE_AIR -> offensive++
                else -> Unit
            }
        }
        return offensive < defensive + MIX_MARGIN
    }

    private const val RECENTLY_ACQUIRED_TURNS = 3
    private const val RAZED_MIN_POPULATION = 3
    private const val ENEMY_CITY_SCORE = 20
    private const val MIX_MARGIN = 3

    //region Tactical moves

    /**
     *  CvPlot::GetInterceptorCount(civ, no unit, any domain, visible only): the units of players at war with [civ] that [civ] sees and that can
     *  intercept an air attack on [tile] now. Barbarians never intercept (CvPlot::GetBestInterceptor does not look at them).
     */
    fun interceptorCount(civ: Civilization, tile: Tile): Int =
        civ.gameInfo.civilizations.asSequence().filter { it != civ && !it.isBarbarian && it.isAlive() && civ.isAtWarWith(it) }
            .flatMap { it.units.getCivUnits() }.count { it.getTile().isVisible(civ) && it.canIntercept(tile) }

    /**
     *  CvTacticalAI::FindAirUnitsToAirSweep: the units of this turn that can sweep [target], at most as many as there are interceptors (one at
     *  least, a fighter may be there for recon), the strongest first. The sweep strength is the ranged strength: the original also scales it by
     *  the sweep modifier of the unit's promotions, which the ruleset does not have.
     */
    fun findAirUnitsToAirSweep(ctx: TacticalContext, target: Tile): List<MapUnit> {
        var interceptions = maxOf(1, interceptorCount(ctx.civ, target))
        val result = ArrayList<MapUnit>()
        for (unit in ctx.liveUnits()) {
            if (interceptions <= 0) break
            if (!unit.baseUnit.isAirUnit() || !TacticalAi.canUseForTacticalAi(unit, ctx.cache) || !canAirSweepAt(unit, target)) continue
            result.add(unit)
            interceptions--
        }
        // std::stable_sort: the order we would like them to attack in
        return result.sortedWith(compareByDescending<MapUnit> { it.baseUnit.rangedStrength }.thenByDescending { it.health })
    }

    /** CvUnit::airSweep for the unit's mission: the unit sweeps [target] and counts as having attacked */
    private fun performAirSweep(unit: MapUnit, target: Tile) {
        unit.action = UnitActionType.AirSweep.value
        AirInterception.airSweep(MapUnitCombatant(unit), target)
        if (!unit.isDestroyed && unit.isPreparingAirSweep()) unit.action = null
    }

    /**
     *  CvTacticalAI::ExecuteAirSweep, before an attack on [target]: if enemy interceptors are to be seen over it, the fighters found for it
     *  ([TacticalContext.airSweepUnits]) sweep it, each commanded once it is out of attacks. A fighter that is only there for recon (no interceptor)
     *  is not sent.
     */
    fun executeAirSweep(ctx: TacticalContext, target: Tile) {
        if (interceptorCount(ctx.civ, target) == 0) return
        for (unit in ctx.airSweepUnits) {
            if (unit.isDestroyed || !unit.hasMovement() || !canAirSweepAt(unit, target)) continue
            ctx.log { "starting air sweep with ${unit.name} at ${unit.getTile().position} before the attack on ${target.position}" }
            performAirSweep(unit, target)
            if (!unit.isDestroyed && TacticalAttacks.isOutOfAttacks(unit)) ctx.unitProcessed(unit, markTacticalMap = false)
        }
    }

    /**
     *  The last resort of CvTacticalAI::ExecuteSpotterMove: when no ground unit can get a view of [target], a fighter sweeps a visible tile next to it
     *  to see it (recon). The original commands the unit by an index that is a direction number there; this commands the unit that swept.
     *  @return whether a sweep was made
     */
    fun sweepForRecon(ctx: TacticalContext, target: Tile): Boolean {
        for (unit in ctx.airSweepUnits) {
            if (unit.isDestroyed || !unit.hasMovement()) continue
            val adjacent = target.neighbors.firstOrNull { it.isVisible(ctx.civ) && canAirSweepAt(unit, it) } ?: continue
            ctx.log { "using air sweep for recon: ${unit.name} at ${adjacent.position}" }
            performAirSweep(unit, adjacent)
            if (!unit.isDestroyed && TacticalAttacks.isOutOfAttacks(unit)) ctx.unitProcessed(unit, markTacticalMap = false)
            return true
        }
        return false
    }

    /**
     *  CvTacticalAI::PlotAirPatrolMoves / ExecuteAirPatrolMoves: a fighter of the tactical AI (they will not rebase, [TacticalAi.recruitUnits]) that
     *  can intercept (CvUnit::canAirPatrol: an air unit with an intercept chance) goes on air patrol when fewer fighters than wanted already patrol
     *  from its tile this turn: half the enemy bombers in its range (at least one interception if one bomber is there) plus a quarter of their
     *  fighters ([numEnemyAirUnitsInRange] of the unit's own range). Going on patrol is staying where it is: it keeps its movement to intercept,
     *  and the tactical AI is done with it.
     */
    fun plotAirPatrolMoves(ctx: TacticalContext) {
        val patrolling = HashMap<Tile, Int>()
        for (unit in ctx.liveUnits()) {
            if (!unit.baseUnit.isAirUnit() || !canAirDefend(unit)) continue
            val tile = unit.getTile()
            var bombers = numEnemyAirUnitsInRange(ctx.civ, tile, unit.getRange(), countFighters = false, countBombers = true)
            val fighters = numEnemyAirUnitsInRange(ctx.civ, tile, unit.getRange(), countFighters = true, countBombers = false)
            // To at least intercept once if only one bomber found
            if (bombers == 1) bombers++
            val wanted = bombers / 2 + fighters / 4
            if ((patrolling[tile] ?: 0) >= wanted) continue
            patrolling.merge(tile, 1, Int::plus)
            ctx.log { "starting air patrol at ${tile.position} with ${unit.name}" }
            ctx.unitProcessed(unit, markTacticalMap = false)
        }
    }
    //endregion
}
