package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.models.metadata.BaseRuleset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's EventGP: CvPlayer::ChangeNumHistoricEvents gives the capital points toward a random Great Person */
@RunWith(GdxTestRunner::class)
class HistoricEventGreatPersonTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        civ = game.addCiv("Each historic event gives the capital [15]% of the points needed for a random Great Person")
        capital = game.addCity(civ, game.getTile(HexCoord.Zero))
        game.ruleset.modOptions.constants.historicEventsLikeVp = true
    }

    @Test
    fun `a historic event gives the capital the percent of the points of one Great Person`() {
        val other = game.addCity(civ, game.getTile(HexCoord(3, 0)))
        civ.culture.onHistoricEvent(HistoricEvent.WorldWonder)
        assertEquals(1, capital.greatPersonProgress.size)
        val (greatPerson, points) = capital.greatPersonProgress.entries.first()
        assertEquals(civ.greatPeople.getPointsRequiredForGreatPerson(greatPerson) * 15 / 100, points)
        assertEquals(0, other.greatPersonProgress.size)
    }

    @Test
    fun `the Great People are those of the specialists, twice those with a free slot for their works`() {
        val specialists = game.ruleset.specialists.values.mapNotNull { it.greatPersonPoints.keys.firstOrNull() }
        val before = civ.greatPeople.getHistoricEventGreatPeople()
        assertEquals(specialists, before.distinct())
        assertEquals(1, before.count { it == "Great Writer" })
        assertEquals("the palace has a free art slot", 2, before.count { it == "Great Artist" })

        capital.cityConstructions.addBuilding(game.createBuilding("Has [1] [Literature] Great Work slots"))
        val after = civ.greatPeople.getHistoricEventGreatPeople()
        assertEquals(2, after.count { it == "Great Writer" })
        assertEquals(before.size + 1, after.size)
        assertEquals(1, after.count { it == "Great Scientist" })
    }

    @Test
    fun `the slots of a Great Person's works come from the works of its names`() {
        assertEquals(GreatWorkSlotType.Literature, GreatWorkFunctions.getSlotTypeOfGreatPerson(game.ruleset, "Great Writer"))
        assertEquals(GreatWorkSlotType.ArtArtifact, GreatWorkFunctions.getSlotTypeOfGreatPerson(game.ruleset, "Great Artist"))
        assertEquals(GreatWorkSlotType.Music, GreatWorkFunctions.getSlotTypeOfGreatPerson(game.ruleset, "Great Musician"))
        assertNull(GreatWorkFunctions.getSlotTypeOfGreatPerson(game.ruleset, "Great Scientist"))
    }

    @Test
    fun `nothing without the unique or when the event does not count`() {
        civ.culture.onHistoricEvent(HistoricEvent.GoldenAge)
        assertEquals("a golden age without turns does not count", 0, capital.greatPersonProgress.size)

        val plain = game.addCiv()
        plain.playerType = PlayerType.Human
        val city = game.addCity(plain, game.getTile(HexCoord(0, 3)))
        assertEquals(0, plain.getMatchingUniques(UniqueType.HistoricEventGreatPersonPoints).count())
        val events = plain.culture.historicEvents
        plain.culture.onHistoricEvent(HistoricEvent.WorldWonder)
        assertEquals(events + 1, plain.culture.historicEvents)
        assertEquals(0, city.greatPersonProgress.size)
        assertEquals(0, plain.notifications.count { it.text.contains("historic event gave") })
    }
}
