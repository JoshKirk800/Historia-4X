package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's worker count rules: CvCity::UpdateTerrainImprovementNeed ([CityStrategyAi.terrainImprovementNeed]), the tile improver
 *  city strategies of CityStrategyAIHelpers ([CityStrategyAi]), CvEconomicAI::DisbandExtraWorkers ([EconomicAi.disbandExtraWorkers])
 *  and the worker bonus of CvUnitProductionAI ([UnitProductionAi.checkUnitBuildSanity]), on Brave New World's rules.
 */
@RunWith(GdxTestRunner::class)
class WorkerNeedTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        game.gameInfo.turns = 40 // past the desperate turn of 30
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    private fun city(x: Int, y: Int, population: Int = 1, owner: Civilization = civ): City =
        game.addCity(owner, tile(x, y), initialPopulation = population)

    /** The tiles the need counts for [city]: owned, not the city tile, in its work range */
    private fun ownedTiles(city: City): List<Tile> =
        city.tilesInRange.filter { it.getOwner() == city.civ && !it.isCityCenter() }.toList()

    private fun freeTile(): Tile = game.tileMap.values.first { it.civilianUnit == null && !it.isCityCenter() && !it.isWater }

    /** [count] workers of [owner], each on a tile of its own; a new turn drops the strategy tests' cached worker count */
    private fun workers(count: Int, owner: Civilization = civ) {
        repeat(count) { game.addUnit("Worker", owner, freeTile()) }
        game.gameInfo.turns++
    }

    private fun needing(count: Int, owner: Civilization = civ) {
        owner.ai.citiesNeedingTerrainImprovements = count
    }

    private fun strategy(name: String) = game.ruleset.ai.cityStrategies.first { it.name == name }

    //region UpdateTerrainImprovementNeed

    @Test
    fun `every owned unimproved land tile counts once, a visible resource twice`() {
        val city = city(0, 0)
        val tiles = ownedTiles(city)
        assertTrue("owned ${tiles.size}", tiles.size >= 6)
        assertEquals(tiles.size, CityStrategyAi.terrainImprovementNeed(city))

        tiles[0].tileResource = game.ruleset.tileResources["Wheat"]
        assertEquals(tiles.size + 1, CityStrategyAi.terrainImprovementNeed(city))
    }

    @Test
    fun `the city tile, water, impassable tiles and tiles of other owners do not count`() {
        val city = city(0, 0)
        val before = CityStrategyAi.terrainImprovementNeed(city)
        val tiles = ownedTiles(city)
        game.setTileTerrain(tiles[0].position, Constants.coast)
        game.setTileTerrain(tiles[1].position, Constants.mountain)
        for (t in game.tileMap.values) t.setTerrainTransients()
        assertEquals(before - 2, CityStrategyAi.terrainImprovementNeed(city))

        val foreign = city(6, 0, owner = game.addCiv())
        foreign.expansion.takeOwnership(tiles[2])
        assertEquals("taken by another civilization", before - 3, CityStrategyAi.terrainImprovementNeed(city))
    }

    @Test
    fun `a tile needs a build that applies to it with our technology`() {
        val city = city(0, 0)
        val jungle = ownedTiles(city).first()
        val before = CityStrategyAi.terrainImprovementNeed(city)
        game.setTileTerrainAndFeatures(jungle.position, Constants.grassland, Constants.jungle)
        assertEquals("no way to clear or use a jungle yet", before - 1, CityStrategyAi.terrainImprovementNeed(city))
        civ.tech.addTechnology("Bronze Working")
        assertEquals("Remove Jungle is known", before, CityStrategyAi.terrainImprovementNeed(city))
    }

    @Test
    fun `an improved tile needs nothing, a pillaged one counts once, so does a road below the best route`() {
        val city = city(0, 0)
        val tiles = ownedTiles(city)
        val before = CityStrategyAi.terrainImprovementNeed(city)

        tiles[0].setImprovement("Farm")
        assertEquals(before - 1, CityStrategyAi.terrainImprovementNeed(city))
        tiles[0].improvementIsPillaged = true
        assertEquals(before, CityStrategyAi.terrainImprovementNeed(city))
        tiles[0].improvementIsPillaged = false

        // Road is the best route we can build: a farm with a road needs nothing, a pillaged road does
        civ.tech.addTechnology("The Wheel")
        assertEquals(RoadStatus.Road, civ.tech.getBestRoadAvailable())
        tiles[0].setRoadStatus(RoadStatus.Road, civ)
        assertEquals(before - 1, CityStrategyAi.terrainImprovementNeed(city))
        tiles[0].roadIsPillaged = true
        assertEquals(before, CityStrategyAi.terrainImprovementNeed(city))
        tiles[0].roadIsPillaged = false

        // with railroads known, the road is the old route
        civ.tech.addTechnology("Railroads")
        assertEquals(RoadStatus.Railroad, civ.tech.getBestRoadAvailable())
        assertEquals(before, CityStrategyAi.terrainImprovementNeed(city))
    }

    @Test
    fun `the city AI keeps the need and the owner counts the cities with one at the start of its next turn`() {
        val first = city(0, 0)
        val second = city(0, 4)
        assertEquals(0, first.ai.terrainImprovementNeed)
        first.ai.doTurn()
        second.ai.doTurn()
        assertEquals(CityStrategyAi.terrainImprovementNeed(first), first.ai.terrainImprovementNeed)
        assertTrue(first.ai.terrainImprovementNeed > 0)
        assertEquals("still last turn's count", 0, civ.ai.citiesNeedingTerrainImprovements)

        second.ai.terrainImprovementNeed = 0
        civ.ai.doTurn()
        assertEquals(1, civ.ai.citiesNeedingTerrainImprovements)
    }

    //endregion

    //region City strategies

    @Test
    fun `the data thresholds are Vox Populi's 67, 1 and 125 and a worker is wanted from size 4`() {
        assertEquals(67, strategy("AICITYSTRATEGY_NEED_TILE_IMPROVERS").weightThreshold)
        assertEquals(1, strategy("AICITYSTRATEGY_WANT_TILE_IMPROVERS").weightThreshold)
        assertEquals(125, strategy("AICITYSTRATEGY_ENOUGH_TILE_IMPROVERS").weightThreshold)
        assertEquals(4, game.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_WANT_TILE_IMPROVERS_MINIMUM_SIZE))
    }

    @Test
    fun `need tile improvers compares the workers at 67 percent with the cities needing improvements`() {
        val city = city(0, 0)
        needing(3)
        assertTrue("no worker after the desperate turn", CityStrategyAi.needTileImprovers(city, 67))

        workers(4) // 4 x 67 / 100 = 2, no more than 3
        assertTrue(CityStrategyAi.needTileImprovers(city, 67))
        workers(1) // more than 3 + 1 workers: none needed; 5 x 67 / 100 = 3 would still be short
        assertFalse(CityStrategyAi.needTileImprovers(city, 67))
    }

    @Test
    fun `need tile improvers waits for the desperate turn, scaled by the speed`() {
        val city = city(0, 0)
        needing(1)
        game.gameInfo.turns = 30
        assertFalse(CityStrategyAi.needTileImprovers(city, 67))
        game.gameInfo.turns = 31
        assertTrue(CityStrategyAi.needTileImprovers(city, 67))
    }

    @Test
    fun `a city-state needs no worker once it has one for each city needing improvements`() {
        val state = game.addCiv(cityStateType = "Cultured")
        val city = city(0, 0, owner = state)
        needing(2, state)
        workers(1, state)
        assertTrue(CityStrategyAi.needTileImprovers(city, 67))
        workers(1, state)
        assertFalse(CityStrategyAi.needTileImprovers(city, 67))
        assertTrue(CityStrategyAi.enoughTileImprovers(city, 125))
    }

    @Test
    fun `with few cities needing workers and barbarians to eradicate, a worker needs 6 for each military unit`() {
        val city = city(0, 0)
        needing(2)
        workers(1)
        civ.ai.militaryStrategies["MILITARYAISTRATEGY_ERADICATE_BARBARIANS"] = 1
        // a worker per 6 military units or more: the army is too small to let workers wander
        assertFalse(CityStrategyAi.needTileImprovers(city, 67))
        repeat(7) { game.addUnit("Warrior", civ, tile(1, it - 3)) }
        game.gameInfo.turns++
        assertTrue("1 worker for 7 military units", CityStrategyAi.needTileImprovers(city, 67))
        civ.ai.militaryStrategies.clear()
        game.gameInfo.turns++
        assertTrue("without the strategy the army does not matter", CityStrategyAi.needTileImprovers(city, 67))
    }

    @Test
    fun `want tile improvers wants a worker for each city needing improvements from city size 4`() {
        val small = city(0, 0, 3)
        val big = city(0, 4, 4)
        needing(3)
        assertTrue("no workers at all", CityStrategyAi.wantTileImprovers(small, 1))

        workers(2)
        assertFalse("size 3", CityStrategyAi.wantTileImprovers(small, 1))
        assertTrue("size 4, 2 workers for 3 cities", CityStrategyAi.wantTileImprovers(big, 1))
        workers(1)
        assertFalse("3 workers for 3 cities", CityStrategyAi.wantTileImprovers(big, 1))
    }

    @Test
    fun `enough tile improvers wants 125 workers per hundred cities needing improvements, more with the flavor`() {
        val city = city(0, 0)
        needing(4)
        assertFalse("no workers", CityStrategyAi.enoughTileImprovers(city, 125))
        workers(4)
        assertFalse("400 < 500", CityStrategyAi.enoughTileImprovers(city, 125))
        workers(1)
        assertTrue("500 >= 500", CityStrategyAi.enoughTileImprovers(city, 125))
        assertFalse("a higher threshold (the TileImprovement flavor)", CityStrategyAi.enoughTileImprovers(city, 126))

        city.ai.cityStrategies["AICITYSTRATEGY_NEED_TILE_IMPROVERS"] = 1
        assertFalse("the city needs workers", CityStrategyAi.enoughTileImprovers(city, 125))
    }

    //endregion

    //region DisbandExtraWorkers

    /**
     *  Four cities with their tiles improved when [improved], a worker in each city and [extra] more in the open, on turn 40.
     *  (A tile holds one civilian unit, so a city holds one worker: the original's idle workers - those standing in cities - can only
     *  outnumber our cities with workers in the cities of others.)
     */
    private fun empire(improved: Boolean, extra: Int) {
        for ((x, y) in listOf(0 to 0, 4 to 0, 0 to 4, -4 to 0)) city(x, y)
        if (improved)
            for (city in civ.cities) for (tile in ownedTiles(city)) tile.setImprovement("Farm")
        for (city in civ.cities) game.addUnit("Worker", civ, city.getCenterTile())
        repeat(extra) { game.addUnit("Worker", civ, freeTile()) }
        game.gameInfo.turns = 40
    }

    /** A worker of ours inside a city of another civilization (where the original lets units wait too) */
    private fun workerInForeignCity(x: Int = 6, y: Int = 0) {
        val center = city(x, y, owner = game.addCiv()).getCenterTile()
        val worker = game.addUnit("Worker", civ, null)
        worker.currentTile = center
        center.placeUnit(worker)
    }

    private fun landWorkers() = civ.units.getCivUnits().count { it.name == "Worker" }

    @Test
    fun `no worker is scrapped while at most as many workers as cities stand in a city`() {
        empire(improved = true, extra = 4)
        assertEquals(8, landWorkers())
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(8, landWorkers())
        assertEquals(-1, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `with the land improved and more idle workers than cities one worker is scrapped and the strategies lock out`() {
        empire(improved = true, extra = 4)
        workerInForeignCity()
        assertEquals(9, landWorkers())
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(8, landWorkers())
        assertEquals(40, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `workers are kept until more than 66 percent of the improvable tiles are improved`() {
        empire(improved = false, extra = 4)
        workerInForeignCity()
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(9, landWorkers())
        assertEquals(-1, civ.ai.economic.lastTurnWorkerDisbanded)

        val improvable = civ.cities.flatMap { city -> city.getTiles().filter { !it.isCityCenter() }.toList() }.distinct()
        var improved = 0
        for (tile in improvable) {
            tile.setImprovement("Farm")
            improved++
            civ.ai.economic.disbandExtraWorkers(civ)
            if (100 * improved / improvable.size > 66) break
            assertEquals("${100 * improved / improvable.size}% improved", 9, landWorkers())
        }
        assertEquals(8, landWorkers())
        assertEquals(40, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `a pillaged improvement is not an improved tile`() {
        empire(improved = true, extra = 4)
        workerInForeignCity()
        for (city in civ.cities) for (tile in ownedTiles(city)) tile.improvementIsPillaged = true
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(9, landWorkers())
    }

    @Test
    fun `workers are kept in the early game, with fewer than 4 cities founded, until turn 101`() {
        for ((x, y) in listOf(0 to 0, 4 to 0, 0 to 4)) city(x, y)
        for (city in civ.cities) for (tile in ownedTiles(city)) tile.setImprovement("Farm")
        for (city in civ.cities) game.addUnit("Worker", civ, city.getCenterTile())
        repeat(4) { game.addUnit("Worker", civ, freeTile()) }
        workerInForeignCity()
        game.gameInfo.turns = 100
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(8, landWorkers())
        game.gameInfo.turns = 101
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(7, landWorkers())
        assertEquals(101, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `a lone worker is never scrapped`() {
        for ((x, y) in listOf(0 to 0, 4 to 0, 0 to 4, -4 to 0)) city(x, y)
        for (city in civ.cities) for (tile in ownedTiles(city)) tile.setImprovement("Farm")
        workerInForeignCity()
        civ.ai.economic.disbandExtraWorkers(civ)
        assertEquals(1, landWorkers())
        assertEquals(-1, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    //endregion

    //region DisbandExtraWorkboats

    /** [cities] cities of ours (turn 40) and [boats] work boats on coast plots of their own, away from the cities' water with resources */
    private fun fleet(cities: Int, boats: Int) {
        for ((x, y) in listOf(0 to 0, 4 to 0, 0 to 4, -4 to 0).take(cities)) city(x, y)
        repeat(boats) {
            val water = tile(6, -it)
            game.setTileTerrain(water.position, Constants.coast)
            game.addUnit("Work Boats", civ, water)
        }
        game.gameInfo.turns = 40
    }

    private fun boats() = civ.units.getCivUnits().count { it.name == "Work Boats" }

    /** A coast plot of the city at (0,0) with Fish that the civilization owns, unimproved */
    private fun fishPlot(x: Int, y: Int): Tile {
        game.setTileTerrain(HexCoord(x, y), Constants.coast)
        tile(x, y).setTileResource("Fish")
        assertEquals(civ, tile(x, y).getOwner())
        return tile(x, y)
    }

    @Test
    fun `more boats than unimproved water resources scraps one and sets the lock-out`() {
        fleet(4, 2)
        fishPlot(1, 0)
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(1, boats())
        assertEquals(40, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `as many boats as unimproved water resources keeps them all`() {
        fleet(4, 2)
        fishPlot(1, 0)
        fishPlot(0, 1)
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(2, boats())
        assertEquals(-1, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `an improved water resource is not counted`() {
        fleet(4, 1)
        fishPlot(1, 0).setImprovement("Fishing Boats")
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(0, boats())
    }

    @Test
    fun `no boat goes while as many cities need naval tile improvement as there are boats`() {
        fleet(4, 2)
        for (city in civ.cities.take(2)) city.ai.cityStrategies["AICITYSTRATEGY_NEED_NAVAL_TILE_IMPROVEMENT"] = 1
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(2, boats())
        civ.cities.first().ai.cityStrategies.remove("AICITYSTRATEGY_NEED_NAVAL_TILE_IMPROVEMENT")
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(1, boats())
    }

    @Test
    fun `boats are kept in the early game, with fewer than 4 cities founded, until turn 101`() {
        fleet(3, 2)
        civ.citiesCreated = 3
        game.gameInfo.turns = 100
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(2, boats())
        game.gameInfo.turns = 101
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(1, boats())
        assertEquals(101, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `a boat being trained counts as one, and the lock-out is set even when there is none to scrap`() {
        fleet(4, 0)
        civ.cities.first().cityConstructions.setCurrentConstruction("Work Boats")
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(0, boats())
        assertEquals(40, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    @Test
    fun `no boats, nothing is done`() {
        fleet(4, 0)
        civ.ai.economic.disbandExtraWorkboats(civ)
        assertEquals(-1, civ.ai.economic.lastTurnWorkerDisbanded)
    }

    //endregion

    //region The worker bonus of unit production

    @Test
    fun `a city that wants workers adds 200 for each missing one and 500 when it needs them, tripled over two missing`() {
        val city = city(0, 0)
        city.cityConstructions.addBuilding("Monument")
        if (city.cityConstructions.getBuiltBuildings().count() < 2) city.cityConstructions.addBuilding("Shrine")
        civ.addGold(100)
        // more land units than twice the cities: no penalty for a civilian land unit
        repeat(3) { game.addUnit("Warrior", civ, tile(1, it + 3)) }
        civ.ai.military.updateBaseData(civ)
        civ.ai.military.updateDefenseState(civ)
        val worker = game.ruleset.units["Worker"]!!
        fun sanity() = ProductionSanityAi.unitSanity(city, worker, 100, false)

        needing(1) // 1 + 1 - 0 = 2 missing, not tripled
        val plain = sanity()
        assertTrue("worker allowed: $plain", plain > 0)
        city.ai.cityStrategies["AICITYSTRATEGY_WANT_TILE_IMPROVERS"] = 1
        assertEquals(plain + 200 * 2, sanity())
        city.ai.cityStrategies["AICITYSTRATEGY_NEED_TILE_IMPROVERS"] = 1
        assertEquals(plain + 200 * 2 + 500 * 2, sanity())

        needing(3) // 4 missing, x3
        assertEquals(plain + (200 + 500) * 4 * 3, sanity())

        city.ai.cityStrategies["AICITYSTRATEGY_ENOUGH_TILE_IMPROVERS"] = 1
        assertTrue("enough workers: refused", sanity() <= 0)
    }

    //endregion
}
