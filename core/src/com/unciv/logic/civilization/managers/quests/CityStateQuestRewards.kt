package com.unciv.logic.civilization.managers.quests

import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.models.ModConstants
import yairm210.purity.annotations.Readonly
import kotlin.math.max

/**
 *  The kinds of reward of a city-state quest under [ModConstants.cityStateQuestsLikeVp] (V:CvMinorCivAI.cpp:329-1067, `SmallAwards`). [growsWithEra]: the base is scaled by the era
 *  of the civilization, [speedScaled]: by the speed's instant yield percentage; [name] is the key of the multiplier tables of [ModConstants.CityStateQuestConstants].
 */
enum class QuestRewardKind(val growsWithEra: Boolean, val speedScaled: Boolean) {
    Influence(true, false),
    Gold(true, true),
    Science(true, true),
    Culture(true, true),
    Faith(true, true),
    GoldenAgePoints(true, true),
    Food(true, true),
    Production(true, true),
    Tourism(true, true),
    Happiness(false, false),
    GreatPersonPoints(true, false),
    GreatPersonPointsEverywhere(true, false),
    GeneralPoints(true, true),
    AdmiralPoints(true, true),
    Experience(false, false),
    Juggernauts(false, false),
}

/** The calculation of the rewards of a quest (CvMinorCivQuest::CalculateRewards, V:CvMinorCivAI.cpp:329-895) in whole numbers, with the divisions of the original in the same order */
object CityStateQuestRewards {
    /** `iEraScaler` (V:347-352): 100, plus half of 100 for each era of the civilization after the first; [era] is the 0-based era number (Ancient is 0) */
    @Readonly
    fun eraScalerPercent(constants: ModConstants.CityStateQuestConstants, era: Int) =
        if (era > 1) 100 + constants.eraScalerPercent * (era - 1) else 100

    /**
     *  CvMinorCivAI::GetQuestRewardModifier (V:4949-4975): 100, plus the percent that the policies of the civilization add ([increasedPercent]), plus that of the pledge to
     *  protect, plus that of the personality of the city-state, times the influence modifier of the religion if it is the same one ([religionPercent], 0 for none); not below 0
     */
    @Readonly
    fun modifierPercent(constants: ModConstants.CityStateQuestConstants, increasedPercent: Int, protectedByThem: Boolean, personality: CityStatePersonality?, religionPercent: Int = 0): Int {
        var modifier = 100 + increasedPercent
        if (protectedByThem) modifier += constants.protectionBoost
        if (personality == CityStatePersonality.Friendly) modifier += constants.friendlyModifier
        else if (personality == CityStatePersonality.Hostile) modifier += constants.hostileModifier
        modifier = modifier * (100 + religionPercent) / 100
        return max(modifier, 0)
    }

    /**
     *  The rewards of a quest for a civilization in era [era]: from the [base] amounts of each kind (kinds without one give none), the same [random] share for all (V:354-360,
     *  `randRangeInclusive(0, SmallAwards.Random) * 2`), the [modifier] of [modifierPercent], the speed's [instantYieldPercent], and the multipliers of the [type] and
     *  [personality] of the city-state. Happiness has the base and half the era (V:681-692); Juggernauts the base alone (V:760-770).
     */
    @Readonly
    fun calculate(
        constants: ModConstants.CityStateQuestConstants,
        base: Map<QuestRewardKind, Int>,
        era: Int,
        random: Int,
        modifier: Int,
        instantYieldPercent: Int,
        type: String,
        personality: String,
    ): Map<QuestRewardKind, Int> {
        val eraScaler = eraScalerPercent(constants, era)
        val result = LinkedHashMap<QuestRewardKind, Int>()
        for ((kind, amount) in base) {
            if (amount <= 0) continue
            var bonus = when (kind) {
                QuestRewardKind.Happiness -> amount + era / 2
                QuestRewardKind.Juggernauts -> amount
                QuestRewardKind.Experience -> (amount + random) * modifier / 100
                else -> ((amount * eraScaler / 100 + random) * modifier / 100)
            }
            if (kind.speedScaled) bonus = bonus * instantYieldPercent / 100
            constants.typeMultipliers[kind.name]?.get(type)?.let { bonus = bonus * it / 100 }
            constants.personalityMultipliers[kind.name]?.get(personality)?.let { bonus = bonus * it / 100 }
            result[kind] = bonus
        }
        return result
    }

    /** The rewards of a pledge or any other change that recalculates them (CalculateRewards with bRecalc, V:360-880): each kind only ever rises */
    @Readonly
    fun recalculated(old: Map<QuestRewardKind, Int>, new: Map<QuestRewardKind, Int>): Map<QuestRewardKind, Int> {
        val result = LinkedHashMap(old)
        for ((kind, amount) in new) if (amount > (result[kind] ?: 0)) result[kind] = amount
        return result
    }

    /** DoRewards for a partial quest (V:900-915): everything but the number of juggernauts is halved (their starting XP is, by the caller) */
    fun halved(rewards: Map<QuestRewardKind, Int>): Map<QuestRewardKind, Int> =
        rewards.mapValues { (kind, amount) -> if (kind == QuestRewardKind.Juggernauts) amount else amount / 2 }
}
