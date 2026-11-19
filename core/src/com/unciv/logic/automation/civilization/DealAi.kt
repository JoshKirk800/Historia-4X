package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.PactPlanningAi
import com.unciv.logic.automation.diplomacy.PeaceTreatyType
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.automation.diplomacy.WarProjection
import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 *  The original's CvDealAI (CvDealAI.cpp): what a player thinks a deal is worth, how it balances a deal, and the deals it puts
 *  together (peace treaties, luxury, embassy, open borders and research agreement offers).
 *
 *  A deal is an Unciv [Trade] seen from [us]: `ourOffers` are the items we give, `theirOffers` the items we receive.
 *  Values are the original's "value points" (gold); there is no gold inflation term.
 *  Item kinds Unciv has and the original does not (Great Works, technologies, introductions, stockpiled resources) keep Unciv's
 *  own valuation, averaged like the others when an even value is asked.
 */
object DealAi {

    /** CvDealAI::GetDealPercentLeewayWithAI */
    const val LEEWAY_WITH_AI_PERCENT = 25
    /** CvDealAI::GetDealPercentLeewayWithHuman */
    const val LEEWAY_WITH_HUMAN_PERCENT = 10

    /** Value a deal item is given when the original refuses it outright */
    const val NEVER = 100000

    /** The three numbers CvDealAI::GetDealValue returns */
    class DealValue {
        var total = 0
        var imOffering = 0
        var theyreOffering = 0
    }

    private fun Civilization.define(define: AiDefine) = gameInfo.ruleset.ai.define(define)

    //region Valuation

    /**
     *  CvDealAI::GetDealValue: the sum of the item values, items we give counted negative. With [useEvenValue] each item is valued at
     *  the average of both players' valuations (deals between AIs).
     */
    fun getDealValue(us: Civilization, them: Civilization, trade: Trade, useEvenValue: Boolean, result: DealValue = DealValue()): DealValue {
        var total = 0L
        var imOffering = 0L
        var theyreOffering = 0L
        for (offer in trade.ourOffers) {
            val value = -getTradeItemValue(us, them, offer, true, useEvenValue, trade).toLong()
            total += value
            if (value < 0) imOffering -= value else theyreOffering += value
        }
        for (offer in trade.theirOffers) {
            val value = getTradeItemValue(us, them, offer, false, useEvenValue, trade).toLong()
            total += value
            if (value < 0) imOffering -= value else theyreOffering += value
        }
        result.total = clamp(total)
        result.imOffering = clamp(imOffering)
        result.theyreOffering = clamp(theyreOffering)
        return result
    }

    private fun clamp(value: Long) = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /** CvDealAI::GetTradeItemValue: what one item is worth to [us]; [fromMe] = we give it. [trade] is the deal seen from [us] */
    fun getTradeItemValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean, useEvenValue: Boolean, trade: Trade): Int =
        when (offer.type) {
            TradeOfferType.Gold -> getGoldForValueExchange(us, them, offer.amount, false, fromMe, useEvenValue, false)
            TradeOfferType.Gold_Per_Turn -> getGptForValueExchange(us, them, offer.amount, false, offer.duration, fromMe, useEvenValue, false)
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> {
                val resource = us.gameInfo.ruleset.tileResources[offer.name]
                if (resource == null) 0 else getResourceValue(us, them, resource, offer.amount, offer.duration, fromMe)
            }
            TradeOfferType.City -> {
                val city = cityById(if (fromMe) us else them, offer.name)
                // The buyer's price whoever asks; a city the buyer will not take at any price spoils the deal for the buyer (INT_MAX)
                if (city == null) 0 else getCityValueForDeal(city, if (fromMe) them else us) ?: if (fromMe) NEVER else -NEVER
            }
            TradeOfferType.Embassy -> getEmbassyValue(us, them, fromMe, useEvenValue)
            TradeOfferType.Agreement -> if (offer.name == Constants.openBorders) getOpenBordersValue(us, them, fromMe, useEvenValue) else 0
            TradeOfferType.Treaty -> when (offer.name) {
                Constants.defensivePact -> getDefensivePactValue(us, them, fromMe, useEvenValue)
                Constants.researchAgreement -> getResearchAgreementValue(us, them, fromMe, useEvenValue)
                else -> 0 // CvDealAI::GetPeaceTreatyValue: peace itself is worth nothing
            }
            TradeOfferType.PeaceProposal -> getThirdPartyPeaceValue(us, them, us.gameInfo.getCivilization(offer.name), fromMe)
            TradeOfferType.WarDeclaration -> getThirdPartyWarValue(us, them, us.gameInfo.getCivilization(offer.name), fromMe)
            TradeOfferType.VoteCommitment -> getVoteCommitmentValue(us, them, offer, fromMe, useEvenValue)
            TradeOfferType.GreatWork, TradeOfferType.Technology, TradeOfferType.Introduction, TradeOfferType.Stockpiled_Resource ->
                getUncivItemValue(us, them, offer, fromMe, useEvenValue, trade)
        }

    private fun cityById(owner: Civilization, id: String): City? = owner.cities.firstOrNull { it.id == id }

    private fun approachGoldModifier(approach: MajorCivApproach) = when (approach) {
        MajorCivApproach.Hostile -> 150
        MajorCivApproach.Guarded -> 110
        else -> 100
    }

    private fun opinionGoldModifier(opinion: MajorCivOpinion) = when (opinion) {
        MajorCivOpinion.Competitor -> 115
        MajorCivOpinion.Enemy -> 140
        MajorCivOpinion.Unforgivable -> 200
        else -> 100
    }

    private fun applyModifier(value: Long, modifier: Int, inverse: Boolean): Long =
        if (inverse) value * 100 / modifier else value * modifier / 100

    /**
     *  CvDealAI::GetGoldForForValueExchange: the value of [goldOrValue] gold, or with [numGoldFromValue] the gold worth [goldOrValue].
     *  Gold we give is marked up by our approach (Hostile 150%, Guarded 110%) and opinion (Competitor 115%, Enemy 140%, Unforgivable 200%).
     */
    fun getGoldForValueExchange(us: Civilization, them: Civilization, goldOrValue: Int, numGoldFromValue: Boolean, fromMe: Boolean,
                                useEvenValue: Boolean, roundUp: Boolean): Int {
        val percent = us.define(AiDefine.EACH_GOLD_VALUE_PERCENT)
        val multiplier = if (numGoldFromValue) 100 else percent
        val divisor = if (numGoldFromValue) (if (percent == 0) 1 else percent) else 100
        var value = goldOrValue.toLong() * multiplier
        if (fromMe) {
            value = applyModifier(value, approachGoldModifier(us.ai.diplomacy.approachTowards(them, true)), numGoldFromValue)
            value = applyModifier(value, opinionGoldModifier(us.ai.diplomacy.opinionOf(them)), numGoldFromValue)
        }
        if (roundUp) value += 99
        value /= divisor
        if (useEvenValue) {
            value += getGoldForValueExchange(them, us, goldOrValue, numGoldFromValue, !fromMe, false, roundUp)
            value /= 2
        }
        return clamp(value)
    }

    /**
     *  CvDealAI::GetGPTforForValueExchange: GPT × turns × EACH_GOLD_PER_TURN_VALUE_PERCENT, or the reverse with [numGptFromValue].
     *  GPT we give is marked up 130% and by the same approach and opinion modifiers as gold.
     */
    fun getGptForValueExchange(us: Civilization, them: Civilization, gptOrValue: Int, numGptFromValue: Boolean, numTurns: Int, fromMe: Boolean,
                               useEvenValue: Boolean, roundUp: Boolean): Int {
        val percent = us.define(AiDefine.EACH_GOLD_PER_TURN_VALUE_PERCENT)
        val turns = max(1, numTurns)
        val preMultiplier = if (numGptFromValue) 1 else turns
        val multiplier = if (numGptFromValue) 100 else percent
        val divisor = if (numGptFromValue) (if (percent == 0) 1 else percent) else 100
        val postDivider = if (numGptFromValue) turns else 1
        var value = gptOrValue.toLong() * preMultiplier * multiplier
        if (fromMe) {
            value = applyModifier(value, 130, numGptFromValue)
            value = applyModifier(value, approachGoldModifier(us.ai.diplomacy.approachTowards(them, true)), numGptFromValue)
            value = applyModifier(value, opinionGoldModifier(us.ai.diplomacy.opinionOf(them)), numGptFromValue)
        }
        if (roundUp) value += 99
        value /= divisor
        value /= postDivider
        if (useEvenValue) {
            value += getGptForValueExchange(them, us, gptOrValue, numGptFromValue, numTurns, !fromMe, false, roundUp)
            value /= 2
        }
        return clamp(value)
    }

    /** CvPlayer::getNumResourceAvailable: the civ's net amount of [resource]; without [includeImport] what it has from its own lands */
    fun numResourceAvailable(civ: Civilization, resource: TileResource, includeImport: Boolean): Int {
        if (includeImport) return civ.getResourceAmount(resource)
        var amount = 0
        for (supply in civ.detailedCivResources)
            if (supply.resource == resource && !supply.isCityStateOrTradeOrigin()) amount += supply.amount
        return amount
    }

    /**
     *  CvDealAI::GetResourceValue (Vox Populi, Community Patch part): [DealResourceValues.luxuryValue] or
     *  [DealResourceValues.strategicValue], with the receiver's net gold; a bonus resource is worth nothing. What the original returns as
     *  INT_MAX is [NEVER] for the player who would give it and -[NEVER] for the one who would get it. There is no even value; the
     *  approach modifiers of [getTradeItemValue] take the place of the opinion scaling of Brave New World.
     */
    fun getResourceValue(us: Civilization, them: Civilization, resource: TileResource, quantity: Int, numTurns: Int, fromMe: Boolean): Int {
        val netGoldOfReceiver = goldRate(if (fromMe) them else us)
        val value = when (resource.resourceType) {
            ResourceType.Luxury -> DealResourceValues.luxuryValue(us, them, resource, numTurns, fromMe, netGoldOfReceiver)
            ResourceType.Strategic -> DealResourceValues.strategicValue(us, them, resource, quantity, numTurns, fromMe, netGoldOfReceiver)
            ResourceType.Bonus -> 0
        }
        return value ?: if (fromMe) NEVER else -NEVER
    }

    /**
     *  CvDealAI::GetCityValueForDeal (Vox Populi, Community Patch part): what [assumedOwner] would pay for [city], from the buyer's side
     *  only (the original's value is the same whoever asks). Null stands for INT_MAX: the city is not for sale to that buyer.
     *
     *  15000, plus a quarter of the city's economic value ([AttackTargetsAi.economicValue] for the buyer) above 1000, doubled when the city
     *  makes more per citizen than half of the buyer's other cities; plus PLOT_BASE_COST x 10 per tile the city owns beyond its first ring,
     *  and x 50 per such tile that borders the buyer. A city bordering the buyer's land loses nothing; one that does not loses 5 plot costs
     *  per tile of distance to the buyer's closest city, at most half. Minus 30 plot costs per unhappy citizen, +20% when the buyer founded
     *  it and +20% when the buyer owns it, x70% a puppet, x50% a city being razed, +11% per world wonder (national wonders too when the
     *  buyer owns it). The price is at least 729 per citizen.
     *
     *  Never for sale: a capital (unless [forCityComparison]); outside a peace treaty (the buyer at war with the owner) a city the buyer
     *  does not own or found when the buyer's empire is unhappy; a city the buyer owns when it has fewer than 5 cities, or is an original
     *  capital or a holy city, or was founded or taken less than 42-46 turns ago; for a city the buyer does not own, when its owner counts
     *  the buyer's warmonger threat severe or worse (major: x2). A city the buyer does not own and does not border, further from the buyer's
     *  closest city than 1.5 times from the seller's closest, is refused unless the buyer is about to take it: two or more of its military
     *  units next to the city and the city below 10% + war score/10 of its hit points. With [onlyCitiesAboutToBeConquered] only such a city
     *  is priced.
     *
     *  Not ported: the city traded-before flag (CvCity::IsTraded: no such record in a city; Unciv's turnAcquired stands in for the
     *  founding turn, so a city taken or traded in the last 42-46 turns also counts as new); CvDiplomacyAI::IsTryingToLiberate (not in this
     *  base; it only refuses an AI buying a city it means to liberate, and no AI proposes to buy a city); the founding turn's random 0-4 is
     *  seeded by the economic value as the original does but with Kotlin's generator.
     */
    fun getCityValueForDeal(city: City, assumedOwner: Civilization, onlyCitiesAboutToBeConquered: Boolean = false, warScore: Int = 0,
                            forCityComparison: Boolean = false): Int? {
        if (!forCityComparison && city.isCapital()) return null
        val seller = city.civ
        val peaceTreatyTrade = assumedOwner.isAtWarWith(seller)
        val buyerOwns = seller == assumedOwner
        val buyerFounded = city.foundingCivObject == assumedOwner
        if (!peaceTreatyTrade && !buyerOwns && !buyerFounded && EconomicMilitaryStrategyTests.isEmpireUnhappy(assumedOwner)) return null
        if (!peaceTreatyTrade && buyerOwns) {
            if (assumedOwner.cities.size < 5) return null
            if (city.isOriginalCapital || city.isHolyCity()) return null
        }

        var itemValue = 15000
        val economicValue = AttackTargetsAi.economicValue(city, assumedOwner)
        val economicValuePerPop = economicValue / max(1, city.population.population)
        itemValue += max(1, economicValue - 1000) / 4

        if (!peaceTreatyTrade && city.civ.gameInfo.turns - city.turnAcquired < 42 + Random(economicValue.toLong()).nextInt(5)) return null

        var betterThanCount = 0
        var minDistance = Int.MAX_VALUE
        val center = city.getCenterTile()
        for (other in assumedOwner.cities) {
            if (other == city) continue
            if (economicValuePerPop > AttackTargetsAi.economicValue(other, assumedOwner) / max(1, other.population.population)) betterThanCount++
            minDistance = min(minDistance, center.aerialDistanceTo(other.getCenterTile()))
        }
        if (betterThanCount > assumedOwner.cities.size / 2) itemValue *= 2

        // The city's tiles beyond the first ring, and the ones next to land of the buyer
        var newInternalBorderCount = 0
        var cityTiles = 0
        for (tile in city.getTiles()) {
            if (tile.aerialDistanceTo(center) !in 2..5) continue
            cityTiles++
            if (seller != assumedOwner && tile.neighbors.any { it.getOwner() == assumedOwner }) newInternalBorderCount++
        }

        val goldPerPlot = assumedOwner.define(AiDefine.PLOT_BASE_COST)
        itemValue += goldPerPlot * 10 * cityTiles
        if (newInternalBorderCount > 0) itemValue += goldPerPlot * 50 * newInternalBorderCount
        else if (!buyerOwns) {
            itemValue -=
                if (minDistance == Int.MAX_VALUE) itemValue / 2
                else min(itemValue / 2, goldPerPlot * 5 * minDistance)
        }

        itemValue -= max(0, city.population.population - city.cityStats.localHappiness.toInt()) * goldPerPlot * 30

        if (buyerFounded) itemValue = itemValue * 120 / 100
        if (buyerOwns) itemValue = itemValue * 120 / 100
        else if (!forCityComparison) {
            // Only a city the buyer borders, or is not much further from than the seller, or is about to take, can be held
            var minDistanceOldOwner = Int.MAX_VALUE
            for (other in seller.cities) if (other != city) minDistanceOldOwner = min(minDistanceOldOwner, center.aerialDistanceTo(other.getCenterTile()))
            var aboutToCapture = false
            if (peaceTreatyTrade) {
                val enemiesAdjacent = center.neighbors.sumOf { tile -> tile.getUnits().count { it.civ == assumedOwner && it.isMilitary() } }
                if (enemiesAdjacent >= 2 && 100 * city.health / city.getMaxHealth() < 10 + warScore / 10) aboutToCapture = true
            }
            if (!aboutToCapture) {
                if (onlyCitiesAboutToBeConquered) return null
                if (newInternalBorderCount == 0 && minDistanceOldOwner != Int.MAX_VALUE && minDistance > minDistanceOldOwner * 3 / 2) return null
            }
        }

        if (city.isPuppet) itemValue = itemValue * 70 / 100
        if (city.isBeingRazed) itemValue = itemValue * 50 / 100

        val buildings = city.cityConstructions.getBuiltBuildings()
        var wonders = buildings.count { it.isWonder }
        if (buyerOwns) wonders += buildings.count { it.isNationalWonder }
        if (wonders > 0) itemValue = itemValue * (100 + wonders * 11) / 100

        if (!peaceTreatyTrade && !buyerOwns) {
            // Not to warmongers
            when (seller.ai.diplomacy.warmongerThreat(assumedOwner)) {
                AiThreat.None, AiThreat.Minor -> {}
                AiThreat.Major -> itemValue *= 2
                AiThreat.Severe, AiThreat.Critical -> return null
            }
        }
        return max(itemValue, city.population.population * 729)
    }

    /**
     *  CvDealAI::GetEmbassyValue: 35 scaled by this speed's deal duration; one we give × approach (Hostile 250%, Guarded 130%, Afraid 80%).
     *  The original's even value averages with the other player's CvDealAI::GetTradeAgreementValue, not its embassy value; ported as is.
     */
    fun getEmbassyValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        val standardDuration = us.gameInfo.ruleset.speeds["Standard"]?.dealDuration ?: us.gameInfo.speed.dealDuration
        var value = 35 * us.gameInfo.speed.dealDuration / standardDuration
        if (fromMe) {
            value *= when (us.ai.diplomacy.approachTowards(them, true)) {
                MajorCivApproach.Hostile -> 250
                MajorCivApproach.Guarded -> 130
                MajorCivApproach.Afraid -> 80
                else -> 100
            }
            value /= 100
        }
        if (useEvenValue) {
            value += getTradeAgreementValue(them, us, !fromMe)
            value /= 2
        }
        return value
    }

    /** CvDealAI::GetTradeAgreementValue (no trade agreement item in BNW; read only by the embassy's even value) */
    private fun getTradeAgreementValue(us: Civilization, them: Civilization, fromMe: Boolean): Int {
        if (!fromMe) return 100
        return 100 * when (us.ai.diplomacy.approachTowards(them, true)) {
            MajorCivApproach.Hostile -> 250
            MajorCivApproach.Guarded -> 130
            MajorCivApproach.Afraid -> 80
            MajorCivApproach.Neutral -> 110
            else -> 100
        } / 100
    }

    /**
     *  CvDealAI::GetOpenBordersValue. A Friendly approach makes it 50 both ways. Giving: by approach (Hostile 1000, Guarded 100, Afraid 20,
     *  Neutral 75) × opinion (Ally 0, Friend 35%, Favorable 70%, Competitor 150%, Enemy 400%, Unforgivable 10000), ×10% / ×25% if they fight
     *  two / one of our enemies. Receiving: by proximity (5/10/15/30), halved if they have one city.
     */
    fun getOpenBordersValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        if (approach == MajorCivApproach.Friendly) return 50
        var value: Int
        if (fromMe) {
            value = when (approach) {
                MajorCivApproach.Hostile -> 1000
                MajorCivApproach.Guarded -> 100
                MajorCivApproach.Afraid -> 20
                MajorCivApproach.Neutral -> 75
                else -> 100
            }
            value = when (us.ai.diplomacy.opinionOf(them)) {
                MajorCivOpinion.Ally -> 0
                MajorCivOpinion.Friend -> value * 35 / 100
                MajorCivOpinion.Favorable -> value * 70 / 100
                MajorCivOpinion.Neutral -> value
                MajorCivOpinion.Competitor -> value * 150 / 100
                MajorCivOpinion.Enemy -> value * 400 / 100
                MajorCivOpinion.Unforgivable -> 10000
            }
            val enemiesAtWarWith = us.ai.diplomacy.numOurEnemiesAtWarWith(them)
            if (enemiesAtWarWith >= 2) value = value * 10 / 100
            else if (enemiesAtWarWith == 1) value = value * 25 / 100
        } else {
            value = when (us.getProximity(them)) {
                Proximity.Distant -> 5
                Proximity.Far -> 10
                Proximity.Close -> 15
                Proximity.Neighbors -> 30
                Proximity.None -> 0
            }
            if (them.cities.size == 1) value = value * 50 / 100
        }
        value = value * CultureAutomation.openBordersValuePercent(us, them, receiving = !fromMe) / 100
        if (useEvenValue) {
            value += getOpenBordersValue(them, us, !fromMe, false)
            value /= 2
        }
        return value
    }

    /**
     *  CvDealAI::GetDefensivePactValue (Vox Populi): [NEVER] unless we want a pact with them ([PactPlanningAi]); otherwise their
     *  [PactPlanningAi.scoreDefensivePactChoice] times our era (at least 1): receiving one is worth a positive score (twice for our most
     *  valuable ally), giving one costs a negative score (twice unless they are our most valuable ally), else 0
     */
    fun getDefensivePactValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        var value = defensivePactValue(us, them, fromMe)
        if (useEvenValue) {
            value += defensivePactValue(them, us, !fromMe)
            value /= 2
        }
        return value
    }

    private fun defensivePactValue(us: Civilization, them: Civilization, fromMe: Boolean): Int {
        if (!us.isMajorCiv || !us.ai.diplomacy.memoryOf(them).wantsDefensivePact) return NEVER
        val coastal = PactPlanningAi.numEffectiveCoastalCities(us) > 1
        val score = PactPlanningAi.scoreDefensivePactChoice(us, them, coastal) * max(1, us.getAiEraNumber())
        val mostValuableAlly = us.ai.diplomacy.mostValuableAlly == them.civID
        return when {
            score > 0 && !fromMe -> if (mostValuableAlly) score * 2 else score
            score < 0 && fromMe -> if (mostValuableAlly) -score else -score * 2
            else -> 0
        }
    }

    /** CvDealAI::GetResearchAgreementValue: 100; giving one +100 per era they are ahead, × approach (Hostile ×10, Afraid ×0.8) */
    fun getResearchAgreementValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        var value = 100
        if (fromMe) {
            value += 100 * max(0, them.getAiEraNumber() - us.getAiEraNumber())
            value *= when (us.ai.diplomacy.approachTowards(them, true)) {
                MajorCivApproach.Hostile -> 1000
                MajorCivApproach.Afraid -> 80
                else -> 100
            }
            value /= 100
        }
        if (useEvenValue) {
            value += getResearchAgreementValue(them, us, !fromMe, false)
            value /= 2
        }
        return value
    }

    /**
     *  CvDealAI::GetThirdPartyPeaceValue: we make peace with [third]: 200-600 by war projection, +50 per era, × our hatred of a major
     *  [third] (Unforgivable ×3, Enemy ×2), × our liking of [them] (Ally 30%, Friend 50%, Favorable 75%). Peace with a city-state allied to
     *  either side, inside a peace deal between us, is worth 0. Peace [them] make is worth -10000.
     */
    fun getThirdPartyPeaceValue(us: Civilization, them: Civilization, third: Civilization, fromMe: Boolean): Int {
        if (third.isCityState && us.isAtWarWith(them)) {
            val ally = third.allyCiv
            if (ally == them || ally == us) return 0
        }
        if (!fromMe) return -10000
        var value = when (us.ai.diplomacy.warProjection(third) ?: WarProjection.Unknown) {
            WarProjection.VeryGood -> 600
            WarProjection.Good -> 400
            WarProjection.Unknown -> 250
            else -> 200
        }
        value += us.getAiEraNumber() * 50
        if (!third.isCityState) {
            when (us.ai.diplomacy.opinionOf(third)) {
                MajorCivOpinion.Unforgivable -> value = value * 300 / 100
                MajorCivOpinion.Enemy -> value = value * 200 / 100
                else -> {}
            }
        }
        when (us.ai.diplomacy.opinionOf(them)) {
            MajorCivOpinion.Ally -> value = value * 30 / 100
            MajorCivOpinion.Friend -> value = value * 50 / 100
            MajorCivOpinion.Favorable -> value = value * 75 / 100
            else -> {}
        }
        return value
    }

    /**
     *  CvDealAI::GetThirdPartyWarValue: we declare war on [third]: refused ([NEVER]) when our War approach bias is under 4; else
     *  400 / 600 / 1000 / 50000 by war projection, +50 per era, ±10% per point of War bias away from DEFAULT_FLAVOR_VALUE; never against
     *  a city-state we are friendly toward; × hatred of a major [third] (25-75%), × liking of [them] (50-125%).
     *  War [them] declare is worth 200 / 100 if we find [third] Unforgivable / Enemy, else -100000.
     */
    fun getThirdPartyWarValue(us: Civilization, them: Civilization, third: Civilization, fromMe: Boolean): Int {
        val warBiasWeight = us.ai.majorCivApproachBias(MajorCivApproach.War)
        if (fromMe && warBiasWeight < 4) return NEVER
        if (!fromMe) {
            if (third.isCityState) return -NEVER
            return when (us.ai.diplomacy.opinionOf(third)) {
                MajorCivOpinion.Unforgivable -> 200
                MajorCivOpinion.Enemy -> 100
                else -> -NEVER
            }
        }
        val projection = us.ai.diplomacy.warProjection(third) ?: WarProjection.Unknown
        var value = when {
            projection >= WarProjection.Good -> 400
            projection == WarProjection.Unknown -> 600
            projection == WarProjection.Stalemate -> 1000
            else -> 50000
        }
        value += us.getAiEraNumber() * 50
        val warBias = us.define(AiDefine.DEFAULT_FLAVOR_VALUE) - warBiasWeight
        value += warBias * 10 * value / 100
        if (third.isCityState) {
            val approach = us.ai.minorCivApproaches[third.civID]
            if (approach == MinorCivApproach.Friendly) value = NEVER
        } else {
            when (us.ai.diplomacy.opinionOf(third)) {
                MajorCivOpinion.Unforgivable -> value = value * 25 / 100
                MajorCivOpinion.Enemy -> value = value * 50 / 100
                MajorCivOpinion.Competitor -> value = value * 75 / 100
                else -> {}
            }
        }
        when (us.ai.diplomacy.opinionOf(them)) {
            MajorCivOpinion.Ally -> value = value * 50 / 100
            MajorCivOpinion.Friend -> value = value * 75 / 100
            MajorCivOpinion.Favorable -> value = value * 85 / 100
            MajorCivOpinion.Competitor -> value = value * 125 / 100
            else -> {}
        }
        return value
    }

    /**
     *  CvDealAI::GetVoteCommitmentValue, per vote. Votes we give: by how much we like the proposal (50-300, [NEVER] when we strongly
     *  dislike it), by our alignment with [them] (-50..+50, [NEVER] for enemies or war), [NEVER] if we are Hostile or planning war on them.
     *  Votes we receive: by how much we like it (50-200, -100000 if not at least weakly), less 20/40 when they keep many votes in hand.
     */
    fun getVoteCommitmentValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean, useEvenValue: Boolean): Int {
        val commitment = VoteCommitmentItem.parse(offer) ?: return 0
        val votes = offer.amount
        var value = 0
        val desire = LeagueAutomation.evaluateVoteForTrade(us, commitment.proposalId, commitment.choice, votes, commitment.repeal)
        if (fromMe) {
            value += when (desire) {
                LeagueDesire.NEVER, LeagueDesire.STRONG_DISLIKE -> NEVER
                LeagueDesire.DISLIKE -> 300
                LeagueDesire.WEAK_DISLIKE -> 200
                LeagueDesire.NEUTRAL, LeagueDesire.WEAK_LIKE, LeagueDesire.LIKE -> 150
                LeagueDesire.STRONG_LIKE, LeagueDesire.ALWAYS -> 50
            }
            value += when (LeagueAutomation.evaluateAlignment(us, them)) {
                LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER -> -50
                LeagueAlignment.ALLY -> -35
                LeagueAlignment.CONFIDANT, LeagueAlignment.FRIEND -> -25
                LeagueAlignment.RIVAL -> 25
                LeagueAlignment.HATRED -> 50
                LeagueAlignment.ENEMY, LeagueAlignment.WAR -> NEVER
                else -> 0
            }
            val approach = us.ai.diplomacy.approachTowards(them, false)
            if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) value += NEVER
        } else {
            value += when (desire) {
                LeagueDesire.WEAK_LIKE -> 50
                LeagueDesire.LIKE -> 100
                LeagueDesire.STRONG_LIKE -> 150
                LeagueDesire.ALWAYS -> 200
                else -> -NEVER
            }
            val league = us.gameInfo.league
            if (league != null) {
                val startingVotes = league.getDelegates(them)
                val ratio = if (startingVotes > 0) votes.toFloat() / startingVotes else 1f
                if (ratio <= 0.25f) value -= 40
                else if (ratio <= 0.5f) value -= 20
            }
        }
        var total = max(value, 0).toLong() * votes
        if (useEvenValue) {
            total += getVoteCommitmentValue(them, us, offer, !fromMe, false)
            total /= 2
        }
        return clamp(total)
    }

    /** Unciv items without a CvDealAI counterpart: Unciv's own prices (no gold inflation) */
    private fun getUncivItemValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean, useEvenValue: Boolean, trade: Trade): Int {
        val ruleset = us.gameInfo.ruleset
        var value: Int = when (offer.type) {
            TradeOfferType.GreatWork -> {
                val ours = (if (fromMe) trade.ourOffers else trade.theirOffers).firstOrNull { it.type == TradeOfferType.GreatWork }
                val theirs = (if (fromMe) trade.theirOffers else trade.ourOffers).firstOrNull { it.type == TradeOfferType.GreatWork }
                val given = (if (fromMe) ours else theirs)?.let { GreatWorkFunctions.findTradeSlot(us, it.name) }
                val received = (if (fromMe) theirs else ours)?.let { GreatWorkFunctions.findTradeSlot(them, it.name) }
                if (given == null || received == null) (if (fromMe) NEVER else 0)
                else if (fromMe) 200 + max(0, -GreatWorkFunctions.themingGain(given, received)) * 250
                else 200 + max(0, GreatWorkFunctions.themingGain(given, received)) * 250
            }
            TradeOfferType.Technology -> ruleset.technologies[offer.name]?.let { sqrt(it.cost.toDouble()).toInt() * 20 } ?: 0
            TradeOfferType.Introduction ->
                ruleset.modOptions.getMatchingUniques(UniqueType.TradeCivIntroductions).firstOrNull()?.params?.get(0)?.toInt() ?: 0
            else -> {
                val resource = ruleset.tileResources[offer.name]
                if (resource == null) 0
                else if (fromMe) resource.getMatchingUniques(UniqueType.AiWillSellAt, GameContext(us)).minOfOrNull { it.params[0].toInt() } ?: NEVER
                else resource.getMatchingUniques(UniqueType.AiWillBuyAt, GameContext(us)).minOfOrNull { it.params[0].toInt() } ?: 0
            }
        }
        if (useEvenValue) {
            value += getUncivItemValue(them, us, offer, !fromMe, false, trade.reverse())
            value /= 2
        }
        return value
    }
    //endregion

    //region Acceptance and balancing

    /** CvDealAI::IsDealWithHumanAcceptable, with [cachedPeaceValue] standing for CvDealAI::GetCachedValueOfPeaceWithHuman */
    class HumanDealCheck {
        var acceptable = false
        var cantMatchOffer = false
        val value = DealValue()
        var amountOverWeWillRequest = 0
        var amountUnderWeWillOffer = 0
    }

    /**
     *  CvDealAI::IsDealWithHumanAcceptable: acceptable when no gold is involved and both sides are within 25 value (one GPT) of each other;
     *  for a peace deal when the value reaches the cached value of the peace we want; otherwise when the value is in
     *  0..10% of the deal's total size.
     */
    fun isDealWithHumanAcceptable(us: Civilization, human: Civilization, trade: Trade, check: HumanDealCheck = HumanDealCheck()): HumanDealCheck {
        check.cantMatchOffer = false
        check.acceptable = false
        val value = getDealValue(us, human, trade, false, check.value)
        if (trade.ourOffers.none { it.type == TradeOfferType.Gold } && trade.theirOffers.none { it.type == TradeOfferType.Gold }) {
            if (abs(value.theyreOffering - value.imOffering) < 25) {
                check.acceptable = true
                return check
            }
        }
        val dealSum = value.imOffering + value.theyreOffering
        check.amountOverWeWillRequest = dealSum * LEEWAY_WITH_HUMAN_PERCENT / 100
        check.amountUnderWeWillOffer = 0
        if (trade.isPeaceTreaty()) {
            if (value.total >= cachedValueOfPeaceWithHuman(us, human)) check.acceptable = true
        } else if (value.total <= check.amountOverWeWillRequest && value.total >= check.amountUnderWeWillOffer) {
            check.acceptable = true
        } else if (value.total > check.amountOverWeWillRequest) {
            check.cantMatchOffer = true
        }
        return check
    }

    /**
     *  CvDealAI::GetCachedValueOfPeaceWithHuman, as CvDealAI::DoTradeScreenOpened sets it: the value of the peace treaty we want with the
     *  human - minus what we would give if we surrender, plus what they would give if they surrender, 0 for white peace.
     */
    fun cachedValueOfPeaceWithHuman(us: Civilization, human: Civilization): Int {
        if (!us.isAtWarWith(human)) return 0
        val offer = us.ai.diplomacy.treatyWillingToOffer(human)
        val accept = us.ai.diplomacy.treatyWillingToAccept(human)
        if (offer < PeaceTreatyType.WhitePeace || accept < PeaceTreatyType.WhitePeace) return 0
        val trade = Trade()
        return when {
            offer > PeaceTreatyType.WhitePeace -> {
                addItemsToDealForPeaceTreaty(trade, us, human, offer, meSurrendering = true)
                -getDealValue(us, human, trade, false).imOffering
            }
            accept > PeaceTreatyType.WhitePeace -> {
                addItemsToDealForPeaceTreaty(trade, us, human, accept, meSurrendering = false)
                getDealValue(us, human, trade, false).theyreOffering
            }
            else -> 0
        }
    }

    /** The outcome of CvDealAI::DoHumanOfferDealToThisAI */
    enum class DealResponse { Accepted, Unacceptable, Insulting }

    /**
     *  CvDealAI::DoHumanOfferDealToThisAI: accept an acceptable deal or a gift (they give more than we do, outside peace deals);
     *  else "unacceptable" if the value is above -75 and we are not asked for five times more than we get, else "insulting".
     */
    fun respondToHumanDeal(us: Civilization, human: Civilization, trade: Trade): DealResponse {
        val check = isDealWithHumanAcceptable(us, human, trade)
        val value = check.value
        val acceptable = check.acceptable || (!trade.isPeaceTreaty() && value.theyreOffering > value.imOffering)
        return when {
            acceptable -> DealResponse.Accepted
            value.total > -75 && value.imOffering < value.theyreOffering * 5 -> DealResponse.Unacceptable
            else -> DealResponse.Insulting
        }
    }

    /**
     *  CvDealAI::DoAcceptedDeal for a deal a human offered us: a generous deal (value ≥ 100, or they give five times what we give)
     *  adds its value to our recent trade value with them, an acceptable one half of it; peace deals count against the cached peace value.
     */
    fun onHumanDealAccepted(us: Civilization, human: Civilization, trade: Trade, value: DealValue) {
        val dealValueToMe = value.total - (if (trade.isPeaceTreaty()) cachedValueOfPeaceWithHuman(us, human) else 0)
        if (dealValueToMe >= 100 || value.theyreOffering > value.imOffering * 5)
            us.ai.diplomacy.changeRecentTradeValue(human, dealValueToMe)
        else us.ai.diplomacy.changeRecentTradeValue(human, dealValueToMe / 2)
    }

    /**
     *  CvDealAI::DoEqualizeDealWithAI: with even values, a deal within ±25% of its total size is fine as it is. Otherwise items are added
     *  and removed (resources, GPT, gold, then trimming GPT and gold; open borders are never added between AIs), and the
     *  result is acceptable only if each side, by its own valuation, receives at least 75% of what it gives.
     */
    fun doEqualizeDealWithAI(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val even = getDealValue(us, them, trade, true)
        val dealDuration = us.gameInfo.speed.dealDuration
        val dealSum = even.imOffering + even.theyreOffering
        val amountOver = dealSum * LEEWAY_WITH_AI_PERCENT / 100
        val amountUnder = dealSum * -LEEWAY_WITH_AI_PERCENT / 100
        if (even.total in amountUnder..amountOver) return true

        val eq = Equalizer(us, them, trade, true, even)
        eq.addVoteCommitmentToThem(false, amountOver)
        eq.addVoteCommitmentToUs(false, amountUnder)
        eq.addResourceToThem(false, amountOver, dealDuration)
        eq.addResourceToUs(false, amountUnder, dealDuration)
        // DoAddOpenBordersToThem/ToUs are called with bDontChange...ExistingItems = true here, so they never add anything
        eq.addGptToThem(false, dealDuration)
        eq.addGptToUs(false, dealDuration)
        eq.addGoldToThem(false)
        eq.addGoldToUs(false)
        eq.removeGptFromThem(dealDuration)
        eq.removeGptFromUs(dealDuration)
        eq.removeGoldFromUs()
        eq.removeGoldFromThem()

        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) return false
        return isAcceptableToBothAIs(us, them, trade)
    }

    /** The end of CvDealAI::DoEqualizeDealWithAI: each side gets at least (100 - leeway)% of what it gives, by its own valuation */
    fun isAcceptableToBothAIs(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val mine = getDealValue(us, them, trade, false)
        val lowEndOfWhatIWillAccept = mine.imOffering - mine.imOffering * LEEWAY_WITH_AI_PERCENT / 100
        if (mine.theyreOffering < lowEndOfWhatIWillAccept) return false
        val theirs = getDealValue(them, us, trade.reverse(), false)
        val lowEndOfWhatTheyWillAccept = theirs.imOffering - theirs.imOffering * LEEWAY_WITH_AI_PERCENT / 100
        return theirs.theyreOffering >= lowEndOfWhatTheyWillAccept
    }

    /** Whether a deal another AI proposes is one we would have made: the even-value band of CvDealAI::DoEqualizeDealWithAI, or the 75% rule */
    fun isAcceptableBetweenAIs(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val even = getDealValue(us, them, trade, true)
        val dealSum = even.imOffering + even.theyreOffering
        if (even.total in dealSum * -LEEWAY_WITH_AI_PERCENT / 100..dealSum * LEEWAY_WITH_AI_PERCENT / 100) return true
        return isAcceptableToBothAIs(us, them, trade)
    }

    /**
     *  CvDealAI::DoEqualizeDealWithHuman: a peace deal is replaced by the treaty we want (IsOfferPeace); otherwise, unless already
     *  acceptable, items are added in the original's order (embassies, resources, open borders, GPT, gold, cities when we
     *  surrender) and trimmed. Returns whether the resulting [trade] is acceptable to us.
     */
    fun doEqualizeDealWithHuman(us: Civilization, human: Civilization, trade: Trade,
                                dontChangeMyExistingItems: Boolean, dontChangeTheirExistingItems: Boolean): Boolean {
        if (trade.isPeaceTreaty()) {
            trade.ourOffers.clear()
            trade.theirOffers.clear()
            return isOfferPeace(us, human, trade)
        }
        val check = isDealWithHumanAcceptable(us, human, trade)
        if (check.value.total < 0 && dontChangeTheirExistingItems) return false
        if (check.acceptable) return true
        val dealDuration = us.gameInfo.speed.dealDuration
        val over = check.amountOverWeWillRequest
        val under = check.amountUnderWeWillOffer
        val eq = Equalizer(us, human, trade, false, check.value)
        eq.addVoteCommitmentToThem(dontChangeTheirExistingItems, over)
        eq.addVoteCommitmentToUs(dontChangeMyExistingItems, under)
        eq.addEmbassyToThem(dontChangeTheirExistingItems, over)
        eq.addEmbassyToUs(dontChangeMyExistingItems, under)
        eq.addResourceToThem(dontChangeTheirExistingItems, over, dealDuration)
        eq.addResourceToUs(dontChangeMyExistingItems, under, dealDuration)
        eq.addOpenBordersToThem(dontChangeTheirExistingItems, over, dealDuration)
        eq.addOpenBordersToUs(dontChangeMyExistingItems, under, dealDuration)
        eq.addGptToThem(dontChangeTheirExistingItems, dealDuration)
        eq.addGptToUs(dontChangeMyExistingItems, dealDuration)
        eq.addGoldToThem(dontChangeTheirExistingItems)
        eq.addGoldToUs(dontChangeMyExistingItems)
        if (!dontChangeTheirExistingItems) eq.removeGptFromThem(dealDuration)
        if (!dontChangeMyExistingItems) eq.removeGptFromUs(dealDuration)
        eq.removeGoldFromUs()
        eq.removeGoldFromThem()
        // DoAddCitiesToUs acts only when we are the surrendering player of a peace deal, which this is not
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) return false
        return isDealWithHumanAcceptable(us, human, trade).acceptable
    }

    /**
     *  The CvDealAI::DoAdd... / DoRemove... helpers on one deal. Each adds or trims an item only when it moves the deal toward
     *  [DealValue.total] = 0 without crossing the limit, and recomputes the total afterwards (where the original does).
     */
    private class Equalizer(val us: Civilization, val them: Civilization, val trade: Trade, val useEvenValue: Boolean, val value: DealValue) {
        var total = value.total
        private val ourAvailable: TradeOffersList
        private val theirAvailable: TradeOffersList

        init {
            val logic = TradeLogic(us, them)
            ourAvailable = logic.ourAvailableOffers
            theirAvailable = logic.theirAvailableOffers
        }

        private fun recompute() {
            getDealValue(us, them, trade, useEvenValue, value)
            total = value.total
        }

        private fun item(fromUs: Boolean, offer: TradeOffer) =
            getTradeItemValue(us, them, offer, fromUs, useEvenValue, trade)

        private fun isPossible(fromUs: Boolean, offer: TradeOffer) =
            isPossibleToTradeItem(trade, if (fromUs) us else them, if (fromUs) them else us, offer, fromUs, if (fromUs) ourAvailable else theirAvailable)

        fun addVoteCommitmentToThem(dontChange: Boolean, amountOver: Int) {
            if (dontChange || total >= 0) return
            if (hasVoteCommitment()) return
            if (!LeagueAutomation.canCommitVote(them, us)) return
            for (commitment in LeagueAutomation.desiredVoteCommitments(us, them)) {
                val offer = VoteCommitmentItem.offer(commitment, us.gameInfo.speed)
                if (!isPossible(false, offer)) continue
                if (item(false, offer) + total <= amountOver) {
                    trade.theirOffers.add(offer)
                    recompute()
                }
            }
        }

        fun addVoteCommitmentToUs(dontChange: Boolean, amountUnder: Int) {
            if (dontChange || total <= 0) return
            if (hasVoteCommitment()) return
            if (!LeagueAutomation.canCommitVote(us, them)) return
            for (commitment in LeagueAutomation.desiredVoteCommitments(them, us)) {
                val offer = VoteCommitmentItem.offer(commitment, us.gameInfo.speed)
                if (!isPossible(true, offer)) continue
                if (-item(true, offer) + total >= amountUnder) {
                    trade.ourOffers.add(offer)
                    recompute()
                }
            }
        }

        private fun hasVoteCommitment() = trade.ourOffers.any { it.type == TradeOfferType.VoteCommitment }
            || trade.theirOffers.any { it.type == TradeOfferType.VoteCommitment }

        /** CvDeal::ChangeResourceTrade then AddResourceTrade: an existing item is changed in place without re-evaluating, as in the original */
        private fun addOrChangeResource(list: TradeOffersList, offer: TradeOffer) {
            val existing = list.firstOrNull { it.name == offer.name && it.type == offer.type }
            if (existing != null) {
                existing.amount = offer.amount
                existing.duration = offer.duration
                return
            }
            list.add(offer)
            recompute()
        }

        private fun resourceOfferType(resource: TileResource) =
            if (resource.resourceType == ResourceType.Luxury) TradeOfferType.Luxury_Resource else TradeOfferType.Strategic_Resource

        fun addResourceToThem(dontChange: Boolean, amountOver: Int, duration: Int) {
            if (dontChange || total >= 0) return
            val resources = us.gameInfo.ruleset.tileResources.values
            val league = us.gameInfo.league
            for (resource in resources) {
                if (resource.resourceType != ResourceType.Luxury) continue
                if (numResourceAvailable(them, resource, false) <= 0) continue
                if (league?.isLuxuryBanned(resource.name) == true) continue
                val offer = TradeOffer(resource.name, TradeOfferType.Luxury_Resource, 1, duration)
                if (!isPossible(false, offer)) continue
                if (item(false, offer) + total <= amountOver) addOrChangeResource(trade.theirOffers, offer)
            }
            for (resource in resources) {
                if (resource.resourceType == ResourceType.Luxury) continue
                val available = numResourceAvailable(them, resource, false)
                if (available <= 0) continue
                val offer = TradeOffer(resource.name, resourceOfferType(resource), min(5, available), duration)
                if (!isPossible(false, offer)) continue
                if (item(false, offer) + total <= amountOver) addOrChangeResource(trade.theirOffers, offer)
            }
        }

        fun addResourceToUs(dontChange: Boolean, amountUnder: Int, duration: Int) {
            if (dontChange || total <= 0) return
            val league = us.gameInfo.league
            for (resource in us.gameInfo.ruleset.tileResources.values) {
                var quantity = numResourceAvailable(us, resource, false)
                if (quantity == 0) continue
                if (resource.resourceType == ResourceType.Luxury) {
                    quantity = 1
                    if (league?.isLuxuryBanned(resource.name) == true) continue
                } else quantity = min(5, quantity)
                val offer = TradeOffer(resource.name, resourceOfferType(resource), quantity, duration)
                if (!isPossible(true, offer)) continue
                if (-item(true, offer) + total >= amountUnder) addOrChangeResource(trade.ourOffers, offer)
            }
        }

        fun addEmbassyToThem(dontChange: Boolean, amountOver: Int) {
            if (dontChange || total >= 0 || trade.theirOffers.any { it.type == TradeOfferType.Embassy }) return
            val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = us.gameInfo.speed)
            if (!isPossible(false, offer)) return
            if (item(false, offer) + total <= amountOver) {
                trade.theirOffers.add(offer)
                recompute()
            }
        }

        fun addEmbassyToUs(dontChange: Boolean, amountUnder: Int) {
            if (dontChange || total <= 0 || trade.ourOffers.any { it.type == TradeOfferType.Embassy }) return
            val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = us.gameInfo.speed)
            if (!isPossible(true, offer)) return
            if (-item(true, offer) + total >= amountUnder) {
                trade.ourOffers.add(offer)
                recompute()
            }
        }

        fun addOpenBordersToThem(dontChange: Boolean, amountOver: Int, duration: Int) {
            if (dontChange || total >= 0 || trade.theirOffers.any { it.name == Constants.openBorders }) return
            val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, duration)
            if (!isPossible(false, offer)) return
            if (item(false, offer) + total <= amountOver) {
                trade.theirOffers.add(offer)
                recompute()
            }
        }

        fun addOpenBordersToUs(dontChange: Boolean, amountUnder: Int, duration: Int) {
            if (dontChange || total <= 0 || trade.ourOffers.any { it.name == Constants.openBorders }) return
            val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, duration)
            if (!isPossible(true, offer)) return
            if (-item(true, offer) + total >= amountUnder) {
                trade.ourOffers.add(offer)
                recompute()
            }
        }

        private fun amountOf(list: TradeOffersList, type: TradeOfferType) = list.firstOrNull { it.type == type }?.amount ?: 0

        /** CvDeal::ChangeGoldTrade/AddGoldTrade (and the GPT versions): set the item's amount, adding it if missing */
        private fun setAmount(list: TradeOffersList, type: TradeOfferType, amount: Int, duration: Int) {
            val existing = list.firstOrNull { it.type == type }
            if (existing != null) {
                existing.amount = amount
                return
            }
            val name = if (type == TradeOfferType.Gold) Constants.flatGold else Constants.goldPerTurn
            list.add(TradeOffer(name, type, amount, duration))
        }

        private fun removeItem(list: TradeOffersList, type: TradeOfferType) {
            list.firstOrNull { it.type == type }?.let { list.remove(it) }
        }

        fun addGoldToThem(dontChange: Boolean) {
            if (dontChange || total >= 0 || amountOf(trade.ourOffers, TradeOfferType.Gold) != 0) return
            var gold = getGoldForValueExchange(us, them, -total, true, false, useEvenValue, false)
            val already = amountOf(trade.theirOffers, TradeOfferType.Gold)
            gold = min(gold + already, goldAvailable(trade, them, false))
            if (gold != already) setAmount(trade.theirOffers, TradeOfferType.Gold, gold, -1)
            recompute()
        }

        fun addGoldToUs(dontChange: Boolean) {
            if (dontChange || total <= 0 || amountOf(trade.theirOffers, TradeOfferType.Gold) != 0) return
            var gold = getGoldForValueExchange(us, them, total, true, true, useEvenValue, false)
            val already = amountOf(trade.ourOffers, TradeOfferType.Gold)
            gold = min(gold + already, goldAvailable(trade, us, true))
            if (gold != already) setAmount(trade.ourOffers, TradeOfferType.Gold, gold, -1)
            recompute()
        }

        fun addGptToThem(dontChange: Boolean, duration: Int) {
            if (dontChange || total >= 0 || goldRate(them) <= 0 || amountOf(trade.ourOffers, TradeOfferType.Gold_Per_Turn) != 0) return
            var gpt = getGptForValueExchange(us, them, -total, true, duration, false, useEvenValue, false)
            val already = amountOf(trade.theirOffers, TradeOfferType.Gold_Per_Turn)
            gpt = min(gpt + already, goldRate(them))
            if (gpt != already) setAmount(trade.theirOffers, TradeOfferType.Gold_Per_Turn, gpt, duration)
            recompute()
        }

        fun addGptToUs(dontChange: Boolean, duration: Int) {
            if (dontChange || total <= 0 || goldRate(us) <= 0 || amountOf(trade.theirOffers, TradeOfferType.Gold_Per_Turn) != 0) return
            var gpt = getGptForValueExchange(us, them, total, true, duration, true, useEvenValue, false)
            val already = amountOf(trade.ourOffers, TradeOfferType.Gold_Per_Turn)
            gpt = min(gpt + already, goldRate(us))
            if (gpt != already) setAmount(trade.ourOffers, TradeOfferType.Gold_Per_Turn, gpt, duration)
            recompute()
        }

        /** CvDealAI::DoRemoveGPTFromThem: trims their GPT by the GPT worth 150% of our surplus */
        fun removeGptFromThem(duration: Int) {
            if (total <= 0) return
            val toRemove = getGptForValueExchange(us, them, total * 150 / 100, true, duration, false, useEvenValue, true)
            val inDeal = amountOf(trade.theirOffers, TradeOfferType.Gold_Per_Turn)
            if (inDeal <= 0) return
            val left = inDeal - min(toRemove, inDeal)
            if (left == 0) removeItem(trade.theirOffers, TradeOfferType.Gold_Per_Turn)
            else setAmount(trade.theirOffers, TradeOfferType.Gold_Per_Turn, left, duration)
            recompute()
        }

        /** CvDealAI::DoRemoveGPTFromUs */
        fun removeGptFromUs(duration: Int) {
            if (total >= 0) return
            val toRemove = getGptForValueExchange(us, them, -total * 150 / 100, true, duration, true, useEvenValue, true)
            val inDeal = amountOf(trade.ourOffers, TradeOfferType.Gold_Per_Turn)
            if (inDeal <= 0) return
            val left = inDeal - min(toRemove, inDeal)
            if (left == 0) removeItem(trade.ourOffers, TradeOfferType.Gold_Per_Turn)
            else setAmount(trade.ourOffers, TradeOfferType.Gold_Per_Turn, left, duration)
            recompute()
        }

        /** CvDealAI::DoRemoveGoldFromThem */
        fun removeGoldFromThem() {
            if (total <= 0) return
            val inDeal = amountOf(trade.theirOffers, TradeOfferType.Gold)
            if (inDeal <= 0) return
            val left = inDeal - min(inDeal, getGoldForValueExchange(us, them, total, true, false, useEvenValue, true))
            if (left == 0) removeItem(trade.theirOffers, TradeOfferType.Gold)
            else setAmount(trade.theirOffers, TradeOfferType.Gold, left, -1)
            recompute()
        }

        /** CvDealAI::DoRemoveGoldFromUs */
        fun removeGoldFromUs() {
            if (total >= 0) return
            val inDeal = amountOf(trade.ourOffers, TradeOfferType.Gold)
            if (inDeal <= 0) return
            val left = inDeal - min(inDeal, getGoldForValueExchange(us, them, -total, true, true, useEvenValue, true))
            if (left == 0) removeItem(trade.ourOffers, TradeOfferType.Gold)
            else setAmount(trade.ourOffers, TradeOfferType.Gold, left, -1)
            recompute()
        }
    }

    /** CvPlayer::calculateGoldRate */
    fun goldRate(civ: Civilization) = civ.stats.statsForNextTurn.gold.toInt()

    /** CvDeal::GetGoldAvailable(ePlayer, TRADE_ITEM_GOLD): the civ's gold minus research agreement costs it pays in [trade] */
    fun goldAvailable(trade: Trade, civ: Civilization, civGivesOurOffers: Boolean): Int {
        val list = if (civGivesOurOffers) trade.ourOffers else trade.theirOffers
        var gold = civ.gold
        for (offer in list) if (offer.type == TradeOfferType.Treaty && offer.name == Constants.researchAgreement) gold -= offer.amount
        return gold
    }

    /**
     *  CvDeal::IsPossibleToTradeItem for an item [from] would add to [trade] (given toward [to]); [fromGivesOurOffers] = [from] is the
     *  player whose items are `trade.ourOffers`. The item must be in [from]'s available offers with enough amount, and: lump gold needs a
     *  mutual Declaration of Friendship outside peace deals and no more than the gold available; GPT no more than the gold rate;
     *  a luxury the receiver does not have yet and is not giving in this deal.
     */
    fun isPossibleToTradeItem(trade: Trade, from: Civilization, to: Civilization, offer: TradeOffer, fromGivesOurOffers: Boolean,
                              available: TradeOffersList): Boolean {
        val receiverGives = if (fromGivesOurOffers) trade.theirOffers else trade.ourOffers
        when (offer.type) {
            TradeOfferType.Gold -> {
                if (!trade.isPeaceTreaty() && !TradeEvaluation.hasMutualDeclarationOfFriendship(from, to)) return false
                return offer.amount <= goldAvailable(trade, from, fromGivesOurOffers)
            }
            TradeOfferType.Gold_Per_Turn -> return offer.amount <= goldRate(from)
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> {
                val resource = from.gameInfo.ruleset.tileResources[offer.name] ?: return false
                if (offer.amount > numResourceAvailable(from, resource, false)) return false
                if (resource.resourceType == ResourceType.Luxury && numResourceAvailable(to, resource, true) > 0) return false
                if (receiverGives.any { it.name == offer.name && it.type == offer.type }) return false
                return available.any { it.name == offer.name && it.type == offer.type }
            }
            TradeOfferType.VoteCommitment -> return !from.isAtWarWith(to) && LeagueAutomation.canCommitVote(from, to)
            else -> return available.any { it.name == offer.name && it.type == offer.type && it.amount >= offer.amount }
        }
    }
    //endregion

    //region Peace

    /**
     *  CvDealAI::IsOfferPeace: fills [trade] (seen from [us]) with a peace treaty both sides accept. Between AIs: what we are willing to
     *  offer must reach what they want to accept and vice versa; when both would give something up, both levels drop until one is white
     *  peace; a gap is split in the middle; the side giving more surrenders that treaty's items. With a human: we surrender if we are
     *  willing to give anything, else we ask them to surrender what we want; white peace adds only the allied city-states' peace.
     */
    fun isOfferPeace(us: Civilization, them: Civilization, trade: Trade): Boolean {
        if (!us.isAtWarWith(them)) return false
        var imWillingToOffer = us.ai.diplomacy.treatyWillingToOffer(them)
        val imWillingToAccept = us.ai.diplomacy.treatyWillingToAccept(them)
        if (imWillingToOffer < PeaceTreatyType.WhitePeace || imWillingToAccept < PeaceTreatyType.WhitePeace) return false

        if (!them.isHuman()) {
            val theyreWillingToAccept = them.ai.diplomacy.treatyWillingToAccept(us)
            var theyreWillingToOffer = them.ai.diplomacy.treatyWillingToOffer(us)
            if (imWillingToOffer < theyreWillingToAccept) return false
            if (theyreWillingToOffer < imWillingToAccept) return false
            val white = PeaceTreatyType.WhitePeace.ordinal
            if (imWillingToOffer.ordinal > white && theyreWillingToOffer.ordinal > white) {
                // The original subtracts the enum values themselves, NO_PEACE_TREATY_TYPE being -1 and WHITE_PEACE 0
                val reduce = min(imWillingToOffer.ordinal, theyreWillingToOffer.ordinal) - white
                imWillingToOffer = PeaceTreatyType.entries[imWillingToOffer.ordinal - reduce]
                theyreWillingToOffer = PeaceTreatyType.entries[theyreWillingToOffer.ordinal - reduce]
            }
            if (imWillingToOffer > theyreWillingToAccept)
                imWillingToOffer = PeaceTreatyType.entries[(imWillingToOffer.ordinal - white + theyreWillingToAccept.ordinal - white) / 2 + white]
            if (theyreWillingToOffer > imWillingToAccept)
                theyreWillingToOffer = PeaceTreatyType.entries[(theyreWillingToOffer.ordinal - white + imWillingToAccept.ordinal - white) / 2 + white]
            if (imWillingToOffer > theyreWillingToOffer)
                addItemsToDealForPeaceTreaty(trade, us, them, imWillingToOffer, meSurrendering = true)
            else if (imWillingToOffer < theyreWillingToOffer)
                addItemsToDealForPeaceTreaty(trade, us, them, theyreWillingToOffer, meSurrendering = false)
            else {
                addPeaceTreaty(trade, us)
                addPlayersAlliesToTreaty(trade, us, them)
            }
        } else {
            when {
                imWillingToOffer > PeaceTreatyType.WhitePeace -> addItemsToDealForPeaceTreaty(trade, us, them, imWillingToOffer, meSurrendering = true)
                imWillingToAccept > PeaceTreatyType.WhitePeace -> addItemsToDealForPeaceTreaty(trade, us, them, imWillingToAccept, meSurrendering = false)
                else -> {
                    addPeaceTreaty(trade, us)
                    addPlayersAlliesToTreaty(trade, us, them)
                }
            }
        }
        return true
    }

    /**
     *  CvDealAI::DoAddItemsToDealForPeaceTreaty (Vox Populi): the loser's package, by war score rather than by treaty. Nothing below an
     *  armistice (white peace has no items; callers add the peace themselves). The score is the loser's [WarDamageAi.warScore] against the
     *  winner as a magnitude, less 10 when the loser has no city in danger (CvPlayer::HasCityInDanger(false, 0) is true for any city,
     *  the danger being a damage total of at least 0, so this only applies to a loser without cities).
     *
     *  Peace between the two and peace with the city-states of each side's ally. Above 25 the loser offers its one most valuable city
     *  ([getCityValueForDeal] for the winner, only cities about to be conquered, never a capital) when it has more than one, and the score
     *  falls by 25. Then gold and half as much GPT (a share in percent equal to the score, then half of it, never the last 2 GPT) of its gold
     *  and rate. Without that city, above 60 and with more than one city, cities worth in total the score/5 percent of all its cities'
     *  value for comparison, most valuable first while each is worth less than what is left. Above 15 luxuries (one each) worth in total
     *  the score percent of what the winner values them at, most valuable first, each while it fits; above 35 strategic resources (up to
     *  3 of each) worth in total score/4 percent of the winner's value, most valuable first, each while it is worth less than what is left.
     *  A resource the winner values at nothing is left out. The winner's value is its own, not an average ([getResourceValue] for the winner).
     *
     *  Not ported: becoming a vassal (and halving the score), revoking vassals: no vassals (roadmap D3).
     */
    fun addItemsToDealForPeaceTreaty(trade: Trade, us: Civilization, them: Civilization, treaty: PeaceTreatyType, meSurrendering: Boolean) {
        if (treaty < PeaceTreatyType.Armistice) return
        val duration = us.gameInfo.speed.dealDuration
        val loser = if (meSurrendering) us else them
        val winner = if (meSurrendering) them else us
        val loserItems = if (meSurrendering) trade.ourOffers else trade.theirOffers
        val available = TradeLogic(loser, winner).ourAvailableOffers

        var warScore = abs(WarDamageAi.warScore(loser, winner))
        if (!hasCityInDanger(loser)) warScore = max(0, warScore - 10)

        addPeaceTreaty(trade, us)
        addPlayersAlliesToTreaty(trade, us, them)

        // A city that is about to fall
        var cityToOffer: City? = null
        var maxCityValue = 0
        if (warScore > 25 && loser.cities.size > 1) {
            for (city in loser.cities) {
                val value = getCityValueForDeal(city, winner, onlyCitiesAboutToBeConquered = true, warScore = warScore) ?: continue
                if (value > maxCityValue) {
                    maxCityValue = value
                    cityToOffer = city
                }
            }
        }
        if (cityToOffer != null) {
            warScore -= 25
            loserItems.add(TradeOffer(cityToOffer.id, TradeOfferType.City, 1, -1))
        }

        val percentGold = warScore
        val percentGpt = warScore / 2
        val giveUpCities = warScore > 60
        val percentCitiesGiveUp = warScore / 5
        val giveUpStrategic = warScore > 35
        val giveUpLuxury = warScore > 15
        val giveUpLuxuryPercent = warScore
        val giveUpStrategicPercent = warScore / 4

        if (cityToOffer == null && giveUpCities && percentCitiesGiveUp > 0 && loser.cities.size > 1) {
            var totalCityValue = 0
            val values = ArrayList<Pair<City, Int>>()
            for (city in loser.cities) {
                val value = getCityValueForDeal(city, winner, false, warScore)
                val valueForComparison = getCityValueForDeal(city, winner, false, warScore, forCityComparison = true)
                if (valueForComparison != null) totalCityValue += valueForComparison
                if (value == null || value <= 0) continue
                values.add(city to value)
            }
            var valueToSurrender = totalCityValue * percentCitiesGiveUp / 100
            // CvWeightedVector::StableSortItems: highest weight first, equal ones in the order they were added
            for ((city, value) in values.sortedByDescending { it.second }) {
                if (value < valueToSurrender) {
                    loserItems.add(TradeOffer(city.id, TradeOfferType.City, 1, -1))
                    valueToSurrender -= value
                }
            }
        }

        if (percentGold > 0) {
            val gold = goldAvailable(trade, loser, meSurrendering) * percentGold / 100
            if (gold > 0) loserItems.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, gold, -1))
        }
        if (percentGpt > 0) {
            val rate = goldRate(loser)
            var gptToGive = rate * percentGpt / 100
            if (gptToGive >= rate - 2) gptToGive -= 2
            if (gptToGive > 0) loserItems.add(TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, gptToGive, duration))
        }

        val resources = us.gameInfo.ruleset.tileResources.values
        if (giveUpLuxury && giveUpLuxuryPercent > 0) {
            var totalValue = 0
            val values = ArrayList<Pair<TileResource, Int>>()
            for (resource in resources) {
                if (resource.resourceType != ResourceType.Luxury || numResourceAvailable(loser, resource, false) == 0) continue
                if (!isPossibleToTradeItem(trade, loser, winner, TradeOffer(resource.name, TradeOfferType.Luxury_Resource, 1, duration), meSurrendering, available)) continue
                val value = getResourceValue(winner, loser, resource, 1, duration, false)
                if (value >= NEVER || value <= 0) continue
                totalValue += value
                values.add(resource to value)
            }
            var valueToSurrender = totalValue * giveUpLuxuryPercent / 100
            for ((resource, value) in values.sortedByDescending { it.second }) {
                if (value <= valueToSurrender) {
                    loserItems.add(TradeOffer(resource.name, TradeOfferType.Luxury_Resource, 1, duration))
                    valueToSurrender -= value
                }
            }
        }
        if (giveUpStrategic && giveUpStrategicPercent > 0) {
            var totalValue = 0
            val values = ArrayList<Triple<TileResource, Int, Int>>()
            for (resource in resources) {
                if (resource.resourceType != ResourceType.Strategic) continue
                val quantity = min(3, numResourceAvailable(loser, resource, false))
                if (quantity <= 0) continue
                if (!isPossibleToTradeItem(trade, loser, winner, TradeOffer(resource.name, TradeOfferType.Strategic_Resource, quantity, duration), meSurrendering, available)) continue
                val value = getResourceValue(winner, loser, resource, quantity, duration, false)
                if (value >= NEVER || value <= 0) continue
                totalValue += value
                values.add(Triple(resource, quantity, value))
            }
            var valueToSurrender = totalValue * giveUpStrategicPercent / 100
            for ((resource, quantity, value) in values.sortedByDescending { it.third }) {
                if (value < valueToSurrender) {
                    loserItems.add(TradeOffer(resource.name, TradeOfferType.Strategic_Resource, quantity, duration))
                    valueToSurrender -= value
                }
            }
        }
    }

    /** CvPlayer::HasCityInDanger(false, 0): a city whose plot danger (a damage total, never negative) is at least 0, that is, any city */
    private fun hasCityInDanger(civ: Civilization) = civ.cities.isNotEmpty()

    /** CvDeal::AddPeaceTreaty for both players, unless the deal has them already */
    private fun addPeaceTreaty(trade: Trade, us: Civilization) {
        val duration = us.gameInfo.speed.peaceDealDuration
        for (list in listOf(trade.ourOffers, trade.theirOffers))
            if (list.none { it.type == TradeOfferType.Treaty && it.name == Constants.peaceTreaty })
                list.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, duration))
    }

    /**
     *  CvDealAI::DoAddPlayersAlliesToTreaty: peace with each city-state allied to [them] that is at war with us, and with each
     *  city-state allied to us that is at war with them (no permanent wars in Unciv).
     */
    fun addPlayersAlliesToTreaty(trade: Trade, us: Civilization, them: Civilization) {
        val duration = us.gameInfo.speed.peaceDealDuration
        for (minor in us.gameInfo.getAliveCityStates()) {
            val ally = minor.allyCiv
            if (ally == them) {
                if (!us.isAtWarWith(minor)) continue
                if (trade.ourOffers.none { it.type == TradeOfferType.PeaceProposal && it.name == minor.civID })
                    trade.ourOffers.add(TradeOffer(minor.civID, TradeOfferType.PeaceProposal, 1, duration))
            } else if (ally == us) {
                if (!them.isAtWarWith(minor)) continue
                if (trade.theirOffers.none { it.type == TradeOfferType.PeaceProposal && it.name == minor.civID })
                    trade.theirOffers.add(TradeOffer(minor.civID, TradeOfferType.PeaceProposal, 1, duration))
            }
        }
    }
    //endregion

    //region Offers

    /**
     *  CvDealAI::IsMakeOfferForLuxuryResource: unless Hostile or planning war, ask for the first luxury (ruleset order) they have a spare
     *  copy of and we are allowed to get happiness from, then balance the deal (DoEqualizeDealWithAI between AIs).
     */
    fun isMakeOfferForLuxuryResource(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) return false
        val league = us.gameInfo.league
        val luxury = us.gameInfo.ruleset.tileResources.values.firstOrNull {
            it.resourceType == ResourceType.Luxury && league?.isLuxuryBanned(it.name) != true && numResourceAvailable(them, it, false) > 1
        } ?: return false
        val offer = TradeOffer(luxury.name, TradeOfferType.Luxury_Resource, 1, us.gameInfo.speed.dealDuration)
        if (!isPossibleToTradeItem(trade, them, us, offer, false, TradeLogic(us, them).theirAvailableOffers)) return false
        trade.theirOffers.add(offer)
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = false, dontChangeTheirExistingItems = true)
    }

    /** CvDiplomacyAI::WantsEmbassyAtPlayer: unless Hostile or planning war */
    fun wantsEmbassyAt(us: Civilization, them: Civilization): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        return approach != MajorCivApproach.Hostile && approach != MajorCivApproach.War
    }

    /** CvDealAI::MakeOfferForEmbassy: unless Hostile, Guarded or planning war, ask for an embassy in their capital and balance the deal */
    fun makeOfferForEmbassy(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War || approach == MajorCivApproach.Guarded) return false
        val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = us.gameInfo.speed)
        if (!isPossibleToTradeItem(trade, them, us, offer, false, TradeLogic(us, them).theirAvailableOffers)) return false
        if (!wantsEmbassyAt(us, them)) return false
        trade.theirOffers.add(offer)
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = false, dontChangeTheirExistingItems = true)
    }

    /**
     *  CvDiplomacyAI::IsWantsOpenBordersWithPlayer: the culture branch (CultureAutomation.wantsOpenBordersWith), else only with neighbours
     *  we are not Hostile, Guarded or Afraid toward and who are not stronger than us.
     */
    fun wantsOpenBordersWith(us: Civilization, them: Civilization): Boolean {
        if (CultureAutomation.wantsOpenBordersWith(us, them)) return true
        if (us.getProximity(them) != Proximity.Neighbors) return false
        val approach = us.ai.diplomacy.approachTowards(them, true)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.Guarded || approach == MajorCivApproach.Afraid) return false
        val strength = us.ai.diplomacy.militaryStrength(them)
        return strength == null || strength < StrengthLevel.Strong
    }

    /** CvDealAI::IsMakeOfferForOpenBorders: unless Hostile or planning war, ask for open borders we want and balance the deal */
    fun isMakeOfferForOpenBorders(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) return false
        val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, us.gameInfo.speed.dealDuration)
        if (!isPossibleToTradeItem(trade, them, us, offer, false, TradeLogic(us, them).theirAvailableOffers)) return false
        if (!wantsOpenBordersWith(us, them)) return false
        trade.theirOffers.add(offer)
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = false, dontChangeTheirExistingItems = true)
    }

    /** CvDealAI::IsMakeOfferForResearchAgreement: a research agreement on both sides, balanced (we may not change our side with a human) */
    fun isMakeOfferForResearchAgreement(us: Civilization, them: Civilization, trade: Trade): Boolean {
        if (!us.diplomacyFunctions.canSignResearchAgreementWith(them)) return false
        val cost = us.diplomacyFunctions.getResearchAgreementCost(them)
        val duration = us.gameInfo.speed.dealDuration
        trade.ourOffers.add(TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, cost, duration))
        trade.theirOffers.add(TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, cost, duration))
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = true, dontChangeTheirExistingItems = false)
    }

    /** CvDiplomacyAI::IsEmbassyExchangeAcceptable: not when planning war, Hostile or Guarded */
    fun isEmbassyExchangeAcceptable(us: Civilization, them: Civilization): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        return approach != MajorCivApproach.War && approach != MajorCivApproach.Hostile && approach != MajorCivApproach.Guarded
    }

    /** CvDiplomacyAI::IsOpenBordersExchangeAcceptable: only when Friendly or Afraid */
    fun isOpenBordersExchangeAcceptable(us: Civilization, them: Civilization): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        return approach == MajorCivApproach.Friendly || approach == MajorCivApproach.Afraid
    }

    /** CvDealAI::IsMakeOfferForDefensivePact (Vox Populi): a defensive pact on both sides, balanced */
    fun isMakeOfferForDefensivePact(us: Civilization, them: Civilization, trade: Trade): Boolean {
        if (!us.diplomacyFunctions.canSignDefensivePactWith(them)) return false
        trade.ourOffers.add(TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, speed = us.gameInfo.speed))
        trade.theirOffers.add(TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, speed = us.gameInfo.speed))
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = true, dontChangeTheirExistingItems = false)
    }

    /** CvDeal::GetItemTradeableState */
    private enum class RenewStatus { Renewable, NonRenewable, Supplemental }

    private fun renewStatus(offer: TradeOffer): RenewStatus = when (offer.type) {
        TradeOfferType.Gold_Per_Turn, TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> RenewStatus.Renewable
        TradeOfferType.Agreement -> if (offer.name == Constants.openBorders) RenewStatus.Renewable else RenewStatus.NonRenewable
        TradeOfferType.Treaty -> when (offer.name) {
            Constants.defensivePact -> RenewStatus.Renewable
            Constants.researchAgreement -> RenewStatus.Supplemental
            else -> RenewStatus.NonRenewable
        }
        TradeOfferType.Gold -> RenewStatus.Supplemental
        else -> RenewStatus.NonRenewable
    }

    /** CvDeal::IsPotentiallyRenewable: at least one renewable item (GPT, resources, open borders, defensive pact) and no non-renewable one */
    fun isPotentiallyRenewable(trade: Trade): Boolean {
        var hasRenewable = false
        for (offer in trade.ourOffers.asSequence() + trade.theirOffers.asSequence()) {
            when (renewStatus(offer)) {
                RenewStatus.NonRenewable -> return false
                RenewStatus.Renewable -> hasRenewable = true
                RenewStatus.Supplemental -> {}
            }
        }
        return hasRenewable
    }

    /** CvGameDeals::DoUpdateCurrentDeals moving an ended deal to the historic deals: an AI major keeps a renewable deal with a major for renewal */
    fun onDealEnded(civ: Civilization, other: Civilization, trade: Trade) {
        if (civ.isHuman() || !civ.isMajorCiv || !other.isMajorCiv || !isPotentiallyRenewable(trade)) return
        civ.ai.deals.expiredDeals.getOrPut(other.civID) { ArrayList() }.add(trade.clone())
    }

    /**
     *  CvDiplomacyAI::DoRenewExpiredDeal: not while a coop war with [them] is coming; the first ended deal with them whose every item can be
     *  traded again is put into [trade] (timed items for a new deal duration) and taken off the list (m_bCheckedForRenewal). With an AI it
     *  must then balance (DoEqualizeDealWithAI); with a human it is offered as it is.
     */
    fun doRenewExpiredDeal(us: Civilization, them: Civilization, trade: Trade): Boolean {
        if (us.ai.diplomacy.isCoopWarSoon(them)) return false
        val deals = us.ai.deals.expiredDeals[them.civID] ?: return false
        if (deals.isEmpty()) return false
        val logic = TradeLogic(us, them)
        val duration = us.gameInfo.speed.dealDuration
        val empty = Trade()
        for (old in deals) {
            val candidate = Trade()
            for (offer in old.ourOffers) candidate.ourOffers.add(renewed(offer, duration))
            for (offer in old.theirOffers) candidate.theirOffers.add(renewed(offer, duration))
            if (candidate.ourOffers.any { !isPossibleToTradeItem(empty, us, them, it, true, logic.ourAvailableOffers) }) continue
            if (candidate.theirOffers.any { !isPossibleToTradeItem(empty, them, us, it, false, logic.theirAvailableOffers) }) continue
            deals.remove(old)
            trade.set(candidate)
            if (!them.isHuman() && !doEqualizeDealWithAI(us, them, trade)) {
                trade.ourOffers.clear()
                trade.theirOffers.clear()
                return false
            }
            return true
        }
        return false
    }

    private fun renewed(offer: TradeOffer, duration: Int): TradeOffer {
        val copy = offer.copy()
        if (offer.duration >= 0 && offer.type != TradeOfferType.Gold) copy.duration = duration
        return copy
    }
    //endregion
}
