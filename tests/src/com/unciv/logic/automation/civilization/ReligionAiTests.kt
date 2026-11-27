package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.civilization.ReligionAutomation.FaithPurchase
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.unit.ReligiousUnitAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Decision-level checks of the CvReligionAI port (CvReligionClasses.cpp) against the DLL formulas, with the BNW beliefs */
@RunWith(GdxTestRunner::class)
class ReligionAiTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
    }

    /** A religion with a Founder belief, so that it counts as founded (a major religion) */
    private fun foundReligion(civ: Civilization): Religion {
        val religion = game.addReligion(civ)
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        setState(civ, ReligionState.Religion)
        return religion
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(x, y)

    /** The religion state has a private setter; the decisions under test only read it */
    private fun setState(civ: Civilization, state: ReligionState) {
        ReligionManager::class.java.getDeclaredField("religionState").apply { isAccessible = true }.set(civ.religionManager, state)
    }

    private fun neutralCiv(vararg uniques: String): Civilization {
        val civ = game.addCiv(*uniques)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        civ.ai.setGrandStrategyAndPursuit(null)
        return civ
    }

    private fun playerScore(civ: Civilization, belief: String): Int =
        BeliefScoring.scoreBeliefForPlayer(BeliefAiProfile.of(game.ruleset.beliefs[belief]!!, civ), BeliefScoringContext(civ))

    //region ScoreBeliefForPlayer, one flavor at a time

    @Test
    fun `offense scores faith from kills, doubled for world conquest`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        // God of War: 50 faith from kills × 4 distance × offense / 100
        assertEquals(50 * 4 * 5 / 100, playerScore(civ, "God of War"))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertEquals(50 * 4 * 5 / 100 * 2, playerScore(civ, "God of War"))
    }

    @Test
    fun `defense scores the friendly-territory combat bonus`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Defense, 4)
        assertEquals(4 * 20 / 4, playerScore(civ, "Defender of the Faith"))
    }

    @Test
    fun `happiness scores happiness per following city with offense minus defense`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 6)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 2)
        // Ceremonial Burial: 1 happiness per 2 following cities × (happiness + 2 × offense − defense)
        assertEquals((0.5f * (6 + 4)).toInt(), playerScore(civ, "Ceremonial Burial"))
    }

    @Test
    fun `culture scores culture per foreign followers`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        // World Church: 1 culture per 5 foreign followers: 10 × culture / 5
        assertEquals(10 * 5 / 5, playerScore(civ, "World Church"))
    }

    @Test
    fun `gold scores gold per following city and per followers`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Gold, 7)
        assertEquals(2 * 7, playerScore(civ, "Church Property"))
        // Tithe: 1 gold per 4 followers: gold × 4 / 4
        assertEquals(7 * 4 / 4, playerScore(civ, "Tithe"))
    }

    @Test
    fun `great people scores faith from expended great people`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.GreatPeople, 3)
        assertEquals(3 * 50 / 10, playerScore(civ, "Reliquary"))
    }

    @Test
    fun `science scores science from spreading plus the early spread-strength term`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Science, 5)
        // Interfaith Dialogue: science × 10 / 5, plus 2 × science while fewer than a third of the religions are enhanced
        assertEquals(5 * 10 / 5 + 2 * 5, playerScore(civ, "Interfaith Dialogue"))
    }

    @Test
    fun `diplomacy scores the city-state resting influence`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 7)
        assertEquals(7 * 15 / 7, playerScore(civ, "Papal Primacy"))
    }

    @Test
    fun `expansion scores spread distance by the share of enhanced religions`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 4)
        val distance = 3 * 100 / game.ruleset.ai.define(AiDefine.RELIGION_ADJACENT_CITY_DISTANCE)
        assertEquals(2 * 4 + distance, playerScore(civ, "Itinerant Preachers"))
        // every religion enhanced: a quarter
        val founder = game.addCiv()
        foundReligion(founder)
        setState(founder, ReligionState.EnhancedReligion)
        while (BeliefScoringContext(civ).religionsEnhancedPercent < 66) {
            val other = game.addCiv()
            foundReligion(other)
            setState(other, ReligionState.EnhancedReligion)
        }
        assertEquals((2 * 4 + distance) / 4, playerScore(civ, "Itinerant Preachers"))
    }

    @Test
    fun `reformation beliefs score their effects and the barbarian strategy`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 10)
        assertEquals(15, playerScore(civ, "Heathen Conversion"))
        civ.ai.militaryStrategies["MILITARYAISTRATEGY_ERADICATE_BARBARIANS"] = 0
        assertEquals(40, playerScore(civ, "Heathen Conversion"))
        assertEquals(2 * 20, playerScore(civ, "Sacred Sites"))
        assertEquals(5 * 5, playerScore(civ, "Underground Sect"))
        assertEquals(30 * 10 / 5, playerScore(civ, "Charitable Missions"))
        assertEquals(50 / 2, playerScore(civ, "Unity of the Prophets"))
    }

    @Test
    fun `pantheon beliefs score half`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 10)
        val belief = game.ruleset.beliefs["God of War"]!!
        // 5 base + 50 × 4 × 10 / 100, halved
        assertEquals((5 + 20) / 2, BeliefScoring.scoreBelief(belief, BeliefScoringContext(civ)))
    }
    //endregion

    @Test
    fun `top choices roll only among the three best, each in proportion`() {
        val choices = listOf("a" to 10, "b" to 50, "c" to 40, "d" to 30, "e" to 5)
        val picks = (0 until 2000).map { ReligionAutomation.chooseFromTopChoices(choices, 3, Random(it))!! }
        assertEquals(setOf("b", "c", "d"), picks.toSet())
        val b = picks.count { it == "b" }
        assertTrue("b is 50/120 of the rolls, got $b", b in 750..920)
        assertEquals(null, ReligionAutomation.chooseFromTopChoices(emptyList<Pair<String, Int>>(), 3, Random(0)))
    }

    //region DoFaithPurchases

    private fun foundedCiv(vararg uniques: String): Pair<Civilization, Religion> {
        val civ = game.addCiv(*uniques)
        val capital = game.addCity(civ, tile(0, 0))
        val religion = foundReligion(civ)
        setState(civ, ReligionState.EnhancedReligion)
        capital.religion.religionThisIsTheHolyCityOf = religion.name
        capital.religion.addPressure(religion.name, 5000)
        civ.religionManager.storedFaith = 100_000
        return civ to religion
    }

    private fun follow(city: City, religion: Religion, population: Int = 3) {
        city.population.setPopulation(population)
        city.religion.addPressure(religion.name, 5000)
    }

    private fun lockOutFounding() {
        while (game.gameInfo.civilizations.first().religionManager.remainingFoundableReligions() > 0) {
            val founder = game.addCiv()
            foundReligion(founder)
        }
    }

    @Test
    fun `saves while a religion can still be founded or enhanced`() {
        val civ = game.addCiv()
        game.addCity(civ, tile(0, 0))
        assertEquals(FaithPurchase.Save, ReligionAutomation.chooseFaithPurchase(civ))
        foundReligion(civ)
        setState(civ, ReligionState.Religion)
        assertEquals(FaithPurchase.Save, ReligionAutomation.chooseFaithPurchase(civ))
    }

    @Test
    fun `locked out of founding buys a faith building of the majority, then in the Industrial era a Great Person`() {
        val civ = game.addCiv("May buy [Great Scientist] units with [Faith] [in all cities]")
        val city = game.addCity(civ, tile(0, 0))
        civ.religionManager.storedFaith = 100_000
        lockOutFounding()
        assertEquals(FaithPurchase.Save, ReligionAutomation.chooseFaithPurchase(civ))
        civ.tech.era = game.ruleset.eras["Industrial era"]!!
        assertEquals(FaithPurchase.LockedOutGreatPerson, ReligionAutomation.chooseFaithPurchase(civ))

        val other = game.addCiv()
        val religion = foundReligion(other)
        religion.addBelief("Pagodas")
        follow(city, religion)
        assertEquals(FaithPurchase.LockedOutFaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
    }

    @Test
    fun `a city needing a prophet critically comes first, while five or fewer prophets were earned`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val city = game.addCity(civ, tile(3, 0))
        follow(city, hereticReligion)
        // 3 heretics × 100 / (3 + 1) × 3 > 50
        assertEquals(FaithPurchase.Prophet, ReligionAutomation.chooseFaithPurchase(civ))
        civ.civConstructions.boughtItemsWithIncreasingPrice.add(civ.religionManager.getGreatProphetEquivalent()!!.name, 6)
        assertEquals(FaithPurchase.MissionaryForOwnCities, ReligionAutomation.chooseFaithPurchase(civ))
        assertEquals(religion, ReligionAutomation.religionToSpread(civ))
    }

    @Test
    fun `converted cities get the faith building, then non-faith buildings, then the Great Person`() {
        val (civ, religion) = foundedCiv(
            "May buy [Monument] buildings with [Faith] [in all cities]",
            "May buy [Great Scientist] units with [Faith] [in all cities]",
        )
        religion.addBelief("Pagodas")
        assertEquals(FaithPurchase.FaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
        val capital = civ.cities.first()
        capital.cityConstructions.addBuilding(civ.getEquivalentBuilding("Pagoda"))
        assertEquals(FaithPurchase.NonFaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
        capital.cityConstructions.addBuilding("Monument")
        assertTrue(ReligionAutomation.chooseFaithPurchase(civ) != FaithPurchase.GreatPerson)
        civ.tech.era = game.ruleset.eras["Industrial era"]!!
        assertEquals(FaithPurchase.GreatPerson, ReligionAutomation.chooseFaithPurchase(civ))
    }

    @Test
    fun `then missionaries for puppets, civilizations without a religion, an inquisitor, and religion founders`() {
        val (civ, religion) = foundedCiv()
        religion.addBelief("Pagodas")
        val capital = civ.cities.first()
        capital.cityConstructions.addBuilding(civ.getEquivalentBuilding("Pagoda"))
        val puppet = game.addCity(civ, tile(-3, 0))
        follow(puppet, religion)
        puppet.isPuppet = true
        assertEquals(FaithPurchase.MissionaryForPuppets, ReligionAutomation.chooseFaithPurchase(civ))
        puppet.cityConstructions.addBuilding(civ.getEquivalentBuilding("Pagoda"))

        val pagan = game.addCiv()
        val paganCity = game.addCity(pagan, tile(0, 4))
        paganCity.getCenterTile().setExplored(civ, true)
        assertEquals(FaithPurchase.MissionaryForCivsWithoutReligion, ReligionAutomation.chooseFaithPurchase(civ))
        val paganReligion = foundReligion(pagan)

        assertEquals(FaithPurchase.Inquisitor, ReligionAutomation.chooseFaithPurchase(civ))
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, capital.getCenterTile())
        inquisitor.religion = religion.name
        assertEquals(FaithPurchase.MissionaryForReligionFounders, ReligionAutomation.chooseFaithPurchase(civ))

        follow(paganCity, religion)
        assertEquals(FaithPurchase.OtherFaithBuilding, ReligionAutomation.chooseFaithPurchase(civ))
        assertTrue(paganReligion.isMajorReligion())
    }
    //endregion

    //region GetDesiredFaithGreatPerson

    private fun greatPersonCiv(): Civilization {
        val civ = game.addCiv(
            *listOf("Great Writer", "Great Scientist", "Great Merchant", "Great Engineer", "Great General")
                .map { "May buy [$it] units with [Faith] [in all cities]" }.toTypedArray()
        )
        game.addCity(civ, tile(0, 0))
        return civ
    }

    private fun desired(civ: Civilization) = ReligionAutomation.getDesiredFaithGreatPerson(civ)?.name

    @Test
    fun `faith great person follows the grand strategy, scientist always 1000`() {
        val civ = greatPersonCiv()
        // no grand strategy: scientist 1000 (the DLL overwrites its Spaceship check), the others 400 / 500
        assertEquals("Great Scientist", desired(civ))
        // one scientist bought from faith: 1000 / 2 = 500, below each grand strategy's 1000 (engineer: 2000 in the homestretch)
        civ.civConstructions.boughtItemsWithIncreasingPrice.add("Great Scientist", 1)
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CULTURE)
        assertEquals("Great Writer", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.UNITED_NATIONS)
        assertEquals("Great Merchant", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        assertEquals("Great General", desired(civ))
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.SPACESHIP)
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH"] = 0
        assertEquals("Great Engineer", desired(civ))
    }

    @Test
    fun `great generals are divided by the ones we already have`() {
        val civ = greatPersonCiv()
        civ.ai.setGrandStrategyAndPursuit(GrandStrategyAi.CONQUEST)
        civ.civConstructions.boughtItemsWithIncreasingPrice.add("Great Scientist", 3) // 250
        civ.civConstructions.boughtItemsWithIncreasingPrice.add("Great Engineer", 1) // 250
        game.addUnit("Great General", civ, tile(1, 1))
        // general 1000 / (1 + 1 owned) = 500 > writer 400
        assertEquals("Great General", desired(civ))
        game.addUnit("Great General", civ, tile(1, 2))
        // 1000 / 3 = 333 < writer 400
        assertEquals("Great Writer", desired(civ))
    }
    //endregion

    @Test
    fun `missionary target scores own cities x5, cities without a religion x3, peace x2, holy cities halved`() {
        val (civ, religion) = foundedCiv()
        val missionaryTile = tile(0, 1)
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, missionaryTile)
        missionary.religion = religion.name
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)

        // A city of ours held firmly by another religion: the visit cannot flip it
        val own = game.addCity(civ, tile(3, 0))
        own.religion.addPressure(hereticReligion.name, 1_000_000)
        own.getCenterTile().setExplored(civ, true)
        val d1 = missionaryTile.aerialDistanceTo(own.getCenterTile())
        assertEquals((100 * 5 - d1) * 2, ReligionAutomation.scoreCityForMissionary(civ, own, missionary, religion))

        val pagan = game.addCiv()
        val paganCity = game.addCity(pagan, tile(-3, 3))
        paganCity.religion.addPressure(hereticReligion.name, 1_000_000)
        paganCity.getCenterTile().setExplored(civ, true)
        val d2 = missionaryTile.aerialDistanceTo(paganCity.getCenterTile())
        assertEquals((100 * 3 - d2) * 2, ReligionAutomation.scoreCityForMissionary(civ, paganCity, missionary, religion))
        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        civ.getDiplomacyManager(pagan)!!.declareWar()
        assertEquals(100 * 3 - d2, ReligionAutomation.scoreCityForMissionary(civ, paganCity, missionary, religion))

        // the heretics' holy city: owner has a religion (×1), peace ×2, holy ÷2
        val holy = game.addCity(heretic, tile(0, -4))
        holy.religion.religionThisIsTheHolyCityOf = hereticReligion.name
        holy.religion.addPressure(hereticReligion.name, 1_000_000)
        holy.getCenterTile().setExplored(civ, true)
        val d3 = missionaryTile.aerialDistanceTo(holy.getCenterTile())
        assertEquals((100 - d3) * 2 / 2, ReligionAutomation.scoreCityForMissionary(civ, holy, missionary, religion))

        // a city we could flip doubles; a city of our religion or unrevealed scores 0
        val small = game.addCity(pagan, tile(4, -4))
        small.getCenterTile().setExplored(civ, true)
        assertTrue(ReligionAutomation.shouldBecomeNewMajority(small, religion.name, 1_000_000))
        assertEquals(0, ReligionAutomation.scoreCityForMissionary(civ, civ.cities.first(), missionary, religion))
        small.getCenterTile().setExplored(civ, false)
        assertEquals(0, ReligionAutomation.scoreCityForMissionary(civ, small, missionary, religion))
        assertEquals(own, ReligionAutomation.chooseMissionaryTargetCity(civ, missionary))
    }

    @Test
    fun `missionaries leave a friend with a religion of its own, and a civilization we promised, alone`() {
        val (civ, religion) = foundedCiv()
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 1))
        missionary.religion = religion.name
        val other = game.addCiv()
        val city = game.addCity(other, tile(3, 0))
        city.getCenterTile().setExplored(civ, true)
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val ours = civ.getDiplomacyManager(other)!!
        fun score() = ReligionAutomation.scoreCityForMissionary(civ, city, missionary, religion)

        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertTrue("a friend without a religion: nothing asked", score() > 0)
        foundReligion(other)
        assertEquals("a friend with a religion", 0, score())
        ours.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
        assertTrue("a stranger", score() > 0)
        other.getDiplomacyManager(civ)!!.setFlag(DiplomacyFlags.AgreedToNotSpreadReligion, 20)
        assertEquals("our promise to them", 0, score())
        other.getDiplomacyManager(civ)!!.removeFlag(DiplomacyFlags.AgreedToNotSpreadReligion)
        ours.setFlag(DiplomacyFlags.AgreedToNotSpreadReligion, 20)
        assertTrue("their promise to us does not bind us", score() > 0)
    }

    //region Targets like Vox Populi (religionCultureAiLikeVp)

    /** A city of [owner] at [x], [y] with [population] and these accumulated pressures (atheism keeps its starting 100 plus [atheism]) */
    private fun cityWith(owner: Civilization, x: Int, y: Int, population: Int, vararg pressures: Pair<String, Int>, atheism: Int = 0): City {
        val city = game.addCity(owner, tile(x, y), initialPopulation = population)
        if (atheism > 0) city.religion.addPressure(com.unciv.Constants.noReligionName, atheism)
        for ((religion, amount) in pressures) city.religion.addPressure(religion, amount)
        for (civ in game.gameInfo.civilizations) city.getCenterTile().setExplored(civ, true)
        return city
    }

    @Test
    fun `with Vox Populi a missionary weighs the distances and how much its visit would move the city`() {
        val (civ, religion) = foundedCiv()
        assertEquals("BNW missionary: strength 1000, 2 spreads", 1000, ReligionAutomation.missionaryUnit(civ)!!.religiousStrength)
        val missionaryTile = tile(0, 1)
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, missionaryTile)
        missionary.religion = religion.name
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val pagan = game.addCiv()
        fun distances(city: City) = 50 - city.getCenterTile().aerialDistanceTo(tile(0, 0)) - city.getCenterTile().aerialDistanceTo(missionaryTile)

        // 2000 of 5000 is an impact of 40 (+17 × 3), our share 20 (+6 × 3); a major without a religion, its state religion not ours: × 3/2
        val paganCity = cityWith(pagan, 3, 0, 1, hereticReligion.name to 4000, religion.name to 1000)
        assertEquals((distances(paganCity) + 51 + 18) * 3 / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, paganCity, missionary, religion))
        assertEquals("without a unit only the holy city's distance",
            (50 - 3 + 51 + 18) * 3 / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, paganCity, null, religion))

        // no founded religion there: the impact is capped at 100 (+77 × 3, +46 × 3), × 3/2 without a majority, × 3/2 for the owner
        val emptyCity = cityWith(pagan, -3, 3, 1)
        assertEquals((distances(emptyCity) + 231 + 138) * 3 / 2 * 3 / 2,
            ReligionAutomation.scoreCityForMissionaryLikeVp(civ, emptyCity, missionary, religion))

        // our own city doubles, without the bonus for an owner without a religion
        val own = cityWith(civ, 0, -3, 1, hereticReligion.name to 4000, religion.name to 1000)
        assertEquals((distances(own) + 51 + 18) * 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, own, missionary, religion))

        // the holy city of the owner's religion is halved, an owner with a religion gets no bonus
        val holy = cityWith(heretic, 4, -4, 1, hereticReligion.name to 4000, religion.name to 1000)
        holy.religion.religionThisIsTheHolyCityOf = hereticReligion.name
        assertEquals((distances(holy) + 51 + 18) / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, holy, missionary, religion))
        // a founder without its holy city owns no religion (CvPlayerReligions::OwnsReligion): the bonus, and nothing to halve
        holy.religion.religionThisIsTheHolyCityOf = null
        assertEquals((distances(holy) + 51 + 18) * 3 / 2, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, holy, missionary, religion))

        civ.diplomacyFunctions.makeCivilizationsMeet(pagan)
        civ.getDiplomacyManager(pagan)!!.declareWar()
        assertEquals("at war", 0, ReligionAutomation.scoreCityForMissionaryLikeVp(civ, paganCity, missionary, religion))
    }

    @Test
    fun `with Vox Populi missionaries go to the best city first and leave one another's targets alone`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(0, 1))
        missionary.religion = religion.name
        val other = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(1, 0))
        other.religion = religion.name
        val pagan = game.addCiv()
        val near = cityWith(pagan, 3, 0, 1)
        val far = cityWith(pagan, -4, 4, 1)

        assertEquals(listOf(near, far), ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, emptyMap()))
        assertEquals(listOf(far), ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, mapOf(near to other)))
        assertEquals("its own target stays", listOf(near, far), ReligionAutomation.missionaryTargetsLikeVp(civ, missionary, mapOf(near to missionary)))

        val cache = AutomationCache()
        val view = GameView(game.gameInfo, civ)
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(missionary), cache)
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(other), cache)
        assertEquals("the second missionary takes the next city", mapOf(near to missionary, far to other), cache.missionaryTargets)
    }

    @Test
    fun `with Vox Populi heresy is removed from a city where another religion leads, or gains on ours without a majority`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)

        // 7 citizens, 700 pressure: 4 atheists, 2 of the strongest religion, 1 of the other; no majority
        val split = cityWith(civ, -3, 3, 7, religion.name to 200, hereticReligion.name to 100, atheism = 300)
        assertEquals(null, split.religion.getMajorityReligion())
        fun perTurn() = split.religion.getPressuresFromSurroundingCities()
        assertTrue("precondition: the heretics gain no more than we do", perTurn()[hereticReligion.name] <= perTurn()[religion.name])
        assertTrue(!ReligionAutomation.shouldRemoveHeresyLikeVp(split, religion.name))
        for (x in listOf(-4, -2, 2)) cityWith(heretic, x, -4, 1, hereticReligion.name to 4000)
        assertTrue("precondition: the heretics gain more", perTurn()[hereticReligion.name] > perTurn()[religion.name])
        assertTrue(ReligionAutomation.shouldRemoveHeresyLikeVp(split, religion.name))

        val hereticMajority = cityWith(civ, 3, 0, 1, hereticReligion.name to 4000)
        assertTrue(ReligionAutomation.shouldRemoveHeresyLikeVp(hereticMajority, religion.name))
        assertTrue("ours is the majority", !ReligionAutomation.shouldRemoveHeresyLikeVp(civ.cities.first(), religion.name))
        val hereticsLead = cityWith(civ, 0, 5, 7, hereticReligion.name to 200, religion.name to 100, atheism = 300)
        assertEquals(null, hereticsLead.religion.getMajorityReligion())
        assertTrue(ReligionAutomation.shouldRemoveHeresyLikeVp(hereticsLead, religion.name))
    }

    @Test
    fun `with Vox Populi an inquisitor goes against more than 6 heretics, doubled under a heretic majority, farthest first`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        val inquisitorTile = tile(0, 1)
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, inquisitorTile)
        inquisitor.religion = religion.name
        fun score(city: City, unit: MapUnit? = inquisitor) = ReligionAutomation.scoreCityForInquisitorOffensiveLikeVp(civ, city, unit, religion)

        // 6 citizens: 3 heretics (the majority), 2 ours, 1 atheist: 3 × 2 = 6 is not enough
        val three = cityWith(civ, 3, 0, 6, hereticReligion.name to 3000, religion.name to 2000, atheism = 900)
        assertEquals(3, three.religion.getFollowersOf(hereticReligion.name))
        assertEquals(0, score(three))
        // 8 citizens: 4 heretics, the majority: 8 scores the distance, or the count without a unit
        val four = cityWith(civ, -3, 3, 8, hereticReligion.name to 4000, religion.name to 3000, atheism = 900)
        assertEquals(4, four.religion.getFollowersOf(hereticReligion.name))
        assertEquals(inquisitorTile.aerialDistanceTo(four.getCenterTile()), score(four))
        assertEquals(8, score(four, null))
        inquisitor.putInTile(four.getCenterTile())
        assertEquals("a unit in the city scores its distance, 0", 0, score(four))
        val foreign = cityWith(heretic, 0, -5, 8, hereticReligion.name to 4000, religion.name to 3000, atheism = 900)
        assertEquals(0, score(foreign))
    }

    @Test
    fun `with Vox Populi an inquisitor guards the holy city or a border city a missionary could sway`() {
        val (civ, religion) = foundedCiv()
        val inquisitorTile = tile(0, 2)
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, inquisitorTile)
        inquisitor.religion = religion.name
        fun score(city: City, unfriendly: List<Civilization> = emptyList(), unit: MapUnit? = inquisitor) =
            ReligionAutomation.scoreCityForInquisitorDefensiveLikeVp(civ, city, unit, religion, unfriendly)
        val capital = civ.cities.first()

        // 2000 of 5000: impact 40 + 7 for the holy city
        assertEquals(2, score(capital))
        assertEquals(47, score(capital, unit = null))
        capital.religion.addPressure(religion.name, 5000)
        assertEquals("impact 20 + 7 for the holy city", 27, score(capital, unit = null))

        val twenty = cityWith(civ, 3, -3, 1, religion.name to 10_000)
        assertEquals("impact 20 is not enough", 0, score(twenty, unit = null))
        val twentyTwo = cityWith(civ, 0, -4, 1, religion.name to 9000)
        assertEquals("impact 22 is not enough either", 0, score(twentyTwo, unit = null))
        val twentyFive = cityWith(civ, -3, 0, 1, religion.name to 8000)
        assertEquals(25, score(twentyFive, unit = null))

        val rival = game.addCiv()
        cityWith(rival, 5, -3, 1)
        assertEquals("impact 20 + 11 on the border", 31, score(twenty, listOf(rival), unit = null))
    }

    @Test
    fun `with Vox Populi an inquisitor tries heretic cities first and skips a city another of ours already guards`() {
        val (civ, religion) = foundedCiv()
        val heretic = game.addCiv()
        val hereticReligion = foundReligion(heretic)
        // next to the capital: it does not count as a guard against itself
        val inquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, tile(0, 1))
        inquisitor.religion = religion.name
        val capital = civ.cities.first()
        val heretics = cityWith(civ, -3, 3, 8, hereticReligion.name to 4000, religion.name to 3000, atheism = 900)

        val targets = ReligionAutomation.inquisitorTargetsLikeVp(civ, inquisitor, emptyMap())
        assertEquals(listOf(heretics), targets.offensive)
        assertEquals(listOf(capital), targets.defensive)

        val guard = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, tile(1, 0))
        guard.religion = religion.name
        assertEquals(emptyList<City>(), ReligionAutomation.inquisitorTargetsLikeVp(civ, inquisitor, emptyMap()).defensive)
        assertEquals(emptyList<City>(), ReligionAutomation.inquisitorTargetsLikeVp(civ, inquisitor, mapOf(heretics to guard)).offensive)
    }

    @Test
    fun `with Vox Populi an inquisitor of another religion works for it, a missionary of another religion without a target is disbanded`() {
        val (civ, religion) = foundedCiv()
        game.ruleset.modOptions.constants.religionCultureAiLikeVp = true
        val hereticReligion = foundReligion(game.addCiv())
        val ours = cityWith(civ, -3, 3, 8, religion.name to 50_000)
        val foreignInquisitor = game.addUnit(ReligionAutomation.inquisitorUnit(civ)!!.name, civ, tile(0, 1))
        foreignInquisitor.religion = hereticReligion.name
        assertEquals("our 8 followers are heretics to it", listOf(ours),
            ReligionAutomation.inquisitorTargetsLikeVp(civ, foreignInquisitor, emptyMap()).offensive)

        // no other city to convert: our missionary waits, the other one goes
        val missionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(1, 0))
        missionary.religion = religion.name
        val foreignMissionary = game.addUnit(ReligionAutomation.missionaryUnit(civ)!!.name, civ, tile(1, -1))
        foreignMissionary.religion = hereticReligion.name
        val view = GameView(game.gameInfo, civ)
        val cache = AutomationCache()
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(missionary), cache)
        ReligiousUnitAutomation.automateMissionary(view.getMapUnitView(foreignMissionary), cache)
        ReligiousUnitAutomation.automateInquisitor(view.getMapUnitView(foreignInquisitor), cache)
        val units = civ.units.getCivUnits().toList()
        assertTrue(missionary in units)
        assertTrue(foreignMissionary !in units)
        assertTrue(foreignInquisitor in units)
        assertEquals(foreignInquisitor, cache.inquisitorTargets[ours])
    }
    //endregion
}
