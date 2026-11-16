package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.CityStateAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.models.Religion
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  CvDiplomacyAI::DoUpdateOpinions / DoUpdateOnePlayerOpinion / CalculateCivOpinionWeight: one weight per known major (positive = bad),
 *  mapped to the seven opinion levels by the OPINION_THRESHOLD_* defines. Vox Populi's frame (`phaseE/vp-opinion-plan.md`): its
 *  thresholds, its human shadow AI rule, its base score and its dispute, expander, wonder spammer and military posture scores; the
 *  other terms are still BNW's until the plan's later branches.
 *
 *  The terms are written each turn into the [DiplomacyManager.diplomaticModifiers] of the pair, sign flipped (Unciv shows positive = good), so the
 *  diplomacy screen lists them and [DiplomacyManager.opinionOfOtherCiv] equals minus the weight. Terms the original computes but that Unciv has no game
 *  action for are left out: forgave for spying, gave assistance / paid tribute (always 0 in the original), the city-state attack and
 *  bully promises (no such request in Unciv), the scenario modifiers.
 */
object OpinionAi {

    /** Every modifier this computation owns: all of them are rewritten on each update */
    private val OWNED_TERMS = DiplomaticModifiers.entries.filter { it.isOpinionTerm }

    fun updateOpinions(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            if (!other.isMajorCiv) continue
            updateOpinion(civ, other, rules)
        }
    }

    /**
     *  CvDiplomacyAI::DoUpdateOnePlayerOpinion (Unciv has no teams). A human's shadow AI does not weigh: enemy at war or having denounced
     *  them, friend with a declaration of friendship or a defensive pact, else neutral (its terms are still written for the diplomacy
     *  screen).
     */
    fun updateOpinion(civ: Civilization, other: Civilization, rules: AiRuleset = civ.gameInfo.ruleset.ai) {
        val diplomacy = civ.getDiplomacyManager(other) ?: return
        val weight = opinionWeight(civ, other, rules, diplomacy)
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (civ.isHuman()) {
            memory.opinion = when {
                civ.isAtWarWith(other) || isDenouncedPlayer(civ, other) -> MajorCivOpinion.Enemy
                isDoFAccepted(civ, other) || diplomacy.diplomaticStatus == DiplomaticStatus.DefensivePact -> MajorCivOpinion.Friend
                else -> MajorCivOpinion.Neutral
            }
            return
        }
        memory.opinionWeight = weight
        memory.opinion = opinionLevel(weight, rules)
    }

    /** The OPINION_THRESHOLD_* mapping of DoUpdateOnePlayerOpinion: at or above the unfriendly thresholds, strictly above the friendly ones */
    fun opinionLevel(weight: Int, rules: AiRuleset): MajorCivOpinion = when {
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_UNFORGIVABLE) -> MajorCivOpinion.Unforgivable
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_ENEMY) -> MajorCivOpinion.Enemy
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_COMPETITOR) -> MajorCivOpinion.Competitor
        weight > rules.define(AiDefine.OPINION_THRESHOLD_FAVORABLE) -> MajorCivOpinion.Neutral
        weight > rules.define(AiDefine.OPINION_THRESHOLD_FRIEND) -> MajorCivOpinion.Favorable
        weight > rules.define(AiDefine.OPINION_THRESHOLD_ALLY) -> MajorCivOpinion.Friend
        else -> MajorCivOpinion.Ally
    }

    /**
     *  CvDiplomacyAI::GetMajorCivOpinionWeight. With [write], also sets each term on that diplomacy manager (opinion = minus the term)
     *  and removes the terms that are 0.
     */
    fun opinionWeight(civ: Civilization, other: Civilization, rules: AiRuleset, write: DiplomacyManager? = null): Int {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        var total = 0
        fun term(modifier: DiplomaticModifiers, weight: Int) {
            total += weight
            if (write == null) return
            if (weight == 0) write.removeModifier(modifier) else write.setModifier(modifier, -weight.toFloat())
        }
        if (write != null) for (modifier in OWNED_TERMS) write.removeModifier(modifier)

        // VP's base score: the difficulty's opinion change
        term(DiplomaticModifiers.BaseOpinion, baseOpinionScore(civ, other))
        val land = landDisputeScore(civ, other, rules)
        term(if (land < 0) DiplomaticModifiers.NoLandDispute else DiplomaticModifiers.LandDispute, land)
        term(DiplomaticModifiers.WonderDispute, wonderDisputeScore(civ, other, rules))
        term(DiplomaticModifiers.MinorCivDispute, minorCivDisputeScore(civ, other, rules))
        term(DiplomaticModifiers.RecklessExpander, recklessExpanderScore(civ, other, rules))
        term(DiplomaticModifiers.WonderSpammer, wonderSpammerScore(civ, other, rules))
        term(DiplomaticModifiers.MilitaryPosture, militaryPostureScore(civ, other, rules))
        term(DiplomaticModifiers.WarMongerer, warmongerThreatScore(civ, other))

        // War deeds (Vox Populi)
        term(DiplomaticModifiers.CivilianKiller, maxOf(civilianKillerScore(civ, other, rules), civilianKillerGlobalScore(civ, other, rules)))
        term(DiplomaticModifiers.UsedNuclearWeapons, if (memory.numTimesNuked > 0) rules.define(AiDefine.OPINION_WEIGHT_NUKED_MAX) else 0)
        term(DiplomaticModifiers.CapturedOurHolyCity, holyCityCapturedScore(civ, other, rules))
        term(DiplomaticModifiers.CapturedOurCities, capitalCapturedScore(civ, other, rules))

        // Nice things (Vox Populi)
        term(DiplomaticModifiers.ReturnedCapturedUnits, civiliansReturnedScore(civ, other, rules))
        term(DiplomaticModifiers.BuiltLandmarksForUs, landmarksScore(civ, other, rules))
        term(DiplomaticModifiers.ResurrectedUs, resurrectedScore(civ, other, rules))
        term(DiplomaticModifiers.LiberatedCapital, liberatedCapitalScore(civ, other, rules))
        term(DiplomaticModifiers.LiberatedHolyCity, if (memory.liberatedHolyCity) rules.define(AiDefine.OPINION_WEIGHT_LIBERATED_HOLY_CITY) else 0)
        term(DiplomaticModifiers.LiberatedCity, liberatedCitiesScore(civ, other, rules))
        // The embassies are state (TradeLogic sets them): the first one there shows the opinion term, any other shows 0
        val embassy = embassyScore(civ, other, rules)
        if (write != null) {
            var shown = false
            for (modifier in listOf(DiplomaticModifiers.SharedEmbassies, DiplomaticModifiers.EstablishedEmbassy, DiplomaticModifiers.ReceivedEmbassy)) {
                if (!write.hasModifier(modifier)) continue
                write.setModifier(modifier, if (shown) 0f else -embassy.toFloat())
                shown = true
            }
        }
        total += embassy
        term(DiplomaticModifiers.HasDiplomat, diplomatScore(civ, other, rules))
        term(DiplomaticModifiers.SharedIntrigue, intrigueSharedScore(civ, other, rules))

        // Asked us to do things we don't like
        term(DiplomaticModifiers.AskedUsNotToSettle, requestScore(civ, other, PromiseAi.PromiseKind.Expansion, AiDefine.OPINION_WEIGHT_ASKED_NO_SETTLE))
        term(DiplomaticModifiers.AskedUsToStopSpying, requestScore(civ, other, PromiseAi.PromiseKind.Spy, AiDefine.OPINION_WEIGHT_ASKED_STOP_SPYING))
        term(DiplomaticModifiers.UnacceptableDemands, demandMadeScore(civ, other, rules))
        term(DiplomaticModifiers.StealingTerritory, memory.numTimesCultureBombed * rules.define(AiDefine.OPINION_WEIGHT_CULTURE_BOMBED))
        val eraName = DiplomacyTurn.gameEraName(civ)
        val religionEmphasis = rules.eraDiplomacy(eraName).diploEmphasisReligion
        term(DiplomaticModifiers.ReligiousConversions, religiousConversionScore(civ, other, rules))
        religionScore(civ, other, rules)?.let { (modifier, weight) -> term(modifier, weight) }
        val ideology = ideologyScore(civ, other, rules)
        if (ideology != 0) term(if (ideology < 0) DiplomaticModifiers.SameIdeology else DiplomaticModifiers.DifferentIdeology, ideology)
        term(DiplomaticModifiers.SpiedOnUs, robbedScore(civ, other, rules))
        term(DiplomaticModifiers.StoleArtifacts, stoleArtifactsScore(civ, other, rules))

        // Broken promises
        term(DiplomaticModifiers.BetrayedPromiseToNotAttackUs,
            if (memory.brokenMilitaryPromise) PromiseAi.promiseScore(civ, memory, PromiseAi.PromiseKind.Military) else 0)
        val brokenWithAnybody = !memory.brokenMilitaryPromise && hasBrokenMilitaryPromiseWithAnybody(other)
        term(DiplomaticModifiers.BetrayedPromiseToNotAttackOtherCiv, if (brokenWithAnybody) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_MILITARY_PROMISE_WORLD) else 0)
        term(DiplomaticModifiers.RefusedToPromiseNotToAttackUs,
            if (!memory.brokenMilitaryPromise && !brokenWithAnybody) PromiseAi.promiseScore(civ, memory, PromiseAi.PromiseKind.Military) else 0)
        fun promise(broken: DiplomaticModifiers, ignored: DiplomaticModifiers, kind: PromiseAi.PromiseKind, emphasis: Int = 1) {
            val score = PromiseAi.promiseScore(civ, memory, kind, emphasis)
            term(if (PromiseAi.state(memory, kind).first) broken else ignored, score)
        }
        promise(DiplomaticModifiers.BetrayedPromiseToNotSettleCitiesNearUs, DiplomaticModifiers.RefusedToNotSettleCitiesNearUs, PromiseAi.PromiseKind.Expansion)
        promise(DiplomaticModifiers.BrokenBorderPromise, DiplomaticModifiers.IgnoredBorderPromise, PromiseAi.PromiseKind.Border)
        promise(DiplomaticModifiers.BetrayedPromiseToNotSpreadReligionToUs, DiplomaticModifiers.RefusedToNotSpreadReligionToUs,
            PromiseAi.PromiseKind.NoConvert, religionEmphasis)
        promise(DiplomaticModifiers.BetrayedPromiseToNotDigInOurLands, DiplomaticModifiers.IgnoredRequestToStopDigging, PromiseAi.PromiseKind.NoDigging)
        promise(DiplomaticModifiers.BetrayedPromiseToNotSendingSpiesToUs, DiplomaticModifiers.RefusedToNotSendingSpiesToUs, PromiseAi.PromiseKind.Spy)
        term(DiplomaticModifiers.BrokenCoopWarPromise, PromiseAi.promiseScore(civ, memory, PromiseAi.PromiseKind.CoopWar))

        // Protected minors
        term(DiplomaticModifiers.DestroyedProtectedMinor, protectedMinorKilledScore(memory, rules))
        term(DiplomaticModifiers.AttackedProtectedMinor, protectedMinorAttackedScore(memory, rules))
        term(DiplomaticModifiers.BulliedProtectedMinor, protectedMinorBulliedScore(memory, rules))
        term(DiplomaticModifiers.SidedWithProtectedMinor, if (isAngryAboutSidedWithProtectedMinor(memory, rules)) rules.define(AiDefine.OPINION_WEIGHT_SIDED_WITH_THEIR_MINOR) else 0)

        // Friendship, pacts, open borders, research agreements, city-states protected together (Vox Populi)
        term(DiplomaticModifiers.DeclarationOfFriendship, dofAcceptedScore(civ, other, rules))
        term(DiplomaticModifiers.DeclaredFriendshipWithOurAllies, dofWithAnyFriendScore(civ, other, rules))
        term(DiplomaticModifiers.DeclaredFriendshipWithOurEnemies, dofWithAnyEnemyScore(civ, other, rules))
        term(DiplomaticModifiers.DefensivePact, dpAcceptedScore(civ, other, rules))
        term(DiplomaticModifiers.DefensivePactWithOurAllies, dpWithAnyFriendScore(civ, other, rules))
        term(DiplomaticModifiers.DefensivePactWithOurEnemies, dpWithAnyEnemyScore(civ, other, rules))
        term(DiplomaticModifiers.OpenBorders, openBordersScore(civ, other, rules))
        term(DiplomaticModifiers.ResearchAgreement, researchAgreementScore(civ, other, rules))
        term(DiplomaticModifiers.ProtectingSameCityStates, sameProtectedMinorScore(civ, other, rules))

        // Friends not getting along: the worst of the traitor terms
        term(DiplomaticModifiers.BetrayedDeclarationOfFriendship, traitorScore(civ, other, rules))

        // Denouncing (Vox Populi)
        term(DiplomaticModifiers.Denunciation, denouncedScore(civ, other, rules))
        term(DiplomaticModifiers.DenouncedOurAllies, denouncedFriendScore(civ, other, rules))
        term(DiplomaticModifiers.DenouncedOurEnemies, denouncedEnemyScore(civ, other, rules))
        term(DiplomaticModifiers.DenouncedByOurFriends, denouncedByOurFriendScore(civ, other, rules))

        // Recent trade, assistance, common foes (Vox Populi)
        term(DiplomaticModifiers.TradedWithUs, recentTradeScore(civ, other, rules))
        term(DiplomaticModifiers.SharedEnemy, commonFoeScore(civ, other, rules))
        val assist = recentAssistScore(civ, other, rules)
        term(if (assist < 0) DiplomaticModifiers.HelpedUs else DiplomaticModifiers.RefusedToHelpUs, assist)

        // World Congress
        val league = civ.gameInfo.league
        val turn = civ.gameInfo.turns
        val ourMemory = league?.members?.get(civ.civID)?.alignmentMemory?.get(other.civID)
        if (ourMemory != null) {
            term(DiplomaticModifiers.LikedTheirProposal, likedTheirProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.DislikedTheirProposal, dislikedTheirProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.SupportedOurProposal, supportedMyProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.FoiledOurProposal, foiledMyProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.SupportedOurHosting, supportedMyHostingScore(ourMemory, turn, rules))
        }
        return total
    }

    //region The terms (CvDiplomacyAI::Get*Score)

    /** A difficulty column (Vox Populi's HandicapInfos; 100 without the difficulty) */
    private fun handicapValue(civ: Civilization, column: String, default: Int = 100): Int =
        civ.gameInfo.ruleset.ai.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get(column) ?: default

    /** GetBaseOpinionScore: the difficulty's HumanOpinionChange towards a human, its AIOpinionChange towards an AI */
    fun baseOpinionScore(civ: Civilization, other: Civilization): Int =
        handicapValue(civ, if (other.isHuman()) "HumanOpinionChange" else "AIOpinionChange", 0)

    /** Our era for the dispute terms, at least 1 */
    private fun era(civ: Civilization) = maxOf(1, civ.getEraNumber())

    /** A dispute weight scaled by a personality value x the difficulty's percent, / 750 when it is bad, / 500 when it is good */
    private fun scaleDispute(weight: Int, personality: Int, percent: Int): Int {
        if (weight == 0) return 0
        val scaled = weight * personality * percent
        return if (scaled > 0) scaled / 750 else scaled / 500
    }

    /**
     *  GetLandDisputeLevelScore: by the land dispute level; a conqueror (Domination primary victory pursuit) adds
     *  OPINION_WEIGHT_LAND_WARMONGER to a dispute and (OPINION_WEIGHT_LAND_NONE_WARMONGER - neediness) x era / 2 to none; scaled by
     *  boldness x the difficulty's LandDisputePercent
     */
    fun landDisputeScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        var weight = when (civ.ai.diplomacy.memoryOf(other).landDispute) {
            DisputeLevel.Fierce -> rules.define(AiDefine.OPINION_WEIGHT_LAND_FIERCE)
            DisputeLevel.Strong -> rules.define(AiDefine.OPINION_WEIGHT_LAND_STRONG)
            DisputeLevel.Weak -> rules.define(AiDefine.OPINION_WEIGHT_LAND_WEAK)
            DisputeLevel.None -> rules.define(AiDefine.OPINION_WEIGHT_LAND_NONE)
        }
        if (civ.ai.isConqueror()) {
            if (weight > 0) weight += rules.define(AiDefine.OPINION_WEIGHT_LAND_WARMONGER)
            else if (weight < 0) weight += (rules.define(AiDefine.OPINION_WEIGHT_LAND_NONE_WARMONGER) -
                civ.ai.diplomacyValue(DiplomacyPersonality.Neediness)) * era(civ) / 2
        }
        return scaleDispute(weight, civ.ai.diplomacyValue(DiplomacyPersonality.Boldness), handicapValue(civ, "LandDisputePercent"))
    }

    /**
     *  GetWonderDisputeLevelScore: by the wonder dispute level; a cultural leader (Culture primary victory pursuit) adds
     *  OPINION_WEIGHT_WONDER_CULTURAL to a dispute and, unless they spam wonders, (OPINION_WEIGHT_WONDER_NONE_CULTURAL - neediness) x
     *  era / 2 to none; scaled by wonder competitiveness x the difficulty's WonderDisputePercent
     */
    fun wonderDisputeScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val memory = civ.ai.diplomacy.memoryOf(other)
        var weight = when (memory.wonderDispute) {
            DisputeLevel.Fierce -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_FIERCE)
            DisputeLevel.Strong -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_STRONG)
            DisputeLevel.Weak -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_WEAK)
            DisputeLevel.None -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_NONE)
        }
        if (civ.ai.isCultural()) {
            if (weight > 0) weight += rules.define(AiDefine.OPINION_WEIGHT_WONDER_CULTURAL)
            else if (!memory.wonderSpammer) weight += (rules.define(AiDefine.OPINION_WEIGHT_WONDER_NONE_CULTURAL) -
                civ.ai.diplomacyValue(DiplomacyPersonality.Neediness)) * era(civ) / 2
        }
        return scaleDispute(weight, civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness), handicapValue(civ, "WonderDisputePercent"))
    }

    /**
     *  GetMinorCivDisputeLevelScore, once we have met a city-state: by the city-state dispute level; a diplomat (Diplomacy primary victory
     *  pursuit) adds OPINION_WEIGHT_MINOR_CIV_DIPLOMAT to a dispute and, unless they trouble city-states (IsMinorCivTroublemaker without
     *  bullying: a dispute above weak, a protected city-state of ours they killed or attacked), (OPINION_WEIGHT_MINOR_CIV_NONE_DIPLOMAT -
     *  neediness) x era / 2 to none; scaled by city-state competitiveness x the difficulty's MinorCivDisputePercent. Coups and the
     *  city-state attack promise have no counterpart (none).
     */
    fun minorCivDisputeScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (civ.gameInfo.civilizations.none { it.isCityState && it.isAlive() && civ.knows(it) }) return 0
        val memory = civ.ai.diplomacy.memoryOf(other)
        var weight = when (memory.minorCivDispute) {
            DisputeLevel.Fierce -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_FIERCE)
            DisputeLevel.Strong -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_STRONG)
            DisputeLevel.Weak -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_WEAK)
            DisputeLevel.None -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_NONE)
        }
        if (civ.ai.isDiplomat()) {
            val troublemaker = isMinorCivTroublemaker(memory, rules, ignoreBullying = true)
            if (weight > 0) weight += rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_DIPLOMAT)
            else if (!troublemaker) weight += (rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_NONE_DIPLOMAT) -
                civ.ai.diplomacyValue(DiplomacyPersonality.Neediness)) * era(civ) / 2
        }
        return scaleDispute(weight, civ.ai.diplomacyValue(DiplomacyPersonality.MinorCivCompetitiveness), handicapValue(civ, "MinorCivDisputePercent"))
    }

    /**
     *  GetRecklessExpanderScore: for a reckless expander, OPINION_WEIGHT_RECKLESS_EXPANDER plus the larger of
     *  (cities over ours or over RECKLESS_EXPANDER_CITIES_THRESHOLD % of the median, the smaller, less 1) x _PER_CITY and (tiles likewise
     *  with RECKLESS_EXPANDER_LAND_THRESHOLD) x _PER_TILE, plus _STRATEGIC_MOD for a conqueror (primary or secondary victory pursuit) or
     *  the warmonger trait; x boldness x the difficulty's LandDisputePercent / 500
     */
    fun recklessExpanderScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (!WarStateAi.isPlayerRecklessExpander(civ, other)) return 0
        val players = civ.gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() }
        val medianCities = ExpanderAi.median(players.map { it.cities.size })
        val medianPlots = ExpanderAi.median(players.map { ExpanderAi.totalLand(it) })
        val cities = other.cities.size
        val plots = ExpanderAi.totalLand(other)
        val cityDifference = minOf(cities - civ.cities.size,
            (cities * 100 - medianCities * rules.define(AiDefine.RECKLESS_EXPANDER_CITIES_THRESHOLD)) / 100)
        val plotDifference = minOf(plots - ExpanderAi.totalLand(civ),
            (plots * 100 - medianPlots * rules.define(AiDefine.RECKLESS_EXPANDER_LAND_THRESHOLD)) / 100)
        val cityWeight = if (cityDifference > 1) (cityDifference - 1) * rules.define(AiDefine.OPINION_WEIGHT_RECKLESS_EXPANDER_PER_CITY) else 0
        val plotWeight = if (plotDifference > 1) (plotDifference - 1) * rules.define(AiDefine.OPINION_WEIGHT_RECKLESS_EXPANDER_PER_TILE) else 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_RECKLESS_EXPANDER) + maxOf(cityWeight, plotWeight)
        if (civ.ai.isConqueror() || civ.ai.isSecondaryConqueror() || civ.ai.hasTraitCategory(TraitCategory.Warmonger))
            weight += rules.define(AiDefine.OPINION_WEIGHT_RECKLESS_EXPANDER_STRATEGIC_MOD)
        return weight * civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) * handicapValue(civ, "LandDisputePercent") / 500
    }

    /**
     *  GetWonderSpammerScore: for a wonder spammer, OPINION_WEIGHT_WONDER_SPAMMER plus, by the wonders they built over ours or over the
     *  median (the smaller) beyond WONDER_SPAMMER_THRESHOLD + 1 - the difficulty's WonderBlockMod, _PER_WONDER each (at most _CAP in
     *  all), plus _STRATEGIC_MOD for a conqueror (primary or secondary victory pursuit) or the warmonger trait; x wonder
     *  competitiveness x the difficulty's WonderBlockPercent / 750
     */
    fun wonderSpammerScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (!civ.ai.diplomacy.memoryOf(other).wonderSpammer) return 0
        val theirs = other.ai.diplomacy.wondersConstructed
        val difference = minOf(theirs - civ.ai.diplomacy.wondersConstructed, theirs - ExpanderAi.medianWonders(civ))
        val beyond = rules.define(AiDefine.WONDER_SPAMMER_THRESHOLD) + 1 - handicapValue(civ, "WonderBlockMod", 0)
        var weight = rules.define(AiDefine.OPINION_WEIGHT_WONDER_SPAMMER)
        if (difference > beyond)
            weight = minOf(weight + (difference - beyond) * rules.define(AiDefine.OPINION_WEIGHT_WONDER_SPAMMER_PER_WONDER),
                rules.define(AiDefine.OPINION_WEIGHT_WONDER_SPAMMER_CAP))
        if (civ.ai.isConqueror() || civ.ai.isSecondaryConqueror() || civ.ai.hasTraitCategory(TraitCategory.Warmonger))
            weight += rules.define(AiDefine.OPINION_WEIGHT_WONDER_SPAMMER_STRATEGIC_MOD)
        return weight * civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness) * handicapValue(civ, "WonderBlockPercent") / 750
    }

    /** GetMilitaryAggressivePostureScore: by their military posture towards us, none at war or under a peace treaty */
    fun militaryPostureScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (civ.isAtWarWith(other) || (civ.getDiplomacyManager(other)?.turnsToPeaceTreaty() ?: 0) > 0) return 0
        return when (civ.ai.diplomacy.memoryOf(other).militaryPosture) {
            AggressivePosture.None -> 0
            AggressivePosture.Low -> rules.define(AiDefine.OPINION_WEIGHT_MILITARY_AGGRESSIVE_POSTURE_LOW)
            AggressivePosture.Medium -> rules.define(AiDefine.OPINION_WEIGHT_MILITARY_AGGRESSIVE_POSTURE_MEDIUM)
            AggressivePosture.High -> rules.define(AiDefine.OPINION_WEIGHT_MILITARY_AGGRESSIVE_POSTURE_HIGH)
            AggressivePosture.Incredible -> rules.define(AiDefine.OPINION_WEIGHT_MILITARY_AGGRESSIVE_POSTURE_INCREDIBLE)
        }
    }

    /** GetWarmongerThreatScore: the warmonger score, counted from 5 */
    fun warmongerThreatScore(civ: Civilization, other: Civilization): Int {
        val score = DiplomacyTurn.warmongerScore(civ, other)
        return if (score < 5) 0 else score
    }

    /**
     *  GetNoSettleRequestScore / GetStopSpyingRequestScore (Vox Populi): they asked us for the promise of [kind] and we refused or
     *  broke it (their memory of us), fading from that turn over the refused or broken promise's duration
     */
    fun requestScore(civ: Civilization, other: Civilization, kind: PromiseAi.PromiseKind, weight: AiDefine): Int {
        val theirs = other.ai.diplomacy.memories[civ.civID] ?: return 0
        val (broken, ignored) = PromiseAi.state(theirs, kind)
        if (!broken && !ignored) return 0
        return PromiseAi.timed(civ, theirs, kind, civ.gameInfo.ruleset.ai.define(weight))
    }

    /**
     *  GetDemandMadeScore (Vox Populi): the first demand in full, each further one OPINION_WEIGHT_MADE_DEMAND_OF_US_SUBSEQUENT, fading
     *  (diminishing) over MADE_DEMAND_TURNS_UNTIL_FORGIVEN adjusted by inverted forgiveness; halved (NO_TAKE_DIVISOR) unless we
     *  gave in to them recently, tripled when we did and are bankrupt, growing towards that as bankruptcy nears
     */
    fun demandMadeScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val memory = civ.ai.diplomacy.memories[other.civID] ?: return 0
        if (memory.numDemandsMade <= 0) return 0
        val first = rules.define(AiDefine.OPINION_WEIGHT_MADE_DEMAND_OF_US)
        var weight = diminishing(civ, memory.numDemandsMade, memory.demandMadeTurn, first,
            rules.define(AiDefine.OPINION_WEIGHT_MADE_DEMAND_OF_US_SUBSEQUENT), rules.define(AiDefine.MADE_DEMAND_TURNS_UNTIL_FORGIVEN),
            forgiveness(civ), invert = true)
        val multiplier = rules.define(AiDefine.OPINION_WEIGHT_MADE_DEMAND_BANKRUPT_MULTIPLIER)
        val multiplierTurns = rules.define(AiDefine.OPINION_WEIGHT_MADE_DEMAND_BANKRUPT_MULTIPLIER_TURNS)
        if (!WarTargetAi.isRecentDemandAccepted(other, civ))
            weight = weight * 100 / maxOf(rules.define(AiDefine.OPINION_WEIGHT_MADE_DEMAND_YOU_NO_TAKE_DIVISOR), 1)
        else if (civ.gold == 0 && WarStateAi.avgGoldRate(civ) <= 0) weight = weight * multiplier / 100
        else {
            val turnsToBankruptcy = WarStateAi.turnsToBankruptcy(civ, 0)
            if (turnsToBankruptcy == 0) weight = weight * multiplier / 100
            else if (turnsToBankruptcy < multiplierTurns)
                weight += (weight * multiplier / 100 - weight) * (multiplierTurns - turnsToBankruptcy) / multiplierTurns
        }
        return weight
    }

    /** The game turn the timed terms count from */
    private fun turn(civ: Civilization) = civ.gameInfo.turns

    private fun loyalty(civ: Civilization) = civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty)
    private fun forgiveness(civ: Civilization) = civ.ai.diplomacyValue(DiplomacyPersonality.Forgiveness)

    /** A "first one in full, each further one less" term, fading over [baseTurns] adjusted by [personality] (VP's diminishing modifier) */
    private fun diminishing(civ: Civilization, count: Int, stamp: Int, first: Int, subsequent: Int, baseTurns: Int, personality: Int,
                            invert: Boolean = false): Int {
        if (count <= 0) return 0
        val value = first + subsequent * (count - 1)
        val duration = OpinionTimers.adjustDuration(civ, baseTurns, personality, invert)
        return OpinionTimers.adjust(value, duration, stamp, turn(civ), OpinionTimers.Type.Diminishing, count, first)
    }

    /** A "the same for each one" term, fading over [baseTurns] adjusted by [personality] (VP's stacked modifier) */
    private fun stacked(civ: Civilization, count: Int, stamp: Int, each: Int, baseTurns: Int, personality: Int, invert: Boolean = false): Int {
        if (count <= 0) return 0
        val duration = OpinionTimers.adjustDuration(civ, baseTurns, personality, invert)
        return OpinionTimers.adjust(each * count, duration, stamp, turn(civ), OpinionTimers.Type.Stacked, count)
    }

    /**
     *  Vox Populi's TestOpinionModifiers for the stacked deeds, before the opinions: each count halves once per its duration (the
     *  deed's TURNS_UNTIL_FORGOTTEN or _FORGIVEN, adjusted by loyalty or inverted forgiveness and the game speed) and takes the turn
     *  as its new stamp. VP sets the stamp to the current turn whenever it rewrites the count, which it does every turn, so its deeds
     *  never fade; the stamp moves here only when the count does (the design its comments describe). The demands made of us decay the
     *  same way. Then the promises broken or ignored long enough ago are forgotten ([PromiseAi.testPromiseExpiry]), and DoCounters'
     *  decay of the civilian killer value (CIVILIAN_KILLER_VALUE_PER_TURN_DECAY).
     */
    fun testOpinionModifiers(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val now = turn(civ)
        fun decayed(count: Int, stamp: Int, baseTurns: AiDefine, personality: Int, invert: Boolean, set: (Int, Int) -> Unit) {
            if (count <= 0 || stamp < 0) return
            val duration = OpinionTimers.adjustDuration(civ, rules.define(baseTurns), personality, invert)
            val left = OpinionTimers.decayStacks(count, stamp, now, duration)
            if (left != count) set(left, if (left > 0) now else -1)
        }
        val loyalty = loyalty(civ)
        val forgiveness = forgiveness(civ)
        for (other in others) {
            if (!other.isMajorCiv) continue
            val m = civ.ai.diplomacy.memoryOf(other)
            decayed(m.numCiviliansReturned, m.civiliansReturnedTurn, AiDefine.RETURNED_CIVILIAN_TURNS_UNTIL_FORGOTTEN, loyalty, false) { c, t ->
                m.numCiviliansReturned = c; m.civiliansReturnedTurn = t }
            val diplomacy = civ.getDiplomacyManager(other)
            if (diplomacy != null) decayed(diplomacy.landmarksBuiltForUs, m.landmarksBuiltTurn, AiDefine.BUILT_LANDMARK_TURNS_UNTIL_FORGOTTEN, loyalty, false) { c, t ->
                diplomacy.landmarksBuiltForUs = c; m.landmarksBuiltTurn = t }
            decayed(m.numCitiesLiberated, m.liberatedCitiesTurn, AiDefine.LIBERATED_CITY_TURNS_UNTIL_FORGOTTEN, loyalty, false) { c, t ->
                m.numCitiesLiberated = c; m.liberatedCitiesTurn = t }
            decayed(m.negativeReligiousConversionPoints, m.religiousConversionTurn, AiDefine.RELIGIOUS_CONVERSION_TURNS_UNTIL_FORGIVEN, forgiveness, true) { c, t ->
                m.negativeReligiousConversionPoints = c; m.religiousConversionTurn = t }
            decayed(m.numTimesRobbed, m.robbedTurn, AiDefine.ROBBED_US_TURNS_UNTIL_FORGIVEN, forgiveness, true) { c, t ->
                m.numTimesRobbed = c; m.robbedTurn = t }
            decayed(m.numWondersBeatenTo, m.beatenToWonderTurn, AiDefine.BEATEN_TO_WONDER_TURNS_UNTIL_FORGIVEN, forgiveness, true) { c, t ->
                m.numWondersBeatenTo = c; m.beatenToWonderTurn = t }
            decayed(m.numArtifactsStolen, m.stoleArtifactTurn, AiDefine.EXCAVATED_ARTIFACT_TURNS_UNTIL_FORGIVEN, forgiveness, true) { c, t ->
                m.numArtifactsStolen = c; m.stoleArtifactTurn = t }
            decayed(m.numTimesIntrigueShared, m.intrigueSharedTurn, AiDefine.SHARED_INTRIGUE_TURNS_UNTIL_FORGOTTEN, loyalty, false) { c, t ->
                m.numTimesIntrigueShared = c; m.intrigueSharedTurn = t }
            decayed(m.numDemandsMade, m.demandMadeTurn, AiDefine.MADE_DEMAND_TURNS_UNTIL_FORGIVEN, forgiveness, true) { c, t ->
                m.numDemandsMade = c; m.demandMadeTurn = t }
            PromiseAi.testPromiseExpiry(civ, m)
            m.civilianKillerValue = maxOf(0, m.civilianKillerValue - rules.define(AiDefine.CIVILIAN_KILLER_VALUE_PER_TURN_DECAY))
        }
    }

    /** GetMaxCivilianKillerValue: CIVILIAN_KILLER_VALUE_PER_OPINION_WEIGHT x OPINION_WEIGHT_CIVILIAN_KILLER_MAX x the game speed's OpinionDurationPercent / 100 */
    fun maxCivilianKillerValue(civ: Civilization): Int {
        val rules = civ.gameInfo.ruleset.ai
        return rules.define(AiDefine.CIVILIAN_KILLER_VALUE_PER_OPINION_WEIGHT) * rules.define(AiDefine.OPINION_WEIGHT_CIVILIAN_KILLER_MAX) *
            DiplomacyTurn.opinionDurationPercent(civ) / 100
    }

    /** GetCivilianKillerScore: OPINION_WEIGHT_CIVILIAN_KILLER_MAX x their civilian killer value's share of the maximum */
    fun civilianKillerScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val value = civ.ai.diplomacy.memoryOf(other).civilianKillerValue
        if (value <= 0) return 0
        val percent = value * 100 / maxOf(maxCivilianKillerValue(civ), 1)
        return if (percent > 0) rules.define(AiDefine.OPINION_WEIGHT_CIVILIAN_KILLER_MAX) * percent / 100 else 0
    }

    /**
     *  GetCivilianKillerGlobalScore: towards someone we see as neutral or worse, with no DoF or pact, OPINION_WEIGHT_CIVILIAN_KILLER_WORLD
     *  when another major we know holds a civilian killer score of OPINION_WEIGHT_CIVILIAN_KILLER_WORLD_THRESHOLD or more against them
     *  and we care: not at war with it, no denouncement either way, not untrustworthy, not approached as afraid or deceptive or worse,
     *  not seen as an enemy, and it never betrayed a friendship with us. (VP's backstabber clause: our own backstabber flag is not kept.)
     */
    fun civilianKillerGlobalScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (memory.opinion > MajorCivOpinion.Neutral || isDoFAccepted(civ, other) ||
            civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact) return 0
        for (third in civ.gameInfo.civilizations) {
            if (third == civ || third == other || !third.isMajorCiv || !WarStateAi.isPlayerValid(civ, third)) continue
            if (civilianKillerScore(third, other, rules) < rules.define(AiDefine.OPINION_WEIGHT_CIVILIAN_KILLER_WORLD_THRESHOLD)) continue
            val ourMemory = civ.ai.diplomacy.memoryOf(third)
            val approach = ourMemory.approach
            val dontCare = ourMemory.doFBrokenTurn >= 0 || civ.isAtWarWith(third) || isDenouncedPlayer(civ, third) ||
                isDenouncedPlayer(third, civ) || isUntrustworthyFriend(third) || approach == MajorCivApproach.Afraid ||
                (approach != null && approach <= MajorCivApproach.Deceptive) || ourMemory.opinion <= MajorCivOpinion.Enemy
            if (!dontCare) return rules.define(AiDefine.OPINION_WEIGHT_CIVILIAN_KILLER_WORLD)
        }
        return 0
    }

    /** IsCapitalCapturedBy (not only currently): they took our original capital, or hold it now */
    private fun capitalCaptured(civ: Civilization, other: Civilization) =
        civ.ai.diplomacy.memoryOf(other).capturedCapital || isCapitalCapturedBy(civ, other)

    /** IsHolyCityCapturedBy (not only currently): they took our holy city, or hold it now */
    private fun holyCityCaptured(civ: Civilization, other: Civilization) =
        civ.ai.diplomacy.memoryOf(other).capturedHolyCity || CompetitorAi.isHolyCityCapturedBy(civ, other)

    /** GetHolyCityCapturedByScore: OPINION_WEIGHT_CAPTURED_HOLY_CITY. Returning it by deal and vassalage have no counterpart: none. */
    fun holyCityCapturedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int =
        if (holyCityCaptured(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_CAPTURED_HOLY_CITY) else 0

    /** GetCapitalCapturedByScore: OPINION_WEIGHT_CAPTURED_CAPITAL. Returning it by deal and vassalage have no counterpart: none. */
    fun capitalCapturedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int =
        if (capitalCaptured(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_CAPTURED_CAPITAL) else 0

    /** GetCiviliansReturnedToMeScore: OPINION_WEIGHT_RETURNED_CIVILIAN, _SUBSEQUENT for each further one, fading with loyalty */
    fun civiliansReturnedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val m = civ.ai.diplomacy.memoryOf(other)
        return diminishing(civ, m.numCiviliansReturned, m.civiliansReturnedTurn, rules.define(AiDefine.OPINION_WEIGHT_RETURNED_CIVILIAN),
            rules.define(AiDefine.OPINION_WEIGHT_RETURNED_CIVILIAN_SUBSEQUENT), rules.define(AiDefine.RETURNED_CIVILIAN_TURNS_UNTIL_FORGOTTEN), loyalty(civ))
    }

    /** GetLandmarksBuiltForMeScore: OPINION_WEIGHT_BUILT_LANDMARK, _SUBSEQUENT for each further one, fading with loyalty */
    fun landmarksScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val count = civ.getDiplomacyManager(other)?.landmarksBuiltForUs ?: 0
        return diminishing(civ, count, civ.ai.diplomacy.memoryOf(other).landmarksBuiltTurn, rules.define(AiDefine.OPINION_WEIGHT_BUILT_LANDMARK),
            rules.define(AiDefine.OPINION_WEIGHT_BUILT_LANDMARK_SUBSEQUENT), rules.define(AiDefine.BUILT_LANDMARK_TURNS_UNTIL_FORGOTTEN), loyalty(civ))
    }

    /** GetResurrectedScore: OPINION_WEIGHT_RESURRECTED, divided by the liberator-captured divisors when they took our capital or holy city */
    fun resurrectedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (!civ.ai.diplomacy.memoryOf(other).resurrectedUs) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_RESURRECTED)
        if (capitalCaptured(civ, other)) weight = weight * 100 / maxOf(100, rules.define(AiDefine.OPINION_WEIGHT_LIBERATOR_CAPTURED_CAPITAL_DIVISOR))
        if (holyCityCaptured(civ, other)) weight = weight * 100 / maxOf(100, rules.define(AiDefine.OPINION_WEIGHT_LIBERATOR_CAPTURED_HOLY_CITY_DIVISOR))
        return weight
    }

    /** GetLiberatedCapitalScore: OPINION_WEIGHT_LIBERATED_CAPITAL, divided when they took our holy city */
    fun liberatedCapitalScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (!civ.ai.diplomacy.memoryOf(other).liberatedCapital) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_LIBERATED_CAPITAL)
        if (holyCityCaptured(civ, other)) weight = weight * 100 / maxOf(100, rules.define(AiDefine.OPINION_WEIGHT_LIBERATOR_CAPTURED_HOLY_CITY_DIVISOR))
        return weight
    }

    /**
     *  GetLiberatedCitiesScore, none when they took our capital: OPINION_WEIGHT_LIBERATED_CITY per city, fading with loyalty, divided when
     *  they took our holy city, then by the cities they took from us less those they ever liberated, plus 1
     */
    fun liberatedCitiesScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (capitalCaptured(civ, other)) return 0
        val m = civ.ai.diplomacy.memoryOf(other)
        var weight = stacked(civ, m.numCitiesLiberated, m.liberatedCitiesTurn, rules.define(AiDefine.OPINION_WEIGHT_LIBERATED_CITY),
            rules.define(AiDefine.LIBERATED_CITY_TURNS_UNTIL_FORGOTTEN), loyalty(civ))
        if (weight == 0) return 0
        if (holyCityCaptured(civ, other)) weight = weight * 100 / maxOf(100, rules.define(AiDefine.OPINION_WEIGHT_LIBERATOR_CAPTURED_HOLY_CITY_DIVISOR))
        return weight / maxOf(1, m.numCitiesCapturedBy - m.numCitiesEverLiberated + 1)
    }

    /** GetEmbassyScore: OPINION_WEIGHT_EMBASSY_MUTUAL with embassies both ways, _EMBASSY with ours in their capital, _EMBASSY_THEM with theirs in ours */
    fun embassyScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val diplomacy = civ.getDiplomacyManager(other) ?: return 0
        val ours = diplomacy.hasModifier(DiplomaticModifiers.EstablishedEmbassy) || diplomacy.hasModifier(DiplomaticModifiers.SharedEmbassies)
        val theirs = diplomacy.hasModifier(DiplomaticModifiers.ReceivedEmbassy) || diplomacy.hasModifier(DiplomaticModifiers.SharedEmbassies)
        return when {
            ours && theirs -> rules.define(AiDefine.OPINION_WEIGHT_EMBASSY_MUTUAL)
            ours -> rules.define(AiDefine.OPINION_WEIGHT_EMBASSY)
            theirs -> rules.define(AiDefine.OPINION_WEIGHT_EMBASSY_THEM)
            else -> 0
        }
    }

    /**
     *  GetDiplomatScore: OPINION_WEIGHT_DIPLOMAT (and _MOD more for a scientist or the nerd trait) while one of their spies is a diplomat
     *  in our capital; none after they robbed us or broke or ignored a spy promise
     */
    fun diplomatScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val m = civ.ai.diplomacy.memoryOf(other)
        if (m.numTimesRobbed > 0 || m.brokenSpyPromise || m.ignoredSpyPromise) return 0
        val capital = civ.getCapital() ?: return 0
        if (other.espionageManager.spyList.none { it.isDiplomat && it.getCityOrNull() == capital }) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DIPLOMAT)
        if (civ.ai.isScientist() || civ.ai.hasTraitCategory(TraitCategory.Nerd)) weight += rules.define(AiDefine.OPINION_WEIGHT_DIPLOMAT_MOD)
        return weight
    }

    /** GetTimesIntrigueSharedScore: OPINION_WEIGHT_INTRIGUE_SHARED_BY, _SUBSEQUENT for each further one, fading with loyalty */
    fun intrigueSharedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val m = civ.ai.diplomacy.memoryOf(other)
        return diminishing(civ, m.numTimesIntrigueShared, m.intrigueSharedTurn, rules.define(AiDefine.OPINION_WEIGHT_INTRIGUE_SHARED_BY),
            rules.define(AiDefine.OPINION_WEIGHT_INTRIGUE_SHARED_BY_SUBSEQUENT), rules.define(AiDefine.SHARED_INTRIGUE_TURNS_UNTIL_FORGOTTEN), loyalty(civ))
    }

    /** GetTimesRobbedScore: OPINION_WEIGHT_ROBBED_BY per theft, fading with (inverted) forgiveness */
    fun robbedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val m = civ.ai.diplomacy.memoryOf(other)
        return stacked(civ, m.numTimesRobbed, m.robbedTurn, rules.define(AiDefine.OPINION_WEIGHT_ROBBED_BY),
            rules.define(AiDefine.ROBBED_US_TURNS_UNTIL_FORGIVEN), forgiveness(civ), invert = true)
    }

    /** GetStoleArtifactsScore: OPINION_WEIGHT_EXCAVATED_ARTIFACT per artifact, fading with (inverted) forgiveness */
    fun stoleArtifactsScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val m = civ.ai.diplomacy.memoryOf(other)
        return stacked(civ, m.numArtifactsStolen, m.stoleArtifactTurn, rules.define(AiDefine.OPINION_WEIGHT_EXCAVATED_ARTIFACT),
            rules.define(AiDefine.EXCAVATED_ARTIFACT_TURNS_UNTIL_FORGIVEN), forgiveness(civ), invert = true)
    }

    /**
     *  GetReligiousConversionPointsScore: the conversion points x the era's religion emphasis x OPINION_WEIGHT_PER_NEGATIVE_CONVERSION,
     *  fading (stacked) over RELIGIOUS_CONVERSION_TURNS_UNTIL_FORGIVEN adjusted by inverted forgiveness
     */
    fun religiousConversionScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val m = civ.ai.diplomacy.memoryOf(other)
        val each = rules.eraDiplomacy(DiplomacyTurn.gameEraName(civ)).diploEmphasisReligion * rules.define(AiDefine.OPINION_WEIGHT_PER_NEGATIVE_CONVERSION)
        return stacked(civ, m.negativeReligiousConversionPoints, m.religiousConversionTurn, each,
            rules.define(AiDefine.RELIGIOUS_CONVERSION_TURNS_UNTIL_FORGIVEN), forgiveness(civ), invert = true)
    }

    /**
     *  CvPlayerReligions::UpdateStateReligion: the religion we founded while we hold its holy city, else the one whose holy city we
     *  hold with the most followers in our cities, else the religion of most of our cities (pantheons do not count). The second of the
     *  pair is whether we hold its holy city (GetOwnedReligion is the state religion then).
     */
    fun stateReligion(civ: Civilization): Pair<Religion, Boolean>? {
        civ.religionManager.getFounderBenefitsReligion()?.let { return it to true }
        val held = civ.gameInfo.religions.values.filter { religion ->
            religion.isMajorReligion() && civ.cities.any { it.religion.religionThisIsTheHolyCityOf == religion.name }
        }.maxByOrNull { religion -> civ.cities.sumOf { it.religion.getFollowersOf(religion.name) } }
        if (held != null) return held to true
        return civ.religionManager.getMajorityReligion()?.takeIf { it.isMajorReligion() }?.let { it to false }
    }

    /** The civilization that holds the holy city of [religion] (VP's controller of a religion), if any */
    private fun holyCityOwner(civ: Civilization, religion: Religion): Civilization? =
        civ.gameInfo.getCities().firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }?.civ

    /**
     *  The era's emphasis for a belief score adjusted by a diplomacy flavor (GetPersonalityFlavorForDiplomacy: the leader's flavor, 0 as
     *  1): one less below 5 (not below 0), one more above 7
     */
    private fun flavoredEmphasis(civ: Civilization, emphasis: Int, flavor: Flavor): Int {
        val value = civ.ai.personality(flavor).let { if (it == 0) 1 else it }
        return when {
            value < 5 -> maxOf(0, emphasis - 1)
            value > 7 -> emphasis + 1
            else -> emphasis
        }
    }

    /**
     *  GetReligionScore: with no owned religion but a state religion, a bonus when we adopted their owned religion (x the World
     *  Religion modifier when it is the World Religion) or share their state religion, unless they converted our cities or took our
     *  holy city; a penalty for different state religions. With an owned religion: a penalty when they own one too (x the World
     *  Religion modifier), a bonus when they adopted ours (likewise), else the different owned religions penalty when their state
     *  religion is controlled (its holy city held) by them, nothing when by us, else the different state religions penalty. Each x the
     *  era's religion emphasis (adjusted by the religion flavor, +1 with the religious trait). Penalties end when we ignore religious
     *  differences ([isIgnoreReligionDifferences]). The modifier names the case.
     */
    fun religionScore(civ: Civilization, other: Civilization, rules: AiRuleset): Pair<DiplomaticModifiers, Int>? {
        val ourState = stateReligion(civ)
        val theirState = stateReligion(other)
        val ours = ourState?.takeIf { it.second }?.first
        val theirs = theirState?.takeIf { it.second }?.first
        var eraMod = flavoredEmphasis(civ, rules.eraDiplomacy(DiplomacyTurn.gameEraName(civ)).diploEmphasisReligion, Flavor.Religion)
        if (civ.ai.hasTraitCategory(TraitCategory.Religious)) eraMod++
        val league = civ.gameInfo.league
        fun world(weight: Int, religion: Religion) =
            if (league != null && league.getReligionSpreadPercent(religion.name) > 0) weight * rules.define(AiDefine.OPINION_WEIGHT_WORLD_RELIGION_MODIFIER) / 100
            else weight
        var result: Pair<DiplomaticModifiers, Int>? = null
        if (ours == null && ourState != null) {
            val state = ourState.first
            if (civ.ai.diplomacy.memoryOf(other).negativeReligiousConversionPoints <= 0 && !holyCityCaptured(civ, other)) {
                if (state == theirs) result = DiplomaticModifiers.WeAdoptedTheirReligion to
                    world(rules.define(AiDefine.OPINION_WEIGHT_ADOPTING_HIS_RELIGION) * eraMod, theirs)
                else if (state == theirState?.first) result = DiplomaticModifiers.SameReligion to rules.define(AiDefine.OPINION_WEIGHT_SAME_STATE_RELIGIONS) * eraMod
            }
            if (theirState != null && state != theirState.first)
                result = DiplomaticModifiers.DifferentReligion to rules.define(AiDefine.OPINION_WEIGHT_DIFFERENT_STATE_RELIGIONS) * eraMod
        } else if (ours != null) {
            if (theirs != null) result = DiplomaticModifiers.DifferentReligion to world(rules.define(AiDefine.OPINION_WEIGHT_DIFFERENT_OWNED_RELIGIONS) * eraMod, theirs)
            else if (theirState != null) {
                val state = theirState.first
                if (state == ours) result = DiplomaticModifiers.TheyAdoptedOurReligion to world(rules.define(AiDefine.OPINION_WEIGHT_ADOPTING_MY_RELIGION) * eraMod, ours)
                else {
                    val controller = holyCityOwner(civ, state)
                    if (controller == civ) return null
                    result = DiplomaticModifiers.DifferentReligion to if (controller == other)
                        world(rules.define(AiDefine.OPINION_WEIGHT_DIFFERENT_OWNED_RELIGIONS) * eraMod, state)
                    else rules.define(AiDefine.OPINION_WEIGHT_DIFFERENT_STATE_RELIGIONS) * eraMod
                }
            }
        }
        if (result != null && result.second > 0 && isIgnoreReligionDifferences(civ, other)) return null
        return result
    }

    /** Their coop war with us against anyone is being prepared or fought (VP's GetGlobalCoopWarWithState >= COOP_WAR_STATE_PREPARING) */
    private fun isPreparingCoopWarWith(civ: Civilization, other: Civilization) =
        civ.ai.diplomacy.memoryOf(other).coopWars.values.any { it.state == CoopWarState.Soon || it.state == CoopWarState.Accepted }

    /**
     *  IsIgnoreReligionDifferences: never when they converted our cities, took our holy city or capital (when they resurrected us,
     *  only taking it counts), are at war with us or untrustworthy; always with the same ideology, when they liberated our capital or
     *  holy city, resurrected us, are battle brothers, liberated a city of ours recently or prepare a coop war with us. The returned
     *  capital or holy city by deal, the coop war agreement score and the Celtic no-spread trait have no counterpart: none.
     */
    fun isIgnoreReligionDifferences(civ: Civilization, other: Civilization): Boolean {
        if (!civ.isMajorCiv || !other.isMajorCiv) return true
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (memory.negativeReligiousConversionPoints > 0 || holyCityCaptured(civ, other)) return false
        if (if (memory.resurrectedUs) memory.capturedCapital else capitalCaptured(civ, other)) return false
        if (civ.isAtWarWith(other) || isUntrustworthyFriend(other)) return false
        if (civ.getIdeology() != null && civ.getIdeology() == other.getIdeology()) return true
        if (memory.liberatedCapital || memory.liberatedHolyCity || memory.resurrectedUs || memory.doFLevel == DoFLevel.BattleBrothers) return true
        if (WarSanityAi.isCityRecentlyLiberatedBy(civ, other)) return true
        return isPreparingCoopWarWith(civ, other)
    }

    /**
     *  GetIdeologyScore: with ideologies on both sides, the same ideology a bonus (x the World Ideology modifier when it has World
     *  Ideology pressure) and different ones a penalty (likewise when either has), x the era's late policy emphasis adjusted by the
     *  culture flavor, +1 for the tourism trait or a cultural pursuit (primary or secondary), +1 when close to a culture victory. The
     *  penalty ends when we ignore ideological differences ([isIgnoreIdeologyDifferences]). Vassal divisors: none.
     */
    fun ideologyScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val ours = civ.getIdeology() ?: return 0
        val theirs = other.getIdeology() ?: return 0
        var eraMod = flavoredEmphasis(civ, rules.eraDiplomacy(DiplomacyTurn.gameEraName(civ)).diploEmphasisLatePolicies, Flavor.Culture)
        if (civ.ai.hasTraitCategory(TraitCategory.Tourism) || civ.ai.isCultural() || civ.ai.isSecondaryCultural()) eraMod++
        if (VictoryProgressAi.cultureVictoryProgress(civ, rules) >= rules.define(AiDefine.CLOSE_TO_CULTURE_VICTORY_THRESHOLD)) eraMod++
        val league = civ.gameInfo.league
        fun pressure(name: String) = league != null && league.getIdeologyPressure(name) > 0
        var weight = if (ours == theirs) eraMod * rules.define(AiDefine.OPINION_WEIGHT_SAME_LATE_POLICIES)
            else eraMod * rules.define(AiDefine.OPINION_WEIGHT_DIFFERENT_LATE_POLICIES)
        if (pressure(ours.name) || (ours != theirs && pressure(theirs.name)))
            weight = weight * rules.define(AiDefine.OPINION_WEIGHT_WORLD_IDEOLOGY_MODIFIER) / 100
        if (weight > 0 && isIgnoreIdeologyDifferences(civ, other)) return 0
        return weight
    }

    /**
     *  IsIgnoreIdeologyDifferences: never when at war or untrustworthy, or when they took our capital (when they resurrected us, only
     *  taking our capital or holy city counts); always when they liberated our capital, resurrected us, are battle brothers or prepare a
     *  coop war with us, or liberated our holy city or a city of ours recently. The ideology embargo (Cold War) and the coop war
     *  agreement score have no counterpart: none.
     */
    fun isIgnoreIdeologyDifferences(civ: Civilization, other: Civilization): Boolean {
        if (!civ.isMajorCiv || !other.isMajorCiv) return true
        if (civ.isAtWarWith(other) || isUntrustworthyFriend(other)) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (if (memory.resurrectedUs) memory.capturedCapital || memory.capturedHolyCity else capitalCaptured(civ, other)) return false
        if (memory.liberatedCapital || memory.resurrectedUs || memory.doFLevel == DoFLevel.BattleBrothers) return true
        if (isPreparingCoopWarWith(civ, other)) return true
        return memory.liberatedHolyCity || WarSanityAi.isCityRecentlyLiberatedBy(civ, other)
    }

    private fun hasBrokenMilitaryPromiseWithAnybody(other: Civilization): Boolean =
        other.gameInfo.civilizations.any { it != other && it.isMajorCiv && it.ai.diplomacy.memories[other.civID]?.brokenMilitaryPromise == true }

    /** GetAngryAboutProtectedMinorKilledScore */
    fun protectedMinorKilledScore(memory: MajorCivMemory, rules: AiRuleset): Int {
        if (!isAngryAboutProtectedMinorKilled(memory, rules)) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_KILLED_PROTECTED_MINOR)
        if (memory.numProtectedMinorsKilled > 1) weight += rules.define(AiDefine.OPINION_WEIGHT_KILLED_MANY_PROTECTED_MINORS)
        return weight
    }

    /** GetAngryAboutProtectedMinorAttackedScore */
    fun protectedMinorAttackedScore(memory: MajorCivMemory, rules: AiRuleset): Int {
        if (!isAngryAboutProtectedMinorAttacked(memory, rules)) return 0
        var weight = if (memory.turnsSinceAttackedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_RECENTLY_NUM_TURNS))
            rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_RECENTLY)
        else rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_WHILE_AGO)
        if (memory.numProtectedMinorsAttacked > 1) weight += rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_MANY_PROTECTED_MINORS)
        return weight
    }

    /** GetAngryAboutProtectedMinorBulliedScore */
    fun protectedMinorBulliedScore(memory: MajorCivMemory, rules: AiRuleset): Int {
        if (memory.numProtectedMinorsBullied <= 0 || memory.turnsSinceBulliedProtectedMinor < 0 ||
            memory.turnsSinceBulliedProtectedMinor >= rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)) return 0
        var weight = if (memory.turnsSinceBulliedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_RECENTLY_NUM_TURNS))
            rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_RECENTLY)
        else rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_WHILE_AGO)
        if (memory.numProtectedMinorsBullied > 1) weight += rules.define(AiDefine.OPINION_WEIGHT_BULLIED_MANY_PROTECTED_MINORS)
        return weight
    }

    /** IsAngryAboutProtectedMinorKilled */
    fun isAngryAboutProtectedMinorKilled(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        memory.numProtectedMinorsKilled > 0 && memory.turnsSinceKilledProtectedMinor > -1 &&
            memory.turnsSinceKilledProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_KILLED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    /** IsAngryAboutProtectedMinorAttacked: anger over a killed city-state takes precedence */
    fun isAngryAboutProtectedMinorAttacked(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        !isAngryAboutProtectedMinorKilled(memory, rules) && memory.numProtectedMinorsAttacked > 0 && memory.turnsSinceAttackedProtectedMinor > -1 &&
            memory.turnsSinceAttackedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    fun isAngryAboutSidedWithProtectedMinor(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        memory.turnsSinceSidedWithProtectedMinor > -1 &&
            memory.turnsSinceSidedWithProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_SIDED_WITH_THEIR_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    /** IsAngryAboutProtectedMinorBullied (Vox Populi): anger over attacked or killed city-states trumps it (VP also scales the turns by
     *  forgiveness, AdjustModifierDuration, which we do not have) */
    fun isAngryAboutProtectedMinorBullied(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        !isAngryAboutProtectedMinorKilled(memory, rules) && !isAngryAboutProtectedMinorAttacked(memory, rules) &&
            memory.turnsSinceBulliedProtectedMinor > -1 &&
            memory.turnsSinceBulliedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    /** CvDiplomacyAI::IsDoFAccepted: Unciv keeps the friendship as [DiplomacyFlags.DeclarationOfFriendship] */
    fun isDoFAccepted(civ: Civilization, other: Civilization): Boolean =
        civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true

    /** CvDiplomacyAI::IsDenouncedPlayer: [civ] has denounced [other] (Unciv: [DiplomacyFlags.Denunciation] on civ's side) */
    fun isDenouncedPlayer(civ: Civilization, other: Civilization): Boolean =
        civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.Denunciation) == true

    /**
     *  IsMinorCivTroublemaker: a city-state dispute above weak, a protected city-state of ours they killed or attacked, unless
     *  [ignoreBullying] one they bullied or a bullying promise they broke or ignored. Coups and the city-state attack promise: none.
     */
    fun isMinorCivTroublemaker(memory: MajorCivMemory, rules: AiRuleset, ignoreBullying: Boolean = false): Boolean {
        if (memory.minorCivDispute > DisputeLevel.Weak) return true
        if (isAngryAboutProtectedMinorKilled(memory, rules) || isAngryAboutProtectedMinorAttacked(memory, rules)) return true
        return !ignoreBullying && (isAngryAboutProtectedMinorBullied(memory, rules) || memory.brokenBullyCityStatePromise ||
            memory.ignoredBullyCityStatePromise)
    }

    private fun hasDefensivePact(civ: Civilization, other: Civilization) =
        civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** A living major with a city, by civID, else null (VP's checks on the most valuable friend, ally and biggest competitor) */
    private fun livingMajor(civ: Civilization, id: String?): Civilization? =
        id?.let { civ.gameInfo.getCivilizationOrNull(it) }?.takeIf { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() }

    /** Our biggest competitor when we denounced or fight them (VP's eWorstCompetitor in the "with any enemy" scores) */
    private fun opposedCompetitor(civ: Civilization): Civilization? =
        livingMajor(civ, CompetitorAi.biggestCompetitor(civ))?.takeIf { isDenouncedPlayer(civ, it) || civ.isAtWarWith(it) }

    /**
     *  GetPlayerNumMajorsConquered for ourselves (DoUpdateConquestStats): the majors whose original capital we hold. VP also counts
     *  the majors we killed whose capital is gone; Unciv keeps no killer: none.
     */
    private fun numMajorsConquered(civ: Civilization): Int =
        civ.cities.count { city -> city.isOriginalCapital && city.foundingCivObject.let { it != null && it != civ && it.isMajorCiv } }

    /**
     *  GetDOFAcceptedScore: with a DoF OPINION_WEIGHT_DOF, plus _DOF_MOST_VALUED_FRIEND for our most valuable friend; without one, the
     *  weight of the friendship level we had (friends, allies, battle brothers); scaled by inverted DoF willingness
     */
    fun dofAcceptedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val ai = civ.ai.diplomacy
        val weight = if (isDoFAccepted(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DOF) +
            (if (ai.mostValuableFriend == other.civID) rules.define(AiDefine.OPINION_WEIGHT_DOF_MOST_VALUED_FRIEND) else 0)
        else when (ai.memoryOf(other).doFLevel) {
            DoFLevel.Friends -> rules.define(AiDefine.OPINION_WEIGHT_DOF_TYPE_FRIENDS)
            DoFLevel.Allies -> rules.define(AiDefine.OPINION_WEIGHT_DOF_TYPE_ALLIES)
            DoFLevel.BattleBrothers -> rules.define(AiDefine.OPINION_WEIGHT_DOF_TYPE_BATTLE_BROTHERS)
            DoFLevel.Untrustworthy, DoFLevel.New -> 0
        }
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.DoFWillingness), invert = true)
    }

    /**
     *  GetDOFWithAnyFriendScore: OPINION_WEIGHT_DOF_WITH_FRIEND, _SUBSEQUENT for each further friend (a DoF) we share, plus
     *  _DOF_WITH_MOST_VALUED_FRIEND / _ALLY when they are friends with our most valuable friend or ally; scaled by work-with willingness
     */
    fun dofWithAnyFriendScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val mutual = countedMajors(civ).count { it != other && isDoFAccepted(civ, it) && isDoFAccepted(other, it) }
        if (mutual <= 0) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_FRIEND) + rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_FRIEND_SUBSEQUENT) * (mutual - 1)
        val ai = civ.ai.diplomacy
        val bestFriend = livingMajor(civ, ai.mostValuableFriend)?.takeIf { isDoFAccepted(civ, it) }
        val bestAlly = livingMajor(civ, ai.mostValuableAlly)?.takeIf { isDoFAccepted(civ, it) && hasDefensivePact(civ, it) }
        weight += mostValuedBonus(bestFriend, bestAlly, rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_MOST_VALUED_FRIEND),
            rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_MOST_VALUED_ALLY)) { isDoFAccepted(other, it) }
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.WorkWithWillingness))
    }

    /**
     *  The most valuable partner bonus of the "with any friend" scores: with both partners, [first]'s weight is checked first when it
     *  is the stronger (more negative or equal), else [second]'s; with one, the stronger weight when [sharedWith] it. (VP's branch
     *  without defensive pacts does not arise: Unciv always allows them.)
     */
    private fun mostValuedBonus(first: Civilization?, second: Civilization?, firstWeight: Int, secondWeight: Int,
                                sharedWith: (Civilization) -> Boolean): Int {
        if (first != null && second != null) {
            return if (firstWeight <= secondWeight) {
                if (sharedWith(first)) firstWeight else if (sharedWith(second)) secondWeight else 0
            } else {
                if (sharedWith(second)) secondWeight else if (sharedWith(first)) firstWeight else 0
            }
        }
        val shared = (first != null && sharedWith(first)) || (second != null && sharedWith(second))
        return if (shared) minOf(firstWeight, secondWeight) else 0
    }

    /**
     *  GetDOFWithAnyEnemyScore: OPINION_WEIGHT_DOF_WITH_ENEMY, _SUBSEQUENT for each further player we denounced or fight that they are
     *  friends with, plus _DOF_WITH_BIGGEST_COMPETITOR when that is our biggest competitor; scaled by neediness. The league rival: none.
     */
    fun dofWithAnyEnemyScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val enemies = countedMajors(civ).count { isDoFAccepted(other, it) && (isDenouncedPlayer(civ, it) || civ.isAtWarWith(it)) }
        if (enemies <= 0) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_ENEMY) + rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_ENEMY_SUBSEQUENT) * (enemies - 1)
        val competitor = opposedCompetitor(civ)
        if (competitor != null && isDoFAccepted(other, competitor)) weight += rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_BIGGEST_COMPETITOR)
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.Neediness))
    }

    /**
     *  GetDPAcceptedScore: with a defensive pact OPINION_WEIGHT_DP, plus _DP_MOST_VALUED_ALLY for our most valuable ally; x
     *  OPINION_WEIGHT_DP_CONQUEROR_MULTIPLIER % once we have conquered a major, else scaled by warmonger hate
     */
    fun dpAcceptedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        var weight = 0
        if (hasDefensivePact(civ, other)) {
            weight += rules.define(AiDefine.OPINION_WEIGHT_DP)
            if (civ.ai.diplomacy.mostValuableAlly == other.civID) weight += rules.define(AiDefine.OPINION_WEIGHT_DP_MOST_VALUED_ALLY)
        }
        if (numMajorsConquered(civ) > 0) return weight * rules.define(AiDefine.OPINION_WEIGHT_DP_CONQUEROR_MULTIPLIER) / 100
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate))
    }

    /**
     *  GetDPWithAnyFriendScore: OPINION_WEIGHT_DP_WITH_FRIEND, _SUBSEQUENT for each further defensive pact we share, plus
     *  _DP_WITH_MOST_VALUED_ALLY / _FRIEND when they have one with our most valuable ally or friend; scaled by warmonger hate
     */
    fun dpWithAnyFriendScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val living = civ.gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() && it != other }
        val mutual = living.count { hasDefensivePact(civ, it) && hasDefensivePact(other, it) }
        if (mutual <= 0) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_FRIEND) + rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_FRIEND_SUBSEQUENT) * (mutual - 1)
        val ai = civ.ai.diplomacy
        val bestAlly = livingMajor(civ, ai.mostValuableAlly)?.takeIf { hasDefensivePact(civ, it) }
        val bestFriend = livingMajor(civ, ai.mostValuableFriend)?.takeIf { hasDefensivePact(civ, it) && isDoFAccepted(civ, it) }
        weight += mostValuedBonus(bestAlly, bestFriend, rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_MOST_VALUED_ALLY),
            rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_MOST_VALUED_FRIEND)) { hasDefensivePact(other, it) }
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate))
    }

    /**
     *  GetDPWithAnyEnemyScore: OPINION_WEIGHT_DP_WITH_ENEMY, _SUBSEQUENT for each further player we denounced or fight that they have
     *  a defensive pact with, plus _DP_WITH_BIGGEST_COMPETITOR for our biggest competitor; scaled by neediness. The league rival: none.
     */
    fun dpWithAnyEnemyScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val living = civ.gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() }
        val enemies = living.count { hasDefensivePact(other, it) && (isDenouncedPlayer(civ, it) || civ.isAtWarWith(it)) }
        if (enemies <= 0) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_ENEMY) + rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_ENEMY_SUBSEQUENT) * (enemies - 1)
        val competitor = opposedCompetitor(civ)
        if (competitor != null && hasDefensivePact(other, competitor)) weight += rules.define(AiDefine.OPINION_WEIGHT_DP_WITH_BIGGEST_COMPETITOR)
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.Neediness))
    }

    /** GetOpenBordersScore: OPINION_WEIGHT_OPEN_BORDERS_MUTUAL both ways, _US when only they let us in, _THEM when only we let them in */
    fun openBordersScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val theyAllow = civ.getDiplomacyManager(other)?.hasOpenBorders == true
        val weAllow = other.getDiplomacyManager(civ)?.hasOpenBorders == true
        return when {
            weAllow && theyAllow -> rules.define(AiDefine.OPINION_WEIGHT_OPEN_BORDERS_MUTUAL)
            theyAllow -> rules.define(AiDefine.OPINION_WEIGHT_OPEN_BORDERS_US)
            weAllow -> rules.define(AiDefine.OPINION_WEIGHT_OPEN_BORDERS_THEM)
            else -> 0
        }
    }

    /** GetResearchAgreementScore: OPINION_WEIGHT_RA, plus _RA_SCIENTIFIC_MOD for a scientist (primary or secondary pursuit) or the nerd trait */
    fun researchAgreementScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.ResearchAgreement) != true) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_RA)
        if (civ.ai.isScientist() || civ.ai.isSecondaryScientist() || civ.ai.hasTraitCategory(TraitCategory.Nerd))
            weight += rules.define(AiDefine.OPINION_WEIGHT_RA_SCIENTIFIC_MOD)
        return weight
    }

    /**
     *  GetPtPSameCSScore: the city-states both of us protect, x OPINION_WEIGHT_PTP_SAME_MINOR_DIPLOMAT_MULTIPLIER % for a diplomat
     *  (primary or secondary) when they make no trouble with city-states; _EACH per one, at least _MIN
     */
    fun sameProtectedMinorScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        var same = civ.gameInfo.civilizations.count {
            it.isCityState && it.cities.isNotEmpty() && CityStateAi.isProtectedBy(it, other) && CityStateAi.isProtectedBy(it, civ)
        }
        if ((civ.ai.isDiplomat() || civ.ai.isSecondaryDiplomat()) && !isMinorCivTroublemaker(civ.ai.diplomacy.memoryOf(other), rules))
            same = same * rules.define(AiDefine.OPINION_WEIGHT_PTP_SAME_MINOR_DIPLOMAT_MULTIPLIER) / 100
        if (same <= 0) return 0
        return minOf(rules.define(AiDefine.OPINION_WEIGHT_PTP_SAME_MINOR_MIN), same * rules.define(AiDefine.OPINION_WEIGHT_PTP_SAME_MINOR_EACH))
    }

    /** IsPlayerDenouncedFriend: they denounced someone we have a DoF with */
    fun isPlayerDenouncedFriend(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacy.values.any { it.otherCiv != other && it.hasFlag(DiplomacyFlags.DeclarationOfFriendship) && isDenouncedPlayer(other, it.otherCiv) }

    /** IsUntrustworthyFriend: declared war on a friend, or denounced at least 2 friends */
    fun isUntrustworthyFriend(civ: Civilization): Boolean =
        weDeclaredWarOnFriendCount(civ) > 0 || weDenouncedFriendCount(civ) >= 2

    /** GetWeDenouncedFriendCount: how many players remember [civ] denouncing them while friends */
    fun weDenouncedFriendCount(civ: Civilization): Int =
        civ.gameInfo.civilizations.count { it != civ && it.isMajorCiv && it.ai.diplomacy.memories[civ.civID]?.friendDenouncedUs == true }

    /** GetWeDeclaredWarOnFriendCount */
    fun weDeclaredWarOnFriendCount(civ: Civilization): Int =
        civ.gameInfo.civilizations.count { it != civ && it.isMajorCiv && it.ai.diplomacy.memories[civ.civID]?.friendDeclaredWarOnUs == true }

    /** The traitor block of GetMajorCivOpinionWeight: the largest of five terms */
    fun traitorScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val memory = civ.ai.diplomacy.memoryOf(other)
        var traitor = friendDenouncementScore(civ, other, rules)
        // GetWeDenouncedFriendScore
        traitor = maxOf(traitor, weDenouncedFriendCount(other) * rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_FRIEND_EACH))
        // GetFriendDenouncedUsScore
        if (memory.friendDenouncedUs) traitor = maxOf(traitor, rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ME_FRIENDS))
        // GetWeDeclaredWarOnFriendScore: the decaying values every player keeps for them
        var declaredWarOnFriendSum = 0
        for (third in civ.gameInfo.civilizations) if (third.isMajorCiv) declaredWarOnFriendSum += third.ai.diplomacy.memories[other.civID]?.declaredWarOnFriendValue ?: 0
        traitor = maxOf(traitor, declaredWarOnFriendSum /
            (rules.define(AiDefine.DECLARED_WAR_ON_FRIEND_PER_OPINION_WEIGHT) * DiplomacyTurn.opinionDurationPercent(civ)))
        // GetFriendDeclaredWarOnUsScore
        if (memory.friendDeclaredWarOnUs) traitor = maxOf(traitor, rules.define(AiDefine.OPINION_WEIGHT_WAR_ME_FRIENDS))
        return traitor
    }

    /** GetFriendDenouncementScore: their (trustworthy) former friends who denounced them, weighted by whom we like more */
    fun friendDenouncementScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        var score = 0
        val ourOpinionOfThem = civ.ai.diplomacy.opinionOf(other)
        for ((denouncerId, theirMemory) in other.ai.diplomacy.memories) {
            if (!theirMemory.friendDenouncedUs || denouncerId == civ.civID) continue
            val denouncer = civ.gameInfo.getCivilizationOrNull(denouncerId) ?: continue
            if (isUntrustworthyFriend(denouncer)) continue
            val ourOpinionOfDenouncer = civ.ai.diplomacy.opinionOf(denouncer)
            if (ourOpinionOfDenouncer > ourOpinionOfThem) score += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_FRIEND_EACH)
            else if (ourOpinionOfDenouncer < ourOpinionOfThem) score += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_FRIEND_DONT_LIKE)
        }
        return score
    }

    /** GetRecentTradeScore: OPINION_WEIGHT_TRADE_MAX x the recent trade value's share of its maximum (VP's strategic trade partners: none) */
    fun recentTradeScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val value = civ.ai.diplomacy.memoryOf(other).recentTradeValue
        if (value <= 0) return 0
        val percent = value * 100 / maxOf(civ.ai.diplomacy.maxRecentTradeValue(), 1)
        return if (percent > 0) rules.define(AiDefine.OPINION_WEIGHT_TRADE_MAX) * percent / 100 else 0
    }

    /** GetCommonFoeScore: OPINION_WEIGHT_COMMON_FOE_MAX x the common foe value's share of its maximum */
    fun commonFoeScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val value = civ.ai.diplomacy.memoryOf(other).commonFoeValue
        if (value <= 0) return 0
        val percent = value * 100 / maxOf(civ.ai.diplomacy.maxCommonFoeValue(), 1)
        return if (percent > 0) rules.define(AiDefine.OPINION_WEIGHT_COMMON_FOE_MAX) * percent / 100 else 0
    }

    /**
     *  GetRecentAssistScore: help (our negative value) the size of OPINION_WEIGHT_ASSIST_MAX, as a bonus, x its share of the maximum;
     *  refusals OPINION_WEIGHT_FAILED_ASSIST_MAX x theirs
     */
    fun recentAssistScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val value = civ.ai.diplomacy.memoryOf(other).recentAssistValue
        if (value == 0) return 0
        val failed = value > 0
        val percent = kotlin.math.abs(value) * 100 / maxOf(civ.ai.diplomacy.maxRecentAssistValue(failed), 1)
        if (percent <= 0) return 0
        return if (failed) kotlin.math.abs(rules.define(AiDefine.OPINION_WEIGHT_FAILED_ASSIST_MAX)) * percent / 100
            else -kotlin.math.abs(rules.define(AiDefine.OPINION_WEIGHT_ASSIST_MAX)) * percent / 100
    }

    /**
     *  CvDiplomacyAI::AdjustConditionalModifier: [value] x 50% .. 150% by a personality value from 1 to 10 (x 0.5, 0.625, 0.75, 0.875,
     *  1 at 5, 1.1, 1.2, 1.3, 1.4, 1.5), the other way round when [invert]ed
     */
    fun adjustConditional(value: Int, personality: Int, invert: Boolean = false): Int {
        if (value == 0 || personality == 0) return value
        val permille = when (personality) {
            1 -> if (invert) 1500 else 500
            2 -> if (invert) 1375 else 625
            3 -> if (invert) 1250 else 750
            4 -> if (invert) 1125 else 875
            6 -> if (invert) 900 else 1100
            7 -> if (invert) 800 else 1200
            8 -> if (invert) 700 else 1300
            9 -> if (invert) 600 else 1400
            10 -> if (invert) 500 else 1500
            else -> 1000
        }
        return value * permille / 1000
    }

    /** GetDenouncedScore: OPINION_WEIGHT_MUTUAL_DENOUNCEMENT, else OPINION_WEIGHT_DENOUNCED_ME for theirs, else _DENOUNCED_THEM for ours */
    fun denouncedScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val theirs = isDenouncedPlayer(other, civ)
        val ours = isDenouncedPlayer(civ, other)
        return when {
            theirs && ours -> rules.define(AiDefine.OPINION_WEIGHT_MUTUAL_DENOUNCEMENT)
            theirs -> rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ME)
            ours -> rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_THEM)
            else -> 0
        }
    }

    /** The living majors with a city (a human's shadow AI does not count other humans) */
    private fun countedMajors(civ: Civilization) = civ.gameInfo.civilizations.filter {
        it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() && !(civ.isHuman() && it.isHuman())
    }

    /**
     *  GetDenouncedFriendScore: OPINION_WEIGHT_DENOUNCED_FRIEND, _SUBSEQUENT for each further friend of ours (a DoF) they denounced,
     *  plus OPINION_WEIGHT_DENOUNCED_MOST_VALUED_FRIEND / _ALLY when they denounced our most valuable friend (a DoF) or ally (a DoF and a
     *  defensive pact; with both, the larger weight checked first); scaled by loyalty
     */
    fun denouncedFriendScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val friends = countedMajors(civ).count { isDoFAccepted(civ, it) && isDenouncedPlayer(other, it) }
        if (friends <= 0) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_FRIEND) + rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_FRIEND_SUBSEQUENT) * (friends - 1)
        val ai = civ.ai.diplomacy
        fun valid(id: String?, pact: Boolean) = id?.let { civ.gameInfo.getCivilizationOrNull(it) }?.takeIf {
            it.isAlive() && it.cities.isNotEmpty() && isDoFAccepted(civ, it) &&
                (!pact || civ.getDiplomacyManager(it)?.diplomaticStatus == DiplomaticStatus.DefensivePact)
        }
        val bestFriend = valid(ai.mostValuableFriend, pact = false)
        val bestAlly = valid(ai.mostValuableAlly, pact = true)
        val friendWeight = rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_MOST_VALUED_FRIEND)
        val allyWeight = rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_MOST_VALUED_ALLY)
        val friendDenounced = bestFriend != null && isDenouncedPlayer(other, bestFriend)
        val allyDenounced = bestAlly != null && isDenouncedPlayer(other, bestAlly)
        weight += when {
            bestFriend != null && bestAlly != null ->
                if (friendWeight >= allyWeight) (if (friendDenounced) friendWeight else if (allyDenounced) allyWeight else 0)
                else (if (allyDenounced) allyWeight else if (friendDenounced) friendWeight else 0)
            friendDenounced || allyDenounced -> maxOf(friendWeight, allyWeight)
            else -> 0
        }
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty))
    }

    /**
     *  GetDenouncedEnemyScore: OPINION_WEIGHT_DENOUNCED_ENEMY, _SUBSEQUENT for each further player we denounced or fight that they
     *  denounced too, plus OPINION_WEIGHT_DENOUNCED_BIGGEST_COMPETITOR when that one is our biggest competitor; scaled by work-against
     *  willingness. (VP's league rival has no counterpart: none.)
     */
    fun denouncedEnemyScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val enemies = countedMajors(civ).count { (isDenouncedPlayer(civ, it) || civ.isAtWarWith(it)) && isDenouncedPlayer(other, it) }
        if (enemies <= 0) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ENEMY) + rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ENEMY_SUBSEQUENT) * (enemies - 1)
        val competitor = CompetitorAi.biggestCompetitor(civ)?.let { civ.gameInfo.getCivilizationOrNull(it) }
        if (competitor != null && competitor.isAlive() && competitor.cities.isNotEmpty() &&
            (isDenouncedPlayer(civ, competitor) || civ.isAtWarWith(competitor)) && isDenouncedPlayer(other, competitor))
            weight += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BIGGEST_COMPETITOR)
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness))
    }

    /**
     *  GetDenouncedByOurFriendScore, unless they are our friend or ally or liberated us: over the majors that denounced them and that we
     *  like at least as much (a DoF level not below theirs, a lower opinion weight): OPINION_WEIGHT_DENOUNCED_BY_THEIR_KEY_FRIEND for our
     *  most valuable friend or ally among them, OPINION_WEIGHT_DENOUNCED_BY_THEIR_FRIEND, _SUBSEQUENT for each further friend or ally
     *  of ours; scaled by work-against willingness
     */
    fun denouncedByOurFriendScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        if (isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact) return 0
        if (WarSanityAi.isLiberator(civ, other, onlyMajorCities = false)) return 0
        val ai = civ.ai.diplomacy
        val theirMemory = ai.memoryOf(other)
        var weight = 0
        var denouncements = 0
        for (third in countedMajors(civ)) {
            if (third == civ || third == other || !isDenouncedPlayer(third, other)) continue
            val thirdMemory = ai.memoryOf(third)
            if (theirMemory.doFLevel > thirdMemory.doFLevel || theirMemory.opinionWeight <= thirdMemory.opinionWeight) continue
            val dof = isDoFAccepted(civ, third)
            val pact = civ.getDiplomacyManager(third)?.diplomaticStatus == DiplomaticStatus.DefensivePact
            if (dof || pact) denouncements++
            if ((ai.mostValuableFriend == third.civID && dof) || (ai.mostValuableAlly == third.civID && pact))
                weight += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_THEIR_KEY_FRIEND)
        }
        if (denouncements > 0) weight += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_THEIR_FRIEND) +
            rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_THEIR_FRIEND_SUBSEQUENT) * (denouncements - 1)
        return adjustConditional(weight, civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness))
    }

    /** IsCapitalCapturedBy: [other] holds our original capital */
    fun isCapitalCapturedBy(civ: Civilization, other: Civilization): Boolean =
        other.cities.any { it.isOriginalCapital && it.foundingCivObject == civ }

    private fun recent(turnSet: Int, turn: Int, numTurns: Int): Boolean = turnSet >= 0 && turn - turnSet < numTurns

    /** The opposing memory does not exist or is older (DLL: iTurnsSince < iOpposingTurnsSince) */
    private fun newerThan(turnSet: Int, opposing: Int): Boolean = opposing < 0 || turnSet > opposing

    fun likedTheirProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.likedProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.likedProposalTurn, memory.dislikedProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL) else 0

    fun dislikedTheirProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.dislikedProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.dislikedProposalTurn, memory.likedProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL) else 0

    fun supportedMyProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.supportedProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.supportedProposalTurn, memory.foiledProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL) else 0

    fun foiledMyProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.foiledProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.foiledProposalTurn, memory.supportedProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL) else 0

    fun supportedMyHostingScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.supportedHostingTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING_NUM_TURNS)))
            rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING) else 0
    //endregion
}
