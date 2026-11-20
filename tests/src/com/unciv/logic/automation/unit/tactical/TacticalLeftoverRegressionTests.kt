package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.BarbarianAutomation
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.stats.Stats
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
 *  What changed in the tactical AI's turn and the barbarians' (Brave New World data), through the entry points that exist since before the change, so
 *  that each of these fails on the code they replaced: the recruiting of every unit (CvTacticalAI::RecruitUnits), the third round of
 *  PositionUnitsAroundTarget, the city fallback shot of ReviewUnassignedUnits, the air sweep that belongs to the attack plans and not to a move of
 *  its own, PlotBlockadeMoves, ExecuteBarbarianTheft and ExecuteBarbarianRoaming.
 */
@RunWith(GdxTestRunner::class)
class TacticalLeftoverRegressionTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun atWar(radius: Int, enemyUniques: Array<String> = arrayOf("Provides [100] [Iron]", "Provides [100] [Horses]")): Pair<Civilization, Civilization> {
        game.makeHexagonalMap(radius)
        val civ = game.addCiv()
        val enemy = game.addCiv(*enemyUniques)
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        return civ to enemy
    }

    private fun see(civ: Civilization) {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun context(civ: Civilization, vararg units: MapUnit): TacticalContext {
        see(civ)
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList(), TacticalTargets.find(civ, map, danger))
    }

    // region RecruitUnits

    @Test
    fun `a hurt unit with no enemy anywhere near is healed by the tactical turn, which no longer waits for danger`() {
        val (civ, enemy) = atWar(16)
        val hurt = game.addUnit("Warrior", civ, game.tileMap[0, 0])
        hurt.health = 60
        game.addUnit("Warrior", enemy, game.tileMap[0, 14]) // beyond the recruit range of 8
        val cache = AutomationCache()
        see(civ)
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache)
        assertTrue("recruited, so the heal move commands it", hurt in cache.processedUnits)
        assertEquals(game.tileMap[0, 0], hurt.getTile())
        assertTrue(hurt.isFortified())
    }

    // endregion
    // region The third round of PositionUnitsAroundTarget

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    /** Our army of three gathers 3 tiles from our city at (0, 0) for the attack on the enemy's at (8, 0) */
    private fun armyScenario(): Triple<Civilization, Civilization, AiOperation> {
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        val civ = neutralCiv()
        val enemy = neutralCiv()
        val city = game.addCity(civ, tile(0, 0))
        val enemyCity = game.addCity(enemy, tile(8, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        val ring = tile(0, 0).getTilesAtDistance(3).filter { it.militaryUnit == null }.toList()
        game.addUnit("Archer", civ, ring[0])
        game.addUnit("Warrior", civ, ring[3])
        game.addUnit("Warrior", civ, ring[6])
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        return Triple(civ, enemy, operation)
    }

    @Test
    fun `an army unit in more danger than it heals is moved to a plot with less`() {
        val (civ, enemy, operation) = armyScenario()
        val unit = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second }.first()
        val start = unit.getTile()
        val target = start.neighbors.first { it.militaryUnit == null }
        // an archer of the enemy, 3 tiles from the unit: within its move and its shot
        game.addUnit("Archer", enemy, start.getTilesAtDistance(3).first { it.militaryUnit == null && it.aerialDistanceTo(tile(0, 0)) > 3 })
        val ctx = context(civ, unit)
        val danger = civ.ai.unitDanger
        assertTrue("the scenario puts the unit in danger", danger.get(unit) > TacticalSafety.actualHealRate(unit))
        val before = danger.get(unit)
        ReinforcementMoves.positionUnitsAroundTarget(ctx, listOf(unit), target)
        assertTrue(unit in ctx.cache.processedUnits)
        assertFalse(unit.hasMovement())
        assertTrue("less danger where it ended: ${danger.get(unit)} against $before", danger.get(unit) < before || unit.getTile() != start)
    }

    // endregion
    // region The city's fallback shot

    @Test
    fun `the review of the unassigned units ends with the city's shot at the unit in range`() {
        val (civ, enemy) = atWar(10)
        val city = game.addCity(civ, tile(0, 0))
        val victim = game.addUnit("Warrior", enemy, tile(2, 0))
        see(civ)
        val ctx = TacticalContext(civ, civ.ai.tactical.map, civ.ai.plotDanger, AutomationCache(), emptyList(), TacticalTargets.none())
        assertFalse(city.attackedThisTurn)
        TacticalAi.reviewUnassignedUnits(ctx)
        assertTrue("the city shot", city.attackedThisTurn)
        assertTrue("and hurt the unit", victim.isDestroyed || victim.health < 100)
    }

    @Test
    fun `a city with nothing in range is left alone by the review`() {
        val (civ, enemy) = atWar(10)
        val city = game.addCity(civ, tile(0, 0))
        game.addUnit("Warrior", enemy, tile(5, 0))
        see(civ)
        TacticalAi.reviewUnassignedUnits(TacticalContext(civ, civ.ai.tactical.map, civ.ai.plotDanger, AutomationCache(), emptyList(), TacticalTargets.none()))
        assertFalse(city.attackedThisTurn)
    }

    // endregion
    // region The air sweep

    @Test
    fun `there is no stand-alone sweep, a fighter next to a city of the enemy that holds a fighter is not sent against it`() {
        val (civ, enemy) = atWar(24)
        game.addCity(civ, tile(0, 0))
        val forward = game.addCity(civ, tile(6, 0))
        game.addCity(enemy, tile(10, 0))
        val fighter = game.addUnit("Fighter", civ, forward.getCenterTile())
        // the city's garrison, so that we dominate its zone, which was the condition of Brave New World's PlotAirSweepMoves
        for (tile in forward.getCenterTile().neighbors.take(2)) game.addUnit("Swordsman", civ, tile)
        game.addUnit("Fighter", enemy, tile(10, 0))
        val cache = AutomationCache()
        see(civ)
        assertEquals(TacticalDominance.Friendly, civ.ai.tactical.map.zoneByCity(forward, false)!!.dominance)
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache)
        assertEquals("the sweep belongs to the attack plans", 0, fighter.attacksThisTurn)
    }

    // endregion
    // region Blockades

    @Test
    fun `a ship that can get to a blockade point of the enemy's water goes there in the tactical turn`() {
        game.makeHexagonalMap(14)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Coast")
        for ((center, radius) in listOf(tile(0, 0) to 2, tile(-11, 0) to 1))
            for (tile in center.getTilesInDistance(radius)) game.setTileTerrain(tile.position, "Grassland")
        val civ = game.addCiv()
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        game.addCity(civ, tile(-11, 0))
        val enemyCity = game.addCity(enemy, tile(0, 0))
        val point = tile(3, 0)
        enemyCity.expansion.takeOwnership(point)
        val ship = game.addUnit("Trireme", civ, tile(9, 0))
        val cache = AutomationCache()
        see(civ)
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache)
        assertTrue("sailed towards the point", ship.getTile().aerialDistanceTo(point) < 6)
        assertTrue(ship in cache.processedUnits)
    }

    // endregion
    // region The barbarians

    private fun barbarianGame(): Pair<Civilization, Civilization> {
        game.makeHexagonalMap(12)
        val barbarians = game.addBarbarianCiv()
        barbarians.ai.setTransients(barbarians)
        game.gameInfo.barbarians.gameInfo = game.gameInfo
        val civ = game.addCiv()
        game.setDifficulty("Prince")
        for (tile in game.tileMap.values) tile.setExplored(barbarians, true)
        barbarians.viewableTiles = game.tileMap.values.toSet()
        return barbarians to civ
    }

    @Test
    fun `a barbarian next to a city without a garrison robs it before it attacks`() {
        val (barbarians, civ) = barbarianGame()
        val city = game.addCity(civ, tile(0, 0))
        city.cityStats.currentCityStats = Stats(production = 10f, food = 10f, gold = 10f, science = 10f, culture = 10f)
        civ.tech.techsToResearch.add("Pottery")
        city.cityConstructions.setCurrentConstruction("Factory")
        val thief = game.addUnit("Warrior", barbarians, tile(1, 0))
        fun holdings() = listOf(civ.gold, civ.policies.storedCulture, civ.tech.researchOfTech("Pottery"), city.population.foodStored,
            city.cityConstructions.getWorkDone("Factory"))
        var robbed = 0
        for (turn in 1..8) {
            game.gameInfo.turns = turn
            civ.addGold(1000 - civ.gold)
            civ.policies.storedCulture = 1000
            civ.tech.techsInProgress["Pottery"] = 1000
            city.population.foodStored = 200
            city.cityConstructions.inProgressConstructions["Factory"] = 60
            city.health = 200
            thief.health = 100
            thief.attacksThisTurn = 0
            thief.currentMovement = thief.getMaxMovement().toFloat()
            barbarians.viewableTiles = game.tileMap.values.toSet()
            barbarians.ai.invalidateTurnCaches()
            val before = holdings()
            BarbarianAutomation(barbarians).automate()
            if (holdings() != before) {
                robbed++
                assertEquals("it did not attack the city", 200, city.health)
                assertTrue("and paid for it", thief.health in 70..99)
            }
        }
        assertTrue("robbed on $robbed of 8 turns", robbed > 0)
    }

    @Test
    fun `a barbarian that finds no target keeps its movement for the moves to safety, roaming does not end its turn`() {
        val (barbarians, _) = barbarianGame()
        val lone = game.addUnit("Warrior", barbarians, tile(0, 0))
        barbarians.ai.invalidateTurnCaches()
        BarbarianAutomation(barbarians).automate()
        assertNotEquals(0f, lone.currentMovement)
        assertEquals(tile(0, 0), lone.getTile())
    }

    // endregion
}
