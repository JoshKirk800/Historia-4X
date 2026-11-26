package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/**
 * The unit supply rows of [DecisionLog] (`phaseE/m6-supply-deep.md` section 5.2: what a study must record before the supply table is changed): AI_SUPPLY per living
 * major civilization every [EVERY_TURNS] turns. The columns are a registry like [HappinessColumns] (the header line and the rows are both built from [columns], readers
 * take the columns by position and ignore extra ones):
 *
 *     AI_SUPPLY  turn  name  supply  unitsToSupply  deficit  productionPenalty  growthPenalty  cities  puppets  <the breakdown>  <the supply buildings>
 *
 * supply is [com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.getUnitSupply] (what the game reads, the `unitSupply` column of AI_ECON), unitsToSupply the units
 * that use it, deficit the units out of supply (CvPlayer::GetNumUnitsOutOfSupply) and productionPenalty and growthPenalty what that costs the civilization's constructions
 * and cities, in percent (0 or negative, CvPlayer::calculateUnitProductionMaintenanceMod and calculateUnitGrowthMaintenanceMod). puppets are the cities that are puppets
 * (they do not count for the empire size divisor).
 *
 * The breakdown is [com.unciv.logic.civilization.transients.UnitSupplyBreakdown] as [com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn.getUnitSupplyBreakdown]
 * makes it, one column per field but the gross values (before the technology reductions): techProgress, the three net parts (difficulty, cities, population), subtotal,
 * empireDivisor (percent), afterEmpireSize, bonusPercent (the difficulty's bonus, 100 plus it is the multiplier) and total. With `unitSupplyLikeVp` total is the supply; the
 * column next to it makes a difference visible instead of assumed.
 *
 * The supply buildings are the number of each of [supplyBuildings] the civilization's cities have built (one column each, the name of the building without spaces),
 * so that what the buildings add to the supply of a game can be read from the rows. The supply of a building itself (Vox Populi's `CitySupplyFlat` and `CitySupplyModifier`)
 * is not in the engine, so no column says what the buildings give.
 *
 * Not in the rows: how many units were scrapped and how many were bought while the civilization was at its supply (both need a count in the game's code, not in a log).
 */
object SupplyColumns {
    const val EVERY_TURNS = 10

    /** The buildings of Vox Populi that carry supply (`phaseE/m6-supply-plan.md` section 3.2), by the name the ruleset has */
    val supplyBuildings = listOf(
        "Barracks", "Stable", "Harbor", "Armory", "Military Academy", "Walls", "Castle", "Arsenal", "Military Base", "Seaport"
    )

    private fun builtCount(civ: Civilization, building: String) =
        civ.cities.count { it.cityConstructions.isBuilt(building) }

    /** Columns after `turn  name` of AI_SUPPLY */
    val columns: MutableList<HappinessColumns.Column<Civilization>> = (
        listOf(
            HappinessColumns.Column<Civilization>("supply") { it.stats.getUnitSupply().toString() },
            HappinessColumns.Column("unitsToSupply") { it.units.getNumUnitsToSupply().toString() },
            HappinessColumns.Column("deficit") { it.stats.getUnitSupplyDeficit().toString() },
            HappinessColumns.Column("productionPenalty") { it.stats.getUnitSupplyProductionPenalty().toInt().toString() },
            HappinessColumns.Column("growthPenalty") { it.stats.getUnitSupplyGrowthPenalty().toString() },
            HappinessColumns.Column("cities") { it.cities.size.toString() },
            HappinessColumns.Column("puppets") { civ -> civ.cities.count { it.isPuppet }.toString() },
            HappinessColumns.Column("techProgress") { it.stats.getUnitSupplyBreakdown(includeGross = false).techProgressPercent.toString() },
            HappinessColumns.Column("difficulty") { it.stats.getUnitSupplyBreakdown(includeGross = false).difficultyNet.toString() },
            HappinessColumns.Column("citiesPart") { it.stats.getUnitSupplyBreakdown(includeGross = false).citiesNet.toString() },
            HappinessColumns.Column("populationPart") { it.stats.getUnitSupplyBreakdown(includeGross = false).populationNet.toString() },
            HappinessColumns.Column("subtotal") { it.stats.getUnitSupplyBreakdown(includeGross = false).subtotal.toString() },
            HappinessColumns.Column("empireDivisor") { it.stats.getUnitSupplyBreakdown(includeGross = false).empireSizeDivisorPercent.toString() },
            HappinessColumns.Column("afterEmpireSize") { it.stats.getUnitSupplyBreakdown(includeGross = false).afterEmpireSize.toString() },
            HappinessColumns.Column("bonusPercent") { it.stats.getUnitSupplyBreakdown(includeGross = false).difficultyModifierPercent.minus(100).toString() },
            HappinessColumns.Column("breakdownTotal") { it.stats.getUnitSupplyBreakdown(includeGross = false).total.toString() },
        ) + supplyBuildings.map { building ->
            HappinessColumns.Column(building.replace(" ", "")) { civ -> builtCount(civ, building).toString() }
        }
        ).toMutableList()

    fun header(): String = "# AI_SUPPLY\tturn\tname\t" + columns.joinToString("\t") { it.name } + "\n"

    /** The AI_SUPPLY row of every living major civilization of [game], as [DecisionLog] writes them at the end of [turn] */
    fun rows(game: GameInfo, turn: Int): String {
        val text = StringBuilder()
        for (civ in HappinessColumns.livingMajors(game))
            text.append("AI_SUPPLY\t$turn\t${civ.civName}\t").append(columns.joinToString("\t") { it.value(civ) }).append('\n')
        return text.toString()
    }
}
