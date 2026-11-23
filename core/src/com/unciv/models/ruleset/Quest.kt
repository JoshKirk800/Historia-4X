package com.unciv.models.ruleset

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.quests.QuestImplementation
import com.unciv.logic.civilization.managers.quests.QuestRewardKind
import com.unciv.logic.civilization.managers.quests.IQuestImplementation
import com.unciv.models.stats.INamed
import yairm210.purity.annotations.Readonly

enum class QuestName(
    val value: String,
    private val implementation: IQuestImplementation
) : IQuestImplementation by implementation {
    None("", QuestImplementation.None()),
    Route("Route", QuestImplementation.Route()),
    ClearBarbarianCamp("Clear Barbarian Camp", QuestImplementation.ClearBarbarianCamp()),
    ConstructWonder("Construct Wonder", QuestImplementation.ConstructWonder()),
    ConnectResource("Connect Resource", QuestImplementation.ConnectResource()),
    GreatPerson("Acquire Great Person", QuestImplementation.GreatPerson()),
    ConquerCityState("Conquer City State", QuestImplementation.ConquerCityState()),
    FindPlayer("Find Player", QuestImplementation.FindPlayer()),
    FindNaturalWonder("Find Natural Wonder", QuestImplementation.FindNaturalWonder()),
    GiveGold("Give Gold", QuestImplementation.GiveGold()),
    PledgeToProtect("Pledge to Protect", QuestImplementation.PledgeToProtect()),
    ContestCulture("Contest Culture", QuestImplementation.ContestCulture()),
    ContestFaith("Contest Faith", QuestImplementation.ContestFaith()),
    ContestTech("Contest Technologies", QuestImplementation.ContestTech()),
    Invest("Invest", QuestImplementation.Invest()),
    BullyCityState("Bully City State", QuestImplementation.BullyCityState()),
    DenounceCiv("Denounce Civilization", QuestImplementation.DenounceCiv()),
    SpreadReligion("Spread Religion", QuestImplementation.SpreadReligion()),
    TradeRoute("Trade Route", QuestImplementation.TradeRoute()),
    ;
    companion object {
        fun find(value: String) = entries.firstOrNull { it.value == value } ?: None
    }
}

enum class QuestType {
    Individual,
    Global
}

/** [Quest] class holds all functionality relative to a quest */
// Notes: This is **not** `IsPartOfGameInfoSerialization`, only Ruleset.
// Saves contain [QuestManager]s instead, which contain lists of [AssignedQuest] instances.
// These are matched to this Quest **by name**.
class Quest : INamed {

    /** Unique identifier name of the quest, it is also shown.
     *  Must match a [QuestName.value] for the Quest to have any functionality. */
    override var name: String = ""

    val questNameInstance by lazy { QuestName.find(name) }  // lazy only ensures evaluation happens after deserialization, all will be 'triggered'

    /** Description of the quest shown to players */
    var description: String = ""

    /** [QuestType]: it is either Individual or Global */
    var type: QuestType = QuestType.Individual

    /** Influence reward gained on quest completion */
    var influence: Float = 40f

    /** The rewards of the other kinds (`SmallAwards` of Vox Populi), base amounts before the era and the multipliers: only with
     *  [com.unciv.models.ModConstants.cityStateQuestsLikeVp], which also reads [influence] as the base of the influence (see [baseRewards]). 0 is none */
    var gold = 0
    var science = 0
    var culture = 0
    var faith = 0
    var goldenAgePoints = 0
    var food = 0
    var production = 0
    var tourism = 0
    var happiness = 0
    var greatPersonPoints = 0
    var greatPersonPointsEverywhere = 0
    var generalPoints = 0
    var admiralPoints = 0
    var experience = 0
    var juggernauts = 0

    /** `SmallAwards.Random`: the random share of the rewards is 0 to this, twice */
    var randomMod = 0

    /**
     *  The weight of the quest for the era (the era's name) and the type and personality of the city-state ("Cultured|Friendly"), `MinorCivilizations_QuestWeights`; a missing entry is 0,
     *  and a quest with weight 0 is not given in the era. Only with [com.unciv.models.ModConstants.cityStateQuestsLikeVp], which ignores [weightForCityStateType]
     */
    var weightByEra = HashMap<String, HashMap<String, Int>>()

    /** Whether Vox Populi enables the quest (the types with weight 0 in all eras are off); only with [com.unciv.models.ModConstants.cityStateQuestsLikeVp] */
    var enabled = true

    /** The base amounts of all kinds for the rewards of [com.unciv.models.ModConstants.cityStateQuestsLikeVp] */
    @Readonly
    fun baseRewards(): Map<QuestRewardKind, Int> = mapOf(
        QuestRewardKind.Influence to influence.toInt(),
        QuestRewardKind.Gold to gold, QuestRewardKind.Science to science, QuestRewardKind.Culture to culture, QuestRewardKind.Faith to faith,
        QuestRewardKind.GoldenAgePoints to goldenAgePoints, QuestRewardKind.Food to food, QuestRewardKind.Production to production,
        QuestRewardKind.Tourism to tourism, QuestRewardKind.Happiness to happiness,
        QuestRewardKind.GreatPersonPoints to greatPersonPoints, QuestRewardKind.GreatPersonPointsEverywhere to greatPersonPointsEverywhere,
        QuestRewardKind.GeneralPoints to generalPoints, QuestRewardKind.AdmiralPoints to admiralPoints,
        QuestRewardKind.Experience to experience, QuestRewardKind.Juggernauts to juggernauts,
    )

    /** Maximum number of turns to complete the quest, 0 if there's no turn limit */
    var duration: Int = 0

    /** Minimum number of [Civilization]s needed to start the quest. It is meaningful only for [QuestType.Global]
     *  quests [type]. */
    var minimumCivs: Int = 1

    /** Certain city states are more likely to give certain quests
     * This is based on both personality and city-state type
     * Both are mapped here as 'how much to multiply the weight of this quest for this kind of city-state' */
    var weightForCityStateType = HashMap<String, Float>()

    /** Checks if `this` is a Global quest */
    @Readonly fun isGlobal(): Boolean = type == QuestType.Global
    @Readonly fun isIndividual(): Boolean = !isGlobal()
}
