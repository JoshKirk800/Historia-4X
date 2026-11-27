package com.unciv.logic.civilization

import com.unciv.logic.automation.diplomacy.WarDamageAi
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.managers.PublicOpinion
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi 5.4.8 adds the war weariness to the empire's unhappiness (CvPlayer::DoUpdateTotalUnhappiness, V:CvPlayer.cpp:22189-22192): CvPlayer::GetUnhappinessFromWarWeariness
 *  (V:CvPlayer.cpp:37111-37114) is the highest war weariness percent x the citizens of all the cities x 34 (0 or more) / 10000, one product cut once. Here it is
 *  `needsWarWearinessLikeVp` with `cityNeedsLikeVp` and `warWearinessLikeVp`.
 *
 *  The empire's cached war value is set to 650, so a weariness w against the enemy is 100 w / 650 percent (58 -> 8, 260 -> 40, 650 and 2000 -> 100). With 37 citizens in five cities
 *  (8 + 8 + 7 + 7 + 7), 260 gives 40 x 37 x 34 / 10000 = 5 (5.03; 4 if cut after the citizens' share or after the cap's, 6 if rounded up), 58 gives 8 x 37 x 34 / 10000 = 1 (1.0064; 0, 0 and 2).
 */
@RunWith(BaseTestRunner::class)
class WarWearinessNeedsTests {
    private lateinit var world: NeedsTestWorld
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private val constants get() = world.game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        world = NeedsTestWorld()
        civ = world.civ
        constants.warWearinessLikeVp = true
        constants.needsWarWearinessLikeVp = true
        for (size in listOf(8, 8, 7, 7, 7)) world.addCity(size)
        enemy = world.game.addCiv()
        world.game.addCity(enemy, world.game.getTile(HexCoord(4, -2)))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        civ.ai.diplomacy.cachedWarValue = 650
        world.refresh()
    }

    private fun weary(weariness: Int) {
        civ.ai.diplomacy.memoryOf(enemy).warWeariness = weariness
    }

    private val needs get() = civ.stats.needs

    @Test
    fun `the unhappiness is the percent of the citizens times 34 over 100, in one product cut once`() {
        weary(260)
        assertEquals("100 x 260 / 650 = 40", 40, WarDamageAi.highestWarWearinessPercent(civ))
        assertEquals("40 x 37 x 34 / 10000 = 5.03: not 4 and not 6", 5, needs.warWeariness())
        weary(58)
        assertEquals("8 x 37 x 34 / 10000 = 1.0064: not 0 and not 2", 1, needs.warWeariness())
        weary(650)
        assertEquals("100 x 37 x 34 / 10000 = 12.58", 12, needs.warWeariness())
        weary(2000)
        assertEquals("the percent stops at 100", 12, needs.warWeariness())
    }

    @Test
    fun `the cap is the constant, 0 or more`() {
        weary(650)
        constants.warWeariness = constants.warWeariness.copy(populationPercentCap = 50)
        assertEquals("100 x 37 x 50 / 10000 = 18.5", 18, needs.warWeariness())
        constants.warWeariness = constants.warWeariness.copy(populationPercentCap = -20)
        assertEquals("a negative cap is 0", 0, needs.warWeariness())
    }

    @Test
    fun `every city counts, a puppet too, and it is the citizens now`() {
        weary(650)
        assertEquals(12, needs.warWeariness())
        civ.cities.first().isPuppet = true
        assertEquals("a puppet counts: 100 x 37 x 34 / 10000", 12, needs.warWeariness())
        civ.cities.first().population.setPopulation(24)
        assertEquals("the citizens of a city that grew: 100 x 53 x 34 / 10000 = 18.02", 18, needs.warWeariness())
    }

    @Test
    fun `the pool of unhappiness has it and public opinion`() {
        weary(260)
        PublicOpinion::class.java.getDeclaredField("unhappiness").apply { isAccessible = true }.setInt(civ.culture.publicOpinion, 4)
        world.refresh()
        assertEquals("4 and 5", 9, needs.unhappinessPool)
        weary(0)
        world.refresh()
        assertEquals(4, needs.unhappinessPool)
    }

    @Test
    fun `nothing is added without the switch of the needs or of the weariness`() {
        weary(260)
        world.refresh()
        assertEquals(5, needs.unhappinessPool)
        constants.needsWarWearinessLikeVp = false
        world.refresh()
        assertEquals(0, needs.warWeariness())
        assertEquals(0, needs.unhappinessPool)
        constants.needsWarWearinessLikeVp = true
        constants.warWearinessLikeVp = false
        world.refresh()
        assertEquals("no weariness without its own switch", 0, needs.unhappinessPool)
    }

    @Test
    fun `a war that cannot end counts for nothing`() {
        weary(260)
        civ.ai.diplomacy.memoryOf(enemy).turnsLockedIntoWar = 3
        assertEquals(0, needs.warWeariness())
        civ.ai.diplomacy.memoryOf(enemy).turnsLockedIntoWar = 0
        assertEquals(5, needs.warWeariness())
    }
}
