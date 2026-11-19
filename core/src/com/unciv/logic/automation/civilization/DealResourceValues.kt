package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.SpaceshipProductionAi
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 *  What a resource is worth in a deal, as Vox Populi's Community Patch calculates it (CvDealAI.cpp, Release-5.4.8; D7): the luxury
 *  branch of CvDealAI::GetLuxuryResourceValue that `MOD_BALANCE_VP` does not guard, and CvDealAI::GetStrategicResourceValue with the list
 *  of things the player could build with the resource (CvDealAI::GetStrategicResourceItemList). The values are the buyer's or the seller's
 *  own, before the approach modifiers [DealAi.getTradeItemValue] applies to the deal; `null` is the original's INT_MAX: not for sale or
 *  not worth buying at any price.
 *
 *  Not ported: the VP branch of the luxury value (`MOD_BALANCE_VP`, roadmap W8.7); the resource obsolescence of CvTeam::IsResourceObsolete
 *  (the era after which the AI stops trading Iron and Horses: not in the ruleset); the luxury yields of beliefs (Goddess of Festivals:
 *  Vox Populi's; BNW has none) and of the Netherlands' trade traits; the building's mutually exclusive group (no such field in a
 *  building); Nuclear Gandhi (CvDiplomacyAI::IsNuclearGandhi: a mod option of the original); the renewal of a deal in progress (no
 *  deal under renewal is kept apart from the new one); the units a civ cannot train (traits that forbid a unit class) and the units
 *  City-States gift (no such flags on a unit).
 */
object DealResourceValues {

    /** CvDealAI::GetOneGPTValue: the turns of a deal at the game speed (a peace deal's, if [peaceDeal]) less the interest of one gold per turn */
    fun oneGptValue(civ: Civilization, peaceDeal: Boolean): Int {
        val speed = civ.gameInfo.speed
        val duration = if (peaceDeal) speed.peaceDealDuration else speed.dealDuration
        val interest = max(civ.gameInfo.ruleset.ai.define(AiDefine.EACH_GOLD_PER_TURN_VALUE_PERCENT), 0)
        return max(1, duration * 100 / (100 + interest))
    }

    /** CvPlayer::getResourceExport: the copies of [resource] [civ] has traded away */
    fun resourceExport(civ: Civilization, resource: TileResource): Int =
        -civ.detailedCivResources.filter { it.resource == resource && it.amount < 0 && (it.origin == "Trade" || it.origin == "Trade request") }
            .sumOf { it.amount }

    /** CvPlayer::GetExtraHappinessPerLuxury: what each luxury gives beyond its own happiness (the difficulty's, and the civ's uniques) */
    fun extraHappinessPerLuxury(civ: Civilization): Int {
        var extra = civ.getDifficulty().extraHappinessPerLuxury.toInt()
        civ.forEachMatchingUnique(UniqueType.BonusHappinessFromLuxury) { extra += it.params[0].toInt() }
        return extra
    }

    /**
     *  CvPlayer::GetHappinessFromLuxury: nothing for a luxury the World Congress banned; the luxury's happiness (4) when [civ] has a copy
     *  (imports counted), else the share it retains of it for a copy it traded away (the Netherlands' retention unique), else nothing
     */
    fun happinessFromLuxury(civ: Civilization, resource: TileResource): Int {
        if (civ.gameInfo.league?.isLuxuryBanned(resource.name) == true) return 0
        if (resource.resourceType != ResourceType.Luxury) return 0
        if (DealAi.numResourceAvailable(civ, resource, true) > 0) return LUXURY_HAPPINESS
        val retained = civ.getMatchingUniques(UniqueType.RetainHappinessFromLuxury).sumOf { it.params[0].toInt() }
        if (retained > 0 && resourceExport(civ, resource) > 0) return LUXURY_HAPPINESS * retained / 100
        return 0
    }

    private const val URANIUM = "Uranium"

    /** CvResourceInfo::getHappiness of every BNW luxury (Resources.Happiness = 4), as Unciv's happiness calculation uses it */
    const val LUXURY_HAPPINESS = 4

    /**
     *  CvDealAI::GetLuxuryResourceValue, Community Patch branch: (the happiness of one more copy for the owner of the item - ours if we
     *  give it, theirs if they do - plus the game's era) x turns / 2. Raised by the receiver's net gold: our own if we receive, theirs if
     *  we give it: by sqrt(net gold / 3) when we give it, sqrt(net gold / 4) when we receive it, plus a quarter, at most the value itself.
     *  Giving: not our last copy while the empire is unhappy ([null]); our last copy is worth x3 (x2 with the retention unique). Receiving:
     *  x2 when the empire is unhappy (x3 very unhappy).
     */
    fun luxuryValue(us: Civilization, them: Civilization, resource: TileResource, numTurns: Int, fromMe: Boolean, netGoldOfReceiver: Int): Int? {
        val turns = max(1, numTurns)
        val owner = if (fromMe) us else them
        val baseHappiness = happinessFromLuxury(owner, resource) + extraHappinessPerLuxury(owner)
        val available = DealAi.numResourceAvailable(us, resource, true)
        var value = (baseHappiness + DiplomacyTurn.gameEraNumber(us)) * turns / 2

        if (netGoldOfReceiver > 0) {
            val surcharge = (0.25 + sqrt(netGoldOfReceiver / (if (fromMe) 3.0 else 4.0))).toInt()
            value += min(surcharge, value)
        }
        val unhappy = EconomicMilitaryStrategyTests.isEmpireUnhappy(us)
        if (fromMe) {
            if (unhappy && available == 1) return null
            if (available == 1) value *= if (us.hasUnique(UniqueType.RetainHappinessFromLuxury)) 2 else 3
            return value
        }
        if (unhappy) value *= if (us.getHappiness() <= us.gameInfo.ruleset.ai.define(AiDefine.VERY_UNHAPPY_THRESHOLD)) 3 else 2
        return value
    }

    /** CvPlayer::getNumResourceUnimproved: the copies of [resource] on tiles of [civ]'s cities that its improvement does not yet provide */
    fun numResourceUnimproved(civ: Civilization, resource: TileResource): Int {
        if (!civ.tech.isRevealed(resource)) return 0
        var amount = 0
        for (city in civ.cities)
            for (tile in city.getTiles())
                if (tile.resource == resource.name && !tile.providesResources(civ)) amount += tile.resourceAmount
        return amount
    }

    /**
     *  CvTeamTechs::GetTechFrontier: the technologies [civ] does not know whose prerequisites it knows, and those whose prerequisites it
     *  knows or are in that first set
     */
    fun techFrontier(civ: Civilization): Set<String> {
        val technologies = civ.gameInfo.ruleset.technologies.values
        val first = technologies.filter { !civ.tech.isResearched(it.name) && it.prerequisites.all { pre -> civ.tech.isResearched(pre) } }
            .mapTo(HashSet()) { it.name }
        val second = technologies.filter { it.name !in first && !civ.tech.isResearched(it.name) &&
            it.prerequisites.all { pre -> civ.tech.isResearched(pre) || pre in first } }.map { it.name }
        return first + second
    }

    /** CvTeam::GetTechProgressPercent: the share of technologies known, at least 1 */
    fun techProgressPercent(civ: Civilization): Int =
        max(1, civ.tech.techsResearched.size * 100 / max(1, civ.gameInfo.ruleset.technologies.size))

    /** Whether [civ] is at war with a major, or (an AI) has a major it plans war on: CvDealAI::GetStrategicResourceItemList's `bWar` */
    private fun atWarOrPlanningWar(civ: Civilization): Boolean {
        if (civ.gameInfo.civilizations.any { it.isMajorCiv && it.isAlive() && civ.isAtWarWith(it) }) return true
        if (civ.isHuman()) return false
        return civ.gameInfo.civilizations.any {
            it.isMajorCiv && civ.ai.diplomacy.isPlayerValid(it) && civ.ai.diplomacy.approachTowards(it, false) == MajorCivApproach.War
        }
    }

    /** What stops a building being built in a city apart from the resource we are valuing and the technology the frontier test looks at */
    private val ignoredRejections = setOf(RejectionReasonType.ConsumesResources, RejectionReasonType.RequiresTech)

    /**
     *  CvDealAI::GetStrategicResourceItemList: what [us] could use [resource] for, as a list of weights: for each of its cities that is no
     *  puppet, the buildings that need the resource, that it can build apart from the resource (and the technology, for one on the frontier,
     *  when selling), not built or queued there: the production weight of the building /100 x the economic value of the city x one GPT's
     *  value / 100000 x turns / 50, once for each copy the building needs (the weight divided among them); with a list at least as long as
     *  the copies available when selling, the search stops. Then the units that need it, that it has the technology for (the frontier when
     *  selling), not obsolete: their power (x2 at war or planning war) x turns / 50, three entries for each, each 10% less than the one
     *  before; air units as many as it has free air slots; others as many as its unit supply allows (all of the supply left when at war,
     *  two thirds otherwise, at least one). And, for Aluminum with the Apollo Program built, 10000 for each part and building it still needs.
     *  Sorted from the highest weight unless selling.
     */
    fun strategicResourceItemList(us: Civilization, resource: TileResource, numTurns: Int, fromMe: Boolean, numAvailable: Int, peaceDeal: Boolean): List<Int> {
        val ruleset = us.gameInfo.ruleset
        val total = ArrayList<Int>()
        val oneGpt = oneGptValue(us, peaceDeal)
        val frontier = techFrontier(us)
        fun techAllows(tech: String?) = tech == null || us.tech.isResearched(tech) || (fromMe && tech in frontier)

        val buildings = ruleset.buildings.values.filter {
            us.getEquivalentBuilding(it.name) == it && it.getResourceRequirementsPerTurn()[resource.name] > 0 &&
                techAllows(it.requiredTech) && it.getMatchingUniques(UniqueType.ObsoleteWith).none { unique -> us.tech.isResearched(unique.params[0]) }
        }
        if (buildings.isNotEmpty()) for (city in us.cities) {
            if (city.isPuppet) continue
            val weights = ArrayList<Pair<Building, Int>>()
            val economicValue = lazy { AttackTargetsAi.economicValue(city, us) }
            for (building in buildings) {
                val constructions = city.cityConstructions
                if (constructions.isBuilt(building.name) || constructions.isBeingConstructedOrEnqueued(building.name)) continue
                if (building.getRejectionReasons(constructions).any { it.type !in ignoredRejections }) continue
                var weight = (ProductionAi.buildingWeight(city, building) / 100).toLong()
                weight *= economicValue.value
                weight *= oneGpt
                weight /= 100000
                weight *= max(1, numTurns)
                weight /= 50
                if (weight <= 0) continue
                val needed = building.getResourceRequirementsPerTurn()[resource.name]
                // A building that needs several copies counts a reduced weight for each
                repeat(needed) { weights.add(building to (weight / needed).toInt()) }
            }
            for ((_, weight) in weights.sortedByDescending { it.second }) {
                total.add(weight)
                // Selling and already needing every copy we have: no need to look further
                if (fromMe && total.size >= numAvailable) return total
            }
        }

        val supplyLeft = us.stats.getUnitSupply() - us.units.getNumUnitsToSupply()
        val war = atWarOrPlanningWar(us)
        val airUnits = ArrayList<Int>()
        val otherUnits = ArrayList<Int>()
        val units = ruleset.units.values.filter { us.getEquivalentUnit(it.name) == it && it.getResourceRequirementsPerTurn()[resource.name] > 0 }
        for (unit in units) {
            if (supplyLeft < 0 && !unit.isAirUnit()) continue
            if (unit.hasUnique(UniqueType.NuclearWeapon) && !us.gameInfo.gameParameters.nuclearWeaponsEnabled) continue
            if (!unit.requiredTechs().all { techAllows(it) }) continue
            if (unit.techsAtWhichNoLongerAvailable().any { us.tech.isResearched(it) }) continue
            if (unit.hasUnique(UniqueType.SpaceshipPart)) continue
            var value = unit.getAiPower()
            if (war) value *= 2
            value = value * max(1, numTurns) / 50
            for (i in 0 until 3) (if (unit.isAirUnit()) airUnits else otherUnits).add(value * (10 - i) / 10)
        }
        if (airUnits.isNotEmpty()) {
            val freeSlots = us.cities.sumOf { it.getMaxAirUnits() - it.getCenterTile().airUnits.size }
            if (freeSlots > 0) total.addAll(airUnits.sortedDescending().take(min(freeSlots, airUnits.size)))
        }
        if (otherUnits.isNotEmpty() && supplyLeft > 0) {
            var toBuild = supplyLeft
            if (!war) toBuild = toBuild * 2 / 3
            toBuild = min(max(toBuild, 1), otherUnits.size)
            total.addAll(otherUnits.sortedDescending().take(toBuild))
        }

        if (resource.name == SpaceshipProductionAi.ALUMINUM && GrandStrategyAi.hasApolloProgram(us)) {
            val needed = SpaceshipProductionAi.aluminumStillNeededForSpaceship(us) + SpaceshipProductionAi.aluminumStillNeededForCoreCities(us)
            repeat(max(0, needed)) { total.add(10000) }
        }

        if (!fromMe) total.sortDescending()
        return total
    }

    /**
     *  CvDealAI::GetStrategicResourceValue: a resource [us] does not see is worth nothing to anyone ([null]). The copies available: when
     *  selling those of its own lands, when buying those it has with imports and the unimproved ones in its cities. Selling: never the last
     *  copy; Uranium not to a civ that is close to world conquest or that we avoid exchanges with at peace, nor to one that nuked us; Aluminum
     *  not while we compete for a victory and they are close to the spaceship. Buying: nothing for a resource it already has 5 copies of.
     *  The base is one GPT's value x the number of eras x the share of technologies known. Selling: each copy beyond what the list needs for
     *  itself ([strategicResourceItemList]) is worth the base (twice for Uranium), x8 and x4 for the two copies it leaves us with,
     *  [null] when the last of them goes. Buying: for each of up to 5 copies, the list's weights from the number it has on, raised by
     *  sqrt(net gold)/2 up to 20%, at least the base and at most 10 times it.
     */
    fun strategicValue(us: Civilization, them: Civilization, resource: TileResource, quantity: Int, numTurns: Int, fromMe: Boolean, netGoldOfReceiver: Int): Int? {
        val peaceDeal = us.isAtWarWith(them)
        if (!us.tech.isRevealed(resource)) return null
        if (quantity == 0) return 0
        val turns = max(1, numTurns)
        var available = DealAi.numResourceAvailable(us, resource, !fromMe)
        if (!fromMe) available += numResourceUnimproved(us, resource)
        if (fromMe && available - quantity <= 0) return null

        val rules = us.gameInfo.ruleset.ai
        if (fromMe) {
            if (resource.name == URANIUM) {
                if (us.ai.diplomacy.memoryOf(them).numTimesNuked > 0) return null
                if (!peaceDeal && (VictoryProgressAi.isCloseToWorldConquest(them, rules) || WarAlliesAi.avoidExchangesWithPlayer(us, them))) return null
            } else if (resource.name == SpaceshipProductionAi.ALUMINUM) {
                if (VictoryProgressAi.isCompetingForVictory(us) && VictoryProgressAi.isCloseToSpaceshipVictory(them, rules)) return null
            }
        }
        if (!fromMe && available >= 5) return 0

        val list = strategicResourceItemList(us, resource, turns, fromMe, available, peaceDeal)
        val baseValue = oneGptValue(us, peaceDeal) * us.gameInfo.ruleset.eras.size * techProgressPercent(us) / 100

        var value = 0
        if (fromMe) {
            val canSell = available - list.size
            if (canSell <= 0) return null
            var toAdd = baseValue
            if (resource.name == URANIUM) toAdd *= 2
            for (copy in 1..quantity) {
                val after = canSell - copy
                if (after <= 0) return null
                value += if (after <= 2) toAdd * 4 * (3 - after) else toAdd
            }
        } else {
            if (list.isEmpty()) return 0
            for (index in available until available + min(5, quantity)) {
                if (index >= list.size) continue
                var toAdd = list[max(0, index)]
                if (netGoldOfReceiver > 0) {
                    val surcharge = (0.5 * sqrt(netGoldOfReceiver.toDouble())).toInt()
                    toAdd += min(surcharge, toAdd / 5)
                }
                value += min(max(baseValue, toAdd), 10 * baseValue)
            }
        }
        return value
    }
}
