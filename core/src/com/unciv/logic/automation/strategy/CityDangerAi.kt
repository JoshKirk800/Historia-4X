package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.unit.tactical.TacticalDominance
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

    /** CvCity::isUnderSiege: more than 5 damage taken last turn and a tile of the city blockaded (by Unciv's rule, as VP asks its own) */
    fun isUnderSiege(city: City): Boolean = city.damageTakenLastTurn > 5 && city.getTiles().any { it.isBlockaded() }

    /** What [comparativeDanger] finds: each side's danger score, whether either side has a city in serious danger, and the cities of
     *  the enemy endangered by our side (more than barely) and by us alone */
    class ComparativeDanger(
        val ours: Int, val theirs: Int, val seriousUs: Boolean, val seriousThem: Boolean,
        val theirCitiesEndangered: List<City>, val theirCitiesEndangeredByUs: List<City>,
    )

    /**
     *  CvDiplomacyAI::GetComparativeDanger, [civ] at war with [other]: for each of our cities in danger from [theirAllies], 1, +1 for each
     *  dominance zone of the city (land, water) the enemy dominates in our last tactical map, +1 under siege (+3 if also falling); a
     *  falling or half-destroyed city we founded, or a capital, original capital, holy city of ours or theirs, or wonder city, is serious
     *  danger; then x3 a capital, x2 an original capital or the holy city of ours or theirs, +1 another holy city or a national wonder
     *  city. Their cities in danger from [ourAllies] alike, in their tactical map (no national wonder term), noting those endangered
     *  more than barely and those in danger from us alone. VP's IsExposedToEnemy and IsPreferredAttackTarget terms (CvMilitaryAI) have
     *  no input here.
     */
    fun comparativeDanger(civ: Civilization, other: Civilization, ourAllies: List<Civilization>, theirAllies: List<Civilization>): ComparativeDanger {
        val ourReligion = civ.religionManager.religion?.takeIf { it.foundingCivName == civ.civName }?.name
        val theirReligion = other.religionManager.religion?.takeIf { it.foundingCivName == other.civName }?.name
        fun keyHolyCity(city: City) = (ourReligion != null && city.isHolyCityOf(ourReligion)) || (theirReligion != null && city.isHolyCityOf(theirReligion))
        fun originalMajorCapital(city: City) = city.isOriginalCapital && city.foundingCivObject?.isMajorCiv == true
        fun baseMod(city: City): Int {
            var mod = 1
            val map = city.civ.ai.tactical.lastMap
            if (map?.zoneByCity(city, false)?.dominance == TacticalDominance.Enemy) mod++
            if (map?.zoneByCity(city, true)?.dominance == TacticalDominance.Enemy) mod++
            if (isUnderSiege(city)) mod += if (isInDangerOfFalling(city, extraCareful = true)) 3 else 1
            return mod
        }
        fun badlyHurt(city: City) = isInDangerOfFalling(city, extraCareful = true) || city.health <= city.getMaxHealth() / 2
        var ours = 0
        var seriousUs = false
        for (city in civ.cities) {
            if (!isInDangerFromPlayers(city, theirAllies)) continue
            var mod = baseMod(city)
            if (badlyHurt(city) && (city.foundingCivObject == civ || city.isCapital() || originalMajorCapital(city) || keyHolyCity(city)
                    || city.cityConstructions.getBuiltBuildings().any { it.isAnyWonder() })) seriousUs = true
            mod = when {
                city.isCapital() -> mod * 3
                originalMajorCapital(city) || keyHolyCity(city) -> mod * 2
                city.isHolyCity() || city.cityConstructions.getBuiltBuildings().any { it.isNationalWonder } -> mod + 1
                else -> mod
            }
            ours += mod
        }
        var theirs = 0
        var seriousThem = false
        val endangered = ArrayList<City>()
        val endangeredByUs = ArrayList<City>()
        for (city in other.cities) {
            if (!isInDangerFromPlayers(city, ourAllies)) continue
            var mod = baseMod(city)
            if (badlyHurt(city)) seriousThem = true
            if (mod > 1) endangered.add(city)
            if (isInDangerFromPlayers(city, listOf(civ))) endangeredByUs.add(city)
            mod = when {
                city.isCapital() -> mod * 3
                originalMajorCapital(city) || keyHolyCity(city) -> mod * 2
                city.isHolyCity() -> mod + 1
                else -> mod
            }
            theirs += mod
        }
        return ComparativeDanger(ours, theirs, seriousUs, seriousThem, endangered, endangeredByUs)
    }

    /** CvDiplomacyAI::GetNumberOfThreatenedCities: at war with [other], our cities in danger from them and their war allies */
    fun numberOfThreatenedCities(civ: Civilization, other: Civilization): Int {
        if (!civ.isAtWarWith(other)) return 0
        val enemies = WarAlliesAi.warAllies(civ, other, includeMinors = true, reverse = true) + other
        return civ.cities.count { isInDangerFromPlayers(it, enemies) }
    }
}
