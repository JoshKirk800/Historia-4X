package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vox Populi's unlock yields of a religion: Tithe on a tech, Church Property on a policy, Initiation Rites on an era (CvPlayer::doInstantYield's TECH,
 * POLICY_UNLOCK and ERA_UNLOCK, V:CvPlayer.cpp:26074-26098, 26134-26152), by the followers or cities of the religion up to a limit, and in the holy city.
 */
@RunWith(GdxTestRunner::class)
class ReligionUnlockYieldTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var holyCity: City
    private lateinit var ours: Religion

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(9)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(0, 0), initialPopulation = 3)
        holyCity = game.addCity(civ, game.getTile(5, 0), initialPopulation = 2)
        ours = game.addReligion(civ)
        founderBelief() // a religion, not a pantheon
        holyCity.religion.religionThisIsTheHolyCityOf = ours.name
        civ.religionManager.invalidateStateReligion()
    }

    private fun founderBelief(vararg uniques: String) = ours.addBeliefs(listOf(game.createBelief(BeliefType.Founder, *uniques)))

    /** Every citizen of the city follows [religion] */
    private fun City.follow(religion: Religion) {
        this.religion.addPressure(religion.name, 5000)
        this.religion.removeAllPressuresExceptFor(religion.name)
        civ.religionManager.invalidateStateReligion()
    }

    private fun followers(cap: Int) = Countables.getCountableAmount("Followers of our religion up to [$cap]", GameContext(civ))

    @Test
    fun `the followers of our religion are those in the cities of every civilization, up to the limit`() {
        assertEquals(0, followers(250)) // nobody follows it yet
        holyCity.follow(ours)
        val other = game.addCiv()
        val foreignCity = game.addCity(other, game.getTile(-5, 0), initialPopulation = 4)
        foreignCity.follow(ours)

        assertEquals(6, followers(250))
        assertEquals(5, followers(5))
    }

    @Test
    fun `without a religion we have the founder benefits of there are no followers of ours`() {
        holyCity.follow(ours)
        holyCity.religion.religionThisIsTheHolyCityOf = null // the founded religion without its holy city
        civ.religionManager.invalidateStateReligion()

        assertEquals(0, followers(250))
    }

    @Test
    fun `a tech gives a founder belief's yield for each follower, Food in the holy city`() {
        holyCity.follow(ours)
        founderBelief("Gain [2] [Gold] <upon discovering [All] technology> <for every [Followers of our religion up to [250]]>",
            "Gain [15] [Food] <upon discovering [All] technology>")
        val gold = civ.gold
        val capitalFood = capital.population.foodStored
        val holyFood = holyCity.population.foodStored

        civ.tech.addTechnology("Pottery")

        assertEquals(4, civ.gold - gold) // 2 for each of the 2 followers
        assertEquals(15, holyCity.population.foodStored - holyFood)
        assertEquals(0, capital.population.foodStored - capitalFood)
    }

    @Test
    fun `a founder belief's Food of a city's event goes to that city`() {
        founderBelief("Gain [15] [Food] <upon population growth>")
        game.gameInfo.turns = 5 // a city grows for instant yields only after the turn it was founded
        val capitalFood = capital.population.foodStored
        val holyFood = holyCity.population.foodStored

        capital.population.addPopulation(1)

        assertEquals(15, capital.population.foodStored - capitalFood)
        assertEquals(0, holyCity.population.foodStored - holyFood)
    }

    @Test
    fun `any policy and the opening of a branch fire upon adopting a policy, the policy for completing the branch does not`() {
        game.createBuilding("Gain [10] [Gold] <upon adopting a policy>").also { capital.cityConstructions.addBuilding(it) }
        val tradition = game.ruleset.policyBranches["Tradition"]!!
        civ.policies.freePolicies = 10
        val gold = civ.gold

        civ.policies.adopt(tradition)
        for (policy in tradition.policies.dropLast(1)) civ.policies.adopt(policy) // the last one completes the branch, adopted with the one before

        assertEquals(true, civ.policies.isAdopted(tradition.policies.last().name))
        assertEquals(10 * tradition.policies.size, civ.gold - gold) // the branch and its policies, not the finisher
    }

    @Test
    fun `with repeatTechsGiveNoInstantYieldsLikeVp a repeated tech fires no instant yields, another tech does`() {
        game.ruleset.modOptions.constants.repeatTechsGiveNoInstantYieldsLikeVp = true
        game.createBuilding("Gain [5] [Gold] <upon discovering [All] technology>").also { capital.cityConstructions.addBuilding(it) }
        val repeated = game.ruleset.technologies.values.first { it.isContinuallyResearchable() }.name
        val gold = civ.gold

        civ.tech.addTechnology(repeated)
        assertEquals(0, civ.gold - gold)
        civ.tech.addTechnology("Pottery")
        assertEquals(5, civ.gold - gold)
    }

    @Test
    fun `without repeatTechsGiveNoInstantYieldsLikeVp a repeated tech fires instant yields`() {
        game.createBuilding("Gain [5] [Gold] <upon discovering [All] technology>").also { capital.cityConstructions.addBuilding(it) }
        val repeated = game.ruleset.technologies.values.first { it.isContinuallyResearchable() }.name
        val gold = civ.gold

        civ.tech.addTechnology(repeated)
        assertEquals(5, civ.gold - gold)
    }
}
