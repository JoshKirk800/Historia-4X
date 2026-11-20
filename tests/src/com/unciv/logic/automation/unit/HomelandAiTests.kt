package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.ExplorationPlotSets
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.battle.BattleConstants
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvHomelandAI's military moves against the DLL formulas (Brave New World data): the priority list with flavors, which unit garrisons
 *  which city, the weight of sentry points, the explorers' plot values and targets, and the movement part of upgrades.
 */
@RunWith(GdxTestRunner::class)
class HomelandAiTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16, Constants.grassland)
        civ = neutralCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun neutralCiv(): Civilization {
        val created = game.addCiv()
        for (flavor in Flavor.entries) created.ai.setPersonalityFlavor(flavor, 0)
        return created
    }

    /** The tile `y` steps from the middle along a straight line: distances are |a - b| */
    private fun tile(y: Int): Tile = game.tileMap[0, y]

    private fun see() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun run(vararg units: MapUnit, cache: AutomationCache = AutomationCache()): AutomationCache {
        see()
        HomelandAi.doTurn(civ, units.toList(), cache)
        return cache
    }

    /** A policy that encourages garrisons puts the garrison move above the sentry move, so the tests of garrisons need no sentry points to be rid of */
    private fun encourageGarrisons() {
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Oligarchy"]!!)
    }

    private fun distance(unit: MapUnit, tile: Tile) = unit.getTile().aerialDistanceTo(tile)

    // region Priorities

    @Test
    fun `without flavors the moves run in the order of the base priorities, equal ones in the original's enum order`() {
        val order = HomelandAi.priorities(civ).map { it.move to it.priority }
        assertEquals(listOf(
            HomelandMove.AncientRuins to 40, HomelandMove.GarrisonCityState to 40,
            HomelandMove.Explore to 35, HomelandMove.ExploreSea to 35,
            HomelandMove.Heal to 30, HomelandMove.ToSafety to 30,
            HomelandMove.Upgrade to 25, HomelandMove.Airlift to 24, HomelandMove.Sentry to 20, HomelandMove.MobileReserve to 15,
            HomelandMove.Garrison to 10, HomelandMove.Patrol to 0), order)
    }

    @Test
    fun `flavors are added to the priorities, defense and recon scaled by 0_3 and truncated`() {
        civ.ai.setPersonalityFlavor(Flavor.Defense, 7)   // 2.1 -> 2: garrison, heal, to safety, reserve, sentry
        civ.ai.setPersonalityFlavor(Flavor.Recon, 20)    // 6: explore and goody huts
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 9) // 9, not scaled
        civ.ai.setPersonalityFlavor(Flavor.Offense, 20)  // read by the original but added to no move
        val priorities = HomelandAi.priorities(civ).associate { it.move to it.priority }
        assertEquals(12, priorities[HomelandMove.Garrison])
        assertEquals(32, priorities[HomelandMove.Heal])
        assertEquals(32, priorities[HomelandMove.ToSafety])
        assertEquals(17, priorities[HomelandMove.MobileReserve])
        assertEquals(22, priorities[HomelandMove.Sentry])
        assertEquals(41, priorities[HomelandMove.Explore])
        assertEquals(46, priorities[HomelandMove.AncientRuins])
        assertEquals(34, priorities[HomelandMove.Upgrade])
        assertEquals(0, priorities[HomelandMove.Patrol])
        assertEquals(HomelandMove.AncientRuins, HomelandAi.priorities(civ).first().move)
    }

    @Test
    fun `a policy that encourages garrisons puts the garrison one above the sentry`() {
        assertTrue(HomelandAi.priorities(civ).indexOfFirst { it.move == HomelandMove.Garrison } >
            HomelandAi.priorities(civ).indexOfFirst { it.move == HomelandMove.Sentry })
        encourageGarrisons()
        val priorities = HomelandAi.priorities(civ).associate { it.move to it.priority }
        assertEquals(priorities[HomelandMove.Sentry]!! + 1, priorities[HomelandMove.Garrison])
    }

    // endregion
    // region Garrison

    @Test
    fun `an exposed city is garrisoned before a sheltered one, which only counts for a policy that rewards garrisons`() {
        val sheltered = game.addCity(civ, tile(0))
        val exposed = game.addCity(civ, tile(10))
        // every plot three tiles from the sheltered city is ours, most of those around the other one are nobody's
        for (plot in tile(0).getTilesAtDistance(3)) plot.setOwningCity(sheltered)
        val unit = game.addUnit("Archer", civ, tile(5))
        see()
        assertEquals("without a policy that rewards garrisons only the exposed city is a target", listOf(exposed), HomelandAi.findTargets(civ, AutomationCache()).cities.map { it.city })
        encourageGarrisons()
        val targets = HomelandAi.findTargets(civ, AutomationCache()).cities
        assertEquals(listOf(exposed, sheltered), targets.map { it.city })
        assertEquals(listOf(70, 30), targets.map { it.weight })
        run(unit)
        assertTrue("it set out for the exposed city", distance(unit, exposed.getCenterTile()) < 5)
        assertTrue(unit.isMoving())
    }

    @Test
    fun `one unit garrisons a city per turn and a unit already inside fortifies there`() {
        val city = game.addCity(civ, tile(0))
        val inside = game.addUnit("Archer", civ, tile(0))
        val other = game.addUnit("Archer", civ, tile(3))
        encourageGarrisons()
        val cache = run(inside, other)
        assertEquals(tile(0), inside.getTile())
        assertTrue(inside.isFortified())
        assertTrue(inside in cache.processedUnits)
        assertEquals(game.gameInfo.turns, civ.ai.tactical.lastTurnGarrisonAssigned(city))
        assertNotEquals("the second unit is not sent to a city with a garrison", city.getCenterTile(), other.getTile())
        assertFalse(other.isMoving())
    }

    @Test
    fun `a ranged unit garrisons before a melee unit when it is not farther away`() {
        val city = game.addCity(civ, tile(0))
        val melee = game.addUnit("Warrior", civ, tile(-2))
        val ranged = game.addUnit("Archer", civ, tile(2))
        encourageGarrisons()
        run(melee, ranged)
        assertEquals(city.getCenterTile(), ranged.getTile())
        assertNotEquals("the warrior was left to other moves", city.getCenterTile(), melee.getTile())
    }

    @Test
    fun `a closer melee unit garrisons before a ranged unit that needs longer`() {
        val city = game.addCity(civ, tile(0))
        val melee = game.addUnit("Warrior", civ, tile(1))
        val ranged = game.addUnit("Archer", civ, tile(6))
        encourageGarrisons()
        run(melee, ranged)
        assertEquals(city.getCenterTile(), melee.getTile())
        assertNotEquals(city.getCenterTile(), ranged.getTile())
    }

    @Test
    fun `a unit with promotion strength bonuses is not used to garrison`() {
        val city = game.addCity(civ, tile(0))
        val promoted = game.addUnit("Warrior", civ, tile(1))
        promoted.promotions.addPromotion("Survivalism I", true)   // +25% Strength when defending
        val plain = game.addUnit("Warrior", civ, tile(4))
        encourageGarrisons()
        run(promoted, plain)
        assertNotEquals(city.getCenterTile(), promoted.getTile())
        assertTrue("the plain unit, farther away, set out", distance(plain, city.getCenterTile()) < 4)
    }

    @Test
    fun `a unit that cannot reach the city is passed over for one that can`() {
        val city = game.addCity(civ, tile(0))
        val stranded = game.addUnit("Warrior", civ, tile(-3)) // the nearest, but walled in by water
        for (neighbor in tile(-3).neighbors) game.setTileTerrain(neighbor.position, Constants.ocean)
        val reachable = game.addUnit("Warrior", civ, tile(7))
        encourageGarrisons()
        run(stranded, reachable)
        assertEquals(tile(-3), stranded.getTile())
        assertTrue(distance(reachable, city.getCenterTile()) < 7)
        assertTrue(reachable.isMoving())
    }

    @Test
    fun `a city the tactical AI garrisoned this turn gets no unit`() {
        val open = game.addCity(civ, tile(10))
        val unit = game.addUnit("Archer", civ, tile(5))
        encourageGarrisons()
        see()
        civ.ai.tactical.setLastTurnGarrisonAssigned(open, game.gameInfo.turns)
        run(unit)
        assertFalse("no city was left to garrison", unit.isMoving())
        assertNotEquals(open.getCenterTile(), unit.getTile())
    }

    @Test
    fun `the city of a city-state needs a garrison, which is the tactical AI's, so it is no homeland target`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(0))
        for (tile in game.tileMap.values) tile.setExplored(cityState, true)
        cityState.viewableTiles = game.tileMap.values.toSet()
        cityState.ai.invalidateTurnCaches()
        // CvMilitaryAI::IsExposedToEnemy: city-states assume all, so CvCity::NeedsGarrison is true (it is not the early expansion phase)
        assertTrue(HomelandAi.findTargets(cityState, AutomationCache()).cities.isEmpty())
    }

    // endregion
    // region Sentry points

    @Test
    fun `the score of a sentry point is the defense modifier plus 23 for each plot two tiles away that it sees`() {
        see()
        val flat = tile(-9)
        val defense = civ.ai.tactical.map.defenseModifier(flat)
        // grassland all around: the 12 plots at distance 2 are all in sight
        assertEquals(defense + 12 * 23, HomelandAi.sentryScore(civ, flat))
        // an ocean plot at distance 2 is passable terrain here, a mountain is not
        val mountain = game.setTileTerrain(HexCoord(0, -11), Constants.mountain)
        see()
        val seesMountain = tile(-9)
        assertTrue(mountain.aerialDistanceTo(seesMountain) == 2)
        assertEquals(civ.ai.tactical.map.defenseModifier(seesMountain) + 11 * 23, HomelandAi.sentryScore(civ, seesMountain))
        val hills = game.setTileTerrain(HexCoord(0, -6), Constants.hill)
        see()
        assertEquals("hills add their defense modifier", civ.ai.tactical.map.defenseModifier(hills) + 12 * 23, HomelandAi.sentryScore(civ, hills))
    }

    @Test
    fun `only a point with a score above 30 is a sentry point, in or next to our land and not past an impassable plot`() {
        val city = game.addCity(civ, tile(0))
        see()
        val points = HomelandAi.findTargets(civ, AutomationCache()).sentryPoints
        assertTrue(points.isNotEmpty())
        for (point in points) {
            assertTrue("${point.tile.position}", point.weight > 30)
            assertEquals(point.weight, HomelandAi.sentryScore(civ, point.tile))
            val owner = point.tile.getOwner()
            assertTrue(owner == civ || (owner == null && point.tile.neighbors.any { !it.isWater && it.getOwner() == civ }))
        }
        assertTrue("land inside our land that borders nobody is no sentry point", points.none { it.tile.getOwner() == civ && it.tile.neighbors.all { n -> n.getOwner() == civ } })
        assertTrue(city.getCenterTile() !in points.map { it.tile })
    }

    @Test
    fun `sentry points are sorted by weight and no two of them are adjacent`() {
        game.addCity(civ, tile(0))
        game.addUnit("Warrior", enemy, tile(6))
        see()
        val points = HomelandAi.findTargets(civ, AutomationCache()).sentryPoints
        assertTrue(points.size > 1)
        assertEquals(points.sortedByDescending { it.weight }.map { it.tile }, points.map { it.tile })
        for (a in points) for (b in points) if (a !== b) assertNotEquals(1, a.tile.aerialDistanceTo(b.tile))
        // the point a unit stands on (ours) is allowed, one with an enemy on it is not
        assertTrue(points.none { it.tile == tile(6) })
    }

    @Test
    fun `the unit that is sent to a sentry point fortifies there when it arrives`() {
        game.addCity(civ, tile(0))
        see()
        val point = HomelandAi.findTargets(civ, AutomationCache()).sentryPoints.first().tile
        val unit = game.addUnit("Warrior", civ, point.neighbors.first { it.getOwner() == civ })
        val cache = run(unit)
        assertEquals(point, unit.getTile())
        assertTrue(unit.isFortified())
        assertTrue(unit in cache.processedUnits)
    }

    @Test
    fun `a road tile is a mobile reserve point only where the sentry rule did not take it`() {
        game.addCity(civ, tile(0))
        val withWorker = tile(1)
        game.addUnit("Worker", civ, withWorker)
        withWorker.roadStatus = com.unciv.logic.map.tile.RoadStatus.Road
        val empty = tile(-1)
        empty.roadStatus = com.unciv.logic.map.tile.RoadStatus.Road
        see()
        val roads = HomelandAi.findTargets(civ, AutomationCache()).roads.map { it.tile }
        assertEquals(listOf(withWorker), roads)
    }

    // endregion
    // region Exploring

    private fun exploreOnlyAround(center: Tile, radius: Int) {
        for (tile in game.tileMap.values) tile.setExplored(civ, tile.aerialDistanceTo(center) <= radius)
    }

    @Test
    fun `a revealed plot is worth the unrevealed plots next to revealed ones within its sight, by what they are`() {
        exploreOnlyAround(tile(0), 0)
        game.setTileTerrain(tile(1).position, Constants.coast)
        val plots = ExplorationPlotSets.build(civ)
        // range 1: five grassland neighbours x LAND_EXPLORE_TILE_VALUE + the coast x COAST_EXPLORE_TILE_VALUE; the plots two away touch
        // no revealed plot, so the wider ranges add nothing
        assertEquals(5 * 50 + 20, plots.land.getValue(tile(0).zeroBasedIndex, 1))
        assertEquals(5 * 50 + 20, plots.land.getValue(tile(0).zeroBasedIndex, 3))
        assertEquals(1, plots.land.size)
    }

    @Test
    fun `a goody hut within three turns is the explore target over any plot worth exploring`() {
        exploreOnlyAround(tile(0), 3)
        val hut = tile(-2)
        hut.improvement = "Ancient ruins"
        val scout = game.addUnit("Scout", civ, tile(0))
        see()
        val plots = ExplorationPlotSets.build(civ)
        assertEquals(hut, ExplorerAi.bestExploreTarget(scout, 3, ExplorerAi.Reach(scout), plots))
        hut.improvement = null
        assertNotEquals(hut, ExplorerAi.bestExploreTarget(scout, 3, ExplorerAi.Reach(scout), plots))
    }

    @Test
    fun `a hurt explorer stays to heal, unless the danger on its plot takes the heal away`() {
        exploreOnlyAround(tile(0), 0)
        val scout = game.addUnit("Scout", civ, tile(0))
        scout.health = 60
        see()
        assertEquals("it heals 10, which is 7 or more", tile(0), ExplorerAi.bestExploreTarget(scout, 3, ExplorerAi.Reach(scout), ExplorationPlotSets.build(civ)))
        game.addUnit("Warrior", enemy, tile(3))
        see()
        assertTrue("setup: the Warrior hurts it", civ.ai.plotDanger.get(scout) >= 10)
        assertNotEquals(tile(0), ExplorerAi.bestExploreTarget(scout, 3, ExplorerAi.Reach(scout), ExplorationPlotSets.build(civ)))
    }

    @Test
    fun `a builder does not end a turn where more than half its health is in danger, but where less is it does`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        val warrior = game.addUnit("Warrior", enemy, tile(5))
        see()
        // three tiles from the Warrior it can only be hit: 40 of 100 health, the end of the worker's first turn (two tiles) is fine
        assertEquals(BattleConstants.DAMAGE_TO_CIVILIAN_UNIT * 80 / 100, civ.ai.plotDanger.get(worker, tile(2)))
        assertTrue(ExplorerAi.Reach(worker, abortInDanger = true).within(2).any { it.tile == tile(2) && it.pathLength == 1 })
        warrior.destroy()
        game.addUnit("Warrior", enemy, tile(4))
        see()
        // two tiles from it the Warrior walks in and captures the worker
        assertEquals(Int.MAX_VALUE, civ.ai.plotDanger.get(worker, tile(2)))
        assertFalse("no stop at the end of the first turn there", ExplorerAi.Reach(worker, abortInDanger = true).within(2).any { it.tile == tile(2) && it.pathLength == 1 })
        assertTrue(ExplorerAi.Reach(worker, abortInDanger = true).within(2).any { it.tile == tile(-2) && it.pathLength == 1 })
    }

    @Test
    fun `an explorer with nothing left to explore goes back to its own unit AI`() {
        val scout = game.addUnit("Scout", civ, tile(0))
        val warrior = game.addUnit("Warrior", civ, tile(2))
        UnitAiTypes.set(warrior, UnitAiType.EXPLORE)
        run(scout, warrior)
        assertFalse(scout.isDestroyed)
        assertEquals(UnitAiType.FAST_ATTACK, UnitAiTypes.current(scout))
        assertEquals(UnitAiType.ATTACK, UnitAiTypes.current(warrior))
    }

    @Test
    fun `a warrior that is the only explorer roams beyond six plots of the cities, with a Scout exploring it stays near them`() {
        game.addCity(civ, tile(0))
        val warrior = game.addUnit("Warrior", civ, tile(0))
        UnitAiTypes.set(warrior, UnitAiType.EXPLORE)
        see()
        val far = tile(8)
        assertFalse(ExplorerAi.limitedToHome(warrior))
        assertTrue(ExplorerAi.isValidExplorerEndTurnPlot(warrior, far))
        val scout = game.addUnit("Scout", civ, tile(1))
        UnitAiTypes.set(scout, UnitAiType.EXPLORE)
        assertTrue(ExplorerAi.limitedToHome(warrior))
        assertFalse(ExplorerAi.isValidExplorerEndTurnPlot(warrior, far))
        assertNull(ExplorerAi.bestExploreTarget(warrior, 12, ExplorerAi.Reach(warrior), ExplorationPlotSets.build(civ)))
        assertTrue(ExplorerAi.isValidExplorerEndTurnPlot(scout, far))
        // a Scout that stopped exploring is no explorer to leave it to
        UnitAiTypes.set(scout, UnitAiType.FAST_ATTACK)
        assertFalse(ExplorerAi.limitedToHome(warrior))
    }

    // endregion
    // region Upgrades and the rest

    @Test
    fun `the unit next in line to upgrade goes next to our nearest city when it stands outside our territory`() {
        civ.tech.addTechnology("Bronze Working")
        civ.tech.addTechnology("Civil Service")
        val city = game.addCity(civ, tile(0))
        val outside = game.addUnit("Spearman", civ, tile(4))
        assertNotEquals(civ, outside.getTile().getOwner())
        val cache = run(outside)
        assertTrue(outside in cache.processedUnits)
        assertEquals("on its way to a tile beside the city", 1, outside.getMovementDestination().aerialDistanceTo(city.getCenterTile()))
        assertTrue("and the gold for its upgrade is being saved", civ.ai.purchaseSavings.requests.isNotEmpty())
        assertTrue(outside.isMoving() || distance(outside, city.getCenterTile()) == 1)
    }

    @Test
    fun `a unit with nothing to do steps to a neighboring tile of our own territory`() {
        val city = game.addCity(civ, tile(0))
        val garrison = game.addUnit("Archer", civ, tile(0))
        val border = city.getTiles().first { it != city.getCenterTile() && it.neighbors.any { n -> n.getOwner() != civ } }
        val spare = game.addUnit("Archer", civ, border) // ranged: no sentry, and the city has its garrison
        val cache = run(garrison, spare)
        assertTrue(spare in cache.processedUnits)
        assertNotEquals(border, spare.getTile())
        assertEquals(civ, spare.getTile().getOwner())
    }

    @Test
    fun `units that cannot be moved by the homeland AI are not recruited`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        val processed = game.addUnit("Warrior", civ, tile(1))
        val spent = game.addUnit("Warrior", civ, tile(2)).also { it.currentMovement = 0f }
        val free = game.addUnit("Warrior", civ, tile(3))
        val cache = AutomationCache().also { it.processedUnits.add(processed) }
        assertEquals(listOf(free), HomelandAi.recruitUnits(civ, listOf(worker, processed, spent, free), cache))
        assertFalse(worker in cache.processedUnits)
    }

    // endregion
    // region Airlift (CvHomelandAI::PlotAirliftMoves)

    private fun airportCity(y: Int): City = game.addCity(civ, tile(y)).also { it.cityConstructions.addBuilding("Airport") }

    private fun doorsOf(vararg cities: City): Set<Tile> = cities.flatMap { it.getCenterTile().neighbors.toList() }.toSet()

    private fun airliftDestination(unit: MapUnit, vararg cities: City): Boolean = unit.isMoving() && unit.getMovementDestination() in doorsOf(*cities)

    /** The tiles of the sentry points: a unit may walk to a door of a city for a sentry's sake, which is no airlift */
    private fun sentryTiles(): Set<Tile> {
        see()
        return HomelandAi.findTargets(civ, AutomationCache()).sentryPoints.map { it.tile }.toSet()
    }

    private fun airliftedTo(unit: MapUnit, sentries: Set<Tile>, vararg cities: City): Boolean =
        airliftDestination(unit, *cities) && unit.getMovementDestination() !in sentries

    @Test
    fun `with two cities that have airports a spare unit walks to a free tile next to one of them`() {
        val a = airportCity(0)
        val b = airportCity(10)
        val spare = game.addUnit("Warrior", civ, tile(5))
        run(spare)
        assertTrue(airliftDestination(spare, a, b))
    }

    @Test
    fun `one city with an airport is not enough`() {
        val a = airportCity(0)
        val b = game.addCity(civ, tile(10))
        val spare = game.addUnit("Warrior", civ, tile(5))
        val sentries = sentryTiles()
        run(spare)
        assertFalse(airliftedTo(spare, sentries, a, b))
    }

    @Test
    fun `a human player's units are not sent to the airports`() {
        val a = airportCity(0)
        val b = airportCity(10)
        civ.playerType = PlayerType.Human
        val spare = game.addUnit("Warrior", civ, tile(5))
        val sentries = sentryTiles()
        run(spare)
        assertFalse(airliftedTo(spare, sentries, a, b))
    }

    @Test
    fun `a defender at an airport city's door stays, finished for the turn, and the others go to the free doors`() {
        val a = airportCity(0)
        val b = airportCity(10)
        val door = a.getCenterTile().neighbors.first()
        val guard = game.addUnit("Warrior", civ, door)
        val spare = game.addUnit("Warrior", civ, tile(5))
        val cache = run(guard, spare)
        assertEquals(door, guard.getTile())
        assertEquals(0f, guard.currentMovement)
        assertTrue(guard in cache.processedUnits)
        assertTrue(airliftDestination(spare, a, b))
        assertNotEquals(door, spare.getMovementDestination())
    }

    @Test
    fun `a defender that the homeland AI does not have is left alone`() {
        val a = airportCity(0)
        airportCity(10)
        val door = a.getCenterTile().neighbors.first()
        val guard = game.addUnit("Warrior", civ, door)
        run(game.addUnit("Warrior", civ, tile(5)))
        assertEquals(guard.getMaxMovement().toFloat(), guard.currentMovement)
    }

    @Test
    fun `the doors of a city the enemy dominates are not origins`() {
        val threatened = airportCity(0)
        val safe = airportCity(-10)
        val around = threatened.getCenterTile().getTilesInDistance(3).filter { it != threatened.getCenterTile() && it.position.y >= 0 }.sortedBy { it.aerialDistanceTo(threatened.getCenterTile()) }
        for (tile in around.take(8)) game.addUnit("Swordsman", enemy, tile)
        val spare = game.addUnit("Warrior", civ, tile(-5))
        see()
        val dominance = civ.ai.tactical.map.zoneByCity(threatened, false)!!.dominance
        assertTrue("the scenario has the enemy ahead: $dominance", dominance != TacticalDominance.Friendly && dominance != TacticalDominance.NoUnitsVisible)
        run(spare)
        assertTrue(airliftDestination(spare, safe))
    }

    // endregion
}
