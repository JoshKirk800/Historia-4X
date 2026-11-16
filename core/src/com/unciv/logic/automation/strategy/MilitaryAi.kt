package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.PactPlanningAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
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

    /**
     *  CvMilitaryAI::m_potentialAttackTargets / m_exposedCities ([AttackTargetsAi.updateAttackTargets]): recomputed each turn after the
     *  strategies, so the base data reads the last turn's
     */
    @Transient var potentialAttackTargets: List<AttackTarget> = emptyList(); private set
    @Transient var exposedCities: List<AttackTarget> = emptyList(); private set
    @Transient private var attackTargetsTurn = -1

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

    /**
     *  What CvMilitaryAI::DoTurn does before its strategy loop: ScanForBarbarians, UpdateThreats, UpdateBaseData, UpdateDefenseState. The
     *  attack targets are worked out first when there are none yet (the original keeps the last turn's in the save).
     */
    fun doTurnBeforeStrategies(civ: Civilization) {
        if (attackTargetsTurn < 0) updateAttackTargets(civ)
        scanForBarbarians(civ)
        updateThreats(civ)
        updateBaseData(civ)
        updateDefenseState(civ)
        dataTurn = civ.gameInfo.turns
    }

    /** CvMilitaryAI::UpdateAttackTargets, after the strategy loop for every player (the AI relies on the data of humans too) */
    fun updateAttackTargets(civ: Civilization) {
        val (potential, exposed) = AttackTargetsAi.updateAttackTargets(civ)
        potentialAttackTargets = potential
        exposedCities = exposed
        attackTargetsTurn = civ.gameInfo.turns
    }

    /** CvMilitaryAI::IsExposedToEnemy: [city] (null: any of ours) is exposed to [enemy] (null: anyone) by an army of [armyType] (null: any); city-states assume all */
    fun isExposedToEnemy(civ: Civilization, city: City?, enemy: Civilization?, armyType: TargetArmyType? = null): Boolean {
        if (civ.isCityState) return true
        return exposedCities.any { (enemy == null || it.musterCity.civ == enemy) && (city == null || it.targetCity == city)
            && (armyType == null || it.armyType == armyType) }
    }

    /** CvMilitaryAI::HavePreferredAttackTarget: a preferred target among [enemy]'s cities */
    fun havePreferredAttackTarget(enemy: Civilization): Boolean = potentialAttackTargets.any { it.preferred && it.targetCity.civ == enemy }

    /** CvMilitaryAI::HavePossibleAttackTarget: any target among [enemy]'s cities */
    fun havePossibleAttackTarget(enemy: Civilization): Boolean = potentialAttackTargets.any { it.targetCity.civ == enemy }

    /** CvMilitaryAI::IsPreferredAttackTarget: [city] is a preferred target */
    fun isPreferredAttackTarget(city: City): Boolean = potentialAttackTargets.any { it.preferred && it.targetCity == city }

    /** CvMilitaryAI::IsPossibleAttackTarget: [city], not ours, is a target for an army of [armyType] (null: any) */
    fun isPossibleAttackTarget(civ: Civilization, city: City, armyType: TargetArmyType? = null): Boolean =
        city.civ != civ && potentialAttackTargets.any { it.targetCity == city && (armyType == null || it.armyType == armyType) }

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
     *  + cities x UNITS_PER_CITY + settlers, by default unit AI and in training) + 10 per city exposed to an enemy by land, naval defense
     *  7 x effective coastal cities x UNITS_PER_CITY + 10 per city exposed by sea (a combined exposure counts for both), offense 10 x
     *  BALANCE_BASIC_ATTACK_ARMY_SIZE per major with a preferred attack target and once more under conquest; the naval share of offense
     *  is effective coastal cities x NAVAL (personality) x 7 / cities. Defense x (100 + 2 x DEFENSE), offense x (100 + boldness + OFFENSE)
     *  (personality and grand strategy). Explorers (units whose default unit AI is EXPLORE) take their own share first: at most a
     *  quarter of the cap, no more than the supply leaves room for. Land is truncated, sea rounded, as in the original; a share our units
     *  already exceed grows to them at the other's expense.
     *
     *  Missing input, counted as none: the explorers needed of Vox Populi's CvEconomicAI::DoReconState.
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
        for (city in civ.cities) {
            if (isExposedToEnemy(civ, city, null, TargetArmyType.Combined)) {
                landDefenseWeight += 10
                navalDefenseWeight += 10
            } else {
                if (isExposedToEnemy(civ, city, null, TargetArmyType.Land)) landDefenseWeight += 10
                if (isExposedToEnemy(civ, city, null, TargetArmyType.Naval)) navalDefenseWeight += 10
            }
        }

        val attackArmySize = rules.define(AiDefine.BALANCE_BASIC_ATTACK_ARMY_SIZE)
        var totalOffenseWeight = 0
        for (other in civ.gameInfo.civilizations)
            if (other != civ && other.isMajorCiv && havePreferredAttackTarget(other)) totalOffenseWeight += attackArmySize * 10
        if (civ.ai.isGoingForWorldConquest()) totalOffenseWeight += attackArmySize * 10

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
     *  CvMilitaryAI::DisbandObsoleteUnits (Vox Populi), after turn 25: not for a major losing all its wars, nor for a city-state at war
     *  or with barbarians threatening it. A useless ship goes first ([findUselessShip]); else, when LOSING_MONEY (majors) or with more
     *  than 1 unit over the supply (3 going for conquest), the cheaper of the land and the sea unit [findUnitToScrap] names, the sea one
     *  on a tie.
     *
     *  Not here: the units that cannot heal and are hurt (no Brave New World unit is unable to heal); CvPlayer::GetBestGiftTarget (a
     *  major gives the unit to a city-state instead of scrapping it) is not ported: the unit is scrapped.
     */
    fun disbandObsoleteUnits(civ: Civilization) {
        if (civ.isBarbarian || civ.gameInfo.turns <= 25) return
        var inDeficit = false
        var conquest = false
        if (civ.isCityState) {
            if (civ.isAtWar()) return
            if (civ.cityStateFunctions.getNumThreateningBarbarians() > 0) return
        } else {
            if (WarStateAi.numberCivsAtWarWith(civ) > 0 && WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return
            inDeficit = civ.ai.isEconomicStrategyActive(EconomicAi.LOSING_MONEY)
            conquest = civ.ai.isGoingForWorldConquest()
        }
        val overSupplyCap = civ.stats.getUnitSupplyDeficit() > if (conquest) 3 else 1

        var scrap = findUselessShip(civ)
        if (scrap == null && (inDeficit || overSupplyCap)) {
            val naval = findUnitToScrap(civ, isLand = false)
            val land = findUnitToScrap(civ, isLand = true)
            scrap = if ((land?.second ?: Int.MAX_VALUE) < (naval?.second ?: Int.MAX_VALUE)) land?.first else naval?.first
        }
        scrap?.disband()
    }

    /**
     *  CvMilitaryAI::FindUselessShip (Vox Populi): the least experienced combat ship that no area needs ([AreaCensus.needShipInArea]) - neither the
     *  one it is in (a ship in port is in its continent) nor a water body it reaches within 2 turns.
     *
     *  Deviation: the 2 turns are 2 x its moves in water tiles it may enter (not the ocean for a ship that cannot), whatever their cost
     *  and whoever stands or owns them (the original's path ignores stacking, enemies and right of passage).
     */
    private fun findUselessShip(civ: Civilization): MapUnit? {
        val census = AreaCensus(civ)
        val candidates = civ.units.getCivUnits()
            .filter { it.isMilitary() && it.baseUnit.isWaterUnit && !census.needShipInArea(census.areaOf(it.getTile())) }
            .sortedBy { it.promotions.XP }
        for (ship in candidates) {
            val range = 2 * ship.getMaxMovement()
            val reached = HashSet<Tile>()
            var frontier = listOf(ship.getTile())
            reached.add(ship.getTile())
            repeat(range) {
                frontier = frontier.flatMap { tile -> tile.neighbors.filter { next ->
                    next.isWater && !next.isImpassible() && (!next.isOcean || !ship.cache.cannotEnterOceanTiles) && reached.add(next)
                }.toList() }
            }
            if (reached.filter { it.isWater }.map { census.areaOf(it) }.toSet().none { census.needShipInArea(it) }) return ship
        }
        return null
    }

    /**
     *  The per-area counts of CvArea/CvLandmass that MilitaryAIHelpers::NeedShipInArea reads. An area is a water body or a continent; its
     *  units are those standing on it, its cities those on it - for a water body, those next to it.
     */
    private class AreaCensus(private val civ: Civilization) {
        private val bodies = WaterBodies.of(civ)
        private val tileMap = civ.gameInfo.tileMap
        private val ourUnits = HashMap<Int, Int>()
        private val allUnits = HashMap<Int, Int>()
        private val ourCities = HashMap<Int, Int>()
        private val allCities = HashMap<Int, Int>()

        init {
            for (other in civ.gameInfo.civilizations) {
                for (unit in other.units.getCivUnits()) {
                    val area = areaOf(unit.getTile())
                    allUnits.merge(area, 1, Int::plus)
                    if (other == civ) ourUnits.merge(area, 1, Int::plus)
                }
                for (city in other.cities) {
                    val center = city.getCenterTile()
                    val areas = center.neighbors.filter { it.isWater }.map { areaOf(it) }.toSet() + areaOf(center)
                    for (area in areas) {
                        allCities.merge(area, 1, Int::plus)
                        if (other == civ) ourCities.merge(area, 1, Int::plus)
                    }
                }
            }
        }

        /** Water bodies as -1 - their id, continents as their id */
        fun areaOf(tile: Tile): Int = if (tile.isWater) -1 - bodies.bodyOf(tile) else tile.getContinent()

        private fun tiles(area: Int): Int = if (area < 0) bodies.size(-1 - area) else tileMap.continentSizes[area] ?: 0

        /**
         *  MilitaryAIHelpers::NeedShipInArea: unless we have more than one ship per AI_CONFIG_MILITARY_TILES_PER_SHIP tiles there (all our
         *  units count), other civilizations' cities or units in the area
         */
        fun needShipInArea(area: Int): Boolean {
            val ours = ourUnits[area] ?: 0
            val tooManyUnits = tiles(area) < ours * civ.gameInfo.ruleset.ai.define(AiDefine.AI_CONFIG_MILITARY_TILES_PER_SHIP)
            if (tooManyUnits) return false
            val foreignCities = (allCities[area] ?: 0) - (ourCities[area] ?: 0)
            val foreignUnits = (allUnits[area] ?: 0) - ours
            return foreignCities > 0 || foreignUnits > 0
        }
    }

    /**
     *  CvMilitaryAI::FindUnitToScrap (Vox Populi, checking obsolescence): among our combat units of the domain, the obsolete ones (their
     *  obsolete tech researched, unless they have an upgrade whose resources we have, counting what the unit itself uses) and those using a
     *  resource we lack, the one with the lowest power x production cost + level (halved for a resource lack). Explorers are kept while
     *  the recon state (naval for sea explorers) is NEEDED, units in an army always (CvUnit::canUseForAIOperation; its garrison, tactical
     *  zone and nearby enemy checks are not ported).
     */
    fun findUnitToScrap(civ: Civilization, isLand: Boolean): Pair<MapUnit, Int>? {
        var best: MapUnit? = null
        var bestScore = Int.MAX_VALUE
        for (unit in civ.units.getCivUnits()) {
            if (!unit.isMilitary()) continue
            val baseUnit = unit.baseUnit
            if (isLand && !baseUnit.isLandUnit || !isLand && !baseUnit.isWaterUnit) continue
            val unitAi = UnitAiTypes.current(unit)
            if (unitAi == UnitAiType.EXPLORE_SEA && civ.ai.economic.navalReconState == ReconState.Needed) continue
            if (unitAi == UnitAiType.EXPLORE && civ.ai.economic.reconState == ReconState.Needed) continue
            if (civ.ai.operations.armyOf(unit) != null) continue

            val ownResources = baseUnit.getResourceRequirementsPerTurn(civ.state)
            var obsolete = baseUnit.obsoleteTech?.let { civ.tech.isResearched(it) } == true
            if (obsolete) {
                val upgrade = baseUnit.getRulesetUpgradeUnits(civ.state).firstOrNull()?.let { civ.getEquivalentUnit(it) }
                if (upgrade != null)
                    obsolete = upgrade.getResourceRequirementsPerTurn(civ.state).any { (resource, amount) ->
                        civ.getResourceAmount(resource) + ownResources[resource] < amount
                    }
            }
            val resourceDeficit = ownResources.keys.any { civ.getResourceAmount(it) < 0 }
            if (!obsolete && !resourceDeficit) continue

            var score = unit.getAiPower() * baseUnit.cost + unit.promotions.numberOfPromotions + 1
            if (resourceDeficit) score /= 2
            if (score < bestScore) {
                bestScore = score
                best = unit
            }
        }
        return best?.let { it to bestScore }
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
        if (landDefenseState <= DefenseState.Neutral) land = findBestUnitToScrap(civ, isLand = true)
        if (navalDefenseState <= DefenseState.Neutral) naval = findBestUnitToScrap(civ, isLand = false)
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
     *  CvMilitaryAI::FindBestUnitToScrap (Brave New World) as CvPlayer::DoDeficit forces it: among military units of the domain the one
     *  with the lowest power (CvUnit::GetPower: level and health), null if none.
     */
    fun findBestUnitToScrap(civ: Civilization, isLand: Boolean): Pair<MapUnit, Int>? {
        var best: MapUnit? = null
        var bestScore = Int.MAX_VALUE
        for (unit in civ.units.getCivUnits()) {
            if (!unit.isMilitary()) continue
            val baseUnit = unit.baseUnit
            if (isLand && !baseUnit.isLandUnit || !isLand && !baseUnit.isWaterUnit) continue
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
    }
}
