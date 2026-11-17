package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset

/** ExplorationDomainTypes: the exploration plot set a plot belongs to, by what it is (land, shallow water, deep water) */
enum class ExplorationDomain { Land, Coast, Ocean }

/** CvExplorationPlotSet: for each plot (by [Tile.zeroBasedIndex]) its explore values at range 1, 2 and 3; a plot whose three are 0 is not in it */
class ExplorationPlotSet {
    private val values = HashMap<Int, IntArray>()

    val size: Int get() = values.size

    /** ChangeValue: adds [change] to the value of [range] (1-3) */
    fun changeValue(plotIndex: Int, range: Int, change: Int) {
        val entry = values.getOrPut(plotIndex) { IntArray(3) }
        entry[range - 1] += change
        if (entry[0] == 0 && entry[1] == 0 && entry[2] == 0) values.remove(plotIndex)
    }

    /** GetValue: the values of the ranges up to [range] (at most 3) */
    fun getValue(plotIndex: Int, range: Int): Int {
        val entry = values[plotIndex] ?: return 0
        var value = 0
        for (r in 1..range.coerceAtMost(3)) value += entry[r - 1]
        return value
    }
}

/**
 *  Vox Populi's exploration plots of a civilization (CvEconomicAI::m_vPlotsToExploreLand, ..Coast, ..Ocean): every revealed plot, in the
 *  set of its domain, valued at each range r by the plots not revealed at exactly r that it has a line of sight to (at r = 1, every
 *  neighbour) and that have a revealed neighbour, each worth [multiplier]. VP keeps the sets up to date as plots are revealed and lines
 *  of sight change (AddPlotToExplorePlots, InitiateExplorePlot, UpdateNearbyExplorePlots, UpdateExplorePlotLoS); [build] computes what
 *  they amount to from the plots revealed, once a turn. The line of sight is [com.unciv.logic.map.TileMap.getViewableTiles] from the plot
 *  (CvMap::GetPlotsAtRangeX with LoS from the plot, which uses CvPlot::canSeePlot).
 */
class ExplorationPlotSets private constructor() {
    val land = ExplorationPlotSet()
    val coast = ExplorationPlotSet()
    val ocean = ExplorationPlotSet()

    /** CvEconomicAI::GetExplorationPlots(ExplorationDomainTypes) */
    fun of(domain: ExplorationDomain): ExplorationPlotSet = when (domain) {
        ExplorationDomain.Land -> land
        ExplorationDomain.Coast -> coast
        ExplorationDomain.Ocean -> ocean
    }

    /**
     *  The value of [tile] at [range] in CvEconomicAI::GetExplorationPlots([unit]): the land plots for a land unit, the coast plots for a
     *  ship or a unit that can embark now, the ocean plots for a unit that may stay in the ocean ([canStayInOcean]); 0 for the others
     */
    fun valueFor(unit: MapUnit, tile: Tile, range: Int): Int {
        val included = when (domainOf(tile)) {
            ExplorationDomain.Land -> unit.baseUnit.isLandUnit
            ExplorationDomain.Coast -> !unit.baseUnit.isLandUnit || unit.civ.tech.unitsCanEmbark && !unit.cache.cannotEmbark
            ExplorationDomain.Ocean -> canStayInOcean(unit)
        }
        return if (included) of(domainOf(tile)).getValue(tile.zeroBasedIndex, range) else 0
    }

    companion object {
        /** The set a plot is kept in: land; shallow water (CvPlot::isShallowWater, Coast and Lakes); deep water */
        fun domainOf(tile: Tile): ExplorationDomain = when {
            !tile.isWater -> ExplorationDomain.Land
            !tile.isOcean -> ExplorationDomain.Coast
            else -> ExplorationDomain.Ocean
        }

        /** GetPlotExploreMultiplier: LAND_EXPLORE_TILE_VALUE for land, 0 for ice, COAST_EXPLORE_TILE_VALUE for shallow water, else OCEAN_EXPLORE_TILE_VALUE */
        fun multiplier(tile: Tile, rules: AiRuleset): Int = when {
            !tile.isWater -> rules.define(AiDefine.LAND_EXPLORE_TILE_VALUE)
            tile.terrainFeatures.contains(Constants.ice) -> 0
            !tile.isOcean -> rules.define(AiDefine.COAST_EXPLORE_TILE_VALUE)
            else -> rules.define(AiDefine.OCEAN_EXPLORE_TILE_VALUE)
        }

        /** CvUnit::CanStayInOcean: no ocean-impassable rule, and a land unit only when embarked units may enter the ocean */
        fun canStayInOcean(unit: MapUnit): Boolean =
            !unit.cache.cannotEnterOceanTiles && (!unit.baseUnit.isLandUnit || unit.civ.tech.embarkedUnitsCanEnterOcean)

        /**
         *  EconomicAIHelpers::GetExtraExploreValue: for [range] 2 or 3, UNKNOWN_EXPLORE_TILE_VALUE for each plot at distance 2 to [range]
         *  that is not revealed, has no revealed neighbour and is in the line of sight of [tile] (CanMaybeSeePlot). VP means to average the
         *  multipliers of its neighbours with a revealed neighbour, but its loop covers only the plot itself, which has none.
         */
        fun extraExploreValue(civ: Civilization, tile: Tile, range: Int): Int {
            if (range < 2) return 0
            val maxRange = range.coerceAtMost(3)
            val tileMap = civ.gameInfo.tileMap
            val unknownValue = civ.gameInfo.ruleset.ai.define(AiDefine.UNKNOWN_EXPLORE_TILE_VALUE)
            val lineOfSight by lazy { tileMap.getViewableTiles(tile.position, maxRange).toHashSet() }
            var value = 0
            for (distance in 2..maxRange) tileMap.forEachTileAtDistance(tile.position, distance) { nearby ->
                if (!civ.hasExplored(nearby) && nearby.neighbors.none { civ.hasExplored(it) } && nearby in lineOfSight) value += unknownValue
            }
            return value
        }

        /** The sets of [civ]: from each plot not revealed that has a revealed neighbour, the revealed plots within 3 that count it */
        fun build(civ: Civilization): ExplorationPlotSets {
            val sets = ExplorationPlotSets()
            val tileMap = civ.gameInfo.tileMap
            val rules = civ.gameInfo.ruleset.ai
            val lineOfSight = HashMap<Tile, Set<Tile>>()
            for (target in tileMap.values) {
                if (civ.hasExplored(target) || target.neighbors.none { civ.hasExplored(it) }) continue
                val value = multiplier(target, rules)
                if (value == 0) continue
                for (range in 1..3) tileMap.forEachTileAtDistance(target.position, range, { civ.hasExplored(it) }) { plot ->
                    if (range == 1 || target in lineOfSight.getOrPut(plot) { tileMap.getViewableTiles(plot.position, 3).toHashSet() })
                        sets.of(domainOf(plot)).changeValue(plot.zeroBasedIndex, range, value)
                }
            }
            return sets
        }
    }
}
