package com.unciv.logic.civilization.diplomacy

import com.unciv.models.ModConstants
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 *  The rules of Vox Populi's influence of a major with a city-state as pure functions of their inputs (CvMinorCivAI, V:CvMinorCivAI.cpp), used with
 *  [ModConstants.cityStateInfluenceLikeVp]. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The integer arithmetic of the source is kept, in the same order and with the same
 *  truncations (towards zero, as C++ and Kotlin do), because a different rounding moves the turn a city-state changes its status.
 */
object CityStateInfluence {
    /**
     *  What [changePerTurnTimes100] needs of the game, all in the units of the source: the influence and the resting point in hundredths, [shiftTimes100] the extra influence of policies
     *  and traits ("iShift", in hundredths), [recoveryPercent] `100 + shared religion + CityStateRecoveryModifier`, [dropTimes100] the decay of the turn before the modifiers (100, or 150 for
     *  a hostile city-state, 200 against a civilization that attacks city-states), [damagedCapitalProtected] whether the capital has taken damage and the major is its protector,
     *  [decayPercent] `100 + the decay modifiers` (below 0 is 0), [stopsDecay] whether this is an ally that does not decay (at war with the policy, or the Cold War embargo, V:10523-10531),
     *  [trainPercent] the game speed's `TrainPercent` and [ally] whether the major is the ally.
     */
    class Change(
        val currentTimes100: Int, val restingPointTimes100: Int, val shiftTimes100: Int = 0, val recoveryPercent: Int = 100, val dropTimes100: Int = 100,
        val damagedCapitalProtected: Boolean = false, val decayPercent: Int = 100, val stopsDecay: Boolean = false, val trainPercent: Int = 100, val ally: Boolean = false
    )

    /** CvMinorCivAI::GetFriendshipChangePerTurnTimes100 from the inputs of [i] on (V:CvMinorCivAI.cpp:10428-10562), in hundredths */
    @Readonly
    fun changePerTurnTimes100(constants: ModConstants.CityStateInfluenceConstants, i: Change): Int {
        val current = i.currentTimes100
        val resting = i.restingPointTimes100
        val shift = i.shiftTimes100
        if (current == resting) return shift

        var change = 0
        if (current < resting) {
            // below: influence goes up by the recovery, faster with the percentages
            change = constants.recoveryTimes100
            change = if (change > 0) change * i.recoveryPercent / 100 else 0
        } else {
            change = -i.dropTimes100
            val over = current - resting
            if (over >= constants.scalingDecayFull * 100) {
                // 100 over the resting point is -1 more, 200 is -2.82, 300 is -5.2 ...: the points over, in hundreds, to the exponent
                val extra = (over.toDouble() / (constants.scalingDecayFull * 100)).pow(constants.scalingDecayExponent) * -100.0
                change += extra.toInt()
            } else if (over > constants.scalingDecayStart * 100) {
                // from 50 over: -0.02 for each point more, until the 100
                change += (over - constants.scalingDecayStart * 100) / -50
            }
            if (i.damagedCapitalProtected) change = change * constants.damagedCapitalDecayPercent / 100
            change = if (change < 0) change * max(0, i.decayPercent) / 100 else 0
        }

        change += shift
        if (change < 0 && i.ally && i.stopsDecay) change = 0

        // the game speed
        change = change * 100 / i.trainPercent

        // decay does not take the influence below the resting point, recovery does not take it above, except by the shift
        val delta = resting - current
        return when {
            current > resting && change < 0 -> max(change, delta)
            current < resting && change > 0 ->
                if (shift > 0) {
                    val withoutShift = change - shift
                    if (withoutShift > 0) min(withoutShift, delta) + shift else change
                } else min(change, delta)
            else -> change
        }
    }

    /**
     *  Who is the ally after the influence of [changed] went from [oldTimes100] to [newTimes100] (CvMinorCivAI::DoFriendshipChangeEffects, V:CvMinorCivAI.cpp:11393-11452): the new
     *  influence is above the ally threshold ([aboveAllyThreshold]: 60 or more and not at war) and there is no ally, or it is more than the ally's ([effectiveTimes100] of the old ally;
     *  equal does not take the place); the ally is none when it falls under the threshold; an ally that lost influence is replaced by the one of [others] with the most, if more than its own.
     *  A [permanentAlly] (World Congress) is the ally, [noAlly] means none, whatever the influence.
     */
    @Readonly
    fun <T : Any> allyAfter(
        oldAlly: T?, changed: T, oldTimes100: Int, newTimes100: Int, aboveAllyThreshold: Boolean,
        effectiveTimes100: (T) -> Int, others: Iterable<T>, permanentAlly: T?, noAlly: Boolean
    ): T? {
        if (noAlly) return null
        if (permanentAlly != null) return permanentAlly

        var newAlly = oldAlly
        val wasAlly = oldAlly == changed
        if (aboveAllyThreshold && (oldAlly == null || (!wasAlly && newTimes100 > effectiveTimes100(oldAlly))))
            newAlly = changed
        if (wasAlly && !aboveAllyThreshold) {
            newAlly = null
        } else if (wasAlly && newTimes100 < oldTimes100) {
            var most = effectiveTimes100(oldAlly!!)
            for (civ in others) {
                if (effectiveTimes100(civ) > most) {
                    most = effectiveTimes100(civ)
                    newAlly = civ
                }
            }
        }
        return newAlly
    }
}
