package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
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
