package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.strategy.EconomicAi
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.models.ruleset.Building
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 *  Where the AI spends gold, by the rules of the original: plot purchases (CvEconomicAI::DoPlotPurchases), unit upgrades
 *  (CvHomelandAI::PlotUpgradeMoves) and military unit purchases (CvMilitaryAI::BuyEmergencyUnit), each through the purchase savings
 *  ([com.unciv.logic.automation.strategy.PurchaseSavings]). The original never buys the current construction of a city
 *  (CvEconomicAI::DoHurry's gold branch is disabled), so neither does this. City-state gifts are the city-state diplomacy port's.
 */
object UseGoldAutomation {

    //region Unit upgrades (CvHomelandAI::PlotUpgradeMoves)

    /** UPGRADE_THIS_TURN_PRIORITY_BOOST of CvHomelandAI.h */
    private const val UPGRADE_THIS_TURN_PRIORITY_BOOST = 1000
    /** UPGRADE_IN_TERRITORY_PRIORITY_BOOST of CvHomelandAI.h */
    private const val UPGRADE_IN_TERRITORY_PRIORITY_BOOST = 500

    /** The upgrade CvUnit::GetUpgradeUnitType gives (the unit type's normal upgrade), if the tech and the resources allow it */
    private fun upgradeFor(unit: MapUnit): PossibleUpgrade? =
        unit.upgrade.getUpgradesOfKind(isSpecial = false).firstOrNull { it.availability == UpgradeAvailability.Available }

    /** The number of units [PlotUpgradeMoves][plotUpgradeMoves] upgrades per turn: max(1, MILITARY_TRAINING / 3) + max(0, handicap - 5) */
    fun maxUpgradesPerTurn(civ: Civilization): Int =
        max(1, civ.ai.personalityAndGrandStrategy(Flavor.MilitaryTraining) / 3) + max(0, EconomicAi.gameHandicapIndex(civ.gameInfo) - 5)

    /** What [plotUpgradeMoves] did: the units it [upgraded], and the unit that is next in line but could not upgrade now ([waiting]) */
    class UpgradeMoves(val upgraded: Int, val waiting: MapUnit?)

    /**
     *  CvHomelandAI::PlotUpgradeMoves, gold side, over [units] (the units the homeland AI has this turn; the original leaves out human
     *  units and units in an army, [isInArmy]). Units whose upgrade the tech and resources allow are ranked: lowest unit power first,
     *  +[UPGRADE_THIS_TURN_PRIORITY_BOOST] if they can upgrade now (gold, movement, own territory), else +[UPGRADE_IN_TERRITORY_PRIORITY_BOOST]
     *  in own territory. Those that can upgrade now are upgraded in that order, at most [maxUpgradesPerTurn]. If none could, the gold for
     *  the first is reserved at priority AI_GOLD_PRIORITY_UPGRADE_BASE + AI_GOLD_PRIORITY_UPGRADE_PER_FLAVOR_POINT x current MILITARY_TRAINING,
     *  and that unit is returned as [UpgradeMoves.waiting]: bringing it home when it stands outside our territory is the homeland AI's
     *  ([com.unciv.logic.automation.unit.HomelandAi]).
     */
    fun plotUpgradeMoves(civ: Civilization, units: Sequence<MapUnit> = civ.units.getCivUnits(), isInArmy: (MapUnit) -> Boolean = { false }): UpgradeMoves {
        class Candidate(val unit: MapUnit, val upgrade: PossibleUpgrade, val canUpgradeNow: Boolean, val priority: Int)
        val candidates = ArrayList<Candidate>()
        for (unit in units) {
            if (unit.isDestroyed || isInArmy(unit)) continue
            val upgrade = upgradeFor(unit) ?: continue
            val canUpgradeNow = unit.upgrade.canPerformUpgrade(upgrade, isFree = false)
            var priority = UPGRADE_THIS_TURN_PRIORITY_BOOST - unit.baseUnit.getAiPower()
            if (canUpgradeNow) priority += UPGRADE_THIS_TURN_PRIORITY_BOOST
            else if (unit.getTile().getOwner() == civ) priority += UPGRADE_IN_TERRITORY_PRIORITY_BOOST
            candidates.add(Candidate(unit, upgrade, canUpgradeNow, priority))
        }
        // The original cancels the request only when it is not saving for it, which changes nothing: no candidates, nothing to do
        if (candidates.isEmpty()) return UpgradeMoves(0, null)
        candidates.sortByDescending { it.priority } // stable, as std::stable_sort

        val maxUpgrades = maxUpgradesPerTurn(civ)
        var upgraded = 0
        for (candidate in candidates) {
            if (!candidate.canUpgradeNow) continue
            // An earlier upgrade spent gold or took the resources: check again
            val upgrade = upgradeFor(candidate.unit) ?: continue
            if (!candidate.unit.upgrade.canPerformUpgrade(upgrade, isFree = false)) continue
            candidate.unit.upgrade.performUpgrade(upgrade, isFree = false)
            upgraded++
            if (upgraded >= maxUpgrades) return UpgradeMoves(upgraded, null)
        }
        if (upgraded > 0) return UpgradeMoves(upgraded, null)

        val amount = candidates.first().upgrade.goldCostOfUpgrade
        if (amount > 0) {
            val rules = civ.gameInfo.ruleset.ai
            val priority = rules.define(AiDefine.AI_GOLD_PRIORITY_UPGRADE_BASE) +
                rules.define(AiDefine.AI_GOLD_PRIORITY_UPGRADE_PER_FLAVOR_POINT) * civ.ai.flavor(Flavor.MilitaryTraining)
            civ.ai.purchaseSavings.cancelSaveForPurchase(PurchaseType.UnitUpgrade)
            civ.ai.purchaseSavings.startSaveForPurchase(PurchaseType.UnitUpgrade, amount, priority)
        }
        return UpgradeMoves(0, candidates.first().unit)
    }
    //endregion

    //region Plot purchases (CvEconomicAI::DoPlotPurchases)

    /**
     *  The base tile cost of a city - the original's player-level CvPlayer::GetBuyPlotCost, which its tile costs scale by distance.
     *  With plotAcquisitionLikeCivV it is that: [Civilization.getBuyPlotCost]. Otherwise Unciv prices tiles per city
     *  ([com.unciv.logic.city.managers.CityExpansionManager.getGoldCostOfTile]): this is the price of a tile at distance 2, the closest one
     *  that can be bought.
     */
    fun baseTileCost(city: City): Int {
        if (city.civ.gameInfo.ruleset.modOptions.constants.plotAcquisitionLikeCivV) return city.civ.getBuyPlotCost()
        val ringTwoTile = city.getCenterTile().getTilesAtDistance(2).firstOrNull() ?: city.getCenterTile()
        return city.expansion.getGoldCostOfTile(ringTwoTile)
    }

    /**
     *  CvEconomicAI::DoPlotPurchases (Vox Populi): not for city-states, not while at war and losing all wars, not below the treasury cushion
     *  ([treasuryBuffer] + 200). With the balance (gold less the cushion) beyond [currentCost] x the game's era (at least 2) and the cost below
     *  AI_GOLD_BALANCE_TO_HALVE_PLOT_BUY_MINIMUM, the score a tile needs ([minimumPlotBuyScore]) is beaten by the best tile of each city
     *  ([buyPlotScore]) whose cost the purchase savings let go at its score as priority; the best of those is bought, one per turn.
     *  A city's best tile that the savings do not let go starts saving for it at priority 2.
     */
    fun doPlotPurchases(civ: Civilization) {
        if (civ.isCityState) return
        if (civ.ai.isMilitaryStrategyActive(AT_WAR) && WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return
        val buffer = treasuryBuffer(civ) + 200
        if (civ.gold < buffer) return
        val currentCost = civ.cities.minOfOrNull { baseTileCost(it) } ?: return
        var bestScore = minimumPlotBuyScore(civ, currentCost, civ.gold - buffer) ?: return

        val waterAreas = waterAreasOf(civ)
        var bestCity: City? = null
        var bestTile: Tile? = null
        for (city in civ.cities) {
            val (score, tile) = buyPlotScore(city, waterAreas) ?: continue
            val cost = city.expansion.getGoldCostOfTile(tile)
            if (civ.ai.purchaseSavings.canWithdrawMoneyForPurchase(civ, PurchaseType.Tile, cost, score)) {
                if (score > bestScore) {
                    bestCity = city
                    bestTile = tile
                    bestScore = score
                }
            } else civ.ai.purchaseSavings.startSaveForPurchase(PurchaseType.Tile, cost, 2)
        }
        if (bestCity != null && bestTile != null) bestCity.expansion.buyTile(bestTile)
    }

    /**
     *  The cushion CvEconomicAI::DoPlotPurchases and DoHurry keep in the treasury (Vox Populi): AI_GOLD_TREASURY_BUFFER x the game speed's
     *  gold percent x the civilization's era; in debt, 10 x the (negative) income more is taken off; else the income is subtracted (not below 0).
     *  The income is the civilization's gold per turn.
     */
    fun treasuryBuffer(civ: Civilization): Int {
        var buffer = civ.gameInfo.ruleset.ai.define(AiDefine.AI_GOLD_TREASURY_BUFFER)
        buffer = buffer * (civ.gameInfo.speed.goldCostModifier * 100).roundToInt() / 100
        buffer *= civ.getAiEraNumber()
        val goldPerTurn = civ.stats.statsForNextTurn.gold.toInt()
        return if (goldPerTurn < 0) buffer + 10 * goldPerTurn else max(0, buffer - goldPerTurn)
    }

    /**
     *  The score a tile must beat in [doPlotPurchases]: AI_GOLD_PRIORITY_MINIMUM_PLOT_BUY_VALUE (150), lowered by 50% x (balance - cost) /
     *  (AI_GOLD_BALANCE_TO_HALVE_PLOT_BUY_MINIMUM - cost); null (no purchase) unless the cost times the game's era (at least 2) is below the
     *  [balance] and the cost below that gold amount.
     */
    fun minimumPlotBuyScore(civ: Civilization, currentCost: Int, balance: Int): Int? {
        val rules = civ.gameInfo.ruleset.ai
        val goldForHalfCost = rules.define(AiDefine.AI_GOLD_BALANCE_TO_HALVE_PLOT_BUY_MINIMUM)
        val multiplier = max(2, DiplomacyTurn.gameEraNumber(civ))
        if (currentCost * multiplier >= balance || goldForHalfCost <= currentCost) return null
        val minimum = rules.define(AiDefine.AI_GOLD_PRIORITY_MINIMUM_PLOT_BUY_VALUE)
        val discountPercent = 50 * (balance - currentCost) / (goldForHalfCost - currentCost)
        return minimum - minimum * discountPercent / 100
    }

    /**
     *  CvCity::GetBuyPlotScore (Vox Populi): the best tile the city may buy and its [individualPlotScore], -1 for none ([buyPlotScore] returns null).
     *  The tiles are those of CvCity::CanBuyPlot(bIgnoreCost = true): what the civilization can afford is for [doPlotPurchases] to judge.
     */
    fun buyPlotScore(city: City, waterAreas: Lazy<PlotShape.WaterAreas> = waterAreasOf(city.civ)): Pair<Int, Tile>? {
        var bestScore = -1
        var bestTile: Tile? = null
        var scoring: PlotScoring? = null
        for (tile in city.tilesInRange) {
            if (!city.expansion.canBuyTile(tile)) continue
            val plotScoring = scoring ?: PlotScoring(city, waterAreas).also { scoring = it }
            val score = plotScoring.score(tile)
            if (score > bestScore) {
                bestScore = score
                bestTile = tile
            }
        }
        return bestTile?.let { bestScore to it }
    }

    /** The water areas of the map, computed when a plot first needs them */
    private fun waterAreasOf(civ: Civilization) = lazy { PlotShape.WaterAreas(civ.gameInfo.tileMap) }

    /**
     *  CvCity::GetIndividualPlotScore (Vox Populi): a revealed strategic resource 3 x AI_PLOT_VALUE_STRATEGIC_RESOURCE (3 x 80), a luxury
     *  3 x AI_PLOT_VALUE_LUXURY_RESOURCE (3 x 40, x2 if the civilization has none of it); each yield x10 (x20 if it is the city specialization's
     *  yield), x5 if it is the city's most deficient yield (CvCityStrategyAI::GetMostDeficientYield); AI_PLOT_VALUE_STRATEGIC_RESOURCE more for a
     *  plot on a route the builders plan ([com.unciv.logic.automation.unit.PlannedRoutes.bestRouteTypeAndValue]), for a choke point
     *  ([PlotShape.isChokePoint]) and for a water area separator ([PlotShape.isWaterAreaSeparator]); for every major we have a land dispute
     *  with whose nearest city on the tile's landmass is within 10 tiles, (10 - distance) x 6 / 4 / 2 (fierce / strong / weak). Then x the base
     *  tile cost; +50% for each of the plot's neighbours that is ours if a foreign great general stands on it within 3 tiles of the city;
     *  x2 for a natural wonder next to the land of someone else; divided by this tile's cost.
     *
     *  BNW's version had none of the route, choke point, separator, citadel and wonder terms, and 1 x the resource values (50, 40).
     *  Inputs of Vox Populi's version that the BNW rules lack, counted as none: the yield a trait grants for buying a terrain
     *  (Trait_YieldFromTilePurchaseTerrainType: each yield adds that x the yield multiplier / 10) and the trait that lets a civilization buy
     *  tiles others own (Traits.BuyOwnedTiles, America in Vox Populi: x8 / x4 / x2 for a dispute partner's tile, and the value must be positive).
     *  The original's impassable terrain test per team (a team that crosses mountains) is the plain terrain test here.
     */
    fun individualPlotScore(city: City, tile: Tile, waterAreas: Lazy<PlotShape.WaterAreas> = waterAreasOf(city.civ)): Int =
        PlotScoring(city, waterAreas).score(tile)

    /** What [individualPlotScore] needs of a city, computed once for all its tiles */
    private class PlotScoring(val city: City, val waterAreas: Lazy<PlotShape.WaterAreas>) {
        val civ = city.civ
        val rules = civ.gameInfo.ruleset.ai
        val baseCost = baseTileCost(city)
        /** This turn's route plan (the builder AI's [com.unciv.logic.automation.unit.PlannedRoutes]), planned when a plot is first scored */
        val plannedRoutes by lazy { civ.getWorkerAutomation().plannedRoutes }
        /** Multiplier per yield, by index in [YIELDS] */
        val yieldMultipliers = IntArray(YIELDS.size).also { multipliers ->
            val specializationYield = city.ai.specializationDef()?.yieldType
            for ((index, stat) in YIELDS.withIndex()) {
                var multiplier = if (stat.name == specializationYield) rules.define(AiDefine.AI_PLOT_VALUE_SPECIALIZATION_MULTIPLIER)
                    else rules.define(AiDefine.AI_PLOT_VALUE_YIELD_MULTIPLIER)
                if (city.ai.mostDeficientYield == stat) multiplier *= rules.define(AiDefine.AI_PLOT_VALUE_DEFICIENT_YIELD_MULTIPLIER)
                multipliers[index] = multiplier
            }
        }
        /** The majors we have a land dispute with: their dispute multiplier and cities */
        val disputes = civ.gameInfo.civilizations.mapNotNull { other ->
            if (other == civ || !other.isMajorCiv || !other.isAlive()) return@mapNotNull null
            val multiplier = when (civ.ai.diplomacy.landDisputeLevel(other)) {
                DisputeLevel.Fierce -> rules.define(AiDefine.AI_PLOT_VALUE_FIERCE_DISPUTE)
                DisputeLevel.Strong -> rules.define(AiDefine.AI_PLOT_VALUE_STRONG_DISPUTE)
                DisputeLevel.Weak -> rules.define(AiDefine.AI_PLOT_VALUE_WEAK_DISPUTE)
                DisputeLevel.None -> return@mapNotNull null
            }
            multiplier to other.cities
        }

        fun score(tile: Tile): Int {
            var value = 0
            val resource = tile.tileResource
            if (tile.resource != null && civ.canSeeResource(resource)) {
                when (resource.resourceType) {
                    ResourceType.Strategic -> value += rules.define(AiDefine.AI_PLOT_VALUE_STRATEGIC_RESOURCE) * 3
                    ResourceType.Luxury -> {
                        var luxuryValue = rules.define(AiDefine.AI_PLOT_VALUE_LUXURY_RESOURCE) * 3
                        if (civ.getResourceAmount(resource) == 0) luxuryValue *= 2
                        value += luxuryValue
                    }
                    else -> {}
                }
            }
            val natureYields = tile.stats.getTileStats(null, civ)
            for ((index, stat) in YIELDS.withIndex()) value += natureYields[stat].toInt() * yieldMultipliers[index]

            // A route the builders plan, a choke point and a water area separator are each worth a strategic resource's base value
            val strategicValue = rules.define(AiDefine.AI_PLOT_VALUE_STRATEGIC_RESOURCE)
            if (plannedRoutes.bestRouteTypeAndValue(tile) != null) value += strategicValue
            if (PlotShape.isChokePoint(tile)) value += strategicValue
            if (PlotShape.isWaterAreaSeparator(tile, waterAreas.value)) value += strategicValue

            for ((multiplier, cities) in disputes) {
                var nearest = Int.MAX_VALUE
                for (other in cities) {
                    val center = other.getCenterTile()
                    if (center.getContinent() != tile.getContinent()) continue
                    nearest = minOf(nearest, center.aerialDistanceTo(tile))
                }
                if (nearest < 10) value += (10 - nearest) * multiplier
            }

            val cost = city.expansion.getGoldCostOfTile(tile)
            if (cost == 0) return 0
            value *= baseCost

            // Protect against citadels: a foreign great general within 3 tiles of the city, +50% for each neighbour of the plot that is ours
            if (city.getCenterTile().aerialDistanceTo(tile) < 4 &&
                tile.getUnits().any { it.civ != civ && UnitAiTypes.current(it) == UnitAiType.GENERAL }) {
                val bonus = 50 * tile.neighbors.count { it.getOwner() == civ }
                value += value * bonus / 100
            }
            // Try and snatch up natural wonders next to the land of someone else
            if (tile.isNaturalWonder() && tile.neighbors.any { val owner = it.getOwner(); owner != null && owner != civ }) value *= 2
            return value / cost
        }
    }

    /** The original's yields (YieldTypes): Unciv stats without happiness */
    private val YIELDS = Stat.entries.filter { it != Stat.Happiness }
    //endregion

    //region Military purchases (CvMilitaryAI::BuyEmergencyUnit)

    /**
     *  CvMilitaryAI::BuyEmergencyUnit: buys the unit CvUnitProductionAI::RecommendUnit picks for [unitAi] in [city] - with gold if the
     *  purchase savings let its cost go at AI_GOLD_PRIORITY_UNIT, else with faith if it can be bought with faith. Not in puppets
     *  (unless the civilization may buy in puppets, the original's Venice exception). The unit has no movement left.
     *  The unit is an extra one: it does not fill a slot its city committed to (the original's CreateUnit without bUseToSatisfyOperation).
     *  @return the unit bought, null if none
     */
    fun buyEmergencyUnit(civ: Civilization, unitAi: UnitAiType, city: City): MapUnit? {
        val unitType = ProductionAi.recommendUnit(city, unitAi) ?: return null
        val constructions = city.cityConstructions
        val goldCost = unitType.getStatBuyCost(city, Stat.Gold)
        if (goldCost != null && constructions.isConstructionPurchaseAllowed(unitType, Stat.Gold, goldCost)) {
            val priority = civ.gameInfo.ruleset.ai.define(AiDefine.AI_GOLD_PRIORITY_UNIT)
            if (civ.ai.purchaseSavings.canWithdrawMoneyForPurchase(civ, PurchaseType.Unit, goldCost, priority))
                return purchase(city, unitType, Stat.Gold)
        }
        val faithCost = unitType.getStatBuyCost(city, Stat.Faith)
        if (faithCost != null && constructions.isConstructionPurchaseAllowed(unitType, Stat.Faith, faithCost))
            return purchase(city, unitType, Stat.Faith)
        return null
    }

    /**
     *  CvMilitaryAI::BuyEmergencyBuilding: buys every building [city] can build now that adds to its defense (the original's DefenseModifier,
     *  the city strength a building gives: walls, castle, ...), each for gold if the purchase savings let its cost go at
     *  AI_GOLD_PRIORITY_DEFENSIVE_BUILDING. Not in puppets (unless the civilization may buy there).
     *  @return the buildings bought
     */
    fun buyEmergencyBuilding(civ: Civilization, city: City): List<Building> {
        val bought = ArrayList<Building>()
        val constructions = city.cityConstructions
        val priority = civ.gameInfo.ruleset.ai.define(AiDefine.AI_GOLD_PRIORITY_DEFENSIVE_BUILDING)
        for (building in civ.gameInfo.ruleset.buildings.values) {
            if (building.cityStrength <= 0.0 || !building.isBuildable(constructions)) continue
            val goldCost = building.getStatBuyCost(city, Stat.Gold) ?: continue
            if (!constructions.isConstructionPurchaseAllowed(building, Stat.Gold, goldCost)) continue
            if (!civ.ai.purchaseSavings.canWithdrawMoneyForPurchase(civ, PurchaseType.Building, goldCost, priority)) continue
            if (constructions.purchaseConstruction(building, -1, true, Stat.Gold)) {
                AiDecisionLog.log(civ) { "Emergency building bought: ${building.name} in ${city.name} for $goldCost" }
                bought.add(building)
            }
        }
        return bought
    }

    private fun purchase(city: City, unitType: BaseUnit, stat: Stat): MapUnit? {
        val before = city.civ.units.getCivUnitsSize()
        if (!city.cityConstructions.purchaseConstruction(unitType, -1, true, stat, useToSatisfyOperation = false)) return null
        if (city.civ.units.getCivUnitsSize() <= before) return null
        val unit = city.civ.units.getCivUnits().last()
        unit.currentMovement = 0f
        return unit
    }
    //endregion

    private const val AT_WAR = "MILITARYAISTRATEGY_AT_WAR"
}
