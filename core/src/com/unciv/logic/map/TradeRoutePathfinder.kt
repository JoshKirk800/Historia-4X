package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import java.util.PriorityQueue

/**
 * The path a trade route takes, found the way the original does (CvAStar.cpp: TradeRouteLandPathCost, TradeRouteWaterPathCost,
 * TradeRouteLandValid, TradeRouteWaterValid, TradeRouteHeuristic): an A* search on the tiles with integer costs, where a step costs 100
 * (50 along a road), and the heuristic is the distance times 100.
 * It does not depend on a unit, and borders, units and fog do not stop it.
 */
object TradeRoutePathfinder {
    private const val STEP = 100
    private const val ROAD_STEP = 50
    private const val PENALTY = 1000
    private const val TILE_BITS = 22
    private const val ORDER_BITS = 22
    private const val TILE_MASK = (1L shl TILE_BITS) - 1
    /** The original allows a route if the cost is not more than range * 100 + 99, which makes up for the + 1 or + 2 that most steps cost */
    private const val RANGE_SLACK = 99

    /** Whether trade ships can start from or reach [tile], a coastal city */
    @Readonly
    fun isOnWater(tile: Tile) = tile.neighbors.any { it.isWater }

    /**
     * @return the tiles from [origin] to [destination], both included, if their cost does not exceed [range]; `null` if there is no such path
     */
    @Readonly
    fun findPath(civ: Civilization, origin: Tile, destination: Tile, isSea: Boolean, range: Int): List<Tile>? {
        if (origin == destination) return null
        if (isSea && (!isOnWater(origin) || !isOnWater(destination))) return null

        val budget = range * STEP + RANGE_SLACK
        val allWaterPassage = civ.tech.embarkedUnitsCanEnterOcean
        val woodsAreRoads = civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)
        val tiles = origin.tileMap.tileList

        // Not the shortest one in every case, as in the original: the heuristic overestimates along roads.
        // The AI asks for tens of thousands of paths a turn, so the search works on arrays indexed by the tile's number (cost and where it
        // came from, both stored plus one so that 0 means "not reached") and on a heap of packed longs, not on maps and node objects.
        @LocalState val cost = IntArray(tiles.size)
        @LocalState val cameFrom = IntArray(tiles.size)
        @LocalState var heap = LongArray(256)
        var heapSize = 0
        val destinationIndex = destination.zeroBasedIndex
        val originIndex = origin.zeroBasedIndex
        var counter = 0L
        cost[originIndex] = 1

        // The queue leaves its entries in the order of (priority, the order they were queued in); the tile is in the low bits of the key
        fun push(priority: Long, tileIndex: Int) {
            check(counter < (1L shl ORDER_BITS)) { "a trade route search queued more than ${1L shl ORDER_BITS} tiles" }
            val key = (priority shl (TILE_BITS + ORDER_BITS)) or (counter++ shl TILE_BITS) or tileIndex.toLong()
            if (heapSize == heap.size) heap = heap.copyOf(heapSize * 2)
            var i = heapSize++
            while (i > 0) {
                val parent = (i - 1) ushr 1
                if (heap[parent] <= key) break
                heap[i] = heap[parent]
                i = parent
            }
            heap[i] = key
        }

        fun pop(): Int {
            val first = heap[0]
            val last = heap[--heapSize]
            var i = 0
            while (true) {
                var child = 2 * i + 1
                if (child >= heapSize) break
                if (child + 1 < heapSize && heap[child + 1] < heap[child]) child++
                if (heap[child] >= last) break
                heap[i] = heap[child]
                i = child
            }
            if (heapSize > 0) heap[i] = last
            return (first and TILE_MASK).toInt()
        }

        push(STEP.toLong() * origin.aerialDistanceTo(destination), originIndex)
        while (heapSize > 0) {
            val currentIndex = pop()
            if (currentIndex == destinationIndex) break
            val current = tiles[currentIndex]
            val known = cost[currentIndex] - 1
            for (next in current.neighbors) {
                val stepCost = if (isSea) getWaterStepCost(civ, current, next, allWaterPassage)
                    else getLandStepCost(current, next, woodsAreRoads)
                if (stepCost < 0) continue
                val newCost = known + stepCost
                if (newCost > budget) continue // a step never costs less than 50, so the way on cannot get cheaper
                val nextIndex = next.zeroBasedIndex
                if (cost[nextIndex] != 0 && newCost >= cost[nextIndex] - 1) continue
                cost[nextIndex] = newCost + 1
                cameFrom[nextIndex] = currentIndex + 1
                push(newCost + STEP.toLong() * next.aerialDistanceTo(destination), nextIndex)
            }
        }

        if (cameFrom[destinationIndex] == 0) return null
        val path = ArrayList<Tile>()
        var index = destinationIndex
        while (true) {
            path.add(tiles[index])
            if (cameFrom[index] == 0) break
            index = cameFrom[index] - 1
        }
        path.reverse()
        return path
    }

    /**
     * The tiles a path of cost within [range] from [origin] can end on: those [findPath] finds a path to (an A* that is not stopped by its
     * estimate reaches every tile that a path of cost within the budget leads to), found in one pass, so that the AI need not search a path to
     * every city only to learn that there is none, or to learn a path it will never look at. Empty when [isSea] and [origin] is not on water.
     */
    @Readonly
    fun reachableTiles(civ: Civilization, origin: Tile, isSea: Boolean, range: Int): Set<Tile> {
        val reached = HashSet<Tile>()
        if (isSea && !isOnWater(origin)) return reached
        val budget = range * STEP + RANGE_SLACK
        val allWaterPassage = civ.tech.embarkedUnitsCanEnterOcean
        val woodsAreRoads = civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)
        val cost = HashMap<Tile, Int>()
        cost[origin] = 0
        // (cost, tile number) in one long, the cheapest first
        val queue = PriorityQueue<Long>()
        queue.add(origin.zeroBasedIndex.toLong())
        val tiles = origin.tileMap.tileList
        while (queue.isNotEmpty()) {
            val entry = queue.poll()
            val current = tiles[(entry and TILE_MASK).toInt()]
            val known = (entry shr TILE_BITS).toInt()
            if (known > cost[current]!!) continue
            reached.add(current)
            for (next in current.neighbors) {
                val stepCost = if (isSea) getWaterStepCost(civ, current, next, allWaterPassage)
                    else getLandStepCost(current, next, woodsAreRoads)
                if (stepCost < 0) continue
                val newCost = known + stepCost
                if (newCost > budget) continue
                if (newCost >= (cost[next] ?: Int.MAX_VALUE)) continue
                cost[next] = newCost
                queue.add((newCost.toLong() shl TILE_BITS) or next.zeroBasedIndex.toLong())
            }
        }
        return reached
    }

    /**
     * The cost of walking [path] (SPath::iNormalizedDistanceRaw of the original, which CvGameTrade::GetValidTradeRoutePathLength returns): the sum of the costs of its steps as
     * [findPath] counts them, 100 for a step without terrain costs and 50 along a road. A step that is not allowed (the path was found when it was) counts as a plain step.
     */
    @Readonly
    fun pathCost(civ: Civilization, path: List<Tile>, isSea: Boolean): Int {
        val allWaterPassage = civ.tech.embarkedUnitsCanEnterOcean
        val woodsAreRoads = civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)
        var total = 0
        for (index in 1 until path.size) {
            val stepCost = if (isSea) getWaterStepCost(civ, path[index - 1], path[index], allWaterPassage)
                else getLandStepCost(path[index - 1], path[index], woodsAreRoads)
            total += if (stepCost < 0) STEP else stepCost
        }
        return total
    }

    @Readonly
    private fun hasRoad(tile: Tile) = tile.isCityCenter() || tile.roadStatus != RoadStatus.None

    /** @return the cost of a step on land, `-1` if it is not allowed */
    @Readonly
    private fun getLandStepCost(from: Tile, to: Tile, woodsAreRoads: Boolean): Int {
        if (!to.isLand || to.isImpassible() || to.baseTerrain == Constants.mountain)
            return -1

        if (hasRoad(from) && hasRoad(to)) return ROAD_STEP
        val isWoods = to.terrainFeatures.contains(Constants.forest) || to.terrainFeatures.contains(Constants.jungle)
        if (woodsAreRoads && isWoods) return ROAD_STEP

        var cost = STEP
        if (to.isHill() || isWoods || to.terrainFeatures.contains(Constants.ice)) cost++
        if (!to.terrainFeatures.contains("Oasis")) cost++
        return cost
    }

    /** @return the cost of a step on water, `-1` if it is not allowed. Any city can be entered */
    @Readonly
    private fun getWaterStepCost(civ: Civilization, from: Tile, to: Tile, allWaterPassage: Boolean): Int {
        if (to.isCityCenter()) return STEP
        if (!to.isWater || to.isImpassible()) return -1
        val terrain = to.getBaseTerrain()
        val isShallow = terrain.isCoast || terrain.isFreshwater
        if (!isShallow && !allWaterPassage) return -1

        var cost = STEP
        if (!(isNextToLand(from) && isNextToLand(to))) cost++
        val owner = to.getOwner()
        if (owner != null && owner.isAtWarWith(civ)) cost += PENALTY
        return cost
    }

    @Readonly
    private fun isNextToLand(tile: Tile) = tile.neighbors.any { it.isLand }
}
