package com.unciv.models.metadata

import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSetupInfoTests {
    @Test
    fun `a first setup is a wrapping rectangular Small map of Civ V of the Continents land step`() {
        val map = GameSetupInfo.firstSetup().mapParameters
        assertEquals(MapType.continentsLikeCivV, map.type)
        assertEquals(MapShape.rectangular, map.shape)
        assertEquals(MapSize.Small.name, map.mapSize.name)
        assertEquals(66, map.mapSize.width)
        assertEquals(42, map.mapSize.height)
        assertTrue(map.worldWrap)
    }

    @Test
    fun `the first setup takes the difficulty it is given and leaves the defaults of saved games alone`() {
        assertEquals("Chieftain", GameSetupInfo.firstSetup("Chieftain").gameParameters.difficulty)
        val saved = MapParameters()
        assertEquals(MapType.pangaea, saved.type)
        assertEquals("Medium", saved.mapSize.name)
        assertEquals(listOf(20, 44, 29), listOf(saved.mapSize.radius, saved.mapSize.width, saved.mapSize.height))
        assertEquals(MapShape.hexagonal, saved.shape)
    }
}
