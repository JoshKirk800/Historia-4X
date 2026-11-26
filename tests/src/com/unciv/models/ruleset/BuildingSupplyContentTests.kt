package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The unit supply of the Brave New World ruleset's buildings (`phaseE/m6-supply-deep.md` section 9): Vox Populi 5.4.8's `Buildings.CitySupplyFlat`, `CitySupplyModifier`,
 *  `CitySupplyModifierGlobal` and `CitySupplyFlatGlobal` (`ref/db/vp.sqlite`; CvCity::processBuilding V:CvCity.cpp:13656-13657, CvPlayer::processBuilding V:CvPlayer.cpp:16086-16087)
 *  as the uniques of `unitSupplyBuildingsLikeVp`. The rows are data only: no ruleset sets the switch, so no game reads them until the content flip of the supply plan.
 *
 *  The six VP buildings with a supply column that the ruleset does not have (Qila, Ostrog, Penitentiary, Fornix, Piazza San Marco, Arsenale di Venezia) are not rows here.
 */
@RunWith(GdxTestRunner::class)
class BuildingSupplyContentTests {
    private val ruleset = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset   // TestGame loads the rulesets when the class is run alone

    /** By name, so that the class compiles (and fails) where the player-wide unique does not exist yet */
    private val allCitizens = UniqueType.entries.firstOrNull { it.name == "UnitSupplyPercentFromAllCitizens" }
    private val supplyTypes = listOfNotNull(UniqueType.UnitSupplyInCity, UniqueType.UnitSupplyPercentFromCitizens, allCitizens, UniqueType.UnitSupplyPerCity)

    /** The supply uniques of a building as `type -> amounts` */
    private fun supplyOf(name: String): Map<UniqueType, List<Int>> =
        ruleset.buildings[name]!!.uniqueObjects.filter { it.type in supplyTypes }
            .groupBy({ it.type!! }, { it.params[0].toInt() })

    // name -> (CitySupplyFlat, CitySupplyModifier, CitySupplyModifierGlobal, CitySupplyFlatGlobal), typed from the database
    private val vp = mapOf(
        "Stable" to listOf(1, 0, 0, 0), "Harbor" to listOf(1, 0, 0, 0), "Barracks" to listOf(1, 0, 0, 0), "Armory" to listOf(1, 0, 0, 0),
        "Military Academy" to listOf(1, 0, 0, 0), "Ducal Stable" to listOf(1, 0, 0, 0), "Homestead" to listOf(1, 0, 0, 0),
        "Schützenstand" to listOf(1, 0, 0, 0), "Teocalli" to listOf(1, 0, 0, 0), "Ikanda" to listOf(2, 0, 0, 0), "Barbican" to listOf(2, 0, 0, 0),
        "Great Wall" to listOf(3, 0, 0, 0), "Himeji Castle" to listOf(5, 0, 0, 0), "Terracotta Army" to listOf(5, 0, 0, 0), "Brandenburg Gate" to listOf(10, 0, 0, 0),
        "Walls" to listOf(0, 5, 0, 0), "Castle" to listOf(0, 5, 0, 0), "Arsenal" to listOf(0, 5, 0, 0), "Military Base" to listOf(0, 5, 0, 0),
        "Walls of Babylon" to listOf(0, 5, 0, 0), "Bastion Fort" to listOf(0, 5, 0, 0), "Seaport" to listOf(0, 10, 0, 0), "Heroic Epic" to listOf(0, 10, 0, 0),
        "Lamassu Gate" to listOf(0, 10, 0, 0), "The Motherland Calls" to listOf(0, 100, 0, 0), "Red Fort" to listOf(0, 0, 5, 0), "Royal Guardhouse" to listOf(0, 0, 10, 0),
        "Pentagon" to listOf(0, 0, 0, 1)
    )

    @Test
    fun `every building with a supply column in Vox Populi has exactly its numbers as uniques`() {
        for ((name, columns) in vp) {
            val expected = mutableMapOf<UniqueType, List<Int>>()
            if (columns[0] != 0) expected[UniqueType.UnitSupplyInCity] = listOf(columns[0])
            if (columns[1] != 0) expected[UniqueType.UnitSupplyPercentFromCitizens] = listOf(columns[1])
            if (columns[2] != 0) expected[allCitizens ?: UniqueType.Unbuildable] = listOf(columns[2])
            if (columns[3] != 0) expected[UniqueType.UnitSupplyPerCity] = listOf(columns[3])
            assertEquals(name, expected, supplyOf(name))
        }
    }

    @Test
    fun `no other building of the ruleset carries a supply unique`() {
        val others = ruleset.buildings.values.filter { it.name !in vp && it.uniqueObjects.any { u -> u.type in supplyTypes } }.map { it.name }
        assertEquals(emptyList<String>(), others)
    }

    @Test
    fun `the ruleset does not play the supply of buildings yet`() {
        assertFalse(ruleset.modOptions.constants.unitSupplyBuildingsLikeVp)
    }
}
