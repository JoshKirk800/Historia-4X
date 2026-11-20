package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/**
 *  The checks the safety and heal moves share (CvUnit::IsCanDefend, ActualHealRate, IsUnderEnemyRangedAttack, IsKnownVisibleToEnemy, IsCoveringFriendlyCivilian)
 *  and the simple form of CvTacticalAI::PlotHealMoves / ExecuteHeals ([heal]) that CvHomelandAI's own version of the move still uses
 *  ([com.unciv.logic.automation.unit.HomelandAi], [com.unciv.logic.automation.unit.CivilianUnitAutomation]); the tactical AI's is [TacticalHealing].
 *  The moves to safety are [SafestPlot]'s, for the homeland AI too ([com.unciv.logic.automation.unit.CivilianSafety]).
 *
 *  Deviation: [isUnderEnemyRangedAttack] only counts what we can see (the original reads every enemy unit) and takes no line of sight into account.
 */
object TacticalSafety {

    /** CvUnit::IsCanDefend: a combat unit that is not embarked (a land unit on water cannot defend itself) */
    @Readonly
    fun canDefend(unit: MapUnit): Boolean = unit.baseUnit.strength > 0 && unit.isMilitary() && !unit.isEmbarked()

    /**
     *  CvUnit::ActualHealRate(tile, checkMovement): what the unit heals in a turn on [tile]: nothing if embarked there or if it heals only by
     *  pillaging, and - looking at the tile it stands on, [checkMovement] - nothing if it has moved this turn, unless it heals even after acting
     *  ([UniqueType.HealsEvenAfterAction]). The terrain's damage is not taken off.
     */
    @Readonly
    fun actualHealRate(unit: MapUnit, tile: Tile = unit.getTile(), checkMovement: Boolean = true): Int {
        if (unit.isDestroyed || unit.isEmbarkedAt(tile) || unit.hasUnique(UniqueType.HealOnlyByPillaging, checkCivInfoUniques = true)) return 0
        if (checkMovement && unit.hasUnitMovedThisTurn() && !unit.hasUnique(UniqueType.HealsEvenAfterAction)) return 0
        return maxOf(unit.healingAmountForTile(tile, noTerrainDamage = true), 0)
    }

    /** CvPlot::IsKnownVisibleToEnemy: a civilization at war with [civ] sees the tile */
    fun isKnownVisibleToEnemy(civ: Civilization, tile: Tile): Boolean = civ.getCivsAtWarWith().any { tile in it.viewableTiles }

    /**
     *  CvUnit::IsCoveringFriendlyCivilian: the unit protects a unit of ours that cannot defend itself and has finished its turn, outside a city,
     *  on a tile enemies can attack ([com.unciv.logic.automation.unit.PlotDanger.possibleAttackers]).
     */
    fun isCoveringFriendlyCivilian(ctx: TacticalContext, unit: MapUnit): Boolean {
        val tile = unit.getTile()
        if (tile.isCityCenter()) return false
        for (other in tile.getUnits()) {
            if (other == unit || other.civ != unit.civ) continue
            if (!canDefend(other) && other in ctx.cache.processedUnits) return unit.civ.ai.plotDanger.possibleAttackers(tile).isNotEmpty()
        }
        return false
    }

    /**
     *  CvUnit::IsUnderEnemyRangedAttack: a city of a civilization at war with us that can bombard the tile, or enemy units that can shoot it
     *  and together do more damage than the unit heals in a turn.
     */
    fun isUnderEnemyRangedAttack(unit: MapUnit): Boolean {
        val civ = unit.civ
        val tile = unit.getTile()
        var totalDamage = 0
        val target = MapUnitCombatant(unit)
        val healing = unit.healingAmountForTile(tile)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (city in other.cities)
                if (city.getCenterTile().aerialDistanceTo(tile) <= city.getBombardRange() && city.getCenterTile().isExplored(civ)) return true
            for (enemy in other.units.getCivUnits()) {
                if (!enemy.isMilitary() || enemy.baseUnit.isAirUnit() || enemy.baseUnit.rangedStrength <= 0) continue
                if (enemy.getTile().aerialDistanceTo(tile) > enemy.getRange() || !enemy.isVisibleTo(civ)) continue
                totalDamage += BattleDamage.calculateDamageToDefender(MapUnitCombatant(enemy), target, tile, EXPECTED_RANDOMNESS)
                if (totalDamage > healing) return true
            }
        }
        return false
    }

    /**
     *  CvTacticalAI::PlotHealMoves / ExecuteHeals: a unit below full health that is neither embarked nor in a city, not a ship outside our
     *  territory and not under enemy ranged attack, fortifies (or just waits) to heal where it stands; the caller marks it processed. It keeps its
     *  movement: a unit only heals at the end of a turn in which it did not move ([com.unciv.logic.map.mapunit.UnitTurnManager.endTurn]; in the
     *  original CvUnit::hasMoved is the flag of actually having moved, not the movement left), so a unit with its movement used up would never heal.
     *  True if it did.
     */
    fun heal(unit: MapUnit): Boolean {
        if (unit.health >= MAX_HEALTH || unit.isEmbarked() || unit.getTile().isCityCenter()) return false
        if (unit.baseUnit.isWaterUnit && !unit.getTile().isFriendlyTerritory(unit.civ)) return false
        if (isUnderEnemyRangedAttack(unit)) return false
        AiDecisionLog.log(unit.civ) { "Tactical: ${unit.name} heals at ${unit.getTile().position} (${unit.health})" }
        if (unit.canFortify()) unit.fortify()
        return true
    }

    /** CvUnit::GetMaxHitPoints */
    private const val MAX_HEALTH = 100

    /** The mean of the damage roll, for expected damage (the original's getCombatDamage without randomness) */
    private const val EXPECTED_RANDOMNESS = 0.5f
}

