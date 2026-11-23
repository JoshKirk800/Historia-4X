package com.unciv.models.ruleset

import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The military activation of the Brave New World ruleset (P6 M4): every switch of the staged slices is on with the numbers of Vox Populi 5.4.8
 *  (DefineChanges.sql), the accepted Vox Populi units and promotions are in Units.json and UnitPromotions.json, and the buildings, terrains and
 *  game speeds carry the data the rules read.
 */
@RunWith(GdxTestRunner::class)
class MilitaryContentTests {
    private val ruleset = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset
    private val constants get() = ruleset.modOptions.constants

    // region the switches and their numbers

    @Test
    fun `every military switch is on in the Brave New World ruleset`() {
        val on = mapOf(
            "combatHealthStrengthLikeVp" to constants.combatHealthStrengthLikeVp,
            "combatXpFirstAttackOnly" to constants.combatXpFirstAttackOnly,
            "combatXpCapsLikeVp" to constants.combatXpCapsLikeVp,
            "unitHealingLikeVp" to constants.unitHealingLikeVp,
            "stateReligionHealingLikeVp" to constants.stateReligionHealingLikeVp,
            "cityCombatLikeVp" to constants.cityCombatLikeVp,
            "embarkAwarePathingLikeVp" to constants.embarkAwarePathingLikeVp,
            "retroactiveCityPromotionsLikeVp" to constants.retroactiveCityPromotionsLikeVp,
            "civilianPromotionApplicabilityLikeVp" to constants.civilianPromotionApplicabilityLikeVp,
            "enemyCombatAurasLikeVp" to constants.enemyCombatAurasLikeVp,
            "unitMaxHitPointsLikeVp" to constants.unitMaxHitPointsLikeVp,
            "mountedUnitFlagLikeVp" to constants.mountedUnitFlagLikeVp,
            "captureClassLikeVp" to constants.captureClassLikeVp,
            "unitSupplyLikeVp" to constants.unitSupplyLikeVp,
            "flankPowerLikeVp" to constants.flankPowerLikeVp,
            "terrainMovementPromotionsLikeVp" to constants.terrainMovementPromotionsLikeVp,
            "scoutingXpLikeVp" to constants.scoutingXpLikeVp,
            "pillageXpLikeVp" to constants.pillageXpLikeVp,
            "giftPromotionLossLikeVp" to constants.giftPromotionLossLikeVp,
            "cityStateGiftUnitFlagsLikeVp" to constants.cityStateGiftUnitFlagsLikeVp,
            "airBasingLikeVp" to constants.airBasingLikeVp,
            "airDefenseLikeVp" to constants.airDefenseLikeVp,
            "airInterceptionLikeVp" to constants.airInterceptionLikeVp,
            "conquestLikeVp" to constants.conquestLikeVp,
            "barbariansLikeVp" to constants.barbariansLikeVp,
        )
        assertEquals(emptyList<String>(), on.filterValues { !it }.keys.toList())
    }

    @Test
    fun `the switches of other rulesets stay off`() {
        val defaults = ModConstants()
        assertFalse(defaults.cityCombatLikeVp)
        assertFalse(defaults.unitSupplyLikeVp)
        assertFalse(defaults.conquestLikeVp)
        assertFalse(defaults.barbariansLikeVp)
        assertFalse(defaults.airBasingLikeVp)
        assertFalse(defaults.captureClassLikeVp)
    }

    @Test
    fun `the numbers are those of Vox Populi`() {
        assertEquals(45, constants.maxXPfromBarbarians)
        assertEquals(70, constants.maxXPfromCityStates)
        assertEquals(3, constants.embarkedUnitMovement)
        assertEquals(listOf(5, 10, 15, 20), listOf(constants.enemyUnitHeal, constants.neutralUnitHeal, constants.friendlyUnitHeal, constants.cityUnitHeal))
        assertEquals(50, constants.combatDamage.rangedCivilianDamage)
        assertEquals(2, constants.airBasing.cityAirUnitCapacity)
        assertEquals(500, constants.airBasing.rebaseRangeMultiplier)
        val supply = constants.unitSupplyConstants
        assertEquals(100, supply.baseTechReductionPerEra)
        assertEquals(100, supply.citiesTechReductionMultiplier)
        assertEquals(100, supply.populationTechReductionMultiplier)
        assertEquals(5, supply.empireSizePenaltyPerCity)
        assertEquals(5, supply.productionPenaltyPerUnit)
        assertEquals(5, supply.growthPenaltyPerUnit)
        val city = constants.cityCombat
        assertEquals(250, city.baseHealth)
        assertEquals(8, city.healthPerPopulation)
        assertEquals(500, city.baseStrengthHundredths)
        assertEquals(200, city.hillStrengthHundredths)
        assertEquals(200, city.landGarrisonDivisor)
        assertEquals(400, city.navalGarrisonDivisor)
        assertEquals(0, city.rangedStrengthModifier)
        assertEquals(8, city.healing)
    }

    @Test
    fun `a captured unit keeps a quarter of its health as in the source`() {
        val conquest = constants.conquestConstants
        assertEquals(75, conquest.capturePopulationPercent)
        assertEquals(5, conquest.conquestReductionBoost)
        assertEquals(75, conquest.combatCaptureHealthPercent)
        assertTrue(conquest.combatCaptureHealthIsDamage)
    }

    @Test
    fun `the defense process of the city is read from the json`() {
        val process = constants.cityCombat.defenseProcesses["Defense"]
        assertNotNull(process)
        assertEquals(CityDefenseProcess(10, 1, 15, "Military Strategy"), process)
        assertTrue(ruleset.technologies.containsKey(process!!.requiredTech))
    }

    // endregion
    // region the data

    @Test
    fun `the hills defend with 10 percent and the game speeds scale experience`() {
        assertEquals(0.1f, ruleset.terrains["Hill"]!!.defenceBonus, 0.0001f)
        assertEquals(0.1f, ruleset.terrains["Mountain"]!!.defenceBonus, 0.0001f)
        assertEquals(0.05f, ruleset.terrains["Marsh"]!!.defenceBonus, 0.0001f)
        assertEquals(2, ruleset.terrains["Desert"]!!.movementCost)
        assertEquals(listOf(67, 100, 150, 300), listOf("Quick", "Standard", "Epic", "Marathon").map { ruleset.speeds[it]!!.experiencePercent })
    }

    @Test
    fun `the buildings that defend against aircraft or keep their effect when captured say so`() {
        fun uniques(name: String) = ruleset.buildings[name]!!.uniques
        assertTrue("[15] air defense" in uniques("Military Base"))
        assertTrue("[10] air defense" in uniques("Airport"))
        assertTrue("Has a [100]% chance of surviving when the city is captured" in uniques("Cathedral"))
        assertTrue("Destroyed when the city is captured" in uniques("Military Base"))
    }

    @Test
    fun `the accepted Vox Populi units and promotions are in the ruleset`() {
        // a unit and a promotion that only Vox Populi has
        assertNotNull(ruleset.units["Cruiser"])
        assertNotNull(ruleset.units["Attack Submarine"])
        assertNotNull(ruleset.unitPromotions["Charge I"])
        assertNotNull(ruleset.unitPromotions["Eagle Hunter"])
        // a promotion with a strength form: Shock I is +10% Strength and +5% to flank attack bonuses in Vox Populi, not 15% in open terrain
        assertTrue("[+10]% Strength" in ruleset.unitPromotions["Shock I"]!!.uniques)
        // a Brave New World unique unit that Vox Populi has as another unit of the same name keeps its object
        assertEquals("Greece", ruleset.units["Companion Cavalry"]!!.uniqueTo)
        assertEquals("Brazil", ruleset.units["Pracinha"]!!.uniqueTo)
        // the renamed units of Vox Populi are not in as a second object of the same unit
        assertNull(ruleset.units["Fusilier"])
        assertNull(ruleset.units["Heavy Bomber"])
    }

    // endregion
}
