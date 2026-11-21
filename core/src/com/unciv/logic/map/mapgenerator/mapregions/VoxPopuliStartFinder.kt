package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 * How Vox Populi's `AssignStartingPlots:FindStart` chooses the start of a region, `(2) Vox Populi/Core Files/Overrides/AssignStartingPlots.lua`.
 * It differs from [RegionStartFinder]'s (Brave New World's) in:
 *  - one candidate list for the center area and one for the middle area, and the best scored eligible tile of a list wins; there is no
 *    preference for river tiles over coastal tiles over the rest (`IterateThroughCandidatePlotList`);
 *  - the score: +80 next to the sea, else +40 on a river or next to fresh water (`EvaluateCandidatePlot`; Brave New World: +40 next to the sea),
 *    its own tables per ring, and every river tile of the three rings counts for the score;
 *  - the minimums: production 1, 1 and 3 in the rings (Brave New World: 0, 0, 2) and no cap on the junk;
 *  - the production counted for the score is capped at twice the food, and the cap carries over to the next ring;
 *  - when nothing is eligible in the center and middle, the eligible tile of the outer area closest to a corner of the region wins, not the one
 *    closest to its center. If nothing is eligible anywhere, the best scored tile of each area is kept and the best of those wins.
 *
 * What is kept: the ripples around a start (`PlaceImpactAndRipples`: the 8 rings of [MapRegions.closeStartPenaltyForRing]); a tile with a ripple is
 * never eligible and loses `score x ripple / 100` (`EvaluateCandidatePlot`). The classes of a tile (food, production, good, junk) are the
 * ruleset's `Has quality` uniques, not `MeasureSinglePlot`'s per region type.
 *
 * The Lua walks the region row by row and breaks ties by the first tile (and shuffles the outer candidates); the tiles here are sorted row by row
 * too and the first of the shuffled ties is the first in that order, so a seed always gives the same start.
 */
object VoxPopuliStartFinder {
    /** `ChooseLocations` defaults of the Lua: minimum food, production and good tiles in the first, second and third ring (running totals) */
    private val minimumFood = listOf(1, 4, 4)
    private val minimumProd = listOf(1, 1, 3)
    private val minimumGood = listOf(3, 6, 8)

    /** `WEIGHTED_FOOD_SCORE` and `WEIGHTED_PROD_SCORE`: the score for a total of 0, 1, ... tiles; each tile beyond the table adds 1 */
    private val foodScores = listOf(listOf(0, 8, 14, 18, 20), listOf(0, 2, 5, 10, 20, 25, 28, 30, 32, 34), listOf(0))
    private val prodScores = listOf(listOf(0, 10, 16, 20, 22, 24), listOf(0, 10, 20, 25, 30, 35, 39, 42, 44), listOf(0))
    private val goodMultiplier = listOf(2, 2, 1)
    private val junkMultiplier = listOf(-3, -3, -2)

    private const val COASTAL_SCORE = 80
    private const val FRESH_WATER_SCORE = 40

    /** The tiles of one ring around a candidate, as `EvaluateCandidatePlot` measures them */
    class RingCounts(val food: Int, val prod: Int, val good: Int, val junk: Int, val river: Int, val tilesOnMap: Int)

    /** A candidate start with its score; [meetsMinimums] false means a fallback (too few good tiles, or a start closer than 9) */
    class Scored<T>(val item: T, val score: Int, val meetsMinimums: Boolean)

    /** The score of a [total] of tiles in a table of scores: the table's entry, and 1 more for each tile beyond its last entry */
    @Readonly
    fun tableScore(table: List<Int>, total: Int): Int = if (total < table.size) table[total] else table.last() + total - (table.size - 1)

    /**
     * `EvaluateCandidatePlot`: [rings] are the three rings around the candidate, [baseScore] the coast or fresh water score.
     * The totals of food, production, good, junk and rivers run over the rings; [closeStartPenalty] is the candidate's ripple value (0: none).
     */
    @Readonly
    fun evaluate(rings: List<RingCounts>, baseScore: Int, closeStartPenalty: Int): Scored<Unit> {
        var food = 0
        var prod = 0
        var good = 0
        var junk = 0
        var river = 0
        var meetsMinimums = true
        var score = baseScore
        for ((index, ring) in rings.withIndex()) {
            food += ring.food
            prod += ring.prod
            good += ring.good
            junk += ring.junk + (6 * (index + 1) - ring.tilesOnMap) // off the map counts as junk
            river += ring.river
            if (food < minimumFood[index] || prod < minimumProd[index] || good < minimumGood[index]) meetsMinimums = false
            // Without food the production cannot be used; the cap stays for the rings after
            prod = minOf(prod, food * 2)
            score += tableScore(foodScores[index], food) + tableScore(prodScores[index], prod) +
                good * goodMultiplier[index] + junk * junkMultiplier[index] + river
        }
        if (closeStartPenalty > 0) {
            meetsMinimums = false
            score -= Math.floorDiv(score * closeStartPenalty, 100)
        }
        return Scored(Unit, score, meetsMinimums)
    }

    /** The first candidate with the highest score above [floor] among those that [meetsMinimums] says yes or no to (the Lua takes the first of equal scores) */
    @Readonly
    fun <T> best(candidates: List<Scored<T>>, meetsMinimums: Boolean, floor: Int): Scored<T>? {
        var best: Scored<T>? = null
        var bestScore = floor
        for (candidate in candidates) {
            if (candidate.meetsMinimums != meetsMinimums || candidate.score <= bestScore) continue
            best = candidate
            bestScore = candidate.score
        }
        return best
    }

    /** Sorts row by row like the Lua's loops (even-q rows of two hex coordinates, then the column) */
    private fun inRowOrder(tiles: Sequence<Tile>): List<Tile> =
        tiles.sortedWith(compareBy({ it.position.x + it.position.y }, { HexMath.getColumn(it.position) })).toList()

    /** Vox Populi's `FindStart` for [region]: calls `RegionStartFinder.setRegionStart` with the position */
    internal fun findStart(region: Region, tileData: TileDataMap) {
        val tileMap = region.tileMap
        val inRegion = inRowOrder(tileMap.getTilesInRectangle(region.rect)).filter { tile ->
            tile.isLand && !tile.isImpassible() && !tileData[tile]!!.isTwoFromCoast && (region.continentID == -1 || region.continentID == tile.getContinent())
        }
        val centerRect = RegionStartFinder.getCentralRectangle(region.rect, RegionStartFinder.CENTER_BIAS_PERCENT)
        val middleRect = RegionStartFinder.getCentralRectangle(region.rect, RegionStartFinder.MIDDLE_BIAS_PERCENT)
        val centerTiles = tileMap.getTilesInRectangle(centerRect).toSet()
        val middleTiles = tileMap.getTilesInRectangle(middleRect).toSet()
        val center = inRegion.filter { it in centerTiles }
        val middle = inRegion.filter { it !in centerTiles && it in middleTiles }
        val outer = inRegion.filter { it !in centerTiles && it !in middleTiles }

        val fallbacks = ArrayList<Scored<Tile>>()
        for (area in listOf(center, middle)) {
            val scored = area.map { scoreCandidate(it, tileData) }
            val eligible = best(scored, true, -5000)
            if (eligible != null) return RegionStartFinder.setRegionStart(region, eligible.item.position, tileData)
            best(scored, false, -5000)?.let { fallbacks += it }
        }

        if (outer.isNotEmpty()) {
            val scored = outer.map { scoreCandidate(it, tileData) }
            val eligible = scored.filter { it.meetsMinimums }
            if (eligible.isNotEmpty()) {
                val closest = eligible.minByOrNull { distanceToNearestCorner(region, it.item) }!!
                return RegionStartFinder.setRegionStart(region, closest.item.position, tileData)
            }
            best(scored, false, -50)?.let { fallbacks += it }
        }

        val fallback = best(fallbacks, false, 0)
        if (fallback != null) return RegionStartFinder.setRegionStart(region, fallback.item.position, tileData)
        // Reaching this means that the region has no tile to start on: the Lua makes the corner a grass tile
        RegionStartFinder.setRegionStart(region, RegionStartFinder.panicPosition(region, tileData), tileData)
    }

    /** The distance to the nearest of the region's four corners; the Lua's east and north edges lie one column and one row beyond the region */
    private fun distanceToNearestCorner(region: Region, tile: Tile): Int {
        val west = region.rect.x.toInt()
        val south = region.rect.y.toInt()
        val east = west + region.rect.width.toInt()
        val north = south + region.rect.height.toInt()
        return listOf(west to south, west to north, east to south, east to north).minOf { (column, row) ->
            HexMath.getDistance(tile.position, HexMath.getTileCoordsFromColumnRow(column, row))
        }
    }

    /** `EvaluateCandidatePlot` of [tile] with its class totals from the three rings around it */
    private fun scoreCandidate(tile: Tile, tileData: TileDataMap): Scored<Tile> {
        val rings = (1..3).map { ring ->
            var food = 0
            var prod = 0
            var good = 0
            var junk = 0
            var river = 0
            var tilesOnMap = 0
            tile.forEachTileAtDistance(ring) { ringTile ->
                tilesOnMap++
                val data = tileData[ringTile]!!
                if (data.isJunk) junk++
                else {
                    if (data.isFood) food++
                    if (data.isProd) prod++
                    if (data.isGood) good++
                }
                if (ringTile.isAdjacentToRiver()) river++
            }
            RingCounts(food, prod, good, junk, river, tilesOnMap)
        }
        val baseScore = when {
            tile.isAdjacentToCoast() -> COASTAL_SCORE
            tile.isAdjacentToRiver() || tile.isAdjacentTo(com.unciv.Constants.freshWater) -> FRESH_WATER_SCORE
            else -> 0
        }
        val result = evaluate(rings, baseScore, tileData[tile]!!.closeStartPenalty)
        return Scored(tile, result.score, result.meetsMinimums)
    }
}
