package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/**
 *  The terms of Vox Populi's AI that ask how far a civilization is from a resource monopoly (slice T6 of `phaseE/vp-trade-plan.md`), over what
 *  [com.unciv.logic.civilization.managers.MonopolyManager] knows. Each function is one place of the original and is only read where the ruleset has
 *  monopolies ([com.unciv.models.ruleset.Ruleset.hasMonopolyResources]: the original's MOD_BALANCE_RESOURCE_MONOPOLIES), so rules without them keep their numbers.
 *
 *  Not ported: everything the original reads through `IsImportsCountTowardsMonopolies` (no trait has it in Vox Populi's data: the Netherlands branches of the
 *  deal values, the "can reach a monopoly through imports" tests), the resource a build creates (`GetResourceFromImprovement`: no improvement of ours does),
 *  and the monopoly term of the adjacent plots of the border claim (V:CvCity.cpp:28409), which only runs for a trait that claims terrain (`TerrainClaimBoost`: none).
 */
object MonopolyAi {
    /** What a build adds to the secondary and to the potential score of a plot */
    class BuildBonus(val secondary: Int, val potential: Int)

    private val none = BuildBonus(0, 0)

    /** CvPlot::getNumResource: the copies a tile with [resource] holds, as [com.unciv.logic.GameInfo.resourceCopiesOnMap] counts them: the quantity of a strategic resource, one of any other */
    @Readonly
    fun copiesOf(tile: Tile, resource: TileResource): Int = if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount else 1

    /**
     *  CvBuilderTaskingAI::ScorePlotBuild, "amp up monopoly alloc" (V:CvBuilderTaskingAI.cpp:4073-4140), for the build of an improvement that connects [resource]:
     *  [extraResource] copies more (or fewer) than the civilization has (what other builds will connect, less what the old improvement did), [baseAmount] the copies
     *  of the plot itself. A resource that is a monopoly and, if a luxury, is not banned for the civilization counts the share the civilization owns now and would own
     *  then of the copies on the map (a resource that is not on the map: 100 or 0 percent by owning any). Share now at most the global threshold: the build gives a
     *  global monopoly +2000 secondary, a strategic one +1000 secondary (and +1000 potential if a global one is still reachable); else a global monopoly still
     *  reachable +2000 potential, a strategic one +1000. Reachable: the share then, or the civilization's tiles with the resource (and its City-States' copies when they
     *  count), is above the threshold; strategic only for a strategic resource.
     */
    @Readonly
    fun plotBuildBonus(civ: Civilization, resource: TileResource, extraResource: Int, baseAmount: Int): BuildBonus {
        if (!resource.isMonopoly) return none
        val gameInfo = civ.gameInfo
        if (resource.resourceType == ResourceType.Luxury && gameInfo.league?.isLuxuryBanned(resource.name) == true) return none
        val constants = gameInfo.ruleset.modOptions.constants.monopoly
        val owned = civ.monopolies.ownedCopies(resource) + extraResource
        val total = gameInfo.resourceCopiesOnMap(resource.name)
        val current: Int
        val future: Int
        if (total <= 0) {
            current = if (owned > 0) 100 else 0
            future = if (owned + baseAmount > 0) 100 else 0
        } else {
            current = owned * 100 / total
            future = (owned + baseAmount) * 100 / total
        }
        val globalThreshold = constants.globalThreshold
        val strategicThreshold = if (resource.resourceType == ResourceType.Strategic) constants.strategicThreshold else 0
        val fromMinors = if (civ.hasUnique(UniqueType.CityStateResourcesCountForMonopolies)) copiesFromCityStates(civ, resource) else 0
        val inOwnedPlots = ownedPlotsWith(civ, resource)
        val canReachGlobal = future > globalThreshold || 100 * (inOwnedPlots + fromMinors) > globalThreshold * total
        val canReachStrategic = strategicThreshold != 0 &&
            (future > strategicThreshold || 100 * (inOwnedPlots + fromMinors) > strategicThreshold * total)

        if (current > globalThreshold) return none
        return when {
            future > globalThreshold -> BuildBonus(2000, 0)
            strategicThreshold != 0 && current <= strategicThreshold && future > strategicThreshold -> BuildBonus(1000, if (canReachGlobal) 1000 else 0)
            canReachGlobal -> BuildBonus(0, 2000)
            canReachStrategic -> BuildBonus(0, 1000)
            else -> none
        }
    }

    /** CvPlayer::getResourceFromMinors: the copies that allied City-States give */
    @Readonly
    private fun copiesFromCityStates(civ: Civilization, resource: TileResource): Int =
        civ.detailedCivResources.filter { it.resource == resource && it.amount > 0 && it.origin == Constants.cityStates }.sumOf { it.amount }

    /** CvPlayer::getResourceInOwnedPlots: the tiles of the civilization's cities that have [resource] and that it can see */
    @Readonly
    private fun ownedPlotsWith(civ: Civilization, resource: TileResource): Int {
        if (!civ.canSeeResource(resource)) return 0
        return civ.cities.sumOf { city -> city.getTiles().count { it.resource == resource.name } }
    }

    /**
     *  CvCitySiteEvaluator::ComputeTradeableResourceValue's monopoly step (V:CvSiteEvaluationClasses.cpp:1141-1153): 2 when the [copies] of the plot with the copies
     *  [civ] owns (its tiles, buildings and policies, copies sold included) make at least 30 percent of the copies on the map, else 1. The original's x10 for at
     *  least 50 percent without the copies sold can never apply: it needs 50 percent of a share that is at most the share the first test already takes.
     */
    @Readonly
    fun siteMultiplier(civ: Civilization, resource: TileResource, copies: Int): Int {
        if (!civ.gameInfo.ruleset.hasMonopolyResources) return 1
        val total = civ.gameInfo.resourceCopiesOnMap(resource.name)
        if (total <= 0) return 1
        return if ((copies + civ.monopolies.ownedCopies(resource)) * 100 / total >= SITE_MONOPOLY_PERCENT) 2 else 1
    }

    /**
     *  CvMilitaryAI::ScoreAttackTarget's monopoly step (V:CvMilitaryAI.cpp:1233-1250): the percent the desirability of [target] is multiplied by, 125 when it has,
     *  on tiles that its owner can see, a resource of which [civ] already owns a share of the copies on the map, else 100. The first such resource counts.
     */
    @Readonly
    fun attackDesirabilityPercent(civ: Civilization, target: City): Int {
        val ruleset = civ.gameInfo.ruleset
        if (!ruleset.hasMonopolyResources) return 100
        val tiles = target.getTiles()
        for (resource in ruleset.tileResources.values) {
            val total = civ.gameInfo.resourceCopiesOnMap(resource.name)
            if (total <= 0) continue
            if (!target.civ.canSeeResource(resource) || tiles.none { it.resource == resource.name }) continue
            if (civ.monopolies.ownedCopies(resource) * 100 / total > 0) return ATTACK_MONOPOLY_PERCENT
        }
        return 100
    }

    /**
     *  CvPlayer::WouldGainMonopoly as the two city functions ask it, in a ruleset with monopolies only: CvCity::updateEconomicValue (V:CvCity.cpp:7928, 7941: whether
     *  [civ] would gain a monopoly of [resource] with the [copies] of a city, which raises the value of the resource to the best tier) and CvCity::GetBuyablePlotList
     *  (V:CvCity.cpp:28295: whether the copies on a tile are the ones the city's owner needs, which lowers the tile's influence cost by PLOT_INFLUENCE_RESOURCE_COST)
     */
    @Readonly
    fun wouldGainMonopoly(civ: Civilization, resource: TileResource, copies: Int): Boolean =
        civ.gameInfo.ruleset.hasMonopolyResources && civ.monopolies.wouldGain(resource, copies)

    private const val SITE_MONOPOLY_PERCENT = 30
    private const val ATTACK_MONOPOLY_PERCENT = 125
}
