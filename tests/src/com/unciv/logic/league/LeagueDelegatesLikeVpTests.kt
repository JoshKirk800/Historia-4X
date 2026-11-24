package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The delegates of Vox Populi, `ModConstants.leagueDelegatesLikeVp` (CvLeague::CalculateStartingVotesForMember, V:CvVotingClasses.cpp:4046-4390): [League.getDelegateSources],
 * the four sources that CvPlayer::DoProcessVotes caches at the start of the civilization's turn ([League.refreshCachedSources]), and the World Religion and World Ideology.
 * Every source is given by a unique of the civilization's nation, the one place of a test that needs no culture, buildings or techs.
 */
@RunWith(GdxTestRunner::class)
class LeagueDelegatesLikeVpTests {
    private lateinit var game: TestGame
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val constants get() = game.ruleset.modOptions.constants

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        constants.leagueDelegatesLikeVp = true
    }

    //region Helpers
    private fun cityTile() = game.tileMap.tileList[7 * nextCell++]

    private fun major(vararg uniques: String): Civilization {
        val civ = game.addCiv(*uniques)
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private fun cityState(): Civilization {
        val civ = game.addCiv(cityStateType = "Cultured")
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private fun meetAll() {
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    /** The league as the Congress has it once founded; its session gives every civilization 1 delegate and the host 1 more */
    private fun found(founder: Civilization): League {
        founder.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        return league
    }

    private fun friends(x: Civilization, y: Civilization) {
        x.getDiplomacyManager(y)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        y.getDiplomacyManager(x)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
    }

    private fun sources(civ: Civilization) = league.getDelegateSources(civ)

    private fun follow(religionName: String, civ: Civilization) {
        for (city in civ.cities) city.religion.addPressure(religionName, 1000)
    }

    private fun belief(vararg uniques: String): Belief = game.createBelief(BeliefType.Follower, *uniques)
    //endregion

    //region Off: the delegates of Brave New World
    @Test
    fun `without the switch the sources are not read and the delegates are those of Brave New World`() {
        constants.leagueDelegatesLikeVp = false
        val a = major("[1] World Congress Delegates for every 2 Declarations of Friendship we have")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        friends(a, b)
        friends(a, c)
        league.refreshCachedSources(a)
        assertTrue("nothing is cached without the switch", league.members.getValue(a.civID).cachedSources.isEmpty())
        assertEquals("1 of the era and 1 as the host, and nothing for the friendships", 2, league.getDelegates(a))
    }

    @Test
    fun `without the switch the World Religion and World Ideology are those of Brave New World`() {
        constants.leagueDelegatesLikeVp = false
        val a = major()
        val b = major()
        val c = major()
        meetAll()
        found(a)
        val religion = game.addReligion(a)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[+1 Food] from every [Shrine]")))
        a.getCapital()!!.religion.religionThisIsTheHolyCityOf = religion.name
        follow(religion.name, a)
        follow(religion.name, b)
        for (civ in listOf(b, c)) civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        b.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        c.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        league.activeResolutions.add(ActiveResolution(1, "World Religion", null, religion.name, 0))
        league.activeResolutions.add(ActiveResolution(2, "World Ideology", null, "Freedom", 0))
        league.setTransients(gameInfo)
        val religionVotes = game.ruleset.resolutions["World Religion"]!!.votesForFollowingReligion
        val ideologyVotes = game.ruleset.resolutions["World Ideology"]!!.votesForFollowingIdeology
        // Brave New World: the flat votes for each civilization that follows it, no bonus for the holy city and none for the other followers or ideology allies
        assertEquals("1 of the era, 1 as the host, the religion's votes", 2 + religionVotes, league.getDelegates(a))
        assertEquals("1 of the era, the religion's and the ideology's votes", 1 + religionVotes + ideologyVotes, league.getDelegates(b))
    }

    @Test
    fun `turning the switch off clears what was cached`() {
        val a = major("[1] World Congress Delegates for every 2 Declarations of Friendship we have")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        friends(a, b)
        friends(a, c)
        league.refreshCachedSources(a)
        assertEquals(1, league.members.getValue(a.civID).cachedSources[League.SOURCE_FRIENDSHIPS])
        constants.leagueDelegatesLikeVp = false
        league.refreshCachedSources(a)
        assertTrue(league.members.getValue(a.civID).cachedSources.isEmpty())
    }
    //endregion

    //region The cache
    @Test
    fun `a declaration of friendship counts from the next refresh and not at once`() {
        val a = major("[1] World Congress Delegates for every 2 Declarations of Friendship we have")
        val others = List(4) { major() }
        meetAll()
        found(a)
        friends(a, others[0])
        friends(a, others[1])
        assertEquals("not yet refreshed: only the era's and the host's", 2, league.getDelegates(a))
        league.refreshCachedSources(a)
        assertEquals("2 friends are one pair", 3, league.getDelegates(a))
        // a third friend does not change what two friends gave: the floor is before the multiplication
        friends(a, others[2])
        league.refreshCachedSources(a)
        assertEquals("3 friends are one pair", 3, league.getDelegates(a))
        friends(a, others[3])
        assertEquals("the fourth counts from the next refresh only", 3, league.getDelegates(a))
        league.refreshCachedSources(a)
        assertEquals("4 friends are two pairs", 4, league.getDelegates(a))
    }

    @Test
    fun `friendship delegates are the pairs times the amount and the amounts of all uniques are added`() {
        val a = major(
            "[2] World Congress Delegates for every 2 Declarations of Friendship we have",
            "[1] World Congress Delegates for every 2 Declarations of Friendship we have")
        val others = List(3) { major() }
        meetAll()
        found(a)
        for (other in others) friends(a, other)
        league.refreshCachedSources(a)
        assertEquals("3 friends are 1 pair, times 2 + 1", 3, sources(a)[League.SOURCE_FRIENDSHIPS])
    }

    @Test
    fun `defensive pacts give their number times the amount`() {
        val a = major("[2] World Congress Delegates for every Defensive Pact we have")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        a.getDiplomacyManager(b)!!.signDefensivePact(20)
        a.getDiplomacyManager(c)!!.signDefensivePact(20)
        league.refreshCachedSources(a)
        assertEquals(4, sources(a)[League.SOURCE_PACTS])
    }

    @Test
    fun `an original capital of another major civilization counts, the own and a city-state's do not`() {
        val a = major("[3] World Congress Delegates for every original capital of another civilization that we hold")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        league.refreshCachedSources(a)
        assertEquals("a holds only its own capital", null, sources(a)[League.SOURCE_CAPITALS])

        val bCapital = b.getCapital()!!
        bCapital.moveToCiv(a)
        assertTrue(bCapital.isOriginalCapital)
        assertEquals(b, bCapital.foundingCivObject)
        league.refreshCachedSources(a)
        assertEquals("b's capital is held by a", 3, sources(a)[League.SOURCE_CAPITALS])

        val state = cityState()
        state.getCapital()!!.moveToCiv(a)
        league.refreshCachedSources(a)
        assertEquals("a conquered city-state's capital adds nothing", 3, sources(a)[League.SOURCE_CAPITALS])
    }

    @Test
    fun `faith is floored once after adding every building's whole hundredths`() {
        // two buildings that each give 1 delegate per 10 cities following the religion: 15 cities give 150 + 150 hundredths = 3, and not 1 + 1
        val a = major(
            "1 World Congress Delegate for every [10] cities following our religion",
            "1 World Congress Delegate for every [10] cities following our religion")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        val religion = game.addReligion(a)
        repeat(12) { game.addCity(b, game.tileMap.tileList[100 + 7 * it]) }
        // 15 cities follow it: all of b's 13 (its capital and 12), a's one and c's one
        for (city in b.cities + a.cities + c.cities) city.religion.addPressure(religion.name, 1000)
        assertEquals(15, a.religionManager.numberOfCitiesFollowingThisReligion())
        league.refreshCachedSources(a)
        assertEquals(3, sources(a)[League.SOURCE_FAITH])
    }

    @Test
    fun `faith gives nothing to a civilization without a religion`() {
        val a = major("1 World Congress Delegate for every [10] cities following our religion")
        val b = major()
        meetAll()
        found(a)
        league.refreshCachedSources(a)
        assertEquals(null, sources(a)[League.SOURCE_FAITH])
    }
    //endregion

    //region Read live
    @Test
    fun `gold per turn is divided by the sum of the divisors once and capped by a quarter of the city-states`() {
        val a = major(
            "1 World Congress Delegate for every [20] Gold per turn",
            "1 World Congress Delegate for every [30] Gold per turn")
        val b = major()
        meetAll()
        repeat(8) { cityState() }
        found(a)
        val capital = a.getCapital()!!
        capital.cityConstructions.addBuilding(game.createBuilding("[+100 Gold]"))
        capital.cityStats.update()
        val gross = a.stats.getStatMapForNextTurn().values.sumOf { it.gold.toInt() }
        assertTrue("the fixture must really make gold, and the buildings' maintenance is no part of the gross income: $gross", gross >= 100)
        // the divisors are added: 20 + 30 = 50, one quotient of the gross gold, and at most 8 city-states / 4 = 2
        assertEquals(2, sources(a)[League.SOURCE_GOLD])
        // the same gold with the divisors taken one by one would give 5 + 3 = 8 before the cap, so the cap, not the sum, is what this pins; lower the gold to see the quotient
        capital.cityConstructions.removeBuilding(capital.cityConstructions.getBuiltBuildings().first { it.uniques.contains("[+100 Gold]") })
        capital.cityConstructions.addBuilding(game.createBuilding("[+60 Gold]"))
        capital.cityStats.update()
        assertEquals("60 gold per 50 is 1", 1, sources(a)[League.SOURCE_GOLD])
    }

    @Test
    fun `improvements of the religion give the fractions added exactly and truncated once`() {
        val a = major()
        val b = major()
        meetAll()
        found(a)
        val religion = game.addReligion(a)
        // 1/2 and 1/3 of a delegate per Farm owned, 5/6 together: 5 farms give 25/6 = 4 delegates, where truncating each fraction would give 2 + 1 = 3
        religion.addBeliefs(listOf(
            belief("1 World Congress Delegate for every [2] [Farm] we own"),
            belief("1 World Congress Delegate for every [3] [Farm] we own")))
        val capital = a.getCapital()!!
        var placed = 0
        for (tile in game.tileMap.values) {
            if (placed == 5) break
            if (tile.isWater || tile.improvement != null || tile.getCity() != null) continue
            game.addTileToCity(capital, tile)
            tile.setOwningCity(capital)
            tile.setImprovement("Farm", a)
            placed++
        }
        assertEquals(5, placed)
        assertEquals(4, sources(a)[League.SOURCE_IMPROVEMENTS])
    }
    //endregion

    //region World Religion and World Ideology
    @Test
    fun `the owner of the holy city gets the votes and one more for every other living major that follows the religion`() {
        val a = major()
        val b = major()
        val c = major()
        meetAll()
        found(a)
        val religion = game.addReligion(a)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[+1 Food] from every [Shrine]")))
        a.getCapital()!!.religion.religionThisIsTheHolyCityOf = religion.name
        follow(religion.name, a)
        follow(religion.name, b)
        league.activeResolutions.add(ActiveResolution(1, "World Religion", null, religion.name, 0))
        league.setTransients(gameInfo)

        assertEquals("the owner of the holy city: 2 for the religion and 1 for b", 3, sources(a)[League.SOURCE_WORLD_RELIGION])
        assertEquals("b only follows it", 2, sources(b)[League.SOURCE_WORLD_RELIGION])
        assertEquals(null, sources(c)[League.SOURCE_WORLD_RELIGION])
    }

    @Test
    fun `a second World Religion resolution adds nothing once the first has applied`() {
        val a = major()
        val b = major()
        meetAll()
        found(a)
        val first = game.addReligion(a)
        first.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[+1 Food] from every [Shrine]")))
        val second = game.addReligion(a)
        second.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[+1 Food] from every [Shrine]")))
        // a holds the holy city of both religions (its capital and a second city), and b follows the first only
        a.getCapital()!!.religion.religionThisIsTheHolyCityOf = first.name
        game.addCity(a, game.tileMap.tileList[150]).religion.religionThisIsTheHolyCityOf = second.name
        follow(first.name, b)
        league.activeResolutions.add(ActiveResolution(1, "World Religion", null, first.name, 0))
        league.activeResolutions.add(ActiveResolution(2, "World Religion", null, second.name, 0))
        league.setTransients(gameInfo)
        val votes = game.ruleset.resolutions["World Religion"]!!.votesForFollowingReligion
        assertTrue("a is the owner of both, which the test needs", gameInfo.getCities().count { it.civ == a && it.isHolyCity() } == 2)
        assertEquals("the first resolution's votes and 1 for b; the second adds nothing, as Vox Populi returns from the loop", votes + 1, sources(a)[League.SOURCE_WORLD_RELIGION])
    }

    @Test
    fun `a World Ideology gives one more for every other civilization of the ideology and counts the dead`() {
        val a = major()
        val b = major()
        val c = major()
        val dead = major()
        meetAll()
        found(a)
        for (civ in listOf(b, c, dead)) civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        b.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        c.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        dead.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        dead.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
        assertFalse("the fixture needs a dead civilization", dead.isAlive())
        league.activeResolutions.add(ActiveResolution(1, "World Ideology", null, "Freedom", 0))
        league.setTransients(gameInfo)
        val votes = game.ruleset.resolutions["World Ideology"]!!.votesForFollowingIdeology
        assertEquals("the votes, one for c and one for the dead civilization", votes + 2, sources(b)[League.SOURCE_WORLD_IDEOLOGY])
        assertEquals(null, sources(a)[League.SOURCE_WORLD_IDEOLOGY])
    }
    //endregion

    //region The map
    @Test
    fun `the sources add up to the delegates and keep Vox Populi's order`() {
        val a = major("[2] World Congress Delegates", "[1] World Congress Delegates for every 2 Declarations of Friendship we have")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        friends(a, b)
        friends(a, c)
        league.refreshCachedSources(a)
        val map = sources(a)
        assertEquals(map.values.sum(), league.getDelegates(a))
        assertEquals(listOf(League.SOURCE_SESSION, League.SOURCE_HOST, League.SOURCE_BUILDINGS_AND_POLICIES, League.SOURCE_FRIENDSHIPS), map.keys.toList())
        assertEquals(listOf(1, 1, 2, 1), map.values.toList())
    }

    @Test
    fun `a civilization that cannot vote has no sources`() {
        val a = major()
        val b = major()
        meetAll()
        found(a)
        val state = cityState()
        assertTrue(league.getDelegateSources(state).isEmpty())
        assertEquals(0, league.getDelegates(state))
    }

    @Test
    fun `the cache is saved with the member and copied by clone`() {
        val a = major("[1] World Congress Delegates for every 2 Declarations of Friendship we have")
        val b = major()
        val c = major()
        meetAll()
        found(a)
        friends(a, b)
        friends(a, c)
        league.refreshCachedSources(a)
        val copy = league.clone()
        assertEquals(mapOf(League.SOURCE_FRIENDSHIPS to 1), copy.members.getValue(a.civID).cachedSources)
    }
    //endregion
}
