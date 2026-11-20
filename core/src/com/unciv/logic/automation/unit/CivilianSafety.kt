package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.Deployment
import com.unciv.logic.automation.unit.tactical.SafestPlot
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.map.mapunit.MapUnit

/**
 *  How the homeland AI gets units out of harm's way, by the danger of the plot to the unit ([PlotDanger.get], CvUnit::GetDanger):
 *  CvHomelandAI::PlotMovesToSafety's test ([shouldFlee]) and ExecuteMovesToSafestPlot ([fleeIfThreatened]), and MoveCivilianToSafety, the fallback of
 *  workers and Great People ([moveToSafety]). Both go to the plot TacticalAIHelpers::FindSafestPlotInReach finds ([SafestPlot.find], which handles the
 *  civilians' own danger, being captured, and cover by a defender of ours); the combat units of the tactical AI use the same search.
 *
 *  Deviations: ExecuteMovesToSafestPlot is the tactical version ([SafestPlot.executeMovesToSafestPlot]) for civilians: the retreat is one move, where
 *  the homeland's loops it up to three times, and the unit's turn is over after it; the siege unit that PlotMovesToSafety first moves away from the
 *  border (ExecuteMoveUnitAwayFromBorder) is left out; MoveCivilianToGarrison, the other fallback of the workers, is not ported (they go to the city
 *  the assignment found for them, see [com.unciv.logic.automation.unit.WorkerAutomation]).
 */
object CivilianSafety {

    private const val FALLOUT = "Fallout"
    /** CvHomelandAI::PlotMovesToSafety: danger below this share of the unit's hit points ("allow some danger from fog etc") is no reason to flee */
    private const val TOLERATED_DANGER_DIVISOR = 5

    /**
     *  CvHomelandAI::PlotMovesToSafety's test: a unit flees when its danger is at least a fifth of its hit points; a military unit only in mortal danger
     *  (its hit points), a civilian always except a worker in our land that cleans fallout, which stays while the danger is below its hit points.
     */
    fun shouldFlee(unit: MapUnit): Boolean {
        val level = unit.civ.ai.plotDanger.get(unit)
        if (level < unit.health / TOLERATED_DANGER_DIVISOR) return false
        if (!unit.isCivilian()) return level >= unit.health
        val tile = unit.getTile()
        return !(tile.getOwner() == unit.civ && tile.terrainFeatures.contains(FALLOUT) && level < unit.health)
    }

    /**
     *  CvHomelandAI::PlotMovesToSafety for a civilian that is [shouldFlee]: it goes to the safest plot it reaches this turn ([SafestPlot.executeMovesToSafestPlot])
     *  and its turn ends. False if it does not need to flee.
     */
    fun fleeIfThreatened(unit: MapUnit, cache: AutomationCache = AutomationCache()): Boolean {
        if (!shouldFlee(unit)) return false
        val tile = unit.getTile()
        AiDecisionLog.log(unit.civ) { "Civilian: ${unit.name} in danger ${unit.civ.ai.plotDanger.get(unit)} at ${tile.position} flees" }
        SafestPlot.executeMovesToSafestPlot(context(unit, cache), unit)
        unit.currentMovement = 0f
        return true
    }

    /**
     *  CvHomelandAI::MoveCivilianToSafety: [unit] goes to the plot [SafestPlot.find] finds; where it already is the best plot, a unit outside our land with
     *  no danger heads for the capital, else it stays. False if there is no plot to go to.
     */
    fun moveToSafety(unit: MapUnit, cache: AutomationCache = AutomationCache()): Boolean {
        val civ = unit.civ
        val best = SafestPlot.find(context(unit, cache), unit, allowEmbark = true)?.tile ?: return false
        if (best != unit.getTile()) return Deployment.headTowards(unit, best)
        // If the unit is not in friendly territory and not in danger, try to bring it home
        val capital = civ.getCapital()
        if (!best.isFriendlyTerritory(civ) && civ.ai.plotDanger.get(unit) == 0 && capital != null) Deployment.headTowards(unit, capital.getCenterTile())
        return true
    }

    /** A context for the safety search: the analysis map and no targets */
    private fun context(unit: MapUnit, cache: AutomationCache) =
        TacticalContext(unit.civ, unit.civ.ai.tactical.map, unit.civ.ai.plotDanger, cache, emptyList(), TacticalTargets.none())
}
