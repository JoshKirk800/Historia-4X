package com.unciv.logic.civilization.managers

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.max

/**
 *  The resource monopolies of a civilization (slice T2 of `phaseE/vp-trade-plan.md`): CvPlayer::CheckForMonopoly and what reads the result.
 *  Only resources that are [TileResource.isMonopoly] take part, so a ruleset that has none (Brave New World's) has no monopolies and this class does nothing.
 *
 *  A civilization owns a copy of a resource when it comes from its tiles, buildings or policies, copies it sold in a deal included (V:CvPlayer.cpp:39128, 39342:
 *  `getNumResourceTotal(res, false)` is the supply less the export, the export is added back): what the supply list holds that is not trade, not the units' upkeep,
 *  and the allied City-States' copies only under `CityStateResourcesCountForMonopolies`. It has a **global monopoly** of a luxury (not banned by the World Congress)
 *  or strategic resource when it owns more of the copies on the map than the greatest share of any civilization and than [com.unciv.models.ModConstants.MonopolyConstants.globalThreshold]
 *  percent (the greatest holder needs at least the greatest share and still more than the threshold, V:CvPlayer.cpp:39139-39152, 39195-39232) and a **strategic
 *  monopoly** of a strategic resource when it owns more than the strategic threshold percent (39173-39188).
 *
 *  The uniques of a held monopoly are in [uniqueMap], which the civilization reads like its policies' (so the combat, healing and experience rows use the existing
 *  unique types, the plan's section 4.4); the percentages, flat amounts, tile yields, happiness, and golden age length that the plan lists are read by their own
 *  functions below, because they add the civilization's `MonopolyFlatBonus` and `MonopolyPercentBonus` (V:CvPlayer.cpp:31444-31476).
 *
 *  The notifications of a monopoly gained or lost are told by [MonopolyNotices]. Not ported: the great person rates of a monopoly (no data in the original), the trait that counts imports
 *  (no such trait), and the siphoned copies of a Feitoria, which count with the City-States' copies here.
 */
class MonopolyManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var civInfo: Civilization

    /** The resources of which the civilization has a global monopoly, saved */
    var hasGlobalMonopoly = HashSet<String>()
    /** The resources of which it has a strategic monopoly, saved */
    var hasStrategicMonopoly = HashSet<String>()

    /** The uniques of the monopolies held: the global list of every global monopoly and the strategic list of every strategic one */
    @Transient
    var uniqueMap = UniqueMap()
        private set
    @Transient
    private var activeUniques = ArrayList<Unique>()

    fun clone(): MonopolyManager {
        val toReturn = MonopolyManager()
        toReturn.hasGlobalMonopoly.addAll(hasGlobalMonopoly)
        toReturn.hasStrategicMonopoly.addAll(hasStrategicMonopoly)
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        civInfo = civ
        rebuildUniqueMap()
    }

    private fun rebuildUniqueMap() {
        val resources = civInfo.gameInfo.ruleset.tileResources
        val newMap = UniqueMap()
        val newActive = ArrayList<Unique>()
        fun add(resource: TileResource?, strategic: Boolean) {
            val holder = resource?.monopolyUniqueHolders?.get(if (strategic) 1 else 0) ?: return
            for (unique in holder.uniqueObjects) {
                newMap.addUnique(unique)
                newActive.add(unique)
            }
        }
        for (name in hasGlobalMonopoly) add(resources[name], false)
        for (name in hasStrategicMonopoly) add(resources[name], true)
        uniqueMap = newMap
        activeUniques = newActive
    }

    @Readonly fun hasGlobalMonopoly(resourceName: String) = resourceName in hasGlobalMonopoly
    @Readonly fun hasStrategicMonopoly(resourceName: String) = resourceName in hasStrategicMonopoly
    /** CvPlayer::GetNumGlobalMonopolies */
    @Readonly fun globalMonopolyCount() = hasGlobalMonopoly.size
    /** Whether [unique] is one of those the monopolies held give, as opposed to the same text from another source */
    @Readonly fun isMonopolyUnique(unique: Unique) = activeUniques.any { it === unique }

    //region Owned copies and shares

    /** The copies of [resource] the civilization owns for the purpose of monopolies, V:CvPlayer.cpp:39128-39132 */
    @Readonly
    fun ownedCopies(resource: TileResource): Int {
        val countCityStates = civInfo.hasUnique(UniqueType.CityStateResourcesCountForMonopolies)
        var owned = 0
        for (supply in civInfo.detailedCivResources) {
            if (supply.resource != resource || supply.amount <= 0) continue
            if (supply.origin == "Trade" || supply.origin == "Trade request") continue
            if (supply.origin == Constants.cityStates && !countCityStates) continue
            owned += supply.amount
        }
        return owned
    }

    /** CvPlayer::GetMonopolyPercent: the share of the copies on the map the civilization owns, 100 when it owns a resource that is not on the map */
    @Readonly
    fun percent(resource: TileResource): Int {
        val owned = ownedCopies(resource)
        val total = civInfo.gameInfo.resourceCopiesOnMap(resource.name)
        if (total <= 0) return if (owned > 0) 100 else 0
        return owned * 100 / total
    }

    /** CvPlayer::WouldGainMonopoly: whether [extraCopies] more copies would make a monopoly of the kind the resource has (a luxury not banned: global, a strategic one: strategic) */
    @Readonly
    fun wouldGain(resource: TileResource, extraCopies: Int): Boolean {
        if (extraCopies <= 0) return false
        val constants = civInfo.gameInfo.ruleset.modOptions.constants.monopoly
        val share = percent(resource) + extraCopies * 100 / max(1, civInfo.gameInfo.resourceCopiesOnMap(resource.name))
        return when {
            resource.resourceType == ResourceType.Luxury && !isBanned(resource.name) -> share > constants.globalThreshold
            resource.resourceType == ResourceType.Strategic -> share > constants.strategicThreshold
            else -> false
        }
    }

    @Readonly
    private fun isBanned(resourceName: String) = civInfo.gameInfo.league?.isLuxuryBanned(resourceName) == true

    //endregion
    //region CheckForMonopoly

    /**
     *  CvPlayer::CheckForMonopoly for every monopoly resource (CvGame::doTurn runs it for every living major civilization and resource, V:CvGame.cpp:8470-8488;
     *  the resource and league changes run it for the resource they changed). [refreshGreatest] first finds the greatest holder again as that loop does.
     *  When a monopoly is gained or lost the cities and the civilization's stats are updated, as the original's `updateYield`.
     *  @return whether any monopoly changed
     */
    fun checkAll(refreshGreatest: Boolean = true): Boolean {
        if (!civInfo.isMajorCiv || !civInfo.isAlive()) return false
        val game = civInfo.gameInfo
        var changed = false
        for (resource in game.ruleset.tileResources.values) {
            if (!resource.isMonopoly) continue
            if (refreshGreatest) game.updateGreatestMonopolyPlayer(resource.name)
            if (check(resource)) changed = true
        }
        if (changed) {
            rebuildUniqueMap()
            for (city in civInfo.cities) city.cityStats.update(updateCivStats = false)
            civInfo.updateStatsForNextTurn()
        }
        return changed
    }

    private fun check(resource: TileResource): Boolean {
        if (!civInfo.tech.isRevealed(resource)) return false
        val game = civInfo.gameInfo
        val constants = game.ruleset.modOptions.constants.monopoly
        val name = resource.name
        val hadGlobal = hasGlobalMonopoly(name)
        val hadStrategic = hasStrategicMonopoly(name)
        val total = game.resourceCopiesOnMap(name)
        if (total <= 0) setGlobal(name, false)
        else {
            val owned = ownedCopies(resource)
            val banned = isBanned(name)
            if (resource.resourceType == ResourceType.Luxury && !banned)
                setGlobal(name, isGreatestEnough(resource, owned, total))
            else if (resource.resourceType == ResourceType.Strategic) {
                setStrategic(name, owned * 100 > total * constants.strategicThreshold)
                setGlobal(name, isGreatestEnough(resource, owned, total))
            }
            if (banned) setGlobal(name, false)
        }
        val changed = hadGlobal != hasGlobalMonopoly(name) || hadStrategic != hasStrategicMonopoly(name)
        if (changed) {
            game.updateGreatestMonopolyPlayer(name)
            MonopolyNotices.notify(civInfo, resource, hadGlobal, hadStrategic)
        }
        return changed
    }

    /** The global test, V:CvPlayer.cpp:39139-39152: the threshold is the greatest share (50 at least); the greatest holder may be at it, the others must be above it */
    @Readonly
    private fun isGreatestEnough(resource: TileResource, owned: Int, total: Int): Boolean {
        val game = civInfo.gameInfo
        val constants = game.ruleset.modOptions.constants.monopoly
        val greatestId = game.greatestMonopolyPlayer[resource.name]
        val greatest = greatestId?.let { id -> game.civilizations.firstOrNull { it.civID == id } }
        val greatestShare = greatest?.monopolies?.percent(resource) ?: 0
        val threshold = max(greatestShare, constants.globalThreshold)
        return if (greatestId == civInfo.civID)
            owned * 100 >= total * threshold && owned * 100 > total * constants.globalThreshold
        else owned * 100 > total * threshold
    }

    private fun setGlobal(resourceName: String, value: Boolean) {
        if (value) hasGlobalMonopoly.add(resourceName) else hasGlobalMonopoly.remove(resourceName)
    }

    private fun setStrategic(resourceName: String, value: Boolean) {
        if (value) hasStrategicMonopoly.add(resourceName) else hasStrategicMonopoly.remove(resourceName)
    }

    //endregion
    //region Modifiers and effects

    /** CvPlayer::GetMonopolyModFlat: the sum of `MonopolyFlatBonus` */
    @Readonly
    fun flatBonus(): Int {
        var sum = 0
        civInfo.forEachMatchingUnique(UniqueType.MonopolyFlatBonus) { sum += it.params[0].toInt() }
        return sum
    }

    /** CvPlayer::GetMonopolyModPercent: the sum of `MonopolyPercentBonus` */
    @Readonly
    fun percentBonus(): Int {
        var sum = 0
        civInfo.forEachMatchingUnique(UniqueType.MonopolyPercentBonus) { sum += it.params[0].toInt() }
        return sum
    }

    @Readonly
    private fun globalUniquesOf(name: String): List<Unique> =
        civInfo.gameInfo.ruleset.tileResources[name]?.monopolyUniqueHolders?.get(0)?.uniqueObjects ?: emptyList()

    /**
     *  `Resource_YieldChangeFromMonopoly` on a tile with [resource] (V:CvPlot.cpp:11135-11141): the stats of its `MonopolyTileYield` uniques, the flat bonus
     *  added to each that is positive; `null` when the civilization has no monopoly that gives one. A strategic monopoly's list counts too
     */
    @Readonly
    fun tileYield(resource: TileResource): Stats? {
        if (!resource.isMonopoly) return null
        val global = hasGlobalMonopoly(resource.name)
        val strategic = hasStrategicMonopoly(resource.name)
        if (!global && !strategic) return null
        val flat = flatBonus().toFloat()
        @LocalState val result = Stats()
        var any = false
        for ((index, holder) in resource.monopolyUniqueHolders.withIndex()) {
            if (if (index == 1) !strategic else !global) continue // the second holder is the strategic list
            for (unique in holder.uniqueObjects) {
                if (unique.type != UniqueType.MonopolyTileYield) continue
                @LocalState val stats = unique.stats.clone()
                for (stat in Stat.entries) if (stats[stat] > 0f) stats[stat] += flat
                result.add(stats)
                any = true
            }
        }
        return if (any) result else null
    }

    /**
     *  `getCityYieldModFromMonopoly`: what the civilization's `MonopolyPercentBonus` adds to the percentage of every [UniqueType.StatPercentBonus] and
     *  [UniqueType.StatPercentBonusCities] unique of a global monopoly for [city] (V:CvPlayer.cpp:38980-38985: `+10 + GetMonopolyModPercent()` for each yield
     *  the resource has a modifier of). The 10 itself comes through the unique like any percentage
     */
    @Readonly
    fun cityPercentBonuses(city: City): Stats? {
        if (hasGlobalMonopoly.isEmpty()) return null
        val extra = percentBonus()
        if (extra == 0) return null
        @LocalState val result = Stats()
        var any = false
        for (name in hasGlobalMonopoly) for (unique in globalUniquesOf(name)) {
            val matches = when (unique.type) {
                UniqueType.StatPercentBonus -> true
                UniqueType.StatPercentBonusCities -> city.matchesFilter(unique.params[2])
                else -> false
            }
            if (!matches || unique.params[0].toFloat() == 0f || !unique.conditionalsApply(city.state)) continue
            result.add(Stat.valueOf(unique.params[1]), extra.toFloat())
            any = true
        }
        return if (any) result else null
    }

    /**
     *  CvPlayer::GetHappinessFromResourceMonopolies as `GetBonusHappinessFromLuxuriesFlat` applies it (V:CvPlayer.cpp:22030-22039): for each luxury of [ownedLuxuries]
     *  (the ones that count for happiness: not banned, and not obsolete through [UniqueType.ObsoleteWith]) the civilization has the global monopoly of,
     *  the `MonopolyHappiness` of its uniques plus the flat bonus
     */
    @Readonly
    fun luxuryHappiness(ownedLuxuries: List<TileResource>): Float {
        if (hasGlobalMonopoly.isEmpty()) return 0f
        val flat = flatBonus()
        var total = 0
        for (resource in ownedLuxuries) {
            if (!resource.isMonopoly || !hasGlobalMonopoly(resource.name)) continue
            if (resource.getMatchingUniques(UniqueType.ObsoleteWith).any { civInfo.tech.isResearched(it.params[0]) }) continue
            for (unique in globalUniquesOf(resource.name)) {
                if (unique.type != UniqueType.MonopolyHappiness) continue
                val amount = unique.params[0].toInt()
                if (amount > 0) total += amount + flat
            }
        }
        return total.toFloat()
    }

    /**
     *  CvPlayer::getGoldenAgeModifier's monopoly part (V:CvPlayer.cpp:25270-25290): the percentages of the [UniqueType.GoldenAgeLength] uniques of the global
     *  monopolies, each with the `MonopolyPercentBonus`, and those of the strategic ones, added up
     */
    @Readonly
    fun goldenAgeLengthPercent(): Int {
        if (hasGlobalMonopoly.isEmpty() && hasStrategicMonopoly.isEmpty()) return 0
        val extra = percentBonus()
        var total = 0
        for (name in hasGlobalMonopoly) for (unique in globalUniquesOf(name)) {
            if (unique.type != UniqueType.GoldenAgeLength) continue
            val amount = unique.params[0].toInt()
            if (amount > 0) total += amount + extra
        }
        for (name in hasStrategicMonopoly) {
            val holder = civInfo.gameInfo.ruleset.tileResources[name]?.monopolyUniqueHolders?.get(1) ?: continue
            for (unique in holder.uniqueObjects)
                if (unique.type == UniqueType.GoldenAgeLength) total += unique.params[0].toInt()
        }
        return total
    }

    /**
     *  CvCity::IsBuildingResourceMonopolyValid without the corporation part (V:CvCity.cpp:9914-9986): every resource of [Building.requiredMonopolies] must be a
     *  global monopoly that is not banned, and one of [Building.requiredMonopolyAnyOf], skipping banned luxuries. As in the original, a list whose every resource
     *  is banned asks for nothing (the counter of the resources not held only counts those not banned)
     */
    @Readonly
    fun meetsRequirements(building: Building): Boolean {
        for (name in building.requiredMonopolies)
            if (!hasGlobalMonopoly(name) || isBanned(name)) return false
        var notHeld = 0
        for (name in building.requiredMonopolyAnyOf) {
            if (isBanned(name)) continue
            if (hasGlobalMonopoly(name)) return true
            notHeld++
        }
        return notHeld == 0
    }

    //endregion
}
