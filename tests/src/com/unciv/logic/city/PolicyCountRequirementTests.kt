package com.unciv.logic.city

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  A wonder that needs adopted policies (`Buildings.NumPoliciesNeeded`, CvPlayer::canConstruct, V:CvPlayer.cpp:14578-14650): the policies owned without the finishers (and
 *  without the branches themselves, which VP counts apart), fewer by the allied city-states over the number of Statecraft's finisher (`XCSAlliesLowersPolicyNeedWonders`,
 *  14585-14595). V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
@RunWith(BaseTestRunner::class)
class PolicyCountRequirementTests {
    private val game = TestGame()
    private val civ = game.addCiv()

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        game.addCity(civ, game.getTile(HexCoord.Zero))
    }

    private val city get() = civ.getCapital()!!
    private fun rejected(building: Building) = building.getRejectionReasons(city.cityConstructions).any { it.type == RejectionReasonType.RequiresPolicyCount }
    private fun adopt(count: Int) = repeat(count) { civ.policies.adopt(game.createPolicyInBranch(), true) }

    private fun TestGame.createPolicyInBranch() = createPolicy().also { createPolicyBranch(policy = it) }

    @Test
    fun `a building that needs policies is rejected until enough are adopted`() {
        val wonder = game.createBuilding("Requires [2] adopted Policies")
        assertTrue(rejected(wonder))
        adopt(1)
        assertTrue(rejected(wonder))
        adopt(1)
        assertFalse(rejected(wonder))
    }

    @Test
    fun `the branches and the finishers are not counted`() {
        val wonder = game.createBuilding("Requires [1] adopted Policies")
        val branch = game.createPolicyBranch()
        civ.policies.adopt(branch, true)
        civ.policies.adopt(branch.policies.single { it.name.endsWith(com.unciv.models.ruleset.Policy.branchCompleteSuffix) }, true)
        assertTrue(rejected(wonder))
        adopt(1)
        assertFalse(rejected(wonder))
    }

    @Test
    fun `allied city-states lower the number by one for each number of them that the policy names`() {
        val wonder = game.createBuilding("Requires [3] adopted Policies")
        val withPolicy = game.addCiv("Wonders need 1 fewer adopted Policy for every [3] allied City-States")
        game.addCity(withPolicy, game.getTile(HexCoord(4, 0)))
        val withPolicyCity = withPolicy.getCapital()!!
        fun rejectedFor() = wonder.getRejectionReasons(withPolicyCity.cityConstructions).any { it.type == RejectionReasonType.RequiresPolicyCount }

        assertTrue("no allies", rejectedFor())
        for (place in listOf(HexCoord(-4, 0), HexCoord(0, 4), HexCoord(-2, 3))) {
            val cityState = game.addCiv(cityStateType = "Cultured")
            game.addCity(cityState, game.getTile(place))
            cityState.allyCiv = withPolicy
        }
        // 3 allies / 3 = 1 fewer: 2 are needed
        assertTrue("0 of 2", rejectedFor())
        repeat(2) { withPolicy.policies.adopt(game.createPolicyInBranch(), true) }
        assertFalse("2 of 2", rejectedFor())
    }
}
