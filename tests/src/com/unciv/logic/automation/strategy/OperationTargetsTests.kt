package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Where Vox Populi's operations go and where they muster (Release-5.4.8): CvMilitaryAI::RequestBullyingOperation and RequestPillageAttack,
 *  CvAIOperationPillageEnemy::FindBestTarget, CvAIOperationMilitary::Init (the muster city of CvPlayer::GetClosestCity within 23 plots, the
 *  target plot as the army's goal), CvPlayer::GetClosestCityByPathLength and CvCity::HasSharedAreaWith. BNW rules and data, desert land on a
 *  hexagonal map of radius 30: our city at (0, 0), the enemy's at (8, 0), whose zone on the tactical map borders ours.
 */
@RunWith(GdxTestRunner::class)
class OperationTargetsTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City
    private lateinit var luxury: TileResource
    private lateinit var strategic: TileResource
    private lateinit var bonus: TileResource

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(30)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, tile(0))
        enemyCity = game.addCity(enemy, tile(8))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        luxury = game.createResource().apply { resourceType = ResourceType.Luxury }
        strategic = game.createResource().apply { resourceType = ResourceType.Strategic }
        bonus = game.createResource().apply { resourceType = ResourceType.Bonus }
        see()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))

    /** Putting units and cities on the map changes what we see and what the tactical map holds: afterwards we see all of it, and the maps are made again */
    private fun see() {
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    /** A military unit of ours: on the first free tile around the capital unless [at] is given (one military unit per tile) */
    private fun unit(name: String, at: HexCoord? = null): MapUnit {
        val tile = at?.let { game.getTile(it) } ?: tile(0).getTilesInDistance(4).first { it.militaryUnit == null && it.civilianUnit == null }
        return game.addUnit(name, civ, tile)
    }

    /** The units of the basic city attack's required slots: an archer and two warriors */
    private fun attackReserves() {
        unit("Archer")
        repeat(2) { unit("Warrior") }
        see()
    }

    /** [resource] on the next free tile around [city]'s center, worked by the city or [worked] not */
    private fun place(city: City, resource: TileResource, worked: Boolean = true) {
        val tile = city.getCenterTile().neighbors.first { it.resource == null }
        tile.setTileResource(resource.name)
        if (worked) city.workedTiles.add(tile.position) else city.workedTiles.remove(tile.position)
    }

    //region CvAIOperationPillageEnemy::FindBestTarget

    @Test
    fun `a pillage target is a city that works luxury or strategic resources`() {
        assertNull(AiOperationLogic.findPillageTarget(civ, enemy))
        place(enemyCity, luxury, worked = false)
        place(enemyCity, bonus)
        assertNull("a bonus resource and one that is not worked count for nothing", AiOperationLogic.findPillageTarget(civ, enemy))
        place(enemyCity, strategic)
        // the target and our city nearest to it, by path: the muster city
        assertEquals(enemyCity to city, AiOperationLogic.findPillageTarget(civ, enemy))
    }

    @Test
    fun `a city in a zone that none of ours borders is no pillage target, however rich`() {
        val far = game.addCity(enemy, tile(14))
        repeat(2) { place(far, luxury) }
        place(far, strategic)
        place(enemyCity, luxury)
        see()
        val map = civ.ai.tactical.map
        assertTrue(map.zoneByCity(enemyCity, isWater = false)!!.neighbors.any { it.owner == civ })
        assertTrue("it borders the enemy's other zone only", map.zoneByCity(far, isWater = false)!!.neighbors.none { it.owner == civ })
        assertEquals(enemyCity, AiOperationLogic.findPillageTarget(civ, enemy)!!.first)
    }

    /** BNW chose the city by its improved tiles per step of the route; Vox Populi counts the luxury and strategic resources the city works */
    @Test
    fun `the richer city is chosen by its worked resources, not by its improved tiles`() {
        val north = game.addCity(enemy, tile(0, 8))
        see()
        place(enemyCity, luxury)
        for (farmland in enemyCity.getCenterTile().getTilesAtDistance(2).take(5)) farmland.improvement = "Farm"
        place(north, luxury)
        place(north, strategic)
        assertEquals(north, AiOperationLogic.findPillageTarget(civ, enemy)!!.first)
    }

    @Test
    fun `a city on another landmass is no pillage target, embarking is dangerous`() {
        place(enemyCity, luxury)
        assertNotNull(AiOperationLogic.findPillageTarget(civ, enemy))
        for (tile in game.tileMap.values)
            if (tile != city.getCenterTile() && tile != enemyCity.getCenterTile()) game.setTileTerrain(tile.position, "Coast")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        see()
        assertFalse(MilitaryTargets.hasSharedLandArea(city, enemyCity))
        assertNull(AiOperationLogic.findPillageTarget(civ, enemy))
    }

    @Test
    fun `cities share an area when they stand on one landmass or the land around them borders a landmass that borders both`() {
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Clear)
        assertTrue("no continents assigned: one area", MilitaryTargets.hasSharedLandArea(city, enemyCity))
        city.getCenterTile().setContinent(1)
        enemyCity.getCenterTile().setContinent(2)
        val ours = city.getCenterTile().neighbors.toList()
        val theirs = enemyCity.getCenterTile().neighbors.toList()
        for (neighbor in ours) neighbor.setContinent(1)
        for (neighbor in theirs) neighbor.setContinent(2)
        assertFalse(MilitaryTargets.hasSharedLandArea(city, enemyCity))
        // CvPlot::hasSharedAdjacentArea: a third landmass next to both
        for (neighbor in listOf(ours.first(), theirs.first())) { neighbor.clearContinent(); neighbor.setContinent(3) }
        assertTrue(MilitaryTargets.hasSharedLandArea(city, enemyCity))
    }

    @Test
    fun `our closest city by path is not always the closest by plots`() {
        val west = game.addCity(civ, tile(5))
        val east = game.addCity(civ, tile(12))
        // the enemy city is walled in by mountains but on its east side: the way from the west goes round
        for (neighbor in enemyCity.getCenterTile().neighbors) if (neighbor.position != HexCoord(9, 0)) game.setTileTerrain(neighbor.position, "Mountain")
        see()
        assertEquals("by plots", west, civ.cities.minBy { it.getCenterTile().aerialDistanceTo(enemyCity.getCenterTile()) })
        assertEquals(east to 4, MilitaryTargets.closestCityByPathLength(civ, enemy, enemyCity.getCenterTile(), allowSea = false))
    }
    //endregion

    //region The pillage raid (CvMilitaryAI::RequestPillageAttack, CvAIOperationPillageEnemy::Init)

    @Test
    fun `a pillage raid heads for the city itself from our closest city and succeeds within 3 plots of it`() {
        place(enemyCity, luxury)
        val horseman = unit("Horseman", HexCoord(-2, 0))
        see()
        assertTrue(MilitaryOperations.requestPillageAttack(civ, enemy))
        val operation = ops.firstOfType(AiOperationType.PILLAGE_ENEMY)!!
        val army = operation.firstArmy!!
        assertEquals(enemyCity.getCenterTile().position, operation.targetTile)
        assertEquals(city.id, operation.startCityId)
        assertEquals("MUFORMATION_FAST_PILLAGERS", army.formation)
        assertEquals("the army's goal is the target city", enemyCity.getCenterTile().position, army.goalTile)
        assertEquals(army, ops.armyOf(horseman))
        assertEquals("one unit fills the formation and gathers where it stands", OperationState.MOVING_TO_TARGET, operation.state)
        assertTrue(operation.slotsToBuild.isEmpty())
        // one raid at a time
        assertFalse(MilitaryOperations.requestPillageAttack(civ, enemy))
        // 10 plots from the city it has not arrived, 2 plots from it it has: the tactical AI focuses on the city
        assertFalse(AiOperationLogic.checkOnTarget(civ, operation))
        assertFalse(civ.ai.tactical.isInFocusArea(enemyCity.getCenterTile()))
        horseman.removeFromTile()
        horseman.putInTile(tile(6))
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.isInFocusArea(enemyCity.getCenterTile()))
    }

    @Test
    fun `a pillage raid musters at our city nearest to its target by path`() {
        val nearer = game.addCity(civ, tile(4))
        place(enemyCity, luxury)
        unit("Horseman", HexCoord(-2, 0))
        see()
        assertTrue(MilitaryOperations.requestPillageAttack(civ, enemy))
        assertEquals(nearer.id, ops.firstOfType(AiOperationType.PILLAGE_ENEMY)!!.startCityId)
    }

    @Test
    fun `no pillage raid without a target or without the unit at hand`() {
        val horseman = unit("Horseman", HexCoord(-2, 0))
        see()
        assertFalse("no worked resource: no target", MilitaryOperations.requestPillageAttack(civ, enemy))
        assertTrue(ops.operations.isEmpty())
        place(enemyCity, strategic)
        horseman.destroy()
        see()
        assertFalse("no unit to fill the one required slot, and none is built for a raid", MilitaryOperations.requestPillageAttack(civ, enemy))
        assertTrue(ops.operations.isEmpty())
    }
    //endregion

    //region CvMilitaryAI::RequestBullyingOperation

    @Test
    fun `a show of force is the ordinary land attack on the enemy's closest city from ours, with the units at hand, one at a time`() {
        attackReserves()
        assertTrue(MilitaryOperations.requestShowOfForce(civ, enemy))
        val operation = ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)!!
        assertEquals(enemyCity.getCenterTile().position, operation.targetTile)
        assertEquals(city.id, operation.startCityId)
        assertEquals(enemy.civID, operation.enemyCivId)
        assertTrue("no unit is left to build", operation.slotsToBuild.isEmpty())
        // it is the operation a demand looks at (getFirstOffensiveAIOperation) and none is sent beside it
        assertTrue(operation === ops.firstOffensiveOperationAgainst(enemy))
        assertFalse(MilitaryOperations.requestShowOfForce(civ, enemy))
        assertEquals(1, ops.operations.size)
    }

    @Test
    fun `a show of force needs every required unit at hand, and a barbarian is no target`() {
        assertFalse("no units", MilitaryOperations.requestShowOfForce(civ, enemy))
        assertTrue(ops.operations.isEmpty())
        unit("Archer")
        unit("Warrior")
        see()
        assertFalse("one required unit is missing and none is built for it", MilitaryOperations.requestShowOfForce(civ, enemy))
        assertTrue(ops.operations.isEmpty())
        unit("Warrior")
        see()
        assertFalse(MilitaryOperations.requestShowOfForce(civ, game.gameInfo.civilizations.first { it.isBarbarian }))
        assertTrue(ops.operations.isEmpty())
        assertTrue(MilitaryOperations.requestShowOfForce(civ, enemy))
    }

    @Test
    fun `a show of force does not go further than 23 steps`() {
        val far = neutralCiv()
        game.addCity(far, tile(0, 25))
        val near = neutralCiv()
        game.addCity(near, tile(0, 22))
        civ.diplomacyFunctions.makeCivilizationsMeet(far)
        civ.diplomacyFunctions.makeCivilizationsMeet(near)
        attackReserves()
        assertFalse(MilitaryOperations.requestShowOfForce(civ, far))
        assertTrue(ops.operations.isEmpty())
        assertTrue(MilitaryOperations.requestShowOfForce(civ, near))
        assertEquals(tile(0, 22).position, ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)!!.targetTile)
    }

    @Test
    fun `the target is a major's city nearest to ours, a city-state's capital`() {
        val nearer = game.addCity(enemy, tile(0, 6))
        attackReserves()
        assertTrue(MilitaryOperations.requestShowOfForce(civ, enemy))
        val first = ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)!!
        assertEquals(nearer.getCenterTile().position, first.targetTile)
        ops.kill(first, OperationAbortReason.KILLED)

        val state = game.addCiv(cityStateType = "Maritime")
        val capital = game.addCity(state, tile(0, -9))
        game.addCity(state, tile(0, -5))
        civ.diplomacyFunctions.makeCivilizationsMeet(state)
        see()
        assertTrue(MilitaryOperations.requestShowOfForce(civ, state))
        assertEquals(capital.getCenterTile().position, ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)!!.targetTile)
    }

    @Test
    fun `a show of force across the sea is sent by ships alone, and none is sent without a route`() {
        for (tile in game.tileMap.values)
            if (tile != city.getCenterTile() && tile != enemyCity.getCenterTile()) game.setTileTerrain(tile.position, "Coast")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val fleet = listOf(
            game.addUnit("Quinquereme", civ, tile(2, 0)), game.addUnit("Quinquereme", civ, tile(2, 1)),
            game.addUnit("Galleass", civ, tile(3, 0)), game.addUnit("Galleass", civ, tile(3, 1)),
        )
        see()
        assertFalse("our units cannot embark: no route", MilitaryOperations.requestShowOfForce(civ, enemy))
        assertTrue(ops.operations.isEmpty())
        civ.tech.unitsCanEmbark = true
        assertTrue(MilitaryOperations.requestShowOfForce(civ, enemy))
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        // CvAIOperationCityAttackNaval::Init: the goal is the coastal water next to the city
        val goal = game.getTile(operation.targetTile!!)
        assertTrue(goal.isWater && goal.aerialDistanceTo(enemyCity.getCenterTile()) <= 2)
        assertEquals(city.id, operation.startCityId)
        assertNull(ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK))
        assertTrue(fleet.all { ops.armyOf(it) == operation.firstArmy })
    }

    @Test
    fun `a saved small city attack, which Vox Populi has not, is dropped when the game loads`() {
        ops.operations.add(AiOperation().apply { type = AiOperationType.SMALL_CITY_ATTACK })
        ops.setTransients(civ)
        assertTrue(ops.operations.isEmpty())
    }
    //endregion

    //region CvAIOperationMilitary::Init: the muster city and the goal

    @Test
    fun `a city attack with no muster city given musters at our closest city within 23 plots`() {
        val nearer = game.addCity(civ, tile(3))
        attackReserves()
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity)
        assertNotEquals(OperationState.ABORTED, operation.state)
        assertEquals(nearer.id, operation.startCityId)
        assertEquals(nearer.getCenterTile().position, operation.musterTile)
        assertEquals("the army's goal is the target city", enemyCity.getCenterTile().position, operation.firstArmy!!.goalTile)
    }

    @Test
    fun `no city of ours within 23 plots of the target ends the attack, 23 plots is near enough`() {
        val far = game.addCity(enemy, tile(24))
        val near = game.addCity(enemy, tile(23))
        see()
        val tooFar = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, far)
        assertEquals(OperationState.ABORTED, tooFar.state)
        assertEquals(OperationAbortReason.NO_MUSTER, tooFar.abortReason)
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, near)
        assertNotEquals(OperationState.ABORTED, operation.state)
        assertEquals(city.id, operation.startCityId)
    }
    //endregion
}
