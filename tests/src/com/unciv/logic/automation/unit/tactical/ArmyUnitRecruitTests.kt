package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The units of an army and the tactical AI (BNW data, desert land): CvTacticalAI::RecruitUnits leaves them to the army moves. Our city at (0, 0), the
 *  enemy's at (8, 0); the city attack gathers on the plots 3 from our city.
 */
@RunWith(GdxTestRunner::class)
class ArmyUnitRecruitTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, tile(0, 0))
        enemyCity = game.addCity(enemy, tile(8, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    /** One archer and two warriors on the free tiles 3 from the capital: enough for the city attack to start gathering */
    private fun gatheringAttack(): AiOperation {
        val ring = tile(0, 0).getTilesAtDistance(3).filter { it.militaryUnit == null }.toList()
        game.addUnit("Archer", civ, ring[0])
        game.addUnit("Warrior", civ, ring[3])
        game.addUnit("Warrior", civ, ring[6])
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        return operation
    }

    private fun armyUnits(operation: AiOperation): List<MapUnit> = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second }

    @Test
    fun `the units of an army are not recruited, the others are`() {
        val operation = gatheringAttack()
        val inArmy = armyUnits(operation)
        assertEquals(3, inArmy.size)
        val loose = game.addUnit("Warrior", civ, tile(-6, 0))
        val recruited = TacticalAi.recruitUnits(civ, inArmy + loose, AutomationCache())
        assertEquals(listOf(loose), recruited)
    }
}
