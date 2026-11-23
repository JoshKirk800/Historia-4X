package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.tactical.PillageRules
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.PromotionAiDef
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.Promotion
import kotlin.random.Random

/**
 *  The promotion choice of the AI: CvPlayerAI::AI_doTurnUnitsPost has every unit of a non-human player take its promotions
 *  (CvUnit::AI_promote), each time the one CvUnit::AI_promotionValue rates highest, until it cannot afford another.
 *  The original does this at the end of CvPlayer::doTurnUnits, which runs when the player's turn starts, before its units move.
 *
 *  A promotion is rated from the player's personality flavors, the unit's AI type and what the unit already has, by the columns of its
 *  UnitPromotions row (AI.json `promotionAi`); one that adds up to nothing is never taken. Equal ratings are told apart by the roll
 *  that is added to every positive one (0 to 14, from the civilization's random) and, if still equal, by the order of the XML rows.
 *  Unciv has no free promotions to take in the original, so only XP buys one.
 *
 *  Unciv promotions and the XML promotions they stand for: by name, except Besiege = PROMOTION_SIEGE, Extended Range = PROMOTION_RANGE,
 *  Operational Range = PROMOTION_AIR_RANGE, Air Targeting I and II = PROMOTION_AIR_TARGETING_1 and 2, Anti-Armor I and II =
 *  PROMOTION_HELI_AMBUSH_1 and 2, Siege I to III = PROMOTION_AIR_SIEGE_1 to 3, Mobility I and II = PROMOTION_HELI_MOBILITY_1 and 2.
 *  Unciv merged equal ones: Ambush I and II also stand for PROMOTION_AIR_AMBUSH_1 and 2, Logistics for PROMOTION_AIR_LOGISTICS, Air
 *  Repair for PROMOTION_REPAIR and PROMOTION_HELI_REPAIR (the promotion count of the tree is the same, the rating does not differ).
 *  A promotion without a row reads nothing of what the original rates (Wolfpack, Flight Deck, Landing Party, Morale, the unique unit
 *  abilities, ...) and is worth 0. The promotions units start with in the original and Unciv gives as unique abilities (Unit_FreePromotions,
 *  for example Pikeman's bonus against mounted units) count as what the unit already has through [com.unciv.models.ruleset.ai.UnitAiTypesDef.freePromotions].
 */
object PromotionAi {

    /** CvPlayerAI::AI_doTurnUnitsPost: [civ]'s units, in unit id order, take the promotions they can afford */
    fun promoteUnits(civ: Civilization) {
        timeThis("AI.Promotions") {
            val random = civ.ai.random("UnitAI.Promote")
            for (unit in civ.units.getCivUnits().sortedBy { it.id }.toList()) {
                // an earlier promotion can destroy a unit
                if (!unit.isDestroyed) promote(unit, random)
            }
        }
    }

    /** CvUnit::AI_promote: takes the best valued promotion while the unit has the XP, then looks again */
    fun promote(unit: MapUnit, random: Random) {
        val rules = unit.civ.gameInfo.ruleset.ai
        while (unit.promotions.XP >= unit.promotions.xpForNextPromotion()) {
            var best: Promotion? = null
            var bestValue = 0
            val effects = UnitEffects(unit, rules)
            for (promotion in unit.promotions.getAvailablePromotions()) {
                val value = rate(unit, rules.promotionAiOf(promotion.name), effects, promotion, random)
                if (value > bestValue) {
                    bestValue = value
                    best = promotion
                }
            }
            unit.promotions.addPromotion((best ?: return).name)
        }
    }

    /** What the unit has of the promotions the rating reads: its own and the ones its unit type starts with, once each per XML promotion */
    private class UnitEffects(unit: MapUnit, rules: AiRuleset) {
        val defs: List<PromotionAiDef>
        val combatClass: String? = rules.unitAiTypesOf(unit.baseUnit.name)?.combatClass

        init {
            val byType = LinkedHashMap<String, PromotionAiDef>()
            for (name in unit.promotions.promotions) rules.promotionAiOf(name)?.let { byType[it.type] = it }
            for (type in rules.unitAiTypesOf(unit.baseUnit.name)?.freePromotions.orEmpty()) rules.promotionAiOfType(type)?.let { byType[type] = it }
            defs = byType.values.toList()
        }

        inline fun sum(selector: (PromotionAiDef) -> Int) = defs.sumOf(selector)
        fun terrainAttack(key: String) = defs.sumOf { it.terrains[key]?.attack ?: 0 }
        fun terrainDefense(key: String) = defs.sumOf { it.terrains[key]?.defense ?: 0 }
        fun featureAttack(key: String) = defs.sumOf { it.features[key]?.attack ?: 0 }
        fun featureDefense(key: String) = defs.sumOf { it.features[key]?.defense ?: 0 }
        fun unitCombatModifier(key: String) = defs.sumOf { it.unitCombatModifiers[key] ?: 0 }
        fun otherCombatModifiers(own: String?) = defs.sumOf { def -> def.unitCombatModifiers.filterKeys { it != own }.values.sum() }
    }

    private fun canAirDefend(unit: MapUnit) = unit.baseUnit.isAirUnit() && unit.interceptChance() > 0

    private fun canAirSweep(unit: MapUnit) = unit.baseUnit.isAirUnit() && !unit.isEmbarked() && unit.hasUnique(UniqueType.CanAirsweep)

    /** CvUnit::isPromotionValid beyond the promotion tree: a heal needs damage, interception and air sweep promotions need the ability */
    private fun canTake(unit: MapUnit, def: PromotionAiDef): Boolean {
        if (def.instaHeal && unit.health >= unit.getMaxHealth()) return false
        if (def.interceptionCombatModifier != 0 && !canAirDefend(unit)) return false
        if (def.airSweepCombatModifier != 0 && !canAirSweep(unit)) return false
        return true
    }


    private val offensiveTypes = setOf(UnitAiType.ATTACK, UnitAiType.FAST_ATTACK)
    private val defensiveTypes = setOf(UnitAiType.DEFENSE, UnitAiType.COUNTER)
    private val exploringTypes = setOf(UnitAiType.EXPLORE, UnitAiType.EXPLORE_SEA)
    private val mobileTypes = setOf(
        UnitAiType.ATTACK_SEA, UnitAiType.PIRATE_SEA, UnitAiType.RESERVE_SEA, UnitAiType.ESCORT_SEA, UnitAiType.EXPLORE_SEA,
        UnitAiType.ASSAULT_SEA, UnitAiType.SETTLER_SEA, UnitAiType.FAST_ATTACK, UnitAiType.ATTACK, UnitAiType.PARADROP
    )
    private val strikeTypes = setOf(
        UnitAiType.ATTACK, UnitAiType.CITY_BOMBARD, UnitAiType.FAST_ATTACK, UnitAiType.COUNTER, UnitAiType.ATTACK_SEA,
        UnitAiType.PIRATE_SEA, UnitAiType.ESCORT_SEA, UnitAiType.PARADROP
    )

    /** CvUnit::AI_promotionValue of [promotion] for [unit]: 0 for one the original rates nothing of or the unit cannot take */
    fun promotionValue(unit: MapUnit, promotion: Promotion, random: Random): Int {
        val rules = unit.civ.gameInfo.ruleset.ai
        val def = rules.promotionAiOf(promotion.name)
        return rate(unit, def, if (def != null) UnitEffects(unit, rules) else null, promotion, random)
    }

    /**
     *  The rating of [promotion] for [unit]: the columns of its [def] row, if it has one and the unit can take it ([rowValue]), plus what
     *  [vpUniqueValue] reads from the promotion's own uniques; positive ones get the roll, as the original adds it to the one value it computes.
     *  [effects] is the unit's, needed with a [def].
     */
    private fun rate(unit: MapUnit, def: PromotionAiDef?, effects: UnitEffects?, promotion: Promotion, random: Random): Int {
        var value = 0
        if (def != null && effects != null) {
            if (!canTake(unit, def)) return 0
            value = rowValue(unit, effects, def)
        }
        value += vpUniqueValue(unit, promotion)
        if (value > 0) value += random.nextInt(ROLL_RANGE)
        return value
    }

    private fun rowValue(unit: MapUnit, own: UnitEffects, p: PromotionAiDef): Int {
        val ai = unit.civ.ai
        val offense = ai.personality(Flavor.Offense)
        val defense = ai.personality(Flavor.Defense)
        val ranged = ai.personality(Flavor.Ranged)
        val recon = ai.personality(Flavor.Recon)
        val mobile = ai.personality(Flavor.Mobile)
        val naval = ai.personality(Flavor.Naval)
        val air = ai.personality(Flavor.Air)
        val unitAi = UnitAiTypes.current(unit)
        val isRanged = unit.baseUnit.isRanged()
        val isSea = unit.baseUnit.isWaterUnit
        val isAir = unit.baseUnit.isAirUnit()
        // CvUnit::noDefensiveBonus: boats never fortify
        val noDefensiveBonus = isSea || unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true) ||
            own.defs.any { it.noDefensiveBonus }
        val baseMoves = unit.getMaxMovement()
        var value = 0

        // If we are damaged, insta heal is the way to go (half health or less)
        if (p.instaHeal && unit.getMaxHealth() - unit.health >= unit.getMaxHealth() / 2) value += 1000

        if (p.openAttack != 0) {
            var t = p.openAttack * (100 + own.sum { it.openAttack } * 2) / 100
            if (noDefensiveBonus) t *= 2
            value += t + offense * 2
        }
        if (p.openDefense != 0) {
            var t = p.openDefense * (100 + own.sum { it.openDefense } * 2) / 100
            if (noDefensiveBonus) t *= 2
            value += t + defense * 2
        }
        if (p.roughAttack != 0) {
            var t = p.roughAttack * (100 + own.sum { it.roughAttack } * 2) / 100
            if (!noDefensiveBonus) t *= 2
            value += t + offense * 2
        }
        if (p.roughDefense != 0) {
            var t = p.roughDefense * (100 + own.sum { it.roughDefense } * 2) / 100
            if (!noDefensiveBonus) t *= 2
            value += t + defense * 2
        }
        if (p.openRangedAttackMod != 0 && isRanged) {
            var t = p.openRangedAttackMod * (100 + own.sum { it.openRangedAttackMod } * 2) / 100
            if (noDefensiveBonus) t *= 2
            value += t + ranged * 2
        }
        if (p.roughRangedAttackMod != 0 && isRanged) {
            var t = p.roughRangedAttackMod * (100 + own.sum { it.roughRangedAttackMod } * 2) / 100
            if (!noDefensiveBonus) t *= 2
            value += t + ranged * 2
        }

        value += p.visibilityChange * ((if (unitAi in exploringTypes) 50 else 10) + recon * 2)

        if (p.cityAttack != 0) {
            val t = p.cityAttack * (100 + own.sum { it.cityAttack } * 2) / 100
            value += t + offense * 2
            if (isRanged) value += ranged * 2
        }
        if (p.cityDefense != 0) {
            val t = p.cityDefense * (100 + own.sum { it.cityDefense } * 2) / 100
            value += t + defense * 2
            if (unitAi in defensiveTypes) value *= 2
        }
        if (p.attackFortifiedMod != 0 && isRanged) {
            val t = p.attackFortifiedMod * (100 + own.sum { it.attackFortifiedMod } * 2) / 100
            value += t + offense * ranged * 2
        }

        value += p.enemyHealChange * ((if (unitAi == UnitAiType.PARADROP || unitAi == UnitAiType.PIRATE_SEA) 50 else 10) + offense * 2)
        value += p.neutralHealChange * ((if (unitAi in exploringTypes) 40 else 10) + recon * 2)
        value += p.friendlyHealChange * ((if (unitAi in defensiveTypes) 20 else 10) + defense * 2)
        value += p.adjacentTileHealChange * ((if (unitAi in defensiveTypes) 40 else 10) + defense * 2)

        if (p.amphib) value += (if (unitAi in offensiveTypes) 40 else 10) + offense * 2
        if (p.river) value += (if (unitAi in offensiveTypes) 40 else 10) + offense * 2

        if (p.rangedDefenseMod != 0) {
            var t = p.rangedDefenseMod * (100 + own.sum { it.rangedDefenseMod } * 2) / 100
            // likely not a ranged unit
            if (unitAi in defensiveTypes || unitAi == UnitAiType.ATTACK) t *= 2
            // a slow unit
            if (baseMoves <= 2) t *= 2
            value += t + defense * 2
        }
        if (p.rangeAttackIgnoreLOS && isRanged) value += 75 + ranged * 2
        if (p.attackWoundedMod != 0 && isRanged) {
            val t = p.attackWoundedMod * (100 + own.sum { it.attackWoundedMod } * 2) / 100
            value += t + offense * ranged * 2
        }
        if (p.flankAttackModifier > 0) value += (2 * offense + mobile) * baseMoves * p.flankAttackModifier / 100
        if (p.healOutsideFriendly && isSea) value += 50 + naval * 2

        value += p.movesChange * ((if (unitAi in mobileTypes) 50 else 30) + mobile * 2)
        if (p.alwaysHeal) value += (if (unitAi in strikeTypes) 50 else 30) + mobile * 2
        if (p.blitz) value += (if (unitAi in strikeTypes) 50 else 20) + (mobile + offense) * 2
        if (p.canMoveAfterAttacking) value += (if (unitAi in strikeTypes) 30 else 10) + (mobile + offense) * 2
        value += p.extraAttacks * 200
        if (isRanged) value += p.rangeChange * 200

        if (p.interceptionCombatModifier != 0 && canAirDefend(unit)) {
            val t = p.interceptionCombatModifier * (100 + own.sum { it.interceptionCombatModifier } * 2) / 100
            value += t + air * 2
        }
        if (p.airSweepCombatModifier != 0 && canAirSweep(unit)) {
            val t = p.airSweepCombatModifier * (100 + own.sum { it.airSweepCombatModifier } * 2) / 100
            value += t + air * 2
        }
        value += p.numInterceptionChange * 200
        if (p.interceptionDefenseDamageModifier != 0 && isAir) {
            val t = p.interceptionDefenseDamageModifier * (100 + own.sum { it.interceptionDefenseDamageModifier } * 2) / 100
            value += t + air * 2
        }
        if (p.defenseMod != 0) {
            val t = p.defenseMod * (100 + own.sum { it.defenseMod } * 2) / 100
            value += t + defense * 2
        }

        for ((key, terrain) in p.terrains) {
            if (terrain.attack != 0) {
                value += terrain.attack * (100 + own.terrainAttack(key) * 2) / 100 + offense * 2
                if (unitAi in offensiveTypes) value *= 2
            }
            if (terrain.defense != 0) {
                value += terrain.defense * (100 + own.terrainDefense(key)) / 100 + defense * 2
                if (unitAi in defensiveTypes) value *= 2
            }
            if (terrain.doubleMove) value += doubleMoveValue(unitAi, recon, mobile, offense)
        }
        for ((key, feature) in p.features) {
            if (feature.attack != 0) {
                value += feature.attack * (100 + own.featureAttack(key) * 2) / 100 + offense * 2
                if (unitAi in offensiveTypes) value *= 2
            }
            if (feature.defense != 0) {
                value += feature.defense * (100 + own.featureDefense(key) * 2) / 100 + defense * 2
                if (unitAi in defensiveTypes) value *= 2
            }
            if (feature.doubleMove) value += doubleMoveValue(unitAi, recon, mobile, offense)
        }

        val sameCombat = own.combatClass?.let { own.unitCombatModifier(it) } ?: 0
        val otherCombat = own.otherCombatModifiers(own.combatClass)
        for ((combatClass, modifier) in p.unitCombatModifiers) {
            val weight = if (combatClass == own.combatClass) (if (sameCombat >= otherCombat) 70 else 30)  // fighting their own kind
            else if (own.unitCombatModifier(combatClass) > 10) 70 else 30  // fighting other kinds
            value += when (unitAi) {
                UnitAiType.COUNTER, UnitAiType.RANGED -> modifier * weight / 25
                UnitAiType.ATTACK, UnitAiType.DEFENSE -> modifier * weight / 50
                else -> modifier * weight / 100
            }
        }
        for (modifier in p.domainModifiers.values) {
            value += when (unitAi) {
                UnitAiType.COUNTER -> modifier * 2
                UnitAiType.ATTACK, UnitAiType.DEFENSE -> modifier
                else -> modifier / 2
            }
        }

        return value
    }

    private const val ROLL_RANGE = 15

    /**
     *  The terms of Vox Populi's CvUnit::AI_promotionValue (Release-5.4.8) for what the military flip's promotions do that the Brave New World rows
     *  ([PromotionAiDef]) do not describe, read from the promotion's uniques with the original's flavor weights and factors; each is added only where the
     *  engine reads the unique (its ModConstants switch is on), and the unit's own promotions stand for the original's `getExtra...` sums:
     *  - `[amount] maximum hit points` (MaxHitPointsChange; `unitMaxHitPointsLikeVp`): half of amount x (offense + defense + city defense) for a shooter, else amount x (2 offense + defense);
     *  - `[+n]% Strength` against units above half health (AttackAboveHealthMod), at or below half health (AttackBelowHealthMod, with what killing heals,
     *    mounted x1.5) and fully healed (AttackFullyHealedMod): (n + the unit's own) x flavors x a factor by shooter or not;
     *  - `[amount] extra flanking power` (ExtraFlankPower; `flankPowerLikeVp`): amount x (offense + mobile + recon), by the unit's flank bonus and (2 + moves) / 2;
     *  - `Ignores terrain cost in`, `Half movement in` and `[amount] extra movement cost in` (IgnoreTerrainCostIn, TerrainHalfMove, TerrainExtraMove; `terrainMovementPromotionsLikeVp`)
     *    for each terrain that matches and costs more than 1 (the half and extra ones, which cost, are negative), the more for a unit that gains XP from scouting;
     *  - `Gains XP from scouting` (`scoutingXpLikeVp`) and `[amount] XP when pillaging` (`pillageXpLikeVp`);
     *  - `[amount] air defense` (LandAirDefenseBonus; `airDefenseLikeVp`), doubled while we need air units, and `[amount]% chance to evade interception`
     *    (EvasionChange; `airInterceptionLikeVp`).
     *  The original adds up doubles and truncates once; so does this.
     */
    private fun vpUniqueValue(unit: MapUnit, promotion: Promotion): Int {
        val uniques = promotion.uniqueObjects
        if (uniques.isEmpty()) return 0
        val civ = unit.civ
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val ai = civ.ai
        val offense = ai.personality(Flavor.Offense)
        val defense = ai.personality(Flavor.Defense)
        val cityDefense = ai.personality(Flavor.CityDefense)
        val recon = ai.personality(Flavor.Recon)
        val mobile = ai.personality(Flavor.Mobile)
        val air = ai.personality(Flavor.Air)
        val isRanged = unit.baseUnit.isRanged()
        val baseMoves = unit.getMaxMovementWhenEmbarked(false, ignoreOtherUnit = true)
        val scouts = unit.cache.gainsXpFromScouting
        var value = 0.0

        fun total(source: List<Unique>, type: UniqueType) = source.filter { it.type == type }.sumOf { it.params[0].toInt() }
        fun strengthVs(source: List<Unique>, conditional: UniqueType) =
            source.filter { it.type == UniqueType.Strength && it.modifiers.any { modifier -> modifier.type == conditional } }.sumOf { it.params[0].toInt() }
        val own by lazy { unit.promotions.getPromotions().flatMap { it.uniqueObjects }.toList() }

        if (constants.unitMaxHitPointsLikeVp) {
            val amount = total(uniques, UniqueType.MaxHitPointsChange)
            if (amount != 0) value += if (isRanged) amount * (offense + defense + cityDefense) * 0.5 else amount * (2 * offense + defense).toDouble()
        }

        val aboveHalf = strengthVs(uniques, UniqueType.ConditionalVsAboveHalfHealth)
        if (aboveHalf != 0)
            value += (aboveHalf + strengthVs(own, UniqueType.ConditionalVsAboveHalfHealth)) * (defense + 2 * cityDefense) * (if (isRanged) 0.45 else 0.2)
        val belowHalf = strengthVs(uniques, UniqueType.ConditionalVsHalfHealthOrBelow)
        if (belowHalf != 0) {
            var extra = strengthVs(own, UniqueType.ConditionalVsHalfHealthOrBelow)
            unit.forEachMatchingUnique(UniqueType.HealsAfterKilling, checkCivInfoUniques = true) { extra += it.params[0].toInt() }
            var term = (belowHalf + extra) * (defense + 2 * offense) * (if (isRanged) 0.25 else 0.4)
            if (MapUnitCombatant(unit).matchesOpponentFilter("Mounted")) term *= 1.5
            value += term
        }
        val fullyHealed = strengthVs(uniques, UniqueType.ConditionalVsFullyHealed)
        if (fullyHealed != 0)
            value += (fullyHealed + strengthVs(own, UniqueType.ConditionalVsFullyHealed)) * (defense + 2 * cityDefense) * (if (isRanged) 0.3 else 0.15)

        if (constants.flankPowerLikeVp) {
            val power = total(uniques, UniqueType.ExtraFlankPower)
            if (power > 0) {
                var flankBonus = 0
                unit.forEachMatchingUnique(UniqueType.FlankAttackBonus, checkCivInfoUniques = true) { flankBonus += it.params[0].toInt() }
                value += power * (offense + mobile + recon) * (100 + flankBonus) / 100.0 * ((2 + baseMoves) / 2)
            }
        }

        if (constants.terrainMovementPromotionsLikeVp) {
            val terrains = civ.gameInfo.ruleset.terrains.values
            val ignoresAll = unit.hasUnique(UniqueType.IgnoresTerrainCost)
            fun filtersOf(type: UniqueType): List<String> {
                val filters = ArrayList<String>()
                unit.forEachMatchingUnique(type, gameContext = GameContext.IgnoreConditionals) { filters += it.params[if (type == UniqueType.ExtraMovementCostIn) 1 else 0] }
                return filters
            }
            val ownIgnore = filtersOf(UniqueType.IgnoresTerrainCostIn)
            val ownHalf = filtersOf(UniqueType.HalfMovementIn)
            val ownExtra = filtersOf(UniqueType.ExtraMovementCostIn)
            for (unique in uniques) {
                when (unique.type) {
                    UniqueType.IgnoresTerrainCostIn -> if (!ignoresAll) for (terrain in terrains)
                        if (terrain.movementCost > 1 && terrain.matchesFilter(unique.params[0]) && ownIgnore.none { terrain.matchesFilter(it) })
                            value += (mobile * 2 + recon) * 8 * (if (scouts) 3 else 1)
                    UniqueType.HalfMovementIn -> for (terrain in terrains)
                        if (terrain.matchesFilter(unique.params[0]) && ownHalf.none { terrain.matchesFilter(it) })
                            value += mobile * 3 * -8 * (if (scouts) 2 else 1)
                    UniqueType.ExtraMovementCostIn -> for (terrain in terrains)
                        if (terrain.matchesFilter(unique.params[1]) && ownExtra.none { terrain.matchesFilter(it) })
                            value += mobile * 3 * -5 * (if (scouts) 1.8 else 1.0)
                    else -> Unit
                }
            }
        }

        if (constants.scoutingXpLikeVp && !scouts && uniques.any { it.type == UniqueType.GainsXpFromScouting })
            value += (unit.getVisibilityRange() + baseMoves) * (3 * recon) * 5

        if (constants.pillageXpLikeVp) {
            val xp = total(uniques, UniqueType.XpFromPillaging)
            if (xp > 0) value += xp * (offense + 2 * mobile) * baseMoves * (if (PillageRules.hasFreePillageMove(unit)) 2 else 1)
        }

        if (constants.airDefenseLikeVp) {
            var amount = total(uniques, UniqueType.AirDefense)
            if (amount != 0) {
                if (ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_NEED_AIR")) amount *= 2
                value += amount * (3 * ai.personality(Flavor.Antiair)) * 0.4
            }
        }
        if (constants.airInterceptionLikeVp) {
            val evasion = total(uniques, UniqueType.EvadeInterception)
            if (evasion != 0) value += evasion * (offense + 2 * air) * 0.5
        }
        return value.toInt()
    }

    private fun doubleMoveValue(unitAi: UnitAiType, recon: Int, mobile: Int, offense: Int) = when (unitAi) {
        UnitAiType.EXPLORE -> 5 * (recon + mobile)
        UnitAiType.ATTACK, UnitAiType.FAST_ATTACK -> 3 * (offense + mobile)
        else -> 3 * mobile
    }
}
