package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unit.BaseUnit
import java.util.WeakHashMap

/**
 *  What the unit sanity ([UnitProductionAi.checkUnitBuildSanity]) reads of Vox Populi's CvPlayer lists that the original updates once at the
 *  start of the player's turn, worked out when first asked in a turn: the best city for units of a unit combat class and of a domain
 *  ([bestCity], CvPlayer::GetBestMilitaryCity, rated by [MilitaryCities]) and the unit AI types we have too few of ([isUnderrepresented],
 *  CvPlayer::DoUnitDiversity / IsUnderrepresentedUnitType).
 */
object UnitSanityCache {
    private class Data(val turn: Int) {
        val bestByClass = HashMap<String, City?>()
        val bestByDomain = HashMap<MilitaryCities.UnitDomain, City?>()
        var needed: Set<UnitAiType>? = null
    }

    private val cache = WeakHashMap<Civilization, Data>()

    private fun data(civ: Civilization): Data = synchronized(cache) {
        val cached = cache[civ]
        if (cached != null && cached.turn == civ.gameInfo.turns) cached else Data(civ.gameInfo.turns).also { cache[civ] = it }
    }

    /** Units.CombatClass (UNITCOMBAT_*) of [unit] from the AI data, null if it has none */
    fun combatClassOf(civ: Civilization, unit: BaseUnit): String? = civ.gameInfo.ruleset.ai.unitAiTypesOf(unit.name)?.combatClass

    fun domainOf(unit: BaseUnit): MilitaryCities.UnitDomain = MilitaryCities.UnitDomain.entries.first { it.contains(unit) }

    /** CvPlayer::GetBestMilitaryCity(unit combat class) */
    fun bestCity(civ: Civilization, combatClass: String): City? = synchronized(cache) {
        val data = data(civ)
        if (combatClass in data.bestByClass) data.bestByClass[combatClass]
        else MilitaryCities.bestForCombatClass(civ, combatClass).also { data.bestByClass[combatClass] = it }
    }

    /** CvPlayer::GetBestMilitaryCity(NO_UNITCOMBAT, domain) */
    fun bestCity(civ: Civilization, domain: MilitaryCities.UnitDomain): City? = synchronized(cache) {
        val data = data(civ)
        if (domain in data.bestByDomain) data.bestByDomain[domain]
        else MilitaryCities.bestForDomain(civ, domain).also { data.bestByDomain[domain] = it }
    }

    /** CvPlayer::IsUnderrepresentedUnitType */
    fun isUnderrepresented(civ: Civilization, type: UnitAiType): Boolean = synchronized(cache) {
        val data = data(civ)
        val needed = data.needed ?: neededUnitAiTypes(civ).also { data.needed = it }
        type in needed
    }

    /**
     *  CvPlayer::DoUnitDiversity: of the combat units we can train (explorers, sea explorers and carriers aside) the number we have and are
     *  training of each unit type is summed per domain and default unit AI; in each domain the unit AI types with the fewest (not all tied)
     *  are the ones we need more of.
     */
    private fun neededUnitAiTypes(civ: Civilization): Set<UnitAiType> {
        val rules = civ.gameInfo.ruleset.ai
        val owned = HashMap<String, Int>()
        for (unit in civ.units.getCivUnits()) owned.merge(unit.name, 1, Int::plus)
        for (city in civ.cities) (city.cityConstructions.getCurrentConstruction() as? BaseUnit)?.let { owned.merge(it.name, 1, Int::plus) }
        val countByType = HashMap<MilitaryCities.UnitDomain, HashMap<UnitAiType, Int>>()
        for (unit in civ.gameInfo.ruleset.units.values) {
            if (!UnitProductionAi.isCombat(unit) || !unit.isBuildable(civ)) continue
            val unitAi = UnitAiTypes.defaultOf(unit, rules)
            if (unitAi == UnitAiType.EXPLORE || unitAi == UnitAiType.EXPLORE_SEA || unitAi == UnitAiType.CARRIER_SEA) continue
            countByType.getOrPut(domainOf(unit)) { HashMap() }.merge(unitAi, owned[unit.name] ?: 0, Int::plus)
        }
        val needed = HashSet<UnitAiType>()
        for (counts in countByType.values) {
            val min = counts.values.min()
            val max = counts.values.max()
            for ((type, count) in counts) if (count == min && count != max) needed.add(type)
        }
        return needed
    }
}
