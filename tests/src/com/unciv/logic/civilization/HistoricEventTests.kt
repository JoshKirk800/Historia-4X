package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's historic events: CvPlayer::GetHistoricEventTourism, getYieldPerTurnHistory, ChangeNumHistoricEvents and where they happen */
@RunWith(GdxTestRunner::class)
class HistoricEventTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var met: Civilization
    private lateinit var unmet: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(6)
        civ = game.addCiv()
        met = game.addCiv()
        unmet = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero))
        game.addCity(met, game.getTile(HexCoord(4, 0)))
        game.addCity(unmet, game.getTile(HexCoord(0, 4)))
        civ.diplomacyFunctions.makeCivilizationsMeet(met)
        game.ruleset.modOptions.constants.historicEventsLikeVp = true
        game.gameInfo.turns = 20
    }

    /** Ten turns of event tourism, 20 tourism a turn from the capital, four turns of 30 culture and 20 tourism kept, 210 culture from instant yields */
    private fun giveHistory() {
        capital.cityConstructions.addBuilding(game.createBuilding("[10] turns of yields as tourism for historic events", "[+20] Tourism [in this city]"))
        repeat(4) {
            civ.stats.statsForNextTurn = Stats(culture = 30f)
            civ.culture.recordYieldHistory()
        }
        civ.culture.addInstantYieldTotal(false, 210)
    }

    @Test
    fun `the tourism of an event is a fifth of the culture plus the tourism of the turns divided by the kind`() {
        giveHistory()
        // culture: 10 * 120 / 10 + 10 * 210 / 21 = 220; tourism: 10 * 80 / 10 = 80; 220 / 5 + 80 = 124
        assertEquals(220, civ.culture.getYieldPerTurnHistory(false, 10))
        assertEquals(80, civ.culture.getYieldPerTurnHistory(true, 10))
        assertEquals(124 / 15, civ.culture.getHistoricEventTourism(HistoricEvent.GreatPerson))
        assertEquals(124 / 10, civ.culture.getHistoricEventTourism(HistoricEvent.EraChange))
        assertEquals(124 / 5, civ.culture.getHistoricEventTourism(HistoricEvent.WorldWonder))
    }

    @Test
    fun `the history is cut to the turns played and only the last ten turns are kept`() {
        giveHistory()
        repeat(10) {
            civ.stats.statsForNextTurn = Stats(culture = 50f)
            civ.culture.recordYieldHistory()
        }
        game.gameInfo.turns = 5
        civ.culture.addInstantYieldTotal(true, 12)
        // culture: 5 * 500 / 10 + 5 * 210 / 6 = 425; tourism: 5 * 200 / 10 + 5 * 12 / 6 = 110
        assertEquals(425, civ.culture.getYieldPerTurnHistory(false, 10))
        assertEquals(110, civ.culture.getYieldPerTurnHistory(true, 10))
    }

    @Test
    fun `a great person born gives its tourism to the civilizations met and is counted`() {
        giveHistory()
        game.ruleset.modOptions.constants.accomplishmentsLikeVp = true
        val expected = civ.culture.getInstantInfluenceOn(met, 124 / 15)
        civ.units.addUnit("Great Scientist", capital)
        assertEquals(expected, civ.culture.influence[met.civID])
        assertEquals(0, civ.culture.influence[unmet.civID])
        assertEquals(1, civ.culture.historicEvents)
        assertEquals(1, civ.accomplishments[Accomplishment.HistoricEvent.name])
    }

    @Test
    fun `an event without turns of event tourism is still counted`() {
        capital.cityConstructions.addBuilding(game.createBuilding("[+1 Culture] [in this city] <for every [Historic events]>"))
        capital.cityStats.update()
        val before = capital.cityStats.currentCityStats[Stat.Culture]
        civ.units.addUnit("Great Scientist", capital)
        civ.units.addUnit("Warrior", capital)
        assertEquals(0, civ.culture.influence[met.civID])
        assertEquals(1, civ.culture.historicEvents)
        capital.cityStats.update()
        assertEquals(before + 1f, capital.cityStats.currentCityStats[Stat.Culture], 0.001f)
    }

    @Test
    fun `a world wonder built is an event and a national wonder is not`() {
        giveHistory()
        val wonder = game.createWonder()
        val national = game.createBuilding().apply { isNationalWonder = true }
        capital.cityConstructions.completeConstruction(national)
        assertEquals(0, civ.culture.historicEvents)
        capital.cityConstructions.completeConstruction(wonder)
        assertEquals(1, civ.culture.historicEvents)
        assertEquals(civ.culture.getInstantInfluenceOn(met, 124 / 5), civ.culture.influence[met.civID])
    }

    @Test
    fun `a new era is an event unless the technology comes with the start`() {
        val medieval = game.ruleset.technologies.values.first { it.era() == "Medieval era" }
        civ.tech.addTechnology(medieval.name, false)
        assertEquals(0, civ.culture.historicEvents)
        val renaissance = game.ruleset.technologies.values.first { it.era() == "Renaissance era" }
        civ.tech.addTechnology(renaissance.name)
        assertEquals(1, civ.culture.historicEvents)
    }

    @Test
    fun `without the switch nothing is kept or counted`() {
        game.ruleset.modOptions.constants.historicEventsLikeVp = false
        giveHistory()
        civ.units.addUnit("Great Scientist", capital)
        assertEquals(0, civ.culture.getYieldPerTurnHistory(false, 10))
        assertEquals(0, civ.culture.historicEvents)
        assertEquals(0, civ.culture.influence[met.civID])
    }

    @Test
    fun `instant culture and tourism and the end of the turn feed the history`() {
        capital.cityConstructions.addBuilding(game.createBuilding("[+20] Tourism [in this city]"))
        InstantYields.gain(civ, capital, Stat.Culture, 30, Unique("Gain [30] [Culture]"))
        UniqueTriggerActivation.triggerUnique(Unique("[12] Tourism towards all civilizations we have met"), civ)
        TurnManager(civ).endTurn()
        assertEquals(30, civ.culture.instantCultureTotal)
        assertEquals(12, civ.culture.instantTourismTotal)
        assertEquals(listOf(20), civ.culture.tourismHistory)
        assertEquals(1, civ.culture.cultureHistory.size)
    }

    @Test
    fun `the clone keeps the events and the history`() {
        giveHistory()
        civ.culture.addInstantYieldTotal(true, 12)
        civ.units.addUnit("Great Scientist", capital)
        val clone = civ.culture.clone()
        assertEquals(1, clone.historicEvents)
        assertEquals(civ.culture.cultureHistory, clone.cultureHistory)
        assertEquals(civ.culture.tourismHistory, clone.tourismHistory)
        assertEquals(210, clone.instantCultureTotal)
        assertEquals(12, clone.instantTourismTotal)
    }
}
