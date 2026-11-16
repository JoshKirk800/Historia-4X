package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.CoopWarAi
import com.unciv.logic.automation.diplomacy.CoopWarState
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.CompetitorAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach

/**
 *  Vox Populi's war allies (CvDiplomacyAI::GetWarAllies with GetOffensiveWarAllies and AvoidExchangesWithPlayer, Release-5.4.8; D7),
 *  without teams and vassals (none here, D3) and the game options. VP's DoF levels (allies) and its city-state war target have no
 *  counterpart: an ally by opinion stands for the first, a Conquest approach towards the city-state for the second. Our coop war
 *  states are BNW's: VP's PREPARING is [CoopWarState.Soon], its ONGOING [CoopWarState.Accepted] (the war is declared on acceptance).
 */
object WarAlliesAi {

    private fun hasDefensivePact(a: Civilization, b: Civilization) =
        a.getDiplomacyManager(b)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** IsLiberator(ePlayer, true, false) as far as we have it: they resurrected us */
    private fun resurrectedBy(civ: Civilization, other: Civilization) = civ.ai.diplomacy.memories[other.civID]?.resurrectedUs == true

    /**
     *  CvDiplomacyAI::GetWarAllies (GetOffensiveWarAllies when not [defensive], GetDefensiveWarAllies when it is). Normal mode: who
     *  fights [other] on our side, among the met players with cities not at war with us (and, while we fight [other], at war with them):
     *  our defensive pact partners (at war with them, unless [defensive]), those at war with them we have a DoF with or who resurrected
     *  us, an AI's trusted ally by opinion (not our biggest competitor, no reason to avoid exchanges), and coop war partners against
     *  them (ongoing or preparing) or against a defensive pact partner of theirs (ongoing; or preparing, unless [defensive]);
     *  [includeMinors] adds the city-states allied with us or with one of those. [reverse]: who fights us on [other]'s side (a major),
     *  among the met players with cities [other] is not at war with (and, while we fight [other], at war with us): their defensive
     *  pact partners (at war with us, unless [defensive]), those at war with us they have a DoF with or who resurrected them, and their
     *  partners in an ongoing coop war against us or against a defensive pact partner of ours; plus the city-states allied with
     *  [other] or with one of those. [newWarsOnly] (defensive only): the wars a declaration would add - nothing while we fight [other],
     *  else only those not yet at war with the side they would join against.
     */
    fun warAllies(civ: Civilization, other: Civilization, includeMinors: Boolean, reverse: Boolean, defensive: Boolean = false,
                  newWarsOnly: Boolean = false): List<Civilization> {
        val newOnly = defensive && newWarsOnly
        if (newOnly && civ.isAtWarWith(other)) return emptyList()
        if (reverse && !other.isMajorCiv) return emptyList()
        val (us, them) = if (reverse) other to civ else civ to other
        val result = ArrayList<Civilization>()
        val minors = ArrayList<Civilization>()
        for (loop in civ.gameInfo.civilizations) {
            if (loop == civ || loop == other || !loop.isAlive() || loop.cities.isEmpty() || !civ.knows(loop)) continue
            if (!loop.isMajorCiv && !(includeMinors && loop.isCityState)) continue
            if (us.isAtWarWith(loop)) continue
            if (civ.isAtWarWith(other) && !them.isAtWarWith(loop)) continue
            if (loop.isCityState) {
                if (loop.allyCiv != null) minors.add(loop)
                continue
            }
            val atWarWithThem = loop.isAtWarWith(them)
            if (hasDefensivePact(us, loop) && (defensive || atWarWithThem)) { result.add(loop); continue }
            if (atWarWithThem && (OpinionAi.isDoFAccepted(us, loop) || resurrectedBy(us, loop))) { result.add(loop); continue }
            if (!reverse && !atWarWithThem && !avoidExchangesWithPlayer(us, loop) && !us.isHuman() &&
                CompetitorAi.biggestCompetitor(us) != loop.civID && us.ai.diplomacy.memoryOf(loop).opinion == MajorCivOpinion.Ally) {
                result.add(loop); continue
            }
            if (isCoopWarAlly(us, loop, them, reverse, defensive)) result.add(loop)
        }
        for (minor in minors) {
            val ally = minor.allyCiv ?: continue
            if (ally == us || ally in result) result.add(minor)
        }
        if (!newOnly) return result
        return result.filter { if (reverse) !civ.isAtWarWith(it) else !other.isAtWarWith(it) }
    }

    /** A coop war of [us] with [loop] against [them] (ongoing; or preparing, normal mode), or against a defensive pact partner of [them]
     *  (ongoing; or preparing, normal mode and not [defensive]) */
    private fun isCoopWarAlly(us: Civilization, loop: Civilization, them: Civilization, reverse: Boolean, defensive: Boolean): Boolean {
        val direct = CoopWarAi.state(us, loop, them)
        if (direct == CoopWarState.Accepted || (!reverse && direct == CoopWarState.Soon)) return true
        for (third in us.gameInfo.civilizations) {
            if (third == them || !third.isMajorCiv || !third.isAlive() || third.cities.isEmpty() || !us.knows(third)) continue
            if (!hasDefensivePact(third, them)) continue
            val state = CoopWarAi.state(us, loop, third)
            if (state == CoopWarState.Accepted || (!reverse && !defensive && state == CoopWarState.Soon)) return true
        }
        return false
    }

    /**
     *  CvDiplomacyAI::AvoidExchangesWithPlayer: at war with [other]; for a major, an AI's visible hostile approach towards us, a
     *  denouncement either way, a coop war being prepared against them, and (unless we are human, or [ignoreSelfApproach]) our approach
     *  war (hostile or worse unless [warOnly]) or an enemy opinion behind a face that is not friendly; for a city-state, our Conquest
     *  approach towards it (VP's city-state war target and war approach).
     */
    fun avoidExchangesWithPlayer(civ: Civilization, other: Civilization, warOnly: Boolean = false, ignoreSelfApproach: Boolean = false): Boolean {
        if (civ.isAtWarWith(other)) return true
        if (!other.isAlive() || other.cities.isEmpty()) return false
        val diplomacy = civ.ai.diplomacy
        if (other.isMajorCiv) {
            if (!warOnly && !other.isHuman() && other.ai.diplomacy.approachTowards(civ, hideTrueFeelings = true) == MajorCivApproach.Hostile)
                return true
            if (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ)) return true
            val coop = CoopWarAi.globalState(civ, other)
            if (coop == CoopWarState.Soon || coop == CoopWarState.Accepted) return true
            if (civ.isHuman() || ignoreSelfApproach) return false
            val approach = diplomacy.approachTowards(other)
            if (warOnly) { if (approach == MajorCivApproach.War) return true }
            else if (approach == MajorCivApproach.War || approach == MajorCivApproach.Hostile) return true
            return diplomacy.approachTowards(other, hideTrueFeelings = true) != MajorCivApproach.Friendly &&
                diplomacy.opinionOf(other) <= MajorCivOpinion.Enemy
        }
        if (other.isCityState && !civ.isHuman()) return civ.ai.minorCivApproaches[other.civID] == MinorCivApproach.Conquest
        return false
    }
}
