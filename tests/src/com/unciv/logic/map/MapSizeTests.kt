package com.unciv.logic.map

import com.unciv.json.json
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.UncivMapSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sizes and map types a player can pick are Civ V's. What Unciv's old table named (a saved game's "Medium") still loads.
 */
@RunWith(BaseTestRunner::class)
class MapSizeTests {
    // region the sizes offered

    @Test
    fun `the predefined sizes are the world sizes of the Brave New World ruleset`() {
        RulesetCache.loadRulesets(noMods = true)
        val rows = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!.ai.worldSizes
        assertEquals(6, rows.size)
        assertEquals(rows.map { it.name }, MapSize.Predefined.entries.map { it.name })
        assertEquals(rows.map { it.gridWidth }, MapSize.Predefined.entries.map { it.width })
        assertEquals(rows.map { it.gridHeight }, MapSize.Predefined.entries.map { it.height })
    }

    @Test
    fun `the sizes are Civ V's six with the radius of the hexagon of as many tiles`() {
        val expected = listOf("Duel" to (40 to 24), "Tiny" to (56 to 36), "Small" to (66 to 42), "Standard" to (80 to 52), "Large" to (104 to 64), "Huge" to (128 to 80))
        assertEquals(expected, MapSize.Predefined.entries.map { it.name to (it.width to it.height) })
        for (size in MapSize.Predefined.entries) {
            assertEquals(size.name, HexMath.getEquivalentHexagonalRadius(size.width, size.height), size.radius)
            val copy = MapSize(size)
            assertEquals(size.name, copy.name)
            assertEquals(size.radius, copy.radius)
            assertEquals(size.width, copy.width)
            assertEquals(size.height, copy.height)
        }
        assertEquals("Duel", MapSize.Duel.name)
        assertEquals("Standard", MapSize.Standard.name)
        assertEquals(66, MapSize.Small.width)
        assertEquals(42, MapSize.Small.height)
    }

    @Test
    fun `the picker lists Duel Tiny Small Standard Large Huge and Auto`() {
        assertEquals(listOf("Duel", "Tiny", "Small", "Standard", "Large", "Huge", MapSize.auto), MapSize.names())
        assertFalse("Medium" in MapSize.names())
        assertEquals(MapSize.auto, MapSize(MapSize.auto).name)
    }

    @Test
    fun `a name that is not predefined any more makes the default Small and does not throw`() {
        assertEquals("Small", MapSize("Medium").name)
        assertEquals(66, MapSize("Medium").width)
        assertEquals("Small", MapSize("no such size").name)
    }

    // endregion
    // region sizes of saved games and saved settings

    @Test
    fun `the default of a map is the Medium that saved games rely on`() {
        val size = MapParameters().mapSize
        assertEquals("Medium", size.name)
        assertEquals(20, size.radius)
        assertEquals(44, size.width)
        assertEquals(29, size.height)
        val auto = MapSize(MapSize.auto)
        assertEquals(listOf(20, 44, 29), listOf(auto.radius, auto.width, auto.height))
    }

    @Test
    fun `a saved game's Medium is read as it was written and the picker shows it as Custom with its dimensions`() {
        val written = """{"type":"Pangaea","shape":"Rectangular","mapSize":{"name":"Medium","radius":20,"width":44,"height":29},"worldWrap":true,"seed":42}"""
        val parameters = json().fromJson(MapParameters::class.java, written)
        assertEquals("Medium", parameters.mapSize.name)
        assertEquals(listOf(20, 44, 29), listOf(parameters.mapSize.radius, parameters.mapSize.width, parameters.mapSize.height))
        assertEquals(MapType.pangaea, parameters.type)

        val shown = parameters.mapSize.forPicker()
        assertEquals(MapSize.custom, shown.name)
        assertEquals(listOf(20, 44, 29), listOf(shown.radius, shown.width, shown.height))
        // the caller's size is not changed
        assertEquals("Medium", parameters.mapSize.name)
    }

    @Test
    fun `a game that wrote no size is read with the default and survives a round trip`() {
        val omitted = json().fromJson(MapParameters::class.java, """{"type":"Pangaea","seed":7}""")
        assertEquals(listOf("Medium", 20, 44, 29), omitted.mapSize.let { listOf(it.name, it.radius, it.width, it.height) })

        val again = json().fromJson(MapParameters::class.java, json().toJson(omitted))
        assertEquals(listOf("Medium", 20, 44, 29), again.mapSize.let { listOf(it.name, it.radius, it.width, it.height) })
    }

    @Test
    fun `old saved settings with Medium load and the setup keeps the size`() {
        val settings = """{"gameParameters":{},"mapParameters":{"type":"Pangaea","shape":"Hexagonal","mapSize":{"name":"Medium","radius":20,"width":44,"height":29}}}"""
        val setup = json().fromJson(GameSetupInfo::class.java, settings)
        assertEquals("Medium", setup.mapParameters.mapSize.name)
        val cloned = GameSetupInfo(setup).mapParameters.mapSize
        assertEquals(listOf("Medium", 20, 44, 29), listOf(cloned.name, cloned.radius, cloned.width, cloned.height))
        val shown = cloned.forPicker()
        assertEquals(MapSize.custom, shown.name)
        assertEquals(listOf(20, 44, 29), listOf(shown.radius, shown.width, shown.height))
    }

    @Test
    fun `a picker keeps the sizes it offers and Custom as they are`() {
        for (size in listOf(MapSize.Duel, MapSize.Tiny, MapSize.Small, MapSize.Standard, MapSize.Large, MapSize.Huge, MapSize(MapSize.auto), MapSize(23, 15))) {
            val shown = size.forPicker()
            assertSame(size.name, size, shown)
        }
    }

    @Test
    fun `a size of Unciv's old table that has the name of an offered size is shown as Custom with its own dimensions`() {
        for (old in listOf(UncivMapSizes.Tiny, UncivMapSizes.Small, UncivMapSizes.Large, UncivMapSizes.Huge)) {
            val shown = old.forPicker()
            assertEquals(old.name, MapSize.custom, shown.name)
            assertEquals(old.name, listOf(old.radius, old.width, old.height), listOf(shown.radius, shown.width, shown.height))
            assertNotEquals(old.name, MapSize(old.name).width, shown.width)
        }
        assertEquals(33, UncivMapSizes.Small.forPicker().width)
        assertEquals(66, MapSize("Small").width)
    }

    // endregion
    // region the types offered

    @Test
    fun `only Civ V types are offered`() {
        assertTrue(MapType.allValues.isNotEmpty())
        for (type in MapType.allValues) assertTrue(type, type.endsWith(" (Civ V)"))
        assertEquals(MapType.allValues.size, MapType.allValues.toSet().size)
        assertEquals(MapType.continentsLikeCivV, MapType.allValues.first())
        assertFalse(MapType.archipelagoLikeCivV in MapType.allValues)
        assertFalse(MapType.tinyIslandsLikeCivV in MapType.allValues)
    }

    @Test
    fun `Unciv's own types are not offered but are still types that generate`() {
        val unciv = listOf("Pangaea", "Small Continents", "Perlin", "Fractal", "Continent and Islands", "Archipelago", "Two Continents", "Three Continents",
            "Inner Sea", "Lakes", "Four Corners", "Spiral", "Boreal")
        assertEquals(unciv.toSet(), MapType.uncivValues.toSet())
        for (type in unciv) assertFalse(type, type in MapType.allValues)
        RulesetCache.loadRulesets(noMods = true)
        val generator = MapGenerator(RulesetCache.getVanillaRuleset())
        for (type in unciv.take(3)) {
            val map = generator.generateMap(MapParameters().apply { this.type = type; mapSize = UncivMapSizes.Tiny; seed = 3 })
            assertTrue(type, map.values.any { it.isLand })
        }
    }

    // endregion
}
