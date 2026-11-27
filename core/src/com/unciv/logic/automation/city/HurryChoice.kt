package com.unciv.logic.automation.city

import com.unciv.logic.automation.WeightedChoice
import com.unciv.logic.automation.strategy.BuildingProductionAi
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.UnitProductionAi
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.city.City
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import kotlin.math.max

/**
 *  The item a city would like to buy with gold: CvCityStrategyAI::ChooseHurry (CvCityStrategyAI.cpp), called per city by
 *  CvEconomicAI::DoHurry, which orders the cities' choices by [Candidate.score] and buys them; units and buildings. The faith mode and the
 *  "units only" flag of ChooseHurry serve [com.unciv.logic.automation.civilization.ReligionAutomation] (see [choose]).
 *
 *  As the original:
 *  - candidates (m_BuildablesPrecheck): the unit for the next open slot of an operation ([Kind.Operation], CvCity::GetUnitForOperation,
 *    weight AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT + AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER x (OFFENSE + the times such a
 *    build was skipped over) + the unit's own weight; offering it counts one more skip, BumpNumberOfTimesOpsBuildSkippedOver), the unit
 *    for an attack army ([Kind.Army], CvMilitaryAI::GetUnitTypeForArmy, base weight + multiplier x OFFENSE) - neither needs to be
 *    purchasable, as in the original, see [Candidate.canBuyNow] - and every unit and building the city may buy now with gold
 *    ([Kind.Ordinary], CvCity::IsCanPurchase with bTestPurchaseCost: [com.unciv.logic.city.CityConstructions.isConstructionPurchaseAllowed],
 *    which also tests the treasury, the puppet and resistance rules, and a city being razed) with a flavor weight above 0
 *    ([ProductionAi.unitWeight], [ProductionAi.buildingWeight]); projects are never bought. A unit that uses unit supply is out when
 *    the civilization has no supply left (GetNumUnitsToSupply is not below GetNumUnitsSupplied);
 *  - the candidates are sorted by that weight, then each weight divided by its time penalty (ReweightByDuration:
 *    [WonderProductionAi.reweightByTurnsLeft] of the turns left, [ProductionAi.turnsLeft]) - and it is this reweighted weight that goes
 *    into the sanity checks, unlike ChooseProduction, which reweights after them;
 *  - sanity: units through [UnitProductionAi.checkUnitBuildSanity] with forPurchase (the operation unit as a unit for an operation, only
 *    while the next open slot musters in this city; the army and ordinary units not), buildings through
 *    [BuildingProductionAi.checkBuildingBuildSanity], a building with production in it under 50% done scaled by (100 + done%) / max(1, done%)
 *    (it is cheaper to finish what is started); at most 0 refuses. No 3x for the early expansion phase (ChooseHurry has none);
 *  - the pick: the sanity weights sorted, a weighted pick among those within HandicapInfos.CityProductionChoiceCutoffThreshold % of
 *    the best ([WeightedChoice.chooseAbovePercentThreshold]); null when nothing is left.
 *
 *  The purchase rules of Vox Populi's economy (building investments, the purchase cooldown of units, the building a unit needs to be bought) are in
 *  [com.unciv.logic.city.PurchaseRules] and so in CvCity::IsCanPurchase of the candidates above: a unit on cooldown, a building that is invested in already
 *  or finishes next turn is no candidate, which is how the original's chooser sees them. Not ported: unit investments (MOD_BALANCE_UNIT_INVESTMENTS, off in
 *  Vox Populi), VP's NoSupply unit flag.
 *  Unciv extras kept from [ConstructionAutomation]: what a human disabled in the city, the personality's "Will not build" uniques and
 *  the ruleset's AiChoiceWeight uniques.
 *
 *  Side effect as the original: [com.unciv.logic.automation.strategy.MilitaryAi.numberOfTimesOpsBuildSkippedOver] goes up by one when an
 *  operation unit is offered; the caller resets it when the operation unit is bought.
 */
object HurryChoice {

    /** CvCityBuildable's type: CITY_BUILDABLE_UNIT / _BUILDING, CITY_BUILDABLE_UNIT_FOR_ARMY, CITY_BUILDABLE_UNIT_FOR_OPERATION */
    enum class Kind { Ordinary, Army, Operation }

    /** CvCityBuildable of ChooseHurry: [score] is its m_iValue after sanity and [turns] its m_iTurnsToConstruct. */
    class Candidate(
        val city: City,
        val construction: INonPerpetualConstruction,
        val kind: Kind,
        val score: Int,
        val turns: Int
    ) {
        /** The gold cost now, null if it cannot be bought with gold */
        val goldCost: Int? get() = construction.getStatBuyCost(city, Stat.Gold)

        /** Whether the city may buy it with gold right now (the original does not test this for [Kind.Operation] and [Kind.Army]) */
        val canBuyNow: Boolean
            get() = goldCost?.let { city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Gold, it) } == true
    }

    private class Precheck(val construction: INonPerpetualConstruction, val kind: Kind, var weight: Int, val turns: Int)

    private const val PRODUCTION_CUTOFF = "CityProductionChoiceCutoffThreshold"
    /** HandicapInfos.CityProductionChoiceCutoffThreshold of Vox Populi (every difficulty), for a ruleset without the column */
    private const val DEFAULT_PRODUCTION_CUTOFF = 90

    /**
     *  CvCityStrategyAI::ChooseHurry(bUnitOnly, bFaithPurchase) for [city]: the item to buy, null if there is none (NOT_A_CITY_BUILDABLE).
     *  [faithPurchase] (with [religionCultureAiLikeVp][com.unciv.models.ModConstants.religionCultureAiLikeVp], from
     *  [com.unciv.logic.automation.civilization.ReligionAutomation]): no operation or army unit, the candidates are those the city may buy now with
     *  Faith, and units that spread religion or remove heresy or are special (VP's Special: Great People, aircraft, missiles) are skipped; the
     *  sanity check still asks that the unit can be bought with gold now, as the original's. [unitOnly]: no buildings.
     *  [Candidate.goldCost] and [Candidate.canBuyNow] stay gold.
     */
    fun choose(city: City, unitOnly: Boolean = false, faithPurchase: Boolean = false): Candidate? {
        val civ = city.civ
        val rules = civ.gameInfo.ruleset.ai
        val constructions = city.cityConstructions
        val ruleset = civ.gameInfo.ruleset
        val precheck = ArrayList<Precheck>()

        val constructionsToAvoid = civ.getPersonality().getMatchingUniques(UniqueType.WillNotBuild, city.state).map { it.params[0] }.toList()
        fun isAllowed(construction: INonPerpetualConstruction): Boolean {
            if (civ.isHuman() && construction.name in city.disabledConstructions) return false
            for (toAvoid in constructionsToAvoid) {
                if (construction is Building && construction.matchesFilter(toAvoid, city.state)) return false
                if (construction is BaseUnit && construction.matchesFilter(toAvoid, city.state)) return false
            }
            return true
        }
        fun withAiChoiceWeight(construction: INonPerpetualConstruction, weight: Int): Int =
            if (weight <= 0) weight else (weight * construction.getWeightForAiDecision(GameContext(civ, city))).toInt()

        // bCanSupply = GetNumUnitsToSupply < GetNumUnitsSupplied; bOoS = !bCanSupply && IsMilitarySupport && !IsNoSupply
        val canSupply = civ.units.getNumUnitsToSupply() < civ.stats.getUnitSupply()
        val noSupplyFlag = civ.gameInfo.ruleset.modOptions.constants.unitSupplyLikeVp
        fun outOfSupply(unit: BaseUnit) = !canSupply && unit.usesUnitSupply && !(noSupplyFlag && unit.hasNoUnitSupply)

        val military = civ.ai.military
        val offense = civ.ai.personalityAndGrandStrategy(Flavor.Offense)

        // Units for operations first, then for sneak attack armies
        val offer = if (faithPurchase) null else civ.ai.operations.nextUnitForOperationSlot(city)
        if (offer != null && isAllowed(offer.unit) && !outOfSupply(offer.unit)) {
            val weight = rules.define(AiDefine.AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT) +
                rules.define(AiDefine.AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER) * (offense + military.numberOfTimesOpsBuildSkippedOver) +
                ProductionAi.unitWeight(city, offer.unit)
            if (weight > 0) {
                precheck.add(Precheck(offer.unit, Kind.Operation, weight, ProductionAi.turnsLeft(city, offer.unit)))
                military.numberOfTimesOpsBuildSkippedOver++
            }
        }
        val armyUnit = if (faithPurchase) null else MilitaryOperations.unitForArmy(city)
        if (armyUnit != null && isAllowed(armyUnit) && !outOfSupply(armyUnit)) {
            val weight = rules.define(AiDefine.AI_CITYSTRATEGY_ARMY_UNIT_BASE_WEIGHT) +
                rules.define(AiDefine.AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER) * offense
            if (weight > 0) precheck.add(Precheck(armyUnit, Kind.Army, weight, ProductionAi.turnsLeft(city, armyUnit)))
        }

        val purchaseStat = if (faithPurchase) Stat.Faith else Stat.Gold
        fun canPurchase(construction: INonPerpetualConstruction): Boolean {
            if (city.isBeingRazed) return false   // IsRazing
            val cost = construction.getStatBuyCost(city, purchaseStat) ?: return false
            return constructions.isConstructionPurchaseAllowed(construction, purchaseStat, cost)
        }

        // The units that can be bought now
        for (unit in ruleset.units.values) {
            // the original's faith price test (GetFaithCost > 0) is in canPurchase
            if (faithPurchase && (unit.hasUnique(UniqueType.CanSpreadReligion) || unit.hasUnique(UniqueType.CanRemoveHeresy)
                    || unit.isGreatPerson || unit.isAirUnit())) continue
            if (outOfSupply(unit)) continue
            val weight = ProductionAi.unitWeight(city, unit)
            if (weight <= 0 || !isAllowed(unit) || !canPurchase(unit)) continue
            val weighted = withAiChoiceWeight(unit, weight)
            if (weighted > 0) precheck.add(Precheck(unit, Kind.Ordinary, weighted, ProductionAi.turnsLeft(city, unit)))
        }

        // The buildings that can be bought now (projects are not purchasable)
        if (!unitOnly) for (building in ruleset.buildings.values) {
            if (ProductionAi.isProject(rules, building)) continue
            val weight = ProductionAi.buildingWeight(city, building)
            if (weight <= 0 || !isAllowed(building) || !canPurchase(building)) continue
            val weighted = withAiChoiceWeight(building, weight)
            if (weighted > 0) precheck.add(Precheck(building, Kind.Ordinary, weighted, ProductionAi.turnsLeft(city, building)))
        }

        // StableSortItems, then ReweightByDuration
        val sorted = precheck.sortedByDescending { it.weight }
        for (item in sorted) item.weight = WonderProductionAi.reweightByTurnsLeft(civ, item.weight, item.turns)

        // Sanity and AI optimization check
        val buildables = ArrayList<Candidate>()
        for (item in sorted) {
            val construction = item.construction
            val weight = when (construction) {
                is BaseUnit -> when (item.kind) {
                    Kind.Operation ->
                        if (civ.ai.operations.isNextSlotMusteredIn(city))
                            UnitProductionAi.checkUnitBuildSanity(city, construction, item.weight, true, true)
                        else 0
                    else -> UnitProductionAi.checkUnitBuildSanity(city, construction, item.weight, false, true)
                }
                is Building -> {
                    var sane = BuildingProductionAi.checkBuildingBuildSanity(city, construction, item.weight)
                    val done = constructions.getWorkDone(construction.name) * 100
                    if (done > 0) {
                        val needed = max(1, construction.getProductionCost(civ, city))
                        val percent = done / needed
                        if (percent < 50) sane = (sane.toLong() * (100 + percent) / max(1, percent)).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    }
                    sane
                }
                else -> 0
            }
            if (weight > 0) buildables.add(Candidate(city, construction, item.kind, weight, item.turns))
        }
        if (buildables.isEmpty()) return null
        buildables.sortByDescending { it.score }

        val cutoff = rules.handicapValue(civ.gameInfo.getDifficulty().name, PRODUCTION_CUTOFF) ?: DEFAULT_PRODUCTION_CUTOFF
        return buildables[WeightedChoice.chooseAbovePercentThreshold(buildables.map { it.score }, cutoff, civ.ai.random("ChooseHurry" + city.id))]
    }
}
