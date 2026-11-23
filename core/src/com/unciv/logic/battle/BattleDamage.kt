package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.ruleset.GlobalUniques
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.TranslationCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import yairm210.purity.annotations.ReturnsNewInstance
import java.util.Collections
import java.util.WeakHashMap
import kotlin.collections.set
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

object BattleDamage {

    /**
     *  The text of the modifiers of the same unique is the same in one language, and every strength modifier of every battle estimate
     *  (the AI makes thousands a turn) asks for it: it is translated once per unique and language.
     */
    @Cache
    private val modifierStrings: MutableMap<Unique, String> = Collections.synchronizedMap(WeakHashMap())
    @Volatile
    private var modifierStringsLanguage = ""

    @Readonly @Suppress("purity") // fills the cache
    private fun getModifierStringFromUnique(unique: Unique): String {
        val language = TranslationCache.language
        if (language != modifierStringsLanguage) {
            modifierStrings.clear()
            modifierStringsLanguage = language
        }
        return modifierStrings.getOrPut(unique) { modifierStringOf(unique) }
    }

    @Readonly
    private fun modifierStringOf(unique: Unique): String {
        val source = when (unique.sourceObjectType) {
            UniqueTarget.Unit -> "Unit ability"
            UniqueTarget.Nation -> "National ability"
            UniqueTarget.Global -> GlobalUniques.getUniqueSourceDescription(unique)
            else -> "[${unique.sourceObjectName}] ([${unique.getSourceNameForUser()}])"
        }.tr()
        if (unique.modifiers.isEmpty()) return source

        val conditionalsText = unique.modifiers.joinToString { it.text.tr() }
        return "$source - $conditionalsText"
    }

    /**
     *  CvUnit::GetStrategicResourceCombatPenalty: for each resource the unit needs that the civilization has used more of than it has
     *  (available below 0), floor(missing / used x STRATEGIC_RESOURCE_EXHAUSTED_PENALTY) percent, summed and at most -50. None for barbarians.
     */
    @Readonly
    private fun getMissingResourcePenalty(combatant: MapUnitCombatant, civInfo: Civilization): Int {
        if (civInfo.isBarbarian) return 0
        if (civInfo.gameInfo.ruleset.modOptions.constants.resourceShortageLikeVp) return 0 // "Units cannot heal anymore, but strength is unaffected": ResourceShortage.cannotHeal
        val supply = civInfo.detailedCivResources // per origin: what is supplied (positive), what units and buildings use (negative)
        val civResources = civInfo.getCivResourcesByName()
        var penalty = 0
        for (resource in combatant.unit.getResourceRequirementsPerTurn().keys) {
            if (civResources[resource]!! >= 0) continue
            val total = supply.filter { it.resource.name == resource && it.amount > 0 }.sumOf { it.amount }
            val used = supply.filter { it.resource.name == resource && it.amount < 0 }.sumOf { -it.amount }
            val missing = used - total
            if (used <= 0 || missing <= 0) continue
            penalty += floor(missing.toDouble() / used * BattleConstants.MISSING_RESOURCES_MALUS).toInt()
        }
        return max(penalty, BattleConstants.MISSING_RESOURCES_MALUS)
    }

    @Readonly @ReturnsNewInstance
    private fun getGeneralModifiers(combatant: ICombatant, enemy: ICombatant, combatAction: CombatAction, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = Counter<String>()

        val conditionalState = getGameContext(combatAction, combatant, enemy)
        val civInfo = combatant.getCivInfo()

        if (combatant is MapUnitCombatant) {

            val unitUniqueModifiers = getUnitUniqueModifiers(combatant, enemy, conditionalState, tileToAttackFrom)
            modifiers.add(unitUniqueModifiers)

            val missingResourcePenalty = getMissingResourcePenalty(combatant, civInfo)
            if (missingResourcePenalty != 0) modifiers["Missing resource"] = missingResourcePenalty

            val (greatGeneralName, greatGeneralBonus) = GreatGeneralImplementation.getGreatGeneralBonus(combatant, enemy, combatAction)
            if (greatGeneralBonus != 0)
                modifiers[greatGeneralName] = greatGeneralBonus

            // CvUnit::GetMaxAttackStrength, GetMaxDefenseStrength: with Vox Populi's needs the units of an unhappy empire fight worse (CvPlayer::GetUnhappinessCombatStrengthPenalty)
            if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) {
                val unhappinessPenalty = civInfo.stats.needs.combatPenalty
                if (unhappinessPenalty != 0) modifiers["Unhappiness"] = unhappinessPenalty
            }

        } else if (combatant is CityCombatant) {
            combatant.city.forEachMatchingUnique(UniqueType.StrengthForCities, conditionalState) { unique ->
                modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
            }
        }

        if (enemy.getCivInfo().isBarbarian) {
            modifiers["Difficulty"] =
                (civInfo.gameInfo.getDifficulty().barbarianBonus * 100).toInt()
        }

        return modifiers
    }

    @Readonly
    private fun getGameContext(
        combatAction: CombatAction,
        combatant: ICombatant,
        enemy: ICombatant,
    ): GameContext {
        val attackedTile =
            if (combatAction == CombatAction.Attack) enemy.getTile()
            else combatant.getTile()

        val conditionalState = GameContext(
            combatant.getCivInfo(),
            city = (combatant as? CityCombatant)?.city,
            ourCombatant = combatant,
            theirCombatant = enemy,
            attackedTile = attackedTile,
            combatAction = combatAction
        )
        return conditionalState
    }

    @Readonly
    private fun getUnitUniqueModifiers(combatant: MapUnitCombatant, enemy: ICombatant, conditionalState: GameContext,
                                       tileToAttackFrom: Tile): Counter<String> {
        val civInfo = combatant.getCivInfo()
        val modifiers = Counter<String>()

        combatant.unit.forEachMatchingUnique(UniqueType.Strength, conditionalState, checkCivInfoUniques = true) { unique ->
            modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
        }

        // e.g., Mehal Sefari https://civilization.fandom.com/wiki/Mehal_Sefari_(Civ5)
        for (unique in combatant.getMatchingUniques(
            UniqueType.StrengthNearCapital, conditionalState, true
        )) {
            if (civInfo.cities.isEmpty() || civInfo.getCapital() == null) break
            val distance =
                combatant.getTile().aerialDistanceTo(civInfo.getCapital()!!.getCenterTile())
            // https://steamcommunity.com/sharedfiles/filedetails/?id=326411722#464287
            val effect = unique.params[0].toInt() - 3 * distance
            if (effect > 0)
                modifiers.add(getModifierStringFromUnique(unique), effect)
        }

        //https://www.carlsguides.com/strategy/civilization5/war/combatbonuses.php
        var adjacentUnits = combatant.getTile().neighbors.flatMap { it.getUnits() }
        if (enemy.getTile() !in combatant.getTile().neighbors && tileToAttackFrom in combatant.getTile().neighbors
            && enemy is MapUnitCombatant
        )
            adjacentUnits += sequenceOf(enemy.unit)

        // e.g., Maori Warrior - https://civilization.fandom.com/wiki/Maori_Warrior_(Civ5)
        val strengthMalus = adjacentUnits.filter { it.civ.isAtWarWith(combatant.getCivInfo()) }
            .flatMap { it.getMatchingUniques(UniqueType.StrengthForAdjacentEnemies) }
            .filter { combatant.matchesFilter(it.params[1]) && combatant.getTile().matchesFilter(it.params[2]) }
            .maxByOrNull { it.params[0] }
        if (strengthMalus != null) {
            modifiers.add("Adjacent enemy units", strengthMalus.params[0].toInt())
        }
        if (civInfo.gameInfo.ruleset.modOptions.constants.enemyCombatAurasLikeVp &&
            (combatant.unit.type.isLandUnit() || combatant.unit.type.isWaterUnit())) {
            val position = if (conditionalState.combatAction == CombatAction.Attack) tileToAttackFrom else combatant.getTile()
            var worst = 0
            for (tile in position.getTilesInDistance(8)) for (source in tile.getUnits()) {
                if (!source.civ.isAtWarWith(civInfo) || source.isEmbarked() ||
                    source.type.getMovementType() != combatant.unit.type.getMovementType()) continue
                var amount = 0
                var range = 0
                for (unique in source.getMatchingUniques(UniqueType.EnemyStrengthAura, checkCivInfoUniques = true)) {
                    if (!combatant.matchesFilter(unique.params[1])) continue
                    amount += unique.params[0].toInt()
                    range += unique.params[2].toInt()
                }
                if (position.aerialDistanceTo(tile) <= range) worst = minOf(worst, amount)
            }
            if (worst != 0) modifiers.add("Nearby enemy units", worst)
        }
        return modifiers
    }

    /** CvUnit::IsNearSapper: a friendly unit of the same domain with the Sapper ability, adjacent to the attacked city and within its radius of the attacker
     *  (but not on the attacker's tile), gives the attacker a bonus against that city. The best one counts. */
    @Readonly
    private fun getSapperBonus(attacker: MapUnitCombatant, city: CityCombatant): Int {
        val unit = attacker.unit
        val attackerTile = unit.getTile()
        val cityTile = city.getTile()
        var best = 0
        for (sapper in unit.civ.units.getCivUnits()) {
            if (!sapper.cache.hasSappingBonusUnique) continue
            val sapperTile = sapper.getTile()
            if (sapperTile == attackerTile || cityTile !in sapperTile.neighbors) continue
            if (sapper.baseUnit.isLandUnit != unit.baseUnit.isLandUnit || sapper.baseUnit.isWaterUnit != unit.baseUnit.isWaterUnit) continue
            val distance = sapperTile.aerialDistanceTo(attackerTile)
            for (unique in sapper.getMatchingUniques(UniqueType.SappingBonusInRadius,
                    GameContext(unit.civ, ourCombatant = attacker, theirCombatant = city, combatAction = CombatAction.Attack))) {
                if (distance <= unique.params[2].toInt() && unit.matchesFilter(unique.params[1], state = sapper.cache.state))
                    best = max(best, unique.params[0].toInt())
            }
        }
        return best
    }

    /** @param ignoreFlanking leave out the flanking bonus (CvUnit::GetMaxAttackStrength's bIgnoreUnitAdjacency): the tactical simulation adds the flanking of the units it has not moved yet itself */
    @Readonly
    fun getAttackModifiers(
        attacker: ICombatant,
        defender: ICombatant, tileToAttackFrom: Tile,
        ignoreFlanking: Boolean = false
    ): Counter<String> {
        val modifiers = getGeneralModifiers(attacker, defender, CombatAction.Attack, tileToAttackFrom)

        if (attacker is MapUnitCombatant) {

            val terrainAttackModifiers = getTerrainAttackModifiers(attacker, defender, tileToAttackFrom)
            modifiers.add(terrainAttackModifiers)

            // Air unit attacking with Air Sweep
            if (attacker.unit.isPreparingAirSweep())
                modifiers.add(getAirSweepAttackModifiers(attacker))

            if (defender is CityCombatant) {
                val sapperBonus = getSapperBonus(attacker, defender)
                if (sapperBonus != 0) modifiers["Sapper"] = sapperBonus
            }

            if (attacker.isMelee() && defender is MapUnitCombatant && !ignoreFlanking) {
                val flanking = getFlankingBonus(attacker, defender, CombatAction.Attack)
                if (flanking != 0) modifiers["Flanking"] = flanking
            }

        }

        return modifiers
    }

    @Readonly
    private fun getTerrainAttackModifiers(attacker: MapUnitCombatant, defender: ICombatant, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = Counter<String>()
        if (attacker.unit.isEmbarked() && defender.getTile().isLand
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast)
        )
            modifiers["Landing"] = BattleConstants.LANDING_MALUS

        // Land Melee Unit attacking to Water
        if (attacker.unit.type.isLandUnit() && !attacker.getTile().isWater && attacker.isMelee() && defender.getTile().isWater
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast)
        )
            modifiers["Boarding"] = BattleConstants.BOARDING_MALUS

        // Melee Unit on water attacking to Land (not City) unit
        if (!attacker.unit.type.isAirUnit() && attacker.isMelee() && attacker.getTile().isWater && !defender.getTile().isWater
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast) && !defender.isCity()
        )
            modifiers["Landing"] = BattleConstants.LANDING_MALUS

        if (isMeleeAttackingAcrossRiverWithNoBridge(attacker, tileToAttackFrom, defender))
            modifiers["Across river"] = BattleConstants.ATTACKING_ACROSS_RIVER_MALUS
        return modifiers
    }

    @Readonly
    private fun isMeleeAttackingAcrossRiverWithNoBridge(attacker: MapUnitCombatant, tileToAttackFrom: Tile, defender: ICombatant) = (
        attacker.isMelee()
            &&
            (tileToAttackFrom.aerialDistanceTo(defender.getTile()) == 1
                && tileToAttackFrom.isConnectedByRiver(defender.getTile())
                && !attacker.unit.hasUnique(UniqueType.AttackAcrossRiver))
            &&
            (!tileToAttackFrom.hasConnection(attacker.getCivInfo()) // meaning, the tiles are not road-connected for this civ
                || !defender.getTile().hasConnection(attacker.getCivInfo())
                || !attacker.getCivInfo().tech.roadsConnectAcrossRivers)
        )

    @Readonly
    fun getAirSweepAttackModifiers(
        attacker: ICombatant
    ): Counter<String> {
        val modifiers = Counter<String>()

        if (attacker is MapUnitCombatant) {
            attacker.unit.forEachMatchingUnique(UniqueType.StrengthWhenAirsweep) { unique ->
                modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
            }
        }

        return modifiers
    }

    @Readonly
    fun getDefenceModifiers(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile, ignoreFlanking: Boolean = false): Counter<String> {
        val modifiers = getGeneralModifiers(defender, attacker, CombatAction.Defend, tileToAttackFrom)
        val tile = defender.getTile()

        // Ranged attacks ignore flanking (GetMaxDefenseStrength passes bIgnoreUnitAdjacency = bFromRangedAttack)
        if (defender is MapUnitCombatant && attacker is MapUnitCombatant && !attacker.isRanged() && !ignoreFlanking) {
            val flanking = getFlankingBonus(defender, attacker, CombatAction.Defend, tileToAttackFrom)
            if (flanking != 0) modifiers["Flanking"] = flanking
        }

        if (defender is MapUnitCombatant && !defender.unit.isEmbarked()) { // Embarked units get no terrain defensive bonuses

            val tileDefenceBonus = tile.getDefensiveBonus(unit = defender.unit)
            if (!defender.unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true) && tileDefenceBonus > 0
                || !defender.unit.hasUnique(UniqueType.NoDefensiveTerrainPenalty, checkCivInfoUniques = true) && tileDefenceBonus < 0
            )
                modifiers["Tile"] = (tileDefenceBonus * 100).toInt()


            // CvUnit::fortifyModifier: a unit with ranged support fire (the Impi) gets no bonus for being fortified
            if ((defender.unit.isFortified() || defender.unit.isGuarding())
                && !(defender.unit.civ.gameInfo.ruleset.modOptions.constants.rangedSupportFireUnitsGetNoFortification
                    && defender.unit.hasUnique(UniqueType.ExtraRangedAttack)))
                modifiers["Fortification"] = BattleConstants.FORTIFICATION_BONUS * defender.unit.getFortificationTurns()
        }

        return modifiers
    }

    /**
     *  CvUnit::GetGenericMaxStrengthModifier, "Flanking": BONUS_PER_ADJACENT_FRIEND for each combat unit next to the [opponent] that is at war
     *  with it, is on the same domain, is not embarked and is not the [unit] itself (CvUnit::GetNumEnemyUnitsAdjacent) - ranged units and units
     *  of allies count too, and the defender gets the bonus as well - times the unit's FlankAttackBonus. Zero against a city.
     *  @param opponentTile where the opponent stands
     */
    @Readonly
    private fun getFlankingBonus(unit: MapUnitCombatant, opponent: MapUnitCombatant, action: CombatAction, opponentTile: Tile = opponent.getTile()): Int {
        val opponentCiv = opponent.getCivInfo()
        val domain = opponent.unit.type.getMovementType()
        // CvPlot::GetNumEnemyUnitsAdjacent with bConsiderFlanking: each unit adds CvUnit::GetFlankPower, 1 plus its `extra flanking power` (flankPowerLikeVp)
        val flankPower = unit.unit.civ.gameInfo.ruleset.modOptions.constants.flankPowerLikeVp
        val friends = opponentTile.neighbors.sumOf { neighbor ->
            neighbor.getUnits().sumOf {
                if (it != unit.unit && it.isMilitary() && !it.isEmbarked() && it.civ.isAtWarWith(opponentCiv) && it.type.getMovementType() == domain)
                    (if (flankPower) 1 + it.cache.extraFlankPower else 1)
                else 0
            }
        }
        if (friends <= 0) return 0
        var flankingBonus = BattleConstants.BASE_FLANKING_BONUS
        // e.g., Discipline policy - https://civilization.fandom.com/wiki/Discipline_(Civ5)
        unit.unit.forEachMatchingUnique(UniqueType.FlankAttackBonus, checkCivInfoUniques = true,
                gameContext = getGameContext(action, unit, opponent)) { unique ->
            flankingBonus *= unique.params[0].toPercent()
        }
        return (flankingBonus * friends).toInt()
    }

    /** The sum of the modifiers as a factor; a unit never drops below 10% of its strength (CvUnit::GetMaxAttackStrength: "can't drop below 10%") */
    @Readonly
    private fun modifiersToFinalBonus(modifiers: Counter<String>): Float {
        var finalModifier = 1f
        for (modifierValue in modifiers.values) finalModifier += modifierValue / 100f
        return max(finalModifier, 0.1f)
    }

    /** The original truncates the damage to whole hit points and deals at least 1 (CvUnit::getCombatDamage and the ranged and air versions);
     *  a city's strike and its defense against a bombing mission deal at least MIN_CITY_STRIKE_DAMAGE (CvCity::rangeCombatDamage, GetAirStrikeDefenseDamage) */
    @Pure
    private fun damageToInt(damage: Float, minimum: Int = 1): Int = max(damage.toInt(), minimum)

    /** @param fullHealthScale damage is proportional to the health left, as in the original's air strikes (GetAirCombatDamage,
     *  GetAirStrikeDefenseDamage, GetInterceptionDamage - a city's too); otherwise each 3 points of health lost reduce it by 1%,
     *  and a city is not weakened at all (GetCombatDamage) */
    @Readonly
    private fun getHealthDependantDamageRatio(combatant: ICombatant, fullHealthScale: Boolean = false): Float {
        if (combatant.getCivInfo().gameInfo.ruleset.modOptions.constants.combatHealthStrengthLikeVp) return 1f
        if (fullHealthScale) return combatant.getHealth().toFloat() / combatant.getMaxHealth()
        return if (combatant !is MapUnitCombatant
            || combatant.unit.hasUnique(UniqueType.NoDamagePenaltyWoundedUnits, checkCivInfoUniques = true)
        ) 1f
        else {
            // The multiplier is WOUNDED_DAMAGE_MULTIPLIER plus the owner's WoundedUnitDamageMod, never below 0
            // (CvUnit.cpp:2796-2802, strength reduction 9851-9862, projected damage 10893-10899)
            var multiplier = BattleConstants.WOUNDED_DAMAGE_MULTIPLIER
            combatant.unit.forEachMatchingUnique(UniqueType.WoundedUnitDamagePenalty, checkCivInfoUniques = true) { multiplier += it.params[0].toInt() }
            // A third of the lost health, in whole numbers: WOUNDED_DAMAGE_MULTIPLIER is 33% (CvUnit::getCombatDamage); the lost health is
            // the damage of the unit, so with unitMaxHitPointsLikeVp it is counted from the unit's own maximum, not from 100
            (100 - (combatant.getMaxHealth() - combatant.getHealth()) * max(multiplier, 0) / 100) / 100f
        }
    }


    /**
     * Includes attack modifiers
     */
    @Readonly
    fun getAttackingStrength(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile,
        ignoreFlanking: Boolean = false
    ): Float {
        val modifiers = Counter<String>()
        modifiers.add(getAttackModifiers(attacker, defender, tileToAttackFrom, ignoreFlanking))
        val healthPenalty = vpHealthStrengthPenalty(attacker, false)
        if (healthPenalty != 0) modifiers.add("Wounded", healthPenalty)
        val attackModifier = modifiersToFinalBonus(modifiers)
        val strength = max(1f, attacker.getAttackingStrength(defender) * attackModifier)
        // CvUnitCombat::GenerateMeleeCombatInfo: Heavy Charge, 150% after all the modifiers, against a unit that cannot fall back
        return if (attacker is MapUnitCombatant && attacker.unit.cache.hasHeavyChargeUnique && MeleeFallBack.isHeavyChargeBoosted(attacker, defender)) strength * 1.5f else strength
    }


    /**
     * Includes defence modifiers
     */
    @Readonly
    fun getDefendingStrength(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile, ignoreFlanking: Boolean = false): Float {
        val modifiers = Counter<String>()
        modifiers.add(getDefenceModifiers(attacker, defender, tileToAttackFrom, ignoreFlanking))
        val healthPenalty = vpHealthStrengthPenalty(defender, attacker.isRanged())
        if (healthPenalty != 0) modifiers.add("Wounded", healthPenalty)
        val defenceModifier = modifiersToFinalBonus(modifiers)
        return max(1f, defender.getDefendingStrength(attacker) * defenceModifier)
    }

    /** VP 5.4.8 CvUnit::GetDamageCombatModifier; use the combatant's overlay HP, never the live unit HP. */
    @Readonly
    private fun vpHealthStrengthPenalty(combatant: ICombatant, rangedDefence: Boolean): Int {
        if (combatant !is MapUnitCombatant ||
            !combatant.getCivInfo().gameInfo.ruleset.modOptions.constants.combatHealthStrengthLikeVp) return 0
        val lostHealth = (combatant.getMaxHealth() - combatant.getHealth()).coerceAtLeast(0)
        if (lostHealth == 0) return 0
        // CvUnit::GetDamageCombatModifier: the player modifier is a flat bonus while wounded,
        // independent of the normal health-scaled penalty and of ranged-defence immunity.
        var modifier = 0
        combatant.unit.forEachMatchingUnique(UniqueType.WoundedUnitDamagePenalty, checkCivInfoUniques = true) {
            modifier -= it.params[0].toInt()
        }
        val woundAmount = lostHealth * BattleConstants.WOUNDED_DAMAGE_MULTIPLIER / 100
        if (combatant.unit.hasUnique(UniqueType.StrongerWhenDamaged, checkCivInfoUniques = true)) return modifier + woundAmount
        if (!rangedDefence && !combatant.unit.hasUnique(UniqueType.NoDamagePenaltyWoundedUnits, checkCivInfoUniques = true))
            modifier -= woundAmount
        return modifier
    }
    
    @Readonly
    fun getRandomness(combatant: ICombatant): Float = 
        Random(combatant.getCivInfo().gameInfo.turns
                * combatant.getTile().position.toVector2().hashCode().toLong()).nextFloat()

    /** A bombing mission: an air unit attacking something that is not an air unit. The original resolves it separately
     *  (CvUnitCombat::GenerateAirCombatInfo) and scales damage with the full health of the one dealing it. */
    @Readonly
    fun isAirStrike(attacker: ICombatant, defender: ICombatant) = attacker.isAirUnit() && !defender.isAirUnit()

    /**
     *  With `airDefenseLikeVp`: the damage a unit or a city does to an air unit that bombs it (CvUnitCombat::GenerateAirCombatInfo, the damage the attacker takes),
     *  as MOD_BALANCE_AIR_UNIT_CHANGES (on in VP) has CvUnit::GetAirStrikeDefenseDamage and CvCity::GetAirStrikeDefenseDamage compute it. A unit: its [UniqueType.AirDefense]
     *  (Units.BaseLandAirDefense plus the promotions' LandAirDefenseBonus), times 100 plus the attacker's interception damage modifier (negative when good, at least 0) over 100,
     *  plus 0-4 (randRangeExclusive(0, 5), from [randomnessFactor]), times 100 plus the attacker's damage taken modifier over 100. A city: the sum of its [UniqueType.AirDefense]
     *  (Buildings.CityAirStrikeDefense), changed by the attacker's modifier, plus 0-9. Neither depends on health or strength, and a city has no minimum damage here.
     *  The original also adds the attacker's `getChangeDamageValue` and ends the fight at `getForcedDamageValue`; Unciv has neither.
     */
    @Readonly
    fun airStrikeDefenseDamage(attacker: MapUnitCombatant, defender: ICombatant, randomnessFactor: Float): Int {
        val modifier = attacker.unit.interceptionDefenseDamageModifier()
        if (defender is CityCombatant) {
            var damage = defender.city.getMatchingUniques(UniqueType.AirDefense).sumOf { it.params[0].toInt() }
            if (modifier != 0) damage = damage * (100 + modifier) / 100
            return max(0, damage + (10 * randomnessFactor).toInt().coerceIn(0, 9))
        }
        val unit = (defender as? MapUnitCombatant)?.unit ?: return 0
        var damage = unit.getMatchingUniques(UniqueType.AirDefense).sumOf { it.params[0].toInt() }
        damage = damage * max(0, 100 + modifier) / 100
        damage += (5 * randomnessFactor).toInt().coerceIn(0, 4)
        damage = damage * (100 + damageTakenModifier(attacker)) / 100
        return max(0, damage)
    }

    @Readonly
    fun calculateDamageToAttacker(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        /** Between 0 and 1. */
        randomnessFactor: Float = getRandomness(attacker),
        fullHealthScale: Boolean = isAirStrike(attacker, defender),
        ignoreFlanking: Boolean = false
    ): Int {
        if (attacker.isRanged() && !attacker.isAirUnit()) return 0
        val constants = attacker.getCivInfo().gameInfo.ruleset.modOptions.constants
        if (constants.airDefenseLikeVp && attacker is MapUnitCombatant && isAirStrike(attacker, defender))
            return airStrikeDefenseDamage(attacker, defender, randomnessFactor)
        if (defender.isCivilian()) return 0
        val attackStrength = getAttackingStrength(attacker, defender, tileToAttackFrom, ignoreFlanking)
        val defenceStrength = getDefendingStrength(attacker, defender, tileToAttackFrom, ignoreFlanking)
        val minimum = if (defender is CityCombatant && isAirStrike(attacker, defender)) BattleConstants.MIN_CITY_STRIKE_DAMAGE else 1
        if (constants.combatHealthStrengthLikeVp) {
            val damage = constants.combatDamage
            var percent = if (defender is CityCombatant && attacker.isMelee()) damage.cityMeleeRetaliationPercent else 100
            percent += damageTakenModifier(attacker)
            if (defender is CityCombatant) percent -= cityAssaultReduction(attacker)
            return vpDamageMath((defenceStrength * 100).toInt(), (attackStrength * 100).toInt(),
                randomnessFactor, damage.meleeBase, damage.meleeRandom, percent)
        }
        return damageToInt(damageModifier(attackStrength / defenceStrength, true, randomnessFactor) * getHealthDependantDamageRatio(defender, fullHealthScale), minimum)
    }

    @Readonly
    fun calculateDamageToDefender(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        /** Between 0 and 1.  Defaults to turn and location-based random to avoid save scumming */
        randomnessFactor: Float = getRandomness(defender),
        /** Damage follows the full health of the attacker instead of a third of its health loss: bombing missions and interceptions */
        fullHealthScale: Boolean = isAirStrike(attacker, defender),
        ignoreFlanking: Boolean = false
    ): Int {
        var damage = rawDamageToDefender(attacker, defender, tileToAttackFrom, randomnessFactor, fullHealthScale, ignoreFlanking)
        if (defender is CityCombatant) damage = (damage - defender.getFlatDamageReduction()).coerceAtLeast(0)
        if (defender !is CityCombatant || !sharesGarrisonDamage(attacker, defender)) return damage
        val garrisonMaxHealth = defender.city.getGarrison()?.getMaxHealth() ?: 0 // CvUnit::GetRangeCombatDamage: iGarrisonMaxHP
        return damage - garrisonShare(damage, defender.getMaxHealth(), garrisonMaxHealth)
    }

    /** VP 5.4.8 CvUnit::getMeleeCombatDamageCity / GetRangeCombatDamage: split before capping to current HP.
     * [garrisonMaxHealth] is null for the live garrison, or its hypothetical maximum HP (zero when absent) in a simulation. */
    @Readonly
    fun calculateDamageToDefenderAndGarrison(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        randomnessFactor: Float = getRandomness(defender),
        fullHealthScale: Boolean = isAirStrike(attacker, defender),
        ignoreFlanking: Boolean = false,
        garrisonMaxHealth: Int? = null,
    ): Pair<Int, Int> {
        var damage = rawDamageToDefender(attacker, defender, tileToAttackFrom, randomnessFactor, fullHealthScale, ignoreFlanking)
        if (defender is CityCombatant) damage = (damage - defender.getFlatDamageReduction()).coerceAtLeast(0)
        if (defender !is CityCombatant || !sharesGarrisonDamage(attacker, defender)) return damage to 0
        val maxHealth = garrisonMaxHealth ?: defender.city.getGarrison()?.getMaxHealth() ?: 0
        val share = garrisonShare(damage, defender.getMaxHealth(), maxHealth)
        return damage - share to share
    }

    @Readonly
    private fun rawDamageToDefender(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile,
        randomnessFactor: Float, fullHealthScale: Boolean, ignoreFlanking: Boolean): Int {
        val constants = attacker.getCivInfo().gameInfo.ruleset.modOptions.constants
        if (defender.isCivilian()) return if (constants.combatHealthStrengthLikeVp && attacker.isRanged())
            constants.combatDamage.rangedCivilianDamage else BattleConstants.DAMAGE_TO_CIVILIAN_UNIT
        val attackStrength = getAttackingStrength(attacker, defender, tileToAttackFrom, ignoreFlanking)
        val defenceStrength = getDefendingStrength(attacker, defender, tileToAttackFrom, ignoreFlanking)
        val minimum = if (attacker is CityCombatant) BattleConstants.MIN_CITY_STRIKE_DAMAGE else 1
        if (constants.combatHealthStrengthLikeVp) {
            val damage = constants.combatDamage
            val base = if (attacker.isRanged()) damage.rangedBase else damage.meleeBase
            val random = if (attacker.isRanged()) damage.rangedRandom else damage.meleeRandom
            var percent = if (defender is CityCombatant && attacker.isMelee()) damage.meleeCityDamagePercent else 100
            if (attacker is CityCombatant) percent -= cityAssaultReduction(defender)
            else if (attacker.isMelee()) percent += damageTakenModifier(defender)
            val result = vpDamageMath((attackStrength * 100).toInt(), (defenceStrength * 100).toInt(),
                randomnessFactor, base, random, percent)
            return if (attacker.isRanged() && attacker !is CityCombatant && defender is MapUnitCombatant)
                (result * (100 + damageTakenModifier(defender)) / 100).coerceAtLeast(0) else result
        }
        return damageToInt(damageModifier(attackStrength / defenceStrength, false, randomnessFactor) * getHealthDependantDamageRatio(attacker, fullHealthScale), minimum)
    }

    @Readonly
    private fun damageTakenModifier(combatant: ICombatant): Int = if (combatant is MapUnitCombatant)
        combatant.unit.getMatchingUniques(UniqueType.DamageTakenModifier, checkCivInfoUniques = true).sumOf { it.params[0].toInt() } else 0

    @Readonly
    private fun cityAssaultReduction(combatant: ICombatant): Int = if (combatant is MapUnitCombatant)
        combatant.unit.getMatchingUniques(UniqueType.CityAssaultDamageReduction, checkCivInfoUniques = true).sumOf { it.params[0].toInt() } else 0

    @Readonly
    private fun sharesGarrisonDamage(attacker: ICombatant, city: CityCombatant): Boolean =
        city.getCivInfo().gameInfo.ruleset.modOptions.constants.cityCombatLikeVp &&
            !(attacker.isRanged() && attacker is MapUnitCombatant && attacker.hasUnique(UniqueType.IgnoresGarrisonAbsorption))

    @Pure
    private fun garrisonShare(damage: Int, cityMaxHealth: Int, garrisonMaxHealth: Int): Int {
        if (garrisonMaxHealth <= 0) return 0
        return (damage.toLong() * 2 * garrisonMaxHealth / (cityMaxHealth.toLong() + 2L * garrisonMaxHealth)).toInt()
    }

    /** A melee attack on a unit rolls both damages up front and applies both (CvUnitCombat::GenerateMeleeCombatInfo). If that would kill
     *  both units, the one that has taken less damage in total - the damage it already had plus what it would take - survives with
     *  1 HP; on a tie the defender survives.
     *  @return the damage to the defender and the damage to the attacker after that correction */
    @Readonly
    fun resolveMeleeDeaths(attacker: ICombatant, defender: ICombatant, damageToDefender: Int, damageToAttacker: Int): Pair<Int, Int> {
        val attackerHealth = attacker.getHealth()
        val defenderHealth = defender.getHealth()
        if (damageToDefender < defenderHealth || damageToAttacker < attackerHealth) {
            // VP 5.4.8 CvUnit::getMeleeCombatDamage: the side killed cannot inflict its full retaliation.
            if (!attacker.isAirUnit() && attacker.getCivInfo().gameInfo.ruleset.modOptions.constants.combatHealthStrengthLikeVp) {
                if (damageToDefender >= defenderHealth && damageToDefender > 0)
                    return damageToDefender to damageToAttacker * defenderHealth / damageToDefender
                if (damageToAttacker >= attackerHealth && damageToAttacker > 0)
                    return damageToDefender * attackerHealth / damageToAttacker to damageToAttacker
            }
            return damageToDefender to damageToAttacker
        }
        val totalOnDefender = damageToDefender + defender.getMaxHealth() - defenderHealth
        val totalOnAttacker = damageToAttacker + attacker.getMaxHealth() - attackerHealth
        return if (totalOnDefender > totalOnAttacker) damageToDefender to attackerHealth - 1
        else defenderHealth - 1 to damageToAttacker
    }

    /** (max, min) bonus damage dealt to [defender] from an additional [UniqueType.ExtraRangedAttack] - `(0, 0)` if not applicable. */
    @Readonly
    fun getExtraRangedAttackBonusDamage(attacker: MapUnitCombatant, defender: ICombatant, tileToAttackFrom: Tile): Pair<Int, Int> {
        var maxExtra = 0
        var minExtra = 0
        for (fakeAttacker in Battle.getExtraRangedAttackFakeUnits(attacker)) {
            maxExtra += calculateDamageToDefender(fakeAttacker, defender, tileToAttackFrom, 1f)
            minExtra += calculateDamageToDefender(fakeAttacker, defender, tileToAttackFrom, 0f)
        }
        return maxExtra to minExtra
    }

    /** VP 5.4.8 CvUnitCombat::DoDamageMath, strengths and intermediate damage in hundredths. */
    @Pure
    fun vpDamageMath(attackerStrength: Int, defenderStrength: Int, randomnessFactor: Float,
        base: Int = 2400, random: Int = 1200, percent: Int = 100): Int {
        val stronger = max(attackerStrength, defenderStrength).toDouble()
        val weaker = max(1, minOf(attackerStrength, defenderStrength))
        val ratio = minOf(1000.0, (((stronger / weaker + 3.0) / 4.0).pow(4) + 1.0) / 2.0)
        val adjustedRatio = if (defenderStrength > attackerStrength) 1.0 / ratio else ratio
        val baseDamage = base + (random * randomnessFactor).toInt()
        return ((baseDamage * adjustedRatio).toInt().toLong() * percent / 100 / 100).toInt().coerceAtLeast(0)
    }

    @Pure
    private fun damageModifier(
        attackerToDefenderRatio: Float,
        damageToAttacker: Boolean,
        /** Between 0 and 1. */
        randomnessFactor: Float,
    ): Float {
        // https://forums.civfanatics.com/threads/getting-the-combat-damage-math.646582/#post-15468029
        val strongerToWeakerRatio =
            attackerToDefenderRatio.pow(if (attackerToDefenderRatio < 1) -1 else 1)
        var ratioModifier = (((strongerToWeakerRatio + 3) / 4).pow(4) + 1) / 2
        if (damageToAttacker && attackerToDefenderRatio > 1 || !damageToAttacker && attackerToDefenderRatio < 1) // damage ratio from the weaker party is inverted
            ratioModifier = ratioModifier.pow(-1)
        val randomCenteredAround30 = 24 + 12 * randomnessFactor
        return randomCenteredAround30 * ratioModifier
    }
}
enum class CombatAction {
    Attack,
    Defend,
    Intercept,
}
