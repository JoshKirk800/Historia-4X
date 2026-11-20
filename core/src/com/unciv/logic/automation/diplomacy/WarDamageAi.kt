package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

/**
 *  Vox Populi's war damage bookkeeping (CvPlayer.cpp, Release-5.4.8; D7): what each player lost to each other player
 *  ([MajorCivMemory.warValueLost], kept by majors and city-states), its decay, the share of an empire it amounts to
 *  ([warDamageValue]), the war score built from both sides' shares ([warScore]) and the combat-skill military rating
 *  ([militaryRating]). VP has no war damage levels; ours ([DiplomacyTurn]) remain for their BNW readers until those are ported.
 *  War weariness and the vassal terms of VP have no counterpart (BNW has no war weariness; no vassals, D3).
 */
object WarDamageAi {

    /**
     *  CvPlayer::ApplyWarDamage: [winner] did [amount] of war damage to [loser]. Between majors: the military rating moves by the amount
     *  (unless [noRating]) and every other met major at war with the loser, and not with the winner, likes the winner for it (common foe,
     *  unless [noCommonFoe]) by the amount scaled by its view of the loser's strength. The loser's record of what it lost grows by the
     *  amount (VP's war score modifiers are VP policies and traits, none in BNW).
     */
    fun applyWarDamage(winner: Civilization, loser: Civilization, amount: Int, noRating: Boolean = false, noCommonFoe: Boolean = false) {
        if (winner.isBarbarian || loser.isBarbarian || winner == loser) return
        if (winner.isMajorCiv && loser.isMajorCiv) {
            if (!noRating) {
                changeMilitaryRating(winner, amount)
                changeMilitaryRating(loser, -amount)
            }
            if (!noCommonFoe) for (third in winner.gameInfo.civilizations) {
                if (third == winner || third == loser || !third.isMajorCiv || !third.isAlive()) continue
                if (!winner.knows(third) || !loser.knows(third) || third.isAtWarWith(winner) || !loser.isAtWarWith(third)) continue
                val percent = when (WarStateAi.militaryStrengthComparedToUs(third, loser)) {
                    StrengthLevel.Immense -> 300
                    StrengthLevel.Powerful -> 200
                    StrengthLevel.Strong -> 150
                    StrengthLevel.Average -> 100
                    StrengthLevel.Poor -> 75
                    StrengthLevel.Weak -> 50
                    else -> 25
                }
                third.ai.diplomacy.changeCommonFoeValue(winner, amount * percent / 100)
            }
        }
        if (loser.isMajorCiv || loser.isCityState) loser.ai.diplomacy.memoryOf(winner).warValueLost += amount
    }

    /**
     *  CvUnit::pillage: [pillager] pillaged [improvement] on [tile] of a player it is at war with. The war value is
     *  PILLAGED_TILE_BASE_WAR_VALUE raised by a resource the owner can see (strategic +100%, luxury +50%, bonus +20%), the
     *  improvement's defensive bonus, and +100% for a great improvement. VP's +20% for improvements without follow-up has no
     *  counterpart in Unciv's improvements.
     */
    fun onPillage(pillager: Civilization, tile: Tile, improvement: TileImprovement) {
        val owner = tile.getOwner() ?: return
        if (pillager.isBarbarian || owner.isBarbarian || !pillager.isAtWarWith(owner)) return
        var multiplier = 0
        val resource = tile.tileResource.takeIf { tile.resource != null && owner.canSeeResource(it) }
        multiplier += when (resource?.resourceType) {
            ResourceType.Strategic -> 100
            ResourceType.Luxury -> 50
            ResourceType.Bonus -> 20
            null -> 0
        }
        multiplier += improvement.getMatchingUniques(UniqueType.DefensiveBonus).sumOf { it.params[0].toInt() }
        if (improvement.isGreatImprovement()) multiplier += 100
        val base = owner.gameInfo.ruleset.ai.define(AiDefine.PILLAGED_TILE_BASE_WAR_VALUE)
        applyWarDamage(pillager, owner, base * (100 + multiplier) / 100)
        // A high-value tile: a strategic or luxury resource, or a great improvement (VP's chokepoints have no counterpart)
        val highValue = resource?.resourceType == ResourceType.Strategic || resource?.resourceType == ResourceType.Luxury ||
            improvement.isGreatImprovement()
        WarProgressAi.onPillage(pillager, owner, highValue)
    }

    /**
     *  CvCity's razing turn: the owner razing [city] applies war damage to the major it took the city from (not to a city-state or a
     *  dead player): WAR_DAMAGE_LEVEL_CITY_WEIGHT + population x WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER + world wonders x
     *  WAR_DAMAGE_LEVEL_WORLD_WONDER_MULTIPLIER, over half the razing turns left (population over [removedPopulation] a turn), with no
     *  rating change and no common foe; and, between majors, the former owner's diplomacy holds the razing against the owner
     *  (CvDiplomacyAI::ChangeCivilianKillerValue: 500 x its era, at least 1, x the game speed's OpinionDurationPercent / 100, capped at
     *  CIVILIAN_KILLER_VALUE_PER_OPINION_WEIGHT x OPINION_WEIGHT_CIVILIAN_KILLER_MAX x that percent / 100). Call before the population
     *  goes down.
     */
    fun onRazingTurn(city: City, removedPopulation: Int) {
        val former = city.civ.gameInfo.getCivilizationOrNull(city.previousOwner) ?: return
        if (former == city.civ || former.isBarbarian || !former.isMajorCiv || !former.isAlive()) return
        val rules = city.civ.gameInfo.ruleset.ai
        val value = rules.define(AiDefine.WAR_DAMAGE_LEVEL_CITY_WEIGHT) +
            city.population.population * rules.define(AiDefine.WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER) +
            city.cityConstructions.getBuiltBuildings().count { it.isWonder } * rules.define(AiDefine.WAR_DAMAGE_LEVEL_WORLD_WONDER_MULTIPLIER)
        val turnsLeft = (city.population.population + removedPopulation - 1) / maxOf(1, removedPopulation)
        applyWarDamage(city.civ, former, value / maxOf(1, turnsLeft / 2), noRating = true, noCommonFoe = true)
        if (city.civ.isMajorCiv) {
            val memory = former.ai.diplomacy.memoryOf(city.civ)
            val percent = DiplomacyTurn.opinionDurationPercent(former)
            memory.civilianKillerValue = minOf(memory.civilianKillerValue + 500 * maxOf(1, former.getEraNumber()) * percent / 100,
                OpinionAi.maxCivilianKillerValue(former))
        }
    }

    /**
     *  CvCity::GetWarValue: WAR_DAMAGE_LEVEL_CITY_WEIGHT + population x WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER + world wonders x
     *  WAR_DAMAGE_LEVEL_WORLD_WONDER_MULTIPLIER, times WAR_PROGRESS_CAPITAL_MULTIPLIER % for the owner's original capital, else
     *  WAR_PROGRESS_HOLY_CITY_MULTIPLIER % for the holy city of the religion the owner founded, else 150% for another major's original
     *  capital, else 115% for a city-state's.
     */
    @Readonly
    fun cityWarValue(city: City): Int {
        val rules = city.civ.gameInfo.ruleset.ai
        var value = rules.define(AiDefine.WAR_DAMAGE_LEVEL_CITY_WEIGHT) +
            city.population.population * rules.define(AiDefine.WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER) +
            city.cityConstructions.getBuiltBuildings().count { it.isWonder } * rules.define(AiDefine.WAR_DAMAGE_LEVEL_WORLD_WONDER_MULTIPLIER)
        val owner = city.civ
        val ownReligion = owner.religionManager.religion?.takeIf { it.foundingCivName == owner.civName }
        val founder = city.foundingCivObject
        value = when {
            city.isOriginalCapital && founder == owner -> value * rules.define(AiDefine.WAR_PROGRESS_CAPITAL_MULTIPLIER) / 100
            owner.isMajorCiv && ownReligion != null && city.isHolyCityOf(ownReligion.name) ->
                value * rules.define(AiDefine.WAR_PROGRESS_HOLY_CITY_MULTIPLIER) / 100
            city.isOriginalCapital && founder?.isMajorCiv == true -> value * 150 / 100
            city.isOriginalCapital && founder?.isCityState == true -> value * 115 / 100
            else -> value
        }
        return value
    }

    /** CvUnit::kill's unit value: its power against the strongest unit of its domain its owner can build (100 = that unit) */
    @Readonly
    fun unitWarValue(unit: BaseUnit, owner: Civilization): Int =
        warValueAgainst(unit, powerOfStrongestBuildableUnit(owner, unit), owner.gameInfo.ruleset.ai)

    @Readonly
    private fun warValueAgainst(unit: BaseUnit, typical: Int, rules: AiRuleset): Int =
        if (typical > 0) unit.getAiPower() * 100 / typical else rules.define(AiDefine.DEFAULT_WAR_VALUE_FOR_UNIT)

    /** CvMilitaryAI::GetPowerOfStrongestBuildableUnit for the domain of [like] (land, sea or air) */
    @Readonly
    fun powerOfStrongestBuildableUnit(civ: Civilization, like: BaseUnit): Int {
        var best = 0
        for (unit in civ.gameInfo.ruleset.units.values) {
            if (!unit.isMilitary || !sameDomain(unit, like)) continue
            val power = unit.getAiPower()
            if (power > best && unit.isBuildable(civ)) best = power
        }
        return best
    }

    @Readonly
    private fun sameDomain(a: BaseUnit, b: BaseUnit): Boolean =
        if (b.isAirUnit()) a.isAirUnit() else if (b.isWaterUnit) a.isWaterUnit else a.isLandUnit

    /**
     *  The current value of [civ]'s empire as DoUpdateWarDamageAndWeariness counts it: its cities' war values and its units' values. The
     *  strongest buildable unit of a domain is looked for once per domain, not once per unit.
     */
    @Readonly
    fun empireWarValue(civ: Civilization): Int {
        @LocalState val typicalByDomain = IntArray(3) { -1 }
        val rules = civ.gameInfo.ruleset.ai
        return civ.cities.sumOf { cityWarValue(it) } + civ.units.getCivUnits().sumOf { unit ->
            val base = unit.baseUnit
            if (base.getAiPower() <= 0) return@sumOf 0
            val domain = if (base.isAirUnit()) 2 else if (base.isWaterUnit) 1 else 0
            if (typicalByDomain[domain] < 0) typicalByDomain[domain] = powerOfStrongestBuildableUnit(civ, base)
            warValueAgainst(base, typicalByDomain[domain], rules)
        }
    }

    /**
     *  CvPlayer::GetWarDamageValue (set by DoUpdateWarDamageAndWeariness): what [civ] lost to [other], twice over its empire's current
     *  value in % (VP doubles it so that the war score can be odd). Computed when asked rather than once at the start of the turn.
     */
    @Readonly
    fun warDamageValue(civ: Civilization, other: Civilization): Int {
        if (civ.isBarbarian || !other.isAlive()) return 0
        val lost = civ.ai.diplomacy.memories[other.civID]?.warValueLost ?: return 0
        if (lost <= 0) return 0
        val current = empireWarValue(civ)
        return if (current > 0) lost * 200 / current else lost * 2
    }

    /** CvPlayer::GetWarScore: at war, the damage [civ] did to [other] less the damage it took from them, between -100 and 100 */
    @Readonly
    fun warScore(civ: Civilization, other: Civilization): Int {
        if (!civ.isAtWarWith(other)) return 0
        return (warDamageValue(other, civ) - warDamageValue(civ, other)).coerceIn(-100, 100)
    }

    /** CvGameSpeedInfo::getTrainPercent: the game speed's unit production cost in percent (Marathon 300, Epic 150, Standard 100, Quick 67) */
    @Readonly
    private fun trainPercent(civ: Civilization): Int = (civ.gameInfo.speed.productionCostModifier * 100).roundToInt()

    /**
     *  CvPlayer::GetMostRecentWarScore: [civ]'s war score against [other] when their last war ended (or 100 when it killed [other]; -100
     *  the other way round), which decays towards 0 by one a turn at Standard speed and slower on slower speeds. The value is kept scaled
     *  by the speed's train percent (above 100) so that the decay takes more turns, and read back unscaled and rounded.
     */
    @Readonly
    fun mostRecentWarScore(civ: Civilization, other: Civilization): Int {
        if (civ.isBarbarian) return 0
        val stored = civ.ai.diplomacy.memories[other.civID]?.mostRecentWarScore ?: return 0
        val train = trainPercent(civ)
        if (train <= 100) return stored
        return (stored * 100 + if (stored >= 0) train / 2 else -train / 2) / train
    }

    /** CvPlayer::SetMostRecentWarScore (not from the decay): [value] is scaled for the game speed as [mostRecentWarScore] expects */
    fun setMostRecentWarScore(civ: Civilization, other: Civilization, value: Int) {
        if (civ.isBarbarian || !civ.isMajorCiv) return
        val train = trainPercent(civ)
        civ.ai.diplomacy.memoryOf(other).mostRecentWarScore =
            if (train <= 100) value else (value * train + if (value >= 0) 50 else -50) / 100
    }

    /** CvPlayer::DoMostRecentWarScoreDecay: the stored (scaled) value moves one towards 0 */
    private fun mostRecentWarScoreDecay(civ: Civilization, other: Civilization) {
        val memory = civ.ai.diplomacy.memories[other.civID] ?: return
        if (memory.mostRecentWarScore > 0) memory.mostRecentWarScore--
        else if (memory.mostRecentWarScore < 0) memory.mostRecentWarScore++
    }

    /** CvGame::GetStartingMilitaryRating: MILITARY_RATING_STARTING_VALUE, plus MILITARY_RATING_PER_ADVANCED_START_ERA per later start era */
    @Readonly
    fun startingMilitaryRating(civ: Civilization): Int {
        val rules = civ.gameInfo.ruleset.ai
        val startEra = civ.gameInfo.ruleset.eras[civ.gameInfo.gameParameters.startingEra]?.eraNumber ?: 0
        return rules.define(AiDefine.MILITARY_RATING_STARTING_VALUE) + startEra * rules.define(AiDefine.MILITARY_RATING_PER_ADVANCED_START_ERA)
    }

    /** CvPlayer::GetMilitaryRating: the starting rating until war damage first moves it (and in games saved before it was kept) */
    @Readonly
    fun militaryRating(civ: Civilization): Int =
        civ.ai.diplomacy.militaryRating.takeIf { it >= 0 } ?: startingMilitaryRating(civ)

    private fun changeMilitaryRating(civ: Civilization, change: Int) {
        civ.ai.diplomacy.militaryRating = maxOf(0, militaryRating(civ) + change)
    }

    /**
     *  The war damage part of CvPlayer::doTurn, for majors and city-states: DoWarValueLostDecay (what we lost to each living player goes
     *  down by a 50th each turn at war with them, a 10th at peace, at least 1) and DoMilitaryRatingDecay (the rating moves towards the
     *  starting rating by MILITARY_RATING_DECAY_PERCENT tenths of a percent of the gap, at least 1; VP's per-speed value, 20 at Standard,
     *  is that define over the speed's modifier).
     */
    fun doTurn(civ: Civilization) {
        for ((id, memory) in civ.ai.diplomacy.memories) {
            if (memory.warValueLost <= 0) continue
            val other = civ.gameInfo.getCivilizationOrNull(id) ?: continue
            if (!other.isAlive()) continue
            memory.warValueLost -= maxOf(1, memory.warValueLost / if (civ.isAtWarWith(other)) 50 else 10)
        }
        if (!civ.isMajorCiv) return
        // CvPlayer::doTurn: the recent war scores against the living majors decay
        if (civ.isAlive()) for (other in civ.gameInfo.civilizations) if (other.isMajorCiv && other.isAlive()) mostRecentWarScoreDecay(civ, other)
        val starting = startingMilitaryRating(civ)
        val current = militaryRating(civ)
        if (current == starting) return
        val rate = (civ.gameInfo.ruleset.ai.define(AiDefine.MILITARY_RATING_DECAY_PERCENT) / civ.gameInfo.speed.modifier).toInt()
        val step = maxOf(1, kotlin.math.abs(starting - current) * rate / 1000)
        civ.ai.diplomacy.militaryRating = if (current < starting) minOf(starting, current + step) else maxOf(starting, current - step)
    }
}
