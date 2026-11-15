package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import yairm210.purity.annotations.Readonly

/**
 *  How close a civilization is to a victory, after Vox Populi's CvDiplomacyAI (Release-5.4.8; roadmap D7, `phaseE/vp-conquest-endgame-plan.md`
 *  step 1). Unciv has no teams and no vassals (D3), so "our team or our vassal" is the civilization itself and a capital's owner for
 *  domination (CvCity::GetOwnerForDominationVictory) is its owner.
 */
object VictoryProgressAi {

    /**
     *  CvDiplomacyAI::GetDominationVictoryProgress: the larger of two percentages, each rounded down - our share of the military might of
     *  the living major civilizations that have a city, and our share of the original capitals of major civilizations that still stand.
     *  A capital never founded or razed counts for no one. 0 when the civilization is dead or cannot win by Domination
     *  (CvGame::CanPlayerAttemptDominationVictory: Unciv has no passive AI or always-peace option, so only whether the victory is open).
     *  Might is [com.unciv.logic.automation.strategy.CivAi.militaryMight] (the BNW formula; VP's calculateMilitaryMight is a later queue item),
     *  a statistic of the turn as in VP; the capitals are counted at the call, so a capture counts at once.
     */
    @Readonly
    fun dominationVictoryProgress(civ: Civilization): Int {
        if (!civ.isAlive() || !canAttemptDominationVictory(civ)) return 0
        var ourMight = 0
        var totalMight = 0
        for (major in civ.gameInfo.civilizations) {
            if (!major.isMajorCiv || !major.isAlive() || major.cities.isEmpty()) continue
            val might = major.ai.militaryMight()
            totalMight += might
            if (major == civ) ourMight += might
        }
        var capitals = 0
        var ours = 0
        for (city in civ.gameInfo.getCities()) {
            if (!city.isOriginalCapital || city.foundingCivObject?.isMajorCiv != true) continue
            capitals++
            if (city.civ == civ) ours++
        }
        val mightPercent = ourMight * 100 / maxOf(1, totalMight)
        val capitalsPercent = ours * 100 / maxOf(1, capitals)
        return maxOf(mightPercent, capitalsPercent)
    }

    /** CvDiplomacyAI::IsCloseToWorldConquest: progress at or above CLOSE_TO_DOMINATION_VICTORY_THRESHOLD */
    @Readonly
    fun isCloseToWorldConquest(civ: Civilization, rules: AiRuleset): Boolean =
        dominationVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_DOMINATION_VICTORY_THRESHOLD)

    /**
     *  CvDiplomacyAI::IsCloseToAnyVictoryCondition: nobody has won yet and [civ] is close to a victory. Only Domination so far; the
     *  Diplomatic, Science and Culture tests (IsCloseToDiploVictory, IsCloseToSpaceshipVictory, IsCloseToCultureVictory) are step 6 of the plan.
     */
    @Readonly
    fun isCloseToAnyVictoryCondition(civ: Civilization, rules: AiRuleset): Boolean =
        civ.gameInfo.victoryData == null && isCloseToWorldConquest(civ, rules)

    /**
     *  CvDiplomacyAI::DoUpdateCompetingForVictory: a civilization competes for victory until somebody has won (CvGame::
     *  IsVictoryCompetitionEnabled), unless another civilization resurrected it or it lost its original capital (CvPlayer::IsHasLostCapital:
     *  it founded one and does not hold it). Not a vassal (no vassals, D3). VP's game options that switch the competition and the endgame
     *  aggression off are not carried over. Computed when asked rather than stored at the start of the turn: its inputs change only when a
     *  city changes hands or the game is won.
     */
    @Readonly
    fun isCompetingForVictory(civ: Civilization): Boolean {
        if (civ.gameInfo.victoryData != null) return false
        if (civ.ai.diplomacy.memories.values.any { it.resurrectedUs }) return false
        val lostCapital = civ.hasEverOwnedOriginalCapital && civ.cities.none { it.isOriginalCapital && it.foundingCivObject == civ }
        return !lostCapital
    }

    /**
     *  CvDiplomacyAI::IsEndgameAggressive and IsEndgameAggressiveTo (set in DoUpdateCompetingForVictory): a civilization that competes for
     *  victory is aggressive towards every major it has met that is alive with a city and close to any victory (IsPlayerValid).
     */
    @Readonly
    fun isEndgameAggressiveTo(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (other == civ || !other.isMajorCiv || !other.isAlive() || other.cities.isEmpty() || !civ.knows(other)) return false
        return isCompetingForVictory(civ) && isCloseToAnyVictoryCondition(other, rules)
    }

    /** Whether one of the victories open to [civ] is won by capturing every capital (the Domination victory, by its milestone, not its name) */
    @Readonly
    fun canAttemptDominationVictory(civ: Civilization): Boolean =
        civ.victoryManager.getAvailableVictories().any { victory -> victory.milestoneObjects.any { it.type == MilestoneType.CaptureAllCapitals } }
}
