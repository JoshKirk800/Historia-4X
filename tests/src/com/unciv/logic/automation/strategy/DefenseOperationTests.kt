package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's defense operations by land (Release-5.4.8): CvMilitaryAI::UpdateOperations, CheckLandDefenses and SetupInstantDefenses start a
 *  rapid response (CvAIOperationDefenseRapidResponse) and a city defense (CvAIOperationDefendCity) through CvPlayer::addAIOperation
 *  (PreconditionsAreMet, the limit of units to build) and CvAIOperationMilitary::Init / SetUpArmy; their lifecycle is CheckTransitionToNextStage,
 *  VerifyOrAdjustTarget and OnSuccess; the threatened cities they defend are CvPlayer::GetThreatenedCities (UpdateCityThreatCriteria).
 *  The naval squadron is in [NavalOperationTests]. BNW rules and data (the defense formations are Community Patch's), desert land: our city
 *  at (0, 0), the enemy's at (8, 0), our reserves 6 or more plots west of our city (the recruits of an operation keep 4 plots away from
 *  the city it defends and out of reach of the enemy's units).
 */
@RunWith(GdxTestRunner::class)
class DefenseOperationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        enemyCity = game.addCity(enemy, game.getTile(HexCoord(8, 0)))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        see()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    /** Putting units on the map changes what we see: after placing them, we see all of it, and the turn's maps are made again */
    private fun see() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun war() = civ.getDiplomacyManager(enemy)!!.declareWar()

    private fun losing() {
        civ.ai.warStates.warStates[enemy.civID] = WarState.Defensive
    }

    private val reservePlaces = listOf(
        HexCoord(-6, 0), HexCoord(-6, 1), HexCoord(-6, 2), HexCoord(-7, 0), HexCoord(-7, 1), HexCoord(-7, 2), HexCoord(-8, 0), HexCoord(-8, 1),
    )

    /** [count] warriors in the reserves, far enough from the city to be recruited for its defense */
    private fun reserves(count: Int): List<MapUnit> = reservePlaces.take(count).map { game.addUnit("Warrior", civ, game.getTile(it)) }

    /** An enemy warrior next to our city, on land that is ours */
    private fun intruder(): MapUnit = game.addUnit("Warrior", enemy, city.getCenterTile().neighbors.first())

    /**
     *  Enemy swordsmen on the tiles within two plots of our city that lie east of it: the enemy dominates its zone, and the west stays open (the
     *  reserves' route to the city is checked as the unit's own path, which a ring of units all around would close)
     */
    private fun surround() {
        for (tile in city.getCenterTile().getTilesInDistance(2)) if (tile.position.x >= 1) game.addUnit("Swordsman", enemy, tile)
    }

    //region CheckLandDefenses: the rapid response

    @Test
    fun `a rapid response goes to the city where the enemy gathers when the war is going badly`() {
        war()
        val reserves = reserves(4)
        intruder()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.RAPID_RESPONSE)!!
        assertEquals(city.getCenterTile().position, operation.targetTile)
        assertEquals(enemy.civID, operation.enemyCivId)
        assertTrue("the four reserves fill it", reserves.all { ops.armyOf(it) == operation.firstArmy })
        // the target is ours: the operation is a defense, not an offense
        assertFalse(ops.hasAnyOffensiveOperationsAgainst(enemy))
        assertEquals(0, ops.numOffensiveOperations())
    }

    @Test
    fun `no rapid response while the war is not going badly`() {
        war()
        reserves(4)
        intruder()
        see()
        civ.ai.warStates.warStates[enemy.civID] = WarState.Stalemate
        MilitaryOperations.updateOperations(civ)
        assertNull(ops.firstOfType(AiOperationType.RAPID_RESPONSE))
    }

    @Test
    fun `no rapid response with no enemy near our land, whatever is at hand`() {
        war()
        reserves(4)
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        assertNull(ops.firstOfType(AiOperationType.RAPID_RESPONSE))
    }

    @Test
    fun `no rapid response without a required unit at hand, and one missing required unit is accepted`() {
        war()
        intruder()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        assertNull("no unit at all", ops.firstOfType(AiOperationType.RAPID_RESPONSE))
        val only = reserves(1).single()
        see()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.RAPID_RESPONSE)!!
        // The formation has two required slots; the one unit fills the first, the other is built (the limit is one)
        assertEquals(ops.armyOf(only), operation.firstArmy)
        assertEquals(1, operation.slotsToBuild.size)
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
    }

    @Test
    fun `a rapid response that runs near the city is not started again`() {
        war()
        reserves(8)
        intruder()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.RAPID_RESPONSE))
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.RAPID_RESPONSE))
    }

    @Test
    fun `units next to the city to defend are not recruited for its defense`() {
        war()
        val near = game.addUnit("Warrior", civ, game.getTile(HexCoord(-3, 0)))
        val far = reserves(1).single()
        intruder()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.RAPID_RESPONSE)!!
        assertNull(ops.armyOf(near))
        assertEquals(operation.firstArmy, ops.armyOf(far))
    }

    @Test
    fun `a war going badly ends our offensive operations against the enemy, and keeps the defense`() {
        war()
        val attack = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)
        assertTrue(attack in ops.operations)
        reserves(4)
        intruder()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        assertFalse(attack in ops.operations)
        assertNotNull(ops.firstOfType(AiOperationType.RAPID_RESPONSE))
    }
    //endregion

    //region CheckLandDefenses: the city defense

    @Test
    fun `a city in a zone the enemy dominates gets a city defense`() {
        war()
        val reserves = reserves(8)
        surround()
        see()
        losing()
        assertTrue(civ.ai.tactical.map.isInEnemyDominatedZone(city.getCenterTile()))
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE)!!
        assertEquals(city.getCenterTile().position, operation.targetTile)
        // it musters in the city it defends, until the units are together and the muster point moves to where they stand (SetMusterPlot)
        assertEquals(city.id, operation.startCityId)
        assertTrue(reserves.any { ops.armyOf(it) == operation.firstArmy })
        assertEquals(0, ops.numOffensiveOperations())
    }

    @Test
    fun `no city defense in a zone we dominate`() {
        war()
        reserves(8)
        intruder()
        see()
        losing()
        assertFalse(civ.ai.tactical.map.isInEnemyDominatedZone(city.getCenterTile()))
        MilitaryOperations.updateOperations(civ)
        assertNull(ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE))
    }

    @Test
    fun `a city defense with up to two required units still to build is made`() {
        war()
        // The formation requires COUNTER/DEFENSE, ATTACK/COUNTER and RANGED/DEFENSE; a rapid response (one short allowed) takes the first
        // four units, so the city defense is asked with the one that is left
        reserves(5)
        surround()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val defense = ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE)!!
        assertTrue("two required units to build is the most that is accepted", defense.slotsToBuild.size <= 2)
        assertTrue(defense.firstArmy!!.numSlotsFilled() >= 1)
    }

    @Test
    fun `a city defense ends when its city is no longer ours`() {
        war()
        game.addCity(civ, game.getTile(HexCoord(0, -8))) // so that the civilization is not left without a city
        reserves(8)
        surround()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE)!!
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        city.moveToCiv(enemy)
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.TARGET_ALREADY_CAPTURED, operation.abortReason)
    }
    //endregion

    //region The lifecycle of a defense operation

    @Test
    fun `a rapid response ends when no enemy is left to block`() {
        war()
        reserves(4)
        val intruder = intruder()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.RAPID_RESPONSE)!!
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        intruder.destroy()
        see()
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.NO_TARGET, operation.abortReason)
    }

    @Test
    fun `units that are together move out of the muster point and the army that is in place succeeds, the city is a focus of the tactical AI`() {
        war()
        val reserves = reserves(8)
        surround()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE)!!
        val army = operation.firstArmy!!
        val units = AiOperationLogic.armyUnits(civ, army).map { it.second }
        assertTrue(units.isNotEmpty() && units.all { it in reserves })
        // Next to one another, they are gathered wherever they are and move out
        operation.state = OperationState.GATHERING_FORCES
        army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertEquals(ArmyState.MOVING_TO_DESTINATION, army.state)
        assertFalse(civ.ai.tactical.isInFocusArea(city.getCenterTile()))
        // Within 3 plots of the city, and none of them 6 or more away: it has arrived
        val goal = game.gameInfo.tileMap[army.goalTile!!]
        val places = goal.getTilesInDistance(3).filter { it.militaryUnit == null && !it.isCityCenter() }.toList()
        for ((index, unit) in units.withIndex()) { unit.removeFromTile(); unit.putInTile(places[index]) }
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.isInFocusArea(city.getCenterTile()))
        // The sweep ends it as a success; its units rest from operations for a few turns
        ops.sweep()
        assertNull(ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE))
        assertTrue(units.all { ops.deployFromOperationTurn(it.id) == game.gameInfo.turns })
    }

    @Test
    fun `an army that is far from its goal has not arrived`() {
        war()
        reserves(8)
        surround()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        val operation = ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE)!!
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        assertFalse(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    @Test
    fun `a peace treaty ends the defense operations against the enemy`() {
        war()
        reserves(8)
        surround()
        see()
        losing()
        MilitaryOperations.updateOperations(civ)
        assertNotNull(ops.firstOfType(AiOperationType.CITY_CLOSE_DEFENSE))
        val diplomacy = civ.getDiplomacyManager(enemy)!!
        diplomacy.makePeace()
        diplomacy.trades.add(Trade().apply { ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10)) })
        MilitaryOperations.updateOperations(civ)
        assertTrue(ops.operations.isEmpty())
    }
    //endregion

    //region SetupInstantDefenses (CvTeam::declareWar)

    @Test
    fun `a war declared between two majors gives us a rapid response at once, with every required unit at hand`() {
        val reserves = reserves(4)
        see()
        war()
        val operation = ops.firstOfType(AiOperationType.RAPID_RESPONSE)!!
        assertEquals(city.getCenterTile().position, operation.targetTile)
        assertEquals(enemy.civID, operation.enemyCivId)
        assertTrue(operation.slotsToBuild.isEmpty())
        assertTrue(reserves.all { ops.armyOf(it) == operation.firstArmy })
    }

    @Test
    fun `no instant defense when a required unit would still have to be built`() {
        reserves(1)
        see()
        war()
        assertNull(ops.firstOfType(AiOperationType.RAPID_RESPONSE))
    }
    //endregion

    //region The threatened cities (CvPlayer::UpdateCityThreatCriteria, GetThreatenedCities)

    @Test
    fun `a focus area adds the weight of the capital to the threat of the city it covers, until it runs out`() {
        war()
        val weight = game.ruleset.ai.define(AiDefine.AI_MILITARY_CITY_THREAT_WEIGHT_CAPITAL)
        val calm = CityThreat.threatValue(city)
        civ.ai.tactical.addFocusArea(city.getCenterTile(), 3, 5)
        civ.ai.tactical.invalidateMap()
        assertEquals(calm + weight, CityThreat.threatValue(city))
        game.gameInfo.turns += 6
        civ.ai.tactical.invalidateMap()
        assertEquals(calm, CityThreat.threatValue(city))
    }

    @Test
    fun `an enemy that dominates the zone of a city adds 50 to its threat`() {
        war()
        val calm = CityThreat.threatValue(city)
        surround()
        see()
        assertTrue(civ.ai.tactical.map.isInEnemyDominatedZone(city.getCenterTile()))
        assertTrue(CityThreat.threatValue(city) >= calm + 50)
    }

    @Test
    fun `the threatened cities come most threatened first, and in the order of our cities when they are equal`() {
        val second = game.addCity(civ, game.getTile(HexCoord(-6, 5)))
        assertEquals(listOf(city, second), CityThreat.threatenedCities(civ))
        civ.ai.tactical.addFocusArea(second.getCenterTile(), 2, 5)
        civ.ai.tactical.invalidateMap()
        assertEquals(listOf(second, city), CityThreat.threatenedCities(civ))
        assertEquals("on desert there is no coast", emptyList<City>(), CityThreat.threatenedCities(civ, coastalOnly = true))
    }
    //endregion

    //region CvPlayer::getFirstAIOperationOfType and IsOffensive

    @Test
    fun `an operation is found by a target less than three plots from its own, and not once it is aborted`() {
        val attack = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)
        assertEquals(attack, ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK, target = game.getTile(HexCoord(10, 0))))
        assertNull(ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK, target = game.getTile(HexCoord(11, 0))))
        assertTrue(ops.hasAnyOffensiveOperationsAgainst(enemy))
        AiOperationLogic.abortOperation(attack, OperationAbortReason.KILLED)
        assertNull(ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK))
    }
    //endregion
}
