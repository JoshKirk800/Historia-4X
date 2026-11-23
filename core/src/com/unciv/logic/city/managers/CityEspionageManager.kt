package com.unciv.logic.city.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Spy
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
    }

    /**
     *  How well the city keeps foreign spies out (CvCity::CalculateCitySecurity, V:CvCity.cpp:25992-26085), only with [com.unciv.models.ModConstants.espionageLikeVp]: -1 for a city of a
     *  city-state or the barbarians; otherwise the base, the security modifier of the difficulty (of the civilization's, and for an AI also the game's chosen one), the citizens, the trade routes
     *  the civilization has with it and the points of unhappiness the city has over its happiness, between 0 and the maximum. The buildings, policies and the counterspy focus come with their
     *  own slices. [com.unciv.models.ModConstants.EspionageConstants.networkPointsReductionPerSecurity] turns it into the percent of the Network Points of a spy gathering intel here that is lost
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
