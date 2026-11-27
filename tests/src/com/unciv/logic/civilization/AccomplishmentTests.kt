package com.unciv.logic.civilization

import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Vox Populi's accomplishments, CvPlayer::CompleteAccomplishment, and `Completed [accomplishment] accomplishments` */
@RunWith(GdxTestRunner::class)
class AccomplishmentTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero))
        game.ruleset.modOptions.constants.accomplishmentsLikeVp = true
    }

    private fun count(accomplishment: Accomplishment) = civ.accomplishments[accomplishment.name]

    private fun branchOfEra(eraNumber: Int, vararg uniques: String): PolicyBranch {
        val branch = game.createPolicyBranch(*uniques, policy = game.createPolicy())
        branch.era = game.ruleset.eras.values.first { it.eraNumber == eraNumber }.name
        return branch
    }

    private fun finish(branch: PolicyBranch) {
        civ.policies.freePolicies += 2
        civ.policies.adopt(branch)
        civ.policies.adopt(branch.policies.first())
    }

    @Test
    fun `a finished policy branch counts by the era it needs`() {
        finish(branchOfEra(0))
        finish(branchOfEra(0))
        finish(branchOfEra(2))
        finish(branchOfEra(4))
        assertEquals(2, count(Accomplishment.AncientPolicyBranch))
        assertEquals(1, count(Accomplishment.MedievalPolicyBranch))
        assertEquals(1, count(Accomplishment.IndustrialPolicyBranch))
    }

    @Test
    fun `a finished branch that needs no era counts as ancient`() {
        finish(branchOfEra(0).apply { era = "" })
        assertEquals(1, count(Accomplishment.AncientPolicyBranch))
    }

    @Test
    fun `a branch of the second era counts as nothing and one that is only opened does not count`() {
        finish(branchOfEra(1))
        civ.policies.freePolicies += 1
        civ.policies.adopt(branchOfEra(0))
        assertTrue(civ.accomplishments.isEmpty())
    }

    @Test
    fun `only the first ideology counts`() {
        val ideologies = List(2) { branchOfEra(4, UniqueType.IdeologyBranch.text) }
        for (ideology in ideologies) {
            civ.policies.freePolicies += 1
            civ.policies.adopt(ideology)
        }
        assertEquals(1, count(Accomplishment.Ideology))
    }

    @Test
    fun `each chosen belief counts by its type`() {
        val manager = civ.religionManager
        manager.chooseBeliefs(listOf(game.createBelief(BeliefType.Pantheon)), useFreeBeliefs = true)
        manager.foundReligion(game.addUnit("Great Prophet", civ, capital.getCenterTile()))
        assertEquals(ReligionState.FoundingReligion, manager.religionState)
        manager.chooseBeliefs(listOf(game.createBelief(BeliefType.Founder), game.createBelief(BeliefType.Follower), game.createBelief(BeliefType.Follower)))
        assertEquals(1, count(Accomplishment.PantheonBelief))
        assertEquals(1, count(Accomplishment.FounderBelief))
        assertEquals(2, count(Accomplishment.FollowerBelief))
        assertEquals(0, count(Accomplishment.EnhancerBelief))
    }

    @Test
    fun `taking the last city of a civilization counts for the taker`() {
        val other = game.addCiv()
        DiplomacyEvents.onLastCityTaken(civ, other)
        assertEquals(1, count(Accomplishment.EliminatedCivilization))
        assertEquals(0, other.accomplishments[Accomplishment.EliminatedCivilization.name])
    }

    @Test
    fun `without accomplishmentsLikeVp nothing counts`() {
        game.ruleset.modOptions.constants.accomplishmentsLikeVp = false
        val other = game.addCiv() // before the made-up branch, which a new civilization's era check cannot read
        finish(branchOfEra(0))
        civ.religionManager.chooseBeliefs(listOf(game.createBelief(BeliefType.Pantheon)), useFreeBeliefs = true)
        DiplomacyEvents.onLastCityTaken(civ, other)
        assertTrue(civ.accomplishments.isEmpty())
    }

    @Test
    fun `an improvement and a building yield for every time the accomplishment was completed`() {
        val tile = game.getTile(HexCoord(1, 0))
        game.addTileToCity(capital, tile)
        tile.setImprovementBasic(game.createTileImprovement("[+1 Culture] <for every [Completed [Eliminated civilization] accomplishments]>"))
        val building = game.createBuilding("[+2 Gold] [in this city] <for every [Completed [Eliminated civilization] accomplishments]>")
        capital.cityConstructions.addBuilding(building)
        val others = List(2) { game.addCiv() }
        fun cultureOfTile() = tile.stats.getTileStats(capital, civ).culture
        fun goldOfBuilding() = capital.cityStats.baseStatTree.children["Buildings"]!!.totalStats.gold
        val cultureBefore = cultureOfTile()
        val goldBefore = goldOfBuilding()
        for (other in others) DiplomacyEvents.onLastCityTaken(civ, other)
        assertEquals(cultureBefore + 2, cultureOfTile())
        assertEquals("the city updates its yields", goldBefore + 4, goldOfBuilding())
    }

    @Test
    fun `a copy of the game keeps the counts`() {
        finish(branchOfEra(2))
        val copy = game.gameInfo.clone().getCivilization(civ.civName)
        assertEquals(1, copy.accomplishments[Accomplishment.MedievalPolicyBranch.name])
    }
}
