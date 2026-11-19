package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.logic.battle.Nuke
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  The naval operations of CvAIOperation.cpp: CvAIOperationNavalSuperiority (the squadron that defends a threatened coastal city, Vox
 *  Populi's), CvAINavalOperation (CvAIOperationPureNavalCityAttack), CvAIOperationNavalAttack and its descendants NavalSneakAttack /
 *  NavalCityStateAttack (an invasion: land units that embark, with warships), and CvAIOperationNukeAttack: Init, target finding,
 *  ArmyInPosition, CheckOnTarget. The base machinery is [AiOperationLogic] (the squadron's lifecycle is its
 *  [AiOperationLogic.checkTransitionToNextStage]), the ships are moved by [com.unciv.logic.automation.unit.NavalOperationMoves].
 *
 *  As in the original, land units embark themselves (there are no transports): an invasion army is the land units and the warships of its
 *  slots (UNITAI_ASSAULT_SEA is the Frigate, Galleass and the like), all moving as a formation on water.
 *  Not ported: the colonization party of CvAINavalEscortedOperation (AI_OPERATION_COLONIZE), which no caller in the original ever creates.
 *  Deviations, with the reason: an army's operation area is the one the original gives it
 *  (superiority and pure naval attacks: the water at the muster point, which no city lies on, so cities do not train for
 *  them, their ships come from the reserves and the last one is bought; invasions: the landmass of the target); FindBestTarget of the
 *  invasions and the pure naval attack is "obsolete" in the original and is not ported (the target is the city chosen by the military
 *  AI); a naval target is visible to the original's whole-map loops only when revealed, as here.
 */
object NavalOperationLogic {

    private fun tileAt(civ: Civilization, coord: com.unciv.logic.map.HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

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
     *  CvAIOperationPureNavalCityAttack::Init: the fleet musters on a shallow water tile next to our port [muster], its goal is the enemy city
     *  [target].
     */
    fun initPureNavalAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val operations = civ.ai.operations
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        if (target == null || muster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.getCenterTile().position
        army.goalTile = target.getCenterTile().position
        operation.startCityId = muster.id
        // Muster just off the coast
        val coastalMuster = coastalPlotAdjacentToTarget(civ, muster.getCenterTile(), army)
        if (coastalMuster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        operation.area = coastalMuster.getContinent()
        operation.musterTile = coastalMuster.position
        army.position = coastalMuster.position
        operations.buildListOfUnitsWeStillNeedToBuild(operation)
        if (AiOperationLogic.grabUnitsFromTheReserves(civ, operation)) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else operation.state = OperationState.RECRUITING_UNITS
    }

    /**
     *  CvAIOperationNavalAttack::Init (also the sneak attack and the city-state attack): the invasion leaves [muster], our port, for the enemy
     *  city [target]; units are taken from the reserves for the muster (no target check, as in the original).
     */
    fun initNavalInvasion(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val operations = civ.ai.operations
        if (muster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        operation.startCityId = muster.id
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        if (target == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        val targetTile = target.getCenterTile()
        operation.targetTile = targetTile.position
        operation.area = targetTile.getContinent()
        army.goalTile = targetTile.position
        operation.musterTile = muster.getCenterTile().position
        army.position = muster.getCenterTile().position
        operations.buildListOfUnitsWeStillNeedToBuild(operation)
        if (AiOperationLogic.grabUnitsFromTheReserves(civ, operation)) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else operation.state = OperationState.RECRUITING_UNITS
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

    /** CvAIOperationNavalAttack::ArmyInPosition: within twice the usual range of the target the invasion is handed to the tactical AI */
    fun navalInvasionInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        if (operation.state != OperationState.MOVING_TO_TARGET) return AiOperationLogic.baseArmyInPosition(operation, army)
        val target = AiOperationLogic.targetTile(civ, operation) ?: return false
        val com = AiOperationLogic.centerOfMass(civ, army, sea = true)
        // Are we within tactical range of our target? (larger than usual range for a naval attack)
        if (com != null && com.aerialDistanceTo(target) <= civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE) * 2) {
            // Notify Diplo AI we're in place for attack
            AiOperationLogic.enemy(civ, operation)?.let { civ.ai.diplomacy.memoryOf(it).musteringForAttack = true }
            // Notify tactical AI to focus on this area
            civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.City, navalInvasion = true)
            operation.state = OperationState.SUCCESSFUL_FINISH
            AiOperationLogic.onSuccess(civ, operation)
            return true
        }
        return false
    }

    /**
     *  CvAIOperationPureNavalCityAttack::ArmyInPosition: gathered, the target must still be the enemy's; within 2 tiles of it the fleet is
     *  handed to the tactical AI with a zone around the city.
     */
    fun pureNavalAttackInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val target = AiOperationLogic.targetTile(civ, operation)
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                val changed = AiOperationLogic.baseArmyInPosition(operation, army)
                // Is target still under enemy control?
                if (target?.getOwner()?.civID != operation.enemyCivId) AiOperationLogic.abortOperation(operation, OperationAbortReason.TARGET_ALREADY_CAPTURED)
                return changed
            }
            OperationState.MOVING_TO_TARGET -> {
                val position = tileAt(civ, army.position)
                if (position != null && target != null && position.aerialDistanceTo(target) < 2) {
                    // Notify tactical AI to focus on this area
                    civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.City)
                    operation.state = OperationState.SUCCESSFUL_FINISH
                    AiOperationLogic.onSuccess(civ, operation)
                }
                return false
            }
            else -> return AiOperationLogic.baseArmyInPosition(operation, army)
        }
    }

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

    //region CheckOnTarget

    /**
     *  CvAIOperation::CheckOnTarget for the naval move types. Escort: gathering when the center of mass is within the gather tolerance of the
     *  muster and no unit is further than three times that; moving when it is within twice the city attack range of the target and
     *  no unit further than three times that range. Fleet: as the original's enemy territory check with the center of mass at sea.
     *  (The naval superiority squadron's is [AiOperationLogic.checkTransitionToNextStage].)
     *  @return whether an army was found in position
     */
    fun checkOnTarget(civ: Civilization, operation: AiOperation): Boolean {
        val range = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        for (army in operation.armies.toList()) {
            if (army.numSlotsFilled() < 1) continue
            val com = AiOperationLogic.centerOfMass(civ, army, sea = true)
            val muster = AiOperationLogic.musterTile(civ, operation)
            val target = AiOperationLogic.targetTile(civ, operation)
            when (operation.type.moveType) {
                OperationMoveType.NavalEscort -> when (operation.state) {
                    OperationState.GATHERING_FORCES -> {
                        val tolerance = gatherTolerance(civ, operation, army)
                        if (com != null && muster != null && com.aerialDistanceTo(muster) <= tolerance &&
                            AiOperationLogic.furthestUnitDistance(civ, army, muster) <= tolerance * 3) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    }
                    OperationState.MOVING_TO_TARGET ->
                        if (com != null && target != null && com.aerialDistanceTo(target) <= range * 2 &&
                            AiOperationLogic.furthestUnitDistance(civ, army, target) <= range * 3) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    else -> Unit
                }
                OperationMoveType.NavalFleet -> when (operation.state) {
                    OperationState.GATHERING_FORCES -> {
                        val tolerance = gatherTolerance(civ, operation, army)
                        if (com != null && muster != null && com.aerialDistanceTo(muster) <= tolerance &&
                            AiOperationLogic.furthestUnitDistance(civ, army, muster) <= tolerance * 3 / 2) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    }
                    OperationState.MOVING_TO_TARGET ->
                        if (com != null && target != null && com.aerialDistanceTo(target) <= range &&
                            AiOperationLogic.furthestUnitDistance(civ, army, target) <= range * 3 / 2) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    else -> Unit
                }
                else -> Unit
            }
        }
        return false
    }

    /**
     *  CvAIOperation::GetGatherTolerance for naval armies: the gather range for the number of units if the muster tile and the ones around it
     *  can take them (usable for gathering, water, and no ocean unless the whole army can cross it), else 3.
     *  The original checks the muster cell for every tile around it, so all of the tiles count or none does.
     */
    fun gatherTolerance(civ: Civilization, operation: AiOperation, army: AiArmy): Int {
        val units = army.numSlotsFilled()
        if (units < 1) return 0
        val range = AiOperationLogic.gatherRangeForXUnits(units)
        val muster = AiOperationLogic.musterTile(civ, operation) ?: return range
        val map = civ.ai.tactical.map
        var usable = map.canUseForOperationGathering(muster) && muster.isWater
        if (usable && !allOceanGoing(civ, army) && map.isOcean(muster)) usable = false
        val valid = if (usable) (2 * range + 1) * (2 * range + 1) else 0
        return if (valid > units) range else 3
    }

    /** CvArmyAI::IsAllOceanGoing: every ship of the army can enter the ocean (land units do not count) */
    fun allOceanGoing(civ: Civilization, army: AiArmy): Boolean =
        AiOperationLogic.armyUnits(civ, army).all { !it.second.baseUnit.isWaterUnit || !it.second.cache.cannotEnterOceanTiles }
    //endregion

    /** CvAIOperationNavalSuperiority::FindBestTarget looks at this many of the most threatened coastal cities */
    private const val SUPERIORITY_CITIES = 3
}
