package com.unciv.ui.screens.cityscreen

import com.unciv.logic.city.City
import com.unciv.logic.city.PurchaseRules
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.NeedsRow
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.max

/**
 *  What the city screen tells about buying a construction under Vox Populi's economy rules ([PurchaseRules]): the investment in a building, the reduced production cost
 *  after it, the cooldown before a unit can be bought again and the building that a unit needs to be bought, as plain data that the screens draw ([NeedsRow]) and tests read
 *  without a screen. Everything is empty or false for a ruleset without the investments, cooldowns and unit requirements (the ModConstants `buildingInvestments`,
 *  `purchaseCooldownEnabled` and the unique `Can only be purchased in cities with [buildingName]`), so what the original showed stays as it was.
 */
object ConstructionPurchaseRows {
    private const val INVESTED = "Invested: production cost [cost] instead of [before]"
    private const val CAN_INVEST = "Investing [price] [Gold] lowers the production cost from [before] to [after]"
    private const val CANNOT_INVEST = "The building is finished next turn anyway: no investment possible"
    private const val COOLDOWN = "Purchase cooldown with [stat]: [turns] turns left"
    private const val MISSING_BUILDING = "Can only be purchased in cities with [building]"

    /** Whether buying [construction] with [stat] is an investment in it (CvCity::PurchaseBuilding with `MOD_BALANCE_BUILDING_INVESTMENTS`): a building bought with gold, with the ModConstants `buildingInvestments` */
    @Readonly
    fun isInvestment(city: City, construction: INonPerpetualConstruction, stat: Stat): Boolean =
        construction is Building && stat == Stat.Gold && city.civ.gameInfo.ruleset.modOptions.constants.buildingInvestments

    /**
     *  The turns [city] has to wait before it can buy [unit] with [stat] (CvCity::IsCanPurchase, V:CvCity.cpp:30789-30800 for gold, 30976-31000 for faith): gold the counter of the
     *  unit's kind less what the city's buildings take off, which the city can buy again at 0; faith the longer of the civilization's counter (if the unit has a global
     *  faith cooldown) and the city's (if it has a local one). 0 when nothing keeps the city from buying it.
     */
    @Readonly
    fun cooldownTurnsLeft(city: City, unit: BaseUnit, stat: Stat): Int {
        if (!PurchaseRules.isOnCooldown(city, unit, stat)) return 0
        val civilian = PurchaseRules.isCivilian(unit)
        return when (stat) {
            Stat.Gold -> city.purchaseCooldowns.ofGold(civilian) - PurchaseRules.cooldownReduction(city, civilian)
            Stat.Faith -> max(
                if (unit.globalFaithPurchaseCooldown > 0) city.civ.faithPurchaseCooldown else 0,
                if (unit.localFaithPurchaseCooldown > 0) city.purchaseCooldowns.ofFaith(civilian) else 0
            )
            else -> 0
        }
    }

    /** The production cost of [building] in [city] after an investment in it now: its cost before, less what [PurchaseRules.investmentReduction] takes off, at least 1 (Building.getProductionCost) */
    @Readonly
    private fun costAfterInvestment(city: City, building: Building): Int {
        val before = building.getProductionCostBeforeInvestment(city.civ, city)
        val reduction = PurchaseRules.investmentReduction(city, building)
        return if (reduction == 0) before else max(1, before - reduction)
    }

    /**
     *  Why the purchase rules keep [city] from buying [construction] with [stat] (the part of CvCity::IsCanPurchase that [PurchaseRules.isBlocked] tests), a translatable sentence
     *  with its numbers in brackets, or null if they do not. The first of: the cooldown of a unit with its turns left, the building a unit needs, the investment a building
     *  has been made already, a building that is finished next turn anyway.
     */
    @Readonly
    fun blockReason(city: City, construction: INonPerpetualConstruction, stat: Stat): String? {
        if (!PurchaseRules.isBlocked(city, construction, stat)) return null
        return when (construction) {
            is BaseUnit -> {
                val turns = cooldownTurnsLeft(city, construction, stat)
                if (turns > 0) COOLDOWN.replace("[stat]", "[${stat.name}]").replace("[turns]", "[$turns]")
                else MISSING_BUILDING.replace("[building]", "[${PurchaseRules.missingPurchaseBuilding(city, construction)}]")
            }
            is Building ->
                if (city.cityConstructions.isInvestedIn(construction.name)) invested(city, construction)
                else CANNOT_INVEST
            else -> null
        }
    }

    @Readonly
    private fun invested(city: City, building: Building): String =
        INVESTED.replace("[cost]", "[${building.getProductionCost(city.civ, city)}]")
            .replace("[before]", "[${building.getProductionCostBeforeInvestment(city.civ, city)}]")

    /**
     *  The lines under the description of the selected [construction] in [city]: for a building with the investments the investment it has, or the price of one and the
     *  production cost it brings (the price is the one of the buy button, [INonPerpetualConstruction.getStatBuyCost]); for a unit the cooldowns that keep the city from buying it
     *  and the building it lacks. Empty if there is nothing to say.
     */
    @Readonly
    fun rows(city: City, construction: INonPerpetualConstruction): List<NeedsRow> {
        @LocalState val rows = ArrayList<NeedsRow>()
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        when (construction) {
            is Building -> if (constants.buildingInvestments && construction.canBePurchasedWithStat(city, Stat.Gold)) {
                val constructions = city.cityConstructions
                if (constructions.isInvestedIn(construction.name)) {
                    rows += NeedsRow("Investment", "-${constructions.getInvestmentReduction(construction)}")
                    rows += NeedsRow(invested(city, construction), style = NeedsRow.Style.Note)
                } else if (PurchaseRules.canInvest(city, construction)) {
                    val price = construction.getStatBuyCost(city, Stat.Gold)
                    if (price != null)
                        rows += NeedsRow(CAN_INVEST.replace("[price]", "[$price]")
                            .replace("[before]", "[${construction.getProductionCostBeforeInvestment(city.civ, city)}]")
                            .replace("[after]", "[${costAfterInvestment(city, construction)}]"), style = NeedsRow.Style.Note)
                } else rows += NeedsRow(CANNOT_INVEST, style = NeedsRow.Style.Note)
            }
            is BaseUnit -> {
                for (stat in listOf(Stat.Gold, Stat.Faith)) {
                    val turns = cooldownTurnsLeft(city, construction, stat)
                    if (turns > 0)
                        rows += NeedsRow(COOLDOWN.replace("[stat]", "[${stat.name}]").replace("[turns]", "[$turns]"), isAlert = true)
                }
                val missing = PurchaseRules.missingPurchaseBuilding(city, construction)
                if (missing != null) rows += NeedsRow(MISSING_BUILDING.replace("[building]", "[$missing]"), isAlert = true)
            }
        }
        return rows
    }
}
