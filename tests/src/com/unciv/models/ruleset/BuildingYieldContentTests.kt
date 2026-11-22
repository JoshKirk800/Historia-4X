package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Content flip A of the yield sources plan (`phaseE/vp-yields-plan.md` Y4, `phaseE/rulegen.md` "Content flip A"): the Brave New World ruleset has Vox Populi's buildings,
 *  tech costs, specialists, era discount and purchase constants. The expected numbers are typed from the Vox Populi 5.4.8 database (`ref/db/vp.sqlite`: Buildings.Cost,
 *  GoldMaintenance, HurryCostModifier, Technologies.Cost and the defines of `(2) Vox Populi/Database Changes/DefineChanges.sql`); `python -m rulegen check bnw all`
 *  (run from the workspace) explains every difference of the files from the Brave New World database.
 *
 *  Not in this class (declared gaps, `rulegen.md`): the faith cost of buildings (Unciv buys with faith through beliefs) and the Zoo of the plan (not complete: its
 *  `Building_FeatureYieldChanges` is P5), see `the buildings that wait keep their Brave New World form`.
 */
@RunWith(GdxTestRunner::class)
class BuildingYieldContentTests {
    private val ruleset = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset   // TestGame loads the rulesets when the class is run alone

    @Test
    fun `the Brave New World ruleset plays the VP purchase and cost constants`() {
        val constants = ruleset.modOptions.constants
        assertEquals(4.0, constants.purchase.goldPerProduction, 0.0)
        assertEquals(0.88, constants.purchase.exponent, 0.0)
        assertEquals(10, constants.purchase.visibleDivisor)
        assertTrue(constants.buildingInvestments)
        assertEquals(-50, constants.buildingInvestmentBaseline)
        assertEquals(2, constants.buildingInvestmentWonderDivisor)
        assertEquals(50, constants.goldBuildingCostPercent)
        assertEquals(listOf(25, 15, 10, 5), constants.worldWonderCostIncrease)
        assertTrue(constants.purchaseCooldownEnabled)
        assertTrue(constants.instantYieldsUseInstantYieldPercent)
        assertEquals(listOf(0.75f, 1f, 1.5f, 2f), listOf("Quick", "Standard", "Epic", "Marathon").map { ruleset.speeds[it]!!.instantYieldModifier })
        assertEquals(ruleset.eras.size, ruleset.eras.values.count { it.laterEraBuildingConstructMod == -2 })
    }

    @Test
    fun `a Library gives 2 science, costs 150 and has a scientist slot, a Market gives 3 gold and no modifier`() {
        val library = ruleset.buildings["Library"]!!
        assertEquals(150, library.cost)
        assertEquals(1, library.maintenance)
        assertEquals(2f, library.science, 0f)
        assertEquals(mapOf("Scientist" to 1), library.newSpecialists().toMap())
        assertEquals(0f, library.getStatPercentageBonuses(null).science, 0f)
        val market = ruleset.buildings["Market"]!!
        assertEquals(110, market.cost)
        assertEquals(0, market.maintenance)
        assertEquals(3f, market.gold, 0f)
        assertEquals(0f, market.getStatPercentageBonuses(null).gold, 0f)
        // the other rows of 2.3 of the plan that are complete: Temple 200 / 1 / +3 faith, Granary 65 / 1 / +1 food and 25 food when built, Factory 1250 / 6 / +10 production
        val temple = ruleset.buildings["Temple"]!!
        assertEquals(listOf(200, 1), listOf(temple.cost, temple.maintenance))
        assertEquals(3f, temple.faith, 0f)
        val granary = ruleset.buildings["Granary"]!!
        assertEquals(listOf(65, 1), listOf(granary.cost, granary.maintenance))
        assertEquals(1f, granary.food, 0f)
        assertTrue("Gain [25] [Food] <(modified by game speed)>" in granary.uniques)
        val factory = ruleset.buildings["Factory"]!!
        assertEquals(listOf(1250, 6), listOf(factory.cost, factory.maintenance))
        assertEquals(10f, factory.production, 0f)
    }

    /** name|cost|maintenance|hurry cost modifier of the buildings that are Vox Populi's whole (no declared gap): the Vox Populi database's rows */
    private val vpBuildings = listOf(
        "Courthouse|200|3|0",
        "Bazaar|110|0|0",
        "Tabya|110|1|0",
        "Seaport|1000|5|0",
        "Water Mill|150|1|0",
        "Circus|300|2|0",
        "Forge|110|1|0",
        "Windmill|600|4|0",
        "Customs House|350|0|0",
        "Garden|300|2|0",
        "Harbor|350|2|0",
        "Arena|150|1|0",
        "Monument|65|0|0",
        "Temple|200|1|0",
        "Barracks|110|1|0",
        "Armory|300|2|0",
        "Military Academy|1250|6|0",
        "Arsenal|1800|7|0",
        "Walls|110|1|0",
        "Military Base|2250|8|0",
        "Granary|65|1|0",
        "Medical Lab|2250|8|0",
        "Factory|1250|6|0",
        "Spaceship Factory|2750|10|0",
        "Market|110|0|0",
        "Library|150|1|0",
        "Public School|1000|5|0",
        "Research Lab|2000|7|0",
        "Heroic Epic|60|0|0",
        "School of Philosophy|80|0|0",
        "Chartered Company|140|0|0",
        "Ironworks|140|0|0",
        "The Great Lighthouse|200|0|0",
        "Stonehenge|150|0|0",
        "The Pyramids|150|0|0",
        "Colossus|200|0|0",
        "The Oracle|250|0|0",
        "Hanging Gardens|200|0|0",
        "Angkor Wat|400|0|0",
        "Chichen Itza|800|0|0",
        "Porcelain Tower|900|0|0",
        "Forbidden Palace|500|0|0",
        "Brandenburg Gate|1250|0|0",
        "Eiffel Tower|1250|0|0",
        "Aqueduct|200|1|0",
        "Stone Works|110|1|0",
        "Statue of Zeus|185|0|0",
        "Mausoleum of Halicarnassus|185|0|0",
        "Walls of Babylon|110|1|0",
        "Ceilidh Hall|300|2|0",
        "Ikanda|110|1|0",
        "Candi|300|2|0",
        "Hanse|350|0|0",
        "Writers' Guild|200|1|0",
        "Artists' Guild|350|2|0",
        "Musicians' Guild|600|4|0",
        "Hotel|1000|5|0",
        "Airport|2250|8|0",
        "Red Fort|900|0|0",
        "Prora|1700|0|0",
        "Shrine|65|0|0",
        "Recycling Center|2250|8|0",
        "Strategic Defense System|2250|8|0",
        "Alhambra|400|0|0",
        "Hubble Space Telescope|3000|0|0",
        "Leaning Tower of Pisa|800|0|0",
        "University of Sankore|400|0|0",
        "Neuschwanstein|1000|0|0",
        "Petra|185|0|0",
        "Terracotta Army|200|0|0",
        "Great Firewall|3000|0|0",
        "Stele|65|0|0",
        "Grand Temple|120|0|0",
        "Council|65|0|0",
        "Well|65|1|0",
        "Grocer|500|4|0",
        "Bastion Fort|600|4|0",
        "Agribusiness|1250|0|0",
        "Mine Field|1600|7|0",
        "Refinery|2000|7|0",
        "Lamassu Gate|110|1|0",
        "Schützenstand|1250|6|0",
        "Andelsbevægelse|1250|0|0",
        "Steam Mill|1000|5|0",
        "Harappan Reservoir|200|1|0",
        "Siege Foundry|110|1|0",
        "Barbican|450|2|0",
        "Marae|65|0|0",
        "Pogost|350|0|0",
        "Tophet|65|0|0",
        "Huey Teocalli|120|0|0",
        "Great Cothon|140|0|0",
        "Karlstejn|500|0|0",
        "Soho Foundry|1000|0|0",
        "The Motherland Calls|2150|0|0",
        "CERN|3250|0|0"
    )

    @Test
    fun `every VP building complete after P2 exists with VP's cost, maintenance and hurry modifier`() {
        for (line in vpBuildings) {
            val fields = line.split("|")
            val name = fields[0]
            val building = ruleset.buildings[name]
            assertNotNull(name, building)
            assertEquals("$name cost", fields[1].toInt(), building!!.cost)
            assertEquals("$name maintenance", fields[2].toInt(), building.maintenance)
            assertEquals("$name hurry cost modifier", fields[3].toInt(), building.hurryCostModifier)
        }
    }

    @Test
    fun `the BNW-only buildings are gone`() {
        val removed = listOf("Amphitheater", "Big Ben", "Bomb Shelter", "Burial Tomb", "Coffee House", "Colosseum", "East India Company", "Floating Gardens",
            "Great Mosque of Djenne", "Hermitage", "Hydro Plant", "Krepost", "Mint", "Mud Pyramid Mosque", "Mughal Fort", "National College", "National Epic",
            "National Visitor Center", "Nuclear Plant", "Oxford University", "Paper Maker", "Pyramid", "Solar Plant")
        assertEquals(emptyList<String>(), removed.filter { it in ruleset.buildings })
        // what pointed at them is repaired (BasicTests.baseRulesetHasNoBugs has no reference to a missing object)
        assertFalse(ruleset.buildings.values.any { it.requiredBuilding in removed || it.replaces in removed })
        assertEquals(emptyList<String>(), ruleset.policies.values.filter { p -> p.uniques.any { u -> removed.any { "[$it]" in u } } }.map { it.name })
        assertEquals(emptyList<String>(), ruleset.beliefs.values.filter { b -> b.uniques.any { u -> removed.any { "[$it]" in u } } }.map { it.name })
        assertTrue("Laborer" in ruleset.specialists && "Unemployed Citizen" !in ruleset.specialists)
    }

    @Test
    fun `the buildings that wait keep their Brave New World form, the Zoo of the plan is not complete`() {
        // Vox Populi's Zoo needs Building_FeatureYieldChanges (P5) and the Theater, whose 500 instant science the plan expected, Building_ThemingYieldBonus (P5):
        // the Zoo stays at Brave New World's 200 without the 500 science, the Theater is not in the ruleset, the Opera House, Museum, Broadcast Tower and CN Tower (VP namesakes
        // that wait) stay Brave New World's with their slots, and what has no Brave New World building (Bath, Roman Forum, Great Altar, Holy Council) is absent
        val zoo = ruleset.buildings["Zoo"]!!
        assertEquals(200, zoo.cost)
        assertFalse(zoo.uniques.any { "[500] [Science]" in it })
        for (name in listOf("Theater", "Bath", "Roman Forum", "Great Altar", "Holy Council"))
            assertFalse(name, name in ruleset.buildings)
        for (name in listOf("Opera House", "Museum", "Broadcast Tower", "CN Tower")) assertNotNull(name, ruleset.buildings[name])
        assertEquals(null, ruleset.buildings["Opera House"]!!.requiredBuilding)   // the removed Amphitheater was its requirement
        assertEquals("Opera House", ruleset.buildings["Museum"]!!.requiredBuilding)
        assertEquals(2, ruleset.buildings["Museum"]!!.greatWorkSlotTypes.size)
        // the University is Brave New World's +33% science (Vox Populi's +3 science needs Building_GrowthExtraYield), written out with its cost
        val university = ruleset.buildings["University"]!!
        assertEquals(160, university.cost)
        assertEquals(33f, university.getStatPercentageBonuses(null).science, 0f)
        assertEquals(0f, university.science, 0f)
    }

    @Test
    fun `the corporation headquarters and franchises are not in the ruleset until P4`() {
        // a headquarters has no tech and no cost to pay in the data (Vox Populi founds it with a Great Merchant once Corporations is enabled): built as a wonder it would be
        // buildable from the first turn, which the Wonder AI did
        assertEquals(emptyList<String>(), ruleset.buildings.keys.filter { "Headquarters" in it || it.endsWith("Franchise") })
    }

    @Test
    fun `no Vox Populi building carries a theming bonus or a great work yield of a later package`() {
        // P5 (theming yields, great work yields) and P3 (internal trade route yields) are withheld whole: the Vox Populi buildings that need them are absent
        for (building in ruleset.buildings.values) {
            if (vpBuildings.none { it.startsWith(building.name + "|") }) continue
            assertTrue(building.name, building.themingBonuses.isEmpty() || building.isWonder)
            assertTrue(building.name, building.name == "Hotel" || building.uniques.none { "Tourism from Great Works" in it })
        }
    }

    /** name|cost of Vox Populi's technologies (Technologies.Cost): its tree and prices, with Unciv's spelling of the names that it spells otherwise */
    private val vpTechs = listOf(
        "Agriculture|20",
        "Pottery|60",
        "Trapping|60",
        "The Wheel|60",
        "Animal Husbandry|60",
        "Mining|60",
        "Fishing|100",
        "Trade|100",
        "Calendar|100",
        "Construction|100",
        "Military Strategy|100",
        "Bronze Working|100",
        "Sailing|130",
        "Writing|130",
        "Mathematics|130",
        "Masonry|130",
        "Iron Working|130",
        "Drama and Poetry|275",
        "Philosophy|275",
        "Currency|275",
        "Engineering|275",
        "Metal Casting|275",
        "Education|500",
        "Theology|500",
        "Chivalry|500",
        "Physics|500",
        "Steel|500",
        "Civil Service|700",
        "Guilds|700",
        "Compass|700",
        "Machinery|700",
        "Banking|1750",
        "Printing Press|1750",
        "Astronomy|1750",
        "Gunpowder|1750",
        "Chemistry|1750",
        "Architecture|2400",
        "Acoustics|2400",
        "Economics|2400",
        "Navigation|2400",
        "Metallurgy|2400",
        "Scientific Theory|3600",
        "Railroads|3600",
        "Steam Power|3600",
        "Rifling|3600",
        "Archaeology|5150",
        "Fertilizer|5150",
        "Industrialization|5150",
        "Dynamite|5150",
        "Military Science|5150",
        "Biology|8100",
        "Electricity|8100",
        "Corporations|8100",
        "Replaceable Parts|8100",
        "Combustion|8100",
        "Plastics|11000",
        "Radio|11000",
        "Flight|11000",
        "Ballistics|11000",
        "Pharmaceuticals|13000",
        "Refrigeration|13000",
        "Atomic Theory|13000",
        "Rocketry|13000",
        "Combined Arms|13000",
        "Electronics|16000",
        "Computers|16000",
        "Nuclear Fission|16000",
        "Radar|16000",
        "Alternative Energy|19100",
        "Telecommunications|19100",
        "Satellites|19100",
        "Advanced Ballistics|19100",
        "Mobile Tactics|19100",
        "Robotics|23400",
        "The Internet|23400",
        "Lasers|23400",
        "Stealth|23400",
        "Nanotechnology|24450",
        "Particle Physics|24450",
        "Globalization|24450",
        "Nuclear Fusion|24450",
        "Future Tech|28550"
    )

    @Test
    fun `the tech costs equal VP's`() {
        assertEquals(vpTechs.size, ruleset.technologies.size)
        for (line in vpTechs) {
            val (name, cost) = line.split("|")
            assertEquals(name, cost.toInt(), ruleset.technologies[name]!!.cost)
        }
        assertEquals(8100, ruleset.technologies["Electricity"]!!.cost)
        assertEquals(emptyList<String>(), listOf("Archery", "Ecology", "Horseback Riding", "Optics").filter { it in ruleset.technologies })
    }

    @Test
    fun `the specialists are VP's`() {
        assertEquals(5f, ruleset.specialists["Merchant"]!!.gold, 0f)
        assertEquals(4f, ruleset.specialists["Engineer"]!!.production, 0f)
        assertEquals(5f, ruleset.specialists["Musician"]!!.culture, 0f)
    }
}
