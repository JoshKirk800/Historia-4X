package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.DealResourceValues
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** CvMilitaryAI ArmyType of an attack target (ARMY_TYPE_LAND / NAVAL / COMBINED) */
enum class TargetArmyType { Land, Naval, Combined }

/**
 *  CvAttackTarget: an army path from [musterCity] to [targetCity] ([pathLength] tiles, both ends included); [stagingTile] is the 4th
 *  tile from the end (the muster city on a path of 4 tiles or fewer). The approach is the best of [AttackTargetsAi.bestTargetApproach].
 */
class AttackTarget(
    val musterCity: City,
    val stagingTile: Tile,
    val targetCity: City,
    val pathLength: Int,
    val armyType: TargetArmyType,
    val approachScore: Int,
) {
    /** CvAttackTarget::m_bPreferred: scored above a third of the best target */
    var preferred = false
}

/**
 *  Vox Populi's attack targets of CvMilitaryAI::UpdateAttackTargets (CvMilitaryAI.cpp 811-960): from each of our cities the army paths
 *  by land and by sea to every foreign city in reach, the approach to it, whether the path is good, the target's score; the best
 *  become [MilitaryAi.potentialAttackTargets], and the same paths the other way round make our cities exposed to their owners
 *  ([MilitaryAi.exposedCities]).
 */
object AttackTargetsAi {
    /** CvAStar's PATH_BASE_COST as 100: ArmyStepCost's scales are percentages of it */
    private const val BASE_COST = 100

    /**
     *  CvMilitaryAI::UpdateAttackTargets. For each muster city, the land and the water army paths ([armyPaths]) to cities of valid
     *  players ([isPlayerValid]). A target keeps its best approach ([bestTargetApproach]) if that scores above 0 and the path is good
     *  ([armyPathIsGood], fewer than 3 bad tiles); its score is [scoreAttackTarget]. The path reversed is the other player's attack on us:
     *  kept (scored approach x 10 / sqrt(length), their approach from their side) if our approach on them scored above 0 - the original
     *  tests the forward score here - and it is good for them with fewer than 6 bad tiles. Attack targets sorted by score, those above 0
     *  are kept once per city and army type, preferred above a third of the best score; defense options above a third of the best one
     *  make the city exposed, once per city and enemy.
     */
    fun updateAttackTargets(civ: Civilization): Pair<List<AttackTarget>, List<AttackTarget>> {
        val attackOptions = ArrayList<Pair<AttackTarget, Int>>()
        val defenseOptions = ArrayList<Pair<AttackTarget, Int>>()
        val economicValues = HashMap<City, Int>()
        val closestCities = ClosestCities(civ)
        for (muster in civ.cities) {
            for (water in listOf(false, true)) {
                for (path in armyPaths(civ, muster, water)) {
                    val targetCity = path.last().getCity() ?: continue
                    val other = targetCity.civ
                    if (!isPlayerValid(civ, other)) continue

                    val target = makeTarget(path, civ)
                    if (target != null && target.approachScore > 0 && armyPathIsGood(path, civ, other, 3, closestCities)) {
                        val score = scoreAttackTarget(civ, target, economicValues)
                        attackOptions.add(target to score)
                    }

                    val reversed = path.asReversed()
                    val reverseTarget = makeTarget(reversed, other) ?: continue
                    if (target != null && target.approachScore > 0 && armyPathIsGood(reversed, other, civ, 6, closestCities)) {
                        val score = (reverseTarget.approachScore * 10 / sqrt(reversed.size.toDouble())).toInt()
                        defenseOptions.add(reverseTarget to score)
                    }
                }
            }
        }

        val potential = ArrayList<AttackTarget>()
        attackOptions.sortByDescending { it.second }
        val bestAttack = attackOptions.firstOrNull()?.second ?: 0
        for ((target, score) in attackOptions) {
            if (score == 0) continue
            target.preferred = score > bestAttack / 3
            if (potential.any { it.targetCity == target.targetCity && it.armyType == target.armyType }) continue
            potential.add(target)
        }

        val exposed = ArrayList<AttackTarget>()
        defenseOptions.sortByDescending { it.second }
        val bestDefense = defenseOptions.firstOrNull()?.second ?: 0
        for ((target, score) in defenseOptions) {
            if (score <= bestDefense / 3) continue
            if (exposed.any { it.targetCity == target.targetCity && it.musterCity.civ == target.musterCity.civ }) continue
            exposed.add(target)
        }
        return potential to exposed
    }

    /** CvMilitaryAI::IsPlayerValid: barbarians always; a city-state attacks only those it is at war with; else CvDiplomacyAI::IsPlayerValid */
    fun isPlayerValid(civ: Civilization, other: Civilization): Boolean {
        if (other.isBarbarian) return true
        if (civ.isCityState && !civ.isAtWarWith(other)) return false
        return civ.ai.diplomacy.isPlayerValid(other)
    }

    /** CvAttackTarget::SetWaypoints and MilitaryAIHelpers::SetBestTargetApproach for [attacker]; null for a path of a single tile */
    private fun makeTarget(path: List<Tile>, attacker: Civilization): AttackTarget? {
        if (path.size < 2) return null
        val muster = path.first().getCity() ?: return null
        val targetCity = path.last().getCity() ?: return null
        val staging = if (path.size > 4) path[path.size - 4] else path.first()
        val (armyType, score) = bestTargetApproach(attacker, staging, targetCity) ?: return AttackTarget(muster, staging, targetCity, path.size, TargetArmyType.Land, 0)
        return AttackTarget(muster, staging, targetCity, path.size, armyType, score)
    }

    //region Paths (CvMilitaryAI::GetArmyPathsFromCity, PT_ARMY_LAND / PT_ARMY_WATER)

    /**
     *  CvMilitaryAI::GetArmyPathsFromCity: the cheapest army paths from [muster] to the cities of everyone but us and the city-states
     *  allied to us whose tile or a neighbor we have revealed, within the trade route range of the domain (CvPlayerTrade::GetTradeRouteRange,
     *  in steps of the base cost). Steps (ArmyStepValidLand / ArmyStepValidWater, right of passage and enemies ignored): revealed tiles;
     *  by land no water and no impassable tile, by sea water tiles that are not impassable and coastal cities. Costs (ArmyStepCost): 54 on
     *  a route, 157 on rough ground, 213 for an embarkation change outside cities, 67 from water to water, else 100.
     */
    fun armyPaths(civ: Civilization, muster: City, water: Boolean): List<List<Tile>> {
        val destinations = HashSet<Tile>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || other.isCityState && other.allyCiv == civ) continue
            for (city in other.cities) {
                val tile = city.getCenterTile()
                if (civ.hasExplored(tile) || tile.neighbors.any { civ.hasExplored(it) }) destinations.add(tile)
            }
        }
        if (destinations.isEmpty()) return emptyList()

        val maxCost = tradeRouteRange(civ, muster, water) * BASE_COST
        val start = muster.getCenterTile()
        val cost = HashMap<Tile, Int>()
        val parent = HashMap<Tile, Tile>()
        val queue = PriorityQueue<Pair<Tile, Int>>(compareBy { it.second })
        cost[start] = 0
        queue.add(start to 0)
        val found = ArrayList<Tile>()
        while (queue.isNotEmpty()) {
            val (tile, known) = queue.poll()
            if (known > (cost[tile] ?: Int.MAX_VALUE)) continue
            if (tile != start && tile in destinations) {
                found.add(tile)
                if (found.size == destinations.size) break
            }
            for (next in tile.neighbors) {
                if (!isValidStep(civ, next, water)) continue
                val nextCost = known + stepCost(tile, next)
                if (nextCost > maxCost || nextCost >= (cost[next] ?: Int.MAX_VALUE)) continue
                cost[next] = nextCost
                parent[next] = tile
                queue.add(next to nextCost)
            }
        }
        return found.map { end ->
            val path = ArrayList<Tile>()
            var tile: Tile? = end
            while (tile != null) { path.add(tile); tile = parent[tile] }
            path.reverse()
            path
        }
    }

    private fun isValidStep(civ: Civilization, tile: Tile, water: Boolean): Boolean {
        if (!civ.hasExplored(tile)) return false
        if (!water) return tile.isLand && !tile.isImpassible()
        if (tile.isWater) return !tile.isImpassible()
        return tile.isCityCenter() && tile.neighbors.any { it.isWater }
    }

    private fun stepCost(from: Tile, to: Tile): Int {
        val scale = when {
            to.roadStatus != RoadStatus.None -> 54
            to.isRoughTerrain() -> 157
            from.isWater != to.isWater && !from.isCityCenter() && !to.isCityCenter() -> 213
            from.isWater && to.isWater -> 67
            else -> 100
        }
        return BASE_COST * scale / 100
    }

    /** CvPlayerTrade::GetTradeRouteRange for [origin]: the base range of the trade unit of the domain (10 by land, 20 by sea) with its uniques */
    private fun tradeRouteRange(civ: Civilization, origin: City, water: Boolean): Int {
        val trade = civ.tradeRoutes
        val base = trade.getTradeUnitType(water)?.let { trade.getBaseRange(it) } ?: if (water) 20 else 10
        return trade.getRange(origin, water, base)
    }
    //endregion

    //region Judging a target

    /**
     *  MilitaryAIHelpers::ArmyPathIsGood: paths of fewer than 4 tiles always; else over the tiles 3 from either end that are owned or
     *  within 3 of a city, counts those whose closest city belongs to [enemy] but is not the target or to [attacker] but is not the muster
     *  (cities on the coast only for a path by sea), and those whose closest city belongs to a player [attacker] is at war with or that
     *  are owned by a third party whose territory is not friendly to [attacker]. Good if both counts stay below [threshold].
     */
    fun armyPathIsGood(path: List<Tile>, attacker: Civilization, enemy: Civilization, threshold: Int, closestCities: ClosestCities = ClosestCities(attacker)): Boolean {
        if (path.size < 4) return true
        val waterPath = path[1].isWater
        val muster = path.first().getCity()
        val target = path.last().getCity()
        var thirdPartyTiles = 0
        var wrongCityTiles = 0
        for (i in 3 until path.size - 3) {
            val tile = path[i]
            val closest = closestCities.of(tile) ?: continue
            if (closest.getCenterTile().aerialDistanceTo(tile) > 3 && tile.getOwner() == null) continue
            val owner = closest.civ
            when {
                owner == enemy -> if (closest != target && (!waterPath || closest.isCoastal())) wrongCityTiles++
                owner == attacker -> if (closest != muster && (!waterPath || closest.isCoastal())) wrongCityTiles++
                attacker.isAtWarWith(owner) -> thirdPartyTiles++
                tile.getOwner() != null && tile.getOwner() != enemy && !isFriendlyTerritory(tile, attacker) -> thirdPartyTiles++
            }
        }
        return thirdPartyTiles < threshold && wrongCityTiles < threshold
    }

    /** CvGame::GetClosestCityByPlots, remembered per tile for one [updateAttackTargets] */
    class ClosestCities(civ: Civilization) {
        private val cities = civ.gameInfo.getCities().toList()
        private val closest = HashMap<Tile, City>()
        fun of(tile: Tile): City? = closest[tile] ?: cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }?.also { closest[tile] = it }
    }

    /** CvPlot::IsFriendlyTerritory: ours, a major's that opens its borders to us, or a city-state's that we are friends with */
    private fun isFriendlyTerritory(tile: Tile, civ: Civilization): Boolean {
        val owner = tile.getOwner() ?: return false
        if (civ.isBarbarian) return false
        if (owner == civ) return true
        val diplomacy = civ.getDiplomacyManager(owner) ?: return false
        if (owner.isCityState) return !civ.isCityState && !owner.isAtWarWith(civ)
            && owner.getDiplomacyManager(civ)!!.relationshipIgnoreAfraid() >= RelationshipLevel.Friend
        return diplomacy.hasOpenBorders
    }

    /**
     *  MilitaryAIHelpers::SetBestTargetApproach: the best of the land, naval and combined approaches ([evaluateTargetApproach]), null
     *  unless it scores above 30; land wins ties, then naval
     */
    fun bestTargetApproach(attacker: Civilization, staging: Tile, targetCity: City): Pair<TargetArmyType, Int>? {
        val land = evaluateTargetApproach(attacker, staging, targetCity, TargetArmyType.Land)
        val naval = evaluateTargetApproach(attacker, staging, targetCity, TargetArmyType.Naval)
        val combined = evaluateTargetApproach(attacker, staging, targetCity, TargetArmyType.Combined)
        return when {
            land >= naval && land >= combined && land > 30 -> TargetArmyType.Land to land
            naval >= land && naval >= combined && naval > 30 -> TargetArmyType.Naval to naval
            combined >= naval && combined >= land && combined > 30 -> TargetArmyType.Combined to combined
            else -> null
        }
    }

    /**
     *  MilitaryAIHelpers::EvaluateTargetApproach: 0 if the staging tile is water for a land army or land for the others. Over the tiles
     *  within 3 of the target and of the staging tile that the attacker may enter (passable, not a city; not owned by a third party that
     *  closes its borders to it), in an area of the target city's (its continent or a water body next to it): by land only land tiles,
     *  for the navy only water, combined water or coastal land; none next to an enemy improvement that damages units by 25 or more. A tile is
     *  good if it is not rough, is ours with an intact route, or is 2 from the target and sees it; 3 points for good, 1 for usable,
     *  x 100 / 45 (x 100 / 54 combined).
     *
     *  Deviation: the movement traits that make rough tiles good (woodland, hills, mountains, rivers) are not read.
     */
    fun evaluateTargetApproach(attacker: Civilization, staging: Tile, targetCity: City, type: TargetArmyType): Int {
        if (type == TargetArmyType.Land && staging.isWater) return 0
        if (type != TargetArmyType.Land && !staging.isWater) return 0
        val target = targetCity.getCenterTile()
        val bodies = WaterBodies.of(attacker)
        val targetAreas = target.neighbors.filter { it.isWater }.map { -1 - bodies.bodyOf(it) }.toSet() + target.getContinent()
        var good = 0
        var usable = 0
        for (tile in target.getTilesInDistance(3)) {
            if (tile.isCityCenter() || !isValidMovePlot(attacker, tile)) continue
            val owner = tile.getOwner()
            if (owner != null && owner != attacker && owner != targetCity.civ
                && attacker.getDiplomacyManager(owner)?.hasOpenBorders != true) continue
            if (staging.aerialDistanceTo(tile) > 3) continue
            val area = if (tile.isWater) -1 - bodies.bodyOf(tile) else tile.getContinent()
            if (area !in targetAreas) continue
            when (type) {
                TargetArmyType.Land -> if (tile.isWater) continue
                TargetArmyType.Naval -> if (!tile.isWater) continue
                TargetArmyType.Combined -> if (!tile.isWater && tile.neighbors.none { it.isWater }) continue
            }
            if (enemyImprovementDamage(attacker, tile) >= 25) continue
            val isGood = !tile.isRoughTerrain()
                || owner == attacker && tile.roadStatus != RoadStatus.None && !tile.roadIsPillaged
                || target.aerialDistanceTo(tile) == 2 && target in tile.getViewableTilesList(2)
            if (isGood) good++ else usable++
        }
        val scale = if (type == TargetArmyType.Combined) 54 else 45
        return (good * 3 + usable) * 100 / scale
    }

    /** CvPlot::isValidMovePlot(player, no territory check): passable, or on an intact route; water only once [civ] may embark (deep water once it may cross the ocean) */
    private fun isValidMovePlot(civ: Civilization, tile: Tile): Boolean {
        if (tile.isImpassible()) return tile.roadStatus != RoadStatus.None && !tile.roadIsPillaged
        if (tile.isWater) return if (tile.isOcean) civ.tech.embarkedUnitsCanEnterOcean else civ.tech.unitsCanEmbark
        return true
    }

    /** TacticalAIHelpers::GetOtherPlayerImprovementDamage: the damage that improvements of others than [civ] next to [tile] (or on it) deal */
    private fun enemyImprovementDamage(civ: Civilization, tile: Tile): Int {
        var damage = 0
        for (near in tile.getTilesInDistance(1)) {
            val owner = near.getOwner() ?: continue
            if (owner == civ) continue
            val improvement = near.getUnpillagedTileImprovement() ?: continue
            for (unique in improvement.getMatchingUniques(UniqueType.DamagesAdjacentEnemyUnits))
                damage = max(damage, unique.params[0].toInt())
        }
        return damage
    }

    /**
     *  CvMilitaryAI::ScoreAttackTarget: approach x distance weight x desirability x sqrt(economic value / the city's hit points). The
     *  distance weight falls linearly with the path length from 10 at 8 tiles to 1 at 24 by land (6 to 1 from 8 to 36 by sea). Desirability:
     *  x 2.5 for an original capital when going for (or close to) world conquest, x 1.5 for a city we founded; a city-state's city / 2,
     *  x 100 / 250 more when its ally is at war with us, 0 while we fight a major and not that city-state.
     *
     *  Missing inputs (none): the city-state quests of the original (liberation, war, acquire a city - Vox Populi's), IsTryingToLiberate,
     *  the city-state combat trait, conquest yields by terrain, resource monopolies.
     */
    fun scoreAttackTarget(civ: Civilization, target: AttackTarget, economicValues: MutableMap<City, Int> = HashMap()): Int {
        val city = target.targetCity
        val length = target.pathLength.toFloat()
        val distanceWeight = if (target.armyType == TargetArmyType.Land)
            min(10f, max(1f, (length - 8f) * (1f - 10f) / (24f - 8f) + 10f))
        else min(6f, max(1f, (length - 8f) * (1f - 6f) / (36f - 8f) + 6f))

        val rules = civ.gameInfo.ruleset.ai
        var desirability = 1f
        if (city.isOriginalCapital && (civ.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules)))
            desirability = desirability * 250 / 100
        if (city.foundingCivObject == civ) desirability = desirability * 150 / 100
        if (city.civ.isCityState) {
            desirability /= 2
            val ally = city.civ.allyCiv
            if (ally != null) {
                if (ally.isAtWarWith(civ)) desirability = desirability * 100 / 250
            } else if (civ.gameInfo.civilizations.any { it.isMajorCiv && civ.isAtWarWith(it) } && !civ.isAtWarWith(city.civ)) return 0
        }

        val value = economicValues.getOrPut(city) { economicValue(city, civ) }
        val economic = sqrt(max(0, value).toFloat() / max(1, city.health).toFloat())
        return (target.approachScore * distanceWeight * desirability * economic).toInt()
    }

    /**
     *  CvCity::updateEconomicValue / getEconomicValue for [possibleOwner], in hundredths of gold: (food x 3 + production x 4 + science x 3 +
     *  gold (after building maintenance) + culture x 3 + faith x 3) / 3, + 23 per world wonder; plus the resources the city owns or will
     *  likely claim within 3 tiles: a luxury quantity x happiness per luxury x 200 (100 when the World Congress bans it; 600 if [possibleOwner]
     *  has none or the owner would lose its last), a strategic resource quantity x 400 (800 likewise). Food is the city's whole food, before
     *  what the citizens eat. Tourism and golden age points per city have no counterpart here.
     */
    fun economicValue(city: City, possibleOwner: Civilization): Int {
        val stats = city.cityStats.currentCityStats
        val grossFood = stats.food + city.population.population * 2
        var yieldValue = (grossFood * 100 * 3 + stats.production * 100 * 4 + stats.science * 100 * 3 + stats.gold * 100 +
            stats.culture * 100 * 3 + stats.faith * 100 * 3).toInt()
        yieldValue /= 3
        yieldValue += city.cityConstructions.getBuiltBuildings().count { it.isWonder } * 23

        val owner = city.civ
        var resourceValue = 0
        for (tile in city.getCenterTile().getTilesInDistance(3)) {
            val resource = tile.tileResource ?: continue
            if (!owner.canSeeResource(resource)) continue
            val good = if (tile.getOwner() != null) tile.getCity() == city
                else tile.neighbors.any { it.getOwner() == owner } && tile.neighbors.none { it.getOwner() != null && it.getOwner() != owner }
            if (!good) continue
            if (resource.getMatchingUniques(UniqueType.ObsoleteWith).any { possibleOwner.tech.isResearched(it.params[0]) }) continue
            val quantity = if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount else 1
            if (quantity <= 0) continue
            val lacking = possibleOwner.getResourceAmount(resource.name) == 0 || owner.getResourceAmount(resource.name) == quantity
            when (resource.resourceType) {
                ResourceType.Luxury -> {
                    var value = if (city.civ.gameInfo.league?.isLuxuryBanned(resource.name) == true) 100 else 200
                    if (lacking) value = 600
                    var happiness = DealResourceValues.luxuryHappiness(possibleOwner)
                    possibleOwner.forEachMatchingUnique(UniqueType.BonusHappinessFromLuxury) { happiness += it.params[0].toInt() }
                    resourceValue += quantity * happiness * value
                }
                ResourceType.Strategic -> resourceValue += quantity * if (lacking) 800 else 400
                else -> {}
            }
        }
        return yieldValue + resourceValue
    }
    //endregion
}
