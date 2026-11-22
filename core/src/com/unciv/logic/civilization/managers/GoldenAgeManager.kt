package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.roundToInt

class GoldenAgeManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var civInfo: Civilization

    var storedHappiness = 0
    /** Golden ages entered by filling the happiness meter (CvPlayer::GetNumGoldenAges counts those of every source) */
    var numberOfGoldenAges = 0
        private set
    var turnsLeftForCurrentGoldenAge = 0

    fun clone(): GoldenAgeManager {
        val toReturn = GoldenAgeManager()
        toReturn.numberOfGoldenAges = numberOfGoldenAges
        toReturn.storedHappiness = storedHappiness
        toReturn.turnsLeftForCurrentGoldenAge = turnsLeftForCurrentGoldenAge
        return toReturn
    }

    @Readonly fun isGoldenAge(): Boolean = turnsLeftForCurrentGoldenAge > 0
    
    fun addHappiness(amount: Int) {
        storedHappiness += amount
    }

    @Readonly
    fun happinessRequiredForNextGoldenAge(): Int {
        val constants = civInfo.gameInfo.ruleset.modOptions.constants
        if (constants.cityNeedsLikeVp) return happinessRequiredForNextGoldenAgeLikeVp(constants.needs)
        // CvPlayer::GetGoldenAgeProgressThreshold, integer arithmetic as there: 500 + 250 per golden age so far, 1% of that for every city
        // after the first, x the game speed's GreatPeoplePercent, rounded down to a multiple of 5 (GOLDEN_AGE_VISIBLE_THRESHOLD_DIVISOR)
        var cost = 500 + numberOfGoldenAges * 250
        cost += (cost * (civInfo.cities.size - 1) * 0.01).toInt()
        cost = cost * (civInfo.gameInfo.speed.modifier * 100).roundToInt() / 100
        return cost / 5 * 5
    }

    /**
     *  CvPlayer::GetGoldenAgeProgressThreshold under `MOD_BALANCE_VP` (V:CvPlayer.cpp:24885-24941), integer arithmetic as there: 1000 for the first golden age, then
     *  400 + 2000 for each one had, 5 percent more for each effective city (GetNumEffectiveCities: the cities but the puppets, at least 1), x the game speed's
     *  GreatPeoplePercent, rounded down to a multiple of 5 (GOLDEN_AGE_VISIBLE_THRESHOLD_DIVISOR). Not ported: the golden age meter modifier of policies
     *  (GetGoldenAgeMeterMod, no unique for it) and the handicap's golden age percentages (the difficulties have no such field).
     */
    @Readonly
    private fun happinessRequiredForNextGoldenAgeLikeVp(needs: ModConstants.NeedsConstants): Int {
        var cost = if (numberOfGoldenAges == 0) needs.goldenAgeThresholdInitial
            else needs.goldenAgeThresholdBase + numberOfGoldenAges * needs.goldenAgeThresholdPerGoldenAge
        val effectiveCities = max(1, civInfo.cities.count { !it.isPuppet })
        cost = cost * (100 + effectiveCities * needs.goldenAgeThresholdPerCityPercent) / 100
        cost = cost * (civInfo.gameInfo.speed.modifier * 100).roundToInt() / 100
        val divisor = max(1, needs.goldenAgeVisibleDivisor)
        return cost / divisor * divisor
    }

    @Readonly
    fun calculateGoldenAgeLength(unmodifiedNumberOfTurns: Int): Int {
        var turnsToGoldenAge = unmodifiedNumberOfTurns.toFloat()
        civInfo.forEachMatchingUnique(UniqueType.GoldenAgeLength) { unique ->
            turnsToGoldenAge *= unique.params[0].toPercent()
        }
        turnsToGoldenAge *= civInfo.gameInfo.speed.goldenAgeLengthModifier
        return turnsToGoldenAge.toInt()
    }

    fun enterGoldenAge(unmodifiedNumberOfTurns: Int = 10) {
        turnsLeftForCurrentGoldenAge += calculateGoldenAgeLength(unmodifiedNumberOfTurns)
        civInfo.addNotification("You have entered a Golden Age!",
            CivilopediaAction("Tutorial/Golden Age"),
            NotificationCategory.General, "StatIcons/Happiness")
        civInfo.popupAlerts.add(PopupAlert(AlertType.GoldenAge, ""))

        // INSTANT_YIELD_TYPE_GOLDEN_AGE_START (V:CvPlayer.cpp:25123)
        InstantYields.fire(UniqueType.TriggerUponEnteringGoldenAge, civInfo, null, 0, civInfo.state)
        //Golden Age can happen mid turn with Great Artist effects
        for (city in civInfo.cities)
            city.cityStats.update()
    }

    /**
     *  @param happiness what the meter gains: the happiness over unhappiness of the original's model, or with [com.unciv.models.ModConstants.cityNeedsLikeVp]
     *      the golden age points of the cities' happiness ([CivNeeds.goldenAgePointsFromHappiness])
     */
    fun endTurn(happiness: Int) {
        if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) {
            endTurnLikeVp(happiness)
            return
        }
        if (!isGoldenAge())
            storedHappiness = (storedHappiness + happiness).coerceAtLeast(0)

        if (isGoldenAge()){
            turnsLeftForCurrentGoldenAge--
            if (turnsLeftForCurrentGoldenAge <= 0)
                civInfo.forEachTriggeredUnique(UniqueType.TriggerUpponEndingGoldenAge, ignoreCities = false) { unique ->
                    UniqueTriggerActivation.triggerUnique(unique, civInfo)
                }
        }
                
        else if (storedHappiness >= happinessRequiredForNextGoldenAge()) {
            storedHappiness -= happinessRequiredForNextGoldenAge()
            enterGoldenAge()
            numberOfGoldenAges++
        }
    }

    /**
     *  CvPlayer::DoProcessGoldenAge under `MOD_BALANCE_VP` (V:CvPlayer.cpp:24773-24821): a golden age in progress ticks down first; the meter then gains
     *  [points] whether or not a golden age is in progress ("VP still keeps the GAP meter running during a GA"); when no golden age is left and the meter reaches
     *  the threshold, the threshold comes off the meter and the next golden age starts, so the turn a golden age ends one can start at once.
     */
    private fun endTurnLikeVp(points: Int) {
        if (isGoldenAge()) {
            turnsLeftForCurrentGoldenAge--
            if (turnsLeftForCurrentGoldenAge <= 0)
                civInfo.forEachTriggeredUnique(UniqueType.TriggerUpponEndingGoldenAge, ignoreCities = false) { unique ->
                    UniqueTriggerActivation.triggerUnique(unique, civInfo)
                }
        }
        // The source lets the meter fall when the points are negative (it never goes under 0 here, as before)
        storedHappiness = (storedHappiness + points).coerceAtLeast(0)
        if (!isGoldenAge() && storedHappiness >= happinessRequiredForNextGoldenAge()) {
            storedHappiness -= happinessRequiredForNextGoldenAge()
            enterGoldenAge()
            numberOfGoldenAges++
        }
    }

}
