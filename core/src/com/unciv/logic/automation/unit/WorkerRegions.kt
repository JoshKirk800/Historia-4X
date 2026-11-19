package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max

/**
 *  CvHomelandAI::PlanWorkerDistribution (Vox Populi), SWorkerRegion, IsWorkerAtAllocatedRegion and GetWorkerRegionTargetPlot: how many workers
 *  each land region of the civilization should have, and which workers belong to which. Planned once a turn by [BuilderAssignment].
 *
 *  - Only for an AI civilization (not a human's, a city-state or the barbarians) at peace; otherwise there are no regions
 *    ([considerRegions] is the original's bConsiderRegions of ExecuteWorkerMoves, which asks the same).
 *  - A region is the set of cities reachable from one another on foot ([reachableCities]: the original's step finder PT_WORKER_LAND_UNIT_SAFE),
 *    foreign ones included (a worker standing on a foreign city's plot belongs to its region); its need is the sum of the
 *    [com.unciv.logic.automation.strategy.CityAi.terrainImprovementNeed] of our cities in it.
 *  - Every worker gets a region by the city that owns its plot (none outside every city's territory, such a worker is on the move between
 *    regions). Each region wants one worker first (in order of need), then the rest go to the region with the most need left, each worker taking
 *    the need of `max(total need / workers, 1)` away. A region with fewer workers than wanted takes the workers without region, then the
 *    surplus of the others.
 *
 *  Unciv mapping and deviations:
 *  - A worker is a land unit that can build improvements and is no Great Person (the original's UNITAI_WORKER by the unit's default AI).
 *  - The step finder's validity (CvAStar.cpp: LandWorkerUnitSafeValid): a plot must be explored and seen (or reached from one that is), water needs
 *    embarking (the ocean, the embarked units' ocean crossing), and it must be safe for a route ([PlannedRoutes.isPlotSafeForRoute], with the
 *    adjacent plots) for itself or its predecessor. Impassable plots are left out (the original's step finder lets a worker walk through a mountain
 *    range or ice; ours cannot, and the plan's decision is "the cities reachable from each other on foot"). The search has no turn limit.
 *  - A worker that is not at its region goes to the region's first city ([BuilderAssignment], [WorkerAutomation]); the original does that
 *    in the pass (CvHomelandAI::ExecuteMoveToTarget, with the flags no enemy territory and abort in danger); ours decides in the pass whether a path
 *    exists ([com.unciv.logic.map.mapunit.movement.UnitMovement.canReach]) and the builder walks when its own turn comes.
 *  - None: SWorkerRegion's global ID counter (the order of the cities is the order of the regions).
 */
class WorkerRegions(private val civ: Civilization, private val routes: PlannedRoutes) {
    /** SWorkerRegion: the [cities] reachable from one another ([capital] is the first of ours that made it, where the workers are sent) */
    class Region(val id: Int, val cities: Set<City>, var need: Int, val capital: Tile) {
        var wanted = 0
        val workers = ArrayList<MapUnit>()
        fun owns(unit: MapUnit) = unit in workers
    }

    /** bConsiderRegions: the original plans regions for an AI civilization that is not a city-state, a barbarian, or at war */
    val considerRegions = !civ.isHuman() && !civ.isCityState && !civ.isBarbarian && !civ.isAtWar()

    /** The regions of this turn by their id, empty when there are none to plan (no workers, no cities, [considerRegions] is false) */
    val regions: List<Region> = plan()

    /** CvHomelandAI::GetWorkerRegionTargetPlot: the first city of the region that owns [unit], null if it has none */
    fun targetPlot(unit: MapUnit): Tile? = regions.firstOrNull { it.owns(unit) }?.capital

    /**
     *  CvHomelandAI::IsWorkerAtAllocatedRegion: the city that owns [target] (the unit's own plot when null) is in the region of [unit], or one
     *  that owns a plot within two of it (the unit may be a bit outside of its territory).
     */
    fun isAtAllocatedRegion(unit: MapUnit, target: Tile? = null): Boolean {
        val tile = target ?: unit.getTile()
        val region = regions.firstOrNull { it.owns(unit) }
        tile.getCity()?.let { city -> if (region != null && city in region.cities) return true }
        return region != null && tile.getTilesInDistanceRange(1..2).any { val city = it.getCity(); city != null && city in region.cities }
    }

    /** The workers of the original's PlanWorkerDistribution: land builders of ours that are no Great People, in the order of our units */
    private fun workers(): List<MapUnit> = civ.units.getCivUnits().filter {
        !it.isDestroyed && it.isCivilian() && !it.baseUnit.isGreatPerson && it.baseUnit.isLandUnit && it.hasUnique(UniqueType.BuildImprovements)
    }.toList()

    private fun plan(): List<Region> {
        if (!considerRegions) return emptyList()
        val workers = workers()
        if (workers.isEmpty()) return emptyList()

        val found = ArrayList<Region>()
        val processed = HashSet<City>()
        var globalNeed = 0
        for (city in civ.cities) {
            if (city in processed) continue
            val cities = LinkedHashSet<City>()
            cities.add(city)
            var need = city.ai.terrainImprovementNeed
            for (reached in reachableCities(city)) {
                if (reached == city) continue
                cities.add(reached)
                if (reached.civ == civ) need += reached.ai.terrainImprovementNeed
                processed.add(reached)
            }
            found.add(Region(found.size, cities, need, city.getCenterTile()))
            processed.add(city)
            globalNeed += need
        }
        if (found.isEmpty()) return emptyList()

        // Sort by number of improvements needed
        var sorted = found.sortedByDescending { it.need }

        var available = workers.size
        val withoutRegion = ArrayList<MapUnit>()
        for (worker in workers) {
            val region = worker.getTile().getCity()?.let { owner -> sorted.firstOrNull { owner in it.cities } }
            if (region != null) region.workers.add(worker) else withoutRegion.add(worker)
        }

        val improvementsPerWorker = max(globalNeed / available, 1)

        // First we want at least one worker in each contiguous region if possible
        for (region in sorted) {
            if (available <= 0) break
            region.wanted++
            region.need -= improvementsPerWorker
            available--
        }

        // Then assign workers based on which region needs them the most
        while (available > 0) {
            val mostValuable = sorted.first()
            mostValuable.wanted++
            mostValuable.need -= improvementsPerWorker
            sorted = sorted.sortedByDescending { it.need }
            available--
        }

        // Sort by region ID
        val byId = sorted.sortedBy { it.id }

        // We have figured out how many workers each region should have, now we need to assign our workers to the different regions
        for (region in byId) {
            if (region.workers.size >= region.wanted) continue
            // first check for unassigned workers (they are moving between regions)
            val taken = ArrayList<MapUnit>()
            for (worker in withoutRegion) {
                if (region.workers.size >= region.wanted) break
                region.workers.add(worker)
                taken.add(worker)
            }
            withoutRegion.removeAll(taken.toSet())

            // then check if we can take some from another region
            if (region.workers.size < region.wanted) {
                for (other in byId) {
                    while (region.workers.size < region.wanted && other.workers.size > other.wanted) region.workers.add(other.workers.removeAt(0))
                }
            }
        }
        return byId
    }

    /**
     *  The cities (any owner) that a worker of ours walking from [start] reaches (GetPlotsInReach of PT_WORKER_LAND_UNIT_SAFE): the plot must be
     *  explored, seen or next to a seen one, and a water plot needs embarking; and be safe for a route, or its predecessor.
     */
    private fun reachableCities(start: City): List<City> {
        val origin = start.getCenterTile()
        val seen = HashSet<Tile>()
        seen.add(origin)
        val queue = ArrayDeque<Tile>()
        queue.add(origin)
        val cities = ArrayList<City>()
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (tile in parent.neighbors) {
                if (tile in seen || !isValid(parent, tile)) continue
                seen.add(tile)
                queue.add(tile)
                if (tile.isCityCenter()) tile.getCity()?.let { cities.add(it) }
            }
        }
        return cities
    }

    /** CvAStar.cpp: LandWorkerUnitSafeValid */
    private fun isValid(parent: Tile, tile: Tile): Boolean {
        if (!tile.isExplored(civ)) return false
        if (!tile.isVisible(civ) && !parent.isVisible(civ)) return false
        if (tile.isImpassible()) return false
        if (tile.isWater && !(if (tile.isOcean) civ.tech.embarkedUnitsCanEnterOcean else civ.tech.unitsCanEmbark)) return false
        return routes.isPlotSafeForRoute(tile, true) || routes.isPlotSafeForRoute(parent, true)
    }
}
