package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.ProductionSanityAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit

/**
 *  CvTacticalAI::PlotGarrisonMoves of Vox Populi 5.4.8 (Community Patch and Vox Populi share it): the garrisons of the cities that the zones around
 *  them threaten (the friendly-city targets, [TacticalTargetType.CityToDefend], those with a border score) and that are border cities, have a
 *  blockaded tile or are exposed to an enemy army. For each such city:
 *  - the city shoots first if it has not this turn (a safety net for postures that did not make it shoot);
 *  - ships of ours standing in it are collected and, at the end, upgrade or make an opportunity attack that may move them;
 *  - the garrison (not a recon unit) upgrades if it is healthy and can ([UseGoldAutomation.upgradeNow]; then the city is done), else, if it is not in an
 *    army, it counts the enemy units next to the city and those within three tiles that can reach it this turn, makes an opportunity attack
 *    ([OpportunityAttacks.perform]) that may move it only when fewer than two enemies are near and we have more than one city, and is marked processed
 *    with its movement kept (a garrison that finished its moves would not heal);
 *  - a city under siege does not look for a better garrison; otherwise, if the garrison is not a ranged land unit of range 2 or more and the city needs a
 *    garrison ([AiOperationLogic.needsGarrison]), [UnitSelection.findUnitForThisMove] picks one within [turnsAway] turns: the present garrison moves out
 *    to a free tile next to the city first if the new unit gets there this turn, then the new unit goes to the city and makes an opportunity attack.
 *
 *  Deviations: the enemies that count when they are two or three tiles away are those whose movement and range reach the city ([PlotDanger.canThreaten]);
 *  the supply check of the upgrades is left out; a unit that goes to the city with turns to spare simply heads for it.
 */
object GarrisonMoves {

    /** CvTacticalAI::PlotGarrisonMoves(iNumTurnsAway): the enemy count is taken over three rings; one ring is enough to count without a reach test */
    private const val ENEMY_RING = 3
    private const val ENEMIES_TO_STAY = 2

    fun plotGarrisonMoves(ctx: TacticalContext, turnsAway: Int) {
        val civ = ctx.civ
        val ships = ArrayList<MapUnit>()
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.CityToDefend)) {
            val city = target.city ?: continue
            val cityTile = city.getCenterTile()
            // It's possible that the city did not perform a ranged attack this turn yet although enemies are present
            cityShoots(city)

            // Ignore core cities here (handled by homeland ai)
            if (!ProductionSanityAi.isBorderCity(city) && city.getTiles().none { it.isBlockaded() } && !civ.ai.military.isExposedToEnemy(civ, city, null)) continue

            // Naval units in cities shouldn't stay idle
            for (unit in cityTile.getUnits()) if (unit.civ == civ && unit.baseUnit.isWaterUnit && !unit.isDestroyed && unit !in ships) ships.add(unit)

            // Note that garrisons do not need to be "recruited" into tactical AI; allow valid land garrisons, but don't use recon units as garrisons
            var garrison = city.getGarrison()?.takeIf { UnitAiTypes.defaultOf(it.baseUnit, ctx.rules) != UnitAiType.EXPLORE }
            if (garrison != null) {
                if (garrison.health >= MAX_HEALTH) {
                    val upgraded = UseGoldAutomation.upgradeNow(garrison)
                    if (upgraded != null) {
                        ctx.unitProcessed(upgraded)
                        continue
                    }
                }
                // Sometimes we have an accidental garrison ...
                if (garrison.isDestroyed || garrison in ctx.cache.processedUnits || civ.ai.operations.armyOf(garrison) != null) continue

                // First check how many enemies are around
                val enemies = enemyCount(ctx, city)
                // Ranged garrisons are also used in ExecuteDestroyUnit etc. if they can hit a target without moving; here we have more advanced logic and
                // allow some movement if it's safe (and it's not our last stand)
                OpportunityAttacks.perform(ctx, garrison, enemies < ENEMIES_TO_STAY && civ.cities.size > 1)
                // Do not finish the garrison's moves, else it will not heal
                if (!garrison.isDestroyed) {
                    garrison.fortifyIfCan()
                    ctx.unitProcessed(garrison)
                    ctx.state.setLastTurnGarrisonAssigned(city, ctx.turn)
                }
                if (garrison.isDestroyed) garrison = null
                // Don't try to find a better garrison while under siege
                if (CityDangerAi.isUnderSiege(city)) continue
            }

            // Prefer ranged land units as garrisons ...
            val wantGarrison = garrison == null || !garrison.baseUnit.isRanged() || garrison.getRange() < 2
            if (!wantGarrison || !AiOperationLogic.needsGarrison(civ, city)) continue
            // Grab units that make sense for this move type
            val unit = UnitSelection.findUnitForThisMove(ctx, UnitMove.Garrison, cityTile, turnsAway) ?: continue
            // Move out old garrison if necessary
            if (garrison != null && unit != garrison && TacticalAttacks.turnsToReach(unit, cityTile) <= 1) {
                // Do not set it processed, it should be considered for other tactical moves later
                TacticalMoves.emptyTileNextTo(garrison, cityTile)?.let { garrison.movement.tryHeadTowards(it) }
            }
            // If the old garrison did not move out this will fail (possibly also for other reasons)
            if (unit.getTile() != cityTile && !unit.movement.canMoveTo(cityTile)) continue
            if (!Deployment.headTowards(unit, cityTile)) continue
            ctx.log { "${unit.name} moves to garrison ${city.name}, border score ${target.aux}" }
            // Just in case we're doing this with enemies around and have movement left
            OpportunityAttacks.perform(ctx, unit)
            // The new garrison came from the tactical AI's units, so need to mark it
            if (!unit.isDestroyed) {
                if (unit.getTile() == cityTile) unit.fortifyIfCan()
                ctx.unitProcessed(unit)
                ctx.state.setLastTurnGarrisonAssigned(city, ctx.turn)
            }
        }

        for (ship in ships) {
            if (ship.isDestroyed || ship in ctx.cache.processedUnits) continue
            // Upgrade, or else an opportunity attack that may move the ship
            if (ship.health >= MAX_HEALTH) {
                val upgraded = UseGoldAutomation.upgradeNow(ship)
                if (upgraded != null) {
                    ctx.unitProcessed(upgraded)
                    continue
                }
            }
            OpportunityAttacks.perform(ctx, ship, allowMovement = true)
        }
    }

    /**
     *  The enemy units that count for the garrison's freedom to move: those of civilizations at war with us next to the city, and those up to
     *  three tiles away that can reach it this turn.
     */
    fun enemyCount(ctx: TacticalContext, city: City): Int {
        val civ = ctx.civ
        val center = city.getCenterTile()
        var count = 0
        for (tile in center.getTilesInDistance(ENEMY_RING)) {
            if (tile == center) continue
            val enemy = TacticalTargets.visibleEnemyDefender(civ, tile) ?: continue
            if (tile.aerialDistanceTo(center) == 1 || PlotDanger.canThreaten(enemy, center)) count++
        }
        return count
    }

    /** CvCity::getBestRangedStrikeTarget and rangeStrike: the city shoots the visible enemy unit it hurts most, if it still can this turn */
    private fun cityShoots(city: City) {
        if (!city.canBombard()) return
        val shooter = CityCombatant(city)
        val target = TargetHelper.getBombardableTiles(city)
            .mapNotNull { tile -> Battle.getMapCombatantOfTile(tile)?.takeIf { it.getCivInfo() != city.civ && city.civ.isAtWarWith(it.getCivInfo()) && !it.isCivilian() } }
            .maxByOrNull { BattleDamage.calculateDamageToDefender(shooter, it, city.getCenterTile(), TacticalTargets.EXPECTED_RANDOMNESS) } ?: return
        Battle.attackOrNuke(shooter, AttackableTile(city.getCenterTile(), target.getTile(), 0f, target))
    }

    private const val MAX_HEALTH = 100
}
