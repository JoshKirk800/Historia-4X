package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.Counter
import yairm210.purity.annotations.Readonly

/**
 *  Vox Populi's record of the gains of the last turns that had one (CvPlayer::changeInstantYieldValue, V:CvPlayer.cpp:42605-42623, and
 *  changeInstantTourismPerPlayerValue, V:CvPlayer.cpp:43668-43686): an entry for each such turn with its amounts by key, the oldest dropped
 *  once there are [LENGTH]. Read as an average per turn by the influence trend.
 */
class InstantHistory : IsPartOfGameInfoSerialization {
    class Entry : IsPartOfGameInfoSerialization {
        var turn = 0
        var amounts = Counter<String>()
    }

    var entries = ArrayList<Entry>()
        private set

    fun add(turn: Int, key: String, amount: Int) {
        if (amount == 0) return
        if (entries.isEmpty() || entries.last().turn < turn) {
            if (entries.size == LENGTH) entries.removeAt(0)
            entries.add(Entry().also { it.turn = turn })
        }
        if (entries.last().turn == turn) entries.last().amounts.add(key, amount)
    }

    /** What [key] has had over the [LENGTH] turns before [turn] and [turn] itself, divided by that many turns, cut to a whole number
     *  (CvPlayer::getInstantTourismPerPlayerAvg over INSTANT_YIELD_HISTORY_LENGTH, V:CvPlayer.cpp:43693-43706) */
    @Readonly
    fun recentAverage(key: String, turn: Int): Int =
        entries.filter { it.turn in turn - LENGTH..turn }.sumOf { it.amounts[key] } / (LENGTH + 1)

    fun clone(): InstantHistory {
        val toReturn = InstantHistory()
        for (entry in entries) toReturn.entries.add(Entry().also { it.turn = entry.turn; it.amounts = entry.amounts.clone() })
        return toReturn
    }

    companion object {
        /** INSTANT_YIELD_HISTORY_LENGTH, V:CvPlayer.h:67 */
        const val LENGTH = 30
    }
}
