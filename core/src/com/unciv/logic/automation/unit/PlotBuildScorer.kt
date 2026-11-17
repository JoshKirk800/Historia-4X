package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.max
import kotlin.math.min

/**
 *  CvHomelandAI's SBuilderState: what the assignment pass has planned so far (builds, features and extra resources of plots), which the
 *  scoring takes into account. Empty until the assignment pass plans for several builders at once ([BuilderTaskingAi] plans one turn's directives
 *  and every builder picks on its own).
 */
class SBuilderState private constructor() {
    companion object {
        val Default = SBuilderState()
    }
}

/**
 *  CvBuilderTaskingAI::ScorePlotBuild and its helpers (Vox Populi): what a build on a tile is worth to [civ], as a [PlotBuildScore].
 *  One instance per civilization and turn (held by [BuilderTaskingAi]); it caches the tile yields it computes.
 *
 *  Ported terms of ScorePlotBuild, in the original's order:
 *  - Not building (a KEEP directive) has an inherent value of 100. A plot of a city that is being razed scores -1, so does a plot we do not
 *    own (the original scores building outside our borders as worth nothing unless the improvement takes the tiles: NewOwner is none).
 *  - A chop that helps a rush adds the feature's production × the production price / 50.
 *  - The yield loop: for each yield of the tile (food, production, gold, science, culture, faith) the yield the plot has with the build (what it
 *    has now without one), ×100, price per yield (BUILDER_TASKING_BASELINE_ADDS_*) and the city's puppet modifier. The yields that techs and
 *    policies will add to the improvement ([FutureYields], CvBuilderTaskingAI::GetFutureYields) go the same way into the potential score. Gold
 *    is smoothed for golden ages (the tile stats already add 1 gold of a golden age on a tile with gold): +40 when no golden age is on, -60 in
 *    one. Only for a plot that
 *    one of our cities can work.
 *  - Connecting a resource: its [resourceWeight] to the base score, ⅔ of it (⅓ with exactly one available) moved to the potential score when
 *    the resource is already available more than once (once); resources the old improvement connects do not count as available.
 *  - A plot on a planned shortcut or capital connection route ([PlannedRoutes.bestRouteTypeAndValue], [PlannedRoutes.isRoutePlanned]) costs the
 *    improvement 100 (a Great Person's 1000 to build; none to keep or repair), the road taking its place: the original's penalty for an improvement
 *    that gains nothing from roads, which is all of ours (its village improvements are none).
 *  - Not on an antiquity site except the dig.
 *  - The unused yield: when the plot's simplified yield value is not better than the worst worked plot of every city that can work it, the
 *    yield score goes to [PlotBuildScore.unusedYieldScore].
 *
 *  Not ported (none-inputs, the rules BNW lacks or later branches): the tile claim and the culture bomb (NewOwner), tile theft and
 *  BlockTileSteal, Great Person rate (GreatPersonRateModifier), domain production and
 *  experience, the planned state of other builds ([SBuilderState]) and potential adjacent improvements (YieldPerXAdjacent*, adjacency yields),
 *  WLTKD and golden age improvement yields, resource monopolies, defense, combat bonus and canal terms,
 *  improvement maintenance (0 in the rules), spawned resources, IMPROVEMENTS_EXTENSIONS and the vanilla Celts block (inactive in Vox Populi).
 *
 *  Unciv mapping: plot yields are Unciv tile stats truncated to integers (a copy of the tile with the build applied gives the projected
 *  yields); the effective owning city is the tile's city; a razing city is [City.isBeingRazed]; a plot in work range is in [City.tilesInRange] of one of
 *  our cities; the puppet modifier is [com.unciv.logic.city.CityStats.getStatPercentBonusesFromPuppetCity] (the original's 30% puppet
 *  multiplier is Vox Populi's own: ours is 100%).
 */
class PlotBuildScorer(val civ: Civilization, val routes: PlannedRoutes = PlannedRoutes(civ)) {
    private val ruleset = civ.gameInfo.ruleset
    private val rules = ruleset.ai

    /** Current yields per plot this turn (UpdateCurrentPlotYields) */
    private val currentYieldCache = HashMap<Tile, IntArray>()
    /** Projected yields per plot and build this turn (UpdateProjectedPlotYields) */
    private val projectedYieldCache = HashMap<Pair<Tile, String>, IntArray>()
    /** Copies of plots with their top feature removed, to test and project builds that need the feature gone */
    private val withoutFeatureCache = HashMap<Tile, Tile>()
    /** m_worstCityPlotValues: the simplified yield value of the worst plot each city works */
    private val worstCityPlotValues = HashMap<City, Int>()

    /** m_futureYieldBonuses, filled by [BuilderTaskingAi] every turn (empty: no future yields) */
    val futureYields = FutureYields(civ)

    /** GetYieldBaseModifierTimes100: the price of one point of [stat] (the original's yields beyond culture are zero in the rules) */
    fun yieldBaseModifierTimes100(stat: Stat): Int = when (stat) {
        Stat.Food -> rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_FOOD)
        Stat.Production -> rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_PRODUCTION)
        Stat.Gold -> rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_GOLD)
        Stat.Science -> rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_SCIENCE)
        Stat.Culture -> rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_CULTURE)
        Stat.Faith -> rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_FAITH)
        else -> 0
    }

    /**
     *  GetYieldCityModifierTimes100: only the puppet part is counted, "most other things are too dynamic": 100 plus the puppet's percent
     *  modifier of the yield when it is negative. The multiplicative part (the original's PUPPET_YIELD_AND_SUPPLY_MODIFIER_MULTIPLICATIVE) is none.
     */
    fun yieldCityModifierTimes100(city: City, stat: Stat): Int {
        val puppet = city.cityStats.getStatPercentBonusesFromPuppetCity() ?: return 100
        return 100 + min(0, puppet[stat].toInt())
    }

    /** CvPlot::isPlayerCityRadius: within the work range of one of our cities */
    fun isWithinWorkRange(tile: Tile) = civ.cities.any { tile in it.tilesInRange }

    /** CvPlot::getResourceType(team): the resource if [civ] can see it */
    fun visibleResource(tile: Tile) = tile.tileResource?.takeIf { civ.canSeeResource(it) }

    /**
     *  ScorePlotBuild: [improvement] is the improvement the build creates (the one a repair repairs, the one that stays for no build), [build] the
     *  build or null for "keep what is there". A plot we cannot or should not build on scores -1; callers drop a directive whose
     *  [PlotBuildScore.maxScore] is not above 0.
     */
    @Suppress("UNUSED_PARAMETER")
    fun scorePlotBuild(tile: Tile, improvement: TileImprovement?, build: Build?, sState: SBuilderState = SBuilderState.Default): PlotBuildScore {
        var plotImprovement = improvement
        val isBuild = build != null
        if (plotImprovement == null && tile.improvementIsPillaged && build?.isRepair == true) plotImprovement = tile.tileImprovement

        val resource = visibleResource(tile)
        val feature = tile.terrainFeatures.lastOrNull()

        var yieldScore = 0
        var secondaryScore = 0
        var potentialScore = 0

        // Not building anything has some inherent value
        if (!isBuild && plotImprovement != null) secondaryScore += 100

        val owningCity = tile.getCity()
        if (owningCity != null && owningCity.isBeingRazed) return PlotBuildScore(-1)
        // Assume building outside our borders is not worth it
        if (tile.getOwner() != civ) return PlotBuildScore(-1)

        val isWithinWorkRange = isWithinWorkRange(tile)

        // Give a bonus for chopping
        if (feature != null && build?.removeFirst != null && doesBuildHelpRush(tile, build)) {
            val productionFromChop = chopProduction(tile, build)
            if (productionFromChop > 0) secondaryScore += productionFromChop * yieldBaseModifierTimes100(Stat.Production) / 50
        }

        var extraResource = 0
        val connectsResource = resource != null && plotImprovement != null && resource.isImprovedBy(plotImprovement.name)

        val yields = if (!isWithinWorkRange) null else if (build != null) projectedYields(tile, build, owningCity) else currentYields(tile, owningCity)
        for ((i, stat) in YIELDS.withIndex()) {
            if (yields == null) break
            var newYieldTimes100 = 100 * yields[i]
            var futureYieldTimes100 = 0

            // Normalize Golden Age gold yields (assume active 40% of the time)
            if (stat == Stat.Gold && newYieldTimes100 >= GOLDEN_AGE_GOLD_THRESHOLD * 100) {
                newYieldTimes100 += if (!civ.goldenAges.isGoldenAge()) 40 * GOLDEN_AGE_GOLD_YIELD else -60 * GOLDEN_AGE_GOLD_YIELD
            }

            // Bonus yields from future techs/policies
            if (plotImprovement != null) futureYieldTimes100 += futureYields[plotImprovement, stat]

            if (newYieldTimes100 != 0 || futureYieldTimes100 != 0) {
                val cityYieldModifier = owningCity?.let { yieldCityModifierTimes100(it, stat) } ?: 100
                yieldScore += newYieldTimes100 * yieldBaseModifierTimes100(stat) * cityYieldModifier / 10000
                potentialScore += futureYieldTimes100 * yieldBaseModifierTimes100(stat) * cityYieldModifier / 10000
            }
        }

        // Improvement connects the resource
        if (connectsResource && resource != null && plotImprovement != null) {
            val resourceAmount = tile.resourceAmount
            val resourceWeight = resourceWeight(civ, resource, resourceAmount)

            // If the old improvement connected the same resource, subtract the resource from this improvement
            val oldImprovement = if (!tile.improvementIsPillaged) tile.tileImprovement else null
            if (oldImprovement != null && resource.isImprovedBy(oldImprovement.name)) extraResource -= tile.resourceAmount

            val numResourceAvailable = civ.getResourceAmount(resource) + extraResource
            secondaryScore += resourceWeight

            // Give a priority reduction if we have this resource already: plenty to spare ⅔, one already ⅓
            val downPriority = when {
                numResourceAvailable > 1 -> resourceWeight * 2 / 3
                numResourceAvailable > 0 -> resourceWeight / 3
                else -> 0
            }
            if (downPriority > 0) {
                secondaryScore -= downPriority
                potentialScore += downPriority
            }
        }

        // Do we have or will we build a road here? Not for a plot that is only on a strategic route
        val routeNeeded = routes.bestRouteTypeAndValue(tile)?.first ?: RoadStatus.None
        val forceCityConnection =
            if (routeNeeded != RoadStatus.None && (routes.isRoutePlanned(tile, routeNeeded, RoutePurpose.Shortcut)
                    || routes.isRoutePlanned(tile, routeNeeded, RoutePurpose.ConnectCapital))) routeNeeded
            else RoadStatus.None
        if (forceCityConnection != RoadStatus.None) {
            // Improvements gain nothing from a road here (no improvement of the rules does): the road takes the plot's place. No penalty for
            // keeping or repairing a Great Person's improvement, a heavy one for building it
            secondaryScore -= if (plotImprovement?.isGreatImprovement() == true) (if (!isBuild || build.isRepair) 0 else GREAT_IMPROVEMENT_ROAD_PENALTY)
                else ROAD_PENALTY
        }

        // Don't build on antiquity sites
        if (resource != null && build != null && blockedByAntiquitySite(tile, resource.name, build)) return PlotBuildScore(-1)

        var yieldUnused = false
        if (build != null && isWithinWorkRange && owningCity != null) {
            val simplifiedYieldValue = plotYieldValueSimplified(tile, build)
            var worstWorkedValue = Int.MAX_VALUE
            for (city in civ.cities) {
                if (city != owningCity && city.isPuppet) continue
                if (tile !in city.tilesInRange) continue
                worstWorkedValue = min(worstWorkedValue, worstWorkedPlotValue(city))
            }
            if (simplifiedYieldValue <= worstWorkedValue) yieldUnused = true
        }

        return PlotBuildScore(yieldScore + secondaryScore, potentialScore, if (yieldUnused) yieldScore else 0)
    }

    /** The tile has an antiquity site we can dig, and [build] is not the dig */
    private fun blockedByAntiquitySite(tile: Tile, resourceName: String, build: Build): Boolean {
        val constants = ruleset.modOptions.constants
        if (resourceName != constants.archaeologySiteResource && resourceName != constants.archaeologyHiddenSiteResource) return false
        val context = GameContext(civ, tile = tile)
        return ruleset.tileResources[resourceName]!!.getImprovements().any { name ->
            val dig = ruleset.tileImprovements[name]
            dig != null && dig != build.improvement && tile.improvementFunctions.canBuildImprovement(dig, context)
        }
    }

    //region Yields

    /** GetPlotYieldValueSimplified: the plot's yields with the [build] (without one: now) at the price of each yield */
    fun plotYieldValueSimplified(tile: Tile, build: Build?): Int {
        val city = tile.getCity()
        val yields = if (build == null) currentYields(tile, city) else projectedYields(tile, build, city)
        var value = 0
        for ((i, stat) in YIELDS.withIndex()) value += yieldBaseModifierTimes100(stat) * yields[i]
        return value
    }

    /** UpdateAllCityWorstPlots: for every city of ours, the simplified value of the worst plot it works (a city plot excluded) */
    fun updateAllCityWorstPlots() {
        worstCityPlotValues.clear()
        for (city in civ.cities) {
            var worstValue = Int.MAX_VALUE
            for (tile in city.tilesInRange) {
                if (tile.isCityCenter() || !city.isWorked(tile)) continue
                worstValue = min(worstValue, plotYieldValueSimplified(tile, null))
            }
            worstCityPlotValues[city] = worstValue
        }
    }

    /** GetWorstWorkedPlotValue */
    fun worstWorkedPlotValue(city: City) = worstCityPlotValues[city] ?: Int.MAX_VALUE

    /** CvPlot::getYield (each of [YIELDS]), cached for the turn */
    fun currentYields(tile: Tile, city: City?): IntArray = currentYieldCache.getOrPut(tile) {
        yieldsOf(tile.stats.getTileStats(city, civ))
    }

    /** CvPlot::getYieldWithBuild (each at least 0), cached for the turn */
    fun projectedYields(tile: Tile, build: Build, city: City?): IntArray = projectedYieldCache.getOrPut(tile to build.improvement.name) {
        val alternative = if (build.removeFirst != null) withoutFeature(tile).clone(addUnits = false) else tile.clone(addUnits = false)
        alternative.setTerrainTransients()
        if (build.isRepair) {
            alternative.improvementIsPillaged = false
            alternative.roadIsPillaged = false
        } else if (!build.isRemoval) {
            alternative.setImprovement(build.improvement.name)
        }
        yieldsOf(alternative.stats.getTileStats(city, civ)).also { for (i in it.indices) it[i] = max(it[i], 0) }
    }

    /** A copy of [tile] without its top terrain feature */
    fun withoutFeature(tile: Tile): Tile = withoutFeatureCache.getOrPut(tile) {
        val copy = tile.clone(addUnits = false)
        copy.setTerrainTransients()
        copy.terrainFeatures.lastOrNull()?.let { copy.removeTerrainFeature(it) }
        copy
    }

    private fun yieldsOf(stats: Stats) = IntArray(YIELDS.size) { stats[YIELDS[it]].toInt() }

    //endregion
    //region Chop

    /** CvPlot::getFeatureProduction for Unciv's chop (TileImprovementFunctions): the production the removal gives the closest city */
    fun chopProduction(tile: Tile, build: Build): Int {
        val removal = build.removeFirst ?: build.improvement.takeIf { build.isRemoval } ?: return 0
        val feature = removal.name.removePrefix(Constants.remove)
        val terrain = ruleset.terrains[feature] ?: return 0
        val city = closestCityForChop(tile) ?: return 0
        val distance = city.getCenterTile().aerialDistanceTo(tile)
        var production = 0f
        for (unique in terrain.getMatchingUniques(UniqueType.ProductionBonusWhenRemoved, GameContext(civ, tile = tile))) {
            var stats = unique.stats
            if (unique.isModifiedByGameSpeed()) stats = stats * civ.gameInfo.speed.modifier
            if (unique.isModifiedByGameProgress()) stats = stats * unique.getGameProgressModifier(civ)
            production += stats.production
        }
        if (distance != 1) production *= (6 - distance) / 4f
        if (tile.getCity()?.civ != civ) production *= 2 / 3f
        return production.toInt()
    }

    private fun closestCityForChop(tile: Tile): City? {
        val city = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return null
        return city.takeIf { it.getCenterTile().aerialDistanceTo(tile) <= 5 }
    }

    /**
     *  CvBuilderTaskingAI::DoesBuildHelpRush: the build removes a feature that gives production to a city whose current order is rushed
     *  (an AI city's non-perpetual construction needing more than AI_ATTEMPT_RUSH_OVER_X_TURNS_TO_BUILD turns, which is when the original's
     *  CvCityStrategyAI::ChooseProduction sets the order's rush flag; ×MINOR_CIV_PRODUCTION_PERCENT for city-states). Chop production is Unciv's
     *  (closest city within 5 tiles).
     */
    fun doesBuildHelpRush(tile: Tile, build: Build): Boolean {
        if (chopProduction(tile, build) <= 0) return false
        val city = closestCityForChop(tile) ?: return false
        if (city.civ.isHuman()) return false
        val construction = city.cityConstructions.currentConstructionName()
        if (construction.isEmpty() || construction in PerpetualConstruction.perpetualConstructionsMap) return false
        var rushOver = rules.define(AiDefine.AI_ATTEMPT_RUSH_OVER_X_TURNS_TO_BUILD)
        if (civ.isCityState) rushOver = rushOver * rules.define(AiDefine.MINOR_CIV_PRODUCTION_PERCENT) / 100
        return city.cityConstructions.turnsToConstruction(construction) > rushOver
    }

    //endregion

    companion object {
        /** The original's yields that a tile has, in YieldTypes order */
        val YIELDS = arrayOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)

        /** The original's Yields.GoldenAgeYieldThreshold and GoldenAgeYield of gold (the other yields have none); ours is TileStatFunctions' golden age +1 gold */
        private const val GOLDEN_AGE_GOLD_THRESHOLD = 1
        private const val GOLDEN_AGE_GOLD_YIELD = 1

        /** The score an improvement loses on a plot of a planned route (ScorePlotBuild, MOD_BALANCE_VP): 100, a Great Person's improvement 1000 to build */
        private const val ROAD_PENALTY = 100
        private const val GREAT_IMPROVEMENT_ROAD_PENALTY = 1000
    }
}
