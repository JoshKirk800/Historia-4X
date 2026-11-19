package com.unciv.logic.automation.strategy

import com.unciv.json.json
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.League
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.EconomicStrategyDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvGrandStrategyAI, CvFlavorManager and strategy-loop ports against the DLL formulas (BNW XML values) */
@RunWith(GdxTestRunner::class)
class GrandStrategyAiTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
    }

    private fun def(name: String): GrandStrategyDef = game.ruleset.ai.getGrandStrategies().first { it.name == name }

    /** A major whose personality is all zero except what the test sets */
    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        return civ
    }

    @Test
    fun `base priority is the grand strategy flavors times the personality`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 3) // not a Culture grand strategy flavor
        // AIGrandStrategy_Flavors: CULTURE has FLAVOR_CULTURE 10
        assertEquals(70, GrandStrategyAi.baseGrandStrategyPriority(civ, def(GrandStrategyAi.CULTURE)))
        // CONQUEST has FLAVOR_OFFENSE 9
        assertEquals(27, GrandStrategyAi.baseGrandStrategyPriority(civ, def(GrandStrategyAi.CONQUEST)))
    }

    private fun conquest(civ: Civilization) = GrandStrategyAi.conquestPriority(civ, def(GrandStrategyAi.CONQUEST))

    /** A tile no city's territory touches */
    private fun farTile() = game.tileMap.values.first { it.getOwner() == null && it.neighbors.none { n -> n.getOwner() != null } }

    /**
     *  [count] majors with a city each, that nobody has met (founding a city within sight meets the neighbors, so that is undone): enough
     *  capitals around for no civilization to be close to world conquest
     */
    private fun bystanders(count: Int) = List(count) {
        val bystander = neutralCiv()
        game.addCity(bystander, farTile())
        for (other in game.gameInfo.civilizations) {
            other.diplomacy.remove(bystander.civID)
            bystander.diplomacy.remove(other.civID)
        }
        bystander
    }

    @Test
    fun `conquest priority scales boldness, the approach biases and meanness by the era from 10`() {
        val civ = neutralCiv()
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 8)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Meanness, 6)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.War, 7)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.Hostile, 4)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.Deceptive, 9)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.Friendly, 5)
        // GetConquestPriority: (boldness + max(deceptive, hostile, war) - friendly + meanness) * (10 - era), the era at least 1
        assertEquals(0, civ.getAiEraNumber())
        assertEquals((8 + 9 - 5 + 6) * 9, conquest(civ))
        civ.tech.era = game.ruleset.eras["Medieval era"]!!
        assertEquals((8 + 9 - 5 + 6) * (10 - civ.getAiEraNumber()), conquest(civ))
    }

    @Test
    fun `being close to world conquest adds 9000`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        assertTrue(VictoryProgressAi.isCloseToWorldConquest(civ, game.ruleset.ai))
        assertEquals(9000, conquest(civ))
        bystanders(2)
        assertEquals(0, conquest(civ))
    }

    @Test
    fun `a conquered major adds 50 and each of several 125, also when its capital is not held`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        bystanders(4)
        val first = neutralCiv()
        val firstCapital = game.addCity(first, farTile())
        val second = neutralCiv()
        val secondCapital = game.addCity(second, farTile())
        assertEquals(0, conquest(civ))

        CityConquestFunctions(firstCapital).puppetCity(civ)
        assertEquals("the original capital of a major we took", 50, conquest(civ))
        CityConquestFunctions(secondCapital).puppetCity(civ)
        assertEquals(2 * 125, conquest(civ))
    }

    @Test
    fun `a major killed without taking its capital counts as conquered`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        bystanders(3)
        val victim = neutralCiv()
        val city = game.addCity(victim, farTile())
        city.isOriginalCapital = false // the capital was lost long ago: only the kill is left
        victim.hasEverOwnedOriginalCapital = true
        assertEquals(0, conquest(civ))
        CityConquestFunctions(city).puppetCity(civ)
        assertTrue(victim.isDefeated())
        assertEquals(50, conquest(civ))
        // somebody else's kill is not ours
        assertEquals(0, conquest(bystanders(1).first()))
    }

    @Test
    fun `the world military average shrinks by a tenth for each major conquered`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        val others = bystanders(4)
        civ.diplomacyFunctions.makeCivilizationsMeet(others[0])
        for (i in 1..3) game.addUnit("Warrior", civ, game.getTile(i, -1))
        for (other in others) // the rest of the world has more might than we have: not close to world conquest
            for (tile in other.cities.first().getCenterTile().neighbors.take(4)) game.addUnit("Warrior", other, tile)
        val victim = neutralCiv()
        val capital = game.addCity(victim, farTile())
        CityConquestFunctions(capital).puppetCity(civ)
        game.gameInfo.turns = 60 // AI_GS_CONQUEST_MILITARY_STRENGTH_FIRST_TURN
        val might = civ.ai.militaryMight()
        val average = GrandStrategyAi.worldMilitaryStrengthAverage(civ) * 100 / 110
        assertTrue(might > 0 && average > 0)
        var ratio = (might - average) * 100 / average
        if (ratio > 0) ratio /= 2
        assertEquals(50 + ratio, conquest(civ))
    }

    @Test
    fun `more met land per player than ours adds the cramped weight, five times it when cramped`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        val rich = neutralCiv()
        val richCity = game.addCity(rich, farTile())
        bystanders(1)
        civ.diplomacyFunctions.makeCivilizationsMeet(rich)
        assertEquals(0, conquest(civ))
        for (tile in game.tileMap.values.filter { it.getOwner() == null }.take(30)) tile.setOwningCity(richCity)
        // AI_GRAND_STRATEGY_CONQUEST_CRAMPED_WEIGHT 20
        assertEquals(20, conquest(civ))
        civ.ai.isCramped = true
        assertEquals(100, conquest(civ))
    }

    @Test
    fun `the flavors of policies and buildings add an eighth`() {
        val civ = neutralCiv()
        val city = game.addCity(civ, game.getTile(0, 0))
        bystanders(2)
        // Discipline: Offense 10, Defense 5 (not counted), Mobile 5; Barracks: Military Training 20; Statue of Zeus: Offense 25 counted twice
        civ.policies.getAdoptedPolicies().add("Discipline")
        city.cityConstructions.addBuilding("Barracks")
        city.cityConstructions.addBuilding("Statue of Zeus")
        assertEquals((10 + 5 + 20 + 2 * 25) / 8, conquest(civ))
    }

    @Test
    fun `a warmonger leader adds 100 and a smaller one takes 100`() {
        for ((category, amount) in listOf("Warmonger" to 100, "Smaller" to -100, "Expansionist" to 50)) {
            val personality = Personality().apply { name = "Test $category"; traitCategories = linkedMapOf(category to 1) }
            game.ruleset.personalities[personality.name] = personality
            val nation = Nation().apply { name = "Nation $category"; this.personality = personality.name; cities = arrayListOf("The Capital") }
            game.ruleset.nations[nation.name] = nation
            val civ = game.addCiv(nation)
            for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
            for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
            for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
            assertEquals(category, amount, conquest(civ))
        }
    }

    @Test
    fun `an ideology and another civilization close to a victory add the danger times the era`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        val rival = neutralCiv()
        val rivalCity = game.addCity(rival, farTile())
        bystanders(1)
        civ.diplomacyFunctions.makeCivilizationsMeet(rival)
        rival.tech.techsResearched.addAll(game.ruleset.technologies.keys)
        rivalCity.cityConstructions.addBuilding("Apollo Program")
        assertTrue(VictoryProgressAi.isCloseToSpaceshipVictory(rival, game.ruleset.ai))
        assertEquals("no ideology of ours: the rival's progress is not counted", 0, conquest(civ))
        civ.policies.getAdoptedPolicies().add("Freedom")
        assertTrue(civ.policies.getIdeology() != null)
        // desperate (we are close to nothing and the rival is): 25, times the era (at least 1)
        assertEquals(25, conquest(civ))
    }

    @Test
    fun `a met major that is dead still counts as met`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0))
        bystanders(2) // so that holding the only met capital is not close to world conquest
        game.gameInfo.turns = 30
        // AI_GRAND_STRATEGY_CONQUEST_NOBODY_MET_WEIGHT -50 from turn AI_GS_CONQUEST_NOBODY_MET_FIRST_TURN 20 when no major has been met
        assertEquals(-50, GrandStrategyAi.conquestPriority(civ, def(GrandStrategyAi.CONQUEST)))
        val other = neutralCiv()
        val otherCity = game.addCity(other, game.getTile(3, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        assertEquals(0, GrandStrategyAi.conquestPriority(civ, def(GrandStrategyAi.CONQUEST)))
        // the original loops over the teams: the dead one has still been met
        other.cities = listOf()
        for (tile in otherCity.getTiles().toList()) tile.setOwningCity(null) // a dead civilization owns no land
        other.hasEverOwnedOriginalCapital = true
        assertTrue(other.isDefeated())
        assertEquals(0, GrandStrategyAi.conquestPriority(civ, def(GrandStrategyAi.CONQUEST)))
    }

    @Test
    fun `culture priority adds the culture-ahead weight over the flavor term`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        val other1 = game.addCiv()
        val other2 = game.addCiv()
        for (c in listOf(civ, other1, other2)) game.addCity(c, game.tileMap.values.first { it.getCity() == null && it.neighbors.none { n -> n.getCity() != null } }, replacePalace = true)
        civ.stats.statsForNextTurn = Stats(culture = 10f)
        other1.stats.statsForNextTurn = Stats(culture = 3f)
        other2.stats.statsForNextTurn = Stats(culture = 3f)
        // era (at least 1) * flavor * 150 / 100 = 7; ahead of both of 2: AI_GS_CULTURE_AHEAD_WEIGHT 50 * 2 / 2; tourism all equal, so behind both:
        // -AI_GS_CULTURE_TOURISM_AHEAD_WEIGHT 100 * 2 / 2; the bonus: one effective city, -10 / 8
        assertEquals(7 + 50 - 100 - 1, GrandStrategyAi.culturePriority(civ, def(GrandStrategyAi.CULTURE)))
        other2.stats.statsForNextTurn = Stats(culture = 30f)
        // ahead of 1, behind 1: no longer ahead of more than behind
        assertEquals(7 - 100 - 1, GrandStrategyAi.culturePriority(civ, def(GrandStrategyAi.CULTURE)))
    }

    @Test
    fun `culture priority adds an eighth of the stored culture, the tourism, the cities and the flavors of policies and buildings`() {
        val civ = neutralCiv()
        val city = game.addCity(civ, game.getTile(0, 0), replacePalace = true) // the Palace has flavors of its own
        bystanders(1)
        civ.policies.storedCulture = 9000 // times 100 / 3000
        // Military Caste: Culture 10, Happiness 10; Amphitheater: Culture and more
        civ.policies.getAdoptedPolicies().add("Military Caste")
        val flavors = game.ruleset.ai.flavorsOf(game.ruleset.buildings["Amphitheater"]!!)
        city.cityConstructions.addBuilding("Amphitheater")
        val buildingSum = flavors[Flavor.GreatPeople.ordinal] + flavors[Flavor.Culture.ordinal] + flavors[Flavor.Wonder.ordinal] + flavors[Flavor.Happiness.ordinal]
        assertTrue(buildingSum > 0)
        // somebody else is alive, so we are not influential over everybody: not close to a culture victory; culture and tourism behind: -100
        assertEquals(-100 + (300 + 20 + buildingSum - 10) / 8, GrandStrategyAi.culturePriority(civ, def(GrandStrategyAi.CULTURE)))
    }

    @Test
    fun `the trait categories of the leader move the three peaceful priorities`() {
        for ((category, amounts) in listOf("Warmonger" to listOf(-50, -100, -25), "Diplomat" to listOf(25, 100, 25), "Nerd" to listOf(25, 25, 100))) {
            val personality = Personality().apply { name = "Test $category"; traitCategories = linkedMapOf(category to 1) }
            game.ruleset.personalities[personality.name] = personality
            val nation = Nation().apply { name = "Nation $category"; this.personality = personality.name; cities = arrayListOf("The Capital") }
            game.ruleset.nations[nation.name] = nation
            val civ = game.addCiv(nation)
            for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
            // culture: no city, so one effective city is counted as -10 / 8 = -1
            assertEquals(category, amounts[0] - 1, GrandStrategyAi.culturePriority(civ, def(GrandStrategyAi.CULTURE)))
            assertEquals(category, amounts[1], GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
            assertEquals(category, amounts[2], GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
        }
    }

    @Test
    fun `united nations priority is multiplied by the share of the delegates a World Leader vote needs`() {
        val civ = neutralCiv()
        game.addCity(civ, game.getTile(0, 0), replacePalace = true)
        val rival = neutralCiv()
        game.addCity(rival, game.getTile(3, 0), replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(rival)
        civ.tech.addTechnology("Printing Press")
        val league = League.tryFound(game.gameInfo)!!
        game.gameInfo.league = league
        civ.stats.statsForNextTurn = Stats(gold = 2500f) // a twenty-fifth, a tenth: 10
        val needed = league.getVotesNeededForWorldLeader()
        val member = league.members.getValue(civ.civID)
        for ((target, multiplier) in listOf(needed to 15, needed * 3 / 4 to 10, needed * 2 / 4 to 5)) {
            member.extraDelegates = 0
            member.extraDelegates = target - league.getDelegates(civ)
            val delegates = league.getDelegates(civ)
            val lead = delegates - league.getDelegates(rival)
            assertTrue("$delegates delegates of $needed", delegates >= target && lead > 0)
            val base = 10 + if (VictoryProgressAi.isCloseToDiploVictory(civ, game.ruleset.ai)) 7000 else 0 // AI.json CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD
            assertEquals("$delegates of $needed", base * multiplier + maxOf(100, lead * 20), GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
        }
        // fewer than half of the delegates needed: not multiplied
        member.extraDelegates = 0
        val base = league.getDelegates(civ)
        assertTrue(base < needed * 2 / 4)
        val lead = base - league.getDelegates(rival)
        assertTrue(lead > 0)
        assertEquals(10 + maxOf(100, lead * 20), GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
    }

    @Test
    fun `united nations priority before the congress weights the diplomacy flavor`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 6)
        // (10 - era) * flavor * 125 / 100
        assertEquals(75, GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
        game.gameInfo.gameParameters.victoryTypes.remove("Diplomatic")
        assertEquals(-100, GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
    }

    @Test
    fun `spaceship priority gets the Apollo bonus`() {
        val apollo = game.addCiv("Enables construction of Spaceship parts")
        for (flavor in Flavor.entries) apollo.ai.setPersonalityFlavor(flavor, 0)
        assertEquals(150, GrandStrategyAi.spaceshipPriority(apollo, def(GrandStrategyAi.SPACESHIP)))
        assertEquals(0, GrandStrategyAi.spaceshipPriority(neutralCiv(), def(GrandStrategyAi.SPACESHIP)))
    }

    @Test
    fun `the era the original's AI reads ends with its last era`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Science, 10)
        civ.tech.era = game.ruleset.eras["Information era"]!!
        // XML eras: ... Postmodern 6, Future 7; Unciv's Information era (7) and Future era (8) are both its Future
        assertEquals(7, civ.getEraNumber())
        assertEquals(1470, GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
        civ.tech.era = game.ruleset.eras["Future era"]!!
        assertEquals(8, civ.getEraNumber())
        // era squared * flavor * 300 / 100 with era 7
        assertEquals(1470, GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
    }

    @Test
    fun `the Apollo bonus stays after the program building is lost`() {
        val civ = neutralCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        val city = game.addCity(civ, game.getTile(0, 0), replacePalace = true)
        val apollo = game.createBuilding("Enables construction of Spaceship parts")
        city.cityConstructions.addBuilding(apollo)
        assertEquals(150, GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
        // the original counts the project built, not the building standing
        city.cityConstructions.removeBuilding(apollo)
        assertFalse(civ.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts))
        assertEquals(150, GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
    }

    @Test
    fun `spaceship priority adds the lead in techs and the bump for being first, and a tenth of science`() {
        val civ = neutralCiv()
        val behind = neutralCiv()
        val other = neutralCiv()
        for (c in listOf(civ, behind, other)) game.addCity(c, game.tileMap.values.first { it.getCity() == null && it.neighbors.none { n -> n.getCity() != null } }, replacePalace = true)
        for (tech in game.ruleset.technologies.values.take(3)) civ.tech.addTechnology(tech.name)
        assertTrue(civ.tech.techsResearched.size > behind.tech.techsResearched.size)
        civ.stats.statsForNextTurn = Stats(science = 500f) // a twenty-fifth, a tenth: 2
        // ahead of both: AI_GS_CULTURE_AHEAD_WEIGHT 50 * 2 / 2, first overall: 50 * 2
        assertEquals(50 + 100 + 2, GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
        for (tech in game.ruleset.technologies.values.take(6)) other.tech.addTechnology(tech.name)
        // ahead of one, behind one: neither
        assertEquals(2, GrandStrategyAi.spaceshipPriority(civ, def(GrandStrategyAi.SPACESHIP)))
    }

    @Test
    fun `priorities are reduced by the share of met players guessed to pursue the same strategy`() {
        val civ = neutralCiv()
        val a = game.addCiv()
        val b = game.addCiv()
        // only living majors count as met players
        game.addCity(civ, game.tileMap[0, 0])
        game.addCity(a, game.tileMap[3, 0])
        game.addCity(b, game.tileMap[0, 3])
        civ.diplomacyFunctions.makeCivilizationsMeet(a)
        civ.diplomacyFunctions.makeCivilizationsMeet(b)
        civ.ai.grandStrategyGuesses[a.civID] = GrandStrategyGuess(GrandStrategyAi.CULTURE, GuessConfidence.Likely)
        civ.ai.grandStrategyGuesses[b.civID] = GrandStrategyGuess(GrandStrategyAi.CULTURE, GuessConfidence.Unsure)
        val defs = game.ruleset.ai.getGrandStrategies()
        for (d in defs) civ.ai.grandStrategyPriorities[d.name] = 200
        GrandStrategyAi.applyCrowding(civ, defs)
        // 200 * 50 * 2 guessed / 3 met (us included) / 100 = 66
        assertEquals(134, civ.ai.grandStrategyPriority(GrandStrategyAi.CULTURE))
        assertEquals(200, civ.ai.grandStrategyPriority(GrandStrategyAi.CONQUEST))
    }

    @Test
    fun `an active grand strategy is kept for 10 turns before it can change`() {
        val civ = neutralCiv()
        game.addCity(civ, game.tileMap[0, 0])
        bystanders(2) // holding the only capital would be close to world conquest
        civ.ai.setPersonalityFlavor(Flavor.Culture, 20) // culture: 200 base + 400 flavor term, far above anything else
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        civ.ai.turnsSinceGrandStrategySet = 8
        GrandStrategyAi.doTurn(civ)
        assertEquals(GrandStrategyAi.CONQUEST, civ.ai.grandStrategy)
        assertEquals(9, civ.ai.turnsSinceGrandStrategySet)
        GrandStrategyAi.doTurn(civ)
        assertEquals(GrandStrategyAi.CULTURE, civ.ai.grandStrategy)
        assertEquals(0, civ.ai.turnsSinceGrandStrategySet)
    }

    @Test
    fun `guess confidence follows the positive and likely thresholds`() {
        val civ = neutralCiv()
        assertEquals(GuessConfidence.Positive, GrandStrategyAi.guessConfidence(civ, 120))
        assertEquals(GuessConfidence.Likely, GrandStrategyAi.guessConfidence(civ, 119))
        assertEquals(GuessConfidence.Likely, GrandStrategyAi.guessConfidence(civ, 70))
        assertEquals(GuessConfidence.Unsure, GrandStrategyAi.guessConfidence(civ, 69))
    }

    @Test
    fun `randomized personality stays within 2 of the leader, keeps zeros, and survives save and load`() {
        val nation = game.ruleset.nations.values.first { it.isMajorCiv && it.personality != null }
        val leader = game.ruleset.personalities[nation.personality]!!
        val civ = game.addCiv(nation)
        for (flavor in Flavor.entries) {
            val original = leader.flavors[flavor.name] ?: continue
            if (flavor == Flavor.Expansion || flavor == Flavor.Growth) continue // also adjusted for the map
            val value = civ.ai.personality(flavor)
            if (original == 0) assertEquals(0, value)
            else assertTrue("$flavor $original -> $value", value in maxOf(0, original - 2)..minOf(20, original + 2))
        }
        val reloaded = json().fromJson(CivAi::class.java, json().toJson(civ.ai))
        reloaded.setTransients(civ)
        assertTrue(civ.ai.personalityFlavorVector().contentEquals(reloaded.personalityFlavorVector()))
        assertEquals(civ.ai.diplomacyValue(DiplomacyPersonality.Boldness), reloaded.diplomacyValue(DiplomacyPersonality.Boldness))
    }

    @Test
    fun `strategies are only dropped on check turns after the minimum, and their flavors follow`() {
        val civ = neutralCiv()
        val city = game.addCity(civ, game.tileMap[0, 0])
        var shouldBeActive = true
        StrategyTests.register("TEST_STRATEGY_HYSTERESIS") { _, _ -> shouldBeActive }
        game.ruleset.ai.economicStrategies.clear() // only the test strategy, so no grand-strategy flavors mix in
        game.ruleset.ai.economicStrategies.add(EconomicStrategyDef().apply {
            name = "TEST_STRATEGY_HYSTERESIS"
            checkTriggerTurnCount = 3
            minimumNumTurnsExecuted = 5
            playerFlavors = linkedMapOf("Gold" to 10)
            cityFlavors = linkedMapOf("Gold" to 100)
        })
        fun turn(n: Int) { game.gameInfo.turns = n; civ.ai.doTurn() }

        turn(0)
        assertTrue(civ.ai.isEconomicStrategyActive("TEST_STRATEGY_HYSTERESIS"))
        assertEquals(10, civ.ai.flavor(Flavor.Gold))
        assertEquals(100, city.ai.flavor(Flavor.Gold))
        shouldBeActive = false
        for (n in 1..5) {
            turn(n) // 3 is a check turn but before the minimum of 5; 4 and 5 are no check turns
            assertTrue("turn $n", civ.ai.isEconomicStrategyActive("TEST_STRATEGY_HYSTERESIS"))
        }
        turn(6)
        assertTrue(!civ.ai.isEconomicStrategyActive("TEST_STRATEGY_HYSTERESIS"))
        assertEquals(0, civ.ai.flavor(Flavor.Gold))
        assertEquals(0, city.ai.flavor(Flavor.Gold))
    }

    @Test
    fun `flavors of an object without a table entry are derived from its stats`() {
        val building = game.createBuilding()
        building.culture = 2f
        building.cityStrength = 5.0
        val flavors = game.ruleset.ai.flavorsOf(building)
        assertEquals(20, flavors[Flavor.Culture.ordinal])
        assertEquals(20, flavors[Flavor.CityDefense.ordinal])
        assertEquals(0, flavors[Flavor.Science.ordinal])
    }

    @Test
    fun `a major is remembered as a conqueror when it takes a last city, not when the civilization dies`() {
        val conqueror = neutralCiv()
        val observer = neutralCiv()
        val victim = neutralCiv()
        game.addCity(conqueror, game.getTile(0, 0))
        game.addCity(observer, game.getTile(0, 3))
        observer.diplomacyFunctions.makeCivilizationsMeet(conqueror)
        val first = game.addCity(victim, game.getTile(2, 0))
        val last = game.addCity(victim, game.getTile(-2, 0))
        // a unit and no original capital: Unciv still counts it as alive, the original has "killed" it (CvPlayer::acquireCity: no city left)
        victim.hasEverOwnedOriginalCapital = false
        game.addUnit("Warrior", victim, game.getTile(0, -3))

        CityConquestFunctions(first).puppetCity(conqueror)
        assertEquals("the victim still has a city", 0, observer.ai.aggressionOf(conqueror).majorsConquered)
        CityConquestFunctions(last).puppetCity(conqueror)
        assertFalse(victim.isDefeated())
        assertEquals(1, observer.ai.aggressionOf(conqueror).majorsConquered)
    }

    @Test
    fun `generated AI data loads for the three base rulesets without validation problems`() {
        for (base in listOf(BaseRuleset.Civ_V_Vanilla, BaseRuleset.Civ_V_GnK, BaseRuleset.Civ_V_BNW)) {
            val ruleset = RulesetCache[base.fullName]!!
            assertTrue(base.fullName, ruleset.ai.economicStrategies.isNotEmpty() && ruleset.ai.grandStrategies.size == 4)
            val problems = ruleset.getErrorList().filter { "AI.json" in it.text || it.text.startsWith("Personality ") }
            assertTrue(problems.joinToString { it.text }, problems.isEmpty())
            // every major has leader flavors through its personality
            for (nation in ruleset.nations.values.filter { it.isMajorCiv })
                assertTrue(nation.name, ruleset.personalities[nation.personality]?.flavors?.isNotEmpty() == true)
        }
    }
}
