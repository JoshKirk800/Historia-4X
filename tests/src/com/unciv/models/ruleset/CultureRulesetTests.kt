package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The tourism and culture victory data of Brave New World */
@RunWith(GdxTestRunner::class)
class CultureRulesetTests {
    private val bnw = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
    private val gnk = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!

    @Test
    fun `the culture victory of Brave New World is won by influence, of Gods and Kings by the Utopia Project`() {
        assertEquals(listOf("Be [Influential] over all other civilizations"), bnw.victories["Cultural"]!!.milestones)
        assertFalse("Utopia Project" in bnw.buildings)
        assertTrue("Build [Utopia Project]" in gnk.victories["Cultural"]!!.milestones)
    }

    @Test
    fun `the Hotel turns landmarks and Great Works into tourism`() {
        // Vox Populi's Hotel: 25% (Brave New World: 50%, and the Airport and the National Visitor Center did the same; the Airport makes no tourism in Vox Populi and
        // the National Visitor Center is not in the ruleset, see BuildingYieldContentTests)
        val uniques = bnw.buildings["Hotel"]!!.uniques
        assertTrue("[+25]% Tourism from Great Works [in this city]" in uniques)
        assertTrue("[25]% of the Culture from wonders, natural wonders and improved tiles becomes Tourism [in this city]" in uniques)
        assertFalse("National Visitor Center" in bnw.buildings)
    }

    @Test
    fun `the Internet adds half to tourism unless the target has the Great Firewall`() {
        assertTrue("[+50]% Tourism [in all cities] unless the target has a [Great Firewall]" in bnw.technologies["The Internet"]!!.uniques)
        assertTrue("Great Firewall" in bnw.buildings)
    }

    @Test
    fun `Cultural Exchange adds fifteen percent to three relations and is where the original has it`() {
        val policy = bnw.policies["Cultural Exchange"]!!
        assertEquals(listOf("Flourishing of the Arts"), policy.requires)
        assertEquals(3, policy.uniques.count { it.startsWith("[+15]% Tourism towards civilizations with [") })
        assertTrue(policy in bnw.policyBranches["Aesthetics"]!!.policies)
        assertEquals("the branch still ends with its finisher", "Aesthetics Complete", bnw.policyBranches["Aesthetics"]!!.policies.last().name)
    }

    @Test
    fun `sites appear with Archaeology, and are dug by an archaeologist whose finds are chosen`() {
        assertTrue("Archaeological sites appear around the world" in bnw.technologies["Archaeology"]!!.uniques)
        assertEquals("Archaeology", bnw.tileResources["Antiquity site"]!!.revealedBy)
        assertEquals("the policy that completes Exploration", "Exploration Complete", bnw.tileResources["Hidden Antiquity site"]!!.revealedBy)
        assertTrue(bnw.policies.containsKey("Exploration Complete"))

        val digging = bnw.tileImprovements["Archaeological Dig"]!!
        assertEquals(6, digging.turnsToBuild)
        for (unique in listOf("Excavate what was found here", "Remove [Antiquity site] resources from this tile", "Remove [Hidden Antiquity site] resources from this tile", "Can be built outside your borders"))
            assertTrue(unique, unique in digging.uniques)

        val archaeologist = bnw.units["Archaeologist"]!!
        assertEquals(200, archaeologist.cost)
        assertEquals("Archaeology", archaeologist.requiredTech)
        assertTrue("Can build [Archaeological Dig] improvements on tiles" in archaeologist.uniques)
        assertTrue("Only available <in cities with a [University]>" in archaeologist.uniques)
    }

    @Test
    fun `a Landmark is only made by archaeology, the Great Artist of Brave New World cannot, that of Gods and Kings can`() {
        val landmark = bnw.tileImprovements["Landmark"]!!
        assertTrue("Unbuildable" in landmark.uniques)
        assertTrue("[+1 Culture] for every era since the site of this improvement was formed" in landmark.uniques)
        assertEquals(0f, landmark.culture, 0f)
        assertFalse(bnw.units["Great Artist"]!!.uniques.any { "Landmark" in it })
        assertTrue(gnk.units["Great Artist"]!!.uniques.any { "Landmark" in it })
    }

    @Test
    fun `the Eiffel Tower and the Mughal Fort of the original make no tourism in Vox Populi's data`() {
        // Vox Populi's Eiffel Tower has no tourism row (Brave New World's made 12 after Radio) and the Mughal Fort is not in the ruleset
        assertTrue(bnw.buildings["Eiffel Tower"]!!.uniques.none { "Tourism" in it })
        assertFalse("Mughal Fort" in bnw.buildings)
    }
}
