package com.unciv.logic.city.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.models.ruleset.unique.forEachMatchingUnique
import com.unciv.models.ruleset.unique.getMatchingTagUniques
import yairm210.purity.annotations.Readonly

enum class SpyFleeReason {
    CityDestroyed,
    CityCaptured,
    CityBought,
    CityTakenOverByMarriage,
    Other
}

class CityEspionageManager {
    // Note: Instance lives in City, but since we don't carry data, it's transient, and no `: IsPartOfGameInfoSerialization` here.

    @Transient
    lateinit var city: City

    fun setTransients(city: City) {
        this.city = city
        seenFocusVersion = -1
        derivedFocus = null
    }

    /** The focus of the counterspy as last derived, valid while [seenFocusVersion] equals the owner's [com.unciv.logic.civilization.managers.EspionageManager.focusVersion] */
    @Transient
    private var derivedFocus: SpyMission? = null
    @Transient
    private var seenFocusVersion = -1

    /**
     *  The spy of the city's owner that counters spies here (CvCityEspionage::HasCounterSpy, GetCounterSpyID, V:CvEspionageClasses.cpp:7289-7304): null for a city of a city-state, without such a
     *  spy, while it still travels, and without [com.unciv.models.ModConstants.espionageLikeVp]. Everything a focus does reads this, so a spy that is not in the state `CounterIntelligence`
     *  in the city has no effect on it
     */
    @Readonly
    fun counterspy(): Spy? {
        val civ = city.civ
        if (!civ.isMajorCiv) return null
        val spies = civ.espionageManager.spyList
        if (spies.isEmpty() || !civ.gameInfo.ruleset.modOptions.constants.espionageLikeVp) return null
        return spies.firstOrNull { it.action == SpyAction.CounterIntelligence && it.getCityOrNull() == city }
    }

    /**
     *  The focus of [counterspy] (CvCityEspionage::GetCounterSpyFocus, V:CvEspionageClasses.cpp:7305-7316), null if there is no spy or it has not chosen one. The city's uniques are read many
     *  times a turn, so the answer is kept and found again only when the owner's spies changed what they counter ([com.unciv.logic.civilization.managers.EspionageManager.focusVersion]);
     *  with no spies, or without the switch, it is a comparison of two numbers
     */
    @Readonly
    @Suppress("purity") // it updates the transient cache
    fun getFocus(): SpyMission? {
        val manager = city.civ.espionageManager
        if (seenFocusVersion != manager.focusVersion) {
            derivedFocus = counterspy()?.focus?.let { city.civ.gameInfo.ruleset.spyMissions[it] }
            seenFocusVersion = manager.focusVersion
        }
        return derivedFocus
    }

    /**
     *  What the focus does is a source of uniques of the city beside its buildings and its religion. Like the others it gives no timed trigger and only uniques whose conditionals apply;
     *  the focus's uniques are local effects of this city, never global ones
     */
    @Readonly
    fun forEachFocusUnique(uniqueType: UniqueType, gameContext: GameContext, op: (unique: Unique) -> Unit) {
        // the effects that spy missions left here and that last for some turns are uniques of the city too (CvCity::DoEventChoice, V:CvCity.cpp:6560-7126)
        city.missionEffects.forEachMatchingUnique(uniqueType, gameContext, op)
        val focus = getFocus() ?: return
        focus.forEachMatchingUnique(uniqueType, gameContext) { if (!it.isTimedTriggerable) op(it) }
    }

    /**
     *  Every unique of the focus whose trigger condition [triggerFilter] accepts and whose conditionals apply, for [City.forEachLocalTriggeredUnique]: the focus's own `<upon [killing] a foreign spy
     *  in one of our cities>` yields (V:CvEspionageClasses.cpp:1586-1613) are triggered uniques, which the typed lookups of [forEachFocusUnique] do not reach. Like the buildings' uniques,
     *  only those that say `in this city` count
     */
    @Readonly
    fun forEachFocusTriggeredUnique(trigger: UniqueType, gameContext: GameContext, triggerFilter: (Unique) -> Boolean, op: (Unique) -> Unit) {
        val focus = getFocus() ?: return
        focus.uniqueMap.forEachUnique({ it.isLocalEffect && it.getModifiers(trigger).any(triggerFilter) && it.conditionalsApply(gameContext) }) { it.forEachMultiplied(gameContext, op) }
    }

    /** [forEachFocusUnique] as a sequence, for the callers that still ask for one */
    @Readonly
    fun getFocusUniques(uniqueType: UniqueType): Sequence<Unique> {
        val effects = city.missionEffects.getMatchingTagUniques(uniqueType, city.state)
        val focus = getFocus() ?: return effects
        return effects + focus.getMatchingUniques(uniqueType).filter { !it.isTimedTriggerable }
    }

    /** A turn of the city has passed: the effects of the missions that ran their turns end, and with them the missions (CvCity::DoTurn, V:CvCity.cpp:3264-3293) */
    fun endTurn() {
        if (city.missionEffects.isNotEmpty()) city.missionEffects.endTurn()
        if (city.activeMissions.isNotEmpty()) {
            val turn = city.civ.gameInfo.turns
            city.activeMissions.values.removeAll { it <= turn }
        }
    }

    /**
     *  How well the city keeps foreign spies out (CvCity::CalculateCitySecurity, V:CvCity.cpp:25992-26085), only with [com.unciv.models.ModConstants.espionageLikeVp]: -1 for a city of a
     *  city-state or the barbarians; otherwise the base, the security modifier of the difficulty (of the civilization's, and for an AI also the game's chosen one), the citizens, the trade routes
     *  the civilization has with it and the points of unhappiness the city has over its happiness, plus the `City Security` uniques of the city (its counterspy focus; the buildings and policies
     *  come with their own slice), between 0 and the maximum. [com.unciv.models.ModConstants.EspionageConstants.networkPointsReductionPerSecurity] turns it into the percent of the Network Points of a spy gathering intel here that is lost
     */
    @Readonly
    fun getSecurity(): Int {
        val civ = city.civ
        if (!civ.isMajorCiv) return -1
        val constants = civ.gameInfo.ruleset.modOptions.constants.espionage
        var security = constants.securityBase
        security += civ.getDifficulty().spySecurityModifier
        if (!civ.isHuman()) security += civ.gameInfo.getDifficulty().spySecurityModifier
        security += city.population.population * constants.securityPerCitizen
        val routes = civ.tradeRoutes.routes.count { it.origin == city.location || it.destination == city.location }
        security += routes * constants.securityPerTradeRoute
        val delta = city.cityStats.needs.delta
        if (delta < 0) security += -delta * constants.securityPerExcessUnhappiness
        // buildings, policies and wonders, and the focus of the counterspy, which is a unique source of the city (V:CvCity.cpp:26040-26075)
        city.forEachMatchingUnique(UniqueType.CitySecurity) { if (city.matchesFilter(it.params[1])) security += it.params[0].toInt() }
        return security.coerceIn(0, constants.securityMax)
    }

    @Readonly fun hasSpyOf(civInfo: Civilization): Boolean = civInfo.espionageManager.spyList.any { it.getCityOrNull() == city }

    @Readonly fun getAllStationedSpies(): List<Spy> =
        city.civ.gameInfo.civilizations.flatMap { it.espionageManager.getSpiesInCity(city) }

    fun removeAllPresentSpies(reason: SpyFleeReason) {
        for (spy in getAllStationedSpies()) {
            val notificationString = when (reason) {
                SpyFleeReason.CityDestroyed -> "After the city of [${city.name}] was destroyed, your spy [${spy.name}] has fled back to our hideout."
                SpyFleeReason.CityCaptured -> "After the city of [${city.name}] was conquered, your spy [${spy.name}] has fled back to our hideout."
                SpyFleeReason.CityBought, SpyFleeReason.CityTakenOverByMarriage ->  "After the city of [${city.name}] was taken over, your spy [${spy.name}] has fled back to our hideout."
                else -> "Due to the chaos ensuing in [${city.name}], your spy [${spy.name}] has fled back to our hideout."
            }
            spy.addNotification(notificationString)
            spy.moveTo(null)
        }
    }
}
