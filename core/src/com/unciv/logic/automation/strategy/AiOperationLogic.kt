package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.ApproachWinningAi
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
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.automation.unit.tactical.Deployment
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.view.GameView
import com.unciv.logic.automation.diplomacy.ApproachPart6Ai
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.automation.unit.tactical.TacticalMap
import com.unciv.logic.automation.unit.tactical.TacticalHealing
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.models.ruleset.ai.FormationSlotDef
import kotlin.math.max

/**
 *  The behaviour of CvAIOperation and its subclasses (CvAIOperation.cpp): Init of each operation type, the state machine (CheckOnTarget,
 *  ArmyInPosition, ShouldAbort, UnitWasRemoved, DoDelayedDeath), recruiting from the reserves (GrabUnitsFromTheReserves,
 *  OperationalAIHelpers::IsUnitSuitableForRecruitment, CvUnit::canUseForAIOperation, CvArmyAI::UpdateCheckpointTurnsAndRemoveBadUnits)
 *  and BuyFinalUnit. Operation and army data are in [AiOperations]; the units are moved by [com.unciv.logic.automation.unit.tactical.OperationalMoves].
 *
 *  Covered: CvAIOperationFoundCity, QuickColonize, the attacks by land (Vox Populi's CvAIOperationCityAttackLand: BNW's basic, sneak and
 *  city-state attacks, [initCityAttack]), PillageEnemy ([initPillage], [findPillageTarget]); the defense operations (CvAIOperationDefendCity and
 *  DefenseRapidResponse on CvAIOperationMilitary, see [initDefense], [preconditionsAreMet], [checkTransitionToNextStage]); the naval operations
 *  and the nuke attack are [NavalOperationLogic]. BNW's barbarian camp, naval bombardment and small city attack operations are retired.
 *
 *  Deviations, with the reason: the step finder is [StepFinder]; "path length" of a unit's route (CvIgnoreUnitsPathFinder) is the unit's turns
 *  (`getShortestPath`), which respects units.
 *
 *  Recruiting (Vox Populi, Release-5.4.8) deviates where Unciv has no counterpart, each once here:
 *  - the reach test is a unit's own route to the muster point in turns ([turnsToReach], not "approximate target ring 2") within
 *    [maxRecruitTurns], after a cheap test of the distance on the map (the original's step finder `GetPlotsInReach`);
 *  - CvUnit::shouldHeal ([TacticalHealing.shouldHeal]) has no conditional promotions;
 *  - no formation of the data has a slot without a unit AI, so the original's wildcard slot is not ported;
 *  - the units' AI-type reset on release is [AiOperations.removeFromArmy]'s; the upgrade of a unit joining an army (CvArmyAI::AddUnit) is not
 *    done: the gold AI upgrades units;
 *  - a unit bought for an operation (CvAIOperation::RecruitUnit) joins by the next turn's [continueRecruiting], before any unit moves;
 *  - a slot a city has committed to is not offered to the reserves (the original lists it again, and a second city may commit to it);
 *  - the "already close to the target" rule keeps every military operation's recruits away from its target, the defense operations' too
 *    (units within [MAX_TARGET_DISTANCE] plots of the city to defend are the tactical AI's); the settlers' operations recruit for their muster
 *    point alone;
 *  - an attack operation that found no unit at its start does not abort (SetUpArmy's NO_UNITS): the creation of operations is where the
 *    original checks the units at hand (PreconditionsAreMet) and where the limit of missing units is applied. The defense operations
 *    and the pillage raid follow SetUpArmy ([setUpArmy]).
 */
object AiOperationLogic {

    //region Helpers

    /**
     *  CvUnit::TurnsToReachTarget with the flags every operation caller of the original passes (CvAIOperation::GrabUnitsFromTheReserves,
     *  CvArmyAI::AddUnit and UpdateCheckpointTurnsAndRemoveBadUnits: MOVEFLAG_APPROX_TARGET_RING2, IGNORE_STACKING_SELF, IGNORE_ZOC): the
     *  turns [unit] needs to a plot one or two plots from [tile] that it may end its turn on, with no unit of the enemy there and no
     *  impassable plot in between - the muster point may be occupied by foreign units or besieged, and is never entered itself. 0 if the
     *  unit stands within two plots of [tile] already; the plot itself if it can be reached and none of those can. [Int.MAX_VALUE] if the
     *  unit cannot get there.
     *  Deviation: of the plots that qualify the three nearest to the unit are tried, the fewest turns win; the original's search finds the
     *  best one. Our units' stacking is no obstacle (the original's flag), the enemy's zones of control are none in Unciv.
     */
    fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        val here = unit.getTile()
        if (here == tile) return 0
        val distance = here.aerialDistanceTo(tile)
        if (distance <= RING_TWO && commonNeighborIsPassable(here, tile)) return 0
        var best = Int.MAX_VALUE
        val candidates = tile.getTilesInDistanceRange(1..RING_TWO)
            .filter { unit.movement.canPassThrough(it) && commonNeighborIsPassable(it, tile) }
            .sortedBy { it.aerialDistanceTo(here) }
            .take(RING_CANDIDATES)
        for (candidate in candidates) {
            val path = unit.movement.getShortestPath(candidate)
            if (path.isNotEmpty()) best = minOf(best, path.size.coerceAtLeast(1))
        }
        if (best != Int.MAX_VALUE) return best
        if (!unit.movement.canReach(tile)) return Int.MAX_VALUE
        return unit.movement.getShortestPath(tile).size.coerceAtLeast(1)
    }

    /** CvPathFinder::CommonNeighborIsPassable: next to each other, or a plot next to both that is no mountain or ice (impassable) */
    private fun commonNeighborIsPassable(from: Tile, to: Tile): Boolean {
        if (from.aerialDistanceTo(to) <= 1) return true
        val around = to.neighbors.toSet()
        return from.neighbors.any { it in around && !it.isImpassible() }
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

    /** Whether the city attack formations are the bigger ones: handicap above 4 on a map that is not an archipelago (GetMap().GetAIMapHint() & 1) */
    private fun biggerForces(civ: Civilization) =
        EconomicAi.gameHandicapIndex(civ.gameInfo) > 4 && SettleAreas.aiMapHint(civ.gameInfo) and 1 == 0

    /**
     *  MilitaryAIHelpers::GetCurrentBestFormationTypeForCombinedAttack and ...ForPureNavalAttack: [late] after the Medieval era (the game's
     *  era, [ApproachWinningAi.gameEra]), else [early]; a ruleset that has no late-game formation keeps [early]
     */
    private fun lateGameOr(civ: Civilization, late: String, early: String): String =
        if (ApproachWinningAi.gameEra(civ) > civ.gameInfo.ruleset.ai.define(AiDefine.MEDIEVAL_ERA) && civ.ai.operations.formationDef(late) != null) late else early

    /** CvAIOperation::GetFormation of the operation types (a formation the ruleset lacks is replaced by the basic city attack force) */
    fun formationName(civ: Civilization, type: AiOperationType): String {
        val name = when (type) {
            AiOperationType.FOUND_CITY -> "MUFORMATION_SETTLER_ESCORT"
            AiOperationType.QUICK_COLONIZE -> "MUFORMATION_QUICK_COLONY_SETTLER"
            AiOperationType.BASIC_CITY_ATTACK -> if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.SNEAK_CITY_ATTACK ->
                if (civ.gameInfo.turns < 50 && civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) >= 5) "MUFORMATION_EARLY_RUSH" // CvAIOperationQuickSneakCityAttack
                else if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.CITY_STATE_ATTACK -> if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_CITY_STATE_ATTACK_FORCE"
            // never asked: a save's retired operations are dropped when it loads
            AiOperationType.DESTROY_BARBARIAN_CAMP, AiOperationType.NAVAL_BOMBARDMENT, AiOperationType.SMALL_CITY_ATTACK -> "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.PILLAGE_ENEMY -> "MUFORMATION_FAST_PILLAGERS"
            AiOperationType.CITY_CLOSE_DEFENSE -> "MUFORMATION_CLOSE_CITY_DEFENSE"
            AiOperationType.RAPID_RESPONSE -> "MUFORMATION_RAPID_RESPONSE_FORCE"
            // MilitaryAIHelpers::GetCurrentBestFormationTypeForCombinedAttack: every combined army, a city-state's too
            AiOperationType.NAVAL_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK, AiOperationType.CITY_STATE_NAVAL_ATTACK ->
                lateGameOr(civ, "MUFORMATION_LATE_GAME_INVASION_FORCE", "MUFORMATION_NAVAL_INVASION")
            AiOperationType.NAVAL_SUPERIORITY -> "MUFORMATION_NAVAL_SQUADRON"
            AiOperationType.CARRIER_GROUP -> "MUFORMATION_CARRIER_ESCORT"
            // MilitaryAIHelpers::GetCurrentBestFormationTypeForPureNavalAttack
            AiOperationType.PURE_NAVAL_CITY_ATTACK -> lateGameOr(civ, "MUFORMATION_LATE_GAME_PURE_NAVAL_FORCE", "MUFORMATION_PURE_NAVAL_CITY_ATTACK")
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
     *  recruits what it can from the reserves and sets its state. With a [maxMissingUnits] the operation ends at once (AI_ABORT_NO_UNITS)
     *  when its initialization leaves more units to build. An operation that has ended at once never existed: it is deleted as the
     *  original does ([AiOperations.discard], no statistic) and returned in its state ABORTED.
     *  @param target the city to attack or defend, [muster] our city it starts from; only [enemy] for the other types
     */
    fun addOperation(
        civ: Civilization, type: AiOperationType, enemy: Civilization? = null, target: City? = null, muster: City? = null,
        maxMissingUnits: Int? = null
    ): AiOperation {
        val operations = civ.ai.operations
        val operation = operations.newOperation(type, enemy)
        when {
            type.isCityAttack -> initCityAttack(civ, operation, target, muster)
            type == AiOperationType.PILLAGE_ENEMY -> initPillage(civ, operation)
            type.isNavalInvasion -> NavalOperationLogic.initNavalInvasion(civ, operation, target, muster)
            type == AiOperationType.PURE_NAVAL_CITY_ATTACK -> NavalOperationLogic.initPureNavalAttack(civ, operation, target, muster)
            type == AiOperationType.NAVAL_SUPERIORITY -> NavalOperationLogic.initSuperiority(civ, operation, target, muster)
            type == AiOperationType.CARRIER_GROUP -> NavalOperationLogic.initCarrierGroup(civ, operation)
            type == AiOperationType.NUKE_ATTACK -> NavalOperationLogic.initNuke(civ, operation)
            else -> initDefense(civ, operation, target, muster)
        }
        // check number of units again
        if (maxMissingUnits != null && operation.state != OperationState.ABORTED && operation.slotsToBuild.size > maxMissingUnits)
            abort(operation, OperationAbortReason.NO_UNITS)
        if (operation.state == OperationState.ABORTED) {
            AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id} aborted at start: ${operation.abortReason}" }
            operations.discard(operation)
        } else logStart(civ, operation)
        return operation
    }

    /**
     *  CvAIOperationMilitary::Init for the attacks by land (Vox Populi's CvAIOperationCityAttackLand; BNW's basic, sneak and city-state
     *  attacks are the same here): the army musters at [muster], else at our city nearest to [target] on its landmass within
     *  [MUSTER_RADIUS] plots (CvPlayer::GetClosestCity), and its goal is the target city itself (SetUpArmy with no deploy plot; it has
     *  arrived within [DEPLOY_RANGE]). BNW's goal a few plots short of the target, found along the route, and its NO_MUSTER abort for an
     *  attack with no muster city given are gone. With no [target] or no muster city the operation ends.
     */
    private fun initCityAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val targetTile = target?.getCenterTile() ?: run { abort(operation, OperationAbortReason.LOST_TARGET); return }
        val musterCity = muster ?: closestCity(civ, targetTile, MUSTER_RADIUS, sameLandmass = true)
        if (musterCity == null) { abort(operation, OperationAbortReason.NO_MUSTER); return }
        operation.startCityId = musterCity.id
        val army = civ.ai.operations.newArmy(operation, formationName(civ, operation.type))
        val musterTile = musterCity.getCenterTile()
        operation.targetTile = targetTile.position
        army.goalTile = targetTile.position
        operation.musterTile = musterTile.position
        army.position = musterTile.position
        operation.area = musterTile.getContinent()
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

    /**
     *  CvAIOperationMilitary::Init for the pillage raid (CvAIOperationPillageEnemy): the target is the raid's own ([findPillageTarget]
     *  against the enemy) and the muster is our city nearest to it by path, as [findPillageTarget] found them; the army is set up as
     *  the defense operations' ([setUpArmy], so a raid with no unit at hand ends: NO_UNITS) with the target city as its goal and
     *  [checkTransitionToNextStage] to judge it. BNW's target (improved tiles per step), its muster point out on the route and its goal
     *  a few plots short of the target are gone.
     */
    private fun initPillage(civ: Civilization, operation: AiOperation) {
        val found = enemy(civ, operation)?.let { findPillageTarget(civ, it) }
        if (found == null) { abort(operation, OperationAbortReason.LOST_TARGET); return }
        val (target, muster) = found
        operation.startCityId = muster.id
        val army = civ.ai.operations.newArmy(operation, formationName(civ, operation.type))
        setUpArmy(civ, operation, army, muster.getCenterTile(), target.getCenterTile())
    }

    /**
     *  CvAIOperationMilitary::Init for the land defense operations (CvAIOperationDefendCity, CvAIOperationDefenseRapidResponse), which
     *  Vox Populi launches for a city: the army musters at [muster], else our city nearest to [target] on its landmass within
     *  [MUSTER_RADIUS] plots (the city itself when it is ours), and is sent to the city. No target or no muster city ends the operation.
     */
    private fun initDefense(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val targetTile = target?.getCenterTile() ?: run { abort(operation, OperationAbortReason.LOST_TARGET); return }
        val musterCity = muster ?: closestCity(civ, targetTile, MUSTER_RADIUS, sameLandmass = true)
        if (musterCity == null) { abort(operation, OperationAbortReason.NO_MUSTER); return }
        operation.startCityId = musterCity.id
        val army = civ.ai.operations.newArmy(operation, formationName(civ, operation.type))
        setUpArmy(civ, operation, army, musterCity.getCenterTile(), targetTile)
    }

    /**
     *  CvAIOperationDefenseRapidResponse::FindBestTarget: where the enemy's units gather around the operation's target - the best blocking
     *  position against the current threats ([findEnemiesNearHomelandPlot] within [RAPID_RESPONSE_RETARGET_RANGE] plots of the target)
     */
    private fun findRapidResponseTarget(civ: Civilization, operation: AiOperation): Tile? =
        findEnemiesNearHomelandPlot(civ, enemy(civ, operation) ?: return null, targetTile(civ, operation), RAPID_RESPONSE_RETARGET_RANGE)

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
     *  CvAIOperationPillageEnemy::FindBestTarget (Vox Populi, Release-5.4.8): the city of [enemy] whose worked luxury and strategic resources
     *  ([workedResourceCount]) are the most, among those that share a landmass ([MilitaryTargets.hasSharedLandArea]) with our city nearest
     *  to them by path ([MilitaryTargets.closestCityByPathLength]) - embarking is dangerous - and whose land zone of the tactical map has a
     *  neighboring zone of ours (only pillage in border zones); a city with no such resource is none. Ties go to the first city.
     *  @return the city and our city nearest to it (the raid's muster city), null if there is none
     *  Deviation: the zone neighbors are the tactical map's, which counts every adjacent zone ([TacticalMap]).
     */
    fun findPillageTarget(civ: Civilization, enemy: Civilization): Pair<City, City>? {
        val map = civ.ai.tactical.map
        var best: Pair<City, City>? = null
        var bestValue = 0
        for (city in enemy.cities) {
            // Make sure city is in the same area as our potential muster point
            val closest = MilitaryTargets.closestCityByPathLength(civ, enemy, city.getCenterTile(), allowSea = false)?.first ?: continue
            // only on the same continent, embarking is dangerous ...
            if (!MilitaryTargets.hasSharedLandArea(city, closest)) continue
            // Only pillage in border zones
            val zone = map.zoneByCity(city, isWater = false) ?: continue
            if (zone.neighbors.none { it.owner == civ }) continue
            val value = workedResourceCount(city)
            if (value > bestValue) { bestValue = value; best = city to closest }
        }
        return best
    }

    /**
     *  The sum of CvCity::CountNumWorkedResource over the luxury and strategic resources: the tiles the city works that hold such a
     *  resource its owner can see. Deviation: the city's center tile is not among its worked tiles here.
     */
    private fun workedResourceCount(city: City): Int = city.getWorkedTiles().count { tile ->
        val resource = tile.tileResource?.takeIf { city.civ.canSeeResource(it) } ?: return@count false
        resource.resourceType == ResourceType.Luxury || resource.resourceType == ResourceType.Strategic
    }

    /**
     *  CvAIOperationCivilian::Init for CvAIOperationCivilianFoundCity (and the BNW quick colony, which has no escort): the settler [civilian]
     *  (a loose settler of ours) goes to the best site [findSettleTarget] finds, with an escort recruited from the reserves if possible; with none a
     *  player with more than one city or boldness over 5 sends it alone when no enemy unit is in sight of the site, else the operation recruits the escort.
     */
    fun addSettlerOperation(civ: Civilization, type: AiOperationType, civilian: MapUnit): AiOperation {
        val operations = civ.ai.operations
        val operation = operations.newOperation(type, null)
        val target = findSettleTarget(civ, operation, civilian)
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
            val alone = (civ.cities.size > 1 || civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 5) && !SettleAreas.isVisibleEnemyUnit(civ, target)
            if (!alone) {
                // we'll wait it out for an escort
                operation.slotsToBuild.clear()
                operation.slotsToBuild.add(SlotRef(operation.id, army.id, 1))
                operation.state = OperationState.RECRUITING_UNITS
            } else {
                operation.escorted = false
                operation.slotsToBuild.clear()
                army.state = ArmyState.MOVING_TO_DESTINATION
                operation.state = OperationState.MOVING_TO_TARGET
            }
        }
        logStart(civ, operation)
        return operation
    }

    /**
     *  CvAIOperationCivilianFoundCity::FindBestTargetForUnit: the best settle plot for the unit ([SettleAreas.bestSettlePlot], the targets of our
     *  other found-city operations excluded), unless its quality is under the minimum acceptable one and we have a city already.
     */
    fun findSettleTarget(civ: Civilization, operation: AiOperation, unit: MapUnit): Tile? {
        val best = SettleAreas.bestSettlePlot(civ, unit, operation) ?: return null
        if (SettleAreas.settlePlotQualityMeasure(civ, best) < SettleAreas.minAcceptableSettleQuality(civ) && civ.cities.isNotEmpty()) return null
        return best
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
    fun isCloseToContestedBorder(civ: Civilization, map: TacticalMap, tile: Tile): Boolean = tile.getTilesAtDistance(2).any { other ->
        val owner = other.getOwner()
        owner != null && civ.isAtWarWith(owner) && !other.isWater && map.zoneOf(other)?.dominance != TacticalDominance.Friendly
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
     *  embark if [mustEmbark], [canUseForAiOperation] and not [TacticalHealing.shouldHeal].
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
        return canUseForAiOperation(civ, unit) && !TacticalHealing.shouldHeal(civ, unit, beforeAttacks = false)
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
    internal fun unfriendlyMajors(civ: Civilization): List<Civilization> = civ.gameInfo.civilizations.filter { other ->
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
    internal fun isBorderLand(civ: Civilization, tile: Tile, unfriendly: List<Civilization>): Boolean {
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
        // see the class comment: every military operation keeps the units near its target out of the army; the settlers' do not
        val closeTarget = if (operation.type.isEscorted) null else target
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
    internal fun checkpointTile(civ: Civilization, operation: AiOperation, army: AiArmy): Tile? = when (army.state) {
        ArmyState.WAITING_FOR_UNITS_TO_REINFORCE, ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> musterTile(civ, operation)
        ArmyState.MOVING_TO_DESTINATION -> centerOfMass(civ, army, operation.type.allNaval || operation.type.mixedLandNaval)
    }

    /**
     *  CvArmyAI::UpdateCheckpointTurnsAndRemoveBadUnits: an offensive operation (CvAIOperation::IsOffensive) drops the units that are
     *  [TacticalHealing.shouldHeal] before the attacks; every unit not within the gather tolerance of the army's checkpoint ([checkpointTile]) records the turns it
     *  needs to get there ([ArmySlot.checkpointTurns], [Int.MAX_VALUE] if it needs more than [maxRecruitTurns]), and one that has made no
     *  progress over the last three records ([ArmySlot.isMakingProgress]) is dropped. Generals and admirals are not judged: they follow the
     *  army by their own scoring ([centerOfMass]).
     */
    private fun updateCheckpointTurnsAndRemoveBadUnits(civ: Civilization, operation: AiOperation, army: AiArmy) {
        val operations = civ.ai.operations
        if (operations.isOffensive(operation)) {
            for ((_, unit) in armyUnits(civ, army)) {
                if (!TacticalHealing.shouldHeal(civ, unit, beforeAttacks = true)) continue
                AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id}: ${unit.name} leaves the army to heal" }
                operations.removeFromArmy(unit)
            }
        }
        val checkpoint = checkpointTile(civ, operation, army) ?: return
        val tolerance =
            if (operation.type.allNaval || operation.type.mixedLandNaval) gatherToleranceFor(civ, operation, army, checkpoint)
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
     *  CvAIOperation::CheckTransitionToNextStage after the army moved. The military operations - the attacks by land and by sea, the pillage
     *  raid, the defense operations, the naval superiority squadron - are CvAIOperationMilitary's ([checkTransitionToNextStage]:
     *  recruiting, gathering, moving, success within [DEPLOY_RANGE] of the target, or discovered at peace); BNW's tests of their own (the
     *  gathering within the muster's tolerance, the arrival within 5 plots or, for an invasion, twice the city attack range, the abort for a
     *  target that changed) are gone. The settlers' operations keep theirs: an
     *  army with enough units starts gathering ([finishRecruitingIfDone]), the armies that have gathered move out, those at the target
     *  hand over. True if the state changed.
     */
    fun checkOnTarget(civ: Civilization, operation: AiOperation): Boolean {
        if (operation.type.isDefense || operation.type.moveType == OperationMoveType.EnemyTerritory) return checkTransitionToNextStage(civ, operation)
        val recruited = finishRecruitingIfDone(civ, operation)
        val inPosition = checkInPosition(civ, operation)
        return recruited || inPosition
    }

    /** The gathering and moving cases of [checkOnTarget] for the civilian and the naval operations */
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
            OperationMoveType.EnemyTerritory, OperationMoveType.None -> Unit
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
            type == AiOperationType.NUKE_ATTACK -> NavalOperationLogic.nukeInPosition(civ, operation, army)
            type.isEscorted -> escortedInPosition(civ, operation, army)
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
                    val better = findSettleTarget(civ, operation, settler)
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

    /** CvAIOperationCivilian::RetargetCivilian: look for another site */
    fun retargetCivilian(civ: Civilization, operation: AiOperation, army: AiArmy, civilian: MapUnit): Boolean {
        val better = findSettleTarget(civ, operation, civilian)
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
     *  CvAIOperation::ShouldAbort. True when the operation is to be removed (the sweep kills it): it ended, or its purpose is gone
     *  ([verifyOrAdjustTarget]). An operation with no army is over (AI_ABORT_NO_UNITS, as CvAIOperation::DeleteArmyAI and Move end it).
     *  Units of a successful operation are marked deployed (CvUnit::SetDeployFromOperationTurn).
     *  Vox Populi's failsafe (CvAIOperation::ShouldAbort, Release-5.4.8; D7): an operation started more than [TIMEOUT_TURNS] turns ago
     *  aborts, TIMED_OUT. BNW has none, and an operation that can no longer finish (an army stuck on the way, one recruiting units that
     *  never come) blocks new ones of its kind for the rest of the game.
     */
    fun shouldAbort(civ: Civilization, operation: AiOperation): Boolean {
        val operations = civ.ai.operations
        if (operation.state == OperationState.SUCCESSFUL_FINISH)
            for (army in operation.armies) for ((_, unit) in armyUnits(civ, army)) operations.deployFromOperationTurns[unit.id] = civ.gameInfo.turns
        if (operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) return true
        if (civ.gameInfo.turns - operation.createdTurn > TIMEOUT_TURNS && !operation.type.isNeverEnding) {
            abort(operation, OperationAbortReason.TIMED_OUT)
            return true
        }
        if (operation.armies.isEmpty()) abort(operation, OperationAbortReason.NO_UNITS)
        else verifyOrAdjustTarget(civ, operation)
        // the verification of the target may have ended it (CvAIOperation::ShouldAbort: true for an operation that is aborted)
        return operation.state == OperationState.ABORTED
    }

    /**
     *  What CvAIOperation::Move ends with, after the army moved: [shouldAbort] again, and an operation that ends releases its army's units
     *  (CvArmyAI::ReleaseAllUnits) before anything else asks for them.
     *  @return whether the operation goes on to [checkOnTarget] (CvAIOperation::DoTurn: CheckTransitionToNextStage after a Move that did not end it)
     */
    fun continueAfterMove(civ: Civilization, operation: AiOperation): Boolean {
        if (!shouldAbort(civ, operation)) return true
        civ.ai.operations.releaseAllUnits(operation)
        return false
    }

    /**
     *  CvAIOperation::VerifyOrAdjustTarget of the operation's class, for every army (one): the target a civilian operation
     *  ([escortedShouldAbort]) or a defense operation ([verifyDefenseTarget], [NavalOperationLogic.verifySuperiorityTarget]) keeps or
     *  changes, the pillage raid's best target of the moment ([verifyPillageTarget]), and for the rest CvAIOperationMilitary's: a target plot
     *  owned by anyone but the enemy ends the operation with TARGET_ALREADY_CAPTURED - also by us, the original does not call it a
     *  success - and war is not asked (that would break sneak attacks). A target plot nobody owns any more (a razed city) does not end it.
     *  The nuke attack's own rule asks for the enemy as owner.
     */
    private fun verifyOrAdjustTarget(civ: Civilization, operation: AiOperation) {
        val type = operation.type
        when {
            type.isEscorted -> escortedShouldAbort(civ, operation)
            type.isLandDefense -> verifyDefenseTarget(civ, operation)
            type == AiOperationType.NAVAL_SUPERIORITY -> NavalOperationLogic.verifySuperiorityTarget(civ, operation)
            type == AiOperationType.CARRIER_GROUP -> NavalOperationLogic.verifyCarrierTarget(civ, operation)
            type == AiOperationType.PILLAGE_ENEMY -> verifyPillageTarget(civ, operation)
            else -> {
                val owner = targetTile(civ, operation)?.getOwner()
                val captured = if (type == AiOperationType.NUKE_ATTACK) owner?.civID != operation.enemyCivId
                    else owner != null && owner.civID != operation.enemyCivId
                if (captured) abort(operation, OperationAbortReason.TARGET_ALREADY_CAPTURED)
            }
        }
    }

    /**
     *  CvAIOperationPillageEnemy::VerifyOrAdjustTarget: every time the army moves the best target is looked for again ([findPillageTarget]);
     *  none ends the raid (NO_TARGET), a new one becomes the target and the army's goal.
     */
    private fun verifyPillageTarget(civ: Civilization, operation: AiOperation) {
        val better = enemy(civ, operation)?.let { findPillageTarget(civ, it) }?.first?.getCenterTile()
        if (better == null) abort(operation, OperationAbortReason.NO_TARGET) else setTarget(operation, better)
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
     *  CvAIOperation::UnitWasRemoved (and CvAIEscortedOperation's for the found-city operations): recruiting wants the slot again; once
     *  the army is gathering or on its way it aborts (HALF_STRENGTH) with fewer units than half the required slots of the formation (the
     *  original counts the formation's, as the slots of the army are made not required when cleared; the filled ones include the optional
     *  units), and with fewer than 2 when 2 are required. The naval invasions use the root rule too: CvAIOperationNavalAttack::UnitWasRemoved
     *  calls CvAIOperation's, not the CvAINavalEscortedOperation one that aborts on a lost slot 0.
     *  Departure: an army that has lost every unit is over too (with 1 required slot, as the pillage raid has, the original would keep it
     *  until the failsafe of [TIMEOUT_TURNS] ends it).
     */
    fun unitWasRemoved(operations: AiOperations, operation: AiOperation, army: AiArmy, slot: Int) {
        // the carrier slot is special - without it there's nothing we can do (CvAIOperationCarrierGroup::UnitWasRemoved), then the default handling
        if (operation.type == AiOperationType.CARRIER_GROUP && slot == 0) abort(operation, OperationAbortReason.CANCELLED)
        if (operation.type.isEscorted) {
            // Assumes the civilian is in the first slot of the formation
            if (slot == 0) abort(operation, OperationAbortReason.LOST_CIVILIAN) else operation.escorted = false
            return
        }
        when (operation.state) {
            OperationState.RECRUITING_UNITS -> operation.slotsToBuild.add(SlotRef(operation.id, army.id, slot))
            OperationState.GATHERING_FORCES, OperationState.MOVING_TO_TARGET, OperationState.AT_TARGET -> {
                val required = operations.slotDefs(army).count { it.required }
                val filled = army.numSlotsFilled()
                if (filled <= 0 || filled < required / 2) abort(operation, OperationAbortReason.HALF_STRENGTH)
                // special for escorted ops
                if (filled < 2 && required == 2) abort(operation, OperationAbortReason.HALF_STRENGTH)
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

    //region Defense operations (CvAIOperationMilitary with CvAIOperationDefendCity and CvAIOperationDefenseRapidResponse)

    /**
     *  CvAIOperation::SetUpArmy for the military operations that Vox Populi launches to defend: the operation heads for [target] from
     *  [muster] with [army] (the muster point is where the army gathers, [target] its goal), takes what the reserves have, and ends at once
     *  (NO_UNITS) when not one unit came; an army with all it needs starts gathering, and may go straight on to move.
     *  @return false if the operation ended
     */
    internal fun setUpArmy(civ: Civilization, operation: AiOperation, army: AiArmy, muster: Tile?, target: Tile?): Boolean {
        if (muster == null || target == null) {
            abort(operation, if (target != null) OperationAbortReason.NO_MUSTER else OperationAbortReason.NO_TARGET)
            return false
        }
        operation.targetTile = target.position
        operation.musterTile = muster.position
        operation.area = muster.getContinent()
        army.goalTile = target.position
        army.position = muster.position
        operation.state = OperationState.RECRUITING_UNITS
        army.state = ArmyState.WAITING_FOR_UNITS_TO_REINFORCE
        grabUnitsFromTheReserves(civ, operation)
        // bail if there isn't a single suitable unit
        if (army.numSlotsFilled() == 0) {
            abort(operation, OperationAbortReason.NO_UNITS)
            return false
        }
        // see if we can switch to gathering stage; maybe we can even switch to movement stage
        if (checkTransitionToNextStage(civ, operation)) checkTransitionToNextStage(civ, operation)
        return true
    }

    /**
     *  CvAIOperation::PreconditionsAreMet, which CvPlayer::addAIOperation asks before it makes an operation (it does no path finding, to
     *  keep the logs clean and the turn fast): our free units that are suitable ([isSuitableForRecruitment]; with no [givenMuster] given, as
     *  for the defense operations, wherever they are, and ships that cannot enter the ocean are no recruits) take the slots of the
     *  operation's formation in order, and [maxMissingUnits] (an optional unit makes up for half a required one) may be missing.
     *  CvAIOperationPillageEnemy::PreconditionsAreMet overwrites the target and the muster with the raid's own ([findPillageTarget] against
     *  [enemy]) and has none without them.
     */
    fun preconditionsAreMet(
        civ: Civilization, type: AiOperationType, givenTarget: Tile?, givenMuster: Tile?, maxMissingUnits: Int, enemy: Civilization? = null
    ): Boolean {
        var target = givenTarget
        var muster = givenMuster
        if (type == AiOperationType.PILLAGE_ENEMY) {
            val (targetCity, musterCity) = enemy?.let { findPillageTarget(civ, it) } ?: return false
            target = targetCity.getCenterTile()
            muster = musterCity.getCenterTile()
        }
        val defs = civ.ai.operations.formationDef(formationName(civ, type))?.slots ?: return false
        val naval = type.mixedLandNaval || type.allNaval
        val crossesOcean = if (muster != null && target != null) isAcrossOcean(civ, muster, target, naval) else true
        val maxTurns = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_MAX_RECRUIT_TURNS_ENEMY_TERRITORY)
        val free = defs.indices.toMutableList()
        val filled = BooleanArray(defs.size)
        for (unit in civ.units.getCivUnits()) {
            if (free.isEmpty()) break
            if (muster != null && unit.getTile().aerialDistanceTo(muster) > maxTurns * RECRUIT_PLOTS_PER_TURN) continue
            if (!isSuitableForRecruitment(civ, unit, naval, target, crossesOcean)) continue
            val index = free.indexOfFirst { slotFits(civ, unit, defs[it]) }
            if (index < 0) continue
            filled[free.removeAt(index)] = true
        }
        var presentRequired = 0
        var presentOptional = 0
        var freeRequired = 0
        for ((index, def) in defs.withIndex()) when {
            filled[index] && def.required -> presentRequired++
            filled[index] -> presentOptional++
            def.required -> freeRequired++
        }
        return presentRequired > 0 && freeRequired <= presentOptional / 2 + maxMissingUnits
    }

    /** CvPlayer::GetClosestCity: our city nearest to [tile] within [searchRadius] plots, one with access to the landmass or water of [tile] when [sameLandmass] */
    fun closestCity(civ: Civilization, tile: Tile, searchRadius: Int, sameLandmass: Boolean): City? {
        val area = civ.ai.tactical.map.areaOf(tile)
        var best: City? = null
        var bestDistance = Int.MAX_VALUE
        for (city in civ.cities) {
            if (sameLandmass && !hasAccessToArea(city, area)) continue
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            if (distance < bestDistance && distance <= searchRadius) { best = city; bestDistance = distance }
        }
        return best
    }

    /** OperationalAIHelpers::GetClosestFriendlyCoastalCity: our coastal city nearest to [tile] that has access to its landmass or water body */
    fun closestFriendlyCoastalCity(civ: Civilization, tile: Tile): City? {
        val area = civ.ai.tactical.map.areaOf(tile)
        var best: City? = null
        var bestDistance = Int.MAX_VALUE
        for (city in civ.cities) {
            if (!city.isCoastal() || !hasAccessToArea(city, area)) continue
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            if (distance < bestDistance) { best = city; bestDistance = distance }
        }
        return best
    }

    /** CvCity::HasAccessToLandmassOrOcean: the landmass or body of water [area] is the city's own or next to it */
    private fun hasAccessToArea(city: City, area: Int): Boolean {
        val map = city.civ.ai.tactical.map
        val center = city.getCenterTile()
        return map.areaOf(center) == area || center.neighbors.any { map.areaOf(it) == area }
    }

    /**
     *  OperationalAIHelpers::FindEnemiesNearHomelandPlot: where [enemy]'s land units are most to be feared at home: among those we see that
     *  stand on our land or within [HOMELAND_PATH_LENGTH] steps of one of our cities, the tile whose cluster of attackers (the power of
     *  [enemy]'s units on tiles within two that we see; a single unit is too volatile) is the strongest, weighed down by its distance from
     *  [reference] (full within three plots, none from [maxDistance]: we don't want to adjust our target too much). Null if there is none.
     *  Deviation: the steps from a city are [StepFinder]'s rule (an army of ours walking against [enemy]) from our cities outwards, in place
     *  of the original's per-plot city distance map.
     */
    fun findEnemiesNearHomelandPlot(civ: Civilization, enemy: Civilization, reference: Tile?, maxDistance: Int): Tile? {
        var best: Tile? = null
        var bestPower = 0
        val nearOurCities by lazy { tilesNearOurCities(civ, enemy) }
        for (unit in enemy.units.getCivUnits()) {
            if (!unit.baseUnit.isLandUnit || !unit.isVisibleTo(civ)) continue
            val tile = unit.getTile()
            if (tile.getOwner() != civ && tile !in nearOurCities) continue
            val distance = reference?.let { tile.aerialDistanceTo(it) } ?: 0
            val power = localEnemyPower(civ, tile) * mapToPercent(distance, max(maxDistance, 3), 3)
            if (power > bestPower) { bestPower = power; best = tile }
        }
        return best
    }

    /** CvPlot::GetLocalUnitPower, its enemy half over two plots with units of the plot's domain: the power of the visible attackers at war with us */
    private fun localEnemyPower(civ: Civilization, tile: Tile): Int {
        var power = 0
        for (nearby in tile.getTilesInDistance(2)) {
            if (!nearby.isVisible(civ)) continue
            for (unit in nearby.getUnits()) {
                if (!unit.isMilitary() || !(if (tile.isWater) unit.baseUnit.isWaterUnit else unit.baseUnit.isLandUnit)) continue
                if (civ.isAtWarWith(unit.civ) && unit.isVisibleTo(civ)) power += unit.getAiPower()
            }
        }
        return power
    }

    /** The land tiles within [HOMELAND_PATH_LENGTH] steps of one of our cities that an army of ours going for [enemy] could walk ([StepFinder.isValid]) */
    private fun tilesNearOurCities(civ: Civilization, enemy: Civilization): Set<Tile> {
        val steps = civ.ai.military.steps
        val reached = HashSet<Tile>()
        var frontier = ArrayList<Tile>()
        for (city in civ.cities) if (reached.add(city.getCenterTile())) frontier.add(city.getCenterTile())
        repeat(HOMELAND_PATH_LENGTH) {
            val next = ArrayList<Tile>()
            for (tile in frontier) for (neighbor in tile.neighbors)
                if (neighbor !in reached && steps.isValid(civ, enemy, neighbor)) { reached.add(neighbor); next.add(neighbor) }
            frontier = next
        }
        return reached
    }

    /** MapToPercent: [value] mapped linearly to 0 at [zeroAt] and 100 at [hundredAt], clamped */
    private fun mapToPercent(value: Int, zeroAt: Int, hundredAt: Int): Int = when {
        hundredAt != zeroAt -> ((value - zeroAt) * 100 / (hundredAt - zeroAt)).coerceIn(0, 100)
        value > hundredAt -> 100
        value < zeroAt -> 0
        else -> 50
    }

    /**
     *  The VerifyOrAdjustTarget of the land defense operations, which ShouldAbort asks of every army that has not finished. City defense: our
     *  target city must still be ours (TARGET_ALREADY_CAPTURED). Rapid response: the best blocking position against the current threats
     *  ([findRapidResponseTarget]), no position at all ends it (NO_TARGET), and a position more than [RAPID_RESPONSE_RETARGET_RANGE] plots from
     *  the target becomes the new target (else the tactical AI should do the rest).
     */
    private fun verifyDefenseTarget(civ: Civilization, operation: AiOperation) {
        val target = targetTile(civ, operation)
        if (operation.type == AiOperationType.CITY_CLOSE_DEFENSE) {
            // See if our target city is still owned by us
            if (target?.getOwner() != civ) abort(operation, OperationAbortReason.TARGET_ALREADY_CAPTURED)
            return
        }
        val better = findRapidResponseTarget(civ, operation)
        if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return }
        if (target == null || better.aerialDistanceTo(target) > RAPID_RESPONSE_RETARGET_RANGE) setTarget(operation, better)
    }

    /** CvAIOperation::SetTargetPlot: the armies' goal follows, and an army that had reached its target has to move again */
    internal fun setTarget(operation: AiOperation, tile: Tile) {
        if (operation.targetTile == tile.position) return
        if (operation.state == OperationState.AT_TARGET) {
            operation.state = OperationState.MOVING_TO_TARGET
            for (army in operation.armies) army.state = ArmyState.MOVING_TO_DESTINATION
        }
        operation.targetTile = tile.position
        for (army in operation.armies) army.goalTile = tile.position
    }

    /** CvArmyAI::GetCenterOfMass(bClampToUnit) and its variances: the average position of the army's units, as the tile of the unit nearest to it */
    private class ArmyCenter(val tile: Tile, val varX: Float, val varY: Float)

    /**
     *  CvArmyAI::GetCenterOfMass with its spread: the units' average position and the variance of their coordinates (Unciv's hex coordinates,
     *  not the original's offset grid), and the tile of the unit nearest to the average (and then to the army's goal) when [clampToUnit].
     *  Departure as in [centerOfMass]: generals and admirals are left out.
     */
    private fun armyCenter(civ: Civilization, army: AiArmy, clampToUnit: Boolean): ArmyCenter? {
        val all = armyUnits(civ, army).map { it.second }
        val units = all.filter { !Deployment.isGeneralOrAdmiral(it) }.ifEmpty { all }
        if (units.isEmpty()) return null
        val map = civ.gameInfo.tileMap
        val reference = units[0].getTile().position
        var totalX = 0
        var totalY = 0
        var totalX2 = 0
        var totalY2 = 0
        for (unit in units) {
            var position = unit.getTile().position
            if (map.mapParameters.worldWrap) {
                val unwrapped = map.getUnwrappedPosition(position)
                if (HexMath.getDistance(unwrapped, reference) < HexMath.getDistance(position, reference)) position = unwrapped
            }
            val dx = position.x - reference.x
            val dy = position.y - reference.y
            totalX += dx; totalY += dy
            totalX2 += dx * dx; totalY2 += dy * dy
        }
        val count = units.size.toFloat()
        val averageX = totalX / count + reference.x
        val averageY = totalY / count + reference.y
        // rounding to nearest integer
        val roundedX = if (averageX > 0) (averageX + 0.5f).toInt() else (averageX - 0.5f).toInt()
        val roundedY = if (averageY > 0) (averageY + 0.5f).toInt() else (averageY - 0.5f).toInt()
        val center = map.getOrNull(roundedX, roundedY) ?: return null
        val varX = totalX2 / count - (totalX / count) * (totalX / count)
        val varY = totalY2 / count - (totalY / count) * (totalY / count)
        if (!clampToUnit) return ArmyCenter(center, varX, varY)
        val goal = tileAt(civ, army.goalTile)
        val nearest = units.minByOrNull { unit ->
            unit.getTile().aerialDistanceTo(center) * 100 + (goal?.let { unit.getTile().aerialDistanceTo(it) } ?: 0)
        } ?: return null
        return ArmyCenter(nearest.getTile(), varX, varY)
    }

    /** CvAIOperation::SetMusterPlot: the army gathers somewhere else; the units' progress towards the muster point starts anew */
    private fun setMuster(operation: AiOperation, army: AiArmy, tile: Tile) {
        operation.musterTile = tile.position
        army.position = tile.position
        for (slot in army.slots) slot.checkpointTurns.clear()
    }

    /**
     *  CvAIOperation::GetGatherTolerance: how far from [tile] the army's units may be for it to count as gathered - 4 plots for fewer than
     *  5 units (else 3), one more for a naval army that cannot cross the ocean, and one more when the 19 plots around [tile] that a unit of
     *  the army's kind may stand on are not more than its units. Deviation: CvPlot::canPlaceCombatUnit is the tactical map's
     *  CanUseForOperationGathering.
     */
    fun gatherToleranceFor(civ: Civilization, operation: AiOperation, army: AiArmy, tile: Tile?): Int {
        if (tile == null) return 1
        val units = army.numSlotsFilled()
        var range = if (units < 5) 4 else 3
        val naval = operation.type.allNaval || operation.type.mixedLandNaval
        if (naval && !(civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean)) range++
        val map = civ.ai.tactical.map
        val oceanGoing = NavalOperationLogic.allOceanGoing(civ, army)
        var valid = 0
        for (nearby in tile.getTilesInDistance(2)) {
            if (naval != nearby.isWater || (naval && !oceanGoing && nearby.isOcean)) continue
            if (map.canUseForOperationGathering(nearby)) valid++
        }
        if (valid <= units) range++
        return range
    }

    /**
     *  CvAIOperationMilitary::CheckTransitionToNextStage by the army's state, for the military operations that go by land: the attacks, the
     *  pillage raid and the defense operations.
     *  Recruiting: an army with enough units ([haveEnoughUnits]) goes to gather; an initial cluster of units owned ground nearer the target
     *  than the muster point (more than 2 plots from it) becomes the muster point. Gathering: units that are within the tolerance of
     *  one another make their own place the muster point, and an army whose furthest unit is within the tolerance of the muster point
     *  moves out. Moving: an army whose center is within [DEPLOY_RANGE] plots of its goal and whose furthest unit is within
     *  twice that has arrived, as has one at peace with the enemy with more than 2 units in the enemy's sight (the jig is up, and
     *  the diplomatic AI is told: [MajorCivMemory.musteringForAttack]); the operation succeeds and the tactical AI takes over ([onSuccess]).
     *  Deviations: the army has no AT_DESTINATION state (the operation's SUCCESSFUL_FINISH ends it in the same sweep, and the movers'
     *  exhaustive states are another branch's), and the log of the progress over 5 turns is not kept.
     *  @return whether the state changed
     */
    fun checkTransitionToNextStage(civ: Civilization, operation: AiOperation): Boolean {
        val army = operation.firstArmy ?: return false
        val sea = operation.type.allNaval || operation.type.mixedLandNaval
        val muster = musterTile(civ, operation)
        return when (army.state) {
            ArmyState.WAITING_FOR_UNITS_TO_REINFORCE -> {
                var changed = false
                if (haveEnoughUnits(civ, army, 0)) {
                    army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
                    operation.state = OperationState.GATHERING_FORCES
                    changed = true
                }
                // if we have an initial cluster of units, everyone should go there
                val center = armyCenter(civ, army, clampToUnit = true)
                val target = targetTile(civ, operation)
                if (center != null && muster != null && target != null) {
                    val tolerance = gatherToleranceFor(civ, operation, army, muster)
                    // be a bit careful, don't have units hanging around just anywhere
                    if (center.varX < tolerance && center.varY < tolerance &&
                        center.tile.aerialDistanceTo(target) < muster.aerialDistanceTo(target) &&
                        center.tile.aerialDistanceTo(muster) > 2 && center.tile.getOwner() == civ) setMuster(operation, army, center.tile)
                }
                changed
            }
            ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> {
                val center = armyCenter(civ, army, clampToUnit = true) ?: return false
                var gatherPoint = muster ?: return false
                val tolerance = gatherToleranceFor(civ, operation, army, gatherPoint)
                if (center.varX < tolerance && center.varY < tolerance) {
                    // put the muster plot where our units are and go straight to movement phase
                    setMuster(operation, army, center.tile)
                    gatherPoint = center.tile
                }
                if (furthestUnitDistance(civ, army, gatherPoint) > tolerance) return false
                army.state = ArmyState.MOVING_TO_DESTINATION
                operation.state = OperationState.MOVING_TO_TARGET
                true
            }
            ArmyState.MOVING_TO_DESTINATION -> {
                // some ops are not intended to ever reach their target
                if (operation.type.isNeverEnding) return false
                val goal = tileAt(civ, army.goalTile) ?: return false
                val center = centerOfMass(civ, army, sea) ?: return false
                var inPlace = center.aerialDistanceTo(goal) <= DEPLOY_RANGE && furthestUnitDistance(civ, army, goal) < 2 * DEPLOY_RANGE
                // check for nearby enemy (for sneak attacks): the jig is up - happens also if we run up against the enemy border
                val enemy = enemy(civ, operation)
                val atPeace = enemy != null && !civ.isAtWarWith(enemy)
                if (!inPlace && enemy != null && atPeace &&
                    armyUnits(civ, army).count { (_, unit) -> unit.getTile() in enemy.viewableTiles } > UNITS_SEEN_GIVING_AWAY) inPlace = true
                if (!inPlace) return false
                // Notify Diplo AI we're in place for attack
                if (enemy != null && atPeace) civ.ai.diplomacy.memoryOf(enemy).musteringForAttack = true
                // that's it: the army is disbanded next turn, its units are free for the tactical AI
                operation.state = OperationState.SUCCESSFUL_FINISH
                // Retained from BNW's ArmyInPosition until the tactical map follows the focus areas: the attacked city is a temporary zone
                // (the naval invasion's one also lets ships and landing units close on it, as BNW's naval attack did)
                if (operation.type.isCityAttack || operation.type.isNavalInvasion || operation.type == AiOperationType.PURE_NAVAL_CITY_ATTACK)
                    focusPlot(civ, operation)?.takeIf { it.isCityCenter() }
                        ?.let { civ.ai.tactical.addTemporaryZone(it, TacticalTargetType.City, navalInvasion = operation.type.isNavalInvasion) }
                onSuccess(civ, operation)
                true
            }
        }
    }

    /**
     *  CvAIOperationMilitary::OnSuccess: the tactical AI focuses on the target for AI_TACTICAL_MAP_TEMP_ZONE_TURNS turns, within 3 plots
     *  ([TacticalState.addFocusArea]) - on the enemy's city when the target is not a city and one of theirs is less than 4 plots away.
     *  The nearest city is by plots, the original's by path length.
     */
    internal fun onSuccess(civ: Civilization, operation: AiOperation) {
        val plot = focusPlot(civ, operation) ?: return
        civ.ai.tactical.addFocusArea(plot, FOCUS_AREA_RADIUS, civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS))
    }

    /**
     *  The plot [onSuccess] focuses on: the operation's target, or the enemy's city less than [OWN_CITY_FOCUS_DISTANCE] plots from it when the
     *  target is not a city (the coastal water of a naval attack).
     */
    private fun focusPlot(civ: Civilization, operation: AiOperation): Tile? {
        val plot = targetTile(civ, operation) ?: return null
        val enemy = enemy(civ, operation)
        if (!plot.isCityCenter() && enemy != null) {
            val city = enemy.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(plot) }
            if (city != null && city.getCenterTile().aerialDistanceTo(plot) < OWN_CITY_FOCUS_DISTANCE) return city.getCenterTile()
        }
        return plot
    }

    /** The enemy city an attack is going for: the one on its target, or the one near the coastal water that is the target of a naval attack */
    fun attackedCity(civ: Civilization, operation: AiOperation): City? = focusPlot(civ, operation)?.takeIf { it.isCityCenter() }?.getCity()
    //endregion

    /** Turns a FOUND_CITY settler waits for an escort before it goes alone (CvAIOperationFoundCity::ShouldAbort) */
    private const val ESCORT_WAIT_TURNS = 15

    /** Turns after which any operation aborts (Vox Populi's CvAIOperation::ShouldAbort: `iTurns > 42`) */
    const val TIMEOUT_TURNS = 42

    /** Vox Populi's TACTICAL_COMBAT_MAX_TARGET_DISTANCE (CvTacticalAI.cpp): units less than this many plots from the target are not recruited; the tactical AI's units this close have arrived */
    const val MAX_TARGET_DISTANCE = 4

    /** MOVEFLAG_APPROX_TARGET_RING2 ([turnsToReach]): the plots this far from the target count as the target, and the nearest [RING_CANDIDATES] of them are tried */
    private const val RING_TWO = 2
    private const val RING_CANDIDATES = 3

    /** CvAIOperationMilitary::CheckTransitionToNextStage: more than this many of our units in sight of an enemy at peace give the army away */
    private const val UNITS_SEEN_GIVING_AWAY = 2

    /**
     *  CvAIOperationMilitary::GetDeployRange (CvAIOperation.h): 3 for every military operation - the attacks, the pillage raid, the rapid
     *  response and the city defense alike (the 1 of the same header is CvAIOperationCivilian's). Vox Populi has no
     *  AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE or AI_OPERATIONAL_PILLAGE_ENEMY_DEPLOY_RANGE define (BNW's 5 and 5); the land operations no
     *  longer read them. The naval operations still do ([NavalOperationLogic]), as does the tactical AI's deployment ([Deployment]).
     */
    const val DEPLOY_RANGE = 3

    /** CvAIOperationMilitary::Init asks CvPlayer::GetClosestCity for the muster city within this many plots */
    private const val MUSTER_RADIUS = 23

    /** CvAIOperationDefenseRapidResponse::VerifyOrAdjustTarget and FindBestTarget: a new position this far from the target replaces it, the search weighs positions by distance up to it */
    private const val RAPID_RESPONSE_RETARGET_RANGE = 5

    /** OperationalAIHelpers::FindEnemiesNearHomelandPlot: enemy units off our land count within this many steps of one of our cities */
    private const val HOMELAND_PATH_LENGTH = 6

    /** CvAIOperationMilitary::OnSuccess: the focus area's radius, and how close the enemy's city must be to take the place of a target that is no city */
    private const val FOCUS_AREA_RADIUS = 3
    private const val OWN_CITY_FOCUS_DISTANCE = 4

    /** CvUnit::canUseForAIOperation looks at the plots within this range for enemies (RING3_PLOTS) */
    private const val NEARBY_ENEMY_RANGE = 3

    /** The plots a turn the step finder of GrabUnitsFromTheReserves counts (it asks for [maxRecruitTurns] times this many steps) */
    private const val RECRUIT_PLOTS_PER_TURN = 4

    /** GrabUnitsFromTheReserves' ranking: the base, the penalty per turn to the muster point, the bonus for a unit that stays at its citadel */
    private const val BASE_RECRUIT_SCORE = 10000
    private const val RECRUIT_SCORE_PER_TURN = 30
    private const val CITADEL_GUARD_SCORE = 42
}
