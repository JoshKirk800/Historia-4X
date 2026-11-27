package com.unciv.logic.civilization

import com.unciv.models.ruleset.BeliefType
import yairm210.purity.annotations.Readonly

/**
 *  Vox Populi's accomplishments (the Accomplishments table, CvPlayer::m_aiAccomplishments): what a civilization has done, counted each time,
 *  for `<for every [Completed [accomplishment] accomplishments]>`. Counted only with `ModConstants.accomplishmentsLikeVp`, see
 *  [Civilization.completeAccomplishment]. [label] is the name a unique uses.
 */
enum class Accomplishment(val label: String) {
    AncientPolicyBranch("Ancient policy branch"),
    MedievalPolicyBranch("Medieval policy branch"),
    IndustrialPolicyBranch("Industrial policy branch"),
    Ideology("Ideology"),
    PantheonBelief("Pantheon belief"),
    FounderBelief("Founder belief"),
    FollowerBelief("Follower belief"),
    EnhancerBelief("Enhancer belief"),
    ReformationBelief("Reformation belief"),
    EliminatedCivilization("Eliminated civilization"),
    HistoricEvent("Historic event"),
    WarWon("War won"),
    ;

    companion object {
        @Readonly fun byLabel(label: String): Accomplishment? = entries.firstOrNull { it.label == label }

        /** CvReligionBeliefs::AddBelief (V:CvBeliefClasses.cpp:2092-2112): the accomplishment of choosing a belief of the type */
        fun ofBelief(type: BeliefType): Accomplishment? = when (type) {
            BeliefType.Pantheon -> PantheonBelief
            BeliefType.Founder -> FounderBelief
            BeliefType.Follower -> FollowerBelief
            BeliefType.Enhancer -> EnhancerBelief
            BeliefType.Reformation -> ReformationBelief
            else -> null
        }

        /** CvPlayerPolicies::SetPolicyBranchFinished (V:CvPolicyClasses.cpp:5735-5751): a finished branch counts by the era it needs,
         *  none or the first one as ancient, the third as medieval, the fifth as industrial, any other era not at all */
        fun ofPolicyBranchEra(eraNumber: Int): Accomplishment? = when (eraNumber) {
            0 -> AncientPolicyBranch
            2 -> MedievalPolicyBranch
            4 -> IndustrialPolicyBranch
            else -> null
        }
    }
}
