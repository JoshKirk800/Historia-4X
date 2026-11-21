package com.unciv.logic.map.mapgenerator

import com.unciv.Constants
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.RiverGenerator.RiverCoordinate
import com.unciv.logic.map.mapgenerator.RiverGenerator.RiverCoordinate.BottomRightOrLeft
import com.unciv.logic.map.tile.Tile
import kotlin.random.Random

/**
 *  The rivers and lakes of MapGenerator.lua (AddRivers, DoRiver, GetRiverValueAtPlot, AddLakes), for rulesets that set `terrainGeneratorLikeCivV`.
 *
 *  Four passes over the land pick sources (hills and mountains, a random 1 in 8 of the inland land, hills and mountains and then any land of a
 *  landmass that has fewer river edges than a twelfth of its tiles + 1), each only where no fresh water is within 4 tiles
 *  and no sea within 2 (2 and 1 in the last two passes). From a corner of the source tile the river runs along tile edges, at each corner
 *  turning 60 degrees left or right towards the neighbourhood with the most water and the least rise, preferring to keep its first heading
 *  (the value of a way ahead is reduced by a quarter if it is), until it meets water or an edge that already has a river.
 *  The scripts' grid has pointy hexes, Unciv's flat ones: the corners and edges are Unciv's ([RiverCoordinate]); the six headings are east,
 *  west and the four diagonals.
 *
 *  A [script] of the layered family has its own rules: Four_Corners lets a plot with a neighbour off the map restart the sum of
 *  GetRiverValueAtPlot (the rivers run for the edge), InlandSea adds the distance to the centre (times 0 to 2) to the value, leaves the edge
 *  rows and columns without sources and gives every river a first heading towards the centre ([CivVScript.RiverCode]).
 *  Not ported: Four_Corners' DoRiver lets a river leave the map through the top row and the left column; here a river ends at the edge.
 */
class CivVRiverGenerator(private val map: TileMap, private val rng: Random, private val script: CivVScript = CivVScripts.standard) {

    companion object {
        private const val PLOTS_PER_RIVER_EDGE = 12
        private const val RIVER_SOURCE_MIN_RIVER_RANGE = 4
        private const val RIVER_SOURCE_MIN_SEAWATER_RANGE = 2
        private const val LAKE_PLOT_RAND = 160
        private const val MAX_RIVER_LENGTH = 400
    }

    /** The six headings, in the order of their angles: east 0, north-east 60 ... */
    private enum class Heading { East, NorthEast, NorthWest, West, SouthWest, SouthEast;
        val opposite get() = entries[(ordinal + 3) % 6]
        val left get() = entries[(ordinal + 1) % 6]
        val right get() = entries[(ordinal + 5) % 6]
    }

    private val tiles = map.values.sortedWith(compareBy({ it.getRow() }, { it.getColumn() }))

    // The cell of a tile on the script's grid (the size of the map in columns and rows), for the scripts that look at the edge or the centre
    private val minColumn = tiles.minOf { it.getColumn() }
    private val minRow = tiles.minOf { it.getRow() }
    private val gridWidth = tiles.maxOf { it.getColumn() } - minColumn + 1
    private val gridHeight = tiles.maxOf { it.getRow() } - minRow + 1
    private fun cellX(tile: Tile) = tile.getColumn() - minColumn
    private fun cellY(tile: Tile) = tile.getRow() - minRow
    private val edgesOfLandmass = HashMap<Int, Int>()
    private val riverOfVertex = HashMap<RiverCoordinate, Int>()
    private var nextRiverId = 0

    /** The tiles that have fresh water: lakes, and what is next to a lake, a river or an oasis */
    private val fresh = HashSet<Tile>()

    fun spawnRivers() {
        // Lakes and what is next to them (no river exists yet)
        for (tile in tiles) if (tile.baseTerrain == "Lakes" || tile.neighbors.any { it.baseTerrain == "Lakes" }) fresh.add(tile)
        for (pass in 1..4) {
            val sourceRange = if (pass <= 2) RIVER_SOURCE_MIN_RIVER_RANGE else RIVER_SOURCE_MIN_RIVER_RANGE / 2
            val seaRange = if (pass <= 2) RIVER_SOURCE_MIN_SEAWATER_RANGE else RIVER_SOURCE_MIN_SEAWATER_RANGE / 2
            for (tile in tiles) {
                if (tile.isWater) continue
                if (script.rivers == CivVScript.RiverCode.InlandSea && isTooCloseToTheEdge(tile)) continue
                val wanted = when (pass) {
                    1 -> isHillsOrMountain(tile)
                    2 -> !isCoastalLand(tile) && rng.nextInt(8) == 0
                    3 -> isHillsOrMountain(tile) && riverEdgesBelowBudget(tile)
                    else -> riverEdgesBelowBudget(tile)
                }
                if (!wanted) continue
                if (hasFreshWaterWithin(tile, sourceRange) || hasWaterWithin(tile, seaRange)) continue
                val corner = inlandCorner(tile) ?: continue
                doRiver(corner, if (script.rivers == CivVScript.RiverCode.InlandSea) headingToTheCentre(tile) else null)
            }
        }
    }

    /** InlandSea's AddRivers ignores the plots in the first column, the last two, the first two rows and the last one */
    private fun isTooCloseToTheEdge(tile: Tile): Boolean {
        val x = cellX(tile)
        val y = cellY(tile)
        return x < 1 || x >= gridWidth - 2 || y < 2 || y >= gridHeight - 1
    }

    /**
     *  InlandSea's `orig_direction`: a river that starts in the south half runs north (in the south-west third north-east, in the south-east third
     *  north-west), in the north half south (south-east in the north-west third, south-west in the north-east third). The scripts' pointy grid has
     *  rivers running north and south, Unciv's flat grid has none: the middle third takes the diagonal that leans to the middle column.
     */
    private fun headingToTheCentre(tile: Tile): Heading {
        val x = cellX(tile)
        val south = cellY(tile) < gridHeight / 2.0
        val west = x < gridWidth / 3.0
        val east = x > gridWidth * 0.66
        val towardsEast = if (west) true else if (east) false else x < gridWidth / 2.0
        return if (south) { if (towardsEast) Heading.NorthEast else Heading.NorthWest }
        else { if (towardsEast) Heading.SouthEast else Heading.SouthWest }
    }

    private fun isHillsOrMountain(tile: Tile) = Constants.hill in tile.terrainFeatures || tile.baseTerrain == Constants.mountain

    private fun isCoastalLand(tile: Tile) = tile.neighbors.any { it.isWater }

    private fun riverEdgesBelowBudget(tile: Tile): Boolean {
        val landmass = tile.getContinent()
        val size = map.continentSizes[landmass] ?: return false
        return (edgesOfLandmass[landmass] ?: 0) < size.toDouble() / PLOTS_PER_RIVER_EDGE + 1
    }

    private fun hasFreshWaterWithin(tile: Tile, range: Int) =
        tile in fresh || tile.getTilesInDistance(range).any { it in fresh }

    private fun hasWaterWithin(tile: Tile, range: Int) = tile.getTilesInDistance(range).any { it.isWater }

    /** CvPlot::getInlandCorner: a corner of [tile] where no tile around is water, taken at random */
    private fun inlandCorner(tile: Tile): RiverCoordinate? {
        val anchors = listOf(tile) + tile.neighbors.toList()
        val corners = anchors.flatMap { anchor ->
            BottomRightOrLeft.entries.map { RiverCoordinate(map, anchor.position, it) }
        }.filter { tile in it.getAdjacentTiles().toList() }.distinct()
        return corners.shuffled(rng).firstOrNull { corner ->
            val around = corner.getAdjacentTiles().toList()
            around.size == 3 && around.none { it.isWater }
        }
    }

    /** The heading of the step from [from] to [to], a neighbouring corner (by which tile [to] is anchored on, the map may wrap) */
    private fun heading(from: RiverCoordinate, to: RiverCoordinate): Heading {
        val p = from.position
        if (to.position == p) return if (from.bottomRightOrLeft == BottomRightOrLeft.BottomLeft) Heading.East else Heading.West
        return if (from.bottomRightOrLeft == BottomRightOrLeft.BottomLeft) {
            if (map.getIfTileExistsOrNull(p.x + 1, p.y)?.position == to.position) Heading.NorthWest else Heading.SouthWest
        } else {
            if (map.getIfTileExistsOrNull(p.x, p.y + 1)?.position == to.position) Heading.NorthEast else Heading.SouthEast
        }
    }

    /** GetRiverValueAtPlot: low is wet and flat. Water is 1, flat land 2, hills 3, mountains 4 (x 20 for the tile, x 1 for each of its 6 neighbours, 40 off the map) and a random 0-9 */
    private fun valueAt(tile: Tile): Double {
        fun rise(t: Tile) = when {
            t.isWater -> 1
            t.baseTerrain == Constants.mountain -> 4
            Constants.hill in t.terrainFeatures -> 3
            else -> 2
        }
        var sum = rise(tile) * 20
        when (script.rivers) {
            CivVScript.RiverCode.FourCorners -> {
                // `sum = 0` for every direction that leaves the map: only the neighbours listed after the last such direction count
                val clockPositions = listOf(2, 4, 6, 8, 10, 12)
                for (clock in clockPositions) {
                    val neighbor = map.getClockPositionNeighborTile(tile, clock)
                    if (neighbor == null) sum = 0 else sum += rise(neighbor)
                }
            }
            else -> {
                val neighbors = tile.neighbors.toList()
                sum += neighbors.sumOf { rise(it) }
                sum += (6 - neighbors.size) * 40
            }
        }
        var value = sum.toDouble()
        if (script.rivers == CivVScript.RiverCode.InlandSea)
            value += CivVLayeredScripts.distanceToCentre(cellX(tile), cellY(tile), gridWidth, gridHeight) * rng.nextInt(3)
        return value + rng.nextInt(10)
    }

    /** DoRiver, from a corner; [firstHeading] is the `originalFlowDirection` a script gives (InlandSea), otherwise the first step sets it */
    private fun doRiver(start: RiverCoordinate, firstHeading: Heading? = null) {
        val riverId = nextRiverId++
        val other = riverOfVertex[start]
        if (other != null && other != riverId) return // cannot branch off another river
        var vertex = start
        var heading: Heading? = null
        var original: Heading? = firstHeading

        repeat(MAX_RIVER_LENGTH) {
            // The ways on: the other two neighbouring corners (all three at the start), but not back and not along a river edge
            val options = vertex.getAdjacentPositions().map { heading(vertex, it) to it }.toList()
                .filter { (h, _) -> heading == null || h == heading!!.left || h == heading!!.right }
            fun choose(avoidBack: Boolean): Pair<Heading, RiverCoordinate>? {
                var best: Pair<Heading, RiverCoordinate>? = null
                var bestValue = Double.MAX_VALUE
                for ((h, next) in options) {
                    if (avoidBack && original != null && h.opposite == original) continue
                    val ahead = next.getAdjacentTiles().firstOrNull { it !in vertex.getAdjacentTiles().toList() } ?: continue
                    var value = valueAt(ahead)
                    if (h == original) value = value * 3 / 4
                    if (value < bestValue) { bestValue = value; best = h to next }
                }
                return best
            }
            val (nextHeading, next) = choose(true) ?: choose(false) ?: return
            if (original == null) original = nextHeading

            // Put the edge: both tiles beside it must be land and have no river between them
            val here = vertex.getAdjacentTiles().toList()
            val shared = next.getAdjacentTiles().filter { it in here }.toList()
            if (shared.size < 2 || shared.any { it.isWater } || shared[0].isConnectedByRiver(shared[1])) return
            vertex.paintTo(next, null)
            shared.forEach { it.forgetAdjacentToRiver() }
            fresh.addAll(shared)
            val landmass = shared[0].getContinent()
            edgesOfLandmass[landmass] = (edgesOfLandmass[landmass] ?: 0) + 1
            riverOfVertex[vertex] = riverId
            riverOfVertex[next] = riverId

            vertex = next
            heading = nextHeading
            if (vertex.getAdjacentTiles().any { it.isWater }) return // reached the sea or a lake
        }
    }

    /** AddLakes: 1 in 160 of the inland land tiles without a river becomes a lake, in the order of the tiles (a lake makes its neighbours coastal) */
    fun addLakes() {
        if ("Lakes" !in map.ruleset!!.terrains) return
        for (tile in tiles) {
            if (tile.isWater || isCoastalLand(tile) || tile.isAdjacentToRiver()) continue
            if (rng.nextInt(LAKE_PLOT_RAND) != 0) continue
            tile.removeTerrainFeatures()
            tile.baseTerrain = "Lakes"
            tile.setTerrainTransients()
        }
    }
}
