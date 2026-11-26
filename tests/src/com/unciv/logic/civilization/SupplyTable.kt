package com.unciv.logic.civilization

import com.unciv.models.ruleset.Ruleset

/**
 *  The unit supply columns of the Brave New World ruleset's difficulties as the Community Patch had them before the content flip of the supply plan (`phaseE/m6-supply-deep.md`
 *  section 10 and the flip): a human 10/3, 7/3, 7/2, then 5/2; an AI and a city-state 7, 3, 50 percent with the bonus 0, 0, 10, 20, 30, 30, 40, 50.
 *  The supply tests derive their numbers by hand from this table and the original's constants, so they set it themselves and restore what the ruleset ships ([restore]):
 *  a [TestGame]'s ruleset objects are cached across classes.
 */
object SupplyTable {
    private val levels = listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity")
    private val humanBase = listOf(10, 7, 7, 5, 5, 5, 5, 5)
    private val humanPerCity = listOf(3, 3, 2, 2, 2, 2, 2, 2)
    private val bonus = listOf(0, 0, 10, 20, 30, 30, 40, 50)

    private class Saved(val base: Int, val perCity: Int, val ai: List<Int>, val cityState: List<Int>)
    private val saved = mutableMapOf<String, Saved>()
    private var savedPerPopulation: Double? = null

    /** The table, and half a human citizen per unit (ModConstants.unitSupplyPerPopulation, 0.2 once flipped). The switch of the buildings is left to the test */
    fun communityPatch(ruleset: Ruleset) {
        val constants = ruleset.modOptions.constants
        if (savedPerPopulation == null) savedPerPopulation = constants.unitSupplyPerPopulation
        constants.unitSupplyPerPopulation = 0.5
        for ((i, name) in levels.withIndex()) {
            val level = ruleset.difficulties[name]!!
            saved.getOrPut(name) {
                Saved(level.unitSupplyBase, level.unitSupplyPerCity,
                    listOf(level.aiUnitSupplyBase, level.aiUnitSupplyPerCity, level.aiUnitSupplyPopulationPercent, level.aiUnitSupplyBonusPercent),
                    listOf(level.cityStateUnitSupplyBase, level.cityStateUnitSupplyPerCity, level.cityStateUnitSupplyPopulationPercent, level.cityStateUnitSupplyBonusPercent))
            }
            level.unitSupplyBase = humanBase[i]
            level.unitSupplyPerCity = humanPerCity[i]
            level.aiUnitSupplyBase = 7
            level.aiUnitSupplyPerCity = 3
            level.aiUnitSupplyPopulationPercent = 50
            level.aiUnitSupplyBonusPercent = bonus[i]
            level.cityStateUnitSupplyBase = 7
            level.cityStateUnitSupplyPerCity = 3
            level.cityStateUnitSupplyPopulationPercent = 50
            level.cityStateUnitSupplyBonusPercent = bonus[i]
        }
    }

    /** What the ruleset shipped before [communityPatch] */
    fun restore(ruleset: Ruleset) {
        savedPerPopulation?.let { ruleset.modOptions.constants.unitSupplyPerPopulation = it }
        savedPerPopulation = null
        for ((name, s) in saved) {
            val level = ruleset.difficulties[name]!!
            level.unitSupplyBase = s.base
            level.unitSupplyPerCity = s.perCity
            level.aiUnitSupplyBase = s.ai[0]; level.aiUnitSupplyPerCity = s.ai[1]; level.aiUnitSupplyPopulationPercent = s.ai[2]; level.aiUnitSupplyBonusPercent = s.ai[3]
            level.cityStateUnitSupplyBase = s.cityState[0]; level.cityStateUnitSupplyPerCity = s.cityState[1]
            level.cityStateUnitSupplyPopulationPercent = s.cityState[2]; level.cityStateUnitSupplyBonusPercent = s.cityState[3]
        }
        saved.clear()
    }
}
