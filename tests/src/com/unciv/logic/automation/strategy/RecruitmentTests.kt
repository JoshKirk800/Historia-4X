package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Reserve selection and recruitment of Vox Populi's CvAIOperation (Release-5.4.8): which units of the reserves an operation takes
 *  (OperationalAIHelpers::IsUnitSuitableForRecruitment, CvUnit::canUseForAIOperation, CvUnit::shouldHeal), how it ranks them
 *  (GrabUnitsFromTheReserves), how long it waits and when it takes more (GetMaximumRecruitTurns, CvAIOperation::Move), how required and
 *  optional slots count (HaveEnoughUnits, UnitWasRemoved) and which units it drops (CvArmyAI::UpdateCheckpointTurnsAndRemoveBadUnits).
 *  BNW data and rules, desert land.
 */
@RunWith(GdxTestRunner::class)
class RecruitmentTests {
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
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun unit(name: String, x: Int, y: Int, owner: Civilization = civ): MapUnit = game.addUnit(name, owner, game.getTile(HexCoord(x, y)))

    /** The basic city attack on the enemy city from ours: required CITY_BOMBARD/RANGED, ATTACK/DEFENSE and ATTACK/COUNTER (slots 0, 3 and 4) */
    private fun attack() = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)

    private fun fertileLand() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    //region Who is a candidate and how they rank

    /** GrabUnitsFromTheReserves: 10000 + power - 30 per turn to the muster point; the first of the unit AI type is no longer the nearest */
    @Test
    fun `a unit with more power than 30 per extra turn of its way outranks the nearer one`() {
        val spearman = unit("Spearman", 0, 0)
        val pikeman = unit("Pikeman", 1, 0)
        val army = attack().firstArmy!!
        // Spearman power 45 at the muster point, Pikeman power 81 one turn away: 10045 against 10051
        assertEquals(pikeman.id, army.slots[3].unitId)
        assertEquals("the spearman takes the next slot that fits it", spearman.id, army.slots[4].unitId)
    }

    /** CvUnit::canUseForAIOperation: units with an enemy within 3 plots are engaged and not poached */
    @Test
    fun `a unit with an enemy within three plots is not recruited`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        val engaged = unit("Warrior", 2, 0)
        val free = unit("Warrior", -3, 0)
        unit("Warrior", 5, 0, enemy)
        civ.ai.invalidateTurnCaches()
        attack()
        assertNull(ops.armyOf(engaged))
        assertNotNull(ops.armyOf(free))
    }

    /** IsUnitSuitableForRecruitment: below 70 health no unit joins, and a unit that fights well wounded may be hurt up to 50 (shouldHeal) */
    @Test
    fun `health decides by Vox Populi's 70 percent and what a unit may be hurt to`() {
        val hardiness = game.createUnitPromotion("No damage penalty for wounded units")
        val hardy = unit("Warrior", 1, 0)
        hardy.promotions.addPromotion(hardiness.name, true)
        hardy.health = 75
        val frail = unit("Warrior", 0, 1)
        frail.promotions.addPromotion(hardiness.name, true)
        frail.health = 65
        val plain = unit("Warrior", -1, 0)
        plain.health = 75
        attack()
        assertEquals(70, ops.minHealth())
        assertNotNull("25 damage is fine for a unit that fights well wounded, and 75 is above 70", ops.armyOf(hardy))
        assertNull("below 70", ops.armyOf(frail))
        assertNull("more than 20 damage, and it should heal", ops.armyOf(plain))
    }

    /** IsUnitSuitableForRecruitment: units within less than 4 plots of the target are the tactical AI's, as are those in the zone of the enemy's city */
    @Test
    fun `units near the target of an attack are not recruited for it`() {
        val far = unit("Warrior", 4, 0)
        val near = unit("Warrior", 5, 0)
        attack()
        assertNotNull(ops.armyOf(far))
        assertNull(ops.armyOf(near))
    }

    //endregion
    //region Required and optional slots

    /** OperationalAIHelpers::HaveEnoughUnits: two optional units make up for one missing required one */
    @Test
    fun `optional units make up for a required slot that stays empty and the operation gathers`() {
        unit("Archer", 1, 0)
        unit("Archer", 0, 1)
        unit("Warrior", -1, 0)
        unit("Great General", 0, -1)
        val operation = attack()
        val army = operation.firstArmy!!
        // required: bombard (slot 0) and attack/defense (3) filled, attack/counter (4) empty; optional: the second archer (1) and the general (6)
        assertEquals(listOf(SlotRef(operation.id, army.id, 4)), operation.slotsToBuild)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertTrue(AiOperationLogic.haveEnoughUnits(civ, army, 0))
    }

    /** CvAIOperation::UnitWasRemoved: half of the formation's required slots, not of all its slots */
    @Test
    fun `an army aborts below half of its required slots, not of all of them`() {
        unit("Archer", 1, 0)
        unit("Warrior", 0, 1)
        unit("Warrior", -1, 0)
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        val units = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second }
        ops.removeFromArmy(units[0])
        ops.removeFromArmy(units[1])
        // one unit of three required slots; the seven of the formation would make that half strength
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        ops.removeFromArmy(units[2])
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.HALF_STRENGTH, operation.abortReason)
    }

    //endregion
    //region Ongoing recruitment

    /** CvAIOperation::Move: an army still waiting for units takes what the reserves have each turn */
    @Test
    fun `an operation that is recruiting takes units that become free later, and then gathers`() {
        unit("Archer", 1, 0)
        val operation = attack()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(2, operation.slotsToBuild.size)
        unit("Warrior", 0, 1)
        unit("Warrior", -1, 0)
        civ.ai.invalidateTurnCaches()
        ops.refreshCommitments()
        assertTrue(operation.slotsToBuild.isEmpty())
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertNotEquals(OperationState.RECRUITING_UNITS, operation.state)
    }

    /** CvAIOperation::GetMaximumRecruitTurns: 5 plus half the turns since the operation started, for a settler's escort */
    @Test
    fun `a settler waits for an escort that needs more turns than it will wait, and takes it when the wait has grown`() {
        fertileLand()
        val warrior = unit("Warrior", -11, 0) // 6 turns of two moves from the settler
        val settler = unit("Settler", 0, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(5, AiOperationLogic.maxRecruitTurns(civ, operation))
        assertNull(ops.armyOf(warrior))
        game.gameInfo.turns += 1
        ops.refreshCommitments()
        assertNull("5 turns still", ops.armyOf(warrior))
        game.gameInfo.turns += 1
        ops.refreshCommitments()
        assertEquals(6, AiOperationLogic.maxRecruitTurns(civ, operation))
        assertEquals(operation.firstArmy, ops.armyOf(warrior))
        assertTrue(operation.slotsToBuild.isEmpty())
    }

    /** CvArmyAI::UpdateCheckpointTurnsAndRemoveBadUnits: a unit that makes no progress to the checkpoint over three records leaves */
    @Test
    fun `a unit that cannot get to the muster point leaves the army after three records`() {
        unit("Archer", 1, 0)
        unit("Warrior", 0, 1)
        unit("Warrior", -1, 0)
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        val stranded = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).first().second
        val island = game.getTile(HexCoord(-8, 0))
        for (neighbor in island.neighbors) game.setTileTerrain(neighbor.position, "Ocean") // no embarking without Optics
        stranded.removeFromTile()
        stranded.putInTile(island)
        ops.refreshCommitments()
        assertNotNull("two records are no history", ops.armyOf(stranded))
        ops.refreshCommitments()
        assertNull(ops.armyOf(stranded))
        assertEquals("the army is not half strength", OperationState.GATHERING_FORCES, operation.state)
    }

    /** CvUnit::shouldHeal(true): an offensive operation drops a badly hurt unit */
    @Test
    fun `a badly hurt unit leaves an army that goes to attack`() {
        unit("Archer", 1, 0)
        unit("Warrior", 0, 1)
        unit("Warrior", -1, 0)
        val operation = attack()
        val units = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second }
        units[0].health = 10
        ops.refreshCommitments()
        assertNull(ops.armyOf(units[0]))
        assertNotNull(ops.armyOf(units[1]))
    }

    //endregion
    //region Releasing units

    /** CvArmyAI::RemoveUnit: a unit that leaves its army has its own unit AI back */
    @Test
    fun `a unit that leaves its army is no longer what its slot made of it`() {
        val operation = attack()
        val offer = ops.nextUnitForOperationSlot(city)!!
        city.cityConstructions.setCurrentConstruction("Warrior")
        ops.commitToBuild(city, offer, 2)
        val trained = unit("Warrior", 1, 0)
        assertTrue(ops.finishedBuilding(trained, city))
        // the slot's secondary unit AI, which is not what a warrior is by itself
        UnitAiTypes.set(trained, UnitAiType.DEFENSE)
        assertEquals(UnitAiType.DEFENSE, UnitAiTypes.current(trained))
        ops.removeFromArmy(trained)
        assertEquals(UnitAiTypes.defaultOf(trained.baseUnit, game.ruleset.ai), UnitAiTypes.current(trained))
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
    }

    //endregion
}
