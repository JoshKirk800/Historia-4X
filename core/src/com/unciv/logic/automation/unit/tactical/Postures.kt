package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City

/**
 *  What the units around a dominance zone do in the posture [PostureSelection] chose for it: CvTacticalAI::ProcessDominanceZones (the loop over
 *  the zones that the zone moves of the priority list run), PlotAttritionAttacks, PlotExploitFlanksMoves, PlotSteamrollMoves,
 *  PlotSurgicalCityStrikeMoves, PlotHedgehogMoves, PlotCounterattackMoves and PlotCloseOnTarget. The attacks are Vox Populi's: every unit target
 *  of the zone gets one combat simulation ([TacticalSimulation.executeDestroyEnemyUnits] through [TacticalMoves.destroyEnemyUnits]) with the
 *  aggression of the posture ([AggressionLevel.Low] for attrition and the hedgehog, [AggressionLevel.Medium] for exploiting flanks, surgical city
 *  strikes and counterattacks, [AggressionLevel.High] for the steamroll) and the cities are taken by [TacticalSimulation.executeCaptureCityMoves].
 *  Shore bombardment and the sit-and-bombard posture of the temporary operation zones are BNW's ([TacticalBombards]); the placing of units is
 *  [Deployment].
 */
object Postures {

    const val OFFENSIVE_POSTURE_MOVES = "TACTICAL_OFFENSIVE_POSTURE_MOVES"
    const val SHORE_BOMBARDMENT = "TACTICAL_POSTURE_SHORE_BOMBARDMENT"
    const val HEDGEHOG = "TACTICAL_POSTURE_HEDGEHOG"
    const val CLOSE_ON_TARGET = "TACTICAL_CLOSE_ON_TARGET"

    private val offensivePostures = setOf(
        TacticalPosture.SitAndBombard, TacticalPosture.AttritFromRange, TacticalPosture.ExploitFlanks,
        TacticalPosture.Steamroll, TacticalPosture.SurgicalCityStrike, TacticalPosture.Counterattack
    )

    /**
     *  The zone loop of CvTacticalAI::ProcessDominanceZones for one zone move: [move] is run for each zone in the order of their value whose
     *  posture [matches] and that has targets (or is a temporary zone or land around an enemy city, which are always processed, there may be an
     *  operation aiming at them), with the zone's targets as the current ones. Every zone with targets is processed, as in Vox Populi 5.4.8: the
     *  barbarians, whom the strengths of the zones ignore, are fought in the zone they stand in.
     */
    fun forEachZone(ctx: TacticalContext, matches: (DominanceZone, TacticalPosture) -> Boolean, move: (DominanceZone, TacticalPosture) -> Unit) {
        val state = ctx.state
        for (zone in ctx.map.zones) {
            val posture = state.findPosture(zone.owner?.civName, zone.isWater, zone.closestCity?.id)
            if (!matches(zone, posture)) continue
            val targets = ctx.targets.forZone(zone)
            // Must have some moves to continue or it must be land around an enemy city (which we always want to process because we might
            // have an operation targeting it)
            if (targets.isEmpty() && zone.territory != TacticalTerritory.TempZone && (zone.territory != TacticalTerritory.Enemy || zone.isWater)) continue
            ctx.currentTargets = targets
            try {
                move(zone, posture)
            } finally {
                ctx.currentTargets = ctx.targets.all
            }
        }
    }

    /** TACTICAL_OFFENSIVE_POSTURE_MOVES: every zone whose posture is an offensive one runs that posture's move, the zones in the order of their value */
    fun offensivePostureMoves(ctx: TacticalContext) =
        forEachZone(ctx, { _, posture -> posture in offensivePostures }) { zone, posture ->
            when (posture) {
                TacticalPosture.SitAndBombard -> sitAndBombardMoves(ctx, zone)
                TacticalPosture.AttritFromRange -> attritFromRangeMoves(ctx)
                TacticalPosture.ExploitFlanks -> exploitFlanksMoves(ctx)
                TacticalPosture.Steamroll -> steamrollMoves(ctx, zone)
                TacticalPosture.SurgicalCityStrike -> surgicalCityStrikeMoves(ctx, zone)
                TacticalPosture.Counterattack -> counterattackMoves(ctx)
                else -> Unit
            }
        }

    /** TACTICAL_POSTURE_SHORE_BOMBARDMENT */
    fun shoreBombardmentZones(ctx: TacticalContext) =
        forEachZone(ctx, { _, posture -> posture == TacticalPosture.ShoreBombardment }) { _, _ -> shoreBombardmentMoves(ctx) }

    /** TACTICAL_POSTURE_HEDGEHOG */
    fun hedgehogZones(ctx: TacticalContext) =
        forEachZone(ctx, { _, posture -> posture == TacticalPosture.Hedgehog }) { zone, _ -> hedgehogMoves(ctx, zone) }

    /** TACTICAL_CLOSE_ON_TARGET: okay for all zones */
    fun closeOnTargetZones(ctx: TacticalContext) = forEachZone(ctx, { _, _ -> true }) { zone, _ -> plotCloseOnTarget(ctx, zone, true) }

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
     *  taken if the units that reach it can, shot at from protected tiles; the units near it are bombarded; then the army closes in.
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

        plotCloseOnTarget(ctx, zone, true)
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

    /** CvTacticalAI::PlotSteamrollMoves, we have more overall strength than the enemy, defeat his army first: attacks that survive a counterattack, then the city; then close in */
    fun steamrollMoves(ctx: TacticalContext, zone: DominanceZone) {
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.High)
        TacticalSimulation.executeCaptureCityMoves(ctx)
        plotCloseOnTarget(ctx, zone, false)
    }

    /** CvTacticalAI::PlotSurgicalCityStrikeMoves, we should be strong enough to take out the city before the enemy can whittle us down with ranged attacks: the city first, then any other really good attacks; then close in */
    fun surgicalCityStrikeMoves(ctx: TacticalContext, zone: DominanceZone) {
        TacticalSimulation.executeCaptureCityMoves(ctx)
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Medium)
        plotCloseOnTarget(ctx, zone, false)
    }

    /** CvTacticalAI::PlotHedgehogMoves, build a defensive shell around this city: careful attacks on the units around it, then the shell */
    fun hedgehogMoves(ctx: TacticalContext, zone: DominanceZone) {
        // Be careful with our units, we do not have so many
        TacticalMoves.destroyEnemyUnits(ctx, AggressionLevel.Low)
        // After the best attacks are exhausted go right to playing defense
        val city = zone.closestCity ?: return
        Deployment.executeHedgehogDefense(ctx, city.getCenterTile(), zone)
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
     *  CvTacticalAI::PlotCloseOnTarget, close units in on the primary target of the dominance zone: the camp a temporary zone is about, or the
     *  city of an enemy zone if we cannot see it or are not outmatched by more than half. Not in a zone the enemy dominates if [checkDominance].
     *  Closing in is BNW's, not Vox Populi's, and only for a zone that is worth it: an operation's target (temporary zone) or an enemy city that
     *  units of ours are near (BNW's CvTacticalAnalysisMap::UseThisDominanceZone), not a far-away one that merely has targets.
     */
    fun plotCloseOnTarget(ctx: TacticalContext, zone: DominanceZone, checkDominance: Boolean) {
        if (checkDominance && zone.dominance == TacticalDominance.Enemy) return
        // Flank attacks done; if in an enemy zone, close in on target
        if (zone.territory == TacticalTerritory.TempZone) {
            Deployment.executeCloseOnTarget(ctx, zone.tempZoneCenter ?: return, zone)
        } else if (zone.territory == TacticalTerritory.Enemy) {
            val city = zone.closestCity ?: return
            if (zone.totalFriendlyUnitCount == 0 && !ctx.state.isTemporaryZoneCity(city)) return
            val canSeeCity = city.getCenterTile() in ctx.civ.viewableTiles
            // If we can't see the city, be careful advancing on it. We want to be sure we're not heavily outnumbered
            if (!canSeeCity || zone.overallFriendlyStrength > zone.overallEnemyStrength / 2) Deployment.executeCloseOnTarget(ctx, city.getCenterTile(), zone)
        }
    }
}
