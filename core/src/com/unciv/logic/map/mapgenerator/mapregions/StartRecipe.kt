package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.logic.map.MapType
import yairm210.purity.annotations.Readonly

/** `GenerateRegions{method = ...}` of the scripts: how the civs are dealt to the land */
enum class RegionMethod {
    /** Method 2: civs are dealt to the landmasses by fertility ([VoxPopuliContinentAssignment]), each landmass is cut into its civs' regions */
    Continental,
    /** Method 3: the whole map is cut into rectangles, one per civ, and a region's start is the best site of its most fertile landmass
     *  that has an eligible one (`FindStartWithoutRegardToAreaID`) */
    Rectangular
}

/** The minimum food and production tiles in the first, second and third ring around a start (running totals; `minFoodInner` ... `minProdOuter`) */
class StartMinimums(val food: List<Int>, val prod: List<Int>) {
    companion object {
        /** `ASP:__Init` of Vox Populi: food 1, 4, 4; production 1, 1, 3 */
        val voxPopuli = StartMinimums(listOf(1, 4, 4), listOf(1, 1, 3))
    }
}

/**
 * What the start code of a Civ V script (`StartPlotSystem`) sets that the generic start code does not: the region method, `ChooseLocations`'s
 * `mustBeCoast` and lowered minimums, and the AI map hint. Data of the map type; [MapRegions] and [VoxPopuliStartFinder] read it for these types only.
 * The region method applies to every ruleset; `mustBeCoast` and the minimums only where the ruleset has `startFinderLikeVoxPopuli`
 * ([VoxPopuliStartFinder]): the other finder keeps the generic start.
 *
 * `null` minimums are the engine's defaults ([StartMinimums.voxPopuli]), the ones `ASP:ChooseLocations` keeps for an argument a script does not pass.
 */
data class StartRecipe(
    /** The Lua file the settings are from, and the [MapType] that runs it */
    val script: String,
    val mapType: String,
    val regionMethod: RegionMethod,
    /** `mustBeCoast`: only start sites next to the sea are candidates, unless the region has fewer than 3 coastal land tiles or no coastal candidate */
    val mustBeCoast: Boolean,
    val minFoodMiddle: Int? = null,
    val minProdMiddle: Int? = null,
    val minFoodOuter: Int? = null,
    val minProdOuter: Int? = null,
    /** `Map.ChangeAIMapHint`: 1 = naval map, 4 = offshore expansion map, 0 = never set */
    val aiMapHint: Int = 0
) {
    @Readonly
    fun minimums(): StartMinimums {
        val default = StartMinimums.voxPopuli
        return StartMinimums(
            listOf(default.food[0], minFoodMiddle ?: default.food[1], minFoodOuter ?: default.food[2]),
            listOf(default.prod[0], minProdMiddle ?: default.prod[1], minProdOuter ?: default.prod[2])
        )
    }
}

object StartRecipes {
    /** SmallContinents.lua: method 2, `ChooseLocations{mustBeCoast = true}` and nothing lowered, AI map hint 4 */
    val smallContinents = StartRecipe(
        script = "SmallContinents.lua", mapType = MapType.smallContinentsLikeCivV,
        regionMethod = RegionMethod.Continental, mustBeCoast = true, aiMapHint = 4
    )

    /** Archipelago.lua: method 3, `mustBeCoast = true, minFoodMiddle = 2, minFoodOuter = 3, minProdOuter = 1`, AI map hint 1 + 4 */
    val archipelago = StartRecipe(
        script = "Archipelago.lua", mapType = MapType.archipelagoLikeCivV,
        regionMethod = RegionMethod.Rectangular, mustBeCoast = true,
        minFoodMiddle = 2, minFoodOuter = 3, minProdOuter = 1, aiMapHint = 1 + 4
    )

    /** TinyIslands.lua: method 3, `mustBeCoast = true, minFoodMiddle = 2, minProdMiddle = 1, minFoodOuter = 2, minProdOuter = 1`, AI map hint 1 + 4 */
    val tinyIslands = StartRecipe(
        script = "TinyIslands.lua", mapType = MapType.tinyIslandsLikeCivV,
        regionMethod = RegionMethod.Rectangular, mustBeCoast = true,
        minFoodMiddle = 2, minProdMiddle = 1, minFoodOuter = 2, minProdOuter = 1, aiMapHint = 1 + 4
    )

    val all = listOf(smallContinents, archipelago, tinyIslands)

    /** The start recipe of a map type, null for the types that start with the generic code */
    @Readonly
    fun forType(mapType: String): StartRecipe? = all.firstOrNull { it.mapType == mapType }
}
