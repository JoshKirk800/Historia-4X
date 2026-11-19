package com.unciv.logic.automation.unit

import com.unciv.logic.map.HexCoord
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import kotlin.math.roundToInt

/**
 *  One thing a builder can do on a tile (the original's CvBuildInfo). The original's builds remove the plot feature as part of
 *  the improvement (a Farm on a Forest); Unciv removes it with a separate "Remove <feature>" improvement queued before, kept in
 *  [removeFirst]. A route build has the road or railroad as [improvement]; a repair has [Constants.repair].
 */
class Build(val improvement: TileImprovement, val removeFirst: TileImprovement? = null) {
    val isRepair get() = improvement.name == Constants.repair
    val isRemoval get() = improvement.name.startsWith(Constants.remove)
    val isRoute get() = improvement.isRoad()

    /** The original's CvBuildInfo::getImprovement: the improvement the build creates; none for a repair, a removal and a route */
    val createdImprovement: TileImprovement? get() = if (isRepair || isRemoval || isRoute) null else improvement
}

/**
 *  CvBuilderTaskingAI::m_assignedDirectives (GetAssignedDirective/SetAssignedDirective): the directive a builder was sent to execute, of which
 *  CvUnit::DoFinishBuildIfSafe compares the plot and the build ([improvement] is the improvement the build creates, a route's road). Kept per
 *  builder in [com.unciv.logic.civilization.Civilization.assignedBuilderDirectives] until the next turn's directives are planned.
 */
class AssignedBuilderDirective(val position: HexCoord, val improvement: String)

/**
 *  CvBuilderTaskingAI's PlotBuildScore: what a build on a tile is worth. [score] the base score, [potentialScore] what it may give in the
 *  future, [unusedYieldScore] the part of the yield score that no city will probably use, [scorePenalty] a down-priority.
 */
data class PlotBuildScore(
    val score: Int = 0,
    val potentialScore: Int = 0,
    val unusedYieldScore: Int = 0,
    val scorePenalty: Int = 0
) {
    /** GetPriorityScore: the sort key of a directive; a third of the potential counts */
    val priorityScore get() = score + potentialScore / 3 - scorePenalty - unusedYieldScore
    /** GetMaxScore */
    val maxScore get() = score + potentialScore
}

/**
 *  CvBuilderTaskingAI's BuilderDirective: what a builder should do on [tile], planned once per turn by [BuilderTaskingAi].
 *  [build] is null for a [Type.KeepImprovement], which is never executed, only compared with the builds for the same tile.
 *  [canBuild] is false for a build whose tech is not known yet (the plan reaches one era ahead) or whose feature cannot be removed yet.
 *  [isGreatPerson]: the improvement is a Great Person's. [scrubsFallout]: the build removes fallout (CvBuildInfo::isFeatureRemove(FEATURE_FALLOUT)).
 */
class BuilderDirective(
    val type: Type,
    val build: Build?,
    val tile: Tile,
    val isGreatPerson: Boolean,
    val canBuild: Boolean,
    val plotBuildScore: PlotBuildScore,
    val scrubsFallout: Boolean = false
) {
    enum class Type {
        /** BUILD_IMPROVEMENT_ON_RESOURCE: an improvement that connects the visible resource of the tile */
        BuildImprovementOnResource,
        BuildImprovement,
        BuildRoute,
        RepairImprovement,
        RepairRoute,
        /** REMOVE_FEATURE: a chop ahead of a build that is not possible yet, or the scrub of fallout */
        RemoveFeature,
        /** REMOVE_ROAD: a road or railroad we pay maintenance for where no route is planned */
        RemoveRoad,
        KeepImprovement
    }

    val priorityScore get() = plotBuildScore.priorityScore

    fun withPenalty(penalty: Int) = withScore(plotBuildScore.copy(scorePenalty = penalty))

    /** The same directive with another score (the assignment pass re-scores directives for what it has planned) */
    fun withScore(score: PlotBuildScore) = BuilderDirective(type, build, tile, isGreatPerson, canBuild, score, scrubsFallout)
}

/** Selection and filters of the per-turn directive list (CvHomelandAI's directive weight, CvBuilderTaskingAI's filters) */
object BuilderDirectives {
    /** The square root of INT_MAX, precomputed in the original */
    private const val SQRT_INT_MAX = 46340

    /** CvBuilderTaskingAI::ShouldAnyBuilderConsiderPlot: seen, not a city, not another major's land; not the marker of a ruin's improvement either */
    fun shouldAnyBuilderConsiderPlot(civ: Civilization, tile: Tile): Boolean {
        if (tile.isCityCenter() || tile.isMarkedForCreatesOneImprovement() || !tile.isExplored(civ)) return false
        val owner = tile.getOwner()
        return owner == null || owner == civ || owner.isCityState
    }

    /**
     *  CvBuilderTaskingAI::GetTurnsToBuild: the turns [unit] needs to build [improvement] on [tile], counting work already done there; 0 for an
     *  instant (Great Person) improvement. Without a unit the original returns the total build time at a work rate of 1; here that is the
     *  improvement's turns at the game speed and era, without a worker's bonus.
     */
    fun turnsToBuild(civ: Civilization, unit: MapUnit?, tile: Tile, improvement: TileImprovement): Int = when {
        tile.improvementInProgress == improvement.name -> tile.turnsToImprovement
        improvement.turnsToBuild == -1 -> 0
        unit != null -> improvement.getTurnsToBuild(civ, unit, tile)
        else -> (civ.gameInfo.speed.improvementBuildLengthModifier * civ.gameInfo.getStartingEra().buildPercent / 100f * improvement.turnsToBuild)
            .roundToInt().coerceAtLeast(1)
    }

    /**
     *  CvHomelandAI.cpp: GetDirectiveWeight, static. A Great Person's improvement gets 100 × (priority score - 100 × move turns), so it goes
     *  first (its penalty keeps it off plots where something better can be built). Else the priority score / 10 squared over
     *  1 + build turns + move turns; a negative score is made worse by the time. Large scores divide first and stop at Int.MAX_VALUE.
     */
    fun directiveWeight(directive: BuilderDirective, buildTurns: Int, moveTurns: Int): Int {
        var score = directive.priorityScore
        if (directive.isGreatPerson) return 100 * (score - 100 * moveTurns)

        score /= 10
        val buildTime = buildTurns + moveTurns
        if (score > SQRT_INT_MAX) {
            val divideFirst = score / (1 + buildTime)
            if (divideFirst > Int.MAX_VALUE / score) return Int.MAX_VALUE
            return divideFirst * score
        }
        return if (score >= 0) score * score / (1 + buildTime) else -(score * score) * (1 + buildTime)
    }

    /**
     *  CvHomelandAI.cpp: IsBestDirectiveForPlot, static: no other buildable directive for the same plot is better by maximum score. [onPlot]
     *  are the directives of the plot, [directive] among them. A route and a non-route build do not compete, a Great Person improvement does not
     *  stand in the way, fallout is scrubbed first. A KEEP directive (no build) is never executed.
     */
    fun isBestDirectiveForPlot(directive: BuilderDirective, onPlot: List<BuilderDirective>): Boolean {
        val build = directive.build ?: return false
        val directiveScore = directive.plotBuildScore.maxScore
        for (other in onPlot) {
            val otherBuild = other.build
            if (otherBuild != null && build.improvement == otherBuild.improvement) continue
            if (other.isGreatPerson) continue
            if (!other.canBuild) continue
            if (build.isRoute != (otherBuild?.isRoute == true)) continue
            if (other.scrubsFallout && !directive.scrubsFallout) return false
            if (other.plotBuildScore.maxScore > directiveScore) return false
        }
        return true
    }

    /**
     *  CvBuilderTaskingAI.cpp: FilterOnlyBestImprovements: drops a directive when another buildable directive for the same plot that creates an
     *  improvement which is not a Great Person's has at least its maximum score. Great Person improvements stay, so does everything on a plot
     *  where nothing beats it; a tie drops both, as in the original.
     */
    fun filterOnlyBestImprovements(directives: List<BuilderDirective>): List<BuilderDirective> {
        val byTile = directives.groupBy { it.tile }
        return directives.filter { directive ->
            val improvement = directive.build?.createdImprovement ?: if (directive.build == null) directive.tile.tileImprovement else null
            if (improvement != null && improvement.isGreatImprovement()) return@filter true
            val maxScore = directive.plotBuildScore.maxScore
            byTile.getValue(directive.tile).none { other ->
                other !== directive && !sameBuild(directive, other)
                    && other.plotBuildScore.maxScore >= maxScore && other.canBuild
                    && (other.build?.createdImprovement ?: if (other.build == null) other.tile.tileImprovement else null)
                        ?.let { !it.isGreatImprovement() } == true
            }
        }
    }

    /** BuilderDirective::operator==: the same build (the original's build is the improvement, the feature removal comes with it) on the same plot (always in use here) */
    private fun sameBuild(first: BuilderDirective, second: BuilderDirective) = first.build?.improvement == second.build?.improvement

    /**
     *  CvBuilderTaskingAI.cpp: UpdateGreatPersonDirectives: a Great Person improvement directive gets the best priority score among the
     *  other directives for its plot (the non-Great-Person ones) as penalty, so that it goes where nothing better can be built.
     */
    fun updateGreatPersonDirectives(directives: List<BuilderDirective>): List<BuilderDirective> {
        val byTile = directives.groupBy { it.tile }
        return directives.map { directive ->
            val improvement = directive.build?.createdImprovement
            if (improvement == null || !improvement.isGreatImprovement()) directive
            else directive.withPenalty(bestNonGreatPersonScoreOnPlot(byTile.getValue(directive.tile)))
        }
    }

    /** GetBestNonGPDirectiveScoreInPlot: the best priority score of the directives of one plot that do not create a Great Person's improvement, at least 0 */
    private fun bestNonGreatPersonScoreOnPlot(onPlot: List<BuilderDirective>): Int {
        var best = 0
        for (other in onPlot) {
            if (other.build?.createdImprovement?.isGreatImprovement() == true) continue
            if (other.priorityScore > best) best = other.priorityScore
        }
        return best
    }
}
