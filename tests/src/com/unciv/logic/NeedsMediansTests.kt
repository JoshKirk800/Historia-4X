package com.unciv.logic

import com.unciv.json.json
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.NeedYields
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The medians of the yields per citizen (CvGame::updateGlobalMedians, Vox Populi Release-5.4.8) and their storage in the game */
@RunWith(BaseTestRunner::class)
class NeedsMediansTests {
    private val world = NeedsTestWorld()
    private val game get() = world.game
    private val medians get() = world.medians
    private val yieldsOfCities = ArrayList<Pair<City, NeedYields>>()

    /** A city of [population] citizens whose needs read [food] and [production] (of which [tradeProduction] came from trade routes), [gold], [science] and [culture].
     *  Founding another city makes the others calculate their yields again, so the yields are given to them by [updateMedians] */
    private fun city(population: Int, food: Int = 0, production: Int = 0, tradeProduction: Int = 0, gold: Int = 0, science: Int = 0, culture: Int = 0): City {
        val city = world.addCity(population)
        yieldsOfCities.add(city to NeedYields(foodBeforeConsumption = food, production = production, productionWithoutTrade = production - tradeProduction,
            gold = gold, science = science, culture = culture))
        return city
    }

    private fun updateMedians() {
        for ((city, yields) in yieldsOfCities) city.cityStats.needs.update(yields)
        medians.update(game.gameInfo)
    }

    @Test
    fun `median ignores puppets, razing and resistance cities and cities under population 3`() {
        city(3, food = 600, production = 300, gold = 160, science = 100, culture = 50)
        city(2, food = 9999, production = 9999, gold = 9999, science = 9999, culture = 9999)
        city(6, food = 9999, production = 9999, gold = 9999, science = 9999, culture = 9999).isPuppet = true
        city(6, food = 9999, production = 9999, gold = 9999, science = 9999, culture = 9999).isBeingRazed = true
        city(6, food = 9999, production = 9999, gold = 9999, science = 9999, culture = 9999).setFlag(CityFlags.Resistance, 3)
        updateMedians()
        // the one city counts: 900 / 3 = 300 per citizen, 160 / 3 = 53.3, 33.3 and 16.7, smoothed with 0: 0.5 + whole x 0.65, truncated
        assertEquals(195, medians.basic)
        assertEquals(34, medians.gold)
        assertEquals(21, medians.science)
        assertEquals(10, medians.culture)
    }

    @Test
    fun `median picks index size x 50 over 100 of the sorted per-citizen yields and trade routes are not counted`() {
        // basic yields per citizen: 300, 500 (not 3100: 8000 of its production came from trade routes), 1000
        city(3, production = 900)
        city(3, food = 300, production = 9200, tradeProduction = 8000)
        city(3, production = 3000)
        updateMedians()
        assertEquals("index 3 x 50 / 100 = 1: 500", (0.5f + 500 * 0.65f).toInt(), medians.basic)
    }

    @Test
    fun `an even number of cities gives the upper median`() {
        for (production in listOf(900, 1500, 3000, 6000)) city(3, production = production)
        updateMedians()
        assertEquals("sorted 300, 500, 1000, 2000: index 2", (0.5f + 1000 * 0.65f).toInt(), medians.basic)
    }

    @Test
    fun `median is smoothed 65 percent new with truncation`() {
        medians.basic = 100
        city(3, production = 1500) // 500 per citizen
        updateMedians()
        assertEquals("0.5 + 500 x 0.65 + 100 x 0.35 = 360.5", 360, medians.basic)
    }

    @Test
    fun `the element is cut to a whole number before the product`() {
        city(3, production = 1000) // 333.33 per citizen counts as 333
        updateMedians()
        assertEquals("0.5 + 333 x 0.65 = 216.95", 216, medians.basic)
    }

    @Test
    fun `the median of the technologies is the upper median of what the civilizations know and is not smoothed`() {
        val other = game.addCiv()
        game.addCity(other, game.getTile(2, 2), initialPopulation = 3)
        for (tech in listOf("Agriculture", "Pottery", "Archery")) other.tech.addTechnology(tech)
        city(3, production = 900)
        medians.techs = 99
        updateMedians()
        val ours = world.civ.tech.researchedTechnologies.size
        val theirs = other.tech.researchedTechnologies.size
        assertTrue(theirs > ours)
        assertEquals("two civilizations: index 1", maxOf(ours, theirs), medians.techs)
    }

    @Test
    fun `without a city that counts the medians stay as they are`() {
        medians.basic = 11
        medians.gold = 22
        medians.science = 33
        medians.culture = 44
        medians.techs = 5
        city(2, production = 9999) // too small
        updateMedians()
        assertEquals(listOf(11, 22, 33, 44, 5), listOf(medians.basic, medians.gold, medians.science, medians.culture, medians.techs))
    }

    @Test
    fun `the medians survive save and load and an old save loads with zero medians`() {
        val info = GameInfo()
        info.needsMedians.basic = 111
        info.needsMedians.gold = 222
        info.needsMedians.science = 333
        info.needsMedians.culture = 444
        info.needsMedians.techs = 5
        val text = json().toJson(info)
        val loaded = json().fromJson(GameInfo::class.java, text)
        assertEquals(listOf(111, 222, 333, 444, 5), loaded.needsMedians.let { listOf(it.basic, it.gold, it.science, it.culture, it.techs) })

        // a game saved before the medians existed has no such field
        val old = text.replace(Regex("\"?needsMedians\"?\\s*:\\s*\\{[^}]*\\},?"), "")
        assertNotEquals("the field is in the save", text, old)
        val oldLoaded = json().fromJson(GameInfo::class.java, old)
        assertEquals(listOf(0, 0, 0, 0, 0), oldLoaded.needsMedians.let { listOf(it.basic, it.gold, it.science, it.culture, it.techs) })
    }

    @Test
    fun `GameInfo clone copies the medians`() {
        val info = GameInfo()
        info.needsMedians.basic = 111
        info.needsMedians.techs = 7
        val clone = info.clone()
        assertEquals(111, clone.needsMedians.basic)
        assertEquals(7, clone.needsMedians.techs)
        clone.needsMedians.basic = 5
        assertEquals("a copy, not the same object", 111, info.needsMedians.basic)
    }
}
