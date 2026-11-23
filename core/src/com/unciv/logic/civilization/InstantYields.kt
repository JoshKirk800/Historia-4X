package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.TradeRoute
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.roundToInt

/** The ways a city gets a tile, the second parameter of [UniqueType.TriggerUponAcquiringTile] (the `Trait_YieldFromTile...` tables of Vox Populi) */
enum class TileAcquisition(val label: String) {
    /** The culture of the city is enough for a new tile (`CvCity::DoJONSCultureLevelIncrease`, `INSTANT_YIELD_TYPE_BORDERS`) */
    BorderGrowth("border growth"),
    /** The city buys the tile with gold (`Trait_YieldFromTilePurchase`, `INSTANT_YIELD_TYPE_TILE_PURCHASE`) */
    Purchase("purchase"),
    /** The tile belongs to the city when it is founded (`Trait_YieldFromTileSettle`, `INSTANT_YIELD_TYPE_FOUND`) */
    Settling("settling"),
    /** The tile belongs to a city that was conquered (`Trait_YieldFromTileConquest`, `INSTANT_YIELD_TYPE_F_CONQUEST`) */
    Conquest("conquest"),
    /** A culture bomb or a free tile takes a tile that no one owns (`Trait_YieldFromTileCultureBomb`, `INSTANT_YIELD_TYPE_CULTURE_BOMB`) */
    CultureBomb("culture bomb"),
    /** A culture bomb takes a tile that another civilization owns (`Trait_YieldFromTileStealCultureBomb`) */
    StolenByCultureBomb("stolen by culture bomb"),
}

/**
 *  The instant yields of Vox Populi (`CvPlayer::doInstantYield`, V:CvPlayer.cpp:25909-28247): what an event gives the civilization, in one place.
 *  Vox Populi has 64 event types and a table of every yield for each of them; here the event is a trigger condition (the new ones are in
 *  [UniqueType.TriggerUponPopulationGrowth] and the following), the yield a unique of any object that has the trigger as its modifier
 *  (`Gain [amount] [stat] <upon population growth>`, [UniqueType.OneTimeGainStat]), so the ruleset's data and not the code holds the tables.
 *
 *  [fire] is `doInstantYield`'s loop over the cities, which says which uniques an event takes (V:CvPlayer.cpp:25928-25931 and the handlers of each event):
 *  - the event's own city, or every city if the event has none, runs the uniques of its buildings that say `in this city` (the building's city tables, which
 *    VP reads from `pLoopCity`) and of its religion (the follower beliefs of the city's majority religion, `pReligion`), with the city as the context;
 *  - then one pass for the civilization: its nation, policies, techs, era, founder beliefs and global uniques, and the buildings' uniques that do not say
 *    `in this city` (VP's `...Global` tables and player fields), which VP adds in the pass of the event's city (BIRTH, BORDERS, CONSTRUCTION...) or of the capital
 *    (TECH, GP_USE, F_CONQUEST...). The city of the pass is the event's, else the capital: that is where Food and Production go.
 *
 *  No yields for city-states and barbarians (V:CvPlayer.cpp:25912). Every unique runs once for each time its `<for every ...>` modifiers say, with the
 *  event's [GameContext.amount] as the countable `Triggering amount`, and the `Era scaler` for the yields that VP multiplies by `max(1, era)`.
 *
 *  What VP does with the value afterwards is [scaleBySpeed] (the speed's `InstantYieldPercent`, cut off) and [gain] (V:CvPlayer.cpp:27186-27326): Food adds to
 *  the city's stored food, Production to its current construction or else its overflow, Culture to the civilization and the city's border culture, the rest to the
 *  civilization, each never taking a city store under 0.
 *
 *  Departures from Vox Populi: the speed percentage and the era are in the data (the modifiers `(modified by game speed)` and `<for every [Era scaler]>`) and not in
 *  the code, so the twelve events without the percentage and the seven that scale inside their handler are the uniques that the data writes without them; VP's two calls
 *  for most events (one scaled by the era, one not) are two uniques; VP's `m_viInstantYieldsTotal` is [InstantYieldTotals], by event, and not saved.
 */
object InstantYields {

    /**
     *  Fires [trigger] for [civ]: every unique that has it as a trigger condition, whose conditionals apply and that [triggerFilter] (on the trigger condition, as in
     *  [Civilization.forEachTriggeredUnique]) and [effectFilter] (on the unique) accept, is triggered ([UniqueTriggerActivation.triggerUnique]) for each time its `<for every>` modifiers say.
     *  @param city the city of the event, which limits the first part to its own buildings and religion, null for an event of the whole civilization
     *  @param amount what the event carries: [GameContext.amount], the countable `Triggering amount` and the base of [UniqueType.OneTimeGainStatOfTriggeringAmount]
     *  @param context what the conditionals see, with the unit and tile of the event if it has them; the city and the amount are put into it
     */
    fun fire(
        trigger: UniqueType,
        civ: Civilization,
        city: City? = null,
        amount: Int = 0,
        context: GameContext = GameContext(civ),
        triggerNotificationText: String? = null,
        triggerFilter: (Unique) -> Boolean = { true },
        effectFilter: (Unique) -> Boolean = { true },
    ) {
        if (civ.isCityState || civ.isBarbarian || civ.isSpectator) return

        // Gathered first, since a trigger can add buildings, policies or techs that have triggers themselves
        val gathered = ArrayList<Pair<Unique, City?>>()

        for (cityOfEvent in if (city != null) listOf(city) else civ.cities.toList()) {
            val cityContext = context.copy(city = cityOfEvent, tile = context.tile ?: cityOfEvent.getCenterTileOrNull(), amount = amount)
            cityOfEvent.forEachLocalTriggeredUnique(trigger, cityContext, triggerFilter) { if (effectFilter(it)) gathered.add(it to cityOfEvent) }
        }

        val creditedCity = city ?: civ.getCapital()
        val civContext = context.copy(city = creditedCity, amount = amount)
        civ.forEachTriggeredUnique(trigger, civContext, triggerFilter, true) { if (effectFilter(it)) gathered.add(it to creditedCity) }
        for (cityWithBuildings in civ.cities)
            cityWithBuildings.cityConstructions.builtBuildingUniqueMap.forEachUnique(
                { !it.isLocalEffect && it.getModifiers(trigger).any(triggerFilter) && it.conditionalsApply(civContext) },
                { it.forEachMultiplied(civContext) { unique -> if (effectFilter(unique)) gathered.add(unique to creditedCity) } }
            )

        for ((unique, creditedTo) in gathered) {
            val tile = context.tile ?: creditedTo?.getCenterTileOrNull() ?: context.unit?.takeIf { it.hasTile() }?.currentTile
            UniqueTriggerActivation.triggerUnique(unique, civ, creditedTo, context.unit, tile,
                triggerNotificationText = triggerNotificationText, triggeringAmount = amount)
        }
        civ.instantYieldNotices.flush(civ) // one notification for the event, see InstantYieldNotices
    }

    /**
     *  Whether any unique that [fire] could take for [trigger] exists for [civ] (its nation, buildings, policies, techs, era, religion or global uniques), whatever its
     *  conditionals say: for the places that do something more than fire only when someone listens, and for loops over many tiles that would fire for nothing
     */
    fun hasHandlers(civ: Civilization, trigger: UniqueType): Boolean {
        if (civ.isCityState || civ.isBarbarian || civ.isSpectator) return false
        var found = false
        // The context that skips conditionals and multipliers: every unique with the trigger counts once
        civ.forEachTriggeredUnique(trigger, GameContext.IgnoreMultiplicationForCaching, { true }, false) { found = true }
        return found || civ.cities.any { city -> city.religion.getAllUniques().any { unique -> unique.getModifiers(trigger).isNotEmpty() } }
    }

    /**
     *  [tile] (null: none was left to take, which `border growth` still counts, for the filter `[All]` only) went to [city], or to another city of its owner but is credited to
     *  [city] (the capital for a conquered city's tiles, as in VP), in the way [kind]: fires [UniqueType.TriggerUponAcquiringTile] for the uniques whose tile filter matches
     *  the tile and whose kind is [kind]
     */
    fun tileAcquired(city: City, tile: Tile?, kind: TileAcquisition) {
        val civ = city.civ
        fire(UniqueType.TriggerUponAcquiringTile, civ, city, context = GameContext(civ, city, tile = tile),
            triggerNotificationText = "due to acquiring a tile",
            triggerFilter = { trigger ->
                trigger.params[1] == kind.label &&
                    if (tile == null) trigger.params[0] in Constants.all else tile.matchesFilter(trigger.params[0], civ)
            })
    }

    /**
     *  CvPlayer::CheckForLuxuryResourceGainInstantYields (V:CvPlayer.cpp:38561-38590): for each luxury resource of which [civ] now has more of its own, without what it
     *  imports and with what it exports, than the most it ever had ([Civilization.highestLuxuryAmounts]), fires [UniqueType.TriggerUponGainingLuxury] in the capital with
     *  the difference as the amount. Called at the start of the civilization's turn, as VP's `CvPlayer::doTurn` does; VP also checks when the number of a resource changes,
     *  which here waits until the next turn start.
     */
    fun checkLuxuryGains(civ: Civilization) {
        if (civ.isCityState || civ.isBarbarian || civ.cities.isEmpty()) return
        val owned = HashMap<String, Int>()
        for (supply in civ.detailedCivResources)
            if (supply.resource.resourceType == ResourceType.Luxury && supply.origin != "Trade" && supply.origin != Constants.cityStates)
                owned.merge(supply.resource.name, supply.amount, Int::plus)
        for (name in owned.keys.sorted()) {
            val quantity = owned.getValue(name)
            val highest = civ.highestLuxuryAmounts[name] ?: 0
            if (quantity <= highest) continue
            civ.highestLuxuryAmounts[name] = quantity
            fire(UniqueType.TriggerUponGainingLuxury, civ, civ.getCapital(), quantity - highest, triggerNotificationText = "due to gaining [$name]")
        }
    }

    /**
     *  INSTANT_YIELD_TYPE_TR_END (V:CvTradeClasses.cpp:2606-2650): a trade route of [civ] ran its course. Fires [UniqueType.TriggerUponCompletingTradeRoute] in the city it started in
     *  and, for a domestic route, once more in the city it ended in (the original calls `doInstantYield` for each end, so what the civilization's own uniques give is given twice).
     */
    fun tradeRouteCompleted(civ: Civilization, route: TradeRoute, origin: City, destination: City?) {
        val kind = if (route.isInternational()) "international" else "domestic"
        val filter = { trigger: Unique -> trigger.params[0] == "any" || trigger.params[0] == kind }
        fire(UniqueType.TriggerUponCompletingTradeRoute, civ, origin, triggerNotificationText = "due to completing a trade route", triggerFilter = filter)
        if (!route.isInternational() && destination != null)
            fire(UniqueType.TriggerUponCompletingTradeRoute, civ, destination, triggerNotificationText = "due to completing a trade route", triggerFilter = filter)
    }

    /**
     *  INSTANT_YIELD_TYPE_TR_MOVEMENT (V:CvTradeClasses.cpp:2085-2150): the trade unit of a route of [civ] that starts in [origin] has made its steps of the turn (and when it starts,
     *  V:CvTradeClasses.cpp:848): fires [UniqueType.TriggerUponTradeUnitMoving] once, in the city of origin, however many steps it made.
     */
    fun tradeUnitMoved(civ: Civilization, origin: City) {
        fire(UniqueType.TriggerUponTradeUnitMoving, civ, origin, triggerNotificationText = "due to a trade route unit moving")
    }

    /**
     *  INSTANT_YIELD_TYPE_PLUNDER_TRADE_ROUTE (V:CvTradeClasses.cpp:5034, V:CvPlayer.cpp:26863-26870): [plunderer] of [civ] plundered a trade route. Fires
     *  [UniqueType.TriggerUponPlunderingTradeRoute] for the civilization's uniques and for the unit's own, in the city the unit was made in, the capital for a unit with none.
     */
    fun tradeRoutePlundered(civ: Civilization, plunderer: MapUnit?) {
        if (civ.isCityState || civ.isBarbarian || civ.isSpectator) return
        val city = plunderer?.getOriginCity()?.takeIf { it.civ == civ } ?: civ.getCapital()
        val context = GameContext(civ, city = city, unit = plunderer, tile = plunderer?.takeIf { it.hasTile() }?.currentTile ?: city?.getCenterTileOrNull())
        val notification = "due to plundering a trade route"
        fire(UniqueType.TriggerUponPlunderingTradeRoute, civ, city, context = context, triggerNotificationText = notification)
        if (plunderer != null)
            for (unique in plunderer.getTriggeredUniques(UniqueType.TriggerUponPlunderingTradeRoute, context).toList())
                UniqueTriggerActivation.triggerUnique(unique, civ, city, plunderer, context.tile, triggerNotificationText = notification)
    }

    /** Whether [unique] is one of the effects that Vox Populi's `processBuilding` hands out only the first time a building is built in a city (V:CvCity.cpp:14830-14853) */
    @Readonly
    fun isGain(unique: Unique) = when (unique.type) {
        UniqueType.OneTimeGainStat, UniqueType.OneTimeGainStatRange, UniqueType.OneTimeGainStatOfTriggeringAmount,
        UniqueType.OneTimeGainGoldenAgePoints, UniqueType.OneTimeGainTourism, UniqueType.OneTimeGainPopulation -> true
        else -> false
    }

    /**
     *  [amount] of [stat] changed by the game speed if [unique] has the modifier `(modified by game speed)`.
     *  With the ModConstants `instantYieldsUseInstantYieldPercent` it is CvPlayer::doInstantYield's `value * InstantYieldPercent / 100` (whole numbers, cut off),
     *  else the speed's cost modifier of the stat, rounded, as the unique always did.
     */
    @Readonly
    fun scaleBySpeed(unique: Unique, stat: Stat, amount: Int, gameInfo: GameInfo): Int {
        if (!unique.isModifiedByGameSpeed()) return amount
        val speed = gameInfo.speed
        if (gameInfo.ruleset.modOptions.constants.instantYieldsUseInstantYieldPercent)
            return (amount.toLong() * (speed.instantYieldModifier * 100).roundToInt() / 100).toInt()
        return (amount * speed.statCostModifiers[stat]!!).roundToInt()
    }

    /**
     *  Gives [amount] of [stat] (CvPlayer::doInstantYield, V:CvPlayer.cpp:27186-27326): to [city] for Food (its stored food, never under 0) and Production (its current
     *  construction while that is unfinished or the amount is negative, else its overflow, never under 0), to the civilization for the rest, Culture also to the border
     *  culture of [city] if there is one. Tallies it in [Civilization.instantYieldTotals] under the trigger condition of [unique].
     *  @param city null for a stat of the civilization; Food and Production need it and are not given without
     */
    fun gain(civ: Civilization, city: City?, stat: Stat, amount: Int, unique: Unique) {
        when (stat) {
            Stat.Food -> {
                val population = (city ?: return).population
                population.foodStored = max(0, population.foodStored + amount)
            }
            Stat.Production -> addProduction(city ?: return, amount)
            Stat.Culture -> {
                civ.addStat(stat, amount)
                if (city != null) city.expansion.cultureStored += amount
            }
            else -> civ.addStat(stat, amount)
        }
        civ.instantYieldTotals.add(eventOf(unique), stat, amount)
    }

    /** V:CvPlayer.cpp:27200-27219: the current construction gets it while it has not been paid for or the amount takes production away, else the city's overflow; neither goes under 0 */
    private fun addProduction(city: City, amount: Int) {
        val constructions = city.cityConstructions
        val current = constructions.getCurrentConstruction()
        if (current is INonPerpetualConstruction &&
            (constructions.getWorkDone(current.name) < current.getProductionCost(city.civ, city) || amount < 0)) {
            constructions.inProgressConstructions[current.name] = max(0, constructions.getWorkDone(current.name) + amount)
        } else
            constructions.productionOverflow = max(0, constructions.productionOverflow + amount)
    }

    /** The name of the trigger condition's unique type that [unique] has as a modifier, for [InstantYieldTotals]; [InstantYieldTotals.DIRECT] if it has none */
    @Readonly
    fun eventOf(unique: Unique): String =
        unique.modifiers.firstOrNull { it.type?.targetTypes?.contains(UniqueTarget.TriggerCondition) == true }?.type?.name ?: InstantYieldTotals.DIRECT
}
