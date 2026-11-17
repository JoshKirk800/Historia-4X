package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.max

/**
 *  CvPlayer::GetBestMilitaryCity: the city where the military buildings of a unit combat class or a domain pay off most, as
 *  CvPlayer::UpdateBestMilitaryCities rates the cities. Computed when asked (the original keeps the last result and moves it only to a
 *  city rated higher, which differs only when no city has any rating at all).
 *
 *  The original's tables map onto Unciv's uniques: the domain rows (Building_DomainFreeExperiences, Building_DomainProductionModifiers) are
 *  uniques whose unit filter fits every military unit of a domain ("Land", "Water", "Military" for each); the unit combat rows
 *  (Building_UnitCombatFreeExperiences, Building_UnitCombatProductionModifiers) are the other filters, taken for a combat class
 *  (Units.CombatClass of the AI data) when every unit of that class fits the filter. Not read: the city's own free experience and the
 *  experience from earlier Golden Ages, the experience from Great Works, the production modifiers of the city's religion beliefs, the free
 *  promotions of the city (+50 per unit combat), VP-only rows.
 */
object MilitaryCities {
    enum class UnitDomain {
        Land, Sea, Air;

        fun contains(unit: BaseUnit) = when (this) {
            Land -> unit.isLandUnit
            Sea -> unit.isWaterUnit
            Air -> unit.isAirUnit()
        }
    }

    /** Units.CombatClass (UNITCOMBAT_*) of [unit], null if it has none */
    private fun combatClassOf(civ: Civilization, unit: BaseUnit): String? = civ.gameInfo.ruleset.ai.unitAiTypesOf(unit.name)?.combatClass

    /** A domain row of the original: [filter] fits every military unit of [domain] */
    private fun filterIsDomain(civ: Civilization, filter: String, domain: UnitDomain): Boolean {
        val units = civ.gameInfo.ruleset.units.values.filter { it.isMilitary && domain.contains(it) }
        return units.isNotEmpty() && units.all { it.matchesFilter(filter) }
    }

    /** A unit combat row of the original: [filter] is no domain row and fits every unit of [combatClass] */
    private fun filterIsCombatClass(civ: Civilization, filter: String, combatClass: String): Boolean {
        if (UnitDomain.entries.any { filterIsDomain(civ, filter, it) }) return false
        val units = civ.gameInfo.ruleset.units.values.filter { combatClassOf(civ, it) == combatClass }
        return units.isNotEmpty() && units.all { it.matchesFilter(filter) }
    }

    /** The unit combat classes of the ruleset's units */
    fun combatClasses(civ: Civilization): List<String> =
        civ.gameInfo.ruleset.units.values.mapNotNull { combatClassOf(civ, it) }.distinct()

    /** "New [filter] units start with [amount] XP [city filter]" of a city (its buildings' and the civilization's that apply to it) */
    fun experienceUniques(city: City): List<Unique> =
        city.getMatchingUniques(UniqueType.UnitStartingExperience).filter { city.matchesFilter(it.params[2]) }.toList()

    fun experienceUniques(building: Building): List<Unique> =
        building.getMatchingUniques(UniqueType.UnitStartingExperience, GameContext.IgnoreConditionals).toList()

    /** "[amount]% Production when constructing [filter] units [city filter]" of a city, as [experienceUniques] */
    fun productionUniques(city: City): List<Unique> =
        city.getMatchingUniques(UniqueType.PercentProductionUnits).filter { city.matchesFilter(it.params[2]) }.toList()

    fun productionUniques(building: Building): List<Unique> =
        building.getMatchingUniques(UniqueType.PercentProductionUnits, GameContext.IgnoreConditionals).toList()

    /** The experience the [uniques] ([experienceUniques]) give new units of the domain */
    fun domainExperience(civ: Civilization, uniques: List<Unique>, domain: UnitDomain): Int =
        uniques.filter { filterIsDomain(civ, it.params[0], domain) }.sumOf { it.params[1].toInt() }

    fun classExperience(civ: Civilization, uniques: List<Unique>, combatClass: String): Int =
        uniques.filter { filterIsCombatClass(civ, it.params[0], combatClass) }.sumOf { it.params[1].toInt() }

    /** The production bonus the [uniques] ([productionUniques]) give units of the domain */
    fun domainProduction(civ: Civilization, uniques: List<Unique>, domain: UnitDomain): Int =
        uniques.filter { filterIsDomain(civ, it.params[1], domain) }.sumOf { it.params[0].toInt() }

    fun classProduction(civ: Civilization, uniques: List<Unique>, combatClass: String): Int =
        uniques.filter { filterIsCombatClass(civ, it.params[1], combatClass) }.sumOf { it.params[0].toInt() }

    /**
     *  CvPlayer::UpdateBestMilitaryCities for a unit combat class: the city with the highest value, the first one on a tie; null if no city
     *  has any. The value is its production (raw hammers per turn / 5), plus the city's production bonus and experience for the class
     *  (at least 1 each when they exist).
     */
    fun bestForCombatClass(civ: Civilization, combatClass: String): City? {
        var best: City? = null
        var bestValue = 0
        for (city in civ.cities) {
            var value = ProductionSanityAi.rawProductionTimes100(city) / 500
            val production = classProduction(civ, productionUniques(city), combatClass)
            if (production > 0) value += max(1, production)
            val experience = classExperience(civ, experienceUniques(city), combatClass)
            if (experience > 0) value += max(1, experience)
            if (value > bestValue) {
                bestValue = value
                best = city
            }
        }
        return best
    }

    /**
     *  CvPlayer::UpdateBestMilitaryCities for a domain: the city with the highest value, the first one on a tie; null if no city has any.
     *  The value is its production (raw hammers per turn / 5), plus the city's experience and production bonus for the domain (at least
     *  1 each when they exist), doubled for each unit combat class the city is the best city of.
     */
    fun bestForDomain(civ: Civilization, domain: UnitDomain): City? {
        val classBest = combatClasses(civ).map { bestForCombatClass(civ, it) }
        var best: City? = null
        var bestValue = 0
        for (city in civ.cities) {
            var value = ProductionSanityAi.rawProductionTimes100(city) / 500
            val experience = domainExperience(civ, experienceUniques(city), domain)
            if (experience > 0) value += max(1, experience)
            val production = domainProduction(civ, productionUniques(city), domain)
            if (production > 0) value += max(1, production)
            for (bestCity in classBest) if (bestCity == city && value <= Int.MAX_VALUE / 2) value *= 2
            if (value > bestValue) {
                bestValue = value
                best = city
            }
        }
        return best
    }
}
