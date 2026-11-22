package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/**
 * The economy rows of [DecisionLog]: AI_YIELD per living major civilization every [EVERY_TURNS] turns and, for the instant yields it has gained since
 * the game began, AI_INSTANT per event and stat. The columns of AI_YIELD are a registry like [HappinessColumns] (the header line and the rows are both built
 * from [civColumns], readers take the columns by position and ignore extra ones):
 *
 *     AI_YIELD    turn  name  gold  goldPerTurn  sciencePerTurn  culturePerTurn  faith  production  food  techs  era  policies  wonders  buildings
 *     AI_INSTANT  turn  name  event  stat  amount
 *
 * gold and faith are the treasuries, the three per-turn columns are what [com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.statsForNextTurn]
 * promises for the next turn (the original observer's `CalculateGoldRate`, `GetScience`, `GetTotalJONSCulturePerTurn`, see `tools/bnw_cap/snap.lua`
 * civRow), production and food the sum of the cities' production and food surplus, techs the researched ones, era the era's number
 * (zero-based), policies the adopted ones, wonders the world and national wonders in the civilization's cities and buildings every other building there.
 *
 * AI_INSTANT is one row for every event and stat with a total other than 0: the total, from the start of the game (or of the load), of what
 * [com.unciv.logic.civilization.InstantYields.gain] gave the civilization because of that event (the name of the trigger condition's unique type, or
 * `Direct` for a gain with no trigger condition, see [com.unciv.logic.civilization.InstantYieldTotals]) in that stat. The rows are cumulative, the last row of a
 * game is the whole game. A game in which nothing instant is gained has no AI_INSTANT row.
 */
object EconomyColumns {
    const val EVERY_TURNS = 10

    /** The surplus of one of the cities' stats, summed over the cities (what the civilization makes in a turn), rounded towards 0 as the treasury rows are */
    private fun citySum(civ: Civilization, stat: (com.unciv.models.stats.Stats) -> Float): Int =
        civ.cities.sumOf { stat(it.cityStats.currentCityStats).toDouble() }.toInt()

    /** Columns after `turn  name` of AI_YIELD */
    val civColumns: MutableList<HappinessColumns.Column<Civilization>> = mutableListOf(
        HappinessColumns.Column("gold") { it.gold.toString() },
        HappinessColumns.Column("goldPerTurn") { it.stats.statsForNextTurn.gold.toInt().toString() },
        HappinessColumns.Column("sciencePerTurn") { it.stats.statsForNextTurn.science.toInt().toString() },
        HappinessColumns.Column("culturePerTurn") { it.stats.statsForNextTurn.culture.toInt().toString() },
        HappinessColumns.Column("faith") { it.religionManager.storedFaith.toString() },
        HappinessColumns.Column("production") { citySum(it) { stats -> stats.production }.toString() },
        HappinessColumns.Column("food") { citySum(it) { stats -> stats.food }.toString() },
        HappinessColumns.Column("techs") { it.tech.researchedTechnologies.size.toString() },
        HappinessColumns.Column("era") { it.getEraNumber().toString() },
        HappinessColumns.Column("policies") { it.policies.getAdoptedPolicies().size.toString() },
        HappinessColumns.Column("wonders") { civ -> civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().count { it.isAnyWonder() } }.toString() },
        HappinessColumns.Column("buildings") { civ -> civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().count { !it.isAnyWonder() } }.toString() },
    )

    fun header(): String =
        "# AI_YIELD\tturn\tname\t" + civColumns.joinToString("\t") { it.name } + "\n" +
            "# AI_INSTANT\tturn\tname\tevent\tstat\tamount\n"

    /** The AI_YIELD row of every living major civilization of [game] and its AI_INSTANT rows, as [DecisionLog] writes them at the end of [turn] */
    fun rows(game: GameInfo, turn: Int): String {
        val text = StringBuilder()
        for (civ in HappinessColumns.livingMajors(game)) {
            text.append("AI_YIELD\t$turn\t${civ.civName}\t").append(civColumns.joinToString("\t") { it.value(civ) }).append('\n')
            for ((event, stat, amount) in civ.instantYieldTotals.entries())
                text.append("AI_INSTANT\t$turn\t${civ.civName}\t$event\t${stat.name}\t$amount\n")
        }
        return text.toString()
    }
}
