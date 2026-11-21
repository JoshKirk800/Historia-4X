package com.unciv.logic

import yairm210.purity.annotations.Readonly
import kotlin.math.min

/**
 *  The medians of the yields per citizen that the needs of every city are measured against, saved in the game
 *  (Vox Populi's `CvGame::m_iBasicNeedsMedian`, `m_iGoldMedian`, `m_iScienceMedian`, `m_iCultureMedian` and `m_iMedianTechsResearched`).
 *  Only with [com.unciv.models.ModConstants.cityNeedsLikeVp]; with the key off, and in a game saved before they existed, they are all 0 and
 *  no city has a need (CvCity::GetDistressRaw returns 0 for a median of 0 or less).
 *
 *  The yield medians are in hundredths of a yield per citizen and are smoothed from turn to turn: each is the one of the last turn
 *  and the one of this turn mixed by [com.unciv.models.ModConstants.NeedsConstants.medianRateOfChange], so they depend on their own history
 *  and cannot be recalculated, which is why they are saved.
 */
class NeedsMedians : IsPartOfGameInfoSerialization {
    /** Food before consumption plus production, per citizen (Distress) */
    var basic = 0
    /** Gold per citizen (Poverty) */
    var gold = 0
    /** Science per citizen (Illiteracy) */
    var science = 0
    /** Culture per citizen (Boredom) */
    var culture = 0
    /** The median of the numbers of technologies the major civilizations know, not smoothed */
    var techs = 0

    @Readonly
    fun clone(): NeedsMedians {
        val toReturn = NeedsMedians()
        toReturn.basic = basic
        toReturn.gold = gold
        toReturn.science = science
        toReturn.culture = culture
        toReturn.techs = techs
        return toReturn
    }

    /**
     *  CvGame::updateGlobalMedians, once per game turn: every major civilization that is alive and has a city gives the number of its
     *  technologies, and every one of its cities that is no puppet, is not being razed, is not in resistance and has at least
     *  `medianMinPopulation` citizens gives its yields per citizen. The median of each list is its element number size x percentile / 100
     *  of the sorted list (the upper median for 50), and the new yield medians are smoothed with the old ones. A game with no such city or no civilization
     *  leaves the medians as they are.
     *
     *  What a city yields is read from its [com.unciv.logic.city.CityNeeds.yields]: food before consumption with the trade routes' food but
     *  production without the trade routes' production, as CvGame.cpp does (`getFoodPerTurnBeforeConsumptionTimes100()` and
     *  `getYieldRateTimes100(YIELD_PRODUCTION, true)`), and gold, science and culture without the trade routes.
     *
     *  Departure from the source: the index of a percentile of 100 (one past the end in the source) is the last element.
     */
    fun update(gameInfo: GameInfo) {
        val constants = gameInfo.ruleset.modOptions.constants.needs
        val basicYields = ArrayList<Float>()
        val goldYields = ArrayList<Float>()
        val scienceYields = ArrayList<Float>()
        val cultureYields = ArrayList<Float>()
        val techsKnown = ArrayList<Int>()

        for (civ in gameInfo.civilizations) {
            if (!civ.isMajorCiv || !civ.isAlive() || civ.cities.isEmpty()) continue
            techsKnown.add(civ.tech.researchedTechnologies.size)
            for (city in civ.cities) {
                if (city.isPuppet || city.isBeingRazed || city.isInResistance()) continue
                val population = city.population.population
                if (population < constants.medianMinPopulation) continue
                val yields = city.cityStats.needs.yields
                val citizens = population.toFloat()
                basicYields.add((yields.foodBeforeConsumption + yields.productionWithoutTrade) / citizens)
                goldYields.add(yields.gold / citizens)
                scienceYields.add(yields.science / citizens)
                cultureYields.add(yields.culture / citizens)
            }
        }

        // The median is not defined without a city that counts
        if (basicYields.isEmpty() || techsKnown.isEmpty()) return

        val index = min(basicYields.size * constants.medianPercentile.coerceIn(1, 100) / 100, basicYields.size - 1)
        val techIndex = min(techsKnown.size * constants.techMedianPercentile.coerceIn(1, 100) / 100, techsKnown.size - 1)

        techs = techsKnown.sorted()[techIndex]
        // The source truncates the float element before the product, and adds half to the float sum before the truncation
        val alpha = constants.medianRateOfChange
        fun smooth(yields: List<Float>, old: Int) = (0.5f + yields.sorted()[index].toInt() * alpha + old * (1 - alpha)).toInt()
        basic = smooth(basicYields, basic)
        gold = smooth(goldYields, gold)
        science = smooth(scienceYields, science)
        culture = smooth(cultureYields, culture)
    }
}
