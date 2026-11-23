package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.TacticalAi
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.unit.tactical.TacticalSimulation
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ModConstants
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
 *  (ExecuteAirSweep with FindAirUnitsToAirSweep, PlotAirInterceptMoves, RecruitUnits for air units) and CvHomelandAI (ExecuteAircraftMoves) with BNW data.
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
        // These tests are about the AI's air decisions with the original numbers: six slots in a city, a rebase range of twice the range,
        // 200 hit points for a city without buildings and BNW's interception. The BNW ruleset turns the Vox Populi versions on.
        val constants = game.ruleset.modOptions.constants
        constants.airBasingLikeVp = false
        constants.airBasing = ModConstants.AirBasingConstants()
        constants.airDefenseLikeVp = false
        constants.airInterceptionLikeVp = false
        constants.cityCombatLikeVp = false
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
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.find(civ, map))
    }

    // region Counting and sweep targets

    @Test
    fun `enemy air units count within their own range, at most 12, plus half the range of the unit that asks`() {
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile()) // 10 from home: its range 8
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile()) // its range 10
        game.addUnit("Bomber", enemy, enemyWest.getCenterTile()) // 14 from home
        game.addUnit("Fighter", civ, home.getCenterTile())
        val center = home.getCenterTile()
        // a range of 0: the fighter needs 8, the bomber 10
        assertEquals(0, AirMoves.numEnemyAirUnitsInRange(civ, center, 0, countFighters = true, countBombers = false))
        assertEquals(1, AirMoves.numEnemyAirUnitsInRange(civ, center, 0, countFighters = false, countBombers = true))
        // a range of 5 adds 2: the fighter at 10 is in (10), the bomber at 10 is in (12), the other bomber at 14 is out
        assertEquals(1, AirMoves.numEnemyAirUnitsInRange(civ, center, 5, countFighters = true, countBombers = false))
        assertEquals(1, AirMoves.numEnemyAirUnitsInRange(civ, center, 5, countFighters = false, countBombers = true))
        // a range of 10 adds 5: the western bomber at 14 is in (15)
        assertEquals(1, AirMoves.numEnemyAirUnitsInRange(civ, center, 10, countFighters = true, countBombers = false))
        assertEquals(2, AirMoves.numEnemyAirUnitsInRange(civ, center, 10, countFighters = false, countBombers = true))
        assertEquals(3, AirMoves.numEnemyAirUnitsInRange(civ, center, 10, countFighters = true, countBombers = true))
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
        val recruited = TacticalAi.recruitUnits(civ, listOf(ready, hurt), AutomationCache())
        assertEquals(listOf(ready), recruited)
    }

    @Test
    fun `the interceptors over a tile are the visible units of players at war with us that can intercept it`() {
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile()) // 100 percent, range 8
        game.addUnit("Triplane", enemy, enemyEast.getCenterTile()) // 50 percent, range 5
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile()) // cannot intercept
        game.addUnit("Fighter", enemy, enemyWest.getCenterTile()) // 24 tiles from the city, beyond its range
        game.addUnit("Fighter", civ, forward.getCenterTile()) // ours do not count
        civ.viewableTiles = game.tileMap.values.toSet()
        assertEquals(2, AirMoves.interceptorCount(civ, enemyEast.getCenterTile()))
        civ.viewableTiles = game.tileMap.values.filter { it != enemyEast.getCenterTile() }.toSet()
        assertEquals("a tile we do not see hides its interceptors", 0, AirMoves.interceptorCount(civ, enemyEast.getCenterTile()))
    }

    @Test
    fun `no more fighters are found to sweep than there are interceptors, one at least, the first of the turn's units, the strongest of them first`() {
        // FindAirUnitsToAirSweep takes units in the order of the turn while interceptions are left, and sorts what it found
        val triplane = game.addUnit("Triplane", civ, forward.getCenterTile()) // strength 35
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile()) // 45
        val jet = game.addUnit("Jet Fighter", civ, forward.getCenterTile()) // 75
        val target = enemyEast.getCenterTile()
        var ctx = context()
        assertEquals("nothing intercepts, but a fighter may be there for recon", listOf(triplane), AirMoves.findAirUnitsToAirSweep(ctx, target))
        game.addUnit("Fighter", enemy, target)
        ctx = context()
        assertEquals(listOf(triplane), AirMoves.findAirUnitsToAirSweep(ctx, target))
        game.addUnit("Fighter", enemy, target)
        ctx = context()
        assertEquals(listOf(fighter, triplane), AirMoves.findAirUnitsToAirSweep(ctx, target))
        game.addUnit("Fighter", enemy, target)
        ctx = context()
        assertEquals(listOf(jet, fighter, triplane), AirMoves.findAirUnitsToAirSweep(ctx, target))
    }

    @Test
    fun `a fighter that cannot reach the target, has used its attack or is no sweeper is not found`() {
        val reaching = game.addUnit("Fighter", civ, forward.getCenterTile())
        val tooFar = game.addUnit("Triplane", civ, home.getCenterTile()) // range 5: 10 tiles from the target
        val bomber = game.addUnit("Bomber", civ, forward.getCenterTile())
        val spent = game.addUnit("Fighter", civ, forward.getCenterTile()).also { it.attacksThisTurn = 1 }
        val target = enemyEast.getCenterTile()
        for (i in 1..4) game.addUnit("Fighter", enemy, target)
        val found = AirMoves.findAirUnitsToAirSweep(context(), target)
        assertEquals(listOf(reaching), found)
        assertFalse(tooFar in found || bomber in found || spent in found)
    }

    @Test
    fun `the sweep before an attack is made when interceptors are over the target, and not made when none is`() {
        val jet = game.addUnit("Jet Fighter", civ, forward.getCenterTile())
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        val target = enemyEast.getCenterTile()
        val bomber = game.addUnit("Bomber", enemy, target)
        var ctx = context()
        ctx.airSweepUnits = AirMoves.findAirUnitsToAirSweep(ctx, target)
        AirMoves.executeAirSweep(ctx, target)
        assertEquals("a bomber cannot intercept, nothing to sweep", 0, jet.attacksThisTurn)
        assertTrue(ctx.cache.processedUnits.isEmpty())

        game.addUnit("Fighter", enemy, target)
        ctx = context()
        ctx.airSweepUnits = AirMoves.findAirUnitsToAirSweep(ctx, target)
        assertEquals(listOf(jet), ctx.airSweepUnits)
        AirMoves.executeAirSweep(ctx, target)
        assertTrue("the sweep counts as an attack", jet.attacksThisTurn > 0)
        assertTrue("and commands the sweeper, which is out of attacks", jet in ctx.cache.processedUnits)
        assertEquals(0, fighter.attacksThisTurn)
        assertFalse(fighter in ctx.cache.processedUnits)
        assertTrue(bomber.getTile() == target)
    }

    @Test
    fun `the units that strike a target give the fighters to sweep it only when aircraft strike`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile()) // the interceptor, 4 tiles from the target
        val prey = tile(6, 4)
        game.addUnit("Warrior", enemy, prey)
        var ctx = context()
        TacticalSimulation.findUnitsWithinStrikingDistance(ctx, prey)
        assertEquals("only a fighter kept for interception: no aircraft strikes", emptyList<MapUnit>(), ctx.airSweepUnits)

        val bomber = game.addUnit("Bomber", civ, forward.getCenterTile())
        ctx = context()
        val strikers = TacticalSimulation.findUnitsWithinStrikingDistance(ctx, prey)
        assertTrue(bomber in strikers)
        assertEquals(listOf(fighter), ctx.airSweepUnits)
    }

    @Test
    fun `with no ground unit to see the target a fighter sweeps a visible tile next to it for recon`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        val other = game.addUnit("Fighter", civ, forward.getCenterTile())
        val target = tile(8, 2)
        civ.viewableTiles = game.tileMap.values.filter { it != target }.toSet()
        civ.ai.invalidateTurnCaches()
        fun contextWithoutTargets() =
            TacticalContext(civ, civ.ai.tactical.map, civ.ai.plotDanger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.none())
        val ctx = contextWithoutTargets()
        assertFalse("no sweeper found yet", AirMoves.sweepForRecon(ctx, target))
        ctx.airSweepUnits = listOf(fighter)
        assertTrue(AirMoves.sweepForRecon(ctx, target))
        assertEquals(1, fighter.attacksThisTurn)
        assertTrue(fighter in ctx.cache.processedUnits)

        // the spotter move of an attack falls back on it when no ground unit can see the target
        val spotting = contextWithoutTargets()
        assertFalse(TacticalSimulation.executeSpotterMove(spotting, emptyList(), target))
        spotting.airSweepUnits = listOf(other)
        assertTrue(TacticalSimulation.executeSpotterMove(spotting, emptyList(), target))
        assertEquals(1, other.attacksThisTurn)
    }

    @Test
    fun `a fighter goes on patrol when enemy bombers are near and not when only a few fighters are`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        game.addUnit("Fighter", enemy, enemyEast.getCenterTile())
        var ctx = context()
        AirMoves.plotAirPatrolMoves(ctx)
        assertFalse("one fighter is a quarter of an interceptor, which rounds down to none", fighter in ctx.cache.processedUnits)
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        ctx = context()
        AirMoves.plotAirPatrolMoves(ctx)
        assertTrue(fighter in ctx.cache.processedUnits)
        assertTrue("it keeps its movement to intercept", fighter.hasMovement())
    }

    @Test
    fun `air patrol wants half the bombers and a quarter of the fighters in range as interceptors from one tile, and one bomber counts as two`() {
        // Fighter range 8: enemy aircraft count within their own range (a Bomber's 10, a Fighter's 8) plus half of 8
        val ours = List(4) { game.addUnit("Fighter", civ, forward.getCenterTile()) }
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        var ctx = context()
        AirMoves.plotAirPatrolMoves(ctx)
        assertEquals("one bomber: counts as two, so one interceptor", 1, ours.count { it in ctx.cache.processedUnits })

        val eastNear = game.addCity(enemy, tile(6, 4))
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        for (fighter in ours) game.addUnit("Fighter", enemy, eastNear.getCenterTile())
        ctx = context()
        AirMoves.plotAirPatrolMoves(ctx)
        assertEquals("2 bombers and 4 fighters: 1 + 1", 2, ours.count { it in ctx.cache.processedUnits })
    }

    @Test
    fun `air patrol is not for a bomber or a fighter that was used`() {
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        val bomber = game.addUnit("Bomber", civ, forward.getCenterTile())
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        game.addUnit("Bomber", enemy, enemyEast.getCenterTile())
        val ctx = context()
        ctx.unitProcessed(fighter, markTacticalMap = false)
        AirMoves.plotAirPatrolMoves(ctx)
        assertFalse("a bomber has no intercept chance", bomber in ctx.cache.processedUnits)
        assertEquals("the used fighter is the only interceptor there is", setOf(fighter), ctx.cache.processedUnits)
    }

    @Test
    fun `air patrol is not for a fighter out of the bombers' reach`() {
        // The western city is 14 from home and 20 from forward: a Bomber's range 10 plus half of the Fighter's 8 is 14
        game.addUnit("Bomber", enemy, enemyWest.getCenterTile())
        game.addUnit("Bomber", enemy, enemyWest.getCenterTile())
        val forwardFighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        val ctx = context()
        AirMoves.plotAirPatrolMoves(ctx)
        assertFalse(forwardFighter in ctx.cache.processedUnits)
        val homeFighter = game.addUnit("Fighter", civ, home.getCenterTile())
        val ctx2 = context()
        AirMoves.plotAirPatrolMoves(ctx2)
        assertTrue(homeFighter in ctx2.cache.processedUnits)
    }
    // endregion

    // region Nukes (Vox Populi: ShouldRebase, ExecuteAircraftMoves, ScoreAirBase, IsGoodUnitMix)

    /** The Atomic Bomb of the BNW ruleset has Vox Populi's range of 6 (it was 10), a rebase range of 12 here */
    private val NUKE_RANGE = 6

    /** Only the western enemy city is left; our new city at -8 has it within an Atomic Bomb's range of 6, home (14 from it) does not */
    private fun nukeSetup(): City {
        enemyEast.destroyCity(overrideSafeties = true)
        val west = game.addCity(civ, tile(-8))
        game.gameInfo.turns = 10 // the cities were not just taken
        return west
    }

    @Test
    fun `at war a nuke rebases to a base with an enemy city in range`() {
        val west = nukeSetup()
        val nuke = game.addUnit("Atomic Bomb", civ, home.getCenterTile())
        assertEquals(NUKE_RANGE, nuke.getRange())
        assertEquals(1, AirMoves.nukeBaseScore(civ, home.getCenterTile(), NUKE_RANGE, enemy.cities))
        assertEquals(21, AirMoves.nukeBaseScore(civ, west.getCenterTile(), NUKE_RANGE, enemy.cities))
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
        assertEquals(-1, AirMoves.nukeBaseScore(civ, west.getCenterTile(), NUKE_RANGE, enemy.cities))
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
        // The rebase range is 12 (twice the nuke's range of 6): the city at 12 is 20 from the base at -8, out of reach, and has home (12 away)
        // and the forward city (6 away) in reach
        val far = game.addCity(civ, tile(12))
        val nuke = game.addUnit("Atomic Bomb", civ, far.getCenterTile())
        AirMoves.plotNukeMove(nuke)
        assertEquals("home is 8 from the best base, the forward city 14", home.getCenterTile(), nuke.getTile())
        nuke.currentMovement = 1f
        AirMoves.plotNukeMove(nuke)
        assertEquals(west.getCenterTile(), nuke.getTile())
    }
    // endregion
}
