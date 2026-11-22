package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.NeedsAiScenarios
import com.unciv.logic.automation.civilization.CityConquestAutomation.ConquestChoice
import com.unciv.logic.automation.diplomacy.WarmongerAi
import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.PublicOpinion
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.OriginalHappinessModel
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's CvPlayerAI::AI_conquerCity, AI_considerAnnex and AI_considerRaze (Release-5.4.8) as [CityConquestAutomation] has them.
 *  [attacker] has just taken [target] from [defender]; the other cities, which decide the median city, make [MEDIAN_PRODUCTION].
 */
@RunWith(GdxTestRunner::class)
class ConquestAftermathTests {
    private lateinit var game: TestGame
    private lateinit var attacker: Civilization
    private lateinit var defender: Civilization
    private lateinit var attackerCapital: City
    private lateinit var target: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        // The tests outside of the last region set the happiness of the one number of the original's model ([OriginalHappinessModel]), which the key-off
        // branches of CityConquestAutomation read; the needs model's approval rating decides in the tests of the last region, on their own games
        OriginalHappinessModel.use(game.ruleset)
        game.makeHexagonalMap(8, Constants.grassland)
        attacker = neutralCiv()
        defender = neutralCiv()
        attackerCapital = game.addCity(attacker, tile(-6, 0))
        val defenderCapital = game.addCity(defender, tile(6, 0))
        val spare = game.addCity(defender, tile(4, 2))
        val otherSpare = game.addCity(defender, tile(4, -2))
        target = game.addCity(defender, tile(0, 0))
        for (city in listOf(attackerCapital, defenderCapital, spare, otherSpare, target)) city.cityStats.currentCityStats.production = MEDIAN_PRODUCTION
        attacker.stats.happiness = 5
    }

    private fun neutralCiv(vararg uniques: String): Civilization {
        val civ = game.addCiv(*uniques)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        return civ
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    private fun choice(by: Civilization = attacker, city: City = target) = CityConquestAutomation.chooseConquest(by, city)

    /** [city]'s economic value for [attacker] as a percentage of the median city's, as AI_conquerCity rates it */
    private fun percentOfMedian(city: City = target) =
        AttackTargetsAi.economicValue(city, attacker) * 100 / WarmongerAi.medianEconomicValue(game.gameInfo)

    /** Makes the target worth [percent] percent of the median city, or a little more (production comes in steps) */
    private fun worth(percent: Int) {
        for (production in 0..400) {
            target.cityStats.currentCityStats.production = production.toFloat()
            if (percentOfMedian() >= percent) break
        }
        assertTrue("worth ${percentOfMedian()}% for a wanted $percent%", percentOfMedian() in percent..percent + 20)
    }

    private fun setPublicOpinionUnhappiness(civ: Civilization, unhappiness: Int) {
        PublicOpinion::class.java.getDeclaredField("unhappiness").apply { isAccessible = true }.setInt(civ.culture.publicOpinion, unhappiness)
    }

    //region AI_conquerCity

    @Test
    fun `a city worth the median is annexed, one worth something puppeted, a worthless one razed`() {
        assertEquals(100, percentOfMedian())
        assertEquals(ConquestChoice.Annex, choice())
        worth(40)
        assertEquals(ConquestChoice.Puppet, choice())
        target.cityStats.currentCityStats.production = 0f
        assertTrue(percentOfMedian() < 40)
        assertEquals(ConquestChoice.Raze, choice())
        worth(60)
        assertEquals("under the high value threshold of 80", ConquestChoice.Puppet, choice())
        worth(80)
        assertEquals(ConquestChoice.Annex, choice())
    }

    @Test
    fun `an unhappy empire puppets what it would annex and razes what it would puppet`() {
        attacker.stats.happiness = -1
        assertEquals("a valuable city is kept, but not annexed", ConquestChoice.Puppet, choice())
        worth(40)
        assertEquals("a city worth something is no puppet when we are unhappy", ConquestChoice.Raze, choice())
    }

    @Test
    fun `a very unhappy empire keeps no city for its value`() {
        attacker.stats.happiness = -10
        assertEquals(ConquestChoice.Raze, choice())
        attacker.stats.happiness = -9
        assertEquals("only unhappy", ConquestChoice.Puppet, choice())
    }

    @Test
    fun `a city with a world wonder is kept however little it is worth`() {
        target.cityStats.currentCityStats.production = 0f
        assertEquals(ConquestChoice.Raze, choice())
        target.cityConstructions.addBuilding(game.createWonder())
        assertEquals(ConquestChoice.Annex, choice())
        attacker.stats.happiness = -30
        assertEquals("kept, but puppeted for the unhappiness", ConquestChoice.Puppet, choice())
    }

    @Test
    fun `the only city of ours on its continent is kept unless we are very unhappy, and razed only when we may revolt`() {
        attackerCapital.getCenterTile().apply { clearContinent(); setContinent(1) }
        target.getCenterTile().apply { clearContinent(); setContinent(2) }
        target.cityStats.currentCityStats.production = 0f
        assertEquals(ConquestChoice.Annex, choice())
        attacker.stats.happiness = -10
        assertEquals("very unhappy, but it would not revolt: the foothold is puppeted", ConquestChoice.Puppet, choice())
        attacker.stats.happiness = -20
        assertEquals("super unhappy: it may revolt", ConquestChoice.Raze, choice())
        attacker.stats.happiness = -10
        setPublicOpinionUnhappiness(attacker, 3)
        assertEquals("very unhappy with the opinion of the people against us", ConquestChoice.Raze, choice())
    }

    @Test
    fun `a second city on the continent leaves nothing to keep a worthless one for`() {
        attackerCapital.getCenterTile().apply { clearContinent(); setContinent(2) }
        target.getCenterTile().apply { clearContinent(); setContinent(2) }
        target.cityStats.currentCityStats.production = 0f
        assertEquals(ConquestChoice.Raze, choice())
    }

    @Test
    fun `a city we founded is annexed in any mood, and so is every city of a civilization that has none`() {
        target.foundingCivObject = attacker
        attacker.stats.happiness = -30
        assertEquals(ConquestChoice.Annex, choice())
        target.foundingCivObject = defender
        assertEquals(ConquestChoice.Annex, choice(neutralCiv()))
    }

    @Test
    fun `a civilization that may not annex puppets the city it keeps`() {
        val venice = neutralCiv("May not annex cities")
        game.addCity(venice, tile(-4, 4))
        assertEquals(ConquestChoice.Puppet, choice(venice))
    }

    @Test
    fun `a city-state liberates the city of its ally and annexes any other`() {
        val founder = neutralCiv()
        game.addCity(founder, tile(0, -6))
        val founded = game.addCity(founder, tile(2, 4))
        founded.puppetCity(defender)
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(-2, 6))
        assertEquals(ConquestChoice.Annex, choice(cityState, founded))
        cityState.allyCiv = founder
        assertEquals(ConquestChoice.Liberate, choice(cityState, founded))
        assertEquals("not the city of someone else", ConquestChoice.Annex, choice(cityState, target))
    }

    //endregion

    //region AI_considerAnnex

    /** A city of [civ], a puppet, that makes [production] on top of what its tile does; founded by [founder] (by [civ] when null) */
    private fun puppet(civ: Civilization, at: HexCoord, production: Float, founder: Civilization? = null): City {
        val city = game.addCity(founder ?: civ, game.getTile(at))
        if (founder != null) city.puppetCity(civ).also { city.removeFlag(CityFlags.Resistance) }
        city.isPuppet = true
        city.cityConstructions.addBuilding(game.createBuilding().apply { this.production = production })
        city.cityStats.update()
        return city
    }

    /** considerAnnex with the happiness of [civ] set again, as founding or taking a city recalculates it */
    private fun annexFor(civ: Civilization): City? {
        civ.stats.happiness = 5
        return CityConquestAutomation.considerAnnex(civ)
    }

    private fun annexer(): Civilization {
        val civ = neutralCiv()
        game.addCity(civ, tile(-3, -4))
        civ.tech.addTechnology("Philosophy")   // the technology of the Courthouse in Vox Populi (Brave New World: Mathematics)
        civ.stats.happiness = 5
        return civ
    }

    @Test
    fun `a city-state stops razing and annexes every city, a major civilization one a turn`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val first = puppet(cityState, HexCoord(-2, 6), 3f)
        val second = puppet(cityState, HexCoord(2, 4), 3f)
        val razing = game.addCity(cityState, tile(-4, 4)).apply { isBeingRazed = true }
        assertNull(CityConquestAutomation.considerAnnex(cityState))
        assertTrue(!first.isPuppet && !second.isPuppet && !razing.isBeingRazed)

        val civ = annexer()
        val one = puppet(civ, HexCoord(-6, -6), 3f)
        val two = puppet(civ, HexCoord(2, -4), 3f)
        val chosen = annexFor(civ)
        assertEquals(1, listOf(one, two).count { it.isPuppet })
        assertTrue(chosen == one || chosen == two)
        assertFalse(chosen!!.isPuppet)
    }

    @Test
    fun `a puppet capital is annexed first, and a capital being razed stops being razed`() {
        val civ = annexer()
        val capital = civ.getCapital()!!
        capital.isPuppet = true
        capital.isBeingRazed = true
        val other = puppet(civ, HexCoord(-4, 4), 9f)
        assertEquals(capital, annexFor(civ))
        assertFalse(capital.isPuppet)
        assertFalse(capital.isBeingRazed)
        assertTrue(other.isPuppet)
    }

    @Test
    fun `nothing is annexed by an unhappy empire, without a courthouse to build, or while a city is occupied without one`() {
        val civ = annexer()
        val puppet = puppet(civ, HexCoord(-4, 4), 3f)
        civ.stats.happiness = -1
        assertNull(CityConquestAutomation.considerAnnex(civ))
        civ.stats.happiness = 5

        val unlearned = neutralCiv()
        game.addCity(unlearned, tile(-2, 6))
        unlearned.stats.happiness = 5
        val unlearnedPuppet = puppet(unlearned, HexCoord(2, -4), 3f)
        assertNull("no Philosophy: no courthouse", CityConquestAutomation.considerAnnex(unlearned))
        assertTrue(unlearnedPuppet.isPuppet)

        val occupied = annexed(civ, HexCoord(2, 4))
        assertNull("an occupied city waits for its courthouse", annexFor(civ))
        assertTrue(puppet.isPuppet)
        occupied.cityConstructions.addBuilding("Courthouse")
        assertEquals(puppet, annexFor(civ))
    }

    @Test
    fun `the puppet to annex is the one of the highest weight times production, not just the best producer`() {
        val civ = annexer()
        val producer = puppet(civ, HexCoord(-4, 4), 8f, founder = defender)
        val home = puppet(civ, HexCoord(2, -4), 3f) // founded by us: weight 1 + 4, and 5 x 3 beats 1 x 8
        assertEquals(home, annexFor(civ))
        assertTrue(producer.isPuppet)
    }

    @Test
    fun `a razed city with a wonder stops being razed, and a razed city may be the one chosen to be saved`() {
        val civ = annexer()
        val wonderCity = annexed(civ, HexCoord(2, 4))
        wonderCity.cityConstructions.addBuilding("Courthouse")
        wonderCity.cityConstructions.addBuilding(game.createWonder())
        wonderCity.isBeingRazed = true
        assertNull("no longer razed, so no option", annexFor(civ))
        assertFalse(wonderCity.isBeingRazed)

        val razedCity = annexed(civ, HexCoord(2, -4))
        razedCity.cityConstructions.addBuilding("Courthouse")
        razedCity.isBeingRazed = true
        assertEquals("a razed city is an option too", razedCity, annexFor(civ))
        assertFalse("and is no longer razed, as a puppet would be annexed", razedCity.isBeingRazed)
    }

    @Test
    fun `under the culture grand strategy only a puppet of weight four or more is annexed`() {
        val civ = annexer()
        val ordinary = puppet(civ, HexCoord(-4, 4), 10f, founder = defender)
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        assertNull(annexFor(civ))
        ordinary.foundingCivObject = civ // a city we founded weighs 5
        assertEquals(ordinary, annexFor(civ))
    }

    //endregion

    //region AI_considerRaze

    /** A city [civ] took from [defender] and annexed, no longer in resistance */
    private fun annexed(civ: Civilization, at: HexCoord): City {
        val city = game.addCity(defender, game.getTile(at))
        city.puppetCity(civ)
        city.annexCity()
        city.removeFlag(CityFlags.Resistance)
        return city
    }

    /** A capital and three occupied cities; [nets] is what each of them adds to the empire's happiness */
    private class Empire(val civ: Civilization, val big: City, val medium: City, val small: City) {
        val nets = linkedMapOf(big to -10f, medium to -5f, small to -2f)
    }

    private fun revoltingEmpire(): Empire {
        val civ = neutralCiv()
        game.addCity(civ, tile(-3, -4))
        return Empire(civ, annexed(civ, HexCoord(-4, 4)), annexed(civ, HexCoord(2, -4)), annexed(civ, HexCoord(-2, 6)))
    }

    /** considerRaze for an empire whose excess happiness is [happiness]; both are set again, as taking a city or building something recalculates them */
    private fun raze(e: Empire, happiness: Int): List<City> {
        for ((city, net) in e.nets) city.cityStats.happinessList = linkedMapOf("Population" to net)
        e.civ.stats.happiness = happiness
        return CityConquestAutomation.considerRaze(e.civ)
    }

    @Test
    fun `an empire that may revolt burns the city whose loss gains it the most happiness, and no more than needed`() {
        val e = revoltingEmpire()
        assertEquals("very unhappy, but nobody turns the people against it", emptyList<City>(), raze(e, -15))
        assertEquals(listOf(e.big), raze(e, -25)) // -25 + 10 = -15, above the threshold of -20
        assertTrue(e.big.isBeingRazed)
        assertFalse(e.medium.isBeingRazed)
    }

    @Test
    fun `a city already being razed counts as gone, and the last city is never burnt`() {
        val e = revoltingEmpire()
        e.medium.isBeingRazed = true // -24 + 5 = -19, above the threshold of -20
        assertEquals(emptyList<City>(), raze(e, -24))
        e.medium.isBeingRazed = false
        assertEquals(listOf(e.big, e.medium, e.small), raze(e, -60))
        assertFalse(e.civ.getCapital()!!.isBeingRazed)
    }

    @Test
    fun `very unhappy with the opinion of the people against it, the threshold is the very unhappy one`() {
        val e = revoltingEmpire()
        setPublicOpinionUnhappiness(e.civ, 3)
        assertEquals(listOf(e.big), raze(e, -15)) // -5, above -10
        e.big.isBeingRazed = false
        assertEquals("nothing to burn when the threshold is met", emptyList<City>(), raze(e, -9))
    }

    @Test
    fun `cities without a courthouse burn before those with one, and cities in danger before those that are not`() {
        val e = revoltingEmpire()
        e.big.cityConstructions.addBuilding("Courthouse")
        assertEquals("the big one has a courthouse", listOf(e.medium, e.small), raze(e, -25))
        e.medium.isBeingRazed = false
        e.small.isBeingRazed = false
        e.big.cityConstructions.removeBuilding("Courthouse")
        // The small city is about to fall to an enemy at war with us: it goes first, though it gains the least
        val enemy = neutralCiv()
        game.addCity(enemy, tile(2, 8))
        e.civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        e.civ.getDiplomacyManager(enemy)!!.diplomaticStatus = DiplomaticStatus.War
        enemy.getDiplomacyManager(e.civ)!!.diplomaticStatus = DiplomaticStatus.War
        e.small.damageTakenLastTurn = 1000
        assertEquals(listOf(e.small, e.big), raze(e, -25))
    }

    @Test
    fun `cities that cannot be razed, puppets, and cities that cost no happiness are left alone`() {
        val e = revoltingEmpire()
        e.big.religion.religionThisIsTheHolyCityOf = "A religion"
        e.medium.isPuppet = true
        e.nets[e.small] = 3f
        assertEquals(emptyList<City>(), raze(e, -25))
        assertTrue(listOf(e.big, e.medium, e.small).none { it.isBeingRazed })
    }

    //endregion

    //region The needs model (cityNeedsLikeVp): the approval rating decides

    private fun neutralize(civ: Civilization) {
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
    }

    /** An AI conqueror whose capital has [unhappy] unhappy citizens and [happy] happiness (approval min(200, happy x 100 / unhappy) / 2), a defender and a city it just took */
    private inner class NeedsConquest(happy: Int, unhappy: Int) {
        val world = NeedsTestWorld()
        val attacker: Civilization = world.game.addCiv().also { neutralize(it) }
        val defender: Civilization = world.game.addCiv().also { neutralize(it) }
        val capital = NeedsAiScenarios.addEmpireCity(world, attacker, happy, unhappy)
        val target: City

        init {
            val others = listOf(world.addCity(3, of = defender), world.addCity(3, of = defender), world.addCity(3, of = defender))
            target = world.game.addCity(defender, world.game.getTile(HexCoord(0, -5)))
            for (city in others + capital + target) city.cityStats.currentCityStats.production = MEDIAN_PRODUCTION
        }

        /** The target is worth [percent] percent of the median city, or a little more */
        fun worth(percent: Int) {
            for (production in 0..400) {
                target.cityStats.currentCityStats.production = production.toFloat()
                if (AttackTargetsAi.economicValue(target, attacker) * 100 / WarmongerAi.medianEconomicValue(world.game.gameInfo) >= percent) break
            }
        }

        fun choice() = CityConquestAutomation.chooseConquest(attacker, target)
    }

    @Test
    fun `with the needs model a conquest is annexed until the approval rating is under 40, puppeted below that, and razed by a very unhappy empire that may revolt`() {
        var e = NeedsConquest(20, 10) // approval 100
        e.worth(100)
        assertEquals(100, e.attacker.getApproval())
        assertEquals(ConquestChoice.Annex, e.choice())

        e = NeedsConquest(9, 10) // 45: unhappy, but only a little: a buffer under 40
        e.worth(100)
        assertTrue(e.attacker.isEmpireUnhappy())
        assertEquals(45, e.attacker.getApproval())
        assertEquals("a little unhappy is fine to take a city", ConquestChoice.Annex, e.choice())

        e = NeedsConquest(7, 9) // 38
        e.worth(100)
        assertEquals(38, e.attacker.getApproval())
        assertEquals(ConquestChoice.Puppet, e.choice())

        e = NeedsConquest(3, 5) // 30: very unhappy keeps no city for its value
        e.worth(100)
        assertTrue(e.attacker.isEmpireVeryUnhappy())
        assertEquals(ConquestChoice.Raze, e.choice())
    }

    @Test
    fun `a very unhappy empire may revolt without the opinion of the people against it`() {
        val e = NeedsConquest(3, 5) // approval 30
        e.worth(0)
        e.capital.getCenterTile().apply { clearContinent(); setContinent(1) }
        e.target.getCenterTile().apply { clearContinent(); setContinent(2) }
        assertEquals(0, e.attacker.culture.publicOpinion.unhappiness)
        assertEquals("its only city on the continent is razed, as it may revolt", ConquestChoice.Raze, e.choice())
    }

    /** An occupied city of [civ] of [population] citizens: taken from a defender and annexed, no longer in resistance */
    private fun occupied(world: NeedsTestWorld, civ: Civilization, defender: Civilization, at: HexCoord, population: Int): City {
        val city = world.game.addCity(defender, world.game.getTile(at), initialPopulation = population)
        city.puppetCity(civ)
        city.annexCity()
        city.removeFlag(CityFlags.Resistance)
        city.population.setPopulation(population) // taking a city costs citizens
        return city
    }

    @Test
    fun `a very unhappy empire burns the occupied city whose loss raises the approval rating most, and stops at the very unhappy threshold`() {
        for ((happiness, expectedRazed) in listOf(2 to listOf("big"), 1 to listOf("big", "small"), 6 to emptyList())) {
            val world = NeedsTestWorld()
            val civ = world.game.addCiv().also { neutralize(it) }
            val defender = world.game.addCiv().also { neutralize(it) }
            NeedsAiScenarios.addEmpireCity(world, civ, happiness, 0)
            world.addCity(3, of = defender) // the defender's capital: a capital is not razed, so it must not be one of the cities taken
            val big = occupied(world, civ, defender, HexCoord(0, -5), 4)
            val small = occupied(world, civ, defender, HexCoord(5, 0), 2)
            NeedsAiScenarios.refresh(civ)
            // Occupied cities give no happiness and have their citizens unhappy: 6 unhappy against the capital's happiness
            assertEquals(6, civ.stats.needs.unhappyCitizens)
            assertEquals(happiness, civ.stats.needs.happyCitizens)

            val razed = CityConquestAutomation.considerRaze(civ)
            assertEquals("with $happiness happiness", expectedRazed.map { if (it == "big") big else small }, razed)
            if (expectedRazed.isEmpty()) assertFalse("not very unhappy: nothing to do", civ.isEmpireVeryUnhappy())
        }
    }

    @Test
    fun `with the needs model a city with a world wonder is kept however little it is worth, and puppeted below an approval rating of 40`() {
        var e = NeedsConquest(20, 10) // approval 100
        e.worth(0)
        e.target.cityConstructions.addBuilding(e.world.game.createWonder())
        e.target.cityStats.currentCityStats.production = 0f
        assertEquals(ConquestChoice.Annex, e.choice())

        e = NeedsConquest(7, 9) // 38
        e.worth(0)
        e.target.cityConstructions.addBuilding(e.world.game.createWonder())
        e.target.cityStats.currentCityStats.production = 0f
        assertEquals("kept, but puppeted for the unhappiness", ConquestChoice.Puppet, e.choice())
    }

    /**
     *  A capital with [happiness] happiness and three occupied cities of 4, 3 and 2 citizens (their citizens are unhappy, they give no happiness), the big one
     *  with a courthouse if [bigCourthouse] (it costs no unhappiness then, so burning it gains nothing), the small one in danger if [smallInDanger]
     *  (an enemy at war with us has a city 4 tiles away and the small city took damage last turn). The cities considerRaze burns, by name.
     */
    private fun burnt(happiness: Int, bigCourthouse: Boolean, smallInDanger: Boolean): List<String> {
        val world = NeedsTestWorld()
        val civ = world.game.addCiv().also { neutralize(it) }
        val defender = world.game.addCiv().also { neutralize(it) }
        NeedsAiScenarios.addEmpireCity(world, civ, happiness, 0)
        world.addCity(3, of = defender)
        val big = occupied(world, civ, defender, HexCoord(0, -5), 4)
        val medium = occupied(world, civ, defender, HexCoord(-5, 0), 3)
        val small = occupied(world, civ, defender, HexCoord(5, 0), 2)
        if (bigCourthouse) world.build(big, "Removes extra unhappiness from annexed cities")
        if (smallInDanger) {
            val enemy = world.game.addCiv().also { neutralize(it) }
            world.game.addCity(enemy, world.game.getTile(HexCoord(5, 4)))
            civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
            civ.getDiplomacyManager(enemy)!!.diplomaticStatus = DiplomaticStatus.War
            enemy.getDiplomacyManager(civ)!!.diplomaticStatus = DiplomaticStatus.War
            small.damageTakenLastTurn = 1000
        }
        NeedsAiScenarios.refresh(civ)
        assertTrue(civ.isEmpireVeryUnhappy())
        val names = mapOf(big to "big", medium to "medium", small to "small")
        return CityConquestAutomation.considerRaze(civ).map { names[it]!! }
    }

    @Test
    fun `with the needs model cities without a courthouse burn before those with one, and cities in danger before those that are not`() {
        // 3 happiness against 9 unhappy citizens: approval 16, to be brought to 35. The big city's loss gains the most (to 30), the medium one's next (to 75)
        assertEquals(listOf("big", "medium"), burnt(3, bigCourthouse = false, smallInDanger = false))
        // With a courthouse the big city costs nothing, so there is nothing to gain from burning it: 5 unhappy citizens, approval 30; the medium one's loss is enough
        assertEquals(listOf("medium"), burnt(3, bigCourthouse = true, smallInDanger = false))
        // The small city is about to fall to an enemy at war with us: it goes first, though it gains the least
        assertEquals(listOf("small", "big"), burnt(3, bigCourthouse = false, smallInDanger = true))
    }

    //endregion

    private companion object {
        const val MEDIAN_PRODUCTION = 30f
    }
}
