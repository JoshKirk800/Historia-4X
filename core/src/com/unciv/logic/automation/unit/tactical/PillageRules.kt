package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.unit.PlotDefense
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent

/**
 *  When a unit that is on its way somewhere else pillages what it stands on: CvUnit::shouldPillage and what the original asks of it (CvUnit::canPillage,
 *  hasFreePillageMove, getPillageHealAmount). Used by the moves to safety and the heal moves ([SafestPlot], [TacticalHealing]), which pillage
 *  "before and after" a retreat when they have movement to spare.
 *
 *  Deviations: TARGET_VALUE_DIFFICULT is [TargetValue.Bad] and below (this port has no level between bad and average); not here: the original's
 *  yield-from-pillage traits, the experience from pillaging and the area healing and damage promotions, which Brave New World has none of.
 */
object PillageRules {

    private const val SLOW_UNIT_MOVES = 3
    private const val DESERT = "Desert"
    private const val MARSH = "Marsh"
    private const val FOREST = "Forest"

    /** CvUnit::hasFreePillageMove */
    fun hasFreePillageMove(unit: MapUnit) = unit.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true)

    /** CvUnit::getPillageHealAmount: the health a pillaged improvement (not a road) gives the unit */
    fun pillageHealAmount(unit: MapUnit, tile: Tile): Int {
        if (!tile.canPillageTileImprovement()) return 0
        var heal = unit.civ.gameInfo.ruleset.ai.define(AiDefine.PILLAGE_HEAL_AMOUNT).toFloat()
        unit.forEachMatchingUnique(UniqueType.PercentHealthFromPillaging, checkCivInfoUniques = true) { heal *= it.params[0].toPercent() }
        return heal.toInt()
    }

    /**
     *  CvUnit::shouldPillage(tile, conservative, ignoreMovement): the unit can pillage here and: has a free pillage move next to a city or on a
     *  route in a foreign land; or the improvement hurts adjacent enemies (a citadel of someone at war with us); or - unless [ignoreMovement],
     *  in which case slow units do not "waste" their movement when the danger outweighs their health plus the heal - the tile is in a city's land
     *  that is a focus of ours with a strategic or luxury resource, whose owner is stronger than average or a difficult target, or that is not a
     *  preferred attack target; or the route is in rough terrain (hills, desert, marsh, forest) of someone's land; else, if [conservative], the
     *  unit is damaged by at least what the pillage heals and that is a full 25, otherwise whenever it is damaged at all.
     */
    fun shouldPillage(unit: MapUnit, tile: Tile, conservative: Boolean = false, ignoreMovement: Boolean = false): Boolean {
        if (unit.isDestroyed || !UnitActionsPillage.canPillage(unit, tile)) return false
        val civ = unit.civ
        val free = hasFreePillageMove(unit)
        val owner = tile.getOwner()
        val hasRoute = tile.getUnpillagedRoad() != RoadStatus.None
        if (free && (tile.neighbors.any { it.isCityCenter() } || (owner != null && hasRoute))) return true

        // Citadel here?
        val improvement = tile.getUnpillagedTileImprovement()
        if (improvement != null && owner != null && civ.isAtWarWith(owner) && PlotDefense.nearbyEnemyDamage(improvement) != 0) return true

        val heal = pillageHealAmount(unit, tile)
        // Be careful "wasting" movement for slow units
        if (!ignoreMovement && civ.ai.plotDanger.get(unit) > unit.health + heal && unit.currentMovement < SLOW_UNIT_MOVES && !free) return false

        val city = tile.getCity()
        if (city != null && owner != null && !owner.isBarbarian) {
            if (civ.ai.tactical.isInFocusArea(tile)) {
                val resource = tile.tileResource
                if (resource != null && (resource.resourceType == ResourceType.Strategic || resource.resourceType == ResourceType.Luxury)) return true
            }
            if ((WarStateAi.militaryStrengthComparedToUs(civ, owner) ?: StrengthLevel.Average) > StrengthLevel.Average ||
                WarStateAi.targetValue(civ, owner)?.let { it <= TargetValue.Bad } == true) return true
            if (!civ.ai.military.isPreferredAttackTarget(city)) return true
        }

        // CvUnit::shouldPillage: a unit with XP from pillaging pillages (pillageXpLikeVp)
        if (civ.gameInfo.ruleset.modOptions.constants.pillageXpLikeVp && unit.hasUnique(UniqueType.XpFromPillaging)) return true

        // A route in rough terrain
        if (owner != null && hasRoute &&
            (tile.isHill() || tile.baseTerrain == DESERT || tile.terrainFeatures.any { it == MARSH || it == FOREST } || tile.isAdjacentToRiver())) return true

        val damage = unit.getMaxHealth() - unit.health
        if (conservative) return damage >= heal && heal >= civ.gameInfo.ruleset.ai.define(AiDefine.PILLAGE_HEAL_AMOUNT)
        return damage > 0
    }

    /** CvUnit::PushMission(MISSION_PILLAGE): the unit pillages the tile it stands on; false if it could not */
    fun pillage(unit: MapUnit): Boolean {
        val action = UnitActionsPillage.getPillageAction(unit, unit.getTile())?.action ?: return false
        action()
        return true
    }
}
