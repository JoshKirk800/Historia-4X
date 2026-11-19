package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.battle.Nuke
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  The naval operations of CvAIOperation.cpp: CvAIOperationNavalSuperiority (the squadron that defends a threatened coastal city, Vox
 *  Populi's), CvAIOperationCityAttackNaval (ships alone, [AiOperationType.PURE_NAVAL_CITY_ATTACK]), CvAIOperationCityAttackCombined (ships
 *  and land units that embark, [AiOperationType.isNavalInvasion]) and CvAIOperationNukeAttack: Init and target finding. Their lifecycle is
 *  CvAIOperationMilitary's, [AiOperationLogic.checkTransitionToNextStage] and [AiOperationLogic.verifyOrAdjustTarget], and their armies are
 *  moved with every other army of the military operations ([com.unciv.logic.automation.unit.tactical.OperationalMoves], Vox Populi's
 *  CvTacticalAI::PlotArmyMovesCombat).
 *
 *  As in the original, land units embark themselves (there are no transports): an invasion army is the land units and the warships of its
 *  slots (UNITAI_ASSAULT_SEA is the Frigate, Galleass and the like), all gathering and sailing on water.
 *  Not ported: the colonization party of CvAINavalEscortedOperation (AI_OPERATION_COLONIZE), which no caller in the original ever creates.
 *  Deviations, with the reason: an army's operation area is the water at the muster point, which no city lies on, so cities do not train
 *  for them, their ships come from the reserves and the last one is bought (the original lets the city that owns the muster water train);
 *  FindBestTarget of the naval city attacks is "obsolete" in the original and is not ported (the target is the city chosen by the military
 *  AI); a naval target is visible to the original's whole-map loops only when revealed, as here.
 */
object NavalOperationLogic {

    /** CvPlot::isShallowWater */
    fun isShallowWater(tile: Tile) = tile.isWater && tile.getBaseTerrain().isCoast

    /**
     *  CvMilitaryAI::GetCoastalPlotAdjacentToTarget with an army: the first shallow water tile next to [target] that the army's first unit can
     *  get to (without an army, the first shallow water tile). The original asks TurnsToReachTarget with bIgnoreUnits: the path ignores the
     *  units on the way and on the tile (and, as CvAStar.cpp IgnoreUnitsValid tests canEnterTerritory with the unit's own team, borders).
     *  Here the step finder's sea route against [target]'s owner, which ignores units and may enter that owner's territory. A unit's own path
     *  (`canReach`) cannot cross closed borders, so before the war no water next to a grown city was a goal and every sneak invasion ended
     *  at once with LOST_PATH (a Domination game: 191 naval sneak attacks, none succeeded).
     */
    fun coastalPlotAdjacentToTarget(civ: Civilization, target: Tile, army: AiArmy?): Tile? {
        val first = army?.let { AiOperationLogic.armyUnits(civ, it).firstOrNull()?.second }
        val steps = civ.ai.military.steps
        for (adjacent in target.neighbors) {
            if (!isShallowWater(adjacent)) continue
            if (first == null) return adjacent
            if (first.getTile() == adjacent || steps.distance(civ, target.getOwner(), first.getTile(), adjacent, sea = true) >= 0) return adjacent
        }
        return null
    }

    //region Init

    /** CvAINavalOperation::GetOperationStartCity: the start city, else the first coastal city */
    fun startCity(civ: Civilization, operation: AiOperation): City? {
        operation.startCityId?.let { id -> civ.cities.firstOrNull { it.id == id }?.let { return it } }
        return civ.cities.firstOrNull { it.isCoastal() }
    }


    /**
     *  MilitaryAIHelpers::GetCoastalWaterNearPlot: shallow water within two plots of [target] that is not a bay (more than two sailable
     *  neighbours) and has no feature (ice); with [checkTeam] it must be the target owner's or nobody's. The original looks at the plots in
     *  one of three fixed orders picked by the target's pseudo-random seed, so as not to return the same plot every time; here each ring (the
     *  plot, the ring around it, the ring around that) is shuffled by a random number of the civilization's turn and the plot, as the
     *  layouts of the rings of Civ V's grid are not Unciv's. Null if there is none.
     */
    fun coastalWaterNearPlot(civ: Civilization, target: Tile, checkTeam: Boolean = false): Tile? {
        val random = civ.ai.random("CoastalWater${target.position}")
        val owner = target.getOwner()
        for (distance in 0..2) {
            val ring = if (distance == 0) listOf(target) else target.getTilesAtDistance(distance).toMutableList().also { it.shuffle(random) }
            for (tile in ring) {
                if (checkTeam && tile.getOwner() != owner && tile.getOwner() != null) continue
                if (isShallowWater(tile) && tile.terrainFeatures.isEmpty() && tile.neighbors.count { it.isWater && !it.isImpassible() } > 2) return tile
            }
        }
        return null
    }

    /**
     *  CvAIOperationNavalSuperiority::Init, which Vox Populi's CheckSeaDefenses starts for a threatened coastal city: a squadron that musters
     *  on the coastal water next to [muster] (else our coastal city nearest to [target] that has access to it, else the city itself) and goes to
     *  the coastal water next to [target]. Without water at either end the operation ends.
     */
    fun initSuperiority(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val targetCity = target ?: run { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        val musterCity = muster ?: AiOperationLogic.closestFriendlyCoastalCity(civ, targetCity.getCenterTile()) ?: targetCity
        // this is where we gather the army, and where the army should go
        val musterTile = coastalWaterNearPlot(civ, musterCity.getCenterTile())
        val targetTile = coastalWaterNearPlot(civ, targetCity.getCenterTile())
        if (musterTile == null || targetTile == null) {
            AiOperationLogic.abortOperation(operation, if (targetTile != null) OperationAbortReason.NO_MUSTER else OperationAbortReason.NO_TARGET)
            return
        }
        operation.startCityId = musterCity.id
        val army = civ.ai.operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        AiOperationLogic.setUpArmy(civ, operation, army, musterTile, targetTile)
    }

    /**
     *  OperationalAIHelpers::GetClosestCoastalCityPair: our coastal city and [enemy]'s coastal city that are the fewest steps apart over
     *  water (the step finder's sea route, [StepFinder.distance]: both on one body of water, deep ocean only if we can embark onto it), the
     *  first of the equally near ones; null if there is none. The original asks for the path of an army of ours that cannot cross the ocean
     *  (MOVEFLAG_NO_OCEAN) unless we can.
     *  @return our city (the muster) and theirs (the target)
     */
    fun closestCoastalCityPair(civ: Civilization, enemy: Civilization?): Pair<City, City>? {
        if (enemy == null) return null
        val steps = civ.ai.military.steps
        var best: Pair<City, City>? = null
        var bestDistance = Int.MAX_VALUE
        for (mine in civ.cities) {
            if (!mine.isCoastal()) continue
            for (theirs in enemy.cities) {
                if (!theirs.isCoastal()) continue
                val distance = steps.distance(civ, enemy, mine.getCenterTile(), theirs.getCenterTile(), sea = true)
                if (distance in 1 until bestDistance) { bestDistance = distance; best = mine to theirs }
            }
        }
        return best
    }

    /**
     *  CvAIOperationCityAttackNaval::Init (the ships alone, [AiOperationType.PURE_NAVAL_CITY_ATTACK]): for a target that is no coastal city the
     *  closest pair of coastal cities of us and the enemy ([closestCoastalCityPair]) replaces both; a muster that is no coastal city is our
     *  coastal city nearest to the target ([AiOperationLogic.closestFriendlyCoastalCity]). The fleet musters on the coastal water next to the
     *  muster ([coastalWaterNearPlot]) and its goal is the coastal water next to the target, which Vox Populi's CheckTransitionToNextStage
     *  and VerifyOrAdjustTarget (the target plot stays the enemy's) then judge; [AiOperationLogic.setUpArmy] recruits and ends the operation with
     *  no water at either end or no unit at hand. The start city, which buys the last unit, is the muster city.
     */
    fun initPureNavalAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        var targetCity = target
        var musterCity = muster
        if (targetCity == null || !targetCity.isCoastal()) {
            val pair = closestCoastalCityPair(civ, AiOperationLogic.enemy(civ, operation))
            if (pair != null) { musterCity = pair.first; targetCity = pair.second }
        }
        if ((musterCity == null || !musterCity.isCoastal()) && targetCity != null)
            musterCity = AiOperationLogic.closestFriendlyCoastalCity(civ, targetCity.getCenterTile())
        setUpNavalAttack(civ, operation, targetCity, musterCity)
    }

    /**
     *  CvAIOperationCityAttackCombined::Init (the ships with land units that embark, [AiOperationType.isNavalInvasion]; BNW's naval attack,
     *  naval sneak attack and city-state naval attack are the same): no target ends the operation; the muster is [muster] or our coastal
     *  city nearest to the target; the army musters on the coastal water next to it and its goal is the coastal water next to the target city,
     *  which the original wants to be the enemy's (the operation ends when anyone else owns it). BNW's goal on the city's own tile and its
     *  muster in our port are gone.
     */
    fun initNavalInvasion(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        if (target == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        val musterCity = muster ?: AiOperationLogic.closestFriendlyCoastalCity(civ, target.getCenterTile())
        if (musterCity == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        setUpNavalAttack(civ, operation, target, musterCity)
    }

    /** The end of both city attack Inits: AddArmy, SetUpArmy with the coastal water near the muster and near the target */
    private fun setUpNavalAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        // Target just off the coast - important that it is owned by the enemy: the operation terminates if that should ever change
        val targetPlot = target?.let { coastalWaterNearPlot(civ, it.getCenterTile()) }
        // Muster just off the coast
        val musterPlot = muster?.let { coastalWaterNearPlot(civ, it.getCenterTile()) }
        val army = civ.ai.operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        muster?.let { operation.startCityId = it.id }
        AiOperationLogic.setUpArmy(civ, operation, army, musterPlot, targetPlot)
    }

    /**
     *  CvAIOperationNukeAttack::Init: the nuke and enemy city of [FindBestTarget] fill the operation's one slot and, if it can, the nuke goes off at
     *  once (the operation is static: it only works with the unit already in place).
     */
    fun initNuke(civ: Civilization, operation: AiOperation) {
        val operations = civ.ai.operations
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        val best = findBestNukeTarget(civ, operation)
        if (best == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        val (unit, city) = best
        operation.targetTile = city.getCenterTile().position
        army.goalTile = city.getCenterTile().position
        operation.musterTile = unit.getTile().position
        army.position = unit.getTile().position
        operations.addUnit(army, unit, 0)
        operation.state = OperationState.AT_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        nukeInPosition(civ, operation, army)
    }
    //endregion

    //region Targets

    /**
     *  CvAIOperationNavalSuperiority::FindBestTarget: the coastal water next to the threatened coastal city (of the three most threatened,
     *  [CityThreat.threatenedCities]) that the squadron reaches by the shortest sea route from where it is now ([AiOperationLogic.checkpointTile]);
     *  the first of the three when it is nowhere. Null if no sea route leads to any of them.
     */
    fun findSuperiorityTarget(civ: Civilization, operation: AiOperation): Tile? {
        val current = operation.firstArmy?.let { AiOperationLogic.checkpointTile(civ, operation, it) }
        val enemy = AiOperationLogic.enemy(civ, operation)
        var best: Tile? = null
        var bestLength = Int.MAX_VALUE
        for (city in CityThreat.threatenedCities(civ, coastalOnly = true).take(SUPERIORITY_CITIES)) {
            if (current == null) return coastalWaterNearPlot(civ, city.getCenterTile(), checkTeam = true)
            val length = civ.ai.military.steps.distance(civ, enemy, current, city.getCenterTile(), sea = true)
            if (length in 0 until bestLength) { bestLength = length; best = coastalWaterNearPlot(civ, city.getCenterTile(), checkTeam = true) }
        }
        return best
    }

    /**
     *  CvAIOperationNavalSuperiority::VerifyOrAdjustTarget: the threatened coastal cities change, so the squadron's target follows them; with none
     *  to sail to the operation ends (NO_TARGET).
     */
    fun verifySuperiorityTarget(civ: Civilization, operation: AiOperation) {
        val better = findSuperiorityTarget(civ, operation)
        if (better == null) AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_TARGET)
        else AiOperationLogic.setTarget(operation, better)
    }

    /**
     *  CvAIOperationNukeAttack::FindBestTarget: of our nukes and the enemy's cities in the nuke's range, the pair with the best value: the city's
     *  population less a fifth of its damage; each tile in the blast radius adds or removes by who owns it (ours -1, -5 with an improvement, -1000
     *  with a resource; the enemy's +1, +2, +5; a third party's -1000, which would start a war), each unit on it (ours -2, the enemy's +2, a third
     *  party's -1000); a capital counts double.
     */
    fun findBestNukeTarget(civ: Civilization, operation: AiOperation): Pair<MapUnit, City>? {
        val enemy = AiOperationLogic.enemy(civ, operation) ?: return null
        var best: Pair<MapUnit, City>? = null
        var bestValue = 0
        for (nuke in civ.units.getCivUnits()) {
            if (!canNuke(nuke)) continue
            for (city in enemy.cities) {
                if (nuke.getTile().aerialDistanceTo(city.getCenterTile()) > nuke.getRange()) continue
                val value = nukeCityValue(civ, nuke, city)
                if (value > bestValue) { bestValue = value; best = nuke to city }
            }
        }
        return best
    }

    /** CvUnit::canNuke: a nuclear weapon that can still move */
    fun canNuke(unit: MapUnit) = unit.isNuclearWeapon() && unit.hasMovement()

    /** The value of the blast on [city] (see [findBestNukeTarget]) */
    fun nukeCityValue(civ: Civilization, nuke: MapUnit, city: City): Int {
        var value = city.population.population - (city.getMaxHealth() - city.health) * 100 / city.getMaxHealth() / 5
        city.getCenterTile().forEachTileInDistance(nuke.getNukeBlastRadius()) { tile ->
            val owner = tile.getOwner()
            if (owner == civ) {
                value -= 1
                if (tile.improvement != null && !tile.improvementIsPillaged) {
                    value -= 5
                    if (tile.resource != null) value -= 1000 // we aren't nuking our own resources
                }
            } else if (owner != null && civ.isAtWarWith(owner)) {
                value += 1
                if (tile.improvement != null && !tile.improvementIsPillaged) {
                    value += 2
                    if (tile.resource != null) value += 5 // we like nuking their resources
                }
            } else if (owner != null) value -= 1000 // this will trigger a war
            for (unit in tile.getUnits()) {
                if (unit.civ == civ) value -= 2
                else if (civ.isAtWarWith(unit.civ)) value += 2
                else if (owner != null) value -= 1000 // this will trigger a war
            }
        }
        if (city.isCapital()) value *= 2
        return value
    }
    //endregion

    //region ArmyInPosition

    /** CvAIOperationNukeAttack::ArmyInPosition: the nuke goes off at the target if it can */
    fun nukeInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val target = AiOperationLogic.targetTile(civ, operation)
        val nuke = AiOperationLogic.armyUnits(civ, army).firstOrNull()?.second
        if (target != null && nuke != null && canNuke(nuke) && Nuke.mayUseNuke(MapUnitCombatant(nuke), target)) {
            AiDecisionLog.log(civ) { "${nuke.name} nukes ${target.getCity()?.name} at ${target.position}" }
            Nuke.NUKE(MapUnitCombatant(nuke), target)
            operation.state = OperationState.SUCCESSFUL_FINISH
        }
        return true
    }
    //endregion

    /** CvArmyAI::IsAllOceanGoing: every ship of the army can enter the ocean (land units do not count) */
    fun allOceanGoing(civ: Civilization, army: AiArmy): Boolean =
        AiOperationLogic.armyUnits(civ, army).all { !it.second.baseUnit.isWaterUnit || !it.second.cache.cannotEnterOceanTiles }

    /** CvAIOperationNavalSuperiority::FindBestTarget looks at this many of the most threatened coastal cities */
    private const val SUPERIORITY_CITIES = 3
}
