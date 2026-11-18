package com.unciv.logic.automation.unit

import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 * Vox Populi's AddTradePathToCache and CvTradeAI::ApplyTradeRouteModifier (CvTradeClasses.cpp): political risk per
 * owned tile, visible enemy defenders and enemy-dominated tactical zones, plus at most ten unseen tiles. The city-state
 * ally supplies its political stance. The tactical map is the latest available map; absent maps contribute no zone risk.
 * VP's corporation invulnerability, passing-route and terrain trait yields are not present in the BNW rules base.
 */
class VpTradePathScore(private val civ: Civilization) {
    /** Positive trait yields per distinct trading partner, including Morocco's countable nation unique. */
    val partnerYield: Int = civ.nation.uniqueObjects.asSequence()
        .filter { it.type == UniqueType.Stats && it.modifiers.any { modifier ->
            modifier.type == UniqueType.ForEveryCountable && modifier.params[0] == "Trade Route Partners"
        } }
        .sumOf { unique -> Stat.entries.sumOf { if (it == Stat.Happiness) 0 else maxOf(0, unique.stats[it].toInt()) } }
    private val politicalDanger = civ.gameInfo.civilizations.associateWith { owner ->
        when {
            owner == civ -> 0
            civ.isAtWarWith(owner) -> 100
            else -> {
                val political = if (owner.isCityState) owner.allyCiv else owner
                if (political == null || political == civ || !political.isMajorCiv || !civ.knows(political)) 0
                else {
                    val approach = political.ai.diplomacy.approachTowards(civ, hideTrueFeelings = true)
                    val memory = civ.ai.diplomacy.memoryOf(political)
                    when {
                        approach == MajorCivApproach.Hostile || OpinionAi.isDenouncedPlayer(political, civ) -> 50
                        approach == MajorCivApproach.Guarded && memory.numWarsDeclaredOnUs > 0 -> 50
                        approach == MajorCivApproach.Guarded -> 25
                        else -> 0
                    }
                }
            }
        }
    }

    fun danger(path: List<Tile>): Int {
        var unseen = 0
        var danger = 0
        val map = civ.ai.tactical.lastMap
        for (tile in path) {
            if (!tile.isVisible(civ)) unseen++
            danger += politicalDanger[tile.getOwner()] ?: 0
            val defender = tile.militaryUnit
            if (defender != null && civ.isAtWarWith(defender.civ) && defender.isVisibleTo(civ) && TacticalSafety.canDefend(defender))
                danger += 200
            else if (map?.isInEnemyDominatedZone(tile) == true) danger += 100
        }
        return danger + minOf(unseen, 10)
    }

    fun apply(score: Int, path: List<Tile>): Int = apply(score.toLong(), path).toInt()
    fun apply(score: Long, path: List<Tile>): Long = score * 100 / (100 + danger(path))
}
