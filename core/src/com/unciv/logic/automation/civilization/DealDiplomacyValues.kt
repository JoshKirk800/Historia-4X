package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.BlockLevel
import com.unciv.logic.automation.diplomacy.CompetitorAi
import com.unciv.logic.automation.diplomacy.CoopWarAi
import com.unciv.logic.automation.diplomacy.CoopWarState
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.automation.diplomacy.WarSanityAi
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.logic.league.League
import com.unciv.logic.trade.TradeOffer
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.random.Random

/**
 *  What the diplomatic items of a deal are worth to the AI, as Vox Populi's Community Patch calculates them (CvDealAI.cpp and
 *  CvDiplomacyAI.cpp, Release-5.4.8; D7): open borders (CvDealAI::GetOpenBordersValue with CvDiplomacyAI::IsWillingToGiveOpenBordersToPlayer
 *  and IsWantsOpenBordersWithPlayer), peace with a third party (GetThirdPartyPeaceValue), war on a third party (GetThirdPartyWarValue) and
 *  votes in the World Congress (GetVoteCommitmentValue). A value is the one player's own, before the approach modifiers of
 *  [DealAi.getTradeItemValue]; `null` is the original's INT_MAX: the item cannot be part of a deal.
 *
 *  Not ported, as the inputs are not there: vassals (roadmap D3); the original's tourism flip of open borders
 *  (MOD_BALANCE_FLIPPED_OPEN_BORDERS_TOURISM, off in the Community Patch: only Vox Populi turns it on); a Defensive Pact with a vassal;
 *  CvDiplomacyAI::IsArmyInPlaceForAttack and MusteringForNeighborAttack; the victory dispute and block levels that CvDiplomacyAI::
 *  IsWillingToGiveOpenBordersToPlayer reads (VP's DoUpdateVictoryDisputeLevels is not ported, [com.unciv.logic.automation.diplomacy.DisputeAi]);
 *  the artifacts a civ dug up in our territory; the "pocket city" city strategy and the neighbour opinion; CvDiplomacyAI::IsPotentialWarTarget
 *  (the AI's potential war targets are not ported, [com.unciv.logic.automation.diplomacy.WarTargetAi]) and the demand target
 *  (GetDemandTargetPlayer); Nuclear Gandhi; the trade-route production siphon traits of Vox Populi.
 */
object DealDiplomacyValues {

    private fun rules(civ: Civilization) = civ.gameInfo.ruleset.ai

    private fun areClose(civ: Civilization, other: Civilization) =
        civ.getProximity(other).let { it == Proximity.Neighbors || it == Proximity.Close }

    private fun isDenouncedEitherWay(a: Civilization, b: Civilization) =
        OpinionAi.isDenouncedPlayer(a, b) || OpinionAi.isDenouncedPlayer(b, a)

    private fun hasDefensivePact(a: Civilization, b: Civilization) = a.getDiplomacyManager(b)?.hasFlag(DiplomacyFlags.DefensivePact) == true

    private fun isFriendOrAlly(civ: Civilization, other: Civilization) = OpinionAi.isDoFAccepted(civ, other) || hasDefensivePact(civ, other)

    //region Open borders

    /**
     *  CvDiplomacyAI::IsWillingToGiveOpenBordersToPlayer, Community Patch: not to an untrustworthy civ, one that culture-bombed us, one
     *  close to world conquest, one either side denounced; not, when we compete for victory, to one that is no pact partner or liberator
     *  and in a victory dispute or block with us, and that has influence over us VP wants to slow down (familiar and rising, popular
     *  and not falling or fiercely competitive or our biggest competitor, influential and not rising); not when we plan war on them;
     *  yes for a Declaration of Friendship or Defensive Pact, an Afraid, Friendly or Neutral approach, or a military no stronger than
     *  average. VP's artifact and army-in-place checks are not ported.
     */
    fun isWillingToGiveOpenBordersToPlayer(us: Civilization, them: Civilization): Boolean {
        if (OpinionAi.isUntrustworthyFriend(us, them)) return false
        if (us.ai.diplomacy.memoryOf(them).numTimesCultureBombed > 0) return false
        if (VictoryProgressAi.isCloseToWorldConquest(them, rules(us))) return false
        if (isDenouncedEitherWay(us, them)) return false
        if (isInfluenceRaceAgainst(us, them)) return false
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.War) return false
        if (isFriendOrAlly(us, them)) return true
        if (approach >= MajorCivApproach.Afraid) return true
        val strength = us.ai.diplomacy.militaryStrength(them)
        return strength == null || strength <= StrengthLevel.Average
    }

    /** The open borders block of IsWillingToGiveOpenBordersToPlayer for a victory competitor: see [isWillingToGiveOpenBordersToPlayer] */
    private fun isInfluenceRaceAgainst(us: Civilization, them: Civilization): Boolean {
        val memory = us.ai.diplomacy.memoryOf(them)
        if (!VictoryProgressAi.isCompetingForVictory(us) || hasDefensivePact(us, them) || memory.numCitiesLiberated > 0) return false
        val dispute = memory.victoryDisputeLevel
        val block = memory.victoryBlockLevel
        if (dispute == DisputeLevel.None && block == BlockLevel.None) return false
        val trend = them.culture.getInfluenceTrend(us)
        return when (them.culture.getInfluenceLevel(us)) {
            InfluenceLevel.Familiar -> trend == InfluenceTrend.Rising
            InfluenceLevel.Popular -> trend != InfluenceTrend.Falling || dispute == DisputeLevel.Fierce || block == BlockLevel.Fierce ||
                CompetitorAi.biggestCompetitor(us) == them.civID
            InfluenceLevel.Influential -> trend != InfluenceTrend.Rising
            else -> false
        }
    }

    /**
     *  CvDiplomacyAI::IsWantsOpenBordersWithPlayer, Community Patch: not with an untrustworthy civ or one either side denounced; always
     *  with one we need influence on when we go for the culture victory; not when we plan war on them; yes for a Declaration of Friendship
     *  or Defensive Pact, when we go for world conquest, an Afraid, Friendly or Neutral approach, when we are cramped or they have more
     *  than 1.5 times our cities, when we need to explore (a navy: when they have a coastal city).
     */
    fun isWantsOpenBordersWithPlayer(us: Civilization, them: Civilization): Boolean {
        if (OpinionAi.isUntrustworthyFriend(us, them)) return false
        if (isDenouncedEitherWay(us, them)) return false
        if (us.ai.isGoingForCultureVictory()) {
            val level = us.culture.getInfluenceLevel(them) ?: InfluenceLevel.Unknown
            val trend = us.culture.getInfluenceTrend(them)
            if (level < InfluenceLevel.Influential || trend == null || trend != InfluenceTrend.Rising) return true
        }
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.War) return false
        if (isFriendOrAlly(us, them)) return true
        if (us.ai.isGoingForWorldConquest()) return true
        if (approach >= MajorCivApproach.Afraid) return true
        if (us.ai.isCramped || them.cities.size * 3 > us.cities.size * 2) return true
        if (us.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON)) return true
        if (us.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON_SEA) && them.cities.any { it.isCoastal() }) return true
        return false
    }

    /**
     *  CvDealAI::GetOpenBordersValue, Community Patch: 10 and, giving them (not willing: [null]): by our approach 600 (Hostile, Guarded,
     *  War), 400 (Deceptive), 300 (Afraid), 200 (Neutral), 50 (Friendly), scaled by max(5, 100 - 5 per enemy of ours they fight) percent.
     *  Receiving them (not wanting them: [null]): by our approach 0 (War, Hostile, Deceptive, Guarded), 200 (Afraid), 100 (Friendly),
     *  50 (Neutral), by their proximity 70 / 80 / 90 / 110 percent (nothing at all for none), half when they have one city, x125% when we
     *  are cramped and they have more cities than we, x115% for each of the two recon strategies, and with tourism x200% for the civ we
     *  need influence on most and x200% when we are influential on half the civs and they are not. At least one GPT's value.
     */
    fun openBordersValue(us: Civilization, them: Civilization, fromMe: Boolean): Int? {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        var value = 10L
        if (fromMe) {
            if (!isWillingToGiveOpenBordersToPlayer(us, them)) return null
            value += when (approach) {
                MajorCivApproach.Hostile, MajorCivApproach.Guarded, MajorCivApproach.War -> 600
                MajorCivApproach.Deceptive -> 400
                MajorCivApproach.Afraid -> 300
                MajorCivApproach.Friendly -> 50
                MajorCivApproach.Neutral -> 200
            }
            val enemiesAtWarWith = us.ai.diplomacy.numOurEnemiesAtWarWith(them)
            if (enemiesAtWarWith > 0) value = value * max(5, 100 - enemiesAtWarWith * 5) / 100
        } else {
            if (!isWantsOpenBordersWithPlayer(us, them)) return null
            value += when (approach) {
                MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Deceptive, MajorCivApproach.Guarded -> 0
                MajorCivApproach.Afraid -> 200
                MajorCivApproach.Friendly -> 100
                MajorCivApproach.Neutral -> 50
            }
            value = when (us.getProximity(them)) {
                Proximity.Distant -> value * 70 / 100
                Proximity.Far -> value * 80 / 100
                Proximity.Close -> value * 90 / 100
                Proximity.Neighbors -> value * 110 / 100
                Proximity.None -> 0
            }
            if (them.cities.size == 1) value = value * 50 / 100
            if (us.ai.isCramped && them.cities.size > us.cities.size) value = value * 125 / 100
            if (us.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON)) value = value * 115 / 100
            if (us.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON_SEA)) value = value * 115 / 100
            if (us.ai.tourism() > 0) {
                if (CultureAutomation.getCivLowestInfluence(us) == them) value = value * 200 / 100
                if (CultureAutomation.isInfluentialOnHalf(us) && CultureAutomation.isBelowInfluential(us, them)) value = value * 200 / 100
            }
        }
        return max(value.toInt(), DealResourceValues.oneGptValue(us, false))
    }

    //endregion

    //region Third parties

    /**
     *  CvDealAI::GetThirdPartyPeaceValue, Community Patch. Peace with a city-state allied to either side, in a peace deal between us: 0.
     *  Not with a denounced civ either way or an untrustworthy one ([null]).
     *
     *  Giving it (we make peace with [third]): 100 + 15 per point of our war score against [third] + 50 per era of ours. A city-state:
     *  never when we plan its conquest, +200 when we bully it. A major: never when it took our capital or holy city, we are close to
     *  world conquest or aggressive for the endgame towards it; x5 when our approach to it is Hostile or War, x2.5 Deceptive, x1.5 Guarded;
     *  x3 when we want it conquered (world conquest or Unforgivable) and plan war on it; x2 when it is our biggest competitor and our approach
     *  Hostile or worse, x1.5 another major competitor we plan war on. Then by our approach to [them]: x6 War or Hostile, x1.5 Deceptive,
     *  x1.25 Guarded or Neutral, x0.75 Afraid or Friendly. Halved when [third] is not an easy target.
     *
     *  Receiving it ([them] make peace with [third]): 100 + 5 per point of their war score + 25 per era of ours. A major: x2 when our
     *  approach to it is Friendly, x0.5 Neutral, else [null]. A city-state: divided by 50, x3 when we protect it or are its ally, x1.5 when our
     *  approach is Friendly, else [null]. By our approach to [them]: [null] War or Hostile, x1.5 Deceptive, x1.25 Guarded, Afraid or Neutral, x2 Friendly.
     *  Halved when [third] is not an easy target for them.
     */
    fun thirdPartyPeaceValue(us: Civilization, them: Civilization, third: Civilization, fromMe: Boolean): Int? {
        if (third.isCityState && us.isAtWarWith(them)) {
            val ally = third.allyCiv
            if (ally == them || ally == us) return 0
        }
        if (isDenouncedEitherWay(us, them)) return null
        if (OpinionAi.isUntrustworthyFriend(us, them)) return null

        val diplomacy = us.ai.diplomacy
        val era = us.getAiEraNumber()
        var value = 100L
        if (fromMe) {
            value += WarDamageAi.warScore(us, third) * 15L
            value += era * 50L
            if (third.isCityState) {
                when (us.ai.minorCivApproaches[third.civID]) {
                    MinorCivApproach.Conquest -> return null
                    MinorCivApproach.Bully -> value += 200
                    else -> {}
                }
            } else {
                if (OpinionAi.isCapitalCapturedBy(us, third) || CompetitorAi.isHolyCityCapturedBy(us, third) ||
                    VictoryProgressAi.isCloseToWorldConquest(us, rules(us)) || VictoryProgressAi.isEndgameAggressiveTo(us, third, rules(us))) return null
                val approachToThird = diplomacy.approachTowards(third, false)
                value = when {
                    approachToThird <= MajorCivApproach.Hostile -> value * 500 / 100
                    approachToThird == MajorCivApproach.Deceptive -> value * 250 / 100
                    approachToThird == MajorCivApproach.Guarded -> value * 150 / 100
                    else -> value
                }
                val wantsConquest = us.ai.isGoingForWorldConquest() || diplomacy.opinionOf(third) == MajorCivOpinion.Unforgivable
                if (wantsConquest && approachToThird == MajorCivApproach.War) value *= 3
                if (CompetitorAi.biggestCompetitor(us) == third.civID && approachToThird <= MajorCivApproach.Hostile) value *= 2
                else if (CompetitorAi.isMajorCompetitor(us, third) && approachToThird == MajorCivApproach.War) value = value * 150 / 100
            }
            value *= when (diplomacy.approachTowards(them, true)) {
                MajorCivApproach.War, MajorCivApproach.Hostile -> 600
                MajorCivApproach.Deceptive -> 150
                MajorCivApproach.Guarded, MajorCivApproach.Neutral -> 125
                MajorCivApproach.Afraid, MajorCivApproach.Friendly -> 75
            }
            value /= 100
            if (!WarStateAi.isEasyTarget(us, third)) value /= 2
        } else {
            value += WarDamageAi.warScore(them, third) * 5L
            value += era * 25L
            if (!third.isCityState) {
                value = when (diplomacy.approachTowards(third, false)) {
                    MajorCivApproach.Friendly -> value * 200 / 100
                    MajorCivApproach.Neutral -> value * 50 / 100
                    else -> return null
                }
            } else {
                value /= 50
                value = when {
                    third.cityStateFunctions.getProtectorCivs().contains(us) || third.allyCiv == us -> value * 300 / 100
                    us.ai.minorCivApproaches[third.civID] == MinorCivApproach.Friendly -> value * 150 / 100
                    else -> return null
                }
            }
            value *= when (diplomacy.approachTowards(them, true)) {
                MajorCivApproach.War, MajorCivApproach.Hostile -> return null
                MajorCivApproach.Deceptive -> 150
                MajorCivApproach.Guarded, MajorCivApproach.Afraid, MajorCivApproach.Neutral -> 125
                MajorCivApproach.Friendly -> 200
            }
            value /= 100
            if (!WarStateAi.isEasyTarget(them, third)) value /= 2
        }
        return value.toInt()
    }

    /**
     *  A basic unit's gold cost in the capital (CvPlayer::GetCompetitiveSpawnUnitType, CvCity::GetPurchaseCost): the strongest land
     *  combat unit the capital can build, else 100. The original draws among the best few; this takes the best.
     */
    private fun basicUnitCost(civ: Civilization): Int {
        val capital = civ.getCapital() ?: return 100
        val unit = capital.cityConstructions.getConstructableUnits()
            .filter { !it.isCivilian() && it.isLandUnit && it.uniqueTo == null }
            .maxByOrNull { it.getAiPower() } ?: return 100
        return unit.getStatBuyCost(capital, Stat.Gold) ?: 100
    }

    /**
     *  CvDealAI::GetThirdPartyWarValue, Community Patch: the declarer ([us] when we give it, [them] when we receive it) must have a capital;
     *  no value with a denounced or untrustworthy civ, in a war on a city-state, on a major the declarer is Afraid of, with the declarer's "no
     *  new wars" state, close to a victory, without a preferred attack target in [third], at war with a major and not winning all its wars, with a
     *  dangerous major at war, when the war is not sane for it, when [third] is not at least close, when peace was made within 23 to 37 turns
     *  after a war, in a coop war preparing against [third]. An AI declarer also refuses a war that brings in a defensive ally that is
     *  close and Afraid-of, or stronger than average (a major it plans no war on; a city-state it is not hostile to), or that would not be
     *  sane against it, and one on a civ that is not its competitor and that it is Afraid of or worse towards the asker.
     *
     *  Otherwise a basic unit's cost in the declarer's capital x the WarmongerHate of the one who judges it (half of it when we
     *  receive the war): x0.5 for the declarer's biggest competitor, x0.75 another major competitor; x0.75 when its approach to [third] is War,
     *  x0.9 Hostile, else x1.25.
     */
    fun thirdPartyWarValue(us: Civilization, them: Civilization, third: Civilization, fromMe: Boolean): Int? {
        val declarer = if (fromMe) us else them
        declarer.getCapital() ?: return null
        if (isDenouncedEitherWay(us, them)) return null
        if (OpinionAi.isUntrustworthyFriend(us, them)) return null

        val declarerAi = declarer.ai.diplomacy
        val rules = rules(us)
        val approachToThird = declarerAi.approachTowards(third, false)
        if (third.isCityState) return null
        if (third.isMajorCiv && approachToThird == MajorCivApproach.Afraid) return null
        if (WarStateAi.isNoNewWars(declarer)) return null
        if (VictoryProgressAi.isCloseToAnyVictoryCondition(declarer, rules)) return null
        if (!declarer.ai.military.havePreferredAttackTarget(third)) return null
        val atWarWithMajor = declarer.gameInfo.civilizations.any { it.isMajorCiv && it.isAlive() && declarer.isAtWarWith(it) }
        if (atWarWithMajor && WarStateAi.stateAllWars(declarer) != StateAllWars.Winning) return null
        if (WarStateAi.numDangerousMajorsAtWarWith(declarer, excludePhonyWars = true, excludeIfNoTarget = false) > 0) return null
        if (!WarSanityAi.isWarSane(declarer, third)) return null
        if (!areClose(declarer, third)) return null

        val memory = declarerAi.memoryOf(third)
        if (memory.numWarsFought > 0 && memory.turnMadePeace > -1) {
            val turnsSincePeace = declarer.gameInfo.turns - memory.turnMadePeace
            if (turnsSincePeace < 23 + Random(declarer.civID.hashCode()).nextInt(15)) return null
        }

        if (!declarer.isHuman()) {
            for (ally in WarAlliesAi.warAllies(declarer, third, includeMinors = true, reverse = true, defensive = true, newWarsOnly = true)) {
                if (!areClose(ally, declarer)) continue
                val strength = WarStateAi.militaryStrengthComparedToUs(declarer, ally)
                if (ally.isMajorCiv) {
                    val allyApproach = declarerAi.approachTowards(ally, false)
                    if (allyApproach == MajorCivApproach.Afraid) return null
                    if (allyApproach != MajorCivApproach.War && strength != null && strength > StrengthLevel.Average) return null
                    if (!WarSanityAi.isWarSane(declarer, ally)) return null
                } else {
                    val minorApproach = declarer.ai.minorCivApproaches[ally.civID]
                    if ((minorApproach == MinorCivApproach.Ignore || minorApproach == MinorCivApproach.Friendly || minorApproach == MinorCivApproach.Protective) &&
                        strength != null && strength > StrengthLevel.Average) return null
                }
            }
        }

        // A coop war already being prepared against them: no need for a bribe
        if (third.isMajorCiv) {
            val coop = CoopWarAi.globalState(declarer, third)
            if (coop != null && coop >= CoopWarState.Soon) return null
        }

        var value = basicUnitCost(declarer).toLong()
        val hate = us.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate)
        value *= if (fromMe) hate else hate / 2

        if (!declarer.isHuman()) {
            if (CompetitorAi.biggestCompetitor(declarer) == third.civID) value /= 2
            else if (third.isMajorCiv && CompetitorAi.isMajorCompetitor(declarer, third)) value = value * 75 / 100
            else if (declarerAi.approachTowards(if (fromMe) them else us, false) < MajorCivApproach.Afraid) return null
        }

        value = when {
            approachToThird == MajorCivApproach.War -> value * 75 / 100
            approachToThird <= MajorCivApproach.Hostile -> value * 90 / 100
            else -> value * 125 / 100
        }
        return value.toInt()
    }

    //endregion

    //region Votes

    /**
     *  CvDealAI::GetVoteCommitmentValue, Community Patch, for the whole commitment. Giving our votes: 2 x the deal's turns + the turns x the
     *  votes x 100 / 60 / 40 / 30 / 20 / 10 / 5 / 2 for a choice we strongly dislike ... always like (never: [null]), then x1 for a
     *  liberator, leader or teammate of ours in the World Congress, x1.1 ally, x1.2 confidant, x1.3 friend, x1.5 neutral, x2 rival, x2.5 hatred,
     *  x3 enemy, x5 war. Never to vote on a matter of our own (a sanction on us, or a repeal of one), nor against a friend's sanction nor for
     *  sanctioning one (a friend, ally, liberator or a civ we prepare a coop war with that we are not hostile to); for the World Leader
     *  vote never when we have half the votes needed, else x20.
     *  Receiving votes: the turns x the votes x 0 (never, ... neutral) / 2 / 4 / 10 / 20 for a choice we weakly like ... always like,
     *  x1.2 for a liberator, leader or teammate, x1.1 ally, x0.9 rival, x0.8 hatred, x0.7 enemy or war. Never on a matter of their own, nor the
     *  sanction of their friend; for the World Leader vote only when the votes reach what is needed and the choice is us (x10).
     */
    fun voteCommitmentValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean): Int? {
        val commitment = VoteCommitmentItem.parse(offer) ?: return null
        val votes = offer.amount
        if (votes == 0) return null
        val league = us.gameInfo.league ?: return null
        val duration = us.gameInfo.speed.dealDuration
        val desire = LeagueAutomation.evaluateVoteForTrade(us, commitment.proposalId, commitment.choice, votes, commitment.repeal)
        val alignment = LeagueAutomation.evaluateAlignment(us, them)
        val proposal = league.proposals.firstOrNull { it.id == commitment.proposalId && it.repeal == commitment.repeal }
        val resolutions = us.gameInfo.ruleset.resolutions
        // The player a proposal is about (an embargo's target), and whether it sanctions him
        val resolutionName = if (proposal != null && proposal.repeal)
            league.activeResolutions.firstOrNull { it.id == proposal.targetId }?.resolution else proposal?.resolution
        val resolution = resolutionName?.let { resolutions[it] }
        val targetId = if (proposal != null && proposal.repeal)
            league.activeResolutions.firstOrNull { it.id == proposal.targetId }?.choice else proposal?.proposerChoice
        val target = targetId?.let { us.gameInfo.getCivilizationOrNull(it) }?.takeIf { it.isMajorCiv }
        val isSanction = resolution != null && resolution.embargoesPlayer && target != null
        // Voting yes on an enactment or no on a repeal is for the sanction; the other choice against it
        val forSanction = commitment.choice == (if (commitment.repeal) League.NO else League.YES)

        var value = 0L
        if (fromMe) {
            value += 2L * duration
            value += votes.toLong() * duration * when (desire) {
                LeagueDesire.NEVER -> return null
                LeagueDesire.STRONG_DISLIKE -> 100
                LeagueDesire.DISLIKE -> 60
                LeagueDesire.WEAK_DISLIKE -> 40
                LeagueDesire.NEUTRAL -> 30
                LeagueDesire.WEAK_LIKE -> 20
                LeagueDesire.LIKE -> 10
                LeagueDesire.STRONG_LIKE -> 5
                LeagueDesire.ALWAYS -> 2
            }
            value = value * when (alignment) {
                LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER, LeagueAlignment.SELF -> 100
                LeagueAlignment.ALLY -> 110
                LeagueAlignment.CONFIDANT -> 120
                LeagueAlignment.FRIEND -> 130
                LeagueAlignment.NEUTRAL -> 150
                LeagueAlignment.RIVAL -> 200
                LeagueAlignment.HATRED -> 250
                LeagueAlignment.ENEMY -> 300
                LeagueAlignment.WAR -> 500
            } / 100
            if (proposal != null) {
                if (resolution?.isWorldLeader == true && !commitment.repeal) {
                    val ourPercent = league.getDelegates(us) * 100 / max(1, league.getVotesNeededForWorldLeader())
                    if (ourPercent >= 50) return null
                    value *= 20
                } else if (target != null) {
                    // Not asked to vote on what concerns us, nor to sanction or unsanction a friend
                    if (target == us) return null
                    if (isSanction && forSanction) {
                        val approach = us.ai.diplomacy.approachTowards(target, false)
                        val coop = CoopWarAi.globalState(us, target)
                        if (approach > MajorCivApproach.Deceptive && (isFriendOrAlly(us, target) || WarSanityAi.isLiberator(us, target, false) ||
                            (coop != null && coop >= CoopWarState.Soon))) return null
                    }
                }
            }
        } else {
            value += votes.toLong() * duration * when (desire) {
                LeagueDesire.WEAK_LIKE -> 2
                LeagueDesire.LIKE -> 4
                LeagueDesire.STRONG_LIKE -> 10
                LeagueDesire.ALWAYS -> 20
                else -> 0
            }
            value = value * when (alignment) {
                LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER, LeagueAlignment.SELF -> 120
                LeagueAlignment.ALLY -> 110
                LeagueAlignment.RIVAL -> 90
                LeagueAlignment.HATRED -> 80
                LeagueAlignment.ENEMY, LeagueAlignment.WAR -> 70
                else -> 100
            } / 100
            if (proposal != null) {
                if (resolution?.isWorldLeader == true && !commitment.repeal) {
                    if (league.getDelegates(us) + votes < league.getVotesNeededForWorldLeader() || commitment.choice != us.civID) return null
                    value *= 10
                } else if (target != null) {
                    // We do not ask them about what concerns themselves, nor to sanction or unsanction their friend
                    if (target == them) return null
                    if (isSanction && forSanction && (isFriendOrAlly(them, target) || WarSanityAi.isLiberator(them, target, false))) return null
                }
            }
        }
        return max(value, 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    //endregion
}
