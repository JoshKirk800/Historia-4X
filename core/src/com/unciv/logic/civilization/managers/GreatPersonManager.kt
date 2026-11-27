package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.MayaLongCountAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Counter
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.MayaCalendar
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt


// todo: Great Admiral?

/**
 * Great People of a civilization. Great Person points are kept per city ([com.unciv.logic.city.City.greatPersonProgress]), as in the original game
 * (CvCityCitizens::DoSpecialists): a city's points for a Great Person never mix with those of another city, and a Great Person is born in
 * the city whose points reach the threshold. Great Generals and Admirals come from combat points, kept here per civilization.
 */
class GreatPersonManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization

    /**
     * How many Great People of each group (the [UniqueType.GPPointPool] parameter, "" without one) this civilization has gained: born from
     * points, chosen as a free Great Person, or given by a building or policy. The next one of the group needs more points for each.
     * Great People bought with faith, Prophets, and the Great People of combat are not counted (CvCityCitizens::DoSpawnGreatPerson).
     */
    var greatPeopleCreated = Counter<String>()

    var pointsForNextGreatGeneral = 200
    var pointsForNextGreatGeneralCounter = Counter<String>() // Initial values assigned when needed
    /** How many of each combat Great Person this civilization earned through combat points; see [ModConstants.greatGeneralThresholdLikeCivV] */
    var greatGeneralsCreatedCounter = Counter<String>()

    var greatGeneralPointsCounter = Counter<String>()
    var greatGeneralPoints = 0
    var freeGreatPeople = 0
    /** Marks subset of [freeGreatPeople] as subject to maya ability restrictions (each only once until all used) */
    var mayaLimitedFreeGP = 0
    /** Remaining candidates for maya ability - whenever empty refilled from all GP, starts out empty */
    var longCountGPPool = HashSet<String>()
    /** How often each Great Person was chosen for the Long Count, with the ModConstants `longCountYieldsLikeVp`: Vox Populi's LONGCOUNT accomplishments */
    var longCountChoices = Counter<String>()

    /** Only in games saved before Great Person points were kept per city (base threshold per group, doubled per Great Person);
     *  read and emptied by [com.unciv.logic.BackwardCompatibility.migrateGreatPersonPointsToCities] */
    var pointsForNextGreatPersonCounter = Counter<String>()
    /** Only in games saved before Great Person points were kept per city (points of the whole civilization);
     *  read and emptied by [com.unciv.logic.BackwardCompatibility.migrateGreatPersonPointsToCities] */
    var greatPersonPointsCounter = Counter<String>()

    fun clone(): GreatPersonManager {
        val toReturn = GreatPersonManager()
        toReturn.freeGreatPeople = freeGreatPeople
        toReturn.greatPeopleCreated = greatPeopleCreated.clone()
        toReturn.greatPersonPointsCounter = greatPersonPointsCounter.clone()
        toReturn.pointsForNextGreatPersonCounter = pointsForNextGreatPersonCounter.clone()
        toReturn.pointsForNextGreatGeneralCounter = pointsForNextGreatGeneralCounter.clone()
        toReturn.greatGeneralsCreatedCounter = greatGeneralsCreatedCounter.clone()
        toReturn.greatGeneralPointsCounter = greatGeneralPointsCounter.clone()
        toReturn.pointsForNextGreatGeneral = pointsForNextGreatGeneral
        toReturn.greatGeneralPoints = greatGeneralPoints
        toReturn.mayaLimitedFreeGP = mayaLimitedFreeGP
        toReturn.longCountGPPool = longCountGPPool.toHashSet()
        toReturn.longCountChoices = longCountChoices.clone()
        return toReturn
    }

    @Readonly
    fun getPoolKey(greatPerson: String) = civInfo.getEquivalentUnit(greatPerson)
        .getMatchingUniques(UniqueType.GPPointPool)
        // An empty string is used to indicate the Unique wasn't found
        .firstOrNull()?.params?.get(0) ?: ""

    /**
     * The points a city needs for [greatPerson] (CvCityCitizens::GetSpecialistUpgradeThreshold): base + increase × the Great People of its
     * group gained so far ([greatPeopleCreated]), scaled by the game speed and then by the starting era, rounding down each time.
     */
    @Readonly
    fun getPointsRequiredForGreatPerson(greatPerson: String): Int {
        val gameInfo = civInfo.gameInfo
        val constants = gameInfo.ruleset.modOptions.constants
        val base = constants.greatPersonThresholdBase + constants.greatPersonThresholdIncrease * greatPeopleCreated[getPoolKey(greatPerson)]
        val speedPercent = (gameInfo.speed.modifier * 100).roundToInt()
        return base * speedPercent / 100 * gameInfo.getStartingEra().greatPeoplePercent / 100
    }

    /**
     * By how much the combat points of the next Great General or Admiral rise when [created] of them are earned. Civ V
     * (CvPlayer::createGreatGeneral): the percentage modifier on the base of 200 grows by 50 x (created / 10 + 1), so +100 for each of
     * the first nine, +200 for each of the next ten and so on. Otherwise a flat 50.
     */
    @Readonly
    private fun getGreatGeneralThresholdIncrease(created: Int): Int {
        if (!civInfo.gameInfo.ruleset.modOptions.constants.greatGeneralThresholdLikeCivV) return 50
        return 200 * 50 * (created / 10 + 1) / 100
    }

    /** A Great General or Admiral whose combat points are reached, if any; takes the points and raises the next threshold */
    fun getNewGreatGeneral(): String? {
        for ((unit, value) in greatGeneralPointsCounter){
            if (pointsForNextGreatGeneralCounter[unit] == 0) {
                pointsForNextGreatGeneralCounter[unit] = 200
            }
            val requiredPoints = pointsForNextGreatGeneralCounter[unit] * civInfo.gameInfo.getStartingEra().greatPeoplePercent / 100
            if (value >= requiredPoints) {
                greatGeneralPointsCounter[unit] -= requiredPoints
                greatGeneralsCreatedCounter.add(unit, 1)
                pointsForNextGreatGeneralCounter[unit] += getGreatGeneralThresholdIncrease(greatGeneralsCreatedCounter[unit])
                return unit
            }
        }
        return null
    }

    /** Adds each city's Great Person points of this turn to its progress (CvCityCitizens::DoSpecialists); only for major civilizations */
    fun addGreatPersonPoints() {
        for (city in civInfo.cities) city.greatPersonProgress.add(city.getGreatPersonPoints())
    }

    /**
     * Each city whose progress toward a Great Person reaches the threshold gets that Great Person, and its progress toward it starts again
     * from nothing: points beyond the threshold are lost (CvCityCitizens::DoSpecialists). The threshold is taken again for each, so a
     * second Great Person of a group in the same turn needs more points.
     */
    fun spawnGreatPeopleFromPoints() {
        if (!civInfo.isMajorCiv) return
        val ruleset = civInfo.gameInfo.ruleset
        for (city in civInfo.cities.toList()) {
            for (greatPerson in city.greatPersonProgress.keys.toList()) {
                if (city.greatPersonProgress[greatPerson] < getPointsRequiredForGreatPerson(greatPerson)) continue
                city.greatPersonProgress.remove(greatPerson)
                val unit = ruleset.units[greatPerson] ?: continue
                if (civInfo.units.addUnit(unit, city) != null) countGreatPersonGained(unit)
            }
        }
    }

    /**
     * A Great Person gained from points, as a free choice, or from a building or policy raises what the next one of its group needs
     * (CvCityCitizens::DoSpawnGreatPerson, CvCity::processBuilding, CvPlayer free units). Prophets and the Great People of combat have
     * counts of their own, and those bought with faith count for nothing.
     */
    fun countGreatPersonGained(unit: BaseUnit) {
        if (!unit.isGreatPerson || unit.hasUnique(UniqueType.MayFoundReligion)) return
        if (civInfo.gameInfo.ruleset.greatGeneralUnits.any { it.name == unit.name }) return
        greatPeopleCreated.add(getPoolKey(unit.name), 1)
    }

    /** For each Great Person someone has points toward: the name of the city closest to getting one, and its points */
    @Readonly
    fun getLeadingCities(): Map<String, Pair<String, Int>> {
        val leading = LinkedHashMap<String, Pair<String, Int>>()
        for (city in civInfo.cities)
            for ((greatPerson, points) in city.greatPersonProgress)
                if (points > (leading[greatPerson]?.second ?: -1)) leading[greatPerson] = city.name to points
        return leading
    }

    /**
     *  The Great People a historic event may give points toward (CvPlayer::ChangeNumHistoricEvents, V:CvPlayer.cpp:31131-31166): that of each specialist that has
     *  Great Person points, twice when a slot for its Great Works is free somewhere ([GreatWorkFunctions.getSlotTypeOfGreatPerson])
     */
    @Readonly
    fun getHistoricEventGreatPeople(): List<String> {
        val ruleset = civInfo.gameInfo.ruleset
        val candidates = ArrayList<String>()
        for (specialist in ruleset.specialists.values) {
            val greatPerson = specialist.greatPersonPoints.keys.firstOrNull() ?: continue
            candidates.add(greatPerson)
            val slotType = GreatWorkFunctions.getSlotTypeOfGreatPerson(ruleset, greatPerson) ?: continue
            if (civInfo.cities.any { it.greatWorks.getFreeSlot(slotType) != null }) candidates.add(greatPerson)
        }
        return candidates
    }

    /**
     *  A historic event that counted, with [UniqueType.HistoricEventGreatPersonPoints] (CvPlayer::ChangeNumHistoricEvents, V:CvPlayer.cpp:31127-31184, the original's
     *  `EventGP`): the capital gets that percent of the points it needs for one of [getHistoricEventGreatPeople], chosen at random
     */
    fun onHistoricEvent() {
        val percent = civInfo.getMatchingUniques(UniqueType.HistoricEventGreatPersonPoints).sumOf { it.params[0].toInt() }
        val capital = civInfo.getCapital() ?: return
        if (percent <= 0) return
        val candidates = getHistoricEventGreatPeople()
        if (candidates.isEmpty()) return
        val greatPerson = candidates[civInfo.state.stateBasedRandom("HistoricEventGreatPerson", civInfo.culture.historicEvents).nextInt(candidates.size)]
        val points = getPointsRequiredForGreatPerson(greatPerson) * percent / 100
        capital.greatPersonProgress.add(greatPerson, points)
        civInfo.addNotification("Our historic event gave [$points] points toward a [$greatPerson] in [${capital.name}]",
            capital.location, NotificationCategory.General, greatPerson)
    }

    /** Add [amount] points for [name] to the capital's progress, or the first city's without a capital (used in dev console) */
    fun addGreatPersonPoints(name: String, amount: Int) {
        (civInfo.getCapital() ?: civInfo.cities.firstOrNull())?.greatPersonProgress?.add(name, amount)
    }

    fun triggerMayanGreatPerson() {
        if (civInfo.isSpectator) return
        val greatPeople = getGreatPeople()
        if (longCountGPPool.isEmpty())
            longCountGPPool = greatPeople.map { it.name }.toHashSet()

        freeGreatPeople++
        mayaLimitedFreeGP++

        // Anyone an idea for a good icon?
        val notification = "{A new b'ak'tun has just begun!}\n{A Great Person joins you!}"
        civInfo.addNotification(notification, MayaLongCountAction(), NotificationCategory.General, MayaCalendar.notificationIcon)
    }

    /** Get Great People specific to this manager's Civilization, already filtered by `isHiddenBySettings` */
    @Readonly
    fun getGreatPeople() = civInfo.gameInfo.ruleset.units.values.asSequence()
        .filter { it.isGreatPerson }
        .map { civInfo.getEquivalentUnit(it.name) }
        .filterNot { it.isUnavailableBySettings(civInfo.gameInfo) }
        .toHashSet()

    /**
     * Returns the Great People this Civilization can currently select for a free grant.
     *
     * An option being present does not guarantee placement will succeed when it is chosen.
     */
    @Readonly
    fun getFreeGreatPersonOptions(): Set<BaseUnit> {
        if (freeGreatPeople <= 0 || civInfo.cities.isEmpty()) return emptySet()

        val greatPeople = getGreatPeople()
        if (mayaLimitedFreeGP <= 0) return greatPeople
        return greatPeople.filter { it.name in longCountGPPool }.toSet()
    }

    /**
     * Attempts to grant the named option, returning the placed unit or `null` if the choice is not
     * currently available or placement fails. A failed placement consumes no entitlement or Maya
     * pool entry, though the existing placement internals may already have allocated a unit ID.
     */
    fun chooseFreeGreatPerson(unitName: String): MapUnit? {
        val choice = getFreeGreatPersonOptions().singleOrNull { it.name == unitName } ?: return null
        val useMayaLongCount = mayaLimitedFreeGP > 0
        val unit = civInfo.units.addUnit(choice, civInfo.getCapital()) ?: return null

        freeGreatPeople--
        if (useMayaLongCount) {
            mayaLimitedFreeGP--
            longCountGPPool.remove(choice.name)
            // CvPlayerTraits::SetUnitBaktun (V:CvTraitClasses.cpp:7325-7369): the choice is an accomplishment by the kind of Great Person, and the cities update
            if (civInfo.gameInfo.ruleset.modOptions.constants.longCountYieldsLikeVp) {
                longCountChoices.add(choice.name, 1)
                for (city in civInfo.cities) city.cityStats.update()
            }
        }
        countGreatPersonGained(choice)
        return unit
    }

    /** The Great Person points all cities get this turn, for display */
    @Readonly
    fun getGreatPersonPointsForNextTurn(): Counter<String> {
        val greatPersonPoints = Counter<String>()
        for (city in civInfo.cities) greatPersonPoints.add(city.getGreatPersonPoints())
        return greatPersonPoints
    }
}
