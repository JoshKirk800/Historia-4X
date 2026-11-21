package com.unciv.testing

import com.unciv.json.json
import com.unciv.logic.map.MapSize

/**
 *  The sizes Unciv offered before the maps followed Civ V (`MapSize.Predefined` is Civ V's table now), with their old name, radius and
 *  dimensions: what a saved game of those days holds, read the way a save is read. For tests and dev tools whose maps were calibrated
 *  on them, or that only need a small and fast map. No picker offers them.
 */
object UncivMapSizes {
    private fun size(name: String, radius: Int, width: Int, height: Int) =
        json().fromJson(MapSize::class.java, """{"name":"$name","radius":$radius,"width":$width,"height":$height}""")

    val Tiny get() = size("Tiny", 10, 23, 15)
    val Small get() = size("Small", 15, 33, 21)
    val Medium get() = size("Medium", 20, 44, 29)
    val Large get() = size("Large", 30, 66, 43)
    val Huge get() = size("Huge", 40, 87, 57)

    /** The size called [name] of the old table, null if there is none */
    fun byName(name: String) = when (name) {
        "Tiny" -> Tiny
        "Small" -> Small
        "Medium" -> Medium
        "Large" -> Large
        "Huge" -> Huge
        else -> null
    }
}
