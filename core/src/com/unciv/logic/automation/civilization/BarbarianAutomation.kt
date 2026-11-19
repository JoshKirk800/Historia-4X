package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.unit.tactical.AggressionLevel
import com.unciv.logic.automation.unit.tactical.BarbarianMoves
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
 *  defense, the attacks on every unit and city (the attacks on units and cities are [TacticalSimulation]'s with AL_BRAVEHEART, PlotBarbarianAttacks),
 *  capturing civilians, pillaging resources and improvements, plundering trade units and then moves to safety, the combat units first. The
 *  hunting, sea raids, camp defense and captive escort are [BarbarianMoves], pillaging and the capture of civilians are [TacticalMoves], the trade
 *  unit plunder is [TradeMoves]. Replaces the per-unit chain (pillage to heal, upgrade, attack nearby, pillage, wander) and, since the original no
 *  longer has it, the list in the order of AI_TACTICAL_BARBARIAN_PRIORITY_* with a die roll of AI_TACTICAL_MOVE_PRIORITY_RANDOMNESS per move.
 *
 *  Deviations: the roaming of the units that are left is Brave New World's PlotBarbarianMove ([BarbarianMoves.executeMoves]: it ends the turn of
 *  every unit it handles, where Vox Populi's ExecuteBarbarianRoaming leaves a unit that still can move), so it comes after the moves to
 *  safety, not before them; the captive civilians' escort to a camp (Brave New World's, Vox Populi's roaming moves them) goes first.
 *  Not here: ExecuteBarbarianTheft (stealing from or taking over a city-state next to a unit: CvBarbarians::DoStealFromCity and DoTakeOverCityState
 *  have no counterpart), the blockade move (also commented out in Brave New World), the barbarian release turn (Unciv's spawner has its own start).
 */
class BarbarianAutomation(val civInfo: Civilization) {

    fun automate() = timeThis("AI.Barbarians") {
        val cache = AutomationCache()
        val danger = civInfo.ai.plotDanger
        // CommandeerUnits: all the barbarians that can move
        val units = civInfo.units.getCivUnits().filter { it.hasMovement() }.toList()
        val state = civInfo.ai.tactical
        val alive = civInfo.units.getCivUnits().mapTo(HashSet()) { it.id }
        state.unitDestinations.keys.retainAll(alive)
        if (units.isNotEmpty()) {
            val ctx = TacticalContext(civInfo, null, danger, cache, units, TacticalTargets.find(civInfo, null, danger))
            assignBarbarianMoves(ctx)
            AiDecisionLog.log(civInfo) { "Barbarians: ${units.size} units, ${units.size - ctx.units.size} commanded, ${ctx.targets.all.size} targets" }
        }
        // fix buildup of alerts - to shrink saves and ease debugging
        civInfo.popupAlerts.clear()
    }

    /** CvTacticalAI::AssignBarbarianMoves: all the targets are the current ones, there are no zones */
    fun assignBarbarianMoves(ctx: TacticalContext) {
        ctx.currentTargets = ctx.targets.all
        BarbarianMoves.executeCivilianEscortMoves(ctx)
        // Even barbarians like their camps
        BarbarianMoves.plotCampDefenseMoves(ctx)
        // Barbarians do not have tactical zones, they just attack everything that moves (PlotBarbarianAttacks)
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Braveheart)
        TacticalSimulation.executeCaptureCityMoves(ctx)
        for (type in TacticalAi.civilianTargetTypes) TacticalMoves.plotCivilianAttackMoves(ctx, type)
        // Barbarians like to plunder as well
        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, true)
        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
        TradeMoves.plotPlunderTradeUnitMoves(ctx)
        // Safety comes last for the barbarians...
        SafestPlot.plotMovesToSafety(ctx, true)
        SafestPlot.plotMovesToSafety(ctx, false)
        // ...and the roaming to find targets for the units that did not attack or flee
        BarbarianMoves.executeMoves(ctx, true)
    }
}
