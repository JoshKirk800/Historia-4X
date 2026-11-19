package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.TacticalAi
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The air moves ported from CvMilitaryAI (WillAirUnitRebase, GetNumEnemyAirUnitsInRange, GetBestAirSweepTarget), CvTacticalAI
 *  (PlotAirSweepMoves, PlotAirInterceptMoves, CommandeerUnits for air units) and CvHomelandAI (ExecuteAircraftMoves) with BNW data.
 *  Cities lie on the x axis: ours at 0 and 6, the enemy's at 14 and -14; a Fighter has range 5 (rebase range 10), a Bomber 10.
 */
@RunWith(BaseTestRunner::class)
class AirMovesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var home: City
    private lateinit var forward: City
    private lateinit var enemyEast: City
    private lateinit var enemyWest: City

    /** A city without buildings: 200 hit points */
    private val MAX_CITY_HEALTH = 200

    @Before
    fun setUp() {
        game.makeHexagonalMap(24)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        civ.viewableTiles = game.tileMap.values.toSet()
        home = game.addCity(civ, tile(0))
        forward = game.addCity(civ, tile(6))
        enemyEast = game.addCity(enemy, tile(10))
        enemyWest = game.addCity(enemy, tile(-14))
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))

    private fun context(): TacticalContext {
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.find(civ, map, danger))
    }

    // region Counting and sweep targets

    @Test
    fun `enemy air units within 10 tiles count as fighters or bombers`() {
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        game.addUnit("Bomber", enemy, enemyWest.getCenterTile()) // 14 away
        game.addUnit("Fighter", civ, home.getCenterTile())
        val center = home.getCenterTile()
        assertEquals(1, AirMoves.numEnemyAirUnitsInRange(civ, center, countFighters = true, countBombers = false))
        assertEquals(1, AirMoves.numEnemyAirUnitsInRange(civ, center, countFighters = false, countBombers = true))
        assertEquals(2, AirMoves.numEnemyAirUnitsInRange(civ, center, countFighters = true, countBombers = true))
    }

    @Test
    fun `the air sweep target is the enemy city in range holding the most fighters`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        val bigger = game.addCity(enemy, tile(6, 4))
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        game.addUnit("Fighter", enemy, bigger.getCenterTile())
        game.addUnit("Fighter", enemy, bigger.getCenterTile())
        game.addUnit("Fighter", enemy, enemyWest.getCenterTile()) // out of range
        assertEquals(bigger.getCenterTile(), AirMoves.bestAirSweepTarget(civ, fighter))
    }

    @Test
    fun `no air sweep target without a fighter in a visible city in range`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        assertNull("no air units there", AirMoves.bestAirSweepTarget(civ, fighter))
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        assertNull("a bomber does not count", AirMoves.bestAirSweepTarget(civ, fighter))
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        civ.viewableTiles = emptySet()
        assertNull("not visible", AirMoves.bestAirSweepTarget(civ, fighter))
        civ.viewableTiles = game.tileMap.values.toSet()
        assertEquals(enemyEast.getCenterTile(), AirMoves.bestAirSweepTarget(civ, fighter))
    }
    // endregion

    // region Rebasing

    @Test
    fun `a fighter with nothing to do where it is rebases, one with enemy aircraft near does not`() {
        val fighter = game.addUnit("Fighter", civ, home.getCenterTile())
        assertTrue("no enemy aircraft and no sweep target", AirMoves.willAirUnitRebase(fighter))
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        assertFalse("an enemy bomber is within 10 tiles", AirMoves.willAirUnitRebase(fighter))
    }

    @Test
    fun `a bomber rebases only when its base has lost over a fifth of its hit points and a healthy base is in reach`() {
        val bomber = game.addUnit("Bomber", civ, home.getCenterTile())
        assertFalse(AirMoves.willAirUnitRebase(bomber))
        home.health = MAX_CITY_HEALTH * 4 / 5 + 1 // not yet
        assertFalse(AirMoves.willAirUnitRebase(bomber))
        home.health = MAX_CITY_HEALTH * 4 / 5 - 1
        assertTrue(AirMoves.willAirUnitRebase(bomber))
        forward.health = MAX_CITY_HEALTH / 2
        assertFalse("the other base is hurt as well and there is nowhere to go", AirMoves.willAirUnitRebase(bomber))
    }

    @Test
    fun `an aircraft rebases to the base in the most plot danger`() {
        val bomber = game.addUnit("Bomber", civ, home.getCenterTile())
        home.health = 10
        game.addUnit("Archer", enemy, tile(8)) // next to the forward city, which has the danger
        civ.ai.invalidateTurnCaches()
        assertEquals(forward.getCenterTile(), AirMoves.rebaseTarget(bomber))
        assertTrue(AirMoves.plotAircraftMove(bomber))
        assertEquals(forward.getCenterTile(), bomber.getTile())
    }

    @Test
    fun `an aircraft whose own base is the most dangerous stays but its turn is over`() {
        val bomber = game.addUnit("Bomber", civ, home.getCenterTile())
        // hurt just enough to want to leave, then every other city is out of reach or hurt
        home.health = MAX_CITY_HEALTH / 2
        forward.health = MAX_CITY_HEALTH / 2
        assertFalse(AirMoves.plotAircraftMove(bomber))
        assertEquals(home.getCenterTile(), bomber.getTile())
    }

    @Test
    fun `damaged aircraft are left to heal`() {
        val bomber = game.addUnit("Bomber", civ, home.getCenterTile())
        home.health = 10
        bomber.health = 40
        assertFalse(AirMoves.plotAircraftMove(bomber))
        assertEquals(home.getCenterTile(), bomber.getTile())
    }

    @Test
    fun `a carrier with a free slot is a base, a full one or a damaged one is not`() {
        val water = tile(0, 3)
        game.setTileTerrain(water.position, "Coast")
        game.setTileTerrain(tile(0, 4).position, "Coast")
        val bomber = game.addUnit("Bomber", civ, home.getCenterTile())
        home.health = 10
        forward.health = 10 // no city to go to
        val carrier = game.addUnit("Carrier", civ, water)
        assertEquals(water, AirMoves.rebaseTarget(bomber))
        carrier.health = 50
        assertEquals("a damaged carrier is no place to land", home.getCenterTile(), AirMoves.rebaseTarget(bomber))
        carrier.health = 100
        game.addUnit("Bomber", civ, water)
        game.addUnit("Bomber", civ, water)
        assertEquals("both slots taken", home.getCenterTile(), AirMoves.rebaseTarget(bomber))
    }
    // endregion

    // region Tactical moves

    @Test
    fun `air units with more than half their health that will not rebase are tactical`() {
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile()) // a reason to stay
        val ready = game.addUnit("Fighter", civ, home.getCenterTile())
        val hurt = game.addUnit("Fighter", civ, forward.getCenterTile())
        hurt.health = 40
        val commandeered = TacticalAi.commandeerUnits(civ, listOf(ready, hurt), AutomationCache())
        assertEquals(listOf(ready), commandeered)
    }

    @Test
    fun `a fighter in a city that has a city of the enemy with fighters in range sweeps it`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        // the city's garrison, so that we dominate its zone: a lone fighter against one of theirs is only an even match (Vox Populi's 70 percent)
        for (tile in forward.getCenterTile().neighbors.take(2)) game.addUnit("Swordsman", civ, tile)
        val defender = game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        val ctx = context()
        assertEquals(TacticalDominance.Friendly, ctx.map.zoneByCity(forward, false)!!.dominance)
        AirMoves.plotAirSweepMoves(ctx)
        assertTrue(fighter in ctx.cache.processedUnits)
        assertTrue("the sweep counts as an attack", fighter.attacksThisTurn > 0)
        assertTrue(defender.getTile() == enemyEast.getCenterTile())
    }

    @Test
    fun `a fighter that is hurt or that will rebase does not sweep`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        fighter.health = 45
        val ctx = context()
        AirMoves.plotAirSweepMoves(ctx)
        assertFalse(fighter in ctx.cache.processedUnits)
    }

    @Test
    fun `a fighter stays to intercept when enemy bombers are near and does nothing when only fighters are`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        var ctx = context()
        AirMoves.plotAirInterceptMoves(ctx)
        assertFalse("only fighters near", fighter in ctx.cache.processedUnits)
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        ctx = context()
        AirMoves.plotAirInterceptMoves(ctx)
        assertTrue(fighter in ctx.cache.processedUnits)
        assertTrue("it keeps its movement to intercept", fighter.hasMovement())
    }
    // endregion

    // region Nukes (Vox Populi: ShouldRebase, ExecuteAircraftMoves, ScoreAirBase, IsGoodUnitMix)

    /** Only the western enemy city is left; our new city at -6 has it within an Atomic Bomb's range of 10, home (14 from it) does not */
    private fun nukeSetup(): City {
        enemyEast.destroyCity(overrideSafeties = true)
        val west = game.addCity(civ, tile(-6))
        game.gameInfo.turns = 10 // the cities were not just taken
        return west
    }

    @Test
    fun `at war a nuke rebases to a base with an enemy city in range`() {
        val west = nukeSetup()
        val nuke = game.addUnit("Atomic Bomb", civ, home.getCenterTile())
        assertEquals(1, AirMoves.nukeBaseScore(civ, home.getCenterTile(), 10, enemy.cities))
        assertEquals(21, AirMoves.nukeBaseScore(civ, west.getCenterTile(), 10, enemy.cities))
        assertTrue(AirMoves.plotNukeMove(nuke))
        assertEquals(west.getCenterTile(), nuke.getTile())
    }

    /** Our operations run at the start of the turn, before movement comes back: a nuke that stays keeps its movement for the nuke attack */
    @Test
    fun `a nuke already in the best base keeps its movement`() {
        val west = nukeSetup()
        val nuke = game.addUnit("Atomic Bomb", civ, west.getCenterTile())
        assertFalse(AirMoves.plotNukeMove(nuke))
        assertEquals(west.getCenterTile(), nuke.getTile())
        assertTrue(nuke.hasMovement())
    }

    @Test
    fun `at peace a nuke in a safe base stays`() {
        nukeSetup()
        civ.getDiplomacyManager(enemy)!!.makePeace()
        val nuke = game.addUnit("Atomic Bomb", civ, home.getCenterTile())
        AirMoves.plotNukeMove(nuke)
        assertEquals(home.getCenterTile(), nuke.getTile())
    }

    @Test
    fun `a nuke does not rebase to a base just taken or one with three offensive aircraft more than fighters`() {
        val west = nukeSetup()
        val nuke = game.addUnit("Atomic Bomb", civ, home.getCenterTile())
        west.turnAcquired = game.gameInfo.turns - 1
        assertEquals(-1, AirMoves.nukeBaseScore(civ, west.getCenterTile(), 10, enemy.cities))
        AirMoves.plotNukeMove(nuke)
        assertEquals("just taken", home.getCenterTile(), nuke.getTile())

        west.turnAcquired = 0
        repeat(3) { game.addUnit("Bomber", civ, west.getCenterTile()) }
        assertFalse(AirMoves.isGoodUnitMix(west.getCenterTile(), nuke))
        game.addUnit("Fighter", civ, west.getCenterTile())
        assertTrue("a fighter evens it out", AirMoves.isGoodUnitMix(west.getCenterTile(), nuke))
    }

    @Test
    fun `a nuke out of reach of the best base goes to the base in reach nearest to it`() {
        val west = nukeSetup()
        val far = game.addCity(civ, tile(20)) // 26 from the base at -6, beyond a rebase range of 20
        val nuke = game.addUnit("Atomic Bomb", civ, far.getCenterTile())
        AirMoves.plotNukeMove(nuke)
        assertEquals("home is 6 from the best base, the forward city 12", home.getCenterTile(), nuke.getTile())
        nuke.currentMovement = 1f
        AirMoves.plotNukeMove(nuke)
        assertEquals(west.getCenterTile(), nuke.getTile())
    }
    // endregion
}
