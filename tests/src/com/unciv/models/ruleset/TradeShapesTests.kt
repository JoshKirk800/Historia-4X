package com.unciv.models.ruleset

import com.badlogic.gdx.Gdx
import com.unciv.json.json
import com.unciv.models.ModConstants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tech.Era
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueParameterType
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The shapes of slice T0 of `phaseE/vp-trade-plan.md` (`parity/vp-trade-shapes`): the unique types and the parameter type for the trade routes and monopolies of
 *  Vox Populi, the fields of resources, buildings, eras and speeds, the constants, and the validator rules. Nothing reads them yet (the slices T1-T4 add the rules),
 *  and no ruleset uses them: every default is inert and the old JSON loads as it did.
 */
@RunWith(BaseTestRunner::class)
class TradeShapesTests {

    private class Shape(val owner: UniqueTarget, val text: String)

    private val policy = UniqueTarget.Policy

    /** A text of each new unique type with names that the Brave New World ruleset has, on the kind of object that owns it */
    private val shapes = listOf(
        Shape(policy, "[+1 Gold] for each trade route that starts in this city"),
        Shape(policy, "[+1 Gold, +1 Culture] on [International] trade routes <for every [Era scaler]>"),
        Shape(policy, "[+25]% quantity of [Iron]"),
        Shape(policy, "[+5] to the flat bonus of each monopoly"),
        Shape(policy, "[+5]% to the percentage bonus of each monopoly"),
        Shape(policy, "City-State resources count towards monopolies"),
        Shape(policy, "[+1 Gold] [in all cities] for every global monopoly"),
        Shape(policy, "Gain [10] [Gold] <upon completing a [international] trade route>"),
        Shape(policy, "Gain [10] [Gold] <upon completing a [domestic] trade route>"),
        Shape(policy, "Gain [10] [Gold] <upon completing a [any] trade route>"),
        Shape(policy, "Gain [5] [Production] <upon a trade route unit moving>"),
        Shape(policy, "Gain [5] [Gold] <upon plundering a trade route>"),
        Shape(policy, "[+1 Gold] <with a global monopoly of [Iron]>"),
        Shape(policy, "[+1 Gold] <with a strategic monopoly of [Iron]>"),
        Shape(policy, "[+1 Gold] <on tiles of trade routes and their cities>"),
        Shape(UniqueTarget.Building, "[+1 Production] on domestic trade routes that start in this city"),
        Shape(UniqueTarget.Building, "[+2 Science] while any trade route passes through this city"),
        Shape(UniqueTarget.Improvement, "[+1 Gold] if a trade route crosses this tile on a [Road]"),
        Shape(UniqueTarget.Resource, "[-15]% Production of [Siege] units in cities with this resource <before the [Industrial era]>"),
        Shape(UniqueTarget.Resource, "[+15]% Production of Wonders in cities with this resource <before the [Industrial era]>"),
    )

    /** The texts that only the monopoly lists of a resource carry */
    private val monopolyListShapes = listOf(
        "[+2 Gold] from each tile with this resource", "[2] Happiness from a global monopoly", "[+10]% [Production] [in all cities]", "[+25]% Golden Age length",
    )

    /** The unique types this slice adds (the plan's table 4.3 less the corporations, and less the monopoly percentages and golden age length, which are the existing types) */
    private val newTypes = listOf(
        UniqueType.StatsPerTradeRouteOfCity, UniqueType.StatsOnTradeRouteDomain, UniqueType.ResourceQuantityPercent, UniqueType.MonopolyFlatBonus,
        UniqueType.MonopolyPercentBonus, UniqueType.CityStateResourcesCountForMonopolies, UniqueType.StatsPerGlobalMonopoly, UniqueType.StatsOnDomesticRoutesFromCity,
        UniqueType.StatsWhenTradeRoutePasses, UniqueType.MonopolyTileYield, UniqueType.MonopolyHappiness, UniqueType.ResourceProductionCost,
        UniqueType.ResourceWonderProduction, UniqueType.ImprovementStatsOnRoute, UniqueType.ConditionalGlobalMonopoly, UniqueType.ConditionalStrategicMonopoly,
        UniqueType.ConditionalOnTradeRoute, UniqueType.TriggerUponCompletingTradeRoute, UniqueType.TriggerUponTradeUnitMoving, UniqueType.TriggerUponPlunderingTradeRoute,
    )

    private fun newBnwGame() = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun errorsOf(game: TestGame, name: String) = game.ruleset.getErrorList()
        .filter { it.text.contains(name) && it.errorSeverityToReport >= RulesetErrorSeverity.Warning }
        .map { it.text }

    @Test
    fun `every shape of the trade plan is a unique type with the target of its owner`() {
        for (shape in shapes) {
            val unique = Unique(shape.text)
            assertNotNull("${shape.text} is no unique type", unique.type)
            assertTrue("${shape.text} is not allowed on ${shape.owner}", unique.type!!.canAcceptUniqueTarget(shape.owner))
        }
        for (modifier in shapes.flatMap { Unique(it.text).modifiers })
            assertNotNull("<${modifier.text}> is no modifier", modifier.type)
        val used = (shapes.map { Unique(it.text) } + monopolyListShapes.map { Unique(it) }).flatMap { listOfNotNull(it.type) + it.modifiers.mapNotNull { m -> m.type } }.toSet()
        val unused = newTypes.filter { it !in used }
        assertTrue("new types with no shape in this test: $unused", unused.isEmpty())
    }

    @Test
    fun `every shape of the trade plan validates on the object that owns it`() {
        val game = newBnwGame()
        val failures = HashMap<String, List<String>>()
        for (shape in shapes) {
            val name = when (shape.owner) {
                UniqueTarget.Policy -> game.createPolicy(shape.text).name
                UniqueTarget.Building -> game.createBuilding(shape.text).apply { cost = 100 }.name
                UniqueTarget.Improvement -> game.createTileImprovement(shape.text).apply { terrainsCanBeBuiltOn = listOf("Plains") }.name
                else -> game.createResource(shape.text).name
            }
            failures[shape.text] = errorsOf(game, name)
        }
        val resource = game.createResource().apply {
            isMonopoly = true
            globalMonopolyUniques = ArrayList(monopolyListShapes)
        }
        failures["the monopoly lists"] = errorsOf(game, resource.name)
        val failed = failures.filterValues { it.isNotEmpty() }
        assertTrue("shapes with errors:\n" + failed.entries.joinToString("\n") { "${it.key}: ${it.value}" }, failed.isEmpty())
    }

    @Test
    fun `the corporation shapes of the plan are not types, the owner has no corporations for now`() {
        for (text in listOf("Founds corporation [Firaxite Materials]", "[+1 Gold] [in this city] for every franchise of your corporation", "Offices count as franchises"))
            assertNull("$text is a unique type", Unique(text).type)
    }

    @Test
    fun `the trade route kind parameter takes international, domestic and any only`() {
        val type = UniqueParameterType.safeValueOf("tradeRouteKind")
        assertNotNull(type)
        val ruleset = newBnwGame().ruleset
        for (kind in listOf("international", "domestic", "any")) assertTrue(kind, type!!.isKnownValue(kind, ruleset))
        for (kind in listOf("foreign", "International", "Food")) assertFalse(kind, type!!.isKnownValue(kind, ruleset))
        assertTrue(UniqueType.TriggerUponCompletingTradeRoute.text.contains("[tradeRouteKind]"))
    }

    @Test
    fun `a trade route kind that is none of the three is a validator error`() {
        val game = newBnwGame()
        val name = game.createPolicy("Gain [10] [Gold] <upon completing a [foreign] trade route>").name
        assertTrue(errorsOf(game, name).any { it.contains("foreign") })
    }

    @Test
    fun `every new unique type has an entry in the documentation of the uniques`() {
        val documented = Gdx.files.local("../../docs/Modders/uniques.md").readString()
        for (type in newTypes) {
            val isModifier = type.targetTypes.any { it.modifierType != UniqueTarget.ModifierType.None }
            val heading = if (isModifier) "&lt;${type.text}&gt;" else type.text
            assertTrue("${type.name} (${type.text}) has no entry in docs/Modders/uniques.md: regenerate it with UniqueDocsWriter", "\"$heading\"" in documented)
            assertNotNull("${type.name} has no docDescription", type.docDescription)
        }
        assertTrue("the parameter tradeRouteKind is not documented", "*[tradeRouteKind]:" in documented)
    }

    @Test
    fun `a resource that is a monopoly and has no monopoly uniques is a validator warning`() {
        val game = newBnwGame()
        val bare = game.createResource().apply { isMonopoly = true }
        val withUniques = game.createResource().apply { isMonopoly = true; globalMonopolyUniques = arrayListOf("[+2 Gold] from each tile with this resource") }
        val plain = game.createResource()
        val bareErrors = game.ruleset.getErrorList().filter { it.text.contains(bare.name) && it.text.contains("monopoly") }
        assertEquals(1, bareErrors.size)
        assertEquals(RulesetErrorSeverity.Warning, bareErrors.first().errorSeverityToReport)
        assertTrue(errorsOf(game, withUniques.name).isEmpty())
        assertTrue(errorsOf(game, plain.name).isEmpty())
    }

    @Test
    fun `monopoly uniques on a resource that is no monopoly are a warning and names in them must resolve`() {
        val game = newBnwGame()
        val orphan = game.createResource().apply { strategicMonopolyUniques = arrayListOf("[+2 Gold] from each tile with this resource") }
        assertTrue(errorsOf(game, orphan.name).any { it.contains("not a monopoly resource") })

        val unknown = game.createResource().apply {
            isMonopoly = true
            globalMonopolyUniques = arrayListOf("[+1 Gold] <with a global monopoly of [Unobtainium]>")
        }
        assertTrue(errorsOf(game, unknown.name).any { it.contains("Unobtainium") })
    }

    @Test
    fun `an unknown technology or monopoly resource named by an object is a validator error`() {
        val game = newBnwGame()
        val resource = game.createResource().apply { obsoleteBy = "No such technology" }
        assertTrue(errorsOf(game, resource.name).any { it.contains("No such technology") })
        val known = game.createResource().apply { obsoleteBy = "Rocketry" }
        assertTrue(errorsOf(game, known.name).isEmpty())

        val unknownMonopoly = game.createBuilding().apply { cost = 100; requiredMonopolyAnyOf = listOf("Unobtainium") }
        assertTrue(errorsOf(game, unknownMonopoly.name).any { it.contains("Unobtainium") })
        val notMonopoly = game.createBuilding().apply { cost = 100; requiredMonopolies = listOf("Stone") }   // Iron is a monopoly resource since content flip F1; Stone is not
        assertTrue(errorsOf(game, notMonopoly.name).any { it.contains("no monopoly resource") })
        val monopoly = game.createResource().apply { isMonopoly = true; globalMonopolyUniques = arrayListOf("[2] Happiness from a global monopoly") }
        val fine = game.createBuilding().apply { cost = 100; requiredMonopolyAnyOf = listOf(monopoly.name) }
        assertTrue(errorsOf(game, fine.name).isEmpty())
    }

    @Test
    fun `old ruleset JSON without the new fields loads with the inert defaults`() {
        val resource = json().fromJson(TileResource::class.java, """{"name": "Old", "resourceType": "Luxury", "gold": 2}""")
        assertFalse(resource.isMonopoly)
        assertNull(resource.obsoleteBy)
        assertTrue(resource.globalMonopolyUniques.isEmpty() && resource.strategicMonopolyUniques.isEmpty())
        val era = json().fromJson(Era::class.java, """{"name": "Old era", "eraNumber": 0}""")
        assertEquals(0, era.tradeRouteFoodBonus)
        assertEquals(0, era.tradeRouteProductionBonus)
        val building = json().fromJson(Building::class.java, """{"name": "Old building", "cost": 10}""")
        assertTrue(building.requiredMonopolyAnyOf.isEmpty() && building.requiredMonopolies.isEmpty())

        // a ruleset without Vox Populi's data: the Brave New World one has it since content flip F1 (TradeContentTests), Gods & Kings does not
        val bnw = TestGame().ruleset
        assertTrue(bnw.tileResources.values.none { it.isMonopoly || it.obsoleteBy != null || it.globalMonopolyUniques.isNotEmpty() || it.strategicMonopolyUniques.isNotEmpty() })
        assertTrue(bnw.buildings.values.none { it.requiredMonopolyAnyOf.isNotEmpty() || it.requiredMonopolies.isNotEmpty() })
        assertTrue(bnw.eras.values.all { it.tradeRouteFoodBonus == 0 && it.tradeRouteProductionBonus == 0 })
        assertTrue(bnw.speeds.values.all { it.tradeRouteSpeedModifier == 1f })
    }

    @Test
    fun `the new fields are read from JSON`() {
        val resource = json().fromJson(TileResource::class.java, """{"name": "New", "resourceType": "Luxury", "isMonopoly": true, "obsoleteBy": "Rocketry",
            "globalMonopolyUniques": ["[2] Happiness from a global monopoly"], "strategicMonopolyUniques": ["[+2 Gold] from each tile with this resource"]}""")
        assertTrue(resource.isMonopoly)
        assertEquals("Rocketry", resource.obsoleteBy)
        assertEquals(listOf("[2] Happiness from a global monopoly"), resource.globalMonopolyUniques)
        assertEquals(listOf("[+2 Gold] from each tile with this resource"), resource.strategicMonopolyUniques)
        assertEquals(2, resource.monopolyUniqueHolders.size)
        assertEquals(UniqueType.MonopolyHappiness, resource.monopolyUniqueHolders[0].uniqueObjects.single().type)
        val era = json().fromJson(Era::class.java, """{"name": "New era", "eraNumber": 1, "tradeRouteFoodBonus": 100, "tradeRouteProductionBonus": 50}""")
        assertEquals(100, era.tradeRouteFoodBonus)
        assertEquals(50, era.tradeRouteProductionBonus)
        val building = json().fromJson(Building::class.java, """{"name": "New building", "requiredMonopolyAnyOf": ["Amber", "Jade"], "requiredMonopolies": ["Coal"]}""")
        assertEquals(listOf("Amber", "Jade"), building.requiredMonopolyAnyOf)
        assertEquals(listOf("Coal"), building.requiredMonopolies)
        val speed = json().fromJson(Speed::class.java, """{"name": "New speed", "tradeRouteSpeedModifier": 1.5}""")
        assertEquals(1.5f, speed.tradeRouteSpeedModifier, 0f)
    }

    @Test
    fun `the new constants are inert by default and read from JSON, whole blocks only`() {
        val defaults = ModConstants()
        assertFalse(defaults.tradeRouteModelLikeVp)
        assertFalse(defaults.dealsBlockTemporaryForPermanent)
        assertFalse(defaults.dealNoPeacetimeSellingFoundedCities)
        assertTrue(defaults.worldTradeRouteDistance.isEmpty())
        assertEquals(ModConstants.TradeRouteVpConstants(internationalTradeBase = 80, cityGoldDivisor = 80), ModConstants.TradeRouteVpConstants())
        assertEquals(50, defaults.monopoly.globalThreshold)
        assertEquals(25, defaults.monopoly.strategicThreshold)
        // Brave New World's own route constants are untouched
        assertEquals(1f, defaults.tradeRouteBaseGold, 0f)
        assertEquals(20, defaults.tradeRouteCityGoldDivisor)

        val read = json().fromJson(ModConstants::class.java, """{"tradeRouteModelLikeVp": true, "tradeRouteVp": {"internationalTradeBase": 90},
            "worldTradeRouteDistance": [{"tiles": 1000, "percent": 80}, {"tiles": 4000, "percent": 100}], "monopoly": {"globalThreshold": 60}}""")
        assertTrue(read.tradeRouteModelLikeVp)
        assertEquals(90, read.tradeRouteVp.internationalTradeBase)
        assertEquals(80, read.tradeRouteVp.cityGoldDivisor)
        assertEquals(listOf(ModConstants.WorldTradeRouteDistance(1000, 80), ModConstants.WorldTradeRouteDistance(4000, 100)), read.worldTradeRouteDistance)
        assertEquals(60, read.monopoly.globalThreshold)
        assertEquals(25, read.monopoly.strategicThreshold)

        // the constants of a ruleset without Vox Populi's (Gods & Kings; the Brave New World ruleset turns them on since content flip F1, TradeContentTests) stay what they were
        val constants = TestGame().ruleset.modOptions.constants
        assertFalse(constants.tradeRouteModelLikeVp)
        assertTrue(constants.worldTradeRouteDistance.isEmpty())
        assertEquals(ModConstants.TradeRouteVpConstants(), constants.tradeRouteVp)
        assertEquals(ModConstants.MonopolyConstants(), constants.monopoly)
    }
}
