package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.nation.Difficulty
import com.unciv.testing.TestGame

/**
 *  A Brave New World game that plays Vox Populi's needs (`cityNeedsLikeVp`) for the needs tests: one human civilization with no happiness of its own
 *  (the game's difficulty gives no base happiness, so the pool is whatever a test puts in), the medians of the game at 0 (so no city has a yield need until
 *  a test sets a median), and the tech median of the game at what the civilization knows. The ruleset still holds the original model's unhappiness
 *  effects (GlobalUniques.json), so tests read the entries the needs model adds ("Happiness", "Unhappiness", "Unhappiness" in combat) and not the totals.
 *
 *  The difficulties are new objects, because the ruleset's own are shared with the other tests: [difficulty] is the chosen one, [aiLevel] the one of the AI civilizations.
 *
 *  @param civUniques uniques of the civilization's nation
 *  @param globalUniques uniques of the ruleset, for the whole game
 */
class NeedsTestWorld(vararg civUniques: String, globalUniques: List<String> = emptyList()) {
    val game = TestGame(*globalUniques.toTypedArray(), baseRuleset = BaseRuleset.Civ_V_BNW)
    val difficulty = Difficulty().apply { name = "Needs"; aiDifficultyLevel = "Needs AI" }
    val aiLevel = Difficulty().apply { name = "Needs AI"; isSelectable = false }
    val civ: Civilization
    private var citiesFounded = 0
    private val spots = listOf(HexCoord(0, 0), HexCoord(3, 0), HexCoord(-3, 0), HexCoord(0, 3), HexCoord(0, -3), HexCoord(3, -3), HexCoord(-3, 3))

    init {
        game.ruleset.modOptions.constants.cityNeedsLikeVp = true
        game.ruleset.difficulties[difficulty.name] = difficulty
        game.ruleset.difficulties[aiLevel.name] = aiLevel
        game.setDifficulty(difficulty.name)
        game.makeHexagonalMap(6)
        civ = game.addCiv(*civUniques, isPlayer = true)
        game.gameInfo.needsMedians.techs = civ.tech.researchedTechnologies.size
    }

    val medians get() = game.gameInfo.needsMedians
    val constants get() = game.ruleset.modOptions.constants

    /** The game speed gives [startingHappiness] to every civilization (a new object, the ruleset's speeds are shared with the other tests) */
    fun setStartingHappiness(startingHappiness: Int) {
        val standard = game.gameInfo.speed
        val speed = Speed().apply { name = "Needs"; turns = standard.turns; this.startingHappiness = startingHappiness }
        game.ruleset.speeds[speed.name] = speed
        game.setSpeed(speed.name)
    }

    /** The next city of [of] (the civilization), on one of the spots of the map; the first one is the capital, with a Palace that has no stats */
    fun addCity(population: Int = 3, of: Civilization = civ): City =
        game.addCity(of, game.getTile(spots[citiesFounded++]), replacePalace = true, initialPopulation = population)

    /** A building with these uniques, built in [city] */
    fun build(city: City, vararg uniques: String) {
        city.cityConstructions.addBuilding(game.createBuilding(*uniques))
    }

    /** Local happiness of [amount] in [city] that nothing limits */
    fun giveHappiness(city: City, amount: Int) = build(city, "[+$amount Happiness]")

    fun addSpecialists(city: City, count: Int) = city.population.specialistAllocations.add("Scientist", count)

    /** Every city adds up its own stats, then the civilization hands out its pool and unhappiness */
    fun refresh() {
        for (city in civ.cities) city.cityStats.update(updateCivStats = false)
        civ.updateStatsForNextTurn()
    }

    fun needsOf(city: City) = city.cityStats.needs
}
