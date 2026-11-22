package com.unciv.models.ruleset.unique

import com.unciv.models.translations.removeConditionals
import yairm210.purity.annotations.Readonly

/**
 *  How the instant yields of Vox Populi's engine ([com.unciv.logic.civilization.InstantYields]) read in the descriptions of buildings, policies and beliefs and in the Civilopedia:
 *  `Gain [20] [Production] <upon population growth> <in this city> <(modified by game speed)> <for every [Triggering amount]> <for every [Era scaler]>` is shown as
 *  `Gain [20] [Production] <upon population growth> <in this city> <per citizen gained> <multiplied by the era (at least 1)>`: the two modifiers that only say how the engine
 *  works out the number are put into the words of what they do. Only for the gains of the events that the engine added (the first group of [UniqueType.TriggerUponPopulationGrowth]
 *  and the six after it), so no unique of any other ruleset or an older mod changes how it reads.
 */
object InstantYieldTexts {
    private val newEvents = setOf(
        UniqueType.TriggerUponPopulationGrowth, UniqueType.TriggerUponAcquiringTile, UniqueType.TriggerUponBuying, UniqueType.TriggerUponCompletingUnit,
        UniqueType.TriggerUponPillaging, UniqueType.TriggerUponWltkdStart, UniqueType.TriggerUponGainingLuxury,
    )

    /** Whether [unique] is a gain of one of the new events, whose text [displayText] gives */
    @Readonly
    fun appliesTo(unique: Unique): Boolean =
        (unique.type == UniqueType.OneTimeGainStat || unique.type == UniqueType.OneTimeGainStatOfTriggeringAmount) &&
            unique.modifiers.any { it.type in newEvents }

    /**
     *  The modifiers that [unique] shows to the user, in the words of [displayText]: without `(modified by game speed)` (every instant yield has it, as the original scales them
     *  all but twelve events), `<for every [Era scaler]>` as what it does, `<for every [Triggering amount]>` as "per citizen gained" (growth), "per luxury gained"
     *  (luxury) or else as "per unit of the event"
     */
    @Readonly
    fun displayText(unique: Unique): String {
        val visible = unique.modifiers.filter { !it.isHiddenToUsers() && it.type != UniqueType.ModifiedByGameSpeed }
        val growth = unique.modifiers.any { it.type == UniqueType.TriggerUponPopulationGrowth }
        val luxury = unique.modifiers.any { it.type == UniqueType.TriggerUponGainingLuxury }
        val words = visible.map { modifier ->
            val countable = modifier.params.firstOrNull()
            when {
                modifier.type == UniqueType.ForEveryCountable && countable == "Triggering amount" ->
                    if (growth) PER_CITIZEN else if (luxury) PER_LUXURY else PER_EVENT_UNIT
                modifier.type == UniqueType.ForEveryCountable && countable == "Era scaler" -> TIMES_ERA
                else -> modifier.text
            }
        }
        return unique.text.removeConditionals() + words.joinToString("") { " <$it>" }
    }

    const val PER_CITIZEN = "per citizen gained"
    const val PER_LUXURY = "per luxury gained"
    const val PER_EVENT_UNIT = "for each unit of the event"
    const val TIMES_ERA = "multiplied by the era (at least 1)"
}
