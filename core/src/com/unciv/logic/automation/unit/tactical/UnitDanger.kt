package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.unit.PlotDefense
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  What the enemies we see can do to one of our units on a tile next turn: CvUnit::GetDanger(plot) (CvDangerPlotContents::GetDanger(unit)) and
 *  CvPlayer::GetPossibleAttackers (CvDangerPlotContents::GetPossibleAttackers), for the moves that look at a single unit: fleeing, healing,
 *  opportunity attacks and the lone pursuer ([OpportunityAttacks]). The combat simulation has the same number for its hypothetical units
 *  ([SimWorld.danger]), with the damage the plan has done so far taken into account.
 *
 *  The sources are the visible military units of civilizations at war with [civ] (air units are left out) that can get within their movement and
 *  range of the tile, on their own domain unless they shoot, and the cities of those civilizations that can bombard the tile; the damage is
 *  [BattleDamage] at the mean of the randomness, plus what an enemy improvement next to the tile and the terrain do. The list of sources is
 *  collected when the first question is asked, units that die later are skipped; an enemy that shows up after that is not in it.
 *
 *  Deviations: the original's path finder, the fog-of-war counts and the units it remembers after they vanished are not modelled (the same as
 *  [SimWorld]); a unit capturing one of our cities is not expected to garrison it (the city on the tile counts as a city defended by the unit).
 */
class UnitDanger(private val civ: Civilization) {

    private val enemyUnits: List<MapUnit> by lazy {
        civ.gameInfo.civilizations.asSequence()
            .filter { it != civ && it.isAlive() && civ.isAtWarWith(it) }
            .flatMap { it.units.getCivUnits() }
            .filter { it.isMilitary() && !it.baseUnit.isAirUnit() && (it.baseUnit.isMelee() || it.baseUnit.isRanged()) && it.isVisibleTo(civ) }
            .toList()
    }

    private val enemyCities: List<City> by lazy {
        civ.gameInfo.civilizations.asSequence()
            .filter { it != civ && it.isAlive() && civ.isAtWarWith(it) }
            .flatMap { it.cities }
            .filter { it.getCenterTile().isExplored(civ) }
            .toList()
    }

    /** CvUnit::GetDanger(plot): the expected damage [unit] would take on [tile] (where it stands by default) from the enemies, at most 10 times its hit points of 100 */
    fun get(unit: MapUnit, tile: Tile = unit.getTile()): Int {
        val health = unit.health.coerceAtLeast(1)
        val defender = SimCombatant(unit, tile, health)
        val cityHere = if (tile.isCityCenter() && tile.getOwner() == civ) tile.getCity() else null
        var sum = 0
        for (attacker in enemyUnits) {
            if (attacker.isDestroyed || !canThreaten(attacker, tile)) continue
            val attackerTile = attacker.getTile()
            if (attackerTile == tile) continue
            val attackerCombatant = SimCombatant(attacker, attackerTile, attacker.health.coerceAtLeast(1))
            if (cityHere != null) {
                sum += BattleDamage.calculateDamageToDefender(attackerCombatant, CityCombatant(cityHere), attackerTile, EXPECTED_RANDOMNESS, ignoreFlanking = true)
                continue
            }
            var damage = BattleDamage.calculateDamageToDefender(attackerCombatant, defender, attackerTile, EXPECTED_RANDOMNESS)
            val attackerSelf = if (attacker.baseUnit.isRanged()) 0
                else BattleDamage.calculateDamageToAttacker(attackerCombatant, defender, attackerTile, EXPECTED_RANDOMNESS)
            // A suicide attack with a unit that does not do suicide attacks is unlikely
            if (attackerSelf >= attacker.health) damage /= 2
            sum += damage
        }
        if (cityHere == null)
            for (city in enemyCities)
                if (city.getCenterTile().aerialDistanceTo(tile) <= city.getBombardRange())
                    sum += BattleDamage.calculateDamageToDefender(CityCombatant(city), defender, city.getCenterTile(), EXPECTED_RANDOMNESS)
        if (cityHere != null) {
            // Capturing a city destroys its garrison: the unit takes its share of what the city would take
            val maxHealth = cityHere.getMaxHealth()
            return if (sum + (maxHealth - cityHere.health) < maxHealth + CITY_FALLS_MARGIN) {
                if (unit.canGarrison()) sum * 2 * 100 / maxHealth + improvementDamageAt(tile) else 0
            } else MAX_DANGER
        }
        sum += improvementDamageAt(tile) + unit.getDamageFromTerrain(tile)
        return minOf(sum, MAX_DANGER)
    }

    /**
     *  CvPlayer::GetPossibleAttackers(tile, team): the visible enemy units that can attack [tile] next turn (within their movement and range,
     *  see [canThreaten]), in the order of the civilizations.
     */
    fun possibleAttackers(tile: Tile): List<MapUnit> = enemyUnits.filter { !it.isDestroyed && canThreaten(it, tile) }

    private fun improvementDamageAt(tile: Tile): Int = tile.neighbors.maxOfOrNull { neighbor ->
        val owner = neighbor.getOwner()
        if (owner == null || owner == civ || !civ.isAtWarWith(owner)) 0
        else neighbor.getUnpillagedTileImprovement()?.let { PlotDefense.nearbyEnemyDamage(it) } ?: 0
    } ?: 0

    companion object {
        private const val EXPECTED_RANDOMNESS = TacticalTargets.EXPECTED_RANDOMNESS
        private const val MAX_DANGER = 10 * 100
        private const val CITY_FALLS_MARGIN = 50

        /**
         *  Whether [unit] can attack [tile] this coming turn: within its movement and range, on its domain unless it shoots (a city is
         *  attackable from anywhere).
         */
        fun canThreaten(unit: MapUnit, tile: Tile): Boolean {
            val ranged = unit.baseUnit.isRanged()
            val range = if (ranged) unit.getRange() else 1
            val distance = unit.getTile().aerialDistanceTo(tile)
            if (distance > unit.baseUnit.movement.coerceAtLeast(1) + range) return false
            return ranged || unit.baseUnit.isLandUnit == tile.isLand || tile.isCityCenter()
        }
    }
}
