package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly

/**
 *  What the notifications of instant yields say and when they are given, with [com.unciv.models.ModConstants.instantYieldsUseInstantYieldPercent] (Vox Populi's instant
 *  yields): the gains of an event are told in one notification that names the event, each city that got something and the building, policy, belief or nation that
 *  gave it, not in one notification for every unique and every city.
 *
 *  What Vox Populi does (CvPlayer::doInstantYield, V:CvPlayer.cpp:27347-27560 and 28138-28245): every gain adds `+N [yield icon]` to the yield string of its city and
 *  the cities' strings make one text per call: "Birth of a Citizen: ...", "Constructing a Building: ...". The events that happen again and again in a turn
 *  (`INSTANT_YIELD_TYPE_BIRTH`, `..._WLTKD_START`, the refund, unit gift and Baktun end events) do not notify at once: the text of each call is added to the text of its
 *  event type and CvPlayer::doTurnPostDiplomacy (V:CvPlayer.cpp:10531-10547) gives one notification "Empire-Wide Bonus Yields" for each type that has one. Some events (combat experience,
 *  healing, city damage, scrapping) have none. The names of the sources are not told by Vox Populi (its text shows the event and the city only): here they are.
 *
 *  Not saved, and only filled for human civilizations (the others have no notifications). An [InstantYields.fire] hands its gains out and has [flush] give the notification
 *  of every event that is not deferred; the turn manager has [flushDeferred] give the ones that are, at the end of the civilization's turn and of its turn start.
 */
class InstantYieldNotices {
    /** What one unique gave one city (or the civilization, [city] null) of [source], to be added up: [stats] is the stat gained, [location] where it happened if it has one */
    private class Gain(val city: String?, val source: String?, val stats: Stats, val location: HexCoord?)

    private val pending = LinkedHashMap<String, MutableList<Gain>>()

    /** Tells that [unique] of an event [event] ([eventLabel] is not null for it) gave [amount] of [stat] to [city] */
    fun add(event: String, unique: Unique, city: City?, stat: Stat, amount: Int, location: HexCoord?) {
        val gains = pending.getOrPut(event) { ArrayList() }
        val gain = gains.firstOrNull { it.city == city?.name && it.source == unique.sourceObjectName }
            ?: Gain(city?.name, unique.sourceObjectName, Stats(), location).also { gains.add(it) }
        gain.stats.add(stat, amount.toFloat())
    }

    /** The notification of every event that happens once or twice per turn and is not [isDeferred], then forgets them */
    fun flush(civ: Civilization) = flushWhere(civ) { !isDeferred(it) }

    /**
     *  The notification of every event that is still waiting, one for each, then forgets them: Vox Populi's CvPlayer::doTurn giving the text of the turn for the deferred
     *  events, and with them the gains of a trigger that did not come through [InstantYields.fire]
     */
    fun flushDeferred(civ: Civilization) = flushWhere(civ) { true }

    private fun flushWhere(civ: Civilization, filter: (String) -> Boolean) {
        if (pending.isEmpty()) return
        for (event in pending.keys.filter(filter)) {
            val gains = pending.remove(event) ?: continue
            val lines = gains.map { Line(it.city, it.source, it.stats) }
            val text = text(event, lines) ?: continue
            val locations = gains.mapNotNull { it.location }.distinct()
            val icon = gains.first().stats.firstOrNull { it.value != 0f }?.key?.notificationIcon ?: Stat.Gold.notificationIcon
            civ.addNotification(text, LocationAction(locations.asSequence()), NotificationCategory.General, icon)
        }
    }

    /** One line of a notification: what [source] gave [city] */
    class Line(val city: String?, val source: String?, val stats: Stats)

    companion object {
        /** The trigger conditions that tell a Vox Populi instant yield event, with the name that the notification gives the event: the keys of [InstantYieldTotals] */
        private val eventLabels = mapOf(
            UniqueType.TriggerUponPopulationGrowth to "Birth of a Citizen",
            UniqueType.TriggerUponAcquiringTile to "Expanding Borders",
            UniqueType.TriggerUponBuying to "Purchasing a Unit or Building",
            UniqueType.TriggerUponCompletingUnit to "Producing a Unit",
            UniqueType.TriggerUponPillaging to "Pillaging a Tile",
            UniqueType.TriggerUponWltkdStart to "Start of We Love The King Day",
            UniqueType.TriggerUponGainingLuxury to "Acquiring a Luxury Resource",
            UniqueType.TriggerUponConstructingBuilding to "Constructing a Building",
            UniqueType.TriggerUponConstructingBuildingCityFilter to "Constructing a Building",
            UniqueType.TriggerUponResearch to "Researching a Technology",
            UniqueType.TriggerUponEnteringEra to "Entering a New Era",
            UniqueType.TriggerUponEnteringEraUnfiltered to "Entering a New Era",
            UniqueType.TriggerUponAdoptingPolicyOrBelief to "Adopting a Policy or Belief",
            UniqueType.TriggerUponEnteringGoldenAge to "Starting a Golden Age",
            // No name for `upon defeating a [mapUnitFilter] unit`: the uniques of the units that win also use it, and their notifications are given at once, not at the end of the event
        ).mapKeys { it.key.name }

        /** The events of which Vox Populi gives one notification for the whole turn (`getInstantYieldText` and `addInstantYieldText` in CvPlayer::doInstantYield) */
        private val deferredEvents = setOf(UniqueType.TriggerUponPopulationGrowth.name, UniqueType.TriggerUponWltkdStart.name)

        /** The notification name of [event] (the name of a trigger condition's unique type, see [InstantYields.eventOf]), or null if its yields keep the notifications they had */
        @Readonly
        fun eventLabel(event: String): String? = eventLabels[event]

        @Readonly
        fun isDeferred(event: String) = event in deferredEvents

        /** Whether the gains of [event] for [civ] are told in the notification of the event: with Vox Populi's instant yields, for a named event and a civilization that reads notifications */
        @Readonly
        fun tellsEvent(civ: Civilization, event: String) =
            civ.gameInfo.ruleset.modOptions.constants.instantYieldsUseInstantYieldPercent && civ.playerType != PlayerType.AI && event in eventLabels

        /**
         *  The text of the notification of [event]: its name and then each line `[city]: [stats] from [source]`, in the order in which the gains were made, the lines of a
         *  city without a source or the civilization without a city leaving out their part. Null when the event has no name or nothing was gained.
         *  The parts in curly brackets are translated one by one, so a language can word each line as it likes.
         */
        fun text(event: String, lines: List<Line>): String? {
            val label = eventLabels[event] ?: return null
            val told = lines.filter { line -> line.stats.any { it.value != 0f } }
            if (told.isEmpty()) return null
            return "{$label}: " + told.joinToString("; ") { line ->
                val stats = line.stats.toStringForNotifications()
                when {
                    line.city != null && line.source != null -> "{[${line.city}]: [$stats] from [${line.source}]}"
                    line.city != null -> "{[${line.city}]: [$stats]}"
                    line.source != null -> "{[$stats] from [${line.source}]}"
                    else -> "{Gained [$stats]}"
                }
            }
        }
    }
}
