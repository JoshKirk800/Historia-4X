package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resource rules of slice T1 of `phaseE/vp-trade-plan.md`: a resource that a technology makes obsolete (V:CvPlot.cpp:7743, V:CvDealClasses.cpp:546),
 * and the production cost of units and wonders in cities with a resource (V:CvCity.cpp:9746-9770, 11422-11442, 18325-18420).
 */
@RunWith(GdxTestRunner::class)
class ResourceRulesTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        a = game.addCiv()
        b = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        city = game.addCity(a, game.getTile(HexCoord(0, 0)))
        game.addCity(b, game.getTile(HexCoord(4, 0)))
    }

    /** The objects of a TestGame's ruleset are those of the cached ruleset (its clone is shallow): what a test sets on Horses must not stay for the other tests */
    @After
    fun restoreHorses() {
        game.ruleset.tileResources["Horses"]!!.obsoleteBy = null
    }

    //region obsolete resources
    /** Horses on a pasture tile of the city of [a], three copies, as Brave New World's script places them */
    private fun horsesOnPasture(): Pair<TileResource, com.unciv.logic.map.tile.Tile> {
        val horses = game.ruleset.tileResources["Horses"]!!
        horses.obsoleteBy = "Rocketry"
        val tile = game.getTile(HexCoord(1, 0))
        game.addTileToCity(city, tile)
        tile.setOwningCity(city)
        tile.setTileResource("Horses")
        tile.resourceAmount = 3
        tile.setImprovement("Pasture")
        a.tech.addTechnology("Animal Husbandry")
        return horses to tile
    }

    @Test
    fun `a team with Rocketry no longer sees Horses and cannot trade them`() {
        val (horses, tile) = horsesOnPasture()
        a.cache.updateCivResources()
        assertTrue(a.canSeeResource(horses))
        assertEquals(3, a.getCivResourceSupply().sumBy("Horses"))
        val offer = TradeOffer("Horses", TradeOfferType.Strategic_Resource, 3, game.gameInfo.speed)
        val trade = Trade().apply { ourOffers.add(offer) }
        assertTrue(TradeLogic(a, b).ourAvailableOffers.any { it.name == "Horses" })

        // V:CvDealClasses.cpp:546-548: it is the tech of either side that makes it untradeable
        b.tech.addTechnology("Rocketry")
        assertTrue("a does not have the tech: it still sees them", a.canSeeResource(horses))
        assertFalse(TradeLogic(a, b).ourAvailableOffers.any { it.name == "Horses" })
        assertFalse(TradeEvaluation().isTradeValid(trade, a, b))

        // V:CvPlot.cpp:7743: a team with the tech does not see the resource of a tile
        a.tech.addTechnology("Rocketry")
        assertFalse(a.canSeeResource(horses))
        assertEquals("the tile keeps its resource", "Horses", tile.resource)
    }

    @Test
    fun `a resource with obsoleteBy keeps its tile but yields no supply`() {
        val (horses, tile) = horsesOnPasture()
        a.cache.updateCivResources()
        assertEquals(3, a.getCivResourceSupply().sumBy("Horses"))
        a.tech.addTechnology("Rocketry")
        assertEquals("the tile still has it", "Horses", tile.resource)
        assertEquals(3, tile.resourceAmount)
        assertFalse(tile.providesResources(a))
        assertEquals("but nothing is supplied", 0, a.getCivResourceSupply().sumBy("Horses"))
        assertFalse("b has not revealed it (Animal Husbandry)", b.canSeeResource(horses))
    }

    @Test
    fun `a revealed strategic resource is supplied until the technology that makes it obsolete, as Paper is by Agriculture`() {
        val paper = game.createResource().apply {
            resourceType = ResourceType.Strategic; revealedBy = "Writing"; obsoleteBy = "Pottery"; improvement = "Pasture"; setTransients(game.ruleset)
        }
        val tile = game.getTile(HexCoord(1, 0))
        game.addTileToCity(city, tile)
        tile.setOwningCity(city)
        tile.setTileResource(paper.name)
        tile.resourceAmount = 2
        tile.setImprovement("Pasture")
        a.tech.addTechnology("Animal Husbandry")
        a.tech.addTechnology("Writing")
        a.cache.updateCivResources()
        assertEquals(2, a.getCivResourceSupply().sumBy(paper.name))
        a.tech.addTechnology("Pottery") // Agriculture, the technology of Paper in Vox Populi, is the one every test civilization starts with
        assertFalse(a.canSeeResource(paper))
        assertEquals(0, a.getCivResourceSupply().sumBy(paper.name))
    }
    @Test
    fun `a quantity percentage changes the copies a city gives, rounded down`() {
        horsesOnPasture()
        a.cache.updateCivResources()
        assertEquals(3, a.getCivResourceSupply().sumBy("Horses"))
        city.cityConstructions.addBuilding(game.createBuilding("[+50]% quantity of [Horses]"))
        a.cache.updateCivResources()
        // 3 x 1.5 = 4.5, whole numbers: 4. Another resource is not changed
        assertEquals(4, a.getCivResourceSupply().sumBy("Horses"))
    }
    //endregion

    //region production
    private fun giveCityResource(resource: TileResource) {
        val tile = city.getCenterTile()
        tile.tileResource = resource
        tile.resourceAmount = 1
        a.cache.updateCivResources()
    }

    private fun wonder(tech: String): Building = game.createWonder().apply { requiredTech = tech }

    private fun wonderProduction(wonder: Building): Float {
        city.cityStats.update(wonder)
        return city.cityStats.statPercentBonusTree.totalStats[Stat.Production]
    }

    @Test
    fun `Marble gives +15 percent to Wonders in a city that has it until its obsolete era`() {
        val marble = game.createResource("[+15]% Production of Wonders in cities with this resource <before the [Industrial era]>")
            .apply { resourceType = ResourceType.Luxury; setTransients(game.ruleset) }
        val ancient = wonder("Pottery")
        val industrial = wonder("Electricity")
        val building = game.createBuilding()
        val baseAncient = wonderProduction(ancient)
        val baseIndustrial = wonderProduction(industrial)
        val baseBuilding = wonderProduction(building)

        giveCityResource(marble)
        // V:CvCity.cpp:18351-18416: the era of the wonder's technology decides, not the era of the player
        assertEquals(baseAncient + 15f, wonderProduction(ancient), 0f)
        assertEquals("the wonder's era is not before Industrial", baseIndustrial, wonderProduction(industrial), 0f)
        assertEquals("not a wonder", baseBuilding, wonderProduction(building), 0f)
        a.tech.addTechnology("Electricity")
        assertEquals("the player's era does not end it", baseAncient + 15f, wonderProduction(ancient), 0f)

        city.getCenterTile().tileResource = null
        a.cache.updateCivResources()
        assertEquals("a city without it", baseAncient, wonderProduction(ancient), 0f)
    }

    @Test
    fun `Ivory lowers siege production 15 percent until Industrial`() {
        val ivory = game.createResource("[-15]% Production of [Siege] units in cities with this resource <before the [Industrial era]>")
            .apply { resourceType = ResourceType.Luxury; setTransients(game.ruleset) }
        val catapult = game.ruleset.units["Catapult"]!!
        val swordsman = game.ruleset.units["Swordsman"]!!
        val artillery = game.ruleset.units.values.first { unit ->
            unit.matchesFilter("Siege") && unit.requiredTech != null && game.ruleset.technologies[unit.requiredTech]!!.era() == "Industrial era" }
        val before = listOf(catapult, swordsman, artillery).map { it.getProductionCost(a, city) }

        giveCityResource(ivory)
        // iNumProductionNeeded x (100 - 15) / 100, whole numbers
        assertEquals(before[0] * 85 / 100, catapult.getProductionCost(a, city))
        assertEquals("not a siege unit", before[1], swordsman.getProductionCost(a, city))
        assertEquals("a siege unit of the Industrial era", before[2], artillery.getProductionCost(a, city))
        assertEquals("without a city nothing is local", before[0], catapult.getProductionCost(a, null))
    }
    //endregion
}
