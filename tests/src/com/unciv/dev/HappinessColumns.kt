package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import java.util.Locale

/**
 * The happiness rows of [DecisionLog] (AI_HAPPY per living major civilization, AI_CITYHAPPY per city of one, every [EVERY_TURNS] turns) and
 * the registry of their columns. A column is a name and a function of the civilization or city; the header line and the row are both built from
 * [civColumns] and [cityColumns], so a later model (the per-city needs of Vox Populi) adds its columns (approval, thresholds, needs, delta) by
 * appending to these two lists, with no change to the format or to the readers, which take the columns by position and ignore extra ones.
 *
 * What the columns mean with the model as it is (BNW's global happiness) is in the KDoc of [DecisionLog].
 */
object HappinessColumns {
    const val EVERY_TURNS = 10

    class Column<T>(val name: String, val value: (T) -> String)

    /** Entries of [com.unciv.logic.city.CityStats.happinessList] that are the city's own unhappiness, all negative */
    private val unhappinessEntries = listOf("Cities", "Population", "Occupied City")

    private fun localUnhappiness(city: City): Float =
        -unhappinessEntries.sumOf { (city.cityStats.happinessList[it] ?: 0f).toDouble() }.toFloat()

    private fun localHappiness(city: City): Float = city.cityStats.localHappiness

    /** A whole number without decimals, else two (Locale-independent), so the files stay stable and short */
    private fun number(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(Locale.ROOT, "%.2f", value)

    /** Columns after `turn  name` of AI_HAPPY */
    val civColumns: MutableList<Column<Civilization>> = mutableListOf(
        Column("happiness") { it.getHappiness().toString() },
        Column("pool") { number(it.stats.getHappinessBreakdown().values.sum()) },
        Column("localHappinessSum") { civ -> number(civ.cities.sumOf { localHappiness(it).toDouble() }.toFloat()) },
        Column("localUnhappinessSum") { civ -> number(civ.cities.sumOf { localUnhappiness(it).toDouble() }.toFloat()) },
    )

    /** Columns after `turn  name` of AI_CITYHAPPY */
    val cityColumns: MutableList<Column<City>> = mutableListOf(
        Column("city") { it.name },
        Column("population") { it.population.population.toString() },
        Column("localHappiness") { number(localHappiness(it)) },
        Column("localUnhappiness") { number(localUnhappiness(it)) },
        Column("delta") { number(localHappiness(it) - localUnhappiness(it)) },
    )

    fun header(): String =
        "# AI_HAPPY\tturn\tname\t" + civColumns.joinToString("\t") { it.name } + "\n" +
            "# AI_CITYHAPPY\tturn\tname\t" + cityColumns.joinToString("\t") { it.name } + "\n"

    fun livingMajors(game: GameInfo): List<Civilization> =
        game.civilizations.filter { it.isMajorCiv && !it.isSpectator && !it.isBarbarian && !it.isDefeated() }

    /** The AI_HAPPY row of every living major civilization of [game] and the AI_CITYHAPPY rows of its cities, as [DecisionLog] writes them at the end of [turn] */
    fun rows(game: GameInfo, turn: Int): String {
        val text = StringBuilder()
        for (civ in livingMajors(game)) {
            text.append("AI_HAPPY\t$turn\t${civ.civName}\t").append(civColumns.joinToString("\t") { it.value(civ) }).append('\n')
            for (city in civ.cities)
                text.append("AI_CITYHAPPY\t$turn\t${civ.civName}\t").append(cityColumns.joinToString("\t") { it.value(city) }).append('\n')
        }
        return text.toString()
    }
}
