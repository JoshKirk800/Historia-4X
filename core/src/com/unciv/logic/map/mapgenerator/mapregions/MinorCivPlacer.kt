package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.equalsPlaceholderText
import com.unciv.models.translations.getPlaceholderParameters
import yairm210.purity.annotations.Readonly
import com.unciv.logic.map.HexMath
import kotlin.math.ceil
import kotlin.math.min

/** The city-states and the regions they are assigned to, in the order of assignment (a null region: the uninhabited landmasses).
 *  AssignStartingPlots:PlaceCityStates places them in this order. */
private typealias Assignments = MutableList<Pair<Civilization, Region?>>

object MinorCivPlacer {

    /** Which tiles are sites for a city-state (AssignStartingPlots:CanPlaceCityStateAt) */
    private enum class SiteRule {
        /** Land that is not undesirable for a start and not at the edge of the map */
        Generic,
        /** Brave New World: land that is no mountain and no snow */
        CivV,
        /** Vox Populi: land that is no mountain, not at the edge, no ice near, not too fertile (see [VoxPopuliCityStateSites]) */
        VoxPopuli
    }

    private fun siteRule(ruleset: Ruleset) = ruleset.modOptions.constants.run {
        when {
            cityStatePlacementLikeVoxPopuli -> SiteRule.VoxPopuli
            cityStateSitesLikeCivV -> SiteRule.CivV
            else -> SiteRule.Generic
        }
    }

    /** Assigns [civs] to regions or "uninhabited" land and places them. Depends on
     *  assignLuxuries having been called previously.
     *  Note: can silently fail to place all city states if there is too little room.
     *  Currently our GameStarter fills out with random city states, Civ V behavior is to
     *  forget about the discarded city states entirely. With `cityStatePlacementLikeVoxPopuli` a city state that
     *  does not fit is discarded, after a last chance on the whole map, as Vox Populi does; otherwise it is forced in. */
    fun placeMinorCivs(regions: List<Region>, tileMap: TileMap, civs: List<Civilization>, tileData: TileDataMap, ruleset: Ruleset) {
        if (civs.isEmpty()) return
        val assignments: Assignments = ArrayList()

        // Some but not all city states are assigned to regions directly. Determine the CS density.
        val unassignedCivs = assignMinorCivsDirectlyToRegions(civs, regions, assignments)

        // Some city states are assigned to "uninhabited" continents - unless it's an archipelago type map
        // (Because then every continent will have been assigned to a region anyway)
        val uninhabitedCoastal = ArrayList<Tile>()
        val uninhabitedHinterland = ArrayList<Tile>()
        val civAssignedToUninhabited = ArrayList<Civilization>()
        if (!tileMap.usingArchipelagoRegions()) {
            spreadCityStatesBetweenHabitedAndUninhabited(
                tileMap,
                regions,
                tileData,
                uninhabitedCoastal,
                uninhabitedHinterland,
                civs,
                unassignedCivs,
                civAssignedToUninhabited,
                ruleset
            )
            for (civ in civAssignedToUninhabited) assignments.add(civ to null)
        }

        assignCityStatesToRegionsWithCommonLuxuries(regions, unassignedCivs, assignments)
        spreadCityStatesEvenlyBetweenRegions(unassignedCivs, regions, assignments)
        assignRemainingCityStatesToWorstFertileRegions(regions, unassignedCivs, ruleset.modOptions.constants.startAssignmentLikeCivV, assignments)

        // After we've finished assigning, NOW we actually place them
        if (siteRule(ruleset) == SiteRule.VoxPopuli) {
            placeAssignedMinorCivsLikeVoxPopuli(assignments, tileMap, uninhabitedCoastal, uninhabitedHinterland, tileData, ruleset, regions)
            return
        }
        placeAssignedMinorCivs(
            civAssignedToUninhabited,
            tileMap,
            uninhabitedCoastal,
            tileData,
            ruleset,
            uninhabitedHinterland,
            regions
        )
    }

    private fun spreadCityStatesBetweenHabitedAndUninhabited(
        tileMap: TileMap,
        regions: List<Region>,
        tileData: TileDataMap,
        uninhabitedCoastal: ArrayList<Tile>,
        uninhabitedHinterland: ArrayList<Tile>,
        civs: List<Civilization>,
        unassignedCivs: MutableList<Civilization>,
        civAssignedToUninhabited: ArrayList<Civilization>,
        ruleset: Ruleset
    ) {
        val siteRule = siteRule(ruleset)
        // Vox Populi counts every site of a landmass without civs as uninhabited land, and only drops from the candidates those of a landmass
        // with less than 2 sites (areas_too_small); the others don't bother with islands of less than 4 tiles at all
        val vp = siteRule == SiteRule.VoxPopuli
        val uninhabitedContinents = if (vp) tileMap.values.map { it.getContinent() }.distinct().filter { continent ->
                regions.none { region -> region.continentID == continent }
            }.toSet()
        else tileMap.continentSizes.filter {
            it.value >= 4 && // Don't bother with tiny islands
                regions.none { region -> region.continentID == it.key }
        }.keys
        var numInhabitedTiles = 0
        var numUninhabitedTiles = 0
        val sitesPerContinent = HashMap<Int, Int>()
        // Go through the entire map to build the data
        for (tile in tileMap.values) {
            if (!canPlaceMinorCiv(tile, tileData, siteRule)) continue
            val continent = tile.getContinent()
            if (continent in uninhabitedContinents) {
                if (tile.isAdjacentToCoast())
                    uninhabitedCoastal.add(tile)
                else
                    uninhabitedHinterland.add(tile)
                numUninhabitedTiles++
                sitesPerContinent[continent] = (sitesPerContinent[continent] ?: 0) + 1
            } else
                numInhabitedTiles++
        }
        if (vp) {
            uninhabitedCoastal.removeAll { sitesPerContinent[it.getContinent()]!! < 2 }
            uninhabitedHinterland.removeAll { sitesPerContinent[it.getContinent()]!! < 2 }
        }
        // Determine how many minor civs to put on uninhabited continents.
        val maxByUninhabited =
            if (numInhabitedTiles + numUninhabitedTiles == 0) 0
            else (3 * civs.size * numUninhabitedTiles) / (numInhabitedTiles + numUninhabitedTiles)
        val maxByRatio = (civs.size + 1) / 2
        // AssignCityStatesToRegionsOrToUninhabited only hands out what the regions did not take already
        val targetForUninhabited = if (ruleset.modOptions.constants.startAssignmentLikeCivV)
            minOf(maxByRatio, maxByUninhabited, unassignedCivs.size) else min(maxByRatio, maxByUninhabited)
        // Prefer Coast-bias CS for wilderness slots (often coastal).
        val ordered = unassignedCivs.filter { prefersCoastalStart(it, ruleset) } +
            unassignedCivs.filterNot { prefersCoastalStart(it, ruleset) }
        val civsToAssign = ordered.take(targetForUninhabited)
        unassignedCivs.removeAll(civsToAssign)
        civAssignedToUninhabited.addAll(civsToAssign)
    }

    /** If there are still unassigned minor civs, assign extra ones to regions that share their
     *  luxury type with two others, as compensation. Because starting close to a city state is good??
     */
    private fun assignCityStatesToRegionsWithCommonLuxuries(
        regions: List<Region>,
        unassignedCivs: MutableList<Civilization>,
        assignments: Assignments
    ) {
        if (unassignedCivs.isEmpty()) return
        val regionsWithCommonLuxuries = regions.filter {
            regions.count { other -> other.luxury == it.luxury } >= 3
        }
        // assign one civ each to regions with common luxuries if there are enough to go around
        if (regionsWithCommonLuxuries.isNotEmpty() &&
            regionsWithCommonLuxuries.size <= unassignedCivs.size
        ) {
            regionsWithCommonLuxuries.forEach {
                assignToRegion(unassignedCivs.removeAt(0), it, assignments)
            }
        }
    }

    private fun assignToRegion(civ: Civilization, region: Region, assignments: Assignments) {
        region.assignedMinorCivs.add(civ)
        assignments.add(civ to region)
    }

    /** Add one extra to each region as long as there are enough to go around */
    private fun spreadCityStatesEvenlyBetweenRegions(
        unassignedCivs: MutableList<Civilization>,
        regions: List<Region>,
        assignments: Assignments
    ) {
        if (unassignedCivs.isEmpty()) return
        while (unassignedCivs.size >= regions.size) {
            regions.forEach {
                assignToRegion(unassignedCivs.removeAt(0), it, assignments)
            }
        }
    }


    /** At this point there is at least for sure less remaining city states than regions
        Sort regions by fertility and put extra city states in the worst ones. */
    private fun assignRemainingCityStatesToWorstFertileRegions(
        regions: List<Region>,
        unassignedCivs: MutableList<Civilization>,
        fertilityPerLandTile: Boolean,
        assignments: Assignments
    ) {
        if (unassignedCivs.isEmpty()) return
        // Civ V: the regions with the least fertility per land tile, not the least in total
        val worstRegions = if (fertilityPerLandTile)
            regions.sortedBy { region -> region.totalFertility.toFloat() / region.tiles.count { it.isLand }.coerceAtLeast(1) }.take(unassignedCivs.size)
        else regions.sortedBy { it.totalFertility }.take(unassignedCivs.size)
        worstRegions.forEach {
            assignToRegion(unassignedCivs.removeAt(0), it, assignments)
        }
    }

    /** Actually placee the minor civs, after they have been sorted into groups and assigned to regions */
    private fun placeAssignedMinorCivs(
        civAssignedToUninhabited: ArrayList<Civilization>,
        tileMap: TileMap,
        uninhabitedCoastal: ArrayList<Tile>,
        tileData: TileDataMap,
        ruleset: Ruleset,
        uninhabitedHinterland: ArrayList<Tile>,
        regions: List<Region>
    ) {
        // All minor civs are assigned - now place them
        // First place the "uninhabited continent" ones, preferring coastal starts
        tryPlaceMinorCivsInTiles(
            civAssignedToUninhabited, tileMap, uninhabitedCoastal, tileData, ruleset
        )
        tryPlaceMinorCivsInTiles(
            civAssignedToUninhabited, tileMap, uninhabitedHinterland, tileData, ruleset
        )
        // Fallback to a random region for civs that couldn't be placed in the wilderness
        for (unplacedCiv in civAssignedToUninhabited) {
            val rng = unplacedCiv.state.stateBasedRandom("MinorCivPlacer.placeAssignedMinorCivs")
            regions.random(rng).assignedMinorCivs.add(unplacedCiv)
        }

        // Now place the ones assigned to specific regions.
        for (region in regions) {
            // More constrained biases (and coastal) first so they do not lose scarce matching tiles.
            val ordered = region.assignedMinorCivs.sortedWith(
                compareByDescending<Civilization> {
                    it.nation.getStartBias(ruleset, it.getGameContextForStartBias()).size
                }.thenByDescending { prefersCoastalStart(it, ruleset) }
            )
            val unplaced = ordered.filterNot { placeMinorCivInRegionLikeCivV(it, region, tileMap, tileData, ruleset) }
                .toMutableList()
            // Only if nothing in the region was free, even when forced
            tryPlaceMinorCivsInTiles(unplaced, tileMap, region.tiles.toMutableList(), tileData, ruleset)
        }
    }

    /**
     *  AssignStartingPlots:PlaceCityStateInRegion: looks for a place along the outer band of the region first
     *  (the 16.7 % of its longer side, and once that fails for the band of what is left inside it), a coastal tile
     *  before an inland one. The tiles must be free of the impact of any start, then (forced) may be within the
     *  impact but not on a start, then may be on one. Vox Populi (`cityStatePlacementLikeVoxPopuli`) has no forced passes.
     *  The way the original shrinks the part it looks at is kept: a region that is wider than tall is searched
     *  along its left and right edges but shrinks at the top and bottom, and the other way around.
     *  @return whether a place was found
     */
    private fun placeMinorCivInRegionLikeCivV(
        civ: Civilization, region: Region, tileMap: TileMap, tileData: TileDataMap, ruleset: Ruleset
    ): Boolean {
        val biases = civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())
        val rng = GameContext(gameInfo = tileMap.gameInfo).stateBasedRandom("MinorCivPlacer.placeMinorCivInRegionLikeCivV")
        val siteRule = siteRule(ruleset)
        // Vox Populi only looks for a free site: PlaceCityStateInRegion calls ObtainNextSectionInRegion with force_it and ignore_collisions false
        val passes = if (siteRule == SiteRule.VoxPopuli) listOf(false to false)
            else listOf(false to false, true to false, true to true)

        for ((force, ignoreCollisions) in passes) {
            var x = region.rect.x.toInt()
            var y = region.rect.y.toInt()
            var width = region.rect.width.toInt()
            var height = region.rect.height.toInt()
            var reachedMiddle = false
            while (!reachedMiddle) {
                if (width <= 0 || height <= 0) break
                if (width < 4 || height < 4) reachedMiddle = true
                val taller = height > width
                val rowsToCheck = ceil(0.167 * (if (taller) height else width)).toInt()

                val coastal = ArrayList<Tile>()
                val inland = ArrayList<Tile>()
                for (row in y until y + height) {
                    for (column in x until x + width) {
                        val inBand = reachedMiddle ||
                            if (taller) row < y + rowsToCheck || row >= y + height - rowsToCheck
                            else column < x + rowsToCheck || column >= x + width - rowsToCheck
                        if (!inBand) continue
                        val coords = HexMath.getTileCoordsFromColumnRow(column, row)
                        val tile = tileMap.getIfTileExistsOrNull(coords.x.toInt(), coords.y.toInt()) ?: continue
                        if (!canPlaceMinorCivInRegion(tile, region, tileData, force, ignoreCollisions, siteRule)) continue
                        if (tile.isAdjacentToCoast()) coastal.add(tile) else inland.add(tile)
                    }
                }
                if (taller) {
                    x += rowsToCheck
                    width -= 2 * rowsToCheck
                } else {
                    y += rowsToCheck
                    height -= 2 * rowsToCheck
                }

                val candidates = sequenceOf(coastal, inland).map { list ->
                    filterTilesByStartBias(list, biases).ifEmpty { list }
                }.firstOrNull { it.isNotEmpty() } ?: continue
                placeMinorCiv(civ, tileMap, candidates.random(rng), tileData, ruleset)
                return true
            }
        }
        return false
    }

    /** AssignStartingPlots:CanPlaceCityStateAt: land of the region's landmass that [canPlaceMinorCiv] allows, free of the impact of another start
     *  (or, when [force]d, free of a start itself). */
    @Readonly
    private fun canPlaceMinorCivInRegion(
        tile: Tile, region: Region, tileData: TileDataMap, force: Boolean, ignoreCollisions: Boolean, siteRule: SiteRule
    ): Boolean {
        if (region.continentID != -1 && tile.getContinent() != region.continentID) return false
        if (!canPlaceMinorCiv(tile, tileData, siteRule)) return false
        val impact = tileData[tile]!!.impacts[MapRegions.ImpactType.MinorCiv] ?: return true
        if (!force) return false
        return ignoreCollisions || impact != 99 // 99 is a start itself
    }

    private fun assignMinorCivsDirectlyToRegions(
        civs: List<Civilization>,
        regions: List<Region>,
        assignments: Assignments
    ): MutableList<Civilization> {
        val minorCivRatio = civs.size.toFloat() / regions.size
        val minorCivPerRegion = when {
            minorCivRatio > 14f -> 10 // lol
            minorCivRatio > 11f -> 8
            minorCivRatio > 8f -> 6
            minorCivRatio > 5.7f -> 4
            minorCivRatio > 4.35f -> 3
            minorCivRatio > 2.7f -> 2
            minorCivRatio > 1.35f -> 1
            else -> 0
        }
        val rng = GameContext(gameInfo = regions.first().tileMap.gameInfo).stateBasedRandom("MinorCivPlacer.assignMinorCivsDirectlyToRegions")
        val unassignedCivs = civs.shuffled(rng).toMutableList()
        if (minorCivPerRegion > 0) {
            regions.forEach {
                val civsToAssign = unassignedCivs.take(minorCivPerRegion)
                for (civ in civsToAssign) assignToRegion(civ, it, assignments)
                unassignedCivs.removeAll(civsToAssign)
            }
        }
        return unassignedCivs
    }

    /**
     *  Attempts to place civs from [civsToPlace] in tiles from [tileList]. Assumes that
     *  [tileList] is pre-vetted and only contains habitable land tiles.
     *  Respects start biases (nation field + StartBias uniques on nation / [CityStateType]),
     *  including `Avoid [terrainFilter]`, same idea as [com.unciv.logic.GameStarter] major placement.
     *  Will modify both [civsToPlace] and [tileList] as it goes!
     */
    private fun tryPlaceMinorCivsInTiles(civsToPlace: MutableList<Civilization>, tileMap: TileMap, tileList: MutableList<Tile>, tileData: TileDataMap, ruleset: Ruleset) {
        // More constrained biases (and coastal) first so they do not lose scarce matching tiles.
        val ordered = civsToPlace.sortedWith(
            compareByDescending<Civilization> {
                it.nation.getStartBias(ruleset, it.getGameContextForStartBias()).size
            }.thenByDescending { prefersCoastalStart(it, ruleset) }
        ).toMutableList()
        civsToPlace.clear()
        civsToPlace.addAll(ordered)

        while (tileList.isNotEmpty() && civsToPlace.isNotEmpty()) {
            val civToAdd = civsToPlace.first()
            val preferred = filterTilesByStartBias(
                tileList,
                civToAdd.nation.getStartBias(ruleset, civToAdd.getGameContextForStartBias())
            )
            val pool = preferred.ifEmpty { tileList }

            val rng = GameContext(gameInfo = tileMap.gameInfo).stateBasedRandom("MinorCivPlcer.tryPlaceMinorCivsInTiles")
            val chosenTile = pool.random(rng)
            tileList.remove(chosenTile)
            val data = tileData[chosenTile]!!
            // If the randomly chosen tile is too close to a player or a city state, discard it
            if (data.impacts.containsKey(MapRegions.ImpactType.MinorCiv))
                continue
            // Otherwise, go ahead and place the minor civ
            civsToPlace.remove(civToAdd)
            placeMinorCiv(civToAdd, tileMap, chosenTile, tileData, ruleset)
        }
    }

    /** Prefer tiles matching positive start biases; drop tiles matching `Avoid [filter]`. */
    @Readonly
    fun filterTilesByStartBias(tiles: List<Tile>, startBiases: Collection<String>): List<Tile> {
        var preferred = tiles
        for (startBias in startBiases) {
            preferred = when {
                startBias.equalsPlaceholderText("Avoid []") -> {
                    val tileToAvoid = startBias.getPlaceholderParameters()[0]
                    preferred.filter { tile ->
                        tile.getTilesInDistance(1).none { it.matchesTerrainFilter(tileToAvoid, null) }
                    }
                }
                else -> preferred.filter { tile ->
                    tile.getTilesInDistance(1).any { it.matchesTerrainFilter(startBias, null) }
                }
            }
        }
        return preferred
    }

    /** True when nation or city-state type start bias includes Coast. */
    @Readonly
    fun prefersCoastalStart(civ: Civilization, ruleset: Ruleset): Boolean =
        "Coast" in civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())

    /** The land a city-state may be put on, by the [siteRule]: [SiteRule.CivV] land that is no mountain and no snow (Brave New World's
     *  AssignStartingPlots:CanPlaceCityStateAt), [SiteRule.VoxPopuli] see [VoxPopuliCityStateSites.isSite],
     *  else land that is not undesirable for a start (no snow hills, no desert outside desert regions) and not at the edge of the map. */
    @Readonly
    private fun canPlaceMinorCiv(tile: Tile, tileData: TileDataMap, siteRule: SiteRule) =
        when (siteRule) {
            SiteRule.VoxPopuli -> VoxPopuliCityStateSites.isSite(tile)
            SiteRule.CivV -> !tile.isWater && !tile.isImpassible() && tile.baseTerrain != Constants.snow
            SiteRule.Generic -> !tile.isWater && !tile.isImpassible() &&
                !tileData[tile]!!.isJunk &&
                tile.getBaseTerrain().getMatchingUniques(UniqueType.HasQuality).none { it.params[0] == "Undesirable" } && // So we don't get snow hills
                tile.neighbors.count() == 6 // Avoid map edges
        }

    /**
     *  AssignStartingPlots:PlaceCityStates with Vox Populi's rules. The city states are placed in the order they were assigned.
     *  One assigned to a region that finds no free site in it is discarded, one assigned to the uninhabited landmasses takes a free site
     *  of the coastal ones, else the inland ones (and is discarded when there is none; with no candidates at all it is sent to a random
     *  region instead). Then the discarded ones get a random free site anywhere on the map ("last chance"), and the rest are dropped.
     *  A site is free when no start or city state has its impact on it.
     */
    private fun placeAssignedMinorCivsLikeVoxPopuli(
        assignments: Assignments,
        tileMap: TileMap,
        uninhabitedCoastal: List<Tile>,
        uninhabitedHinterland: List<Tile>,
        tileData: TileDataMap,
        ruleset: Ruleset,
        regions: List<Region>
    ) {
        var uninhabitedCandidates = uninhabitedCoastal.size + uninhabitedHinterland.size
        val discarded = ArrayList<Civilization>()
        for ((civ, assignedRegion) in assignments) {
            val region = if (assignedRegion != null) assignedRegion
                else if (uninhabitedCandidates > 0) null
                else regions.random(civ.state.stateBasedRandom("MinorCivPlacer.placeAssignedMinorCivsLikeVoxPopuli"))
            val placed = if (region != null) placeMinorCivInRegionLikeCivV(civ, region, tileMap, tileData, ruleset)
                else {
                    uninhabitedCandidates--
                    placeMinorCivOnFreeSite(civ, listOf(uninhabitedCoastal, uninhabitedHinterland), tileMap, tileData, ruleset)
                }
            if (!placed) discarded.add(civ)
        }
        if (discarded.isEmpty()) return

        val everywhere = tileMap.values.filter { VoxPopuliCityStateSites.isSite(it) }
        for (civ in discarded)
            if (!placeMinorCivOnFreeSite(civ, listOf(everywhere), tileMap, tileData, ruleset)) break
    }

    /** AssignStartingPlots:PlaceCityState with proximity and collision checks: a random tile that is free of any impact from the first of the
     *  [lists] that has one (preferring those that match the start bias of [civ]). */
    private fun placeMinorCivOnFreeSite(
        civ: Civilization, lists: List<List<Tile>>, tileMap: TileMap, tileData: TileDataMap, ruleset: Ruleset
    ): Boolean {
        val biases = civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())
        val rng = GameContext(gameInfo = tileMap.gameInfo).stateBasedRandom("MinorCivPlacer.placeMinorCivOnFreeSite")
        for (list in lists) {
            val free = list.filter { !tileData[it]!!.impacts.containsKey(MapRegions.ImpactType.MinorCiv) }
            val candidates = filterTilesByStartBias(free, biases).ifEmpty { free }
            if (candidates.isEmpty()) continue
            placeMinorCiv(civ, tileMap, candidates.random(rng), tileData, ruleset)
            return true
        }
        return false
    }

    private fun placeMinorCiv(civ: Civilization, tileMap: TileMap, tile: Tile, tileData: TileDataMap, ruleset: Ruleset) {
        tileMap.addStartingLocation(civ.civID, tile)
        tileData.placeImpact(MapRegions.ImpactType.MinorCiv,tile, 4)
        tileData.placeImpact(MapRegions.ImpactType.Luxury,  tile, 3)
        tileData.placeImpact(MapRegions.ImpactType.Strategic,tile, 0)
        tileData.placeImpact(MapRegions.ImpactType.Bonus,   tile, 3)

        // As the original does, the city states of the Civ V start normalizer are improved after all resources are placed (see MapRegions)
        if (!ruleset.modOptions.constants.startNormalizerLikeCivV)
            StartNormalizer.normalizeStart(tile, tileMap, tileData, ruleset, isMinorCiv = true)
    }

}
