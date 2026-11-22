package com.unciv.logic.automation.city

import com.unciv.logic.automation.strategy.BuildingProductionAi
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.ProductionSanityAi
import com.unciv.logic.automation.strategy.SpaceshipProductionAi
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvCityStrategyAI::ChooseProduction / Cv*ProductionAI / CvCityAI::AI_chooseProduction port (BNW XML values) */
@RunWith(GdxTestRunner::class)
class ProductionChoiceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        city = game.addCity(civ, game.tileMap[0, 0])
    }

    @Test
    fun `building weight is the XML flavors times the city flavors, negative city flavors as 0`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.ai.setPersonalityFlavor(Flavor.GreatPeople, 3)
        // Building_Flavors: Writers' Guild CULTURE 90; the Library SCIENCE 40, GREAT_PEOPLE 2 (here: Arena CULTURE 20, PRODUCTION 10 of Vox Populi's)
        assertEquals(90 * 7, ProductionAi.buildingWeight(city, game.ruleset.buildings["Writers' Guild"]!!))
        assertEquals(20 * 7, ProductionAi.buildingWeight(city, game.ruleset.buildings["Arena"]!!))
        // The settler pump has CULTURE -4: city culture 3 - 4 = -1 counts 0, not -20
        civ.ai.setPersonalityFlavor(Flavor.Culture, 3)
        city.ai.changeSpecialization("CITYSPECIALIZATION_SETTLER_PUMP")
        assertEquals(0, ProductionAi.buildingWeight(city, game.ruleset.buildings["Arena"]!!))
    }

    @Test
    fun `units, projects and processes use their own flavor tables`() {
        civ.ai.setPersonalityFlavor(Flavor.TileImprovement, 4)
        civ.ai.setPersonalityFlavor(Flavor.Spaceship, 2)
        civ.ai.setPersonalityFlavor(Flavor.Science, 3)
        civ.ai.setPersonalityFlavor(Flavor.Gold, 6)
        // Unit_Flavors: Worker TILE_IMPROVEMENT 30
        assertEquals(30 * 4, ProductionAi.unitWeight(city, game.ruleset.units["Worker"]!!))
        // Project_Flavors: Apollo Program SPACESHIP 250, SCIENCE 25, WONDER 10
        val apollo = game.ruleset.buildings["Apollo Program"]!!
        assertTrue(ProductionAi.isProject(game.ruleset.ai, apollo))
        assertEquals(250 * 2 + 25 * 3, ProductionAi.projectWeight(city, apollo))
        // Process_Flavors: Wealth GOLD 5, DIPLOMACY 5; Research SCIENCE 4, SPACESHIP 5, NUKE 1
        assertEquals(5 * 6, ProductionAi.processWeight(city, PerpetualConstruction.Gold.name))
        assertEquals(4 * 3 + 5 * 2, ProductionAi.processWeight(city, PerpetualConstruction.Science.name))
    }

    @Test
    fun `time reweighting divides by turns to the power 0_15 plus 0_015 per turn`() {
        // AI_PRODUCTION_WEIGHT_BASE_MOD 0.15, AI_PRODUCTION_WEIGHT_MOD_PER_TURN_LEFT 0.015 (XML; the DLL comment's 0.004 is stale)
        assertEquals(1000, WonderProductionAi.reweightByTurnsLeft(civ, 1000, 1))
        assertEquals(501, WonderProductionAi.reweightByTurnsLeft(civ, 1000, 10)) // 1000 / 10^0.30
        assertEquals(62, WonderProductionAi.reweightByTurnsLeft(civ, 1000, 40)) // 1000 / 40^0.75
    }

    @Test
    fun `turns left are counted in hundredths of the city's production, not its rounded value`() {
        val building = game.createBuilding().apply { cost = 40 }
        city.cityConstructions.setCurrentConstruction(building.name)
        val work = city.cityConstructions.getRemainingWork(building.name)
        // CvCity::getProductionTurnsLeft with 4.6 production per turn: (work x 100 - 460) / 460 rounded up, plus the first turn
        city.cityStats.currentCityStats.production = 4.6f
        val left = work * 100 - 460
        assertEquals((left + 459) / 460 + 1, ProductionAi.turnsLeft(city, building))
        // the queue's own estimate counts whole points, as the city really adds them
        assertEquals((work + 4) / 5, city.cityConstructions.turnsToConstruction(building.name))
        city.cityStats.currentCityStats.production = 0f
        assertEquals(work * 100 + 1, ProductionAi.turnsLeft(city, building))
    }

    /** A building no other one needs and that no strategy touches: the sanity is the normalised weight plus 200 for the era */
    private fun plainBuilding() = game.createBuilding().apply { cost = 30 }

    @Test
    fun `the pick is a weighted roll among the choices within 90 percent of the best`() {
        val automation = ConstructionAutomation(city.cityConstructions)
        val monument = plainBuilding()
        val library = plainBuilding()
        val granary = plainBuilding()
        val counts = HashMap<String, Int>()
        for (turn in 1..300) {
            game.gameInfo.turns = turn
            val candidates = listOf(
                ConstructionAutomation.Buildable(monument, 20000, 1),
                ConstructionAutomation.Buildable(library, 30000, 1),
                ConstructionAutomation.Buildable(granary, 28000, 1),
            )
            val chosen = automation.choose(candidates)!!.construction.name
            counts[chosen] = (counts[chosen] ?: 0) + 1
        }
        // normalised to sqrt(10 x weight) + 200: 647, 747, 729; 647 is below 90% of 747, 747 : 729 both happen
        assertEquals(null, counts[monument.name])
        val libraryCount = counts[library.name] ?: 0
        val granaryCount = counts[granary.name] ?: 0
        assertTrue("library $libraryCount granary $granaryCount", libraryCount > 60 && granaryCount > 60)
    }

    @Test
    fun `the current build goes on while it weighs at least half the best`() {
        val automation = ConstructionAutomation(city.cityConstructions)
        val monument = plainBuilding()
        val library = plainBuilding()
        city.cityConstructions.setCurrentConstruction(monument.name)
        fun choice(monumentWeight: Int) = automation.choose(listOf(
            ConstructionAutomation.Buildable(library, 30000, 1),
            ConstructionAutomation.Buildable(monument, monumentWeight, 1),
        ))!!.construction
        // the library is worth 547 + 200 = 747, half of it 373: sqrt(29930) + 200 is 373, sqrt(29920) + 200 372
        assertEquals(monument, choice(2993))
        assertEquals(library, choice(2992))
    }

    @Test
    fun `the time penalty can reorder the choices`() {
        val automation = ConstructionAutomation(city.cityConstructions)
        // 516 each for the monument and library, 300 for the granary; the monument over 40 turns falls to 32: only the library is within 90% of the best
        val monument = plainBuilding()
        val library = plainBuilding()
        val granary = plainBuilding()
        val candidates = listOf(
            ConstructionAutomation.Buildable(monument, 10000, 40),
            ConstructionAutomation.Buildable(library, 10000, 1),
            ConstructionAutomation.Buildable(granary, 1000, 1),
        )
        for (turn in 1..50) {
            game.gameInfo.turns = turn
            val chosen = automation.choose(candidates)!!
            assertEquals(library, chosen.construction)
        }
    }

    @Test
    fun `a process is worth next to nothing while the army is tiny`() {
        // no military unit: at most 2 per city, -100%; the weight stays at least 1
        assertEquals(1, ProductionSanityAi.processSanity(city, PerpetualConstruction.Gold, 1000))
        game.addUnit("Warrior", civ, game.tileMap[1, 0])
        game.addUnit("Warrior", civ, game.tileMap[0, 1])
        game.addUnit("Warrior", civ, game.tileMap[1, 1])
        assertEquals(ProductionSanityAi.normalise(1000), ProductionSanityAi.processSanity(city, PerpetualConstruction.Gold, 1000))
    }

    @Test
    fun `a process making the city's most deficient yield gets 150 percent more`() {
        for (tile in listOf(game.tileMap[1, 0], game.tileMap[0, 1], game.tileMap[1, 1])) game.addUnit("Warrior", civ, tile)
        val normal = ProductionSanityAi.normalise(1000)
        city.ai.mostDeficientYield = Stat.Gold
        assertEquals(normal * 250 / 100, ProductionSanityAi.processSanity(city, PerpetualConstruction.Gold, 1000))
        assertEquals(normal, ProductionSanityAi.processSanity(city, PerpetualConstruction.Science, 1000))
        city.ai.mostDeficientYield = null
        assertEquals(normal, ProductionSanityAi.processSanity(city, PerpetualConstruction.Gold, 1000))
    }

    @Test
    fun `with the Apollo Program the parts still needed that a known technology allows go to the planner's best cities`() {
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        civ.tech.addTechnology("Advanced Ballistics")
        assertEquals("no Apollo Program yet", 0, SpaceshipProductionAi.numPartsBuildableNow(civ))
        city.cityConstructions.addBuilding("Apollo Program")
        // the three SS Boosters of the six parts are what Advanced Ballistics allows
        assertEquals(3, SpaceshipProductionAi.numPartsBuildableNow(civ))
        assertEquals(listOf(city), SpaceshipProductionAi.bestCitiesForParts(civ))
        civ.victoryManager.currentsSpaceshipParts.add("SS Booster", 3)
        assertEquals("the boosters are all built", 0, SpaceshipProductionAi.numPartsBuildableNow(civ))
    }

    private fun makeWonderCity() = game.createWonder().apply { cost = 100 }.also { wonder ->
        civ.ai.citySpecialization.nextWonderDesired = wonder.name
    }

    @Test
    fun `a city told to interrupt wonders starts the next wonder when it is the best city for it and there is an Engineer`() {
        val wonder = makeWonderCity()
        val automation = ConstructionAutomation(city.cityConstructions)
        // VP asks for an Engineer (UNITAI_ENGINEER) and for the interruption: neither alone starts the wonder
        automation.chooseNextConstruction(interruptWonders = true)
        assertTrue(city.cityConstructions.currentConstructionName() != wonder.name)
        game.addUnit("Great Engineer", civ, game.tileMap[1, 0])
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        automation.chooseNextConstruction()
        assertTrue(city.cityConstructions.currentConstructionName() != wonder.name)
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        automation.chooseNextConstruction(interruptWonders = true)
        assertEquals(wonder.name, city.cityConstructions.currentConstructionName())
    }

    @Test
    fun `a city that is not the best for the next wonder does not start it`() {
        val wonder = makeWonderCity()
        game.addUnit("Great Engineer", civ, game.tileMap[1, 0])
        val second = game.addCity(civ, game.tileMap[3, 0], initialPopulation = 8)
        // production already put into the wonder makes that city the best for it (x10)
        second.cityConstructions.inProgressConstructions[wonder.name] = 40
        assertTrue(BuildingProductionAi.isBestForWonder(second, wonder))
        assertTrue(!BuildingProductionAi.isBestForWonder(city, wonder))
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        ConstructionAutomation(city.cityConstructions).chooseNextConstruction(interruptWonders = true)
        assertTrue(city.cityConstructions.currentConstructionName() != wonder.name)
    }

    @Test
    fun `a world wonder some civilization has built makes the specializations dirty`() {
        val wonder = makeWonderCity()
        val other = game.addCiv()
        game.addCity(other, game.tileMap[3, 0]).cityConstructions.addBuilding(wonder.name)
        civ.ai.citySpecialization.specializationsDirty = false
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        ConstructionAutomation(city.cityConstructions).chooseNextConstruction()
        assertTrue(civ.ai.citySpecialization.specializationsDirty)
    }

    @Test
    fun `turns left are those of the production the city makes building that very item, in hundredths`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        // CvCity::getProductionTurnsLeft: the base production with the modifiers of that item - here +20% for everything and
        // +100% more for wonders - and the production left over that, not the city's current production for every item
        val workshop = game.createBuilding("[+20]% [Production]", "[+100]% Production when constructing [All] wonders [in this city]")
            .apply { production = 4f }
        city.cityConstructions.addBuilding(workshop.name)
        city.cityStats.update()
        val building = game.createBuilding().apply { cost = 60; culture = 1f }
        val wonder = game.createWonder().apply { cost = 60; culture = 1f }

        val base = city.cityStats.baseStatTree.totalStats.production
        assertTrue("base production $base", base >= 4f && base * 1.2f % 1f > 0.5f) // a whole-point rounding would give another turn count
        fun turns(cost: Int, modifier: Float): Int {
            val production100 = Math.round(base * modifier * 100f)
            val left = cost * 100 - production100
            return (left + production100 - 1) / production100 + 1
        }

        val candidates = ConstructionAutomation(city.cityConstructions).getBuildables().associateBy { it.construction.name }
        assertEquals(turns(60, 1.2f), candidates[building.name]!!.turns)
        assertEquals(turns(60, 2.2f), candidates[wonder.name]!!.turns)
    }

    @Test
    fun `puppets we may annex build no building of a later era than ours minus one`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        val other = game.addCiv()
        val puppet = game.addCity(other, game.tileMap[2, 0])
        puppet.puppetCity(civ)
        val ancient = game.createBuilding().apply { cost = 30; culture = 1f }
        val pottery = game.createBuilding().apply { cost = 30; culture = 1f; requiredTech = "Pottery" }
        val classical = game.createBuilding().apply { cost = 30; culture = 1f; requiredTech = "Mathematics" }
        fun weights() = ConstructionAutomation(puppet.cityConstructions).getBuildables().associate { it.construction.name to it.weight }
        civ.tech.addTechnology("Pottery")
        // era 0 (the ancient era): only what needs no technology (VP: GetEra -1) is not later than era - 1
        assertEquals(10 * 5, weights()[ancient.name])
        assertEquals(null, weights()[pottery.name])
        civ.tech.addTechnology("Mathematics")
        // era 1: the buildings of the ancient era pass, those of the classical era do not
        assertEquals(10 * 5, weights()[pottery.name])
        assertEquals(null, weights()[classical.name])
    }

    @Test
    fun `puppets we may annex build no building with maintenance and no defense while losing money`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        val other = game.addCiv()
        val puppet = game.addCity(other, game.tileMap[2, 0])
        puppet.puppetCity(civ)
        val cheap = game.createBuilding().apply { cost = 30; culture = 1f; maintenance = 0 }
        val upkeep = game.createBuilding().apply { cost = 30; culture = 1f; maintenance = 1 }
        val walls = game.createBuilding().apply { cost = 30; culture = 1f; maintenance = 1; cityStrength = 5.0 }
        fun weights() = ConstructionAutomation(puppet.cityConstructions).getBuildables().associate { it.construction.name to it.weight }
        // the weights are the flavor weights now (no doubling of the cheap ones): derived flavors CULTURE 10 per culture point
        assertEquals(10 * 5, weights()[upkeep.name])
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_LOSING_MONEY"] = 0
        val losing = weights()
        assertEquals(10 * 5, losing[cheap.name])
        assertEquals(null, losing[upkeep.name])
        assertEquals(10 * 5, losing[walls.name])
    }

    @Test
    fun `puppets get no wonder because they are never the best city for one`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        val other = game.addCiv()
        val puppet = game.addCity(other, game.tileMap[2, 0])
        puppet.puppetCity(civ)
        val wonder = game.createWonder().apply { cost = 30; culture = 1f }
        assertEquals(ProductionSanityAi.SR_STRATEGY, ProductionSanityAi.buildingSanity(puppet, wonder, 1000))
    }
}
