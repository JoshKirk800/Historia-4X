package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.IHasUniques
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
 *  Content flip F1 of the trade plan (`phaseE/vp-trade-plan.md` T7, `phaseE/rulegen.md` "Content flip F1"): the Brave New World ruleset plays Vox Populi's resources with their
 *  monopolies, route model, route yields of buildings, improvements and traits, and the rules of shortages and deals. The expected values are typed from the Vox Populi 5.4.8
 *  database (`ref/db/vp.sqlite`: Resources.IsMonopoly and the Monopoly* columns, Resource_YieldChangeFromMonopoly, Resource_CityYieldModFromMonopoly,
 *  Resource_MonopolyCombatModifiers, Eras.TradeRouteFoodBonusTimes100, GameSpeeds.TradeRouteSpeedMod, Building_YieldFromInternalTR, Improvement_RouteYieldChanges, the trait tables)
 *  and the options of `(2) Vox Populi/Database Changes/CustomModOptionChanges.sql`; `python -m rulegen check bnw all` explains every difference of the files from the Brave New
 *  World database.
 *
 *  Not here (declared gaps): the golden age points of Gold, Ivory, Lapis Lazuli and Jade (no stat for them: those four keep the golden age length), Glass, Brazilwood and Paper
 *  (no source on the map, a Brazil-only resource, and a resource that Vox Populi obsoletes with the starting technology), the buildings and traits that wait for another package.
 */
@RunWith(GdxTestRunner::class)
class TradeContentTests {
    private val ruleset = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset   // TestGame loads the rulesets when the class is run alone

    @Test
    fun `the ruleset plays VP's route model, shortage rules, deal rules and monopoly thresholds`() {
        val constants = ruleset.modOptions.constants
        assertTrue(constants.tradeRouteModelLikeVp)
        assertTrue(constants.resourceShortageLikeVp)
        assertTrue(constants.dealsBlockTemporaryForPermanent)
        assertTrue(constants.dealHumanPermanentForAiTemporary)
        assertTrue(constants.dealNoPeacetimeSellingFoundedCities)
        assertTrue(constants.dealLuxuryValueLikeVp)
        assertTrue(constants.worldTradeRouteDistance.isEmpty())   // D22 O8: 100 percent on every map
        assertEquals(50, constants.monopoly.globalThreshold)
        assertEquals(25, constants.monopoly.strategicThreshold)
        val vp = constants.tradeRouteVp
        assertEquals(listOf(80, 80, 25, 0, 5, 125, 125, 600, 600), listOf(vp.internationalTradeBase, vp.cityGoldDivisor, vp.seaModifier, vp.landModifier, vp.differentResourceValue,
            vp.scienceDivisorTimes100, vp.cultureDivisorTimes100, vp.baseFoodValue, vp.baseProductionValue))
        assertEquals(listOf(2, 1, 1, 1, 20, 25, 50, 30), listOf(vp.cityStateAllyGold, vp.cityStateFriendGold, vp.allyScienceDelta, vp.allyCultureDelta, vp.openBordersModifier,
            vp.riverModifier, vp.proximityMax, vp.targetTurns))
    }

    @Test
    fun `the eras add VP's food and production to a route`() {
        val bonus = listOf("Ancient", "Classical", "Medieval", "Renaissance", "Industrial", "Modern", "Atomic", "Information").map { ruleset.eras["$it era"]!! }
        assertEquals(listOf(0, 100, 200, 400, 600, 800, 1000, 1200), bonus.map { it.tradeRouteFoodBonus })
        assertEquals(listOf(0, 100, 200, 400, 600, 800, 1000, 1200), bonus.map { it.tradeRouteProductionBonus })
        // Unciv's ninth era has no row in Vox Populi: it takes the last one
        assertEquals(1200, ruleset.eras["Future era"]!!.tradeRouteFoodBonus)
        assertEquals(1200, ruleset.eras["Future era"]!!.tradeRouteProductionBonus)
    }

    @Test
    fun `the speeds scale the length of a route as VP's GameSpeeds do`() {
        assertEquals(listOf(0.67f, 1f, 1.5f, 3f), listOf("Quick", "Standard", "Epic", "Marathon").map { ruleset.speeds[it]!!.tradeRouteSpeedModifier })
    }

    // --- resources

    private fun tile(stat: String, n: Int) = "[+$n $stat] from each tile with this resource"
    private fun city(stat: String, n: Int) = "[+$n]% [$stat] [in all cities]"
    private fun happiness(n: Int) = "[$n] Happiness from a global monopoly"
    private fun goldenAge(n: Int) = "[+$n]% Golden Age length"
    private val attack = "[+10]% Strength <when attacking>"
    private val defense = "[+10]% Strength <when defending>"

    /** The global monopoly of each of the 41 resources of the ruleset with a monopoly (Glass and Brazilwood are not in it) */
    private val global = mapOf(
        "Iron" to tile("Production", 2), "Horses" to tile("Science", 2), "Coal" to tile("Gold", 3), "Oil" to tile("Science", 2), "Aluminum" to tile("Production", 2),
        "Uranium" to city("Science", 10), "Whales" to city("Science", 10), "Pearls" to tile("Faith", 2), "Gold" to goldenAge(25), "Silver" to tile("Culture", 2),
        "Gems" to city("Gold", 10), "Marble" to city("Culture", 10), "Ivory" to goldenAge(25), "Furs" to happiness(6), "Dyes" to tile("Gold", 3),
        "Cinnamon" to tile("Culture", 2), "Silk" to city("Gold", 10), "Sugar" to city("Food", 10), "Cotton" to tile("Gold", 3), "Wine" to tile("Faith", 2),
        "Incense" to tile("Faith", 2), "Nutmeg" to city("Production", 10), "Cloves" to happiness(6), "Pepper" to tile("Food", 3), "Cocoa" to tile("Culture", 2),
        "Jewelry" to happiness(6), "Porcelain" to goldenAge(30), "Copper" to city("Production", 10), "Salt" to city("Food", 10), "Crab" to tile("Food", 3),
        "Truffles" to tile("Food", 3), "Citrus" to tile("Science", 2), "Coffee" to city("Production", 10), "Tea" to city("Culture", 10), "Tobacco" to tile("Faith", 2),
        "Amber" to happiness(6), "Jade" to goldenAge(25), "Olives" to city("Food", 10), "Perfume" to tile("Gold", 3), "Coral" to tile("Science", 2),
        "Lapis Lazuli" to goldenAge(25))

    /** Attack, defense, healing and experience come with either kind of monopoly: the strategic list of the six strategic resources (Coal's are the sea modifiers) */
    private val strategic = mapOf(
        "Iron" to listOf(defense), "Horses" to listOf(attack), "Uranium" to listOf(attack), "Oil" to listOf("New [Military] units start with [2] XP [in all cities]"),
        "Aluminum" to listOf("[5] HP when healing"),
        "Coal" to listOf("[+10]% Strength <when attacking> <for [Water] units>", "[+10]% Strength <when defending> <for [Water] units>"))

    @Test
    fun `41 resources have a monopoly with the bonuses of VP's monopoly tables`() {
        val monopolies = ruleset.tileResources.values.filter { it.isMonopoly }
        assertEquals(41, monopolies.size)
        assertEquals(global.keys, monopolies.map { it.name }.toSet())
        for (resource in monopolies) {
            assertEquals(resource.name, listOf(global[resource.name]), resource.globalMonopolyUniques.toList())
            assertEquals(resource.name, strategic[resource.name] ?: emptyList<String>(), resource.strategicMonopolyUniques.toList())
        }
        assertEquals(6, ruleset.tileResources.values.count { it.strategicMonopolyUniques.isNotEmpty() })
        // the monopoly of the Bonus resources is that of the luxuries: Wheat, Cattle, Sheep and Deer have none
        for (name in listOf("Wheat", "Cattle", "Sheep", "Deer", "Bananas", "Fish", "Stone", "Bison", "Maize", "Rice", "Figs")) assertFalse(name, ruleset.tileResources[name]!!.isMonopoly)
    }

    @Test
    fun `the resources left out of the ruleset are the three of VP that no source can place`() {
        for (name in listOf("Glass", "Brazilwood", "Paper")) assertNull(name, ruleset.tileResources[name])
        // VP's 57 resources are the ruleset's 54, the three above and nothing else: the 12 added by the placement slice and the five renames are in
        assertEquals(54, ruleset.tileResources.size)
        assertNotNull(ruleset.tileResources["Figs"])
        for (old in listOf("Spices", "Gold Ore")) assertNull(old, ruleset.tileResources[old])
        for (name in listOf("Cinnamon", "Gold")) assertNotNull(name, ruleset.tileResources[name])
        for (old in listOf("Trading post", "Customs house", "Terrace farm")) assertNull(old, ruleset.tileImprovements[old])
        for (name in listOf("Village", "Town", "Pata-Pata")) assertNotNull(name, ruleset.tileImprovements[name])
    }

    @Test
    fun `Horses are gone with Rocketry and the other resources never`() {
        assertEquals("Rocketry", ruleset.tileResources["Horses"]!!.obsoleteBy)
        assertEquals(listOf("Horses"), ruleset.tileResources.values.filter { it.obsoleteBy != null }.map { it.name })
    }

    @Test
    fun `Marble, Stone and Ivory change the production of the city that has them`() {
        val marble = ruleset.tileResources["Marble"]!!.uniques
        assertTrue("[+15]% Production of Wonders in cities with this resource <before the [Industrial era]>" in marble)
        assertFalse(marble.any { "Production when constructing [" in it && "era] wonders" in it })   // Brave New World's rule was the same bonus, a second time
        assertTrue("[+10]% Production of Wonders in cities with this resource <before the [Medieval era]>" in ruleset.tileResources["Stone"]!!.uniques)
        assertTrue("[-15]% Production of [Siege] units in cities with this resource <before the [Industrial era]>" in ruleset.tileResources["Ivory"]!!.uniques)
    }

    // --- improvements, buildings, traits

    @Test
    fun `the Village and the Town give gold and production where a route crosses their road or railroad`() {
        val village = ruleset.tileImprovements["Village"]!!.uniques
        assertTrue("[+1 Gold, +1 Production] if a trade route crosses this tile on a [Road]" in village)
        assertTrue("[+2 Gold, +2 Production] if a trade route crosses this tile on a [Railroad]" in village)
        val town = ruleset.tileImprovements["Town"]!!.uniques
        assertTrue("[+2 Gold, +2 Production] if a trade route crosses this tile on a [Road]" in town)
        assertTrue("[+4 Gold, +4 Production] if a trade route crosses this tile on a [Railroad]" in town)
        assertEquals(4, ruleset.tileImprovements.values.sumOf { i -> i.uniques.count { "if a trade route crosses this tile" in it } })
    }

    @Test
    fun `seven buildings give food or production on the domestic routes that start in their city`() {
        val expected = mapOf("Stable" to "[+2 Production]", "Ducal Stable" to "[+2 Production]", "Lighthouse" to "[+4 Food]", "Workshop" to "[+4 Production]",
            "Coaling Station" to "[+6 Food]", "Homestead" to "[+2 Production]", "Runestone" to "[+4 Food]")
        for ((name, stats) in expected)
            assertTrue(name, "$stats on domestic trade routes that start in this city" in ruleset.buildings[name]!!.uniques)
        assertEquals(expected.keys, ruleset.buildings.values.filter { b -> b.uniques.any { "on domestic trade routes that start in this city" in it } }.map { it.name }.toSet())
    }

    @Test
    fun `the Chartered Company and the Great Cothon add a quarter to the gold of resource diversity`() {
        for (name in listOf("Chartered Company", "Great Cothon"))
            assertTrue(name, "[+25]% Gold from resource diversity on trade routes" in ruleset.buildings[name]!!.uniques)
    }

    @Test
    fun `the buildings of the other packages that have route rows stay out`() {
        // Gumey (a count of tiles), Doelen (espionage), Elephant Camp (city-state resources), Arsenale di Venezia (buildings of the ruleset's own list), Slater Mill (its duration is not read)
        for (name in listOf("Gumey", "Doelen", "Elephant Camp", "Arsenale di Venezia", "Slater Mill")) assertNull(name, ruleset.buildings[name])
    }

    @Test
    fun `six traits have their trade rows`() {
        fun uniques(nation: String) = ruleset.nations[nation]!!.uniques
        assertTrue("No proximity penalty on trade routes" in uniques("Morocco"))
        assertTrue("No destination restriction on trade routes" in uniques("Venice"))
        assertTrue("[+2] to the flat bonus of each monopoly" in uniques("Indonesia"))
        assertTrue("[+5]% to the percentage bonus of each monopoly" in uniques("Indonesia"))
        assertTrue("[+3 Gold, +2 Culture] for each luxury resource exported <for every [Era scaler]>" in uniques("The Netherlands"))
        assertTrue("[+3 Gold, +2 Culture] for each luxury resource imported <for every [Era scaler]>" in uniques("The Netherlands"))
        assertTrue("Gain [4] [Gold] <upon a trade route unit moving> <for every [Era scaler]>" in uniques("Portugal"))
        assertTrue("Gain [4] [Science] <upon a trade route unit moving> <for every [Era scaler]>" in uniques("Portugal"))
        val speed = "<(modified by game speed)> <for every [Era scaler]>"
        val ottomans = uniques("The Ottomans")
        assertTrue("Gain [75] [Gold] <upon completing a [domestic] trade route> $speed" in ottomans)
        assertTrue("Gain [75] [Culture] <upon completing a [domestic] trade route> $speed" in ottomans)
        assertTrue("Gain [150] [Food] <upon completing a [international] trade route> $speed" in ottomans)
        assertTrue("Gain [150] [Science] <upon completing a [international] trade route> $speed" in ottomans)
        // the Inca's trait needs the era scaler of the held trait flip: no row of it yet
        assertFalse(uniques("Inca").any { "on tiles of trade routes" in it })
    }

    // --- validation

    @Test
    fun `no unique of the ruleset names a resource, improvement or building that the ruleset does not have`() {
        val bad = ruleset.getErrorList().filter { "which does not fit parameter type" in it.text }
            .filter { error -> listOf("resource", "improvement", "building", "Building", "Resource", "Improvement").any { it in error.text.substringAfter("which does not fit parameter type") } }
        assertTrue(bad.joinToString("\n") { it.text }, bad.isEmpty())
    }

    @Test
    fun `every unique the F1 data writes is known to the game`() {
        val holders: List<IHasUniques> = ruleset.tileResources.values.flatMap { listOf(it) + it.monopolyUniqueHolders } +
            ruleset.tileImprovements.values + ruleset.buildings.values + ruleset.nations.values + ruleset.eras.values
        val unknown = holders.flatMap { holder ->
            holder.uniqueObjects.filter { it.type == null || it.modifiers.any { modifier -> modifier.type == null } }.map { "${holder.name}: ${it.text}" }
        }
        assertTrue(unknown.joinToString("\n"), unknown.isEmpty())
    }
}
