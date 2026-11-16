package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  Vox Populi's war sanity (CvDiplomacyAI, Release-5.4.8): whether declaring war on a player is sane for us - financially and
 *  diplomatically, since a war may break a friendship, a pact or a promise.
 *
 *  Inputs with no counterpart, counted as none: strategic trade partners, troop move requests, the coop war agreement score, the wish
 *  to end a defensive pact, city-state gold bonuses (none in BNW). VP's flags by
 *  what we keep: untrustworthy is
 *  [OpinionAi.isUntrustworthyFriend], the backstabber flag having declared war on a friend, "ever backstabbed by" a DoF they broke
 *  with us ([MajorCivMemory.doFBrokenTurn]); a liberator is one who resurrected us (liberated capitals and holy cities are not
 *  told apart). The average gold rate is this turn's.
 */
object WarSanityAi {

    /**
     *  CvDiplomacyAI::DoUpdateSaneDiplomaticTargets: each met player we are not at war with is insane to attack when we want no new
     *  wars (unless they took our capital or holy city, or we are aggressive towards a major in the endgame), when the war would
     *  bankrupt us, or when it would betray a friend we are not willing to attack ([isSaneDiplomaticTarget]).
     */
    fun updateSaneTargets(civ: Civilization, rules: AiRuleset) {
        val insane = civ.ai.diplomacy.insaneTargets
        insane.clear()
        val noNewWars = WarStateAi.isNoNewWars(civ)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !(other.isMajorCiv || other.isCityState) || !other.isAlive() || !civ.knows(other)) continue
            if (civ.isAtWarWith(other)) continue
            val sane = when {
                noNewWars && !OpinionAi.isCapitalCapturedBy(civ, other) && !CompetitorAi.isHolyCityCapturedBy(civ, other) &&
                    (!other.isMajorCiv || !VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) -> false
                isWarWouldBankruptUs(civ, other) -> false
                else -> isSaneDiplomaticTarget(civ, other, impulse = false, rules)
            }
            if (!sane) insane.add(other.civID)
        }
    }

    /** CvDiplomacyAI::IsWarSane (one player per team): the last [updateSaneTargets] found [other] sane to attack */
    fun isWarSane(civ: Civilization, other: Civilization): Boolean = other.civID !in civ.ai.diplomacy.insaneTargets

    /**
     *  CvDiplomacyAI::IsWarWouldBankruptUs: a war we are not in that would cost us gold, while we are broke already, would take our
     *  income below [minimumIncome] (when above 0), or would leave us bankrupt within 30 turns. The income is VP's average gold rate
     *  ([WarStateAi.avgGoldRate]), not one turn's.
     */
    fun isWarWouldBankruptUs(civ: Civilization, other: Civilization, minimumIncome: Int = 0): Boolean {
        if (civ.isAtWarWith(other)) return false
        val lost = goldPerTurnLostFromWar(civ, other)
        if (lost <= 0) return false
        val income = WarStateAi.avgGoldRate(civ)
        if (civ.gold <= 0 && income <= 0) return true
        if (minimumIncome > 0 && income - lost < minimumIncome) return true
        return WarStateAi.turnsToBankruptcy(civ, lost) <= 30
    }

    /**
     *  CvDiplomacyAI::CalculateGoldPerTurnLostFromWar: the trade route gold with [other] and, for a major, the net gold per turn of
     *  our deals with them; plus the same with each player the war would add on their side (their new defensive war allies).
     */
    fun goldPerTurnLostFromWar(civ: Civilization, other: Civilization): Int {
        if (civ.isAtWarWith(other)) return 0
        var gold = ApproachAi.tradeGoldFrom(civ, other)
        if (!other.isMajorCiv) return gold
        gold += dealGoldLostFromWar(civ, other)
        for (ally in WarAlliesAi.warAllies(civ, other, includeMinors = true, reverse = true, defensive = true, newWarsOnly = true)) {
            gold += ApproachAi.tradeGoldFrom(civ, ally)
            if (ally.isMajorCiv) gold += dealGoldLostFromWar(civ, ally)
        }
        return gold
    }

    /** CvGameDeals::GetDealGPTLostFromWar: the gold per turn [other] gives us in current deals, less what we give them */
    private fun dealGoldLostFromWar(civ: Civilization, other: Civilization): Int {
        val trades = civ.getDiplomacyManager(other)?.trades ?: return 0
        var gold = 0
        for (trade in trades) {
            gold += trade.theirOffers.filter { it.type == TradeOfferType.Gold_Per_Turn }.sumOf { it.amount }
            gold -= trade.ourOffers.filter { it.type == TradeOfferType.Gold_Per_Turn }.sumOf { it.amount }
        }
        return gold
    }

    /**
     *  CvDiplomacyAI::DoUpdateOnePlayerSaneDiplomaticTarget: a city-state is no target if we protect it or are its ally, or a friend
     *  or ally of ours is its ally or protector. A major is none when an [impulse] war would come before a coop war we prepare, or
     *  when the war would backstab them or one who would join them ([canBackstab]) and we are not willing to ([isWillingToAttackFriend]).
     */
    fun isSaneDiplomaticTarget(civ: Civilization, other: Civilization, impulse: Boolean, rules: AiRuleset): Boolean {
        if (civ.isAtWarWith(other)) return true
        if (other.isCityState) {
            val protectors = other.cityStateFunctions.getProtectorCivs()
            if (civ in protectors) return false
            val ally = other.allyCiv
            if (ally == civ) return false
            if (ally != null && isFriendOrAlly(civ, ally)) return false
            return protectors.none { isFriendOrAlly(civ, it) }
        }
        if (impulse && CoopWarAi.globalState(civ, other) == CoopWarState.Soon) return false
        val players = WarAlliesAi.warAllies(civ, other, includeMinors = false, reverse = true, defensive = true, newWarsOnly = true) + other
        for (player in players)
            if (canBackstab(civ, player) && !isWillingToAttackFriend(civ, player, direct = player == other, impulse, rules)) return false
        return true
    }

    /**
     *  CvDiplomacyAI::CanBackstab: a war on [other] would be backstabbing - a DoF, a defensive pact, an ally by opinion, a
     *  resurrection either way, battle brothers, a promise we made them not to attack, a liberator, a recent liberation by them, a DoF
     *  we broke with them within DOF_BROKEN_BACKSTAB_TIMER turns, a coop war with them prepared or ongoing.
     */
    fun canBackstab(civ: Civilization, other: Civilization): Boolean {
        val memory = civ.ai.diplomacy.memoryOf(other)
        val theirs = other.ai.diplomacy.memoryOf(civ)
        return isFriendOrAlly(civ, other) || memory.opinion == MajorCivOpinion.Ally || memory.resurrectedUs || theirs.resurrectedUs ||
            memory.doFLevel == DoFLevel.BattleBrothers || theirs.madeMilitaryPromise || isLiberator(civ, other, onlyMajorCities = true) ||
            isCityRecentlyLiberatedBy(civ, other) || isBackstabTimer(civ, other) || coopWarWithState(civ, other) != null
    }

    /**
     *  CvDiplomacyAI::IsWillingToAttackFriend: an AI competing for victory, past the ancient and classical eras unless a backstabber,
     *  not resurrected by them nor they by us, not afraid of them, not their ally by opinion. Unless an [impulse], endgame aggression or
     *  their untrustworthiness: not against a [direct] liberator, a recent liberator while we have lost cities, battle brothers, a
     *  coop war partner, or a [direct] target of our promise. Within the backstab timer, a [direct] war must not anger a valid major
     *  who minds backstabbing and is our friend or ally (or, short of endgame aggression, not an easy target, or liked by a
     *  non-backstabber). With a DoF or defensive pact: loyalty above 6 refuses (unless a backstabber); a [direct], unprovoked war needs
     *  a hostile approach and an opinion below friend (a loyal leader's, below neutral, or favourable at loyalty 4); they must not be
     *  too strong for us militarily or economically ([direct]: powerful for an aggressive leader, strong for others, with at least two
     *  of: our cities not exposed to them, a preferred attack target among theirs (CvMilitaryAI), an easy target; average and poor
     *  without), nor (indirect) too strong a neighbour; and a good reason is needed (a backstabber,
     *  endgame aggression, untrustworthy, close to world conquest while they hold their capital, a backstabber themselves, our
     *  biggest competitor, a severe warmonger; indirect also going for conquest while they hold their capital, a major competitor).
     *  With a DoF: [direct], no valid major minding backstabbing may be our friend or ally, liked as a friend by a non-backstabber,
     *  or a strong neighbour (below average when neighbours, below powerful when close), or one our cities are exposed to that is not an
     *  easy target - this loop
     *  counts [other] too, as VP's does, so a current friend is attacked directly only when one of us minds no more; indirect, a
     *  loyalty above 8 refuses (unless a backstabber or endgame aggressive), and an impulse needs an opinion below friend.
     */
    fun isWillingToAttackFriend(civ: Civilization, other: Civilization, direct: Boolean, impulse: Boolean, rules: AiRuleset): Boolean {
        if (civ.isHuman()) return false
        if (!VictoryProgressAi.isCompetingForVictory(civ)) return false
        val backstabber = isBackstabber(civ)
        if (civ.getEra().eraNumber <= 1 && !backstabber) return false
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        val theirs = other.ai.diplomacy.memoryOf(civ)
        if (memory.resurrectedUs || theirs.resurrectedUs) return false
        val approach = ai.approachTowards(other)
        val opinion = ai.opinionOf(other)
        if (approach == MajorCivApproach.Afraid || opinion == MajorCivOpinion.Ally) return false
        val endgameAggressive = VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
        val untrustworthy = OpinionAi.isUntrustworthyFriend(other)
        if (!impulse && !endgameAggressive && !untrustworthy) {
            if (direct && isLiberator(civ, other, onlyMajorCities = true)) return false
            if (isCityRecentlyLiberatedBy(civ, other) && citiesLost(civ) > 0) return false
            if (memory.doFLevel == DoFLevel.BattleBrothers) return false
            if (coopWarWithState(civ, other) != null) return false
            if (direct && theirs.madeMilitaryPromise) return false
        }
        val loyalty = civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty)
        val majors = civ.gameInfo.civilizations.filter { it.isMajorCiv && WarStateAi.isPlayerValid(civ, it) }
        if (direct && isBackstabTimer(civ, other)) {
            for (third in majors) {
                if (ignoresBackstabbing(third, other) || ignoresBackstabbing(third, civ, checkCurrent = true)) continue
                if (isFriendOrAlly(civ, third)) return false
                if (endgameAggressive) continue
                if (!WarStateAi.isEasyTarget(civ, third)) return false
                if (!backstabber && ai.opinionOf(third) >= MajorCivOpinion.Favorable) return false
            }
        } else if (isFriendOrAlly(civ, other)) {
            if (!backstabber && loyalty > 6) return false
            if (direct && !impulse) {
                if (approach == MajorCivApproach.Neutral || approach == MajorCivApproach.Friendly) return false
                if (opinion == MajorCivOpinion.Friend) return false
                if (!backstabber) {
                    if (loyalty > 4 && opinion >= MajorCivOpinion.Neutral) return false
                    if (loyalty == 4 && opinion == MajorCivOpinion.Favorable) return false
                }
            }
            val aggressive = civ.ai.isConqueror() || civ.ai.hasTraitCategory(TraitCategory.Warmonger) ||
                civ.ai.hasTraitCategory(TraitCategory.Expansionist)
            val easyTarget = WarStateAi.isEasyTarget(civ, other)
            val military = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
            if (direct) {
                val economic = memory.economicStrength ?: StrengthLevel.Average
                // At least two of: our cities not exposed to them, their cities vulnerable (a preferred attack target), an easy target
                val ourCitiesOk = !civ.ai.military.isExposedToEnemy(civ, null, other)
                val theirCitiesVulnerable = civ.ai.military.havePreferredAttackTarget(other)
                val atLeastTwo = (ourCitiesOk && theirCitiesVulnerable) || (ourCitiesOk && easyTarget) || (theirCitiesVulnerable && easyTarget)
                val limit = when {
                    atLeastTwo -> if (aggressive) StrengthLevel.Powerful else StrengthLevel.Strong
                    aggressive -> StrengthLevel.Average
                    else -> StrengthLevel.Poor
                }
                if (military >= limit || economic >= limit) return false
            } else {
                val strengthOk = when (DiplomacyTurn.proximity(other, civ)) {
                    DiplomacyTurn.PROXIMITY_NEIGHBORS -> military <= if (aggressive) StrengthLevel.Average else StrengthLevel.Poor
                    DiplomacyTurn.PROXIMITY_CLOSE -> military <= if (aggressive) StrengthLevel.Strong else StrengthLevel.Average
                    else -> true
                }
                if (!strengthOk) return false
            }
            val holdsCapital = !VictoryProgressAi.hasLostCapital(other)
            var goodReason = backstabber || endgameAggressive || untrustworthy ||
                (VictoryProgressAi.isCloseToWorldConquest(civ, rules) && holdsCapital) || OpinionAi.weDeclaredWarOnFriendCount(other) > 0 ||
                CompetitorAi.biggestCompetitor(civ) == other.civID || ai.warmongerThreat(other) >= AiThreat.Severe
            if (!direct) goodReason = goodReason || (civ.ai.isGoingForWorldConquest() && holdsCapital) || CompetitorAi.isMajorCompetitor(civ, other)
            if (!goodReason) return false
            if (OpinionAi.isDoFAccepted(civ, other)) {
                if (direct) for (third in majors) {
                    if (ignoresBackstabbing(third, other) || ignoresBackstabbing(third, civ, checkCurrent = true)) continue
                    if (isFriendOrAlly(civ, third)) return false
                    if (!backstabber && ai.opinionOf(third) >= MajorCivOpinion.Friend) return false
                    val proximity = DiplomacyTurn.proximity(third, civ)
                    val strength = WarStateAi.militaryStrengthComparedToUs(civ, third) ?: StrengthLevel.Average
                    val strengthOk = (proximity == DiplomacyTurn.PROXIMITY_NEIGHBORS && strength < StrengthLevel.Average) ||
                        (proximity == DiplomacyTurn.PROXIMITY_CLOSE && strength < StrengthLevel.Powerful) || proximity < DiplomacyTurn.PROXIMITY_CLOSE
                    // and an easy target or our cities not exposed to them
                    if (!strengthOk || !(WarStateAi.isEasyTarget(civ, third) || !civ.ai.military.isExposedToEnemy(civ, null, third))) return false
                } else {
                    if (!backstabber && !endgameAggressive && loyalty > 8) return false
                    if (impulse && opinion >= MajorCivOpinion.Friend) return false
                }
            }
        }
        return true
    }

    /** CvDiplomacyAI::IsFriendOrAlly: a DoF or a defensive pact */
    private fun isFriendOrAlly(civ: Civilization, other: Civilization): Boolean =
        OpinionAi.isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** CvDiplomacyAI::IsBackstabber, as VP's TestBackstabberFlag keeps it: we declared war on a friend */
    fun isBackstabber(civ: Civilization): Boolean = OpinionAi.weDeclaredWarOnFriendCount(civ) > 0

    /** We broke a DoF with [other] within DOF_BROKEN_BACKSTAB_TIMER turns (IsDoFBroken and GetTurnsSinceDoFBroken on their side) */
    private fun isBackstabTimer(civ: Civilization, other: Civilization): Boolean {
        val broken = other.ai.diplomacy.memoryOf(civ).doFBrokenTurn
        return broken >= 0 && civ.gameInfo.turns - broken < civ.gameInfo.ruleset.ai.define(AiDefine.DOF_BROKEN_BACKSTAB_TIMER)
    }

    /** CvDiplomacyAIHelpers::IgnoresBackstabbing: [observer] and [victim] broke a DoF with each other before, or ([checkCurrent])
     *  [victim] is untrustworthy */
    internal fun ignoresBackstabbing(observer: Civilization, victim: Civilization, checkCurrent: Boolean = false): Boolean {
        if (observer.ai.diplomacy.memoryOf(victim).doFBrokenTurn >= 0 || victim.ai.diplomacy.memoryOf(observer).doFBrokenTurn >= 0) return true
        return checkCurrent && OpinionAi.isUntrustworthyFriend(victim)
    }

    /** CvDiplomacyAI::GetGlobalCoopWarWithState: the furthest coop war agreed with [partner], against anyone; null = none */
    private fun coopWarWithState(civ: Civilization, partner: Civilization): CoopWarState? =
        civ.ai.diplomacy.memories[partner.civID]?.coopWars?.values?.mapNotNull { it.state }?.maxOrNull()

    /**
     *  CvDiplomacyAI::IsLiberator: not one who took our capital or holy city, captured more of our cities than they liberated, is at
     *  war with us or untrustworthy; one who resurrected us, or (unless [onlyMajorCities]) liberated more of our cities than captured.
     */
    fun isLiberator(civ: Civilization, other: Civilization, onlyMajorCities: Boolean): Boolean {
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (OpinionAi.isCapitalCapturedBy(civ, other) || CompetitorAi.isHolyCityCapturedBy(civ, other)) return false
        if (memory.numCitiesCapturedBy > memory.numCitiesLiberated) return false
        if (civ.isAtWarWith(other) || OpinionAi.isUntrustworthyFriend(other)) return false
        if (memory.resurrectedUs) return true
        return !onlyMajorCities && memory.numCitiesLiberated > 0 && memory.numCitiesLiberated > memory.numCitiesCapturedBy
    }

    /** CvDiplomacyAI::IsCityRecentlyLiberatedBy: a liberator who liberated a city of ours within 20 turns */
    fun isCityRecentlyLiberatedBy(civ: Civilization, other: Civilization): Boolean {
        val turn = civ.ai.diplomacy.memoryOf(other).liberatedCitiesTurn
        if (turn < 0 || !isLiberator(civ, other, onlyMajorCities = false)) return false
        return civ.gameInfo.turns - turn < 20
    }

    /** CvPlayer::getCitiesLost: the cities others captured from us */
    fun citiesLost(civ: Civilization): Int = civ.ai.diplomacy.memories.values.sumOf { it.numCitiesCapturedBy }
}
