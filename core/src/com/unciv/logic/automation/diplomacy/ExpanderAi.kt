package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  Vox Populi's reckless expanders and wonder spammers (CvDiplomacyAI::DoUpdateRecklessExpanders, DoUpdateWonderSpammers;
 *  Release-5.4.8; D7), kept each turn in [MajorCivMemory.recklessExpander] and [MajorCivMemory.wonderSpammer], and the two terms of
 *  part 7 of SelectBestApproachTowardsMajorCiv that read them. On our scale: VP's personality bias is ours x 100.
 *  The wonder spammer update reads the current victory pursuit (going for world conquest, a culture victory), the approach terms the
 *  leader's victory pursuits (primary or secondary).
 */
object ExpanderAi {
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal

    /** CvPlayer::getTotalLand: the tiles the civilization owns */
    fun totalLand(civ: Civilization): Int = civ.cities.sumOf { it.tiles.size }

    /** CvGame::CalculateMedian*: the upper middle of the sorted values, 0 without any */
    fun median(values: List<Int>): Int = if (values.isEmpty()) 0 else values.sorted()[values.size / 2]

    /** CvGame::CalculateMedianNumWondersConstructed: over the majors that built any */
    fun medianWonders(civ: Civilization): Int =
        median(civ.gameInfo.civilizations.filter { it.isMajorCiv }.map { it.ai.diplomacy.wondersConstructed }.filter { it > 0 })

    private fun competing(civ: Civilization): Boolean =
        VictoryProgressAi.isCompetingForVictory(civ) && civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } >= 2

    /**
     *  DoUpdateRecklessExpanders: an AI competing for victory marks a valid major close to us with at least 4 cities, more cities and
     *  more land than us, and no difficult target (our bad or worse), when its cities are above RECKLESS_EXPANDER_CITIES_THRESHOLD % of
     *  the median and 1.5 times the average of the others, or its land above RECKLESS_EXPANDER_LAND_THRESHOLD % of the median and 1.5
     *  times the others' average (the living majors with cities).
     */
    fun updateRecklessExpanders(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        if (civ.isHuman()) return
        val ai = civ.ai.diplomacy
        for (memory in ai.memories.values) memory.recklessExpander = false
        if (!competing(civ)) return
        val players = civ.gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() }
        val medianCities = median(players.map { it.cities.size })
        val medianLand = median(players.map { totalLand(it) })
        val totalCities = players.sumOf { it.cities.size }.toDouble()
        val totalPlots = players.sumOf { totalLand(it) }.toDouble()
        val ourLand = totalLand(civ)
        for (other in others) {
            if (!other.isMajorCiv) continue
            val cities = other.cities.size
            val land = totalLand(other)
            if (cities < 4 || DiplomacyTurn.proximity(civ, other) < DiplomacyTurn.PROXIMITY_CLOSE) continue
            if (cities <= civ.cities.size || land <= ourLand) continue
            // VP's TARGET_VALUE_DIFFICULT or worse (none counts as VP's NO_TARGET_VALUE, below it)
            if ((WarStateAi.targetValue(civ, other) ?: TargetValue.Impossible) <= TargetValue.Difficult) continue
            val averageCities = (totalCities - cities) / maxOf(1, players.size - 1)
            val averageLand = (totalPlots - land) / maxOf(1, players.size - 1)
            ai.memoryOf(other).recklessExpander =
                (cities * 100 > medianCities * rules.define(AiDefine.RECKLESS_EXPANDER_CITIES_THRESHOLD) && cities > averageCities * 1.5) ||
                    (land * 100 > medianLand * rules.define(AiDefine.RECKLESS_EXPANDER_LAND_THRESHOLD) && land > averageLand * 1.5)
        }
    }

    /**
     *  DoUpdateWonderSpammers: an AI competing for victory marks a valid major that built more than 2 world wonders and more than
     *  we did, close enough to matter (any distance for a cultural leader; for a conqueror far, or close without ocean crossing; for
     *  others close, or neighbours without ocean crossing), when its wonders exceed the median (of the majors that built any) by more
     *  than WONDER_SPAMMER_THRESHOLD less the handicap's WonderBlockMod, and are at least 1.5 times their average.
     */
    fun updateWonderSpammers(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        if (civ.isHuman()) return
        val ai = civ.ai.diplomacy
        for (memory in ai.memories.values) memory.wonderSpammer = false
        if (!competing(civ)) return
        val builders = civ.gameInfo.civilizations.filter { it.isMajorCiv }.map { it.ai.diplomacy.wondersConstructed }.filter { it > 0 }
        val medianWonders = median(builders)
        val averageWonders = builders.sum().toDouble() / maxOf(1, builders.size)
        val cultural = civ.ai.hasTraitCategory(TraitCategory.Tourism) || civ.ai.isGoingForCultureVictory()
        val conqueror = civ.ai.hasTraitCategory(TraitCategory.Warmonger) || civ.ai.isGoingForWorldConquest()
        val canCrossOcean = civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean
        val blockMod = handicapValue(civ, "WonderBlockMod", 0)
        for (other in others) {
            if (!other.isMajorCiv) continue
            val wonders = other.ai.diplomacy.wondersConstructed
            if (wonders <= 2 || ai.wondersConstructed >= wonders) continue
            val proximity = DiplomacyTurn.proximity(civ, other)
            if (!cultural) {
                val needed = when {
                    conqueror && canCrossOcean -> DiplomacyTurn.PROXIMITY_FAR
                    conqueror || canCrossOcean -> DiplomacyTurn.PROXIMITY_CLOSE
                    else -> DiplomacyTurn.PROXIMITY_NEIGHBORS
                }
                if (proximity < needed) continue
            }
            if (wonders > medianWonders + rules.define(AiDefine.WONDER_SPAMMER_THRESHOLD) - blockMod && wonders >= averageWonders * 1.5)
                ai.memoryOf(other).wonderSpammer = true
        }
    }

    private fun handicapValue(civ: Civilization, column: String, default: Int): Int =
        civ.gameInfo.ruleset.ai.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get(column) ?: default

    /**
     *  Part 7, reckless expanders and wonder spammers: war, hostility and deception 2 x bias, 2 x more for a conqueror (primary or
     *  secondary victory pursuit, or the warmonger trait; deception only when it applies) or, against a wonder spammer, a cultural leader
     *  too (primary or secondary pursuit, or the tourism
     *  trait); war 3 x more against an easy target with a preferred attack target, or with our unique unit in the field; scaled by
     *  boldness (reckless expander) or wonder competitiveness (wonder spammer) x the handicap's LandDisputePercent or
     *  WonderBlockPercent / 500.
     */
    fun addExpanderWeights(civ: Civilization, other: Civilization, deception: Boolean, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val memory = civ.ai.diplomacy.memories[other.civID] ?: return
        val conqueror = civ.ai.isConqueror() || civ.ai.isSecondaryConqueror() || civ.ai.hasTraitCategory(TraitCategory.Warmonger)
        val cultural = civ.ai.isCultural() || civ.ai.isSecondaryCultural() || civ.ai.hasTraitCategory(TraitCategory.Tourism)
        val uniqueUnitActive = ApproachAdditionalAi.hasUniqueUnitTech(civ, rules) && ApproachAdditionalAi.hasUniqueUnitActive(civ, rules)
        val easyAttack = WarStateAi.isEasyTarget(civ, other) && civ.ai.military.havePreferredAttackTarget(other)
        fun add(stronger: Boolean, personality: Int, difficulty: Int) {
            var war = bias(MajorCivApproach.War) * 2
            var hostile = bias(MajorCivApproach.Hostile) * 2
            var deceptive = bias(MajorCivApproach.Deceptive) * 2
            if (stronger) {
                war += bias(MajorCivApproach.War) * 2
                hostile += bias(MajorCivApproach.Hostile) * 2
                if (deception) deceptive += bias(MajorCivApproach.Deceptive) * 2
            }
            if (easyAttack || uniqueUnitActive) war += bias(MajorCivApproach.War) * 3
            w[WAR] += war * personality * difficulty / 500
            w[HOSTILE] += hostile * personality * difficulty / 500
            w[DECEPTIVE] += deceptive * personality * difficulty / 500
        }
        if (memory.recklessExpander)
            add(conqueror, civ.ai.diplomacyValue(DiplomacyPersonality.Boldness), handicapValue(civ, "LandDisputePercent", 100))
        if (memory.wonderSpammer)
            add(conqueror || cultural, civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness),
                handicapValue(civ, "WonderBlockPercent", 100))
    }
}
