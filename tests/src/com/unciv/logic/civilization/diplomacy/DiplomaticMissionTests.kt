package com.unciv.logic.civilization.diplomacy

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.quests.QuestManager
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.ModConstants
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Quest
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diplomatic mission of Vox Populi (CvUnit::canTrade and getTradeInfluence, V:CvUnit.cpp:11990-12062, trade V:12064-12212), only with `diplomaticMissionsLikeVp`. V: is
 * `ref/vp-5.4.8/CvGameCoreDLL_Expansion2/`. The numbers are worked out by hand from the source, in its integer order: each percentage multiplies, divides by 100 and truncates.
 * The units, promotions and buildings are made here, never the shared ones changed. Not built, so not tested: the Great Diplomat's lowering of the others' influence.
 */
@RunWith(GdxTestRunner::class)
class DiplomaticMissionTests {
    private val constants = ModConstants.DiplomaticMissionConstants()

    private fun influence(
        base: Int? = 40, cityBoost: Int = 0, perEra: Int = 0, era: Int = 0,
        unitPercent: Int = 0, civPercent: Int = 0, quest: Boolean = false,
        c: ModConstants.DiplomaticMissionConstants = constants
    ) = DiplomaticMission.influence(c, base, cityBoost, perEra, era, unitPercent, civPercent, quest)

    @Test
    fun `an Emissary gives its 40`() = assertEquals(40, influence())

    @Test
    fun `the city boost is added to the base before anything else`() = assertEquals(50, influence(cityBoost = 10))

    @Test
    fun `a percentage of the civilization rounds down`() {
        assertEquals(44, influence(civPercent = 10))             // 40 x 110 / 100
        assertEquals(49, influence(cityBoost = 5, civPercent = 10)) // 45 x 110 / 100 = 49.5
    }

    @Test
    fun `the percentage of the unit and of the civilization are two steps, each rounding down`() {
        // 43 x 125 / 100 = 53 (53.75), then 53 x 110 / 100 = 58 (58.3). One step of 137.5% would give 59
        assertEquals(58, influence(base = 43, unitPercent = 25, civPercent = 10))
    }

    @Test
    fun `an active quest adds 20 percent last`() {
        assertEquals(48, influence(quest = true))                         // 40 x 120 / 100
        assertEquals(54, influence(cityBoost = 5, quest = true))          // 45 x 120 / 100
        // last: 45 x 110 / 100 = 49 (49.5), then 49 x 120 / 100 = 58 (58.8). Boosted first it would be 54, then 59 (59.4)
        assertEquals(58, influence(cityBoost = 5, civPercent = 10, quest = true))
        // 50 x 125 / 100 = 62 (62.5), 62 x 110 / 100 = 68 (68.2), 68 x 120 / 100 = 81 (81.6); boosted first 60, 75, 82 (82.5)
        assertEquals(81, influence(base = 50, unitPercent = 25, civPercent = 10, quest = true))
    }

    @Test
    fun `the amount per era is added before the percentages and grows with the era`() {
        assertEquals(130, influence(base = 100, perEra = 15, era = 2))
        assertEquals(100, influence(base = 100, perEra = 15, era = 0))
        // (100 + 15 x 2) x 110 / 100 = 143, not 100 x 110 / 100 + 30 = 140
        assertEquals(143, influence(base = 100, perEra = 15, era = 2, civPercent = 10))
    }

    @Test
    fun `a unit with no mission influence of its own gets the define and no city boost`() {
        assertEquals(0, influence(base = null, cityBoost = 10))
        val community = ModConstants.DiplomaticMissionConstants(noMissionInfluence = 30)
        assertEquals(30, influence(base = null, cityBoost = 10, c = community))
        assertEquals(36, influence(base = null, civPercent = 20, c = community))  // 30 x 120 / 100
    }

    @Test
    fun `the quest boost is its own constant`() {
        val strong = ModConstants.DiplomaticMissionConstants(questBoostPercent = 50)
        assertEquals(60, influence(quest = true, c = strong))                // 40 x 150 / 100
    }

    // ---- the readers and the action on a game ----

    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var cityState: Civilization

    private fun setUpGame(vararg civUniques: String) {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.ruleset.modOptions.constants.diplomaticMissionsLikeVp = true
        // the quest of Vox Populi that raises a mission is no row of the Brave New World ruleset; a quest that is assigned has to be one
        game.ruleset.quests["Influence"] = Quest().apply { name = "Influence" }
        us = game.addCiv(*civUniques, isPlayer = true)
        cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(us, game.getTile(-6, 0), replacePalace = true)
        game.addCity(cityState, game.getTile(5, 0), replacePalace = true)
        us.diplomacyFunctions.makeCivilizationsMeet(cityState)
    }

    /** The tiles around the city-state's centre, one for each unit made (a civilian does not share a tile) */
    private val ring = listOf(4 to 0, 6 to 0, 5 to 1, 5 to -1, 4 to 1, 6 to -1)
    private var nextTile = 0

    private fun diplomat(vararg uniques: String, promotionUniques: List<String> = emptyList(), tile: Pair<Int, Int> = ring[nextTile++ % ring.size]): MapUnit {
        val base = game.createBaseUnit("Civilian", *uniques)
        val unit = game.addUnit(base.name, us, game.getTile(tile.first, tile.second))
        if (promotionUniques.isNotEmpty()) {
            val promotion = Promotion().apply {
                name = "Test promotion ${game.ruleset.unitPromotions.size}"
                this.uniques = ArrayList(promotionUniques)
            }
            game.ruleset.unitPromotions[promotion.name] = promotion
            unit.promotions.addPromotion(promotion.name, isFree = true)
        }
        return unit
    }

    private fun csInfluence() = cityState.getDiplomacyManager(us)!!.getInfluence()
    private fun mission(unit: MapUnit) = UnitActions.getUnitActions(unit, UnitActionType.ConductDiplomaticMission).toList()
    private val can = "Can undertake a diplomatic mission with City-State"

    @Test
    fun `the base is the sum of the unit's own uniques and its promotions' and null with none`() {
        setUpGame()
        assertEquals(null, DiplomaticMission.baseOf(diplomat(can)))
        assertEquals(40, DiplomaticMission.baseOf(diplomat(can, "[40] Influence from diplomatic missions")))
        assertEquals(60, DiplomaticMission.baseOf(diplomat(can, "[40] Influence from diplomatic missions", promotionUniques = listOf("[20] Influence from diplomatic missions"))))
        assertEquals(10, DiplomaticMission.baseOf(diplomat(can, promotionUniques = listOf("[10] Influence from diplomatic missions"))))
    }

    @Test
    fun `the city boost is the sum over the buildings of the origin city`() {
        setUpGame()
        val city = us.cities.first()
        assertEquals(0, DiplomaticMission.cityBoostOf(city))
        city.cityConstructions.addBuilding(game.createBuilding("[10] Influence from diplomatic missions of units from this city"))
        city.cityConstructions.addBuilding(game.createBuilding("[5] Influence from diplomatic missions of units from this city"))
        assertEquals(15, DiplomaticMission.cityBoostOf(city))
        assertEquals(0, DiplomaticMission.cityBoostOf(null))
    }

    @Test
    fun `the Wat of the origin city counts, else the one of the capital`() {
        setUpGame()
        val capital = us.cities.first()
        val other = game.addCity(us, game.getTile(-3, 3))
        capital.cityConstructions.addBuilding(game.createBuilding("[10] Influence from diplomatic missions of units from this city"))
        val unit = diplomat(can, "[40] Influence from diplomatic missions")
        assertEquals("a unit with no origin city uses the capital", 50, DiplomaticMission.influenceOf(unit, cityState))
        unit.originCityId = other.id
        assertEquals("the origin city has no building", 40, DiplomaticMission.influenceOf(unit, cityState))
        unit.originCityId = capital.id
        assertEquals(50, DiplomaticMission.influenceOf(unit, cityState))
    }

    @Test
    fun `the era, the percent of the unit and the percent of the civilization are read`() {
        setUpGame("[+10]% Influence from diplomatic missions")
        val unit = diplomat(
            can, "[100] Influence from diplomatic missions", "[15] Influence from diplomatic missions for every era",
            "[+25]% Influence from diplomatic missions"
        )
        val era = us.getEraNumber()
        // (100 + 15 x era) x 125 / 100 x 110 / 100, each truncated
        val expected = ((100 + 15 * era) * 125 / 100) * 110 / 100
        assertEquals("era $era", expected, DiplomaticMission.influenceOf(unit, cityState))
    }

    @Test
    fun `an assigned quest of the name raises the mission for its assignee only`() {
        setUpGame()
        val unit = diplomat(can, "[40] Influence from diplomatic missions")
        assertEquals(40, DiplomaticMission.influenceOf(unit, cityState))
        giveQuest("Influence", us)
        assertEquals(48, DiplomaticMission.influenceOf(unit, cityState))
        giveQuest("Influence", game.addCiv())
        assertEquals("a quest of another civilization", 40, DiplomaticMission.influenceOf(unit, cityState))
        giveQuest("Invest", us)
        assertEquals("another quest", 40, DiplomaticMission.influenceOf(unit, cityState))
    }

    /** The only quest of the city-state, as a game that was loaded would have it */
    private fun giveQuest(name: String, assignee: Civilization) {
        val json = "{\"assignedQuests\":[{\"questName\":\"$name\",\"assigner\":\"${cityState.civID}\",\"assignee\":\"${assignee.civID}\",\"assignedOnTurn\":1}]}"
        cityState.questManager = json().fromJson(QuestManager::class.java, json)
        cityState.questManager.setTransients(cityState)
    }

    @Test
    fun `the mission gives the influence and uses the unit up`() {
        setUpGame()
        val unit = diplomat(can, "[40] Influence from diplomatic missions")
        val before = csInfluence()
        val action = mission(unit).single()
        assertNotNull("it can be done", action.action)
        action.action!!.invoke()
        assertEquals(before + 40f, csInfluence(), 0.001f)
        assertFalse("the unit is gone", unit in us.units.getCivUnits())
    }

    @Test
    fun `the mission is not offered without the switch, without the unique, without a mission influence, at home or outside a city-state`() {
        setUpGame()
        val withBase = "[40] Influence from diplomatic missions"
        assertTrue(mission(diplomat(can, withBase)).isNotEmpty())
        assertTrue("no unique", mission(diplomat(withBase)).isEmpty())
        assertTrue("no mission influence", mission(diplomat(can)).isEmpty())
        assertTrue("outside a city-state's land", mission(diplomat(can, withBase, tile = -2 to 0)).isEmpty())
        game.ruleset.modOptions.constants.diplomaticMissionsLikeVp = false
        assertTrue("the switch is off", mission(diplomat(can, withBase)).isEmpty())
    }

    @Test
    fun `at war the action is listed but cannot be done`() {
        setUpGame()
        val unit = diplomat(can, "[40] Influence from diplomatic missions")
        us.getDiplomacyManager(cityState)!!.declareWar()
        val before = csInfluence()
        assertTrue(mission(unit).all { it.action == null })
        assertEquals(before, csInfluence(), 0.001f)
    }

    @Test
    fun `a Great Merchant has no trade mission with the switch on and keeps it with the switch off`() {
        setUpGame()
        val merchant = game.createBaseUnit("Civilian", "Can undertake a trade mission with City-State, giving a large sum of gold and [30] Influence")
        val unit = game.addUnit(merchant.name, us, game.getTile(4, 0))
        assertTrue(UnitActions.getUnitActions(unit, UnitActionType.ConductTradeMission).toList().isEmpty())
        game.ruleset.modOptions.constants.diplomaticMissionsLikeVp = false
        val before = csInfluence()
        val goldBefore = us.gold
        val action = UnitActions.getUnitActions(unit, UnitActionType.ConductTradeMission).single()
        action.action!!.invoke()
        assertEquals(before + 30f, csInfluence(), 0.001f)
        assertTrue("gold came too", us.gold > goldBefore)
    }

    // ---- the Great Diplomat (CvUnit::trade, V:CvUnit.cpp:12086-12179) ----

    private val greatDiplomat = "Lowers the influence of every other civilization that knows the City-State by as much as it gives, with its diplomatic mission"
    private val gives = "[%d] Influence from diplomatic missions"

    /** [us] and two other majors that all know the city-state, with the ally rule of Vox Populi */
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    private fun setUpThree() {
        setUpGame()
        game.ruleset.modOptions.constants.cityStateInfluenceLikeVp = true
        b = game.addCiv()
        c = game.addCiv()
        game.addCity(b, game.getTile(-3, 3), replacePalace = true)
        game.addCity(c, game.getTile(-3, -3), replacePalace = true)
        b.diplomacyFunctions.makeCivilizationsMeet(cityState)
        c.diplomacyFunctions.makeCivilizationsMeet(cityState)
    }

    private fun set(civ: Civilization, amount: Float) = cityState.getDiplomacyManager(civ)!!.setInfluenceWithoutSideEffects(amount)
    private fun of(civ: Civilization) = cityState.getDiplomacyManager(civ)!!.getInfluence()

    private fun great(amount: Int, vararg more: String) = diplomat(can, gives.format(amount), greatDiplomat, *more)

    @Test
    fun `a Great Diplomat takes the influence of the mission from every other civilization that knows the city-state`() {
        setUpThree()
        set(b, 50f); set(c, 20f); set(us, 0f)
        DiplomaticMission.conduct(great(40), cityState)
        assertEquals(40f, of(us), 0.001f)
        assertEquals(10f, of(b), 0.001f)
        assertEquals(-20f, of(c), 0.001f)
    }

    @Test
    fun `a plain mission leaves the others alone`() {
        setUpThree()
        set(b, 50f); set(c, 20f)
        DiplomaticMission.conduct(diplomat(can, gives.format(40)), cityState)
        assertEquals(50f, of(b), 0.001f)
        assertEquals(20f, of(c), 0.001f)
    }

    @Test
    fun `what is taken stops at the lowest influence there is`() {
        setUpThree()
        set(b, -50f)
        DiplomaticMission.conduct(great(40), cityState)
        assertEquals(-60f, of(b), 0.001f)
    }

    @Test
    fun `a civilization that has not met the city-state, one that is defeated, a city-state and the owner are not lowered`() {
        setUpThree()
        val stranger = game.addCiv()
        game.addCity(stranger, game.getTile(0, 6), replacePalace = true)
        val other = game.addCiv(cityStateType = "Maritime")
        game.addCity(other, game.getTile(0, -6), replacePalace = true)
        set(b, 50f); set(c, 50f)
        c.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertTrue("c is defeated", c.isDefeated())
        DiplomaticMission.conduct(great(40), cityState)
        assertEquals("b is lowered", 10f, of(b), 0.001f)
        assertEquals("a defeated civilization is not", 50f, of(c), 0.001f)
        assertEquals("the owner gains and does not lose", 40f, of(us), 0.001f)
        assertFalse("a civilization that does not know it has no relation to change", cityState.knows(stranger))
    }

    @Test
    fun `the ally is updated after ours has risen, from what each had before`() {
        setUpThree()
        // b is the ally with 90 and c has 90 too (a tie keeps the ally); the mission gives 10.
        // Together: b 80, c 80, us 10; b stays the ally, as a tie does not take the place (V:CvMinorCivAI.cpp:11393-11452).
        // Had the ally been updated while b was lowered, c (still at 90) would have taken the place and then kept it: c, not b.
        set(b, 90f); set(c, 90f); set(us, 0f)
        cityState.allyCiv = b
        DiplomaticMission.conduct(great(10), cityState)
        assertEquals(80f, of(b), 0.001f)
        assertEquals(80f, of(c), 0.001f)
        assertEquals(b, cityState.allyCiv)
    }

    @Test
    fun `an ally that falls under 60 is no ally and our own rise can make us the ally`() {
        setUpThree()
        // b is the ally with 70, us at 30; a mission of 40 takes b to 30 first, then gives us 70: above 60 and more than b has by then (30), so we take the place;
        // updating b afterwards (70 -> 30) changes nothing, as b is no longer the ally
        set(b, 70f); set(c, 0f); set(us, 30f)
        cityState.allyCiv = b
        DiplomaticMission.conduct(great(40), cityState)
        assertEquals(70f, of(us), 0.001f)
        assertEquals(30f, of(b), 0.001f)
        assertEquals(us, cityState.allyCiv)
    }

    @Test
    fun `an ally that was lowered under 60 loses the alliance in the second pass, though our own rise does not reach 60`() {
        setUpThree()
        // b is the ally with 70, us at 0; a mission of 40 takes b to 30 and gives us 40. Our rise alone changes nothing (40 is under 60, the ally stays b);
        // only the update of b from 70 to 30 ends its alliance
        set(b, 70f); set(c, 0f); set(us, 0f)
        cityState.allyCiv = b
        DiplomaticMission.conduct(great(40), cityState)
        assertEquals(30f, of(b), 0.001f)
        assertEquals(40f, of(us), 0.001f)
        assertEquals(null, cityState.allyCiv)
    }

    @Test
    fun `the resting point of our influence is raised and the others' is not`() {
        setUpThree()
        val tadodaho = great(40, "[45] to the resting point of the influence of the City-State, for the civilization of the unit, from its diplomatic mission")
        DiplomaticMission.conduct(tadodaho, cityState)
        assertEquals(45f, cityState.getDiplomacyManager(us)!!.restingPointChange, 0.001f)
        assertEquals(0f, cityState.getDiplomacyManager(b)!!.restingPointChange, 0.001f)
        DiplomaticMission.conduct(great(40, "[45] to the resting point of the influence of the City-State, for the civilization of the unit, from its diplomatic mission"), cityState)
        assertEquals("a second one adds again", 90f, cityState.getDiplomacyManager(us)!!.restingPointChange, 0.001f)
    }

    @Test
    fun `the action of a Great Diplomat lowers the others and uses the unit up`() {
        setUpThree()
        set(b, 50f)
        val unit = great(40)
        mission(unit).single().action!!.invoke()
        assertEquals(10f, of(b), 0.001f)
        assertEquals(40f, of(us), 0.001f)
        assertFalse(unit in us.units.getCivUnits())
    }

    @Test
    fun `the percentage of a promotion is read like the unit's`() {
        setUpGame()
        val unit = diplomat(can, "[40] Influence from diplomatic missions", promotionUniques = listOf("[+25]% Influence from diplomatic missions"))
        assertEquals(50, DiplomaticMission.influenceOf(unit, cityState))   // 40 x 125 / 100
    }

    // ---- the spies of the mission (CvUnit::trade, V:CvUnit.cpp:12206-12210) ----

    private val spies = "Gain [50] Spy Points <upon conducting a diplomatic mission>"

    private fun setUpSpies() {
        setUpGame()
        game.gameInfo.gameParameters.espionageEnabled = true
        game.ruleset.modOptions.constants.spyPointsLikeVp = true
    }

    @Test
    fun `the mission gives the unit's spy points`() {
        setUpSpies()
        mission(diplomat(can, gives.format(40), spies)).single().action!!.invoke()
        assertEquals(50, us.espionageManager.spyPoints)
        mission(diplomat(can, gives.format(40), spies)).single().action!!.invoke()
        assertEquals("the second one makes a spy of 100 points", 1, us.espionageManager.spyList.size)
    }

    @Test
    fun `a unit used up otherwise and a mission of a unit without the unique give no spy points`() {
        setUpSpies()
        diplomat(can, gives.format(40), spies).consume()
        assertEquals("used up by another action", 0, us.espionageManager.spyPoints)
        mission(diplomat(can, gives.format(40))).single().action!!.invoke()
        assertEquals(0, us.espionageManager.spyPoints)
    }

    @Test
    fun `changing the influence without the ally update returns the old one, rises and stops at the lowest`() {
        setUpThree()
        set(b, 20f)
        val manager = cityState.getDiplomacyManager(b)!!
        assertEquals(20f, manager.changeInfluenceWithoutAllyUpdate(15f), 0.001f)
        assertEquals(35f, manager.getInfluence(), 0.001f)
        assertEquals(35f, manager.changeInfluenceWithoutAllyUpdate(-500f), 0.001f)
        assertEquals(-60f, manager.getInfluence(), 0.001f)
    }
}
