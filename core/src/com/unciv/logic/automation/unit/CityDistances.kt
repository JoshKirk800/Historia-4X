package com.unciv.logic.automation.unit

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import java.util.WeakHashMap

/**
 *  The closest-city lookup tables of Vox Populi's CvGame (CvDistanceMap.cpp: CvDistanceMapByPlots and CvDistanceMapByPathLength), which
 *  the site evaluation and the settle plot search read through CvPlayer::GetCityDistanceInPlots, GetCityDistancePathLength,
 *  CvGame::GetClosestCityDistanceInPlots and GetClosestCityByPathLength: for each tile the distance to the nearest city - of one major civilization,
 *  of all majors, of everybody - and which city it is. Each table is one multi-source breadth-first search, rebuilt when the cities change.
 *
 *  Deviations: the original's path length is the step finder's route of an army that crosses every terrain not impassable - over land and
 *  water, unrevealed tiles and foreign territory included - up to 36 turns; here it is the number of steps over land and shallow water (deep
 *  ocean excluded, mountains and other impassable terrain blocked) with no limit, and a tile with no route is [VERY_FAR] (72 = 2 x 36, as in the
 *  original). The original's tie-break between two cities at the same distance (CvDistanceMap.cpp: SCityTiebreak) tests the owner of the plot
 *  with the sign reversed (the owner of the plot loses), against what the rest of it says: here the owner of the plot wins, then the older
 *  city.
 */
object CityDistances {
    /** The distance of a tile with no route to a city: iVeryFar = 2 x 36 turns in CvDistanceMapByPathLength::Update */
    const val VERY_FAR = 72
    /** The distance by plots of a tile when nobody has a city: SHRT_MAX in CvDistanceMapByPlots::Update */
    const val NO_CITY = Short.MAX_VALUE.toInt()

    /**
     *  One CvDistanceMap: for each tile (by [Tile.zeroBasedIndex]) the distance to the nearest city and that city, as the index of its owner in
     *  [GameInfo.civilizations] and its [City.id]. The tables are cached per game and hold no reference to the game's objects, which would keep
     *  the game from being collected (a [WeakHashMap] value that refers to its key).
     */
    class Table(size: Int, far: Int) {
        val distance = IntArray(size) { far }
        val owner = IntArray(size) { -1 }
        val cityId = arrayOfNulls<String>(size)
    }

    /** The tables for one state of the world; [signature] says which cities they were built from */
    class Maps(val turn: Int, val signature: Int, private val plotsAll: Table, private val plotsMajors: Table, private val plotsByCiv: Map<String, Table>,
               private val pathAll: Table, private val pathMajors: Table, private val pathByCiv: Map<String, Table>) {

        /** CvPlayer::GetCityDistanceInPlots: the distance in plots from [tile] to the nearest city of [civ]; a minor civilization's is half the distance to its capital */
        fun cityDistanceInPlots(civ: Civilization, tile: Tile): Int {
            if (civ.isMajorCiv) return plotsByCiv[civ.civID]?.distance?.get(tile.zeroBasedIndex) ?: NO_CITY
            val capital = civ.getCapital() ?: return Int.MAX_VALUE
            return capital.getCenterTile().aerialDistanceTo(tile) / 2
        }

        /** CvPlayer::GetCityDistancePathLength: the steps from [tile] to the nearest city of [civ], [VERY_FAR] where there is no route */
        fun cityDistancePathLength(civ: Civilization, tile: Tile): Int {
            if (civ.isMajorCiv) return pathByCiv[civ.civID]?.distance?.get(tile.zeroBasedIndex) ?: VERY_FAR
            val capital = civ.getCapital() ?: return Int.MAX_VALUE
            return capital.getCenterTile().aerialDistanceTo(tile) / 2
        }

        /** CvGame::GetClosestCityDistanceInPlots: the distance in plots to the nearest city of anybody, or of the major civilizations */
        fun closestCityDistanceInPlots(tile: Tile, majorsOnly: Boolean = false): Int =
            (if (majorsOnly) plotsMajors else plotsAll).distance[tile.zeroBasedIndex]

        /** The index in [GameInfo.civilizations] of the owner of CvGame::GetClosestCityByPathLength, -1 if there is none */
        fun closestCityOwnerByPathLength(tile: Tile, majorsOnly: Boolean): Int = (if (majorsOnly) pathMajors else pathAll).owner[tile.zeroBasedIndex]

        /** The [City.id] of CvGame::GetClosestCityByPathLength, null if there is none */
        fun closestCityIdByPathLength(tile: Tile, majorsOnly: Boolean): String? = (if (majorsOnly) pathMajors else pathAll).cityId[tile.zeroBasedIndex]

        /** CvGame::GetClosestCityDistancePathLength */
        fun closestCityDistancePathLength(tile: Tile, majorsOnly: Boolean): Int = (if (majorsOnly) pathMajors else pathAll).distance[tile.zeroBasedIndex]
    }

    private val cache = WeakHashMap<GameInfo, Maps>()

    private fun signatureOf(gameInfo: GameInfo): Int {
        var hash = 17
        for (civ in gameInfo.civilizations) for (city in civ.cities) {
            hash = hash * 31 + city.id.hashCode()
            hash = hash * 31 + civ.civID.hashCode()
        }
        return hash
    }

    /** The tables of [gameInfo] as of now: rebuilt when the turn or any city or its owner changed (the original marks them dirty on city founding and conquest) */
    fun of(gameInfo: GameInfo): Maps {
        val signature = signatureOf(gameInfo)
        synchronized(cache) {
            val cached = cache[gameInfo]
            if (cached != null && cached.turn == gameInfo.turns && cached.signature == signature) return cached
        }
        val maps = build(gameInfo, signature)
        synchronized(cache) { cache[gameInfo] = maps }
        return maps
    }

    private fun build(gameInfo: GameInfo, signature: Int): Maps {
        val tiles = gameInfo.tileMap.tileList
        val allCities = gameInfo.civilizations.flatMap { it.cities }
        val majorCities = allCities.filter { it.civ.isMajorCiv }
        val majors = gameInfo.civilizations.filter { it.isMajorCiv && it.cities.isNotEmpty() }
        val anywhere: (Tile) -> Boolean = { true }
        val walkable: (Tile) -> Boolean = { !it.isImpassible() && !it.isOcean }
        val civIndex = gameInfo.civilizations.withIndex().associate { it.value.civID to it.index }
        fun table(cities: List<City>, far: Int, passable: (Tile) -> Boolean) = flood(tiles, cities, far, passable, civIndex)
        return Maps(
            gameInfo.turns, signature,
            table(allCities, NO_CITY, anywhere), table(majorCities, NO_CITY, anywhere), majors.associate { it.civID to table(it.cities, NO_CITY, anywhere) },
            table(allCities, VERY_FAR, walkable), table(majorCities, VERY_FAR, walkable), majors.associate { it.civID to table(it.cities, VERY_FAR, walkable) })
    }

    /**
     *  CvDistanceMap::UpdateDistanceIfLower for every city at once: a layered breadth-first search from all [cities], so that every tile ends up with
     *  its nearest city; a tile where two cities are equally near keeps the one [better] says.
     */
    private fun flood(tiles: List<Tile>, cities: List<City>, far: Int, passable: (Tile) -> Boolean, civIndex: Map<String, Int>): Table {
        val table = Table(tiles.size, far)
        val nearest = arrayOfNulls<City>(tiles.size)
        val queue = IntArray(tiles.size)
        var head = 0
        var tail = 0
        for (city in cities) {
            val index = city.getCenterTile().zeroBasedIndex
            if (table.distance[index] == 0) continue
            table.distance[index] = 0
            nearest[index] = city
            queue[tail++] = index
        }
        while (head < tail) {
            val index = queue[head++]
            val tile = tiles[index]
            val next = table.distance[index] + 1
            // A tile this far is "no route" (CvDistanceMapByPathLength::Update stops at iMaxTurns): it keeps the default distance and has no city
            if (next >= far) continue
            val city = nearest[index]!!
            for (neighbor in tile.neighbors) {
                if (!passable(neighbor)) continue
                val neighborIndex = neighbor.zeroBasedIndex
                val known = table.distance[neighborIndex]
                if (next < known) {
                    table.distance[neighborIndex] = next
                    nearest[neighborIndex] = city
                    queue[tail++] = neighborIndex
                } else if (next == known && nearest[neighborIndex] !== city && better(neighbor, city, nearest[neighborIndex]!!)) {
                    nearest[neighborIndex] = city
                }
            }
        }
        for (index in tiles.indices) {
            val city = nearest[index] ?: continue
            table.owner[index] = civIndex[city.civ.civID] ?: -1
            table.cityId[index] = city.id
        }
        return table
    }

    /** SCityTiebreak: [candidate] beats [current] at [tile] when its owner owns the tile, else when it is older */
    private fun better(tile: Tile, candidate: City, current: City): Boolean {
        val owner = tile.getOwner()
        if (owner != null) {
            if (owner == current.civ && owner != candidate.civ) return false
            if (owner == candidate.civ && owner != current.civ) return true
        }
        return candidate.turnAcquired < current.turnAcquired
    }

    /** [Maps.cityDistanceInPlots] of the current tables */
    fun cityDistanceInPlots(civ: Civilization, tile: Tile) = of(civ.gameInfo).cityDistanceInPlots(civ, tile)

    /** [Maps.cityDistancePathLength] of the current tables */
    fun cityDistancePathLength(civ: Civilization, tile: Tile) = of(civ.gameInfo).cityDistancePathLength(civ, tile)

    /** [Maps.closestCityDistanceInPlots] of the current tables */
    fun closestCityDistanceInPlots(gameInfo: GameInfo, tile: Tile, majorsOnly: Boolean = false) = of(gameInfo).closestCityDistanceInPlots(tile, majorsOnly)

    /** CvGame::GetClosestCityByPathLength of the current tables */
    fun closestCityByPathLength(gameInfo: GameInfo, tile: Tile, majorsOnly: Boolean): City? {
        val maps = of(gameInfo)
        val owner = maps.closestCityOwnerByPathLength(tile, majorsOnly)
        val id = maps.closestCityIdByPathLength(tile, majorsOnly)
        if (owner < 0 || id == null) return null
        return gameInfo.civilizations[owner].cities.firstOrNull { it.id == id }
    }

    /** [Maps.closestCityDistancePathLength] of the current tables */
    fun closestCityDistancePathLength(gameInfo: GameInfo, tile: Tile, majorsOnly: Boolean) = of(gameInfo).closestCityDistancePathLength(tile, majorsOnly)
}
