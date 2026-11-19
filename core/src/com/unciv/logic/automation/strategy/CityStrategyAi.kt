package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatPersonPointsBreakdown
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.logic.automation.unit.tactical.CityThreat
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 *  The city strategy tests (CvCityStrategyAI.cpp: CityStrategyAIHelpers::IsTestCityStrategy_*) and the helpers of CvCityStrategyAI that
 *  other ports read (PrecalcYieldStats). ReweightByTurnsLeft is [WonderProductionAi.reweightByTurnsLeft], GetNumUnitsWithUnitAI [UnitAiTypes].
 *
 *  Not registered here: the trade and espionage city strategies (registered by their own port). AICITYSTRATEGY_CAPITAL_UNDER_THREAT
 *  reads the dominance of the capital's zones on the tactical map ([CityThreat.isCapitalUnderThreat]).
 */
object CityStrategyAi {
    /** CityStrategyAI.cpp NO_WORKER_AFTER_DISBAND_DURATION: the lock-out of all three tile improver strategies after a scrapped worker */
    private const val NO_WORKER_AFTER_DISBAND_DURATION = 12
    /** The reasons that keep an improvement from the tiles of a civilization whatever the tile (the build's tech, obsolescence and civilization) */
    private val TILE_INDEPENDENT_PROBLEMS = listOf(
        ImprovementBuildingProblem.Replaced, ImprovementBuildingProblem.WrongCiv, ImprovementBuildingProblem.MissingTech,
        ImprovementBuildingProblem.Unbuildable, ImprovementBuildingProblem.Obsolete)
    private const val NEED_TILE_IMPROVERS = "AICITYSTRATEGY_NEED_TILE_IMPROVERS"
    private const val NEED_NAVAL_TILE_IMPROVEMENT = "AICITYSTRATEGY_NEED_NAVAL_TILE_IMPROVEMENT"
    private const val CAPITAL_UNDER_THREAT = "AICITYSTRATEGY_CAPITAL_UNDER_THREAT"

    //region Helpers

    /**
     *  Per civilization and turn: the counts and lists the tests of all its cities share (CvPlayer::GetNumUnitsWithUnitAI, ...).
     *  Rebuilt on a new turn and by [clearTurnCache].
     */
    private class TurnCache(val turn: Int) {
        var landWorkers = -1
        var seaWorkers = -1
        var settlers = -1
        var enemyNavalTiles: List<Tile>? = null
    }
    private val turnCaches = WeakHashMap<Civilization, TurnCache>()

    private fun turnCache(civ: Civilization): TurnCache = synchronized(turnCaches) {
        val turn = civ.gameInfo.turns
        turnCaches[civ]?.takeIf { it.turn == turn } ?: TurnCache(turn).also { turnCaches[civ] = it }
    }

    /** Forgets the shared values of [civ] - at the start of its city strategy turn, as units and cities may have changed since */
    internal fun clearTurnCache(civ: Civilization) {
        synchronized(turnCaches) { turnCaches.remove(civ) }
    }

    private fun numLandWorkers(civ: Civilization): Int = turnCache(civ).run {
        if (landWorkers < 0) landWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true, includeWater = false)
        landWorkers
    }
    private fun numSeaWorkers(civ: Civilization): Int = turnCache(civ).run {
        if (seaWorkers < 0) seaWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER_SEA, includeBeingTrained = true, includeWater = true)
        seaWorkers
    }
    private fun numSettlers(civ: Civilization): Int = turnCache(civ).run {
        if (settlers < 0) settlers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true, includeWater = true)
        settlers
    }

    /** The yields of CvCityStrategyAI::PrecalcYieldStats, in the original's YieldTypes order, with their define of the expected yield per citizen */
    private val EXPECTED_YIELDS = listOf(
        Stat.Food to AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_FOOD,
        Stat.Production to AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_PRODUCTION,
        Stat.Gold to AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_GOLD,
        Stat.Science to AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_SCIENCE,
        Stat.Culture to AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_CULTURE,
        Stat.Faith to AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_FAITH)

    /**
     *  CvCityStrategyAI::PrecalcYieldStats (run at the start of the city's strategy turn, [CityAi.doTurn]): for each of food, production,
     *  gold, science, culture and faith, the city's yield per turn x 100 per citizen (population, at least 1; whole hundredths) minus the
     *  expected yield per citizen x 100 (AI_CITYSTRATEGY_YIELD_DEFICIENT_*: VP's 0.5 food - it is the surplus after consumption -, 1 production,
     *  1.5 gold, 2.5 science, culture and faith). The yield with the greatest surplus is [CityAi.mostAbundantYield] if that surplus is above 0;
     *  the one with the greatest shortfall is [CityAi.mostDeficientYield] if it is below 0. A tie goes to the earlier yield for the abundant
     *  one and the later for the deficient one (the original's stable descending sort takes the front and back).
     *
     *  The city's yield is [com.unciv.logic.city.CityStats.currentCityStats] (CvCity::getYieldRateTimes100, the cached value), the gold without
     *  building maintenance as the original's gold has none. The original's remaining yields (tourism, golden age points, ...) only fill
     *  m_aiYieldModifier, which nothing reads, so it is not kept.
     */
    fun precalcYieldStats(city: City) {
        val rules = city.civ.gameInfo.ruleset.ai
        val stats = city.cityStats
        val population = max(1, city.population.population)
        val scores = EXPECTED_YIELDS.map { (stat, define) ->
            var amount = stats.currentCityStats[stat]
            if (stat == Stat.Gold) amount -= stats.finalStatList["Maintenance"]?.gold ?: 0f
            val perPopulation100 = (amount * 100).roundToInt() / population
            stat to perPopulation100 - (100 * rules.defineFloat(define)).toInt()
        }.sortedByDescending { it.second }
        city.ai.mostAbundantYield = scores.first().takeIf { it.second > 0 }?.first
        city.ai.mostDeficientYield = scores.last().takeIf { it.second < 0 }?.first
    }

    /** CityStrategyAIHelpers::IsTestCityStrategy_NeedImprovement: [stat] is the city's most deficient yield */
    private fun isNeedImprovement(city: City, stat: Stat) = city.ai.mostDeficientYield == stat

    /** The stats of the city's buildings (the "Buildings" branch of its base stats) */
    private fun buildingYield(city: City, stat: Stat): Float = city.cityStats.baseStatTree.children["Buildings"]?.totalStats?.get(stat) ?: 0f

    /** CvCity::getYieldRateModifier: the percentage bonus of the city's buildings */
    fun buildingYieldModifier(city: City, stat: Stat): Float = city.cityStats.statPercentBonusTree.children["Buildings"]?.totalStats?.get(stat) ?: 0f

    /** CvCity::GetBaseYieldRateFromBuildings */
    fun baseYieldFromBuildings(city: City, stat: Stat): Int = buildingYield(city, stat).toInt()

    /** CvCity::GetYieldPerPopTimes100: what the city's buildings give per citizen, ×100 */
    fun yieldPerPopTimes100(city: City, stat: Stat): Int {
        var total = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.StatsPerPopulation, city.state)) {
                val perAmount = unique.params[1].toInt()
                if (perAmount > 0) total += (unique.stats[stat] * 100 / perAmount).toInt()
            }
        return total
    }

    private val landUnitFilterCache = WeakHashMap<AiRuleset, HashMap<String, Boolean>>()

    /** CvCity::getDomainFreeExperience(DOMAIN_LAND): the experience the city's buildings give every new land military unit - a unique
     *  that only some of them get (Poland's Ducal Stable gives [Mounted] only) is not a domain experience */
    fun landFreeExperience(city: City): Int {
        val ruleset = city.civ.gameInfo.ruleset
        val cache = synchronized(landUnitFilterCache) { landUnitFilterCache.getOrPut(ruleset.ai) { HashMap() } }
        var experience = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.UnitStartingExperience, city.state)) {
                val filter = unique.params[0]
                val matchesLand = synchronized(cache) {
                    cache.getOrPut(filter) { ruleset.units.values.filter { it.isLandUnit && it.isMilitary }.let { land -> land.isNotEmpty() && land.all { it.matchesFilter(filter) } } }
                }
                if (matchesLand && city.matchesFilter(unique.params[2])) experience += unique.params[1].toInt()
            }
        return experience
    }

    /** Tiles within 2 of the city owned by its owner */
    private fun countOwnedTilesWithin2(city: City, predicate: (Tile) -> Boolean): Int {
        var count = 0
        city.getCenterTile().forEachTileInDistance(2) { if (it.getOwner() == city.civ && predicate(it)) count++ }
        return count
    }

    /** GC.getGame().getHandicapInfo().GetID(): the index of the game's difficulty (Settler 0 ... Deity 7) */
    fun gameHandicapIndex(civ: Civilization): Int = civ.gameInfo.ruleset.difficulties.keys.indexOf(civ.gameInfo.getDifficulty().name)

    /** GameSpeedInfo::getTrainPercent */
    private fun trainPercent(civ: Civilization): Int = (civ.gameInfo.speed.productionCostModifier * 100).toInt()
    //endregion

    //region Tests

    fun isTinyCity(city: City): Boolean =
        city.population.population < city.civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_SMALL_CITY_POP_THRESHOLD)

    fun isSmallCity(city: City): Boolean {
        val rules = city.civ.gameInfo.ruleset.ai
        val population = city.population.population
        return population >= rules.define(AiDefine.AI_CITYSTRATEGY_SMALL_CITY_POP_THRESHOLD)
            && population < rules.define(AiDefine.AI_CITYSTRATEGY_MEDIUM_CITY_POP_THRESHOLD)
    }

    /** Never the capital, so that one city keeps its high SPACESHIP and similar flavors */
    fun isMediumCity(city: City): Boolean {
        if (city.isCapital()) return false
        val rules = city.civ.gameInfo.ruleset.ai
        val population = city.population.population
        return population >= rules.define(AiDefine.AI_CITYSTRATEGY_MEDIUM_CITY_POP_THRESHOLD)
            && population < rules.define(AiDefine.AI_CITYSTRATEGY_LARGE_CITY_POP_THRESHOLD)
    }

    fun isLargeCity(city: City): Boolean =
        city.population.population >= city.civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_LARGE_CITY_POP_THRESHOLD)

    /** CvEconomicAI::GetLastTurnWorkerDisbanded is set and the game turn is at most NO_WORKER_AFTER_DISBAND_DURATION after it */
    private fun workerDisbandedRecently(civ: Civilization): Boolean {
        val last = civ.ai.economic.lastTurnWorkerDisbanded
        return last >= 0 && civ.gameInfo.turns - last <= NO_WORKER_AFTER_DISBAND_DURATION
    }

    /**
     *  CvCity::UpdateTerrainImprovementNeed (Vox Populi), run at the start of the city's strategy turn ([CityAi.doTurn]): how many of the
     *  city's workable tiles - the city tile itself excluded - a worker could improve. A land tile of the owner that is passable and no
     *  city counts:
     *  - once if it has no improvement and at least one build applies to it, twice if it also has a resource its owner can see. A build
     *    applies when the tile takes that improvement (or removal) now: the owner has its tech and the improvement is not obsolete,
     *    unique to another civilization or unbuildable. Like the original's build list this includes the Great Person improvements (they
     *    have no tech, so any flat tile counts from the start), the removal of a feature, and the removal of a road or railroad;
     *  - once if its improvement is pillaged;
     *  - once if it has a road or railroad that is not the best the owner can build, or is pillaged.
     *
     *  Rules: the original's IsNoBuild (a trait that forbids a build: Vox Populi's Byzantine fort) has no counterpart in BNW's data; its
     *  build prerequisites and obsolescence are the improvement's tech and "obsolete with" ([ImprovementBuildingProblem]). The original
     *  does not test whether the tile is worked, nor the city's population.
     */
    fun terrainImprovementNeed(city: City): Int {
        val civ = city.civ
        val ruleset = civ.gameInfo.ruleset
        val builds = ruleset.tileImprovements.values.filter { improvement ->
            improvement.name != Constants.cancelImprovementOrder && improvement.name != Constants.repair
                && TILE_INDEPENDENT_PROBLEMS.none { it.isRelevant(improvement, civ.state) }
        }
        val bestRoute = civ.tech.getBestRoadAvailable()
        var need = 0
        for (tile in city.tilesInRange) {
            if (tile.isCityCenter() || tile.isWater || tile.getOwner() != civ || tile.isImpassible()) continue
            if (tile.improvement == null) {
                val context = GameContext(civ, tile = tile)
                if (builds.none { tile.improvementFunctions.canBuildImprovement(it, context) }) continue
                need++
                if (civ.canSeeResource(tile.tileResource)) need++
            } else if (tile.improvementIsPillaged) need++
            else if (tile.roadStatus != RoadStatus.None && (tile.roadStatus != bestRoute || tile.roadIsPillaged)) need++
        }
        return need
    }

    /**
     *  IsTestCityStrategy_NeedTileImprovers (Vox Populi): do we really need to train workers? False for 12 turns after a worker was
     *  scrapped ([EconomicAi.disbandExtraWorkers]), then, with [needing] the cities with a [terrainImprovementNeed]:
     *  - a city-state with at least one worker per needing city: false (the original's MOD_MINOR_CIV_EXTENDED branch, which rebuilds a lost
     *    worker after half the training-percent turns, is not in BNW's rules);
     *  - a major civilization with more workers than needing cities + 1, or one that is losing all wars: false;
     *  - with at most 4 needing cities and the ERADICATE_BARBARIANS military strategy, 6 workers or more for each military unit: false;
     *  - true after the desperate turn ([AiDefine.AI_CITYSTRATEGY_NEED_TILE_IMPROVERS_DESPERATE_TURN], scaled by the speed's training
     *    percent) when the workers x [weightThreshold] / 100 are no more than the needing cities, or none.
     *  Workers count the ones being trained. The military units are CvPlayer::getNumMilitaryUnits' (units with military support: [com.unciv.logic.map.mapunit.MapUnit.isMilitary]).
     */
    fun needTileImprovers(city: City, weightThreshold: Int): Boolean {
        val civ = city.civ
        val needing = civ.ai.citiesNeedingTerrainImprovements
        if (workerDisbandedRecently(civ)) return false
        val numWorkers = numLandWorkers(civ)
        if (civ.isCityState) {
            if (numWorkers >= needing) return false
        } else {
            if (numWorkers > needing + 1) return false
            if (WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return false
        }
        if (needing <= 4 && civ.ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")
            && numWorkers * 6 >= civ.units.getCivUnits().count { it.isMilitary() }) return false
        val moddedNumWorkers = numWorkers * weightThreshold / 100
        if (moddedNumWorkers <= needing || moddedNumWorkers == 0) {
            val desperateTurn = civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_NEED_TILE_IMPROVERS_DESPERATE_TURN) * trainPercent(civ) / 100
            if (civ.gameInfo.turns > desperateTurn) return true
        }
        return false
    }

    /**
     *  IsTestCityStrategy_WantTileImprovers (Vox Populi): the owner's workers (those being trained included) are fewer than the
     *  cities needing improvements x [weightThreshold] (1 in Vox Populi: a worker per city), the city has at least
     *  [AiDefine.AI_CITYSTRATEGY_WANT_TILE_IMPROVERS_MINIMUM_SIZE] citizens, and it is not locked out for 12 turns after a scrapped
     *  worker, or while the owner is losing all wars. No workers at all: always.
     */
    fun wantTileImprovers(city: City, weightThreshold: Int): Boolean {
        val civ = city.civ
        if (workerDisbandedRecently(civ)) return false
        if (!civ.isCityState && WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return false
        val numBuilders = numLandWorkers(civ)
        if (numBuilders <= 0) return true
        return city.population.population >= civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_WANT_TILE_IMPROVERS_MINIMUM_SIZE)
            && numBuilders < civ.ai.citiesNeedingTerrainImprovements * weightThreshold
    }

    /**
     *  IsTestCityStrategy_EnoughTileImprovers (Vox Populi): always for 12 turns after a worker was scrapped; never without workers or
     *  while the city needs workers (NEED_TILE_IMPROVERS); a city-state with a worker per city has enough; else the workers x 100 reach
     *  [perCityThreshold] (125 plus the TileImprovement flavor modifier) per city needing improvements.
     */
    fun enoughTileImprovers(city: City, perCityThreshold: Int): Boolean {
        val civ = city.civ
        if (workerDisbandedRecently(civ)) return true
        val numBuilders = numLandWorkers(civ)
        if (numBuilders <= 0) return false
        if (city.ai.isCityStrategyActive(NEED_TILE_IMPROVERS)) return false
        if (civ.isCityState && numBuilders >= civ.cities.size) return true
        return numBuilders * 100 >= perCityThreshold * civ.ai.citiesNeedingTerrainImprovements
    }

    /** At least the threshold percent of the city's owned workable tiles are water that is not a lake */
    fun needNavalGrowth(city: City, threshold: Int): Boolean {
        var oceanTiles = 0
        var workableTiles = 0
        for (tile in city.tilesInRange) {
            if (tile.getOwner() != city.civ || tile.isCityCenter()) continue
            workableTiles++
            if (tile.isWater && !tile.getBaseTerrain().isFreshwater) oceanTiles++
        }
        return workableTiles > 0 && oceanTiles * 100 / workableTiles >= threshold
    }

    /** More unimproved water resources the city can work than sea workers (on the map or being trained) */
    fun needNavalTileImprovement(city: City): Boolean {
        var unimproved = 0
        for (tile in city.tilesInRange)
            if (tile.isWater && tile.getCity() == city && tile.resource != null && tile.improvement == null) unimproved++
        return unimproved > numSeaWorkers(city.civ)
    }

    fun isCapitalNeedSettler(city: City, weightThreshold: Int): Boolean {
        if (!city.isCapital()) return false
        val civ = city.civ
        if (civ.isCityState) return false
        val citiesPlusSettlers = civ.cities.size + numSettlers(civ)
        if (citiesPlusSettlers >= 3) return false
        val turn = civ.gameInfo.turns
        if (turn > 50 && city.ai.isCityStrategyActive(CAPITAL_UNDER_THREAT)) return false
        if (civ.ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_WAR_MOBILIZATION")) return false
        return (citiesPlusSettlers == 1 && turn * 4 > weightThreshold) || (citiesPlusSettlers == 2 && turn * 2 > weightThreshold)
    }

    /**
     *  CvCity::GetJONSCulturePerTurnFromBuildings and GetFaithPerTurnFromBuildings: what the city's buildings give by themselves, with their
     *  unconditional stats (the tech-enhanced yields). Beliefs and policies that add culture or faith per building are separate sums
     *  there (FromReligion, FromPolicies), so the stat tree's "Buildings" branch, which holds them, is not read.
     */
    private fun ownYieldFromBuildings(city: City, stat: Stat): Int {
        var total = 0f
        for (building in city.cityConstructions.getBuiltBuildings()) {
            total += building[stat]
            building.forEachMatchingUnique(UniqueType.Stats, city.state) { total += it.stats[stat] }
        }
        return total.toInt()
    }

    fun isFirstCultureBuilding(city: City): Boolean {
        val culture = ownYieldFromBuildings(city, Stat.Culture)
        return culture <= 0 || (city.isCapital() && culture <= 1)
    }

    fun isFirstCultureBuildingEmergency(city: City): Boolean =
        city.population.population >= 5 && ownYieldFromBuildings(city, Stat.Culture) <= 0

    fun isFirstScienceBuilding(city: City): Boolean =
        city.population.population >= 4 && yieldPerPopTimes100(city, Stat.Science) == 0

    fun isFirstGoldBuilding(city: City): Boolean =
        city.population.population >= 4 && buildingYieldModifier(city, Stat.Gold) == 0f

    fun isFirstProductionBuilding(city: City): Boolean =
        city.population.population >= 4 && baseYieldFromBuildings(city, Stat.Production) == 0 && buildingYieldModifier(city, Stat.Production) == 0f

    /** Religion personality above 6, or above 4 once the civilization has a pantheon; the city has 2+ citizens and no faith from buildings */
    fun isFirstFaithBuilding(city: City): Boolean {
        if (ownYieldFromBuildings(city, Stat.Faith) > 0) return false
        if (city.population.population < 2) return false
        val religionFlavor = city.civ.ai.personality(Flavor.Religion)
        return (religionFlavor > 4 && city.civ.religionManager.religion != null) || religionFlavor > 6
    }

    /** CvCityCitizens::IsAnyPlotBlockaded: a water tile of the city with a military unit of an enemy within 2 tiles on the same body of water */
    fun isUnderBlockade(city: City): Boolean {
        val civ = city.civ
        val center = city.getCenterTile()
        val cache = turnCache(civ)
        val allEnemyTiles = cache.enemyNavalTiles ?: civ.getCivsAtWarWith().flatMap { enemy ->
            enemy.units.getCivUnits().filter { it.isMilitary() && it.currentTile.isWater }.map { it.currentTile }.toList()
        }.also { cache.enemyNavalTiles = it }
        val enemyTiles = allEnemyTiles.filter { it.aerialDistanceTo(center) <= 5 }
        if (enemyTiles.isEmpty()) return false
        val bodies = WaterBodies.of(civ)
        return city.tilesInRange.any { tile ->
            tile != center && tile.isWater &&
                enemyTiles.any { it.aerialDistanceTo(tile) <= 2 && bodies.bodyOf(it) == bodies.bodyOf(tile) }
        }
    }

    fun isMediumCityHighDifficulty(city: City): Boolean = city.population.population >= 5 && gameHandicapIndex(city.civ) > 4

    fun isHillCity(city: City): Boolean = countOwnedTilesWithin2(city) { it.isHill() } > 1
    fun isForestCity(city: City): Boolean = countOwnedTilesWithin2(city) { Constants.forest in it.terrainFeatures } > 1
    fun isJungleCity(city: City): Boolean = countOwnedTilesWithin2(city) { Constants.jungle in it.terrainFeatures } > 1
    fun isMountainCity(city: City): Boolean = city.getCenterTile().neighbors.any { it.baseTerrain == Constants.mountain }

    /** CityStrategyAIHelpers::IsTestCityStrategy_GoodGPCity: 8+ Great Person points per turn from specialists and buildings with their modifiers, not rounded per kind */
    fun isGoodGreatPersonCity(city: City): Boolean =
        GreatPersonPointsBreakdown(city).sumFixedPoint().sumValues() >= 8 * GreatPersonPointsBreakdown.fixedPointFactor

    /**
     *  CvCityCulture::GetSlotTypeFirstAvailableCultureBuilding: the slot type of the cheapest building with Great Work slots, not a world wonder,
     *  that the city does not have and can build now; null if none.
     */
    fun firstAvailableCultureBuildingSlotType(city: City): String? {
        var cheapest: Building? = null
        for (building in city.civ.gameInfo.ruleset.buildings.values) {
            if (building.greatWorkSlotTypes.isEmpty() || building.isWonder) continue
            if (cheapest != null && building.cost >= cheapest.cost) continue
            if (city.cityConstructions.isBuilt(building.name)) continue
            if (!building.isBuildable(city.cityConstructions)) continue
            cheapest = building
        }
        return cheapest?.greatWorkSlotTypes?.first()?.name
    }

    /** Empty Great Work slots of [slotType] over all cities (CvPlayerCulture::GetNumGreatWorkSlots(eSlotType)) */
    fun numAvailableGreatWorkSlots(civ: Civilization, slotType: String): Int =
        civ.cities.sumOf { city -> city.greatWorks.getSlots().count { it.getType().name == slotType && it.isFree() } }

    fun needCultureBuilding(city: City): Boolean {
        val slotType = firstAvailableCultureBuildingSlotType(city) ?: return false
        return numAvailableGreatWorkSlots(city.civ, slotType) <= 2
    }

    /** CityStrategyAIHelpers::IsTestCityStrategy_NeedTourismBuilding: culture of the world wonders, worked natural wonders and worked improvements plus the base tourism of the city above 10 */
    fun needTourismBuilding(city: City): Boolean {
        val culture = city.civ.culture
        return culture.getCultureOfWonders(city) + culture.getCultureOfWorkedTiles(city) + culture.getCityTourism(city) > 10
    }

    /** The capital, a city on another landmass than the capital, or one more than 20 tiles away from it */
    fun isGoodAirliftCity(city: City): Boolean {
        if (city.isCapital()) return true
        val capital = city.civ.getCapital() ?: return false
        if (city.getCenterTile().getContinent() != capital.getCenterTile().getContinent()) return true
        return city.getCenterTile().aerialDistanceTo(capital.getCenterTile()) > 20
    }
    //endregion

    /** Registers the city strategy tests (the CvCityStrategyAI::DoTurn dispatch); thresholds as in the original: XML WeightThreshold plus
     *  [CivAi.weightThresholdModifier] where the original adds GetWeightThresholdModifier */
    internal fun registerAll(tests: StrategyTests) {
        tests.registerCity("AICITYSTRATEGY_TINY_CITY") { city, _ -> isTinyCity(city) }
        tests.registerCity("AICITYSTRATEGY_SMALL_CITY") { city, _ -> isSmallCity(city) }
        tests.registerCity("AICITYSTRATEGY_MEDIUM_CITY") { city, _ -> isMediumCity(city) }
        tests.registerCity("AICITYSTRATEGY_LARGE_CITY") { city, _ -> isLargeCity(city) }
        tests.registerCity("AICITYSTRATEGY_LANDLOCKED") { city, _ -> !WaterBodies.isCoastal(city) }
        tests.registerCity(NEED_TILE_IMPROVERS) { city, def -> needTileImprovers(city, def.weightThreshold) }
        tests.registerCity("AICITYSTRATEGY_WANT_TILE_IMPROVERS") { city, def -> wantTileImprovers(city, def.weightThreshold) } // no flavor modifier, as in the original
        tests.registerCity("AICITYSTRATEGY_ENOUGH_TILE_IMPROVERS") { city, def ->
            enoughTileImprovers(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity("AICITYSTRATEGY_NEED_NAVAL_GROWTH") { city, def ->
            needNavalGrowth(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity(NEED_NAVAL_TILE_IMPROVEMENT) { city, _ -> needNavalTileImprovement(city) }
        tests.registerCity("AICITYSTRATEGY_ENOUGH_NAVAL_TILE_IMPROVEMENT") { city, _ -> !city.ai.isCityStrategyActive(NEED_NAVAL_TILE_IMPROVEMENT) }
        tests.registerCity("AICITYSTRATEGY_NEED_IMPROVEMENT_FOOD") { city, _ -> isNeedImprovement(city, Stat.Food) }
        tests.registerCity("AICITYSTRATEGY_NEED_IMPROVEMENT_PRODUCTION") { city, _ -> isNeedImprovement(city, Stat.Production) }
        tests.registerCity("AICITYSTRATEGY_HAVE_TRAINING_FACILITY") { city, _ -> landFreeExperience(city) > 0 }
        tests.registerCity("AICITYSTRATEGY_CAPITAL_NEED_SETTLER") { city, def ->
            isCapitalNeedSettler(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity("AICITYSTRATEGY_FIRST_CULTURE_BUILDING_EMERGENCY") { city, _ -> isFirstCultureBuildingEmergency(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_CULTURE_BUILDING") { city, _ -> isFirstCultureBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_SCIENCE_BUILDING") { city, _ -> isFirstScienceBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_GOLD_BUILDING") { city, _ -> isFirstGoldBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_FAITH_BUILDING") { city, _ -> isFirstFaithBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_PRODUCTION_BUILDING") { city, _ -> isFirstProductionBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_UNDER_BLOCKADE") { city, _ -> isUnderBlockade(city) }
        tests.registerCity("AICITYSTRATEGY_IS_PUPPET") { city, _ -> city.isPuppet }
        tests.registerCity("AICITYSTRATEGY_MEDIUM_CITY_HIGH_DIFFICULTY") { city, _ -> isMediumCityHighDifficulty(city) }
        tests.registerCity("AICITYSTRATEGY_ORIGINAL_CAPITAL") { city, _ -> city.isOriginalCapital }
        tests.registerCity("AICITYSTRATEGY_RIVER_CITY") { city, _ -> city.getCenterTile().isAdjacentToRiver() }
        tests.registerCity("AICITYSTRATEGY_MOUNTAIN_CITY") { city, _ -> isMountainCity(city) }
        tests.registerCity("AICITYSTRATEGY_FOREST_CITY") { city, _ -> isForestCity(city) }
        tests.registerCity("AICITYSTRATEGY_HILL_CITY") { city, _ -> isHillCity(city) }
        tests.registerCity("AICITYSTRATEGY_JUNGLE_CITY") { city, _ -> isJungleCity(city) }
        tests.registerCity("AICITYSTRATEGY_COAST_CITY") { city, _ -> WaterBodies.isCoastal(city) }
        tests.registerCity("AICITYSTRATEGY_GOOD_GP_CITY") { city, _ -> isGoodGreatPersonCity(city) }
        tests.registerCity("AICITYSTRATEGY_NEED_CULTURE_BUILDING") { city, _ -> needCultureBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_NEED_TOURISM_BUILDING") { city, _ -> needTourismBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_GOOD_AIRLIFT_CITY") { city, _ -> isGoodAirliftCity(city) }
        tests.registerCity(CAPITAL_UNDER_THREAT) { city, _ -> CityThreat.isCapitalUnderThreat(city) }
    }
}
