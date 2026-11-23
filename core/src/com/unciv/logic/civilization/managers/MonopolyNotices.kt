package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.ruleset.tile.TileResource

/**
 *  The notifications of a monopoly gained or lost (slice T9 of `phaseE/vp-trade-plan.md`; CvPlayer::CheckForMonopoly, V:CvPlayer.cpp:39255-39328, whose texts are
 *  TXT_KEY_NOTIFICATION_MONOPOLY_GAINED/LOST, ..._STRATEGIC_MONOPOLY_GAINED/LOST and their OTHER_PLAYER forms, `Text/en_US/Notifications/NewNotificationText.xml`):
 *  the human owner is told of its own gain and loss, every other human who has met it of a gain. Called by [MonopolyManager] once for each resource whose monopoly changed;
 *  not for the civilizations of the AI, which are told nothing.
 *
 *  Not ported: the long text with the help of the resource ({2_ResourceHelp}), which the notification of the original adds to its summary: the resource's description is
 *  one click away in the Civilopedia.
 */
object MonopolyNotices {
    private const val GAINED = "You control over [percent]% of the World's [resource], and therefore have a Global Monopoly!"
    private const val STRATEGIC_GAINED = "You control over [percent]% of the World's [resource], and therefore have a Strategic Monopoly!"
    private const val LOST = "You no longer have a Global Monopoly on [resource]!"
    private const val STRATEGIC_LOST = "You no longer have a Strategic Monopoly on [resource]!"
    private const val OTHER_GAINED = "[civ] has a Global Monopoly on [resource]!"
    private const val OTHER_STRATEGIC_GAINED = "[civ] has a Strategic Monopoly on [resource]!"

    /** What a change tells: the text for the civilization itself, for the others (null when nobody else is told), the kind of the change */
    class Notice(val own: String, val others: String?)

    /** The notices for the change of the monopolies of [resource] from [hadGlobal] and [hadStrategic] to [hasGlobal] and [hasStrategic], as the original orders them: a global gain first, then a strategic one, else a loss */
    fun notices(
        resource: TileResource,
        hadGlobal: Boolean, hasGlobal: Boolean,
        hadStrategic: Boolean, hasStrategic: Boolean,
        globalThreshold: Int, strategicThreshold: Int,
    ): List<Notice> {
        val name = "[${resource.name}]"
        val result = ArrayList<Notice>()
        if (!hadGlobal && hasGlobal) result.add(Notice(GAINED.replace("[percent]", "[$globalThreshold]").replace("[resource]", name), OTHER_GAINED.replace("[resource]", name)))
        else if (hadGlobal && !hasGlobal) result.add(Notice(LOST.replace("[resource]", name), null))
        if (!hadStrategic && hasStrategic)
            result.add(Notice(STRATEGIC_GAINED.replace("[percent]", "[$strategicThreshold]").replace("[resource]", name), OTHER_STRATEGIC_GAINED.replace("[resource]", name)))
        else if (hadStrategic && !hasStrategic) result.add(Notice(STRATEGIC_LOST.replace("[resource]", name), null))
        return result
    }

    /** Tells the humans of the change (see the class comment) */
    fun notify(civ: Civilization, resource: TileResource, hadGlobal: Boolean, hadStrategic: Boolean) {
        val game = civ.gameInfo
        val constants = game.ruleset.modOptions.constants.monopoly
        val changes = notices(resource, hadGlobal, civ.monopolies.hasGlobalMonopoly(resource.name), hadStrategic, civ.monopolies.hasStrategicMonopoly(resource.name),
            constants.globalThreshold, constants.strategicThreshold)
        for (notice in changes) {
            if (civ.isHuman()) civ.addNotification(notice.own, NotificationCategory.Trade, resource.name)
            val others = notice.others ?: continue
            for (other in game.civilizations)
                if (other != civ && other.isHuman() && other.isMajorCiv && other.knows(civ))
                    other.addNotification(others.replace("[civ]", "[${civ.civName}]"), NotificationCategory.Trade, resource.name)
        }
    }
}
