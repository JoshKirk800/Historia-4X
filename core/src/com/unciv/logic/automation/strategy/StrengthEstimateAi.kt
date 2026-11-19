package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 *  Vox Populi's CvDiplomacyAI::DoUpdatePlayerStrengthEstimates with ComputeDynamicStrengthModifier, ComputeRatingStrengthAdjustment and
 *  AdjustThirdPartyStrength (CvDiplomacyAI.cpp, Release-5.4.8; D7). For every living player we have met it estimates the economic strength
 *  (their economic might against ours), the military strength (their attack against our defense: units with the dynamic combat
 *  modifiers and the military rating's adjustment, nuclear might against the other's units and shelters, war allies' backup, our cities
 *  and the early game's caution) and the target value (their defense - their units, economy, cities and backup - against our attack),
 *  each also as the raw value without the boldness bias. The edge cases with no cities and with nuclear weapons end in Immense and
 *  Impossible or Pathetic and Cakewalk before any of that. Run by [WarStateAi.doTurn] and when two players meet.
 *
 *  Inputs and what stands for them in Unciv:
 *  - GetMilitaryMight, GetNuclearMight, calculateNuclearMight (shelters of the target): [CivAi.militaryMight], [CivAi.nuclearMight],
 *    [NuclearMight.of]; GetEconomicMight: [WarStateAi.economicMight]; CvCity::GetPower: [WarStateAi.cityPower].
 *  - War allies: [WarAlliesAi.warAllies] (no vassals, D3). VP fills its list of players while the loop runs (vValidPlayers, line 11122), so
 *    an ally counts only when it comes before the evaluated player in player order; kept as VP has it ([GameInfo.civilizations] order).
 *  - Traits: GetCityStateCombatModifier (the "[x]% Strength <vs [City-States]>" unique of the nation), GetCombatBonusVsLargerCiv (the
 *    nation's "when fighting units from a Civilization with more Cities than you"), GetGreatGeneralExtraBonus (the nation's "Great General
 *    provides double combat bonus": the extra is the general's own bonus). GetCombatBonusVsHigherPop has no Unciv unique and counts as 0;
 *    a general counts when its tile is visible to us (VP also asks if the unit is invisible or dying: no input).
 *  - CanCrossOcean: embarked units may enter the ocean; IsInTerribleShapeForWar: [WarStateAi.isInTerribleShapeForWar] (last turn's flag, as VP
 *    reads it); getNumMilitaryUnits: [com.unciv.logic.civilization.managers.UnitManager.getNumUnitsToSupply]; getNumNukeUnits: units with
 *    the nuclear weapon unique; humans of ISHUMAN_AI_UNITS, ISHUMAN_AI_DIPLOMACY and ISHUMAN_HANDICAP: human civilizations.
 *  - GetMilitaryRating, GetStartingMilitaryRating, GetMinimumHumanMilitaryRating: [WarDamageAi]; the handicap's HumanStrengthPerceptionMod:
 *    the game's difficulty row of AI.json.
 */
object StrengthEstimateAi {

    //region Levels from ratios

    private fun levelFromRatio(ratio: Int, rules: AiRuleset, immense: AiDefine, powerful: AiDefine, strong: AiDefine, average: AiDefine,
                               poor: AiDefine, weak: AiDefine): StrengthLevel = when {
        ratio >= rules.define(immense) -> StrengthLevel.Immense
        ratio >= rules.define(powerful) -> StrengthLevel.Powerful
        ratio >= rules.define(strong) -> StrengthLevel.Strong
        ratio >= rules.define(average) -> StrengthLevel.Average
        ratio >= rules.define(poor) -> StrengthLevel.Poor
        ratio >= rules.define(weak) -> StrengthLevel.Weak
        else -> StrengthLevel.Pathetic
    }

    /** CvDiplomacyAI::MilitaryStrengthFromRatio */
    fun militaryStrengthFromRatio(ratio: Int, rules: AiRuleset) = levelFromRatio(ratio, rules,
        AiDefine.MILITARY_STRENGTH_IMMENSE_THRESHOLD, AiDefine.MILITARY_STRENGTH_POWERFUL_THRESHOLD, AiDefine.MILITARY_STRENGTH_STRONG_THRESHOLD,
        AiDefine.MILITARY_STRENGTH_AVERAGE_THRESHOLD, AiDefine.MILITARY_STRENGTH_POOR_THRESHOLD, AiDefine.MILITARY_STRENGTH_WEAK_THRESHOLD)

    /** CvDiplomacyAI::EconomicStrengthFromRatio */
    fun economicStrengthFromRatio(ratio: Int, rules: AiRuleset) = levelFromRatio(ratio, rules,
        AiDefine.ECONOMIC_STRENGTH_IMMENSE_THRESHOLD, AiDefine.ECONOMIC_STRENGTH_POWERFUL_THRESHOLD, AiDefine.ECONOMIC_STRENGTH_STRONG_THRESHOLD,
        AiDefine.ECONOMIC_STRENGTH_AVERAGE_THRESHOLD, AiDefine.ECONOMIC_STRENGTH_POOR_THRESHOLD, AiDefine.ECONOMIC_STRENGTH_WEAK_THRESHOLD)

    /** CvDiplomacyAI::TargetValueFromRatio */
    fun targetValueFromRatio(ratio: Int, rules: AiRuleset): TargetValue = when {
        ratio >= rules.define(AiDefine.TARGET_IMPOSSIBLE_THRESHOLD) -> TargetValue.Impossible
        ratio >= rules.define(AiDefine.TARGET_BAD_THRESHOLD) -> TargetValue.Bad
        ratio >= rules.define(AiDefine.TARGET_DIFFICULT_THRESHOLD) -> TargetValue.Difficult
        ratio >= rules.define(AiDefine.TARGET_AVERAGE_THRESHOLD) -> TargetValue.Average
        ratio >= rules.define(AiDefine.TARGET_FAVORABLE_THRESHOLD) -> TargetValue.Favorable
        ratio >= rules.define(AiDefine.TARGET_SOFT_THRESHOLD) -> TargetValue.Soft
        else -> TargetValue.Cakewalk
    }
    //endregion

    //region Helpers

    /** CvDiplomacyAI::ApplyPercentageModifier */
    private fun percent(value: Int, percent: Int): Int = (value.toLong() * percent / 100).toInt()

    private fun ratio(numerator: Int, multiplier: Int, denominator: Int): Int = (numerator.toLong() * multiplier / max(denominator, 1)).toInt()

    /** max(iBase + GetMilitaryMight - GetNuclearMight, 1): the strength of a player's units without its nuclear weapons, added later */
    private fun unitStrength(civ: Civilization, rules: AiRuleset): Int =
        max(rules.define(AiDefine.MILITARY_STRENGTH_BASE) + civ.ai.militaryMight() - civ.ai.nuclearMight(), 1)

    private fun economicStrengthOf(civ: Civilization): Int = if (civ.cities.isNotEmpty()) max(WarStateAi.economicMight(civ), 1) else 1

    private fun numNukes(civ: Civilization): Int = civ.units.getCivUnits().count { it.isNuclearWeapon() }

    private fun numMilitaryUnits(civ: Civilization): Int = civ.units.getNumUnitsToSupply()

    private fun canCrossOcean(civ: Civilization) = civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean

    private fun isClose(civ: Civilization, other: Civilization) =
        WarStateAi.proximityRank(civ.getProximity(other)) >= WarStateAi.proximityRank(Proximity.Close)

    /** A civilization's defense in cities: their power by the hit points left, at MILITARY_STRENGTH_CITY_MOD % */
    private fun cityDefense(civ: Civilization, rules: AiRuleset): Int {
        var defense = 0
        for (city in civ.cities) {
            val maxHealth = city.getMaxHealth()
            val power = (WarStateAi.cityPower(city).toLong() * city.health / max(maxHealth, 1)).toInt()
            defense += power * rules.define(AiDefine.MILITARY_STRENGTH_CITY_MOD) / 100
        }
        return defense
    }

    /** Whether an AI judges a human [player] by more than its rating (VP's clause for a human with nuclear weapons or one in good shape
     *  and not winning against [against]) */
    private fun isStrongHuman(player: Civilization, against: Civilization): Boolean {
        if (numNukes(player) > 0) return true
        val state = against.ai.warStates.warStates[player.civID]
        return !WarStateAi.isInTerribleShapeForWar(player) && (state == null || state < WarState.Offensive)
    }
    //endregion

    //region ComputeDynamicStrengthModifier, ComputeRatingStrengthAdjustment

    private fun nationStrengthBonus(civ: Civilization, matches: (Unique) -> Boolean): Int =
        civ.nation.uniqueObjects.filter { it.type == UniqueType.Strength && matches(it) }.sumOf { it.params[0].toInt() }

    /**
     *  CvDiplomacyAI::ComputeDynamicStrengthModifier: the combat bonuses of a major [player] that depend on who it is up against ([against]):
     *  against a city-state (Rome, Mongolia), with fewer cities (Ethiopia), with a Great General close to a city of either side when the
     *  nation doubles its effect (Sweden, China), seen by [viewer]; in % of [strength].
     */
    fun dynamicStrengthModifier(viewer: Civilization, player: Civilization, against: Civilization, strength: Int): Int {
        if (!player.isMajorCiv) return strength
        var modifier = 100
        if (against.isCityState) modifier += GrandStrategyAi.cityStateCombatModifier(player)
        if (player.cities.size < against.cities.size)
            modifier += nationStrengthBonus(player) { u -> u.modifiers.any { it.type == UniqueType.ConditionalVsLargerCiv } }
        if (player.hasUnique(UniqueType.GreatGeneralProvidesDoubleCombatBonus)) modifier += greatGeneralExtraBonus(viewer, player, against)
        return percent(strength, modifier)
    }

    /** The doubling of a Great General's bonus (GetGreatGeneralExtraBonus) while one of [player]'s generals is close to a city of
     *  [player] or [against] and visible to [viewer]; 0 without one */
    private fun greatGeneralExtraBonus(viewer: Civilization, player: Civilization, against: Civilization): Int {
        val range = player.gameInfo.ruleset.ai.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        for (unit in player.units.getCivUnits()) {
            if (!unit.isGreatPersonOfType("War")) continue
            val bonus = unit.getMatchingUniques(UniqueType.StrengthBonusInRadius).firstOrNull()?.params?.get(0)?.toIntOrNull() ?: continue
            val tile = unit.getTile()
            if (player != viewer && !tile.isVisible(viewer)) continue
            val close = player.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= range } ||
                against.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= range }
            if (close) return bonus
        }
        return 0
    }

    /**
     *  CvDiplomacyAI::ComputeRatingStrengthAdjustment: [strength] of a major [player] scaled by how its military rating compares with the
     *  average of the other majors [viewer] has met (with cities): +% above, capped at MILITARY_RATING_MAXIMUM_BONUS, -% below, capped at
     *  MILITARY_RATING_MAXIMUM_PENALTY. An AI judging a human takes the larger of that and the difficulty's HumanStrengthPerceptionMod when
     *  the human has the starting rating or more or plays well, and a share of it while it has lost less than a fifth of the starting rating.
     */
    fun ratingStrengthAdjustment(viewer: Civilization, player: Civilization, against: Civilization, strength: Int): Int {
        if (!player.isMajorCiv) return strength
        val rules = viewer.gameInfo.ruleset.ai
        var totalRating = 0
        var numCivs = 0
        for (other in viewer.gameInfo.civilizations) {
            if (other == player || (other != viewer && !viewer.knows(other))) continue
            if (other.isAlive() && other.isMajorCiv && other.cities.isNotEmpty()) {
                totalRating += WarDamageAi.militaryRating(other)
                numCivs++
            }
        }
        if (numCivs == 0) return strength
        // One other civ only, and we are the one judged: nothing to compare with (VP avoids counting it twice)
        if (numCivs == 1 && viewer == player) return strength

        val civRating = WarDamageAi.militaryRating(player)
        val averageRating = totalRating / numCivs
        val percentageDifference = abs((civRating * 100 - averageRating * 100) / max(averageRating, 1))
        var skillMultiplier = 100
        if (civRating > averageRating) skillMultiplier = min(100 + percentageDifference, max(rules.define(AiDefine.MILITARY_RATING_MAXIMUM_BONUS), 0) + 100)
        else if (civRating < averageRating)
            skillMultiplier = max(100 - percentageDifference, max(rules.define(AiDefine.MILITARY_RATING_MAXIMUM_PENALTY), -100) + 100)
        var adjustment = skillMultiplier

        if (!viewer.isHuman() && player.isHuman() && isStrongHuman(player, against)) {
            val startingRating = WarDamageAi.startingMilitaryRating(player)
            val buffer = rules.define(AiDefine.MILITARY_RATING_HUMAN_BUFFER_VALUE_PERCENT)
            val minimumHumanRating = startingRating * buffer.coerceIn(0, 100) / 100
            val humanMultiplier = max(0, rules.handicapValue(viewer.gameInfo.difficulty, "HumanStrengthPerceptionMod") ?: 0)
            if (skillMultiplier >= 100 || civRating >= startingRating || startingRating == 0) {
                adjustment = 100 + max(humanMultiplier, skillMultiplier - 100)
            } else if (civRating >= minimumHumanRating && buffer < 100) {
                val maxPercentageLost = 100 - max(buffer, 0)
                val percentageLost = (civRating * 100 - startingRating * 100) / -startingRating
                val difference = (100 + humanMultiplier) - skillMultiplier
                var finalMultiplier = 100 + humanMultiplier - (difference * percentageLost / maxPercentageLost)
                // Do not exceed the human strength mod
                if (finalMultiplier - skillMultiplier > humanMultiplier) finalMultiplier = skillMultiplier + humanMultiplier
                adjustment = finalMultiplier
            }
        }
        return percent(strength, adjustment)
    }

    /** [strength] of [player] as [viewer] perceives it against [against]: the dynamic modifiers, then the rating's adjustment */
    private fun perceived(viewer: Civilization, player: Civilization, against: Civilization, strength: Int): Int =
        ratingStrengthAdjustment(viewer, player, against, dynamicStrengthModifier(viewer, player, against, strength))
    //endregion

    //region AdjustThirdPartyStrength

    private fun backupPercent(rules: AiRuleset, strength: StrengthLevel, thirdParty: Civilization, military: Boolean): Int {
        val prefix = (if (military) "MILITARY_STRENGTH_" else "TARGET_") + (if (thirdParty.isCityState) "MINOR" else "MAJOR") + "_BACKUP_"
        return rules.define(AiDefine.valueOf(prefix + strength.name.uppercase()))
    }

    /**
     *  CvDiplomacyAI::AdjustThirdPartyStrength: the attack and defense value [thirdParty] brings its ally [thirdPartyAlly] against
     *  [thirdPartyEnemy], scaled by its strength against that enemy, by how close it is to the enemy (attack) and to its ally (defense),
     *  and reduced for each other war it is in.
     *  @return the new (attack, defense) values
     */
    private fun adjustThirdPartyStrength(thirdParty: Civilization, thirdPartyEnemy: Civilization, thirdPartyAlly: Civilization,
                                         strength: StrengthLevel, attack: Int, defense: Int, rules: AiRuleset): Pair<Int, Int> {
        var attackValue = percent(attack, backupPercent(rules, strength, thirdParty, military = true))
        var defenseValue = percent(defense, backupPercent(rules, strength, thirdParty, military = false))
        attackValue = percent(attackValue, if (thirdParty.isCityState) rules.define(AiDefine.MILITARY_STRENGTH_MINOR_BACKUP_NEIGHBORS)
            else proximityBackup(rules, thirdParty.getProximity(thirdPartyEnemy), military = true))
        defenseValue = percent(defenseValue, if (thirdParty.isCityState) rules.define(AiDefine.TARGET_MINOR_BACKUP_NEIGHBORS)
            else proximityBackup(rules, thirdParty.getProximity(thirdPartyAlly), military = false))
        if (attackValue > 0 || defenseValue > 0) {
            var warCount = WarStateAi.numDangerousMajorsAtWarWith(thirdParty, excludePhonyWars = false, excludeIfNoTarget = true)
            if (thirdParty.isAtWarWith(thirdPartyEnemy)) warCount--
            if (warCount > 0) {
                var scale = max(max(rules.define(AiDefine.MILITARY_STRENGTH_BACKUP_ALREADY_WAR_MINIMUM), 0),
                    100 - warCount * rules.define(AiDefine.MILITARY_STRENGTH_BACKUP_ALREADY_WAR_EACH_PLAYER))
                attackValue = percent(attackValue, scale)
                scale = max(max(rules.define(AiDefine.TARGET_ALREADY_WAR_MINIMUM), 0), 100 - warCount * rules.define(AiDefine.TARGET_ALREADY_WAR_EACH_PLAYER))
                defenseValue = percent(defenseValue, scale)
            }
        }
        return attackValue to defenseValue
    }

    private fun proximityBackup(rules: AiRuleset, proximity: Proximity, military: Boolean): Int {
        val prefix = (if (military) "MILITARY_STRENGTH_" else "TARGET_") + "MAJOR_BACKUP_"
        return rules.define(AiDefine.valueOf(prefix + when (proximity) {
            Proximity.Neighbors -> "NEIGHBORS"
            Proximity.Close -> "CLOSE"
            Proximity.Far -> "FAR"
            else -> "DISTANT"
        }))
    }
    //endregion

    //region DoUpdatePlayerStrengthEstimates

    /** What we know of ourselves for the whole update: our unit strength, economy, city defense and the civilizations we have met */
    private class Self(val civ: Civilization, val rules: AiRuleset) {
        val unitStrength = unitStrength(civ, rules)
        val economicStrength = economicStrengthOf(civ)
        val cityDefense = cityDefense(civ, rules)
        val numNukes = numNukes(civ)
        val numMilitaryUnits = numMilitaryUnits(civ)
        val valid = civ.gameInfo.civilizations.filter { it != civ && !it.isBarbarian && !it.isSpectator && it.isAlive() && civ.knows(it) }
    }

    /**
     *  CvDiplomacyAI::DoUpdatePlayerStrengthEstimates for [civ] against every living player it has met (the economic strength is kept in
     *  the player's [MajorCivMemory], the rest in [WarStateMemory]). Players we have not met or that died lose their entries.
     */
    fun update(civ: Civilization) {
        val self = Self(civ, civ.gameInfo.ruleset.ai)
        val memory = civ.ai.warStates
        val validIds = self.valid.mapTo(HashSet()) { it.civID }
        memory.militaryStrengths.keys.retainAll(validIds)
        memory.rawMilitaryStrengths.keys.retainAll(validIds)
        memory.targetValues.keys.retainAll(validIds)
        memory.rawTargetValues.keys.retainAll(validIds)
        for ((index, player) in self.valid.withIndex()) estimate(self, player, self.valid.subList(0, index + 1), memory)
    }

    private fun set(memory: WarStateMemory, player: Civilization, strength: StrengthLevel, target: TargetValue) {
        memory.militaryStrengths[player.civID] = strength
        memory.rawMilitaryStrengths[player.civID] = strength
        memory.targetValues[player.civID] = target
        memory.rawTargetValues[player.civID] = target
    }

    /** The war allies of one side's war against a player, as VP's four lists (their and our defensive and offensive allies) */
    private class WarAllies(val ourDefensive: List<Civilization>, val ourOffensive: List<Civilization>,
                            val theirDefensive: List<Civilization>, val theirOffensive: List<Civilization>)

    private fun warAlliesAgainst(civ: Civilization, player: Civilization): WarAllies {
        val ourDefensive = WarAlliesAi.warAllies(civ, player, includeMinors = true, reverse = false, defensive = true)
        val ourOffensive = WarAlliesAi.warAllies(civ, player, includeMinors = true, reverse = false)
        // A city-state is backed by the allies of its ally; nothing is evaluated without one (or when it is us: we are not in the list)
        val evaluate = if (player.isCityState) player.allyCiv else player
        if (evaluate == null || evaluate == civ) return WarAllies(ourDefensive, ourOffensive, emptyList(), emptyList())
        var theirDefensive = WarAlliesAi.warAllies(civ, evaluate, includeMinors = true, reverse = true, defensive = true)
        var theirOffensive = emptyList<Civilization>()
        if (player.isMajorCiv) theirOffensive = WarAlliesAi.warAllies(civ, evaluate, includeMinors = true, reverse = true)
        else theirDefensive = (theirDefensive + evaluate).filter { it != player }
        return WarAllies(ourDefensive, ourOffensive, theirDefensive, theirOffensive)
    }

    /** The two sides' allies that can reach the war: those in [candidates] (VP's vValidPlayers at this point) that are not terrible for war,
     *  can cross the ocean or are close enough, or (a city-state) are neighbors */
    private fun reachableAllies(civ: Civilization, player: Civilization, candidates: List<Civilization>, allies: WarAllies): Pair<List<Civilization>, List<Civilization>> {
        val ours = ArrayList<Civilization>()
        val theirs = ArrayList<Civilization>()
        for (loop in candidates) {
            if (loop == player || WarStateAi.isInTerribleShapeForWar(loop)) continue
            val crosses = loop.isMajorCiv && canCrossOcean(loop)
            var canMoveToUs = crosses || isClose(loop, civ)
            var canMoveToThem = crosses || isClose(loop, player)
            if (loop.isCityState) {
                canMoveToUs = loop.getProximity(civ) == Proximity.Neighbors
                canMoveToThem = loop.getProximity(player) == Proximity.Neighbors
            }
            if (canMoveToUs && loop in allies.ourDefensive) ours.add(loop)
            else if (canMoveToThem && loop in allies.ourOffensive) ours.add(loop)
            if (canMoveToThem && loop in allies.theirDefensive) theirs.add(loop)
            else if (canMoveToUs && loop in allies.theirOffensive) theirs.add(loop)
        }
        return ours to theirs
    }

    /** A side of the early game's caution: both with fewer than 6 military units, within a tenfold of one another's might, no nukes */
    private fun isEarlyGame(unitsA: Int, unitsB: Int, mightA: Int, mightB: Int, nukesA: Int, nukesB: Int) =
        unitsA < 6 && unitsB < 6 && mightA.toLong() < mightB.toLong() * 10 && mightB.toLong() < mightA.toLong() * 10 && nukesA == 0 && nukesB == 0

    /** The edge cases with no cities and with nuclear weapons: our strength estimates are fixed, true when [player] was settled that way */
    private fun settleEdgeCases(self: Self, player: Civilization, ourMight: Int, theirMight: Int, memory: WarStateMemory): Boolean {
        val ourCities = self.civ.cities.size
        val theirCities = player.cities.size
        val theirNukes = numNukes(player)
        val immense = (ourCities <= 0 && theirCities > 0 && ourMight.toLong() < theirMight.toLong() * 10 && self.numNukes == 0) ||
            (theirNukes > 0 && ourCities <= 0 && (theirCities > 0 || self.numNukes == 0))
        val pathetic = (theirCities <= 0 && ourCities > 0 && theirMight.toLong() < ourMight.toLong() * 10 && theirNukes == 0) ||
            (self.numNukes > 0 && theirCities <= 0 && (ourCities > 0 || theirNukes == 0))
        if (immense) set(memory, player, StrengthLevel.Immense, TargetValue.Impossible)
        else if (pathetic) set(memory, player, StrengthLevel.Pathetic, TargetValue.Cakewalk)
        return immense || pathetic
    }

    private fun estimate(self: Self, player: Civilization, candidates: List<Civilization>, memory: WarStateMemory) {
        val civ = self.civ
        val rules = self.rules
        val multiplier = rules.define(AiDefine.MILITARY_STRENGTH_RATIO_MULTIPLIER)
        val theirEconomic = economicStrengthOf(player)
        civ.ai.diplomacy.memoryOf(player).economicStrength =
            economicStrengthFromRatio(ratio(theirEconomic, rules.define(AiDefine.ECONOMIC_STRENGTH_RATIO_MULTIPLIER), self.economicStrength), rules)

        var ourMilitary = self.unitStrength
        var theirMilitary = unitStrength(player, rules)
        if (settleEdgeCases(self, player, ourMilitary, theirMilitary, memory)) return

        ourMilitary = perceived(civ, civ, player, ourMilitary)
        theirMilitary = perceived(civ, player, civ, theirMilitary)
        ourMilitary += NuclearMight.of(civ, player)
        theirMilitary += NuclearMight.of(player, civ)

        val allies = warAlliesAgainst(civ, player)
        val (ourAllies, theirAllies) = reachableAllies(civ, player, candidates, allies)
        var allyAttackBonus = 0
        var allyDefenseBonus = 0
        for (ally in ourAllies) {
            var allyMight = unitStrength(ally, rules)
            var theirMight = unitStrength(player, rules)
            allyMight = perceived(civ, ally, player, allyMight)
            theirMight = perceived(civ, player, ally, theirMight)
            allyMight += NuclearMight.of(ally, player)
            theirMight += NuclearMight.of(player, ally)
            if (isEarlyGame(numMilitaryUnits(ally), numMilitaryUnits(player), allyMight, theirMight, numNukes(ally), numNukes(player))) allyMight = theirMight
            val strength = militaryStrengthFromRatio(ratio(allyMight, multiplier, theirMight), rules)
            val (attack, defense) = adjustThirdPartyStrength(ally, player, civ, strength,
                if (ally in allies.ourOffensive) allyMight else 0, if (ally in allies.ourDefensive) allyMight else 0, rules)
            allyAttackBonus += attack
            allyDefenseBonus += defense
        }
        var enemyAttackBonus = 0
        var enemyDefenseBonus = 0
        for (enemy in theirAllies) {
            var ourMight = self.unitStrength
            var enemyMight = unitStrength(enemy, rules)
            ourMight = perceived(civ, civ, enemy, ourMight)
            enemyMight = perceived(civ, enemy, civ, enemyMight)
            ourMight += NuclearMight.of(civ, enemy)
            enemyMight += NuclearMight.of(enemy, civ)
            if (isEarlyGame(numMilitaryUnits(enemy), self.numMilitaryUnits, enemyMight, ourMight, numNukes(enemy), self.numNukes)) enemyMight = ourMight
            val strength = militaryStrengthFromRatio(ratio(enemyMight, multiplier, ourMight), rules)
            val (attack, defense) = adjustThirdPartyStrength(enemy, civ, player, strength,
                if (enemy in allies.theirOffensive) enemyMight else 0, if (enemy in allies.theirDefensive) enemyMight else 0, rules)
            enemyAttackBonus += attack
            enemyDefenseBonus += defense
        }

        // Their overall attack against our overall defense
        val theirOverallAttack = theirMilitary + enemyAttackBonus
        var ourOverallDefense = ourMilitary + self.cityDefense
        val earlyGameCaution = isEarlyGame(numMilitaryUnits(player), self.numMilitaryUnits, theirMilitary, ourMilitary, numNukes(player), self.numNukes)
        if (earlyGameCaution) ourOverallDefense = theirMilitary
        var ourWarCount = WarStateAi.numDangerousMajorsAtWarWith(civ, excludePhonyWars = !civ.isHuman(), excludeIfNoTarget = true)
        if (civ.isAtWarWith(player)) ourWarCount--
        if (ourWarCount > 0) ourOverallDefense = percent(ourOverallDefense,
            max(rules.define(AiDefine.TARGET_ALREADY_WAR_MINIMUM), 100 - ourWarCount * rules.define(AiDefine.TARGET_ALREADY_WAR_EACH_PLAYER)))
        ourOverallDefense += allyDefenseBonus

        // ATTACK: military strength, biased by our boldness (an AI underestimates what it fights), and unbiased
        val boldness = civ.ai.diplomacyValue(DiplomacyPersonality.Boldness)
        var ratioMultiplier = if (civ.isHuman()) 100
            else max(boldness * rules.define(AiDefine.MILITARY_STRENGTH_REDUCTION_PER_BOLDNESS) + multiplier, 0)
        memory.militaryStrengths[player.civID] = militaryStrengthFromRatio(ratio(theirOverallAttack, ratioMultiplier, ourOverallDefense), rules)
        memory.rawMilitaryStrengths[player.civID] = militaryStrengthFromRatio(ratio(theirOverallAttack, 100, ourOverallDefense), rules)

        // Our overall attack against their overall defense
        val targetEconomic = rules.define(AiDefine.TARGET_ECONOMIC_MOD)
        val ourOverallAttackBase = ourMilitary + allyAttackBonus + percent(self.economicStrength, targetEconomic)
        var theirOverallDefense = theirMilitary + percent(theirEconomic, targetEconomic)
        val ourOverallAttack = if (earlyGameCaution) theirOverallDefense + allyAttackBonus else ourOverallAttackBase
        theirOverallDefense += cityDefense(player, rules)
        var theirWarCount = WarStateAi.numDangerousMajorsAtWarWith(player, excludePhonyWars = false, excludeIfNoTarget = true)
        if (civ.isAtWarWith(player)) theirWarCount--
        if (theirWarCount > 0) theirOverallDefense = percent(theirOverallDefense,
            max(rules.define(AiDefine.TARGET_ALREADY_WAR_MINIMUM), 100 - theirWarCount * rules.define(AiDefine.TARGET_ALREADY_WAR_EACH_PLAYER)))
        theirOverallDefense += enemyDefenseBonus

        // DEFENSE: target value, biased by our boldness and unbiased, both by distance
        ratioMultiplier = if (civ.isHuman()) 100
            else max(boldness * rules.define(AiDefine.TARGET_VALUE_REDUCTION_PER_BOLDNESS) + multiplier, 0)
        memory.targetValues[player.civID] = targetValueFromRatio(
            byProximity(ratio(theirOverallDefense, ratioMultiplier, ourOverallAttack), civ.getProximity(player), earlyGameCaution, rules), rules)
        memory.rawTargetValues[player.civID] = targetValueFromRatio(
            byProximity(ratio(theirOverallDefense, 100, ourOverallAttack), civ.getProximity(player), earlyGameCaution, rules), rules)
    }

    /** The target value ratio by how close they are: times TARGET_<proximity> over its divisor (neighbors: the divisor itself in the early
     *  game when it is the larger, so they count the same as close) */
    private fun byProximity(targetRatio: Int, proximity: Proximity, earlyGameCaution: Boolean, rules: AiRuleset): Int {
        val (factor, divisor) = when (proximity) {
            Proximity.Neighbors -> {
                val neighbors = rules.define(AiDefine.TARGET_NEIGHBORS)
                val divisor = rules.define(AiDefine.TARGET_NEIGHBORS_DIVISOR)
                (if (earlyGameCaution && neighbors < divisor) max(divisor, 1) else max(neighbors, 1)) to divisor
            }
            Proximity.Close -> rules.define(AiDefine.TARGET_CLOSE) to rules.define(AiDefine.TARGET_CLOSE_DIVISOR)
            Proximity.Far -> rules.define(AiDefine.TARGET_FAR) to rules.define(AiDefine.TARGET_FAR_DIVISOR)
            else -> rules.define(AiDefine.TARGET_DISTANT) to rules.define(AiDefine.TARGET_DISTANT_DIVISOR)
        }
        return (targetRatio.toLong() * max(factor, 1) / max(divisor, 1)).toInt()
    }
    //endregion
}
