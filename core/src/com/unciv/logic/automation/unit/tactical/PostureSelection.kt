package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine

/**
 * Select a tactical posture per zone. Zone eligibility and temporary operation zones remain from the current operation framework;
 * ordinary posture selection follows Vox Populi 5.4.8's CvTacticalDominanceZone::SelectPostureSingleZone.
 */
object PostureSelection {

    /** CvTacticalAI::UseThisDominanceZone: temporary zones, the capital's zone, zones where we have units near the enemy's city, where they have units near us, or both have units */
    fun useThisZone(civ: Civilization, state: TacticalState, zone: DominanceZone): Boolean {
        val city = zone.closestCity
        val isOurCapital = city != null && city == civ.getCapital()
        val isTempZone = city != null && state.isTemporaryZoneCity(city)
        val weHaveUnitsNearEnemy = zone.totalFriendlyUnitCount > 0 && zone.territory == TacticalTerritory.Enemy
        // The dominance cannot tell: our own city makes a quiet zone Friendly. Enemy units are what the enemy has near us.
        val theyHaveUnitsNearUs = zone.overallEnemyStrength > 0 && zone.territory == TacticalTerritory.Friendly
        val bothHaveUnits = zone.totalFriendlyUnitCount > 0 && zone.totalEnemyUnitCount > 0
        return isTempZone || isOurCapital || weHaveUnitsNearEnemy || theyHaveUnitsNearUs || bothHaveUnits
    }

    /** Choose a fresh posture for each zone used by the current operation framework. */
    fun updatePostures(civ: Civilization, state: TacticalState, map: TacticalMap) {
        val newPostures = ArrayList<ZonePosture>()
        for (zone in map.zones) {
            if (!useThisZone(civ, state, zone)) continue
            val ownerId = zone.owner?.civName
            val cityId = zone.closestCity?.id
            val entry = ZonePosture()
            entry.ownerId = ownerId
            entry.isWater = zone.isWater
            entry.cityId = cityId
            entry.posture = coordinateWithNeighbors(zone, selectPosture(civ, state, zone))
            newPostures.add(entry)
        }
        state.postures = newPostures
    }

    /** CvTacticalDominanceZone::SelectPostureMultiZone: support threatened friendly neighbors and avoid exposed naval advances. */
    private fun coordinateWithNeighbors(zone: DominanceZone, initial: TacticalPosture): TacticalPosture {
        if (zone.owner == null) return initial
        var posture = initial
        for (neighbor in zone.neighbors) {
            if (zone.isWater && posture == TacticalPosture.Steamroll && !neighbor.isWater &&
                neighbor.dominance == TacticalDominance.Enemy && neighbor.enemyRangedStrength > zone.friendlyNavalRangedStrength)
                posture = TacticalPosture.ExploitFlanks
            if (zone.territory != TacticalTerritory.Friendly && zone.isWater == neighbor.isWater &&
                neighbor.territory == TacticalTerritory.Friendly && neighbor.dominance == TacticalDominance.Enemy) {
                val city = neighbor.closestCity
                if (city == null || !CityDangerAi.isInDangerOfFalling(city, false)) posture = TacticalPosture.Withdraw
            }
        }
        return posture
    }

    /**
     * CvTacticalDominanceZone::SelectPostureSingleZone. Temporary operation zones retain the current framework's assault and
     * shore-bombardment overrides until operations move to VP's focus areas.
     */
    fun selectPosture(civ: Civilization, state: TacticalState, zone: DominanceZone): TacticalPosture {
        val city = zone.closestCity
        if (zone.territory == TacticalTerritory.TempZone)
            return if (zone.isWater) TacticalPosture.ShoreBombardment else TacticalPosture.ExploitFlanks
        if (zone.territory == TacticalTerritory.Enemy && city != null && state.isTemporaryZoneCity(city))
            return if (zone.dominance == TacticalDominance.Enemy) TacticalPosture.SurgicalCityStrike else TacticalPosture.Steamroll
        if (zone.owner == null) return TacticalPosture.ExploitFlanks

        val percentage = civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_DOMINANCE_PERCENTAGE)
        val friendlyRanged = zone.friendlyDomainRangedStrength
        val enemyRanged = zone.enemyDomainRangedStrength
        val rangedRatio = (0.5f + 100 * (friendlyRanged.toFloat() / maxOf(1, enemyRanged))).toInt()
        val rangedAdvantage = (enemyRanged == 0 && friendlyRanged > 0) || rangedRatio > 100 + percentage
        val friendlyFront = if (zone.isWater) zone.friendlyNavalStrength else zone.friendlyMeleeStrength
        val enemyFront = if (zone.isWater) zone.enemyNavalStrength else zone.enemyMeleeStrength
        val counterattackRisk = enemyFront.toLong() > 3L * friendlyFront && friendlyRanged.toLong() > 3L * friendlyFront

        return when (zone.territory) {
            TacticalTerritory.Enemy -> when {
                city != null && CityDangerAi.isInDangerOfFalling(city, false) -> TacticalPosture.SurgicalCityStrike
                zone.dominance == TacticalDominance.Enemy || counterattackRisk -> TacticalPosture.Withdraw
                zone.dominance == TacticalDominance.Even && zone.enemyMeleeStrength > zone.friendlyMeleeStrength ->
                    if (rangedAdvantage) TacticalPosture.AttritFromRange else TacticalPosture.ExploitFlanks
                zone.dominance == TacticalDominance.Even ->
                    if (rangedAdvantage) TacticalPosture.Steamroll else TacticalPosture.ExploitFlanks
                zone.dominance == TacticalDominance.Friendly -> TacticalPosture.Steamroll
                else -> TacticalPosture.AttritFromRange
            }
            TacticalTerritory.Neutral, TacticalTerritory.NoOwner, TacticalTerritory.None -> when {
                zone.dominance == TacticalDominance.Enemy && !rangedAdvantage -> TacticalPosture.Withdraw
                zone.dominance == TacticalDominance.Enemy && zone.enemyMeleeStrength <= zone.friendlyMeleeStrength ->
                    TacticalPosture.ExploitFlanks
                rangedAdvantage -> TacticalPosture.AttritFromRange
                else -> TacticalPosture.ExploitFlanks
            }
            TacticalTerritory.Friendly ->
                if (zone.dominance == TacticalDominance.Enemy) TacticalPosture.Hedgehog else TacticalPosture.Counterattack
            TacticalTerritory.TempZone -> if (zone.isWater) TacticalPosture.ShoreBombardment else TacticalPosture.ExploitFlanks
        }
    }
}
