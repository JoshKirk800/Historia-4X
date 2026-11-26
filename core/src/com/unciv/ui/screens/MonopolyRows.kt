package com.unciv.ui.screens

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.Unique
import yairm210.purity.annotations.Readonly

/**
 *  What the resource screens tell about monopolies (slice T9 of `phaseE/vp-trade-plan.md`; the state is [com.unciv.logic.civilization.managers.MonopolyManager]'s,
 *  CvPlayer::CheckForMonopoly, V:CvPlayer.cpp:39114-39337): the share of the copies on the map a civilization owns, whether it holds the global or the strategic monopoly, who
 *  has the greatest share (CvGame::GetGreatestPlayerResourceMonopolyValue), and what the monopoly gives, read from the resource's own monopoly uniques. As plain data, so that no
 *  screen builds it itself and tests need no GL context. Everything is empty for a ruleset with no monopoly resource, so nothing is shown that was not shown before.
 *
 *  @property percent the share of the copies on the map the civilization owns (CvPlayer::GetMonopolyPercent)
 *  @property global whether the civilization holds the global monopoly, [strategic] the strategic one
 *  @property greatest the civilization with the greatest share if the viewer knows it (or is it), else null; [greatestPercent] its share
 */
data class MonopolyRow(
    val resource: String,
    val resourceType: ResourceType,
    val percent: Int,
    val global: Boolean,
    val strategic: Boolean,
    val greatest: String?,
    val greatestPercent: Int,
)

object MonopolyRows {
    private const val GLOBAL = "Global monopoly"
    private const val STRATEGIC = "Strategic monopoly"
    private const val NONE = "No monopoly"
    private const val SHARE = "[percent]% of the copies on the map"
    private const val GREATEST = "Greatest share: [civ] ([percent]%)"
    private const val GIVES_GLOBAL = "A global monopoly gives:"
    private const val GIVES_STRATEGIC = "A strategic monopoly gives:"

    /** The row of [resource] for [civ], null for a resource that is no monopoly resource or that [civ] cannot see (the original does not check what it has not revealed) */
    @Readonly
    fun row(civ: Civilization, resource: TileResource): MonopolyRow? {
        if (!resource.isMonopoly || resource.resourceType == ResourceType.Bonus || !civ.tech.isRevealed(resource)) return null
        val game = civ.gameInfo
        val greatest = game.greatestMonopolyPlayer[resource.name]?.let { id -> game.civilizations.firstOrNull { it.civID == id } }
            ?.takeIf { it == civ || civ.knows(it) }
        return MonopolyRow(
            resource.name, resource.resourceType, civ.monopolies.percent(resource),
            civ.monopolies.hasGlobalMonopoly(resource.name), civ.monopolies.hasStrategicMonopoly(resource.name),
            greatest?.civName, greatest?.monopolies?.percent(resource) ?: 0
        )
    }

    /** The rows of every monopoly resource [civ] can see, luxuries before strategic resources, by name */
    @Readonly
    fun rows(civ: Civilization): List<MonopolyRow> {
        val ruleset = civ.gameInfo.ruleset
        if (!ruleset.hasMonopolyResources) return emptyList()
        return ruleset.tileResources.values.mapNotNull { row(civ, it) }.sortedWith(compareBy({ it.resourceType }, { it.resource }))
    }

    /** What the row says in the resource overview: the state, then the share, then the greatest holder when it is somebody else */
    @Readonly
    fun state(row: MonopolyRow): String = when {
        row.global -> GLOBAL
        row.strategic -> STRATEGIC
        else -> NONE
    }

    /** The texts of the row, translatable sentences with `[bracketed]` numbers: the state, the share, and who has the greatest share */
    @Readonly
    fun lines(row: MonopolyRow): List<String> {
        val result = arrayListOf(state(row), SHARE.replace("[percent]", "[${row.percent}]"))
        if (row.greatest != null && row.greatestPercent > 0)
            result.add(GREATEST.replace("[civ]", "[${row.greatest}]").replace("[percent]", "[${row.greatestPercent}]"))
        return result
    }

    /**
     *  The lines of the monopoly in the tooltip and the description of [resource]: what a global monopoly gives (the display texts of its uniques), then what a strategic one
     *  gives. Empty for a resource that is not a monopoly resource.
     */
    @Readonly
    fun tooltipLines(resource: TileResource): List<String> {
        if (!resource.isMonopoly) return emptyList()
        val result = ArrayList<String>()
        fun add(heading: String, texts: List<String>) {
            if (texts.isEmpty()) return
            result.add(heading)
            for (text in texts) result.add("  $text")
        }
        add(GIVES_GLOBAL, resource.globalMonopolyUniques.map { Unique(it).getDisplayText() })
        add(GIVES_STRATEGIC, resource.strategicMonopolyUniques.map { Unique(it).getDisplayText() })
        return result
    }
}
