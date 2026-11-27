package com.unciv.logic.civilization.transients

/**
 *  CvPlayer::UnitSupplyBreakdown (Vox Populi 5.4.8, CvPlayer.h): the parts of the unit supply, as
 *  [CivInfoStatsForNextTurn.getUnitSupplyBreakdown] makes them with `unitSupplyLikeVp`. "Gross" is before the technology reductions, "net" after.
 *  [total] is the supply ([CivInfoStatsForNextTurn.getUnitSupply]); [Int.MAX_VALUE] for the barbarians, who have no limit.
 */
data class UnitSupplyBreakdown(
    val techProgressPercent: Int = 0,
    val citiesTechDivisorPercent: Int = 100,
    val populationTechDivisorPercent: Int = 100,
    val difficultyGross: Int = 0,
    val difficultyNet: Int = 0,
    val citiesGross: Int = 0,
    val citiesNet: Int = 0,
    val populationGross: Int = 0,
    val populationNet: Int = 0,
    val greatPeople: Int = 0,
    val subtotal: Int = 0,
    val effectiveCities: Int = 0,
    val empireSizeDivisorPercent: Int = 100,
    val afterEmpireSize: Int = 0,
    val difficultyModifierPercent: Int = 100,
    /** The supply before the war weariness reduction (iBeforeWarWeariness) */
    val beforeWarWeariness: Int = 0,
    /** The percent of the supply that the war weariness takes (iWarWearinessPct): 0 unless `unitSupplyWarWearinessLikeVp` */
    val warWearinessPercent: Int = 0,
    val total: Int = 0,
)
