package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.automation.unit.tactical.OperationalMoves
import com.unciv.view.MapUnitView

/**
 *  Where and when AI settlers go: CvHomelandAI::PlotFirstTurnSettlerMoves / ExecuteFirstTurnSettlerMoves for the first city, and for the
 *  later ones the launch of the found-city operation of EconomicAIHelpers::IsTestStrategy_FoundCity (Vox Populi: one operation per loose
 *  settler, no choice of landmass). The operations themselves - target from CvPlayer::GetBestSettlePlot ([SettleAreas.bestSettlePlot]), the
 *  escort recruited from the reserves or trained by a city (slot commitments of [com.unciv.logic.automation.strategy.AiOperations]), retargeting
 *  when the site is lost, founding on arrival - are CvAIOperationCivilianFoundCity in [AiOperationLogic], moved by [OperationalMoves].
 */
object SettlerAutomation {

    /** A settler of the first city may move on only in the first [FIRST_TURNS] turns of the game, and must found in the last of them */
    private const val FIRST_TURNS = 3

    /** The better site must be at least 10% above the current one to justify the trouble (iAltValue > iCurrentValue * 1.1f) */
    private const val BETTER_SITE_PERCENT = 110

    fun automateSettler(unitView: MapUnitView, cache: AutomationCache) = timeThis("AI.Settler") {
        val unit = unitView.getUnit()
        val civ = unit.civ

        // CvHomelandAI::PlotFirstTurnSettlerMoves: with no city yet, the first-turn moves. (The original asks for no city ever founded; a civilization
        // that lost all its cities and has a settler would do nothing there, here it founds again.)
        if (civ.cities.isEmpty() && firstTurnSettlerMoves(unitView)) return@timeThis

        // The operation normally exists and has moved the settler already (the tactical AI's MOVE_OPERATIONS); a settler of a human's automation, or one
        // that came to our civilization this turn, starts and moves it here.
        val operations = civ.ai.operations
        operations.reconcileUnits()
        val operation = operations.operationFor(unit) ?: launchOperation(unit) ?: run {
            AiDecisionLog.log(civ) { "settler ${unit.id} at ${unit.getTile().position}: no settle site" }
            return@timeThis
        }
        OperationalMoves.moveOperation(civ, operation, cache)
        operations.sweep()
    }

    /**
     *  CvHomelandAI::ExecuteFirstTurnSettlerMoves for one settler: a city-state of a ruleset that does not search for its first site
     *  (CS_CAN_MOVE_STARTING_SETTLER 0, the Vox Populi value) founds where it stands; so does any settler on a river estuary (fresh water on
     *  the coast: "those are always good"); in the first three turns it moves to the reachable site whose found value is more than 10% above
     *  the best found so far (starting with its own plot's), and founds there if it has movement left, in the third turn with at least one
     *  point of movement left on the way, or arrives and founds next turn ("it is better to lose a turn or two early on than to be in a bad
     *  spot"); with no better site it founds where it stands; where it cannot found, it scrambles up to three random neighbor tiles.
     *  (MAJORS_CAN_MOVE_STARTING_SETTLER is 1 in Vox Populi: a major civilization's settler may move.)
     *  Deviation: the danger check of the candidate sites ([MapUnit] danger below the maximum) is a visible enemy unit on the tile.
     *  @return whether the settler was dealt with
     */
    fun firstTurnSettlerMoves(unitView: MapUnitView): Boolean {
        val unit = unitView.getUnit()
        val civ = unit.civ
        val here = unit.getTile()
        fun found() = unitView.tryInvokeUnitAction(UnitActionType.FoundCity)

        if (SettleAreas.canFound(unit, here)) {
            if (civ.isCityState && !civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.CityStatesSearchForFirstCitySite)) return found()
            // Let's check for a river estuary - those are always good
            if (here.isAdjacentTo(Constants.freshWater) && WaterBodies.of(civ).isCoastalLand(here) && found()) return true
        }

        // Look for a better place: move in the first three turns only!
        val elapsed = civ.gameInfo.turns
        var better: Tile? = null
        var currentValue = SettleAreas.foundValue(civ, here)
        if (elapsed < FIRST_TURNS) {
            val minMovesLeft = if (elapsed == FIRST_TURNS - 1) 1 else 0 // must found in the third turn
            for ((tile, path) in unit.movement.getDistanceToTiles()) {
                if (unit.currentMovement - path.totalMovement < minMovesLeft) continue
                if (!SettleAreas.canFound(unit, tile) || SettleAreas.isVisibleEnemyUnit(civ, tile)) continue
                val value = SettleAreas.foundValue(civ, tile)
                if (value * 100 > currentValue * BETTER_SITE_PERCENT) {
                    currentValue = value
                    better = tile
                }
            }
        }
        if (better != null) {
            unit.movement.moveToTile(better)
            // Could not get there and found in one move? That is okay - it is better to lose a turn or two early on than to be in a bad spot
            if (unit.getTile() == better && unit.hasMovement()) found()
            return true
        }

        // Did not find a better plot (the ideal case, in fact)
        if (SettleAreas.canFound(unit, here)) return found()

        // Emergency: cannot found! Move in a random direction to explore
        val random = civ.ai.random("FirstTurnSettler")
        val neighbors = here.neighbors.toList()
        var target: Tile? = null
        for (attempt in 0 until 3) {
            if (neighbors.isEmpty()) break
            val candidate = neighbors[random.nextInt(neighbors.size)]
            if (unit.movement.canMoveTo(candidate) && !SettleAreas.isVisibleEnemyUnit(civ, candidate)) {
                unit.movement.moveToTile(candidate)
                target = candidate
                break
            }
        }
        if (target != null && unit.hasMovement() && unit.getTile() == target && SettleAreas.canFound(unit, target)) found()
        return true
    }

    /**
     *  EconomicAIHelpers::IsTestStrategy_FoundCity, its operation launch for a loose settler (Vox Populi): never for a very unhappy empire; no
     *  best settle plot for the unit - no operation; during the early expansion a plot whose found value is under half the capital's is
     *  passed on ("we need good plots for our core cities, so hold off for a while: there is a chance we just have not explored enough yet");
     *  else the operation, whose target must have at least the minimum acceptable quality (CvAIOperationCivilianFoundCity::FindBestTargetForUnit).
     *  @return the operation (it may have aborted at once: no site, see [AiOperationLogic.addSettlerOperation]), null if none was started
     */
    fun launchOperation(unit: MapUnit): AiOperation? {
        val civ = unit.civ
        if (civ.cities.isEmpty() || civ.isOneCityChallenger()) return null
        if (EconomicMilitaryStrategyTests.isEmpireVeryUnhappy(civ)) return null
        val best = SettleAreas.bestSettlePlot(civ, unit, null) ?: return null
        val capital = civ.getCapital()
        if (civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.EARLY_EXPANSION) && capital != null &&
            SettleAreas.foundValue(civ, best) < SettleAreas.foundValue(civ, capital.getCenterTile()) / 2) {
            AiDecisionLog.log(civ) { "settler ${unit.id}: passing on settle plot ${best.position}, value ${SettleAreas.foundValue(civ, best)}, it is not good enough" }
            return null
        }
        if (SettleAreas.settlePlotQualityMeasure(civ, best) < SettleAreas.minAcceptableSettleQuality(civ)) return null
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, unit)
        AiDecisionLog.log(civ) {
            "settler ${unit.id} at ${unit.getTile().position}: ${operation.type} to ${operation.targetTile} " +
                "(${if (operation.escorted) "escorted" else "alone"}, ${operation.state})"
        }
        return operation
    }

    /** Settlers without an operation of their own that the operation pass launches one for: loose units with the SETTLE unit AI */
    fun isLooseSettler(unit: MapUnit): Boolean =
        unit.civ.ai.operations.armyOf(unit) == null && UnitAiTypes.current(unit) == UnitAiType.SETTLE && unit.hasUnique(UniqueType.FoundCity)
}
