package com.unciv.logic.automation

import com.unciv.logic.city.City
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization

/**
 *  Empires of the needs model for the tests of the AI readers: one city of [population] citizens whose approval rating is set by how many of them
 *  are unhappy (each specialist costs one unhappy citizen while no median is set, so no yield need exists) and how much happiness the city has:
 *  approval = min(200, happy x 100 / unhappy) / 2 (CvPlayer::CalculateNetHappiness).
 */
object NeedsAiScenarios {
    /**
     *  The next city of [owner] (the first one is its capital) with [unhappy] unhappy citizens (specialists) and [happy] local happiness, and food enough
     *  for no famine; both civilizations' stats are refreshed.
     */
    fun addEmpireCity(world: NeedsTestWorld, owner: Civilization, happy: Int, unhappy: Int, population: Int = maxOf(unhappy, 3)): City {
        val city = world.addCity(population, of = owner)
        world.build(city, "[+500 Food]")
        if (unhappy > 0) world.addSpecialists(city, unhappy)
        if (happy > 0) world.giveHappiness(city, happy)
        refresh(owner)
        return city
    }

    /** Every city of [civ] adds up its own stats, then the civilization hands out its pool and unhappiness (as [NeedsTestWorld.refresh] does for the world's civilization) */
    fun refresh(civ: Civilization) {
        for (city in civ.cities) city.cityStats.update(updateCivStats = false)
        civ.updateStatsForNextTurn()
    }
}
