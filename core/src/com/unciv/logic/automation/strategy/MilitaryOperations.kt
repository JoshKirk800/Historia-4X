package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.WarAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MultiUnitFormationDef
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.VictoryProgressAi

/**
 *  The operation side of CvMilitaryAI: UpdateOperations (kill and launch rules), the Request* functions, IsAttackReady,
 *  MilitaryAIHelpers::NumberOfFillableSlots / FirstSlotCityCanFill, GetUnitForArmy, GetLandReservesAvailable and MakeEmergencyPurchases.
 *
 *  UpdateOperations is Vox Populi's ([updateOperations]: its stops, and at war [doCityAttacks]' city attacks at its attack targets and
 *  pillage raids). Not launched yet: Vox Populi's defense operations (rapid response, city defense, naval superiority at a threatened
 *  city), which exist as operations ([AiOperationLogic.addOperation]) but whose move type is never set, so the tactical AI would not
 *  move them. The naval and nuke operations are [NavalOperationLogic]'s.
 *  MakeEmergencyPurchases itself only buys the last unit of operations; the defensive emergency purchases of the original are the tactical
 *  AI's (CvTacticalAI::PlotEmergencyPurchases).
 */
object MilitaryOperations {
    const val AT_WAR = "MILITARYAISTRATEGY_AT_WAR"

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
            if (bySea) "MUFORMATION_NAVAL_INVASION" else AiOperationLogic.formationName(civ, AiOperationType.BASIC_CITY_ATTACK)) ?: return null
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
        val naval = formationName == "MUFORMATION_NAVAL_INVASION" || formationName == "MUFORMATION_NAVAL_SQUADRON" ||
            formationName == "MUFORMATION_CITY_STATE_INVASION" || formationName == "MUFORMATION_PURE_NAVAL_CITY_ATTACK"
        val fillable = numberOfFillableSlots(civ, formation, naval)
        return fillable.filled >= fillable.required
    }
    //endregion

    //region Requests (CvMilitaryAI::Request*)

    private fun isOperationOk(operation: AiOperation) = operation.state != OperationState.ABORTED

    private fun requestUnits(civ: Civilization, bySea: Boolean) = civ.ai.military.requestArmy(bySea)

    private fun sneakType(bySea: Boolean) = if (bySea) AiOperationType.NAVAL_SNEAK_ATTACK else AiOperationType.SNEAK_CITY_ATTACK
    private fun sneakFormation(civ: Civilization, bySea: Boolean) =
        if (bySea) "MUFORMATION_NAVAL_INVASION" else AiOperationLogic.formationName(civ, AiOperationType.BASIC_CITY_ATTACK)

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

    /** CvMilitaryAI::RequestShowOfForce: a smaller strike force (or the naval sneak attack) to force concessions, started without checking the forces */
    fun requestShowOfForce(civ: Civilization, enemy: Civilization): Boolean {
        val (target, _) = MilitaryTargets.findBestAttackTarget(civ, AiOperationType.SNEAK_CITY_ATTACK, enemy) ?: return false
        val type = if (target.attackBySea) AiOperationType.NAVAL_SNEAK_ATTACK else AiOperationType.SMALL_CITY_ATTACK
        return isOperationOk(AiOperationLogic.addOperation(civ, type, enemy, target.targetCity, target.musterCity))
    }

    /**
     *  CvMilitaryAI::RequestCityStateAttack, with Vox Populi's targets ([MilitaryTargets.cityAttackTargets], only the preferred ones when
     *  [careful]); true whenever a target exists, as in the original, started or not
     */
    fun requestCityStateAttack(civ: Civilization, enemy: Civilization, careful: Boolean = false): Boolean {
        val target = MilitaryTargets.cityAttackTargets(civ, enemy, careful).firstOrNull() ?: return false
        val type = if (target.attackBySea) AiOperationType.CITY_STATE_NAVAL_ATTACK else AiOperationType.CITY_STATE_ATTACK
        val formation = if (target.attackBySea) "MUFORMATION_CITY_STATE_INVASION" else AiOperationLogic.formationName(civ, AiOperationType.CITY_STATE_ATTACK)
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
        val formationName = if (target.pureNaval) "MUFORMATION_PURE_NAVAL_CITY_ATTACK" else sneakFormation(civ, target.attackBySea)
        val formation = civ.ai.operations.formationDef(formationName) ?: return false
        val fillable = numberOfFillableSlots(civ, formation, target.attackBySea)
        if (fillable.required - fillable.filled > numUnitsWillingToBuild) return false
        if (fillable.landReservesUsed > 0 && fillable.landReservesUsed > landReservesAvailable(civ)) return false
        return isOperationOk(AiOperationLogic.addOperation(civ, attackType(target), target.targetCity.civ, target.targetCity, target.musterCity))
    }

    /**
     *  Vox Populi's CvMilitaryAI::RequestPillageAttack: a pillage raid on [enemy] while none runs, only with the units at hand. As
     *  addAIOperation with no units to build: the required slots must be fillable before the operation is created
     *  (PreconditionsAreMet), and one that still has units to build after its Init is ended at once (AI_ABORT_NO_UNITS).
     */
    fun requestPillageAttack(civ: Civilization, enemy: Civilization): Boolean {
        val operations = civ.ai.operations
        if (operations.firstOfType(AiOperationType.PILLAGE_ENEMY) != null) return false
        val formation = operations.formationDef(AiOperationLogic.formationName(civ, AiOperationType.PILLAGE_ENEMY)) ?: return false
        val fillable = numberOfFillableSlots(civ, formation, false)
        if (fillable.filled < fillable.required) return false
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.PILLAGE_ENEMY, enemy)
        if (isOperationOk(operation) && operation.slotsToBuild.isNotEmpty()) operations.kill(operation, OperationAbortReason.NO_UNITS)
        return isOperationOk(operation) && operation in operations.operations
    }

    /**
     *  Vox Populi's CvMilitaryAI::DoCityAttacks against [enemy], at war with us: when our units outside armies (land and naval) reach the
     *  recommended land army size, or no offensive operation runs, and our war state is troubled or better (against a major; any other
     *  enemy counts as offensive; VP's troubled is not one of ours: stalemate or better), a careful city attack willing to build two units
     *  ([requestBasicAttack]) and, against a major, a pillage raid ([requestPillageAttack]).
     */
    fun doCityAttacks(civ: Civilization, enemy: Civilization) {
        val military = civ.ai.military
        val reserves = military.numLandUnits + military.numNavalUnits - military.numLandUnitsInArmies - military.numNavalUnitsInArmies
        if (reserves < military.recommendedLandArmySize && civ.ai.operations.numOffensiveOperations() > 0) return
        val warState = if (enemy.isMajorCiv) WarStateAi.warState(civ, enemy) else WarState.Offensive
        if (warState == null || warState < WarState.Stalemate) return
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
     *  ([useNukeFlavorForDiplomacy]), if they are Powerful compared to us, the war goes badly for us (defensive or worse: VP's
     *  troubled state, between defensive and stalemate, is not one of ours), we see them as an enemy or worse, we go for or are close
     *  to world conquest, or they are close to a victory.
     *  BNW rolled only when the war was projected to end in destruction or its projection got worse, with two rolls; its AI held up to
     *  23 nukes in a game and set off a handful.
     */
    fun shouldLaunchNuke(civ: Civilization, other: Civilization): Boolean {
        if (civ.units.getCivUnits().none { it.isNuclearWeapon() } || !civ.isAtWarWith(other)) return false
        val strength = WarStateAi.militaryStrengthComparedToUs(civ, other)
        val warState = WarStateAi.warState(civ, other)
        // if we will surely lose this war anyway, we might as well nuke them!
        if (strength == StrengthLevel.Immense || warState == WarState.NearlyDefeated) return true
        if (!other.isMajorCiv) return false
        // if one of us has already nuked the other... well, keep the trend going!
        if (civ.ai.diplomacy.memoryOf(other).numTimesNuked > 0 || other.ai.diplomacy.memoryOf(civ).numTimesNuked > 0) return true
        val rules = civ.gameInfo.ruleset.ai
        val roll = strength == StrengthLevel.Powerful
            || (warState != null && warState <= WarState.Defensive)
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

    /** CvMilitaryAI::GetShowOfForceOperation */
    fun showOfForceOperation(civ: Civilization, enemy: Civilization) = operationAgainst(civ, AiOperationType.SMALL_CITY_ATTACK, enemy = enemy)

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
     *  ones (AI_ABORT_TARGET_NOT_VALID); at war, with cities of ours (GetThreatenedCities lists them all), [checkDefenses]' stops, then
     *  nukes ([shouldLaunchNuke]) and the attacks ([doCityAttacks]). BNW's barbarian camp operations, opportunity naval operations
     *  (superiority and bombardment) and its kill rules are gone: Vox Populi has none of them.
     *  Not yet: the defense operations CheckLandDefenses and CheckSeaDefenses start (rapid response, city defense, naval superiority),
     *  and the carrier group (no such operation).
     */
    fun updateOperations(civ: Civilization) {
        if (!civ.isMajorCiv) return
        val operations = civ.ai.operations
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !(other.isBarbarian || WarStateAi.isPlayerValid(civ, other))) continue
            // If we've made peace, abort all operations
            if (forcedPeace(civ, other)) for (offensive in listOf(true, false)) for (naval in listOf(false, true))
                operations.stopAll(offensive, naval, other, OperationAbortReason.WAR_STATE_CHANGE)
            // If we cannot declare war on this player, abort all offensive operations related to him
            if (cannotAttack(civ, other)) for (naval in listOf(false, true))
                operations.stopAll(offensive = true, naval = naval, enemy = other, reason = OperationAbortReason.TARGET_NOT_VALID)
            if (!civ.isAtWarWith(other)) continue
            if (civ.cities.isNotEmpty()) checkDefenses(civ, other)
            // finally offense
            if (shouldLaunchNuke(civ, other)) requestNukeAttack(civ, other)
            doCityAttacks(civ, other)
        }
    }

    /**
     *  The stops of Vox Populi's CheckLandDefenses and CheckSeaDefenses for [enemy] at war with us, by our war state against them (a
     *  non-major counts as offensive): stalemate or better, none (VP's troubled, which we lack, none either); defensive, our offensive
     *  operations against them; nearly defeated, our offensive operations against anyone - by land and by sea alike.
     */
    private fun checkDefenses(civ: Civilization, enemy: Civilization) {
        val warState = (if (enemy.isMajorCiv) WarStateAi.warState(civ, enemy) else WarState.Offensive) ?: return
        val against = when (warState) {
            WarState.Defensive -> enemy
            WarState.NearlyDefeated -> null
            else -> return
        }
        for (naval in listOf(false, true)) civ.ai.operations.stopAll(offensive = true, naval = naval, enemy = against, reason = OperationAbortReason.WAR_STATE_CHANGE)
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
