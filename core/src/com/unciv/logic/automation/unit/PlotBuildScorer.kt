package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.civilization.CityStateAi
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.TheftAi
import com.unciv.logic.automation.diplomacy.TheftType
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.max
import kotlin.math.min

/**
 *  CvHomelandAI.h: SBuilderState: what the assignment pass ([BuilderAssignment]) has planned so far, which the scoring takes into account:
 *  [changedPlotImprovements] is mChangedPlotImprovements: the improvement planned for a plot (null: none, the improvement the plot has
 *  counts) and [extraResources] is mExtraResources: the resources (by name) the planned builds will connect (+) or disconnect (-).
 *  mChangedPlotFeatures is none: what reads it is Vox Populi's adjacent feature yields and the vanilla Celts' forests. The pass fills the
 *  maps; [Default] is the empty state of the scoring outside the pass and is never filled.
 */
class SBuilderState(changed: Map<Tile, TileImprovement?> = emptyMap(), extra: Map<String, Int> = emptyMap()) {
    val changedPlotImprovements: MutableMap<Tile, TileImprovement?> = HashMap(changed)
    val extraResources: MutableMap<String, Int> = HashMap(extra)

    /** CvBuilderTaskingAI.cpp: GetPlannedImprovementInPlot: the improvement planned for [tile], else the one it has unless it is pillaged */
    fun plannedImprovement(tile: Tile): TileImprovement? =
        if (tile in changedPlotImprovements) changedPlotImprovements[tile]
        else if (tile.improvementIsPillaged) null else tile.tileImprovement

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
 *    own (the original scores building outside our borders as worth nothing unless the improvement takes the tiles: NewOwner is none; that
 *    return comes before the defense and culture bomb terms, so a Fort or a Citadel outside our borders is never worth building).
 *  - A Great Person's improvement that is not a culture bomb loses 1000, 500, 100 or 40 by how likely a neighbour is to take the plot
 *    ([PlotDefense.theftLikelihood]; none for Impossible).
 *  - A chop that helps a rush adds the feature's production × the production price / 50.
 *  - The yield loop: for each yield of the tile (food, production, gold, science, culture, faith) the yield the plot has with the build (what it
 *    has now without one), ×100, price per yield (BUILDER_TASKING_BASELINE_ADDS_*) and the city's puppet modifier. The yields that techs and
 *    policies will add to the improvement ([FutureYields], CvBuilderTaskingAI::GetFutureYields) go the same way into the potential score. Gold
 *    is smoothed for golden ages (the tile stats already add 1 gold of a golden age on a tile with gold): +40 when no golden age is on, -60 in
 *    one. Only for a plot that one of our cities can work.
 *  - Connecting a resource: its [resourceWeight] to the base score, ⅔ of it (⅓ with exactly one available) moved to the potential score when
 *    the resource is already available more than once (once); resources the old improvement connects do not count as available, those the
 *    assignment pass plans to connect do ([SBuilderState.extraResources]).
 *  - Adjacency (CvBuilderTaskingAI::ScorePlotBuild's planned adjacency, with the adjacency rules that name an improvement: BNW's
 *    ImprovementStatsForAdjacencies, the Moai's culture, and Vox Populi's YieldPerXAdjacentImprovement, [UniqueType.ImprovementStatsPerAdjacent], 1/n of the stats for
 *    each adjacent one): the improvement's own yield changes by the planned improvements of its neighbours that differ from what
 *    they have; the neighbours' planned improvements that gain from this one add their yield to the secondary score (worked plot) or to the
 *    potential score (not worked); the best improvement still to be planned beside it that would give or take such a yield counts ⅔ of it in the
 *    potential score ([potentialAdjacentImprovements]).
 *  - A plot on a planned shortcut or capital connection route ([PlannedRoutes.bestRouteTypeAndValue], [PlannedRoutes.isRoutePlanned]) costs the
 *    improvement 100 (a Great Person's 1000 to build; none to keep or repair), the road taking its place: the original's penalty for an improvement
 *    that gains nothing from roads, which is all of ours (its village improvements are none).
 *  - Not on an antiquity site except the dig.
 *  - An improvement with a defensive bonus or that hurts adjacent enemy units adds [PlotDefense.defenseBuildValue] where a neighbour threatens;
 *    a Fort where none does has its yield score halved (it looks ugly when spammed).
 *  - The nation's combat bonus improvement (Polynesia's Moai, [PlotDefense.combatBonusImprovement]) adds twice its bonus for each plot of ours
 *    within its range that no improvement of that kind within range covers yet (counted in [SBuilderState.plannedImprovement]).
 *  - A culture bomb that is built (a Citadel) loses 2000 if it is a Great Person's and adds for every plot it would take from somebody within its
 *    radius: 10, + 50 for a luxury or strategic resource we see (+ 50 more when somebody owns the plot), and for a plot of somebody + the
 *    defensive bonus of its improvement, + 50 for a Great Person's, + the plot's yields; × 3 for a city-state, else × 8 / 4 / 1 by our opinion of
 *    the owner (worse than neutral / neutral / better), × 10. A major civilization's builder gets -10000 for taking from a bad target
 *    ([TheftAi.isBadTheftTarget], culture bomb); a city-state's for taking from its friend, protector or ally.
 *  - The unused yield: when the plot's simplified yield value is not better than the worst worked plot of every city that can work it, the
 *    yield score goes to [PlotBuildScore.unusedYieldScore].
 *
 *  Not ported (none-inputs, the rules BNW lacks or later branches): the tile claim (NewOwner), BlockTileSteal (a theft bonus for an improvement
 *  that blocks tile theft), Great Person rate (GreatPersonRateModifier), domain production and experience, the planned features of
 *  [SBuilderState] (mChangedPlotFeatures), the adjacent terrain, feature and resource yields (YieldPerXAdjacentTerrain, AdjacentTerrain/Feature/ResourceYieldChanges: they do not change with what is planned, and the tile stats of a plot hold them), WLTKD and
 *  golden age improvement yields, resource monopolies, the canal term (MakesPassable), CvPlot::IsChokePoint, CvPlot::IsStealBlockedByImprovement
 *  and embassy improvements,
 *  improvement maintenance (0 in the rules), spawned resources, IMPROVEMENTS_EXTENSIONS and the vanilla Celts block (inactive in Vox Populi).
 *  The culture bomb walks the plots in Unciv's order (the original's ring table decides which plots come before the first plot of somebody else,
 *  from which on a plot nobody owns counts), and skips city centers and the plots the improvement's filter does not take (Unciv's take-over).
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
    /** What an improvement's uniques say about culture bomb and defense, read once per improvement */
    private val cultureBombRadiusCache = HashMap<TileImprovement, Int>()
    private val defensiveCache = HashMap<TileImprovement, Boolean>()

    /** m_futureYieldBonuses, filled by [BuilderTaskingAi] every turn (empty: no future yields) */
    val futureYields = FutureYields(civ)

    /** CvPlayerTraits' combat bonus improvement (Polynesia's Moai), null for a nation without one */
    private val combatBonusImprovement = PlotDefense.combatBonusImprovement(civ)

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

        // A Great Person's improvement that is not a culture bomb is worth less where a neighbour is likely to steal the plot
        val cultureBombRadius = plotImprovement?.let { cultureBombRadiusCache.getOrPut(it) { PlotDefense.cultureBombRadius(it) } } ?: 0
        if (plotImprovement?.isGreatImprovement() == true && cultureBombRadius == 0)
            secondaryScore -= when (PlotDefense.theftLikelihood(civ, tile)) {
                TheftLikelihood.Certain -> 1000
                TheftLikelihood.Likely -> 500
                TheftLikelihood.Possible -> 100
                TheftLikelihood.Unlikely -> 40
                TheftLikelihood.Impossible -> 0
            }

        val isWithinWorkRange = isWithinWorkRange(tile)

        // Give a bonus for chopping
        if (feature != null && build?.removeFirst != null && doesBuildHelpRush(tile, build)) {
            val productionFromChop = chopProduction(tile, build)
            if (productionFromChop > 0) secondaryScore += productionFromChop * yieldBaseModifierTimes100(Stat.Production) / 50
        }

        val connectsResource = resource != null && plotImprovement != null && resource.isImprovedBy(plotImprovement.name)
        // What the planned builds of other builders will connect
        var extraResource = if (connectsResource) resource?.let { sState.extraResources[it.name] } ?: 0 else 0

        val yields = if (!isWithinWorkRange) null else if (build != null) projectedYields(tile, build, owningCity) else currentYields(tile, owningCity)

        // The adjacency of improvements (the Moai's culture): what the planned improvements of the neighbours change
        val ownBonuses = if (plotImprovement != null) adjacencyBonuses(plotImprovement) else emptyList()
        val users = if (plotImprovement != null) adjacencyUsers[plotImprovement.name].orEmpty() else emptyList()
        val plannedOwn = if (yields != null && ownBonuses.isNotEmpty()) plannedOwnAdjacency(tile, ownBonuses, sState) else null
        val neighbourGain = if (users.isNotEmpty()) neighbourAdjacencyGain(tile, users, sState) else null
        val potentialAdjacency =
            if (plotImprovement != null && isWithinWorkRange && (ownBonuses.isNotEmpty() || users.isNotEmpty())) potentialAdjacency(tile, plotImprovement, ownBonuses, users, sState)
            else null

        for ((i, stat) in YIELDS.withIndex()) {
            var newYieldTimes100 = 0
            var futureYieldTimes100 = 0
            val price = yieldBaseModifierTimes100(stat)
            fun cityYieldModifier() = owningCity?.let { yieldCityModifierTimes100(it, stat) } ?: 100

            if (yields != null) {
                newYieldTimes100 = 100 * (yields[i] + (plannedOwn?.get(i) ?: 0))

                // Normalize Golden Age gold yields (assume active 40% of the time)
                if (stat == Stat.Gold && newYieldTimes100 >= GOLDEN_AGE_GOLD_THRESHOLD * 100) {
                    newYieldTimes100 += if (!civ.goldenAges.isGoldenAge()) 40 * GOLDEN_AGE_GOLD_YIELD else -60 * GOLDEN_AGE_GOLD_YIELD
                }

                // Bonus yields from future techs/policies
                if (plotImprovement != null) futureYieldTimes100 += futureYields[plotImprovement, stat]
            }

            // Bonus yields that this improvement provides to adjacent improvements
            if (neighbourGain != null) {
                secondaryScore += neighbourGain.first[i]
                potentialScore += neighbourGain.second[i]
            }

            if (newYieldTimes100 != 0 || futureYieldTimes100 != 0) {
                yieldScore += newYieldTimes100 * price * cityYieldModifier() / 10000
                potentialScore += futureYieldTimes100 * price * cityYieldModifier() / 10000
            }

            // Extra adjacency bonuses from potential adjacent improvements
            if (potentialAdjacency != null) {
                var potentialNewYieldTimes100 = 100 * potentialAdjacency.thisPlot[i]
                // Yield was below the threshold, but including potential adjacent improvements we get above the threshold
                if (stat == Stat.Gold && newYieldTimes100 + potentialNewYieldTimes100 >= GOLDEN_AGE_GOLD_THRESHOLD * 100 && newYieldTimes100 < GOLDEN_AGE_GOLD_THRESHOLD * 100)
                    potentialNewYieldTimes100 += 40 * GOLDEN_AGE_GOLD_YIELD
                potentialNewYieldTimes100 += potentialAdjacency.adjacentPlots[i]
                // Assume 2/3 of potential will happen (divide by 15000 instead of 10000)
                if (potentialNewYieldTimes100 != 0) potentialScore += potentialNewYieldTimes100 * price * cityYieldModifier() / 15000
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

        // Is this a good spot for a defensive building?
        if (plotImprovement != null && defensiveCache.getOrPut(plotImprovement) { PlotDefense.isDefensive(plotImprovement) }) {
            val defenseBuildValue = PlotDefense.defenseBuildValue(civ, tile, build, plotImprovement, sState)
            if (defenseBuildValue > 0) secondaryScore += defenseBuildValue
            // De-emphasize yields for forts if we're not close to the border (they look ugly when spammed)
            else if (plotImprovement.name == FORT) yieldScore /= 2
        }

        // How many tiles will be covered by an encampment bonus (or similar)
        val combatBonus = combatBonusImprovement
        if (combatBonus != null && combatBonus.improvement == plotImprovement) secondaryScore += combatBonusScore(tile, combatBonus, sState)

        if (plotImprovement != null && cultureBombRadius > 0 && isBuild) secondaryScore += cultureBombScore(tile, plotImprovement, cultureBombRadius)

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

    /**
     *  The combat bonus term of ScorePlotBuild: every plot of ours within the bonus range of [tile] that no improvement of the kind (planned or
     *  built, on a plot of ours) within the range of that plot covers yet is worth twice the [CombatBonusImprovement.bonus] (20 bonus on the 18 plots
     *  of two rings gives 720).
     */
    private fun combatBonusScore(tile: Tile, combatBonus: CombatBonusImprovement, sState: SBuilderState): Int {
        var score = 0
        for (covered in tile.getTilesInDistance(combatBonus.range)) {
            if (covered.getOwner() != civ) continue
            // Check if any adjacent tile covers this one already
            val alreadyCovered = covered.getTilesInDistance(combatBonus.range).any {
                it.getOwner() == civ && sState.plannedImprovement(it) == combatBonus.improvement
            }
            if (!alreadyCovered) score += combatBonus.bonus * 2
        }
        return score
    }

    /**
     *  The culture bomb term of ScorePlotBuild: what [improvement] (a culture bomb of [radius]) built on [tile] is worth by what it takes from
     *  others, to add to the secondary score. A Great Person's improvement starts at -2000 ("culture bombs need to be quite good to be worth it").
     *  For each plot it takes that is not ours, passable and not a city center: 10, + 50 for a luxury or strategic resource we see (+ 50 more
     *  when somebody owns it), and when somebody owns it (then the bomb steals enemy land): + the plot improvement's defensive bonus, + 50 for a Great Person's,
     *  + the plot's yields; × 3 for a city-state, else × 8 / 4 / 1 by our opinion of the owner (worse than neutral / neutral / better), × 10. A plot
     *  nobody owns adds that only after a plot of somebody has been seen. A plot whose owner is a bad target of a major [civ]'s culture bomb
     *  ([TheftAi.isBadTheftTarget]), or that a city-state's friend, protector or ally owns when [civ] is a city-state, makes the whole term -10000.
     */
    private fun cultureBombScore(tile: Tile, improvement: TileImprovement, radius: Int): Int {
        var secondaryScore = 0
        var stealScore = 0
        var stealsEnemyLand = false
        val bombs = improvement.getMatchingUniques(UniqueType.OneTimeTakeOverTilesInRadius).toList()

        if (improvement.isGreatImprovement()) secondaryScore -= 2000

        for (ring in 0..radius) for (adjacent in tile.getTilesAtDistance(ring)) {
            val owner = adjacent.getOwner()
            if (owner == civ) continue
            if (adjacent.isCityCenter() || bombs.none { ring <= it.params[1].toInt() && adjacent.matchesFilter(it.params[0]) }) continue

            if (civ.isMajorCiv && owner != null && TheftAi.isBadTheftTarget(civ, owner, TheftType.CultureBomb)) {
                stealScore = -10000
                break
            } else if (civ.isCityState && owner != null) {
                if (CityStateAi.isFriends(civ, owner) || CityStateAi.isProtectedBy(civ, owner) || civ.allyCiv == owner) {
                    stealScore = -10000
                    break
                }
            }
            if (adjacent.isImpassible()) continue

            var multiplier = 1
            var tempScore = 10

            // There's a resource on the plot
            val resourceType = visibleResource(adjacent)?.resourceType
            if (resourceType == ResourceType.Strategic || resourceType == ResourceType.Luxury) {
                tempScore += 50
                if (owner != null) tempScore += 50
            }

            // These only apply when we're stealing from other players
            if (owner != null) {
                stealsEnemyLand = true

                // For major civs (and barbarians), it depends on how much we hate them
                if (owner.isCityState) multiplier = 3
                else {
                    val opinion = civ.ai.diplomacy.opinionOf(owner)
                    if (opinion < MajorCivOpinion.Neutral) multiplier = 8
                    else if (opinion == MajorCivOpinion.Neutral) multiplier = 4
                }

                // We're stealing a defensive plot or Great Person improvement
                val adjacentImprovement = adjacent.tileImprovement
                if (adjacentImprovement != null) {
                    if (adjacentImprovement.isGreatImprovement()) tempScore += 50
                    tempScore += PlotDefense.defenseModifier(adjacentImprovement)
                }

                // Finally we tie break with yields (only a rough count)
                val stats = adjacent.stats.getTileStats(null)
                for (stat in YIELDS) tempScore += stats[stat].toInt()
            }

            if (tempScore != 0 && multiplier != 0 && stealsEnemyLand) stealScore += tempScore * multiplier * 10
        }

        if (stealScore != 0) secondaryScore += stealScore
        return secondaryScore
    }

    //region Adjacency of improvements

    /** An ImprovementStatsForAdjacencies unique that names an improvement: [stats] for each adjacent [improvement] (the original's YieldPerXAdjacentImprovement) */
    private class AdjacencyBonus(val improvement: String, val stats: Stats)

    /** What [potentialAdjacency] adds, by yield (in [YIELDS] order): to this plot, and to the plots next to it (hundredths) */
    private class PotentialAdjacency(val thisPlot: IntArray, val adjacentPlots: IntArray)

    private val adjacencyBonusCache = HashMap<TileImprovement, List<AdjacencyBonus>>()

    /** The whole yield of a sum of fractions, cut off as the engine does ([com.unciv.logic.map.tile.TileStatFunctions]: the epsilon is for 1/3 x 3 being 0.99999994 as a float) */
    private fun wholeYield(value: Float): Int = (if (value >= 0f) value + 1e-4f else value - 1e-4f).toInt()

    /** The adjacency bonuses [improvement] gets from other improvements (the Moai's culture for each adjacent Moai) */
    private fun adjacencyBonuses(improvement: TileImprovement): List<AdjacencyBonus> = adjacencyBonusCache.getOrPut(improvement) {
        // YieldPerXAdjacentImprovement (V:CvBuilderTaskingAI.cpp:3612-3616, 3744-3750) is `[stats] from every [n] adjacent [improvement]`: each adjacent one gives 1/n of the stats.
        // The same unique with a terrain or feature as its filter does not depend on what is planned, and is in the stats of the plot already
        improvement.getMatchingUniques(UniqueType.ImprovementStatsForAdjacencies, GameContext.IgnoreConditionals)
            .filter { it.params[1] in ruleset.tileImprovements }.map { AdjacencyBonus(it.params[1], it.stats) }.toList() +
            improvement.getMatchingUniques(UniqueType.ImprovementStatsPerAdjacent, GameContext.IgnoreConditionals)
                .filter { it.params[2] in ruleset.tileImprovements }.map { AdjacencyBonus(it.params[2], it.stats.times(1f / it.params[1].toInt())) }.toList()
    }

    /** For each improvement name, the improvements that gain from an adjacent one of that name, with what they gain */
    private val adjacencyUsers: Map<String, List<Pair<TileImprovement, Stats>>> by lazy {
        val users = HashMap<String, MutableList<Pair<TileImprovement, Stats>>>()
        for (improvement in ruleset.tileImprovements.values) for (bonus in adjacencyBonuses(improvement))
            users.getOrPut(bonus.improvement) { ArrayList() }.add(improvement to bonus.stats)
        users
    }

    private fun bonusFrom(bonuses: List<AdjacencyBonus>, improvement: String, stat: Stat): Float =
        bonuses.sumOf { if (it.improvement == improvement) it.stats[stat].toDouble() else 0.0 }.toFloat()

    /**
     *  The planned adjacency in the yield loop of ScorePlotBuild: what the neighbours' improvements the assignment pass has planned give or take
     *  from [tile]'s yields compared with those they have now (which the tile's own yields already count), by yield; null if nothing differs.
     */
    private fun plannedOwnAdjacency(tile: Tile, bonuses: List<AdjacencyBonus>, sState: SBuilderState): IntArray? {
        if (sState.changedPlotImprovements.isEmpty()) return null
        var delta: FloatArray? = null
        for (neighbour in tile.neighbors) {
            if (neighbour !in sState.changedPlotImprovements) continue
            val planned = sState.changedPlotImprovements[neighbour]?.name
            val actual = if (neighbour.improvementIsPillaged) null else neighbour.improvement
            for (bonus in bonuses) {
                val change = (if (planned == bonus.improvement) 1 else 0) - (if (actual == bonus.improvement) 1 else 0)
                if (change == 0) continue
                val sum = delta ?: FloatArray(YIELDS.size).also { delta = it }
                for ((i, stat) in YIELDS.withIndex()) sum[i] += bonus.stats[stat] * change
            }
        }
        return delta?.let { sum -> IntArray(YIELDS.size) { wholeYield(sum[it]) } }
    }

    /**
     *  "Bonus yields that this improvement provides to adjacent improvements" of ScorePlotBuild: for each neighbour of ours that one of our cities works
     *  or can work, whose planned improvement ([users]: the improvements that gain from this one) gains yield from this one: that yield at the price
     *  of the yield in the secondary score when the plot is worked, in the potential score when it is not. By yield, null if there is none.
     */
    private fun neighbourAdjacencyGain(tile: Tile, users: List<Pair<TileImprovement, Stats>>, sState: SBuilderState): Pair<IntArray, IntArray>? {
        var result: Pair<IntArray, IntArray>? = null
        for (neighbour in tile.neighbors) {
            if (neighbour.isCityCenter() || neighbour.getOwner() != civ || !isWithinWorkRange(neighbour)) continue
            val city = neighbour.getCity()?.takeUnless { it.isBeingRazed } ?: continue
            val planned = sState.plannedImprovement(neighbour) ?: continue
            val gains = users.filter { it.first == planned }
            if (gains.isEmpty()) continue
            val worked = city.isWorked(neighbour)
            for ((i, stat) in YIELDS.withIndex()) {
                val delta = wholeYield(gains.sumOf { it.second[stat].toDouble() }.toFloat())
                if (delta == 0) continue
                val value = delta * yieldBaseModifierTimes100(stat) * yieldCityModifierTimes100(city, stat) / 100
                val sums = result ?: Pair(IntArray(YIELDS.size), IntArray(YIELDS.size)).also { result = it }
                if (worked) sums.first[i] += value else sums.second[i] += value
            }
        }
        return result
    }

    /**
     *  "Calculate the best potential adjacent improvements" and "Extra adjacency bonuses from potential adjacent improvements" of ScorePlotBuild: on
     *  every neighbour with nothing planned the improvement that would give (or gain) the most yield next to [improvement] counts as built. The
     *  yield this plot would gain from those, and what they would gain from this one, go to the potential score. The neighbour must be a plot of ours
     *  or nobody's that one of our cities can work, no city, and the improvement one a builder can build there now (the original lets the next
     *  era's techs count too, and ignores Great People's improvements).
     */
    private fun potentialAdjacency(
        tile: Tile, improvement: TileImprovement, ownBonuses: List<AdjacencyBonus>, users: List<Pair<TileImprovement, Stats>>, sState: SBuilderState
    ): PotentialAdjacency {
        val candidates = LinkedHashMap<String, TileImprovement>()
        for (bonus in ownBonuses) ruleset.tileImprovements[bonus.improvement]?.let { candidates[it.name] = it }
        for ((user, _) in users) candidates[user.name] = user

        val current = FloatArray(YIELDS.size)
        val potential = FloatArray(YIELDS.size)
        val toAdjacent = FloatArray(YIELDS.size)
        for (neighbour in tile.neighbors) {
            val planned = sState.plannedImprovement(neighbour)
            if (planned != null) {
                for ((i, stat) in YIELDS.withIndex()) current[i] += bonusFrom(ownBonuses, planned.name, stat)
                continue
            }
            val best = bestPotentialImprovement(neighbour, improvement, ownBonuses, candidates.values) ?: continue
            val bestBonuses = adjacencyBonuses(best)
            for ((i, stat) in YIELDS.withIndex()) {
                potential[i] += bonusFrom(ownBonuses, best.name, stat)
                toAdjacent[i] += bonusFrom(bestBonuses, improvement.name, stat)
            }
        }
        return PotentialAdjacency(
            IntArray(YIELDS.size) { (potential[it] + current[it]).toInt() - current[it].toInt() },
            IntArray(YIELDS.size) { (toAdjacent[it] * 100).toInt() }
        )
    }

    /** The improvement of [candidates] with the best adjacency score next to [improvement] that a builder can build on [neighbour], null if none scores above 0 */
    private fun bestPotentialImprovement(
        neighbour: Tile, improvement: TileImprovement, ownBonuses: List<AdjacencyBonus>, candidates: Collection<TileImprovement>
    ): TileImprovement? {
        if (neighbour.isCityCenter() || !isWithinWorkRange(neighbour)) return null
        val owner = neighbour.getOwner()
        if (owner != null && owner != civ) return null
        val city = neighbour.getCity()
        var best: TileImprovement? = null
        var bestScore = 0
        for (other in candidates) {
            if (other.isGreatImprovement()) continue
            if (!neighbour.improvementFunctions.canBuildImprovement(other, GameContext(civ, tile = neighbour))) continue
            val otherBonuses = adjacencyBonuses(other)
            var score = 0
            for (stat in YIELDS) {
                val modifier = yieldBaseModifierTimes100(stat) * (city?.let { yieldCityModifierTimes100(it, stat) } ?: 100)
                score += (bonusFrom(ownBonuses, other.name, stat) * modifier / 100f).toInt()
                score += (bonusFrom(otherBonuses, improvement.name, stat) * modifier / 100f).toInt()
            }
            if (score > bestScore) {
                bestScore = score
                best = other
            }
        }
        return best
    }

    //endregion

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

        /** The improvement whose yields ScorePlotBuild halves where it defends nothing (the original: a type starting with IMPROVEMENT_FORT) */
        private const val FORT = "Fort"
    }
}
