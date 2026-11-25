package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.civilization.Proximity
import com.unciv.models.ruleset.ai.MinorCivApproach
import yairm210.purity.annotations.Pure
import kotlin.math.max

/**
 * What the World Congress AI makes of Open Door and Sphere of Influence for a City-State: the arithmetic of two blocks of
 * CvLeagueAI::ScoreVoteChoiceYesNo (Vox Populi, V:CvVotingClasses.cpp:13271-13336 and 13583-13682), with the inputs as arguments.
 * [LeagueEvaluator] gathers them. Every term truncates in the order the source has it.
 *
 * The approach to a City-State is one of four in Vox Populi (`SetCivApproach` refuses the major ones for a City-State,
 * V:CvDiplomacyAI.cpp:3385): CIV_APPROACH_WAR 0, HOSTILE 1, NEUTRAL 5, FRIENDLY 6, with GUARDED 3 as the pivot of both blocks
 * (V:CvDiplomacyAIEnums.h:28-36), so a friendly approach is +3 and a neutral one +2 steps above it, hostility and war below it.
 */
object LeagueCityStateScoring {
    private const val GUARDED = 3
    private const val STEP = 150

    /** The CivApproachTypes value of a City-State approach of ours (V:CvDiplomacyAIEnums.h:28-36) */
    @Pure
    fun approachValue(approach: MinorCivApproach): Int = when (approach) {
        MinorCivApproach.Conquest -> 0
        MinorCivApproach.Bully -> 1
        MinorCivApproach.Ignore -> 5
        MinorCivApproach.Friendly, MinorCivApproach.Protective -> 6 // Protective is BNW's and never chosen: a save's one loads as Friendly (CivAi.setTransients)
    }

    /**
     * The Open Door block (V:13271-13336) as VP's `iExtra`, for a City-State whose door would be opened. Open Door takes the City-State's ally
     * slot away, so what we would want from allying it counts against it.
     *  - [influence] ours with it (`GetEffectiveFriendshipWithMajor`, -60 at war) against [friendsThreshold]: at or under the threshold, the
     *    distance to it, 0 to 100, is worth having (13279-13284: the friend bonus Open Door's 40 might give).
     *  - the want to ally it: (approach - GUARDED) x 150 (13288). Above 0, [proximity] adds 250 for neighbours, 100 for close
     *    (13289-13303) and 100 for each resource we lack that it has ([resourcesLacked], 13305-13309); then, when [allyInfluence] is the influence of
     *    another civilization that is its ally, x (influence x 75 / max(1, that influence)) / 100, two truncating steps (13310-13321).
     *    Below 0 (a hostile approach or war), [rawTargetValue] above average makes it 150 more negative per step (13322-13330).
     *  - the result is the friend bonus minus the want to ally (13332).
     * [allyInfluence] is `null` when it has no ally or we are the ally.
     */
    @Pure
    fun openDoor(influence: Int, friendsThreshold: Int, approach: MinorCivApproach, proximity: Proximity, resourcesLacked: Int,
                 allyInfluence: Int?, rawTargetValue: TargetValue): Int {
        var extra = 0
        if (influence <= friendsThreshold) extra += (friendsThreshold - influence).coerceIn(0, 100)
        var allyDesire = (approachValue(approach) - GUARDED) * STEP
        val proximityBonus = when (proximity) {
            Proximity.Close -> 100
            Proximity.Neighbors -> 250
            Proximity.None, Proximity.Distant, Proximity.Far -> 0
        }
        val resourceDesire = max(0, 100 * resourcesLacked)
        if (allyDesire > 0) {
            allyDesire += proximityBonus + resourceDesire
            if (allyInfluence != null) {
                allyDesire *= (influence * 75) / max(1, allyInfluence)
                allyDesire /= 100
            }
        }
        if (allyDesire < 0) {
            val targetValue = if (rawTargetValue > TargetValue.Average) STEP * (rawTargetValue.ordinal - TargetValue.Average.ordinal) else 0
            allyDesire -= targetValue
        }
        return extra - allyDesire
    }

    /**
     * The Sphere of Influence block (V:13583-13682) as VP's `iExtra`, before the doubling when the Diplomatic Victory is enabled (13676-13679).
     * [affectedIsUs]: the civilization the Sphere would make (or, for a repeal, unmake) the ally is us (13592-13593).
     *  - for us (13595-13634): 100, and 500 more when we are not its ally ([weAreAlly] false), else 500 - (our influence - the highest
     *    influence of any other major), at least 0 and at most 500; and for [proximity] 400 for neighbours, 200 for close and 200 for
     *    distant (the far one counts nothing). A repeal of ours is scored the same, and VP's `Score` is reversed afterwards by the caller.
     *  - for another civilization (13635-13673): [approach] above GUARDED gives 150 + (approach - GUARDED) x 150 of want, 150 otherwise, and
     *    above 150 neighbours add 400 and close ones 200 (distant and far nothing, unlike the block above); when we are the ally it is
     *    minus 1.5 x that; when another civilization is [allyInfluence] it is minus that x (our influence x 100 / max(1, theirs), 0 to 100) / 100;
     *    with no ally nothing.
     */
    @Pure
    fun sphere(affectedIsUs: Boolean, weAreAlly: Boolean, influence: Int, highestOtherInfluence: Int, proximity: Proximity,
               approach: MinorCivApproach, allyInfluence: Int?, diploVictoryEnabled: Boolean): Int {
        var extra = 0
        if (affectedIsUs) {
            extra += 100
            extra += if (!weAreAlly) 500 else (500 - (influence - highestOtherInfluence)).coerceIn(0, 500)
            extra += when (proximity) {
                Proximity.Close -> 200
                Proximity.Neighbors -> 400
                Proximity.Distant -> 200
                Proximity.None, Proximity.Far -> 0
            }
        } else {
            var allyDesire = 150
            val value = approachValue(approach)
            if (value > GUARDED) allyDesire += (value - GUARDED) * STEP
            if (allyDesire > 150) allyDesire += when (proximity) {
                Proximity.Close -> 200
                Proximity.Neighbors -> 400
                Proximity.None, Proximity.Distant, Proximity.Far -> 0
            }
            if (weAreAlly) extra -= allyDesire * 150 / 100
            else if (allyInfluence != null) extra -= allyDesire * ((influence * 100) / max(1, allyInfluence)).coerceIn(0, 100) / 100
        }
        return if (diploVictoryEnabled) extra * 2 else extra
    }
}
