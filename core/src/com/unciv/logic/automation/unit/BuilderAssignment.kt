package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max

/**
 *  CvHomelandAI::ExecuteWorkerMoves (Vox Populi): the builders of a civilization get their directives together, once a turn. The best pair of
 *  (builder, directive) goes first, by the directive's weight for its best builder ([weigh], CvHomelandAI::GetWeightedDirectives: the priority score
 *  of [BuilderDirectives.directiveWeight] over the turns the builder takes to build there and to walk there); then what the pick changes is planned
 *  ([SBuilderState]) and the remaining directives are re-scored for it ([rescore]), and the next pair is picked, until the builders or the
 *  directives are gone. Builders left without a directive go to the city with the fewest builders assigned.
 *
 *  Instantiated once per civilization and turn by [WorkerAutomation] on its first builder, which plans all builders that can still move; the
 *  others read their pick ([pickFor]). A builder that appears later in the turn is planned by one more pass over the directives that are left.
 *
 *  Unciv mapping and deviations:
 *  - The original executes each pick in the loop (CvBuilderTaskingAI::ExecuteWorkerMove); here the pick is recorded and the builder carries it out
 *    when its own turn comes ([WorkerAutomation.executeWorkerMove]): the unit AI of the civilization moves one unit at a time, and a builder that
 *    heals or flees first keeps its pick for nothing. So a move that fails (the original: "we ran into danger on the way to the tile") does not
 *    drop the directive and re-plan, and a build is never finished before the re-scoring (the original's bFinishedBuilding, for the Great People's
 *    instant improvements): every pick counts as planned.
 *  - The original's ignored directives are the ones it dropped in the re-scoring anyway; a directive of a plot in m_workedPlots is a builder's with
 *    a directive from before the pass (set by the human UI: none).
 *  - The distance is the original's reach of the builder: [ExplorerAi.Reach] with the flags AI_ABORT_IN_DANGER and VISIBLE_ONLY, 7 turns
 *    (the original counts turns from 0, [ExplorerAi.ReachablePlot.pathLength] from 1), a turn more when the builder ends up without moves.
 *  - The danger of a plot to a builder is Unciv's plot danger ([PlotDanger.get]), the same for every unit (the original's is the unit's own, with its
 *    promotions and health); the defender's too.
 *  - The pass re-scores what [SBuilderState] holds and what Unciv's rules read from it: the planned improvements (adjacency of the Moai, the defense
 *    and the combat bonus of [PlotDefense], the combat bonus) and the extra resources. mChangedPlotFeatures and the re-scoring for it are none (only the
 *    vanilla Celts and Vox Populi's adjacent feature yields read it), so are the plots next to a planned no-two-adjacent improvement (none in the rules),
 *    the canal and the culture-bomb owner of a plot nobody owns (a plan is for our plots), CvBuilderTaskingAI::UpdateCityWorstPlots (the original
 *    stores a build it never set: the worst plot stays the one of the start of the turn).
 *  - Worker regions (CvHomelandAI::PlanWorkerDistribution, [WorkerRegions]): for an AI civilization at peace a builder outside the region it was
 *    allocated to walks to the region's first city before any directive is weighed ([regionTarget]; the original's ExecuteMoveToTarget, which
 *    is carried out when the builder's own turn comes, and counted as done when a path exists), and the others only weigh the directives of their
 *    region (bConsiderRegions of CvHomelandAI::GetWeightedDirectives). The regions are planned once, with the pass's first builder.
 *  - None: the plots of builders that were assigned
 *    before the pass (the human's UI sets them), builders that are not automated (a human's: only automated builders take part, and the original's
 *    recommendation for the others is UI), the naval sentry points (nothing reads them), CvHomelandAI::MoveCivilianToGarrison (the fallback of
 *    [WorkerAutomation] is [CivilianSafety.moveToSafety], which prefers cities).
 */
class BuilderAssignment(private val civ: Civilization, private val ai: BuilderTaskingAi, directives: List<BuilderDirective> = ai.directives) {
    /** A builder's pick: [weight], [buildTurns] and [moveTurns] are the ones [weigh] found for it */
    class Pick(val unit: MapUnit, val directive: BuilderDirective, val weight: Int, val buildTurns: Int, val moveTurns: Int) {
        val tile get() = directive.tile
        val build get() = directive.build!!
    }

    /** CvHomelandAI::GetWeightedDirectives' entry: the best builder for [directive], null with [NONE] as weight for none */
    private class Weighted(val directive: BuilderDirective, val builder: MapUnit?, val weight: Int, val buildTurns: Int, val moveTurns: Int)

    private val scorer = ai.scorer

    /** What the pass plans (the original's sState), read by [PlotBuildScorer.scorePlotBuild] */
    val state = SBuilderState()

    /** The directives still open this turn (the original's aDistanceWeightedDirectives, without the weights), re-scored for the picks so far */
    var remaining: List<BuilderDirective> = directives
        private set

    private val picks = LinkedHashMap<MapUnit, Pick>()
    private val planned = HashSet<MapUnit>()
    private val leftovers = HashMap<MapUnit, City>()
    private val cityWorkers = LinkedHashMap<City, Int>().also { map -> for (city in civ.cities) map[city] = 0 }
    private val builds = HashMap<MapUnit, BuilderTaskingAi.BuilderBuilds>()
    private val reaches = HashMap<MapUnit, Map<Tile, ExplorerAi.ReachablePlot>>()
    private val combatBonus = PlotDefense.combatBonusImprovement(civ)

    /**
     *  The sentry points the picks made (CvHomelandAI::m_TargetedSentryPoints): a plot outside our borders or next to another civilization's land
     *  where a builder works is to be guarded, with the priority score of the directive as weight. [HomelandAi.findTargets] adds them.
     */
    val sentryPoints = ArrayList<HomelandTarget>()

    /** The picks of the builders planned so far, in the order they were made */
    val allPicks: Collection<Pick> get() = picks.values

    /** CvHomelandAI::PlanWorkerDistribution: how the builders are spread over the land regions this turn */
    val regions = WorkerRegions(civ, ai.routes)

    private val regionWalkers = HashMap<MapUnit, Tile>()

    /** The first city of the region a builder that is not in its region goes to (CvHomelandAI::ExecuteWorkerMoves, the region move), null if it has none */
    fun regionTarget(unit: MapUnit): Tile? = regionWalkers[unit]

    /** The pick of [unit], null if it has none; plans [unit] (with the builders that can move: [pending]) first when it is not planned yet */
    fun pickFor(unit: MapUnit, pending: () -> List<MapUnit>): Pick? {
        if (unit !in planned) assign((pending() + unit).distinct())
        return picks[unit]
    }

    /** The city [unit] was sent to for want of a directive (CvHomelandAI::ExecuteWorkerMoves, the leftover builders), null if none */
    fun leftoverCity(unit: MapUnit): City? = leftovers[unit]

    /**
     *  The pass: [workers] (the builders that can move and are not planned yet) pick directives in turn. Returns the new picks in the order made.
     */
    fun assign(workers: List<MapUnit>): List<Pick> = timeThis("AI.WorkerAssign") {
        val fresh = workers.filter { planned.add(it) }
        val processed = HashSet<MapUnit>()
        val newPicks = ArrayList<Pick>()

        // Check if any worker is in the wrong region, if so they should move to the right one
        if (regions.considerRegions) for (unit in fresh) {
            if (regions.isAtAllocatedRegion(unit)) continue
            val target = regions.targetPlot(unit) ?: continue
            if (unit.getTile() == target || !unit.movement.canReach(target)) continue
            regionWalkers[unit] = target
            processed.add(unit)
        }

        // Loop through all the directives sorted by weighted score and distance (see GetDirectiveWeight)
        var weighted = weigh(remaining, fresh, processed)
        while (fresh.size > processed.size) {
            val front = weighted.firstOrNull() ?: break
            val builder = front.builder ?: break
            val directive = front.directive
            val tile = directive.tile

            // Need to save the state of the plot in case we finish the improvement this turn
            val oldImprovement = if (tile.improvementIsPillaged) null else tile.tileImprovement
            val oldResource = scorer.visibleResource(tile)

            // We may have planned an improvement that we can't build yet, but should still update other plots as if we've built it
            val canBuild = directive.canBuild
            if (canBuild) {
                val pick = Pick(builder, directive, front.weight, front.buildTurns, front.moveTurns)
                picks[builder] = pick
                newPicks.add(pick)
                processed.add(builder)
                addSentryPoint(directive)
            }

            // We may want to recalculate some of the other directive scores (kept for a builder planned later when this was the last one)
            remaining = rescore(weighted, directive, canBuild, oldImprovement, oldResource)
            if (fresh.size > processed.size) weighted = weigh(remaining, fresh, processed)
        }

        sendLeftoversToCities(fresh.filter { it !in processed })
        newPicks
    }

    //region Weighing

    /**
     *  CvHomelandAI::GetWeightedDirectives: every directive with its best builder among [workers] that are not [processed], sorted by weight, the
     *  best first (ties keep the order of [directives]). A directive without a build, one that cannot be better than the best found (its weight without
     *  time) and one that is not the best of its plot ([BuilderDirectives.isBestDirectiveForPlot]) has no builder. A builder must be able to
     *  do the build (a build of a later era included: [BuilderTaskingAi.BuilderBuilds.canPerform] with the era test), may consider the plot
     *  ([BuilderTaskingAi.shouldBuilderConsiderPlot]), not be in too much danger there (unless the best defender of the plot is safe), and reach it.
     */
    private fun weigh(directives: List<BuilderDirective>, workers: List<MapUnit>, processed: Set<MapUnit>): List<Weighted> {
        val onPlot = directives.groupBy { it.tile }
        val out = ArrayList<Weighted>(directives.size)
        var bestWeighted = NONE

        for (directive in directives) {
            val build = directive.build
            // If the score is lower than the best weighted score we have found, it will never be better than the best weighted score
            if (build == null || BuilderDirectives.directiveWeight(directive, 0, 0) <= bestWeighted
                || !BuilderDirectives.isBestDirectiveForPlot(directive, onPlot.getValue(directive.tile))) {
                out.add(Weighted(directive, null, NONE, 0, 0))
                continue
            }
            val tile = directive.tile

            // Loop over all workers to find the one closest to the directive plot (including build time)
            var bestBuilder: MapUnit? = null
            var bestBuilderWeighted = NONE
            var bestBuildTurns = 0
            var bestMoveTurns = 0
            for (unit in workers) {
                if (unit in processed) continue
                if (regions.considerRegions && regions.targetPlot(unit) != null && !regions.isAtAllocatedRegion(unit, tile)) continue
                if (!ai.shouldBuilderConsiderPlot(unit, tile) || !builds(unit).canPerform(directive, testEra = true)) continue
                if (isTooDangerous(unit, tile)) continue

                // e.g., prisoners of war need longer to build ...
                val buildTurns = (if (build.isRoute) ai.routes.totalRouteBuildTime(unit, tile) else ai.turnsLeft(unit, tile, build)) ?: continue

                // If the weighted score is lower than the best weighted score we have found with 0 move steps, it will never be better
                if (BuilderDirectives.directiveWeight(directive, buildTurns, 0) <= max(bestBuilderWeighted, bestWeighted)) continue

                val moveTurns = moveTurns(unit, tile) ?: continue
                val weight = BuilderDirectives.directiveWeight(directive, buildTurns, moveTurns)
                if (weight > bestBuilderWeighted) {
                    bestBuilderWeighted = weight
                    bestBuilder = unit
                    bestBuildTurns = buildTurns
                    bestMoveTurns = moveTurns
                }
            }

            if (bestBuilderWeighted > bestWeighted) bestWeighted = bestBuilderWeighted
            out.add(Weighted(directive, bestBuilder, bestBuilderWeighted, bestBuildTurns, bestMoveTurns))
        }
        return out.sortedByDescending { it.weight }
    }

    private fun builds(unit: MapUnit) = builds.getOrPut(unit) { ai.BuilderBuilds(unit) }

    /**
     *  CvHomelandAI::GetWeightedDirectives' danger rule: a plot whose danger is above the builder's health is for it only when our best defender
     *  there is safe (its danger at most half its health).
     */
    private fun isTooDangerous(unit: MapUnit, tile: Tile): Boolean {
        val danger = civ.ai.plotDanger.get(tile)
        if (danger <= unit.health) return false
        val defender = tile.militaryUnit?.takeIf { it.civ == civ } ?: return true
        return danger > defender.health / 2
    }

    /** CvHomelandAI::GetBuilderNumTurnsAway: 0 on the plot, else the turns of the path in the builder's reach, plus one when it ends with no moves; null if out of reach */
    private fun moveTurns(unit: MapUnit, tile: Tile): Int? {
        if (tile == unit.getTile()) return 0
        val plot = reaches.getOrPut(unit) {
            ExplorerAi.Reach(unit, abortInDanger = true, visibleOnly = true).within(BUILDER_MAX_TURNS + 1).associateBy { it.tile }
        }[tile] ?: return null
        return plot.pathLength - 1 + (if (plot.movesLeft == 0) 1 else 0)
    }

    //endregion
    //region Re-scoring

    /** BuilderDirective::operator==: the same build on the same plot */
    private fun same(first: BuilderDirective, second: BuilderDirective) =
        first.tile == second.tile && first.build?.improvement == second.build?.improvement

    /** The build removes the plot's [feature] (CvBuildInfo::isFeatureRemove) */
    private fun removes(build: Build, feature: String) =
        build.removeFirst?.name == Constants.remove + feature || (build.isRemoval && build.improvement.name == Constants.remove + feature)

    /**
     *  The body of CvHomelandAI::ExecuteWorkerMoves after a pick: what [directive] changes on its plot is planned in [state] (extra resources, the
     *  improvement; only for a directive that can be built now, [canBuild]), then every directive of [weighted] is carried over, pruned or scored again
     *  for it; the Great Person penalties are set again and the list sorted by priority score.
     *  - Same plot: all other directives go, unless the pick cannot be built yet. Then a feature removal that the pick's improvement needs, and a
     *    Great Person's improvement, go, and any other build gets ⅔ of its base score and its potential score plus ⅓ ("if we are planning to build something
     *    else here in the future"); one left with no score goes. A Great Person's directive and a route can be built together and stay.
     *  - A plot with the resource the pick connects or disconnects: scored again for the new count.
     *  - Within 2 plots of a pick that changes the defense or the damage to adjacent enemies, and within the range of a combat bonus improvement
     *    that changes (Polynesia's Moai): scored again if its improvement is of that kind. Next to a changed improvement: scored again.
     *  - Next to a route that is built or repaired: a route directive gets its potential score in its score.
     */
    private fun rescore(
        weighted: List<Weighted>, directive: BuilderDirective, canBuild: Boolean, oldImprovement: TileImprovement?, oldResource: TileResource?
    ): List<BuilderDirective> {
        val tile = directive.tile
        val build = directive.build!!
        var improvement = build.createdImprovement
        if (improvement == null && directive.type == BuilderDirective.Type.RepairImprovement) improvement = tile.tileImprovement
        val feature = tile.terrainFeatures.lastOrNull()?.takeUnless { removes(build, it) }

        // Resource considerations: the resource the plot has is connected or disconnected (no improvement of the rules creates one)
        var resourceStateChanged = false
        if (canBuild && oldResource != null) {
            val oldConnects = oldImprovement != null && oldResource.isImprovedBy(oldImprovement.name)
            val newConnects = improvement != null && oldResource.isImprovedBy(improvement.name)
            val amount = tile.resourceAmount
            if (!oldConnects && newConnects) {
                state.extraResources.merge(oldResource.name, amount, Int::plus)
                resourceStateChanged = true
            } else if (oldConnects && !newConnects) {
                state.extraResources.merge(oldResource.name, -amount, Int::plus)
                resourceStateChanged = true
            }
        }

        // Improvement considerations
        val improvementStateChanged = canBuild && directive.type in IMPROVEMENT_TYPES
        if (improvementStateChanged) state.changedPlotImprovements[tile] = improvement

        // Defense considerations
        val defenseStateChanged = improvementStateChanged && (defenseModifier(improvement) != defenseModifier(oldImprovement)
            || nearbyDamage(improvement) != nearbyDamage(oldImprovement))
        val bonus = combatBonus
        val changedBonus = if (improvementStateChanged && bonus != null && (bonus.improvement == improvement || bonus.improvement == oldImprovement)) bonus else null

        // Road considerations
        val routeStateChanged = canBuild && directive.type in ROUTE_TYPES

        val out = ArrayList<BuilderDirective>(weighted.size)
        for (entry in weighted) {
            var other = entry.directive
            val otherTile = other.tile
            val otherBuild = other.build
            val otherOld = if (otherTile.improvementIsPillaged) null else otherTile.tileImprovement
            var otherImprovement = if (otherBuild != null) otherBuild.createdImprovement else otherOld
            if (otherImprovement == null && otherBuild?.isRepair == true && otherTile.improvementIsPillaged) otherImprovement = otherTile.tileImprovement
            var updated = false
            val distance = tile.aerialDistanceTo(otherTile)

            // Pruning
            if (otherTile == tile) {
                val simultaneous = (directive.isGreatPerson && other.type == BuilderDirective.Type.BuildRoute)
                    || (other.isGreatPerson && directive.type == BuilderDirective.Type.BuildRoute)
                // Prune directives that are in the same plot
                if ((canBuild && !simultaneous) || same(directive, other)) continue

                // Don't remove features that are needed for future better improvements
                if (feature != null && otherBuild != null && removes(otherBuild, feature) && improvement?.canBeBuiltOn(feature) == true) continue

                // Don't build GP improvements where we want to build better things
                if (!simultaneous && otherImprovement?.isGreatImprovement() == true) continue

                if (!simultaneous && otherImprovement != null && otherImprovement != otherOld) {
                    val score = scorer.scorePlotBuild(tile, otherImprovement, otherBuild, state)
                    // If we are planning to build something else here in the future, downscale the priority of this by 1/3
                    val downScaled = other.plotBuildScore.copy(score = score.score * 2 / 3, potentialScore = score.potentialScore + score.score / 3)
                    if (downScaled.maxScore <= 0) continue
                    other = other.withScore(downScaled)
                    updated = true
                }
            }

            // Reevaluating
            if (otherImprovement != null && resourceStateChanged && !updated) {
                // Connecting a resource may reduce or increase the value of connecting more instances of the same resource
                if (scorer.visibleResource(otherTile) == oldResource) {
                    other = scoredAgain(other, otherImprovement, otherBuild)
                    updated = true
                }
            }

            if (otherImprovement != null && defenseStateChanged && !updated && (distance == 1 || distance == 2)) {
                // If we are building or removing a defensive improvement, recalculate nearby defensive structure changes
                if (defenseModifier(otherImprovement) != 0 || nearbyDamage(otherImprovement) != 0) {
                    other = scoredAgain(other, otherImprovement, otherBuild)
                    updated = true
                }
            }

            if (otherImprovement != null && changedBonus != null && !updated
                && distance >= 1 && distance <= changedBonus.range * 2 && changedBonus.improvement == otherImprovement) {
                other = scoredAgain(other, otherImprovement, otherBuild)
                updated = true
            }

            if (otherImprovement != null && improvementStateChanged && !updated && distance == 1) {
                // If we are building or removing an improvement, give all adjacent plot directives yield increases/decreases from this change
                other = scoredAgain(other, otherImprovement, otherBuild)
                updated = true
            }

            if (routeStateChanged && distance == 1 && otherBuild?.isRoute == true) {
                // If we are building a road, make sure all neighboring road builds get their value set to their full value
                val score = other.plotBuildScore
                other = other.withScore(score.copy(score = score.score + score.potentialScore, potentialScore = 0))
            }

            out.add(other)
        }
        return BuilderDirectives.updateGreatPersonDirectives(out).sortedByDescending { it.priorityScore }
    }

    /** The directive with the score of its build on the planned [state] */
    private fun scoredAgain(directive: BuilderDirective, improvement: TileImprovement, build: Build?): BuilderDirective {
        val score = scorer.scorePlotBuild(directive.tile, improvement, build, state)
        return if (score != directive.plotBuildScore) directive.withScore(score) else directive
    }

    private fun defenseModifier(improvement: TileImprovement?) = improvement?.let { PlotDefense.defenseModifier(it) } ?: 0

    private fun nearbyDamage(improvement: TileImprovement?) = improvement?.let { PlotDefense.nearbyEnemyDamage(it) } ?: 0

    //endregion

    /**
     *  A pick on a plot outside our borders or next to another civilization's land makes a sentry point there (the original's check is
     *  IsAdjacentOwnedByTeamOtherThan with every flag: any land of another team).
     */
    private fun addSentryPoint(directive: BuilderDirective) {
        val tile = directive.tile
        if (tile.getOwner() == civ && tile.neighbors.none { val owner = it.getOwner(); owner != null && owner != civ }) return
        if (tile.isWater) return
        sentryPoints.add(HomelandTarget(HomelandTargetType.SentryPoint, tile, directive.priorityScore))
    }

    /**
     *  CvHomelandAI::ExecuteWorkerMoves, the leftover builders: a builder (a work boat too) that has no directive (and is no combat unit) goes to
     *  the city with the fewest builders assigned so far, the closest of them to it.
     */
    private fun sendLeftoversToCities(units: List<MapUnit>) {
        for (unit in units) {
            if (unit.isMilitary() || !(unit.hasUnique(UniqueType.BuildImprovements) || unit.cache.hasUniqueToCreateWaterImprovements)) continue
            var leastAssigned = Int.MAX_VALUE
            var closest = Int.MAX_VALUE
            var best: City? = null
            for ((city, assigned) in cityWorkers) {
                val distance = unit.getTile().aerialDistanceTo(city.getCenterTile())
                if (assigned < leastAssigned || (assigned == leastAssigned && distance < closest)) {
                    leastAssigned = assigned
                    closest = distance
                    best = city
                }
            }
            if (best == null) continue
            leftovers[unit] = best
            cityWorkers[best] = cityWorkers.getValue(best) + 1
        }
    }

    companion object {
        /** The weight of a directive nobody can build ("INT_MIN") */
        const val NONE = Int.MIN_VALUE

        /** CvHomelandAI::ExecuteWorkerMoves' BUILDER_MAX_TURNS: the builder's reach */
        const val BUILDER_MAX_TURNS = 7

        private val IMPROVEMENT_TYPES = setOf(
            BuilderDirective.Type.BuildImprovement, BuilderDirective.Type.BuildImprovementOnResource, BuilderDirective.Type.RepairImprovement
        )
        private val ROUTE_TYPES = setOf(BuilderDirective.Type.BuildRoute, BuilderDirective.Type.RepairRoute)
    }
}
