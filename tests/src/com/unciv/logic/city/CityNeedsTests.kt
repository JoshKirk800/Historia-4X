package com.unciv.logic.city

import com.unciv.logic.civilization.transients.CapitalConnectionsFinder.CapitalConnectionMedium
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.ai.WorldSizeDef
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.EnumSet

/**
 *  Vox Populi's needs of a city (CvCity::GetUnhappinessAggregated, GetLocalHappiness, updateNetHappiness and the sources they add up), Release-5.4.8,
 *  with `cityNeedsLikeVp` on. The yields come from [NeedYields] and the medians are set, so each need is a number worked out by hand.
 */
@RunWith(BaseTestRunner::class)
class CityNeedsTests {
    private var world = NeedsTestWorld()
    private val game get() = world.game
    private val civ get() = world.civ

    /** A route that starts in [from] and ends in [to], two cities of the civilization */
    private fun addRoute(from: City, to: City) {
        val route = TradeRoute(from.location, to.location, TradeRouteKind.Food, false, "Caravan", listOf(from.location, to.location), 2, 30, 0)
        route.setTransients(civ)
        civ.tradeRoutes.routes.add(route)
    }

    //region Yield needs and their modifiers

    /** The worked example of CvCity::GetDistressRaw: median 500 x (100 + 17) %, yield 2400, 5 citizens */
    @Test
    fun `distress is ceil of population minus food and production over the modified median`() {
        world.addCity(5)
        val target = world.addCity(5)
        world.addCity(5)
        world.addCity(5)
        world.medians.basic = 500
        val needs = world.needsOf(target)
        needs.update(NeedYields(foodBeforeConsumption = 1000, production = 1400))

        assertEquals("3 cities but the capital x 5 plus 20 citizens x 125 / 1000", 17, needs.empireSizeModifier())
        assertEquals(17, needs.allNeedsModifier())
        assertEquals(585f, needs.modifiedMedian(NeedType.Distress), 0.001f)
        assertEquals("ceil(5 - 2400 / 585)", 1, needs.distress)

        needs.update(NeedYields(foodBeforeConsumption = 1000, production = 2100))
        assertEquals("ceil(5 - 3100 / 585) is below 0", 0, needs.distress)
        needs.update(NeedYields())
        assertEquals("nothing yielded: every citizen is unhappy, at most the population", 5, needs.distress)
    }

    @Test
    fun `poverty illiteracy and boredom read gold science and culture`() {
        world.addCity(4)
        val target = world.addCity(4) // 5 + 8 x 125 / 1000 = 6 points
        world.medians.basic = 300
        world.medians.gold = 300
        world.medians.science = 200
        world.medians.culture = 100
        val needs = world.needsOf(target)
        assertEquals(6, needs.allNeedsModifier())
        needs.update(NeedYields(gold = 600, science = 200, culture = 0, foodBeforeConsumption = 99999))
        assertEquals("ceil(4 - 600 / 318)", 3, needs.poverty)
        assertEquals("ceil(4 - 200 / 212)", 4, needs.illiteracy)
        assertEquals("ceil(4 - 0 / 106)", 4, needs.boredom)
        assertEquals("the food covers distress, and it does not help the others", 0, needs.distress)
    }

    @Test
    fun `no needs unhappiness while all four medians are zero`() {
        val city = world.addCity(5)
        val needs = world.needsOf(city)
        needs.update(NeedYields())
        assertEquals(0f, needs.modifiedMedian(NeedType.Distress), 0f)
        assertEquals(0, needs.distress + needs.poverty + needs.illiteracy + needs.boredom)
        assertEquals(0, needs.aggregated)
    }

    @Test
    fun `the capital needs 25 percent more`() {
        val capital = world.addCity(4)
        val other = world.addCity(4)
        assertTrue(capital.isCapital())
        world.medians.basic = 500
        assertEquals("5 for the second city, 1 for 8 citizens", 6, world.needsOf(capital).empireSizeModifier())
        assertEquals(25 + 6, world.needsOf(capital).allNeedsModifier())
        assertEquals(6, world.needsOf(other).allNeedsModifier())
        assertEquals(500f * 131 / 100, world.needsOf(capital).modifiedMedian(NeedType.Distress), 0.001f)
    }

    @Test
    fun `a tech lead adds 1 percent per tech above the median and a lag subtracts 2 per tech`() {
        val capital = world.addCity(3)
        val needs = world.needsOf(capital)
        val known = civ.tech.researchedTechnologies.size
        assertEquals(25, needs.allNeedsModifier())
        world.medians.techs = known - 3
        assertEquals("3 above", 28, needs.allNeedsModifier())
        world.medians.techs = known + 2
        assertEquals("2 below", 21, needs.allNeedsModifier())
        world.constants.needs = world.constants.needs.copy(techNeedPerTechAboveMedian = 50, techNeedPerTechBelowMedian = -300)
        world.medians.techs = known - 3
        assertEquals("1.5 points per tech, truncated", 26, needs.allNeedsModifier())
        world.medians.techs = known + 2
        assertEquals("3 points per tech", 19, needs.allNeedsModifier())
    }

    @Test
    fun `empire size modifier is 5 points per non-puppet city but the capital plus 1 per 8 citizens`() {
        world.addCity(5)
        val second = world.addCity(5)
        val third = world.addCity(5)
        world.addCity(5)
        assertEquals("15 for 3 cities, 2 for 20 citizens", 17, world.needsOf(second).empireSizeModifier())
        third.isPuppet = true
        assertEquals("2 cities: 10, 15 citizens outside the puppet: 1", 11, world.needsOf(second).empireSizeModifier())
    }

    @Test
    fun `empire size modifier is scaled by the world's percent, at most 100, and capped`() {
        world.addCity(5)
        val second = world.addCity(5)
        world.addCity(5)
        world.addCity(5)
        val needs = world.needsOf(second)
        val rows = game.ruleset.ai.worldSizes
        val row = game.ruleset.ai.worldSizeForTiles(game.tileMap.tileList.size)
        fun setWorldPercent(percent: Int) {
            rows[rows.indexOf(game.ruleset.ai.worldSizeForTiles(game.tileMap.tileList.size))] =
                WorldSizeDef().also { it.name = row.name; it.numCitiesUnhappinessPercent = percent }
        }
        setWorldPercent(60)
        assertEquals("17 x 60 / 100", 10, needs.empireSizeModifier())
        setWorldPercent(150)
        assertEquals("a percent above 100 counts as 100", 17, needs.empireSizeModifier())
        setWorldPercent(100)
        world.constants.needs = world.constants.needs.copy(empireSizeNeedCap = 12)
        assertEquals("the cap", 12, needs.empireSizeModifier())
        world.constants.needs = world.constants.needs.copy(empireSizeNeedCap = 100000)
        world.difficulty.empireSizeNeedModifier = 50
        assertEquals("the difficulty changes it by percent: 17 x 150 / 100", 25, needs.empireSizeModifier())
    }

    @Test
    fun `empire size reduction cannot push the modifier below zero or above itself`() {
        world.addCity(5)
        val second = world.addCity(5)
        world.addCity(5)
        world.addCity(5)
        val needs = world.needsOf(second)
        assertEquals(17, needs.reducedEmpireSizeModifier())
        world.build(second, "Reduces the empire size need modifier by [5] [in this city]")
        assertEquals(12, needs.reducedEmpireSizeModifier())
        world.build(second, "Reduces the empire size need modifier by [3] [in all cities]")
        assertEquals("local and global add up", 9, needs.reducedEmpireSizeModifier())
        world.build(second, "Reduces the empire size need modifier by [100] [in this city]")
        assertEquals("not below 0", 0, needs.reducedEmpireSizeModifier())
        assertEquals("the unreduced modifier is still there", 17, needs.empireSizeModifier())
        assertEquals("another city has the global 3 only", 14, world.needsOf(civ.cities.last()).reducedEmpireSizeModifier())
    }

    //endregion
    //region Flat reductions, growth

    @Test
    fun `flat reductions of the city and the empire come off and a need never goes below zero or above the population`() {
        world.addCity(5)
        val target = world.addCity(5)
        val other = world.addCity(5)
        world.medians.gold = 500
        world.medians.basic = 500
        val needs = world.needsOf(target)
        needs.update(NeedYields())
        assertEquals("every citizen is unhappy", 5, needs.poverty)
        world.build(target, "Reduces [Poverty] by [2] [in this city]")
        needs.update(NeedYields())
        assertEquals(3, needs.poverty)
        assertEquals("the reduction is of the poverty only", 5, needs.distress)
        world.build(other, "Reduces [Poverty] by [1] [in all cities]")
        needs.update(NeedYields())
        assertEquals("the global one of another city's building counts too", 2, needs.poverty)
        world.build(target, "Reduces [Poverty] by [10] [in this city]")
        needs.update(NeedYields())
        assertEquals(0, needs.poverty)
        assertEquals("another city of the empire got only the global one", 4, world.needsOf(other).also { it.update(NeedYields()) }.poverty)
    }

    @Test
    fun `a need after growth uses population plus 1 and the extra yield`() {
        val city = world.addCity(3) // the capital: 500 x 125 / 100 = 625 per citizen
        world.medians.basic = 500
        val needs = world.needsOf(city)
        needs.update(NeedYields(foodBeforeConsumption = 1250))
        assertEquals(625f, needs.modifiedMedian(NeedType.Distress), 0.001f)
        assertEquals("ceil(3 - 2)", 1, needs.yieldNeed(NeedType.Distress))
        assertEquals("ceil(4 - 2)", 2, needs.yieldNeed(NeedType.Distress, growth = 1))
        assertEquals("ceil(3 - 3)", 0, needs.yieldNeed(NeedType.Distress, extraYield100 = 625))
        assertEquals("ceil(4 - 3)", 1, needs.yieldNeed(NeedType.Distress, growth = 1, extraYield100 = 625))
        assertEquals("the potential unhappiness of the four yield needs", 1, needs.potentialUnhappinessWithGrowth())
    }

    //endregion
    //region Other sources

    private fun foundReligions(population: Int, majority: Int, minority: Int): City {
        val city = world.addCity(population)
        val religion = game.addReligion(civ)
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        val other = game.addReligion(game.addCiv())
        city.religion.addPressure(religion.name, majority * 1000)
        city.religion.addPressure(other.name, minority * 1000)
        return city
    }

    @Test
    fun `religious unrest is half a point per minority citizen, truncated, scaled by the need modifier, reduced, capped`() {
        val city = foundReligions(12, 7, 5)
        val needs = world.needsOf(city)
        needs.update(NeedYields())
        assertEquals("5 minority citizens x 0.5 x 125 % (the capital), truncated", 3, needs.religiousUnrest)
        world.build(city, "Reduces [Religious Unrest] by [1] [in this city]")
        needs.update(NeedYields())
        assertEquals(2, needs.religiousUnrest)
        world.build(city, "Reduces [Religious Unrest] by [10] [in all cities]")
        needs.update(NeedYields())
        assertEquals(0, needs.religiousUnrest)
    }

    @Test
    fun `no religious unrest without a religion majority above pantheon`() {
        val city = world.addCity(12)
        val pantheon = game.addReligion(civ) // no founder belief: a pantheon
        city.religion.addPressure(pantheon.name, 7000)
        city.religion.addPressure(game.addReligion(game.addCiv()).name, 5000)
        val needs = world.needsOf(city)
        needs.update(NeedYields())
        assertEquals(0, needs.religiousUnrest)
        assertEquals("nothing is followed", 0, world.needsOf(world.addCity(6)).also { it.update(NeedYields()) }.religiousUnrest)
    }

    @Test
    fun `isolation is a third of the population when not connected, none in the capital or with a trade route to a connected city`() {
        val capital = world.addCity(9)
        val city = world.addCity(9)
        val needs = world.needsOf(city)
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        needs.update(NeedYields())
        assertEquals("9 x 0.34, truncated", 3, needs.isolation)
        world.needsOf(capital).update(NeedYields())
        assertEquals("not in the capital", 0, world.needsOf(capital).isolation)
        city.population.setPopulation(4)
        needs.update(NeedYields())
        assertEquals("4 x 0.34 = 1.36", 1, needs.isolation)

        civ.cache.citiesConnectedToCapitalToMediums = mapOf(city to EnumSet.of(CapitalConnectionMedium.Road))
        needs.update(NeedYields())
        assertEquals("a road", 0, needs.isolation)

        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        addRoute(city, capital)
        needs.update(NeedYields())
        assertEquals("a trade route to the capital", 0, needs.isolation)

        // to a city that is connected to the capital
        civ.tradeRoutes.routes.clear()
        val far = world.addCity(3)
        civ.cache.citiesConnectedToCapitalToMediums = mapOf(far to EnumSet.of(CapitalConnectionMedium.Road))
        needs.update(NeedYields())
        assertEquals("the other city is connected, this one is not", 1, needs.isolation)
        addRoute(far, city)
        needs.update(NeedYields())
        assertEquals("a trade route to a connected city, whichever way it goes", 0, needs.isolation)
    }

    @Test
    fun `pillaged tiles count half, truncated`() {
        val city = world.addCity(8)
        val neighbours = city.getCenterTile().neighbors.toList()
        for (tile in neighbours) game.addTileToCity(city, tile)
        val needs = world.needsOf(city)
        needs.update(NeedYields())
        assertEquals(0, needs.pillaged)
        for (tile in neighbours.take(5)) tile.improvementIsPillaged = true
        needs.update(NeedYields())
        assertEquals("5 x 0.5, truncated", 2, needs.pillaged)
        for (tile in neighbours) tile.improvementIsPillaged = true
        needs.update(NeedYields())
        assertEquals(3, needs.pillaged)
    }

    @Test
    fun `famine counts the food deficit, at most the population, and not when food becomes production`() {
        val city = world.addCity(5)
        val needs = world.needsOf(city)
        needs.update(NeedYields(foodBeforeConsumption = 300, foodConsumption = 600))
        assertEquals("3 food short", 3, needs.famine)
        needs.update(NeedYields(foodBeforeConsumption = 0, foodConsumption = 1000))
        assertEquals("10 short, 5 citizens", 5, needs.famine)
        needs.update(NeedYields(foodBeforeConsumption = 300, foodConsumption = 600, isFoodProduction = true))
        assertEquals("a settler being built", 0, needs.famine)
        needs.update(NeedYields(foodBeforeConsumption = 700, foodConsumption = 600))
        assertEquals("a surplus", 0, needs.famine)
    }

    @Test
    fun `an occupied city costs its citizens, unless it has a courthouse`() {
        world.addCity(6)
        val annexed = world.addCity(6)
        annexed.foundingCivObject = game.addCiv()
        val needs = world.needsOf(annexed)
        needs.update(NeedYields())
        assertEquals(6, needs.occupation)
        constantsUnhappinessPerOccupiedPopulation(0.5f)
        needs.update(NeedYields())
        assertEquals("half a citizen each", 3, needs.occupation)
        world.build(annexed, "Removes extra unhappiness from annexed cities")
        needs.update(NeedYields())
        assertEquals(0, needs.occupation)
        assertTrue("and it gives and gets happiness again", needs.givesAndGetsHappiness())
    }

    private fun constantsUnhappinessPerOccupiedPopulation(value: Float) {
        world.constants.needs = world.constants.needs.copy(unhappinessPerOccupiedPopulation = value)
    }

    //endregion
    //region Adding up

    @Test
    fun `the sum of sources is capped at the population before specialists are added`() {
        world.addCity(4)
        val city = world.addCity(4)
        world.medians.basic = 500
        world.medians.gold = 500
        civ.cache.citiesConnectedToCapitalToMediums = emptyMap()
        val needs = world.needsOf(city)
        needs.update(NeedYields())
        assertEquals(4, needs.distress)
        assertEquals(4, needs.poverty)
        assertEquals(1, needs.isolation)
        assertEquals("9 unhappy citizens, 4 citizens", 4, needs.aggregated)
        world.addSpecialists(city, 2)
        needs.update(NeedYields())
        assertEquals("the specialists are on top", 4 + 2, needs.aggregated)
        assertEquals(-6, needs.delta)
        assertEquals(setOf("Distress", "Poverty", "Isolation", "Urbanization"), needs.breakdown.keys)
        assertEquals("sorted by size", listOf(4, 4, 2, 1), needs.breakdown.values.toList())
    }

    @Test
    fun `free specialists from the capital, the empire and the city come off first`() {
        val capital = world.addCity(6)
        val city = world.addCity(6)
        world.addSpecialists(capital, 3)
        world.addSpecialists(city, 3)
        world.needsOf(capital).update(NeedYields())
        world.needsOf(city).update(NeedYields())
        assertEquals(3, world.needsOf(capital).specialists)
        assertEquals(3, world.needsOf(city).specialists)

        world.build(capital, "The first [2] specialists cause no unhappiness [in capital]")
        world.needsOf(capital).update(NeedYields())
        world.needsOf(city).update(NeedYields())
        assertEquals("the capital's own", 1, world.needsOf(capital).specialists)
        assertEquals("not for other cities", 3, world.needsOf(city).specialists)

        world.build(city, "The first [1] specialists cause no unhappiness [in this city]")
        world.build(city, "The first [1] specialists cause no unhappiness [in all cities]")
        world.needsOf(capital).update(NeedYields())
        world.needsOf(city).update(NeedYields())
        assertEquals("the city's and the empire's", 1, world.needsOf(city).specialists)
        assertEquals("the capital's 2 and the empire's 1 are all of its 3 specialists", 0, world.needsOf(capital).specialists)
        world.build(city, "The first [10] specialists cause no unhappiness [in this city]")
        world.needsOf(city).update(NeedYields())
        assertEquals("never more than the specialists", 0, world.needsOf(city).specialists)
    }

    @Test
    fun `half-specialist policy rounds up`() {
        val city = world.addCity(6)
        val needs = world.needsOf(city)
        world.build(city, "[-50]% Unhappiness from [Specialists] [in all cities]")
        for ((specialists, expected) in listOf(1 to 1, 2 to 1, 3 to 2, 5 to 3)) {
            city.population.specialistAllocations.clear()
            world.addSpecialists(city, specialists)
            needs.update(NeedYields())
            assertEquals("$specialists specialists", expected, needs.specialists)
        }
    }

    @Test
    fun `a puppet has unhappiness population over 4, no local happiness and no needs, except under Venice`() {
        world.addCity(5)
        val puppet = world.addCity(9)
        world.giveHappiness(puppet, 5)
        world.addSpecialists(puppet, 3)
        world.medians.basic = 500
        puppet.isPuppet = true
        world.refresh()
        val needs = world.needsOf(puppet)
        assertEquals("9 / 4", 2, needs.aggregated)
        assertEquals("no yield needs, no urbanization", 0, needs.distress + needs.specialists)
        assertEquals("no local happiness", 0, needs.localHappiness())
        assertEquals("and no delta", 0, needs.delta)

        val venice = NeedsTestWorld("May not annex cities")
        venice.addCity(5)
        val vassal = venice.addCity(9)
        venice.giveHappiness(vassal, 5)
        vassal.isPuppet = true
        venice.refresh()
        assertEquals("pays population / 4", 2, venice.needsOf(vassal).aggregated)
        assertEquals("but keeps its local happiness", 5, venice.needsOf(vassal).localHappiness())
        assertEquals(3, venice.needsOf(vassal).delta)
    }

    //endregion
    //region Local happiness

    @Test
    fun `local happiness is not limited by the population`() {
        val city = world.addCity(2)
        world.giveHappiness(city, 6)
        world.refresh()
        assertEquals(6, world.needsOf(city).happinessWithoutEmpire)
        assertNull("no population limit entry", city.cityStats.happinessList["Population limit on local happiness"])
        assertEquals(6f, city.cityStats.localHappiness, 0f)
    }

    @Test
    fun `buildings, policies, beliefs and the capital default add up as in the source`() {
        val capital = world.addCity(2)
        val other = world.addCity(2)
        world.difficulty.baseHappinessCapital = 2
        world.giveHappiness(capital, 6)
        world.build(capital, "[+1 Happiness] [in all cities]")
        world.build(capital, "[1] Happiness per [2] citizens [in all cities]")
        world.build(capital, "[2] Happiness from the first [2] specialists [in all cities]")
        world.build(capital, "[3] Happiness per active trade route [in all cities]")
        world.addSpecialists(capital, 3)
        addRoute(capital, other)
        world.refresh()

        // 6 from the building, 1 per city, 1 for 2 citizens, 2 for each of the first 2 of 3 specialists, 3 for the route, 2 as the capital
        assertEquals(6 + 1 + 1 + 4 + 3 + 2, world.needsOf(capital).happinessWithoutEmpire)
        assertEquals("the other city has the per city and per citizen ones: 1 + 1", 2, world.needsOf(other).happinessWithoutEmpire)
        assertEquals(2f, capital.cityStats.happinessList["Capital"]!!, 0f)
        assertNull(other.cityStats.happinessList["Capital"])
    }

    @Test
    fun `happiness per great works counts every second number works`() {
        val capital = world.addCity(2)
        world.build(capital, "[2] Happiness per [3] Great Works [in all cities]")
        world.refresh()
        assertEquals(0, world.needsOf(capital).happinessWithoutEmpire)
        val gallery = game.createBuilding("Has [7] [Art/Artifact] Great Work slots")
        capital.cityConstructions.addBuilding(gallery)
        val work = game.createGreatWork()
        repeat(7) { capital.greatWorks.place(GreatWorkInstance(work.name, civ.civName, "Ancient era", 1), GreatWorkSlot(capital, gallery, it)) }
        world.refresh()
        assertEquals("7 works: 2 times 2", 4, world.needsOf(capital).happinessWithoutEmpire)
    }

    //endregion
    //region Effects

    @Test
    fun `growth is plus 2 per point of positive delta and minus 10 per negative, empire unhappiness removes the bonus and adds the penalty`() {
        val happy = world.addCity(3)
        val unhappy = world.addCity(2)
        for (city in listOf(happy, unhappy)) world.build(city, "[+10 Food]")
        world.giveHappiness(happy, 5)
        world.addSpecialists(unhappy, 2)
        world.refresh()
        assertEquals("5 happy, 2 unhappy citizens: approval 100", 100, civ.getApproval())
        assertEquals(5, world.needsOf(happy).delta)
        assertEquals(-2, world.needsOf(unhappy).delta)
        assertEquals("delta 5 x 2", 10, world.needsOf(happy).growthModifier())
        assertEquals("delta -2 x 10", -20, world.needsOf(unhappy).growthModifier())
        assertEquals(0.1f * happy.cityStats.excessFood, happy.cityStats.finalStatList["Happiness"]!!.food, 0.001f)
        assertEquals(-0.2f * unhappy.cityStats.excessFood, unhappy.cityStats.finalStatList["Happiness"]!!.food, 0.001f)

        // 4 specialists and 2 for the isolation in the second city: 5 happy, 6 unhappy citizens, approval 83 / 2 = 41
        unhappy.population.specialistAllocations.clear()
        world.addSpecialists(unhappy, 4)
        unhappy.population.addPopulation(4)
        world.refresh()
        assertEquals(41, civ.getApproval())
        assertEquals("the bonus is gone, the penalty is 2.5 x -9 truncated", -22, world.needsOf(happy).growthModifier())
        assertEquals(-0.22f * happy.cityStats.excessFood, happy.cityStats.finalStatList["Happiness"]!!.food, 0.001f)
        assertEquals("its delta of -6 and the penalty", -60 - 22, world.needsOf(unhappy).growthModifier())
    }

    @Test
    fun `We Love The King Day adds 25 percent whatever the happiness`() {
        val city = world.addCity(4)
        world.build(city, "[+10 Food]")
        world.addSpecialists(city, 4)
        city.setFlag(CityFlags.WeLoveTheKing, 5)
        world.refresh()
        assertTrue("the only city, nothing happy", civ.stats.needs.isSuperUnhappy())
        assertTrue(civ.getHappiness() < 0)
        assertEquals("-4 x 10 and the penalty of the empire, at most", -100, world.needsOf(city).growthModifier())
        val excess = city.cityStats.excessFood
        assertTrue("there is food", excess > 0f)
        assertEquals(excess / 4, city.cityStats.finalStatList["We Love The King Day"]!!.food, 0.001f)
    }

    @Test
    fun `the growth modifiers add up to minus 100 percent at the most`() {
        world = NeedsTestWorld(globalUniques = listOf("[-80]% growth [in all cities]"))
        val city = world.addCity(4)
        world.build(city, "[+40 Food]")
        world.giveHappiness(city, 8)
        world.addSpecialists(city, 4)
        world.refresh()
        assertEquals("delta 4 x 2, with the -80% of the ruleset: -72%", 8, world.needsOf(city).growthModifier())
        assertTrue(city.cityStats.excessFood > 0f)
        assertNull(city.cityStats.finalStatList["Growth modifiers limit"])

        // 10 citizens that cost, 8 happy ones: approval 40, delta -2 (-20%) and the empire's -25%
        city.population.specialistAllocations.clear()
        world.addSpecialists(city, 10)
        city.population.addPopulation(6)
        world.refresh()
        assertEquals(40, civ.getApproval())
        assertEquals(-45, world.needsOf(city).growthModifier())
        assertNotNull(city.cityStats.finalStatList["Growth modifiers limit"])
        assertEquals("what the modifiers leave is not below 0", 0f, city.cityStats.finalStatList.values.sumOf { it.food.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `a settler costs 10 percent more production per point of local deficit plus the empire penalty, at most 75, and a combat unit only the local part`() {
        val settler = game.ruleset.units["Settler"]!!
        val warrior = game.ruleset.units["Warrior"]!!
        val worker = game.ruleset.units["Worker"]!!

        // one city, 5 specialists and 4 happiness: delta -1, approval 4 x 100 / 5 / 2 = 40
        val city = world.addCity(5)
        world.build(city, "[+60 Food]") // no famine
        world.addSpecialists(city, 5)
        world.giveHappiness(city, 4)
        world.refresh()
        assertEquals(40, civ.getApproval())
        assertEquals(-1, world.needsOf(city).delta)
        assertEquals("-10 and 2.5 x -10", -35, world.needsOf(city).unitProductionPenalty(settler))
        assertEquals("only the local part", -10, world.needsOf(city).unitProductionPenalty(warrior))
        assertEquals("not a unit of either kind", 0, world.needsOf(city).unitProductionPenalty(worker))
        assertEquals(35, world.needsOf(city).settlerPurchaseSurcharge(settler))
        assertEquals(0, world.needsOf(city).settlerPurchaseSurcharge(warrior))

        city.cityStats.update(settler, updateCivStats = false)
        assertEquals(-35f, city.cityStats.statPercentBonusTree.children["Unhappiness"]!!.totalStats[Stat.Production], 0f)
        city.cityStats.update(warrior, updateCivStats = false)
        assertEquals(-10f, city.cityStats.statPercentBonusTree.children["Unhappiness"]!!.totalStats[Stat.Production], 0f)
        city.cityStats.update(worker, updateCivStats = false)
        assertNull(city.cityStats.statPercentBonusTree.children["Unhappiness"])

        // 14 citizens that cost, 4 that are happy: delta -10 is -100% for a combat unit, at most -75
        city.population.specialistAllocations.clear()
        world.addSpecialists(city, 14)
        city.population.addPopulation(9)
        world.refresh()
        assertEquals(-10, world.needsOf(city).delta)
        assertEquals(-75, world.needsOf(city).unitProductionPenalty(warrior))
        assertEquals("-100 and the empire's -75", -75, world.needsOf(city).unitProductionPenalty(settler))
        assertEquals(75, world.needsOf(city).settlerPurchaseSurcharge(settler))
    }

    @Test
    fun `the gold price of a settler rises by the same share`() {
        val settler = game.ruleset.units["Settler"]!!
        val warrior = game.ruleset.units["Warrior"]!!
        val capital = world.addCity(2)
        val city = world.addCity(2)
        world.giveHappiness(capital, 30) // the empire stays happy: approval 100
        world.refresh()
        val settlerPrice = settler.getStatBuyCost(city, Stat.Gold)!!
        val warriorPrice = warrior.getStatBuyCost(city, Stat.Gold)!!

        world.addSpecialists(city, 2) // delta -2
        world.refresh()
        assertEquals(-2, world.needsOf(city).delta)
        assertEquals("20% more", settlerPrice * 1.2, settler.getStatBuyCost(city, Stat.Gold)!!.toDouble(), 10.0)
        assertTrue(settler.getStatBuyCost(city, Stat.Gold)!! > settlerPrice)
        assertEquals("combat units cost no more", warriorPrice, warrior.getStatBuyCost(city, Stat.Gold)!!)
        assertEquals("nor does a settler in a happy city", settlerPrice, settler.getStatBuyCost(capital, Stat.Gold)!!)
    }

    @Test
    fun `the production of a settler under unhappiness is not read by distress`() {
        val settler = game.ruleset.units["Settler"]!!
        val city = world.addCity(5)
        world.addSpecialists(city, 5)
        world.medians.basic = 300
        world.refresh()
        val before = world.needsOf(city).yields.production
        city.cityStats.update(settler, updateCivStats = false)
        assertTrue("a penalty on the settler is on", world.needsOf(city).unitProductionPenalty(settler) < 0)
        assertEquals("the production for the needs is the same", before, world.needsOf(city).yields.production)
    }

    //endregion
}
