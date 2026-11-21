package com.unciv.logic.map

import com.unciv.logic.IsPartOfGameInfoSerialization
import yairm210.purity.annotations.Readonly


/**
 *  Encapsulates the "map size" concept, without also choosing a shape.
 *
 *  Predefined sizes are kept in the [Predefined] enum, instances derived from these have the same [name] and copied dimensions.
 *  Custom sizes always have [custom] as [name], even if created with the exact same dimensions as a [Predefined]. 
 *  Auto sizes  similarly have [auto] as [name], their sizes are changed dynamically when the map is generated.
 *
 *  @property name
 *  @property radius
 *  @property width
 *  @property height
 *  @see MapShape
 */
/*
 *  The architecture is not as elegant as I'd like - an interface implemented by both an enum and a "custom" subclass would be shorter and nicer to read,
 *  but the obstacle of Json deserialization has -for the moment- the heavier weight. Instance creation would have to be customized, and with the Gdx.Json
 *  model, that would mean the simpler Serializable interface won't do, needing the clunky setSerializer instead.
 */
class MapSize private constructor(
    val name: String,
    var radius: Int,
    var width: Int,
    var height: Int,
) : IsPartOfGameInfoSerialization {

    /** Needed for Json parsing */
    @Suppress("unused")
    private constructor() : this("", 0, 0, 0)

    constructor(size: Predefined) : this(size.name, size.radius, size.width, size.height)

    /** The size called [name] of the sizes offered ([names]). A name that is not offered (a size of Unciv's old table such as "Medium")
     *  is the default [Predefined.Small]: a saved game never comes here (its size is read field by field), and the pickers
     *  turn such a size into a custom one first ([forPicker]). */
    constructor(name: String) : this(if (name == auto) Auto else MapSize(Predefined.safeValueOf(name) ?: Predefined.Small))

    constructor(radius: Int) : this(custom, radius, 0, 0) {
        setNewRadius(radius)
    }

    constructor(width: Int, height: Int) : this(custom, HexMath.getEquivalentHexagonalRadius(width, height), width, height)

    constructor(other: MapSize) : this(other.name, other.radius, other.width, other.height)

    /**
     *  The world sizes of Civ V: the `gridWidth` and `gridHeight` of the `worldSizes` of the Brave New World ruleset (CIV5Worlds.xml:
     *  Duel 40x24, Tiny 56x36, Small 66x42, Standard 80x52, Large 104x64, Huge 128x80, see [CivVWorldSizes]).
     *  Their name can appear in json only as copy in MapSize. The radius is that of the hexagonal map with as many tiles.
     */
    enum class Predefined(
        val width: Int,
        val height: Int,
    ) {
        Duel(40, 24),
        Tiny(56, 36),
        Small(66, 42),
        Standard(80, 52),
        Large(104, 64),
        Huge(128, 80);

        val radius = HexMath.getEquivalentHexagonalRadius(width, height)

        companion object {
            /** @return the size called [name], null if there is none (for instance the "Medium" of the sizes Unciv used to offer) */
            @Readonly fun safeValueOf(name: String) = entries.firstOrNull { it.name == name }
        }
    }

    companion object {
        /** Not a [Predefined] enum value, but a String
         * used in [name] to indicate user-defined dimensions.
         * Do not mistake for [MapGeneratedMainType.custom]. */
        const val custom = "Custom"
        const val auto = "Auto"

        /*
         *  The "Medium" of the sizes Unciv offered before the maps followed Civ V. It is no longer offered, but it is the value
         *  of every saved game that did not write its size, see the DO NOT CHANGE DEFAULTS note in [MapParameters]: a game's size is read
         *  from the save, and the fields a save omits are these. [Auto] starts from the same numbers (they are replaced when the map is generated).
         */
        private const val savedGameDefaultName = "Medium"
        private const val savedGameDefaultRadius = 20
        private const val savedGameDefaultWidth = 44
        private const val savedGameDefaultHeight = 29

        /** The default of [MapParameters.mapSize]: the old Medium (name "Medium", radius 20, 44x29), so that saved games load unchanged. Not in [names]; a picker shows it as custom ([forPicker]) */
        val SavedGameDefault get() = MapSize(savedGameDefaultName, savedGameDefaultRadius, savedGameDefaultWidth, savedGameDefaultHeight)
        val Auto get() = MapSize(auto, savedGameDefaultRadius, savedGameDefaultWidth, savedGameDefaultHeight)
        val Duel get() = MapSize(Predefined.Duel)
        val Tiny get() = MapSize(Predefined.Tiny)
        val Small get() = MapSize(Predefined.Small)
        val Standard get() = MapSize(Predefined.Standard)
        val Large get() = MapSize(Predefined.Large)
        val Huge get() = MapSize(Predefined.Huge)
        /** The sizes a player can pick: Civ V's six and [auto] (a picker adds [custom]) */
        fun names() = Predefined.entries.map { it.name } + listOf(auto)
    }

    fun clone() = MapSize(name, radius, width, height)

    /** The size as a size picker shows it: itself if it is [custom], [auto] or a [Predefined] size with its dimensions, otherwise
     *  (a saved game's or saved settings' size that is not offered, such as Unciv's "Medium", or one that carries the name
     *  of an offered size but the dimensions of Unciv's old table, such as its "Small" of 33x21) a [custom] size with the very same radius, width and height. */
    fun forPicker(): MapSize {
        if (name == custom || name == auto) return this
        val predefined = Predefined.safeValueOf(name)
        if (predefined != null && predefined.radius == radius && predefined.width == width && predefined.height == height) return this
        return MapSize(custom, radius, width, height)
    }

    /** Check custom dimensions, fix if too extreme
     * @param worldWrap whether world wrap is on
     * @return null if size was acceptable, otherwise untranslated reason message
     */
    fun fixUndesiredSizes(worldWrap: Boolean): String? {
        if (name != custom) return null  // predefined sizes are OK
        // world-wrap mas must always have an even width, so round down silently
        if (worldWrap && width % 2 != 0 ) width--
        // check for any bad condition and bail if none of them
        val message = when {
            worldWrap && width < 32 ->    // otherwise horizontal scrolling will show edges, empirical
                "World wrap requires a minimum width of 32 tiles"
            width < 3 || height < 3 || radius < 2 ->
                "The provided map dimensions were too small"
            radius > 500 ->
                "The provided map dimensions were too big"
            height * 16 < width || width * 16 < height ->    // aspect ratio > 16:1
                "The provided map dimensions had an unacceptable aspect ratio"
            else -> null
        } ?: return null

        // fix the size - not knowing whether hexagonal or rectangular is used
        setNewRadius(when {
            radius < 2 -> 2
            radius > 500 -> 500
            worldWrap && radius < 15 -> 15    // minimum for hexagonal but more than required for rectangular
            else -> radius
        })

        // tell the caller that map dimensions have changed and why
        return message
    }

    private fun setNewRadius(radius: Int) {
        this.radius = radius
        val size = HexMath.getEquivalentRectangularSize(radius)
        width = size.x
        height = size.y
    }

    // For debugging and MapGenerator console output
    override fun toString() = if (name == custom) "${width}x${height}" else name
}
