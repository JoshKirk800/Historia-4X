package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.CityStateAi
import com.unciv.logic.automation.civilization.ReligionAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach

/**
 *  CvDiplomacyAI::IsBadTheftTarget (Vox Populi, Release-5.4.8): whether taking something from a player - tiles by a culture bomb, a
 *  religious conversion, an artifact dug up in their lands, a spy's work in their cities - would cost more than it is worth, because they
 *  are a friend, a partner we do not want to anger, or someone we promised to leave alone. Read by the culture bomb plots of the Great
 *  Generals ([com.unciv.logic.automation.unit.GreatPersonAutomation.findBestArtistTargetPlot]), the religion calculator of the building
 *  value ([com.unciv.logic.automation.strategy.BuildingValueAi]) and the missionary targets ([ReligionAutomation]), the antiquity sites of the
 *  archaeologists, and the offense city list of the spies ([com.unciv.logic.automation.unit.EspionageAutomation.buildOffenseCityList]).
 *  The readers whose rules BNW lacks do not reach it: the plots a building claims (VP's Building_ResourceClaim) in the building sanity
 *  check, the settling next to foreign borders (a VP policy column) in the city site evaluation, and trade route plunder and plot theft.
 *
 *  Not ported, [TheftType] has only the four types BNW can act on: trade routes (Morocco), tile theft (America) and embassies.
 *  None, because the input is not in BNW: teams and teammates (Unciv has none), vassals and their treatment (decided out, D3), and the
 *  accepted request to move troops from a player's borders (Unciv has no such request; its military promise is a different VP state).
 *  By what we keep: a promise we made is the [Demand.agreedToDemand] flag on the other civilization's side towards us (VP's
 *  `GET_PLAYER(ePlayer)->MadeXPromise(GetID())`); IsLiberator is [WarSanityAi.isLiberator] (no returned capitals or holy cities by deal);
 *  the approach we show is [DiplomacyAi.approachTowards] with hidden feelings (a war face for a war we plan); "asked not to convert"
 *  (m_abAskedNotToConvert, set whenever a human asks, reset when we convert again) is a standing request of theirs we agreed to or refused
 *  ([PromiseAi]'s ignored state; Unciv forgets a refusal after the promise duration where VP keeps the flag until our next conversion).
 *  The human's shadow AI is a human (`isHuman`, VP's ISHUMAN_AI_DIPLOMACY): an automated worker or recommender asks for it.
 */
object TheftAi {

    /** Vox Populi's CivApproachTypes order, in which NEUTRAL comes before FRIENDLY (ours does not) */
    private fun rank(approach: MajorCivApproach) = ApproachAi.VP_ORDER.indexOf(approach)

    /**
     *  Whether [civ] should not take what [type] is from [other]. False for a barbarian, ourselves, the dead and a civilization at war with us.
     *  A city-state is only judged for a culture bomb ([isBadCultureBombOnCityState]). Of a major we never take from a friend (a DoF, a
     *  liberator, a defensive pact); an AI also not from an ally by opinion, one it is afraid of, a friend it approaches as friendly, or a
     *  competitor or better that it approaches as neutral or friendly and has signed a DoF with at least twice ([DoFLevel.Allies]). Then,
     *  by the theft:
     *  - culture bomb: a human's automation takes it; an AI does not take from one who has our promise not to settle near them, and takes
     *    it only when the face it shows is hostile (worse than guarded) or it covets their land (a strong land dispute);
     *  - conversion: no bad target at all (checked before the friends): a civilization with no religion that never asked us to stop;
     *    else one we promised;
     *  - artifact: one we promised, or whose borders are closed to us;
     *  - spy: one we promised.
     */
    fun isBadTheftTarget(civ: Civilization, other: Civilization, type: TheftType): Boolean {
        if (other.isBarbarian || other == civ || !other.isAlive()) return false
        if (civ.isAtWarWith(other)) return false

        val human = civ.isHuman()
        if (other.isCityState) return type == TheftType.CultureBomb && isBadCultureBombOnCityState(civ, other, human)

        // Exception for religious conversion: Not a bad target if they haven't created a religion and haven't asked us to stop.
        if (type == TheftType.Conversion && ReligionAutomation.religionToSpread(other) == null && !askedNotToConvert(civ, other)) return false

        // If any of the below conditions are true, never steal from this player
        if (OpinionAi.isDoFAccepted(civ, other)) return true
        if (WarSanityAi.isLiberator(civ, other, onlyMajorCities = false)) return true
        if (civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact) return true

        val diplomacy = civ.ai.diplomacy
        val opinion = diplomacy.opinionOf(other)
        val trueApproach = diplomacy.approachTowards(other)
        if (!human) {
            if (opinion == MajorCivOpinion.Ally) return true
            if (trueApproach == MajorCivApproach.Afraid) return true
            if (opinion >= MajorCivOpinion.Friend && rank(trueApproach) >= rank(MajorCivApproach.Friendly)) return true
            if (opinion >= MajorCivOpinion.Competitor && rank(trueApproach) >= rank(MajorCivApproach.Neutral) &&
                (diplomacy.memories[other.civID]?.doFLevel ?: DoFLevel.New) >= DoFLevel.Allies) return true
        }

        return when (type) {
            TheftType.CultureBomb ->
                if (human) false
                else if (madePromise(civ, other, Demand.DoNotSettleNearUs)) true
                // Only steal if we're hostile or covet their lands
                else rank(diplomacy.approachTowards(other, hideTrueFeelings = true)) > rank(MajorCivApproach.Guarded) &&
                    diplomacy.landDisputeLevel(other) < DisputeLevel.Strong
            TheftType.Conversion -> madePromise(civ, other, Demand.DoNotSpreadReligion)
            TheftType.Artifact -> madePromise(civ, other, Demand.DoNotDigInOurLands) ||
                civ.getDiplomacyManager(other)?.hasOpenBorders != true
            TheftType.Spy -> madePromise(civ, other, Demand.DontSpyOnUs)
        }
    }

    /**
     *  The minor-civ branch for a culture bomb: an AI that approaches the city-state as friendly, or that is its ally; a human that is
     *  its friend (an automated worker should not take a friend's tiles).
     */
    private fun isBadCultureBombOnCityState(civ: Civilization, minor: Civilization, human: Boolean): Boolean {
        if ((!human && civ.ai.minorCivApproaches[minor.civID] == MinorCivApproach.Friendly) || CityStateAi.isAllies(minor, civ)) return true
        return human && CityStateAi.isFriends(minor, civ)
    }

    /** CvDiplomacyAI::MadeXPromise of [other]'s state: we promised [other] [demand] (Unciv keeps it on the promisee's side, [Demand.agreedToDemand]) */
    private fun madePromise(civ: Civilization, other: Civilization, demand: Demand): Boolean =
        other.getDiplomacyManager(civ)?.hasFlag(demand.agreedToDemand) == true

    /** m_abAskedNotToConvert[other]: [other] asked us to stop converting its cities and we agreed or refused */
    private fun askedNotToConvert(civ: Civilization, other: Civilization): Boolean {
        if (madePromise(civ, other, Demand.DoNotSpreadReligion)) return true
        val memory = other.ai.diplomacy.memories[civ.civID] ?: return false
        return PromiseAi.state(memory, PromiseAi.PromiseKind.NoConvert).second
    }
}
