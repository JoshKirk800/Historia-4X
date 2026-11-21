package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.Constants
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.toStats
import com.unciv.models.ruleset.tile.ResourceType
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 *  Where Vox Populi's AssignStartingPlots:CanPlaceCityStateAt allows a city-state, apart from the checks that depend on what has been placed
 *  already (the impact layer, the landmass): land that is no mountain, not on the outermost ring of the map, no ice within 1 tile, and not
 *  one of the best city sites, `Plot_GetFertilityInRange(plot, 2) <= 30`. Snow is allowed.
 *
 *  Plot_GetFertility is measured in yields, not in the region fertility of [getTileFertility] (which is on another scale: grassland is 3 there,
 *  a whole ring of grassland is 45 against the limit of 30). The city-states are placed before the resources, so the yields are those of the terrain.
 */
internal object VoxPopuliCityStateSites {
    /** `if fertility > 30 then return false` */
    const val MAX_FERTILITY = 30.0

    /** The tile is a site as far as its terrain and surroundings go. */
    @Readonly
    fun isSite(tile: Tile): Boolean {
        if (tile.isWater || tile.isImpassible()) return false
        // `x < 1 or x >= iW - 1`: a tile on the outermost ring is missing a neighbor
        if (tile.neighbors.count() < 6) return false
        if (tile.neighbors.any { neighbor -> neighbor.terrainFeatureObjects.any { it.isIce } }) return false
        return fertilityInRange2(tile) <= MAX_FERTILITY
    }

    /** `Plot_GetFertilityInRange(plot, 2)`: the fertility of each tile at distance 1 and 2 (not the tile itself), divided by its distance. */
    @Readonly
    fun fertilityInRange2(tile: Tile): Double {
        var sum = 0.0
        for (distance in 1..2)
            for (near in tile.getTilesAtDistance(distance)) sum += tileFertility(near) / distance
        return sum
    }

    /** `Plot_GetFertility(plot)`: the yields (food + production + gold + 2 x science + 2 x culture + 2 x faith) with a few adjustments. */
    @Readonly
    fun tileFertility(tile: Tile): Double {
        if (tile.isImpassible() || tile.getBaseTerrain().isOcean) return 0.0
        val stats = tile.stats.getTerrainStatsBreakdown().toStats()
        var value = (stats.food + stats.production + stats.gold + 2 * (stats.science + stats.culture + stats.faith)).toDouble()
        val isLake = tile.isWater && tile.getBaseTerrain().isFreshwater
        // Fresh water farm possibility
        if (!tile.isHill() && tile.isAdjacentTo(Constants.freshWater)) value += 0.25
        // Can't improve lakes
        if (isLake) value -= 0.5
        // Jungles aren't as good as the yields imply
        if (Constants.jungle in tile.terrainFeatures) value -= 0.5
        val resource = tile.tileResource
        if (resource == null) {
            // Can't improve coast tiles until lighthouse
            if (tile.terrainFeatures.isEmpty() && tile.getBaseTerrain().isCoast) value -= 1
        } else when {
            resource.resourceType == ResourceType.Luxury -> value += 2 * LUXURY_HAPPINESS
            resource.resourceType == ResourceType.Bonus -> value += 2
            // RESOURCECLASS_RUSH: iron and horses
            resource.name == "Iron" || resource.name == "Horses" -> value += ceil(3 * sqrt(tile.resourceAmount.toDouble()))
        }
        return value
    }

    /** `<Happiness>4</Happiness>` of every luxury (CIV5Resources.xml) */
    private const val LUXURY_HAPPINESS = 4
}
