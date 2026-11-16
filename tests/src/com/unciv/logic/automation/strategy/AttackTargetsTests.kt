package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
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

/** Vox Populi's CvMilitaryAI::UpdateAttackTargets and its helpers ([AttackTargetsAi]) */
@RunWith(GdxTestRunner::class)
class AttackTargetsTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14, Constants.grassland)
        us = civ()
        them = civ()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        for (tile in game.tileMap.values) { tile.setExplored(us, true); tile.setExplored(them, true) }
    }

    private fun civ(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 5)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 5)
        return civ
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    @Test
    fun `a city in reach on open land is a preferred land target, and ours exposed to its owner`() {
        val ours = game.addCity(us, tile(0, 0))
        val theirs = game.addCity(them, tile(6, 0))
        val (targets, exposed) = AttackTargetsAi.updateAttackTargets(us)
        val target = targets.singleOrNull { it.targetCity == theirs }
        assertNotNull(target)
        assertEquals(TargetArmyType.Land, target!!.armyType)
        assertTrue("approach ${target.approachScore}", target.approachScore > 30)
        assertTrue(target.preferred)
        assertEquals(7, target.pathLength)
        assertTrue(exposed.any { it.targetCity == ours && it.musterCity.civ == them })
    }

    @Test
    fun `cities beyond the trade route range are no targets`() {
        game.addCity(us, tile(0, 0))
        game.addCity(them, tile(13, 0))
        val (targets, exposed) = AttackTargetsAi.updateAttackTargets(us)
        assertTrue(targets.isEmpty())
        assertTrue(exposed.isEmpty())
    }

    @Test
    fun `a path through the closed territory of a third party is not good`() {
        game.addCity(us, tile(0, 0))
        game.addCity(them, tile(10, 0))
        val path = AttackTargetsAi.armyPaths(us, us.cities.first(), water = false).single()
        assertEquals(11, path.size)
        assertTrue(AttackTargetsAi.armyPathIsGood(path, us, them, 3))
        // a third civilization's city next to the middle of the path, owning the middle; its borders are closed to us
        val third = civ()
        us.diplomacyFunctions.makeCivilizationsMeet(third)
        val site = path[5].neighbors.first { it !in path }
        val city = game.addCity(third, site)
        for (i in 3..7) if (path[i].getOwner() == null) city.expansion.takeOwnership(path[i])
        assertFalse(AttackTargetsAi.armyPathIsGood(path, us, them, 3))
        // with open borders the tiles are friendly
        us.getDiplomacyManager(third)!!.hasOpenBorders = true
        assertTrue(AttackTargetsAi.armyPathIsGood(path, us, them, 3))
    }

    @Test
    fun `a city on a small island is approached by sea, a land army has no approach`() {
        for (t in game.tileMap.values) if (t.aerialDistanceTo(tile(8, 0)) in 1..4) game.setTileTerrain(t.position, Constants.coast)
        for (t in game.tileMap.values) t.setTerrainTransients()
        us.tech.addTechnology("Sailing")
        us.tech.unitsCanEmbark = true
        val theirs = game.addCity(them, tile(8, 0))
        val staging = tile(5, 0)
        assertTrue(staging.isWater)
        assertEquals(0, AttackTargetsAi.evaluateTargetApproach(us, staging, theirs, TargetArmyType.Land))
        val naval = AttackTargetsAi.evaluateTargetApproach(us, staging, theirs, TargetArmyType.Naval)
        assertTrue("naval $naval", naval > 30)
        assertNull(AttackTargetsAi.bestTargetApproach(us, tile(-3, 0), theirs))
    }
}
