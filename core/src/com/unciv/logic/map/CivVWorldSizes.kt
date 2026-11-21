package com.unciv.logic.map

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 *  The world sizes of Brave New World, for maps whose size follows the number of players ("Auto").
 *
 *  Source: CIV5Worlds.xml (BNW), `DefaultPlayers`, `DefaultMinorCivs`, `GridWidth`, `GridHeight` of Duel, Tiny, Small, Standard, Large and Huge:
 *  2/4 on 40x24, 4/8 on 56x36, 6/12 on 66x42, 8/16 on 80x52, 10/20 on 104x64, 12/24 on 128x80.
 *  The default has two city-states for each civilization, so a row is keyed by its civilizations.
 *
 *  A game with another mix is placed between the rows by the number of civilizations it is worth ([equivalentMajors]).
 *  Dimensions between two rows are interpolated linearly; below Duel and above Huge the sides scale with the square root of the
 *  count, which keeps the tiles per civilization of the nearest row.
 */
object CivVWorldSizes {
    private class Row(val majors: Int, val width: Int, val height: Int)

    private val rows = listOf(
        Row(2, 40, 24),
        Row(4, 56, 36),
        Row(6, 66, 42),
        Row(8, 80, 52),
        Row(10, 104, 64),
        Row(12, 128, 80),
    )

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

    /** Width and height of the map for [majors] civilizations and [minors] city-states; the width is even, as world wrap needs */
    fun dimensions(majors: Int, minors: Int): Pair<Int, Int> {
        val equivalent = equivalentMajors(majors, minors)
        val first = rows.first()
        val last = rows.last()
        val (width, height) = when {
            equivalent <= first.majors -> scaled(first, equivalent)
            equivalent >= last.majors -> scaled(last, equivalent)
            else -> {
                val upper = rows.indexOfFirst { it.majors >= equivalent }
                val low = rows[upper - 1]
                val high = rows[upper]
                val share = (equivalent - low.majors) / (high.majors - low.majors)
                Pair(low.width + (high.width - low.width) * share, low.height + (high.height - low.height) * share)
            }
        }
        return Pair(evenWidth(width), maxOf(height.roundToInt(), minHeight))
    }

    private fun scaled(row: Row, equivalent: Float): Pair<Float, Float> {
        val side = sqrt(equivalent / row.majors)
        return Pair(row.width * side, row.height * side)
    }

    private fun evenWidth(width: Float) = maxOf((width / 2).roundToInt() * 2, minWidth)
}
