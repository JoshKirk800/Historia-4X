package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/**
 *  The influence of a diplomatic mission to a city-state (CvUnit::canTrade and getTradeInfluence, V:CvUnit.cpp:11990-12062) in the original's integer order: every percentage multiplies, then
 *  divides by 100 and truncates, so the results are the original's and not a float's. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. Only with [ModConstants.diplomaticMissionsLikeVp].
 *
 *  Not built: the Great Diplomat's other half (CvUnit::trade, V:CvUnit.cpp:12087-12170: lowering the influence of the other civilizations, the resting point change, the memory of
 *  who lowered whose influence). The era is [Civilization.getEraNumber], as in the other influence formulas of this port (rigging, protection, bullying).
 */
object DiplomaticMission {
    /**
     *  The whole-point influence. [base] is the sum of `[n] Influence from diplomatic missions` of the unit and its promotions (`GetDiploMissionInfluence`), or null for a unit with none, which
     *  then has [ModConstants.DiplomaticMissionConstants.noMissionInfluence] (`MINOR_FRIENDSHIP_FROM_TRADE_MISSION`); [cityBoost] is what the origin city's buildings add and only counts with a [base]
     *  (`GetDiplomatInfluenceBoost`, V:CvUnit.cpp:12032-12041). Then, each one truncating: + [perEra] x [era]; x (100 + [unitPercent]) / 100; x (100 + [civPercent]) / 100;
     *  x (100 + [ModConstants.DiplomaticMissionConstants.questBoostPercent]) / 100 if [questActive] (V:CvUnit.cpp:12043-12059)
     */
    @Readonly
    fun influence(
        constants: ModConstants.DiplomaticMissionConstants,
        base: Int?, cityBoost: Int, perEra: Int, era: Int,
        unitPercent: Int, civPercent: Int, questActive: Boolean
    ): Int {
        var influence = if (base != null) base + cityBoost else constants.noMissionInfluence
        influence += perEra * era
        influence *= 100 + unitPercent
        influence /= 100
        influence *= 100 + civPercent
        influence /= 100
        if (questActive) {
            influence *= 100 + constants.questBoostPercent
            influence /= 100
        }
        return influence
    }

    /** A unit has a mission influence of its own: the uniques of it and of its promotions. null if it has none of the unique at all (not 0 from a sum of zeros) */
    @Readonly
    fun baseOf(unit: MapUnit): Int? {
        var sum: Int? = null
        for (unique in unit.getMatchingUniques(UniqueType.InfluenceFromDiplomaticMissions)) {
            sum = (sum ?: 0) + unique.params[0].toInt()
        }
        return sum
    }

    /** `CvCity::GetDiplomatInfluenceBoost`: the sum over the buildings of THIS city only (not the civilization's, which would count the Wat of every city); 0 without a city */
    @Readonly
    fun cityBoostOf(city: City?): Int {
        if (city == null) return 0
        var sum = 0
        for (unique in city.getMatchingUniques(UniqueType.InfluenceFromDiplomaticMissionsOfCity, includeCivUniques = false))
            sum += unique.params[0].toInt()
        return sum
    }

    /** Whether [cityState] has assigned the quest of [ModConstants.DiplomaticMissionConstants.questName] to [owner] (`IsActiveQuestForPlayer(owner, MINOR_CIV_QUEST_INFLUENCE)`, V:CvUnit.cpp:12052) */
    @Readonly
    fun hasQuest(constants: ModConstants.DiplomaticMissionConstants, cityState: Civilization, owner: Civilization): Boolean =
        cityState.questManager.getAssignedQuestsFor(owner).any { it.questName == constants.questName }

    /**
     *  What [unit] gives [cityState]: the origin city is the unit's, else the owner's capital (V:CvUnit.cpp:12034-12041). The unit's own percent is
     *  [UniqueType.PercentInfluenceFromDiplomaticMissions] on the unit (and its promotions); the civilization's is the same unique from its policies and other sources
     */
    @Readonly
    fun influenceOf(unit: MapUnit, cityState: Civilization): Int {
        val owner = unit.civ
        val constants = owner.gameInfo.ruleset.modOptions.constants.diplomaticMission
        val base = baseOf(unit)
        val origin = unit.getOriginCity() ?: owner.getCapital()
        var perEra = 0
        for (unique in unit.getMatchingUniques(UniqueType.InfluenceFromDiplomaticMissionsPerEra)) perEra += unique.params[0].toInt()
        var unitPercent = 0
        for (unique in unit.getMatchingUniques(UniqueType.PercentInfluenceFromDiplomaticMissions)) unitPercent += unique.params[0].toInt()
        var civPercent = 0
        for (unique in owner.getMatchingUniques(UniqueType.PercentInfluenceFromDiplomaticMissions)) civPercent += unique.params[0].toInt()
        return influence(constants, base, if (base != null) cityBoostOf(origin) else 0, perEra, owner.getEraNumber(), unitPercent, civPercent, hasQuest(constants, cityState, owner))
    }
}
