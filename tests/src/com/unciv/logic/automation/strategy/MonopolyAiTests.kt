package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.civilization.DealResourceValues
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueEffects
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AI terms that ask how far a civilization is from a monopoly (slice T6 of `phaseE/vp-trade-plan.md`): the plot build score (V:CvBuilderTaskingAI.cpp:4073-4140), the city
 * site (V:CvSiteEvaluationClasses.cpp:1141-1153), the target of an attack (V:CvMilitaryAI.cpp:1233-1250), the economic value of a city (V:CvCity.cpp:7928, 7941) and the
 * plot a city buys (V:CvCity.cpp:28295). Every number is worked out from the cited lines: a resource with `total` copies on the map, `owned` of them owned by the civilization.
 */
@RunWith(GdxTestRunner::class)
class MonopolyAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var cityA: City
    private lateinit var cityB: City
    private var freeTiles = emptyList<Tile>()
    private var used = 0

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        a = game.addCiv()
        b = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        cityA = game.addCity(a, game.getTile(HexCoord(0, 0)))
        cityB = game.addCity(b, game.getTile(HexCoord(6, 0)))
        freeTiles = game.tileMap.values
            .filter { it.aerialDistanceTo(cityA.getCenterTile()) > 4 && it.aerialDistanceTo(cityB.getCenterTile()) > 4 }
            .sortedBy { it.position.x * 100 + it.position.y }
    }

    private fun resource(type: ResourceType): TileResource = game.createResource().apply {
        resourceType = type
        isMonopoly = true
    }

    /** [copies] tiles far from both cities carry [resource] */
    private fun place(resource: TileResource, copies: Int) {
        repeat(copies) {
            val tile = freeTiles[used++]
            tile.setTileResource(resource.name)
            if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount = 1
        }
    }

    private fun give(civ: Civilization, resource: TileResource, copies: Int) {
        civ.getCapital()!!.cityConstructions.addBuilding(game.createBuilding("Provides [$copies] [${resource.name}]"))
        civ.cache.updateCivResources()
    }

    //region The plot build score

    @Test
    fun `a build that takes us over half of the copies gives 2000 to the secondary score`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 10)
        give(a, luxury, 4)
        // 4 of 10 now; the plot's 1 copy makes 50: not above the threshold, and none of our tiles has it: nothing
        assertEquals(0, MonopolyAi.plotBuildBonus(a, luxury, 0, 1).secondary)
        assertEquals(0, MonopolyAi.plotBuildBonus(a, luxury, 0, 1).potential)
        // 2 copies make 60 percent: V:CvBuilderTaskingAI.cpp:4116-4120
        val gain = MonopolyAi.plotBuildBonus(a, luxury, 0, 2)
        assertEquals(2000, gain.secondary)
        assertEquals(0, gain.potential)
        // the copies other builds will connect count as owned now
        assertEquals(2000, MonopolyAi.plotBuildBonus(a, luxury, 1, 1).secondary)
    }

    @Test
    fun `a strategic resource gives 1000 for a strategic monopoly and 1000 more to the potential only while a global one is reachable`() {
        val strategic = resource(ResourceType.Strategic)
        place(strategic, 10)
        give(a, strategic, 1)
        // 10 percent now, 40 with 3 copies: over the strategic 25, under the global 50 (V:CvBuilderTaskingAI.cpp:4121-4127)
        val strategicGain = MonopolyAi.plotBuildBonus(a, strategic, 0, 3)
        assertEquals(1000, strategicGain.secondary)
        assertEquals("none of our tiles has it, so no global monopoly is in reach", 0, strategicGain.potential)
        // 30 percent now: already a strategic monopoly, 40 later and still reachable as a strategic one (4133)
        give(a, strategic, 2)
        val reachable = MonopolyAi.plotBuildBonus(a, strategic, 0, 1)
        assertEquals(0, reachable.secondary)
        assertEquals(1000, reachable.potential)
        // 60 percent now: a global monopoly already, the build changes nothing
        give(a, strategic, 3)
        assertEquals(0, MonopolyAi.plotBuildBonus(a, strategic, 0, 1).secondary + MonopolyAi.plotBuildBonus(a, strategic, 0, 1).potential)
    }

    @Test
    fun `a luxury the World Congress banned and a resource that is no monopoly give nothing`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 10)
        // 6 copies of 10 from the plots: 60 percent
        assertEquals(2000, MonopolyAi.plotBuildBonus(a, luxury, 0, 6).secondary)
        val league = game.gameInfo.league ?: League().also { game.gameInfo.league = it }
        val ban = ActiveResolution(league.activeResolutions.size + 1, "Ban Luxury", null, luxury.name, game.gameInfo.turns)
        league.activeResolutions.add(ban)
        league.setTransients(game.gameInfo)
        LeagueEffects.onEnact(league, ban)
        assertEquals("V:CvBuilderTaskingAI.cpp:4074 skips a banned luxury", 0, MonopolyAi.plotBuildBonus(a, luxury, 0, 6).secondary)
        league.activeResolutions.remove(ban)
        league.setTransients(game.gameInfo)
        LeagueEffects.onRepeal(league, ban)
        luxury.isMonopoly = false
        assertEquals("not a monopoly resource", 0, MonopolyAi.plotBuildBonus(a, luxury, 0, 6).secondary)
    }

    //endregion
    //region The city site

    @Test
    fun `a plot that brings the share of the copies to 30 percent doubles its tradeable value`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 10)
        give(a, luxury, 2)
        // (1 + 2) x 100 / 10 = 30: V:CvSiteEvaluationClasses.cpp:1143-1147
        assertEquals(2, MonopolyAi.siteMultiplier(a, luxury, 1))
        // 20 percent
        assertEquals(1, MonopolyAi.siteMultiplier(a, luxury, 0))
        // a resource that is on no tile has no share
        val unplaced = resource(ResourceType.Luxury)
        assertEquals(1, MonopolyAi.siteMultiplier(a, unplaced, 1))
    }

    @Test
    fun `without monopoly resources in the rules the site and the attack keep their numbers`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 10)
        give(a, luxury, 5)
        luxury.isMonopoly = false
        assertFalse(game.ruleset.hasMonopolyResources)
        assertEquals(1, MonopolyAi.siteMultiplier(a, luxury, 5))
        cityB.getCenterTile().setTileResource(luxury.name)
        assertEquals(100, MonopolyAi.attackDesirabilityPercent(a, cityB))
        assertFalse(MonopolyAi.wouldGainMonopoly(a, luxury, 5))
    }

    //endregion
    //region The target of an attack

    @Test
    fun `a city with a resource of which we own a share is 25 percent more desirable`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 9)
        cityB.getCenterTile().setTileResource(luxury.name)
        // total 10 copies, we own none: V:CvMilitaryAI.cpp:1241-1247 wants more than 0 percent
        assertEquals(100, MonopolyAi.attackDesirabilityPercent(a, cityB))
        give(a, luxury, 1)
        assertEquals(125, MonopolyAi.attackDesirabilityPercent(a, cityB))
        // a resource on no tile of the city does not count
        cityB.getCenterTile().setTileResource(null as String?)
        assertEquals(100, MonopolyAi.attackDesirabilityPercent(a, cityB))
    }

    //endregion
    //region The economic value of a city and the plots a city buys

    @Test
    fun `the copies that give us a monopoly make a luxury worth 600 per happiness in the economic value of a city`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 9)
        val tile = game.getTile(HexCoord(5, 0))
        tile.setTileResource(luxury.name)
        assertEquals("setup: the tile next to the city is its own", cityB, tile.getCity())
        give(b, luxury, 3)
        give(a, luxury, 4)
        // 4 of 10 owned, +1 copy makes 50 percent: not over the threshold; we have some and b has 3, not the 1 of the city: 200 per happiness
        val before = AttackTargetsAi.economicValue(cityB, a)
        give(a, luxury, 1)
        // 5 of 10, +1 copy makes 60 percent: a monopoly (V:CvCity.cpp:7928): 600 per happiness instead of 200, for the 1 copy and the happiness of a luxury
        val after = AttackTargetsAi.economicValue(cityB, a)
        assertEquals(1 * DealResourceValues.luxuryHappiness(a) * (600 - 200), after - before)
    }

    @Test
    fun `the copy that gives a monopoly lowers the cost of the plot a city buys by the resource cost`() {
        val luxury = resource(ResourceType.Luxury)
        place(luxury, 9)
        val tile = game.getTile(HexCoord(2, 0))
        tile.setTileResource(luxury.name)
        give(a, luxury, 4)
        val cost = game.ruleset.ai.define(AiDefine.PLOT_INFLUENCE_RESOURCE_COST)
        val before = cityA.expansion.plotInfluenceScore(tile, 2)
        give(a, luxury, 1)
        // V:CvCity.cpp:28295: 5 + 1 of 10 copies is over 50 percent, 4 + 1 is not
        assertEquals(cost, cityA.expansion.plotInfluenceScore(tile, 2) - before)
        assertTrue("the cost is a reduction", cost < 0)
    }

    //endregion
}
