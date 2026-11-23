package com.unciv.logic.map.mapunit

import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Religion
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VP 5.4.8 CvUnit::healRate: the territory auras of nearby units come in friendly, neutral and enemy kinds (GetHealFriendlyTerritoryFromNearbyUnit,
 * GetHealNeutralTerritoryFromNearbyUnit, GetHealEnemyTerritoryFromNearbyUnit), a city in resistance does not add its own heal rate, and with MOD_BALANCE_VP
 * the beliefs that heal come from the state religion (CvPlayerReligions::UpdateStateReligion), the secondary pantheon belief of the capital
 * and the civilization's own pantheon (MOD_BALANCE_PERMANENT_PANTHEONS), checked with CvReligionBeliefs::IsBeliefValid.
 */
@RunWith(BaseTestRunner::class)
class VpHealingVariantsTests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private fun newGame(): TestGame {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        return game
    }

    private fun tile(game: TestGame, x: Int, y: Int): Tile = game.getTile(x, y)

    private fun heal(unit: MapUnit, tile: Tile) = unit.healingAmountForTile(tile, noTerrainDamage = true)

    //region Territory aura kinds

    private class AuraDeltas(val friendly: Int, val neutral: Int, val enemy: Int, val foreignCity: Int)

    /** What a medic with the [aura] unique adds to the healing of a unit on a friendly, a neutral, an enemy and a foreign city tile, each within 2 of the medic */
    private fun auraDeltas(aura: String): AuraDeltas {
        val game = newGame()
        val civ = game.addCiv(isPlayer = true)
        val enemy = game.addCiv(isPlayer = true)
        val other = game.addCiv(isPlayer = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        val ownCity = game.addCity(civ, tile(game, -5, 0))
        val enemyCity = game.addCity(enemy, tile(game, 5, 0))
        val foreignCity = game.addCity(other, tile(game, 0, 0))
        val friendlyTile = tile(game, 3, 0).also { ownCity.expansion.takeOwnership(it) }
        val enemyTile = tile(game, 4, 0).also { enemyCity.expansion.takeOwnership(it) }
        val neutralTile = tile(game, 1, 0)
        val patient = game.addUnit("Warrior", civ, tile(game, -3, 0))
        val medic = game.addUnit("Warrior", civ, tile(game, 2, 0))
        setFlag(game, "unitHealingLikeVp", true)

        val tiles = listOf(friendlyTile, neutralTile, enemyTile, foreignCity.getCenterTile())
        val before = tiles.map { heal(patient, it) }
        val promotion = Promotion().apply {
            name = "Test aura medic"
            unitTypes = listOf(medic.type.name)
            uniques = arrayListOf(aura)
        }
        game.ruleset.unitPromotions[promotion.name] = promotion
        medic.promotions.addPromotion(promotion.name, isFree = true)
        val after = tiles.map { heal(patient, it) }
        val deltas = after.indices.map { after[it] - before[it] }
        return AuraDeltas(deltas[0], deltas[1], deltas[2], deltas[3])
    }

    @Test
    fun `the friendly aura only heals where the friendly territory heal applies`() {
        val d = auraDeltas("[+7] HP healing for [Military] units on [All] tiles within [2] tiles")
        assertEquals(listOf(7, 0, 0, 0), listOf(d.friendly, d.neutral, d.enemy, d.foreignCity))
    }

    @Test
    fun `the neutral aura heals on unowned and foreign non-hostile tiles, also in a foreign city`() {
        val d = auraDeltas("[+7] HP healing in neutral territory for [Military] units on [All] tiles within [2] tiles")
        assertEquals(listOf(0, 7, 0, 7), listOf(d.friendly, d.neutral, d.enemy, d.foreignCity))
    }

    @Test
    fun `the enemy aura heals only in the territory of a civilization at war`() {
        val d = auraDeltas("[+7] HP healing in enemy territory for [Military] units on [All] tiles within [2] tiles")
        assertEquals(listOf(0, 0, 7, 0), listOf(d.friendly, d.neutral, d.enemy, d.foreignCity))
    }

    //endregion

    //region City occupation

    @Test
    fun `a city in resistance gives only the enemy base heal and no building heal`() {
        val game = newGame()
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, tile(game, 0, 0))
        city.cityConstructions.addBuilding(game.createBuilding("[Military] Units adjacent to this city heal [+10] HP per turn when healing"))
        val unit = game.addUnit("Warrior", civ, city.getCenterTile())
        setFlag(game, "unitHealingLikeVp", true)
        val constants = game.ruleset.modOptions.constants
        assertEquals(constants.cityUnitHeal + 10, heal(unit, city.getCenterTile()))

        city.setFlag(CityFlags.Resistance, 3)
        assertEquals(constants.enemyUnitHeal, heal(unit, city.getCenterTile()))

        city.isBeingRazed = true // a razed city is no longer occupied: its full rate applies
        assertEquals(constants.cityUnitHeal + 10, heal(unit, city.getCenterTile()))
    }

    //endregion

    //region State religion, secondary pantheon, permanent pantheon

    private fun belief(game: TestGame, name: String, type: BeliefType, vararg uniques: String): Belief {
        val belief = Belief().apply {
            this.name = name
            this.type = type
            this.uniques = arrayListOf(*uniques)
        }
        game.ruleset.beliefs[name] = belief
        return belief
    }

    private fun healBelief(game: TestGame, name: String, type: BeliefType, amount: Int) =
        belief(game, name, type, "[Military] Units adjacent to this city heal [+$amount] HP per turn when healing")

    private fun follow(city: City, religion: Religion, population: Int = 3) {
        city.population.setPopulation(population)
        city.religion.addPressure(religion.name, 5000)
    }

    /** A civilization with a capital at (0, 0) and a warrior on a tile of its own two tiles away, so that no city religion effect reaches it */
    private class Home(val civ: Civilization, val capital: City, val unit: MapUnit, val tile: Tile)

    private fun home(game: TestGame, vararg uniques: String): Home {
        val civ = game.addCiv(*uniques, isPlayer = true)
        val capital = game.addCity(civ, tile(game, 0, 0), replacePalace = true)
        val tile = tile(game, 2, 0)
        capital.expansion.takeOwnership(tile)
        return Home(civ, capital, game.addUnit("Warrior", civ, tile), tile)
    }

    private fun enableReligionHealing(game: TestGame) {
        setFlag(game, "unitHealingLikeVp", true)
        setFlag(game, "stateReligionHealingLikeVp", true)
    }

    @Test
    fun `a conquered holy city makes its religion the state religion even without a majority of cities`() {
        val game = newGame()
        val home = home(game)
        game.addCity(home.civ, tile(game, 0, 4)) // two cities: one following the religion is no majority
        val founder = game.addCiv()
        val religion = game.addReligion(founder)
        religion.addBelief(belief(game, "Founder of the test", BeliefType.Founder))
        religion.addBelief(healBelief(game, "Healing of the test", BeliefType.Follower, 9))
        home.capital.religion.religionThisIsTheHolyCityOf = religion.name
        follow(home.capital, religion)
        enableReligionHealing(game)
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal + 9, heal(home.unit, home.tile))
    }

    @Test
    fun `a founder who lost the holy city does not get the beliefs through its majority`() {
        val game = newGame()
        val home = home(game)
        val conqueror = game.addCiv()
        val holyCity = game.addCity(conqueror, tile(game, 0, 4))
        val religion = game.addReligion(home.civ)
        religion.addBelief(belief(game, "Founder of the test", BeliefType.Founder))
        religion.addBelief(healBelief(game, "Healing of the test", BeliefType.Follower, 9))
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        follow(holyCity, religion)
        follow(home.capital, religion)
        enableReligionHealing(game)
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal, heal(home.unit, home.tile))
    }

    @Test
    fun `the pantheon belief of the second religion of the capital heals with the secondary pantheon policy`() {
        val game = newGame()
        val home = home(game, "Cities with a majority religion also get the Pantheon belief bonus of the second most popular religion")
        val own = game.addReligion(home.civ)
        own.addBelief(belief(game, "Founder of the test", BeliefType.Founder))
        home.capital.religion.religionThisIsTheHolyCityOf = own.name
        val other = game.addReligion(game.addCiv())
        other.addBelief(belief(game, "Other founder of the test", BeliefType.Founder))
        other.addBelief(healBelief(game, "Pantheon healing of the test", BeliefType.Pantheon, 6))
        home.capital.population.setPopulation(6)
        home.capital.religion.addPressure(own.name, 2000)
        home.capital.religion.addPressure(other.name, 1000)
        enableReligionHealing(game)
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal + 6, heal(home.unit, home.tile))
    }

    @Test
    fun `the own pantheon keeps healing when the state religion is a conquered one`() {
        val game = newGame()
        val home = home(game)
        val pantheon = game.addReligion(home.civ)
        pantheon.addBelief(healBelief(game, "Pantheon healing of the test", BeliefType.Pantheon, 4))
        pantheon.foundingTurn = 0
        val conquered = game.addReligion(game.addCiv())
        conquered.foundingTurn = 5
        conquered.addBelief(belief(game, "Founder of the test", BeliefType.Founder))
        home.capital.religion.religionThisIsTheHolyCityOf = conquered.name
        home.civ.religionManager.religion = pantheon
        enableReligionHealing(game)
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal + 4, heal(home.unit, home.tile))
    }

    @Test
    fun `a pantheon belief that the own religion has too is not counted twice`() {
        val game = newGame()
        val home = home(game)
        val pantheonBelief = healBelief(game, "Pantheon healing of the test", BeliefType.Pantheon, 4)
        val pantheon = game.addReligion(home.civ)
        pantheon.addBelief(pantheonBelief)
        pantheon.foundingTurn = 0
        val religion = game.addReligion(home.civ)
        religion.foundingTurn = 5
        religion.addBelief(pantheonBelief)
        religion.addBelief(belief(game, "Founder of the test", BeliefType.Founder))
        home.capital.religion.religionThisIsTheHolyCityOf = religion.name
        enableReligionHealing(game)
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal + 4, heal(home.unit, home.tile))
    }

    @Test
    fun `the belief healing of a city that follows the religion is not added a second time next to the city`() {
        val game = newGame()
        val home = home(game)
        val religion = game.addReligion(home.civ)
        religion.addBelief(belief(game, "Founder of the test", BeliefType.Founder))
        religion.addBelief(healBelief(game, "Healing of the test", BeliefType.Follower, 9))
        home.capital.religion.religionThisIsTheHolyCityOf = religion.name
        follow(home.capital, religion)
        val adjacent = tile(game, 1, 0).also { home.capital.expansion.takeOwnership(it) }
        enableReligionHealing(game)
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal + 9, heal(home.unit, adjacent))
    }

    //endregion
}
