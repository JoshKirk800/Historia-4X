package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  Vox Populi's friendship planning (Release-5.4.8; D7): the Declaration of Friendship half of CvDiplomacyAI::DoUpdatePlanningExchanges
 *  and the checks it reads (IsGoodChoiceForDoF, IsEndDoFAcceptable, IsDenounceFriendAcceptable, IsDoFAcceptable, IsTooEarlyForDoF).
 *  Each turn an AI decides with whom it wants a friendship ([MajorCivMemory.wantsDoF]), which friendships it wants to end
 *  ([MajorCivMemory.wantsToEndDoF]) and its most valuable friend ([DiplomacyAi.mostValuableFriend]); its statements act on these.
 *
 *  As none (no input yet): vassals, the league's prime competitor and ally, sanctions, victory dispute and block levels, plots and coups
 *  against us, the coop war agreement score, liberated or returned capitals and holy cities, strategic trade partners, DoF-to-votes
 *  policies, teams. VP's defensive pact half follows
 *  ([PactPlanningAi.updatePacts]).
 */
object FriendshipPlanningAi {

    /** VP's PromiseStates as far as a promise can stand: kept (or never made), ignored, broken */
    private enum class PromiseState { None, Ignored, Broken }

    private fun state(broken: Boolean, ignored: Boolean) = when {
        broken -> PromiseState.Broken
        ignored -> PromiseState.Ignored
        else -> PromiseState.None
    }

    /** DoUpdatePlanningExchanges, the friendship half: wants-DoF and wants-to-end-DoF towards every major, and the most valuable friend */
    fun updateFriendships(civ: Civilization) {
        if (civ.isHuman()) return
        val ai = civ.ai.diplomacy
        val rules = civ.gameInfo.ruleset.ai
        val valid = DiplomacyTurn.validPlayers(civ).filter { it.isMajorCiv }
        for ((id, memory) in ai.memories) {
            val other = civ.gameInfo.getCivilizationOrNull(id) ?: continue
            if (!other.isMajorCiv || other in valid) continue
            memory.wantsDoF = false
            memory.wantsToEndDoF = OpinionAi.isDoFAccepted(civ, other) && other.isAlive()
        }

        val acceptable = valid.filter { isAcceptableFriend(civ, it, rules) }.toSet()
        for (other in valid) {
            val memory = ai.memoryOf(other)
            // Not if we broke off a DoF with them recently
            if (isDoFBrokenByUs(civ, other, 30)) {
                memory.wantsDoF = false
                memory.wantsToEndDoF = false
                continue
            }
            if (OpinionAi.isDoFAccepted(civ, other) && other !in acceptable) {
                memory.wantsToEndDoF = isEndDoFAcceptable(civ, other)
                memory.wantsDoF = false
            } else {
                memory.wantsDoF = OpinionAi.isDoFAccepted(civ, other)
                memory.wantsToEndDoF = false
            }
        }

        var friends = valid.count { OpinionAi.isDoFAccepted(civ, it) }
        val doFWillingness = civ.ai.diplomacyValue(DiplomacyPersonality.DoFWillingness)
        val baseFriendLimit = when {
            doFWillingness > 8 -> 4
            doFWillingness > 6 -> 3
            else -> 2
        }
        // Scaled with the map: per 8 valid players
        val friendLimit = maxOf(baseFriendLimit, baseFriendLimit * valid.size / 8)
        val bestFriendPossible = acceptable.isNotEmpty() && valid.size > 1
        var mostValuableFriend: Civilization? = null
        var limitReached = false
        for (approach in CANDIDATE_ORDER) {
            if (limitReached) break
            val excluded = HashSet<Civilization>()
            while (true) {
                val candidate = highestStrategicApproachValue(civ, valid, approach, excluded) ?: break
                if (candidate in acceptable) {
                    val deceptive = approach == MajorCivApproach.Deceptive
                    if (!deceptive && mostValuableFriend == null && bestFriendPossible) mostValuableFriend = candidate
                    if (!OpinionAi.isDoFAccepted(civ, candidate) && friends < friendLimit) {
                        ai.memoryOf(candidate).wantsDoF = true
                        friends++
                        if (friends >= friendLimit && (deceptive || mostValuableFriend != null || !bestFriendPossible)) {
                            limitReached = true
                            break
                        }
                    }
                }
                excluded.add(candidate)
            }
        }
        ai.mostValuableFriend = mostValuableFriend?.civID
        PactPlanningAi.updatePacts(civ, valid, acceptable, mostValuableFriend, bestFriendPossible)
    }

    /** The order DoUpdatePlanningExchanges walks the candidates in: by their strategic friendly, neutral, afraid, then deceptive values */
    private val CANDIDATE_ORDER = listOf(MajorCivApproach.Friendly, MajorCivApproach.Neutral, MajorCivApproach.Afraid, MajorCivApproach.Deceptive)

    /** GetPlayerWithHighestStrategicApproachValue: the valid player not in [excluded] with the highest strategic value above 0 for [approach] */
    private fun highestStrategicApproachValue(civ: Civilization, valid: List<Civilization>, approach: MajorCivApproach,
                                              excluded: Set<Civilization>): Civilization? {
        if (civ.getCapital() == null) return null
        var best: Civilization? = null
        var highest = 0
        for (other in valid) {
            if (other in excluded) continue
            val value = civ.ai.diplomacy.memoryOf(other).strategicApproachValues.getOrNull(approach.ordinal) ?: 0
            if (value > highest) {
                best = other
                highest = value
            }
        }
        return best
    }

    /**
     *  The first filter of DoUpdatePlanningExchanges: not at war; our biggest competitor only on good terms (a friend - or neutral once
     *  we have been friends - or a liberator we do not hate), with no denounced friend, friendship or pact with our enemies or denouncement
     *  by a friend of ours, no aggression (war, hostile or guarded approach, a severe warmonger threat, endgame aggression), no culture
     *  bombs, no assistance above 75 (VP's sign: helped us; VP files it under "being nasty to us"), no broken or ignored promises;
     *  then [isGoodChoiceForDoF].
     */
    private fun isAcceptableFriend(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (civ.isAtWarWith(other)) return false
        val ai = civ.ai.diplomacy
        if (ai.biggestCompetitor == other.civID) {
            val memory = ai.memoryOf(other)
            val opinion = ai.opinionOf(other)
            val approach = ai.approachTowards(other)
            val liberator = WarSanityAi.isLiberator(civ, other, onlyMajorCities = false) && opinion > MajorCivOpinion.Enemy
            val workedTogether = memory.doFLevel >= DoFLevel.Friends
            if (((!workedTogether && opinion < MajorCivOpinion.Friend) || (workedTogether && opinion < MajorCivOpinion.Neutral)) && !liberator)
                return false
            if (OpinionAi.isPlayerDenouncedFriend(civ, other) || isPlayerDoFWithAnyEnemy(civ, other) || isPlayerDPWithAnyEnemy(civ, other) ||
                isDenouncedByOurFriend(civ, other)) return false
            if (approach == MajorCivApproach.War || approach == MajorCivApproach.Hostile || approach == MajorCivApproach.Guarded ||
                ai.warmongerThreat(other) >= AiThreat.Severe || VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) return false
            // Our recent assist value has the opposite sign of VP's (ours: negative = they helped us)
            if (memory.recentAssistValue < -75 || memory.numTimesCultureBombed > 0) return false
            if (memory.brokenExpansionPromise || memory.ignoredExpansionPromise ||
                memory.brokenBorderPromise || memory.ignoredBorderPromise ||
                memory.brokenSpyPromise || memory.ignoredSpyPromise || memory.brokenBullyCityStatePromise || memory.ignoredBullyCityStatePromise ||
                memory.brokenAttackCityStatePromise || memory.ignoredAttackCityStatePromise ||
                memory.brokenNoConvertPromise || memory.ignoredNoConvertPromise || memory.brokenNoDiggingPromise || memory.ignoredNoDiggingPromise)
                return false
        }
        return isGoodChoiceForDoF(civ, other)
    }

    /**
     *  CvDiplomacyAI::IsGoodChoiceForDoF: no war planned or fought with them, known long enough, trustworthy, no denouncement either way,
     *  our strategic and current approaches better than hostile (the opinion is left out on purpose, to let relations heal), no wish to
     *  denounce them, end a friendship with them or backstab them, and no peace treaty with them within the handicap's dampener turns.
     */
    fun isGoodChoiceForDoF(civ: Civilization, other: Civilization): Boolean {
        if (WarAlliesAi.avoidExchangesWithPlayer(civ, other, warOnly = true)) return false
        if (isTooEarlyForDoF(civ, other)) return false
        if (OpinionAi.isUntrustworthyFriend(other)) return false
        if (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ)) return false
        val ai = civ.ai.diplomacy
        val strategic = ai.memoryOf(other).strategicApproach ?: MajorCivApproach.Neutral
        if (strategic.isWarOrHostile() || ai.approachTowards(other).isWarOrHostile()) return false
        if (DiplomacyAutomation.isDenounceAcceptable(civ, other, bias = false) || isEndDoFAcceptable(civ, other, ignoreCurrentDoF = true) ||
            isDenounceFriendAcceptable(civ, other)) return false
        return !WarTargetAi.isRecentPeaceTreaty(civ, other, civ.gameInfo.ruleset.ai)
    }

    private fun MajorCivApproach.isWarOrHostile() = this == MajorCivApproach.War || this == MajorCivApproach.Hostile

    /**
     *  CvDiplomacyAI::IsTooEarlyForDoF: known for fewer than DOF_TURN_BUFFER turns plus DOF_TURN_BUFFER_REDUCTION_PER_ERA per era of
     *  ours, but at least JUST_MET_TURN_BUFFER
     */
    fun isTooEarlyForDoF(civ: Civilization, other: Civilization): Boolean {
        val met = civ.ai.diplomacy.memoryOf(other).turnMet
        if (met < 0) return false
        val rules = civ.gameInfo.ruleset.ai
        var buffer = rules.define(AiDefine.DOF_TURN_BUFFER)
        val era = civ.getEraNumber()
        if (era > 0) buffer += minOf(0, era * rules.define(AiDefine.DOF_TURN_BUFFER_REDUCTION_PER_ERA))
        buffer = maxOf(buffer, rules.define(AiDefine.JUST_MET_TURN_BUFFER))
        return civ.gameInfo.turns - met < buffer
    }

    /** [civ] broke a DoF with [other] within [turns] turns (their IsDoFBroken and GetTurnsSinceDoFBroken towards us) */
    private fun isDoFBrokenByUs(civ: Civilization, other: Civilization, turns: Int): Boolean {
        val broken = other.ai.diplomacy.memoryOf(civ).doFBrokenTurn
        return broken >= 0 && civ.gameInfo.turns - broken < turns
    }

    /**
     *  CvDiplomacyAI::IsDoFAcceptable: not at war, not within 30 turns of our breaking a friendship with them; yes when we are afraid of
     *  them and they are trustworthy; no on a turn they ended a friendship; else whether we want one ([MajorCivMemory.wantsDoF]).
     */
    fun isDoFAcceptable(civ: Civilization, other: Civilization): Boolean {
        if (civ.isAtWarWith(other)) return false
        if (isDoFBrokenByUs(civ, other, 30)) return false
        val ai = civ.ai.diplomacy
        if (ai.approachTowards(other) == MajorCivApproach.Afraid && !OpinionAi.isUntrustworthyFriend(other)) return true
        if (hasEndedFriendshipThisTurn(other)) return false
        return ai.memoryOf(other).wantsDoF
    }

    /** CvDiplomacyAI::HasEndedFriendshipThisTurn */
    fun hasEndedFriendshipThisTurn(civ: Civilization): Boolean = civ.ai.diplomacy.endedFriendshipTurn == civ.gameInfo.turns

    /**
     *  CvDiplomacyAI::IsEndDoFAcceptable: whether we want to end our friendship with [other] early ([ignoreCurrentDoF]: as if we had
     *  one). Untrustworthy: yes; war planned with them and (as if we had one, or) the war not sane: yes; befriended within 15 turns: no;
     *  afraid or friendly approach or a friend opinion: no; enemy or worse: yes. Else a chance of 10 + loyalty, with resurrection +20,
     *  liberated cities +2 each, a coop war prepared together +5, less the warmonger hate for a severe warmonger, broken (ignored)
     *  promises by our leanings, competitiveness for endgame aggression, boldness for a strong land dispute, wonder and minor civ
     *  competitiveness for strong disputes, 10 for an earlier backstab, plus 2, 5, 10 for friends, allies, battle brothers: <= 0 ends it.
     */
    fun isEndDoFAcceptable(civ: Civilization, other: Civilization, ignoreCurrentDoF: Boolean = false): Boolean {
        if (!ignoreCurrentDoF && !OpinionAi.isDoFAccepted(civ, other)) return false
        if (OpinionAi.isUntrustworthyFriend(other)) return true
        if (WarAlliesAi.avoidExchangesWithPlayer(civ, other) && (ignoreCurrentDoF || !WarSanityAi.isWarSane(civ, other))) return true
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        // GetTurnsSinceBefriendedPlayer: Unciv counts it in doFCounter from the signing
        if (!ignoreCurrentDoF && memory.doFCounter <= 15) return false
        val approach = ai.approachTowards(other)
        if (approach == MajorCivApproach.Afraid || approach == MajorCivApproach.Friendly) return false
        val opinion = ai.opinionOf(other)
        if (opinion >= MajorCivOpinion.Friend) return false
        if (opinion <= MajorCivOpinion.Enemy) return true

        val rules = civ.gameInfo.ruleset.ai
        fun personality(value: DiplomacyPersonality) = civ.ai.diplomacyValue(value)
        var chance = 10 + personality(DiplomacyPersonality.Loyalty)
        if (memory.resurrectedUs) chance += 20
        chance += memory.numCitiesLiberated * 2
        val coop = ai.memories[other.civID]?.coopWars?.values?.mapNotNull { it.state }?.maxOrNull()
        if (coop != null && coop >= CoopWarState.Soon) chance += 5
        if (ai.warmongerThreat(other) >= AiThreat.Severe) chance -= personality(DiplomacyPersonality.WarmongerHate)

        val competing = VictoryProgressAi.isCompetingForVictory(civ)
        val civAi = civ.ai
        val bold = personality(DiplomacyPersonality.Boldness) > 7 || civAi.isConqueror() || civAi.isSecondaryConqueror() ||
            (competing && civAi.isGoingForWorldConquest()) || civAi.hasTraitCategory(TraitCategory.Warmonger)
        val diplomatic = civAi.isDiplomat() || civAi.isSecondaryDiplomat() || (competing && civAi.isGoingForDiploVictory()) ||
            civAi.hasTraitCategory(TraitCategory.Diplomat)
        val scientific = civAi.isScientist() || civAi.isSecondaryScientist() || (competing && civAi.isGoingForSpaceshipVictory()) ||
            civAi.hasTraitCategory(TraitCategory.Nerd)
        val religious = civAi.personality(Flavor.Religion).coerceIn(1, 10) > 6 || civAi.hasTraitCategory(TraitCategory.Religious)
        val cultural = civAi.personality(Flavor.Culture).coerceIn(1, 10) > 6 || civAi.isCultural() || civAi.isSecondaryCultural() ||
            (competing && civAi.isGoingForCultureVictory()) || civAi.hasTraitCategory(TraitCategory.Tourism)
        fun penalty(state: PromiseState, broken: Int, ignored: Int) {
            chance -= when (state) {
                PromiseState.Broken -> broken
                PromiseState.Ignored -> ignored
                PromiseState.None -> 0
            }
        }
        penalty(state(memory.brokenExpansionPromise, memory.ignoredExpansionPromise), if (bold) 10 else 5, if (bold) 10 else 2)
        penalty(state(memory.brokenBorderPromise, memory.ignoredBorderPromise), if (bold) 10 else 5, if (bold) 10 else 2)
        penalty(state(memory.brokenBullyCityStatePromise, memory.ignoredBullyCityStatePromise), if (diplomatic) 10 else 5, if (diplomatic) 5 else 2)
        if (memory.brokenAttackCityStatePromise) return true
        penalty(state(false, memory.ignoredAttackCityStatePromise), 0, if (diplomatic) 10 else 5)
        penalty(state(memory.brokenSpyPromise, memory.ignoredSpyPromise), if (scientific) 20 else 10, if (scientific) 10 else 5)
        penalty(state(memory.brokenNoConvertPromise, memory.ignoredNoConvertPromise), if (religious) 20 else 10, if (religious) 10 else 5)
        penalty(state(memory.brokenNoDiggingPromise, memory.ignoredNoDiggingPromise), if (cultural) 20 else 10, if (cultural) 10 else 5)

        if (competing && VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) chance -= personality(DiplomacyPersonality.VictoryCompetitiveness)
        if (ai.landDisputeLevel(other) >= DisputeLevel.Strong) chance -= personality(DiplomacyPersonality.Boldness)
        if (memory.wonderDispute >= DisputeLevel.Strong) chance -= personality(DiplomacyPersonality.WonderCompetitiveness)
        if (memory.minorCivDispute >= DisputeLevel.Strong) chance -= personality(DiplomacyPersonality.MinorCivCompetitiveness)
        if (wasEverBackstabbedBy(civ, other)) chance -= 10
        chance += when (memory.doFLevel) {
            DoFLevel.Untrustworthy, DoFLevel.New -> 0
            DoFLevel.Friends -> 2
            DoFLevel.Allies -> 5
            DoFLevel.BattleBrothers -> 10
        }
        return chance <= 0
    }

    /** CvDiplomacyAI::WasEverBackstabbedBy: [other] denounced us or declared war on us while we were friends */
    private fun wasEverBackstabbedBy(civ: Civilization, other: Civilization): Boolean {
        val memory = civ.ai.diplomacy.memoryOf(other)
        return memory.friendDenouncedUs || memory.friendDeclaredWarOnUs
    }

    private fun isFriendOrAlly(civ: Civilization, other: Civilization): Boolean =
        OpinionAi.isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** GetCachedOpinionWeight of [observer] towards [target] (lower is better), 0 when it has no memory of them */
    private fun opinionWeight(observer: Civilization, target: Civilization): Int =
        observer.ai.diplomacy.memories[target.civID]?.opinionWeight ?: 0

    /** CvDiplomacyAI::EstimateLoyalty and the like: a leader's base personality value (1..10); 5 for a human */
    private fun estimate(other: Civilization, value: DiplomacyPersonality): Int {
        if (other.isHuman()) return 5
        val personality = other.nation.personality?.let { other.gameInfo.ruleset.personalities[it] }
        val base = personality?.diplomacyPersonality?.get(value.name) ?: other.gameInfo.ruleset.ai.define(AiDefine.DEFAULT_FLAVOR_VALUE)
        return base.coerceIn(1, 10)
    }

    /**
     *  CvDiplomacyAI::IsDenounceFriendAcceptable: whether we would rather denounce our friend [other] than end the friendship. Only
     *  the treacherous consider it (a backstabber, they are untrustworthy or backstabbed us, denounce willingness 8+, loyalty 3 or less);
     *  never battle brothers or one who resurrected us. Someone must care (a friend, one we want as a friend, one powerful, or of our
     *  strength and close, who likes us better than them and would not count us as a serial backstabber), and none of those who would
     *  side with them may be one we fear. Then untrustworthy: yes; afraid or friendly approach: no; hostile: yes; enemy opinion: yes;
     *  favourable or better: no; else a chance of 10 + loyalty, less denounce willingness for a competitor, warmonger hate for a severe
     *  warmonger, victory competitiveness for endgame aggression, meanness when conquering a weak and juicy neighbour, 10 for an
     *  earlier backstab, plus 2 or 5 for friends or allies and 3 per city they liberated: <= 0 denounces.
     */
    fun isDenounceFriendAcceptable(civ: Civilization, other: Civilization): Boolean {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        fun personality(value: DiplomacyPersonality) = civ.ai.diplomacyValue(value)
        val untrustworthy = OpinionAi.isUntrustworthyFriend(other)
        val backstabbed = wasEverBackstabbedBy(civ, other)
        if (!WarSanityAi.isBackstabber(civ) && !untrustworthy && !backstabbed && personality(DiplomacyPersonality.DenounceWillingness) < 8 &&
            personality(DiplomacyPersonality.Loyalty) > 3) return false
        if (memory.doFLevel == DoFLevel.BattleBrothers) return false
        if (memory.resurrectedUs) return false

        val rules = civ.gameInfo.ruleset.ai
        var anyoneWouldCare = false
        val weDenouncedFriendCount = OpinionAi.weDenouncedFriendCount(civ) + 1
        val majorsEver = civ.gameInfo.civilizations.count { it.isMajorCiv }
        val majorsAlive = civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() }
        val dangerous = majorsAlive <= majorsEver / 2 || VictoryProgressAi.capitalsConquered(civ) >= majorsEver / 3
        for (third in civ.gameInfo.civilizations) {
            if (!third.isMajorCiv || !third.isAlive() || third.cities.isEmpty() || third == other || third == civ) continue
            if (OpinionAi.isUntrustworthyFriend(civ)) continue
            if (third.isAtWarWith(civ) || OpinionAi.isDenouncedPlayer(civ, third) || OpinionAi.isDenouncedPlayer(third, civ)) continue
            var deterrent = ai.memories[third.civID]?.wantsDoF == true || isFriendOrAlly(civ, third)
            if (!deterrent) {
                val strength = WarStateAi.militaryStrengthComparedToUs(civ, third) ?: StrengthLevel.Average
                if (strength >= StrengthLevel.Powerful) deterrent = true
                if (strength >= StrengthLevel.Average && DiplomacyTurn.proximity(third, civ) >= DiplomacyTurn.PROXIMITY_CLOSE) deterrent = true
            }
            if (third.isHuman()) {
                if (third.isAtWarWith(other) || OpinionAi.isDenouncedPlayer(third, other)) anyoneWouldCare = true
                else {
                    if (isFriendOrAlly(third, other)) {
                        if (deterrent) return false else continue
                    }
                    if (WarSanityAi.ignoresBackstabbing(third, civ)) continue
                    if (isFriendOrAlly(civ, third) || OpinionAi.isDenouncedPlayer(other, third) || wasEverBackstabbedBy(third, other))
                        anyoneWouldCare = true
                }
                continue
            }
            val theirAi = third.ai.diplomacy
            if (!WarSanityAi.ignoresBackstabbing(third, other)) {
                // Cheat a bit, as VP does: their opinion weights (lower is better)
                if (opinionWeight(third, other) <= opinionWeight(third, civ) || isFriendOrAlly(third, other)) {
                    if (deterrent) return false else continue
                }
                if (theirAi.memories[civ.civID]?.resurrectedUs != true) {
                    var tolerance = if (estimate(third, DiplomacyPersonality.Loyalty) < 5) 2 else 1
                    if (estimate(third, DiplomacyPersonality.Forgiveness) > 8) tolerance++
                    if (dangerous) tolerance = 1
                    else if (WarSanityAi.isBackstabber(third)) continue
                    if (WarSanityAi.isLiberator(third, civ, onlyMajorCities = true)) tolerance++
                    if (weDenouncedFriendCount > tolerance) {
                        if (deterrent) return false else continue
                    }
                }
                if (WarSanityAi.ignoresBackstabbing(third, civ)) continue
                anyoneWouldCare = true
            } else {
                if (WarSanityAi.ignoresBackstabbing(third, civ)) continue
                if (opinionWeight(third, civ) < opinionWeight(third, other)) anyoneWouldCare = true
            }
        }
        if (!anyoneWouldCare) return false
        if (untrustworthy) return true

        val approach = ai.approachTowards(other)
        if (approach == MajorCivApproach.Afraid || approach == MajorCivApproach.Friendly) return false
        if (approach == MajorCivApproach.Hostile) return true
        val opinion = ai.opinionOf(other)
        if (opinion <= MajorCivOpinion.Enemy) return true
        if (opinion >= MajorCivOpinion.Favorable) return false

        var chance = 10 + personality(DiplomacyPersonality.Loyalty)
        if (opinion == MajorCivOpinion.Competitor) chance -= personality(DiplomacyPersonality.DenounceWillingness)
        if (ai.warmongerThreat(other) >= AiThreat.Severe) chance -= personality(DiplomacyPersonality.WarmongerHate)
        if (VictoryProgressAi.isCompetingForVictory(civ)) {
            if (VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) chance -= personality(DiplomacyPersonality.VictoryCompetitiveness)
            if (civ.ai.isGoingForWorldConquest() && DiplomacyTurn.proximity(civ, other) >= DiplomacyTurn.PROXIMITY_CLOSE) {
                val strength = WarStateAi.militaryStrengthComparedToUs(civ, other)
                val target = WarStateAi.targetValue(civ, other)
                if (strength != null && strength <= StrengthLevel.Poor && target != null && target >= TargetValue.Average)
                    chance -= personality(DiplomacyPersonality.Meanness)
            }
        }
        if (backstabbed) chance -= 10
        chance += when (memory.doFLevel) {
            DoFLevel.Allies -> 5
            DoFLevel.Friends -> 2
            else -> 0
        }
        chance += memory.numCitiesLiberated * 3
        return chance <= 0
    }

    /** CvDiplomacyAI::IsPlayerDoFWithAnyEnemy (Vox Populi): [other] is friends with someone we denounced or fight */
    private fun isPlayerDoFWithAnyEnemy(civ: Civilization, other: Civilization): Boolean = civ.gameInfo.civilizations.any { third ->
        third != other && third != civ && third.isMajorCiv && third.isAlive() && third.cities.isNotEmpty() && !(civ.isHuman() && third.isHuman()) &&
            OpinionAi.isDoFAccepted(other, third) && (OpinionAi.isDenouncedPlayer(civ, third) || civ.isAtWarWith(third))
    }

    /** CvDiplomacyAI::IsPlayerDPWithAnyEnemy: [other] has a defensive pact with someone we denounced or fight */
    private fun isPlayerDPWithAnyEnemy(civ: Civilization, other: Civilization): Boolean = civ.gameInfo.civilizations.any { third ->
        third != other && third != civ && third.isMajorCiv && third.isAlive() && third.cities.isNotEmpty() &&
            other.getDiplomacyManager(third)?.diplomaticStatus == DiplomaticStatus.DefensivePact &&
            (OpinionAi.isDenouncedPlayer(civ, third) || civ.isAtWarWith(third))
    }

    /**
     *  GetDenouncedByOurFriendScore > 0: unless [other] is our friend, ally or liberator, a friend or pact partner of ours denounced
     *  them whom we hold at least as dear (by DoF level and opinion weight)
     */
    private fun isDenouncedByOurFriend(civ: Civilization, other: Civilization): Boolean {
        if (isFriendOrAlly(civ, other)) return false
        if (WarSanityAi.isLiberator(civ, other, onlyMajorCities = false)) return false
        val ai = civ.ai.diplomacy
        return civ.gameInfo.civilizations.any { third ->
            third != civ && third != other && third.isMajorCiv && third.isAlive() && third.cities.isNotEmpty() &&
                OpinionAi.isDenouncedPlayer(third, other) &&
                ai.memoryOf(other).doFLevel <= ai.memoryOf(third).doFLevel &&
                ai.memoryOf(other).opinionWeight > ai.memoryOf(third).opinionWeight &&
                isFriendOrAlly(civ, third)
        }
    }
}
