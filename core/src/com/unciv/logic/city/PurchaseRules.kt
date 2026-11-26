package com.unciv.logic.city

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 *  The turns a city has to wait before it can buy a unit again (CvCity::m_iUnitPurchaseCooldown, `...Civilian`, `m_iUnitFaithPurchaseCooldown`, `...Civilian`),
 *  only with [ModConstants.purchaseCooldownEnabled]. Saved with the city; a saved game from before them has them all 0.
 */
class PurchaseCooldowns : IsPartOfGameInfoSerialization {
    /** Gold, military units */
    var gold = 0
    /** Gold, civilian units */
    var goldCivilian = 0
    /** Faith, military units */
    var faith = 0
    /** Faith, civilian units */
    var faithCivilian = 0

    fun clone(): PurchaseCooldowns {
        val toReturn = PurchaseCooldowns()
        toReturn.gold = gold
        toReturn.goldCivilian = goldCivilian
        toReturn.faith = faith
        toReturn.faithCivilian = faithCivilian
        return toReturn
    }

    @Readonly fun ofGold(civilian: Boolean) = if (civilian) goldCivilian else gold
    @Readonly fun ofFaith(civilian: Boolean) = if (civilian) faithCivilian else faith
}

/**
 *  What Vox Populi's economy adds to buying things with gold and faith, and to what buildings cost to build: the gold price formula, the investment in a building
 *  instead of buying it, the cooldown after buying a unit, the building a unit needs to be bought, the world wonder cost increase and the discount of buildings
 *  of earlier eras. Every rule is behind a [ModConstants] switch or a number whose default changes nothing, so Brave New World plays as it did.
 *
 *  `V:` is `CvGameCoreDLL_Expansion2` of Vox Populi 5.4.8.
 */
object PurchaseRules {

    //region Price

    /**
     *  CvGameCoreUtils.cpp: GetBasePurchaseCostFromProduction (V:CvGameCoreUtils.cpp:1265-1277): `(production x goldPerProduction) ^ exponent` times the
     *  speed's hurry percentage. The source truncates the power to a whole number before the percentage; this keeps the fraction (see [ModConstants.PurchaseConstants]).
     */
    @Readonly
    fun baseGoldPrice(production: Int, constants: ModConstants, speed: Speed): Double {
        val purchase = constants.purchase
        return (purchase.goldPerProduction * production).pow(purchase.exponent) * speed.hurryModifier
    }

    /** What the price of a construction is rounded down to a multiple of (`GOLD_PURCHASE_VISIBLE_DIVISOR`) */
    @Readonly
    fun visibleDivisor(constants: ModConstants): Int = max(1, constants.purchase.visibleDivisor)

    //endregion
    //region Production cost rules

    /**
     *  The percent that earlier eras take off the production cost of [building] for [civ] (CvPlayer::getProductionNeeded, V:CvPlayer.cpp:15395-15450):
     *  the sum of [com.unciv.models.ruleset.tech.Era.laterEraBuildingConstructMod] of the eras from the era of the building's technology up to, not including,
     *  the civilization's. A building without a technology counts every era before the civilization's. 0 when no era has the modifier (Brave New World).
     */
    @Readonly
    fun laterEraCostPercent(civ: Civilization, building: Building): Int {
        val ruleset = civ.gameInfo.ruleset
        val currentEra = civ.getEraNumber()
        val buildingEra = building.requiredTech?.let { tech -> ruleset.technologies[tech]?.let { ruleset.eras[it.era()]?.eraNumber } } ?: 0
        if (buildingEra >= currentEra) return 0
        return ruleset.eras.values.sumOf { if (it.eraNumber in buildingEra until currentEra) it.laterEraBuildingConstructMod else 0 }
    }

    /**
     *  The percent a world wonder costs [civ] more for the world wonders it owns (CvPlayer::getProductionNeeded with MOD_BALANCE_WORLD_WONDER_COST_INCREASE,
     *  V:CvPlayer.cpp:15337-15380, applied at 15492): every world wonder in its cities that has a technology adds the entry of
     *  [ModConstants.worldWonderCostIncrease] for how many eras ago the era of that technology was (the last entry for the earlier ones, and for an era not yet reached).
     *  0 for anything that is not a world wonder, or without the constant.
     */
    @Readonly
    fun worldWonderCostPercent(civ: Civilization, building: Building): Int {
        val increase = civ.gameInfo.ruleset.modOptions.constants.worldWonderCostIncrease
        if (increase.isEmpty() || !building.isWonder) return 0
        val ruleset = civ.gameInfo.ruleset
        val currentEra = civ.getEraNumber()
        var percent = 0
        for (city in civ.cities) for (owned in city.cityConstructions.getBuiltBuildings()) {
            if (!owned.isWonder) continue
            val tech = owned.requiredTech?.let { ruleset.technologies[it] } ?: continue
            val wonderEra = ruleset.eras[tech.era()]?.eraNumber ?: continue
            val eraDifference = currentEra - wonderEra
            percent += increase[if (eraDifference in increase.indices) eraDifference else increase.lastIndex]
        }
        return percent
    }

    //endregion
    //region Investments

    /**
     *  The production that investing in [building] takes off its cost: CvCity::SetBuildingInvestment (V:CvCity.cpp:11661-11700). The cost before the investment falls to
     *  (100 + [ModConstants.buildingInvestmentBaseline]) percent of itself, half of that percentage (divided by [ModConstants.buildingInvestmentWonderDivisor]) for a
     *  world wonder, but never below what the city has put into it plus 1; 0 if that would be more.
     */
    @Readonly
    fun investmentReduction(city: City, building: Building): Int {
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        val needed = building.getProductionCostBeforeInvestment(city.civ, city)
        var discountPercent = constants.buildingInvestmentBaseline
        if (building.isWonder) discountPercent /= max(1, constants.buildingInvestmentWonderDivisor)
        val after = max(needed * (100 + discountPercent) / 100, city.cityConstructions.getWorkDone(building.name) + 1)
        return max(0, needed - after)
    }

    /**
     *  Whether the city may invest in [building] now (CvCity::GetPurchaseCost, V:CvCity.cpp:12225-12232, and CvCity::IsCanPurchase, 30757-30766): not when it is invested in,
     *  and not when the city finishes it next turn anyway. The turns left are those of the production the city makes now, not of what it makes building [building]
     *  (the source asks `getProductionTurnsLeft(building, 0)`), and what the city has put into it counts only if it is at the front of the queue or not in it.
     */
    @Readonly
    fun canInvest(city: City, building: Building): Boolean {
        val constructions = city.cityConstructions
        if (constructions.isInvestedIn(building.name)) return false
        val queueIndex = constructions.constructionQueue.indexOf(building.name)
        val stored = if (queueIndex <= 0) constructions.getWorkDone(building.name) else 0
        val left = building.getProductionCost(city.civ, city) - stored - constructions.productionOverflow
        return left > city.cityStats.currentCityStats.production.roundToInt()
    }

    //endregion
    //region Cooldowns and requirements of unit purchases

    /** Whether CvUnitEntry's combat strengths both are 0: the units that the civilian cooldown counts (V:CvCity.cpp:31099) */
    @Readonly
    fun isCivilian(unit: BaseUnit): Boolean = unit.strength <= 0 && unit.rangedStrength <= 0

    /** The turns the buildings of [city] take off its gold cooldown of military ([civilian] false) or civilian units (CvCity::GetUnitPurchaseCooldownMod) */
    @Readonly
    fun cooldownReduction(city: City, civilian: Boolean): Int = city.cityConstructions.getBuiltBuildings()
        .sumOf { if (civilian) it.purchaseCooldownReductionCivilian else it.purchaseCooldownReduction }

    /**
     *  Whether a cooldown keeps [city] from buying [unit] with [stat] now (CvCity::IsCanPurchase, V:CvCity.cpp:30789-30800 for gold, 30976-31000 for faith). Gold:
     *  the counter of the unit's kind less the reduction of the city's buildings is above 0. Faith: the civilization's counter is above 0 and the unit has a global faith
     *  cooldown, or the unit has a local one and the city's counter of its kind is above 0.
     */
    @Readonly
    fun isOnCooldown(city: City, unit: BaseUnit, stat: Stat): Boolean {
        if (!city.civ.gameInfo.ruleset.modOptions.constants.purchaseCooldownEnabled) return false
        val civilian = isCivilian(unit)
        return when (stat) {
            Stat.Gold -> city.purchaseCooldowns.ofGold(civilian) - cooldownReduction(city, civilian) > 0
            Stat.Faith ->
                (city.civ.faithPurchaseCooldown > 0 && unit.globalFaithPurchaseCooldown > 0) ||
                    (unit.localFaithPurchaseCooldown > 0 && city.purchaseCooldowns.ofFaith(civilian) > 0)
            else -> false
        }
    }

    /** The building [city] lacks to buy [unit] with gold: [UniqueType.CanOnlyBePurchasedInCitiesWith] (V:CvCity.cpp:30802-30815), or null if it has them all */
    @Readonly
    fun missingPurchaseBuilding(city: City, unit: BaseUnit): String? =
        unit.getMatchingUniques(UniqueType.CanOnlyBePurchasedInCitiesWith, city.state)
            .map { it.params[0] }
            .firstOrNull { !city.cityConstructions.containsBuildingOrEquivalent(it) }

    /**
     *  Whether the purchase rules keep [city] from buying [construction] with [stat] at all, as the gold rules of CvCity::IsCanPurchase do: a unit on cooldown, a unit
     *  whose building is missing (gold only), a building that is invested in already or that is finished next turn (gold only, with investments).
     */
    @Readonly
    fun isBlocked(city: City, construction: INonPerpetualConstruction, stat: Stat): Boolean {
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        return when (construction) {
            is BaseUnit -> isOnCooldown(city, construction, stat) || (stat == Stat.Gold && missingPurchaseBuilding(city, construction) != null)
            is Building -> stat == Stat.Gold && constants.buildingInvestments && !canInvest(city, construction)
            else -> false
        }
    }

    /**
     *  Sets the cooldowns of [city] after it bought [unit] with [stat] (CvCity::PurchaseUnit: V:CvCity.cpp:31100 for gold, 31121-31135 for faith): gold sets the counter of the
     *  unit's kind to its cooldown less the city's reduction (so the reduction counts twice against it, in this function and in [isOnCooldown], as in the source), faith
     *  adds the global cooldown to the civilization's counter and the local one to the city's, both times the game speed's production cost modifier.
     */
    fun afterPurchase(city: City, unit: BaseUnit, stat: Stat) {
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        if (!constants.purchaseCooldownEnabled) return
        val civilian = isCivilian(unit)
        when (stat) {
            Stat.Gold -> {
                val cooldown = unit.purchaseCooldown - cooldownReduction(city, civilian)
                if (civilian) city.purchaseCooldowns.goldCivilian = cooldown else city.purchaseCooldowns.gold = cooldown
            }
            Stat.Faith -> {
                val speed = city.civ.gameInfo.speed.productionCostModifier
                if (unit.globalFaithPurchaseCooldown > 0)
                    city.civ.faithPurchaseCooldown += (unit.globalFaithPurchaseCooldown * speed).toInt()
                if (unit.localFaithPurchaseCooldown > 0) {
                    val cooldown = (unit.localFaithPurchaseCooldown * speed).toInt()
                    if (civilian) city.purchaseCooldowns.faithCivilian += cooldown else city.purchaseCooldowns.faith += cooldown
                }
            }
            else -> {}
        }
    }

    /** CvCity::doTurn (V:CvCity.cpp:2485-2500): every counter above 0 falls by one */
    fun tickCooldowns(city: City) {
        val cooldowns = city.purchaseCooldowns
        if (cooldowns.gold > 0) cooldowns.gold--
        if (cooldowns.goldCivilian > 0) cooldowns.goldCivilian--
        if (cooldowns.faith > 0) cooldowns.faith--
        if (cooldowns.faithCivilian > 0) cooldowns.faithCivilian--
    }

    //endregion
}
