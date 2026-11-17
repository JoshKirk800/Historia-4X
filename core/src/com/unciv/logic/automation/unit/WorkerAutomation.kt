package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.view.MapUnitView
import com.unciv.view.shortDisplayName
import yairm210.purity.annotations.Readonly

/**
 *  Builder dispatch of the original's homeland AI: CvHomelandAI::ExecuteWorkerMoves / ExecuteWorkerMove (land builders and Great People
 *  creating an improvement), MoveCivilianToSafety and PlotWorkerSeaMoves (work boats). What to build where is [BuilderTaskingAi]; who builds what,
 *  [BuilderAssignment]: the first builder of a turn that gets here has all the builders that can move planned together, the others carry out their pick.
 *
 *  Instantiated once per civilization and turn by [Civilization.getWorkerAutomation].
 *
 *  @param civInfo       The Civilization - data common to all automated builders is cached once per Civ
 *  @param cachedForTurn The turn number this was created for - a recreation of the instance is forced on different turn numbers
 */
class WorkerAutomation(val civInfo: Civilization, val cachedForTurn: Int) {
    val roadToAutomation = RoadToAutomation(civInfo)
    /** The route plan of the turn (CvBuilderTaskingAI's planned routes): made on first use, read by the directives, the scoring and plot buying */
    val plannedRoutes by lazy { PlannedRoutes(civInfo) }
    val builderTaskingAi by lazy { BuilderTaskingAi(civInfo, plannedRoutes) }
    private val assignmentOfTurn = lazy { BuilderAssignment(civInfo, builderTaskingAi) }
    /** The builders' directives of the turn (CvHomelandAI::ExecuteWorkerMoves), planned on the first builder that needs one */
    val assignment: BuilderAssignment by assignmentOfTurn

    /** The sentry points the builders' picks made (CvHomelandAI::m_TargetedSentryPoints), none if no builder was planned this turn */
    val sentryPoints: List<HomelandTarget> get() = if (assignmentOfTurn.isInitialized()) assignment.sentryPoints else emptyList()

    /** CvHomelandAI's AI_HOMELAND_TARGET_NAVAL_RESOURCE targets of this turn (FindHomelandTargets) */
    private val navalResourceTargets: List<Tile> by lazy { findNavalResourceTargets() }

    /**
     *  CvHomelandAI::ExecuteWorkerMoves for one builder: on a plot under immediate threat without a defender, move to safety; else carry out the
     *  directive the assignment gave it ([executeWorkerMove]); with none, go to the city the assignment found for it, else to the safest plot
     *  nearby (ignoring units).
     */
    fun automateWorkerAction(unitView: MapUnitView): Unit =
        timeThis("AI.WorkerMoves") {
            val unit = unitView.getUnit()
            val tile = unit.getTile()
            if (civInfo.ai.plotDanger.isUnderImmediateThreat(tile) && tile.militaryUnit?.civ != civInfo && CivilianSafety.moveToSafety(unit)) {
                unit.currentMovement = 0f
                return@timeThis
            }
            if (executeWorkerMove(unit)) return@timeThis

            unitView.tryAddNotification("${unitView.shortDisplayName()} has no work to do.", NotificationCategory.Units, unitView.name, "OtherIcons/Sleep")
            if (assignment.leftoverCity(unit)?.let { moveToCity(unit, it) } == true) return@timeThis
            if (CivilianSafety.moveToSafety(unit, ignoreUnits = true)) unit.currentMovement = 0f
        }

    /** CvHomelandAI::ExecuteMoveToTarget for a builder without a directive: true when it is in the city or moved towards it */
    private fun moveToCity(unit: MapUnit, city: City): Boolean {
        val target = city.getCenterTile()
        if (unit.getTile() == target) return true
        val before = unit.getTile()
        unit.movement.tryHeadTowards(target)
        return unit.getTile() != before || !unit.hasMovement()
    }

    /**
     *  CvHomelandAI::ExecuteWorkerMove: a builder already building keeps building; otherwise it carries out its pick of the assignment
     *  ([BuilderAssignment.pickFor], which plans the builders that can move first when this is the first one) as [executeDirective] does.
     *  False if there is no directive for it, or it cannot be carried out.
     */
    fun executeWorkerMove(unit: MapUnit): Boolean {
        if (isBuilding(unit)) return true
        val pick = assignment.pickFor(unit) { pendingBuilders() } ?: return false
        return executeDirective(unit, pick.directive)
    }

    /**
     *  CvBuilderTaskingAI::ExecuteWorkerMove: on the plot of [directive] the builder starts the build; otherwise it moves there, and starts the build
     *  when it is there with moves left. Its moves spent on the way count as success; a builder that has moves left and is not there (the path was
     *  aborted) did not carry it out. The directive is then its assigned directive ([Civilization.assignedBuilderDirectives]).
     */
    private fun executeDirective(unit: MapUnit, directive: BuilderDirective): Boolean {
        val tile = directive.tile
        val build = directive.build ?: return false
        var success = false
        if (unit.getTile() != tile) {
            unit.movement.tryHeadTowards(tile)
            if (unit.hasMovement()) {
                if (unit.getTile() != tile) return false
            } else success = true
        }
        if (unit.getTile() == tile && unit.hasMovement()) {
            startBuild(unit, build)
            success = true
        }
        if (success) civInfo.assignedBuilderDirectives[unit.id] = AssignedBuilderDirective(tile.position, build.improvement.name)
        return success
    }

    /**
     *  The builders CvHomelandAI::PlotWorkerMoves recruits that this civilization has planned by the time the first of them moves: land builders that
     *  can move, are not building, do not connect cities by road, and are the AI's or a human's automated ones; and the Great People that have the
     *  directive to construct their improvement. (Military units with a worker's builds work as builders in peace, as in [UnitAutomation].)
     */
    private fun pendingBuilders(): List<MapUnit> = civInfo.units.getCivUnits().filter { unit ->
        if (unit.isDestroyed || !unit.hasMovement() || isBuilding(unit)) return@filter false
        if (civInfo.isHuman() && !unit.isAutomated()) return@filter false
        if (unit.hasUnique(UniqueType.BuildImprovements))
            return@filter !unit.isAutomatingRoadConnection() && (unit.isCivilian() || !civInfo.isAtWar())
        !civInfo.isHuman() && unit.baseUnit.isGreatPerson && unit.hasUnique(UniqueType.ConstructImprovementInstantly)
            && GreatPersonAutomation.directive(unit) == GreatPeopleDirective.ConstructImprovement
    }.toList()

    private fun startBuild(unit: MapUnit, build: Build) {
        val tile = unit.getTile()
        when {
            build.isRepair -> UnitActionsFromUniques.getRepairAction(unit)?.action?.invoke()
            build.improvement.turnsToBuild == -1 ->
                UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, tile)
                    .firstOrNull { it.title.startsWith("Create [${build.improvement.name}]") }?.action?.invoke()
            build.removeFirst != null -> {
                tile.startWorkingOnImprovement(build.removeFirst, civInfo, unit)
                tile.queueImprovement(build.improvement, civInfo, unit)
            }
            else -> tile.startWorkingOnImprovement(build.improvement, civInfo, unit)
        }
    }

    /**
     *  CvHomelandAI::PlotWorkerSeaMoves for one work boat: the naval resource target it can build on that is fewest turns away; it moves
     *  there and creates the improvement when on the plot.
     *  @return whether the boat acted (moved or improved)
     */
    fun automateWorkBoats(unitView: MapUnitView): Boolean = timeThis("AI.WorkerSeaMoves") {
        val unit = unitView.getUnit()
        val unitTile = unit.getTile()
        var target: Tile? = null
        var targetTurns = Int.MAX_VALUE
        for (tile in navalResourceTargets) {
            if (!canCreateOn(unit, tile)) continue
            val turns = if (tile == unitTile) 0 else unit.movement.getShortestPath(tile).size.takeIf { it > 0 } ?: continue
            if (turns < targetTurns) {
                targetTurns = turns
                target = tile
            }
        }
        if (target == null) return@timeThis false
        if (unitTile != target) unit.movement.tryHeadTowards(target)
        if (unit.getTile() == target) unitView.tryInvokeUnitAction(UnitActionType.CreateImprovement)
        unitTile != unit.getTile() || target.improvement != null
    }

    /** Visible water plots without improvement, with a resource we see, in the reach of one of our cities, that a water improvement uses */
    private fun findNavalResourceTargets(): List<Tile> = civInfo.cities.asSequence()
        .flatMap { city -> city.tilesInRange.asSequence().filter { it.getCity() == city } }
        .filter { tile ->
            tile.isWater && tile.improvement == null && tile in civInfo.viewableTiles
                && tile.tileResource?.let { resource -> civInfo.canSeeResource(resource) && resource.getImprovements().isNotEmpty() } == true
        }
        .toList()

    /** CvUnit::canBuild for a work boat: it creates water improvements and one for the plot's resource can be built there */
    private fun canCreateOn(unit: MapUnit, tile: Tile): Boolean {
        if (!unit.cache.hasUniqueToCreateWaterImprovements) return false
        val resource = tile.tileResource ?: return false
        val context = GameContext(civInfo, unit = unit, tile = tile)
        return resource.getImprovements().any { name ->
            val improvement = tile.ruleset.tileImprovements[name]
            improvement != null && tile.improvementFunctions.canBuildImprovement(improvement, context)
        }
    }

    companion object {
        /** Stands for the original's plot danger, which outweighs the territory bonus but not a city's defense */
        private const val DANGER_PENALTY = 1000

        /** CvUnit::getBuildType != NO_BUILD: the builder stands on a plot with work in progress it can do */
        fun isBuilding(unit: MapUnit): Boolean {
            val tile = unit.getTile()
            if (!unit.cache.hasUniqueToBuildImprovements || tile.isMarkedForCreatesOneImprovement()) return false
            val inProgress = tile.getTileImprovementInProgress() ?: return false
            return inProgress.name == Constants.repair || unit.canBuildImprovement(inProgress, tile)
        }

        /**
         *  CvUnit::DoFinishBuildIfSafe, at the start of the unit's turn (CvUnit::doTurn): a road or railroad with at most one turn of work left is
         *  finished now, with the builder's moves still unspent, so that it can be used at once. Not for a human's builder that is not automated,
         *  and only when the builder can move, the plot is not in danger, and the builder's assigned directive ([Civilization.assignedBuilderDirectives],
         *  CvBuilderTaskingAI::GetAssignedDirective: the last turn's, as the original clears them when it plans the new turn) is this plot and this
         *  build.
         */
        fun finishBuildIfSafe(unit: MapUnit) {
            val civ = unit.civ
            if (civ.isHuman() && !unit.isAutomated()) return
            if (!isBuilding(unit)) return
            val tile = unit.getTile()
            val build = tile.getTileImprovementInProgress() ?: return
            if (tile.turnsToImprovement > 1 || !unit.hasMovement() || civ.ai.plotDanger.get(tile) > 0) return
            val assigned = civ.assignedBuilderDirectives[unit.id] ?: return
            if (assigned.position != tile.position || assigned.improvement != build.name || !build.isRoad()) return
            if (tile.doWorkerTurn(unit)) tile.getCity()?.shouldReassignPopulation = true
        }
    }
}
