package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit

/**
 *  Vox Populi's spaceship production planner (CvPlayerAI::AI_doSpaceshipAndUtopiaProduction, GetBestCitiesForSpaceshipParts,
 *  CvPlayer::GetNumSpaceshipPartsBuildableNow): the AI picks the cities that build the spaceship parts on the player level, before its
 *  cities choose their production. BNW has no Utopia project, so that half is none. Not ported: the production focus VP sets for a
 *  moment to count a city's turns (the turns are those of its current focus), airlifts between the city and the capital.
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
