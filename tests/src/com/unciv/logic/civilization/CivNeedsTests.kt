package com.unciv.logic.civilization

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.NeedsTestWorld
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueEffects
import com.unciv.logic.civilization.managers.PublicOpinion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.OriginalHappinessModel
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The empire's side of Vox Populi's happiness (CvPlayer::DoUpdateTotalHappiness, DistributeHappinessToCities, CalculateNetHappiness,
 *  IsEmpireUnhappy and its siblings, the effects of the thresholds), Release-5.4.8, with `cityNeedsLikeVp` on.
 */
@RunWith(BaseTestRunner::class)
class CivNeedsTests {
    private var world = NeedsTestWorld()
    private val game get() = world.game
    private val civ get() = world.civ

    //region The pool

    private var luxuriesAdded = 0
    private var luxuryCities = 0

    /** A luxury on the centre tile of a new city of the civilization, where it is connected without an improvement */
    private fun addLuxury(): TileResource {
        // not the four whose monopoly gives 6 happiness (Vox Populi's, in the ruleset since content flip F1): the lone copy on these maps would be a monopoly
        val resource = game.ruleset.tileResources.values
            .filter { it.resourceType == ResourceType.Luxury && it.globalMonopolyUniques.none { unique -> "Happiness from a global monopoly" in unique } }[luxuriesAdded++]
        val city = world.addCity(1)
        luxuryCities++
        city.getCenterTile().tileResource = resource
        city.getCenterTile().resourceAmount = 1
        for (improvement in resource.getImprovements())
            game.ruleset.tileImprovements[improvement]?.techRequired?.let { civ.tech.addTechnology(it) }
        civ.cache.updateCivResources()
        return resource
    }

    @Test
    fun `the pool holds difficulty base, speed start, AI base, luxuries at 2 each`() {
        world.difficulty.baseHappiness = 7
        world.setStartingHappiness(2)
        addLuxury()
        addLuxury()
        world.refresh()
        val breakdown = civ.stats.getHappinessBreakdown()
        assertEquals(7f, breakdown["Base happiness"]!!, 0f)
        assertEquals(2f, breakdown["Game speed"]!!, 0f)
        assertEquals("2 luxuries x 2", 4f, breakdown["Luxury resources"]!!, 0f)
        assertEquals(7 + 2 + 4, civ.stats.needs.pool)

        world.difficulty.extraHappinessPerLuxury = 1f
        world.constants.needs = world.constants.needs.copy(happinessPerLuxury = 3)
        assertEquals("2 luxuries x (3 + 1)", 8f, civ.stats.getHappinessBreakdown()["Luxury resources"]!!, 0f)
        world.refresh()
        assertEquals(7 + 2 + 8, civ.stats.needs.pool)
    }

    @Test
    fun `an AI civilization adds the game's AI base happiness`() {
        val ai = game.addCiv()
        world.aiLevel.baseHappiness = 3
        world.difficulty.baseHappiness = 5
        world.difficulty.aiBaseHappiness = 8
        assertEquals("the AI's own level and the game's AI base", 11f, ai.stats.getHappinessBreakdown()["Base happiness"]!!, 0f)
        assertEquals("a human gets none of it", 5f, civ.stats.getHappinessBreakdown()["Base happiness"]!!, 0f)
    }

    @Test
    fun `a banned luxury gives nothing`() {
        val luxury = addLuxury()
        val other = addLuxury()
        assertEquals(4f, civ.stats.getHappinessBreakdown()["Luxury resources"]!!, 0f)
        val league = League().also { game.gameInfo.league = it }
        var id = 1
        fun enact(choice: String) {
            val active = ActiveResolution(id++, "Ban Luxury", null, choice, game.gameInfo.turns)
            league.activeResolutions.add(active)
            league.setTransients(game.gameInfo)
            LeagueEffects.onEnact(league, active)
        }
        enact(luxury.name)
        assertEquals("the other one still counts", 2f, civ.stats.getHappinessBreakdown()["Luxury resources"]!!, 0f)
        enact(other.name)
        assertEquals(0f, civ.stats.getHappinessBreakdown()["Luxury resources"]!!, 0f)
    }

    @Test
    fun `the pool does not hold the happiness of the cities or public opinion`() {
        val city = world.addCity(3)
        world.giveHappiness(city, 5)
        setPublicOpinionUnhappiness(4)
        world.refresh()
        val breakdown = civ.stats.getHappinessBreakdown()
        assertFalse(breakdown.containsKey("Public Opinion"))
        assertFalse(breakdown.containsKey("Buildings"))
        assertEquals(0, civ.stats.needs.pool)
        assertEquals(4, civ.stats.needs.unhappinessPool)
    }

    private fun setPublicOpinionUnhappiness(unhappiness: Int) {
        PublicOpinion::class.java.getDeclaredField("unhappiness").apply { isAccessible = true }.setInt(civ.culture.publicOpinion, unhappiness)
    }

    //endregion
    //region Distribution

    private fun share(city: City) = world.needsOf(city).happinessFromEmpire

    @Test
    fun `the pool goes to cities by descending need then by population`() {
        world.difficulty.baseHappiness = 10
        val a = world.addCity(5)
        val b = world.addCity(3)
        val c = world.addCity(2)
        world.giveHappiness(a, 1) // needs 4
        world.giveHappiness(c, 5) // needs nothing
        world.refresh()
        // 7 points go round the two cities with a need (a 4, b 3), the other 3 one each by population: a, b, c
        assertEquals(5, share(a))
        assertEquals(4, share(b))
        assertEquals(1, share(c))
        assertEquals(10, share(a) + share(b) + share(c))
        assertEquals("the happiness of a city is its own and its share", 1 + 5, world.needsOf(a).localHappiness())
    }

    @Test
    fun `a city with the greater need is served first, and what it needs is all it gets before the others`() {
        world.difficulty.baseHappiness = 5
        val a = world.addCity(2) // needs 2
        val b = world.addCity(6) // needs 6
        world.refresh()
        // b 1, a 1, b 1, a 1, b 1: the order of the list breaks no tie here, the need does
        assertEquals(2, share(a))
        assertEquals(3, share(b))
    }

    @Test
    fun `a single city gets all of the pool`() {
        world.difficulty.baseHappiness = 7
        val city = world.addCity(1)
        world.giveHappiness(city, 50)
        world.refresh()
        assertEquals(7, share(city))
    }

    @Test
    fun `occupied and resisting cities get none, and a puppet gets none unless it is Venice's`() {
        world.difficulty.baseHappiness = 6
        val capital = world.addCity(8)
        val occupied = world.addCity(8)
        occupied.foundingCivObject = game.addCiv()
        val resisting = world.addCity(8)
        resisting.setFlag(CityFlags.Resistance, 3)
        val puppet = world.addCity(8)
        puppet.isPuppet = true
        world.refresh()
        assertEquals(6, share(capital))
        assertEquals(0, share(occupied))
        assertEquals(0, share(resisting))
        assertEquals(0, share(puppet))

        val venice = NeedsTestWorld("May not annex cities")
        venice.difficulty.baseHappiness = 6
        val first = venice.addCity(8)
        val vassal = venice.addCity(8)
        vassal.isPuppet = true
        venice.refresh()
        assertEquals("the puppet of a civilization that may not annex is a city like the others", 3, venice.needsOf(first).happinessFromEmpire)
        assertEquals(3, venice.needsOf(vassal).happinessFromEmpire)
    }

    @Test
    fun `a pool of zero or less gives nothing and terminates`() {
        for (base in listOf(0, -3)) {
            world = NeedsTestWorld()
            world.difficulty.baseHappiness = base
            val a = world.addCity(4)
            val b = world.addCity(4)
            world.refresh()
            assertEquals("pool $base", 0, share(a) + share(b))
            assertEquals(base, civ.stats.needs.pool)
        }
    }

    @Test
    fun `the unhappiness of the empire is shared among the cities, the first cities get the rest`() {
        setPublicOpinionUnhappiness(7)
        val a = world.addCity(3)
        val b = world.addCity(3)
        val c = world.addCity(3)
        world.refresh()
        assertEquals(3, world.needsOf(a).unhappinessFromEmpire)
        assertEquals(2, world.needsOf(b).unhappinessFromEmpire)
        assertEquals(2, world.needsOf(c).unhappinessFromEmpire)
        val d = world.addCity(3)
        d.isPuppet = true
        world.refresh()
        assertEquals("a puppet has none and does not count", 0, world.needsOf(d).unhappinessFromEmpire)
        assertEquals(3, world.needsOf(a).unhappinessFromEmpire)
        assertEquals("the city's share is part of its unhappiness", 3, world.needsOf(a).aggregated)
        assertEquals(3, world.needsOf(a).breakdown["Empire"])
    }

    //endregion
    //region Approval and thresholds

    /** An empire of one city of [unhappy] citizens who all are specialists, with [happy] happiness from a building: approval is min(200, happy x 100 / unhappy) / 2 */
    private fun scenario(happy: Int, unhappy: Int) {
        world = NeedsTestWorld()
        val city = world.addCity(maxOf(unhappy, 3))
        world.build(city, "[+500 Food]") // no famine
        if (unhappy > 0) world.addSpecialists(city, unhappy)
        if (happy > 0) world.giveHappiness(city, happy)
        world.refresh()
    }

    @Test
    fun `approval is 100 without unhappiness, otherwise min(200, happy x 100 over unhappy) over 2`() {
        scenario(0, 0)
        assertEquals("no unhappiness", 100, civ.getApproval())
        assertEquals(0, civ.stats.needs.unhappyCitizens)
        for ((happy, unhappy, expected) in listOf(Triple(7, 3, 100), Triple(3, 7, 21), Triple(0, 5, 0), Triple(5, 5, 50), Triple(1, 3, 16))) {
            scenario(happy, unhappy)
            assertEquals("$happy against $unhappy", expected, civ.getApproval())
            assertEquals(happy, civ.stats.needs.happyCitizens)
            assertEquals(unhappy, civ.stats.needs.unhappyCitizens)
        }
    }

    @Test
    fun `the stat Happiness is approval minus 50`() {
        scenario(3, 7) // 21
        assertEquals(21 - 50, civ.getHappiness())
        scenario(7, 3) // 100
        assertEquals(50, civ.getHappiness())
        scenario(0, 0)
        assertEquals(50, civ.getHappiness())
    }

    @Test
    fun `unhappy below 50, very unhappy below 35, super unhappy below 20`() {
        // 100 unhappy citizens: happy x 100 / 100 / 2 is half of the happiness
        for ((happy, states) in listOf(
            100 to Triple(false, false, false), // 50
            98 to Triple(true, false, false),   // 49
            70 to Triple(true, false, false),   // 35
            68 to Triple(true, true, false),    // 34
            40 to Triple(true, true, false),    // 20
            38 to Triple(true, true, true),     // 19
        )) {
            scenario(happy, 100)
            assertEquals("approval at $happy", happy / 2, civ.getApproval())
            assertEquals("unhappy at $happy", states.first, civ.isEmpireUnhappy())
            assertEquals("very unhappy at $happy", states.second, civ.isEmpireVeryUnhappy())
            assertEquals("super unhappy at $happy", states.third, civ.isEmpireSuperUnhappy())
            assertEquals(states.first, civ.stats.needs.isUnhappy())
            assertEquals(states.first, civ.getHappiness() < 0)
        }
    }

    @Test
    fun `without the key the empire is unhappy below happiness 0, very unhappy at -10, super unhappy at -20`() {
        val oldGame = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        OriginalHappinessModel.use(oldGame.ruleset)   // Brave New World plays the needs now
        val old = oldGame.addCiv()
        for ((happiness, expected) in listOf(0 to Triple(false, false, false), -1 to Triple(true, false, false), -9 to Triple(true, false, false),
            -10 to Triple(true, true, false), -19 to Triple(true, true, false), -20 to Triple(true, true, true))) {
            old.stats.happiness = happiness
            assertEquals("unhappy at $happiness", expected.first, old.isEmpireUnhappy())
            assertEquals("very unhappy at $happiness", expected.second, old.isEmpireVeryUnhappy())
            assertEquals("super unhappy at $happiness", expected.third, old.isEmpireSuperUnhappy())
        }
    }

    //endregion
    //region Effects

    private fun attackerModifiers(): Map<String, Int> {
        val attacker = game.addUnit("Warrior", civ, game.getTile(1, 1))
        val defender = game.addUnit("Warrior", game.addCiv(), game.getTile(2, 1))
        return BattleDamage.getAttackModifiers(MapUnitCombatant(attacker), MapUnitCombatant(defender), attacker.currentTile)
    }

    private fun defenderModifiers(): Map<String, Int> {
        val attacker = game.addUnit("Warrior", game.addCiv(), game.getTile(1, -1))
        val defender = game.addUnit("Warrior", civ, game.getTile(2, -1))
        return BattleDamage.getDefenceModifiers(MapUnitCombatant(attacker), MapUnitCombatant(defender), attacker.currentTile)
    }

    @Test
    fun `unhappy empires lose 10 percent combat strength and very unhappy ones 20`() {
        scenario(10, 5) // 100
        assertEquals(0, civ.stats.needs.combatPenalty)
        assertEquals("a Counter answers 0 for what is not in it", 0, attackerModifiers()["Unhappiness"])

        scenario(4, 5) // 40: unhappy
        assertEquals(-10, civ.stats.needs.combatPenalty)
        assertEquals(-10, attackerModifiers()["Unhappiness"])
        assertEquals("defending too", -10, defenderModifiers()["Unhappiness"])

        scenario(3, 6) // 25: very unhappy
        assertEquals(-20, civ.stats.needs.combatPenalty)
        assertEquals(-20, attackerModifiers()["Unhappiness"])
        assertEquals(-20, defenderModifiers()["Unhappiness"])
    }

    @Test
    fun `the empire's growth and settler penalties are 2_5 per approval point under 50`() {
        scenario(10, 5) // 100
        assertEquals(0, civ.stats.needs.growthPenalty)
        assertEquals(0, civ.stats.needs.settlerPenalty)
        scenario(4, 5) // 40
        assertEquals(-25, civ.stats.needs.growthPenalty)
        assertEquals(-25, civ.stats.needs.settlerPenalty)
        scenario(3, 6) // 25
        assertEquals(-62, civ.stats.needs.growthPenalty)
        assertEquals("at least -75", -62, civ.stats.needs.settlerPenalty)
        scenario(0, 5) // 0
        assertEquals("at least -100", -100, civ.stats.needs.growthPenalty)
        assertEquals("at least -75", -75, civ.stats.needs.settlerPenalty)
    }

    @Test
    fun `golden age points from happiness are the positive deltas, raised by the policies`() {
        world = NeedsTestWorld("[+20]% golden age points from happiness", "[+20]% golden age points from happiness")
        val a = world.addCity(2)
        val b = world.addCity(2)
        world.giveHappiness(a, 10)
        world.addSpecialists(b, 2)
        world.refresh()
        assertEquals(10, world.needsOf(a).delta)
        assertEquals(-2, world.needsOf(b).delta)
        assertEquals("only the positive delta, 10 x 140 %", 14, civ.stats.needs.goldenAgePointsFromHappiness())

        world = NeedsTestWorld()
        val c = world.addCity(2)
        world.giveHappiness(c, 10)
        world.refresh()
        assertEquals(10, civ.stats.needs.goldenAgePointsFromHappiness())
    }

    @Test
    fun `an empire without a city is at the unhappy threshold and not unhappy`() {
        assertEquals(civ.gameInfo.ruleset.modOptions.constants.needs.unhappyThreshold, civ.getApproval())
        world.refresh()
        assertEquals(0, civ.getHappiness())
        assertFalse(civ.isEmpireUnhappy())
    }

    //endregion
}
