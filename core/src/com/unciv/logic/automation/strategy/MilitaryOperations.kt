package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.WarAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MultiUnitFormationDef
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.unit.tactical.CityThreat

/**
 *  The operation side of CvMilitaryAI: UpdateOperations (kill and launch rules), SetupInstantDefenses, CheckLandDefenses, CheckSeaDefenses,
 *  the Request* functions, IsAttackReady, MilitaryAIHelpers::NumberOfFillableSlots / FirstSlotCityCanFill, GetUnitForArmy,
 *  GetLandReservesAvailable and MakeEmergencyPurchases.
 *
 *  UpdateOperations is Vox Populi's ([updateOperations]: its stops, the defense operations of [checkLandDefenses] and [checkSeaDefenses]
 *  for the two most threatened cities ([CityThreat.threatenedCities]), and at war [doCityAttacks]' city attacks at its attack targets and
 *  pillage raids). The defense operations (rapid response, city defense, naval superiority at a threatened coastal city) are
 *  [AiOperationLogic]'s and [NavalOperationLogic]'s, and their armies are moved by the tactical AI as the attacks' are. Not ported: the
 *  carrier group (no such operation), and the city defense that Vox Populi's city-state quests (horde, rebellion) start (Unciv's quests
 *  have no such). The naval and nuke operations are [NavalOperationLogic]'s.
 *  The show of force and the pillage raid are Vox Populi's RequestBullyingOperation and RequestPillageAttack ([requestShowOfForce],
 *  [requestPillageAttack]): BNW's show of force by its target scorer is gone.
 *  MakeEmergencyPurchases itself only buys the last unit of operations; the defensive emergency purchases of the original are the tactical
 *  AI's (CvTacticalAI::PlotEmergencyPurchases).
 */
object MilitaryOperations {
    const val AT_WAR = "MILITARYAISTRATEGY_AT_WAR"

    /** CvMilitaryAI::UpdateOperations defends the most threatened cities and the most threatened coastal cities of the list: this many of each */
    private const val DEFENDED_CITIES = 2

    /** CvMilitaryAI::CheckLandDefenses looks for the enemy's units this many plots from the threatened city for the rapid response */
    private const val RAPID_RESPONSE_SEARCH_RANGE = 8

    /** CvMilitaryAI::RequestBullyingOperation does not go for a target further than this many steps from our nearest city */
    private const val BULLYING_MAX_DISTANCE = 23

    //region Slot counting (MilitaryAIHelpers)

    /** The result of [numberOfFillableSlots] */
    class Fillable(val filled: Int, val required: Int, val landReservesUsed: Int)

    /**
     *  MilitaryAIHelpers::NumberOfFillableSlots: how many slots of [formation] our free units (not exploring, healthy, not in an army, not
     *  deployed from an operation lately, able to go by sea if [requiresNavalMoves]) could fill, how many slots are required in all
     *  (filled ones plus the required slots still empty) and how many land units that takes.
     */
    fun numberOfFillableSlots(civ: Civilization, formation: MultiUnitFormationDef, requiresNavalMoves: Boolean): Fillable {
        val rules = civ.gameInfo.ruleset.ai
        val slots = ArrayList(formation.slots)
        var filled = 0
        var landUsed = 0
        for (unit in civ.units.getCivUnits()) {
            if (slots.isEmpty()) break
            if (!AiOperationLogic.isFreeForOperation(civ, unit, requiresNavalMoves)) continue
            val index = slots.indexOfFirst { slotFitsUnit(rules, it.primaryUnitAi, it.secondaryUnitAi, unit.baseUnit) }
            if (index < 0) continue
            slots.removeAt(index)
            filled++
            if (unit.baseUnit.isLandUnit) landUsed++
        }
        return Fillable(filled, filled + slots.count { it.required }, landUsed)
    }

    private fun slotFitsUnit(rules: com.unciv.models.ruleset.ai.AiRuleset, primary: String, secondary: String, unit: com.unciv.models.ruleset.unit.BaseUnit): Boolean =
        listOf(primary, secondary).any { name -> UnitAiType.ofXmlName(name)?.let { UnitAiTypes.canHave(unit, it, rules) } == true }

    /**
     *  MilitaryAIHelpers::FirstSlotCityCanFill: after our free units have taken the slots of [formation] they fit, the unit AI of the first
     *  required slot still empty (its secondary one if [secondary]); in a coastal city the first such slot with a sea unit AI. Null if every
     *  required slot is filled.
     */
    fun firstSlotCityCanFill(civ: Civilization, formation: MultiUnitFormationDef, bySea: Boolean, coastal: Boolean, secondary: Boolean): UnitAiType? {
        val rules = civ.gameInfo.ruleset.ai
        val slots = ArrayList(formation.slots)
        for (unit in civ.units.getCivUnits()) {
            if (slots.isEmpty()) break
            if (!AiOperationLogic.isFreeForOperation(civ, unit, bySea)) continue
            val index = slots.indexOfFirst { slotFitsUnit(rules, it.primaryUnitAi, it.secondaryUnitAi, unit.baseUnit) }
            if (index >= 0) slots.removeAt(index)
        }
        fun typeOf(slot: com.unciv.models.ruleset.ai.FormationSlotDef) = UnitAiType.ofXmlName(if (secondary) slot.secondaryUnitAi else slot.primaryUnitAi)
        if (coastal) {
            val seaSlot = slots.firstOrNull { it.required && UnitAiType.ofXmlName(it.primaryUnitAi) in SEA_ARMY_UNIT_AIS }
            if (seaSlot != null) return typeOf(seaSlot)
        }
        return slots.firstOrNull { it.required }?.let { typeOf(it) }
    }

    private val SEA_ARMY_UNIT_AIS = setOf(UnitAiType.ASSAULT_SEA, UnitAiType.ATTACK_SEA, UnitAiType.RESERVE_SEA, UnitAiType.ESCORT_SEA)

    /**
     *  CvMilitaryAI::GetUnitForArmy: while an army is being built ([MilitaryAi.armyTypeBeingBuilt]), the unit [city] would train for it -
     *  the best unit for the first empty required slot of the city attack formation (the naval invasion one for a sea army), else for the
     *  secondary unit AI of that slot. Null if no army is being built, the formation is full, or the city cannot train such a unit.
     */
    fun unitForArmy(city: City): com.unciv.models.ruleset.unit.BaseUnit? {
        val civ = city.civ
        val type = civ.ai.military.armyTypeBeingBuilt ?: return null
        val bySea = type == ArmyType.NavalInvasion
        val formation = civ.ai.operations.formationDef(
            AiOperationLogic.formationName(civ, if (bySea) AiOperationType.NAVAL_ATTACK else AiOperationType.BASIC_CITY_ATTACK)) ?: return null
        for (secondary in listOf(false, true)) {
            val unitAi = firstSlotCityCanFill(civ, formation, bySea, city.isCoastal(), secondary) ?: continue
            ProductionAi.recommendUnit(city, unitAi)?.let { return it }
        }
        return null
    }

    /** CvMilitaryAI::GetLandReservesAvailable: land units that no army holds and the mandatory reserve does not need */
    fun landReservesAvailable(civ: Civilization): Int {
        val military = civ.ai.military
        return military.numLandUnits - military.numLandUnitsInArmies - military.mandatoryReserveSize
    }

    /**
     *  CvMilitaryAI::IsAttackReady: the forces for an attack of [formationName] are at hand - unless an operation of [type] is already
     *  running and past recruiting.
     */
    fun isAttackReady(civ: Civilization, formationName: String, type: AiOperationType): Boolean {
        val running = civ.ai.operations.firstOfType(type)
        if (running != null && running.state != OperationState.RECRUITING_UNITS) return false
        val formation = civ.ai.operations.formationDef(formationName) ?: return false
        val naval = type.mixedLandNaval || type.allNaval
        val fillable = numberOfFillableSlots(civ, formation, naval)
        return fillable.filled >= fillable.required
    }
    //endregion

    //region Requests (CvMilitaryAI::Request*)

    private fun isOperationOk(operation: AiOperation) = operation.state != OperationState.ABORTED

    private fun requestUnits(civ: Civilization, bySea: Boolean) = civ.ai.military.requestArmy(bySea)

    private fun sneakType(bySea: Boolean) = if (bySea) AiOperationType.NAVAL_SNEAK_ATTACK else AiOperationType.SNEAK_CITY_ATTACK
    private fun sneakFormation(civ: Civilization, bySea: Boolean) =
        AiOperationLogic.formationName(civ, if (bySea) AiOperationType.NAVAL_SNEAK_ATTACK else AiOperationType.BASIC_CITY_ATTACK)

    /**
     *  CvMilitaryAI::RequestSneakAttack, with Vox Populi's targets (RequestCityAttack: [MilitaryTargets.cityAttackTargets], only the
     *  preferred ones when [careful]): one sneak attack at a time; the first target whose army (by land or by sea) is at hand starts it,
     *  else cities are asked for units for the best target's army
     */
    fun requestSneakAttack(civ: Civilization, enemy: Civilization, careful: Boolean = false): Boolean {
        val operations = civ.ai.operations
        if (operations.firstOfType(AiOperationType.NAVAL_SNEAK_ATTACK) != null || operations.firstOfType(AiOperationType.SNEAK_CITY_ATTACK) != null) return false
        val targets = MilitaryTargets.cityAttackTargets(civ, enemy, careful)
        if (targets.isEmpty()) return false
        for (target in targets) {
            val type = sneakType(target.attackBySea)
            if (!isAttackReady(civ, sneakFormation(civ, target.attackBySea), type)) continue
            val operation = AiOperationLogic.addOperation(civ, type, enemy, target.targetCity, target.musterCity)
            if (isOperationOk(operation)) return true
        }
        requestUnits(civ, targets.first().attackBySea)
        return false
    }

    /**
     *  Vox Populi's CvMilitaryAI::RequestBullyingOperation (Release-5.4.8), BNW's RequestShowOfForce: an army sent to force concessions
     *  from [enemy] (a demand on a major, the bullying of a city-state), with the units at hand only. Not at a barbarian, and not while an
     *  offensive operation against [enemy] runs. The target is the city of a major that is nearest to a city of ours by plots
     *  ([MilitaryTargets.closestCityToUsByPlots]), a city-state's capital; no further than [BULLYING_MAX_DISTANCE] steps from our nearest city
     *  by path ([MilitaryTargets.closestCityByPathLength], which is the muster city). The operation is the ordinary city attack: by land
     *  ([AiOperationType.BASIC_CITY_ATTACK]) when the muster city and the target share a landmass ([MilitaryTargets.hasSharedLandArea]),
     *  else by sea with ships alone ([AiOperationType.PURE_NAVAL_CITY_ATTACK], Vox Populi's CITY_ATTACK_NAVAL), and made only if no unit
     *  is left to build ([addLimitedOperation]). BNW's small city attack and naval sneak attack, picked by its target scorer, are gone.
     */
    fun requestShowOfForce(civ: Civilization, enemy: Civilization): Boolean {
        if (enemy.isBarbarian) return false
        // If we already have an operation on the way, don't send another one
        if (civ.ai.operations.hasAnyOffensiveOperationsAgainst(enemy)) return false
        val targetCity = (if (enemy.isMajorCiv) MilitaryTargets.closestCityToUsByPlots(civ, enemy) else enemy.getCapital(firstCityIfNoCapital = false))
            ?: return false
        // don't venture too far
        val (musterCity, distance) = MilitaryTargets.closestCityByPathLength(civ, enemy, targetCity.getCenterTile(), allowSea = true) ?: return false
        if (distance > BULLYING_MAX_DISTANCE) return false
        // if the target is very close assume we can embark or don't even need to
        val type = if (MilitaryTargets.hasSharedLandArea(musterCity, targetCity)) AiOperationType.BASIC_CITY_ATTACK else AiOperationType.PURE_NAVAL_CITY_ATTACK
        // don't try to build additional units, only do this if we have enough at hand
        return addLimitedOperation(civ, type, 0, enemy, targetCity, musterCity) != null
    }

    /**
     *  CvMilitaryAI::RequestCityStateAttack, with Vox Populi's targets ([MilitaryTargets.cityAttackTargets], only the preferred ones when
     *  [careful]); true whenever a target exists, as in the original, started or not
     */
    fun requestCityStateAttack(civ: Civilization, enemy: Civilization, careful: Boolean = false): Boolean {
        val target = MilitaryTargets.cityAttackTargets(civ, enemy, careful).firstOrNull() ?: return false
        val type = if (target.attackBySea) AiOperationType.CITY_STATE_NAVAL_ATTACK else AiOperationType.CITY_STATE_ATTACK
        val formation = AiOperationLogic.formationName(civ, type)
        if (isAttackReady(civ, formation, type)) AiOperationLogic.addOperation(civ, type, enemy, target.targetCity, target.musterCity)
        else requestUnits(civ, target.attackBySea)
        return true
    }

    /**
     *  CvMilitaryAI::RequestCityAttack (Vox Populi), our basic attack: the first of Vox Populi's targets
     *  ([MilitaryTargets.cityAttackTargets], only the preferred ones when [careful]) whose army can be formed but for
     *  [numUnitsWillingBuild] required slots ([requestSpecificAttack]). When careful, a target already attacked waits until the army of
     *  that attack has left our territory. Careless, Vox Populi uses the units at hand only; our careless callers (BNW's coop war and
     *  demands) keep their own numbers.
     */
    fun requestBasicAttack(civ: Civilization, enemy: Civilization, numUnitsWillingBuild: Int, careful: Boolean = false): Boolean =
        MilitaryTargets.cityAttackTargets(civ, enemy, careful).any { target ->
            !(careful && previousArmyStillHome(civ, target)) && requestSpecificAttack(civ, target, numUnitsWillingBuild)
        }

    /** RequestCityAttack's "don't duplicate operations": an attack of the same type on the target whose army is still in our territory */
    private fun previousArmyStillHome(civ: Civilization, target: MilitaryTarget): Boolean {
        val operation = civ.ai.operations.firstOfType(attackType(target), target.targetCity.civ, target.targetCity.getCenterTile()) ?: return false
        val army = operation.firstArmy ?: return false
        val center = AiOperationLogic.centerOfMass(civ, army, target.attackBySea) ?: return true
        return center.getOwner() == civ
    }

    /** The operation of an attack on [target]: by land the basic city attack, by sea the naval invasion, with ships alone the pure naval attack */
    private fun attackType(target: MilitaryTarget) = when {
        target.pureNaval -> AiOperationType.PURE_NAVAL_CITY_ATTACK
        target.attackBySea -> AiOperationType.NAVAL_ATTACK
        else -> AiOperationType.BASIC_CITY_ATTACK
    }

    /**
     *  The attack on one target of Vox Populi's RequestCityAttack (addAIOperation with its PreconditionsAreMet): the attack
     *  ([attackType]) on [target] if the forces are at hand but for [numUnitsWillingToBuild] required slots and - our BNW reserve - the
     *  land units they use fit into the reserves ([landReservesAvailable]).
     */
    fun requestSpecificAttack(civ: Civilization, target: MilitaryTarget, numUnitsWillingToBuild: Int): Boolean {
        val formationName = AiOperationLogic.formationName(civ, attackType(target))
        val formation = civ.ai.operations.formationDef(formationName) ?: return false
        val fillable = numberOfFillableSlots(civ, formation, target.attackBySea)
        if (fillable.required - fillable.filled > numUnitsWillingToBuild) return false
        if (fillable.landReservesUsed > 0 && fillable.landReservesUsed > landReservesAvailable(civ)) return false
        return isOperationOk(AiOperationLogic.addOperation(civ, attackType(target), target.targetCity.civ, target.targetCity, target.musterCity))
    }

    /**
     *  Vox Populi's CvMilitaryAI::RequestPillageAttack: a pillage raid on [enemy] while none runs, only with the units at hand
     *  ([addLimitedOperation], no unit may be left to build; its target and muster city are the pillage operation's own
     *  ([AiOperationLogic.findPillageTarget]), so one without a target is not made).
     */
    fun requestPillageAttack(civ: Civilization, enemy: Civilization): Boolean {
        // don't try to build additional units, only do this if we have enough at hand
        if (civ.ai.operations.firstOfType(AiOperationType.PILLAGE_ENEMY) != null) return false
        return addLimitedOperation(civ, AiOperationType.PILLAGE_ENEMY, 0, enemy) != null
    }

    /**
     *  Vox Populi's CvMilitaryAI::DoCityAttacks against [enemy], at war with us: when our units outside armies (land and naval) reach the
     *  recommended land army size, or no offensive operation runs, and our war state is troubled or better (against a major; any other
     *  enemy counts as offensive), a careful city attack willing to build two units
     *  ([requestBasicAttack]) and, against a major, a pillage raid ([requestPillageAttack]).
     */
    fun doCityAttacks(civ: Civilization, enemy: Civilization) {
        val military = civ.ai.military
        val reserves = military.numLandUnits + military.numNavalUnits - military.numLandUnitsInArmies - military.numNavalUnitsInArmies
        if (reserves < military.recommendedLandArmySize && civ.ai.operations.numOffensiveOperations() > 0) return
        val warState = if (enemy.isMajorCiv) WarStateAi.warState(civ, enemy) else WarState.Offensive
        if (warState == null || warState < WarState.Troubled) return
        requestBasicAttack(civ, enemy, 2, careful = true)
        if (enemy.isMajorCiv) requestPillageAttack(civ, enemy)
    }

    /** CvMilitaryAI::RequestNukeAttack: with a nuke at hand, one that goes at [enemy]'s best city in its range */
    fun requestNukeAttack(civ: Civilization, enemy: Civilization): Boolean {
        if (civ.units.getCivUnits().none { it.isNuclearWeapon() }) return false
        return isOperationOk(AiOperationLogic.addOperation(civ, AiOperationType.NUKE_ATTACK, enemy))
    }

    /**
     *  Vox Populi's CvMilitaryAI::DoNuke (Release-5.4.8; D7, in place of BNW's trigger in UpdateOperations), for an enemy at war with us
     *  while we hold nukes. Launch when we will surely lose anyway (they are Immense compared to us, or our war state is nearly defeated),
     *  or when either of us has nuked the other. Else roll every turn, a launch when 0..9 is at most our use-nuke flavor
     *  ([useNukeFlavorForDiplomacy]), if they are Powerful compared to us, the war goes badly for us (troubled or worse),
     *  we see them as an enemy or worse, we go for or are close
     *  to world conquest, or they are close to a victory.
     *  BNW rolled only when the war was projected to end in destruction or its projection got worse, with two rolls; its AI held up to
     *  23 nukes in a game and set off a handful.
     */
    fun shouldLaunchNuke(civ: Civilization, other: Civilization): Boolean {
        if (civ.units.getCivUnits().none { it.isNuclearWeapon() } || !civ.isAtWarWith(other)) return false
        val strength = WarStateAi.rawMilitaryStrengthComparedToUs(civ, other)
        val warState = WarStateAi.warState(civ, other)
        // if we will surely lose this war anyway, we might as well nuke them!
        if (strength == StrengthLevel.Immense || warState == WarState.NearlyDefeated) return true
        if (!other.isMajorCiv) return false
        // if one of us has already nuked the other... well, keep the trend going!
        if (civ.ai.diplomacy.memoryOf(other).numTimesNuked > 0 || other.ai.diplomacy.memoryOf(civ).numTimesNuked > 0) return true
        val rules = civ.gameInfo.ruleset.ai
        val roll = strength == StrengthLevel.Powerful
            || (warState != null && warState <= WarState.Troubled)
            || civ.ai.diplomacy.opinionOf(other) <= MajorCivOpinion.Enemy
            || civ.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules)
            || VictoryProgressAi.isCloseToAnyVictoryCondition(other, rules)
        if (!roll) return false
        return civ.ai.random("Nuke" + other.civID).nextInt(10) <= useNukeFlavorForDiplomacy(civ)
    }

    /**
     *  CvFlavorManager::GetPersonalityFlavorForDiplomacy for FLAVOR_USE_NUKE: the personality flavor on the 1..10 scale of the diplomacy AI
     *  (a zeroed flavor counts as 1). VP's personality flavors run from 1 to 10 and are returned as they are; ours keep BNW's range of 0..20
     *  (randomized around the leaders' 0..10 values), so they are kept within 1..10 instead of VP's rescaling of a wider range.
     */
    fun useNukeFlavorForDiplomacy(civ: Civilization): Int = civ.ai.personality(Flavor.UseNuke).coerceIn(1, 10)

    private fun operationAgainst(civ: Civilization, vararg types: AiOperationType, enemy: Civilization): AiOperation? =
        types.firstNotNullOfOrNull { civ.ai.operations.firstOfType(it, enemy) }

    /** CvMilitaryAI::GetSneakAttackOperation */
    fun sneakAttackOperation(civ: Civilization, enemy: Civilization) =
        operationAgainst(civ, AiOperationType.SNEAK_CITY_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK, enemy = enemy)

    /** CvMilitaryAI::GetCityStateAttackOperation */
    fun cityStateAttackOperation(civ: Civilization, enemy: Civilization) =
        operationAgainst(civ, AiOperationType.CITY_STATE_ATTACK, AiOperationType.CITY_STATE_NAVAL_ATTACK, enemy = enemy)
    //endregion

    //region UpdateOperations

    /** CvTeam::isForcePeace: a peace treaty still running */
    private fun forcedPeace(civ: Civilization, other: Civilization) = (civ.getDiplomacyManager(other)?.turnsToPeaceTreaty() ?: 0) > 0

    /** The original's "can't declare war": peace we cannot declare war out of */
    private fun cannotAttack(civ: Civilization, other: Civilization) =
        !civ.isAtWarWith(other) && civ.getDiplomacyManager(other)?.canDeclareWar() != true

    /**
     *  Vox Populi's CvMilitaryAI::UpdateOperations (CvMilitaryAI.cpp 2401), for majors only, per player (CvMilitaryAI::IsPlayerValid: the
     *  barbarians too): a peace treaty ends all our operations against them; at peace without the right to declare war, the offensive
     *  ones (AI_ABORT_TARGET_NOT_VALID); at war, with no threatened (coastal) city to defend the defensive operations against them (every
     *  city of ours is listed by [CityThreat.threatenedCities], so only with no cities), [checkLandDefenses] and [checkSeaDefenses] for
     *  the two most threatened cities (coastal cities), then nukes ([shouldLaunchNuke]) and the attacks ([doCityAttacks]). BNW's barbarian
     *  camp operations, opportunity naval operations (superiority and bombardment) and its kill rules are gone: Vox Populi has none of them.
     *  Not here: the carrier group (no such operation).
     */
    fun updateOperations(civ: Civilization) {
        if (!civ.isMajorCiv) return
        val operations = civ.ai.operations
        val threatened by lazy { CityThreat.threatenedCities(civ) }
        val coastal by lazy { CityThreat.threatenedCities(civ, coastalOnly = true) }
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !(other.isBarbarian || WarStateAi.isPlayerValid(civ, other))) continue
            // If we've made peace, abort all operations
            if (forcedPeace(civ, other)) for (offensive in listOf(true, false)) for (naval in listOf(false, true))
                operations.stopAll(offensive, naval, other, OperationAbortReason.WAR_STATE_CHANGE)
            // If we cannot declare war on this player, abort all offensive operations related to him
            if (cannotAttack(civ, other)) for (naval in listOf(false, true))
                operations.stopAll(offensive = true, naval = naval, enemy = other, reason = OperationAbortReason.TARGET_NOT_VALID)
            if (!civ.isAtWarWith(other)) continue
            // Defense check.
            if (coastal.isEmpty()) operations.stopAll(offensive = false, naval = true, enemy = other, reason = OperationAbortReason.WAR_STATE_CHANGE)
            if (threatened.isEmpty()) operations.stopAll(offensive = false, naval = false, enemy = other, reason = OperationAbortReason.WAR_STATE_CHANGE)
            for (city in threatened.take(DEFENDED_CITIES)) checkLandDefenses(civ, other, city)
            for (city in coastal.take(DEFENDED_CITIES)) checkSeaDefenses(civ, other, city)
            // finally offense
            if (shouldLaunchNuke(civ, other)) requestNukeAttack(civ, other)
            doCityAttacks(civ, other)
        }
    }

    /**
     *  The stops of Vox Populi's CheckLandDefenses and CheckSeaDefenses for [enemy] at war with us, by our war state against them (a
     *  non-major counts as offensive; none yet is worse than any): stalemate or better, nothing more is done; defensive, our offensive
     *  operations by land (sea) against them; nearly defeated, our offensive operations by land (sea) against anyone. A troubled war
     *  (between defensive and stalemate) stops nothing, but the defense is arranged.
     *  @return whether the defense is to be arranged
     */
    private fun stopOffensesWhenLosing(civ: Civilization, enemy: Civilization, naval: Boolean): Boolean {
        val warState = if (enemy.isMajorCiv) WarStateAi.warState(civ, enemy) else WarState.Offensive
        // If we're winning, nothing to do
        if (warState != null && warState >= WarState.Stalemate) return false
        when (warState) {
            // If we are losing, concentrate on defense against this player
            WarState.Defensive -> civ.ai.operations.stopAll(offensive = true, naval = naval, enemy = enemy, reason = OperationAbortReason.WAR_STATE_CHANGE)
            // If we are really losing, let's pull back everywhere
            WarState.NearlyDefeated -> civ.ai.operations.stopAll(offensive = true, naval = naval, enemy = null, reason = OperationAbortReason.WAR_STATE_CHANGE)
            else -> Unit
        }
        return true
    }

    /**
     *  CvMilitaryAI::CheckLandDefenses: abort or start operations as appropriate given the current threats and war states. When the war is
     *  not going well ([stopOffensesWhenLosing]): a rapid response with at most one unit to build, at the city that owns the plot where
     *  [enemy]'s units threaten the [threatened] city most ([AiOperationLogic.findEnemiesNearHomelandPlot], within 8 plots of it), unless
     *  one runs near the city; and, when the city lies in a zone the enemy dominates, a city defense of it with at most two units to build,
     *  unless one runs there.
     */
    private fun checkLandDefenses(civ: Civilization, enemy: Civilization, threatened: City) {
        if (!stopOffensesWhenLosing(civ, enemy, naval = false)) return
        val operations = civ.ai.operations
        val tile = threatened.getCenterTile()
        // first a quick one if necessary
        val hasOperationUnderway = operations.firstOfType(AiOperationType.RAPID_RESPONSE, target = tile) != null
        val startCity = AiOperationLogic.findEnemiesNearHomelandPlot(civ, enemy, tile, RAPID_RESPONSE_SEARCH_RANGE)?.getCity()
        if (!hasOperationUnderway && startCity != null) addLimitedOperation(civ, AiOperationType.RAPID_RESPONSE, 1, enemy, startCity)
        // this may take a bit longer to arrange
        if (civ.ai.tactical.map.isInEnemyDominatedZone(tile) && operations.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE, target = tile) == null)
            addLimitedOperation(civ, AiOperationType.CITY_CLOSE_DEFENSE, 2, enemy, threatened)
    }

    /**
     *  CvMilitaryAI::CheckSeaDefenses: when the war is not going well ([stopOffensesWhenLosing]), a naval superiority squadron with at
     *  most one ship to build for the [threatened] coastal city when it has no water to sail to, none runs near it, or it lies in a zone the
     *  enemy dominates.
     */
    private fun checkSeaDefenses(civ: Civilization, enemy: Civilization, threatened: City) {
        if (!stopOffensesWhenLosing(civ, enemy, naval = true)) return
        if (!threatened.isCoastal()) return
        val tile = threatened.getCenterTile()
        if (NavalOperationLogic.coastalWaterNearPlot(civ, tile) == null) return
        val isEnemyZone = civ.ai.tactical.map.isInEnemyDominatedZone(tile)
        val hasOperationUnderway = civ.ai.operations.firstOfType(AiOperationType.NAVAL_SUPERIORITY, target = tile) != null
        if (!hasOperationUnderway || isEnemyZone) addLimitedOperation(civ, AiOperationType.NAVAL_SUPERIORITY, 1, enemy, threatened)
    }

    /**
     *  CvMilitaryAI::SetupInstantDefenses, which CvTeam::declareWar asks of both players of a new war between majors that are not human
     *  (the original also runs it for a human whose units are the AI's): at once a rapid response to the most threatened city and a naval
     *  superiority squadron at the most threatened coastal city, with no unit to build, unless one runs near the city. The tactical map is
     *  forgotten, as it was made in the middle of another player's turn.
     */
    fun setupInstantDefenses(civ: Civilization, enemy: Civilization) {
        if (!civ.isMajorCiv || !enemy.isMajorCiv || !civ.isAI()) return
        val operations = civ.ai.operations
        // land response
        val threatened = CityThreat.threatenedCities(civ).firstOrNull()
        if (threatened != null && operations.firstOfType(AiOperationType.RAPID_RESPONSE, target = threatened.getCenterTile()) == null)
            addLimitedOperation(civ, AiOperationType.RAPID_RESPONSE, 0, enemy, threatened)
        // naval response
        val coastal = CityThreat.threatenedCities(civ, coastalOnly = true).firstOrNull()
        if (coastal != null && operations.firstOfType(AiOperationType.NAVAL_SUPERIORITY, target = coastal.getCenterTile()) == null)
            addLimitedOperation(civ, AiOperationType.NAVAL_SUPERIORITY, 0, enemy, coastal)
        civ.ai.tactical.invalidateMap()
    }

    /**
     *  CvPlayer::addAIOperation for an operation against [enemy] at [target] from [muster] (each may be left to the operation to find): it
     *  is made only if the units at hand would do but for [maxMissingUnits] required ones ([AiOperationLogic.preconditionsAreMet]), and
     *  is deleted at once ([AiOperationLogic.addOperation]) when its initialization fails or leaves more than that many units to build.
     *  @return the operation, null if none was made
     */
    private fun addLimitedOperation(
        civ: Civilization, type: AiOperationType, maxMissingUnits: Int, enemy: Civilization, target: City? = null, muster: City? = null
    ): AiOperation? {
        if (!AiOperationLogic.preconditionsAreMet(civ, type, target?.getCenterTile(), muster?.getCenterTile(), maxMissingUnits, enemy)) return null
        return AiOperationLogic.addOperation(civ, type, enemy, target, muster, maxMissingUnits).takeIf { it.state != OperationState.ABORTED }
    }
    //endregion

    /**
     *  CvMilitaryAI::MakeEmergencyPurchases: when not at war, or winning all wars, the operation waiting for one more unit gets it bought in
     *  its start city ([AiOperationLogic.buyFinalUnit]); one purchase a turn.
     */
    fun makeEmergencyPurchases(civ: Civilization) {
        if (civ.ai.isMilitaryStrategyActive(AT_WAR) && WarStateAi.stateAllWars(civ) != StateAllWars.Winning) return
        for (operation in civ.ai.operations.operations.toList())
            if (operation.hasOneMoreSlotToFill() && AiOperationLogic.buyFinalUnit(civ, operation)) return
    }
}
