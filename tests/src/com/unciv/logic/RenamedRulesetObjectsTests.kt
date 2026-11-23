package com.unciv.logic

import com.unciv.logic.BackwardCompatibility.removeMissingModReferences
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Slice T1 of `phaseE/vp-trade-plan.md` renames five objects of the Brave New World ruleset to Vox Populi's names (Spices to Cinnamon, Gold Ore to Gold,
 * Terrace farm to Pata-Pata, Trading post to Village, Customs house to Town). A save from before names them: it must load with the new names.
 */
@RunWith(GdxTestRunner::class)
class RenamedRulesetObjectsTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
    }

    /** The queue of improvements being built is internal to the tile: an old save has an entry in it, so one is put there the way the save loader would */
    @Suppress("UNCHECKED_CAST")
    private fun queue(tile: com.unciv.logic.map.tile.Tile, improvement: String, turns: Int) {
        val entryClass = Class.forName("com.unciv.logic.map.tile.Tile\$ImprovementQueueEntry")
        val constructor = entryClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val field = com.unciv.logic.map.tile.Tile::class.java.getDeclaredField("improvementQueue").apply { isAccessible = true }
        (field.get(tile) as ArrayList<Any>).add(constructor.newInstance(improvement, turns))
    }

    @Test
    fun `the Brave New World ruleset has the Vox Populi names`() {
        val ruleset = game.ruleset
        for (name in listOf("Cinnamon", "Gold")) assertTrue(name, name in ruleset.tileResources)
        for (name in listOf("Spices", "Gold Ore")) assertFalse(name, name in ruleset.tileResources)
        for (name in listOf("Pata-Pata", "Village", "Town")) assertTrue(name, name in ruleset.tileImprovements)
        for (name in listOf("Terrace farm", "Trading post", "Customs house")) assertFalse(name, name in ruleset.tileImprovements)
        assertTrue("the building of the same name as the Town stays", "Customs House" in ruleset.buildings)
    }

    @Test
    fun `an old save naming Spices loads as Cinnamon and the rest as their new names`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        val resourceTile = game.getTile(HexCoord(1, 0))
        resourceTile.setTileResource("Spices", false) // not in the ruleset: only the name is set, as an old save has it
        resourceTile.improvement = "Trading post"
        val goldTile = game.getTile(HexCoord(0, 1))
        goldTile.setTileResource("Gold Ore", false)
        goldTile.improvement = "Mine"
        val incaTile = game.getTile(HexCoord(-1, 1))
        incaTile.improvement = "Terrace farm"
        val queuedTile = game.getTile(HexCoord(-1, 0))
        queue(queuedTile, "Customs house", 4)
        civ.lastSeenImprovement[HexCoord(1, 0)] = "Trading post"
        civ.lastSeenImprovement[HexCoord(-1, 1)] = "Terrace farm"
        civ.cityStateResource = "Spices"
        civ.getDiplomacyManagerOrMeet(game.addCiv()).trades.add(com.unciv.logic.trade.Trade().apply {
            ourOffers.add(TradeOffer("Spices", TradeOfferType.Luxury_Resource, 1, game.gameInfo.speed))
            theirOffers.add(TradeOffer("Gold Ore", TradeOfferType.Luxury_Resource, 1, game.gameInfo.speed))
        })

        with(BackwardCompatibility) { game.gameInfo.removeMissingModReferences() }

        assertEquals("Cinnamon", resourceTile.resource)
        assertEquals("Village", resourceTile.improvement)
        assertEquals("Gold", goldTile.resource)
        assertEquals("Mine", goldTile.improvement)
        assertEquals("Pata-Pata", incaTile.improvement)
        assertEquals("Town", queuedTile.improvementInProgress)
        assertEquals("the turns left stay", 4, queuedTile.turnsToImprovement)
        assertEquals("Village", civ.lastSeenImprovement[HexCoord(1, 0)])
        assertEquals("Pata-Pata", civ.lastSeenImprovement[HexCoord(-1, 1)])
        assertEquals("Cinnamon", civ.cityStateResource)
        val trade = civ.diplomacy.values.first { it.trades.isNotEmpty() }.trades.first()
        assertEquals(listOf("Cinnamon"), trade.ourOffers.map { it.name })
        assertEquals(listOf("Gold"), trade.theirOffers.map { it.name })
        assertEquals("the city stays", city, civ.getCapital())
    }

    @Test
    fun `a save that already has the new names is left alone`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
        val tile = game.getTile(HexCoord(1, 0))
        tile.setTileResource("Cinnamon", false)
        tile.improvement = "Village"
        with(BackwardCompatibility) { game.gameInfo.removeMissingModReferences() }
        assertEquals("Cinnamon", tile.resource)
        assertEquals("Village", tile.improvement)
    }
}
