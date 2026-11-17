package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.canContribute
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import kotlin.math.sqrt

/**
 *  The sanity checks of Vox Populi's production AIs: what CvCityStrategyAI::ChooseProduction makes of each candidate's flavor weight
 *  (CvUnitProductionAI::CheckUnitBuildSanity, CvBuildingProductionAI::CheckBuildingBuildSanity, CvProcessProductionAI::
 *  CheckProcessBuildSanity, CvProjectProductionAI::CheckProjectBuildSanity). Each brings the flavor weight down to sqrti(10 x weight)
 *  ([normalise]) and then adds its own terms; a result of 0 or less refuses the candidate (VP's SanityResult codes, [SR_IMPOSSIBLE] ...).
 *
 *  Not ported yet: the bonuses of the unit and building sanity (their refusals that are ported: [UnitProductionAi.isRefused]); VP's most
 *  deficient yield of a city (CvCityStrategyAI::PrecalcYieldStats, which VP's citizens and builders read too), so no process gets its +150.
 *  VP-only inputs, none in BNW: the process conversion modifiers of buildings (GetYieldFromProcessModifier), the needs (poverty, boredom,
 *  illiteracy, distress, famine), the city happiness delta, the projects' events, supply, spy security, production and conquest yields,
 *  happiness and resources, defense processes, the Utopia project.
 */
object ProductionSanityAi {
    const val SR_IMPOSSIBLE = -1
    const val SR_UNITSUPPLY = -2
    const val SR_MAINTENANCE = -3
    const val SR_STRATEGY = -4
    const val SR_USELESS = -5
    const val SR_BALANCE = -6

    private const val LOSING_WARS = "MILITARYAISTRATEGY_LOSING_WARS"
    private const val GS_CULTURE = "ECONOMICAISTRATEGY_GS_CULTURE"
    private const val GS_DIPLOMACY = "ECONOMICAISTRATEGY_GS_DIPLOMACY"
    private const val GS_SPACESHIP = "ECONOMICAISTRATEGY_GS_SPACESHIP"
    private const val GS_SPACESHIP_HOMESTRETCH = "ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH"

    /** sqrti(10 x weight): "this seems to work well to bring the raw flavor weight into a sensible range [0 ... 200]" */
    fun normalise(weight: Int): Int = sqrt(10.0 * weight).toInt()

    /** CheckUnitBuildSanity as far as ported: no weight, or a refusal ([UnitProductionAi.isRefused]), refuses; else [normalise] */
    fun unitSanity(city: City, unit: BaseUnit, weight: Int, forOperation: Boolean): Int {
        if (weight < 1) return SR_IMPOSSIBLE
        if (UnitProductionAi.isRefused(city, unit, forOperation)) return SR_STRATEGY
        return normalise(weight)
    }

    /** CheckBuildingBuildSanity as far as ported: no weight refuses; else [normalise] */
    fun buildingSanity(weight: Int): Int = if (weight < 1) SR_IMPOSSIBLE else normalise(weight)

    /**
     *  CvProcessProductionAI::CheckProcessBuildSanity: for a city-state half the normalised weight. Else a modifier: -100 with a tiny army
     *  (military units at most 2 per city, or no garrison in a border city, [isBorderCity]); at war, unless a puppet we may annex, -5 per
     *  major we fight, - the city's threat, -25 in a border city, -25 under siege; -50 while LOSING_WARS. By the yield the process makes:
     *  gold +50 while LOSING_MONEY; culture +30 under GS_CULTURE; science refused with every technology known, +30 in the key science city;
     *  food refused while avoiding growth, +30 with a food deficit, +20 each for NEED_IMPROVEMENT_FOOD and NEED_NAVAL_GROWTH. A World
     *  Congress project ([leagueProjectModifier]) adds the modifier to the weight, the others take weight x (100 + modifier)%; at least 1.
     */
    fun processSanity(city: City, process: PerpetualConstruction, weight: Int): Int {
        if (weight < 1) return SR_IMPOSSIBLE
        var value = normalise(weight)
        val civ = city.civ
        if (civ.isCityState) return value / 2

        var modifier = 0
        val borderCity = isBorderCity(city)
        if (civ.units.getCivUnits().count { it.isMilitary() } <= civ.cities.size * 2 || city.getCenterTile().militaryUnit == null && borderCity)
            modifier -= 100
        if (!isPuppetAndAnnexable(city)) {
            val wars = WarStateAi.numberCivsAtWarWith(civ)
            if (wars > 0) {
                modifier -= wars * 5
                modifier -= civ.ai.plotDanger.cityThreat(city)
                if (borderCity) modifier -= 25
                if (CityDangerAi.isUnderSiege(city)) modifier -= 25
            }
        }
        if (civ.ai.isMilitaryStrategyActive(LOSING_WARS)) modifier -= 50

        when ((process as? PerpetualConstruction.StatConversion)?.stat) {
            Stat.Gold -> if (civ.ai.isEconomicStrategyActive(EconomicAi.LOSING_MONEY)) modifier += 50
            Stat.Culture -> if (civ.ai.isEconomicStrategyActive(GS_CULTURE)) modifier += 30
            Stat.Science -> {
                if (civ.tech.allTechsAreResearched()) return 0
                if (city.ai.isCityStrategyActive("AICITYSTRATEGY_KEY_SCIENCE_CITY")) modifier += 30
            }
            Stat.Food -> {
                if (city.avoidGrowth) return 0
                if (city.cityStats.currentCityStats.food < 0) modifier += 30
                if (city.ai.isCityStrategyActive("AICITYSTRATEGY_NEED_IMPROVEMENT_FOOD")) modifier += 20
                if (city.ai.isCityStrategyActive("AICITYSTRATEGY_NEED_NAVAL_GROWTH")) modifier += 20
            }
            else -> {}
        }

        val resolution = civ.gameInfo.ruleset.resolutions[process.name]
        if (resolution != null && resolution.projectCostPerPlayer > 0) {
            if (city.cityConstructions.currentConstructionName() == process.name) modifier += 10000
            modifier += leagueProjectModifier(civ, process.name)
            value += modifier
        } else value = value * (100 + modifier) / 100
        return maxOf(1, value)
    }

    /**
     *  The rewards of a World Congress project in CheckProcessBuildSanity, when [civ] can still contribute: Golden Age points (x 25), free
     *  policies (50 per policy owned), happiness (x (100 - excess happiness)), a temporary culture or tourism bonus (1500 under GS_CULTURE),
     *  City-State influence (1000 under GS_DIPLOMACY, else 500), turns of science (1000 under GS_SPACESHIP, else 500), a free unit (its
     *  [unitSanity] in the capital from 1500), a free building in the capital (its [buildingSanity] from 2000); every tier counts.
     */
    private fun leagueProjectModifier(civ: Civilization, projectName: String): Int {
        val league = civ.gameInfo.league ?: return 0
        if (!league.canContribute(civ, projectName)) return 0
        val resolution = civ.gameInfo.ruleset.resolutions[projectName] ?: return 0
        val capital = civ.getCapital(firstCityIfNoCapital = false)
        var modifier = 0
        for (unique in resolution.uniqueObjects) {
            if (unique.getModifiers(UniqueType.TriggerUponProjectTier).isEmpty()) continue
            when (unique.type) {
                UniqueType.OneTimeGainGoldenAgePoints -> modifier += unique.params[0].toInt() * 25
                UniqueType.OneTimeAmountFreePolicies -> modifier += civ.policies.getAdoptedPolicies().size * 50
                UniqueType.OneTimeGainPermanentStats ->
                    modifier += unique.stats.happiness.toInt() * (100 - civ.getHappiness())
                UniqueType.StatPercentBonus, UniqueType.StatPercentBonusCities, UniqueType.TourismPercent -> {
                    val culture = unique.type != UniqueType.TourismPercent && unique.params[1] == Stat.Culture.name
                    if ((culture || unique.type == UniqueType.TourismPercent) && civ.ai.isEconomicStrategyActive(GS_CULTURE)) modifier += 1500
                }
                UniqueType.OneTimeGainCityStateInfluence -> modifier += if (civ.ai.isEconomicStrategyActive(GS_DIPLOMACY)) 1000 else 500
                UniqueType.OneTimeGainScienceTurns -> modifier += if (civ.ai.isEconomicStrategyActive(GS_SPACESHIP)) 1000 else 500
                UniqueType.OneTimeFreeUnit -> {
                    val unit = civ.gameInfo.ruleset.units[unique.params[0]]?.let { civ.getEquivalentUnit(it) }
                    if (unit != null && capital != null) modifier += maxOf(0, unitSanity(capital, unit, 1500, forOperation = false))
                }
                UniqueType.GainFreeBuildings -> if (capital != null) modifier += maxOf(0, buildingSanity(2000))
                else -> {}
            }
        }
        return modifier
    }

    /**
     *  CvProjectProductionAI::CheckProjectBuildSanity: refused under siege, for city-states and in puppets we may annex. The Manhattan
     *  Project (it allows nukes) x 25 at war with a major, else x 10. A project a victory of this game needs (the Apollo Program): refused
     *  without that victory; +5000 in our best production city for it ([bestProductionCity]), +1000 in a competitive one
     *  ([isCityCompetitive]), refused elsewhere; x 10 when going for the science victory, x 10 more under GS_SPACESHIP_HOMESTRETCH.
     */
    fun projectSanity(city: City, project: Building, weight: Int): Int {
        if (weight < 1) return SR_IMPOSSIBLE
        if (CityDangerAi.isUnderSiege(city)) return SR_STRATEGY
        var value = normalise(weight)
        val civ = city.civ
        if (civ.isCityState || isPuppetAndAnnexable(city)) return SR_IMPOSSIBLE
        if (project.hasUnique(UniqueType.EnablesNuclearWeapons))
            value *= if (civ.gameInfo.civilizations.any { it.isMajorCiv && civ.isAtWarWith(it) }) 25 else 10
        val victory = civ.gameInfo.ruleset.victories.values.firstOrNull { victory ->
            victory.milestoneObjects.any { it.type == MilestoneType.BuiltBuilding && it.params.firstOrNull() == project.name }
        }
        if (victory != null) {
            if (victory.name !in civ.gameInfo.gameParameters.victoryTypes) return SR_IMPOSSIBLE
            value += when {
                bestProductionCity(civ, project) == city -> 5000
                isCityCompetitive(city, project) -> 1000
                else -> return SR_STRATEGY
            }
            if (civ.ai.isGoingForSpaceshipVictory()) value *= 10
            if (civ.ai.isEconomicStrategyActive(GS_SPACESHIP_HOMESTRETCH)) value *= 10
            return value
        }
        return maxOf(1, value)
    }

    /** CvCity::getRawProductionPerTurnTimes100: the production rate x 100 with the city's general modifiers, not those of what it builds */
    fun rawProductionTimes100(city: City): Int {
        val base = city.cityStats.baseStatTree.totalStats.production
        val modifier = 100 + city.cityStats.statPercentBonusTree.totalStats.production
        return (base * 100 * modifier / 100).toInt()
    }

    /**
     *  CvPlayer::GetBestProductionCity for a project: the first city with production put into it (all BNW projects are one per player),
     *  else the one with the most invested production x 100 + raw production
     */
    fun bestProductionCity(civ: Civilization, project: Building): City? {
        civ.cities.firstOrNull { it.cityConstructions.getWorkDone(project.name) > 0 }?.let { return it }
        return civ.cities.maxByOrNull { rawProductionTimes100(it) }?.takeIf { rawProductionTimes100(it) > 0 }
    }

    /** CvPlayer::IsCityCompetitive: the city's invested production x 100 + raw production at least the average of our other non-puppet cities */
    fun isCityCompetitive(city: City, project: Building): Boolean {
        fun production(other: City) = other.cityConstructions.getWorkDone(project.name) * 100 + rawProductionTimes100(other)
        val others = city.civ.cities.filter { !it.isPuppet && it != city }
        val threshold = others.sumOf { production(it) } / maxOf(1, others.size)
        return production(city) >= threshold
    }

    /** CityStrategyAIHelpers::IsTestCityStrategy_IsPuppetAndAnnexable: a puppet of a civilization that may annex (not Venice) */
    fun isPuppetAndAnnexable(city: City): Boolean = city.isPuppet && !city.civ.hasUnique(UniqueType.MayNotAnnexCities)

    /**
     *  CvCity::isBorderCity (CvPlot::IsBorderLand) for a major: the city is within AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT of a city of a
     *  living major we are not friendly towards, or next to its territory
     */
    fun isBorderCity(city: City): Boolean {
        val civ = city.civ
        val center = city.getCenterTile()
        val range = civ.gameInfo.ruleset.ai.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        val unfriendly = civ.gameInfo.civilizations.filter { other ->
            other != civ && other.isMajorCiv && other.isAlive() && other.cities.isNotEmpty() &&
                (civ.isCityState || civ.ai.diplomacy.approachTowards(other) != MajorCivApproach.Friendly)
        }
        if (unfriendly.any { other -> other.cities.any { it.getCenterTile().aerialDistanceTo(center) <= range } }) return true
        return center.neighbors.any { neighbor -> neighbor.getOwner()?.let { it in unfriendly } == true && !neighbor.isImpassible() }
    }
}
