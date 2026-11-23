package com.unciv.logic.city

import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.Conditionals
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import yairm210.purity.annotations.Readonly
import kotlin.math.max

/**
 *  What a resource a city has changes in what it builds (slice T1 of `phaseE/vp-trade-plan.md`): the production of wonders
 *  ([UniqueType.ResourceWonderProduction], `Resources.WonderProductionMod`, V:CvCity.cpp:18351-18420) and the cost of units
 *  ([UniqueType.ResourceProductionCost], `Resource_UnitCombatProductionCostModifiersLocal`, V:CvCity.cpp:11422-11452).
 *
 *  A city has a resource (CvCity::IsHasResourceLocal) when one of its tiles with it is improved: [com.unciv.logic.map.tile.Tile.providesResources].
 *  The modifier `<before the [era]>` of these uniques is the original's obsolete era and is compared with the era of what is built, the technology of the
 *  wonder or unit, and not with the era of the civilization, as the conditional does elsewhere: it is left out of the conditionals that are evaluated here.
 */
object ResourceProductionModifiers {
    @Readonly
    private fun hasLocally(city: City, resource: TileResource) =
        city.getTiles().any { it.tileResource == resource && it.providesResources(city.civ) }

    /** Whether the conditionals of [unique] hold for [city] and, if it has `<before the [era]>`, [constructionEra] is before that era */
    @Readonly
    private fun applies(unique: Unique, city: City, constructionEra: Int?): Boolean {
        for (modifier in unique.modifiers) {
            if (modifier.type == UniqueType.ConditionalBeforeEra) {
                val obsolete = city.getRuleset().eras[modifier.params[0]]?.eraNumber ?: continue
                if (constructionEra != null && constructionEra >= obsolete) return false
            } else if (!Conditionals.conditionalApplies(unique, modifier, city.state)) return false
        }
        return true
    }

    @Readonly
    private fun eraOfTech(city: City, tech: String?): Int? =
        tech?.let { city.getRuleset().technologies[it]?.column?.era }?.let { city.getRuleset().eras[it]?.eraNumber }

    /** The percent a wonder adds to the production of [city]: the sum over the resources the city has (V:CvCity.cpp:18387-18412) */
    @Readonly
    fun wonderPercent(city: City, wonder: Building): Int {
        if (!wonder.isAnyWonder()) return 0
        val era = eraOfTech(city, wonder.requiredTech)
        var percent = 0
        for (resource in city.getRuleset().tileResources.values) {
            val uniques = resource.getMatchingUniques(UniqueType.ResourceWonderProduction, GameContext.IgnoreConditionals).toList()
            if (uniques.isEmpty() || !hasLocally(city, resource)) continue
            for (unique in uniques) if (applies(unique, city, era)) percent += unique.params[0].toInt()
        }
        return percent
    }

    /** The production [cost] of [unit] in [city] after the resources the city has: x (100 + the sum of the changes) / 100, at least 1 (V:CvCity.cpp:11422-11452) */
    @Readonly
    fun unitCost(city: City, unit: BaseUnit, cost: Int): Int {
        val era = eraOfTech(city, unit.requiredTech) ?: city.civ.getEraNumber()
        var change = 0
        for (resource in city.getRuleset().tileResources.values) {
            val uniques = resource.getMatchingUniques(UniqueType.ResourceProductionCost, GameContext.IgnoreConditionals).toList()
            if (uniques.isEmpty() || !hasLocally(city, resource)) continue
            for (unique in uniques)
                if (unit.matchesFilter(unique.params[1], city.state) && applies(unique, city, era)) change += unique.params[0].toInt()
        }
        return if (change == 0) cost else max(1, cost * (100 + change) / 100)
    }
}
