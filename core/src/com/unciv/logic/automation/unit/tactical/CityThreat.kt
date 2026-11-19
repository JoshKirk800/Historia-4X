package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.diplomacy.ApproachAi
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import kotlin.math.max

/**
 *  How threatened our cities are, as Vox Populi's CvPlayer::UpdateCityThreatCriteria (CvCity::getThreatValue) and
 *  CvPlayer::GetThreatenedCities have it, and what the city strategy "capital under threat" reads of the tactical map.
 *
 *  A city's threat value is the border score of its land and of its water zone (CvTacticalDominanceZone::GetBorderScore: the neighbouring
 *  zones of enemies, neutral civilizations and unclaimed land, by how hostile they look and how many units stand in them compared with
 *  ours), 50 for each of the two zones the enemy dominates and 23 for each that is evenly matched, the weight of the capital
 *  (AI_MILITARY_CITY_THREAT_WEIGHT_CAPITAL) while a focus area covers the city, and what the land of other civilizations three to five
 *  plots away adds by our opinion of its owner. Danger is ignored: it is too volatile, or rather it is too late then.
 *
 *  Deviations: the original works the values out at the start of the player's turn from the last turn's map; here the first reader of a
 *  turn does, from the tactical map that the turn has built ([TacticalState.map]), and keeps them until the map is rebuilt. The zone sizes
 *  that GetBorderScore tests ("ignore very small zones") are counted over the map's tiles. Neighbouring zones that are temporary zones
 *  (the BNW-era zones Vox Populi no longer has, they take what it calls neutral unclaimed land) count as that land, and so score nothing.
 */
object CityThreat {

    /** CvCity::getThreatValue */
    fun threatValue(city: City): Int = valuesOf(city.civ).value(city)

    /**
     *  CvPlayer::GetThreatenedCities: every city of [civ] (the coastal ones when [coastalOnly]), the most threatened first; cities of equal
     *  threat stay in the order of the player's cities. The most threatened city of a civilization at peace is thus still the first.
     */
    fun threatenedCities(civ: Civilization, coastalOnly: Boolean = false): List<City> {
        val values = valuesOf(civ)
        return civ.cities.filter { !coastalOnly || it.isCoastal() }.sortedByDescending { values.value(it) }
    }

    /**
     *  CityStrategyAIHelpers::IsTestCityStrategy_CapitalUnderThreat: the capital of a civilization that is not a city-state, when its land
     *  zone or its water zone is not one that we dominate.
     */
    fun isCapitalUnderThreat(city: City): Boolean {
        if (!city.isCapital()) return false
        val civ = city.civ
        if (civ.isCityState) return false
        val map = civ.ai.tactical.map
        val land = map.zoneByCity(city, false)
        val water = map.zoneByCity(city, true)
        return (land != null && land.dominance != TacticalDominance.Friendly) || (water != null && water.dominance != TacticalDominance.Friendly)
    }

    private fun valuesOf(civ: Civilization): CityThreats {
        val state = civ.ai.tactical
        val map = state.map
        state.cityThreats?.let { if (it.map === map) return it }
        return CityThreats(civ, map).also { state.cityThreats = it }
    }

    /** The threat values of one civilization's cities for the turn's tactical map, worked out when asked for */
    class CityThreats internal constructor(private val civ: Civilization, val map: TacticalMap) {
        private val values = HashMap<String, Int>()
        private val zonePlots: Map<Int, Int> by lazy { countZonePlots() }
        private val rules = civ.gameInfo.ruleset.ai

        fun value(city: City): Int = values.getOrPut(city.id) { compute(city) }

        private fun compute(city: City): Int {
            var threat = 0
            for (water in listOf(false, true)) {
                val zone = map.zoneByCity(city, water) ?: continue
                threat += borderScore(zone, water)
                threat += when (zone.dominance) {
                    TacticalDominance.Enemy -> ENEMY_DOMINANCE_THREAT
                    TacticalDominance.Even -> EVEN_DOMINANCE_THREAT
                    else -> 0
                }
            }
            // Is this a focused city? If so, we need to support it ASAP.
            val center = city.getCenterTile()
            if (civ.ai.tactical.isInFocusArea(center)) threat += rules.define(AiDefine.AI_MILITARY_CITY_THREAT_WEIGHT_CAPITAL)

            // check the wider area for enemy tiles. may also be on another landmass
            for (distance in NEARBY_FIRST_RING..NEARBY_LAST_RING) for (tile in center.getTilesAtDistance(distance)) {
                if (!tile.isExplored(civ)) continue
                val owner = tile.getOwner() ?: continue
                if (owner == civ || !owner.isMajorCiv) continue
                val near = distance <= NEARBY_FIRST_RING
                val opinion = civ.ai.diplomacy.opinionOf(owner)
                threat += when {
                    opinion == MajorCivOpinion.Neutral -> if (near) 3 else 1
                    opinion == MajorCivOpinion.Competitor -> if (near) 5 else 2
                    opinion < MajorCivOpinion.Competitor -> if (near) 7 else 3
                    else -> 0
                }
            }
            return threat
        }

        /** The number of tiles of every zone of the map (CvTacticalDominanceZone::GetNumPlots) */
        private fun countZonePlots(): Map<Int, Int> {
            val counts = HashMap<Int, Int>()
            for (tile in civ.gameInfo.tileMap.tileList) map.zoneOf(tile)?.let { counts.merge(it.id, 1, Int::plus) }
            return counts
        }

        /**
         *  CvTacticalDominanceZone::GetBorderScore for the zone of one of our cities, over its neighbours in [water]'s domain: for each
         *  neighbouring zone of at least five tiles 8 if an enemy's, for a neutral civilization's 2 and one more for each of: its
         *  visible approach towards us is guarded or worse, ours towards it is, our opinion of it is enemy or worse (a city-state counts
         *  as its ally, none without one); and the units of enemies and neutral civilizations there per unit of ours in this zone.
         */
        private fun borderScore(zone: DominanceZone, water: Boolean): Int {
            var sum = 0
            for (neighbor in zone.neighbors) {
                if ((zonePlots[neighbor.id] ?: 0) < MIN_NEIGHBOR_PLOTS || neighbor.isWater != water) continue
                var score = 0
                when (neighbor.territory) {
                    TacticalTerritory.Enemy -> score += 8
                    TacticalTerritory.Neutral -> {
                        score += 2
                        val owner = neighbor.owner ?: continue
                        val reference = if (owner.isMajorCiv) owner else owner.allyCiv
                        if (reference != null) {
                            val guarded = ApproachAi.VP_ORDER.indexOf(MajorCivApproach.Guarded)
                            if (ApproachAi.VP_ORDER.indexOf(reference.ai.diplomacy.approachTowards(civ, hideTrueFeelings = true)) <= guarded) score++
                            if (ApproachAi.VP_ORDER.indexOf(civ.ai.diplomacy.approachTowards(reference)) <= guarded) score++
                            if (civ.ai.diplomacy.opinionOf(reference) <= MajorCivOpinion.Enemy) score++
                        }
                    }
                    TacticalTerritory.Friendly -> Unit
                    // unclaimed land: Vox Populi's neutral zone without an owner is left out
                    else -> continue
                }
                // try to take into account relative strength
                score += (neighbor.totalEnemyUnitCount + neighbor.neutralUnitCount) / max(1, zone.totalFriendlyUnitCount)
                sum += score
            }
            return sum
        }
    }

    private const val ENEMY_DOMINANCE_THREAT = 50
    private const val EVEN_DOMINANCE_THREAT = 23
    private const val MIN_NEIGHBOR_PLOTS = 5
    /** RING3_PLOTS to RING5_PLOTS: the plots three to five away, the ones within three counting more */
    private const val NEARBY_FIRST_RING = 3
    private const val NEARBY_LAST_RING = 5
}
