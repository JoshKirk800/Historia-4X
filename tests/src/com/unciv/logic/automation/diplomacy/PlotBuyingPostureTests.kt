package com.unciv.logic.automation.diplomacy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvDiplomacyAI::DoUpdatePlotBuyingAggressivePostures (thresholds 3, 5, 9, 12; BNW's counted tiles against 2, 4, 7, 10) and the
 *  weights of the tiles it sums, from CvCity::BuyPlot (1 for a resource, 3 for a natural wonder, 2 or 1 by the neighbours' owners, only the
 *  first city near the tile), Release-5.4.8.
 */
@RunWith(GdxTestRunner::class)
class PlotBuyingPostureTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var human: Civilization
    private lateinit var cityA: City
    private lateinit var cityB: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10, Constants.grassland)
        rules = game.ruleset.ai
        a = game.addCiv()
        b = game.addCiv()
        human = game.addCiv(isPlayer = true)
        cityA = game.addCity(a, tile(0, 0))
        cityB = game.addCity(b, tile(5, 0))
        game.addCity(human, tile(-5, 0))
        for (pair in listOf(a to b, a to human, b to human)) pair.first.diplomacyFunctions.makeCivilizationsMeet(pair.second)
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))
    private fun memory(civ: Civilization, of: Civilization) = civ.ai.diplomacy.memoryOf(of)

    /** The weight [DiplomacyEvents.onPlotBought] notes with [a] for [buyer] buying [tile] */
    private fun noted(buyer: Civilization, tile: Tile): Int {
        memory(a, buyer).numPlotsBoughtNearUs = 0
        DiplomacyEvents.onPlotBought(buyer, tile)
        return memory(a, buyer).numPlotsBoughtNearUs
    }

    private fun resourceOn(tile: Tile) = tile.setTileResource(game.createResource().name)

    //region The postures

    @Test
    fun `the postures start at 3, 5, 9 and 12, not BNW's 2, 4, 7 and 10`() {
        val defines = listOf(AiDefine.PLOT_BUYING_POSTURE_LOW_THRESHOLD, AiDefine.PLOT_BUYING_POSTURE_MEDIUM_THRESHOLD,
            AiDefine.PLOT_BUYING_POSTURE_HIGH_THRESHOLD, AiDefine.PLOT_BUYING_POSTURE_INCREDIBLE_THRESHOLD)
        assertEquals(listOf(3, 5, 9, 12), defines.map { rules.define(it) })
        val expected = listOf(0 to AggressivePosture.None, 2 to AggressivePosture.None, 3 to AggressivePosture.Low, 4 to AggressivePosture.Low,
            5 to AggressivePosture.Medium, 8 to AggressivePosture.Medium, 9 to AggressivePosture.High, 11 to AggressivePosture.High,
            12 to AggressivePosture.Incredible, 40 to AggressivePosture.Incredible)
        for ((score, posture) in expected) {
            memory(a, b).numPlotsBoughtNearUs = score
            DisputeAi.updatePlotBuyingPostures(a, listOf(b), rules)
            assertEquals("score $score", posture, memory(a, b).plotBuyingPosture)
        }
    }

    @Test
    fun `a human's shadow AI keeps its postures, and whoever is not a valid major has none`() {
        memory(human, b).plotBuyingPosture = AggressivePosture.High
        memory(human, b).numPlotsBoughtNearUs = 0
        DisputeAi.updatePlotBuyingPostures(human, listOf(a, b), rules)
        assertEquals("the human's memory is not judged", AggressivePosture.High, memory(human, b).plotBuyingPosture)

        val minor = game.addCiv(cityStateType = "Cultured")
        game.addCity(minor, tile(3, 3))
        a.diplomacyFunctions.makeCivilizationsMeet(minor)
        memory(a, minor).plotBuyingPosture = AggressivePosture.High
        memory(a, b).plotBuyingPosture = AggressivePosture.High
        a.ai.diplomacy.memories["gone"] = MajorCivMemory().apply { plotBuyingPosture = AggressivePosture.Incredible }
        DisputeAi.updatePlotBuyingPostures(a, listOf(b, minor), rules)
        assertEquals(AggressivePosture.None, memory(a, minor).plotBuyingPosture)
        assertEquals(AggressivePosture.None, a.ai.diplomacy.memories["gone"]!!.plotBuyingPosture)
        assertEquals("a valid major with nothing bought", AggressivePosture.None, memory(a, b).plotBuyingPosture)
    }
    //endregion

    //region The weight of a bought tile

    @Test
    fun `a tile next to nothing of theirs weighs nothing, one next to their tiles 2, or 1 if the buyer has as many`() {
        assertEquals("(3,0) touches none of ours", 0, noted(b, tile(3, 0)))
        // (1,0) is ours (the city's first ring) and next to (2,0): more of theirs than of the buyer's
        assertEquals(2, noted(b, tile(2, 0)))
        cityB.expansion.takeOwnership(tile(3, 0))
        assertEquals("one each", 1, noted(b, tile(2, 0)))
    }

    @Test
    fun `a resource they can see weighs 1 and a natural wonder 3, before the neighbours count`() {
        val withResource = tile(2, 0)
        resourceOn(withResource)
        assertEquals(1, noted(b, withResource))

        val wonder = tile(0, 2)
        wonder.naturalWonder = "El Dorado"
        wonder.setTerrainTransients()
        assertEquals(3, noted(b, wonder))
    }

    @Test
    fun `only the first city near the tile that it matters to is noted`() {
        // Ours, two rings from (2,0), takes it; the city of b three rings away, which has a tile next to it too, gets nothing (BNW: 1 each)
        cityB.expansion.takeOwnership(tile(3, 0))
        assertEquals(2, noted(human, tile(2, 0)))
        assertEquals(0, memory(b, human).numPlotsBoughtNearUs)
    }

    @Test
    fun `a city-state's city counts for nothing and the search goes on`() {
        // the city-state's city is one ring from the tile, ours two
        game.addCity(game.addCiv(cityStateType = "Cultured"), tile(3, 0))
        assertEquals(2, noted(human, tile(2, 0)))
    }

    @Test
    fun `buying a tile with gold notes it with the cities near it`() {
        val tile = tile(3, 0) // two from the buyer's city, three from ours
        resourceOn(tile)
        b.addGold(1000)
        cityB.expansion.buyTile(tile)
        assertEquals(1, b.plotsBought)
        assertEquals(1, memory(a, b).numPlotsBoughtNearUs)
    }

    @Test
    fun `a human's promise not to buy land breaks at the purchase`() {
        human.getDiplomacyManager(a)!!.agreeToDemand(Demand.DoNotBuyLandNearUs)
        val toHuman = a.getDiplomacyManager(human)!!
        assertTrue(toHuman.hasFlag(DiplomacyFlags.AgreedToNotBuyLandNearUs))
        assertFalse(toHuman.hasFlag(DiplomacyFlags.BoughtLandNearUs))

        // b's city is nearer to (3,0) than ours and has the neighbouring tiles: it takes the tile, ours is not reached
        DiplomacyEvents.onPlotBought(human, tile(3, 0))
        assertFalse(toHuman.hasFlag(DiplomacyFlags.BoughtLandNearUs))

        resourceOn(tile(2, 0))
        DiplomacyEvents.onPlotBought(human, tile(2, 0))
        assertTrue(toHuman.hasFlag(DiplomacyFlags.BoughtLandNearUs))
        assertNull(memory(a, human).borderPromisePosture)
    }

    @Test
    fun `refusing to stop buying land resets what they bought`() {
        memory(a, human).numPlotsBoughtNearUs = 7
        human.getDiplomacyManager(a)!!.refuseDemand(Demand.DoNotBuyLandNearUs)
        assertEquals(0, memory(a, human).numPlotsBoughtNearUs)
        assertTrue(memory(a, human).ignoredBorderPromise)
    }
    //endregion
}
