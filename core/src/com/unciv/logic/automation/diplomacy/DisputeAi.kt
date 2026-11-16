package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.CityStateAi
import com.unciv.logic.automation.civilization.MinorCivHelpers
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  The disputes and postures of CvDiplomacyAI: land (Vox Populi's DoUpdateLandDisputeLevels, by contested plots), the expansion and plot
 *  buying postures, wonders (DoUpdateWonderDisputeLevels), city-states (DoUpdateMinorCivDisputeLevels) and military posture
 *  (DoUpdateMilitaryAggressivePostures). The victory dispute (DoUpdateVictoryDisputeLevels) is not ported: in the original nothing but an
 *  end-of-friendship text reads it.
 */
object DisputeAi {

    /**
     *  Vox Populi's CvDiplomacyAI::DoUpdateLandDisputeLevels (an AI's; a human's levels stay as they are): towards a player closer than
     *  far, the contested plot score of our cities ([contestedPlotScore]) x (100 + multiplier) / 100, the multiplier LAND_DISPUTE_CRAMPED_MULTIPLIER
     *  when we are cramped, +50 when bold (boldness above 7, the warmonger or expansionist trait, competing for victory by world conquest,
     *  or an expansion flavor with the grand strategy above 7), +100 in the Ancient era, +50 to the Classical, +25 to the Medieval
     *  (our era); then LAND_DISPUTE_FIERCE / STRONG / WEAK_THRESHOLD. VP's settle-spot thief (the player who settled near the best city
     *  site VP's GetBestSettlePlot had found, raising the level) needs VP's settle plot search, which is not ported: none.
     */
    fun updateLandDisputes(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        if (civ.isHuman()) return
        val ai = civ.ai
        val era = civ.getEraNumber()
        val bold = ai.diplomacyValue(DiplomacyPersonality.Boldness) > 7 || ai.hasTraitCategory(TraitCategory.Warmonger) ||
            ai.hasTraitCategory(TraitCategory.Expansionist) || (VictoryProgressAi.isCompetingForVictory(civ) && ai.isGoingForWorldConquest()) ||
            ai.personalityAndGrandStrategy(Flavor.Expansion) > 7
        var multiplier = if (ai.isCramped) rules.define(AiDefine.LAND_DISPUTE_CRAMPED_MULTIPLIER) else 0
        if (bold) multiplier += 50
        multiplier += when {
            era <= rules.define(AiDefine.ANCIENT_ERA) -> 100
            era <= rules.define(AiDefine.CLASSICAL_ERA) -> 50
            era <= rules.define(AiDefine.MEDIEVAL_ERA) -> 25
            else -> 0
        }
        for (other in others) {
            val memory = ai.diplomacy.memoryOf(other)
            memory.lastTurnLandDispute = memory.landDispute
            if (DiplomacyTurn.proximity(civ, other) < DiplomacyTurn.PROXIMITY_CLOSE) {
                memory.landDispute = DisputeLevel.None
                continue
            }
            val score = civ.cities.sumOf { contestedPlotScore(it, other) } * (100 + multiplier) / 100
            memory.landDispute = when {
                score >= rules.define(AiDefine.LAND_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                score >= rules.define(AiDefine.LAND_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                score >= rules.define(AiDefine.LAND_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                else -> DisputeLevel.None
            }
        }
    }

    /**
     *  Vox Populi's CvCity::GetContestedPlotScore: the tiles within the city's work range (at most 5) that [other] owns and that yield
     *  something (food, production, gold, science, culture or faith; CvPlot::hasYield), 3 each in the first ring, 2 in the second, 1 beyond
     */
    fun contestedPlotScore(city: City, other: Civilization): Int {
        val range = city.getWorkRange().coerceIn(1, 5)
        val center = city.getCenterTile()
        var score = 0
        for (tile in center.getTilesInDistance(range)) {
            if (tile.getOwner() != other) continue
            val stats = tile.stats.getTileStats(tile.getCity(), other)
            if (stats.food <= 0f && stats.production <= 0f && stats.gold <= 0f && stats.science <= 0f && stats.culture <= 0f && stats.faith <= 0f)
                continue
            score += when (center.aerialDistanceTo(tile)) {
                0, 1 -> 3
                2 -> 2
                else -> 1
            }
        }
        return score
    }

    /** CvDiplomacyAI::GetTotalLandDisputeLevel */
    fun totalLandDisputeLevel(civ: Civilization, others: List<Civilization>, rules: AiRuleset): Int {
        var total = 0
        for (other in others) total += when (civ.ai.diplomacy.memoryOf(other).landDispute) {
            DisputeLevel.Fierce -> rules.define(AiDefine.AI_DIPLO_LAND_DISPUTE_WEIGHT_FIERCE)
            DisputeLevel.Strong -> rules.define(AiDefine.AI_DIPLO_LAND_DISPUTE_WEIGHT_STRONG)
            DisputeLevel.Weak -> rules.define(AiDefine.AI_DIPLO_LAND_DISPUTE_WEIGHT_WEAK)
            DisputeLevel.None -> 0
        }
        return total
    }

    /** CvDiplomacyAI::DoUpdateWonderDisputeLevels (an AI's): wonders they beat us to × WonderCompetitiveness */
    fun updateWonderDisputes(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        if (civ.isHuman()) return
        val competitiveness = civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness)
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            val weight = memory.numWondersBeatenTo * competitiveness
            memory.wonderDispute = when {
                weight >= rules.define(AiDefine.WONDER_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                weight >= rules.define(AiDefine.WONDER_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                weight >= rules.define(AiDefine.WONDER_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                else -> DisputeLevel.None
            }
        }
    }

    /**
     *  Vox Populi's CvDiplomacyAI::DoUpdateMinorCivDisputeLevels (an AI's): over the city-states we have met and do not mean to conquer or
     *  bully, those that are our friends: one allied with them adds city-state competitiveness² / 2 when we are friendly towards it;
     *  one friends with them adds competitiveness² when it is our ally, else takes it away. When either of us is its ally, our influence
     *  / 5 more, and competitiveness² scaled by how close our influences are (x 4 within 20 down to x 0.2 / 0.4 within 200, by who
     *  leads; x 1.5 more when we protect it) - or our influence when tied. MINOR_CIV_DISPUTE_FIERCE / STRONG / WEAK_THRESHOLD. The
     *  times they lowered our influence (VP's coups and quests) have no counterpart: none.
     */
    fun updateMinorCivDisputes(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        if (civ.isHuman()) return
        val competitiveness = civ.ai.diplomacyValue(DiplomacyPersonality.MinorCivCompetitiveness)
        val personalityMod = competitiveness * competitiveness
        val minors = civ.gameInfo.getAliveCityStates().filter { minor ->
            civ.knows(minor) && civ.ai.minorCivApproaches[minor.civID].let { it != MinorCivApproach.Conquest && it != MinorCivApproach.Bully }
        }
        for (other in others) {
            if (!other.isMajorCiv) continue
            var weight = 0
            for (minor in minors) {
                if (!CityStateAi.isFriends(minor, civ)) continue
                var oneIsAllied = false
                if (CityStateAi.isAllies(minor, other)) {
                    if (civ.ai.minorCivApproaches[minor.civID] == MinorCivApproach.Friendly) weight += personalityMod / 2
                    oneIsAllied = true
                } else if (CityStateAi.isFriends(minor, other)) {
                    if (CityStateAi.isAllies(minor, civ)) {
                        weight += personalityMod
                        oneIsAllied = true
                    } else weight -= personalityMod
                }
                if (!oneIsAllied) continue
                val ours = MinorCivHelpers.influence(minor, civ)
                val theirs = MinorCivHelpers.influence(minor, other)
                weight += ours / 5
                if (ours == theirs) {
                    weight += ours
                    continue
                }
                val tenths = if (theirs > ours) when (theirs - ours) {
                    in 0..20 -> 40
                    in 21..40 -> 35
                    in 41..60 -> 30
                    in 61..80 -> 20
                    in 81..100 -> 10
                    in 101..120 -> 5
                    in 121..150 -> 3
                    in 151..200 -> 2
                    else -> 10
                } else when (ours - theirs) {
                    in 0..20 -> 40
                    in 21..40 -> 30
                    in 41..60 -> 20
                    in 61..80 -> 15
                    in 81..100 -> 10
                    in 101..120 -> 8
                    in 121..150 -> 6
                    in 151..200 -> 4
                    else -> 10
                }
                var influenceStart = personalityMod * tenths / 10
                if (civ in minor.cityStateFunctions.getProtectorCivs()) influenceStart = influenceStart * 15 / 10
                weight += influenceStart
            }
            civ.ai.diplomacy.memoryOf(other).minorCivDispute = when {
                weight >= rules.define(AiDefine.MINOR_CIV_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                weight >= rules.define(AiDefine.MINOR_CIV_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                weight >= rules.define(AiDefine.MINOR_CIV_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                else -> DisputeLevel.None
            }
        }
    }

    /** CvDiplomacyAI::DoUpdateMilitaryAggressivePostures for every player in [others] */
    fun updateMilitaryPostures(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) updateMilitaryPosture(civ, other, rules)
    }

    /**
     *  CvDiplomacyAI::DoUpdateOnePlayerMilitaryAggressivePosture: their visible combat units (CvUnit::IsCombatUnit: a melee strength, so no aircraft)
     *  on our home front, 10 each and 5 in their own land. The original's test for units near someone they are at war with ends in a
     *  `continue` of the innermost loop, so it excludes nothing: those units count too.
     */
    fun updateMilitaryPosture(civ: Civilization, other: Civilization, rules: AiRuleset) {
        val range = rules.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.lastTurnMilitaryPosture = memory.militaryPosture
        var value = 0
        for (unit in other.units.getCivUnits()) {
            if (!unit.isMilitary() || unit.baseUnit.isAirUnit()) continue
            val tile = unit.getTile()
            if (!tile.isVisible(civ) || !WarStateAi.isHomeFrontFor(tile, civ, range)) continue
            value += if (tile.getOwner() == other) 5 else 10
        }
        memory.militaryPosture = when {
            value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_INCREDIBLE) -> AggressivePosture.Incredible
            value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_HIGH) -> AggressivePosture.High
            value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_MEDIUM) -> AggressivePosture.Medium
            value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_LOW) -> AggressivePosture.Low
            else -> AggressivePosture.None
        }
    }

    /** CvDiplomacyAI::DoUpdateExpansionAggressivePostures for every player in [others] */
    fun updateExpansionPostures(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) updateExpansionPosture(civ, other, rules)
    }

    /**
     *  CvDiplomacyAI::DoUpdateOnePlayerExpansionAggressivePosture: their most aggressive self-founded city (not the capital) by its
     *  distance to our capital; several cities tied at Medium or High bump the level by one.
     */
    fun updateExpansionPosture(civ: Civilization, other: Civilization, rules: AiRuleset) {
        val ourCapital = civ.getCapital(firstCityIfNoCapital = false)?.getCenterTile() ?: return
        // Irrelevant if we can't declare war on them
        if (!civ.isAtWarWith(other) && civ.getDiplomacyManager(other)?.canDeclareWar() != true) return
        val theirCapital = other.getCapital(firstCityIfNoCapital = false)?.getCenterTile() ?: return
        val capitalsDistance = theirCapital.aerialDistanceTo(ourCapital)
        var most = AggressivePosture.None
        var numMost = 0
        for (city in other.cities) {
            if (city.isCapital() || city.foundingCivObject != other) continue
            val tile = city.getCenterTile()
            val toUs = ourCapital.aerialDistanceTo(tile)
            val toTheirCapital = theirCapital.aerialDistanceTo(tile)
            var posture = when {
                toUs <= rules.define(AiDefine.EXPANSION_CAPITAL_DISTANCE_AGGRESSIVE_POSTURE_HIGH) -> AggressivePosture.High
                toUs <= rules.define(AiDefine.EXPANSION_CAPITAL_DISTANCE_AGGRESSIVE_POSTURE_MEDIUM) -> AggressivePosture.Medium
                toUs <= rules.define(AiDefine.EXPANSION_CAPITAL_DISTANCE_AGGRESSIVE_POSTURE_LOW) -> AggressivePosture.Low
                else -> AggressivePosture.None
            }
            // Closer to our capital than to theirs: at least medium
            if (posture == AggressivePosture.Low && toUs < toTheirCapital) posture = AggressivePosture.Medium
            // Farther from their capital than our capitals are apart: incredible
            if (posture >= AggressivePosture.Medium && capitalsDistance < toTheirCapital) posture = AggressivePosture.Incredible
            if (posture == most) numMost++
            else if (posture > most) {
                most = posture
                numMost = 0
            }
            if (most == AggressivePosture.Incredible) break
        }
        if (numMost > 1 && most > AggressivePosture.Low && most < AggressivePosture.Incredible) most = AggressivePosture.entries[most.ordinal + 1]
        civ.ai.diplomacy.memoryOf(other).expansionPosture = most
    }

    /** CvDiplomacyAI::DoUpdatePlotBuyingAggressivePosture: tiles they bought within 3 tiles of our cities */
    fun updatePlotBuyingPostures(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            val score = memory.numPlotsBoughtNearUs
            memory.plotBuyingPosture = when {
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_INCREDIBLE_THRESHOLD) -> AggressivePosture.Incredible
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_HIGH_THRESHOLD) -> AggressivePosture.High
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_MEDIUM_THRESHOLD) -> AggressivePosture.Medium
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_LOW_THRESHOLD) -> AggressivePosture.Low
                else -> AggressivePosture.None
            }
        }
    }
}
