package com.unciv.logic.civilization

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.SpyMission
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The ruleset validator looks at spy missions: their uniques, the technology they need and the foci they name as blocking them. A fixture with no civilizations, because the validator stops at the
 *  nations a test adds without colors (and then reports one error for the ruleset, not the ones asked for here), which the first assertion of each test would show.
 */
@RunWith(GdxTestRunner::class)
class SpyMissionValidationTests {
    private lateinit var game: TestGame
    private val added = ArrayList<String>()

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    }

    @After
    fun tearDown() {
        for (name in added) game.ruleset.spyMissions.remove(name)
    }

    private fun add(name: String, build: SpyMission.() -> Unit = {}): SpyMission {
        val mission = SpyMission().apply { this.name = name; build() }
        game.ruleset.spyMissions[name] = mission
        added += name
        return mission
    }

    private fun errorsOf(name: String): List<String> {
        val all = game.ruleset.getErrorList()
        assertTrue("the validator finished: ${all.take(2).map { it.text.take(160) }}", all.none { it.text.startsWith("Error while validating ruleset") })
        return all.filter { it.text.contains(name) }.map { it.text }
    }

    @Test
    fun `a mission with an unknown technology, a unique that does not parse and a negative duration is reported for each`() {
        add("Bad mission") {
            requiredTech = "No such technology"
            duration = -1
            uniques = arrayListOf("Needs [on the moon]")
        }
        val errors = errorsOf("Bad mission")
        assertTrue("the technology: $errors", errors.any { "unknown technology" in it })
        assertTrue("the unique: $errors", errors.any { "on the moon" in it })
        assertTrue("the duration: $errors", errors.any { "negative duration" in it })
    }

    @Test
    fun `a mission with a technology that exists and uniques that parse is not reported`() {
        add("Good mission") {
            requiredTech = game.ruleset.technologies.keys.first()
            duration = 10
            group = 1
            uniques = arrayListOf("Needs [no fresh water]", "Steal [35]% of the treasury of the target", "City is sapped <for [5] turns>")
        }
        assertEquals(emptyList<String>(), errorsOf("Good mission"))
    }

    @Test
    fun `a mission blocked by a focus the ruleset lacks is reported for that focus only`() {
        add("A focus") { isFocus = true }
        add("Blocked") { blockedBy = arrayListOf("A focus", "No such focus") }
        val warnings = errorsOf("Blocked")
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single().contains("No such focus"))
    }
}
