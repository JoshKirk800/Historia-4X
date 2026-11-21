package com.unciv.logic.civilization.managers

import com.unciv.logic.city.City
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 *  What the empire does with Vox Populi's happiness (Release-5.4.8), `cityNeedsLikeVp` on: the golden age meter and threshold (CvPlayer::DoProcessGoldenAge,
 *  GetGoldenAgeProgressThreshold), uprisings (DoUpdateUprisings, DoResetUprisingCounter, DoUprising), city revolts (DoUpdateCityRevolts, GetMostUnhappyCity,
 *  GetMostUnhappyCityRecipient), settlers (CanFound) and the goody huts (canReceiveGoody).
 *
 *  The approval rating is made by the capital's building with a number of happiness points against the 13 unhappy citizens of one city that is not
 *  the capital: its 10 specialists and the 3 citizens of its isolation. Approval is happy x 100 / 13 / 2: [CONTENT] 14 makes 53, [UNHAPPY] 11 makes 42,
 *  [VERY_UNHAPPY] 8 makes 30, [SUPER_UNHAPPY] 5 makes 19.
 */
@RunWith(BaseTestRunner::class)
class NeedsEmpireRulesTests {
    private companion object {
        const val CONTENT = 14
        const val UNHAPPY = 11
        const val VERY_UNHAPPY = 8
        const val SUPER_UNHAPPY = 5
    }

    private var world = NeedsTestWorld()
    private val game get() = world.game
    private val civ get() = world.civ
    private val flag = CivFlags.CityRevoltCountdown.name

    private lateinit var capital: City
    private lateinit var unhappyCity: City

    /** The speed's lengthening of the counters (only lengthens: Civ V's train percent over 100) */
    private val speed get() = game.gameInfo.speed.modifier.coerceAtLeast(1f)

    private fun setUpEmpire(happiness: Int) {
        capital = world.addCity(3)
        unhappyCity = world.addCity(10)
        world.build(unhappyCity, "[+500 Food]", "Removes extra unhappiness from annexed cities")
        world.addSpecialists(unhappyCity, 10)
        world.giveHappiness(capital, happiness)
        world.refresh()
    }

    private fun setApproval(happiness: Int) {
        world = NeedsTestWorld()
        setUpEmpire(happiness)
    }

    //region Golden ages

    private fun threshold(base: Int, cities: Int): Int {
        val speedPercent = (game.gameInfo.speed.modifier * 100).roundToInt()
        return base * (100 + 5 * cities) / 100 * speedPercent / 100 / 5 * 5
    }

    @Test
    fun `the first golden age needs 1000, later ones 400 plus 2000 each, plus 5 percent per effective city, rounded down to 5`() {
        world.addCity(1)
        val manager = civ.goldenAges
        assertEquals(threshold(1000, 1), manager.happinessRequiredForNextGoldenAge())
        world.addCity(1)
        world.addCity(1)
        assertEquals(threshold(1000, 3), manager.happinessRequiredForNextGoldenAge())
        world.addCity(1).isPuppet = true
        assertEquals("a puppet is no effective city", threshold(1000, 3), manager.happinessRequiredForNextGoldenAge())
        manager.storedHappiness = manager.happinessRequiredForNextGoldenAge()
        manager.endTurn(0) // the first golden age
        assertEquals(1, manager.numberOfGoldenAges)
        assertEquals(threshold(400 + 2000, 3), manager.happinessRequiredForNextGoldenAge())
    }

    @Test
    fun `the threshold is rounded down to the visible divisor`() {
        world.constants.needs = world.constants.needs.copy(goldenAgeThresholdInitial = 1003)
        world.addCity(1)
        assertEquals(threshold(1003, 1), civ.goldenAges.happinessRequiredForNextGoldenAge())
    }

    @Test
    fun `without the key a golden age costs 500 plus 250 each`() {
        world.constants.cityNeedsLikeVp = false
        world.addCity(1)
        val speedPercent = (game.gameInfo.speed.modifier * 100).roundToInt()
        assertEquals(500 * speedPercent / 100 / 5 * 5, civ.goldenAges.happinessRequiredForNextGoldenAge())
    }

    @Test
    fun `the meter keeps filling during a golden age`() {
        world.addCity(1)
        civ.goldenAges.turnsLeftForCurrentGoldenAge = 5
        civ.goldenAges.endTurn(7)
        assertEquals(4, civ.goldenAges.turnsLeftForCurrentGoldenAge)
        assertEquals(7, civ.goldenAges.storedHappiness)
        civ.goldenAges.endTurn(3)
        assertEquals(10, civ.goldenAges.storedHappiness)
    }

    @Test
    fun `without the key the meter is idle during a golden age`() {
        world.constants.cityNeedsLikeVp = false
        world.addCity(1)
        civ.goldenAges.turnsLeftForCurrentGoldenAge = 5
        civ.goldenAges.endTurn(7)
        assertEquals(0, civ.goldenAges.storedHappiness)
    }

    @Test
    fun `a golden age that ends this turn is followed at once by one the meter pays for`() {
        world.addCity(1)
        val manager = civ.goldenAges
        val threshold = manager.happinessRequiredForNextGoldenAge()
        manager.turnsLeftForCurrentGoldenAge = 1
        manager.storedHappiness = threshold - 5
        manager.endTurn(10)
        assertTrue("the next golden age started", manager.isGoldenAge())
        assertEquals("the meter paid for it", 5, manager.storedHappiness)
        assertEquals(1, manager.numberOfGoldenAges)
    }

    //endregion
    //region Uprisings

    private fun barbarians() = game.addBarbarianCiv()

    /** 4 + 0..2 turns, longer on slow speeds, then half the first time, 3 quarters when super unhappy */
    private fun seedLengths(superUnhappy: Boolean) = (4..6).map { (it * speed).toInt() / 2 }
        .map { if (superUnhappy) it * 75 / 100 else it }.map { it.coerceAtLeast(1) }.toSet()

    @Test
    fun `a very unhappy empire seeds the uprising counter with half the length`() {
        barbarians()
        setUpEmpire(VERY_UNHAPPY)
        assertTrue(civ.isEmpireVeryUnhappy())
        assertFalse(civ.isEmpireSuperUnhappy())
        TurnManager(civ).updateRevolts()
        assertTrue("${civ.uprisingCounter} in ${seedLengths(false)}", civ.uprisingCounter in seedLengths(false))
    }

    @Test
    fun `a super unhappy empire seeds a quarter shorter`() {
        barbarians()
        setUpEmpire(SUPER_UNHAPPY)
        assertTrue(civ.isEmpireSuperUnhappy())
        TurnManager(civ).updateRevolts()
        assertTrue("${civ.uprisingCounter} in ${seedLengths(true)}", civ.uprisingCounter in seedLengths(true))
    }

    @Test
    fun `an empire that is only unhappy has no uprising`() {
        barbarians()
        setUpEmpire(UNHAPPY)
        assertTrue(civ.isEmpireUnhappy())
        assertFalse(civ.isEmpireVeryUnhappy())
        TurnManager(civ).updateRevolts()
        assertEquals(0, civ.uprisingCounter)
        civ.uprisingCounter = 2
        TurnManager(civ).updateRevolts()
        assertEquals("the counter keeps its value", 2, civ.uprisingCounter)
    }

    @Test
    fun `the counter runs out in an uprising of the rebels the source counts`() {
        val barbarians = barbarians()
        setUpEmpire(VERY_UNHAPPY)
        // 25 more cities of 1 citizen, which need and cost nothing: the rebels grow with the number of cities
        val spots = game.tileMap.tileList.filter { !it.isCityCenter() && it.getTilesInDistance(1).none { near -> near.isCityCenter() } }
            .filterIndexed { index, _ -> index % 3 == 0 }.take(25)
        for (spot in spots) game.addCity(civ, spot, initialPopulation = 1)
        world.refresh()
        assertTrue(civ.isEmpireVeryUnhappy())
        val cities = civ.cities.size
        civ.uprisingCounter = 1
        TurnManager(civ).updateRevolts()
        val rebels = barbarians.units.getCivUnits().count()
        val minimum = (100 + cities * 20) / 100
        val maximum = (100 + (2 * cities - 1) * 20) / 100
        assertTrue("$rebels rebels for $cities cities, $minimum to $maximum", rebels in minimum..maximum)
    }

    //endregion
    //region City revolts

    private lateinit var rival: Civilization

    private fun addRival() {
        rival = game.addCiv()
        game.addCity(rival, game.getTile(HexCoord(0, -6)), replacePalace = true)
    }

    private fun updateRevolts() = TurnManager(civ).updateCityRevolts()

    private fun seedTurns(superUnhappy: Boolean): Int {
        val seed = (10 * game.gameInfo.speed.productionCostModifier.coerceAtLeast(1f)).toInt()
        return (if (superUnhappy) seed * 75 / 100 else seed).coerceAtLeast(1)
    }

    @Test
    fun `a very unhappy empire starts the revolt counter at 10 turns and a city of another founder revolts to it`() {
        addRival()
        setUpEmpire(VERY_UNHAPPY)
        unhappyCity.foundingCivObject = rival
        updateRevolts()
        assertEquals(seedTurns(false), civ.flagsCountdown[flag])
        repeat(seedTurns(false) - 1) { updateRevolts() }
        assertEquals(1, civ.flagsCountdown[flag])
        assertSame(civ, unhappyCity.civ)
        updateRevolts()
        assertFalse(civ.hasFlag(flag))
        assertSame("the founder gets its city back", rival, unhappyCity.civ)
    }

    @Test
    fun `a super unhappy empire waits a quarter fewer turns`() {
        addRival()
        setUpEmpire(SUPER_UNHAPPY)
        unhappyCity.foundingCivObject = rival
        assertTrue(civ.isEmpireSuperUnhappy())
        updateRevolts()
        assertEquals(seedTurns(true), civ.flagsCountdown[flag])
    }

    @Test
    fun `an empire that is not very unhappy drops the counter`() {
        addRival()
        setUpEmpire(UNHAPPY)
        unhappyCity.foundingCivObject = rival
        civ.addFlag(flag, 5)
        updateRevolts()
        assertFalse(civ.hasFlag(flag))
        assertSame(civ, unhappyCity.civ)
    }

    @Test
    fun `the capital never revolts and a city that is not unhappy neither`() {
        addRival()
        capital = world.addCity(10)
        world.build(capital, "[+500 Food]")
        world.addSpecialists(capital, 10)
        val happy = world.addCity(3)
        world.build(happy, "Removes extra unhappiness from annexed cities")
        happy.foundingCivObject = rival
        world.giveHappiness(happy, 6)
        world.refresh()
        assertTrue(civ.isEmpireVeryUnhappy())
        assertTrue(world.needsOf(capital).delta < 0)
        assertTrue(world.needsOf(happy).delta > 0)
        updateRevolts()
        assertNull("only a city that is not the capital and has a negative delta can revolt", civ.flagsCountdown[flag])
    }

    @Test
    fun `a city we founded revolts only to a civilization that has the people's revolution or out-influences us while we are super unhappy`() {
        addRival()
        setUpEmpire(VERY_UNHAPPY)
        updateRevolts()
        assertNull("nobody to receive it", civ.flagsCountdown[flag])
        PublicOpinion::class.java.getDeclaredField("type").apply { isAccessible = true }
            .set(civ.culture.publicOpinion, PublicOpinionType.RevolutionaryWave)
        updateRevolts()
        assertEquals(seedTurns(false), civ.flagsCountdown[flag])
        repeat(seedTurns(false)) { updateRevolts() }
        assertSame(rival, unhappyCity.civ)
    }

    @Test
    fun `a city we founded counts a quarter, so a city of another founder with less unhappiness is the one to go`() {
        addRival()
        capital = world.addCity(3)
        unhappyCity = world.addCity(10)
        world.build(unhappyCity, "[+500 Food]")
        world.addSpecialists(unhappyCity, 10)
        val other = world.addCity(6)
        world.build(other, "[+500 Food]", "Removes extra unhappiness from annexed cities")
        world.addSpecialists(other, 6)
        other.foundingCivObject = rival
        world.giveHappiness(capital, 6)
        world.refresh()
        assertTrue(world.needsOf(unhappyCity).delta < world.needsOf(other).delta)
        updateRevolts()
        assertNotNull(civ.flagsCountdown[flag])
        repeat(seedTurns(civ.isEmpireSuperUnhappy())) { updateRevolts() }
        assertSame(rival, other.civ)
        assertSame(civ, unhappyCity.civ)
    }

    //endregion
    //region Settlers and goody huts

    private fun settlerCanFound(): Boolean {
        val tile = game.getTile(HexCoord(0, -5))
        val settler = game.addUnit("Settler", civ, tile)
        val action = UnitActions.getUnitActions(settler, UnitActionType.FoundCity).firstOrNull()
        settler.destroy()
        return action?.action != null
    }

    @Test
    fun `a very unhappy empire cannot found cities`() {
        setApproval(CONTENT)
        assertTrue("content", settlerCanFound())
        setApproval(UNHAPPY)
        assertTrue("only unhappy", settlerCanFound())
        setApproval(VERY_UNHAPPY)
        assertFalse("very unhappy", settlerCanFound())
    }

    @Test
    fun `without the key a very unhappy empire can found cities`() {
        world.constants.cityNeedsLikeVp = false
        setUpEmpire(VERY_UNHAPPY)
        civ.stats.happiness = -30
        assertTrue(settlerCanFound())
    }

    private fun survivorsOffered(): Boolean {
        val unit = game.addUnit("Warrior", civ, game.getTile(HexCoord(0, -5)))
        val offered = civ.ruinsManager.getEligibleRewards(unit).any { it.name.startsWith("survivors") }
        unit.destroy()
        return offered
    }

    @Test
    fun `goody huts give no population while the empire is unhappy`() {
        setApproval(CONTENT)
        assertTrue(survivorsOffered())
        setApproval(UNHAPPY)
        assertFalse("approval 42", survivorsOffered())
    }

    //endregion
}
