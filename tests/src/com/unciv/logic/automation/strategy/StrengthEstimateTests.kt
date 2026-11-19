package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.ApproachPart6Ai
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvDiplomacyAI::DoUpdatePlayerStrengthEstimates, ComputeDynamicStrengthModifier and ComputeRatingStrengthAdjustment
 *  (Release-5.4.8, CvDiplomacyAI.cpp 11073-11632), CvPlayer::calculateEconomicMight and the readers that take the raw values.
 *  Strength levels and target values are compared by name where the old code has the type but not the value.
 */
@RunWith(GdxTestRunner::class)
class StrengthEstimateTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        us = civ()
        them = civ()
    }

    private fun civ(vararg uniques: String, human: Boolean = false): Civilization {
        val civ = game.addCiv(*uniques, isPlayer = human)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        return civ
    }

    private fun city(civ: Civilization, x: Int, y: Int) = game.addCity(civ, game.getTile(HexCoord(x, y)))

    private fun warriors(civ: Civilization, count: Int, y: Int) {
        for (i in 0 until count) game.addUnit("Warrior", civ, game.getTile(HexCoord(i - 3, y)))
    }

    /** Both with a city and six warriors, which is no early game anymore, neighbours */
    private fun twoArmies() {
        city(us, 0, 0)
        city(them, 6, 0)
        warriors(us, 6, 3)
        warriors(them, 6, -3)
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.proximity[them.civID] = Proximity.Neighbors
        them.proximity[us.civID] = Proximity.Neighbors
    }

    private fun strength(of: Civilization = them, viewer: Civilization = us) = WarStateAi.militaryStrengthComparedToUs(viewer, of)!!

    //region No cities and nuclear weapons: the estimates end before any calculation (11134-11166)

    @Test
    fun `a civilization without cities is a pathetic opponent and a cakewalk for one with cities`() {
        city(us, 0, 0)
        game.addUnit("Warrior", us, game.getTile(HexCoord(1, 0)))
        game.addUnit("Warrior", them, game.getTile(HexCoord(6, 0)))
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        WarStateAi.doTurn(us)
        assertEquals("Pathetic", WarStateAi.militaryStrengthComparedToUs(us, them)?.name)
        assertEquals("Cakewalk", WarStateAi.targetValue(us, them)?.name)
    }

    @Test
    fun `a civilization without cities sees one with cities as immense and impossible`() {
        city(us, 0, 0)
        game.addUnit("Warrior", us, game.getTile(HexCoord(1, 0)))
        game.addUnit("Warrior", them, game.getTile(HexCoord(6, 0)))
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        WarStateAi.doTurn(them)
        assertEquals("Immense", WarStateAi.militaryStrengthComparedToUs(them, us)?.name)
        assertEquals("Impossible", WarStateAi.targetValue(them, us)?.name)
    }

    @Test
    fun `with a nuclear weapon a civilization without cities is pathetic whatever its army`() {
        city(us, 0, 0)
        game.addUnit("Atomic Bomb", us, game.getTile(HexCoord(0, 0)))
        for (i in 0 until 5) game.addUnit("Modern Armor", them, game.getTile(HexCoord(6, i)))
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        WarStateAi.doTurn(us)
        assertEquals("Pathetic", WarStateAi.militaryStrengthComparedToUs(us, them)?.name)
        assertEquals("Cakewalk", WarStateAi.targetValue(us, them)?.name)
    }

    @Test
    fun `without a nuclear weapon a civilization without cities but a huge army is no pathetic opponent`() {
        city(us, 0, 0)
        for (i in 0 until 5) game.addUnit("Modern Armor", them, game.getTile(HexCoord(6, i)))
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        WarStateAi.doTurn(us)
        assertNotEquals("Pathetic", WarStateAi.militaryStrengthComparedToUs(us, them)?.name)
    }
    //endregion

    //region Dynamic modifiers and the rating (11489-11632)

    @Test
    fun `a nation that fights larger civilizations better counts for a fifth more against one with more cities`() {
        val ethiopia = civ("[+20]% Strength <when fighting units from a Civilization with more Cities than you>")
        city(ethiopia, 6, 0)
        city(us, 0, 0)
        assertEquals(100, StrengthEstimateAi.dynamicStrengthModifier(us, ethiopia, us, 100))
        city(us, 0, 6)
        assertEquals(120, StrengthEstimateAi.dynamicStrengthModifier(us, ethiopia, us, 100))
        assertEquals("a nation without the unique", 100, StrengthEstimateAi.dynamicStrengthModifier(ethiopia, us, ethiopia, 100))
    }

    @Test
    fun `a civilization above the average military rating is seen as stronger`() {
        twoArmies()
        WarStateAi.doTurn(us)
        val before = strength()
        them.ai.diplomacy.militaryRating = 2000
        WarStateAi.doTurn(us)
        assertTrue("$before before, ${strength()} after", strength() > before)
    }

    @Test
    fun `an AI sees a human on a high difficulty as stronger by the difficulty's perception modifier`() {
        val human = civ(human = true)
        city(us, 0, 0)
        city(human, 6, 0)
        us.diplomacyFunctions.makeCivilizationsMeet(human)
        game.gameInfo.difficulty = "Settler"
        assertEquals(1000, StrengthEstimateAi.ratingStrengthAdjustment(us, human, us, 1000))
        game.gameInfo.difficulty = "Deity"
        assertEquals(1350, StrengthEstimateAi.ratingStrengthAdjustment(us, human, us, 1000))
    }
    //endregion

    //region Raw values, boldness and the readers of the raw values

    @Test
    fun `our boldness lowers the biased strength and target value, not the raw ones`() {
        twoArmies()
        us.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 0)
        WarStateAi.doTurn(us)
        val raw = WarStateAi.rawMilitaryStrengthComparedToUs(us, them)
        val rawTarget = WarStateAi.rawTargetValue(us, them)
        assertEquals(raw, strength())
        us.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 40) // 100 - 3 x 40 below zero: nothing they have counts
        WarStateAi.doTurn(us)
        assertEquals("Pathetic", strength().name)
        assertEquals("Cakewalk", WarStateAi.targetValue(us, them)?.name)
        assertEquals(raw, WarStateAi.rawMilitaryStrengthComparedToUs(us, them))
        assertEquals(rawTarget, WarStateAi.rawTargetValue(us, them))
    }

    @Test
    fun `the economic might is reduced for each city and counts food, science and culture`() {
        val city = city(us, 0, 0)
        city.cityStats.update()
        val stats = city.cityStats.currentCityStats
        val eaten = city.cityStats.finalStatList["Population"]?.food ?: 0f
        val culture = us.stats.statsForNextTurn.culture.toInt()
        val sum = 5 + city.population.population + (stats.food - eaten + stats.production + stats.science + stats.gold).toInt() + culture
        assertEquals(sum * 100 / 110, WarStateAi.economicMight(us))
    }

    @Test
    fun `DoNuke judges the war by the raw strength`() {
        city(us, 0, 0)
        city(them, 6, 0)
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        game.addUnit("Atomic Bomb", us, game.getTile(HexCoord(0, 0)))
        us.ai.setGrandStrategyAndPursuit(null)
        us.getDiplomacyManager(them)!!.declareWar()
        us.ai.warStates.militaryStrengths[them.civID] = StrengthLevel.Average
        assertFalse(MilitaryOperations.shouldLaunchNuke(us, them))
        us.ai.warStates.rawMilitaryStrengths[them.civID] = StrengthLevel.Immense
        assertTrue(MilitaryOperations.shouldLaunchNuke(us, them))
    }

    @Test
    fun `a neighbour is a potential threat when its raw strength is above average`() {
        city(us, 0, 0)
        city(them, 6, 0)
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.proximity[them.civID] = Proximity.Neighbors
        them.proximity[us.civID] = Proximity.Neighbors
        us.ai.warStates.militaryStrengths[them.civID] = StrengthLevel.Average
        us.ai.warStates.rawMilitaryStrengths[them.civID] = StrengthLevel.Average
        assertFalse(ApproachPart6Ai.isPotentialMilitaryTargetOrThreat(us, them))
        us.ai.warStates.rawMilitaryStrengths[them.civID] = StrengthLevel.Powerful
        assertTrue(ApproachPart6Ai.isPotentialMilitaryTargetOrThreat(us, them))
    }
    //endregion
}
