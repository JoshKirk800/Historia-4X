package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Vox Populi's warmonger system (Release-5.4.8, CvDiplomacyAI.cpp: CvDiplomacyAIHelpers::GetCityWarmongerValue, GetCityLiberationValue,
 *  GetWarmongerTriggerPenalty, ApplyWarmongerPenalties, ApplyLiberationBonuses; CvDiplomacyAI::DoUpdateWarmongerThreats,
 *  GetOtherPlayerWarmongerScore, ChangeOtherPlayerNum*Attacked, ChangeOtherPlayerNumPlayersNuked) with the database values: the amounts
 *  are in hundredths, every Ancient era game is scaled by 50%.
 *
 *  a conquers (or liberates) a city of b; c watches. Everyone is in the Ancient era; c sees a as an average military and b as an
 *  average one, a is a close neighbour of c, nobody is friends or enemies, unless a test says otherwise.
 */
@RunWith(GdxTestRunner::class)
class WarmongerTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var lost: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        game.addCity(b, game.getTile(HexCoord(10, -2)))
        lost = game.addCity(b, game.getTile(HexCoord(10, 2)))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        seesAs(c, a, StrengthLevel.Average)
        seesAs(c, b, StrengthLevel.Average)
        seesAs(b, a, StrengthLevel.Average)
        a.proximity[c.civID] = Proximity.Close
    }

    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun seesAs(observer: Civilization, other: Civilization, strength: StrengthLevel) {
        observer.ai.warStates.militaryStrengths[other.civID] = strength
        observer.ai.warStates.rawMilitaryStrengths[other.civID] = strength
    }

    /** A median that makes [city]'s worth 25 for [owner]: its economic value is half of it, half of the percentage 50 */
    private fun medianForShare25(city: City, owner: Civilization): Int {
        val value = AttackTargetsAi.economicValue(city, owner)
        assertTrue("the test city has an economic value", value > 0)
        return value * 2
    }

    private fun warmonger(observer: Civilization, other: Civilization, city: City = lost) =
        WarmongerAi.cityWarmongerValue(city, other, city.civ, observer, medianForShare25(city, city.civ))

    private fun amount(observer: Civilization, other: Civilization) = observer.ai.diplomacy.memoryOf(other).warmongerAmountTimes100

    private fun setEra(eraNumber: Int) {
        val era = game.ruleset.eras.values.first { it.eraNumber == eraNumber }
        for (civ in game.gameInfo.civilizations) civ.tech.era = era
    }

    //region GetCityWarmongerValue

    @Test
    fun `a city taken is worth a quarter at 25, x 133% for an average conqueror and 150% for a close one, x 50% in the Ancient era`() {
        // 25 -> 25 x 133 / 100 = 33 -> 33 x 150 / 100 = 49 -> 49 x 50 / 100 = 24
        assertEquals(24, warmonger(c, a))
    }

    @Test
    fun `a city is worth between 5 and 100 percent of the median city`() {
        val value = AttackTargetsAi.economicValue(lost, b)
        // Far below the median: 5; far above: 100 (x 133 x 150 x 50 / 100 each time)
        assertEquals(5 * 133 / 100 * 150 / 100 * 50 / 100, WarmongerAi.cityWarmongerValue(lost, a, b, c, value * 1000))
        assertEquals(100 * 133 / 100 * 150 / 100 * 50 / 100, WarmongerAi.cityWarmongerValue(lost, a, b, c, 1))
    }

    @Test
    fun `an original capital counts half again, and a city the conqueror owned before a third as much`() {
        lost.isOriginalCapital = true
        // 25 x 150 / 100 = 37 -> 49 -> 65 -> 32
        assertEquals(37 * 133 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
        lost.isOriginalCapital = false
        lost.addTimeOwnedBy(a.civID)
        lost.addTimeOwnedBy(b.civID)
        assertEquals(2, lost.getNumTimesOwned(b.civID))
        // Owned once before: 25 / 3 = 8
        assertEquals(8 * 133 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `taking back its own city, or the one it owned last, or a barbarian's, is no warmongering`() {
        val own = game.addCity(a, game.getTile(HexCoord(0, 6)))
        own.puppetCity(b)
        assertEquals("a founded it", 0, WarmongerAi.cityWarmongerValue(own, a, b, c, medianForShare25(own, b)))
        lost.previousOwner = a.civID
        assertEquals("a owned it last", 0, warmonger(c, a))
        lost.previousOwner = ""
        assertEquals(24, warmonger(c, a))
        val barbarians = game.addBarbarianCiv()
        assertEquals(0, WarmongerAi.cityWarmongerValue(lost, a, barbarians, c, 1))
    }

    @Test
    fun `an observer that has not met both players holds nothing against the conqueror`() {
        val d = neutralCiv(HexCoord(0, 8))
        assertEquals(0, warmonger(d, a))
        d.diplomacyFunctions.makeCivilizationsMeet(a)
        assertEquals("it has not met the owner", 0, warmonger(d, a))
        d.diplomacyFunctions.makeCivilizationsMeet(b)
        seesAs(d, a, StrengthLevel.Average)
        seesAs(d, b, StrengthLevel.Average)
        assertTrue(warmonger(d, a) > 0)
    }

    @Test
    fun `a strong conqueror is charged more and a strong victim less, never below a quarter`() {
        seesAs(c, a, StrengthLevel.Immense) // +100
        seesAs(c, b, StrengthLevel.Poor) // -0
        assertEquals(25 * 200 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
        seesAs(c, b, StrengthLevel.Strong) // -25
        assertEquals(25 * 175 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
        seesAs(c, a, StrengthLevel.Pathetic) // -50 and the victim immense -75: below 25%
        seesAs(c, b, StrengthLevel.Immense)
        assertEquals(25 * 25 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `friends of the owner charge more, friends of the conqueror less, enemies of the owner less and of the conqueror more`() {
        val ownerFriends = 25 * 133 / 100 * (100 + 25 + 50) / 100 * 50 / 100
        c.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        c.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.Friends
        assertEquals(ownerFriends, warmonger(c, a))
        c.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.Allies
        assertEquals(25 * 133 / 100 * (100 + 50 + 50) / 100 * 50 / 100, warmonger(c, a))
        c.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        c.ai.diplomacy.memoryOf(b).doFLevel = DoFLevel.New
        assertEquals(24, warmonger(c, a))
        // A denounced conqueror (+25), one that denounced us too (+25): 100 + 50 + 50
        c.getDiplomacyManager(a)!!.denounce()
        assertEquals(25 * 133 / 100 * (100 + 25 + 50) / 100 * 50 / 100, warmonger(c, a))
        a.getDiplomacyManager(c)!!.denounce()
        assertEquals(25 * 133 / 100 * (100 + 25 + 25 + 50) / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `proximity counts for the conquerors distance to the observer`() {
        a.proximity[c.civID] = Proximity.Neighbors // +75
        assertEquals(25 * 133 / 100 * 175 / 100 * 50 / 100, warmonger(c, a))
        a.proximity[c.civID] = Proximity.Far // +25
        assertEquals(25 * 133 / 100 * 125 / 100 * 50 / 100, warmonger(c, a))
        a.proximity[c.civID] = Proximity.Distant // -25
        assertEquals(25 * 133 / 100 * 75 / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `at war with the owner it is worth less, and nothing when we are losing that war`() {
        c.getDiplomacyManager(b)!!.declareWar()
        // 25 x 75 / 100 = 18 -> 23 -> 34, no war score yet -> 17
        assertEquals(18 * 133 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
        // Our war score against b is negative when we lost more to it than it lost to us
        c.ai.diplomacy.memoryOf(b).warValueLost = 500
        assertTrue(WarDamageAi.warScore(c, b) < 0)
        assertEquals("a conqueror that hurts our enemy has done us a favour", 0, warmonger(c, a))
    }

    @Test
    fun `a victim that threatens the observer more than the conqueror does is a lesser crime`() {
        WarDamageAi.setMostRecentWarScore(c, b, -40)
        // The owner's recent war score -40 against the conqueror's 0: x 60%
        assertEquals(49 * 60 / 100 * 50 / 100, warmonger(c, a))
        WarDamageAi.setMostRecentWarScore(c, b, 0)
        WarDamageAi.setMostRecentWarScore(c, a, -90) // the conqueror lost to us before: the owner's 0 against -90 is no discount, capped at 100%
        assertEquals(24, warmonger(c, a))
        WarDamageAi.setMostRecentWarScore(c, a, 0)
        WarDamageAi.setMostRecentWarScore(c, b, -90)
        assertEquals("a quarter at the least", 49 * 25 / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `the loss is the observer's own when it owns the city - twice as much, with no strength discount, politics or threat discount`() {
        seesAs(b, a, StrengthLevel.Pathetic) // -50 would discount an outsider
        // 25 x 200 / 100 = 50; strength -50 is dropped; politics and threat are not applied; era 50%
        assertEquals(50 * 50 / 100, WarmongerAi.cityWarmongerValue(lost, a, b, b, medianForShare25(lost, b)))
        // A defensive pact partner feels the same, but only the discounts are dropped: an average conqueror still adds 33
        c.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.DefensivePact
        assertEquals(50 * 133 / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `a coop war with the conqueror against the owner is worth a tenth, and with the owner against the conqueror counts as our loss`() {
        CoopWarAi.entry(c, a, b).state = CoopWarState.Accepted
        // 25 x 10 / 100 = 2 -> 2 x 133 / 100 = 2 -> x 150 / 100 = 3 -> 1
        assertEquals(2 * 133 / 100 * 150 / 100 * 50 / 100, warmonger(c, a))
        CoopWarAi.entry(c, a, b).state = null
        CoopWarAi.entry(c, b, a).state = CoopWarState.Accepted
        assertEquals(50 * 133 / 100 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `a resurrection halves it for the observer the conqueror resurrected and doubles it for one resurrected by the owner`() {
        c.ai.diplomacy.memoryOf(a).resurrectedUs = true
        assertEquals(49 / 2 * 50 / 100, warmonger(c, a))
        c.ai.diplomacy.memoryOf(a).resurrectedUs = false
        c.ai.diplomacy.memoryOf(b).resurrectedUs = true
        assertEquals(49 * 2 * 50 / 100, warmonger(c, a))
    }

    @Test
    fun `the era scales it from 50 percent in the Ancient era to 70 in the Medieval`() {
        setEra(2)
        assertEquals(49 * 70 / 100, warmonger(c, a))
    }

    @Test
    fun `a city-state's friends and protectors charge more, strangers less`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val csCity = game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        c.diplomacyFunctions.makeCivilizationsMeet(cityState)
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        seesAs(c, cityState, StrengthLevel.Poor)
        fun value() = WarmongerAi.cityWarmongerValue(csCity, a, cityState, c, medianForShare25(csCity, cityState))
        // A stranger: -25, and -25 more for being neither an ally nor protected: 50%
        cityState.getDiplomacyManager(c)!!.setInfluenceWithoutSideEffects(0f)
        assertEquals(25 * 133 / 100 * 50 / 100 * 50 / 100, value())
        // A friend: +50, but -25 for not being an ally or protected: 125%
        cityState.getDiplomacyManager(c)!!.setInfluenceWithoutSideEffects(35f)
        assertEquals(25 * 133 / 100 * 125 / 100 * 50 / 100, value())
        // An ally and protector: +75 and +50, capped at 200%
        cityState.getDiplomacyManager(c)!!.setInfluenceWithoutSideEffects(65f)
        cityState.getDiplomacyManager(c)!!.diplomaticStatus = DiplomaticStatus.Protector
        assertEquals(25 * 133 / 100 * 200 / 100 * 50 / 100, value())
    }
    //endregion

    //region ApplyWarmongerPenalties

    @Test
    fun `every other major holds the city's value against the conqueror and its threat is updated at once`() {
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 10)
        val before = amount(c, a)
        WarmongerAi.applyWarmongerPenalties(lost, a, b)
        val median = WarmongerAi.medianEconomicValue(game.gameInfo)
        assertEquals(WarmongerAi.cityWarmongerValue(lost, a, b, c, median) * 100, amount(c, a) - before)
        assertEquals(WarmongerAi.cityWarmongerValue(lost, a, b, b, median) * 100, amount(b, a))
        assertEquals("the conqueror holds nothing against itself", 0, amount(a, a))
        c.ai.diplomacy.memoryOf(a).warmongerAmountTimes100 = 40000
        WarmongerAi.updateThreats(c, updateOnly = true)
        assertEquals(AiThreat.Critical, c.ai.diplomacy.warmongerThreat(a))
    }

    @Test
    fun `taking a city is counted by the conquest event, not by a city the conqueror founded`() {
        DiplomacyEvents.onCityConquered(a, b, lost)
        assertTrue(amount(c, a) > 0)
        val own = game.addCity(a, game.getTile(HexCoord(0, 6)))
        own.puppetCity(b)
        val before = amount(c, a)
        DiplomacyEvents.onCityConquered(a, b, own)
        assertEquals(before, amount(c, a))
    }

    @Test
    fun `a city that changes hands counts one more time owned by its new owner, the founder's first one included`() {
        assertEquals(1, lost.getNumTimesOwned(b.civID))
        assertEquals(0, lost.getNumTimesOwned(a.civID))
        lost.puppetCity(a)
        assertEquals(1, lost.getNumTimesOwned(a.civID))
        assertEquals(1, lost.getNumTimesOwned(b.civID))
        lost.puppetCity(b)
        assertEquals(2, lost.getNumTimesOwned(b.civID))
    }
    //endregion

    //region GetCityLiberationValue

    /** A city of b that d holds, which a is taking from d to give back to b (the city is still d's, as at the moment of the decision) */
    private fun liberated(at: HexCoord = HexCoord(10, 6)): City {
        val d = game.gameInfo.civilizations.firstOrNull { it.isMajorCiv && it != a && it != b && it != c } ?: neutralCiv(HexCoord(0, 8))
        val city = game.addCity(b, game.getTile(at))
        city.puppetCity(d)
        return city
    }

    private fun liberation(observer: Civilization, city: City) =
        WarmongerAi.cityLiberationValue(city, a, b, observer, medianForShare25(city, a))

    @Test
    fun `liberating a city is worth the same as taking it, negated`() {
        // -25 x 133 / 100 = -33 -> x 150 / 100 = -49 -> x 50 / 100 = -24
        val city = liberated()
        assertEquals(-24, liberation(c, city))
    }

    @Test
    fun `a weak liberator earns more and a strong one less`() {
        val city = liberated()
        seesAs(c, a, StrengthLevel.Pathetic) // +100
        assertEquals(-25 * 200 / 100 * 150 / 100 * 50 / 100, liberation(c, city))
        seesAs(c, a, StrengthLevel.Immense) // -50
        assertEquals(-25 * 50 / 100 * 150 / 100 * 50 / 100, liberation(c, city))
    }

    @Test
    fun `the liberated player itself gets four times, with nothing discounted`() {
        val city = liberated()
        seesAs(b, a, StrengthLevel.Immense) // -50 is dropped when the gain is our own
        // 25: x (200 + 200) = 100, the liberator's immense -> inverted pathetic -50 dropped; x 50%
        assertEquals(-100 * 50 / 100, WarmongerAi.cityLiberationValue(city, a, b, b, medianForShare25(city, a)))
    }

    @Test
    fun `no bonus from an observer that owned the city, or fights the liberated player`() {
        val city = liberated()
        city.everOwnedBy.add(c.civID)
        city.addTimeOwnedBy(c.civID)
        assertEquals(0, liberation(c, city))
        val other = liberated(HexCoord(10, 8))
        c.getDiplomacyManager(b)!!.declareWar()
        assertEquals(0, liberation(c, other))
    }

    @Test
    fun `a friend of the liberator and of the liberated player cheers more, a resurrection doubles it`() {
        val city = liberated()
        a.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        c.ai.diplomacy.memoryOf(a).doFLevel = DoFLevel.Friends // the liberator's friends +25: 100 + 25 + 50
        assertEquals(-25 * 133 / 100 * 175 / 100 * 50 / 100, liberation(c, city))
        c.ai.diplomacy.memoryOf(b).resurrectedUs = true
        assertEquals(-25 * 133 / 100 * 175 / 100 * 2 * 50 / 100, liberation(c, city))
    }

    @Test
    fun `the first liberation by a civilization lowers what the others hold against it, the second one does not`() {
        val city = liberated()
        c.ai.diplomacy.memoryOf(a).warmongerAmountTimes100 = 10000
        DiplomacyEvents.onCityLiberated(a, b, city, receiverWasAlive = true)
        val median = WarmongerAi.medianEconomicValue(game.gameInfo)
        val after = amount(c, a)
        assertTrue(after < 10000)
        assertEquals(10000 + WarmongerAi.cityLiberationValue(city, a, b, c, median) * 100, after)
        DiplomacyEvents.onCityLiberated(a, b, city, receiverWasAlive = true)
        assertEquals("giving the same city back again earns nothing", after, amount(c, a))
        assertEquals("and counts as one city ever liberated", 1, b.ai.diplomacy.memoryOf(a).numCitiesEverLiberated)
        assertEquals(2, b.ai.diplomacy.memoryOf(a).numCitiesLiberated)
    }
    //endregion

    //region Trigger penalties

    @Test
    fun `declaring war on a major is 1500 hundredths, more for a strong attacker, 50 percent in the Ancient era`() {
        seesAs(c, a, StrengthLevel.Strong) // +50
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(1500 * 150 / 100 * 50 / 100, amount(c, a))
        assertEquals("the victim counts it too", 1500 * (100 + 33) / 100 * 50 / 100, amount(b, a))
        assertEquals(1, c.ai.diplomacy.memoryOf(a).numMajorsAttacked)
    }

    @Test
    fun `each earlier attack the observer has seen adds 500, and a strong victim is discounted for outsiders only`() {
        c.ai.diplomacy.memoryOf(a).numMajorsAttacked = 2
        b.ai.diplomacy.memoryOf(a).numMajorsAttacked = 2
        seesAs(c, b, StrengthLevel.Strong) // -25
        assertEquals((1500 + 2 * 500) * (100 + 33 - 25) / 100 * 50 / 100,
            WarmongerAi.triggerPenalty(a, b, c, WarmongerAi.Trigger.MajorAttacked))
        assertEquals("the victim itself has no defender discount",
            (1500 + 2 * 500) * (100 + 33) / 100 * 50 / 100, WarmongerAi.triggerPenalty(a, b, b, WarmongerAi.Trigger.MajorAttacked))
    }

    @Test
    fun `an observer already at war with the victim, or planning war on it, does not count the attack`() {
        c.getDiplomacyManager(b)!!.declareWar()
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(0, amount(c, a))
        val d = neutralCiv(HexCoord(0, 8))
        d.diplomacyFunctions.makeCivilizationsMeet(a)
        d.diplomacyFunctions.makeCivilizationsMeet(b)
        d.ai.diplomacy.memoryOf(b).approach = MajorCivApproach.War
        DiplomacyEvents.onWarDeclared(a, b, isDefensivePact = false)
        assertEquals("it wanted war on b itself", 0, amount(d, a))
    }

    @Test
    fun `an observer locked into a coop war against the victim does not count it either, a defensive pact war counts for nobody`() {
        val d = neutralCiv(HexCoord(0, 8))
        d.diplomacyFunctions.makeCivilizationsMeet(a)
        d.diplomacyFunctions.makeCivilizationsMeet(b)
        CoopWarAi.entry(d, c, b).state = CoopWarState.Accepted
        CoopWarAi.entry(d, c, b).counter = 0
        DiplomacyEvents.onWarDeclared(a, b, isDefensivePact = false)
        assertEquals(0, amount(d, a))
        assertTrue(amount(c, a) > 0)
        val before = amount(c, a)
        DiplomacyEvents.onWarDeclared(a, b, isDefensivePact = true)
        assertEquals(before, amount(c, a))
    }

    @Test
    fun `a war on a city-state is 750, more for each one attacked before, and one attacked again within 50 turns counts nothing new`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        c.diplomacyFunctions.makeCivilizationsMeet(cityState)
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        a.getDiplomacyManager(cityState)!!.declareWar()
        assertEquals(750 * 133 / 100 * 50 / 100, amount(c, a))
        assertEquals(1, c.ai.diplomacy.memoryOf(a).numMinorsAttacked)
        game.gameInfo.turns += 10
        DiplomacyEvents.onWarDeclared(a, cityState, isDefensivePact = false)
        assertEquals("within 50 turns", 750 * 133 / 100 * 50 / 100, amount(c, a))
        game.gameInfo.turns += 50
        DiplomacyEvents.onWarDeclared(a, cityState, isDefensivePact = false)
        assertEquals(750 * 133 / 100 * 50 / 100 + (750 + 250) * 133 / 100 * 50 / 100, amount(c, a))
    }

    @Test
    fun `a nuke on a player is 4000 and 2000 more for each before, and every major notes it`() {
        DiplomacyEvents.onNuked(b, a)
        assertEquals(4000 * 133 / 100 * 50 / 100, amount(c, a))
        assertEquals(1, c.ai.diplomacy.memoryOf(a).numPlayersNuked)
        assertEquals(1, b.ai.diplomacy.memoryOf(a).numTimesNuked)
        DiplomacyEvents.onNuked(b, a)
        assertEquals(4000 * 133 / 100 * 50 / 100 + (4000 + 2000) * 133 / 100 * 50 / 100, amount(c, a))
    }
    //endregion

    //region Scores and threats

    @Test
    fun `the score is the amount in whole points x WarmongerHate over 20, with no floor`() {
        c.ai.diplomacy.memoryOf(a).warmongerAmountTimes100 = 40099
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 10)
        assertEquals(400 * 10 / 20, WarmongerAi.warmongerScore(c, a))
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 1)
        assertEquals(20, WarmongerAi.warmongerScore(c, a))
        c.ai.diplomacy.memoryOf(a).warmongerAmountTimes100 = 800
        assertEquals("under 5 is still an opinion term", 0, WarmongerAi.warmongerScore(c, a))
        c.ai.diplomacy.memoryOf(a).warmongerAmountTimes100 = 10000
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 10)
        assertEquals(50, OpinionAi.warmongerThreatScore(c, a))
    }

    @Test
    fun `the threat is minor from 20, major from 80, severe from 140 and critical from 200`() {
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 10)
        fun threatAt(score: Int): AiThreat {
            c.ai.diplomacy.memoryOf(a).warmongerAmountTimes100 = score * 2 * 100 // the score is the amount x 10 / 20
            WarmongerAi.updateThreats(c, updateOnly = true)
            return c.ai.diplomacy.warmongerThreat(a)
        }
        assertEquals(AiThreat.None, threatAt(19))
        assertEquals(AiThreat.Minor, threatAt(20))
        assertEquals(AiThreat.Minor, threatAt(79))
        assertEquals(AiThreat.Major, threatAt(80))
        assertEquals(AiThreat.Major, threatAt(139))
        assertEquals(AiThreat.Severe, threatAt(140))
        assertEquals(AiThreat.Severe, threatAt(199))
        assertEquals(AiThreat.Critical, threatAt(200))
    }

    @Test
    fun `without updateOnly the amount first decays by 200 hundredths x the other's raw strength, doubled for friends`() {
        val memory = c.ai.diplomacy.memoryOf(a)
        memory.warmongerAmountTimes100 = 10000
        seesAs(c, a, StrengthLevel.Average) // 150%
        WarmongerAi.updateThreats(c, updateOnly = false)
        assertEquals(10000 - 300, memory.warmongerAmountTimes100)
        seesAs(c, a, StrengthLevel.Immense) // 50%, and at least 100
        WarmongerAi.updateThreats(c, updateOnly = false)
        assertEquals(9700 - 100, memory.warmongerAmountTimes100)
        seesAs(c, a, StrengthLevel.Pathetic) // 300%
        WarmongerAi.updateThreats(c, updateOnly = false)
        assertEquals(9600 - 600, memory.warmongerAmountTimes100)
        c.getDiplomacyManager(a)!!.signDeclarationOfFriendship()
        seesAs(c, a, StrengthLevel.Strong) // 100% x 200%
        WarmongerAi.updateThreats(c, updateOnly = false)
        assertEquals(9000 - 400, memory.warmongerAmountTimes100)
        WarmongerAi.updateThreats(c, updateOnly = true)
        assertEquals("an update only does not decay", 8600, memory.warmongerAmountTimes100)
    }

    @Test
    fun `the amount does not decay below nothing, and a player we do not know or that is dead is no threat`() {
        val memory = c.ai.diplomacy.memoryOf(a)
        memory.warmongerAmountTimes100 = 150
        WarmongerAi.updateThreats(c, updateOnly = false)
        assertEquals(0, memory.warmongerAmountTimes100)
        memory.warmongerThreat = AiThreat.Severe
        val d = neutralCiv(HexCoord(0, 8))
        d.ai.diplomacy.memoryOf(a).warmongerThreat = AiThreat.Severe
        WarmongerAi.updateThreats(d, updateOnly = true)
        assertEquals("d has not met a", AiThreat.None, d.ai.diplomacy.warmongerThreat(a))
    }

    @Test
    fun `a third of the majors ever killed is critical whatever the score, a quarter severe, unless that player resurrected us`() {
        val d = neutralCiv(HexCoord(0, 8))
        d.diplomacyFunctions.makeCivilizationsMeet(a)
        d.diplomacyFunctions.makeCivilizationsMeet(c)
        val aggression = c.ai.aggressionOf(a)
        aggression.majorsConquered = 1 // 1 of 4 majors: 25%
        WarmongerAi.updateThreats(c, updateOnly = true)
        assertEquals(AiThreat.Severe, c.ai.diplomacy.warmongerThreat(a))
        aggression.majorsConquered = 0
        aggression.minorsConquered = 2 // half a major each: 1 of 4 again
        WarmongerAi.updateThreats(c, updateOnly = true)
        assertEquals(AiThreat.Severe, c.ai.diplomacy.warmongerThreat(a))
        aggression.majorsConquered = 2 // 2 and 1 of 4: 75%
        WarmongerAi.updateThreats(c, updateOnly = true)
        assertEquals(AiThreat.Critical, c.ai.diplomacy.warmongerThreat(a))
        c.ai.diplomacy.memoryOf(a).resurrectedUs = true
        WarmongerAi.updateThreats(c, updateOnly = true)
        assertEquals("a resurrector's kills are forgiven", AiThreat.None, c.ai.diplomacy.warmongerThreat(a))
    }
    //endregion

    //region The most recent war score

    @Test
    fun `peace notes each side's war score, which decays by one a turn towards nothing`() {
        a.getDiplomacyManager(b)!!.declareWar()
        WarDamageAi.applyWarDamage(a, b, 100)
        val score = WarDamageAi.warScore(a, b)
        assertTrue(score > 0)
        a.getDiplomacyManager(b)!!.makePeace()
        assertEquals(score, WarDamageAi.mostRecentWarScore(a, b))
        assertEquals(-score, WarDamageAi.mostRecentWarScore(b, a))
        WarDamageAi.doTurn(a)
        assertEquals(score - 1, WarDamageAi.mostRecentWarScore(a, b))
        WarDamageAi.doTurn(b)
        assertEquals(-score + 1, WarDamageAi.mostRecentWarScore(b, a))
    }

    @Test
    fun `killing a player is a won war of 100 for everyone at war with it, and nobody holds its warmongering against it any more`() {
        c.getDiplomacyManager(b)!!.declareWar()
        c.ai.diplomacy.memoryOf(b).warmongerAmountTimes100 = 5000
        a.ai.diplomacy.memoryOf(b).warmongerAmountTimes100 = 5000
        DiplomacyEvents.onLastCityTaken(a, b)
        assertEquals(100, WarDamageAi.mostRecentWarScore(c, b))
        assertEquals(-100, WarDamageAi.mostRecentWarScore(b, c))
        assertEquals(0, amount(c, b))
        assertEquals(0, amount(a, b))
    }
    //endregion
}
