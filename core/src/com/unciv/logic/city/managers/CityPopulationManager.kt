package com.unciv.logic.city.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.city.CitizenAutomation
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.city.City
import com.unciv.logic.civilization.InstantYields
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.Counter
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import com.unciv.utils.withItem
import com.unciv.utils.withoutItem
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

class CityPopulationManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var city: City

    var population = 1
        private set
    var foodStored = 0

    val specialistAllocations = Counter<String>()

    @Readonly fun getNewSpecialists() = specialistAllocations 


    //region pure functions
    fun clone(): CityPopulationManager {
        val toReturn = CityPopulationManager()
        toReturn.specialistAllocations.add(specialistAllocations)
        toReturn.population = population
        toReturn.foodStored = foodStored
        return toReturn
    }

    @Readonly fun getNumberOfSpecialists() = getNewSpecialists().values.sum()

    @Readonly
    fun getFreePopulation(): Int {
        val workingPopulation = city.getWorkedTiles().count()
        return population - workingPopulation - getNumberOfSpecialists()
    }
    
    @Readonly
    fun getFoodToNextPopulation(): Int {
        // civ v math, civilization.wikia; the constants of CvPlayer::getGrowthThreshold
        val growth = city.getRuleset().modOptions.constants.cityGrowth
        var foodRequired = growth.base + ((population - 1) * growth.multiplier).toInt() +
            floor((population - 1).toDouble().pow(growth.exponent.toDouble()))

        foodRequired *= city.civ.gameInfo.speed.modifier
        foodRequired *= city.civ.gameInfo.getStartingEra().growthPercent / 100f

        if (city.civ.isCityState)
            foodRequired *= 1.5f
        if (city.civ.receivesAiHandicap())
            foodRequired *= city.civ.gameInfo.getDifficulty().aiCityGrowthModifier * city.civ.getAiPerEraFactor()
        return foodRequired.toInt()
    }

    /** Take null to mean infinity. */
    @Readonly
    fun getNumTurnsToStarvation(): Int? {
        if (!city.isStarving()) return null
        return foodStored / -city.foodForNextTurn() + 1
    }


    /** Take null to mean infinity. */
    @Readonly
    fun getNumTurnsToNewPopulation(): Int? {
        if (!city.isGrowing()) return null
        val roundedFoodPerTurn = city.foodForNextTurn().toFloat()
        val remainingFood = getFoodToNextPopulation() - foodStored
        var turnsToGrowth = ceil(remainingFood / roundedFoodPerTurn).toInt()
        if (turnsToGrowth < 1) turnsToGrowth = 1
        return turnsToGrowth
    }


    //endregion

    /** Implements [UniqueParameterType.PopulationFilter][com.unciv.models.ruleset.unique.UniqueParameterType.PopulationFilter] */
    @Readonly
    fun getPopulationFilterAmount(filter: String): Int {
        return when (filter) {
            "Specialists" -> getNumberOfSpecialists()
            // Universal Suffrage halves specialist unhappiness rounding the removed part up: (specialists + 1) / 2 (CvPlayer::GetUnhappinessFromCitySpecialists, SDK:CvPlayer.cpp:11518-11525).
            // Half of this amount is that, for an amount that is exactly the specialists rounded up to an even number.
            "Specialists (rounded up to even)" -> getNumberOfSpecialists().let { it + it % 2 }
            "Population" -> population
            "Followers of the Majority Religion", "Followers of this Religion" -> city.religion.getFollowersOfMajorityReligion()
            "Unemployed" -> getFreePopulation()
            else -> specialistAllocations[filter]
        }
    }


    fun nextTurn(food: Int) {
        foodStored += food
        if (food < 0)
            city.civ.addNotification("[${city.name}] is starving!",
                city.location, NotificationCategory.Cities, NotificationIcon.Growth, NotificationIcon.Death)
        if (foodStored < 0) {        // starvation!
            if (population > 1) addPopulation(-1)
            foodStored = 0
        }
        val foodNeededToGrow = getFoodToNextPopulation()
        if (foodStored < foodNeededToGrow) return

        // What if the stores are already over foodNeededToGrow but NullifiesGrowth is in effect?
        // We could simply test food==0 - but this way NullifiesStat(food) will still allow growth:
        if (city.getMatchingUniques(UniqueType.NullifiesGrowth).any())
            return

        // Hard block growth when using Avoid Growth, cap stored food
        if (city.avoidGrowth) {
            foodStored = foodNeededToGrow
            return
        }

        // growth!
        foodStored -= foodNeededToGrow
        var percentOfFoodCarriedOver = 0
        city.forEachMatchingUnique(UniqueType.CarryOverFood) {
            if (city.matchesFilter(it.params[1]))
                percentOfFoodCarriedOver += it.params[0].toInt()
        }
        percentOfFoodCarriedOver = percentOfFoodCarriedOver.coerceAtMost(95)  // Try to avoid runaway food gain in mods, just in case
        foodStored += (foodNeededToGrow * percentOfFoodCarriedOver / 100f).toInt()
        addPopulation(1)
        city.shouldReassignPopulation = true
        city.civ.addNotification("[${city.name}] has grown!", city.location,
            NotificationCategory.Cities, NotificationIcon.Growth)
    }

    /**
     *  Changes the population by [count], not below 1. A growth above [City.highestPopulation] pays the instant yields of `upon population growth`
     *  (CvCity::setPopulation, V:CvCity.cpp:16595-16618), with the citizens gained as the triggering amount, unless the city is in resistance or was acquired
     *  this turn (the original's `GetGameTurnFounded`: the turn it was founded, here [City.turnAcquired]); a city that starves and regrows gets nothing.
     */
    fun addPopulation(count: Int) {
        val changedAmount = count.coerceAtLeast(1 - population)
        population += changedAmount
        // VP CvCity::setPopulation stores damage, not remaining HP; shrinking must leave at least one HP.
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        if (constants.cityCombatLikeVp)
            city.health = (city.health + changedAmount * constants.cityCombat.healthPerPopulation).coerceIn(1, city.getMaxHealth())
        val grewBeyondHighest = population > city.highestPopulation
        if (grewBeyondHighest) city.highestPopulation = population
        val freePopulation = getFreePopulation()
        if (freePopulation < 0) {
            unassignExtraPopulation()
            city.cityStats.update()
        } else {
            autoAssignPopulation()
        }

        if (city.civ.gameInfo.isReligionEnabled())
            city.religion.updatePressureOnPopulationChange(changedAmount)

        if (grewBeyondHighest && !city.isInResistance() && city.civ.gameInfo.turns > city.turnAcquired)
            InstantYields.fire(UniqueType.TriggerUponPopulationGrowth, city.civ, city, changedAmount, GameContext(city),
                "due to the growth of [${city.name}]")
    }

    fun setPopulation(count: Int) {
        addPopulation(-population + count)
    }

    /** Gives every unemployed citizen a job, one at a time (CvCityCitizens::DoAddBestCitizenFromUnassigned, see [CitizenAutomation.Governor]) */
    internal fun autoAssignPopulation(): Unit = timeThis("CityPopulationManager.autoAssignPopulation") {
        city.cityStats.update()  // calculate current stats with current assignments
        val freePopulation = getFreePopulation()
        if (freePopulation <= 0) return

        val governor = CitizenAutomation.Governor(city)
        repeat(freePopulation) { governor.addBestCitizen() }
        city.cityStats.update()
    }

    fun unassignExtraPopulation() {
        for (tile in city.getWorkedTiles().toList()) {
            if (tile.getOwner() != city.civ || tile.getWorkingCity() != city
                    || tile.aerialDistanceTo(city.getCenterTile()) > city.getWorkRange())
                city.stopWorkingTile(tile)
        }

        // unassign specialists that cannot be (e.g. the city was captured and one of the specialist buildings was destroyed)
        for ((specialistName, maxAmount) in getMaxSpecialists())
            if (specialistAllocations[specialistName] > maxAmount)
                specialistAllocations[specialistName] = maxAmount

        if (getFreePopulation() >= 0) return
        // CvCity::setPopulation: CvCityCitizens::DoRemoveWorstCitizen for each citizen too many
        val governor = CitizenAutomation.Governor(city)
        while (getFreePopulation() < 0) {
            if (!governor.removeWorstCitizen()) break
        }
    }

    @Readonly
    fun getMaxSpecialists(): Counter<String> {
        val counter = Counter<String>()
        for (building in city.cityConstructions.getBuiltBuildings())
            counter.add(building.newSpecialists())
        return counter
    }
}
