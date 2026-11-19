package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City

/**
 *  What the units around a dominance zone do in the posture [PostureSelection] chose for it: CvTacticalAI::ProcessDominanceZones (the loop over
 *  the zones, [processZones], with the switch on the posture, [plotPostureMoves]), PlotAttritionAttacks, PlotExploitFlanksMoves, PlotSteamrollMoves,
 *  PlotSurgicalCityStrikeMoves, PlotHedgehogMoves, PlotCounterattackMoves and PlotWithdrawMoves ([WithdrawMoves]). The attacks are Vox Populi's:
 *  every unit target of the zone gets one combat simulation ([TacticalSimulation.executeDestroyEnemyUnits] through [TacticalMoves.destroyEnemyUnits])
 *  with the aggression of the posture ([AggressionLevel.Low] for attrition and the hedgehog, [AggressionLevel.Medium] for exploiting flanks, surgical
 *  city strikes and counterattacks, [AggressionLevel.High] for the steamroll) and the cities are taken by [TacticalSimulation.executeCaptureCityMoves];
 *  the units that are not in the fight come to the zone in the second pass ([ReinforcementMoves]), the hedgehog asks for it early.
 *
 *  Not Vox Populi's: shore bombardment and the sit-and-bombard posture of the temporary operation zones are BNW's ([TacticalBombards]), as is
 *  the closing in on a temporary zone's target ([Deployment.executeCloseOnTarget]); they stay until the operations use Vox Populi's focus areas.
 */
object Postures {

    /**
     *  The zone loop of CvTacticalAI::ProcessDominanceZones: for each zone in the order of their value, [beforeZone] (PlotEmergencyPurchases, asked
     *  of every zone), then, if the zone has targets (or is a temporary zone or land around an enemy city, which are always processed, there may be
     *  an operation aiming at them), [moves] (the moves of its posture, [plotPostureMoves]) with the zone's targets as the current ones. Every zone
     *  with targets is processed, as in Vox Populi 5.4.8: the barbarians, whom the strengths of the zones ignore, are fought in the zone they stand in.
     */
    fun processZones(
        ctx: TacticalContext,
        beforeZone: (DominanceZone) -> Unit = {},
        moves: (DominanceZone, TacticalPosture) -> Unit = { zone, posture -> plotPostureMoves(ctx, zone, posture) },
    ) {
        for (zone in ctx.map.zones) {
            beforeZone(zone)
            val targets = ctx.targets.forZone(zone)
            // Must have some moves to continue or it must be land around an enemy city (which we always want to process because we might
            // have an operation targeting it)
            if (targets.isEmpty() && zone.territory != TacticalTerritory.TempZone && (zone.territory != TacticalTerritory.Enemy || zone.isWater)) continue
            ctx.currentTargets = targets
            try {
                moves(zone, ctx.postureOf(zone))
            } finally {
                ctx.currentTargets = ctx.targets.all
            }
        }
    }

    /** The switch of CvTacticalAI::ProcessDominanceZones: the moves of [posture] for [zone], the targets of the zone being the current ones */
    fun plotPostureMoves(ctx: TacticalContext, zone: DominanceZone, posture: TacticalPosture) {
        when (posture) {
            // No posture assigned so do nothing
            TacticalPosture.None -> Unit
            // Give up
            TacticalPosture.Withdraw -> WithdrawMoves.plotWithdrawMoves(ctx, zone)
            // Defend
            TacticalPosture.Hedgehog -> hedgehogMoves(ctx, zone)
            // Low risk attacks on units
            TacticalPosture.AttritFromRange -> attritFromRangeMoves(ctx)
            // Try to kill enemy units
            TacticalPosture.ExploitFlanks -> exploitFlanksMoves(ctx)
            // Attack everything including cities
            TacticalPosture.Steamroll -> steamrollMoves(ctx)
            // Go for the city first
            TacticalPosture.SurgicalCityStrike -> surgicalCityStrikeMoves(ctx)
            // Concentrated fire on enemy units
            TacticalPosture.Counterattack -> counterattackMoves(ctx)
            // Brave New World's, for the temporary zones of the operations
            TacticalPosture.SitAndBombard -> sitAndBombardMoves(ctx, zone)
            TacticalPosture.ShoreBombardment -> shoreBombardmentMoves(ctx)
        }
        // Brave New World's, for the temporary zones of the operations: the army closes in on the target
        if (zone.territory == TacticalTerritory.TempZone) plotCloseOnTarget(ctx, zone)
    }

    private fun cityTarget(zone: DominanceZone, city: City) =
        TacticalTarget(TacticalTargetType.City, city.getCenterTile(), zone.id, city.civ, city = city)

    private fun unitTargets(ctx: TacticalContext, includeLow: Boolean = true) = ctx.currentTargets.filter {
        it.type == TacticalTargetType.HighPriorityUnit || it.type == TacticalTargetType.MediumPriorityUnit ||
            includeLow && it.type == TacticalTargetType.LowPriorityUnit
    }

    /** The bombards (safe, then protected) at the target; [TacticalBombards.executeSafeBombards] wants the bombard cells of the target set */
    private fun bombard(ctx: TacticalContext, target: TacticalTarget): Boolean {
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        val underway = TacticalBombards.executeSafeBombards(ctx, target)
        val protectedBombard = target.isAlive(ctx.civ) && TacticalBombards.executeProtectedBombards(ctx, target)
        return underway || protectedBombard
    }

    /**
     *  The BNW posture of the temporary operation zones, win an attrition campaign with bombardments: the zone's city is shot at from safe tiles,
     *  taken if the units that reach it can, shot at from protected tiles; the units near it are bombarded.
     */
    fun sitAndBombardMoves(ctx: TacticalContext, zone: DominanceZone) {
        val city = zone.closestCity ?: return
        val target = cityTarget(zone, city)

        // Attack primary target (city)
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        TacticalBombards.executeSafeBombards(ctx, target)
        if (target.isAlive(ctx.civ)) {
            TacticalSimulation.executeCaptureCityMoves(ctx)
            if (target.isAlive(ctx.civ)) TacticalBombards.executeProtectedBombards(ctx, target)
        }

        // Attack ancillary target (nearby units)
        for (unitTarget in unitTargets(ctx)) if (unitTarget.isAlive(ctx.civ)) bombard(ctx, unitTarget)
    }

    /** CvTacticalAI::PlotAttritionAttacks, win an attrition campaign: low risk attacks on units (the plan only attacks where it does more damage than it takes) */
    fun attritFromRangeMoves(ctx: TacticalContext) {
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Low)
        // Our units are not exposed for city attacks here: if we are likely to succeed this posture is not chosen
    }

    /** CvTacticalAI::PlotExploitFlanksMoves, try to kill enemy units: attacks that accept slightly more damage than they deal, then the city, which may be wide open with its defenders elsewhere */
    fun exploitFlanksMoves(ctx: TacticalContext) {
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Medium)
        TacticalSimulation.executeCaptureCityMoves(ctx)
    }

    /** CvTacticalAI::PlotSteamrollMoves, we have more overall strength than the enemy, defeat his army first: attacks that survive a counterattack, then the city */
    fun steamrollMoves(ctx: TacticalContext) {
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.High)
        TacticalSimulation.executeCaptureCityMoves(ctx)
    }

    /** CvTacticalAI::PlotSurgicalCityStrikeMoves, we should be strong enough to take out the city before the enemy can whittle us down with ranged attacks: the city first, then any other really good attacks */
    fun surgicalCityStrikeMoves(ctx: TacticalContext) {
        TacticalSimulation.executeCaptureCityMoves(ctx)
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Medium)
    }

    /**
     *  CvTacticalAI::PlotHedgehogMoves, build a defensive shell around this city: careful attacks on the units around it, then the early
     *  reinforcement before the attacks in other zones are considered ([ReinforcementMoves.plotReinforcementMoves]; the second pass asks for it again)
     */
    fun hedgehogMoves(ctx: TacticalContext, zone: DominanceZone) {
        // Be careful with our units, we do not have so many
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Low)
        // Exception: early reinforcement before attacks in other zones are considered
        ReinforcementMoves.plotReinforcementMoves(ctx, zone)
    }

    /** CvTacticalAI::PlotCounterattackMoves, try to push back the invader: attacks that accept slightly more damage than they deal */
    fun counterattackMoves(ctx: TacticalContext) {
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Medium)
    }

    /** CvTacticalAI::PlotShoreBombardmentMoves, bombard any enemy targets we can get to on shore: ships shoot the unit targets, then the cities */
    fun shoreBombardmentMoves(ctx: TacticalContext) {
        for (target in unitTargets(ctx)) {
            if (!target.isAlive(ctx.civ)) continue
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
            TacticalBombards.executeSafeBombards(ctx, target)
        }
        for (target in ctx.currentTargets.filter { it.type == TacticalTargetType.City }) {
            if (!target.isAlive(ctx.civ)) continue
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
            TacticalBombards.executeSafeBombards(ctx, target)
        }
    }

    /**
     *  Brave New World's CvTacticalAI::PlotCloseOnTarget for the temporary zone of an operation (Vox Populi has none: the units come to a zone in the
     *  second pass, [ReinforcementMoves]): units close in on the target the zone is about, the camp or city of the operation, unless the enemy
     *  dominates the zone.
     */
    fun plotCloseOnTarget(ctx: TacticalContext, zone: DominanceZone) {
        if (zone.dominance == TacticalDominance.Enemy) return
        Deployment.executeCloseOnTarget(ctx, zone.tempZoneCenter ?: return, zone)
    }
}
