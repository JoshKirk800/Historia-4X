package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.unit.tactical.AggressionLevel
import com.unciv.logic.automation.unit.tactical.BarbarianMoves
import com.unciv.logic.automation.unit.tactical.BarbarianTheft
import com.unciv.logic.automation.unit.tactical.SafestPlot
import com.unciv.logic.automation.unit.tactical.TacticalAi
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.unit.tactical.TacticalMoves
import com.unciv.logic.automation.unit.tactical.TacticalSimulation
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.automation.unit.tactical.TradeMoves
import com.unciv.logic.civilization.Civilization

/**
 *  The barbarians' turn, CvTacticalAI as it runs for the barbarian player (ProcessDominanceZones' barbarian branch): all their units are
 *  tactical, there is no analysis map and no dominance zone; the targets are found ([TacticalTargets.find], with
 *  IdentifyPriorityBarbarianTargets) and the moves of AssignBarbarianMoves are run in Vox Populi's fixed order ([assignBarbarianMoves]): camp
 *  defense, the attacks (the theft from cities without a garrison, [BarbarianTheft], then the attacks on every unit and city, which are
 *  [TacticalSimulation]'s with AL_BRAVEHEART, PlotBarbarianAttacks), capturing civilians, pillaging resources and improvements, plundering trade
 *  units, the roaming to far away targets ([BarbarianMoves.plotRoamingMoves]) and then moves to safety, the combat units first. The camp defense
 *  and the roaming are [BarbarianMoves], pillaging and the capture of civilians are [TacticalMoves], the trade unit plunder is [TradeMoves].
 *  Replaces the per-unit chain (pillage to heal, upgrade, attack nearby, pillage, wander) and, since the original no longer has it, the list in
 *  the order of AI_TACTICAL_BARBARIAN_PRIORITY_* with a die roll of AI_TACTICAL_MOVE_PRIORITY_RANDOMNESS per move, and the escort of captured civilians.
 *
 *  Not here: CvBarbarians::DoTakeOverCityState (no city-state quests, so none is ever ready for a take-over), the blockade move (Vox Populi has it
 *  for the other players only), the barbarian release turn (Unciv's spawner has its own start).
 */
class BarbarianAutomation(val civInfo: Civilization) {

    fun automate() = timeThis("AI.Barbarians") {
        val cache = AutomationCache()
        val danger = civInfo.ai.plotDanger
        // CommandeerUnits: all the barbarians that can move
        val units = civInfo.units.getCivUnits().filter { it.hasMovement() }.toList()
        if (units.isNotEmpty()) {
            val ctx = TacticalContext(civInfo, null, danger, cache, units, timeThis("AI.Barbarians.Targets") { TacticalTargets.find(civInfo, null) })
            assignBarbarianMoves(ctx)
            AiDecisionLog.log(civInfo) { "Barbarians: ${units.size} units, ${units.size - ctx.units.size} commanded, ${ctx.targets.all.size} targets" }
        }
        // fix buildup of alerts - to shrink saves and ease debugging
        civInfo.popupAlerts.clear()
    }

    /** CvTacticalAI::AssignBarbarianMoves: all the targets are the current ones, there are no zones */
    fun assignBarbarianMoves(ctx: TacticalContext) {
        ctx.currentTargets = ctx.targets.all
        // Even barbarians like their camps
        timeThis("AI.Barbarians.CampDefense") { BarbarianMoves.plotCampDefenseMoves(ctx) }
        // Barbarians do not have tactical zones, they just attack everything that moves (PlotBarbarianAttacks), after robbing the cities next to them
        timeThis("AI.Barbarians.Theft") { BarbarianTheft.executeTheft(ctx) }
        timeThis("AI.Barbarians.Attacks") {
            TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Braveheart)
            TacticalSimulation.executeCaptureCityMoves(ctx)
            for (type in TacticalAi.civilianTargetTypes) TacticalMoves.plotCivilianAttackMoves(ctx, type)
        }
        // Barbarians like to plunder as well
        timeThis("AI.Barbarians.Pillage") {
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, true)
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
            TradeMoves.plotPlunderTradeUnitMoves(ctx)
        }
        // Normal roaming to find targets
        timeThis("AI.Barbarians.Roaming") { BarbarianMoves.plotRoamingMoves(ctx) }
        // Safety comes last for the barbarians
        timeThis("AI.Barbarians.Safety") {
            SafestPlot.plotMovesToSafety(ctx, true)
            SafestPlot.plotMovesToSafety(ctx, false)
        }
    }
}
