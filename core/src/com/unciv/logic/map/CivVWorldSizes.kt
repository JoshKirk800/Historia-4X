package com.unciv.logic.map

import com.unciv.models.ruleset.ai.WorldSizeDef
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 *  The world sizes of Civ V for maps whose size follows the number of players ("Auto").
 *
 *  The rows are the ruleset's `worldSizes` (CIV5Worlds.xml: `DefaultPlayers`, `DefaultMinorCivs`, `GridWidth`, `GridHeight` of Duel, Tiny,
 *  Small, Standard, Large and Huge: 2/4 on 40x24, 4/8 on 56x36, 6/12 on 66x42, 8/16 on 80x52, 10/20 on 104x64, 12/24 on 128x80).
 *  The default has two city-states for each civilization, so a row is keyed by its civilizations.
 *
 *  A game with another mix is placed between the rows by the number of civilizations it is worth ([equivalentMajors]).
 *  Dimensions between two rows are interpolated linearly; below the first and above the last row the sides scale with the square root
 *  of the count, which keeps the tiles per civilization of the nearest row.
 */
object CivVWorldSizes {
    /** Civ V's default number of city-states per civilization */
    private const val defaultMinorsPerMajor = 2

    /** What a city-state weighs against a civilization when choosing the size: the 60 and 384 tiles of the formula this table replaced */
    private const val minorWeight = 60f / 384

    /** Smallest sides: a world-wrapped map needs 32 columns (see [MapSize.fixUndesiredSizes]) */
    private const val minWidth = 32
    private const val minHeight = 20

    /** The number of civilizations in a default game that needs as much land as [majors] civilizations and [minors] city-states */
    fun equivalentMajors(majors: Int, minors: Int): Float =
        (majors + minors * minorWeight) / (1 + defaultMinorsPerMajor * minorWeight)

    /** Width and height of the map for [majors] civilizations and [minors] city-states on the [rows] of Worlds; the width is even, as world wrap needs */
    fun dimensions(rows: List<WorldSizeDef>, majors: Int, minors: Int): Pair<Int, Int> {
        val sorted = rows.sortedBy { it.defaultPlayers }
        val equivalent = equivalentMajors(majors, minors)
        val first = sorted.first()
        val last = sorted.last()
        val (width, height) = when {
            equivalent <= first.defaultPlayers -> scaled(first, equivalent)
            equivalent >= last.defaultPlayers -> scaled(last, equivalent)
            else -> {
                val upper = sorted.indexOfFirst { it.defaultPlayers >= equivalent }
                val low = sorted[upper - 1]
                val high = sorted[upper]
                val share = (equivalent - low.defaultPlayers) / (high.defaultPlayers - low.defaultPlayers)
                Pair(low.gridWidth + (high.gridWidth - low.gridWidth) * share, low.gridHeight + (high.gridHeight - low.gridHeight) * share)
            }
        }
        return Pair(evenWidth(width), maxOf(height.roundToInt(), minHeight))
    }

    private fun scaled(row: WorldSizeDef, equivalent: Float): Pair<Float, Float> {
        val side = sqrt(equivalent / row.defaultPlayers)
        return Pair(row.gridWidth * side, row.gridHeight * side)
    }

    private fun evenWidth(width: Float) = maxOf((width / 2).roundToInt() * 2, minWidth)
}
