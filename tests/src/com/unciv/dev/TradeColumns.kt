package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.models.TradeRoute
import com.unciv.models.ruleset.tile.ResourceType

/**
 * The trade rows of [DecisionLog] (phaseE/vp-trade-plan.md, slice T0: the before row of the trade measures): AI_TRADE and AI_MONOPOLY per living major
 * civilization every [EVERY_TURNS] turns. The columns are registries like [HappinessColumns] (the header line and the rows are both built from them, readers take
 * the columns by position and ignore extra ones):
 *
 *     AI_TRADE     turn  name  international  domestic  plundered  gold  science  culture  partners
 *     AI_MONOPOLY  turn  name  globalMonopolies  strategicMonopolies  resources
 *
 * AI_TRADE: international and domestic are the trade routes of the civilization that exist now (a domestic route is a food or production route to a city of its own),
 * plundered the routes of it that were plundered, as many as the connections [com.unciv.logic.civilization.managers.TradeRouteManager.plunderedConnections] remembers
 * (the same two cities count once), gold, science and culture what the routes make for the civilization's cities in a turn (the yields of its own routes for the city they
 * start in and the yields of the routes of others that end in a city of it, [TradeRoute.originYields] and [TradeRoute.destinationYields], rounded towards 0 as the
 * treasury rows are), partners the civilizations at the other end of those routes, the civilization itself and barbarians not counted.
 *
 * AI_MONOPOLY: a tally of the copies of every resource with `isMonopoly` on the map that the civilization owns, and not the monopolies of the engine (they come with
 * slice T2 of the plan, which replaces this tally by its own state). A copy is owned when it lies on a tile of the civilization that provides the resource (a strategic
 * resource counts its quantity, any other resource one per tile), `total` is every copy on the map. The civilization has a global monopoly when it owns more than the
 * [com.unciv.models.ModConstants.MonopolyConstants.globalThreshold] percent of the copies of a luxury or strategic resource and a strategic monopoly when it owns more
 * than the strategicThreshold percent of the copies of a strategic resource (a strategic resource it has the global monopoly of counts for both, as in
 * V:CvPlayer.cpp:39139-39232). resources are the names of the resources of the monopolies, comma-separated (`-` for none), a strategic monopoly that is no global monopoly
 * with the suffix `:strategic`. A ruleset with no resource that is `isMonopoly` has 0, 0 and `-` in every row.
 */
object TradeColumns {
    const val EVERY_TURNS = 10

    /** What the civilization's routes bring and the routes of others that end in its cities, as [TradeRoute.originYields] and [TradeRoute.destinationYields] */
    private class Flows(civ: Civilization) {
        val own: List<TradeRoute> = civ.tradeRoutes.routes
        val incoming: List<TradeRoute> = civ.gameInfo.civilizations.asSequence()
            .filter { it != civ }
            .flatMap { it.tradeRoutes.routes.asSequence() }
            .filter { it.getDestinationCiv() == civ }
            .toList()

        fun sum(stat: (com.unciv.models.stats.Stats) -> Float): Int =
            (own.sumOf { stat(it.originYields).toDouble() } + incoming.sumOf { stat(it.destinationYields).toDouble() }).toInt()

        val partners: Int get() = (own.mapNotNull { it.getDestinationCiv() } + incoming.map { it.civ })
            .filter { !it.isBarbarian }
            .toSet().size
    }

    private fun flows(civ: Civilization) = Flows(civ)

    /** Columns after `turn  name` of AI_TRADE */
    val tradeColumns: MutableList<HappinessColumns.Column<Civilization>> = mutableListOf(
        HappinessColumns.Column("international") { civ -> civ.tradeRoutes.routes.count { it.isInternational() }.toString() },
        HappinessColumns.Column("domestic") { civ -> civ.tradeRoutes.routes.count { !it.isInternational() }.toString() },
        HappinessColumns.Column("plundered") { it.tradeRoutes.plunderedConnections.size.toString() },
        HappinessColumns.Column("gold") { flows(it).sum { stats -> stats.gold }.toString() },
        HappinessColumns.Column("science") { flows(it).sum { stats -> stats.science }.toString() },
        HappinessColumns.Column("culture") { flows(it).sum { stats -> stats.culture }.toString() },
        HappinessColumns.Column("partners") { flows(it).partners.toString() },
    )

    /** The monopolies of [civ] by the tally of the class comment: (resource name, global, strategic), in the order of the ruleset's resources */
    fun monopolies(civ: Civilization): List<Triple<String, Boolean, Boolean>> {
        val ruleset = civ.gameInfo.ruleset
        val constants = ruleset.modOptions.constants.monopoly
        val monopolyResources = ruleset.tileResources.values.filter { it.isMonopoly }
        if (monopolyResources.isEmpty()) return emptyList()
        val total = HashMap<String, Int>()
        val owned = HashMap<String, Int>()
        for (tile in civ.gameInfo.tileMap.values) {
            val resource = tile.tileResource ?: continue
            if (!resource.isMonopoly) continue
            val copies = if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount else 1
            total.merge(resource.name, copies, Int::plus)
            if (tile.getOwner() == civ && tile.providesResources(civ)) owned.merge(resource.name, copies, Int::plus)
        }
        return monopolyResources.mapNotNull { resource ->
            val all = total[resource.name] ?: return@mapNotNull null
            val mine = owned[resource.name] ?: 0
            val global = resource.resourceType != ResourceType.Bonus && mine * 100 > all * constants.globalThreshold
            val strategic = resource.resourceType == ResourceType.Strategic && mine * 100 > all * constants.strategicThreshold
            if (global || strategic) Triple(resource.name, global, strategic) else null
        }
    }

    /** Columns after `turn  name` of AI_MONOPOLY */
    val monopolyColumns: MutableList<HappinessColumns.Column<Civilization>> = mutableListOf(
        HappinessColumns.Column("globalMonopolies") { civ -> monopolies(civ).count { it.second }.toString() },
        HappinessColumns.Column("strategicMonopolies") { civ -> monopolies(civ).count { it.third }.toString() },
        HappinessColumns.Column("resources") { civ ->
            monopolies(civ).joinToString(",") { (name, global, _) -> if (global) name else "$name:strategic" }.ifEmpty { "-" }
        },
    )

    fun header(): String =
        "# AI_TRADE\tturn\tname\t" + tradeColumns.joinToString("\t") { it.name } + "\n" +
            "# AI_MONOPOLY\tturn\tname\t" + monopolyColumns.joinToString("\t") { it.name } + "\n"

    /** The AI_TRADE and AI_MONOPOLY rows of every living major civilization of [game], as [DecisionLog] writes them at the end of [turn] */
    fun rows(game: GameInfo, turn: Int): String {
        val text = StringBuilder()
        for (civ in HappinessColumns.livingMajors(game)) {
            text.append("AI_TRADE\t$turn\t${civ.civName}\t").append(tradeColumns.joinToString("\t") { it.value(civ) }).append('\n')
            text.append("AI_MONOPOLY\t$turn\t${civ.civName}\t").append(monopolyColumns.joinToString("\t") { it.value(civ) }).append('\n')
        }
        return text.toString()
    }
}
