package com.unciv.logic.city

import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.HistoricEvent
import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.league.addContribution
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.isUsersTurn
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.IRulesetObject
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.screens.civilopediascreen.CivilopediaCategories
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.pickerscreens.PromotionTree
import com.unciv.utils.withItem
import com.unciv.utils.withoutItem
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * City constructions manager.
 *
 * @property city the city it refers to
 * @property  currentConstructionName() name of the construction that is currently being produced
 * @property currentConstructionIsUserSet a flag indicating if the [ currentConstructionName()] has been set by the user or by the AI
 * @property constructionQueue a list of constructions names enqueued
 */
class CityConstructions : IsPartOfGameInfoSerialization {
    companion object {
        private const val queueMaxSize = 10
    }

    //region Non-Serialized Properties
    @Transient
    lateinit var city: City

    @Transient
    private var builtBuildingObjects = ArrayList<Building>()

    @Transient @Cache
    private val containedBuildingFiltersCache = HashMap<String, Boolean>()

    @Transient @Cache
    private var productionCache: HashMap<List<Any>, Float>? = null
    @Transient
    private var productionCacheOwner: Thread? = null

    @Transient
    val builtBuildingUniqueMap = UniqueMap()

    @Readonly fun currentConstructionName(): String {
        val queue = constructionQueue // Single read, the field may be swapped by another thread
        return if (queue.isEmpty()) "" else queue.first()
    }
    fun setCurrentConstruction(value: String) = editQueue {
        if (isEmpty()) add(value) else this[0] = value
    }

    /** The queue is never mutated in place, only replaced as a whole, so concurrent readers always see a consistent list.
     *  Readers must take the field **once** per task and work on that snapshot.
     *  Writers are `@Synchronized` (read-copy-assign must not interleave); all modifications must go through here
     *  or another `@Synchronized` function of this class. */
    @Synchronized
    fun editQueue(edit: MutableList<String>.() -> Unit) {
        val newQueue = ArrayList(constructionQueue)
        newQueue.edit()
        constructionQueue = newQueue
    }

    //endregion
    //region Serialized Fields

    var builtBuildings = HashSet<String>()

    /** CvCityBuildings::m_paiFirstTimeBuilding: the buildings this city has ever had, sold or not. A building's instant yields ([InstantYields.isGain]) are only
     *  given the first time it is built here (V:CvCity.cpp:14836); [setTransients] adds the built buildings, which is what a saved game from before this was kept has */
    var everBuilt = HashSet<String>()

    /** CvCity::m_abBuildingInvestment and m_aiBuildingCostInvestmentReduction: the buildings that gold was invested in
     *  ([com.unciv.models.ModConstants.buildingInvestments]) with the production that the investment took off each one's cost. A saved game from before them has none */
    val investedBuildings = HashMap<String, Int>()
    val inProgressConstructions = HashMap<String, Int>()
    var currentConstructionIsUserSet = false
    @Volatile
    var constructionQueue: List<String> = ArrayList(queueMaxSize)
        private set
    var productionOverflow = 0

    /** Maps cities by id to a set of the buildings they received (by nation equivalent name)
     *  Source: [UniqueType.GainFreeBuildings]
     */
    val freeBuildingsProvidedFromThisCity: HashMap<String, HashSet<String>> = hashMapOf()

    //endregion
    //region pure functions

    fun clone(): CityConstructions {
        val toReturn = CityConstructions()
        toReturn.builtBuildings.addAll(builtBuildings)
        toReturn.everBuilt.addAll(everBuilt)
        toReturn.investedBuildings.putAll(investedBuildings)
        toReturn.inProgressConstructions.putAll(inProgressConstructions)
        toReturn.currentConstructionIsUserSet = currentConstructionIsUserSet
        toReturn.constructionQueue = ArrayList(constructionQueue)
        toReturn.productionOverflow = productionOverflow
        toReturn.freeBuildingsProvidedFromThisCity.putAll(freeBuildingsProvidedFromThisCity.mapValues { it.value.toHashSet() })
        return toReturn
    }

    // Why is one of these called 'buildable' and the other 'constructable'?
    @Readonly
    internal fun getBuildableBuildings(): Sequence<Building> {
        val queue = constructionQueue // Read once, the sequence is evaluated later
        return city.getRuleset().buildings.values.asSequence().filter { it.isBuildable(this, queue) }
    }

    @Readonly
    fun getConstructableUnits(): Sequence<BaseUnit> {
        val queue = constructionQueue
        return city.getRuleset().units.values.asSequence().filter { it.isBuildable(this, queue) }
    }

    /**
     * @return [Stats] provided by all built buildings in city
     */
    @Readonly
    fun getStats(): StatTreeNode = timeThis("CityConstructions.getStats") {
        @LocalState val stats = StatTreeNode()
        for (building in getBuiltBuildings())
            stats.addStats(building.getStats(city), building.name)
        return stats
    }

    /**
     * @return Maintenance cost of all built buildings
     */
    @Readonly
    fun getMaintenanceCosts(): Float {
        var maintenanceCost = 0f
        val freeBuildings = city.civ.civConstructions.getFreeBuildingNames(city)

        val buildingMaintenanceUniques = city.getMatchingUniques(UniqueType.BuildingMaintenance)
            .filter { city.matchesFilter(it.params[2]) }.toList()

        for (building in getBuiltBuildings().filterNot { it.name in freeBuildings }) {
            var maintenanceForThisBuilding = building.maintenance.toFloat()
            for (unique in buildingMaintenanceUniques)
                if (building.matchesFilter(unique.params[1]))
                    maintenanceForThisBuilding *= unique.params[0].toPercent()

            maintenanceCost += maintenanceForThisBuilding
        }

        return maintenanceCost
    }

    @Readonly
    fun getProductionMarkup(ruleset: Ruleset): FormattedLine {
        val currentConstructionSnapshot = currentConstructionName()
        if (currentConstructionSnapshot.isEmpty()) return FormattedLine()
        val category = when {
            ruleset.buildings[currentConstructionSnapshot]?.isAnyWonder() == true ->
                CivilopediaCategories.Wonder.name
            currentConstructionSnapshot in ruleset.buildings ->
                CivilopediaCategories.Building.name
            currentConstructionSnapshot in ruleset.units ->
                CivilopediaCategories.Unit.name
            else -> ""
        }
        var label = "{$currentConstructionSnapshot}"
        if (!PerpetualConstruction.perpetualConstructionsMap.containsKey(currentConstructionSnapshot)) {
            val turnsLeft = turnsToConstruction(currentConstructionSnapshot)
            label += " - $turnsLeft${Fonts.turn}"
        }
        return if (category.isEmpty()) FormattedLine(label)
            else FormattedLine(label, link="$category/$currentConstructionSnapshot")
    }

    @Readonly fun getCurrentConstruction(): IConstruction = getConstruction( currentConstructionName())

    @Readonly fun isBuilt(buildingName: String): Boolean = builtBuildings.contains(buildingName)

    /** CvCity::IsBuildingInvestment */
    @Readonly fun isInvestedIn(buildingName: String): Boolean = investedBuildings.containsKey(buildingName)

    /** The production that an investment took off the cost of [building] (CvCity::GetBuildingCostInvestmentReduction), 0 if there is none or the ruleset has no investments */
    @Readonly
    fun getInvestmentReduction(building: Building): Int {
        if (investedBuildings.isEmpty() || !city.getRuleset().modOptions.constants.buildingInvestments) return 0
        return investedBuildings[building.name] ?: 0
    }

    // Note: There was a isEnqueued here functionally identical to isBeingConstructedOrEnqueued,
    // which was calling both isEnqueued and isBeingConstructed - BUT:  currentConstructionName() is just a
    // a wrapper for constructionQueue[0], so that was redundant. Also, isEnqueued was used nowhere,
    // and isBeingConstructed _only_ redundantly as described above.
    // `isEnqueuedForLater` is not optimal code as it can iterate the whole list where checking size
    // and first() would suffice, but the one current use in CityScreenConstructionMenu isn't critical.

    @Suppress("unused", "MemberVisibilityCanBePrivate")  // kept for illustration
    /** @return `true` if [constructionName] is the top queue entry, the one receiving production points */
    @Readonly fun isBeingConstructed(constructionName: String) = currentConstructionName() == constructionName
    /** @return `true` if [constructionName] is queued but not the top queue entry */
    @Readonly fun isEnqueuedForLater(constructionName: String) = constructionQueue.indexOf(constructionName) > 0
    /** @return `true` if [constructionName] is anywhere in the construction queue - [isBeingConstructed] **or** [isEnqueuedForLater] */
    @Readonly fun isBeingConstructedOrEnqueued(constructionName: String) = constructionQueue.contains(constructionName)

    @Readonly fun isQueueFull(): Boolean = constructionQueue.size >= queueMaxSize

    /** The queue without its first entry named [constructionName], or the current queue if there is none: what remains of the queue
     *  once that entry is bought or built, so it does not count against itself (see [INonPerpetualConstruction.getRejectionReasons]).
     *  Reads the field once. */
    @Readonly
    fun queueWithoutOneOf(constructionName: String): List<String> = queueWithoutOneOf(constructionQueue, constructionName)

    /** Same on a [queue] snapshot the caller took once */
    @Readonly
    fun queueWithoutOneOf(queue: List<String>, constructionName: String): List<String> {
        val index = queue.indexOf(constructionName)
        if (index < 0) return queue
        return queue.filterIndexed { i, _ -> i != index }
    }

    @Readonly
    fun isBuildingWonder(): Boolean {
        val currentConstruction = getCurrentConstruction()
        return currentConstruction is Building && currentConstruction.isWonder
    }

    @Readonly
    fun canBeHurried(): Boolean {
        val currentConstruction = getCurrentConstruction()
        return currentConstruction is INonPerpetualConstruction && !currentConstruction.hasUnique(UniqueType.CannotBeHurried)
    }

    /** If the city is constructing multiple units of the same type, subsequent units will require the full cost  */
    @Readonly
    fun isFirstConstructionOfItsKind(constructionQueueIndex: Int, name: String): Boolean =
        isFirstConstructionOfItsKind(constructionQueue, constructionQueueIndex, name)

    /** Same as above on a [queue] snapshot the caller took once - a caller walking the queue must not mix it with fresh reads of the field */
    @Readonly
    fun isFirstConstructionOfItsKind(queue: List<String>, constructionQueueIndex: Int, name: String): Boolean {
        // Simply compare index of first found [name] with given index
        return constructionQueueIndex == queue.indexOf(name)
    }


    @Readonly
    internal fun getConstruction(constructionName: String): IConstruction {
        val gameBasics = city.getRuleset()
        when {
            constructionName == "" -> return PerpetualConstruction.Idle
            gameBasics.buildings.containsKey(constructionName) -> return gameBasics.buildings[constructionName]!!
            gameBasics.units.containsKey(constructionName) -> return gameBasics.units[constructionName]!!
            else -> {
                val special = PerpetualConstruction.perpetualConstructionsMap[constructionName]
                if (special != null) return special
            }
        }

        class NotBuildingOrUnitException(message: String) : Exception(message)
        throw NotBuildingOrUnitException("$constructionName is not a building or a unit!")
    }

    @Readonly fun getBuiltBuildings(): Sequence<Building> = builtBuildingObjects.asSequence()

    @Readonly fun containsBuildingOrEquivalent(buildingNameOrUnique: String): Boolean =
            containedBuildingFiltersCache.getOrPut(buildingNameOrUnique) {
                isBuilt(buildingNameOrUnique)
                        || getBuiltBuildings().any { it.replaces == buildingNameOrUnique || it.hasTagUnique(buildingNameOrUnique, city.state) }
            }

    @Readonly
    fun getWorkDone(constructionName: String): Int {
        return if (inProgressConstructions.containsKey(constructionName)) inProgressConstructions[constructionName]!!
            else 0
    }

    @Readonly
    fun getRemainingWork(constructionName: String, useStoredProduction: Boolean = true): Int {
        val constr = getConstruction(constructionName)
        return when {
            constr is PerpetualConstruction -> 0
            useStoredProduction -> (constr as INonPerpetualConstruction).getProductionCost(city.civ, city) - getWorkDone(constructionName)
            else -> (constr as INonPerpetualConstruction).getProductionCost(city.civ, city)
        }
    }

    @Readonly
    fun turnsToConstruction(constructionName: String, useStoredProduction: Boolean = true): Int {
        val workLeft = getRemainingWork(constructionName, useStoredProduction)
        if (workLeft <= 0) // This most often happens when a production is more than finished in a multiplayer game while its not your turn
            return 0 // So we finish it at the start of the next turn. This could technically also happen when we lower production costs during our turn,
        // but distinguishing those two cases is difficult, and the second one is much rarer than the first
        if (workLeft <= productionOverflow) // if we already have stored up enough production to finish it directly
            return 1 // we'll finish this next turn

        return ceil((workLeft-productionOverflow) / productionForConstruction(constructionName).toDouble()).toInt()
    }

    @Readonly
    fun productionForConstruction(constructionName: String): Int = productionForConstructionExact(constructionName).roundToInt()

    /** What the city would produce per turn while building [constructionName], before the whole-number rounding of what is added each turn */
    @Readonly @Suppress("purity") // reads and fills the scoped cache, which the stats do not depend on
    fun productionForConstructionExact(constructionName: String): Float {
        if (currentConstructionName() == constructionName) return city.cityStats.currentCityStats.production
        val construction = city.cityConstructions.getConstruction(constructionName)
        val cache = productionCache
        if (cache == null || productionCacheOwner !== Thread.currentThread()) return productionWhileBuilding(construction)
        return cache.getOrPut(city.cityStats.constructionSignature(construction)) { productionWhileBuilding(construction) }
    }

    @Readonly
    private fun productionWhileBuilding(construction: IConstruction): Float {
        /*
        The ol' Switcharoo - what would our stats be if that was our current construction?
        Since this is only ever used for UI purposes, I feel fine with having it be a bit inefficient
        and recalculating the entire city stats
        We don't want to change our current construction queue - what if we have an empty queue,
         this can affect the city if we run it on another thread like in ConstructionsTable -
        So we run the numbers for the other construction
        ALSO apparently if we run on the actual cityStats from another thread,
          we get all sorts of fun concurrency problems when accessing various parts of the cityStats.
        SO, we create an entirely new CityStats and iterate there - problem solve!
        */
        @LocalState val cityStats = CityStats(city)
        cityStats.statsFromTiles = city.cityStats.statsFromTiles // take as-is
        cityStats.update(construction, updateTileStats = false, updateCivStats = false)
        return cityStats.currentCityStats.production
    }

    /**
     *  Runs [block] with the production [productionForConstructionExact] gives remembered per [CityStats.constructionSignature], for
     *  a caller that evaluates many constructions while nothing about the city or its civilization changes (the AI listing what to
     *  build): most buildings share a signature, so the full stats calculation runs once per signature, not once per construction.
     *  Only the calling thread uses the memory.
     */
    fun <T> withProductionCache(block: () -> T): T {
        if (productionCache != null) return block()
        productionCache = HashMap()
        productionCacheOwner = Thread.currentThread()
        try {
            return block()
        } finally {
            productionCache = null
            productionCacheOwner = null
        }
    }

    @Readonly
    fun cheapestStatBuilding(stat: Stat): Building? {
        val queue = constructionQueue
        return city.getRuleset().buildings.values.asSequence()
            .filter { !it.isAnyWonder() && it.isStatRelated(stat, city) &&
                (it.isBuildable(this, queue) || it.name in queue) }
            .minByOrNull { it.cost }
    }

    //endregion
    //region state changing functions

    fun setTransients() {
        everBuilt.addAll(builtBuildings)
        builtBuildingObjects = ArrayList(builtBuildings.map {
            city.getRuleset().buildings[it]
                    ?: throw java.lang.Exception("Building $it is not found!")
        })
        updateUniques(true)
    }

    fun addProductionPoints(productionToAdd: Int) {
        val constructionName = currentConstructionName()
        val construction = getConstruction(constructionName)
        if (construction is PerpetualConstruction) {
            productionOverflow += productionToAdd
            return
        }
        if (!inProgressConstructions.containsKey(constructionName))
            inProgressConstructions[constructionName] = 0
        inProgressConstructions[constructionName] = inProgressConstructions[constructionName]!! + productionToAdd
    }

    fun constructIfEnough():Unit = timeThis("constructIfEnough") {
        validateConstructionQueue()

        // Update InProgressConstructions for any available refunds
        validateInProgressConstructions()

        val constructionName = currentConstructionName()
        val construction = getConstruction(constructionName)
        if (construction is PerpetualConstruction) chooseNextConstruction() // check every turn if we could be doing something better, because this doesn't end by itself
        else {
            val productionCost = (construction as INonPerpetualConstruction).getProductionCost(city.civ, city)
            if (inProgressConstructions.containsKey(constructionName)
                    && inProgressConstructions[constructionName]!! >= productionCost) {
                val potentialOverflow = inProgressConstructions[constructionName]!! - productionCost
                if (completeConstruction(construction)) {
                    // See the URL below for explanation for this cap
                    // https://forums.civfanatics.com/threads/hammer-overflow.419352/
                    val maxOverflow = maxOf(productionCost, city.cityStats.currentCityStats.production.roundToInt())
                    productionOverflow = min(maxOverflow, potentialOverflow)
                    // CvCity::popOrder: the part above the cap is converted to gold at MAXED_UNIT_GOLD_PERCENT or MAXED_BUILDING_GOLD_PERCENT
                    val lostProduction = potentialOverflow - productionOverflow
                    val lostPercent = maxedGoldPercent(construction)
                    if (lostProduction > 0 && lostPercent > 0) city.civ.addGold(lostProduction * lostPercent / 100)
                }
                else {
                    city.civ.addNotification("No space available to place [${construction.name}] near [${city.name}]",
                        city.location, NotificationCategory.Production, construction.name)
                }
                city.civ.civConstructions.builtItemsWithIncreasingCost[construction.name] += 1
            }
        }
    }

    fun endTurn(cityStats: Stats) {
        validateConstructionQueue()
        validateInProgressConstructions()

        val currentName = currentConstructionName() // Once, this is one step
        val currentConstruction = getConstruction(currentName)
        if (currentConstruction is PerpetualConstruction.Project) {
            // A League project: the production, with what was stored, is the contribution of the civilization (modelled on a Process: CvCity::doProcess adds the city's production to the stockpile, V:CvCity.cpp:31995-32007)
            city.civ.gameInfo.league?.addContribution(city.civ, currentName, cityStats.production.roundToInt() + productionOverflow)
            productionOverflow = 0
        } else if (currentConstruction !is PerpetualConstruction) {
            if (getWorkDone(currentName) == 0) {
                constructionBegun(currentConstruction)
            }
            addProductionPoints(cityStats.production.roundToInt() + productionOverflow)
            productionOverflow = 0
        }
    }


    private fun validateConstructionQueue() {
        // Validate a snapshot without holding a lock (it asks many questions), and publish the result only if nobody
        // replaced the queue meanwhile - otherwise their change would be lost, so validate the new queue instead.
        while (true) {
            val queueSnapshot = constructionQueue
            val validatedQueue = validatedCopyOf(queueSnapshot)
            val published = synchronized(this) {
                if (constructionQueue !== queueSnapshot) false
                else { constructionQueue = validatedQueue; true }
            }
            if (published) break
        }
        chooseNextConstruction()
        validateCreatesOneImprovementMarkers()
    }

    private fun validatedCopyOf(queueSnapshot: List<String>): List<String> {
        // Build into a new list and swap it in at the end, so concurrent readers never see an empty queue
        val validatedQueue = ArrayList<String>(queueMaxSize)

        for (constructionName in queueSnapshot) {
            val construction = getConstruction(constructionName)
            // First construction will be built next turn, we need to make sure it has the correct resources
            if (validatedQueue.isEmpty() && getWorkDone(constructionName) == 0) {
                val stockpileCosts = construction.getStockpiledResourceRequirements(city.state)
                val civResources = city.civ.getCivResourcesByName()

                if (stockpileCosts.any { (resourceName, amount) ->
                            civResources[resourceName] == null
                                    || amount > civResources[resourceName]!! }) {
                    if (construction is Building)
                        removeImprovementForBuilding(construction)
                    continue // Removes this construction from the queue
                }
            }
            val buildable = if (construction is INonPerpetualConstruction)
                construction.isBuildable(this, validatedQueue) // Count only the entries before this one, not the entry itself
            else construction.isBuildable(this)
            if (buildable)
                validatedQueue.add(constructionName)
            else if (construction is Building)
                removeImprovementForBuilding(construction)
        }
        return validatedQueue
    }

    /** Remove orphaned [UniqueType.CreatesOneImprovement] markers whose queue entry was removed elsewhere. */
    private fun validateCreatesOneImprovementMarkers() {
        val markedTiles = city.getTiles()
            .filter { it.isMarkedForCreatesOneImprovement() }
            .toList()
        if (markedTiles.isEmpty()) return

        val improvementsInQueue = constructionQueue.asSequence()
            .mapNotNull { getConstruction(it) as? Building }
            .mapNotNullTo(hashSetOf()) { it.getImprovementToCreate(city.getRuleset(), city.civ)?.name }

        for (tile in markedTiles) {
            val improvementInProgress = checkNotNull(tile.improvementInProgress) {
                "Tile ${tile.position} is marked for ${UniqueType.CreatesOneImprovement.name} without an improvement in progress"
            }
            if (improvementInProgress !in improvementsInQueue)
                tile.improvementFunctions.removeCreatesOneImprovementMarker(removeConstruction = false)
        }
    }

    fun validateInProgressConstructions() {
        // remove obsolete stuff from in progress constructions - happens often and leaves clutter in memory and save files
        // should have little visible consequences - any accumulated points that may be reused later should stay (nukes when manhattan project city lost, nat wonder when conquered an empty city...), all other points should be refunded
        // Should at least be called before each turn - if another civ completes a wonder after our previous turn, we should get the refund this turn
        val queue = constructionQueue // Once for the whole pass
        val currentName = queue.firstOrNull() ?: ""
        val inProgressSnapshot = inProgressConstructions.keys.filter { it != currentName }
        for (constructionName in inProgressSnapshot) {
            val construction = getConstruction(constructionName)
            // Perpetual constructions should always still be valid (I hope)
            if (construction is PerpetualConstruction) continue

            // A queued entry must not count against itself when it is checked for MaxNumberBuildable
            val rejectionReasons = (construction as INonPerpetualConstruction)
                .getRejectionReasons(this, queueWithoutOneOf(queue, constructionName))

            if (!rejectionReasons.any { it.hasAReasonToBeRemovedFromQueue() }) continue

            val workDone = getWorkDone(constructionName)
            // CvCity::doCheckProduction: production put into a unit or non-wonder building of which the limit is reached is converted to gold
            // (wonders are handled below, at 100%)
            if (workDone > 0 && !(construction is Building && construction.isWonder)
                && rejectionReasons.any { it.type == RejectionReasonType.MaxNumberBuildable }) {
                val gold = workDone * maxedGoldPercent(construction) / 100
                if (gold > 0) {
                    city.civ.addGold(gold)
                    val icon = if (construction is Building) "BuildingIcons/$constructionName" else "UnitIcons/$constructionName"
                    city.civ.addNotification(
                        "Excess production for [$constructionName] converted to [$gold] gold",
                        city.location, NotificationCategory.Production, NotificationIcon.Gold, icon)
                }
            }
            if (construction is Building) {
                // Production put into wonders gets refunded
                if (construction.isWonder && workDone != 0) {
                    // CvCity::doTurn: beaten to a world wonder: the diplomacy AI blames the major that has it
                    if (rejectionReasons.any { it.type == RejectionReasonType.WonderAlreadyBuilt }) {
                        val builder = city.civ.gameInfo.civilizations.firstOrNull { civ ->
                            civ.isMajorCiv && civ.cities.any { it.cityConstructions.isBuilt(constructionName) } }
                        if (builder != null) DiplomacyEvents.onWonderBeaten(city.civ, builder)
                    }
                    city.civ.addGold(workDone)
                    city.civ.addNotification(
                        "Excess production for [$constructionName] converted to [$workDone] gold",
                        city.location,
                        NotificationCategory.Production,
                        NotificationIcon.Gold, "BuildingIcons/${constructionName}")
                }
                removeImprovementForBuilding(construction)

            } else if (construction is BaseUnit) {
                // Production put into upgradable units gets put into upgraded version
                val cheapestUpgradeUnit = construction.getRulesetUpgradeUnits(city.state)
                    .map { city.civ.getEquivalentUnit(it) }
                    .filter { it.isBuildable(this, queue) }
                    .minByOrNull { it.cost }
                if (rejectionReasons.all { it.type == RejectionReasonType.Obsoleted } && cheapestUpgradeUnit != null) {
                    inProgressConstructions[cheapestUpgradeUnit.name] = (inProgressConstructions[cheapestUpgradeUnit.name] ?: 0) + workDone
                }
            }
            inProgressConstructions.remove(constructionName)
        }
    }

    /** The percentage of production that is converted to gold when a unit or building cannot be completed, see ModConstants.maxedUnitGoldPercent */
    private fun maxedGoldPercent(construction: INonPerpetualConstruction): Int {
        val constants = city.getRuleset().modOptions.constants
        return if (construction is Building) constants.maxedBuildingGoldPercent else constants.maxedUnitGoldPercent
    }

    private fun removeImprovementForBuilding(building: Building){
        val improvementToCreate = building.getImprovementToCreate(city.getRuleset(), city.civ) ?: return
        val tile = city.getTiles().firstOrNull { it.isMarkedForCreatesOneImprovement(improvementToCreate.name) }
        tile?.improvementFunctions?.removeCreatesOneImprovementMarker(removeConstruction = false)
    }

    private fun constructionBegun(construction: IConstruction) {
        for ((resourceName, amount) in construction.getStockpiledResourceRequirements(city.state)) {
            val resource = city.civ.gameInfo.ruleset.tileResources[resourceName] ?: continue
            city.gainStockpiledResource(resource, -amount)
        }

        if (construction !is INonPerpetualConstruction) return
        if (!construction.hasUnique(UniqueType.TriggersAlertOnStart)) return
        val icon = if (construction is Building) "BuildingIcons/${construction.name}" else "UnitIcons/${construction.name}"
        for (otherCiv in city.civ.gameInfo.civilizations) {
            if (otherCiv == city.civ) continue
            when {
                otherCiv.hasExplored(city.getCenterTile()) ->
                    otherCiv.addNotification("The city of [${city.name}] has started constructing [${construction.name}]!",
                        city.location, NotificationCategory.General, NotificationIcon.Construction, icon)
                otherCiv.knows(city.civ) ->
                    otherCiv.addNotification("[${city.civ.civName}] has started constructing [${construction.name}]!",
                        NotificationCategory.General, NotificationIcon.Construction, icon)
                else -> otherCiv.addNotification("[An unknown civilization] has started constructing [${construction.name}]!",
                    NotificationCategory.General, NotificationIcon.Construction, icon)
            }
        }
    }

    /** Returns false if we tried to construct a unit but it has nowhere to go */
    fun completeConstruction(construction: INonPerpetualConstruction): Boolean {
        var unit: MapUnit? = null
        if (construction is Building) construction.construct(this)
        else if (construction is BaseUnit) {
            unit = construction.construct(this, null)
                ?: return false // unable to place unit

            /* check if it's true that we should load saved promotion for the unitType,
               Then check if the player want to rebuild the unit the saved promotion,
               and do a null check.
               and finally check if the current unit has enough XP. */
            val possiblePromotions = hashSetOf<String>()

            // to get promotion in order from nodes
            val prmotionTreeOrder = mutableSetOf<String>()

            /* Added all the possible Prmotion that the unit can be promoted,
               to avoid edge case where upgrading a scout to spearman
               whould give the rest of the spearman the "ignore terrain cost" promotion
               when built with auto promotion. */
            for (promotion in PromotionTree(unit).possiblePromotions) possiblePromotions.add(promotion.name)
            for (roots in PromotionTree(unit).allNodes()) prmotionTreeOrder.add(roots.promotion.name)

            val savedPromotion = city.unitToPromotions[unit.baseUnit.name]

            if (city.unitShouldUseSavedPromotion[unit.baseUnit.name] == true &&
                savedPromotion != null && unit.promotions.XP >= savedPromotion.XP) {
                // this variable is the filted promotion from savedPrmotion to only get promotion that are possible for this unit.
                val possiblePromotionFilted =  savedPromotion.promotions.filter { it in possiblePromotions }
                // sort in order to avoid getting Accuracy III before Accuracy I
                for (promotions in prmotionTreeOrder) {
                    // check if here is enough XP for the next promotion
                    if (unit.promotions.XP-unit.promotions.xpForNextPromotion() >= 0) {
                        if (promotions in possiblePromotionFilted) unit.promotions.addPromotion(promotions)
                    } else {
                        break
                    }
                }
            }
        }

        if (construction.name in inProgressConstructions)
            inProgressConstructions.remove(construction.name)
        if (construction.name == currentConstructionName())
            removeCurrentConstruction()

        validateConstructionQueue() // if we've built e.g. the Great Lighthouse, then Lighthouse is no longer relevant in the queue

        construction as IRulesetObject // Always OK for INonPerpetualConstruction, but compiler doesn't know

        val buildingIcon = "BuildingIcons/${construction.name}"
        val pediaAction = CivilopediaAction(construction.makeLink())
        val locationAction = if (construction is BaseUnit) MapUnitAction(unit!!)
            else LocationAction(city.location)
        val locationAndPediaActions = listOf(locationAction, pediaAction)

        if (construction is Building && construction.isWonder) {
            city.civ.popupAlerts.add(PopupAlert(AlertType.WonderBuilt, construction.name))
            DiplomacyEvents.onWonderConstructed(city.civ)
            // CvCity::processBuilding: a world wonder built, not given, is a historic event (V:CvCity.cpp:13312-13319)
            city.civ.culture.onHistoricEvent(HistoricEvent.WorldWonder)
            for (civ in city.civ.gameInfo.civilizations) {
                if (civ.hasExplored(city.getCenterTile()))
                    civ.addNotification("[${construction.name}] has been built in [${city.name}]",
                        locationAndPediaActions,
                        if (civ == city.civ) NotificationCategory.Production else NotificationCategory.General, buildingIcon)
                else
                    civ.addNotification("[${construction.name}] has been built in a faraway land",
                        pediaAction, NotificationCategory.General, buildingIcon)
            }
        } else {
            val icon = if (construction is Building) buildingIcon else construction.name // could be a unit, in which case take the unit name.
            city.civ.addNotification(
                "[${construction.name}] has been built in [${city.name}]",
                locationAndPediaActions, NotificationCategory.Production, NotificationIcon.Construction, icon)
        }

        if (construction.hasUnique(UniqueType.TriggersAlertOnCompletion, city.state)) {
            for (otherCiv in city.civ.gameInfo.civilizations) {
                // No need to notify ourself, since we already got the building notification anyway
                if (otherCiv == city.civ) continue
                val completingCivDescription =
                    if (otherCiv.knows(city.civ)) "[${city.civ.civName}]" else "[An unknown civilization]"
                otherCiv.addNotification("$completingCivDescription has completed [${construction.name}]!",
                    pediaAction, NotificationCategory.General, NotificationIcon.Construction, buildingIcon)
            }
        }

        // CvCity::produce(UnitTypes): INSTANT_YIELD_TYPE_U_PROD, with the unit's production cost
        if (construction is BaseUnit)
            InstantYields.fire(UniqueType.TriggerUponCompletingUnit, city.civ, city, construction.getProductionCost(city.civ, city), city.state,
                "due to completing a [${construction.name}]",
                triggerFilter = { construction.matchesFilter(it.params[0], city.state) })
        return true
    }

    fun addBuilding(buildingName: String) {
        val building = city.getRuleset().buildings[buildingName]!!
        addBuilding(building)
    }

    fun addBuilding(building: Building,
                    /** False when creating initial buildings in city - so we don't "waste" a free building on a building we're going to get anyway, from settler buildings */
                    tryAddFreeBuildings: Boolean = true) {
        val buildingName = building.name
        val civ = city.civ

        if (building.cityHealth > 0) {
            // VP CvCity::ChangeExtraHitPoints preserves absolute damage when maximum HP changes.
            city.health += if (civ.gameInfo.ruleset.modOptions.constants.cityCombatLikeVp) building.cityHealth
            else (building.cityHealth.toFloat() * city.health.toFloat() / city.getMaxHealth().toFloat()).toInt()
        }
        builtBuildingObjects = builtBuildingObjects.withItem(building)
        builtBuildings.add(buildingName)
        investedBuildings.remove(buildingName) // finished: what it cost is not read any more, and a sold building starts without (CvCity::SetBuildingInvestment)
        val firstTimeInCity = everBuilt.add(buildingName)
        if (building.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts, GameContext.IgnoreConditionals))
            civ.ai.apolloProgramBuilt = true

        updateUniques()
        applyRetroactivePromotions(building)

        /** Support for [UniqueType.CreatesOneImprovement] */
        applyCreateOneImprovement(building)

        triggerNewBuildingUniques(building, firstTimeInCity)

        if (building.hasUnique(UniqueType.EnemyUnitsSpendExtraMovement))
            civ.cache.updateHasActiveEnemyMovementPenalty()

        /**
         * The [Korean unique](https://civilization.fandom.com/wiki/Korean_(Civ5)#Strategy) gives the same tech boost as a [research agreement](https://civilization.fandom.com/wiki/Diplomacy_(Civ5)#Research_Agreement).
         * We use Vanilla / G&K logic as it is more straightforward, i.e. half of the median cost of our researchable techs.
         * It is unclear whether RA modifiers should apply. For now, they do not.
         */
        fun applyKoreanUnique() {
            if (!building.isStatRelated(Stat.Science, city)) return
            if (!civ.hasUnique(UniqueType.TechBoostWhenScientificBuildingsBuiltInCapital)) return
            if (!city.isCapital()) return
            val availableTechCosts = city.getRuleset().technologies.values
                .filter { civ.tech.canBeResearched(it.name) }
                .map { civ.tech.costOfTech(it.name) }
                .sorted()
            if (availableTechCosts.isEmpty()) return
            val n = availableTechCosts.size
            val medianCost =
                if (n % 2 == 1) availableTechCosts[n / 2].toFloat()
                else (availableTechCosts[n / 2 - 1] + availableTechCosts[n / 2]) / 2f
            val techBoost = (0.5f * medianCost).roundToInt()
            civ.tech.addScience(techBoost)
        }
        
        applyKoreanUnique()

        val previousHappiness = civ.getHappiness()
        // can cause civ happiness update: reassignPopulationDeferred -> reassignPopulation -> cityStats.update -> civ.updateHappiness
        city.reassignPopulationDeferred()
        val newHappiness = civ.getHappiness()

        /** Same check as [com.unciv.logic.civilization.Civilization.updateStatsForNextTurn] -
         *   but that triggers *stat calculation* whereas this is for *population assignment* */
        if (previousHappiness != newHappiness && (city.civ.gameInfo.ruleset.hasUniquesScalingWithHappiness
                || city.civ.gameInfo.ruleset.allHappinessLevelsThatAffectUniques.any { newHappiness < it != previousHappiness < it }))
            city.civ.cities.filter { it != city }.forEach { it.reassignPopulationDeferred() }

        if (tryAddFreeBuildings)
            city.civ.civConstructions.tryAddFreeBuildings()
    }

    /**
     *  What a new [building] does: its own uniques that have no trigger, and the uniques whose trigger is `upon constructing` it ([InstantYields.fire]: those of the
     *  buildings of this city that say `in this city`, of its religion, and of the civilization). The instant yields among them ([InstantYields.isGain]) are only given
     *  the first time the building is built in the city ([firstTimeInCity]) and not in a city in resistance or being razed (CvCity::processBuilding,
     *  V:CvCity.cpp:14830-14853, which also leaves out a building bought with gold, `IsPurchased`, that no caller sets); a free unit or population is still given.
     */
    fun triggerNewBuildingUniques(building: Building, firstTimeInCity: Boolean = true) {
        val stateForConditionals = city.state
        val triggerNotificationText ="due to constructing [${building.name}]"
        val givesYields = firstTimeInCity && !city.isInResistance() && !city.isBeingRazed
        val allowed = { unique: Unique -> givesYields || !InstantYields.isGain(unique) }

        for (unique in building.uniqueObjects) {
            if (unique.hasTriggerConditional() || !unique.conditionalsApply(stateForConditionals) || !allowed(unique)) continue
            repeat(unique.getUniqueMultiplier(city.state)) {
                UniqueTriggerActivation.triggerUnique(unique, city, triggerNotificationText = triggerNotificationText)
            }
        }

        InstantYields.fire(UniqueType.TriggerUponConstructingBuilding, city.civ, city, 0, stateForConditionals, triggerNotificationText,
            triggerFilter = { building.matchesFilter(it.params[0], stateForConditionals) }, effectFilter = allowed)

        InstantYields.fire(UniqueType.TriggerUponConstructingBuildingCityFilter, city.civ, city, 0, stateForConditionals, triggerNotificationText,
            triggerFilter = { building.matchesFilter(it.params[0], stateForConditionals) && city.matchesFilter(it.params[1]) }, effectFilter = allowed)
    }

    /** VP CvCity::processBuilding and SetRetroactivePromotion: grant, including when the building is removed. */
    private fun applyRetroactivePromotions(building: Building) {
        if (!city.civ.gameInfo.ruleset.modOptions.constants.retroactiveCityPromotionsLikeVp) return
        for (unique in building.getMatchingUniques(UniqueType.UnitStartingPromotions, city.state)) {
            if (!city.matchesFilter(unique.params[1])) continue
            val promotion = city.getRuleset().unitPromotions[unique.params[2]] ?: continue
            for (unit in city.civ.units.getCivUnits()) {
                val origin = unit.getOriginCity()
                if (origin != city && (origin != null || !city.isCapital())) continue
                val filter = unique.params[0]
                if (!unit.promotions.isApplicableToUnit(promotion) ||
                    filter != "relevant" && !unit.matchesFilter(filter)) continue
                unit.promotions.addPromotion(promotion.name, isFree = true)
            }
        }
    }

    fun removeBuilding(buildingName: String) {
        val buildingObject = city.getRuleset().buildings[buildingName]
        if (buildingObject != null) removeBuilding(buildingObject)
    }

    fun removeBuilding(building: Building) {
        if (building in builtBuildingObjects) applyRetroactivePromotions(building)
        if (city.civ.gameInfo.ruleset.modOptions.constants.cityCombatLikeVp && building in builtBuildingObjects)
            city.health = (city.health - building.cityHealth).coerceAtLeast(1)
        builtBuildingObjects = builtBuildingObjects.withoutItem(building)
        builtBuildings.remove(building.name)
        // CvCityBuildings::SetNumRealBuildingTimed: a building that is gone takes its investment with it
        investedBuildings.remove(building.name)
        city.greatWorks.onBuildingRemoved(building)
        
        if (building.hasCreateOneImprovementUnique()){
            val improvement = building.getImprovementToCreate(city.getRuleset(), city.civ)!!
            val tileWithImprovementToRemove = city.getTiles().firstOrNull { it.tileImprovement == improvement }
            tileWithImprovementToRemove?.removeImprovement()
        }
        
        updateUniques()
    }

    private fun updateUniques(onLoadGame: Boolean = false) {
        builtBuildingUniqueMap.clear()
        containedBuildingFiltersCache.clear()

        for (building in getBuiltBuildings())
            builtBuildingUniqueMap.addUniques(building.uniqueObjects)
        if (!onLoadGame) {
            city.civ.cache.updateCitiesConnectedToCapital(false) // could be a connecting building, like a harbor
            city.cityStats.update()
            city.civ.cache.updateCivResources()
        }
    }

    /**
     *  Purchase a construction for gold (or another stat)
     *  called from NextTurnAutomation and the City UI
     *  Build / place the new item, deduct cost, and maintain queue.
     *
     *  @param constructionName What to buy (needed since buying something not queued is allowed)
     *  @param queuePosition    Position in the queue or -1 if not from queue
     *                          Note: -1 does not guarantee queue will remain unchanged (validation)
     *  @param automatic        Flag whether automation should try to choose what next to build (not coming from UI)
     *                          Note: settings.autoAssignCityProduction is handled later
     *  @param stat             Stat object of the stat with which was paid for the construction
     *  @param tile             Supports [UniqueType.CreatesOneImprovement] the tile to place the improvement from that unique on.
     *                          Ignored when the [constructionName] does not have that unique. If null and the building has the unique, a tile is chosen automatically.
     *  @return                 Success (false e.g. unit cannot be placed)
     */
    fun purchaseConstruction(
        constructionName: String,
        queuePosition: Int,
        automatic: Boolean,
        stat: Stat = Stat.Gold,
        tile: Tile? = null
    ): Boolean {
        val construction = getConstruction(constructionName) as? INonPerpetualConstruction ?: return false
        return purchaseConstruction(construction, queuePosition, automatic, stat, tile)
    }

    /**
     *  Purchase a construction for gold (or another stat)
     *  called from NextTurnAutomation and the City UI
     *  Build / place the new item, deduct cost, and maintain queue.
     *
     *  @param construction What to buy (needed since buying something not queued is allowed)
     *  @param queuePosition    Position in the queue or -1 if not from queue
     *                          Note: -1 does not guarantee queue will remain unchanged (validation)
     *  @param automatic        Flag whether automation should try to choose what next to build (not coming from UI)
     *                          Note: settings.autoAssignCityProduction is handled later
     *  @param stat             Stat object of the stat with which was paid for the construction
     *  @param tile             Supports [UniqueType.CreatesOneImprovement] the tile to place the improvement from that unique on.
     *                          Ignored when the [constructionName] does not have that unique. If null and the building has the unique, a tile is chosen automatically.
     *  @param useToSatisfyOperation Passed to [BaseUnit.construct]
     *  @return                 Success (false e.g. unit cannot be placed)
     */
    fun purchaseConstruction(
        construction: INonPerpetualConstruction,
        queuePosition: Int,
        automatic: Boolean,
        stat: Stat = Stat.Gold,
        tile: Tile? = null,
        useToSatisfyOperation: Boolean = true
    ): Boolean {
        // Support UniqueType.CreatesOneImprovement: it is active when getImprovementToCreate returns an improvement
        val improvementToPlace = (construction as? Building)?.getImprovementToCreate(city.getRuleset(), city.civ)
        if (improvementToPlace != null) {
            // If active without a predetermined tile to place the improvement on, automate a tile
            val finalTile = tile
                ?: Automation.getTileForConstructionImprovement(city, improvementToPlace)
                ?: return false // This was never reached in testing
            if (!tryPlaceCreateOneImprovementMarker(improvementToPlace, finalTile)) return false
            // postBuildEvent does the rest by calling cityConstructions.applyCreateOneImprovement
        }

        // CvCity::PurchaseBuilding with MOD_BALANCE_BUILDING_INVESTMENTS: the gold is an investment in the building, which stays in the queue (V:CvCity.cpp:31308-31321)
        if (construction is Building && stat == Stat.Gold && city.getRuleset().modOptions.constants.buildingInvestments)
            return investInBuilding(construction)

        if (construction is Building) construction.construct(this)
        else if (construction is BaseUnit) {
            construction.construct(this, stat, useToSatisfyOperation)
                ?: return false  // nothing built - no pay
        }

        if (!city.civ.gameInfo.gameParameters.godMode) {
            val constructionCost = construction.getStatBuyCost(city, stat)
                ?: return false // We should never end up here anyway, so things have already gone _way_ wrong
            city.addStat(stat, -1 * constructionCost)

            val conditionalState = city.state

            if ((
                    city.civ.getMatchingUniques(UniqueType.BuyUnitsIncreasingCost, conditionalState) +
                    city.civ.getMatchingUniques(UniqueType.BuyBuildingsIncreasingCost, conditionalState)
                ).any {
                    (
                        construction is BaseUnit && construction.matchesFilter(it.params[0], conditionalState) ||
                        construction is Building && construction.matchesFilter(it.params[0], conditionalState)
                    )
                    && city.matchesFilter(it.params[3])
                    && it.params[2] == stat.name
                }
            ) {
                city.civ.civConstructions.boughtItemsWithIncreasingPrice.add(construction.name, 1)
            }

            // Consume stockpiled resources - usually consumed when construction starts, but not when bought
            if (getWorkDone(construction.name) == 0){ // we didn't pay the resources when we started building
                for ((resourceName, amount) in construction.getStockpiledResourceRequirements(conditionalState)) {
                    val resource = city.civ.gameInfo.ruleset.tileResources[resourceName] ?: continue
                    city.gainStockpiledResource(resource, -amount)
                }
            }

            fireBuyingYields(construction, stat, constructionCost)
        }

        // CvCity::PurchaseUnit (V:CvCity.cpp:31100, 31121-31135): a bought unit sets the cooldowns of its kind
        if (construction is BaseUnit) PurchaseRules.afterPurchase(city, construction, stat)

        if (queuePosition >= 0) removeFromQueue(queuePosition, automatic)
        validateConstructionQueue()

        // A purchase should never leave the city idle if we invalidated or emptied the queue
        if (isQueueEmptyOrIdle()) chooseNextConstruction()

        return true
    }


    /** CvCity::Purchase: INSTANT_YIELD_TYPE_PURCHASE and ..._FAITH_PURCHASE, with the price paid */
    private fun fireBuyingYields(construction: INonPerpetualConstruction, stat: Stat, price: Int) {
        if (price > 0)
            InstantYields.fire(UniqueType.TriggerUponBuying, city.civ, city, price, city.state,
                "due to buying [${construction.name}]", triggerFilter = { it.params[0] == stat.name })
    }

    /**
     *  Pays for an investment in [building] ([com.unciv.models.ModConstants.buildingInvestments]): the building is not built, but its cost falls
     *  ([PurchaseRules.investmentReduction]) and it is put at the front of the queue if it is not in it (CvCity::PurchaseBuilding, V:CvCity.cpp:31302-31321).
     *  The price is the one before the investment, as the source reads it first.
     */
    private fun investInBuilding(building: Building): Boolean {
        var price = 0
        if (!city.civ.gameInfo.gameParameters.godMode) {
            price = building.getStatBuyCost(city, Stat.Gold) ?: return false
            city.addStat(Stat.Gold, -price)
        }
        investedBuildings[building.name] = PurchaseRules.investmentReduction(city, building)
        if (!isBeingConstructedOrEnqueued(building.name)) {
            editQueue { add(0, building.name) }
            currentConstructionIsUserSet = true
        }
        fireBuyingYields(building, Stat.Gold, price)
        return true
    }

    /** This is the *one true test* of "can we buy this construction"
     * This tests whether the buy button should be _enabled_ */
    @Readonly
    fun isConstructionPurchaseAllowed(construction: INonPerpetualConstruction, stat: Stat, constructionBuyCost: Int): Boolean {
        return when {
            city.isPuppet && !city.getMatchingUniques(UniqueType.MayBuyConstructionsInPuppets).any() -> false
            city.isInResistance() -> false
            !construction.isPurchasable(city.cityConstructions) -> false    // checks via 'rejection reason'
            construction is BaseUnit && !city.canPlaceNewUnit(construction) -> false
            !construction.canBePurchasedWithStat(city, stat) -> false
            PurchaseRules.isBlocked(city, construction, stat) -> false // investments, cooldowns, the building a unit needs
            city.civ.gameInfo.gameParameters.godMode -> true
            constructionBuyCost == 0 -> true
            else -> city.getStatReserve(stat) >= constructionBuyCost
        }
    }

    @Readonly
    fun isConstructionPurchaseBlockedByUnit(construction: INonPerpetualConstruction): Boolean {
        return !city.isPuppet && !city.getMatchingUniques(UniqueType.MayBuyConstructionsInPuppets)
            .any() &&
            !city.isInResistance() &&
            construction.isPurchasable(city.cityConstructions) &&
            (construction is BaseUnit) && !city.canPlaceNewUnit(construction)
    }

    private fun removeCurrentConstruction() = removeFromQueue(0, true)

    fun chooseNextConstruction():Unit = timeThis("chooseNextConstruction") {
        val currentName = currentConstructionName()
        if (currentName.isNotEmpty() && currentName != PerpetualConstruction.Idle.name) { // the queue is neither empty nor idle
            // If the USER set a perpetual construction, then keep it!
            if (getConstruction(currentName) !is PerpetualConstruction || currentConstructionIsUserSet) return
        }

        val isCurrentPlayersTurn = city.civ.gameInfo.isUsersTurn()
                || !city.civ.gameInfo.gameParameters.isOnlineMultiplayer
        if ((isCurrentPlayersTurn && (UncivGame.Current.settings.autoAssignCityProduction
                || UncivGame.Current.worldScreen?.autoPlay?.isAutoPlayingAndFullAutoPlayAI() == true)) // only automate if the active human player has the setting to automate production
                || city.civ.isAI() || city.isPuppet) {
            ConstructionAutomation(this).chooseNextConstruction()
        }

        /** Support for [UniqueType.CreatesOneImprovement] - if an Improvement-creating Building was auto-queued, auto-choose a tile: */
        val building = getCurrentConstruction() as? Building ?: return
        val improvement = building.getImprovementToCreate(city.getRuleset(), city.civ) ?: return
        
        if (getTileForImprovement(improvement.name) == null) {
            val newTile = Automation.getTileForConstructionImprovement(city, improvement) ?: return
            tryPlaceCreateOneImprovementMarker(improvement, newTile)
        }
    }

    /** Whether this city may mark its own [tile] to create [improvement] when construction completes. */
    @Readonly
    fun canPlaceCreateOneImprovementOn(improvement: TileImprovement, tile: Tile): Boolean =
        tile.getCity() == city
            && tile in city.tilesInRange
            && !tile.isCityCenter()
            && !tile.isMarkedForCreatesOneImprovement()
            && tile.improvementFunctions.canBuildImprovement(improvement, city.state)

    /**
     * Try to mark [tile] so completing this construction will create [improvement] there.
     *
     * The tile must already belong to this city; reaching marker placement with another
     * city's tile is invalid internal state and fails loudly.
     *
     * @return `true` if [tile] is now marked for [improvement], or `false` if it is not eligible.
     */
    fun tryPlaceCreateOneImprovementMarker(improvement: TileImprovement, tile: Tile): Boolean {
        if (tile.getCity() == city && tile.isMarkedForCreatesOneImprovement(improvement.name))
            return true
        check(tile.getCity() == city) {
            "Cannot mark ${tile.position} for ${UniqueType.CreatesOneImprovement.name} in ${city.name}: tile is owned by ${tile.getCity()?.name ?: "no city"}"
        }
        if (!canPlaceCreateOneImprovementOn(improvement, tile))
            return false

        tile.improvementFunctions.markForCreatesOneImprovement(improvement.name)
        return true
    }

    @Readonly
    fun canAddToQueue(construction: IConstruction, queue: List<String> = constructionQueue): Boolean {
        // [queue] is a snapshot: by default taken here, otherwise by a caller that checks several things in a row
        if (queue.size >= queueMaxSize) return false
        if (construction is Building && queue.contains(construction.name)) return false
        // For limited constructions this counts everything already queued, in this city too - the queue is passed explicitly
        return if (construction is INonPerpetualConstruction) construction.isBuildable(this, queue)
            else construction.isBuildable(this)
    }

    private fun isLastConstructionPerpetual(): Boolean {
        val queue = constructionQueue
        // `getConstruction(queue.last()) is PerpetualConstruction` is clear but more expensive
        return queue.isNotEmpty() && PerpetualConstruction.isNamePerpetual(queue.last())
    }

    @Readonly fun isQueueEmptyOrIdle() = currentConstructionName().isEmpty()
        ||  currentConstructionName() == PerpetualConstruction.Idle.name

    /** Add [construction] to the end or top (controlled by [addToTop]) of the queue with all checks (does nothing if not possible)
     *
     *  @param tile Supports [UniqueType.CreatesOneImprovement] the tile to place the improvement from that unique on.
     *      Required when adding such a building without an existing marker.
     *  Note: Overload with string parameter `constructionName` exists as well.
     */
    @Synchronized
    fun addToQueue(construction: IConstruction, addToTop: Boolean = false, tile: Tile? = null) {
        if (!canAddToQueue(construction)) return
        markTileForCreatesOneImprovement(construction, tile)
        val constructionName = construction.name
        when {
            isQueueEmptyOrIdle() ->
                 setCurrentConstruction(constructionName)
            addToTop && construction is PerpetualConstruction && PerpetualConstruction.isNamePerpetual( currentConstructionName()) ->
                 setCurrentConstruction(constructionName) // perpetual constructions will replace each other
            addToTop ->
                editQueue { add(0, constructionName) }
            isLastConstructionPerpetual() -> {
                // Note this also works if  currentConstructionName() is perpetual and the only entry - that var is delegated to the first queue position
                if (construction is PerpetualConstruction) {
                    // perpetual constructions will replace each other
                    editQueue { this[lastIndex] = constructionName }
                } else
                    editQueue { add(size - 1, constructionName) } // insert new construction before perpetual one
            }
            else ->
                editQueue { add(constructionName) }
        }
        currentConstructionIsUserSet = true
    }

    private fun markTileForCreatesOneImprovement(construction: IConstruction, tile: Tile?) {
        val improvementToCreate = (construction as? Building)?.getImprovementToCreate(city.getRuleset(), city.civ)
            ?: return
        if (getTileForImprovement(improvementToCreate.name) != null) return

        val tileForImprovement = requireNotNull(tile) {
            "Cannot queue ${construction.name} without a target tile for ${UniqueType.CreatesOneImprovement.name}"
        }
        require(tryPlaceCreateOneImprovementMarker(improvementToCreate, tileForImprovement)) {
            "Cannot queue ${construction.name}: ${improvementToCreate.name} cannot be created on ${tileForImprovement.position}"
        }
    }

    /** Add a construction named [constructionName] to the end of the queue with all checks
     *
     *  Note: Delegates to overload with `construction` parameter.
     */
    fun addToQueue(constructionName: String, tile: Tile? = null) = addToQueue(getConstruction(constructionName), tile = tile)

    /** Remove one entry from the queue by index.
     *  @param automatic  If this was done automatically, we should automatically try to choose a new construction and treat it as such
     */
    @Synchronized
    fun removeFromQueue(constructionQueueIndex: Int, automatic: Boolean) {
        if (constructionQueueIndex !in constructionQueue.indices) return // The queue may have changed since the caller looked at it
        val newQueue = ArrayList(constructionQueue)
        val constructionName = newQueue.removeAt(constructionQueueIndex)
        val queueBecameEmpty = newQueue.isEmpty()
        // To prevent Construction Automation
        if (queueBecameEmpty && !automatic) newQueue.add(PerpetualConstruction.Idle.name)
        constructionQueue = newQueue

        // UniqueType.CreatesOneImprovement support
        val construction = getConstruction(constructionName)
        if (construction is Building) {
            val improvement = construction.getImprovementToCreate(city.getRuleset(), city.civ)
            if (improvement != null) {
                getTileForImprovement(improvement.name)
                    ?.improvementFunctions
                    ?.removeCreatesOneImprovementMarker(removeConstruction = false)
            }
        }

        currentConstructionIsUserSet = if (queueBecameEmpty) {
            if (automatic) chooseNextConstruction()
            false
        } else true // we're just continuing the regular queue
    }

    /** Remove all queue entries for [constructionName].
     *
     *  Does nothing if there's no entry of that name in the queue.
     *  If the queue is emptied, no automatic: getSettings().autoAssignCityProduction is ignored! (parameter to be added when needed)
     */
    @Synchronized
    fun removeAllByName(constructionName: String) {
        while (!isQueueEmptyOrIdle()) {
            val index = constructionQueue.indexOf(constructionName)
            if (index < 0) return
            removeFromQueue(index, false)
        }
    }
    
    @Synchronized
    fun removeAll(){
        while (!isQueueEmptyOrIdle())
            // automatic=false so once the queue is empties the "idle" construction is set, ending the loop
            removeFromQueue(0, false)
        chooseNextConstruction()
        currentConstructionIsUserSet = false
    }

    /** Moves an entry to the queue top by index.
     *  No-op when index invalid. Must not be called for PerpetualConstruction entries - unchecked! */
    @Synchronized
    fun moveEntryToTop(constructionQueueIndex: Int) {
        if (constructionQueueIndex == 0 || constructionQueueIndex >= constructionQueue.size) return
        editQueue { add(0, removeAt(constructionQueueIndex)) }
    }

    /** Moves an entry by index to the end of the queue, or just before a PerpetualConstruction
     *  (or replacing a PerpetualConstruction if it itself is one and the queue is by happenstance invalid having more than one of those)
     */
    @Synchronized
    fun moveEntryToEnd(constructionQueueIndex: Int) {
        if (constructionQueueIndex >= constructionQueue.size) return
        val withoutEntry = ArrayList(constructionQueue)
        val constructionName = withoutEntry.removeAt(constructionQueueIndex)
        // Some of the overhead of addToQueue is redundant here, but if the complex "needs to replace or go before a perpetual" logic is needed, then use it anyway
        if (withoutEntry.isNotEmpty() && PerpetualConstruction.isNamePerpetual(withoutEntry.last())) {
            constructionQueue = withoutEntry
            return addToQueue(constructionName)
        }
        withoutEntry.add(constructionName)
        constructionQueue = withoutEntry
    }

    fun raisePriority(constructionQueueIndex: Int): Int {
        if (constructionQueueIndex == 0) return 0 // Already first
        editQueue { swap(constructionQueueIndex - 1, constructionQueueIndex) }
        return constructionQueueIndex - 1
    }

    // Lowering == Highering next element in queue
    @Synchronized
    fun lowerPriority(constructionQueueIndex: Int): Int {
        if (constructionQueueIndex >= constructionQueue.size - 1) return constructionQueueIndex // Already last
        raisePriority(constructionQueueIndex + 1)
        return constructionQueueIndex + 1
    }

    private fun MutableList<String>.swap(idx1: Int, idx2: Int) {
        val tmp = this[idx1]
        this[idx1] = this[idx2]
        this[idx2] = tmp
    }

    /** Support for [UniqueType.CreatesOneImprovement]:
     *
     *  If [building] is an improvement-creating one, find a marked tile matching the improvement to be created
     *  (skip if none found), then un-mark the tile and place the improvement unless [removeOnly] is set.
     */
    private fun applyCreateOneImprovement(building: Building, removeOnly: Boolean = false) {
        val improvement = building.getImprovementToCreate(city.getRuleset(), city.civ)
            ?: return
        val tileForImprovement = getTileForImprovement(improvement.name) ?: return
        tileForImprovement.improvementFunctions.removeCreatesOneImprovementMarker(removeConstruction = false)
        if (removeOnly) return
        tileForImprovement.setImprovement(improvement, city.civ)
        // If bought the worldscreen will not have been marked to update, and the new improvement won't show until later...
        GUI.setUpdateWorldOnNextRender()
    }

    /** Support for [UniqueType.CreatesOneImprovement]:
     *
     *  To be called after circumstances forced clearing a marker from a tile (pillaging, nuking).
     *  Should remove one matching building from the queue without looking for a marked tile.
     */
    @Synchronized
    fun removeCreateOneImprovementConstruction(improvement: String) {
        val ruleset = city.getRuleset()
        val indexToRemove = constructionQueue.withIndex().firstNotNullOfOrNull {
            val construction = getConstruction(it.value)
            val buildingImprovement = (construction as? Building)?.getImprovementToCreate(ruleset, city.civ)?.name
            it.index.takeIf { buildingImprovement == improvement }
        } ?: return

        val newQueue = ArrayList(constructionQueue)
        newQueue.removeAt(indexToRemove)
        val queueBecameEmpty = newQueue.isEmpty()
        if (queueBecameEmpty) newQueue.add(PerpetualConstruction.Idle.name)
        constructionQueue = newQueue

        currentConstructionIsUserSet = !queueBecameEmpty
    }

    /** Support for [UniqueType.CreatesOneImprovement]:
     *
     *  Find the selected tile for a specific improvement being constructed via a building, if any.
     */
    @Readonly
    fun getTileForImprovement(improvementName: String) = city.getTiles()
        .firstOrNull { it.isMarkedForCreatesOneImprovement(improvementName) }
    //endregion
}
