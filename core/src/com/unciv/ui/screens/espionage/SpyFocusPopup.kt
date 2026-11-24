package com.unciv.ui.screens.espionage

import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.models.Spy
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 *  The counterspy focuses a spy that counters spies in an own city can choose (only with the espionage of Vox Populi, `espionageLikeVp`; CvPlayerEspionage::TriggerSpyFocusSetup,
 *  V:CvEspionageClasses.cpp:1478-1553, CvCity::DoEventChoice, V:CvCity.cpp:6519-6557): each with what it needs and why it cannot be chosen now. A human spy that has arrived cannot move until it has one
 *  ([Spy.focusPending]), and one that is changed cannot move for a few turns, which is said before it is done. [onDone] runs after a focus was chosen, to update the screen.
 */
class SpyFocusPopup(private val screen: BaseScreen, private val spy: Spy, private val onDone: () -> Unit) : Popup(screen) {
    init {
        val ruleset = spy.civInfo.gameInfo.ruleset
        val foci = ruleset.spyMissions.values.filter { it.isFocus }
        addGoodSizedLabel("What [${spy.name}] does against spies in [${spy.getLocationName()}]").row()
        val chosen = spy.focus
        add((if (chosen != null) "Now: [$chosen]" else "No focus chosen yet").toLabel()).padBottom(10f).row()
        if (foci.isEmpty()) {
            addGoodSizedLabel("This game has no counterspy focuses. The spy can go back to the hideout from the espionage screen.").row()
        } else if (spy.focusPending && foci.none { SpyMissionResolver.unavailableFocusReason(spy, it) == null }) {
            addGoodSizedLabel("None can be chosen yet. The spy waits here until one can, or goes back to the hideout.").padBottom(10f).row()
        }
        for (focus in foci) {
            val button = focus.name.toTextButton()
            val reason = SpyMissionResolver.unavailableFocusReason(spy, focus)
            if (reason != null) button.disable()
            else button.onClick {
                spy.chooseFocus(focus)
                close()
                onDone()
            }
            add(button).row()
            if (reason != null) add(reason.toLabel()).padBottom(5f).row()
        }
        addCloseButton()
    }
}
