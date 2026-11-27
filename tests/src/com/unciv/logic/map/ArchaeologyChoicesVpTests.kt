package com.unciv.logic.map

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyChoice
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.ArchaeologyRecord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ArtifactClasses
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvPlayerCulture::DoArchaeologyChoice with `archaeologyChoicesLikeVp` and the dig event of `historicEventDispatchLikeVp` */
@RunWith(GdxTestRunner::class)
class ArchaeologyChoicesVpTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var other: Civilization
    private lateinit var city: City
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        other = game.addCiv()
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        game.addCity(other, game.tileMap[0, 7], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        game.gameInfo.currentPlayerCiv = other
        constants.archaeologyChoicesLikeVp = true
    }

    private fun archaeologist(tile: Tile): MapUnit {
        game.gameInfo.archaeologyTriggered = true
        civ.tech.addTechnology("Archaeology")
        return game.addUnit("Archaeologist", civ, tile)
    }

    private fun digOn(tile: Tile, kind: String = ArtifactClasses.MeleeBattle, work: String? = null) {
        tile.archaeologyRecord = ArchaeologyRecord(kind, "Ancient era", civ.civName, other.civName, work)
        tile.setImprovement(constants.archaeologicalDigImprovement)
    }

    private fun cityStateTile(): Pair<Civilization, Tile> {
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val stateCity = game.addCity(cityState, game.tileMap[-6, 2])
        civ.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val tile = game.tileMap[-5, 2]
        game.addTileToCity(stateCity, tile)
        tile.setOwningCity(stateCity)
        return cityState to tile
    }

    @Test
    fun `a Landmark in a City-State's land raises the resting influence by ten per era and the influence to it`() {
        val (cityState, tile) = cityStateTile()
        constants.landmarkInfluenceWithCityStates = 0
        val unit = archaeologist(tile) // Archaeology brings the civilization to its era
        val era = civ.getEraNumber()
        assertTrue(era > 1)
        val diplomacy = cityState.getDiplomacyManager(civ)!!
        val restingBefore = diplomacy.getCityStateInfluenceRestingPoint()
        diplomacy.setInfluence(restingBefore)
        digOn(tile)

        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, unit))
        assertEquals(cityState.civID, tile.landmarkCreditCityState)
        assertEquals(restingBefore + 10f * era, diplomacy.getCityStateInfluenceRestingPoint(), 0.001f)
        assertEquals("snaps up to the new resting point", restingBefore + 10f * era, diplomacy.getInfluence(), 0.001f)
        assertEquals(10 * era, ArchaeologyFunctions.getLandmarkRestingInfluence(cityState, civ))
        assertEquals("only for its builder", 0, ArchaeologyFunctions.getLandmarkRestingInfluence(cityState, other))
    }

    @Test
    fun `influence above the resting point only gains the extra, which goes by the era`() {
        val (cityState, tile) = cityStateTile()
        val unit = archaeologist(tile)
        val era = civ.getEraNumber()
        val diplomacy = cityState.getDiplomacyManager(civ)!!
        diplomacy.setInfluence(diplomacy.getCityStateInfluenceRestingPoint() + 10f * era + 5f)
        val before = diplomacy.getInfluence()
        digOn(tile)
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, unit))
        assertEquals(before + 50f * era, diplomacy.getInfluence(), 0.001f)
    }

    @Test
    fun `the credit goes when the improvement changes or the builder takes the tile`() {
        val (cityState, tile) = cityStateTile()
        val unit = archaeologist(tile)
        digOn(tile)
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, unit))
        tile.setOwningCity(city)
        assertNull(tile.landmarkCreditCityState)

        tile.setOwningCity(cityState.cities.first())
        assertEquals("a Landmark given back has no credit", 0, ArchaeologyFunctions.getLandmarkRestingInfluence(cityState, civ))
        tile.landmarkCreditCityState = cityState.civID
        tile.setImprovement("Farm")
        assertNull(tile.landmarkCreditCityState)
        assertEquals(0, ArchaeologyFunctions.getLandmarkRestingInfluence(cityState, civ))
    }

    @Test
    fun `without the switch a Landmark gives the flat influence and no credit`() {
        constants.archaeologyChoicesLikeVp = false
        val (cityState, tile) = cityStateTile()
        val unit = archaeologist(tile)
        val diplomacy = cityState.getDiplomacyManager(civ)!!
        val before = diplomacy.getInfluence()
        digOn(tile)
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, unit))
        assertEquals(before + 50f, diplomacy.getInfluence(), 0.001f)
        assertNull(tile.landmarkCreditCityState)
        tile.landmarkCreditCityState = cityState.civID
        assertEquals(0, ArchaeologyFunctions.getLandmarkRestingInfluence(cityState, civ))
    }

    @Test
    fun `culture from a writing in our lands also goes to the borders of the city`() {
        val tile = game.tileMap[0, -1]
        assertEquals(civ, tile.getOwner())
        val unit = archaeologist(tile)
        digOn(tile, ArtifactClasses.Writing, "Rosetta Stone")
        for (turn in 0 until 8) { game.gameInfo.turns = turn; civ.policies.endTurn(500L) }
        val culture = civ.policies.getCultureFromGreatWriter()
        assertTrue(culture > 0)
        val stored = city.expansion.cultureStored
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.CultureBoost, unit))
        assertEquals(stored + culture, city.expansion.cultureStored)
    }

    @Test
    fun `culture from a writing outside our lands goes to no city`() {
        val tile = game.tileMap[0, 6]
        assertEquals(other, tile.getOwner())
        archaeologist(game.tileMap[0, -1])
        val unit: MapUnit? = null
        digOn(tile, ArtifactClasses.Writing, "Rosetta Stone")
        for (turn in 0 until 8) { game.gameInfo.turns = turn; civ.policies.endTurn(500L) }
        val otherCity = other.cities.first()
        val stored = otherCity.expansion.cultureStored to city.expansion.cultureStored
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.CultureBoost, unit))
        assertEquals(stored, otherCity.expansion.cultureStored to city.expansion.cultureStored)
    }

    @Test
    fun `the record goes after an artifact but stays under a Landmark`() {
        city.cityConstructions.addBuilding(game.createBuilding("Has [4] [Art/Artifact] Great Work slots"))
        val tile = game.tileMap[0, -2]
        digOn(tile)
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.ArtifactOfFirst, archaeologist(tile)))
        assertNull(tile.archaeologyRecord)

        val landmark = game.tileMap[0, -3]
        digOn(landmark)
        assertTrue(ArchaeologyFunctions.execute(civ, landmark, ArchaeologyChoice.Landmark, game.addUnit("Archaeologist", civ, landmark)))
        assertNotNull(landmark.archaeologyRecord)

        constants.archaeologyChoicesLikeVp = false
        val kept = game.tileMap[1, -2]
        digOn(kept)
        assertTrue(ArchaeologyFunctions.execute(civ, kept, ArchaeologyChoice.ArtifactOfFirst, game.addUnit("Archaeologist", civ, kept)))
        assertNotNull("without the switch", kept.archaeologyRecord)
    }

    @Test
    fun `a dig is a historic event with the turns of the unique, worth a tenth`() {
        constants.historicEventsLikeVp = true
        constants.historicEventDispatchLikeVp = true
        val tile = game.tileMap[0, -2]
        val unit = archaeologist(tile)
        digOn(tile)
        val events = civ.culture.historicEvents
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, unit))
        assertEquals("no turns, no event", events, civ.culture.historicEvents)

        city.cityConstructions.addBuilding(game.createBuilding("[3] turns of yields as tourism for archaeological digs"))
        val second = game.tileMap[0, -3]
        digOn(second)
        assertTrue(ArchaeologyFunctions.execute(civ, second, ArchaeologyChoice.Landmark, game.addUnit("Archaeologist", civ, second)))
        assertEquals(events + 1, civ.culture.historicEvents)
        assertEquals(3, civ.culture.getHistoricEventTurns(com.unciv.logic.civilization.HistoricEvent.Dig))

        constants.historicEventDispatchLikeVp = false
        val third = game.tileMap[1, -3]
        digOn(third)
        assertTrue(ArchaeologyFunctions.execute(civ, third, ArchaeologyChoice.Landmark, game.addUnit("Archaeologist", civ, third)))
        assertEquals("not without the switch", events + 1, civ.culture.historicEvents)
    }
}
