package com.unciv.models.ruleset.tile

import com.unciv.Constants
import com.unciv.logic.MultiFilter
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetStatsObject
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.GameResource
import com.unciv.models.stats.Stats
import com.unciv.ui.objectdescriptions.ResourceDescriptions
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.Readonly

class TileResource : RulesetStatsObject(), GameResource {

    var resourceType: ResourceType = ResourceType.Bonus
    var terrainsCanBeFoundOn: List<String> = listOf()

    /** Stats that this resource adds to a tile when a correct improvement has been constructed
     *  - The Stats component of this resource on the other hand is always in effect. */
    var improvementStats: Stats? = null

    /** Name of the Technology needed to see and use this resource
     *  - Note: Currently, Unciv cannot treat e.g. `UniqueType.OnlyAvailable` with tech-based
     *    Conditionals as equivalent for purposes such as description and map generation */
    var revealedBy: String? = null

    /** Legacy "which improvement will unlock this resource"
     *  @see improvedBy
     *  @see getImprovements
     */
    var improvement: String? = null
    /** Defines which improvement will "unlock" this resource
     *  @see improvement
     *  @see getImprovements
     */
    var improvedBy: List<String> = listOf()

    /** Name of the Technology (or era-bound policy) that makes the resource obsolete: a team that has it no longer sees the resource and cannot trade it
     *  (the original's `Resources.TechObsolete`: Horses with Rocketry, Paper with Agriculture). null: never. Not read yet: slice T1 of `phaseE/vp-trade-plan.md` */
    var obsoleteBy: String? = null

    /** Whether owning most of the copies on the map gives a monopoly bonus (the original's `Resources.IsMonopoly`, V:CvPlayer.cpp:38944-39266). The bonuses are the
     *  uniques of [globalMonopolyUniques] and [strategicMonopolyUniques]; a resource that [isMonopoly] without any of them gives nothing (the validator warns).
     *  Read by [com.unciv.logic.civilization.managers.MonopolyManager] */
    var isMonopoly = false
    /** Uniques that apply to the civilization while it has a global monopoly of this resource (more than half of the copies on the map for a luxury, V:CvPlayer.cpp:39139-39232) */
    var globalMonopolyUniques = ArrayList<String>()
    /** Uniques that apply while it has a strategic monopoly (more than a quarter of the copies, V:CvPlayer.cpp:39173-39188) */
    var strategicMonopolyUniques = ArrayList<String>()

    /** [globalMonopolyUniques] and [strategicMonopolyUniques] as unique holders, so that the validator checks them like any other unique list of the resource */
    @delegate:Transient
    val monopolyUniqueHolders: List<MonopolyUniques> by lazy {
        listOf(MonopolyUniques("$name (global monopoly)", globalMonopolyUniques), MonopolyUniques("$name (strategic monopoly)", strategicMonopolyUniques))
    }

    /** A list of monopoly uniques of one [TileResource], with the unique target of the resource */
    class MonopolyUniques(override var name: String, override var uniques: ArrayList<String>) : IHasUniques {
        @delegate:Transient
        override val uniqueObjects: List<Unique> by lazy(::uniqueObjectsProvider)
        @delegate:Transient
        override val uniqueMap: UniqueMap by lazy(::uniqueMapProvider)
        override fun getUniqueTarget() = UniqueTarget.Resource
    }

    var majorDepositAmount: DepositAmount = DepositAmount()
    var minorDepositAmount: DepositAmount = DepositAmount()

    val isCityWide by lazy { hasUnique(UniqueType.CityResource, GameContext.IgnoreConditionals) }

    val isStockpiled by lazy { hasUnique(UniqueType.Stockpiled, GameContext.IgnoreConditionals) }

    /** Cache collecting [improvement], [improvedBy] and [UniqueType.ImprovesResources] uniques on the improvements themselves. */
    @Cache private var allImprovements: Set<String>? = null
    private var ruleset: Ruleset? = null

    /** Collects which improvements "unlock" this resource, caches and returns the Set.
     *  - The cache is cleared after ruleset load and combine, via [setTransients].
     *  @see improvement
     *  @see improvedBy
     *  @see UniqueType.ImprovesResources
     */
    @Readonly
    fun getImprovements(): Set<String> {
        if (allImprovements != null) return allImprovements!!

        val ruleset = this.ruleset
            ?: throw IllegalStateException("No ruleset on TileResource when initializing improvements")

        val allImprovementsLocal = mutableSetOf<String>()

        if (improvement != null) allImprovementsLocal += improvement!!
        allImprovementsLocal.addAll(improvedBy)
        for (improvement in ruleset.tileImprovements.values) {
            // Explicitly stated by the improvement, or this is a replacement improvement
            if (improvement.getMatchingUniques(UniqueType.ImprovesResources).any { matchesFilter(it.params[0]) }
                || allImprovementsLocal.contains(improvement.replaces)) {
                allImprovementsLocal += improvement.name
            }
        }


        allImprovements = allImprovementsLocal
        return allImprovementsLocal
    }

    /** Clears the cache for [getImprovements] and saves the Ruleset the cache update will need.
     *  - Doesn't evaluate the cache immediately, that would break tests as it trips the uniqueObjects lazy and tests manipulate uniques after ruleset load.
     *  - called from [Ruleset.updateResourceTransients]
     */
    fun setTransients(ruleset: Ruleset) {
        allImprovements = null
        this.ruleset = ruleset
    }

    override fun getUniqueTarget() = UniqueTarget.Resource

    override fun makeLink() = "Resource/$name"

    override fun getCivilopediaTextLines(ruleset: Ruleset) =
        ResourceDescriptions.getCivilopediaTextLines(this, ruleset)

    override fun getSortGroup(ruleset: Ruleset) = resourceType.ordinal
    override fun getSubCategory(ruleset: Ruleset): String? = when (resourceType) {
        ResourceType.Luxury -> "Luxury resource"
        ResourceType.Strategic -> "Strategic resource"
        ResourceType.Bonus -> "Bonus resource"
    }

    @Readonly
    fun isImprovedBy(improvementName: String): Boolean {
        return getImprovements().contains(improvementName)
    }

    @Readonly
    fun isImprovedBy(improvement: TileImprovement): Boolean {
        return getImprovements().contains(improvement.name)
    }

    /** @return Of all the potential improvements in [getImprovements], the first this civ can actually build, if any. */
    @Readonly
    fun getImprovingImprovement(tile: Tile, gameContext: GameContext): TileImprovement? {
        if (gameContext.civInfo == null) return null
        val civ: Civilization = gameContext.civInfo
        return getImprovements().asSequence()
            .map { civ.gameInfo.ruleset.tileImprovements[it]!! }
            .firstOrNull { tile.improvementFunctions.canBuildImprovement(it, gameContext) }
    }

    @Readonly
    fun matchesFilter(filter: String, state: GameContext? = null): Boolean =
        MultiFilter.multiFilter(filter, {
            matchesSingleFilter(it) ||
                state != null && hasTagUnique(it, state) ||
                state == null && hasTagUnique(it)
        })

    @Readonly
    fun matchesSingleFilter(filter: String) = when (filter) {
        "any" -> true
        in Constants.all -> true
        else -> when (filter) {
            name -> true
            resourceType.name -> true
            else -> improvementStats?.any { filter == it.key.name } == true
        }
    }

    @Readonly
    fun generatesNaturallyOn(tile: Tile): Boolean {
        
        if (tile.lastTerrain.name !in terrainsCanBeFoundOn) return false
        val gameContext = GameContext(tile = tile)
        if (hasUnique(UniqueType.NoNaturalGeneration, gameContext)) return false
        if (tile.allTerrains.any { it.hasUnique(UniqueType.BlocksResources, gameContext) }) return false
        
        val smallerLandmassUniques = getMatchingUniques(UniqueType.NaturalWonderSmallerLandmass, gameContext).toList()
        val largerLandmassUniques = getMatchingUniques(UniqueType.NaturalWonderLargerLandmass, gameContext).toList()

        if (smallerLandmassUniques.any() || largerLandmassUniques.any()) {
            val sortedContinents = tile.tileMap.continentsSortedBySize

            for (unique in smallerLandmassUniques) {
                if (tile.getContinent() in sortedContinents.take(unique.params[0].toInt())) return false
            }
            for (unique in largerLandmassUniques) {
                if (tile.getContinent() !in sortedContinents.take(unique.params[0].toInt())) return false
            }
        }
        if (tile.temperature!=null && tile.humidity!=null) // Only works when in map generation
            for (unique in getMatchingUniques(UniqueType.TileGenerationConditions, gameContext)){
                if (tile.temperature!! !in unique.params[0].toDouble() .. unique.params[1].toDouble()) return false
                if (tile.humidity!! !in unique.params[2].toDouble() .. unique.params[3].toDouble()) return false
            }

        return true
    }

    class DepositAmount {
        var sparse: Int = 1
        var default: Int = 2
        var abundant: Int = 3
    }
}
