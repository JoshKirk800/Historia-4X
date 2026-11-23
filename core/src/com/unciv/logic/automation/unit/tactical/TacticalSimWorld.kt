package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.PlotDefense
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.UniqueType
import java.util.BitSet
import java.util.PriorityQueue

/** A unit of ours the simulation moves (CvTacticalPosition::addAvailableUnit): [strategy] is where in the formation it wants to be */
class SimUnit(val unit: MapUnit, val strategy: MoveStrategy) {
    val domain: SimDomain = if (unit.baseUnit.isLandUnit) SimDomain.Land else if (unit.baseUnit.isWaterUnit) SimDomain.Sea else SimDomain.Both
    val ranged = unit.baseUnit.isRanged()
    val maxMoves = unit.getMaxMovement().toFloat()
    val canMoveAfterAttacking = unit.hasUnique(UniqueType.CanMoveAfterAttacking)
    val maxAttacks = unit.maxAttacksPerTurn()
    val startMoves = unit.currentMovement
    val startAttacks = (unit.maxAttacksPerTurn() - unit.attacksThisTurn).coerceAtLeast(0)
}

/** An enemy unit on a plot of the simulation; [plot] is the index in [SimWorld.plots] */
class SimEnemy(val unit: MapUnit, val plot: Int) {
    val domain: SimDomain = if (unit.baseUnit.isLandUnit) SimDomain.Land else if (unit.baseUnit.isWaterUnit) SimDomain.Sea else SimDomain.Both
}

/** An enemy city on a plot of the simulation; its garrison is no separate enemy, an attack hits the city alone */
class SimCity(val city: City, val plot: Int) {
    val health = city.health
    val maxHealth = city.getMaxHealth()
}

/** An enemy unit that can hurt our units on a plot (CvDangerPlotContents): [index] is the index among [SimWorld.enemies], -1 if it is outside the plots */
class DangerSource(val unit: MapUnit, val index: Int)

/** The damage already dealt in a plan, per enemy unit and enemy city (CvTacticalPosition::unitDamageDealt), which danger and attacks take into account */
class DamageOverlay(val enemy: IntArray, val city: IntArray, val garrison: IntArray = IntArray(city.size)) {
    val hash get() = (enemy.contentHashCode() * 31 + city.contentHashCode()) * 31 + garrison.contentHashCode()
}

/** Enemy distances of all plots for one set of living enemies (CvTacticalPlot's distance transform), indexed by [SimDomain.ordinal] */
class EnemyField(plotCount: Int) {
    val distance = Array(3) { IntArray(plotCount) { MAX_DISTANCE } }
    val adjacent = Array(3) { IntArray(plotCount) }
    val rangedDistance = Array(3) { IntArray(plotCount) { MAX_DISTANCE } }
}

/** The plots a unit can reach and the movement it has left there; [plots] are indices in [SimWorld.plots], tiles outside the plots are in [outside] */
class Reach(val plots: IntArray, val moves: FloatArray, val outside: List<Tile>, val outsideMoves: FloatArray)

/**
 *  A unit as the simulation sees it: standing on [simTile] with [simHealth] hit points, whatever the real unit is (the hypothetical attacker
 *  and defender of CvUnit::GetMaxAttackStrength's plot parameters and of the damage the plan has done so far).
 */
class SimCombatant(unit: MapUnit, private val simTile: Tile, private val simHealth: Int) : MapUnitCombatant(unit) {
    override fun getHealth(): Int = simHealth
    override fun getTile(): Tile = simTile
    override fun isDefeated(): Boolean = simHealth <= 0
}

const val MAX_DISTANCE = 4

/** CvTacticalAI's TACTICAL_COMBAT_CITADEL_BONUS: larger than 60 to override the difference between first and second line */
const val CITADEL_BONUS = 67

/**
 *  The situation a tactical simulation starts from and everything about it that does not change while plans are made
 *  (CvTacticalPosition::initFromScratch and the setup of CvTacticalAI's FindBestUnitAssignments): the plots around the [target] (up to
 *  [MAX_DISTANCE] tiles away, plus the tiles around the enemies in them and around our units), the enemy units and cities on them, the units
 *  of ours that may be moved, their danger, the damage they do, where they can go, and the caches those need.
 *
 *  Sources: CvTacticalAI.cpp FindBestUnitAssignments, CvTacticalPlot::CvTacticalPlot, CvTacticalPosition::addAvailableUnit,
 *  updateMoveAndAttackPlotsForUnit, countEnemiesAndCheckVisibility, refreshVolatilePlotProperties, CvTacticalPlot::checkEdgePlotsForSurprises,
 *  TacticalAIHelpers::GetSimulatedDamageFromAttackOnUnit / OnCity, CvDangerPlotContents::GetDanger(unit), UpdatePlotDistanceToTarget.
 *
 *  Deviations from the original:
 *  - the damage is [BattleDamage] at the mean of the randomness; a hypothetical position of a unit is a [SimCombatant], flanking is left out
 *    of the damage (the original's bIgnoreUnitAdjacencyBoni) and added by the simulation as 10% per unit;
 *  - with cityCombatLikeVp, city attacks also track hypothetical garrison damage and remove its strength after it dies;
 *  - danger is the expected damage of the visible enemy units that can get within range (their distance within their movement and range, on
 *    their own domain unless they shoot) and of enemy cities within bombard range, without the original's path finder and fog-of-war counts;
 *  - the tiles a unit can reach are found by [reach], which follows Unciv's movement costs with zones of control of the enemies still alive in the plan;
 *  - plots are not added as units reveal them: a move that reveals an enemy ends the plan with a restart ([AssignmentType.Restart]);
 *  - what is not known: air cover, the plots enemies can see, citadel sites (no General in the plans), plague and yield bonuses,
 *    experience on kill, splash damage (Unciv has none), the extra ranged attack of the Zulu.
 */
class SimWorld(
    val ctx: TacticalContext,
    val target: Tile,
    unitList: List<MapUnit>,
    val aggression: AggressionLevel,
    val targetDistanceRelevant: Boolean,
    val returnToStart: Boolean,
    val saveMovement: Float,
) {
    val civ: Civilization = ctx.civ
    private val tileMap = civ.gameInfo.tileMap
    private val numTiles = tileMap.tileList.size
    private val atWar = HashMap<Civilization, Boolean>()

    //region Plots
    val plots = ArrayList<Tile>()
    private val plotByTile = IntArray(numTiles) { -1 }
    /** Neighbors of each plot among the plots (CvTacticalPosition::getTactPlot of the neighbors) */
    lateinit var neighbors: Array<IntArray>
        private set
    /** Bit 1 land, 2 sea: a non-simulated unit of any civilization blocks the plot for combat units of that domain; bit 4: it is a unit of ours (isBlockedByNonSimUnit) */
    private lateinit var blocked: IntArray
    /** A non-simulated unit of ours that has finished its turn stands here (bFriendlyDefenderEndTurn) */
    private lateinit var friendlyEndTurn: BooleanArray
    private lateinit var civilianAt: BooleanArray
    private lateinit var campAt: BooleanArray
    private lateinit var edge: BooleanArray
    private lateinit var improvementDamage: IntArray
    //endregion

    val units = ArrayList<SimUnit>()
    val enemies = ArrayList<SimEnemy>()
    val cities = ArrayList<SimCity>()
    private lateinit var enemyAt: IntArray
    private lateinit var cityAt: IntArray
    /** Enemies just outside the plots that count for the distance to the enemy (checkEdgePlotsForSurprises) */
    private val outsideEnemies = ArrayList<Pair<Tile, SimDomain>>()
    private val dangerUnits = ArrayList<DangerSource>()
    private val dangerCities = ArrayList<City>()
    private val dangerCityIndex = HashMap<City, Int>()

    //region Parameters
    val rules = ctx.rules
    val maxBranches: Int
    val maxChoicesPerUnit: Int
    val maxCompletedPositions: Int
    val defaultUnitLossThreshold: Int
    val minHpForTactsim: Int
    var medianUnitXp = 0
        private set
    /** Enemy units and cities in the plots, not updated after kills (nOriginalEnemies) */
    var originalEnemies = 0
        private set
    /** Our units in the simulation plus those of ours near the enemy that are not (nOurOriginalUnits) */
    var ourOriginalUnits = 0
        private set
    /** For each unit the number of tiles it can reach where it would be safe: with none, it must end its turn wherever it can (gSafePlotCount) */
    val safePlotCount = IntArray(unitList.size.coerceAtLeast(1))
    private val targetDistance = Array(3) { HashMap<Int, Int>() }
    //endregion

    //region Caches
    private val reachCache = HashMap<ReachKey, Reach>()
    private val attackCache = HashMap<AttackKey, IntArray>()
    private val dangerCache = HashMap<DangerKey, Int>()
    private val fieldCache = HashMap<BitSet, EnemyField>()
    private val rangeAttackCache = HashMap<Long, IntArray>()
    private val revealCache = HashMap<Long, IntArray>()
    private data class ReachKey(val unit: Int, val plot: Int, val moves: Int, val freed: BitSet)
    private data class AttackKey(val unit: Int, val plot: Int, val enemy: Int, val city: Int, val self: Int, val prevUnit: Int, val prevCity: Int)
    private data class DangerKey(val unit: Int, val tile: Int, val self: Int, val overlay: Int)
    //endregion

    init {
        val difficulty = civ.gameInfo.getDifficulty().name
        val limits = HANDICAP_LIMITS[difficulty] ?: DEFAULT_LIMITS
        maxBranches = limits[0].coerceIn(2, 9)
        maxChoicesPerUnit = limits[1].coerceIn(2, 9)
        maxCompletedPositions = limits[2].coerceIn(1, 4000)
        val offense = civ.ai.personalityAndGrandStrategy(Flavor.Offense).coerceIn(0, 10)
        defaultUnitLossThreshold = if (offense > 6 && unitList.size > 6) 1 else 0
        minHpForTactsim = 50 - 2 * offense
        buildPlots(unitList)
        buildEnemies()
        buildUnits(unitList)
        buildEdges()
        buildDangerSources()
        countEnemies()
        if (units.isNotEmpty()) buildTargetDistances()
    }

    fun isAtWar(other: Civilization): Boolean = atWar.getOrPut(other) { other != civ && civ.isAtWarWith(other) }

    //region Setup
    private fun addPlot(tile: Tile, cap: Boolean = true): Boolean {
        if (plotByTile[tile.zeroBasedIndex] >= 0) return false
        if (cap && plots.size >= MAX_TACT_PLOTS) return false
        plotByTile[tile.zeroBasedIndex] = plots.size
        plots.add(tile)
        return true
    }

    fun plotOf(tile: Tile): Int = plotByTile[tile.zeroBasedIndex]

    private fun enemyDefenderOf(tile: Tile): MapUnit? {
        if (tile.isCityCenter()) return null
        return TacticalTargets.visibleEnemyDefender(civ, tile)
    }

    private fun enemyCityOf(tile: Tile): City? {
        if (!tile.isCityCenter()) return null
        val city = tile.getCity() ?: return null
        if (!isAtWar(city.civ) || city.hasJustBeenConquered) return null
        return city
    }

    private fun buildPlots(unitList: List<MapUnit>) {
        val seen = civ.viewableTiles
        // The plots around the target; not equivalent to the union of all reachable plots: unreachable enemies count too
        val ring = tileMap.getTilesInDistance(target.position, MAX_DISTANCE).filter { it.aerialDistanceTo(target) <= MAX_DISTANCE }
            .sortedWith(compareBy<Tile> { it.aerialDistanceTo(target) }.thenBy { it.zeroBasedIndex }).toList()
        val enemyTiles = ArrayList<Tile>()
        for (tile in ring) {
            if (tile !in seen) continue
            if (addPlot(tile) && (enemyDefenderOf(tile) != null || enemyCityOf(tile) != null)) enemyTiles.add(tile)
        }
        // Space to maneuver around the enemies
        for (enemyTile in enemyTiles)
            for (tile in tileMap.getTilesInDistance(enemyTile.position, 1).sortedBy { it.zeroBasedIndex })
                if (tile in seen) addPlot(tile)
        // The immediate surroundings of every unit (its own tile in any case: a unit needs a plot to stand on)
        for (unit in unitList) addPlot(unit.getTile(), cap = false)
        for (unit in unitList)
            for (tile in tileMap.getTilesInDistance(unit.getTile().position, 1).sortedBy { it.zeroBasedIndex }) addPlot(tile)
    }

    private fun buildEnemies() {
        val count = plots.size
        enemyAt = IntArray(count) { -1 }
        cityAt = IntArray(count) { -1 }
        civilianAt = BooleanArray(count)
        campAt = BooleanArray(count)
        blocked = IntArray(count)
        friendlyEndTurn = BooleanArray(count)
        edge = BooleanArray(count)
        improvementDamage = IntArray(count)
        for (index in 0 until count) {
            val tile = plots[index]
            val city = enemyCityOf(tile)
            if (city != null) {
                cityAt[index] = cities.size
                cities.add(SimCity(city, index))
            }
            val defender = enemyDefenderOf(tile)
            if (defender != null) {
                enemyAt[index] = enemies.size
                enemies.add(SimEnemy(defender, index))
            }
            val civilian = tile.civilianUnit
            if (civilian != null && defender == null && city == null && civilian.civ != civ && isAtWar(civilian.civ)) civilianAt[index] = true
            if (!civ.isBarbarian && tile.isBarbarianEncampment() && tile.isExplored(civ)) campAt[index] = true
        }
    }

    /** CvTacticalPosition::addAvailableUnit: which line of the formation a unit stands in; null for units that are not part of the simulation */
    fun strategyOf(unit: MapUnit): MoveStrategy? {
        if (unit.isDestroyed || unit.civ != civ || !unit.hasMovement() || unit.baseUnit.isAirUnit() || unit.isGreatPerson() || !unit.isMilitary()) return null
        val base = unit.baseUnit
        val range = unit.getRange()
        return when (UnitAiTypes.defaultOf(base, rules)) {
            UnitAiType.ATTACK, UnitAiType.DEFENSE, UnitAiType.COUNTER, UnitAiType.PARADROP, UnitAiType.ATTACK_SEA, UnitAiType.RESERVE_SEA,
            UnitAiType.ESCORT_SEA, UnitAiType.FAST_ATTACK, UnitAiType.RANGED, UnitAiType.CITY_BOMBARD, UnitAiType.ASSAULT_SEA -> when {
                range > 2 || unit.canIntercept() -> MoveStrategy.ThirdLine
                range == 2 -> MoveStrategy.SecondLine
                // The unit AI type is unreliable, so this is done by hand: very fast ships stay far back, skirmishers are second line
                base.isRanged() && base.isWaterUnit && unit.getMaxMovement() > 3 && unit.hasUnique(UniqueType.CanMoveAfterAttacking) -> MoveStrategy.ThirdLine
                base.isRanged() && unit.hasUnique(UniqueType.CanMoveAfterAttacking) && unit.getMaxMovement() > 2 -> MoveStrategy.SecondLine
                else -> MoveStrategy.FirstLine
            }
            UnitAiType.CARRIER_SEA, UnitAiType.EXPLORE, UnitAiType.EXPLORE_SEA -> MoveStrategy.ThirdLine
            // Support units (generals, admirals, siege towers), air units and civilians are no part of the plan
            else -> null
        }
    }

    private fun buildUnits(unitList: List<MapUnit>) {
        val simulated = HashSet<MapUnit>()
        val experience = ArrayList<Int>()
        for (unit in unitList) {
            if (unit in simulated || unit in ctx.cache.processedUnits) continue
            val strategy = strategyOf(unit) ?: continue
            // A unit outside its native domain violates one-unit-per-tile: it is used only alone on its tile or in a city
            val tile = unit.getTile()
            val native = if (unit.baseUnit.isWaterUnit) tile.isWater || tile.isCityCenter() else tile.isLand
            if (!native && tile.getUnits().count() != 1 && !tile.isCityCenter()) continue
            simulated.add(unit)
            units.add(SimUnit(unit, strategy))
            experience.add(unit.promotions.XP)
        }
        if (experience.isNotEmpty()) medianUnitXp = experience.sorted()[experience.size / 2]
        ourOriginalUnits = units.size
        // Everything else on the plots that is a combat unit blocks, the unit being ours or not
        val simUnits = units.mapTo(HashSet()) { it.unit }
        for (index in plots.indices) {
            val tile = plots[index]
            val occupant = tile.militaryUnit ?: continue
            if (occupant in simUnits || enemyAt[index] >= 0) continue
            if (occupant.civ != civ && isAtWar(occupant.civ)) continue
            val domainBit = if (occupant.baseUnit.isWaterUnit) 2 else 1
            val defends = TacticalSafety.canDefend(occupant)
            if (!defends && occupant.civ == civ) continue
            if (occupant.civ == civ) {
                blocked[index] = blocked[index] or 4 or domainBit
                if (occupant in ctx.cache.processedUnits || !occupant.hasMovement()) friendlyEndTurn[index] = true
                if (tile.isCityCenter()) blocked[index] = blocked[index] or 3
            } else {
                // Rules for cities of others are complex: do not try it
                blocked[index] = blocked[index] or domainBit or (if (tile.isCityCenter()) 3 else 0)
            }
        }
        for (index in plots.indices) {
            val tile = plots[index]
            if (tile.isCityCenter() && cityAt[index] < 0 && tile.getCity()?.civ.let { it != civ && it != null && !isAtWar(it) }) blocked[index] = blocked[index] or 3
        }
    }

    /** CvTacticalPlot::checkEdgePlotsForSurprises and the citadel damage of refreshVolatilePlotProperties */
    private fun buildEdges() {
        for (index in plots.indices) {
            val tile = plots[index]
            val reference = tile.aerialDistanceTo(target)
            for (neighbor in tile.neighbors) {
                val known = plotByTile[neighbor.zeroBasedIndex] >= 0
                if (known) continue
                // only outward neighbors
                if (neighbor.aerialDistanceTo(target) < reference) continue
                val city = enemyCityOf(neighbor)
                if (city != null) outsideEnemies.add(neighbor to SimDomain.Land)
                else if (neighbor in civ.viewableTiles) {
                    val enemy = enemyDefenderOf(neighbor)
                    if (enemy != null) {
                        if (enemy.baseUnit.isLandUnit) outsideEnemies.add(neighbor to SimDomain.Land)
                        else if (enemy.baseUnit.isWaterUnit) outsideEnemies.add(neighbor to SimDomain.Sea)
                    }
                } else if (!neighbor.isImpassible()) edge[index] = true
                improvementDamage[index] = maxOf(improvementDamage[index], enemyImprovementDamage(neighbor))
            }
            for (neighbor in tile.neighbors)
                if (plotByTile[neighbor.zeroBasedIndex] >= 0) improvementDamage[index] = maxOf(improvementDamage[index], enemyImprovementDamage(neighbor))
        }
        neighbors = Array(plots.size) { index ->
            plots[index].neighbors.map { plotByTile[it.zeroBasedIndex] }.filter { it >= 0 }.toList().toIntArray()
        }
    }

    /** TacticalAIHelpers::GetOtherPlayerImprovementDamage: what an improvement of someone we are at war with does to our units next to it */
    fun enemyImprovementDamage(tile: Tile): Int {
        val owner = tile.getOwner() ?: return 0
        if (owner == civ || !isAtWar(owner)) return 0
        val improvement = tile.getUnpillagedTileImprovement() ?: return 0
        return PlotDefense.nearbyEnemyDamage(improvement)
    }

    /** TacticalAIHelpers::IsPlayerCitadel: an improvement of ours that damages adjacent enemies by more than what they heal and defends well */
    fun isOurCitadel(tile: Tile): Boolean {
        if (tile.getOwner() != civ) return false
        val improvement = tile.getUnpillagedTileImprovement() ?: return false
        // IsPlayerCitadel: damage must exceed enemy healing and defense must be at least 50%.
        return PlotDefense.nearbyEnemyDamage(improvement) > 10 && PlotDefense.defenseModifier(improvement) >= 50
    }

    private fun buildDangerSources() {
        val reachRing = MAX_DISTANCE + 2
        for (other in civ.gameInfo.civilizations) {
            if (!other.isAlive() || !isAtWar(other)) continue
            for (enemy in other.units.getCivUnits()) {
                if (!enemy.isMilitary() || enemy.baseUnit.isAirUnit() || !enemy.baseUnit.isMelee() && !enemy.baseUnit.isRanged() || !enemy.isVisibleTo(civ)) continue
                val reach = enemy.baseUnit.movement.coerceAtLeast(1) + if (enemy.baseUnit.isRanged()) enemy.getRange() else 1
                if (enemy.getTile().aerialDistanceTo(target) > reachRing + reach) continue
                val index = plotOf(enemy.getTile()).let { if (it >= 0) enemyAt[it] else -1 }
                dangerUnits.add(DangerSource(enemy, if (index >= 0 && enemies[index].unit == enemy) index else -1))
            }
            for (city in other.cities) {
                if (!city.getCenterTile().isExplored(civ) || city.getCenterTile().aerialDistanceTo(target) > reachRing + city.getBombardRange()) continue
                val plot = plotOf(city.getCenterTile())
                dangerCities.add(city)
                dangerCityIndex[city] = if (plot >= 0) cityAt[plot] else -1
            }
        }
    }

    /** CvTacticalPosition::countEnemiesAndCheckVisibility: how many enemies there are (cities and barbarian camps count as one each) and how many units of ours are near them */
    private fun countEnemies() {
        var count = 0
        for (index in plots.indices) {
            if (cityAt[index] >= 0) count++
            if (campAt[index]) count++
            if (enemyAt[index] >= 0) count++
        }
        originalEnemies = count
        val field = fieldFor(allAlive())
        for (index in plots.indices)
            if (blocked[index] and 4 != 0 && field.distance[SimDomain.Both.ordinal][index] < 3) ourOriginalUnits++
    }
    //endregion

    //region Reading the plots
    val enemyCount get() = enemies.size
    fun allAlive(): BitSet = BitSet(enemies.size + cities.size).also { it.set(0, enemies.size + cities.size) }

    fun enemyIndexAt(plot: Int) = enemyAt[plot]
    fun cityIndexAt(plot: Int) = cityAt[plot]
    fun hasEnemyCivilian(plot: Int) = civilianAt[plot]
    fun hasCamp(plot: Int) = campAt[plot]
    fun isEdgePlot(plot: Int) = edge[plot]
    fun adjacentImprovementDamage(plot: Int) = improvementDamage[plot]
    fun distance(a: Int, b: Int) = plots[a].aerialDistanceTo(plots[b])

    /** CvTacticalPlot::isBlockedByNonSimUnit */
    fun isBlockedByNonSimUnit(plot: Int, domain: SimDomain = SimDomain.Both, mustBeFriendly: Boolean = false): Boolean {
        val bits = blocked[plot]
        if (mustBeFriendly && bits and 4 == 0) return false
        return when (domain) {
            SimDomain.Both -> bits and 3 != 0
            SimDomain.Land -> bits and 1 != 0
            SimDomain.Sea -> bits and 2 != 0
        }
    }

    fun isFriendlyDefenderEndTurn(plot: Int) = friendlyEndTurn[plot]

    /** Non-simulated combat units of ours on the neighbors of the plot, by domain (the deferred adjacency update of refreshVolatilePlotProperties) */
    fun nonSimFriendliesAround(plot: Int, domain: SimDomain): Int {
        var count = 0
        for (n in neighbors[plot]) {
            val bits = blocked[n]
            if (bits and 4 == 0) continue
            if (domain == SimDomain.Both && bits and 3 != 0) count++
            else if (domain == SimDomain.Land && bits and 1 != 0) count++
            else if (domain == SimDomain.Sea && bits and 2 != 0 && !plots[n].isCityCenter()) count++
        }
        return count
    }

    fun nonSimFriendliesEndTurnAround(plot: Int, domain: SimDomain): Int {
        var count = 0
        for (n in neighbors[plot]) {
            if (!friendlyEndTurn[n]) continue
            val bits = blocked[n]
            if (domain == SimDomain.Both && bits and 3 != 0) count++
            else if (domain == SimDomain.Land && bits and 1 != 0) count++
            else if (domain == SimDomain.Sea && bits and 2 != 0) count++
        }
        return count
    }

    /** Whether any plot neighbor of the plot is outside the simulation: no use, the original's passable-neighbor count (CvPlot::countPassableNeighbors) */
    fun passableNeighbors(plot: Int) = plots[plot].neighbors.count { !it.isImpassible() }

    /** CvTacticalPosition::refreshVolatilePlotProperties' distance transform for the enemies that are alive */
    fun fieldFor(alive: BitSet): EnemyField = fieldCache.getOrPut(alive.clone() as BitSet) {
        val field = EnemyField(plots.size)
        class Source(val tile: Tile, val domain: SimDomain, val isCity: Boolean)
        val sources = ArrayList<Source>()
        for (i in enemies.indices) if (alive[i]) sources.add(Source(plots[enemies[i].plot], enemies[i].domain, false))
        for (i in cities.indices) if (alive[enemies.size + i]) sources.add(Source(plots[cities[i].plot], SimDomain.Both, true))
        for ((tile, domain) in outsideEnemies) sources.add(Source(tile, domain, false))
        for (index in plots.indices) {
            val from = plots[index]
            for (source in sources) {
                val d = from.aerialDistanceTo(source.tile)
                val domains = if (source.isCity) listOf(SimDomain.Both, SimDomain.Land, SimDomain.Sea)
                    else if (source.domain == SimDomain.Both) listOf(SimDomain.Both) else listOf(SimDomain.Both, source.domain)
                for (domain in domains) {
                    val k = domain.ordinal
                    if (d < field.distance[k][index]) field.distance[k][index] = d
                    if (d == 1) field.adjacent[k][index]++
                    if (d < field.rangedDistance[k][index] && (d == 0 || hasLineOfSight(from, source.tile))) field.rangedDistance[k][index] = d
                }
            }
        }
        field
    }

    fun hasLineOfSight(from: Tile, to: Tile) = ctx.hasLineOfSight(from, to)
    //endregion

    //region Damage
    /** [SimWorld.Companion.damageOnUnit] (flanking is left to the caller) */
    fun damageOnUnit(
        attacker: MapUnit, attackerTile: Tile, attackerHealth: Int, defender: MapUnit, defenderTile: Tile, defenderHealth: Int,
        ignoreFlanking: Boolean,
    ): Pair<Int, Int> = Companion.damageOnUnit(attacker, attackerTile, attackerHealth, defender, defenderTile, defenderHealth, ignoreFlanking, resolveDeaths = false)

    /** CvTacticalAI GetSimulatedDamageFromAttackOnCity: (damage the city takes, damage the attacker takes) */
    fun damageOnCity(attacker: MapUnit, attackerTile: Tile, attackerHealth: Int, city: City, previousGarrisonDamage: Int): Triple<Int, Int, Int> {
        val attackerCombatant = SimCombatant(attacker, attackerTile, attackerHealth.coerceAtLeast(1))
        val garrison = city.getGarrison()
        val remaining = ((garrison?.health ?: 0) - previousGarrisonDamage).coerceAtLeast(0)
        val cityCombatant = CityCombatant(city, ignoreGarrison = garrison != null && remaining == 0)
        if (attacker.baseUnit.isRanged() && attacker.baseUnit.isWaterUnit && attackerTile.isCityCenter()) return Triple(0, 0, 0)
        val (dealt, garrisonDamage) = BattleDamage.calculateDamageToDefenderAndGarrison(
            attackerCombatant, cityCombatant, attackerTile, EXPECTED_RANDOMNESS, ignoreFlanking = true,
            garrisonMaxHealth = if (remaining > 0) garrison?.getMaxHealth() ?: 0 else 0)
        val taken = if (attacker.baseUnit.isRanged()) 0
            else BattleDamage.calculateDamageToAttacker(attackerCombatant, cityCombatant, attackerTile, EXPECTED_RANDOMNESS, ignoreFlanking = true)
        return Triple(dealt, taken, garrisonDamage.coerceAtMost(remaining))
    }

    /** What the city does to a unit of ours that stands next to it (CvCity::rangeCombatDamage) */
    fun cityStrike(city: City, victim: MapUnit, victimTile: Tile, victimHealth: Int): Int =
        BattleDamage.calculateDamageToDefender(CityCombatant(city), SimCombatant(victim, victimTile, victimHealth.coerceAtLeast(1)), city.getCenterTile(), EXPECTED_RANDOMNESS)

    /** The attack cache of CAttackCache: results by attacker, its plot, what it attacks and the damage already dealt */
    fun cachedAttack(unit: Int, plot: Int, enemy: Int, city: Int, self: Int, prevUnit: Int, prevCity: Int, compute: () -> IntArray): IntArray =
        attackCache.getOrPut(AttackKey(unit, plot, enemy, city, self, prevUnit, prevCity), compute)
    //endregion

    //region Danger
    /**
     *  CvUnit::GetDanger(plot, unitDamageDealt, extraDamage): what the enemies can do to the unit on [tile] next turn, given the damage the plan
     *  has dealt to them and the [selfDamage] the unit has taken in it. Capped at ten times the unit's health.
     */
    fun danger(unitIndex: Int, tile: Tile, selfDamage: Int, overlay: DamageOverlay): Int =
        dangerCache.getOrPut(DangerKey(unitIndex, tile.zeroBasedIndex, selfDamage, overlay.hash)) { computeDanger(unitIndex, tile, selfDamage, overlay) }

    private fun computeDanger(unitIndex: Int, tile: Tile, selfDamage: Int, overlay: DamageOverlay): Int {
        val unit = units[unitIndex].unit
        val health = (unit.health - selfDamage).coerceAtLeast(1)
        val defender = SimCombatant(unit, tile, health)
        val cityHere = if (tile.isCityCenter() && tile.getOwner() == civ) tile.getCity() else null
        var sum = 0
        for (source in dangerUnits) {
            val attacker = source.unit
            val extra = if (source.index >= 0) overlay.enemy[source.index] else 0
            val attackerHealth = attacker.health - extra
            if (attackerHealth <= 0) continue
            val attackerTile = attacker.getTile()
            if (attackerTile == tile) continue
            val distance = attackerTile.aerialDistanceTo(tile)
            val ranged = attacker.baseUnit.isRanged()
            val range = if (ranged) attacker.getRange() else 1
            if (distance > attacker.baseUnit.movement.coerceAtLeast(1) + range) continue
            // A unit that does not shoot attacks its own domain
            if (!ranged && attacker.baseUnit.isLandUnit != tile.isLand && !tile.isCityCenter()) continue
            val attackerCombatant = SimCombatant(attacker, attackerTile, attackerHealth)
            if (cityHere != null) {
                sum += BattleDamage.calculateDamageToDefender(attackerCombatant, CityCombatant(cityHere), attackerTile, EXPECTED_RANDOMNESS, ignoreFlanking = true)
                continue
            }
            var damage = BattleDamage.calculateDamageToDefender(attackerCombatant, defender, attackerTile, EXPECTED_RANDOMNESS)
            val attackerSelf = if (ranged) 0 else BattleDamage.calculateDamageToAttacker(attackerCombatant, defender, attackerTile, EXPECTED_RANDOMNESS)
            // A suicide attack with a unit that does not do suicide attacks is unlikely
            if (attackerSelf >= attackerHealth) damage /= 2
            sum += damage
        }
        for (city in dangerCities) {
            val cityIndex = dangerCityIndex[city] ?: -1
            val extra = if (cityIndex >= 0) overlay.city[cityIndex] else 0
            if (extra >= city.health - 1) continue
            if (city.getCenterTile().aerialDistanceTo(tile) > city.getBombardRange() || cityHere != null) continue
            sum += cityStrike(city, unit, tile, health)
        }
        if (cityHere != null) {
            // Capturing a city destroys its garrison: the unit takes its share of what the city would take
            val maxHealth = cityHere.getMaxHealth()
            return if (sum + (maxHealth - cityHere.health) < maxHealth + 50) {
                if (unit.canGarrison()) sum * 2 * unit.getMaxHealth() / maxHealth + improvementDamageAt(tile) else 0
            } else 10 * unit.getMaxHealth()
        }
        sum += improvementDamageAt(tile) + unit.getDamageFromTerrain(tile)
        return minOf(sum, 10 * 100)
    }

    private fun improvementDamageAt(tile: Tile): Int {
        val plot = plotOf(tile)
        if (plot >= 0) return improvementDamage[plot]
        return tile.neighbors.maxOfOrNull { enemyImprovementDamage(it) } ?: 0
    }
    //endregion

    //region Movement
    /**
     *  TacticalAIHelpers::GetAllPlotsInReachThisTurn for a unit starting on [startPlot] with [moves] movement left, the enemies killed so far
     *  ([freed]: no zone of control, passable) taken into account: the tiles it can end its turn on, not holding an enemy, not a plot a
     *  non-simulated unit holds, of its own domain (a unit that is not at sea does not embark here), with the movement it has left there.
     *  Zones of control cost all the movement; a tile can always be entered with a single movement point left (Unciv's rule).
     */
    fun reach(unitIndex: Int, startPlot: Int, moves: Float, freed: BitSet): Reach {
        val key = ReachKey(unitIndex, startPlot, moves.toRawBits(), freed)
        reachCache[key]?.let { return it }
        val result = computeReach(units[unitIndex], plots[startPlot], moves, freed)
        reachCache[ReachKey(unitIndex, startPlot, moves.toRawBits(), freed.clone() as BitSet)] = result
        return result
    }

    private fun isFreed(tile: Tile, freed: BitSet): Boolean {
        val plot = plotOf(tile)
        return plot >= 0 && freed[plot]
    }

    private fun canPassThrough(unit: MapUnit, tile: Tile, freed: BitSet): Boolean {
        val reason = unit.movement.cannotPassThroughReason(tile) ?: return true
        return reason == UnitMovement.CannotMoveToReason.TileIsNotEmpty && isFreed(tile, freed)
    }

    /** CvUnitMovement::IsSlowedByZOC: moving from [from] to the neighboring [to] costs all the movement, [freed] being the plots whose enemies are dead */
    fun isSlowedByZoneOfControl(unit: MapUnit, from: Tile, to: Tile, freed: BitSet): Boolean {
        if (unit.cache.ignoresZoneOfControl) return false
        for (neighbor in from.neighbors) {
            if (neighbor.aerialDistanceTo(to) != 1 || isFreed(neighbor, freed)) continue
            if (neighbor.isCityCenter()) {
                val owner = neighbor.getOwner() ?: continue
                if (civ.isAtWarWith(owner)) return true
            } else {
                val other = neighbor.militaryUnit ?: continue
                if ((other.type.isWaterUnit() || unit.type.isLandUnit() && !other.isEmbarked()) && civ.isAtWarWith(other.civ)) return true
            }
        }
        return false
    }

    private fun computeReach(simUnit: SimUnit, start: Tile, moves: Float, freed: BitSet): Reach {
        val unit = simUnit.unit
        val distance = HashMap<Tile, Float>()
        distance[start] = 0f
        if (moves > 0f && !unit.cache.cannotMove) {
            val queue = PriorityQueue<Pair<Tile, Float>>(compareBy<Pair<Tile, Float>> { it.second }.thenBy { it.first.zeroBasedIndex })
            queue.add(start to 0f)
            while (queue.isNotEmpty()) {
                val (tile, spent) = queue.poll()
                if (spent > distance.getValue(tile)) continue
                // Out of movement: nothing can be reached from here
                if (spent >= moves - Constants.minimumMovementEpsilon) continue
                for (neighbor in tile.neighbors) {
                    if (!canPassThrough(unit, neighbor, freed)) continue
                    val cost = if (isSlowedByZoneOfControl(unit, tile, neighbor, freed)) moves
                        else MovementCost.getMovementCostBetweenAdjacentTiles(unit, tile, neighbor, false)
                    var total = spent + cost
                    // One can always enter an adjacent tile, it only uses up what is left
                    if (total >= moves - Constants.minimumMovementEpsilon) total = moves
                    val known = distance[neighbor]
                    if (known == null || known > total) {
                        distance[neighbor] = total
                        queue.add(neighbor to total)
                    }
                }
            }
        }
        val inPlots = ArrayList<Pair<Int, Float>>()
        val outside = ArrayList<Pair<Tile, Float>>()
        for ((tile, spent) in distance.entries.sortedBy { it.key.zeroBasedIndex }) {
            if (!canEndTurnAt(unit, tile, start, freed)) continue
            val left = (moves - spent).coerceAtLeast(0f)
            val plot = plotOf(tile)
            if (plot >= 0) {
                if (tile != start && isBlockedByNonSimUnit(plot, simUnit.domain)) continue
                inPlots.add(plot to left)
            } else outside.add(tile to left)
        }
        inPlots.sortBy { it.first }
        return Reach(
            inPlots.map { it.first }.toIntArray(), inPlots.map { it.second }.toFloatArray(),
            outside.map { it.first }, outside.map { it.second }.toFloatArray()
        )
    }

    /** Stays within what a unit can do: its own domain, no enemy on the tile (CvUnit::canEndTurnAtPlot and the checks of updateMoveAndAttackPlotsForUnit) */
    private fun canEndTurnAt(unit: MapUnit, tile: Tile, start: Tile, freed: BitSet): Boolean {
        if (tile == start) return true
        val native = if (unit.baseUnit.isWaterUnit) tile.isWater || tile.isCityCenter() else tile.isLand
        if (!native) return false
        val plot = plotOf(tile)
        if (plot >= 0 && !isFreed(tile, freed) && (enemyAt[plot] >= 0 || cityAt[plot] >= 0)) return false
        val reason = unit.movement.getCannotMoveToReason(tile, assumeCanPassThrough = true) ?: return true
        // Our own units standing there are the plan's business (they may move away or swap); an enemy that was killed leaves the tile free
        return reason == UnitMovement.CannotMoveToReason.TileIsNotEmpty && (tile.militaryUnit?.civ == civ || isFreed(tile, freed))
    }

    /**
     *  TacticalAIHelpers::GetPlotsUnderRangedAttackFrom for the plots of the simulation: the plots with an enemy in the range of a ranged unit
     *  standing on [plot], that it can shoot at (line of sight unless it shoots over obstacles, [TargetHelper.containsAttackableEnemy]'s rules).
     */
    fun rangeAttackPlots(unitIndex: Int, plot: Int): IntArray = rangeAttackCache.getOrPut(unitIndex.toLong() * 100000 + plot) {
        val simUnit = units[unitIndex]
        val unit = simUnit.unit
        if (!simUnit.ranged) return@getOrPut IntArray(0)
        val from = plots[plot]
        val range = unit.getRange().coerceIn(1, 5)
        val ignoreLineOfSight = unit.hasUnique(UniqueType.IndirectFire, checkCivInfoUniques = true)
        val result = ArrayList<Int>()
        for (index in plots.indices) {
            if (enemyAt[index] < 0 && cityAt[index] < 0) continue
            val tile = plots[index]
            val d = from.aerialDistanceTo(tile)
            if (d < 1 || d > range) continue
            if (!ignoreLineOfSight && !hasLineOfSight(from, tile)) continue
            if (!canShootAt(unit, tile, from)) continue
            result.add(index)
        }
        result.toIntArray()
    }

    /** CvUnit::canEverRangeStrikeAt: the rules that do not depend on the plan */
    private fun canShootAt(unit: MapUnit, tile: Tile, from: Tile): Boolean {
        if (unit.baseUnit.isWaterUnit && from.isCityCenter()) return false
        if (unit.isEmbarked() && !unit.hasUnique(UniqueType.AttackOnSea)) return false
        return TargetHelper.containsAttackableEnemy(tile, MapUnitCombatant(unit))
    }

    /** Whether [unit] can attack what is on [tile] in melee (CvUnit::canMoveOrAttackInto with the attack flag) */
    fun canMeleeAttack(unitIndex: Int, tile: Tile): Boolean {
        val unit = units[unitIndex].unit
        return TargetHelper.containsAttackableEnemy(tile, MapUnitCombatant(unit))
    }

    /**
     *  CvTacticalPosition::getPlotsWithChangedVisibility: (how many tiles a unit would newly see standing on [plot], how many enemy units and cities
     *  among them). Tiles no unit of ours sees now.
     */
    fun reveal(unitIndex: Int, plot: Int): IntArray = revealCache.getOrPut(unitIndex.toLong() * 100000 + plot) {
        val unit = units[unitIndex].unit
        val tile = plots[plot]
        var newTiles = 0
        var newEnemies = 0
        for (seen in tileMap.getViewableTiles(tile.position, unit.getVisibilityRange())) {
            if (seen in civ.viewableTiles) continue
            newTiles++
            val unitThere = seen.militaryUnit
            val enemyUnit = unitThere != null && unitThere.civ != civ && isAtWar(unitThere.civ) && TacticalSafety.canDefend(unitThere)
            if (enemyUnit || enemyCityOf(seen) != null) newEnemies++
        }
        intArrayOf(newTiles, newEnemies)
    }

    /** The distance of a tile to the target for units of a domain (UpdatePlotDistanceToTarget / GetPlotDistanceToTarget), [Int.MAX_VALUE] if it is too far or not connected */
    fun distanceToTarget(tile: Tile, domain: SimDomain): Int {
        if (!targetDistanceRelevant) return tile.aerialDistanceTo(target)
        val map = targetDistance[if (domain == SimDomain.Sea) 1 else 0]
        return map[tile.zeroBasedIndex] ?: Int.MAX_VALUE
    }

    /** A breadth-first search from the target over the tiles a unit of the domain can cross (CvStepFinder), as far as units are used at all */
    private fun buildTargetDistances() {
        for (domain in 0..1) {
            val map = targetDistance[domain]
            val water = domain == 1
            map[target.zeroBasedIndex] = 0
            var frontier = listOf(target)
            var d = 0
            while (frontier.isNotEmpty() && d < DISTANCE_SEARCH_RADIUS) {
                d++
                val next = ArrayList<Tile>()
                for (tile in frontier)
                    for (neighbor in tile.neighbors) {
                        if (neighbor.zeroBasedIndex in map || neighbor.isImpassible()) continue
                        if (!water && neighbor.isWater && !civ.tech.unitsCanEmbark) continue
                        if (water && neighbor.isLand && !neighbor.isCityCenter()) continue
                        map[neighbor.zeroBasedIndex] = d
                        next.add(neighbor)
                    }
                frontier = next
            }
        }
    }
    //endregion

    companion object {
        const val MAX_TACT_PLOTS = 255
        const val EXPECTED_RANDOMNESS = TacticalTargets.EXPECTED_RANDOMNESS

        /**
         *  TacticalAIHelpers::GetSimulatedDamageFromAttackOnUnit: (damage the defender takes, damage the attacker takes) of [attacker] attacking
         *  from [attackerTile] the [defender] on [defenderTile], each at the health given. Flanking is left to the caller if [ignoreFlanking].
         */
        fun damageOnUnit(
            attacker: MapUnit, attackerTile: Tile, attackerHealth: Int, defender: MapUnit, defenderTile: Tile, defenderHealth: Int,
            ignoreFlanking: Boolean,
            resolveDeaths: Boolean = true,
        ): Pair<Int, Int> {
            val attackerCombatant = SimCombatant(attacker, attackerTile, attackerHealth.coerceAtLeast(1))
            val defenderCombatant = SimCombatant(defender, defenderTile, defenderHealth.coerceAtLeast(1))
            // Naval units cannot fire from cities
            if (attacker.baseUnit.isRanged() && attacker.baseUnit.isWaterUnit && attackerTile.isCityCenter()) return 0 to 0
            val dealt = BattleDamage.calculateDamageToDefender(attackerCombatant, defenderCombatant, attackerTile, EXPECTED_RANDOMNESS, ignoreFlanking = ignoreFlanking)
            val taken = if (attacker.baseUnit.isRanged()) 0
                else BattleDamage.calculateDamageToAttacker(attackerCombatant, defenderCombatant, attackerTile, EXPECTED_RANDOMNESS, ignoreFlanking = ignoreFlanking)
            if (resolveDeaths && !attacker.baseUnit.isRanged() && attacker.civ.gameInfo.ruleset.modOptions.constants.combatHealthStrengthLikeVp)
                return BattleDamage.resolveMeleeDeaths(attackerCombatant, defenderCombatant, dealt, taken)
            return dealt to taken
        }

        private const val DISTANCE_SEARCH_RADIUS = 14
        private val DEFAULT_LIMITS = intArrayOf(4, 4, 512)

        /**
         *  HandicapInfos' TacticalSimMaxBranches, TacticalSimMaxChoicesPerUnit and TacticalSimMaxCompletedPositions by difficulty level (the
         *  Community Patch's CoreDifficultyChanges.xml); a game on another difficulty uses the defaults of DifficultyTableChanges.sql.
         */
        private val HANDICAP_LIMITS = mapOf(
            "Settler" to intArrayOf(3, 3, 32), "Chieftain" to intArrayOf(3, 3, 64), "Warlord" to intArrayOf(3, 3, 128),
            "Prince" to intArrayOf(4, 4, 256), "King" to intArrayOf(4, 4, 384), "Emperor" to intArrayOf(4, 4, 512),
            "Immortal" to intArrayOf(5, 5, 768), "Deity" to intArrayOf(5, 5, 2560),
        )
    }
}
