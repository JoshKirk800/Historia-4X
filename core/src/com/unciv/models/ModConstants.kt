package com.unciv.models

import com.unciv.models.ruleset.CityCombatConstants
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

    /** VP 5.4.8 CvUnitCombat::ResolveMeleeCombat: only the first attack each turn grants attacker XP. */
    var combatXpFirstAttackOnly = false

    /** VP 5.4.8 CvCity::SetRetroactivePromotion grants trained promotions to existing units. */
    var retroactiveCityPromotionsLikeVp = false

    /** VP 5.4.8 IsPromotionValidForCivilianUnitType checks the specific civilian unit type. */
    var civilianPromotionApplicabilityLikeVp = false

    /** VP 5.4.8 CvUnit::maxXPValue and changeExperienceTimes100 cap awards against barbarians and minors. */
    var combatXpCapsLikeVp = false

    // --- Mounted flag (StageMounted) ---
    /** VP 5.4.8 CvUnitEntry::IsMounted, CvUnit::unitCombatModifier / GetMaxAttackStrength / GetMaxDefenseStrength ("hack: mounted units can have secondary
     *  combat class") and CvUnit::canAcquirePromotion (`MountedOnly`): a unit with the unique `Is a mounted unit` is also matched by `<vs [Mounted] units>`
     *  strength conditionals when its own unit type is not Mounted, and a promotion with `Only for mounted units` is refused to other units. false: neither unique is read. */
    var mountedUnitFlagLikeVp = false
    var maxXPfromCityStates = -1

    /** VP 5.4.8 CvUnit::GetDamageCombatModifier applies wound penalties to strength, except ranged defence. */
    var combatHealthStrengthLikeVp = false
    var enemyCombatAurasLikeVp = false

    /** VP 5.4.8 CvUnit::GetMaxHitPoints: a unit's maximum hit points are the base 100 plus the sum of the promotions' `MaxHitPointsChange`
     *  (the unique `[amount] maximum hit points`) instead of always 100. Health, healing, damage, the wounded test, the captured and the
     *  upgraded unit's health follow the unit's own maximum (CvUnit::setDamage, GetScrapGold, CvUnit::convert, CvUnit::GetMaxHitPoints);
     *  the health lost stays the same when a promotion changes the maximum (CvUnit::changeMaxHitPointsChange). false: 100 and the unique is not read. */
    var unitMaxHitPointsLikeVp = false

    /** VP 5.4.8 CvUnit::getCaptureUnitType, getCaptureDefinition, createCaptureUnit: a captured civilian becomes the capturer's own unit of the unit's `Capture` class
     *  (the unique `Becomes [baseUnitFilter] when captured`, resolved with the capturer's unique replacements) instead of the hard-coded Settler to Worker rule.
     *  With barbarians involved (owner or capturer) the unit stays as it is; a city-state destroys a resulting settler. Units without the unique follow the old rules. false: Settlers become Workers. */
    var captureClassLikeVp = false

    // ---- StageForms2: promotion and unit columns of Vox Populi, each off (the uniques are not read) until the military flip ----
    /** VP 5.4.8 CvUnit::GetFlankPower (UnitPromotions.ExtraFlankPower), read by CvPlot::GetNumEnemyUnitsAdjacent with bConsiderFlanking in CvPlot::GetEffectiveFlankingBonus:
     *  a unit that supports a flank counts as 1 plus the sum of its `[amount] extra flanking power` instead of always 1. */
    var flankPowerLikeVp = false

    /** VP 5.4.8 CvUnitMovement::GetCostsForMove, GetMovementCostMultiplierFromPromotions, GetMovementCostAdderFromPromotions (UnitPromotions_Terrains / _Features IgnoreTerrainCostIn,
     *  HalfMove, ExtraMove, with MOD_BALANCE_SANE_UNIT_MOVEMENT_COST off as in VP): the uniques `Ignores terrain cost in [tileFilter] tiles`, `Half movement in [tileFilter] tiles` and
     *  `[amount] extra movement cost in [tileFilter] tiles` change the cost of entering a tile, in every movement cost, path search and reach calculation. */
    var terrainMovementPromotionsLikeVp = false

    /** VP 5.4.8 CvPlot::setRevealed (counts the tiles a unit reveals, weighted by BALANCE_SCOUT_XP_RANDOM_VALUE) and CvUnit::setXY (awards BALANCE_SCOUT_XP_DENOMINATOR points per XP):
     *  the unique `Gains XP from scouting`. */
    var scoutingXpLikeVp = false
    /** VP 5.4.8 BALANCE_SCOUT_XP_RANDOM_VALUE: the points of a natural wonder a unit reveals (a third of it for ancient ruins, a fourth for a resource). Read with [scoutingXpLikeVp]. */
    var scoutXpRandomValue = 12
    /** VP 5.4.8 BALANCE_SCOUT_XP_DENOMINATOR: the points of revealed tiles that make one experience point. Read with [scoutingXpLikeVp]. */
    var scoutXpDenominator = 10

    /** VP 5.4.8 CvUnit::pillage (changeExperienceTimes100(GetXPFromPillaging() * 100) for an improvement) and CvUnit::shouldPillage (a unit with XP from pillaging pillages whenever it can):
     *  the unique `[amount] XP when pillaging`. */
    var pillageXpLikeVp = false

    /** VP 5.4.8 CvUnit::convert (bIsGift), CvMinorCivIncomingUnitGift::applyToUnit: a gifted unit does not keep the promotions with the unique `Lost when the unit is gifted`, unless its
     *  unit type gives them for free. */
    var giftPromotionLossLikeVp = false

    /** VP 5.4.8 CvGame::GetRandomUniqueUnitType (IsMinorCivGift, IsInvalidMinorCivGift), CvPlayer::GetCompetitiveSpawnUnitType with bMinorCivGift, CvPlayer::canTrainUnit: the uniques
     *  `Can be gifted by city-states` and `Cannot be gifted by city-states` take part in the choice of a city-state's unique unit and of the unit it gives its patron. */
    var cityStateGiftUnitFlagsLikeVp = false

    // --- Barbarian camps (StageBarbarians, VP slice M8) ---
    /** VP 5.4.8 CvBarbarians::DoCamps, ActivateBarbSpawner, DoBarbSpawnerAttacked, DoBarbCampCleared, SpawnBarbarianUnits and GetRandomBarbarianUnitType, read through
     *  [barbarianConstants] (see [com.unciv.logic.automation.civilization.BarbarianCampsLikeVp]): camps are scheduled by the game turns elapsed (a percentage of the valid tiles on
     *  the initial turn, then one every [BarbarianConstants.campSpawnRate] turns, no cap on their number), keep minimum distances from capitals, camps, barbarian cities and
     *  recently cleared camps, and a per-camp spawn counter drives the units (several at once, capped by the barbarians nearby, boats from [BarbarianConstants.navalUnitStartTurn]).
     *  false: the Brave New World model of [com.unciv.logic.automation.civilization.BarbarianManager] (a camp target from the fog, a roll each turn, 8 to 12 turns between spawns). */
    var barbariansLikeVp = false

    /** The GlobalDefines BARBARIAN_* and MAX_BARBARIANS_FROM_* of Vox Populi, read only with [barbariansLikeVp] (can only be modded all-or-nothing, a data class so the Json serializer
     *  can omit it when default). Values of Vox Populi 5.4.8 after the Community Patch and Vox Populi DefineChanges; the original game's CHILL option does not exist in
     *  Unciv, so only the RAGING variants are modelled. Percentages and the `CampFirstTurnPercent*` are as in the source: the initial camp count is
     *  `(CampFirstTurnPercentOfTarget + startEra * CampFirstTurnPercentPerEra) * validTiles / 1000`, so 10 means 1%. */
    data class BarbarianConstants(
        /** BARBARIAN_INITIAL_SPAWN_TURN: camps begin once this many game turns have elapsed (0 in Community Patch) */
        val initialSpawnTurn: Int = 2,
        /** BARBARIAN_INITIAL_SPAWN_TURN_FROM_SPAWNER: camps and cities start spawning units after this many turns */
        val initialSpawnTurnFromSpawner: Int = 10,
        /** BARBARIAN_INITIAL_SPAWN_SPAWNER_DELAY: turns added to the first counter of camps made on the initial turn */
        val initialSpawnSpawnerDelay: Int = 0,
        /** BARBARIAN_CAMP_FIRST_TURN_PERCENT_OF_TARGET_TO_ADD (VP 10, CP 33) */
        val campFirstTurnPercentOfTarget: Int = 10,
        /** BARBARIAN_CAMP_FIRST_TURN_PERCENT_PER_ERA, times the starting era's number */
        val campFirstTurnPercentPerEra: Int = 5,
        /** BARBARIAN_CAMP_SPAWN_RATE and ..._RAGING: a camp is placed every this many turns after the initial turn (at least 1) */
        val campSpawnRate: Int = 2,
        val campSpawnRateRaging: Int = -1,
        /** BARBARIAN_CAMP_NUM_AFTER_INITIAL and ..._RAGING: camps placed on such a turn */
        val campNumAfterInitial: Int = 1,
        val campNumAfterInitialRaging: Int = 0,
        /** BARBARIAN_CAMP_MINIMUM_DISTANCE_CAPITAL / _ANOTHER_CAMP (camps and barbarian cities) / _RECENTLY_CLEARED_CAMP; 0 turns the rule off */
        val campMinimumDistanceCapital: Int = 4,
        val campMinimumDistanceAnotherCamp: Int = 4,
        val campMinimumDistanceRecentlyClearedCamp: Int = 2,
        /** BARBARIAN_CAMP_CLEARED_MIN_TURNS_TO_RESPAWN: turns a cleared camp or city counts as recently cleared */
        val campClearedMinTurnsToRespawn: Int = 15,
        /** BARBARIAN_CAMP_MINIMUM_ISLAND_SIZE (CP 2) */
        val campMinimumIslandSize: Int = 1,
        /** BARBARIAN_CAMP_COASTAL_SPAWN_ROLL: percent chance that a camp is placed on the coast if a coastal tile is valid */
        val campCoastalSpawnRoll: Int = 33,
        /** CustomModOptions BALANCE_ENCAMPMENTS_SPAWN_ON_VISIBLE_TILES, enabled by Vox Populi: camps may appear on tiles a civilization can see */
        val campsOnVisibleTiles: Boolean = true,
        /** BARBARIAN_MIN_SPAWN_DELAY */
        val minSpawnDelay: Int = 2,
        /** BARBARIAN_SPAWN_DELAY_FROM_ENCAMPMENT, _RAND, _RAGING_MULTIPLIER, BARBARIAN_ENCAMPMENT_SPAWN_DELAY_REDUCTION_PER_SPAWN, _MAX_SPAWN_DELAY_REDUCTION,
         *  BARBARIAN_ENCAMPMENT_ATTACKED_DELAY_MULTIPLIER */
        val encampmentSpawnDelay: Int = 12,
        val encampmentSpawnDelayRand: Int = 9,
        val encampmentSpawnDelayRagingMultiplier: Int = 50,
        val encampmentSpawnDelayReductionPerSpawn: Int = -1,
        val encampmentMaxSpawnDelayReduction: Int = -3,
        val encampmentAttackedDelayMultiplier: Int = 50,
        /** The same for cities: BARBARIAN_SPAWN_DELAY_FROM_CITY, _RAND, _RAGING_MULTIPLIER, BARBARIAN_CITY_SPAWN_DELAY_REDUCTION_PER_SPAWN, BARBARIAN_CITY_MAX_SPAWN_DELAY_REDUCTION,
         *  BARBARIAN_CITY_ATTACKED_DELAY_MULTIPLIER */
        val citySpawnDelay: Int = 6,
        val citySpawnDelayRand: Int = 4,
        val citySpawnDelayRagingMultiplier: Int = 50,
        val citySpawnDelayReductionPerSpawn: Int = -1,
        val cityMaxSpawnDelayReduction: Int = -3,
        val cityAttackedDelayMultiplier: Int = 50,
        /** BARBARIAN_NUM_UNITS_INITIAL_ENCAMPMENT_CREATION_SPAWN (+_RAGING, +_PER_ERA in percent of an era): units of a camp made on the initial turn */
        val numUnitsInitialEncampmentCreation: Int = 2,
        val numUnitsInitialEncampmentCreationRaging: Int = 0,
        val numUnitsInitialEncampmentCreationPerEra: Int = 0,
        /** BARBARIAN_NUM_UNITS_ENCAMPMENT_CREATION_SPAWN: units of a camp made later */
        val numUnitsEncampmentCreation: Int = 2,
        val numUnitsEncampmentCreationRaging: Int = 0,
        val numUnitsEncampmentCreationPerEra: Int = 0,
        /** BARBARIAN_NUM_UNITS_PER_ENCAMPMENT_SPAWN: units a camp spawns when its counter runs out */
        val numUnitsPerEncampmentSpawn: Int = 1,
        val numUnitsPerEncampmentSpawnRaging: Int = 0,
        val numUnitsPerEncampmentSpawnPerEra: Int = 0,
        /** BARBARIAN_NUM_UNITS_PER_CITY_SPAWN: units a barbarian city spawns */
        val numUnitsPerCitySpawn: Int = 1,
        val numUnitsPerCitySpawnRaging: Int = 0,
        val numUnitsPerCitySpawnPerEra: Int = 0,
        /** MAX_BARBARIANS_FROM_CAMP_NEARBY / _RANGE and MAX_BARBARIANS_FROM_CITY_NEARBY / _RANGE: no spawn while this many barbarian military units are within the range */
        val maxBarbariansFromCampNearby: Int = 2,
        val maxBarbariansFromCampNearbyRange: Int = 4,
        val maxBarbariansFromCityNearby: Int = 4,
        val maxBarbariansFromCityNearbyRange: Int = 4,
        /** BARBARIAN_NAVAL_UNIT_START_TURN_SPAWN (CP 30): game turn from which boats may be spawned next to a camp or city */
        val navalUnitStartTurn: Int = 20,
        /** BARBARIAN_UNIT_SPAWN_NO_RESOURCE_MULTIPLIER, _PREFERRED_TYPE (percent), _NUM_CANDIDATES (the best n are drawn from by weight) */
        val unitSpawnNoResourceMultiplier: Int = 150,
        val unitSpawnPreferredTypeMultiplier: Int = 200,
        val unitSpawnNumCandidates: Int = 5,
        /** The ruleset has no `DefaultUnitAI`, so the preferred AI types of GetRandomBarbarianUnitType are approximated: UNITAI_FAST_ATTACK is a melee land unit of one of
         *  [fastAttackUnitTypes], UNITAI_DEFENSE a melee land unit of one of [defenseUnitTypes]; UNITAI_RANGED is any ranged land unit without indirect fire,
         *  UNITAI_ATTACK_SEA any melee ship */
        val fastAttackUnitTypes: List<String> = listOf("Mounted", "Armored"),
        val defenseUnitTypes: List<String> = listOf("Gunpowder"),
    )
    var barbarianConstants = BarbarianConstants()

    /** VP CvUnitCombat::DoDamageMath uses separate melee and ranged defines in hundredths. */
    data class CombatDamageConstants(
        val meleeBase: Int = 2400,
        val meleeRandom: Int = 1200,
        val rangedBase: Int = 2400,
        val rangedRandom: Int = 1200,
        val meleeCityDamagePercent: Int = 100,
        val cityMeleeRetaliationPercent: Int = 100,
        val rangedCivilianDamage: Int = 40,
    )
    var combatDamage = CombatDamageConstants()

    /** VP 5.4.8 CvUnit::baseMoves uses EMBARKED_UNIT_MOVEMENT before movement bonuses. */
    var embarkedUnitMovement = 2

    /** VP 5.4.8 CvUnit::baseMoves / CvUnitMovement::GetMovementCostChecked and CvAStar (UnitPathValid, PathCost): a path search spends the movement of
     *  the state the unit is in at each node, not that of the starting tile. An embarked unit (or a land unit on a water node) has [embarkedUnitMovement]
     *  as its budget (plus embarked-movement bonuses), landing on a tile ends the embarked state with its own full land budget on the next turn, and the
     *  path cache keys include the embarked state. Off: the search assumes one fixed full-move budget and the unit's current embarked state. */
    var embarkAwarePathingLikeVp = false

    /** VP 5.4.8 CvUnit::healRate: territory and occupation determine base healing. The territory auras of nearby units
     *  (GetHealFriendlyTerritoryFromNearbyUnit, ...Neutral..., ...Enemy...) apply only on tiles of their own kind, and a city in resistance
     *  that is not being razed gives its unit the enemy base rate without the heal rate of the city's own buildings. */
    var unitHealingLikeVp = false
    /** VP 5.4.8 CvUnit::healRate with MOD_BALANCE_VP: beliefs heal on own tiles for a civilization with a capital: those of the state religion
     *  (CvPlayerReligions::UpdateStateReligion, [com.unciv.logic.civilization.managers.ReligionManager.getStateReligion]) that CvReligionBeliefs::IsBeliefValid
     *  accepts, the pantheon belief of the second religion of the capital, and the civilization's own pantheon belief that it keeps for good
     *  (MOD_BALANCE_PERMANENT_PANTHEONS). The healing of a city's own religion next to the city is then not added again. Needs [unitHealingLikeVp]. */
    var stateReligionHealingLikeVp = false
    /** VP 5.4.8 CvReligionBeliefs::IsBeliefValid, the Reformation branch (V:CvBeliefClasses.cpp:2229-2253): a Reformation belief works for the civilization that owns the holy city
     *  of the religion or has it as its state religion ([com.unciv.logic.civilization.managers.ReligionManager.getStateReligion]), and in a city only if the majority religion
     *  of that city is that religion. Without it, as before, every Reformation belief works for the founder of the religion only, and in all of its cities.
     *  `bHolyCityOnly` and who owns the holy city are [religiousBeliefValidityLikeVp]'s. */
    var reformationBeliefsLikeVp = false
    /** VP 5.4.8 CvReligionBeliefs::IsBeliefValid as a whole (V:CvBeliefClasses.cpp:2151-2317,
     *  [com.unciv.logic.civilization.managers.ReligionManager.isBeliefValid]) and the religion each reader asks it of:
     *  - who owns a religion: the owner of its holy city, and a holy city that an inquisitor of another religion has stripped is nobody's
     *    (CvCityReligions::RemoveOtherReligions clears the holy plot, V:CvReligionClasses.cpp:5246-5253);
     *  - the founder-side beliefs that civilization-wide lookups read are those of the OWNED religion
     *    (CvPlayerReligions::GetOwnedReligion, V:CvReligionClasses.cpp:3742-3759): the state religion while we own its holy city, conquered or founded.
     *    A founder that lost its holy city has no Founder or Enhancer belief any more. A Reformation belief is that of the state religion,
     *    asked with its holy city, so the holy city must follow it;
     *  - the global yields of the founder benefits ask with the holy city and `bHolyCityOnly` (CvPlayer::GetHappinessFromReligion, V:CvPlayer.cpp:21844-21935);
     *  - Heathen Conversion and the score read the owned religion (CvUnit::setXY V:CvUnit.cpp:20859-20866, CvPlayer::GetScoreFromReligion V:CvPlayer.cpp:11348-11363);
     *  - the influence a gift of gold gets from beliefs needs the city-state's capital to follow the donor's owned religion
     *    (CvMinorCivAI::IsSameReligionAsMajor, V:CvMinorCivAI.cpp:16599-16612);
     *  - the erosion of a missionary is valid for the missionary's owner, and the retention of an inquisition for the owner of the city
     *    (CvCityReligions::AddMissionarySpread V:CvReligionClasses.cpp:4813-4821, ErodeOtherReligiousPressure 4957).
     *  DEPARTURE: an inquisitor of the religion itself gives a stripped holy city back, as before; VP never gives it back.
     *  Off: the founded religion, the holy city of a religion counts while it is blocked, and the founder keeps its Founder and Enhancer beliefs. Independent of
     *  [reformationBeliefsLikeVp], which says who reads the Reformation beliefs at all. */
    var religiousBeliefValidityLikeVp = false
    /** VP 5.4.8 natural religious pressure between cities (CvGameReligions::SpreadReligionToOneCity, V:CvReligionClasses.cpp:358-445):
     *  - a city reaches another if a trade route links them in either direction, or a trade path that the original would cache does
     *    (CvGameReligions::IsCityConnectedToCity, V:CvReligionClasses.cpp:534-632): the cheapest land or sea trade path from either city to the other, within the trade
     *    range of the owner of the path's first city, measured against the trade range of the sending city, raised by the spread distance beliefs, under 100%.
     *    City-states and barbarians cache no paths; their cities reach only cities that reach them;
     *  - without a trade route the pressure falls with the square of the distance, `base * (100 - percent)² / 10000` in whole numbers; with one it is the whole base,
     *    raised by the trade route pressure of the sending civilization, once however many routes there are
     *    (CvGameReligions::GetAdjacentCityReligiousPressure, V:CvReligionClasses.cpp:2801-2950);
     *  - the percentages add up and divide once, and the beliefs count if CvReligionBeliefs::IsBeliefValid accepts them for the owner of the sending city;
     *    the doubling technology of a spread strength belief is the founder's (V:CvReligionClasses.cpp:2889-2904).
     *  "Religion naturally spreads to cities [amount] tiles away" is ten percent of distance a tile: Brave New World's Itinerant Preachers is 30% and +3 tiles.
     *  The followers follow as in the original (CvCityReligions::RecomputeFollowers and ComputeReligiousMajority, V:CvReligionClasses.cpp:5430-5520, 4443-4489):
     *  - they are recomputed after the holy city pressure, after each city's pressure and after each spy's, so a source can flip the majority the next one finds;
     *  - whole numbers: `total pressure / population` a follower, the rest by largest remainder; the majority needs half of all followers, atheists included,
     *    and atheists with the most followers mean none (off: half the population, rounded down, among the religions);
     *  - each pressure of a religion takes half as much from each pantheon there (CvCityReligions::AddReligiousPressure, V:CvReligionClasses.cpp:4896-4930).
     *  Spies (V:CvReligionClasses.cpp:413-443): only those of major civilizations that are not travelling; their pressure is added where the religion is absent too
     *  (AddSpyPressure, 5186-5190), and [com.unciv.models.ruleset.unique.UniqueType.SpyReligionPressureErosion] takes that many times the base from each other major
     *  religion, down to nothing (DoSpyPressureErosion, 5193-5212). Removing the other religions keeps the retained share rounded up (ErodeOtherReligiousPressure,
     *  4958-4965); a prophet adding before it removes ends as VP's AddProphetSpread (4825-4830) does, the pantheons and the removed pressure being gone either way.
     *  DEPARTURE: pressure keeps the engine's units, a tenth of the original's (6 a turn, not 60 or 65, on Standard), so the falloff rounds down in those.
     *  Not built: the base pressure modifiers (India, buildings), the religion distance and pressure policies, the friendship that the city-state spread belief needs,
     *  vassals, the conversion modifiers of the target, the traits of IsValidTarget, the trade pressure of buildings and beliefs, the order of the cities spread to
     *  (the original's player order), the franchise pressure of corporations.
     *  Off: cities within 10 tiles, plus the spread distance uniques, or linked by a trade route spread the whole pressure, and the percentages multiply. */
    var religiousSpreadLikeVp = false
    /** VP 5.4.8 CvUnit::GetGenericMeleeStrengthModifier (V:CvUnit.cpp:16471-16507) and CvUnit::GetMaxRangedCombatStrength (V:CvUnit.cpp:17336-17372): a land unit fighting a unit of
     *  a major civilization gets the four religious combat bonuses of its owner's state religion (Defender of the Faith, Evangelism) by the territory of the battle tile and
     *  the state religion of the other side ([com.unciv.logic.battle.ReligiousCombat]). Off: the uniques of [com.unciv.models.ruleset.unique.UniqueType.ReligiousStrengthOwnLands]
     *  and its three siblings do nothing. */
    var religiousCombatLikeVp = false
    var cityUnitHeal = 25
    var friendlyUnitHeal = 20
    var neutralUnitHeal = 10
    var enemyUnitHeal = 10

    /** Staged city model; its values are only used when enabled. */
    var cityCombatLikeVp = false
    var cityCombat = CityCombatConstants()

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

    // ---- (P6/M6) Vox Populi's unit supply, staged ----
    /** VP 5.4.8 CvPlayer::GetUnitSupplyBreakdown, GetNumUnitsSuppliedByHandicap/Cities/Population, calculateUnitProductionMaintenanceMod, calculateUnitGrowthMaintenanceMod,
     *  CvUnit::isNoSupply. true: the supply is the breakdown of [unitSupplyConstants] (era reduction of the base supply, the technology divisors of the cities' and the population's
     *  supply, the empire size divisor, the puppet share of a city's supply), a unit with the unique `Does not use unit supply` (its own or from a promotion) uses no supply,
     *  the production penalty and the growth penalty per unit over the supply are [unitSupplyConstants]'. false: the supply of [unitSupplyPerPopulation] and the difficulty, -10% production
     *  per unit over the supply (at most -70%), no growth penalty, and the unique is not read. */
    var unitSupplyLikeVp = false

    /** VP 5.4.8 CvCity::processBuilding (CitySupplyFlat, CitySupplyModifier), CvCity::getCitySupplyFlat/getCitySupplyModifier, read by CvPlayer::GetNumUnitsSuppliedByCities and
     *  GetNumUnitsSuppliedByPopulation. true (and [unitSupplyLikeVp]): the uniques `[amount] Unit Supply in this city` and `[amount]% Unit Supply from the citizens of this city`
     *  of a city's buildings add to what that city gives. false: they are not read. Staged with the Vox Populi difficulty table and the building rows (decision D26): the three flip together. */
    var unitSupplyBuildingsLikeVp = false

    /** VP 5.4.8 CvCity::canTrain (V:CvCity.cpp:8696-8703). true (and [unitSupplyLikeVp]): a civilization whose city production the AI decides (an AI, a city-state; not a human player, not a
     *  barbarian) cannot train or buy a combat unit that uses supply once its units to supply are as many as its supply (`GetNumUnitsToSupply() < GetNumUnitsSupplied()` is the
     *  condition to train: "this works when we're at the limit"). false: only the limit of [UnitSupplyConstants.maxProductionPenalty] / [UnitSupplyConstants.productionPenaltyPerUnit] units over the
     *  supply stops the training, and the AI's own army sizing and `HurryChoice` hold it back. Staged with the flip of the AI's supply consumers (decision D26) */
    var unitSupplyAiAtLimitLikeVp = false

    /**
     *  The GlobalDefines of VP's unit supply, read only with [unitSupplyLikeVp] (can only be modded all-or-nothing, a data class so the Json serializer can omit it when default). The
     *  defaults are the numbers of the original / Community Patch (nothing reduces the supply, 10% production per unit over it); `DEF-VP` is `(2) Vox Populi/Database Changes/DefineChanges.sql:160-195`.
     *  The puppet share of the supply of a city and of its citizens is [NeedsConstants.puppetYieldMultiplier] (one define, `PUPPET_YIELD_AND_SUPPLY_MODIFIER_MULTIPLICATIVE`, for yield and supply).
     */
    data class UnitSupplyConstants(
        /** Hundredths of a unit taken off the base supply per era of the civilization (`UNIT_SUPPLY_BASE_TECH_REDUCTION_PER_ERA`, 100 in VP) */
        val baseTechReductionPerEra: Int = 0,
        /** Percent of the technologies known that divide the supply of the cities, and of the citizens: divisor = 100 + progress x this / 100, progress 0-100
         *  (`UNIT_SUPPLY_CITIES_TECH_REDUCTION_MULTIPLIER`, `UNIT_SUPPLY_POPULATION_TECH_REDUCTION_MULTIPLIER`, both 100 in VP) */
        val citiesTechReductionMultiplier: Int = 0,
        val populationTechReductionMultiplier: Int = 0,
        /** Percent the supply loses per effective city (the cities but the puppets): divisor = 100 + cities x this (the column `NumCitiesUnitSupplyMod` of VP's `Worlds`, 5 for every
         *  world size in VP, 0 in Community Patch; we have no per-world table) */
        val empireSizePenaltyPerCity: Int = 0,
        /** Percent production lost per unit over the supply, and the most that can be lost (`PRODUCTION_PENALTY_PER_UNIT_OVER_SUPPLY` 10 in CP, 5 in VP; `MAX_UNIT_SUPPLY_PRODMOD` 70) */
        val productionPenaltyPerUnit: Int = 10,
        val maxProductionPenalty: Int = 70,
        /** Percent growth lost per unit over the supply, and the most that can be lost (`GROWTH_PENALTY_PER_UNIT_OVER_SUPPLY` 0 in CP, 5 in VP; `MAX_UNIT_SUPPLY_GROWTH_MOD` 70) */
        val growthPenaltyPerUnit: Int = 0,
        val maxGrowthPenalty: Int = 70,
    )
    var unitSupplyConstants = UnitSupplyConstants()

    // ---- (StageConquest / M5) conquest and capture like Vox Populi, staged ----
    /** VP 5.4.8 CvPlayer::acquireCity, CvPlayerCulture::GetInfluenceCityConquestReduction, CvUnit::createCaptureUnit. true: a conquered city keeps [ConquestConstants.capturePopulationPercent]
     *  of its citizens (plus the share of the rest that the influence reduction gives back; at least 1, never when the conqueror is the founder, the city was a barbarian one or was in resistance),
     *  the reduction by influence is [ConquestConstants]'s table plus the boost (resistance only between two major civilizations), the city keeps its damage up to [ConquestConstants.cityCaptureDamagePercent]
     *  of its maximum hit points, a building that is not destroyed by its own unique survives with the chance of `Has a [n]% chance of surviving when the city is captured`
     *  (default [ConquestConstants.buildingSurvivalPercent]), a captured military unit gets [ConquestConstants.combatCaptureHealthPercent] as VP's COMBAT_CAPTURE_HEALTH, and keeps its moves.
     *  false: a city loses 1 + population / 4 citizens less the influence share, has half of its maximum hit points, buildings are destroyed with 34% chance, captured units have 50% health and no moves. */
    var conquestLikeVp = false

    /** The numbers of VP's conquest, read only with [conquestLikeVp] (all-or-nothing, a data class so the Json serializer can omit it when default). `DEF-VP` is `(2) Vox Populi/Database Changes/DefineChanges.sql:105-115`. */
    data class ConquestConstants(
        /** Percent of the citizens a conquered city keeps (`CITY_CAPTURE_POPULATION_PERCENT`, 50 in CP, 75 in VP; used up to 100) */
        val capturePopulationPercent: Int = 75,
        /** Percent added to the influence reduction of population loss and resistance, also with no influence (`BALANCE_CONQUEST_REDUCTION_BOOST`, 0 in CP, 5 in VP) */
        val conquestReductionBoost: Int = 5,
        /** CvPlayerCulture::GetInfluenceCityConquestReduction by influence level of the conqueror over the old owner (Exotic 10, Familiar 25, Popular 50, Influential 75, Dominant 100 in VP; Unknown 0) */
        val influenceReductionExotic: Int = 10,
        val influenceReductionFamiliar: Int = 25,
        val influenceReductionPopular: Int = 50,
        val influenceReductionInfluential: Int = 75,
        val influenceReductionDominant: Int = 100,
        /** Percent of its maximum hit points the damage of a conquered city is capped at (`CITY_CAPTURE_DAMAGE_PERCENT`, 50 in CP and VP) */
        val cityCaptureDamagePercent: Int = 50,
        /** Percent of the buildings that survive a conquest when the building has no unique for it (`Buildings.ConquestProb`, 66 in CP, 80 in VP's base setting) */
        val buildingSurvivalPercent: Int = 80,
        /** `COMBAT_CAPTURE_HEALTH`: 50 in CP, 75 in VP */
        val combatCaptureHealthPercent: Int = 75,
        /** VP's CvUnit::createCaptureUnit passes this share of the unit's maximum hit points to CvUnit::setDamage, so the captured unit has what is left (25% with 75): true like the source, false: it is the health */
        val combatCaptureHealthIsDamage: Boolean = true,
        /** CvUnit::createCaptureUnit with MOD_BALANCE_VP: the captured unit has its full moves back, but has made all its attacks of the turn */
        val capturedUnitMovesButCannotAttack: Boolean = true,
    )
    var conquestConstants = ConquestConstants()

    // Gold maintenance of units (CvTreasury::CalculateUnitCost), with 0.5 gold per unit that is not free:
    // cost = (paidUnits * 0.5 * (1 + unitMaintenanceGameMultiplier * progress)) ^ (1 + progress / unitMaintenanceExponentDivisor)
    // where progress is the turn divided by the number of turns of the game speed. The defaults are the original Unciv values; Gods & Kings uses 0, 8 and 7, Brave New World 0, 8 and 5 (Vox Populi's value).
    // Departure: Vox Populi takes progress from the techs known (CvTreasury::CalculateUnitCost reads CvTeam::GetTechProgressPercent, V:CvTreasury.cpp:522-525), we from the turn.
    var unitMaintenanceFreeUnits = 3
    var unitMaintenanceGameMultiplier = 1f
    var unitMaintenanceExponentDivisor = 3f

    // The science each citizen of a city gives (the "Population" entry of the city's stats): Defines SCIENCE_PER_POPULATION, 1 in Brave New World and
    // Community Patch, 0 in Vox Populi (DefineChanges.sql:350, CvCity.cpp: CvCity::setPopulation). The Brave New World ruleset keeps 1 until the science sources
    // that replace it in Vox Populi are flipped
    var sciencePerPopulation = 1f

    /** The food a city of population p needs to grow, before the speed, the starting era and the handicaps (CvPlayer::getGrowthThreshold, V:CvPlayer.cpp:46530-46537):
     *  `base + (p - 1) * multiplier` (cut to an integer) `+ floor((p - 1) ^ exponent)`. Defines BASE_CITY_GROWTH_THRESHOLD, CITY_GROWTH_MULTIPLIER and CITY_GROWTH_EXPONENT:
     *  15, 8 and 1.5 in Brave New World, 15, 12 and 2.22 in Vox Populi (DefineChanges.sql). Can only be modded all-or-nothing */
    data class CityGrowthConstants(
        val base: Int = 15,
        val multiplier: Float = 8f,
        val exponent: Float = 1.5f,
    )
    var cityGrowth = CityGrowthConstants()

    /** The culture a city needs for its next tile under [cultureCostLikeCivV] (CvCity::GetJONSCultureThreshold, V:CvCity.cpp:17311-17325):
     *  `first + int((level * laterPlotMultiplier) ^ exponent)`, level being the number of tiles the city has claimed by culture. Defines CULTURE_COST_FIRST_PLOT,
     *  CULTURE_COST_LATER_PLOT_MULTIPLIER and CULTURE_COST_LATER_PLOT_EXPONENT: 15, 10 and 1.1 in Brave New World, 20, 12 and 1.35 in Vox Populi (DefineChanges.sql:144-146;
     *  the "15 in VP" comment of the source is stale). Can only be modded all-or-nothing */
    data class BorderCultureConstants(
        val first: Int = 15,
        val laterPlotMultiplier: Int = 10,
        val exponent: Double = 1.1,
    )
    var borderCulture = BorderCultureConstants()

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

    // --- Air basing, land air defense and interception (StageAir) ---
    /** VP 5.4.8 CvPlot::countNumAirUnits, CvUnit::canRebaseAt, CvUnit::getRebaseRange, CvCity::GetMaxAirUnits, CvCity::CanTrain: the air units standing in a city use
     *  up its slots by the amount of the unique `Counts [amount] against the air unit capacity` (an air unit without it counts 1, as every air unit of VP's data has AirUnitCap 1;
     *  units carried by a carrier or an overflow marked as transported count nothing), a unit may enter (be built in, rebase to) a city while the slots in use of
     *  its owner's air units there (not counting the unit itself when it is already there) are fewer than the capacity, which starts from [AirBasingConstants.cityAirUnitCapacity]
     *  instead of [cityAirUnitCapacity], and an air unit can rebase `range * rebaseRangeMultiplier / 100` tiles away instead of twice its range. Aircraft that exceed the capacity
     *  after the switch is turned on are kept; only new ones are refused. A carrier's capacity is unchanged: each aircraft takes one cargo slot (CvUnit::cargoSpaceAvailable).
     *  false: every untransported air unit in the tile counts 1 against [cityAirUnitCapacity] and the range is twice the range of the unit. */
    var airBasingLikeVp = false
    /** The GlobalDefines of VP's air basing, read only with [airBasingLikeVp]; the defaults are the numbers of the original (`BASE_CITY_AIR_STACKING`, `AIR_UNIT_REBASE_RANGE_MULTIPLIER`),
     *  VP's are 2 and 500 (`DefineChanges.sql`). */
    data class AirBasingConstants(
        val cityAirUnitCapacity: Int = 6,
        /** Percent of the unit's range it can rebase over */
        val rebaseRangeMultiplier: Int = 200,
    )
    var airBasing = AirBasingConstants()

    /** VP 5.4.8 CvUnit::GetAirStrikeDefenseDamage and CvCity::GetAirStrikeDefenseDamage with MOD_BALANCE_AIR_UNIT_CHANGES (on in VP): the damage a unit or a city does to an air unit
     *  that attacks it (CvUnitCombat::GenerateAirCombatInfo) is the flat value of the unique `[amount] air defense` (the unit's own and its promotions', the city's buildings'; reduced by the
     *  attacker's interception damage reduction, plus a random 0-4 for a unit and 0-9 for a city, a unit's also changed by the attacker's `[amount]% damage taken`), not a comparison of strengths.
     *  false: the damage follows from the strengths and the unique is not read. */
    var airDefenseLikeVp = false

    /** VP 5.4.8 CvPlot::GetBestInterceptor, CvPlot::GetInterceptorCount, CvUnit::interceptionProbability, CvUnit::evasionProbability, CvUnit::GetInterceptionDamage,
     *  CvUnitCombat::GenerateAirCombatInfo / ResolveAirUnitVsCombat: every civilization at war with the attacker (barbarians never) may intercept, over a third party's land only at war with
     *  its owner or on friendly land; the interceptor is the one with the highest (strength after `Damage when intercepting`) x (chance x health) - for an air unit x health again -,
     *  the nearest of equals, and the defender may be it; the attacker first rolls its `[amount]% chance to evade interception`, then the interceptor its chance x health / max health (any unit);
     *  the damage is VP's damage math with the interceptor's strength raised by `Damage when intercepting` and the attacker's `Damage taken from interception reduced by` (summed) as the
     *  modifier; an interception that does damage ends the attack (the target is not hit), and a suicide attacker is lost. false: the defender's civilization intercepts, with the highest
     *  chance, the interceptor is rolled by its chance, the damage is multiplied by `Damage when intercepting` and by the product of the reductions, and the attack goes on. */
    var airInterceptionLikeVp = false

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
    // Where an instant yield's "Gain [amount] [stat] <(modified by game speed)>" with `<for every ...>` is cut to a whole number. false: each copy that the
    // multipliers make is changed by the speed and cut by itself. true: CvPlayer::doInstantYield's order, the amount times every multiplier but the `Era scaler`
    // changed by the speed and cut once, then times the `Era scaler` (V:CvPlayer.cpp:27162-27184): a belief's 2 Faith for each of 250 followers is 375 at Quick, not 250
    var instantYieldsCutTotalLikeVp = false
    // Whether researching a tech that can be researched again (Future Tech) fires `upon discovering [techFilter] technology` for the instant yields of
    // the civilization's uniques. false: every time. true: never, as CvTeam::setHasTech calls processTech, which fires INSTANT_YIELD_TYPE_TECH, only for a tech
    // that does not repeat (V:CvTeam.cpp:6516-6518). The tech's own triggered uniques fire either way
    var repeatTechsGiveNoInstantYieldsLikeVp = false
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

    // Whether buying a unit sets a cooldown (CvCity::PurchaseUnit sets it, V:CvCity.cpp:31100, and CvCity::doTurn counts it down, V:2485-2503): the unit's `purchaseCooldown` (less the city's
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

    /**
     *  How the influence of a major with a city-state changes each turn as in Vox Populi (CvMinorCivAI::GetFriendshipChangePerTurnTimes100, V:CvMinorCivAI.cpp:10393-10562, with
     *  MOD_BALANCE_VP), instead of Brave New World's: the resting point (`GetFriendshipAnchorWithMajor`, 10664-10752: 5 for a protector, not the 10 of the old code), a decay that
     *  grows with the points over the resting point, three times as fast towards a protector whose capital is damaged, the trade route bonus of policies times the number of the
     *  routes to city-states (at most [CityStateInfluenceConstants.tradeRouteBonusRoutes]), the change divided by the game speed's production cost percentage, and an ally that
     *  stays until another civilization has more influence (`DoFriendshipChangeEffects`, 11348-11489). Off: the old rules.
     */
    var cityStateInfluenceLikeVp = false

    /**
     *  The defines of [cityStateInfluenceLikeVp], in the hundredths of influence the source uses where it does. Can only be modded all-or-nothing; the defaults are Vox Populi 5.4.8's.
     *  [dropTimes100] `MINOR_FRIENDSHIP_DROP_PER_TURN` (-100 there), [dropHostileTimes100] `..._HOSTILE` (150), [dropAggressorTimes100] `..._AGGRESSOR` (200): the decay of a turn above the
     *  resting point, for a hostile city-state and against a civilization that attacked city-states; [recoveryTimes100] `MINOR_FRIENDSHIP_NEGATIVE_INCREASE_PER_TURN`;
     *  [sharedReligionRatePercent] `MINOR_FRIENDSHIP_RATE_MOD_SHARED_RELIGION` (recovery faster by this, decay slower by half of it); [scalingDecayExponent] `MINOR_INFLUENCE_SCALING_DECAY_EXPONENT`
     *  with [scalingDecayStart] and [scalingDecayFull], the points over the resting point from which the decay grows and where the exponent takes over (50 and 100 in the source's code);
     *  [damagedCapitalDecayPercent] `MINOR_FRIENDSHIP_DROP_PER_TURN_DAMAGED_CAPITAL_MULTIPLIER` (CP 100, VP 300); [anchorProtected] `MINOR_FRIENDSHIP_ANCHOR_MOD_PROTECTED` and [anchorWary]
     *  `MINOR_FRIENDSHIP_ANCHOR_MOD_WARY_OF`: the resting point of a protector and against a civilization the city-state is wary of; [unitGift] `FRIENDSHIP_PER_UNIT_GIFTED`, doubled
     *  ([unitGiftProxyWarMultiplier]) in a proxy war; [barbarianKilled] `FRIENDSHIP_PER_BARB_KILLED`; [civilianReturned] `RETURN_CIVILIAN_FRIENDSHIP`; [unitIntruding]
     *  `FRIENDSHIP_PER_UNIT_INTRUDING` (5 taken for each unit); [liberationPerEra] `MINOR_LIBERATION_FRIENDSHIP`, [liberationMinimum] the ally threshold and 10 more,
     *  [liberationRestingPoint] `MINOR_LIBERATION_RESTING_INFLUENCE`; [jerkTurns] `BALANCE_CS_WAR_COOLDOWN_RATE` (times the speed's production cost percentage, in which the resting
     *  point is lowered after an attack); [recentlyLiberatedTurns] the turns after its liberation in which a city-state's tribute score is lowered ([CityStateBullyConstants.liberatedPenalty]).
     */
    data class CityStateInfluenceConstants(
        val dropTimes100: Int = 100,
        val dropHostileTimes100: Int = 150,
        val dropAggressorTimes100: Int = 200,
        val recoveryTimes100: Int = 100,
        val sharedReligionRatePercent: Int = 50,
        val scalingDecayExponent: Double = 1.5,
        val scalingDecayStart: Int = 50,
        val scalingDecayFull: Int = 100,
        val damagedCapitalDecayPercent: Int = 300,
        val anchorProtected: Int = 5,
        val anchorWary: Int = -20,
        val tradeRouteBonusRoutes: Int = 5,
        val unitGift: Int = 15,
        val unitGiftProxyWarMultiplier: Int = 2,
        val barbarianKilled: Int = 15,
        val civilianReturned: Int = 50,
        val unitIntruding: Int = 5,
        val liberationPerEra: Int = 105,
        val liberationMinimum: Int = 70,
        val liberationRestingPoint: Int = 100,
        val jerkTurns: Int = 50,
        val recentlyLiberatedTurns: Int = 50,
    )
    var cityStateInfluence = CityStateInfluenceConstants()

    /**
     *  The pledge to protect a city-state as in Vox Populi (CvMinorCivAI::CanMajorProtect, V:CvMinorCivAI.cpp:12367-12490, CanMajorWithdrawProtection, 12498-12520,
     *  DoChangeProtectionFromMajor, 12291-12365, TestChangeProtectionFromMajor, 11979-12145; MOD_BALANCE_MINOR_PROTECTION_REQUIREMENTS) instead of Brave New World's: only a major whose
     *  military might over its distance is at least the median of all the living majors', and that is the ally, has a trade route with the city-state or could make one, can
     *  pledge; a pledge cannot be withdrawn for a while; a pledge that no longer qualifies ends after a warning, or at once below 0 influence, and costs influence for the era.
     *  Off: the old rules. The numbers are in [CityStateProtectionConstants].
     */
    var cityStateProtectionLikeVp = false

    /**
     *  The defines of [cityStateProtectionLikeVp]. Can only be modded all-or-nothing; the defaults are Vox Populi 5.4.8's. [brokenTurns] `PLEDGE_BROKEN_MINIMUM_TURNS` and [bullyingTurns]
     *  `PLEDGE_BROKEN_MINIMUM_TURNS_BULLYING`: the turns after a broken pledge, and after a tribute it demanded, in which it cannot pledge again (times the game speed's production
     *  cost percentage); [minimumDuration] `BALANCE_MINOR_PROTECTION_MINIMUM_DURATION`; [warningTurns] the turns (times the same percentage) a pledge that no longer qualifies has to
     *  qualify again, with a reminder every [reminderTurns]; [dishonourPerEra] `MINOR_FRIENDSHIP_DROP_DISHONOR_PLEDGE_TO_PROTECT` (-2000 in hundredths) the influence a broken pledge
     *  costs for each era; [minimumInfluence] `FRIENDSHIP_THRESHOLD_CAN_PLEDGE_TO_PROTECT`; [militaryRequirement] `BALANCE_MINOR_PROTECTION_REQUIREMENTS`.
     */
    data class CityStateProtectionConstants(
        val brokenTurns: Int = 20,
        val bullyingTurns: Int = 30,
        val minimumDuration: Int = 50,
        val warningTurns: Int = 12,
        val reminderTurns: Int = 4,
        val dishonourPerEra: Int = 20,
        val minimumInfluence: Int = 0,
        val militaryRequirement: Boolean = true,
    )
    var cityStateProtection = CityStateProtectionConstants()

    /**
     *  Whether a major can give gold to a city-state (the original's `CSD_GOLD_GIFT_DISABLED` is 1 in Vox Populi, whose human gift menu has units and tile improvements
     *  instead, and `MINOR_GOLD_GIFT_SMALL`, `_MEDIUM` and `_LARGE` are 0, V:CvDiplomacyAI.cpp:36765). false: no gold gift, for the player and the AI
     */
    var cityStateGoldGifts = true

    /**
     *  The rewards of city-state quests as in Vox Populi (CvMinorCivQuest::CalculateRewards, V:CvMinorCivAI.cpp:329-895, DoRewards, 896-1067, CvMinorCivAI::GetQuestRewardModifier,
     *  4949-4975) instead of Brave New World's influence alone: the [Quest] holds the rewards of many kinds, which grow with the era of the civilization, get a random share, the
     *  percentage of the speed, the multipliers of the type and personality of the city-state, and the modifier of the civilization (protection, personality, policies); they are
     *  frozen into the assigned quest and given out when it is complete. A heavy tribute (the owner's rule with [cityStateBullyLikeVp]) gives gold and the rewards of the personal
     *  quests but their influence. Off: the old rules. The numbers are in [CityStateQuestConstants].
     */
    var cityStateQuestsLikeVp = false

    /**
     *  The defines of [cityStateQuestsLikeVp]. Can only be modded all-or-nothing; the defaults are Vox Populi 5.4.8's. [eraScalerPercent] the growth of a reward for each era after the
     *  first (`iEraScaler`, V:CvMinorCivAI.cpp:347); [protectionBoost] `BALANCE_INFLUENCE_BOOST_PROTECTION_MINOR`, [friendlyModifier] `MINOR_CIV_QUEST_REWARD_FRIENDLY` and
     *  [hostileModifier] `MINOR_CIV_QUEST_REWARD_HOSTILE` the percent added to the modifier of the civilization; [firstPossibleTurn] `MINOR_CIV_PERSONAL_QUEST_FIRST_POSSIBLE_TURN` and
     *  `_GLOBAL_`, [firstTurnRandom] `..._FIRST_POSSIBLE_TURN_RAND`, [personalMinTurnsBetween], [personalRandTurnsBetween], [globalMinTurnsBetween], [globalRandTurnsBetween] the
     *  `..._MIN_TURNS_BETWEEN` and `..._RAND_TURNS_BETWEEN` (a hostile city-state multiplies the random span by [hostileRandMultiplier] percent), see [com.unciv.logic.civilization.managers.quests.CityStateQuestSchedule]; [typeMultipliers] and [personalityMultipliers] the percent of a reward
     *  of a [com.unciv.logic.civilization.managers.quests.QuestRewardKind] name by city-state type and by personality name (the `MINOR_CIV_QUEST_REWARD_*` defines; no entry is 100).
     */
    data class CityStateQuestConstants(
        val eraScalerPercent: Int = 50,
        val protectionBoost: Int = 15,
        val friendlyModifier: Int = 25,
        val hostileModifier: Int = -25,
        val firstPossibleTurn: Int = 4,
        val firstTurnRandom: Int = 0,
        val personalMinTurnsBetween: Int = 10,
        val personalRandTurnsBetween: Int = 20,
        val globalMinTurnsBetween: Int = 25,
        val globalRandTurnsBetween: Int = 20,
        val hostileRandMultiplier: Int = 200,
        val typeMultipliers: Map<String, Map<String, Int>> = mapOf(
            "Gold" to mapOf("Mercantile" to 150, "Maritime" to 125),
            "Science" to mapOf("Religious" to 60),
            "Culture" to mapOf("Cultured" to 150),
            "Faith" to mapOf("Religious" to 150),
            "GoldenAgePoints" to mapOf("Cultured" to 150, "Religious" to 125),
            "Food" to mapOf("Maritime" to 150, "Mercantile" to 50),
            "Production" to mapOf("Militaristic" to 125),
            "Tourism" to mapOf("Militaristic" to 50, "Cultured" to 125),
            "GreatPersonPoints" to mapOf("Cultured" to 75),
            "GreatPersonPointsEverywhere" to mapOf("Religious" to 125, "Cultured" to 75),
            "GeneralPoints" to mapOf("Militaristic" to 150, "Cultured" to 75),
            "AdmiralPoints" to mapOf("Maritime" to 150, "Cultured" to 75),
            "Experience" to mapOf("Militaristic" to 125, "Religious" to 50),
        ),
        val personalityMultipliers: Map<String, Map<String, Int>> = mapOf(
            "Culture" to mapOf("Neutral" to 80, "Irrational" to 125),
            "Faith" to mapOf("Neutral" to 60),
            "GoldenAgePoints" to mapOf("Irrational" to 60),
            "Food" to mapOf("Neutral" to 80),
            "Tourism" to mapOf("Neutral" to 125, "Irrational" to 75),
            "GreatPersonPoints" to mapOf("Neutral" to 150, "Irrational" to 75),
            "GreatPersonPointsEverywhere" to mapOf("Neutral" to 125),
        ),
    )
    var cityStateQuests = CityStateQuestConstants()

    /**
     *  Demanding tribute from a city-state as in Vox Populi (CvMinorCivAI::CalculateBullyScore, V:CvMinorCivAI.cpp:14015-14400, GetBullyGoldAmount, 13969-14012, CanMajorBullyGold,
     *  14403-14470, DoMajorBullyGold, 14603-14750, DoBulliedByMajorReaction, 15025-15080; MOD_BALANCE_VP with MOD_BALANCE_HEAVY_TRIBUTE) instead of Brave New World's: the score is the
     *  share of the military might of all the civilizations and the local power, with no base reluctance and no penalty for low influence, gold needs a score of 20 and heavy tribute
     *  of 100, a demand costs 30 influence and everything the civilization has over its resting point change, and the yields of policies and traits for a demand are paid.
     *  Off: the old rules. The numbers are in [CityStateBullyConstants].
     */
    var cityStateBullyLikeVp = false

    /**
     *  The defines of [cityStateBullyLikeVp], with the amounts of the penalties as positive numbers. Can only be modded all-or-nothing; the defaults are Vox Populi 5.4.8's.
     *  [blockedTurns] the turns after a tribute in which there is no new one (10 x the game speed's production cost percentage), [cooldownTurns] the turns in which a tribute counts
     *  against the score, [recentlyBulliedPerTurn] for each turn left of them; [globalMilitaryShare] the percent of the might share that counts; [goldBase] `MINOR_BULLY_GOLD` and
     *  [goldGrowth] `MINOR_BULLY_GOLD_GROWTH_FACTOR`, the gold of a tribute at the start and what it grows by over the game; [goldThreshold] `MINOR_CIV_GOLD_TRIBUTE_THRESHOLD`
     *  and [heavyThreshold] `MINOR_CIV_HEAVY_TRIBUTE_THRESHOLD` the scores a demand needs; [dropGoldTimes100] `MINOR_FRIENDSHIP_DROP_BULLY_GOLD_SUCCESS` and [dropHeavyTimes100]
     *  `MINOR_FRIENDSHIP_DROP_BULLY_WORKER_SUCCESS` the influence a demand costs, in hundredths; [allyPenalty], [protectorPenalty] for each, [hostilePenalty] and [militaristicPenalty]
     *  what the city-state's situation takes off; [scoreCap] the highest score.
     */
    data class CityStateBullyConstants(
        val blockedTurns: Int = 10,
        val cooldownTurns: Int = 30,
        val recentlyBulliedPerTurn: Int = 10,
        val globalMilitaryShare: Int = 50,
        val goldBase: Int = 50,
        val goldGrowth: Int = 1000,
        val goldThreshold: Int = 20,
        val heavyThreshold: Int = 100,
        val dropGoldTimes100: Int = 3000,
        val dropHeavyTimes100: Int = 6000,
        val allyPenalty: Int = 15,
        val protectorPenalty: Int = 15,
        val hostilePenalty: Int = 10,
        val militaristicPenalty: Int = 10,
        val liberatedPenalty: Int = 100,
        val scoreCap: Int = 100,
    )
    var cityStateBully = CityStateBullyConstants()

    // Espionage
    /**
     *  Vox Populi's spy points (CvPlayer::CreateSpies with MOD_BALANCE_SPY_POINTS, V:CvPlayer.cpp:23292-23338) instead of whole spies. false: `Gain an extra spy` makes one spy and
     *  `Gain [n] Spy Points` does nothing. true: the points are counted ([com.unciv.logic.civilization.managers.EspionageManager.spyPoints]), a spy is made each time they reach the
     *  threshold of [EspionageConstants], `Gain an extra spy` is one threshold of points (CreateSpies(1, false)), and a negative amount is a spy debt.
     */
    var spyPointsLikeVp = false

    /**
     *  Vox Populi's espionage model (BALANCE_VP in CvEspionageClasses.cpp), instead of Brave New World's: a spy in a foreign city gathers Network Points each turn (`GatheringIntel`), reduced by the city's
     *  security, and the spy owner gets the passive bonuses (`SpyBonuses.json`) at the highest amount ever stored; the diplomat gets them in the capital at once; a spy needs a number of turns to
     *  travel that grows with the distance of the civilizations; the old surveillance, establishing a network and the theft of technology by progress are never entered. The rates are
     *  [EspionageConstants]. Staged off: missions, the counterspy focus and the rigging of elections follow in their own slices. Needs [spyPointsLikeVp] to give spies in the same terms
     */
    var espionageLikeVp = false

    /**
     *  Vox Populi's BALANCE_NO_COUPS (V:CustomMods.cpp:381, CvPlayerEspionage::CanStageCoup, V:CvEspionageClasses.cpp:3595-3598): a spy cannot stage a coup in a city-state, so the button of the
     *  espionage screen, the AI's attempts and [com.unciv.models.Spy.canDoCoup] are gone. Independent of [espionageLikeVp], as the original's options are. The coup quest
     *  (V:CvMinorCivAI.cpp:6710) belongs to the city-state quests
     */
    var noCoups = false

    /**
     *  Vox Populi's diplomatic missions (CvUnit::canTrade, getTradeInfluence and trade, V:CvUnit.cpp:11990-12219) instead of the Great Merchant's trade mission: a unit with
     *  `Can undertake a diplomatic mission with City-State` does the action 'Conduct Diplomatic Mission' in a City-State's territory and is used up; the influence is
     *  [com.unciv.logic.civilization.diplomacy.DiplomaticMission.influence]; spy points come from the unit's own `Gain [n] Spy Points` (`Units.ExtraSpies`). With it, the Great Merchant's
     *  trade mission action is gone. The Great Diplomat lowers the influence of the other civilizations that know the city-state in two passes (V:CvUnit.cpp:12101-12179) and a unit can raise
     *  the resting point of our influence (V:12094-12098). Not built: the memory of who lowered whose influence (only the diplomacy AI of Vox Populi reads it) and the quest that raises a mission,
     *  which no quest slice has made; [DiplomaticMissionConstants] reads one assigned by name. Staged off; the units, promotions and buildings come in the content slice
     */
    var diplomaticMissionsLikeVp = false

    /** Read only with [diplomaticMissionsLikeVp]. [questBoostPercent] is `INFLUENCE_MINOR_QUEST_BOOST` (20 in Vox Populi, V:CvUnit.cpp:12056-12060), applied when the City-State has the quest
     *  named [questName] assigned to the unit's owner. [noMissionInfluence] is `MINOR_FRIENDSHIP_FROM_TRADE_MISSION` (0 in Vox Populi, 30 in Community Patch): the base of a unit
     *  that has a gold blast and no mission influence of its own */
    data class DiplomaticMissionConstants(
        val questBoostPercent: Int = 20,
        val questName: String = "Influence",
        val noMissionInfluence: Int = 0,
    )
    var diplomaticMission = DiplomaticMissionConstants()

    /**
     *  The defines of spy points and of the time a dead spy needs, `spyToPlayerRatio`, `majorPlayerMultiplier`, `thresholdMin`, `thresholdMax` and `pointUnit` read only with [spyPointsLikeVp].
     *  The threshold is `100 x spyToPlayerRatio / (city-states + majorPlayerMultiplier x majors)` of the players at the start, at least 1 of each, kept between the minimum and the
     *  maximum (CvGame::initSpyThreshold and CvGame::GetSpyThreshold, V:CvGame.cpp:10722-10764: BALANCE_SPY_TO_PLAYER_RATIO 20, BALANCE_SPY_POINT_MAJOR_PLAYER_MULTIPLIER 2,
     *  BALANCE_SPY_POINT_THRESHOLD_MIN and _MAX, which Vox Populi sets to 100 both, so a spy costs 100 points on every map). [pointUnit] is the points of 1 in the original's tables
     *  (ESPIONAGE_SPY_POINT_UNIT, 1 in Vox Populi), applied by the generator when it writes `Gain [n] Spy Points`. [respawnTurns] is how long a killed spy stays dead
     *  (BALANCE_SPY_RESPAWN_TIMER, Vox Populi 10; the default is Brave New World's 5), read with or without [spyPointsLikeVp]. Can only be modded all-or-nothing.
     */
    data class EspionageConstants(
        val spyToPlayerRatio: Int = 20,
        val majorPlayerMultiplier: Int = 2,
        val thresholdMin: Int = 100,
        val thresholdMax: Int = 100,
        val pointUnit: Int = 1,
        val respawnTurns: Int = 5,
        /** The Network Points a spy in a foreign city gathers each turn (CvPlayerEspionage::CalcNetworkPointsPerTurn, V:CvEspionageClasses.cpp:3262-3379), read only with [espionageLikeVp]:
         *  `ESPIONAGE_NP_BASE` 50, `..._PER_SPY_RANK` 0 (per rank, ours counts from 1 where the original counts from 0, so this is multiplied by rank - 1), `..._CULTURAL_INFLUENCE` 15 per
         *  influence level above Exotic, `..._MAX_NUM_TECH` 10 and `..._PER_TECHNOLOGY_BEHIND` 0, `..._REDUCTION_PER_SECURITY_POINT` 100 (percent of the points lost per point of security, over 100) */
        val networkPointsBase: Int = 50,
        val networkPointsPerSpyRank: Int = 0,
        val networkPointsPerInfluenceLevel: Int = 15,
        val networkPointsMaxTechDifference: Int = 10,
        val networkPointsPerTechBehind: Int = 0,
        val networkPointsReductionPerSecurity: Int = 100,
        /** The security of a city (CvCity::CalculateCitySecurity, V:CvCity.cpp:25992-26085): `ESPIONAGE_SECURITY_BASE` 20, `..._PER_POPULATION` -2, `..._PER_TRADE_ROUTE` -5,
         *  `..._PER_EXCESS_UNHAPPINESS` -4 (per point of the city's happiness delta below 0), kept between 0 and `ESPIONAGE_MAX_NUM_SECURITY_POINTS` 100. Read only with [espionageLikeVp] */
        val securityBase: Int = 20,
        val securityMax: Int = 100,
        val securityPerCitizen: Int = -2,
        val securityPerTradeRoute: Int = -5,
        val securityPerExcessUnhappiness: Int = -4,
        /** The turns a spy needs to reach a city (CvPlayerEspionage::CalcRequired(SPY_STATE_TRAVELLING), V:CvEspionageClasses.cpp:3137-3182): [travelBaseTurns] (`iSpyTurnsToTravel`) plus the entry of
         *  [travelTurnsByProximity] (the neighbours, close, far and distant civilizations of the city's owner; not for an own city), halved (at least 1) when the city's owner gives the spy's owner open borders */
        val travelBaseTurns: Int = 1,
        val travelTurnsByProximity: List<Int> = listOf(1, 2, 3, 4),
        /** The turns a spy that did a mission must wait for its next one, at standard speed times the speed's modifier (`ESPIONAGE_TURNS_BETWEEN_CITY_STATE_ELECTIONS`,
         *  CvGame::GetTurnsBetweenMinorCivElections, V:CvGame.cpp:11667-11673, and CvPlayerEspionage::GetNumTurnsSpyActiveMissionsBlocked, V:CvEspionageClasses.cpp:2844-2856) */
        val missionCooldownTurns: Int = 15,
        /** The turns a spy cannot move after it chose a counterspy focus (`ESPIONAGE_COUNTERSPY_CHANGE_FOCUS_COOLDOWN`, CvPlayerEspionage::GetNumTurnsSpyMovementBlocked, V:CvEspionageClasses.cpp:2829-2842) */
        val counterspyChangeCooldownTurns: Int = 5,
        /** The influence the winner of a rigged election gets (CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15221: `ESPIONAGE_INFLUENCE_GAINED_FOR_RIGGED_ELECTION`, 30 in Vox Populi), times the game's era and
         *  the streak; the percent it grows by for each election the winner rigged in a row (`ESPIONAGE_CONSECUTIVE_RIGGING_INFLUENCE_MODIFIER`, 40, :15233); and what each other major loses, times the
         *  era (`ESPIONAGE_INFLUENCE_LOST_FOR_RIGGED_ELECTION`, 5, :15318). Read only with [espionageLikeVp] */
        val riggingInfluenceGained: Int = 30,
        val riggingConsecutiveModifierPercent: Int = 40,
        val riggingInfluenceLost: Int = 5,
        /** The votes a rigging spy adds each turn is its rank times this plus its civilization's offensive strength percent (`CalcPerTurn(SPY_STATE_RIG_ELECTION)`, V:CvEspionageClasses.cpp:3099-3102); the
         *  original compares the votes of the players and never divides by 100, so only the proportions matter */
        val riggingVotesPerRank: Int = 100,
    )
    var espionage = EspionageConstants()

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
    // [tradeRouteVp] and the Eras' `tradeRouteFoodBonus` and `tradeRouteProductionBonus`, with the route rules of the original. Slice T3 of phaseE/vp-trade-plan.md reads the value
    // model ([com.unciv.logic.civilization.managers.TradeRouteValue]: the yields of a route, how long it lasts by the game speed's `tradeRouteSpeedModifier`, what a plunder pays, the
    // range by [worldTradeRouteDistance]); the rules (destination restriction, slots) are slice T4's, which turns them on with this flag as well
    var tradeRouteModelLikeVp = false

    // Vox Populi's rule for a civilization that uses more of a strategic resource than it has (BALANCE_RESOURCE_SHORTAGE_UNIT_HEALING and
    // BALANCE_RESOURCE_SHORTAGE_BUILDING_REFUND, `STRATEGIC_RESOURCE_EXHAUSTED_PENALTY` 0), slice T8 of phaseE/vp-trade-plan.md. false: the combat malus of the original (BNW).
    // true: units short of a resource they need do not heal and keep their strength (CvUnit::GetStrategicResourceCombatPenalty, CvUnit::IsCannotHeal), and at each turn's start the
    // buildings that use a short resource are refunded the shortage, each refunded copy adding 2 percent to the maintenance of all buildings (CvCity::DoTurn, CvTreasury::GetBuildingGoldMaintenance).
    // Only major civilizations are affected
    var resourceShortageLikeVp = false
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
     *  `tiles` that the map has at least (the original's `Worlds.TradeRouteDistanceMod`, CvPlayerTrade::GetTradeRouteRange, V:CvTradeClasses.cpp:5288-5359, the factor at 5357: 80 Duel and Tiny, 90 Small, 100 Standard, 130 Large,
     *  160 Huge). Empty: 100 percent. Read by [com.unciv.logic.civilization.managers.TradeRouteManager.getRange] with [tradeRouteModelLikeVp], where the other percentages of the
     *  range add to it (O8 of phaseE/vp-trade-plan.md: by the number of tiles until the world sizes have rule tables) */
    data class WorldTradeRouteDistance(val tiles: Int = 0, val percent: Int = 100)
    var worldTradeRouteDistance: List<WorldTradeRouteDistance> = emptyList()

    // Rules of the deals (CvDealClasses.cpp). dealsBlockTemporaryForPermanent: a deal in which the AI gives something temporary (gold per turn, resources, open borders,
    // pacts, agreements) and gets something permanent (gold, maps, cities, techs) is refused (BlockTemporaryForPermanentTrade, V:CvDealClasses.cpp:1368-1470), except with
    // dealHumanPermanentForAiTemporary for a human who gives the permanent item (MOD_DEALAI_HUMAN_PERMANENT_FOR_AI_TEMPORARY, 1 in Vox Populi; only read with the rule).
    // dealNoPeacetimeSellingFoundedCities: the founder does not sell a city it founded unless at war (MOD_DEALAI_NO_PEACETIME_SELLING_FOUNDED_CITIES, V:CvDealClasses.cpp:649).
    // Read by slice T4 of phaseE/vp-trade-plan.md
    var dealsBlockTemporaryForPermanent = false
    var dealHumanPermanentForAiTemporary = true
    var dealNoPeacetimeSellingFoundedCities = false
    /** The AI values a luxury in a deal as Vox Populi's `MOD_BALANCE_VP` branch of CvDealAI::GetLuxuryResourceValue does (V:CvDealAI.cpp:1290-1590), in the units of one gold
     *  per turn, instead of the Community Patch's: see [com.unciv.logic.automation.civilization.VpLuxuryValue] */
    var dealLuxuryValueLikeVp = false

    /** The shares of the copies of a resource on the map that make a monopoly (GLOBAL_RESOURCE_MONOPOLY_THRESHOLD 50 and STRATEGIC_RESOURCE_MONOPOLY_THRESHOLD 25,
     *  V:CvPlayer.cpp:39139-39232), in percent. Only read for resources that are `isMonopoly`. Can only be modded all-or-nothing */
    data class MonopolyConstants(
        val globalThreshold: Int = 50,
        val strategicThreshold: Int = 25,
    )
    var monopoly = MonopolyConstants()

    // World Congress (CvVotingClasses.cpp, GlobalDefines.xml; the values are those of Brave New World)
    /** The delegates of Vox Populi (CvLeague::CalculateStartingVotesForMember, V:CvVotingClasses.cpp:4046-4383): [League.getDelegateSources] reads every source the ruleset
     *  has uniques for. Faith, friendships, original capitals and defensive pacts are cached and refreshed at the start of the civilization's own turn, so a friendship counts
     *  from the next turn; the rest (wonders, policies, gold per turn, diplomats, improvements) is read live, as in Vox Populi. The World Religion and World Ideology follow its rules.
     *  Off, [League.getDelegates] adds what Brave New World does */
    var leagueDelegatesLikeVp = false
    /** The flow of a session as Vox Populi has it (CvLeague, V:CvVotingClasses.cpp): the host proposes first and the others only after the host's proposals are used
     *  (`m_bHostProposing`, :2619, 2658, 4391, 8368); what was proposed last session, resolution and choice, cannot be proposed again except a project (:2820-2850); a regular
     *  session start fires `upon a World Congress session` for each member (:7292-7303) and a proposal that passes fires `upon a proposal of ours passing` for its player proposer (:7624, 8028).
     *  Set [leagueProposersPerSession] to 3 with it (the value of the Vox Populi ruleset, `Defines.LEAGUE_PROPOSERS_PER_SESSION`; Community Patch's is 2). Off, a session is Brave New World's */
    var leagueSessionRulesLikeVp = false
    /**
     * Vote commitments in deals as Vox Populi has them (`MOD_BALANCE_PERMANENT_VOTE_COMMITMENTS`, 1 in its database): a vote commitment is a PERMANENT item of a deal
     * (V:CvDealClasses.cpp:1343, 1362, 1423, 1440, 1461), so it is not given for a temporary one and not taken for one; a war does not cancel the commitments (:5895);
     * a commitment whose proposal is gone makes the member abstain the votes it promised (V:CvVotingClasses.cpp:10660, 10689); and what a civilization asks for is
     * the votes it can buy with its diplomat (`League.getPotentialVotes`, :3943-4014), not the core votes of the original (its CvVotingClasses.cpp:7540). Off, none of it
     */
    var permanentVoteCommitments = false
    /** Civilizations that propose resolutions in a session, the host included (LEAGUE_PROPOSERS_PER_SESSION: 2 in Community Patch, 3 in the Vox Populi ruleset) */
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
    /**
     * How much each counts in those `count`s: a living civilization, a defeated one, a living city-state, a defeated one (DIPLO_VICTORY_CIV_DELEGATES_ALIVE, _ELIMINATED, DIPLO_VICTORY_CS_DELEGATES_ALIVE and _ELIMINATED,
     * `Defines` rows: 1, 0.5, 1 and 0.5 in Community Patch, 1.5, 0.75, 1.34 and 0.75 in the Vox Populi ruleset, V:CvGame.cpp:5679-5761). Data, not behind `leagueSessionRulesLikeVp`
     */
    var leagueWorldLeaderCivAliveWeight = 1f
    var leagueWorldLeaderCivDefeatedWeight = 0.5f
    var leagueWorldLeaderCityStateAliveWeight = 1f
    var leagueWorldLeaderCityStateDefeatedWeight = 0.5f
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
    // Percent more tourism towards a civilization that has our diplomat in its capital (TOURISM_MODIFIER_DIPLOMAT: 25, 20 in Vox Populi)
    var tourismModifierDiplomat = 25
    // With tourismModifierLikeVp, the most that shared religion gives before the policies' percent, 0 for no cap
    // (TOURISM_MODIFIER_SHARED_RELIGION_MAX: 50 in Vox Populi)
    var tourismModifierSharedReligionMax = 0
    /** Vox Populi's percent more tourism towards a civilization instead of Brave New World's (CvPlayerCulture::GetTourismModifierWith,
     *  V:CvCultureClasses.cpp:4341-4479): the policies' bonus for common enemies counts once however many there are, the diplomat counts
     *  whatever the ideologies, and the sum is never below -100. Open borders count when we let the other in. Shared religion is
     *  [tourismModifierSharedReligion] for each percent of the people of the other that follow our state religion (not a pantheon),
     *  at most [tourismModifierSharedReligionMax], and the policies' percent for it multiplies that */
    var tourismModifierLikeVp = false
    /** Vox Populi's tourism that is not made by the cities: `[n] Tourism towards all civilizations we have met` and a concert tour take the speed's
     *  culture cost modifier and the modifier towards each civilization they reach (CvPlayerCulture::ChangeInfluenceOnTimes100 with modifiers, V:CvCultureClasses.cpp:3699-3735) */
    var instantTourismLikeVp = false
    /** Vox Populi's influence trend and turns to Influential (CvPlayerCulture::GetInfluenceTrend and GetTurnsToInfluential, V:CvCultureClasses.cpp:3849-3952):
     *  this turn's tourism and culture with their averages over the last 30 turns of changes to influence and of instant culture, against last turn's */
    var influenceTrendLikeVp = false
    /** Vox Populi's tourism in hundredths (CvPlayerCulture::ChangeInfluenceOnTimes100, V:CvCultureClasses.cpp:3680-3693;
     *  GetInfluencePerTurnTimes100, V:CvCultureClasses.cpp:3778-3793): the tourism of the cities keeps the parts of their percentages up to the
     *  modifier towards each civilization, and the influence keeps what is below a whole one for the next turn, the level reading it */
    var tourismPrecisionLikeVp = false
    /** Vox Populi's culture of the turn in hundredths (CvPlayer::doTurnPostDiplomacy, V:CvPlayer.cpp:10450-10459): the stored culture and the culture
     *  of the whole game keep what is below a whole one for the next turn, and the influence levels read the latter */
    var cultureHundredthsLikeVp = false
    /** Vox Populi's accomplishments (CvPlayer::CompleteAccomplishment, V:CvPlayer.cpp:38783-38840): a finished policy branch of the first, third or fifth era,
     *  the first ideology, each chosen belief by its type and each civilization eliminated are counted, for `Completed [accomplishment] accomplishments` */
    var accomplishmentsLikeVp = false
    /** Vox Populi's Long Count yields: the end of a b'ak'tun fires `upon the end of a b'ak'tun` (CvPlayerTraits::IsEndOfMayaLongCount, V:CvTraitClasses.cpp:6993-7018),
     *  and each Great Person chosen for it is counted (CvPlayerTraits::SetUnitBaktun, V:CvTraitClasses.cpp:7325-7369), for `Long Count choices of [baseUnitFilter]` */
    var longCountYieldsLikeVp = false
    /** Vox Populi's historic events (CvPlayer::GetHistoricEventTourism, V:CvPlayer.cpp:31252-31420): a Great Person born, a new era and a world wonder built give
     *  tourism from the culture and tourism of the last turns that `[positiveAmount] turns of yields as tourism for historic events` say, and are counted for
     *  the countable `Historic events` (CvPlayer::ChangeNumHistoricEvents) */
    var historicEventsLikeVp = false
    /** Vox Populi's war victories (CvPlayer::DoWarVictoryBonuses, V:CvPlayer.cpp:18734-18760): the winner of a peace deal its loser surrenders, of a third-party
     *  peace or of an elimination completes `War won`, fires `upon winning a war` and has the historic event of a won war */
    var warVictoryLikeVp = false
    /** Vox Populi's other historic events (CvPlayer::changeGoldenAgeTurns, CvPlayerTrade::MoveUnits, CvCityReligions::CityConvertsReligion): a golden age,
     *  a trade route that ends, a city that adopts our religion first and the finds of a dig (CvPlayerCulture::DoArchaeologyChoice) give tourism and count,
     *  with `historicEventsLikeVp` */
    var historicEventDispatchLikeVp = false
    /** Vox Populi's archaeology choices (CvPlayerCulture::DoArchaeologyChoice): a Landmark in a City-State's land raises the resting influence, a culture
     *  boost in our lands also goes to the city's borders, the record of the site goes but under a Landmark; `SarcophagusSitesNearNewCities` works */
    var archaeologyChoicesLikeVp = false
    /** Vox Populi's celebrations of new Great Works (CvUnit::createGreatWork and the others that create one): `GreatWorkWltkdTurns` works */
    var greatWorkWltkdLikeVp = false
    /** Vox Populi's theming (CvPlayerCulture::MoveWorks and CvCityCulture::GetThemingBonusMultiplierTimes10000): where the AI puts its works and in which
     *  order, the theming bonuses of a building from the highest, the works other civilizations offer swapped in while theming, the AI's works arranged
     *  right after each new one and by City-States, the Optimize buttons for humans; theming percentages added up and truncated once */
    var greatWorkThemingLikeVp = false
    /** Vox Populi's golden age conversion (CvPlayer::changeGoldenAgeTurns, Trait_GAPToYield): each golden age started or extended carries the golden age
     *  points the next one needs, while there is a capital, as the triggering amount of `upon entering a Golden Age` */
    var goldenAgeConversionLikeVp = false
    /** Vox Populi's limits of the concert tour (CvUnit::canBlastTourism): none in the lands of a civilization we are at war with or already Influential over */
    var concertTourLimitsLikeVp = false
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
    // With archaeologyChoicesLikeVp: resting influence with a City-State per era of the builder for each Landmark on its land (MINOR_LANDMARK_RESTING_INFLUENCE)
    var landmarkRestingInfluencePerEra = 10

    // Great People from points (CvCityCitizens::GetSpecialistUpgradeThreshold, GREAT_PERSON_THRESHOLD_BASE and _INCREASE in GlobalDefines.xml):
    // a city needs base + increase × (Great People of the group gained so far) points, scaled by game speed and starting era.
    // 100 and 100 in Brave New World, 150 and 250 in Vox Populi (DefineChanges.sql; with GLOBAL_SEPARATE_GP_COUNTERS, which Vox Populi sets, each of the Great
    // Writers, Artists, Musicians, Merchants, Scientists and Engineers has a counter of its own: the groups of [UniqueType.GPPointPool])
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
