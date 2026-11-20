package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.OperationalMoves
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
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
 *  Vox Populi's CvAIOperationCarrierGroup (CvAIOperation.cpp: Init, VerifyOrAdjustTarget, IsNeverEnding, UnitWasRemoved) and the idle
 *  carrier's launch in CvMilitaryAI::UpdateOperations, with the CP formation MUFORMATION_CARRIER_ESCORT. A channel of coast water separates
 *  our city at x=0 from the enemy's at x=14.
 */
@RunWith(GdxTestRunner::class)
class CarrierGroupTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(20)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        for (x in 1..13) for (y in -6..6) game.tileMap.getOrNull(x, y)?.let { game.setTileTerrain(it.position, "Coast") }
        city = game.addCity(civ, tile(0))
        game.addCity(enemy, tile(14))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.tech.unitsCanEmbark = true
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        seeAll()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))
    private fun tile(position: HexCoord) = game.getTile(position)

    private fun seeAll() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    /** The carrier and one ocean-going ship for each of the other required slots of the carrier escort, (the carrier on the water east of our port, the others 5 plots further) */
    private fun strikeGroup(): List<MapUnit> {
        val rules = game.ruleset.ai
        // the carrier is the muster and the target in peacetime, and units less than 4 plots from the target are no recruits (IsUnitSuitableForRecruitment)
        val places = ArrayDeque(listOf(tile(2, 0)) + (-2..2).map { y -> tile(7, y) })
        val units = ArrayList<MapUnit>()
        for (slot in ops.formationDef("MUFORMATION_CARRIER_ESCORT")!!.slots.filter { it.required }) {
            val candidates = game.ruleset.units.values.filter { it.isWaterUnit && (it.strength > 0 || it.rangedStrength > 0) && it.uniqueTo == null }
            val unitAi = UnitAiType.ofXmlName(slot.primaryUnitAi)!!
            var unit: MapUnit? = null
            for (base in candidates.filter { UnitAiTypes.canHave(it, unitAi, rules) }.sortedByDescending { UnitAiTypes.defaultOf(it, rules) == unitAi }) {
                val created = game.addUnit(base.name, civ, places.first())
                if (unitAi == UnitAiType.CARRIER_SEA || !created.cache.cannotEnterOceanTiles) { unit = created; places.removeFirst(); break }
                created.destroy()
            }
            units += unit!!
        }
        seeAll()
        return units
    }

    private fun launch(): AiOperation {
        MilitaryOperations.updateOperations(civ)
        return ops.firstOfType(AiOperationType.CARRIER_GROUP)!!
    }

    @Test
    fun `an idle carrier with its escort starts a carrier group against nobody, the carrier in slot 0, staying where it is at peace`() {
        val group = strikeGroup()
        val operation = launch()
        assertNull(operation.enemyCivId)
        assertEquals("MUFORMATION_CARRIER_ESCORT", operation.firstArmy!!.formation)
        assertEquals(group[0].id, operation.firstArmy!!.slots[0].unitId)
        assertTrue(group.all { ops.armyOf(it) != null })
        // no zone to go to: it is "good enough for now" where the carrier stands
        assertEquals(group[0].getTile().position, operation.targetTile)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.CARRIER_GROUP))
        MilitaryOperations.updateOperations(civ)
        assertEquals("the carrier is no longer idle", 1, ops.numOperationsOfType(AiOperationType.CARRIER_GROUP))
    }

    @Test
    fun `no carrier or too few ships starts nothing`() {
        game.addUnit("Quinquereme", civ, tile(6, 0))
        MilitaryOperations.updateOperations(civ)
        assertNull(ops.firstOfType(AiOperationType.CARRIER_GROUP))
        val group = strikeGroup()
        group.drop(1).forEach { it.destroy() }
        MilitaryOperations.updateOperations(civ)
        assertNull("a carrier alone is short of required units", ops.firstOfType(AiOperationType.CARRIER_GROUP))
    }

    /** CvAIOperation::ShouldAbort: the 42-turn failsafe does not apply to an operation that is never ending */
    @Test
    fun `a carrier group does not time out and does not arrive`() {
        strikeGroup()
        val operation = launch()
        operation.createdTurn = game.gameInfo.turns - AiOperationLogic.TIMEOUT_TURNS * 3
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        assertNotNull(ops.firstOfType(AiOperationType.CARRIER_GROUP))
        // at its goal it is not "in place": an operation that never ends keeps moving
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        assertFalse(AiOperationLogic.checkTransitionToNextStage(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        // any other operation of that age is called off
        val squadron = AiOperation().also { it.type = AiOperationType.NAVAL_SUPERIORITY; it.state = OperationState.MOVING_TO_TARGET; it.createdTurn = operation.createdTurn }
        ops.operations.add(squadron)
        assertTrue(AiOperationLogic.shouldAbort(civ, squadron))
        assertEquals(OperationAbortReason.TIMED_OUT, squadron.abortReason)
    }

    /** CvAIOperationCarrierGroup::UnitWasRemoved */
    @Test
    fun `a carrier group without its carrier is cancelled, without an escort ship it goes on`() {
        val group = strikeGroup()
        val operation = launch()
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        ops.removeFromArmy(group[1])
        assertNotEquals(OperationState.ABORTED, operation.state)
        ops.removeFromArmy(group[0])
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.CANCELLED, operation.abortReason)
    }

    /** CvAIOperationCarrierGroup::VerifyOrAdjustTarget: a carrier that would not survive the next turn retreats */
    @Test
    fun `a carrier that would die next turn leaves the operation`() {
        val group = strikeGroup()
        val operation = launch()
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addUnit("Ironclad", enemy, tile(group[0].getTile().position.x + 1, group[0].getTile().position.y))
        // CvUnit::isProjectedToDieNextTurn: the damage it took last turn is more than the health it has left
        group[0].health = 5
        group[0].damageTakenLastTurn = 6
        seeAll()
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
        assertEquals(OperationAbortReason.HALF_STRENGTH, operation.abortReason)
    }

    /** With no zone to go to the group goes to the coastal water of our nearest port */
    @Test
    fun `a carrier group that has nowhere to go sails home`() {
        val group = strikeGroup()
        val operation = launch()
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        for ((index, unit) in group.withIndex()) { unit.removeFromTile(); unit.putInTile(tile(8, index - 2)) }
        seeAll()
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        val target = tile(operation.targetTile!!)
        assertTrue("home waters: ${target.position}", target.isWater && target.aerialDistanceTo(city.getCenterTile()) <= 2)
        assertEquals(target.position, operation.firstArmy!!.goalTile)
    }

    @Test
    fun `a carrier group moves with the other armies of the tactical AI`() {
        val group = strikeGroup()
        val operation = launch()
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertTrue(group.all { it.getTile().isWater })
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?) = org.junit.Assert.assertNotEquals(unexpected, actual)
}
