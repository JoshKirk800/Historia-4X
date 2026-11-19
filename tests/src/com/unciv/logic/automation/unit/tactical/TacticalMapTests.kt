package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 *  CvTacticalAnalysisMap with Brave New World data (ancient era: tactical range 5, recruit range 4): the strengths of a dominance zone
 *  (Vox Populi's CalculateMilitaryStrengths: a unit's strength x100 and the modifiers of the unit and the zone, a city its strength
 *  as ranged strength), who dominates it (SetOverallDominance), what the zone is worth, and the zones temporary zones make.
 */
@RunWith(BaseTestRunner::class)
class TacticalMapTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City

    private val range = 5

    @Before
    fun setUp() {
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        city = game.addCity(civ, game.tileMap[0, 0], initialPopulation = 1)
    }

    private fun tile(y: Int) = game.tileMap[0, y]
    private fun strength(unit: String) = game.ruleset.units[unit]!!.strength

    private fun map(): TacticalMap {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        return civ.ai.tactical.map
    }

    private fun cityStrength() = CityCombatant(city).getCityStrength() * 100 * city.health / game.ruleset.ai.define(AiDefine.MAX_CITY_HIT_POINTS)
    private fun zone() = map().zoneOf(city.getCenterTile())!!

    @Test
    fun `unclaimed land near a city belongs to that city tactical zone`() {
        val unclaimed = tile(3)
        assertNull(unclaimed.getOwner())
        val tactical = map()
        assertSame(tactical.zoneOf(city.getCenterTile()), tactical.zoneOf(unclaimed))
    }

    /** The enemy melee strength of our city's zone with one unit of [name] [distance] tiles from the city, which is removed again */
    private fun enemyMeleeOf(name: String, distance: Int, health: Int = 100): Int {
        val unit = game.addUnit(name, enemy, tile(distance))
        unit.health = health
        return zone().enemyMeleeStrength.also { unit.destroy() }
    }

    @Test
    fun `the tactical range grows with the era and the dominance margin is Vox Populi's 70 percent`() {
        assertEquals(range, map().tacticalRange)
        assertEquals(70, game.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_DOMINANCE_PERCENTAGE))
    }

    @Test
    fun `the city counts as ranged strength of its side and its hit points, units by their strength x100 and a bonus near the center`() {
        game.addUnit("Warrior", civ, tile(1))
        game.addUnit("Warrior", enemy, tile(3))
        val zone = zone()
        assertEquals(TacticalTerritory.Friendly, zone.territory)
        assertEquals(city, zone.closestCity)
        assertEquals(cityStrength(), zone.friendlyRangedStrength)
        assertEquals("within 3 of the city: +50%", strength("Warrior") * 150, zone.friendlyMeleeStrength)
        assertEquals(strength("Warrior") * 150, zone.enemyMeleeStrength)
        assertEquals(0, zone.enemyRangedStrength)
        assertEquals(1, zone.friendlyLandUnitCount)
        assertEquals(1, zone.enemyLandUnitCount)
    }

    @Test
    fun `a city adds no melee strength, a damaged city adds less`() {
        val full = zone().friendlyRangedStrength
        assertEquals(cityStrength(), full)
        assertEquals(0, zone().friendlyMeleeStrength)
        assertEquals(0, zone().totalFriendlyUnitCount)
        city.health = game.ruleset.ai.define(AiDefine.MAX_CITY_HIT_POINTS) / 2
        assertEquals(full / 2, zone().friendlyRangedStrength)
    }

    @Test
    fun `a unit counts 50 percent more within 3 tiles, 50 percent more with more than 2 moves or range and less when wounded`() {
        val warrior = strength("Warrior") * 100
        assertEquals("no bonus", warrior, enemyMeleeOf("Warrior", 4))
        assertEquals("near the city", warrior * 3 / 2, enemyMeleeOf("Warrior", 3))
        assertEquals("a Horseman has 4 moves", strength("Horseman") * 150, enemyMeleeOf("Horseman", 4))
        assertEquals("both", strength("Horseman") * 200, enemyMeleeOf("Horseman", 3))
        assertTrue("wounds reduce strength", enemyMeleeOf("Warrior", 4, health = 50) < warrior)
    }

    @Test
    fun `a unit in a citadel of its owner counts double more, a pillaged one does not`() {
        val citadel = tile(1)
        citadel.setImprovement("Citadel")
        val swordsman = game.addUnit("Swordsman", civ, citadel)
        assertEquals("+50% near the city, +100% in the citadel", strength("Swordsman") * 250, zone().friendlyMeleeStrength)
        citadel.improvementIsPillaged = true
        assertEquals(strength("Swordsman") * 150, zone().friendlyMeleeStrength)
        swordsman.destroy()
    }

    @Test
    fun `units within the recruit range of a city count in its zone even outside it, those beyond only in their own zone`() {
        game.addUnit("Warrior", enemy, tile(4))
        assertEquals("half of 8 plus the era", 1, zone().enemyLandUnitCount)
        game.addUnit("Warrior", enemy, tile(6))
        val map = map()
        assertEquals(1, map.zoneOf(city.getCenterTile())!!.enemyLandUnitCount)
        val unclaimed = map.zoneOf(tile(6))!!
        assertEquals(TacticalTerritory.NoOwner, unclaimed.territory)
        assertEquals(1, unclaimed.enemyLandUnitCount)
    }

    @Test
    fun `the barbarians are ignored, units of civilizations at war are not`() {
        val barbarians = game.addBarbarianCiv()
        game.addUnit("Warrior", barbarians, tile(1))
        val zone = zone()
        assertEquals(0, zone.enemyMeleeStrength)
        assertEquals(0, zone.totalEnemyUnitCount)
        assertEquals("only the city", TacticalDominance.Friendly, zone.dominance)
        game.addUnit("Warrior", enemy, tile(-1))
        assertEquals(1, zone().totalEnemyUnitCount)
        assertTrue(zone().enemyMeleeStrength > 0)
    }

    @Test
    fun `an enemy unit we cannot see counts half and is counted, those next to far-away plots we see count in full`() {
        game.addUnit("Warrior", enemy, tile(4))
        civ.viewableTiles = emptySet()
        civ.ai.invalidateTurnCaches()
        val unseen = civ.ai.tactical.map.zoneOf(city.getCenterTile())!!
        assertEquals(strength("Warrior") * 50, unseen.enemyMeleeStrength)
        assertEquals(1, unseen.enemyLandUnitCount)

        civ.viewableTiles = setOf(tile(5)) // not ours, so what is next to it counts as seen
        civ.ai.invalidateTurnCaches()
        assertEquals(strength("Warrior") * 100, civ.ai.tactical.map.zoneOf(city.getCenterTile())!!.enemyMeleeStrength)
    }

    @Test
    fun `the plots next to our own plots we see do not count as seen`() {
        game.addUnit("Warrior", enemy, tile(1))
        civ.viewableTiles = setOf(city.getCenterTile()) // ours: no neighbors are added
        civ.ai.invalidateTurnCaches()
        // 100 - 50 (unseen) + 50 (near the city)
        assertEquals(strength("Warrior") * 100, civ.ai.tactical.map.zoneOf(city.getCenterTile())!!.enemyMeleeStrength)
    }

    @Test
    fun `air units add ranged strength only, not counted, to the ranged or over water the naval ranged bucket`() {
        val fighter = game.ruleset.units["Fighter"]!!
        game.addCity(enemy, tile(4))
        game.addUnit("Fighter", enemy, tile(4))
        val land = zone()
        assertEquals("100% of 45 plus 50% for range 8, no bonus 4 tiles out", fighter.rangedStrength * 150, land.enemyRangedStrength)
        assertEquals(0, land.enemyMeleeStrength)
        assertEquals(0, land.totalEnemyUnitCount)

        val sea = city.getCenterTile().neighbors.first()
        game.setTileTerrain(sea.position, "Coast")
        game.addUnit("Carrier", civ, sea)
        game.addUnit("Fighter", civ, sea)
        val water = map().zoneOf(sea)!!
        assertTrue(water.isWater)
        assertEquals("+50% range, +50% near the city", fighter.rangedStrength * 200, water.friendlyNavalRangedStrength)
        assertEquals("the city counts in its water zone as well", cityStrength(), water.friendlyRangedStrength)
        assertEquals("only the carrier is counted", 1, water.totalFriendlyUnitCount)
    }

    @Test
    fun `in a water zone ships count, land units only when they attack at range and at half`() {
        val first = city.getCenterTile().neighbors.first()
        enemy.tech.addTechnology("Optics")
        val second = first.neighbors.first { it != city.getCenterTile() && it.aerialDistanceTo(city.getCenterTile()) == 1 }
        game.setTileTerrain(first.position, "Coast")
        game.setTileTerrain(second.position, "Coast")
        val trireme = game.addUnit("Trireme", enemy, first)
        val warrior = game.addUnit("Warrior", enemy, second)
        val map = map()
        val water = map.zoneOf(first)!!
        assertTrue(water.isWater)
        assertSame(water, map.zoneOf(second))
        assertEquals("10 x100, +50% for 4 moves, +50% near the city", strength("Trireme") * 200, water.enemyNavalStrength)
        assertEquals(1, water.enemyNavalUnitCount)
        assertEquals("the embarked Warrior has no ranged attack", 0, water.enemyLandUnitCount)
        assertEquals(0, water.enemyMeleeStrength)

        warrior.destroy()
        val archer = game.addUnit("Archer", enemy, second)
        val withArcher = map().zoneOf(second)!!
        val archerType = game.ruleset.units["Archer"]!!
        assertEquals("embarked: -50%, near the city +50%", archerType.strength * 100, withArcher.enemyMeleeStrength)
        assertEquals(archerType.rangedStrength * 100, withArcher.enemyRangedStrength)
        assertEquals(1, withArcher.enemyLandUnitCount)
        assertEquals(strength("Trireme") * 200, withArcher.enemyNavalStrength)
        archer.destroy()
        trireme.destroy()
    }

    @Test
    fun `units of a civilization fighting the same enemies are friendly, those of one at peace with everybody are neutral`() {
        game.addUnit("Warrior", enemy, tile(11)) // far away, but it keeps the enemy alive: an army with no city is gone
        val ally = game.addCiv()
        ally.diplomacyFunctions.makeCivilizationsMeet(enemy)
        ally.getDiplomacyManager(enemy)!!.declareWar()
        civ.diplomacyFunctions.makeCivilizationsMeet(ally)
        ally.getDiplomacyManager(civ)!!.hasOpenBorders = true
        val bystander = game.addCiv()
        bystander.diplomacyFunctions.makeCivilizationsMeet(civ)
        bystander.getDiplomacyManager(civ)!!.hasOpenBorders = true
        civ.getDiplomacyManager(bystander)!!.hasOpenBorders = true
        val nearby = city.getCenterTile().getTilesAtDistance(3).filter { it.getOwner() == null }.take(2).toList()
        game.addUnit("Warrior", ally, nearby[0])
        game.addUnit("Warrior", bystander, nearby[1])
        val zone = zone()
        assertEquals(1, zone.friendlyLandUnitCount)
        assertEquals(strength("Warrior") * 150, zone.friendlyMeleeStrength)
        assertEquals(0, zone.totalEnemyUnitCount)
        assertEquals(1, zone.neutralUnitCount)
        assertEquals("a neutral unit adds its own strength only", strength("Warrior") * 150, zone.neutralStrength)
    }


    @Test
    fun `our city alone makes its zone ours, one enemy unit against our city is an even match, a siege is not`() {
        assertEquals(TacticalDominance.Friendly, zone().dominance)
        game.addUnit("Warrior", enemy, tile(6))
        assertEquals("outside the city zone and recruit range", TacticalDominance.Friendly, zone().dominance)
        game.addUnit("Warrior", enemy, tile(3))
        assertEquals("one enemy unit may tip the balance", TacticalDominance.Even, zone().dominance)
        for (tile in freeTilesAroundCity().take(8)) game.addUnit("Swordsman", enemy, tile)
        assertEquals(TacticalDominance.Enemy, zone().dominance)
        assertTrue(map().isInEnemyDominatedZone(city.getCenterTile()))
    }

    /** Free tiles around the city, nearest first */
    private fun freeTilesAroundCity() = city.getCenterTile().getTilesInDistance(3)
        .filter { it != city.getCenterTile() && it.militaryUnit == null }
        .sortedBy { it.aerialDistanceTo(city.getCenterTile()) }.toList()

    private fun dominance(
        territory: TacticalTerritory = TacticalTerritory.Friendly,
        friendlyCount: Int = 3,
        enemyCount: Int = 3,
        percentage: Int = 70,
        setup: DominanceZone.() -> Unit
    ): TacticalDominance {
        val zone = DominanceZone(0, territory, null, 0, false, null)
        zone.friendlyLandUnitCount = friendlyCount
        zone.enemyLandUnitCount = enemyCount
        zone.setup()
        return zone.setOverallDominance(percentage)
    }

    @Test
    fun `a zone where neither side has strength shows no units, where only we have any it is ours, where only they have any it is theirs`() {
        assertEquals(TacticalDominance.NoUnitsVisible, dominance(friendlyCount = 0, enemyCount = 0) {})
        assertEquals("a city counts", TacticalDominance.Friendly, dominance(friendlyCount = 0, enemyCount = 0) { friendlyRangedStrength = 500 })
        assertEquals(TacticalDominance.Friendly, dominance(friendlyCount = 2, enemyCount = 0) { friendlyMeleeStrength = 500 })
        assertEquals("their city alone", TacticalDominance.Enemy,
            dominance(TacticalTerritory.Enemy, friendlyCount = 0, enemyCount = 0) { enemyRangedStrength = 500 })
    }

    @Test
    fun `one enemy unit against one of ours or none is an even match whatever the strengths`() {
        assertEquals(TacticalDominance.Even, dominance(friendlyCount = 1, enemyCount = 1) { friendlyMeleeStrength = 10000; enemyMeleeStrength = 10 })
        assertEquals(TacticalDominance.Even, dominance(friendlyCount = 1, enemyCount = 1) { friendlyMeleeStrength = 10; enemyMeleeStrength = 10000 })
        assertEquals(TacticalDominance.Even, dominance(friendlyCount = 0, enemyCount = 1) { friendlyRangedStrength = 10000; enemyMeleeStrength = 10 })
        assertEquals("two of ours are not weak", TacticalDominance.Friendly,
            dominance(friendlyCount = 2, enemyCount = 1) { friendlyMeleeStrength = 10000; enemyMeleeStrength = 10 })
    }

    @Test
    fun `we dominate with more than 170 percent of their strength, they with more than 170 percent of ours`() {
        assertEquals(TacticalDominance.Even, dominance { friendlyRangedStrength = 1700; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Friendly, dominance { friendlyRangedStrength = 1710; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Even, dominance { friendlyRangedStrength = 1000; enemyRangedStrength = 1700 })
        assertEquals(TacticalDominance.Enemy, dominance { friendlyRangedStrength = 1000; enemyRangedStrength = 1710 })
    }

    @Test
    fun `in enemy land we need 30 percent more to dominate while they do not`() {
        assertEquals(TacticalDominance.Even, dominance(TacticalTerritory.Enemy) { friendlyRangedStrength = 2000; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Friendly, dominance(TacticalTerritory.Enemy) { friendlyRangedStrength = 2010; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Friendly, dominance(TacticalTerritory.Friendly) { friendlyRangedStrength = 2010; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Enemy, dominance(TacticalTerritory.Enemy) { friendlyRangedStrength = 1000; enemyRangedStrength = 1710 })
    }

    @Test
    fun `the margin is at least 10 for us and at most 90 for them, and the ratio is rounded`() {
        assertEquals(TacticalDominance.Friendly, dominance(percentage = 5) { friendlyRangedStrength = 1120; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Even, dominance(percentage = 5) { friendlyRangedStrength = 1100; enemyRangedStrength = 1000 })
        assertEquals(TacticalDominance.Enemy, dominance(percentage = 200) { friendlyRangedStrength = 1000; enemyRangedStrength = 1910 })
        assertEquals(TacticalDominance.Even, dominance(percentage = 200) { friendlyRangedStrength = 1000; enemyRangedStrength = 1900 })
        // 872 / 512 = 170.31 and 873 / 512 = 170.51 percent: the second rounds up to 171
        assertEquals(TacticalDominance.Even, dominance { friendlyRangedStrength = 872; enemyRangedStrength = 512 })
        assertEquals(TacticalDominance.Friendly, dominance { friendlyRangedStrength = 873; enemyRangedStrength = 512 })
    }

    @Test
    fun `melee strength weighs a third more than the other kinds`() {
        val zone = DominanceZone(0, TacticalTerritory.Friendly, null, 0, false, null)
        zone.friendlyMeleeStrength = 900
        zone.friendlyNavalStrength = 100
        zone.friendlyRangedStrength = 10
        zone.friendlyNavalRangedStrength = 1
        assertEquals(1200 + 100 + 10 + 1, zone.overallFriendlyStrength)
        zone.enemyRangedStrength = 1000
        zone.friendlyNavalStrength = 0
        zone.friendlyRangedStrength = 0
        zone.friendlyNavalRangedStrength = 0
        zone.friendlyMeleeStrength = 1300 // 1733 against 1000: more than 170 percent, which 1300 alone would not be
        zone.friendlyLandUnitCount = 3
        zone.enemyLandUnitCount = 3
        assertEquals(TacticalDominance.Friendly, zone.setOverallDominance(70))
    }

    @Test
    fun `the zone of our capital is worth 3 for the city, 2 for the capital, 4 for a visible city, 3 for land and 6 when the enemy dominates`() {
        // no enemy: base (1 + (1 + sqrt 1)) x2 x4 x3
        val base = (1 + (1 + sqrt(1f).toInt())) * 2 * 4 * 3
        assertEquals(base, map().zoneOf(city.getCenterTile())!!.value)

        // the enemy dominates a zone that is ours: x6
        for (tile in freeTilesAroundCity().take(8)) game.addUnit("Swordsman", enemy, tile)
        val zone = map().zoneOf(city.getCenterTile())!!
        assertEquals(TacticalDominance.Enemy, zone.dominance)
        assertEquals(base * 6, zone.value)
    }

    @Test
    fun `zones are sorted by value and an own zone beats unclaimed land`() {
        game.addUnit("Warrior", civ, tile(9))
        val map = map()
        val zones = map.zones
        assertEquals(zones.sortedByDescending { it.value }.map { it.id }, zones.map { it.id })
        assertSame(zones.first(), map.zoneOf(city.getCenterTile()))
    }

    @Test
    fun `unclaimed land is one zone per landmass and counts the units standing in it, at the center of a zone`() {
        game.addUnit("Warrior", civ, tile(9))
        game.addUnit("Warrior", enemy, tile(10))
        val map = map()
        val zone = map.zoneOf(tile(9))!!
        assertEquals(TacticalTerritory.NoOwner, zone.territory)
        assertNull(zone.closestCity)
        assertSame(zone, map.zoneOf(tile(-9)))
        assertEquals("no city to measure from: distance 0, +50%", strength("Warrior") * 150, zone.friendlyMeleeStrength)
        assertEquals(strength("Warrior") * 150, zone.enemyMeleeStrength)
        assertEquals(1, zone.friendlyLandUnitCount)
        assertEquals(1, zone.enemyLandUnitCount)
    }

    @Test
    fun `enemy dominance in open land does not mark it as an enemy city zone`() {
        game.addUnit("Warrior", enemy, tile(9))
        game.addUnit("Warrior", enemy, tile(10))
        val map = map()
        assertEquals(TacticalDominance.Enemy, map.zoneOf(tile(9))!!.dominance)
        assertFalse(map.isInEnemyDominatedZone(tile(9)))
    }

    @Test
    fun `a temporary zone is worth 1000 and takes the unclaimed land near its center until it expires`() {
        civ.ai.tactical.addTemporaryZone(tile(9), TacticalTargetType.BarbarianCamp)
        val map = map()
        val zone = map.zoneOf(tile(9))!!
        assertEquals(TacticalTerritory.TempZone, zone.territory)
        assertEquals(1000, zone.value)
        assertSame(zone, map.zoneOf(tile(9 - range)))
        assertFalse(zone === map.zoneOf(tile(9 - range - 1)))
        assertEquals(zone, map.zones.first())

        val lastTurn = game.gameInfo.turns + game.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS)
        game.gameInfo.turns = lastTurn
        assertTrue("still there on its last turn", map().zones.any { it.territory == TacticalTerritory.TempZone })
        game.gameInfo.turns = lastTurn + 1
        assertTrue("gone after it", map().zones.none { it.territory == TacticalTerritory.TempZone })
    }

    @Test
    fun `a temporary zone on a city makes that city's zone worth 20 times its base instead of 4`() {
        val enemyCity = game.addCity(enemy, tile(9), initialPopulation = 1)
        // base (1 + (1 + 1)) x2 for the enemy capital, x land 3, and x2 for an enemy territory the enemy dominates
        val normal = map().zoneOf(enemyCity.getCenterTile())!!
        assertEquals(TacticalDominance.Enemy, normal.dominance)
        assertEquals(3 * 2 * 4 * 3 * 2, normal.value)

        civ.ai.tactical.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        val map = map()
        assertTrue(civ.ai.tactical.isTemporaryZoneCity(enemyCity))
        assertEquals(3 * 2 * 20 * 3 * 2, map.zoneOf(enemyCity.getCenterTile())!!.value)
        assertTrue("no zone of its own for a city", map.zones.none { it.territory == TacticalTerritory.TempZone })
    }

    @Test
    fun `an enemy zone where only the enemy city counts is dominated by the enemy`() {
        val enemyCity = game.addCity(enemy, tile(9), initialPopulation = 1)
        val map = map()
        val zone = map.zoneOf(enemyCity.getCenterTile())!!
        assertEquals(TacticalTerritory.Enemy, zone.territory)
        assertEquals(TacticalDominance.Enemy, zone.dominance)
        assertTrue(map.isInEnemyDominatedZone(enemyCity.getCenterTile()))
        assertFalse(map.isInEnemyDominatedZone(city.getCenterTile()))
        assertNotNull(map.zoneByCity(enemyCity, false))
    }

    @Test
    fun `an enemy can attack the tiles it can reach in a move and move past those it reaches with movement left`() {
        game.addUnit("Warrior", enemy, tile(6))
        game.addUnit("Warrior", enemy, tile(-1)) // next to our city, which no enemy can ever "attack" by this measure
        val map = map()
        assertTrue(map.isSubjectToAttack(tile(4)))
        assertTrue(map.enemyCanMovePast(tile(5)))
        assertTrue("on the edge of its moves it can attack but not move on", map.isSubjectToAttack(tile(4)) && !map.enemyCanMovePast(tile(4)))
        assertFalse(map.isSubjectToAttack(tile(3)))
        assertFalse("never in our own city", map.isSubjectToAttack(city.getCenterTile()))
    }
}
