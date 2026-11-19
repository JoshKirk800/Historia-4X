package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvTacticalAI::SelectPosture, UpdatePostures and UseThisDominanceZone on zones with chosen strengths and counts */
@RunWith(BaseTestRunner::class)
class PostureSelectionTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var enemyCity: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        game.addCity(civ, game.tileMap[0, -9])
        enemyCity = game.addCity(enemy, game.tileMap[0, 9])
    }

    private val state get() = civ.ai.tactical

    /**
     *  A zone with chosen counts and overall strengths: the ranged strength of the zone's domain as given, the rest of [friendlyStrength]
     *  and [enemyStrength] melee strength (which weighs 4/3)
     */
    private fun zone(
        territory: TacticalTerritory,
        dominance: TacticalDominance,
        isWater: Boolean = false,
        city: City? = null,
        friendlyUnits: Int = 2,
        friendlyRanged: Int = 0,
        enemyUnits: Int = 2,
        friendlyRangedStrength: Int = 100,
        enemyRangedStrength: Int = 100,
        friendlyStrength: Int = 1000,
        enemyStrength: Int = 1000,
    ) = DominanceZone(0, territory, if (territory == TacticalTerritory.NoOwner || territory == TacticalTerritory.TempZone) null else
        if (territory == TacticalTerritory.Friendly) civ else enemy, 0, isWater, city).also {
        it.dominance = dominance
        it.friendlyLandUnitCount = friendlyUnits
        it.friendlyRangedUnitCount = friendlyRanged
        it.enemyLandUnitCount = enemyUnits
        if (isWater) {
            it.friendlyNavalRangedStrength = friendlyRangedStrength
            it.enemyNavalRangedStrength = enemyRangedStrength
        } else {
            it.friendlyRangedStrength = friendlyRangedStrength
            it.enemyRangedStrength = enemyRangedStrength
        }
        it.friendlyMeleeStrength = (friendlyStrength - friendlyRangedStrength).coerceAtLeast(0) * 3 / 4
        it.enemyMeleeStrength = (enemyStrength - enemyRangedStrength).coerceAtLeast(0) * 3 / 4
    }

    private fun select(zone: DominanceZone) = PostureSelection.selectPosture(civ, state, zone)

    @Test
    fun `defend our territory when enemy dominates despite our ranged advantage`() {
        val threatened = zone(TacticalTerritory.Friendly, TacticalDominance.Enemy, city = civ.getCapital(),
            friendlyRanged = 2, friendlyRangedStrength = 1000, enemyRangedStrength = 100)
        assertEquals(TacticalPosture.Hedgehog, select(threatened))
        threatened.dominance = TacticalDominance.Even
        assertEquals(TacticalPosture.Counterattack, select(threatened))
    }

    @Test
    fun `attack a city in danger before withdrawing from a superior enemy`() {
        val threatened = zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity)
        assertEquals(TacticalPosture.Withdraw, select(threatened))
        enemyCity.damageTakenLastTurn = enemyCity.health + 1
        assertEquals(TacticalPosture.SurgicalCityStrike, select(threatened))
    }

    @Test
    fun `withdraw when ranged forces are vulnerable to a melee counterattack`() {
        val exposed = zone(TacticalTerritory.Enemy, TacticalDominance.Friendly, city = enemyCity,
            friendlyRangedStrength = 1000, enemyRangedStrength = 0, friendlyStrength = 1000, enemyStrength = 1000)
        exposed.friendlyMeleeStrength = 100
        exposed.enemyMeleeStrength = 301
        assertEquals(TacticalPosture.Withdraw, select(exposed))
        exposed.enemyMeleeStrength = 300
        assertEquals(TacticalPosture.Steamroll, select(exposed))
    }

    @Test
    fun `choose between attrition flanks and steamroll when enemy zone is even`() {
        val zone = zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity,
            friendlyRangedStrength = 400, enemyRangedStrength = 100)
        zone.friendlyMeleeStrength = 100
        zone.enemyMeleeStrength = 200
        assertEquals(TacticalPosture.AttritFromRange, select(zone))
        zone.enemyMeleeStrength = 100
        assertEquals(TacticalPosture.Steamroll, select(zone))
        zone.friendlyRangedStrength = 0
        assertEquals(TacticalPosture.ExploitFlanks, select(zone))
    }

    @Test
    fun `neutral territory withdraws from superior enemies without a ranged advantage`() {
        val zone = zone(TacticalTerritory.Neutral, TacticalDominance.Enemy, friendlyRangedStrength = 0, enemyRangedStrength = 0)
        assertEquals(TacticalPosture.Withdraw, select(zone))
        zone.friendlyRangedStrength = 500
        zone.friendlyMeleeStrength = 100
        zone.enemyMeleeStrength = 200
        assertEquals(TacticalPosture.AttritFromRange, select(zone))
        zone.enemyMeleeStrength = 100
        assertEquals(TacticalPosture.ExploitFlanks, select(zone))
    }

    @Test
    fun `water counterattack danger uses naval not land strengths`() {
        val water = zone(TacticalTerritory.Enemy, TacticalDominance.Friendly, isWater = true, city = enemyCity,
            friendlyRangedStrength = 400, enemyRangedStrength = 0)
        water.friendlyNavalStrength = 100
        water.enemyNavalStrength = 301
        assertEquals(TacticalPosture.Withdraw, select(water))
        water.enemyNavalStrength = 300
        assertEquals(TacticalPosture.Steamroll, select(water))
    }

    @Test
    fun `unowned land is flanked and temporary operation zones keep their existing orders`() {
        assertEquals(TacticalPosture.ExploitFlanks, select(zone(TacticalTerritory.NoOwner, TacticalDominance.Enemy)))
        assertEquals(TacticalPosture.ShoreBombardment, select(zone(TacticalTerritory.TempZone, TacticalDominance.Even, isWater = true)))
        state.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        assertEquals(TacticalPosture.SurgicalCityStrike, select(zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity)))
        assertEquals(TacticalPosture.Steamroll, select(zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity)))
    }

    @Test
    fun `an enemy zone withdraws to support a threatened neighboring city unless it is falling`() {
        val friendlyCity = game.addCity(civ, game.tileMap[0, 0])
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = state.map
        val friendly = map.zoneOf(friendlyCity.getCenterTile())!!
        val hostile = map.zoneOf(enemyCity.getCenterTile())!!
        assertTrue(hostile.neighbors.contains(friendly))
        friendly.dominance = TacticalDominance.Enemy
        hostile.dominance = TacticalDominance.Friendly
        hostile.friendlyLandUnitCount = 2
        PostureSelection.updatePostures(civ, state, map)
        assertEquals(TacticalPosture.Withdraw, state.findPosture(enemy.civName, false, enemyCity.id))
        friendlyCity.damageTakenLastTurn = friendlyCity.health + 1
        PostureSelection.updatePostures(civ, state, map)
        assertEquals(TacticalPosture.Steamroll, state.findPosture(enemy.civName, false, enemyCity.id))
    }

    // endregion
    // region Which zones count and what is remembered

    @Test
    fun `a zone counts when it is temporary, our capital's, or where we or they have units near`() {
        val capital = civ.getCapital()!!
        val quiet = { city: City, enemyStrength: Int -> zone(TacticalTerritory.Friendly, TacticalDominance.Friendly, city = city, friendlyUnits = 0, enemyUnits = 0,
            friendlyRangedStrength = 500, enemyRangedStrength = 0, friendlyStrength = 500, enemyStrength = enemyStrength) }
        assertTrue(PostureSelection.useThisZone(civ, state, quiet(capital, 0)))
        val other = game.addCity(civ, game.tileMap[0, 0])
        assertFalse("a quiet city of ours: Friendly by its own strength, but nobody is near it", PostureSelection.useThisZone(civ, state, quiet(other, 0)))
        assertTrue("with the enemy in sight", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Friendly, TacticalDominance.Even, city = other, friendlyUnits = 0, enemyUnits = 1)))
        assertTrue("with enemy air units over it, which are not counted", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Friendly, TacticalDominance.Even, city = other, friendlyUnits = 0, enemyUnits = 0)))
        assertTrue("our units near their city", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity, friendlyUnits = 1, enemyUnits = 0)))
        assertFalse("no units of ours near their city", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity, friendlyUnits = 0, enemyUnits = 0)))
        assertTrue("both have units", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.NoOwner, TacticalDominance.Even)))
        state.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        assertTrue("an operation's target", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity, friendlyUnits = 0, enemyUnits = 0)))
    }

    @Test
    fun `postures are chosen for the zones worth using and remembered by owner, water and city`() {
        val capital = civ.getCapital()!!
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        // five Swordsmen around our capital, which is the only zone in use: the enemy dominates it
        for (tile in capital.getCenterTile().getTilesAtDistance(2).take(5)) game.addUnit("Swordsman", enemy, tile)
        civ.ai.invalidateTurnCaches()
        assertEquals(TacticalDominance.Enemy, state.map.zoneOf(capital.getCenterTile())!!.dominance)
        PostureSelection.updatePostures(civ, state, state.map)
        assertEquals(1, state.postures.size)
        assertEquals(TacticalPosture.Hedgehog, state.findPosture(civ.civName, false, capital.id))
        assertEquals(TacticalPosture.None, state.findPosture(civ.civName, true, capital.id))
        assertEquals(TacticalPosture.None, state.findPosture(null, false, null))

        // what a zone chose last turn is what it remembers
        state.postures[0].posture = TacticalPosture.Counterattack
        assertEquals(TacticalPosture.Counterattack, state.findPosture(civ.civName, false, capital.id))
    }

    // endregion
}
