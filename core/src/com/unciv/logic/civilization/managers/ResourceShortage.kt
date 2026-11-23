package com.unciv.logic.civilization.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.tile.ResourceType
import yairm210.purity.annotations.Readonly
import kotlin.math.min

/**
 * Vox Populi's rule for a civilization that uses more of a strategic resource than it has, behind [com.unciv.models.ModConstants.resourceShortageLikeVp]
 * (BALANCE_RESOURCE_SHORTAGE_UNIT_HEALING and BALANCE_RESOURCE_SHORTAGE_BUILDING_REFUND, V:CvUnit.cpp:7717-7727, 16139-16141, 18218-18232; V:CvCity.cpp:2525-2590;
 * V:CvTreasury.cpp:611-625; the combat malus of 0 is in [com.unciv.logic.battle.BattleDamage]).
 *
 * The refund is a notional pool, not a change of the resources: the real amount stays below 0 (so units keep not healing) and the buildings keep working.
 */
object ResourceShortage {
    /** The percent of building maintenance each refunded copy costs (V:CvCity.cpp: `iExcessConsumption * 2`) */
    private const val MAINTENANCE_PERCENT_PER_COPY = 2

    /**
     * CvUnit::canHeal and IsCannotHeal: a unit of a major civilization does not heal while the civilization has less than 0 of a resource the unit needs
     * (CvPlayer::HasResourceForNewUnit with bContinue: the copies the unit holds are already used, so only a negative amount fails). False without the flag.
     */
    @Readonly
    fun cannotHeal(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (!civ.gameInfo.ruleset.modOptions.constants.resourceShortageLikeVp || !civ.isMajorCiv) return false
        for (resource in unit.getResourceRequirementsPerTurn().keys)
            if (civ.getResourceAmount(resource) < 0) return true
        return false
    }

    /**
     * CvCity::DoTurn, run for each city at the start of the owner's turn: for every strategic resource the civilization is short of (used and available below 0)
     * the shortage is a pool; the buildings of each city, in ruleset order, take min(pool, what the building uses) out of it, 2 percent of maintenance a copy.
     * The city keeps the sum as [com.unciv.logic.city.City.extraBuildingMaintenance] (0 when nothing was refunded). Does nothing without the flag or for minor civilizations.
     */
    fun refundBuildings(civ: Civilization) {
        val ruleset = civ.gameInfo.ruleset
        if (!ruleset.modOptions.constants.resourceShortageLikeVp || !civ.isMajorCiv) return
        val penalties = HashMap<City, Int>()
        for (resource in ruleset.tileResources.values) {
            if (resource.resourceType != ResourceType.Strategic || resource.isStockpiled) continue
            var pool = -civ.getResourceAmount(resource)
            if (pool <= 0) continue
            for (city in civ.cities) {
                for (building in ruleset.buildings.values) {
                    if (!city.cityConstructions.isBuilt(building.name)) continue
                    val used = building.getResourceRequirementsPerTurn(city.state)[resource.name]
                    if (used <= 0) continue
                    val refunded = min(pool, used)
                    penalties[city] = (penalties[city] ?: 0) + refunded * MAINTENANCE_PERCENT_PER_COPY
                    pool -= refunded
                    if (pool <= 0) break
                }
                if (pool <= 0) break
            }
        }
        for (city in civ.cities) city.extraBuildingMaintenance = penalties[city] ?: 0
    }

    /** CvTreasury::GetBuildingGoldMaintenance: the percent that the refunded copies of all cities add to the maintenance of every building. 0 without the flag. */
    @Readonly
    fun extraMaintenancePercent(civ: Civilization): Int {
        if (!civ.gameInfo.ruleset.modOptions.constants.resourceShortageLikeVp) return 0
        return civ.cities.sumOf { it.extraBuildingMaintenance }
    }
}
