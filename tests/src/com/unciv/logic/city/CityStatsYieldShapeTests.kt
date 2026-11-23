package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The per-turn yield shapes of Vox Populi's data that Unciv did not have: a process modifier, a yield converted to another yield, a Great Work of a type,
 *  the yield per adjacent tiles of an improvement, the capital's yield per citizens of the empire, and the number of golden ages. V: is
 *  `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`: CvCity::GetBaseYieldRateFromProcessTimes100 (V:CvCity.cpp:23821-23834), UpdateCityYieldFromYield and
 *  GetPostModifierYieldRateTimes100 (23269-23291, 23537-23579), CvCityBuildings::GetYieldFromGreatWorksTimes100, CvPlot::ComputeFractionalYieldFromAdjacentImprovement
 *  (V:CvPlot.cpp:6045-6089), CvPlot::calculateYield (V:CvPlot.cpp:10252).
 */
@RunWith(BaseTestRunner::class)
class CityStatsYieldShapeTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var second: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(6)
        civ = game.addCiv(isPlayer = true)
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 3)
        second = game.addCity(civ, game.getTile(HexCoord(5, 0)), initialPopulation = 4)
        assertEquals(capital, civ.getCapital())
    }

    private fun City.build(vararg uniques: String) = game.createBuilding(*uniques).also { cityConstructions.addBuilding(it) }
    private fun City.total(stat: Stat): Float { cityStats.update(); return cityStats.finalStatList.values.sumOf { it[stat].toDouble() }.toFloat() }

    @Test
    fun `owned city tiles countable counts only the context city`() {
        val countable = "Owned [All] Tiles in this city"
        assertTrue(Countables.isKnownValue(countable, game.ruleset))
        assertEquals(capital.getTiles().count(), Countables.getCountableAmount(countable, GameContext(civ, city = capital)))
        assertEquals(second.getTiles().count(), Countables.getCountableAmount(countable, GameContext(civ, city = second)))
    }

    @Test
    fun `border culture applies locally and does not add policy culture`() {
        val culture = capital.total(Stat.Culture)
        capital.build("[3] border culture [in this city]", "[50]% border culture [in this city]")
        assertEquals(19.5f, capital.expansion.borderCulturePerTurn(10f), 1e-3f)
        assertEquals(10f, second.expansion.borderCulturePerTurn(10f), 1e-3f)
        assertEquals(-2f, second.expansion.borderCulturePerTurn(-2f), 1e-3f)
        capital.expansion.nextTurn(0f)
        assertEquals(4, capital.expansion.cultureStored)
        assertEquals(culture, capital.total(Stat.Culture), 1e-3f)
    }

    @Test
    fun `border culture from tiles counts the city center and worked tiles only`() {
        capital.clearWorkedTiles()
        val worked = capital.getTiles().first { it != capital.getCenterTile() }
        capital.workTile(worked)
        capital.build("[2] border culture from [All] tiles [in this city]")
        assertEquals(4f, capital.expansion.borderCulturePerTurn(0f), 1e-3f)
        capital.clearWorkedTiles()
        assertEquals(2f, capital.expansion.borderCulturePerTurn(0f), 1e-3f)
        assertEquals(0f, second.expansion.borderCulturePerTurn(0f), 1e-3f)
    }

    @Test
    fun `fractional population yield retains hundredths`() {
        val before = capital.total(Stat.Science)
        val other = second.total(Stat.Science)
        capital.build("[+34 Science] per [100] population proportionally [in this city]")
        assertEquals(before + 1.02f, capital.total(Stat.Science), 1e-3f)
        assertEquals(other, second.total(Stat.Science), 1e-3f)
    }

    @Test
    fun `a city receives proportional yield per empire citizen`() {
        val before = second.total(Stat.Science)
        val capitalBefore = capital.total(Stat.Science)
        second.build("[+34 Science] per [100] citizens in the empire proportionally [in this city]")
        assertEquals(before + 2.38f, second.total(Stat.Science), 1e-3f)
        assertEquals(capitalBefore, capital.total(Stat.Science), 1e-3f)
    }

    //region Processes

    @Test
    fun `a process yield modifier adds percentage points to the conversion of the production`() {
        capital.cityConstructions.setCurrentConstruction("Gold")
        capital.cityStats.update()
        assertEquals(0.25f, capital.cityStats.getStatConversionRate(Stat.Gold), 1e-6f)
        val before = capital.cityStats.finalStatList["Construction"]!!.gold
        assertTrue(before > 0f)

        capital.build("[50]% yield from processes [in this city]")
        capital.cityStats.update()

        assertEquals(0.75f, capital.cityStats.getStatConversionRate(Stat.Gold), 1e-6f)
        assertEquals("three times the gold of a quarter", before * 3, capital.cityStats.finalStatList["Construction"]!!.gold, 1e-3f)
    }

    @Test
    fun `a process modifier is the city's own and does nothing while the city builds something`() {
        capital.build("[50]% yield from processes [in this city]")
        capital.cityConstructions.setCurrentConstruction("Science")
        second.cityConstructions.setCurrentConstruction("Science")
        second.cityStats.update()
        capital.cityStats.update()

        assertEquals(0.75f, capital.cityStats.getStatConversionRate(Stat.Science), 1e-6f)
        assertEquals(0.25f, second.cityStats.getStatConversionRate(Stat.Science), 1e-6f)
    }

    //endregion
    //region Yield from yield

    @Test
    fun `a yield converted to another yield adds the base yield times the percentage after the bonuses`() {
        capital.build("[+10 Production]")
        val production = capital.total(Stat.Production)
        val gold = capital.total(Stat.Gold)
        val base = capital.cityStats.baseStatTree.totalStats.production
        assertTrue(base >= 10f)

        capital.build("[50]% of [Production] [in this city] added to [Gold]")
        assertEquals("the production is the same, the gold is half of the base", production, capital.total(Stat.Production), 1e-3f)
        assertEquals(gold + base / 2, capital.total(Stat.Gold), 1e-3f)

        // a bonus makes more of the first yield, but the converted part is of what the sources add up to, and the bonus of the gold does not touch it
        capital.build("[100]% [Production]")
        assertEquals(production * 2, capital.total(Stat.Production), 1e-3f)
        assertEquals("not of the production that the bonus doubled", gold + base / 2, capital.total(Stat.Gold), 1e-3f)
        capital.build("[100]% [Gold]")
        assertEquals("and not by the bonus of the gold", gold * 2 + base / 2, capital.total(Stat.Gold), 1e-3f)
    }

    @Test
    fun `a converted yield is not converted again and other cities are left alone`() {
        capital.build("[+10 Production]", "[+6 Gold]")
        second.build("[+10 Production]", "[+6 Gold]")
        val gold = capital.total(Stat.Gold)
        val science = capital.total(Stat.Science)
        val production = capital.cityStats.baseStatTree.totalStats.production
        val baseGold = capital.cityStats.baseStatTree.totalStats.gold
        assertTrue(baseGold >= 6f)
        val otherGold = second.total(Stat.Gold)
        val otherScience = second.total(Stat.Science)

        capital.build("[100]% of [Production] [in this city] added to [Gold]", "[100]% of [Gold] [in this city] added to [Science]")

        assertEquals("all the base production", gold + production, capital.total(Stat.Gold), 1e-3f)
        assertEquals("the base gold, not the gold that the first unique made", science + baseGold, capital.total(Stat.Science), 1e-3f)
        assertEquals(otherGold, second.total(Stat.Gold), 1e-3f)
        assertEquals(otherScience, second.total(Stat.Science), 1e-3f)
    }

    //endregion
    //region Great Works

    private fun fillGallery(city: City, vararg types: GreatWorkType) {
        val gallery = city.build("Has [${types.size}] [Art/Artifact] Great Work slots")
        types.forEachIndexed { index, type ->
            val work = game.createGreatWork(type)
            city.greatWorks.place(GreatWorkInstance(work.name, civ.civName, "Ancient era", 1), GreatWorkSlot(city, gallery, index))
        }
    }

    @Test
    fun `a great work of a type gives its stats for each work of that type in the matching cities`() {
        fillGallery(capital, GreatWorkType.Art, GreatWorkType.Art, GreatWorkType.Artifact)
        val culture = capital.total(Stat.Culture)
        val gold = capital.total(Stat.Gold)

        capital.build("[+3 Culture] from each [Art] Great Work [in this city]")
        capital.build("[+7 Gold] from each [Artifact] Great Work [in all cities]")

        assertEquals("two works of art", culture + 6, capital.total(Stat.Culture), 1e-3f)
        assertEquals("one artifact", gold + 7, capital.total(Stat.Gold), 1e-3f)
    }

    @Test
    fun `a great work of a type is read in the city of the work only, with its filter`() {
        fillGallery(capital, GreatWorkType.Art)
        val culture = second.total(Stat.Culture)
        val before = capital.total(Stat.Culture)
        civ.policies.adopt(game.createPolicy("[+3 Culture] from each [Art] Great Work [in this city]"), true)

        assertEquals("the second city has no work", culture, second.total(Stat.Culture), 1e-3f)
        assertEquals("the policy pays in the city that holds the work", before + 3, capital.total(Stat.Culture), 1e-3f)
        civ.policies.adopt(game.createPolicy("[+4 Culture] from each [Music] Great Work [in all cities]"), true)
        assertEquals("another type", before + 3, capital.total(Stat.Culture), 1e-3f)
    }

    //endregion
    //region Adjacent tiles: V:CvPlot.cpp:6045-6089, 10611-10612

    private fun improvedTile(vararg uniques: String): Tile {
        val tile = game.getTile(HexCoord(2, 0))
        game.addTileToCity(capital, tile)
        val improvement = game.createTileImprovement(*uniques)
        tile.setImprovementBasic(improvement)
        return tile
    }
    private fun gold(tile: Tile) = tile.stats.getTileStats(capital, civ).gold
    private fun neighborsWith(tile: Tile, vararg terrains: String) = terrains.forEachIndexed { index, terrain ->
        val neighbor = tile.neighbors.filter { it.position != HexCoord.Zero }.toList()[index]
        game.setTileTerrain(neighbor.position, terrain)
    }

    @Test
    fun `a per-adjacent improvement yield is the yield for every number of adjacent tiles, cut off at the whole yield`() {
        val tile = improvedTile("[+1 Gold] from every [3] adjacent [Forest]")
        val base = gold(tile)
        val forests = tile.neighbors.filter { it.position != HexCoord.Zero }.toList()

        for ((count, expected) in listOf(0 to 0f, 2 to 0f, 3 to 1f, 5 to 1f, 6 to 2f)) {
            for (neighbor in forests) game.setTileFeatures(neighbor.position)
            for (index in 0 until minOf(count, forests.size)) game.setTileFeatures(forests[index].position, "Forest")
            assertEquals("$count forests", base + expected, gold(tile), 1e-4f)
        }
    }

    @Test
    fun `the adjacent yields of one improvement add up their fractions before the cut`() {
        val tile = improvedTile("[+1 Gold] from every [2] adjacent [Forest]", "[+1 Gold] from every [2] adjacent [Hill]")
        val base = gold(tile)
        val neighbors = tile.neighbors.filter { it.position != HexCoord.Zero }.toList()
        game.setTileFeatures(neighbors[0].position, "Forest")
        assertEquals("one half is nothing", base, gold(tile), 1e-4f)

        game.setTileTerrain(neighbors[1].position, "Hill")
        assertEquals("a half and a half is one", base + 1, gold(tile), 1e-4f)
    }

    @Test
    fun `the adjacent yield counts terrains too`() {
        val tile = improvedTile("[+2 Gold] from every [1] adjacent [Hill]")
        val base = gold(tile)
        neighborsWith(tile, "Hill", "Hill")
        assertEquals(base + 4, gold(tile), 1e-4f)
    }

    //endregion
    //region The capital's yield per citizens of the empire: V:CvPlot.cpp:10252

    @Test
    fun `the capital gets a yield per citizens in the empire and the other cities nothing`() {
        val culture = capital.total(Stat.Culture)
        val otherCulture = second.total(Stat.Culture)
        civ.policies.adopt(game.createPolicy("Capital's [+2 Culture] per [5] citizens in the empire"), true)

        assertEquals("3 and 4 citizens: one fifth full", culture + 2, capital.total(Stat.Culture), 1e-3f)
        assertEquals(otherCulture, second.total(Stat.Culture), 1e-3f)

        second.population.addPopulation(3) // 10 citizens
        assertEquals(culture + 4, capital.total(Stat.Culture), 1e-3f)
        second.population.addPopulation(-2) // 8: one
        assertEquals(culture + 2, capital.total(Stat.Culture), 1e-3f)
    }

    @Test
    fun `fewer citizens than the number give nothing`() {
        val culture = capital.total(Stat.Culture)
        civ.policies.adopt(game.createPolicy("Capital's [+2 Culture] per [8] citizens in the empire"), true)
        assertEquals(culture, capital.total(Stat.Culture), 1e-3f)
    }

    //endregion
    //region Eras and specialists

    private fun enterEras(count: Int) {
        val start = game.gameInfo.getStartingEra().eraNumber
        civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == start + count }
    }

    /** CvTeam::SetCurrentEra adds Feature_EraYieldChanges once for each era the team enters but its start era (V:CvTeam.cpp:8635-8688) */
    @Test
    fun `the countable Eras entered counts the eras since the starting era`() {
        assertTrue(Countables.isKnownValue("Eras entered", game.ruleset))
        assertEquals(0, Countables.getCountableAmount("Eras entered", GameContext(civ)))
        enterEras(2)
        assertEquals(2, Countables.getCountableAmount("Eras entered", GameContext(civ)))
    }

    /** CvPlot::calculateYield: the team's feature yield change is added to the tile's yield (V:CvPlot.cpp:10998, 11020) */
    @Test
    fun `a feature yield grows with each era entered for the civilization that looks at the tile`() {
        val feature = com.unciv.models.ruleset.tile.Terrain().apply {
            name = "Era feature"
            type = com.unciv.models.ruleset.tile.TerrainType.TerrainFeature
            uniques.add("[+2 Science] <for every [Eras entered]>")
        }
        game.ruleset.terrains[feature.name] = feature
        feature.setTransients()
        val tile = game.setTileTerrainAndFeatures(HexCoord(2, 0), "Grassland", feature.name)

        assertEquals(0f, tile.stats.getTileStats(capital, civ).science - plainScience(), 1e-3f)
        enterEras(3)
        assertEquals(6f, tile.stats.getTileStats(capital, civ).science - plainScience(), 1e-3f)
        assertEquals("no civilization, no eras", 0f, tile.stats.getTileStats(null, null).science - plainScience(), 1e-3f)
    }

    private fun plainScience() = game.setTileTerrain(HexCoord(3, 0), "Grassland").stats.getTileStats(capital, civ).science

    /** CvCity::GetBaseYieldRateFromSpecialists counts the specialists of every kind for every yield, golden age points included (V:CvCity.cpp:23389) */
    @Test
    fun `the countable of the specialists of a kind in the city`() {
        assertTrue(Countables.isKnownValue("[Merchant] Specialists in this city", game.ruleset))
        capital.population.specialistAllocations["Merchant"] = 2
        capital.population.specialistAllocations["Scientist"] = 1
        assertEquals(2, Countables.getCountableAmount("[Merchant] Specialists in this city", GameContext(civ, city = capital)))
        assertEquals(1, Countables.getCountableAmount("[Scientist] Specialists in this city", GameContext(civ, city = capital)))
        assertEquals(0, Countables.getCountableAmount("[Merchant] Specialists in this city", GameContext(civ, city = second)))
    }

    /** CvCity::ChangeBaseYieldRateFromBuildings: a policy's or a building's yield for each building of a class counts the buildings of the class in the city (V:CvCity.cpp:14616) */
    @Test
    fun `the countable of the buildings of a kind in the city counts that city only, and carries a golden age points yield`() {
        val kind = capital.build()
        assertTrue(Countables.isKnownValue("[${kind.name}] Buildings in this city", game.ruleset))
        assertEquals(1, Countables.getCountableAmount("[${kind.name}] Buildings in this city", GameContext(civ, city = capital)))
        assertEquals(0, Countables.getCountableAmount("[${kind.name}] Buildings in this city", GameContext(civ, city = second)))
        assertEquals("the empire's count is the old countable's", 1, Countables.getCountableAmount("[${kind.name}] Buildings", GameContext(civ)))

        second.build() // another building, of another kind
        civ.policies.adopt(game.createPolicy("[+3] Golden Age points per turn [in all cities] <for every [[${kind.name}] Buildings in this city]>"), true)
        assertEquals("only the capital has one", 3, civ.goldenAges.pointsPerTurnFromUniques())
    }

    /** CvCity::getBaseYieldRateTimes100: getYieldFromNonSpecialistCitizensTimes100 x (population - specialists) (V:CvCity.cpp:23500-23504) */
    @Test
    fun `the countable of the citizens that are no specialists`() {
        assertTrue(Countables.isKnownValue("Non-specialist citizens in this city", game.ruleset))
        capital.population.specialistAllocations["Merchant"] = 1
        assertEquals(capital.population.population - 1, Countables.getCountableAmount("Non-specialist citizens in this city", GameContext(civ, city = capital)))
        assertEquals(second.population.population, Countables.getCountableAmount("Non-specialist citizens in this city", GameContext(civ, city = second)))
    }

    @Test
    fun `stats may carry a decimal amount and nothing else about them changes`() {
        assertTrue(com.unciv.models.stats.Stats.isStats("+0.25 Culture, +2 Food"))
        assertEquals(0.25f, com.unciv.models.stats.Stats.parse("+0.25 Culture").culture, 1e-6f)
        assertEquals(-3f, com.unciv.models.stats.Stats.parse("-3 Gold").gold, 1e-6f)
        assertTrue(!com.unciv.models.stats.Stats.isStats("+1.5.2 Food"))
        assertTrue(!com.unciv.models.stats.Stats.isStats("+.5 Food"))
    }

    @Test
    fun `a fraction of a yield for each citizen that is no specialist is a fractional stat that the count multiplies`() {
        val before = second.total(Stat.Culture)
        second.build("[+0.25 Culture] [in this city] <for every [Non-specialist citizens in this city]>")
        assertEquals(before + second.population.population * 0.25f, second.total(Stat.Culture), 1e-3f)
    }

    //endregion
    //region Golden ages

    /** CvPlayer::GetGoldenAgePointsFromCitiesTimes100 sums CvCity::getYieldRateTimes100(YIELD_GOLDEN_AGE_POINTS) of the cities (V:CvPlayer.cpp:24846-24860) */
    @Test
    fun `golden age points a turn are summed over the cities that the filter names`() {
        assertEquals(0, civ.goldenAges.pointsPerTurnFromUniques())
        capital.build("[+4] Golden Age points per turn [in this city]")
        assertEquals(4, civ.goldenAges.pointsPerTurnFromUniques())
        civ.policies.adopt(game.createPolicy("[+2] Golden Age points per turn [in all cities]"), true)
        assertEquals("the policy gives each of the two cities 2", 8, civ.goldenAges.pointsPerTurnFromUniques())
    }

    @Test
    fun `golden age points a turn multiply with a countable and the meter takes them at the end of the turn`() {
        // a meter well above 0 (the meter never goes below it) and well below the threshold
        civ.goldenAges.storedHappiness = 200
        val stored = civ.goldenAges.storedHappiness
        com.unciv.logic.civilization.managers.TurnManager(civ).endTurn()
        val without = civ.goldenAges.storedHappiness - stored

        capital.build("[+1] Golden Age points per turn [in this city] <for every [Cities]>")
        assertEquals(2, civ.goldenAges.pointsPerTurnFromUniques())
        civ.goldenAges.storedHappiness = 200
        val stored2 = civ.goldenAges.storedHappiness
        com.unciv.logic.civilization.managers.TurnManager(civ).endTurn()
        assertEquals("two points more than a turn without the unique", 2, civ.goldenAges.storedHappiness - stored2 - without)
    }

    /** Building_YieldChangesPerGoldenAge: yield x golden ages, up to YieldCap (V:CvPlot.cpp:11129-11132 for the trait's rows, the building's cap CvCity.cpp:14373-14380) */
    @Test
    fun `the countable Golden Ages up to a cap stops at the cap`() {
        repeat(3) { civ.goldenAges.endTurn(100000); civ.goldenAges.turnsLeftForCurrentGoldenAge = 0 }
        assertEquals(3, Countables.getCountableAmount("Golden Ages", GameContext(civ)))
        assertEquals(2, Countables.getCountableAmount("Golden Ages up to [2]", GameContext(civ)))
        assertEquals(3, Countables.getCountableAmount("Golden Ages up to [5]", GameContext(civ)))
        assertTrue(Countables.isKnownValue("Golden Ages up to [10]", game.ruleset))
    }

    @Test
    fun `the countable Golden Ages is the number of golden ages had`() {
        assertEquals(0, Countables.getCountableAmount("Golden Ages", GameContext(civ)))
        assertTrue(Countables.isKnownValue("Golden Ages", game.ruleset))

        civ.goldenAges.endTurn(100000)
        assertEquals(1, Countables.getCountableAmount("Golden Ages", GameContext(civ)))
    }

    //endregion
}
