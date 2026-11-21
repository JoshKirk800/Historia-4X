package com.unciv.logic.map.mapgenerator.resourceplacement

import com.unciv.Constants
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/**
 *  The plot lists of AssignStartingPlots:GenerateResourcePlotListsFromSpecificPlots (Vox Populi 5.4.8) that the strategic resources are placed on.
 *  A tile can be on several. Mountains are on none of these, natural wonders neither; a snow hill is on [Hills] and [Land] only.
 *  "No feature" means no feature but the hill, "wet" means next to fresh water.
 */
enum class VpPlotList {
    Land, Hills, HillsNoFeature, HillsForest, HillsJungle, HillsPlainsGrassNoFeature, HillsDesertTundraPlainsNoFeature,
    FlatForest, FlatJungle, FlatSnow, Marsh, PlainsGrassForest, FlatPlainsGrassForest, FlatTundraForest,
    FlatGrassNoFeature, FlatPlainsNoFeature, FlatDesertNoFeature, FlatTundraNoFeature,
    FlatPlainsGrassNoFeature, FlatDesertTundraNoFeature, FlatTundraPlainsGrassNoFeature,
    FlatDesertWet, FlatPlainsWetNoFeature, FlatPlainsDryNoFeature, FlatGrassWetNoFeature, FlatGrassDryNoFeature,
    CoastNextToLand;

    companion object {
        /** The lists [tile] is on, as the Lua puts it there (the Lua's `plot:IsFreshWater()` is a tile next to fresh water) */
        fun of(tile: Tile): List<VpPlotList> {
            if (tile.naturalWonder != null) return emptyList()
            val terrain = tile.baseTerrain
            val features = tile.terrainFeatures.filter { it != Constants.hill }
            if (tile.isWater) {
                if (terrain != Constants.coast || "Atoll" in features || Constants.ice in features) return emptyList()
                return if (tile.neighbors.any { !it.isWater }) listOf(CoastNextToLand) else emptyList()
            }
            if (terrain == Constants.mountain) return emptyList()
            val feature = features.singleOrNull()
            if (features.size > 1) return emptyList()
            val onPlainsOrGrass = terrain == Constants.plains || terrain == Constants.grassland
            val result = ArrayList<VpPlotList>(6)
            result += Land
            if (Constants.hill in tile.terrainFeatures) {
                result += Hills
                when (feature) {
                    null -> when (terrain) {
                        Constants.snow -> {}
                        Constants.tundra, Constants.desert -> { result += HillsNoFeature; result += HillsDesertTundraPlainsNoFeature }
                        Constants.plains -> { result += HillsNoFeature; result += HillsPlainsGrassNoFeature; result += HillsDesertTundraPlainsNoFeature }
                        Constants.grassland -> { result += HillsNoFeature; result += HillsPlainsGrassNoFeature }
                        else -> return emptyList()
                    }
                    Constants.jungle -> result += HillsJungle
                    Constants.forest -> { result += HillsForest; if (onPlainsOrGrass) result += PlainsGrassForest }
                    else -> return emptyList()
                }
                return result
            }
            val wet = tile.isAdjacentTo(Constants.freshWater)
            when (feature) {
                "Marsh" -> result += Marsh
                "Flood plains" -> result += FlatDesertWet
                Constants.jungle -> result += FlatJungle
                Constants.forest -> {
                    result += FlatForest
                    if (onPlainsOrGrass) { result += PlainsGrassForest; result += FlatPlainsGrassForest }
                    else if (terrain == Constants.tundra) result += FlatTundraForest
                }
                null -> when (terrain) {
                    Constants.snow -> result += FlatSnow
                    Constants.tundra -> { result += FlatTundraNoFeature; result += FlatDesertTundraNoFeature; result += FlatTundraPlainsGrassNoFeature }
                    Constants.desert -> { result += FlatDesertNoFeature; result += FlatDesertTundraNoFeature; if (wet) result += FlatDesertWet }
                    Constants.plains -> {
                        result += FlatPlainsNoFeature; result += FlatPlainsGrassNoFeature; result += FlatTundraPlainsGrassNoFeature
                        result += if (wet) FlatPlainsWetNoFeature else FlatPlainsDryNoFeature
                    }
                    Constants.grassland -> {
                        result += FlatGrassNoFeature; result += FlatPlainsGrassNoFeature; result += FlatTundraPlainsGrassNoFeature
                        result += if (wet) FlatGrassWetNoFeature else FlatGrassDryNoFeature
                    }
                    else -> return emptyList()
                }
                else -> {} // an oasis: on land and nothing else
            }
            return result
        }
    }
}

/**
 *  The strategic resources of AssignStartingPlots.lua of Vox Populi 5.4.8 (`VP-ASP` below): PlaceStrategicAndBonusResources up to the bonus resources,
 *  for rulesets that set `strategicPlacementLikeVoxPopuli`. Line numbers are of that file.
 *
 *  Every strategic resource has its own plot lists, each with its own "one deposit per N tiles" rule and a count of `ceil(list size / N)` (`ProcessResourceList`,
 *  `PlaceStrategicAndBonusResources`: 35 lists), so every non-empty list yields a deposit whatever the size of the map. Each resource has its own impact
 *  layer; a deposit blocks all layers on its own tile and ripples through its own layer only (`PlaceResourceImpact`). After them: the small deposit at each
 *  city state (2 in 5 get none, `AddModernMinorStrategicsToCityStates`), the small deposits on land (`PlaceSmallQuantitiesOfStrategics`: one per 30 land
 *  tiles), oil in the sea (`PlaceOilInTheSea`: units / 5 in deposits of 2 and of 3) and the top-ups for iron, horses, coal, oil, aluminum and uranium
 *  per civ (up to 999 rounds each).
 *
 *  Differences from the Lua, deliberate: the amounts of a deposit come from the resource's `majorDepositAmount` and `minorDepositAmount` (which
 *  hold the Lua's tables), a snow hill never receives a resource (the ruleset's `Never receives any resources`) though the Lua counts it in `Land`
 *  and `Hills`, and the tiles of the plot lists are those of the map when this runs: tiles with a luxury count in the lists (the Lua builds them
 *  before it places the luxuries), but cannot hold a deposit. The Lua's map script options `ReducedSupply` and the random resource density are not modelled.
 *
 *  @param regions only used for the number of civs (the Lua's `iNumCivs`)
 */
class VoxPopuliStrategics(
    private val tileMap: TileMap,
    private val regions: List<Region>,
    private val tileData: TileDataMap,
    private val ruleset: Ruleset,
    private val rng: Random
) {
    /** The strategic resources of the Lua, each with its impact layer (`ImpactLayers`: iron 1, horse 9, coal 10, oil 11, aluminum 12, uranium 13) */
    enum class Strategic(val resourceName: String) { Iron("Iron"), Horses("Horses"), Coal("Coal"), Oil("Oil"), Aluminum("Aluminum"), Uranium("Uranium") }

    /** One `ProcessResourceList` call of `PlaceStrategicAndBonusResources`: a deposit of [resource] per [tilesPerDeposit] tiles of [list]
     *  (times the density setting), each [quantityFactor] times the major amount and with an impact radius of [minRadius] to [maxRadius] */
    data class Rule(
        val resource: Strategic, val list: VpPlotList, val tilesPerDeposit: Int,
        val minRadius: Int, val maxRadius: Int, val quantityFactor: Double = 1.0
    )

    /** One round of a top-up (the `while ... < n * iNumCivs` loops): a deposit of [rule]'s resource on each of its lists, one tile per list */
    data class TopUp(val resource: Strategic, val unitsPerCiv: Int, val minRadius: Int, val maxRadius: Int, val lists: List<VpPlotList>)

    companion object {
        private const val NO_IMPACT = 0
        /** The impact of a tile that holds a deposit or a start (`PlaceResourceImpact`: `impact_value`); nothing is placed on it */
        private const val BLOCKED = 99

        /** The Lua's `ProcessResourceList` and top-up rounds give up after this many rounds (`iLoop < 999`) */
        const val MAXIMUM_TOP_UP_ROUNDS = 999
        /** `ProcessResourceList(99999, ...)`: one deposit for that many tiles, that is one for any non-empty list */
        private const val ONE_DEPOSIT = 99999.0

        /** `PlaceSmallQuantitiesOfStrategics(30 * resMultiplier, land)`: tiles of land for a small deposit */
        const val SMALL_DEPOSIT_TILES = 30

        /** `PlaceOilInTheSea`: the oil in the sea is a fifth of the units of oil placed so far, in deposits of [SEA_OIL_SMALL_AMOUNT] (radius 2 to 4)
         *  and of [SEA_OIL_MAJOR_AMOUNT] (radius 4 to 7) */
        const val SEA_OIL_FRACTION = 5
        const val SEA_OIL_SMALL_AMOUNT = 2
        const val SEA_OIL_MAJOR_AMOUNT = 3
        const val SEA_OIL_SMALL_MIN_RADIUS = 2
        const val SEA_OIL_SMALL_MAX_RADIUS = 4
        const val SEA_OIL_MAJOR_MIN_RADIUS = 4
        const val SEA_OIL_MAJOR_MAX_RADIUS = 7
        /** `PlaceSpecificNumberOfResources(..., ratio 0.2, ...)`: at most this share of the plot list gets a deposit */
        const val SEA_OIL_RATIO = 0.2

        /** `AddModernMinorStrategicsToCityStates`: a die of [CITY_STATE_DICE] sides, 0 and 1 give nothing, 2 coal, 3 oil, 4 aluminum, a small deposit within [CITY_STATE_RADIUS] */
        const val CITY_STATE_DICE = 5
        const val CITY_STATE_RADIUS = 3
        /** The impact a city state puts on all strategic layers (`PlaceCityState`: militaristic city states put none) */
        const val CITY_STATE_IMPACT_RADIUS = 3
        const val MILITARISTIC = "Militaristic"

        /** The variance of `TryPlacingOneResource`: a die of 4, a 3 makes the deposit 1.2 times as large, a 0 0.8 times, rounded */
        const val QUANTITY_DICE = 4
        const val QUANTITY_HIGH = 1.2
        const val QUANTITY_LOW = 0.8

        /** The lists of `PlaceStrategicAndBonusResources`, in the order of the Lua (horses, iron, coal, oil, aluminum, uranium) */
        val rules: List<Rule> = run {
            val h = Strategic.Horses; val i = Strategic.Iron; val c = Strategic.Coal; val o = Strategic.Oil; val a = Strategic.Aluminum; val u = Strategic.Uranium
            listOf(
                Rule(h, VpPlotList.FlatGrassNoFeature, 24, 1, 2), Rule(h, VpPlotList.FlatPlainsNoFeature, 36, 1, 2),
                Rule(h, VpPlotList.FlatDesertWet, 40, 2, 3, 0.7), Rule(h, VpPlotList.FlatTundraNoFeature, 60, 2, 3, 0.7),
                Rule(i, VpPlotList.HillsNoFeature, 75, 1, 3), Rule(i, VpPlotList.HillsForest, 45, 1, 3),
                Rule(i, VpPlotList.FlatDesertNoFeature, 50, 1, 3), Rule(i, VpPlotList.FlatPlainsGrassNoFeature, 80, 1, 3),
                Rule(i, VpPlotList.FlatPlainsGrassForest, 50, 1, 3), Rule(i, VpPlotList.FlatTundraNoFeature, 80, 1, 3),
                Rule(i, VpPlotList.FlatTundraForest, 55, 1, 3), Rule(i, VpPlotList.FlatSnow, 17, 1, 3),
                Rule(c, VpPlotList.HillsPlainsGrassNoFeature, 60, 1, 2), Rule(c, VpPlotList.FlatGrassNoFeature, 100, 1, 2),
                Rule(c, VpPlotList.FlatPlainsNoFeature, 80, 1, 2), Rule(c, VpPlotList.PlainsGrassForest, 70, 1, 2),
                Rule(o, VpPlotList.FlatDesertNoFeature, 35, 1, 3), Rule(o, VpPlotList.FlatTundraNoFeature, 75, 1, 3),
                Rule(o, VpPlotList.FlatJungle, 100, 1, 3), Rule(o, VpPlotList.Marsh, 25, 1, 3), Rule(o, VpPlotList.FlatSnow, 17, 1, 3),
                Rule(a, VpPlotList.HillsDesertTundraPlainsNoFeature, 38, 1, 3), Rule(a, VpPlotList.FlatDesertTundraNoFeature, 25, 1, 3),
                Rule(a, VpPlotList.FlatPlainsNoFeature, 50, 1, 3),
                Rule(u, VpPlotList.HillsNoFeature, 200, 2, 4), Rule(u, VpPlotList.HillsForest, 300, 2, 4),
                Rule(u, VpPlotList.HillsJungle, 50, 2, 4), Rule(u, VpPlotList.FlatJungle, 150, 2, 4),
                Rule(u, VpPlotList.FlatDesertNoFeature, 150, 2, 4), Rule(u, VpPlotList.FlatTundraNoFeature, 90, 2, 4),
                Rule(u, VpPlotList.FlatTundraForest, 350, 2, 4), Rule(u, VpPlotList.FlatPlainsGrassNoFeature, 300, 2, 4),
                Rule(u, VpPlotList.FlatPlainsGrassForest, 325, 2, 4), Rule(u, VpPlotList.Marsh, 37, 2, 4),
                Rule(u, VpPlotList.FlatSnow, 35, 2, 4)
            )
        }

        /** The checks "Map has very low iron / horse / coal / oil / aluminum / uranium" at the end of `PlaceStrategicAndBonusResources`, in order */
        val topUps: List<TopUp> = listOf(
            TopUp(Strategic.Iron, 4, 1, 2, listOf(VpPlotList.Hills, VpPlotList.FlatForest, VpPlotList.FlatDesertNoFeature)),
            TopUp(Strategic.Horses, 4, 1, 2, listOf(VpPlotList.FlatTundraPlainsGrassNoFeature, VpPlotList.FlatDesertWet)),
            TopUp(Strategic.Coal, 4, 1, 2, listOf(VpPlotList.HillsPlainsGrassNoFeature, VpPlotList.FlatPlainsGrassNoFeature, VpPlotList.PlainsGrassForest)),
            TopUp(Strategic.Oil, 4, 1, 2, listOf(VpPlotList.FlatDesertTundraNoFeature, VpPlotList.FlatJungle)),
            TopUp(Strategic.Aluminum, 5, 1, 2, listOf(VpPlotList.HillsDesertTundraPlainsNoFeature, VpPlotList.FlatDesertTundraNoFeature, VpPlotList.FlatPlainsNoFeature)),
            TopUp(Strategic.Uranium, 2, 2, 4, listOf(VpPlotList.Land))
        )

        /** `AddModernMinorStrategicsToCityStates`: the lists a city state's small deposit is looked for on, best first, by die roll */
        fun cityStateLists(diceRoll: Int): Pair<Strategic, List<VpPlotList>>? = when (diceRoll) {
            2 -> Strategic.Coal to listOf(VpPlotList.HillsPlainsGrassNoFeature, VpPlotList.FlatPlainsDryNoFeature, VpPlotList.FlatGrassDryNoFeature,
                VpPlotList.FlatGrassWetNoFeature, VpPlotList.FlatPlainsWetNoFeature)
            3 -> Strategic.Oil to listOf(VpPlotList.FlatDesertNoFeature, VpPlotList.Marsh, VpPlotList.FlatJungle, VpPlotList.FlatTundraNoFeature,
                VpPlotList.CoastNextToLand)
            4 -> Strategic.Aluminum to listOf(VpPlotList.HillsDesertTundraPlainsNoFeature, VpPlotList.FlatDesertTundraNoFeature,
                VpPlotList.FlatPlainsDryNoFeature, VpPlotList.FlatPlainsWetNoFeature)
            else -> null
        }

        /** The quantity of a deposit after the variance of `TryPlacingOneResource` ([roll] is the die of [QUANTITY_DICE] sides) */
        fun varied(quantity: Double, roll: Int): Int {
            val factor = when (roll) { QUANTITY_DICE - 1 -> QUANTITY_HIGH; 0 -> QUANTITY_LOW; else -> 1.0 }
            return floor(quantity * factor + 0.5).toInt()
        }

        /**
         *  The resource `PlaceSmallQuantitiesOfStrategics` puts on [tile], by the die rolls of its terrain tables, or null when the table has none for it
         *  (flood plains and oases; a flat desert next to fresh water gets horses without a roll). [roll] draws a number below its argument.
         */
        fun smallDepositFor(tile: Tile, roll: (Int) -> Int): Strategic? {
            val features = tile.terrainFeatures.filter { it != Constants.hill }
            val hills = Constants.hill in tile.terrainFeatures
            val terrain = tile.baseTerrain
            val wet = tile.isAdjacentTo(Constants.freshWater)
            val uranium = Strategic.Uranium; val iron = Strategic.Iron; val coal = Strategic.Coal
            val horses = Strategic.Horses; val oil = Strategic.Oil; val aluminum = Strategic.Aluminum
            return when (features.singleOrNull()) {
                Constants.jungle, "Marsh" -> if (roll(5) < 1 || hills) uranium else oil
                Constants.forest -> {
                    val d = roll(10)
                    if (terrain == Constants.grassland || terrain == Constants.plains) { if (d < 2) uranium else if (d < 5) coal else iron }
                    else if (d < 2) uranium else iron
                }
                null -> if (features.isNotEmpty()) null else if (hills) when (terrain) {
                    Constants.grassland -> roll(10).let { if (it < 1) uranium else if (it < 6) iron else coal }
                    Constants.plains -> roll(10).let { if (it < 1) uranium else if (it < 4) iron else if (it < 7) coal else aluminum }
                    else -> roll(10).let { if (it < 1) uranium else if (it < 5) iron else aluminum }
                } else when (terrain) {
                    Constants.grassland ->
                        if (wet) roll(10).let { if (it < 1) uranium else if (it < 3) iron else horses }
                        else roll(10).let { if (it < 1) uranium else if (it < 4) iron else if (it < 7) coal else horses }
                    Constants.plains -> roll(10).let {
                        if (it < 1) uranium else if (it < 3) iron else if (it < 5) coal else if (it < 7) horses else aluminum
                    }
                    Constants.desert ->
                        if (wet) horses
                        else roll(10).let { if (it < 1) uranium else if (it < 3) iron else if (it < 6) oil else aluminum }
                    Constants.tundra -> roll(10).let { if (it < 1) uranium else if (it < 6) iron else if (it < 9) horses else oil }
                    else -> roll(10).let { if (it < 3) uranium else if (it < 6) iron else oil }
                }
                else -> null
            }
        }
    }

    private val resources: Map<Strategic, TileResource?> = Strategic.entries.associateWith { ruleset.tileResources[it.resourceName] }
    private val setting = tileMap.mapParameters.getMapResources()
    private val densityMultiplier = setting.bonusFrequencyMultiplier.toDouble()
    private val civs = regions.size

    /** The units of each resource placed so far, as the Lua's `amounts_of_resources_placed` counts them */
    private val unitsPlaced = Strategic.entries.associateWith { strategic ->
        tileMap.values.filter { it.resource == strategic.resourceName }.sumOf { max(it.resourceAmount, 1) }
    }.toMutableMap()

    // The impact layers (`impactData`): one per resource. A tile in [blocked] has 99 on all of them (a start, a city state, a deposit),
    // the others the ripples of the deposits of their layer. Ripples of a layer are what `PlaceResourceImpact` makes of them.
    private val blocked = HashSet<Tile>()
    private val ripples = Strategic.entries.associateWith { HashMap<Tile, Int>() }

    private fun impact(layer: Strategic, tile: Tile) = if (tile in blocked) BLOCKED else ripples[layer]!![tile] ?: NO_IMPACT

    /** `PlaceResourceImpact`: 99 on all layers at [tile], and on [layer] (null: on all layers, `PlaceStrategicResourceImpact`) a ripple of [radius] rings:
     *  `radius - ring + 1`, where there is one already the larger of both plus 2, at most 99 */
    private fun placeImpact(layer: Strategic?, tile: Tile, radius: Int) {
        blocked += tile
        if (radius <= 0) return
        for (target in if (layer == null) Strategic.entries else listOf(layer)) {
            val layerRipples = ripples[target]!!
            for (ring in 1..radius) {
                val value = radius - ring + 1
                tile.forEachTileAtDistance(ring) { other ->
                    if (other in blocked) return@forEachTileAtDistance
                    val existing = layerRipples[other] ?: 0
                    layerRipples[other] = if (existing > 0) minOf(BLOCKED, max(existing, value) + 2) else value
                }
            }
        }
    }

    private fun totalImpact(tile: Tile) = Strategic.entries.sumOf { impact(it, tile) }

    private fun canReceive(tile: Tile) =
        tile.resource == null && !tile.getBaseTerrain().hasUnique(UniqueType.BlocksResources, GameContext(tile = tile))

    private fun amountOf(resource: TileResource, major: Boolean): Int {
        val amounts = if (major) resource.majorDepositAmount else resource.minorDepositAmount
        return when (setting) {
            MapResourceSetting.sparse -> amounts.sparse
            MapResourceSetting.abundant -> amounts.abundant
            else -> amounts.default
        }
    }

    private fun put(strategic: Strategic, tile: Tile, quantity: Int) {
        val resource = resources[strategic]!!
        tile.setTileResource(resource, majorDeposit = false)
        tile.resourceAmount = quantity
        unitsPlaced[strategic] = unitsPlaced[strategic]!! + quantity
    }

    private lateinit var globalLists: Map<VpPlotList, List<Tile>>

    /** `GenerateGlobalResourcePlotLists`: tiles without a start, a city state, a wonder or a resource other than a luxury, each list shuffled */
    private fun buildGlobalLists() {
        val taken = tileMap.startingLocationsByNation.values.flatten().toSet()
        val lists = VpPlotList.entries.associateWith { ArrayList<Tile>() }
        for (tile in tileMap.values) {
            if (tile in taken) continue
            val resource = tile.tileResource
            if (resource != null && resource.resourceType != ResourceType.Luxury) continue
            for (list in VpPlotList.of(tile)) lists[list]!!.add(tile)
        }
        for (list in lists.values) list.shuffle(rng)
        globalLists = lists
    }

    /** What the starts, city states and wonders put on the layers before the strategics: no deposit on their tiles, ripples around the city states */
    private fun seedImpacts() {
        for (tile in tileMap.values)
            if (tileData[tile]?.impacts?.get(ImpactType.Strategic) == BLOCKED || tile.naturalWonder != null) blocked += tile
        for ((civName, starts) in tileMap.startingLocationsByNation) {
            val nation = ruleset.nations[civName] ?: continue
            for (start in starts) {
                blocked += start
                if (nation.isCityState && nation.cityStateType != MILITARISTIC) placeImpact(null, start, CITY_STATE_IMPACT_RADIUS)
            }
        }
    }

    /** `TryPlacingOneResource`: the radius of the impact and the variance of the quantity are drawn, the deposit is a major one or a small one */
    private fun tryPlacing(strategic: Strategic, tile: Tile, major: Boolean, quantityFactor: Double, minRadius: Int, maxRadius: Int): Boolean {
        if (!canReceive(tile)) return false
        val resource = resources[strategic] ?: return false
        val radius = minRadius + rng.nextInt(maxRadius - minRadius + 1)
        put(strategic, tile, varied(amountOf(resource, major) * quantityFactor, rng.nextInt(QUANTITY_DICE)))
        placeImpact(strategic, tile, radius)
        return true
    }

    /** `ProcessResourceList`: `ceil(size / frequency)` deposits, on the tiles in list order that have no impact on the layer of the resource, then,
     *  when the list is used up, on the tile with the least impact; none if there is no tile without a resource left */
    private fun processResourceList(
        strategic: Strategic, frequency: Double, list: List<Tile>, major: Boolean, quantityFactor: Double, minRadius: Int, maxRadius: Int
    ) {
        val total = list.size
        val toPlace = ceil(total / frequency).toInt()
        var index = 0
        var avoidRipples = true
        var placed = 0
        while (placed < toPlace) {
            if (avoidRipples) {
                var check = index
                while (check < total) {
                    if (check == total - 1) avoidRipples = false
                    val tile = list[check]
                    check++
                    index = check
                    if (impact(strategic, tile) == NO_IMPACT && tryPlacing(strategic, tile, major, quantityFactor, minRadius, maxRadius)) { placed++; break }
                }
            }
            if (!avoidRipples) {
                var lowest = BLOCKED - 1
                var best: Tile? = null
                for (tile in list) {
                    val value = impact(strategic, tile)
                    if (value < lowest && canReceive(tile)) { lowest = value; best = tile }
                }
                if (best == null) return
                if (tryPlacing(strategic, best, major, quantityFactor, minRadius, maxRadius)) placed++
            }
        }
    }

    /** The lists of `PlaceStrategicAndBonusResources`, resource by resource */
    fun placeMajorDeposits() {
        for (rule in rules)
            processResourceList(rule.resource, rule.tilesPerDeposit * densityMultiplier, globalLists[rule.list]!!, true, rule.quantityFactor, rule.minRadius, rule.maxRadius)
    }

    /** `AddModernMinorStrategicsToCityStates`: no impact is placed by these deposits */
    fun placeCityStateDeposits() {
        for ((civName, starts) in tileMap.startingLocationsByNation) {
            if (ruleset.nations[civName]?.isCityState != true) continue
            val start = starts.first()
            val (strategic, wanted) = cityStateLists(rng.nextInt(CITY_STATE_DICE)) ?: continue
            val resource = resources[strategic] ?: continue
            val site = VpPlotList.entries.associateWith { ArrayList<Tile>() }
            for (ring in 1..CITY_STATE_RADIUS) for (tile in start.getTilesAtDistance(ring)) for (list in VpPlotList.of(tile)) site[list]!!.add(tile)
            for (list in wanted) {
                val tile = site[list]!!.also { it.shuffle(rng) }.firstOrNull { it.resource == null } ?: continue
                put(strategic, tile, amountOf(resource, false))
                break
            }
        }
    }

    /** `PlaceSmallQuantitiesOfStrategics`: one small deposit per [SMALL_DEPOSIT_TILES] tiles of land (times the density), on the tiles in list order that no
     *  layer has an impact on, the kind by the terrain's die rolls; a tile the tables have nothing for is passed over */
    fun placeSmallDeposits() {
        val land = globalLists[VpPlotList.Land]!!
        val toPlace = ceil(land.size / (SMALL_DEPOSIT_TILES * densityMultiplier)).toInt()
        var index = 0
        repeat(toPlace) {
            while (index < land.size) {
                val tile = land[index++]
                if (totalImpact(tile) != NO_IMPACT || !canReceive(tile)) continue
                val strategic = smallDepositFor(tile) { rng.nextInt(it) } ?: continue
                val resource = resources[strategic] ?: continue
                val radius = rng.nextInt(4).let { if (it > 2) 1 else it }
                put(strategic, tile, amountOf(resource, false))
                placeImpact(null, tile, radius)
                break
            }
        }
    }

    /** `PlaceOilInTheSea`: a fifth of the units of oil placed so far, in deposits of 2 (radius 2 to 4) and again in deposits of 3 (radius 4 to 7), on coast next
     *  to land that has no oil impact, in list order (`PlaceSpecificNumberOfResources`: one pass over the list per deposit, no fallback) */
    fun placeSeaOil() {
        if (resources[Strategic.Oil] == null) return
        val units = unitsPlaced[Strategic.Oil]!!
        val list = globalLists[VpPlotList.CoastNextToLand]!!
        fun place(amount: Int, count: Int, minRadius: Int, maxRadius: Int) {
            repeat(minOf(count, ceil(SEA_OIL_RATIO * list.size).toInt())) {
                val tile = list.firstOrNull { impact(Strategic.Oil, it) == NO_IMPACT && it.resource == null } ?: return@repeat
                put(Strategic.Oil, tile, amount)
                placeImpact(Strategic.Oil, tile, minRadius + rng.nextInt(maxRadius - minRadius + 1))
            }
        }
        place(SEA_OIL_SMALL_AMOUNT, (units / SEA_OIL_FRACTION.toDouble() / SEA_OIL_SMALL_AMOUNT).toInt(), SEA_OIL_SMALL_MIN_RADIUS, SEA_OIL_SMALL_MAX_RADIUS)
        place(SEA_OIL_MAJOR_AMOUNT, (units / SEA_OIL_FRACTION.toDouble() / SEA_OIL_MAJOR_AMOUNT).toInt(), SEA_OIL_MAJOR_MIN_RADIUS, SEA_OIL_MAJOR_MAX_RADIUS)
    }

    /** The "Map has very low ..." loops: while a resource has fewer than [TopUp.unitsPerCiv] units per civ, a small deposit on each of the lists, at most [MAXIMUM_TOP_UP_ROUNDS] rounds */
    fun topUpDeposits() {
        for (topUp in topUps) {
            var round = 0
            while (unitsPlaced[topUp.resource]!! < topUp.unitsPerCiv * civs && round < MAXIMUM_TOP_UP_ROUNDS) {
                val before = unitsPlaced[topUp.resource]!!
                for (list in topUp.lists)
                    processResourceList(topUp.resource, ONE_DEPOSIT, globalLists[list]!!, false, 1.0, topUp.minRadius, topUp.maxRadius)
                round++
                if (unitsPlaced[topUp.resource]!! == before) break // nothing can be placed: the rounds that remain would not place anything either
            }
        }
    }

    /** The plot lists and the impacts of the starts, city states and wonders: what the stages that follow work on */
    fun prepare() {
        buildGlobalLists()
        seedImpacts()
    }

    /** All of it, in the order of `PlaceStrategicAndBonusResources` (the stages are public for the tests) */
    fun placeStrategics() {
        prepare()
        placeMajorDeposits()
        placeCityStateDeposits()
        placeSmallDeposits()
        placeSeaOil()
        topUpDeposits()
    }
}
