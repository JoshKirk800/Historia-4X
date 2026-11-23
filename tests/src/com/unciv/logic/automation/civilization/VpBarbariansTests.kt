package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  VP 5.4.8 CvBarbarians as `barbariansLikeVp` models it: DoCamps (the initial and the periodic schedule, the minimum distances, a recently cleared camp),
 *  ActivateBarbSpawner and DoBarbSpawnerAttacked (the spawn counter), SpawnBarbarianUnits (units per spawn, the cap of barbarians nearby, boats) and the
 *  unchanged Brave New World model with the switch off.
 */
@RunWith(BaseTestRunner::class)
class VpBarbariansTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var barbarians: Civilization

    private fun setSwitch(value: Boolean) {
        val constants = game.ruleset.modOptions.constants
        constants.javaClass.getMethod("setBarbariansLikeVp", Boolean::class.javaPrimitiveType).invoke(constants, value)
    }

    private fun changeConstants(change: (com.unciv.models.ModConstants.BarbarianConstants) -> com.unciv.models.ModConstants.BarbarianConstants) {
        val constants = game.ruleset.modOptions.constants
        constants.barbarianConstants = change(constants.barbarianConstants)
    }

    /** A hexagonal map of grassland with only the barbarians, who know Agriculture (the Warrior), on game turn [turns] (so [turns] - 1 elapsed) */
    private fun setUp(radius: Int, turns: Int, vp: Boolean = true, ocean: Boolean = false): BarbarianManager {
        game.makeHexagonalMap(radius)
        for (tile in game.tileMap.values.toList()) game.setTileTerrain(tile.position, Constants.grassland)
        if (ocean) for (tile in game.tileMap.values.toList()) if (tile.position != game.getTile(0, 0).position) game.setTileTerrain(tile.position, Constants.coast)
        barbarians = game.addBarbarianCiv()
        barbarians.tech.addTechnology("Agriculture", false)
        setSwitch(vp)
        game.gameInfo.turns = turns
        game.gameInfo.barbarians.setTransients(game.gameInfo)
        return game.gameInfo.barbarians
    }

    private fun campTiles(manager: BarbarianManager) = manager.getEncampmentTiles().filter { it.isBarbarianEncampment() }

    private fun barbarianUnits() = barbarians.units.getCivUnits().toList()

    //region schedule

    @Test
    fun `initial turn asks for the percentage of the target, plus the era, and later turns for a camp every second turn`() {
        val camps = BarbarianCampsLikeVp(setUp(3, turns = 3))
        assertEquals(2, camps.elapsedGameTurns)
        assertEquals(10, camps.campsAsked(initialSpawning = true, turnsSinceInitial = 0))
        assertEquals(0, camps.campsAsked(false, 1))
        assertEquals(1, camps.campsAsked(false, 2))
        assertEquals(0, camps.campsAsked(false, 3))
        assertEquals(1, camps.campsAsked(false, 4))
        game.gameInfo.gameParameters.startingEra = game.ruleset.eras.values.first { it.eraNumber == 1 }.name
        assertEquals(15, camps.campsAsked(true, 0))
    }

    @Test
    fun `raging barbarians place a camp every turn`() {
        val camps = BarbarianCampsLikeVp(setUp(3, turns = 3))
        game.gameInfo.gameParameters.ragingBarbarians = true
        assertEquals(1, camps.campsAsked(false, 1))
        assertEquals(1, camps.campsAsked(false, 2))
    }

    @Test
    fun `nothing is placed before the initial turn`() {
        val manager = setUp(6, turns = 2)
        manager.updateEncampments()
        assertTrue(manager.encampments.isEmpty())
    }

    @Test
    fun `the initial turn places a thousandth of the valid tiles for each point of the percentage, with two defenders each`() {
        val manager = setUp(30, turns = 3)
        val tiles = game.tileMap.values.size
        manager.updateEncampments()
        val expected = 10 * tiles / 1000
        assertTrue(expected > 1)
        val camps = campTiles(manager)
        assertEquals(expected, camps.size)
        assertEquals(expected * 2, barbarianUnits().size)
        for (camp in camps) assertEquals(1, camp.getUnits().count())
        for (a in camps) for (b in camps) if (a != b) assertTrue(a.aerialDistanceTo(b) > 4)
    }

    @Test
    fun `a later camp turn places one camp with two defenders and a quiet turn none`() {
        val manager = setUp(8, turns = 5)  // elapsed 4, 2 after the initial turn
        manager.updateEncampments()
        assertEquals(1, campTiles(manager).size)
        assertEquals(2, barbarianUnits().size)
        game.gameInfo.turns = 6  // 3 after the initial turn
        manager.updateEncampments()
        assertEquals(1, campTiles(manager).size)
    }

    @Test
    fun `no camp is placed when the switch is off other than by the Brave New World rules`() {
        val manager = setUp(30, turns = 3, vp = false)
        manager.updateEncampments()
        // The Brave New World model places at most one camp on a turn after the first, and never sets the spawner state of Vox Populi
        assertTrue(campTiles(manager).size <= 1)
        assertTrue(manager.encampments.none { it.attacked })
        assertTrue(barbarianUnits().size <= 1)
    }

    //endregion

    //region distances

    @Test
    fun `a camp keeps more than four tiles from a major capital`() {
        val manager = setUp(5, turns = 5)
        game.addCity(game.addCiv(), game.getTile(0, 0))
        manager.updateEncampments()
        val camps = campTiles(manager)
        assertEquals(1, camps.size)
        assertEquals(5, camps.single().aerialDistanceTo(game.getTile(0, 0)))
    }

    @Test
    fun `a camp keeps more than four tiles from another camp`() {
        val manager = setUp(5, turns = 5)
        manager.createNewCamp(game.getTile(0, 0))
        manager.updateEncampments()
        val camps = campTiles(manager)
        assertEquals(2, camps.size)
        assertTrue(camps.all { it.aerialDistanceTo(game.getTile(0, 0)) == 0 || it.aerialDistanceTo(game.getTile(0, 0)) == 5 })
    }

    @Test
    fun `a camp keeps more than four tiles from a barbarian city`() {
        val manager = setUp(5, turns = 5)
        game.addCity(barbarians, game.getTile(0, 0))
        manager.updateEncampments()
        val camps = campTiles(manager)
        assertEquals(1, camps.size)
        assertEquals(5, camps.single().aerialDistanceTo(game.getTile(0, 0)))
    }

    @Test
    fun `a camp keeps more than two tiles from a recently cleared camp, and the place is free again after fifteen turns`() {
        val manager = setUp(2, turns = 5)
        val center = game.getTile(0, 0)
        val ghost = BarbarianEncampment(center.position)
        ghost.gameInfo = game.gameInfo
        ghost.destroyed = true
        ghost.countdown = 10
        manager.encampments.add(ghost)
        manager.updateEncampments()
        assertTrue(campTiles(manager).isEmpty())
        assertEquals(9, ghost.countdown)

        ghost.countdown = 1  // the last turn of the cooldown: the tile itself waits, the rest may host a camp again
        manager.updateEncampments()
        assertEquals(0, ghost.countdown)
        assertFalse(ghost in manager.encampments)
        val camps = campTiles(manager)
        assertEquals(1, camps.size)
        assertTrue(camps.single() != center)
    }

    @Test
    fun `a cleared camp is remembered for fifteen turns and forgets its units`() {
        val manager = setUp(3, turns = 4)  // 1 after the initial turn: no camp is due
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val entry = manager.encampments.single()
        assertEquals(0, entry.spawnedUnits)
        tile.removeImprovement()
        manager.updateEncampments()
        assertTrue(entry.destroyed)
        assertEquals(-1, entry.spawnedUnits)
        assertEquals(14, entry.countdown)
        repeat(13) { manager.updateEncampments() }
        assertEquals(1, entry.countdown)
        assertTrue(entry in manager.encampments)
        manager.updateEncampments()
        assertFalse(entry in manager.encampments)
    }

    //endregion

    //region the spawn counter

    @Test
    fun `a new camp is seeded with twelve to twenty-one turns, one more for the first activation, and less for later ones`() {
        val manager = setUp(2, turns = 5)
        manager.createNewCamp(game.getTile(0, 0))
        val entry = manager.encampments.single()
        val camps = BarbarianCampsLikeVp(manager)
        assertEquals(0, entry.spawnedUnits)
        assertTrue(entry.countdown in 13..22)  // 12 + 0..9, + 1 as the units counter starts at -1
        camps.activate(entry)
        assertEquals(1, entry.spawnedUnits)
        assertTrue(entry.countdown in 12..21)
        camps.activate(entry)
        assertEquals(2, entry.spawnedUnits)
        assertTrue(entry.countdown in 11..20)  // one less for the unit spawned before
    }

    @Test
    fun `raging barbarians halve the counter`() {
        val manager = setUp(2, turns = 5)
        game.gameInfo.gameParameters.ragingBarbarians = true
        manager.createNewCamp(game.getTile(0, 0))
        assertTrue(manager.encampments.single().countdown in 7..11)  // (12 + 0..9) / 2 + 1
    }

    @Test
    fun `only the first attack of a turn shortens the counter, which the next turn allows again`() {
        val manager = setUp(2, turns = 4)
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val entry = manager.encampments.single()
        entry.countdown = 10
        manager.campAttacked(tile.position)
        assertEquals(5, entry.countdown)
        manager.campAttacked(tile.position)
        assertEquals(5, entry.countdown)
        manager.updateEncampments()
        assertFalse(entry.attacked)
        assertEquals(4, entry.countdown)
        manager.campAttacked(tile.position)
        assertEquals(2, entry.countdown)
    }

    @Test
    fun `with the switch off every attack halves the counter`() {
        val manager = setUp(2, turns = 4, vp = false)
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val entry = manager.encampments.single()
        entry.countdown = 8
        manager.campAttacked(tile.position)
        manager.campAttacked(tile.position)
        assertEquals(2, entry.countdown)
        assertFalse(entry.attacked)
    }

    @Test
    fun `a camp spawns one unit when its counter runs out after turn ten, not before`() {
        val manager = setUp(3, turns = 11)  // elapsed 10 and 8 after the initial turn: no camp is due
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val entry = manager.encampments.single()
        game.gameInfo.turns = 10  // elapsed 9
        entry.countdown = 1
        manager.updateEncampments()
        assertEquals(0, entry.countdown)
        assertTrue(barbarianUnits().isEmpty())
        game.gameInfo.turns = 12  // elapsed 11, 9 after the initial turn
        manager.updateEncampments()
        assertEquals(1, barbarianUnits().size)
        assertEquals(tile, barbarianUnits().single().getTile())
        assertEquals(1, entry.spawnedUnits)
        assertTrue(entry.countdown in 12..21)
    }

    //endregion

    //region units per spawn

    @Test
    fun `a spawner fills its empty camp first and the cap of two barbarians nearby cuts the rest`() {
        val manager = setUp(3, turns = 12)
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val camps = BarbarianCampsLikeVp(manager)

        val first = camps.spawnUnits(tile, 3, BarbarianSpawnReason.FromEncampment)  // the defender, then one next to it: two at most
        assertEquals(2, first.size)
        assertEquals(1, first.count { it.getTile() == tile })
        assertEquals(1, first.count { it.getTile().aerialDistanceTo(tile) == 1 })

        val second = camps.spawnUnits(tile, 5, BarbarianSpawnReason.FromEncampment)  // the camp is defended, one nearby: one more fits
        assertEquals(1, second.size)
        assertEquals(1, second.single().getTile().aerialDistanceTo(tile))

        assertTrue(camps.spawnUnits(tile, 5, BarbarianSpawnReason.FromEncampment).isEmpty())  // two nearby: nothing
        assertEquals(3, barbarianUnits().size)
    }

    @Test
    fun `a new camp has no cap and its defenders are not ranged`() {
        val manager = setUp(3, turns = 12)
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val spawned = BarbarianCampsLikeVp(manager).spawnUnits(tile, 4, BarbarianSpawnReason.NewEncampment)
        assertEquals(4, spawned.size)
        assertFalse(spawned.first { it.getTile() == tile }.baseUnit.isRanged())
    }

    @Test
    fun `the units of a camp come from the constants, at least one for a new camp`() {
        val camps = BarbarianCampsLikeVp(setUp(2, turns = 5))
        assertEquals(2, camps.unitsOfNewCamp(initialSpawning = true))
        assertEquals(2, camps.unitsOfNewCamp(initialSpawning = false))
        assertEquals(1, camps.unitsPerSpawn(city = false))
        assertEquals(1, camps.unitsPerSpawn(city = true))
        changeConstants { it.copy(numUnitsEncampmentCreation = 0, numUnitsPerEncampmentSpawn = 3) }
        assertEquals(1, camps.unitsOfNewCamp(initialSpawning = false))
        assertEquals(3, camps.unitsPerSpawn(city = false))
    }

    /** A camp on a one-tile island that asks for three units on game turn [turn], the barbarians knowing Astronomy (the Caravel) */
    private fun spawnOnIslandCamp(turn: Int): List<com.unciv.logic.map.mapunit.MapUnit> {
        val manager = setUp(1, turns = turn, ocean = true)
        barbarians.tech.addTechnology(game.ruleset.units["Caravel"]!!.requiredTech!!, false)
        val tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val camps = BarbarianCampsLikeVp(manager)
        assertNotNull(camps.chooseUnit(game.tileMap.values.first { it.isWater }, BarbarianPreferredAi.AttackSea, true, emptySet(), Random(1)))
        return camps.spawnUnits(tile, 3, BarbarianSpawnReason.FromEncampment)
    }

    @Test
    fun `no boat is spawned next to a camp before turn twenty`() {
        val spawned = spawnOnIslandCamp(19)
        assertEquals(1, spawned.size)
        assertEquals(0, spawned.count { it.getTile().isWater })
    }

    @Test
    fun `a boat is spawned next to a camp from turn twenty`() {
        val spawned = spawnOnIslandCamp(20)
        assertEquals(2, spawned.size)
        assertEquals(1, spawned.count { it.getTile().isWater })
    }

    @Test
    fun `a unit that needs a resource is only drawn if the resource is near`() {
        val manager = setUp(2, turns = 12)
        barbarians.tech.addTechnology("Iron Working", false)
        val camps = BarbarianCampsLikeVp(manager)
        val tile = game.getTile(0, 0)
        val iron = game.ruleset.units["Swordsman"]!!.requiredResource!!
        val without = camps.scoredUnits(tile, BarbarianPreferredAi.Defense, true, emptySet()).associate { it.first.name to it.second }
        // The barbarians' Brute replaces the Warrior (and needs no tech); 8, +50% for needing no resource
        assertEquals(without.toString(), 12, without["Brute"])
        assertFalse(without.toString(), "Swordsman" in without)
        val with = camps.scoredUnits(tile, BarbarianPreferredAi.Defense, true, setOf(iron)).associate { it.first.name to it.second }
        assertEquals(with.toString(), 12, with["Brute"])
        assertEquals(with.toString(), 14, with["Swordsman"])
    }

    //endregion

    //region off

    @Test
    fun `with the switch off the camp state of Vox Populi is not touched`() {
        val manager = setUp(3, turns = 12, vp = false)
        val tile: Tile = game.getTile(0, 0)
        manager.createNewCamp(tile)
        val entry = manager.encampments.single()
        assertEquals(-1, entry.spawnedUnits)
        assertEquals(0, entry.countdown)
        manager.updateEncampments()
        assertFalse(entry.attacked)
    }

    //endregion
}
