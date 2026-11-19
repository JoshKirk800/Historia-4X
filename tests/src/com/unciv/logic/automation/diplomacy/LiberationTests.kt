package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.CityConquestAutomation
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.ruleset.BeliefType
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvDiplomacyAI::DoPossibleMajorLiberation (Release-5.4.8) and its place in CvPlayerAI::AI_conquerCity ([CityConquestAutomation]):
 *  a is the AI that has taken a city of its founder b from c.
 */
@RunWith(GdxTestRunner::class)
class LiberationTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        game.addCity(b, game.getTile(HexCoord(10, -2)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        city = cityOf(b, HexCoord(10, 2))
        // The first contact starts a friendly approach, and a victory pursuit is chosen at the start: a neutral ground to test from
        for (other in listOf(b, c)) a.ai.diplomacy.memoryOf(other).approach = MajorCivApproach.Neutral
        a.ai.primaryVictoryPursuit = null
        a.ai.currentVictoryPursuit = null
    }

    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    /** A city that [founder] founded and c holds, as a is about to take it */
    private fun cityOf(founder: Civilization, at: HexCoord): City {
        val city = game.addCity(founder, game.getTile(at))
        city.puppetCity(c)
        return city
    }

    private fun wants(founder: Civilization = b, of: City = city) = LiberationAi.wantsToLiberateMajor(a, of, founder)

    private fun feel(opinion: MajorCivOpinion, approach: MajorCivApproach? = null, other: Civilization = b) {
        val memory = a.ai.diplomacy.memoryOf(other)
        memory.opinion = opinion
        memory.approach = approach
    }

    @Test
    fun `a neutral founder that is no friend of ours gets its city back from nobody`() {
        assertFalse(wants())
    }

    @Test
    fun `an original capital is never given back while going for world conquest, nor the holy city of another religion`() {
        feel(MajorCivOpinion.Ally)
        assertTrue(wants())
        city.isOriginalCapital = true
        assertTrue(wants())
        a.ai.currentVictoryPursuit = VictoryPursuit.Domination
        assertFalse(wants())
        a.ai.currentVictoryPursuit = null
        city.isOriginalCapital = false
        // The holy city of a religion that is not our state religion (a founded religion whose holy city we hold is the state religion)
        val ours = game.addReligion(a)
        ours.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        ReligionManager::class.java.getDeclaredField("religionState").apply { isAccessible = true }.set(a.religionManager, ReligionState.Religion)
        a.cities.first().religion.religionThisIsTheHolyCityOf = ours.name
        assertTrue("our own state religion's holy city is no obstacle", wants())
        city.religion.religionThisIsTheHolyCityOf = "Theirs"
        assertFalse(wants())
    }

    @Test
    fun `a founder that we hate gets nothing, whatever binds us`() {
        a.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.BattleBrothers
        feel(MajorCivOpinion.Enemy)
        assertFalse("opinion enemy", wants())
        feel(MajorCivOpinion.Ally, MajorCivApproach.Guarded)
        assertFalse("a guarded approach", wants())
        feel(MajorCivOpinion.Ally, MajorCivApproach.Deceptive)
        assertFalse("a deceptive one", wants())
        feel(MajorCivOpinion.Ally, MajorCivApproach.Friendly)
        assertTrue(wants())
        c.ai.diplomacy.memoryOf(b).friendDeclaredWarOnUs = true // b declared war on a friend of c's: untrustworthy
        assertFalse("an untrustworthy founder", wants())
    }

    @Test
    fun `an unhappy empire gives the city to a friend, not to a neutral`() {
        a.stats.happiness = -5
        feel(MajorCivOpinion.Neutral)
        assertFalse(wants())
        feel(MajorCivOpinion.Friend)
        assertTrue(wants())
    }

    @Test
    fun `defensive pact partners at war with the previous owner liberate each other, a coop war against it too`() {
        // (declaring a war ends the declarer's pacts with the target's friends, so the pact is made after the wars)
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertFalse("no war on c", wants())
        a.getDiplomacyManager(c)!!.declareWar()
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertFalse("b is not at war with c", wants())
        b.getDiplomacyManager(c)!!.declareWar()
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertTrue(wants())
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.Peace
        assertFalse(wants())
        CoopWarAi.entry(a, b, c).state = CoopWarState.Soon
        assertTrue("a coop war against c being prepared", wants())
        CoopWarAi.entry(a, b, c).state = CoopWarState.Rejected
        assertFalse(wants())
    }

    @Test
    fun `a liberator of ours, a civilization we resurrected, or a friend of the highest degree gets the city`() {
        a.ai.diplomacy.memoryOf(b).resurrectedUs = true
        assertTrue("it resurrected us", wants())
        a.ai.diplomacy.memoryOf(b).resurrectedUs = false
        assertFalse(wants())
        b.ai.diplomacy.memoryOf(a).resurrectedUs = true
        assertTrue("we resurrected it", wants())
        b.ai.diplomacy.memoryOf(a).resurrectedUs = false
        assertFalse(wants())
        a.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.Allies
        assertTrue(wants())
        a.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.Friends
        feel(MajorCivOpinion.Ally)
        assertTrue(wants())
    }

    @Test
    fun `a city liberated before is given back to a favorable founder unless the war goes badly, which leaves the friendship cases`() {
        a.ai.diplomacy.memoryOf(b).numCitiesLiberated = 1
        feel(MajorCivOpinion.Neutral)
        assertFalse("not favorable", wants())
        feel(MajorCivOpinion.Favorable)
        assertTrue(wants())
        // At war with c and losing, while b fights c too: a bad war decision
        a.getDiplomacyManager(c)!!.declareWar()
        b.getDiplomacyManager(c)!!.declareWar()
        a.ai.warStates.warStates[c.civID] = WarState.Defensive
        assertFalse(wants())
        a.ai.warStates.warStates[c.civID] = WarState.Offensive
        assertTrue(wants())
        a.ai.warStates.warStates.remove(c.civID)
        assertFalse("a war not assessed yet counts as bad", wants())
    }

    @Test
    fun `friends are given the city when going for diplomacy, or when we are friends in opinion and approach and the war goes well`() {
        feel(MajorCivOpinion.Friend, MajorCivApproach.Neutral)
        assertFalse(wants())
        a.ai.currentVictoryPursuit = VictoryPursuit.Diplomacy
        assertTrue(wants())
        a.ai.currentVictoryPursuit = null
        feel(MajorCivOpinion.Friend, MajorCivApproach.Friendly)
        assertTrue(wants())
        a.getDiplomacyManager(c)!!.declareWar()
        b.getDiplomacyManager(c)!!.declareWar()
        a.ai.warStates.warStates[c.civID] = WarState.Troubled
        assertFalse(wants())
        a.ai.currentVictoryPursuit = VictoryPursuit.Diplomacy
        assertTrue("going for diplomacy ignores the war", wants())
    }

    @Test
    fun `a friend of a culture victory seeker gets the city unless that victory is close and its influence over us is too small`() {
        a.ai.currentVictoryPursuit = VictoryPursuit.Culture
        feel(MajorCivOpinion.Friend, MajorCivApproach.Neutral)
        assertTrue(wants())
        feel(MajorCivOpinion.Neutral, MajorCivApproach.Friendly)
        assertTrue(wants())
        feel(MajorCivOpinion.Neutral, MajorCivApproach.Neutral)
        assertFalse("no friend", wants())
    }

    @Test
    fun `a civilization that is dead is brought back when going for diplomacy, when it was a liberator, an ally, or the war is no bad decision`() {
        val dead = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(dead)
        val deadCity = cityOf(dead, HexCoord(10, 6))
        assertTrue(dead.isDefeated())
        assertTrue("nothing speaks against it", wants(dead, deadCity))
        a.getDiplomacyManager(c)!!.declareWar()
        a.ai.warStates.warStates[c.civID] = WarState.Defensive
        assertFalse("a bad war decision", wants(dead, deadCity))
        a.ai.currentVictoryPursuit = VictoryPursuit.Diplomacy
        assertTrue(wants(dead, deadCity))
        a.ai.currentVictoryPursuit = null
        a.ai.diplomacy.memoryOf(dead).doFLevel = DoFLevel.Allies
        assertTrue(wants(dead, deadCity))
        a.ai.diplomacy.memoryOf(dead).doFLevel = DoFLevel.New
        a.ai.diplomacy.memoryOf(dead).resurrectedUs = true
        assertTrue(wants(dead, deadCity))
    }

    private fun choice() = CityConquestAutomation.chooseConquest(a, city)

    @Test
    fun `the conquest decision gives a city back to an ally of ours, which BNW did only for a pact or a diplomatic victory`() {
        assertEquals(CityConquestAutomation.ConquestChoice.Puppet, choice())
        feel(MajorCivOpinion.Ally)
        assertEquals(CityConquestAutomation.ConquestChoice.Liberate, choice())
    }

    @Test
    fun `a founder we hate is not given its city for a defensive pact, which BNW did`() {
        a.getDiplomacyManager(c)!!.declareWar()
        b.getDiplomacyManager(c)!!.declareWar()
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertEquals(CityConquestAutomation.ConquestChoice.Liberate, choice())
        feel(MajorCivOpinion.Enemy)
        assertTrue(choice() != CityConquestAutomation.ConquestChoice.Liberate)
    }

    @Test
    fun `an unhappy empire gives the city to a friend instead of razing it`() {
        a.stats.happiness = -5
        assertTrue(choice() != CityConquestAutomation.ConquestChoice.Liberate)
        feel(MajorCivOpinion.Friend)
        assertEquals(CityConquestAutomation.ConquestChoice.Liberate, choice())
    }

    @Test
    fun `a founder that is no major gets the original's rules, a city-state liberated for a friendly approach`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val csCity = cityOf(cityState, HexCoord(10, 6))
        assertEquals(CityConquestAutomation.ConquestChoice.Puppet, CityConquestAutomation.chooseConquest(a, csCity))
        a.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Friendly
        assertEquals(CityConquestAutomation.ConquestChoice.Liberate, CityConquestAutomation.chooseConquest(a, csCity))
    }
}
