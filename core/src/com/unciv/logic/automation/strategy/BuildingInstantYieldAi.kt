package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max

/**
 *  What the yield shapes of Vox Populi's economy add to CityStrategyAIHelpers::GetBuildingYieldValue (V:CvCityStrategyAI.cpp:3407-4260), for the shapes that
 *  Unciv's engine has since the instant yield events and the per-turn yield shapes: [terms] adds up, for one yield of one city, what the building's uniques that fire on
 *  an event give (the `iInstant` part of the original, which [BuildingYieldAi.yieldValue] then divides by the city's rate and multiplies by the era), what they add to the
 *  flat yield (`iFlatYield`) and to the modifier (`iModifier`).
 *
 *  A building's event yield is a unique `Gain [n] [stat] <upon EVENT>` ([UniqueType.OneTimeGainStat]) or `Gain [n]% of the triggering amount as [stat] <upon EVENT>`
 *  ([UniqueType.OneTimeGainStatOfTriggeringAmount]); without an event it is the building's own instant yield (`Building_InstantYield`). The amount is the one of the
 *  column, not scaled by the speed, nor by the era (the original multiplies all instant yields by the era afterwards), nor by the citizens (`<for every>` is not read).
 *  A unique that says `in this city` is the building's city table, any other the `...Global` table. The values per event, by the column they replace:
 *  - GPExpend: n x max(10, (100 + the great people rate) / 10); WLTKDStart: a twentieth of n, as a modifier; Construction, Tech: n; Victory (`upon defeating a unit`): a
 *    fifth of n for the city table, n for the global one; Pillage: n, x10 for the global one;
 *  - Birth (`upon population growth`): max(1, 50 - population) ("we want these as early as possible") + n + the city's food + the city's growth percentage;
 *  - BorderGrowth (`upon acquiring a tile through border growth`): n;
 *  - Purchase (`upon buying something with [Gold]`): n, x the cities for the global table; FaithPurchase ([Faith]): n, twice for a Religious civilization;
 *  - UnitProduction (`upon completing a unit`): n for each domain (land, sea, air) of which this is the best military city ([MilitaryCities.bestForDomain]);
 *  - GoldenAgeStart (`upon entering a Golden Age`, a flat yield): max(1, 3 x n x the golden age points a turn / what the next golden age needs).
 *
 *  Per-turn shapes: a process modifier (`[x]% yield from processes`) is a modifier of twice x for the yield of the process the city is running (the unique
 *  names no yield); yield from yield (`[x]% of [stat] added to [stat]`) is x percent of the city's rate of the first stat as a flat yield (VP divides the target rate by x, which is
 *  dimensionally wrong: a departure); the yield of a Great Work type (`[stats] from each [type] Great Work`) is the stats for each slot of the city, or for half of
 *  the slots of the empire for a global one (the type is not read, as in the original); `Capital's [stats] per [n] citizens in the empire` is the flat yield in the capital.
 *
 *  None, because VP's input is not in the engine: unit level up, combat experience and death yields (P6), the border growth part of the plot culture cost modifiers
 *  (`getPlotCultureCostModifier`), the investment modifiers of policies and traits added to the purchase yields, the retroactive and growth extra yields, the policy
 *  unlock, the spy, long count, trade route end and victory in a Golden Age yields (P3, P4, P5), the traits' `WLTKDGPImprovementModifier` and `GrowthBoon`, the
 *  Golden Age yields per age (`<for every [Golden Ages]>` flows through the building's stats like any other multiplier).
 */
object BuildingInstantYieldAi {
    /** What [terms] found: the instant, flat and modifier part of [BuildingYieldAi.yieldValue] */
    class Terms {
        var flat = 0
        var instant = 0
        var modifier = 0
    }

    private val ignoreConditionals = GameContext.IgnoreConditionals

    /** The event of a unique: its first modifier that is a trigger condition, null for a building's own instant yield */
    private fun eventOf(unique: Unique): Unique? =
        unique.modifiers.firstOrNull { it.type?.targetTypes?.contains(UniqueTarget.TriggerCondition) == true }

    /** The yield terms [building] would add to [stat] in [city] (see the class comment) */
    fun terms(city: City, building: Building, stat: Stat): Terms {
        val terms = Terms()
        eventYields(city, building, stat, terms)
        perTurnShapes(city, building, stat, terms)
        return terms
    }

    private fun eventYields(city: City, building: Building, stat: Stat, terms: Terms) {
        val civ = city.civ
        val numCities = civ.cities.size
        for (type in listOf(UniqueType.OneTimeGainStat, UniqueType.OneTimeGainStatOfTriggeringAmount))
            for (unique in building.getMatchingUniques(type, ignoreConditionals)) {
                if (unique.params[1] != stat.name) continue
                val amount = unique.params[0].toInt()
                if (amount <= 0) continue
                val local = unique.isLocalEffect
                val event = eventOf(unique)
                when (event?.type) {
                    null -> terms.instant += amount
                    UniqueType.TriggerUponExpendingUnit ->
                        terms.instant += amount * max(10, (100 + BuildingValueAi.greatPeopleRate(city)) / 10)
                    UniqueType.TriggerUponWltkdStart -> terms.modifier += amount / 20
                    UniqueType.TriggerUponConstructingBuilding, UniqueType.TriggerUponConstructingBuildingCityFilter,
                    UniqueType.TriggerUponResearch -> terms.instant += amount
                    UniqueType.TriggerUponDefeatingUnit -> terms.instant += if (local) amount / 5 else amount
                    UniqueType.TriggerUponPillaging -> terms.instant += if (local) amount else amount * 10
                    UniqueType.TriggerUponPopulationGrowth -> terms.instant += birthValue(city, amount)
                    UniqueType.TriggerUponAcquiringTile ->
                        if (event.params[1] == "border growth") terms.instant += amount
                    UniqueType.TriggerUponBuying -> terms.instant += purchaseValue(city, event.params[0], amount, local, numCities)
                    UniqueType.TriggerUponCompletingUnit -> for (domain in MilitaryCities.UnitDomain.entries)
                        if (MilitaryCities.bestForDomain(civ, domain) == city) terms.instant += amount
                    UniqueType.TriggerUponEnteringGoldenAge -> terms.flat += goldenAgeStartValue(city, amount)
                    else -> {}
                }
            }
    }

    /** BIRTH of GetBuildingYieldValue: earlier is better, the city's food and growth percentage make it come sooner */
    private fun birthValue(city: City, amount: Int): Int {
        var growth = 0
        city.forEachMatchingUnique(UniqueType.GrowthPercentBonus) { if (city.matchesFilter(it.params[1])) growth += it.params[0].toInt() }
        return max(1, 50 - city.population.population) + amount + city.cityStats.currentCityStats.food.toInt() + growth
    }

    /** PURCHASE and FAITH_PURCHASE of GetBuildingYieldValue (the investment modifiers of policies and traits are not read: none exist) */
    private fun purchaseValue(city: City, paidWith: String, amount: Int, local: Boolean, numCities: Int): Int = when (paidWith) {
        Stat.Gold.name -> if (local) amount else amount * numCities
        Stat.Faith.name -> if (city.civ.ai.hasTraitCategory(TraitCategory.Religious)) amount * 2 else amount
        else -> 0
    }

    /** YieldFromGoldenAgeStart: an estimate of how often we start a golden age (the points a turn come from the cities' happiness) */
    private fun goldenAgeStartValue(city: City, amount: Int): Int {
        val civ = city.civ
        val pointsPerTurn = if (civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) civ.stats.needs.goldenAgePointsFromHappiness()
            else max(0, civ.getHappiness())
        return max(1, 3 * amount * pointsPerTurn / max(1, civ.goldenAges.happinessRequiredForNextGoldenAge()))
    }

    private fun perTurnShapes(city: City, building: Building, stat: Stat, terms: Terms) {
        val civ = city.civ

        val processStat = (city.cityConstructions.getCurrentConstruction() as? PerpetualConstruction.StatConversion)?.stat
        if (processStat == stat)
            for (unique in building.getMatchingUniques(UniqueType.ProcessYieldPercent, ignoreConditionals))
                if (unique.params[0].toInt() > 0) terms.modifier += unique.params[0].toInt() * 2

        for (unique in building.getMatchingUniques(UniqueType.StatPercentAddedToStat, ignoreConditionals)) {
            val percent = unique.params[0].toInt()
            if (percent <= 0 || unique.params[3] != stat.name) continue
            val source = Stat.valueOf(unique.params[1])
            val cities = if (unique.isLocalEffect) listOf(city) else civ.cities.filter { it.matchesFilter(unique.params[2]) }
            terms.flat += cities.sumOf { it.cityStats.currentCityStats[source].toInt() * percent / 100 }
        }

        for (unique in building.getMatchingUniques(UniqueType.StatsFromGreatWorkType, ignoreConditionals)) {
            val change = unique.stats[stat].toInt()
            if (change <= 0) continue
            terms.flat += change *
                if (unique.isLocalEffect) city.greatWorks.getSlots().size else civ.cities.sumOf { it.greatWorks.getSlots().size } / 2
        }

        if (city.isCapital())
            for (unique in building.getMatchingUniques(UniqueType.CapitalStatsPerEmpirePopulation, ignoreConditionals)) {
                val change = unique.stats[stat].toInt()
                if (change > 0) terms.flat += change * civ.cities.sumOf { it.population.population } / unique.params[1].toInt()
            }
    }
}
