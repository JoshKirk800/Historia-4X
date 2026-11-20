package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.AirMoves
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  CvTacticalAI::Update for one civilization's turn: the units under tactical control ([recruitUnits]), the targets ([TacticalTargets]), then the
 *  moves in the hardcoded order of Vox Populi's ProcessDominanceZones ([automate]): healing, the armies of the operations ([OperationalMoves]) and the
 *  garrisons ([GarrisonMoves]); for each dominance zone the emergency purchases and the moves of its posture (the attacks on units and cities by
 *  [TacticalSimulation], hedgehog, withdrawing - [Postures], [WithdrawMoves]); the reinforcements ([ReinforcementMoves]); air patrols ([AirMoves]), ruins,
 *  capturing civilians, pillaging and plundering ([TacticalMoves], [TradeMoves], [GuardMoves]); bastions and moves to safety ([SafestPlot]); guarding
 *  improvements; escorting embarked units ([NavalMoves]); and the review of the units nothing moved. The order is no longer the `tacticalMoves` priority
 *  list of Brave New World's AI.json. A unit a move commands is added to [AutomationCache.processedUnits]; the other units, tactical or not, go on to the
 *  rest of the unit AI.
 *
 *  Not ported: naval blockades (PlotBlockadeMoves: its targets are the barbarians' blockade points, which [TacticalTargets] does not make). Kept from
 *  Brave New World, where Vox Populi has nothing: the air sweep, the defensive airlift and parking on trade routes.
 */
object TacticalAi {
    /**
     *  CvTacticalAI::RecruitUnits: every unit that [canUseForTacticalAi] is the tactical AI's this turn, wherever it stands: not the units in an army
     *  (the operations move those, [OperationalMoves] hands them over when the enemy is near), not units that cannot move or were commanded already,
     *  explorers (the homeland AI's), carriers and nukes (operations only), and, of the rest, combat units and Great Generals. Air units are all
     *  tactical when they will not rebase ([AirMoves.willAirUnitRebase]; the homeland AI rebases them) and have more than half their health.
     *  (Barbarians have their own AI, [com.unciv.logic.automation.civilization.BarbarianAutomation].)
     */
    fun recruitUnits(civ: Civilization, units: List<MapUnit>, cache: AutomationCache): List<MapUnit> {
        val result = ArrayList<MapUnit>()
        for (unit in units) {
            if (unit.civ != civ || !canUseForTacticalAi(unit, cache)) continue
            // CommandeerUnits: air units that are combat ready (more than half health) and not about to rebase
            if (unit.baseUnit.isAirUnit()) {
                if (AirMoves.isCombatReady(unit) && !AirMoves.willAirUnitRebase(unit)) result.add(unit)
                continue
            }
            if (!unit.isMilitary() && !unit.isGreatPersonOfType("War")) continue
            // The units of an army are moved as part of the army moves (setTacticalMove(AI_TACTICAL_OPERATION))
            if (civ.ai.operations.armyOf(unit) != null) continue
            result.add(unit)
        }
        return result
    }

    /**
     *  CvUnit::canUseForTacticalAI: alive, able to move, not commanded already this turn, not an explorer (the homeland AI's, however near the enemy),
     *  and not a carrier or a nuclear weapon, which only operations use.
     */
    fun canUseForTacticalAi(unit: MapUnit, cache: AutomationCache): Boolean {
        if (unit.isDestroyed || !unit.hasMovement() || unit in cache.processedUnits) return false
        return when (UnitAiTypes.current(unit)) {
            UnitAiType.EXPLORE, UnitAiType.EXPLORE_SEA, UnitAiType.CARRIER_SEA, UnitAiType.ICBM, UnitAiType.MISSILE_CARRIER_SEA -> false
            else -> true
        }
    }

    /** CvUnit::canParadrop(plot, false): the unit has a paradrop range, has not moved this turn, is not embarked and stands in friendly territory */
    fun canParadrop(unit: MapUnit): Boolean =
        unit.hasUnique(UniqueType.MayParadrop) && !unit.hasUnitMovedThisTurn() && !unit.isEmbarked() && unit.getTile().isFriendlyTerritory(unit.civ)

    /** The tiles of the visible units and cities of players at war with [civ] (CvTacticalAI::NearVisibleEnemy's candidates) */
    fun visibleEnemyTiles(civ: Civilization): List<Tile> {
        val tiles = ArrayList<Tile>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (unit in other.units.getCivUnits()) if (unit.isVisibleTo(civ)) tiles.add(unit.getTile())
            for (city in other.cities) if (city.getCenterTile() in civ.viewableTiles) tiles.add(city.getCenterTile())
        }
        return tiles
    }

    /**
     *  The tactical turn (CvTacticalAI::Update, ProcessDominanceZones): find the targets, then run the moves in Vox Populi's hardcoded order, each
     *  commanding the units it needs. [units] are the civilization's units in the order they would move.
     *
     *  AssignGlobalHighPrioMoves: healing before the attacks (to make space near the front line), the armies of the operations, the garrisons (they
     *  sometimes make a sortie and have to get back; the sweeps of the aircraft are Brave New World's, Vox Populi sweeps in its attack plans).
     *  Then for each dominance zone, the most valuable first: the emergency purchases, and the moves of its posture ([Postures.processZones]).
     *  The second pass brings in the reinforcements ([ReinforcementMoves]; with them Brave New World's defensive airlift). AssignGlobalMidPrioMoves:
     *  air patrols, ruins, capturing civilians, the bastions, moving to safety (the combat units, those not in an army), healing again now that
     *  units have moved out of the way, and harassing the enemy (pillaging and plundering trade routes). AssignGlobalLowPrioMoves: guarding improvements
     *  for the next turn, escorting embarked units, the civilians out of harm's way last. Then the failsafe, [reviewUnassignedUnits].
     */
    fun automate(civ: Civilization, units: List<MapUnit>, cache: AutomationCache) = timeThis("AI.Tactical") {
        val state = civ.ai.tactical
        val tacticalUnits = recruitUnits(civ, units, cache)
        val recruited = tacticalUnits.size
        val map = state.map
        PostureSelection.updatePostures(civ, state, map)
        val danger = civ.ai.plotDanger
        val ctx = TacticalContext(civ, map, danger, cache, tacticalUnits, TacticalTargets.find(civ, map, danger), units)
        val highPriority by lazy { ctx.targets.all.filter { it.type == TacticalTargetType.HighPriorityUnit }.mapNotNullTo(HashSet()) { it.unit } }

        // CvTacticalAI::ExtractTargetsForZone(NULL) before each of the global groups: all the targets are the current ones
        fun globally(name: String, move: () -> Unit) = timeThis("AI.Tactical.$name") { ctx.currentTargets = ctx.targets.all; move() }

        // AssignGlobalHighPrioMoves
        globally("Heal1") { TacticalHealing.plotHealMoves(ctx, true) }
        globally("Operations") { OperationalMoves.plotOperationalArmyMoves(ctx) }
        globally("Garrison2") { GarrisonMoves.plotGarrisonMoves(ctx, 2) }
        globally("AirSweep") { AirMoves.plotAirSweepMoves(ctx) }

        // Then confront the enemy in each tactical zone
        timeThis("AI.Tactical.Zones") {
            Postures.processZones(ctx, beforeZone = { zone ->
                if (civ.isAIOrAutoPlaying() && zone.dominance != TacticalDominance.Friendly && zone.territory == TacticalTerritory.Friendly)
                    EmergencyPurchases.plot(civ, zone, map) { highPriority }
            })
        }

        // Second pass: bring in reinforcements
        timeThis("AI.Tactical.Reinforcements") {
            for (zone in map.zones) {
                ctx.currentTargets = ctx.targets.all
                if (zone.dominance != TacticalDominance.Friendly && zone.territory == TacticalTerritory.Friendly) plotDefensiveAirlifts(ctx, zone)
                ReinforcementMoves.plotReinforcementMoves(ctx, zone)
            }
        }

        // AssignGlobalMidPrioMoves
        globally("AirPatrol") { AirMoves.plotAirPatrolMoves(ctx) }
        globally("Goodies") { GuardMoves.plotGrabGoodyMoves(ctx) }
        globally("Civilians") {
            for (type in civilianTargetTypes) TacticalMoves.plotCivilianAttackMoves(ctx, type)
        }
        globally("Bastion2") { GuardMoves.plotBastionMoves(ctx, 2) }
        // Now all attacks are done, try to move any unprocessed units out of harm's way
        globally("Safety1") { SafestPlot.plotMovesToSafety(ctx, true) }
        // Try again now that other blocking units might have moved
        globally("Heal2") { TacticalHealing.plotHealMoves(ctx, false) }
        // Harass the enemy (plundering also happens during the combat simulation)
        globally("Pillage") {
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Citadel, true)
            TradeMoves.plotPlunderTradeUnitMoves(ctx)
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, true)
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Citadel, false)
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, false)
            TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, false)
            TradeMoves.plotParkOnTradeRouteMoves(ctx)
        }

        // AssignGlobalLowPrioMoves: defense preparation for next turn
        globally("Guard2") { GuardMoves.plotGuardImprovementMoves(ctx, 2) }
        // Do this last after the units in need have already moved
        globally("NavalEscort") { NavalMoves.plotEscortEmbarkedMoves(ctx) }
        // Civilians move out of harm's way last, when all potential defenders are set in place
        globally("Safety2") { SafestPlot.plotMovesToSafety(ctx, false) }

        // Failsafe
        globally("Review") { reviewUnassignedUnits(ctx) }
        AiDecisionLog.log(civ) {
            "Tactical: $recruited units recruited, ${recruited - ctx.units.size} commanded, ${ctx.targets.all.size} targets; postures " +
                state.postures.filter { it.posture != TacticalPosture.None }.groupingBy { "${it.posture}${if (it.isWater) " (sea)" else ""}" }.eachCount()
                    .entries.joinToString { if (it.value > 1) "${it.key} x${it.value}" else it.key }.ifEmpty { "-" }
        }
    }

    /**
     *  PlotCivilianAttackMoves' target types, the most valuable first: Vox Populi has two classes of civilian targets (high and low priority) which
     *  its ExecuteCivilianAttackMoves takes in that order; ours has four (generals and admirals, captured settlers and workers...) in the order of Brave New World's priorities
     */
    val civilianTargetTypes = listOf(
        TacticalTargetType.VeryHighPriorityCivilian, TacticalTargetType.HighPriorityCivilian,
        TacticalTargetType.MediumPriorityCivilian, TacticalTargetType.LowPriorityCivilian
    )

    /**
     *  CvTacticalAI::ReviewUnassignedUnits: the units of the tactical AI that nothing moved and that stand where the danger is more than they heal
     *  in a turn go to the safest plot in reach ([SafestPlot.find], embarking allowed), upgrading there if they are not hurt; one that cannot move
     *  on is done. The rest are left to the homeland AI, with their movement.
     *  Not ported: the cities' fallback shot at a unit in range, whose target the tactical targets suppressed (the cities shoot in the unit AI).
     */
    fun reviewUnassignedUnits(ctx: TacticalContext) {
        val danger = ctx.civ.ai.unitDanger
        for (unit in ctx.liveUnits()) {
            if (!unit.hasMovement() || unit.baseUnit.isAirUnit()) continue
            // There should not be any danger but just in case
            if (danger.get(unit) <= TacticalSafety.actualHealRate(unit)) continue
            unit.movement.clearPathfindingCache()
            val safePlot = SafestPlot.find(ctx, unit, allowEmbark = true)?.tile ?: continue
            if (safePlot != unit.getTile()) TacticalMoves.moveSwapping(unit, safePlot)
            var current = unit
            // Do not upgrade a hurt unit
            if (!current.isDestroyed && current.health >= MAX_HEALTH) UseGoldAutomation.upgradeNow(current)?.let { current = it }
            if (!current.isDestroyed && !current.hasMovement()) ctx.unitProcessed(current)
        }
    }

    private const val MAX_HEALTH = 100

    /**
     *  CvTacticalAI::PlotDefensiveAirlifts for one zone: not for city-states. In the zone around one of our cities that allows airlifts
     *  ([com.unciv.logic.city.City.canAirlift]), on our own land, where enemy units are, every tactical unit that can be airlifted ([com.unciv.logic.map.mapunit.movement.UnitMovement.airliftTo])
     *  to a tile next to the city (the first one that works) goes there.
     *  @return the units airlifted
     */
    fun plotDefensiveAirlifts(ctx: TacticalContext, zone: DominanceZone): List<MapUnit> {
        val civ = ctx.civ
        if (civ.isCityState) return emptyList()
        val city = zone.closestCity ?: return emptyList()
        if (city.civ != civ || zone.territory != TacticalTerritory.Friendly || !city.canAirlift() || zone.totalEnemyUnitCount <= 0) return emptyList()

        val airlifted = ArrayList<MapUnit>()
        for (unit in ctx.liveUnits()) {
            val from = unit.getTile()
            val target = city.getCenterTile().neighbors.firstOrNull { unit.movement.canAirliftTo(it) } ?: continue
            if (!unit.movement.airliftTo(target)) continue
            ctx.log { "${unit.name} airlifted from ${from.position} to ${city.name}" }
            airlifted.add(unit)
        }
        for (unit in airlifted) ctx.unitProcessed(unit)
        return airlifted
    }
}
