package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.diplomacy.AggressivePosture
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.CompetitorAi
import com.unciv.logic.automation.diplomacy.CoopWarAi
import com.unciv.logic.automation.diplomacy.CoopWarState
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.pow

/** CvDiplomacyAI WarStateTypes, worst to best (the original's order matters: comparisons use it; Vox Populi's TROUBLED sits between) */
enum class WarState { NearlyDefeated, Defensive, Troubled, Stalemate, Calm, Offensive, NearlyWon }

/** CvDiplomacyAI StateAllWars: the overall assessment of all wars against majors */
enum class StateAllWars { Neutral, Winning, Losing }

/** CvDiplomacyAI TargetValueTypes (Vox Populi's seven), hardest to easiest (order matters) */
enum class TargetValue { Impossible, Bad, Difficult, Average, Favorable, Soft, Cakewalk }

/** CvDiplomacyAI StrengthTypes: another player's military strength compared to ours, weakest to strongest (order matters) */
enum class StrengthLevel { Pathetic, Weak, Poor, Average, Strong, Powerful, Immense }

/**
 *  What CvDiplomacyAI keeps about wars (`civ.ai.warStates`), updated once per turn by [WarStateAi.doTurn]. Keys are civIDs.
 *  Old saves have none: everything starts empty (no war state, neutral overall state, 0 turns at war).
 */
class WarStateMemory : IsPartOfGameInfoSerialization {
    /** CvDiplomacyAI::m_paiPlayerNumTurnsAtWar */
    var turnsAtWar = HashMap<String, Int>()
    /** CvDiplomacyAI::m_paeWarState: only players we are at war with have an entry (NO_WAR_STATE_TYPE = no entry) */
    var warStates = HashMap<String, WarState>()
    /** CvDiplomacyAI::m_eStateAllWars */
    var stateAllWars = StateAllWars.Neutral
    /** CvDiplomacyAI::m_paePlayerTargetValue: with the bias of our boldness (see [StrengthEstimateAi]) */
    var targetValues = HashMap<String, TargetValue>()
    /** CvDiplomacyAI::m_paeRawTargetValue: without the bias of our boldness */
    var rawTargetValues = HashMap<String, TargetValue>()
    /** CvDiplomacyAI::m_paePlayerMilitaryStrengthComparedToUs */
    var militaryStrengths = HashMap<String, StrengthLevel>()
    /** CvDiplomacyAI::m_paeRawMilitaryStrengthComparedToUs */
    var rawMilitaryStrengths = HashMap<String, StrengthLevel>()
    /** CvPlayer::IsInTerribleShapeForWar (Vox Populi): see [WarStateAi.updateBadShapeForWar] */
    var terribleShapeForWar = false
    /** CvPlayer::IsNoNewWars (Vox Populi): see [WarStateAi.updateBadShapeForWar] */
    var noNewWars = false
    /** CvDiplomacyAI::IsEasyTarget (Vox Populi), by civID: see [WarStateAi.updateEasyTargets] */
    var easyTargets = HashSet<String>()
    /** CvTreasury::m_GoldChangeForTurnTimes100 (Vox Populi): our gold income of the last HISTORY_NUM_TURNS_TO_AVERAGE turns, oldest first */
    var goldIncomeHistory = ArrayList<Int>()
    /** CvPlayer::m_iCachedGoldRate (Vox Populi's getAvgGoldRate): see [WarStateAi.updateAverageGoldRate] */
    var avgGoldRate = 0

    fun clone() = WarStateMemory().also {
        it.turnsAtWar.putAll(turnsAtWar)
        it.warStates.putAll(warStates)
        it.stateAllWars = stateAllWars
        it.targetValues.putAll(targetValues)
        it.rawTargetValues.putAll(rawTargetValues)
        it.militaryStrengths.putAll(militaryStrengths)
        it.rawMilitaryStrengths.putAll(rawMilitaryStrengths)
        it.terribleShapeForWar = terribleShapeForWar
        it.noNewWars = noNewWars
        it.easyTargets.addAll(easyTargets)
        it.goldIncomeHistory.addAll(goldIncomeHistory)
        it.avgGoldRate = avgGoldRate
    }
}

/**
 *  The war assessment of CvDiplomacyAI (CvDiplomacyAI.cpp), run each turn for major civilizations, humans included, as CvDiplomacyAI::DoTurn
 *  does: DoUpdatePlayerStrengthEstimates ([StrengthEstimateAi]), DoUpdateWarStates, plus the turns-at-war counter of DoCounters.
 *
 *  Queries (read the state of the last [doTurn]):
 *  - [warState] (CvDiplomacyAI::GetWarState): null if not at war with that player;
 *  - [stateAllWars] (CvDiplomacyAI::GetStateAllWars);
 *  - [targetValue] (CvDiplomacyAI::GetTargetValue), [militaryStrengthComparedToUs] (GetMilitaryStrengthComparedToUs), and their raw
 *    forms without the bias of our boldness ([rawTargetValue], [rawMilitaryStrengthComparedToUs]); [turnsAtWar] (GetPlayerNumTurnsAtWar);
 *  - [numberCivsAtWarWith] (CvMilitaryAI::GetNumberCivsAtWarWith), [isPlayerValid] (CvDiplomacyAI::IsPlayerValid).
 *
 *  Unciv mapping: unit power is [com.unciv.models.ruleset.unit.BaseUnit.getAiPower] (CvUnitEntry::GetPower) × (1000 + 125 × level) / 1000
 *  × health / 100 (CvUnit::GetPower, level = 1 + promotions); city power is the city's combat strength ^ 1.5 (CvCity::GetPower);
 *  CvPlayer::getPower is [CivAi.militaryMight] plus the economic might ([economicMight]).
 *  Unciv has no teams: "team" checks are per civilization.
 */
object WarStateAi {

    //region Queries

    /** CvDiplomacyAI::GetWarState: null (NO_WAR_STATE_TYPE) if not at war with [other] or not evaluated yet */
    fun warState(civ: Civilization, other: Civilization): WarState? = civ.ai.warStates.warStates[other.civID]

    /** CvDiplomacyAI::GetStateAllWars */
    fun stateAllWars(civ: Civilization): StateAllWars = civ.ai.warStates.stateAllWars

    /** CvDiplomacyAI::GetPlayerTargetValue: null (NO_TARGET_VALUE) before the first evaluation or for invalid players */
    fun targetValue(civ: Civilization, other: Civilization): TargetValue? = civ.ai.warStates.targetValues[other.civID]

    /** CvDiplomacyAI::GetPlayerMilitaryStrengthComparedToUs: null (NO_STRENGTH_VALUE) before the first evaluation or for invalid players */
    fun militaryStrengthComparedToUs(civ: Civilization, other: Civilization): StrengthLevel? = civ.ai.warStates.militaryStrengths[other.civID]

    /** CvDiplomacyAI::GetRawTargetValue: the target value without the bias of our boldness */
    fun rawTargetValue(civ: Civilization, other: Civilization): TargetValue? = civ.ai.warStates.rawTargetValues[other.civID]

    /** CvDiplomacyAI::GetRawMilitaryStrengthComparedToUs: the military strength without the bias of our boldness */
    fun rawMilitaryStrengthComparedToUs(civ: Civilization, other: Civilization): StrengthLevel? = civ.ai.warStates.rawMilitaryStrengths[other.civID]

    /** CvDiplomacyAI::GetPlayerNumTurnsAtWar */
    fun turnsAtWar(civ: Civilization, other: Civilization): Int = civ.ai.warStates.turnsAtWar[other.civID] ?: 0

    /** CvPlayer::getAvgGoldRate (Vox Populi): as of the last [updateAverageGoldRate] */
    fun avgGoldRate(civ: Civilization): Int = civ.ai.warStates.avgGoldRate

    /**
     *  CvPlayer::getTurnsToBankruptcy (Vox Populi): never for a city-state or while the average gold rate ([avgGoldRate]) less
     *  [assumedExtraExpense] stays positive, else the treasury over that deficit
     */
    fun turnsToBankruptcy(civ: Civilization, assumedExtraExpense: Int): Int {
        if (!civ.isMajorCiv) return Int.MAX_VALUE
        val goldRate = avgGoldRate(civ) - assumedExtraExpense
        if (goldRate > 0) return Int.MAX_VALUE
        return civ.gold / maxOf(1, -goldRate)
    }

    /**
     *  CvPlayer::cacheAvgGoldRate and CvTreasury::DoGold's history (Vox Populi): the gold rate is this turn's income plus the average income
     *  of the last HISTORY_NUM_TURNS_TO_AVERAGE turns (VP's formula, which counts a steady income about twice); then this turn's income
     *  joins the history. A single turn's swing of the income no longer decides whether we are broke.
     */
    fun updateAverageGoldRate(civ: Civilization, memory: WarStateMemory, rules: AiRuleset) {
        val income = civ.stats.statsForNextTurn.gold.toInt()
        val history = memory.goldIncomeHistory
        memory.avgGoldRate = income + if (history.isEmpty()) 0 else history.sum() / history.size
        history.add(income)
        while (history.size > rules.define(AiDefine.HISTORY_NUM_TURNS_TO_AVERAGE)) history.removeAt(0)
    }

    /** CvMilitaryAI::GetNumberCivsAtWarWith: the valid majors we have a war state with */
    fun numberCivsAtWarWith(civ: Civilization): Int =
        civ.gameInfo.civilizations.count { it.isMajorCiv && it != civ && isPlayerValid(civ, it) && warState(civ, it) != null }

    /**
     *  CvPlayer::CountNumDangerousMajorsAtWarWith (Vox Populi): the living majors with cities we are at war with - for a city-state any
     *  major it fights - leaving out phony wars ([excludePhonyWars], [WarIntentAi.isPhonyWar]) and ([excludeIfNoTarget]) those our
     *  cities are not exposed to ([MilitaryAi.isExposedToEnemy]) that threaten none of them ([CityDangerAi.numberOfThreatenedCities])
     */
    fun numDangerousMajorsAtWarWith(civ: Civilization, excludePhonyWars: Boolean, excludeIfNoTarget: Boolean): Int {
        val enemies = civ.gameInfo.civilizations.filter { it.isMajorCiv && civ.isAtWarWith(it) }
        if (civ.isCityState) return enemies.size
        return enemies.count { other ->
            other.isAlive() && other.cities.isNotEmpty()
                && !(excludePhonyWars && WarIntentAi.isPhonyWar(civ, other))
                && !(excludeIfNoTarget && !civ.ai.military.isExposedToEnemy(civ, null, other) && CityDangerAi.numberOfThreatenedCities(civ, other) <= 0)
        }
    }

    /** CvDiplomacyAI::IsPlayerValid: alive, with at least one city, met, not ourselves (Unciv has no teams) */
    fun isPlayerValid(civ: Civilization, other: Civilization): Boolean =
        other != civ && !other.isBarbarian && !other.isSpectator && other.isAlive() && other.cities.isNotEmpty() && civ.knows(other)
    //endregion

    /**
     *  The per-turn update, in CvDiplomacyAI::DoTurn order: turns at war (DoCounters), war states, the bad-shape test, strength estimates
     *  (which read the war states and the bad-shape flags of other players), the bad-shape test again (it reads the estimates), easy targets
     */
    fun doTurn(civ: Civilization) {
        val memory = civ.ai.warStates
        val rules = civ.gameInfo.ruleset.ai
        for (other in civ.gameInfo.civilizations) {
            if (!isPlayerValid(civ, other)) continue
            if (civ.isAtWarWith(other)) memory.turnsAtWar[other.civID] = (memory.turnsAtWar[other.civID] ?: 0) + 1
            else memory.turnsAtWar.remove(other.civID)
        }
        WarStateUpdateAi.update(civ, memory)
        updateAverageGoldRate(civ, memory, rules)
        updateBadShapeForWar(civ, memory, rules)
        StrengthEstimateAi.update(civ)
        updateBadShapeForWar(civ, memory, rules)
        updateEasyTargets(civ, memory, rules)
    }

    /** CvDiplomacyAI::IsEasyTarget (Vox Populi): as of the last [updateEasyTargets] */
    fun isEasyTarget(civ: Civilization, other: Civilization): Boolean = other.civID in civ.ai.warStates.easyTargets

    /**
     *  CvDiplomacyAI::DoUpdateEasyTargets (Vox Populi, Release-5.4.8; D7), after the bad-shape test, for every valid player. As VP's code
     *  does, only a player we are at war with can be an easy target (without a war its war state is none, which counts as below
     *  stalemate). Nearly won: easy. Below stalemate, or we are in terrible shape, they are far, or 30 turns at war without taking a
     *  city of theirs for 30 turns: not easy. A city-state: easy when a favourable target or better. Not easy either when another war of
     *  ours (unless this one is phony) goes troubled or worse, or (for a major target) calm or worse against a foe still taking new
     *  wars. Easy when they are in terrible shape. Otherwise deterrents (we are unhappy, they took more of our cities than we of theirs,
     *  their combat rating beats ours, we are less aggressive, we do not want to conquer them, our cities are exposed to them -
     *  [MilitaryAi.isExposedToEnemy]) against aggravators (a coop war against them, they are unhappy and we are not, we took more of their
     *  cities, our rating beats theirs, we are more aggressive, we want to conquer them, a preferred attack target among their cities -
     *  [MilitaryAi.havePreferredAttackTarget]) choose between their raw and adjusted target value and strength (one form each here) and
     *  settle it by VP's table. Personality: more aggressive for a conqueror (primary or secondary victory pursuit) or with the warmonger
     *  or expansionist trait ([CivAi.hasTraitCategory]), less for a scientist (primary pursuit) or with the nerd trait (neither for
     *  Assyria, CvPlayerTraits::IsTechFromCityConquer) or the smaller trait, neither with both. Nuclear Gandhi has no input.
     */
    fun updateEasyTargets(civ: Civilization, memory: WarStateMemory, rules: AiRuleset) {
        val ai = civ.ai
        val assyriaException = civ.hasUnique(UniqueType.TechnologyFromConqueringCity)
        var moreAggressive = ai.isConqueror() || ai.isSecondaryConqueror() || ai.hasTraitCategory(TraitCategory.Warmonger) ||
            ai.hasTraitCategory(TraitCategory.Expansionist)
        var lessAggressive = (ai.isScientist() && !assyriaException) ||
            (ai.hasTraitCategory(TraitCategory.Nerd) && !assyriaException) || ai.hasTraitCategory(TraitCategory.Smaller)
        if (moreAggressive && lessAggressive) {
            moreAggressive = false
            lessAggressive = false
        }
        val terrible = memory.terribleShapeForWar
        val valid = civ.gameInfo.civilizations.filter { isPlayerValid(civ, it) }
        // The tests below (the early-game competitor) read last turn's easy targets while the new ones are found
        memory.easyTargets = valid.filter { isEasyTargetNow(civ, it, memory, valid, terrible, moreAggressive, lessAggressive, rules) }
            .mapTo(HashSet()) { it.civID }
    }

    private fun isEasyTargetNow(civ: Civilization, other: Civilization, memory: WarStateMemory, valid: List<Civilization>, terrible: Boolean,
                                moreAggressive: Boolean, lessAggressive: Boolean, rules: AiRuleset): Boolean {
        val state = memory.warStates[other.civID]
        if (state == WarState.NearlyWon) return true
        if (state == null || state < WarState.Stalemate) return false
        if (terrible) return false
        if (proximityRank(civ.getProximity(other)) <= proximityRank(Proximity.Far)) return false
        val ourMemory = civ.ai.diplomacy.memoryOf(other)
        val sinceCapture = if (ourMemory.lastCityCaptureTurn < 0) Int.MAX_VALUE else civ.gameInfo.turns - ourMemory.lastCityCaptureTurn
        if ((memory.turnsAtWar[other.civID] ?: 0) >= 30 && sinceCapture >= 30) return false
        if (other.isCityState) return (memory.targetValues[other.civID] ?: TargetValue.Average) >= TargetValue.Favorable
        if (!WarIntentAi.isPhonyWar(civ, other)) for (third in valid) {
            if (third == other || !civ.isAtWarWith(third)) continue
            val thirdState = memory.warStates[third.civID] ?: continue
            if (thirdState <= WarState.Troubled) return false
            if (!isNoNewWars(third) && thirdState <= WarState.Calm) return false
        }
        if (isInTerribleShapeForWar(other)) return true
        val unhappy = civ.isEmpireUnhappy()
        val theirMemory = other.ai.diplomacy.memoryOf(civ)
        val coop = CoopWarAi.globalState(civ, other)
        val ourRating = WarDamageAi.militaryRating(civ)
        val theirRating = WarDamageAi.militaryRating(other)
        val start = WarDamageAi.startingMilitaryRating(civ)
        val skillBoost = ourRating > start && ourRating > theirRating
        val skillPenalty = !skillBoost && theirRating > start && theirRating > ourRating
        val wantsConquest = civ.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules) ||
            state == WarState.Offensive || isPlayerRecklessExpander(civ, other) ||
            VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules) || CompetitorAi.biggestCompetitor(civ) == other.civID ||
            ourMemory.opinion == MajorCivOpinion.Unforgivable || civ.ai.diplomacy.warmongerThreat(other) >= AiThreat.Severe ||
            CoopWarAi.isLockedIntoCoopWar(civ, other, rules) || CompetitorAi.isEarlyGameCompetitor(civ, other, rules)
        val deterrents = listOf(unhappy, skillPenalty, ourMemory.numCitiesCapturedBy > theirMemory.numCitiesCapturedBy,
            lessAggressive, !wantsConquest, civ.ai.military.isExposedToEnemy(civ, null, other)).count { it }
        val aggravators = listOf(coop == CoopWarState.Soon || coop == CoopWarState.Accepted, !unhappy && other.isEmpireUnhappy(),
            theirMemory.numCitiesCapturedBy > ourMemory.numCitiesCapturedBy, skillBoost, moreAggressive, wantsConquest,
            civ.ai.military.havePreferredAttackTarget(other)).count { it }
        val noNewWars = memory.noNewWars && !civ.isAtWarWith(other)
        // Raw values (without our boldness) when we are held back, else the biased ones
        val raw = noNewWars || deterrents > aggravators
        val target = (if (raw) memory.rawTargetValues[other.civID] else memory.targetValues[other.civID]) ?: TargetValue.Average
        val military = (if (raw) memory.rawMilitaryStrengths[other.civID] else memory.militaryStrengths[other.civID]) ?: StrengthLevel.Average
        val economic = ourMemory.economicStrength ?: StrengthLevel.Average
        if (target <= TargetValue.Bad || military == StrengthLevel.Immense || economic == StrengthLevel.Immense) return false
        if (target >= TargetValue.Soft || military == StrengthLevel.Pathetic || economic == StrengthLevel.Pathetic) return true
        if ((aggravators < 3 && deterrents >= aggravators + 2) || (aggravators >= 3 && deterrents >= aggravators + 3)) return false
        if (aggravators > deterrents) {
            if (military <= StrengthLevel.Poor && economic <= StrengthLevel.Strong) return true
            if (military <= StrengthLevel.Weak && economic <= StrengthLevel.Powerful) return true
            if (economic <= StrengthLevel.Poor && military <= StrengthLevel.Strong) return true
            if (economic <= StrengthLevel.Weak && military <= StrengthLevel.Powerful) return true
            if (military <= StrengthLevel.Poor && target >= TargetValue.Favorable) return true
            if (military <= StrengthLevel.Weak && target >= TargetValue.Average) return true
            if (economic <= StrengthLevel.Poor && target >= TargetValue.Favorable) return true
            if (economic <= StrengthLevel.Weak && target >= TargetValue.Average) return true
        }
        return easyByPersonality(military, economic, target, aggravators, deterrents, noNewWars, moreAggressive, lessAggressive)
    }

    /** The last cases of DoUpdateEasyTargets, by how aggressive we are: an even balance of reasons with a favourable and poor target;
     *  a more aggressive player's wider margins; then a weak army or a weak economy with the other not too strong */
    private fun easyByPersonality(military: StrengthLevel, economic: StrengthLevel, target: TargetValue, aggravators: Int,
                                  deterrents: Int, noNewWars: Boolean, moreAggressive: Boolean, lessAggressive: Boolean): Boolean {
        if (!lessAggressive && !noNewWars && aggravators == deterrents && aggravators > 1 && target >= TargetValue.Favorable &&
            military <= StrengthLevel.Poor && economic <= StrengthLevel.Poor) return true
        if (moreAggressive && target >= TargetValue.Average && aggravators + 1 >= deterrents && !noNewWars) {
            if (military <= StrengthLevel.Poor) {
                if (aggravators + 1 > deterrents && economic <= StrengthLevel.Strong) return true
                if (economic <= StrengthLevel.Average || target >= TargetValue.Favorable) return true
            }
            if (economic <= StrengthLevel.Poor && military <= StrengthLevel.Average) return true
        }
        if (military <= StrengthLevel.Weak) {
            if (lessAggressive && economic <= StrengthLevel.Poor) return true
            if (!lessAggressive && economic <= StrengthLevel.Average) return true
            if (moreAggressive && economic <= StrengthLevel.Strong) return true
            if (target >= TargetValue.Favorable) return true
        }
        if (economic <= StrengthLevel.Weak) {
            if (lessAggressive && military <= StrengthLevel.Poor) return true
            if (!lessAggressive && military <= StrengthLevel.Average) return true
            if (moreAggressive && military <= StrengthLevel.Strong) return true
            if (target >= TargetValue.Favorable) return true
        }
        return false
    }

    /**
     *  CvPlayer::DoTestEmpireInBadShapeForWar (Vox Populi, Release-5.4.8; D7), after the war states and strengths as VP's second call:
     *  in terrible shape for war, and taking no new wars, when losing all wars, bankrupt (no gold and no income), without our original
     *  capital, very unhappy (BNW's rule: excess happiness at or below VERY_UNHAPPY_THRESHOLD), or doing badly (troubled or worse)
     *  against a powerful major that is close; no new wars either while doing badly against another major,
     *  or at a stalemate with a neighbour or a close player whose military posture is aggressive, unless that war is phony
     *  ([WarIntentAi.isPhonyWar]) or they are an easy target ([isEasyTarget], last turn's as in VP).
     */
    fun updateBadShapeForWar(civ: Civilization, memory: WarStateMemory, rules: AiRuleset) {
        val terrible = isTerribleShapeNow(civ, memory)
        // The phony-war test below reads last turn's flags, as VP sets them only at the end
        val noNewWars = terrible || civ.gameInfo.civilizations.any { other ->
            if (!other.isMajorCiv || !other.isAlive() || !civ.isAtWarWith(other)) return@any false
            val state = memory.warStates[other.civID] ?: return@any false
            val theirProximity = proximityRank(other.getProximity(civ))
            when {
                state <= WarState.Troubled -> true
                state != WarState.Stalemate || isEasyTarget(civ, other) || WarIntentAi.isPhonyWar(civ, other) -> false
                theirProximity == proximityRank(Proximity.Neighbors) -> true
                else -> theirProximity == proximityRank(Proximity.Close) &&
                    civ.ai.diplomacy.memoryOf(other).militaryPosture > AggressivePosture.None
            }
        }
        memory.terribleShapeForWar = terrible
        memory.noNewWars = noNewWars
    }

    private fun isTerribleShapeNow(civ: Civilization, memory: WarStateMemory): Boolean {
        if (memory.stateAllWars == StateAllWars.Losing) return true
        if (civ.gold <= 0 && memory.avgGoldRate <= 0) return true
        if (VictoryProgressAi.hasLostCapital(civ)) return true
        if (civ.isEmpireVeryUnhappy()) return true
        return civ.gameInfo.civilizations.any { other ->
            other.isMajorCiv && other.isAlive() && civ.isAtWarWith(other) &&
                (memory.warStates[other.civID] ?: WarState.Calm) <= WarState.Troubled &&
                (memory.militaryStrengths[other.civID] ?: StrengthLevel.Average) >= StrengthLevel.Powerful &&
                proximityRank(other.getProximity(civ)) >= proximityRank(Proximity.Close)
        }
    }

    /** CvPlayer::IsNoNewWars (Vox Populi): see [updateBadShapeForWar] */
    fun isNoNewWars(civ: Civilization): Boolean = civ.ai.warStates.noNewWars

    /** CvPlayer::IsInTerribleShapeForWar (Vox Populi): see [updateBadShapeForWar] */
    fun isInTerribleShapeForWar(civ: Civilization): Boolean = civ.ai.warStates.terribleShapeForWar

    /** CvDiplomacyAI::IsRecklessExpander (Vox Populi, D7): the flag [com.unciv.logic.automation.diplomacy.ExpanderAi] keeps each turn */
    fun isPlayerRecklessExpander(civ: Civilization, other: Civilization): Boolean =
        civ.ai.diplomacy.memories[other.civID]?.recklessExpander == true

    /** PlayerProximityTypes order of the original: DISTANT 0, FAR 1, CLOSE 2, NEIGHBORS 3; no cities (NO_PLAYER_PROXIMITY) -1 */
    fun proximityRank(proximity: Proximity): Int = when (proximity) {
        Proximity.None -> -1
        Proximity.Distant -> 0
        Proximity.Far -> 1
        Proximity.Close -> 2
        Proximity.Neighbors -> 3
    }
    //endregion

    //region CvPlot::IsHomeFrontForPlayer

    /** CvPlot::IsHomeFrontForPlayer: owned by [civ], or closer than [range] to one of its cities */
    fun isHomeFrontFor(tile: Tile, civ: Civilization, range: Int): Boolean {
        if (tile.getOwner() == civ) return true
        return civ.cities.any { it.getCenterTile().aerialDistanceTo(tile) < range }
    }
    //endregion

    //region Power (CvUnit::GetPower, CvCity::GetPower, CvPlayer::getPower)

    fun cityPower(city: City): Int = CityCombatant(city).getCityStrength().toDouble().pow(1.5).toInt()

    /** CvPlayer::getPower: military might plus economic might ([economicMight]) */
    fun getPower(civ: Civilization): Int = civ.ai.militaryMight() + economicMight(civ)

    /**
     *  CvPlayer::calculateEconomicMight (Vox Populi, Release-5.4.8; D7): 5 + the population and the yields of all cities (calculateTotalYield:
     *  food as the cities produce it, before their citizens eat; production, science and gold; culture as the civilization makes it,
     *  policies included), reduced for each city: x100 over 100 + 10 per city, at most 300 (the more cities, the less each can defend).
     *  VP also adds the yields of instant bonuses over the game's turns, which Unciv does not keep.
     */
    fun economicMight(civ: Civilization): Int {
        var economicMight = 5 + civ.cities.sumOf { it.population.population }
        for (city in civ.cities) {
            val stats = city.cityStats.currentCityStats
            val eaten = city.cityStats.finalStatList["Population"]?.food ?: 0f
            economicMight += (stats.food - eaten + stats.production + stats.science + stats.gold).toInt()
        }
        economicMight += civ.stats.statsForNextTurn.culture.toInt()
        return economicMight * 100 / minOf(300, civ.cities.size * 10 + 100)
    }
    //endregion
}
