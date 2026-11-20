package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.civilization.BeliefAiProfile
import com.unciv.logic.automation.diplomacy.TheftAi
import com.unciv.logic.automation.diplomacy.TheftType
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.abs
import kotlin.math.max

/**
 *  Four of the values CvBuildingProductionAI::CheckBuildingBuildSanity adds to a building's bonus (Vox Populi, CvCityStrategyAI.cpp,
 *  CityStrategyAIHelpers): [basicValue] (GetBuildingBasicValue), [grandStrategyValue] (GetBuildingGrandStrategyValue), [policyValue]
 *  (GetBuildingPolicyValue) and [religionValue] (GetBuildingReligionValue); the yield loop is [BuildingYieldAi].
 *
 *  BNW's building columns are read as the uniques that replace them, a local one (VP's building column) when the unique is a local effect
 *  (`<in this city>`) and a global one (the column's "Global" twin, or the player-level total) otherwise: Theming bonuses and great work
 *  slots, "[x]% Production when constructing [All] buildings [in this city]" (BuildingProductionModifier), "[n] population [cityFilter]"
 *  (PopulationChange, GlobalPopulationChange), "[x]% Food is carried over after population increases" (FoodKept), "Allows [stat] trade
 *  routes between your cities" (the food and production trade route columns), free technologies, free units, "Gain a free [building]
 *  [in this city]" (FreeBuildingThisCity), "[x]% Culture cost of natural border growth", "[x]% Gold cost of acquiring tiles",
 *  "[n] Trade Route slots", "[x]% Culture cost of adopting new Policies", Golden Age length and "Empire enters golden age", "Free Great
 *  Person", great person points and "[x]% Great Person generation", "Gain an extra spy", "New spies start with [n] level(s)", "Promotes
 *  all spies [n] time(s)", "[x]% enemy spy effectiveness" (GlobalEspionageModifier), "[n] World Congress Delegates" (ExtraLeagueVotes),
 *  "[x]% Gold cost of upgrading" (UnitUpgradeCost), "[x]% Science from Great Scientist discoveries", the "[x]% Production when
 *  constructing [Spaceship part] units" (SpaceProductionModifier and its global twin), "Gain the [work] Great Work" (FreeGreatWork),
 *  "Free Social Policy", Tourism from Great Works and Landmarks (the building's and the global twin), the promotions "newly-trained units
 *  receive" (TrainedFreePromotion; promotions that let missionaries spread more often are the missionary spread columns instead).
 *  The player-level totals the policy value adds (worker speed, plot costs, Golden Age length, great person rate, trade routes) are the
 *  same uniques of every source the civilization has (nation, policies, buildings elsewhere, and so on); the traits of the nation are the
 *  nation's uniques. The state religion of the original is the civilization's own religion, its pantheon included; its beliefs are read
 *  through [BeliefAiProfile].
 *
 *  None, because VP's input is not in BNW: the trait columns (capital theming, capital building discount and modifier, the building discount
 *  from wonder production, the Golden Age yields and great person modifiers of VP, GreatWorkWLTKD, GPWLTKD, WLTKD improvements), great
 *  person points from construction, the building's specific great person rate, reformation, minimum food and no starvation, the ideology
 *  trigger, city connection trade route modifier, no unhappiness from specialists, the great work yield modifier of the player, great
 *  person expended yields, VP's spy points (a spy is a whole spy, so the spy threshold cancels out), spy security, the policy modifiers of
 *  espionage, the belief spy pressure and network points, the beliefs' golden age effects (BNW has none) and their great work and
 *  specific great person yields, the hurry modifiers, free promotions of units already in the city, always heal, the air modifiers, the
 *  event tourism and land and sea tourism end columns, the votes columns other than ExtraLeagueVotes, the minor friendship change, the
 *  diplomat unit combat, the medians, the instant military increase, the great general rate, the global military production per war,
 *  the military production modifier and the experience columns of the unit combat classes and domains (they are the rows
 *  [BuildingProductionAi] reads; the original's generic FreeExperience and GlobalFreeExperience are BNW's "Military" rows of those),
 *  and the unit special types other than great people. The religion calculator skips a bad conversion target as the original
 *  (CvDiplomacyAI::IsBadTheftTarget, [TheftAi]).
 */
object BuildingValueAi {
    private val ignoreConditionals = GameContext.IgnoreConditionals

    /** The sum of a unique type's first parameter over the uniques of [this] that pass [filter] */
    private fun Building.amount(type: UniqueType, amountParam: Int = 0, filter: (Unique) -> Boolean = { true }): Int =
        getMatchingUniques(type, ignoreConditionals).filter(filter).sumOf { it.params[amountParam].toInt() }

    private fun Building.count(type: UniqueType) = getMatchingUniques(type, ignoreConditionals).count()

    /** The sum of a unique type's amounts over every source of the civilization that applies to all its cities */
    private fun Civilization.amount(type: UniqueType, amountParam: Int = 0): Int {
        var sum = 0
        forEachMatchingUnique(type) { sum += it.params[amountParam].toInt() }
        return sum
    }

    private fun population(civ: Civilization) = civ.cities.sumOf { it.population.population }

    /** The great person rate modifiers of the city (its local ones, the original's getTotalGreatPeopleRateModifier) and of the civilization (getGreatPeopleRateModifier) */
    fun greatPeopleRate(city: City): Int {
        var cityRate = 0
        city.forEachLocalMatchingUnique(UniqueType.GreatPersonPointPercentage) { if (it.isLocalEffect) cityRate += it.params[0].toInt() }
        return cityRate + city.civ.amount(UniqueType.GreatPersonPointPercentage)
    }

    /**
     *  GetBuildingBasicValue: what the building is worth beyond its yields: great work slots (a theming bonus each, a slot is worth twice
     *  the works the city already holds), production for buildings, population gained, a puppet that can be bought in, food kept, trade
     *  routes between our cities, a free technology, free units (special units 100, city founders 50, others 10 each), the free building
     *  it gives (its own sanity result for weight 30, if positive), the missionary spread columns (a share of the cities our religion
     *  could convert, see [numCitiesWithReligion]) and 100 for each building we could build now that needs this one.
     */
    fun basicValue(city: City, building: Building): Int {
        val civ = city.civ
        val ruleset = civ.gameInfo.ruleset
        val population = city.population.population
        var value = 0

        //GWS
        val slots = building.amount(UniqueType.GreatWorkSlots)
        if (building.themingBonuses.isNotEmpty() || slots > 0) {
            val numWorks = max(1, city.greatWorks.getNumWorks())
            value += building.themingBonuses.size
            value += slots * numWorks * 2
        }
        val buildingProduction = building.amount(UniqueType.PercentProductionBuildings) { it.isLocalEffect && it.params[1] == "All" }
        if (buildingProduction > 0) value += (buildingProduction + population) * 5

        val populationChange = building.amount(UniqueType.OneTimeGainPopulation) { it.isLocalEffect }
        if (populationChange > 0) value += (populationChange + population) * 10
        if (building.amount(UniqueType.OneTimeGainPopulation) { !it.isLocalEffect } > 0)
            value += (populationChange + population(civ)) * 5

        if (building.hasUnique(UniqueType.MayBuyConstructionsInPuppets, ignoreConditionals) && city.isPuppet)
            value += (25 + population) * 5

        val foodKept = building.amount(UniqueType.CarryOverFood) { it.isLocalEffect }
        if (foodKept > 0) value += foodKept * population

        for (unique in building.getMatchingUniques(UniqueType.AllowsDomesticTradeRoutes, ignoreConditionals)) {
            if (unique.params[0] != Stat.Food.name && unique.params[0] != Stat.Production.name) continue
            value += (if (unique.isLocalEffect) 10 else 25) * population
        }

        if (building.amount(UniqueType.OneTimeAmountFreeTechs) + building.count(UniqueType.OneTimeFreeTech) > 0) {
            //Earlier techs pretty useful...
            value += 100 + ruleset.technologies.size - civ.tech.techsResearched.size
        }
        val freeUnits = building.getMatchingUniques(UniqueType.OneTimeFreeUnit, ignoreConditionals).map { it.params[0] to 1 } +
            building.getMatchingUniques(UniqueType.OneTimeAmountFreeUnits, ignoreConditionals).map { it.params[1] to it.params[0].toInt() }
        for ((unitName, numUnits) in freeUnits) {
            val unit = ruleset.units[unitName] ?: continue
            if (numUnits <= 0) continue
            value += numUnits * when {
                unit.isGreatPerson -> 100
                unit.isCityFounder() -> 50
                else -> 10
            }
        }

        for (unique in building.getMatchingUniques(UniqueType.GainFreeBuildings, ignoreConditionals)) {
            if (!unique.isLocalEffect) continue
            val freeBuilding = ruleset.buildings[unique.params[0]]?.let { civ.getEquivalentBuilding(it) } ?: continue
            val freeValue = BuildingProductionAi.checkBuildingBuildSanity(city, freeBuilding, 30, noBestWonderCityCheck = true, freeBuilding = true)
            if (freeValue > 0) value += freeValue
        }

        if (civ.isMajorCiv) {
            val spreads = BuildingProductionAi.extraMissionarySpreads(ruleset, building)
            val strength = building.hasUnique(UniqueType.SpreadReligionStrength, ignoreConditionals)
            if (spreads.isNotEmpty() || strength) {
                val numNearbyCities = numCitiesWithReligion(civ)
                if (spreads.any { it.isLocalEffect }) value += numNearbyCities / 25
                if (spreads.any { !it.isLocalEffect }) value += numNearbyCities / 10
                if (strength) value += numNearbyCities / 25
            }
        }

        //Is this a prereq for another building we can build right now?
        for (other in ruleset.buildings.values) {
            val tech = other.requiredTech ?: continue
            if (civ.tech.isResearched(tech) && other.requiredBuilding == building.name) value += 100
        }
        return value
    }

    /**
     *  CvReligionAI::GetNumCitiesWithReligionCalculator for the state religion: every living civilization's cities that follow it (cities
     *  without a majority religion or with a pantheon for none or a pantheon: 2 each), doubled for ourselves and for civilizations that
     *  founded no religion, then halved, quartered or divided by six by how close their land is, times the eras left, halved. A major
     *  skips the civilizations that are a bad target for a conversion ([TheftAi.isBadTheftTarget]).
     */
    private fun numCitiesWithReligion(civ: Civilization): Int {
        val religion = civ.religionManager.religion
        var total = 0
        for (other in civ.gameInfo.civilizations) {
            if (!other.isAlive() || !(other.isMajorCiv || other.isCityState)) continue
            if (civ.isMajorCiv && TheftAi.isBadTheftTarget(civ, other, TheftType.Conversion)) continue
            var numCities = 0
            for (city in other.cities) {
                val majority = city.religion.getMajorityReligion()
                if (religion == null) {
                    if (majority == null || majority.isPantheon()) numCities += 2
                } else if (if (religion.isPantheon()) majority?.isPantheon() == true else majority == religion) numCities++
            }
            //Emphasis on our own!
            if (other == civ) numCities *= 2
            if (other.religionManager.religionState < ReligionState.Religion) numCities *= 2
            when (other.getProximity(civ)) {
                Proximity.Close -> numCities /= 2
                Proximity.Far -> numCities /= 4
                Proximity.Distant -> numCities /= 6
                else -> {}
            }
            total += numCities
        }
        //Let's make some predictions. Earlier in the game = more cities to make. And they'll all potentially be ours!
        val eraBonus = max(1, civ.gameInfo.ruleset.eras.size - civ.getEraNumber())
        return total * eraBonus / 2
    }

    /**
     *  GetBuildingGrandStrategyValue: the building's worth to each victory (diplomacy: World Congress delegates; conquest: cheaper unit
     *  upgrades, the promotion units are trained with; science: Great Scientist discoveries, spaceship part production; tourism: great
     *  works, free great works, great work and landmark tourism; culture: free policies), each scaled up by the interest of the grand
     *  strategy in it (its priority / 10 percent, the leader's traits multiply the interests). Tourism, diplomacy, science and conquest
     *  count in full when the civilization is going for or close to that victory, else divided by era + 1; culture always in full. 0 for a
     *  puppet that may be annexed. [city] is null when the value is asked without a city (CvPlayerTechs::SetGSPriorities passes NULL): the
     *  terms that read the city (the Great Work slots it has, its culture) are then left out and the puppet test passes.
     */
    fun grandStrategyValue(civ: Civilization, city: City?, building: Building): Int {
        val ai = civ.ai
        if (city != null && ProductionSanityAi.isPuppetAndAnnexable(city)) return 0

        var diploInterest = 0
        var conquestInterest = 0
        var scienceInterest = 0
        var cultureInterest = 0
        var tourismInterest = 0
        // Loop through all GrandStrategies and get priority. Since these are usually 100+, we will divide by 10 later
        for (def in ai.rules.getGrandStrategies()) {
            val priority = ai.grandStrategyPriority(def.name)
            when (def.name) {
                GrandStrategyAi.CONQUEST -> conquestInterest += priority
                GrandStrategyAi.CULTURE -> {
                    cultureInterest += priority / 2
                    tourismInterest += priority
                }
                GrandStrategyAi.UNITED_NATIONS -> diploInterest += priority
                GrandStrategyAi.SPACESHIP -> scienceInterest += priority
            }
        }
        if (ai.hasTraitCategory(TraitCategory.Expansionist) || ai.hasTraitCategory(TraitCategory.Warmonger)) {
            conquestInterest *= 6
            diploInterest *= 5
            tourismInterest *= 3
            cultureInterest *= 2
            scienceInterest *= 4
        }
        if (ai.hasTraitCategory(TraitCategory.Nerd)) {
            conquestInterest *= 2
            diploInterest *= 4
            tourismInterest *= 3
            cultureInterest *= 5
            scienceInterest *= 6
        }
        if (ai.hasTraitCategory(TraitCategory.Diplomat) || ai.hasTraitCategory(TraitCategory.Smaller)) {
            conquestInterest *= 2
            diploInterest *= 6
            tourismInterest *= 5
            cultureInterest *= 4
            scienceInterest *= 3
        }
        if (ai.hasTraitCategory(TraitCategory.Tourism) || ai.hasTraitCategory(TraitCategory.Religious)) {
            conquestInterest *= 2
            diploInterest *= 4
            tourismInterest *= 6
            cultureInterest *= 5
            scienceInterest *= 3
        }

        //Let's look at building special traits.
        var diploValue = 0
        var scienceValue = 0
        var conquestValue = 0
        var cultureValue = 0
        var tourismValue = 0

        val extraVotes = building.amount(UniqueType.WorldCongressDelegates)
        if (extraVotes > 0) diploValue += 200 / extraVotes

        val upgradeCost = building.amount(UniqueType.UnitUpgradeCost)
        if (upgradeCost != 0) conquestValue += upgradeCost * -1
        if (hasTrainedPromotion(civ.gameInfo.ruleset, building)) conquestValue += 50

        val globalSpace = building.amount(UniqueType.PercentProductionUnits) { it.params[1] == "Spaceship part" && !it.isLocalEffect }
        if (globalSpace > 0) scienceValue += globalSpace
        val discoveries = building.amount(UniqueType.GreatScientistDiscoveryScience)
        if (discoveries > 0) scienceValue += discoveries
        val localSpace = building.amount(UniqueType.PercentProductionUnits) { it.params[1] == "Spaceship part" && it.isLocalEffect }
        if (localSpace > 0) scienceValue += localSpace

        if (building.hasUnique(UniqueType.OneTimeGainGreatWork, ignoreConditionals)) tourismValue += 50
        if (building.amount(UniqueType.OneTimeAmountFreePolicies) + building.count(UniqueType.OneTimeFreePolicy) > 0)
            cultureValue += civ.policies.getAdoptedPolicies().size * civ.cities.size * 5
        val greatWorkSlots = building.amount(UniqueType.GreatWorkSlots)
        if (greatWorkSlots > 0) {
            tourismValue += greatWorkSlots * 5
            if (city != null && city.greatWorks.getSlots().size <= greatWorkSlots) tourismValue += greatWorkSlots * 5
        }

        val greatWorksTourism = building.amount(UniqueType.GreatWorksTourismPercent) { it.isLocalEffect }
        val greatWorksTourismGlobal = building.amount(UniqueType.GreatWorksTourismPercent) { !it.isLocalEffect }
        if (greatWorksTourism > 0) tourismValue += greatWorksTourism
        val landmarks = building.amount(UniqueType.LandmarksTourismPercent) { it.isLocalEffect }
        val landmarksGlobal = building.amount(UniqueType.LandmarksTourismPercent) { !it.isLocalEffect }
        if (landmarks > 0) tourismValue += landmarks
        if (greatWorksTourismGlobal > 0) tourismValue += greatWorksTourismGlobal * civ.cities.size
        if (landmarksGlobal > 0) tourismValue += landmarksGlobal * civ.cities.size
        if (city != null && (landmarks > 0 || landmarksGlobal > 0)) {
            val culture = city.cityStats.currentCityStats.culture.toInt()
            tourismValue += culture / max(1, landmarks + landmarksGlobal)
        }
        if (city != null && (greatWorksTourism > 0 || greatWorksTourismGlobal > 0)) {
            val works = max(3, city.greatWorks.getSlots().size)
            //Higher value the higher the number of works.
            tourismValue += works * (greatWorksTourism + greatWorksTourismGlobal)
        }

        //Take the bonus from above and multiply it by the priority value / 10 (as most are 100+, so we're getting a % interest here).
        conquestValue = conquestValue * (100 + conquestInterest / 10) / 100
        cultureValue = cultureValue * (100 + cultureInterest / 10) / 100
        tourismValue = tourismValue * (100 + tourismInterest / 10) / 100
        diploValue = diploValue * (100 + diploInterest / 10) / 100
        scienceValue = scienceValue * (100 + scienceInterest / 10) / 100

        val era = civ.getEraNumber() + 1
        val rules = ai.rules
        //And now add them in. Reduce if not our main focus.
        var value = 0
        value += if (ai.isGoingForCultureVictory() || VictoryProgressAi.isCloseToCultureVictory(civ, rules)) tourismValue else tourismValue / era
        value += if (ai.isGoingForDiploVictory() || VictoryProgressAi.isCloseToDiploVictory(civ, rules)) diploValue else diploValue / era
        value += if (ai.isGoingForSpaceshipVictory() || VictoryProgressAi.isCloseToSpaceshipVictory(civ, rules)) scienceValue else scienceValue / era
        value += if (ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules)) conquestValue else conquestValue / era
        value += cultureValue
        return value
    }

    /** CvBuildingEntry::GetTrainedFreePromotion: a promotion that units trained in the city receive, other than the missionary spread promotions */
    private fun hasTrainedPromotion(ruleset: Ruleset, building: Building): Boolean {
        val spreadPromotions = BuildingProductionAi.extraMissionarySpreads(ruleset, building).map { it.params[2] }.toSet()
        return building.getMatchingUniques(UniqueType.UnitStartingPromotions, ignoreConditionals).any { it.params[2] !in spreadPromotions }
    }

    /**
     *  GetBuildingPolicyValue: what the building adds to bonuses the civilization already has, so that they compound: worker speed, cheaper
     *  border growth and tiles, trade routes, cheaper policies (-10 for each point of policy discount of the buildings we have, also for
     *  a building without one), Golden Age length (with the Golden Age bonuses of the nation), great people (the rate bonuses of the city
     *  and the civilization, the points, 50 for each, the gold and faith of expending one), the production bonus and the happiness of the
     *  civilization's policies for the building (5 per city), spies (100 for each whole spy it gives or levels, times a modifier of the
     *  enemy spy effectiveness and the levels), all times era + 1.
     */
    fun policyValue(city: City, building: Building): Int {
        val civ = city.civ
        var value = 0

        //Bonuses below are compounding based on existing bonuses. The idea is to help the AI 'synergize' its bonuses.
        val workerSpeed = building.amount(UniqueType.ImprovementTimeIncrease, 1)
        if (workerSpeed > 0) value += civ.amount(UniqueType.ImprovementTimeIncrease, 1) + workerSpeed

        val cultureCost = building.amount(UniqueType.BorderGrowthPercentage) { it.isLocalEffect }
        if (cultureCost < 0) value += 2 * abs(civ.amount(UniqueType.BorderGrowthPercentage) + cultureCost)
        val buyCost = building.amount(UniqueType.TileCostPercentage) { it.isLocalEffect }
        if (buyCost < 0) value += 2 * abs(civ.amount(UniqueType.TileCostPercentage) + buyCost)

        val numTradeRoutes = civ.tradeRoutes.getNumSlots()
        val tradeRouteBonus = building.amount(UniqueType.TradeRouteSlots)
        if (tradeRouteBonus != 0) value += (tradeRouteBonus + numTradeRoutes) * 5
        val resourceDiversity = building.amount(UniqueType.TradeRouteResourceGoldPercent)
        if (resourceDiversity != 0) value += (resourceDiversity + numTradeRoutes) * 5

        val policyCost = building.amount(UniqueType.LessPolicyCost)
        if (policyCost <= 0) {
            val ofBuildings = civ.cities.sumOf { c -> c.cityConstructions.getBuiltBuildings().sumOf { it.amount(UniqueType.LessPolicyCost) } }
            value += -10 * (ofBuildings + policyCost)
        }

        val goldenAgeLength = building.amount(UniqueType.GoldenAgeLength)
        if (goldenAgeLength > 0 || building.hasUnique(UniqueType.OneTimeEnterGoldenAge, ignoreConditionals)) {
            value += civ.amount(UniqueType.GoldenAgeLength) + goldenAgeLength
            for (unique in civ.nation.getMatchingUniques(UniqueType.GreatPersonEarnedFaster, ignoreConditionals))
                if (unique.hasModifier(UniqueType.ConditionalGoldenAge)) value += unique.params[1].toInt()
            for (unique in civ.nation.getMatchingUniques(UniqueType.Strength, ignoreConditionals))
                if (unique.hasModifier(UniqueType.ConditionalGoldenAge)) value += unique.params[0].toInt()
            for (unique in civ.nation.getMatchingUniques(UniqueType.TourismPercent, ignoreConditionals))
                if (unique.hasModifier(UniqueType.ConditionalGoldenAge)) value += unique.params[0].toInt()
        }

        val greatPeopleLocal = building.amount(UniqueType.GreatPersonPointPercentage) { it.isLocalEffect }
        val greatPeopleGlobal = building.amount(UniqueType.GreatPersonPointPercentage) { !it.isLocalEffect }
        val greatPersonPoints = building.greatPersonPoints.values.sum()
        if (building.hasUnique(UniqueType.OneTimeFreeGreatPerson, ignoreConditionals) || greatPersonPoints > 0 ||
            greatPeopleGlobal > 0 || greatPeopleLocal > 0) {
            val cityRate = greatPeopleRate(city)
            val expendGold = civ.getTriggeredUniques(UniqueType.TriggerUponExpendingUnit, ignoreConditionals)
                .filter { it.type == UniqueType.OneTimeGainStat && it.params[1] == Stat.Gold.name }.sumOf { it.params[0].toInt() }
            value += cityRate + expendGold / 10 + greatPeopleGlobal + greatPeopleLocal
            value += greatPersonPoints * 50
            val religion = civ.religionManager.religion
            if (religion != null) {
                val expendFaith = religion.getAllBeliefsOrdered().sumOf { BeliefAiProfile.of(it, civ).greatPersonExpendedFaith }
                if (expendFaith > 0) value += expendFaith / 5
            }
        }

        val productionBonus = civ.policies.policyUniques.getMatchingUniques(UniqueType.PercentProductionBuildings, city.state)
            .filter { building.matchesFilter(it.params[1], city.state) && city.matchesFilter(it.params[2]) }
            .sumOf { it.params[0].toInt() }
        if (productionBonus > 0) value += productionBonus
        val happinessBonus = civ.policies.policyUniques.getMatchingUniques(UniqueType.StatsFromObject, city.state)
            .filter { it.stats.happiness > 0 && building.matchesFilter(it.params[1], city.state) }
        if (happinessBonus.any()) value += 5 * civ.cities.size

        /* Spy Buildings */
        val extraSpies = building.count(UniqueType.OneTimeGainSpy)
        val espionage = building.amount(UniqueType.EnemySpyEffectiveness) { !it.isLocalEffect }
        val spyRank = building.amount(UniqueType.SpyStartingLevel) + building.amount(UniqueType.OneTimeSpiesLevelUp)
        if (extraSpies > 0 || espionage != 0 || spyRank > 0) {
            // build the modifier that synergises with spies
            val modifier = 100 + espionage * -20 + spyRank * 20
            // if the effect hits all existing spies, they count too, else just any new spies being gained
            val spies = extraSpies + if (espionage != 0 || spyRank > 0) civ.espionageManager.spyList.size else 0
            // score = 100 per relevant spy * % modifier
            value += 100 * spies * modifier / 100
        }
        return value * (civ.getEraNumber() + 1)
    }

    /**
     *  GetBuildingReligionValue: what the beliefs of the civilization's religion add for the building, summed over its beliefs: happiness
     *  and yields for the building ("from every [building]"), the yields of its specialist (and those for any specialist in a city without
     *  one), the wonder production of a world wonder; yields in a belief count times half the era modifier when they include Faith. The
     *  sum is times the modifier: the eras left (at least 2), doubled for a religious leader, before a pantheon, and while a religion can
     *  still be founded.
     */
    fun religionValue(city: City, building: Building): Int {
        val civ = city.civ
        val manager = civ.religionManager

        var modifier = max(2, 15 - civ.getEraNumber())
        if (civ.ai.hasTraitCategory(TraitCategory.Religious)) modifier *= 2
        if (manager.religionState == ReligionState.None) modifier *= 2
        if (manager.religionState != ReligionState.None && manager.religionState < ReligionState.Religion &&
            manager.remainingFoundableReligions() > 0) modifier *= 2

        var religionBonus = 0
        val religion = manager.religion
        if (religion != null) {
            val ourBuilding = civ.getEquivalentBuilding(building)
            val specialist = building.specialistSlots.entries.firstOrNull { it.value > 0 }?.key
            for (belief in religion.getAllBeliefsOrdered()) {
                val profile = BeliefAiProfile.of(belief, civ)
                var temp = 0
                val happiness = profile.buildingHappiness[ourBuilding] ?: 0
                if (happiness > 0) temp += happiness
                for (yield in YIELDS) {
                    if (specialist != null) {
                        val specialistYield = profile.namedSpecialistYields[specialist]?.get(yield)?.toInt() ?: 0
                        if (specialistYield > 0) temp += specialistYield
                        val anySpecialist = profile.anySpecialistYield[yield].toInt()
                        if (city.population.getNumberOfSpecialists() <= 0 && anySpecialist > 0) temp += anySpecialist
                    }
                    val buildingYield = profile.buildingYields[ourBuilding]?.get(yield)?.toInt() ?: 0
                    if (buildingYield > 0) temp += buildingYield
                    if (yield == Stat.Faith) temp *= max(1, modifier / 2)
                }
                if (profile.wonderProductionModifier != 0 && building.isWonder) temp += profile.wonderProductionModifier
                religionBonus += temp
            }
        }
        return religionBonus * modifier
    }

    /** VP's YIELD_FOOD .. YIELD_FAITH, in its order (the Faith multiplier applies to what the earlier yields added) */
    private val YIELDS = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)
}
