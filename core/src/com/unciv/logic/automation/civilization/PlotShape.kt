package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile

/**
 *  The shape of the ground around a plot, which Vox Populi's CvCity::GetIndividualPlotScore ([UseGoldAutomation.individualPlotScore]) values:
 *  CvPlot::IsChokePoint and CvPlot::IsWaterAreaSeparator. A tile is impassable as the tactical map takes it (impassable terrain or a
 *  mountain); the original's per-team exceptions (a team that crosses mountains) are not modelled.
 */
object PlotShape {

    private fun isImpassable(tile: Tile) = tile.isImpassible() || tile.baseTerrain == Constants.mountain

    /** CvPlot::countPassableNeighbors(DOMAIN_LAND) */
    private fun passableLandNeighbors(tile: Tile): List<Tile> = tile.neighbors.filter { !it.isWater && !isImpassable(it) }.toList()

    private fun isAdjacent(a: Tile, b: Tile) = a.neighbors.contains(b)

    /**
     *  CvPlot::IsChokePoint: a passable plot with two to four passable land neighbours, at least two of which have more than two passable land
     *  neighbours themselves (no dead ends) and are not too much next to each other: two of them not adjacent, three with at most one adjacent pair,
     *  four with at most two adjacent pairs.
     */
    fun isChokePoint(tile: Tile): Boolean {
        if (isImpassable(tile)) return false
        val passable = passableLandNeighbors(tile)
        if (passable.size < 2 || passable.size > 4) return false
        val open = passable.filter { passableLandNeighbors(it).size > 2 }
        var adjacentPairs = 0
        for (first in open.indices) for (second in first + 1 until open.size)
            if (isAdjacent(open[first], open[second])) adjacentPairs++
        return when (open.size) {
            2 -> adjacentPairs == 0
            3 -> adjacentPairs < 2
            4 -> adjacentPairs < 3
            else -> false
        }
    }

    /** The water areas of a map (CvArea of the water plots): connected bodies of water, numbered by [Tile.zeroBasedIndex]; computed once per instance */
    class WaterAreas(tileMap: TileMap) {
        private val areas = IntArray(tileMap.tileList.size)

        init {
            var next = 0
            val stack = ArrayDeque<Tile>()
            for (start in tileMap.tileList) {
                if (!start.isWater || areas[start.zeroBasedIndex] != 0) continue
                next++
                areas[start.zeroBasedIndex] = next
                stack.add(start)
                while (stack.isNotEmpty()) {
                    for (neighbor in stack.removeLast().neighbors) {
                        if (!neighbor.isWater || areas[neighbor.zeroBasedIndex] != 0) continue
                        areas[neighbor.zeroBasedIndex] = next
                        stack.add(neighbor)
                    }
                }
            }
        }

        fun areaOf(tile: Tile) = areas[tile.zeroBasedIndex]
    }

    /** CvPlot::IsWaterAreaSeparator: a passable land plot with water of two different water areas next to it */
    fun isWaterAreaSeparator(tile: Tile, waterAreas: WaterAreas): Boolean {
        if (tile.isWater || isImpassable(tile)) return false
        var first = 0
        for (neighbor in tile.neighbors) {
            if (!neighbor.isWater) continue
            val area = waterAreas.areaOf(neighbor)
            if (first == 0) first = area
            else if (area != first) return true
        }
        return false
    }
}
