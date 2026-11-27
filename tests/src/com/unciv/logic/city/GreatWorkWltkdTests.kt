package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class GreatWorkWltkdTests {
    private lateinit var game: TestGame

    private val celebrate = "Each new Great Work gives all cities [6] turns of We Love The King Day"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(6)
        game.ruleset.modOptions.constants.greatWorkWltkdLikeVp = true
    }

    private fun createWork(civ: Civilization, city: City) {
        city.cityConstructions.addBuilding(game.createBuilding("Has [1] [Art/Artifact] Great Work slots"))
        GreatWorkFunctions.createWork(civ, game.createGreatWork(GreatWorkType.Art), city.greatWorks.getFreeSlot(GreatWorkSlotType.ArtArtifact)!!)
    }

    private fun wltkd(city: City) = if (city.hasFlag(CityFlags.WeLoveTheKing)) city.getFlag(CityFlags.WeLoveTheKing) else 0

    @Test
    fun `a new work starts the celebration in every city and extends one that is going`() {
        val civ = game.addCiv(celebrate, isPlayer = true)
        val capital = game.addCity(civ, game.tileMap[0, 0])
        val other = game.addCity(civ, game.tileMap[0, 4])
        other.setFlag(CityFlags.WeLoveTheKing, 3)

        createWork(civ, capital)

        assertEquals(6, wltkd(capital))
        assertEquals(9, wltkd(other))
        assertTrue(civ.notifications.any { it.text == "Our new Great Work makes our cities celebrate We Love The King Day for [6] more turns" })
    }

    @Test
    fun `the turns follow the production cost of the game speed`() {
        game.gameInfo.speed = game.ruleset.speeds["Marathon"]!!
        val civ = game.addCiv(celebrate, isPlayer = true)
        val city = game.addCity(civ, game.tileMap[0, 0])

        createWork(civ, city)

        // CvGameSpeedInfo::getTrainPercent is 300 on Marathon
        assertEquals(18, wltkd(city))
    }

    @Test
    fun `each new work fires what comes upon We Love The King Day starting`() {
        val civ = game.addCiv(celebrate, "Gain [10] [Gold] <upon We Love The King Day starting>", isPlayer = true)
        val city = game.addCity(civ, game.tileMap[0, 0])
        val gold = civ.gold

        createWork(civ, city)
        createWork(civ, city)

        assertEquals(12, wltkd(city))
        assertEquals(gold + 20, civ.gold)
    }

    @Test
    fun `nothing without the switch or the unique`() {
        val unmoved = game.addCiv(isPlayer = true)
        val unmovedCity = game.addCity(unmoved, game.tileMap[0, 0])
        createWork(unmoved, unmovedCity)
        assertFalse(unmovedCity.hasFlag(CityFlags.WeLoveTheKing))

        game.ruleset.modOptions.constants.greatWorkWltkdLikeVp = false
        val civ = game.addCiv(celebrate, isPlayer = true)
        val city = game.addCity(civ, game.tileMap[0, 4])
        createWork(civ, city)
        assertFalse(city.hasFlag(CityFlags.WeLoveTheKing))
    }
}
