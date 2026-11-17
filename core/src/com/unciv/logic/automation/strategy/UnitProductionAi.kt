package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.max

/**
 *  The military refusals of Vox Populi's CvUnitProductionAI::CheckUnitBuildSanity (CvUnitProductionAI.cpp 172-612), for the units a city
 *  chooses to train ([com.unciv.logic.automation.city.ConstructionAutomation]): bForPurchase and bFree are false.
 *
 *  Not here yet, with the reason: the weights (the flavor weight's sqrt(10 x weight), the bonuses of the land and sea need, war types,
 *  resources and unit types; with VP's building weights, which they are balanced against), the civilian refusals (lines 905 on: settlers,
 *  archaeologists, workers, trade units, great people), the purchase and free-unit callers, the Aluminum kept for the spaceship
 *  (GetNumAluminumStillNeededForSpaceship not ported).
 */
object UnitProductionAi {

    /**
     *  Whether CheckUnitBuildSanity refuses [unit] in [city] (one of its SR_ results), [forOperation] when the unit fills an operation's
     *  slot. In order:
     *  - a city under siege trains combat units only;
     *  - a city with fewer than 2 buildings trains none, unless under siege;
     *  - no unit whose upgrade some city of ours can train;
     *  - unless under siege, no combat unit while the average unit upkeep would bankrupt us within 9 turns ([WarStateAi.turnsToBankruptcy]);
     *  - a combat land or sea unit needs a passable tile of its domain without a combat unit within 2 tiles of the city when the city has
     *    a garrison;
     *  - unit supply: no land combat unit (explorers aside) once the land combat units, ours and those our other cities are training,
     *    less the explorers, reach the recommended army ([MilitaryAi.recommendedLandArmySize]); no sea combat unit once they reach the
 *    recommended navy; no explorer (default unit AI EXPLORE) once the explorers, those in training included, reach the recommended
 *    explorers ([MilitaryAi.recommendedExplorers]);
     *  - city-states: no combat unit once there are more than max(4, (era + 3) x cities), no ship at the recommended navy, no land unit
     *    at the recommended army;
     *  - no land combat unit, outside an operation, in a city on a landmass of at most 3 tiles that a land unit garrisons;
     *  - majors: carriers only for an operation or when air units lack carrier space ([EconomicMilitaryStrategyTests.needAirCarriers]);
     *    combat ships only in cities connected to the ocean ([isConnectedToOcean]); bombers not while they outnumber fighters 3 to 2,
     *    nor fighters bombers.
     */
    fun isRefused(city: City, unit: BaseUnit, forOperation: Boolean): Boolean {
        val civ = city.civ
        val rules = civ.gameInfo.ruleset.ai
        civ.ai.military.ensureData(civ)
        val combat = unit.isMilitary
        val desperate = CityDangerAi.isUnderSiege(city)
        if (desperate && !combat) return true
        if (city.cityConstructions.getBuiltBuildings().count() < 2 && !desperate) return true
        if (upgradeTrainableAnywhere(civ, unit)) return true
        if (!desperate && combat) {
            val averageGoldPerUnit = civ.stats.getUnitMaintenance() / max(1, civ.units.getCivUnitsSize())
            if (WarStateAi.turnsToBankruptcy(civ, averageGoldPerUnit) < 9) return true
        }
        if (combat && !unit.isAirUnit() && city.getCenterTile().militaryUnit != null && !hasRoomNearby(city, unit)) return true

        val unitAi = UnitAiTypes.defaultOf(unit, rules)
        var numExplorers = UnitAiTypes.numUnitsWithDefaultUnitAi(civ, UnitAiType.EXPLORE, includeBeingTrained = true)
        var numLandUnits = civ.units.getCivUnits().count { it.isMilitary() && it.baseUnit.isLandUnit }
        var numSeaUnits = civ.units.getCivUnits().count { it.isMilitary() && it.baseUnit.isWaterUnit }
        for (other in civ.cities) {
            val training = other.cityConstructions.getCurrentConstruction() as? BaseUnit ?: continue
            if (!training.isMilitary) continue
            if (training.isLandUnit) numLandUnits++ else if (training.isWaterUnit) numSeaUnits++
        }
        val current = city.cityConstructions.getCurrentConstruction() as? BaseUnit
        if (current != null) when {
            current.isLandUnit && UnitAiTypes.defaultOf(current, rules) == UnitAiType.EXPLORE -> numExplorers--
            current.isLandUnit && current.isMilitary -> numLandUnits--
            current.isWaterUnit && current.isMilitary -> numSeaUnits--
        }
        val numTotalUnits = numLandUnits + numSeaUnits
        numLandUnits -= numExplorers

        val military = civ.ai.military
        if (combat) {
            if (unit.isLandUnit && unitAi != UnitAiType.EXPLORE && numLandUnits >= military.recommendedLandArmySize) return true
            if (unit.isWaterUnit && numSeaUnits >= military.recommendedNavySize) return true
            if (unitAi == UnitAiType.EXPLORE && numExplorers >= military.recommendedExplorers) return true
        }

        if (civ.isCityState && combat) {
            if (numTotalUnits > max(4, (civ.getEraNumber() + 3) * max(1, civ.cities.size))) return true
            if (unit.isWaterUnit && numSeaUnits >= military.recommendedNavySize) return true
            if (unit.isLandUnit && numLandUnits >= military.recommendedLandArmySize) return true
        }

        if (combat && unit.isLandUnit && !forOperation) {
            val centerTile = city.getCenterTile()
            if ((civ.gameInfo.tileMap.continentSizes[centerTile.getContinent()] ?: Int.MAX_VALUE) <= 3
                && centerTile.militaryUnit?.baseUnit?.isLandUnit == true) return true
        }

        if (civ.isCityState) return false
        if (unitAi == UnitAiType.CARRIER_SEA && !forOperation && !EconomicMilitaryStrategyTests.needAirCarriers(civ)) return true
        if (unit.isWaterUnit && combat && !isConnectedToOcean(city)) return true
        if (unit.isAirUnit()) {
            val bombers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.ATTACK_AIR, includeBeingTrained = true, includeWater = true)
            val fighters = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.DEFENSE_AIR, includeBeingTrained = true, includeWater = true)
            if (unitAi == UnitAiType.ATTACK_AIR && bombers * 2 > fighters * 3) return true
            if (unitAi == UnitAiType.DEFENSE_AIR && fighters * 2 > bombers * 3) return true
        }
        return false
    }

    /** CvPlayer::GetUnitClassTrainingAllowedAnywhere against CvUnitEntry::GetUpgradeUnitClass: our version of an upgrade of [unit] can be trained in one of our cities */
    private fun upgradeTrainableAnywhere(civ: Civilization, unit: BaseUnit): Boolean =
        unit.getRulesetUpgradeUnits(civ.state).any { upgrade ->
            val ours = civ.getEquivalentUnit(upgrade)
            civ.cities.any { ours.isBuildable(it.cityConstructions) }
        }

    /**
     *  The no-place-to-put-them check: a tile within 2 of the city, of the unit's domain (water for ships, land for land units), that we may
     *  move onto (CvPlot::isValidMovePlot: passable or on an intact road; not the territory of a major that closes its borders to us and is
     *  not at war with us) and that holds no combat unit.
     */
    private fun hasRoomNearby(city: City, unit: BaseUnit): Boolean {
        val civ = city.civ
        return city.getCenterTile().getTilesInDistance(2).any { tile ->
            tile.militaryUnit == null && isValidMovePlot(civ, tile)
                && (tile.isWater && unit.isWaterUnit || tile.isLand && unit.isLandUnit)
        }
    }

    private fun isValidMovePlot(civ: Civilization, tile: Tile): Boolean {
        if (tile.isImpassible() && (tile.roadStatus == RoadStatus.None || tile.roadIsPillaged)) return false
        val owner = tile.getOwner() ?: return true
        return owner == civ || owner.isCityState || civ.diplomacyFunctions.canPassThroughTiles(owner)
    }

    /**
     *  CvCity::IsConnectedToOcean (UpdateConnectedToOcean): a water body next to the city of more than 50 tiles, or one other civilizations
     *  have cities on as well
     */
    fun isConnectedToOcean(city: City): Boolean {
        val bodies = WaterBodies.of(city.civ)
        val adjacent = city.getCenterTile().neighbors.filter { it.isWater }.map { bodies.bodyOf(it) }.toSet()
        if (adjacent.any { bodies.size(it) > 50 }) return true
        return city.civ.gameInfo.civilizations.any { other ->
            other != city.civ && other.cities.any { otherCity -> otherCity.getCenterTile().neighbors.any { it.isWater && bodies.bodyOf(it) in adjacent } }
        }
    }
}
