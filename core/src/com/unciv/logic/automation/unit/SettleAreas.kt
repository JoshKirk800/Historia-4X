package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.MilitaryAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.tactical.TacticalTerritory
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 *  The settling map of each player in Vox Populi's form: CvPlayer::updatePlotFoundValues (the found value of every revealed plot, once per
 *  turn, [CitySiteEvaluator]), CvPlayer::GetSettlePlotQualityMeasure / GetMinAcceptableSettleQuality / HaveGoodSettlePlot (the found value against
 *  the capitals' average, CvGame::GetCityQualityReference) and CvPlayer::GetBestSettlePlot. The found values are computed on first use in a
 *  turn and kept for the rest of it, as in the original (which computes them on first read and marks them dirty on a war declaration).
 *  The BNW landmass totals (CvArea::getTotalFoundValue, CvPlayer::GetBestSettleAreas) are gone: Vox Populi has no such thing.
 */
object SettleAreas {
    /** The radius around the other civilization's cities that a no-settle promise blocks (CvDiplomacyAI::SetPlayerNoSettleRequestAccepted) */
    const val PROMISED_NO_SETTLE_RANGE = 6

    /** How many candidate sites [bestSettlePlot] tries to find a path to for a unit before it gives up (the original reaches for all of them after three) */
    private const val MAX_PATH_ATTEMPTS = 8

    /** The turns a settler may walk to its site (GeneratePath's iMaxTurns in GetBestSettlePlot) */
    private const val MAX_PATH_TURNS = 23

    /** A player's found values of one turn: by [Tile.zeroBasedIndex], -1 for plots not revealed or where no city can be founded */
    class FoundValues(val turn: Int, val values: IntArray)

    private val cache = WeakHashMap<Civilization, FoundValues>()

    /** The answer of the last [bestSettlePlot] search without a unit, which several strategies and the production AI ask for each turn (by tile index: no reference to the game from a [WeakHashMap] value) */
    private class BestPlot(val turn: Int, val signature: Int, val tileIndex: Int)

    private val bestPlotCache = WeakHashMap<Civilization, BestPlot>()

    /** CvMap::GetAIMapHint, set by the original's map scripts: 1 = naval map, 4 = offshore expansion map (Archipelago 1+4, Small Continents 4) */
    fun aiMapHint(gameInfo: GameInfo): Int = when (gameInfo.tileMap.mapParameters.type) {
        MapType.archipelago -> 1 + 4
        MapType.smallContinents -> 4
        else -> 0
    }

    /** This turn's found values of [civ], computed on first use */
    fun foundValues(civ: Civilization): FoundValues {
        synchronized(cache) {
            val cached = cache[civ]
            if (cached != null && cached.turn == civ.gameInfo.turns) return cached
        }
        return updateFoundValues(civ)
    }

    /** CvPlayer::getPlotFoundValue (CvPlot::getFoundValue): the found value of [tile] for [civ] this turn, -1 if not revealed or no city can be founded there */
    fun foundValue(civ: Civilization, tile: Tile): Int = foundValues(civ).values[tile.zeroBasedIndex]

    /**
     *  CvPlayer::updatePlotFoundValues: the found value ([CitySiteEvaluator.plotFoundValue]) of every plot [civ] has revealed, -1 elsewhere. Barbarians
     *  and city-states without a settler do not settle: nothing. The plots whose yield the evaluation ignores are those owned by others, those
     *  next to land others own and within the minimum city distance of a city, and those not revealed.
     */
    fun updateFoundValues(civ: Civilization): FoundValues = timeThis("AI.UpdateFoundValues") {
        val tiles = civ.gameInfo.tileMap.tileList
        val values = IntArray(tiles.size) { -1 }
        val doesNotSettle = civ.isBarbarian ||
            (civ.isCityState && UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = false) == 0)
        if (!doesNotSettle) {
            val minDistance = civ.gameInfo.ruleset.modOptions.constants.minimalCityDistance
            val distances = CityDistances.of(civ.gameInfo)
            val ignore = BooleanArray(tiles.size)
            for (tile in tiles) {
                val owner = tile.getOwner()
                ignore[tile.zeroBasedIndex] = if (owner != null) owner != civ
                    else if (tile.neighbors.any { val other = it.getOwner(); other != null && other != civ } &&
                        distances.closestCityDistanceInPlots(tile) < minDistance) true
                    else !civ.hasExplored(tile)
            }
            val evaluator = CitySiteEvaluator(civ, ignore)
            for (tile in tiles) if (civ.hasExplored(tile)) values[tile.zeroBasedIndex] = evaluator.plotFoundValue(tile)
        }
        val result = FoundValues(civ.gameInfo.turns, values)
        synchronized(cache) { cache[civ] = result }
        result
    }

    /** CvGame::GetCityQualityReference: 54% of the average found value of the capitals the major civilizations founded (the capitals tend to be quite good, so the threshold is lower) */
    fun cityQualityReference(gameInfo: GameInfo): Int = 54 * gameInfo.cityFoundValueReference / (max(1, gameInfo.numReferenceCities) * 100)

    /** CvGame::NewCapitalFounded for the first city of [civ], founded on [tile]: its found value (at least 0) joins the reference */
    fun recordCapitalFoundValue(civ: Civilization, tile: Tile) {
        // Deviation: a civilization whose personality is not drawn yet (a human's, before any AI help; a test's) is left out: the evaluation would draw it here
        if (!civ.ai.personalityInitialized) return
        val value = CitySiteEvaluator(civ).plotFoundValue(tile)
        civ.gameInfo.cityFoundValueReference += max(0, value)
        civ.gameInfo.numReferenceCities++
        // The evaluation of the first turn read the water bodies of a map that may still be set up: do not keep them for the turn
        WaterBodies.forget(civ.gameInfo.tileMap)
    }

    /** CvPlayer::GetSettlePlotQualityMeasure: -50 to +50, the found value of [tile] as a percentage of the capitals' reference, 50..150% mapped to -50..+50; null is -50, a map with no reference yet +50 */
    fun settlePlotQualityMeasure(civ: Civilization, tile: Tile?): Int {
        if (tile == null) return -50
        val reference = cityQualityReference(civ.gameInfo)
        if (reference < 1) return 50
        val rawPercent = 100 * foundValue(civ, tile) / reference
        return rawPercent.coerceIn(50, 150) - 100
    }

    /** CvPlayer::GetMinAcceptableSettleQuality: somewhere between -36 and 0, three times the EXPANSION flavor of the personality (clamped to 0..12) below zero */
    fun minAcceptableSettleQuality(civ: Civilization): Int = -3 * civ.ai.personality(Flavor.Expansion).coerceIn(0, 12)

    /** CvPlayer::HaveGoodSettlePlot: a plot of landmass [areaId] (-1 = anywhere) whose quality is above the minimum acceptable one */
    fun haveGoodSettlePlot(civ: Civilization, areaId: Int): Boolean {
        val threshold = minAcceptableSettleQuality(civ)
        val values = foundValues(civ).values
        val reference = cityQualityReference(civ.gameInfo)
        for (tile in civ.gameInfo.tileMap.tileList) {
            if (areaId != -1 && tile.getContinent() != areaId) continue
            if (reference < 1) return true
            val quality = (100 * values[tile.zeroBasedIndex] / reference).coerceIn(50, 150) - 100
            if (quality > threshold) return true
        }
        return false
    }

    /** CvPlot::isVisibleEnemyUnit: a unit of a player at war with [civ] on [tile], which [civ] sees */
    fun isVisibleEnemyUnit(civ: Civilization, tile: Tile): Boolean {
        if (tile !in civ.viewableTiles) return false
        for (unit in tile.getUnits()) if (unit.civ != civ && civ.isAtWarWith(unit.civ) && unit.isVisibleTo(civ)) return true
        return false
    }

    /**
     *  CvUnit::canFoundCity: [unit] can found a city and the plot allows one (CvCitySiteEvaluator::CanFoundCity via [Tile.canBeSettled]); and
     *  CvPlayer::canFound's first test, the plot is not one the civilization agreed not to settle ([isPromisedNotToSettle])
     */
    fun canFound(unit: MapUnit, tile: Tile): Boolean =
        UnitActionsFromUniques.getFoundCityAction(unit, tile) != null && tile.canBeSettled(unit.civ) && !isPromisedNotToSettle(unit.civ, tile)

    /**
     *  CvPlot::IsNoSettling as CvDiplomacyAI::SetPlayerNoSettleRequestAccepted sets it: [tile] is within 6 of a city of a civilization that
     *  [civ] agreed not to settle near ([hasPromisedNotToSettleNear]). Not for city-states or barbarians.
     */
    fun isPromisedNotToSettle(civ: Civilization, tile: Tile): Boolean {
        if (!civ.isMajorCiv) return false
        return civ.getKnownCivs().any { other ->
            other.isMajorCiv && !civ.isAtWarWith(other) && hasPromisedNotToSettleNear(civ, other) &&
                other.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= PROMISED_NO_SETTLE_RANGE }
        }
    }

    /** [civ] agreed not to settle near [other] (CvDiplomacyAI::GetPlayersWithNoSettlePolicy: [DiplomacyFlags.AgreedToNotSettleNearUs], which a war ends) */
    fun hasPromisedNotToSettleNear(civ: Civilization, other: Civilization): Boolean =
        other.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs) == true

    /**
     *  CvPlayer::GetBestSettlePlot: the best revealed plot in the world to settle, for [unit] or, with none, for [civ] (the search the strategies
     *  and the production AI make without a settler). Plots another player owns, barbarian camps, the territory of enemies of the tactical map
     *  and plots near the cities of players at war with us (within AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT) are out, so are plots within 3 of the
     *  target of a found-city operation other than [operationToIgnore]. A unit also needs to be able to found there and move there, and to embark
     *  to leave its landmass. The found value is scaled by how far the plot is from our cities: nothing at SETTLER_EVALUATION_DISTANCE plus a
     *  little over the game's length (17 plots over 512 turns, twice when cramped) or farther, all of it up to SETTLER_DISTANCE_DROPOFF_MODIFIER,
     *  linear between; a plot on another landmass is at least two thirds of its value when we want to expand offshore, and when we have no
     *  city on that landmass only its coast counts. The best candidate is returned; for a unit, the best of the first few it can walk to
     *  (within 23 turns). [scores] (optional) receives the final score of every candidate.
     *
     *  Deviations: the original's check of the cities of players at war is a `continue` of the inner loop (no effect; the territory test above
     *  catches most of it): here such a plot is out, as the code evidently intends; the path is the unit's own pathfinding over what it knows, with
     *  at most [MAX_PATH_ATTEMPTS] candidates tried where the original, after three, asks for everything the unit reaches in 23 turns.
     */
    fun bestSettlePlot(civ: Civilization, unit: MapUnit?, operationToIgnore: AiOperation?, scores: MutableMap<Tile, Int>? = null): Tile? =
        timeThis("AI.BestSettlePlot") {
            val gameInfo = civ.gameInfo
            val targeted = civ.ai.operations.settleTargets(operationToIgnore)
            val signature = targeted.fold(civ.cities.size) { hash, tile -> hash * 31 + tile.zeroBasedIndex }
            if (unit == null && scores == null) {
                synchronized(bestPlotCache) {
                    bestPlotCache[civ]?.let { if (it.turn == gameInfo.turns && it.signature == signature) return@timeThis gameInfo.tileMap.tileList.getOrNull(it.tileIndex) }
                }
            }
            val best = searchBestSettlePlot(civ, unit, targeted, scores)
            if (unit == null && scores == null) synchronized(bestPlotCache) { bestPlotCache[civ] = BestPlot(gameInfo.turns, signature, best?.zeroBasedIndex ?: -1) }
            best
        }

    private fun searchBestSettlePlot(civ: Civilization, unit: MapUnit?, targeted: List<Tile>, scores: MutableMap<Tile, Int>?): Tile? {
        val gameInfo = civ.gameInfo
        val rules = gameInfo.ruleset.ai
        val values = foundValues(civ).values
        val distances = CityDistances.of(gameInfo)
        val tacticalMap = civ.ai.tactical.lastMap
        val atWar = civ.getKnownCivs().filter { it.isMajorCiv && civ.isAtWarWith(it) }.toList()
        val homeFront = rules.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        val numCities = civ.cities.size

        // Prefer settling close in the beginning
        val timeOffset = 17 * gameInfo.turns / 512
        // The theoretical maximum distance for onshore settling: the plot value at this distance or greater is scaled to zero
        var maxSettleDistance = rules.define(AiDefine.SETTLER_EVALUATION_DISTANCE) + timeOffset
        if (civ.ai.isCramped) maxSettleDistance += timeOffset
        // The score starts to taper towards zero if we exceed this
        val dropoffThreshold = min(maxSettleDistance, rules.define(AiDefine.SETTLER_DISTANCE_DROPOFF_MODIFIER))
        val wantOffshore = civ.ai.isEconomicStrategyActive(MilitaryAi.EXPAND_TO_OTHER_CONTINENTS)
        val canEmbark = civ.tech.unitsCanEmbark
        val capitalContinent = civ.getCapital()?.getCenterTile()?.getContinent()
        val citiesByContinent = civ.cities.map { it.getCenterTile().getContinent() }.toSet()
        val unitTile = unit?.getTile()

        val candidates = ArrayList<Pair<Tile, Int>>()
        for (tile in gameInfo.tileMap.tileList) {
            var value = values[tile.zeroBasedIndex]
            if ((numCities == 0 && value < 0) || (numCities > 0 && value <= 0)) continue
            if (!civ.hasExplored(tile)) continue
            val owner = tile.getOwner()
            if ((owner != null && owner != civ) || tile.improvement == Constants.barbarianEncampment) continue
            // Check the territory, not the dominance: the latter is too volatile
            if (tacticalMap?.zoneOf(tile)?.territory == TacticalTerritory.Enemy) continue
            if (atWar.any { distances.cityDistanceInPlots(it, tile) <= homeFront }) continue
            if (targeted.any { it.aerialDistanceTo(tile) <= 3 }) continue
            if (unit != null && unitTile != null && tile.getContinent() != unitTile.getContinent() && !canEmbark) continue

            // Important: take into account the distance from existing cities
            val relevantDistance = distances.cityDistanceInPlots(civ, tile)
            var scale = mapToPercent(relevantDistance, maxSettleDistance, dropoffThreshold)
            val continent = tile.getContinent()
            // If we want offshore expansion, manipulate the distance scaler
            val offshore = continent != -1 && capitalContinent != null && continent != capitalContinent
            if (wantOffshore && offshore) scale = max(67, scale)
            // On a new continent we want to settle along the coast
            val newContinent = continent != -1 && continent !in citiesByContinent
            if (newContinent && numCities > 0 && !WaterBodies.of(civ).isCoastalLand(tile)) scale = 0
            if (scale == 0 && numCities > 0) continue

            if (unit != null && (!canFound(unit, tile) || !(tile == unitTile || unit.movement.canMoveTo(tile)))) continue

            // Factor in the distance
            value = value * scale / 100
            candidates.add(tile to value)
            scores?.put(tile, value)
        }
        if (candidates.isEmpty()) return null
        // Order by increasing score; ties keep the plot order, the last one wins
        candidates.sortBy { it.second }
        if (unit == null) return candidates.last().first

        // Try the top candidates: a successful path terminates quickly
        unit.movement.clearPathfindingCache()
        var attempts = 0
        for (index in candidates.indices.reversed()) {
            if (attempts++ >= MAX_PATH_ATTEMPTS) break
            val tile = candidates[index].first
            if (tile == unitTile) return tile
            val path = unit.movement.getShortestPath(tile)
            if (path.isNotEmpty() && path.size <= MAX_PATH_TURNS) return tile
        }
        return null
    }

    /**
     *  CvPlayer::GetPlayerWhoStoleMyFavoriteCitySite: the player who settled close to the best site we had found the last time we asked (an
     *  AI's land dispute level goes up with it, [com.unciv.logic.automation.diplomacy.DisputeAi.updateLandDisputes]). Only while we want
     *  to found cities (not ENOUGH_EXPANSION). When the previous favorite has a foreign city within 5 and the best settle plot ([bestSettlePlot])
     *  changed, the owner of that city is the thief if our path distance to the old site is less than our boldness plus
     *  AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT. Remembers the new favorite ([com.unciv.logic.automation.strategy.CivAi.previousBestSettlePlot]).
     */
    fun playerWhoStoleMyFavoriteCitySite(civ: Civilization): Civilization? {
        val ai = civ.ai
        if (ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")) {
            ai.previousBestSettlePlot = -1
            return null
        }
        // Maybe our old favorite is no longer available?
        val tiles = civ.gameInfo.tileMap.tileList
        val previous = tiles.getOrNull(ai.previousBestSettlePlot)
        // Did somebody else settle close? Search from the inside out
        var stressor: Civilization? = null
        if (previous != null) {
            for (tile in previous.getTilesInDistance(5)) {
                if (tile.isCityCenter() && tile.getOwner() != civ) { stressor = tile.getOwner(); break }
            }
            // Assume our favorite is still good
            if (stressor == null) return null
        }
        // Looking up the best settle plot is expensive, so do this only if we really need it
        val current = bestSettlePlot(civ, null, null)
        // No problem, the spot is still the best
        if (current == previous) return null
        ai.previousBestSettlePlot = current?.zeroBasedIndex ?: -1
        // Get triggered if the settle spot was close to one of our cities
        if (previous != null && stressor != null) {
            val triggerDistance = civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) +
                civ.gameInfo.ruleset.ai.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
            if (CityDistances.cityDistancePathLength(civ, previous) < triggerDistance) return stressor
        }
        return null
    }

    /** MapToPercent: [value] mapped linearly to 0..100, [zeroAt] being 0 and [hundredAt] 100; outside the thresholds the minimum or maximum */
    fun mapToPercent(value: Int, zeroAt: Int, hundredAt: Int): Int = when {
        hundredAt != zeroAt -> ((value - zeroAt) * 100 / (hundredAt - zeroAt)).coerceIn(0, 100)
        value > hundredAt -> 100
        value < zeroAt -> 0
        else -> 50
    }
}
