package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The religious combat bonuses of Vox Populi with `religiousCombatLikeVp` (CvUnit::GetGenericMeleeStrengthModifier, V:CvUnit.cpp:16471-16507; the ranged copy in
 * CvUnit::GetMaxRangedCombatStrength, V:CvUnit.cpp:17336-17372; CvPlot::IsFriendlyTerritory, V:CvPlot.cpp:4943-4978).
 *
 * The religion carries two Reformation beliefs with distinct numbers, so that every sum names the terms in it: "defender" +10 in lands friendly to us and +5 more against
 * another state religion; "evangelism" +20 in lands friendly to the enemy and +40 more against another state religion. Sums: 10, 15, 20, 60, 30 (both lands, same
 * religion), 75 (both lands, other religion).
 */
@RunWith(GdxTestRunner::class)
class ReligiousCombatTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization
    private lateinit var religion: Religion
    private lateinit var holyCity: City
    private lateinit var theirCity: City

    private val ownLands = "[+10]% Strength for land units in territory friendly to us against units of major civilizations"
    private val ownLandsOther = "[+5]% Strength for land units in territory friendly to us against units of major civilizations with another state religion"
    private val theirLands = "[+20]% Strength for land units in territory friendly to the enemy against units of major civilizations"
    private val theirLandsOther = "[+40]% Strength for land units in territory friendly to the enemy against units of major civilizations with another state religion"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        game.ruleset.modOptions.constants.religiousCombatLikeVp = true
        us = game.addCiv()
        them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
        holyCity = game.addCity(us, game.getTile(0, 0), initialPopulation = 5)
        theirCity = game.addCity(them, game.getTile(6, 0), initialPopulation = 5)
        religion = game.addReligion(us)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder),
            game.createBelief(BeliefType.Reformation, ownLands, ownLandsOther),
            game.createBelief(BeliefType.Reformation, theirLands, theirLandsOther)))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        holyCity.religion.addPressure(religion.name, 1000)
    }

    /** A tile of [city] next to its centre without a military unit */
    private fun tileOf(city: City): Tile = city.getCenterTile().neighbors.first { it.getCity() == city && it.militaryUnit == null }

    private fun melee(civ: Civilization, tile: Tile) = MapUnitCombatant(game.addDefaultMeleeUnitWithUniques(civ, tile))

    private fun religiousAttack(attacker: MapUnitCombatant, defender: MapUnitCombatant) =
        BattleDamage.getAttackModifiers(attacker, defender, attacker.getTile())["State religion"]

    private fun religiousDefence(attacker: MapUnitCombatant, defender: MapUnitCombatant) =
        BattleDamage.getDefenceModifiers(attacker, defender, attacker.getTile())["State religion"]

    /** A city of [civ] whose citizens follow [religion], which makes it the state religion of a civilization with no other city */
    private fun followingCity(civ: Civilization, x: Int, y: Int): City {
        val city = game.addCity(civ, game.getTile(x, y), initialPopulation = 5)
        city.religion.addPressure(religion.name, 1000)
        return city
    }

    //region the battle tile and its territory

    @Test
    fun `a unit defending in its own lands against a civilization without a state religion gets both own-lands bonuses`() {
        val attacker = melee(them, game.getTile(0, -3)) // unowned
        val defender = melee(us, tileOf(holyCity))
        assertEquals(10 + 5, religiousDefence(attacker, defender))
    }

    @Test
    fun `a unit attacking into the lands of the enemy gets both their-lands bonuses`() {
        val attacker = melee(us, game.getTile(3, 0))
        val defender = melee(them, tileOf(theirCity))
        assertEquals(20 + 40, religiousAttack(attacker, defender))
    }

    @Test
    fun `the battle tile of an attack is the defender's tile, not the attacker's`() {
        // the attacker stands in its own lands, the defender on an unowned tile: friendly to nobody
        val attacker = melee(us, tileOf(holyCity))
        val defender = melee(them, game.getTile(0, -3))
        assertEquals(0, religiousAttack(attacker, defender))
    }

    @Test
    fun `a unit defending in the lands of the enemy gets the their-lands bonuses`() {
        val attacker = melee(them, game.getTile(3, 0))
        val defender = melee(us, tileOf(theirCity))
        assertEquals(20 + 40, religiousDefence(attacker, defender))
    }

    @Test
    fun `a ranged attack takes the target's tile`() {
        val attacker = MapUnitCombatant(game.addDefaultRangedUnitWithUniques(us, game.getTile(4, -2)))
        val defender = melee(them, tileOf(theirCity))
        assertEquals(20 + 40, religiousAttack(attacker, defender))
    }

    @Test
    fun `lands of a third civilization friendly to neither side give nothing`() {
        val third = game.addCiv()
        val thirdCity = game.addCity(third, game.getTile(0, 6))
        us.diplomacyFunctions.makeCivilizationsMeet(third)
        us.getDiplomacyManager(third)!!.declareWar() // so that our unit may stand there
        val attacker = melee(them, game.getTile(0, 3))
        val defender = melee(us, tileOf(thirdCity))
        assertEquals(0, religiousDefence(attacker, defender))
    }

    @Test
    fun `lands that open their borders to us count as ours, and the owner's own lands count as theirs too`() {
        // their tile: friendly to them (their own) and, with their open borders for us, friendly to us: both pairs
        us.getDiplomacyManager(them)!!.hasOpenBorders = true
        val attacker = melee(us, game.getTile(3, 0))
        val defender = melee(them, tileOf(theirCity))
        assertEquals(10 + 5 + 20 + 40, religiousAttack(attacker, defender))
    }

    @Test
    fun `the lands of a City-State count as ours only while we are its friend`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val csCity = game.addCity(cityState, game.getTile(0, 6))
        us.diplomacyFunctions.makeCivilizationsMeet(cityState)
        // open borders let our unit stand there; the City-State half of the original's test does not read them
        us.getDiplomacyManager(cityState)!!.hasOpenBorders = true
        val attacker = melee(them, game.getTile(0, 3))
        val defender = melee(us, tileOf(csCity))
        assertEquals(0, religiousDefence(attacker, defender))
        cityState.getDiplomacyManager(us)!!.setInfluence(35f) // Friends from 30
        assertEquals(10 + 5, religiousDefence(attacker, defender))
    }

    //endregion

    //region the other side

    @Test
    fun `against a civilization with the same state religion only the unconditional values count`() {
        theirCity.religion.addPressure(religion.name, 1000)
        assertSame(religion, them.religionManager.getStateReligion())
        val attackerInOurLands = melee(them, game.getTile(0, -3))
        assertEquals(10, religiousDefence(attackerInOurLands, melee(us, tileOf(holyCity))))
        assertEquals(20, religiousAttack(melee(us, game.getTile(3, 0)), melee(them, tileOf(theirCity))))
    }

    @Test
    fun `a unit of a City-State or of the barbarians gives no religious bonus`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val barbarians = game.addBarbarianCiv()
        val defender = melee(us, tileOf(holyCity))
        assertEquals(0, religiousDefence(melee(cityState, game.getTile(0, -3)), defender))
        assertEquals(0, religiousDefence(melee(barbarians, game.getTile(-3, 0)), defender))
    }

    //endregion

    //region our side

    @Test
    fun `a unit that is not a land unit gets nothing`() {
        val coast = game.setTileTerrain(tileOf(holyCity).position, Constants.coast)
        assertSame(holyCity, coast.getCity())
        val ship = MapUnitCombatant(game.addUnit(game.createBaseUnit("Melee Water").name, us, coast))
        val attacker = melee(them, game.getTile(0, -3))
        assertEquals(0, religiousDefence(attacker, ship))
    }

    @Test
    fun `a civilization without a state religion gets nothing`() {
        val stranger = game.addCiv()
        stranger.diplomacyFunctions.makeCivilizationsMeet(them)
        val strangerCity = game.addCity(stranger, game.getTile(-6, 0))
        val attacker = melee(them, game.getTile(-3, 0))
        assertEquals(0, religiousDefence(attacker, melee(stranger, tileOf(strangerCity))))
    }

    @Test
    fun `a civilization that has the religion as its state religion gets its Reformation bonuses though it does not own the holy city`() {
        val follower = game.addCiv()
        val followerCity = followingCity(follower, -6, 0)
        assertSame(religion, follower.religionManager.getStateReligion())
        val attacker = melee(them, game.getTile(-3, 0))
        assertEquals(10 + 5, religiousDefence(attacker, melee(follower, tileOf(followerCity))))
    }

    @Test
    fun `a Founder belief with the bonuses works only for the civilization that owns the holy city`() {
        // their religion has the their-lands value alone, on a Founder belief
        val founderReligion = game.addReligion(them)
        founderReligion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, theirLands)))
        val holy = game.addCity(them, game.getTile(6, -4), initialPopulation = 5)
        holy.religion.religionThisIsTheHolyCityOf = founderReligion.name
        holy.religion.addPressure(founderReligion.name, 5000)
        theirCity.religion.addPressure(founderReligion.name, 5000)
        val follower = game.addCiv()
        val followerCity = game.addCity(follower, game.getTile(-6, 0), initialPopulation = 5)
        followerCity.religion.addPressure(founderReligion.name, 5000)
        assertSame(founderReligion, follower.religionManager.getStateReligion())
        // both attack into our lands, friendly to their enemy
        val ourUnit = melee(us, tileOf(holyCity))
        assertEquals("the state religion does not give a Founder belief", 0, religiousAttack(melee(follower, game.getTile(-3, 0)), ourUnit))
        assertEquals("the owner of the holy city has it", 20, religiousAttack(melee(them, game.getTile(3, 0)), ourUnit))
    }

    @Test
    fun `a Reformation belief is lost while the holy city follows another religion`() {
        // IsBeliefValid with the holy city as the city: a Reformation belief needs the city's majority to be the religion
        val other = game.addReligion(them)
        other.addBeliefs(listOf(game.createBelief(BeliefType.Founder)))
        holyCity.religion.addPressure(other.name, 100000)
        assertSame("still the state religion: we own its holy city", religion, us.religionManager.getStateReligion())
        val attacker = melee(them, game.getTile(0, -3))
        assertEquals(0, religiousDefence(attacker, melee(us, tileOf(holyCity))))
    }

    @Test
    fun `without the switch there is no religious bonus`() {
        game.ruleset.modOptions.constants.religiousCombatLikeVp = false
        val attacker = melee(them, game.getTile(0, -3))
        assertEquals(0, religiousDefence(attacker, melee(us, tileOf(holyCity))))
    }

    //endregion

    //region the arithmetic and the territory test alone

    @Test
    fun `a pair counts only when one of its two values is positive`() {
        // V:CvUnit.cpp:16491: (iOwn > 0 || iOwnOtherReligion > 0); a negative value alone is not applied, but is with a positive partner
        assertEquals(0, ReligiousCombat.modifier(-5, 0, 0, 0, friendlyToUs = true, friendlyToThem = false, sameStateReligion = false))
        assertEquals(-5 + 8, ReligiousCombat.modifier(-5, 8, 0, 0, friendlyToUs = true, friendlyToThem = false, sameStateReligion = false))
        assertEquals(0, ReligiousCombat.modifier(0, 0, -7, 0, friendlyToUs = false, friendlyToThem = true, sameStateReligion = false))
        assertEquals(-7 + 9, ReligiousCombat.modifier(0, 0, -7, 9, friendlyToUs = false, friendlyToThem = true, sameStateReligion = false))
        // with the same religion the partner still opens the pair, and only the unconditional value is added
        assertEquals(-5, ReligiousCombat.modifier(-5, 8, 0, 0, friendlyToUs = true, friendlyToThem = false, sameStateReligion = true))
    }

    @Test
    fun `the free-intrusion trait makes City-State lands friendly except at war, and a City-State, barbarians or unowned land never count`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val csTile = tileOf(game.addCity(cityState, game.getTile(0, 6)))
        val intruder = game.addCiv("City-State territory always counts as friendly territory")
        intruder.diplomacyFunctions.makeCivilizationsMeet(cityState)
        assertTrue(ReligiousCombat.isFriendlyTerritory(csTile, intruder))
        intruder.getDiplomacyManager(cityState)!!.declareWar()
        assertFalse("at war", ReligiousCombat.isFriendlyTerritory(csTile, intruder))

        val otherCityState = game.addCiv(cityStateType = "Cultured")
        otherCityState.diplomacyFunctions.makeCivilizationsMeet(us)
        otherCityState.getDiplomacyManager(us)!!.hasOpenBorders = true // the engine lets a City-State enter its ally's lands; the original's test does not count them
        assertFalse("a City-State in its ally's lands", ReligiousCombat.isFriendlyTerritory(tileOf(holyCity), otherCityState))
        assertTrue(ReligiousCombat.isFriendlyTerritory(tileOf(holyCity), us))
        assertFalse("barbarians", ReligiousCombat.isFriendlyTerritory(tileOf(holyCity), game.addBarbarianCiv()))
        assertFalse("unowned", ReligiousCombat.isFriendlyTerritory(game.getTile(0, -3), us))
    }

    //endregion
}
