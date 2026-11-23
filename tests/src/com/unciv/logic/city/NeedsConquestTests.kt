package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What Vox Populi (Release-5.4.8) does to a conquered city and a puppet, with `cityNeedsLikeVp` on: the turns of resistance (CvPlayer::acquireCity),
 *  annexing (CvCity::DoAnnex), a city in resistance or being razed (CvCity::updateNetHappiness, DistributeHappinessToCities), the yields and great
 *  person points of a puppet (CvCity::getYieldModifierMultiplicative, CvPlayer great person points), the tech cost of cities (CvPlayerTechs).
 */
@RunWith(BaseTestRunner::class)
class NeedsConquestTests {
    private var world = NeedsTestWorld()
    private val game get() = world.game
    private val civ get() = world.civ

    private fun conqueror(): Civilization {
        val conqueror = game.addCiv()
        game.addCity(conqueror, game.getTile(HexCoord(0, -6)), replacePalace = true)
        return conqueror
    }

    /** A city of the civilization with [population] citizens, taken as a puppet by a civilization that has not met it; the population left is checked */
    private fun conquer(population: Int, expectedLeft: Int, by: Civilization = conqueror()): City {
        // These tests are about the needs model on top of the original conquest (1 + population / 4 citizens lost, resistance by the citizens left, no boost of the
        // reduction); the Brave New World ruleset switches Vox Populi's conquest on, which keeps 75% of the citizens and adds 5% of reduction
        world.constants.conquestLikeVp = false
        if (civ.cities.isEmpty()) world.addCity(3)
        val target = world.addCity(population)
        target.puppetCity(by)
        assertEquals(expectedLeft, target.population.population)
        return target
    }

    //region Resistance and annexing

    @Test
    fun `conquest resistance lasts two thirds of the population that is left`() {
        val target = conquer(10, 7) // a city of 10 loses 1 + 10 / 4 citizens
        assertEquals(7 * 2 / 3, target.getFlag(CityFlags.Resistance))
    }

    @Test
    fun `without the key resistance lasts the whole population`() {
        world.constants.cityNeedsLikeVp = false
        val target = conquer(10, 7)
        assertEquals(7, target.getFlag(CityFlags.Resistance))
    }

    @Test
    fun `tourism shortens resistance but never below one turn`() {
        val conqueror = conqueror()
        val conquered = civ
        world.addCity(3) // a civilization without cities is defeated and cannot be influenced
        conqueror.diplomacyFunctions.makeCivilizationsMeet(conquered)
        conquered.totalCultureForContests = 1000
        // twice its culture: Dominant, a conquest costs no citizens and takes all the resistance off, but a turn
        conqueror.culture.addInfluence(conquered, 2000)
        assertEquals(100, conqueror.culture.getConquestReductionPercent(conquered))
        val target = conquer(10, 10, conqueror)
        assertTrue("not under 1 turn, V:CvPlayer.cpp:4470", target.isInResistance())
        assertEquals(1, target.getFlag(CityFlags.Resistance))
    }

    @Test
    fun `annexing a city in resistance halves the remaining turns, one turn stays one`() {
        val target = conquer(10, 7)
        target.setFlag(CityFlags.Resistance, 5)
        target.annexCity()
        assertEquals(5 - 5 / 2, target.getFlag(CityFlags.Resistance))
        target.setFlag(CityFlags.Resistance, 1)
        target.annexCity()
        assertEquals(1, target.getFlag(CityFlags.Resistance))
        assertFalse(target.isPuppet)
    }

    @Test
    fun `without the key annexing leaves the resistance as it is`() {
        world.constants.cityNeedsLikeVp = false
        val target = conquer(10, 7)
        target.setFlag(CityFlags.Resistance, 5)
        target.annexCity()
        assertEquals(5, target.getFlag(CityFlags.Resistance))
    }

    @Test
    fun `a city in resistance or being razed has delta minus population and takes no share of the pool`() {
        world.difficulty.baseHappiness = 6
        world.addCity(4)
        val resisting = world.addCity(5)
        val razing = world.addCity(3)
        val normal = world.addCity(2)
        resisting.setFlag(CityFlags.Resistance, 3)
        razing.isBeingRazed = true
        world.refresh()
        assertEquals(-5, world.needsOf(resisting).delta)
        assertEquals(5, world.needsOf(resisting).aggregated)
        assertEquals(-3, world.needsOf(razing).delta)
        for (city in listOf(resisting, razing)) {
            assertEquals(0, world.needsOf(city).happinessFromEmpire)
            assertEquals(0, world.needsOf(city).localHappiness())
        }
        assertTrue("the others share the pool", world.needsOf(normal).happinessFromEmpire > 0)
    }

    //endregion
    //region Puppets

    private fun yieldBuilding(city: City) =
        world.build(city, "[+10 Gold]", "[+10 Science]", "[+10 Culture]", "[+10 Faith]", "[+10 Production]")

    /** Two cities that make the same, the second a puppet */
    private fun puppetAndNormal(): Pair<City, City> {
        world.addCity(3)
        val normal = world.addCity(4)
        val puppet = world.addCity(4)
        yieldBuilding(normal)
        yieldBuilding(puppet)
        puppet.isPuppet = true
        normal.cityStats.update(updateCivStats = false)
        puppet.cityStats.update(updateCivStats = false)
        return puppet to normal
    }

    /** What the city yields of [stat] before its buildings' maintenance, which the share does not touch */
    private fun City.yield(stat: Stat) = cityStats.finalStatList.filterKeys { it != "Maintenance" }.values.sumOf { it[stat].toDouble() }.toFloat()

    @Test
    fun `a puppet keeps 30 percent of its gold science culture and faith, and all of its production`() {
        val (puppet, normal) = puppetAndNormal()
        for (stat in listOf(Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)) {
            assertTrue("$stat of the other city", normal.yield(stat) > 1f)
            assertEquals("$stat", normal.yield(stat) * 0.3f, puppet.yield(stat), 0.01f)
        }
        assertEquals(normal.yield(Stat.Production), puppet.yield(Stat.Production), 0.01f)
    }

    @Test
    fun `the puppet yield share is the constant, and the old minus 25 percent science and culture are gone`() {
        world.constants.needs = world.constants.needs.copy(puppetYieldMultiplier = 50)
        val (puppet, normal) = puppetAndNormal()
        assertEquals(normal.yield(Stat.Science) * 0.5f, puppet.yield(Stat.Science), 0.01f)
        assertNull(puppet.cityStats.getStatPercentBonusesFromPuppetCity())
    }

    @Test
    fun `without the key a puppet loses a quarter of its science and culture only`() {
        world.constants.cityNeedsLikeVp = false
        val (puppet, normal) = puppetAndNormal()
        assertEquals(normal.yield(Stat.Gold), puppet.yield(Stat.Gold), 0.01f)
        assertEquals(normal.yield(Stat.Science) * 0.75f, puppet.yield(Stat.Science), 0.01f)
        assertEquals(normal.yield(Stat.Culture) * 0.75f, puppet.yield(Stat.Culture), 0.01f)
        assertEquals(normal.yield(Stat.Faith), puppet.yield(Stat.Faith), 0.01f)
    }

    @Test
    fun `a puppet adds no great person points`() {
        world.addCity(3)
        val city = world.addCity(4)
        world.addSpecialists(city, 2)
        assertTrue("a normal city does", city.getGreatPersonPoints().values.sum() > 0)
        city.isPuppet = true
        assertTrue(city.getGreatPersonPoints().isEmpty())
        world.constants.cityNeedsLikeVp = false
        assertTrue("without the key it does", city.getGreatPersonPoints().values.sum() > 0)
    }

    @Test
    fun `puppets do not count for the tech cost per city`() {
        world.addCity(3)
        val tech = game.ruleset.technologies.values.first { it.cost > 0 }.name
        val alone = civ.tech.costOfTech(tech)
        world.addCity(3).isPuppet = true
        assertEquals(alone, civ.tech.costOfTech(tech))
        world.addCity(3)
        assertNotEquals("a city that is none does", alone, civ.tech.costOfTech(tech))
    }

    //endregion
}
