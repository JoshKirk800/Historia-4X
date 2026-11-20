package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.BarbarianAutomation
import com.unciv.logic.automation.civilization.BarbarianEncampment
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The barbarian tactical AI (CvTacticalAI as it runs for the barbarian player) with Brave New World data: the target ranges by difficulty
 *  (HandicapInfos.BarbarianLandTargetRange / BarbarianSeaTargetRange), the fixed order of AssignBarbarianMoves, the roaming of
 *  ExecuteBarbarianRoaming on land and sea (FindBestBarbarianLandTarget, FindBestBarbarianSeaTarget, FindNearbyTarget), camp defense,
 *  captured civilians, and a whole turn.
 */
@RunWith(BaseTestRunner::class)
class BarbarianMovesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var barbarians: Civilization
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(20)
        barbarians = game.addBarbarianCiv()
        barbarians.ai.setTransients(barbarians)
        game.gameInfo.barbarians.gameInfo = game.gameInfo
        civ = game.addCiv()
        game.setDifficulty("Prince")
        for (tile in game.tileMap.values) tile.setExplored(barbarians, true)
        barbarians.viewableTiles = game.tileMap.values.toSet()
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))

    private fun context(vararg units: MapUnit): TacticalContext {
        // putting units on the map recomputes what a civilization sees: the barbarians see everything for these tests
        barbarians.viewableTiles = game.tileMap.values.toSet()
        civ.viewableTiles = game.tileMap.values.toSet()
        barbarians.ai.invalidateTurnCaches()
        val danger = barbarians.ai.plotDanger
        return TacticalContext(barbarians, null, danger, AutomationCache(), units.toList(), TacticalTargets.find(barbarians, null))
    }

    private fun makeWater(xRange: IntRange, yRange: IntRange) {
        for (x in xRange) for (y in yRange) game.tileMap.getOrNull(x, y)?.let { game.setTileTerrain(it.position, "Coast") }
    }

    // region Ranges and priorities

    @Test
    fun `the target ranges are the handicap's land and sea target range of the game's difficulty`() {
        val expected = mapOf("Settler" to (2 to 4), "Chieftain" to (3 to 6), "Warlord" to (4 to 8), "Prince" to (5 to 10), "King" to (5 to 12),
            "Emperor" to (6 to 15), "Immortal" to (7 to 18), "Deity" to (8 to 20))
        for ((difficulty, ranges) in expected) {
            game.setDifficulty(difficulty)
            assertEquals(difficulty, ranges.first, BarbarianMoves.landRange(barbarians))
            assertEquals(difficulty, ranges.second, BarbarianMoves.seaRange(barbarians))
        }
    }

    /** What [BarbarianAutomation.automate] logged for the barbarians, in order */
    private fun logOfTurn(): List<String> {
        val log = ArrayList<String>()
        // putting units on the map recomputes what a civilization sees
        barbarians.viewableTiles = game.tileMap.values.toSet()
        barbarians.ai.invalidateTurnCaches()
        AiDecisionLog.sink = { civilization, message -> if (civilization == barbarians) log.add(message) }
        try {
            BarbarianAutomation(barbarians).automate()
        } finally {
            AiDecisionLog.sink = null
        }
        return log
    }

    @Test
    fun `the moves run in Vox Populi's fixed order, capturing civilians before the roaming of the units that are left`() {
        game.addUnit("Worker", civ, tile(-3))
        game.addUnit("Warrior", barbarians, tile(-2)) // next to the worker
        game.addUnit("Archer", barbarians, tile(14)) // nothing to attack: it roams towards the warrior of ours, 10 tiles away
        game.addUnit("Warrior", civ, tile(4))
        val log = logOfTurn()
        val capture = log.indexOfFirst { it.contains("attacking") && it.contains("Worker") }
        val roam = log.indexOfFirst { it.contains("roaming: Archer") }
        assertTrue("the capture logged: $log", capture >= 0)
        assertTrue("the roaming logged: $log", roam >= 0)
        assertTrue("the capture, then the roaming: $log", capture < roam)
    }

    @Test
    fun `barbarians do not pillage citadels, only resources and improvements`() {
        val owner = game.addCity(civ, tile(3))
        val citadel = tile(1)
        owner.expansion.takeOwnership(citadel)
        citadel.setImprovementBasic("Citadel")
        val raider = game.addUnit("Warrior", barbarians, tile(0))
        assertEquals("the citadel is the civilization's land", civ, citadel.getOwner())
        BarbarianAutomation(barbarians).automate()
        assertFalse("Vox Populi's barbarians pillage the resource and the improvement targets only", citadel.isPillaged())
        assertEquals(0, raider.attacksThisTurn)
    }
    // endregion

    // region Roaming on land

    @Test
    fun `a barbarian hunts the nearest target it reaches within its land target range in turns, and ignores one beyond`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        val near = game.addUnit("Archer", civ, tile(4))
        game.addUnit("Archer", civ, tile(-7))
        assertEquals("the nearest first", tile(4), BarbarianMoves.findBestLandTarget(context(hunter), hunter))
        near.removeFromTile()
        assertEquals("the one 7 tiles away is 3 turns of 2 moves", tile(-7), BarbarianMoves.findBestLandTarget(context(hunter), hunter))
    }

    @Test
    fun `the land target range is in turns, a Warrior of 2 moves gets next to a target 13 tiles away in 5 turns, not one 14 tiles away`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        val prey = game.addUnit("Archer", civ, tile(14)) // 13 steps to the tile next to it: 7 turns of 2, the first ends where it stands
        assertNull(BarbarianMoves.findBestLandTarget(context(hunter), hunter))
        prey.removeFromTile()
        game.addUnit("Archer", civ, tile(13)) // 12 steps: 6 turns, the last one being the sixth: 5 turns after this one
        assertEquals(tile(13), BarbarianMoves.findBestLandTarget(context(hunter), hunter))
    }

    @Test
    fun `how far a barbarian looks depends on the difficulty`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        game.addUnit("Archer", civ, tile(12)) // 11 steps: 6 turns of 2 moves, 5 after this one
        game.setDifficulty("Warlord") // 4 turns
        assertNull(BarbarianMoves.findBestLandTarget(context(hunter), hunter))
        game.setDifficulty("Prince") // 5 turns
        assertEquals(tile(12), BarbarianMoves.findBestLandTarget(context(hunter), hunter))
    }

    @Test
    fun `a unit that cannot defend itself goes back to the nearest camp, as far as 23 turns away`() {
        tile(9).setImprovementBasic(Constants.barbarianEncampment)
        tile(-3).setImprovementBasic(Constants.barbarianEncampment)
        val captive = game.addUnit("Worker", barbarians, tile(0))
        game.addUnit("Archer", civ, tile(5)) // an offensive target, which is no business of a worker
        assertEquals(tile(-3), BarbarianMoves.findBestLandTarget(context(captive), captive))
    }

    @Test
    fun `a combat unit with no offensive target explores, then goes back to a camp`() {
        val unit = game.addUnit("Warrior", barbarians, tile(0))
        tile(6).setImprovementBasic(Constants.barbarianEncampment)
        // everything is revealed, visible and nobody's: nothing to explore
        assertEquals(tile(6), BarbarianMoves.findBestLandTarget(context(unit), unit))
        // a tile nobody has seen is worth exploring: a reachable plot next to it gets 3 for it, and the unseen plot itself is no candidate
        val unseen = tile(2)
        barbarians.viewableTiles = game.tileMap.values.toSet()
        for (tile in game.tileMap.values) tile.setExplored(barbarians, tile.position != unseen.position)
        val ctx = TacticalContext(barbarians, null, barbarians.ai.plotDanger, AutomationCache(), listOf(unit), TacticalTargets.find(barbarians, null, barbarians.ai.plotDanger))
        val target = BarbarianMoves.findExploreTarget(ctx, unit)
        assertNotNull(target)
        assertEquals(1, target!!.aerialDistanceTo(unseen))
    }

    @Test
    fun `a roaming barbarian ends its turn next to a defended target when it has no movement left`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        game.addUnit("Archer", civ, tile(8))
        val ctx = context(hunter)
        BarbarianMoves.plotRoamingMoves(ctx)
        assertFalse(hunter.hasMovement())
        assertTrue(hunter in ctx.cache.processedUnits)
        assertEquals("2 moves towards it", 6, hunter.getTile().aerialDistanceTo(tile(8)))
    }

    @Test
    fun `a roaming unit that is out of movement is done, one with no target is left alone for the moves to safety`() {
        val hunter = game.addUnit("Horseman", barbarians, tile(0))
        game.addUnit("Archer", civ, tile(14))
        hunter.currentMovement = 1f // it can only step once
        val ctx = context(hunter)
        BarbarianMoves.plotRoamingMoves(ctx)
        assertEquals("one step along the way", 13, hunter.getTile().aerialDistanceTo(tile(14)))
        assertTrue("out of movement: commanded", hunter in ctx.cache.processedUnits)
        val standing = game.addUnit("Warrior", barbarians, tile(-10)) // 24 tiles from the archer, beyond the 15 of its range
        val ctx2 = context(standing)
        BarbarianMoves.plotRoamingMoves(ctx2)
        assertFalse("no target, nothing done", standing in ctx2.cache.processedUnits)
        assertTrue(standing.hasMovement())
    }

    @Test
    fun `a barbarian in a camp or a city stays and is done`() {
        val camp = tile(5)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        game.addUnit("Archer", civ, tile(12))
        val sitting = game.addUnit("Warrior", barbarians, camp)
        val ctx = context(sitting)
        BarbarianMoves.plotRoamingMoves(ctx)
        assertEquals(camp, sitting.getTile())
        assertTrue(sitting in ctx.cache.processedUnits)
    }

    @Test
    fun `an unprotected improvement of the enemy is entered and pillaged when the unit has movement left`() {
        val owner = game.addCity(civ, tile(8))
        val farm = tile(2)
        owner.expansion.takeOwnership(farm)
        farm.setImprovementBasic("Farm")
        val raider = game.addUnit("Horseman", barbarians, tile(0))
        assertEquals(civ, farm.getOwner())
        val ctx = context(raider)
        assertEquals(farm, BarbarianMoves.findBestLandTarget(ctx, raider))
        BarbarianMoves.plotRoamingMoves(ctx)
        assertEquals(farm, raider.getTile())
        assertTrue("2 of its 4 moves left for the pillage", farm.isPillaged())
    }

    // endregion

    // region Camps

    private fun camp(at: Tile): Tile {
        at.setImprovementBasic(Constants.barbarianEncampment)
        game.gameInfo.barbarians.encampments.add(BarbarianEncampment(at.position))
        return at
    }

    @Test
    fun `an enemy unit that can reach a barbarian camp next turn is a high priority target, one that cannot is a low one`() {
        camp(tile(0))
        val threat = game.addUnit("Warrior", civ, tile(2))
        val distant = game.addUnit("Warrior", civ, tile(9))
        val ctx = context()
        assertEquals(TacticalTargetType.HighPriorityUnit, ctx.targets.all.first { it.unit == threat }.type)
        assertEquals(TacticalTargetType.LowPriorityUnit, ctx.targets.all.first { it.unit == distant }.type)
    }

    @Test
    fun `an archer that can shoot a camp from where it stands is marked a high priority target, then medium as every ranged unit is`() {
        camp(tile(0))
        val archer = game.addUnit("Archer", civ, tile(2))
        // the original makes every ranged unit a medium priority target, a high one too (IdentifyPriorityTargetsByType)
        assertEquals(TacticalTargetType.MediumPriorityUnit, context().targets.all.first { it.unit == archer }.type)
    }

    @Test
    fun `an empty camp gets the nearest unit within five turns, one too far away stays`() {
        val camp = tile(3)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        val archer = game.addUnit("Archer", barbarians, tile(2))
        val swordsman = game.addUnit("Swordsman", barbarians, tile(1))
        val far = game.addUnit("Swordsman", barbarians, tile(-4))
        val ctx = context(archer, swordsman, far)
        BarbarianMoves.plotCampDefenseMoves(ctx)
        assertTrue(camp.militaryUnit == archer || camp.militaryUnit == swordsman)
        assertEquals(tile(-4), far.getTile())
    }

    @Test
    fun `a camp defender attacks what is next to the camp without leaving it, and is done`() {
        val camp = tile(0)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        val defender = game.addUnit("Swordsman", barbarians, camp)
        val victim = game.addUnit("Warrior", civ, tile(1))
        val ctx = context(defender)
        BarbarianMoves.plotCampDefenseMoves(ctx)
        assertSame(defender, camp.militaryUnit)
        assertTrue(victim.isDestroyed || victim.health < 100)
        assertTrue(defender in ctx.cache.processedUnits)
    }

    // endregion

    // region Sea raids

    @Test
    fun `a barbarian ship picks the nearest enemy ship it reaches within the sea target range`() {
        makeWater(-18..18, -3..3)
        val raider = game.addUnit("Trireme", barbarians, tile(-9))
        val victim = game.addUnit("Trireme", civ, tile(-2))
        game.addUnit("Trireme", civ, tile(15))
        assertEquals(victim.getTile(), BarbarianMoves.findBestSeaTarget(context(raider), raider))
    }

    @Test
    fun `with no ship to pick on a barbarian ship sails to the water beside a camp other than the nearest`() {
        makeWater(-18..18, -3..3)
        tile(-9, 4).setImprovementBasic(Constants.barbarianEncampment)
        tile(9, 4).setImprovementBasic(Constants.barbarianEncampment)
        val raider = game.addUnit("Trireme", barbarians, tile(-8))
        val target = BarbarianMoves.findBestSeaTarget(context(raider), raider)
        assertNotNull(target)
        assertTrue("next to the second camp", target!!.aerialDistanceTo(tile(9, 4)) == 1 && target.isWater)
    }

    @Test
    fun `a barbarian ship sails towards its target, attacks what it can reach and is done`() {
        makeWater(-18..18, -3..3)
        val victim = game.addUnit("Trireme", civ, tile(2))
        val raider = game.addUnit("Trireme", barbarians, tile(-6))
        val ctx = context(raider)
        BarbarianMoves.plotRoamingMoves(ctx)
        assertTrue(raider.getTile() != tile(-6))
        assertTrue("closer to the victim", raider.getTile().aerialDistanceTo(victim.getTile()) < 8)
        assertTrue(raider in ctx.cache.processedUnits)
    }
    // endregion

    // region Captured civilians

    private fun captive(at: Tile): MapUnit {
        val worker = game.addUnit("Worker", barbarians, at)
        worker.originalOwner = civ.civID
        return worker
    }

    @Test
    fun `a captured civilian walks to the nearest camp alone, and one already on a camp stays`() {
        tile(8).setImprovementBasic(Constants.barbarianEncampment)
        val walking = captive(tile(0))
        BarbarianMoves.plotRoamingMoves(context(walking))
        assertTrue("two moves along the way", walking.getTile().aerialDistanceTo(tile(8)) < 8)
        val settled = captive(tile(8))
        val ctx = context(settled)
        BarbarianMoves.plotRoamingMoves(ctx)
        assertEquals(tile(8), settled.getTile())
        assertTrue(settled in ctx.cache.processedUnits)
    }
    // endregion

    // region A whole turn

    @Test
    fun `a barbarian next to a worker of ours captures it`() {
        val raider = game.addUnit("Warrior", barbarians, tile(0))
        val worker = game.addUnit("Worker", civ, tile(1))
        BarbarianAutomation(barbarians).automate()
        assertEquals(barbarians, worker.civ)
        assertEquals(tile(1), raider.getTile())
    }

    @Test
    fun `a barbarian next to a unit of ours that it can kill attacks it`() {
        // The attacks on units come before everything but camp defense and the theft (PlotBarbarianAttacks, AL_BRAVEHEART)
        val raider = game.addUnit("Swordsman", barbarians, tile(0))
        val victim = game.addUnit("Warrior", civ, tile(1))
        victim.health = 30
        BarbarianAutomation(barbarians).automate()
        assertTrue("the victim lost hit points or died", victim.isDestroyed || victim.health < 30)
        assertTrue(raider.attacksThisTurn > 0)
    }
    // endregion
}
