package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.automation.unit.tactical.Deployment
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.view.GameView
import com.unciv.logic.automation.diplomacy.ApproachPart6Ai
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.automation.unit.tactical.TacticalMap
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.logic.battle.TargetHelper
import com.unciv.models.ruleset.ai.FormationSlotDef
import kotlin.math.max

/**
 *  The behaviour of CvAIOperation and its subclasses (CvAIOperation.cpp): Init of each operation type, the state machine (CheckOnTarget,
 *  ArmyInPosition, ShouldAbort, UnitWasRemoved, DoDelayedDeath), recruiting from the reserves (GrabUnitsFromTheReserves,
 *  OperationalAIHelpers::IsUnitSuitableForRecruitment, CvUnit::canUseForAIOperation, CvArmyAI::UpdateCheckpointTurnsAndRemoveBadUnits)
 *  and BuyFinalUnit. Operation and army data are in [AiOperations]; the units are moved by [com.unciv.logic.automation.unit.tactical.OperationalMoves].
 *
 *  Covered: CvAIOperationFoundCity, QuickColonize, BasicCityAttack, SneakCityAttack (and the Quick one), SmallCityAttack, CityStateAttack,
 *  DestroyBarbarianCamp, PillageEnemy, CityCloseDefense, RapidResponse; the naval operations and the nuke attack are [NavalOperationLogic].
 *
 *  Deviations, with the reason: the step finder is [StepFinder]; "path length" of a unit's route (CvIgnoreUnitsPathFinder) is the unit's turns
 *  (`getShortestPath`), which respects units; the original's EnemyTerritoryOperation::Init overwrites its LOST_PATH abort when no deploy point is found
 *  (the abort is kept here); a barbarian camp with no land route from the start city is not a target (the original's step distance would be negative).
 *
 *  Recruiting (Vox Populi, Release-5.4.8) deviates where Unciv has no counterpart, each once here:
 *  - the reach test is a unit's own route to the muster point in turns ([turnsToReach], not "approximate target ring 2") within
 *    [maxRecruitTurns], after a cheap test of the distance on the map (the original's step finder `GetPlotsInReach`);
 *  - CvUnit::shouldHeal has no "damage taken last turn" (a unit next to an enemy flees by the danger alone) and no conditional promotions;
 *  - no formation of the data has a slot without a unit AI, so the original's wildcard slot is not ported;
 *  - the units' AI-type reset on release is [AiOperations.removeFromArmy]'s; the upgrade of a unit joining an army (CvArmyAI::AddUnit) is not
 *    done: the gold AI upgrades units;
 *  - a unit bought for an operation (CvAIOperation::RecruitUnit) joins by the next turn's [continueRecruiting], before any unit moves;
 *  - a slot a city has committed to is not offered to the reserves (the original lists it again, and a second city may commit to it);
 *  - only the operations the military AI launches (the attacks on cities and the pillage raids, the naval invasions and pure naval attacks)
 *    leave the units near their target alone: the "already close to the target" rule is for the tactical AI of an attack, and the settlers',
 *    the defense and the naval superiority operations recruit for their muster point alone;
 *  - an operation that found no unit at its start does not abort (SetUpArmy's NO_UNITS): the creation of operations is where the original
 *    checks the units at hand (PreconditionsAreMet) and where the limit of missing units is applied.
 */
object AiOperationLogic {

    //region Helpers

    /** CvArmyAI's TurnsToReachTarget: the turns [unit] needs to [tile], [Int.MAX_VALUE] if it cannot get there */
    fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (unit.getTile() == tile) return 0
        if (!unit.movement.canReach(tile)) return Int.MAX_VALUE
        return unit.movement.getShortestPath(tile).size.coerceAtLeast(1)
    }

    internal fun tileAt(civ: Civilization, coord: HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

    fun targetTile(civ: Civilization, operation: AiOperation) = tileAt(civ, operation.targetTile)
    fun musterTile(civ: Civilization, operation: AiOperation) = tileAt(civ, operation.musterTile)
    fun enemy(civ: Civilization, operation: AiOperation): Civilization? = operation.enemyCivId?.let { civ.gameInfo.getCivilizationOrNull(it) }

    /** The units of [army] that exist (CvArmyAI::GetFirstUnit ...) with their slot index */
    fun armyUnits(civ: Civilization, army: AiArmy): List<Pair<Int, MapUnit>> {
        val result = ArrayList<Pair<Int, MapUnit>>(army.slots.size)
        for ((index, slot) in army.slots.withIndex()) {
            if (slot.unitId == -1) continue
            civ.units.getUnitById(slot.unitId)?.let { result.add(index to it) }
        }
        return result
    }

    /** CvArmyAI::UpdateCheckpointTurns: when each unit that exists will be at the muster point */
    fun updateCheckpointTurns(civ: Civilization, army: AiArmy, muster: Tile) {
        for ((index, unit) in armyUnits(civ, army)) {
            val turns = turnsToReach(unit, muster)
            if (turns != Int.MAX_VALUE) civ.ai.operations.setEstimatedTurn(army, index, turns)
        }
    }

    /** CvArmyAI::GetTurnAtNextCheckpoint: the latest expected arrival, [ArmySlot.UNKNOWN_TURN] if a slot has no estimate */
    fun turnAtNextCheckpoint(army: AiArmy): Int {
        var result = ArmySlot.NOT_INCLUDED
        for (slot in army.slots) {
            if (slot.estimatedTurn == ArmySlot.UNKNOWN_TURN) return ArmySlot.UNKNOWN_TURN
            if (slot.estimatedTurn > result) result = slot.estimatedTurn
        }
        return result
    }

    /** CvAIOperation::GetDeployRange */
    fun deployRange(civ: Civilization, operation: AiOperation): Int {
        val rules = civ.gameInfo.ruleset.ai
        return when (operation.type) {
            AiOperationType.PILLAGE_ENEMY -> rules.define(AiDefine.AI_OPERATIONAL_PILLAGE_ENEMY_DEPLOY_RANGE)
            else -> rules.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        }
    }

    /** Whether the city attack formations are the bigger ones: handicap above 4 on a map that is not an archipelago (GetMap().GetAIMapHint() & 1) */
    private fun biggerForces(civ: Civilization) =
        EconomicAi.gameHandicapIndex(civ.gameInfo) > 4 && SettleAreas.aiMapHint(civ.gameInfo) and 1 == 0

    /** CvAIOperation::GetFormation of the operation types (a formation the ruleset lacks is replaced by the basic city attack force) */
    fun formationName(civ: Civilization, type: AiOperationType): String {
        val name = when (type) {
            AiOperationType.FOUND_CITY -> "MUFORMATION_SETTLER_ESCORT"
            AiOperationType.QUICK_COLONIZE -> "MUFORMATION_QUICK_COLONY_SETTLER"
            AiOperationType.BASIC_CITY_ATTACK -> if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.SNEAK_CITY_ATTACK ->
                if (civ.gameInfo.turns < 50 && civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) >= 5) "MUFORMATION_EARLY_RUSH" // CvAIOperationQuickSneakCityAttack
                else if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.SMALL_CITY_ATTACK ->
                if (EconomicAi.gameHandicapIndex(civ.gameInfo) > 4) "MUFORMATION_BASIC_CITY_ATTACK_FORCE" else "MUFORMATION_SMALL_CITY_ATTACK_FORCE"
            AiOperationType.CITY_STATE_ATTACK -> if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_CITY_STATE_ATTACK_FORCE"
            // never asked: a save's retired operations are dropped when it loads
            AiOperationType.DESTROY_BARBARIAN_CAMP, AiOperationType.NAVAL_BOMBARDMENT -> "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.PILLAGE_ENEMY -> "MUFORMATION_FAST_PILLAGERS"
            AiOperationType.CITY_CLOSE_DEFENSE -> "MUFORMATION_CLOSE_CITY_DEFENSE"
            AiOperationType.RAPID_RESPONSE -> "MUFORMATION_RAPID_RESPONSE_FORCE"
            AiOperationType.NAVAL_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK -> "MUFORMATION_NAVAL_INVASION"
            AiOperationType.CITY_STATE_NAVAL_ATTACK -> "MUFORMATION_CITY_STATE_INVASION"
            AiOperationType.NAVAL_SUPERIORITY -> "MUFORMATION_NAVAL_SQUADRON"
            AiOperationType.PURE_NAVAL_CITY_ATTACK -> "MUFORMATION_PURE_NAVAL_CITY_ATTACK"
            AiOperationType.NUKE_ATTACK -> "MUFORMATION_NUKE_ATTACK"
        }
        return if (civ.ai.operations.formationDef(name) != null) name else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
    }

    /**
     *  CvAIOperation::GetOperationStartCity: the city the operation starts at - the one at its start city plot, else the capital, else our
     *  biggest city on the landmass with the most of our and the enemy's cities together.
     */
    fun startCity(civ: Civilization, operation: AiOperation): City? {
        if (operation.type.allNaval) return NavalOperationLogic.startCity(civ, operation) // CvAINavalOperation::GetOperationStartCity
        operation.startCityId?.let { id -> civ.cities.firstOrNull { it.id == id }?.let { return it } }
        civ.getCapital(firstCityIfNoCapital = false)?.let { return it }
        val enemy = enemy(civ, operation)
        var bestArea = -1
        var bestTotal = 0
        for (area in civ.cities.map { it.getCenterTile().getContinent() }.distinct()) {
            val mine = civ.cities.count { it.getCenterTile().getContinent() == area }
            var theirs = 0
            if (enemy != null && !enemy.isBarbarian) {
                theirs = enemy.cities.count { it.getCenterTile().getContinent() == area }
                if (theirs == 0) continue
            }
            if (mine + theirs > bestTotal) { bestTotal = mine + theirs; bestArea = area }
        }
        if (bestArea == -1) return null
        return civ.cities.filter { it.getCenterTile().getContinent() == bestArea }.maxByOrNull { it.population.population }
    }

    private fun logStart(civ: Civilization, operation: AiOperation) {
        civ.ai.operations.statsLaunched++
        AiDecisionLog.log(civ) {
            val army = operation.firstArmy
            "operation ${operation.type} #${operation.id} started: target ${operation.targetTile}, muster ${operation.musterTile}, " +
                "state ${operation.state}, formation ${army?.formation} filled ${army?.numSlotsFilled()}/${army?.slots?.size}, " +
                "to build ${operation.slotsToBuild.size}, enemy ${operation.enemyCivId}"
        }
    }

    private fun abort(operation: AiOperation, reason: OperationAbortReason) = abortOperation(operation, reason)

    /** CvAIOperation::SetToAbort */
    fun abortOperation(operation: AiOperation, reason: OperationAbortReason) {
        operation.state = OperationState.ABORTED
        operation.abortReason = reason
    }
    //endregion

    //region Init (CvPlayer::addAIOperation)

    /**
     *  CvPlayer::addAIOperation for the operations of the military AI: creates the operation with its army, finds its target and muster,
     *  recruits what it can from the reserves and sets its state. The operation is returned even when it aborted at once (state ABORTED;
     *  the original removes it in the next sweep, [doTurn]).
     *  @param target the city to attack, [muster] our city it starts from (city attacks); [defend] only [enemy] for the other types
     */
    fun addOperation(civ: Civilization, type: AiOperationType, enemy: Civilization? = null, target: City? = null, muster: City? = null): AiOperation {
        val operations = civ.ai.operations
        val operation = operations.newOperation(type, enemy)
        when {
            type.isCityAttack -> initCityAttack(civ, operation, target, muster)
            type == AiOperationType.PILLAGE_ENEMY -> initEnemyTerritory(civ, operation)
            type.isNavalInvasion -> NavalOperationLogic.initNavalInvasion(civ, operation, target, muster)
            type == AiOperationType.PURE_NAVAL_CITY_ATTACK -> NavalOperationLogic.initPureNavalAttack(civ, operation, target, muster)
            type == AiOperationType.NAVAL_SUPERIORITY -> NavalOperationLogic.initFleet(civ, operation)
            type == AiOperationType.NUKE_ATTACK -> NavalOperationLogic.initNuke(civ, operation)
            else -> initDefense(civ, operation)
        }
        if (operation.state != OperationState.ABORTED) logStart(civ, operation)
        else AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id} aborted at start: ${operation.abortReason}" }
        return operation
    }

    /** CvAIOperationBasicCityAttack::Init (also Sneak, Small, CityState and the naval attacks) */
    private fun initCityAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val operations = civ.ai.operations
        if (muster == null) { abort(operation, OperationAbortReason.NO_MUSTER); return }
        operation.startCityId = muster.id
        val army = operations.newArmy(operation, formationName(civ, operation.type))
        if (target == null) { abort(operation, OperationAbortReason.LOST_TARGET); return }
        val targetTile = target.getCenterTile()
        val musterTile = muster.getCenterTile()
        operation.targetTile = targetTile.position
        army.goalTile = targetTile.position
        operation.musterTile = musterTile.position
        army.position = musterTile.position
        operation.area = musterTile.getContinent()
        // A few plots shy of the target
        val deploy = civ.ai.military.steps.xPlotsFromEnd(civ, enemy(civ, operation), musterTile, targetTile, deployRange(civ, operation) / 2)
        if (deploy == null) { abort(operation, OperationAbortReason.LOST_PATH); return }
        army.goalTile = deploy.position
        startRecruiting(civ, operation)
    }

    /**
     *  CvAIOperation::SetUpArmy's recruiting: the army is built from the reserves ([grabUnitsFromTheReserves]); one with enough units
     *  gathers. An army that found no unit does not abort here (SetUpArmy's NO_UNITS belongs to the creation of operations, which
     *  checks the units at hand first: [MilitaryOperations]).
     */
    private fun startRecruiting(civ: Civilization, operation: AiOperation) {
        operation.state = OperationState.RECRUITING_UNITS
        grabUnitsFromTheReserves(civ, operation)
        finishRecruitingIfDone(civ, operation)
    }

    /** CvAIEnemyTerritoryOperation::Init (pillage) */
    private fun initEnemyTerritory(civ: Civilization, operation: AiOperation) {
        val operations = civ.ai.operations
        val army = operations.newArmy(operation, formationName(civ, operation.type))
        val target = findBestTarget(civ, operation)
        if (target == null) { abort(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.position
        army.goalTile = target.position
        val muster = selectInitialMusterPoint(civ, operation, army)
        if (muster == null) { abort(operation, OperationAbortReason.NO_MUSTER); return }
        operation.musterTile = muster.position
        army.position = muster.position
        operation.area = muster.getContinent()
        if (operation.area == target.getContinent()) {
            val deploy = civ.ai.military.steps.xPlotsFromEnd(civ, enemy(civ, operation), muster, target, deployRange(civ, operation) / 2)
            if (deploy == null) { abort(operation, OperationAbortReason.LOST_PATH); return }
            army.goalTile = deploy.position
        }
        startRecruiting(civ, operation)
    }

    /** CvAIOperationCityCloseDefense::Init / CvAIOperationRapidResponse::Init: gather at the point to defend */
    private fun initDefense(civ: Civilization, operation: AiOperation) {
        val army = civ.ai.operations.newArmy(operation, formationName(civ, operation.type))
        val target = findBestTarget(civ, operation) ?: run { abort(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.position
        army.goalTile = target.position
        operation.musterTile = target.position // gather directly at the point we're trying to defend
        army.position = target.position
        operation.area = target.getContinent()
        startRecruiting(civ, operation)
    }

    /** The target of the operation types that look for one themselves (the FindBestTarget functions) */
    fun findBestTarget(civ: Civilization, operation: AiOperation): Tile? = when (operation.type) {
        AiOperationType.PILLAGE_ENEMY -> findPillageTarget(civ, operation)
        AiOperationType.CITY_CLOSE_DEFENSE, AiOperationType.RAPID_RESPONSE -> findDefenseTarget(civ, operation)
        else -> operation.targetTile?.let { tileAt(civ, it) }
    }

    /** CvAIOperationCityCloseDefense/RapidResponse::FindBestTarget: the most threatened city, else our city nearest the enemy capital */
    private fun findDefenseTarget(civ: Civilization, operation: AiOperation): Tile? {
        CityThreat.mostThreatenedCity(civ)?.let { return it.getCenterTile() }
        val enemy = enemy(civ, operation) ?: return null
        val enemyCapital = enemy.getCapital() ?: return null
        return civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(enemyCapital.getCenterTile()) }?.getCenterTile()
    }

    /** CvAIOperationPillageEnemy::FindBestTarget: the enemy city on the start city's landmass with the most improved tiles per step */
    private fun findPillageTarget(civ: Civilization, operation: AiOperation): Tile? {
        val enemy = enemy(civ, operation)?.takeIf { !it.isDefeated() } ?: return null
        val start = startCity(civ, operation)?.getCenterTile() ?: return null
        val cities = enemy.cities.filter { it.getCenterTile().getContinent() == start.getContinent() }
        if (cities.isEmpty()) return null
        val lengths = civ.ai.military.steps.distances(civ, enemy, start, cities.map { it.getCenterTile() })
        var best: City? = null
        var bestValue = 0
        for ((index, city) in cities.withIndex()) {
            var value = city.getWorkableTiles().count { it.improvement != null && it.getOwner() == enemy }
            if (lengths[index] > 0) value = value * 100 / lengths[index]
            if (value > bestValue) { bestValue = value; best = city }
        }
        return best?.getCenterTile()
    }

    /**
     *  CvAIEnemyTerritoryOperation::SelectInitialMusterPoint: on another landmass than the goal the start city; else walking back from the goal
     *  along the step path the first tile of ours, or the deploy point when less than AI_OPERATIONAL_PERCENT_DANGER_FOR_FORWARD_MUSTER percent
     *  of the tiles on the way are dangerous.
     */
    private fun selectInitialMusterPoint(civ: Civilization, operation: AiOperation, army: AiArmy): Tile? {
        val start = startCity(civ, operation)?.getCenterTile() ?: return null
        val goal = tileAt(civ, army.goalTile) ?: return null
        if (start.getContinent() != goal.getContinent()) return start
        val path = civ.ai.military.steps.path(civ, enemy(civ, operation), start, goal) ?: return null
        val range = deployRange(civ, operation)
        val danger = civ.ai.plotDanger
        var spaces = 0
        var deploy: Tile? = null
        var dangerous = 0
        var muster: Tile? = null
        for (index in path.indices.reversed()) {
            val tile = path[index]
            if (spaces == range) deploy = tile
            if (tile.getOwner() == civ) { muster = tile; break }
            if (danger.get(tile) > 0) dangerous++
            spaces++
        }
        val limit = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_PERCENT_DANGER_FOR_FORWARD_MUSTER)
        if (spaces > 0 && dangerous * 100 / spaces < limit && deploy != null) muster = deploy
        return muster
    }

    /**
     *  CvAIOperationFoundCity::Init and CvAIOperationQuickColonize::Init: the settler [civilian] (a loose settler of ours) goes to the best
     *  site of [targetArea] (-1 = anywhere), with an escort recruited from the reserves if possible; with none a player with more than one
     *  city or boldness over 5 sends it alone to the best site found with the safe-path search, else the operation recruits the escort.
     */
    fun addSettlerOperation(civ: Civilization, type: AiOperationType, civilian: MapUnit, targetArea: Int): AiOperation {
        val operations = civ.ai.operations
        val operation = operations.newOperation(type, null)
        operation.targetArea = targetArea
        val target = findSettleTarget(civ, operation, civilian, onlySafePaths = false)
        val army = operations.newArmy(operation, formationName(civ, type))
        if (target == null) { abort(operation, OperationAbortReason.LOST_TARGET); return operation }
        operation.targetTile = target.position
        army.goalTile = target.position
        val musterTile = civilian.getTile()
        operation.musterTile = musterTile.position
        army.position = musterTile.position
        operation.area = musterTile.getContinent()
        operations.addUnit(army, civilian, 0)

        if (type == AiOperationType.QUICK_COLONIZE) {
            operation.escorted = false
            army.state = ArmyState.MOVING_TO_DESTINATION
            operation.state = OperationState.MOVING_TO_TARGET
            logStart(civ, operation)
            return operation
        }
        // FOUND_CITY: the escort is slot 1
        operation.escorted = true
        operation.slotsToBuild.clear()
        operation.slotsToBuild.add(SlotRef(operation.id, army.id, 1))
        grabUnitsFromTheReserves(civ, operation)
        if (army.numSlotsFilled() > 1) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else {
            var safe: Tile? = null
            if (civ.cities.size > 1 || civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 5)
                safe = findSettleTarget(civ, operation, civilian, onlySafePaths = true)
            if (safe == null) {
                // we'll wait it out for an escort
                operation.slotsToBuild.clear()
                operation.slotsToBuild.add(SlotRef(operation.id, army.id, 1))
                operation.state = OperationState.RECRUITING_UNITS
            } else {
                operation.escorted = false
                operation.slotsToBuild.clear()
                army.goalTile = safe.position
                operation.targetTile = safe.position
                army.state = ArmyState.MOVING_TO_DESTINATION
                operation.state = OperationState.MOVING_TO_TARGET
            }
        }
        logStart(civ, operation)
        return operation
    }

    /** CvAIOperationFoundCity::FindBestTarget / CvAIOperationQuickColonize::FindBestTarget: in the operation's area, else anywhere */
    fun findSettleTarget(civ: Civilization, operation: AiOperation, unit: MapUnit, onlySafePaths: Boolean): Tile? {
        val targeted = civ.ai.operations.settleTargets(operation)
        val safe = onlySafePaths && operation.type == AiOperationType.FOUND_CITY
        SettleAreas.bestSettlePlot(unit, safe, operation.targetArea, targeted)?.let { return it }
        operation.targetArea = -1
        return SettleAreas.bestSettlePlot(unit, safe, -1, targeted)
    }
    //endregion

    //region Recruiting from the reserves

    /**
     *  CvAIOperation::GetMaximumRecruitTurns: how long the operation waits for a recruit to show up. A military operation
     *  (CvAIOperationMilitary) waits AI_OPERATIONAL_MAX_RECRUIT_TURNS_ENEMY_TERRITORY turns; the settler operations the base class'
     *  AI_OPERATIONAL_MAX_RECRUIT_TURNS_DEFAULT, extended by half the turns since the operation started.
     */
    fun maxRecruitTurns(civ: Civilization, operation: AiOperation): Int {
        val rules = civ.gameInfo.ruleset.ai
        if (!operation.type.isEscorted) return rules.define(AiDefine.AI_OPERATIONAL_MAX_RECRUIT_TURNS_ENEMY_TERRITORY)
        return rules.define(AiDefine.AI_OPERATIONAL_MAX_RECRUIT_TURNS_DEFAULT) + (civ.gameInfo.turns - operation.createdTurn) / 2
    }

    /**
     *  CvCity::NeedsGarrison: [city] is under siege, or - outside the early expansion phase, when the garrison may escort settlers -
     *  exposed to an enemy army.
     */
    fun needsGarrison(civ: Civilization, city: City): Boolean {
        if (CityDangerAi.isUnderSiege(city)) return true
        if (civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.EARLY_EXPANSION)) return false
        return civ.ai.military.isExposedToEnemy(civ, city, null)
    }

    /**
     *  CvUnit::canUseForAIOperation: not in an army already; an ordinary civilian is always fair game (a general is not one); not the
     *  garrison of a city that needs one; not in the zone of a city that our side does not dominate on the tactical map; no enemy
     *  that can defend within 3 plots; not in a citadel near a contested border.
     */
    fun canUseForAiOperation(civ: Civilization, unit: MapUnit): Boolean {
        if (civ.ai.operations.armyOf(unit) != null) return false
        if (unit.isCivilian() && !Deployment.isGeneralOrAdmiral(unit)) return true
        val tile = unit.getTile()
        val city = tile.getCity()
        if (unit.isMilitary() && tile.isCityCenter() && city != null && city.civ == civ && needsGarrison(civ, city)) return false
        val map = civ.ai.tactical.map
        val zone = map.zoneOf(tile)
        if (zone != null && zone.closestCity != null && zone.dominance != TacticalDominance.Friendly) return false
        for (nearby in tile.getTilesInDistance(NEARBY_ENEMY_RANGE))
            if (nearby.getUnits().any { it.civ != civ && civ.isAtWarWith(it.civ) && TacticalSafety.canDefend(it) }) return false
        return !(map.isPlayerCitadel(tile, civ) && isCloseToContestedBorder(civ, map, tile))
    }

    /**
     *  TacticalAIHelpers::IsCloseToContestedBorder: a land tile two plots away is owned by someone we are at war with, and its zone is
     *  not one we dominate.
     */
    private fun isCloseToContestedBorder(civ: Civilization, map: TacticalMap, tile: Tile): Boolean = tile.getTilesAtDistance(2).any { other ->
        val owner = other.getOwner()
        owner != null && civ.isAtWarWith(owner) && !other.isWater && map.zoneOf(other)?.dominance != TacticalDominance.Friendly
    }

    /**
     *  CvUnit::shouldHeal(false): a hurt unit that can heal at all heals when next to an enemy unit or city only if the danger of its plot
     *  exceeds its health plus what it heals; else when its damage is above 20 (50 if it fights well damaged, or cannot heal in our
     *  capital). Departure: the original also asks whether the unit took more damage last turn than it has health left, which is not recorded.
     */
    private fun shouldHeal(civ: Civilization, unit: MapUnit): Boolean {
        if (unit.health >= MAX_HEALTH || unit.hasUnique(UniqueType.HealOnlyByPillaging, checkCivInfoUniques = true)) return false
        val tile = unit.getTile()
        val enemyAdjacent = tile.neighbors.any { neighbor ->
            neighbor.getUnits().any { it.civ != civ && civ.isAtWarWith(it.civ) } ||
                (neighbor.isCityCenter() && neighbor.getOwner()?.let { civ.isAtWarWith(it) } == true)
        }
        if (enemyAdjacent) return civ.ai.plotDanger.get(tile) > unit.health + max(0, unit.healingAmountForTile(tile, noTerrainDamage = true))
        val capital = civ.getCapital(firstCityIfNoCapital = false)
        val maxHealRate = when {
            capital == null -> unit.healingAmountForTile(tile, noTerrainDamage = true)
            unit.baseUnit.isWaterUnit && !capital.isCoastal() -> 0
            else -> unit.healingAmountForTile(capital.getCenterTile(), noTerrainDamage = true)
        }
        val allowMoreDamage = civ.isBarbarian || unit.hasUnique(UniqueType.NoDamagePenaltyWoundedUnits, checkCivInfoUniques = true)
        val acceptableDamage = if (allowMoreDamage || maxHealRate <= 0) 50 else 20
        return MAX_HEALTH - unit.health > acceptableDamage
    }

    /**
     *  CvUnit::shouldHeal(true): the unit leaves its army to heal when badly hurt - below 13 health always, below a third if it can heal
     *  where it is and has nothing to attack.
     */
    private fun shouldHealBeforeAttacks(unit: MapUnit): Boolean {
        if (unit.health >= MAX_HEALTH || unit.hasUnique(UniqueType.HealOnlyByPillaging, checkCivInfoUniques = true)) return false
        if (unit.health < HARD_HEALTH_LIMIT) return true
        return unit.health < MAX_HEALTH / 3 && unit.getHealAmountForCurrentTile() > 0 &&
            TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()).isEmpty()
    }

    /**
     *  Whether [unit] is free for an operation in general (the counting of the fillable slots in [MilitaryOperations] shares it with the
     *  recruiting): [isSuitableForRecruitment] with no target and no deep water.
     */
    fun isFreeForOperation(civ: Civilization, unit: MapUnit, naval: Boolean): Boolean =
        isSuitableForRecruitment(civ, unit, naval, target = null, mustBeDeepWater = false)

    /**
     *  OperationalAIHelpers::IsUnitSuitableForRecruitment without its muster reach and slot match ([grabUnitsFromTheReserves] tests those):
     *  not exploring or settling (own unit AI), no paratrooper (the tactical AI drops them on pillage targets), at least
     *  AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION health, not deployed from an operation within the last AI_TACTICAL_MAP_TEMP_ZONE_TURNS
     *  turns, not already within less than 4 plots of [target], no ship that cannot enter the ocean if [mustBeDeepWater], a land unit able to
     *  embark if [mustEmbark], [canUseForAiOperation] and not [shouldHeal].
     */
    private fun isSuitableForRecruitment(civ: Civilization, unit: MapUnit, mustEmbark: Boolean, target: Tile?, mustBeDeepWater: Boolean): Boolean {
        val unitAi = UnitAiTypes.current(unit)
        if (unitAi == UnitAiType.EXPLORE || unitAi == UnitAiType.EXPLORE_SEA || unitAi == UnitAiType.SETTLE) return false
        if (unit.hasUnique(UniqueType.MayParadrop)) return false
        val operations = civ.ai.operations
        if (unit.health < operations.minHealth()) return false
        if (operations.deployFromOperationTurn(unit.id) + civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS) >= civ.gameInfo.turns) return false
        if (target != null && unit.getTile().aerialDistanceTo(target) < MAX_TARGET_DISTANCE) return false
        if (mustBeDeepWater && unit.baseUnit.isWaterUnit && unit.cache.cannotEnterOceanTiles) return false
        if (mustEmbark && !unit.baseUnit.isWaterUnit && (unit.cache.cannotEmbark || !civ.tech.unitsCanEmbark)) return false
        return canUseForAiOperation(civ, unit) && !shouldHeal(civ, unit)
    }

    /** A free slot of an army being built, with its formation entry */
    private class OpenSlot(val army: AiArmy, val index: Int, val def: FormationSlotDef)

    /** Whether [unit] fits the formation entry [def]: its unit AI, or what its type may take, is the entry's primary or secondary one */
    private fun slotFits(civ: Civilization, unit: MapUnit, def: FormationSlotDef): Boolean {
        val rules = civ.gameInfo.ruleset.ai
        val current = UnitAiTypes.current(unit)
        val primary = UnitAiType.ofXmlName(def.primaryUnitAi)
        if (primary != null && (current == primary || UnitAiTypes.canHave(unit.baseUnit, primary, rules))) return true
        val secondary = UnitAiType.ofXmlName(def.secondaryUnitAi)
        return secondary != null && (current == secondary || UnitAiTypes.canHave(unit.baseUnit, secondary, rules))
    }

    /**
     *  GrabUnitsFromTheReserves' "simple heuristic" bOcean: the muster point and the target are on different landmasses - for a naval
     *  operation the original has moved both to the coastal water before, so the bodies of water next to them (Vox Populi has dropped
     *  BNW's RequiresNavalUnitConsistency for this test). Ships that cannot enter the ocean are no recruits then.
     */
    private fun isAcrossOcean(civ: Civilization, muster: Tile, target: Tile?, naval: Boolean): Boolean {
        if (target == null) return false
        fun placeOf(tile: Tile): Tile? = if (!naval || tile.isWater) tile else tile.neighbors.firstOrNull { it.isWater }
        val map = civ.ai.tactical.map
        val from = placeOf(muster) ?: return false
        val to = placeOf(target) ?: return false
        return map.areaOf(from) != map.areaOf(to)
    }

    /**
     *  CvPlayer::GetUnfriendlyMajors: the living majors with cities that a major sees as a threat or target
     *  ([ApproachPart6Ai.isPotentialMilitaryTargetOrThreat]), all of them for a barbarian; a city-state's are those it is at war with (the
     *  original adds those it is wary of or that were jerks to it).
     */
    private fun unfriendlyMajors(civ: Civilization): List<Civilization> = civ.gameInfo.civilizations.filter { other ->
        other != civ && other.isMajorCiv && other.isAlive() && other.cities.isNotEmpty() && when {
            civ.isMajorCiv -> ApproachPart6Ai.isPotentialMilitaryTargetOrThreat(civ, other)
            civ.isBarbarian -> true
            else -> civ.isAtWarWith(other)
        }
    }

    /**
     *  CvPlot::IsBorderLand with the unfriendly majors given: a city of one of them within AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT plots,
     *  or a passable neighbour plot owned by one (by anyone, for a barbarian).
     */
    private fun isBorderLand(civ: Civilization, tile: Tile, unfriendly: List<Civilization>): Boolean {
        val range = civ.gameInfo.ruleset.ai.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        if (unfriendly.any { other -> other.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= range } }) return true
        return tile.neighbors.any { neighbor ->
            val owner = neighbor.getOwner()
            owner != null && owner != civ && !neighbor.isImpassible() && (civ.isBarbarian || owner in unfriendly)
        }
    }

    /**
     *  OperationalAIHelpers::HaveEnoughUnits: [army] has at least one required unit, and the required slots still empty are made up
     *  for by the optional units at hand (two make up for one) and [maxMissingUnits].
     */
    fun haveEnoughUnits(civ: Civilization, army: AiArmy, maxMissingUnits: Int): Boolean {
        val defs = civ.ai.operations.slotDefs(army)
        var freeRequired = 0
        var presentRequired = 0
        var presentOptional = 0
        for ((index, slot) in army.slots.withIndex()) {
            val required = defs.getOrNull(index)?.required == true
            val used = slot.unitId != -1
            if (used && required) presentRequired++
            else if (used) presentOptional++
            else if (required) freeRequired++
        }
        return presentRequired > 0 && freeRequired <= presentOptional / 2 + maxMissingUnits
    }

    /**
     *  CvAIOperation::CheckTransitionToNextStage while recruiting: an operation with enough units starts gathering ([haveEnoughUnits]; a
     *  settler operation as soon as it has its escort, which its formation has optional in the BNW data). True if it did.
     */
    fun finishRecruitingIfDone(civ: Civilization, operation: AiOperation): Boolean {
        if (operation.state != OperationState.RECRUITING_UNITS) return false
        val army = operation.firstArmy ?: return false
        val enough = if (operation.type.isEscorted) army.slots.getOrNull(1)?.unitId?.let { it != -1 } == true else haveEnoughUnits(civ, army, 0)
        if (!enough) return false
        for (each in operation.armies) each.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
        operation.state = OperationState.GATHERING_FORCES
        return true
    }

    /**
     *  CvAIOperation::GrabUnitsFromTheReserves: every open slot of the armies being built (a settler operation's: the ones still to build)
     *  is filled from the reserves. A unit is a candidate if it fits an open slot, is suitable ([isSuitableForRecruitment], with the
     *  operation's target, and ships must be able to cross the ocean when the muster point and the target are on different landmasses), is
     *  near enough to the muster point and gets there within [maxRecruitTurns]. Candidates are ranked 10000 + power (CvUnit::GetPower) - 30
     *  per turn to the muster point (one more outside our territory, one more for a land unit on another landmass), +42 for a land unit
     *  guarding a citadel on the border of an unfriendly major. The source comment says to prefer other units, but its descending sort
     *  applies this as a positive bonus. Open slots, in order, take the best candidate that fits. A slot left open that is required
     *  stays on the list of units still to build, an optional one is given up until the next time.
     *  @return whether the army now has enough units ([haveEnoughUnits])
     */
    fun grabUnitsFromTheReserves(civ: Civilization, operation: AiOperation): Boolean {
        val operations = civ.ai.operations
        val army = operation.firstArmy ?: return false
        val muster = musterTile(civ, operation) ?: return false
        val target = targetTile(civ, operation)
        val escorted = operation.type.isEscorted

        val open = ArrayList<OpenSlot>()
        if (escorted) {
            for (ref in operation.slotsToBuild) {
                val owner = operation.armyById(ref.armyId) ?: continue
                val def = operations.slotDefs(owner).getOrNull(ref.slotId) ?: continue
                if (owner.slots.getOrNull(ref.slotId)?.unitId == -1) open.add(OpenSlot(owner, ref.slotId, def))
            }
        } else {
            val committed = operation.committedCities.map { it.slot }
            for (owner in operation.armies) {
                if (owner.state != ArmyState.WAITING_FOR_UNITS_TO_REINFORCE) continue
                val defs = operations.slotDefs(owner)
                for (index in owner.slots.indices) {
                    if (owner.slots[index].unitId != -1 || SlotRef(operation.id, owner.id, index) in committed) continue
                    defs.getOrNull(index)?.let { open.add(OpenSlot(owner, index, it)) }
                }
            }
        }

        val naval = operation.type.mixedLandNaval || operation.type.allNaval
        // see the class comment: only the operations the military AI launches leave the units near their target alone
        val type = operation.type
        val closeTarget = if (type.isCityAttack || type == AiOperationType.PILLAGE_ENEMY || type.isNavalInvasion || type == AiOperationType.PURE_NAVAL_CITY_ATTACK) target else null
        val crossesOcean = isAcrossOcean(civ, muster, target, naval)
        val maxTurns = maxRecruitTurns(civ, operation)
        var unfriendly: List<Civilization>? = null
        val choices = ArrayList<Pair<MapUnit, Int>>()
        if (open.isNotEmpty()) for (unit in civ.units.getCivUnits()) {
            if (open.none { slotFits(civ, unit, it.def) }) continue
            val tile = unit.getTile()
            if (tile.aerialDistanceTo(muster) > maxTurns * RECRUIT_PLOTS_PER_TURN) continue
            if (!isSuitableForRecruitment(civ, unit, naval, closeTarget, crossesOcean)) continue
            var turns = turnsToReach(unit, muster)
            if (turns > maxTurns) continue
            // When in doubt prefer units in our own territory ...
            if (tile.getOwner() != civ) turns++
            // ... and units which don't have to pass through a chokepoint
            if (unit.baseUnit.isLandUnit && tile.getContinent() != muster.getContinent()) turns++
            // The source adds 42 here, despite its comment about preferring units not guarding the border.
            var extra = 0
            if (unit.baseUnit.isLandUnit && civ.ai.tactical.map.isPlayerCitadel(tile, civ)) {
                val majors = unfriendly ?: unfriendlyMajors(civ).also { unfriendly = it }
                if (majors.isNotEmpty() && isBorderLand(civ, tile, majors)) extra = CITADEL_GUARD_SCORE
            }
            choices.add(unit to BASE_RECRUIT_SCORE + unit.getAiPower() + extra - turns * RECRUIT_SCORE_PER_TURN)
        }
        choices.sortByDescending { it.second }

        // rebuild the list of what is still to build
        val pending = ArrayList<SlotRef>()
        for (slot in open) {
            val pick = choices.indexOfFirst { slotFits(civ, it.first, slot.def) }
            if (pick >= 0) {
                // remove it so we don't use it a second time
                operations.addUnit(slot.army, choices.removeAt(pick).first, slot.index)
            } else if (escorted || slot.def.required) pending.add(SlotRef(operation.id, slot.army.id, slot.index))
            else operations.setEstimatedTurn(slot.army, slot.index, ArmySlot.NOT_INCLUDED)
        }
        operation.slotsToBuild.clear()
        operation.slotsToBuild.addAll(pending)
        return haveEnoughUnits(civ, army, 0)
    }

    /**
     *  What CvAIOperation::Move does before the army moves, for every operation of [civ] that is not over: an army still waiting for units
     *  takes what the reserves have now ([grabUnitsFromTheReserves]), then [updateCheckpointTurnsAndRemoveBadUnits]. The settlers'
     *  operations keep their meeting-point movement (no progress test), and the operations the tactical AI does not move have no
     *  progress to judge.
     */
    fun continueRecruiting(civ: Civilization) {
        for (operation in civ.ai.operations.operations.toList()) {
            if (operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) continue
            val army = operation.firstArmy ?: continue
            if (operation.state == OperationState.RECRUITING_UNITS && army.state == ArmyState.WAITING_FOR_UNITS_TO_REINFORCE)
                grabUnitsFromTheReserves(civ, operation)
            if (!operation.type.isEscorted && operation.type.moveType != OperationMoveType.None)
                updateCheckpointTurnsAndRemoveBadUnits(civ, operation, army)
        }
    }

    /** CvArmyAI::GetCurrentPlot: where the army's units are to be: the muster point until the army moves, then its center of mass */
    private fun checkpointTile(civ: Civilization, operation: AiOperation, army: AiArmy): Tile? = when (army.state) {
        ArmyState.WAITING_FOR_UNITS_TO_REINFORCE, ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> musterTile(civ, operation)
        ArmyState.MOVING_TO_DESTINATION -> centerOfMass(civ, army, operation.type.allNaval || operation.type.mixedLandNaval)
    }

    /**
     *  CvArmyAI::UpdateCheckpointTurnsAndRemoveBadUnits: an offensive operation (CvAIOperation::IsOffensive) drops the units that are
     *  [shouldHealBeforeAttacks]; every unit not within the gather tolerance of the army's checkpoint ([checkpointTile]) records the turns it
     *  needs to get there ([ArmySlot.checkpointTurns], [Int.MAX_VALUE] if it needs more than [maxRecruitTurns]), and one that has made no
     *  progress over the last three records ([ArmySlot.isMakingProgress]) is dropped. Generals and admirals are not judged: they follow the
     *  army by their own scoring ([centerOfMass]).
     */
    private fun updateCheckpointTurnsAndRemoveBadUnits(civ: Civilization, operation: AiOperation, army: AiArmy) {
        val operations = civ.ai.operations
        if (operations.isOffensive(operation)) {
            for ((_, unit) in armyUnits(civ, army)) {
                if (!shouldHealBeforeAttacks(unit)) continue
                AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id}: ${unit.name} leaves the army to heal" }
                operations.removeFromArmy(unit)
            }
        }
        val checkpoint = checkpointTile(civ, operation, army) ?: return
        val tolerance =
            if (operation.type.allNaval || operation.type.mixedLandNaval) NavalOperationLogic.gatherTolerance(civ, operation, army)
            else gatherTolerance(civ, army)
        val maxTurns = maxRecruitTurns(civ, operation)
        for ((index, unit) in armyUnits(civ, army)) {
            if (Deployment.isGeneralOrAdmiral(unit)) continue
            val slot = army.slots[index]
            if (unit.getTile().aerialDistanceTo(checkpoint) < tolerance) {
                slot.pushCheckpointTurns(0)
                continue
            }
            val turns = turnsToReach(unit, checkpoint)
            slot.pushCheckpointTurns(if (turns > maxTurns) Int.MAX_VALUE else turns)
            if (slot.isMakingProgress()) continue
            AiDecisionLog.log(civ) {
                "operation ${operation.type} #${operation.id}: ${unit.name} leaves the army, no progress to the checkpoint ${checkpoint.position}: ${slot.checkpointTurns}"
            }
            operations.removeFromArmy(unit)
        }
    }
    //endregion

    //region State machine

    /**
     *  CvAIOperation::CheckTransitionToNextStage after the army moved: an army with enough units starts gathering
     *  ([finishRecruitingIfDone]), armies that have gathered move out, those at the target hand over. True if the state changed.
     */
    fun checkOnTarget(civ: Civilization, operation: AiOperation): Boolean {
        val recruited = finishRecruitingIfDone(civ, operation)
        val inPosition = checkInPosition(civ, operation)
        return recruited || inPosition
    }

    /** The gathering and moving cases of [checkOnTarget] */
    private fun checkInPosition(civ: Civilization, operation: AiOperation): Boolean {
        val army = operation.firstArmy ?: return false
        when (operation.type.moveType) {
            OperationMoveType.SingleHex -> {
                if (army.numSlotsFilled() < 1) return false
                val units = armyUnits(civ, army)
                val civilian = units.firstOrNull { it.first == 0 }?.second
                val civilianTile = civilian?.getTile()
                if (operation.state == OperationState.MOVING_TO_TARGET && civilianTile != null && civilianTile.position == operation.targetTile) {
                    armyInPosition(civ, operation, army)
                    return true
                }
                if (operation.state == OperationState.GATHERING_FORCES) {
                    if (army.numSlotsFilled() == 1) { armyInPosition(civ, operation, army); return true }
                    val escortTile = units.firstOrNull { it.first != 0 }?.second?.getTile()
                    if (civilianTile != null && civilianTile == escortTile) { armyInPosition(civ, operation, army); return true }
                }
            }
            OperationMoveType.EnemyTerritory -> for (a in operation.armies.toList()) {
                if (a.numSlotsFilled() < 1) continue
                val com = centerOfMass(civ, a) ?: continue
                when (operation.state) {
                    OperationState.GATHERING_FORCES -> {
                        val muster = musterTile(civ, operation) ?: continue
                        val tolerance = gatherTolerance(civ, a)
                        if (com.aerialDistanceTo(muster) <= tolerance && furthestUnitDistance(civ, a, muster) <= tolerance * 3 / 2) {
                            armyInPosition(civ, operation, a)
                            return true
                        }
                    }
                    OperationState.MOVING_TO_TARGET -> {
                        val target = targetTile(civ, operation) ?: continue
                        val tolerance = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
                        if (com.aerialDistanceTo(target) <= tolerance && furthestUnitDistance(civ, a, target) <= tolerance * 3 / 2) {
                            armyInPosition(civ, operation, a)
                            return true
                        }
                    }
                    else -> Unit
                }
            }
            OperationMoveType.NavalEscort, OperationMoveType.FreeformNaval, OperationMoveType.NavalFleet -> return NavalOperationLogic.checkOnTarget(civ, operation)
            OperationMoveType.None -> Unit
        }
        return false
    }

    /** CvAIOperation::GetGatherTolerance for land armies: the gather range for the number of units (OperationalAIHelpers::GetGatherRangeForXUnits) */
    fun gatherTolerance(civ: Civilization, army: AiArmy): Int = if (army.numSlotsFilled() < 1) 0 else gatherRangeForXUnits(army.numSlotsFilled())

    /** OperationalAIHelpers::GetGatherRangeForXUnits */
    fun gatherRangeForXUnits(units: Int) = when {
        units <= 2 -> 1
        units <= 6 -> 2
        units <= 10 -> 3
        else -> 4
    }

    /**
     *  CvArmyAI::GetCenterOfMass: the average position of the army's units (nudged to land or sea - always sea for [sea] - if it falls
     *  elsewhere). Departure, recorded (D7), as in [furthestUnitDistance]: generals and admirals are left out (an army of them alone uses
     *  them). The original counts a general placed by its own scoring; one left far behind pulled the center back each turn, and an army of
     *  two infantry and a general 40 tiles behind them marched in place for 365 turns, blocking every other sneak attack.
     */
    fun centerOfMass(civ: Civilization, army: AiArmy, sea: Boolean = false): Tile? {
        val all = armyUnits(civ, army).map { it.second }
        val units = all.filter { !Deployment.isGeneralOrAdmiral(it) }.ifEmpty { all }
        if (units.isEmpty()) return null
        val map = civ.gameInfo.tileMap
        val reference = units[0].getTile().position
        var sumX = 0
        var sumY = 0
        for (unit in units) {
            var position = unit.getTile().position
            if (map.mapParameters.worldWrap) {
                val unwrapped = map.getUnwrappedPosition(position)
                if (HexMath.getDistance(unwrapped, reference) < HexMath.getDistance(position, reference)) position = unwrapped
            }
            sumX += position.x; sumY += position.y
        }
        val averageX = (sumX + units.size / 2) / units.size
        val averageY = (sumY + units.size / 2) / units.size
        val center = map.getOrNull(averageX, averageY) ?: return units[0].getTile()
        val wantWater = sea || units.all { it.baseUnit.isWaterUnit }
        if (center.isWater == wantWater) return center
        return center.neighbors.firstOrNull { it.isWater == wantWater } ?: units[0].getTile()
    }

    /**
     *  CvArmyAI::GetFurthestUnitDistance, without the army's generals and admirals. Departure, recorded (D7): CvTacticalAI::MoveGreatGeneral
     *  places them by ScoreGreatGeneralPlot, which is the same for every tile within 3 of the army's center and prefers a friendly city, so a
     *  general can settle in a city 3 from the center and 4 from the muster point; the original counts it here and the gathering never ends
     *  (a Domination game: every sneak attack of the leader stuck at its muster). They follow the army by their own scoring.
     */
    fun furthestUnitDistance(civ: Civilization, army: AiArmy, tile: Tile): Int =
        armyUnits(civ, army).filter { !Deployment.isGeneralOrAdmiral(it.second) }.maxOfOrNull { it.second.getTile().aerialDistanceTo(tile) } ?: 0

    /** CvAIOperation::ArmyInPosition and the overrides of the subclasses; true if the state changed */
    fun armyInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val type = operation.type
        return when {
            type.isNavalInvasion -> NavalOperationLogic.navalInvasionInPosition(civ, operation, army)
            type == AiOperationType.NAVAL_SUPERIORITY -> NavalOperationLogic.superiorityInPosition(civ, operation, army)
            type == AiOperationType.PURE_NAVAL_CITY_ATTACK -> NavalOperationLogic.pureNavalAttackInPosition(civ, operation, army)
            type == AiOperationType.NUKE_ATTACK -> NavalOperationLogic.nukeInPosition(civ, operation, army)
            type.isCityAttack -> cityAttackInPosition(civ, operation, army)
            type.isEscorted -> escortedInPosition(civ, operation, army)
            type == AiOperationType.PILLAGE_ENEMY -> {
                if (operation.state == OperationState.MOVING_TO_TARGET) {
                    if (army.position == army.goalTile) operation.state = OperationState.SUCCESSFUL_FINISH
                    false
                } else baseArmyInPosition(operation, army)
            }
            type == AiOperationType.RAPID_RESPONSE ->
                if (operation.state == OperationState.MOVING_TO_TARGET) { retargetDefensiveArmy(civ, operation, army); false }
                else baseArmyInPosition(operation, army)
            else -> baseArmyInPosition(operation, army)
        }
    }

    internal fun baseArmyInPosition(operation: AiOperation, army: AiArmy): Boolean {
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                operation.state = OperationState.MOVING_TO_TARGET
                army.state = ArmyState.MOVING_TO_DESTINATION
                return true
            }
            OperationState.MOVING_TO_TARGET -> if (army.position == operation.targetTile) {
                operation.state = OperationState.AT_TARGET
                return true
            }
            else -> Unit
        }
        return false
    }

    /** CvAIOperationBasicCityAttack::ArmyInPosition */
    private fun cityAttackInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val target = targetTile(civ, operation)
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                val changed = baseArmyInPosition(operation, army)
                // Is target still under enemy control?
                if (target?.getOwner()?.civID != operation.enemyCivId) abort(operation, OperationAbortReason.TARGET_ALREADY_CAPTURED)
                return changed
            }
            OperationState.MOVING_TO_TARGET -> {
                val com = centerOfMass(civ, army)
                val enemy = enemy(civ, operation)
                // Are we within tactical range of our target?
                var inPlace = com != null && target != null &&
                    com.aerialDistanceTo(target) <= civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
                // Vox Populi (CvAIOperation::CheckOnTarget): at peace, more than two of our units in the enemy's sight - the jig is up
                if (!inPlace && enemy != null && !civ.isAtWarWith(enemy) &&
                    armyUnits(civ, army).count { (_, unit) -> unit.getTile() in enemy.viewableTiles } > 2) inPlace = true
                if (inPlace) {
                    enemy?.let { civ.ai.diplomacy.memoryOf(it).musteringForAttack = true }
                    // Notify tactical AI to focus on this area
                    if (target != null) civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.City)
                    operation.state = OperationState.SUCCESSFUL_FINISH
                }
                return false
            }
            else -> return baseArmyInPosition(operation, army)
        }
    }

    /** CvAIOperationRapidResponse::RetargetDefensiveArmy */
    fun retargetDefensiveArmy(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val better = findBestTarget(civ, operation)
        if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return false }
        if (better.position != operation.targetTile) {
            operation.targetTile = better.position
            army.goalTile = better.position
        }
        army.state = ArmyState.MOVING_TO_DESTINATION
        operation.state = OperationState.MOVING_TO_TARGET
        return true
    }

    /** CvAIOperationFoundCity::ArmyInPosition: merge settler and escort and move out, found the city on arrival */
    private fun escortedInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val units = armyUnits(civ, army)
        val settler = units.firstOrNull { it.first == 0 }?.second
        val escort = units.firstOrNull { it.first == 1 }?.second
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                if (!operation.escorted) return baseArmyInPosition(operation, army)
                if (escort == null) { abort(operation, OperationAbortReason.ESCORT_DIED); return true }
                if (settler != null && settler.getTile() == escort.getTile()) {
                    // let's see if the target still makes sense (modified from RetargetCivilian)
                    val better = findSettleTarget(civ, operation, settler, onlySafePaths = true)
                    if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return false }
                    operation.targetTile = better.position
                    army.goalTile = better.position
                    return baseArmyInPosition(operation, army)
                }
            }
            OperationState.MOVING_TO_TARGET, OperationState.AT_TARGET -> {
                val changed = baseArmyInPosition(operation, army)
                if (settler == null) return changed
                val target = targetTile(civ, operation) ?: return changed
                val owner = target.getOwner()
                val unusable = (owner != null && owner != civ) || target.neighbors.any { val o = it.getOwner(); o != null && o != civ }
                if (unusable) {
                    retargetCivilian(civ, operation, army, settler)
                    settler.currentMovement = 0f
                    escort?.currentMovement = 0f
                } else if (settler.getTile() == target && settler.hasMovement() && SettleAreas.canFound(settler, target)) {
                    // If the settler made it, we don't care about the entire army
                    if (GameView(civ.gameInfo, civ).getMapUnitView(settler).tryInvokeUnitAction(UnitActionType.FoundCity))
                        operation.state = OperationState.SUCCESSFUL_FINISH
                } else if (settler.getTile() == target && !SettleAreas.canFound(settler, target)) {
                    // someone else beat us to this area: move back out, picking a new target
                    retargetCivilian(civ, operation, army, settler)
                    settler.currentMovement = 0f
                    escort?.currentMovement = 0f
                }
                return changed
            }
            else -> return baseArmyInPosition(operation, army)
        }
        return false
    }

    /** CvAIEscortedOperation::RetargetCivilian: look for another site (with the safe-path search if the settler is escorted) */
    fun retargetCivilian(civ: Civilization, operation: AiOperation, army: AiArmy, civilian: MapUnit): Boolean {
        val better = findSettleTarget(civ, operation, civilian, onlySafePaths = operation.escorted)
        if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return false }
        if (better.position == operation.targetTile) {
            abort(operation, OperationAbortReason.REPEAT_TARGET)
            return false
        }
        operation.targetTile = better.position
        army.goalTile = better.position
        army.state = ArmyState.MOVING_TO_DESTINATION
        operation.state = OperationState.MOVING_TO_TARGET
        return true
    }

    /**
     *  CvAIOperation::ShouldAbort with the subclasses' tests. True when the operation is to be removed (the sweep kills it): it ended, or
     *  its purpose is gone (target city taken, camp cleared or rescue over, camp stronger than our units). Units of a successful
     *  operation are marked deployed (CvUnit::SetDeployFromOperationTurn).
     *  Vox Populi's failsafe (CvAIOperation::ShouldAbort, Release-5.4.8; D7): an operation started more than [TIMEOUT_TURNS] turns ago
     *  aborts, TIMED_OUT. BNW has none, and an operation that can no longer finish (an army stuck on the way, one recruiting units that
     *  never come) blocks new ones of its kind for the rest of the game.
     */
    fun shouldAbort(civ: Civilization, operation: AiOperation): Boolean {
        val operations = civ.ai.operations
        if (operation.state == OperationState.SUCCESSFUL_FINISH)
            for (army in operation.armies) for ((_, unit) in armyUnits(civ, army)) operations.deployFromOperationTurns[unit.id] = civ.gameInfo.turns
        if (operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) return true
        if (civ.gameInfo.turns - operation.createdTurn > TIMEOUT_TURNS) {
            abort(operation, OperationAbortReason.TIMED_OUT)
            return true
        }
        val type = operation.type
        when {
            type.isCityAttack -> {
                // See if our target city is still owned by our enemy: success, the city was captured or destroyed
                val target = targetTile(civ, operation)
                if (target == null || target.getCity() == null || target.getOwner()?.civID != operation.enemyCivId) return true
            }
            type == AiOperationType.PURE_NAVAL_CITY_ATTACK -> {
                // See if our target city is still owned by our enemy: success, the city was captured or destroyed
                if (targetTile(civ, operation)?.getOwner()?.civID != operation.enemyCivId) return true
            }
            type.isEscorted -> escortedShouldAbort(civ, operation)
        }
        return false
    }

    /** CvAIOperationFoundCity::ShouldAbort: an escort that does not come: after 15 turns, or off our land, with no city committed, go alone */
    private fun escortedShouldAbort(civ: Civilization, operation: AiOperation) {
        val army = operation.firstArmy ?: return
        val operations = civ.ai.operations
        if (operation.state == OperationState.RECRUITING_UNITS && operation.escorted) {
            val muster = musterTile(civ, operation)
            if (muster?.getOwner() != civ || civ.gameInfo.turns - army.slots[0].estimatedTurn > ESCORT_WAIT_TURNS) {
                if (operation.committedCities.isEmpty()) {
                    // try to get the escort from existing units that are waiting around
                    grabUnitsFromTheReserves(civ, operation)
                    if (army.numSlotsFilled() > 1) {
                        army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
                        operation.state = OperationState.GATHERING_FORCES
                    } else {
                        // we are going by our lonesome
                        operation.escorted = false
                        operation.slotsToBuild.clear()
                        army.state = ArmyState.MOVING_TO_DESTINATION
                        operation.state = OperationState.MOVING_TO_TARGET
                    }
                }
            }
        } else if (operation.state == OperationState.GATHERING_FORCES && operation.escorted) {
            val settler = army.slots[0].unitId.takeIf { it != -1 }?.let { civ.units.getUnitById(it) }
            if (settler != null && settler.getTile().isWater) {
                // we are going by our lonesome
                operation.escorted = false
                army.state = ArmyState.MOVING_TO_DESTINATION
                operation.state = OperationState.MOVING_TO_TARGET
            }
        }
    }

    /**
     *  CvAIOperation::UnitWasRemoved (and CvAIEscortedOperation's for the found-city operations): recruiting wants the slot again; below half
     *  strength the operation aborts. The naval invasions use the root rule too: CvAIOperationNavalAttack::UnitWasRemoved calls
     *  CvAIOperation's, not the CvAINavalEscortedOperation one that aborts on a lost slot 0.
     */
    fun unitWasRemoved(operations: AiOperations, operation: AiOperation, army: AiArmy, slot: Int) {
        if (operation.type.isEscorted) {
            // Assumes the civilian is in the first slot of the formation
            if (slot == 0) abort(operation, OperationAbortReason.LOST_CIVILIAN) else operation.escorted = false
            return
        }
        when (operation.state) {
            OperationState.RECRUITING_UNITS -> operation.slotsToBuild.add(SlotRef(operation.id, army.id, slot))
            OperationState.GATHERING_FORCES, OperationState.MOVING_TO_TARGET, OperationState.AT_TARGET -> {
                // the formation's required slots count: the optional ones are extras
                val required = operations.slotDefs(army).count { it.required }
                if (army.numSlotsFilled() <= 0 || army.numSlotsFilled() < required / 2) abort(operation, OperationAbortReason.HALF_STRENGTH)
            }
            else -> Unit
        }
    }

    /**
     *  CvAIOperation::PercentFromMusterPointToTarget: how far along its route the army that is furthest along is, 0..100 (step distances);
     *  0 while recruiting or gathering, 100 once at the target.
     */
    fun percentFromMusterPointToTarget(civ: Civilization, operation: AiOperation): Int {
        when (operation.state) {
            OperationState.GATHERING_FORCES, OperationState.ABORTED, OperationState.RECRUITING_UNITS -> return 0
            OperationState.AT_TARGET, OperationState.SUCCESSFUL_FINISH -> return 100
            OperationState.MOVING_TO_TARGET -> Unit
        }
        var result = 0
        val muster = musterTile(civ, operation) ?: return 0
        val enemy = enemy(civ, operation)
        val steps = civ.ai.military.steps
        val sea = operation.type.allNaval || operation.type.mixedLandNaval
        for (army in operation.armies) {
            val goal = tileAt(civ, army.goalTile) ?: continue
            val com = centerOfMass(civ, army, sea) ?: continue
            val musterToTarget = steps.distance(civ, enemy, muster, goal, sea)
            val currentToTarget = steps.distance(civ, enemy, com, goal, sea)
            if (musterToTarget <= 0) return 0
            if (currentToTarget in 0..2) return 100
            if (currentToTarget < 0) continue
            result = max(result, 100 - 100 * currentToTarget / musterToTarget)
        }
        return result
    }

    /** CvAIOperation::DoDelayedDeath: remove the operation if it is to go away */
    fun doDelayedDeath(civ: Civilization, operation: AiOperation): Boolean {
        if (!shouldAbort(civ, operation)) return false
        civ.ai.operations.kill(operation,
            if (operation.state == OperationState.SUCCESSFUL_FINISH) OperationAbortReason.SUCCESS else OperationAbortReason.KILLED)
        return true
    }

    /**
     *  CvAIOperation::BuyFinalUnit: the one slot an operation still needs is bought in its start city, with the primary unit AI of the slot.
     *  The slot leaves the list before the purchase: an idle city choosing its next production while buying must not commit to it.
     */
    fun buyFinalUnit(civ: Civilization, operation: AiOperation): Boolean {
        val city = startCity(civ, operation) ?: return false
        val slot = operation.slotsToBuild.lastOrNull() ?: return false
        val army = operation.armyById(slot.armyId) ?: return false
        val entry = civ.ai.operations.slotDefs(army).getOrNull(slot.slotId) ?: return false
        val type = UnitAiType.ofXmlName(entry.primaryUnitAi) ?: return false
        operation.slotsToBuild.removeAt(operation.slotsToBuild.lastIndex)
        val unit = UseGoldAutomation.buyEmergencyUnit(civ, type, city)
        if (unit == null) {
            operation.slotsToBuild.add(slot)
            return false
        }
        civ.ai.operations.addUnit(army, unit, slot.slotId)
        if (operation.slotsToBuild.isEmpty() && operation.committedCities.isEmpty()) finishRecruitingIfDone(civ, operation)
        return true
    }
    //endregion

    /** Turns a FOUND_CITY settler waits for an escort before it goes alone (CvAIOperationFoundCity::ShouldAbort) */
    private const val ESCORT_WAIT_TURNS = 15

    /** Turns after which any operation aborts (Vox Populi's CvAIOperation::ShouldAbort: `iTurns > 42`) */
    const val TIMEOUT_TURNS = 42

    /** Vox Populi's TACTICAL_COMBAT_MAX_TARGET_DISTANCE (CvTacticalAI.cpp): units less than this many plots from the target are not recruited */
    private const val MAX_TARGET_DISTANCE = 4

    /** CvUnit::canUseForAIOperation looks at the plots within this range for enemies (RING3_PLOTS) */
    private const val NEARBY_ENEMY_RANGE = 3

    /** The plots a turn the step finder of GrabUnitsFromTheReserves counts (it asks for [maxRecruitTurns] times this many steps) */
    private const val RECRUIT_PLOTS_PER_TURN = 4

    /** GrabUnitsFromTheReserves' ranking: the base, the penalty per turn to the muster point, the bonus for a unit that stays at its citadel */
    private const val BASE_RECRUIT_SCORE = 10000
    private const val RECRUIT_SCORE_PER_TURN = 30
    private const val CITADEL_GUARD_SCORE = 42

    /** CvUnit::GetMaxHitPoints in Unciv's health scale, and shouldHeal(true)'s hard limit */
    private const val MAX_HEALTH = 100
    private const val HARD_HEALTH_LIMIT = 13
}
