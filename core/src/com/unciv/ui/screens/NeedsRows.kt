package com.unciv.ui.screens

import com.unciv.logic.city.City
import com.unciv.logic.city.NeedType
import com.unciv.logic.civilization.Civilization
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 *  A line of the needs breakdowns: [label] is a translatable text (a term, or a sentence with `[bracketed]` numbers), [value] the text
 *  of the number that goes with it, empty for a note that has none. Built by [NeedsRows], drawn by [com.unciv.ui.components.widgets.NeedsRowsTable].
 *
 *  @property indent how far the line is pushed in under the line above (the detail of a need)
 *  @property isAlert the line is something to worry about (an empire under a threshold, a city that is unhappy)
 */
data class NeedsRow(
    val label: String,
    val value: String = "",
    val style: Style = Style.Line,
    val indent: Int = 0,
    val isAlert: Boolean = false
) {
    enum class Style { Heading, Line, Note, Total }
}

/**
 *  The rows of the needs screens of Vox Populi's happiness system (CvCity::GetCityUnhappinessBreakdown and GetCityHappinessBreakdown, V:CvCity.cpp:20566-21160
 *  and 19934-19996, and the approval rating of CvPlayer::GetExcessHappiness), as plain data so that no screen builds them itself and tests need no GL context.
 *  All of them are empty unless [com.unciv.models.ModConstants.cityNeedsLikeVp] is on: then nothing is shown that was not shown before.
 */
object NeedsRows {
    private const val UNHAPPINESS = "Local Unhappiness"
    private const val HAPPINESS = "Local Happiness"
    private const val URBANIZATION = "Urbanization"

    private val yieldNeeds = listOf(NeedType.Distress, NeedType.Poverty, NeedType.Illiteracy, NeedType.Boredom)

    @Readonly private fun Civilization.usesNeeds() = gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp

    @Readonly private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

    @Readonly private fun oneDecimal(hundredths: Float) = String.format(Locale.ROOT, "%.1f", hundredths / 100f)

    /** The unhappiness sources of the city, its happiness sources and the difference; for a city of a major civilization only, and only with the needs model */
    @Readonly fun cityRows(city: City): List<NeedsRow> {
        if (!city.civ.usesNeeds() || !city.civ.isMajorCiv) return emptyList()
        val needs = city.cityStats.needs
        val population = city.population.population
        @LocalState val rows = ArrayList<NeedsRow>()

        // Unhappiness, the sources sorted by size (CityNeeds.breakdown), the biggest first
        rows += NeedsRow(UNHAPPINESS, needs.aggregated.toString(), NeedsRow.Style.Heading, isAlert = needs.aggregated > 0 && needs.delta < 0)
        for ((source, amount) in needs.breakdown) rows += NeedsRow(source, amount.toString())
        val noOwnNeeds = city.isPuppet || city.isBeingRazed || city.isInResistance()
        if (city.isPuppet) {
            rows += NeedsRow("A puppet city has no needs of its own", style = NeedsRow.Style.Note)
        } else if (!noOwnNeeds) {
            val capped = needs.breakdown.filterKeys { it != URBANIZATION }.values.sum()
            if (capped > population)
                rows += NeedsRow("[$capped] unhappy citizens are counted as at most [$population], the population", style = NeedsRow.Style.Note)
            rows += yieldNeedDetails(city)
        }

        // Happiness
        rows += NeedsRow(HAPPINESS, needs.localHappiness().toString(), NeedsRow.Style.Heading)
        if (needs.givesAndGetsHappiness()) {
            if (needs.happinessFromEmpire != 0) rows += NeedsRow("Empire", needs.happinessFromEmpire.toString())
            for ((source, amount) in city.cityStats.happinessList.entries
                    .map { it.key to it.value.roundToInt() }.filter { it.second != 0 }.sortedByDescending { it.second })
                rows += NeedsRow(source, amount.toString())
            val growthHappiness = needs.happinessWithGrowth()
            if (growthHappiness != 0) rows += NeedsRow("Potential happiness with growth", signed(growthHappiness), NeedsRow.Style.Note)
        } else {
            rows += NeedsRow("This city gives no happiness and gets none of the empire's", style = NeedsRow.Style.Note)
        }
        if (!noOwnNeeds) {
            val growthUnhappiness = needs.potentialUnhappinessWithGrowth()
            if (growthUnhappiness != 0) rows += NeedsRow("Potential unhappiness with growth", signed(growthUnhappiness), NeedsRow.Style.Note)
        }
        rows += NeedsRow("Happiness less unhappiness", signed(needs.delta), NeedsRow.Style.Total, isAlert = needs.delta < 0)
        return rows
    }

    /**
     *  For each yield need that is above 0 how it comes about: the city's yield against the median per citizen it is held to (the median of the game raised by
     *  the city's need modifier), and the flat reductions of buildings and policies that took citizens off the need (only told when the need is above 0,
     *  for then the reductions are what the plain need lacks).
     */
    @Readonly private fun yieldNeedDetails(city: City): List<NeedsRow> {
        val needs = city.cityStats.needs
        @LocalState val rows = ArrayList<NeedsRow>()
        val population = city.population.population
        var modifierTold = false
        for (type in yieldNeeds) {
            val need = needs.yieldNeed(type)
            if (need <= 0) continue
            val yields = needs.yields
            val yield100 = when (type) {
                NeedType.Distress -> yields.foodBeforeConsumption + yields.production
                NeedType.Poverty -> yields.gold
                NeedType.Illiteracy -> yields.science
                else -> yields.culture
            }
            val median = needs.modifiedMedian(type)
            rows += NeedsRow("[${type.label}]: [${oneDecimal(yield100.toFloat())}] yield against [${oneDecimal(median)}] per citizen",
                style = NeedsRow.Style.Note, indent = 1)
            val raw = if (median <= 0f) 0 else ceil(population - yield100 / median).toInt().coerceIn(0, population)
            if (raw > need) rows += NeedsRow("[${raw - need}] fewer unhappy citizens by buildings and policies", style = NeedsRow.Style.Note, indent = 2)
            if (!modifierTold && needs.allNeedsModifier() != 0) {
                rows += NeedsRow("The median is raised by [${needs.allNeedsModifier()}]% in this city", style = NeedsRow.Style.Note, indent = 2)
                modifierTold = true
            }
        }
        return rows
    }

    /**
     *  The empire's approval rating, the thresholds under which it is unhappy, the pool of happiness with its entries and the empire's unhappiness, and
     *  what the cities get of both: all of them when [city] is null, else only the one of [city].
     */
    @Readonly fun empireRows(civ: Civilization, city: City? = null): List<NeedsRow> {
        if (!civ.usesNeeds() || !civ.isMajorCiv) return emptyList()
        val needs = civ.stats.needs
        val constants = civ.gameInfo.ruleset.modOptions.constants.needs
        val approval = civ.getApproval()
        @LocalState val rows = ArrayList<NeedsRow>()

        rows += NeedsRow("Approval rating", "$approval%", NeedsRow.Style.Heading, isAlert = civ.isEmpireUnhappy())
        rows += NeedsRow("Unhappy below", "${constants.unhappyThreshold}%", isAlert = civ.isEmpireUnhappy())
        rows += NeedsRow("Very unhappy below", "${constants.veryUnhappyThreshold}%", isAlert = civ.isEmpireVeryUnhappy())
        rows += NeedsRow("Super unhappy below", "${constants.superUnhappyThreshold}%", isAlert = civ.isEmpireSuperUnhappy())
        rows += NeedsRow("Happiness", signed(civ.getHappiness()), NeedsRow.Style.Note)
        rows += NeedsRow("Happy citizens", needs.happyCitizens.toString())
        rows += NeedsRow("Unhappy citizens", needs.unhappyCitizens.toString())

        rows += NeedsRow("Happiness of the empire", needs.pool.toString(), NeedsRow.Style.Heading)
        for ((source, amount) in needs.poolBreakdown.entries
                .map { it.key to it.value.roundToInt() }.filter { it.second != 0 }.sortedByDescending { it.second })
            rows += NeedsRow(source, amount.toString())
        val publicOpinion = civ.culture.publicOpinion.unhappiness
        val warWeariness = needs.warWeariness()
        if (publicOpinion != 0 || warWeariness != 0) {
            rows += NeedsRow("Unhappiness of the empire", needs.unhappinessPool.toString(), NeedsRow.Style.Heading)
            if (publicOpinion != 0) rows += NeedsRow("Public opinion", publicOpinion.toString())
            if (warWeariness != 0) rows += NeedsRow("War weariness", warWeariness.toString())
        }

        if (city != null) {
            val share = city.cityStats.needs
            rows += NeedsRow("Share of this city", style = NeedsRow.Style.Heading)
            rows += NeedsRow("Happiness", share.happinessFromEmpire.toString())
            rows += NeedsRow("Unhappiness", share.unhappinessFromEmpire.toString())
        } else {
            rows += NeedsRow("Share of the cities", style = NeedsRow.Style.Heading)
            for (other in civ.cities) {
                val share = other.cityStats.needs
                rows += NeedsRow(other.name, "${signed(share.happinessFromEmpire)} / -${share.unhappinessFromEmpire}")
            }
        }
        return rows
    }

    /** The text of the tooltip of the Happiness stat in the top bar (which is the approval rating less the unhappy threshold): the percentage and the thresholds; null without the needs model */
    @Readonly fun approvalTooltip(civ: Civilization): String? {
        if (!civ.usesNeeds() || !civ.isMajorCiv) return null
        val constants = civ.gameInfo.ruleset.modOptions.constants.needs
        return "Approval rating [${civ.getApproval()}]%, unhappy below [${constants.unhappyThreshold}]%, " +
            "very unhappy below [${constants.veryUnhappyThreshold}]%, super unhappy below [${constants.superUnhappyThreshold}]%"
    }
}
