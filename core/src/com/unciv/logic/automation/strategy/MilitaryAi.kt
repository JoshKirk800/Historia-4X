package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.PactPlanningAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import kotlin.math.max
import kotlin.math.min

/** CvMilitaryAI DefenseState, best to worst (the original compares them by this order) */
enum class DefenseState { Enough, Neutral, Needed, Critical }

/** CvMilitaryAI ArmyType: what kind of army the cities are asked to supply units for */
enum class ArmyType { Land, NavalInvasion }

/**
 *  The strategic part of CvMilitaryAI beyond the strategy loop (`civ.ai.military`): threats, barbarian scan, unit counts by role,
 *  recommended army and navy sizes, defense states, offensive purchases and disbanding of obsolete units.
 *
 *  For the production port, the military AI's requests are its strategies' flavors (through `civ.ai.flavors()` / `city.ai.flavors()`)
 *  and this API: [landDefenseState], [navalDefenseState], [recommendedLandArmySize], [recommendedNavySize], [recommendedExplorers],
 *  [mandatoryReserveSize], the unit counts ([numLandUnits], [numRangedLandUnits], [numMobileLandUnits], [numMeleeLandUnits],
 *  [numAntiAirUnits], [numNavalUnits], [numAirUnits]), [highestThreat], [totalThreatWeight] and [barbarianThreatTotal].
 *
 *  Not ported, with the reason: UpdateWars (its only effect is a log line). The operation side (UpdateOperations, the Request* functions,
 *  IsAttackReady, MakeEmergencyPurchases) is in [MilitaryOperations], the target selection in [MilitaryTargets].
 */
class MilitaryAi : IsPartOfGameInfoSerialization {
    /** CvMilitaryAI::m_paeLastTurnMilitaryThreat: by civID, [AiThreat] name (missing = None) */
    var lastTurnMilitaryThreats = HashMap<String, String>()
    /** CvMilitaryAI::m_iTotalThreatWeight: starts at 1, "don't ever assume there is no threat at all" */
    var totalThreatWeight = 1
    /** CvMilitaryAI::m_iNumberOfTimesOpsBuildSkippedOver: how often a city was offered a unit for an operation and chose another item; it raises that unit's weight */
    var numberOfTimesOpsBuildSkippedOver = 0
    /** CvMilitaryAI::m_eArmyTypeBeingBuilt: set when an attack was requested that the units at hand cannot form, never reset in the original */
    var armyTypeBeingBuilt: ArmyType? = null
    /** CvMilitaryAI::m_iNumLandAttacksRequested / m_iNumNavalAttacksRequested: the attacks that could not start for lack of units */
    var numLandAttacksRequested = 0
    var numNavalAttacksRequested = 0

    /** The step finder the target selection and the operations use (scratch space, not saved) */
    @Transient val steps = StepFinder()

    //region Per-turn data (CvMilitaryAI::UpdateBaseData, ScanForBarbarians, UpdateDefenseState), recomputed each turn
    @Transient private var dataTurn = -1
    @Transient var numLandUnits = 0; private set
    /** CvMilitaryAI::m_iNumLandUnitsInArmies: land units that serve in an operation's army, counted with the base data */
    @Transient var numLandUnitsInArmies = 0; private set
    @Transient var numRangedLandUnits = 0; private set
    @Transient var numMobileLandUnits = 0; private set
    @Transient var numMeleeLandUnits = 0; private set
    @Transient var numAntiAirUnits = 0; private set
    @Transient var numNavalUnits = 0; private set
    @Transient var numAirUnits = 0; private set
    /** Brave New World's mandatory reserve, see [bnwMandatoryReserve] */
    @Transient var mandatoryReserveSize = 0; private set
    /** CvMilitaryAI::m_iRecLandUnits / m_iRecNavalUnits / m_iRecExplorerUnits (Vox Populi), see [setRecommendedArmyNavySize] */
    @Transient var recommendedLandArmySize = 0; private set
    @Transient var recommendedNavySize = 0; private set
    @Transient var recommendedExplorers = 0; private set
    @Transient var landDefenseState = DefenseState.Neutral; private set
    @Transient var navalDefenseState = DefenseState.Neutral; private set
    @Transient var barbarianCampCount = 0; private set
    @Transient var visibleBarbarianCount = 0; private set
    //endregion

    fun clone(): MilitaryAi {
        val toReturn = MilitaryAi()
        toReturn.lastTurnMilitaryThreats.putAll(lastTurnMilitaryThreats)
        toReturn.totalThreatWeight = totalThreatWeight
        toReturn.numberOfTimesOpsBuildSkippedOver = numberOfTimesOpsBuildSkippedOver
        toReturn.armyTypeBeingBuilt = armyTypeBeingBuilt
        toReturn.numLandAttacksRequested = numLandAttacksRequested
        toReturn.numNavalAttacksRequested = numNavalAttacksRequested
        return toReturn
    }

    /** The unit-short branch of RequestSneakAttack / RequestCityStateAttack: one more attack by land or by sea waits for units, the larger count names the army */
    fun requestArmy(bySea: Boolean) {
        if (bySea) numNavalAttacksRequested++ else numLandAttacksRequested++
        armyTypeBeingBuilt = if (numLandAttacksRequested > numNavalAttacksRequested) ArmyType.Land else ArmyType.NavalInvasion
    }

    /** What CvMilitaryAI::DoTurn does before its strategy loop: ScanForBarbarians, UpdateThreats, UpdateBaseData, UpdateDefenseState */
    fun doTurnBeforeStrategies(civ: Civilization) {
        scanForBarbarians(civ)
        updateThreats(civ)
        updateBaseData(civ)
        updateDefenseState(civ)
        dataTurn = civ.gameInfo.turns
    }

    /**
     *  What CvMilitaryAI::DoTurn does after its strategy loop for AI players: UpdateOperations, MakeEmergencyPurchases,
     *  MakeOffensivePurchases, DisbandObsoleteUnits
     */
    fun doTurnAfterStrategies(civ: Civilization) {
        MilitaryOperations.updateOperations(civ)
        MilitaryOperations.makeEmergencyPurchases(civ)
        makeOffensivePurchases(civ)
        disbandObsoleteUnits(civ)
    }

    /** Computes the per-turn data if it was never computed (a game just loaded, or another player's AI asking before our turn) */
    fun ensureData(civ: Civilization) {
        if (dataTurn < 0) doTurnBeforeStrategies(civ)
    }

    //region Threats

    /** CvMilitaryAI::GetThreatWeight */
    fun threatWeight(civ: Civilization, threat: AiThreat): Int {
        val rules = civ.gameInfo.ruleset.ai
        return when (threat) {
            AiThreat.None -> 0
            AiThreat.Minor -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MINOR)
            AiThreat.Major -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MAJOR)
            AiThreat.Severe -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_SEVERE)
            AiThreat.Critical -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_CRITICAL)
        }
    }

    /** CvMilitaryAI::UpdateThreats (ThreatIncrease/ThreatDecrease): the total weight follows each valid major's change of threat level */
    fun updateThreats(civ: Civilization) {
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || !civ.ai.diplomacy.isPlayerValid(other)) continue
            val threat = civ.ai.diplomacy.militaryThreat(other)
            val last = lastTurnMilitaryThreats[other.civID]?.let { AiThreat.valueOf(it) } ?: AiThreat.None
            if (threat == last) continue
            totalThreatWeight += threatWeight(civ, threat) - threatWeight(civ, last)
            lastTurnMilitaryThreats[other.civID] = threat.name
        }
    }

    /** CvMilitaryAI::GetHighestThreat: the highest military threat level of a valid major, as its level number (NONE = 0 ... CRITICAL = 4) */
    fun highestThreat(civ: Civilization): Int {
        var highest = AiThreat.None
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || !civ.ai.diplomacy.isPlayerValid(other)) continue
            val threat = civ.ai.diplomacy.militaryThreat(other)
            if (threat > highest) highest = threat
        }
        return highest.ordinal
    }

    /**
     *  CvMilitaryAI::ScanForBarbarians: revealed barbarian camps (a camp in our territory counts 10, one within 9 tiles of a city 5)
     *  and visible tiles with barbarian units.
     */
    fun scanForBarbarians(civ: Civilization) {
        var camps = 0
        var visible = 0
        for (tile in civ.gameInfo.tileMap.values) {
            if (civ.hasExplored(tile) && tile.isBarbarianEncampment()) {
                camps++
                if (tile.getOwner() == civ) camps += 9
                else if (civ.cities.any { it.getCenterTile().aerialDistanceTo(tile) < 10 }) camps += 4
            }
            if (tile in civ.viewableTiles && tile.getUnits().any { it.civ.isBarbarian }) visible++
        }
        barbarianCampCount = camps
        visibleBarbarianCount = visible
    }

    /** CvMilitaryAI::GetBarbarianThreatTotal: a major threat per camp, a minor one per AI_MILITARY_BARBARIANS_FOR_MINOR_THREAT barbarians */
    fun barbarianThreatTotal(civ: Civilization): Int {
        val rules = civ.gameInfo.ruleset.ai
        return rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MAJOR) * barbarianCampCount +
            visibleBarbarianCount / rules.define(AiDefine.AI_MILITARY_BARBARIANS_FOR_MINOR_THREAT)
    }
    //endregion

    //region Army size (CvMilitaryAI::UpdateBaseData, SetRecommendedArmyNavySize, UpdateDefenseState)

    /**
     *  CvMilitaryAI::UpdateBaseData (Vox Populi). Counts the units that can attack, explorers included: land ones by role (ranged; else
     *  anti-air; else mobile with more than 2 moves; else melee), naval and air ones; then [setRecommendedArmyNavySize].
     */
    fun updateBaseData(civ: Civilization) {
        var land = 0; var ranged = 0; var mobile = 0; var melee = 0; var antiAir = 0; var naval = 0; var air = 0; var inArmies = 0
        for (unit in civ.units.getCivUnits()) {
            if (!unit.isMilitary()) continue
            val baseUnit = unit.baseUnit
            when {
                baseUnit.isLandUnit -> {
                    land++
                    if (civ.ai.operations.armyOf(unit) != null) inArmies++
                    when {
                        baseUnit.isRanged() -> ranged++
                        unit.canIntercept() -> antiAir++
                        baseUnit.movement > 2 -> mobile++
                        else -> melee++
                    }
                }
                baseUnit.isWaterUnit -> naval++
                baseUnit.isAirUnit() -> air++
            }
        }
        numLandUnits = land; numRangedLandUnits = ranged; numMobileLandUnits = mobile; numMeleeLandUnits = melee
        numLandUnitsInArmies = inArmies
        numAntiAirUnits = antiAir; numNavalUnits = naval; numAirUnits = air
        mandatoryReserveSize = bnwMandatoryReserve(civ)
        setRecommendedArmyNavySize(civ)
    }

    /**
     *  The mandatory reserve of Brave New World's CvMilitaryAI::UpdateBaseData, which Vox Populi dropped. Kept for
     *  [MilitaryOperations.landReservesAvailable] until the operations recruit their units the Vox Populi way
     *  (CvAIOperation::GrabUnitsFromTheReserves). (DEFEND_MY_LANDS_BASE_UNITS + cities x UNITS_PER_CITY + settlers) x (0.40 + (highest
     *  threat + OFFENSE + DEFENSE) / 100) (personality and grand strategy) + max(0, handicap - 1), at least 1; city-states halve it;
     *  x 2/3 under NAVAL_MAP.
     */
    private fun bnwMandatoryReserve(civ: Civilization): Int {
        val rules = civ.gameInfo.ruleset.ai
        val flavorOffense = civ.ai.personalityAndGrandStrategy(Flavor.Offense)
        val flavorDefense = civ.ai.personalityAndGrandStrategy(Flavor.Defense)
        val multiplier = 0.40f + (highestThreat(civ) + flavorOffense + flavorDefense).toFloat() / 100f
        var unitsWanted = rules.define(AiDefine.AI_STRATEGY_DEFEND_MY_LANDS_BASE_UNITS)
        unitsWanted += (civ.cities.size * rules.defineFloat(AiDefine.AI_STRATEGY_DEFEND_MY_LANDS_UNITS_PER_CITY)).toInt()
        unitsWanted += UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true)
        var reserve = (unitsWanted * multiplier).toInt()
        reserve += max(0, EconomicAi.gameHandicapIndex(civ.gameInfo) - 1)
        reserve = max(1, reserve)
        if (civ.isCityState) reserve = max(1, reserve / 2)
        if (civ.ai.isEconomicStrategyActive(NAVAL_MAP)) reserve = reserve * 2 / 3
        return reserve
    }

    /**
     *  CvMilitaryAI::SetRecommendedArmyNavySize (Vox Populi): splits the units we can afford ([EconomicAi.softSupplyCap]) between land
     *  and sea. City-states want 2 ships with a coastal capital, the rest on land. Majors weigh land defense 10 x (DEFEND_MY_LANDS_BASE_UNITS
     *  + cities x UNITS_PER_CITY + settlers, by default unit AI and in training), naval defense 7 x effective coastal cities x
     *  UNITS_PER_CITY, offense 10 x BALANCE_BASIC_ATTACK_ARMY_SIZE under conquest; the naval share of offense is effective coastal cities
     *  x NAVAL (personality) x 7 / cities. Defense x (100 + 2 x DEFENSE), offense x (100 + boldness + OFFENSE) (personality and grand
     *  strategy). Explorers (units whose default unit AI is EXPLORE) take their own share first: at most a quarter of the cap, no more
     *  than the supply leaves room for. Land is truncated, sea rounded, as in the original; a share our units already exceed grows to
     *  them at the other's expense.
     *
     *  Missing inputs, counted as none: the cities exposed to an enemy (10 land and 10 naval weight each) and the preferred attack
     *  targets (10 x BALANCE_BASIC_ATTACK_ARMY_SIZE offense each) of CvMilitaryAI::UpdateAttackTargets, and the explorers needed of
     *  Vox Populi's CvEconomicAI::DoReconState.
     */
    private fun setRecommendedArmyNavySize(civ: Civilization) {
        var maxPossibleUnits = EconomicAi.softSupplyCap(civ)
        if (civ.isCityState) {
            recommendedNavySize = if (civ.getCapital()?.isCoastal() == true) 2 else 0
            recommendedLandArmySize = maxPossibleUnits - recommendedNavySize
            return
        }

        val rules = civ.gameInfo.ruleset.ai
        val unitsPerCity = rules.defineFloat(AiDefine.AI_STRATEGY_DEFEND_MY_LANDS_UNITS_PER_CITY)
        val numCoastalCities = PactPlanningAi.numEffectiveCoastalCities(civ)
        var landDefenseWeight = rules.define(AiDefine.AI_STRATEGY_DEFEND_MY_LANDS_BASE_UNITS) * 10
        landDefenseWeight += (civ.cities.size * 10 * unitsPerCity).toInt()
        landDefenseWeight += UnitAiTypes.numUnitsWithDefaultUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true) * 10
        var navalDefenseWeight = (numCoastalCities * 7 * unitsPerCity).toInt()

        var totalOffenseWeight = 0
        if (civ.ai.isGoingForWorldConquest()) totalOffenseWeight += rules.define(AiDefine.BALANCE_BASIC_ATTACK_ARMY_SIZE) * 10

        val navalPercent = numCoastalCities * civ.ai.personality(Flavor.Naval) * 7 / max(1, civ.cities.size)
        val defenseModifier = 100 + civ.ai.personalityAndGrandStrategy(Flavor.Defense) * 2
        val offenseModifier = 100 + civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) + civ.ai.personalityAndGrandStrategy(Flavor.Offense)
        landDefenseWeight *= defenseModifier
        navalDefenseWeight *= defenseModifier
        totalOffenseWeight *= offenseModifier
        val totalWeight = totalOffenseWeight + landDefenseWeight + navalDefenseWeight

        val numExplorers = UnitAiTypes.numUnitsWithDefaultUnitAi(civ, UnitAiType.EXPLORE, includeBeingTrained = false)
        val numUnitsToSupply = civ.units.getCivUnitsSize()
        val numUnitsSupplied = civ.stats.getUnitSupply()
        var explorersNeeded = min(maxPossibleUnits / 4, 0)
        if (numUnitsToSupply - numExplorers + explorersNeeded > numUnitsSupplied)
            explorersNeeded = numExplorers + numUnitsSupplied - numUnitsToSupply
        recommendedExplorers = explorersNeeded
        maxPossibleUnits -= max(explorersNeeded, numExplorers)

        var land = (maxPossibleUnits.toLong() * (100L * landDefenseWeight + totalOffenseWeight.toLong() * (100 - navalPercent)) / (100f * totalWeight)).toInt()
        var naval = (maxPossibleUnits.toLong() * (100L * navalDefenseWeight + totalOffenseWeight.toLong() * navalPercent) / (100f * totalWeight) + 0.5f).toInt()
        val numLand = numLandUnits - numExplorers
        if (numLand > land) {
            naval -= numLand - land
            land = numLand
        } else if (numNavalUnits > naval) {
            land -= numNavalUnits - naval
            naval = numNavalUnits
        }
        recommendedLandArmySize = land
        recommendedNavySize = naval
    }

    /**
     *  CvMilitaryAI::UpdateDefenseState (Vox Populi): land CRITICAL below the recommended army, NEUTRAL below 125% of it, else ENOUGH
     *  (the original's NEEDED below 75% can never be reached); naval CRITICAL up to half the recommended navy, NEEDED up to it, NEUTRAL up
     *  to 125%, else ENOUGH. A city under siege (a coastal one for the navy) makes a NEUTRAL or ENOUGH state CRITICAL.
     */
    fun updateDefenseState(civ: Civilization) {
        landDefenseState = when {
            numLandUnits < recommendedLandArmySize -> DefenseState.Critical
            numLandUnits < recommendedLandArmySize * 3 / 4 -> DefenseState.Needed
            numLandUnits < recommendedLandArmySize * 5 / 4 -> DefenseState.Neutral
            else -> DefenseState.Enough
        }
        if (landDefenseState <= DefenseState.Neutral && civ.cities.any { CityDangerAi.isUnderSiege(it) })
            landDefenseState = DefenseState.Critical
        navalDefenseState = when {
            numNavalUnits <= recommendedNavySize / 2 -> DefenseState.Critical
            numNavalUnits <= recommendedNavySize -> DefenseState.Needed
            numNavalUnits <= recommendedNavySize * 5 / 4 -> DefenseState.Neutral
            else -> DefenseState.Enough
        }
        if (navalDefenseState <= DefenseState.Neutral && civ.cities.any { it.isCoastal() && CityDangerAi.isUnderSiege(it) })
            navalDefenseState = DefenseState.Critical
    }

    /** CvMilitaryAI::HasAirforce */
    fun hasAirforce(): Boolean = numAirUnits > 0
    //endregion

    //region Purchases and disbanding

    /**
     *  CvMilitaryAI::MakeOffensivePurchases (Brave New World; Vox Populi has none): majors not at war (or winning all wars), with personality
     *  OFFENSE >= 7 and a land army below the recommended size ([recommendedLandArmySize]) or a navy that is not ENOUGH, buy one unit in the capital - for the navy if its state is worse than the land
     *  one (in the first coastal city if the capital is not; none if no city is): assault or attack ship; otherwise fast attack, attack or
     *  defense when there are no more melee than ranged units, else siege or ranged.
     */
    fun makeOffensivePurchases(civ: Civilization) {
        if (civ.isCityState) return
        if (civ.ai.isMilitaryStrategyActive(AT_WAR) && WarStateAi.stateAllWars(civ) != StateAllWars.Winning) return
        if (civ.ai.personality(Flavor.Offense) < 7) return
        if (numLandUnits >= recommendedLandArmySize && navalDefenseState <= DefenseState.Enough) return
        // Do we have operations running (if so let them recruit the units)?
        if (armyTypeBeingBuilt != null) return
        var city = civ.getCapital(firstCityIfNoCapital = false) ?: return
        var needNaval = navalDefenseState > landDefenseState
        if (needNaval && !city.isCoastal()) {
            val coastal = civ.cities.firstOrNull { it.isCoastal() }
            needNaval = coastal != null
            if (coastal != null) city = coastal
        }
        val order = when {
            needNaval -> listOf(UnitAiType.ASSAULT_SEA, UnitAiType.ATTACK_SEA)
            numMeleeLandUnits <= numRangedLandUnits -> listOf(UnitAiType.FAST_ATTACK, UnitAiType.ATTACK, UnitAiType.DEFENSE)
            else -> listOf(UnitAiType.CITY_BOMBARD, UnitAiType.RANGED)
        }
        for (type in order) if (UseGoldAutomation.buyEmergencyUnit(civ, type, city) != null) return
    }

    /**
     *  CvMilitaryAI::DisbandObsoleteUnits: majors at peace. When losing money (LOSING_MONEY, or more than 5 gold per unit) or, when not
     *  going for conquest, with a land (naval) defense state of NEUTRAL or ENOUGH, finds the weakest obsolete land (naval) unit
     *  ([findBestUnitToScrap]); scraps the land one if the land state is not worse than the naval one or it is not stronger, else the naval one.
     */
    fun disbandObsoleteUnits(civ: Civilization) {
        if (WarStateAi.numberCivsAtWarWith(civ) > 0) return
        if (civ.isCityState) return
        val averageGoldPerUnit = civ.stats.getUnitMaintenance() / max(1, civ.units.getCivUnitsSize())
        val inDeficit = civ.ai.isEconomicStrategyActive(EconomicAi.LOSING_MONEY) || averageGoldPerUnit > 5
        val conquest = civ.ai.isGoingForWorldConquest()

        var land: Pair<MapUnit, Int>? = null
        var naval: Pair<MapUnit, Int>? = null
        if (inDeficit || (landDefenseState <= DefenseState.Neutral && !conquest)) land = findBestUnitToScrap(civ, isLand = true)
        if (inDeficit || (navalDefenseState <= DefenseState.Neutral && !conquest)) naval = findBestUnitToScrap(civ, isLand = false)
        val landScore = land?.second ?: Int.MAX_VALUE
        val navalScore = naval?.second ?: Int.MAX_VALUE
        if (land != null && (landDefenseState <= navalDefenseState || landScore <= navalScore)) land.first.disband()
        else naval?.first?.disband()
    }

    /**
     *  CvPlayer::DoDeficit, which CvTreasury::DoGold calls when the treasury after the turn's income would be at or below
     *  DEFICIT_UNIT_DISBANDING_THRESHOLD (see [com.unciv.logic.civilization.managers.TurnManager.endTurn]): for any civilization (human
     *  too) with more military units than cities, in half of the cases one unit is disbanded - the weakest of [findBestUnitToScrap] over
     *  land (when the land defense state is NEUTRAL or better) and over sea (likewise), the land one if the land state is not worse than
     *  the naval one or it is not stronger. The player is told which.
     */
    fun doDeficit(civ: Civilization) {
        if (civ.units.getCivUnits().count { it.isMilitary() } <= civ.cities.size) return
        if (civ.ai.random("DoDeficit").nextInt(100) >= 50) return
        ensureData(civ)

        var land: Pair<MapUnit, Int>? = null
        var naval: Pair<MapUnit, Int>? = null
        if (landDefenseState <= DefenseState.Neutral) land = findBestUnitToScrap(civ, isLand = true, deficitForced = true)
        if (navalDefenseState <= DefenseState.Neutral) naval = findBestUnitToScrap(civ, isLand = false, deficitForced = true)
        val landScore = land?.second ?: Int.MAX_VALUE
        val navalScore = naval?.second ?: Int.MAX_VALUE
        val scrapped = when {
            land != null && (landDefenseState <= navalDefenseState || landScore <= navalScore) -> land.first
            naval != null -> naval.first
            else -> null
        } ?: return
        civ.addNotification("A unit was disbanded because your empire is running a negative budget and has no treasury!",
            scrapped.getTile().position, NotificationCategory.Units, scrapped.name, NotificationIcon.Death)
        scrapped.disband()
    }

    /**
     *  CvMilitaryAI::FindBestUnitToScrap: among military units of the domain the one with the lowest power (CvUnit::GetPower: level and
     *  health), null if none. Not [deficitForced] (the AI deciding to disband): skipping all if that domain's defense state is CRITICAL,
     *  land units we can still build, units in an army, units without a researched obsolete tech, units with at least half the power of the
     *  strongest unit we can build in the domain, and units whose upgrade needs a resource we have while that domain's state is worse than NEUTRAL.
     *  When the game forces the disbanding (CvPlayer::DoDeficit), none of that applies.
     */
    fun findBestUnitToScrap(civ: Civilization, isLand: Boolean, deficitForced: Boolean = false): Pair<MapUnit, Int>? {
        val state = if (isLand) landDefenseState else navalDefenseState
        if (!deficitForced && state == DefenseState.Critical) return null
        val strongestBuildable = if (deficitForced) 0 else powerOfStrongestBuildableUnit(civ, isLand)
        var best: MapUnit? = null
        var bestScore = Int.MAX_VALUE
        for (unit in civ.units.getCivUnits()) {
            if (!unit.isMilitary()) continue
            val baseUnit = unit.baseUnit
            if (isLand && !baseUnit.isLandUnit || !isLand && !baseUnit.isWaterUnit) continue
            if (!deficitForced) {
                if (civ.ai.operations.armyOf(unit) != null) continue
                if (isLand && baseUnit.isBuildable(civ)) continue
                val obsoleteTech = baseUnit.obsoleteTech ?: continue
                if (!civ.tech.isResearched(obsoleteTech)) continue
                if (baseUnit.getAiPower() * 2 >= strongestBuildable) continue
                if (state > DefenseState.Neutral) {
                    val upgradeTo = baseUnit.upgradesTo?.let { civ.getEquivalentUnit(it) }
                    if (upgradeTo != null && upgradeTo.getResourceRequirementsPerTurn(civ.state).keys.any { civ.getResourceAmount(it) > 0 })
                        continue // we'll wait and try to upgrade this one
                }
            }
            val score = unit.getAiPower()
            if (score < bestScore) {
                bestScore = score
                best = unit
            }
        }
        return best?.let { it to bestScore }
    }
    //endregion

    companion object {
        const val AT_WAR = "MILITARYAISTRATEGY_AT_WAR"
        const val NAVAL_MAP = "ECONOMICAISTRATEGY_NAVAL_MAP"
        const val EXPAND_TO_OTHER_CONTINENTS = "ECONOMICAISTRATEGY_EXPAND_TO_OTHER_CONTINENTS"

        /** CvMilitaryAI::GetPowerOfStrongestBuildableUnit: the highest unit type power among units of the domain we can train */
        fun powerOfStrongestBuildableUnit(civ: Civilization, isLand: Boolean): Int {
            var best = 0
            for (baseUnit in civ.gameInfo.ruleset.units.values) {
                if (isLand && !baseUnit.isLandUnit || !isLand && !baseUnit.isWaterUnit) continue
                val power = baseUnit.getAiPower()
                if (power > best && baseUnit.isBuildable(civ)) best = power
            }
            return best
        }
    }
}
