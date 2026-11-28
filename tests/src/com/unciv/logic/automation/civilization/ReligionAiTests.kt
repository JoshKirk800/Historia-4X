package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.city.HurryChoice
import com.unciv.logic.automation.civilization.ReligionAutomation.FaithPurchase
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.TheftAi
import com.unciv.logic.automation.diplomacy.TheftType
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.ReligiousUnitAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.civilization.transients.CapitalConnectionsFinder.CapitalConnectionMedium
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.removeConditionals
import com.unciv.testing.GdxTestRunner
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.EnumSet
import kotlin.random.Random

/** Decision-level checks of the CvReligionAI port (CvReligionClasses.cpp) against the DLL formulas, with the BNW beliefs */
@RunWith(GdxTestRunner::class)
class ReligionAiTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
    }

    /** A religion with a Founder belief, so that it counts as founded (a major religion) */
    private fun foundReligion(civ: Civilization): Religion {
        val religion = game.addReligion(civ)
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        setState(civ, ReligionState.Religion)
        return religion
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(x, y)

    /** The religion state has a private setter; the decisions under test only read it */
    private fun setState(civ: Civilization, state: ReligionState) {
        ReligionManager::class.java.getDeclaredField("religionState").apply { isAccessible = true }.set(civ.religionManager, state)
    }

    private fun neutralCiv(vararg uniques: String): Civilization {
        val civ = game.addCiv(*uniques)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        return civ
    }

    private fun playerScore(civ: Civilization, belief: String): Int =
        BeliefScoring.scoreBeliefForPlayer(BeliefAiProfile.of(game.ruleset.beliefs[belief]!!, civ), BeliefScoringContext(civ))

    //region ScoreBeliefForPlayer, one flavor at a time

    @Test
    fun `offense scores faith from kills, doubled for world conquest`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        // God of War: 50 faith from kills × 4 distance × offense / 100
        assertEquals(50 * 4 * 5 / 100, playerScore(civ, "God of War"))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertEquals(50 * 4 * 5 / 100 * 2, playerScore(civ, "God of War"))
    }

    @Test
    fun `defense scores the friendly-territory combat bonus`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Defense, 4)
        assertEquals(4 * 20 / 4, playerScore(civ, "Defender of the Faith"))
    }

    @Test
    fun `happiness scores happiness per following city with offense minus defense`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 6)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 2)
        // Ceremonial Burial: 1 happiness per 2 following cities × (happiness + 2 × offense − defense)
        assertEquals((0.5f * (6 + 4)).toInt(), playerScore(civ, "Ceremonial Burial"))
    }

    @Test
    fun `culture scores culture per foreign followers`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        // World Church: 1 culture per 5 foreign followers: 10 × culture / 5
        assertEquals(10 * 5 / 5, playerScore(civ, "World Church"))
    }

    @Test
    fun `gold scores gold per following city and per followers`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Gold, 7)
        assertEquals(2 * 7, playerScore(civ, "Church Property"))
        // Tithe: 1 gold per 4 followers: gold × 4 / 4
        assertEquals(7 * 4 / 4, playerScore(civ, "Tithe"))
    }

    @Test
    fun `great people scores faith from expended great people`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.GreatPeople, 3)
        assertEquals(3 * 50 / 10, playerScore(civ, "Reliquary"))
    }

    @Test
    fun `science scores science from spreading plus the early spread-strength term`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Science, 5)
        // Interfaith Dialogue: science × 10 / 5, plus 2 × science while fewer than a third of the religions are enhanced
        assertEquals(5 * 10 / 5 + 2 * 5, playerScore(civ, "Interfaith Dialogue"))
    }

    @Test
    fun `diplomacy scores the city-state resting influence`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 7)
        assertEquals(7 * 15 / 7, playerScore(civ, "Papal Primacy"))
    }

    @Test
    fun `expansion scores spread distance by the share of enhanced religions`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 4)
        val distance = 3 * 100 / game.ruleset.ai.define(AiDefine.RELIGION_ADJACENT_CITY_DISTANCE)
        assertEquals(2 * 4 + distance, playerScore(civ, "Itinerant Preachers"))
        // every religion enhanced: a quarter
        val founder = game.addCiv()
        foundReligion(founder)
        setState(founder, ReligionState.EnhancedReligion)
        while (BeliefScoringContext(civ).religionsEnhancedPercent < 66) {
            val other = game.addCiv()
            foundReligion(other)
            setState(other, ReligionState.EnhancedReligion)
        }
        assertEquals((2 * 4 + distance) / 4, playerScore(civ, "Itinerant Preachers"))
    }

    @Test
    fun `reformation beliefs score their effects and the barbarian strategy`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 10)
        assertEquals(15, playerScore(civ, "Heathen Conversion"))
        civ.ai.militaryStrategies["MILITARYAISTRATEGY_ERADICATE_BARBARIANS"] = 0
        assertEquals(40, playerScore(civ, "Heathen Conversion"))
        assertEquals(2 * 20, playerScore(civ, "Sacred Sites"))
        assertEquals(5 * 5, playerScore(civ, "Underground Sect"))
        assertEquals(30 * 10 / 5, playerScore(civ, "Charitable Missions"))
        assertEquals(50 / 2, playerScore(civ, "Unity of the Prophets"))
    }

    @Test
    fun `pantheon beliefs score half`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 10)
        val belief = game.ruleset.beliefs["God of War"]!!
        // 5 base + 50 × 4 × 10 / 100, halved
        assertEquals((5 + 20) / 2, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)))
    }

    private fun plotWeights(civ: Civilization, considerExpansion: Boolean): Map<Tile, Int> {
        val context = BeliefScoringContext(civ, considerExpansion)
        return context.plots.indices.associate { context.plots[it] to context.plotMultipliers[it] }
    }

    private fun mountain(tile: Tile) {
        tile.baseTerrain = Constants.mountain
        tile.setTransients()
    }

    @Test
    fun `with Vox Populi belief plots weigh our cities and worked plots 10, our others in range 8, mountains 10 per city in range`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv()
        val capital = game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(5, 0))
        val other = game.addCiv()
        val foreign = game.addCity(other, tile(-5, 0))
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        capital.workedTiles.clear()
        capital.workedTiles.add(tile(1, 0).position)
        mountain(tile(3, 0))
        mountain(tile(-3, 0))
        capital.expansion.takeOwnership(tile(0, 4))
        assertEquals(4, tile(0, 4).aerialDistanceTo(capital.getCenterTile()))
        val weights = plotWeights(civ, considerExpansion = false)
        assertEquals("ours, out of reach", null, weights[tile(0, 4)])
        assertEquals(10, weights[capital.getCenterTile()])
        assertEquals(null, weights[foreign.getCenterTile()])
        assertEquals("worked", 10, weights[tile(1, 0)])
        assertEquals("ours, unworked", 8, weights[tile(0, 1)])
        assertEquals("in reach of both cities", 20, weights[tile(3, 0)])
        assertEquals("in reach of the capital only, unowned", 10, weights[tile(-3, 0)])
        assertEquals("unowned plots only for a pantheon", null, weights[tile(2, 0)])
        assertEquals(6, plotWeights(civ, considerExpansion = true)[tile(2, 0)])
    }

    @Test
    fun `with Vox Populi a pantheon weighs unowned plots by our reach, the exploration range and nearer known cities`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv()
        val capital = game.addCity(civ, tile(0, 0))
        val other = game.addCiv()
        game.addCity(other, tile(-7, 0))
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val center = capital.getCenterTile()
        val probes = listOf(tile(2, 0), tile(3, 0), tile(4, 0), tile(-5, 0), tile(6, 0), tile(7, 0))
        assertEquals(listOf(2, 3, 4, 5, 6, 7), probes.map { it.aerialDistanceTo(center) })
        var weights = plotWeights(civ, considerExpansion = true)
        assertEquals("in reach, next to ours", 6, weights[tile(2, 0)])
        assertEquals("in reach", 3, weights[tile(3, 0)])
        // exploration range 9, work range 3: (4 - 3) <= (9 - 3) / 2
        assertEquals(2, weights[tile(4, 0)])
        assertEquals(2, weights[tile(6, 0)])
        assertEquals(1, weights[tile(7, 0)])
        assertEquals("not yet met", 2, weights[tile(-5, 0)])
        mountain(tile(7, 0))
        other.diplomacyFunctions.makeCivilizationsMeet(civ)
        val theirLand = other.cities.flatMap { it.getTiles().toList() }
        for (tile in theirLand) tile.setExplored(civ, false)
        tile(4, 0).setExplored(civ, false)
        weights = plotWeights(civ, considerExpansion = true)
        assertEquals("met, their land unseen", 2, weights[tile(-5, 0)])
        assertEquals("unseen", null, weights[tile(4, 0)])
        for (tile in theirLand) tile.setExplored(civ, true)
        weights = plotWeights(civ, considerExpansion = true)
        assertEquals("a mountain counts 4 times", 4, weights[tile(7, 0)])
        assertEquals("their city is nearer", null, weights[tile(-5, 0)])
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policyBranches["Tradition"]!!)
        weights = plotWeights(civ, considerExpansion = true)
        assertEquals("range 6", null, weights[tile(7, 0)])
        assertEquals("(6 - 3) > (6 - 3) / 2", 1, weights[tile(6, 0)])
    }

    @Test
    fun `with Vox Populi the exploration range counts from the capital, the work range from any of our cities`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv()
        val capital = game.addCity(civ, tile(-4, 0))
        game.addCity(civ, tile(0, 0))
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policyBranches["Tradition"]!!)
        assertEquals(listOf(8, 4), listOf(capital.getCenterTile(), tile(0, 0)).map { it.aerialDistanceTo(tile(4, 0)) })
        assertEquals(listOf(7, 3), listOf(capital.getCenterTile(), tile(0, 0)).map { it.aerialDistanceTo(tile(3, 0)) })
        val weights = plotWeights(civ, considerExpansion = true)
        assertEquals("beyond range 6 of the capital, 4 from a city", null, weights[tile(4, 0)])
        assertEquals("beyond range 6 of the capital, in reach of a city", 3, weights[tile(3, 0)])
    }

    private fun vpPlotScore(civ: Civilization, belief: String, tile: Tile, futureTech: Boolean = true): Int {
        val profile = BeliefAiProfile.of(game.ruleset.beliefs[belief]!!, civ)
        return BeliefScoring.scoreBeliefAtPlotLikeVp(profile, tile, BeliefScoringContext(civ, considerFutureTech = futureTech))
    }

    private fun terrain(x: Int, y: Int, base: String, vararg features: String, resource: String? = null): Tile {
        val tile = tile(x, y)
        tile.baseTerrain = base
        tile.setTerrainFeatures(features.toList())
        if (resource != null) tile.setTileResource(resource)
        tile.setTransients()
        return tile
    }

    private fun vpPlotCiv(): Civilization {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = neutralCiv()
        game.addCity(civ, tile(0, 0))
        return civ
    }

    @Test
    fun `with Vox Populi a plot scores each yield by its personality flavor, each truncated`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 3)
        val wine = terrain(2, 0, Constants.plains, resource = "Wine")
        // culture 100 × 7 × 100 / 10000, faith 100 × 3 × 110 / 10000 (3.3)
        assertEquals(7 + 3, vpPlotScore(civ, "Goddess of Festivals", wine))
        assertEquals(3, vpPlotScore(civ, "Desert Folklore", terrain(3, 0, Constants.desert)))
        assertEquals(0, vpPlotScore(civ, "Desert Folklore", wine))
        val fuji = tile(-2, 0)
        fuji.naturalWonder = "Mount Fuji"
        fuji.setTransients()
        assertEquals(400 * 330 / 10000, vpPlotScore(civ, "One with Nature", fuji))
    }

    @Test
    fun `with Vox Populi a feature yield counts as far as the feature is likely to stay`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 8)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 10)
        val jungle = terrain(2, 0, Constants.plains, Constants.jungle)
        // 25% likely to be cleared: 100 × 75 / 100 × 8 × 100 / 10000
        assertEquals(6, vpPlotScore(civ, "Sacred Path", jungle))
        assertEquals("not looking ahead", 8, vpPlotScore(civ, "Sacred Path", jungle, futureTech = false))
        assertEquals(11, vpPlotScore(civ, "Dance of the Aurora", terrain(3, 0, Constants.tundra)))
        // only as likely as the forest is to go: 100 × 25 / 100 × 10 × 110 / 10000
        val forest = terrain(4, 0, Constants.tundra, Constants.forest)
        assertEquals(2, vpPlotScore(civ, "Dance of the Aurora", forest))
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policyBranches["Tradition"]!!)
        // 40%: 60 × 800 / 10000
        assertEquals(4, vpPlotScore(civ, "Sacred Path", jungle))
        assertEquals(40 * 1100 / 10000, vpPlotScore(civ, "Dance of the Aurora", forest))
    }

    @Test
    fun `with Vox Populi a feature likely stays where the plot's last improvement keeps it or for a civilization that moves through it`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 8)
        // a Fort, the last improvement this plot can take, keeps the jungle: 100 × 8 × 100 / 10000
        assertEquals(8, vpPlotScore(civ, "Sacred Path", terrain(2, 0, Constants.plains, Constants.jungle, resource = "Bananas")))
        civ.nation.forestsAndJunglesAreRoads = true
        // 25 - 20 = 5% likely to be cleared: 95 × 800 / 10000
        assertEquals(7, vpPlotScore(civ, "Sacred Path", terrain(3, 0, Constants.plains, Constants.jungle)))
    }

    @Test
    fun `with Vox Populi a belief scores its plots by their weights and our flavors`() {
        val civ = vpPlotCiv()
        val belief = game.ruleset.beliefs["Desert Folklore"]!!
        val context = BeliefScoringContext(civ)
        for (plot in context.plots) terrain(plot.position.x, plot.position.y, Constants.desert)
        val weight = context.plotMultipliers.sum()
        assertTrue(weight > 0)
        // with no religion flavor a desert's faith is worth nothing
        val none = BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ))
        civ.ai.setPersonalityFlavor(Flavor.Religion, 10)
        val gain = BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)) - none
        // 11 a plot × its weight
        assertEquals(11 * weight, gain)
    }

    @Test
    fun `with Vox Populi an improvement yield counts by how soon the plot can have it`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 10)
        val cattle = terrain(2, 0, Constants.grassland, resource = "Cattle")
        val wooded = terrain(3, 0, Constants.grassland, Constants.forest, resource = "Cattle")
        val bare = terrain(4, 0, Constants.grassland)
        // a pasture some day: 1 × 50 × 10 × 100 / 10000
        assertEquals(5, vpPlotScore(civ, "God of the Open Sky", cattle))
        assertEquals("no pasture without a resource", 0, vpPlotScore(civ, "God of the Open Sky", bare))
        civ.tech.techsToResearch.add("Animal Husbandry")
        assertEquals("researching", 8, vpPlotScore(civ, "God of the Open Sky", cattle))
        civ.tech.addTechnology("Animal Husbandry")
        assertEquals(9, vpPlotScore(civ, "God of the Open Sky", cattle))
        assertEquals("the forest must go first: 90 × 80 / 100", 7, vpPlotScore(civ, "God of the Open Sky", wooded))
        cattle.setImprovement("Pasture")
        assertEquals(10, vpPlotScore(civ, "God of the Open Sky", cattle))
        assertEquals("not looking ahead, only what is built", 10, vpPlotScore(civ, "God of the Open Sky", cattle, futureTech = false))
        assertEquals(0, vpPlotScore(civ, "God of the Open Sky", wooded, futureTech = false))
    }

    private fun pantheonCityScore(civ: Civilization, belief: Belief, city: City?) =
        BeliefScoring.scorePantheonBeliefAtCity(BeliefAiProfile.of(belief, civ), city, BeliefScoringContext(civ))

    private fun pantheonCityScore(civ: Civilization, belief: String, city: City?) =
        pantheonCityScore(civ, game.ruleset.beliefs[belief]!!, city)

    @Test
    fun `with Vox Populi a pantheon's happiness counts in a city by whether the city has it and its population`() {
        val civ = vpPlotCiv()
        val capital = civ.getCapital()!!
        // 3 a point of happiness, × 10 where it is sure, 3 for a city we may found
        assertEquals(0, pantheonCityScore(civ, "Sacred Waters", capital))
        assertEquals(9, pantheonCityScore(civ, "Sacred Waters", null))
        capital.getCenterTile().hasBottomRiver = true
        capital.getCenterTile().forgetAdjacentToRiver()
        assertEquals(30, pantheonCityScore(civ, "Sacred Waters", capital))
        // Goddess of Love wants 6 population: 10% off for each one missing
        assertEquals(15, pantheonCityScore(civ, "Goddess of Love", capital))
        assertEquals(15, pantheonCityScore(civ, "Goddess of Love", null))
        assertEquals(21, pantheonCityScore(civ, "Goddess of Love", game.addCity(civ, tile(4, 0), initialPopulation = 3)))
        assertEquals(30, pantheonCityScore(civ, "Goddess of Love", game.addCity(civ, tile(-4, 0), initialPopulation = 6)))
        assertEquals("nothing without a capital", 0, pantheonCityScore(neutralCiv(), "Goddess of Love", null))
    }

    @Test
    fun `with Vox Populi a pantheon's city yields count by the yield's flavor and the city's connection`() {
        val civ = vpPlotCiv()
        val capital = civ.getCapital()!!
        civ.ai.setPersonalityFlavor(Flavor.Production, 8)
        civ.ai.setPersonalityFlavor(Flavor.Science, 5)
        // 1 production × 10 × 8 × 50 / 100, 20% off for the 2 population short of 3
        assertEquals(32, pantheonCityScore(civ, "God of Craftsmen", capital))
        assertEquals(40, pantheonCityScore(civ, "God of Craftsmen", game.addCity(civ, tile(4, 0), initialPopulation = 3)))
        // 2 science × 7 until connected to the capital, then × 10; × 5 × 80 / 100
        val town = game.addCity(civ, tile(-4, 0))
        assertEquals(56, pantheonCityScore(civ, "Messenger of the Gods", town))
        assertEquals(56, pantheonCityScore(civ, "Messenger of the Gods", null))
        civ.cache.citiesConnectedToCapitalToMediums = mapOf(town to EnumSet.of(CapitalConnectionMedium.Road))
        assertEquals(80, pantheonCityScore(civ, "Messenger of the Gods", town))
        // a city yield for 2 followers: half the population follows before we have a religion, none in a city to found
        val followers = game.createBelief(BeliefType.Pantheon,
            "[+1 Science] [in all cities] <in cities with at least [2] [Followers of this Religion]>")
        assertEquals(40 * 80 / 100, pantheonCityScore(civ, followers, null))
        assertEquals(40 * 90 / 100, pantheonCityScore(civ, followers, game.addCity(civ, tile(0, 4), initialPopulation = 3)))
        assertEquals(40, pantheonCityScore(civ, followers, game.addCity(civ, tile(0, -4), initialPopulation = 4)))
    }

    @Test
    fun `with Vox Populi a pantheon's building yields count by how soon the city can have the building`() {
        val civ = vpPlotCiv()
        val capital = civ.getCapital()!!
        civ.ai.setPersonalityFlavor(Flavor.Culture, 10)
        val town = game.addCity(civ, tile(4, 0))
        // 1 culture × availability × 10 × 100 / 100: a Granary needs Pottery, of our era: 3, 2 for a city we may found
        val granary = game.createBelief(BeliefType.Pantheon, "[+1 Culture] from every [Granary]")
        assertEquals(30, pantheonCityScore(civ, granary, capital))
        assertEquals(20, pantheonCityScore(civ, granary, null))
        civ.tech.addTechnology("Pottery")
        assertEquals("buildable now", 80, pantheonCityScore(civ, granary, capital))
        assertEquals("its tech known", 60, pantheonCityScore(civ, granary, null))
        capital.cityConstructions.addBuilding("Granary")
        assertEquals(100, pantheonCityScore(civ, granary, capital))
        // a University's Education is two eras on: 3 - 2
        val university = game.createBelief(BeliefType.Pantheon, "[+1 Culture] from every [University]")
        assertEquals(10, pantheonCityScore(civ, university, capital))
        assertEquals(0, pantheonCityScore(civ, university, null))
        // Industrialization is four eras on: nothing, never less
        assertEquals(0, pantheonCityScore(civ, game.createBelief(BeliefType.Pantheon, "[+1 Culture] from every [Factory]"), capital))
        assertEquals("Stone Works needs stone nearby", 10,
            pantheonCityScore(civ, game.createBelief(BeliefType.Pantheon, "[+1 Culture] from every [Stone Works]"), capital))
        assertEquals("a loss from a building counts nothing", 0,
            pantheonCityScore(civ, game.createBelief(BeliefType.Pantheon, "[-1 Culture] from every [Granary]"), capital))
        // God-King's Palace yields only in the capital
        assertEquals(100, pantheonCityScore(civ, "God-King", capital))
        assertEquals(0, pantheonCityScore(civ, "God-King", town))
        assertEquals(0, pantheonCityScore(civ, "God-King", null))
    }

    @Test
    fun `with Vox Populi a pantheon counts our own unique building sooner`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 10)
        val shrine = game.createBuilding()
        shrine.replaces = "Shrine"
        shrine.requiredTech = "Pottery"
        civ.cache.uniqueBuildings.add(shrine)
        // our Shrine needs Pottery: 3 + 3, then buildable 8 + 3, at most 10
        assertEquals(60, pantheonCityScore(civ, "Ancestor Worship", civ.getCapital()))
        civ.tech.addTechnology("Pottery")
        assertEquals(100, pantheonCityScore(civ, "Ancestor Worship", civ.getCapital()))
    }

    @Test
    fun `with Vox Populi a pantheon belief scores each city by its pantheon city score`() {
        val civ = vpPlotCiv()
        val belief = game.ruleset.beliefs["Sacred Waters"]!!
        val dry = BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ))
        civ.getCapital()!!.getCenterTile().hasBottomRiver = true
        civ.getCapital()!!.getCenterTile().forgetAdjacentToRiver()
        // the capital on a river: 30
        assertEquals(dry + 30, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)))
    }

    private fun pantheonPlayerScore(civ: Civilization, belief: Belief) =
        BeliefScoring.scorePantheonBeliefForPlayer(BeliefAiProfile.of(belief, civ), BeliefScoringContext(civ))

    private fun pantheonPlayerScore(civ: Civilization, belief: String) = pantheonPlayerScore(civ, game.ruleset.beliefs[belief]!!)

    private fun adoptBranch(civ: Civilization, branch: String) {
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policyBranches[branch]!!)
    }

    private val neighborSpots = ArrayDeque(listOf(6 to 0, -6 to 0, 0 to 6, 0 to -6, 6 to 6, -6 to -6))

    /** A living major civilization that counts us as [proximity] (VP reads the neighbor's proximity to us) */
    private fun neighbor(civ: Civilization, nation: String? = null, proximity: Proximity = Proximity.Close): Civilization {
        val other = if (nation == null) game.addCiv() else game.addCiv(game.ruleset.nations[nation]!!)
        val (x, y) = neighborSpots.removeFirst()
        game.addCity(other, tile(x, y))
        other.proximity[civ.civID] = proximity
        return other
    }

    @Test
    fun `with Vox Populi a warmonger with close neighbors values faith from kills`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 6)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 10)
        adoptBranch(civ, "Honor")
        assertEquals("no neighbor to fight", 0, pantheonPlayerScore(civ, "God of War"))
        neighbor(civ, proximity = Proximity.Far)
        assertEquals(0, pantheonPlayerScore(civ, "God of War"))
        game.addCiv().proximity[civ.civID] = Proximity.Close
        assertEquals("a defeated civilization is no neighbor", 0, pantheonPlayerScore(civ, "God of War"))
        neighbor(civ)
        // 6 offense × 50% × 10 × 110 × 1 neighbor / 1000, halved: only near our cities
        assertEquals(6 * 50 * 1100 / 1000 / 2, pantheonPlayerScore(civ, "God of War"))
        repeat(3) { neighbor(civ) }
        assertEquals("at most 3 neighbors count", 6 * 50 * 1100 * 3 / 1000 / 2, pantheonPlayerScore(civ, "God of War"))
    }

    @Test
    fun `with Vox Populi a peaceful civilization next to a warmonger values healing and city strikes`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.CityDefense, 4)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 3)
        neighbor(civ)
        assertEquals("the neighbor is no warmonger", 0, pantheonPlayerScore(civ, "Faith Healers"))
        neighbor(civ, "England")
        // (4 + 3) × 30 × 1 warmonger neighbor
        assertEquals(210, pantheonPlayerScore(civ, "Faith Healers"))
        assertEquals(210, pantheonPlayerScore(civ, "Goddess of Protection"))
        adoptBranch(civ, "Tradition")
        assertEquals("tall, twice", 420, pantheonPlayerScore(civ, "Goddess of Protection"))
    }

    @Test
    fun `with Vox Populi a warmonger values no defense and a defender no kills`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 6)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 3)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 10)
        neighbor(civ, "England")
        assertEquals(0, pantheonPlayerScore(civ, "God of War"))
        adoptBranch(civ, "Honor")
        assertEquals(0, pantheonPlayerScore(civ, "Faith Healers"))
    }

    @Test
    fun `with Vox Populi wonder production counts by the wonder flavor, our trait and the era it ends`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Wonder, 5)
        // 5 × 15%, × the era after the Classical era (2) / 5
        assertEquals(5 * 15 * 2 / 5, pantheonPlayerScore(civ, "Monument to the Gods"))
        val egypt = neutralCiv("[+20]% Production when constructing [All] wonders [in all cities]")
        game.addCity(egypt, tile(5, 0))
        egypt.ai.setPersonalityFlavor(Flavor.Wonder, 5)
        // × (100 + 2 × 20) / 100
        assertEquals(5 * 15 * 140 / 100 * 2 / 5, pantheonPlayerScore(egypt, "Monument to the Gods"))
        val goldenAgeBuilder = neutralCiv("[+20]% Production when constructing [All] wonders [in all cities] <during a Golden Age>")
        goldenAgeBuilder.ai.setPersonalityFlavor(Flavor.Wonder, 5)
        // the golden-age % counts once: × (100 + 20) / 100
        assertEquals(5 * 15 * 120 / 100 * 2 / 5, pantheonPlayerScore(goldenAgeBuilder, "Monument to the Gods"))
        val medieval = game.ruleset.technologies.values.first { it.era() == "Medieval era" }.name
        civ.tech.addTechnology(medieval)
        egypt.tech.addTechnology(medieval)
        assertEquals("the game is past the Classical era", 0, pantheonPlayerScore(civ, "Monument to the Gods"))
    }

    @Test
    fun `with Vox Populi world wonder yields count by the wonder flavor and the wonders we have`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Wonder, 4)
        civ.ai.setPersonalityFlavor(Flavor.Culture, 10)
        val belief = game.createBelief(BeliefType.Pantheon, "[+1 Culture] from every [Wonder]")
        // 4 × 1, × 10 × 100 / 100
        assertEquals(40, pantheonPlayerScore(civ, belief))
        civ.getCapital()!!.cityConstructions.addBuilding("Stonehenge")
        assertEquals("+ 10 a wonder", 140, pantheonPlayerScore(civ, belief))
    }

    @Test
    fun `with Vox Populi growth and cheaper borders count by size and flavors`() {
        val civ = vpPlotCiv()
        assertEquals(2 * 10, pantheonPlayerScore(civ, "Fertility Rites"))
        // 5 × max(15%, (defense - offense) × 7)
        assertEquals(5 * 15, pantheonPlayerScore(civ, "Religious Settlements"))
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 1)
        assertEquals(5 * 28, pantheonPlayerScore(civ, "Religious Settlements"))
        adoptBranch(civ, "Tradition")
        assertEquals("tall, twice", 4 * 10, pantheonPlayerScore(civ, "Fertility Rites"))
    }

    @Test
    fun `with Vox Populi without Tradition or Honor the leader's traits make us tall or a warmonger, unless Liberty`() {
        val england = game.addCiv(game.ruleset.nations["England"]!!)
        game.addCity(england, tile(0, 0))
        england.ai.setPersonalityFlavor(Flavor.Offense, 6)
        england.ai.setPersonalityFlavor(Flavor.Religion, 10)
        neighbor(england)
        assertEquals("a warmonger by trait", 6 * 50 * 1100 / 1000 / 2, pantheonPlayerScore(england, "God of War"))
        adoptBranch(england, "Liberty")
        assertEquals(0, pantheonPlayerScore(england, "God of War"))
        val venice = game.addCiv(game.ruleset.nations["Venice"]!!)
        game.addCity(venice, tile(-3, 3))
        assertEquals("tall by its smaller trait", 4 * 10, pantheonPlayerScore(venice, "Fertility Rites"))
    }

    @Test
    fun `with Vox Populi a belief that needs peace costs fighters and defenders`() {
        val civ = vpPlotCiv()
        val belief = game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities] <when not at war>")
        adoptBranch(civ, "Honor")
        assertEquals("growth only, no one to fight", 2 * 10, pantheonPlayerScore(civ, belief))
        neighbor(civ)
        assertEquals(2 * 10 - 1000, pantheonPlayerScore(civ, belief))
        val defender = neutralCiv()
        game.addCity(defender, tile(-3, 3))
        neighbor(defender, "England")
        assertEquals("200 for each warmonger neighbor", 2 * 10 - 200, pantheonPlayerScore(defender, belief))
    }

    @Test
    fun `with Vox Populi a pantheon belief adds its pantheon player score`() {
        val civ = vpPlotCiv()
        val belief = game.ruleset.beliefs["Fertility Rites"]!!
        val wide = BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ))
        adoptBranch(civ, "Tradition")
        // 20 more when tall
        assertEquals(wide + 20, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)))
    }

    @Test
    fun `with Vox Populi a belief scores no base and a pantheon is not halved`() {
        val civ = vpPlotCiv()
        // growth counts only for the player: 2 × 10
        val belief = game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities]")
        assertEquals(20, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)))
    }

    @Test
    fun `with Vox Populi plots that lose yields count nothing and the total is at least 0`() {
        val civ = vpPlotCiv()
        civ.ai.setPersonalityFlavor(Flavor.Growth, 10)
        val belief = game.createBelief(BeliefType.Pantheon, "[-1 Food] from every [Grassland]", "[+10]% growth [in all cities]")
        assertEquals(20, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)))
        adoptBranch(civ, "Honor")
        neighbor(civ)
        val peace = game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities] <when not at war>")
        assertEquals("20 - 1000", 0, BeliefScoring.scoreBelief(peace, BeliefScoringContext(civ)))
    }

    @Test
    fun `with Vox Populi any belief counts the cities we may still found, by the land not ours`() {
        val civ = vpPlotCiv()
        val pantheon = game.ruleset.beliefs["Sacred Waters"]!!
        val nearby = BeliefScoringContext(civ, considerExpansion = true)
        assertTrue(nearby.plots.count { it.getOwner() != civ && it.isLand } < 40)
        // with little land around still at least 3 cities: 9 × (3 - 1) / 2
        assertEquals(9, BeliefScoring.scoreBelief(pantheon, nearby) - BeliefScoring.scoreBelief(pantheon, BeliefScoringContext(civ)))
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val expanding = BeliefScoringContext(civ, considerExpansion = true)
        val landNotOurs = expanding.plots.count { it.getOwner() != civ && it.isLand }
        assertTrue("$landNotOurs plots", landNotOurs >= 100)
        // a city to found counts a river 3 (of 10): 3 × 1 × 3 = 9, × (6 - 1 city) / 2
        val potential = 3 * 1 * 3 * (6 - 1) / 2
        assertEquals(potential, BeliefScoring.scoreBelief(pantheon, expanding) - BeliefScoring.scoreBelief(pantheon, BeliefScoringContext(civ)))
        val follower = game.createBelief(BeliefType.Follower, *pantheon.uniques.toTypedArray())
        assertEquals("not only pantheons", potential,
            BeliefScoring.scoreBelief(follower, expanding) - BeliefScoring.scoreBelief(follower, BeliefScoringContext(civ)))
    }

    @Test
    fun `with Vox Populi more cities than VP wants cost each city to found`() {
        val civ = vpPlotCiv()
        for ((x, y) in listOf(4 to 0, -4 to 0, 0 to 4, 0 to -4, 4 to 4, -4 to -4)) game.addCity(civ, tile(x, y))
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val river = civ.cities.last().getCenterTile()
        river.hasBottomRiver = true
        river.forgetAdjacentToRiver()
        val belief = game.ruleset.beliefs["Sacred Waters"]!!
        // the river city 30, and (6 - 7 cities) × 9 / 2
        assertEquals(30 + (6 - 7) * 9 / 2, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ, considerExpansion = true)))
    }

    @Test
    fun `with Vox Populi a belief other than a pantheon counts its city score 6 times`() {
        val civ = vpPlotCiv()
        val belief = game.createBelief(BeliefType.Follower, "[+1 Happiness] from every [Shrine]")
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 10)
        game.addCity(civ, tile(4, 0))
        val context = BeliefScoringContext(civ)
        val profile = BeliefAiProfile.of(belief, civ)
        val atCities = civ.cities.sumOf { BeliefScoring.scoreBeliefAtCityLikeVp(profile, it, context) }
        // the Shrine's happiness × 3 (Happiness flavor 10) in each city
        assertEquals(6, atCities)
        // no plot yields, no land to expand to: the cities × 6 and the player's score
        assertEquals(6 * atCities + BeliefScoring.scoreBeliefForPlayer(profile, context), BeliefScoring.scoreBelief(belief, context))
    }

    private fun cityScore(civ: Civilization, city: City, vararg uniques: String): Int {
        val profile = BeliefAiProfile.of(game.createBelief(BeliefType.Follower, *uniques), civ)
        return BeliefScoring.scoreBeliefAtCityLikeVp(profile, city, BeliefScoringContext(civ))
    }

    @Test
    fun `with Vox Populi a city scores happiness by the offense the personality needs`() {
        val civ = vpPlotCiv()
        val city = civ.cities.first()
        city.population.setPopulation(3)
        assertEquals("× 6 below a need of 6", 6, cityScore(civ, city, "[+1 Happiness] [in all cities]"))
        assertEquals("× 3 at the population", 18,
            cityScore(civ, city, "[+1 Happiness] [in all cities] <in cities with at least [3] [Population]>"))
        civ.ai.setPersonalityFlavor(Flavor.Offense, 8)
        assertEquals("× 15 above 15", 15, cityScore(civ, city, "[+1 Happiness] [in all cities]"))
        civ.ai.setPersonalityFlavor(Flavor.Offense, 7)
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 1)
        assertEquals("× 3 at 15", 3, cityScore(civ, city, "[+1 Happiness] [in all cities]"))
        civ.ai.setPersonalityFlavor(Flavor.Offense, 2)
        assertEquals("× 6 at 5", 6, cityScore(civ, city, "[+1 Happiness] [in all cities]"))
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 0)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 3)
        assertEquals("× 3 at 6", 3, cityScore(civ, city, "[+1 Happiness] [in all cities]"))
        assertEquals("a building's × 2 at the minimum followers", 6,
            cityScore(civ, city, "[+1 Happiness] from every [Shrine] <in cities with at least [3] [Followers of this Religion]>"))
        civ.ai.setPersonalityFlavor(Flavor.Defense, 10)
        assertEquals("cheaper borders (25 / 7) × max(3, Defense - Offense)", 3 * 7,
            cityScore(civ, city, "[-25]% Culture cost of natural border growth [in all cities]"))
        assertEquals("not on a hill", 0, cityScore(civ, city, "[+1 Happiness] in cities on [Hill] tiles"))
        city.getCenterTile().setBaseTerrain(game.ruleset.terrains["Plains"]!!)
        assertEquals("on plains, × 6 with Defense 10", 6, cityScore(civ, city, "[+1 Happiness] in cities on [Plains] tiles"))
    }

    @Test
    fun `with Vox Populi a city yield counts by the eras left and the population, the capital's by the eras left`() {
        val civ = vpPlotCiv()
        val capital = civ.cities.first()
        val other = game.addCity(civ, tile(4, 0))
        capital.population.setPopulation(3)
        // E = (9 eras - 0) / 2 = 4
        assertEquals(2 * (4 + 3), cityScore(civ, capital, "[+2 Gold] [in all cities]"))
        assertEquals(2 * (4 + 1), cityScore(civ, other, "[+2 Gold] [in all cities]"))
        assertEquals(2 * 4, cityScore(civ, capital, "[+2 Gold] [in capital]"))
        assertEquals(0, cityScore(civ, other, "[+2 Gold] [in capital]"))
        assertEquals("world wonders × WonderCompetitiveness", 2 * civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness),
            cityScore(civ, other, "[+2 Gold] from every [Wonder]"))
        // a building's × E, halved for a wonder, doubled once built
        assertEquals(2 * 4, cityScore(civ, other, "[+2 Gold] from every [Shrine]"))
        assertEquals(2 * 4 / 2, cityScore(civ, other, "[+2 Gold] from every [Stonehenge]"))
        other.cityConstructions.addBuilding("Shrine")
        assertEquals(2 * 4 * 2, cityScore(civ, other, "[+2 Gold] from every [Shrine]"))
        assertEquals("the follower modifier × population² / 10", 15 * 9 / 10,
            cityScore(civ, capital, "[+1]% [Production] from every follower, up to [15]%"))
        assertEquals("not coastal", 0, cityScore(civ, capital, "[+2 Gold] [in all coastal cities]"))
        assertEquals("on grassland", 2 * 4, cityScore(civ, capital, "[+2 Gold] in cities on [Grassland] tiles"))
        assertEquals("any specialist × E + the population, no slots", 2 * 4 + 3,
            cityScore(civ, capital, "[+2 Gold] [in all cities] <in cities with at least [1] [Specialists]>"))
        civ.cache.updateCitiesConnectedToCapital()
        assertEquals("trade route × E × 5 when connected", 2 * 4 * 5, cityScore(civ, capital, "[+2 Gold] from each Trade Route"))
        capital.cityStats.update()
        assertEquals("the food before eating × the ideal size, at least 30", ((capital.cityStats.excessFood + 2 * 3) * 30).toInt(),
            cityScore(civ, capital, "[+1 Faith] per [1] population [in all cities]"))
    }

    @Test
    fun `with Vox Populi a city scores strength, healing, wonder and unit production by flavors and traits`() {
        val civ = vpPlotCiv()
        val city = civ.cities.first()
        civ.ai.setPersonalityFlavor(Flavor.CityDefense, 12)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 2)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        assertEquals("(30 / 3) × max(10, 12 - 2)", 100, cityScore(civ, city, "[+30]% Strength for cities"))
        assertEquals("10 × Defense / Offense", 25, cityScore(civ, city, "[All] Units adjacent to this city heal [10] HP per turn when healing"))
        assertEquals("× its last era (Classical: 2) / 5", 15 * 2 / 5,
            cityScore(civ, city, "[+15]% Production when constructing [Classical era] wonders [in all cities]"))
        assertEquals("halved without the traits", 10, cityScore(civ, city, "[+20]% Production when constructing [Military] units [in all cities]"))
        val venice = neighbor(civ, "Venice")
        assertEquals("growth × 2 when smaller", 20, cityScore(venice, venice.cities.first(), "[+10]% growth [in all cities]"))
    }

    @Test
    fun `with Vox Populi a city scores population yields by its food over the ideal size, and the holy city yield in our holy city`() {
        val (civ, _) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val holy = civ.cities.first()
        holy.population.setPopulation(100)
        holy.cityStats.update()
        // the food before eating (2 eaten per citizen) × the ideal size: the capital's 100
        val food = ((holy.cityStats.excessFood + 2 * 100) * 100).toInt() * 100 / 100
        assertTrue("$food", food / 300 != food / 400)
        assertEquals("2 population per point", food / 2, cityScore(civ, holy, "[+2 Faith] per [4] population [in all cities]"))
        assertEquals("upon growth: × (food / 400)", 12 * (food / 400), cityScore(civ, holy, "Gain [12] [Culture] <upon population growth>"))
        val other = game.addCity(civ, tile(4, 0))
        assertEquals(3, cityScore(civ, holy, "[+3 Gold] [in holy cities]"))
        assertEquals(0, cityScore(civ, other, "[+3 Gold] [in holy cities]"))
    }

    private fun bonusGain(civ: Civilization, belief: Belief): Int {
        val context = BeliefScoringContext(civ)
        return BeliefScoring.scoreBelief(belief, context, forBonus = true) - BeliefScoring.scoreBelief(belief, context)
    }

    @Test
    fun `with Vox Populi a bonus belief adds 800 for a founder belief and 200 for an enhancer while religions can spread`() {
        val civ = vpPlotCiv()
        assertEquals(0, BeliefScoringContext(civ).religionsEnhancedPercent)
        assertEquals(800, bonusGain(civ, game.createBelief(BeliefType.Founder, "[+1 Happiness] from every [Shrine]")))
        assertEquals(200, bonusGain(civ, game.createBelief(BeliefType.Enhancer, "[+1 Happiness] from every [Shrine]")))
        val reformation = game.createBelief(BeliefType.Reformation, "[+1 Happiness] from every [Shrine]")
        assertEquals("a reformation belief", 0, bonusGain(civ, reformation))
        val worthless = game.createBelief(BeliefType.Reformation)
        assertEquals("not raised to 1", 0, BeliefScoring.scoreBelief(worthless, BeliefScoringContext(civ), forBonus = true))
        val pantheon = game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities]")
        assertEquals("20 - 500, at least 1", 1, BeliefScoring.scoreBelief(pantheon, BeliefScoringContext(civ), forBonus = true))
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = false
        assertEquals("not without the switch", 0, bonusGain(civ, pantheon))
    }

    @Test
    fun `with Vox Populi a bonus follower belief adds 100 for a building to buy with faith, 500 with a faith discount, else costs 200`() {
        val civ = vpPlotCiv()
        val cathedrals = game.createBelief(BeliefType.Follower, "May buy [Cathedral] buildings with [Faith] [in cities following this religion]")
        assertEquals(100, bonusGain(civ, cathedrals))
        val discount = neutralCiv("[Faith] cost of purchasing items in cities [-15]%")
        game.addCity(discount, tile(4, 0))
        assertEquals(500, bonusGain(discount, cathedrals))
        val goldDiscount = neutralCiv("[Gold] cost of purchasing items in cities [-15]%")
        game.addCity(goldDiscount, tile(-4, 0))
        assertEquals("a gold discount", 100, bonusGain(goldDiscount, cathedrals))
        civ.ai.setPersonalityFlavor(Flavor.Growth, 10)
        val food = game.createBelief(BeliefType.Follower, "[+2 Food] from every [Grassland]")
        val base = BeliefScoring.scoreBelief(food, BeliefScoringContext(civ))
        assertTrue("base $base", base > 200)
        assertEquals(-200, bonusGain(civ, food))
    }

    @Test
    fun `with Vox Populi a bonus belief counts against us once most religions are enhanced and no city could follow us`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        setState(heretic, ReligionState.EnhancedReligion)
        assertEquals(100, BeliefScoringContext(civ).religionsEnhancedPercent)
        val pantheon = game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities]")
        val base = BeliefScoring.scoreBelief(pantheon, BeliefScoringContext(civ))
        assertTrue("base $base", base < 500)
        // our holy city follows our state religion: 2 for our own city × the eras left / 2, of the 13 cities a map this small holds
        assertEquals("spreads", 1 - base, bonusGain(civ, pantheon))
        civ.getCapital()!!.religion.addPressure(hereticReligion.name, 100_000_000)
        assertEquals("no city follows us", 500, bonusGain(civ, pantheon))
        val founder = game.createBelief(BeliefType.Founder, "[+1 Happiness] from every [Shrine]")
        assertEquals(1, BeliefScoring.scoreBelief(founder, BeliefScoringContext(civ), forBonus = true))
        // a close neighbor's city following us: 1 × 2 (it has no religion) / 2 (close) × the eras left / 2, for all but a pantheon belief
        neighbor(civ).getCapital()!!.religion.addPressure(religion.name, 5000)
        assertEquals("only our own cities for a pantheon belief", 500, bonusGain(civ, pantheon))
        assertEquals(800, bonusGain(civ, founder))
        setState(heretic, ReligionState.Religion)
        assertEquals("half the religions enhanced", 1 - base, bonusGain(civ, pantheon))
    }

    /** The picks of [ReligionAutomation.chooseBeliefLikeVp] among [candidates] only (the other pantheons excluded), over 200 turns */
    private fun pantheonPicks(civ: Civilization, vararg candidates: Belief): Set<Belief?> {
        val others = game.ruleset.beliefs.values.filter { it.type == BeliefType.Pantheon && it !in candidates }.toSet()
        val context = BeliefScoringContext(civ, considerExpansion = true)
        return (0 until 200).mapTo(HashSet()) { turn ->
            game.gameInfo.turns = turn
            ReligionAutomation.chooseBeliefLikeVp(civ, BeliefType.Pantheon, others, context)
        }
    }

    @Test
    fun `with Vox Populi a belief is picked among all scoring at least 90 percent of the best`() {
        val civ = vpPlotCiv()
        // growth counts only for the player: 2 × the percent
        val (twenty, eighteen, sixteen) = listOf(10, 9, 8).map { game.createBelief(BeliefType.Pantheon, "[+$it]% growth [in all cities]") }
        assertEquals(listOf(20, 18, 16), listOf(twenty, eighteen, sixteen).map { BeliefScoring.scoreBelief(it, BeliefScoringContext(civ)) })
        assertEquals("16 is below 90% of 20", setOf(twenty, eighteen), pantheonPicks(civ, twenty, eighteen, sixteen))
        val four = (1..4).map { game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities]") }
        assertEquals("not only the best 3", four.toSet(), pantheonPicks(civ, *four.toTypedArray()))
    }

    @Test
    fun `with Vox Populi a belief is picked even when none scores above 0, never one excluded`() {
        val civ = vpPlotCiv()
        val worthless = game.createBelief(BeliefType.Pantheon)
        val alsoWorthless = game.createBelief(BeliefType.Pantheon)
        assertEquals(0, BeliefScoring.scoreBelief(worthless, BeliefScoringContext(civ, considerExpansion = true)))
        assertEquals(setOf(worthless, alsoWorthless), pantheonPicks(civ, worthless, alsoWorthless))
        assertEquals(setOf(worthless), pantheonPicks(civ, worthless))
    }

    @Test
    fun `with Vox Populi a belief of any type is scored as a bonus belief`() {
        val civ = vpPlotCiv()
        val founder = game.createBelief(BeliefType.Founder)
        val pantheon = game.createBelief(BeliefType.Pantheon, "[+10]% growth [in all cities]")
        val others = game.ruleset.beliefs.values.filter { it != founder && it != pantheon }.toSet()
        // 0 + 800 against 20 - 500, at least 1
        assertEquals(founder, ReligionAutomation.chooseBeliefLikeVp(civ, BeliefType.Any, others, BeliefScoringContext(civ)))
    }
    //endregion

    @Test
    fun `top choices roll only among the three best, each in proportion`() {
        val choices = listOf("a" to 10, "b" to 50, "c" to 40, "d" to 30, "e" to 5)
        val picks = (0 until 2000).map { ReligionAutomation.chooseFromTopChoices(choices, 3, Random(it))!! }
        assertEquals(setOf("b", "c", "d"), picks.toSet())
        val b = picks.count { it == "b" }
        assertTrue("b is 50/120 of the rolls, got $b", b in 750..920)
        assertEquals(null, ReligionAutomation.chooseFromTopChoices(emptyList<Pair<String, Int>>(), 3, Random(0)))
    }

    //region DoFaithPurchases

    private fun foundedCiv(vararg uniques: String): Pair<Civilization, Religion> {
        val civ = game.addCiv(*uniques)
        val capital = game.addCity(civ, tile(0, 0))
        val religion = foundReligion(civ)
        setState(civ, ReligionState.EnhancedReligion)
        capital.religion.religionThisIsTheHolyCityOf = religion.name
        capital.religion.addPressure(religion.name, 5000)
        civ.religionManager.storedFaith = 100_000
        return civ to religion
    }

    private fun follow(city: City, religion: Religion, population: Int = 3) {
        city.population.setPopulation(population)
        city.religion.addPressure(religion.name, 5000)
    }

    private fun lockOutFounding() {
        while (game.gameInfo.civilizations.first().religionManager.remainingFoundableReligions() > 0) {
            val founder = game.addCiv()
            foundReligion(founder)
        }
    }

    @Test
    fun `saves while a religion can still be founded or enhanced`() {
        val civ = game.addCiv()
        game.addCity(civ, tile(0, 0))
        assertEquals(FaithPurchase.Save, ReligionAutomation.chooseFaithPurchase(civ))
        foundReligion(civ)
        setState(civ, ReligionState.Religion)
        assertEquals(FaithPurchase.Save, ReligionAutomation.chooseFaithPurchase(civ))
    }

    @Test
    fun `locked out of founding buys a faith building of the majority, then in the Industrial era a Great Person`() {
        val civ = game.addCiv("May buy [Great Scientist] units with [Faith] [in all cities]")
        val city = game.addCity(civ, tile(0, 0))
        civ.religionManager.storedFaith = 100_000
        lockOutFounding()
        assertEquals(FaithPurchase.Save, ReligionAutomation.chooseFaithPurchase(civ))
        civ.tech.era = game.ruleset.eras["Industrial era"]!!
        assertEquals(FaithPurchase.LockedOutGreatPerson, ReligionAutomation.chooseFaithPurchase(civ))

        val other = game.addCiv()
        val religion = foundReligion(other)
        religion.addBelief("Pagodas")
        follow(city, religion)
        assertEquals(FaithPurchase.LockedOutFaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
    }

    @Test
    fun `a city needing a prophet critically comes first, while five or fewer prophets were earned`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val city = game.addCity(civ, tile(3, 0))
        follow(city, hereticReligion)
        // 3 heretics × 100 / (3 + 1) × 3 > 50
        assertEquals(FaithPurchase.Prophet, ReligionAutomation.chooseFaithPurchase(civ))
        civ.civConstructions.boughtItemsWithIncreasingPrice.add(civ.religionManager.getGreatProphetEquivalent()!!.name, 6)
        assertEquals(FaithPurchase.MissionaryForOwnCities, ReligionAutomation.chooseFaithPurchase(civ))
        assertEquals(religion, ReligionAutomation.religionToSpread(civ))
    }

    @Test
    fun `converted cities get the faith building, then non-faith buildings, then the Great Person`() {
        val (civ, religion) = foundedCiv(
            "May buy [Monument] buildings with [Faith] [in all cities]",
            "May buy [Great Scientist] units with [Faith] [in all cities]",
        )
        religion.addBelief("Pagodas")
        assertEquals(FaithPurchase.FaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
        val capital = civ.cities.first()
        capital.cityConstructions.addBuilding(civ.getEquivalentBuilding("Pagoda"))
        assertEquals(FaithPurchase.NonFaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
        capital.cityConstructions.addBuilding("Monument")
        assertTrue(ReligionAutomation.chooseFaithPurchase(civ) != FaithPurchase.GreatPerson)
        civ.tech.era = game.ruleset.eras["Industrial era"]!!
        assertEquals(FaithPurchase.GreatPerson, ReligionAutomation.chooseFaithPurchase(civ))
    }

    @Test
    fun `then missionaries for puppets, civilizations without a religion, an inquisitor, and religion founders`() {
        val (civ, religion) = foundedCiv()
        religion.addBelief("Pagodas")
        val capital = civ.cities.first()
        capital.cityConstructions.addBuilding(civ.getEquivalentBuilding("Pagoda"))
        val puppet = game.addCity(civ, tile(-3, 0))
        follow(puppet, religion)
        puppet.isPuppet = true
        assertEquals(FaithPurchase.MissionaryForPuppets, ReligionAutomation.chooseFaithPurchase(civ))
        puppet.cityConstructions.addBuilding(civ.getEquivalentBuilding("Pagoda"))

        val pagan = game.addCiv()
        val paganCity = game.addCity(pagan, tile(0, 4))
        paganCity.getCenterTile().setExplored(civ, true)
        assertEquals(FaithPurchase.MissionaryForCivsWithoutReligion, ReligionAutomation.chooseFaithPurchase(civ))
        val paganReligion = foundReligion(pagan)

        assertEquals(FaithPurchase.Inquisitor, ReligionAutomation.chooseFaithPurchase(civ))
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, capital.getCenterTile())
        inquisitor.religion = religion.name
        assertEquals(FaithPurchase.MissionaryForReligionFounders, ReligionAutomation.chooseFaithPurchase(civ))

        follow(paganCity, religion)
        assertEquals(FaithPurchase.OtherFaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
        assertTrue(paganReligion.isMajorReligion())
    }
    //endregion

    //region GetDesiredFaithGreatPerson

    private fun greatPersonCiv(): Civilization {
        val civ = game.addCiv(
            *listOf("Great Writer", "Great Scientist", "Great Merchant", "Great Engineer", "Great General")
                .map { "May buy [$it] units with [Faith] [in all cities]" }.toTypedArray()
        )
        game.addCity(civ, tile(0, 0))
        return civ
    }

    private fun desired(civ: Civilization) = ReligionAutomation.getDesiredFaithGreatPerson(civ)?.name

    @Test
    fun `faith great person follows the grand strategy, scientist always 1000`() {
        val civ = greatPersonCiv()
        // no grand strategy: scientist 1000 (the DLL overwrites its Spaceship check), the others 400 / 500
        assertEquals("Great Scientist", desired(civ))
        // one scientist bought from faith: 1000 / 2 = 500, below each grand strategy's 1000 (engineer: 2000 in the homestretch)
        civ.civConstructions.boughtItemsWithIncreasingPrice.add("Great Scientist", 1)
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        assertEquals("Great Writer", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.UNITED_NATIONS)
        assertEquals("Great Merchant", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertEquals("Great General", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.SPACESHIP)
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH"] = 0
        assertEquals("Great Engineer", desired(civ))
    }

    @Test
    fun `great generals are divided by the ones we already have`() {
        val civ = greatPersonCiv()
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        civ.civConstructions.boughtItemsWithIncreasingPrice.add("Great Scientist", 3) // 250
        civ.civConstructions.boughtItemsWithIncreasingPrice.add("Great Engineer", 1) // 250
        game.addUnit("Great General", civ, tile(1, 1))
        // general 1000 / (1 + 1 owned) = 500 > writer 400
        assertEquals("Great General", desired(civ))
        game.addUnit("Great General", civ, tile(1, 2))
        // 1000 / 3 = 333 < writer 400
        assertEquals("Great Writer", desired(civ))
    }

    @Test
    fun `with Vox Populi the faith Great Person scores 20000 - its price, plus 100 or 200 by the victory pursued`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv(
            *listOf("Great Writer", "Great Scientist", "Great Merchant", "Great General")
                .map { "May buy [$it] units with [Faith] [in all cities]" }.toTypedArray(),
            "May buy [Great Engineer] units for [340] [Faith] [in all cities]"
        )
        val capital = game.addCity(civ, tile(0, 0))
        fun price(name: String) = game.ruleset.units[name]!!.getStatBuyCost(capital, Stat.Faith)!!
        assertEquals(listOf(200, 200, 340), listOf("Great Writer", "Great Scientist", "Great Engineer").map { price(it) })
        civ.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 1) // max(100, 100 / 3 × 1.3) = 100
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        assertEquals("the writer's 200 beats the others' 100", "Great Writer", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.SPACESHIP)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 9)
        assertEquals("the engineer's 19660 + 100 / 3 × 9.3 = 19970 is below the scientist's 19800 + 200", "Great Scientist", desired(civ))
        civ.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 10)
        assertEquals("19660 + 100 / 3 × 10.3 = 20003", "Great Engineer", desired(civ))
    }

    @Test
    fun `with Vox Populi a faith Great Prophet needs the capital or holy city to follow our religion, and counts twice unenhanced`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv(
            "May buy [Great Prophet] units with [Faith] [in all cities]", "May buy [Great Scientist] units with [Faith] [in all cities]"
        )
        val holyCity = civ.getCapital()!!
        val capital = game.addCity(civ, tile(4, 0))
        civ.moveCapitalTo(capital, holyCity)
        fun price(name: String) = game.ruleset.units[name]!!.getStatBuyCost(capital, Stat.Faith)!!
        assertEquals("prophet ${price("Great Prophet")}, scientist ${price("Great Scientist")}: the holy city follows", "Great Prophet", desired(civ))
        holyCity.religion.addPressure(foundReligion(game.addCiv()).name, 1_000_000)
        assertEquals("neither follows", "Great Scientist", desired(civ))
        capital.religion.addPressure(religion.name, 5000)
        assertEquals("the capital follows", "Great Prophet", desired(civ))
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        assertEquals("enhanced, not doubled", "Great Scientist", desired(civ))
    }
    //endregion

    @Test
    fun `missionary target scores own cities x5, cities without a religion x3, peace x2, holy cities halved`() {
        val (civ, religion) = foundedCiv()
        val missionaryTile = tile(0, 1)
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, missionaryTile)
        missionary.religion = religion.name
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)

        // A city of ours held firmly by another religion: the visit cannot flip it
        val own = game.addCity(civ, tile(3, 0))
        own.religion.addPressure(hereticReligion.name, 1_000_000)
        own.getCenterTile().setExplored(civ, true)
        val d1 = missionaryTile.aerialDistanceTo(own.getCenterTile())
        assertEquals((100 * 5 - d1) * 2, ReligionAutomation.scoreCityForMissionary(civ, own, missionary, religion))

        val pagan = game.addCiv()
        val paganCity = game.addCity(pagan, tile(-3, 3))
        paganCity.religion.addPressure(hereticReligion.name, 1_000_000)
        paganCity.getCenterTile().setExplored(civ, true)
        val d2 = missionaryTile.aerialDistanceTo(paganCity.getCenterTile())
        assertEquals((100 * 3 - d2) * 2, ReligionAutomation.scoreCityForMissionary(civ, paganCity, missionary, religion))
        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        civ.getDiplomacyManager(pagan)!!.declareWar()
        assertEquals(100 * 3 - d2, ReligionAutomation.scoreCityForMissionary(civ, paganCity, missionary, religion))

        // the heretics' holy city: owner has a religion (×1), peace ×2, holy ÷2
        val holy = game.addCity(heretic, tile(0, -4))
        holy.religion.religionThisIsTheHolyCityOf = hereticReligion.name
        holy.religion.addPressure(hereticReligion.name, 1_000_000)
        holy.getCenterTile().setExplored(civ, true)
        val d3 = missionaryTile.aerialDistanceTo(holy.getCenterTile())
        assertEquals((100 - d3) * 2 / 2, ReligionAutomation.scoreCityForMissionary(civ, holy, missionary, religion))

        // a city we could flip doubles; a city of our religion or unrevealed scores 0
        val small = game.addCity(pagan, tile(4, -4))
        small.getCenterTile().setExplored(civ, true)
        assertTrue(ReligionAutomation.shouldBecomeNewMajority(small, religion.name, 1_000_000))
        assertEquals(0, ReligionAutomation.scoreCityForMissionary(civ, civ.cities.first(), missionary, religion))
        small.getCenterTile().setExplored(civ, false)
        assertEquals(0, ReligionAutomation.scoreCityForMissionary(civ, small, missionary, religion))
        assertEquals(own, ReligionAutomation.chooseMissionaryTargetCity(civ, missionary))
    }

    @Test
    fun `missionaries leave a friend with a religion of its own, and a civilization we promised, alone`() {
        val (civ, religion) = foundedCiv()
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 1))
        missionary.religion = religion.name
        val other = game.addCiv()
        val city = game.addCity(other, tile(3, 0))
        city.getCenterTile().setExplored(civ, true)
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val ours = civ.getDiplomacyManager(other)!!
        fun score() = ReligionAutomation.scoreCityForMissionary(civ, city, missionary, religion)

        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertTrue("a friend without a religion: nothing asked", score() > 0)
        foundReligion(other)
        assertEquals("a friend with a religion", 0, score())
        ours.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertTrue("a stranger", score() > 0)
        other.getDiplomacyManager(civ)!!.setFlag(DiplomacyFlags.AgreedToNotSpreadReligion, 20)
        assertEquals("our promise to them", 0, score())
        other.getDiplomacyManager(civ)!!.removeFlag(DiplomacyFlags.AgreedToNotSpreadReligion)
        ours.setFlag(DiplomacyFlags.AgreedToNotSpreadReligion, 20)
        assertTrue("their promise to us does not bind us", score() > 0)
    }

    //region Targets like Vox Populi (religionCultureAiLikeVp)

    /** A city of [owner] at [x], [y] with [population] and these accumulated pressures (atheism keeps its starting 100 plus [atheism]) */
    private fun cityWith(owner: Civilization, x: Int, y: Int, population: Int, vararg pressures: Pair<String, Int>, atheism: Int = 0): City {
        val city = game.addCity(owner, tile(x, y), initialPopulation = population)
        if (atheism > 0) city.religion.addPressure(com.unciv.Constants.noReligionName, atheism)
        for ((religion, amount) in pressures) city.religion.addPressure(religion, amount)
        for (civ in game.gameInfo.civilizations) city.getCenterTile().setExplored(civ, true)
        return city
    }

    @Test
    fun `with Vox Populi a missionary weighs the distances and how much its visit would move the city`() {
        val (civ, religion) = foundedCiv()
        assertEquals("BNW missionary: strength 1000, 2 spreads", 1000, ReligionAutomation.missionaryUnit(civ)!!.religiousStrength)
        val missionaryTile = tile(0, 1)
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, missionaryTile)
        missionary.religion = religion.name
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val pagan = game.addCiv()
        fun distances(city: City) = 50 - city.getCenterTile().aerialDistanceTo(tile(0, 0)) - city.getCenterTile().aerialDistanceTo(missionaryTile)

        // 2000 of 5000 is an impact of 40 (+17 × 3), our share 20 (+6 × 3); a major without a religion, its state religion not ours: × 3/2
        val paganCity = cityWith(pagan, 3, 0, 1, hereticReligion.name to 4000, religion.name to 1000)
        assertEquals((distances(paganCity) + 51 + 18) * 3 / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, paganCity, missionary, religion))
        assertEquals("without a unit only the holy city's distance",
            (50 - 3 + 51 + 18) * 3 / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, paganCity, null, religion))

        // no founded religion there: the impact is capped at 100 (+77 × 3, +46 × 3), × 3/2 without a majority, × 3/2 for the owner
        val emptyCity = cityWith(pagan, -3, 3, 1)
        assertEquals((distances(emptyCity) + 231 + 138) * 3 / 2 * 3 / 2,
            ReligionAutomation.scoreCityForMissionaryLikeVp(civ, emptyCity, missionary, religion))

        // our own city doubles, without the bonus for an owner without a religion
        val own = cityWith(civ, 0, -3, 1, hereticReligion.name to 4000, religion.name to 1000)
        assertEquals((distances(own) + 51 + 18) * 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, own, missionary, religion))

        // the holy city of the owner's religion is halved, an owner with a religion gets no bonus
        val holy = cityWith(heretic, 4, -4, 1, hereticReligion.name to 4000, religion.name to 1000)
        holy.religion.religionThisIsTheHolyCityOf = hereticReligion.name
        assertEquals((distances(holy) + 51 + 18) / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, holy, missionary, religion))
        // a founder without its holy city owns no religion (CvPlayerReligions::OwnsReligion): the bonus, and nothing to halve
        holy.religion.religionThisIsTheHolyCityOf = null
        assertEquals((distances(holy) + 51 + 18) * 3 / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, holy, missionary, religion))

        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        civ.getDiplomacyManager(pagan)!!.declareWar()
        assertEquals("at war", 0, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, paganCity, missionary, religion))
    }

    @Test
    fun `with Vox Populi missionaries go to the best city first and leave one another's targets alone`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 1))
        missionary.religion = religion.name
        val other = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(1, 0))
        other.religion = religion.name
        val pagan = game.addCiv()
        val near = cityWith(pagan, 3, 0, 1)
        val far = cityWith(pagan, -4, 4, 1)

        assertEquals(listOf(near, far), ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, emptyMap()))
        assertEquals(listOf(far), ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, mapOf(near to other)))
        assertEquals("its own target stays", listOf(near, far), ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, mapOf(near to missionary)))

        val cache = AutomationCache()
        val view = GameView(game.gameInfo, civ)
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(missionary), cache)
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(other), cache)
        assertEquals("the second missionary takes the next city", mapOf(near to missionary, far to other), cache.missionaryTargets)
    }

    @Test
    fun `with Vox Populi heresy is removed from a city where another religion leads, or gains on ours without a majority`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)

        // 7 citizens, 700 pressure: 4 atheists, 2 of the strongest religion, 1 of the other; no majority
        val split = cityWith(civ, -3, 3, 7, religion.name to 200, hereticReligion.name to 100, atheism = 300)
        assertEquals(null, split.religion.getMajorityReligion())
        fun perTurn() = split.religion.getPressuresFromSurroundingCities()
        assertTrue("precondition: the heretics gain no more than we do", perTurn()[hereticReligion.name] <= perTurn()[religion.name])
        assertTrue(!ReligionAutomation.shouldRemoveHeresyLikeVp(split, religion.name))
        for (x in listOf(-4, -2, 2)) cityWith(heretic, x, -4, 1, hereticReligion.name to 4000)
        assertTrue("precondition: the heretics gain more", perTurn()[hereticReligion.name] > perTurn()[religion.name])
        assertTrue(ReligionAutomation.shouldRemoveHeresyLikeVp(split, religion.name))

        val hereticMajority = cityWith(civ, 3, 0, 1, hereticReligion.name to 4000)
        assertTrue(ReligionAutomation.shouldRemoveHeresyLikeVp(hereticMajority, religion.name))
        assertTrue("ours is the majority", !ReligionAutomation.shouldRemoveHeresyLikeVp(civ.cities.first(), religion.name))
        val hereticsLead = cityWith(civ, 0, 5, 7, hereticReligion.name to 200, religion.name to 100, atheism = 300)
        assertEquals(null, hereticsLead.religion.getMajorityReligion())
        assertTrue(ReligionAutomation.shouldRemoveHeresyLikeVp(hereticsLead, religion.name))
    }

    @Test
    fun `with Vox Populi an inquisitor goes against more than 6 heretics, doubled under a heretic majority, farthest first`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val inquisitorTile = tile(0, 1)
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, inquisitorTile)
        inquisitor.religion = religion.name
        fun score(city: City, unit: MapUnit? = inquisitor) = ReligionAutomation.scoreCityForInquisitorOffensiveLikeVp(civ, city, unit, religion)

        // 6 citizens: 3 heretics (the majority), 2 ours, 1 atheist: 3 × 2 = 6 is not enough
        val three = cityWith(civ, 3, 0, 6, hereticReligion.name to 3000, religion.name to 2000, atheism = 900)
        assertEquals(3, three.religion.getFollowersOf(hereticReligion.name))
        assertEquals(0, score(three))
        // 8 citizens: 4 heretics, the majority: 8 scores the distance, or the count without a unit
        val four = cityWith(civ, -3, 3, 8, hereticReligion.name to 4000, religion.name to 3000, atheism = 900)
        assertEquals(4, four.religion.getFollowersOf(hereticReligion.name))
        assertEquals(inquisitorTile.aerialDistanceTo(four.getCenterTile()), score(four))
        assertEquals(8, score(four, null))
        inquisitor.putInTile(four.getCenterTile())
        assertEquals("a unit in the city scores its distance, 0", 0, score(four))
        val foreign = cityWith(heretic, 0, -5, 8, hereticReligion.name to 4000, religion.name to 3000, atheism = 900)
        assertEquals(0, score(foreign))
    }

    @Test
    fun `with Vox Populi an inquisitor guards the holy city or a border city a missionary could sway`() {
        val (civ, religion) = foundedCiv()
        val inquisitorTile = tile(0, 2)
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, inquisitorTile)
        inquisitor.religion = religion.name
        fun score(city: City, unfriendly: List<Civilization> = emptyList(), unit: MapUnit? = inquisitor) =
            ReligionAutomation.scoreCityForInquisitorDefensiveLikeVp(civ, city, unit, religion, unfriendly)
        val capital = civ.cities.first()

        // 2000 of 5000: impact 40 + 7 for the holy city
        assertEquals(2, score(capital))
        assertEquals(47, score(capital, unit = null))
        capital.religion.addPressure(religion.name, 5000)
        assertEquals("impact 20 + 7 for the holy city", 27, score(capital, unit = null))

        val twenty = cityWith(civ, 3, -3, 1, religion.name to 10_000)
        assertEquals("impact 20 is not enough", 0, score(twenty, unit = null))
        val twentyTwo = cityWith(civ, 0, -4, 1, religion.name to 9000)
        assertEquals("impact 22 is not enough either", 0, score(twentyTwo, unit = null))
        val twentyFive = cityWith(civ, -3, 0, 1, religion.name to 8000)
        assertEquals(25, score(twentyFive, unit = null))

        val rival = game.addCiv()
        cityWith(rival, 5, -3, 1)
        assertEquals("impact 20 + 11 on the border", 31, score(twenty, listOf(rival), unit = null))
    }

    @Test
    fun `with Vox Populi an inquisitor tries heretic cities first and skips a city another of ours already guards`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        // next to the capital: it does not count as a guard against itself
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, tile(0, 1))
        inquisitor.religion = religion.name
        val capital = civ.cities.first()
        val heretics = cityWith(civ, -3, 3, 8, hereticReligion.name to 4000, religion.name to 3000, atheism = 900)

        val targets = ReligionAutomation.inquisitorTargetsLikeVp(civ, inquisitor, emptyMap())
        assertEquals(listOf(heretics), targets.offensive)
        assertEquals(listOf(capital), targets.defensive)

        val guard = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, tile(1, 0))
        guard.religion = religion.name
        assertEquals(emptyList<City>(), ReligionAutomation.inquisitorTargetsLikeVp(civ, inquisitor, emptyMap()).defensive)
        assertEquals(emptyList<City>(), ReligionAutomation.inquisitorTargetsLikeVp(civ, inquisitor, mapOf(heretics to guard)).offensive)
    }

    @Test
    fun `with Vox Populi an inquisitor of another religion works for it, a missionary of another religion without a target is disbanded`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val hereticReligion = foundReligion(game.addCiv())
        val ours = cityWith(civ, -3, 3, 8, religion.name to 50_000)
        val foreignInquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, tile(0, 1))
        foreignInquisitor.religion = hereticReligion.name
        assertEquals("our 8 followers are heretics to it", listOf(ours),
            ReligionAutomation.inquisitorTargetsLikeVp(civ, foreignInquisitor, emptyMap()).offensive)

        // no other city to convert: our missionary waits, the other one goes
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(1, 0))
        missionary.religion = religion.name
        val foreignMissionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(1, -1))
        foreignMissionary.religion = hereticReligion.name
        val view = GameView(game.gameInfo, civ)
        val cache = AutomationCache()
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(missionary), cache)
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(foreignMissionary), cache)
        ReligiousUnitAutomation.automateInquisitor(view.getMapUnitView(foreignInquisitor), cache)
        val units = civ.units.getCivUnits().toList()
        assertTrue(missionary in units)
        assertTrue(foreignMissionary !in units)
        assertTrue(foreignInquisitor in units)
        assertEquals(foreignInquisitor, cache.inquisitorTargets[ours])
    }
    //endregion

    //region Religion to spread like Vox Populi

    /** A religion with a Follower belief, whose score is the same for every religion that has it */
    private fun religionWithFollowerBelief(founder: Civilization): Religion {
        val religion = foundReligion(founder)
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Follower })
        return religion
    }

    private fun noMoreReligionsToFound() {
        game.ruleset.modOptions.constants.religionLimitBase = 0
        game.ruleset.modOptions.constants.religionLimitMultiplier = 0f
    }

    @Test
    fun `with Vox Populi the religion to spread is the state religion, and none for a City-State or a founder without one`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        assertEquals(religion, ReligionAutomation.religionToSpread(civ))

        val cityState = game.addCiv(cityStateType = "Cultured")
        cityWith(cityState, 3, 0, 1, religion.name to 5000)
        assertEquals(religion, cityState.religionManager.getStateReligion())
        assertEquals(null, ReligionAutomation.religionToSpread(cityState, considerForeign = true))

        // a founder whose holy city is gone and whose cities follow nothing has no state religion
        val founder = game.addCiv()
        val lost = religionWithFollowerBelief(founder)
        cityWith(founder, -3, 3, 1)
        assertEquals(lost, ReligionAutomation.foundedReligion(founder))
        assertEquals(null, ReligionAutomation.religionToSpread(founder))
    }

    @Test
    fun `with Vox Populi a civilization without a state religion spreads the foreign one it likes best that it can buy missionaries of`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv()
        val friend = game.addCiv()
        val liked = religionWithFollowerBelief(friend)
        val rival = game.addCiv()
        val other = religionWithFollowerBelief(rival)
        civ.ai.diplomacy.memoryOf(friend).opinion = MajorCivOpinion.Friend
        civ.ai.diplomacy.memoryOf(rival).opinion = MajorCivOpinion.Favorable
        // one city each: neither is the majority of our cities, so there is no state religion
        val likedCity = cityWith(civ, 0, 0, 1, liked.name to 5000)
        cityWith(civ, 3, 0, 1, other.name to 5000)
        assertEquals(null, civ.religionManager.getStateReligion())
        game.ruleset.modOptions.constants.religionLimitBase = 4
        assertTrue(civ.religionManager.remainingFoundableReligions() > 0)
        assertEquals("not while religions can still be founded", null, ReligionAutomation.religionToSpread(civ, considerForeign = true))

        noMoreReligionsToFound()
        assertEquals("130% for a friend over 115%", liked, ReligionAutomation.religionToSpread(civ, considerForeign = true))
        assertEquals("only when the caller considers foreign religions", null, ReligionAutomation.religionToSpread(civ))

        // the liked one is no longer bought here; the other, of a competitor (50%), is under 2/3 of it
        likedCity.religion.addPressure(other.name, 50_000)
        civ.ai.diplomacy.memoryOf(rival).opinion = MajorCivOpinion.Competitor
        assertEquals(null, ReligionAutomation.favoriteForeignReligion(civ, forInternalSpread = true))
        assertEquals(other, ReligionAutomation.favoriteForeignReligion(civ, forInternalSpread = false))
    }

    @Test
    fun `with Vox Populi the founder beliefs of a foreign religion are worth nothing to us`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        noMoreReligionsToFound()
        val civ = game.addCiv()
        val founderOnly = foundReligion(game.addCiv())
        cityWith(civ, 0, 0, 1, founderOnly.name to 5000)
        assertEquals(null, ReligionAutomation.favoriteForeignReligion(civ, forInternalSpread = false))
    }

    @Test
    fun `with Vox Populi a missionary of a religion we do not own spreads it only in our own cities`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        noMoreReligionsToFound()
        val civ = game.addCiv()
        val friend = game.addCiv()
        val liked = religionWithFollowerBelief(friend)
        cityWith(civ, 0, 0, 1, liked.name to 5000)
        val pagan = cityWith(civ, 3, 0, 1)
        cityWith(civ, -3, 3, 1)
        val foreignPagan = cityWith(game.addCiv(), 0, -4, 1)
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 1))
        missionary.religion = liked.name
        assertEquals(liked, ReligionAutomation.religionToSpread(civ, considerForeign = true))
        val targets = ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, emptyMap())
        assertTrue(pagan in targets)
        assertTrue(foreignPagan !in targets)
    }

    @Test
    fun `with Vox Populi a founder that lost its holy city owns no religion, so converting it is never a bad theft`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv()
        game.addCity(civ, tile(-3, 0))
        val founder = game.addCiv()
        val religion = religionWithFollowerBelief(founder)
        val holy = game.addCity(founder, tile(3, 0))
        holy.religion.religionThisIsTheHolyCityOf = religion.name
        civ.diplomacyFunctions.makeCivilizationsMeet(founder)
        civ.getDiplomacyManager(founder)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertTrue("a pact partner with a religion", TheftAi.isBadTheftTarget(civ, founder, TheftType.Conversion))
        holy.religion.religionThisIsTheHolyCityOf = null
        assertTrue("CvPlayerReligions::OwnsReligion", !TheftAi.isBadTheftTarget(civ, founder, TheftType.Conversion))
    }
    //endregion

    //region DoFaithPurchases like Vox Populi

    private fun count(civ: Civilization, unit: String) = civ.units.getCivUnits().count { it.name == unit }

    /** Unciv buys no civilian in a city whose center holds one, so the bought units walk out between purchases */
    private fun leaveCapital(civ: Civilization) {
        for ((index, unit) in civ.getCapital()!!.getCenterTile().getUnits().toList().withIndex()) {
            unit.removeFromTile()
            unit.putInTile(tile(-2, -2 - civ.units.getCivUnits().count() - index))
        }
    }

    @Test
    fun `with Vox Populi an enhanced religion buys two inquisitors before a missionary for a heretic city of ours, and saves when it cannot pay`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        val heretics = foundReligion(game.addCiv())
        val city = game.addCity(civ, tile(3, 0))
        follow(city, heretics)
        val inquisitor = ReligionAutomation.inquisitorUnit(civ)!!.name
        val missionary = ReligionAutomation.missionaryUnit(civ)!!.name

        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        leaveCapital(civ)
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        leaveCapital(civ)
        assertEquals(2, count(civ, inquisitor))
        assertEquals(0, count(civ, missionary))
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        leaveCapital(civ)
        assertEquals(2, count(civ, inquisitor))
        assertEquals(1, count(civ, missionary))
        assertTrue(civ.units.getCivUnits().all { it.religion == religion.name })

        civ.religionManager.storedFaith = 0
        assertTrue("saving for the next one", ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(1, count(civ, missionary))
    }

    @Test
    fun `with Vox Populi too many missionaries make us buy the Great Prophet that enhances our religion`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv("May buy [Great Prophet] units with [Faith] [in all cities]")
        setState(civ, ReligionState.Religion)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 0)
        val prophet = civ.religionManager.getGreatProphetEquivalent()!!.name
        val limit = game.ruleset.ai.define(AiDefine.RELIGION_MAX_MISSIONARIES) + ReligionAutomation.spreadScore(civ, religion)
        repeat(limit - 1) {
            game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 2 + it)).religion = religion.name
        }
        val heretics = foundReligion(game.addCiv())
        game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(-2, -2)).religion = heretics.name
        assertTrue("no prophet comes soon at no faith a turn", !ReligionAutomation.isProphetGainRateAcceptable(civ))
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(0, count(civ, prophet))

        game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 1)).religion = religion.name
        civ.religionManager.storedFaith = 0
        assertTrue("saving for the prophet", ReligionAutomation.doFaithPurchasesLikeVp(civ))
        civ.religionManager.storedFaith = 100_000
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(1, count(civ, prophet))
    }

    @Test
    fun `with Vox Populi a prophet is soon enough under 20 turns while two cities follow us, else under 30`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        val cost = civ.religionManager.faithForNextGreatProphet()
        civ.stats.statsForNextTurn = Stats(faith = cost / 20f)
        assertTrue(!ReligionAutomation.isProphetGainRateAcceptable(civ))
        civ.stats.statsForNextTurn = Stats(faith = cost / 19f)
        assertTrue(ReligionAutomation.isProphetGainRateAcceptable(civ))

        civ.stats.statsForNextTurn = Stats(faith = cost / 29f)
        assertTrue(!ReligionAutomation.isProphetGainRateAcceptable(civ))
        follow(game.addCity(civ, tile(3, 0)), religion)
        follow(game.addCity(civ, tile(-3, 0)), religion)
        civ.stats.statsForNextTurn = Stats(faith = cost / 29f) // adding a city updates the stats
        assertTrue("three cities follow us", ReligionAutomation.isProphetGainRateAcceptable(civ))
        civ.stats.statsForNextTurn = Stats(faith = cost / 30f)
        assertTrue(!ReligionAutomation.isProphetGainRateAcceptable(civ))
    }

    @Test
    fun `with Vox Populi a prophet is soon enough under 25 turns for a religion with a faith building`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        religion.addBelief("Pagodas") // before the first read of the religion's lazy faith buildings
        follow(game.addCity(civ, tile(3, 0)), religion)
        follow(game.addCity(civ, tile(-3, 0)), religion)
        val cost = civ.religionManager.faithForNextGreatProphet()
        civ.stats.statsForNextTurn = Stats(faith = cost / 25f)
        assertTrue("25 turns with a faith building", !ReligionAutomation.isProphetGainRateAcceptable(civ))
        civ.stats.statsForNextTurn = Stats(faith = cost / 24f)
        assertTrue(ReligionAutomation.isProphetGainRateAcceptable(civ))
    }

    @Test
    fun `with Vox Populi the spread score counts the yields of spreading, conversion and following cities`() {
        val (civ, religion) = foundedCiv()
        assertEquals(0, ReligionAutomation.spreadScore(civ, religion))
        religion.addBelief(game.createBelief(BeliefType.Founder, "Gain [10] [Gold] <upon spreading a religion to a city>").name)
        religion.addBelief(game.createBelief(BeliefType.Founder, "Gain [10] [Science] <upon spreading a religion to a city of another civilization>").name)
        religion.addBelief(game.createBelief(BeliefType.Follower, "Gain [1] [Faith] <upon a city adopting our religion for the first time>").name)
        assertEquals(3, ReligionAutomation.spreadScore(civ, religion))
        religion.addBelief(game.createBelief(BeliefType.Follower, "[+1 Gold, +2 Culture] [in cities following this religion]").name)
        assertEquals(5, ReligionAutomation.spreadScore(civ, religion))
        religion.addBelief(game.createBelief(BeliefType.Follower,
            "[+0.25 Faith] [in cities following this religion] <for every [Followers of the majority religion of this city in its owner's cities]>").name)
        religion.addBelief(game.createBelief(BeliefType.Follower,
            "[+1 Faith] [in cities following this religion] <for every [Followers of the majority religion in this city]>").name)
        assertEquals("not for the local followers", 6, ReligionAutomation.spreadScore(civ, religion))
    }

    @Test
    fun `with Vox Populi a conversion target is near, not at war, of a civilization without a religion or not mostly of another one`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        val pagan = game.addCiv()
        val paganCity = cityWith(pagan, 0, 4, 4)
        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        assertTrue(ReligionAutomation.haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = false, heathensOnly = true))

        val heretics = foundReligion(game.addCiv())
        paganCity.religion.addPressure(heretics.name, 5000)
        assertTrue("mostly heretics", !ReligionAutomation.haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = true, heathensOnly = true))
        assertTrue(ReligionAutomation.haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = true, heathensOnly = false))
        assertTrue("its owner spreads its state religion",
            !ReligionAutomation.haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = false, heathensOnly = false))

        civ.getDiplomacyManager(pagan)!!.declareWar()
        assertTrue("at war", !ReligionAutomation.haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = true, heathensOnly = false))
    }

    @Test
    fun `with Vox Populi a foreign religion we spread at home gets one missionary at most`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        noMoreReligionsToFound()
        val civ = game.addCiv()
        val liked = religionWithFollowerBelief(game.addCiv())
        liked.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        cityWith(civ, 0, 0, 3, liked.name to 5000)
        cityWith(civ, 3, 0, 3)
        cityWith(civ, -3, 3, 3)
        civ.religionManager.storedFaith = 100_000
        val missionary = ReligionAutomation.missionaryUnit(civ)!!.name
        assertEquals(liked, ReligionAutomation.religionToSpread(civ, considerForeign = true))

        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(1, count(civ, missionary))
        leaveCapital(civ)
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(1, count(civ, missionary))
        assertEquals("no inquisitors for a religion we do not own", 0, count(civ, ReligionAutomation.inquisitorUnit(civ)!!.name))
    }

    @Test
    fun `with Vox Populi a religious civilization whose cities all follow it buys missionaries for foreign targets by its religion flavor`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        val pagan = game.addCiv()
        cityWith(pagan, 0, 4, 4)
        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        val missionary = ReligionAutomation.missionaryUnit(civ)!!.name

        civ.ai.setPersonalityFlavor(Flavor.Religion, 4)
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(0, count(civ, missionary))
        civ.ai.setPersonalityFlavor(Flavor.Religion, 5)
        assertTrue("bought, not saving", !ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals("5 with an easy target", 1, count(civ, missionary))
        leaveCapital(civ)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 10)
        assertTrue("the faith stays put at 10", ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(2, count(civ, missionary))
    }

    @Test
    fun `with Vox Populi a city where most are of no religion is a target for heathens`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        val heretics = foundReligion(game.addCiv())
        val pagan = game.addCiv()
        val city = cityWith(pagan, 0, 4, 4, heretics.name to 1000, atheism = 3000)
        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        assertEquals(1, city.religion.getNumberOfFollowers()[heretics.name])
        assertTrue(ReligionAutomation.haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = true, heathensOnly = true))
    }

    @Test
    fun `with Vox Populi an Industrial civilization saves for a Great Person that its faith reaches in fewer turns than 23 - religion flavor`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv("May buy [Great Scientist] units with [Faith] [in all cities]")
        val capital = game.addCity(civ, tile(0, 0))
        civ.tech.era = game.ruleset.eras["Industrial era"]!!
        civ.ai.setPersonalityFlavor(Flavor.Religion, 0)
        val scientist = ReligionAutomation.getDesiredFaithGreatPerson(civ)!!
        val cost = scientist.getStatBuyCost(capital, Stat.Faith)!!
        civ.religionManager.storedFaith = cost - 220
        civ.stats.statsForNextTurn = Stats(faith = 10f)
        assertTrue("22 turns", ReligionAutomation.doFaithPurchasesLikeVp(civ))
        civ.religionManager.storedFaith = cost - 230
        assertTrue("23 turns", !ReligionAutomation.doFaithPurchasesLikeVp(civ))
        civ.ai.setPersonalityFlavor(Flavor.Religion, 10)
        civ.religionManager.storedFaith = cost - 130
        assertTrue("13 turns are no less than 13", !ReligionAutomation.doFaithPurchasesLikeVp(civ))
        civ.religionManager.storedFaith = cost - 120
        assertTrue("12 turns", ReligionAutomation.doFaithPurchasesLikeVp(civ))

        civ.religionManager.storedFaith = cost
        assertTrue(!ReligionAutomation.doFaithPurchasesLikeVp(civ))
        assertEquals(1, count(civ, scientist.name))
    }

    @Test
    fun `with Vox Populi the turn's faith goes through DoFaithPurchases`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        follow(game.addCity(civ, tile(3, 0)), foundReligion(game.addCiv()))
        ReligionAutomation.spendFaithOnReligion(civ)
        assertEquals(1, count(civ, ReligionAutomation.inquisitorUnit(civ)!!.name))
    }
    //endregion

    //region DoTurn like Vox Populi

    @Test
    fun `with Vox Populi a foreign Great Prophet of another religion that reaches our unguarded city this turn makes us buy an emergency inquisitor`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv()
        val foreigner = game.addCiv()
        val heretics = foundReligion(foreigner)
        val prophetName = civ.religionManager.getGreatProphetEquivalent()!!.name
        val inquisitor = ReligionAutomation.inquisitorUnit(civ)!!.name

        game.addUnit(prophetName, civ, tile(2, 0)).religion = heretics.name
        assertTrue("our own prophet", !ReligionAutomation.doReligionDefenseInCitiesLikeVp(civ))

        val prophet = game.addUnit(prophetName, foreigner, tile(0, 3))
        prophet.religion = religion.name
        assertTrue("a prophet of our religion", !ReligionAutomation.doReligionDefenseInCitiesLikeVp(civ))
        prophet.religion = heretics.name
        civ.religionManager.storedFaith = 0
        assertTrue("no faith for it", !ReligionAutomation.doReligionDefenseInCitiesLikeVp(civ))
        civ.religionManager.storedFaith = 100_000
        assertTrue(ReligionAutomation.doReligionDefenseInCitiesLikeVp(civ))
        assertEquals(1, count(civ, inquisitor))
        assertTrue("guarded now", !ReligionAutomation.doReligionDefenseInCitiesLikeVp(civ))
    }

    @Test
    fun `with Vox Populi a foreign Great Prophet that cannot reach our city this turn makes us buy no inquisitor`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, _) = foundedCiv()
        val foreigner = game.addCiv()
        val prophet = game.addUnit(civ.religionManager.getGreatProphetEquivalent()!!.name, foreigner, tile(0, 3))
        prophet.religion = foundReligion(foreigner).name
        prophet.currentMovement = 0f
        assertTrue(!ReligionAutomation.doReligionDefenseInCitiesLikeVp(civ))
    }

    @Test
    fun `with Vox Populi the faith left buys the faith building of each city's religion, the holy city first, unless we must save to found a religion`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        game.ruleset.modOptions.constants.religionLimitBase = 4
        val civ = game.addCiv()
        val liked = foundReligion(game.addCiv())
        liked.addBelief("Pagodas")
        val capital = cityWith(civ, 0, 0, 3, liked.name to 5000)
        val other = cityWith(civ, 4, 0, 3, liked.name to 5000)
        capital.religion.religionThisIsTheHolyCityOf = liked.name
        val pagoda = game.ruleset.buildings["Pagoda"]!!
        civ.religionManager.storedFaith = pagoda.getStatBuyCost(capital, Stat.Faith)!!
        assertTrue(civ.religionManager.remainingFoundableReligions() > 0)
        ReligionAutomation.spendFaithOnReligion(civ)
        assertTrue("saving to found a religion", !capital.cityConstructions.isBuilt(pagoda.name))

        noMoreReligionsToFound()
        capital.cityStats.currentCityStats = Stats(faith = 3f)
        other.cityStats.currentCityStats = Stats(faith = 5f)
        ReligionAutomation.spendFaithOnReligion(civ)
        assertTrue("3 doubled in the holy city", capital.cityConstructions.isBuilt(pagoda.name))
        assertTrue(!other.cityConstructions.isBuilt(pagoda.name))
    }

    @Test
    fun `with Vox Populi the turn's faith left by DoFaithPurchases buys the emergency inquisitor`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, _) = foundedCiv()
        val foreigner = game.addCiv()
        game.addUnit(civ.religionManager.getGreatProphetEquivalent()!!.name, foreigner, tile(0, 3)).religion = foundReligion(foreigner).name
        ReligionAutomation.spendFaithOnReligion(civ)
        assertEquals(1, count(civ, ReligionAutomation.inquisitorUnit(civ)!!.name))
    }

    @Test
    fun `with Vox Populi a city buys the faith building of its religion before other buildings`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, religion) = foundedCiv("May buy [Monument] buildings with [Faith] [in all cities]")
        religion.addBelief("Pagodas")
        val capital = civ.getCapital()!!
        ReligionAutomation.spendFaithOnReligion(civ)
        assertTrue(capital.cityConstructions.isBuilt("Pagoda"))
        assertTrue(!capital.cityConstructions.isBuilt("Monument"))
    }

    @Test
    fun `with Vox Populi the faith left buys other buildings that add no religious pressure`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val pressure = game.createBuilding("[+25]% Natural religion spread [in this city]")
        val plain = game.createBuilding()
        val (civ, _) = foundedCiv("May buy [${pressure.name}] buildings with [Faith] [in all cities]",
            "May buy [${plain.name}] buildings with [Faith] [in all cities]")
        val capital = civ.getCapital()!!
        ReligionAutomation.spendFaithOnReligion(civ)
        assertTrue(!capital.cityConstructions.isBuilt(pressure.name))
        assertTrue(capital.cityConstructions.isBuilt(plain.name))
    }

    /** A converted civilization that may buy Warriors with Faith, has the gold to buy them too (ChooseHurry's sanity asks it) and earns gold */
    private fun warriorBuyer(): Civilization {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val (civ, _) = foundedCiv("May buy [Warrior] units with [Faith] [in all cities]")
        civ.addGold(10_000)
        civ.stats.statsForNextTurn = Stats(gold = 5f)
        return civ
    }

    @Test
    fun `with Vox Populi a converted civilization earning gold buys a unit with the faith left`() {
        val civ = warriorBuyer()
        ReligionAutomation.spendFaithOnReligion(civ)
        assertEquals(1, count(civ, "Warrior"))
    }

    @Test
    fun `with Vox Populi no unit is bought with faith without net gold`() {
        val civ = warriorBuyer()
        civ.stats.statsForNextTurn = Stats(gold = 0f)
        ReligionAutomation.spendFaithOnReligion(civ)
        assertEquals(0, count(civ, "Warrior"))
    }

    @Test
    fun `with Vox Populi no unit is bought with faith while a city of ours follows another religion`() {
        val civ = warriorBuyer()
        val religion = civ.religionManager.religion!!
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        follow(game.addCity(civ, tile(4, 0)), foundReligion(game.addCiv()))
        // so many missionaries that DoFaithPurchases buys none for the heretic city and leaves the faith to the cities
        for (place in tile(0, 0).getTilesAtDistance(6).take(20))
            game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, place).religion = religion.name
        civ.stats.statsForNextTurn = Stats(gold = 5f) // adding units updates the stats
        ReligionAutomation.spendFaithOnReligion(civ)
        assertEquals(0, count(civ, "Warrior"))
    }

    @Test
    fun `ChooseHurry for Faith offers only what the city may buy with Faith, and with units only no building`() {
        val (civ, religion) = foundedCiv()
        religion.addBelief("Pagodas")
        civ.addGold(10_000)
        val capital = civ.getCapital()!!
        assertEquals(null, HurryChoice.choose(capital, unitOnly = true, faithPurchase = true))
        assertEquals("Pagoda", HurryChoice.choose(capital, unitOnly = false, faithPurchase = true)?.construction?.name)
    }

    @Test
    fun `with Vox Populi a unit is bought with faith while we have at most 5 of its unit AI`() {
        for (others in 5..6) {
            setUp()
            val civ = warriorBuyer()
            val unitAi = UnitAiTypes.defaultOf(game.ruleset.units["Warrior"]!!, game.ruleset.ai)
            // civilians given the Warrior's unit AI, so that the military sanity check does not see them
            for (place in tile(0, 0).getTilesAtDistance(6).take(others))
                UnitAiTypes.set(game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, place), unitAi)
            civ.stats.statsForNextTurn = Stats(gold = 5f) // adding units updates the stats
            ReligionAutomation.spendFaithOnReligion(civ)
            assertEquals("with $others", if (others <= 5) 1 else 0, count(civ, "Warrior"))
        }
    }
    //endregion

    //region Great Prophets (CvPlayerAI::GetDirectiveProphet, CvReligionAI::ChooseProphetConversionCity)

    private fun prophet(civ: Civilization, religion: Religion, tile: Tile, spreads: Int = 0): MapUnit {
        val unit = game.addUnit("Great Prophet", civ, tile)
        unit.religion = religion.name
        @Suppress("DEPRECATION")
        val spread = unit.getMatchingUniques(UniqueType.CanSpreadReligion).first().text.removeConditionals()
        if (spreads > 0) unit.abilityToTimesUsed[spread] = spreads
        return unit
    }

    @Test
    fun `with Vox Populi a Great Prophet converts our holy city first, then our cities by heretics and atheists over the distance to the holy city`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val underHeretics = cityWith(civ, 3, 0, 8, hereticReligion.name to 5000)
        val atheists = cityWith(civ, -3, 0, 8, atheism = 5000)
        assertEquals("7 follow the other religion, 1 none", 7, underHeretics.religion.getFollowersOf(hereticReligion.name))
        val hereticsScore = 8 * 100 / (3 + 1) * 3
        // the citizens who follow no religion count too: here all 8
        val atheistsScore = 8 * 100 / (3 + 1)
        assertTrue("no followers in the atheist city", atheists.religion.getNumberOfFollowers().isEmpty())
        assertTrue("$hereticsScore > 500", hereticsScore > 500)
        assertEquals("heretics × 100 / (distance to the holy city + 1), × 3 under a founded religion; atheists not above 500",
            listOf(underHeretics to hereticsScore), ReligionAutomation.prophetTargetsLikeVp(civ, null))

        val used = prophet(civ, religion, tile(1, 1), spreads = 1)
        assertEquals("above 10 for a prophet that has spread", listOf(underHeretics to hereticsScore, atheists to atheistsScore),
            ReligionAutomation.prophetTargetsLikeVp(civ, used))
        val inquisitor = game.addUnit("Inquisitor", civ, tile(3, 1))
        inquisitor.religion = hereticReligion.name
        assertEquals("not where another religion's inquisitor guards", listOf(atheists to atheistsScore), ReligionAutomation.prophetTargetsLikeVp(civ, used))
        assertEquals("a guard counts only for a unit", listOf(underHeretics to hereticsScore), ReligionAutomation.prophetTargetsLikeVp(civ, null))
        inquisitor.destroy()

        val capital = civ.getCapital()!!
        capital.religion.addPressure(hereticReligion.name, 1_000_000)
        assertEquals(capital to 100_000, ReligionAutomation.prophetTargetsLikeVp(civ, null).first())
    }

    @Test
    fun `with Vox Populi a Great Prophet weighs foreign cities by heretics, the pressures, holy cities, founders and our beliefs`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val hereticHoly = cityWith(heretic, 5, 0, 12, hereticReligion.name to 5000)
        hereticHoly.religion.religionThisIsTheHolyCityOf = hereticReligion.name
        val pagan = game.addCiv()
        val converted = cityWith(pagan, -5, 0, 12, hereticReligion.name to 5000)
        cityWith(pagan, 0, 5, 12, atheism = 5000) // no followers of another religion
        val nearUs = cityWith(pagan, -2, 2, 12, hereticReligion.name to 5000)
        for ((x, y) in listOf(-1 to 3, -3 to 1, -1 to 1)) follow(game.addCity(civ, tile(x, y)), religion, 10)
        val used = prophet(civ, religion, tile(1, 1), spreads = 1)
        fun theirsAndOurs(city: City) = city.religion.getPressuresFromSurroundingCities().let { it[hereticReligion.name] to it[religion.name] }
        // followers of other religions × 1000 / (distance to the holy city + 7), × their pressure per turn / ours when that is smaller
        fun base(city: City): Int {
            val score = city.religion.getFollowersOf(hereticReligion.name) * 1000 / (city.getCenterTile().aerialDistanceTo(tile(0, 0)) + 7)
            val (theirs, ours) = theirsAndOurs(city)
            return if (theirs < ours) score * theirs / ours else score
        }
        assertTrue("their pressure below ours", theirsAndOurs(nearUs).let { it.first in 1 until it.second })
        assertTrue("their pressure not below ours", theirsAndOurs(hereticHoly).let { it.first >= it.second })

        // × 2 when the majority religion's founder does not own the city, ÷ 2 for the holy city of the owner's religion
        val expected = listOf(nearUs to base(nearUs) * 2, converted to base(converted) * 2, hereticHoly to base(hereticHoly) / 2)
        assertEquals(expected.sortedByDescending { it.second }, ReligionAutomation.prophetTargetsLikeVp(civ, used))

        // × 2 for each yield our beliefs give for foreign followers or cities, × 2 for happiness from peaceful foreign followers
        religion.addBelief("Pilgrimage")
        religion.addBelief("World Church")
        religion.addBelief("Peace Loving")
        assertEquals(expected.map { it.first to it.second * 8 }.sortedByDescending { it.second }, ReligionAutomation.prophetTargetsLikeVp(civ, used))

        val guard = game.addUnit("Inquisitor", pagan, tile(-5, 1))
        guard.religion = hereticReligion.name
        civ.viewableTiles = civ.viewableTiles + guard.getTile()
        civ.diplomacyFunctions.makeCivilizationsMeet(heretic)
        civ.getDiplomacyManager(heretic)!!.declareWar()
        assertEquals("not guarded against us, not at war", listOf(nearUs), ReligionAutomation.prophetTargetsLikeVp(civ, used).map { it.first })
    }

    @Test
    fun `with Vox Populi a Great Prophet builds Holy Sites while we have few, spreads once it has, and enhances while our holy city follows us`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val view = GameView(game.gameInfo, civ)
        val capital = civ.getCapital()!!
        leaveCapital(civ)
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val target = cityWith(civ, 3, 0, 8, hereticReligion.name to 5000)
        fun automate(unit: MapUnit) = ReligiousUnitAutomation.automateGreatProphet(view.getMapUnitView(unit))

        // not enhanced, our holy city under another religion: spread there, in the holy city itself
        setState(civ, ReligionState.Religion)
        capital.religion.addPressure(hereticReligion.name, 1_000_000)
        val spreader = prophet(civ, religion, capital.getCenterTile())
        assertTrue(automate(spreader))
        assertEquals(ReligionState.Religion, civ.religionManager.religionState)
        assertTrue("it spread", !ReligionAutomation.isFullStrength(spreader))
        spreader.destroy()

        // not enhanced, our holy city follows us: enhance
        capital.religion.addPressure(religion.name, 100_000_000)
        val enhancer = prophet(civ, religion, capital.getCenterTile())
        assertTrue(automate(enhancer))
        assertEquals(ReligionState.EnhancingReligion, civ.religionManager.religionState)
        enhancer.destroy()

        // enhanced: Holy Sites while we have no more than our religion flavor minus our prophets, else spread if there is a city to convert
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        setState(civ, ReligionState.EnhancedReligion)
        val full = prophet(civ, religion, tile(1, 1))
        val flavor = civ.ai.personality(Flavor.Religion)
        val sites = capital.getTiles().filter { it != capital.getCenterTile() }.toList()
        for (tile in sites.take(flavor - 1)) tile.improvement = "Holy site"
        assertEquals("$flavor - 1 prophet sites", false, automate(full))
        sites[flavor - 1].improvement = "Holy site"
        assertEquals("$flavor sites and a city to convert", true, automate(full))
        full.destroy()
        target.religion.addPressure(religion.name, 100_000_000)
        assertEquals("$flavor sites and nothing to convert", false, automate(prophet(civ, religion, tile(1, 1))))

        // a prophet that has spread keeps spreading
        assertTrue(automate(prophet(civ, religion, tile(1, 2), spreads = 1)))
    }

    @Test
    fun `with Vox Populi a Great Prophet takes the nearer of the two best cities when its score over turns + 3 is higher`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val hereticReligion = foundReligion(game.addCiv())
        val far = cityWith(civ, -4, 0, 12, hereticReligion.name to 5000)
        // 12 × 100 / 5 × 3 = 720 at 1 turn, 720 / (1 + 3) = 180; 10 × 100 / 5 × 3 = 600 at 0 turns, 600 / (0 + 3) = 200
        val near = cityWith(civ, 4, 0, 10, hereticReligion.name to 5000)
        val used = prophet(civ, religion, tile(4, 1), spreads = 1)
        assertEquals("the far city scores best", listOf(far, near), ReligionAutomation.prophetTargetsLikeVp(civ, used).map { it.first })
        assertTrue(ReligiousUnitAutomation.automateGreatProphet(GameView(game.gameInfo, civ).getMapUnitView(used)))
        assertEquals(near.getCenterTile(), used.getTile())
    }

    @Test
    fun `with Vox Populi a Great Prophet of a civilization without a religion founds one, or builds a Holy Site once none is left`() {
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val civ = game.addCiv()
        game.addCity(civ, tile(0, 0))
        leaveCapital(civ)
        val view = GameView(game.gameInfo, civ)
        val prophet = game.addUnit("Great Prophet", civ, tile(1, 1))
        assertTrue(ReligiousUnitAutomation.automateGreatProphet(view.getMapUnitView(prophet)))
        noMoreReligionsToFound()
        assertEquals(false, ReligiousUnitAutomation.automateGreatProphet(view.getMapUnitView(prophet)))
    }
    //endregion
}
