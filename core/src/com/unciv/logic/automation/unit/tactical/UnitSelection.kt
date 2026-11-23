package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.ProductionSanityAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max

/** What a unit is wanted for (the moves CvTacticalAI::FindUnitForThisMove tells apart: AI_TACTICAL_GARRISON, AI_TACTICAL_GUARD, AI_TACTICAL_GOODY) */
enum class UnitMove { Garrison, Guard, Goody }

/**
 *  CvTacticalAI::FindUnitForThisMove and FindUnitsForHarassing of Vox Populi 5.4.8: which unit of the tactical AI a garrison, guard (bastion and
 *  improvement), ancient ruins or harassing move (barbarian camps, pillaging, plundering) uses.
 *
 *  [findUnitForThisMove] picks the one unit that suits best by a score (the Community Patch's, shared with Vox Populi). Every unit must be a land or
 *  sea combat unit that can attack and get onto the target, is not exploring, not in an army and not covering a civilian, and is within five tiles per
 *  turn away (an early out). Garrison: not out of a citadel of ours at a border (the unit would be missed there), ranged units (range over 1) +30,
 *  defenders +20, recon -50, + 120 x what the unit adds to the city's strength / the city's strength without its present garrison, less the defense bonus
 *  of its promotions (they are lost in a city). Guard: not hurt enough to heal, not a siege unit, of the target's domain, a land unit that shoots only on
 *  our citadels (vision +23 if not), -21 for one that cannot fortify or ignores defensive terrain, +31 for a defense promotion. Ruins: +31 for a fast
 *  mover (there is no skirmisher unit AI here). Then 1000 + that - 20 x the turns to get there - the distance; the first of the best.
 *
 *  Deviations: city strength is Unciv's ([CityCombatant.getCityStrength] x100, the unit adding its strength x
 *  [com.unciv.models.ModConstants.cityStrengthFromGarrison] x100 where the original divides by CITY_STRENGTH_LAND_UNIT_DIVISOR); a unit that
 *  wants to embark is treated as one that cannot reach (the original asks for a safe embark only); the original's recon-only option for
 *  ruins (MOD_BALANCE_RECON_ONLY_ANCIENT_RUINS, off in the Community Patch) is not here; our citadels are [TacticalMap.isPlayerCitadel] and a
 *  border is [ProductionSanityAi.isBorderLand]; defense and extra visibility are the promotions' [UniqueType.Strength] and [UniqueType.Sight] uniques;
 *  the harassing units are found by looking at every unit, not by walking a step-finder area around the target.
 */
object UnitSelection {

    private const val BASE_SCORE = 1000
    private const val RANGED_GARRISON_BONUS = 30
    private const val DEFENDER_GARRISON_BONUS = 20
    private const val RECON_GARRISON_PENALTY = 50
    private const val GARRISON_STRENGTH_WEIGHT = 120
    private const val SIGHT_GUARD_BONUS = 23
    private const val POOR_GUARD_PENALTY = 21
    private const val DEFENSE_GUARD_BONUS = 31
    private const val FAST_RUINS_BONUS = 31
    private const val TURN_PENALTY = 20
    private const val DISTANCE_PER_TURN = 5

    private class Option(val unit: MapUnit, val score: Int)

    /**
     *  CvTacticalAI::FindUnitForThisMove: the unit that suits [move] at [target] best and gets there within [turnsAway] turns (any if -1), null if none does.
     */
    fun findUnitForThisMove(ctx: TacticalContext, move: UnitMove, target: Tile, turnsAway: Int = -1): MapUnit? {
        val civ = ctx.civ
        var best: Option? = null
        for (unit in ctx.liveUnits()) {
            val base = unit.baseUnit
            if (base.isAirUnit() || !unit.isMilitary() || unit in ctx.cache.processedUnits) continue
            if (!unit.hasMovement() || !TacticalAttacks.canAttack(unit)) continue
            // A city's present garrison is moved out of the way first, so only a garrison move may ignore a held tile
            if (move != UnitMove.Garrison && unit.getTile() != target && !unit.movement.canMoveTo(target)) continue
            val unitAi = UnitAiTypes.current(unit)
            if (unitAi == UnitAiType.EXPLORE || unitAi == UnitAiType.EXPLORE_SEA || civ.ai.operations.armyOf(unit) != null) continue
            if (TacticalSafety.isCoveringFriendlyCivilian(ctx, unit)) continue
            // Performance optimization ... careful because zero is a valid turn value
            val distance = unit.getTile().aerialDistanceTo(target)
            if (turnsAway > 1 && distance > DISTANCE_PER_TURN * turnsAway) continue

            var extra = 0
            when (move) {
                UnitMove.Garrison -> extra = garrisonScore(ctx, unit, target) ?: continue
                UnitMove.Guard -> extra = guardScore(ctx, unit, target) ?: continue
                UnitMove.Goody -> if (UnitAiTypes.canHave(base, UnitAiType.FAST_ATTACK, ctx.rules)) extra += FAST_RUINS_BONUS
            }

            // Otherwise collect and sort
            val turns = turnsToReach(unit, target, ignoreStacking = move == UnitMove.Garrison)
            if (turns == Int.MAX_VALUE || (turnsAway != -1 && turns > turnsAway)) continue
            // Tricky to make a good score avoiding ties ...
            val score = BASE_SCORE + extra - TURN_PENALTY * turns - distance
            if (best == null || score > best.score) best = Option(unit, score)
        }
        return best?.unit
    }

    /**
     *  CvUnit::TurnsToReachTarget: 0 if [unit] gets onto [tile] this turn, 1 if next turn and so on, [Int.MAX_VALUE] if it cannot get there or the tile is held by
     *  a unit it cannot join. [TacticalAttacks.turnsToReach] counts one more for a unit that gets there this turn with no movement left (and for a longer path):
     *  [moveLeft] asks for the original's "the turn that ends there is the next one" flag (MOVEFLAG_TURN_END_IS_NEXT_TURN),
     *  [ignoreStacking] for MOVEFLAG_IGNORE_STACKING_SELF.
     */
    fun turnsToReach(unit: MapUnit, tile: Tile, moveLeft: Boolean = false, ignoreStacking: Boolean = false): Int {
        if (!ignoreStacking && unit.getTile() != tile && !unit.movement.canMoveTo(tile)) return Int.MAX_VALUE
        val turns = TacticalAttacks.turnsToReach(unit, tile)
        if (turns == Int.MAX_VALUE) return turns
        return if (moveLeft) turns else max(turns - 1, 0)
    }

    /**
     *  CvTacticalAI::FindUnitsForHarassing: the units of the tactical AI that can be at [target] (or next to it, if an enemy holds it) within [turnsAway]
     *  turns, the nearest first, at most [maxUnits]. A unit must have [minHealth] to [maxHealth] hit points (0 or less: no limit; absolute hit points, as the callers pass MAX_HIT_POINTS fractions, CvTacticalAI.cpp:4856) and, outside the
     *  exception of a trade route plundered where the unit stands ([plunder]), not be a siege unit or carrier, not cover a civilian, face no more danger at the
     *  target than it has hit points and not be a garrison its city needs; no barbarian in its camp; of [water]'s domain (null: any). [moveLeft]: it must get
     *  there with movement to spare. Aircraft, which the original never recruits for these moves, are left out.
     */
    fun findUnitsForHarassing(
        ctx: TacticalContext, target: Tile, turnsAway: Int, minHealth: Int, maxHealth: Int, water: Boolean?, moveLeft: Boolean, maxUnits: Int,
        plunder: Boolean = false,
    ): List<MapUnit> {
        val civ = ctx.civ
        val danger = civ.ai.plotDanger
        val enemyThere = TacticalTargets.visibleEnemyDefender(civ, target) != null
        val result = ArrayList<Pair<MapUnit, Int>>()
        for (unit in ctx.liveUnits()) {
            if (unit.isDestroyed || !unit.hasMovement() || unit.baseUnit.isAirUnit()) continue
            // Plundering a trade route on the current plot doesn't cost us anything, so we do it whenever possible and the following exclusions do not apply
            if (!plunder || unit.getTile() != target) {
                // These units are too fragile for the moves we have in mind
                val unitAi = UnitAiTypes.current(unit)
                if (unitAi == UnitAiType.CITY_BOMBARD || unitAi == UnitAiType.CARRIER_SEA) continue
                if (TacticalSafety.isCoveringFriendlyCivilian(ctx, unit)) continue
                if (minHealth > 0 && unit.health < minHealth || maxHealth > 0 && unit.health > maxHealth) continue
                if (danger.get(unit, target) > unit.health) continue
                // Don't use garrisons if there is an enemy around. The garrison may still attack when we do garrison moves!
                val city = unit.getTile().takeIf { it.isCityCenter() }?.getCity()
                if (city != null && city.getGarrison() == unit && AiOperationLogic.needsGarrison(civ, city) && !unit.baseUnit.isWaterUnit) continue
            }
            if (civ.isBarbarian && unit.getTile().isBarbarianEncampment()) continue
            if (water != null && unit.baseUnit.isWaterUnit != water) continue
            // An enemy on the target cannot be entered: it is approached (MOVEFLAG_APPROX_TARGET_RING1)
            val turns = if (enemyThere) target.neighbors.minOfOrNull { turnsToReach(unit, it, moveLeft) } ?: Int.MAX_VALUE else turnsToReach(unit, target, moveLeft)
            if (turns <= turnsAway) result.add(unit to turns)
        }
        // The original sorts by 1 + turns away - turns: the fewest turns first
        return result.sortedBy { it.second }.take(maxUnits).map { it.first }
    }

    /** The garrison part of the score, null if the unit is not to be used (a unit in an important citadel, a target that is no city) */
    private fun garrisonScore(ctx: TacticalContext, unit: MapUnit, target: Tile): Int? {
        val civ = ctx.civ
        val base = unit.baseUnit
        val unitTile = unit.getTile()
        // Do not pull units out of important citadels
        if (base.isLandUnit && !civ.isBarbarian && ctx.map.isPlayerCitadel(unitTile, civ) && ProductionSanityAi.isBorderLand(civ, unitTile)) return null
        val city = target.takeIf { it.isCityCenter() }?.getCity() ?: return null
        var extra = 0
        // Want to put ranged units in cities to give them a ranged attack (but siege units should be used for offense)
        when (UnitAiTypes.current(unit)) {
            UnitAiType.RANGED -> if (unit.getRange() > 1) extra += RANGED_GARRISON_BONUS
            UnitAiType.DEFENSE_AIR, UnitAiType.DEFENSE -> extra += DEFENDER_GARRISON_BONUS
            else -> Unit
        }
        // Don't use recon units as garrisons
        if (UnitAiTypes.defaultOf(base, ctx.rules) == UnitAiType.EXPLORE) extra -= RECON_GARRISON_PENALTY

        // Score candidate by effective city-strength contribution relative to city strength without garrison
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val garrisonShare = constants.cityStrengthFromGarrison * 100
        val combatant = CityCombatant(city)
        var cityStrength = combatant.getCityStrength() * 100
        city.getGarrison()?.let {
            cityStrength -= if (constants.cityCombatLikeVp) combatant.vpGarrisonStrength(it)
                else (max(it.baseUnit.strength, it.baseUnit.rangedStrength) * garrisonShare).toInt()
        }
        cityStrength = max(1, cityStrength)
        val contribution = if (constants.cityCombatLikeVp) combatant.vpGarrisonStrength(unit)
            else (max(base.strength, base.rangedStrength) * garrisonShare).toInt()
        extra += GARRISON_STRENGTH_WEIGHT * contribution / cityStrength

        // Don't put units with a defense boosted from promotions in cities, these boosts are ignored
        extra -= defenseModifier(unit)
        return extra
    }

    /** The guard part of the score, null if the unit is not to be used: hurt, a siege unit, of another domain, or a land shooter outside a citadel */
    private fun guardScore(ctx: TacticalContext, unit: MapUnit, target: Tile): Int? {
        val civ = ctx.civ
        // Heal first, guards might be attacked
        if (TacticalHealing.shouldHeal(civ, unit, beforeAttacks = false)) return null
        // Don't embark!
        if (unit.baseUnit.isWaterUnit != target.isWater) return null
        // No siege units as plot defenders
        if (UnitAiTypes.current(unit) == UnitAiType.CITY_BOMBARD) return null
        var extra = 0
        // Ranged units are ok only in citadels
        if (unit.baseUnit.isLandUnit && (civ.isBarbarian || !ctx.map.isPlayerCitadel(target, civ))) {
            if (unit.baseUnit.isRanged()) return null
            if (extraVisibility(unit) > 0) extra += SIGHT_GUARD_BONUS
        }
        // These can do in a pinch
        if (unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true) || !unit.canFortify()) extra -= POOR_GUARD_PENALTY
        // Units with defensive promotions are especially valuable
        if (defenseModifier(unit) > 0) extra += DEFENSE_GUARD_BONUS
        return extra
    }

    /** CvUnit::getDefenseModifier: the strength the promotions add when the unit defends */
    private fun defenseModifier(unit: MapUnit): Int {
        var bonus = 0
        for (promotion in unit.promotions.getPromotions())
            for (unique in promotion.uniqueObjects)
                if (unique.type == UniqueType.Strength && unique.modifiers.all { it.type == UniqueType.ConditionalDefending }) bonus += unique.params[0].toInt()
        return bonus
    }

    /** CvUnit::getExtraVisibilityRange: the sight the promotions add */
    private fun extraVisibility(unit: MapUnit): Int =
        unit.promotions.getPromotions().sumOf { promotion -> promotion.uniqueObjects.filter { it.type == UniqueType.Sight }.sumOf { it.params[0].toInt() } }
}
