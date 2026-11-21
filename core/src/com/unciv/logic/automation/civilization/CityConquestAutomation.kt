package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.diplomacy.LiberationAi
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.WarmongerAi
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.ProductionSanityAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min

/**
 *  The player-level city decisions of Vox Populi's CvPlayerAI (Release-5.4.8; D7): what to do with a conquered city
 *  ([chooseConquest], AI_conquerCity) and, once per turn, which puppet or razed city to annex ([considerAnnex]) and which to burn when the
 *  empire is close to revolt ([considerRaze]).
 *
 *  Only Community Patch's rules are ported: the VP-only branches (MOD_BALANCE_VP: the "mostly happy" buffer instead of
 *  IsEmpireUnhappy, the ratio-based happiness of AI_considerRaze, sphere of influence removal) are not, nor Timur's raze-everything,
 *  vassals (D3) and cities bought or traded (AI_conquerCity's bGift; Unciv's trades do not run through the conquest decision).
 *
 *  Inputs Unciv has no counterpart of, which a faithful port would read: puppet yield and supply modifiers (the Vox Populi policy
 *  Imperialism and Venice's trait set them; no ruleset here has them), teams (a teammate's city), a landmass count by player (we count the
 *  continent's cities of the conqueror), the previous owner as the liberation target when it is a city-state ([City.liberateCity] only
 *  gives a city to its founder), and which tile or city a quest wants liberated.
 */
object CityConquestAutomation {

    /** What [conquerCity] decided */
    enum class ConquestChoice { Liberate, Raze, Puppet, Annex }

    /** CvPlayer::canRaze: not a city we founded; Unciv's rules for what may not be razed ([City.canBeDestroyed]) */
    private fun canRaze(civ: Civilization, city: City, justCaptured: Boolean): Boolean =
        city.foundingCivObject != civ && city.canBeDestroyed(justCaptured)

    /**
     *  CvPlayerAI::AI_conquerCity, for [conqueror] that has just taken [city] (still owned by its previous owner), in VP's order:
     *  - A city we founded is annexed (VP does not run the decision for it, it keeps the annexed state), as is every city of a civilization
     *    without a city ("we must annex and make a capital").
     *  - A city-state liberates its ally's city and annexes any other.
     *  - [LiberationAi.isTryingToLiberate]: liberate.
     *  - The value of the city is its economic value ([AttackTargetsAi.economicValue]) as a percentage of the median city's
     *    ([WarmongerAi.medianEconomicValue]), x AI_CITY_VALUE_MULTIPLIER, x AI_CAPITAL_VALUE_MULTIPLIER for an original major capital;
     *    high from AI_CITY_HIGH_VALUE_THRESHOLD.
     *  - The city is kept when it is of high value and our empire is not very unhappy; when it has a world wonder or is a holy city;
     *    when it is our only city on its continent and we are not very unhappy; when it is an original major capital and we go for or are
     *    close to world conquest. A kept city, or one we could neither liberate nor raze, is annexed, puppeted instead when we may not
     *    annex (Venice) or our empire is unhappy (VP's separate tests for a unique courthouse and for a high value city come to the
     *    same, as they only are reached when the empire is not unhappy).
     *  - Otherwise: liberate a city-state founder; liberate a major when [LiberationAi.wantsToLiberateMajor]; puppet the only city of
     *    a continent unless the empire may revolt (super unhappy, or very unhappy with public opinion unhappiness); puppet a city worth at
     *    least AI_CITY_SOME_VALUE_THRESHOLD while the empire is not very unhappy and not unhappy (VP's puppet bonus clauses have no
     *    input); else raze it if it can be razed ([canRaze]); else puppet it.
     *
     *  BNW's conditions of the original, razing when the empire is unhappy, on a map the AI is told to raze on, and the Huns' faster razing
     *  with 3 + turn/100 cities, are gone: VP replaces them.
     *  @see LiberationAi.playerToLiberate for what can be liberated
     */
    fun chooseConquest(conqueror: Civilization, city: City): ConquestChoice {
        val rules = conqueror.gameInfo.ruleset.ai
        val founder = city.foundingCivObject
        if (founder == conqueror || conqueror.cities.isEmpty()) return ConquestChoice.Annex

        val toLiberate = LiberationAi.playerToLiberate(conqueror, city)
        val canLiberate = toLiberate != null
        val canRaze = canRaze(conqueror, city, justCaptured = true)
        val canAnnex = !conqueror.hasUnique(UniqueType.MayNotAnnexCities)

        if (conqueror.isCityState)
            return if (canLiberate && conqueror.allyCiv == toLiberate) ConquestChoice.Liberate else ConquestChoice.Annex

        if (LiberationAi.isTryingToLiberate(conqueror, city)) return ConquestChoice.Liberate

        val needsModel = conqueror.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp
        // With the needs model the empire is "unhappy" for a conquest a little before the unhappy threshold: AI_MOSTLY_HAPPY_THRESHOLD, a buffer
        val unhappy = if (needsModel) conqueror.getApproval() < rules.define(AiDefine.AI_MOSTLY_HAPPY_THRESHOLD)
            else EconomicMilitaryStrategyTests.isEmpireUnhappy(conqueror)
        val veryUnhappy = EconomicMilitaryStrategyTests.isEmpireVeryUnhappy(conqueror)

        val median = WarmongerAi.medianEconomicValue(conqueror.gameInfo)
        var value = AttackTargetsAi.economicValue(city, conqueror) * 100 / max(1, median)
        value = value * rules.define(AiDefine.AI_CITY_VALUE_MULTIPLIER) / 100
        val originalCapital = LiberationAi.isOriginalMajorCapital(city)
        if (originalCapital) value = value * rules.define(AiDefine.AI_CAPITAL_VALUE_MULTIPLIER) / 100
        val highValue = value >= rules.define(AiDefine.AI_CITY_HIGH_VALUE_THRESHOLD)

        var keepCity = highValue && !veryUnhappy
        if (numWorldWonders(city) > 0 || city.isHolyCity()) keepCity = true

        // The city still belongs to its previous owner, so none of our cities on its continent means it is our only one there
        val continent = city.getCenterTile().getContinent()
        val onlyCityOnContinent = conqueror.cities.none { it.getCenterTile().getContinent() == continent }
        if (onlyCityOnContinent && !veryUnhappy) keepCity = true

        if (!keepCity && originalCapital && (conqueror.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(conqueror, rules)))
            keepCity = true

        if (keepCity || (!canLiberate && !canRaze))
            return if (!canAnnex || unhappy) ConquestChoice.Puppet else ConquestChoice.Annex

        if (toLiberate != null) {
            if (toLiberate.isCityState) return ConquestChoice.Liberate
            if (LiberationAi.wantsToLiberateMajor(conqueror, city, toLiberate)) return ConquestChoice.Liberate
        }

        val canRevolt = EconomicMilitaryStrategyTests.isEmpireSuperUnhappy(conqueror) ||
            (veryUnhappy && (needsModel || conqueror.culture.publicOpinion.unhappiness > 0))
        if (onlyCityOnContinent && !canRevolt) return ConquestChoice.Puppet

        if (!veryUnhappy && value >= rules.define(AiDefine.AI_CITY_SOME_VALUE_THRESHOLD) && !unhappy) return ConquestChoice.Puppet

        return if (canRaze) ConquestChoice.Raze else ConquestChoice.Puppet
    }

    /** Carries out [chooseConquest] for [conqueror] on the just conquered [city] */
    fun conquerCity(conqueror: Civilization, city: City) {
        when (chooseConquest(conqueror, city)) {
            ConquestChoice.Liberate -> city.liberateCity(conqueror)
            ConquestChoice.Raze -> {
                city.puppetCity(conqueror)
                if (!conqueror.hasUnique(UniqueType.MayNotAnnexCities)) city.annexCity()
                city.isBeingRazed = true
            }
            ConquestChoice.Puppet -> city.puppetCity(conqueror)
            ConquestChoice.Annex -> {
                city.puppetCity(conqueror)
                city.annexCity()
            }
        }
    }

    /** CvCity::getNumWorldWonders */
    private fun numWorldWonders(city: City) = city.cityConstructions.getBuiltBuildings().count { it.isWonder }

    /** CvCity::IsNoOccupiedUnhappiness: the city has a building that removes the extra unhappiness of an annexed city (the courthouse) */
    private fun hasCourthouse(city: City) = city.containsBuildingUnique(UniqueType.RemovesAnnexUnhappiness)

    /** The courthouse [civ] may build (CvPlayer::canConstruct of a building with IsNoOccupiedUnhappiness: its unique replacement, its technology) */
    private fun canBuildCourthouse(civ: Civilization): Boolean = civ.gameInfo.ruleset.buildings.values.any { building ->
        building.hasUnique(UniqueType.RemovesAnnexUnhappiness, GameContext.IgnoreConditionals) &&
            !building.isUnavailableBySettings(civ.gameInfo) && civ.getEquivalentBuilding(building) == building &&
            building.requiredTechs().all { civ.tech.isResearched(it) }
    }

    /**
     *  CvPlayerAI::AI_considerAnnex, once per turn, at most one city. A city-state (any civilization that is no major) stops razing every
     *  city and annexes every puppet. A major civilization:
     *  - stops razing its capital and annexes it if it is a puppet (the original capital lost and regained);
     *  - then nothing when it may not annex (Venice), its empire is unhappy, it cannot build a courthouse, or a city of it is occupied
     *    and has none yet ("already converting one");
     *  - else weighs each puppet or razed city not in resistance, 1 + 4 for one it founded + 5 for an original major capital + 4 for the
     *    holy city of its state religion (the holy city of any religion, when it has none: 4 minus the era number, if positive) + 1 when
     *    coastal or on the border of an unfriendly major + 1 per world wonder (a razed city with a wonder is no longer razed first) + 3 when
     *    it is under siege and not about to fall, skipping a city of weight below 4 (below 5 when close to) under the culture grand strategy,
     *    and takes the city of the highest weight x raw production: a razed one stops being razed, a puppet is annexed.
     *  @return the city annexed or no longer razed, null if none
     */
    fun considerAnnex(civ: Civilization): City? {
        if (!civ.isMajorCiv) {
            for (city in civ.cities.toList()) {
                city.isBeingRazed = false
                if (city.isPuppet) city.annexCity()
            }
            return null
        }

        val capital = civ.getCapital(firstCityIfNoCapital = false)
        if (capital != null) {
            capital.isBeingRazed = false
            if (capital.isPuppet) {
                capital.annexCity()
                return capital
            }
        }

        if (civ.hasUnique(UniqueType.MayNotAnnexCities)) return null
        if (EconomicMilitaryStrategyTests.isEmpireUnhappy(civ)) return null
        if (!canBuildCourthouse(civ)) return null
        if (civ.cities.any { it.isOccupied() && !hasCourthouse(it) }) return null

        val rules = civ.gameInfo.ruleset.ai
        val ourReligion = OpinionAi.stateReligion(civ)?.first
        val unfriendly = AiOperationLogic.unfriendlyMajors(civ)
        val goingForCulture = civ.ai.isGoingForCultureVictory()
        var best: City? = null
        var bestScore = Int.MIN_VALUE
        for (city in civ.cities) {
            val wonders = numWorldWonders(city)
            if (city.isBeingRazed && wonders > 0) city.isBeingRazed = false
            if (city.isInResistance()) continue
            if (!city.isPuppet && !city.isBeingRazed) continue

            var weight = 1
            if (city.foundingCivObject == civ) weight += 4
            if (LiberationAi.isOriginalMajorCapital(city)) weight += 5
            if (ourReligion != null && city.isHolyCityOf(ourReligion.name)) weight += 4
            if (ourReligion == null && city.isHolyCity()) weight += max(0, 4 - civ.getEraNumber())
            if (WaterBodies.isCoastal(city) || (unfriendly.isNotEmpty() && AiOperationLogic.isBorderLand(civ, city.getCenterTile(), unfriendly)))
                weight += 1
            weight += wonders
            if (goingForCulture && weight < 4) continue
            if (VictoryProgressAi.isCloseToCultureVictory(civ, rules) && weight < 5) continue
            if (!CityDangerAi.isInDangerOfFalling(city, extraCareful = false) && CityDangerAi.isUnderSiege(city)) weight += 3

            val score = weight * ProductionSanityAi.rawProductionTimes100(city)
            if (score > bestScore) {
                best = city
                bestScore = score
            }
        }

        val target = best ?: return null
        if (target.isBeingRazed) target.isBeingRazed = false else target.annexCity()
        return target
    }

    /** What [city] adds to the empire's excess happiness: the sum of its happiness list (CvCity::GetLocalHappiness less GetUnhappinessAggregated) */
    private fun happinessOf(city: City): Float = city.cityStats.happinessList.values.sum()

    /**
     *  CvPlayerAI::AI_considerRaze, once per turn: a major civilization with more than one city that is allowed to annex, and whose empire may
     *  revolt (super unhappy, or very unhappy with public opinion unhappiness), burns occupied cities (cities it took, not puppets) it may
     *  raze whose loss gains happiness, until its excess happiness is above the threshold (VERY_UNHAPPY_THRESHOLD with public opinion
     *  unhappiness, else SUPER_UNHAPPY_THRESHOLD) or one city is left. It always burns at least one. A city already being razed counts as
     *  gone. In order, the cities that: are in danger from the civilizations at war with us ([CityDangerAi.isInDangerFromPlayers]) and
     *  have no courthouse; have no courthouse; are in danger; the rest. Each by the happiness its loss gains, the best first.
     *
     *  Community Patch's happiness (excess = happiness less unhappiness) is Unciv's excess happiness; as only the difference counts, a city is
     *  worth the sum of its happiness list. Razing a capital is not allowed ([City.canBeDestroyed]); VP only forbids razing the original one.
     *  @return the cities set to be razed
     */
    fun considerRaze(civ: Civilization): List<City> {
        if (!civ.isMajorCiv || civ.hasUnique(UniqueType.MayNotAnnexCities)) return emptyList()
        var numCities = civ.cities.size
        if (numCities <= 1) return emptyList()

        val needsModel = civ.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp
        val ideologyUnrest = civ.culture.publicOpinion.unhappiness > 0
        val canRevolt = EconomicMilitaryStrategyTests.isEmpireSuperUnhappy(civ) ||
            (EconomicMilitaryStrategyTests.isEmpireVeryUnhappy(civ) && (needsModel || ideologyUnrest))
        if (!canRevolt) return emptyList()

        val rules = civ.gameInfo.ruleset.ai
        val needsConstants = civ.gameInfo.ruleset.modOptions.constants.needs
        val threshold = if (needsModel) needsConstants.veryUnhappyThreshold
            else rules.define(if (ideologyUnrest) AiDefine.VERY_UNHAPPY_THRESHOLD else AiDefine.SUPER_UNHAPPY_THRESHOLD)
        // The excess happiness (without the needs model) or the citizens' happiness and unhappiness (with it) that the empire would have without the cities burnt so far
        var happiness = civ.getHappiness().toFloat()
        var happyCitizens = civ.stats.needs.happyCitizens
        var unhappyCitizens = civ.stats.needs.unhappyCitizens
        // Protect against modder stupidity (the source's words): a threshold or happiness that is not above 0 gives nothing to compute
        if (needsModel && (threshold <= 0 || happyCitizens <= 0)) return emptyList()

        fun approvalWith(happy: Int, unhappy: Int) = min(200, happy * 100 / max(1, unhappy)) / 2
        fun lose(city: City) {
            if (needsModel) {
                happyCitizens -= city.cityStats.needs.localHappiness()
                unhappyCitizens -= city.cityStats.needs.aggregated
            } else happiness -= happinessOf(city)
        }
        fun isEnough() = if (needsModel) approvalWith(happyCitizens, unhappyCitizens) >= threshold else happiness > threshold

        val enemies = civ.getCivsAtWarWith().filter { it.isAlive() && !it.isBarbarian }
        val endangered = HashSet<City>()
        val valid = ArrayList<City>()
        for (city in civ.cities) {
            if (city.isBeingRazed) {
                numCities--
                lose(city)
                continue
            }
            if (!city.isOccupied() || !canRaze(civ, city, justCaptured = false)) continue
            if (CityDangerAi.isInDangerFromPlayers(city, enemies)) endangered.add(city)
            valid.add(city)
        }
        if (numCities <= 1 || isEnough()) return emptyList()

        // Burn cities whose loss gains happiness, the biggest gain first (CvPlayerAI::AI_computeHappinessFromRazing with the needs model: the change of the approval rating)
        val candidates = if (needsModel) {
            val now = approvalWith(happyCitizens, unhappyCitizens)
            valid.associateWith { approvalWith(happyCitizens - it.cityStats.needs.localHappiness(), unhappyCitizens - it.cityStats.needs.aggregated) - now }
                .filterValues { it > 0 }.entries.sortedByDescending { it.value }.map { it.key }
        } else valid.filter { happinessOf(it) < 0f }.sortedBy { happinessOf(it) }
        val razed = ArrayList<City>()
        fun burn(city: City): Boolean {
            numCities--
            lose(city)
            city.isBeingRazed = true
            razed.add(city)
            return isEnough() || numCities == 1
        }
        val priorities = listOf<(City) -> Boolean>(
            { !hasCourthouse(it) && it in endangered },
            { !hasCourthouse(it) },
            { it in endangered },
            { true },
        )
        for (priority in priorities)
            for (city in candidates) {
                if (city.isBeingRazed || !priority(city)) continue
                if (burn(city)) return razed
            }
        return razed
    }
}
