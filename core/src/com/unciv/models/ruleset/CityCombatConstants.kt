package com.unciv.models.ruleset

/** Staged city combat inputs; VP 5.4.8 DefineChanges.sql and CvCity::updateStrengthValue, GetMaxHitPoints, doTurn. */
data class CityCombatConstants(
    var baseHealth: Int = 250,
    var healthPerPopulation: Int = 8,
    var baseStrengthHundredths: Int = 500,
    var minorStrengthPerPopulationHundredths: Int = 8,
    var hillStrengthHundredths: Int = 200,
    var minorAllyDefensePercent: Int = 25,
    var minorProtectionDefensePercent: Int = 5,
    var minorProtectionDefenseMaxPercent: Int = 25,
    var landGarrisonDivisor: Int = 200,
    var navalGarrisonDivisor: Int = 400,
    var rangedStrengthModifier: Int = 0,
    var healing: Int = 8,
    var safeHealingMultiplier: Int = 3,
    /** Json can only instantiate a concrete map; its value type is read from this declaration */
    var defenseProcesses: HashMap<String, CityDefenseProcess> = HashMap(),
    var landBlockadeRange: Int = 1,
    var navalBlockadeRange: Int = 1,
)

/** VP CvCity::doTurn process defense value, per-turn increase and positive cap. */
data class CityDefenseProcess(
    var defense: Int = 0,
    var defensePerTurn: Int = 0,
    var defenseCap: Int = 0,
    var requiredTech: String? = null,
)
