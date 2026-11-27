package com.unciv.logic.map

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.ArchaeologyRecord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ArtifactClasses
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Sites with a sarcophagus near new cities (CvPlayer::SpawnResourceInVicinity with bSarcophagus) under `archaeologyChoicesLikeVp` */
@RunWith(GdxTestRunner::class)
class SarcophagusTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private val constants get() = game.ruleset.modOptions.constants
    private val site get() = constants.archaeologySiteResource

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        civ = game.addCiv("[1] antiquity sites with a sarcophagus appear near each of the first [2] cities founded", isPlayer = true)
        constants.archaeologyChoicesLikeVp = true
    }

    private fun sites() = game.tileMap.values.filter { it.resource == site }

    private fun history(tile: Tile) {
        tile.archaeologyRecord = ArchaeologyRecord(ArtifactClasses.MeleeBattle, "Ancient era", civ.civName, null)
    }

    private fun block(tile: Tile) {
        tile.tileResource = game.ruleset.tileResources["Wheat"]
    }

    @Test
    fun `a new city gets a site of its own sarcophagus on its land, on a tile without history first`() {
        val center = game.tileMap[0, 0]
        val ring = center.neighbors.toList()
        for (tile in ring.drop(1)) history(tile)
        game.addCity(civ, center)
        val placed = sites().single()
        assertEquals(ring.first(), placed)
        val record = placed.archaeologyRecord!!
        assertEquals(ArtifactClasses.Sarcophagus, record.kind)
        assertEquals(civ.civName, record.civ1)
        assertNull(record.civ2)
    }

    @Test
    fun `with all of its land taken, the site goes to our other land and then to land in range that no one owns`() {
        val first = game.addCity(civ, game.tileMap[0, -6])
        sites().forEach { it.tileResource = null; it.archaeologyRecord = null }
        val center = game.tileMap[0, 3]
        for (tile in center.neighbors) block(tile)
        game.addCity(civ, center)
        val placed = sites().single()
        assertEquals("our land outside the range of the new city", first, placed.getCity())

        // a third city founded is past the first two
        game.addCity(civ, game.tileMap[6, 0])
        assertEquals(1, sites().size)
    }

    @Test
    fun `land that no one owns in range when we own none`() {
        val center = game.tileMap[0, 0]
        for (tile in center.neighbors) block(tile)
        game.addCity(civ, center)
        val placed = sites().single()
        assertNull(placed.getOwner())
        assertTrue(placed.aerialDistanceTo(center) <= constants.cityWorkRange)
    }

    @Test
    fun `nothing without the switch`() {
        constants.archaeologyChoicesLikeVp = false
        game.addCity(civ, game.tileMap[0, 0])
        assertTrue(sites().isEmpty())
    }

    @Test
    fun `a sarcophagus is not replaced by what happens later`() {
        val city = game.addCity(civ, game.tileMap[0, 0])
        val placed = sites().single()
        val other = game.addCiv()
        placed.addArchaeologyRecord(ArtifactClasses.RazedCity, other, civ)
        assertEquals(ArtifactClasses.Sarcophagus, placed.archaeologyRecord!!.kind)
        assertEquals(city.civ.civName, placed.archaeologyRecord!!.civ1)
    }

    @Test
    fun `the sites that appear with archaeology leave sarcophagi out of their number`() {
        game.addCiv()
        game.addCity(civ, game.tileMap[0, 0])
        val sarcophagus = sites().single()
        val spawned = ArchaeologyFunctions.spawnSites(game.gameInfo, Random(7))
        assertFalse(sarcophagus in spawned)
        // the number spawnSites draws first, for two major civilizations
        val draws = Random(7)
        val min = constants.archaeologySitesPerCivMin * 2
        val half = ((constants.archaeologySitesPerCivMax * 2 - min) / 2).coerceAtLeast(1)
        assertEquals(min + draws.nextInt(half) + draws.nextInt(half), spawned.size)
        assertEquals(ArtifactClasses.Sarcophagus, sarcophagus.archaeologyRecord!!.kind)
    }

    @Test
    fun `a desert next to a sarcophagus comes after grassland far from it`() {
        game.addCiv()
        val sarcophagus = game.tileMap[0, 0]
        sarcophagus.tileResource = game.ruleset.tileResources[site]
        sarcophagus.resourceAmount = 1
        sarcophagus.archaeologyRecord = ArchaeologyRecord(ArtifactClasses.Sarcophagus, "Ancient era", civ.civName, null)
        val near = game.tileMap[0, 1]
        near.setBaseTerrain(game.ruleset.terrains["Desert"]!!)
        val far = game.tileMap[0, -8]
        for (tile in game.tileMap.values) if (tile != near && tile != far && tile != sarcophagus) block(tile)

        // A desert is worth half as much again; a site next to it counts within 1, 2 and 3 tiles and leaves a fourth (CvGame::CalculateDigSiteWeight)
        assertEquals(listOf(far, near), ArchaeologyFunctions.spawnSites(game.gameInfo, Random(7)))
    }
}
