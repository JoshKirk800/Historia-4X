package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization

/**
 *  Vox Populi's CvDiplomacyAI::DoUpdateWarStates (CvDiplomacyAI.cpp, Release-5.4.8; D7), after the war score instead of BNW's forces on the
 *  home fronts: for every player we are at war with, the danger of each side's cities from the other side and its war allies, the cities
 *  endangered, whether either side has a city in serious danger, and the war score ([WarDamageAi.warScore]) decide between nearly
 *  defeated, defensive, troubled, stalemate, calm, offensive and nearly won, and add up to the overall state of all wars.
 *
 *  A side's danger counts, for each of its cities in danger from the other side's allies ([CityDangerAi.isInDangerFromPlayers]): 1, +1
 *  for each of its land and water zones the enemy dominates in the side's last tactical map, and when the city is under siege +3 (+1 if it
 *  is not about to fall); then x3 a capital, x2 an original capital of a major, a holy city of either founder's religion or a city with a
 *  world wonder, +1 another holy city (for us also a city with a national wonder). A city not in danger from the allies still counts as
 *  endangered when the enemy dominates a zone or besieges it (ours always; theirs when we have seen the city). Serious danger: ours,
 *  under siege and falling or more than half destroyed, for a city we founded, or our capital, an original capital, a holy city of
 *  either founder, or a city with a wonder; theirs, falling or under siege and falling or more than half destroyed; and having no cities.
 *
 *  Inputs: the war allies are [WarAlliesAi.warAllies] (no vassals, D3); war weariness (BNW has none) counts as none, so the war score
 *  stands as it is; the thresholds are [WarIntentAi.warScoreThresholdPositive] and [WarIntentAi.warScoreThresholdNegative]; the last
 *  city capture is [MajorCivMemory.lastCityCaptureTurn] (-1 = never: more than any number of turns ago). The tactical zones are those of
 *  the last tactical analysis ([com.unciv.logic.automation.unit.tactical.TacticalMap]); a side without one (a city-state) has no zone.
 */
object WarStateUpdateAi {

    /** One side of a war: its danger score, how many of its cities are endangered, and whether one of them is in serious danger */
    private class Side(val numCities: Int, val numInDanger: Int, val danger: Int, val serious: Boolean)

    private fun foundedReligion(civ: Civilization) = civ.religionManager.religion?.takeIf { it.foundingCivName == civ.civName }?.name

    private fun hasWorldWonder(city: City) = city.cityConstructions.getBuiltBuildings().any { it.isWonder }

    private fun hasNationalWonder(city: City) = city.cityConstructions.getBuiltBuildings().any { it.isNationalWonder }

    private fun isOriginalMajorCapital(city: City) = city.isOriginalCapital && city.foundingCivObject?.isMajorCiv == true

    /** CvPlayer::GetNumTurnsSinceCityCapture: turns since [civ] last took a city from [other] (a number no turn count reaches if never) */
    private fun turnsSinceCityCapture(civ: Civilization, other: Civilization): Int {
        val turn = civ.ai.diplomacy.memories[other.civID]?.lastCityCaptureTurn ?: -1
        return if (turn < 0) Int.MAX_VALUE else civ.gameInfo.turns - turn
    }

    /**
     *  The danger of [owner]'s cities from [enemies] (the other side and its war allies). [ownSide]: the side of the player whose war
     *  states these are ([us]), whose serious danger and wonders count differently and whose cities we always see.
     */
    private fun assess(owner: Civilization, enemies: List<Civilization>, us: Civilization, religions: List<String>, ownSide: Boolean): Side {
        var numInDanger = 0
        var danger = 0
        var serious = false
        val map = owner.ai.tactical.lastMap
        for (city in owner.cities) {
            val landEnemy = map?.zoneByCity(city, false)?.dominance == TacticalDominance.Enemy
            val waterEnemy = map?.zoneByCity(city, true)?.dominance == TacticalDominance.Enemy
            val keyHolyCity = religions.any { city.isHolyCityOf(it) }
            val underSiege = CityDangerAi.isUnderSiege(city)
            var mod = 0
            if (CityDangerAi.isInDangerFromPlayers(city, enemies)) {
                numInDanger++
                mod++
                if (landEnemy) mod++
                if (waterEnemy) mod++
                val falling = CityDangerAi.isInDangerOfFalling(city, extraCareful = true)
                if ((ownSide && underSiege) || (!ownSide && (underSiege || falling))) {
                    mod += if (falling) 3 else 1
                    if (falling || city.getMaxHealth() - city.health >= city.getMaxHealth() / 2) {
                        if (!ownSide) serious = true
                        else if (city.foundingCivObject == owner || city.isCapital() || isOriginalMajorCapital(city) || keyHolyCity ||
                            hasWorldWonder(city) || hasNationalWonder(city)) serious = true
                    }
                }
            } else if (ownSide || city.getCenterTile().isExplored(us)) {
                if (landEnemy || waterEnemy || underSiege) numInDanger++
            }
            if (mod > 0) mod = when {
                city.isCapital() -> mod * 3
                isOriginalMajorCapital(city) || keyHolyCity || hasWorldWonder(city) -> mod * 2
                city.isHolyCity() || (ownSide && hasNationalWonder(city)) -> mod + 1
                else -> mod
            }
            danger += mod
        }
        return Side(owner.cities.size, numInDanger, danger, serious || owner.cities.isEmpty())
    }

    /**
     *  The state of a war from the danger of both sides and the war score: serious danger decides first (ours alone: defensive; theirs
     *  alone: offensive above the positive threshold, else stalemate; both: stalemate between the thresholds), else a score of at least 75
     *  either way, else the danger compared after discounting the side with more safe cities, with the score splitting the cases.
     */
    private fun stateByDanger(ours: Side, theirs: Side, score: Int, positive: Int, negative: Int): WarState {
        var state: WarState? = when {
            ours.serious && !theirs.serious -> WarState.Defensive
            !ours.serious && theirs.serious -> if (score > positive) WarState.Offensive else WarState.Stalemate
            ours.serious && theirs.serious && score > negative && score < positive -> WarState.Stalemate
            else -> null
        }
        if (state == null) state = if (score <= -75) WarState.Defensive else if (score >= 75) WarState.Offensive else null
        return state ?: stateByComparedDanger(ours, theirs, score, positive, negative)
    }

    private fun stateByComparedDanger(ours: Side, theirs: Side, score: Int, positive: Int, negative: Int): WarState {
        // Adjust the danger levels by the safe cities each side has compared to the other
        val ourSafe = ours.numCities - ours.numInDanger
        val theirSafe = theirs.numCities - theirs.numInDanger
        var ourDanger = ours.danger
        var theirDanger = theirs.danger
        if (ourSafe > theirSafe) ourDanger = ourDanger * maxOf(maxOf(theirSafe, 1) * 100 / ourSafe, 34) / 100
        else if (theirSafe > ourSafe) theirDanger = theirDanger * maxOf(maxOf(ourSafe, 1) * 100 / theirSafe, 34) / 100
        val dangerValid = ours.numCities > 0 && theirs.numCities > 0 && (theirDanger > 0 || ourDanger > 0)
        val dangerPercent = theirDanger * 100 / maxOf(ourDanger, 1)
        if (dangerPercent < 100 && dangerValid) return when {
            score >= positive -> WarState.Stalemate
            score > 0 -> WarState.Troubled
            else -> WarState.Defensive
        }
        if (dangerPercent > 100 && dangerValid) return when {
            score <= negative -> WarState.Troubled
            score < 0 -> WarState.Stalemate
            else -> WarState.Offensive
        }
        val weAreNomadic = ours.numCities == 0 && theirs.numCities > 0
        val theyAreNomadic = theirs.numCities == 0 && ours.numCities > 0
        return when {
            score >= positive -> if (weAreNomadic) WarState.Stalemate else WarState.Offensive
            score <= negative || weAreNomadic -> if (weAreNomadic) WarState.Defensive else WarState.Troubled
            else -> if (theyAreNomadic) WarState.Offensive else WarState.Stalemate
        }
    }

    /** CvDiplomacyAI::DoUpdateWarStates for [civ]: [WarStateMemory.warStates] and [WarStateMemory.stateAllWars] */
    fun update(civ: Civilization, memory: WarStateMemory) {
        var stateAllWarsValue = 0
        var losing = false
        memory.warStates.clear()
        val myReligion = foundedReligion(civ)
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || !other.isAlive() || !civ.isAtWarWith(other)) continue
            val religions = listOfNotNull(myReligion, if (other.isCityState) null else foundedReligion(other))
            val ourAllies = WarAlliesAi.warAllies(civ, other, includeMinors = true, reverse = false) + civ
            val theirAllies = WarAlliesAi.warAllies(civ, other, includeMinors = true, reverse = true) + other
            val ours = assess(civ, theirAllies, civ, religions, ownSide = true)
            val theirs = assess(other, ourAllies, civ, religions, ownSide = false)

            val score = WarDamageAi.warScore(civ, other)
            val positive = WarIntentAi.warScoreThresholdPositive(civ)
            val negative = WarIntentAi.warScoreThresholdNegative(civ)
            var state = stateByDanger(ours, theirs, score, positive, negative)
            // If winning and the war score high enough, or losing and the war score low enough, the war state intensifies
            if (score >= positive * 2 && state == WarState.Offensive) state = WarState.NearlyWon
            else if (score <= negative * 2 && state == WarState.Defensive) state = WarState.NearlyDefeated
            // A low war score, no serious danger and no city taken for a while: calm
            if (!theirs.serious && !ours.serious && score <= positive && score >= negative &&
                turnsSinceCityCapture(civ, other) >= 10 && turnsSinceCityCapture(other, civ) >= 10) state = WarState.Calm

            if (other.isMajorCiv) when (state) {
                WarState.NearlyWon -> stateAllWarsValue += 4
                WarState.Offensive -> stateAllWarsValue += if (theirs.serious && !ours.serious) 4 else 2
                WarState.Troubled -> stateAllWarsValue -= if (ours.serious) 2 else 1
                WarState.Defensive -> {
                    stateAllWarsValue -= if (ours.serious) 4 else 2
                    // Defensive in any war with no capital, or one damaged to 75% or less: losing overall
                    val capital = civ.getCapital(firstCityIfNoCapital = false)
                    if (capital == null || capital.getMaxHealth() - capital.health >= capital.getMaxHealth() / 4) losing = true
                }
                WarState.NearlyDefeated -> losing = true
                else -> {}
            }
            memory.warStates[other.civID] = state
        }
        memory.stateAllWars = when {
            stateAllWarsValue < -2 || losing -> StateAllWars.Losing
            stateAllWarsValue > 2 -> StateAllWars.Winning
            else -> StateAllWars.Neutral
        }
    }
}
