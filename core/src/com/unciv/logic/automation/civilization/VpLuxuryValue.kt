package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 *  CvDealAI::GetLuxuryResourceValue's branch of Vox Populi (`MOD_BALANCE_VP`, V:CvDealAI.cpp:1290-1590), read with [com.unciv.models.ModConstants.dealLuxuryValueLikeVp]
 *  in place of the Community Patch value of [DealResourceValues.luxuryValue]: the base is one GPT's value x the number of eras x the share of technologies known, plus
 *  one GPT's value; the values are the buyer's or the seller's own and `null` is the original's INT_MAX (the last copy of an empire that is, or would be, unhappy).
 *
 *  Selling: the last copy of an unhappy empire is not for sale, nor when the empire's citizens would be unhappy without it ([DealResourceValues.wouldBeUnhappyWithout],
 *  `UNHAPPY_THRESHOLD`), else it is worth the base scaled by its happiness and 2 more; the yields a trait of exports gives (the Netherlands') are taken off while nothing
 *  of it is sold yet, as much as one GPT's value for each (twice for science and culture) times the era. Buying: an unhappy empire pays 3 scaled GPT (6 very unhappy,
 *  12 super unhappy), 3 more at war or when it plans one; each city that demands the resource and is not in a We Love the King Day 2 GPT while the empire is not
 *  unhappy; the imports a trait gives yields for the first copy (scaled GPT per yield, twice for science and culture); a quest of a City-State to connect it its
 *  reward in influence (twice, four times for a diplomat); the receiver's net gold raises it by sqrt(net gold) / 2 up to a fifth of the value.
 *
 *  None, because VP's input is not in the engine: the yields of a belief for each luxury (Goddess of Festivals, `Belief_YieldPerLux`: no such shape), the yields that a
 *  We Love the King Day gives the demanding city (they are conditional uniques of many objects here, not one table: `GetYieldFromWLTKD`), the gold of a quest (Unciv's
 *  quests give influence only), the monopoly branches (`IsImportsCountTowardsMonopolies`: no trait of Vox Populi has it), the deal under renewal (not kept apart).
 */
object VpLuxuryValue {
    /** The yields of the exports and imports rows: V:CvDealAI.cpp reads those up to golden age points, of which BNW's stats have these */
    private val YIELDS = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)

    fun value(us: Civilization, them: Civilization, resource: TileResource, fromMe: Boolean, netGoldOfReceiver: Int): Int? {
        // Don't buy obsolete resources
        if (!fromMe && resource.getMatchingUniques(UniqueType.ObsoleteWith).any { us.tech.isResearched(it.params[0]) }) return 0

        val owner = if (fromMe) us else them
        val baseHappiness = DealResourceValues.happinessFromLuxury(owner, resource) + DealResourceValues.extraHappinessPerLuxury(owner)
        val available = DealAi.numResourceAvailable(us, resource, true)
        val oneGpt = DealResourceValues.oneGptValue(us, us.isAtWarWith(them))
        val scaled = oneGpt * us.gameInfo.ruleset.eras.size * DealResourceValues.techProgressPercent(us) / 100
        val base = scaled + oneGpt
        return if (fromMe) selling(us, resource, baseHappiness, available, oneGpt, scaled, base)
        else buying(us, resource, oneGpt, scaled, base, netGoldOfReceiver)
    }

    private fun selling(us: Civilization, resource: TileResource, baseHappiness: Int, available: Int, oneGpt: Int, scaled: Int, base: Int): Int? {
        var value = base
        if (available == 1) {
            // Don't sell our last copy if already unhappy, or if it would make us unhappy
            if (EconomicMilitaryStrategyTests.isEmpireUnhappy(us)) return null
            if (DealResourceValues.wouldBeUnhappyWithout(us, baseHappiness)) return null
            // Extra value for the last copy
            value += scaled * (baseHappiness + 2)
        }
        // Netherlands sells resources more cheaply
        if (DealResourceValues.resourceExport(us, resource) <= 0) value -= yieldBonus(us, UniqueType.StatsPerExportedLuxury) * oneGpt * max(1, us.getEraNumber())
        return value
    }

    private fun buying(us: Civilization, resource: TileResource, oneGpt: Int, scaled: Int, base: Int, netGoldOfReceiver: Int): Int {
        var value = base
        val unhappy = EconomicMilitaryStrategyTests.isEmpireUnhappy(us)
        // We're unhappy! Buy luxuries!
        if (unhappy) {
            value += scaled * when {
                us.isEmpireSuperUnhappy() -> 12
                us.isEmpireVeryUnhappy() -> 6
                else -> 3
            }
            // At war or planning war: this is more serious; we may lose the war due to this
            if (DealResourceValues.atWarOrPlanningWar(us)) value += scaled * 3
        }

        // Are any of our cities demanding this resource?
        for (city in us.cities) {
            if (city.isInResistance() || city.isBeingRazed || city.demandedResource != resource.name || city.isWeLoveTheKingDayActive()) continue
            // Bonus value for each WLTKD if we're not unhappy
            if (!unhappy) value += oneGpt * 2
        }

        // Netherlands buys resources for more if they aren't already importing it
        val imports = us.detailedCivResources.any { it.resource == resource && it.amount > 0 && (it.origin == "Trade" || it.origin == Constants.cityStates) }
        if (!imports) value += yieldBonus(us, UniqueType.StatsPerImportedLuxury) * scaled

        // Is there a City-State quest the AI wants to fulfill by obtaining this resource?
        for (minor in us.gameInfo.civilizations) {
            if (!minor.isCityState || !us.ai.diplomacy.isPlayerValid(minor) || us.isAtWarWith(minor)) continue
            if (us.ai.diplomacy.approachTowards(minor) <= MajorCivApproach.Hostile) continue
            for (assigned in minor.questManager.getAssignedQuestsFor(us))
                if (assigned.questName == QuestName.ConnectResource.value && assigned.data1 == resource.name)
                    value += assigned.quest.influence.toInt() * (if (us.ai.hasTraitCategory(TraitCategory.Diplomat) || us.ai.isGoingForDiploVictory()) 4 else 2)
        }

        // Resource value is increased based on net GPT, capped at 20% of the value of the resource itself
        if (netGoldOfReceiver > 0) value += min((0.5 * sqrt(netGoldOfReceiver.toDouble())).toInt(), value / 5)
        return value
    }

    /** The yields of [type]'s uniques that the civilization has, added up, science and culture twice (V:CvDealAI.cpp:1346-1366, 1486-1507) */
    private fun yieldBonus(us: Civilization, type: UniqueType): Int {
        var total = 0
        us.forEachMatchingUnique(type) { unique ->
            for (stat in YIELDS) {
                var bonus = unique.stats[stat].toInt()
                if (bonus <= 0) continue
                if (stat == Stat.Science || stat == Stat.Culture) bonus *= 2
                total += bonus
            }
        }
        return total
    }
}
