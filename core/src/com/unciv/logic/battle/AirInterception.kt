package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max

object AirInterception {

    /** CvPlot::GetBestInterceptor's rough distance check before it looks at a unit: no interceptor can be further away */
    private const val MAX_INTERCEPTOR_DISTANCE = 11

    /** AIR_SWEEP_INTERCEPTION_DAMAGE_MOD (CvUnitCombat::GenerateAirSweepCombatInfo): percent added to the damage a ground interceptor does to a sweeping fighter.
     *  -50 is the DLL default and Community Patch's `CoreDefineChanges.sql`; Brave New World's database has 0 and no ground interceptor damage at all. */
    private const val AIR_SWEEP_INTERCEPTION_DAMAGE_MOD = -50

    // Should draw an Interception if available on the tile from any Civ
    // Land Units deal 0 damage, and no XP for either party
    // Air Interceptors do Air Combat as if Melee (mutual damage) but using Ranged Strength. 5XP to both
    // But does not use the Interception mechanic bonuses/promotions
    // Counts as an Attack for both units
    // Will always draw out an Interceptor's attack (they cannot miss)
    // This means the combat against Air Units will execute and always deal damage
    // Random Civ at War will Intercept, prioritizing Air Units,
    // sorted by highest Intercept chance (same as regular Intercept)
    fun airSweep(attacker: MapUnitCombatant, attackedTile: Tile) {
        // Air Sweep counts as an attack, even if nothing else happens
        attacker.unit.attacksThisTurn++
        // copied and modified from reduceAttackerMovementPointsAndAttacks()
        // use up movement
        if (attacker.unit.hasUnique(UniqueType.CanMoveAfterAttacking) || attacker.unit.maxAttacksPerTurn() > attacker.unit.attacksThisTurn) {
            // if it was a melee attack and we won, then the unit ALREADY got movement points deducted,
            // for the movement to the enemy's tile!
            // and if it's an air unit, it only has 1 movement anyway, so...
            if (!attacker.unit.baseUnit.isAirUnit())
                attacker.unit.useMovementPoints(1f)
        } else attacker.unit.currentMovement = 0f
        val attackerName = attacker.getName()

        // Make giant sequence of all potential Interceptors from all Civs isAtWarWith()
        var potentialInterceptors = sequence<MapUnit> {  }
        for (interceptingCiv in attacker.getCivInfo().gameInfo.civilizations
            .filter {attacker.getCivInfo().isAtWarWith(it)}) {
            potentialInterceptors += interceptingCiv.units.getCivUnits()
                .filter { it.canIntercept(attackedTile) }
        }

        // first priority, only Air Units
        if (potentialInterceptors.any { it.baseUnit.isAirUnit() })
            potentialInterceptors = potentialInterceptors.filter { it.baseUnit.isAirUnit() }

        // airInterceptionLikeVp: CvUnitCombat::AttackAirSweep takes CvPlot::GetBestInterceptor, whatever its domain (no preference for air units)
        val likeVp = attacker.getCivInfo().gameInfo.ruleset.modOptions.constants.airInterceptionLikeVp
        val candidates = if (likeVp) listOfNotNull(bestInterceptorLikeVp(attacker.getCivInfo(), attacker, attackedTile).interceptor).asSequence()
            else potentialInterceptors
                .shuffled(kotlin.random.Random(attacker.getCivInfo().gameInfo.turns * 31L + attackedTile.position.hashCode()))  // randomize Civ, the same way on every run
                .sortedByDescending { it.currentInterceptChance() }

        // Pick highest chance interceptor
        for (interceptor in candidates) {
            // No chance of Interceptor to miss (unlike regular Interception). Always want to deal damage
            // pairs of LocationAction for Notification
            val locations = LocationAction(
                interceptor.currentTile.position,
                attacker.unit.currentTile.position
            )
            interceptor.attacksThisTurn++  // even if you miss, you took the shot
            if (!interceptor.baseUnit.isAirUnit()) {
                val interceptorName = interceptor.name
                // Deal no damage (moddable in future?) and no XP
                // airInterceptionLikeVp, CvUnitCombat::GenerateAirSweepCombatInfo: a ground interceptor does its interception damage, reduced by
                // AIR_SWEEP_INTERCEPTION_DAMAGE_MOD, and gets EXPERIENCE_DEFENDING_AIR_SWEEP_GROUND (2); it takes none
                var damage = 0
                if (likeVp) {
                    damage = (interceptionDamageLikeVp(interceptor, attacker, attackedTile, BattleDamage.getRandomness(attacker)) *
                        (100 + AIR_SWEEP_INTERCEPTION_DAMAGE_MOD) / 100).coerceAtMost(attacker.unit.health)
                    attacker.takeDamage(damage)
                    Battle.addXp(MapUnitCombatant(interceptor), 2, attacker)
                }
                val attackerText =
                    "Our [$attackerName] ([-$damage] HP) was attacked by an intercepting [$interceptorName] ([-0] HP)"
                val interceptorText =
                    "Our [$interceptorName] ([-0] HP) intercepted and attacked an enemy [$attackerName] ([-$damage] HP)"
                attacker.getCivInfo().addNotification(
                    attackerText, locations, NotificationCategory.War,
                    attackerName, NotificationIcon.War, interceptorName
                )
                interceptor.civ.addNotification(
                    interceptorText, locations, NotificationCategory.War,
                    interceptorName, NotificationIcon.War, attackerName
                )
                attacker.unit.action = null
                return
            }

            // Damage if Air v Air should work similar to Melee
            val damageDealt: Battle.DamageDealt = Battle.takeDamage(attacker, MapUnitCombatant(interceptor))

            // 5 XP to both
            Battle.addXp(MapUnitCombatant(interceptor), 5, attacker)
            Battle.addXp(attacker, 5, MapUnitCombatant(interceptor))

            val locationsInterceptorUnknown =
                LocationAction(attackedTile.position, attacker.unit.currentTile.position)

            addAirSweepInterceptionNotifications(
                attacker,
                interceptor,
                damageDealt,
                locationsInterceptorUnknown,
                locations
            )
            attacker.unit.action = null
            return
        }

        // No Interceptions available
        val attackerText = "Nothing tried to intercept our [$attackerName]"
        attacker.getCivInfo().addNotification(attackerText, NotificationCategory.War, attackerName)
        attacker.unit.action = null
    }

    // TODO: Check overlap with addInterceptionNotifications, and unify what we can
    private fun addAirSweepInterceptionNotifications(
        attacker: MapUnitCombatant,
        interceptor: MapUnit,
        damageDealt: Battle.DamageDealt,
        locationsInterceptorUnknown: Sequence<LocationAction>,
        locations: Sequence<LocationAction>
    ) {
        val attackerName = attacker.getName()
        val interceptorName = interceptor.name

        val attackerText =
            if (attacker.isDefeated()) {
                if (interceptor.getTile() in attacker.getCivInfo().viewableTiles)
                    "Our [$attackerName] ([-${damageDealt.defenderDealt}] HP) was destroyed by an intercepting [$interceptorName] ([-${damageDealt.attackerDealt}] HP)"
                else "Our [$attackerName] ([-${damageDealt.defenderDealt}] HP) was destroyed by an unknown interceptor"
            } else if (MapUnitCombatant(interceptor).isDefeated()) {
                "Our [$attackerName] ([-${damageDealt.defenderDealt}] HP) destroyed an intercepting [$interceptorName] ([-${damageDealt.attackerDealt}] HP)"
            } else "Our [$attackerName] ([-${damageDealt.defenderDealt}] HP) was attacked by an intercepting [$interceptorName] ([-${damageDealt.attackerDealt}] HP)"

        attacker.getCivInfo().addNotification(
            attackerText, locationsInterceptorUnknown, NotificationCategory.War,
            attackerName, NotificationIcon.War, NotificationIcon.Question
        )

        val interceptorText =
            if (attacker.isDefeated())
                "Our [$interceptorName] ([-${damageDealt.attackerDealt}] HP) intercepted and destroyed an enemy [$attackerName] ([-${damageDealt.defenderDealt}] HP)"
            else if (MapUnitCombatant(interceptor).isDefeated()) {
                if (attacker.getTile() in interceptor.civ.viewableTiles) "Our [$interceptorName] ([-${damageDealt.attackerDealt}] HP) intercepted and was destroyed by an enemy [$attackerName] ([-${damageDealt.defenderDealt}] HP)"
                else "Our [$interceptorName] ([-${damageDealt.attackerDealt}] HP) intercepted and was destroyed by an unknown enemy"
            } else "Our [$interceptorName] ([-${damageDealt.attackerDealt}] HP) intercepted and attacked an enemy [$attackerName] ([-${damageDealt.defenderDealt}] HP)"

        interceptor.civ.addNotification(
            interceptorText, locations, NotificationCategory.War,
            interceptorName, NotificationIcon.War, attackerName
        )
    }

    internal fun tryInterceptAirAttack(
        attacker: MapUnitCombatant,
        attackedTile: Tile,
        interceptingCiv: Civilization,
        defender: ICombatant?
    ): Battle.DamageDealt {
        val attackContext = GameContext(attacker, defender, attackedTile, CombatAction.Intercept)
        if (attacker.unit.hasUnique(UniqueType.CannotBeIntercepted, attackContext))
            return Battle.DamageDealt.None
        if (attacker.unit.civ.gameInfo.ruleset.modOptions.constants.airInterceptionLikeVp)
            return tryInterceptAirAttackLikeVp(attacker, attackedTile, attackContext)
        val rng = attackContext.stateBasedRandom("AirInterception.tryInterceptAirAttack")


        // Pick highest chance interceptor
        val interceptor = interceptingCiv.units.getCivUnits()
            .filter { it.canIntercept(attackedTile) && it.currentInterceptChance() > 0 } // CvUnit::GetBestInterceptor needs a value above 0
            .sortedByDescending { it.currentInterceptChance() }
            .firstOrNull { unit ->
                // Can't intercept if we have a unique preventing it
                val conditionalState = GameContext(interceptingCiv, ourCombatant = MapUnitCombatant(unit), theirCombatant = attacker, combatAction = CombatAction.Intercept, attackedTile = attackedTile)
                unit.getMatchingUniques(UniqueType.CannotInterceptUnits, conditionalState)
                    .none { attacker.matchesFilter(it.params[0]) }
                    // Defender can't intercept either
                    && unit != (defender as? MapUnitCombatant)?.unit
            }
            ?: return Battle.DamageDealt.None

        interceptor.attacksThisTurn++  // even if you miss, you took the shot
        // Does Intercept happen? If not, exit
        if (rng.nextFloat() > interceptor.currentInterceptChance() / 100f)
            return Battle.DamageDealt.None

        // CvUnit::GetInterceptionDamage: proportional to the interceptor's full health, not a third of its health loss
        var damage = BattleDamage.calculateDamageToDefender(
            MapUnitCombatant(interceptor),
            attacker,
            fullHealthScale = true
        )

        var damageFactor = 1f + interceptor.interceptDamagePercentBonus().toFloat() / 100f
        damageFactor *= attacker.unit.receivedInterceptDamageFactor()

        damage = (damage.toFloat() * damageFactor).toInt().coerceAtMost(attacker.unit.health)

        attacker.takeDamage(damage)
        if (damage > 0)
            Battle.addXp(MapUnitCombatant(interceptor), 2, attacker)

        addInterceptionNotifications(attacker, interceptor, damage)

        return Battle.DamageDealt(0, damage)
    }

    /** The interceptor [bestInterceptorLikeVp] picks, and how many interceptors could (CvPlot::GetInterceptorCount: those with a value above 0) */
    class InterceptorChoice(val interceptor: MapUnit?, val count: Int)

    /**
     *  With `airInterceptionLikeVp`: CvPlot::GetBestInterceptor(attacking player, attacker, land only, visible only) for an air attack on [attackedTile].
     *  The candidates are the units of every civilization at war with [attackerCiv] (not the barbarians, [attackerCiv] itself or not) that can intercept there now
     *  ([MapUnit.canIntercept]: range, no interception made, an air unit must not have moved) within 11 tiles; over land of a civilization they are not at war with,
     *  only on friendly land ("do not violate neutral players' airspace"). The best is the one with the highest value, the nearest of equal ones (a value of 0 still
     *  qualifies as the best when nothing is better, as in the original): its strength - the larger of strength and ranged strength, times 100 plus its
     *  `Damage when intercepting` (InterceptionCombatModifier) over 100, rounded down - times its [MapUnit.interceptionProbability], which for an air unit is scaled by
     *  its health once more. An [attacker] that cannot be intercepted has none; the defender of the attack may intercept it.
     *  Left out of the original's rules: an invisible attacker (stealth) is not intercepted; Unciv has `Cannot be intercepted` for that, and `Cannot intercept [filter] units` stays.
     */
    fun bestInterceptorLikeVp(
        attackerCiv: Civilization,
        attacker: MapUnitCombatant?,
        attackedTile: Tile,
        landInterceptorsOnly: Boolean = false,
        visibleInterceptorsOnly: Boolean = false
    ): InterceptorChoice {
        if (attacker != null && attacker.unit.hasUnique(UniqueType.CannotBeIntercepted)) return InterceptorChoice(null, 0)
        var best: MapUnit? = null
        var bestValue = 0
        var bestDistance = Int.MAX_VALUE
        var count = 0
        val tileOwner = attackedTile.getOwner()
        for (civ in attackerCiv.gameInfo.civilizations) {
            if (civ.isBarbarian || !civ.isAlive() || !attackerCiv.isAtWarWith(civ)) continue
            // "do not violate neutral players' airspace"
            if (tileOwner != null && !civ.isAtWarWith(tileOwner) && !attackedTile.isFriendlyTerritory(civ)) continue
            for (unit in civ.units.getCivUnits()) {
                if (visibleInterceptorsOnly && !unit.getTile().isVisible(attackerCiv)) continue
                val distance = unit.getTile().aerialDistanceTo(attackedTile)
                if (distance > MAX_INTERCEPTOR_DISTANCE) continue
                if (!unit.canIntercept(attackedTile)) continue
                if (landInterceptorsOnly && !unit.baseUnit.isLandUnit) continue
                if (attacker != null) {
                    val state = GameContext(civ, ourCombatant = MapUnitCombatant(unit), theirCombatant = attacker, combatAction = CombatAction.Intercept, attackedTile = attackedTile)
                    if (unit.getMatchingUniques(UniqueType.CannotInterceptUnits, state).any { attacker.matchesFilter(it.params[0]) }) continue
                }
                val value = interceptorValueLikeVp(unit)
                if (value > 0) count++
                if (value > bestValue || (value == bestValue && distance < bestDistance)) {
                    bestValue = value
                    bestDistance = distance
                    best = unit
                }
            }
        }
        return InterceptorChoice(best, count)
    }

    /** The value CvPlot::GetBestInterceptor ranks an interceptor by: the strength after its interception bonus times its chance x health (and, for an air unit, its health once more) */
    private fun interceptorValueLikeVp(unit: MapUnit): Int {
        val base = unit.baseUnit
        val strength = max(base.strength, base.rangedStrength) * 100 * (100 + unit.interceptDamagePercentBonus()) / 100
        var healthFactor = unit.interceptionProbability()
        if (base.isAirUnit()) healthFactor = healthFactor * (unit.health * 100 / unit.getMaxHealth()) / 100
        return strength * healthFactor
    }

    /**
     *  CvPlot::GetInterceptorCount(attacking civ, no attacker, any domain, visible only) with `airInterceptionLikeVp`: the interceptors the attacker's civilization sees
     *  that could intercept an attack on [tile], those with a value above 0.
     */
    fun interceptorCountLikeVp(attackerCiv: Civilization, tile: Tile): Int =
        bestInterceptorLikeVp(attackerCiv, null, tile, visibleInterceptorsOnly = true).count

    /**
     *  CvUnit::GetInterceptionDamage(attacker, no random roll beyond [randomnessFactor], target plot [targetTile]), with `airInterceptionLikeVp`, uncapped: VP's damage math (the
     *  interception defines, 2400 and 1200, the ranged ones in [com.unciv.models.ModConstants.CombatDamageConstants]) between the strength of the interceptor - attacking: ranged for an
     *  air unit - raised by its `Damage when intercepting`, and the strength of the attacker defending, with the attacker's
     *  [MapUnit.interceptionDefenseDamageModifier] as the damage modifier (plus 100, so -50 halves the damage). A wounded interceptor is weaker through the strength only with
     *  `combatHealthStrengthLikeVp`, as in the original (CvUnit::GetDamageCombatModifier); it takes no part in the proportional-to-health scaling of the old rule.
     */
    fun interceptionDamageLikeVp(interceptor: MapUnit, attacker: MapUnitCombatant, targetTile: Tile, randomnessFactor: Float): Int {
        val interceptorCombatant = MapUnitCombatant(interceptor)
        val constants = interceptor.civ.gameInfo.ruleset.modOptions.constants
        val interceptorStrength = BattleDamage.getAttackingStrength(interceptorCombatant, attacker, targetTile) * (100 + interceptor.interceptDamagePercentBonus()) / 100
        val attackerStrength = BattleDamage.getDefendingStrength(interceptorCombatant, attacker, targetTile)
        return BattleDamage.vpDamageMath(
            (interceptorStrength * 100).toInt(), (attackerStrength * 100).toInt(), randomnessFactor,
            constants.combatDamage.rangedBase, constants.combatDamage.rangedRandom,
            100 + attacker.unit.interceptionDefenseDamageModifier()
        )
    }

    /**
     *  CvUnitCombat::GenerateAirCombatInfo (interception part) and ResolveAirUnitVsCombat with `airInterceptionLikeVp`: the best interceptor ([bestInterceptorLikeVp])
     *  takes its shot whatever happens; the attacker first rolls its [MapUnit.evasionProbability] (1-100, over the chance to evade it is not intercepted), then the
     *  interceptor rolls [MapUnit.interceptionProbability] (100 or more always succeeds); a success does [interceptionDamageLikeVp] (at most the attacker's health) and
     *  gives the interceptor EXPERIENCE_DEFENDING_AIR_SWEEP_GROUND (2) XP. The caller ends the attack when the damage was above 0 ([Battle.attack]).
     */
    private fun tryInterceptAirAttackLikeVp(
        attacker: MapUnitCombatant,
        attackedTile: Tile,
        attackContext: GameContext
    ): Battle.DamageDealt {
        val interceptor = bestInterceptorLikeVp(attacker.getCivInfo(), attacker, attackedTile).interceptor
            ?: return Battle.DamageDealt.None
        val rng = attackContext.stateBasedRandom("AirInterception.tryInterceptAirAttack")

        interceptor.attacksThisTurn++  // increaseInterceptionCount: a shot was taken even if it missed
        var damage = 0
        val evasion = attacker.unit.evasionProbability()
        if (evasion == 0 || rng.nextInt(1, 101) > evasion) {
            val probability = interceptor.interceptionProbability()
            if (probability >= 100 || rng.nextInt(1, 101) <= probability)
                damage = interceptionDamageLikeVp(interceptor, attacker, attackedTile, BattleDamage.getRandomness(attacker))
                    .coerceAtMost(attacker.unit.health)
        }
        if (damage <= 0) return Battle.DamageDealt.None

        attacker.takeDamage(damage)
        Battle.addXp(MapUnitCombatant(interceptor), 2, attacker)
        addInterceptionNotifications(attacker, interceptor, damage)
        return Battle.DamageDealt(0, damage)
    }

    private fun addInterceptionNotifications(
        attacker: MapUnitCombatant,
        interceptor: MapUnit,
        damage: Int
    ) {
        val attackerName = attacker.getName()
        val interceptorName = interceptor.name

        val locations = LocationAction(interceptor.currentTile.position, attacker.unit.currentTile.position)
        val attackerText = if (!attacker.isDefeated())
            "Our [$attackerName] ([-$damage] HP) was attacked by an intercepting [$interceptorName] ([-0] HP)"
        else if (interceptor.getTile() in attacker.getCivInfo().viewableTiles)
            "Our [$attackerName] ([-$damage] HP) was destroyed by an intercepting [$interceptorName] ([-0] HP)"
        else "Our [$attackerName] ([-$damage] HP) was destroyed by an unknown interceptor"

        attacker.getCivInfo().addNotification(
            attackerText, interceptor.currentTile.position, NotificationCategory.War,
            attackerName, NotificationIcon.War, interceptorName
        )

        val interceptorText = if (attacker.isDefeated())
            "Our [$interceptorName] ([-0] HP) intercepted and destroyed an enemy [$attackerName] ([-$damage] HP)"
        else "Our [$interceptorName] ([-0] HP) intercepted and attacked an enemy [$attackerName] ([-$damage] HP)"
        interceptor.civ.addNotification(
            interceptorText, locations, NotificationCategory.War,
            interceptorName, NotificationIcon.War, attackerName
        )
    }

}
