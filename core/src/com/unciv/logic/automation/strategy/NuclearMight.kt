package com.unciv.logic.automation.strategy

import com.unciv.logic.battle.Nuke
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

/**
 *  The military weight of a civilization's nuclear weapons, after Vox Populi's CvPlayer::calculateNuclearMight (Release-5.4.8; roadmap D7,
 *  `phaseE/vp-conquest-endgame-plan.md` step 3). BNW's might counts a nuclear weapon as any other unit (its power); VP counts what it
 *  would destroy: per weapon, the strongest land-or-sea unit 3 times and the strongest air unit 12 times (one land and four air units on
 *  the target tile), plus the strongest land-or-sea unit 2 times (damage level 1) or 3 times (level 2 and up) for each other tile in the
 *  blast. Both this and conventional might use [attackStrength] so their scales agree.
 */
object NuclearMight {

    /**
     * Approximation of CvUnit::GetBestAttackStrength: use the larger base attack and VP's normal wounded penalty.
     * Unlike VP's quick combat calculation this omits contextual and promotion-specific combat modifiers.
     */
    @Readonly
    internal fun attackStrength(unit: MapUnit): Int {
        val strength = maxOf(unit.baseUnit.strength, unit.baseUnit.rangedStrength)
        val woundedPenalty = (unit.getMaxHealth() - unit.health) * 33 / 100
        return strength * (100 - woundedPenalty) / 100
    }

    /**
     *  The nuclear might of [civ]: against [against]'s units and shelters, or, when [against] is null, against the strongest units of all
     *  other living majors and without shelters (the global might, part of [CivAi.militaryMight]). 0 without nuclear weapons.
     */
    @Readonly
    fun of(civ: Civilization, against: Civilization?): Int {
        val nukes = civ.units.getCivUnits().filter { it.isNuclearWeapon() }.toList()
        if (nukes.isEmpty()) return 0
        val targets = if (against != null) sequenceOf(against)
            else civ.gameInfo.civilizations.asSequence().filter { it != civ && it.isMajorCiv && it.isAlive() }
        var highestLandOrSea = 0
        var highestAir = 0
        for (unit in targets.flatMap { it.units.getCivUnits() }) {
            if (unit.isNuclearWeapon() || unit.isCivilian()) continue
            val power = attackStrength(unit)
            if (unit.baseUnit.isAirUnit()) highestAir = maxOf(highestAir, power)
            else highestLandOrSea = maxOf(highestLandOrSea, power)
        }
        val shelters = if (against != null) Shelters.of(against) else Shelters.NONE
        return nukes.sumOf { weaponMight(it, highestLandOrSea, highestAir, shelters) }
    }

    /**
     *  How well a civilization's cities are protected against nuclear attacks, as calculateNuclearMight counts it: [percent] of the cities
     *  (a capital counts twice) have a protection, with an average [nukeModifier] (the percent of population loss prevented: 75 for a Bomb
     *  Shelter) and [interceptionChance] (no BNW building intercepts nuclear weapons, so 0). Departure, recorded: VP adds each city's
     *  modifier once but divides by the weighted count, so a sheltered capital alone averages half its modifier; here the sum is weighted too.
     */
    data class Shelters(val percent: Int, val nukeModifier: Int, val interceptionChance: Int) {
        companion object {
            val NONE = Shelters(0, 0, 0)

            @Readonly
            fun of(civ: Civilization): Shelters {
                var cities = 0
                var sheltered = 0
                var modifier = 0
                for (city in civ.cities) {
                    val weight = if (city.isCapital()) 2 else 1
                    cities += weight
                    val cityModifier = ((1f - Nuke.populationLossModifier(city)) * 100).roundToInt()
                    if (cityModifier > 0) {
                        sheltered += weight
                        modifier += cityModifier * weight
                    }
                }
                if (cities == 0) return NONE
                return Shelters(sheltered * 100 / cities, (modifier / maxOf(sheltered, 1)).coerceIn(0, 100), 0)
            }
        }
    }

    /** One weapon's share of calculateNuclearMight */
    @Readonly
    private fun weaponMight(nuke: MapUnit, highestLandOrSea: Int, highestAir: Int, shelters: Shelters): Int {
        val damageLevel = nuke.getMatchingUniques(UniqueType.NuclearWeapon).firstOrNull()?.params?.get(0)?.toIntOrNull() ?: 0
        if (damageLevel < 1) return 0
        val radius = nuke.getNukeBlastRadius().coerceIn(1, 5)
        val blastTiles = 3 * radius * (radius + 1)   // RING_PLOTS[radius] - 1: the tiles around the target
        var basePower = highestLandOrSea * 3 + highestAir * 12
        val (percent, nukeModifier, interception) = shelters
        if (percent != 0 && nukeModifier != 0)
            basePower = basePower * (100 - percent * nukeModifier / 100) / 100
        var blastPower = highestLandOrSea * blastTiles * (if (damageLevel == 1) 2 else 3)
        if (percent == 0) return basePower + blastPower
        val popFactorReduction = (nukeModifier * 100 / 75) * 17 / 100
        return if (damageLevel == 1) {
            val total = (basePower + blastPower) * (100 - percent * interception / 100) / 100
            total * (100 - percent * popFactorReduction / 100) / 100
        } else {
            blastPower = blastPower * (100 - percent * (34 * interception / 100) / 100) / 100
            (basePower + blastPower) * (100 - percent * popFactorReduction / 100) / 100
        }
    }
}
