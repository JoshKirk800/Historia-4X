package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.DealResourceValues
import com.unciv.logic.automation.civilization.PlotShape
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import kotlin.math.max
import kotlin.math.min

/**
 *  Vox Populi's CvSiteEvaluatorForSettler (CvSiteEvaluationClasses.cpp, Release-5.4.8): the value of founding a city on a plot for one
 *  player, which [SettleAreas] caches per turn (CvPlayer::updatePlotFoundValues) and CvPlayer::GetBestSettlePlot searches.
 *  One instance is one evaluation pass (ComputeFlavorMultipliers, then PlotFoundValue for many plots): it caches the per-tile parts that do not
 *  depend on the evaluated plot. Build a new instance whenever the player, its flavors, the cities or the map may have changed.
 *
 *  Unciv equivalents of the original's inputs: a landmass is a continent id ([Tile.getContinent]); the yields of a plot are the terrain stats
 *  ([natureYield], CvPlot::calculateNatureYield); coastal land (CvPlot::isCoastalLand, MIN_WATER_SIZE_FOR_OCEAN) is [WaterBodies.isCoastalLand];
 *  a river plot is [Tile.isAdjacentToRiver]; NUM_CITY_RINGS is the ruleset's city work range, clamped to 2..5 as the original does.
 *
 *  Deviations: Unciv's hills are a base terrain, so "a hill on grass, plains or tundra without a feature" (+1 production) is a hill without a
 *  feature, whatever the climate under it; a plot's river is the one of CvPlot::isRiver by adjacency; "the way a neighbor's city is found by
 *  its outward direction" ([neighborOutward]) takes the neighbor of the plot that is farthest from the capital instead of the original's
 *  six-way direction bucket; the Inca hills ([INCA_MULTIPLIER]) are the hills next to mountains where the original's Pata-Pata (Terrace farm in Civ V)
 *  stands on mountains themselves, and mountains count for them as the original's [MOUNTAIN_MULTIPLIER]; the strategic monopolies of
 *  Vox Populi's tradeable resource value (BALANCE_RESOURCE_MONOPOLIES) and the Persian-style "rivers are roads" trait have no counterpart in the
 *  ruleset; the logging flag bCoastOnly of the original (set only while it writes its debug file) is not modelled.
 */
class CitySiteEvaluator(private val civ: Civilization, private val ignorePlots: BooleanArray? = null) {
    companion object {
        // Indexes of the flavor multipliers: the original's YieldTypes order, then SITE_EVALUATION_HAPPINESS/RESOURCES/STRATEGIC
        const val FOOD = 0
        const val PRODUCTION = 1
        const val GOLD = 2
        const val SCIENCE = 3
        const val FAITH = 4
        const val HAPPINESS = 5
        const val RESOURCES = 6
        const val STRATEGIC = 7
        private const val FACTORS = 8

        /** The civilization-specific fertility boosts (the constructor of CvSiteEvaluatorForSettler) */
        private const val BRAZIL_MULTIPLIER = 1000
        private const val SPAIN_MULTIPLIER = 5000
        private const val CELTIC_MULTIPLIER = 2000
        private const val MOROCCO_MULTIPLIER = 1000
        private const val FRANCE_MULTIPLIER = 1000
        private const val NETHERLANDS_MULTIPLIER = 2000
        private const val MOUNTAIN_MULTIPLIER = 1000
        private const val INCA_MULTIPLIER = 100

        /** The original's FEATURE_MARSH (the Netherlands' polder sites) */
        private const val MARSH = "Marsh"

        /** The plots around a site: all rings up to the largest work range, 5 */
        private const val MAX_PLOTS = 91

        private const val UNSET = Int.MIN_VALUE

        /** A slightly negative base value for a plot, to discourage settling in bad lands (iDefaultPlotValue) */
        private const val DEFAULT_PLOT_VALUE = -100

        /** The adjustment of the neighbor's strength, by StrengthLevel (strengthModifiers of PlotFoundValue) */
        private val STRENGTH_MODIFIERS = intArrayOf(100, 100, 50, 0, -50, -100, -100)

        /** The adjustment of the personality's boldness 1..10 (BoldnessModifiers of PlotFoundValue) */
        private val BOLDNESS_MODIFIERS = intArrayOf(-30, -25, -20, -10, 0, 10, 20, 25, 30, 30)

        /**
         *  CvSiteEvaluatorForSettler::ComputeFlavorMultipliers: each factor starts at 1; food adds the personality's GROWTH and EXPANSION,
         *  gold GOLD and TILE_IMPROVEMENT, production PRODUCTION and WONDER, science twice SCIENCE, happiness twice HAPPINESS, faith twice
         *  RELIGION (the original keeps a city specialization entry here that it never fills: no specialization); tradeable resources and strategic
         *  value are 10; then the vector is normalized to a sum of 100 (L1) with rounding, `int(0.5f + m / sum * 100)`.
         */
        fun computeFlavorMultipliers(civ: Civilization): IntArray {
            val raw = IntArray(FACTORS) { 1 }
            fun flavor(flavor: Flavor) = civ.ai.personality(flavor)
            raw[FOOD] += flavor(Flavor.Growth) + flavor(Flavor.Expansion)
            raw[GOLD] += flavor(Flavor.Gold) + flavor(Flavor.TileImprovement)
            raw[PRODUCTION] += flavor(Flavor.Production) + flavor(Flavor.Wonder)
            raw[SCIENCE] += flavor(Flavor.Science) * 2
            raw[HAPPINESS] += flavor(Flavor.Happiness) * 2
            raw[FAITH] += flavor(Flavor.Religion) * 2
            for (i in raw.indices) if (raw[i] < 0) raw[i] = 0
            raw[RESOURCES] = 10
            raw[STRATEGIC] = 10
            var sum = 0f
            for (value in raw) sum += value
            return IntArray(FACTORS) { (0.5f + raw[it] / sum * 100).toInt() }
        }

        /** CvCivilizationInfo::isCoastalCiv (Civilization_Start_Along_Ocean); a ruleset without that data: a "Coast" start bias */
        fun isCoastalCiv(civ: Civilization): Boolean {
            val coastal = civ.gameInfo.ruleset.ai.coastalCivilizations
            return if (coastal.isNotEmpty()) civ.civName in coastal
            else "Coast" in civ.nation.getStartBias(civ.gameInfo.ruleset)
        }
    }

    private val gameInfo = civ.gameInfo
    private val ruleset = gameInfo.ruleset
    private val rules = ruleset.ai
    private val tileMap = gameInfo.tileMap
    private val modConstants = ruleset.modOptions.constants
    /** The rings counted: max(2, min(5, CvPlayer::getWorkPlotDistance)) */
    private val range = max(2, min(5, modConstants.cityWorkRange))
    private val tileCount = tileMap.tileList.size

    val flavorMultipliers = computeFlavorMultipliers(civ)
    private val ringModifier = intArrayOf(1, rules.define(AiDefine.CITY_RING_1_MULTIPLIER), rules.define(AiDefine.CITY_RING_2_MULTIPLIER),
        rules.define(AiDefine.CITY_RING_3_MULTIPLIER), rules.define(AiDefine.CITY_RING_4_MULTIPLIER), rules.define(AiDefine.CITY_RING_5_MULTIPLIER))
    private val foodMultiplier = rules.define(AiDefine.SETTLER_FOOD_MULTIPLIER)
    private val happinessMultiplier = rules.define(AiDefine.SETTLER_HAPPINESS_MULTIPLIER)
    private val resourceMultiplier = rules.define(AiDefine.SETTLER_RESOURCE_MULTIPLIER)
    private val strategicMultiplier = rules.define(AiDefine.SETTLER_STRATEGIC_MULTIPLIER)
    private val chokepointValue = rules.define(AiDefine.CHOKEPOINT_STRATEGIC_VALUE)
    private val hillValue = rules.define(AiDefine.HILL_STRATEGIC_VALUE)
    private val badTilesValue = rules.define(AiDefine.BALANCE_BAD_TILES_STRATEGIC_VALUE)
    private val freshWaterValue = rules.define(AiDefine.BALANCE_FRESH_WATER_STRATEGIC_VALUE)
    private val coastValue = rules.define(AiDefine.BALANCE_COAST_STRATEGIC_VALUE)
    private val borderlandValue = rules.define(AiDefine.BALANCE_EMPIRE_BORDERLAND_STRATEGIC_VALUE)
    private val borderlandRange = rules.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
    private val buildOnRiverPercent = rules.define(AiDefine.BUILD_ON_RIVER_PERCENT)
    private val buildOnCoastPercent = rules.define(AiDefine.SETTLER_BUILD_ON_COAST_PERCENT)
    private val sweetSpotBase = rules.define(AiDefine.SETTLER_DISTANCE_DROPOFF_MODIFIER)
    /** The settler multipliers per yield, in [FOOD]..[FAITH] order */
    private val yieldMultipliers = intArrayOf(foodMultiplier, rules.define(AiDefine.SETTLER_PRODUCTION_MULTIPLIER),
        rules.define(AiDefine.SETTLER_GOLD_MULTIPLIER), rules.define(AiDefine.SETTLER_SCIENCE_MULTIPLIER), rules.define(AiDefine.SETTLER_FAITH_MULTIPLIER))

    private val growthFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Growth)
    private val expansionFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Expansion)
    private val navalFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Naval)
    private val boldness = civ.ai.diplomacyValue(DiplomacyPersonality.Boldness)
    private val isCoastalCiv = isCoastalCiv(civ)
    private val ownedResources = civ.getCivResourcesByName()
    private val capital = civ.getCapital()
    private val minDistance = modConstants.minimalCityDistance
    private val waterBodies = WaterBodies.of(civ)
    private val waterAreas by lazy { PlotShape.WaterAreas(tileMap) }
    private val maps = CityDistances.of(gameInfo)

    // Civilization-specific site preferences (the "custom code for UIs" of PlotFoundValue)
    /** CvPlayerTraits::IsFaithFromUnimprovedForest (Celts): faith from unimproved forests next to the city */
    private val isCeltic = civ.getMatchingUniques(UniqueType.StatsPerCity, GameContext.IgnoreConditionals).any { unique ->
        unique.stats.faith > 0 && unique.modifiers.any {
            it.type == UniqueType.ConditionalNeighborTiles && "Forest" in it.params[2] && "unimproved" in it.params[2]
        }
    }
    /** CvPlayerTraits::GetNaturalWonderYieldModifier() > 0 (Spain) */
    private val isSpain = civ.getMatchingUniques(UniqueType.AllStatsPercentFromObject, GameContext.IgnoreConditionals)
        .any { it.params[1] == "Natural Wonder" && it.params[0].toInt() > 0 }
    private val isBrazil = isUniqueImprovementOwner("Brazilwood Camp")
    private val isMorocco = isUniqueImprovementOwner("Kasbah")
    private val isNetherlands = isUniqueImprovementOwner("Polder")
    private val isInca = isUniqueImprovementOwner("Pata-Pata") || isUniqueImprovementOwner("Terrace farm") // the other base rulesets keep Civ V's name
    private val isFrance = isUniqueImprovementOwner("Chateau")

    /** The original looks these up by the civilization an improvement requires */
    private fun isUniqueImprovementOwner(improvement: String) = ruleset.tileImprovements[improvement]?.uniqueTo == civ.civName

    /** Per tile: the ring-independent yield values times the settler multipliers, [UNSET] until computed */
    private val foodCache = IntArray(tileCount) { UNSET }
    private val productionCache = IntArray(tileCount) { UNSET }
    private val otherYieldCache = IntArray(tileCount) { UNSET }
    /** Per tile: the happiness and tradeable resource values of a tile nobody owns, already halved where one of our cities is within 3 */
    private val unownedCache = IntArray(tileCount) { UNSET }
    /** Per tile: the strategic value that depends on the tile alone (ice, fresh water and coast within 3 of a city), before the flavor */
    private val tileStrategicCache = IntArray(tileCount) { UNSET }

    /** Per tile: true if a city stands within the minimum city distance (CvCitySiteEvaluator::CanFoundCity's MIN_CITY_RANGE test) */
    private val blockedByCity = BooleanArray(tileCount).also { blocked ->
        for (otherCiv in gameInfo.civilizations) for (city in otherCiv.cities) {
            val center = city.getCenterTile()
            val sameLandmass = center.getContinent()
            val reach = max(modConstants.minimalCityDistance, modConstants.minimalCityDistanceOnDifferentContinents)
            center.forEachTileInDistance(reach) { tile ->
                val distance = tile.aerialDistanceTo(center)
                if (distance <= modConstants.minimalCityDistanceOnDifferentContinents
                    || distance <= modConstants.minimalCityDistance && tile.getContinent() == sameLandmass)
                    blocked[tile.zeroBasedIndex] = true
            }
        }
    }

    /** Per tile: within [SettleAreas.PROMISED_NO_SETTLE_RANGE] of a city of a civilization we promised not to settle near (CvPlot::IsNoSettling) */
    private val promisedNotToSettle = BooleanArray(tileCount).also { blocked ->
        if (!civ.isMajorCiv) return@also
        for (other in civ.getKnownCivs()) {
            if (!other.isMajorCiv || civ.isAtWarWith(other) || !SettleAreas.hasPromisedNotToSettleNear(civ, other)) continue
            for (city in other.cities) city.getCenterTile().forEachTileInDistance(SettleAreas.PROMISED_NO_SETTLE_RANGE) { blocked[it.zeroBasedIndex] = true }
        }
    }

    /** The resources of the map by name: how many plots hold each */
    private val antiquityResources = setOf(modConstants.archaeologySiteResource, modConstants.archaeologyHiddenSiteResource)

    // State of the plot being evaluated, read by [visit]
    private var distance = 0
    private var plotCount = 0
    private val plotScores = IntArray(MAX_PLOTS)
    private var foodPlots = 0
    private var hammerPlots = 0
    private var goodPlots = 0
    private var totalFood = 0
    private var totalProduction = 0
    private var almostCoast = false
    private var waterPlots = 0
    private var celticForests = 0
    private var forests = 0
    private var jungles = 0
    private var wetlands = 0
    private var naturalWonders = 0
    private var deserts = 0
    private var mountains = 0
    private var luxuries = 0
    private var strategics = 0
    private var bonuses = 0
    private var incaBonus = 0
    private var centerCoastal = false
    private var center: Tile? = null
    private val visitor: (Tile) -> Unit = { visit(it) }

    /**
     *  CvCitySiteEvaluator::CanFoundCity (the part Unciv has the data for): land, passable, not a natural wonder, not owned by another player
     *  and not next to the territory of another player (nobody here settles with the border-bomb permission), not within the no-settle range
     *  of a city of a civilization we promised ([SettleAreas.hasPromisedNotToSettleNear]), and no city within the minimum city distance.
     */
    fun canFound(tile: Tile): Boolean {
        if (tile.isWater || tile.isImpassible() || tile.isNaturalWonder()) return false
        val owner = tile.getOwner()
        if (owner != null && owner != civ) return false
        if (tile.neighbors.any { val neighborOwner = it.getOwner(); neighborOwner != null && neighborOwner != civ }) return false
        if (promisedNotToSettle[tile.zeroBasedIndex]) return false
        return !blockedByCity[tile.zeroBasedIndex]
    }

    /**
     *  CvSiteEvaluatorForSettler::PlotFoundValue: -1 where no city can be founded (or on an antiquity site, or too near the edge of the map),
     *  0 where the plots around are too poor, else the value. The plots within the work range are scored by ring (CITY_RING_n_MULTIPLIER) from
     *  their yields, flavor-weighted (SETTLER_<YIELD>_MULTIPLIER), a slightly negative base of -100 for a plot worth nothing, and for the plots
     *  nobody owns, happiness and tradeable resources (halved where we have a city within 3) and strategic value; a plot another city
     *  is within 2 of counts one ring unit, a plot a city works or is next to none, a natural wonder 10 times, an owned plot half. The best 80% of the
     *  scores are summed. At least 5 food plots and 4 hammer plots within 2, and two plots of one kind of resource (or two natural wonders or
     *  special features) are required. Then the civilization-specific bonuses; for the city plot itself the river (BUILD_ON_RIVER_PERCENT),
     *  the coast when not too exposed and for a coast-loving civilization (SETTLER_BUILD_ON_COAST_PERCENT), unbalanced yields, almost-coast
     *  sites, chokepoints and water-area separators (CHOKEPOINT_STRATEGIC_VALUE, each); and for a civilization with a city: the neighbor's strength and our
     *  approach and opinion of it (BALANCE_EMPIRE_BORDERLAND_STRATEGIC_VALUE, boldness), a site too close to our own cities and the sweet spot of
     *  our own spacing (SETTLER_DISTANCE_DROPOFF_MODIFIER, shifted by GROWTH and EXPANSION).
     */
    fun plotFoundValue(tile: Tile): Int {
        if (!canFound(tile)) return -1
        // To avoid a bug, the AI does not settle on antiquity sites
        if (tile.resource != null && tile.resource in antiquityResources) return -1

        distance = 0
        plotCount = 0
        foodPlots = 0; hammerPlots = 0; goodPlots = 0
        totalFood = 0; totalProduction = 0
        almostCoast = false; waterPlots = 0
        celticForests = 0; forests = 0; jungles = 0; wetlands = 0; naturalWonders = 0; deserts = 0; mountains = 0
        luxuries = 0; strategics = 0; bonuses = 0
        incaBonus = 0
        center = tile
        centerCoastal = waterBodies.isCoastalLand(tile)
        // The AI never settles at the edge of the map
        if (tile.neighbors.count() < 6) return -1
        for (d in 0..range) {
            distance = d
            tileMap.forEachTileAtDistance(tile.position, d, visitor)
        }

        // Take into account only the best 80% of the plots: in the near term the city will not work all of them anyway
        java.util.Arrays.sort(plotScores, 0, plotCount)
        var totalPlotValue = 0
        for (index in plotCount * 20 / 100 until plotCount) totalPlotValue += plotScores[index]

        // Hard cutoffs
        if (totalPlotValue < 0) return 0
        if (foodPlots < 5) return 0
        if (hammerPlots < 4) return 0
        if (luxuries < 2 && strategics < 2 && bonuses < 2 && goodPlots < 2) return 0

        var civModifier = incaBonus
        if (isCeltic) civModifier += celticForests * CELTIC_MULTIPLIER
        else if (isSpain) civModifier += naturalWonders * SPAIN_MULTIPLIER
        if (isBrazil) civModifier += jungles * BRAZIL_MULTIPLIER
        if (isMorocco) civModifier += deserts * MOROCCO_MULTIPLIER
        if (isFrance) civModifier += luxuries * FRANCE_MULTIPLIER
        if (isNetherlands) civModifier += wetlands * NETHERLANDS_MULTIPLIER
        if (isInca) civModifier += mountains * MOUNTAIN_MULTIPLIER

        var valueModifier = 0
        var strategicModifier = 0

        if (totalProduction > 4 * totalFood) valueModifier -= 20 * totalPlotValue / 100
        // Food has a higher weight than production anyway, so set a high threshold here
        if (totalFood > 12 * totalProduction) valueModifier -= 20 * totalPlotValue / 100

        if (tile.isAdjacentToRiver()) valueModifier += totalPlotValue * buildOnRiverPercent / 100

        if (almostCoast) valueModifier -= 20 * totalPlotValue / 100

        if (centerCoastal) {
            if (waterPlots <= 3) valueModifier += totalPlotValue * buildOnCoastPercent / 100
            // We really like the coast (England, Norway, Polynesia, Carthage, etc.)
            if (navalFlavor > 7 || isCoastalCiv) valueModifier += totalPlotValue * buildOnCoastPercent / 100
        }

        if (PlotShape.isChokePoint(tile)) strategicModifier += totalPlotValue * chokepointValue / 100
        // Can we create a canal here?
        if (PlotShape.isWaterAreaSeparator(tile, waterAreas)) strategicModifier += totalPlotValue * chokepointValue / 100

        // For later cities consider placement relative to other players
        if (civ.cities.isNotEmpty()) {
            val ownCityDistance = maps.cityDistancePathLength(civ, tile)
            val neighbor = neighborOf(tile)
            if (neighbor != null) {
                var modifierPercent = STRENGTH_MODIFIERS[(WarStateAi.militaryStrengthComparedToUs(civ, neighbor) ?: StrengthLevel.Average).ordinal]
                val enemyDistance = maps.cityDistancePathLength(neighbor, tile)
                // Are we getting *very* close to the enemy?
                if (enemyDistance < min(ownCityDistance - 1, borderlandRange)) {
                    // Be annoying to our enemies
                    if (civ.ai.diplomacy.approachTowards(neighbor).ordinal <= MajorCivApproach.Guarded.ordinal ||
                        civ.ai.diplomacy.opinionOf(neighbor) <= MajorCivOpinion.Competitor) modifierPercent += 50
                } else if (civ.citiesCreated > 3) {
                    // Be nice to our friends, but only once we have our base cities
                    if (civ.ai.diplomacy.approachTowards(neighbor) == MajorCivApproach.Friendly ||
                        civ.ai.diplomacy.opinionOf(neighbor) >= MajorCivOpinion.Friend) modifierPercent -= 50
                }
                // Personality also plays a role
                modifierPercent += BOLDNESS_MODIFIERS[(boldness - 1).coerceIn(0, BOLDNESS_MODIFIERS.size - 1)]
                val adjustedEffect = borderlandValue * modifierPercent / 100
                strategicModifier += totalPlotValue * adjustedEffect / 100
            }

            // Where is our personal sweet spot? This is handled in plots, not in turns
            if (ownCityDistance <= minDistance) valueModifier -= 20 * totalPlotValue / 100

            var sweetMin = sweetSpotBase
            var sweetMax = sweetMin + 1
            // Check our preferred balance between tall and wide
            if (growthFlavor > 5) { sweetMax++; sweetMin++ } else if (growthFlavor < 5) { sweetMax--; sweetMin-- }
            if (expansionFlavor > 5) { sweetMax++; sweetMin++ } else if (expansionFlavor < 5) { sweetMax--; sweetMin-- }
            if (sweetMin < minDistance) sweetMin = minDistance
            // This affects both friendly and other cities
            val cityDistance = maps.closestCityDistanceInPlots(tile)
            if (cityDistance >= sweetMin && ownCityDistance <= sweetMax) valueModifier += totalPlotValue * 20 / 100
        }

        return max(0, totalPlotValue + valueModifier + strategicModifier + civModifier)
    }

    /**
     *  The major civilization whose territory a new city here would border (the "check if this location can be defended" of PlotFoundValue):
     *  the owner of the nearest city by path length when it is not us; else, in the early game (fewer than 4 cities founded), the owner of the
     *  nearest city two steps farther out from our capital ([neighborOutward]) when that is not us; null if none.
     */
    private fun neighborOf(tile: Tile): Civilization? {
        val closest = maps.closestCityOwnerByPathLength(tile, majorsOnly = true)
        val ours = gameInfo.civilizations.indexOf(civ)
        if (closest >= 0 && closest != ours) return gameInfo.civilizations[closest]
        if (closest >= 0 && civ.citiesCreated < 4) {
            val capitalTile = capital?.getCenterTile() ?: return null
            val outward = neighborOutward(neighborOutward(tile, capitalTile), capitalTile) ?: return null
            val second = maps.closestCityOwnerByPathLength(outward, majorsOnly = true)
            if (second >= 0 && second != ours) return gameInfo.civilizations[second]
        }
        return null
    }

    /** The neighbor of [tile] that is farthest from [capitalTile] (the original steps in the direction from the capital to the plot) */
    private fun neighborOutward(tile: Tile?, capitalTile: Tile): Tile? =
        tile?.neighbors?.maxByOrNull { it.aerialDistanceTo(capitalTile) }

    /** The scoring of one plot in the ring [distance] around [center], and the counts the site's cutoffs and civilization bonuses read */
    private fun visit(loopTile: Tile) {
        val d = distance
        val index = loopTile.zeroBasedIndex
        // Ignore some plots (typically enemy or close to enemy), but only if we cannot instantly claim them
        if (d > 1 && ignorePlots != null && ignorePlots[index]) return

        var ring = ringModifier[d]
        // Not only our cities, also other players' cities!
        val existingCityDistance = maps.closestCityDistanceInPlots(loopTile)
        // Count the tile only if the city will be able to work it (does not need to be passable)
        if (existingCityDistance < 2 || loopTile.isWorked()) ring = 0
        else if (existingCityDistance == 2) ring = 1 // this plot will likely be contested between the two cities, reduce its value

        var plotValue = DEFAULT_PLOT_VALUE
        if (ring > 0) {
            val food = foodValue(loopTile)
            val production = productionValue(loopTile)
            // This is about strategic placement, not resources
            val strategic = strategicValue(loopTile, d) * strategicMultiplier
            val unowned = if (loopTile.getOwner() == null) unownedValue(loopTile) else 0
            totalFood += food
            totalProduction += production
            plotValue += ring * (food + production + otherYieldValue(loopTile) + unowned) + strategic

            // Need at least some food close by, and some hammers
            if (d in 1..2) {
                if (food > 0) foodPlots++
                if (production > 0) hammerPlots++
                // Natural wonders and super features like atolls
                if (loopTile.isNaturalWonder()) goodPlots++
                val featureObjects = loopTile.terrainFeatureObjects
                if (featureObjects.any { it.isFreshwater }) goodPlots++
                if (featureObjects.isNotEmpty() && featureYield(featureObjects) >= 3) goodPlots++
            }
        }

        // Avoid this
        if (d == 1 && !centerCoastal && waterBodies.isCoastalLand(loopTile)) almostCoast = true
        // We do not want to be too exposed
        if (d == 1 && loopTile.isWater && !isLake(loopTile)) waterPlots++
        // If this tile is a natural wonder boost the value: humans over-value it like it is some sort of monopoly
        if (loopTile.isNaturalWonder() && plotValue > 0) plotValue *= 10
        // Lower the value a lot if we or somebody else already own this tile
        if (plotValue > 0 && loopTile.getOwner() != null) plotValue /= 2
        plotScores[plotCount++] = plotValue

        // Some civ-specific checks
        val features = loopTile.terrainFeatures
        if (Constants.forest in features && d > 0) {
            forests++
            if (d == 1 && loopTile.improvement == null) celticForests++
        } else if (Constants.jungle in features && d > 0) jungles++
        else if (MARSH in features) wetlands++
        if (loopTile.isNaturalWonder()) naturalWonders++
        val resource = visibleResource(loopTile)
        if (resource != null) when (resource.resourceType) {
            ResourceType.Luxury -> luxuries++
            ResourceType.Strategic -> strategics++
            ResourceType.Bonus -> bonuses++
        }
        if (loopTile.baseTerrain == Constants.desert && resource == null) deserts++
        if (loopTile.baseTerrain == Constants.mountain) mountains++
        if (isInca && loopTile.isHill()) {
            var adjacentMountains = 0
            for (neighbor in loopTile.neighbors) if (neighbor.baseTerrain == Constants.mountain) adjacentMountains++
            if (adjacentMountains > 0) incaBonus += (adjacentMountains + 1) * INCA_MULTIPLIER
        }
    }

    private fun isLake(tile: Tile) = tile.isWater && tile.getBaseTerrain().isFreshwater

    /** The sum of the yields the [featureObjects] of a plot add (CvFeatureInfo::getYieldChange over the yields before tourism) */
    private fun featureYield(featureObjects: List<com.unciv.models.ruleset.tile.Terrain>): Int {
        var total = 0f
        for (feature in featureObjects) total += feature.food + feature.production + feature.gold + feature.science + feature.culture + feature.faith
        return total.toInt()
    }

    /** ComputeFoodValue × SETTLER_FOOD_MULTIPLIER, cached per tile */
    private fun foodValue(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = foodCache[index]
        if (cached != UNSET) return cached
        val resource = visibleResource(tile)
        var yield = tileYield(tile, FOOD, resource)
        // Assume a farm or similar on suitable terrain: it should be built sooner or later, the value averages out with other improvements
        val features = tile.terrainFeatures
        if ((tile.baseTerrain == Constants.grassland || tile.baseTerrain == Constants.plains) && features.isEmpty() || "Flood plains" in features) yield += 1
        // Help with island settling: assume a lighthouse
        if (tile.isWater && !tile.isOcean) yield += 1
        val value = yield * flavorMultipliers[FOOD] * foodMultiplier
        foodCache[index] = value
        return value
    }

    /** ComputeProductionValue × SETTLER_PRODUCTION_MULTIPLIER, cached per tile */
    private fun productionValue(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = productionCache[index]
        if (cached != UNSET) return cached
        val resource = visibleResource(tile)
        var yield = tileYield(tile, PRODUCTION, resource)
        // Assume a mine or similar in friendly climate: do not run off into the snow
        if (tile.isHill() && tile.terrainFeatures.none { it != Constants.hill }) yield += 1
        val value = yield * flavorMultipliers[PRODUCTION] * yieldMultipliers[PRODUCTION]
        productionCache[index] = value
        return value
    }

    /** ComputeGoldValue, ComputeScienceValue and ComputeFaithValue, each × its SETTLER_<YIELD>_MULTIPLIER, summed and cached per tile */
    private fun otherYieldValue(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = otherYieldCache[index]
        if (cached != UNSET) return cached
        val resource = visibleResource(tile)
        var sum = 0
        for (factor in GOLD..FAITH) sum += tileYield(tile, factor, resource) * flavorMultipliers[factor] * yieldMultipliers[factor]
        otherYieldCache[index] = sum
        return sum
    }

    /** The terrain yield plus what the resource's improvement adds (CvPlot::calculateNatureYield + GetImprovementResourceYield) */
    private fun tileYield(tile: Tile, factor: Int, resource: TileResource?): Int {
        var yield = natureYield(tile, factor, resource)
        if (resource != null) yield += resourceYield(resource, factor)
        return yield
    }

    /**
     *  ComputeHappinessValue × SETTLER_HAPPINESS_MULTIPLIER + ComputeTradeableResourceValue × SETTLER_RESOURCE_MULTIPLIER for a plot nobody owns,
     *  both halved when one of our cities is within 3 ("our existing city will catch those eventually"): a luxury's happiness (x5 when we
     *  do not have it yet); the amount of a luxury or strategic resource times RESOURCES (a luxury x3, x3 again when we do not have it).
     */
    private fun unownedValue(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = unownedCache[index]
        if (cached != UNSET) return cached
        val resource = visibleResource(tile)
        var happiness = 0
        var tradeable = 0
        if (resource != null) {
            val notOwned = (ownedResources[resource.name] ?: 0) <= 0
            if (resource.resourceType == ResourceType.Luxury) happiness = DealResourceValues.luxuryHappiness(civ)
            if (notOwned) happiness *= 5
            if (resource.resourceType == ResourceType.Luxury || resource.resourceType == ResourceType.Strategic) {
                tradeable = (if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount else 1) * flavorMultipliers[RESOURCES]
                // Since there are not "multiple" luxuries on a tile, increase this value
                if (resource.resourceType == ResourceType.Luxury) tradeable *= 3
                if (notOwned) tradeable *= 3
            }
        }
        happiness *= flavorMultipliers[HAPPINESS] * happinessMultiplier
        tradeable *= resourceMultiplier
        if (maps.cityDistanceInPlots(civ, tile) <= 3) {
            happiness /= 2
            tradeable /= 2
        }
        val value = happiness + tradeable
        unownedCache[index] = value
        return value
    }

    /**
     *  CvCitySiteEvaluator::ComputeStrategicValue: for a plot nobody owns within 3 of the city ice (BALANCE_BAD_TILES_STRATEGIC_VALUE), fresh
     *  water (BALANCE_FRESH_WATER_STRATEGIC_VALUE) and coast (BALANCE_COAST_STRATEGIC_VALUE); a hill on the city plot is worth 12 and next to
     *  the city 1 HILL_STRATEGIC_VALUE; the starting location weight of its feature next to the city; times the strategic flavor.
     */
    private fun strategicValue(tile: Tile, plotsFromCity: Int): Int {
        var value = 0
        if (tile.getOwner() == null && plotsFromCity <= 3) value += tileStrategic(tile)
        if (tile.isHill()) {
            if (plotsFromCity == 1) value += hillValue
            else if (plotsFromCity == 0) value += hillValue * 12
        }
        // Some features are less attractive to settle in (e.g. jungles, since it takes a while before you can clear them and they slow down movement)
        if (plotsFromCity == 1) for (feature in tile.terrainFeatures) value += rules.featureStartingLocationWeights[feature] ?: 0
        return value * flavorMultipliers[STRATEGIC]
    }

    private fun tileStrategic(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = tileStrategicCache[index]
        if (cached != UNSET) return cached
        var value = 0
        if (Constants.ice in tile.terrainFeatures) value += badTilesValue
        if (tile.isAdjacentTo(Constants.freshWater)) value += freshWaterValue
        if (waterBodies.isCoastalLand(tile)) value += coastValue
        tileStrategicCache[index] = value
        return value
    }

    private fun visibleResource(tile: Tile): TileResource? =
        if (tile.resource != null && civ.canSeeResource(tile.tileResource)) tile.tileResource else null

    /**
     *  CvPlot::calculateNatureYield: the terrain yield (base terrain, features - some replace it -, natural wonder) plus the resource's
     *  yield if the player can see it; 0 on impassable plots except natural wonders.
     */
    private fun natureYield(tile: Tile, factor: Int, resource: TileResource?): Int {
        if (tile.isImpassible() && !tile.isNaturalWonder()) return 0
        var yield = 0f
        for (terrain in tile.allTerrains) {
            val value = statOf(terrain, factor)
            if (terrain.overrideStats || terrain.hasUnique(UniqueType.NullifyYields)) yield = value else yield += value
            if (terrain.hasUnique(UniqueType.NullifyYields)) break
        }
        if (resource != null) yield += statOf(resource, factor)
        return yield.toInt()
    }

    /** The yield its improvement adds to the resource (CvImprovementEntry::GetImprovementResourceYield) */
    private fun resourceYield(resource: TileResource, factor: Int): Int = (resource.improvementStats?.let { statOf(it, factor) } ?: 0f).toInt()

    private fun statOf(stats: Stats, factor: Int): Float = when (factor) {
        FOOD -> stats.food
        PRODUCTION -> stats.production
        GOLD -> stats.gold
        SCIENCE -> stats.science
        else -> stats.faith
    }

}
