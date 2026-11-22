package com.unciv.models.ruleset.unique

import com.unciv.logic.civilization.TileAcquisition
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The shapes of the instant yield uniques (`phaseE/vp-yields-plan.md` 4.3): the texts, what they accept, and the two countables */
@RunWith(BaseTestRunner::class)
class InstantYieldUniqueTests {

    private val newTriggers = mapOf(
        "upon population growth" to UniqueType.TriggerUponPopulationGrowth,
        "upon acquiring a [Hill] tile through [culture bomb]" to UniqueType.TriggerUponAcquiringTile,
        "upon buying something with [Gold]" to UniqueType.TriggerUponBuying,
        "upon completing a [Military] unit" to UniqueType.TriggerUponCompletingUnit,
        "upon pillaging a tile" to UniqueType.TriggerUponPillaging,
        "upon We Love The King Day starting" to UniqueType.TriggerUponWltkdStart,
        "upon gaining a luxury resource" to UniqueType.TriggerUponGainingLuxury,
    )

    private fun errorsOf(game: TestGame, objectName: String) =
        game.ruleset.getErrorList().filter { it.text.contains(objectName) && it.errorSeverityToReport >= RulesetErrorSeverity.Warning }

    private fun errorsOfBuildingWith(vararg uniques: String): List<String> {
        val game = TestGame()
        val building = game.createBuilding(*uniques).apply { cost = 100 } // a building with no cost and no tech is an error of its own
        return errorsOf(game, building.name).map { it.text }
    }

    @Test
    fun `the seven new events are trigger conditions`() {
        for ((text, type) in newTriggers) {
            assertEquals(text, type, Unique(text).type)
            assertTrue("$text is a trigger condition", UniqueTarget.TriggerCondition in type.targetTypes)
        }
        assertEquals(7, newTriggers.size)
    }

    @Test
    fun `upon defeating a unit is a trigger condition of the civilization's and a building's uniques too`() {
        assertTrue(UniqueTarget.TriggerCondition in UniqueType.TriggerUponDefeatingUnit.targetTypes)
        assertTrue(UniqueTarget.UnitTriggerCondition in UniqueType.TriggerUponDefeatingUnit.targetTypes)
    }

    @Test
    fun `the gain uniques have their texts`() {
        assertEquals(UniqueType.OneTimeGainStat, Unique("Gain [5] [Food]").type)
        assertEquals(UniqueType.OneTimeGainStat, Unique("Gain [5] [Production] <upon population growth>").type)
        assertEquals(UniqueType.OneTimeGainStatOfTriggeringAmount, Unique("Gain [150]% of the triggering amount as [Gold]").type)
        assertEquals("Gain [] []", UniqueType.OneTimeGainStat.placeholderText)
        assertEquals("Gain []% of the triggering amount as []", UniqueType.OneTimeGainStatOfTriggeringAmount.placeholderText)
    }

    @Test
    fun `the new uniques are valid on a building and on a policy`() {
        val shapes = listOf(
            "Gain [5] [Food] <upon population growth> <in this city>",
            "Gain [5] [Production] <upon acquiring a [Hill] tile through [culture bomb]>",
            "Gain [50]% of the triggering amount as [Gold] <upon buying something with [Gold]> <in this city> <(modified by game speed)>",
            "Gain [3] [Culture] <upon completing a [Military] unit> <in this city> <(modified by game speed)>",
            "Gain [1] [Faith] <upon pillaging a tile>",
            "Gain [1] [Gold] <upon We Love The King Day starting> <in this city> <for every [Era scaler]>",
            "Gain [1] [Science] <upon gaining a luxury resource> <for every [Triggering amount]> <for every [Era scaler]>",
            "Gain [150]% of the triggering amount as [Gold] <upon defeating a [Military] unit> <in this city>",
            "Gain [5] [Gold] <upon acquiring a [All] tile through [border growth]>",
        )
        for (shape in shapes) {
            assertEquals(shape, emptyList<String>(), errorsOfBuildingWith(shape))
            val game = TestGame()
            val policy = game.createPolicy(shape)
            assertEquals("policy $shape", emptyList<String>(), errorsOf(game, policy.name).map { it.text })
        }
    }

    @Test
    fun `a tile acquisition that is none of the six is an error`() {
        assertTrue(errorsOfBuildingWith("Gain [5] [Gold] <upon acquiring a [All] tile through [by magic]>").isNotEmpty())
        assertEquals(setOf("border growth", "purchase", "settling", "conquest", "culture bomb", "stolen by culture bomb"),
            TileAcquisition.entries.map { it.label }.toSet())
        assertEquals(TileAcquisition.entries.map { it.label }.toSet(), UniqueParameterType.TileAcquisition.staticKnownValues)
    }

    @Test
    fun `Gain accepts Food and Production, a speed modifier and a countable amount of times`() {
        assertEquals(emptyList<String>(), errorsOfBuildingWith("Gain [5] [Food] <(modified by game speed)>", "Gain [5] [Production] <for every [Cities]>"))
        // a stat that no one has is still no stat
        assertTrue(errorsOfBuildingWith("Gain [5] [Mana] <upon population growth>").isNotEmpty())
    }

    @Test
    fun `a tourism gain has no speed modifier, so Tourism without a target is not scaled`() {
        assertFalse(UniqueFlag.AcceptsSpeedModifier in UniqueType.OneTimeGainTourism.flags)
        assertTrue(UniqueFlag.AcceptsSpeedModifier in UniqueType.OneTimeGainStat.flags)
        assertTrue(UniqueFlag.AcceptsSpeedModifier in UniqueType.OneTimeGainStatOfTriggeringAmount.flags)
    }

    @Test
    fun `every new unique has its documentation`() {
        val types = newTriggers.values + UniqueType.OneTimeGainStat + UniqueType.OneTimeGainStatOfTriggeringAmount + UniqueType.TriggerUponDefeatingUnit
        for (type in types) assertFalse("${type.name} has no docDescription", type.docDescription.isNullOrBlank())
    }

    @Test
    fun `the era scaler is 1 in the Ancient and Classical eras, 2 in the Medieval, and the last era of the ruleset is the most`() {
        val game = TestGame()
        game.makeHexagonalMap(2)
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero))
        val lastEra = game.ruleset.ai.lastEra
        val numbers = (0..game.ruleset.eras.size - 1).map { number ->
            civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == number }
            Countables.getCountableAmount("Era scaler", GameContext(civ))
        }

        assertEquals(1, numbers[0])
        assertEquals(1, numbers[1])
        assertEquals(2, numbers[2])
        assertEquals(minOf(game.ruleset.eras.size - 1, lastEra), numbers.last())
        assertTrue("the scaler never passes the last era", numbers.all { it != null && it <= lastEra })
        assertTrue(Countables.isKnownValue("Era scaler", game.ruleset))
    }

    @Test
    fun `the triggering amount is the amount of the context, 0 without one`() {
        val game = TestGame()
        val civ = game.addCiv()

        assertEquals(7, Countables.getCountableAmount("Triggering amount", GameContext(civ).copy(amount = 7)))
        assertEquals(0, Countables.getCountableAmount("Triggering amount", GameContext(civ)))
        assertTrue(Countables.isKnownValue("Triggering amount", game.ruleset))
    }

    @Test
    fun `an unique that counts the triggering amount applies that many times and not at all for 0`() {
        val game = TestGame()
        val civ = game.addCiv()
        val unique = Unique("Gain [3] [Gold] <upon population growth> <for every [Triggering amount]>")

        assertEquals(3, unique.getUniqueMultiplier(GameContext(civ).copy(amount = 3)))
        assertEquals(0, unique.getUniqueMultiplier(GameContext(civ)))
    }

    @Test
    fun `the amount of a context is not part of its random seed`() {
        val game = TestGame()
        val civ = game.addCiv()
        assertEquals(GameContext(civ).hashCode(), GameContext(civ).copy(amount = 5).hashCode())
    }

    @Test
    fun `a speed has an instant yield modifier that is its modifier unless it says otherwise`() {
        assertEquals(1f, Speed().instantYieldModifier, 0f)
        val game = TestGame()
        assertTrue(game.ruleset.speeds.values.all { it.instantYieldModifier > 0f })
        assertNull(game.ruleset.speeds["Nonexistent"])
    }
}
