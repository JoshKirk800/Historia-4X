package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Target discovery after Vox Populi's CvTacticalAI::FindTacticalTargets. */
@RunWith(BaseTestRunner::class)
class TacticalTargetSelectionTests {
    @Test
    fun `an occupied barbarian camp remains a camp target as well as an enemy unit target`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        val civ = game.addCiv()
        val barbarians = game.addBarbarianCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
        val camp = game.getTile(HexCoord(3, 0))
        camp.setImprovementBasic(Constants.barbarianEncampment)
        val defender = game.addUnit("Warrior", barbarians, camp)
        civ.viewableTiles = game.tileMap.values.toSet()
        val targets = TacticalTargets.find(civ, null).all.filter { it.tile == camp }

        assertTrue("the defender can be attacked", targets.any { it.unit == defender })
        assertTrue("the camp remains an objective", targets.any { it.type == TacticalTargetType.BarbarianCamp })
    }

    private fun bastionGame(enemyCityRow: Int): Triple<TestGame, com.unciv.logic.civilization.Civilization, com.unciv.logic.map.tile.Tile> {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12, Constants.grassland)
        val civ = game.addCiv()
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        val ours = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        game.addCity(enemy, game.getTile(HexCoord(0, enemyCityRow)))
        // a major we have been nuked by is a threat whatever its strength
        civ.ai.diplomacy.memoryOf(enemy).numTimesNuked = 1
        game.ruleset.terrains[Constants.hill]!!.defenceBonus = 0.5f  // strong enough, the plain hill's 25 is below the bastion's 30
        val hill = game.setTileTerrain(HexCoord(0, 3), Constants.hill)
        hill.setOwningCity(ours)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        civ.viewableTiles = game.tileMap.values.toSet()
        return Triple(game, civ, hill)
    }

    @Test
    fun `a strong defensive plot of ours (30 percent or more) in the borderland of a major that threatens us is a bastion worth its defense modifier`() {
        val (_, civ, hill) = bastionGame(enemyCityRow = 8)
        val map = civ.ai.tactical.map
        val defense = map.defenseModifier(hill)
        assertTrue("setup: a strong hill defends by 30 or more, not $defense", defense >= 30)
        val bastions = TacticalTargets.find(civ, map).all.filter { it.type == TacticalTargetType.DefensiveBastion }
        assertEquals(listOf(hill), bastions.map { it.tile })
        assertEquals(defense, bastions.single().aux)
    }

    @Test
    fun `no bastion where no major we fear is near, however strong the plot`() {
        val (_, civ, _) = bastionGame(enemyCityRow = 11)
        val bastions = TacticalTargets.find(civ, civ.ai.tactical.map).all.filter { it.type == TacticalTargetType.DefensiveBastion }
        assertTrue(bastions.isEmpty())
    }
}
