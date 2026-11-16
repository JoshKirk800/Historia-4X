package com.unciv.logic.automation.strategy

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** CvEconomicAI ReconState */
enum class ReconState { Enough, Neutral, Needed }

/**
 *  The part of CvEconomicAI beyond the strategy loop (`civ.ai.economic`): recon state, antiquity sites, worker disbanding.
 *  Plot purchases are in [UseGoldAutomation.doPlotPurchases], the purchase savings in [PurchaseSavings].
 *
 *  Not ported, with the reason: DoHurry (its gold branch is commented out in the original, and the population branch needs
 *  HURRY_POPULATION access that no Brave New World policy or building grants - Unciv has no population rushing either);
 *  DisbandExtraArchaeologists and DoSwapGreatWorks (culture port). The exploration plots and the goody-hut assignments (UpdatePlots) are in [ExplorationAi]/[ExplorationPlots].
 */
class EconomicAi : IsPartOfGameInfoSerialization {
    /** CvEconomicAI::m_iLastTurnWorkerDisbanded, -1 if never */
    var lastTurnWorkerDisbanded = -1
    /** CvEconomicAI::m_iVisibleAntiquitySites, updated by [doAntiquitySites] */
    var visibleAntiquitySites = 0
    /** CvEconomicAI::m_iExplorersDisbanded: land explorers [com.unciv.logic.automation.unit.HomelandAi] disbanded for finding no way home; they count as explorers in [doReconState] for good */
    var explorersDisbanded = 0

    /** CvEconomicAI::m_eReconState, recomputed each turn before the strategy loop (null before the first computation) */
    @Transient var reconState: ReconState? = null
    /** CvEconomicAI::m_eNavalReconState */
    @Transient var navalReconState: ReconState? = null

    fun clone(): EconomicAi {
        val toReturn = EconomicAi()
        toReturn.lastTurnWorkerDisbanded = lastTurnWorkerDisbanded
        toReturn.visibleAntiquitySites = visibleAntiquitySites
        toReturn.explorersDisbanded = explorersDisbanded
        return toReturn
    }

    /** What CvEconomicAI::DoTurn does before its strategy loop: DoReconState and DoAntiquitySites, in one pass over the map */
    fun doTurnBeforeStrategies(civ: Civilization) {
        doReconState(civ)
    }

    /** What CvEconomicAI::DoTurn does after its strategy loop for AI players: DoPlotPurchases, DisbandExtraWorkers */
    fun doTurnAfterStrategies(civ: Civilization) {
        UseGoldAutomation.doPlotPurchases(civ)
        disbandExtraWorkers(civ)
    }

    /**
     *  CvEconomicAI::DoReconState (and DoAntiquitySites, counted in the same pass). Over the tiles the civilization has explored, counts revealed land and revealed coastal water (deep water
     *  too once embarked units may enter the ocean), and the revealed tiles with an unrevealed neighbor (the first such neighbor decides
     *  whether it counts as land or as water fog). The land state compares [reconWeight] of the land counts and the land explorers
     *  (UNITAI_EXPLORE, being trained included, plus [explorersDisbanded]) with 110 - 10 x RECON flavor (at most 100); the naval state, with a coastal city, does
     *  the same for the water counts, the sea explorers and NAVAL_RECON. At ENOUGH the explorers that can attack go back to the attack
     *  role (one land explorer is kept before turn 100).
     */
    fun doReconState(civ: Civilization) {
        if (civ.isCityState) {
            reconState = ReconState.Enough
            navalReconState = ReconState.Enough
            doAntiquitySites(civ)
            return
        }
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val siteResource = constants.archaeologySiteResource
        val hiddenSiteResource = constants.archaeologyHiddenSiteResource
        var antiquitySites = 0
        val rules = civ.gameInfo.ruleset.ai
        val deepWaterToo = civ.tech.embarkedUnitsCanEnterOcean
        UnitAiTypes.pruneOverrides(civ)
        var landRevealed = 1
        var landWithAdjacentFog = 1
        var coastalRevealed = 1
        var coastalWithAdjacentFog = 1
        for (tile in civ.gameInfo.tileMap.values) {
            if (!civ.hasExplored(tile)) continue
            val resource = tile.resource
            if (resource != null && (resource == siteResource || resource == hiddenSiteResource) && civ.canSeeResource(tile.tileResource))
                antiquitySites++
            if (!tile.isWater) landRevealed++
            else if (!tile.isOcean || deepWaterToo) coastalRevealed++
            for (neighbor in tile.neighbors) {
                if (neighbor.isWater) {
                    if ((deepWaterToo || !neighbor.isOcean) && !civ.hasExplored(neighbor)) {
                        coastalWithAdjacentFog++
                        break
                    }
                } else if (!civ.hasExplored(neighbor)) {
                    landWithAdjacentFog++
                    break
                }
            }
        }

        visibleAntiquitySites = antiquitySites

        val landExplorers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.EXPLORE, includeBeingTrained = true, includeWater = false) + explorersDisbanded
        val landWeight = reconWeight(civ, landWithAdjacentFog, landRevealed, landExplorers)
        val landState = reconState(landWeight, reconThreshold(civ, Flavor.Recon))
        reconState = landState
        if (landState == ReconState.Enough) {
            // Keep at least one explorer through turn 100
            var skipFirst = civ.gameInfo.turns < 100
            for (unit in civ.units.getCivUnits()) {
                if (UnitAiTypes.current(unit) != UnitAiType.EXPLORE || !UnitAiTypes.canHave(unit.baseUnit, UnitAiType.ATTACK, rules)) continue
                if (skipFirst) skipFirst = false
                else UnitAiTypes.set(unit, UnitAiType.ATTACK)
            }
        }

        if (civ.cities.none { WaterBodies.isCoastal(it) }) {
            navalReconState = ReconState.Enough
            return
        }
        val seaExplorers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.EXPLORE_SEA, includeBeingTrained = true, includeWater = true)
        val navalWeight = reconWeight(civ, coastalWithAdjacentFog, coastalRevealed, seaExplorers)
        val navalState = reconState(navalWeight, reconThreshold(civ, Flavor.NavalRecon))
        navalReconState = navalState
        if (navalState == ReconState.Enough) {
            // The original keeps one only when NEUTRAL, which cannot be the case here: all go back
            for (unit in civ.units.getCivUnits())
                if (UnitAiTypes.current(unit) == UnitAiType.EXPLORE_SEA && UnitAiTypes.canHave(unit.baseUnit, UnitAiType.ATTACK_SEA, rules))
                    UnitAiTypes.set(unit, UnitAiType.ATTACK_SEA)
        }
    }

    /** CvEconomicAI::DoAntiquitySites: antiquity sites (hidden ones included) on tiles the civilization has revealed ([doReconState] counts them for majors) */
    fun doAntiquitySites(civ: Civilization) {
        visibleAntiquitySites = ArchaeologyFunctions.getVisibleSites(civ).size
    }

    /**
     *  CvEconomicAI::DisbandExtraWorkers: when losing money (LOSING_MONEY) or paying more than 4 gold per unit, scraps one land worker if
     *  there are more than one, more than one per four cities, at least two thirds of the improvable tiles are improved, and more workers
     *  than one per five unimproved tiles (rounded up) plus one per unconnected city on the capital's landmass.
     */
    fun disbandExtraWorkers(civ: Civilization) {
        val inDeficit = civ.ai.isEconomicStrategyActive(LOSING_MONEY)
        if (!inDeficit && civ.stats.getUnitMaintenance() / maxOf(1, civ.units.getCivUnitsSize()) <= 4) return

        val numWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true, includeWater = false)
        val numCities = civ.cities.size
        if (numWorkers / numCities.toDouble() <= 0.25 || numWorkers == 1) return

        var valid = 0
        var improved = 0
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!EconomicAiRatios.isImprovablePlot(tile)) continue
            valid++
            if (tile.improvement != null && !tile.improvementIsPillaged) improved++
        }
        if (valid <= 0) return
        if (improved / valid.toDouble() < 2 / 3.0) return

        val unimproved = valid - improved
        var minWorkers = (unimproved + 4) / 5
        val capital = civ.getCapital(firstCityIfNoCapital = false) ?: return
        val capitalContinent = capital.getCenterTile().getContinent()
        for (city in civ.cities)
            if (city != capital && city.getCenterTile().getContinent() == capitalContinent && !city.isConnectedToCapital()) minWorkers++
        if (numWorkers <= minWorkers) return

        lastTurnWorkerDisbanded = civ.gameInfo.turns
        // CvEconomicAI::FindWorkerToScrap: the first land worker that is not a combat unit
        val rules = civ.gameInfo.ruleset.ai
        val worker = civ.units.getCivUnits().firstOrNull {
            it.baseUnit.isLandUnit && it.isCivilian() && UnitAiTypes.defaultOf(it.baseUnit, rules) == UnitAiType.WORKER
        } ?: return
        worker.disband()
    }

    companion object {
        /**
         *  The recon part of CvCity::CreateUnit, for units AI majors train: under NEED_RECON a unit that can explore (Unit_AITypes) becomes
         *  an explorer unless the threat (total threat weight + barbarian threat) reaches the CRITICAL threat weight; under NEED_RECON_SEA or
         *  REALLY_NEED_RECON_SEA a unit that can explore the sea becomes a sea explorer.
         */
        fun onUnitTrained(unit: MapUnit) {
            val civ = unit.civ
            if (civ.isHuman() || civ.isCityState || civ.isBarbarian) return
            val rules = civ.gameInfo.ruleset.ai
            if (civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON)
                && UnitAiTypes.canHave(unit.baseUnit, UnitAiType.EXPLORE, rules) && UnitAiTypes.current(unit) != UnitAiType.EXPLORE) {
                val military = civ.ai.military
                val threat = military.totalThreatWeight + military.barbarianThreatTotal(civ)
                if (threat < military.threatWeight(civ, AiThreat.Critical)) UnitAiTypes.set(unit, UnitAiType.EXPLORE)
            }
            if ((civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON_SEA)
                    || civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.REALLY_NEED_RECON_SEA))
                && UnitAiTypes.canHave(unit.baseUnit, UnitAiType.EXPLORE_SEA, rules))
                UnitAiTypes.set(unit, UnitAiType.EXPLORE_SEA)
        }

        const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"

        /**
         *  CvEconomicAI::GetSoftSupplyCap (Vox Populi): the most land and sea combat units whose maintenance still leaves the minimum
         *  income. The minimum is 2 gold in the Ancient era, 5 to the Medieval era and 10 later, 1 at war (an AI is at war if its
         *  approach to a met major is WAR or it fights one in earnest, not a phony war; a human if it fights anyone), -5 when losing all
         *  wars, which also allows 3 units over the supply. At least 2 units per city, at most the supply less the units that use it but
         *  are not land or sea combat units. Those, the air units and the non-combat units count towards the maintenance, the
         *  maintenance-free units do not.
         *
         *  Rules: the hard cap is our rule's unit supply ([com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.getUnitSupply]),
         *  under which every unit uses supply. Revenue less the costs other than units is next turn's gold without the unit upkeep, with
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
                if (!isCombat || !unit.baseUnit.isLandUnit && !unit.baseUnit.isWaterUnit) hardSupplyCap--
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

        /** The recon weight of CvEconomicAI::DoReconState: start weight x tiles next to fog / (explorers + divisor)^2 / (int)sqrt(revealed) */
        fun reconWeight(civ: Civilization, withAdjacentFog: Int, revealed: Int, explorers: Int): Int {
            val rules = civ.gameInfo.ruleset.ai
            var weight = rules.define(AiDefine.AI_STRATEGY_EARLY_EXPLORATION_STARTING_WEIGHT) * withAdjacentFog
            val divisor = explorers + rules.define(AiDefine.AI_STRATEGY_EARLY_EXPLORATION_EXPLORERS_WEIGHT_DIVISOR)
            weight /= divisor * divisor
            weight /= sqrt(revealed.toDouble()).toInt()
            return weight
        }

        /** The threshold of CvEconomicAI::DoReconState: 110 - flavor (personality and grand strategy) x 10, at most 100 */
        fun reconThreshold(civ: Civilization, flavor: Flavor): Int =
            min(100, 110 - civ.ai.personalityAndGrandStrategy(flavor) * civ.gameInfo.ruleset.ai.define(AiDefine.AI_STRATEGY_EARLY_EXPLORATION_WEIGHT_PER_FLAVOR))

        private fun reconState(weight: Int, threshold: Int) = when {
            weight > threshold -> ReconState.Needed
            weight > threshold / 4 -> ReconState.Neutral
            else -> ReconState.Enough
        }

        /** CvGame::getHandicapInfo().GetID(): the index of the game's difficulty (Settler = 0 ... Deity = 7) */
        fun gameHandicapIndex(gameInfo: GameInfo): Int =
            gameInfo.ruleset.difficulties.keys.indexOf(gameInfo.getDifficulty().name).coerceAtLeast(0)
    }
}
