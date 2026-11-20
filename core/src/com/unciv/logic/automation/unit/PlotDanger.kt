package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.unit.tactical.SimCombatant
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.battle.BattleConstants
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.TileView

/** CvDangerPlotContents' AirActionType: what an air unit does at the plot whose danger is asked */
enum class AirAction { Attack, Sweep, Intercept }

/**
 * The danger [civ] sees on the map: Vox Populi's CvDangerPlots (Release-5.4.8; CvPlayer::GetPlotDanger, CvUnit::GetDanger) with its
 * CvDangerPlotContents, built once per civilization and turn ([com.unciv.logic.automation.strategy.CivAi.plotDanger]). Danger is expected
 * damage in hit points, not a strength: the BNW sum of strengths over turns away is gone.
 *
 * Contents of a plot (worked out when the plot is first asked, from the sources found at construction): the enemy units that can attack it
 * (UpdateDangerSingleUnit: [canThreaten]), the enemy units that can walk into it and capture a civilian there (m_apCaptureUnits), the enemy cities
 * that bombard it (ring 1 to the bombard range), the fog count (AddFogDanger: each unit of an enemy, each city of one in its own land and each
 * barbarian camp we cannot see makes the hidden plots around it a source of one point of damage for the plots near those) and the damage of an
 * enemy citadel next to it (twice the improvement's damage, UpdateDangerInternal) or of terrain.
 *
 * Sources (ShouldIgnorePlayer / ShouldIgnoreUnit): the military units able to attack of the civilizations at war with [civ] that [civ] sees,
 * nuclear weapons left out; an AI civilization also counts a unit it cannot see in a revealed city or on a barbarian camp, and, from
 * [previouslyKnown] (the known units of the last map), a unit that was in sight then and has moved out of it since (CvDangerPlots::m_vanishedUnits),
 * wherever it really is now. Peace, friendship with a city-state and city-states at peace are all "not at war": those civilizations make no danger
 * (the BNW approach scaling, AI_DANGER_*, is gone).
 *
 * What is asked: [get] for a tile (CvDangerPlotContents::GetDanger(bFixedDamageOnly): the damage of a unit of the strength of each attacker,
 * plus 20 for terrain), for a unit on a tile (GetDanger(unit): a civilian is captured, [Int.MAX_VALUE], where an enemy unit stands, an enemy
 * can walk in or our city is about to fall; air units take the damage of interceptions, [airUnitDamage]; else the expected damage of every
 * attacker, 4/5 of it where the attacker's civilization does not see the tile) and for a city (GetDanger(city)), and [possibleAttackers]
 * (GetPossibleAttackers).
 *
 * Deviations: the plots a unit threatens are those within its movement and range ([canThreaten]), terrain and ZOC not considered, where the
 * original asks the path finder for the plots it reaches this turn (the second pass of COMBATAI_TWO_PASS_DANGER, which is off in the Vox
 * Populi data, only changes the ZOC of units that would die; with no ZOC here it has no counterpart); an air unit threatens the plots within
 * its range of its own base; a unit that cannot fight, and city line of sight, are not looked at; a ranged unit's fire against a unit is
 * [BattleDamage] at the mean of the randomness, with the attacker on its own plot when the target is within its reach and on the target's
 * otherwise; the damage dealt to the city by the attackers before is not added to the next (in this ruleset a city's garrison takes no damage);
 * a unit seen in the fog and the AoE damage of moving units (promotions Unciv does not have), deep water damage and the units that arrive
 * after the map was made (AddKnownAttacker) are not modelled; the plot of a tile's danger for a unit of unknown strength uses the tile's terrain
 * for the defender, where the original uses the attacker's base strength; the player's wounded-unit modifier is applied to strengths only.
 */
class PlotDanger(private val civ: Civilization, previouslyKnown: Set<Int> = emptySet()) {
    private val rules = civ.gameInfo.ruleset.ai
    private val tileMap = civ.gameInfo.tileMap
    private val sourceUnits = ArrayList<MapUnit>()
    private val sourceCities = ArrayList<City>()
    /** The units that were in sight (or counted as in a city or camp) when this map was made, CvDangerPlots::m_knownUnits: the next turn's [previouslyKnown] */
    val knownUnits: Set<Int>
    private val enemyHealRate = rules.define(AiDefine.ENEMY_HEAL_RATE)

    private class Contents(val units: List<MapUnit>, val capturers: List<MapUnit>, val cities: List<City>)

    private val contents = arrayOfNulls<Contents>(tileMap.tileList.size)
    private val improvementDamage = IntArray(tileMap.tileList.size) { NOT_COMPUTED }
    private val fog: IntArray by lazy { fogCounts() }

    init {
        val known = HashSet<Int>()
        val others = civ.gameInfo.civilizations.filter { it != civ && it.isAlive() && civ.isAtWarWith(it) }
        for (other in others) {
            for (unit in other.units.getCivUnits()) {
                if (!canAttack(unit) || !isSeen(unit, ignoreVisibility = false)) continue
                sourceUnits.add(unit)
                known.add(unit.id)
            }
            for (city in other.cities) if (isRevealed(city)) sourceCities.add(city)
        }
        // UpdateDangerInternal: units that were in sight the last time and are not now, and are still around, count where they are
        if (previouslyKnown.isNotEmpty()) for (other in others) for (unit in other.units.getCivUnits())
            if (unit.id in previouslyKnown && unit.id !in known && canAttack(unit) && isSeen(unit, ignoreVisibility = true)) sourceUnits.add(unit)
        knownUnits = known
    }

    /** CvDangerPlots::ShouldIgnoreUnit without the visibility: the unit can attack, as a combat unit that is not a nuclear weapon */
    private fun canAttack(unit: MapUnit) = unit.isMilitary() && (unit.baseUnit.isMelee() || unit.baseUnit.isRanged()) && !unit.isNuclearWeapon()

    /** ShouldIgnoreUnit's visibility: an AI civilization also counts what it has revealed in a city or a barbarian camp, a [ignoreVisibility] unit must only not be hidden by invisibility */
    private fun isSeen(unit: MapUnit, ignoreVisibility: Boolean): Boolean {
        val tile = unit.getTile()
        val counted = ignoreVisibility || (!civ.isHuman() && tile.isExplored(civ) && (tile.isCityCenter() || tile.improvement == Constants.barbarianEncampment))
        return if (counted) !unit.hasActiveInvisibilityUnique(civ) else unit.isVisibleTo(civ)
    }

    /** CvDangerPlots::ShouldIgnoreCity: nothing is known of a city when neither its plot nor a plot next to it is revealed */
    private fun isRevealed(city: City) = city.getCenterTile().let { it.isExplored(civ) || it.neighbors.any { n -> n.isExplored(civ) } }

    //region Asking

    /**
     * CvDangerPlotContents::GetDanger(bFixedDamageOnly): the damage a unit of unknown type would take on [tile] next turn: that of the
     * improvements and the terrain (+20), and unless [fixedDamageOnly] that of each attacker against a unit as strong as itself.
     */
    fun get(tile: Tile, fixedDamageOnly: Boolean = false): Int {
        var damage = improvementDamageAt(tile)
        if (hasTerrainDamage(tile)) damage += UNKNOWN_UNIT_TERRAIN_DAMAGE
        if (fixedDamageOnly) return damage
        for (attacker in contentsOf(tile).units) {
            if (attacker.isDestroyed) continue
            val from = attackFrom(attacker, tile)
            val equal = SimCombatant(attacker, tile, MAX_HEALTH)
            damage += BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), equal, from, EXPECTED_RANDOMNESS, ignoreFlanking = true)
        }
        return damage
    }

    /** [get] for view-based unit code */
    fun get(tile: TileView): Int = get(tileMap[tile.position()])

    /**
     * CvUnit::GetDanger(plot) (CvDangerPlotContents::GetDanger(unit)): the damage [unit] would take on [tile] (where it stands by default) from
     * the enemies. A civilian can be captured, [Int.MAX_VALUE], where an enemy combat unit stands or one can walk in, and in a city of ours that
     * can fall; in our other cities it takes nothing; else the damage of the attackers (40 each), the terrain, the citadels, the fog and the cities.
     * A combat unit in a city of ours takes its share of the city's damage ([get] for a city) if the city does not fall, else [Int.MAX_VALUE].
     */
    fun get(unit: MapUnit, tile: Tile = unit.getTile()): Int {
        if (unit.baseUnit.isAirUnit()) return airUnitDamage(unit, tile, AirAction.Attack)
        val cityHere = friendlyCity(tile)
        if (unit.isCivilian()) return civilianDanger(unit, tile, cityHere)
        if (cityHere != null) return garrisonDanger(unit, tile, cityHere)

        val content = contentsOf(tile)
        val defender = SimCombatant(unit, tile, unit.health.coerceAtLeast(1))
        var sum = 0
        for (attacker in content.units) {
            if (attacker.isDestroyed || attacker.getTile() == tile) continue
            val from = attackFrom(attacker, tile)
            val attackerCombatant = SimCombatant(attacker, from, attacker.health.coerceAtLeast(1))
            var damage = BattleDamage.calculateDamageToDefender(attackerCombatant, defender, from, EXPECTED_RANDOMNESS)
            // A suicide attack with a unit that does not do suicide attacks is unlikely
            val attackerSelf = if (attacker.baseUnit.isAirUnit()) 0 else BattleDamage.calculateDamageToAttacker(attackerCombatant, defender, from, EXPECTED_RANDOMNESS)
            if (attackerSelf >= attacker.health && !attacker.hasUnique(UniqueType.SelfDestructs)) damage /= 2
            sum += unseenDiscount(attacker, tile, damage)
        }
        for (city in content.cities) {
            // If we cannot see a potential garrison, assume there is one
            var damage = BattleDamage.calculateDamageToDefender(CityCombatant(city), defender, city.getCenterTile(), EXPECTED_RANDOMNESS)
            if (!city.getCenterTile().isVisible(civ)) damage *= 2
            sum += damage
        }
        return sum + fog[tile.zeroBasedIndex] * FOG_DEFAULT_DANGER + improvementDamageAt(tile) + terrainDamage(unit, tile)
    }

    /**
     * CvPlayer::GetPlotDanger(city): the damage [city] would take next turn: the damage of every attacker that can reach its plot, melee and ranged
     * at the mean of the randomness (the flanking of its neighbors left out), the air units' less what our interceptors do to them first.
     */
    fun get(city: City): Int {
        val tile = city.getCenterTile()
        val defender = CityCombatant(city)
        var sum = 0
        for (attacker in contentsOf(tile).units) {
            if (attacker.isDestroyed) continue
            val from = attackFrom(attacker, tile)
            var health = attacker.health.coerceAtLeast(1)
            if (attacker.baseUnit.isAirUnit()) health -= interceptionDamage(bestInterceptor(attacker, tile), attacker, attacker.getTile())
            if (health <= 0) continue
            sum += BattleDamage.calculateDamageToDefender(SimCombatant(attacker, from, health), defender, from, EXPECTED_RANDOMNESS, ignoreFlanking = true)
        }
        return sum
    }

    /**
     * CvPlayer::GetPossibleAttackers(tile): the enemy units that can attack [tile] next turn (see [canThreaten]), in the order of the
     * civilizations; with [visibleOnly] those on a plot [civ] sees (the original's eTeamForVisibilityCheck).
     */
    fun possibleAttackers(tile: Tile, visibleOnly: Boolean = false): List<MapUnit> =
        contentsOf(tile).units.filter { !it.isDestroyed && (!visibleOnly || it.getTile().isVisible(civ)) }

    /**
     * CvDangerPlotContents::GetAirUnitDamage(air unit, action): what an interceptor (or, [AirAction.Intercept], an air sweep) does to [unit] at
     * [tile]: 0 for any other unit and for a missile. An attack always meets the best interceptor and is hit by its interception damage; a sweep
     * only takes damage from an interceptor that is an air unit (the ruleset's sweeps draw no fire from ground interceptors), as the mutual fight
     * of an air to air combat; a unit that intercepts takes the best damage an enemy fighter in range does when it sweeps.
     */
    fun airUnitDamage(unit: MapUnit, tile: Tile = unit.getTile(), action: AirAction = AirAction.Attack): Int {
        if (!unit.baseUnit.isAirUnit() || unit.hasUnique(UniqueType.SelfDestructs)) return 0
        val defender = SimCombatant(unit, tile, unit.health.coerceAtLeast(1))
        if (action == AirAction.Intercept) {
            var best = 0
            for (attacker in contentsOf(tile).units) {
                if (attacker.isDestroyed || !AirMoves.isAirSweepCapable(attacker)) continue
                val attackerCombatant = SimCombatant(attacker, attacker.getTile(), attacker.health.coerceAtLeast(1))
                val dealt = BattleDamage.calculateDamageToDefender(attackerCombatant, defender, tile, EXPECTED_RANDOMNESS, fullHealthScale = true)
                val received = BattleDamage.calculateDamageToAttacker(attackerCombatant, defender, tile, EXPECTED_RANDOMNESS, fullHealthScale = true)
                // If both would die, the one that has lost less so far survives with one hit point
                val damage = BattleDamage.resolveMeleeDeaths(attackerCombatant, defender, dealt, received).first
                if (damage > best) best = damage
            }
            return best
        }
        val interceptor = bestInterceptor(unit, tile) ?: return 0
        if (action == AirAction.Attack) return interceptionDamage(interceptor, unit, tile)
        if (!interceptor.baseUnit.isAirUnit()) return 0
        val interceptorCombatant = SimCombatant(interceptor, interceptor.getTile(), interceptor.health.coerceAtLeast(1))
        val fromInterceptor = BattleDamage.calculateDamageToDefender(interceptorCombatant, defender, tile, EXPECTED_RANDOMNESS, fullHealthScale = true)
        val toInterceptor = BattleDamage.calculateDamageToAttacker(interceptorCombatant, defender, tile, EXPECTED_RANDOMNESS, fullHealthScale = true)
        return BattleDamage.resolveMeleeDeaths(interceptorCombatant, defender, fromInterceptor, toInterceptor).first
    }

    //endregion
    //region Units

    private fun friendlyCity(tile: Tile): City? = if (tile.isCityCenter()) tile.getCity()?.takeIf { it.civ == civ } else null

    /** The civilian branch of CvDangerPlotContents::GetDanger(unit) */
    private fun civilianDanger(unit: MapUnit, tile: Tile, cityHere: City?): Int {
        // If the plot holds an enemy combat unit, mark it as max danger
        if (tile.getUnits().any { it.isMilitary() && it.civ != civ && civ.isAtWarWith(it.civ) }) return Int.MAX_VALUE
        // Can't hide in a city forever
        if (cityHere != null) return if (CityDangerAi.isInDangerOfFalling(cityHere, false)) Int.MAX_VALUE else 0
        val content = contentsOf(tile)
        // Plots the enemy can move into, as against those merely under ranged attack
        if (content.capturers.any { !it.isDestroyed }) return Int.MAX_VALUE
        // Attacks are less important for civilians
        var damage = 0
        for (attacker in content.units) {
            if (attacker.isDestroyed) continue
            damage += unseenDiscount(attacker, tile, BattleConstants.DAMAGE_TO_CIVILIAN_UNIT)
        }
        for (city in content.cities) damage += BattleConstants.DAMAGE_TO_CIVILIAN_UNIT
        return damage + improvementDamageAt(tile) + terrainDamage(unit, tile) + fog[tile.zeroBasedIndex] * FOG_DEFAULT_DANGER
    }

    /** A combat unit in a city of ours: the city takes the damage and the unit its share, unless the city falls (GetDanger(unit) calling GetDanger(city)) */
    private fun garrisonDanger(unit: MapUnit, tile: Tile, city: City): Int {
        val cityDanger = get(city)
        val maxHealth = city.getMaxHealth()
        if (cityDanger + (maxHealth - city.health) >= maxHealth + CITY_FALLS_MARGIN) return Int.MAX_VALUE
        // Reconstruct the amount of damage the garrison would absorb for the city
        return if (unit.canGarrison()) cityDanger * 2 * MAX_HEALTH / maxHealth + improvementDamageAt(tile) else 0
    }

    /** What an attacker whose civilization does not see [tile] does: only 4/5 of [damage], there is a chance it will not spot the target */
    private fun unseenDiscount(attacker: MapUnit, tile: Tile, damage: Int) =
        if (tile in attacker.civ.viewableTiles) damage else damage * UNSEEN_PERCENT / 100

    /** The plot [attacker] shoots or strikes [target] from: its own when the target is within its reach, else (it has to move first) the target's */
    private fun attackFrom(attacker: MapUnit, target: Tile): Tile =
        if (attacker.getTile().aerialDistanceTo(target) <= attackRange(attacker)) attacker.getTile() else target

    private fun attackRange(unit: MapUnit) = if (unit.baseUnit.isRanged()) unit.getRange() else 1

    private fun terrainDamage(unit: MapUnit, tile: Tile) = if (hasTerrainDamage(tile)) unit.getDamageFromTerrain(tile) else 0

    private fun hasTerrainDamage(tile: Tile) = tile.allTerrains.any { it.damagePerTurn > 0 }

    /** CvDangerPlots::UpdateDangerInternal: the damage of each revealed enemy citadel next to the plot, twice (it is certain) */
    private fun improvementDamageAt(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        if (improvementDamage[index] != NOT_COMPUTED) return improvementDamage[index]
        var damage = 0
        for (neighbor in tile.neighbors) {
            val improvement = neighbor.getUnpillagedTileImprovement() ?: continue
            if (!neighbor.isExplored(civ)) continue
            val owner = neighbor.getOwner()
            if (owner == civ || (owner != null && !civ.isAtWarWith(owner))) continue
            val nearby = PlotDefense.nearbyEnemyDamage(improvement)
            if (nearby > enemyHealRate) damage += nearby * 2
        }
        improvementDamage[index] = damage
        return damage
    }

    //endregion
    //region Contents and fog

    private fun contentsOf(tile: Tile): Contents {
        val index = tile.zeroBasedIndex
        contents[index]?.let { return it }
        val units = ArrayList<MapUnit>()
        val capturers = ArrayList<MapUnit>()
        for (unit in sourceUnits) {
            if (canThreaten(unit, tile)) units.add(unit)
            if (canCapture(unit, tile)) capturers.add(unit)
        }
        val cities = sourceCities.filter { city ->
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            distance in 1..city.getBombardRange()
        }
        return Contents(units, capturers, cities).also { contents[index] = it }
    }

    /**
     * An enemy unit that can walk into [tile] this turn (the plots UpdateDangerSingleUnit reaches, m_apCaptureUnits), on its own domain: a melee
     * unit not into a city of ours it would have to attack, any other unit, an air unit never.
     */
    private fun canCapture(unit: MapUnit, tile: Tile): Boolean {
        if (unit.baseUnit.isAirUnit()) return false
        if (unit.getTile().aerialDistanceTo(tile) > unit.baseUnit.movement.coerceAtLeast(1)) return false
        val native = if (unit.baseUnit.isWaterUnit) tile.isWater || tile.isCityCenter() else !unit.isEmbarkedAt(tile)
        if (!native) return false
        if (unit.baseUnit.isRanged()) return true
        return !(tile.isCityCenter() && tile.getCity()?.let { civ.isAtWarWith(unit.civ) && it.civ == civ } == true)
    }

    /**
     * CvDangerPlots::AddFogDanger for every source: hidden plots near an enemy unit (2), an enemy city in its own land (3) and a barbarian camp we
     * cannot see (3) could hold more enemies, which hit the plots within two of them on the same domain as the origin; each plot counts how many.
     */
    private fun fogCounts(): IntArray {
        val counts = IntArray(tileMap.tileList.size)
        fun add(origin: Tile, enemy: Civilization?, range: Int, checkOwnership: Boolean) {
            origin.forEachTileInDistance(range) { hidden ->
                if (!hidden.isVisible(civ) && !hidden.isImpassible() && !(checkOwnership && hidden.getOwner() != enemy))
                    hidden.forEachTileInDistance(2) { target -> if (target.isWater == origin.isWater) counts[target.zeroBasedIndex]++ }
            }
        }
        for (unit in sourceUnits) if (unit.id in knownUnits) add(unit.getTile(), unit.civ, 2, false)
        for (city in sourceCities) add(city.getCenterTile(), city.civ, 3, true)
        for (tile in tileMap.tileList)
            if (tile.improvement == Constants.barbarianEncampment && tile.isExplored(civ) && !tile.isVisible(civ)) add(tile, null, 3, false)
        return counts
    }

    //endregion
    //region Interception

    /** CvPlot::GetBestInterceptor(owner of [target], target): the unit of a civilization at war with [target]'s that can intercept at [tile], the likeliest to succeed first */
    private fun bestInterceptor(target: MapUnit, tile: Tile): MapUnit? {
        var best: MapUnit? = null
        for (other in civ.gameInfo.civilizations) {
            if (other == target.civ || !other.isAlive() || !other.isAtWarWith(target.civ)) continue
            for (unit in other.units.getCivUnits())
                if (unit.canIntercept(tile) && unit.currentInterceptChance() > 0 && (best == null || unit.currentInterceptChance() > best.currentInterceptChance())) best = unit
        }
        return best
    }

    /** CvUnit::GetInterceptionDamage: the damage [interceptor] would do to [target] on [targetTile], proportional to its full health, as the interception itself computes it */
    private fun interceptionDamage(interceptor: MapUnit?, target: MapUnit, targetTile: Tile): Int {
        if (interceptor == null || target.hasUnique(UniqueType.CannotBeIntercepted)) return 0
        val damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(interceptor), SimCombatant(target, targetTile, target.health.coerceAtLeast(1)),
            targetTile, EXPECTED_RANDOMNESS, fullHealthScale = true)
        val factor = (1f + interceptor.interceptDamagePercentBonus() / 100f) * target.receivedInterceptDamageFactor()
        return (damage * factor).toInt().coerceAtMost(target.health)
    }

    //endregion

    companion object {
        private const val NOT_COMPUTED = -1
        private const val EXPECTED_RANDOMNESS = TacticalTargets.EXPECTED_RANDOMNESS
        private const val MAX_HEALTH = 100
        /** FOG_DEFAULT_DANGER: what each hidden plot that could hold an enemy adds */
        private const val FOG_DEFAULT_DANGER = 1
        /** GetDanger(bool): terrain and features cannot be told apart from a unit's promotions, so 20 is assumed */
        private const val UNKNOWN_UNIT_TERRAIN_DAMAGE = 20
        /** The city falls when its danger and its damage exceed its hit points by this much (a margin for error) */
        private const val CITY_FALLS_MARGIN = 50
        private const val UNSEEN_PERCENT = 80

        /**
         *  Whether [unit] can attack [tile] this coming turn: within its movement and range, on its domain unless it shoots (a city is
         *  attackable from anywhere); an air unit within its range of where it is.
         */
        fun canThreaten(unit: MapUnit, tile: Tile): Boolean {
            val distance = unit.getTile().aerialDistanceTo(tile)
            if (unit.baseUnit.isAirUnit()) return distance <= unit.getRange()
            val ranged = unit.baseUnit.isRanged()
            val range = if (ranged) unit.getRange() else 1
            if (distance > unit.baseUnit.movement.coerceAtLeast(1) + range) return false
            return ranged || unit.baseUnit.isLandUnit == tile.isLand || tile.isCityCenter()
        }

        /** CvUnit::GetBaseCombatStrengthConsideringDamage (the unit's base strength, see the class comment) */
        fun strengthConsideringDamage(unit: MapUnit, rules: AiRuleset): Int = valueConsideringDamage(unit.baseUnit.strength, unit, rules)

        /** CvUnit::GetDamageCombatModifier: [value] less the share a wounded [unit] loses (WOUNDED_DAMAGE_MULTIPLIER percent of its missing health) */
        fun valueConsideringDamage(value: Int, unit: MapUnit, rules: AiRuleset): Int {
            val damageMod = (100 - unit.health) * rules.define(AiDefine.WOUNDED_DAMAGE_MULTIPLIER) / 100
            return value - value * damageMod / 100
        }
    }
}
