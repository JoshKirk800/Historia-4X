package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.unit.GreatPersonAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Counter
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.PolicyAttributesDef
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  CvPolicyAI::WeightPolicyAttributes (Vox Populi): what the effects of a policy are worth to a player, as one weight per yield, from the
 *  policy's CvPolicyEntry columns and Policy_* rows (AI.json `policyAttributes`: the ones a BNW policy sets, see
 *  phaseE/vp-policy-attributes-map.md; the others are none). [com.unciv.logic.automation.civilization.PolicyAutomation]'s WeighPolicy adds
 *  them up. Inputs that are none here:
 *  - FreeBuildingOnConquest, the buildings and units of the policy (their PolicyType) and the free buildings: valued by
 *    CvBuildingProductionAI::CheckBuildingBuildSanity / CvUnitProductionAI::CheckUnitBuildSanity, not ported;
 *  - the Great Merchants and Scientists created: Unciv counts the Great People of a shared group together ([greatPeopleCreated]).
 */
object PolicyAttributeAi {
    /** The yields of the original's weights that WeighPolicy reads (YieldTypes up to YIELD_GREAT_ADMIRAL_POINTS) */
    enum class AttributeYield { Food, Production, Gold, Science, Culture, Faith, Tourism, GoldenAgePoints, GreatGeneralPoints, GreatAdmiralPoints }

    /** The player values WeightPolicyAttributes reads, taken once for all the policies weighed in a turn */
    class Inputs(val civ: Civilization) {
        val hasCapital = civ.getCapital(firstCityIfNoCapital = false) != null
        private val ai = civ.ai
        val warmonger = ai.hasTraitCategory(TraitCategory.Warmonger)
        val expansionist = ai.hasTraitCategory(TraitCategory.Expansionist)
        val smaller = ai.hasTraitCategory(TraitCategory.Smaller)
        val nerd = ai.hasTraitCategory(TraitCategory.Nerd)
        val diplomat = ai.hasTraitCategory(TraitCategory.Diplomat)
        val tourism = ai.hasTraitCategory(TraitCategory.Tourism)
        val religious = ai.hasTraitCategory(TraitCategory.Religious)
        val wonderCompetitiveness = ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness)

        private val unhappinessPercent = civ.gameInfo.ruleset.ai
            .worldSizeForTiles(civ.gameInfo.tileMap.tileList.size).numCitiesUnhappinessPercent

        /** iNumCities: 4 for Venice; else the cities, +1 for an expansionist, -1 for a smaller civ, and in the first quarter of the
         *  game 3 more (the expansion to come) scaled by the map's NumCitiesUnhappinessPercent (at least 50), at least 1 */
        val numCities: Int = run {
            if (civ.hasUnique(UniqueType.MayNotAnnexCities)) return@run 4
            var count = civ.cities.size
            if (expansionist) count++ else if (smaller) count--
            if (civ.gameInfo.turns <= GreatPersonAutomation.estimateEndTurn(civ.gameInfo) / 4) {
                count = (3 + count) * 100 / maxOf(50, unhappinessPercent)
                if (count <= 1) count = 1
            }
            count
        }

        /** iPopulation: the average city size, rounded, x half the cities (at least 1), scaled by NumCitiesUnhappinessPercent (at least 75) */
        val population: Int = run {
            val average = if (civ.cities.isEmpty()) 0f else civ.cities.sumOf { it.population.population }.toFloat() / civ.cities.size
            val value = (average + 0.5f).toInt() * maxOf(1, numCities / 2) * 100 / maxOf(75, unhappinessPercent)
            if (value <= 1) 1 else value
        }

        /** iNumWonders: our world wonders + half our WonderCompetitiveness */
        val numWonders: Int = civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().count { it.isWonder } } + wonderCompetitiveness / 2

        /** iCapitalPop: the capital's size, at least 10 */
        val capitalPop: Int = maxOf(10, civ.getCapital(firstCityIfNoCapital = false)?.population?.population ?: 0)

        val numGoldenAges: Int get() = civ.goldenAges.numberOfGoldenAges
        val numTradeRoutes: Int get() = civ.tradeRoutes.routes.size
        val themingBonuses: Int by lazy { civ.cities.sumOf { it.greatWorks.getTotalThemingBonus() } }
        val numGreatWorks: Int by lazy { civ.cities.sumOf { it.greatWorks.getNumWorks() } }
        val numSpecialists: Int by lazy { civ.cities.sumOf { it.population.getNumberOfSpecialists() } }

        /** CvPlayer::CountAllImprovement: our tiles with each improvement */
        val improvementCounts: Counter<String> by lazy {
            Counter<String>().apply { for (city in civ.cities) for (tile in city.getTiles()) tile.improvement?.let { add(it, 1) } }
        }

        /** getGreatGeneralsCreated / getGreatAdmiralsCreated: those earned through combat points, land or sea */
        fun greatGeneralsCreated(water: Boolean): Int = civ.greatPeople.greatGeneralsCreatedCounter.entries
            .filter { (unit, _) -> civ.gameInfo.ruleset.units[unit]?.isWaterUnit == water }.sumOf { it.value }

        /** getGreat<X>Created: the Great People gained of [unitName]'s group, none when that group has other Great People too */
        fun greatPeopleCreated(unitName: String): Int {
            val ruleset = civ.gameInfo.ruleset
            if (unitName !in ruleset.units) return 0
            val manager = civ.greatPeople
            val pool = manager.getPoolKey(unitName)
            val shared = ruleset.units.values.any { it.isGreatPerson && it.replaces == null && it.name != unitName && manager.getPoolKey(it.name) == pool }
            return if (shared) 0 else manager.greatPeopleCreated[pool]
        }
    }

    /** The weights of [policy] per [AttributeYield] (by ordinal); all 0 without a capital or AI.json entry */
    fun weightPolicyAttributes(policy: Policy, inputs: Inputs): IntArray {
        val yields = IntArray(AttributeYield.entries.size)
        if (!inputs.hasCapital) return yields
        val def = inputs.civ.gameInfo.ruleset.ai.policyAttributesOf(policy.name) ?: return yields
        weighColumns(def, inputs, yields)
        weighRows(def, inputs, yields)
        return yields
    }

    /** The Policies columns, in the order of the original (its lines 1097-2860) */
    private fun weighColumns(def: PolicyAttributesDef, inputs: Inputs, yields: IntArray) {
        val n = inputs.numCities
        fun term(column: String, yield: AttributeYield, trait: Boolean, ifTrait: (Int) -> Int, otherwise: (Int) -> Int) {
            val v = def.values[column] ?: 0
            if (v != 0) yields[yield.ordinal] += if (trait) ifTrait(v) else otherwise(v)
        }
        with(inputs) {
            term("CulturePerCity", AttributeYield.Culture, expansionist, { it * 5 * n }, { it * 2 * n })
            term("CultureWonderMultiplier", AttributeYield.Culture, false, { 0 }, { it * wonderCompetitiveness + numWonders })
            term("CultureFromBarbarianKills", AttributeYield.Culture, warmonger, { it * 5 }, { it })
            term("GoldFromKills", AttributeYield.Gold, warmonger, { it * 5 }, { it })
            term("EmbarkedExtraMoves", AttributeYield.GreatGeneralPoints, warmonger || expansionist, { it * 150 }, { it * 50 })
            term("AttackBonusTurns", AttributeYield.GreatGeneralPoints, warmonger, { it * it }, { it * (it / 2) })
            term("GoldenAgeTurns", AttributeYield.Tourism, tourism, { it * maxOf(1, numGoldenAges) * 5 }, { it * maxOf(1, numGoldenAges) * 2 })
            term("GoldenAgeDurationMod", AttributeYield.Tourism, tourism, { it * 4 * maxOf(1, numGoldenAges) }, { it * 2 * maxOf(1, numGoldenAges) })
            term("NumFreeTechs", AttributeYield.Science, nerd, { it * 250 }, { it * 125 })
            term("NumFreeGreatPeople", AttributeYield.Culture, tourism, { it * 300 }, { it * 150 })
            term("StrategicResourceMod", AttributeYield.GreatGeneralPoints, warmonger, { it * 5 }, { it * 2 })
            term("WonderProductionModifier", AttributeYield.Production, tourism, { it * (4 + numWonders) }, { it * (2 + numWonders) })
            term("BuildingProductionModifier", AttributeYield.Production, expansionist, { it * 10 }, { it * 5 })
            term("GreatPeopleRateModifier", AttributeYield.Culture, tourism || smaller, { it * 5 }, { it })
            term("GreatGeneralRateModifier", AttributeYield.GreatGeneralPoints, warmonger,
                { it * 3 + greatGeneralsCreated(false) }, { it + greatGeneralsCreated(false) })
            term("GreatAdmiralRateModifier", AttributeYield.GreatGeneralPoints, warmonger,
                { it * 3 + greatGeneralsCreated(true) }, { it + greatGeneralsCreated(true) })
            term("GreatWriterRateModifier", AttributeYield.Tourism, tourism,
                { it * 5 + greatPeopleCreated("Great Writer") }, { it + greatPeopleCreated("Great Writer") })
            term("GreatArtistRateModifier", AttributeYield.Tourism, tourism,
                { it * 5 + greatPeopleCreated("Great Artist") }, { it + greatPeopleCreated("Great Artist") })
            term("GreatMusicianRateModifier", AttributeYield.Tourism, tourism,
                { it * 5 + greatPeopleCreated("Great Musician") }, { it + greatPeopleCreated("Great Musician") })
            term("GreatMerchantRateModifier", AttributeYield.Gold, diplomat,
                { it * 3 + greatPeopleCreated("Great Merchant") }, { it + greatPeopleCreated("Great Merchant") })
            term("GreatScientistRateModifier", AttributeYield.Science, nerd,
                { it * 3 + greatPeopleCreated("Great Scientist") }, { it + greatPeopleCreated("Great Scientist") })
            term("UnhappinessMod", AttributeYield.Food, false, { 0 }, { it * 10 })
            term("CapitalUnhappinessMod", AttributeYield.Food, smaller, { it }, { it * -3 })
            term("FreeExperience", AttributeYield.GreatGeneralPoints, warmonger, { it * 15 }, { it * 2 })
            term("ExpModifier", AttributeYield.GreatGeneralPoints, warmonger, { it * 15 }, { it * 2 })
            term("WorkerSpeedModifier", AttributeYield.Production, expansionist, { it * 3 }, { it })
            term("BaseFreeUnits", AttributeYield.GreatGeneralPoints, warmonger, { it * 10 }, { it * 5 })
            term("HappinessPerGarrisonedUnit", AttributeYield.Food, warmonger || expansionist, { it * 10 * n }, { it * 5 * n })
            term("CulturePerGarrisonedUnit", AttributeYield.Culture, warmonger || expansionist, { it * 10 * n }, { it * 5 * n })
            term("HappinessPerTradeRoute", AttributeYield.Food, expansionist, { it * 10 * n }, { it * 5 * n })
            term("HappinessPerXPopulation", AttributeYield.Food, expansionist, { population * 200 / it }, { population * 100 / it })
            term("ExtraHappinessPerLuxury", AttributeYield.Food, expansionist || diplomat, { it * 10 }, { it * 2 })
            term("PlotCultureExponentModifier", AttributeYield.Culture, expansionist, { it * -3 }, { it * -2 })
            term("NumCitiesPolicyCostDiscount", AttributeYield.Culture, expansionist, { it * -10 }, { it * -2 })
            term("GarrisonedCityRangeStrikeModifier", AttributeYield.GreatGeneralPoints, smaller, { it }, { it / 2 })
            term("UnitPurchaseCostModifier", AttributeYield.Gold, warmonger, { it * -4 }, { it * -2 })
            term("BuildingPurchaseCostModifier", AttributeYield.Gold, expansionist, { it * -5 }, { it * -2 })
            term("TradeMissionGoldModifier", AttributeYield.Gold, diplomat, { it * 2 }, { it })
            term("FaithCostModifier", AttributeYield.Faith, religious, { it * -3 }, { -it })
            term("StealTechFasterModifier", AttributeYield.Science, diplomat, { it / 2 }, { it / 4 })
            term("CatchSpiesModifier", AttributeYield.Science, diplomat, { it * 2 }, { it / 2 })
            term("CityStrengthMod", AttributeYield.GreatGeneralPoints, warmonger, { it }, { it / 2 })
            term("CityGrowthMod", AttributeYield.Food, smaller, { it * 15 * n }, { it * 5 * n })
            term("CapitalGrowthMod", AttributeYield.Food, smaller, { it * 20 }, { it * 5 })
            term("CapitalSettlerProductionModifier", AttributeYield.Production, expansionist, { it }, { it / 2 })
            term("NewCityExtraPopulation", AttributeYield.Food, expansionist, { it * 50 }, { it * 25 })
            term("UnitGoldMaintenanceMod", AttributeYield.Gold, warmonger, { it * -15 }, { it * -5 })
            term("ExpModifier", AttributeYield.GreatGeneralPoints, warmonger, { it * 5 }, { it }) // read a second time (line 2147)
            term("MinorGoldFriendshipMod", AttributeYield.Gold, diplomat, { it * 2 }, { it })
            term("MinorFriendshipMinimum", AttributeYield.Gold, diplomat, { it * 2 }, { it })
            term("MinorFriendshipDecayMod", AttributeYield.Gold, diplomat, { it * -2 }, { -it })
            term("CityStateUnitFrequencyModifier", AttributeYield.GreatGeneralPoints, warmonger, { it * 5 }, { it })
            term("CommonFoeTourismModifier", AttributeYield.Tourism, warmonger, { it * 5 }, { it })
            term("LessHappyTourismModifier", AttributeYield.Tourism, tourism, { it * 5 }, { it })
            term("SharedIdeologyTourismModifier", AttributeYield.Tourism, tourism, { it * 5 }, { it })
            term("LandTradeRouteGoldChange", AttributeYield.Gold, diplomat,
                { maxOf(1, numTradeRoutes) * it / 50 }, { maxOf(1, numTradeRoutes) * it / 100 })
            term("SeaTradeRouteGoldChange", AttributeYield.Gold, diplomat,
                { maxOf(1, numTradeRoutes) * it / 50 }, { maxOf(1, numTradeRoutes) * it / 100 })
            term("SharedIdeologyTradeGoldChange", AttributeYield.Gold, diplomat, { it * 25 }, { it * 5 })
            term("RiggingElectionModifier", AttributeYield.Gold, diplomat, { it * 5 }, { it })
            term("MilitaryUnitGiftExtraInfluence", AttributeYield.Gold, warmonger, { it * 5 }, { it })
            term("ProtectedMinorPerTurnInfluence", AttributeYield.Gold, warmonger, { it * 5 }, { it })
            term("AfraidMinorPerTurnInfluence", AttributeYield.Gold, warmonger, { it * 5 }, { it })
            term("MinorBullyScoreModifier", AttributeYield.Gold, warmonger, { it * 5 }, { it / 2 })
            term("ThemingBonusMultiplier", AttributeYield.Tourism, tourism, { it * themingBonuses }, { it * (themingBonuses / 2) })
            term("InternalTradeRouteYieldModifier", AttributeYield.Production, expansionist, { it / 5 }, { it / 10 })
            term("SharedReligionTourismModifier", AttributeYield.Tourism, religious, { it * 5 }, { it })
            term("TradeRouteTourismModifier", AttributeYield.Tourism, diplomat, { it * 5 }, { it })
            term("OpenBordersTourismModifier", AttributeYield.Tourism, diplomat, { it * 5 }, { it })
            term("MinorGreatPeopleAllies", AttributeYield.Tourism, diplomat, { 250 }, { 50 })
            term("MinorScienceAllies", AttributeYield.Science, diplomat, { 250 }, { 50 })
            term("MinorResourceBonus", AttributeYield.Food, diplomat, { 250 }, { 50 })
            term("HappinessToCulture", AttributeYield.Culture, tourism || smaller, { it * 5 }, { it * 2 })
            term("HappinessToScience", AttributeYield.Science, nerd, { it * 3 }, { it })
            if ((def.values["NumCitiesFreeCultureBuilding"] ?: 0) != 0) {
                if (smaller) yields[AttributeYield.Culture.ordinal] += 50 else yields[AttributeYield.Science.ordinal] += 25
            }
            term("NumCitiesFreeFoodBuilding", AttributeYield.Food, smaller, { 50 }, { 25 })
            term("HalfSpecialistUnhappiness", AttributeYield.Food, smaller || tourism, { 250 }, { 50 })
            term("HalfSpecialistFood", AttributeYield.Food, smaller || tourism, { 500 }, { 150 })
            term("UnitUpgradeCostMod", AttributeYield.Gold, warmonger, { it * -5 }, { -it })
            term("BarbarianCombatBonus", AttributeYield.GreatGeneralPoints, warmonger, { it * 3 }, { it })
            term("AlwaysSeeBarbCamps", AttributeYield.GreatGeneralPoints, warmonger, { 50 }, { 5 })
            term("GarrisonFreeMaintenance", AttributeYield.Gold, expansionist, { 10 * n }, { 5 * n })
            term("SecondReligionPantheon", AttributeYield.Faith, religious, { 100 }, { 50 })
            term("AddReformationBelief", AttributeYield.Faith, religious, { 1000 }, { 50 })
            term("EnablesSSPartHurry", AttributeYield.Science, nerd, { 1000 }, { 100 })
            term("EnablesSSPartPurchase", AttributeYield.Science, nerd, { 1000 }, { 100 })
        }
    }

    /** The Policy_* rows (the original's loops over unit combats, building classes, unit classes, improvements, hurries, promotions
     *  and yields, lines 3543-4289); each row is valued on its own */
    private fun weighRows(def: PolicyAttributesDef, inputs: Inputs, yields: IntArray) {
        val civ = inputs.civ
        val n = inputs.numCities
        val units = civ.gameInfo.ruleset.units
        for (row in def.rows) {
            val v = row.value
            if (v == 0) continue
            val eY = AttributeYield.entries.firstOrNull { it.name == row.yield }
            with(inputs) {
                when (row.table) {
                    "UnitCombatProductionModifiers" ->
                        yields[AttributeYield.GreatGeneralPoints.ordinal] += if (warmonger) v * 4 else v
                    "BuildingClassHappiness" -> yields[AttributeYield.Food.ordinal] += if (expansionist) 3 * v * n else v * n
                    "BuildingClassProductionModifiers" ->
                        yields[AttributeYield.Production.ordinal] += if (expansionist) v * n / 15 else v * n / 25
                    "BuildingClassYieldModifiers" -> if (eY != null)
                        yields[eY.ordinal] += if (expansionist) v * maxOf(1, n / 2) else v * maxOf(1, n / 3)
                    "BuildingClassYieldChanges" -> if (eY != null) yields[eY.ordinal] += if (expansionist) v * 3 * n else v * n
                    "FreeUnitClasses" -> if (row.key in units) {
                        val unit = civ.getEquivalentUnit(row.key)
                        val combat = unit.strength > 0 || unit.rangedStrength > 0
                        if (if (combat) warmonger else expansionist) yields[AttributeYield.GreatGeneralPoints.ordinal] += v * 25
                        when {
                            unit.hasUnique(UniqueType.FoundCity) -> yields[AttributeYield.Food.ordinal] += v * (50 - civ.cities.size * 5)
                            unit.hasUnique(UniqueType.BuildImprovements) -> yields[AttributeYield.Food.ordinal] += v * 50 * civ.cities.size
                            else -> yields[AttributeYield.GreatGeneralPoints.ordinal] += v * 25
                        }
                    }
                    "TourismOnUnitCreation" -> if (row.key in units)
                        yields[AttributeYield.Tourism.ordinal] += if (tourism) v * 5 else v
                    "ImprovementCultureChanges" -> {
                        val count = improvementCounts[row.key]
                        if (count > 0) yields[AttributeYield.Culture.ordinal] += if (tourism) v * count else v * count / 2
                    }
                    "ImprovementYieldChanges" -> {
                        val count = improvementCounts[row.key]
                        if (count > 0 && eY != null) yields[eY.ordinal] += if (expansionist) v * count * 5 else v * count * 2
                    }
                    "HurryModifiers" -> yields[AttributeYield.Gold.ordinal] += if (diplomat) v * -2 else -v
                    "FreePromotions" -> yields[AttributeYield.GreatGeneralPoints.ordinal] += if (warmonger) 125 else 25
                    "CityYieldChanges" -> if (eY != null) yields[eY.ordinal] += if (expansionist) v * 15 * n else v * 10 * n
                    "CoastalCityYieldChanges" -> if (eY != null) yields[eY.ordinal] += if (expansionist) v * 10 * n else v * 5 * n
                    "CapitalYieldChanges" -> if (eY != null)
                        yields[eY.ordinal] += if (smaller || tourism) v * 10 * capitalPop else v * 5 * capitalPop
                    "CapitalYieldPerPopChanges" -> if (eY != null)
                        yields[eY.ordinal] += if (smaller) capitalPop * 100 / v else capitalPop * 50 / v
                    "CapitalYieldModifiers" -> if (eY != null)
                        yields[eY.ordinal] += if (smaller) v * capitalPop else v * capitalPop / 2
                    "GreatWorkYieldChanges" -> if (eY != null)
                        yields[eY.ordinal] += if (tourism) v * 20 * maxOf(1, numGreatWorks) else v * 10 * maxOf(1, numGreatWorks)
                    "SpecialistExtraYields" -> if (eY != null)
                        yields[eY.ordinal] += if (tourism || smaller) v * 5 * maxOf(2, numSpecialists) else v * 2 * maxOf(2, numSpecialists)
                }
            }
        }
    }
}
