package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.civilization.Accomplishment
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.HistoricEvent
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/**
 *  Vox Populi's war victories with the ModConstants `warVictoryLikeVp`: who won a war that ends in a peace deal (CvGameDeals::ActivateDeal),
 *  a third-party peace or an elimination (CvPlayer::CheckForMurder), and what winning gives (CvPlayer::DoWarVictoryBonuses).
 */
object WarVictory {
    /** WARSCORE_THRESHOLD_POSITIVE: the war score from which a side has won */
    private const val WON_WAR_SCORE = 25

    /** [civ]'s war score against [other] says it has won the war (GetWarScore >= WARSCORE_THRESHOLD_POSITIVE) */
    @Readonly
    fun hasWon(civ: Civilization, other: Civilization): Boolean = WarDamageAi.warScore(civ, other) >= WON_WAR_SCORE

    /**
     *  CvPlayer::DoWarVictoryBonuses (V:CvPlayer.cpp:18734-18760): the accomplishment `War won`, the uniques `upon winning a war` (the original's
     *  GoldenAgeFromVictory is one), and the historic event of a won war
     */
    fun onWarWon(civ: Civilization) {
        if (!civ.gameInfo.ruleset.modOptions.constants.warVictoryLikeVp) return
        civ.completeAccomplishment(Accomplishment.WarWon)
        civ.forEachTriggeredUnique(UniqueType.TriggerUponWinningWar, GameContext.IgnoreConditionals, ignoreCities = false) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, civ)
        }
        civ.culture.onHistoricEvent(HistoricEvent.WonWar)
    }

    /**
     *  A peace deal between [from] and [to] (CvGameDeals::ActivateDeal, V:CvDealClasses.cpp:4482-4545 and 5196-5232): a side has won with
     *  the war score before the deal ([fromWonBefore], [toWonBefore]) or after its cities changed hands ([fromWonAfter], [toWonAfter]), and gets the bonuses
     *  when the other surrenders: between two humans the side that alone gives items that are not treaties ([fromGivesOnly], [toGivesOnly]), else the side
     *  that had lost before the deal.
     */
    @Suppress("LongParameterList") // the two sides of the deal, each before and after
    fun onPeaceDeal(
        from: Civilization, to: Civilization,
        fromWonBefore: Boolean, toWonBefore: Boolean, fromWonAfter: Boolean, toWonAfter: Boolean,
        fromGivesOnly: Boolean, toGivesOnly: Boolean
    ) {
        val betweenHumans = from.isHuman() && to.isHuman()
        val fromSurrenders = if (betweenHumans) fromGivesOnly else toWonBefore
        val toSurrenders = if (betweenHumans) toGivesOnly else fromWonBefore
        if ((fromWonBefore || fromWonAfter) && toSurrenders) onWarWon(from)
        if ((toWonBefore || toWonAfter) && fromSurrenders) onWarWon(to)
    }

    /**
     *  [killer] took the last city of [victim] (CvPlayer::CheckForMurder, V:CvPlayer.cpp:34466-34492): for a major victim every living major at war with
     *  it has won, for a city-state the major that took it
     */
    fun onEliminated(killer: Civilization, victim: Civilization) {
        if (victim.isMajorCiv) {
            for (other in victim.gameInfo.civilizations)
                if (other != victim && other.isMajorCiv && other.isAlive() && other.isAtWarWith(victim)) onWarWon(other)
        } else if (victim.isCityState && killer.isMajorCiv) onWarWon(killer)
    }
}
