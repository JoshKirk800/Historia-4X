package com.unciv.logic.automation.unit

import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.TradeRouteCandidate
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Vox Populi's CvTradeAI::ScoreInternationalTR (CvTradeClasses.cpp, Release-5.4.8) for one scoring pass of one civilization: make one instance
 * per civilization and pass (it remembers the per-civilization facts the function asks again for every connection) and call [score] for each
 * candidate. [pathScore] is the shared [VpTradePathScore] whose [VpTradePathScore.apply] is the function's last step
 * (CvTradeAI::ApplyTradeRouteModifier with the path's iDangerScore, built by AddTradePathToCache).
 *
 * Order of the function, kept here: refusals (not international; an offensive operation against the owner of the destination or exchanges
 * to be avoided with them, [WarAlliesAi.avoidExchangesWithPlayer] war only; an origin city under siege; a destination under siege; the
 * connection plundered lately) - gold delta (our gold less theirs, theirs a tenth when we like them, none for a city-state; squared-root
 * emphasis when the treasury loses gold; a city-state route is worth at least 1) / max(1, 10 - FLAVOR_GOLD) - science delta x FLAVOR_SCIENCE
 * - culture delta x FLAVOR_CULTURE - religion delta x (FLAVOR_RELIGION / 2); a sum of 0 or less scores 0 - x era (at least 1) - x10 for a
 * city-state we have a Feitoria in - x5 towards a major we are not connected to when we have tourism and go (or are close) for culture - divided
 * by the civilizations we are at war with (at least 2) - the path's danger.
 *
 * Mapping to what Unciv has:
 * - Yields are those of [com.unciv.logic.civilization.managers.TradeRouteManager.preview], i.e. Brave New World's rules stay: the gold of
 *   each end (CvPlayerTrade::GetTradeConnectionValueTimes100), and the science of each end (the half of the technologies the other knows and we
 *   do not, plus the influence bonus). VP's own estimate of that science (a divisor of 125 instead of 200, multiplied by the era, plus the
 *   policy bump) is not applied, as it estimates a yield BNW does not pay. Culture is read from the route's culture yields (none in BNW,
 *   0 today). The "other side" terms keep VP's /10 (gold, when we are friends, approach friendly or opinion neutral or better) and /3 (science
 *   and culture, friends, approach friendly or opinion friend or better); "friends" is a declaration of friendship or defensive pact
 *   (CvDiplomacyAI::IsFriendOrAlly), the approach and the opinion those of [com.unciv.logic.automation.diplomacy.DiplomacyAi].
 * - VP's domain-specific tourism bonuses come from building fields not present in BNW; they contribute zero here.
 * - Religion: the pressure terms as in VP (pressures divided by RELIGION_MISSIONARY_PRESSURE_MULTIPLIER, 10), with our state religion
 *   ([OpinionAi.stateReligion], CvPlayerReligions::GetStateReligion) and the accumulated pressures of the cities.
 * - The era is [Civilization.getAiEraNumber] (CvPlayer::GetCurrentEra); FLAVOR_* come from [com.unciv.logic.automation.strategy.CivAi.personalityAndGrandStrategy].
 * - The gold of the treasury (CvTreasury::CalculateBaseNetGold) is the net gold of the next turn; the number of civilizations at war is
 *   [WarStateAi.numberCivsAtWarWith] (CvMilitaryAI::GetNumberCivsAtWarWith without city-states). Going for / close to culture victory:
 *   [com.unciv.logic.automation.strategy.CivAi.isGoingForCultureVictory], [VictoryProgressAi.isCloseToCultureVictory]; the siphon of a
 *   city-state (CvMinorCivAI::IsSiphoned) is one of our Feitorias
 *   on a tile of the city-state that [UniqueType.SiphonsLuxuriesFromCityState] gives luxuries from.
 *
 * Not present in the rules, so left out (each of them is a zero or unchanged term here):
 * corporations (invulnerable routes, franchises, offices, GP rate per franchise, free franchise above Popular, the office / franchise
 * score), foreign-territory route yields, production siphon, no open trade, plunder without war, city yields from the end of a route,
 * a city's poverty, illiteracy and boredom, the yields from passing routes and terrain (none in BNW).
 * Also omitted: the city-state production building (x25), event tourism, the protected-minor policy, strategic trade partners
 * and the extra culture and science policy bump. The siege test is [CityDangerAi.isUnderSiege] and, as there are no invulnerable routes, the
 * invulnerable branch of the origin's siege (falling, enemy-dominated zone) never applies.
 */
class VpInternationalTradeScore(private val civ: Civilization, private val pathScore: VpTradePathScore) {

    private val rules = civ.gameInfo.ruleset.ai
    private val haveTourism: Boolean by lazy { civ.ai.tourism() > 0 }
    private val netGold: Int by lazy { civ.stats.statsForNextTurn.gold.toInt() }
    private val goingForCulture: Boolean by lazy { civ.ai.isGoingForCultureVictory() || VictoryProgressAi.isCloseToCultureVictory(civ, rules) }
    private val numCivsAtWar: Int by lazy { WarStateAi.numberCivsAtWarWith(civ) }
    private val atWar: Boolean by lazy { civ.isAtWar() }
    private val stateReligion: String? by lazy { OpinionAi.stateReligion(civ)?.first?.name }
    private val connected = HashMap<String, Boolean>()
    private val siphoned = HashMap<String, Boolean>()

    /** CvTradeAI::ScoreInternationalTR: the score of [candidate], 0 if it is not to be sent */
    fun score(candidate: TradeRouteCandidate): Int {
        val destination = candidate.destination
        if (destination.kind != TradeRouteKind.International) return 0
        val toCity = destination.city
        val fromCity = candidate.origin
        val partner = toCity.civ

        // Don't send trade routes if we're about to declare war
        if (civ.ai.operations.hasAnyOffensiveOperationsAgainst(partner)) return 0
        if (WarAlliesAi.avoidExchangesWithPlayer(civ, partner, warOnly = true)) return 0
        // War near our origin city; a besieged destination (the city may not do well, and the new owner may not suit us); a plundered connection
        if (CityDangerAi.isUnderSiege(fromCity)) return 0
        if (CityDangerAi.isUnderSiege(toCity)) return 0
        val manager = civ.tradeRoutes
        if (manager.wasPlunderedRecently(fromCity, toCity)) return 0

        val unitType = manager.getTradeUnitType(candidate.isSea) ?: return 0
        val route = manager.preview(unitType, fromCity, destination) ?: return 0

        val ai = civ.ai
        val isMajor = partner.isMajorCiv
        val friendOrAlly = isMajor && isFriendOrAlly(partner)
        val approach = if (isMajor) ai.diplomacy.approachTowards(partner) else MajorCivApproach.Neutral
        val opinion = if (isMajor) ai.diplomacy.opinionOf(partner) else MajorCivOpinion.Neutral
        val friendlyApproach = approach == MajorCivApproach.Friendly
        // IsStrategicTradePartner has no input here: always false

        // Gold
        var goldAmount = (route.originYields.gold * 100f).roundToInt().toLong()
        // Emphasize gold if we are in the red
        if (netGold < -1) goldAmount *= sqrt((-netGold).toFloat()).toInt()

        var otherGoldAmount = if (partner.isCityState) 0L else (route.destinationYields.gold * 100f).roundToInt().toLong()
        // If we are friends with the player, we do not care about how much gold they make
        if (friendOrAlly || friendlyApproach || opinion >= MajorCivOpinion.Neutral) otherGoldAmount /= 10
        var goldDelta = goldAmount - otherGoldAmount
        // Out of a logjam: a city-state route is worth trying even if it gives us less than nothing
        if (goldDelta <= 0 && partner.isCityState) goldDelta = 1

        // Science and culture of each end; we do not care so much what a friend makes
        val caresLess = friendOrAlly || friendlyApproach || opinion >= MajorCivOpinion.Friend
        var otherScience = route.destinationYields.science.roundToInt()
        var otherCulture = route.destinationYields.culture.roundToInt()
        if (caresLess) {
            otherScience /= 3
            otherCulture /= 3
        }
        val scienceDelta = route.originYields.science.roundToInt() - otherScience
        val cultureDelta = route.originYields.culture.roundToInt() - otherCulture

        val religionDelta = religionDelta(fromCity, toCity)

        val flavorGold = ai.personalityAndGrandStrategy(Flavor.Gold)
        val flavorScience = ai.personalityAndGrandStrategy(Flavor.Science)
        val flavorCulture = ai.personalityAndGrandStrategy(Flavor.Culture)
        val flavorReligion = ai.personalityAndGrandStrategy(Flavor.Religion)
        var score: Long = goldDelta / max(1, 10 - flavorGold) +
            scienceDelta.toLong() * flavorScience + cultureDelta.toLong() * flavorCulture + religionDelta.toLong() * (flavorReligion / 2)
        // Abort if we are negative
        if (score <= 0) return 0

        score *= max(1, civ.getAiEraNumber()) // more international trade late game, please

        // Encourage routes to Feitorias
        if (partner.isCityState && isSiphoned(partner)) score *= 10

        val isConnected = isConnected(partner)
        // If we have tourism and are not connected to a major, multiply by 5 if going for a culture victory
        if (isMajor && haveTourism && !isConnected && goingForCulture) score *= 5
        if (!isConnected && pathScore.partnerYield > 0) score *= 10

        if (atWar) score /= max(2, numCivsAtWar)

        // Account for the danger along the path
        val result = pathScore.apply(score, destination.path)
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** CvDiplomacyAI::IsFriendOrAlly: a declaration of friendship or a defensive pact */
    private fun isFriendOrAlly(other: Civilization): Boolean =
        OpinionAi.isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** CvGameTrade::IsPlayerConnectedToPlayer: a route from one to the other, either way */
    private fun isConnected(other: Civilization): Boolean = connected.getOrPut(other.civID) {
        civ.tradeRoutes.routes.any { it.getDestinationCiv() == other } || other.tradeRoutes.routes.any { it.getDestinationCiv() == civ }
    }


    /**
     * The religion term: the pressure of our state religion the route would carry to them, less that of another religion it would carry back,
     * each weighted by how much it adds to the pressure already accumulated, when the two religions differ.
     */
    private fun religionDelta(fromCity: City, toCity: City): Int {
        val ownReligion = stateReligion ?: return 0
        val toward = fromCity.religion.wouldExertTradeRoutePressureToward(toCity)
        val back = toCity.religion.wouldExertTradeRoutePressureToward(fromCity)
        if (toward == null && back == null) return 0
        val toReligion = toward?.first
        val fromReligion = back?.first
        if (toReligion == fromReligion) return 0

        var toPressure = (toward?.second ?: 0) / PRESSURE_MULTIPLIER
        var fromPressure = (back?.second ?: 0) / PRESSURE_MULTIPLIER
        val existingToPressureAtFrom = fromReligion?.let { fromCity.religion.getPressures()[it] } ?: 0
        val existingGoodPressureAtTo = toCity.religion.getPressures()[ownReligion]
        if (toReligion != ownReligion) toPressure = 0
        if (fromReligion == ownReligion) fromPressure = 0
        val modFrom = sqrt(ln(max(1, existingToPressureAtFrom + fromPressure).toDouble() / max(1, existingToPressureAtFrom)) / LN2)
        val modTo = sqrt(ln(max(1, existingGoodPressureAtTo + toPressure).toDouble() / max(1, existingGoodPressureAtTo)) / LN2)
        return (toPressure * modTo / PRESSURE_MULTIPLIER + 0.5).toInt() - (fromPressure * modFrom / PRESSURE_MULTIPLIER + 0.5).toInt()
    }

    /** CvMinorCivAI::IsSiphoned: we have an unpillaged improvement that siphons luxuries on a tile of the city-state */
    private fun isSiphoned(cityState: Civilization): Boolean = siphoned.getOrPut(cityState.civID) {
        cityState.cities.any { city ->
            city.getTiles().any { tile ->
                tile.getOwner() == cityState && tile.improvementBuilder == civ.civID && !tile.improvementIsPillaged &&
                    tile.tileImprovement?.hasUnique(UniqueType.SiphonsLuxuriesFromCityState) == true
            }
        }
    }

    private companion object {
        /** RELIGION_MISSIONARY_PRESSURE_MULTIPLIER: the pressure shown to the user is the internal one divided by this */
        const val PRESSURE_MULTIPLIER = 10
        val LN2 = ln(2.0)
    }
}
