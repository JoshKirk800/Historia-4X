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
     *  Might is [com.unciv.logic.automation.strategy.CivAi.militaryMight],
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

    /** CvDiplomacyAI::IsCloseToDiploVictory: progress at or above CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD */
    fun isCloseToDiploVictory(civ: Civilization, rules: AiRuleset): Boolean =
        diplomaticVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD)

    /** CvDiplomacyAI::IsCloseToSpaceshipVictory: progress at or above CLOSE_TO_SCIENCE_VICTORY_THRESHOLD */
    @Readonly
    fun isCloseToSpaceshipVictory(civ: Civilization, rules: AiRuleset): Boolean =
        scienceVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_SCIENCE_VICTORY_THRESHOLD)

    /** CvDiplomacyAI::IsCloseToCultureVictory: progress at or above CLOSE_TO_CULTURE_VICTORY_THRESHOLD */
    @Readonly
    fun isCloseToCultureVictory(civ: Civilization, rules: AiRuleset): Boolean =
        cultureVictoryProgress(civ, rules) >= rules.define(AiDefine.CLOSE_TO_CULTURE_VICTORY_THRESHOLD)

    /**
     *  CvDiplomacyAI::IsCloseToAnyVictoryCondition: nobody has won yet and [civ] is close to a victory: world conquest, or a diplomatic,
     *  science or culture victory at or above their VP thresholds (55, 80, 60).
     */
    fun isCloseToAnyVictoryCondition(civ: Civilization, rules: AiRuleset): Boolean = civ.gameInfo.victoryData == null && (
        isCloseToWorldConquest(civ, rules)
            || isCloseToDiploVictory(civ, rules) || isCloseToSpaceshipVictory(civ, rules) || isCloseToCultureVictory(civ, rules))

    /**
     *  CvDiplomacyAI::GetDiplomaticVictoryProgress: our delegates as a percentage of those a World Leader vote needs, at most 59 before the
     *  United Nations and 79 with it; 0 without a World Congress, when dead, once somebody has won or when no open victory is won by the vote.
     *  Our delegates are those of the current session (VP counts them as if the United Nations already sat, CalculateStartingVotesForMember
     *  with bFakeUN); VP's extra 20 per active United Nations resolution are VP data, not BNW's.
     */
    fun diplomaticVictoryProgress(civ: Civilization): Int {
        val league = civ.gameInfo.league ?: return 0
        if (!civ.isAlive() || civ.gameInfo.victoryData != null || !isVictoryOpen(civ, MilestoneType.WinDiplomaticVote)) return 0
        val progress = league.getDelegates(civ) * 100 / maxOf(1, league.getVotesNeededForWorldLeader())
        return minOf(progress, if (league.unitedNations) 79 else 59)
    }

    /**
     *  CvDiplomacyAI::GetScienceVictoryProgress: the share of the techs known (of all but one), at most 78 plus 21 times the share of the
     *  spaceship's steps done: each part the victory requires and added to the capital, and each building it requires (the Apollo Program).
     *  0 when dead, once somebody has won or when no open victory is won by the spaceship.
     */
    @Readonly
    fun scienceVictoryProgress(civ: Civilization): Int {
        if (!civ.isAlive() || civ.gameInfo.victoryData != null) return 0
        val victory = civ.victoryManager.getAvailableVictories()
            .firstOrNull { v -> v.milestoneObjects.any { it.type == MilestoneType.AddedSSPartsInCapital } } ?: return 0
        val scienceProgress = civ.tech.techsResearched.size * 100 / maxOf(1, civ.gameInfo.ruleset.technologies.size - 1)
        var required = victory.requiredSpaceshipParts.size
        var completed = victory.requiredSpaceshipPartsAsCounter.entries.sumOf { (part, needed) ->
            minOf(needed, civ.victoryManager.currentsSpaceshipParts[part])
        }
        for (milestone in victory.milestoneObjects) {
            if (milestone.type != MilestoneType.BuiltBuilding) continue
            required++
            if (civ.cities.any { it.cityConstructions.isBuilt(milestone.params[0]) }) completed++
        }
        return minOf(scienceProgress, 78 + 21 * completed / maxOf(1, required))
    }

    /**
     *  CvDiplomacyAI::GetCultureVictoryProgress with GetLowestTourismInfluence: the lowest influence percent over the other living majors,
     *  starting from CULTURE_LEVEL_INFLUENTIAL, at most 99. 0 when dead, once somebody has won or when no open victory is won by influence.
     */
    @Readonly
    fun cultureVictoryProgress(civ: Civilization, rules: AiRuleset): Int {
        if (!civ.isAlive() || civ.gameInfo.victoryData != null || !isVictoryOpen(civ, MilestoneType.InfluentialOverAllCivs)) return 0
        var lowest = rules.define(AiDefine.CULTURE_LEVEL_INFLUENTIAL)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive()) continue
            lowest = minOf(lowest, maxOf(0, civ.culture.getInfluencePercent(other)))
        }
        return minOf(99, lowest)
    }

    /** Whether one of the victories open to [civ] has [milestone] */
    @Readonly
    fun isVictoryOpen(civ: Civilization, milestone: MilestoneType) =
        civ.victoryManager.getAvailableVictories().any { victory -> victory.milestoneObjects.any { it.type == milestone } }

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
        return !hasLostCapital(civ)
    }

    /** CvPlayer::IsHasLostCapital: it founded an original capital and does not hold it */
    @Readonly
    fun hasLostCapital(civ: Civilization): Boolean =
        civ.hasEverOwnedOriginalCapital && civ.cities.none { it.isOriginalCapital && it.foundingCivObject == civ }

    /**
     *  CvDiplomacyAI::IsEndgameAggressive and IsEndgameAggressiveTo (set in DoUpdateCompetingForVictory): a civilization that competes for
     *  victory is aggressive towards every major it has met that is alive with a city and close to any victory (IsPlayerValid).
     */
    fun isEndgameAggressiveTo(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (other == civ || !other.isMajorCiv || !other.isAlive() || other.cities.isEmpty() || !civ.knows(other)) return false
        return isCompetingForVictory(civ) && isCloseToAnyVictoryCondition(other, rules)
    }

    /** CvPlayer::GetNumCapitalCities and CvDiplomacyAI::GetPlayerNumMajorsConquered: the original capitals of other majors [civ] holds */
    @Readonly
    fun capitalsConquered(civ: Civilization): Int =
        civ.cities.count { it.isOriginalCapital && it.foundingCivObject?.isMajorCiv == true && it.foundingCivObject != civ }

    /** CvGame::IsAIAggressiveMode: no Diplomatic, Science or Culture victory is open (by their milestones), so only conquest can end the game */
    @Readonly
    fun isAiAggressiveMode(civ: Civilization): Boolean = civ.victoryManager.getAvailableVictories().none { victory ->
        victory.milestoneObjects.any { it.type in PEACEFUL_MILESTONES }
    }

    /** The milestones of the Diplomatic (vote), Science (spaceship) and Culture (influence, or policy branches in other rulesets) victories */
    private val PEACEFUL_MILESTONES = setOf(MilestoneType.WinDiplomaticVote, MilestoneType.AddedSSPartsInCapital,
        MilestoneType.InfluentialOverAllCivs, MilestoneType.CompletePolicyBranches)

    /** Whether one of the victories open to [civ] is won by capturing every capital (the Domination victory, by its milestone, not its name) */
    @Readonly
    fun canAttemptDominationVictory(civ: Civilization): Boolean =
        civ.victoryManager.getAvailableVictories().any { victory -> victory.milestoneObjects.any { it.type == MilestoneType.CaptureAllCapitals } }
}
