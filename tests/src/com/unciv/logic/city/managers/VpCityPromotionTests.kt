package com.unciv.logic.city.managers

import com.unciv.models.ruleset.unit.Promotion
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** VP CvCity::processBuilding and SetRetroactivePromotion: origin and eligible class control free grants. */
@RunWith(BaseTestRunner::class)
class VpCityPromotionTests {
    @Test fun civilianSpecificPromotionUsesSharedApplicability() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(0, 0), replacePalace = true)
        val worker = game.addUnit("Worker", civ, game.getTile(1, 0))
        worker.originCityId = city.id
        val promotion = Promotion().apply { name = "Worker training fixture"; unitTypes = listOf(worker.name) }
        game.ruleset.unitPromotions[promotion.name] = promotion
        game.ruleset.modOptions.constants.retroactiveCityPromotionsLikeVp = true
        game.ruleset.modOptions.constants.civilianPromotionApplicabilityLikeVp = true
        val building = game.createBuilding("All newly-trained [relevant] units [in this city] receive the [${promotion.name}] promotion")
        city.cityConstructions.addBuilding(building)
        assertTrue(promotion.name in worker.promotions.promotions)
    }

    @Test fun onlyEligibleUnitsFromTheCityGainThePromotion() {
        val game = TestGame()
        game.makeHexagonalMap(6)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(0, 0), replacePalace = true)
        val other = game.addCity(civ, game.getTile(4, 0))
        val trained = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(1, 0))
        val elsewhere = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(3, 0))
        trained.originCityId = city.id
        elsewhere.originCityId = other.id
        val promotion = Promotion().apply { name = "City training fixture"; unitTypes = listOf(trained.type.name) }
        game.ruleset.unitPromotions[promotion.name] = promotion
        val building = game.createBuilding("All newly-trained [relevant] units [in this city] receive the [${promotion.name}] promotion")
        city.cityConstructions.addBuilding(building)
        assertFalse(promotion.name in trained.promotions.promotions)
        game.ruleset.modOptions.constants.retroactiveCityPromotionsLikeVp = true
        city.cityConstructions.removeBuilding(building)
        assertTrue(promotion.name in trained.promotions.promotions)
        assertFalse(promotion.name in elsewhere.promotions.promotions)
    }
}
