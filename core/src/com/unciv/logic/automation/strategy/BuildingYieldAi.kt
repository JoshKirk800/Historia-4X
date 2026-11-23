package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.logic.civilization.managers.TradeRouteValue
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max

/**
 *  The yield loop of CvBuildingProductionAI::CheckBuildingBuildSanity (Vox Populi, CvBuildingProductionAI.cpp) with its helpers
 *  CityStrategyAIHelpers::GetBuildingYieldValue and GetBuildingTraitValue (CvCityStrategyAI.cpp): [yieldTerms] adds up what a building
 *  adds to each yield of the city, [yieldValue] and [traitValue] are the two helpers for one yield.
 *
 *  A yield's value is the percentage the building's flat yield adds to the city's rate (at least 1), instant yields (the gold of
 *  expending a great person) over the rate or half of themselves, and the modifiers (percent, made more important by era cubed) times the
 *  rate, then times the city's own modifier of the yield, the strategic importance of the yield (culture: the first culture building,
 *  victories of ours and of others; science, production and faith likewise), and double for the yields of the victory we go for.
 *  The loop adds those, scales production by 4, food by 10 for a small city (none for a city that avoids growth), 5 for the yield of
 *  the city's focus, +10 per gold per turn lost, and the maintenance check no longer applies to a building that helps a negative income.
 *
 *  BNW's building columns are read as the uniques that replace them (see [BuildingValueAi]): [Building.getStats] (the flat yields and the
 *  yield per building class that the city's other uniques give: VP's YieldChange, GetBuildingClassYieldChange of the player and its
 *  buildings, the policies' building class yields), [Building.getStatPercentageBonuses] (YieldModifier and the policies' building class
 *  modifiers), "[stats] [in this city]", "[stats] per [n] population", "[stats] for each major religion with followers in the city",
 *  "[stats] from [tile filter] tiles [city filter]" (the terrain, feature, resource, improvement, river, sea and lake yield changes, counted
 *  over the city's tiles as getPlotStats does, a resource only when revealed; a unique for all cities counts for each of them), "[stats] from
 *  every [building]" (BuildingClassLocalYieldChange: 5 times when the city has one, else 1, for each building class the filter
 *  fits), "[stats] from every [specialist]" (SpecialistYieldChange and its local twin), the stats of the specialists a building adds, the
 *  gold of trade routes for the city, "[x]% [stat] [city filter]" (YieldModifier, GlobalYieldModifier for all
 *  cities times their number) and "Gain [n] [stat] <upon expending a [unit] unit>". The "iYieldPolicyBonus"
 *  and building class yield changes of other buildings are those of the city (Unciv's "from every [building]" counts the buildings of
 *  the city it is in). The nation's uniques are the player traits. The yield rate is the city's yield, the base modifier its percentage
 *  bonus plus 100.
 *
 *  The shapes of the economy engine (instant yields of the events, birth, border growth, purchase and unit production yields, golden age start and WLTKD, process
 *  modifiers, yield from yield, great work yields, the capital's yield per citizens of the empire) are [BuildingInstantYieldAi]; era scaling and `<for every>` multipliers
 *  are read through [Building.getStats] and [Building.getMatchingUniques] like any other.
 *
 *  None, because VP's input is not in the engine: the theming bonus yields and accomplishments (P5), the yield per city strength and combat experience, level up and death
 *  yields (P6), the yield per franchise (P3's corporations, not ported: O1 of the trade plan), strategic resources of city-states, allies and friends and the spy yields (P4), the Maya long count,
 *  per theme, tech enhanced, per building, per tile, golden age yields per age, great person birth, policy unlock, area, distance to capital and religion
 *  building modifiers, the city connection yields, the yields BNW's Stats lack (tourism, golden age points, great general and admiral points, population,
 *  local culture, the JFD yields), the VP happiness flat reductions (distress, poverty, illiteracy, boredom, unrest: with the needs model [BuildingProductionAi] values
 *  them per unhappy citizen), and from the traits: the art,
 *  artifact, literature and music yield changes, the world wonder yields per great work type, VP's golden age timer, great scientist rate,
 *  tech boost from capital buildings, artist golden age tech boost, unique beliefs only, new cities with the capital religion, population boost
 *  from religion, starting pantheon, prophet fervor, faith from unimproved forests, diplomatic marriage and the combat bonus against higher
 *  technology; and the city-state friend count of VP's rule for friends.
 */
object BuildingYieldAi {
    /** VP's YIELD_FOOD .. YIELD_FAITH (the yields BNW has), in its order */
    private val YIELDS = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)

    private val ignoreConditionals = GameContext.IgnoreConditionals

    /** What the yield loop adds to the bonus, and whether the building helps the income (bGoodforGPT: no maintenance refusal) */
    class YieldTerms(val bonus: Int, val goodForGold: Boolean)

    /** The loop over the yields of CheckBuildingBuildSanity (without the happiness reductions of VP) */
    fun yieldTerms(city: City, building: Building): YieldTerms {
        val civ = city.civ
        //Let's look at yield bonuses.
        val averageGold = civ.stats.statsForNextTurn.gold.toInt()
        val focusYield = city.getCityFocus().stat
        val small = city.population.population <= 10
        var bonus = 0
        var goodForGold = false

        for (stat in YIELDS) {
            var value = yieldValue(city, building, stat)
            var trait = traitValue(city, stat, building, value)
            if (value > 0 || trait > 0) {
                when (stat) {
                    Stat.Gold -> if (averageGold < 0) {
                        value += averageGold * -10
                        trait += averageGold * -10
                        goodForGold = true
                    }
                    Stat.Faith -> if (!civ.isMajorCiv) {
                        trait = 0
                        value /= 10
                    }
                    Stat.Production -> if (small) {
                        value *= 4
                        trait *= 4
                    }
                    Stat.Food -> if (city.avoidGrowth) {
                        value = 0
                        trait = 0
                    } else if (small) value *= 10
                    else -> {}
                }
                if (focusYield == stat) {
                    value *= 5
                    trait *= 5
                }
            }
            bonus += value
            bonus += trait
        }
        return YieldTerms(bonus, goodForGold)
    }

    private fun positive(amount: Float) = if (amount > 0f) amount.toInt() else 0

    /** The number of the city's tiles that [filter] fits, less those [without] fits; a resource only when we know of it (SPlotStats) */
    private fun tileCount(city: City, filter: String, without: String?): Int {
        val civ = city.civ
        val isResource = filter in civ.gameInfo.ruleset.tileResources
        return city.getTiles().count { tile ->
            tile.matchesFilter(filter, civ) && (without == null || !tile.matchesFilter(without, civ)) &&
                (!isResource || tile.tileResource?.let { civ.tech.isRevealed(it) } == true)
        }
    }

    /** The building classes (as the civilization's building) that [filter] fits */
    private fun buildingClasses(city: City, filter: String): List<Building> {
        val civ = city.civ
        val ruleset = civ.gameInfo.ruleset
        return ruleset.buildings.values.asSequence()
            .filter { it.matchesFilter(filter, city.state) }
            .map { ruleset.buildings[it.replaces] ?: it }
            .distinct()
            .map { civ.getEquivalentBuilding(it) }
            .toList()
    }

    private fun hasClass(city: City, base: Building) =
        city.cityConstructions.getBuiltBuildings().any { (it.replaces ?: it.name) == (base.replaces ?: base.name) }

    /**
     *  GetBuildingYieldValue for one yield: what the building is worth for the yield in the city (the original also returns the flat
     *  yield, which no caller reads).
     */
    fun yieldValue(city: City, building: Building, stat: Stat): Int {
        val civ = city.civ
        val era = civ.getEraNumber()
        val numCities = civ.cities.size
        val population = city.population.population
        val state = city.state
        val yieldRate = max(1, city.cityStats.currentCityStats[stat].toInt())

        var flat = 0
        var modifier = 0
        var instant = 0

        //////////////
        // Flat Yields
        /////////////
        flat += positive(building.getStats(city)[stat])
        for (unique in building.getMatchingUniques(UniqueType.StatsPerCity, state))
            if (unique.isLocalEffect && city.matchesFilter(unique.params[1])) flat += positive(unique.stats[stat])
        for (unique in building.getMatchingUniques(UniqueType.StatsPerPopulation, state))
            if (unique.isLocalEffect) flat += (population * positive(unique.stats[stat]) / unique.params[1].toInt())
        val numReligions = city.religion.getNumberOfMajorReligionsWithFollowers()
        for (unique in building.getMatchingUniques(UniqueType.StatsPerReligionWithFollowers, state)) {
            val perReligion = positive(unique.stats[stat]) * numReligions
            flat += if (numReligions == 1) perReligion / 2 else perReligion
        }

        for (unique in building.getMatchingUniques(UniqueType.StatsFromTiles, state)) {
            val count = tileCount(city, unique.params[1], null)
            flat += count * positive(unique.stats[stat]) * if (unique.isLocalEffect) 1 else numCities
        }
        for (unique in building.getMatchingUniques(UniqueType.StatsFromTilesWithout, state)) {
            val count = tileCount(city, unique.params[1], unique.params[2])
            flat += count * positive(unique.stats[stat]) * if (unique.isLocalEffect) 1 else numCities
        }

        for (unique in building.getMatchingUniques(UniqueType.StatsFromObject, state)) {
            val change = positive(unique.stats[stat])
            if (change == 0) continue
            for (base in buildingClasses(city, unique.params[1])) flat += change * if (hasClass(city, base)) 5 else 1
        }

        flat += specialistFlat(city, building, stat)
        flat += monopolyAndRouteFlat(city, building, stat)

        if (stat == Stat.Gold) {
            val tradeBonus = building.getMatchingUniques(UniqueType.TradeRouteGoldForDestination, state).filter { it.isLocalEffect }.sumOf { it.params[0].toInt() } +
                building.getMatchingUniques(UniqueType.TradeRouteGoldForOrigin, state).filter { it.isLocalEffect }.sumOf { it.params[0].toInt() }
            if (tradeBonus > 0) flat += civ.tradeRoutes.getStatsForCity(city).gold.toInt() * tradeBonus
        }

        ///////////////
        // Instant Yields, and the shapes of the economy engine (events, processes, yield from yield, great work yields)
        //////////////
        val shapes = BuildingInstantYieldAi.terms(city, building, stat)
        flat += shapes.flat
        instant += shapes.instant

        ///////////////
        // Yield Modifiers
        //////////////
        modifier += shapes.modifier
        modifier += positive(building.getStatPercentageBonuses(city)[stat])
        for (unique in building.getMatchingUniques(UniqueType.StatPercentBonusCities, state)) {
            if (unique.params[1] != stat.name || unique.params[0].toInt() <= 0) continue
            modifier += unique.params[0].toInt() * if (unique.isLocalEffect) 1 else numCities
        }
        for (unique in building.getMatchingUniques(UniqueType.StatPercentBonus, state))
            if (unique.params[1] == stat.name && unique.params[0].toInt() > 0) modifier += unique.params[0].toInt() * numCities

        //Math time! Let's see how this affects our city.
        var value = 0
        if (flat > 0) {
            //let's see our % bump here.
            value += flat * 100 / yieldRate
        }
        if (instant > 0) {
            //Instant Yields almost always scale with era, so compensate.
            instant *= max(1, era)
            //We divide, since we are getting this sporadically, not all the time.
            value += max(instant / 2, instant / yieldRate)
        }
        if (modifier > 0) {
            //Modifiers are more important as the game goes on, exponentially so.
            modifier = modifier * (100 + era * era * era) / 100
            //We multiply, as we want to see what the 'new' value will be with this modifier intact.
            value += modifier * yieldRate / 100
        }

        if (value > 0) {
            //consider the modifiers we have in the city anyway!
            val baseModifier = 100 + city.cityStats.statPercentBonusTree.totalStats[stat].toInt()
            value = value * baseModifier / 100
            value = strategicValue(city, stat, value)
        }
        return value
    }

    /**
     *  The flat yields of the monopolies and routes (V:CvCityStrategyAI.cpp:3505-3508, 3525-3527; the economic building value of V:CvEconomicAI.cpp:2016, 2021 reads the same):
     *  the building's `[stats] [cityFilter] for every global monopoly` (`Building_YieldChangesPerMonopoly`) once for each global monopoly of the civilization, and the
     *  stats it gives `while any trade route passes through this city` (`Building_YieldChangesFromPassingTR`) when the path of a route goes over the city's tile now.
     */
    private fun monopolyAndRouteFlat(city: City, building: Building, stat: Stat): Int {
        var flat = 0
        val state = city.state
        val monopolies = city.civ.monopolies.globalMonopolyCount()
        if (monopolies > 0)
            for (unique in building.getMatchingUniques(UniqueType.StatsPerGlobalMonopoly, state))
                if (city.matchesFilter(unique.params[1])) flat += positive(unique.stats[stat]) * monopolies
        if (building.hasUnique(UniqueType.StatsWhenTradeRoutePasses, ignoreConditionals) && TradeRouteValue.isOnRoute(city.civ.gameInfo, city.getCenterTile()))
            for (unique in building.getMatchingUniques(UniqueType.StatsWhenTradeRoutePasses, state)) flat += positive(unique.stats[stat])
        return flat
    }

    /** The yields a building adds through specialists: the stats of the specialists it adds, and the specialist yields it gives */
    private fun specialistFlat(city: City, building: Building, stat: Stat): Int {
        val civ = city.civ
        val ruleset = civ.gameInfo.ruleset
        val numCities = civ.cities.size
        val state = city.state
        var flat = 0

        for ((specialistName, numNewSpecialists) in building.specialistSlots) {
            val specialist = ruleset.specialists[specialistName] ?: continue
            if (numNewSpecialists <= 0) continue
            val existing = city.population.specialistAllocations[specialistName]
            //Total slots.
            val slots = city.population.getMaxSpecialists()[specialistName]
            var specialistYield = specialist.cloneStats()[stat].toInt()
            city.forEachMatchingUnique(UniqueType.StatsFromSpecialist) { unique ->
                if (city.matchesFilter(unique.params[1])) specialistYield += unique.stats[stat].toInt()
            }
            city.forEachMatchingUnique(UniqueType.StatsFromNamedSpecialist) { unique ->
                if (unique.params[1] == specialistName && city.matchesFilter(unique.params[2])) specialistYield += unique.stats[stat].toInt()
            }
            //Alright, we got the specialist yields.
            if (specialistYield <= 0) continue
            //More than one? Multiply!
            specialistYield *= numNewSpecialists
            if (slots == 0) {
                //If this is our first specialist, double the value.
                specialistYield *= 2
                flat += specialistYield * numNewSpecialists
            } else if (slots - existing != 0) {
                //We have slots already? If we have open slots we aren't using, reduce the value.
                specialistYield *= 2
                specialistYield /= 3
                flat += specialistYield * max(1, existing)
            } else {
                //Growing normally? Value should increase based on number of specialists already here (to encourage clumping)!
                specialistYield *= 3
                specialistYield /= 2
                flat += max(1, existing) * specialistYield
            }
        }

        for ((specialistName, numWorkers) in city.population.getMaxSpecialists()) {
            if (numWorkers <= 0) continue
            for (unique in building.getMatchingUniques(UniqueType.StatsFromNamedSpecialist, state)) {
                if (unique.params[1] != specialistName) continue
                val change = unique.stats[stat].toInt()
                if (unique.isLocalEffect) flat += 2 * change * numWorkers
                else if (change > 0) flat += numWorkers * numCities * change * 5
            }
            for (unique in building.getMatchingUniques(UniqueType.StatsFromSpecialist, state)) {
                val change = unique.stats[stat].toInt()
                if (unique.isLocalEffect) flat += 2 * change * numWorkers
                else if (change > 0) flat += numWorkers * numCities * change * 5
            }
        }
        return flat
    }

    /** The end of GetBuildingYieldValue: how much each yield matters to us now, and what the victory we pursue wants */
    private fun strategicValue(city: City, stat: Stat, yieldValue: Int): Int {
        val civ = city.civ
        val ai = civ.ai
        val rules = ai.rules
        var value = yieldValue
        when (stat) {
            Stat.Culture -> {
                if (city.ai.isCityStrategyActive(FIRST_CULTURE_BUILDING)) value *= 2
                if (VictoryProgressAi.isCloseToCultureVictory(civ, rules)) value *= 2
                for (other in civ.gameInfo.civilizations) {
                    if (other == civ || !other.isMajorCiv || !other.isAlive() || other.cities.isEmpty() || !civ.knows(other)) continue
                    if (VictoryProgressAi.isCloseToCultureVictory(other, rules)) value *= 3
                    else if (other.culture.getInfluenceTrend(civ) == InfluenceTrend.Rising) value *= 2
                    else if ((other.culture.getInfluenceLevel(civ) ?: InfluenceLevel.Unknown) >= InfluenceLevel.Familiar) value *= 2
                }
            }
            Stat.Science -> if (VictoryProgressAi.isCloseToSpaceshipVictory(civ, rules)) value *= 2
            Stat.Production -> if (VictoryProgressAi.isCloseToWorldConquest(civ, rules)) value *= 2
            Stat.Faith -> {
                var flavorReligion = ai.personality(Flavor.Religion)
                if (ai.hasTraitCategory(TraitCategory.Religious)) {
                    flavorReligion *= 3
                    flavorReligion /= 2
                } else {
                    flavorReligion *= 2
                    flavorReligion /= 3
                }
                if (ai.isEconomicStrategyActive(DEVELOPING_RELIGION)) value += max(1, flavorReligion)
                if (civ.religionManager.remainingFoundableReligions() > 0) value *= 3
            }
            else -> {}
        }

        //GS Yield Valuation
        if (ai.isGoingForDiploVictory() && (stat == Stat.Gold || stat == Stat.Faith)) value *= 2
        if (ai.isGoingForWorldConquest() && (stat == Stat.Production || stat == Stat.Gold)) value *= 2
        if (ai.isGoingForCultureVictory() && stat == Stat.Culture) value *= 2
        if (ai.isGoingForSpaceshipVictory() && (stat == Stat.Science || stat == Stat.Food)) value *= 2
        return value
    }

    /**
     *  GetBuildingTraitValue: what the nation's traits (its uniques) add for the yield: 5 for each building class yield of the nation the
     *  building is of (wonders for "from every [Wonder]"), 5 for the yield of each great work if the building holds some, and, for a
     *  building that adds to the yield at all ([value] above 0), 50 for each trait of the yield's kind (technology from conquered cities, the
     *  great person of the Maya, a bonus belief, faith from kills, annexing city-states with gold, no annexing, keeping the happiness of
     *  luxuries).
     */
    fun traitValue(city: City, stat: Stat, building: Building, value: Int): Int {
        val civ = city.civ
        val nation = civ.nation
        var bonus = 0

        //Strategy-specific yield bonuses (that lack a yield modifier)
        for (unique in nation.getMatchingUniques(UniqueType.StatsFromObject, ignoreConditionals)) {
            val change = positive(unique.stats[stat])
            if (change > 0 && building.matchesFilter(unique.params[1], city.state)) bonus += change * 5
        }
        if (building.getMatchingUniques(UniqueType.GreatWorkSlots, ignoreConditionals).any()) {
            for (unique in nation.getMatchingUniques(UniqueType.StatsFromGreatWorks, ignoreConditionals))
                if (unique.stats[stat] > 0f) bonus += positive(unique.stats[stat]) * 5
        }

        if (value > 0) {
            fun has(type: UniqueType) = nation.getMatchingUniques(type, ignoreConditionals).any()
            when (stat) {
                Stat.Science -> {
                    if (has(UniqueType.MayanGainGreatPerson)) bonus += 50
                    if (has(UniqueType.TechnologyFromConqueringCity)) bonus += 50
                }
                Stat.Faith -> {
                    if (has(UniqueType.BonusReformationBelief)) bonus += 50
                    if (nation.getMatchingUniques(UniqueType.KillUnitPlunder, ignoreConditionals).any { it.params.last() == Stat.Faith.name })
                        bonus += 50
                }
                Stat.Gold -> {
                    if (has(UniqueType.CityStateCanBeBoughtForGold)) bonus += 50
                    if (has(UniqueType.MayNotAnnexCities)) bonus += 50
                    if (has(UniqueType.RetainHappinessFromLuxury)) bonus += 50
                }
                else -> {}
            }
        }
        return bonus
    }

    private const val FIRST_CULTURE_BUILDING = "AICITYSTRATEGY_FIRST_CULTURE_BUILDING"
    private const val DEVELOPING_RELIGION = "ECONOMICAISTRATEGY_DEVELOPING_RELIGION"
}
