package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.UniqueTarget
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

/**
 *  A spy mission a spy can do with the Network Points it has stored in a foreign major's city (SpyMissions.json; a `CityEventChoices` row with `IsEspionageMission` in Vox Populi,
 *  CvPlayerEspionage::ProcessSpyMissionResult, V:CvEspionageClasses.cpp:1556-1785, CvCity::CanDoEventChoice, V:CvCity.cpp:4590-4770). What a mission does is its [uniques]
 *  (target `SpyMission`); the cost, the chances to be found and to die and what the city must be are the fields. Only with [com.unciv.models.ModConstants.espionageLikeVp].
 */
class SpyMission : RulesetObject() {
    /** The technology the spy's owner must have researched (`PrereqTech`) */
    var requiredTech: String? = null
    /** The Network Points the spy must have stored in the city, and which the mission spends whether or not it succeeds (CvCityEspionage::DoMission), at standard speed */
    var networkPoints = 0
    /** Whether [networkPoints] scales with the game speed (`NetworkPointsScaling`): multiplied by the speed's production cost modifier (`TrainPercent`) */
    var networkPointsScaledBySpeed = false
    /** The percent chance that the spy is identified, before the counterspy foci of the spy's owner (CvPlayer::GetSpyIdentificationChance) */
    var identifyChance = 0
    /** The percent chance that the spy is killed (CvPlayer::GetSpyKillChance) */
    var killChance = 0
    /** The rank the spy needs (`SpyLevelRequired` + 1: the original counts ranks from 0, ours from 1) */
    var requiredSpyRank = 1
    /** What the city must be, as a city filter: "Capital" for `CapitalOnly` */
    var cityFilter = "All"

    override fun getUniqueTarget() = UniqueTarget.SpyMission

    /** There is no Civilopedia category for spy missions */
    override fun makeLink() = ""

    /** CvModEventCityChoiceInfo::GetNetworkPointsNeededScaled: the cost times the speed's percent over 100, in whole numbers */
    @Readonly
    fun getNetworkPointsNeeded(speed: Speed): Int =
        if (networkPointsScaledBySpeed) networkPoints * (speed.productionCostModifier * 100).roundToInt() / 100 else networkPoints
}
