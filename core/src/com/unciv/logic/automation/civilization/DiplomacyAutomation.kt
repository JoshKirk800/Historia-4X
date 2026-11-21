package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.diplomacy.AggressivePosture
import com.unciv.logic.automation.diplomacy.ApproachAi
import com.unciv.logic.automation.diplomacy.BlockLevel
import com.unciv.logic.automation.diplomacy.CompetitorAi
import com.unciv.logic.automation.diplomacy.CoopWarAi
import com.unciv.logic.automation.diplomacy.CoopWarState
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.IntrigueAi
import com.unciv.logic.automation.diplomacy.DiploStatement
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.FriendshipPlanningAi
import com.unciv.logic.automation.diplomacy.MajorCivMemory
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.WarAi
import com.unciv.logic.automation.diplomacy.WarFace
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.automation.diplomacy.WarSanityAi
import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.tile.ResourceType

/**
 *  What the diplomacy AI of a major civilization does in its turn (the acting part of CvDiplomacyAI::DoTurn): MakeWar, DoMakePeaceWithMinors,
 *  DoUpdateDemands, DoUpdatePlanningExchanges, then DoContactMajorCivs - one statement per player per turn, first come first served
 *  (coop war, demand, DoF, denouncements, then the trade offers of the deal AI, then requests; peace offers while at war).
 *  The trade offers (luxuries, embassies, open borders, research agreements, renewals) are the deal AI's and run between
 *  [contactMajorCivs] and [contactMajorCivsAfterOffers]; they use [com.unciv.logic.automation.diplomacy.DiplomacyAi.hasMadeStatementThisTurn].
 */
object DiplomacyAutomation {

    private const val NEVER = 9999

    internal fun turnsSinceStatement(civ: Civilization, memory: MajorCivMemory, statement: DiploStatement): Int {
        val turn = memory.statementTurns[statement] ?: return NEVER
        return civ.gameInfo.turns - turn
    }

    private fun logStatement(civ: Civilization, other: Civilization, statement: DiploStatement) {
        civ.ai.diplomacy.memoryOf(other).statementTurns[statement] = civ.gameInfo.turns
    }

    internal fun sendStatement(civ: Civilization, other: Civilization, statement: DiploStatement) {
        logStatement(civ, other, statement)
        civ.ai.diplomacy.markStatementMade(other)
    }

    /** The first part of the acting turn: MakeWar, DoMakePeaceWithMinors, DoUpdateDemands, DoUpdatePlanningExchanges */
    fun doTurn(civ: Civilization) {
        val rules = civ.gameInfo.ruleset.ai
        timeThis("AI.MakeWar") { WarAi.makeWar(civ, rules) }
        WarAi.makePeaceWithMinors(civ)
        timeThis("AI.Demands") { updateDemands(civ, rules) }
        updatePlanningExchanges(civ)
    }

    /** CvDiplomacyAI::DoContactMajorCivs / DoContactPlayer up to the offers: AIs first, then humans */
    fun contactMajorCivs(civ: Civilization) = timeThis("AI.ContactMajorCivs") {
        val rules = civ.gameInfo.ruleset.ai
        val players = DiplomacyTurn.validPlayers(civ).filter { it.isMajorCiv }.sortedBy { it.isHuman() }
        for (other in players) contactPlayer(civ, other, rules)
    }

    /** DoContactPlayer after the offers: DoShareIntrigueStatement, DoRequest */
    fun contactMajorCivsAfterOffers(civ: Civilization) {
        val rules = civ.gameInfo.ruleset.ai
        for (other in DiplomacyTurn.validPlayers(civ)) {
            if (!other.isMajorCiv || civ.isAtWarWith(other) || !canContact(civ, other)) continue
            if (civ.ai.diplomacy.hasMadeStatementThisTurn(other)) continue
            if (IntrigueAi.doShareIntrigueStatement(civ, other)) continue
            doRequest(civ, other, rules)
        }
    }

    /** Not on the turn we met them (DoContactPlayer waits until we have said hello) */
    private fun canContact(civ: Civilization, other: Civilization): Boolean {
        val met = civ.ai.diplomacy.memoryOf(other).turnMet
        return met < 0 || civ.gameInfo.turns - met > 0
    }

    private fun contactPlayer(civ: Civilization, other: Civilization, rules: AiRuleset) {
        if (!canContact(civ, other) || civ.ai.diplomacy.hasMadeStatementThisTurn(other)) return
        if (civ.isAtWarWith(other)) {
            doPeaceOffer(civ, other)
            return
        }
        if (doCoopWarTimeStatement(civ, other, rules)) return
        if (doCoopWarStatement(civ, other, rules)) return
        if (doMakeDemand(civ, other, rules)) return
        if (InformationalStatementAi.contact(civ, other)) return
        // VP's order: end a friendship (or denounce the friend), denounce, offer a friendship, then a defensive pact
        if (doEndDoFStatement(civ, other)) return
        if (doDenounceStatement(civ, other)) return
        if (doDoFStatement(civ, other)) return
        if (doDefensivePactOffer(civ, other)) return
        doRequestFriendDenounceStatement(civ, other)
    }

    //region War, peace and coop wars

    /** CvDiplomacyAI::DoPeaceOffer with CvDealAI::IsOfferPeace: AIs settle between themselves, humans get a peace proposal */
    fun doPeaceOffer(civ: Civilization, other: Civilization) {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        if (WarStateAi.turnsAtWar(civ, other) <= 5) return
        if (memory.turnsLockedIntoWar != 0 || other.ai.diplomacy.memoryOf(civ).turnsLockedIntoWar != 0) return
        if (!ai.isWantsPeaceWith(other)) return
        if (turnsSinceStatement(civ, memory, DiploStatement.RequestPeace) < 10) return
        // CvDealAI::IsOfferPeace: the treaty both sides accept, with the surrender items of DoAddItemsToDealForPeaceTreaty
        val tradeLogic = TradeLogic(civ, other)
        if (!DealAi.isOfferPeace(civ, other, tradeLogic.currentTrade)) return
        sendStatement(civ, other, DiploStatement.RequestPeace)
        if (other.isHuman()) other.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
        else tradeLogic.acceptTrade()
    }

    /**
     *  CvDiplomacyAI::DoCoopWarStatement: ask [other] to join a war on our best target ([CoopWarAi.coopWarTarget]; Vox Populi has no
     *  condition on our wars, BNW asked only while at war with no major). An AI answers at once ([CoopWarAi.requestCoopWar]); a human gets an
     *  alert ([CoopWarAi.askHuman]) and answers it there ([CoopWarAi.onHumanAnswered]).
     */
    private fun doCoopWarStatement(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        val target = CoopWarAi.coopWarTarget(civ, other, rules) ?: return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (turnsSinceStatement(civ, memory, DiploStatement.CoopWarRequest) < 10) return false
        sendStatement(civ, other, DiploStatement.CoopWarRequest)
        if (other.isHuman()) CoopWarAi.askHuman(civ, other, target, now = false)
        else CoopWarAi.requestCoopWar(civ, other, target, rules)
        return true
    }

    /**
     *  CvDiplomacyAI::DoCoopWarTimeStatement: a human partner who said "soon" to a war on a target is asked again when the counter reaches
     *  COOP_WAR_SOON_COUNTER (an AI partner's counter is handled by [CoopWarAi.doCoopWarCounters]). A human who is at war with the target
     *  already is taken to have agreed, and we declare war too.
     */
    private fun doCoopWarTimeStatement(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (!other.isHuman()) return false
        for ((targetId, entry) in civ.ai.diplomacy.memoryOf(other).coopWars) {
            if (entry.state != CoopWarState.Soon || entry.counter != rules.define(AiDefine.COOP_WAR_SOON_COUNTER)) continue
            val target = civ.gameInfo.getCivilizationOrNull(targetId) ?: continue
            if (!target.isMajorCiv) continue
            if (!other.isAtWarWith(target) && target.isAlive()) {
                sendStatement(civ, other, DiploStatement.CoopWarTime)
                CoopWarAi.askHuman(civ, other, target, now = true)
                return true
            }
            entry.state = CoopWarState.Accepted
            if (!civ.isAtWarWith(target) && target.isAlive()) {
                WarAi.declareWar(civ, target)
                MilitaryOperations.requestBasicAttack(civ, target, 1)
            }
        }
        return false
    }
    //endregion

    //region Friendship and denouncements

    /**
     *  CvDiplomacyAI::DoFromUIDiploEvent (HUMAN_DISCUSSION_WORK_WITH_US): whether [civ] signs the Declaration of Friendship [human] asked
     *  for. Too early to know them: no; otherwise [FriendshipPlanningAi.isDoFAcceptable].
     */
    fun answerDoFRequest(civ: Civilization, human: Civilization): Boolean {
        if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(human)) return false
        if (FriendshipPlanningAi.isTooEarlyForDoF(civ, human)) return false
        return FriendshipPlanningAi.isDoFAcceptable(civ, human)
    }

    /**
     *  CvDiplomacyAI::DoDoFStatement: we want a friendship with them ([FriendshipPlanningAi.updateFriendships]), have not ended one this
     *  turn, and last asked 30 or more turns ago. A human gets the offer; an AI answers at once by its IsDoFAcceptable.
     */
    private fun doDoFStatement(civ: Civilization, other: Civilization): Boolean {
        if (OpinionAi.isDoFAccepted(civ, other)) return false
        if (FriendshipPlanningAi.hasEndedFriendshipThisTurn(civ)) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (!memory.wantsDoF) return false
        if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)) return false
        if (turnsSinceStatement(civ, memory, DiploStatement.WorkWithUs) < 30) return false
        sendStatement(civ, other, DiploStatement.WorkWithUs)
        if (other.isHuman()) {
            if (other.popupAlerts.none { it.type == AlertType.DeclarationOfFriendship && it.value == civ.civID })
                other.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, civ.civID))
            return true
        }
        if (FriendshipPlanningAi.isDoFAcceptable(other, civ)) civ.getDiplomacyManager(other)!!.signDeclarationOfFriendship()
        return true
    }

    /**
     *  CvDiplomacyAI::DoEndDoFStatement: we want to end our friendship with them; we denounce them when
     *  [FriendshipPlanningAi.isDenounceFriendAcceptable], else we just end it
     */
    private fun doEndDoFStatement(civ: Civilization, other: Civilization): Boolean {
        if (!OpinionAi.isDoFAccepted(civ, other)) return false
        if (!civ.ai.diplomacy.memoryOf(other).wantsToEndDoF) return false
        if (FriendshipPlanningAi.isDenounceFriendAcceptable(civ, other)) {
            sendStatement(civ, other, DiploStatement.DenounceFriend)
            civ.getDiplomacyManager(other)!!.denounce()
        } else {
            sendStatement(civ, other, DiploStatement.EndWorkWithUs)
            civ.getDiplomacyManager(other)!!.endDeclarationOfFriendship()
        }
        return true
    }

    /**
     *  CvDiplomacyAI::DoDefensivePactOffer: no exchanges to avoid with them, both want the pact ([PactPlanningAi.updatePacts]; a human
     *  is asked anyway), 20 turns since the last offer, and a balanced deal ([DealAi.isMakeOfferForDefensivePact]). A human gets the
     *  trade request; an AI's deal is carried out.
     */
    private fun doDefensivePactOffer(civ: Civilization, other: Civilization): Boolean {
        if (WarAlliesAi.avoidExchangesWithPlayer(civ, other)) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (!memory.wantsDefensivePact) return false
        if (!other.isHuman() && !other.ai.diplomacy.memoryOf(civ).wantsDefensivePact) return false
        if (turnsSinceStatement(civ, memory, DiploStatement.DefensivePactRequest) < 20) return false
        val trade = Trade()
        if (!DealAi.isMakeOfferForDefensivePact(civ, other, trade)) return false
        sendStatement(civ, other, DiploStatement.DefensivePactRequest)
        if (!TradeEvaluation().isTradeValid(trade, civ, other)) return true
        if (other.isHuman()) {
            if (other.tradeRequests.none { it.requestingCiv == civ.civID }) other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
            return true
        }
        val logic = TradeLogic(civ, other)
        logic.currentTrade.set(trade)
        logic.acceptTrade()
        return true
    }

    /**
     *  CvDiplomacyAI::GetDenounceWeight (Vox Populi, Release-5.4.8; D7): denounce willingness; by our approach (friendly never;
     *  hostile +6, afraid -10, neutral -8, guarded +1, deceptive -25, war +8 at war, else by its face: hostile +8, neutral -4,
     *  friendly -25) and opinion (unforgivable +10, enemy +5, competitor +2, neutral -5, favourable or better never); their strong victory
     *  dispute +4 and strong block +4; their land dispute (strong +2, fierce +5); -2 per defensive pact of theirs; our biggest competitor +10, a major competitor +2;
     *  untrustworthy +25; endgame aggression + the handicap's larger victory dispute or block percent / 10; resurrection -25 (by
     *  them) or -15 (by us), a defensive pact -10, a liberator -10, cities they liberated -5; each other major tied to them (a
     *  friendship, a pact, a denouncement, a war) that we neither hate nor approach worse than deceptively nor distrust, by
     *  neighbourhood, our opinion of it, its strength and our DoF or pact with it: added when it denounced or fights them, else taken
     *  away; +2 per city-state we do not mean to conquer or bully that asks us to denounce them (unless close to a peaceful victory),
     *  +3 more going for a diplomatic victory or with the diplomat trait, else +2 more with a diplomatic victory pursuit (primary or
     *  secondary); +3 when a friend asks ([bias]). A tied major is not counted when we are endgame-aggressive to them and in a victory
     *  dispute or block with them (VP: "don't be a pawn"). VP's loop also takes in ourselves; as none: vassals, sanctions.
     */
    fun denounceWeight(civ: Civilization, other: Civilization, bias: Boolean): Int {
        val ai = civ.ai.diplomacy
        val rules = civ.gameInfo.ruleset.ai
        val memory = ai.memoryOf(other)
        var weight = civ.ai.diplomacyValue(DiplomacyPersonality.DenounceWillingness)
        weight += when (ai.approachTowards(other)) {
            MajorCivApproach.Friendly -> return 0
            MajorCivApproach.Hostile -> 6
            MajorCivApproach.Afraid -> -10
            MajorCivApproach.Neutral -> -8
            MajorCivApproach.Guarded -> 1
            MajorCivApproach.Deceptive -> -25
            MajorCivApproach.War -> if (civ.isAtWarWith(other)) 8 else when (memory.warFace) {
                WarFace.Hostile -> 8
                WarFace.Friendly -> -25
                else -> -4
            }
        }
        weight += when (ai.opinionOf(other)) {
            MajorCivOpinion.Unforgivable -> 10
            MajorCivOpinion.Enemy -> 5
            MajorCivOpinion.Competitor -> 2
            MajorCivOpinion.Neutral -> -5
            MajorCivOpinion.Favorable, MajorCivOpinion.Friend, MajorCivOpinion.Ally -> return 0
        }
        if (memory.victoryDisputeLevel >= DisputeLevel.Strong) weight += 4
        if (memory.victoryBlockLevel >= BlockLevel.Strong) weight += 4
        weight += when (memory.landDispute) {
            DisputeLevel.Strong -> 2
            DisputeLevel.Fierce -> 5
            else -> 0
        }
        weight -= 2 * other.diplomacy.values.count { it.diplomaticStatus == DiplomaticStatus.DefensivePact }
        if (CompetitorAi.biggestCompetitor(civ) == other.civID) weight += 10
        else if (CompetitorAi.isMajorCompetitor(civ, other)) weight += 2
        if (OpinionAi.isUntrustworthyFriend(civ, other)) weight += 25
        val handicap = rules.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values
        if (VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules))
            weight += maxOf(handicap?.get("VictoryDisputePercent") ?: 100, handicap?.get("VictoryBlockPercent") ?: 100) / 10
        if (memory.resurrectedUs) weight -= 25
        if (civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact) weight -= 10
        if (WarSanityAi.isLiberator(civ, other, onlyMajorCities = true)) weight -= 10
        if (memory.numCitiesLiberated > 0) weight -= 5
        if (other.ai.diplomacy.memories[civ.civID]?.resurrectedUs == true) weight -= 15
        for (third in civ.gameInfo.civilizations) {
            if (!third.isMajorCiv || third == civ || third == other || !third.isAlive()) continue
            val tie = third.getDiplomacyManager(other)?.diplomaticStatus
            val againstThem = OpinionAi.isDenouncedPlayer(third, other) || third.isAtWarWith(other)
            if (!againstThem && !OpinionAi.isDoFAccepted(third, other) && tie != DiplomaticStatus.DefensivePact) continue
            if (ai.opinionOf(third) <= MajorCivOpinion.Enemy) continue
            if (ApproachAi.VP_ORDER.indexOf(ai.approachTowards(third)) <= ApproachAi.VP_ORDER.indexOf(MajorCivApproach.Deceptive)) continue
            if (OpinionAi.isUntrustworthyFriend(civ, third)) continue
            // Third party is close to victory? Don't be a pawn
            if (VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules) &&
                (memory.victoryDisputeLevel > DisputeLevel.None || memory.victoryBlockLevel > BlockLevel.None)) continue
            var mod = 0
            if (DiplomacyTurn.proximity(civ, third) == DiplomacyTurn.PROXIMITY_NEIGHBORS) mod++
            mod += ai.opinionOf(third).ordinal - MajorCivOpinion.Neutral.ordinal
            val strength = WarStateAi.militaryStrengthComparedToUs(civ, third) ?: StrengthLevel.Average
            if (strength > StrengthLevel.Average) mod += strength.ordinal - StrengthLevel.Average.ordinal
            if (OpinionAi.isDoFAccepted(civ, third)) mod += 4
            if (civ.getDiplomacyManager(third)?.diplomaticStatus == DiplomaticStatus.DefensivePact) mod += 2
            weight += if (againstThem) mod else -mod
        }
        val closeToPeacefulVictory = VictoryProgressAi.scienceVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_SCIENCE_VICTORY_THRESHOLD) ||
            VictoryProgressAi.cultureVictoryProgress(civ, rules) >= rules.define(AiDefine.CLOSE_TO_CULTURE_VICTORY_THRESHOLD) ||
            VictoryProgressAi.diplomaticVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD)
        if (!closeToPeacefulVictory) for (cityState in civ.gameInfo.civilizations) {
            if (!cityState.isCityState || !WarStateAi.isPlayerValid(civ, cityState) || civ.isAtWarWith(cityState)) continue
            val approach = civ.ai.minorCivApproaches[cityState.civID]
            if (approach == MinorCivApproach.Conquest || approach == MinorCivApproach.Bully) continue
            if (cityState.questManager.getAssignedQuestsFor(civ)
                    .any { it.questName == QuestName.DenounceCiv.value && it.data1 == other.civName }) {
                weight += 2
                if (civ.ai.isGoingForDiploVictory() || civ.ai.hasTraitCategory(TraitCategory.Diplomat)) weight += 3
                else if (civ.ai.isDiplomat() || civ.ai.isSecondaryDiplomat()) weight += 2
            }
        }
        if (bias) weight += 3
        return weight
    }

    /**
     *  CvDiplomacyAI::IsDenounceAcceptable (Vox Populi): not while at war and wanting peace or in the war's first turn, not when we
     *  denounced them already, are their friends, met them within 10 turns or broke a DoF with them within DOF_BROKEN_BACKSTAB_TIMER;
     *  then a [denounceWeight] above 25.
     */
    fun isDenounceAcceptable(civ: Civilization, other: Civilization, bias: Boolean): Boolean {
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (civ.isAtWarWith(other) && (memory.wantsPeace || WarStateAi.turnsAtWar(civ, other) == 0)) return false
        if (OpinionAi.isDenouncedPlayer(civ, other)) return false
        if (OpinionAi.isDoFAccepted(civ, other)) return false
        if (civ.gameInfo.turns - memory.turnMet < 10) return false
        val broken = other.ai.diplomacy.memories[civ.civID]?.doFBrokenTurn ?: -1
        if (broken >= 0 && civ.gameInfo.turns - broken < civ.gameInfo.ruleset.ai.define(AiDefine.DOF_BROKEN_BACKSTAB_TIMER)) return false
        return denounceWeight(civ, other, bias) > 25
    }

    private fun doDenounceStatement(civ: Civilization, other: Civilization): Boolean {
        if (!isDenounceAcceptable(civ, other, bias = false)) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (turnsSinceStatement(civ, memory, DiploStatement.Denounce) < 60 || turnsSinceStatement(civ, memory, DiploStatement.DenounceRandFailed) < 10) return false
        sendStatement(civ, other, DiploStatement.Denounce)
        civ.getDiplomacyManager(other)!!.denounce()
        return true
    }

    /**
     *  CvDiplomacyAI::GetRequestFriendToDenounce: someone we fight or denounced that our friend [friend] has not denounced. As the original,
     *  the weight is the denounce weight towards the friend plus Neediness, and must reach 23.
     */
    fun requestFriendToDenounceTarget(civ: Civilization, friend: Civilization): Pair<Civilization?, Boolean> {
        if (!OpinionAi.isDoFAccepted(civ, friend)) return null to false
        var target: Civilization? = null
        var bestWeight = -1
        var randFailed = false
        for (candidate in civ.gameInfo.civilizations) {
            if (!candidate.isMajorCiv || !candidate.isAlive() || candidate == civ || candidate == friend) continue
            if (!friend.knows(candidate) || friend.isAtWarWith(candidate)) continue
            if (OpinionAi.isDenouncedPlayer(friend, candidate)) continue
            if (!civ.isAtWarWith(candidate) && !OpinionAi.isDenouncedPlayer(civ, candidate)) continue
            val weight = denounceWeight(civ, friend, bias = false) +
                civ.ai.diplomacyValue(DiplomacyPersonality.Neediness)
            if (weight >= 23 && weight > bestWeight) {
                bestWeight = weight
                target = candidate
                randFailed = false
            } else randFailed = true
        }
        return target to randFailed
    }

    /**
     *  CvDiplomacyAI::DoRequestFriendDenounceStatement (DoSendStatementToPlayer REQUEST_FRIEND_DENOUNCE): an AI friend answers at once by the
     *  asker's own willingness; a human gets an alert and answers it there ([onHumanAnsweredDenounceRequest]).
     */
    private fun doRequestFriendDenounceStatement(civ: Civilization, friend: Civilization): Boolean {
        val (target, randFailed) = requestFriendToDenounceTarget(civ, friend)
        if (target == null) return false
        val memory = civ.ai.diplomacy.memoryOf(friend)
        if (turnsSinceStatement(civ, memory, DiploStatement.RequestFriendDenounce) < 50 ||
            turnsSinceStatement(civ, memory, DiploStatement.DenounceRandFailed) < 10) return false
        if (randFailed) {
            // As the original: the failed roll is logged as a failed denouncement
            logStatement(civ, friend, DiploStatement.DenounceRandFailed)
            return false
        }
        sendStatement(civ, friend, DiploStatement.RequestFriendDenounce)
        if (friend.isHuman()) {
            val value = PopupAlert.pairValue(civ.civID, target.civID)
            if (friend.popupAlerts.none { it.type == AlertType.DenounceRequest && it.value == value })
                friend.popupAlerts.add(PopupAlert(AlertType.DenounceRequest, value))
            return true
        }
        // As the original: the asker's own willingness decides whether the friend agrees
        if (isDenounceAcceptable(civ, target, bias = true)) friend.getDiplomacyManager(target)?.denounce()
        else if (isFriendDenounceRefusalUnacceptable(civ, target)) civ.getDiplomacyManager(friend)!!.denounce()
        return true
    }

    /**
     *  FROM_UI_DIPLO_EVENT_AI_REQUEST_DENOUNCE_RESPONSE: [human] answered [civ]'s request to denounce [target]. Agreeing denounces them; refusing
     *  makes [civ] denounce the human if the original's IsFriendDenounceRefusalUnacceptable says so.
     */
    fun onHumanAnsweredDenounceRequest(civ: Civilization, human: Civilization, target: Civilization, agreed: Boolean) {
        if (agreed) {
            human.getDiplomacyManager(target)?.takeIf { !target.isDefeated() && !human.isAtWarWith(target) }?.denounce()
        } else if (isFriendDenounceRefusalUnacceptable(civ, target)) civ.getDiplomacyManager(human)?.denounce()
    }

    /** CvDiplomacyAI::IsFriendDenounceRefusalUnacceptable */
    fun isFriendDenounceRefusalUnacceptable(civ: Civilization, target: Civilization): Boolean {
        val desire = denounceWeight(civ, target, bias = false) + civ.ai.diplomacyValue(DiplomacyPersonality.Neediness) -
            civ.ai.diplomacyValue(DiplomacyPersonality.Forgiveness)
        return desire >= 23
    }
    //endregion

    //region Demands and requests (CvDiplomacyRequests)

    /** CvDiplomacyAI::IsPlayerDemandAttractive */
    fun isPlayerDemandAttractive(civ: Civilization, other: Civilization): Boolean {
        val ai = civ.ai.diplomacy
        if (ai.approachTowards(other) != MajorCivApproach.Hostile) return false
        if (DiplomacyTurn.proximity(civ, other) < DiplomacyTurn.PROXIMITY_FAR) return false
        val target = WarStateAi.targetValue(civ, other)
        if (target == null || target < TargetValue.Average) return false
        if (turnsSinceStatement(civ, ai.memoryOf(other), DiploStatement.Demand) < 40) return false
        if (ai.memories.values.any { it.warGoal == WarGoal.Prepare }) return false
        if (civ.getCivsAtWarWith().isNotEmpty()) return false
        val ourCapital = civ.getCapital(firstCityIfNoCapital = false) ?: return false
        val theirCapital = other.getCapital(firstCityIfNoCapital = false) ?: return false
        return ourCapital.getCenterTile().getContinent() == theirCapital.getCenterTile().getContinent()
    }

    /** CvDiplomacyAI::DoUpdateDemands, DoStartDemandProcess, DoCancelHaltDemandProcess, DoTestDemandReady */
    private fun updateDemands(civ: Civilization, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val random = civ.ai.random("DiplomacyAI.DemandTarget")
        val candidates = DiplomacyTurn.validPlayers(civ).filter { it.isMajorCiv && isPlayerDemandAttractive(civ, it) }
            .map { it to random.nextInt(100) }.sortedByDescending { it.second }
        var cancel = candidates.isEmpty()
        if (candidates.isNotEmpty() && ai.demandTarget == null &&
            rules.define(AiDefine.DEMAND_RAND) > civ.ai.random("DiplomacyAI.DemandRoll").nextInt(100)) {
            val target = candidates[0].first
            ai.demandTarget = target.civID
            // CvDiplomacyAI::DoStartDemandProcess: the show of force that puts units near them
            val memory = ai.memoryOf(target)
            if (!civ.ai.operations.hasAnyOffensiveOperationsAgainst(target) && !memory.musteringForAttack &&
                !civ.isAtWarWith(target) && civ.getDiplomacyManager(target)?.canDeclareWar() == true) {
                MilitaryOperations.requestShowOfForce(civ, target)
                memory.warGoal = WarGoal.Demand
            }
        }
        val target = ai.demandTarget?.let { civ.gameInfo.getCivilizationOrNull(it) }
        if (target != null && !isPlayerDemandAttractive(civ, target)) cancel = true
        if (cancel && target != null) {
            if (ai.memoryOf(target).warGoal == WarGoal.Demand) {
                ai.memoryOf(target).warGoal = null
                // Get rid of the operation to put units near them
                civ.ai.operations.firstOffensiveOperationAgainst(target)?.let {
                    civ.ai.operations.kill(it, OperationAbortReason.DIPLO_OPINION_CHANGE)
                    ai.memoryOf(target).musteringForAttack = false
                }
            }
            ai.demandTarget = null
            ai.demandReady = false
        }
        // CvDiplomacyAI::DoTestDemandReady: the show of force is 85% of the way, or has mustered
        val current = ai.demandTarget?.let { civ.gameInfo.getCivilizationOrNull(it) } ?: return
        val operation = civ.ai.operations.firstOffensiveOperationAgainst(current)
        if (ai.memoryOf(current).warGoal == WarGoal.Demand && operation != null && !civ.isAtWarWith(current) &&
            (ai.memoryOf(current).musteringForAttack || AiOperationLogic.percentFromMusterPointToTarget(civ, operation) >= WarAi.PERCENT_TO_DECLARE_WAR)) {
            ai.memoryOf(current).musteringForAttack = false
            ai.demandReady = true
        }
    }

    /**
     *  CvDiplomacyAI::DoMakeDemand with CvDealAI::IsMakeDemand: tribute in gold (at most 200 + 150 × their era), a demand only if the gold
     *  can be traded at all: CvDeal::IsPossibleToTradeItem allows lump gold only between civilizations with a mutual Declaration of
     *  Friendship ([TradeEvaluation.hasMutualDeclarationOfFriendship]), so no demand is made on anyone the AI is not friends with.
     *  An AI always gives in (CvDiplomacyAI::DoSendStatementToPlayer); a human gets a trade request that is a demand
     *  ([TradeRequest.isDemand]) and answers it on the trade popup, a refusal ending in [onHumanRefusedDemand]. Nothing is stood down here:
     *  the Demand statement makes the target unattractive for 40 turns, so the next [updateDemands] ends the process
     *  (DoCancelHaltDemandProcess) and with it the show of force; without a demand the process runs while the approach stays hostile.
     */
    private fun doMakeDemand(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        val ai = civ.ai.diplomacy
        if (ai.demandTarget != other.civID || !ai.demandReady) return false
        if (!TradeEvaluation.hasMutualDeclarationOfFriendship(civ, other)) return false
        val gold = minOf(other.gold, 200 + other.getAiEraNumber() * 150)
        if (gold <= 0) return false
        if (turnsSinceStatement(civ, ai.memoryOf(other), DiploStatement.Demand) < 40) return false
        val tribute = TradeOffer(Constants.flatGold, TradeOfferType.Gold, gold, civ.gameInfo.speed)
        if (other.isHuman()) {
            if (other.tradeRequests.any { it.requestingCiv == civ.civID }) return false
            sendStatement(civ, other, DiploStatement.Demand)
            val trade = Trade()
            trade.ourOffers.add(tribute)
            other.tradeRequests.add(TradeRequest(civ.civID, trade, isDemand = true))
            return true
        }
        sendStatement(civ, other, DiploStatement.Demand)
        val tradeLogic = TradeLogic(other, civ)
        tradeLogic.currentTrade.ourOffers.add(tribute)
        tradeLogic.acceptTrade()
        onDemandAccepted(civ, other)
        return true
    }

    /**
     *  CvDiplomacyAI::DoDemandMade (accepted) and SetNumConsecutiveDemandsTheyAccepted (Vox Populi): [payer] gave in to the tribute
     *  [demander] asked: the payer counts one more demand of theirs (ChangeNumDemandsMade, stamping the turn), the demander keeps the turn
     */
    fun onDemandAccepted(demander: Civilization, payer: Civilization) {
        if (demander.isMajorCiv) demander.ai.diplomacy.memoryOf(payer).demandAcceptedTurn = demander.gameInfo.turns
        if (payer.isMajorCiv && !payer.isHuman()) {
            val memory = payer.ai.diplomacy.memoryOf(demander)
            memory.numDemandsMade++
            memory.demandMadeTurn = payer.gameInfo.turns
        }
    }

    /**
     *  CvDiplomacyAI::DoFromUIDiploEvent(FROM_UI_DIPLO_EVENT_DEMAND_HUMAN_REFUSAL): [human] refused the tribute [civ] demanded. With a chance
     *  of one in two [civ] declares war and requests a basic attack on them, willing to build 3 of its units.
     */
    fun onHumanRefusedDemand(civ: Civilization, human: Civilization) {
        civ.ai.diplomacy.memoryOf(human).demandAcceptedTurn = -1
        timeThis("AI.DemandRefusal") {
            if (civ.ai.random("DiplomacyAI.DemandRefusal").nextInt(100) >= 50) return@timeThis
            WarAi.declareWar(civ, human)
            MilitaryOperations.requestBasicAttack(civ, human, 3)
        }
    }

    /** CvDiplomacyAI::DoRequest / IsMakeRequest: a friend we show friendship to may be asked for a luxury or gold; AIs always give in */
    private fun doRequest(civ: Civilization, other: Civilization, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        if (turnsSinceStatement(civ, memory, DiploStatement.Request) < 60 || turnsSinceStatement(civ, memory, DiploStatement.RequestRandFailed) < 15) return
        if (ai.approachTowards(other, hideTrueFeelings = true) != MajorCivApproach.Friendly || !OpinionAi.isDoFAccepted(civ, other)) return
        val request = luxuryRequest(civ, other) ?: goldRequest(civ, other) ?: return
        val roll = civ.ai.random("DiplomacyAI.Request" + other.civID).nextInt(100) + request.second
        if (roll < 67) {
            logStatement(civ, other, DiploStatement.RequestRandFailed)
            return
        }
        if (other.isHuman()) return  // the help request to humans has no Unciv counterpart
        sendStatement(civ, other, DiploStatement.Request)
        val tradeLogic = TradeLogic(other, civ)
        tradeLogic.currentTrade.ourOffers.add(request.first)
        // CvDealAI::DoAcceptedDeal for the asker: the help is remembered (negative assist = good)
        val value = DealAi.getDealValue(civ, other, tradeLogic.currentTrade.reverse(), false).total
        tradeLogic.acceptTrade()
        civ.ai.diplomacy.changeRecentAssistValue(other, -value)
    }

    /** CvDiplomacyAI::IsLuxuryRequest: they have a luxury twice and we have no spare one to trade */
    private fun luxuryRequest(civ: Civilization, other: Civilization): Pair<TradeOffer, Int>? {
        val theirs = other.getPerTurnResourcesWithOriginsForTrade()
            .firstOrNull { it.resource.resourceType == ResourceType.Luxury && it.origin == Constants.tradable && it.amount >= 2 &&
                civ.getResourceAmount(it.resource) == 0 } ?: return null
        val ours = civ.getPerTurnResourcesWithOriginsForTrade()
            .any { it.resource.resourceType == ResourceType.Luxury && it.origin == Constants.tradable && it.amount >= 2 &&
                other.getResourceAmount(it.resource) == 0 }
        if (ours) return null
        val bias = if (civ.isEmpireUnhappy()) 25 else 0
        return TradeOffer(theirs.resource.name, TradeOfferType.Luxury_Resource, 1, civ.gameInfo.speed) to bias
    }

    /** CvDiplomacyAI::IsGoldRequest: we are poor, they are doing well */
    private fun goldRequest(civ: Civilization, other: Civilization): Pair<TradeOffer, Int>? {
        val ourGpt = civ.stats.statsForNextTurn.gold.toInt()
        val ourExpenses = expensesOf(civ)
        if (ourExpenses == 0) return null
        if (civ.gold > 100) return null
        if ((ourGpt + ourExpenses) * 100 / ourExpenses > 135) return null
        val theirGpt = other.stats.statsForNextTurn.gold.toInt()
        val theirExpenses = expensesOf(other)
        if (theirExpenses != 0) {
            if ((theirGpt + theirExpenses) * 100 / theirExpenses < 135) return null
        } else if (theirGpt <= ourGpt) return null
        val bias = if (ourGpt < 0) 25 else 0
        var goldToAskFor = theirGpt * civ.gameInfo.speed.dealDuration / 5
        if (goldToAskFor > other.gold) {
            val gpt = maxOf(1, theirGpt / 6)
            return TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, gpt, civ.gameInfo.speed) to bias
        }
        if (goldToAskFor <= 0) return null
        return TradeOffer(Constants.flatGold, TradeOfferType.Gold, goldToAskFor, civ.gameInfo.speed) to bias
    }

    /** CvTreasury::CalculateInflatedCosts: building and unit maintenance (the negative gold entries of the next turn's stats) */
    private fun expensesOf(civ: Civilization): Int =
        -civ.stats.getStatMapForNextTurn().values.sumOf { if (it.gold < 0) it.gold.toDouble() else 0.0 }.toInt()

    //endregion

    //region Planning exchanges and warnings

    /** CvTeam::IsResearchAgreementTradingAllowedWithTeam: the technology and an embassy */
    private fun isResearchAgreementTradingAllowed(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacyFunctions.canSignResearchAgreement() && civ.diplomacyFunctions.meetsEmbassyRequirementFor(other)

    /**
     *  CvDiplomacyAI::DoUpdatePlanningExchanges: the friendships we want and want to end ([FriendshipPlanningAi.updateFriendships]),
     *  then (BNW's part, still) a research agreement with everyone we may sign one with, unless hostile
     */
    private fun updatePlanningExchanges(civ: Civilization) {
        FriendshipPlanningAi.updateFriendships(civ)
        val ai = civ.ai.diplomacy
        for (other in DiplomacyTurn.validPlayers(civ)) {
            if (!other.isMajorCiv) continue
            val memory = ai.memoryOf(other)
            val allowed = isResearchAgreementTradingAllowed(civ, other) || isResearchAgreementTradingAllowed(other, civ)
            if (civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.ResearchAgreement) != true && !memory.wantsResearchAgreement && allowed) {
                memory.wantsResearchAgreement = true
                // CvDiplomacyAI::DoAddWantsResearchAgreementWithPlayer: start saving for it
                civ.ai.purchaseSavings.startSaveForPurchase(PurchaseType.MajorCivTrade, civ.diplomacyFunctions.getResearchAgreementCost(other), 1)
            }
            if (memory.wantsResearchAgreement && (ai.approachTowards(other, hideTrueFeelings = true) == MajorCivApproach.Hostile || !allowed)) {
                memory.wantsResearchAgreement = false
                civ.ai.purchaseSavings.cancelSaveForPurchase(PurchaseType.MajorCivTrade)
            }
        }
    }

    /**
     *  CvDiplomacyAI::DoAggressiveMilitaryStatement towards humans: their military posture near us became High (or Medium from None).
     *  The warning is Unciv's [Demand.DoNotAttackUs] request.
     */
    fun checkMilitaryPresenceNearBorder(civ: Civilization) {
        val ai = civ.ai.diplomacy
        for (other in DiplomacyTurn.validPlayers(civ)) {
            if (!other.isMajorCiv || !other.isHuman()) continue
            val diplomacy = civ.getDiplomacyManager(other) ?: continue
            if (other.getDiplomacyManager(civ)?.canDeclareWar() != true) continue
            val memory = ai.memoryOf(other)
            if (memory.resurrectedUs) continue
            val send = (memory.militaryPosture >= AggressivePosture.High && memory.lastTurnMilitaryPosture < AggressivePosture.High) ||
                (memory.militaryPosture >= AggressivePosture.Medium && memory.lastTurnMilitaryPosture <= AggressivePosture.None)
            if (!send || OpinionAi.isDoFAccepted(civ, other)) continue
            if (civ.gameInfo.civilizations.any { third -> third != other && third != civ && other.isAtWarWith(third) &&
                    (civ.isAtWarWith(third) || DiplomacyTurn.proximity(civ, third) == DiplomacyTurn.PROXIMITY_NEIGHBORS) }) continue
            if (diplomacy.hasFlag(DiplomacyFlags.AgreedToNotAttackUs)) continue
            if (turnsSinceStatement(civ, memory, DiploStatement.AggressiveMilitaryWarning) < 40) continue
            sendStatement(civ, other, DiploStatement.AggressiveMilitaryWarning)
            diplomacy.setFlag(DiplomacyFlags.MilitaryPresenceNearBorderOrAttackedUsDespitePromise, 30, true)
        }
    }
    //endregion
}
