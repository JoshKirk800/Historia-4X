package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.automation.unit.PlotDefense
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.sqrt

/**
 *  One dominance zone of the tactical analysis map (CvTacticalDominanceZone): the tiles of one kind of land around one city (or one
 *  landmass of unclaimed land, or one temporary zone) and how strong each side is in it.
 *
 *  Strengths are Vox Populi's (CvTacticalAnalysisMap::CalculateMilitaryStrengths): a unit's strength x100 and the zone's modifiers, a city
 *  of the zone its strength x100 x hit points left, in the buckets melee (land units' own strength), naval, ranged (land and air units
 *  over land, and the zone's city) and naval ranged (ships' ranged and air units over water); [overallFriendlyStrength] and
 *  [overallEnemyStrength] are what [setOverallDominance] compares.
 */
class DominanceZone(
    /** CvTacticalDominanceZone::GetDominanceZoneID: the index at the time of creation, what [TacticalMap.zoneOf] and the cells refer to */
    val id: Int,
    val territory: TacticalTerritory,
    /** The owner of the first tile of the zone, null for none (and always for temporary zones) */
    val owner: Civilization?,
    /** The landmass or body of water of the zone (CvPlot::getArea) */
    val area: Int,
    val isWater: Boolean,
    /** The nearest city of [owner] to the first tile of the zone, null if unowned (CvTacticalDominanceZone::GetClosestCity) */
    val closestCity: City?,
    /** The center of a [TacticalTerritory.TempZone] */
    val tempZoneCenter: Tile? = null,
    val isNavalInvasion: Boolean = false,
) {
    var friendlyMeleeStrength = 0
    var friendlyNavalStrength = 0
    var friendlyRangedStrength = 0
    var friendlyNavalRangedStrength = 0
    var enemyMeleeStrength = 0
    var enemyNavalStrength = 0
    var enemyRangedStrength = 0
    var enemyNavalRangedStrength = 0
    /** What units of players neither friendly nor at war with us and neutral cities add (CvTacticalDominanceZone::GetNeutralStrength) */
    var neutralStrength = 0

    /** Land units counted (CvTacticalDominanceZone::GetFriendlyUnitCount): air units add strength but are not counted */
    var friendlyLandUnitCount = 0
    var friendlyNavalUnitCount = 0
    var enemyLandUnitCount = 0
    var enemyNavalUnitCount = 0
    var neutralUnitCount = 0
    /** How many of the counted friendly land and naval units attack at range */
    var friendlyRangedUnitCount = 0
    /** Adjacent revealed zones, populated by CvTacticalAnalysisMap::EstablishZoneNeighborhood. */
    val neighbors = ArrayList<DominanceZone>()
    /** CvTacticalDominanceZone::GetCenterX/Y as a tile ([TacticalMap.computeZoneCenters]), null for a zone with no tile or a center off the map */
    var centerTile: Tile? = null

    var dominance = TacticalDominance.NoUnitsVisible
    /** CvTacticalDominanceZone::GetDominanceZoneValue, set by [TacticalMap]'s PrioritizeZones */
    var value = 0

    /** CvTacticalDominanceZone::GetOverallFriendlyStrength: melee strength x4/3 (siege units are very vulnerable to melee units) plus the others */
    val overallFriendlyStrength get() = friendlyMeleeStrength * 4 / 3 + friendlyNavalStrength + friendlyRangedStrength + friendlyNavalRangedStrength
    /** CvTacticalDominanceZone::GetOverallEnemyStrength */
    val overallEnemyStrength get() = enemyMeleeStrength * 4 / 3 + enemyNavalStrength + enemyRangedStrength + enemyNavalRangedStrength
    /** CvTacticalDominanceZone::GetTotalFriendlyUnitCount */
    val totalFriendlyUnitCount get() = friendlyLandUnitCount + friendlyNavalUnitCount
    /** CvTacticalDominanceZone::GetTotalEnemyUnitCount */
    val totalEnemyUnitCount get() = enemyLandUnitCount + enemyNavalUnitCount

    /** The ranged strength that decides the zone's ranged dominance: CvTacticalDominanceZone::GetRangedDominanceFlag reads the land buckets, GetNavalRangedDominanceFlag the naval ones */
    val friendlyDomainRangedStrength get() = if (isWater) friendlyNavalRangedStrength else friendlyRangedStrength
    val enemyDomainRangedStrength get() = if (isWater) enemyNavalRangedStrength else enemyRangedStrength

    /**
     *  CvTacticalDominanceZone::SetOverallDominance: [TacticalDominance.NoUnitsVisible] when neither side has any strength (a city counts),
     *  Friendly when only we have, Even when one enemy unit faces at most one of ours (one unit may tip the balance); otherwise the ratio of
     *  the overall strengths decides: we need more than 100 + [dominancePercentage] (at least 10, and 30 more in enemy territory, where
     *  units we cannot see may lurk), the enemy more than 100 + [dominancePercentage] (at most 90) of ours. Rounded as the original does.
     */
    fun setOverallDominance(dominancePercentage: Int): TacticalDominance {
        val friendly = overallFriendlyStrength
        val enemy = overallEnemyStrength
        dominance = when {
            enemy == 0 && friendly == 0 -> TacticalDominance.NoUnitsVisible
            enemy == 0 && totalFriendlyUnitCount > 0 -> TacticalDominance.Friendly
            totalEnemyUnitCount == 1 && totalFriendlyUnitCount <= 1 -> TacticalDominance.Even
            else -> {
                var friendlyPercentage = maxOf(10, dominancePercentage)
                val enemyPercentage = minOf(90, dominancePercentage)
                if (territory == TacticalTerritory.Enemy) friendlyPercentage += 30
                val friendToEnemy = (0.5f + 100 * (friendly.toFloat() / maxOf(1, enemy))).toInt()
                val enemyToFriend = (0.5f + 100 * (enemy.toFloat() / maxOf(1, friendly))).toInt()
                when {
                    friendToEnemy > 100 + friendlyPercentage -> TacticalDominance.Friendly
                    enemyToFriend > 100 + enemyPercentage -> TacticalDominance.Enemy
                    else -> TacticalDominance.Even
                }
            }
        }
        return dominance
    }

    /** CvTacticalDominanceZone::GetZoneType is not needed: [territory] and [dominance] say the same */
    override fun toString() = "Zone $id $territory ${closestCity?.name ?: "-"} ${if (isWater) "water" else "land"} $dominance " +
        "friendly $overallFriendlyStrength ($totalFriendlyUnitCount) enemy $overallEnemyStrength ($totalEnemyUnitCount) value $value"
}

/**
 *  CvTacticalAnalysisMap for one civilization and turn ([TacticalState.map]): per tile revealed/visible, impassable terrain or territory,
 *  water/ocean, cities, ownership, defense modifier and the military and civilian units standing on it (cells, read by tile), and the
 *  dominance zones ([zones], most valuable first) with their strengths, dominance flag and value. Also, per tile, whether an enemy can
 *  attack it next turn ([isSubjectToAttack]) or move past it ([enemyCanMovePast]), worked out on first use.
 *
 *  Source: CvTacticalAnalysisMap::PopulateCell, PrioritizeZones, MarkCellsNearEnemy and Vox Populi 5.4.8's CreateDominanceZones,
 *  EstablishZoneNeighborhood, CalculateMilitaryStrengths and CvTacticalDominanceZone::SetOverallDominance.
 *
 *  Deviations:
 *  - units of the cells are only recorded on visible tiles (the original reads every tile); the strength sums see every unit, those we
 *    cannot see at half strength, as Vox Populi does;
 *  - the strengths take a unit's base strength (see [PlotDanger]) and ranged strength, less what wounds take, and the zone's modifiers; the
 *    original's GetMaxAttackStrength and GetMaxRangedCombatStrength add the promotions, terrain, great generals and the rest;
 *  - a citadel is an improvement of the unit's owner that damages adjacent enemy units for more than [ENEMY_HEAL_RATE] and defends at
 *    [CITADEL_MIN_DEFENSE]% or more; "units at war with us are enemies" is as the original, one team being one civilization;
 *  - closest cities are chosen by hex distance within five tiles; equal-distance ties use civilization/city iteration order. Existing
 *    temporary operation zones remain until operations use VP focus areas, and all adjacent hexes define zone neighbors;
 *  - the game's era is the average of the living civilizations' eras (CvGame::getCurrentEra);
 *  - "an enemy can reach the plot" is the hex distance within the unit's base moves on its landmass or body of water instead of the
 *    ignore-units path finder (as in [PlotDanger]); the not-visible-to-enemy flag is not kept;
 *  - the dynamic flags of the attack routines (friendly turn end tile, within range of target, flank bonus) are here; the deployment
 *    ones (safe for deployment, deployment score) belong to the deployment code and are not.
 */
class TacticalMap(private val civ: Civilization, state: TacticalState) {
    private val rules = civ.gameInfo.ruleset.ai
    private val tileList = civ.gameInfo.tileMap.tileList
    private val numTiles = tileList.size

    /** The radius around its city in which a temporary zone takes land and the deployment code places units: ((AI_TACTICAL_RECRUIT_RANGE + game era) x 2) / 3, growing as the game goes on */
    val tacticalRange: Int
    /** How far from the city of a zone its strength sums count units that stand outside the zone: (AI_TACTICAL_RECRUIT_RANGE + game era) / 2 (CalculateMilitaryStrengths' iMaxDistance) */
    private val maxUnitDistance: Int
    private val dominancePercentage = rules.define(AiDefine.AI_TACTICAL_MAP_DOMINANCE_PERCENTAGE)

    /** The longest range of our units that count in a zone (CvTacticalAnalysisMap::GetBestFriendlyRange) */
    var bestFriendlyRange = 0
        private set
    /** Whether one of our units that count in a zone ignores line of sight (CvTacticalAnalysisMap::IsIgnoreLOS) */
    var ignoreLineOfSight = false
        private set

    private val flags = IntArray(numTiles)
    private val defenseModifiers = IntArray(numTiles)
    private val zoneIds = IntArray(numTiles) { -1 }
    private val areas = IntArray(numTiles)
    private val reach = ByteArray(numTiles)
    private val turnEndTiles = BooleanArray(numTiles)
    private val withinRangeOfTarget = BooleanArray(numTiles)
    private val flankBonus = BooleanArray(numTiles)
    private val friendlyMilitary = arrayOfNulls<MapUnit>(numTiles)
    private val friendlyCivilian = arrayOfNulls<MapUnit>(numTiles)
    private val enemyMilitary = arrayOfNulls<MapUnit>(numTiles)
    private val enemyCivilian = arrayOfNulls<MapUnit>(numTiles)
    private val neutralMilitary = arrayOfNulls<MapUnit>(numTiles)
    private val neutralCivilian = arrayOfNulls<MapUnit>(numTiles)

    private val atWar = HashMap<Civilization, Boolean>()
    private val zonesById = ArrayList<DominanceZone>()
    /** The dominance zones, the most valuable first (CvTacticalAnalysisMap::m_DominanceZones after PrioritizeZones) */
    val zones: List<DominanceZone> get() = sortedZones
    private var sortedZones: List<DominanceZone> = emptyList()
    /** The units of players at war with us that can attack (CvTacticalAnalysisMap::BuildEnemyUnitList) */
    private val enemyUnits = ArrayList<MapUnit>()

    private val tempZones = ArrayList<DominanceZone>()
    private val state = state


    init {
        val era = gameEra()
        tacticalRange = ((rules.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE) + era) * 2) / 3
        maxUnitDistance = (rules.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE) + era) / 2
        timeThis("AI.TacticalMap") { build() }
    }

    private fun build() {
        computeAreas()
        addTemporaryZones()
        val visible = BooleanArray(numTiles)
        for (tile in civ.viewableTiles) visible[tile.zeroBasedIndex] = true
        for (tile in tileList) populateCell(tile, visible[tile.zeroBasedIndex])
        createDominanceZones()
        establishZoneNeighborhood()
        computeZoneCenters()
        calculateMilitaryStrengths()
        prioritizeZones()
        buildEnemyUnitList()
    }

    /**
     *  CvTacticalDominanceZone::GetCenterX / GetCenterY: the average position of the zone's tiles, rounded; the tile there (it may lie in
     *  another zone or on the other kind of terrain, as in the original). Hex coordinates are averaged, not the original's offset grid.
     */
    private fun computeZoneCenters() {
        val sums = LongArray(zonesById.size * 3)
        for (tile in tileList) {
            val zone = zoneIds[tile.zeroBasedIndex]
            if (zone < 0) continue
            sums[zone * 3] += tile.position.x.toLong()
            sums[zone * 3 + 1] += tile.position.y.toLong()
            sums[zone * 3 + 2]++
        }
        for (zone in zonesById) {
            val count = sums[zone.id * 3 + 2]
            if (count == 0L) continue
            val x = Math.floorDiv(sums[zone.id * 3] * 2 + count, count * 2).toInt()
            val y = Math.floorDiv(sums[zone.id * 3 + 1] * 2 + count, count * 2).toInt()
            zone.centerTile = civ.gameInfo.tileMap.getOrNull(x, y)
        }
    }

    //region Cell access

    private fun has(tile: Tile, flag: Int) = flags[tile.zeroBasedIndex] and flag != 0

    fun isRevealed(tile: Tile) = has(tile, REVEALED)
    fun isVisible(tile: Tile) = has(tile, VISIBLE)
    fun isImpassableTerrain(tile: Tile) = has(tile, IMPASSABLE_TERRAIN)
    /** Owned by someone we are not at war with and who does not allow us in */
    fun isImpassableTerritory(tile: Tile) = has(tile, IMPASSABLE_TERRITORY)
    fun isWater(tile: Tile) = has(tile, WATER)
    fun isOcean(tile: Tile) = has(tile, OCEAN)
    fun isFriendlyCity(tile: Tile) = has(tile, FRIENDLY_CITY)
    fun isEnemyCity(tile: Tile) = has(tile, ENEMY_CITY)
    fun isNeutralCity(tile: Tile) = has(tile, NEUTRAL_CITY)
    fun isOwnTerritory(tile: Tile) = has(tile, OWN_TERRITORY)
    fun isFriendlyTerritory(tile: Tile) = has(tile, FRIENDLY_TERRITORY)
    fun isEnemyTerritory(tile: Tile) = has(tile, ENEMY_TERRITORY)
    fun isUnclaimedTerritory(tile: Tile) = has(tile, UNCLAIMED_TERRITORY)

    /** CvPlot::defenseModifier(NO_TEAM, true): the terrain's defense bonus in percent */
    fun defenseModifier(tile: Tile) = defenseModifiers[tile.zeroBasedIndex]

    fun friendlyMilitaryUnit(tile: Tile): MapUnit? = friendlyMilitary[tile.zeroBasedIndex]
    fun friendlyCivilianUnit(tile: Tile): MapUnit? = friendlyCivilian[tile.zeroBasedIndex]
    fun enemyMilitaryUnit(tile: Tile): MapUnit? = enemyMilitary[tile.zeroBasedIndex]
    fun enemyCivilianUnit(tile: Tile): MapUnit? = enemyCivilian[tile.zeroBasedIndex]
    fun neutralMilitaryUnit(tile: Tile): MapUnit? = neutralMilitary[tile.zeroBasedIndex]
    fun neutralCivilianUnit(tile: Tile): MapUnit? = neutralCivilian[tile.zeroBasedIndex]

    /** CvTacticalAnalysisCell::CanUseForOperationGathering */
    fun canUseForOperationGathering(tile: Tile): Boolean = !(isImpassableTerrain(tile) || isImpassableTerritory(tile)
        || enemyMilitaryUnit(tile) != null || neutralMilitaryUnit(tile) != null || neutralCivilianUnit(tile) != null
        || isFriendlyTurnEndTile(tile) || isEnemyCity(tile) || isNeutralCity(tile))

    /** CvTacticalAnalysisCell::CanUseForOperationGatheringCheckWater: as [canUseForOperationGathering], and the tile is water exactly if [water] */
    fun canUseForOperationGatheringCheckWater(tile: Tile, water: Boolean): Boolean = water == isWater(tile) && canUseForOperationGathering(tile)

    /** The landmass or body of water of the tile (CvPlot::getArea): land tiles by continent, water tiles by connected body of water */
    fun areaOf(tile: Tile) = areas[tile.zeroBasedIndex]

    /** The zone a tile belongs to, null when it is unrevealed. */
    fun zoneOf(tile: Tile): DominanceZone? = zoneIds[tile.zeroBasedIndex].let { if (it < 0) null else zonesById[it] }

    /** CvTacticalAnalysisMap::IsInEnemyDominatedZone */
    fun isInEnemyDominatedZone(tile: Tile) = zoneOf(tile)?.let { it.closestCity != null && it.dominance == TacticalDominance.Enemy } == true

    /** CvTacticalAnalysisMap::GetZoneByCity: the zone [city] is the closest city of, on land or on water */
    fun zoneByCity(city: City, isWater: Boolean): DominanceZone? = sortedZones.firstOrNull { it.closestCity == city && it.isWater == isWater }

    /** CvTacticalAnalysisCell::IsSubjectToAttack: an enemy unit on the same landmass or sea can reach the tile in a turn, or a hostile citadel is next to it. Never in our cities. */
    fun isSubjectToAttack(tile: Tile): Boolean = reachOf(tile) and SUBJECT_TO_ATTACK != 0

    /** CvTacticalAnalysisCell::IsEnemyCanMovePast: an enemy unit can reach the tile and still have movement left (or is on it) */
    fun enemyCanMovePast(tile: Tile): Boolean = reachOf(tile) and CAN_MOVE_PAST != 0

    //endregion
    //region Dynamic cell data of the attack routines

    /** CvTacticalAnalysisCell::IsFriendlyTurnEndTile: one of our units finished its moves here this turn (set by [markFriendlyTurnEnd]) */
    fun isFriendlyTurnEndTile(tile: Tile) = turnEndTiles[tile.zeroBasedIndex]

    /** CvTacticalAI::UnitProcessed's marking of the cell, so that later routines do not plan onto a tile a unit has settled on */
    fun markFriendlyTurnEnd(tile: Tile) {
        turnEndTiles[tile.zeroBasedIndex] = true
    }

    /** CvTacticalAnalysisCell::IsWithinRangeOfTarget, set by [setTargetBombardCells] */
    fun isWithinRangeOfTarget(tile: Tile) = withinRangeOfTarget[tile.zeroBasedIndex]

    /** CvTacticalAnalysisCell::IsHelpsProvidesFlankBonus, set by [setTargetFlankBonusCells] */
    fun helpsProvideFlankBonus(tile: Tile) = flankBonus[tile.zeroBasedIndex]

    /** CvTacticalAnalysisMap::ClearDynamicFlags (the safe-for-deployment and deployment-score flags belong to the deployment code) */
    fun clearDynamicFlags() {
        withinRangeOfTarget.fill(false)
        flankBonus.fill(false)
    }

    /**
     *  CvTacticalAnalysisMap::SetTargetBombardCells: the revealed, passable tiles within [range] of [target], not enemy or neutral cities,
     *  with a line of sight to it (unless [ignoreLineOfSight]) from where a ranged unit could shoot at it.
     */
    fun setTargetBombardCells(target: Tile, range: Int, ignoreLineOfSight: Boolean) {
        for (tile in target.getTilesInDistance(range)) {
            if (tile == target || !isRevealed(tile) || isImpassableTerrain(tile) || isImpassableTerritory(tile)) continue
            if (isEnemyCity(tile) || isNeutralCity(tile)) continue
            if (ignoreLineOfSight || target in tile.tileMap.getViewableTiles(tile.position, range, true))
                withinRangeOfTarget[tile.zeroBasedIndex] = true
        }
    }

    /** CvTacticalAnalysisMap::SetTargetFlankBonusCells: the free tiles around a land [target] where a unit of ours would add a flank bonus */
    fun setTargetFlankBonusCells(target: Tile) {
        if (target.isWater) return
        for (tile in target.neighbors) {
            if (!isRevealed(tile) || isImpassableTerrain(tile) || isImpassableTerritory(tile)) continue
            if (isFriendlyCity(tile) || isEnemyCity(tile) || isNeutralCity(tile)) continue
            if (!isFriendlyTurnEndTile(tile) && enemyMilitaryUnit(tile) == null) flankBonus[tile.zeroBasedIndex] = true
        }
    }

    //endregion

    private fun gameEra(): Int {
        var sum = 0
        var count = 0
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || !other.isAlive()) continue
            sum += other.getAiEraNumber()
            count++
        }
        return if (count == 0) 0 else sum / count
    }

    private fun isAtWarWith(other: Civilization) = atWar.getOrPut(other) { civ.isAtWarWith(other) }

    /** Land tiles by [Tile.getContinent], water tiles by their connected body of water (numbered above all continents) */
    private fun computeAreas() {
        var nextWaterArea = Int.MAX_VALUE / 2
        val queue = ArrayDeque<Tile>()
        for (tile in tileList) {
            if (!tile.isWater) {
                areas[tile.zeroBasedIndex] = tile.getContinent()
                continue
            }
            if (areas[tile.zeroBasedIndex] != 0) continue
            val area = nextWaterArea++
            areas[tile.zeroBasedIndex] = area
            queue.add(tile)
            while (queue.isNotEmpty()) {
                val current = queue.removeLast()
                for (neighbor in current.neighbors) {
                    if (!neighbor.isWater || areas[neighbor.zeroBasedIndex] != 0) continue
                    areas[neighbor.zeroBasedIndex] = area
                    queue.add(neighbor)
                }
            }
        }
    }

    /** CvTacticalAnalysisMap::AddTemporaryZones: temporary zones come first, so they take the unclaimed tiles near their center */
    private fun addTemporaryZones() {
        for (temporary in state.temporaryZones) {
            if (temporary.targetType == TacticalTargetType.City) continue // only boosts the priority of the city's zone
            val tile = civ.gameInfo.tileMap[temporary.position]
            val zone = DominanceZone(zonesById.size, TacticalTerritory.TempZone, null, areaOf(tile), tile.isWater, null, tile, temporary.navalInvasion)
            zonesById.add(zone)
            tempZones.add(zone)
        }
    }

    /** Populate the per-tile flags independently of the tactical zone assignment. */
    private fun populateCell(tile: Tile, visible: Boolean) {
        val index = tile.zeroBasedIndex
        var flag = 0
        val revealed = tile.isExplored(civ)
        if (revealed) flag = flag or REVEALED
        if (visible) flag = flag or VISIBLE
        val impassableTerrain = tile.isImpassible() || tile.baseTerrain == Constants.mountain
        if (impassableTerrain) flag = flag or IMPASSABLE_TERRAIN
        if (tile.isWater) flag = flag or WATER
        if (tile.isWater && tile.isOcean) flag = flag or OCEAN

        var impassableTerritory = false
        val owner = tile.getOwner()
        if (owner == null) flag = flag or UNCLAIMED_TERRITORY
        else {
            if (owner != civ && !owner.diplomacyFunctions.canPassThroughTiles(civ)) impassableTerritory = true
            else if (tile.isCityCenter()) flag = flag or when {
                owner == civ -> FRIENDLY_CITY
                isAtWarWith(owner) -> ENEMY_CITY
                else -> NEUTRAL_CITY
            }
            if (owner == civ) flag = flag or OWN_TERRITORY
            if (tile.isFriendlyTerritory(civ)) flag = flag or FRIENDLY_TERRITORY
            if (owner != civ && isAtWarWith(owner)) flag = flag or ENEMY_TERRITORY
        }
        if (impassableTerritory) flag = flag or IMPASSABLE_TERRITORY
        flags[index] = flag
        defenseModifiers[index] = (tile.getDefensiveBonus(includeImprovementBonus = true) * 100).toInt()

        if (visible) {
            tile.militaryUnit?.let { place(it, friendlyMilitary, enemyMilitary, neutralMilitary, index) }
            tile.civilianUnit?.let { place(it, friendlyCivilian, enemyCivilian, neutralCivilian, index) }
        }
    }

    private fun place(unit: MapUnit, friendly: Array<MapUnit?>, enemy: Array<MapUnit?>, neutral: Array<MapUnit?>, index: Int) {
        when {
            unit.civ == civ -> friendly[index] = unit
            isAtWarWith(unit.civ) -> enemy[index] = unit
            else -> neutral[index] = unit
        }
    }

    /**
     * CvTacticalAnalysisMap::CreateDominanceZones: each revealed plot within five tiles of its nearest city belongs to that city's
     * land or water zone. Remaining revealed plots form connected non-city zones; small areas can join an adjacent area.
     * Legacy temporary zones retain priority over unowned plots until operation focus areas replace them.
     */
    private fun createDominanceZones() {
        val nearest = arrayOfNulls<City>(numTiles)
        val distances = IntArray(numTiles) { Int.MAX_VALUE }
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || !other.isAlive()) continue
            for (city in other.cities) {
                val center = city.getCenterTile()
                for (tile in center.getTilesInDistance(5)) {
                    val index = tile.zeroBasedIndex
                    val distance = center.aerialDistanceTo(tile)
                    if (distance < distances[index]) {
                        distances[index] = distance
                        nearest[index] = city
                    }
                }
            }
        }

        val landCityZones = HashMap<String, DominanceZone>()
        val waterCityZones = HashMap<String, DominanceZone>()
        val areaSizes = HashMap<Int, Int>()
        for (tile in tileList) areaSizes.merge(areas[tile.zeroBasedIndex], 1, Int::plus)
        for (tile in tileList) {
            if (!tile.isExplored(civ)) continue
            val index = tile.zeroBasedIndex
            if (tile.getOwner() == null) {
                val temporary = tempZones.firstOrNull { tile.aerialDistanceTo(it.tempZoneCenter!!) <= tacticalRange }
                if (temporary != null) {
                    zoneIds[index] = temporary.id
                    continue
                }
            }
            val city = nearest[index] ?: continue
            val owner = city.civ
            val territory = when {
                owner == civ -> TacticalTerritory.Friendly
                isAtWarWith(owner) -> TacticalTerritory.Enemy
                else -> TacticalTerritory.Neutral
            }
            val cityZones = if (tile.isWater) waterCityZones else landCityZones
            val zone = cityZones.getOrPut(city.id) {
                DominanceZone(zonesById.size, territory, owner, areas[index], tile.isWater, city).also { zonesById.add(it) }
            }
            zoneIds[index] = zone.id
        }

        val pending = ArrayDeque<Tile>()
        for (tile in tileList) {
            val index = tile.zeroBasedIndex
            if (!tile.isExplored(civ) || zoneIds[index] >= 0) continue
            val zone = DominanceZone(zonesById.size, TacticalTerritory.NoOwner, null, areas[index], tile.isWater, null)
            zonesById.add(zone)
            zoneIds[index] = zone.id
            pending.add(tile)
            while (pending.isNotEmpty()) {
                val current = pending.removeLast()
                val currentArea = areas[current.zeroBasedIndex]
                for (neighbor in current.neighbors) {
                    val next = neighbor.zeroBasedIndex
                    if (!neighbor.isExplored(civ) || zoneIds[next] >= 0) continue
                    val nextArea = areas[next]
                    if (currentArea != nextArea && areaSizes.getValue(currentArea) >= 4 && areaSizes.getValue(nextArea) >= 4) continue
                    zoneIds[next] = zone.id
                    pending.add(neighbor)
                }
            }
        }
    }

    /** EstablishZoneNeighborhood: adjacent revealed zones are neighbors in both directions. */
    private fun establishZoneNeighborhood() {
        for (tile in tileList) {
            val zoneId = zoneIds[tile.zeroBasedIndex]
            if (zoneId < 0) continue
            val zone = zonesById[zoneId]
            for (neighbor in tile.neighbors) {
                val neighborId = zoneIds[neighbor.zeroBasedIndex]
                if (neighborId < 0 || neighborId == zoneId) continue
                val adjacent = zonesById[neighborId]
                if (adjacent !in zone.neighbors) zone.neighbors.add(adjacent)
            }
        }
    }

    private fun noteFriendlyRange(unit: MapUnit) {
        if (unit.getRange() > bestFriendlyRange) bestFriendlyRange = unit.getRange()
        if (unit.hasUnique(UniqueType.IndirectFire)) ignoreLineOfSight = true
    }

    private enum class Side { Friendly, Enemy, Neutral }

    /** What CalculateMilitaryStrengths reads of one military unit, worked out once for all the zones the unit may count in */
    private class StrengthUnit(
        val unit: MapUnit,
        val side: Side,
        val tile: Tile,
        /** The zone the unit stands in, -1 for none */
        val zoneId: Int,
        /** CvUnit::GetMaxAttackStrength (x100): its own strength, which a ranged unit has too */
        val meleeStrength: Int,
        /** CvUnit::GetMaxRangedCombatStrength (x100) */
        val rangedStrength: Int,
        val canAttackRanged: Boolean,
        /** Embarked, or standing where we cannot see: counts for half (before the zone's domain says so as well) */
        val reducedStrength: Boolean,
        /** The modifier of the unit alone: +50 for speed or range over 2, +100 in a citadel of its owner */
        val unitModifier: Int,
    ) {
        val isLand = unit.baseUnit.isLandUnit
        val isSea = unit.baseUnit.isWaterUnit
        val isAir = unit.baseUnit.isAirUnit()
    }

    /** The plots we see, and the plots next to those that are not ours: a small cheat the original lets a human make by educated guess */
    private fun plotsSeenForStrengths(): BooleanArray {
        val seen = BooleanArray(numTiles)
        for (tile in civ.viewableTiles) {
            seen[tile.zeroBasedIndex] = true
            if (tile.getOwner() != civ) for (neighbor in tile.neighbors) seen[neighbor.zeroBasedIndex] = true
        }
        return seen
    }

    /** Whose side the units of [other] are on in the strength sums: ours, at war with us, or neither */
    private fun sideOf(other: Civilization, ourWars: Set<Civilization>): Side = when {
        isAtWarWith(other) -> Side.Enemy
        other == civ -> Side.Friendly
        civ.isCityState && other == civ.allyCiv -> Side.Friendly
        // a major civilization counts as friendly those it fights exactly the same enemies with
        civ.isMajorCiv && warsOf(other) == ourWars -> Side.Friendly
        else -> Side.Neutral
    }

    /** CvPlayer::GetPlayersAtWarWith: the living civilizations (not the barbarians) a player is at war with */
    private fun warsOf(player: Civilization): Set<Civilization> =
        player.getCivsAtWarWith().filterTo(HashSet()) { !it.isBarbarian && it.isAlive() }

    /** TacticalAIHelpers::IsPlayerCitadel: an improvement of the unit's owner, not pillaged, that hurts adjacent enemies for more than a unit heals and defends at 50% or more */
    internal fun isPlayerCitadel(tile: Tile, owner: Civilization): Boolean {
        if (tile.getOwner() != owner) return false
        val improvement = tile.getUnpillagedTileImprovement() ?: return false
        return PlotDefense.nearbyEnemyDamage(improvement) > ENEMY_HEAL_RATE && PlotDefense.defenseModifier(improvement) >= CITADEL_MIN_DEFENSE
    }

    private fun strengthUnit(unit: MapUnit, side: Side, seen: BooleanArray): StrengthUnit {
        val tile = unit.getTile()
        val base = unit.baseUnit
        val moves = if (unit.isEmbarked()) base.movement else unit.getMaxMovement(true)
        var modifier = 0
        if (maxOf(moves, unit.getRange()) > 2) modifier += RANGE_BONUS
        if (isPlayerCitadel(tile, unit.civ)) modifier += CITADEL_BONUS
        return StrengthUnit(
            unit, side, tile, zoneIds[tile.zeroBasedIndex],
            PlotDanger.valueConsideringDamage(base.strength * 100, unit, rules),
            PlotDanger.valueConsideringDamage(base.rangedStrength * 100, unit, rules),
            base.isRanged() && unit.getRange() > 0,
            unit.isEmbarked() || !seen[tile.zeroBasedIndex],
            modifier
        )
    }

    /**
     *  CvTacticalAnalysisMap::CalculateMilitaryStrengths: the zone's city adds its strength, less the damage it has taken, to the ranged
     *  strength of its side (neutral cities to the neutral one), and every military unit of the world that stands in the zone or within
     *  [maxUnitDistance] of its city adds its strength to that of its side (see [addUnit]). The barbarians' units are ignored: the original
     *  does not base tactical decisions on transients (the barbarians' own AI does not read the map).
     */
    private fun calculateMilitaryStrengths() {
        val seen = plotsSeenForStrengths()
        val ourWars = warsOf(civ)
        val units = ArrayList<StrengthUnit>()
        val unitsByZone = HashMap<Int, ArrayList<StrengthUnit>>()
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || !other.isAlive()) continue
            val side = sideOf(other, ourWars)
            for (unit in other.units.getCivUnits()) {
                if (!unit.isMilitary() || unit.isDestroyed) continue
                val entry = strengthUnit(unit, side, seen)
                units.add(entry)
                unitsByZone.getOrPut(entry.zoneId) { ArrayList() }.add(entry)
            }
        }

        for (zone in zonesById) {
            val city = zone.closestCity
            if (city == null) {
                // no city to measure the distance to: only the units standing in the zone count, at distance zero
                unitsByZone[zone.id]?.forEach { addUnit(zone, it, 0) }
                continue
            }
            val cityTile = city.getCenterTile()
            val cityStrength = CityCombatant(city).getCityStrength() * 100 * city.health / city.getMaxHealth()
            when (zone.territory) {
                TacticalTerritory.Friendly -> zone.friendlyRangedStrength += cityStrength
                TacticalTerritory.Enemy -> zone.enemyRangedStrength += cityStrength
                else -> zone.neutralStrength += cityStrength
            }
            for (entry in units) {
                val distance = entry.tile.aerialDistanceTo(cityTile)
                // units in adjacent zones can count, units too far away and outside the zone cannot
                if (distance > maxUnitDistance && entry.zoneId != zone.id) continue
                addUnit(zone, entry, distance)
            }
        }
    }

    /**
     *  One unit's share of [zone], [distance] from its city. Air units always belong to the zone's domain, land units to land zones and
     *  ships to water zones; of the others only those with a ranged attack count. Its strength, own and ranged, changes by -50% when it
     *  is embarked, in a place we do not see or of the wrong domain, +50% when it has more than 2 moves or range, +50% within
     *  [CENTER_RADIUS] of the city and +100% in a citadel. Air units add ranged strength only, to the naval bucket over water, and are
     *  not counted; the other units add their own strength to the melee (land) or naval bucket and their ranged strength to the ranged
     *  one of their domain, and count. Units of neither side add their own strength to the neutral sum and count.
     */
    private fun addUnit(zone: DominanceZone, entry: StrengthUnit, distance: Int) {
        val domainMatches = entry.isAir || (entry.isLand && !zone.isWater) || (entry.isSea && zone.isWater)
        if (!domainMatches && !entry.canAttackRanged) return

        var modifier = entry.unitModifier
        if (entry.reducedStrength || !domainMatches) modifier -= REDUCED_PENALTY
        if (distance <= CENTER_RADIUS) modifier += CENTER_BONUS
        val melee = entry.meleeStrength + entry.meleeStrength * modifier / 100
        val ranged = entry.rangedStrength + entry.rangedStrength * modifier / 100
        val overWater = entry.tile.isWater
        val isRangedUnit = entry.unit.baseUnit.isRanged()

        when (entry.side) {
            Side.Enemy -> when {
                entry.isSea -> {
                    zone.enemyNavalStrength += melee
                    zone.enemyNavalRangedStrength += ranged
                    zone.enemyNavalUnitCount++
                }
                entry.isLand -> {
                    zone.enemyMeleeStrength += melee
                    zone.enemyRangedStrength += ranged
                    zone.enemyLandUnitCount++
                }
                entry.isAir -> if (overWater) zone.enemyNavalRangedStrength += ranged else zone.enemyRangedStrength += ranged
            }
            Side.Friendly -> {
                when {
                    entry.isSea -> {
                        zone.friendlyNavalStrength += melee
                        zone.friendlyNavalRangedStrength += ranged
                        zone.friendlyNavalUnitCount++
                    }
                    entry.isLand -> {
                        zone.friendlyMeleeStrength += melee
                        zone.friendlyRangedStrength += ranged
                        zone.friendlyLandUnitCount++
                    }
                    entry.isAir -> if (overWater) zone.friendlyNavalRangedStrength += ranged else zone.friendlyRangedStrength += ranged
                }
                if (!entry.isAir && isRangedUnit) zone.friendlyRangedUnitCount++
                if (domainMatches) noteFriendlyRange(entry.unit)
            }
            Side.Neutral -> {
                zone.neutralStrength += melee
                zone.neutralUnitCount++
            }
        }
    }

    /** CvTacticalAnalysisMap::PrioritizeZones: dominance and value of every zone, then the zones sorted by value (stable) */
    private fun prioritizeZones() {
        val stateAllWars = if (civ.isCityState) StateAllWars.Neutral else WarStateAi.stateAllWars(civ)
        for (zone in zonesById) {
            val dominance = zone.setOverallDominance(dominancePercentage)
            var baseValue = 1
            val multiplier: Int
            if (zone.territory == TacticalTerritory.TempZone) multiplier = 1000
            else {
                val city = zone.closestCity
                if (city != null) {
                    baseValue += 1 + sqrt(city.population.population.toFloat()).toInt()
                    if (city.isCapital() && !city.civ.isCityState) baseValue *= 2
                    if (state.isTemporaryZoneCity(city)) baseValue *= 20
                    else if (isCityVisible(city)) {
                        baseValue *= 4
                        // How damaged is this visible city?
                        val maxHitPoints = city.getMaxHealth()
                        val damage = maxHitPoints - city.health
                        if (damage > maxHitPoints / MAX_DAMAGE_MULTIPLIER) baseValue *= (damage + 1) * 10 / maxHitPoints
                    }
                }
                if (!zone.isWater) baseValue *= 3

                var factor = 1
                when (dominance) {
                    TacticalDominance.Enemy -> factor = when (zone.territory) {
                        TacticalTerritory.Enemy -> 2
                        TacticalTerritory.Friendly -> 6
                        else -> 1
                    }
                    TacticalDominance.Even -> if (zone.territory == TacticalTerritory.Enemy || zone.territory == TacticalTerritory.Friendly) factor = 4
                    TacticalDominance.Friendly -> factor = if (zone.territory == TacticalTerritory.Enemy) 8 else 1
                    TacticalDominance.NoUnitsVisible -> {}
                }
                if (!civ.isCityState) {
                    if (stateAllWars == StateAllWars.Winning) {
                        if (zone.territory == TacticalTerritory.Enemy) factor *= 2
                    } else if (stateAllWars == StateAllWars.Losing) {
                        if (zone.territory == TacticalTerritory.Friendly) factor *= 4
                    }
                }
                multiplier = factor
            }
            zone.value = baseValue * multiplier
        }
        sortedZones = zonesById.sortedByDescending { it.value }
    }

    private fun isCityVisible(city: City): Boolean = city.civ == civ || city.getCenterTile() in civ.viewableTiles

    /** CvTacticalAnalysisMap::BuildEnemyUnitList: the units of players at war with us that can attack */
    private fun buildEnemyUnitList() {
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !isAtWarWith(other)) continue
            for (unit in other.units.getCivUnits()) if (unit.isMilitary()) enemyUnits.add(unit)
        }
    }

    /** CvTacticalAnalysisMap::MarkCellsNearEnemy for one tile, on first use */
    private fun reachOf(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        var value = reach[index].toInt()
        if (value == 0) {
            value = 1 or computeReach(tile)
            reach[index] = value.toByte()
        }
        return value
    }

    private fun computeReach(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        if (!has(tile, REVEALED) || has(tile, IMPASSABLE_TERRAIN) || has(tile, IMPASSABLE_TERRITORY) || has(tile, FRIENDLY_CITY)) return 0
        var result = 0
        val area = areas[index]
        for (enemy in enemyUnits) {
            val enemyTile = enemy.getTile()
            if (areas[enemyTile.zeroBasedIndex] != area) continue
            val distance = enemyTile.aerialDistanceTo(tile)
            if (distance == 0) return SUBJECT_TO_ATTACK or CAN_MOVE_PAST
            val baseMoves = enemy.baseUnit.movement
            if (distance > baseMoves) continue
            result = result or SUBJECT_TO_ATTACK
            if (distance < baseMoves) return SUBJECT_TO_ATTACK or CAN_MOVE_PAST
        }
        if (result == 0) for (neighbor in tile.neighbors) {
            val owner = neighbor.getOwner() ?: continue
            if (!isAtWarWith(owner)) continue
            if (neighbor.getUnpillagedTileImprovement()?.hasUnique(UniqueType.DamagesAdjacentEnemyUnits) == true) return SUBJECT_TO_ATTACK
        }
        return result
    }

    private companion object {
        const val REVEALED = 1
        const val VISIBLE = 2
        const val IMPASSABLE_TERRAIN = 4
        const val IMPASSABLE_TERRITORY = 8
        const val WATER = 16
        const val OCEAN = 32
        const val FRIENDLY_CITY = 64
        const val ENEMY_CITY = 128
        const val NEUTRAL_CITY = 256
        const val OWN_TERRITORY = 512
        const val FRIENDLY_TERRITORY = 1024
        const val ENEMY_TERRITORY = 2048
        const val UNCLAIMED_TERRITORY = 4096

        /** Bits of [reach]: 1 = computed */
        const val SUBJECT_TO_ATTACK = 2
        const val CAN_MOVE_PAST = 4

        /** The 10 of PrioritizeZones' iMaxDamageMultiplier: a city is only "damaged" above 1/10 of its hit points */
        const val MAX_DAMAGE_MULTIPLIER = 10

        /** CalculateMilitaryStrengths' iCenterRadius: units this close to the city of a zone are more important */
        const val CENTER_RADIUS = 3
        /** CalculateMilitaryStrengths' modifiers in percent: -50 embarked, unseen or of another domain, +50 for more than 2 moves or range, +50 within the center radius, +100 in a citadel */
        const val REDUCED_PENALTY = 50
        const val RANGE_BONUS = 50
        const val CENTER_BONUS = 50
        const val CITADEL_BONUS = 100
        /** ENEMY_HEAL_RATE of the Brave New World and Community Patch data (5 in Vox Populi): what a unit heals in enemy territory, below which an improvement's damage is no citadel's */
        const val ENEMY_HEAL_RATE = 10
        /** TacticalAIHelpers::IsPlayerCitadel: a citadel defends at 50% or more */
        const val CITADEL_MIN_DEFENSE = 50
    }
}
