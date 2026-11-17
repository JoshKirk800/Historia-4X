package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.view.MapUnitView
import com.unciv.view.shortDisplayName
import yairm210.purity.annotations.Readonly

/**
 *  Builder dispatch of the original's homeland AI: CvHomelandAI::ExecuteWorkerMoves / ExecuteWorkerMove (land builders and Great People
 *  creating an improvement), MoveCivilianToSafety and PlotWorkerSeaMoves (work boats). What to build where is [BuilderTaskingAi].
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

    /** CvHomelandAI's AI_HOMELAND_TARGET_NAVAL_RESOURCE targets of this turn (FindHomelandTargets) */
    private val navalResourceTargets: List<Tile> by lazy { findNavalResourceTargets() }

    /**
     *  CvHomelandAI::ExecuteWorkerMoves for one builder: on a plot under immediate threat without a defender, move to safety; else follow
     *  the builder directive ([executeWorkerMove]); with nothing to do, move to the safest plot nearby (ignoring units).
     */
    fun automateWorkerAction(unitView: MapUnitView): Unit =
        timeThis("AI.WorkerMoves") {
            val unit = unitView.getUnit()
            val tile = unit.getTile()
            if (civInfo.ai.plotDanger.isUnderImmediateThreat(tile) && tile.militaryUnit?.civ != civInfo && CivilianSafety.moveToSafety(unit)) {
                unit.builderTarget = null  // the move to safety is another mission: the unit gives up its plot
                unit.currentMovement = 0f
                return@timeThis
            }
            if (executeWorkerMove(unit)) return@timeThis

            unitView.tryAddNotification("${unitView.shortDisplayName()} has no work to do.", NotificationCategory.Units, unitView.name, "OtherIcons/Sleep")
            if (CivilianSafety.moveToSafety(unit, ignoreUnits = true)) {
                unit.builderTarget = null
                unit.currentMovement = 0f
            }
        }

    /**
     *  CvHomelandAI::ExecuteWorkerMove: a builder already building keeps building; otherwise it builds the build of the directive of the best weight
     *  for it ([BuilderTaskingAi.evaluateBuilder]) when it stands on the plot, or moves there and ends its turn (it starts building on the next one).
     *  False if there is no directive.
     */
    fun executeWorkerMove(unit: MapUnit): Boolean {
        if (isBuilding(unit)) return true
        val picked = builderTaskingAi.evaluateBuilder(unit) ?: return false
        builderTaskingAi.claim(unit, picked.tile)
        if (unit.getTile() == picked.tile) startBuild(unit, picked.build)
        else unit.movement.tryHeadTowards(picked.tile)
        return true
    }


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
         *  and only when the builder can move, the plot is not in danger, and the builder was sent here (the original compares the assigned
         *  directive with the plot and the build; ours is the claim of [BuilderTaskingAi.claim], which names the plot, and the build is the one in
         *  progress, which only the builder's own order starts).
         */
        fun finishBuildIfSafe(unit: MapUnit) {
            val civ = unit.civ
            if (civ.isHuman() && !unit.isAutomated()) return
            if (!isBuilding(unit)) return
            val tile = unit.getTile()
            val build = tile.getTileImprovementInProgress() ?: return
            if (tile.turnsToImprovement > 1 || !unit.hasMovement() || civ.ai.plotDanger.get(tile) > 0) return
            if (unit.builderTarget != tile.position || !build.isRoad()) return
            if (tile.doWorkerTurn(unit)) tile.getCity()?.shouldReassignPopulation = true
        }
    }
}
