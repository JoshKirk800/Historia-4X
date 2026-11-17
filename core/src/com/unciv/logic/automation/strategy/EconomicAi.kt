package com.unciv.logic.automation.strategy

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.max
import kotlin.math.min

/** CvEconomicAI ReconState */
enum class ReconState { Enough, Neutral, Needed }

/**
 *  The part of CvEconomicAI beyond the strategy loop (`civ.ai.economic`): recon state, antiquity sites, worker disbanding.
 *  Plot purchases are in [UseGoldAutomation.doPlotPurchases], the purchase savings in [PurchaseSavings].
 *
 *  Not ported, with the reason: DoHurry (its gold branch is commented out in the original, and the population branch needs
 *  HURRY_POPULATION access that no Brave New World policy or building grants - Unciv has no population rushing either);
 *  DisbandExtraArchaeologists and DoSwapGreatWorks (culture port). The exploration plots are [ExplorationPlotSets].
 */
class EconomicAi : IsPartOfGameInfoSerialization {
    /** CvEconomicAI::m_iLastTurnWorkerDisbanded, -1 if never */
    var lastTurnWorkerDisbanded = -1
    /** CvEconomicAI::m_iVisibleAntiquitySites, updated by [doAntiquitySites] */
    var visibleAntiquitySites = 0

    /** CvEconomicAI::m_eReconState, recomputed each turn before the strategy loop (null before the first computation) */
    @Transient var reconState: ReconState? = null
    /** CvEconomicAI::m_eNavalReconState */
    @Transient var navalReconState: ReconState? = null
    /** CvEconomicAI::m_vPlotsToExploreLand/Coast/Ocean as of the last [refreshExplorationPlots] (null before) */
    @Transient var explorationPlots: ExplorationPlotSets? = null
    /** How many plots were revealed when [explorationPlots] were built (revealed plots only add up, so the same count is the same plots) */
    @Transient private var explorationPlotsRevealed = -1
    /** CvEconomicAI::m_iExplorersNeeded, set by [doReconState]; read by [MilitaryAi]'s recommended explorers */
    @Transient var explorersNeeded = 0

    /**
     *  The exploration plots as the plots revealed now make them: rebuilt when more plots are revealed (VP updates its sets whenever a
     *  plot is revealed or a line of sight changes; a changed line of sight alone is not followed here)
     */
    fun refreshExplorationPlots(civ: Civilization): ExplorationPlotSets {
        val revealed = civ.gameInfo.tileMap.values.count { civ.hasExplored(it) }
        explorationPlots?.let { if (revealed == explorationPlotsRevealed) return it }
        explorationPlotsRevealed = revealed
        return ExplorationPlotSets.build(civ).also { explorationPlots = it }
    }

    fun clone(): EconomicAi {
        val toReturn = EconomicAi()
        toReturn.lastTurnWorkerDisbanded = lastTurnWorkerDisbanded
        toReturn.visibleAntiquitySites = visibleAntiquitySites
        return toReturn
    }

    /** What CvEconomicAI::DoTurn does before its strategy loop: DoReconState and DoAntiquitySites, in one pass over the map */
    fun doTurnBeforeStrategies(civ: Civilization) {
        doReconState(civ)
        doAntiquitySites(civ)
    }

    /** What CvEconomicAI::DoTurn does after its strategy loop for AI players: DoPlotPurchases, DisbandExtraWorkers, DisbandExtraWorkboats */
    fun doTurnAfterStrategies(civ: Civilization) {
        UseGoldAutomation.doPlotPurchases(civ)
        disbandExtraWorkers(civ)
        disbandExtraWorkboats(civ)
    }

    /**
     *  CvEconomicAI::DoReconState (Vox Populi). City-states: ENOUGH for both (VP leaves their states alone, no recon strategy applies to
     *  them). Without cities: NEUTRAL. Losing all wars: ENOUGH, no explorer wanted. Else, from the [explorationPlots] ([ExplorationPlotSets.build]):
     *  - land: the land plots, + a quarter of the coast plots once units can embark, + a quarter of the ocean plots for an ocean farer
     *    (embarked units may enter the ocean; Team::CanBuildOceanCrossingUnit needs a CanCrossOceans promotion, which BNW has not);
     *    explorers wanted x 100 = 50 + plots x 100 / (MAX_PLOTS_PER_EXPLORER - RECON flavor), halved at war (AT_WAR), at least 100 with any
     *    plot ([explorersNeeded]: / 100). Explorers (UNITAI_EXPLORE, those being trained included) more than half a unit short: NEEDED,
     *    and [promoteLandExplorer]; more than half a unit over: ENOUGH, and [retireLandExplorer]; else NEUTRAL;
     *  - naval, with a coastal city (else ENOUGH): the coast plots, + the ocean plots for an ocean farer, the NAVAL_RECON flavor and the
     *    sea explorers (UNITAI_EXPLORE_SEA) likewise, with [promoteNavalExplorer] and [retireNavalExplorer].
     */
    fun doReconState(civ: Civilization) {
        UnitAiTypes.pruneOverrides(civ)
        if (civ.isCityState) {
            reconState = ReconState.Enough
            navalReconState = ReconState.Enough
            return
        }
        reconState = ReconState.Neutral
        navalReconState = ReconState.Neutral
        if (civ.cities.isEmpty()) return
        if (WarStateAi.stateAllWars(civ) == StateAllWars.Losing) {
            reconState = ReconState.Enough
            navalReconState = ReconState.Enough
            explorersNeeded = 0
            return
        }
        val plots = refreshExplorationPlots(civ)
        val rules = civ.gameInfo.ruleset.ai
        val war = civ.ai.isMilitaryStrategyActive(MilitaryAi.AT_WAR)
        val oceanFarer = civ.tech.embarkedUnitsCanEnterOcean

        var plotsToExplore = plots.land.size
        if (civ.tech.unitsCanEmbark) plotsToExplore += plots.coast.size / 4
        if (oceanFarer) plotsToExplore += plots.ocean.size / 4
        val landNeeded = explorersNeededTimes100(civ, plotsToExplore, Flavor.Recon, war)
        explorersNeeded = landNeeded / 100
        val landExplorers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.EXPLORE, includeBeingTrained = true)
        reconState = when {
            landExplorers * 100 < landNeeded - 50 -> ReconState.Needed.also { promoteLandExplorer(civ, oceanFarer) }
            landExplorers * 100 > landNeeded + 50 -> ReconState.Enough.also { retireLandExplorer(civ) }
            else -> ReconState.Neutral
        }

        if (civ.cities.none { WaterBodies.isCoastal(it) }) {
            navalReconState = ReconState.Enough
            return
        }
        var waterToExplore = plots.coast.size
        if (oceanFarer) waterToExplore += plots.ocean.size
        val navalNeeded = explorersNeededTimes100(civ, waterToExplore, Flavor.NavalRecon, war)
        val seaExplorers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.EXPLORE_SEA, includeBeingTrained = true, includeWater = true)
        navalReconState = when {
            seaExplorers * 100 < navalNeeded - 50 -> ReconState.Needed.also { promoteNavalExplorer(civ, oceanFarer, rules) }
            seaExplorers * 100 > navalNeeded + 50 -> ReconState.Enough.also { retireNavalExplorer(civ, rules) }
            else -> ReconState.Neutral
        }
    }

    /** 50 + [plots] x 100 / (MAX_PLOTS_PER_EXPLORER - [flavor] (personality and grand strategy)), halved at [war], at least 100 with any plot */
    private fun explorersNeededTimes100(civ: Civilization, plots: Int, flavor: Flavor, war: Boolean): Int {
        val plotsPerExplorer = civ.gameInfo.ruleset.ai.define(AiDefine.MAX_PLOTS_PER_EXPLORER) - civ.ai.personalityAndGrandStrategy(flavor)
        var needed = 50 + plots * 100 / max(1, plotsPerExplorer)
        if (war) needed /= 2
        if (needed < 100 && plots > 0) needed = 100
        return needed
    }

    /**
     *  The NEEDED step of DoReconState for land: among our units that do not explore, potential land explorers ([isPotentialLandExplorer];
     *  for an ocean farer only those that may stay in the ocean) more than 3 turns old that are free for operations ([canUseForAiOperation]),
     *  the one farthest from our cities, units made to explore first (+2000), becomes an explorer: if such a unit is among them, or if
     *  fewer than half of the other units that could explore already do. Units that gain XP from scouting (+1000): none in BNW.
     */
    private fun promoteLandExplorer(civ: Civilization, oceanFarer: Boolean) {
        val rules = civ.gameInfo.ruleset.ai
        var availableNonNative = 0
        var nativeAvailable = false
        var exploringNonNative = 0
        val candidates = ArrayList<Pair<Int, MapUnit>>()
        for (unit in civ.units.getCivUnits()) {
            val native = UnitAiTypes.defaultOf(unit.baseUnit, rules) == UnitAiType.EXPLORE
            if (UnitAiTypes.current(unit) == UnitAiType.EXPLORE) {
                if (!native) exploringNonNative++
                continue
            }
            if (!isPotentialLandExplorer(unit.baseUnit)) continue
            if (oceanFarer && !ExplorationPlotSets.canStayInOcean(unit)) continue
            if (civ.gameInfo.turns - unit.turnCreated <= 3) continue
            if (!canUseForAiOperation(unit)) continue
            val distance = cityDistanceInPlots(civ, unit.getTile())
            if (native) {
                candidates += distance + 2000 to unit
                nativeAvailable = true
            } else {
                candidates += distance to unit
                availableNonNative++
            }
        }
        val lessThanHalfExploring = exploringNonNative * 2 < exploringNonNative + availableNonNative
        if (nativeAvailable || availableNonNative > 0 && lessThanHalfExploring)
            UnitAiTypes.set(candidates.sortedBy { it.first }.last().second, UnitAiType.EXPLORE)
    }

    /** The ENOUGH step for land: the explorer nearest to our cities (units made to explore last, +2000) goes back to its own unit AI, a unit made to explore to FAST_ATTACK */
    private fun retireLandExplorer(civ: Civilization) {
        val rules = civ.gameInfo.ruleset.ai
        val explorer = civ.units.getCivUnits().filter { UnitAiTypes.current(it) == UnitAiType.EXPLORE }
            .minByOrNull { cityDistanceInPlots(civ, it.getTile()) + if (UnitAiTypes.defaultOf(it.baseUnit, rules) == UnitAiType.EXPLORE) 2000 else 0 }
            ?: return
        val default = UnitAiTypes.defaultOf(explorer.baseUnit, rules)
        UnitAiTypes.set(explorer, if (default != UnitAiType.EXPLORE) default else UnitAiType.FAST_ATTACK)
    }

    /**
     *  The NEEDED step for the sea: among our ships that do not explore the sea, potential naval explorers ([isPotentialNavalExplorer])
     *  that are not ranged and visible (ranged ships stay), more than 3 turns old, free for operations, for an ocean farer only those that
     *  may stay in the ocean, the one farthest from our cities explores the sea.
     */
    private fun promoteNavalExplorer(civ: Civilization, oceanFarer: Boolean, rules: AiRuleset) {
        val explorer = civ.units.getCivUnits().filter { unit ->
            UnitAiTypes.current(unit) != UnitAiType.EXPLORE_SEA
                && isPotentialNavalExplorer(UnitAiTypes.defaultOf(unit.baseUnit, rules))
                && !(unit.baseUnit.isRanged() && !unit.hasUnique(UniqueType.Invisible))
                && civ.gameInfo.turns - unit.turnCreated > 3
                && canUseForAiOperation(unit)
                && !(oceanFarer && !ExplorationPlotSets.canStayInOcean(unit))
        }.sortedBy { cityDistanceInPlots(civ, it.getTile()) }.lastOrNull() ?: return
        UnitAiTypes.set(explorer, UnitAiType.EXPLORE_SEA)
    }

    /** The ENOUGH step for the sea: the sea explorer nearest to our cities whose own unit AI is another goes back to it */
    private fun retireNavalExplorer(civ: Civilization, rules: AiRuleset) {
        val explorer = civ.units.getCivUnits().filter {
            UnitAiTypes.current(it) == UnitAiType.EXPLORE_SEA && UnitAiTypes.defaultOf(it.baseUnit, rules) != UnitAiType.EXPLORE_SEA
        }.minByOrNull { cityDistanceInPlots(civ, it.getTile()) } ?: return
        UnitAiTypes.set(explorer, UnitAiTypes.defaultOf(explorer.baseUnit, rules))
    }

    /** CvPlayer::GetCityDistanceInPlots: the distance from [tile] to our nearest city */
    private fun cityDistanceInPlots(civ: Civilization, tile: Tile): Int = civ.cities.minOf { it.getCenterTile().aerialDistanceTo(tile) }

    /**
     *  CvUnit::canUseForAIOperation: not in an army; a civilian always; not the garrison of a city that needs one (under siege, or exposed
     *  to an enemy outside the early expansion phase, CvCity::NeedsGarrison); no enemy unit within 3 tiles. The tactical zone's dominance
     *  and the citadel near a contested border are not ported.
     */
    private fun canUseForAiOperation(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (civ.ai.operations.armyOf(unit) != null) return false
        if (unit.isCivilian()) return true
        val tile = unit.getTile()
        val city = tile.getCity()
        if (tile.isCityCenter() && city != null && city.civ == civ && needsGarrison(city)) return false
        return tile.getTilesInDistance(3).none { nearby -> nearby.getUnits().any { it.isMilitary() && it.civ.isAtWarWith(civ) } }
    }

    private fun needsGarrison(city: City): Boolean = CityDangerAi.isUnderSiege(city) ||
        !city.civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.EARLY_EXPANSION) && city.civ.ai.military.isExposedToEnemy(city.civ, city, null)

    /** CvEconomicAI::DoAntiquitySites: antiquity sites (hidden ones included) on tiles the civilization has revealed */
    fun doAntiquitySites(civ: Civilization) {
        visibleAntiquitySites = ArchaeologyFunctions.getVisibleSites(civ).size
    }

    /**
     *  CvEconomicAI::DisbandExtraWorkers (Vox Populi), every turn of an AI player: scraps one land worker when
     *  - there is more than one (those being trained included), and the civilization has founded 4 cities or it is turn 101;
     *  - the workers are more than a quarter per city (whole percent, at least one city);
     *  - more than 66% (whole percent) of the owned improvable tiles ([EconomicAiRatios.isImprovablePlot]: land, passable, no city) have an
     *    unpillaged improvement;
     *  - more workers than cities stand idle in a city.
     *  The worker to go is [findWorkerToScrap]; only a scrapped worker sets [lastTurnWorkerDisbanded], which locks out the tile improver
     *  strategies for 12 turns. Rules: the original's CvUnit::canScrap (the unit can move and is not under fire, MOD_BALANCE_VP's rule
     *  against disbanding to deny a kill) is not asked.
     *
     *  Replaces BNW's version, which ran only when losing money or paying more than 4 gold a unit, and kept a worker for every five
     *  unimproved tiles and one for every city not connected to the capital.
     */
    fun disbandExtraWorkers(civ: Civilization) {
        val numWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true, includeWater = false)
        if (numWorkers <= 1) return
        if (civ.citiesCreated < 4 && civ.gameInfo.turns <= 100) return
        val numCities = civ.cities.size
        if (100 * numWorkers / maxOf(1, numCities) <= WORST_CASE_WORKERS_PER_CITY_PERCENT) return

        var valid = 0
        var improved = 0
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!EconomicAiRatios.isImprovablePlot(tile)) continue
            valid++
            if (tile.improvement != null && !tile.improvementIsPillaged) improved++
        }
        if (valid == 0 || 100 * improved / valid <= IMPROVED_PLOTS_PERCENT_TO_DISBAND) return

        val rules = civ.gameInfo.ruleset.ai
        val workers = civ.units.getCivUnits().filter { isLandWorker(it, rules) }.toList()
        if (workers.count { it.getTile().isCityCenter() } <= numCities) return

        val worker = findWorkerToScrap(workers) ?: return
        lastTurnWorkerDisbanded = civ.gameInfo.turns
        worker.disband()
    }

    /** A land unit that is no combat unit with the worker unit AI (the original compares the unit type with the civilization's worker) */
    private fun isLandWorker(unit: MapUnit, rules: AiRuleset): Boolean =
        unit.baseUnit.isLandUnit && unit.isCivilian() && UnitAiTypes.defaultOf(unit.baseUnit, rules) == UnitAiType.WORKER

    /**
     *  CvEconomicAI::FindWorkerToScrap: the worker with the smallest positive work rate bonus (CvUnit::GetWorkRateMod), else the first
     *  scrappable worker. Rules: no unit has a work rate modifier here (a promotion or trait of Vox Populi), so it is the first one in the
     *  civilization's unit list.
     */
    private fun findWorkerToScrap(workers: List<MapUnit>): MapUnit? = workers.firstOrNull()

    /**
     *  CvEconomicAI::DisbandExtraWorkboats (Vox Populi), every turn of an AI player: scraps one work boat when
     *  - the civilization has sea workers (those being trained included), and no more cities run NEED_NAVAL_TILE_IMPROVEMENT than there are of them;
     *  - it has founded 4 cities or it is turn 101;
     *  - the water plots it owns that a unit can pass and that have a resource it sees without an improvement are fewer than the sea workers.
     *  The original sets [lastTurnWorkerDisbanded] (the lock-out of the tile improver strategies) before it looks for a boat, so a turn with no boat
     *  to scrap sets it too. The boat to go is [findSeaWorkerToScrap]. Rules: as for [disbandExtraWorkers] (CvUnit::canScrap is not asked).
     */
    fun disbandExtraWorkboats(civ: Civilization) {
        val numWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER_SEA, includeBeingTrained = true, includeWater = true)
        if (numWorkers <= 0) return

        // If we want workers in any city, don't disband; # of cities needing workers greater than number of workers? don't disband
        if (civ.cities.count { it.ai.isCityStrategyActive(NEED_NAVAL_TILE_IMPROVEMENT) } >= numWorkers) return
        // Don't disband during the early game
        if (civ.citiesCreated < 4 && civ.gameInfo.turns <= 100) return

        var unimproved = 0
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!tile.isWater || tile.isImpassible()) continue
            val resource = tile.tileResource
            if (tile.improvement == null && resource != null && civ.canSeeResource(resource)) unimproved++
        }
        if (numWorkers <= unimproved) return

        lastTurnWorkerDisbanded = civ.gameInfo.turns
        findSeaWorkerToScrap(civ)?.disband()
    }

    /**
     *  CvEconomicAI::FindSeaWorkerToScrap: the first scrappable work boat (a water unit that is no combat unit and creates water improvements:
     *  the original compares the unit type with the civilization's work boat), in the civilization's unit list.
     */
    private fun findSeaWorkerToScrap(civ: Civilization): MapUnit? = civ.units.getCivUnits().firstOrNull {
        it.baseUnit.isWaterUnit && it.isCivilian() && it.cache.hasUniqueToCreateWaterImprovements
    }

    companion object {
        private const val NEED_NAVAL_TILE_IMPROVEMENT = "AICITYSTRATEGY_NEED_NAVAL_TILE_IMPROVEMENT"
        /** CvEconomicAI::DisbandExtraWorkers iWorstCaseWorkerPerCityRatio: one worker for four cities */
        private const val WORST_CASE_WORKERS_PER_CITY_PERCENT = 25
        /** CvEconomicAI::DisbandExtraWorkers: the share of improved tiles up to which no worker is scrapped */
        private const val IMPROVED_PLOTS_PERCENT_TO_DISBAND = 66

        const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"

        /** EconomicAIHelpers::IsPotentialLandExplorer: a land combat unit, not a ranged one with at most 2 moves */
        fun isPotentialLandExplorer(unit: BaseUnit): Boolean =
            unit.isLandUnit && unit.strength > 0 && !(unit.rangedStrength > 0 && unit.movement <= 2)

        /** EconomicAIHelpers::IsPotentialNavalExplorer: ATTACK_SEA, RESERVE_SEA or ASSAULT_SEA (SUBMARINE needs MOD_AI_UNIT_PRODUCTION, off in VP) */
        fun isPotentialNavalExplorer(defaultAi: UnitAiType): Boolean =
            defaultAi == UnitAiType.ATTACK_SEA || defaultAi == UnitAiType.RESERVE_SEA || defaultAi == UnitAiType.ASSAULT_SEA

        /**
         *  CvEconomicAI::GetSoftSupplyCap (Vox Populi): the most land and sea combat units whose maintenance still leaves the minimum
         *  income. The minimum is 2 gold in the Ancient era, 5 to the Medieval era and 10 later, 1 at war (an AI is at war if its
         *  approach to a met major is WAR or it fights one in earnest, not a phony war; a human if it fights anyone), -5 when losing all
         *  wars, which also allows 3 units over the supply. At least 2 units per city, at most the supply less the units that use it but
         *  are not land or sea combat units. Those, the air units and the non-combat units count towards the maintenance, the
         *  maintenance-free units do not.
         *
         *  Rules: the hard cap is our rule's unit supply ([com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.getUnitSupply]),
         *  which only the units with [com.unciv.logic.map.mapunit.MapUnit.usesUnitSupply] use (the MilitarySupport units, not the
         *  workers or settlers). Revenue less the costs other than units is next turn's gold without the unit upkeep, with
         *  the gold per turn of deals counting only as income (CalculateGrossGold takes the positive diplomacy gold, the costs leave out
         *  what we pay).
         */
        fun softSupplyCap(civ: Civilization): Int {
            var hardSupplyCap = civ.stats.getUnitSupply()
            var war = civ.isAtWar()
            if (!civ.isHuman()) {
                war = civ.gameInfo.civilizations.any { other ->
                    other != civ && other.isMajorCiv && civ.knows(other)
                        && (civ.ai.diplomacy.approachTowards(other) == MajorCivApproach.War
                            || civ.isAtWarWith(other) && !WarIntentAi.isPhonyWar(civ, other))
                }
            }

            val rules = civ.gameInfo.ruleset.ai
            var minDesiredGpt = 1
            if (!war) {
                val era = civ.getEraNumber()
                minDesiredGpt = when {
                    era == rules.define(AiDefine.ANCIENT_ERA) -> 2
                    era <= rules.define(AiDefine.MEDIEVAL_ERA) -> 5
                    else -> 10
                }
            } else if (WarStateAi.stateAllWars(civ) == StateAllWars.Losing) {
                minDesiredGpt = -5
                hardSupplyCap += 3
            }

            var revenueLessOtherCosts = 0f
            for ((source, stats) in civ.stats.getStatMapForNextTurn()) when (source) {
                "Unit upkeep" -> {}
                "Trade" -> revenueLessOtherCosts += max(0f, stats.gold)
                else -> revenueLessOtherCosts += stats.gold
            }

            val minUnits = civ.cities.size * 2
            val maintenanceFreeUnits = civ.stats.unitMaintenanceFreeUnits()
            var otherUnits = 0
            for (unit in civ.units.getCivUnits()) {
                val isCombat = unit.isMilitary()
                if (!isCombat || unit.baseUnit.isAirUnit()) otherUnits++
                if (unit.usesUnitSupply() && (!isCombat || !unit.baseUnit.isLandUnit && !unit.baseUnit.isWaterUnit)) hardSupplyCap--
            }

            var softSupplyCap = min(minUnits, hardSupplyCap)
            for (totalUnits in minUnits..hardSupplyCap) {
                val paidUnits = totalUnits + otherUnits - maintenanceFreeUnits
                val unitCost = if (paidUnits > 0) civ.stats.unitCost(paidUnits.toFloat()) else 0
                if (revenueLessOtherCosts.toInt() - unitCost >= minDesiredGpt) softSupplyCap = totalUnits
                else break
            }
            return softSupplyCap
        }

        /** CvGame::getHandicapInfo().GetID(): the index of the game's difficulty (Settler = 0 ... Deity = 7) */
        fun gameHandicapIndex(gameInfo: GameInfo): Int =
            gameInfo.ruleset.difficulties.keys.indexOf(gameInfo.getDifficulty().name).coerceAtLeast(0)
    }
}
