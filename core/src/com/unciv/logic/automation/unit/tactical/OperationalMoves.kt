package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.ArmySlot
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.NavalOperationLogic
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationMoveType
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.automation.unit.SettlerAutomation
import com.unciv.logic.automation.unit.reachMovement
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.max
import kotlin.math.min

/**
 *  The movement of the armies of the AI operations: CvTacticalAI::PlotOperationalArmyMoves (the TACTICAL_MOVE_OPERATIONS move) with
 *  PlotArmyMovesEscort ([EscortMoves]: a settler and its escort), PlotArmyMovesCombat (the armies against a city or camp, defending, and
 *  the naval and combined armies on water; here [moveEnemyTerritory]), ExecuteGatherMoves, ExecuteFormationMoves, CheckForEnemiesNearArmy,
 *  MoveWithFormation, and the army's part of CvAIOperation::ComputeTargetPlotForThisTurn and
 *  CvArmyAI (movement rate, checkpoint turns). The operation model is [com.unciv.logic.automation.strategy.AiOperations]; the hexes the units
 *  take are scored by [Deployment]. BNW's PlotNavalEscortOperationMoves and ExecuteNavalFormationMoves are gone.
 *
 *  An army that is recruiting sends its units to the muster point once they are just able to get there in time; gathering, everyone goes to hexes
 *  around the muster point (scored for safety and terrain, the gather range for the army's size, or 3); moving, the army's center of mass is
 *  pushed along the route to its goal by the average movement of its units plus 1 hex, the units take hexes around that point (melee out front,
 *  ranged behind; the hexes nearer the rear are the safe ones) and the units that are slow arrive later. Before it moves, the enemies next to the
 *  army's position are attacked by the army's units. An army with too little room gathers or moves what fits (Vox Populi has no
 *  NO_ROOM_DEPLOY abort); one with no route to its goal aborts (LOST_PATH).
 *
 *  Deviations:
 *  - the original's attack operations move only their first army, as here;
 *  - the route of the center of mass is [com.unciv.logic.automation.strategy.StepFinder]'s (own and allowed land, or over water for the naval
 *    and combined armies, units ignored; deep ocean only if we can embark onto it, whatever the ships can do);
 *  - the naval and combined armies gather and move on water by the same hex scoring as the land armies ([Deployment]: water hexes, no ocean for
 *    an army that cannot cross it), and their land units are not stopped on land to embark safely ([Deployment.moveToUsingSafeEmbark]); the
 *    original positions the units with its own path finder (PositionUnitsAroundTarget). A fleet of ships alone does not give up when it has
 *    no melee ship left (the "no melee unit" departure below is for armies that have to take their target);
 *  - TurnsToReachTarget(unit, muster) for the checkpoint turns is [AiOperationLogic.turnsToReach] (the unit's path, units not ignored);
 *  - a settler and its escort move as Vox Populi's PlotArmyMovesEscort says ([EscortMoves]; its deviations are listed there), BNW's
 *    PlotSingleHexOperationMoves and its retargeting of the civilian are gone;
 *  - CvAIOperation::ArmyMoved is never called by the original and is not either;
 *  - a unit that has been placed is marked processed.
 */
object OperationalMoves {

    /**
     *  What CvPlayer::doTurnUnits does for the operations before the units move: units that died leave their armies, cities that stopped
     *  building for a slot uncommit, every loose settler gets its operation (EconomicAIHelpers::IsTestStrategy_FoundCity), operations that
     *  are over are swept.
     */
    fun prepareTurn(civ: Civilization) = timeThis("AI.OperationsPrepare") {
        val operations = civ.ai.operations
        operations.reconcileUnits()
        operations.refreshCommitments()
        if (civ.cities.isNotEmpty())
            for (unit in civ.units.getCivUnits().toList())
                if ((civ.isAI() || unit.isAutomated()) && SettlerAutomation.isLooseSettler(unit)) SettlerAutomation.launchOperation(unit)
        operations.sweep()
    }

    /**
     *  CvTacticalAI::PlotOperationalArmyMoves: every operation that has not moved this turn moves its armies, then CheckOnTarget; then the
     *  operations that are over are removed.
     */
    fun plotOperationalArmyMoves(ctx: TacticalContext) = timeThis("AI.OperationMoves") {
        val civ = ctx.civ
        for (operation in civ.ai.operations.operations.toList()) moveOperation(civ, operation, ctx.cache, ctx)
        civ.ai.operations.sweep()
    }

    /**
     *  One operation's turn: its armies move ([moveSingleHex] or [moveEnemyTerritory] by its move type: every army of the military operations,
     *  by land and by sea, as CvAIOperation::Move gives them all to PlotArmyMovesCombat), then it is checked on its target. [ctx]
     *  is the tactical turn's; one is made if it is not given and the operation needs one.
     */
    fun moveOperation(civ: Civilization, operation: AiOperation, cache: AutomationCache, ctx: TacticalContext? = null) {
        val turn = civ.gameInfo.turns
        if (operation.lastTurnMoved >= turn || operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) return
        when (operation.type.moveType) {
            OperationMoveType.SingleHex -> moveSingleHex(ctx ?: lightContext(civ, cache), operation)
            OperationMoveType.EnemyTerritory -> moveEnemyTerritory(ctx ?: fullContext(civ, cache), operation)
            OperationMoveType.None -> Unit
        }
        // CvAIOperation::Move: an operation that ends with the move releases its units, and is not checked on its target
        if (!AiOperationLogic.continueAfterMove(civ, operation)) return
        operation.lastTurnMoved = turn
        AiOperationLogic.checkOnTarget(civ, operation)
    }

    /** A context for the moves that look at no targets (a settler's escort): the analysis map and no targets */
    private fun lightContext(civ: Civilization, cache: AutomationCache) =
        TacticalContext(civ, civ.ai.tactical.map, civ.ai.plotDanger, cache, emptyList(), TacticalTargets.none())

    /** A context with this turn's targets, for an army that has to attack what is near it */
    private fun fullContext(civ: Civilization, cache: AutomationCache) =
        civ.ai.tactical.map.let { map -> TacticalContext(civ, map, civ.ai.plotDanger, cache, emptyList(), TacticalTargets.find(civ, map)) }

    private fun tileAt(civ: Civilization, coord: HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

    /** CvUnit::TurnProcessed: something already decided this unit's moves */
    private fun isProcessed(ctx: TacticalContext, unit: MapUnit) = unit in ctx.cache.processedUnits

    //region Single hex: settlers and their escort (PlotArmyMovesEscort)

    /** CvTacticalAI::PlotArmyMovesEscort: a civilian and its escort meet, then move to the target together, one hex stack: [EscortMoves] */
    private fun moveSingleHex(ctx: TacticalContext, operation: AiOperation) = EscortMoves.plotArmyMovesEscort(ctx, operation)
    //endregion

    //region Armies against a city or camp (PlotEnemyTerritoryOperationMoves)

    /** CvArmyAI::GetMovementRate: the average base movement of the army's units */
    fun movementRate(civ: Civilization, army: AiArmy): Int {
        val units = AiOperationLogic.armyUnits(civ, army)
        if (units.isEmpty()) return 0
        return (units.sumOf { it.second.reachMovement() } + units.size / 2) / units.size
    }

    /**
     *  CvAIOperation::ComputeTargetPlotForThisTurn while the army moves to its target: the army's center of mass pushed along the route to its
     *  goal by the army's movement rate + 1 hexes; the second tile is the one two hexes behind it on the route (the army's current center
     *  as far as the formation is concerned). The route of a naval or combined army is over water (a center of mass on land starts at the
     *  coastal water near it). Null if the operation is not moving or there is no route (the operation aborts).
     */
    fun computeCenterOfMassForTurn(civ: Civilization, operation: AiOperation, army: AiArmy): Pair<Tile, Tile>? {
        if (operation.state != OperationState.MOVING_TO_TARGET) return null
        val goal = tileAt(civ, army.goalTile) ?: return null
        val naval = isNaval(operation)
        var centerOfMass = AiOperationLogic.centerOfMass(civ, army, sea = naval) ?: return null
        // make sure the start position is valid
        if (naval && !centerOfMass.isWater) centerOfMass = NavalOperationLogic.coastalWaterNearPlot(civ, centerOfMass) ?: return null
        // Push the center of mass forward a number of hexes equal to the average movement + 1
        val path = civ.ai.military.steps.path(civ, AiOperationLogic.enemy(civ, operation), centerOfMass, goal, sea = naval) ?: return null
        val forward = min(movementRate(civ, army) + 1, path.size - 1)
        return path[forward] to path[max(forward - 2, 0)]
    }

    /** CvAIOperation::IsNavalOperation: the army is of ships, or of ships and land units that embark (ARMY_TYPE_NAVAL, ARMY_TYPE_COMBINED) */
    private fun isNaval(operation: AiOperation) = operation.type.allNaval || operation.type.mixedLandNaval

    /** CvArmyAI::GetTurnAtNextCheckpoint: the latest expected arrival, [ArmySlot.UNKNOWN_TURN] if a slot has no estimate */
    fun turnAtNextCheckpoint(army: AiArmy): Int {
        var result = ArmySlot.NOT_INCLUDED
        for (slot in army.slots) {
            if (slot.estimatedTurn == ArmySlot.UNKNOWN_TURN) return ArmySlot.UNKNOWN_TURN
            if (slot.estimatedTurn > result) result = slot.estimatedTurn
        }
        return result
    }

    /** CvArmyAI::UpdateCheckpointTurns: when each unit that exists will be at the army's position */
    fun updateCheckpointTurns(civ: Civilization, army: AiArmy) {
        val position = tileAt(civ, army.position) ?: return
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            val turns = AiOperationLogic.turnsToReach(unit, position)
            if (turns != Int.MAX_VALUE) civ.ai.operations.setEstimatedTurn(army, index, turns)
        }
    }

    /** CvTacticalAI::MoveWithFormation: [unit] is to move as part of the army's formation if it can move */
    private fun moveWithFormation(units: MutableList<OperationUnit>, unit: MapUnit, position: com.unciv.models.ruleset.ai.FormationPosition) {
        if (unit.hasMovement()) units.add(OperationUnit(unit, position))
    }

    /** CvTacticalAI::PlotEnemyTerritoryOperationMoves: the army of an attack operation recruits, gathers, or moves one step towards its target */
    private fun moveEnemyTerritory(ctx: TacticalContext, operation: AiOperation) {
        val civ = ctx.civ
        val army = operation.firstArmy ?: return
        val slotDefs = civ.ai.operations.slotDefs(army)
        val operationUnits = ArrayList<OperationUnit>()
        val generals = ArrayList<MapUnit>()
        updateCheckpointTurns(civ, army)
        val naval = isNaval(operation)

        when (army.state) {
            // RECRUITING
            ArmyState.WAITING_FOR_UNITS_TO_REINFORCE -> {
                // If no estimate for when recruiting will end, let the rest of the AI use these units
                val checkpoint = turnAtNextCheckpoint(army)
                if (checkpoint == ArmySlot.UNKNOWN_TURN) return
                val muster = AiOperationLogic.musterTile(civ, operation)
                for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
                    if (isProcessed(ctx, unit)) continue
                    if (Deployment.isGeneralOrAdmiral(unit)) { if (unit.hasMovement()) generals.add(unit); continue }
                    val slot = army.slots[index]
                    val position = slotDefs[index].position
                    // Continue moving to target
                    if (slot.startedOnOperation) moveWithFormation(operationUnits, unit, position)
                    else if (muster != null) {
                        // See if we are just able to get to muster point in time. If so, time for us to head over there
                        val turns = AiOperationLogic.turnsToReach(unit, muster)
                        if (turns != Int.MAX_VALUE && turns + ctx.turn >= checkpoint) {
                            slot.startedOnOperation = true
                            moveWithFormation(operationUnits, unit, position)
                        }
                    }
                }
                executeGatherMoves(ctx, operation, army, operationUnits)
            }

            // GATHERING FORCES
            ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> {
                val contact = checkForEnemiesNearArmy(ctx, army, naval)
                // Request moves for all units
                for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
                    if (isProcessed(ctx, unit)) continue
                    if (Deployment.isGeneralOrAdmiral(unit)) { if (unit.hasMovement()) generals.add(unit); continue }
                    army.slots[index].startedOnOperation = true
                    moveWithFormation(operationUnits, unit, slotDefs[index].position)
                }
                executeGatherMoves(ctx, operation, army, operationUnits, if (contact) AiOperationLogic.centerOfMass(civ, army, naval) else null)
            }

            // MOVING TO TARGET
            ArmyState.MOVING_TO_DESTINATION -> {
                // Departure (D7): with no melee unit left an army that has to take its target cannot do so, and its ranged units, deployed on
                // the hexes behind the turn target (executeFormationMoves), never move the center forward; the original keeps such an army
                // while it is at half strength (its general counting), and in a Domination game three Rocket Artillery and a general held the
                // one sneak attack in place for 200 turns. A fleet (ships only) takes no city, its ranged ships are its strength.
                if (!operation.type.allNaval && AiOperationLogic.armyUnits(civ, army).none { (_, unit) ->
                        unit.isMilitary() && !unit.baseUnit.isRanged() && !Deployment.isGeneralOrAdmiral(unit) }) {
                    civ.ai.operations.abort(operation, OperationAbortReason.HALF_STRENGTH)
                    return
                }
                // Update army's current location
                val (turnTarget, closestCurrent) = computeCenterOfMassForTurn(civ, operation, army) ?: run {
                    civ.ai.operations.abort(operation, OperationAbortReason.LOST_PATH)
                    return
                }
                army.position = turnTarget.position
                val contact = checkForEnemiesNearArmy(ctx, army, naval)
                // Request moves for all units
                for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
                    if (isProcessed(ctx, unit)) continue
                    if (Deployment.isGeneralOrAdmiral(unit)) { if (unit.hasMovement()) generals.add(unit); continue }
                    moveWithFormation(operationUnits, unit, slotDefs[index].position)
                }
                // Do not move on while there are enemies around, it is too dangerous: the units keep together around their center of mass
                val center = if (contact) AiOperationLogic.centerOfMass(civ, army, naval) ?: turnTarget else null
                // getting too close to another enemy?
                if (leadsPastCityOfAnotherEnemy(civ, operation, center ?: turnTarget)) {
                    civ.ai.operations.abort(operation, OperationAbortReason.TOO_DANGEROUS)
                    return
                }
                executeFormationMoves(ctx, operation, army, center ?: turnTarget, center ?: closestCurrent, operationUnits)
            }
        }
        if (generals.isNotEmpty()) Deployment.moveGreatGenerals(ctx, generals, army)
    }

    /**
     *  PlotArmyMovesCombat's check of an operation against a player: the turn target is less than 3 plots from a city (CvGame::GetClosestCityDistanceInPlots,
     *  GetClosestCityOwnerByPlots: the nearest of any player, the first on a tie) whose owner is at war with us and is not the operation's enemy.
     */
    private fun leadsPastCityOfAnotherEnemy(civ: Civilization, operation: AiOperation, turnTarget: Tile): Boolean {
        val enemy = AiOperationLogic.enemy(civ, operation) ?: return false
        val nearest = civ.gameInfo.civilizations.filter { it.isAlive() }.flatMap { it.cities }
            .minWithOrNull(compareBy<City> { it.getCenterTile().aerialDistanceTo(turnTarget) }.thenBy { it.id }) ?: return false
        return nearest.getCenterTile().aerialDistanceTo(turnTarget) < TOO_CLOSE_TO_CITY && nearest.civ != enemy && civ.isAtWarWith(nearest.civ)
    }

    /** PlotArmyMovesCombat: a turn target nearer than this to a city is "getting too close" */
    private const val TOO_CLOSE_TO_CITY = 3

    /**
     *  CvTacticalAI::CheckForEnemiesNearArmy: the army's units (those that can attack or be attacked, with those of ours around that the enemies
     *  near could attack) that are in contact with the enemy join this turn's tactical units and fight it with the combat simulation
     *  ([TacticalSimulation.checkForEnemiesNearArmy]); [naval] armies (ARMY_TYPE_NAVAL, ARMY_TYPE_COMBINED) judge the water zone of the nearest
     *  city, the others its land zone.
     *  @return whether they fought: the army then holds together around its center of mass instead of marching on
     */
    fun checkForEnemiesNearArmy(ctx: TacticalContext, army: AiArmy, naval: Boolean = false): Boolean {
        val units = AiOperationLogic.armyUnits(ctx.civ, army).map { it.second }
        // The army's units join this turn's tactical units
        for (unit in units) if (!isProcessed(ctx, unit) && !unit.isDestroyed && unit.hasMovement()) ctx.addUnit(unit)
        val contact = TacticalSimulation.checkForEnemiesNearArmy(ctx, units, naval)
        if (contact) ctx.log { "army ${army.id}: contact with the enemy" }
        return contact
    }

    /**
     *  CvTacticalAI::ExecuteGatherMoves: the units asked to move with the formation ([units]) gather around the army's position: everyone as a
     *  melee unit, on hexes within the gather range for their number if there are twice as many hexes as units, else within 3. Too little room
     *  only leaves some units where they are: Vox Populi never aborts an operation for it (BNW's NO_ROOM_DEPLOY kept a crowded empire's
     *  sneak attacks from ever gathering).
     */
    private fun executeGatherMoves(ctx: TacticalContext, operation: AiOperation, army: AiArmy, units: List<OperationUnit>, center: Tile? = null) {
        if (units.isEmpty()) return
        val civ = ctx.civ
        val target = center ?: tileAt(civ, army.position) ?: return
        // Gathering - treat everyone as a melee unit; don't need ranged in the rear yet
        val count = units.size
        // Range around target based on number of units we need to place; try one time with the computed range
        val computedRange = AiOperationLogic.gatherRangeForXUnits(count)
        var scored = Deployment.scoreDeploymentPlots(ctx, operation, army, target, count, 0, computedRange)
        // Not twice as many possible plots as units: the wider search
        if (!scored.enough || scored.plots.size < count * 2) scored = Deployment.scoreDeploymentPlots(ctx, operation, army, target, count, 0, 3)
        if (!scored.enough) ctx.log { "army ${army.id}: not enough spaces to gather near ${target.position}, gathering what fits" }
        gatherUnits(ctx, army, units, scored)
    }

    private fun gatherUnits(ctx: TacticalContext, army: AiArmy, units: List<OperationUnit>, scored: DeploymentPlots) {
        // Compute the moves to get the best deployment
        val potential = ArrayList<PotentialBlock>()
        var unitsToPlace = units.size
        val armyUnitIds = army.unitIds()
        for (plot in scored.plots) {
            if (unitsToPlace == 0) break
            // Don't use if there's already a unit not in the army here
            val unitAlreadyThere = plot.tile.militaryUnit
            if (unitAlreadyThere != null && unitAlreadyThere.id !in armyUnitIds) continue
            val candidates = Deployment.findClosestOperationUnits(units, plot.tile, safeForRanged = true, mustBeRanged = false)
            if (candidates.isEmpty()) continue
            potential.addAll(Deployment.blocksFor(plot.tile, candidates))
            unitsToPlace--
        }
        // Now ready to make the assignments
        val chosen = Deployment.assignDeployingUnits(potential, units.size - unitsToPlace)
        Deployment.performChosenMoves(ctx, chosen)
        // Log if someone in army didn't get a move assigned
        if (chosen.size < units.size) ctx.log { "no gather move for ${units.size - chosen.size} units" }
    }

    /**
     *  CvTacticalAI::ExecuteFormationMoves: the units asked to move with the formation ([units]) take the hexes [Deployment.scoreFormationPlots] finds
     *  around the army's goal for the turn, [forwardTarget]: melee units first, the nearest unit to each hex, then the ranged units on the hexes
     *  nearer the rear, then wherever is left. A hex with a unit on it is not used. Too few hexes leave units behind; as in Vox Populi the
     *  operation goes on (BNW aborted it with NO_ROOM_DEPLOY).
     */
    private fun executeFormationMoves(ctx: TacticalContext, operation: AiOperation, army: AiArmy, forwardTarget: Tile, currentCenter: Tile, units: List<OperationUnit>) {
        if (units.isEmpty()) return
        val meleeUnits = units.count { !it.unit.baseUnit.isRanged() }
        val rangedUnits = units.size - meleeUnits

        val scored = Deployment.scoreFormationPlots(ctx, operation, army, forwardTarget, currentCenter, units.size)
        if (!scored.enough) ctx.log { "army ${army.id}: not enough spaces along the formation's path, moving what fits" }

        fun place(plot: DeployPlot, safeForRanged: Boolean, mustBeRanged: Boolean, what: String): Boolean {
            // Don't use if there's already someone here
            if (plot.tile.militaryUnit != null) return false
            val unit = Deployment.findClosestOperationUnits(units, plot.tile, safeForRanged, mustBeRanged).firstOrNull()?.unit ?: return false
            Deployment.moveToUsingSafeEmbark(ctx, unit, plot.tile)
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
            ctx.log { "deploying $what ${unit.name} to ${plot.tile.position}, now at ${unit.getTile().position}" }
            return true
        }

        // First loop for melee units who should be out front
        var meleeToPlace = meleeUnits
        for (plot in scored.plots) {
            if (meleeToPlace == 0) break
            if (place(plot, safeForRanged = false, mustBeRanged = false, what = "melee unit")) meleeToPlace--
        }
        if (meleeToPlace > 0) ctx.log { "no army deployment move for $meleeToPlace melee units" }

        // Second loop for ranged units, on the hexes safe for them, then (third loop) on any hex
        var rangedToPlace = rangedUnits
        for (plot in scored.plots) {
            if (rangedToPlace == 0) break
            if (plot.forRanged && place(plot, safeForRanged = true, mustBeRanged = true, what = "ranged unit")) rangedToPlace--
        }
        for (plot in scored.plots) {
            if (rangedToPlace == 0) break
            if (place(plot, safeForRanged = true, mustBeRanged = true, what = "ranged unit (pass 2)")) rangedToPlace--
        }
        if (rangedToPlace > 0) ctx.log { "no army deployment move for $rangedToPlace ranged units" }
    }
    //endregion
}
