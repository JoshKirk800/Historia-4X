package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The posture moves of CvTacticalAI (ProcessDominanceZones, PlotSitAndBombardMoves ... PlotShoreBombardmentMoves, PlotCloseOnTarget): the
 *  posture the zone has decides which move its units make. The posture is set directly (its choice is [PostureSelectionTests]'), the enemy city
 *  is a temporary zone's, so that the zone is worth using.
 */
@RunWith(BaseTestRunner::class)
class PosturesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private val state get() = civ.ai.tactical

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun context(): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.find(civ, map, danger))
    }

    /** The posture of the zone around [city] (the owner's land, on water or not) */
    private fun setPosture(city: City, posture: TacticalPosture, isWater: Boolean = false) {
        state.postures = arrayListOf(ZonePosture().also {
            it.ownerId = city.civ.civName
            it.cityId = city.id
            it.isWater = isWater
            it.posture = posture
        })
    }

    private fun enemyCity(x: Int = 0, y: Int = 0): City = game.addCity(enemy, tile(x, y)).also { state.addTemporaryZone(it.getCenterTile(), TacticalTargetType.City) }

    private fun unit(name: String, owner: Civilization, x: Int, y: Int): MapUnit = game.addUnit(name, owner, tile(x, y))

    private fun isGone(unit: MapUnit) = unit.isDestroyed || unit.civ == civ

    // region ProcessDominanceZones

    @Test
    fun `a zone move runs for the zones that are worth using, with the zone's targets and its posture`() {
        val city = enemyCity()
        unit("Swordsman", civ, 0, -3)
        unit("Warrior", enemy, 0, 1)
        setPosture(city, TacticalPosture.Hedgehog)
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!

        val seen = ArrayList<Triple<DominanceZone, TacticalPosture, List<TacticalTarget>>>()
        Postures.processZones(ctx, moves = { z, posture -> seen.add(Triple(z, posture, ctx.currentTargets)) })
        val ours = seen.single { it.first == zone }
        assertEquals(TacticalPosture.Hedgehog, ours.second)
        assertEquals("the zone's targets", ctx.targets.forZone(zone), ours.third)
        assertTrue(ours.third.any { it.type == TacticalTargetType.City })
        assertEquals("all of them again afterwards", ctx.targets.all, ctx.currentTargets)
    }

    @Test
    fun `the zones are asked for their emergency purchases first, all of them, in the order of their value`() {
        val city = enemyCity()
        unit("Warrior", enemy, 0, 1)
        val ctx = context()
        val before = ArrayList<DominanceZone>()
        Postures.processZones(ctx, beforeZone = { before.add(it) }, moves = { _, _ -> })
        assertEquals(ctx.map.zones, before)
        assertTrue(city.getCenterTile() in before.mapNotNull { it.closestCity?.getCenterTile() })
    }

    @Test
    fun `a zone runs the move of its posture with the enemy units in it as targets, whatever the strengths of the zones say`() {
        val city = game.addCity(civ, tile(0, 0))
        setPosture(city, TacticalPosture.Counterattack)
        // An enemy unit near the city and none of our units: a target, though the strengths of the zones may not count it (the barbarians'
        // never do)
        val intruder = unit("Warrior", enemy, 0, 2)
        val ctx = context()
        val seen = ArrayList<List<TacticalTarget>>()
        Postures.processZones(ctx, moves = { _, posture -> if (posture == TacticalPosture.Counterattack) seen.add(ctx.currentTargets) })
        assertEquals(1, seen.size)
        assertTrue(seen.single().any { it.unit == intruder })
    }

    // endregion
    // region The postures

    @Test
    fun `sit and bombard shoots the city from safe tiles and closes in`() {
        val city = enemyCity()
        val archer = unit("Archer", civ, 0, -2)
        val swordsman = unit("Swordsman", civ, 0, -5)
        setPosture(city, TacticalPosture.SitAndBombard)
        val ctx = context()
        val before = city.health
        Postures.processZones(ctx)
        assertTrue("the archer shot at the city", city.health < before)
        assertEquals(1, archer.attacksThisTurn)
        assertTrue("and the army closes in", swordsman in ctx.cache.processedUnits && swordsman.getTile().aerialDistanceTo(city.getCenterTile()) < 5)
    }

    @Test
    fun `attrit from range bombards a unit from where it cannot reach the shooter`() {
        val warrior = unit("Warrior", enemy, 0, 0) // reaches hexes within 2
        val artillery = unit("Artillery", civ, 0, -3) // range 3
        state.postures = arrayListOf(ZonePosture().also { it.posture = TacticalPosture.AttritFromRange }) // unclaimed land
        val ctx = context()
        assertFalse("out of the warrior's reach", ctx.map.isSubjectToAttack(artillery.getTile()))
        Postures.processZones(ctx)
        assertTrue("the warrior was shot", warrior.health < 100)
        assertEquals(tile(0, -3), artillery.getTile())
    }

    @Test
    fun `exploit flanks fills the hexes around an enemy unit and attacks it`() {
        val warrior = unit("Warrior", enemy, 0, 0)
        val swordsmen = listOf(unit("Swordsman", civ, 0, -2), unit("Swordsman", civ, -1, -2), unit("Swordsman", civ, 1, -2), unit("Swordsman", civ, 2, -2))
        val ctx = context()
        Postures.exploitFlanksMoves(ctx)
        assertTrue("the unit was attacked", warrior.isDestroyed || warrior.health < 100)
        assertTrue(swordsmen.any { it.attacksThisTurn > 0 })
        assertTrue("the units stood around it", swordsmen.count { it.getTile().aerialDistanceTo(tile(0, 0)) <= 1 } >= 2)
    }

    @Test
    fun `steamroll kills the defenders it can, then goes for the city`() {
        val city = enemyCity(0, 3)
        val defender = unit("Warrior", enemy, 0, 1)
        defender.health = 30
        val swordsmen = listOf(unit("Swordsman", civ, 0, -1), unit("Swordsman", civ, -1, -1), unit("Swordsman", civ, 1, 0))
        val archer = unit("Archer", civ, 0, -2)
        setPosture(city, TacticalPosture.Steamroll)
        val ctx = context()
        Postures.processZones(ctx)
        assertTrue("the defender is dead", defender.isDestroyed)
        assertTrue("the army has moved on", (swordsmen + archer).any { it.getTile().aerialDistanceTo(city.getCenterTile()) < 4 })
    }

    @Test
    fun `a surgical city strike takes a city the units can take`() {
        val city = enemyCity(0, 0)
        city.health = 4
        val swordsmen = listOf(unit("Swordsman", civ, 0, -1), unit("Swordsman", civ, -1, -1))
        setPosture(city, TacticalPosture.SurgicalCityStrike)
        val ctx = context()
        Postures.processZones(ctx)
        assertEquals("the city is ours", civ, city.civ)
        assertTrue(swordsmen.any { it.attacksThisTurn > 0 })
    }

    @Test
    fun `a counterattack goes after the invader in our land, the stronger threats first`() {
        val city = game.addCity(civ, tile(0, 0))
        val invader = unit("Warrior", enemy, 0, 2)
        invader.health = 40
        val defender = unit("Swordsman", civ, 0, 1)
        state.postures = arrayListOf(ZonePosture().also {
            it.ownerId = civ.civName
            it.cityId = city.id
            it.posture = TacticalPosture.Counterattack
        })
        val ctx = context()
        Postures.processZones(ctx)
        assertTrue("the invader was attacked", invader.isDestroyed || invader.health < 40)
        assertTrue(defender.attacksThisTurn > 0)
    }

    @Test
    fun `a hedgehog kills what it can, then asks for the reinforcements of its zone`() {
        val city = game.addCity(civ, tile(0, 0))
        unit("Warrior", enemy, 0, 5)
        val archer = unit("Archer", civ, 0, -5)
        val swordsman = unit("Swordsman", civ, 1, -5)
        state.postures = arrayListOf(ZonePosture().also {
            it.ownerId = civ.civName
            it.cityId = city.id
            it.posture = TacticalPosture.Hedgehog
        })
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!
        Postures.plotPostureMoves(ctx, zone, TacticalPosture.Hedgehog)
        assertTrue("they came closer", listOf(archer, swordsman).all { it.getTile().aerialDistanceTo(city.getCenterTile()) < 5 })
        assertFalse(archer.getTile() == swordsman.getTile())
    }

    @Test
    fun `shore bombardment shoots the enemy's city from the sea`() {
        val city = enemyCity(0, 0)
        for (y in -3..-1) for (x in -2..2) game.setTileTerrain(HexCoord(x, y), "Ocean")
        val frigate = unit("Frigate", civ, 0, -2)
        setPosture(city, TacticalPosture.ShoreBombardment, isWater = true)
        val ctx = context()
        val before = city.health
        Postures.processZones(ctx)
        assertTrue("the ship shot", city.health < before)
        assertEquals(1, frigate.attacksThisTurn)
    }

    // endregion
    // region PlotCloseOnTarget (the temporary zones')

    @Test
    fun `closing in on an operation's city moves in only on a city we see unless we are outmatched by more than half`() {
        val city = enemyCity(0, 0)
        val swordsman = unit("Swordsman", civ, 0, -4)
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!
        zone.friendlyMeleeStrength = 0
        zone.friendlyRangedStrength = 100
        zone.enemyMeleeStrength = 0
        zone.enemyRangedStrength = 1000 // less than half of the enemy's: stay away
        Postures.plotCloseOnTarget(ctx, zone)
        assertEquals(tile(0, -4), swordsman.getTile())

        zone.enemyRangedStrength = 199 // more than half
        Postures.plotCloseOnTarget(ctx, zone)
        assertTrue(swordsman.getTile().aerialDistanceTo(city.getCenterTile()) < 4)
    }

    @Test
    fun `no posture of an ordinary zone closes in, the units come in the second pass`() {
        val city = game.addCity(enemy, tile(0, 3)) // not an operation's target
        val swordsman = unit("Swordsman", civ, 0, -4)
        setPosture(city, TacticalPosture.Steamroll)
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!
        Postures.plotPostureMoves(ctx, zone, TacticalPosture.Steamroll)
        Postures.plotPostureMoves(ctx, zone, TacticalPosture.SurgicalCityStrike)
        assertEquals(tile(0, -4), swordsman.getTile())
    }

    // endregion
}
