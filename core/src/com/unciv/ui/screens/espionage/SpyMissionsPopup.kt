package com.unciv.ui.screens.espionage

import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.models.Spy
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 *  The missions a spy gathering intel can do with the Network Points it has stored (only with the espionage of Vox Populi, `espionageLikeVp`): each with its cost and why it cannot be done now,
 *  and a confirmation with the chances to be identified and to be killed. [onDone] runs after a mission was done, to update the screen.
 */
class SpyMissionsPopup(private val screen: BaseScreen, private val spy: Spy, private val onDone: () -> Unit) : Popup(screen) {
    init {
        val speed = spy.civInfo.gameInfo.speed
        addGoodSizedLabel("Missions of [${spy.name}] in [${spy.getLocationName()}]").row()
        add("[${spy.networkPoints}] Network Points stored".toLabel()).padBottom(10f).row()
        for (mission in spy.civInfo.gameInfo.ruleset.spyMissions.values) {
            val cost = mission.getNetworkPointsNeeded(speed)
            val button = "${mission.name} ($cost)".toTextButton()
            val reason = SpyMissionResolver.unavailableReason(spy, mission)
            if (reason != null) button.disable()
            else button.onClick {
                ConfirmPopup(
                    screen,
                    "[${mission.name}] costs [$cost] Network Points. The spy has a [${mission.identifyChance}]% chance to be identified and a [${mission.killChance}]% chance to be killed. Do it?",
                    "Do it"
                ) {
                    SpyMissionResolver.perform(spy, mission)
                    close()
                    onDone()
                }.open(force = true)
            }
            add(button).row()
            if (reason != null) add(reason.toLabel()).padBottom(5f).row()
        }
        addCloseButton()
    }
}
