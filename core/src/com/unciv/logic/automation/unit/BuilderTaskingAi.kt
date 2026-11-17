package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.min

/** A [BuilderDirective] picked for one builder: [weight] is the original's directive weight (CvHomelandAI::GetDirectiveWeight) of the build time and the move time */
class WeightedDirective(val directive: BuilderDirective, val weight: Int, val buildTurns: Int, val moveTurns: Int) {
    val tile get() = directive.tile
    val build get() = directive.build!!
}

/**
 *  CvBuilderTaskingAI (Vox Populi): what the builders (workers, and Great People that create an improvement) of [civ] should do. One
 *  instance per civilization and turn (held by [WorkerAutomation]); the constructor plans the turn ([update]): the planned routes [routes], then one
 *  civ-wide list of [BuilderDirective]s sorted by priority score ([directives]), improvements scored by [PlotBuildScorer.scorePlotBuild].
 *  A builder then takes the directive of the best weight for it ([evaluateBuilder], CvHomelandAI::GetWeightedDirectives for one unit).
 *
 *  What a turn plans, CvBuilderTaskingAI::GetImprovementDirectives for every plot of ours:
 *  - KEEP_IMPROVEMENT: an unpillaged improvement is scored as it stands, so that a build replaces it only when it scores more.
 *  - AddImprovingPlotsDirective: every improvement the plot takes. A tech one era ahead counts as possible (CvPlayer::canBuild with bTestEra:
 *    a tech whose era is at most ours + 1), as a directive that cannot be built yet; if its feature has to go first and that can be removed
 *    now, a REMOVE_FEATURE directive for the chop (a third of the score, the rest as potential). A build scoring 0 or less is dropped.
 *  - AddScrubFalloutDirectives (20000, always first) and AddRepairImprovementDirective.
 *  - FilterOnlyBestImprovements and UpdateGreatPersonDirectives ([BuilderDirectives]).
 *
 *  Unciv mapping and deviations:
 *  - The original loops over all plots of the map; plots we do not own score -1 (no improvement of the rules takes land; the return comes before
 *    the defense and culture bomb terms, so the original plans no Fort or Citadel outside our borders either), so only the plots of our
 *    cities are walked. The builds a unit can do are its own at selection time ([BuilderBuilds.canPerform]); the plan considers an improvement
 *    possible when the civilization may build it (WrongCiv, Replaced, Unbuildable, Obsolete, conditionals) and, if it is built instantly, a unit of
 *    ours creates it.
 *  - No new plan when the city count changes mid-turn: a city founded after the plan is in the next turn's.
 *  - Great Person improvements and the ones with "Will not be replaced by automated units" are never replaced, whoever plays (the original protects
 *    them for a human's automated workers only). The Celts' forests next to their cities stay unimproved ([faithFromUnimprovedForest],
 *    the original's code for it is not in Vox Populi). The human's options PLAYEROPTION_SAFE_AUTOMATION and PLAYEROPTION_LEAVE_FORESTS
 *    (here: not removing vegetation, unless the build connects the resource) are kept for a human's automated workers.
 *  - A not-yet-buildable improvement without a feature to chop gets no REMOVE_FEATURE directive. The original adds one without a build,
 *    which nothing executes.
 *  - Routes are [routes]' ([PlannedRoutes]: the original's planned routes with three purposes): its route and repair directives join the improvement
 *    ones with the route value as their score. A builder weighs a route directive by the time to build the whole planned route through its plot
 *    (CvBuilderTaskingAI::GetTotalRouteBuildTime).
 *  - Builders keep their plot across turns as the original's MISSIONAI_BUILD does ([MapUnit.builderTarget]): another builder skips it until
 *    that one is sent elsewhere. A human's builder only counts while it is automated.
 *  - Time and distance: the build time is [turnsLeft] (Unciv's work rate; a repair takes the shorter of repairing and rebuilding, as the original's
 *    getBuildTime); the distance is the plot distance, the unit must be able to reach the plot.
 */
class BuilderTaskingAi(val civ: Civilization, val routes: PlannedRoutes = PlannedRoutes(civ)) {
    private val ruleset = civ.gameInfo.ruleset
    private val rules = ruleset.ai
    val scorer = PlotBuildScorer(civ, routes)

    /** CvPlayerTraits::IsFaithFromUnimprovedForest (the Celts): a nation unique conditional on neighboring unimproved forests */
    private val faithFromUnimprovedForest = civ.nation.uniqueObjects.any { unique ->
        unique.modifiers.any { it.type == UniqueType.ConditionalNeighborTiles && "unimproved" in it.params[2] && FOREST in it.params[2] }
    }

    /** m_directives: this turn's directives, the highest priority score first, ties in the order they were added */
    var directives: List<BuilderDirective> = emptyList()
        private set
    private var directivesByTile: Map<Tile, List<BuilderDirective>> = emptyMap()
    /** IsBestDirectiveForPlot of every directive, computed when asked */
    private val bestForPlot = HashMap<BuilderDirective, Boolean>()

    init {
        update()
    }

    /** CvBuilderTaskingAI::Update: the route plan ([routes], made once a turn), then the directives */
    fun update() = timeThis("AI.BuilderDirectives") {
        routes.ensurePlanned()
        updateImprovementPlots()
    }

    //region Directives

    /** CvBuilderTaskingAI::UpdateImprovementPlots: the route and improvement directives, stably sorted by priority score */
    private fun updateImprovementPlots() {
        directives = (routes.directives + improvementDirectives()).sortedByDescending { it.priorityScore }
        directivesByTile = directives.groupBy { it.tile }
        bestForPlot.clear()
    }

    /**
     *  CvBuilderTaskingAI::GetImprovementDirectives: for every plot of ours, a KEEP directive for the improvement it has, the directives that
     *  improve it, scrub fallout and repair; then the filters. The builds are the possible ones ([possibleImprovements]).
     */
    private fun improvementDirectives(): List<BuilderDirective> {
        val possible = possibleImprovements()
        scorer.futureYields.update(possible)
        if (possible.isEmpty()) return emptyList()
        scorer.updateAllCityWorstPlots()

        val out = ArrayList<BuilderDirective>()
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!BuilderDirectives.shouldAnyBuilderConsiderPlot(civ, tile)) continue

            // Evaluate the improvement currently on the tile
            val existing = tile.tileImprovement
            if (existing != null && !tile.improvementIsPillaged) {
                val score = scorer.scorePlotBuild(tile, existing, null)
                out.add(BuilderDirective(BuilderDirective.Type.KeepImprovement, null, tile, false, true, score))
            }

            val owningCity = tile.getCity()
            addImprovingPlotsDirective(out, tile, owningCity, possible)
            addScrubFalloutDirectives(out, tile, owningCity)
            addRepairImprovementDirective(out, tile, owningCity)
        }
        return BuilderDirectives.filterOnlyBestImprovements(BuilderDirectives.updateGreatPersonDirectives(out))
    }

    /**
     *  The builds of GetImprovementDirectives' first loop (aPossibleBuilds): the improvements [civ] may build, a tech one era ahead included
     *  (CvPlayer::canBuild(NULL, build, true)), and that a unit can build: the ones a worker builds, and a Great Person improvement only when one of
     *  our units creates it instantly. Roads, removals, the repair and the cancel order are not improvements of a plot.
     */
    private fun possibleImprovements(): List<TileImprovement> {
        val instant = civ.units.getCivUnits().filter { it.getMatchingUniques(UniqueType.ConstructImprovementInstantly).any() }
            .flatMap { BuilderBuilds(it).instantImprovements.orEmpty() }.toSet()
        val context = civ.state
        // The original's workers have no build for the dig; our workers could build any improvement the plot takes, so it is for the units that excavate
        val excavators = civ.units.getCivUnits().any { it.hasUnique(UniqueType.CanExcavate) }
        return ruleset.tileImprovements.values.filter { improvement ->
            !improvement.isRoad() && !improvement.name.startsWith(Constants.remove)
                && improvement.name != Constants.repair && improvement.name != Constants.cancelImprovementOrder
                && (improvement.turnsToBuild != -1 || improvement in instant)
                && (!improvement.hasUnique(UniqueType.OneTimeExcavation) || excavators)
                && isBuildableAhead(ImprovementBuildingProblem.entries.filter { it.isRelevant(improvement, context) }, improvement)
        }
    }

    /** The build is possible given its [problems]: none, or only a missing tech whose era is at most our era + 1 (CvPlayer::canBuild with bTestEra) */
    private fun isBuildableAhead(problems: List<ImprovementBuildingProblem>, improvement: TileImprovement): Boolean {
        if (problems.isEmpty()) return true
        if (problems.any { it != ImprovementBuildingProblem.MissingTech }) return false
        val tech = improvement.techRequired?.let { ruleset.technologies[it] } ?: return false
        return (tech.era(ruleset)?.eraNumber ?: 0) <= civ.getAiEraNumber() + 1
    }

    /** What [planBuild] decided: the build, whether it can be done now, whether the feature in its way can be removed now (true without a feature) */
    private class PlannedBuild(val build: Build, val canBuild: Boolean, val canChop: Boolean)

    /**
     *  CvPlot::canBuild with the original's bTestEra and the feature removal of its builds: [improvement] on [tile], with the feature removed
     *  first when the plot does not take it with the feature (the removal improvement needs not to be known yet). Null if the plot cannot take it.
     */
    private fun planBuild(tile: Tile, improvement: TileImprovement): PlannedBuild? {
        val direct = problemsAfterEraAhead(improvement, tile)
        if (direct != null) return PlannedBuild(Build(improvement), ImprovementBuildingProblem.MissingTech !in direct, true)

        val feature = tile.terrainFeatures.lastOrNull() ?: return null
        val removal = ruleset.tileImprovements[Constants.remove + feature] ?: return null
        val cleared = problemsAfterEraAhead(improvement, scorer.withoutFeature(tile)) ?: return null
        val removalNow = tile.improvementFunctions.canBuildImprovement(removal, GameContext(civ, tile = tile))
        return PlannedBuild(Build(improvement, removal), ImprovementBuildingProblem.MissingTech !in cleared && removalNow, removalNow)
    }

    /** The problems that keep [improvement] from [tile], null when one of them is not just a tech one era ahead */
    private fun problemsAfterEraAhead(improvement: TileImprovement, tile: Tile): List<ImprovementBuildingProblem>? {
        val problems = tile.improvementFunctions.getImprovementBuildingProblems(improvement, GameContext(civ, tile = tile)).toList()
        return problems.takeIf { isBuildableAhead(it, improvement) }
    }

    /** "Do we have a special improvement here?" (CvBuilderTaskingAI::PlotHasSpecialImprovement): a Great Person's or one automation must not replace */
    private fun hasSpecialImprovement(improvement: TileImprovement) =
        improvement.hasUnique(UniqueType.GreatImprovement) || improvement.hasUnique(UniqueType.AutomatedUnitsWillNotReplace)

    /**
     *  CvBuilderTaskingAI::AddImprovingPlotsDirective: one directive per improvement the plot takes that scores above 0 - of the type "on
     *  resource" when it connects the plot's visible resource - and, for a build not possible yet whose feature can be chopped now, a
     *  directive for the chop.
     */
    private fun addImprovingPlotsDirective(out: MutableList<BuilderDirective>, tile: Tile, owningCity: City?, possible: List<TileImprovement>) {
        val existing = tile.tileImprovement
        val human = civ.isHuman()
        val settings = UncivGame.Current.settings
        // A special improvement, or a human's wish to keep what is there (PLAYEROPTION_SAFE_AUTOMATION)
        if (existing != null && (hasSpecialImprovement(existing) || (human && !settings.automatedWorkersReplaceImprovements))) return
        // If the city owning this plot is being razed, ignore this plot
        if (owningCity != null && owningCity.isBeingRazed) return
        val topFeature = tile.terrainFeatures.lastOrNull()
        if (faithFromUnimprovedForest && existing == null && topFeature == FOREST
            && tile.neighbors.any { it.isCityCenter() && it.getOwner() == civ }) return

        val resource = scorer.visibleResource(tile)
        for (improvement in possible) {
            if (improvement == existing) continue
            val planned = planBuild(tile, improvement) ?: continue
            val build = planned.build
            val connectsResource = resource != null && resource.isImprovedBy(improvement.name)
            // PLAYEROPTION_LEAVE_FORESTS
            if (!connectsResource && build.removeFirst != null && human && settings.stopAutomatedWorkersRemoveVegetation
                && topFeature != null && ruleset.terrains[topFeature]?.hasUnique(UniqueType.Vegetation) == true) continue

            val score = scorer.scorePlotBuild(tile, improvement, build)
            // If we're going backward, bail out!
            if (score.maxScore <= 0) continue

            val type = if (connectsResource) BuilderDirective.Type.BuildImprovementOnResource else BuilderDirective.Type.BuildImprovement
            out.add(BuilderDirective(type, build, tile, improvement.isGreatImprovement(), planned.canBuild, score))

            // If we can't build it yet, we may still want to prechop it
            if (!planned.canBuild && planned.canChop && build.removeFirst != null) {
                val chopScore = PlotBuildScore(score.score / 3, score.potentialScore + score.score * 2 / 3 - 1, score.unusedYieldScore, score.scorePenalty)
                out.add(BuilderDirective(BuilderDirective.Type.RemoveFeature, Build(build.removeFirst), tile, false, true, chopScore))
            }
        }
    }

    /** CvBuilderTaskingAI::AddScrubFalloutDirectives: fallout (a feature that nullifies the yields) on our plot, if we can remove it: SCRUB_FALLOUT */
    private fun addScrubFalloutDirectives(out: MutableList<BuilderDirective>, tile: Tile, owningCity: City?) {
        if (tile.getOwner() != civ) return
        if (owningCity != null && owningCity.isBeingRazed) return
        val feature = tile.terrainFeatures.lastOrNull() ?: return
        if (ruleset.terrains[feature]?.hasUnique(UniqueType.NullifyYields) != true) return
        val removal = ruleset.tileImprovements[Constants.remove + feature] ?: return
        if (!tile.improvementFunctions.canBuildImprovement(removal, GameContext(civ, tile = tile))) return
        val score = PlotBuildScore(rules.define(AiDefine.BUILDER_TASKING_BASELINE_SCRUB_FALLOUT))
        out.add(BuilderDirective(BuilderDirective.Type.RemoveFeature, Build(removal), tile, false, true, score, scrubsFallout = true))
    }

    /** CvBuilderTaskingAI::AddRepairImprovementDirective: the pillaged improvement of our plot, scored as the repaired improvement */
    private fun addRepairImprovementDirective(out: MutableList<BuilderDirective>, tile: Tile, owningCity: City?) {
        val existing = tile.tileImprovement ?: return
        if (!tile.improvementIsPillaged || tile.getOwner() != civ) return
        if (owningCity != null && owningCity.isBeingRazed) return
        val repair = Build(ruleset.tileImprovements[Constants.repair] ?: return)
        val score = scorer.scorePlotBuild(tile, existing, repair)
        if (score.maxScore > 0) out.add(BuilderDirective(BuilderDirective.Type.RepairImprovement, repair, tile, false, true, score))
    }

    //endregion
    //region Selection

    /**
     *  CvHomelandAI::GetWeightedDirectives for the one builder [unit]: the directive of the best weight (CvHomelandAI::GetDirectiveWeight of its
     *  priority score, the turns [unit] needs to build and the plot distance), null if there is none it can do and reach.
     *  A directive is considered when it can be built now, is the best one for its plot ([BuilderDirectives.isBestDirectiveForPlot]), [unit]
     *  can do it ([BuilderBuilds.canPerform]) and the plot is one the builder may consider ([shouldBuilderConsiderPlot]). A directive that cannot
     *  be better than the best found (its weight without time) is not looked at. Ties go to the earlier directive.
     *  The "already building" bail-out of the original is the caller's ([WorkerAutomation.executeWorkerMove]).
     */
    fun evaluateBuilder(unit: MapUnit): WeightedDirective? = timeThis("AI.EvaluateBuilder") {
        val builds = BuilderBuilds(unit)
        val claimed = otherBuilderTargets(unit)
        var best: WeightedDirective? = null
        for (directive in directives) {
            val build = directive.build ?: continue
            if (!directive.canBuild) continue
            val tile = directive.tile
            if (best != null && BuilderDirectives.directiveWeight(directive, 0, 0) <= best.weight) continue
            if (!shouldBuilderConsiderPlot(unit, tile, claimed)) continue
            if (!isBestDirectiveForPlot(directive)) continue
            if (!builds.canPerform(directive)) continue
            // A route is built along its whole planned route: the time of all of it (GetTotalRouteBuildTime)
            val buildTurns = (if (build.isRoute) routes.totalRouteBuildTime(unit, tile) else turnsLeft(unit, tile, build)) ?: continue
            val moveTurns = findTurnsAway(unit, tile)
            if (moveTurns < 0) continue
            val weight = BuilderDirectives.directiveWeight(directive, buildTurns, moveTurns)
            if (best != null && weight <= best.weight) continue
            if (tile != unit.getTile() && !unit.movement.canReach(tile)) continue
            best = WeightedDirective(directive, weight, buildTurns, moveTurns)
        }
        best
    }

    private fun isBestDirectiveForPlot(directive: BuilderDirective) =
        bestForPlot.getOrPut(directive) { BuilderDirectives.isBestDirectiveForPlot(directive, directivesByTile.getValue(directive.tile)) }

    /** Sends [unit] to [tile] (MISSIONAI_BUILD): no other builder considers it until [unit] gets another directive */
    fun claim(unit: MapUnit, tile: Tile) {
        unit.builderTarget = tile.position
    }

    /** The plots other builders of ours were sent to (CvPlayerAI::AI_plotTargetMissionAIs with MISSIONAI_BUILD) */
    private fun otherBuilderTargets(unit: MapUnit): Set<HexCoord> {
        val targets = HashSet<HexCoord>()
        for (other in civ.units.getCivUnits()) {
            if (other == unit) continue
            val target = other.builderTarget ?: continue
            if (civ.isHuman() && !other.isAutomated()) continue
            targets.add(target)
        }
        return targets
    }

    /**
     *  CvBuilderTaskingAI::ShouldBuilderConsiderPlot: not impassable, in the builder's domain, on its landmass unless it can embark, not the
     *  target of another builder ([claimed]), without plot danger, and with no other civilian of ours on it.
     */
    private fun shouldBuilderConsiderPlot(unit: MapUnit, tile: Tile, claimed: Set<HexCoord>): Boolean {
        if (tile.isImpassible() || tile.getBaseTerrain().impassable) return false
        val landUnit = unit.baseUnit.isLandUnit
        if (landUnit == tile.isWater) return false
        if (landUnit && tile.getContinent() != unit.getTile().getContinent() && !civ.tech.unitsCanEmbark) return false
        val atPlot = tile == unit.getTile()
        if (!atPlot && tile.position in claimed) return false
        val otherCivilian = tile.civilianUnit
        if (!atPlot && otherCivilian != null) return false  // another builder works here, or the plot is full (PLOT_UNIT_LIMIT)
        if (civ.ai.plotDanger.get(tile) > 0) return false
        return true
    }

    /** CvBuilderTaskingAI::FindTurnsAway: the plot distance, -1 for another landmass a land unit cannot reach */
    private fun findTurnsAway(unit: MapUnit, tile: Tile): Int {
        if (unit.baseUnit.isLandUnit && tile.getContinent() != unit.getTile().getContinent() && !civ.tech.unitsCanEmbark) return -1
        return unit.getTile().aerialDistanceTo(tile)
    }

    /**
     *  CvBuilderTaskingAI::GetTurnsToBuild: the turns [unit] needs to finish [build] on [tile], counting work already done there; 0 for an instant
     *  (Great Person) improvement. A repair takes the shorter of repairing and building the improvement again.
     */
    fun turnsLeft(unit: MapUnit, tile: Tile, build: Build): Int {
        if (build.isRepair) {
            if (tile.improvementInProgress == Constants.repair) return tile.turnsToImprovement
            val pillaged = tile.getImprovementToRepair() ?: return 0
            return min(build.improvement.getTurnsToBuild(civ, unit, tile), pillaged.getTurnsToBuild(civ, unit, tile))
        }
        var turns = BuilderDirectives.turnsToBuild(civ, unit, tile, build.improvement)
        if (build.removeFirst != null) turns += BuilderDirectives.turnsToBuild(civ, unit, tile, build.removeFirst)
        return turns
    }

    //endregion

    /** The builds [unit] can do: a worker's improvements, or the improvements a Great Person creates instantly */
    inner class BuilderBuilds(private val unit: MapUnit) {
        /** The improvements [unit] creates instantly (a Great Person's), null for a worker */
        val instantImprovements: List<TileImprovement>? =
            UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.ConstructImprovementInstantly).toList()
                .takeIf { it.isNotEmpty() }
                ?.let { uniques ->
                    val context = GameContext(civ, unit = unit)
                    val civResources = civ.getCivResourcesByName()
                    ruleset.tileImprovements.values.filter { improvement ->
                        uniques.any { improvement.matchesFilter(it.params[0], context) }
                            && improvement.getMatchingUniques(UniqueType.ConsumesResources).none { (civResources[it.params[1]] ?: 0) < it.params[0].toInt() }
                    }
                }

        private fun canBuildOn(improvement: TileImprovement, tile: Tile): Boolean {
            if (instantImprovements != null)
                return improvement in instantImprovements && tile.improvementFunctions.canBuildImprovement(improvement, GameContext(civ, unit = unit, tile = tile))
            if (improvement.hasUnique(UniqueType.OneTimeExcavation) && !unit.hasUnique(UniqueType.CanExcavate)) return false
            return unit.canBuildImprovement(improvement, tile) &&
                tile.improvementFunctions.canBuildImprovement(improvement, GameContext(civ, unit = unit, tile = tile))
        }

        /**
         *  CvBuilderTaskingAI::CanUnitPerformDirective: [unit] can do the build of [directive] now (without the plot checks of
         *  [shouldBuilderConsiderPlot]): its improvement on the plot, after the removal of the feature when the build comes with one.
         */
        fun canPerform(directive: BuilderDirective): Boolean {
            val build = directive.build ?: return false
            val tile = directive.tile
            return when {
                build.isRepair -> repair(tile) != null
                build.removeFirst != null -> removal(tile) == build.removeFirst && canBuildOn(build.improvement, scorer.withoutFeature(tile))
                build.isRemoval -> removal(tile) == build.improvement
                else -> canBuildOn(build.improvement, tile)
            }
        }

        /** The "Remove <top feature>" improvement if [unit] can build it on [tile] */
        fun removal(tile: Tile): TileImprovement? {
            if (instantImprovements != null) return null
            val feature = tile.terrainFeatures.lastOrNull() ?: return null
            val removal = ruleset.tileImprovements[Constants.remove + feature] ?: return null
            return removal.takeIf { canBuildOn(it, tile) }
        }

        /** The repair build (CvBuilderTaskingAI::m_eRepairBuild) if [unit] can repair [tile] */
        fun repair(tile: Tile): Build? {
            if (instantImprovements != null || !unit.cache.hasUniqueToBuildImprovements || tile.isCityCenter()) return null
            if (!tile.isPillaged()) return null
            val repair = ruleset.tileImprovements[Constants.repair] ?: return null
            return Build(repair)
        }
    }

    companion object {
        private const val FOREST = "Forest"
    }
}
