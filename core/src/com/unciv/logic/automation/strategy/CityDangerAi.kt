package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  Vox Populi's tests of a city's danger (CvCity.cpp, Release-5.4.8; D7), on the damage it took ([City.damageTakenThisTurn],
 *  [City.damageTakenLastTurn]) and the units around it. Our plot danger ([com.unciv.logic.automation.unit.PlotDanger]) is BNW's form
 *  (strength over turns away), not VP's expected damage: it serves only VP's "no danger at all" filter, and isInDangerOfFalling's clause
 *  for a city taken within two turns (twice the danger above its hit points) waits for VP's CvDangerPlots.
 */
object CityDangerAi {

    /** CvCity::isInDangerOfFalling: last turn's damage (and a third more if [extraCareful]) is more than the hit points left */
    fun isInDangerOfFalling(city: City, extraCareful: Boolean): Boolean {
        val extra = if (extraCareful) city.damageTakenLastTurn / 3 else 0
        return city.damageTakenLastTurn + extra > city.health
    }

    /**
     *  CvCity::IsInDangerFromPlayers: [city] is in danger from [enemies] when it may fall ([isInDangerOfFalling], careful); else, if it
     *  took damage this turn or last or sees any danger, when an enemy nuclear weapon is within 4 tiles or the power of the enemies'
     *  military units 1 to 4 tiles away beats the city's power and its own units', by domain: land (and sea) power +10% with a general (an
     *  admiral) of that side nearby. VP's discount of a nuke likely to be intercepted has no input here.
     */
    fun isInDangerFromPlayers(city: City, enemies: Collection<Civilization>): Boolean {
        if (enemies.isEmpty()) return false
        if (isInDangerOfFalling(city, extraCareful = true)) return true
        val owner = city.civ
        if (city.damageTakenThisTurn == 0 && city.damageTakenLastTurn == 0 && owner.ai.plotDanger.get(city.getCenterTile()) == 0)
            return false
        var ownLand = WarStateAi.cityPower(city)
        var ownSea = 0
        var ownOther = 0
        var ownGeneral = false
        var ownAdmiral = false
        val enemyLand = HashMap<Civilization, Int>()
        val enemySea = HashMap<Civilization, Int>()
        var enemyOther = 0
        val enemyGenerals = HashSet<Civilization>()
        val enemyAdmirals = HashSet<Civilization>()
        for (tile in city.getCenterTile().getTilesInDistanceRange(1..4)) for (unit in tile.getUnits()) {
            val ours = unit.civ == owner
            if (!ours && unit.civ !in enemies) continue
            if (unit.isNuclearWeapon()) {
                if (!ours) return true
                continue
            }
            if (unit.hasUnique(UniqueType.StrengthBonusInRadius)) {
                if (unit.baseUnit.isWaterUnit) { if (ours) ownAdmiral = true else enemyAdmirals.add(unit.civ) }
                else { if (ours) ownGeneral = true else enemyGenerals.add(unit.civ) }
            }
            if (!unit.isMilitary()) continue
            val power = unit.baseUnit.getAiPower()
            when {
                unit.baseUnit.isAirUnit() -> if (ours) ownOther += power else enemyOther += power
                unit.baseUnit.isWaterUnit -> if (ours) ownSea += power else enemySea[unit.civ] = (enemySea[unit.civ] ?: 0) + power
                else -> if (ours) ownLand += power else enemyLand[unit.civ] = (enemyLand[unit.civ] ?: 0) + power
            }
        }
        if (ownGeneral) ownLand = ownLand * 11 / 10
        if (ownAdmiral) ownSea = ownSea * 11 / 10
        var enemyPower = enemyOther
        for ((enemy, power) in enemyLand) enemyPower += if (enemy in enemyGenerals) power * 11 / 10 else power
        for ((enemy, power) in enemySea) enemyPower += if (enemy in enemyAdmirals) power * 11 / 10 else power
        return enemyPower > ownLand + ownSea + ownOther
    }

    /** CvDiplomacyAI::GetNumberOfThreatenedCities: at war with [other], our cities in danger from them and their war allies */
    fun numberOfThreatenedCities(civ: Civilization, other: Civilization): Int {
        if (!civ.isAtWarWith(other)) return 0
        val enemies = WarAlliesAi.offensiveWarAllies(civ, other, includeMinors = true, reverse = true) + other
        return civ.cities.count { isInDangerFromPlayers(it, enemies) }
    }
}
