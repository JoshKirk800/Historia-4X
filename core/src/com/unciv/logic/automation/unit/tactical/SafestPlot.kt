package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A tile a unit can reach this turn and the movement it has left there ([TacticalAIHelpers]'s pair of plot and moves left) */
class ReachedTile(val tile: Tile, val movesLeft: Float)

/**
 *  Where a combat unit goes to get out of harm's way and what it does on the way: CvTacticalAI::PlotMovesToSafety and ExecuteMovesToSafestPlot,
 *  and TacticalAIHelpers::FindSafestPlotInReach for units that are not civilians (the civilians' is [EscortMoves]'s own copy). Vox Populi 5.4.8 (the
 *  Community Patch and Vox Populi share them: there is no MOD_BALANCE_VP branch). Danger is the expected damage of [UnitDanger] (CvUnit::GetDanger).
 *
 *  Deviations: a plot that holds a unit of ours of the same type is taken by swapping places with it ([com.unciv.logic.map.mapunit.movement.UnitMovement.swapMoveToTile], only where [com.unciv.logic.map.mapunit.movement.UnitMovement.canUnitSwapTo] says both units can),
 *  where the original pushes the unit out of the way; "the city distance" is the distance to our nearest city (GetCityDistancePathLength);
 *  the closest city to go home to is the nearest on the unit's landmass, not by path length; the unit's own plot is asked for pillaging after the
 *  retreat, where the original asks about the plot it left (a likely slip: the mission pillages the plot the unit stands on).
 */
object SafestPlot {

    private const val MAX_HEALTH = 100
    private const val LINEAR_DANGER = 144
    private const val UNLIMITED_DANGER = 10000
    /** PlotMovesToSafety's sorting keys for the units that flee: ranged units start at 30, the others at 20, less their movement */
    private const val RANGED_RETREAT_KEY = 30
    private const val MELEE_RETREAT_KEY = 20
    /** ExecuteMovesToSafestPlot: a unit that stays on its plot shoots first only with more than 3/5 of its hit points */
    private const val HEALTHY_FRACTION_NUMERATOR = 3
    private const val HEALTHY_FRACTION_DENOMINATOR = 5

    private class Option(val tile: Tile, val movesLeft: Float, val score: Int)

    /** CvCity::isInDangerOfFalling is asked of a friendly city: it is no safe haven then */
    private fun isSafeCity(civ: Civilization, tile: Tile) =
        tile.isCityCenter() && tile.getCity()?.let { it.civ == civ && !CityDangerAi.isInDangerOfFalling(it, false) } == true

    /** TacticalAIHelpers::IsPlayerCitadel for a land combat unit */
    private fun isCitadelFor(ctx: TacticalContext, unit: MapUnit, tile: Tile) =
        unit.isMilitary() && unit.baseUnit.isLandUnit && !ctx.civ.isBarbarian && ctx.map.isPlayerCitadel(tile, ctx.civ)

    /** CvUnit::isNativeDomain(plot): a ship on water or in a city, a land unit that is not embarked there */
    private fun isNativeDomain(unit: MapUnit, tile: Tile) =
        if (unit.baseUnit.isWaterUnit) tile.isWater || tile.isCityCenter() else !unit.isEmbarkedAt(tile)

    private fun sameDomain(a: MapUnit, b: MapUnit) =
        a.baseUnit.isWaterUnit == b.baseUnit.isWaterUnit && a.baseUnit.isAirUnit() == b.baseUnit.isAirUnit()

    /**
     *  TacticalAIHelpers::FindSafestPlotInReach(unit, allowEmbark, considerPush): among the plots the unit reaches this turn (and its own), without
     *  enemy defenders and cities (it may capture a civilian), the best of the first non-empty list: our cities that are not in danger of falling and
     *  citadels (lowest score first), plots with a defender of ours covering the unit (for civilians and units that cannot fight where they are),
     *  plots with no danger (the farthest from our cities, twice as far counts when outside our land), the rest, [allowEmbark] unless the unit would
     *  have to embark. A unit in a safe city or citadel that does not need to flee, or that covers a civilian under a danger below twice its health,
     *  stays. The score of a plot is its danger (its square root beyond 144), plus: the damage a unit that can outheal the danger would lose by moving
     *  and the loss of fortification, 12 outside our land or when it would have to embark, a quarter if the enemy sees the plot, a twentieth next to
     *  enemy land, a tenth next to enemy units or cities, 13 for each enemy unit next to it less 13 for each of ours, 7 for each coast of a ship
     *  that is not ours; ten times that plus the distance to our nearest city (three times for ships), 250 more on water for a land unit.
     *  A defender that covers the unit is kept in place for the turn (after it shoots, if it can).
     */
    fun find(ctx: TacticalContext, unit: MapUnit, allowEmbark: Boolean, considerPush: Boolean = false): ReachedTile? {
        // Use rebase moves for aircraft!
        if (unit.baseUnit.isAirUnit()) return null
        val civ = ctx.civ
        val danger = civ.ai.unitDanger
        val current = unit.getTile()
        val moves = unit.currentMovement
        // What the unit reaches changes as other units move: the cached reach may be stale
        unit.movement.clearPathfindingCache()

        // Special behavior for cities and citadels - don't run even if there is a "safe" plot somewhere else
        val inCityOrCitadelNow = isSafeCity(civ, current) || isCitadelFor(ctx, unit, current)
        if (inCityOrCitadelNow && !unit.isProjectedToDieNextTurn() && !unit.isEmbarked())
            if (UnitAiTypes.current(unit) != UnitAiType.CITY_BOMBARD || danger.get(unit, current) < unit.health) return ReachedTile(current, moves)

        // For the current plot
        val currentHealRate = TacticalSafety.actualHealRate(unit)
        val currentDanger = danger.get(unit)
        // Don't run if we are needed
        if (TacticalSafety.isCoveringFriendlyCivilian(ctx, unit) && danger.get(unit, current) < unit.health * 2) return ReachedTile(current, moves)

        val reachable = unit.movement.getDistanceToTiles()
        val tiles = (reachable.keys.asSequence() + current).distinct().sortedBy { it.zeroBasedIndex }.toList()
        val cityList = ArrayList<Option>()
        val coverList = ArrayList<Option>()
        val zeroDangerList = ArrayList<Option>()
        val dangerList = ArrayList<Option>()
        val enemies = civ.getCivsAtWarWith()
        val alwaysHeals = unit.hasUnique(UniqueType.HealsEvenAfterAction)
        val damage = MAX_HEALTH - unit.health
        for (tile in tiles) {
            // Don't attack though
            if (TacticalTargets.visibleEnemyDefender(civ, tile) != null || ctx.isEnemyCity(tile)) continue
            // If we cannot move in because one of our own units is blocking us, we may swap with it
            if (tile != current && !unit.movement.canMoveTo(tile)) {
                if (!considerPush || !unit.movement.canUnitSwapTo(tile)) continue
            }
            val movesLeft = if (tile == current) moves else max(moves - (reachable[tile]?.totalMovement ?: moves), 0f)

            // Plot danger is a bit unreliable, so we need extra checks
            var plotDanger = danger.get(unit, tile)
            val cityDistance = civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 0
            val zeroDanger = plotDanger <= 0
            val inTerritory = tile.getOwner() == civ
            // Citadels have low danger but not zero, so we need to make sure we're not abandoning them too easily
            val inCityOrCitadel = isSafeCity(civ, tile) || isCitadelFor(ctx, unit, tile)

            // Civilians and embarked units want cover
            var inCover = false
            if (unit.isCivilian() || !isNativeDomain(unit, tile)) {
                val defender = tile.militaryUnit?.takeIf { it.civ == civ && it != unit }
                if (defender != null && !defender.isProjectedToDieNextTurn() && danger.get(defender) < defender.health) {
                    inCover = true
                    // Otherwise we would get only the maximum for civilians
                    plotDanger = danger.get(defender, tile)
                }
            }

            val wrongDomain = tile.isWater && unit.baseUnit.isLandUnit && !unit.cache.canMoveOnWater
            val wouldEmbark = wrongDomain && !unit.isEmbarked()
            // Map 144 to 144, everything above is not so important
            if (plotDanger >= Int.MAX_VALUE) plotDanger = UNLIMITED_DANGER
            var score = if (plotDanger > LINEAR_DANGER) 12 * sqrt(plotDanger.toDouble()).toInt() else plotDanger

            if (tile != current && !unit.hasUnitMovedThisTurn()) {
                // We can't heal after moving and lose the fortification bonus, so the current plot gets a bonus (respectively all others a penalty)
                if (unit.canFortify()) score += 3
                // We can outheal the danger, should stay and heal
                if (currentHealRate > 0 && damage >= currentHealRate && currentHealRate > currentDanger && !alwaysHeals) score += max(0, currentHealRate * 2)
            }
            // Safer at home ... but not if we need to embark b/c we can't fight back then
            if (!inTerritory || wouldEmbark) score += 12
            // Try to hide - if there are few enemy units, this might be a tiebreaker
            if (enemies.any { tile in it.viewableTiles }) score += score / 4
            // Avoid enemy territory
            if (tile.neighbors.any { it.isEnemyTerritory(civ) }) score += score / 20
            // Avoid enemy units
            if (tile.neighbors.any { n -> n.getUnits().any { civ.isAtWarWith(it.civ) } || ctx.isEnemyCity(n) }) score += score / 10
            // Naval units should avoid enemy coast, never know what's hiding there
            if (unit.baseUnit.isWaterUnit) score += tile.neighbors.count { !it.isWater && it.getOwner() != civ } * 7

            if (!inCityOrCitadel) {
                // Try to go where our friends are, don't go where our foes are
                val friends = tile.neighbors.count { n -> n.militaryUnit?.let { it.civ == civ && it != unit } == true }
                val foes = tile.neighbors.count { n ->
                    n.isVisible(civ) && n.militaryUnit?.let { civ.isAtWarWith(it.civ) && !it.isEmbarked() && sameDomain(it, unit) } == true
                }
                score += (foes - friends) * 13
                // Use city distance as tiebreaker; naval units should try to go home to heal, even if it's considered dangerous
                score = if (unit.baseUnit.isWaterUnit) score * 3 + cityDistance else score * 10 + cityDistance
            }
            // Discourage water tiles for land units
            if (wrongDomain) score += 250

            when {
                inCityOrCitadel -> cityList.add(Option(tile, movesLeft, score))
                inCover -> coverList.add(Option(tile, movesLeft, score))
                // If danger is zero, look at distance to closest owned city instead
                zeroDanger -> zeroDangerList.add(Option(tile, movesLeft, if (inTerritory) -cityDistance else -cityDistance * 2))
                !wouldEmbark || allowEmbark -> dangerList.add(Option(tile, movesLeft, score))
            }
        }

        // The lists are sorted descending and the original takes the last: the lowest score, the later plot of equals
        fun best(list: List<Option>): Option? = list.fold(null as Option?) { acc, option -> if (acc == null || option.score <= acc.score) option else acc }
        best(cityList)?.let { return ReachedTile(it.tile, it.movesLeft) }
        best(coverList)?.let { chosen ->
            // Taking cover only works if the defender will not move away! Since we move civilians only after the combat units have moved it should be safe to pin it
            val defender = chosen.tile.militaryUnit?.takeIf { it.civ == civ && it != unit && it !in ctx.cache.processedUnits }
            if (defender != null) {
                TacticalSimulation.performRangedOpportunityAttack(ctx, defender, false)
                ctx.unitProcessed(defender)
            }
            return ReachedTile(chosen.tile, chosen.movesLeft)
        }
        (best(zeroDangerList) ?: best(dangerList))?.let { return ReachedTile(it.tile, it.movesLeft) }
        return null
    }

    /**
     *  CvTacticalAI::PlotMovesToSafety: units that are in danger (or seen by the enemy) go to safety, one by one, the ranged and slow ones first.
     *  [combatUnits]: our combat units that are not garrisons (ships in a city are not), not in an army, if the danger is above their hit
     *  points, they are projected to die next turn or are barbarians (who, if they get here, did not attack this turn); else the units that cannot
     *  defend themselves (civilians, embarked units) always flee.
     */
    fun plotMovesToSafety(ctx: TacticalContext, combatUnits: Boolean) {
        val civ = ctx.civ
        val danger = civ.ai.unitDanger
        class Fleeing(val unit: MapUnit, val key: Int)
        val fleeing = ArrayList<Fleeing>()
        for (unit in ctx.liveUnits()) {
            if (!unit.hasMovement() || unit.baseUnit.isAirUnit()) continue
            val tile = unit.getTile()
            val level = danger.get(unit)
            if (level <= 0 && !TacticalSafety.isKnownVisibleToEnemy(civ, tile)) continue
            if (combatUnits) {
                val isGarrison = tile.isCityCenter() && tile.getCity()?.getGarrison() == unit
                if (!unit.isMilitary() || unit.baseUnit.strength <= 0 || (isGarrison && !unit.baseUnit.isWaterUnit)) continue
                if (civ.ai.operations.armyOf(unit) != null) continue
                // If danger is high or we took a lot of damage last turn; counterintuitively, barbarians always flee, because if we get here it means we did not attack this turn!
                if (level > MAX_HEALTH || unit.isProjectedToDieNextTurn() || civ.isBarbarian) fleeing.add(Fleeing(unit, retreatKey(unit)))
            } else if (!TacticalSafety.canDefend(unit)) fleeing.add(Fleeing(unit, retreatKey(unit))) // Civilian (or embarked) units always flee from danger
        }
        // Melee units should retreat last: std::stable_sort, the highest key first
        for (entry in fleeing.sortedByDescending { it.key }) if (!entry.unit.isDestroyed) executeMovesToSafestPlot(ctx, entry.unit)
    }

    /** What PlotMovesToSafety fakes as attack strength: ranged and slow units retreat first, so they get a high one */
    private fun retreatKey(unit: MapUnit) = (if (unit.baseUnit.isRanged()) RANGED_RETREAT_KEY else MELEE_RETREAT_KEY) - unit.baseUnit.movement

    /**
     *  CvTacticalAI::ExecuteMovesToSafestPlot: the unit goes to the [find] plot (swapping with a unit of ours that holds it) and is done for the turn.
     *  Before the retreat it pillages and shoots when it has movement to spare (a shooter that can move after attacking keeps what the walk needs); after
     *  it, it pillages and shoots again; a unit that is already on the safest plot shoots if it is healthy (more than 3/5 of its hit points) and stays. With
     *  nowhere to go, a unit that expects to survive the danger stays; else, outside our land, it heads for the nearest of our cities.
     */
    fun executeMovesToSafestPlot(ctx: TacticalContext, unit: MapUnit) {
        val civ = ctx.civ
        val origin = unit.getTile()
        val best = find(ctx, unit, allowEmbark = true, considerPush = true)
        if (best != null) {
            val target = best.tile
            // Check if we need to bump somebody else
            if (target != origin && !unit.movement.canMoveTo(target) && unit.movement.canUnitSwapTo(target)) {
                unit.movement.swapMoveToTile(target)
                if (unit.getTile() == target) {
                    ctx.unitProcessed(unit)
                    return
                }
            }
            var movesRemaining = best.movesLeft
            val freePillage = PillageRules.hasFreePillageMove(unit)
            // Pillage before retreat, if we have movement points to spare
            if ((freePillage || movesRemaining > 1f) && PillageRules.shouldPillage(unit, origin) && PillageRules.pillage(unit) && !freePillage) movesRemaining -= 1f

            if (origin == target) {
                // Typical citadel case
                if (unit.health * HEALTHY_FRACTION_DENOMINATOR > MAX_HEALTH * HEALTHY_FRACTION_NUMERATOR) TacticalSimulation.performRangedOpportunityAttack(ctx, unit)
                // Make sure the unit stays put!
                unit.fortifyIfCan()
            } else {
                // Try to do some damage if we have movement points to spare
                if (movesRemaining > 1f && unit.baseUnit.isRanged() && unit.hasUnique(UniqueType.CanMoveAfterAttacking))
                    TacticalSimulation.performRangedOpportunityAttack(ctx, unit, false, movesRemaining)
                // Move to the lowest danger value found
                if (!unit.isDestroyed && unit.hasMovement()) Deployment.headTowards(unit, target)
                if (!unit.isDestroyed) {
                    // Pillage after retreat, if we have movement points to spare
                    if (PillageRules.shouldPillage(unit, unit.getTile(), conservative = false, ignoreMovement = true) &&
                        (unit.currentMovement > 1f || freePillage || !unit.baseUnit.isRanged())) PillageRules.pillage(unit)
                    // See if we can do damage after retreating
                    if (unit.hasMovement() && unit.baseUnit.isRanged()) TacticalSimulation.performRangedOpportunityAttack(ctx, unit)
                }
            }
            ctx.unitProcessed(unit)
        } else if (civ.ai.unitDanger.get(unit) < min(unit.health + TacticalSafety.actualHealRate(unit, origin), MAX_HEALTH)) {
            // Do nothing and hope for the best
            unit.fortifyIfCan()
            ctx.unitProcessed(unit)
        } else {
            ctx.log { "failed to find destination moving ${unit.name} to safety from ${origin.position}" }
            // Try to go home
            if (origin.getOwner() != civ) {
                val home = (if (civ.isCityState) civ.getCapital()?.getCenterTile()
                    else civ.cities.filter { it.getCenterTile().getContinent() == origin.getContinent() || unit.baseUnit.isWaterUnit }
                        .minByOrNull { it.getCenterTile().aerialDistanceTo(origin) }?.getCenterTile())
                if (home != null) TacticalMoves.moveToEmptySpaceNearTarget(ctx, unit, home)
                ctx.unitProcessed(unit)
            }
        }
    }
}
