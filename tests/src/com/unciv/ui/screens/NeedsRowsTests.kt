package com.unciv.ui.screens

import com.unciv.logic.city.NeedsTestWorld
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 *  The rows of the needs screens ([NeedsRows]), built from games that play Vox Populi's needs: the pure functions the city screen, the empire overview
 *  and the top bar draw from, so they are tested without a screen.
 */
@RunWith(BaseTestRunner::class)
class NeedsRowsTests {
    private val world = NeedsTestWorld()
    private val civ get() = world.civ

    private fun List<NeedsRow>.row(label: String) = firstOrNull { it.label == label }

    @Test
    fun `the city needs breakdown lists sources sorted by size and notes the population cap`() {
        world.addCity(4)
        val city = world.addCity(4)
        world.medians.basic = 500
        world.medians.gold = 500
        world.refresh()
        val needs = world.needsOf(city)
        assertTrue("two needs of four citizens each", needs.poverty == 4 && needs.distress > 0)

        val rows = NeedsRows.cityRows(city)
        val heading = rows.first()
        assertEquals("Local Unhappiness", heading.label)
        assertEquals(NeedsRow.Style.Heading, heading.style)
        assertEquals(needs.aggregated.toString(), heading.value)

        val sources = rows.drop(1).takeWhile { it.style == NeedsRow.Style.Line }
        assertEquals(needs.breakdown.keys.toList(), sources.map { it.label })
        assertEquals("the biggest first", sources.map { it.value.toInt() }.sortedDescending(), sources.map { it.value.toInt() })
        assertEquals(4, sources.first().value.toInt())

        val sum = needs.breakdown.values.sum()
        assertTrue(sum > city.population.population)
        assertNotNull("the cap is noted", rows.firstOrNull { it.style == NeedsRow.Style.Note && it.label.contains("at most [4]") })
        assertEquals("aggregated is capped at the population", 4, needs.aggregated)

        val details = rows.filter { it.label.startsWith("[Poverty]:") }
        assertEquals(1, details.size)
        assertEquals(1, details.first().indent)
        assertTrue(details.first().label.endsWith("per citizen"))
    }

    @Test
    fun `a puppet shows the puppet line and no needs`() {
        world.addCity(4)
        val puppet = world.addCity(8)
        puppet.isPuppet = true
        world.medians.basic = 500
        world.medians.gold = 500
        world.medians.science = 500
        world.medians.culture = 500
        world.refresh()

        val rows = NeedsRows.cityRows(puppet)
        assertEquals("a quarter of the citizens", "2", rows.row("Puppet")?.value)
        assertNotNull(rows.row("A puppet city has no needs of its own"))
        for (need in listOf("Distress", "Poverty", "Illiteracy", "Boredom", "Religious Unrest", "Starvation", "Isolation", "Empire"))
            assertNull("a puppet has no $need", rows.row(need))
        assertTrue("no details of yields", rows.none { it.label.contains("yield against") })
        assertEquals("Local Unhappiness", rows.first().label)
        assertEquals("2", rows.first().value)
    }

    @Test
    fun `the empire table lists approval, the three thresholds, the pool entries and the share`() {
        world.setStartingHappiness(10)
        val capital = world.addCity(3)
        val second = world.addCity(3)
        world.refresh()
        val needs = civ.stats.needs
        val poolEntries = needs.poolBreakdown.entries.filter { it.value.roundToInt() != 0 }
        assertTrue("the starting happiness is an entry of the pool", poolEntries.isNotEmpty())

        val rows = NeedsRows.empireRows(civ)
        assertEquals("Approval rating", rows.first().label)
        assertEquals("${civ.getApproval()}%", rows.first().value)
        assertEquals(listOf("50%", "35%", "20%"), listOf("Unhappy below", "Very unhappy below", "Super unhappy below").map { rows.row(it)?.value })

        val poolIndex = rows.indexOfFirst { it.label == "Happiness of the empire" }
        assertTrue(poolIndex > 0)
        assertEquals(needs.pool.toString(), rows[poolIndex].value)
        for ((source, amount) in poolEntries)
            assertEquals(amount.roundToInt().toString(), rows.drop(poolIndex).row(source)?.value)

        val shares = rows.dropWhile { it.label != "Share of the cities" }
        assertEquals(listOf("Share of the cities", capital.name, second.name), shares.map { it.label })
        val capitalShare = capital.cityStats.needs
        assertTrue("the starting happiness reaches the cities", capitalShare.happinessFromEmpire > 0)
        assertEquals("+${capitalShare.happinessFromEmpire} / -${capitalShare.unhappinessFromEmpire}", shares[1].value)

        val one = NeedsRows.empireRows(civ, second)
        assertNull(one.row("Share of the cities"))
        assertEquals(second.cityStats.needs.happinessFromEmpire.toString(), one.dropWhile { it.label != "Share of this city" }.row("Happiness")?.value)
        assertEquals(second.cityStats.needs.unhappinessFromEmpire.toString(), one.dropWhile { it.label != "Share of this city" }.row("Unhappiness")?.value)
    }

    @Test
    fun `an empire under a threshold has the line of that threshold marked`() {
        world.addCity(4)
        world.addCity(4)
        world.medians.basic = 500
        world.refresh()
        assertTrue(civ.isEmpireUnhappy())
        val rows = NeedsRows.empireRows(civ)
        assertTrue(rows.first().isAlert)
        assertTrue(rows.row("Unhappy below")!!.isAlert)
        assertEquals(civ.isEmpireSuperUnhappy(), rows.row("Super unhappy below")!!.isAlert)
    }

    @Test
    fun `the top bar tooltip shows the approval percentage`() {
        world.addCity(3)
        world.refresh()
        val tooltip = NeedsRows.approvalTooltip(civ)
        assertNotNull(tooltip)
        assertTrue(tooltip!!.startsWith("Approval rating [${civ.getApproval()}]%"))
        assertTrue(tooltip.contains("unhappy below [50]%"))
        assertEquals("the stat is the approval less the threshold", civ.getApproval() - 50, civ.getHappiness())
    }

    @Test
    fun `without the needs model there are no rows and no tooltip`() {
        val city = world.addCity(4)
        world.refresh()
        world.constants.cityNeedsLikeVp = false
        assertTrue(NeedsRows.cityRows(city).isEmpty())
        assertTrue(NeedsRows.empireRows(civ).isEmpty())
        assertNull(NeedsRows.approvalTooltip(civ))
        assertFalse(NeedsRows.empireRows(civ, city).isNotEmpty())
    }
}
