package com.unciv.models.ruleset

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The data of the Brave New World ruleset plays Vox Populi's per-city needs and happiness (Release-5.4.8, database `vp` of tools/civ5db.py: the
 *  columns of the Buildings, Policies, HandicapInfos, GameSpeeds, Features, Traits and Resources tables that the needs read, and the defines
 *  of `(1) Community Patch/Database Changes/Defines/CoreDefineChanges.sql` and `(2) Vox Populi/Database Changes/DefineChanges.sql`).
 *  The expected values are typed here from that database, and `python -m rulegen check bnw all` (tools/rulegen, run from the workspace) compares the
 *  whole ruleset with the Brave New World database and lists each of these differences as explained.
 *
 *  What this ruleset does not take over, because Vox Populi's row is another object there (the policy tree, the belief list, the unique buildings'
 *  building classes: P2's content): the policies Vox Populi gives the same Type another name (Representation is its Equality), the beliefs, the
 *  Satrap's Court's Courthouse role. `phaseE/vp-needs-plan.md` section 2 has the counts.
 */
@RunWith(BaseTestRunner::class)
class NeedsRulesetTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)   // TestGame loads the rulesets when run alone
    private val ruleset = game.ruleset

    private val needUniquePrefixes = listOf("Reduces [", "Reduces the empire size need modifier", "The first [")
    private fun needUniques(uniques: List<String>) = uniques.filter { text -> needUniquePrefixes.any { text.startsWith(it) } }.sorted()

    @Test
    fun `the Brave New World ruleset turns the needs model on and the BNW unhappiness uniques are gone`() {
        val constants = ruleset.modOptions.constants
        assertTrue(constants.cityNeedsLikeVp)
        // GlobalUniques.json held the growth, production, gold, strength, settler and rebel effects of the original's happiness bands
        assertEquals(listOf("Requires establishing embassies to conduct advanced diplomacy", "[-2] Sight <for [Embarked] units>"),
            ruleset.allUniques().filter { it.sourceObjectType == UniqueTarget.GlobalUniques }.map { it.text }.toList())
        val original = ruleset.allUniques().map { it.text }.filter {
            it.contains("Unhappiness from [") || it.contains("unhappiness from the number of cities") || it.startsWith("Retain [") ||
                it.startsWith("Happiness from this building is not limited")
        }.toList()
        assertEquals("uniques of the original's model, which nothing reads under the needs model", emptyList<String>(), original)
    }

    /** Needs uniques that Vox Populi's database gives the buildings this ruleset has: the five FlatReduction columns (and the Global ones as `in all cities`),
     *  EmpireSizeModifierReduction (stored negative there) and NoUnhappfromXSpecialists */
    private val buildingNeeds = mapOf(
        "Wat" to listOf("Reduces [Religious Unrest] by [1] [in this city]"),
        "Circus" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Garden" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "Arena" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Zoo" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Temple" to listOf("Reduces [Religious Unrest] by [1] [in this city]"),
        "Museum" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Broadcast Tower" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Barracks" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Armory" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Military Academy" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Arsenal" to listOf("Reduces the empire size need modifier by [5] [in this city]"),
        "Walls" to listOf("Reduces the empire size need modifier by [5] [in this city]"),
        "Castle" to listOf("Reduces the empire size need modifier by [5] [in this city]"),
        "Military Base" to listOf("Reduces [Distress] by [1] [in this city]", "Reduces the empire size need modifier by [5] [in this city]"),
        "Hospital" to listOf("Reduces [Poverty] by [1] [in this city]", "The first [2] specialists cause no unhappiness [in this city]"),
        "Factory" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "Library" to listOf("Reduces [Illiteracy] by [1] [in this city]", "The first [1] specialists cause no unhappiness [in this city]"),
        "University" to listOf("Reduces [Illiteracy] by [1] [in this city]"),
        "Public School" to listOf("Reduces [Illiteracy] by [1] [in this city]"),
        "Research Lab" to listOf("Reduces [Illiteracy] by [1] [in this city]"),
        "Palace" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "School of Philosophy" to listOf("Reduces [Illiteracy] by [1] [in all cities]", "The first [1] specialists cause no unhappiness [in this city]"),
        "Circus Maximus" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Chartered Company" to listOf("Reduces [Poverty] by [1] [in all cities]"),
        "Customs House" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Grocer" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Bastion Fort" to listOf("Reduces the empire size need modifier by [5] [in this city]"),
        "Lamassu Gate" to listOf("Reduces the empire size need modifier by [5] [in this city]"),
        "Schützenstand" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Steam Mill" to listOf("The first [2] specialists cause no unhappiness [in this city]"),
        "Harappan Reservoir" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Barbican" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Pogost" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Huey Teocalli" to listOf("Reduces [Religious Unrest] by [1] [in all cities]", "Reduces [Religious Unrest] by [1] [in this city]"),
        "Great Cothon" to listOf("Reduces [Poverty] by [1] [in all cities]", "Reduces [Poverty] by [1] [in this city]"),
        "Palace Gardens" to listOf("The first [2] specialists cause no unhappiness [in this city]"),
        "Mandir" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Synagogue" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Order" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Teocalli" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Gurdwara" to listOf("Reduces [Distress] by [1] [in this city]"),
        "The Oracle" to listOf("Reduces [Illiteracy] by [1] [in this city]"),
        "Chichen Itza" to listOf("Reduces the empire size need modifier by [10] [in all cities]"),
        "Porcelain Tower" to listOf("Reduces [Illiteracy] by [1] [in this city]"),
        "Kremlin" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Forbidden Palace" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Aqueduct" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Statue of Zeus" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Temple of Artemis" to listOf("The first [2] specialists cause no unhappiness [in this city]"),
        "Walls of Babylon" to listOf("Reduces the empire size need modifier by [5] [in this city]"),
        "Ceilidh Hall" to listOf("Reduces [Boredom] by [1] [in this city]"),
        "Ikanda" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Royal Library" to listOf("Reduces [Illiteracy] by [1] [in all cities]", "The first [1] specialists cause no unhappiness [in this city]"),
        "Candi" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "Hanse" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Writers' Guild" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "Artists' Guild" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "Musicians' Guild" to listOf("The first [1] specialists cause no unhappiness [in this city]"),
        "Uffizi" to listOf("The first [1] specialists cause no unhappiness [in all cities]"),
        "Globe Theatre" to listOf("Reduces [Boredom] by [1] [in this city]", "The first [1] specialists cause no unhappiness [in all cities]"),
        "Constabulary" to listOf("Reduces [Distress] by [1] [in this city]"),
        "Police Station" to listOf("Reduces [Distress] by [1] [in this city]", "Reduces [Poverty] by [1] [in this city]",
            "Reduces [Illiteracy] by [1] [in this city]", "Reduces [Boredom] by [1] [in this city]"),
        "Cathedral" to listOf("Reduces [Poverty] by [1] [in this city]"),
        "Mosque" to listOf("Reduces [Illiteracy] by [1] [in this city]"),
        "Pagoda" to listOf("Reduces [Boredom] by [1] [in this city]", "Reduces [Religious Unrest] by [2] [in this city]"),
        "Grand Temple" to listOf("Reduces [Religious Unrest] by [1] [in this city]", "Reduces [Religious Unrest] by [1] [in all cities]"),
    ).mapValues { it.value.sorted() }

    @Test
    fun `every building Vox Populi gives a needs column has it in our ruleset with its value, and no other building has one`() {
        val missing = buildingNeeds.keys.filter { it !in ruleset.buildings }
        assertEquals("buildings of the table the ruleset does not have", emptyList<String>(), missing)
        val actual = ruleset.buildings.values.associate { it.name to needUniques(it.uniques) }.filterValues { it.isNotEmpty() }
        assertEquals(buildingNeeds, actual)
    }

    /** Buildings.Happiness plus UnmoddedHappiness, which the file writes as one number; the rows Vox Populi changes (0 where it zeroes them) */
    private val buildingHappiness = mapOf(
        "Arena" to 0, "Zoo" to 0, "Stadium" to 0, "Chichen Itza" to 0, "Taj Mahal" to 0, "Eiffel Tower" to 0, "Neuschwanstein" to 0,
        "Stone Works" to 0, "Cathedral" to 0, "Mosque" to 0, "Pagoda" to 0,
        "Circus" to 1, "Ceilidh Hall" to 1, "Satrap's Court" to 1, "Notre Dame" to 1, "Prora" to 1, "Circus Maximus" to 2,
    )

    @Test
    fun `Colosseum, Theatre, Stadium, Chichen Itza and Taj Mahal give no happiness, and the other happiness rows are Vox Populi's`() {
        val actual = buildingHappiness.mapValues { ruleset.buildings[it.key]!!.happiness.toInt() }
        assertEquals(buildingHappiness, actual)
        // the Courthouse and Satrap's Court have no needs unique of their own: removing the occupation unhappiness is still the Courthouse's
        assertTrue(ruleset.buildings["Courthouse"]!!.uniques.contains("Removes extra unhappiness from annexed cities"))
    }

    private fun policyUniques(name: String) = ruleset.policies[name]!!.uniques

    @Test
    fun `the policies that are the same policy in Vox Populi carry its needs values`() {
        assertTrue("[+2 Happiness] from every [Palace]" in policyUniques("Tradition"))
        assertTrue("Reduces [Religious Unrest] by [5] [in all cities]" in policyUniques("Secularism"))
        assertTrue("Reduces [Boredom] by [2] [in all cities]" in policyUniques("Avant Garde"))
        assertTrue("Reduces [Illiteracy] by [2] [in all cities]" in policyUniques("Academy of Sciences"))
        assertTrue("[+1 Happiness] [in all cities]" in policyUniques("Universal Suffrage"))
        assertTrue("[+1 Happiness] from every [Broadcast Tower]" in policyUniques("Media Culture"))
        val culturalExchange = policyUniques("Cultural Exchange")
        assertTrue("[+20]% golden age points from happiness" in culturalExchange)
        assertTrue("[1] Happiness per [3] Great Works [in all cities]" in culturalExchange)
        val capitalism = policyUniques("Capitalism")
        assertTrue("The first [2] specialists cause no unhappiness [in all cities]" in capitalism)
        assertTrue("[1] Happiness from the first [2] specialists [in all cities]" in capitalism)
    }

    @Test
    fun `the happiness rows Vox Populi no longer has for those policies are gone`() {
        for (name in listOf("Capitalism", "Academy of Sciences"))
            assertEquals(name, emptyList<String>(), policyUniques(name).filter { it.startsWith("[+1 Happiness] from every [") })
        // Policies.HalfSpecialistUnhappiness is 0 for Universal Suffrage
        assertFalse(policyUniques("Universal Suffrage").any { it.contains("Unhappiness from [Specialists") })
    }

    @Test
    fun `luxuries give 2 and the capital default and the difficulty bases are Vox Populi's`() {
        assertEquals(2, ruleset.modOptions.constants.needs.happinessPerLuxury)
        game.makeHexagonalMap(6)
        game.setDifficulty("Prince")
        val human = game.addCiv(isPlayer = true)
        val capital = game.addCity(human, game.getTile(0, 0), replacePalace = true)
        val luxuries = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury }.take(3)
        val spots = listOf(HexCoord(3, 0), HexCoord(-3, 0), HexCoord(0, 3))
        for ((index, luxury) in luxuries.withIndex()) {
            val city = game.addCity(human, game.getTile(spots[index]))
            city.getCenterTile().tileResource = luxury
            city.getCenterTile().resourceAmount = 1
            for (improvement in luxury.getImprovements())
                ruleset.tileImprovements[improvement]?.techRequired?.let { human.tech.addTechnology(it) }
        }
        human.cache.updateCivResources()
        for (city in human.cities) city.cityStats.update(updateCivStats = false)
        human.updateStatsForNextTurn()
        val breakdown = human.stats.getHappinessBreakdown()
        assertEquals("Prince: HappinessDefault 7", 7f, breakdown["Base happiness"]!!, 0f)
        assertEquals("three luxuries at 2", 6f, breakdown["Luxury resources"]!!, 0f)
        assertEquals("Prince: HappinessDefaultCapital 2", 2f, capital.cityStats.happinessList["Capital"]!!, 0f)

        val ai: Civilization = game.addCiv()
        assertEquals("an AI civilization adds AIHappinessDefault 8 to its own level's 0", 8f, ai.stats.getHappinessBreakdown()["Base happiness"]!!, 0f)
    }

    private data class Level(val base: Int, val capital: Int, val aiBase: Int, val aiCapital: Int, val populationNeed: Int, val luxury: Float)

    @Test
    fun `the difficulty levels hold HappinessDefault, HappinessDefaultCapital, the AI pair and PopulationUnhappinessMod of Vox Populi`() {
        val expected = mapOf(
            "Settler" to Level(9, 3, 8, 2, -30, 1f), "Chieftain" to Level(8, 3, 8, 2, -15, 0f), "Warlord" to Level(8, 2, 8, 2, 0, 0f),
            "Prince" to Level(7, 2, 8, 2, 0, 0f), "King" to Level(7, 2, 8, 2, 0, 0f), "Emperor" to Level(7, 1, 8, 2, 0, 0f),
            "Immortal" to Level(6, 1, 8, 2, 0, 0f), "Deity" to Level(5, 1, 8, 2, 0, 0f), "AI Default" to Level(0, 0, 0, 0, 0, 0f),
        )
        val actual = expected.keys.associateWith {
            val d = ruleset.difficulties[it]!!
            Level(d.baseHappiness, d.baseHappinessCapital, d.aiBaseHappiness, d.aiBaseHappinessCapital, d.populationNeedModifier, d.extraHappinessPerLuxury)
        }
        assertEquals(expected, actual)
        assertEquals(expected.keys, ruleset.difficulties.keys)
        for (level in ruleset.difficulties.values) {
            assertEquals(level.name, 0, level.empireSizeNeedModifier)
            assertEquals(level.name, 0, level.aiEmpireSizeNeedModifier)
            assertEquals(level.name, 0, level.aiPopulationNeedModifier)
        }
    }

    @Test
    fun `the game speeds start with GameSpeeds StartingHappiness`() {
        assertEquals(mapOf("Quick" to 0, "Standard" to 0, "Epic" to 1, "Marathon" to 2), ruleset.speeds.mapValues { it.value.startingHappiness })
    }

    @Test
    fun `natural wonders give no happiness for being in the borders and the traits of Vox Populi's zeroed columns are bare`() {
        // Features.InBorderHappiness is 0 for Old Faithful, the Fountain of Youth, Mount Kailash and Sri Pada
        assertEquals(emptyList<String>(), ruleset.terrains.values.filter { terrain -> terrain.uniques.any { it.endsWith("Happiness] while this tile is in your borders") } }.map { it.name })
        // Traits.CityUnhappinessModifier, PopulationUnhappinessModifier, NaturalWonderHappinessModifier and LuxuryHappinessRetention are 0
        assertEquals(emptyList<String>(), ruleset.nations["India"]!!.uniques)
        assertEquals(emptyList<String>(), ruleset.nations["The Netherlands"]!!.uniques)
        assertFalse(ruleset.nations["Spain"]!!.uniques.any { it.contains("Happiness") })
    }
}
