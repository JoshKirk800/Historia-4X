package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The promotion trees of Brave New World hang together */
@RunWith(BaseTestRunner::class)
class PromotionRulesetTests {
    private val ruleset = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset   // TestGame loads the rulesets when run alone

    /** A promotion a unit type lists can be reached by that type: it has no prerequisite, or one of its prerequisites is reachable for the
     *  type, a unit of the type starts with it, or no unit type lists it (a promotion that buildings, policies or abilities give, like the
     *  Ikanda's Buffalo Horns). Catches promotions merged across types whose prerequisites only one type can have (the air units' Ambush once
     *  needed Shock II or Drill II). */
    @Test
    fun `every promotion a unit type lists can be reached by that type`() {
        val unreachable = mutableListOf<String>()
        for (unitType in ruleset.units.values.map { it.unitType }.distinct()) {
            val listed = ruleset.unitPromotions.values.filter { unitType in it.unitTypes }.associateBy { it.name }
            val reachable = listed.values.filter { it.prerequisites.isEmpty() }.map { it.name }.toMutableSet()
            reachable += ruleset.units.values.filter { it.unitType == unitType }.flatMap { it.promotions }
            reachable += ruleset.unitPromotions.values.filter { it.unitTypes.isEmpty() }.map { it.name }
            do {
                val more = listed.values.filter { it.name !in reachable && it.prerequisites.any { p -> p in reachable } }
                reachable += more.map { it.name }
            } while (more.isNotEmpty())
            unreachable += listed.keys.filter { it !in reachable }.map { "$unitType: $it" }
        }
        assertEquals(emptyList<String>(), unreachable.sorted())
    }
}
