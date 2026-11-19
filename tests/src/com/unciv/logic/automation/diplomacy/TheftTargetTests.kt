package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvDiplomacyAI::IsBadTheftTarget (Vox Populi): whom the AI does not take tiles, converts, artifacts and spy work from */
@RunWith(GdxTestRunner::class)
class TheftTargetTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        a = civ(HexCoord(0, 0))
        b = civ(HexCoord(8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    private fun civ(at: HexCoord): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun cityState(): Civilization {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        return cityState
    }

    private val memory get() = a.ai.diplomacy.memoryOf(b)

    private fun bad(type: TheftType, other: Civilization = b) = TheftAi.isBadTheftTarget(a, other, type)

    /** A culture bomb is judged by the approach and the land dispute alone only when neither a friend nor a promise decides before */
    private fun coveting() {
        memory.landDispute = DisputeLevel.Strong
    }

    /** [b] as a civilization with a religion of its own: the reflection the religion tests use for the private state */
    private fun givesItsOwnReligion() {
        game.addReligion(b)
        ReligionManager::class.java.getDeclaredField("religionState").apply { isAccessible = true }.set(b.religionManager, ReligionState.Religion)
    }

    private fun promise(demand: Demand) = b.getDiplomacyManager(a)!!.setFlag(demand.agreedToDemand, 20)

    //region Whom nothing is taken from at all

    @Test
    fun `no player is a bad target for ourselves, nor for a war`() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        assertFalse(TheftAi.isBadTheftTarget(a, a, TheftType.CultureBomb))
        assertTrue("afraid of them", bad(TheftType.CultureBomb))
        a.getDiplomacyManager(b)!!.declareWar()
        for (type in TheftType.entries) assertFalse("at war: $type", bad(type))
    }

    //endregion

    //region Culture bomb

    @Test
    fun `an AI culture bombs only a civilization it shows guarded or worse or has a strong land dispute with`() {
        val diplomacy = a.ai.diplomacy
        fun bomb(approach: MajorCivApproach, dispute: DisputeLevel = DisputeLevel.None): Boolean {
            diplomacy.setApproach(b, approach)
            memory.landDispute = dispute
            return bad(TheftType.CultureBomb)
        }
        assertTrue("neutral, no dispute", bomb(MajorCivApproach.Neutral))
        assertTrue("friendly", bomb(MajorCivApproach.Friendly))
        assertTrue("deceptive looks friendly", bomb(MajorCivApproach.Deceptive))
        assertFalse("guarded", bomb(MajorCivApproach.Guarded))
        assertFalse("hostile", bomb(MajorCivApproach.Hostile))
        assertTrue("neutral, a weak dispute", bomb(MajorCivApproach.Neutral, DisputeLevel.Weak))
        assertFalse("neutral, a strong dispute", bomb(MajorCivApproach.Neutral, DisputeLevel.Strong))
        assertFalse("neutral, a fierce dispute", bomb(MajorCivApproach.Neutral, DisputeLevel.Fierce))
        assertFalse("friendly, a strong dispute", bomb(MajorCivApproach.Friendly, DisputeLevel.Strong))
        assertTrue("afraid: always", bomb(MajorCivApproach.Afraid, DisputeLevel.Fierce))
    }

    @Test
    fun `a war we plan shows its war face`() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War, null)
        assertTrue("no face yet: neutral", bad(TheftType.CultureBomb))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War, WarFace.Friendly)
        assertTrue(bad(TheftType.CultureBomb))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War, WarFace.Hostile)
        assertFalse(bad(TheftType.CultureBomb))
    }

    @Test
    fun `a human's automation culture bombs everyone but its friends`() {
        a.playerType = PlayerType.Human
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Friendly)
        assertFalse(bad(TheftType.CultureBomb))
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Ally)
        promise(Demand.DoNotSettleNearUs)
        assertFalse("the opinion and a promise are an AI's", bad(TheftType.CultureBomb))
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertTrue(bad(TheftType.CultureBomb))
    }

    @Test
    fun `an AI does not culture bomb the civilization it promised not to settle near`() {
        coveting()
        assertFalse(bad(TheftType.CultureBomb))
        a.getDiplomacyManager(b)!!.setFlag(Demand.DoNotSettleNearUs.agreedToDemand, 20)
        assertFalse("their promise to us is not ours", bad(TheftType.CultureBomb))
        promise(Demand.DoNotSettleNearUs)
        assertTrue(bad(TheftType.CultureBomb))
        promise(Demand.DontSpyOnUs)
        b.getDiplomacyManager(a)!!.removeFlag(Demand.DoNotSettleNearUs.agreedToDemand)
        assertFalse("another promise", bad(TheftType.CultureBomb))
    }

    //endregion

    //region Friends: any theft, for an AI also by opinion

    @Test
    fun `a friendship, a defensive pact or a liberator is never robbed`() {
        coveting()
        a.getDiplomacyManager(b)!!.hasOpenBorders = true
        for (type in listOf(TheftType.CultureBomb, TheftType.Artifact, TheftType.Spy)) assertFalse("$type", bad(type))
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        for (type in listOf(TheftType.CultureBomb, TheftType.Artifact, TheftType.Spy)) assertTrue("a DoF: $type", bad(type))
        a.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertFalse(bad(TheftType.CultureBomb))
        a.getDiplomacyManager(b)!!.signDefensivePact(20)
        assertTrue("a defensive pact", bad(TheftType.CultureBomb))
        a.getDiplomacyManager(b)!!.diplomaticStatus = DiplomaticStatus.Peace
        assertFalse(bad(TheftType.CultureBomb))
        memory.resurrectedUs = true
        assertTrue("a liberator", bad(TheftType.CultureBomb))
        memory.numCitiesCapturedBy = 1
        assertFalse("who took a city of ours since", bad(TheftType.CultureBomb))
    }

    @Test
    fun `an AI spares an ally by opinion, one it fears, a friend it is friendly to, a competitor it signed with twice`() {
        coveting()
        val diplomacy = a.ai.diplomacy
        fun spared(opinion: MajorCivOpinion, approach: MajorCivApproach, doF: DoFLevel = DoFLevel.New): Boolean {
            diplomacy.setOpinion(b, opinion)
            diplomacy.setApproach(b, approach)
            memory.doFLevel = doF
            return bad(TheftType.CultureBomb)
        }
        assertFalse(spared(MajorCivOpinion.Neutral, MajorCivApproach.Neutral))
        assertTrue("an ally by opinion", spared(MajorCivOpinion.Ally, MajorCivApproach.Hostile))
        assertTrue("afraid", spared(MajorCivOpinion.Enemy, MajorCivApproach.Afraid))
        assertTrue("a friend, friendly", spared(MajorCivOpinion.Friend, MajorCivApproach.Friendly))
        assertFalse("only favorable, friendly", spared(MajorCivOpinion.Favorable, MajorCivApproach.Friendly))
        assertFalse("a friend, but neutral", spared(MajorCivOpinion.Friend, MajorCivApproach.Neutral))
        assertTrue("a competitor, neutral, an ally of ours", spared(MajorCivOpinion.Competitor, MajorCivApproach.Neutral, DoFLevel.Allies))
        assertTrue("battle brothers", spared(MajorCivOpinion.Competitor, MajorCivApproach.Friendly, DoFLevel.BattleBrothers))
        assertFalse("signed only once", spared(MajorCivOpinion.Competitor, MajorCivApproach.Neutral, DoFLevel.Friends))
        assertFalse("an enemy", spared(MajorCivOpinion.Enemy, MajorCivApproach.Neutral, DoFLevel.Allies))
        assertFalse("guarded", spared(MajorCivOpinion.Competitor, MajorCivApproach.Guarded, DoFLevel.Allies))
    }

    @Test
    fun `a human's automation does not read the opinion`() {
        a.playerType = PlayerType.Human
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Ally)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        a.getDiplomacyManager(b)!!.hasOpenBorders = true
        assertFalse(bad(TheftType.Artifact))
    }

    //endregion

    //region Conversion

    @Test
    fun `a civilization without a religion is no bad target for a conversion unless it asked us to stop, even a friend`() {
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertFalse(bad(TheftType.Conversion))
        promise(Demand.DoNotSpreadReligion)
        assertTrue("asked, and we agreed", bad(TheftType.Conversion))
        b.getDiplomacyManager(a)!!.removeFlag(Demand.DoNotSpreadReligion.agreedToDemand)
        assertFalse(bad(TheftType.Conversion))
        PromiseAi.onDemandRefused(b, a, Demand.DoNotSpreadReligion)
        assertTrue("asked, and we refused: the friend counts again", bad(TheftType.Conversion))
    }

    @Test
    fun `a refused request still lets a stranger without a religion be converted`() {
        PromiseAi.onDemandRefused(b, a, Demand.DoNotSpreadReligion)
        assertFalse(bad(TheftType.Conversion))
    }

    @Test
    fun `a civilization with a religion is judged like any other`() {
        givesItsOwnReligion()
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        assertFalse("a stranger", bad(TheftType.Conversion))
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertTrue("a friend", bad(TheftType.Conversion))
        a.getDiplomacyManager(b)!!.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        promise(Demand.DoNotSpreadReligion)
        assertTrue("one we promised", bad(TheftType.Conversion))
    }

    //endregion

    //region Artifacts and spies

    @Test
    fun `no artifact is dug in the lands of one whose borders are closed to us or who has our promise`() {
        val ours = a.getDiplomacyManager(b)!!
        assertTrue("closed borders", bad(TheftType.Artifact))
        ours.hasOpenBorders = true
        assertFalse("open borders", bad(TheftType.Artifact))
        promise(Demand.DoNotDigInOurLands)
        assertTrue("a promise", bad(TheftType.Artifact))
        b.getDiplomacyManager(a)!!.removeFlag(Demand.DoNotDigInOurLands.agreedToDemand)
        assertFalse(bad(TheftType.Artifact))
        b.getDiplomacyManager(a)!!.hasOpenBorders = true
        ours.hasOpenBorders = false
        assertTrue("their borders are the ones that count", bad(TheftType.Artifact))
    }

    @Test
    fun `no spy works for one we promised not to send`() {
        assertFalse(bad(TheftType.Spy))
        promise(Demand.DoNotSpreadReligion)
        assertFalse("another promise", bad(TheftType.Spy))
        promise(Demand.DontSpyOnUs)
        assertTrue(bad(TheftType.Spy))
    }

    //endregion

    //region City-states

    @Test
    fun `an AI culture bombs a city-state unless it approaches it as friendly or is its ally`() {
        val cityState = cityState()
        for (approach in listOf(MinorCivApproach.Ignore, MinorCivApproach.Conquest, MinorCivApproach.Bully)) {
            a.ai.minorCivApproaches[cityState.civID] = approach
            assertFalse("$approach", bad(TheftType.CultureBomb, cityState))
        }
        a.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Friendly
        assertTrue("friendly", bad(TheftType.CultureBomb, cityState))
        a.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Ignore
        cityState.getDiplomacyManager(a)!!.setInfluenceWithoutSideEffects(40f)
        assertFalse("an AI does not mind a friend", bad(TheftType.CultureBomb, cityState))
        cityState.allyCiv = a
        assertTrue("its ally", bad(TheftType.CultureBomb, cityState))
    }

    @Test
    fun `a human's automation culture bombs a city-state unless it is its friend`() {
        val cityState = cityState()
        a.playerType = PlayerType.Human
        a.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Friendly
        val diplomacy = cityState.getDiplomacyManager(a)!!
        diplomacy.setInfluenceWithoutSideEffects(29f)
        assertFalse("not yet a friend, whatever its approach is", bad(TheftType.CultureBomb, cityState))
        diplomacy.setInfluenceWithoutSideEffects(30f)
        assertTrue("a friend", bad(TheftType.CultureBomb, cityState))
        diplomacy.setInfluenceWithoutSideEffects(0f)
        cityState.allyCiv = a
        assertTrue("its ally", bad(TheftType.CultureBomb, cityState))
    }

    @Test
    fun `a city-state is only judged for a culture bomb`() {
        val cityState = cityState()
        cityState.allyCiv = a
        assertTrue(bad(TheftType.CultureBomb, cityState))
        for (type in listOf(TheftType.Conversion, TheftType.Artifact, TheftType.Spy)) assertFalse("$type", bad(type, cityState))
        a.getDiplomacyManager(cityState)!!.declareWar()
        assertFalse("at war", bad(TheftType.CultureBomb, cityState))
    }

    //endregion
}
