package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.DealAi
import com.unciv.logic.automation.strategy.ProductionSanityAi.SR_IMPOSSIBLE
import com.unciv.logic.automation.strategy.ProductionSanityAi.SR_MAINTENANCE
import com.unciv.logic.automation.strategy.ProductionSanityAi.SR_STRATEGY
import com.unciv.logic.automation.strategy.ProductionSanityAi.SR_UNKNOWN
import com.unciv.logic.automation.strategy.ProductionSanityAi.SR_USELESS
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min

/**
 *  CvBuildingProductionAI::CheckBuildingBuildSanity (Vox Populi, CvBuildingProductionAI.cpp): what a building's flavor weight is worth
 *  in a city, [checkBuildingBuildSanity]; and CvPlayer::SetBestWonderCities / CvCity::IsBestForWonder, [isBestForWonder].
 *
 *  Ported: the refusals (the city about to fall, wonders in small cities, religion wonders without a religion to found, the best wonder city,
 *  resource-consuming buildings in puppets and automated cities, Aluminum the spaceship needs ([SpaceshipProductionAi]), a city under
 *  siege, bankruptcy), the weight (flavor weight brought to
 *  sqrti(10 x weight), plus the bonus sum below, 0 if the bonus is below 1) and the bonus terms: wonders (best city +500, the next wonder
 *  desired up to +1500, -150 for each rival building it), minor civ quests (+10), geography (airlift, trade and city connections, a
 *  luxury copying building), happiness (BNW's single happiness, each point worth 10, x4 for each of unhappy, very unhappy and super
 *  unhappy), defense (scaled up in border and coastal cities, at war and under siege), the courthouse of an occupied city (+5000 per
 *  citizen), units the building unlocks, military experience and production (in the best city of the unit combat or domain, else half),
 *  gold maintenance, the war penalty, the era (+200 per era since the building's technology), buildings that need this one, unique
 *  buildings.
 *
 *  The values of [BuildingValueAi] (GetBuildingBasicValue, GetBuildingGrandStrategyValue (x (era + 1)), GetBuildingPolicyValue and
 *  GetBuildingReligionValue) and the yield loop of [BuildingYieldAi] (GetBuildingYieldValue, GetBuildingTraitValue) are added where the
 *  original adds them; a building that helps a negative gold income is exempt from the maintenance refusal.
 *
 *  None, because VP's input is not in BNW: the free resources of a wonder (trait), the defense per wonder, accomplishments, the happiness
 *  of the VP rules (unhappiness, happiness per war, median modifiers, isolation), the empire's
 *  VP happiness levels (BNW's: excess happiness below 0, at most VERY_UNHAPPY_THRESHOLD, at most SUPER_UNHAPPY_THRESHOLD), the building
 *  columns of VP (supply, ranged strike range and indirect fire, border obstacles of water and land, damage reduction, air and
 *  industrial routes, fresh water, units trainable, great work experience, corporations, marriages, themes, investments), the plots a
 *  building claims (VP's Building_ResourceClaim: BNW has no such building, so the claimed plots' terms and the refusal to take a major's
 *  plots that [com.unciv.logic.automation.diplomacy.TheftAi.isBadTheftTarget] (culture bomb) gives are not reached; a ruleset with such
 *  buildings reads that function there),
 *  and the trade priorities of cities (VP's
 *  CvTradeAI::GetPrioritizedTradeRoutes stores them: counted as 0, so a building for land or sea trade is penalised -50 as in a city with
 *  no route; the potential sea routes are asked of the trade routes of the civilization).
 *
 *  BNW's building columns are read as the uniques that replace them: Defense = city strength x 100, ExtraCityHitPoints = city health,
 *  GlobalDefenseMod = "% City Strength from defensive buildings", NukeModifier = "Population loss from nuclear attacks", Happiness and
 *  UnmoddedHappiness = the happiness stat (Unciv has no separate unmodded stat), HappinessPerCity = "[stats] [in all cities]",
 *  BorderObstacle = the Great Wall's movement unique, the courthouse = "Removes extra unhappiness from annexed cities" (an occupied
 *  city is an annexed one), a free unit = "Free [unit] appears" and "[n] free [unit] units appear".
 */
object BuildingProductionAi {

    /**
     *  CheckBuildingBuildSanity. [weight] is the flavor weight ([ProductionAi.buildingWeight]); [noBestWonderCityCheck], [freeBuilding]
     *  (the building is a gift) and [ignoreSituational] are the original's flags of the same names. A negative result refuses the building
     *  (the SR_ codes of [ProductionSanityAi]), 0 is SR_UNKNOWN (the bonus is below 1).
     */
    fun checkBuildingBuildSanity(
        city: City,
        building: Building,
        weight: Int,
        noBestWonderCityCheck: Boolean = false,
        freeBuilding: Boolean = false,
        ignoreSituational: Boolean = false
    ): Int {
        if (weight < 1) return SR_IMPOSSIBLE
        //do not build any buildings at all when about to be captured
        if (CityDangerAi.isInDangerOfFalling(city, extraCareful = false)) return SR_STRATEGY
        val check = Check(city, building, ProductionSanityAi.normalise(weight), noBestWonderCityCheck, freeBuilding, ignoreSituational)
        return check.run()
    }

    /**
     *  CvCity::IsBestForWonder: whether [city] is the city of its civilization that CvPlayer::SetBestWonderCities picked for the class of the
     *  world or national wonder [wonder]. The pick is the city (not a puppet we may annex) that can build it and scores highest: the
     *  sanity result for weight 1000 without the situational terms and the best city check, x10 when it has production in it or builds
     *  it, less 50 per turn it needs; the last of equal scores. Computed when asked, from the cities as they are.
     */
    fun isBestForWonder(city: City, wonder: Building): Boolean = bestWonderCity(city.civ, wonder) == city

    /** CvBuildingEntry::GetEra: the era of the technology the building needs, -1 without one */
    fun era(ruleset: Ruleset, building: Building): Int = ruleset.technologies[building.requiredTech]?.era(ruleset)?.eraNumber ?: -1

    /**
     *  CvBuildingEntry::GetExtraMissionarySpreads and GetExtraMissionarySpreadsGlobal: units trained get a promotion that lets them spread
     *  the religion more often ([com.unciv.models.ruleset.unique.UniqueType.UnitStartingPromotions] of such a promotion; the global twin is
     *  the unique that does not apply to this city only)
     */
    fun extraMissionarySpreads(ruleset: Ruleset, building: Building): List<Unique> =
        building.getMatchingUniques(UniqueType.UnitStartingPromotions, GameContext.IgnoreConditionals).filter { unique ->
            ruleset.unitPromotions[unique.params[2]]?.uniqueObjects?.any {
                it.type == UniqueType.CanSpreadReligion && it.getModifiers(UniqueType.UnitActionExtraLimitedTimes).isNotEmpty()
            } == true
        }.toList()

    /** The city CvPlayer::SetBestWonderCities picks for [wonder] (see [isBestForWonder]), null if none can build it */
    internal fun bestWonderCity(civ: Civilization, wonder: Building): City? {
        //is this a unique wonder that we can't build? Ignore it.
        if (wonder.uniqueTo != null && wonder.uniqueTo != civ.civName) return null
        var best: City? = null
        var bestScore = Int.MIN_VALUE
        for (city in civ.cities) {
            // No wonders in non-venetian puppets
            if (ProductionSanityAi.isPuppetAndAnnexable(city)) continue
            if (!wonder.isBuildable(city.cityConstructions)) continue
            var score = checkBuildingBuildSanity(city, wonder, 1000, noBestWonderCityCheck = true, ignoreSituational = true)
            if (score < 0) continue
            // We've already started? Massive bonus!
            val constructions = city.cityConstructions
            if (constructions.currentConstructionName() == wonder.name || constructions.getWorkDone(wonder.name) > 0) score *= 10
            //duration is also important
            score -= 50 * ProductionAi.turnsLeft(city, wonder)
            if (score >= bestScore) {
                best = city
                bestScore = score
            }
        }
        return best
    }

    private class Check(
        val city: City,
        val building: Building,
        var value: Int,
        val noBestWonderCityCheck: Boolean,
        val freeBuilding: Boolean,
        val ignoreSituational: Boolean
    ) {
        val civ = city.civ
        val ruleset = civ.gameInfo.ruleset
        val rules = ruleset.ai
        val population = city.population.population
        val happiness = civ.getHappiness()

        //Bonus additive. All values below will be added to this and combined with real value at end.
        var bonus = 0
        var courthouse = false
        val isPuppetAndAnnexable = ProductionSanityAi.isPuppetAndAnnexable(city)
        val capital = civ.getCapital()

        fun run(): Int {
            wonders()?.let { return it }
            quests()
            geography()
            resources()?.let { return it }
            happinessTerms()
            defense()?.let { return it }
            courthouse()
            unlocks()
            militaryExperience()
            valueTerms()
            val yields = BuildingYieldAi.yieldTerms(city, building)
            bonus += yields.bonus
            maintenance(yields.goodForGold)?.let { return it }
            bonus += BuildingValueAi.religionValue(city, building)
            warPenalty()
            eraTerms()
            if (bonus < 1) return SR_UNKNOWN
            //iValue is the compunded value of the items.
            return max(1, value + bonus)
        }

        private fun wonders(): Int? {
            if (population <= 6 && !city.isCapital() && building.isAnyWonder()) return SR_STRATEGY
            if (!building.isWonder && !building.isNationalWonder) return null
            if (building.isWonder) religionWonderGate()?.let { return it }

            if (!noBestWonderCityCheck) {
                if (isBestForWonder(city, building)) bonus += 500
                else return SR_STRATEGY
            }

            // we want this? ramp it up!
            if (civ.ai.citySpecialization.nextWonderDesired == building.name) {
                //cap this at an extreme value.
                bonus += if (building.isNationalWonder) min(1000, population * 50) else min(1500, population * 100)
            }

            if (building.isWonder && !freeBuilding && !ignoreSituational) {
                value += civ.getMatchingUniques(UniqueType.PercentProductionWonders)
                    .filter { building.matchesFilter(it.params[1], city.state) && city.matchesFilter(it.params[2]) }
                    .sumOf { it.params[0].toInt() }
                // Adjust weight for this wonder down based on number of other players currently working on it
                val othersConstructing = civ.gameInfo.civilizations.count { other ->
                    other != civ && other.isMajorCiv && other.isAlive() && civ.knows(other) &&
                        other.cities.any { it.cityConstructions.isBeingConstructedOrEnqueued(building.name) }
                }
                bonus -= othersConstructing * 150
                //probably early game, so if we haven't started yet, we're probably not going to win this one.
                if (civ.cities.size == 1) bonus -= othersConstructing * 100
            }
            return null
        }

        /** Specific check to block non-founder AIs from building Hagia Sophia, Borobudur, Cathedral of St. Basil */
        private fun religionWonderGate(): Int? {
            val religion = civ.religionManager
            if (religion.religionState >= ReligionState.Religion) return null
            val canFoundReligion = religion.remainingFoundableReligions() > 0
            var spawnsProphet = false
            val freeUnits = building.getMatchingUniques(UniqueType.OneTimeFreeUnit, GameContext.IgnoreConditionals).map { it.params[0] } +
                building.getMatchingUniques(UniqueType.OneTimeAmountFreeUnits, GameContext.IgnoreConditionals).map { it.params[1] }
            for (unitName in freeUnits) {
                val unit = ruleset.units[unitName] ?: continue
                // Don't need Great Prophets if we can't found a religion
                if (unit.hasUnique(UniqueType.MayFoundReligion)) {
                    if (!canFoundReligion) return SR_IMPOSSIBLE
                    spawnsProphet = true
                    break
                }
                // Don't need Missionaries or Inquisitors if we aren't a founder
                else if (unit.hasUnique(UniqueType.CanSpreadReligion) || unit.hasUnique(UniqueType.CanRemoveHeresy)) return SR_STRATEGY
            }
            // None of this is useful if we don't have a religion.
            if (!spawnsProphet && (building.hasUnique(UniqueType.SpreadReligionStrength, GameContext.IgnoreConditionals) ||
                    extraMissionarySpreads(ruleset, building).isNotEmpty()))
                return SR_USELESS
            return null
        }

        /** The base, grand strategy and policy values (the yield loop follows them in the original) */
        private fun valueTerms() {
            bonus += BuildingValueAi.basicValue(city, building)
            //Acts as modifier for value.
            bonus += BuildingValueAi.grandStrategyValue(civ, city, building) * (civ.getEraNumber() + 1)
            bonus += BuildingValueAi.policyValue(city, building)
        }

        private fun quests() {
            if (ignoreSituational || !civ.isMajorCiv) return
            for (minor in civ.gameInfo.getAliveCityStates())
                if (minor.questManager.getAssignedQuestsFor(civ).any { it.questName == QuestName.ConstructWonder.value && it.data1 == building.name })
                    bonus += 10
        }

        private fun geography() {
            //Airlift
            if (building.hasUnique(UniqueType.AllowsAirlift, GameContext.IgnoreConditionals)) {
                if (!city.isCapital() && capital != null && !hasSharedAreaWith(city, capital)) bonus += 10
            }

            //No Land trade connections? The trade priority of the city is 0 (see the KDoc)
            if (tradeRouteUniques("Land")) {
                if (!freeBuilding) bonus -= 50
            }

            //this is not about trade routes but city connections
            if (building.hasUnique(UniqueType.ConnectTradeRoutes, GameContext.IgnoreConditionals)) {
                if (city.isConnectedToCapital()) bonus -= 50
                else if (capital != null && !hasSharedAreaWith(capital, city)) bonus += 10 * max(1, population)
                else bonus += 5 * max(1, population)
            }

            //bonus to sea trade: can we even have sea trade here
            if (building.getMatchingUniques(UniqueType.TradeRouteRangePercent, GameContext.IgnoreConditionals).any { it.params[1] == "Water" }) {
                if (WaterBodies.isCoastal(city) && hasSeaTradeRoute()) bonus += 10
                else bonus -= 100
            }
        }

        private fun tradeRouteUniques(filter: String): Boolean =
            building.getMatchingUniques(UniqueType.TradeRouteRange, GameContext.IgnoreConditionals).any { it.params[1] == filter } ||
                building.getMatchingUniques(UniqueType.TradeRouteRangePercent, GameContext.IgnoreConditionals).any { it.params[1] == filter } ||
                building.getMatchingUniques(UniqueType.TradeRouteGold, GameContext.IgnoreConditionals).any { it.params[1] == filter }

        /** CvGameTrade::GetAllPotentialTradeRoutesFromCity(city, sea): some destination in the range of our sea trade unit */
        private fun hasSeaTradeRoute(): Boolean {
            val routes = civ.tradeRoutes
            val range = routes.getTradeUnitType(true)?.let { routes.getBaseRange(it) } ?: return false
            return routes.getDestinations(city, true, range, onlyOne = true, ignoreExisting = true).isNotEmpty()
        }

        private fun resources(): Int? {
            //Building uses resources? Not if we're in resistance, a puppet or automated, thanks!
            if (building.getResourceRequirementsPerTurn(city.state).any { it.value > 0 } &&
                (isPuppetAndAnnexable || civ.isHuman() || city.isInResistance())) return SR_STRATEGY

            // the building needs aluminum? check if we'd still have enough for the spaceship (and, outside the core cities for the
            // spaceship parts, for the buildings in those core cities)
            val aluminumNeeded = building.getResourceRequirementsPerTurn(city.state)[SpaceshipProductionAi.ALUMINUM]
            val aluminum = ruleset.tileResources[SpaceshipProductionAi.ALUMINUM]
            if (aluminumNeeded > 0 && aluminum != null) {
                var usable = DealAi.numResourceAvailable(civ, aluminum, includeImport = true) - SpaceshipProductionAi.aluminumStillNeededForSpaceship(civ)
                if (city !in SpaceshipProductionAi.coreCities(civ)) usable -= SpaceshipProductionAi.aluminumStillNeededForCoreCities(civ)
                if (aluminumNeeded > usable) return SR_STRATEGY
            }

            for (unique in building.getMatchingUniques(UniqueType.ProvidesResources, GameContext.IgnoreConditionals)) {
                if (unique.params[0].toInt() <= 0) continue
                val resource = ruleset.tileResources[unique.params[1]] ?: continue
                //Decrease value based on # we own.
                val owned = DealAi.numResourceAvailable(civ, resource, includeImport = false)
                bonus += max(0, 100 - owned * 5)
            }

            //we would get extra copies but there is nothing to copy? bad
            if (building.hasUnique(UniqueType.ProvidesExtraLuxuryFromCityResources, GameContext.IgnoreConditionals)) {
                //count both improved and unimproved
                val luxuries = city.getTiles()
                    .filter { it.tileResource?.resourceType == ResourceType.Luxury }
                    .sumOf { max(1, it.resourceAmount) }
                if (luxuries <= 0 && !freeBuilding) return SR_USELESS
                bonus += luxuries * 20
            }
            return null
        }

        private fun happinessTerms() {
            var happinessValue = 10 // value of one additional happiness
            if (happiness < 0) happinessValue *= 4
            if (happiness <= rules.define(AiDefine.SUPER_UNHAPPY_THRESHOLD)) happinessValue *= 4
            if (happiness <= rules.define(AiDefine.VERY_UNHAPPY_THRESHOLD)) happinessValue *= 4

            if (building.happiness > 0) bonus += happinessValue * building.happiness.toInt()

            val perCity = building.getMatchingUniques(UniqueType.StatsPerCity, GameContext.IgnoreConditionals)
                .filter { it.params[1] == "in all cities" }.sumOf { it.stats.happiness.toInt() }
            if (perCity > 0) bonus += happinessValue * perCity * civ.cities.size

            for (unique in building.getMatchingUniques(UniqueType.StatsPerPolicies, GameContext.IgnoreConditionals)) {
                val perPolicies = unique.stats.happiness.toInt()
                if (perPolicies > 0) bonus += happinessValue * civ.policies.getAdoptedPolicies().size * perPolicies / unique.params[1].toInt()
            }

            //unfortunately we have to loop through all buildings in the game to do this. Sigh...
            for (other in ruleset.buildings.values) {
                // buildings that need this one in all cities
                val needed = other.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                    .flatMap { it.getModifiers(UniqueType.ConditionalBuildingBuiltAll) }
                    .filter { building.matchesFilter(it.params[0]) }
                    .sumOf { modifier -> civ.cities.count { it.matchesFilter(modifier.params[1]) } }
                if (needed > 0) {
                    val have = civ.cities.count { it.cityConstructions.isBuilt(building.name) }
                    bonus += needed * max(1, have) * 100
                }
            }
            if (happiness < 0) {
                // the happiness this building gives for each building of a class
                for (unique in building.getMatchingUniques(UniqueType.StatsFromObject, GameContext.IgnoreConditionals)) {
                    if (unique.stats.happiness <= 0 || unique.params[1] !in ruleset.buildings) continue
                    bonus += civ.cities.count { c -> c.cityConstructions.getBuiltBuildings().any { it.matchesFilter(unique.params[1]) } } * 25
                }
            }
        }

        private fun defense(): Int? {
            var defense = 0
            for (unique in building.getMatchingUniques(UniqueType.PopulationLossFromNukes, GameContext.IgnoreConditionals)) {
                val change = unique.params[0].toInt()
                if (change < 0) defense += -change
            }
            defense += building.getMatchingUniques(UniqueType.BetterDefensiveBuildings, GameContext.IgnoreConditionals)
                .sumOf { it.params[0].toInt().coerceAtLeast(0) / 2 }
            if (building.cityStrength > 0) defense += (building.cityStrength * 100).toInt() / 25
            if (building.cityHealth > 0) defense += building.cityHealth / 10
            if (!WaterBodies.isCoastal(city) && building.hasUnique(UniqueType.EnemyUnitsSpendExtraMovement, GameContext.IgnoreConditionals))
                defense += 25

            var defenseMod = 100
            val atWarWithMajor = civ.gameInfo.civilizations.any { it.isMajorCiv && civ.isAtWarWith(it) }
            if (ProductionSanityAi.isBorderCity(city) || WaterBodies.isCoastal(city, 12)) defenseMod += if (atWarWithMajor) 1000 else 500
            else if (atWarWithMajor) defenseMod += 150

            if (!ignoreSituational && CityDangerAi.isUnderSiege(city)) {
                defenseMod += 1000
                //do not build any non-defensive buildings when under siege
                if (defense == 0 && city.health < city.getMaxHealth()) return SR_STRATEGY
            }
            defense *= defenseMod
            defense /= 100
            bonus += defense
            return null
        }

        /** Courthouse? Let's get it ASAP. */
        private fun courthouse() {
            if (!building.hasUnique(UniqueType.RemovesAnnexUnhappiness, GameContext.IgnoreConditionals)) return
            if (city.matchesFilter("Annexed") && !city.containsBuildingUnique(UniqueType.RemovesAnnexUnhappiness)) {
                //Extend based on population.
                bonus += 5000 * population
                courthouse = true
            }
        }

        /** Units that "Only available <in cities with a [building]>" (CvBuildingEntry::GetUnitsUnlocked) */
        private fun unlocks() {
            for (unit in ruleset.units.values) {
                val unlocked = unit.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals).any { unique ->
                    unique.getModifiers(UniqueType.ConditionalCityWithBuilding).any { building.matchesFilter(it.params[0]) }
                }
                if (!unlocked) continue
                val tech = unit.requiredTech
                bonus += if (tech == null || civ.tech.isResearched(tech)) 1000 else 50
            }
        }

        /** Domain mods are good, and should be stacked; unit combat bonuses should stack too. */
        private fun militaryExperience() {
            if (isPuppetAndAnnexable) return
            val cityExperience = MilitaryCities.experienceUniques(city)
            val cityProduction = MilitaryCities.productionUniques(city)
            val buildingExperience = MilitaryCities.experienceUniques(building)
            val buildingProduction = MilitaryCities.productionUniques(building)

            for (domain in MilitaryCities.UnitDomain.entries) {
                var temp = 0
                if (MilitaryCities.domainExperience(civ, buildingExperience, domain) > 0)
                    temp += MilitaryCities.domainExperience(civ, cityExperience, domain)
                val production = MilitaryCities.domainProduction(civ, buildingProduction, domain)
                if (production > 0) temp += MilitaryCities.domainProduction(civ, cityProduction, domain) + production
                //Let's try to build our military buildings in our best cities only. Discourage bad cities.
                if (temp > 0) bonus += if (MilitaryCities.bestForDomain(civ, domain) == city) temp else temp / 2
            }

            for (combatClass in MilitaryCities.combatClasses(civ)) {
                var temp = 0
                val production = MilitaryCities.classProduction(civ, buildingProduction, combatClass)
                if (production > 0) temp += MilitaryCities.classProduction(civ, cityProduction, combatClass) + production
                val experience = MilitaryCities.classExperience(civ, buildingExperience, combatClass)
                if (experience > 0) temp += MilitaryCities.classExperience(civ, cityExperience, combatClass) + experience
                if (temp > 0) bonus += if (MilitaryCities.bestForCombatClass(civ, combatClass) == city) temp else temp / 2
            }
        }

        private fun maintenance(goodForGold: Boolean): Int? {
            //careful if it would bankrupt us
            if (building.maintenance > 0 && !courthouse && !goodForGold && building.cityHealth == 0 &&
                WarStateAi.turnsToBankruptcy(civ, building.maintenance) < 9) return SR_MAINTENANCE
            return null
        }

        /** Fewer buildings while at war. */
        private fun warPenalty() {
            if (courthouse || ignoreSituational) return
            var penalty = 0
            //Tiny army? Eek!
            if (civ.units.getCivUnits().count { it.isMilitary() } <= civ.cities.size * 3) penalty += 75
            if (WarStateAi.numberCivsAtWarWith(civ) > 0 && building.cityStrength <= 0 && !isPuppetAndAnnexable && !freeBuilding &&
                happiness > rules.define(AiDefine.VERY_UNHAPPY_THRESHOLD)) {
                penalty += 50 + CityThreat.threatValue(city)
                for (other in civ.gameInfo.civilizations) {
                    if (other == civ || !other.isMajorCiv || !other.isAlive() || !civ.isAtWarWith(other)) continue
                    val state = WarStateAi.warState(civ, other)
                    if (state == null || state < WarState.Stalemate) penalty += 50
                }
            }
            bonus *= (100 - min(99, penalty))
            bonus /= 100
        }

        private fun eraTerms() {
            val buildingEra = max(0, era(ruleset, building))
            val eraValue = max(1, civ.getEraNumber() - buildingEra)
            bonus += 200 * eraValue

            //Unlocks another building?
            if (chainLength() > 0) bonus += 100 * eraValue

            //UB? scale off with pop so UB will not be the first building to build in a fresh city
            if (building.uniqueTo == civ.civName) {
                bonus += if (building.hasUnique(UniqueType.RemovesAnnexUnhappiness, GameContext.IgnoreConditionals)) max(15, population) * 25
                else population * 25
            }
        }

        /** CvPlayer::GetChainLength: how many buildings of our civilization need this one, directly or through others */
        private fun chainLength(): Int {
            var count = 0
            var current = listOf(building)
            while (current.isNotEmpty()) {
                val next = ArrayList<Building>()
                for (step in current) {
                    val base = step.replaces ?: step.name
                    for (other in ruleset.buildings.values)
                        if (other.requiredBuilding == base && civ.getEquivalentBuilding(other) == other) {
                            count++
                            next.add(other)
                        }
                }
                current = next
            }
            return count
        }
    }

    /** CvCity::HasSharedAreaWith(other, allow land, allow water): the cities' tiles or the tiles around them share a land continent or a body of water */
    private fun hasSharedAreaWith(first: City, second: City): Boolean {
        val bodies = WaterBodies.of(first.civ)
        fun areas(city: City): Set<String> {
            val center = city.getCenterTile()
            val result = HashSet<String>()
            result.add("land ${center.getContinent()}")
            for (neighbor in center.neighbors)
                if (neighbor.isWater) result.add("water ${bodies.bodyOf(neighbor)}") else result.add("land ${neighbor.getContinent()}")
            return result
        }
        val secondAreas = areas(second)
        return areas(first).any { it in secondAreas }
    }
}
