package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization

/**
 * The one-time work of a World Congress resolution that comes into force or goes out of force.
 * The ongoing effects are read from the [League] by the engine (trade, happiness, tourism, religion, public opinion)
 * or come from the uniques of the resolution.
 */
object LeagueEffects {
    /** A resolution was enacted: embargoes end the trade routes they forbid (CvTradeClasses.cpp:907-973, LR 4.3.1 and 4.3.2) */
    fun onEnact(league: League, active: ActiveResolution) {
        val gameInfo = league.gameInfo
        val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: return

        if (resolution.embargoesCityStates) {
            // CvGameTrade::ClearAllCityStateTradeRoutes: every route to a city-state ends, the trade unit returns to the origin
            for (civ in gameInfo.civilizations)
                civ.tradeRoutes.cancelRoutesByEmbargo { it.getDestinationCiv()?.isCityState == true }
        }
        if (resolution.embargoesPlayer) {
            val target = active.choice?.let { id -> gameInfo.civilizations.firstOrNull { it.civID == id } }
            if (target != null) {
                // CvGameTrade::ClearAllCivTradeRoutes: routes from or to the target, but not those within the target
                for (civ in gameInfo.civilizations) {
                    civ.tradeRoutes.cancelRoutesByEmbargo { route ->
                        val destination = route.getDestinationCiv()
                        (civ == target || destination == target) && !(civ == target && destination == target)
                    }
                }
            }
        }
        if (gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp) {
            val cityState = active.choice?.let { gameInfo.getCivilizationOrNull(it) }
            if (cityState != null && cityState.isCityState && cityState.isAlive()) {
                if (resolution.opensDoor) openDoor(gameInfo.civilizations, cityState)
                if (resolution.putsInSphere) putInSphere(active.proposer?.let { gameInfo.getCivilizationOrNull(it) }, cityState)
            }
        }
        refresh(league, resolution.bansLuxuryHappiness, resolution.ideologyPressure != 0)
    }

    /**
     *  Open Door (CvActiveResolution::DoEffects, V:CvVotingClasses.cpp:1425-1440), for each living major civilization in the order of the game: it meets [cityState] if it has not, its influence
     *  with it is SET to 40 (up or down; with the update of the ally, as the original sets it with the status update), and the City-State can have no ally. VP runs this once for each member
     *  of the League (every living major civilization here) and sets the flag and clears the ally in each run; here the flag is set once after the influences and the ally cleared once. The
     *  end state is the same: nobody can be the ally of a City-State with the flag, and the ally rule gives none while it is set
     */
    private fun openDoor(civilizations: List<Civilization>, cityState: Civilization) {
        for (major in civilizations) {
            if (!major.isMajorCiv || !major.isAlive()) continue
            if (!major.knows(cityState)) major.diplomacyFunctions.makeCivilizationsMeet(cityState)
            cityState.getDiplomacyManager(major)!!.setInfluence(OPEN_DOOR_INFLUENCE)
        }
        cityState.noAlly = true
        cityState.cityStateFunctions.updateAllyCivForCityState()
    }

    /**
     *  Sphere of Influence (V:CvVotingClasses.cpp:1442-1454), only if the proposer is alive: [cityState] makes peace with it and it is the permanent ally, whatever the influence
     *  ([Civilization.permanentAllyCiv] is read by the ally rule of [com.unciv.logic.civilization.diplomacy.CityStateFunctions.updateAllyCivForCityState])
     */
    private fun putInSphere(proposer: Civilization?, cityState: Civilization) {
        if (proposer == null || !proposer.isAlive()) return
        if (cityState.isAtWarWith(proposer)) cityState.getDiplomacyManager(proposer)!!.makePeace()
        cityState.permanentAllyCiv = proposer
        cityState.cityStateFunctions.updateAllyCivForCityState()
    }

    /**
     *  A resolution was repealed, or stopped being valid: Open Door lets the City-State have an ally again and Sphere of Influence ends the permanent ally (V:CvVotingClasses.cpp:1921-1929,
     *  which acts for the City-State itself). What the enactment set stays: the influence of Open Door, the peace of the Sphere. Otherwise nothing is restored, but what depended on it
     *  is recalculated
     */
    fun onRepeal(league: League, active: ActiveResolution) {
        val gameInfo = league.gameInfo
        val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: return
        if (gameInfo.ruleset.modOptions.constants.leagueSessionRulesLikeVp) {
            val cityState = active.choice?.let { gameInfo.getCivilizationOrNull(it) }
            if (cityState != null && cityState.isCityState) {
                if (resolution.opensDoor) cityState.noAlly = false
                else if (resolution.putsInSphere) cityState.permanentAllyCiv = null
            }
        }
        refresh(league, resolution.bansLuxuryHappiness, resolution.ideologyPressure != 0)
    }

    /** `RESOLUTION_OPEN_DOOR` sets the influence to 40 (V:CvVotingClasses.cpp:1437: `SetFriendshipWithMajor(ePlayer, 40)`) */
    private const val OPEN_DOOR_INFLUENCE = 40f

    private fun refresh(league: League, luxuries: Boolean, ideology: Boolean) {
        for (civ in league.gameInfo.civilizations) {
            if (civ.isBarbarian || civ.isSpectator) continue
            refresh(civ, luxuries, ideology)
        }
    }

    private fun refresh(civ: Civilization, luxuries: Boolean, ideology: Boolean) {
        // updateCivResources also rechecks the monopolies, which a ban and its lifting change (V:CvVotingClasses.cpp:1592, 1818)
        if (luxuries) civ.cache.updateCivResources()
        if (ideology && civ.isMajorCiv) civ.culture.publicOpinion.update()
        for (city in civ.cities) city.cityStats.update()
    }
}
