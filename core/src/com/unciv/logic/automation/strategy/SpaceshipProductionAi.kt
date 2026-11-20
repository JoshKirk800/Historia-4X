package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.roundToInt

/**
 *  Vox Populi's spaceship production planner (CvPlayerAI::AI_doSpaceshipAndUtopiaProduction, GetBestCitiesForSpaceshipParts,
 *  CvPlayer::GetNumSpaceshipPartsBuildableNow): the AI picks the cities that build the spaceship parts on the player level, before its
 *  cities choose their production. BNW has no Utopia project, so that half is none. Not ported: the production focus VP sets for a
 *  moment to count a city's turns (the turns are those of its current focus), airlifts between the city and the capital. Also the
 *  Aluminum the AI keeps for the parts and for the buildings of its [coreCities] (read by the building and unit sanity).
 */
object SpaceshipProductionAi {

    /** The space race this game has: a victory with spaceship parts to add, enabled */
    private fun spaceRace(civ: Civilization): Victory? = civ.gameInfo.ruleset.victories.values.firstOrNull {
        it.requiredSpaceshipParts.isNotEmpty() && it.name in civ.gameInfo.gameParameters.victoryTypes
    }

    /** The spaceship part units of the ruleset (CvUnitEntry::GetSpaceshipProject) */
    private fun parts(civ: Civilization): List<BaseUnit> =
        civ.gameInfo.ruleset.units.values.filter { it.hasUnique(UniqueType.SpaceshipPart) }

    private fun isPart(construction: Any?) = construction is BaseUnit && construction.hasUnique(UniqueType.SpaceshipPart)

    private fun producingPart(city: City) = isPart(city.cityConstructions.getCurrentConstruction())

    /**
     *  CvTeam::GetSSProjectCount(true): the spaceship parts added to the capital or being built in a city, and the Apollo Program once built
     *  (the original counts a project once built or while in production; here a part counts when added or when a city builds it now)
     */
    fun projectCount(civ: Civilization): Int =
        civ.victoryManager.currentsSpaceshipParts.values.sum() + civ.cities.count { producingPart(it) } +
            (if (GrandStrategyAi.hasApolloProgram(civ)) 1 else 0)

    /** RESOURCE_ALUMINUM, the resource Vox Populi keeps for the spaceship */
    const val ALUMINUM = "Aluminum"

    /** CvDiplomacyAI::IsGoingForSpaceshipVictory || IsCloseToSpaceshipVictory */
    fun plansSpaceship(civ: Civilization): Boolean = civ.ai.isGoingForSpaceshipVictory() ||
        VictoryProgressAi.isCloseToSpaceshipVictory(civ, civ.gameInfo.ruleset.ai)

    /**
     *  CvPlayer::DoUpdateCoreCitiesForSpaceshipProduction, computed when asked (VP keeps the list, updated each turn and on a city's loss):
     *  for an AI that [plansSpaceship], of its cities that are no puppets and not under siege, the AI_NUM_CORE_CITIES_FOR_SPACESHIP with
     *  the most production per turn for a spaceship part (in hundredths, x2 in the capital, -4 per tile to the capital without an airlift),
     *  those above 0, the most first.
     */
    fun coreCities(civ: Civilization): List<City> {
        if (civ.isHuman() || !plansSpaceship(civ)) return emptyList()
        val part = parts(civ).firstOrNull() ?: return emptyList()
        val capital = civ.getCapital(firstCityIfNoCapital = false)
        val weights = ArrayList<Pair<City, Int>>()
        for (city in civ.cities) {
            if (city.isPuppet || CityDangerAi.isUnderSiege(city)) continue
            var weight = (city.cityConstructions.productionForConstructionExact(part.name) * 100f).roundToInt()
            if (city.isCapital()) weight *= 2
            else if (!city.canAirlift() && capital != null) weight -= 4 * city.getCenterTile().aerialDistanceTo(capital.getCenterTile())
            if (weight > 0) weights.add(city to weight)
        }
        return weights.sortedByDescending { it.second }
            .take(civ.gameInfo.ruleset.ai.define(AiDefine.AI_NUM_CORE_CITIES_FOR_SPACESHIP)).map { it.first }
    }

    /**
     *  CvPlayer::GetNumAluminumStillNeededForCoreCities: for an AI that [plansSpaceship], two Aluminum-using buildings for each of the
     *  [coreCities] (VP's hard-coded spaceship factory and hydro plant), less the Aluminum of those already built or queued there
     */
    fun aluminumStillNeededForCoreCities(civ: Civilization): Int {
        if (!plansSpaceship(civ)) return 0
        val core = coreCities(civ)
        if (core.isEmpty()) return 0
        var total = core.size * 2
        for (building in civ.gameInfo.ruleset.buildings.values) {
            val aluminum = building.getResourceRequirementsPerTurn()[ALUMINUM]
            if (aluminum <= 0) continue
            for (city in core)
                if (city.cityConstructions.isBuilt(building.name) || building.name in city.cityConstructions.constructionQueue) total -= aluminum
        }
        return total
    }

    /**
     *  CvPlayer::GetNumAluminumStillNeededForSpaceship: for a major with a capital that [plansSpaceship], one Aluminum for each spaceship
     *  part still to add that no city is building
     */
    fun aluminumStillNeededForSpaceship(civ: Civilization): Int {
        if (civ.isBarbarian || civ.isCityState || !plansSpaceship(civ)) return 0
        if (civ.getCapital(firstCityIfNoCapital = false) == null) return 0
        val spaceRace = spaceRace(civ) ?: return 0
        return spaceRace.requiredSpaceshipParts.size - civ.victoryManager.currentsSpaceshipParts.values.sum() - civ.cities.count { producingPart(it) }
    }

    /**
     *  CvPlayer::GetNumSpaceshipPartsBuildableNow(true): with the space race on and the Apollo Program built, the parts still to add of
     *  the kinds whose technology we know
     */
    fun numPartsBuildableNow(civ: Civilization): Int {
        val spaceRace = spaceRace(civ) ?: return 0
        if (!GrandStrategyAi.hasApolloProgram(civ)) return 0
        val required = spaceRace.requiredSpaceshipPartsAsCounter
        var total = 0
        for (part in parts(civ)) {
            if (part.requiredTech != null && !civ.tech.isResearched(part.requiredTech!!)) continue
            total += required[part.name] - civ.victoryManager.currentsSpaceshipParts[part.name]
        }
        return total
    }

    /**
     *  CvPlayerAI::GetBestCitiesForSpaceshipParts: of our cities that are no puppets, not under siege, in resistance, being razed or about
     *  to fall, within 40 tiles of the capital and not building a world wonder, each part still needed goes to the city where all its
     *  parts would be done soonest (its turns for a part, + its turns of parts already given, + the turns to bring the part to the
     *  capital: distance / 15, +2 off the capital's landmass without a coast); a part already started counts towards the first. The cities
     *  with parts, weighted by their turns + the progress (+2 while building a part), the heaviest first, as many as parts can be built now.
     */
    fun bestCitiesForParts(civ: Civilization): List<City> {
        val capital = civ.getCapital(firstCityIfNoCapital = false) ?: return emptyList()
        val spaceRace = spaceRace(civ) ?: return emptyList()
        val stillNeeded = spaceRace.requiredSpaceshipParts.size - civ.victoryManager.currentsSpaceshipParts.values.sum()
        val buildableNow = numPartsBuildableNow(civ)
        if (stillNeeded <= 0 || buildableNow <= 0) return emptyList()
        val parts = parts(civ)
        val anyPart = parts.firstOrNull() ?: return emptyList()

        val considered = civ.cities.filter { city ->
            !city.isPuppet && !CityDangerAi.isUnderSiege(city) && !city.isInResistance() && !city.isBeingRazed
                && !CityDangerAi.isInDangerOfFalling(city, extraCareful = false)
                && city.getCenterTile().aerialDistanceTo(capital.getCenterTile()) <= 40
                && !(city.cityConstructions.getCurrentConstruction().let { it is Building && it.isWonder })
        }
        if (considered.isEmpty()) return emptyList()

        val turnsForNewPart = considered.map { ProductionAi.turnsLeft(it, anyPart) }
        val transportTurns = considered.map { city ->
            if (city == capital) 0
            else city.getCenterTile().aerialDistanceTo(capital.getCenterTile()) / 15 +
                (if (city.getCenterTile().getContinent() != capital.getCenterTile().getContinent() && !city.isCoastal()) 2 else 0)
        }
        val existingProgress = IntArray(considered.size)
        val producing = BooleanArray(considered.size)
        for ((i, city) in considered.withIndex()) {
            for (part in parts) {
                if (city.cityConstructions.getWorkDone(part.name) <= 0) continue
                existingProgress[i] = maxOf(existingProgress[i], turnsForNewPart[i] - ProductionAi.turnsLeft(city, part))
                if (city.cityConstructions.currentConstructionName() == part.name) producing[i] = true
            }
        }
        val totalTurns = IntArray(considered.size) { -existingProgress[it] }
        repeat(stillNeeded) {
            var best = -1
            var bestTurns = Int.MAX_VALUE
            for (i in considered.indices) {
                val turns = totalTurns[i] + turnsForNewPart[i] + transportTurns[i]
                if (turns < bestTurns) {
                    best = i
                    bestTurns = turns
                }
            }
            totalTurns[best] += turnsForNewPart[best]
        }
        return considered.indices.filter { totalTurns[it] > 0 }
            .sortedByDescending { totalTurns[it] + existingProgress[it] + if (producing[it]) 2 else 0 }
            .take(buildableNow)
            .map { considered[it] }
    }

    /**
     *  CvPlayerAI::AI_doSpaceshipAndUtopiaProduction for an AI major: with parts to build, a city building a part that is not one of the
     *  [bestCitiesForParts] drops it, and those best cities that are not building a part drop what they build; each of them takes the
     *  part it has already put production into, else the first part it can build. A city left without production chooses as usual.
     */
    fun doSpaceshipProduction(civ: Civilization) {
        if (civ.isHuman() || !civ.isMajorCiv || civ.cities.isEmpty()) return
        if (numPartsBuildableNow(civ) == 0) return
        val best = bestCitiesForParts(civ)
        for (city in civ.cities)
            if (producingPart(city) && city !in best) city.cityConstructions.editQueue { clear() }
        val available = civ.cities.filter { it in best && !producingPart(it) }
        if (available.isEmpty()) return
        for (city in available) city.cityConstructions.editQueue { clear() }
        val parts = parts(civ).map { civ.getEquivalentUnit(it) }
        for (city in available) {
            val started = parts.firstOrNull { city.cityConstructions.getWorkDone(it.name) > 0 && it.isBuildable(city.cityConstructions) }
            if (started != null) city.cityConstructions.setCurrentConstruction(started.name)
        }
        for (city in available) {
            if (city.cityConstructions.getCurrentConstruction() is BaseUnit) continue
            val part = parts.firstOrNull { it.isBuildable(city.cityConstructions) } ?: continue
            city.cityConstructions.setCurrentConstruction(part.name)
        }
    }
}
