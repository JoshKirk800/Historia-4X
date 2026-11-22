package com.unciv.logic.civilization

import com.unciv.logic.automation.unit.tactical.PillageRules
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The instant yields of Vox Populi (`CvPlayer::doInstantYield`, V:CvPlayer.cpp:25909-28247) as [InstantYields] plays them: the events, which uniques each one takes, what is
 *  credited to which city, and how a gain is applied. V: is `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`.
 */
@RunWith(BaseTestRunner::class)
class InstantYieldTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    /** The capital, founded first, with 2 citizens at (0,0) */
    private lateinit var capital: City
    /** A second city with 2 citizens at (4,0) */
    private lateinit var second: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 2)
        second = game.addCity(civ, game.getTile(HexCoord(4, 0)), initialPopulation = 2)
        assertEquals(capital, civ.getCapital())
        game.gameInfo.turns = 10 // the cities were founded on turn 0
    }

    private fun City.build(vararg uniques: String): Building = game.createBuilding(*uniques).also { cityConstructions.addBuilding(it) }
    private fun adopt(vararg uniques: String) = civ.policies.adopt(game.createPolicy(*uniques), true)
    private fun City.overflow() = cityConstructions.productionOverflow
    /** All the production the city has put somewhere: in its constructions and in its overflow (the AI has chosen something for it to build) */
    private fun City.production() = cityConstructions.productionOverflow + cityConstructions.inProgressConstructions.values.sum()
    private fun City.food() = population.foodStored
    /** The era of the civilization, whatever its techs are (until the next tech is researched, which works the era out again) */
    private fun setEra(number: Int) { civ.tech.era = game.ruleset.eras.values.first { it.eraNumber == number } }
    /** Researches a tech of the era, which puts the civilization in it for good */
    private fun reachEra(number: Int) {
        val era = game.ruleset.eras.values.first { it.eraNumber == number }
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == era.name }.name)
        assertEquals(number, civ.getEraNumber())
    }
    /** The terrain that every tile of the test map has */
    private val terrain get() = game.getTile(HexCoord.Zero).baseTerrain

    // region birth: V:CvCity.cpp:16595-16618, V:CvPlayer.cpp:25990-26026

    @Test
    fun `a city that grows beyond its highest population gives its building's birth yield times the citizens gained, and not when it regrows after starving`() {
        capital.build("Gain [3] [Gold] <upon population growth> <in this city> <for every [Triggering amount]>")
        assertEquals(2, capital.highestPopulation)

        capital.population.addPopulation(2) // 2 to 4 citizens: 2 gained
        assertEquals(6, civ.gold)
        assertEquals(4, capital.highestPopulation)

        capital.population.addPopulation(-1) // starves down to 3
        capital.population.addPopulation(1) // and regrows to 4, which it had: nothing
        assertEquals(6, civ.gold)

        capital.population.addPopulation(1) // 5 citizens, a new highest
        assertEquals(9, civ.gold)
        assertEquals(5, capital.highestPopulation)
    }

    @Test
    fun `no birth yield in a city in resistance or on its founding turn`() {
        capital.build("Gain [3] [Gold] <upon population growth> <in this city>")

        game.gameInfo.turns = capital.turnAcquired // the founding turn: the city's first turn has no growth yields
        capital.population.addPopulation(1)
        assertEquals(0, civ.gold)

        game.gameInfo.turns = capital.turnAcquired + 5
        capital.setFlag(CityFlags.Resistance, 3)
        capital.population.addPopulation(1)
        assertEquals(0, civ.gold)

        capital.removeFlag(CityFlags.Resistance)
        capital.population.addPopulation(1)
        assertEquals(3, civ.gold)
    }

    @Test
    fun `birth yields are credited to the growing city only, a policy's to the same city, Food and Production to that city's stores`() {
        capital.build("Gain [5] [Food] <upon population growth> <in this city>")
        second.build("Gain [5] [Food] <upon population growth> <in this city>")
        adopt("Gain [7] [Production] <upon population growth>")

        second.population.addPopulation(1)

        assertEquals(5, second.food())
        assertEquals(0, capital.food())
        // the policy fires once, in the city that grew, not in the capital
        assertEquals(7, second.production())
        assertEquals(0, capital.production())
    }

    @Test
    fun `a growth in a city of the other civilization does not pay the first`() {
        val other = game.addCiv()
        val theirCity = game.addCity(other, game.getTile(HexCoord(0, 3)), initialPopulation = 2)
        capital.build("Gain [5] [Gold] <upon population growth> <in this city>")

        theirCity.population.addPopulation(1)

        assertEquals(0, civ.gold)
    }

    @Test
    fun `Gain a percentage of the triggering amount takes the whole number of amount times percent over 100`() {
        capital.build("Gain [150]% of the triggering amount as [Gold] <upon population growth> <in this city>")

        capital.population.addPopulation(3) // 3 x 150 / 100 = 4 (cut off)

        assertEquals(4, civ.gold)
    }

    // endregion
    // region borders: V:CvCity.cpp:17176-17304, V:CvPlayer.cpp:26256-26278

    /** A city of its own game, on a map of seven tiles that are all its own */
    private fun lonelyCity(vararg uniques: String): City {
        val own = TestGame()
        own.makeHexagonalMap(1)
        val city = own.addCity(own.addCiv(), own.getTile(HexCoord.Zero))
        own.gameInfo.turns = 10
        if (uniques.isNotEmpty()) city.cityConstructions.addBuilding(own.createBuilding(*uniques))
        return city
    }

    @Test
    fun `border growth yields fire when the city has no tile left to take`() {
        val lonely = lonelyCity("Gain [4] [Gold] <upon acquiring a [All] tile through [border growth]> <in this city>")
        assertTrue(lonely.expansion.chooseNewTileToOwn() == null)
        val level = lonely.expansion.cultureLevel

        lonely.expansion.cultureStored = 100_000
        lonely.expansion.nextTurn(0f)

        assertEquals(4, lonely.civ.gold)
        // as in the original the culture was spent and the level raised, so the yield comes once for each threshold
        assertEquals(level + 1, lonely.expansion.cultureLevel)
        assertTrue(lonely.expansion.cultureStored < 100_000)
    }

    @Test
    fun `a city with nothing to take and nothing listening keeps its culture, as it always did`() {
        val lonely = lonelyCity()
        val level = lonely.expansion.cultureLevel

        lonely.expansion.cultureStored = 100_000
        lonely.expansion.nextTurn(0f)

        assertEquals(100_000, lonely.expansion.cultureStored)
        assertEquals(level, lonely.expansion.cultureLevel)
    }

    @Test
    fun `border growth yields are multiplied by the era`() {
        val lonely = lonelyCity("Gain [4] [Gold] <upon acquiring a [All] tile through [border growth]> <in this city> <for every [Era scaler]>")
        lonely.civ.tech.era = lonely.civ.gameInfo.ruleset.eras.values.first { it.eraNumber == 2 }

        lonely.expansion.cultureStored = 100_000
        lonely.expansion.nextTurn(0f)

        assertEquals(8, lonely.civ.gold) // the Medieval era counts 2
    }

    @Test
    fun `the tile that border growth takes is checked against the tile filter`() {
        capital.build(
            "Gain [4] [Gold] <upon acquiring a [$terrain] tile through [border growth]> <in this city>",
            "Gain [30] [Gold] <upon acquiring a [Hill] tile through [border growth]> <in this city>",
            "Gain [200] [Gold] <upon acquiring a [$terrain] tile through [purchase]> <in this city>")

        capital.expansion.cultureStored = 100_000
        capital.expansion.nextTurn(0f)

        assertEquals(4, civ.gold) // every tile is desert; neither the hill nor the purchase counts
    }

    @Test
    fun `a bought tile pays the yields of a purchase and a settled city those of settling`() {
        capital.build("Gain [3] [Faith] <upon acquiring a [$terrain] tile through [purchase]> <in this city>")
        civ.addGold(10_000)
        val gold = civ.gold
        val tile = game.getTile(HexCoord(2, 0))
        assertTrue(capital.expansion.canBuyTile(tile))

        capital.expansion.buyTile(tile)

        assertEquals(3, civ.religionManager.storedFaith)
        assertTrue(civ.gold < gold)

        adopt("Gain [2] [Faith] <upon acquiring a [All] tile through [settling]>")
        val faith = civ.religionManager.storedFaith
        val third = game.addCity(civ, game.getTile(HexCoord(0, -4)))
        // the center and the six tiles around it
        assertEquals(faith + 2 * third.getTiles().count(), civ.religionManager.storedFaith)
    }

    @Test
    fun `a culture bomb pays for a tile that no one owned and for one it stole by their own kinds`() {
        val other = game.addCiv()
        game.addCity(other, game.getTile(HexCoord(-3, 0))) // its ring takes (-2,0) of the capital's second ring
        capital.build(
            "Gain [1] [Faith] <upon acquiring a [All] tile through [culture bomb]> <in this city>",
            "Gain [100] [Faith] <upon acquiring a [All] tile through [stolen by culture bomb]> <in this city>")
        val center = capital.getCenterTile()
        val tiles = center.getTilesInDistance(2).filter { !it.isCityCenter() && it.getOwner() != civ }.toList()
        val stolen = tiles.count { it.getOwner() == other }
        assertTrue(stolen > 0 && stolen < tiles.size)

        UniqueTriggerActivation.triggerUnique(Unique("Gain control over [All] tiles in a [2]-tile radius"), civ, capital)

        assertEquals(stolen * 100 + (tiles.size - stolen), civ.religionManager.storedFaith)
    }

    // endregion
    // region tech and other civilization-wide events: V:CvPlayer.cpp:26134-26153, V:CvTeam.cpp:8251

    @Test
    fun `a tech's yields - the building's in each city, the policy's in the capital only, times the era`() {
        reachEra(2) // Medieval: x2
        capital.build("Gain [2] [Food] <upon discovering [Pottery] technology> <in this city> <for every [Era scaler]>")
        second.build("Gain [2] [Food] <upon discovering [Pottery] technology> <in this city> <for every [Era scaler]>")
        adopt("Gain [5] [Production] <upon discovering [Pottery] technology> <for every [Era scaler]>")

        civ.tech.addTechnology("Pottery")

        assertEquals(4, capital.food())
        assertEquals(4, second.food())
        assertEquals(10, capital.production())
        assertEquals(0, second.production())
    }

    @Test
    fun `a building's yield for a tech fires only for the techs its filter names`() {
        capital.build("Gain [2] [Food] <upon discovering [Pottery] technology> <in this city>")

        civ.tech.addTechnology("Masonry")
        assertEquals(0, capital.food())
        civ.tech.addTechnology("Pottery")
        assertEquals(2, capital.food())
    }

    @Test
    fun `entering an era, adopting a policy and a golden age pay in the same way`() {
        val policy = game.createPolicy()
        capital.build(
            "Gain [1] [Food] <upon entering a new era> <in this city>",
            "Gain [10] [Food] <upon entering a Golden Age> <in this city>",
            "Gain [100] [Food] <upon adopting [${policy.name}]> <in this city>")
        adopt("Gain [2] [Production] <upon entering a new era>")

        civ.policies.adopt(policy, true)
        assertEquals(100, capital.food())

        civ.goldenAges.enterGoldenAge()
        assertEquals(110, capital.food())
        assertEquals(0, capital.production())

        setEra(0)
        val classicalTech = game.ruleset.technologies.values.first { it.era() == "Classical era" }
        civ.tech.addTechnology(classicalTech.name)
        assertEquals(111, capital.food())
        assertEquals(2, capital.production())
    }

    // endregion
    // region speed and era scaling: V:CvPlayer.cpp:27170-27183

    private fun useInstantYieldPercent(percent: Float) {
        game.ruleset.modOptions.constants.instantYieldsUseInstantYieldPercent = true
        // a new Speed, the ruleset's one belongs to every other test
        val old = game.ruleset.speeds[Speed.DEFAULTFORSIMULATION]!!
        val speed = Speed().apply { name = old.name; turns = old.turns; startYear = old.startYear; instantYieldModifier = percent }
        game.ruleset.speeds[speed.name] = speed
        game.setSpeed(speed.name)
    }

    private fun goldFrom(unique: String, fire: () -> Unit): Int {
        val before = civ.gold
        val building = capital.build(unique)
        fire()
        capital.cityConstructions.removeBuilding(building)
        return civ.gold - before
    }

    /** What each event fires, with the city of the event: the seven new ones and the existing one with a new dispatch */
    private fun events(): List<Pair<String, () -> Unit>> = listOf(
        "upon population growth" to { InstantYields.fire(UniqueType.TriggerUponPopulationGrowth, civ, capital, 1, GameContext(capital)) },
        "upon acquiring a [All] tile through [purchase]" to { InstantYields.tileAcquired(capital, null, TileAcquisition.Purchase) },
        "upon buying something with [Gold]" to { InstantYields.fire(UniqueType.TriggerUponBuying, civ, capital, 100, GameContext(capital), triggerFilter = { true }) },
        "upon completing a [Military] unit" to { InstantYields.fire(UniqueType.TriggerUponCompletingUnit, civ, capital, 40, GameContext(capital)) },
        "upon pillaging a tile" to { InstantYields.fire(UniqueType.TriggerUponPillaging, civ, capital) },
        "upon We Love The King Day starting" to { InstantYields.fire(UniqueType.TriggerUponWltkdStart, civ, capital, 0, GameContext(capital)) },
        "upon gaining a luxury resource" to { InstantYields.fire(UniqueType.TriggerUponGainingLuxury, civ, capital, 1) },
        "upon entering a Golden Age" to { InstantYields.fire(UniqueType.TriggerUponEnteringGoldenAge, civ, null, 0, civ.state) },
    )

    @Test
    fun `instant yields are scaled by the speed's instant yield percentage when their unique says so, and by nothing when it does not, for every event`() {
        useInstantYieldPercent(0.75f)

        for ((trigger, fire) in events()) {
            // 10 x 75 / 100 = 7, cut off; the events that Vox Populi leaves unscaled are the uniques without the modifier
            assertEquals("$trigger scaled", 7, goldFrom("Gain [10] [Gold] <$trigger> <in this city> <(modified by game speed)>", fire))
            assertEquals("$trigger not scaled", 10, goldFrom("Gain [10] [Gold] <$trigger> <in this city>", fire))
        }
    }

    @Test
    fun `without the constant the speed's cost modifier of the stat is used as before`() {
        val speed = game.ruleset.speeds[Speed.DEFAULTFORSIMULATION]!!
        assertEquals(speed.modifier, speed.statCostModifiers[Stat.Gold]!!, 0f)
        assertEquals(10, goldFrom("Gain [10] [Gold] <upon population growth> <in this city> <(modified by game speed)>",
            events().first().second)) // the Standard speed's 1.0
    }

    @Test
    fun `the speed's percentage comes before the era, so a medieval yield is the cut off amount twice`() {
        useInstantYieldPercent(0.75f)
        setEra(2)

        assertEquals(14, goldFrom("Gain [10] [Gold] <upon population growth> <in this city> <(modified by game speed)> <for every [Era scaler]>",
            events().first().second))
    }

    @Test
    fun `an event's yield is not multiplied by the era unless its unique says so`() {
        setEra(2)

        assertEquals(10, goldFrom("Gain [10] [Gold] <upon population growth> <in this city>", events().first().second))
        assertEquals(20, goldFrom("Gain [10] [Gold] <upon population growth> <in this city> <for every [Era scaler]>", events().first().second))
    }

    // endregion
    // region application: V:CvPlayer.cpp:27186-27326

    private fun gain(text: String, city: City? = capital) = UniqueTriggerActivation.triggerUnique(Unique(text), civ, city)

    @Test
    fun `Production goes to the current construction when it is unfinished, else to overflow, and never below 0`() {
        capital.cityConstructions.inProgressConstructions.clear()
        capital.cityConstructions.setCurrentConstruction("Warrior")
        val cost = (capital.cityConstructions.getCurrentConstruction() as INonPerpetualConstruction).getProductionCost(civ, capital)

        gain("Gain [20] [Production]")
        assertEquals(20, capital.cityConstructions.getWorkDone("Warrior"))
        assertEquals(0, capital.overflow())

        gain("Gain [${cost}] [Production]") // unfinished when it came: goes in, past the cost
        assertEquals(20 + cost, capital.cityConstructions.getWorkDone("Warrior"))

        gain("Gain [15] [Production]") // finished: overflow
        assertEquals(15, capital.overflow())
        assertEquals(20 + cost, capital.cityConstructions.getWorkDone("Warrior"))

        gain("Gain [-1000] [Production]") // takes production away, to 0 and no further
        assertEquals(0, capital.cityConstructions.getWorkDone("Warrior"))
        capital.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name) // nothing to build: all of it is overflow
        gain("Gain [-1000] [Production]")
        assertEquals(0, capital.overflow())
    }

    @Test
    fun `Food is added to the stored food of the city and does not take it under 0`() {
        gain("Gain [8] [Food]")
        assertEquals(8, capital.food())
        gain("Gain [-100] [Food]")
        assertEquals(0, capital.food())
    }

    @Test
    fun `a city stat without a city does nothing`() {
        assertEquals(false, gain("Gain [8] [Food]", city = null))
        assertEquals(0, capital.food())
    }

    @Test
    fun `Culture also fills the city's border culture`() {
        val stored = capital.expansion.cultureStored
        val culture = civ.policies.storedCulture

        gain("Gain [10] [Culture]")

        assertEquals(culture + 10, civ.policies.storedCulture)
        assertEquals(stored + 10, capital.expansion.cultureStored)
    }

    @Test
    fun `Culture from a trigger without a city, or found by the tile alone, gives the civilization only`() {
        val stored = capital.expansion.cultureStored
        val culture = civ.policies.storedCulture

        gain("Gain [10] [Culture]", city = null)
        // ruins in our borders: the unit's tile is the capital's, which is no city that the trigger was given
        UniqueTriggerActivation.triggerUnique(Unique("Gain [10] [Culture]"), civ, tile = capital.getCenterTile())

        assertEquals(culture + 20, civ.policies.storedCulture)
        assertEquals(stored, capital.expansion.cultureStored)
    }

    // endregion
    // region purchases and unit completion: V:CvPlayer.cpp:26634-26663, V:CvCity.cpp:29944, 31105-31106

    @Test
    fun `a purchase pays the buyer price x percent over 100 to the city's buildings and to the global ones`() {
        capital.build("Gain [50]% of the triggering amount as [Gold] <upon buying something with [Gold]> <in this city>")
        second.build("Gain [50]% of the triggering amount as [Gold] <upon buying something with [Gold]> <in this city>")
        second.build("Gain [10]% of the triggering amount as [Faith] <upon buying something with [Gold]>") // not local: global
        civ.addGold(1000)
        val warrior = game.ruleset.units["Warrior"]!!
        val price = warrior.getStatBuyCost(capital, Stat.Gold)!!
        assertTrue(price > 10)
        val gold = civ.gold

        assertTrue(capital.cityConstructions.purchaseConstruction(warrior, -1, false, Stat.Gold))

        // the buyer's building gets 50%, the other city's local one nothing; the global building 10 percent of it as faith
        assertEquals(gold - price + price * 50 / 100, civ.gold)
        assertEquals(price * 10 / 100, civ.religionManager.storedFaith)
    }

    @Test
    fun `a purchase with another stat fires only what names that stat`() {
        capital.build("Gain [100]% of the triggering amount as [Gold] <upon buying something with [Faith]> <in this city>")
        civ.addGold(1000)
        val warrior = game.ruleset.units["Warrior"]!!
        val price = warrior.getStatBuyCost(capital, Stat.Gold)!!
        val gold = civ.gold

        capital.cityConstructions.purchaseConstruction(warrior, -1, false, Stat.Gold)

        assertEquals(gold - price, civ.gold)
    }

    @Test
    fun `a unit completed by production pays its production cost as the amount, a bought one does not`() {
        capital.build("Gain [50]% of the triggering amount as [Gold] <upon completing a [Military] unit> <in this city>")
        val warrior = game.ruleset.units["Warrior"]!!
        val cost = warrior.getProductionCost(civ, capital)

        assertTrue(capital.cityConstructions.completeConstruction(warrior))
        assertEquals(cost * 50 / 100, civ.gold)

        val worker = game.ruleset.units["Worker"]!!
        capital.cityConstructions.completeConstruction(worker) // not military
        assertEquals(cost * 50 / 100, civ.gold)
    }

    // endregion
    // region kills: V:CvPlayer.cpp:18409-18447, 26420-26605

    private fun war(): Civilization {
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        return enemy
    }

    private fun killWith(attacker: MapUnit, enemy: Civilization, at: HexCoord): MapUnit {
        val defender = game.addUnit("Warrior", enemy, game.getTile(at))
        defender.health = 1
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))
        assertTrue(defender.isDestroyed)
        return defender
    }

    @Test
    fun `a unit that kills a unit gives the kill yields strength x percent over 100, and a Water filter only at sea`() {
        capital.build(
            "Gain [150]% of the triggering amount as [Gold] <upon defeating a [Military] unit> <in this city>",
            "Gain [1000]% of the triggering amount as [Gold] <upon defeating a [Water] unit> <in this city>")
        val enemy = war()
        val attacker = civ.units.placeUnitNearTile(HexCoord.Zero, "Warrior")!!
        val strength = game.ruleset.units["Warrior"]!!.strength

        killWith(attacker, enemy, HexCoord(1, 0))

        assertEquals(strength * 150 / 100, civ.gold) // 9 for strength 6; the Water row nothing: the Warrior is a land unit
    }

    @Test
    fun `the winner's origin city, else the capital, is the city of the event`() {
        capital.build("Gain [100] [Gold] <upon defeating a [Military] unit> <in this city>")
        second.build("Gain [1000] [Gold] <upon defeating a [Military] unit> <in this city>")
        adopt("Gain [7] [Production] <upon defeating a [Military] unit>")
        val enemy = war()

        // made in the second city: its building and its production
        val fromSecond = civ.units.placeUnitNearTile(HexCoord(4, 0), "Warrior")!!
        assertEquals(second, fromSecond.getOriginCity())
        killWith(fromSecond, enemy, HexCoord(5, 0))
        assertEquals(1000, civ.gold)
        assertEquals(7, second.production())
        assertEquals(0, capital.production())

        // a unit with no origin city: the capital
        val homeless = game.addUnit("Warrior", civ, game.getTile(HexCoord(1, 0)))
        assertEquals(null, homeless.getOriginCity())
        killWith(homeless, enemy, HexCoord(2, 0))
        assertEquals(1100, civ.gold)
        assertEquals(7, capital.production())
    }

    @Test
    fun `a unit keeps its origin city when it is upgraded`() {
        val unit = civ.units.placeUnitNearTile(HexCoord(4, 0), "Warrior")!!
        assertEquals(second, unit.getOriginCity())

        val upgraded = civ.units.placeUnitNearTile(HexCoord(0, 0), game.ruleset.units["Swordsman"]!!, copiedFrom = unit)!!

        assertEquals(second, upgraded.getOriginCity())
    }

    // endregion
    // region pillage, WLTKD, luxuries: V:CvUnit.cpp:10513-10518, V:CvCity.cpp:22014, V:CvPlayer.cpp:38561-38590

    @Test
    fun `pillaging in the territory of another civilization pays the yields of the buildings of the unit's origin city only`() {
        capital.build("Gain [5] [Faith] <upon pillaging a tile> <in this city>")
        second.build("Gain [500] [Faith] <upon pillaging a tile> <in this city>")
        val enemy = war()
        val theirCity = game.addCity(enemy, game.getTile(HexCoord(0, 4)))
        val farm = game.getTile(HexCoord(0, 3))
        theirCity.expansion.takeOwnership(farm)
        farm.setImprovement("Farm")
        val unit = civ.units.placeUnitNearTile(HexCoord.Zero, "Warrior")!!
        assertEquals(capital, unit.getOriginCity())
        unit.putInTile(farm)
        unit.currentMovement = 2f

        assertTrue(PillageRules.pillage(unit)) // the farm's own loot is gold, the yield here is faith

        assertEquals(5, civ.religionManager.storedFaith)
    }

    @Test
    fun `a city that starts a We Love The King Day pays its own building's yield`() {
        capital.build("Gain [5] [Gold] <upon We Love The King Day starting> <in this city>", "Provides [1] [Furs]")
        second.build("Gain [500] [Gold] <upon We Love The King Day starting> <in this city>")
        capital.demandedResource = "Furs"
        assertTrue(capital.getAvailableResourceAmount("Furs") > 0)

        CityTurnManager(capital).startTurn()

        assertTrue(capital.isWeLoveTheKingDayActive())
        assertEquals(5, civ.gold)
    }

    @Test
    fun `a luxury that the civilization has more of than it ever had pays the new amount in the capital`() {
        adopt("Gain [50] [Gold] <upon gaining a luxury resource> <for every [Triggering amount]>")

        capital.build("Provides [2] [Furs]")
        InstantYields.checkLuxuryGains(civ)
        assertEquals(100, civ.gold)

        InstantYields.checkLuxuryGains(civ) // nothing new
        assertEquals(100, civ.gold)

        second.build("Provides [1] [Furs]")
        InstantYields.checkLuxuryGains(civ)
        assertEquals(150, civ.gold)
        assertEquals(3, civ.highestLuxuryAmounts["Furs"])
    }

    // endregion
    // region construction: V:CvCity.cpp:14830-14853

    @Test
    fun `construction yields fire the first time a building is built in a city, not again after it is sold and rebuilt`() {
        val monument = game.createBuilding("Gain [10] [Gold]")
        adopt("Gain [5] [Gold] <upon constructing [${monument.name}]>")

        capital.cityConstructions.addBuilding(monument)
        assertEquals(15, civ.gold)

        capital.cityConstructions.removeBuilding(monument)
        capital.cityConstructions.addBuilding(monument)
        assertEquals(15, civ.gold)

        // another city has not built it yet
        second.cityConstructions.addBuilding(monument)
        assertEquals(30, civ.gold)
    }

    @Test
    fun `a yield of construction is not given in a city in resistance`() {
        val building = game.createBuilding()
        adopt("Gain [5] [Gold] <upon constructing [${building.name}]>")
        capital.setFlag(CityFlags.Resistance, 5)

        capital.cityConstructions.addBuilding(building)

        assertEquals(0, civ.gold)
    }

    // endregion
    // region who receives: V:CvPlayer.cpp:25912

    @Test
    fun `minor civilizations and barbarians receive no instant yields`() {
        val game = TestGame("Gain [5] [Gold] <upon population growth>")
        game.makeHexagonalMap(3)
        val major = game.addCiv()
        val cityState = game.addCiv(cityStateType = "Cultured")
        val barbarians = game.addBarbarianCiv()
        val majorCity = game.addCity(major, game.getTile(HexCoord.Zero))
        val stateCity = game.addCity(cityState, game.getTile(HexCoord(0, 3)))
        game.gameInfo.turns = 10
        val gold = listOf(major, cityState, barbarians).map { it.gold }

        InstantYields.fire(UniqueType.TriggerUponPopulationGrowth, major, majorCity, 1)
        InstantYields.fire(UniqueType.TriggerUponPopulationGrowth, cityState, stateCity, 1)
        InstantYields.fire(UniqueType.TriggerUponPopulationGrowth, barbarians, null, 1)

        assertEquals(gold[0] + 5, major.gold)
        assertEquals(gold[1], cityState.gold)
        assertEquals(gold[2], barbarians.gold)
    }

    // endregion
    // region tally

    @Test
    fun `every gain is tallied under the trigger that paid it`() {
        capital.build("Gain [3] [Gold] <upon population growth> <in this city>")
        gain("Gain [8] [Food]")

        capital.population.addPopulation(1)

        val totals = civ.instantYieldTotals
        assertEquals(3, totals.total("TriggerUponPopulationGrowth", Stat.Gold))
        assertEquals(8, totals.total(InstantYieldTotals.DIRECT, Stat.Food))
        assertEquals(listOf("Direct", "TriggerUponPopulationGrowth"), totals.entries().map { it.first })
    }

    // endregion
    // region saved state

    @Test
    fun `old saves load with highestPopulation equal to the population`() {
        capital.highestPopulation = 0

        capital.setTransients(civ)

        assertEquals(capital.population.population, capital.highestPopulation)
        // and a city that once was bigger keeps it
        capital.highestPopulation = 9
        capital.setTransients(civ)
        assertEquals(9, capital.highestPopulation)
    }

    @Test
    fun `clone copies the new fields`() {
        capital.highestPopulation = 7
        capital.cityConstructions.everBuilt.add("Some building")
        val unit = civ.units.placeUnitNearTile(HexCoord(4, 0), "Warrior")!!
        civ.highestLuxuryAmounts["Furs"] = 3

        assertEquals(7, capital.clone().highestPopulation)
        assertEquals(capital.cityConstructions.everBuilt, capital.cityConstructions.clone().everBuilt)
        assertEquals(second.id, unit.clone().originCityId)
        assertEquals(mapOf("Furs" to 3), civ.clone().highestLuxuryAmounts)
    }

    @Test
    fun `the built buildings of an old save count as built once`() {
        val building = game.createBuilding()
        capital.cityConstructions.addBuilding(building)
        capital.cityConstructions.everBuilt.clear() // a save from before the set was kept

        capital.cityConstructions.setTransients()

        assertTrue(building.name in capital.cityConstructions.everBuilt)
    }

    // endregion
}
