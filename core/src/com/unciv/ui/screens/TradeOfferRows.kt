package com.unciv.ui.screens

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeEvaluation.Companion.Permanence
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import yairm210.purity.annotations.Readonly

/**
 *  What the trade screens tell about the rules of the deals of slice T4 of `phaseE/vp-trade-plan.md` (CvDeal::BlockTemporaryForPermanentTrade, V:CvDealClasses.cpp:1368-1470, and
 *  the founder's city, 649): whether an item is permanent (gold, a city, a technology) or temporary (gold per turn, resources, open borders and the pacts, a war or peace with
 *  a third civilization, an embassy), and why a staged deal cannot be proposed. As plain data that the screens draw and tests read without a screen. Empty
 *  unless the ruleset's `dealsBlockTemporaryForPermanent` or `dealNoPeacetimeSellingFoundedCities` is on, so the screens of Brave New World's rules stay as they were.
 */
object TradeOfferRows {
    private const val PERMANENT = "Permanent item"
    private const val TEMPORARY = "Temporary item"
    private const val BLOCK_TEMPORARY = "A temporary item cannot be traded for a permanent one"
    private const val BLOCK_FOUNDED = "The founder of [city] cannot sell it in peacetime"

    private val Civilization.constants get() = gameInfo.ruleset.modOptions.constants

    /** Whether the screens say that an item is permanent or temporary: the rule that forbids trading one for the other is on */
    @Readonly
    fun marksPermanence(civ: Civilization): Boolean = civ.constants.dealsBlockTemporaryForPermanent

    /** The translatable note for [offer], null for an item that is neither (a peace treaty), or when the rules do not distinguish them */
    @Readonly
    fun permanenceNote(offer: TradeOffer, civ: Civilization): String? {
        if (!marksPermanence(civ)) return null
        return when (TradeEvaluation.permanence(offer, civ.constants.permanentVoteCommitments)) {
            Permanence.Permanent -> PERMANENT
            Permanence.Temporary -> TEMPORARY
            null -> null
        }
    }

    /**
     *  The reasons why [trade] between [ours] and [theirs] cannot be proposed as the rules of the deals say, the first of each kind: the items one side gives that the other
     *  side's items forbid (a temporary item for a permanent one), and a city a founder sells in peacetime. Empty for a deal the rules let through.
     */
    @Readonly
    fun blockReasons(trade: Trade, ours: Civilization, theirs: Civilization): List<String> {
        val reasons = ArrayList<String>()
        val ourBlocked = trade.ourOffers.any { TradeEvaluation.blocksTemporaryForPermanent(it, ours, theirs, trade.theirOffers) }
        val theirBlocked = trade.theirOffers.any { TradeEvaluation.blocksTemporaryForPermanent(it, theirs, ours, trade.ourOffers) }
        if (ourBlocked || theirBlocked) reasons.add(BLOCK_TEMPORARY)
        for ((giver, receiver, offers) in listOf(Triple(ours, theirs, trade.ourOffers), Triple(theirs, ours, trade.theirOffers)))
            for (offer in offers) {
                if (offer.type != TradeOfferType.City) continue
                val city = giver.cities.firstOrNull { it.id == offer.name } ?: continue
                if (TradeEvaluation.isFoundedCityBlocked(city, giver, receiver, trade.isPeaceTreaty())) reasons.add(BLOCK_FOUNDED.replace("[city]", "[${city.name}]"))
            }
        return reasons
    }
}
