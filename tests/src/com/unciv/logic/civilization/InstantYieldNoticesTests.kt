package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The notifications of instant yields ([InstantYieldNotices]): one for an event with each city and each source named, and for the events that happen again and again in a turn
 *  one for the whole turn, as Vox Populi's CvPlayer::doInstantYield and CvPlayer::doTurn do (V:CvPlayer.cpp:27347-27560, 10531-10547). A human civilization plays.
 */
@RunWith(BaseTestRunner::class)
class InstantYieldNoticesTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(5)
        civ = game.addCiv(isPlayer = true)
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 2)
        second = game.addCity(civ, game.getTile(HexCoord(4, 0)), initialPopulation = 2)
        game.gameInfo.turns = 10
        constants.instantYieldsUseInstantYieldPercent = true
        civ.notifications.clear()
    }

    private fun City.build(vararg uniques: String): Building = game.createBuilding(*uniques).also { cityConstructions.addBuilding(it) }
    private fun texts() = civ.notifications.map { it.text }

    @Test
    fun `an instant yield notification names the source`() {
        val shrine = capital.build("Gain [5]% of the triggering amount as [Culture] <upon buying something with [Gold]> <in this city>")

        InstantYields.fire(UniqueType.TriggerUponBuying, civ, capital, 100, capital.state, "due to buying [Granary]", triggerFilter = { it.params[0] == "Gold" })

        assertEquals("the event, the city and the building that gave it, in one notification",
            listOf("{Purchasing a Unit or Building}: {[${capital.name}]: [+5 Culture] from [${shrine.name}]}"), texts())
        assertEquals(5, civ.getStatReserve(Stat.Culture))
    }

    @Test
    fun `every source of an event is named in the one notification, a policy's without a city`() {
        val first = capital.build("Gain [5] [Gold] <upon buying something with [Gold]> <in this city>", "Gain [2] [Culture] <upon buying something with [Gold]> <in this city>")
        val policy = game.createPolicy("Gain [7] [Gold] <upon buying something with [Gold]>")
        civ.policies.adopt(policy, true)
        civ.notifications.clear()

        InstantYields.fire(UniqueType.TriggerUponBuying, civ, capital, 100, capital.state, "due to buying [Granary]", triggerFilter = { it.params[0] == "Gold" })

        val text = texts().single()
        assertTrue(text, text.startsWith("{Purchasing a Unit or Building}: "))
        assertTrue(text, "{[${capital.name}]: [+5 Gold, +2 Culture] from [${first.name}]}" in text)
        assertTrue(text, "{[+7 Gold] from [${policy.name}]}" in text)
    }

    @Test
    fun `an event that happens again and again in a turn is told once, at the end of the turn, with all cities`() {
        val farm = capital.build("Gain [3] [Gold] <upon population growth> <in this city> <for every [Triggering amount]>")
        second.build("Gain [3] [Gold] <upon population growth> <in this city> <for every [Triggering amount]>")
        val policy = game.createPolicy("Gain [7] [Gold] <upon population growth>")
        civ.policies.adopt(policy, true)
        civ.notifications.clear()

        capital.population.addPopulation(1)
        second.population.addPopulation(2)
        assertTrue("nothing is told while the turn goes on", texts().none { it.startsWith("{Birth of a Citizen}") })

        civ.instantYieldNotices.flushDeferred(civ)

        val text = texts().single { it.startsWith("{Birth of a Citizen}") }
        assertTrue(text, "{[${capital.name}]: [+3 Gold] from [${farm.name}]}" in text)
        assertTrue(text, "{[${second.name}]: [+6 Gold] from [" in text)
        assertTrue("the policy's two growths are added into one line", "{[+14 Gold] from [${policy.name}]}" in text)

        civ.notifications.clear()
        civ.instantYieldNotices.flushDeferred(civ)
        assertTrue("and it is not told twice", texts().isEmpty())
    }

    @Test
    fun `without Vox Populi's instant yields every gain has the notification it always had`() {
        constants.instantYieldsUseInstantYieldPercent = false
        capital.build("Gain [5] [Gold] <upon buying something with [Gold]> <in this city>")

        InstantYields.fire(UniqueType.TriggerUponBuying, civ, capital, 100, capital.state, "due to buying [Granary]", triggerFilter = { it.params[0] == "Gold" })

        val text = texts().single()
        assertTrue(text, "Gained [+5 Gold]" in text && "due to buying [Granary]" in text)
        assertTrue(text, !text.contains("Purchasing a Unit or Building"))
    }

    @Test
    fun `the text of a notification leaves out the city or the source it has none of`() {
        val stats = Stats().apply { add(Stat.Gold, 4f) }
        val event = UniqueType.TriggerUponWltkdStart.name

        assertEquals("{Start of We Love The King Day}: {[Berlin]: [+4 Gold] from [Temple]}; {[+4 Gold] from [Policy]}; {[Rome]: [+4 Gold]}; {Gained [+4 Gold]}",
            InstantYieldNotices.text(event, listOf(
                InstantYieldNotices.Line("Berlin", "Temple", stats), InstantYieldNotices.Line(null, "Policy", stats),
                InstantYieldNotices.Line("Rome", null, stats), InstantYieldNotices.Line(null, null, stats))))
        assertNull("an event with no name keeps its own notifications", InstantYieldNotices.text("TriggerUponDefeatingUnit", listOf(InstantYieldNotices.Line("A", "B", stats))))
        assertNull("nothing gained, nothing told", InstantYieldNotices.text(event, listOf(InstantYieldNotices.Line("A", "B", Stats()))))
    }
}
