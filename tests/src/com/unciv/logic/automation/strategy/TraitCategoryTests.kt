package com.unciv.logic.automation.strategy

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's leader trait categories (CvPlayerTraits::SetIsWarmonger ... SetIsReligious) on the Brave New World leaders */
@RunWith(GdxTestRunner::class)
class TraitCategoryTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun categoriesOf(nation: String): Set<TraitCategory> {
        val civ = game.addCiv(game.ruleset.nations[nation]!!)
        return TraitCategory.entries.filter { civ.ai.hasTraitCategory(it) }.toSet()
    }

    @Test
    fun `the leaders' traits put them in VP's categories`() {
        // Assyria: a free technology from conquered cities (warmonger and nerd), England: free naval promotions (warmonger)
        assertEquals(setOf(TraitCategory.Warmonger, TraitCategory.Nerd), categoriesOf("Assyria"))
        assertEquals(setOf(TraitCategory.Warmonger), categoriesOf("England"))
        // Venice: no annexing (smaller) and more trade routes (diplomat); Korea: science specialists (nerd, smaller)
        assertEquals(setOf(TraitCategory.Diplomat, TraitCategory.Smaller), categoriesOf("Venice"))
        assertEquals(setOf(TraitCategory.Nerd, TraitCategory.Smaller), categoriesOf("Korea"))
        // India's population unhappiness modifier is in none
        assertEquals(emptySet<TraitCategory>(), categoriesOf("India"))
    }

    @Test
    fun `a city-state has no leader traits`() {
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        assertFalse(TraitCategory.entries.any { cityState.ai.hasTraitCategory(it) })
    }
}
