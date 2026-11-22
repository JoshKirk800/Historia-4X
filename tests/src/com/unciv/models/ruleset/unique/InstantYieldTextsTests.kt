package com.unciv.models.ruleset.unique

import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.Test

/** How the gains of the new instant yield events read to the user ([InstantYieldTexts], what [Unique.getDisplayText] gives) */
@RunWith(BaseTestRunner::class)
class InstantYieldTextsTests {
    private fun display(text: String) = Unique(text, UniqueTarget.Building, "Library").getDisplayText()

    @Test
    fun `a birth yield reads per citizen gained and times the era, without the speed plumbing`() {
        assertEquals("Gain [20] [Production] <upon population growth> <in this city> <per citizen gained> <multiplied by the era (at least 1)>",
            display("Gain [20] [Production] <upon population growth> <in this city> <(modified by game speed)> <for every [Triggering amount]> <for every [Era scaler]>"))
    }

    @Test
    fun `the other events name what they count`() {
        assertEquals("Gain [5] [Gold] <upon gaining a luxury resource> <per luxury gained>",
            display("Gain [5] [Gold] <upon gaining a luxury resource> <(modified by game speed)> <for every [Triggering amount]>"))
        assertEquals("Gain [5]% of the triggering amount as [Culture] <upon buying something with [Gold]> <in this city>",
            display("Gain [5]% of the triggering amount as [Culture] <upon buying something with [Gold]> <in this city>"))
        assertEquals("Gain [10] [Gold] <upon acquiring a [All] tile through [border growth]> <in this city> <multiplied by the era (at least 1)>",
            display("Gain [10] [Gold] <upon acquiring a [All] tile through [border growth]> <in this city> <(modified by game speed)> <for every [Era scaler]>"))
    }

    @Test
    fun `a gain of an event that is not new reads as written, and so does every other unique`() {
        val old = "Gain [20] [Gold] <upon researching [Writing]> <(modified by game speed)> <for every [Era scaler]>"
        assertEquals(old, display(old))
        val other = "[+1 Food] <in this city> <for every [Era scaler]>"
        assertEquals(other, display(other))
    }
}
