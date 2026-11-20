package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  Vox Populi's war targets (CvDiplomacyAI::DoUpdateWarTargets, Release-5.4.8; D7), at the end of the final approach pass: which
 *  majors get the war approach, within a conflict budget.
 *
 *  The wars we fight and plan (a coop war prepared or ongoing against them), and the players such a war brings in on their side,
 *  cost 10 each (5 for an easy target after the Classical era; a phony war 5, 10 in the first two eras). The budget is 10 plus 5 per
 *  major whose original capital we hold (10 in the first two eras). At the limit nobody else keeps a war approach. Below it, a new
 *  war needs: no recent peace treaty with them (PeaceTreatyDampenerTurns, VP's handicap), room in the budget, a war score above 0,
 *  them not immense unless an easy target with a preferred attack target ([MilitaryAi.havePreferredAttackTarget]), no 'no new wars' unless endgame
 *  aggression or they hold our capital, a declarable war, no recent liberation by them, and a valid approach - war; hostile when
 *  close to world conquest or endgame-aggressive, or close (or, competing for conquest, anywhere) once we cross oceans, or
 *  neighbours; deceptive when close to world conquest or endgame-aggressive, or with neighbours (close across oceans) for a
 *  backstabber, denouncer above 8, deception-biased or conquering leader. A conqueror keeps an invalid, insane ([WarSanityAi]) or
 *  just-paid-off candidate as a last resort, unless unhappy, after a peace treaty, or when they hold no original capital having lost
 *  theirs (an insane one also not when the war would bankrupt us); neighbours, or anyone across oceans. Then sneak attacks under
 *  way (an operation or a mustered army; mustered first, then by war score) continue, the best war scores among the candidates
 *  get war while the budget lasts, and if we fight nobody, the best last resort does. Everyone else gets their best approach
 *  but war ([highestValueApproach]). Then the potential war targets ([updatePotentialTargets]), which the coop wars read.
 */
object WarTargetAi {

    fun updateWarTargets(civ: Civilization, majors: List<Civilization>, rules: AiRuleset) {
        if (civ.isHuman()) return
        val ai = civ.ai.diplomacy
        val era = civ.getEra().eraNumber
        fun cost(other: Civilization) = if (era > 1 && WarStateAi.isEasyTarget(civ, other)) 5 else 10
        fun newWarAllies(other: Civilization) =
            WarAlliesAi.warAllies(civ, other, includeMinors = false, reverse = true, defensive = true, newWarsOnly = true)

        val atWar = LinkedHashSet<Civilization>()
        val direct = LinkedHashSet<Civilization>() // vDirectTargets: the wars we fight and plan, not the allies they bring in
        for (other in majors) if (civ.isAtWarWith(other)) {
            atWar.add(other)
            direct.add(other)
        }
        for (other in majors) {
            if (civ.isAtWarWith(other)) continue
            val coop = CoopWarAi.globalState(civ, other)
            if (coop == null || coop < CoopWarState.Soon) continue
            setApproach(civ, other, MajorCivApproach.War)
            atWar.add(other)
            direct.add(other)
            atWar.addAll(newWarAllies(other))
        }
        var score = atWar.sumOf {
            if (civ.isAtWarWith(it) && WarIntentAi.isPhonyWar(civ, it)) (if (era <= 1) 10 else 5) else cost(it)
        }
        val limit = if (era <= 1) 10 else 10 + 5 * VictoryProgressAi.capitalsConquered(civ)
        if (score >= limit) {
            for (other in majors) if (other !in atWar) setApproach(civ, other, highestValueApproach(civ, other))
            updatePotentialTargets(civ, majors, direct, score, limit, rules)
            return
        }

        val canCrossOcean = civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        val goingForConquest = civ.ai.isGoingForWorldConquest()
        val planning = ArrayList<Civilization>()
        val lastResort = ArrayList<Civilization>()
        var anyPeaceTreaty = false
        for (other in majors) {
            if (other in atWar) continue
            val memory = ai.memoryOf(other)
            if (isRecentPeaceTreaty(civ, other, rules)) {
                anyPeaceTreaty = true
                lastResort.clear()
                continue
            }
            if (score + cost(other) > limit) continue
            if (warValue(civ, other) <= 0) continue
            if (WarStateAi.militaryStrengthComparedToUs(civ, other) == StrengthLevel.Immense
                && !(WarStateAi.isEasyTarget(civ, other) && civ.ai.military.havePreferredAttackTarget(other))) continue
            val endgame = VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
            if (WarStateAi.isNoNewWars(civ) && !endgame && !OpinionAi.isCapitalCapturedBy(civ, other)) continue
            if (civ.getDiplomacyManager(other)?.canDeclareWar() != true) continue
            if (WarSanityAi.isCityRecentlyLiberatedBy(civ, other) && WarSanityAi.citiesLost(civ) > 0 && !endgame) continue
            val proximity = DiplomacyTurn.proximity(civ, other)
            val neighbours = proximity == DiplomacyTurn.PROXIMITY_NEIGHBORS
            val validApproach = when (ai.approachTowards(other)) {
                MajorCivApproach.War -> true
                MajorCivApproach.Hostile -> closeToConquest || endgame ||
                    (if (canCrossOcean) proximity >= DiplomacyTurn.PROXIMITY_CLOSE || (VictoryProgressAi.isCompetingForVictory(civ) && goingForConquest)
                    else neighbours)
                MajorCivApproach.Deceptive -> closeToConquest || endgame ||
                    ((neighbours || (canCrossOcean && proximity >= DiplomacyTurn.PROXIMITY_CLOSE)) &&
                        (WarSanityAi.isBackstabber(civ) || civ.ai.diplomacyValue(DiplomacyPersonality.DenounceWillingness) > 8 ||
                            civ.ai.majorCivApproachBias(MajorCivApproach.Deceptive) > civ.ai.majorCivApproachBias(MajorCivApproach.Friendly) + 1 ||
                            goingForConquest))
                else -> false
            }
            val insane = !WarSanityAi.isWarSane(civ, other)
            if (!validApproach || insane || isRecentDemandAccepted(civ, other)) {
                if (!goingForConquest || anyPeaceTreaty || civ.getHappiness() < 0) continue
                if (VictoryProgressAi.hasLostCapital(other) && other.cities.none { it.isOriginalCapital }) continue
                if (insane && WarSanityAi.isWarWouldBankruptUs(civ, other)) continue
                if (canCrossOcean || neighbours) lastResort.add(other)
                continue
            }
            planning.add(other)
        }

        // Sneak attacks under way, mustered armies first
        val continuing = ArrayList<Civilization>()
        val sneakAttacks = planning.filter {
            ai.demandTarget != it.civID && (civ.ai.operations.hasAnyOffensiveOperationsAgainst(it) || ai.memoryOf(it).musteringForAttack)
        }.sortedByDescending { warValue(civ, it).toLong() + if (ai.memoryOf(it).musteringForAttack) 100_000_000L else 0L }
        fun commit(target: Civilization) {
            direct.add(target)
            score += cost(target)
            setApproach(civ, target, MajorCivApproach.War)
            atWar.add(target)
            for (ally in newWarAllies(target)) if (atWar.add(ally)) score += cost(ally)
        }
        for (target in sneakAttacks) {
            commit(target)
            continuing.add(target)
            if (score >= limit) break
        }
        if (score < limit) {
            for (target in byWarValue(civ, majors)) {
                if (target !in planning || target in continuing) continue
                commit(target)
                if (score >= limit) break
            }
        }
        if (lastResort.isNotEmpty() && atWar.isEmpty())
            byWarValue(civ, majors).firstOrNull { it in lastResort }?.let { commit(it) }

        for (other in majors) if (other !in atWar) setApproach(civ, other, highestValueApproach(civ, other))
        updatePotentialTargets(civ, majors, direct, score, limit, rules)
    }

    /**
     *  The end of CvDiplomacyAI::DoUpdateWarTargets: the majors we might want to attack, [DiplomacyAi.potentialWarTargets] (the original
     *  keeps IsPotentialWarTarget; the coop wars read it, and VP's impulse wars). With no new wars, or the budget used up (the limit
     *  plus 5, 10 in the first two eras), only the targets we have chosen ([direct]) that we are not at war with already. Otherwise every
     *  major we are not at war with but: not one that would take us over the budget, not one we are afraid of, not an immense one
     *  unless it is an easy target with a preferred attack target, not one without any attack target, not after a recent peace treaty,
     *  and, unless untrustworthy, not our most valuable friend or ally, a friend of the early game (unless we are a backstabber), one we
     *  are friendly towards or whose opinion is friend or better, nor a liberator (or a recent liberation while we have lost cities).
     *  War sanity is not asked: the original leaves it to the war declaration, to allow impulse wars and betrayals. VP's strategic trade
     *  partners (no counterpart here) do not keep anyone out.
     */
    private fun updatePotentialTargets(civ: Civilization, majors: List<Civilization>, direct: Set<Civilization>, score: Int, limit: Int, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val era = civ.getEra().eraNumber
        val potentialLimit = if (era <= 1) 10 else limit + 5
        val targets = ai.potentialWarTargets
        targets.clear()
        val onlyDirect = WarStateAi.isNoNewWars(civ) || score >= potentialLimit
        for (other in majors) {
            if (civ.isAtWarWith(other)) continue
            if (other in direct) {
                targets.add(other.civID)
                continue
            }
            if (onlyDirect) continue
            val cost = if (era > 1 && WarStateAi.isEasyTarget(civ, other)) 5 else 10
            if (score + cost > potentialLimit) continue
            if (ai.approachTowards(other) == MajorCivApproach.Afraid) continue
            val easyWithTarget = WarStateAi.isEasyTarget(civ, other) && civ.ai.military.havePreferredAttackTarget(other)
            if (WarStateAi.militaryStrengthComparedToUs(civ, other) == StrengthLevel.Immense && !easyWithTarget) continue
            if (!civ.ai.military.havePossibleAttackTarget(other)) continue
            if (isRecentPeaceTreaty(civ, other, rules)) continue
            if (!OpinionAi.isUntrustworthyFriend(civ, other)) {
                if (ai.mostValuableFriend == other.civID || ai.mostValuableAlly == other.civID) continue
                if (OpinionAi.isDoFAccepted(civ, other) && era <= 1 && !WarSanityAi.isBackstabber(civ)) continue
                if (ai.approachTowards(other) == MajorCivApproach.Friendly) continue
                if (ai.opinionOf(other) >= MajorCivOpinion.Friend) continue
                if (WarSanityAi.isLiberator(civ, other, onlyMajorCities = true) ||
                    (WarSanityAi.isCityRecentlyLiberatedBy(civ, other) && !VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules) &&
                        WarSanityAi.citiesLost(civ) > 0)) continue
            }
            targets.add(other.civID)
        }
    }

    /** GetPlayerApproachValue(WAR): the war score of the last final approach pass */
    private fun warValue(civ: Civilization, other: Civilization): Int =
        civ.ai.diplomacy.memories[other.civID]?.approachValues?.getOrNull(MajorCivApproach.War.ordinal) ?: 0

    /** GetPlayerWithHighestApproachValue(WAR), taken in turn: the valid majors with a war score above 0, highest first (ties in order) */
    private fun byWarValue(civ: Civilization, majors: List<Civilization>): List<Civilization> =
        majors.filter { warValue(civ, it) > 0 }.sortedByDescending { warValue(civ, it) }

    /** CvDiplomacyAI::IsRecentDemandAccepted: [other] paid a tribute we demanded within a deal's duration */
    fun isRecentDemandAccepted(civ: Civilization, other: Civilization): Boolean {
        val turn = civ.ai.diplomacy.memories[other.civID]?.demandAcceptedTurn ?: -1
        return turn >= 0 && civ.gameInfo.turns - turn < civ.gameInfo.speed.dealDuration
    }

    /**
     *  CvDiplomacyAI::GetHighestValueApproach (excluding war, with overrides): the best score of the last final pass but war, ties in
     *  VP's order, all at zero neutral. War or hostile towards one who resurrected us, or recently liberated a city of ours while we
     *  have lost cities (unless they are close to world conquest or we are endgame-aggressive), is guarded, hostile towards a liberator
     *  too; friendly or deceptive after a denouncement either way, towards the untrustworthy, or after we paid them a tribute, is
     *  guarded at enemy opinion or worse, else neutral.
     */
    fun highestValueApproach(civ: Civilization, other: Civilization): MajorCivApproach {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        val values = memory.approachValues
        var best = MajorCivApproach.Neutral
        var highest = 0
        for (approach in ApproachAi.VP_ORDER) {
            if (approach == MajorCivApproach.War) continue
            val value = values.getOrNull(approach.ordinal) ?: 0
            if (value > highest) {
                best = approach
                highest = value
            }
        }
        val rules = civ.gameInfo.ruleset.ai
        if (best == MajorCivApproach.Hostile) {
            val recentLiberation = WarSanityAi.isCityRecentlyLiberatedBy(civ, other) && WarSanityAi.citiesLost(civ) > 0 &&
                !VictoryProgressAi.isCloseToWorldConquest(other, rules) && !VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
            if (memory.resurrectedUs || recentLiberation || WarSanityAi.isLiberator(civ, other, onlyMajorCities = true))
                best = MajorCivApproach.Guarded
        }
        if ((best == MajorCivApproach.Friendly || best == MajorCivApproach.Deceptive) &&
            (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ) || OpinionAi.isUntrustworthyFriend(civ, other) ||
                isRecentDemandAccepted(other, civ)))
            best = if (memory.opinion <= MajorCivOpinion.Enemy) MajorCivApproach.Guarded else MajorCivApproach.Neutral
        return best
    }

    /** A peace treaty with [other] after a war, fewer than the handicap's PeaceTreatyDampenerTurns (Vox Populi) ago */
    internal fun isRecentPeaceTreaty(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (memory.numWarsFought <= 0 || memory.turnMadePeace <= -1) return false
        val dampener = rules.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get("PeaceTreatyDampenerTurns") ?: 20
        return civ.gameInfo.turns - memory.turnMadePeace < dampener
    }

    /** CvDiplomacyAI::SetCivApproach as far as ours goes: a new war approach takes BNW's war face from the approach scores */
    internal fun setApproach(civ: Civilization, other: Civilization, approach: MajorCivApproach) {
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.approach = approach
        if (approach == MajorCivApproach.War && memory.warFace == null && memory.approachValues.size == MajorCivApproach.entries.size)
            memory.warFace = ApproachAi.warFace(civ, other, memory.approachValues.toIntArray())
    }
}
