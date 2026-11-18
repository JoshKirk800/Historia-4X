package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteCandidate
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.TradeRouteKind

/**
 * The trade AI: CvTradeAI (CvTradeClasses.cpp) and CvHomelandAI::ExecuteTradeUnitMoves.
 *
 * Once per turn the civilization lists every connection its cities could start (GetAvailableTR, connections that already carry a route included),
 * scores them (ScoreInternationalTR, ScoreFoodTR, ScoreProductionTR) and orders them; each idle trade unit then takes
 * the first entry of its domain it can start (ChooseTradeUnitTargetPlot).
 *
 * Ordering and scoring follow Vox Populi Release-5.4.8's CvTradeAI::GetPrioritizedTradeRoutes and ScoreInternalTR /
 * ScoreInternationalTR, adapted to the rules and yields available in the BNW base.
 *
 * Inputs: route danger [VpTradePathScore]; route yields [com.unciv.logic.civilization.managers.TradeRouteManager.preview].
 * Deviation: the change of home city (MISSION_CHANGE_TRADE_UNIT_HOME_CITY) is a walk to the origin city, Unciv has no such mission.
 */
object TradeRouteAutomation {

    /** CvHomelandAI::ExecuteTradeUnitMoves for one unit, with CvTradeAI::ChooseTradeUnitTargetPlot */
    fun automateTradeUnit(unit: MapUnit): Unit = timeThis("AI.TradeRoutes") {
        val civ = unit.civ
        val manager = civ.tradeRoutes
        val candidates = getPrioritizedRoutes(civ)
        val slots = manager.getNumSlots()
        val duplicates = countDuplicates(candidates, slots)
        val isSea = unit.baseUnit.isWaterUnit
        val here = unit.getTile().getCity()?.takeIf { it.civ == civ }

        for ((index, candidate) in candidates.withIndex()) {
            if (index >= slots + duplicates) {
                val tradeUnits = manager.routes.size + civ.units.getCivUnits().count { manager.isTradeUnit(it.baseUnit) }
                if (slots - tradeUnits == 0) {
                    // Wrong domain for every free slot: the unit is disbanded
                    unit.disband()
                    return@timeThis
                }
            }
            if (candidate.isSea != isSea) continue
            val destination = candidate.destination
            if (!manager.canConnect(candidate.origin, destination.city, destination.kind)) continue
            if (candidate.origin == here) {
                manager.establish(unit, destination.city, destination.kind)
            } else if (unit.movement.canReach(candidate.origin.getCenterTile())) {
                unit.movement.tryHeadTowards(candidate.origin.getCenterTile())
            }
            return@timeThis
        }
    }

    /** ChooseTradeUnitTargetPlot: entries among the first [slots] with the same origin, destination and kind */
    private fun countDuplicates(candidates: List<TradeRouteCandidate>, slots: Int): Int {
        val end = minOf(slots, candidates.size)
        var duplicates = 0
        for (i in 0 until end) for (j in i + 1 until end) {
            val a = candidates[i]
            val b = candidates[j]
            if (a.origin == b.origin && a.destination.city == b.destination.city && a.destination.kind == b.destination.kind) duplicates++
        }
        return duplicates
    }

    /** CvTradeAI::GetPrioritizedTradeRoutes, kept for the turn */
    fun getPrioritizedRoutes(civ: Civilization): List<TradeRouteCandidate> {
        val manager = civ.tradeRoutes
        if (manager.aiRoutesTurn != civ.gameInfo.turns) {
            manager.aiPrioritizedRoutes = timeThis("AI.PrioritizeTradeRoutes") { prioritizeTradeRoutes(civ) }
            manager.aiRoutesTurn = civ.gameInfo.turns
        }
        return manager.aiPrioritizedRoutes
    }

    /** Vox Populi's CvTradeAI::GetPrioritizedTradeRoutes: stable highest-first ordering across all route kinds */
    fun prioritizeTradeRoutes(civ: Civilization): List<TradeRouteCandidate> {
        val available = getAvailableRoutes(civ)
        if (available.isEmpty()) return available
        val pathScore = VpTradePathScore(civ)
        val internalScore = VpInternalTradeScore(civ, pathScore)
        val internationalScore = VpInternationalTradeScore(civ, pathScore)
        val scored = ArrayList<Pair<TradeRouteCandidate, Int>>(available.size)
        addScoredRoutes(scored, available) { if (it.destination.kind == TradeRouteKind.Production) internalScore.score(it) else 0 }
        addScoredRoutes(scored, available) { if (it.destination.kind == TradeRouteKind.Food) internalScore.score(it) else 0 }
        addScoredRoutes(scored, available) { internationalScore.score(it) }

        scored.sortByDescending { it.second }
        return scored.map { it.first }
    }

    private inline fun addScoredRoutes(
        result: MutableList<Pair<TradeRouteCandidate, Int>>,
        candidates: List<TradeRouteCandidate>,
        score: (TradeRouteCandidate) -> Int
    ) {
        for (candidate in candidates) {
            val value = score(candidate)
            if (value > 0) result.add(candidate to value)
        }
    }

    /** CvTradeAI::GetAvailableTR: from each of our cities to every city, by destination, land before sea, with a valid path */
    fun getAvailableRoutes(civ: Civilization): List<TradeRouteCandidate> {
        val manager = civ.tradeRoutes
        val domains = listOf(false, true).mapNotNull { isSea ->
            manager.getTradeUnitType(isSea)?.let { manager.getBaseRange(it) }?.let { isSea to it }
        }
        val result = ArrayList<TradeRouteCandidate>()
        for (origin in civ.cities) {
            val byDomain = domains.map { (isSea, range) -> isSea to manager.getDestinations(origin, isSea, range, ignoreExisting = true) }
            for (destinationCity in civ.gameInfo.getCities()) {
                for ((isSea, destinations) in byDomain)
                    for (destination in destinations)
                        if (destination.city == destinationCity) result.add(TradeRouteCandidate(origin, destination, isSea))
            }
        }
        return result
    }

}
