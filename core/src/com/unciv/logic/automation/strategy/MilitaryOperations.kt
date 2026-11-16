package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.diplomacy.WarAi
import com.unciv.logic.city.City
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MultiUnitFormationDef
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.VictoryProgressAi

/**
 *  The operation side of CvMilitaryAI: UpdateOperations (kill and launch rules), the Request* functions, IsAttackReady,
 *  MilitaryAIHelpers::NumberOfFillableSlots / FirstSlotCityCanFill, GetUnitForArmy, GetLandReservesAvailable and MakeEmergencyPurchases.
 *
 *  The launches at war are Vox Populi's ([doCityAttacks]: city attacks at its attack targets and pillage raids); the rest of
 *  UpdateOperations is still BNW's: barbarian camps (eradicate strategy), the opportunity naval operations. Not launched, because BNW
 *  has them commented out: city close defense and rapid response (their launch is commented out in UpdateOperations, and their move type
 *  is never set, so the tactical AI would not move them); they exist as operations ([AiOperationLogic.addOperation]) with their kill
 *  rules. The naval and nuke operations are [NavalOperationLogic]'s.
 *  MakeEmergencyPurchases itself only buys the last unit of operations; the defensive emergency purchases of the original are the tactical
 *  AI's (CvTacticalAI::PlotEmergencyPurchases).
 */
object MilitaryOperations {
    const val ERADICATE_BARBARIANS = "MILITARYAISTRATEGY_ERADICATE_BARBARIANS"
    const val AT_WAR = "MILITARYAISTRATEGY_AT_WAR"
    const val EMPIRE_DEFENSE_CRITICAL = "MILITARYAISTRATEGY_EMPIRE_DEFENSE_CRITICAL"

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
     *  Vox Populi's CvMilitaryAI::RequestPillageAttack: a pillage raid on [enemy] while none runs, only with the units at hand (an
     *  operation that would have units to build aborts, AI_ABORT_NO_UNITS)
     */
    fun requestPillageAttack(civ: Civilization, enemy: Civilization): Boolean {
        if (civ.ai.operations.firstOfType(AiOperationType.PILLAGE_ENEMY) != null) return false
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.PILLAGE_ENEMY, enemy)
        if (isOperationOk(operation) && operation.slotsToBuild.isNotEmpty()) AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_UNITS)
        return isOperationOk(operation)
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

    /**
     *  The end of UpdateOperations: naval operations with no enemy in mind, their total (superiority plus bombardment) held to FLAVOR_NAVAL / 2
     *  (twice that on a naval map). Naval superiority when a squadron's required slots are filled by ships at hand and a quick colonization
     *  is under way or we are at war; naval bombardment when the barbarians are to be eradicated and none runs.
     */
    fun launchOpportunityNavalOperations(civ: Civilization) {
        val operations = civ.ai.operations
        var maxOperations = civ.ai.personality(Flavor.Naval) / 2
        if (SettleAreas.aiMapHint(civ.gameInfo) and 1 != 0) maxOperations *= 2
        val superiority = operations.numOperationsOfType(AiOperationType.NAVAL_SUPERIORITY)
        val bombardment = operations.numOperationsOfType(AiOperationType.NAVAL_BOMBARDMENT)
        if (superiority + bombardment > maxOperations) return
        val squadron = operations.formationDef("MUFORMATION_NAVAL_SQUADRON")
        if (squadron != null) {
            val fillable = numberOfFillableSlots(civ, squadron, true)
            // Not willing to build units to get this off the ground
            if (fillable.filled >= fillable.required &&
                (operations.firstOfType(AiOperationType.QUICK_COLONIZE) != null || civ.ai.isMilitaryStrategyActive(AT_WAR))) {
                AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_SUPERIORITY)
                return
            }
        }
        val bombard = operations.formationDef("MUFORMATION_NAVAL_BOMBARDMENT") ?: return
        val fillable = numberOfFillableSlots(civ, bombard, true)
        // If fighting off barbarians, start naval bombardment, but only if don't have one
        if (fillable.filled >= fillable.required && bombardment == 0 && civ.ai.isMilitaryStrategyActive(ERADICATE_BARBARIANS))
            AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_BOMBARDMENT)
    }

    private fun operationAgainst(civ: Civilization, vararg types: AiOperationType, enemy: Civilization): AiOperation? =
        types.firstNotNullOfOrNull { civ.ai.operations.firstOfType(it, enemy) }

    /** CvMilitaryAI::GetSneakAttackOperation */
    fun sneakAttackOperation(civ: Civilization, enemy: Civilization) =
        operationAgainst(civ, AiOperationType.SNEAK_CITY_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK, enemy = enemy)

    /** CvMilitaryAI::GetShowOfForceOperation */
    fun showOfForceOperation(civ: Civilization, enemy: Civilization) = operationAgainst(civ, AiOperationType.SMALL_CITY_ATTACK, enemy = enemy)

    /** CvMilitaryAI::GetBasicAttackOperation */
    fun basicAttackOperation(civ: Civilization, enemy: Civilization) =
        operationAgainst(civ, AiOperationType.BASIC_CITY_ATTACK, AiOperationType.NAVAL_ATTACK, enemy = enemy)

    /** CvMilitaryAI::GetCityStateAttackOperation */
    fun cityStateAttackOperation(civ: Civilization, enemy: Civilization) =
        operationAgainst(civ, AiOperationType.CITY_STATE_ATTACK, AiOperationType.CITY_STATE_NAVAL_ATTACK, enemy = enemy)
    //endregion

    //region UpdateOperations

    private fun validMajors(civ: Civilization) =
        civ.gameInfo.civilizations.filter { it.isMajorCiv && it !== civ && civ.ai.diplomacy.isPlayerValid(it) }

    /** The original's team-level "forced peace" and "can't attack": peace we cannot declare war out of */
    private fun cannotAttack(civ: Civilization, other: Civilization) =
        !civ.isAtWarWith(other) && civ.getDiplomacyManager(other)?.canDeclareWar() != true

    /** The players at war with us that Vox Populi's UpdateOperations loops over (CvMilitaryAI::IsPlayerValid: the barbarians too) */
    private fun enemiesAtWar(civ: Civilization) = civ.gameInfo.civilizations.filter {
        (it.isBarbarian || WarStateAi.isPlayerValid(civ, it)) && it != civ && civ.isAtWarWith(it)
    }

    /**
     *  CvMilitaryAI::UpdateOperations: kills the operations that no longer make sense, then launches the new ones.
     *  Kills: barbarian camp operations when not eradicating barbarians, at war or not willing to take the risk; with no war all attacks, raids
     *  and defensive operations; with a war, per enemy by war state (winning: no defense operations; defensive or nearly defeated: no
     *  attacks or raids), operations against a civilization we cannot attack, close defense operations of cities no longer threatened, rapid
     *  response when the total threat is at most one major's.
     *  Launches: a barbarian camp operation (eradicating barbarians, not at war, no critical defense, none running, willing to take the risk);
     *  at war, nuke attacks ([shouldLaunchNuke]); for a major, Vox Populi's city attacks and raids against each enemy ([doCityAttacks]);
     *  naval superiority and bombardment while there is room for them ([launchOpportunityNavalOperations]).
     */
    fun updateOperations(civ: Civilization) {
        val military = civ.ai.military
        val operations = civ.ai.operations
        val barbs = civ.ai.isMilitaryStrategyActive(ERADICATE_BARBARIANS)
        val atWar = civ.ai.isMilitaryStrategyActive(AT_WAR)

        // Are we willing to risk pressing forward vs. barbarians?
        var willingToAcceptRisk = military.totalThreatWeight / 2 < military.barbarianThreatTotal(civ)
        if (civ.religionManager.religion?.founderBeliefUniqueMap?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.ConvertAdjacentBarbarians) == true)
            willingToAcceptRisk = true // CvPlayerTraits::GetLandBarbarianConversionPercent > 0: the belief that converts them here
        // if they have one of our civilians
        val barbarians = civ.gameInfo.civilizations.firstOrNull { it.isBarbarian }
        if (barbarians?.units?.getCivUnits()?.any { it.originalOwner == civ.civID && UnitAiTypes.current(it).let { t -> t == UnitAiType.SETTLE || t == UnitAiType.WORKER } } == true)
            willingToAcceptRisk = true

        // Operations vs. Barbarians
        if (!barbs || atWar || !willingToAcceptRisk) operations.killAll(AiOperationType.DESTROY_BARBARIAN_CAMP, OperationAbortReason.WAR_STATE_CHANGE)

        // Operations vs. other civs: are our wars over?
        if (!atWar) {
            for (type in listOf(AiOperationType.BASIC_CITY_ATTACK, AiOperationType.PILLAGE_ENEMY, AiOperationType.RAPID_RESPONSE,
                AiOperationType.CITY_CLOSE_DEFENSE, AiOperationType.NAVAL_ATTACK))
                operations.killAll(type, OperationAbortReason.WAR_STATE_CHANGE)
        } else {
            val stateAllWars = WarStateAi.stateAllWars(civ)
            for (other in validMajors(civ)) {
                // If we've made peace with this player (or are losing everywhere), abort all operations related to him
                if (cannotAttack(civ, other) || stateAllWars == StateAllWars.Losing) {
                    for (operation in listOfNotNull(sneakAttackOperation(civ, other), basicAttackOperation(civ, other), showOfForceOperation(civ, other)))
                        operations.kill(operation, OperationAbortReason.WAR_STATE_CHANGE)
                }
                when (WarStateAi.warState(civ, other)) {
                    // If we are dominant, shouldn't be running a defensive strategy
                    WarState.NearlyWon, WarState.Offensive ->
                        for (type in listOf(AiOperationType.RAPID_RESPONSE, AiOperationType.CITY_CLOSE_DEFENSE))
                            operations.firstOfType(type)?.takeIf { it.enemyCivId == other.civID }?.let { operations.kill(it, OperationAbortReason.WAR_STATE_CHANGE) }
                    // If we are losing, make sure attacks are not running; if nearly defeated, call off all operations in enemy territory
                    WarState.Defensive, WarState.NearlyDefeated ->
                        for (type in listOf(AiOperationType.BASIC_CITY_ATTACK, AiOperationType.PILLAGE_ENEMY, AiOperationType.NAVAL_ATTACK))
                            operations.firstOfType(type)?.takeIf { it.enemyCivId == other.civID }?.let { operations.kill(it, OperationAbortReason.WAR_STATE_CHANGE) }
                    else -> Unit
                }
            }
            // Are there city defense operations for cities that no longer need defending?
            for (city in civ.cities) {
                if (civ.ai.plotDanger.cityThreat(city) == 0)
                    operations.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE, target = city.getCenterTile())?.let { operations.kill(it, OperationAbortReason.WAR_STATE_CHANGE) }
            }
            // Are we running a rapid response tactic and the overall threat level is very low?
            if (military.totalThreatWeight <= civ.gameInfo.ruleset.ai.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MAJOR))
                operations.firstOfType(AiOperationType.RAPID_RESPONSE)?.let { operations.kill(it, OperationAbortReason.WAR_STATE_CHANGE) }
        }

        // Operation vs. Barbarians: not at war, no critical defense needs, none running, willing to risk it
        if (barbs && !atWar && !civ.ai.isMilitaryStrategyActive(EMPIRE_DEFENSE_CRITICAL) &&
            operations.firstOfType(AiOperationType.DESTROY_BARBARIAN_CAMP) == null && willingToAcceptRisk)
            AiOperationLogic.addOperation(civ, AiOperationType.DESTROY_BARBARIAN_CAMP)

        // Operation vs. other civs: at war, consider nukes
        if (atWar) for (other in validMajors(civ)) if (shouldLaunchNuke(civ, other)) requestNukeAttack(civ, other)

        // Vox Populi: only majors set up operations; city attacks and raids against each enemy
        if (civ.isMajorCiv) for (enemy in enemiesAtWar(civ)) doCityAttacks(civ, enemy)
        launchOpportunityNavalOperations(civ)
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
