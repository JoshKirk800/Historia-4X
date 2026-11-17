package com.unciv.logic.automation.city

import com.unciv.UncivGame
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.WeightedChoice
import com.unciv.logic.automation.strategy.AiOperations
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.strategy.UnitProductionAi
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.automation.unit.SettlerAutomation
import com.unciv.logic.city.City
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.civilization.CityAction
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.cityscreen.CityScreen

/**
 *  The production choice of a city: CvCityAI::AI_chooseProduction and CvCityStrategyAI::ChooseProduction (CvCityStrategyAI.cpp).
 *
 *  Every buildable item gets its flavor weight ([ProductionAi]: Σ item flavor × city flavor), adjusted per kind as the original does;
 *  then Vox Populi's selection ([choose]): the sanity checks, the time penalty ([WonderProductionAi.reweightByTurnsLeft], ReweightByDuration),
 *  the current build kept while it is still half as good as the best, else a weighted pick among those within
 *  HandicapInfos.CityProductionChoiceCutoffThreshold % of the best. The wonder city first rolls whether to start the next wonder desired.
 *
 *  Units for the open slots of the operations (CvCity::GetUnitForOperation, CITY_BUILDABLE_UNIT_FOR_OPERATION: settler escorts, attack
 *  armies, barbarian hunters ... see [com.unciv.logic.automation.strategy.AiOperations]) are candidates as in the original: weight
 *  AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT + AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER × (OFFENSE + the number of times such a
 *  build was skipped over) (x5 in an operation unit provider city) + the unit's own weight; choosing it commits the city to train the unit
 *  for the slot. Units for an attack army (CvMilitaryAI::GetUnitForArmy) are candidates while an attack waits for units
 *  ([MilitaryOperations.unitForArmy]).
 *  Not ported: the "rush" flag of long builds, which BNW's DoHurry no longer spends gold on.
 *  Unciv extras kept: items a human disabled in the city, the personality's "Will not build" uniques and the ruleset's AiChoiceWeight
 *  uniques (a multiplier on the weight, none in the base rulesets).
 */
class ConstructionAutomation(val cityConstructions: CityConstructions) {

    private val city = cityConstructions.city
    private val civInfo = city.civ

    /** One candidate of ChooseProduction (CvCityBuildable with its weight); [forOperation]: CITY_BUILDABLE_UNIT_FOR_OPERATION or _FOR_ARMY */
    class Buildable(val construction: IConstruction, var weight: Int, val turns: Int, val slotOffer: AiOperations.SlotOffer? = null,
                    val forOperation: Boolean = slotOffer != null)

    private val constructionsToAvoid = civInfo.getPersonality().getMatchingUniques(UniqueType.WillNotBuild, city.state).map { it.params[0] }.toList()

    private fun isAllowed(construction: INonPerpetualConstruction): Boolean {
        if (civInfo.isHuman() && construction.name in city.disabledConstructions) return false
        for (toAvoid in constructionsToAvoid) {
            if (construction is Building && construction.matchesFilter(toAvoid, city.state)) return false
            if (construction is BaseUnit && construction.matchesFilter(toAvoid, city.state)) return false
        }
        return true
    }

    /**
     *  Chooses the next production when the city builds nothing or a process (CvCity::doProduction), or always with [interruptWonders]
     *  (CvCitySpecializationAI::DoTurn after a new war or a lost city).
     */
    fun chooseNextConstruction(interruptWonders: Boolean = false): Unit = timeThis("AI.ChooseProduction") {
        if (!interruptWonders && cityConstructions.getCurrentConstruction() !is PerpetualConstruction) return

        val chosen = chooseWonderForWonderCity(interruptWonders)?.also { wonder ->
            AiDecisionLog.log(civInfo) { "${city.name} builds wonder ${wonder.name} (wonder city)" }
        } ?: run {
            val candidates = getBuildables()
            val picked = choose(candidates) ?: return@run null
            AiDecisionLog.log(civInfo) {
                val sorted = candidates.sortedByDescending { it.weight }
                val settler = sorted.firstOrNull { (it.construction as? BaseUnit)?.isCityFounder() == true }
                val military = sorted.firstOrNull { (it.construction as? BaseUnit)?.isMilitary == true }
                "${city.name} builds ${picked.construction.name} [${picked.weight}, ${picked.turns}t] of " +
                    sorted.take(6).joinToString { "${it.construction.name} ${it.weight}/${it.turns}t" } +
                    "; settler ${settler?.let { "#${sorted.indexOf(it) + 1} ${it.weight}" } ?: "not a candidate"}" +
                    "; best military ${military?.let { "${it.construction.name} #${sorted.indexOf(it) + 1} ${it.weight}" } ?: "none"}" +
                    "; city flavors " + listOf(Flavor.Expansion, Flavor.Defense, Flavor.Offense, Flavor.Growth, Flavor.Science)
                        .joinToString(" ") { "${it.name}=${city.ai.flavor(it, allowNegative = true)}" }
            }
            commitToOperationUnit(picked)
            picked.construction
        } ?: return@timeThis
        setConstruction(chosen)
        if (chosen is PerpetualConstruction) fallThroughToOperationUnit()
    }

    /** CITY_BUILDABLE_UNIT_FOR_OPERATION chosen: CvCity::CommitToBuildingUnitForOperation, then the skipped-over count starts over */
    private fun commitToOperationUnit(picked: Buildable) {
        val offer = picked.slotOffer ?: return
        civInfo.ai.operations.commitToBuild(city, offer, picked.turns)
        civInfo.ai.military.numberOfTimesOpsBuildSkippedOver = 0
    }

    /**
     *  CvCityStrategyAI::ChooseProduction's switch has no break after CITY_BUILDABLE_PROCESS, so a chosen process falls into
     *  CITY_BUILDABLE_UNIT_FOR_OPERATION: the city also commits to the unit the next open slot of an operation needs (pushed in front of the
     *  process, which goes on after it), and the skipped-over count starts over whether or not there was one.
     */
    fun fallThroughToOperationUnit() {
        val offer = civInfo.ai.operations.nextUnitForOperationSlot(city)?.takeIf { isAllowed(it.unit) }
        if (offer != null && cityConstructions.canAddToQueue(offer.unit)) {
            cityConstructions.addToQueue(offer.unit, addToTop = true)
            civInfo.ai.operations.commitToBuild(city, offer, ProductionAi.turnsLeft(city, offer.unit))
        }
        civInfo.ai.military.numberOfTimesOpsBuildSkippedOver = 0
    }

    /**
     *  The wonder part of CvCityAI::AI_chooseProduction, for the wonder city only: it keeps a wonder underway unless [interruptWonders];
     *  if the next wonder desired cannot be built here any more the specializations are re-evaluated; otherwise it starts that wonder when a
     *  roll of 0..10 is at most max(WONDER, GREAT_PEOPLE) of personality and grand strategy.
     *  @return the wonder to build, null to choose normally
     */
    private fun chooseWonderForWonderCity(interruptWonders: Boolean): Building? {
        if (WonderProductionAi.wonderBuildCity(civInfo) != city) return null
        val current = cityConstructions.getCurrentConstruction()
        if (!interruptWonders && current is Building && WonderProductionAi.isWonder(current)) return current
        val state = civInfo.ai.citySpecialization
        val nextWonder = state.nextWonderDesired?.let { civInfo.gameInfo.ruleset.buildings[it] }
        if (nextWonder == null || !nextWonder.isBuildable(cityConstructions)) {
            state.specializationsDirty = true
            return null
        }
        val flavor = maxOf(civInfo.ai.personalityAndGrandStrategy(Flavor.Wonder), civInfo.ai.personalityAndGrandStrategy(Flavor.GreatPeople))
        return if (civInfo.ai.random("WonderRoll" + city.id).nextInt(11) <= flavor) nextWonder else null
    }

    private fun withAiChoiceWeight(construction: INonPerpetualConstruction, weight: Int): Int =
        if (weight <= 0) weight else (weight * construction.getWeightForAiDecision(GameContext(civInfo, city))).toInt()

    /**
     *  The candidates of CvCityStrategyAI::ChooseProduction (m_BuildablesPrecheck), each with its weight, only weights above 0:
     *  - buildings (not projects): [ProductionAi.buildingWeight]; a diplomatic-voting building only when going for the diplomatic victory with
     *    the gold to buy the missing votes; in puppets 0 for wonders, land military training and resource-consuming buildings, doubled without
     *    maintenance;
     *  - not in puppets: units ([ProductionAi.unitWeight]; settlers 0 under ECONOMICAISTRATEGY_ENOUGH_EXPANSION or without a good settle
     *    area); projects ([ProductionAi.projectWeight]; Vox Populi skips the limited ones in puppets, which BNW's all are);
     *  - processes, when the city makes at least 5 production before the modifiers of what it builds (getRawProductionPerTurnTimes100 at
     *    least 500) or there is no other candidate, 1 turn each.
     *  Turns left are those of CvCity::getProductionTurnsLeft ([ProductionAi.turnsLeft]): the production the city would make while building that
     *  very item, with the item's own production modifiers, rounded as the game adds it each turn.
     */
    fun getBuildables(): List<Buildable> {
        val rules = civInfo.gameInfo.ruleset.ai
        val ruleset = civInfo.gameInfo.ruleset
        val candidates = ArrayList<Buildable>()
        addOperationUnit(candidates)
        addArmyUnit(candidates)

        for (building in ruleset.buildings.values) {
            if (ProductionAi.isProject(rules, building)) {
                if (city.isPuppet || !isAllowed(building) || !building.isBuildable(cityConstructions)) continue
                val weight = withAiChoiceWeight(building, ProductionAi.projectWeight(city, building))
                if (weight > 0) candidates.add(Buildable(building, weight, ProductionAi.turnsLeft(city, building)))
                continue
            }
            var weight = ProductionAi.buildingWeight(city, building)
            // Only weights above 0 are candidates, and the adjustments below never raise a weight from 0: skip the costly buildable test
            if (weight <= 0 || !isAllowed(building) || !building.isBuildable(cityConstructions)) continue
            if (WonderProductionAi.isDiplomaticVoting(building) && !WonderProductionAi.isWorthBuildingUnitedNations(civInfo)) weight = 0
            if (city.isPuppet) {
                if (building.isAnyWonder()) weight = 0
                if (givesLandFreeExperience(building)) weight = 0
                if (building.maintenance == 0) weight *= 2
                if (building.getResourceRequirementsPerTurn(city.state).isNotEmpty()) weight = 0
            }
            weight = withAiChoiceWeight(building, weight)
            if (weight > 0) candidates.add(Buildable(building, weight, ProductionAi.turnsLeft(city, building)))
        }

        if (!city.isPuppet) {
            val enoughSettlers = civInfo.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")
            var goodSettleAreas = -1
            for (unit in ruleset.units.values) {
                var weight = ProductionAi.unitWeight(city, unit)
                if (weight <= 0 || !isAllowed(unit) || !unit.isBuildable(cityConstructions)) continue
                val unitAi = UnitAiTypes.defaultOf(unit, rules)
                if (unitAi == UnitAiType.SETTLE) {
                    if (enoughSettlers) weight = 0
                    else {
                        if (goodSettleAreas < 0)
                            goodSettleAreas = SettleAreas.bestSettleAreas(civInfo, rules.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY)).count
                        if (goodSettleAreas == 0) weight = 0
                    }
                }
                weight = withAiChoiceWeight(unit, weight)
                if (weight > 0) candidates.add(Buildable(unit, weight, ProductionAi.turnsLeft(city, unit)))
            }
        }

        if (rawProductionTimes100() >= 500 || candidates.isEmpty()) {
            for (process in PerpetualConstruction.perpetualConstructionsMap.values) {
                if (process == PerpetualConstruction.Idle || !process.isBuildable(cityConstructions)) continue
                if (process is PerpetualConstruction.StatConversion && process.stat == Stat.Science && civInfo.tech.allTechsAreResearched()) continue
                val weight = ProductionAi.processWeight(city, process.name)
                if (weight > 0) candidates.add(Buildable(process, weight, 1))
            }
        }
        return candidates
    }

    /**
     *  The first block of CvCityStrategyAI::ChooseProduction: the unit CvCity::GetUnitForOperation names for the next open slot of an operation
     *  on this landmass, weighted AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT + AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER × (OFFENSE of
     *  personality and grand strategy + the times such a build was skipped over), x5 in an operation unit provider city, plus the weight
     *  the unit has on its own; a settler is no candidate under ENOUGH_EXPANSION or without a good settle area. Offering it counts as one
     *  more skip (BumpNumberOfTimesOpsBuildSkippedOver); choosing it resets the count.
     */
    private fun addOperationUnit(candidates: MutableList<Buildable>) {
        val offer = civInfo.ai.operations.nextUnitForOperationSlot(city) ?: return
        val unit = offer.unit
        if (!isAllowed(unit)) return
        val rules = civInfo.gameInfo.ruleset.ai
        val military = civInfo.ai.military
        var weight = rules.define(AiDefine.AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT)
        weight += rules.define(AiDefine.AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER) *
            (civInfo.ai.personalityAndGrandStrategy(Flavor.Offense) + military.numberOfTimesOpsBuildSkippedOver)
        if (city.ai.specializationDef()?.isOperationUnitProvider == true) weight *= 5
        weight += ProductionAi.unitWeight(city, unit)
        if (UnitAiTypes.defaultOf(unit, rules) == UnitAiType.SETTLE) {
            if (civInfo.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")) weight = 0
            else if (SettleAreas.bestSettleAreas(civInfo, rules.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY)).count == 0) weight = 0
        }
        if (weight <= 0) return
        candidates.add(Buildable(unit, weight, ProductionAi.turnsLeft(city, unit), offer))
        military.numberOfTimesOpsBuildSkippedOver++
    }

    /**
     *  The second block of CvCityStrategyAI::ChooseProduction: the unit CvMilitaryAI::GetUnitForArmy names while an attack army is being
     *  built, weighted AI_CITYSTRATEGY_ARMY_UNIT_BASE_WEIGHT + AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER × OFFENSE (personality and
     *  grand strategy) × max(1, difficulty index - 5) plus the weight the unit has on its own.
     */
    private fun addArmyUnit(candidates: MutableList<Buildable>) {
        val unit = MilitaryOperations.unitForArmy(city) ?: return
        if (!isAllowed(unit)) return
        val rules = civInfo.gameInfo.ruleset.ai
        val difficultyIndex = civInfo.gameInfo.ruleset.difficulties.keys.indexOf(civInfo.gameInfo.difficulty).coerceAtLeast(0)
        var weight = rules.define(AiDefine.AI_CITYSTRATEGY_ARMY_UNIT_BASE_WEIGHT)
        weight += rules.define(AiDefine.AI_CITYSTRATEGY_OPERATION_UNIT_FLAVOR_MULTIPLIER) *
            civInfo.ai.personalityAndGrandStrategy(Flavor.Offense) * maxOf(1, difficultyIndex - 5)
        weight += ProductionAi.unitWeight(city, unit)
        if (weight > 0) candidates.add(Buildable(unit, weight, ProductionAi.turnsLeft(city, unit), forOperation = true))
    }

    /** CvCity::getRawProductionPerTurnTimes100: the production rate x 100 with the city's general modifiers, not those of what it builds */
    private fun rawProductionTimes100(): Int {
        val base = city.cityStats.baseStatTree.totalStats.production
        val modifier = 100 + city.cityStats.statPercentBonusTree.totalStats.production
        return (base * 100 * modifier / 100).toInt()
    }

    /** CvBuildingEntry::GetDomainFreeExperience(DOMAIN_LAND) > 0 */
    private fun givesLandFreeExperience(building: Building): Boolean {
        val units = civInfo.gameInfo.ruleset.units.values
        return building.getMatchingUniques(UniqueType.UnitStartingExperience, GameContext.IgnoreConditionals)
            .any { unique -> unique.params[1].toInt() > 0 && units.any { it.isLandUnit && it.isMilitary && it.matchesFilter(unique.params[0]) } }
    }

    /**
     *  The selection of CvCityStrategyAI::ChooseProduction (Vox Populi):
     *  - the sanity checks: the units CheckUnitBuildSanity refuses ([UnitProductionAi.isRefused]) go (the sanity weights of the units,
     *    buildings, projects and processes are not ported yet); with nothing left, all [candidates] at their weight as they are;
     *  - each weight divided by its time penalty ([WonderProductionAi.reweightByTurnsLeft], ReweightByDuration), best first;
     *  - among those at least half as good as the best, in order: a project a victory of this game needs, or what the city is building
     *    (a building only without [interruptBuildings], never a process), is kept;
     *  - else a weighted pick among those within HandicapInfos.CityProductionChoiceCutoffThreshold % of the best (Vox Populi: 90 at every
     *    difficulty; [WeightedChoice.chooseAbovePercentThreshold]). BNW has no defense process, which VP would take first.
     *  Null without candidates.
     */
    fun choose(candidates: List<Buildable>, interruptBuildings: Boolean = false): Buildable? {
        if (candidates.isEmpty()) return null
        val sane = candidates.filter { candidate ->
            val unit = candidate.construction as? BaseUnit
            unit == null || !UnitProductionAi.isRefused(city, unit, candidate.forOperation)
        }
        for (candidate in sane) candidate.weight = WonderProductionAi.reweightByTurnsLeft(civInfo, candidate.weight, candidate.turns)
        val options = sane.ifEmpty { candidates }.sortedByDescending { it.weight }
        val best = options.first().weight
        val current = cityConstructions.getCurrentConstruction()
        val rules = civInfo.gameInfo.ruleset.ai
        for (option in options) {
            if (option.weight < best / 2) break
            val construction = option.construction
            if (construction is PerpetualConstruction) continue
            val isProject = construction is Building && ProductionAi.isProject(rules, construction)
            if (isProject && isVictoryProject(construction as Building)) return option
            if (construction == current && (construction !is Building || isProject || !interruptBuildings)) return option
        }
        val cutoff = rules.handicapValue(civInfo.gameInfo.getDifficulty().name, PRODUCTION_CUTOFF) ?: DEFAULT_PRODUCTION_CUTOFF
        return options[WeightedChoice.chooseAbovePercentThreshold(options.map { it.weight }, cutoff, civInfo.ai.random("ChooseProduction" + city.id))]
    }

    /** CvProjectEntry::GetVictoryPrereq of a victory this game has: a "Build [project]" milestone of an enabled victory */
    private fun isVictoryProject(project: Building): Boolean {
        val gameInfo = civInfo.gameInfo
        return gameInfo.ruleset.victories.values.any { victory ->
            victory.name in gameInfo.gameParameters.victoryTypes &&
                victory.milestoneObjects.any { it.type == MilestoneType.BuiltBuilding && it.params.firstOrNull() == project.name }
        }
    }

    private fun setConstruction(chosen: IConstruction) {
        // Do not notify while in resistance (you can't do anything about it) - still notify for puppets ("annex already!")
        // Also do not notify while city screen open - might be a buying spree, not helpful
        // Also do not notify when the decision hasn't changed - duh!
        val noNotification = city.isInResistance()
            || civInfo.isAI() // Optimization: addNotification filters anyway, but saves a string builder and a CityAction instantiation
            || cityConstructions.currentConstructionName() == chosen.name
            || UncivGame.Current.screen is CityScreen
        cityConstructions.setCurrentConstruction(chosen.name)
        if (noNotification) return

        civInfo.addNotification(
            "[${city.name}] has started working on [${chosen.name}]",
            CityAction.withLocation(city),
            NotificationCategory.Production,
            NotificationIcon.Construction
        )
    }

    companion object {
        private const val PRODUCTION_CUTOFF = "CityProductionChoiceCutoffThreshold"
        /** HandicapInfos.CityProductionChoiceCutoffThreshold of Vox Populi (every difficulty), for a ruleset without the column */
        private const val DEFAULT_PRODUCTION_CUTOFF = 90
    }
}
