package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.unit.GreatPersonAutomation
import com.unciv.logic.city.NeedType
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Counter
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.PolicyAttributesDef
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTarget
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

        /** CvPlayer::GetUnhappinessFromDistress and its siblings: the unhappy citizens of [need] in all our cities (with the needs model) */
        fun unhappinessFrom(need: NeedType): Int = civ.cities.sumOf { it.cityStats.needs.yieldNeed(need) }

        /** The golden age points a turn that the cities' positive happiness deltas make before a policy's percentage (CvPolicyAI's iTotalGAP) */
        val happinessGoldenAgePoints: Int by lazy { civ.cities.sumOf { maxOf(0, it.cityStats.needs.delta) } }

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
        if (inputs.civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) weighNeedUniques(policy, inputs, yields)
        weighEventUniques(policy, inputs, yields)
        val def = inputs.civ.gameInfo.ruleset.ai.policyAttributesOf(policy.name) ?: return yields
        weighColumns(def, inputs, yields)
        weighRows(def, inputs, yields)
        return yields
    }

    /**
     *  The columns of the needs, which with [com.unciv.models.ModConstants.cityNeedsLikeVp] are uniques of the policy (CvPolicyAI::WeightPolicyAttributes):
     *  - a flat reduction of a need ([UniqueType.NeedReduction]): the points x 100 (x 50 when we have no unhappy citizen of that need and are no expansionist),
     *    for Poverty x 40 (x 20), all in the Food yield;
     *  - citizens whose specialists cost no unhappiness ([UniqueType.NoUnhappinessFromSpecialists]): x 15 for a smaller civilization, else x 10; in the capital x 50 (x 25);
     *  - happiness from specialists ([UniqueType.HappinessFromSpecialists]): x 10 x max(1, population / 5) for a smaller civilization, else x 2 x max(1, population / 10);
     *  - happiness per citizens and per trade route as the original's HappinessPerXPopulation and HappinessPerTradeRoute: [UniqueType.HappinessPerCitizens],
     *    [UniqueType.HappinessPerTradeRoute];
     *  - golden age points from happiness ([UniqueType.GoldenAgePointsFromHappinessPercent]): the cities' positive deltas x the percentage (x 2 for a tourism or smaller
     *    civilization) in the golden age points yield.
     *  The column of the empire size modifier has no weight in the source.
     */
    private fun weighNeedUniques(policy: Policy, inputs: Inputs, yields: IntArray) {
        val food = AttributeYield.Food.ordinal
        with(inputs) {
            for (unique in policy.getMatchingUniques(UniqueType.NeedReduction, GameContext.IgnoreConditionals)) {
                val need = NeedType.entries.firstOrNull { it.label == unique.params[0] } ?: continue
                val points = unique.params[1].toInt()
                val (high, low) = if (need == NeedType.Poverty) 40 to 20 else 100 to 50
                yields[food] += points * if (expansionist || unhappinessFrom(need) > 0) high else low
            }
            for (unique in policy.getMatchingUniques(UniqueType.NoUnhappinessFromSpecialists, GameContext.IgnoreConditionals)) {
                val citizens = unique.params[0].toInt()
                val inCapital = unique.params[1] in CAPITAL_FILTERS
                yields[food] += citizens * if (inCapital) (if (smaller) 50 else 25) else (if (smaller) 15 else 10)
            }
            for (unique in policy.getMatchingUniques(UniqueType.HappinessFromSpecialists, GameContext.IgnoreConditionals)) {
                val specialists = unique.params[1].toInt()
                yields[food] += if (smaller) specialists * 10 * maxOf(1, population / 5) else specialists * 2 * maxOf(1, population / 10)
            }
            for (unique in policy.getMatchingUniques(UniqueType.HappinessPerCitizens, GameContext.IgnoreConditionals)) {
                val perCitizens = unique.params[1].toInt()
                yields[food] += unique.params[0].toInt() * population * (if (expansionist) 200 else 100) / perCitizens
            }
            for (unique in policy.getMatchingUniques(UniqueType.HappinessPerTradeRoute, GameContext.IgnoreConditionals))
                yields[food] += unique.params[0].toInt() * (if (expansionist) 10 else 5) * numCities
            for (unique in policy.getMatchingUniques(UniqueType.GoldenAgePointsFromHappinessPercent, GameContext.IgnoreConditionals)) {
                val points = happinessGoldenAgePoints * unique.params[0].toInt() / 100
                yields[AttributeYield.GoldenAgePoints.ordinal] += if (tourism || smaller) points * 2 else points
            }
        }
    }

    private val CAPITAL_FILTERS = setOf("in capital", "Capital")

    /** The columns of the policy table whose yield the ruleset's AI.json already weighs through [weighColumns], so a unique that is the same yield is not weighed twice */
    private val KILL_COLUMNS = listOf("GoldFromKills", "CultureFromBarbarianKills")

    /**
     *  The instant yield tables of the policy (Policy_YieldFromBirth ... Policy_FounderYield, Policy_YieldFromKills), which are uniques with a trigger condition of
     *  the instant yield events ([com.unciv.logic.civilization.InstantYields]) (CvPolicyAI::WeightPolicyAttributes, V:CvPolicyAI.cpp:4290-4465): the yield of the
     *  gain gets the amount (the percentage of a ratio unique: the table value) times
     *  - birth (`upon population growth`): 2 x cities for an expansionist, else the cities; border growth (`upon acquiring a tile through border growth`): the same;
     *  - construction (`upon constructing [buildingFilter]`): 4 x cities for an expansionist, else 2 x cities; of a world wonder (the filter `World Wonder`):
     *    5 x max(2, our wonders / 2) for a tourism or smaller civilization, else max(2, wonders / 4);
     *  - technology (`upon discovering a technology`): 15 for a nerd or smaller civilization, else 10; expending a great person: 2 for a smaller civilization, else 1;
     *    founding a city: 3 for an expansionist, else 1; completing a unit: 2 for a warmonger, else 1;
     *  - kills (`upon defeating a unit`, and the existing `Earn [n]% of killed [unit]'s [strength] as [stat]` when the AI.json of the ruleset does not weigh
     *    the policy's kill columns already): 5 for a warmonger, else 1.
     *  What the original weighs per citizen of the capital, per retroactive count and by the policy unlock has no unique in the engine yet.
     */
    private fun weighEventUniques(policy: Policy, inputs: Inputs, yields: IntArray) {
        val columns = inputs.civ.gameInfo.ruleset.ai.policyAttributesOf(policy.name)?.values
        val killsWeighedByColumns = columns != null && KILL_COLUMNS.any { (columns[it] ?: 0) != 0 }
        fun weigh(stat: String, weight: Int) {
            val yield = AttributeYield.entries.firstOrNull { it.name == stat } ?: return
            yields[yield.ordinal] += weight
        }
        with(inputs) {
            for (type in listOf(UniqueType.OneTimeGainStat, UniqueType.OneTimeGainStatOfTriggeringAmount))
                for (unique in policy.getMatchingUniques(type, GameContext.IgnoreConditionals)) {
                    val amount = unique.params[0].toInt()
                    if (amount == 0) continue
                    val event = unique.modifiers.firstOrNull { it.type?.targetTypes?.contains(UniqueTarget.TriggerCondition) == true } ?: continue
                    val factor = when (event.type) {
                        UniqueType.TriggerUponPopulationGrowth, UniqueType.TriggerUponAcquiringTile ->
                            if (event.type == UniqueType.TriggerUponAcquiringTile && event.params[1] != "border growth") 0
                            else if (expansionist) 2 * numCities else numCities
                        UniqueType.TriggerUponConstructingBuilding, UniqueType.TriggerUponConstructingBuildingCityFilter ->
                            if (event.params[0] == "World Wonder") {
                                if (tourism || smaller) 5 * maxOf(2, numWonders / 2) else maxOf(2, numWonders / 4)
                            } else if (expansionist) 4 * numCities else 2 * numCities
                        UniqueType.TriggerUponResearch -> if (nerd || smaller) 15 else 10
                        UniqueType.TriggerUponExpendingUnit -> if (smaller) 2 else 1
                        UniqueType.TriggerUponFoundingCity -> if (expansionist) 3 else 1
                        UniqueType.TriggerUponCompletingUnit -> if (warmonger) 2 else 1
                        UniqueType.TriggerUponDefeatingUnit -> if (warmonger) 5 else 1
                        else -> 0
                    }
                    if (factor != 0) weigh(unique.params[1], amount * factor)
                }
            if (!killsWeighedByColumns)
                for (unique in policy.getMatchingUniques(UniqueType.KillUnitPlunder, GameContext.IgnoreConditionals))
                    weigh(unique.params[3], unique.params[0].toInt() * if (warmonger) 5 else 1)
        }
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
