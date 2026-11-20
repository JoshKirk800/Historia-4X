package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min

/**
 *  How a unit decides to heal and where: CvUnit::shouldHeal, CvTacticalAI::PlotHealMoves / ExecuteHeals and TacticalAIHelpers::FindClosestSafePlotForHealing,
 *  of Vox Populi 5.4.8 (Community Patch and Vox Populi share them). The tactical turn runs the moves twice, [plotHealMoves] with `firstPass` to clear
 *  the front of badly hurt units before the armies and garrisons move, and again after the attacks for everybody else.
 *
 *  A unit only heals at the end of a turn in which it did not move ([com.unciv.logic.map.mapunit.UnitTurnManager.endTurn]; the original's
 *  `hasMoved` is the flag of actually having moved, not the movement left): a unit that stays keeps its movement, fortifies if it can, and is marked
 *  processed so that nobody else moves it; a unit that goes to a better plot heals from the next turn on.
 *
 *  Deviations: the area damage of fortified units (GetDamageAoEFortified) and conditional promotions are left out; a unit that must leave its
 *  army to heal leaves it for good ([com.unciv.logic.automation.strategy.AiOperations.removeFromArmy], RemoveUnit(id, false)); a plot held by a unit of
 *  ours is taken by swapping with it (the original pushes the unit out).
 */
object TacticalHealing {

    private const val MAX_HEALTH = 100
    /** CvUnit::shouldHeal(true): below this many hit points a unit heals whatever it could attack; below a third only if it can heal here and has nothing to hit */
    private const val HARD_HEALTH_LIMIT = 13
    /** FindClosestSafePlotForHealing: a plot that heals more than this can be stayed on while there is nothing to fear */
    private const val GOOD_HEAL_RATE = 5
    private const val UNLIMITED_DANGER = 10000
    /** ExecuteHeals: a unit with nowhere safe to heal flees from danger above this (NEUTRAL_HEAL_RATE of the Community Patch) */
    private const val NEUTRAL_HEAL_RATE = 10
    /** CvUnit::shouldHeal(false): the damage a unit accepts before it starts to heal, and what a unit that fights well wounded or cannot heal accepts */
    private const val ACCEPTABLE_DAMAGE = 20
    private const val ACCEPTABLE_DAMAGE_WELL_DAMAGED = 50

    private class Option(val tile: Tile, val movesLeft: Float, val score: Int)

    /**
     *  CvUnit::shouldHeal(beforeAttacks): the unit is hurt and can heal at all, and
     *  - before the attacks: it has below 13 hit points (nothing else will do), or below a third, can heal where it is and has nothing to attack;
     *  - else, next to an enemy unit or city: only if it is projected to die next turn and the danger on its plot exceeds its hit points plus what it heals;
     *  - else, if it has lost more than 20 hit points (50 for a unit that fights well wounded, for barbarians, or when our capital cannot heal it).
     *  No conditional promotions (their damage limit) here.
     */
    fun shouldHeal(civ: Civilization, unit: MapUnit, beforeAttacks: Boolean): Boolean {
        if (unit.isDestroyed || unit.health >= MAX_HEALTH || unit.hasUnique(UniqueType.HealOnlyByPillaging, checkCivInfoUniques = true)) return false
        val tile = unit.getTile()
        // Sometimes we should heal but we have to fight instead
        if (beforeAttacks) {
            // Sometimes there is no other option
            if (unit.health < HARD_HEALTH_LIMIT) return true
            // Also depends on what we can do with the unit
            return unit.health < MAX_HEALTH / 3 && TacticalSafety.actualHealRate(unit) > 0 &&
                TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()).isEmpty()
        }
        val enemyAdjacent = tile.neighbors.any { neighbor ->
            neighbor.getUnits().any { it.civ != civ && civ.isAtWarWith(it.civ) } ||
                (neighbor.isCityCenter() && neighbor.getOwner()?.let { civ.isAtWarWith(it) } == true)
        }
        // Only run away if strictly necessary
        if (enemyAdjacent) return unit.isProjectedToDieNextTurn() && civ.ai.plotDanger.get(unit) > unit.health + TacticalSafety.actualHealRate(unit)
        // We might lack a resource for healing
        val capital = civ.getCapital(firstCityIfNoCapital = false)
        val maxHealRate = when {
            capital == null -> TacticalSafety.actualHealRate(unit, tile, checkMovement = false)
            unit.baseUnit.isWaterUnit && !capital.isCoastal() -> 0
            else -> TacticalSafety.actualHealRate(unit, capital.getCenterTile(), checkMovement = false)
        }
        val allowMoreDamage = civ.isBarbarian || unit.hasUnique(UniqueType.NoDamagePenaltyWoundedUnits, checkCivInfoUniques = true)
        // Typically want to start healing before health becomes critical
        val acceptableDamage = if (allowMoreDamage || maxHealRate == 0) ACCEPTABLE_DAMAGE_WELL_DAMAGED else ACCEPTABLE_DAMAGE
        return MAX_HEALTH - unit.health > acceptableDamage
    }

    /**
     *  TacticalAIHelpers::FindClosestSafePlotForHealing(unit, conservative): where the unit goes to heal. Staying is right if it can heal more than 5 here
     *  and the plot is quiet, or the heal beats the danger; a land unit in a city does not run. Else, the plots it reaches that heal it (not those holding
     *  an enemy unit or a ranged garrison of a besieged city; ships also count a plot where they can pillage), scored by what they heal (twice as much for
     *  staying where it is unmoved; what a pillage heals unless [conservative]) less their danger, less twice the distance, plus the distance of the plot to
     *  our nearest city; the best with a positive score wins, the later of equals.
     */
    fun findClosestSafePlotForHealing(ctx: TacticalContext, unit: MapUnit, conservative: Boolean = true): ReachedTile? {
        val civ = ctx.civ
        val danger = civ.ai.plotDanger
        val current = unit.getTile()
        // What the unit reaches changes as other units move: the cached reach may be stale
        unit.movement.clearPathfindingCache()
        val alwaysHeals = unit.hasUnique(UniqueType.HealsEvenAfterAction)
        // First see if the current plot is good
        val currentHealRate = TacticalSafety.actualHealRate(unit)
        val currentDanger = danger.get(unit)
        if (currentDanger == 0 && currentHealRate > GOOD_HEAL_RATE && !alwaysHeals) return ReachedTile(current, unit.currentMovement)
        // Check if we can outheal the damage
        if (currentHealRate > GOOD_HEAL_RATE && currentHealRate > currentDanger && !alwaysHeals) return ReachedTile(current, unit.currentMovement)
        // Doesn't get much safer than in a city; also garrisons should not run away!
        if (current.isCityCenter() && unit.baseUnit.isLandUnit) return ReachedTile(current, unit.currentMovement)

        val reachable = unit.movement.getDistanceToTiles()
        val candidates = ArrayList<Option>()
        val pillageHeal = civ.gameInfo.ruleset.ai.define(AiDefine.PILLAGE_HEAL_AMOUNT)
        for (tile in (reachable.keys.asSequence() + current).distinct().sortedBy { it.zeroBasedIndex }) {
            if (TacticalTargets.visibleEnemyDefender(civ, tile) != null) continue
            val movesLeft = if (tile == current) unit.currentMovement else max(unit.currentMovement - (reachable[tile]?.totalMovement ?: 0f), 0f)
            val pillages = movesLeft > 0f && PillageRules.shouldPillage(unit, tile, conservative = false)
            // Don't check movement, don't need to heal right now
            val rate = TacticalSafety.actualHealRate(unit, tile, checkMovement = false)
            if (unit.baseUnit.isLandUnit) {
                if (rate == 0) continue
                // Don't mess with (ranged) garrisons if enemies are around
                val city = tile.takeIf { it.isCityCenter() }?.getCity()
                val garrison = city?.getGarrison()
                if (city != null && garrison != null && CityDangerAi.isUnderSiege(city) && garrison.baseUnit.isRanged()) continue
            } else if (!pillages && rate == 0) continue // Naval units usually must pillage to heal ...
            // Can we stay there?
            if (tile != current && !unit.movement.canMoveTo(tile) && !unit.movement.canUnitSwapTo(tile)) continue

            val plotDanger = min(danger.get(unit, tile), UNLIMITED_DANGER)
            var healRate = rate
            // A heal now is about as good as two heals next turn
            if (tile == current && !unit.hasUnitMovedThisTurn() && !alwaysHeals) healRate *= 2
            // Sometimes we want to ignore pillage health, it's a one-time effect and may lead into dead ends
            if (pillages && !conservative) healRate = if (!PillageRules.hasFreePillageMove(unit)) max(healRate, pillageHeal) else healRate + pillageHeal
            // Make up a score function: don't try to go to heal in a plot where we will slowly die
            var score = healRate - plotDanger
            // Is this safe enough?
            if (score > 0) {
                // Tiebreaker
                val cityDistance = civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 0
                score -= tile.aerialDistanceTo(current) * 2 - cityDistance
                candidates.add(Option(tile, movesLeft, score))
            }
        }
        // std::stable_sort ascending and the last: the highest score, the later of equals
        val best = candidates.fold(null as Option?) { acc, option -> if (acc == null || option.score >= acc.score) option else acc } ?: return null
        return ReachedTile(best.tile, best.movesLeft)
    }

    /**
     *  CvTacticalAI::PlotHealMoves: the units of the tactical AI that [shouldHeal] (in the first pass, before the attacks, only those that must) go
     *  to heal.
     */
    fun plotHealMoves(ctx: TacticalContext, firstPass: Boolean) {
        val heal = ctx.liveUnits().filter { it.hasMovement() && shouldHeal(ctx.civ, it, firstPass) }
        if (heal.isNotEmpty()) executeHeals(ctx, heal, firstPass)
    }

    /**
     *  CvTacticalAI::ExecuteHeals: for each unit: it leaves its army (unless the army is only gathering or the operation is a civilian one, which keeps
     *  it); a land unit in danger that has one pursuer in reach kills it if it can ([OpportunityAttacks.killLoneEnemyIfPossible]), a ship in danger or
     *  that cannot heal does that or else pillages; then the unit goes to the [findClosestSafePlotForHealing] plot - a fast shooter shooting first - and
     *  staying means fortifying with all its movement. With no safe plot, in the second pass a unit in more danger than 10 pillages, then flees to the
     *  [SafestPlot] unless it covers a civilian.
     */
    private fun executeHeals(ctx: TacticalContext, units: List<MapUnit>, firstPass: Boolean) {
        val civ = ctx.civ
        val operations = civ.ai.operations
        val danger = civ.ai.plotDanger
        for (unit in units) {
            if (unit.isDestroyed || unit in ctx.cache.processedUnits) continue
            // Need to split from army?
            val army = operations.armyOf(unit)
            if (army != null) {
                // Don't do this for civilian operations!
                if (operations.operationFor(unit)?.type?.isEscorted == true) continue
                if (army.state != ArmyState.WAITING_FOR_UNITS_TO_REINFORCE) operations.removeFromArmy(unit)
            }

            var better: ReachedTile? = null
            val tile = unit.getTile()
            // Find a suitable spot for healing
            if (unit.baseUnit.isLandUnit) {
                // Try opportunistic attacks if there is only one nearby unit
                if (danger.get(unit) > 0 && !unit.isEmbarked()) {
                    val attackers = danger.possibleAttackers(tile)
                    if (attackers.size == 1 && OpportunityAttacks.killLoneEnemyIfPossible(unit, attackers[0]))
                        ctx.log { "healing unit ${unit.name} counterattacked pursuer" }
                }
                if (!unit.isDestroyed) better = findClosestSafePlotForHealing(ctx, unit)
            } else if (unit.baseUnit.isWaterUnit) {
                if (danger.get(unit) > 0 || TacticalSafety.actualHealRate(unit, tile) == 0) {
                    val attackers = danger.possibleAttackers(tile)
                    // Try to turn the tables on him
                    if (attackers.size == 1 && OpportunityAttacks.killLoneEnemyIfPossible(unit, attackers[0])) ctx.log { "healing unit ${unit.name} counterattacked pursuer" }
                    // Why not pillage some tiles?
                    else if (PillageRules.shouldPillage(unit, tile) && PillageRules.pillage(unit)) ctx.log { "heal: pillage with ${unit.name} before move at ${tile.position}" }
                    if (!unit.isDestroyed) better = findClosestSafePlotForHealing(ctx, unit)
                }
            }
            if (unit.isDestroyed) continue

            // Now finally do something
            val target = better?.tile
            if (target != null) {
                if (target != unit.getTile()) {
                    // Ranged attack before fleeing for fast units
                    if (unit.baseUnit.isRanged() && unit.hasUnique(UniqueType.CanMoveAfterAttacking) && better.movesLeft > 1f)
                        TacticalSimulation.performRangedOpportunityAttack(ctx, unit, false, better.movesLeft)
                    if (!unit.isDestroyed && !TacticalMoves.moveSwapping(unit, target)) continue
                } else unit.fortifyIfCan() // this is required to flush the previous mission!
                ctx.unitProcessed(unit)
            } else if (!firstPass && !unit.baseUnit.isAirUnit() && danger.get(unit) > NEUTRAL_HEAL_RATE) {
                // No safe plot to heal ...: why not pillage more tiles?
                if (PillageRules.shouldPillage(unit, unit.getTile())) PillageRules.pillage(unit)
                // At least try to flee if we're not needed
                if (!TacticalSafety.isCoveringFriendlyCivilian(ctx, unit)) {
                    val safest = SafestPlot.find(ctx, unit, allowEmbark = true)
                    if (safest != null && safest.tile != unit.getTile()) TacticalMoves.moveSwapping(unit, safest.tile)
                }
                if (!unit.hasMovement()) ctx.unitProcessed(unit)
            }
        }
    }
}
