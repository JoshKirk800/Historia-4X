package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.FormationSlotDef
import com.unciv.models.ruleset.ai.MultiUnitFormationDef
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.max

/**
 *  How the tactical AI moves the armies of an operation (AIOperationMovementType); [None] = the original's INVALID / STATIC type: never moved.
 *  [EnemyTerritory] is every army of Vox Populi's military operations (ARMY_TYPE_LAND, ARMY_TYPE_NAVAL and ARMY_TYPE_COMBINED, which
 *  CvAIOperation::Move gives to CvTacticalAI::PlotArmyMovesCombat): the attacks on a city, the pillage raids, the defense operations, and the
 *  naval ones, whose ships and embarked land units gather and sail on water ([com.unciv.logic.automation.unit.tactical.OperationalMoves]).
 *  [NavalEscort], [FreeformNaval] and [NavalFleet] are BNW's PlotNavalEscortOperationMoves, PlotFreeformNavalOperationMoves and
 *  the fleet moves, which no operation has any more.
 */
enum class OperationMoveType { None, SingleHex, EnemyTerritory, NavalEscort, FreeformNaval, NavalFleet }

/**
 *  AIOperationTypes this port covers. [moveType] is what CvAIOperation::m_eMoveType is set to by the operation's Init;
 *  [mixedLandNaval] = CvAIOperation::IsMixedLandNavalOperation, [allNaval] = IsAllNavalOperation;
 *  [tacticalMayInterrupt] = CanTacticalAIInterruptOperation (true only for the naval superiority and the pure naval city attack).
 */
enum class AiOperationType(
    val moveType: OperationMoveType,
    val mixedLandNaval: Boolean = false,
    val tacticalMayInterrupt: Boolean = false,
    val allNaval: Boolean = false,
) {
    FOUND_CITY(OperationMoveType.SingleHex),
    QUICK_COLONIZE(OperationMoveType.SingleHex),
    BASIC_CITY_ATTACK(OperationMoveType.EnemyTerritory),
    SNEAK_CITY_ATTACK(OperationMoveType.EnemyTerritory),
    CITY_STATE_ATTACK(OperationMoveType.EnemyTerritory),
    PILLAGE_ENEMY(OperationMoveType.EnemyTerritory),
    CITY_CLOSE_DEFENSE(OperationMoveType.EnemyTerritory),
    RAPID_RESPONSE(OperationMoveType.EnemyTerritory),
    NAVAL_ATTACK(OperationMoveType.EnemyTerritory, mixedLandNaval = true),
    NAVAL_SNEAK_ATTACK(OperationMoveType.EnemyTerritory, mixedLandNaval = true),
    CITY_STATE_NAVAL_ATTACK(OperationMoveType.EnemyTerritory, mixedLandNaval = true),
    NAVAL_SUPERIORITY(OperationMoveType.EnemyTerritory, tacticalMayInterrupt = true, allNaval = true),
    /** CvAIOperationCarrierGroup: an aircraft carrier with its escort, which goes where the war is and never ends (IsNeverEnding) */
    CARRIER_GROUP(OperationMoveType.EnemyTerritory, allNaval = true),
    PURE_NAVAL_CITY_ATTACK(OperationMoveType.EnemyTerritory, tacticalMayInterrupt = true, allNaval = true),
    /** CvAIOperationNukeAttack: the original's STATIC move type, it only works with the nuke already in place */
    NUKE_ATTACK(OperationMoveType.None),
    /**
     *  Brave New World's barbarian camp, naval bombardment and small city attack (the show of force) operations, which Vox Populi has not
     *  (its CvMilitaryAI::RequestBullyingOperation sends the ordinary land or naval city attack): never started; kept only so that a
     *  save holding one loads ([AiOperations.setTransients] drops them)
     */
    DESTROY_BARBARIAN_CAMP(OperationMoveType.None),
    NAVAL_BOMBARDMENT(OperationMoveType.None),
    SMALL_CITY_ATTACK(OperationMoveType.EnemyTerritory);

    /** The operations CvCity-bound settlers run (CvAIEscortedOperation) */
    val isEscorted get() = this == FOUND_CITY || this == QUICK_COLONIZE
    /** CvAIOperationBasicCityAttack and its subclasses: the attacks on a city by land */
    val isCityAttack get() = this == BASIC_CITY_ATTACK || this == SNEAK_CITY_ATTACK || this == CITY_STATE_ATTACK
    /** CvAIOperationDefendCity and CvAIOperationDefenseRapidResponse: the operations that defend by land */
    val isLandDefense get() = this == CITY_CLOSE_DEFENSE || this == RAPID_RESPONSE
    /** The operations that Vox Populi's CheckLandDefenses and CheckSeaDefenses start: the land defense operations and the naval superiority squadron */
    val isDefense get() = isLandDefense || this == NAVAL_SUPERIORITY
    /** Retired types ([DESTROY_BARBARIAN_CAMP], [NAVAL_BOMBARDMENT], [SMALL_CITY_ATTACK]) */
    val isRetired get() = this == DESTROY_BARBARIAN_CAMP || this == NAVAL_BOMBARDMENT || this == SMALL_CITY_ATTACK
    /** CvAIOperation::IsNeverEnding: the operation is not meant to reach its target, so it does not time out ([AiOperationLogic.TIMEOUT_TURNS]) or arrive */
    val isNeverEnding get() = this == CARRIER_GROUP
    /** CvAIOperationNavalAttack and its subclasses: land units that embark, with warships, against a coastal city */
    val isNavalInvasion get() = this == NAVAL_ATTACK || this == NAVAL_SNEAK_ATTACK || this == CITY_STATE_NAVAL_ATTACK
    /** CvAINavalOperation and its subclasses: the operations of ships only */
    val isNavalFleet get() = allNaval
}

/** AIOperationStates */
enum class OperationState { RECRUITING_UNITS, GATHERING_FORCES, MOVING_TO_TARGET, AT_TARGET, SUCCESSFUL_FINISH, ABORTED }

/** AIOperationAbortReason (the ones the ported operations use) */
enum class OperationAbortReason {
    NONE, SUCCESS, NO_TARGET, REPEAT_TARGET, LOST_TARGET, TARGET_ALREADY_CAPTURED, NO_ROOM_DEPLOY, HALF_STRENGTH, NO_MUSTER,
    LOST_CIVILIAN, ESCORT_DIED, KILLED, WAR_STATE_CHANGE, DIPLO_OPINION_CHANGE, LOST_PATH, NO_NUKES,
    /** Vox Populi's AI_ABORT_TIMED_OUT: [AiOperationLogic.shouldAbort]'s failsafe */
    TIMED_OUT,
    /** Vox Populi's AI_ABORT_TARGET_NOT_VALID: war on the enemy may no longer be declared ([WarAi.doMakeWarOnPlayer]) */
    TARGET_NOT_VALID,
    /** Vox Populi's AI_ABORT_NO_UNITS: more units to build than the request allows ([MilitaryOperations.requestPillageAttack]) */
    NO_UNITS,
    /** Vox Populi's AI_ABORT_TOO_DANGEROUS: the army's way leads past a city of another enemy ([com.unciv.logic.automation.unit.tactical.OperationalMoves]) */
    TOO_DANGEROUS,
    /** Vox Populi's AI_ABORT_CANCELLED: the carrier of a carrier group is gone */
    CANCELLED
}

/** ArmyAIState */
enum class ArmyState { WAITING_FOR_UNITS_TO_REINFORCE, WAITING_FOR_UNITS_TO_CATCH_UP, MOVING_TO_DESTINATION }

/** OperationSlot: one slot of an army's formation (CvAIOperation::m_viListOfUnitsWeStillNeedToBuild entries) */
data class SlotRef(var operationId: Int = -1, var armyId: Int = -1, var slotId: Int = -1) : IsPartOfGameInfoSerialization

/** A slot a city has committed to fill (CvAIOperation::m_viListOfUnitsCitiesHaveCommittedToBuild plus CvCity::m_unitBeingBuiltForOperation) */
class SlotCommitment : IsPartOfGameInfoSerialization {
    var slot = SlotRef()
    var cityId = ""
    /** The unit type the city trains and the unit AI type it gets (the slot's primary or secondary one) */
    var unitName = ""
    var unitAi = ""

    fun clone() = SlotCommitment().also { it.slot = slot.copy(); it.cityId = cityId; it.unitName = unitName; it.unitAi = unitAi }
}

/** CvArmyFormationSlot */
class ArmySlot : IsPartOfGameInfoSerialization {
    /** [MapUnit.id] of the unit in the slot, -1 = empty */
    var unitId = -1
    /** Game turn the slot's unit is expected at the muster point: [UNKNOWN_TURN], [NOT_INCLUDED] or a turn */
    var estimatedTurn = UNKNOWN_TURN
    var startedOnOperation = false
    /**
     *  CvArmyFormationSlot::m_estTurnsToCheckpoint: the turns the slot's unit needed to get to the army's checkpoint at the last three
     *  updates ([AiOperationLogic.continueRecruiting]), the newest first; [Int.MAX_VALUE] when it could not get there in time
     */
    var checkpointTurns = ArrayList<Int>()

    fun clone() = ArmySlot().also {
        it.unitId = unitId; it.estimatedTurn = estimatedTurn; it.startedOnOperation = startedOnOperation
        it.checkpointTurns = ArrayList(checkpointTurns)
    }

    /** CvArmyFormationSlot::SetCurrentTurnsToCheckpoint: [turns] is the newest record, only the last three are kept */
    fun pushCheckpointTurns(turns: Int) {
        checkpointTurns.add(0, turns)
        if (checkpointTurns.size > CHECKPOINT_HISTORY) checkpointTurns.removeAt(checkpointTurns.lastIndex)
    }

    /**
     *  CvArmyFormationSlot::IsMakingProgressTowardsCheckpoint: with fewer than three records, or when the newest says the unit is there
     *  next turn, yes; else only if the newest is better than the oldest (a fight or a block costs a turn now and then)
     */
    fun isMakingProgress(): Boolean {
        if (checkpointTurns.size < CHECKPOINT_HISTORY) return true
        if (checkpointTurns.first() < 2) return true
        return checkpointTurns.first() < checkpointTurns.last()
    }

    companion object {
        /** ARMYSLOT_UNKNOWN_TURN_AT_CHECKPOINT */
        const val UNKNOWN_TURN = -1
        /** ARMYSLOT_NOT_INCLUDING_IN_OPERATION */
        const val NOT_INCLUDED = -2
        /** The records [checkpointTurns] keeps */
        const val CHECKPOINT_HISTORY = 3
    }
}

/** CvArmyAI: the units an operation has put together, one per formation slot */
class AiArmy : IsPartOfGameInfoSerialization {
    var id = -1
    var operationId = -1
    /** MultiUnitFormation name */
    var formation = ""
    var state = ArmyState.WAITING_FOR_UNITS_TO_REINFORCE
    /** CvArmyAI::GetGoalPlot: where the army heads (a few plots short of the target for attacks) */
    var goalTile: HexCoord? = null
    /** CvArmyAI::Plot: the army's current rally point (the muster, later this turn's center of mass checkpoint) */
    var position: HexCoord? = null
    var slots = ArrayList<ArmySlot>()

    fun clone(): AiArmy {
        val toReturn = AiArmy()
        toReturn.id = id; toReturn.operationId = operationId; toReturn.formation = formation; toReturn.state = state
        toReturn.goalTile = goalTile; toReturn.position = position
        for (slot in slots) toReturn.slots.add(slot.clone())
        return toReturn
    }

    /** CvArmyAI::GetNumSlotsFilled */
    fun numSlotsFilled() = slots.count { it.unitId != -1 }
    fun unitIds(): List<Int> = slots.mapNotNull { if (it.unitId == -1) null else it.unitId }
}

/** CvAIOperation with its state; the logic is in [AiOperations] */
class AiOperation : IsPartOfGameInfoSerialization {
    var id = -1
    var type = AiOperationType.BASIC_CITY_ATTACK
    /** civID of the enemy (CvAIOperation::GetEnemy), null = none (barbarians, settling) */
    var enemyCivId: String? = null
    var state = OperationState.ABORTED
    var abortReason = OperationAbortReason.NONE
    var targetTile: HexCoord? = null
    var musterTile: HexCoord? = null
    /** [City.id] of the start city (CvAIOperation::GetStartCityPlot) */
    var startCityId: String? = null
    /** CvAIOperation::m_iDefaultArea: the landmass cities must be on to supply units, [Tile.getContinent] */
    var area = -1
    /** CvAIEscortedOperation::m_iTargetArea: the landmass the settler looks for a site on, -1 = anywhere */
    var targetArea = -1
    var armies = ArrayList<AiArmy>()
    /** The slots still to fill, the next to build is the last */
    var slotsToBuild = ArrayList<SlotRef>()
    var committedCities = ArrayList<SlotCommitment>()
    var lastTurnMoved = -1
    var createdTurn = 0
    /** CvAIEscortedOperation::m_bEscorted */
    var escorted = true

    fun clone(): AiOperation {
        val toReturn = AiOperation()
        toReturn.id = id; toReturn.type = type; toReturn.enemyCivId = enemyCivId; toReturn.state = state
        toReturn.abortReason = abortReason; toReturn.targetTile = targetTile; toReturn.musterTile = musterTile
        toReturn.startCityId = startCityId; toReturn.area = area; toReturn.targetArea = targetArea
        for (army in armies) toReturn.armies.add(army.clone())
        for (slot in slotsToBuild) toReturn.slotsToBuild.add(slot.copy())
        for (commitment in committedCities) toReturn.committedCities.add(commitment.clone())
        toReturn.lastTurnMoved = lastTurnMoved; toReturn.createdTurn = createdTurn; toReturn.escorted = escorted
        return toReturn
    }

    /** CvAIOperation::GetFirstArmyID */
    val firstArmy get() = armies.firstOrNull()
    /** CvAIOperation::HasOneMoreSlotToFill */
    fun hasOneMoreSlotToFill() = slotsToBuild.size == 1 && state == OperationState.RECRUITING_UNITS
    fun armyById(armyId: Int) = armies.firstOrNull { it.id == armyId }
}

/**
 *  The operations and armies of a civilization (`civ.ai.operations`): CvPlayer's m_AIOperations / m_AIArmies with CvPlayerAI's
 *  PeekAtNextUnitToBuildForOperationSlot, CityCommitToBuildUnitForOperationSlot, CityUncommitToBuildUnitForOperationSlot and
 *  CityFinishedBuildingUnitForOperationSlot, plus the per-unit army id (CvUnit::getArmyID, kept in the army slots) and
 *  CvUnit::m_iDeployFromOperationTurn. The operation classes' behaviour is in [AiOperationLogic].
 *
 *  Serialized with the civilization; old saves have none (their per-settler operation is dropped: the settler starts a new one).
 */
class AiOperations : IsPartOfGameInfoSerialization {
    var operations = ArrayList<AiOperation>()
    var nextOperationId = 1
    var nextArmyId = 1
    /** CvUnit::GetDeployFromOperationTurn by [MapUnit.id], units that finished a successful operation */
    var deployFromOperationTurns = HashMap<Int, Int>()

    @Transient lateinit var civ: Civilization
    @Transient var statsLaunched = 0
    @Transient var statsSucceeded = 0
    @Transient var statsAborted = 0
    /** Operations created and succeeded per type (for the simulation report): [created, succeeded] */
    @Transient val statsByType = HashMap<AiOperationType, IntArray>()
    /** Operations ended other than by success, per type and reason (for the simulation report) */
    @Transient val statsAbortReasons = HashMap<AiOperationType, HashMap<OperationAbortReason, Int>>()

    fun clone(): AiOperations {
        val toReturn = AiOperations()
        for (operation in operations) toReturn.operations.add(operation.clone())
        toReturn.nextOperationId = nextOperationId
        toReturn.nextArmyId = nextArmyId
        toReturn.deployFromOperationTurns.putAll(deployFromOperationTurns)
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        operations.removeAll { it.type.isRetired }
    }

    //region Queries (CvPlayer::haveAIOperationOfType, numOperationsOfType, getAIOperation, CvUnit::getArmyID)

    /**
     *  CvPlayer::getFirstAIOperationOfType: the first operation of [type] that is not aborted, against [enemy] (any when null) and, when
     *  [target] is given, aimed less than [TARGET_LOOKUP_DISTANCE] plots from it
     */
    fun firstOfType(type: AiOperationType, enemy: Civilization? = null, target: Tile? = null): AiOperation? =
        operations.firstOrNull { operation ->
            operation.type == type && operation.state != OperationState.ABORTED && (enemy == null || operation.enemyCivId == enemy.civID) &&
                (target == null || operation.targetTile?.let { target.aerialDistanceTo(civ.gameInfo.tileMap[it]) < TARGET_LOOKUP_DISTANCE } == true)
        }

    /** CvPlayer::numOperationsOfType */
    fun numOperationsOfType(type: AiOperationType) = operations.count { it.type == type }

    /**
     *  CvPlayer::getFirstOffensiveAIOperation: the first military operation still running against [enemy] whose target is outside our
     *  territory (CvAIOperation::IsOffensive); the one Vox Populi's demand and show of force look at (it has no GetShowOfForceOperation)
     */
    fun firstOffensiveOperationAgainst(enemy: Civilization): AiOperation? = operations.firstOrNull { isOffensiveAgainst(it, enemy) }

    /** CvPlayer::HasAnyOffensiveOperationsAgainstPlayer ([firstOffensiveOperationAgainst] exists) */
    fun hasAnyOffensiveOperationsAgainst(enemy: Civilization): Boolean = firstOffensiveOperationAgainst(enemy) != null

    /** The operations of [hasAnyOffensiveOperationsAgainst] (CvPlayer::StopAllLand/SeaOffensiveOperationsAgainstPlayer stop these) */
    fun offensiveOperationsAgainst(enemy: Civilization): List<AiOperation> = operations.filter { isOffensiveAgainst(it, enemy) }

    /** CvPlayer::GetNumOffensiveOperations, land plus sea: the military operations not aborted whose target is outside our territory */
    fun numOffensiveOperations(): Int = operations.count { !it.type.isEscorted && it.state != OperationState.ABORTED && isOffensive(it) }

    /**
     *  CvPlayer::StopAllLandOffensiveOperationsAgainstPlayer and its Sea / Defensive siblings: the military operations still running
     *  against [enemy] (null: anyone) that are [offensive] or not, by sea (naval invasions and fleets, CvAIOperation::IsNavalOperation)
     *  or not, end for [reason]
     */
    fun stopAll(offensive: Boolean, naval: Boolean, enemy: Civilization?, reason: OperationAbortReason) {
        for (op in operations.toList()) {
            if (op.type.isEscorted || op.state == OperationState.SUCCESSFUL_FINISH || op.state == OperationState.ABORTED) continue
            if (isOffensive(op) != offensive || (op.type.isNavalInvasion || op.type.allNaval) != naval) continue
            if (enemy == null || op.enemyCivId == enemy.civID) kill(op, reason)
        }
    }

    /** CvAIOperation::IsOffensive: the target is not friendly territory for us (ours, or that of one who allows us in) */
    fun isOffensive(op: AiOperation): Boolean = op.targetTile?.let { !civ.gameInfo.tileMap[it].isFriendlyTerritory(civ) } == true

    private fun isOffensiveAgainst(op: AiOperation, enemy: Civilization): Boolean =
        !op.type.isEscorted && op.enemyCivId == enemy.civID &&
            op.state != OperationState.SUCCESSFUL_FINISH && op.state != OperationState.ABORTED && isOffensive(op)

    /** CvPlayerAI::GetNumUnitsNeededToBeBuilt: the sum of CvAIOperation::GetNumUnitsNeededToBeBuilt, the slots not yet committed to a city */
    fun numUnitsNeededToBeBuilt() = operations.sumOf { it.slotsToBuild.size }

    fun operationById(id: Int) = operations.firstOrNull { it.id == id }

    /** The army [unit] serves in, CvUnit::getArmyID */
    fun armyOf(unit: MapUnit): AiArmy? = armyOfUnitId(unit.id)

    fun armyOfUnitId(unitId: Int): AiArmy? {
        for (operation in operations) for (army in operation.armies) for (slot in army.slots) if (slot.unitId == unitId) return army
        return null
    }

    /** The operation [unit]'s army belongs to */
    fun operationFor(unit: MapUnit): AiOperation? {
        for (operation in operations) for (army in operation.armies) for (slot in army.slots) if (slot.unitId == unit.id) return operation
        return null
    }

    fun slotIndexOf(army: AiArmy, unit: MapUnit) = army.slots.indexOfFirst { it.unitId == unit.id }

    /** CvArmyAI::CanTacticalAIInterruptUnit: while the army assembles, by all means; later only if the operation allows it */
    fun canTacticalAiInterrupt(unit: MapUnit): Boolean {
        val operation = operationFor(unit) ?: return true
        val army = operation.armies.first { a -> a.slots.any { it.unitId == unit.id } }
        if (army.state != ArmyState.MOVING_TO_DESTINATION) return true
        return operation.type.tacticalMayInterrupt
    }

    /** CvUnit::GetDeployFromOperationTurn, -100 (the original's initial value) if never */
    fun deployFromOperationTurn(unitId: Int) = deployFromOperationTurns[unitId] ?: NEVER_DEPLOYED

    companion object {
        const val NEVER_DEPLOYED = -100
        /** CvPlayer::getFirstAIOperationOfType's iMaxTargetDistance */
        const val TARGET_LOOKUP_DISTANCE = 3
    }

    /** The target tiles of the settler operations of this civilization other than [except] */
    fun settleTargets(except: AiOperation?): List<Tile> = operations
        .filter { it !== except && it.type.isEscorted }
        .mapNotNull { operation -> operation.targetTile?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) } }
    //endregion

    //region Army and operation lifecycle (CvPlayer::addAIOperation, CvAIOperation::Kill, CvArmyAI)

    internal fun newOperation(type: AiOperationType, enemy: Civilization?): AiOperation {
        val operation = AiOperation()
        operation.id = nextOperationId++
        operation.type = type
        operation.enemyCivId = enemy?.civID
        operation.createdTurn = civ.gameInfo.turns
        statsByType.getOrPut(type) { IntArray(2) }[0]++
        operations.add(operation)
        return operation
    }

    internal fun newArmy(operation: AiOperation, formation: String): AiArmy {
        val army = AiArmy()
        army.id = nextArmyId++
        army.operationId = operation.id
        army.formation = formation
        army.state = ArmyState.WAITING_FOR_UNITS_TO_REINFORCE
        val def = formationDef(formation)
        repeat(def?.slots?.size ?: 0) { army.slots.add(ArmySlot()) }
        operation.armies.add(army)
        return army
    }

    fun formationDef(name: String): MultiUnitFormationDef? = civ.gameInfo.ruleset.ai.multiUnitFormations.firstOrNull { it.name == name }

    /** CvArmyAI::AddUnit: [unit] takes slot [slot] of [army], leaving the army it was in; its estimated arrival at the muster is computed */
    fun addUnit(army: AiArmy, unit: MapUnit, slot: Int) {
        removeFromArmy(unit)
        val entry = army.slots[slot]
        entry.unitId = unit.id
        entry.checkpointTurns.clear()
        val muster = army.position?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) } ?: return
        val turns = AiOperationLogic.turnsToReach(unit, muster)
        if (turns != Int.MAX_VALUE) setEstimatedTurn(army, slot, turns)
        // CvArmyAI::AddUnit records the first estimate to the checkpoint, [Int.MAX_VALUE] for one that takes too long
        entry.pushCheckpointTurns(if (turns > civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_MAX_RECRUIT_TURNS_ENEMY_TERRITORY)) Int.MAX_VALUE else turns)
    }

    /** CvArmyAI::SetEstimatedTurn */
    fun setEstimatedTurn(army: AiArmy, slot: Int, turns: Int) {
        army.slots[slot].estimatedTurn =
            if (turns == ArmySlot.NOT_INCLUDED || turns == ArmySlot.UNKNOWN_TURN) turns else civ.gameInfo.turns + turns
    }

    /** CvPlayer::removeFromArmy: the unit leaves its army and gets its own unit AI back; its operation is told (CvArmyAI::RemoveUnit) */
    fun removeFromArmy(unit: MapUnit) = removeUnitId(unit.id)

    internal fun removeUnitId(unitId: Int) {
        for (operation in operations.toList()) for (army in operation.armies.toList()) {
            val index = army.slots.indexOfFirst { it.unitId == unitId }
            if (index < 0) continue
            army.slots[index].unitId = -1
            army.slots[index].checkpointTurns.clear()
            releaseUnitAi(unitId)
            AiOperationLogic.unitWasRemoved(this, operation, army, index)
        }
    }

    /**
     *  CvArmyAI::RemoveUnit with bTempOnly: [unit] leaves its army and gets its own unit AI back, the operation is not told (a unit that is
     *  replaced at once, CvTacticalAI's SwitchEscort, must not end the operation).
     *  @return the slot [unit] left, -1 if it was in no army
     */
    fun removeFromArmyTemporarily(unit: MapUnit): Int {
        for (operation in operations) for (army in operation.armies) {
            val index = army.slots.indexOfFirst { it.unitId == unit.id }
            if (index < 0) continue
            army.slots[index].unitId = -1
            army.slots[index].checkpointTurns.clear()
            releaseUnitAi(unit.id)
            return index
        }
        return -1
    }

    /** CvArmyAI::RemoveUnit's AI_setUnitAIType(default): a unit that leaves an army is no longer what its slot made of it */
    private fun releaseUnitAi(unitId: Int) {
        val unit = civ.units.getUnitById(unitId) ?: return
        UnitAiTypes.set(unit, UnitAiTypes.defaultOf(unit.baseUnit, civ.gameInfo.ruleset.ai))
    }

    /** CvAIOperation::Kill with CvAIOperation::Uninit: the armies are dissolved, their units are free again */
    fun kill(operation: AiOperation, reason: OperationAbortReason) {
        if (operation.abortReason == OperationAbortReason.NONE) operation.abortReason = reason
        AiDecisionLog.log(civ) {
            "operation ${operation.type} #${operation.id} ended: ${operation.abortReason} (started t${operation.createdTurn}, target ${operation.targetTile})"
        }
        if (operation.abortReason == OperationAbortReason.SUCCESS) {
            statsSucceeded++
            statsByType.getOrPut(operation.type) { IntArray(2) }[1]++
        } else {
            statsAborted++
            statsAbortReasons.getOrPut(operation.type) { HashMap() }.merge(operation.abortReason, 1, Int::plus)
        }
        for (army in operation.armies) for (slot in army.slots) if (slot.unitId != -1) releaseUnitAi(slot.unitId)
        operation.armies.clear()
        operation.slotsToBuild.clear()
        operation.committedCities.clear()
        operations.remove(operation)
    }

    /** Kills every operation of [type] (optionally against [enemy]) */
    fun killAll(type: AiOperationType, reason: OperationAbortReason, enemy: Civilization? = null) {
        for (operation in operations.toList())
            if (operation.type == type && (enemy == null || operation.enemyCivId == enemy.civID)) kill(operation, reason)
    }
    //endregion

    //region City demand (CvPlayerAI::PeekAtNextUnitToBuildForOperationSlot ... CityFinishedBuildingUnitForOperationSlot, CvCity::GetUnitForOperation)

    /** The slot, the unit type to train and the unit AI it takes: what CvCity::GetUnitForOperation names for [city] */
    class SlotOffer(val slot: SlotRef, val unit: BaseUnit, val unitAi: UnitAiType, val unitAiSecondary: Boolean)

    /** CvPlayerAI::PeekAtNextUnitToBuildForOperationSlot: the next slot to fill on landmass [areaId] (first operation that needs one) */
    fun peekAtNextUnitToBuild(areaId: Int): SlotRef? {
        for (operation in operations) if (operation.area == areaId) operation.slotsToBuild.lastOrNull()?.let { return it }
        return null
    }

    /**
     *  Its bCitySameAsMuster: the operation of the next open slot on [city]'s landmass musters on a tile [city] owns (the rule that
     *  only the muster city trains units for an operation, CvCity::GetUnitForOperation and CvCityStrategyAI::ChooseProduction).
     */
    fun isNextSlotMusteredIn(city: City): Boolean {
        val slot = peekAtNextUnitToBuild(city.getCenterTile().getContinent()) ?: return false
        val muster = operationById(slot.operationId)?.musterTile?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) } ?: return false
        return muster.getCity() == city
    }

    /**
     *  CvCity::GetUnitForOperation: for the next open slot on [city]'s landmass, when [city] is the muster city ([isNextSlotMusteredIn]), the
     *  unit CvUnitProductionAI::RecommendUnit picks for the slot's primary unit AI, else its secondary one; null if no slot waits or the
     *  city can train neither.
     */
    fun nextUnitForOperationSlot(city: City): SlotOffer? {
        if (!isNextSlotMusteredIn(city)) return null
        val slot = peekAtNextUnitToBuild(city.getCenterTile().getContinent()) ?: return null
        val army = operationById(slot.operationId)?.armyById(slot.armyId) ?: return null
        val entry = formationDef(army.formation)?.slots?.getOrNull(slot.slotId) ?: return null
        UnitAiType.ofXmlName(entry.primaryUnitAi)?.let { ai ->
            ProductionAi.recommendUnit(city, ai)?.let { return SlotOffer(slot, it, ai, false) }
        }
        UnitAiType.ofXmlName(entry.secondaryUnitAi)?.let { ai ->
            ProductionAi.recommendUnit(city, ai)?.let { return SlotOffer(slot, it, ai, true) }
        }
        return null
    }

    /**
     *  CvCity::CommitToBuildingUnitForOperation / CvAIOperation::CommitToBuildNextUnit: [city] trains the [offer]'s unit for the slot, which
     *  leaves the list still to build; the unit is expected [turns] + half the distance from the city to the muster at the army's rally point.
     */
    fun commitToBuild(city: City, offer: SlotOffer, turns: Int) {
        val operation = operationById(offer.slot.operationId) ?: return
        if (!operation.slotsToBuild.remove(offer.slot)) return
        val commitment = SlotCommitment()
        commitment.slot = offer.slot.copy()
        commitment.cityId = city.id
        commitment.unitName = offer.unit.name
        commitment.unitAi = offer.unitAi.xmlName
        operation.committedCities.add(commitment)
        val army = operation.armyById(offer.slot.armyId) ?: return
        val rally = army.position?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }
        val guess = if (rally == null) 0 else city.getCenterTile().aerialDistanceTo(rally) / 2
        setEstimatedTurn(army, offer.slot.slotId, turns + guess)
    }

    /** CvPlayerAI::CityUncommitToBuildUnitForOperationSlot: the slot is to be built again */
    fun uncommit(commitment: SlotCommitment) {
        val operation = operationById(commitment.slot.operationId) ?: return
        if (operation.committedCities.remove(commitment)) operation.slotsToBuild.add(commitment.slot)
    }

    /** The slot [city] has committed to fill, if it still trains that unit */
    fun commitmentOf(city: City): SlotCommitment? {
        for (operation in operations) for (commitment in operation.committedCities)
            if (commitment.cityId == city.id) return commitment
        return null
    }

    /** CvCity::IsBuildingUnitForOperation */
    fun isBuildingUnitForOperation(city: City): Boolean = commitmentOf(city) != null

    /**
     *  A city that no longer trains the unit it committed to (production changed, city lost) uncommits
     *  (CvCity::popOrder / CvCity::PostKill: CityUncommitToBuildUnitForOperationSlot); then the operations take what the reserves have
     *  now ([AiOperationLogic.continueRecruiting], CvAIOperation::Move's first steps). It runs before the armies move, with the other
     *  start-of-turn bookkeeping ([com.unciv.logic.automation.unit.tactical.OperationalMoves.prepareTurn]).
     */
    fun refreshCommitments() {
        for (operation in operations) for (commitment in operation.committedCities.toList()) {
            val city = civ.cities.firstOrNull { it.id == commitment.cityId }
            if (city == null || city.cityConstructions.currentConstructionName() != commitment.unitName) uncommit(commitment)
        }
        AiOperationLogic.continueRecruiting(civ)
    }

    /**
     *  CvCity::CreateUnit with bUseToSatisfyOperation / CvPlayerAI::CityFinishedBuildingUnitForOperationSlot: a unit [city] trained for a
     *  slot takes the unit AI of the slot and joins the army; the operation moves on to gathering when nothing is left to build.
     *  @return whether [unit] was trained for an operation - the original then skips the recon assignment of units just trained
     */
    fun finishedBuilding(unit: MapUnit, city: City): Boolean {
        val commitment = commitmentOf(city)?.takeIf { it.unitName == unit.name } ?: return false
        val operation = operationById(commitment.slot.operationId) ?: return false
        val army = operation.armyById(commitment.slot.armyId) ?: return false
        UnitAiType.ofXmlName(commitment.unitAi)?.let { UnitAiTypes.set(unit, it) }
        addUnit(army, unit, commitment.slot.slotId)
        operation.committedCities.remove(commitment)
        if (operation.slotsToBuild.isEmpty()) AiOperationLogic.finishRecruitingIfDone(civ, operation)
        return true
    }

    /** CvAIOperation::BuildListOfUnitsWeStillNeedToBuild: every slot of the armies still waiting for units */
    internal fun buildListOfUnitsWeStillNeedToBuild(operation: AiOperation) {
        operation.committedCities.clear()
        operation.slotsToBuild.clear()
        for (army in operation.armies) {
            if (army.state != ArmyState.WAITING_FOR_UNITS_TO_REINFORCE) continue
            for (index in 0 until (formationDef(army.formation)?.slots?.size ?: 0))
                operation.slotsToBuild.add(SlotRef(operation.id, army.id, index))
        }
    }
    //endregion

    /** The slot entries of [army]'s formation */
    fun slotDefs(army: AiArmy): List<FormationSlotDef> = formationDef(army.formation)?.slots ?: emptyList()

    /** AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION in hit points (of 100) */
    fun minHealth() = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION)

    /** The operation is over, failed: CvAIOperation::SetToAbort; the sweep removes it */
    fun abort(operation: AiOperation, reason: OperationAbortReason) {
        operation.state = OperationState.ABORTED
        operation.abortReason = reason
    }

    /** Units that died or were captured leave their army (CvArmyAI::RemoveUnit as CvUnit::kill does) */
    fun reconcileUnits() {
        val alive = civ.units.getCivUnits().mapTo(HashSet()) { it.id }
        for (operation in operations.toList()) for (army in operation.armies.toList()) for (slot in army.slots.toList())
            if (slot.unitId != -1 && slot.unitId !in alive) removeUnitId(slot.unitId)
    }

    /**
     *  The deletion sweep of CvPlayer::doTurnUnits: operations that are over ([AiOperationLogic.doDelayedDeath]) are killed until none is.
     *  Armies with no unit are no longer dissolved on their own (Vox Populi has not BNW's CvArmyAI::DoDelayedDeath): an operation that loses
     *  its units ends by [AiOperationLogic.unitWasRemoved], one with no army by [AiOperationLogic.shouldAbort].
     */
    fun sweep() {
        do {
            val killed = operations.toList().any { AiOperationLogic.doDelayedDeath(civ, it) }
        } while (killed)
    }

    /**
     *  CvArmyAI::ReleaseAllUnits for an operation that ends in the middle of the turn ([AiOperationLogic.continueAfterMove]): its units leave
     *  the armies and get their own unit AI back, and the operation is not told (it is over, [kill] removes it in the sweep).
     */
    fun releaseAllUnits(operation: AiOperation) {
        for (army in operation.armies) for (slot in army.slots) {
            if (slot.unitId == -1) continue
            releaseUnitAi(slot.unitId)
            slot.unitId = -1
            slot.checkpointTurns.clear()
        }
    }

    /**
     *  CvPlayer::deleteAIOperation of an operation that ended while it was made (CvPlayer::addAIOperation: aborted, or more units to build
     *  than allowed): it never was, so it is no statistic. Its units are free again.
     */
    internal fun discard(operation: AiOperation) {
        releaseAllUnits(operation)
        operation.armies.clear()
        operation.slotsToBuild.clear()
        operation.committedCities.clear()
        operations.remove(operation)
        statsByType[operation.type]?.let { it[0]-- }
    }
}
