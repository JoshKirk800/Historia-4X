package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.automation.unit.CityDistances
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.PlayerStrategyDef
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import kotlin.math.max

/**
 *  The trigger tests of the economic (EconomicAIHelpers::IsTestStrategy_*, CvEconomicAI.cpp) and military
 *  (MilitaryAIHelpers::IsTestStrategy_*, CvMilitaryAI.cpp) strategies of Brave New World, registered with [StrategyTests].
 *  Registered elsewhere: the GS_* ones ([GrandStrategyAi]), TECH_LEADER (espionage port), TRADE_WITH_CITY_STATE and CONCERT_TOUR
 *  (great people port, which launches their operations).
 *
 *  The tests read the state [EconomicAi] and [MilitaryAi] compute before the loops of the same turn.
 */
object EconomicMilitaryStrategyTests {
    // Strategy names other tests and ports read
    const val NEED_RECON = "ECONOMICAISTRATEGY_NEED_RECON"
    const val NEED_RECON_SEA = "ECONOMICAISTRATEGY_NEED_RECON_SEA"
    const val REALLY_NEED_RECON_SEA = "ECONOMICAISTRATEGY_REALLY_NEED_RECON_SEA"
    const val EARLY_EXPANSION = "ECONOMICAISTRATEGY_EARLY_EXPANSION"
    const val ISLAND_START = "ECONOMICAISTRATEGY_ISLAND_START"
    const val LOSING_WARS = "MILITARYAISTRATEGY_LOSING_WARS"
    const val WAR_MOBILIZATION = "MILITARYAISTRATEGY_WAR_MOBILIZATION"
    /** MAX_MAJOR_CIVS of the original: the player slots its per-civ loops pass over */
    private const val MAX_MAJOR_CIVS = 22

    internal fun registerAll(tests: StrategyTests) {
        // CvEconomicAI::DoTurn
        tests.register(NEED_RECON) { civ, _ -> needRecon(civ) }
        tests.register("ECONOMICAISTRATEGY_ENOUGH_RECON") { civ, _ -> civ.ai.economic.reconState == ReconState.Enough }
        tests.register(REALLY_NEED_RECON_SEA) { civ, _ -> reallyNeedReconSea(civ) }
        tests.register(NEED_RECON_SEA) { civ, _ -> needReconSea(civ) }
        // IsTestStrategy_EnoughReconSea (Vox Populi): always while LOSING_WARS
        tests.register("ECONOMICAISTRATEGY_ENOUGH_RECON_SEA") { civ, _ ->
            civ.ai.isMilitaryStrategyActive(LOSING_WARS) || civ.ai.economic.navalReconState == ReconState.Enough }
        tests.register(EARLY_EXPANSION) { civ, _ -> earlyExpansion(civ) }
        tests.register("ECONOMICAISTRATEGY_ENOUGH_EXPANSION") { civ, _ -> enoughExpansion(civ) }
        tests.register("ECONOMICAISTRATEGY_NEED_HAPPINESS") { civ, def -> needHappiness(civ, def) }
        tests.register("ECONOMICAISTRATEGY_NEED_HAPPINESS_CRITICAL") { civ, def -> needHappinessCritical(civ, def) }
        tests.register("ECONOMICAISTRATEGY_CITIES_NEED_NAVAL_GROWTH") { civ, def ->
            citiesNeedCityStrategy(civ, def, "AICITYSTRATEGY_NEED_NAVAL_GROWTH") }
        tests.register("ECONOMICAISTRATEGY_CITIES_NEED_NAVAL_TILE_IMPROVEMENT") { civ, def ->
            citiesNeedCityStrategy(civ, def, "AICITYSTRATEGY_NEED_NAVAL_TILE_IMPROVEMENT") }
        tests.register("ECONOMICAISTRATEGY_NEED_IMPROVEMENT_FOOD") { civ, _ ->
            needImprovement(civ, Stat.Food, "AICITYSTRATEGY_NEED_IMPROVEMENT_FOOD") }
        tests.register("ECONOMICAISTRATEGY_NEED_IMPROVEMENT_PRODUCTION") { civ, _ ->
            needImprovement(civ, Stat.Production, "AICITYSTRATEGY_NEED_IMPROVEMENT_PRODUCTION") }
        tests.register("ECONOMICAISTRATEGY_ONE_OR_FEWER_COASTAL_CITIES") { civ, _ -> civ.cities.count { WaterBodies.isCoastal(it) } <= 1 }
        tests.register(EconomicAi.LOSING_MONEY) { civ, def -> losingMoney(civ, def) }
        tests.register("ECONOMICAISTRATEGY_HALT_GROWTH_BUILDINGS") { civ, _ -> haltGrowthBuildings(civ) }
        tests.register("ECONOMICAISTRATEGY_TOO_MANY_UNITS") { civ, _ -> civ.stats.getUnitSupplyDeficit() > 0 }
        tests.register(ISLAND_START) { civ, def -> islandStart(civ, def) }
        tests.register(MilitaryAi.EXPAND_TO_OTHER_CONTINENTS) { civ, _ -> expandToOtherContinents(civ) }
        tests.register("ECONOMICAISTRATEGY_MOSTLY_ON_THE_COAST") { civ, _ -> mostlyOnTheCoast(civ) }
        tests.register("ECONOMICAISTRATEGY_EXPAND_LIKE_CRAZY") { civ, def -> expandLikeCrazy(civ, def) }
        tests.register("ECONOMICAISTRATEGY_GROW_LIKE_CRAZY") { civ, def ->
            civ.ai.personalityAndGrandStrategy(Flavor.Growth) >= def.weightThreshold }
        tests.register(MilitaryAi.NAVAL_MAP) { civ, _ -> !civ.isHuman() && SettleAreas.aiMapHint(civ.gameInfo) and 1 != 0 }
        tests.register("ECONOMICAISTRATEGY_OFFSHORE_EXPANSION_MAP") { civ, _ -> !civ.isHuman() && SettleAreas.aiMapHint(civ.gameInfo) and 4 != 0 }
        tests.register("ECONOMICAISTRATEGY_DEVELOPING_RELIGION") { civ, _ -> developingReligion(civ) }
        tests.register("ECONOMICAISTRATEGY_NEED_ARCHAEOLOGISTS") { civ, _ ->
            civ.ai.economic.visibleAntiquitySites > UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.ARCHAEOLOGIST, includeBeingTrained = true) }
        tests.register("ECONOMICAISTRATEGY_ENOUGH_ARCHAEOLOGISTS") { civ, _ -> enoughArchaeologists(civ) }
        tests.register("ECONOMICAISTRATEGY_NEED_MUSEUMS") { civ, _ ->
            civ.ai.economic.visibleAntiquitySites > GreatWorkFunctions.getNumFreeSlots(civ, GreatWorkSlotType.ArtArtifact) }
        tests.register("ECONOMICAISTRATEGY_NEED_GUILDS") { civ, _ -> needGuilds(civ) }
        tests.register("ECONOMICAISTRATEGY_STARTED_PIETY") { civ, _ -> civ.policies.isAdopted(PIETY) }

        // CvMilitaryAI::UpdateMilitaryStrategies
        tests.register("MILITARYAISTRATEGY_ENOUGH_MILITARY_UNITS") { civ, _ -> enoughMilitaryUnits(civ) }
        tests.register("MILITARYAISTRATEGY_EMPIRE_DEFENSE") { civ, _ -> civ.ai.military.landDefenseState == DefenseState.Needed }
        tests.register("MILITARYAISTRATEGY_EMPIRE_DEFENSE_CRITICAL") { civ, _ -> civ.ai.military.landDefenseState == DefenseState.Critical }
        tests.register("MILITARYAISTRATEGY_ENOUGH_NAVAL_UNITS") { civ, _ -> civ.ai.military.navalDefenseState == DefenseState.Enough }
        tests.register("MILITARYAISTRATEGY_NEED_NAVAL_UNITS") { civ, _ -> civ.ai.military.navalDefenseState == DefenseState.Needed }
        tests.register("MILITARYAISTRATEGY_NEED_NAVAL_UNITS_CRITICAL") { civ, _ -> civ.ai.military.navalDefenseState == DefenseState.Critical }
        tests.register(WAR_MOBILIZATION) { civ, def -> warMobilizationWeight(civ) >= def.weightThreshold &&
            !civ.ai.isMilitaryStrategyActive(MilitaryAi.AT_WAR) }
        tests.register(MilitaryAi.AT_WAR) { civ, _ -> WarStateAi.numberCivsAtWarWith(civ) > 0 }
        tests.register("MILITARYAISTRATEGY_MINOR_CIV_GENERAL_DEFENSE") { _, _ -> true }
        tests.register("MILITARYAISTRATEGY_MINOR_CIV_THREAT_ELEVATED") { civ, _ -> civ.ai.minorCivStatus == MinorCivStatus.Elevated }
        tests.register("MILITARYAISTRATEGY_MINOR_CIV_THREAT_CRITICAL") { civ, _ -> civ.ai.minorCivStatus == MinorCivStatus.Critical }
        tests.register("MILITARYAISTRATEGY_ERADICATE_BARBARIANS") { civ, def -> eradicateBarbarians(civ, def) }
        tests.register("MILITARYAISTRATEGY_WINNING_WARS") { civ, _ -> WarStateAi.stateAllWars(civ) == StateAllWars.Winning }
        tests.register(LOSING_WARS) { civ, _ -> WarStateAi.stateAllWars(civ) == StateAllWars.Losing }
        tests.register("MILITARYAISTRATEGY_NEED_RANGED") { civ, _ ->
            needUnits(civ, Flavor.Ranged, civ.ai.military.numRangedLandUnits, civ.ai.military.numMeleeLandUnits) }
        tests.register("MILITARYAISTRATEGY_ENOUGH_RANGED") { civ, _ ->
            enoughUnits(civ, Flavor.Ranged, civ.ai.military.numRangedLandUnits, civ.ai.military.numMeleeLandUnits) }
        tests.register("MILITARYAISTRATEGY_NEED_RANGED_EARLY") { civ, _ -> civ.ai.isMilitaryStrategyActive(WAR_MOBILIZATION) }
        tests.register("MILITARYAISTRATEGY_NEED_MOBILE") { civ, _ ->
            needUnits(civ, Flavor.Mobile, civ.ai.military.numMobileLandUnits, civ.ai.military.numMeleeLandUnits) }
        tests.register("MILITARYAISTRATEGY_ENOUGH_MOBILE") { civ, _ ->
            enoughUnits(civ, Flavor.Mobile, civ.ai.military.numMobileLandUnits, civ.ai.military.numMeleeLandUnits) }
        tests.register("MILITARYAISTRATEGY_NEED_AIR") { civ, _ ->
            needUnits(civ, Flavor.Air, civ.ai.military.numAirUnits, civ.ai.military.numMeleeLandUnits) }
        tests.register("MILITARYAISTRATEGY_ENOUGH_AIR") { civ, _ ->
            enoughUnits(civ, Flavor.Air, civ.ai.military.numAirUnits, civ.ai.military.numMeleeLandUnits) }
        tests.register("MILITARYAISTRATEGY_NEED_NUKE") { civ, _ -> needANuke(civ) }
        tests.register("MILITARYAISTRATEGY_NEED_ANTIAIR") { civ, _ ->
            anyKnownAirforce(civ) && antiAirRatio(civ) <= 2 }
        tests.register("MILITARYAISTRATEGY_ENOUGH_ANTIAIR") { civ, _ ->
            !anyKnownAirforce(civ) || antiAirRatio(civ) > 2 }
        tests.register("MILITARYAISTRATEGY_NEED_AIR_CARRIER") { civ, _ -> needAirCarriers(civ) }
    }

    private const val PIETY = "Piety"
    private const val EXPLORATION_FINISHER = "Exploration Complete"
    private val GUILDS = listOf("Writers' Guild", "Artists' Guild", "Musicians' Guild")

    /** CvPlayer::IsEmpireUnhappy */
    internal fun isEmpireUnhappy(civ: Civilization) = civ.getHappiness() < 0

    /** CvPlayer::IsEmpireVeryUnhappy without VP's happiness rules: excess happiness at or below VERY_UNHAPPY_THRESHOLD */
    internal fun isEmpireVeryUnhappy(civ: Civilization) =
        civ.getHappiness() <= civ.gameInfo.ruleset.ai.define(AiDefine.VERY_UNHAPPY_THRESHOLD)

    /** CvPlayer::IsEmpireSuperUnhappy without VP's happiness rules (the empire's cities may revolt): excess happiness at or below SUPER_UNHAPPY_THRESHOLD */
    internal fun isEmpireSuperUnhappy(civ: Civilization) =
        civ.getHappiness() <= civ.gameInfo.ruleset.ai.define(AiDefine.SUPER_UNHAPPY_THRESHOLD)


    //region Economic

    /** IsTestStrategy_NeedRecon: recon NEEDED, never while AT_WAR */
    fun needRecon(civ: Civilization): Boolean =
        !civ.ai.isMilitaryStrategyActive(MilitaryAi.AT_WAR) && civ.ai.economic.reconState == ReconState.Needed

    /** IsTestStrategy_NeedReconSea: naval recon NEEDED, never while LOSING_WARS */
    fun needReconSea(civ: Civilization): Boolean =
        !civ.ai.isMilitaryStrategyActive(LOSING_WARS) && civ.ai.economic.navalReconState == ReconState.Needed

    /** Whether a unit type can cross the ocean: no permanent "Cannot enter ocean tiles" (the original's ocean-impassable promotion without a tech) */
    private fun canCrossOcean(civ: Civilization, unit: BaseUnit) = unit.getMatchingUniques(UniqueType.CannotEnterOcean, civ.state).none()

    /**
     *  IsTestStrategy_ReallyNeedReconSea: naval recon NEEDED, and - once embarked units may cross the ocean - no ocean-going sea explorer
     *  (UNITAI_EXPLORE_SEA) exists or is being built; before that, once land units can embark, no sea explorer at all.
     */
    fun reallyNeedReconSea(civ: Civilization): Boolean {
        if (civ.ai.economic.navalReconState != ReconState.Needed) return false
        val rules = civ.gameInfo.ruleset.ai
        val oceanOnly = when {
            civ.tech.embarkedUnitsCanEnterOcean -> true
            civ.tech.unitsCanEmbark -> false
            else -> return false
        }
        for (unit in civ.units.getCivUnits())
            if (UnitAiTypes.current(unit) == UnitAiType.EXPLORE_SEA && (!oceanOnly || !unit.cache.cannotEnterOceanTiles)) return false
        for (city in civ.cities) {
            val inProduction = city.cityConstructions.getCurrentConstruction() as? BaseUnit ?: continue
            if (!inProduction.isWaterUnit || UnitAiTypes.defaultOf(inProduction, rules) != UnitAiType.EXPLORE_SEA) continue
            if (!oceanOnly || canCrossOcean(civ, inProduction)) return false
        }
        return true
    }

    /**
     *  IsTestStrategy_EarlyExpansion (Vox Populi): not for an unhappy empire; always until 3 cities are founded; not once a neighbor that is
     *  stronger than average is at least next door (proximity Neighbors) nor after turn 169; else while the best settle plot
     *  ([SettleAreas.bestSettlePlot]) is within our sphere of influence - its closest city by path length is ours, or ours is at most a step
     *  farther than the closest city of another major - or more than 7 steps (6 for an expansionist, 8 for a smaller or tourism civilization)
     *  from any major's city.
     */
    fun earlyExpansion(civ: Civilization): Boolean {
        if (civ.isOneCityChallenger()) return false
        if (isEmpireUnhappy(civ)) return false
        // We do not want to lose time for building our settlers even if we have not explored yet
        if (civ.citiesCreated < 3) return true
        // Midgame depends on the map situation
        var closestNeighborProximity = -1
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || other == civ) continue
            // Continue expanding if our military is good enough
            val strength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: continue
            if (strength <= StrengthLevel.Average) continue
            closestNeighborProximity = max(closestNeighborProximity, DiplomacyTurn.proximity(civ, other))
        }
        if (closestNeighborProximity >= DiplomacyTurn.PROXIMITY_NEIGHBORS) return false
        // Never in the late game
        if (civ.gameInfo.turns > 169) return false

        val settlePlot = SettleAreas.bestSettlePlot(civ, null, null) ?: return false
        val game = civ.gameInfo
        // As long as it is within our "sphere of influence"
        val closestCity = CityDistances.closestCityByPathLength(game, settlePlot, majorsOnly = true)
        if (closestCity != null) {
            if (closestCity.civ == civ) return true
            // Check for near ties
            val theirDistance = CityDistances.closestCityDistancePathLength(game, settlePlot, majorsOnly = true)
            if (CityDistances.cityDistancePathLength(civ, settlePlot) <= theirDistance + 1) return true
        }
        // If the neighbors are far away it is also good
        var offset = 0
        if (civ.ai.hasTraitCategory(TraitCategory.Expansionist)) offset-- // smaller distance: keep expanding longer
        if (civ.ai.hasTraitCategory(TraitCategory.Smaller) || civ.ai.hasTraitCategory(TraitCategory.Tourism)) offset++ // larger distance: stop expanding earlier
        return CityDistances.closestCityDistancePathLength(game, settlePlot, majorsOnly = true) > 7 + offset
    }

    /**
     *  IsTestStrategy_EnoughExpansion (Vox Populi): always for a one-city challenger and for a civilization that may not annex cities (Venice);
     *  never with one city or less; always for a very unhappy empire and under LOSING_WARS; never while expanding to other continents or early;
     *  else when no plot is a good settle plot ([SettleAreas.haveGoodSettlePlot]). The number of settlers no longer counts here: the production
     *  AI's settler sanity and [CityStrategyAi.isEnoughSettlers] limit them.
     */
    fun enoughExpansion(civ: Civilization): Boolean {
        if (civ.isOneCityChallenger() || civ.hasUnique(UniqueType.MayNotAnnexCities)) return true
        if (civ.cities.size <= 1) return false
        if (isEmpireVeryUnhappy(civ)) return true
        if (civ.ai.isMilitaryStrategyActive(LOSING_WARS)) return true
        if (civ.ai.isEconomicStrategyActive(MilitaryAi.EXPAND_TO_OTHER_CONTINENTS)) return false
        if (civ.ai.isEconomicStrategyActive(EARLY_EXPANSION)) return false
        // Do this check last, it can be expensive
        return !SettleAreas.haveGoodSettlePlot(civ, -1)
    }

    /**
     *  CvPlayer::GetUnhappiness: the unhappiness of the city count, the citizens and the occupied cities (the negative parts of the cities'
     *  happiness lists, whose AI-handicap scaling is already in them) plus the public opinion unhappiness (scaled for an AI player), as a whole number.
     */
    internal fun unhappiness(civ: Civilization): Int {
        var fromCities = 0f
        for (city in civ.cities)
            for (source in UNHAPPINESS_SOURCES) fromCities -= city.cityStats.happinessList[source] ?: 0f
        var opinion = civ.culture.publicOpinion.unhappiness.toFloat()
        if (!civ.isHuman()) opinion *= civ.gameInfo.getDifficulty().aiUnhappinessModifier
        return fromCities.toInt() + opinion.toInt()
    }

    private val UNHAPPINESS_SOURCES = listOf("Cities", "Population", "Occupied City")

    /** IsTestStrategy_NeedHappiness: with population and some unhappiness, excess happiness <= (HAPPINESS personality x threshold mod) / weight threshold (2) */
    fun needHappiness(civ: Civilization, def: PlayerStrategyDef): Boolean {
        if (civ.cities.sumOf { it.population.population } <= 0 || unhappiness(civ) <= 0) return false
        return civ.getHappiness() <= civ.ai.weightThresholdModifier(def) / def.weightThreshold
    }

    /** IsTestStrategy_NeedHappinessCritical: not losing all wars; with population and some unhappiness, excess happiness <= the weight threshold (-3) */
    fun needHappinessCritical(civ: Civilization, def: PlayerStrategyDef): Boolean {
        if (WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return false
        if (civ.cities.sumOf { it.population.population } <= 0 || unhappiness(civ) <= 0) return false
        return civ.getHappiness() <= def.weightThreshold
    }

    /**
     *  IsTestStrategy_CitiesNeedNavalGrowth / _CitiesNeedNavalTileImprovement: more cities run [cityStrategy] than
     *  (cities - 1) x 10 / (weight threshold + personality modifier).
     */
    fun citiesNeedCityStrategy(civ: Civilization, def: PlayerStrategyDef, cityStrategy: String): Boolean {
        val numCities = civ.cities.count { it.ai.isCityStrategyActive(cityStrategy) }
        if (numCities == 0) return false
        val threshold = def.weightThreshold + civ.ai.weightThresholdModifier(def)
        if (threshold == 0) return false
        return numCities > (civ.cities.size - 1) * 10 / threshold
    }

    /**
     *  IsTestStrategy_NeedImprovement: at least AI_STRATEGY_NEED_IMPROVEMENT_CITY_RATIO of the cities run [cityStrategy], a worker exists,
     *  and no improvement whose tech we have and that we can build on one of our tiles raises [stat].
     */
    fun needImprovement(civ: Civilization, stat: Stat, cityStrategy: String): Boolean {
        if (civ.cities.isEmpty()) return false
        val concerned = civ.cities.count { it.ai.isCityStrategyActive(cityStrategy) }
        if (concerned / civ.cities.size.toDouble() < civ.gameInfo.ruleset.ai.defineFloat(AiDefine.AI_STRATEGY_NEED_IMPROVEMENT_CITY_RATIO))
            return false
        if (civ.units.getCivUnits().none { UnitAiTypes.current(it) == UnitAiType.WORKER }) return false
        for (improvement in civ.gameInfo.ruleset.tileImprovements.values) {
            val tech = improvement.techRequired
            if (tech != null && !civ.tech.isResearched(tech)) continue
            if (improvement[stat] <= 0) continue
            val canBuild = civ.cities.any { city ->
                city.getTiles().any { it.improvementFunctions.canBuildImprovement(improvement, civ.state) }
            }
            if (canBuild) return false
        }
        return true
    }

    /**
     *  IsTestStrategy_LosingMoney: after the first MinimumNumTurnsExecuted turns, CvTreasury::AverageIncome(MinimumNumTurnsExecuted)
     *  below the weight threshold (2). The original's average reads the last turn's gold change MinimumNumTurnsExecuted times (its index
     *  never moves), so it is that change: the gold per turn.
     */
    fun losingMoney(civ: Civilization, def: PlayerStrategyDef): Boolean {
        if (civ.gameInfo.turns <= def.minimumNumTurnsExecuted) return false
        return civ.stats.statsForNextTurn.gold < def.weightThreshold
    }

    /** IsTestStrategy_HaltGrowthBuildings: the next wonder city specialization wants gives a free building that yields food */
    fun haltGrowthBuildings(civ: Civilization): Boolean {
        val ruleset = civ.gameInfo.ruleset
        val wonder = civ.ai.citySpecialization.nextWonderDesired?.let { ruleset.buildings[it] } ?: return false
        return wonder.getMatchingUniques(UniqueType.GainFreeBuildings, civ.state).any { unique ->
            (ruleset.buildings[unique.params[0]]?.food ?: 0f) > 0f
        }
    }

    /**
     *  IsTestStrategy_IslandStart: before turn 25, unable to embark, more than AI_STRATEGY_ISLAND_START_COAST_REVEAL_PERCENT revealed tiles of
     *  the starting landmass per its coastal tiles (the original counts every revealed tile of the area, not only coastal ones), and the
     *  landmass smaller than the weight threshold (200 tiles). The start is the capital's tile.
     */
    fun islandStart(civ: Civilization, def: PlayerStrategyDef): Boolean {
        if (civ.gameInfo.turns >= 25 || civ.tech.unitsCanEmbark) return false
        val start = civ.getCapital(firstCityIfNoCapital = false)?.getCenterTile() ?: return false
        val area = start.getContinent()
        var coastal = 0
        var revealed = 0
        var total = 0
        for (tile in civ.gameInfo.tileMap.values) {
            if (tile.getContinent() != area) continue
            total++
            if (tile.isAdjacentToCoast()) coastal++
            if (civ.hasExplored(tile)) revealed++
        }
        return revealed * 100 / (coastal + 1) > civ.gameInfo.ruleset.ai.define(AiDefine.AI_STRATEGY_ISLAND_START_COAST_REVEAL_PERCENT)
            && total < def.weightThreshold
    }

    /**
     *  IsTestStrategy_ExpandToOtherContinents (Vox Populi): not for an unhappy empire, without a capital, before embarking, without a coastal
     *  city, under LOSING_WARS unless cramped, under ISLAND_START or EARLY_EXPANSION, nor once the cities founded reach twice the EXPANSION
     *  flavor; else when a landmass of at least 23 plots, with at least 3 plots revealed and at most a third of its usable plots
     *  claimed at 10 plots per city, has a good settle plot while the capital's landmass has none.
     *  (The original counts the plots of a landmass that are snow, ice or desert without a resource as bad plots, but never fills the count in.)
     */
    fun expandToOtherContinents(civ: Civilization): Boolean {
        if (civ.isOneCityChallenger()) return false
        if (isEmpireUnhappy(civ)) return false
        val capital = civ.getCapital(firstCityIfNoCapital = false) ?: return false
        if (!civ.tech.unitsCanEmbark) return false
        if (civ.cities.none { WaterBodies.isCoastal(it) }) return false
        if (civ.ai.isMilitaryStrategyActive(LOSING_WARS) && !civ.ai.isCramped) return false
        if (civ.ai.isEconomicStrategyActive(ISLAND_START)) return false
        // We should settle our island first
        if (civ.ai.isEconomicStrategyActive(EARLY_EXPANSION)) return false
        // Do not do it if we already have a lot of cities
        if (civ.citiesCreated >= civ.ai.personalityAndGrandStrategy(Flavor.Expansion) * 2) return false

        // Check for sparsely settled areas first
        val start = capital.getCenterTile().getContinent()
        val tiles = HashMap<Int, IntArray>() // by landmass: tiles, revealed, cities
        for (tile in civ.gameInfo.tileMap.tileList) {
            val continent = tile.getContinent()
            if (continent == -1 || tile.isWater) continue
            val counts = tiles.getOrPut(continent) { IntArray(3) }
            counts[0]++
            if (civ.hasExplored(tile)) counts[1]++
            if (tile.isCityCenter()) counts[2]++
        }
        for ((area, counts) in tiles) {
            // Make up an arbitrary threshold for islands
            if (counts[0] < 23) continue
            // Have to know about it
            if (counts[1] < 3) continue
            // Assuming 10 tiles per city (some may be water) at most 1/3 of the good plots should be claimed
            if (counts[2] * 10 > counts[0] / 3) continue
            // Do this last, potentially expensive. Note this does not guarantee that our settler will actually go there
            if (SettleAreas.haveGoodSettlePlot(civ, area) && !SettleAreas.haveGoodSettlePlot(civ, start)) return true
        }
        return false
    }

    /** IsTestStrategy_MostlyOnTheCoast: population of cities on a body of water of at least 100 tiles (no lakes or small inland seas) at least that of the others, and some */
    fun mostlyOnTheCoast(civ: Civilization): Boolean {
        var coastal = 0
        var inland = 0
        for (city in civ.cities) {
            if (WaterBodies.isCoastal(city, 100)) coastal += city.population.population
            else inland += city.population.population
        }
        return coastal > 0 && coastal >= inland
    }

    /** IsTestStrategy_ExpandLikeCrazy: EXPANSION >= the weight threshold (10), never under the culture grand strategy when cities raise policy costs */
    fun expandLikeCrazy(civ: Civilization, def: PlayerStrategyDef): Boolean {
        if (civ.isOneCityChallenger()) return false
        if (civ.ai.isGoingForCultureVictory() &&
            civ.gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().policyCostPerCityModifier > 0) return false
        return civ.ai.personalityAndGrandStrategy(Flavor.Expansion) >= def.weightThreshold
    }

    /** IsTestStrategy_DevelopingReligion: AI players that founded a religion, or have a pantheon while religions can still be founded */
    fun developingReligion(civ: Civilization): Boolean {
        if (civ.isHuman()) return false
        val religion = civ.religionManager
        if (religion.religionState >= ReligionState.Religion) return true
        return religion.religionState >= ReligionState.Pantheon && religion.remainingFoundableReligions() > 0
    }

    /** IsTestStrategy_EnoughArchaeologists: archaeologists > 0.5 x the antiquity sites of the world (+ hidden ones with the Exploration finisher) + 1 */
    fun enoughArchaeologists(civ: Civilization): Boolean {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val countHidden = civ.policies.isAdopted(EXPLORATION_FINISHER)
        var sites = 0
        for (tile in civ.gameInfo.tileMap.values) {
            val resource = tile.resource ?: continue
            if (resource == constants.archaeologySiteResource || countHidden && resource == constants.archaeologyHiddenSiteResource) sites++
        }
        val archaeologists = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.ARCHAEOLOGIST, includeBeingTrained = true)
        return sites * 0.5 + 1 < archaeologists
    }

    /** IsTestStrategy_NeedGuilds: a guild whose tech we have that no city of ours has built */
    fun needGuilds(civ: Civilization): Boolean {
        for (name in GUILDS) {
            val guild = civ.gameInfo.ruleset.buildings[name]?.let { civ.getEquivalentBuilding(it) } ?: continue
            if (!guild.requiredTechs().all { civ.tech.isResearched(it) }) continue
            if (civ.cities.none { it.cityConstructions.isBuilt(guild.name) }) return true
        }
        return false
    }
    //endregion

    //region Military

    /** IsTestStrategy_EnoughMilitaryUnits (Vox Populi): land defense ENOUGH, never while going for conquest without losing money */
    fun enoughMilitaryUnits(civ: Civilization): Boolean {
        val inDeficit = civ.ai.isEconomicStrategyActive(EconomicAi.LOSING_MONEY)
        if (!inDeficit && civ.ai.isGoingForWorldConquest()) return false
        return civ.ai.military.landDefenseState == DefenseState.Enough
    }

    /**
     *  The weight of IsTestStrategy_WarMobilization: 25 under conquest; per other major +100 if preparing war on it, else +50 if our
     *  approach is WAR, HOSTILE or AFRAID, else +25 if GUARDED or DECEPTIVE. (AT_WAR blocks the strategy, see [registerAll].) Once
     *  [MilitaryAi.armyTypeBeingBuilt] is set (never cleared) every pass of the original's loop over its [MAX_MAJOR_CIVS] player slots, empty
     *  slots and our own included, adds 100 whatever the other civ is: 2200, far above the threshold.
     */
    fun warMobilizationWeight(civ: Civilization): Int {
        var weight = if (civ.ai.isGoingForWorldConquest()) 25 else 0
        if (civ.ai.military.armyTypeBeingBuilt != null) return weight + 100 * MAX_MAJOR_CIVS
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv) continue
            if (civ.ai.diplomacy.warGoal(other) == WarGoal.Prepare) {
                weight += 100
                continue
            }
            weight += when (civ.ai.diplomacy.approachTowards(other, hideTrueFeelings = false)) {
                MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Afraid -> 50
                MajorCivApproach.Guarded, MajorCivApproach.Deceptive -> 25
                else -> 0
            }
        }
        return weight
    }

    /**
     *  IsTestStrategy_EradicateBarbarians. At war: the original's test `!GetStateAllWars() == STATE_ALL_WARS_WINNING` is true when the state
     *  is NEUTRAL, so it gives up then (winning or losing it goes on). Always when barbarians hold a settler or worker of ours. Weight =
     *  camps x 50 + visible barbarians x 25 against the weight threshold plus the personality modifier: at twice that with barbarians
     *  visible, always; at once, unless preparing war on a major. BNW also kept it on below the threshold while a barbarian-camp operation
     *  ran; there are none since Vox Populi's UpdateOperations.
     */
    fun eradicateBarbarians(civ: Civilization, def: PlayerStrategyDef): Boolean {
        if (civ.ai.isMilitaryStrategyActive(MilitaryAi.AT_WAR) && WarStateAi.stateAllWars(civ) == StateAllWars.Neutral) return false
        val barbarians = civ.gameInfo.civilizations.firstOrNull { it.isBarbarian }
        if (barbarians != null) for (unit in barbarians.units.getCivUnits()) {
            if (unit.originalOwner != civ.civID) continue
            val type = UnitAiTypes.defaultOf(unit.baseUnit, civ.gameInfo.ruleset.ai)
            if (type == UnitAiType.SETTLE || type == UnitAiType.WORKER) return true
        }
        val military = civ.ai.military
        val weight = military.barbarianCampCount * 50 + military.visibleBarbarianCount * 25
        val threshold = def.weightThreshold + civ.ai.weightThresholdModifier(def)
        if (weight >= threshold * 2 && military.visibleBarbarianCount > 0) return true
        if (weight >= threshold)
            return civ.gameInfo.civilizations.none { it != civ && it.isMajorCiv && civ.ai.diplomacy.warGoal(it) == WarGoal.Prepare }
        return false
    }

    /** IsTestStrategy_Need(Ranged|Mobile|Air)Units: units x 10 / max(1, melee + units) <= flavor (personality and grand strategy) / 2 */
    fun needUnits(civ: Civilization, flavor: Flavor, units: Int, melee: Int): Boolean =
        units * 10 / max(1, melee + units) <= civ.ai.personalityAndGrandStrategy(flavor) / 2

    /** IsTestStrategy_Enough(Ranged|Mobile|Air)Units: units x 10 / max(1, melee + units) >= flavor (personality and grand strategy) */
    fun enoughUnits(civ: Civilization, flavor: Flavor, units: Int, melee: Int): Boolean =
        units * 10 / max(1, melee + units) >= civ.ai.personalityAndGrandStrategy(flavor)

    /** IsTestStrategy_NeedANuke: nukes allowed and fewer nuclear weapons than NUKE / 3 */
    fun needANuke(civ: Civilization): Boolean {
        if (!civ.gameInfo.gameParameters.nuclearWeaponsEnabled) return false
        return civ.units.getCivUnits().count { it.isNuclearWeapon() } < civ.ai.personalityAndGrandStrategy(Flavor.Nuke) / 3
    }

    /** Whether a valid major has an air force (CvMilitaryAI::HasAirforce), for the anti-air strategies */
    fun anyKnownAirforce(civ: Civilization): Boolean = civ.gameInfo.civilizations.any { other ->
        other != civ && other.isMajorCiv && civ.ai.diplomacy.isPlayerValid(other) && other.ai.military.also { it.ensureData(other) }.hasAirforce()
    }

    /** The anti-air ratio of IsTestStrategy_(Need|Enough)AntiAirUnits: anti-air x 10 / max(1, melee + anti-air) */
    private fun antiAirRatio(civ: Civilization): Int {
        val military = civ.ai.military
        return military.numAntiAirUnits * 10 / max(1, military.numMeleeLandUnits + military.numAntiAirUnits)
    }

    /**
     *  IsTestStrategy_NeedAirCarriers: more aircraft that a carrier can take than cargo space on our carriers (units not exploring).
     *  Carriers are units with "Can carry [n] [filter] units"; an aircraft can be taken if it matches the filter of such a unit of the ruleset.
     */
    fun needAirCarriers(civ: Civilization): Boolean {
        val ruleset = civ.gameInfo.ruleset
        val carriedFilters = ruleset.units.values.flatMap { unit ->
            unit.getMatchingUniques(UniqueType.CarryAirUnits).map { it.params[1] }.toList()
        }.toSet()
        if (carriedFilters.isEmpty()) return false
        var loadable = 0
        var cargoSpace = 0
        for (unit in civ.units.getCivUnits()) {
            val type = UnitAiTypes.current(unit)
            if (type == UnitAiType.EXPLORE || type == UnitAiType.EXPLORE_SEA) continue
            val carries = unit.getMatchingUniques(UniqueType.CarryAirUnits).toList()
            if (carries.isNotEmpty()) cargoSpace += carries.sumOf { it.params[0].toInt() }
            else if (unit.baseUnit.isAirUnit() && carriedFilters.any { unit.matchesFilter(it) }) loadable++
        }
        return loadable > cargoSpace
    }
    //endregion
}
