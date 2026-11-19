package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import kotlin.math.max

/** An attack from [musterCity] on [targetCity] by land or [attackBySea] (Vox Populi's CvAttackTarget, as [AttackTargetsAi] makes them) */
class MilitaryTarget(val musterCity: City, val targetCity: City) {
    var attackBySea = false
    /** Ships alone take the city (Vox Populi's naval army, CITY_ATTACK_NAVAL): our pure naval city attack */
    var pureNaval = false
    /** Step path length from the muster to the target city, -1 = no path (call off) */
    var pathLength = 0
}

/**
 *  The step finder of the original (GC.getStepFinder(): a hex path over terrain that ignores units) as far as the military AI needs it:
 *  a land route an army of [civ] could walk against [enemy] - explored, not water, not impassable, and territory it may enter (its own,
 *  the enemy's, friendly land, city-state land and that of civilizations it is at war with) - or a route over explored water under the
 *  same rules, where deep ocean counts as impassable until [civ] can embark onto it. One path length is a step.
 *  Scratch arrays are reused between calls; not thread safe, one instance per civilization AI.
 */
class StepFinder {
    private var stamp = IntArray(0)
    private var parent = IntArray(0)
    private var dist = IntArray(0)
    private var queue = IntArray(0)
    private var counter = 0

    private fun ensure(size: Int) {
        if (stamp.size >= size) return
        stamp = IntArray(size); parent = IntArray(size); dist = IntArray(size); queue = IntArray(size)
        counter = 0
    }

    /**
     *  StepValid: [tile] can be walked on by an army of [civ] heading for [enemy]. Land that has an owner is valid when the owner is
     *  [enemy], the tile is friendly territory for [civ], or the owner is a city-state or at war with [civ]: the original lets any civilization
     *  cross city-state land and only a major that is neither the enemy, a friend nor at war blocks the route. This is the route planner's
     *  rule, not the movement rules ([com.unciv.logic.civilization.diplomacy.DiplomacyFunctions.canPassThroughTiles]), which the units walk by.
     *  The original stays within one area (the land of the start, or its water), which here is land or water by the kind of route.
     */
    fun isValid(civ: Civilization, enemy: Civilization?, tile: Tile): Boolean = !tile.isWater && isOpen(civ, enemy, tile)

    /** StepValid for a route over water: the same rules for the tile, in the water area of the start */
    private fun seaValid(civ: Civilization, enemy: Civilization?, tile: Tile) = tile.isWater && isOpen(civ, enemy, tile)

    /**
     *  StepValid beyond the area check: nothing impassable (mountains, ice), no deep ocean unless the team can embark onto it
     *  (GetEmbarkedAllWaterPassage, [com.unciv.logic.civilization.managers.TechManager.embarkedUnitsCanEnterOcean]), and the territory rule of [isValid].
     *  Explored tiles only, the AI does not know the rest.
     */
    private fun isOpen(civ: Civilization, enemy: Civilization?, tile: Tile): Boolean {
        if (tile.isImpassible() || !civ.hasExplored(tile)) return false
        if (tile.isOcean && !civ.tech.embarkedUnitsCanEnterOcean) return false
        val owner = tile.getOwner() ?: return true
        return owner == enemy || tile.isFriendlyTerritory(civ) || owner.isCityState || owner.isBarbarian || civ.isAtWarWith(owner)
    }

    /** Breadth-first search from [start]; the visited tiles are left in the scratch arrays */
    private fun search(civ: Civilization, enemy: Civilization?, start: Tile, sea: Boolean, maxSteps: Int, stopAt: Tile?): Boolean {
        val tiles = civ.gameInfo.tileMap.tileList
        ensure(tiles.size)
        counter++
        var head = 0
        var tail = 0
        val startIndex = start.zeroBasedIndex
        stamp[startIndex] = counter; dist[startIndex] = 0; parent[startIndex] = -1
        queue[tail++] = startIndex
        while (head < tail) {
            val current = tiles[queue[head++]]
            if (current === stopAt) return true
            if (dist[current.zeroBasedIndex] >= maxSteps) continue
            for (neighbor in current.neighbors) {
                val index = neighbor.zeroBasedIndex
                if (stamp[index] == counter) continue
                if (neighbor !== stopAt && !(if (sea) seaValid(civ, enemy, neighbor) else isValid(civ, enemy, neighbor))) continue
                stamp[index] = counter
                dist[index] = dist[current.zeroBasedIndex] + 1
                parent[index] = current.zeroBasedIndex
                queue[tail++] = index
            }
        }
        return stopAt == null
    }

    /** The step length from [start] to each of [targets], -1 where there is no route (one search for all) */
    fun distances(civ: Civilization, enemy: Civilization?, start: Tile, targets: List<Tile>): IntArray {
        search(civ, enemy, start, sea = false, maxSteps = Int.MAX_VALUE, stopAt = null)
        return IntArray(targets.size) { if (stamp[targets[it].zeroBasedIndex] == counter) dist[targets[it].zeroBasedIndex] else -1 }
    }

    /** GetStepDistanceBetweenPoints / DoesPathExist: the step length, -1 if none */
    fun distance(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile, sea: Boolean = false): Int =
        if (search(civ, enemy, from, sea, maxSteps = Int.MAX_VALUE, stopAt = to)) dist[to.zeroBasedIndex] else -1

    /** GeneratePath: the tiles from [from] to [to] inclusive, null if none */
    fun path(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile, sea: Boolean = false): List<Tile>? {
        if (!search(civ, enemy, from, sea, maxSteps = Int.MAX_VALUE, stopAt = to)) return null
        val tiles = civ.gameInfo.tileMap.tileList
        val result = ArrayList<Tile>()
        var index = to.zeroBasedIndex
        while (index != -1) {
            result.add(tiles[index])
            index = parent[index]
        }
        result.reverse()
        return result
    }

    /** DoesPathExist over water between two sea tiles */
    fun seaPathExists(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile): Boolean = search(civ, enemy, from, sea = true, maxSteps = Int.MAX_VALUE, stopAt = to)

    /**
     *  GetXPlotsFromEnd: the tile [x] steps before the end of the path from [from] to [to] (the start if the path is shorter);
     *  null if there is no path. With [sea] the path runs over explored water (the naval operations' steps).
     */
    fun xPlotsFromEnd(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile, x: Int, sea: Boolean = false): Tile? {
        val path = path(civ, enemy, from, to, sea) ?: return null
        return path[max(0, path.size - 1 - x)]
    }
}

/**
 *  The target selection of CvMilitaryAI that Vox Populi's attack targets ([AttackTargetsAi]) leave to the operations: the targets of
 *  RequestCityAttack ([cityAttackTargets]) and the queries that RequestBullyingOperation and CvAIOperationPillageEnemy::FindBestTarget ask
 *  (CvPlayer::GetClosestCityByPathLength, CvPlayer::GetClosestCityToUsByPlots, CvCity::HasSharedAreaWith). BNW's FindBestAttackTarget with
 *  its ShouldAttackBySea, ScoreTarget and EvaluateMilitaryApproaches, which Vox Populi has not, are gone.
 *
 *  Deviations: the step finder is [StepFinder] (own routes only, no ignore-units variant).
 */
object MilitaryTargets {

    /**
     *  The targets Vox Populi's CvMilitaryAI::RequestCityAttack tries against [enemy], in turn: the potential attack targets among their
     *  cities ([MilitaryAi.potentialAttackTargets], best score first), only the preferred ones when [careful]; a naval or combined army
     *  goes by sea, a naval one with ships alone.
     */
    fun cityAttackTargets(civ: Civilization, enemy: Civilization, careful: Boolean): List<MilitaryTarget> =
        civ.ai.military.potentialAttackTargets
            .filter { it.targetCity.civ == enemy && (!careful || it.preferred) && it.musterCity.civ == civ }
            .map { target ->
                MilitaryTarget(target.musterCity, target.targetCity).apply {
                    attackBySea = target.armyType != TargetArmyType.Land
                    pureNaval = target.armyType == TargetArmyType.Naval
                    pathLength = target.pathLength
                }
            }

    /**
     *  CvPlayer::GetClosestCityByPathLength with CvPlayer::GetCityDistancePathLength (CvDistanceMapByPathLength, Vox Populi
     *  Release-5.4.8): our city with the shortest route to [tile] and that route's length in steps, null if none reaches it. [enemy] is
     *  whose land the route is for ([StepFinder]'s territory rule).
     *  Deviations: the original's map holds PT_ARMY_MIXED routes (land and embarked, the shortest wins) over every tile, revealed or not,
     *  up to 36 turns; here it is the land route of the [StepFinder] over the tiles [civ] has explored and, only when no city of ours has
     *  one, with [allowSea] and embarkation known, the shortest route over explored water from the shallow water next to one of our coastal
     *  cities to that next to [tile], two steps added for the land at both ends (a sea route never beats a land one). The original
     *  answers a minor civilization's query with its capital; ours is a major civilization's.
     */
    fun closestCityByPathLength(civ: Civilization, enemy: Civilization?, tile: Tile, allowSea: Boolean): Pair<City, Int>? {
        val steps = civ.ai.military.steps
        val lengths = steps.distances(civ, enemy, tile, civ.cities.map { it.getCenterTile() })
        var best: Pair<City, Int>? = null
        for ((index, city) in civ.cities.withIndex()) {
            val length = lengths[index]
            if (length >= 0 && (best == null || length < best.second)) best = city to length
        }
        if (best != null || !allowSea || !civ.tech.unitsCanEmbark) return best
        val landing = coastalPlotAdjacentToTarget(tile) ?: return null
        for (city in civ.cities) {
            val port = coastalPlotAdjacentToTarget(city.getCenterTile()) ?: continue
            val length = steps.distance(civ, enemy, port, landing, sea = true)
            if (length >= 0 && (best == null || length + 2 < best.second)) best = city to length + 2
        }
        return best
    }

    /**
     *  CvPlayer::GetClosestCityToUsByPlots: the city of [other] that is nearest to a city of ours (CvPlayer::GetClosestCityByPlots of
     *  each of theirs, the distance in plots); the first of them on a tie.
     */
    fun closestCityToUsByPlots(civ: Civilization, other: Civilization): City? {
        var best: City? = null
        var bestDistance = Int.MAX_VALUE
        for (theirs in other.cities) {
            val tile = theirs.getCenterTile()
            val distance = civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: continue
            if (distance < bestDistance) { bestDistance = distance; best = theirs }
        }
        return best
    }

    /**
     *  CvCity::HasSharedAreaWith(other, allow land, no water): the cities stand on the same landmass, or the land around one of them
     *  borders a landmass that also borders the other (CvPlot::hasSharedAdjacentArea, which leaves a plot's own area out of its adjacent
     *  areas).
     */
    fun hasSharedLandArea(first: City, second: City): Boolean {
        val firstTile = first.getCenterTile()
        val secondTile = second.getCenterTile()
        if (firstTile.getContinent() == secondTile.getContinent()) return true
        fun adjacentLand(tile: Tile) = tile.neighbors.filter { it.isLand && it.getContinent() != tile.getContinent() }.map { it.getContinent() }.toSet()
        val secondAreas = adjacentLand(secondTile)
        return adjacentLand(firstTile).any { it in secondAreas }
    }

    /** CvMilitaryAI::GetCoastalPlotAdjacentToTarget without an army: the first shallow water tile next to [target] */
    fun coastalPlotAdjacentToTarget(target: Tile): Tile? =
        target.neighbors.firstOrNull { it.isWater && it.getBaseTerrain().isCoast }
}
