package com.unciv.logic.automation.unit

import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.automation.diplomacy.DisputeAi
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Decision-level checks of the Vox Populi (Release-5.4.8) CvSiteEvaluatorForSettler::PlotFoundValue, CvPlayer::GetBestSettlePlot and
 *  the settle quality (SettleAreas.kt, CitySiteEvaluator.kt) against the formulas of the DLL with its values (CITY_RING 6/6/3/1/1, SETTLER_FOOD 12,
 *  PRODUCTION 8, GOLD 8, SCIENCE 5, FAITH 4, BUILD_ON_RIVER 35, ...). The expected numbers are worked out by hand from the source in the comments.
 */
@RunWith(GdxTestRunner::class)
class SettleAreasTest {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    }

    /** A hexagonal map of [radius], all [terrain] */
    private fun map(radius: Int, terrain: String = "Plains") {
        game.makeHexagonalMap(radius)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, terrain)
        assignContinents()
    }

    private fun assignContinents() = game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)

    /** A civ whose personality is all zero except [flavors], boldness 5 (the neutral band), that has seen the whole map */
    private fun civ(vararg flavors: Pair<Flavor, Int>, isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer)
        setPersonality(civ, *flavors)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 5)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        civ.viewableTiles = game.tileMap.values.toSet()
        return civ
    }

    private fun setPersonality(civ: Civilization, vararg flavors: Pair<Flavor, Int>) {
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for ((flavor, value) in flavors) civ.ai.setPersonalityFlavor(flavor, value)
    }

    private fun value(civ: Civilization, tile: Tile) = CitySiteEvaluator(civ).plotFoundValue(tile)

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    /** Two Wheat (a bonus resource: +1 food) on plains at the edge of the work range of [site], so that the site has resources enough */
    private fun addWheat(site: Tile, avoidAxis: Boolean = false) {
        val edge = site.getTilesAtDistance(3).filter { !avoidAxis || it.position.y != 0 }.take(2).toList()
        assertEquals(2, edge.size)
        for (tile in edge) {
            tile.setTileResource("Wheat")
        }
    }

    @Test
    fun `flavor multipliers add the flavors to 1, double the single-flavor ones and are normalized to a sum of 100`() {
        map(1)
        val civ = civ(Flavor.Growth to 5, Flavor.Expansion to 3, Flavor.Gold to 2, Flavor.TileImprovement to 4,
            Flavor.Production to 6, Flavor.Wonder to 1, Flavor.Science to 3, Flavor.Happiness to 4, Flavor.Religion to 7)
        // Raw: food 1 + 5 + 3 = 9, production 1 + 6 + 1 = 8, gold 1 + 2 + 4 = 7, science 1 + 2 x 3 = 7, faith 1 + 2 x 7 = 15, happiness 1 + 2 x 4 = 9,
        // resources 10, strategic 10: the sum is 75. int(0.5 + raw / 75 x 100): 12, 11 (11.17), 9 (9.83), 9, 20 (20.5), 12, 13 (13.83), 13
        assertArrayEquals(intArrayOf(12, 11, 9, 9, 20, 12, 13, 13), CitySiteEvaluator.computeFlavorMultipliers(civ))

        // A personality of nothing: every factor 1, resources and strategic 10, the sum is 26: int(0.5 + 3.846) = 4, int(0.5 + 38.46) = 38
        setPersonality(civ)
        assertArrayEquals(intArrayOf(4, 4, 4, 4, 4, 4, 38, 38), CitySiteEvaluator.computeFlavorMultipliers(civ))
    }

    @Test
    fun `a site without resources or special plots is worth nothing and one next to foreign land cannot be founded`() {
        map(6)
        val civ = civ()
        val site = tile(0, 0)
        // Plenty of food and hammers, but fewer than two resources of one kind (or two natural wonders): 0, not -1
        assertEquals(0, value(civ, site))
        addWheat(site)
        assertTrue(value(civ, site) > 0)

        // CanFoundCity: not next to the territory of another player
        val other = civ()
        val city = game.addCity(other, tile(5, 5))
        tile(1, 0).setOwningCity(city)
        assertEquals(-1, value(civ, site))
        // nor on a plot that is owned by another player
        site.setOwningCity(city)
        assertEquals(-1, value(civ, site))
    }

    @Test
    fun `found value sums the best 80 percent of the plots by ring and charges plots worth nothing`() {
        map(6)
        val civ = civ()
        val site = tile(0, 0)
        addWheat(site)
        // Flavors all 0: multipliers 4 for the yields. A plains has 1 food + 1 for the farm one assumes on plains = 2 food, 1 production, so
        // food 2 x 4 x 12 = 96 and production 1 x 4 x 8 = 32 (a Wheat: 3 food, 144); each plot scores -100 + ring x (food + production):
        // ring 0 (1): 28, ring 1 (6): 668, ring 2 (12): 668, ring 3 (3 each: 16 plains 284, 2 Wheat 428).
        // 37 plots: the worst 37 x 20 / 100 = 7 go (28 and six of 284): 10 x 284 + 2 x 428 + 18 x 668 = 15720.
        assertEquals(15720, value(civ, site))
    }

    @Test
    fun `a river adds 35 percent and food far over the hammers costs 20 percent`() {
        map(6)
        val civ = civ()
        val site = tile(0, 0)
        addWheat(site)
        assertEquals(15720, value(civ, site))
        site.hasBottomRiver = true
        site.forgetAdjacentToRiver()
        // The site is next to fresh water now, which its own plot values (ComputeStrategicValue, BALANCE_FRESH_WATER_STRATEGIC_VALUE 5 x
        // the strategic flavor 38 x SETTLER_STRATEGIC_MULTIPLIER 3 = 570): its 28 becomes 598, above six of the 284 that went: the total is
        // 9 x 284 + 2 x 428 + 598 + 18 x 668 = 16034. BUILD_ON_RIVER_PERCENT 35: 16034 x 35 / 100 = 5611
        assertEquals(16034 + 5611, value(civ, site))
        site.hasBottomRiver = false
        site.forgetAdjacentToRiver()

        // Growth 20: food factor 1 + 20 = 21 of 46 (int(0.5 + 45.65) = 46), the others 2 (int(0.5 + 2.17)): food 2 x 46 x 12 = 1104, production 1 x 2 x 8 = 16,
        // Wheat 1656: -100 + ring x ...: 1020, 6620 (18), 3260 (16), 4916 (2): the best 30: 10 x 3260 + 2 x 4916 + 18 x 6620 = 161592.
        // Food 35 x 1104 + 2 x 1656 = 41952 is over 12 x the production 37 x 16 = 592 x 12 = 7104: -20% = -32318
        setPersonality(civ, Flavor.Growth to 20)
        assertEquals(161592 - 32318, value(civ, site))
    }

    @Test
    fun `the coast adds 40 percent, twice for naval flavor over 7, only next to water of 10 or more plots`() {
        map(12)
        val civ = civ()
        val site = tile(0, 0)
        addWheat(site, avoidAxis = true)
        // Water along the east axis: three plots within the work range, then cut off (a body of 3 plots) or continued out of range (a body of 10)
        for (x in 1..3) game.setTileTerrain(HexCoord(x, 0), "Ocean")
        assignContinents()
        val small = value(civ, site)
        assertTrue(small > 0)
        for (x in 4..10) game.setTileTerrain(HexCoord(x, 0), "Ocean")
        assignContinents()
        game.gameInfo.turns++ // the water bodies of a map are kept for the turn
        // The same yields, but the land along the sea is coastal now (a strategic value of its own: BALANCE_COAST_STRATEGIC_VALUE), and the
        // site is next to a body of 10 plots with 3 water plots next to it: the total plot value plus 40% of it
        val coast = value(civ, site)
        assertTrue("coast $coast, no coast $small", coast > small * 140 / 100)
        // NAVAL over 7: a second 40% of the same total, so the total is twice the coast value less the naval one
        setPersonality(civ, Flavor.Naval to 8)
        val naval = value(civ, site)
        val total = 2 * coast - naval
        assertEquals(total * 40 / 100, naval - coast)
        assertEquals(total + total * 40 / 100, coast)
    }

    @Test
    fun `the quality of a plot is its found value against the capitals' reference, 50 to 150 percent mapped to -50 to 50`() {
        map(6)
        val civ = civ()
        val site = tile(0, 0)
        addWheat(site)
        // No capital yet: no reference: 50 (acceptable); no plot: -50
        assertEquals(50, SettleAreas.settlePlotQualityMeasure(civ, site))
        assertEquals(-50, SettleAreas.settlePlotQualityMeasure(civ, null))

        // 54% of the average capital value: 54 x 20000 / (1 x 100) = 10800; the site is worth 15720: 100 x 15720 / 10800 = 145 -> +45
        game.gameInfo.cityFoundValueReference = 20000
        game.gameInfo.numReferenceCities = 1
        assertEquals(10800, SettleAreas.cityQualityReference(game.gameInfo))
        assertEquals(45, SettleAreas.settlePlotQualityMeasure(civ, site))
        // Far above the capitals' (50 to 150 percent is the range) +50, far below -50
        game.gameInfo.cityFoundValueReference = 4000
        assertEquals(50, SettleAreas.settlePlotQualityMeasure(civ, site))
        game.gameInfo.cityFoundValueReference = 400000
        assertEquals(-50, SettleAreas.settlePlotQualityMeasure(civ, site))
    }

    @Test
    fun `the first city of a major civilization joins the quality reference, later ones and city-states do not`() {
        map(6)
        val civ = civ()
        val site = tile(0, 0)
        addWheat(site)
        val expected = value(civ, site)
        assertEquals(15720, expected)
        game.addCity(civ, site)
        assertEquals(15720, game.gameInfo.cityFoundValueReference)
        assertEquals(1, game.gameInfo.numReferenceCities)
        game.addCity(civ, tile(5, 0))
        assertEquals(1, game.gameInfo.numReferenceCities)
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(-5, 0))
        assertEquals(1, game.gameInfo.numReferenceCities)
    }

    @Test
    fun `the minimum acceptable quality is three times the expansion flavor, at most 12`() {
        map(1)
        val civ = civ(Flavor.Expansion to 7)
        assertEquals(-21, SettleAreas.minAcceptableSettleQuality(civ))
        setPersonality(civ, Flavor.Expansion to 20)
        assertEquals(-36, SettleAreas.minAcceptableSettleQuality(civ))
        setPersonality(civ)
        assertEquals(0, SettleAreas.minAcceptableSettleQuality(civ))
    }

    @Test
    fun `the distance scale maps from the maximum settle distance to the dropoff threshold`() {
        // MapToPercent(value, zeroAt = 10, hundredAt = 5): (7 - 10) x 100 / (5 - 10) = 60
        assertEquals(60, SettleAreas.mapToPercent(7, 10, 5))
        assertEquals(100, SettleAreas.mapToPercent(4, 10, 5))
        assertEquals(0, SettleAreas.mapToPercent(12, 10, 5))
        // Equal thresholds: over 100, under 0, else 50
        assertEquals(100, SettleAreas.mapToPercent(6, 5, 5))
        assertEquals(50, SettleAreas.mapToPercent(5, 5, 5))
        assertEquals(0, SettleAreas.mapToPercent(4, 5, 5))
    }

    /** A map of plains with the capital at (-6, -6) on a site of two wheat, and another such site at (-6, 1), 7 plots south of it */
    private fun homeMap(): Civilization {
        map(14)
        val civ = civ()
        addWheat(tile(-6, -6))
        game.addCity(civ, tile(-6, -6))
        civ.viewableTiles = game.tileMap.values.toSet()  // founding the city recomputed the civ's vision
        addWheat(tile(-6, 1))
        return civ
    }

    @Test
    fun `best settle plot scales the found value by the distance to our cities`() {
        val civ = homeMap()
        val scores = HashMap<Tile, Int>()
        val best = SettleAreas.bestSettlePlot(civ, null, null, scores)
        assertNotNull(best)
        // On turn 0 the evaluation distance is 10: no value beyond 10 plots from a city, all of it within 5 (MapToPercent)
        for ((tile, score) in scores) {
            val found = SettleAreas.foundValue(civ, tile)
            val distance = CityDistances.cityDistanceInPlots(civ, tile)
            val scale = SettleAreas.mapToPercent(distance, 10, 5)
            assertTrue("$tile: $distance", scale > 0)
            assertEquals(found * scale / 100, score)
        }
        assertEquals(scores.maxOf { it.value }, scores[best])
        // The second site, 7 plots from the capital: 60% of its found value
        assertTrue(best!!.aerialDistanceTo(tile(-6, 1)) <= 3)
        assertEquals(SettleAreas.foundValue(civ, best) * 60 / 100, scores[best])
    }

    @Test
    fun `a plot within 3 of the target of another found-city operation is not a candidate`() {
        val civ = homeMap()
        val first = game.addUnit("Settler", civ, tile(-6, -4))
        val second = game.addUnit("Settler", civ, tile(-6, -5))
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, first)
        val target = tile(operation.targetTile!!.x, operation.targetTile!!.y)
        assertTrue(target.aerialDistanceTo(tile(-6, 1)) <= 3)
        assertEquals("the best site is the first settler's target", target, SettleAreas.bestSettlePlot(civ, first, operation))
        // Its own operation's target is the second settler's best site as well, unless the operation is another's
        assertEquals(target, SettleAreas.bestSettlePlot(civ, second, operation))
        val other = SettleAreas.bestSettlePlot(civ, second, null)
        assertTrue("the second settler goes elsewhere: $target, $other", other == null || other.aerialDistanceTo(target) > 3)
    }

    @Test
    fun `best settle plot skips plots within 6 of a civilization it promised not to settle near`() {
        val civ = homeMap()
        val neighbor = civ()
        game.addCity(neighbor, tile(6, -6))
        civ.diplomacyFunctions.makeCivilizationsMeet(neighbor)
        addWheat(tile(2, -6))
        game.gameInfo.turns++ // the found values of a turn are kept
        val before = HashMap<Tile, Int>()
        SettleAreas.bestSettlePlot(civ, null, null, before)
        assertTrue("setup: sites within 6 of the neighbor's city", before.keys.any { it.aerialDistanceTo(tile(6, -6)) <= 6 })

        // CvDiplomacyAI::SetPlayerNoSettleRequestAccepted: the plots within 6 of their cities are no-settling for us
        neighbor.getDiplomacyManager(civ)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 100)
        game.gameInfo.turns++
        val after = HashMap<Tile, Int>()
        SettleAreas.bestSettlePlot(civ, null, null, after)
        assertTrue(after.keys.none { it.aerialDistanceTo(tile(6, -6)) <= SettleAreas.PROMISED_NO_SETTLE_RANGE })
        assertTrue(SettleAreas.isPromisedNotToSettle(civ, tile(6, -3)))
        // A war ends the promise
        civ.getDiplomacyManager(neighbor)!!.declareWar()
        assertFalse(SettleAreas.isPromisedNotToSettle(civ, tile(6, -3)))
    }

    @Test
    fun `a settler of the first city founds on a river estuary at once and a city-state in place`() {
        map(12)
        val civ = civ()
        val settler = game.addUnit("Settler", civ, tile(0, 0))
        for (x in 1..10) game.setTileTerrain(HexCoord(x, 0), "Coast") // a body of water of 10 plots: coast
        assignContinents()
        tile(0, 0).hasBottomRiver = true
        tile(0, 0).forgetAdjacentToRiver()
        assertTrue(SettlerAutomation.firstTurnSettlerMoves(GameView(game.gameInfo, civ).getMapUnitView(settler)))
        assertEquals(1, civ.cities.size)
        assertEquals(tile(0, 0), civ.cities.single().getCenterTile())

        val cityState = game.addCiv(cityStateType = "Cultured")
        for (tile in game.tileMap.values) tile.setExplored(cityState, true)
        val csSettler = game.addUnit("Settler", cityState, tile(-5, -3))
        assertTrue(SettlerAutomation.firstTurnSettlerMoves(GameView(game.gameInfo, cityState).getMapUnitView(csSettler)))
        assertEquals(tile(-5, -3), cityState.cities.single().getCenterTile())
    }

    @Test
    fun `a settler of the first city moves to a reachable site more than 10 percent better and keeps its place when none is`() {
        map(8)
        val civ = civ()
        val start = tile(0, 0)
        // Resources only the plots two steps to the east reach: the start itself is worth 0 (cutoffs), (2, 0) is not
        val east = tile(2, 0)
        for (tile in east.getTilesAtDistance(3).filter { it.aerialDistanceTo(start) > 3 }.take(2)) tile.setTileResource("Wheat")
        assertEquals(0, SettleAreas.foundValue(civ, start))
        assertTrue(SettleAreas.foundValue(civ, east) > 0)
        val settler = game.addUnit("Settler", civ, start)
        assertTrue(SettlerAutomation.firstTurnSettlerMoves(GameView(game.gameInfo, civ).getMapUnitView(settler)))
        // It moved to a site worth something (a Settler has 2 movement points: two plains steps) and has no movement left to found
        assertTrue(civ.cities.isEmpty())
        assertTrue(settler.getTile().aerialDistanceTo(start) in 1..2)
        assertTrue(SettleAreas.foundValue(civ, settler.getTile()) > 0)

        // Nothing better to reach (no resources near): founds where it stands
        val other = civ()
        val lone = game.addUnit("Settler", other, tile(-7, -7))
        assertTrue(SettlerAutomation.firstTurnSettlerMoves(GameView(game.gameInfo, other).getMapUnitView(lone)))
        assertEquals(tile(-7, -7), other.cities.single().getCenterTile())
    }

    @Test
    fun `closest city tables tell the nearest city of everybody, of the majors and of one civilization, older cities winning ties`() {
        map(9)
        val first = civ()
        val second = civ()
        val cityState = game.addCiv(cityStateType = "Cultured")
        val firstCity = game.addCity(first, tile(-4, 0))
        val secondCity = game.addCity(second, tile(4, 0))
        val stateCity = game.addCity(cityState, tile(0, 4))
        val gameInfo = game.gameInfo
        val spot = tile(1, 0)
        // plots: first 5, second 3, the city-state 4 (hex distance, wherever the land lies)
        assertEquals(spot.aerialDistanceTo(secondCity.getCenterTile()), CityDistances.closestCityDistanceInPlots(gameInfo, spot))
        assertEquals(spot.aerialDistanceTo(firstCity.getCenterTile()), CityDistances.cityDistanceInPlots(first, spot))
        val nearStateOnly = tile(0, 3)
        assertEquals(1, CityDistances.closestCityDistanceInPlots(gameInfo, nearStateOnly))
        assertEquals(stateCity, CityDistances.closestCityByPathLength(gameInfo, nearStateOnly, majorsOnly = false))
        assertTrue("the majors' table skips the city-state", CityDistances.closestCityDistanceInPlots(gameInfo, nearStateOnly, majorsOnly = true) > 1)
        // a city-state's distance is half the distance to its capital
        assertEquals(nearStateOnly.aerialDistanceTo(stateCity.getCenterTile()) / 2, CityDistances.cityDistanceInPlots(cityState, nearStateOnly))
        // equally far from both: the owner of the plot, else the older city
        val middle = tile(0, 0)
        assertEquals(4, CityDistances.closestCityDistancePathLength(gameInfo, middle, majorsOnly = true))
        assertEquals(firstCity, CityDistances.closestCityByPathLength(gameInfo, middle, majorsOnly = true))
        middle.setOwningCity(secondCity)
        game.gameInfo.turns++ // the tables are rebuilt when the cities or turn change
        assertEquals(secondCity, CityDistances.closestCityByPathLength(gameInfo, middle, majorsOnly = true))
    }

    @Test
    fun `the player who settled by our favorite site is the thief, and a land dispute rises a step`() {
        map(10)
        val civ = civ(Flavor.Expansion to 5)
        val thief = civ()
        game.addCity(civ, tile(-6, 0))
        addWheat(tile(0, 0))
        civ.ai.previousBestSettlePlot = tile(0, 0).zeroBasedIndex
        // Nobody has a city near the favorite: nothing happens, the favorite stays
        assertNull(SettleAreas.playerWhoStoleMyFavoriteCitySite(civ))
        assertEquals(tile(0, 0).zeroBasedIndex, civ.ai.previousBestSettlePlot)

        // The other player settles within 5 of it (and takes it: 2 away), and our path distance to it (6) is less than our boldness 5 + 5
        game.addCity(thief, tile(2, 0))
        game.gameInfo.turns++ // the best plot is searched again
        assertEquals(thief, SettleAreas.playerWhoStoleMyFavoriteCitySite(civ))
        // The favorite is remembered anew, and is no longer by their city: no thief
        assertTrue(civ.ai.previousBestSettlePlot != tile(0, 0).zeroBasedIndex)
        assertNull(SettleAreas.playerWhoStoleMyFavoriteCitySite(civ))

        // A land dispute with no contested plots is None, and the thief's rises a step: Weak (a bold civilization's: Strong)
        civ.ai.previousBestSettlePlot = tile(0, 0).zeroBasedIndex
        game.gameInfo.turns++
        civ.proximity[thief.civID] = Proximity.Close
        DisputeAi.updateLandDisputes(civ, listOf(thief), game.ruleset.ai)
        assertEquals(DisputeLevel.Weak, civ.ai.diplomacy.memoryOf(thief).landDispute)
    }

    @Test
    fun `a tile as far from every city as the no-route distance has no city, and the table does not fail there`() {
        // A hexagonal map 81 plots across: from a city on one edge, the plots on the other are 80 steps away, past VERY_FAR (72 = 2 x 36 turns)
        map(40)
        val civ = civ()
        game.addCity(civ, tile(-40, 0))
        val gameInfo = game.gameInfo
        assertEquals(71, CityDistances.closestCityDistancePathLength(gameInfo, tile(31, 0), majorsOnly = true))
        assertEquals(72, CityDistances.closestCityDistancePathLength(gameInfo, tile(32, 0), majorsOnly = true))
        assertNotNull(CityDistances.closestCityByPathLength(gameInfo, tile(31, 0), majorsOnly = true))
        // Beyond the limit a tile is "no route" (72) and has no city, as in CvDistanceMapByPathLength::Update
        assertEquals(CityDistances.VERY_FAR, CityDistances.closestCityDistancePathLength(gameInfo, tile(40, 0), majorsOnly = true))
        assertEquals(CityDistances.VERY_FAR, CityDistances.cityDistancePathLength(civ, tile(40, 0)))
        assertNull(CityDistances.closestCityByPathLength(gameInfo, tile(40, 0), majorsOnly = true))
        // The distance in plots is not capped
        assertEquals(80, CityDistances.closestCityDistanceInPlots(gameInfo, tile(40, 0)))
    }

    @Test
    fun `a game with no city at all has found values, a best plot and no settle-spot thief`() {
        map(10)
        val civ = civ()
        val other = civ()
        val gameInfo = game.gameInfo
        assertEquals(CityDistances.VERY_FAR, CityDistances.cityDistancePathLength(civ, tile(0, 0)))
        assertNull(CityDistances.closestCityByPathLength(gameInfo, tile(0, 0), majorsOnly = true))
        assertEquals(CityDistances.NO_CITY, CityDistances.closestCityDistanceInPlots(gameInfo, tile(0, 0)))
        assertEquals(-1, SettleAreas.foundValue(civ, tile(0, 0)).coerceAtMost(-1))
        SettleAreas.bestSettlePlot(civ, null, null)
        assertNull(SettleAreas.playerWhoStoleMyFavoriteCitySite(civ))
        DisputeAi.updateLandDisputes(civ, listOf(other), game.ruleset.ai)
        assertEquals(DisputeLevel.None, civ.ai.diplomacy.memoryOf(other).landDispute)
        SettleAreas.haveGoodSettlePlot(civ, -1)
    }

    @Test
    fun `a city-state and a barbarian civilization can be asked for found values and the best plot`() {
        map(10)
        val cityState = game.addCiv(cityStateType = "Cultured")
        val barbarians = game.addBarbarianCiv()
        for (civ in listOf(cityState, barbarians)) {
            for (tile in game.tileMap.values) tile.setExplored(civ, true)
            assertNull(SettleAreas.bestSettlePlot(civ, null, null))
            assertEquals(-1, SettleAreas.foundValue(civ, tile(0, 0)))
        }
        // A city-state with a capital: its distances are half the distance to it, whatever the size
        game.addCity(cityState, tile(0, 0))
        assertEquals(Int.MAX_VALUE, CityDistances.cityDistancePathLength(barbarians, tile(0, 0)))
        assertEquals(0, SettleAreas.mapToPercent(Int.MAX_VALUE, 10, 5))
        assertEquals(100, SettleAreas.mapToPercent(Int.MIN_VALUE, 10, 5))
    }

    @Test
    fun `the Inca check finds Pata-Pata`() {
        map(6)
        val other = civ()
        val inca = civ()
        val site = tile(0, 0)
        addWheat(site)
        game.setTileTerrainAndFeatures(HexCoord(1, 0), "Plains", "Hill")
        game.setTileTerrain(HexCoord(2, 0), "Mountain")
        val plain = value(inca, site)
        assertTrue("setup: a site worth settling", plain > 0)
        assertEquals("nobody owns Pata-Pata yet", plain, value(other, site))
        game.ruleset.tileImprovements["Pata-Pata"]!!.uniqueTo = inca.civName
        assertTrue("the hills next to mountains and the mountains count for its owner", value(inca, site) > plain)
        assertEquals(plain, value(other, site))
    }

    @Test
    fun `a very unhappy empire cannot found a city with the needs model`() {
        for ((happy, unhappy, canFound) in listOf(Triple(20, 10, true), Triple(9, 10, true), Triple(2, 3, false))) {
            val world = NeedsTestWorld()
            NeedsAiScenarios.addEmpireCity(world, world.civ, happy, unhappy)
            val site = world.game.getTile(HexCoord(5, 0))
            val settler = world.game.addUnit("Settler", world.civ, site)
            assertEquals("approval ${world.civ.getApproval()}", canFound, SettleAreas.canFound(settler, site))
        }
    }
}
