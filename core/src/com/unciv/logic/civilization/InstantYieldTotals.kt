package com.unciv.logic.civilization

import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly

/**
 *  What a civilization has gained from instant yields ([InstantYields.gain]) since this game was loaded or started, by the event that triggered
 *  each gain and the stat, for the economy rows of the decision log (`AI_INSTANT`, `tests/.../dev/EconomyColumns.kt`). A study measure only: nothing
 *  in the game reads it, it is not saved and not copied by [Civilization.clone].
 *
 *  Departure: Vox Populi keeps these totals for its interface (CvPlayer::m_viInstantYieldsTotal, one counter for each yield; the plan decision O6
 *  leaves them out of the saved game), here they are by event as well, since the study compares what each event contributes.
 */
class InstantYieldTotals {
    private val totals = HashMap<Pair<String, Stat>, Int>()

    /** Adds [amount] of [stat], gained because of [event] (the name of the trigger condition's [com.unciv.models.ruleset.unique.UniqueType], or [DIRECT]) */
    fun add(event: String, stat: Stat, amount: Int) {
        if (amount != 0) totals.merge(event to stat, amount, Int::plus)
    }

    /** Every total that is not 0 as (event, stat, amount), sorted by event and stat so the rows of the log are stable */
    @Readonly
    fun entries(): List<Triple<String, Stat, Int>> =
        totals.entries.filter { it.value != 0 }
            .sortedWith(compareBy({ it.key.first }, { it.key.second.ordinal }))
            .map { Triple(it.key.first, it.key.second, it.value) }

    @Readonly
    fun total(event: String, stat: Stat): Int = totals[event to stat] ?: 0

    companion object {
        /** The event of a gain whose unique has no trigger condition: a building's own gain at construction, a unit action, ancient ruins... */
        const val DIRECT = "Direct"
    }
}
