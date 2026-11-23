package com.unciv.logic.trade

import com.unciv.Constants
import com.unciv.logic.automation.civilization.DealAi
import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.civilization.VoteCommitmentItem
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import yairm210.purity.annotations.Readonly

/** Whether a trade can be made (CvDeal::IsPossibleToTradeItem) and whether an AI accepts it (through [DealAi]) */
class TradeEvaluation {

    @Readonly
    fun isTradeValid(trade: Trade, offerer: Civilization, tradePartner: Civilization): Boolean {

        // Edge case time! Guess what happens if you offer a peace agreement to the AI for all their cities except for the capital,
        // and then capture their capital THAT SAME TURN? It can agree, leading to the civilization getting instantly destroyed!
        // If a civ has never owned an original capital, which means it has not settled the first city yet, 
        // it shouldn't be forbidden to trade with other civs owing to cities.size == 0.
        if ((offerer.hasEverOwnedOriginalCapital && trade.ourOffers.count { it.type == TradeOfferType.City } == offerer.cities.size)
            || (tradePartner.hasEverOwnedOriginalCapital && trade.theirOffers.count { it.type == TradeOfferType.City } == tradePartner.cities.size)) {
            return false
        }

        // CvDeal::IsPossibleToTradeItem: a deal cannot hold a second vote commitment of the same civilization
        if (trade.ourOffers.count { it.type == TradeOfferType.VoteCommitment } > 1
            || trade.theirOffers.count { it.type == TradeOfferType.VoteCommitment } > 1) return false
        
        val ourWorks = trade.ourOffers.filter { it.type == TradeOfferType.GreatWork }
        val theirWorks = trade.theirOffers.filter { it.type == TradeOfferType.GreatWork }
        if (ourWorks.isNotEmpty() || theirWorks.isNotEmpty()) {
            if (!offerer.isMajorCiv || !tradePartner.isMajorCiv || offerer.isAtWarWith(tradePartner)
                || ourWorks.size != 1 || theirWorks.size != 1
                || trade.ourOffers.size != 1 || trade.theirOffers.size != 1
                || ourWorks[0].amount != 1 || theirWorks[0].amount != 1) return false
            val ourSlot = GreatWorkFunctions.findTradeSlot(offerer, ourWorks[0].name) ?: return false
            val theirSlot = GreatWorkFunctions.findTradeSlot(tradePartner, theirWorks[0].name) ?: return false
            if (!GreatWorkFunctions.canExchange(ourSlot, theirSlot)) return false
        }
        // No way to tell who offers what in isOfferValid()
        val embassyOffer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = offerer.gameInfo.speed)
        val theirDiploManager = tradePartner.getDiplomacyManager(offerer)!!
        val ourDiploManager = offerer.getDiplomacyManager(tradePartner)!!

        if (trade.ourOffers.contains(embassyOffer)
            && (offerer.getCapital() == null
            || theirDiploManager.hasModifier(DiplomaticModifiers.EstablishedEmbassy)
            || theirDiploManager.hasModifier(DiplomaticModifiers.SharedEmbassies)))
            return false

        if (trade.theirOffers.contains(embassyOffer)
            && (tradePartner.getCapital() == null
            || ourDiploManager.hasModifier(DiplomaticModifiers.EstablishedEmbassy)
            || ourDiploManager.hasModifier(DiplomaticModifiers.SharedEmbassies)))
            return false

        // One side can hand the other the gold for a research agreement in the same trade
        val goldFromThem = trade.theirOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount }
        val goldFromUs = trade.ourOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount }
        for (offer in trade.ourOffers)
            if (!isOfferValid(offer, offerer, tradePartner, trade.isPeaceTreaty(), goldFromThem - goldFromUs)
                || blocksTemporaryForPermanent(offer, offerer, tradePartner, trade.theirOffers)) {
                return false
            }            

        for (offer in trade.theirOffers)
            if (!isOfferValid(offer, tradePartner, offerer, trade.isPeaceTreaty(), goldFromUs - goldFromThem)
                || blocksTemporaryForPermanent(offer, tradePartner, offerer, trade.ourOffers)) {
                return false
            }
        return true
    }

    @Readonly @Suppress("purity") // the vote commitment check reads the league AI
    private fun isOfferValid(tradeOffer: TradeOffer, offerer: Civilization, tradePartner: Civilization, isPeaceDeal: Boolean, netGoldReceived: Int): Boolean {

        @Readonly
        fun hasResource(tradeOffer: TradeOffer): Boolean {
            // CvDeal::IsPossibleToTradeItem, V:CvDealClasses.cpp:546-548: nothing is traded that the technology of either side made obsolete
            val resource = offerer.gameInfo.ruleset.tileResources[tradeOffer.name]
            if (resource != null && (offerer.tech.isObsolete(resource) || tradePartner.tech.isObsolete(resource))) return false
            val resourcesByName = offerer.getCivResourcesByName()
            return resourcesByName.containsKey(tradeOffer.name) && resourcesByName[tradeOffer.name]!! >= tradeOffer.amount
        }

        return when (tradeOffer.type) {
            TradeOfferType.Embassy -> true // Already checked
            // CvDeal::IsPossibleToTradeItem: lump gold only between civs with a mutual Declaration of Friendship, except in peace deals
            TradeOfferType.Gold -> (isPeaceDeal || hasMutualDeclarationOfFriendship(offerer, tradePartner)) && tradeOffer.amount <= offerer.gold
            TradeOfferType.Gold_Per_Turn -> tradeOffer.amount <= offerer.stats.statsForNextTurn.gold
            TradeOfferType.Treaty -> {
                // Current automation should prevent these from being offered anyway, 
                //   these are a safeguard against future automation changes 
                when (tradeOffer.name) {
                    Constants.peaceTreaty -> offerer.isAtWarWith(tradePartner)
                    Constants.researchAgreement -> !offerer.getDiplomacyManager(tradePartner)!!.hasFlag(DiplomacyFlags.ResearchAgreement) && offerer.gold + netGoldReceived >= tradeOffer.amount
                    Constants.defensivePact -> !offerer.getDiplomacyManager(tradePartner)!!.hasFlag(DiplomacyFlags.DefensivePact)
                    else -> true // potentional future treaties
                }
            }
            TradeOfferType.Agreement -> true
            TradeOfferType.Luxury_Resource -> hasResource(tradeOffer) && offerer.gameInfo.league?.isLuxuryBanned(tradeOffer.name) != true // World Congress Ban Luxury (LR 4.3.3)
            TradeOfferType.Strategic_Resource -> hasResource(tradeOffer)
            TradeOfferType.Stockpiled_Resource -> hasResource(tradeOffer)
            TradeOfferType.Technology -> offerer.tech.isResearched(tradeOffer.name) &&
                tradeOffer.name !in offerer.tech.noTradeTechnologies
            TradeOfferType.Introduction -> !tradePartner.knows(tradeOffer.name) // You can't introduce them to someone they already know!
            TradeOfferType.WarDeclaration -> offerer.getDiplomacyManager(tradeOffer.name)!!.canDeclareWar()
            TradeOfferType.PeaceProposal -> offerer.isAtWarWith(offerer.gameInfo.getCivilization(tradeOffer.name))
            TradeOfferType.City -> offerer.cities.any { it.id == tradeOffer.name && !isFoundedCityBlocked(it, offerer, tradePartner, isPeaceDeal) }
            TradeOfferType.GreatWork -> tradeOffer.amount == 1 &&
                GreatWorkFunctions.findTradeSlot(offerer, tradeOffer.name) != null
            TradeOfferType.VoteCommitment -> VoteCommitmentItem.parse(tradeOffer)?.let {
                LeagueAutomation.isVoteCommitmentPossible(offerer, tradePartner, it.proposalId, it.choice, tradeOffer.amount, it.repeal)
            } == true
        }
    }

    /**
     *  Whether [evaluator] (an AI) accepts [trade] from [tradePartner] (`ourOffers` = what [evaluator] gives): a human's deal by
     *  CvDealAI::DoHumanOfferDealToThisAI, another AI's by the rule CvDealAI::DoEqualizeDealWithAI applies to AI deals.
     */
    fun isTradeAcceptable(trade: Trade, evaluator: Civilization, tradePartner: Civilization): Boolean {
        if (!isTradeValid(trade, evaluator, tradePartner)) return false
        return if (tradePartner.isHuman()) DealAi.respondToHumanDeal(evaluator, tradePartner, trade) == DealAi.DealResponse.Accepted
            else DealAi.isAcceptableBetweenAIs(evaluator, tradePartner, trade)
    }

    /**
     *  CvDeal::IsPossibleToTradeItem for TRADE_ITEM_THIRD_PARTY_PEACE: whether [civInfo] can offer [tradePartner] to make peace with
     *  [thirdCiv]. A city-state whose ally is at war with [civInfo] blocks it, unless that ally is [tradePartner]. A major must be an AI
     *  that wants peace with [civInfo], and [civInfo] must be an AI whose war goal against it is Damage or Peace.
     */
    @Readonly @Suppress("purity") // reads AI state
    fun isPeaceProposalEnabled(thirdCiv: Civilization, civInfo: Civilization, tradePartner: Civilization): Boolean {
        if (thirdCiv.isCityState) {
            val allyCiv = thirdCiv.allyCiv
            if (allyCiv != null && civInfo.isAtWarWith(allyCiv) && allyCiv != tradePartner) return false
            return true
        }
        if (thirdCiv.isHuman() || civInfo.isHuman()) return false
        val warGoal = civInfo.ai.diplomacy.warGoal(thirdCiv) ?: return false
        if (warGoal < WarGoal.Damage) return false
        return thirdCiv.ai.diplomacy.isWantsPeaceWith(civInfo)
    }

    companion object {
        /** Whether an item is permanent (gold, a city, a technology), temporary (gold per turn, resources, open borders, pacts, a war or peace with a third civilization, an embassy)
         *  or neither (CvDeal::BlockTemporaryForPermanentTrade's lists, V:CvDealClasses.cpp:1368-1470); a peace treaty is irrelevant to the rule */
        @Readonly
        fun permanence(offer: TradeOffer): Permanence? = when (offer.type) {
            TradeOfferType.Gold, TradeOfferType.City, TradeOfferType.Technology -> Permanence.Permanent
            TradeOfferType.Gold_Per_Turn, TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource, TradeOfferType.Agreement,
            TradeOfferType.Embassy, TradeOfferType.WarDeclaration, TradeOfferType.PeaceProposal -> Permanence.Temporary
            TradeOfferType.Treaty -> if (offer.name == Constants.peaceTreaty) null else Permanence.Temporary
            else -> null
        }

        enum class Permanence { Permanent, Temporary }

        /**
         * CvDeal::BlockTemporaryForPermanentTrade (V:CvDealClasses.cpp:1368-1470), with [com.unciv.models.ModConstants.dealsBlockTemporaryForPermanent]:
         * whether [offer], which [from] gives [to], cannot be in a deal in which [to] gives [receiverGives]: a temporary item is not given for a permanent one and
         * a permanent item not for a temporary one. Humans deal with each other as they like, and with `dealHumanPermanentForAiTemporary` a human may give the
         * permanent item for what the AI gives temporarily (MOD_DEALAI_HUMAN_PERMANENT_FOR_AI_TEMPORARY).
         */
        @Readonly
        fun blocksTemporaryForPermanent(offer: TradeOffer, from: Civilization, to: Civilization, receiverGives: List<TradeOffer>): Boolean {
            val constants = from.gameInfo.ruleset.modOptions.constants
            if (!constants.dealsBlockTemporaryForPermanent) return false
            if (offer.type == TradeOfferType.PeaceProposal && from.isAtWarWith(to)) return false
            val fromHuman = from.isHuman()
            val noHumans = !fromHuman && !to.isHuman()
            if (fromHuman && to.isHuman()) return false
            val humanRule = constants.dealHumanPermanentForAiTemporary
            return when (permanence(offer)) {
                Permanence.Temporary ->
                    (!humanRule || fromHuman || noHumans) && receiverGives.any { permanence(it) == Permanence.Permanent }
                Permanence.Permanent ->
                    (!humanRule || !fromHuman || noHumans) && receiverGives.any {
                        permanence(it) == Permanence.Temporary && !(it.type == TradeOfferType.PeaceProposal && from.isAtWarWith(to))
                    }
                null -> false
            }
        }

        /**
         * CvDeal::IsPossibleToTradeItem for a city (V:CvDealClasses.cpp:644-653): outside a peace deal the founder does not sell a city it founded
         * ([com.unciv.models.ModConstants.dealNoPeacetimeSellingFoundedCities]), unless both are humans
         */
        @Readonly
        fun isFoundedCityBlocked(city: com.unciv.logic.city.City, seller: Civilization, buyer: Civilization, isPeaceDeal: Boolean): Boolean =
            seller.gameInfo.ruleset.modOptions.constants.dealNoPeacetimeSellingFoundedCities && !isPeaceDeal
                && !(seller.isHuman() && buyer.isHuman()) && city.foundingCivObject == seller

        /** CvDiplomacyAI::IsDoFAccepted both ways */
        @Readonly
        fun hasMutualDeclarationOfFriendship(civ: Civilization, other: Civilization): Boolean =
            civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
                && other.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
    }
}
