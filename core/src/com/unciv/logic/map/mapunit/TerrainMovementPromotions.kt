package com.unciv.logic.map.mapunit

import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/**
 *  The terrain movement promotions of Vox Populi, collected once per unit (see [MapUnitCache.terrainMovementPromotions]) and asked for by
 *  [com.unciv.logic.map.mapunit.movement.MovementCost.getMovementCostBetweenAdjacentTiles] with ModConstants.terrainMovementPromotionsLikeVp:
 *  - `Ignores terrain cost in [tileFilter] tiles`: CvUnitMovement::GetCostsForMove, isIgnoreTerrainCostIn / isIgnoreFeatureCostIn (the cost of the tile is 1)
 *  - `Half movement in [tileFilter] tiles`: GetMovementCostMultiplierFromPromotions, isTerrainHalfMove / isFeatureHalfMove (the cost is doubled)
 *  - `[amount] extra movement cost in [tileFilter] tiles`: GetMovementCostAdderFromPromotions, getTerrainExtraMoveCount / getFeatureExtraMoveCount
 *    (the cost of the most specific matching kind of tile is raised: a mountain, else a hill, else the base terrain, else a feature)
 */
class TerrainMovementPromotions private constructor(
    private val ignoreCostIn: List<String>,
    private val halfMoveIn: List<String>,
    private val extraCostIn: List<ExtraCost>
) {
    private class ExtraCost(val filter: String, val rank: Int, val amount: Int)

    @Readonly
    fun ignoresCostIn(tile: Tile) = ignoreCostIn.any { tile.matchesFilter(it) }

    @Readonly
    fun halvesMovementIn(tile: Tile) = halfMoveIn.any { tile.matchesFilter(it) }

    /** The extra cost of entering [tile]: the sum of the matching uniques of the first kind (lowest rank) that any of them matches */
    @Readonly
    fun extraCostIn(tile: Tile): Int {
        var bestRank = Int.MAX_VALUE
        var amount = 0
        for (extra in extraCostIn) {
            if (extra.rank > bestRank || !tile.matchesFilter(extra.filter)) continue
            if (extra.rank < bestRank) {
                bestRank = extra.rank
                amount = 0
            }
            amount += extra.amount
        }
        return amount
    }

    companion object {
        private const val MOUNTAIN = 0
        private const val HILL = 1
        private const val BASE_TERRAIN = 2
        private const val FEATURE = 3

        /** @return the promotions of [unit], or `null` if it has none */
        fun of(unit: MapUnit): TerrainMovementPromotions? {
            val terrains = unit.civ.gameInfo.ruleset.terrains
            val ignore = ArrayList<String>()
            val half = ArrayList<String>()
            val extra = ArrayList<ExtraCost>()
            unit.forEachMatchingUnique(UniqueType.IgnoresTerrainCostIn, gameContext = GameContext.IgnoreConditionals) { ignore += it.params[0] }
            unit.forEachMatchingUnique(UniqueType.HalfMovementIn, gameContext = GameContext.IgnoreConditionals) { half += it.params[0] }
            unit.forEachMatchingUnique(UniqueType.ExtraMovementCostIn, gameContext = GameContext.IgnoreConditionals) {
                val filter = it.params[1]
                val terrain = terrains[filter]
                val rank = when {
                    terrain == null -> BASE_TERRAIN
                    terrain.isMountain -> MOUNTAIN
                    terrain.isHill -> HILL
                    terrain.type == TerrainType.TerrainFeature -> FEATURE
                    else -> BASE_TERRAIN
                }
                extra += ExtraCost(filter, rank, it.params[0].toInt())
            }
            if (ignore.isEmpty() && half.isEmpty() && extra.isEmpty()) return null
            return TerrainMovementPromotions(ignore, half, extra)
        }
    }
}
