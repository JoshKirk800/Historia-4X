package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  VP 5.4.8 CvUnit::getCaptureUnitType (the capturer's own unit of the captured unit's `Capture` class), CvUnit::getCaptureDefinition
 *  (barbarians involved: the unit stays as it is) and CvUnit::createCaptureUnit (a minor civ kills a captured settler, judged on the unit it got),
 *  as the unique `Becomes [baseUnitFilter] when captured` behind the switch `captureClassLikeVp`.
 */
@RunWith(BaseTestRunner::class)
class VpCaptureClassTests {
    private val becomesWorker = "Becomes [Worker] when captured"

    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private fun newGame(switchOn: Boolean = true): TestGame {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        setFlag(game, "captureClassLikeVp", switchOn)
        return game
    }

    /** A founding civilian of the ruleset's `Civilian` type, named by the test game */
    private fun createSettlerLike(game: TestGame, vararg uniques: String) =
        game.createBaseUnit("Civilian", "Founds a new city", *uniques).also { it.movement = 2 }

    /** Adds [unique] to an existing unit and rebuilds the unique cache the way [com.unciv.models.ruleset.unit.BaseUnit.setRuleset] does */
    private fun addUnique(game: TestGame, unitName: String, unique: String) {
        val unit = game.ruleset.units[unitName]!!
        unit.uniques.add(unique)
        unit.setRuleset(game.ruleset)
    }

    private val warriorTiles = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private var warriorsPlaced = 0

    private fun capture(game: TestGame, capturer: Civilization, victim: MapUnit) {
        val (x, y) = warriorTiles[warriorsPlaced++]
        val warrior = game.addUnit("Warrior", capturer, game.getTile(x, y))
        BattleUnitCapture.captureCivilianUnit(MapUnitCombatant(warrior), MapUnitCombatant(victim), checkDefeat = false)
    }

    @Test
    fun `settler becomes the capturers worker through the unique`() {
        val game = newGame()
        addUnique(game, "Settler", becomesWorker)
        val attackerCiv = game.addCiv()
        val defenderCiv = game.addCiv()
        val warrior = game.addUnit("Warrior", attackerCiv, game.getTile(1, 0))
        warrior.currentMovement = 2f
        val settler = game.addUnit("Settler", defenderCiv, game.getTile(2, 0))
        Battle.attack(MapUnitCombatant(warrior), MapUnitCombatant(settler))
        val captured = game.getTile(2, 0).civilianUnit!!
        assertEquals(attackerCiv, captured.civ)
        assertEquals("Worker", captured.baseUnit.name)
    }

    @Test
    fun `pioneer becomes the unique worker replacement of the capturer`() {
        val game = newGame()
        val pioneer = createSettlerLike(game, becomesWorker)
        val replacement = game.createBaseUnit("Civilian").also {
            it.replaces = "Worker"
            it.uniqueTo = "Testlanders"
            it.movement = 2
        }
        val testlandersNation = Nation().apply { name = "Testlanders"; cities = arrayListOf("Tests") }
        game.ruleset.nations[testlandersNation.name] = testlandersNation
        val attackerCiv = game.addCiv(testlandersNation, isPlayer = false)
        val defenderCiv = game.addCiv()
        val victim = game.addUnit(pioneer.name, defenderCiv, game.getTile(2, 0))
        capture(game, attackerCiv, victim)
        val captured = game.getTile(2, 0).civilianUnit!!
        assertEquals(attackerCiv, captured.civ)
        assertEquals(replacement.name, captured.baseUnit.name)
    }

    @Test
    fun `pioneer becomes the default worker for a capturer without a replacement`() {
        val game = newGame()
        val pioneer = createSettlerLike(game, becomesWorker)
        val attackerCiv = game.addCiv()
        val defenderCiv = game.addCiv()
        val victim = game.addUnit(pioneer.name, defenderCiv, game.getTile(2, 0))
        capture(game, attackerCiv, victim)
        assertEquals("Worker", game.getTile(2, 0).civilianUnit!!.baseUnit.name)
    }

    @Test
    fun `missionary stays a missionary`() {
        val game = newGame()
        addUnique(game, "Missionary", "Becomes [Missionary] when captured")
        val attackerCiv = game.addCiv()
        val defenderCiv = game.addCiv()
        val victim = game.addUnit("Missionary", defenderCiv, game.getTile(2, 0))
        capture(game, attackerCiv, victim)
        val captured = game.getTile(2, 0).civilianUnit!!
        assertEquals(attackerCiv, captured.civ)
        assertEquals("Missionary", captured.baseUnit.name)
    }

    @Test
    fun `a unit without the unique keeps the old rules`() {
        val game = newGame()
        // No unique on the real Settler: the old Settler to Worker rule still applies
        val attackerCiv = game.addCiv()
        val defenderCiv = game.addCiv()
        val settler = game.addUnit("Settler", defenderCiv, game.getTile(2, 0))
        capture(game, attackerCiv, settler)
        assertEquals("Worker", game.getTile(2, 0).civilianUnit!!.baseUnit.name)
        // A non-founding civilian without the unique is captured as it is, an Uncapturable one is destroyed
        val plain = game.createBaseUnit("Civilian").also { it.movement = 2 }
        val unique = game.createBaseUnit("Civilian", "Uncapturable").also { it.movement = 2 }
        val plainUnit = game.addUnit(plain.name, defenderCiv, game.getTile(0, 2))
        val uncapturable = game.addUnit(unique.name, defenderCiv, game.getTile(-2, 0))
        capture(game, attackerCiv, plainUnit)
        capture(game, attackerCiv, uncapturable)
        assertEquals(plain.name, game.getTile(0, 2).civilianUnit!!.baseUnit.name)
        assertNull(game.getTile(-2, 0).civilianUnit)
    }

    @Test
    fun `with the switch off the unique is not read`() {
        val game = newGame(switchOn = false)
        // A founding unit that would become a Missionary: the old rule makes the default worker
        val pioneer = createSettlerLike(game, "Becomes [Missionary] when captured")
        val attackerCiv = game.addCiv()
        val defenderCiv = game.addCiv()
        val victim = game.addUnit(pioneer.name, defenderCiv, game.getTile(2, 0))
        capture(game, attackerCiv, victim)
        assertEquals("Worker", game.getTile(2, 0).civilianUnit!!.baseUnit.name)
    }

    @Test
    fun `barbarian capturer keeps the unit as it is`() {
        val game = newGame()
        val pioneer = createSettlerLike(game, becomesWorker)
        val barbarian = game.addBarbarianCiv()
        val defenderCiv = game.addCiv()
        val victim = game.addUnit(pioneer.name, defenderCiv, game.getTile(2, 0))
        capture(game, barbarian, victim)
        val captured = game.getTile(2, 0).civilianUnit!!
        assertEquals(barbarian, captured.civ)
        assertEquals(pioneer.name, captured.baseUnit.name)
    }

    @Test
    fun `capturing from barbarians keeps the unit as it is`() {
        val game = newGame()
        val pioneer = createSettlerLike(game, becomesWorker)
        val barbarian = game.addBarbarianCiv()
        val attackerCiv = game.addCiv()
        val victim = game.addUnit(pioneer.name, barbarian, game.getTile(2, 0))
        capture(game, attackerCiv, victim)
        val captured = game.getTile(2, 0).civilianUnit!!
        assertEquals(attackerCiv, captured.civ)
        assertEquals(pioneer.name, captured.baseUnit.name)
    }

    @Test
    fun `city state captures a pioneer as a worker but destroys a settler taken from barbarians`() {
        val game = newGame()
        val pioneer = createSettlerLike(game, becomesWorker)
        val cityState = game.addCiv(cityStateType = "Cultured")
        val defenderCiv = game.addCiv()
        val barbarian = game.addBarbarianCiv()
        // CvUnit::createCaptureUnit judges the unit that results: a Worker is kept ...
        val victim = game.addUnit(pioneer.name, defenderCiv, game.getTile(2, 0))
        capture(game, cityState, victim)
        val captured = game.getTile(2, 0).civilianUnit
        assertNotNull(captured)
        assertEquals(cityState, captured!!.civ)
        assertEquals("Worker", captured.baseUnit.name)
        // ... a settler that stays a settler (taken from barbarians) is killed
        val barbarianSettler = game.addUnit(pioneer.name, barbarian, game.getTile(0, 2))
        capture(game, cityState, barbarianSettler)
        assertNull(game.getTile(0, 2).civilianUnit)
    }

    @Test
    fun `city state still destroys a captured settler with the switch off`() {
        val game = newGame(switchOn = false)
        val pioneer = createSettlerLike(game, becomesWorker)
        val cityState = game.addCiv(cityStateType = "Cultured")
        val defenderCiv = game.addCiv()
        val victim = game.addUnit(pioneer.name, defenderCiv, game.getTile(2, 0))
        capture(game, cityState, victim)
        assertNull(game.getTile(2, 0).civilianUnit)
    }
}
