package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvDiplomacyAI port (major civs) against the DLL formulas with the BNW XML values */
@RunWith(GdxTestRunner::class)
class DiplomacyAiTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        meet(a, b)
        meet(a, c)
        meet(b, c)
    }

    /** A major with one city whose personality is all zero */
    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun meet(x: Civilization, y: Civilization) = x.diplomacyFunctions.makeCivilizationsMeet(y)

    private fun weights(civ: Civilization, other: Civilization, old: MajorCivApproach?) =
        ApproachAi.approachWeights(civ, other, old, rules)

    private fun modifier(civ: Civilization, other: Civilization, modifier: DiplomaticModifiers): Float {
        OpinionAi.updateOpinion(civ, other, rules)
        return civ.getDiplomacyManager(other)!!.diplomaticModifiers[modifier.name] ?: 0f
    }

    //region Opinion

    @Test
    fun `opinion levels follow the OPINION_THRESHOLD defines, inclusive on the unfriendly side and strict on the friendly one`() {
        // DoUpdateOnePlayerOpinion: >= 160 unforgivable, >= 80 enemy, >= 30 competitor, > -30 neutral, > -80 favorable, > -160 friend
        assertEquals(MajorCivOpinion.Unforgivable, OpinionAi.opinionLevel(160, rules))
        assertEquals(MajorCivOpinion.Enemy, OpinionAi.opinionLevel(159, rules))
        assertEquals(MajorCivOpinion.Competitor, OpinionAi.opinionLevel(30, rules))
        assertEquals(MajorCivOpinion.Neutral, OpinionAi.opinionLevel(29, rules))
        assertEquals(MajorCivOpinion.Neutral, OpinionAi.opinionLevel(-29, rules))
        assertEquals(MajorCivOpinion.Favorable, OpinionAi.opinionLevel(-30, rules))
        assertEquals(MajorCivOpinion.Friend, OpinionAi.opinionLevel(-80, rules))
        assertEquals(MajorCivOpinion.Ally, OpinionAi.opinionLevel(-160, rules))
    }

    @Test
    fun `a declaration of friendship weighs 30, being denounced 50, both denouncing each other 50, our own denouncement nothing`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertEquals(30f, modifier(a, b, DiplomaticModifiers.DeclarationOfFriendship), 0.001f)
        assertEquals(30f, modifier(b, a, DiplomaticModifiers.DeclarationOfFriendship), 0.001f)

        a.getDiplomacyManager(c)!!.denounce()
        assertEquals(-50f, modifier(c, a, DiplomaticModifiers.Denunciation), 0.001f)
        assertEquals(0f, modifier(a, c, DiplomaticModifiers.Denunciation), 0.001f)
        c.getDiplomacyManager(a)!!.denounce()
        assertEquals(-50f, modifier(a, c, DiplomaticModifiers.Denunciation), 0.001f)
        // C is no friend of B's: B does not mind A denouncing C
        assertEquals(0f, modifier(b, a, DiplomaticModifiers.DenouncedOurAllies), 0.001f)
    }

    @Test
    fun `denouncing a friend ends the friendship and the friend remembers`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        a.getDiplomacyManager(b)!!.denounce()
        assertFalse(a.getDiplomacyManager(b)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertFalse(b.getDiplomacyManager(a)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertTrue(b.ai.diplomacy.memoryOf(a).friendDenouncedUs)
        // GetRelationshipDuration of a standard game: the denouncement lasts 50 turns
        assertEquals(50, a.getDiplomacyManager(b)!!.getFlag(DiplomacyFlags.Denunciation))
    }

    @Test
    fun `land disputes weigh by boldness and the difficulty, more for a conqueror`() {
        // Prince: LandDisputePercent 80; boldness 6: a dispute x 480 / 750, none x 480 / 500
        a.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 6)
        a.ai.primaryVictoryPursuit = null
        val memory = a.ai.diplomacy.memoryOf(b)
        memory.landDispute = DisputeLevel.Fierce
        assertEquals("40 x 480 / 750", -25f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
        memory.landDispute = DisputeLevel.Weak
        assertEquals("15 x 480 / 750", -9f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
        memory.landDispute = DisputeLevel.None
        assertEquals("-10 x 480 / 500", 9f, modifier(a, b, DiplomaticModifiers.NoLandDispute), 0.001f)
        assertEquals(0f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
        // A conqueror: +10 to a dispute, (-5 - neediness 0) x era 1 / 2 to none
        a.ai.primaryVictoryPursuit = VictoryPursuit.Domination
        assertEquals("-12 x 480 / 500", 11f, modifier(a, b, DiplomaticModifiers.NoLandDispute), 0.001f)
        memory.landDispute = DisputeLevel.Fierce
        assertEquals("50 x 480 / 750", -32f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
    }

    @Test
    fun `being nuked costs the full nuke weight and each liberated city 30`() {
        b.ai.diplomacy.memoryOf(a).numTimesNuked = 1
        assertEquals(-100f, modifier(b, a, DiplomaticModifiers.UsedNuclearWeapons), 0.001f)
        val memory = b.ai.diplomacy.memoryOf(a)
        memory.numCitiesLiberated = 1
        assertEquals(30f, modifier(b, a, DiplomaticModifiers.LiberatedCity), 0.001f)
        memory.numCitiesLiberated = 2
        assertEquals(60f, modifier(b, a, DiplomaticModifiers.LiberatedCity), 0.001f)
    }

    @Test
    fun `city-states we befriend and they ally make a dispute that grows the closer our influences are`() {
        // Competitiveness 10: 100 a city-state; we are friendly towards it, b is its ally: 100 / 2 + our influence / 5, then 100 x 4
        // with influences within 20, x 3.5 within 40
        a.ai.setDiplomacyValue(DiplomacyPersonality.MinorCivCompetitiveness, 10)
        fun cityState(ours: Float, theirs: Float): Civilization {
            val cs = game.addCiv(cityStateType = "Cultured")
            game.addCity(cs, game.getTile(HexCoord(-6 + game.gameInfo.civilizations.count { it.isCityState } * 4, -8)))
            meet(a, cs)
            meet(b, cs)
            cs.getDiplomacyManager(a)!!.setInfluenceWithoutSideEffects(ours)
            cs.getDiplomacyManager(b)!!.setInfluenceWithoutSideEffects(theirs)
            cs.allyCiv = b
            a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Friendly
            return cs
        }
        fun level(): DisputeLevel {
            DisputeAi.updateMinorCivDisputes(a, listOf(b), rules)
            return a.ai.diplomacy.memoryOf(b).minorCivDispute
        }
        val first = cityState(50f, 60f)
        assertEquals("50 + 10 + 400 = 460", DisputeLevel.None, level())
        cityState(50f, 60f)
        assertEquals("2 x 460 = 920", DisputeLevel.Weak, level())
        first.getDiplomacyManager(b)!!.setInfluenceWithoutSideEffects(85f)
        assertEquals("460 + 50 + 10 + 350 = 870", DisputeLevel.Weak, level())
        first.getDiplomacyManager(a)!!.setInfluenceWithoutSideEffects(20f)
        assertEquals("a city-state that is not our friend does not count", DisputeLevel.None, level())
    }
    //endregion

    //region Warmonger

    @Test
    fun `declaring war on a major adds 250 warmonger to observers not at war with the victim`() {
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 8)
        a.getDiplomacyManager(b)!!.declareWar()
        // WARMONGER_THREAT_MAJOR_ATTACKED_WEIGHT, and the victim counts it too
        assertEquals(250, c.ai.diplomacy.memoryOf(a).warmongerAmount)
        assertEquals(250, b.ai.diplomacy.memoryOf(a).warmongerAmount)
        // Score = 250 * WarmongerHate 8 / 100 = 20
        assertEquals(-20f, modifier(c, a, DiplomaticModifiers.WarMongerer), 0.001f)
    }

    @Test
    fun `an observer already at war with the victim does not count the declaration`() {
        c.getDiplomacyManager(b)!!.declareWar()
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(0, c.ai.diplomacy.memoryOf(a).warmongerAmount)
    }

    @Test
    fun `declaring war on a city-state adds 125`() {
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        meet(cityState, a)
        meet(cityState, c)
        a.getDiplomacyManager(cityState)!!.declareWar()
        assertEquals(125, c.ai.diplomacy.memoryOf(a).warmongerAmount)
    }

    @Test
    fun `warmonger score under 5 is no opinion term`() {
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 1)
        c.ai.diplomacy.memoryOf(a).warmongerAmount = 499 // 4.99 -> 4
        assertEquals(0f, modifier(c, a, DiplomaticModifiers.WarMongerer), 0.001f)
        c.ai.diplomacy.memoryOf(a).warmongerAmount = 500
        assertEquals(-5f, modifier(c, a, DiplomaticModifiers.WarMongerer), 0.001f)
    }

    @Test
    fun `warmonger amount decays by 5 a turn down to 0`() {
        c.ai.diplomacy.memoryOf(a).warmongerAmount = 12
        DiplomacyTurn.doTurn(c)
        assertEquals(7, c.ai.diplomacy.memoryOf(a).warmongerAmount)
        DiplomacyTurn.doTurn(c)
        DiplomacyTurn.doTurn(c)
        assertEquals(0, c.ai.diplomacy.memoryOf(a).warmongerAmount)
    }

    @Test
    fun `conquering a city adds the warmonger offset by cities left and halves it for those at war with the loser`() {
        game.addCity(b, game.getTile(HexCoord(10, -2)))
        val lost = game.addCity(b, game.getTile(HexCoord(10, 2)))
        val d = neutralCiv(HexCoord(0, -8))
        meet(d, a)
        meet(d, b)
        d.getDiplomacyManager(b)!!.declareWar()
        val dBefore = d.ai.diplomacy.memoryOf(a).warmongerAmount
        val cBefore = c.ai.diplomacy.memoryOf(a).warmongerAmount
        val estimated = rules.worldSize(game.gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().name).estimatedNumCities
        val allCities = game.gameInfo.civilizations.sumOf { it.cities.size }
        // GetWarmongerOffset: 1000 * estimated / (cities in the world * cities the loser keeps (3 - 1))
        val offset = 1000 * estimated / (allCities * 2)
        DiplomacyEvents.onCityConquered(a, b, lost)
        assertEquals(offset, c.ai.diplomacy.memoryOf(a).warmongerAmount - cBefore)
        assertEquals(offset / 2, d.ai.diplomacy.memoryOf(a).warmongerAmount - dBefore)
    }

    @Test
    fun `conquering a city-state city adds half of the warmonger offset a major's city would`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        val lost = game.addCity(cityState, game.getTile(HexCoord(0, 4)))
        meet(c, a)
        val before = c.ai.diplomacy.memoryOf(a).warmongerAmount
        val estimated = rules.worldSize(game.gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().name).estimatedNumCities
        val allCities = game.gameInfo.civilizations.sumOf { it.cities.size }
        // GetWarmongerOffset(1 city left, minor): 1000 * estimated / (cities in the world * 1), times WARMONGER_ON_CITY_STATE_MULTIPLIER (BNW XML: 50) / 100
        val offset = 1000 * estimated / allCities * 50 / 100
        DiplomacyEvents.onCityConquered(a, cityState, lost)
        assertEquals(offset, c.ai.diplomacy.memoryOf(a).warmongerAmount - before)
    }

    @Test
    fun `killing a city-state's unit pleases nobody - common foes are between majors`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        for (major in listOf(a, b, c)) meet(major, cityState)
        cityState.getDiplomacyManager(a)!!.declareWar()
        cityState.getDiplomacyManager(b)!!.declareWar()
        DiplomacyEvents.onUnitKilled(cityState, b, game.ruleset.units.getValue("Warrior"))
        assertEquals(0, a.ai.diplomacy.memoryOf(b).commonFoeValue)
        assertTrue("the city-state keeps its loss", cityState.ai.diplomacy.memoryOf(b).warValueLost > 0)
    }

    @Test
    fun `the era of the game is the average of every living civilization including the barbarians`() {
        val eras = game.ruleset.eras.values
        for (major in listOf(a, b, c)) major.tech.era = eras.first { it.eraNumber == 2 }
        assertEquals(eras.first { it.eraNumber == 2 }.name, DiplomacyTurn.gameEraName(a))
        game.addBarbarianCiv() // era 0: (2 + 2 + 2 + 0) / 4 = 1
        assertEquals(eras.first { it.eraNumber == 1 }.name, DiplomacyTurn.gameEraName(a))
    }
    //endregion

    //region Approaches

    /** SelectBestApproachTowardsMajorCiv part 1 (Vox Populi), LAST TURN APPROACH BIASES: the old approach + its bias x 2; war x 2 + x 4 */
    @Test
    fun `the current approach is sticky and war sticks harder`() {
        a.ai.setMajorCivApproachBias(MajorCivApproach.Hostile, 3)
        a.ai.setMajorCivApproachBias(MajorCivApproach.War, 4)
        val fromNeutral = weights(a, b, MajorCivApproach.Neutral)
        val fromHostile = weights(a, b, MajorCivApproach.Hostile)
        assertEquals(6, fromHostile[MajorCivApproach.Hostile.ordinal] - fromNeutral[MajorCivApproach.Hostile.ordinal])
        val fromWar = weights(a, b, MajorCivApproach.War)
        assertEquals(24, fromWar[MajorCivApproach.War.ordinal] - fromNeutral[MajorCivApproach.War.ordinal])
        val firstUpdate = weights(a, b, null)
        assertEquals("no last-turn bias on the first update", fromNeutral[MajorCivApproach.War.ordinal], firstUpdate[MajorCivApproach.War.ordinal])
    }

    @Test
    fun `approach biases add to the weights, and the conquest grand strategy adds the war bias once more`() {
        // What the stage named [stage] adds to a's war weight towards b (later stages scale the biases too)
        fun warDelta(stage: String): Int {
            val steps = ArrayList<Pair<String, IntArray>>()
            ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
            try { weights(a, b, null) } finally { ApproachAi.debugSink = null }
            val index = steps.indexOfFirst { it.first.startsWith(stage) }
            return steps[index + 1].second[MajorCivApproach.War.ordinal] - steps[index].second[MajorCivApproach.War.ordinal]
        }
        val base = weights(a, b, null)
        a.ai.setMajorCivApproachBias(MajorCivApproach.Guarded, 6)
        assertEquals(6, weights(a, b, null)[MajorCivApproach.Guarded.ordinal] - base[MajorCivApproach.Guarded.ordinal])
        a.ai.setMajorCivApproachBias(MajorCivApproach.War, 7)
        assertEquals(7, warDelta("Personality"))
        // Vox Populi's conquest bias (part 1, WORLD CONQUEST), not BNW's APPROACH_WAR_CONQUEST_GRAND_STRATEGY
        a.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertEquals(7, warDelta("Conquest bias"))
    }

    //endregion

    //region War goals and peace

    @Test
    fun `a coop war agreed for soon is declared at the soon counter and locks the war`() {
        // partner b, target c; a has no memory of c yet, so locking the war creates it while the memories are being walked
        // (memories of civilizations that no longer exist follow b in the walk and are skipped)
        repeat(30) { a.ai.diplomacy.memories["gone-$it"] = MajorCivMemory() }
        a.ai.diplomacy.memories.remove(c.civID)
        a.ai.diplomacy.memoryOf(b).coopWars[c.civID] = CoopWarEntry(CoopWarState.Soon, rules.define(AiDefine.COOP_WAR_SOON_COUNTER) - 1)
        CoopWarAi.doCoopWarCounters(a, rules)
        assertTrue(a.isAtWarWith(c))
        assertEquals(CoopWarState.Accepted, a.ai.diplomacy.memoryOf(b).coopWars[c.civID]!!.state)
        assertEquals(rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH), a.ai.diplomacy.memoryOf(c).turnsLockedIntoWar)
    }

    @Test
    fun `no war goal at peace`() {
        DiplomacyTurn.doTurn(a)
        assertNull(a.ai.diplomacy.warGoal(b))
    }
    //endregion

    @Test
    fun `an attack is ready when the formation's required slots are filled by healthy non-scout units`() {
        val formation = "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
        val tile = game.getTile(HexCoord(0, 0))
        game.addUnit("Swordsman", a, tile)
        game.addUnit("Catapult", a, game.getTile(HexCoord(1, 0)))
        // A Scout explores: it fills no slot
        game.addUnit("Scout", a, game.getTile(HexCoord(0, 1)))
        assertFalse(MilitaryOperations.isAttackReady(a, formation, AiOperationType.BASIC_CITY_ATTACK))
        // A Pikeman can explore too (Unit_AITypes) but its own AI type is not exploring: it fills the attack/counter slot
        val pikeman = game.addUnit("Pikeman", a, game.getTile(HexCoord(1, -1)))
        assertTrue(MilitaryOperations.isAttackReady(a, formation, AiOperationType.BASIC_CITY_ATTACK))
        // 21 damage is more than a unit that heals in the capital accepts (CvUnit::shouldHeal), whatever AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION (70) says
        pikeman.health = 79
        assertFalse(MilitaryOperations.isAttackReady(a, formation, AiOperationType.BASIC_CITY_ATTACK))
    }

    //region Friendship and denouncements

    @Test
    fun `denouncing needs a weight above 25, after Vox Populi`() {
        game.gameInfo.turns = 20 // met over 10 turns ago
        a.ai.setDiplomacyValue(DiplomacyPersonality.DenounceWillingness, 6)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Hostile)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Enemy)
        // 6 + hostile 6 + enemy 5 = 17; with a friend's request +3 = 20
        assertEquals(17, DiplomacyAutomation.denounceWeight(a, b, bias = false))
        assertFalse(DiplomacyAutomation.isDenounceAcceptable(a, b, bias = true))

        // Our biggest competitor: +10
        a.ai.diplomacy.biggestCompetitor = b.civID
        assertEquals(27, DiplomacyAutomation.denounceWeight(a, b, bias = false))
        assertTrue(DiplomacyAutomation.isDenounceAcceptable(a, b, bias = false))

        // A friend of ours who denounced them: our opinion of it (friend, 2 above neutral) + 4 for the DoF, 1 more if a neighbour
        a.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        a.ai.diplomacy.setApproach(c, MajorCivApproach.Neutral)
        a.ai.diplomacy.setOpinion(c, MajorCivOpinion.Friend)
        a.ai.warStates.militaryStrengths[c.civID] = StrengthLevel.Average
        c.getDiplomacyManager(b)!!.denounce()
        val neighbor = if (DiplomacyTurn.proximity(a, c) == DiplomacyTurn.PROXIMITY_NEIGHBORS) 1 else 0
        assertEquals(27 + 2 + 4 + neighbor, DiplomacyAutomation.denounceWeight(a, b, bias = false))

        // Never a friend
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertFalse(DiplomacyAutomation.isDenounceAcceptable(a, b, bias = true))
    }
    //endregion
}
