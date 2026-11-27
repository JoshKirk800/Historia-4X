package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's war weariness (CvPlayer::ApplyWarDamage, DoUpdateWarDamageAndWeariness, GetWarWearinessPercent, GetHighestWarWearinessPercent;
 *  CvTeam::GetNumTurnsAtWar, canChangeWarPeace; Release-5.4.8), the state and rules only: nothing reads the percent yet.
 *
 *  Every civilization has one size-1 original capital and nothing else, which is worth (175 + 150) x 2 = 650 (see [WarDamageTests]): the
 *  empire's value is 650 for each, so a hundredth of it is 6 and the percent of a weariness w is 100 w / 650 (100 -> 15, 130 -> 20, 260 -> 40).
 */
@RunWith(GdxTestRunner::class)
class WarWearinessTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.ruleset.modOptions.constants.warWearinessLikeVp = true
        a = game.addCiv()
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(8, 0)))
        game.addCity(c, game.getTile(HexCoord(-8, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
    }

    private fun weariness(of: Civilization, against: Civilization) = WarDamageAi.warWeariness(of, against)

    private fun declareWar(on: Civilization = b) = a.getDiplomacyManager(on)!!.declareWar()

    /** Run the turn update of [civ] with the war [age] turns old: the war began on turn 0 (when it was declared) */
    private fun turnAtWarAge(civ: Civilization, age: Int) {
        game.gameInfo.turns = age
        WarDamageAi.doTurn(civ)
    }

    @Test
    fun `war damage makes the winner weary by half of it and the loser by all of it`() {
        declareWar()
        WarDamageAi.applyWarDamage(a, b, 301)
        assertEquals("301 / 2 truncated", 150, weariness(a, b))
        assertEquals(301, weariness(b, a))
        assertEquals("nothing against a civilization it did no damage to", 0, weariness(a, c))
    }

    @Test
    fun `the weariness comes with or without the rating and the common foe, as the razing turn has it`() {
        declareWar()
        WarDamageAi.applyWarDamage(a, b, 300, noRating = true, noCommonFoe = true)
        assertEquals(150, weariness(a, b))
        assertEquals(300, weariness(b, a))
        assertEquals("the rating did not move", WarDamageAi.startingMilitaryRating(a), WarDamageAi.militaryRating(a))
    }

    @Test
    fun `a city-state neither gets weary nor wearies a major`() {
        val cityState = game.addCiv(cityStateType = "Militaristic")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        a.getDiplomacyManager(cityState)!!.declareWar()
        WarDamageAi.applyWarDamage(a, cityState, 300)
        WarDamageAi.applyWarDamage(cityState, a, 300)
        assertEquals(0, weariness(a, cityState))
        assertEquals(0, weariness(cityState, a))
    }

    @Test
    fun `a weariness never goes below 0`() {
        declareWar()
        WarDamageAi.applyWarDamage(a, b, 100)
        assertEquals(50, weariness(a, b))
        WarDamageAi.applyWarDamage(a, b, -400)
        assertEquals("50 + (-400) / 2 = -150 is kept at 0", 0, weariness(a, b))
        assertEquals("100 + (-400) = -300 is kept at 0", 0, weariness(b, a))
    }

    @Test
    fun `the weariness is not kept without the switch`() {
        game.ruleset.modOptions.constants.warWearinessLikeVp = false
        declareWar()
        WarDamageAi.applyWarDamage(a, b, 300)
        turnAtWarAge(a, 20)
        assertEquals(0, weariness(a, b))
        assertEquals(0, weariness(b, a))
    }

    @Test
    fun `a war adds a hundredth of the empire's value a turn once it is ten turns old`() {
        declareWar()
        turnAtWarAge(a, 9)
        assertEquals("nine turns old: nothing yet", 0, weariness(a, b))
        turnAtWarAge(a, 10)
        assertEquals("650 / 100 = 6", 6, weariness(a, b))
        turnAtWarAge(a, 11)
        assertEquals(12, weariness(a, b))
    }

    @Test
    fun `an empire worth less than a hundred still gains one a turn`() {
        // a settler-less nation with one worker: no city, no unit worth anything, so its value is 0 and 0 / 100 = 0 becomes 1
        val poor = game.addCiv()
        game.addUnit("Worker", poor, game.getTile(HexCoord(0, 5)))
        poor.diplomacyFunctions.makeCivilizationsMeet(b)
        poor.getDiplomacyManager(b)!!.declareWar()
        assertEquals(0, WarDamageAi.empireWarValue(poor))
        turnAtWarAge(poor, 10)
        assertEquals(1, weariness(poor, b))
        assertEquals("the value is cached as it is: 0", 0, poor.ai.diplomacy.cachedWarValue)
    }

    @Test
    fun `a war that cannot be ended adds nothing`() {
        declareWar()
        a.ai.diplomacy.memoryOf(b).turnsLockedIntoWar = 5
        turnAtWarAge(a, 20)
        assertEquals(0, weariness(a, b))
        a.ai.diplomacy.memoryOf(b).turnsLockedIntoWar = 0
        b.ai.diplomacy.memoryOf(a).turnsLockedIntoWar = 5
        turnAtWarAge(a, 21)
        assertEquals("the other side's lock counts too", 0, weariness(a, b))
        b.ai.diplomacy.memoryOf(a).turnsLockedIntoWar = 0
        turnAtWarAge(a, 22)
        assertEquals(6, weariness(a, b))
    }

    @Test
    fun `at peace the weariness halves each turn and the war start is forgotten`() {
        declareWar()
        WarDamageAi.applyWarDamage(b, a, 601)
        assertEquals("the winner b: 601 / 2", 300, weariness(b, a))
        assertEquals("the loser a: all of it", 601, weariness(a, b))
        a.getDiplomacyManager(b)!!.makePeace()
        assertEquals("the peace itself clears the turn the war began", Int.MAX_VALUE, WarDamageAi.numTurnsAtWar(a, b))
        WarDamageAi.doTurn(a)
        WarDamageAi.doTurn(b)
        assertEquals("601 / 2", 300, weariness(a, b))
        assertEquals("300 / 2", 150, weariness(b, a))
        WarDamageAi.doTurn(a)
        assertEquals("300 / 2", 150, weariness(a, b))
    }

    @Test
    fun `the weariness against a dead civilization halves even when the war flag is still set`() {
        // no city, no unit and never a capital: the engine counts it as defeated. A war flag left standing and a war old enough to add
        // weariness (value 650 -> 6) would give 301 + 6 = 307 if the dead were treated as a living enemy
        val dead = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(dead)
        a.getDiplomacyManager(dead)!!.declareWar()
        assertTrue("the test needs a dead civilization", dead.isDefeated())
        assertTrue(a.isAtWarWith(dead))
        a.ai.diplomacy.memoryOf(dead).warWeariness = 301
        turnAtWarAge(a, 30)
        assertEquals("301 / 2", 150, weariness(a, dead))
    }

    @Test
    fun `the war's age is the game turn less the turn it began`() {
        game.gameInfo.turns = 7
        declareWar()
        assertEquals(0, WarDamageAi.numTurnsAtWar(a, b))
        assertEquals(0, WarDamageAi.numTurnsAtWar(b, a))
        game.gameInfo.turns = 19
        assertEquals(12, WarDamageAi.numTurnsAtWar(a, b))
    }

    @Test
    fun `the percent is the weariness over the empire's value, cut not rounded, and at most 100`() {
        declareWar()
        WarDamageAi.doTurn(a) // caches the empire's value, 650
        a.ai.diplomacy.memoryOf(b).warWeariness = 100
        assertEquals("100 x 100 / 650 = 15.38", 15, WarDamageAi.warWearinessPercent(a, b))
        a.ai.diplomacy.memoryOf(b).warWeariness = 2000
        assertEquals("307 is capped", 100, WarDamageAi.warWearinessPercent(a, b))
        assertEquals(100, WarDamageAi.highestWarWearinessPercent(a))
    }

    @Test
    fun `the highest percent is the greatest weariness of an opponent whose war can end`() {
        declareWar()
        a.getDiplomacyManager(c)!!.declareWar()
        WarDamageAi.doTurn(a)
        a.ai.diplomacy.memoryOf(b).warWeariness = 130
        a.ai.diplomacy.memoryOf(c).warWeariness = 260
        assertEquals("260: 100 x 260 / 650", 40, WarDamageAi.highestWarWearinessPercent(a))
        a.ai.diplomacy.memoryOf(c).turnsLockedIntoWar = 3
        assertEquals("the locked war with c is left out: 130", 20, WarDamageAi.highestWarWearinessPercent(a))
        assertEquals("but the percent against c itself is still 40", 40, WarDamageAi.warWearinessPercent(a, c))
    }

    @Test
    fun `without the switch there is no percent even when a weariness is set`() {
        declareWar()
        WarDamageAi.doTurn(a)
        a.ai.diplomacy.memoryOf(b).warWeariness = 130
        assertEquals(20, WarDamageAi.warWearinessPercent(a, b))
        game.ruleset.modOptions.constants.warWearinessLikeVp = false
        assertEquals(0, WarDamageAi.warWearinessPercent(a, b))
        assertEquals(0, WarDamageAi.highestWarWearinessPercent(a))
    }

    /** A civilization with one size-1 original capital (value 650) and the given [uniques], at war with [b] */
    private fun civAtWarWithB(vararg uniques: String, at: HexCoord): Civilization {
        val civ = game.addCiv(*uniques)
        game.addCity(civ, game.getTile(at))
        civ.diplomacyFunctions.makeCivilizationsMeet(b)
        civ.getDiplomacyManager(b)!!.declareWar()
        WarDamageAi.doTurn(civ) // caches the value, 650
        return civ
    }

    @Test
    fun `the policies' and the trait's modifiers add up and cut the percent, the order of the original`() {
        val wary = civAtWarWithB("[25]% less war weariness", "[50]% less war weariness", at = HexCoord(0, 6))
        wary.ai.diplomacy.memoryOf(b).warWeariness = 260
        assertEquals("100 x 260 / 650 = 40, x (100 - 75) / 100", 10, WarDamageAi.warWearinessPercent(wary, b))
        val small = civAtWarWithB("[25]% less war weariness", at = HexCoord(0, -6))
        small.ai.diplomacy.memoryOf(b).warWeariness = 10
        assertEquals("100 x 10 / 650 = 1 first, and 1 x 75 / 100 = 0", 0, WarDamageAi.warWearinessPercent(small, b))
    }

    @Test
    fun `the modifiers cannot cut more than all of the percent, nor raise it`() {
        val immune = civAtWarWithB("[60]% less war weariness", "[60]% less war weariness", at = HexCoord(0, 6))
        immune.ai.diplomacy.memoryOf(b).warWeariness = 260
        assertEquals("120 is read as 100", 0, WarDamageAi.warWearinessPercent(immune, b))
        val worse = civAtWarWithB("[-25]% less war weariness", at = HexCoord(0, -6))
        worse.ai.diplomacy.memoryOf(b).warWeariness = 260
        assertEquals("a negative one raises it: 40 x 125 / 100", 50, WarDamageAi.warWearinessPercent(worse, b))
    }

    @Test
    fun `the damage of a civilization with the enemy modifier wearies its enemy more, and itself no more`() {
        val raider = civAtWarWithB("Enemies gain [100]% more war weariness from our war damage", at = HexCoord(0, 6))
        WarDamageAi.applyWarDamage(raider, b, 650)
        assertEquals("the raider as winner: half, unchanged", 325, weariness(raider, b))
        assertEquals("b as loser: 650 x (100 + 100) / 100", 1300, weariness(b, raider))
        WarDamageAi.applyWarDamage(b, raider, 650)
        assertEquals("b as winner: half, the raider's modifier does not apply", 1625, weariness(b, raider))
        assertEquals("the raider as loser: the whole, b has no modifier", 975, weariness(raider, b))
    }

    @Test
    fun `a turn of war against the enemy modifier's holder takes the hundredth first and then the extra`() {
        val raider = civAtWarWithB("Enemies gain [100]% more war weariness from our war damage", at = HexCoord(0, 6))
        b.ai.diplomacy.memoryOf(raider).warWeariness = 0
        turnAtWarAge(b, 20)
        assertEquals("650 / 100 = 6, x 200 / 100 = 12 (not 650 x 2 / 100 = 13)", 12, weariness(b, raider))
    }

    @Test
    fun `a civilization that has not met the other has no percent against it`() {
        val stranger = game.addCiv()
        game.addCity(stranger, game.getTile(HexCoord(0, -8)))
        a.ai.diplomacy.memoryOf(stranger).warWeariness = 300
        WarDamageAi.doTurn(a)
        assertEquals(0, WarDamageAi.warWearinessPercent(a, stranger))
        assertEquals(0, WarDamageAi.highestWarWearinessPercent(a))
    }
}
