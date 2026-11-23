package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ModConstants.BarbarianConstants
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.utils.hashOf
import com.unciv.utils.randomWeighted
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Why [BarbarianCampsLikeVp.spawnUnits] spawns: the `BarbSpawnReason` values of CvBarbarians that camps and barbarian cities use. */
enum class BarbarianSpawnReason {
    /** BARB_SPAWN_NEW_ENCAMPMENT: the defenders of a camp just made */
    NewEncampment,
    /** BARB_SPAWN_FROM_ENCAMPMENT: the counter of a camp ran out */
    FromEncampment,
    /** BARB_SPAWN_FROM_CITY: the counter of a barbarian city ran out */
    FromCity
}

/** The AI types GetRandomBarbarianUnitType prefers (a score bonus), see [BarbarianConstants.fastAttackUnitTypes] for how they are told apart here */
enum class BarbarianPreferredAi { Ranged, Defense, FastAttack, AttackSea }

/**
 *  Vox Populi's barbarian camps and spawners, read with `barbariansLikeVp` in place of the Brave New World model of [BarbarianManager]. One instance per call; the
 *  state lives in the [BarbarianManager.encampments]: `countdown` is the spawn counter of CvBarbarians (m_aiBarbSpawnerCounter, in the
 *  source -1 when idle and below -1 while a cleared camp counts as recently cleared; here a cleared camp or city is an entry with `destroyed` whose `countdown` is
 *  `-counter - 1`), `spawnedUnits` is m_aiBarbSpawnerNumUnitsSpawned and `attacked` m_abBarbSpawnerAttacked. Barbarian-held cities get an entry too, the first time a
 *  pass sees one (the source starts it in CvPlayer::acquireCity, at most one turn earlier).
 *
 *  Turns: the source's `getElapsedGameTurns` is `gameInfo.turns - 1` here, the same mapping the Brave New World model uses for its first turn (`turns == 1`).
 *  Randomness: VP seeds every roll from the game seed and the plot (CvSeeder, `randRangeInclusive`); here every roll is a [Random] seeded from the map seed, the turn,
 *  the plot and a salt, so a game replays identically.
 */
class BarbarianCampsLikeVp(private val manager: BarbarianManager) {
    private val gameInfo get() = manager.gameInfo
    private val tileMap get() = manager.tileMap
    private val constants: BarbarianConstants get() = gameInfo.ruleset.modOptions.constants.barbarianConstants
    private val raging get() = gameInfo.gameParameters.ragingBarbarians
    private val barbarianCiv by lazy { gameInfo.getBarbarianCivilization() }
    private val landmasses by lazy { Landmasses(tileMap) }
    private val campImprovements by lazy {
        gameInfo.ruleset.tileImprovements.values.filter { it.isBarbarianCampEquivalent(GameContext.IgnoreConditionals) }
    }

    /** CvGame::getElapsedGameTurns */
    val elapsedGameTurns get() = gameInfo.turns - 1

    private fun random(salt: String, tile: Tile, extra: Int = 0) =
        Random(hashOf(salt.hashCode(), tileMap.mapParameters.seed.hashCode(), gameInfo.turns, tile.position.hashCode(), extra))

    private val startEraNumber get() = gameInfo.ruleset.eras[gameInfo.gameParameters.startingEra]?.eraNumber ?: 0

    /** CvGame::getCurrentEra, the average era of the living civilizations */
    private val currentEra get() = DiplomacyTurn.gameEraNumber(barbarianCiv)

    //region schedule

    /**
     *  The camp count asked for by CvBarbarians::DoCamps before the valid tiles are known. On the initial turn it is the per-mille of the valid tiles
     *  (`BARBARIAN_CAMP_FIRST_TURN_PERCENT_OF_TARGET_TO_ADD` plus the starting era's number times `..._PER_ERA`, at least 1); later it is
     *  `BARBARIAN_CAMP_NUM_AFTER_INITIAL` on every `BARBARIAN_CAMP_SPAWN_RATE`th turn after the initial one and 0 on the others.
     *  [turnsSinceInitial] is `elapsed - BARBARIAN_INITIAL_SPAWN_TURN`.
     */
    fun campsAsked(initialSpawning: Boolean, turnsSinceInitial: Int): Int {
        if (initialSpawning) {
            val asked = constants.campFirstTurnPercentOfTarget + if (startEraNumber > 0) startEraNumber * constants.campFirstTurnPercentPerEra else 0
            return if (asked <= 0) 1 else asked
        }
        val rate = max(1, constants.campSpawnRate + if (raging) constants.campSpawnRateRaging else 0)
        if (turnsSinceInitial % rate != 0) return 0
        return constants.campNumAfterInitial + if (raging) constants.campNumAfterInitialRaging else 0
    }

    /** The units a new camp is given: BARBARIAN_NUM_UNITS_INITIAL_ENCAMPMENT_CREATION_SPAWN on the initial turn, BARBARIAN_NUM_UNITS_ENCAMPMENT_CREATION_SPAWN later (at least 1) */
    fun unitsOfNewCamp(initialSpawning: Boolean): Int {
        val base = if (initialSpawning) constants.numUnitsInitialEncampmentCreation else constants.numUnitsEncampmentCreation
        val ragingChange = if (!raging) 0 else if (initialSpawning) constants.numUnitsInitialEncampmentCreationRaging else constants.numUnitsEncampmentCreationRaging
        val perEra = if (initialSpawning) constants.numUnitsInitialEncampmentCreationPerEra else constants.numUnitsEncampmentCreationPerEra
        val units = base + ragingChange + currentEra * perEra / 100
        return if (units <= 0) 1 else units
    }

    /** The units a camp (BARBARIAN_NUM_UNITS_PER_ENCAMPMENT_SPAWN) or a barbarian city (BARBARIAN_NUM_UNITS_PER_CITY_SPAWN) asks for when its counter ran out */
    fun unitsPerSpawn(city: Boolean): Int {
        val base = if (city) constants.numUnitsPerCitySpawn else constants.numUnitsPerEncampmentSpawn
        val ragingChange = if (!raging) 0 else if (city) constants.numUnitsPerCitySpawnRaging else constants.numUnitsPerEncampmentSpawnRaging
        val perEra = if (city) constants.numUnitsPerCitySpawnPerEra else constants.numUnitsPerEncampmentSpawnPerEra
        return base + ragingChange + currentEra * perEra / 100
    }

    /** CvBarbarians::ShouldSpawnBarbFromCamp / ShouldSpawnBarbFromCity: after BARBARIAN_INITIAL_SPAWN_TURN_FROM_SPAWNER turns, the counter is 0 */
    fun shouldSpawn(entry: BarbarianEncampment) =
        elapsedGameTurns >= constants.initialSpawnTurnFromSpawner && entry.countdown == 0

    /**
     *  CvBarbarians::ActivateBarbSpawner: seed the counter of a camp or barbarian city, called when a camp is put down, after the units of a new camp and after units spawned
     *  from a spawner. Turns = (initial-turn delay) + base + random 0..rand, scaled by the raging multiplier, plus the reduction per unit spawned so far (the counter of
     *  units starts at -1, so the first activation adds a turn, as in the source), plus the difficulty's spawn delay, scaled by the game speed, at least
     *  BARBARIAN_MIN_SPAWN_DELAY.
     */
    fun activate(entry: BarbarianEncampment) {
        val tile = tileMap[entry.position]
        val city = tile.isCityCenter()
        var turns = if (elapsedGameTurns == max(constants.initialSpawnTurn, 0)) constants.initialSpawnSpawnerDelay else 0
        val random = random("BarbarianCampsLikeVp.activate", tile, entry.spawnedUnits)
        if (city) turns += constants.citySpawnDelay + random.nextInt(constants.citySpawnDelayRand + 1)
        else turns += constants.encampmentSpawnDelay + random.nextInt(constants.encampmentSpawnDelayRand + 1)
        if (raging) turns = turns * (if (city) constants.citySpawnDelayRagingMultiplier else constants.encampmentSpawnDelayRagingMultiplier) / 100
        turns += if (city) constants.citySpawnDelayReductionPerSpawn * min(-constants.cityMaxSpawnDelayReduction, entry.spawnedUnits)
            else constants.encampmentSpawnDelayReductionPerSpawn * min(-constants.encampmentMaxSpawnDelayReduction, entry.spawnedUnits)
        entry.spawnedUnits++
        turns += gameInfo.ruleset.difficulties[gameInfo.gameParameters.difficulty]!!.barbarianSpawnDelay
        turns = turns * (gameInfo.speed.barbarianModifier * 100).roundToInt() / 100
        entry.countdown = max(turns, max(constants.minSpawnDelay, 1))
    }

    /** CvBarbarians::DoBarbSpawnerAttacked: only the first attack on a spawner each turn cuts its counter (to the attacked multiplier, never below 0) */
    fun spawnerAttacked(entry: BarbarianEncampment) {
        if (entry.attacked) return
        val city = tileMap[entry.position].isCityCenter()
        val multiplier = if (city) constants.cityAttackedDelayMultiplier else constants.encampmentAttackedDelayMultiplier
        entry.countdown = max(entry.countdown * multiplier / 100, 0)
        entry.attacked = true
    }

    /** CvBarbarians::DoBarbCampCleared / DoBarbCityCleared: the spawner is idle, forgets its units and the place counts as recently cleared for BARBARIAN_CAMP_CLEARED_MIN_TURNS_TO_RESPAWN turns */
    private fun markCleared(entry: BarbarianEncampment) {
        entry.destroyed = true
        entry.attacked = false
        entry.spawnedUnits = -1
        entry.countdown = constants.campClearedMinTurnsToRespawn
    }

    //endregion

    //region CvBarbarians::DoCamps

    private fun entryOf(tile: Tile, byPosition: MutableMap<HexCoord, BarbarianEncampment>): BarbarianEncampment {
        byPosition[tile.position]?.takeIf { !it.destroyed }?.let { return it }
        // a camp or barbarian city the manager has no record of (or only a cleared one): a counter at 0 for camps made outside DoCamps, seeded now for
        // cities, as CvPlayer::acquireCity does
        val entry = BarbarianEncampment(tile.position)
        entry.gameInfo = gameInfo
        manager.encampments.removeAll { it.position == tile.position }
        manager.encampments.add(entry)
        byPosition[tile.position] = entry
        if (tile.isCityCenter()) activate(entry)
        return entry
    }

    /**
     *  CvBarbarians::DoCamps, once per turn for the barbarians: every camp and barbarian city counts its spawner down and spawns, a cleared one is remembered, and, while the
     *  turn schedules camps, every eligible tile is collected, the ones too close to a major capital (or, on the initial turn of an early start, to a starting settler), a
     *  camp or barbarian city, or a recently cleared camp are dropped and camps are put on the rest (a third of the picks on the coast if one is valid).
     *  A departure: VP's loop that drops the tiles near a camp just placed reads an emptied list (its `vValidPlots[iI]` after `vValidPlots.clear()`), so no later camp of the
     *  same turn would be kept apart; the intended rule (distance above BARBARIAN_CAMP_MINIMUM_DISTANCE_ANOTHER_CAMP from the new camp) is applied here.
     */
    fun doCamps() {
        if (gameInfo.gameParameters.noBarbarians || campImprovements.isEmpty()) return
        val initialTurn = max(constants.initialSpawnTurn, 0)
        val elapsed = elapsedGameTurns
        if (elapsed < initialTurn) return
        val initialSpawning = elapsed == initialTurn
        var campsToAdd = campsAsked(initialSpawning, elapsed - initialTurn)

        val byPosition = HashMap<HexCoord, BarbarianEncampment>()
        for (entry in manager.encampments.toList()) {
            if (!entry.destroyed) {
                val tile = tileMap[entry.position]
                val held = tile.isBarbarianEncampment() || (tile.isCityCenter() && tile.getCity()!!.civ.isBarbarian)
                if (!held) markCleared(entry)
            }
            byPosition[entry.position] = entry
        }

        val minDistanceCapital = constants.campMinimumDistanceCapital
        val minDistanceCamp = constants.campMinimumDistanceAnotherCamp
        val minDistanceCleared = constants.campMinimumDistanceRecentlyClearedCamp
        val avoidStartingSettlers = initialSpawning && (gameInfo.ruleset.eras[gameInfo.gameParameters.startingEra]?.startingSettlerCount ?: 1) <= 1
        val potential = ArrayList<Tile>()
        val capitals = ArrayList<Tile>()
        val camps = ArrayList<Tile>()
        val recentlyCleared = ArrayList<Tile>()
        val finished = ArrayList<BarbarianEncampment>()
        val visibleTiles by lazy {
            gameInfo.civilizations.filterNot { it.isBarbarian || it.isSpectator }.flatMapTo(HashSet()) { it.viewableTiles }
        }

        for (tile in tileMap.values) {
            if (tile.isBarbarianEncampment()) {
                val entry = entryOf(tile, byPosition)
                entry.attacked = false
                if (entry.countdown > 0) entry.countdown--
                if (shouldSpawn(entry)) spawnUnits(tile, unitsPerSpawn(city = false), BarbarianSpawnReason.FromEncampment)
                if (minDistanceCamp > 0) camps += tile
                continue
            }
            if (tile.isCityCenter()) {
                val city = tile.getCity()!!
                if (city.civ.isBarbarian) {
                    val entry = entryOf(tile, byPosition)
                    entry.attacked = false
                    if (entry.countdown > 0) entry.countdown--
                    if (shouldSpawn(entry)) spawnUnits(tile, unitsPerSpawn(city = true), BarbarianSpawnReason.FromCity)
                    if (minDistanceCamp > 0) camps += tile  // cities count as camps for the distance, not for any target
                } else if (minDistanceCapital > 0 && city.civ.isMajorCiv && city.isCapital()) capitals += tile
                continue
            }
            val ghost = byPosition[tile.position]?.takeIf { it.destroyed }
            if (ghost != null) {
                if (ghost.countdown > 0) {
                    ghost.countdown--
                    if (ghost.countdown > 0 && minDistanceCleared > 0) recentlyCleared += tile
                    if (ghost.countdown == 0) finished += ghost
                    continue
                }
                finished += ghost
            }
            if (avoidStartingSettlers && tile.getUnits().any { !it.civ.isBarbarian && it.baseUnit.hasUnique(UniqueType.FoundCity) }) {
                capitals += tile
                continue
            }
            if (campsToAdd <= 0) continue
            if (isEligible(tile) && (constants.campsOnVisibleTiles || tile !in visibleTiles)) potential += tile
        }
        manager.encampments.removeAll(finished.toSet())
        if (campsToAdd <= 0) return

        // Too close to a major capital, a camp or barbarian city, or a recently cleared camp
        var valid = potential.filter { tile ->
            capitals.none { it.aerialDistanceTo(tile) <= minDistanceCapital } &&
                camps.none { it.aerialDistanceTo(tile) <= minDistanceCamp } &&
                recentlyCleared.none { it.aerialDistanceTo(tile) <= minDistanceCleared }
        }
        val unitsOfCamp = unitsOfNewCamp(initialSpawning)
        if (initialSpawning) campsToAdd = max(1, campsToAdd * valid.size / 1000)

        var placed = 0
        while (campsToAdd > 0) {
            val coastal = valid.filter { it.isAdjacentToCoast() }
            val random = Random(hashOf("BarbarianCampsLikeVp.camps".hashCode(), tileMap.mapParameters.seed.hashCode(), gameInfo.turns, placed))
            val relevant = if (coastal.isNotEmpty() && random.nextInt(1, 101) <= constants.campCoastalSpawnRoll) coastal else valid
            if (relevant.isEmpty()) break
            val tile = relevant[random.nextInt(relevant.size)]
            placeCamp(tile, unitsOfCamp, random)
            placed++
            campsToAdd--
            valid = valid.filter { it != tile && it.aerialDistanceTo(tile) > minDistanceCamp }
        }
    }

    /** The tests of DoCamps up to the lists: the tile may host a camp, whatever the camps, capitals and visibility near it */
    private fun isEligible(tile: Tile): Boolean {
        val improvement = tile.tileImprovement
        // No camps on ruins (VP's goody huts), great person improvements or other unpillagable ones (VP's `Permanent`), or natural wonders
        if (improvement != null && (improvement.isAncientRuinsEquivalent() || improvement.isGreatImprovement() || improvement.hasUnique(UniqueType.Unpillagable)))
            return false
        if (tile.isNaturalWonder()) return false
        if (!tile.isLand || tile.isImpassible()) return false
        if (landmasses.sizeOf(tile) < constants.campMinimumIslandSize) return false
        // No camps in owned territory or next to it (the barbarians' own do not count)
        if (tile.getOwner() != null) return false
        if (tile.neighbors.any { val owner = it.getOwner(); owner != null && !owner.isBarbarian }) return false
        // No camps on occupied tiles or next to a unit of anyone else
        if (tile.getUnits().any() || tile.neighbors.any { neighbor -> neighbor.getUnits().any { !it.civ.isBarbarian } }) return false
        // Where the camp improvement may stand: its terrains, and the features that allow no improvements stay out
        if (campImprovements.none { it.canBeBuiltOn(tile.getBaseTerrain()) }) return false
        if (tile.terrainFeatureObjects.any { it.hasUnique(UniqueType.RestrictedBuildableImprovements) }) return false
        return true
    }

    /** CvPlot::setImprovementType(camp) with route removal, then SpawnBarbarianUnits(NEW_ENCAMPMENT) and ActivateBarbSpawner, as DoCamps does */
    private fun placeCamp(tile: Tile, units: Int, random: Random) {
        tile.removeRoad()
        manager.createNewCamp(tile, random)
        manager.notifyCivsOfBarbarianEncampment(tile)
        spawnUnits(tile, units, BarbarianSpawnReason.NewEncampment)
        activate(manager.encampments.first { it.position == tile.position })
    }

    //endregion

    //region CvBarbarians::SpawnBarbarianUnits

    /**
     *  CvBarbarians::SpawnBarbarianUnits for the reasons of camps and barbarian cities: an empty spawner is filled first (no ranged unit for a camp unless on a one-tile
     *  island); a spawner with barbarian military units within the range of [BarbarianConstants.maxBarbariansFromCampNearbyRange] (not counting the one on it) at the cap spawns
     *  nothing; the rest go to random empty passable tiles next to it (boats from [BarbarianConstants.navalUnitStartTurn], an empty camp or city first), the cap cutting their number;
     *  from a spawner the counter is seeded again if anything was spawned. Units may use a strategic resource within 3 tiles that no other civilization owns.
     *  Returns the units spawned.
     */
    fun spawnUnits(tile: Tile, unitsAsked: Int, reason: BarbarianSpawnReason): List<MapUnit> {
        val spawned = ArrayList<MapUnit>()
        if (unitsAsked <= 0 || gameInfo.gameParameters.noBarbarians) return spawned
        var numUnits = unitsAsked
        val (maxBarbarians, range) = when (reason) {
            BarbarianSpawnReason.NewEncampment -> Pair(Int.MAX_VALUE, 0)
            BarbarianSpawnReason.FromEncampment -> Pair(max(constants.maxBarbariansFromCampNearby, 1), constants.maxBarbariansFromCampNearbyRange)
            BarbarianSpawnReason.FromCity -> Pair(max(constants.maxBarbariansFromCityNearby, 1), constants.maxBarbariansFromCityNearbyRange)
        }

        var count = 0
        var spawnOnTile = true
        for (unit in barbarianCiv.units.getCivUnits()) {
            if (!unit.isMilitary()) continue
            val unitTile = unit.getTile()
            if (unitTile == tile) spawnOnTile = false
            else if (unitTile.aerialDistanceTo(tile) <= range) count++
        }
        // A defended spawner at the cap spawns nothing (and keeps its counter, it tries again next turn)
        if (!spawnOnTile && count >= maxBarbarians) return spawned

        val validResources = validResources(tile)
        fun spawnOn(spawnTile: Tile, preferred: BarbarianPreferredAi, allowRanged: Boolean): Boolean {
            val unit = chooseUnit(spawnTile, preferred, allowRanged, validResources, random("BarbarianCampsLikeVp.unit", spawnTile, spawned.size)) ?: return false
            val mapUnit = tileMap.placeUnitNearTile(spawnTile.position, unit, barbarianCiv) ?: return false
            mapUnit.currentMovement = 0f
            spawned += mapUnit
            return true
        }

        if (spawnOnTile) {
            val isCamp = tile.isBarbarianEncampment()
            val allowRanged = !isCamp || (tile.isLand && landmasses.sizeOf(tile) == 1)
            val preferred = if (reason == BarbarianSpawnReason.FromCity || (isCamp && allowRanged)) BarbarianPreferredAi.Ranged else BarbarianPreferredAi.Defense
            if (spawnOn(tile, preferred, allowRanged)) {
                count++
                numUnits--
            }
        }

        if (count >= maxBarbarians || numUnits == 0) {
            if (reason != BarbarianSpawnReason.NewEncampment && spawned.isNotEmpty()) activateAt(tile)
            return spawned
        }
        if (count + numUnits >= maxBarbarians) numUnits = maxBarbarians - count

        val canSpawnBoats = reason != BarbarianSpawnReason.NewEncampment && gameInfo.turns >= constants.navalUnitStartTurn
        val ring = tile.neighbors.filter { neighbor ->
            !neighbor.isImpassible() &&
                !(neighbor.isWater && neighbor.terrainHasUnique(UniqueType.FreshWater)) && // no lakes
                !(neighbor.isCityCenter() && !neighbor.getCity()!!.civ.isBarbarian) &&
                (canSpawnBoats || !neighbor.isWater) &&
                neighbor.getUnits().none()
        }.toMutableList()
        val priority = ring.filter { it.isCityCenter() || it.isBarbarianEncampment() }.toMutableList()
        while (numUnits > 0 && ring.isNotEmpty()) {
            val random = random("BarbarianCampsLikeVp.spot", tile, spawned.size)
            val prioritySpawn = priority.isNotEmpty()
            val spawnTile = if (prioritySpawn) priority[random.nextInt(priority.size)] else ring[random.nextInt(ring.size)]
            var preferred = if (spawnTile.isWater) BarbarianPreferredAi.AttackSea else BarbarianPreferredAi.FastAttack
            var allowRanged = true
            if (prioritySpawn) {
                // An undefended city or camp is filled with a defender (ranged in a city and on a one-tile island)
                if (spawnTile.isCityCenter() || (spawnTile.isLand && landmasses.sizeOf(spawnTile) == 1)) preferred = BarbarianPreferredAi.Ranged
                else {
                    preferred = BarbarianPreferredAi.Defense
                    allowRanged = false
                }
            }
            if (spawnOn(spawnTile, preferred, allowRanged)) numUnits--
            ring.remove(spawnTile)
            priority.remove(spawnTile)
        }
        if (reason != BarbarianSpawnReason.NewEncampment && spawned.isNotEmpty()) activateAt(tile)
        return spawned
    }

    private fun activateAt(tile: Tile) {
        manager.encampments.firstOrNull { it.position == tile.position && !it.destroyed }?.let { activate(it) }
    }

    /** The resources of the spawner's own tile and, within 3 tiles, those on tiles nobody but the barbarians owns */
    private fun validResources(tile: Tile): Set<String> {
        val resources = HashSet<String>()
        tile.resource?.let { resources += it }
        for (near in tile.getTilesInDistance(3)) {
            val owner = near.getOwner()
            if (owner != null && !owner.isBarbarian) continue
            near.resource?.let { resources += it }
        }
        return resources
    }

    /** The preferred AI type of CvBarbarians::GetRandomBarbarianUnitType, approximated as described at [BarbarianConstants.fastAttackUnitTypes] */
    fun matchesPreferredAi(unit: BaseUnit, preferred: BarbarianPreferredAi): Boolean = when (preferred) {
        BarbarianPreferredAi.Ranged -> unit.isLandUnit && unit.isRanged() && !unit.hasUnique(UniqueType.IndirectFire)
        BarbarianPreferredAi.Defense -> unit.isLandUnit && unit.isMelee() && unit.type.name in constants.defenseUnitTypes
        BarbarianPreferredAi.FastAttack -> unit.isLandUnit && unit.isMelee() && unit.type.name in constants.fastAttackUnitTypes
        BarbarianPreferredAi.AttackSea -> unit.isWaterUnit && !unit.isRanged()
    }

    /**
     *  CvBarbarians::GetRandomBarbarianUnitType: a military unit of the tile's domain that the barbarians can build (the techs they know, not obsolete, not unique to a
     *  nation) and, if it needs strategic resources, all of them in [validResources]; ranged ones only if [allowRanged]. A unit is scored by its ranged strength (else its
     *  strength), +50% (BARBARIAN_UNIT_SPAWN_NO_RESOURCE_MULTIPLIER) if it needs no resource, ×2 (BARBARIAN_UNIT_SPAWN_PREFERRED_TYPE) if it is of the [preferred] AI type;
     *  the best BARBARIAN_UNIT_SPAWN_NUM_CANDIDATES are drawn from by their scores.
     */
    fun chooseUnit(tile: Tile, preferred: BarbarianPreferredAi, allowRanged: Boolean, validResources: Set<String>, random: Random): BaseUnit? {
        val scored = scoredUnits(tile, preferred, allowRanged, validResources)
        if (scored.isEmpty()) return null
        val best = scored.sortedByDescending { it.second }.take(constants.unitSpawnNumCandidates.coerceIn(1, scored.size))
        return best.map { it.first }.randomWeighted(best.map { it.second.toFloat() }, random)
    }

    /** The candidates of [chooseUnit] with their scores, in ruleset order */
    fun scoredUnits(tile: Tile, preferred: BarbarianPreferredAi, allowRanged: Boolean, validResources: Set<String>): List<Pair<BaseUnit, Int>> {
        val state = GameContext(civInfo = barbarianCiv)
        val scored = ArrayList<Pair<BaseUnit, Int>>()
        for (unit in gameInfo.ruleset.units.values) {
            if (!unit.isMilitary || unit.hasUnique(UniqueType.CannotAttack) || unit.hasUnique(UniqueType.CannotBeBarbarian)) continue
            if (if (tile.isWater) !unit.isWaterUnit else !unit.isLandUnit) continue
            if (!allowRanged && unit.isRanged()) continue
            if (!unit.isBuildable(barbarianCiv)) continue
            val needed = unit.getResourceRequirementsPerTurn(state).keys + unit.getStockpiledResourceRequirements(state).keys
            if (needed.any { it !in validResources }) continue
            var score = if (unit.rangedStrength > 0) unit.rangedStrength else unit.strength
            if (needed.isEmpty()) score = score * constants.unitSpawnNoResourceMultiplier / 100
            if (matchesPreferredAi(unit, preferred)) score = score * constants.unitSpawnPreferredTypeMultiplier / 100
            if (score > 0) scored += unit to score
        }
        return scored
    }

    //endregion
}
