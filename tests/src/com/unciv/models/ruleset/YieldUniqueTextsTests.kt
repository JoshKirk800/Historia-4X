package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueFlag
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Every unique text that the ruleset generator writes for the yield tables of Vox Populi (`tools/rulegen`, `events.py`; `phaseE/vp-yields-plan.md` 4.3 and 4.5)
 *  parses and validates against [UniqueType].
 *
 *  The generator writes some 540 distinct texts for the 160 yield tables of P2; they differ in the numbers and the names of the objects (a building, a terrain, a
 *  resource of Vox Populi), which the ruleset of the Civ V - Gods & Kings test game does not have, so this test holds one text of every shape the generator
 *  writes, with names that the test ruleset has: each event as `Gain [n] [stat] <upon event>` with the modifiers the generator puts after it, and the static
 *  tables as the texts the generators already used (`from [tileFilter] tiles [cityFilter]`, `from every [specialist] [cityFilter]`, ...). The python side of the
 *  rule, that every row of an event table gives one unique and that each unique's body and modifiers are unique types of the branch, is
 *  `python -m rulegen uniques vp` (`tools/rulegen/__main__.py`).
 *
 *  The four per-turn shapes of Y2 (`parity/vp-yields-economy`) are fixed by the plan and written by the generator, but their unique types come with that branch:
 *  their tests are skipped (an assumption) until the type exists in this one.
 */
@RunWith(BaseTestRunner::class)
class YieldUniqueTextsTests {

    private enum class Owner { Building, Policy, Belief, Improvement }

    private class Shape(val owner: Owner, val text: String)

    private fun building(text: String) = Shape(Owner.Building, text)
    private fun policy(text: String) = Shape(Owner.Policy, text)
    private fun belief(text: String) = Shape(Owner.Belief, text)
    private fun improvement(text: String) = Shape(Owner.Improvement, text)

    private val era = "<for every [Era scaler]>"
    private val speed = "<(modified by game speed)>"
    private val citizens = "<for every [Triggering amount]>"

    /** Building rows: its own city's tables say `<in this city>`, the `...Global` ones do not */
    private val buildingEvents = listOf(
        "Gain [25] [Food] $speed",   // Building_InstantYield: no trigger, fires when the building is built
        "Gain [100] [Gold] <upon We Love The King Day starting> <in this city> $speed $era",
        "Gain [20] [Production] <upon population growth> <in this city> $speed $citizens $era",
        "Gain [50] [Science] <upon population growth> <in this city> $speed",   // Building_YieldFromBirth without IsEraScaling: flat
        "Gain [50] [Science] $speed <for every [[Population] in [in this city] Cities]>",   // Building_YieldFromBirthRetroactive
        "Gain [3] [Culture] <upon constructing [Buildings]> <in this city> $speed $era",
        "Gain [10] [Gold] <upon acquiring a [All] tile through [border growth]> <in this city> $speed $era",
        "Gain [50] Golden Age points <upon expending a [Great Person] unit> <in this city> $era",
        "Gain [50] [Science] <upon expending a [Great Person] unit> <in this city> $speed $era",
        "[1] population [in this city] <upon entering a Golden Age> <in this city>",
        "Gain [30] [Culture] <upon pillaging a tile> <in this city> $speed $era",
        "Gain [25] [Culture] <upon pillaging a tile> $speed $era",   // Building_YieldFromPillageGlobal
        "Gain [50] [Culture] <upon discovering [All] technology> <in this city> $speed $era",
        "Gain [8] [Faith] <upon defeating a [Military] unit> <in this city> $speed",
        "Gain [8] [Culture] <upon defeating a [Military] unit> $speed $era",   // Building_YieldFromVictoryGlobal
        "Gain [10] [Gold] <upon defeating a [Military] unit> <during a Golden Age> $speed $era",
        // the twelve events that Vox Populi does not scale by the speed are ratio rows and have no speed modifier
        "Gain [10]% of the triggering amount as [Food] <upon buying something with [Gold]>",
        "Gain [5]% of the triggering amount as [Culture] <upon buying something with [Gold]> <in this city>",
        "Gain [15]% of the triggering amount as [Science] <upon buying something with [Faith]> <in this city>",
        "Gain [15]% of the triggering amount as [Gold] <upon completing a [All] unit> <in this city>",
    ).map { building(it) }

    private val policyEvents = listOf(
        "Gain [75] [Science] $speed",   // Policy_InstantYield
        "Gain [8] [Science] <upon population growth> $speed $citizens $era",
        "Gain [5] [Culture] $speed <for every [[Population] in [in all cities] Cities]> $era",   // Policy_YieldFromBirthRetroactive
        "Gain [5] Golden Age points <for every [[Population] in [in all cities] Cities]> $era",
        "Gain [20] [Gold] <upon acquiring a [All] tile through [border growth]> $speed $era",
        "Gain [10] Golden Age points <upon acquiring a [All] tile through [border growth]> $era",
        "Gain [15] [Science] <upon constructing [Buildings]> $speed $era",
        "Gain [250] [Gold] <upon constructing [World Wonder]> $speed $era",
        "Gain [15] [Culture] <upon discovering [All] technology> $speed $era",
        "Gain [100] [Science] <upon expending a [Great Person] unit> $speed $era",
        "Gain [40] [Science] <upon founding a city> $speed $era",
        "Earn [10]% of killed [Military] unit's [Strength] as [Science]",
        "Earn [150]% of killed [Military] unit's [Strength] as [Golden Age points]",
        // the traits of nations are Global uniques as well
        "Gain [50] Golden Age points <upon gaining a [Great Admiral] unit> $era",
        "Gain [100] [Gold] <upon gaining a luxury resource> $speed $citizens $era",
        "Gain [10] [Gold] <upon acquiring a [Hill] tile through [settling]> $speed $era",
        "Gain [10] [Gold] <upon acquiring a [Desert] tile through [conquest]> $speed $era",
        "Gain [4] [Faith] <upon acquiring a [Coast] tile through [culture bomb]> $speed $era",
        "Gain [4] [Faith] <upon acquiring a [Hill] tile through [stolen by culture bomb]> $speed $era",
        "Gain [20] [Production] <upon acquiring a [All] tile through [purchase]> $speed $era",
    ).map { policy(it) }

    private val beliefEvents = listOf(
        "Gain [12] [Production] <upon population growth> $speed $citizens $era",
        "Gain [15] [Production] <upon acquiring a [All] tile through [border growth]> $speed",   // Belief_YieldPerBorderGrowth without IsEraScaling
        "Gain [8] [Food] <upon constructing [Buildings]> $speed $era",
        "Gain [10] [Faith] <upon expending a [Great Person] unit> $speed $era",
        "Gain [15] [Culture] <upon We Love The King Day starting> $speed $era",
        "Gain [20] [Gold] <upon pillaging a tile> $speed $era",
        "Gain [20] Golden Age points <upon pillaging a tile> $era",
        "Gain [200]% of the triggering amount as [Gold] <upon defeating a [Military] unit> $speed",
        "Gain [75] [Faith] <upon gaining a [Great General] unit> $speed",
    ).map { belief(it) }

    /** The static tables: the forms the generators already had (the first ones) and the ones this slice added */
    private val staticShapes = listOf(
        building("[+2 Culture] from [Farm] tiles [in this city]"),   // Building_ImprovementYieldChanges
        building("[+3 Culture] from [Mine] tiles [in all cities]"),   // ...Global
        building("[+2 Food] from [Lakes] tiles [in all cities]"),
        building("[+1 Food, +1 Culture] from every [Scientist] [in this city]"),   // Building_SpecialistYieldChangesLocal
        building("[+4 Science] from every [Temple]"),   // Building_BuildingClassLocalYieldChanges
        building("[+10]% [Culture] [in this city] <during a Golden Age>"),
        building("[+10]% [Gold] [in this city] <during We Love The King Day>"),
        building("[+2] Tourism [in this city]"),   // Building_YieldChanges with Tourism
        building("[+3]% Tourism [in this city]"),
        building("[+3]% Tourism [in all cities]"),
        building("[+1 Food] [in this city] <after discovering [Pottery]>"),   // Building_TechEnhancedYieldChanges with its own tech
        policy("[+1 Production] from every [Iron]"),   // Policy_ResourceYieldChanges
        policy("[+1 Science, +1 Production] from every [Ocean]"),
        policy("[+10]% [Food] [in all cities] <during a Golden Age>"),
        policy("[+10]% [Culture] [in all cities] <during We Love The King Day>"),
        policy("[+1 Science, +1 Culture] from every [Scientist] [in all cities]"),
        policy("[+20]% [Science] [in all cities] <during a Golden Age>"),   // Trait_GoldenAgeYieldModifiers
        policy("[+1 Gold] from [Hill] tiles [in all cities]"),   // Trait_TerrainYieldChanges
        policy("[+2 Gold] from every [Merchant] [in all cities]"),   // Tech_SpecialistYieldChanges
        belief("[+1 Culture] from every [Scientist] [in cities following this religion]"),
        belief("[+1 Food, +1 Faith] from [Lakes] tiles [in cities following this religion]"),
        belief("[+1 Culture, +1 Food] from [Marsh] tiles without [improved] [in cities following this religion]"),
        belief("[+2 Food, +1 Faith] [Coastal]"),
        belief("[+20]% [Food] [in cities following this religion] <during a Golden Age>"),
        belief("[+1 Faith] per [25] population [in cities following this religion]"),
        improvement("[+3 Food] for each adjacent [Wheat]"),
        improvement("[+1 Production, +1 Faith] for each adjacent [Desert]"),
        improvement("[+1 Culture] for each adjacent [City center]"),
        improvement("[-2 Food] <in [Marsh] tiles>"),
        improvement("[+1 Food] <in [Fresh water] tiles>"),
        improvement("[+1 Food, +1 Production, +1 Culture] <during a Golden Age>"),
        improvement("[+1 Production, +1 Gold, +1 Science] <during We Love The King Day>"),
    )

    private val y1Shapes = buildingEvents + policyEvents + beliefEvents + staticShapes

    private val processes = building("[+10]% yield from processes [in this city]")
    private val yieldPercent = building("[10]% of [Food] [in this city] added to [Gold]")
    private val perCitizens = policy("Capital's [+1 Food, +1 Production] per [5] citizens in the empire")
    private val adjacentImprovements = improvement("[+1 Food] from every [2] adjacent [Farm]")

    private fun errorsOf(shape: Shape): List<String> {
        val game = TestGame()
        val name = when (shape.owner) {
            Owner.Building -> game.createBuilding(shape.text).apply { cost = 100 }.name   // a building without a cost or a tech is an error of its own
            Owner.Policy -> game.createPolicy(shape.text).name
            Owner.Belief -> game.createBelief(BeliefType.Follower, shape.text).name
            Owner.Improvement -> game.createTileImprovement(shape.text).apply { terrainsCanBeBuiltOn = listOf("Plains") }.name   // an improvement with no terrain is an error of its own
        }
        return game.ruleset.getErrorList()
            .filter { it.text.contains(name) && it.errorSeverityToReport >= RulesetErrorSeverity.Warning }
            .map { it.text }
    }

    private fun typeExists(text: String) = UniqueType.entries.any { it.text == text }

    @Test
    fun `every shape of the yield tables is a unique type`() {
        for (shape in y1Shapes)
            assertNotNull("${shape.text} is no unique type", Unique(shape.text).type)
    }

    @Test
    fun `every shape of the yield tables validates on the object that owns it`() {
        val failures = y1Shapes.associate { it.text to errorsOf(it) }.filterValues { it.isNotEmpty() }
        assertTrue("shapes with errors:\n" + failures.entries.joinToString("\n") { "${it.key}: ${it.value}" }, failures.isEmpty())
    }

    @Test
    fun `every modifier of a shape is a trigger, a conditional or a multiplier`() {
        for (shape in y1Shapes)
            for (modifier in Unique(shape.text).modifiers)
                assertNotNull("<${modifier.text}> of ${shape.text} is no modifier", modifier.type)
    }

    @Test
    fun `the events that Vox Populi does not scale by the speed have no speed modifier and a speed modifier is only written where the unique takes it`() {
        val unscaled = listOf("upon buying something with [Gold]", "upon buying something with [Faith]", "upon completing a [All] unit")
        for (shape in y1Shapes) {
            val hasSpeed = shape.text.contains(speed)
            if (unscaled.any { shape.text.contains("<$it>") })
                assertTrue("${shape.text} is scaled by the speed", !hasSpeed)
            if (hasSpeed)
                assertTrue("${shape.text} takes no speed modifier", UniqueFlag.AcceptsSpeedModifier in Unique(shape.text).type!!.flags)
        }
    }

    @Test
    fun `a Global table gives a unique without in this city and a city table gives one with it`() {
        val global = Unique("Gain [25] [Culture] <upon pillaging a tile> $speed $era")
        val city = Unique("Gain [30] [Culture] <upon pillaging a tile> <in this city> $speed $era")
        assertTrue(global.modifiers.none { it.type == UniqueType.ConditionalInThisCity })
        assertTrue(city.modifiers.any { it.type == UniqueType.ConditionalInThisCity })
    }

    @Test
    fun `the process modifier of Y2 is valid once that branch has the unique type`() {
        assumeTrue("Y2 (parity/vp-yields-economy) has not added it", typeExists("[relativeAmount]% yield from processes [cityFilter]"))
        assertEquals(emptyList<String>(), errorsOf(processes))
    }

    @Test
    fun `the yield percentage of Y2 is valid once that branch has the unique type`() {
        assumeTrue("Y2 (parity/vp-yields-economy) has not added it", typeExists("[relativeAmount]% of [stat] [cityFilter] added to [stat]"))
        assertEquals(emptyList<String>(), errorsOf(yieldPercent))
    }

    @Test
    fun `the capital's yield per citizens in the empire of Y2 is valid once that branch has the unique type`() {
        assumeTrue("Y2 (parity/vp-yields-economy) has not added it", typeExists("Capital's [stats] per [positiveAmount] citizens in the empire"))
        assertEquals(emptyList<String>(), errorsOf(perCitizens))
    }

    @Test
    fun `the yield per adjacent improvements of Y2 is valid once that branch has the unique type`() {
        assumeTrue("Y2 (parity/vp-yields-economy) has not added it", typeExists("[stats] from every [positiveAmount] adjacent [tileFilter]"))
        assertEquals(emptyList<String>(), errorsOf(adjacentImprovements))
    }
}
