package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.objectdescriptions.FormattedLineListBuilder.Companion.buildCivilopediaText
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import yairm210.purity.annotations.Readonly
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.abs

class Speed : RulesetObject() {
    var modifier: Float = 1f
    var goldCostModifier: Float = modifier
    var productionCostModifier: Float = modifier
    var scienceCostModifier: Float = modifier
    var cultureCostModifier: Float = modifier
    var faithCostModifier: Float = modifier
    /** What `Gain [amount] [stat] <(modified by game speed)>` is multiplied by under [com.unciv.models.ModConstants.instantYieldsUseInstantYieldPercent]
     *  (the original's `InstantYieldPercent` of the game speed, Vox Populi's CvPlayer::doInstantYield: 75, 100, 150 and 200 percent for Quick, Standard, Epic and Marathon) */
    var instantYieldModifier: Float = modifier
    /** What the gold price of a construction is multiplied by (the original's `HurryPercent` of the game speed, CvGameCoreUtils.cpp: GetBasePurchaseCostFromProduction;
     *  100 percent on every speed in Brave New World and Vox Populi, so unlike the other modifiers it does not default to [modifier]) */
    var hurryModifier: Float = 1f
    /** What the number of turns a trade route lasts is multiplied by (the original's `GameSpeeds.TradeRouteSpeedMod`, V:CvTradeClasses.cpp:1404-1442: 300, 150, 100 and 67 percent
     *  in Vox Populi for Marathon, Epic, Standard and Quick; the column does not exist in Brave New World, so like [hurryModifier] it does not default to [modifier]).
     *  Read with [com.unciv.models.ModConstants.tradeRouteModelLikeVp] by [com.unciv.logic.civilization.managers.TradeRouteManager.getTargetTurns] */
    var tradeRouteSpeedModifier: Float = 1f
    var goldGiftModifier: Float = modifier
    var cityStateTributeScalingInterval: Float = 6.5f
    var barbarianModifier: Float = modifier
    var improvementBuildLengthModifier: Float = modifier
    var goldenAgeLengthModifier: Float = modifier
    var religiousPressureAdjacentCity: Int = 6
    /** Happiness every civilization starts with on this speed (the original's `StartingHappiness` of the game speed, CvPlayer::DoUpdateTotalHappiness), only with [com.unciv.models.ModConstants.cityNeedsLikeVp] */
    var startingHappiness: Int = 0
    var peaceDealDuration: Int = 10
    var dealDuration: Int = 30
    var startYear: Float = -4000f
    var turns: ArrayList<HashMap<String, Float>> = ArrayList()

    // These could be private but for RulesetValidator checking it
    data class YearsPerTurn(val yearInterval: Float, val untilTurn: Int) {
        internal constructor(rawRow: HashMap<String, Float>) : this(rawRow["yearsPerTurn"]!!, rawRow["untilTurn"]!!.toInt())
    }
    val yearsPerTurn: ArrayList<YearsPerTurn> by lazy { turns.mapTo(ArrayList()) { YearsPerTurn(it) } }

    /** End of defined turn range, used for starting Era's `startPercent` calculation */
    @Readonly fun numTotalTurns(): Int = yearsPerTurn.last().untilTurn

    /** Calculate a Year from a turn number.
     *
     *  Note that years can have fractional parts and the integer part of the year for two consecutive turns _can_ be equal,
     *  but Unciv currently has no way to display that. This is left as Float to enable such display in the future,
     *  maybe as months, or even the 18+1 'months' of the mayan Haab'.
     *
     *  @param turn The logical turn number, any offset from starting in an advanced Era already added in
     */
    @Readonly
    fun turnToYear(turn: Int): Float {
        var year = startYear
        var intervalStartTurn = 0
        val lastIntervalEndTurn = numTotalTurns()
        for ((turnLength, intervalEndTurn) in yearsPerTurn) {
            if (intervalStartTurn >= turn) break // ensure year isn't projected backwards for negative `turn`
            if (turn <= intervalEndTurn || intervalEndTurn == lastIntervalEndTurn) {
                // We can interpolate linearly within this interval and are done
                year += (turn - intervalStartTurn) * turnLength
                break
            }
            // Accumulate total length in years of this interval and move on to the following intervals.
            year += (intervalEndTurn - intervalStartTurn) * turnLength
            intervalStartTurn = intervalEndTurn
        }
        return year
    }

    val statCostModifiers: EnumMap<Stat, Float> by lazy {
        val map = EnumMap<Stat, Float>(Stat::class.java)
        for (stat in Stat.entries) {
            val modifier = when (stat) {
                Stat.Production -> productionCostModifier
                Stat.Gold -> goldCostModifier
                Stat.Science -> scienceCostModifier
                Stat.Faith -> faithCostModifier
                Stat.Culture -> cultureCostModifier
                else -> 1f
            }
            map[stat] = modifier
        }

        map
    }

    companion object {
        const val DEFAULT: String = "Quick"
        const val DEFAULTFORSIMULATION: String = "Standard"
    }

    // Note: Speed uniques will be treated as part of GlobalUniques
    override fun getUniqueTarget(): UniqueTarget = UniqueTarget.Speed

    override fun makeLink(): String = "Speed/$name"
    override fun getCivilopediaTextHeader() = FormattedLine(name, header = 2)
    @Readonly
    override fun getCivilopediaTextLines(ruleset: Ruleset) = buildCivilopediaText {
        add("General speed modifier: [${modifier * 100}]%${Fonts.turn}")
        add("Production cost modifier: [${productionCostModifier * 100}]%${Fonts.production}")
        add("Gold cost modifier: [${goldCostModifier * 100}]%${Fonts.gold}")
        add("Science cost modifier: [${scienceCostModifier * 100}]%${Fonts.science}")
        add("Culture cost modifier: [${cultureCostModifier * 100}]%${Fonts.culture}")
        add("Faith cost modifier: [${faithCostModifier * 100}]%${Fonts.faith}")
        if (ruleset.modOptions.constants.instantYieldsUseInstantYieldPercent)
            add("Instant yield modifier: [${instantYieldModifier * 100}]%${Fonts.gold}")
        add("Improvement build length modifier: [${improvementBuildLengthModifier * 100}]%${Fonts.turn}")
        add("Diplomatic deal duration: [$dealDuration] turns${Fonts.turn}")
        add("Gold gift influence gain modifier: [${goldGiftModifier * 100}]%${Fonts.gold}")
        add("City-state tribute scaling interval: [${cityStateTributeScalingInterval}] turns${Fonts.turn}")
        add("Barbarian spawn modifier: [${barbarianModifier * 100}]%${Fonts.strength}")
        add("Golden age length modifier: [${goldenAgeLengthModifier * 100}]%${Fonts.happiness}")
        add("Adjacent city religious pressure: [$religiousPressureAdjacentCity]${Fonts.faith}")
        add("Peace deal duration: [$peaceDealDuration] turns${Fonts.turn}")
        add("Start year: [" + ("{[${abs(startYear).toInt()}] " + (if (startYear < 0) "BC" else "AD") + "}]").tr())
        addUniques()
    }
    override fun getSortGroup(ruleset: Ruleset): Int = (modifier * 1000).toInt()
}
