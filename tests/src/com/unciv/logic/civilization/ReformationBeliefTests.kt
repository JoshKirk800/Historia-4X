package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Who receives a Reformation belief with `reformationBeliefsLikeVp` (CvReligionBeliefs::IsBeliefValid, the Reformation branch, V:CvBeliefClasses.cpp:2229-2253):
 * the owner of the holy city of the religion and any civilization that has it as its state religion, and in a city only where it is the majority religion.
 * The probe is a Strength unique on the belief, asked through [Civilization.getMatchingUniques], the way every civilization-wide lookup asks it.
 */
@RunWith(GdxTestRunner::class)
class ReformationBeliefTests {
    private lateinit var game: TestGame
    private lateinit var founder: Civilization
    private lateinit var other: Civilization
    private lateinit var stranger: Civilization
    private lateinit var holyCity: City
    private lateinit var religion: Religion

    /** No conditional: `getMatchingUniques` checks conditionals against an empty context and `<for [All] units>` needs a unit in it */
    private val probe = "[+30]% Strength"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = true
        founder = game.addCiv()
        other = game.addCiv()
        stranger = game.addCiv()
        holyCity = game.addCity(founder, game.getTile(0, 0), initialPopulation = 5)
        religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder), game.createBelief(BeliefType.Reformation, probe)))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        holyCity.religion.addPressure(religion.name, 1000)
    }

    private fun strengthUniques(civ: Civilization) = civ.getMatchingUniques(UniqueType.Strength).filter { it.text == probe }.toList()

    /** A city of [civ] whose majority religion is [religion]: followers and so the state religion of a civilization whose cities mostly follow it */
    private fun followingCity(civ: Civilization, x: Int, y: Int): City {
        val city = game.addCity(civ, game.getTile(x, y), initialPopulation = 5)
        city.religion.addPressure(religion.name, 1000)
        return city
    }

    @Test
    fun `the founder that owns its holy city has the Reformation belief`() {
        assertEquals(1, strengthUniques(founder).size)
    }

    @Test
    fun `a civilization with the religion as its state religion has the belief though it did not found it`() {
        followingCity(other, 3, 0)
        assertSame(religion, other.religionManager.getStateReligion())
        assertEquals(1, strengthUniques(other).size)
    }

    @Test
    fun `a civilization with no relation to the religion does not have the belief`() {
        game.addCity(stranger, game.getTile(0, 4), initialPopulation = 5)
        assertNull(stranger.religionManager.getStateReligion())
        assertEquals(0, strengthUniques(stranger).size)
    }

    @Test
    fun `without the switch only the founder has the belief`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        followingCity(other, 3, 0)
        assertSame(religion, other.religionManager.getStateReligion())
        assertEquals(1, strengthUniques(founder).size)
        assertEquals(0, strengthUniques(other).size)
    }

    @Test
    fun `a civilization that owns the holy city of the religion has the belief though it did not found it`() {
        // a holy city conquered: the religion's holy city now belongs to another civilization
        holyCity.moveToCiv(other)
        assertSame(other, holyCity.civ)
        assertEquals(1, strengthUniques(other).size)
    }

    @Test
    fun `the owner of the holy city of a religion has its Reformation belief though the state religion is another one it owns the holy city of`() {
        // other owns two holy cities: the state religion is the one with the most followers in its cities (CvPlayerReligions::UpdateStateReligion), the other one it has by owning the holy city
        val second = game.addReligion(stranger)
        second.addBeliefs(listOf(game.createBelief(BeliefType.Founder), game.createBelief(BeliefType.Reformation, "[+50]% Strength")))
        val secondHoly = game.addCity(stranger, game.getTile(0, 6), initialPopulation = 5)
        secondHoly.religion.religionThisIsTheHolyCityOf = second.name
        secondHoly.religion.addPressure(second.name, 5000)
        holyCity.moveToCiv(other)
        secondHoly.moveToCiv(other)
        followingCity(other, 3, 0).religion.addPressure(second.name, 100000) // more followers of the second religion in its cities
        assertSame("the state religion is the one with the most followers", second, other.religionManager.getStateReligion())
        assertEquals("by the holy city it owns", 1, strengthUniques(other).size)
        assertEquals("by the state religion", 1, other.getMatchingUniques(UniqueType.Strength).count { it.text == "[+50]% Strength" })
    }

    @Test
    fun `the founder that lost its holy city and follows another religion loses the belief`() {
        // its one city left follows a religion of somebody else, so the state religion of the founder is not the one it founded
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val remaining = game.addCity(founder, game.getTile(0, 3), initialPopulation = 5)
        remaining.religion.addPressure(rival.name, 5000)
        holyCity.moveToCiv(other)
        assertNotNull(founder.religionManager.religion)
        assertSame(rival, founder.religionManager.getStateReligion())
        assertEquals(0, strengthUniques(founder).size)
    }

    @Test
    fun `the founder that lost its holy city keeps the belief while its cities still follow the religion`() {
        followingCity(founder, 0, 3)
        holyCity.moveToCiv(other)
        assertSame(religion, founder.religionManager.getStateReligion())
        assertEquals(1, strengthUniques(founder).size)
    }

    //region the cached state religion follows what it reads
    private fun assertCacheAgrees(civ: Civilization, step: String) =
        assertSame("the cached state religion of ${civ.civName} after $step", civ.religionManager.getStateReligion(), civ.religionManager.getStateReligionCached())

    @Test
    fun `the cached state religion is forgotten when a city of the civilization changes hands`() {
        assertCacheAgrees(other, "start") // asked first, so that the cache is filled
        assertNull(other.religionManager.getStateReligionCached())
        holyCity.moveToCiv(other)
        assertCacheAgrees(other, "a holy city conquered")
        assertSame(religion, other.religionManager.getStateReligionCached())
        assertCacheAgrees(founder, "the holy city lost")
    }

    @Test
    fun `the cached state religion is forgotten when a city is founded`() {
        assertCacheAgrees(other, "start")
        followingCity(other, 3, 0)
        assertCacheAgrees(other, "a city founded that follows the religion")
        assertSame(religion, other.religionManager.getStateReligionCached())
    }

    @Test
    fun `the cached state religion is forgotten when the followers of a city change`() {
        val city = followingCity(other, 3, 0)
        assertCacheAgrees(other, "the city follows the religion")
        assertSame(religion, other.religionManager.getStateReligionCached())
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        city.religion.addPressure(rival.name, 100000)
        assertCacheAgrees(other, "the city converted to a rival religion")
        assertSame(rival, other.religionManager.getStateReligionCached())
    }

    @Test
    fun `the cached state religion is forgotten when a city is destroyed`() {
        // three cities: two follow the religion (a strict majority), one is without it. Destroying a following city leaves one against one: no majority, no state religion.
        // Nothing in destroying a city recomputes the followers of the others, so only the list of cities tells the cache
        val doomed = followingCity(other, 3, 0)
        followingCity(other, 4, 0)
        game.addCity(other, game.getTile(0, 5), initialPopulation = 5)
        assertCacheAgrees(other, "two of three cities follow the religion")
        assertSame(religion, other.religionManager.getStateReligionCached())
        doomed.destroyCity(overrideSafeties = true)
        assertNull("one of two cities follows the religion: no majority", other.religionManager.getStateReligion())
        assertCacheAgrees(other, "a city destroyed")
        assertNull(other.religionManager.getStateReligionCached())
    }
    //endregion

    //region Sacred Sites: tourism of the city follows the Reformation rule for the owner of the city
    /** Sacred Sites is a live Reformation belief; Pagoda a building that only Faith buys. A city of [owner] at [x], [y] that follows the religion and has the Pagoda */
    private fun templeCity(owner: Civilization, x: Int, y: Int): City {
        val city = game.addCity(owner, game.getTile(x, y), initialPopulation = 5)
        city.cityConstructions.addBuilding(game.ruleset.buildings["Pagoda"]!!)
        city.religion.addPressure(religion.name, 1000)
        return city
    }

    private fun withSacredSites() = religion.addBeliefs(listOf(game.ruleset.beliefs["Sacred Sites"]!!))

    @Test
    fun `Sacred Sites tourism goes to the owner of a city that has the religion as its state religion`() {
        withSacredSites()
        val city = templeCity(other, 3, 0)
        assertSame(religion, other.religionManager.getStateReligion())
        assertEquals(2, other.culture.getCityTourismBeforeModifiers(city))
    }

    @Test
    fun `Sacred Sites tourism is lost by the owner of a city that follows the religion but has another state religion`() {
        withSacredSites()
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val city = templeCity(other, 3, 0)
        for (x in 4..5) game.addCity(other, game.getTile(x, 0), initialPopulation = 5).religion.addPressure(rival.name, 1000) // two cities of the rival religion: it is the state religion
        assertSame(rival, other.religionManager.getStateReligion())
        assertEquals(religion, city.religion.getMajorityReligion())
        assertEquals(0, other.culture.getCityTourismBeforeModifiers(city))
    }

    @Test
    fun `Sacred Sites tourism of that city is paid whoever owns it without the switch`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        withSacredSites()
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val city = templeCity(other, 3, 0)
        for (x in 4..5) game.addCity(other, game.getTile(x, 0), initialPopulation = 5).religion.addPressure(rival.name, 1000)
        assertEquals(2, other.culture.getCityTourismBeforeModifiers(city))
    }

    @Test
    fun `Sacred Sites tourism is paid to the owner of the holy city in its own city`() {
        withSacredSites()
        holyCity.cityConstructions.addBuilding(game.ruleset.buildings["Pagoda"]!!)
        assertEquals(2, founder.culture.getCityTourismBeforeModifiers(holyCity))
    }
    //endregion

    //region Unity of the Prophets: what a religion keeps when others are removed depends on the owner of the city
    /** [religion] has Unity of the Prophets (50 percent stays) and 1000 pressure in a city of [owner]; a prophet of the rival religion removes the others from it */
    private fun retentionAfterProphetOfRival(owner: Civilization, ownerHasReligionAsState: Boolean): Int {
        religion.addBeliefs(listOf(game.ruleset.beliefs["Unity of the Prophets"]!!))
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val city = game.addCity(owner, game.getTile(3, 0), initialPopulation = 5)
        city.religion.addPressure(religion.name, 1000)
        if (!ownerHasReligionAsState) for (x in 4..5) game.addCity(owner, game.getTile(x, 0), initialPopulation = 5).religion.addPressure(rival.name, 5000)
        city.religion.removeAllPressuresExceptFor(rival.name)
        return city.religion.getPressures()[religion.name]
    }

    @Test
    fun `a religion keeps half its pressure with Unity of the Prophets in the city of an owner that has it as its state religion`() {
        assertEquals(500, retentionAfterProphetOfRival(other, ownerHasReligionAsState = true))
    }

    @Test
    fun `a religion keeps nothing with Unity of the Prophets in the city of an owner that has another state religion`() {
        assertEquals(0, retentionAfterProphetOfRival(other, ownerHasReligionAsState = false))
    }

    @Test
    fun `a religion keeps half its pressure with Unity of the Prophets whoever owns the city without the switch`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        assertEquals(500, retentionAfterProphetOfRival(other, ownerHasReligionAsState = false))
    }
    //endregion

    //region the city: the Reformation beliefs of the majority religion of a city work in it for its owner, where the rule says so
    private fun probesInCity(city: City) = city.getMatchingUniques(UniqueType.Strength).filter { it.text == probe }.toList().size

    /** The probe as the faith purchases of Jesuit Education are written, scoped to the cities that follow the religion: only the city of the religion gets it */
    private val cityProbe = "[+40]% Strength <in [in cities following this religion] cities>"
    private fun cityProbesInCity(city: City) = city.getMatchingUniques(UniqueType.Strength).filter { it.text == cityProbe }.toList().size
    private fun withCityProbe() = religion.addBeliefs(listOf(game.createBelief(BeliefType.Reformation, cityProbe)))

    @Test
    fun `a Reformation unique scoped to the following cities is in a city that follows the religion, once`() {
        withCityProbe()
        val city = followingCity(other, 3, 0)
        assertEquals(1, cityProbesInCity(city))
        assertEquals(1, cityProbesInCity(holyCity))
    }

    @Test
    fun `a Reformation unique scoped to the following cities is not in a city of the owner that does not follow the religion`() {
        withCityProbe()
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        followingCity(other, 3, 0)
        followingCity(other, 4, 0)
        val elsewhere = game.addCity(other, game.getTile(5, 0), initialPopulation = 5)
        elsewhere.religion.addPressure(rival.name, 5000)
        assertSame(religion, other.religionManager.getStateReligion())
        assertEquals(0, cityProbesInCity(elsewhere))
    }

    @Test
    fun `a Reformation unique scoped to the following cities is not in a city of an owner that has another state religion`() {
        withCityProbe()
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val city = followingCity(other, 3, 0)
        for (x in 4..5) game.addCity(other, game.getTile(x, 0), initialPopulation = 5).religion.addPressure(rival.name, 5000)
        assertEquals(religion, city.religion.getMajorityReligion())
        assertEquals(0, cityProbesInCity(city))
    }

    @Test
    fun `a Reformation unique scoped to the following cities is in no city of a civilization that is not the founder without the switch`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        withCityProbe()
        assertEquals(0, cityProbesInCity(followingCity(other, 3, 0)))
    }

    @Test
    fun `a city of the owner of the holy city that follows the religion has the Reformation belief`() {
        assertEquals(1, probesInCity(holyCity))
    }

    @Test
    fun `a city of a civilization with the religion as its state religion has the belief where the city follows the religion`() {
        val city = followingCity(other, 3, 0)
        assertSame(religion, other.religionManager.getStateReligion())
        assertEquals(1, probesInCity(city))
    }

    @Test
    fun `of the live Reformation beliefs the faith purchases of Jesuit Education and Religious Fervor are the ones scoped to the following cities`() {
        val scoped = HashSet<String>()
        val everywhere = HashSet<String>()
        for (belief in game.ruleset.beliefs.values.filter { it.type == BeliefType.Reformation }) {
            val one = game.addReligion(game.addCiv())
            one.addBeliefs(listOf(belief))
            (if (one.reformationCityUniqueMap.isEmpty()) everywhere else scoped).add(belief.name)
            // each unique of a belief is in exactly one of the two maps
            val inCity = one.reformationCityUniqueMap.getAllUniques().count()
            val inCiv = one.reformationCivUniqueMap.getAllUniques().count()
            assertEquals(belief.name, belief.uniqueObjects.size, inCity + inCiv)
        }
        assertEquals(setOf("Jesuit Education", "Religious Fervor"), scoped)
        assertTrue("Sacred Sites" in everywhere && "Underground Sect" in everywhere && "Charitable Missions" in everywhere)
    }

    @Test
    fun `Jesuit Education lets a city buy a University with Faith only where the city follows the religion`() {
        religion.addBeliefs(listOf(game.ruleset.beliefs["Jesuit Education"]!!))
        val university = game.ruleset.buildings["University"]!!
        // the faith purchases of a city are the BuyBuildingsWithStat uniques that its lookup finds, for the University and Faith
        fun purchasable(city: City) = city.getMatchingUniques(UniqueType.BuyBuildingsWithStat)
            .count { it.params[1] == "Faith" && university.matchesFilter(it.params[0], city.state) }
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val elsewhere = game.addCity(founder, game.getTile(0, 3), initialPopulation = 5)
        elsewhere.religion.addPressure(rival.name, 5000) // a city of the founder that follows a rival religion
        assertTrue("the holy city follows the religion", purchasable(holyCity) > 0)
        assertEquals("a city of the founder that follows another religion", 0, purchasable(elsewhere))
    }

    @Test
    fun `a civilization wide Reformation unique is in every city of the civilization that it applies to, once, whatever the city follows`() {
        val city = followingCity(other, 3, 0)
        followingCity(other, 4, 0)
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val elsewhere = game.addCity(other, game.getTile(5, 0), initialPopulation = 5)
        elsewhere.religion.addPressure(rival.name, 5000)
        assertSame(religion, other.religionManager.getStateReligion())
        assertEquals(1, probesInCity(city))
        assertEquals(1, probesInCity(elsewhere))
    }

    @Test
    fun `a city that follows the religion does not have the belief if its owner has another state religion`() {
        val rival = game.addReligion(stranger)
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        val city = followingCity(other, 3, 0)
        for (x in 4..5) game.addCity(other, game.getTile(x, 0), initialPopulation = 5).religion.addPressure(rival.name, 5000)
        assertSame(rival, other.religionManager.getStateReligion())
        assertEquals(religion, city.religion.getMajorityReligion())
        assertEquals(0, probesInCity(city))
    }

    @Test
    fun `without the switch a city of the founder has the belief and a city of another civilization does not`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        val city = followingCity(other, 3, 0)
        assertEquals(0, probesInCity(city))
        assertEquals(1, probesInCity(holyCity))
    }
    //endregion

    //region Underground Sect: the spy pressure of the religion of a civilization with a spy in the city
    /** A city of [stranger] that the religion has pressure in, with a spy of [spy] in it; the natural pressure the religion gets there each turn */
    private fun pressureFromSpy(spy: Civilization): Int {
        game.gameInfo.gameParameters.espionageEnabled = true
        religion.addBeliefs(listOf(game.ruleset.beliefs["Underground Sect"]!!))
        val target = game.addCity(stranger, game.getTile(0, 5), initialPopulation = 5)
        target.religion.addPressure(religion.name, 1000)
        // other sources add pressure too (a holy city and the cities of the religion round it): what the spy adds is the difference
        val before = target.religion.getPressuresFromSurroundingCities()[religion.name]
        spy.espionageManager.addSpy().moveTo(target)
        return target.religion.getPressuresFromSurroundingCities()[religion.name] - before
    }

    @Test
    fun `Underground Sect makes the pressure of the religion of the founder with a spy in a city`() {
        assertTrue("a spy of the founder pressures the religion", pressureFromSpy(founder) > 0)
    }

    @Test
    fun `Underground Sect makes pressure for a civilization that has the religion as its state religion though it did not found it`() {
        followingCity(other, 3, 0)
        assertSame(religion, other.religionManager.getStateReligion())
        assertTrue(pressureFromSpy(other) > 0)
    }

    @Test
    fun `Underground Sect makes no pressure for a civilization that has nothing to do with the religion`() {
        game.addCity(other, game.getTile(3, 0), initialPopulation = 5)
        assertEquals(0, pressureFromSpy(other))
    }

    @Test
    fun `Underground Sect makes no pressure for a state religion civilization without the switch`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        followingCity(other, 3, 0)
        assertEquals(0, pressureFromSpy(other))
    }
    //endregion

    @Test
    fun `a game with no Reformation belief gives the founder its Founder and Enhancer beliefs and nobody else anything`() {
        val plain = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        plain.makeHexagonalMap(5)
        plain.ruleset.modOptions.constants.reformationBeliefsLikeVp = true
        val a = plain.addCiv()
        val b = plain.addCiv()
        val city = plain.addCity(a, plain.getTile(0, 0), initialPopulation = 5)
        val plainReligion = plain.addReligion(a)
        plainReligion.addBeliefs(listOf(plain.createBelief(BeliefType.Founder, "[+10]% Strength"), plain.createBelief(BeliefType.Enhancer, "[+20]% Strength")))
        city.religion.religionThisIsTheHolyCityOf = plainReligion.name
        city.religion.addPressure(plainReligion.name, 1000)
        plain.addCity(b, plain.getTile(3, 0), initialPopulation = 5).religion.addPressure(plainReligion.name, 1000)
        assertSame(plainReligion, b.religionManager.getStateReligion())
        assertEquals(2, a.getMatchingUniques(UniqueType.Strength).count())
        assertEquals(0, b.getMatchingUniques(UniqueType.Strength).count())
    }

    @Test
    fun `the Founder and Enhancer beliefs of the founder stay when the holy city is lost`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, "[+20]% Strength")))
        holyCity.moveToCiv(other)
        assertEquals(1, founder.getMatchingUniques(UniqueType.Strength).count { it.text == "[+20]% Strength" })
    }
}
