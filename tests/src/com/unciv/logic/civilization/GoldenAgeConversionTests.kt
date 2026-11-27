package com.unciv.logic.civilization

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's Trait_GAPToYield (CvPlayer::changeGoldenAgeTurns) with the ModConstants `goldenAgeConversionLikeVp` */
@RunWith(BaseTestRunner::class)
class GoldenAgeConversionTests {
    private val game = TestGame().apply { makeHexagonalMap(4) }
    private val gold = "Gain [30]% of the triggering amount as [Gold] <upon entering a Golden Age>"

    private fun likeVp() { game.ruleset.modOptions.constants.goldenAgeConversionLikeVp = true }

    @Test
    fun `each golden age started or extended converts the points the next one needs, taken before the count goes up`() {
        likeVp()
        val civ = game.addCiv(gold)
        game.addCity(civ, game.getTile(HexCoord.Zero))
        val goldenAges = civ.goldenAges
        val first = goldenAges.happinessRequiredForNextGoldenAge()
        goldenAges.storedHappiness = first
        val before = civ.gold

        goldenAges.endTurn(0)

        assertEquals(1, goldenAges.numberOfGoldenAges)
        assertEquals(first * 30 / 100, civ.gold - before)

        // Extending the golden age converts again, at the threshold the count of golden ages now gives
        val second = goldenAges.happinessRequiredForNextGoldenAge()
        val afterFirst = civ.gold
        goldenAges.enterGoldenAge()
        assertEquals(second * 30 / 100, civ.gold - afterFirst)
    }

    @Test
    fun `nothing is converted without a capital or without the switch`() {
        likeVp()
        val noCapital = game.addCiv(gold)
        noCapital.goldenAges.enterGoldenAge()
        assertEquals(0, noCapital.gold)

        game.ruleset.modOptions.constants.goldenAgeConversionLikeVp = false
        val civ = game.addCiv(gold)
        game.addCity(civ, game.getTile(HexCoord.Zero))
        civ.goldenAges.enterGoldenAge()
        assertEquals(0, civ.gold)
    }

    @Test
    fun `the points can be converted into tourism towards the civilizations we have met`() {
        likeVp()
        val civ = game.addCiv("Gain [30]% of the triggering amount as Tourism towards all civilizations we have met <upon entering a Golden Age>")
        val met = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        game.addCity(met, game.getTile(HexCoord(3, 0)))
        civ.diplomacyFunctions.makeCivilizationsMeet(met)
        val threshold = civ.goldenAges.happinessRequiredForNextGoldenAge()

        civ.goldenAges.enterGoldenAge()

        assertEquals(threshold * 30 / 100, civ.culture.getInfluence(met))
    }
}
