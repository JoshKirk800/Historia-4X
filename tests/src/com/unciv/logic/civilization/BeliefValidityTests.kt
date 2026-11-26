package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.BarbarianConversion
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.Religion
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `religiousBeliefValidityLikeVp`: CvReligionBeliefs::IsBeliefValid (V:CvBeliefClasses.cpp:2151-2317) asked of the religion each reader names in Vox Populi.
 * The religion is OWNED by whoever owns its holy city (CvPlayerReligions::GetOwnedReligion, V:CvReligionClasses.cpp:3742-3759), a holy city stripped by an
 * inquisitor is nobody's, and the founder that lost its holy city has no Founder or Enhancer belief left.
 */
@RunWith(GdxTestRunner::class)
class BeliefValidityTests {
    private lateinit var game: TestGame
    private lateinit var founder: Civilization
    private lateinit var conqueror: Civilization
    private lateinit var holyCity: City
    private lateinit var secondCity: City
    private lateinit var religion: Religion

    private val founderProbe = "[+30]% Strength"
    private val reformationProbe = "[+50]% Strength"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.religiousBeliefValidityLikeVp = true
        founder = game.addCiv()
        conqueror = game.addCiv()
        holyCity = game.addCity(founder, game.getTile(0, 0), initialPopulation = 5)
        secondCity = game.addCity(founder, game.getTile(0, 4), initialPopulation = 5) // so that losing the holy city does not eliminate the founder
        religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, founderProbe, "[+2 Gold] for each global city following this religion")))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        holyCity.religion.addPressure(religion.name, 1000)
        secondCity.religion.addPressure(religion.name, 1000)
    }

    private fun hasProbe(civ: Civilization, probe: String) = civ.getMatchingUniques(UniqueType.Strength).any { it.text == probe }
    private fun religionGold(civ: Civilization) = civ.stats.getStatMapForNextTurn()["Religion"]?.gold ?: 0f

    /** A religion founded by a new civilization, with [uniques] on one Founder belief, the majority in a city of [owner] at ([x], [y]) */
    private fun rivalReligionIn(owner: Civilization, x: Int, y: Int, vararg uniques: String): Pair<Religion, City> {
        val rival = game.addReligion(game.addCiv())
        rival.addBeliefs(listOf(game.createBelief(BeliefType.Founder, *uniques)))
        val city = game.addCity(owner, game.getTile(x, y), initialPopulation = 5)
        city.religion.addPressure(rival.name, 5000)
        return rival to city
    }

    //region who owns the religion
    @Test
    fun `the conqueror of the holy city takes the Founder belief from the founder`() {
        holyCity.moveToCiv(conqueror)
        assertSame(religion, conqueror.religionManager.getOwnedReligion())
        assertTrue(hasProbe(conqueror, founderProbe))
        assertFalse(hasProbe(founder, founderProbe))
    }

    @Test
    fun `the conqueror takes the Founder belief also with reformationBeliefsLikeVp off`() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = false
        holyCity.moveToCiv(conqueror)
        assertTrue(hasProbe(conqueror, founderProbe))
        assertFalse(hasProbe(founder, founderProbe))
    }

    @Test
    fun `without the switch the founder keeps the Founder belief and the conqueror never has it`() {
        game.ruleset.modOptions.constants.religiousBeliefValidityLikeVp = false
        holyCity.moveToCiv(conqueror)
        assertTrue(hasProbe(founder, founderProbe))
        assertFalse(hasProbe(conqueror, founderProbe))
    }

    @Test
    fun `a stripped holy city is nobody's`() {
        holyCity.religion.isBlockedHolyCity = true
        founder.religionManager.invalidateStateReligion()
        assertNull(founder.religionManager.getHolyCityOf(religion))
        assertNull(founder.religionManager.getOwnedReligion())
        assertFalse(hasProbe(founder, founderProbe))
        assertEquals(0f, religionGold(founder), 0.001f)
    }

    @Test
    fun `an inquisitor of another religion in its own holy city strips it and the owner loses the Founder belief`() {
        holyCity.moveToCiv(conqueror)
        val (rival, _) = rivalReligionIn(conqueror, 4, 0)
        holyCity.religion.addPressure(rival.name, 100)
        assertTrue(hasProbe(conqueror, founderProbe)) // the cached state religion is filled here
        val inquisitor = game.addUnit("Inquisitor", conqueror, holyCity.getCenterTile())
        inquisitor.religion = rival.name
        assertTrue(UnitActions.invokeUnitAction(inquisitor, UnitActionType.RemoveHeresy))
        assertTrue(holyCity.religion.isBlockedHolyCity)
        assertFalse(hasProbe(conqueror, founderProbe))
    }

    @Test
    fun `a civilization with the religion as its state religion that does not own the holy city has no Founder belief`() {
        val follower = game.addCiv()
        game.addCity(follower, game.getTile(4, 0), initialPopulation = 5).religion.addPressure(religion.name, 1000)
        assertSame(religion, follower.religionManager.getStateReligion())
        assertNull(follower.religionManager.getOwnedReligion())
        assertFalse(hasProbe(follower, founderProbe))
    }

    @Test
    fun `the global yields of the founder benefits count the cities of the religion the conqueror owns`() {
        assertEquals(4f, religionGold(founder), 0.001f)
        holyCity.moveToCiv(conqueror)
        assertEquals("both cities follow the religion", 4f, religionGold(conqueror), 0.001f)
        assertEquals(0f, religionGold(founder), 0.001f)
    }

    @Test
    fun `the score counts the owned religion`() {
        holyCity.moveToCiv(conqueror)
        assertTrue(conqueror.calculateScoreBreakdown()["Religion"]!! > 0.0)
        assertEquals(0.0, founder.calculateScoreBreakdown()["Religion"]!!, 0.0)
    }
    //endregion

    //region Reformation beliefs of the state religion
    private fun addReformation() {
        game.ruleset.modOptions.constants.reformationBeliefsLikeVp = true
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Reformation, reformationProbe)))
    }

    @Test
    fun `a state religion follower has the Reformation belief while the holy city follows the religion`() {
        addReformation()
        val follower = game.addCiv()
        game.addCity(follower, game.getTile(4, 0), initialPopulation = 5).religion.addPressure(religion.name, 1000)
        assertTrue(hasProbe(follower, reformationProbe))
        assertFalse(hasProbe(follower, founderProbe))
    }

    @Test
    fun `nobody has the Reformation belief while the holy city follows another religion`() {
        addReformation()
        val (rival, _) = rivalReligionIn(founder, 0, -4)
        holyCity.religion.addPressure(rival.name, 100000)
        val follower = game.addCiv()
        game.addCity(follower, game.getTile(4, 0), initialPopulation = 5).religion.addPressure(religion.name, 1000)
        assertSame(religion, follower.religionManager.getStateReligion())
        assertFalse(hasProbe(follower, reformationProbe))
    }
    //endregion

    //region isBeliefValid with bHolyCityOnly
    @Test
    fun `a Founder belief asked for the holy city only works there for its owner`() {
        val belief = religion.getBeliefs(BeliefType.Founder).first()
        val manager = founder.religionManager
        assertTrue(manager.isBeliefValid(belief, religion, holyCity, holyCityOnly = true))
        assertFalse(manager.isBeliefValid(belief, religion, secondCity, holyCityOnly = true))
        assertTrue(manager.isBeliefValid(belief, religion, secondCity))
        val foreignCity = game.addCity(conqueror, game.getTile(4, 0), initialPopulation = 5)
        foreignCity.religion.addPressure(religion.name, 1000)
        assertFalse("not in a city of somebody else", manager.isBeliefValid(belief, religion, foreignCity))
    }

    @Test
    fun `a Reformation belief asked for the holy city only works in the capital of a state religion follower`() {
        addReformation()
        val belief = religion.getBeliefs(BeliefType.Reformation).first()
        val follower = game.addCiv()
        val capital = game.addCity(follower, game.getTile(4, 0), initialPopulation = 5)
        val other = game.addCity(follower, game.getTile(-4, 0), initialPopulation = 5)
        capital.religion.addPressure(religion.name, 1000)
        other.religion.addPressure(religion.name, 1000)
        assertSame(capital, follower.getCapital())
        assertTrue(follower.religionManager.isBeliefValid(belief, religion, capital, holyCityOnly = true))
        assertFalse(follower.religionManager.isBeliefValid(belief, religion, other, holyCityOnly = true))
    }

    @Test
    fun `a pantheon belief asked for the holy city only works in the capital`() {
        val pantheonCiv = game.addCiv()
        val capital = game.addCity(pantheonCiv, game.getTile(4, 0), initialPopulation = 5)
        val other = game.addCity(pantheonCiv, game.getTile(-4, 0), initialPopulation = 5)
        val pantheon = game.addReligion(pantheonCiv)
        val belief = game.createBelief(BeliefType.Pantheon)
        pantheon.addBeliefs(listOf(belief))
        capital.religion.addPressure(pantheon.name, 1000)
        other.religion.addPressure(pantheon.name, 1000)
        assertTrue(pantheonCiv.religionManager.isBeliefValid(belief, pantheon, capital, holyCityOnly = true))
        assertFalse(pantheonCiv.religionManager.isBeliefValid(belief, pantheon, other, holyCityOnly = true))
    }
    //endregion

    //region spread
    private val erosion = "Spreading religion erodes [50]% of the pressure of the other religions in the city"

    @Test
    fun `a missionary of a founder that lost its holy city does not erode`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, erosion)))
        val (rival, target) = rivalReligionIn(conqueror, 4, 0)
        holyCity.moveToCiv(conqueror)
        target.religion.addPressureFromSpreading(religion.name, 100, founder)
        assertEquals(5000, target.religion.getPressures()[rival.name])
    }

    @Test
    fun `the Spread Religion action asks the erosion for the missionary's owner`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, erosion)))
        val (rival, target) = rivalReligionIn(conqueror, 4, 0)
        holyCity.moveToCiv(conqueror)
        val missionary = game.addUnit("Missionary", founder, target.getCenterTile().neighbors.first())
        missionary.religion = religion.name
        assertTrue(UnitActions.invokeUnitAction(missionary, UnitActionType.SpreadReligion))
        assertEquals(5000, target.religion.getPressures()[rival.name])
    }

    @Test
    fun `a missionary of the owner erodes, and so does a spread with no responsible civilization`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, erosion)))
        val (rival, target) = rivalReligionIn(conqueror, 4, 0)
        target.religion.addPressureFromSpreading(religion.name, 100, founder)
        assertEquals(2500, target.religion.getPressures()[rival.name])
        holyCity.moveToCiv(conqueror)
        target.religion.addPressureFromSpreading(religion.name, 100)
        assertEquals(1250, target.religion.getPressures()[rival.name])
    }

    @Test
    fun `a religion keeps its pressure under an inquisition only for the owner of its holy city`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, "[50]% of the pressure of this religion in a city remains when other religions are removed from it")))
        val (rival, foreignCity) = rivalReligionIn(conqueror, 4, 0)
        foreignCity.religion.addPressure(religion.name, 1000)
        val before = secondCity.religion.getPressures()[religion.name]
        secondCity.religion.addPressure(rival.name, 5000)

        foreignCity.religion.removeAllPressuresExceptFor(rival.name)
        assertEquals("the conqueror does not own the holy city", 0, foreignCity.religion.getPressures()[religion.name])
        secondCity.religion.removeAllPressuresExceptFor(rival.name)
        assertEquals("the founder owns it", before / 2, secondCity.religion.getPressures()[religion.name])
    }
    //endregion

    //region gifts and Barbarians
    @Test
    fun `a gift of gold gets the belief bonus only from a city-state whose capital follows the donor's owned religion`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, "Gifts of Gold to City-States generate [100]% more Influence")))
        val cityState = game.addCiv(cityStateType = "Cultured")
        val capital = game.addCity(cityState, game.getTile(4, 0), initialPopulation = 3)
        val (rival, _) = rivalReligionIn(conqueror, -4, 0)
        capital.religion.addPressure(rival.name, 5000)
        val without = cityState.cityStateFunctions.influenceGainedByGift(founder, 500)
        capital.religion.addPressure(religion.name, 50000)
        val with = cityState.cityStateFunctions.influenceGainedByGift(founder, 500)
        assertTrue("$with > $without", with > without)
        // a gift bonus that is not a belief's counts whatever the religions
        val giftCiv = game.addCiv("Gifts of Gold to City-States generate [100]% more Influence")
        val plainCiv = game.addCiv()
        val plain = cityState.cityStateFunctions.influenceGainedByGift(plainCiv, 500)
        assertTrue(cityState.cityStateFunctions.influenceGainedByGift(giftCiv, 500) > plain)
        game.ruleset.modOptions.constants.religiousBeliefValidityLikeVp = false
        capital.religion.removeAllPressuresExceptFor(rival.name)
        assertEquals("without the switch any capital", with, cityState.cityStateFunctions.influenceGainedByGift(founder, 500))
    }

    @Test
    fun `Heathen Conversion belongs to the owner of the holy city`() {
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Enhancer, "[All] units convert adjacent Barbarian units")))
        val barbarians = game.addBarbarianCiv()
        holyCity.moveToCiv(conqueror)
        val founderUnit = game.addUnit("Warrior", founder, game.getTile(2, 2))
        val first = game.addUnit("Warrior", barbarians, game.getTile(2, 3))
        BarbarianConversion.convertAdjacentBarbarians(founderUnit)
        assertSame(barbarians, first.civ)
        val conquerorUnit = game.addUnit("Warrior", conqueror, game.getTile(-2, -2))
        val second = game.addUnit("Warrior", barbarians, game.getTile(-2, -3))
        BarbarianConversion.convertAdjacentBarbarians(conquerorUnit)
        assertSame(conqueror, second.civ)
    }
    //endregion
}
