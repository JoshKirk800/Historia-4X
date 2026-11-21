package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.mapregions.getTileFertility
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset

/**
 *  Whether the civilizations' starts on a generated map are alike enough to play: land, room to settle, fertility.
 *
 *  Not a rule of the original game (its map scripts only place starts by region and normalize them); added because maps from the
 *  Unciv shapes, in particular small ones, sometimes shut a civilization into a few tiles it shares with a city-state, where
 *  the AI cannot expand (a 23-tile home island with no legal site was found in a Domination study).
 *  [MapGenerator.generateMap] regenerates a failing map with another derived seed, see [MAX_ATTEMPTS].
 *
 *  For each civilization (not city-states, but their starts count as neighbours):
 *  - land per player: the land tiles of its landmass divided by the starts (civilizations and city-states) on it, at least [MIN_LAND_PER_PLAYER],
 *  - sites: at least [MIN_SITES] places for further cities within [SITE_RADIUS] tiles on its landmass, packed greedily at more than
 *    `minimalCityDistance` from every start and from each other, each on land that is not impassable, not a wonder, with fertility of
 *    at least 1 and 6 land tiles within 2,
 *  - fertility: the sum of [Tile.getTileFertility] within [FERTILITY_RADIUS] of the start, between [FERTILITY_LOW] and [FERTILITY_HIGH]
 *    times the median civilization's.
 */
class StartFairness(private val map: TileMap, private val ruleset: Ruleset) {

    class Verdict(val failures: List<String>, /** How far the starts miss their limits, in shares of the limit: to pick the best of several maps */ val deficit: Double) {
        val passes get() = failures.isEmpty()
    }

    /** What the check measures at the start of one civilization. [sites] is counted up to the limit given to [measure]. */
    class StartMeasure(
        val nation: String, val tile: Tile,
        /** The land tiles of the start's landmass */ val landmassTiles: Int,
        /** The starts, civilizations and city-states, on that landmass */ val startsOnLandmass: Int,
        val sites: Int,
        /** The fertility within [FERTILITY_RADIUS], and its share of the median civilization's */ val fertility: Double, val fertilityRatio: Double,
    ) {
        val landPerPlayer get() = landmassTiles.toDouble() / startsOnLandmass
    }

    /** The starts on the map: the tile of each and the nation it is for */
    private fun starts() = map.startingLocations.mapNotNull { location -> map[location.position]?.let { Pair(it, location.nation) } }

    /** The measures of every civilization (not city-states), empty when there are fewer than two. Sites are counted up to [maxSites]. */
    fun measure(maxSites: Int = MIN_SITES): List<StartMeasure> {
        val starts = starts()
        val majors = starts.filter { ruleset.nations[it.second]?.isMajorCiv == true }
        if (majors.size < 2) return emptyList()
        val startTiles = starts.map { it.first }
        val playersOn = startTiles.groupingBy { it.getContinent() }.eachCount()
        val fertilities = majors.map { (tile, _) -> tile.getTilesInDistance(FERTILITY_RADIUS).sumOf { it.getTileFertility(true) } }
        val median = fertilities.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
        return majors.mapIndexed { index, (tile, nation) ->
            val continent = tile.getContinent()
            StartMeasure(nation, tile, map.continentSizes[continent] ?: 0, playersOn[continent] ?: 1, countSites(tile, startTiles, maxSites),
                fertilities[index].toDouble(), if (median > 0) fertilities[index] / median else 1.0)
        }
    }

    fun evaluate(): Verdict {
        val failures = ArrayList<String>()
        var deficit = 0.0
        for (measure in measure()) {
            val nation = measure.nation
            val landPerPlayer = measure.landPerPlayer
            if (landPerPlayer < MIN_LAND_PER_PLAYER) {
                failures += "$nation: ${"%.1f".format(landPerPlayer)} land tiles per player on its landmass, under ${MIN_LAND_PER_PLAYER.toInt()}"
                deficit += (MIN_LAND_PER_PLAYER - landPerPlayer) / MIN_LAND_PER_PLAYER
            }
            if (measure.sites < MIN_SITES) {
                failures += "$nation: ${measure.sites} city sites within $SITE_RADIUS tiles, under $MIN_SITES"
                deficit += (MIN_SITES - measure.sites).toDouble() / MIN_SITES
            }
            val ratio = measure.fertilityRatio
            if (ratio < FERTILITY_LOW || ratio > FERTILITY_HIGH) {
                failures += "$nation: fertility ${"%.2f".format(ratio)} times the median, outside $FERTILITY_LOW-$FERTILITY_HIGH"
                deficit += if (ratio < FERTILITY_LOW) FERTILITY_LOW - ratio else ratio - FERTILITY_HIGH
            }
        }
        return Verdict(failures, deficit)
    }

    /** Sites for more cities around [start], at most [maxSites] counted */
    private fun countSites(start: Tile, startTiles: List<Tile>, maxSites: Int): Int {
        val minDistance = ruleset.modOptions.constants.minimalCityDistance
        val continent = start.getContinent()
        val blockers = ArrayList(startTiles)
        val candidates = start.getTilesInDistance(SITE_RADIUS)
            .filter { it.getContinent() == continent && isViableSite(it) }
            .sortedWith(compareBy({ start.aerialDistanceTo(it) }, { it.position.x }, { it.position.y }))
        var sites = 0
        for (candidate in candidates) {
            if (blockers.any { it.aerialDistanceTo(candidate) <= minDistance }) continue
            blockers += candidate
            if (++sites >= maxSites) break
        }
        return sites
    }

    private fun isViableSite(tile: Tile) =
        tile.isLand && !tile.isImpassible() && !tile.isNaturalWonder()
            && tile.getTileFertility(false) >= 1
            && tile.getTilesInDistance(2).count { it.isLand } >= 6

    companion object {
        /** The whole map, first try included */
        const val MAX_ATTEMPTS = 8
        /** The seed of attempt n is the map's seed plus n times this (a prime, so attempts of neighbouring seeds do not coincide) */
        const val SEED_STEP = 10007L
        const val MIN_LAND_PER_PLAYER = 20.0
        const val MIN_SITES = 3
        const val SITE_RADIUS = 10
        const val FERTILITY_RADIUS = 3
        const val FERTILITY_LOW = 0.6
        const val FERTILITY_HIGH = 1.6
    }
}
