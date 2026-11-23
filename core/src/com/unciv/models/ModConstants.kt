package com.unciv.models

import com.unciv.utils.withHash
import java.lang.reflect.Modifier

/** Used as a member of [ModOptions][com.unciv.models.ruleset.ModOptions] for moddable "constants" - factors in formulae and such.
 *
 *  When combining mods, this is [merge]d _per constant/field_, not as entire object like other RulesetObjects.
 *  Merging happens on a very simple basis: If a Mod comes with a non-default value, it is copied, otherwise the parent value is left intact.
 *  If several mods change the same field, the last one wins.
 *
 *  Supports equality contract to enable the Json serializer to recognize unchanged defaults.
 *
 *  Methods [merge], [equals], [hashCode] and [toString] are done through reflection!
 *  Therefore, adding a field will not need to update these methods, but all members ***must*** conform to the equality contract.
 *  (overhead is not a factor, these routines run very rarely. The alternative would be to make the entire thing a data class.)
 */
class ModConstants {
    // Max amount of experience that can be gained from combat with barbarians
    @Suppress("SpellCheckingInspection") // Pfrom is not a word ;)
    var maxXPfromBarbarians = 30

    // Formula for city Strength:
    // Strength = baseStrength + strengthPerPop + strengthFromTiles +
    //            ((%techs * multiplier) ^ exponent) * fullMultiplier +
    //            (garrisonBonus * garrisonUnitStrength * garrisonUnitHealth/100) +
    //            defensiveBuildingStrength
    // where %techs is the percentage of techs in the tech tree that are complete
    // If no techs exist in this ruleset, %techs = 0.5 (=50%)
    var cityStrengthBase = 8.0
    var cityStrengthPerPop = 0.4
    var cityStrengthFromTechsMultiplier = 5.5
    var cityStrengthFromTechsExponent = 2.8
    var cityStrengthFromTechsFullMultiplier = 1.0
    var cityStrengthFromGarrison = 0.2

    // Formula for Unit Supply:
    // Supply = unitSupplyBase (difficulties.json)
    //          unitSupplyPerCity * amountOfCities + (difficulties.json)
    //          unitSupplyPerPopulation * amountOfPopulationInAllCities
    // unitSupplyBase and unitSupplyPerCity can be found in difficulties.json
    // unitSupplyBase, unitSupplyPerCity and unitSupplyPerPopulation can also be increased through uniques
    // Only the units that use supply count against it: those with a combat strength and those with the "Uses unit supply" unique.
    // An AI major civilization and a city-state take base, per city and population share (instead of unitSupplyPerPopulation) from the difficulty's
    // aiUnitSupply... and cityStateUnitSupply... fields, and the sum is increased by their bonus percent.
    var unitSupplyPerPopulation = 0.5

    // Gold maintenance of units (CvTreasury::CalculateUnitCost), with 0.5 gold per unit that is not free:
    // cost = (paidUnits * 0.5 * (1 + unitMaintenanceGameMultiplier * progress)) ^ (1 + progress / unitMaintenanceExponentDivisor)
    // where progress is the turn divided by the number of turns of the game speed. The defaults are the original Unciv values; the Civ V rulesets use 0, 8 and 7.
    var unitMaintenanceFreeUnits = 3
    var unitMaintenanceGameMultiplier = 1f
    var unitMaintenanceExponentDivisor = 3f

    // The minimal distance that must be between any two cities, not counting the tiles cities are on
    // The number is the amount of tiles between two cities, not counting the tiles the cities are on.
    // e.g. "C__C", where "C" is a tile with a city and "_" is a tile without a city, has a distance of 2.
    // First constant is for cities on the same landmass, the second is for cities on different continents.
    var minimalCityDistance = 3
    var minimalCityDistanceOnDifferentContinents = 2

    var baseCityBombardRange = 2
    var cityWorkRange = 3
    var cityExpandRange = 5
    
    // Number of air units that can be stationed in a city
    var cityAirUnitCapacity = 6

    /** The extras of the Civ V resource placement (see CivVResourceExtras): a bonus at the third ring of each start, more bonuses in hills regions,
     *  small coal, oil or aluminum at the city states, top-ups of iron, horses, coal, oil, aluminum and uranium per civ, and no sugar on jungle.
     *  Replaces Unciv's own versions of these. It names the resources of Civ V. */
    var resourceExtrasLikeCivV = false

    /** Deal the regions to the civs as AssignStartingPlots:BalanceAndAssign does (see CivVStartAssignment): a random one of the fitting regions
     *  for each civ, by groups (the sea, preferred, avoided types), civs with the `Chooses its start region before...` unique first. */
    var startAssignmentLikeCivV = false

    /** Place the strategic resources as the AssignStartingPlots.lua of Vox Populi does (see VoxPopuliStrategics): each of iron, horses, coal, oil,
     *  aluminum and uranium on its own plot lists with its own one-deposit-per-N-tiles rule and impact layer (at least one deposit for each non-empty list),
     *  small deposits, oil in the sea as a fifth of the units on land, a small deposit at 3 in 5 city states and the top-ups per civ. It names the
     *  strategic resources of Civ V and takes their amounts from `majorDepositAmount` and `minorDepositAmount`.
     *  false: the major deposits per terrain (`MajorStrategicFrequency`), the minor deposits, `seaDepositsOfLandResource` and the extras. */
    var strategicPlacementLikeVoxPopuli = false

    /** Deal the civs to the continents as Vox Populi's AssignStartingPlots:GenerateRegions does (see VoxPopuliContinentAssignment): continents with less
     *  than a quarter of the biggest one's fertility are ignored and civs settle a continent in pairs, so on Duel both civs share one.
     *  false: each civ goes to the continent with the best fertility / (1 + civs already there), as Brave New World's script does. */
    var regionAssignmentLikeVoxPopuli = false

    /** Place the city-states as Vox Populi's AssignStartingPlots.lua does (see MinorCivPlacer, VoxPopuliCityStateSites): a site is not on the outermost
     *  ring of the map, not next to ice and has at most 30 fertility (the yields of the tiles within 2, by distance) around it; the city-states are
     *  placed in the order of assignment, only on free sites of their region (or of the uninhabited landmasses, those with at least 2 sites); one that
     *  does not fit is discarded, then a last-chance pass puts the discarded ones on any free site of the map, and what still does not fit is dropped.
     *  Implies the sites of [cityStateSitesLikeCivV]. false: the forced placements (within the impact of a start, then on top of one) of the other rulesets. */
    var cityStatePlacementLikeVoxPopuli = false

    /** Choose the start of each region as Vox Populi's AssignStartingPlots:FindStart does (see VoxPopuliStartFinder): one candidate list per area scored with
     *  +80 for the coast, its own minimums (production 1, 1, 3), no cap on junk, the best eligible tile of the outer area nearest to a corner.
     *  false: Brave New World's: river tiles before coastal before dry, coast +40. */
    var startFinderLikeVoxPopuli = false

    /** Assign and place the luxuries as AssignStartingPlots.lua does (see CivVLuxuryPlacement). It names the luxuries of Civ V.
     *  false: the generic placement of the resources' `Generated with weight` rules. */
    var luxuryPlacementLikeCivV = false

    /** With [luxuryPlacementLikeCivV], place the luxuries at the civs' starts as Vox Populi's AssignStartingPlots.lua does: 2 at each start (3 on a
     *  Legendary Start) without making up for a poor region, and Ivory as a special case (like marble) of at least 2.
     *  false: Brave New World's 1 at each start (2 on a Legendary Start), one more for each poor-fertility test failed. */
    var startLuxuriesLikeVoxPopuli = false

    /** With [terrainGeneratorLikeCivV], make a mountain on the coast (a land tile next to water) hills, as Vox Populi's FeatureGenerator:AddFeatures does
     *  (the Civ V scripts call it without an argument, which Brave New World reads as 'allow', and Vox Populi as 'do not allow'). */
    var noMountainsOnTheCoast = false

    /** Make the terrain and the features of land as the Civ V map scripts do (see CivVTerrainGenerator): the base terrain by latitude
     *  with fractal noise, rivers and lakes as MapGenerator.lua places them, then jungle, forest, marsh, oases, flood plains, ice and atolls
     *  from fractal thresholds. It names the terrains and features of the Civ V rulesets.
     *  false: the temperature and humidity of the terrain definitions, and the generic rivers. */
    var terrainGeneratorLikeCivV = false

    /** AssignStartingPlots:PlaceOilInTheSea. The strategic resource that is also found in the sea, but only as many deposits as
     *  a quarter of the half of the units of it on land (one in the sea per 4 units of the Coast deposit, Civ V: "Oil").
     *  Empty: the resource is placed in the sea like on any terrain, by its "Generated with weight" rule for it. */
    var seaDepositsOfLandResource = ""

    /** Normalize starts as AssignStartingPlots:NormalizeStartLocation and NormalizeCityState do (see CivVStartNormalizer).
     *  It names the terrains and resources of the Civ V rulesets, so it only makes sense for them. */
    var startNormalizerLikeCivV = false
    /** Choose and place the natural wonders as PlaceNaturalWonders does: as many as the world size gives (2 on Duel to 7 on Huge, or fewer if
     *  fewer have a candidate tile), drawn one by one by their weights from the wonders that have a candidate tile and placed in the order drawn,
     *  not the order of the fewest candidate tiles. The wonders that fail to place are replaced by the next ones drawn. */
    var naturalWondersLikeCivV = false
    /** How the map generator widens the coast. Empty: the generic way (`maxCoastExtension` passes, an ocean tile next to a coast tile
     *  becomes coast with 1 in 2). Otherwise as GenerateCoasts of MapGenerator.lua: ocean next to land becomes coast, then one pass
     *  for each entry in which an ocean tile next to a coast tile becomes coast with 1 in that number (Civ V: 4 and 4). */
    var coastExpansionDice: List<Int> = emptyList()

    // The culture a city needs for its next tile. false: 6 x (tiles claimed + 1.4813)^1.3 with the percentages multiplied.
    // true: the formula of Civ V (CvCity::GetJONSCultureThreshold), 15 + (10 x tiles gained by culture)^1.1, x 1.5 for city-states,
    // the percentages added and at least -85, x game speed, rounded down to 5
    var cultureCostLikeCivV = false

    // What happens to a treasury that the turn's income would take below zero. false: it goes into debt (which costs science).
    // true: as in Civ V (CvTreasury::DoGold) it is set to 0 and the rest of the deficit is lost; a civilization never has negative gold
    var treasuryNeverNegative = false

    // How tiles are bought and which tile the border takes. false: a tile costs 50 x (distance - 1) + 5 x the tiles its city claimed, can be bought
    // within the city's expand range, and the border takes the tile that [CityExpansionManager.rankTileForExpansion] ranks best.
    // true: as in Civ V (CvPlayer::GetBuyPlotCost, CvCity::GetBuyPlotCost, CvCity::CanBuyPlot, CvCity::GetBuyablePlotList): (50 + 5 x the tiles the
    // civilization bought, with the percentages of the "all cities" cost uniques added, x2 for a city-state) x (100 + 100/3 per point of the influence
    // path beyond the city's cheapest tile) %, a resource -100 points, x game speed, x the percentages of the city's own uniques, rounded down to 5;
    // within 3 tiles of the city; the border takes at random one of the tiles with the lowest influence score (path cost x 100, resources, water,
    // yields, natural wonders, adjacent owned tile). The step costs of the influence path are the `influenceCost` of the terrains
    var plotAcquisitionLikeCivV = false
    // Which tile one step beyond a unit's sight range is seen. false: one that is taller than the tile the unit stands on (Unciv).
    // true: as in Civ V (CvPlot::changeAdjacentSight, seeThroughLevel without forest and jungle): one taller than what blocks the line to it,
    // whatever the unit stands on, so a unit on a hill sees a hill or mountain behind flat land, and a flat forest behind flat land is not seen
    var sightBeyondRangeLikeCivV = false

    // The least sight range a unit has, whatever its uniques add up to. 1: it always sees the tiles around it (Unciv).
    // Civ V (CvUnit::visibilityRange): there is no least value, an embarked unit has EMBARKED_VISIBILITY_RANGE (0) plus what its promotions give,
    // so with 0 nothing lifts it back to 1 (with sightBeyondRangeLikeCivV a unit of range 0 still sees the ring of tiles around it)
    var minimumSightRange = 1
    // Units that are gifted to a city-state from anywhere on the map ("Gift unit to [city-state]"), not only inside its borders.
    // 0: not possible. Civ V (CvPlayer::AddIncomingUnit, MINOR_UNIT_GIFT_TRAVEL_TURNS): 3. The unit leaves its owner at once, a new unit of
    // the same type appears next to the city-state's capital after that many of the city-state's turns, and the influence comes then
    var unitGiftTravelTurns = 0
    // The chance of an air unit (fighter, missile...) to intercept an air attack. false: the "chance to intercept air attacks" of its uniques,
    // whatever its health. true: as in Civ V (CvUnit::currInterceptionProbability) that chance times its health / 100, rounded down,
    // for the choice of the best interceptor too; an interceptor with no chance left is not picked
    var airInterceptionChanceScalesWithHealth = false
    // How the countdown to the rebels of a very unhappy empire runs. false: a flag that starts over (full length) whenever the empire is not
    // very unhappy at the start of a turn. true: as in Civ V (CvPlayer::DoUpdateUprisings, DoResetUprisingCounter) a counter that only winds
    // down while the empire is very unhappy and keeps its value otherwise, seeded with half the length the first time and after an uprising with the full length
    var uprisingCounterLikeCivV = false
    // Whether the happiness a city has from its buildings, from policies and beliefs for each building of a kind, from the garrison policy and from
    // beliefs per city or on rivers is limited to the city's population (2/3 of it, rounded, for India). false: no limit, as before.
    // true: CvCity::GetLocalHappiness. Buildings with "Happiness from this building is not limited by the population of the city" are exempt.
    var localHappinessCappedByPopulation = false
    // Which happiness model the ruleset plays. false: the original's (one number for the whole civilization, summed from the luxuries, the cities
    // and the civilization's own sources, less the unhappiness of the cities and of public opinion; its effects are the uniques of GlobalUniques.json).
    // true: Vox Populi's per-city needs (CvCity::GetUnhappinessAggregated, CvCity::GetLocalHappiness, CvPlayer::DoUpdateTotalHappiness,
    // CvPlayer::DistributeHappinessToCities, CvPlayer::CalculateNetHappiness): the cities have needs (see [needs]), the empire a pool of happiness
    // that is handed to the cities, and the approval rating (happy citizens against unhappy ones) decides the empire's effects. The stat Happiness is
    // then the approval rating less [NeedsConstants.unhappyThreshold].
    var cityNeedsLikeVp = false
    // What "Gain [amount] [stat] <(modified by game speed)>" is multiplied by. false: the speed's statCostModifiers entry of the stat, rounded (as before).
    // true: Vox Populi's instant yields (CvPlayer::doInstantYield): the speed's instantYieldModifier (the original's InstantYieldPercent: 75, 100, 150 and 200 percent
    // for Quick, Standard, Epic and Marathon), cut off at the whole number. The events that the original does not scale (purchases, unit production, refunds, quest rewards,
    // bullying, combat experience, research agreements) are the uniques that do not have the modifier
    var instantYieldsUseInstantYieldPercent = false
    // Whether a unit with the unique "Before engaging in combat performs an extra ranged attack with [amount]% of melee combat strength" gets
    // no combat bonus for being fortified. false: it does. true: CvUnit::fortifyModifier returns 0 for a unit with ranged support fire (the Impi).
    var rangedSupportFireUnitsGetNoFortification = false
    // How much a unit that can speed up construction of a building (the Great Engineer) adds. false: (300 + 30 x population) x production cost
    // modifier of the game speed, at most what is left minus 1; offered even if the city completes it next turn anyway.
    // true: as in Civ V (CvUnit::getMaxHurryProduction, getHurryProduction, canHurry): the same in whole numbers with the speed's percentage,
    // at most all that is left to produce, and not offered when the production is completed next turn anyway.
    var unitHurryLikeCivV = false
    // Where the small deposits of strategic resources ("Minor deposits generated with weight") may be put, and how many.
    // minorDepositsOnFlatLandOnly false: on any land tile that is not blocked. true: as in AssignStartingPlots:GenerateGlobalResourcePlotLists
    // and PlaceSmallQuantitiesOfStrategics, only on land_list: flat land (no hills, mountains, marsh, flood plains, oasis or wonders) that has no feature
    // but forest or jungle, no resource and is no start. minorDepositFrequency: one deposit for this many tiles of that list (Civ V: 23)
    var minorDepositsOnFlatLandOnly = false
    var minorDepositFrequency = 30
    // Where a city-state may be placed in its region. false: not on land that is undesirable for a start (desert outside desert regions, snow, mountains),
    // not on tiles at the edge of the map. true: as AssignStartingPlots:CanPlaceCityStateAt: on any land tile that is no mountain and no snow
    var cityStateSitesLikeCivV = false

    // The happiness an annexed city of a conquered civilization costs. Default: its citizens count double, and the city costs 2 more ("Occupied City").
    // The Civ V rulesets: citizens x UNHAPPINESS_PER_OCCUPIED_POPULATION (1.34, CvPlayer::GetUnhappinessFromOccupiedCities) and no extra term
    var annexedCityCitizenUnhappinessFactor = 2f
    var annexedCityFlatUnhappiness = 2f
    // The damage (of 100 hit points) from a nuclear blast at which a civilian unit dies. 60: it dies when its health is down to 40 or less.
    // Civ V (CvUnitCombat::ApplyNuclearExplosionDamage, NUKE_NON_COMBAT_DEATH_THRESHOLD): 6, so every civilian in the blast dies
    var nukeCivilianDeathDamage = 60
    // The gold a disbanded unit gives, in percent of its production cost (times its health left and the game speed, plus what it carries).
    // 0: 5% of its purchase price, whatever its health. Civ V (CvUnit::GetScrapGold, DISBAND_UNIT_REFUND_PERCENT): 25
    var disbandRefundPercent = 0
    // Movement points extra for entering a hill that has a feature (forest, jungle, fallout...) on top, besides the feature's own cost.
    // 0: the feature's cost only. Civ V (CvUnitMovement::GetCostsForMove, HILLS_EXTRA_MOVEMENT): 1, so a forested hill costs 3
    var featureOnHillsExtraMovement = 0
    // The divisor of the production cost of a building to get the gold for selling it. 10: the raw cost / 10 (Unciv).
    // Civ V (CvCityBuildings::GetSellBuildingRefund): the production cost (game speed, era and modifiers included) / 4
    var buildingSaleDivisor = 10
    // The share of the alive major civilizations that must know a technology for the city-states to get it for free.
    // 0: more than half of them (Unciv). Civ V (CvTeam::DoMinorCivTech, GlobalDefines MINOR_CIV_TECH_PERCENT 40): at least max(1, majors x percent / 100)
    var minorCivTechPercent = 0
    // The percentage of the production stored in a unit or non-wonder building that is converted to gold (as for wonders, always 100%) when its limit is reached
    // and it can no longer be built, and of the production above the overflow cap when it is completed. 0: it is lost (Unciv).
    // Civ V: GlobalDefines MAXED_UNIT_GOLD_PERCENT and MAXED_BUILDING_GOLD_PERCENT, both 100 (CvCity::doCheckProduction, CvCity::popOrder)
    var maxedUnitGoldPercent = 0
    var maxedBuildingGoldPercent = 0
    // What a damaged city heals each turn. false: 20. true: as in Civ V (CvCity::doTurn) 20 (CITY_HIT_POINTS_HEALED_PER_TURN),
    // +1 in the capital of a major civilization, + (strength of the city's buildings x 100) / 500
    var cityHealLikeCivV = false
    // The countdowns of a city's demand for a resource. false: the turns until a city demands a resource are 15 + random(10) + 10 in a new capital, and both
    // that and the length of the We Love the King Day (20) are scaled by the game speed. true: as in Civ V (CvCity::DoSeedResourceDemandedCountdown, DoTestResourceDemanded)
    // the capital adds 25 (RESOURCE_DEMAND_COUNTDOWN_CAPITAL_ADD) and neither is scaled by the game speed
    var resourceDemandLikeCivV = false
    // Improvements on a tile with a resource. false: any improvement allowed on the terrain. true: as in Civ V (CvPlot::canHaveImprovement)
    // a tile whose resource the civilization can see takes only an improvement that improves that resource, or one with the unique
    // "Can be built on tiles with any resource" (Improvements.BuildableOnResources: Fort, Moai, the great person improvements)
    var improvementsOnResourcesLikeCivV = false
    // How much more combat points the next Great General or Admiral needs. false: 50 more each. true: as in Civ V
    // (CvPlayer::createGreatGeneral) the threshold of 200 grows by 100 x (earned / 10 + 1) with the number earned so far
    var greatGeneralThresholdLikeCivV = false
    // Percent of extra Great General points a land unit earns from fighting on a tile of its own civilization's territory, added to the
    // other bonuses (CvPlayer::recomputeGreatPeopleModifiers, CvUnit::changeExperience: COMBAT_EXPERIENCE_IN_BORDERS_PERCENT)
    var greatGeneralPercentInBorders = 0
    // Whether a unit that "Withdraws before melee combat" does so as in Civ V (CvUnit::CanWithdrawFromMelee, DoWithdrawFromMelee). false: it
    // withdraws when the chance of its unique comes up, to a random tile behind it. true: the chances of all its such uniques are added,
    // lowered by withdrawModEnemyMoves for each move of the attacker above 2 and by withdrawModBlockedTile for each of the three tiles behind
    // that it cannot retreat to, with no withdrawal at all if all three are blocked; it retreats to the tile straight behind it if it can
    var withdrawLikeCivV = false
    /** Percentage points added to the withdraw chance for each move the attacker has above 2 (WITHDRAW_MOD_ENEMY_MOVES); used if [withdrawLikeCivV] */
    var withdrawModEnemyMoves = -20
    /** Percentage points added to the withdraw chance for each blocked tile behind the unit (WITHDRAW_MOD_BLOCKED_TILE); used if [withdrawLikeCivV] */
    var withdrawModBlockedTile = -20
    // Whether a city can bombard an enemy city within its range. false: it cannot, only the units outside cities, as in Civ V
    // (CvCity::canRangeStrikeAt: "I don't want cities attacking each other directly")
    var citiesCanBombardCities = true
    // What taking tiles of a city-state ([UniqueType.OneTimeTakeOverTilesInRadius], the culture bomb) does to its influence with the taker.
    // The change is applied for each tile taken, or if cityStateInfluenceOncePerTilesTaken once for each city-state tiles were taken from
    // (CvUnit::PerformCultureBomb: CULTURE_BOMB_MINOR_FRIENDSHIP_CHANGE -50)
    var cityStateInfluenceChangeForTilesTaken = -15f
    var cityStateInfluenceOncePerTilesTaken = false

    // Constants used to calculate Unit Upgrade gold Cost (can only be modded all-or-nothing)
    // This is a data class for one reason only: The equality implementation enables Gdx Json to omit it when default (otherwise only the individual fields are omitted)
    data class UnitUpgradeCost(
        val base: Float = 10f,
        val perProduction: Float = 2f,
        val eraMultiplier: Float = 0f,  // 0.3 in Civ5 cpp sources but 0 in xml
        val exponent: Float = 1f,
        val roundTo: Int = 5
    )
    var unitUpgradeCost = UnitUpgradeCost()

    /**
     *  The numbers of Vox Populi's per-city needs, read only with [cityNeedsLikeVp] (can only be modded all-or-nothing, a data class so the Json
     *  serializer can omit it when default). The defaults are the GlobalDefines of Vox Populi 5.4.8: `DEF-CP` is
     *  `(1) Community Patch/Database Changes/Defines/CoreDefineChanges.sql`, `DEF-VP` is `(2) Vox Populi/Database Changes/DefineChanges.sql`.
     *  The fields marked "(S2)" are for conquest, puppets and golden ages, which read them in their own change.
     *
     *  Departures: VP's four median rates of change and four base modifiers are each one value here (all four are equal in VP); the city-state
     *  luxury multiplier `MINOR_POLICY_RESOURCE_HAPPINESS_MULTIPLIER` is not a field, the percentage of the unique `City-State luxuries ...` is it.
     */
    data class NeedsConstants(
        /** Approval rating (percent) under which the empire is unhappy (DEF-CP `UNHAPPY_THRESHOLD`), very unhappy (DEF-VP `VERY_UNHAPPY_THRESHOLD`) and super unhappy (DEF-VP `SUPER_UNHAPPY_THRESHOLD`) */
        val unhappyThreshold: Int = 50,
        val veryUnhappyThreshold: Int = 35,
        val superUnhappyThreshold: Int = 20,
        /** Percent of growth and of settler production lost per approval point under [unhappyThreshold] (DEF-CP `GLOBAL_GROWTH_PENALTY_PER_UNHAPPY`, `GLOBAL_SETTLER_PRODUCTION_PENALTY_PER_UNHAPPY`) */
        val globalGrowthPenaltyPerPoint: Float = 2.5f,
        val globalSettlerPenaltyPerPoint: Float = 2.5f,
        /** The most percent of production a settler or combat unit can lose to unhappiness (DEF-CP `UNHAPPY_MAX_UNIT_PRODUCTION_PENALTY`) */
        val maxUnitProductionPenalty: Int = -75,
        /** Percent growth per point of a city's happiness delta, above 0 and below (DEF-CP `LOCAL_HAPPINESS_FOOD_MODIFIER`, `LOCAL_UNHAPPINESS_FOOD_MODIFIER`) */
        val localHappinessGrowthPerPoint: Int = 2,
        val localUnhappinessGrowthPerPoint: Int = 10,
        /** Percent production of settlers and of combat units per point of a city's unhappiness over its happiness, negative (DEF-CP `LOCAL_UNHAPPY_SETTLER_PRODUCTION_PENALTY`, `LOCAL_UNHAPPY_COMBAT_UNIT_PRODUCTION_PENALTY`; the formula multiplies by -1 twice) */
        val localUnhappySettlerPenalty: Int = -10,
        val localUnhappyCombatUnitPenalty: Int = -10,
        /** Percent combat strength lost while very unhappy, half of it while only unhappy (DEF-VP `VERY_UNHAPPY_MAX_COMBAT_PENALTY`) */
        val veryUnhappyMaxCombatPenalty: Int = -20,
        /** The percentile of the cities' yields per citizen that is the median, of the technologies the civilizations know, and the population a city needs to count (DEF-CP `YIELD_MEDIAN_PERCENTILE`, `TECH_COUNT_MEDIAN_PERCENTILE`, `YIELD_MEDIAN_MIN_POP_REQUIREMENT`) */
        val medianPercentile: Int = 50,
        val techMedianPercentile: Int = 50,
        val medianMinPopulation: Int = 3,
        /** The share of the new median in the smoothed one (DEF-CP `DISTRESS_MEDIAN_RATE_CHANGE`, `POVERTY_`, `ILLITERACY_`, `BOREDOM_`, all 0.65) */
        val medianRateOfChange: Float = 0.65f,
        /** Percent of the modified median a citizen must make (DEF-CP `DISTRESS_MEDIAN_BASE_MODIFIER`, `POVERTY_`, `ILLITERACY_`, `BOREDOM_`, all 100) */
        val medianBaseModifier: Int = 100,
        /** Percent points the needs of the capital rise by (DEF-CP `CAPITAL_NEED_MODIFIER`), and hundredths of a percent point per citizen of the city (`CITY_SIZE_NEED_MODIFIER`) */
        val capitalNeedModifier: Int = 25,
        val citySizeNeedModifier: Int = 0,
        /** Hundredths of a percent point of the needs per technology above and below the median of the civilizations, and per percent of the technologies researched (DEF-CP `TECH_NEED_MODIFIER_PER_TECH_ABOVE_MEDIAN`, `..._BELOW_MEDIAN`, `TECH_NEED_MODIFIER_PERCENT_RESEARCHED`) */
        val techNeedPerTechAboveMedian: Int = 100,
        val techNeedPerTechBelowMedian: Int = -200,
        val techNeedPercentResearched: Int = 0,
        /** Hundredths of a percent point of the needs per city but the capital and the puppets, thousandths per citizen outside puppets, and the most the sum can be (DEF-CP `EMPIRE_SIZE_NEED_MODIFIER_CITIES`, `..._POP`, `..._CAP`) */
        val empireSizeNeedPerCity: Int = 500,
        val empireSizeNeedPerPopulation: Int = 125,
        val empireSizeNeedCap: Int = 100000,
        /** Unhappy citizens per religious minority citizen, starving food, pillaged tile and isolated citizen (DEF-CP `UNHAPPINESS_PER_RELIGIOUS_MINORITY_POP`, `..._PER_STARVING_POP`, `..._PER_PILLAGED_TILE`, `..._PER_ISOLATED_POP`) */
        val unhappinessPerReligiousMinority: Float = 0.5f,
        val unhappinessPerStarvingPop: Float = 1f,
        val unhappinessPerPillagedTile: Float = 0.5f,
        val unhappinessPerIsolatedPop: Float = 0.34f,
        /** Hundredths of an unhappy citizen per specialist, and the citizens of a puppet per unhappy one (DEF-CP `UNHAPPINESS_PER_SPECIALIST`, `UNHAPPINESS_PER_X_PUPPET_CITIZENS`) */
        val unhappinessPerSpecialist: Int = 100,
        val puppetCitizensPerUnhappiness: Int = 4,
        /** Unhappy citizens per citizen of an occupied city (DEF-VP `UNHAPPINESS_PER_OCCUPIED_POPULATION`, 1.34 in Brave New World) */
        val unhappinessPerOccupiedPopulation: Float = 1f,
        /** Happiness of each luxury resource the empire has (the luxuries' `Happiness` column in Vox Populi, 2, 4 in Brave New World) and of each natural wonder it owns (GlobalDefines `HAPPINESS_PER_NATURAL_WONDER`) */
        val happinessPerLuxury: Int = 2,
        val happinessPerNaturalWonder: Int = 1,
        /** (S2) Percent of the gold, science, culture, faith, tourism and golden age points a puppet yields (DEF-VP `PUPPET_YIELD_AND_SUPPLY_MODIFIER_MULTIPLICATIVE`) */
        val puppetYieldMultiplier: Int = 30,
        /** (S2) The turns of resistance after a conquest are population x numerator / denominator (CvPlayer::acquireCity) and annexing a city in resistance halves what is left (CvCity::DoAnnex) */
        val resistanceTurnsPopulationNumerator: Int = 2,
        val resistanceTurnsPopulationDenominator: Int = 3,
        val annexHalvesResistance: Boolean = true,
        /** (S2) The turns a very unhappy empire waits before a city revolts (DEF-CP `REVOLT_COUNTER_MIN`) */
        val revoltCounterMin: Int = 10,
        /** (S2) The rebels of an uprising are (this + (cities + a random number below the cities) x the next) / 100 (CvPlayer::DoUprising, GlobalDefines `UPRISING_NUM_BASE`, `UPRISING_NUM_CITY_COUNT`, unchanged from Brave New World) */
        val uprisingRebelsBase: Int = 100,
        val uprisingRebelsPerCity: Int = 20,
        /** (S2) The golden age threshold (CvPlayer::GetGoldenAgeProgressThreshold): the first one, the base of the later ones, the rise per golden age had, percent per city, and the divisor the shown threshold is rounded to (DEF-VP `GOLDEN_AGE_BASE_THRESHOLD_INITIAL`, `..._HAPPINESS`, `GOLDEN_AGE_EACH_GA_ADDITIONAL_HAPPINESS`, `GOLDEN_AGE_THRESHOLD_CITY_MULTIPLIER`, DEF-CP `GOLDEN_AGE_VISIBLE_THRESHOLD_DIVISOR`) */
        val goldenAgeThresholdInitial: Int = 1000,
        val goldenAgeThresholdBase: Int = 400,
        val goldenAgeThresholdPerGoldenAge: Int = 2000,
        val goldenAgeThresholdPerCityPercent: Int = 5,
        val goldenAgeVisibleDivisor: Int = 5,
        /** (P6) The city strength from which a city gives the happiness of the policy `HappinessPerCityOverStrengthThreshold` (DEF-CP `CITY_STRENGTH_THRESHOLD_FOR_BONUSES`, CvCity::GetHappinessFromPolicies); city strength is not Vox Populi's until the military package */
        val cityStrengthThresholdForBonuses: Int = 10,
        /** (P6) The most percent of its population a civilization's war weariness can cost in unhappy citizens (DEF-CP `WAR_WEARINESS_POPULATION_PERCENT_CAP`) */
        val warWearinessPopulationPercentCap: Int = 34,
    )
    var needs = NeedsConstants()

    /**
     *  The gold price of a construction from its production cost (CvGameCoreUtils.cpp: GetBasePurchaseCostFromProduction): `(production x [goldPerProduction]) ^ [exponent]`
     *  times the speed's `hurryModifier`. The defaults are Brave New World's GlobalDefines (`GOLD_PURCHASE_GOLD_PER_PRODUCTION` 30, `HURRY_GOLD_PRODUCTION_EXPONENT` 0.75);
     *  Vox Populi has 4 and 0.88 (DEF-VP `(2) Vox Populi/Database Changes/DefineChanges.sql:214-215`). Prices of buildings and units are rounded down
     *  to a multiple of [visibleDivisor] (`GOLD_PURCHASE_VISIBLE_DIVISOR`). Can only be modded all-or-nothing.
     *
     *  Departure: the source truncates the power to a whole number before the speed's percentage, this keeps the fraction (as Unciv always has),
     *  which changes a price by less than one gold before it is rounded down to [visibleDivisor].
     */
    data class PurchaseConstants(
        val goldPerProduction: Double = 30.0,
        val exponent: Double = 0.75,
        val visibleDivisor: Int = 10,
    )
    var purchase = PurchaseConstants()

    // Whether buying a building with gold is an investment (CvCity::PurchaseBuilding with MOD_BALANCE_BUILDING_INVESTMENTS, Vox Populi sets it). false: the building is built at once.
    // true: the gold price is [goldBuildingCostPercent] percent, the building stays in the queue (at the front if it was not in it) and its production cost falls
    // ([com.unciv.logic.city.PurchaseRules.investmentReduction]) to (100 + [buildingInvestmentBaseline]) percent of itself, with the percentage divided by
    // [buildingInvestmentWonderDivisor] for a world wonder, and never to less than one more production than the city has put into it. A building that is
    // invested in cannot be invested in again, and not when it is finished next turn anyway
    var buildingInvestments = false
    var buildingInvestmentBaseline = -50
    var buildingInvestmentWonderDivisor = 2
    // Percent of the gold price of a building that the investment costs, with [buildingInvestments] (HURRY_GOLD_BUILDING_COST_PERCENT, V:CvCity.cpp:12262-12266)
    var goldBuildingCostPercent = 100

    // The percent the production cost of a world wonder rises by for every world wonder the civilization has, by how many eras ago its technology's era was: the first
    // entry for the current era, the second for one era ago ... and the last entry for all earlier eras (CvPlayer::getProductionNeeded with
    // MOD_BALANCE_WORLD_WONDER_COST_INCREASE, BALANCE_WORLD_WONDER_SAME_ERA_COST_MODIFIER 25, ..._PREVIOUS_ERA_ 15, ..._SECOND_PREVIOUS_ERA_ 10, ..._EARLIER_ERA_ 5
    // in Vox Populi). Empty: no increase. A wonder without a technology does not count
    var worldWonderCostIncrease: List<Int> = emptyList()

    // Whether buying a unit sets a cooldown (CvCity::Purchase, V:CvCity.cpp:2485-2503, 31100): the unit's `purchaseCooldown` (less the city's
    // reduction from its buildings, [com.unciv.models.ruleset.Building.purchaseCooldownReduction]) for gold, its `globalFaithPurchaseCooldown` and
    // `localFaithPurchaseCooldown` (times the game speed's production cost percentage) for faith, during which that kind of unit cannot be bought
    var purchaseCooldownEnabled = false

    // How the "[relativeAmount]% Gold cost of upgrading" uniques of a civilization combine. false: they are multiplied (-33% twice gives x0.4489).
    // true: as in Civ V (CvPlayer::SetUnitUpgradeCostMod, CvUnit::upgradePrice) the percentages are summed (-33% twice gives x0.34) and the sum is at least [unitUpgradeCostDiscountMax]
    var unitUpgradeCostModsAreSummed = false
    // The lowest sum of the percentages of the upgrade cost uniques, only used with unitUpgradeCostModsAreSummed (GlobalDefines UNIT_UPGRADE_COST_DISCOUNT_MAX)
    var unitUpgradeCostDiscountMax = -75

    // How the gold price of a unit upgrade is made. false: [unitUpgradeCost] (base + gold per production of the cost difference, times the game speed), as before.
    // true: Vox Populi's option BALANCE_UNIT_UPGRADE_COST (GetBaseUnitUpgradeCost, V:CvGameCoreUtils.cpp:1361-1379): the difference of the gold purchase prices of the two units
    // ([PurchaseConstants] curve of their production costs with the game speed's and the start era's training percentages, the game speed's hurry percentage once; half the new
    // price if the old unit costs nothing), 0 at the least; [unitUpgradeCost]'s base, per production and era multiplier are not read, its exponent and rounding are
    var unitUpgradeCostFromPurchasePrice = false

    // NaturalWonderGenerator uses these to determine the number of Natural Wonders to spawn for a given map size.
    // With these values, radius * mul + add gives a 1-2-3-4-5 progression for Unciv predefined map sizes and a 2-3-4-5-6-7 progression for the original Civ5 map sizes.
    // 0.124 = (Civ5.Huge.getHexagonalRadiusForArea(w*h) - Civ5.Duel.getHexagonalRadiusForArea(w*h)) / 5 (if you do not round in the radius function)
    // The other constant is empiric to avoid an ugly jump in the progression.
    var naturalWonderCountMultiplier = 0.124f
    var naturalWonderCountAddedConstant = 0.1f

    // MapGenerator.spreadAncientRuins: number of ruins = suitable tile count * this
    var ancientRuinCountMultiplier = 0.025f
    /** Place the ancient ruins as AddGoodies of the Civ V scripts does: per landmass of at least 3 tiles one per [tilesPerAncientRuinLikeCivV]
     *  land tiles (rounded to nearest), on tiles at least 3 away from each other and more than 3 from any start, without resource.
     *  Replaces [ancientRuinCountMultiplier] and the spreading over the whole map. */
    var ancientRuinsLikeCivV = false
    var tilesPerAncientRuinLikeCivV = 40
    // MapGenerator.spawnLakesAndCoasts: Water bodies up to this tile count become Lakes
    var maxLakeSize = 10
    // RiverGenerator: river frequency and length bounds
    var riverCountMultiplier = 0.01f
    var minRiverLength = 5
    var maxRiverLength = 666  // Do not set to less than the maximal map radius. Set to 0 to disable river generation.

    // Factors in formula for Maximum Number of foundable Religions
    var religionLimitBase = 1
    var religionLimitMultiplier = 0.5f

    // Factors in formula for pantheon cost
    var pantheonBase = 10
    var pantheonGrowth = 5

    // AI behaviour
    var workboatAutomationSearchMaxTiles = 37

    // Civilization
    var minimumWarDuration = 10
    var baseTurnsUntilRevolt = 4
    var cityStateElectionTurns = 15
    // false: cityStateElectionTurns is the interval on every game speed. true: as in Civ V (CvGame::GetTurnsBetweenMinorCivElections) it is multiplied by the modifier of the game speed
    // (MinorCivElectionFreqMod 67, 100, 150, 300 percent for Quick to Marathon, equal to the speed modifier of the Civ V rulesets), rounded down
    var cityStateElectionsScaleWithGameSpeed = false

    // Economy
    var maxGoldTradeOffer = 10000

    // City State Tribute: maximum points from Force ranking towards reaching Tribute willingness threshold
    var tributeGlobalModifier = 100 // 75 in BNW
    var tributeLocalModifier = 100 // 125 in BNW

    // Espionage
    var maxSpyRank = 3
    // How much of a skill bonus each rank gives.
    // Rank 0 is 100, rank 1 is 130, and so on.
    // Half as much for a coup.
    var spyRankSkillPercentBonus = 30
    // Rank 2 is +25% tech steal rate, rank 3 is +50%, and so on
    var spyRankStealPercentBonus = 25
    // Steal cost equal to 125% of the most expensive stealable tech
    var spyTechStealCostModifier = 1.25f
    
    // Score value of things
    var scoreFromCities = 10 // 8 in BNW
    var scoreFromPopulation = 3 // 4 in BNW
    var scoreFromWonders = 40 // 25 in BNW
    var scoreFromGreatWorks = 0 // 4 in BNW
    // Per adopted policy: the openers, policies and finishers of the branches and the ideology's tenets, not the choice of an ideology. 4 in BNW
    var scoreFromPolicies = 0
    // Per belief of the civilization's founded religion (not a pantheon alone), and per city of any owner that follows it. 20 and 1 in BNW
    var scoreFromReligionBeliefs = 0
    var scoreFromReligionCities = 0
    // The map size at which Cities, Population and Land score at face value; smaller maps score more, larger maps less. 4160 (80 x 52) in Civ V
    var scoreBaseMapTiles = 1276

    // Trade routes (CvTradeClasses.cpp and GlobalDefines.xml; the values are those of Brave New World)
    // Gold of a route for its origin: base + (the gold of the origin city divided by this + the same of the destination city) + bonuses,
    // multiplied by the percentages below. The destination gets the base and its own bonuses, multiplied the same way
    var tradeRouteBaseGold = 1f
    var tradeRouteCityGoldDivisor = 20
    var tradeRouteGoldPerDifferentResource = 0.5f
    var tradeRouteSeaBonusPercent = 100
    var tradeRouteRiverBonusPercent = 25
    // A route lasts the whole number of round trips that comes closest to this many turns (but at least two round trips)
    var tradeRouteTargetTurns = 30
    // Gold for plundering a route. A route by sea is multiplied by the sea bonus
    var tradeRoutePlunderGold = 100
    // Routes within the own civilization: food or production in the destination city, base + this per era of the destination civilization
    var tradeRouteDomesticBaseYield = 3f
    var tradeRouteDomesticYieldPerEra = 0.5f

    // Vox Populi's trade route model instead of Brave New World's (CvPlayerTrade::GetTradeConnectionValueTimes100, V:CvTradeClasses.cpp:3604-4247; the option BALANCE_VP and
    // BALANCE_TRADE_ROUTE_PROXIMITY_PENALTY, BALANCE_TRADE_ROUTE_DESTINATION_RESTRICTION). false: the constants above and the rules as they are. true: the numbers of
    // [tradeRouteVp] and the Eras' `tradeRouteFoodBonus` and `tradeRouteProductionBonus`, with the route rules of the original. Nothing reads these yet: slices T3 and T4 of
    // phaseE/vp-trade-plan.md add the model, and they are what the flag will turn on
    var tradeRouteModelLikeVp = false
    /** The defines of Vox Populi's route model (DB vp `Defines`, `(1) Community Patch/Database Changes/Defines/CoreDefineChanges.sql:1007` and DEF-VP; the values of Vox Populi 5.4.8).
     *  Can only be modded all-or-nothing. Percentages are of 100; "times 100" are hundredths */
    data class TradeRouteVpConstants(
        /** INTERNATIONAL_TRADE_BASE: the base of an international route is this times (era + 2), in hundredths of gold */
        val internationalTradeBase: Int = 80,
        /** INTERNATIONAL_TRADE_CITY_GPT_DIVISOR: each city's gold per turn times 100 divided by this is added */
        val cityGoldDivisor: Int = 80,
        /** TRADE_ROUTE_BASE_SEA_MODIFIER and ..._LAND_MODIFIER */
        val seaModifier: Int = 25,
        val landModifier: Int = 0,
        /** TRADE_ROUTE_DIFFERENT_RESOURCE_VALUE: percent per resource that only one of the two cities has */
        val differentResourceValue: Int = 5,
        /** TRADE_ROUTE_SCIENCE_DIVISOR_TIMES100 and ..._CULTURE_DIVISOR_TIMES100 */
        val scienceDivisorTimes100: Int = 125,
        val cultureDivisorTimes100: Int = 125,
        /** TRADE_ROUTE_BASE_FOOD_VALUE and ..._PRODUCTION_VALUE, hundredths */
        val baseFoodValue: Int = 600,
        val baseProductionValue: Int = 600,
        /** TRADE_ROUTE_CS_ALLY_GOLD and ..._FRIEND_GOLD (per era), ..._CS_ALLY_SCIENCE_DELTA and ..._CULTURE_DELTA */
        val cityStateAllyGold: Int = 2,
        val cityStateFriendGold: Int = 1,
        val allyScienceDelta: Int = 1,
        val allyCultureDelta: Int = 1,
        /** OPEN_BORDERS_MODIFIER_TRADE_GOLD: percent when both civilizations have open borders (half of it when one has) */
        val openBordersModifier: Int = 20,
        /** TRADE_ROUTE_RIVER_CITY_MODIFIER: percent of a land route's gold when a city is on a river */
        val riverModifier: Int = 25,
        /** The most the proximity reduction takes off a route's modifier, percent (V:CvTradeClasses.cpp:3372-3418) */
        val proximityMax: Int = 50,
        /** TRADE_ROUTE_BASE_TARGET_TURNS: before the game speed's `tradeRouteSpeedModifier` */
        val targetTurns: Int = 30,
    )
    var tradeRouteVp = TradeRouteVpConstants()

    /** What the route range (the unit's base range plus the traits' and techs') is multiplied by for the size of the map: the percentage of the entry with the greatest
     *  `tiles` that the map has at least (the original's `Worlds.TradeRouteDistanceMod`, V:CvTradeClasses.cpp:5332-5390: 80 Duel and Tiny, 90 Small, 100 Standard, 130 Large,
     *  160 Huge). Empty: 100 percent. Not read yet: slice T4 of phaseE/vp-trade-plan.md (O8: by the number of tiles until the world sizes have rule tables) */
    data class WorldTradeRouteDistance(val tiles: Int = 0, val percent: Int = 100)
    var worldTradeRouteDistance: List<WorldTradeRouteDistance> = emptyList()

    // Rules of the deals (CvDealClasses.cpp). dealsBlockTemporaryForPermanent: a deal in which the AI gives something temporary (gold per turn, resources, open borders,
    // pacts, agreements) and gets something permanent (gold, maps, cities, techs) is refused (BlockTemporaryForPermanentTrade, V:CvDealClasses.cpp:1368-1470), except with
    // dealHumanPermanentForAiTemporary for a human who gives the permanent item (MOD_DEALAI_HUMAN_PERMANENT_FOR_AI_TEMPORARY, 1 in Vox Populi; only read with the rule).
    // dealNoPeacetimeSellingFoundedCities: the founder does not sell a city it founded unless at war (MOD_DEALAI_NO_PEACETIME_SELLING_FOUNDED_CITIES, V:CvDealClasses.cpp:649).
    // Not read yet: slice T4 of phaseE/vp-trade-plan.md
    var dealsBlockTemporaryForPermanent = false
    var dealHumanPermanentForAiTemporary = true
    var dealNoPeacetimeSellingFoundedCities = false

    /** The shares of the copies of a resource on the map that make a monopoly (GLOBAL_RESOURCE_MONOPOLY_THRESHOLD 50 and STRATEGIC_RESOURCE_MONOPOLY_THRESHOLD 25,
     *  V:CvPlayer.cpp:39139-39232), in percent. Only read for resources that are `isMonopoly`. Can only be modded all-or-nothing */
    data class MonopolyConstants(
        val globalThreshold: Int = 50,
        val strategicThreshold: Int = 25,
    )
    var monopoly = MonopolyConstants()

    // World Congress (CvVotingClasses.cpp, GlobalDefines.xml; the values are those of Brave New World)
    /** Civilizations that propose resolutions in a session (LEAGUE_PROPOSERS_PER_SESSION) */
    var leagueProposersPerSession = 2
    /** Resolutions each of them proposes */
    var leagueProposalsPerProposer = 1
    /** The turns ahead of a session that the player is warned */
    var leagueSessionSoonWarningTurns = 5
    /** Turns between sessions when no era has sessions, at Standard speed */
    var leagueFallbackSessionInterval = 12
    /** Delegates of each civilization when no era has sessions */
    var leagueFallbackDelegates = 1
    /** Delegates that the civilizations leading the vote get more when the World Leader vote fails */
    var leagueLeadersVoteBonusCount = 2
    /** The votes needed to elect the World Leader are the sum of a civilization part and a city-state part, each `max(0, coefficient * ln(count) + constant)` (CvGame::DoUpdateDiploVictory) */
    var leagueWorldLeaderCivCoefficient = 1.443f
    var leagueWorldLeaderCivConstant = 7f
    var leagueWorldLeaderCityStateCoefficient = 16.023f
    var leagueWorldLeaderCityStateConstant = -13.758f
    /** Proposals the host of the World Congress makes in a regular session */
    var leagueRegularHostProposals = 1
    /** League projects: the contribution tier 1 and 2 begin at these multiples of a civilization's share of the cost (LEAGUE_PROJECT_REWARD_TIER_1_THRESHOLD and _2_) */
    var leagueProjectTier1Threshold = 0.5f
    var leagueProjectTier2Threshold = 1f
    /** League projects: the civilizations are told when a project has reached this percent of its cost (PROJECT_PROGRESS_PERCENT_WARNING) */
    var leagueProjectProgressWarningPercent = 33

    // Great Works: culture that each one gives its city, before the bonuses of policies
    var greatWorkBaseCulture = 2

    // Tourism (CvCultureClasses.cpp and GlobalDefines.xml, the values are those of Brave New World)
    // Tourism each Great Work gives its city, before bonuses
    var greatWorkBaseTourism = 2
    // Percent more tourism towards civilizations that share our religion, have open borders with us or are connected to us by trade routes.
    // The original takes the same value for all three
    var tourismModifierSharedReligion = 25
    var tourismModifierOpenBorders = 25
    var tourismModifierTradeRoute = 25
    // A concert tour is worth at least this much tourism, before the game speed
    var concertTourMinimumTourism = 100
    // A civilization is Exotic, Familiar, Popular, Influential or Dominant over another once its tourism towards it, added up over the game,
    // is this percent of the culture the other has made in the whole game
    var influencePercentExotic = 10
    var influencePercentFamiliar = 30
    var influencePercentPopular = 60
    var influencePercentInfluential = 100
    var influencePercentDominant = 200

    // Ideologies (CvPolicyClasses.cpp and GlobalDefines.xml, Brave New World)
    // Changing the ideology costs this many tenets (those that are left become free tenets) and this many turns of anarchy
    var ideologyTenetsLostOnSwitch = 2
    var ideologyAnarchyTurns = 2
    // Percent less tourism between civilizations that follow different ideologies
    var tourismModifierDifferentIdeologies = -34

    // The public opinion gets worse (Dissidents, Civil Resistance, Revolutionary Wave) from these differences of the pressure of other ideologies on the own
    var publicOpinionCivilResistance = 3
    var publicOpinionRevolutionaryWave = 5

    // Archaeology (CvGame.cpp and GlobalDefines.xml, Brave New World). The sites appear once, with 5 to 8 for every major civilization;
    // this share of them is hidden until a policy is adopted, and this share of those holds a writing
    var archaeologySitesPerCivMin = 5
    var archaeologySitesPerCivMax = 8
    var archaeologyPercentHidden = 30
    var archaeologyPercentHiddenWriting = 30
    // The resources a site is, and the improvements that digging and a Landmark are
    var archaeologySiteResource = "Antiquity site"
    var archaeologyHiddenSiteResource = "Hidden Antiquity site"
    var archaeologicalDigImprovement = "Archaeological Dig"
    var landmarkImprovement = "Landmark"
    // The technology that makes the visible sites known, whose civilizations are told when they appear
    var archaeologySiteTech = "Archaeology"
    // Influence with a city-state when we build a Landmark on its land
    var landmarkInfluenceWithCityStates = 50

    // Great People from points (CvCityCitizens::GetSpecialistUpgradeThreshold, GREAT_PERSON_THRESHOLD_BASE and _INCREASE in GlobalDefines.xml):
    // a city needs base + increase × (Great People of the group gained so far) points, scaled by game speed and starting era
    var greatPersonThresholdBase = 100
    var greatPersonThresholdIncrease = 100

    // UI: If set >= 0, ImprovementPicker will silently skip improvements whose tech requirement is more advanced than your current Era + this
    var maxImprovementTechErasForward = -1

    fun merge(other: ModConstants) {
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            val value = field.get(other)
            if (field.get(defaults).equals(value)) continue
            field.set(this, value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModConstants) return false
        return equalsReflected(other)
    }

    override fun hashCode(): Int {
        // Note: This is of course heavily dependent on iteration order.
        // Java reflection uses declaration order, but its doc claims "The elements in the returned array are not sorted and are not in any particular order."
        // kotlin reflection is alphabetically sorted. Embarrassingly, the best documentation guarantee seems to be: https://youtrack.jetbrains.com/issue/KT-41042
        // But - let's rely on at least the order being deterministic over instances of the same class in both reflection engines, so which we use is moot.
        // A kotlin version of this (different result!): `this::class.declaredMemberProperties.fold(0) { a, b -> a * 31 + b.getter.call(this).hashCode() }`
        var result = 0
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            result = result.withHash(field.get(this).hashCode())
        }
        return result
    }

    private fun equalsReflected(other: ModConstants): Boolean {
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            if (!field.get(this).equals(field.get(other))) return false
        }
        return true
    }

    /** Debug only so far */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            if (field.get(this).equals(field.get(defaults))) continue
            sb.append(field.name)
            sb.append(':')
            sb.append(field.get(this))
            sb.append(',')
        }
        sb.deleteCharAt(sb.length - 1) // remove extra ',' with StringBuilder method
        sb.append('}')
        return sb.toString().takeUnless { it == "}" } ?: "defaults"
    }

    companion object {
        /** As merge will need a default instance repeatedly, store it as static */
        // Note Json will not use this but get a fresh instance every time. A fix, if possible, could get messy.
        val defaults = ModConstants()
    }
}
