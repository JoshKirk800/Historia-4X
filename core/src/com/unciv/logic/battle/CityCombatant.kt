package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.logic.MultiFilter
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.math.pow
import kotlin.math.roundToInt

class CityCombatant(val city: City, private val ignoreGarrison: Boolean = false) : ICombatant {
    override fun getMaxHealth(): Int {
        return city.getMaxHealth()
    }
    override fun getHealth(): Int = city.health
    @Readonly override fun getCivInfo(): Civilization = city.civ
    override fun getTile(): Tile = city.getCenterTile()
    override fun getName(): String = city.name
    @Readonly override fun isDefeated(): Boolean = city.health == 1
    override fun isVisibleTo(to: Civilization): Boolean = true
    override fun canAttack(): Boolean = city.canBombard()
    override fun matchesFilter(filter: String, multiFilter: Boolean) = 
        if (multiFilter) MultiFilter.multiFilter(filter, { it == "City" || it in Constants.all || city.matchesFilter(it, multiFilter = false) })
    else filter == "City" || filter in Constants.all || city.matchesFilter(filter, multiFilter = false)
    override fun getAttackSound() = UncivSound.Bombard

    override fun takeDamage(damage: Int) {
        city.health -= damage
        city.damageTakenThisTurn += damage  // CvCity::addDamageReceivedThisTurn (Vox Populi)
        if (city.health < 1) city.health = 1  // min health is 1
    }

    override fun getUnitType(): UnitType = UnitType.City
    override fun getAttackingStrength(defender: ICombatant?): Int {
        val strength = getCityStrength(defender, CombatAction.Attack, forRangedStrike = true)
        return if (city.civ.gameInfo.ruleset.modOptions.constants.cityCombatLikeVp) strength
        else (strength * 0.75).roundToInt()
    }
    @Readonly override fun getDefendingStrength(attacker: ICombatant?): Int = getCityStrength(attacker)

    @Readonly
    fun getCityStrength(theirCombatant: ICombatant? = null, combatAction: CombatAction = CombatAction.Defend, forRangedStrike: Boolean = false): Int { // Civ fanatics forum, from a modder who went through the original code
        val modConstants = getCivInfo().gameInfo.ruleset.modOptions.constants
        if (modConstants.cityCombatLikeVp) return getVpCityStrength(theirCombatant, combatAction, forRangedStrike)
        var strength = modConstants.cityStrengthBase
        strength += (city.population.population * modConstants.cityStrengthPerPop) // Each 5 pop gives 2 defence
        val cityTile = city.getCenterTile()
        // Terrain bonus (hills) is added after the player-wide multiplier below, CvCity.cpp:10386-10395
        var terrainStrength = 0
        for (terrain in cityTile.allTerrains)
            terrain.forEachMatchingUnique(UniqueType.GrantsCityStrength, GameContext.EmptyState) { unique ->
                terrainStrength += unique.params[0].toInt()
            }
        // as tech progresses so does city strength
        val techCount = getCivInfo().gameInfo.ruleset.technologies.size
        val techsPercentKnown: Float = if (techCount > 0) city.civ.tech.techsResearched.size.toFloat() / techCount else 0.5f // for mods with no tech
        strength += (techsPercentKnown * modConstants.cityStrengthFromTechsMultiplier).pow(modConstants.cityStrengthFromTechsExponent) * modConstants.cityStrengthFromTechsFullMultiplier

        // The way all of this adds up...
        // All ancient techs - 0.5 extra, Classical - 2.7, Medieval - 8, Renaissance - 17.5,
        // Industrial - 32.4, Modern - 51, Atomic - 72.5, All - 118.3

        // Garrisoned unit gives up to 20% of strength to city, health-dependant
        if (cityTile.militaryUnit != null)
            strength += cityTile.militaryUnit!!.baseUnit.strength * (cityTile.militaryUnit!!.health / cityTile.militaryUnit!!.getMaxHealth().toFloat()) * modConstants.cityStrengthFromGarrison

        val buildingsDefense = city.getStrength()
        var buildingsStrength = buildingsDefense
        val gameContext = GameContext(getCivInfo(), city, ourCombatant = this, theirCombatant = theirCombatant, combatAction = combatAction)

        getCivInfo().forEachMatchingUnique(UniqueType.BetterDefensiveBuildings, gameContext) { unique ->
            buildingsStrength *= unique.params[0].toPercent()
        }
        strength += buildingsStrength

        // Player-wide multiplier on base + population + techs + garrison + buildings, CvCity.cpp:10383-10387 (Their Finest Hour)
        getCivInfo().forEachMatchingUnique(UniqueType.CityStrengthMultiplier, gameContext) { unique ->
            strength *= unique.params[0].toPercent()
        }
        // CvCity::getStrengthValue(bForRangeStrike): "Strike strikes are weaker" - the defense of the buildings is taken out, what a
        // policy adds to it stays
        if (forRangedStrike) strength -= buildingsDefense
        strength += terrainStrength

        var extraStrength = 0
        city.forEachMatchingUnique(UniqueType.StrengthAmount, gameContext) { extraStrength += it.params[0].toInt() }
        strength += extraStrength

        return strength.roundToInt()
    }

    /** VP CvCity::updateStrengthValue and getStrengthValue: ranged retains buildings but excludes garrison. */
    @Readonly
    private fun getVpCityStrength(other: ICombatant?, action: CombatAction, ranged: Boolean): Int {
        val values = city.civ.gameInfo.ruleset.modOptions.constants.cityCombat
        val context = GameContext(city.civ, city, ourCombatant = this, theirCombatant = other, combatAction = action)
        var buildings = (city.getStrength() * 100).toInt()
        city.civ.forEachMatchingUnique(UniqueType.BetterDefensiveBuildings, context) {
            buildings = (buildings * it.params[0].toPercent()).toInt()
        }
        val processStrength = city.getDefenseProcessStrength()
        var strength = values.baseStrengthHundredths + buildings + processStrength
        val wonders = city.cityConstructions.getBuiltBuildings().count { it.isWonder }
        city.forEachMatchingUnique(UniqueType.CityDefensePerWonder, context, includeCivUniques = false) {
            val divisor = it.params[1].toInt()
            if (divisor > 0) strength += it.params[0].toInt() * wonders * 100 / divisor
        }
        val airUnits = city.getCenterTile().airUnits.count { it.civ == city.civ && !it.hasUnique(UniqueType.SelfDestructs) }
        city.civ.forEachMatchingUnique(UniqueType.CityDefensePerAirUnit, context) { strength += it.params[0].toInt() * airUnits * 100 }
        if (city.civ.isCityState) strength += city.population.population * values.minorStrengthPerPopulationHundredths
        city.civ.forEachMatchingUnique(UniqueType.CityStrengthMultiplier, context) {
            strength = (strength * it.params[0].toPercent()).toInt()
        }
        if (city.civ.isCityState && city.isCapital()) {
            city.civ.allyCiv?.let { ally ->
                var allyBonus = values.minorAllyDefensePercent
                ally.forEachMatchingUnique(UniqueType.AlliedCityStateDefenseModifier) { allyBonus += it.params[0].toInt() }
                if (allyBonus > 0) strength = strength * (100 + allyBonus) / 100
            }
            val protection = (city.civ.cityStateFunctions.getProtectorCivs().size * values.minorProtectionDefensePercent)
                .coerceAtMost(values.minorProtectionDefenseMaxPercent)
            strength = strength * (100 + protection) / 100
        }
        val tile = city.getCenterTile()
        if (tile.isHill()) strength += values.hillStrengthHundredths
        if (tile.getBaseTerrain().isMountain) strength += values.hillStrengthHundredths * 2
        // VP adds the hill-sized bonus when a positive general/admiral area effect reaches the city.
        if (city.civ.units.getCivUnits().any { unit ->
                unit.cache.hasStrengthBonusInRadiusUnique && unit.getMatchingUniques(UniqueType.StrengthBonusInRadius, context).any {
                    it.params[0].toInt() > 0 && unit.getTile().aerialDistanceTo(tile) <= it.params[2].toInt()
                }
            }) strength += values.hillStrengthHundredths
        if (!ranged && !ignoreGarrison) city.getGarrison()?.let { strength += vpGarrisonStrength(it) }
        city.forEachMatchingUnique(UniqueType.StrengthAmount, context) { strength += it.params[0].toInt() * 100 }
        if (!ranged) {
            var adjacentModifier = 0
            for (nearby in tile.getTilesInDistance(1)) for (unit in nearby.getUnits()) {
                if (unit.civ != city.civ) continue
                unit.forEachMatchingUnique(UniqueType.AdjacentCityDefenseModifier, context) { adjacentModifier += it.params[0].toInt() }
            }
            if (adjacentModifier > 0) strength = strength * (100 + adjacentModifier) / 100
        }
        if (ranged) {
            strength -= processStrength
            var modifier = values.rangedStrengthModifier
            city.forEachMatchingUnique(UniqueType.CityRangeStrikeModifier, context) { modifier += it.params[0].toInt() }
            if (!ignoreGarrison && city.getGarrison() != null)
                city.forEachMatchingUnique(UniqueType.GarrisonedCityRangeStrikeModifier, context) { modifier += it.params[0].toInt() }
            if (other is MapUnitCombatant) {
                val previousAttacks = other.unit.getTimesAttackedBy(city.civ.civID)
                city.civ.forEachMatchingUnique(UniqueType.MultipleAttackCityStrikeModifier, context) {
                    modifier += it.params[0].toInt() * previousAttacks
                }
            }
            strength = strength * (100 + modifier) / 100
        }
        return (strength / 100f).roundToInt()
    }

    /** VP CvCity::processBuilding and getDamageReductionFlat: building reductions apply before garrison absorption. */
    @Readonly
    fun getFlatDamageReduction(): Int {
        if (!city.civ.gameInfo.ruleset.modOptions.constants.cityCombatLikeVp) return 0
        var reduction = 0
        city.forEachMatchingUnique(UniqueType.CityFlatDamageReduction, city.state, includeCivUniques = false) {
            reduction += it.params[0].toInt()
        }
        return reduction
    }

    /** Garrison contribution in hundredths of strength, independent of current HP. */
    @Readonly
    fun vpGarrisonStrength(unit: com.unciv.logic.map.mapunit.MapUnit): Int {
        val values = city.civ.gameInfo.ruleset.modOptions.constants.cityCombat
        val divisor = if (unit.baseUnit.isLandUnit) values.landGarrisonDivisor else values.navalGarrisonDivisor
        return (maxOf(unit.baseUnit.strength, unit.baseUnit.rangedStrength) * 100 / divisor) * 100
    }

    @Readonly
    override fun getTriggeredUniques(
        trigger: UniqueType,
        gameContext: GameContext,
        triggerFilter: (Unique) -> Boolean
    ): Sequence<Unique> {
        return city.getTriggeredUniques(trigger, gameContext, triggerFilter)
    }

    override fun toString() = city.name // for debug
}
