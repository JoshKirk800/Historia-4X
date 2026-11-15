package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * [TradeRoutePathfinder] finds the paths the original's A* does. It keeps its state in reused arrays, so every path is checked against the plain
 * HashMap and PriorityQueue version of the same search, on a map with water, mountains, hills, woods, roads and an enemy's waters.
 */
@RunWith(GdxTestRunner::class)
class TradeRoutePathfinderTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14, Constants.plains)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.getDiplomacyManager(enemy)?.declareWar() ?: civ.diplomacyFunctions.makeCivilizationsMeet(enemy).also { civ.getDiplomacyManager(enemy)!!.declareWar() }
        val random = Random(4)
        for (tile in game.tileMap.values) {
            when (random.nextInt(10)) {
                0, 1 -> game.setTileTerrain(tile.position, Constants.ocean)
                2 -> game.setTileTerrain(tile.position, Constants.coast)
                3 -> game.setTileTerrain(tile.position, Constants.mountain)
                4 -> game.setTileTerrainAndFeatures(tile.position, Constants.plains, Constants.forest)
                5 -> game.setTileTerrainAndFeatures(tile.position, Constants.plains, Constants.hill)
                else -> {}
            }
            if (tile.isLand && random.nextInt(4) == 0) tile.roadStatus = RoadStatus.Road
            if (tile.isWater && random.nextInt(5) == 0) tile.setOwningCity(game.addCity(enemy, tile).also { it.isBeingRazed = false })
        }
    }

    /** The search as it was written with a HashMap, a PriorityQueue and a node class: the reference */
    private fun reference(civ: Civilization, origin: Tile, destination: Tile, isSea: Boolean, range: Int): List<Tile>? {
        if (origin == destination) return null
        if (isSea && (!TradeRoutePathfinder.isOnWater(origin) || !TradeRoutePathfinder.isOnWater(destination))) return null
        val budget = range * 100 + 99
        val allWater = civ.tech.embarkedUnitsCanEnterOcean
        val woodsAreRoads = civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)
        class Node(val tile: Tile, val priority: Int, val order: Int)
        val cameFrom = HashMap<Tile, Tile>()
        val cost = HashMap<Tile, Int>()
        val queue = PriorityQueue<Node>(compareBy<Node>({ it.priority }, { it.order }))
        var counter = 0
        cost[origin] = 0
        queue.add(Node(origin, 100 * origin.aerialDistanceTo(destination), counter++))
        fun hasRoad(tile: Tile) = tile.isCityCenter() || tile.roadStatus != RoadStatus.None
        fun nextToLand(tile: Tile) = tile.neighbors.any { it.isLand }
        while (queue.isNotEmpty()) {
            val current = queue.poll().tile
            if (current == destination) break
            val known = cost[current]!!
            for (next in current.neighbors) {
                val step = if (isSea) {
                    if (next.isCityCenter()) 100
                    else if (!next.isWater || next.isImpassible()) -1
                    else {
                        val terrain = next.getBaseTerrain()
                        if (!(terrain.isCoast || terrain.isFreshwater) && !allWater) -1
                        else {
                            var c = 100
                            if (!(nextToLand(current) && nextToLand(next))) c++
                            val owner = next.getOwner()
                            if (owner != null && owner.isAtWarWith(civ)) c += 1000
                            c
                        }
                    }
                } else {
                    if (!next.isLand || next.isImpassible() || next.baseTerrain == Constants.mountain) -1
                    else if (hasRoad(current) && hasRoad(next)) 50
                    else {
                        val woods = next.terrainFeatures.contains(Constants.forest) || next.terrainFeatures.contains(Constants.jungle)
                        if (woodsAreRoads && woods) 50
                        else {
                            var c = 100
                            if (next.isHill() || woods || next.terrainFeatures.contains(Constants.ice)) c++
                            if (!next.terrainFeatures.contains("Oasis")) c++
                            c
                        }
                    }
                }
                if (step < 0) continue
                val newCost = known + step
                if (newCost > budget) continue
                if (newCost >= (cost[next] ?: Int.MAX_VALUE)) continue
                cost[next] = newCost
                cameFrom[next] = current
                queue.add(Node(next, newCost + 100 * next.aerialDistanceTo(destination), counter++))
            }
        }
        if (destination !in cameFrom) return null
        val path = ArrayList<Tile>()
        var tile: Tile? = destination
        while (tile != null) { path.add(tile); tile = cameFrom[tile] }
        path.reverse()
        return path
    }

    @Test
    fun `paths are the same as those of the plain search, on land and at sea, found and not found`() {
        val tiles = game.tileMap.values.toList()
        val random = Random(9)
        var found = 0
        var missing = 0
        repeat(600) {
            val origin = tiles[random.nextInt(tiles.size)]
            val destination = tiles[random.nextInt(tiles.size)]
            val isSea = random.nextBoolean()
            val range = listOf(5, 10, 20, 30)[random.nextInt(4)]
            val expected = reference(civ, origin, destination, isSea, range)
            val actual = TradeRoutePathfinder.findPath(civ, origin, destination, isSea, range)
            assertEquals("$origin to $destination at sea $isSea, range $range", expected?.map { it.position }, actual?.map { it.position })
            if (expected == null) missing++ else found++
        }
        assertTrue("some paths exist ($found) and some do not ($missing)", found > 30 && missing > 30)
    }

    @Test
    fun `the tiles reached in one pass are exactly those the plain search finds a path to`() {
        val tiles = game.tileMap.values.toList()
        val random = Random(21)
        var reachable = 0
        var unreachable = 0
        repeat(40) {
            val origin = tiles[random.nextInt(tiles.size)]
            val isSea = random.nextBoolean()
            val range = listOf(5, 10, 20)[random.nextInt(3)]
            val reached = TradeRoutePathfinder.reachableTiles(civ, origin, isSea, range)
            for (destination in tiles.shuffled(random).take(40)) {
                val found = reference(civ, origin, destination, isSea, range) != null
                val reachedByPass = destination != origin && destination in reached && (!isSea || TradeRoutePathfinder.isOnWater(destination))
                assertEquals("$origin to $destination at sea $isSea, range $range", found, reachedByPass)
                if (found) reachable++ else unreachable++
            }
        }
        assertTrue("both kinds were drawn ($reachable, $unreachable)", reachable > 50 && unreachable > 50)
    }

    @Test
    fun `a search after another one is not confused by what the first left behind`() {
        val land = game.tileMap.values.filter { it.isLand && !it.isImpassible() }
        val a = land[0]; val b = land[land.size / 2]
        val first = TradeRoutePathfinder.findPath(civ, a, b, false, 30)
        repeat(5) { TradeRoutePathfinder.findPath(civ, land[it + 1], land[land.size - 1 - it], false, 30) }
        assertEquals(first?.map { it.position }, TradeRoutePathfinder.findPath(civ, a, b, false, 30)?.map { it.position })
    }

    @Test
    fun `the same tile, and a coast that is not one, have no path`() {
        val land = game.tileMap.values.first { it.isLand }
        assertNull(TradeRoutePathfinder.findPath(civ, land, land, false, 10))
        assertNotNull(game.tileMap.values.count())
    }
}
