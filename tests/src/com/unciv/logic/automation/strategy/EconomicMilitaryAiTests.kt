package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.automation.civilization.CityConquestAutomation
import com.unciv.logic.automation.civilization.CityConquestAutomation.ConquestChoice
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.PactPlanningAi
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.PlayerStrategyDef
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvEconomicAI, CvMilitaryAI and CvPlayerAI ports against the DLL formulas (BNW XML values, Prince = handicap 3) */
@RunWith(GdxTestRunner::class)
class EconomicMilitaryAiTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6, Constants.grassland)
    }

    /** A major whose personality is all zero except what the test sets, with no victory pursuit */
    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        return civ
    }

    private fun economicDef(name: String): PlayerStrategyDef = game.ruleset.ai.economicStrategies.first { it.name == name }
    private fun militaryDef(name: String): PlayerStrategyDef = game.ruleset.ai.militaryStrategies.first { it.name == name }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    //region Savings and gold

    @Test
    fun `higher priority savings reserve gold from lower priority purchases`() {
        val civ = neutralCiv()
        civ.addGold(350)
        val savings = civ.ai.purchaseSavings
        savings.startSaveForPurchase(PurchaseType.UnitUpgrade, 300, 400)
        // The tile at priority 275 only sees 350 - 300 = 50
        assertFalse(savings.canWithdrawMoneyForPurchase(civ, PurchaseType.Tile, 100, 275))
        // Raising the tile's priority above the upgrade frees the whole treasury
        assertTrue(savings.canWithdrawMoneyForPurchase(civ, PurchaseType.Tile, 100, 500))
        // A city-state gift (priority 0) comes after both: the tile request reserves 0, the upgrade 300
        assertEquals(50, savings.amountAvailableForPurchase(civ, PurchaseType.MinorCivGift))
        // Reaching 0 before the purchase's turn means nothing is available, even for a free purchase
        civ.addGold(-50)
        assertEquals(0, savings.amountAvailableForPurchase(civ, PurchaseType.MinorCivGift))
        assertFalse(savings.canWithdrawMoneyForPurchase(civ, PurchaseType.MinorCivGift, 0))
    }

    private fun spearmenReadyToUpgrade(civ: Civilization, count: Int): City {
        val city = game.addCity(civ, tile(0, 0))
        civ.tech.addTechnology("Bronze Working")
        civ.tech.addTechnology("Civil Service")
        val tiles = listOf(tile(0, 0)) + tile(0, 0).neighbors.toList()
        for (i in 0 until count) game.addUnit("Spearman", civ, tiles[i])
        return city
    }

    @Test
    fun `upgrades are capped at a third of the military training flavor`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 6)
        spearmenReadyToUpgrade(civ, 4)
        civ.addGold(10000)
        // max(1, 6 / 3) + max(0, Prince 3 - 5) = 2
        assertEquals(2, UseGoldAutomation.maxUpgradesPerTurn(civ))
        assertEquals(2, UseGoldAutomation.plotUpgradeMoves(civ).upgraded)
        assertEquals(2, civ.units.getCivUnits().count { it.name == "Pikeman" })
        assertEquals(2, civ.units.getCivUnits().count { it.name == "Spearman" })
    }

    @Test
    fun `without gold for an upgrade the AI saves for it at the military training priority`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 6)
        spearmenReadyToUpgrade(civ, 1)
        assertEquals(0, UseGoldAutomation.plotUpgradeMoves(civ).upgraded)
        val request = civ.ai.purchaseSavings.requests[PurchaseType.UnitUpgrade.name]!!
        // AI_GOLD_PRIORITY_UPGRADE_BASE 150 + AI_GOLD_PRIORITY_UPGRADE_PER_FLAVOR_POINT 25 x current MILITARY_TRAINING 6
        assertEquals(300, request.priority)
        assertTrue(request.amount > 0)
    }

    @Test
    fun `the plot score needed drops as the treasury approaches the halving balance`() {
        val civ = neutralCiv()
        civ.addGold(50)
        // no purchase unless the gold exceeds the cost
        assertNull(UseGoldAutomation.minimumPlotBuyScore(civ, 50))
        civ.addGold(475)
        // discount 50 x (525 - 50) / (1000 - 50) = 25%: 275 - 68
        assertEquals(207, UseGoldAutomation.minimumPlotBuyScore(civ, 50))
        // a cost at or above AI_GOLD_BALANCE_TO_HALVE_PLOT_BUY_MINIMUM: no purchase
        civ.addGold(1000)
        assertNull(UseGoldAutomation.minimumPlotBuyScore(civ, 1000))
    }

    @Test
    fun `plot score values a luxury the civilization lacks twice`() {
        // Under the Civ V price of a tile a resource lowers it, which scales the score; Unciv's own price depends on the distance only
        game.ruleset.modOptions.constants.plotAcquisitionLikeCivV = false
        val civ = neutralCiv()
        val city = game.addCity(civ, tile(0, 0))
        val plain = tile(2, 0)
        val withLuxury = tile(-2, 0)
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury } // no yields of its own
        withLuxury.setTileResource(luxury.name)
        val difference = UseGoldAutomation.individualPlotScore(city, withLuxury) - UseGoldAutomation.individualPlotScore(city, plain)
        // AI_PLOT_VALUE_LUXURY_RESOURCE 40, doubled when the civilization has none of it; same distance, so same cost
        assertEquals(80, difference)
    }
    //endregion

    //region Military sizing

    /** Gives [city] a building worth [gold] gold a turn and refreshes its stats */
    private fun addGoldIncome(city: City, gold: Float) {
        city.cityConstructions.addBuilding(game.createBuilding().apply { this.gold = gold })
        city.cityStats.update()
    }

    @Test
    fun `the soft supply cap is the supply when the income pays for all units and two per city when it pays for none`() {
        val civ = neutralCiv()
        val capital = game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(4, 0))
        addGoldIncome(capital, 1000f)
        val supply = civ.stats.getUnitSupply()
        assertTrue("supply $supply", supply > 5)
        assertEquals(supply, EconomicAi.softSupplyCap(civ))
        // a Worker uses no supply (CvUnit::IsMilitarySupport is false), so the cap stays; a Caravan uses it but is no combat unit: one less
        game.addUnit("Worker", civ, tile(1, 1))
        assertEquals(supply, EconomicAi.softSupplyCap(civ))
        game.addUnit("Caravan", civ, tile(1, 2))
        assertEquals(supply - 1, EconomicAi.softSupplyCap(civ))
        // an income that pays for no unit: the floor of 2 per city
        addGoldIncome(capital, -2000f)
        assertEquals(4, EconomicAi.softSupplyCap(civ))
    }

    @Test
    fun `the affordable units are split by the land and naval defense weights after the explorers`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Naval, 10)
        for (t in game.tileMap.values) if (t.position.x >= 1) game.setTileTerrain(t.position, Constants.coast)
        for (t in game.tileMap.values) t.setTerrainTransients()
        val capital = game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(0, -3))
        addGoldIncome(capital, 1000f)
        assertEquals(2, PactPlanningAi.numEffectiveCoastalCities(civ))
        val military = civ.ai.military
        military.updateBaseData(civ)
        val cap = EconomicAi.softSupplyCap(civ)
        // land 10 x (3 + 2 cities) = 50, naval 7 x 2 coastal cities = 14, both x 100, both rounded
        val sizes = "cap $cap, land ${military.recommendedLandArmySize}, navy ${military.recommendedNavySize}"
        assertEquals(sizes, (cap * 50 / 64f + 0.5f).toInt(), military.recommendedLandArmySize)
        assertEquals(sizes, (cap * 14 / 64f + 0.5f).toInt(), military.recommendedNavySize)
        // a scout's share comes off the top
        game.addUnit("Scout", civ, tile(-1, 0))
        military.updateBaseData(civ)
        assertEquals(sizes, ((cap - 1) * 50 / 64f + 0.5f).toInt(), military.recommendedLandArmySize)
    }

    @Test
    fun `land units are counted by role, explorers included, and the land defense state follows the recommended army`() {
        val civ = neutralCiv()
        addGoldIncome(game.addCity(civ, tile(0, 0)), 1000f)
        val free = game.tileMap.values.filter { !it.isCityCenter() }.iterator()
        game.addUnit("Archer", civ, free.next())
        game.addUnit("Horseman", civ, free.next())
        val warrior = game.addUnit("Warrior", civ, free.next())
        UnitAiTypes.set(warrior, UnitAiType.EXPLORE)
        val military = civ.ai.military
        military.doTurnBeforeStrategies(civ)
        assertEquals(1, military.numRangedLandUnits)
        assertEquals(1, military.numMobileLandUnits)
        assertEquals(1, military.numMeleeLandUnits)
        // landlocked: every affordable unit belongs to the land army, which 3 units are below
        val recommended = military.recommendedLandArmySize
        assertEquals(EconomicAi.softSupplyCap(civ), recommended)
        assertTrue("recommended $recommended", recommended > 4)
        assertEquals(DefenseState.Critical, military.landDefenseState)
        // no navy wanted, and no ship is at most half of none
        assertEquals(0, military.recommendedNavySize)
        assertEquals(DefenseState.Critical, military.navalDefenseState)
        repeat(recommended - 4) { game.addUnit("Warrior", civ, free.next()) }
        military.doTurnBeforeStrategies(civ)
        assertEquals(DefenseState.Critical, military.landDefenseState)
        // the recommended army, below 125% of it
        game.addUnit("Warrior", civ, free.next())
        military.doTurnBeforeStrategies(civ)
        assertEquals(recommended, military.numLandUnits)
        assertEquals(DefenseState.Neutral, military.landDefenseState)
    }

    @Test
    fun `unit mix strategies compare the share of a role with the flavor`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Ranged, 6)
        // 1 x 10 / 5 = 2 <= 6 / 2: need ranged
        assertTrue(EconomicMilitaryStrategyTests.needUnits(civ, Flavor.Ranged, 1, 4))
        // 2 x 10 / 5 = 4 > 3
        assertFalse(EconomicMilitaryStrategyTests.needUnits(civ, Flavor.Ranged, 2, 3))
        // enough at a share of 6 tenths, not at 5
        assertTrue(EconomicMilitaryStrategyTests.enoughUnits(civ, Flavor.Ranged, 6, 4))
        assertFalse(EconomicMilitaryStrategyTests.enoughUnits(civ, Flavor.Ranged, 5, 5))
        // the grand strategy's flavor mod counts (GetPersonalityAndGrandStrategy)
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        val conquestRanged = civ.ai.grandStrategyDef()!!.flavorModVector[Flavor.Ranged.ordinal]
        assertEquals(6 + conquestRanged, civ.ai.personalityAndGrandStrategy(Flavor.Ranged))
    }
    //endregion

    //region Strategy tests

    @Test
    fun `war mobilization weighs conquest, war preparations and hostile approaches`() {
        val civ = neutralCiv()
        val preparedAgainst = game.addCiv()
        val guarded = game.addCiv()
        val hostile = game.addCiv()
        civ.ai.diplomacy.memoryOf(preparedAgainst).warGoal = WarGoal.Prepare
        civ.ai.diplomacy.memoryOf(preparedAgainst).approach = MajorCivApproach.Hostile // ignored: preparing counts instead
        civ.ai.diplomacy.memoryOf(guarded).approach = MajorCivApproach.Guarded
        civ.ai.diplomacy.memoryOf(hostile).approach = MajorCivApproach.Afraid
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertEquals(25 + 100 + 25 + 50, EconomicMilitaryStrategyTests.warMobilizationWeight(civ))
    }

    @Test
    fun `an army being built for a sneak or city-state attack turns war mobilization on for good`() {
        val civ = neutralCiv()
        game.addCiv()
        val threshold = militaryDef(EconomicMilitaryStrategyTests.WAR_MOBILIZATION).weightThreshold
        assertTrue(EconomicMilitaryStrategyTests.warMobilizationWeight(civ) < threshold)
        civ.ai.military.requestArmy(bySea = false)
        assertTrue(EconomicMilitaryStrategyTests.warMobilizationWeight(civ) >= threshold)
        civ.ai.military.numLandAttacksRequested = 0
        assertTrue("the original never clears the army type", EconomicMilitaryStrategyTests.warMobilizationWeight(civ) >= threshold)
    }

    @Test
    fun `barbarian camps near our cities make eradication worthwhile unless a war is being prepared`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        val other = game.addCiv()
        game.addCity(civ, tile(0, 0))
        val camp = tile(3, 0)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        camp.setExplored(civ, true)
        assertTrue(camp.isBarbarianEncampment())
        val military = civ.ai.military
        military.scanForBarbarians(civ)
        // a camp within 10 tiles of a city counts 5
        assertEquals(5, military.barbarianCampCount)
        val def = militaryDef("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")
        // 5 x 50 = 250 >= 200 + (-2 x 5 + 2 x 5)
        assertTrue(EconomicMilitaryStrategyTests.eradicateBarbarians(civ, def))
        civ.ai.diplomacy.memoryOf(other).warGoal = WarGoal.Prepare
        assertFalse(EconomicMilitaryStrategyTests.eradicateBarbarians(civ, def))
    }

    @Test
    fun `need happiness uses half the happiness personality as margin, critical a fixed -3`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 8)
        game.addCity(civ, tile(0, 0), initialPopulation = 3).cityStats.update()
        val need = economicDef("ECONOMICAISTRATEGY_NEED_HAPPINESS")
        val critical = economicDef("ECONOMICAISTRATEGY_NEED_HAPPINESS_CRITICAL")
        civ.stats.happiness = 4
        assertTrue(EconomicMilitaryStrategyTests.needHappiness(civ, need))   // 4 <= 8 x 1 / 2
        civ.stats.happiness = 5
        assertFalse(EconomicMilitaryStrategyTests.needHappiness(civ, need))
        assertFalse(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
        civ.stats.happiness = -3
        assertTrue(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
        civ.stats.happiness = -2
        assertFalse(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
    }

    @Test
    fun `need happiness needs some unhappiness as well as population`() {
        val civ = neutralCiv()
        val city = game.addCity(civ, tile(0, 0), initialPopulation = 3)
        val need = economicDef("ECONOMICAISTRATEGY_NEED_HAPPINESS")
        val critical = economicDef("ECONOMICAISTRATEGY_NEED_HAPPINESS_CRITICAL")
        civ.stats.happiness = -5
        city.cityStats.happinessList = linkedMapOf("Cities" to 0f, "Population" to 0f)
        assertFalse(EconomicMilitaryStrategyTests.needHappiness(civ, need))
        assertFalse(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
        city.cityStats.happinessList = linkedMapOf("Cities" to -0.5f, "Population" to -0.4f)
        assertFalse("less than 1 unhappiness", EconomicMilitaryStrategyTests.needHappiness(civ, need))
        city.cityStats.happinessList = linkedMapOf("Cities" to -3f, "Population" to -3f)
        assertTrue(EconomicMilitaryStrategyTests.needHappiness(civ, need))
        assertTrue(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
    }

    @Test
    fun `expand like crazy follows the expansion flavor but not a culture victory on maps where cities cost policies`() {
        val civ = neutralCiv()
        val def = economicDef("ECONOMICAISTRATEGY_EXPAND_LIKE_CRAZY")
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 9)
        assertFalse(EconomicMilitaryStrategyTests.expandLikeCrazy(civ, def))
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 10)
        assertTrue(EconomicMilitaryStrategyTests.expandLikeCrazy(civ, def))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        // 20 - 10 (the culture grand strategy's EXPANSION mod) would pass the threshold, but cities raise policy costs on this map size
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 20)
        assertTrue(civ.ai.personalityAndGrandStrategy(Flavor.Expansion) >= def.weightThreshold)
        assertFalse(EconomicMilitaryStrategyTests.expandLikeCrazy(civ, def))
    }

    @Test
    fun `early expansion wants cities by the expansion to growth ratio scaled by map size`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 20)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 1)
        val tiles = game.tileMap.values.size
        // 8 x 20 / 1 + max(0, Prince 3 - 3), x tiles / (80 x 52)
        assertEquals(160 * tiles / 4160, EconomicMilitaryStrategyTests.desiredEarlyCities(civ))
        civ.ai.setPersonalityFlavor(Flavor.Growth, 4)
        assertEquals(40 * tiles / 4160, EconomicMilitaryStrategyTests.desiredEarlyCities(civ))
    }

    @Test
    fun `with nothing to explore one explorer stays and the others retire one a turn`() {
        val civ = neutralCiv()
        game.makeHexagonalMap(2, Constants.grassland)
        game.addCity(civ, tile(0, 0))
        for (t in game.tileMap.values) t.setExplored(civ, true)
        for (position in listOf(tile(1, 0), tile(-1, 0), tile(0, 1)))
            UnitAiTypes.set(game.addUnit("Warrior", civ, position), UnitAiType.EXPLORE)
        fun exploring() = civ.units.getCivUnits().count { UnitAiTypes.current(it) == UnitAiType.EXPLORE }
        // no plot to explore: 50 wanted, so ENOUGH above 100 and NEUTRAL from there
        civ.ai.economic.doReconState(civ)
        assertEquals(ReconState.Enough, civ.ai.economic.reconState)
        assertEquals(2, exploring())
        civ.ai.economic.doReconState(civ)
        assertEquals(1, exploring())
        civ.ai.economic.doReconState(civ)
        assertEquals(ReconState.Neutral, civ.ai.economic.reconState)
        assertEquals(1, exploring())
    }

    @Test
    fun `a needed explorer is the unit farthest from our cities among those older than three turns`() {
        val civ = neutralCiv()
        game.addCity(civ, tile(0, 0))
        for (t in tile(0, 0).getTilesInDistance(2)) t.setExplored(civ, true)
        game.gameInfo.turns = 10
        val near = game.addUnit("Warrior", civ, tile(1, 0)).apply { turnCreated = 0 }
        val far = game.addUnit("Warrior", civ, tile(2, 0)).apply { turnCreated = 0 }
        val fresh = game.addUnit("Warrior", civ, tile(-2, 0)).apply { turnCreated = 8 }
        civ.ai.economic.doReconState(civ)
        assertEquals(ReconState.Needed, civ.ai.economic.reconState)
        assertEquals(listOf(UnitAiType.ATTACK, UnitAiType.EXPLORE, UnitAiType.ATTACK), listOf(near, far, fresh).map { UnitAiTypes.current(it) })
    }

    @Test
    fun `city-state status is critical with a neighbor at war and normal with a close peaceful one`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val major = neutralCiv()
        game.addCity(cityState, tile(0, 0))
        game.addCity(major, tile(4, 0))
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        major.proximity[cityState.civID] = Proximity.Neighbors
        assertEquals(MinorCivStatus.Normal, MinorCivStatusAi.status(cityState))
        for (civ in listOf(major, cityState)) civ.getDiplomacyManager(if (civ == major) cityState else major)!!.diplomaticStatus = DiplomaticStatus.War
        // NEIGHBORS at war: +20
        assertEquals(MinorCivStatus.Critical, MinorCivStatusAi.status(cityState))
        // CLOSE counts only minor-civ aggressors, not the war itself
        major.proximity[cityState.civID] = Proximity.Close
        assertEquals(MinorCivStatus.Normal, MinorCivStatusAi.status(cityState))
    }
    //endregion

    //region Player decisions

    private fun conquestSetup(): Triple<Civilization, Civilization, City> {
        val attacker = neutralCiv()
        val defender = neutralCiv()
        game.addCity(attacker, tile(-4, 0))
        game.addCity(defender, tile(4, 0))
        val target = game.addCity(defender, tile(0, 0))
        attacker.stats.happiness = 5
        return Triple(attacker, defender, target)
    }

    @Test
    fun `conquered cities are puppeted when happy and razed when unhappy`() {
        val (attacker, _, target) = conquestSetup()
        assertEquals(ConquestChoice.Puppet, CityConquestAutomation.chooseConquest(attacker, target))
        attacker.stats.happiness = -1
        assertEquals(ConquestChoice.Raze, CityConquestAutomation.chooseConquest(attacker, target))
    }

    @Test
    fun `retaken own cities are annexed`() {
        val (attacker, _, target) = conquestSetup()
        target.foundingCivObject = attacker
        attacker.stats.happiness = -1 // a city we founded is never razed
        assertEquals(ConquestChoice.Annex, CityConquestAutomation.chooseConquest(attacker, target))
    }

    @Test
    fun `city-state cities are liberated by friendly conquerors and diplomats`() {
        val (attacker, _, target) = conquestSetup()
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(0, 4))
        target.foundingCivObject = cityState
        assertEquals(ConquestChoice.Puppet, CityConquestAutomation.chooseConquest(attacker, target))
        attacker.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Friendly
        assertEquals(ConquestChoice.Liberate, CityConquestAutomation.chooseConquest(attacker, target))
        attacker.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Bully
        attacker.ai.setGrandStrategyAndPursuit(GrandStrategyAi.UNITED_NATIONS)
        assertEquals(ConquestChoice.Liberate, CityConquestAutomation.chooseConquest(attacker, target))
    }

    @Test
    fun `annexing takes the best producing puppet in the top half, never under the culture grand strategy`() {
        val civ = neutralCiv()
        civ.stats.happiness = 5
        val capital = game.addCity(civ, tile(0, 0))
        val strong = game.addCity(civ, tile(4, 0))
        val weak = game.addCity(civ, tile(-4, 0))
        val weakest = game.addCity(civ, tile(0, 4))
        for ((city, production) in listOf(capital to 10f, strong to 8f, weak to 3f, weakest to 1f)) {
            city.cityStats.currentCityStats.production = production
        }
        weak.isPuppet = true
        // only the top 50% (capital, strong) count: the puppet is third
        assertNull(CityConquestAutomation.considerAnnex(civ))
        strong.isPuppet = true
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        assertNull(CityConquestAutomation.considerAnnex(civ))
        civ.ai.setGrandStrategyAndPursuit(null)
        assertEquals(strong, CityConquestAutomation.considerAnnex(civ))
        assertFalse(strong.isPuppet)
        assertTrue(weak.isPuppet)
    }
    //endregion

    //region Strategy tests

    @Test
    fun `found city counts only loose settlers, not those an operation already moves`() {
        game.makeHexagonalMap(12, Constants.grassland)
        val civ = neutralCiv()
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        game.addCity(civ, tile(0, 0))
        assertFalse(EconomicMilitaryStrategyTests.foundCity(civ))
        val settler = game.addUnit("Settler", civ, tile(2, 0))
        assertTrue(EconomicMilitaryStrategyTests.foundCity(civ))
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertNotNull(civ.ai.operations.armyOf(settler))
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        assertFalse(EconomicMilitaryStrategyTests.foundCity(civ))
    }
    //endregion
}
