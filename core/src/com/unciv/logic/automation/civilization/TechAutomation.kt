package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.VictoryPursuitAi
import com.unciv.logic.automation.strategy.BuildingValueAi
import com.unciv.logic.automation.strategy.EconomicMilitaryStrategyTests
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 *  The research AI of the original (CvTechAI with the AI parts of CvPlayerTechs, CvPlayerAI::AI_chooseResearch / AI_chooseFreeTech), the weights
 *  as Vox Populi builds them (CvPlayerTechs::FlavorUpdate, AddFlavorAsStrategies, SetLocalePriorities, SetGSPriorities, CvTechAI::AddFlavorWeights).
 *
 *  Each tech has a weight ([com.unciv.logic.civilization.managers.TechManager.aiTechWeights]): Technology_Flavors × the player's flavors
 *  (current flavors blended with personality + grand strategy by game progress), plus a priority for techs unlocking our unique items, one for
 *  the resources nearby it lets us improve and one for what the grand strategies and the leader's traits ask of the buildings and units it
 *  unlocks, with its share passed down to the prerequisites. The weights are rebuilt when the player's strategies change, as the original
 *  rebuilds them on each flavor change. A research choice divides the weights of the researchable techs by a power of the turns they take
 *  and picks weighted-random among the best few (CvTechAI::ChooseNextTech and ReweightByCost are still Brave New World's; VP's choose
 *  above a percent threshold of the top weight and scale a free tech's weights by its cost).
 */
object TechAutomation {

    /** HandicapInfos.TechNumOptionsConsidered of the original's Prince and higher handicaps, for rulesets without that data */
    private const val DEFAULT_NUM_OPTIONS_CONSIDERED = 2
    /** A reweighted tech weight is at most this: the original's cast of a huge double to int is undefined (a negative weight on x86), and the sum of weights must fit an Int */
    private const val MAX_REWEIGHTED_WEIGHT = Int.MAX_VALUE / 64
    /** Distance from the city within which unowned tiles count for [localeTechPriorities] (literal in Vox Populi's CvPlayerTechs::SetLocalePriorities) */
    private const val LOCALE_UNOWNED_TILE_RANGE = 4
    /** AddFlavorWeights adds this to a tech that allows embarking when the island start strategy is active */
    private const val EMBARKING_ISLAND_START_BONUS = 10
    /** SetGSPriorities: a city founder's tech gets max(1, this − the number of cities) */
    private const val FOUNDER_CITIES_BASE = 10
    /** AIConstructPercent and AITrainPercent of a handicap the rules do not describe (Prince) */
    private const val DEFAULT_AI_PERCENT = 100

    // The flavor sets of SetGSPriorities, by the focus or trait category that counts them
    private val DIPLOMACY_FLAVORS = setOf(Flavor.Diplomacy, Flavor.Gold, Flavor.Espionage)
    private val CONQUEST_FLAVORS = setOf(Flavor.Offense, Flavor.MilitaryTraining, Flavor.Mobile, Flavor.Ranged, Flavor.Defense, Flavor.Air)
    private val CULTURE_FLAVORS = setOf(Flavor.Culture, Flavor.GreatPeople, Flavor.Wonder)
    private val SCIENCE_FLAVORS = setOf(Flavor.Science, Flavor.Spaceship)
    private val SMALLER_FLAVORS = setOf(Flavor.Growth, Flavor.NavalGrowth, Flavor.CityDefense)
    private val RELIGIOUS_FLAVORS = setOf(Flavor.Religion, Flavor.GreatPeople)
    private val EXPANSIONIST_FLAVORS = setOf(Flavor.Infrastructure, Flavor.Expansion)

    /** Per-ruleset lookups: the techs by index (ruleset order), their XML-ordered prerequisites, flavors, eras and unlocks, and the improvement that lets a resource be used */
    private class TechData(val ruleset: Ruleset) {
        val techs: Array<Technology> = ruleset.technologies.values.toTypedArray()
        val index = HashMap<String, Int>(techs.size * 2)
        /** CvTechEntry::GetPrereqAndTechs: in the XML order where AI.json has it for the same set of prerequisites */
        val prereqs: Array<IntArray>
        /** Technology_Flavors by tech index, vectors by [Flavor.ordinal] */
        val flavors: Array<IntArray> = Array(techs.size) { ruleset.ai.flavorsOf(techs[it]) }
        /** CvTechEntry::GetEra: the number of the tech's era */
        val eraNumber = IntArray(techs.size) { ruleset.eras[techs[it].column?.era]?.eraNumber ?: 0 }
        /** CvTechEntry::IsRepeat: the tech can be researched again and again */
        val repeatable = BooleanArray(techs.size) { techs[it].isContinuallyResearchable() }
        /** CvTechEntry::IsAllowsEmbarking */
        val allowsEmbarking = BooleanArray(techs.size) { techs[it].hasUnique(UniqueType.LandUnitEmbarkation, GameContext.IgnoreConditionals) }
        /** The buildings (CvBuildingEntry::GetPrereqAndTech) and units (CvUnitEntry::GetPrereqAndTech) each tech is the required tech of */
        val buildings: Array<List<Building>>
        val units: Array<List<BaseUnit>>
        /** Resource name -> the first improvement that connects it (the first matching build of SetLocalePriorities), null if none; filled on first use */
        private val connectingImprovements = HashMap<String, TileImprovement?>()

        fun connectingImprovementOf(resource: TileResource): TileImprovement? = connectingImprovements.getOrPut(resource.name) {
            ruleset.tileImprovements.values.firstOrNull { resource.isImprovedBy(it) }
        }

        /** The tech of the first improvement that connects [resource], -1 if none */
        fun connectingTechOf(resource: TileResource): Int = connectingImprovementOf(resource)?.techRequired?.let { index[it] } ?: -1

        init {
            techs.forEachIndexed { i, tech -> index[tech.name] = i }
            val xmlOrder = ruleset.ai.techPrereqs.associateBy { it.name }
            prereqs = Array(techs.size) { i ->
                val tech = techs[i]
                val ordered = xmlOrder[tech.name]?.prereqs
                    ?.takeIf { it.size == tech.prerequisites.size && tech.prerequisites.containsAll(it) }
                    ?: tech.prerequisites
                ordered.mapNotNull { index[it] }.toIntArray()
            }
            val buildingsOf = Array(techs.size) { ArrayList<Building>() }
            for (building in ruleset.buildings.values) index[building.requiredTech ?: continue]?.let { buildingsOf[it].add(building) }
            buildings = Array(techs.size) { buildingsOf[it] }
            val unitsOf = Array(techs.size) { ArrayList<BaseUnit>() }
            for (unit in ruleset.units.values) index[unit.requiredTech ?: continue]?.let { unitsOf[it].add(unit) }
            units = Array(techs.size) { unitsOf[it] }
        }
    }

    @Volatile private var cachedData: TechData? = null

    private fun data(ruleset: Ruleset): TechData {
        val cached = cachedData
        if (cached != null && cached.ruleset === ruleset) return cached
        return TechData(ruleset).also { cachedData = it }
    }

    //region Weights (CvPlayerTechs::FlavorUpdate, CvTechAI::AddFlavorWeights)

    /**
     *  CvPlayerTechs::FlavorUpdate is called on every change of the player's flavors, i.e. whenever an economic or military strategy is
     *  adopted or dropped (CvFlavorManager::ChangeFlavors), and once at game start. Called each turn after the strategy loops
     *  ([com.unciv.logic.automation.strategy.CivAi.doTurn]): rebuilds the weights when the active strategies differ from those they were built with.
     */
    fun updateFlavorWeights(civ: Civilization) {
        val key = activeStrategiesKey(civ)
        if (civ.tech.aiTechWeightsStrategies != key) rebuildWeights(civ, key)
    }

    private fun activeStrategiesKey(civ: Civilization): String {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val key = StringBuilder()
        for (def in rules.economicStrategies) if (ai.isEconomicStrategyActive(def.name)) key.append(def.name).append(';')
        for (def in rules.militaryStrategies) if (ai.isMilitaryStrategyActive(def.name)) key.append(def.name).append(';')
        return key.toString()
    }

    /** CvPlayerTechs::FlavorUpdate: SetLocalePriorities, SetGSPriorities, then AddFlavorAsStrategies(TECH_WEIGHT_PROPAGATION_PERCENT) into the stored weights */
    fun rebuildWeights(civ: Civilization, strategiesKey: String = activeStrategiesKey(civ)) {
        val data = data(civ.gameInfo.ruleset)
        val weights = computeWeights(civ, data)
        val stored = civ.tech.aiTechWeights
        stored.clear()
        for (i in data.techs.indices) stored[data.techs[i].name] = weights[i]
        civ.tech.aiTechWeightsStrategies = strategiesKey
    }

    /**
     *  CvPlayerTechs::AddFlavorAsStrategies (Vox Populi): the current flavors (negative ones read as 0, CvFlavorManager::GetLatestFlavorValue)
     *  are scaled so the biggest is 10, then blended with CvGrandStrategyAI::GetPersonalityAndGrandStrategy (main flavors boosted) by
     *  p = elapsed turns × 1000 / the estimated end turn of the difficulty ([gameProgressFactor]) kept within 100..900:
     *  (current × (1000 − p) + personality × p) / 1000. A blend under 10 gets (10 − blend) / 4 more, / 8 once p is over 500, so that
     *  every flavor counts. Each positive blend goes through CvTechAI::AddFlavorWeights.
     */
    private fun computeWeights(civ: Civilization, data: TechData): IntArray {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val weights = IntArray(data.techs.size)
        val civPriority = civTechPriorities(civ, data)
        val localePriority = localeTechPriorities(civ, data)
        val gsPriority = gsTechPriorities(civ, data)
        val flavors = ai.flavors()
        var biggest = -1000
        for (value in flavors) biggest = max(biggest, max(0, value))
        val progress = gameProgressFactor(civ)
        val islandStart = ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.ISLAND_START)
        for (flavor in Flavor.entries) {
            val f = flavor.ordinal
            val current = max(0, flavors[f]) * 10 / max(1, biggest)
            var value = (current * (1000 - progress) + ai.personalityAndGrandStrategy(flavor, boostMainFlavor = true) * progress) / 1000
            if (value < 10) value += (10 - value) / (if (progress > 500) 8 else 4)
            if (value <= 0) continue
            addFlavorWeights(civ, data, weights, f, value, civPriority, localePriority, gsPriority, islandStart, rules)
        }
        return weights
    }

    /**
     *  CvTechAI::AddFlavorWeights (Vox Populi) for one flavor: each tech we do not know and that is at most NUM_OR_TECH_PREREQS eras ahead gets
     *  its flavor × [flavorValue] plus its civilization, locale and grand strategy priorities (BNW multiplied the first two), 10 more if it
     *  allows embarking and we start on an island, and passes that on to its prerequisites ([propagateWeights]).
     */
    @Suppress("LongParameterList")
    private fun addFlavorWeights(
        civ: Civilization, data: TechData, weights: IntArray, flavor: Int, flavorValue: Int,
        civPriority: IntArray, localePriority: IntArray, gsPriority: IntArray, islandStart: Boolean, rules: AiRuleset
    ) {
        val propagationPercent = rules.define(AiDefine.TECH_WEIGHT_PROPAGATION_PERCENT)
        val propagationLevels = rules.define(AiDefine.TECH_WEIGHT_PROPAGATION_LEVELS)
        val numOrPrereqs = rules.define(AiDefine.NUM_OR_TECH_PREREQS)
        val era = civ.getEraNumber()
        for (t in data.techs.indices) {
            if (civ.tech.isResearched(data.techs[t].name)) continue
            if (data.eraNumber[t] - era > numOrPrereqs) continue
            var weight = data.flavors[t][flavor] * flavorValue + civPriority[t] + localePriority[t] + gsPriority[t]
            if (islandStart && data.allowsEmbarking[t]) weight += EMBARKING_ISLAND_START_BONUS
            propagateWeights(data, weights, t, weight, propagationPercent, 0, propagationLevels)
        }
    }

    /** The p of [computeWeights]: elapsed turns × 1000 over the estimated end turn of the game speed, shortened by the difficulty, within 100..900 */
    private fun gameProgressFactor(civ: Civilization): Int {
        val rules = civ.gameInfo.ruleset.ai
        val difficulty = civ.gameInfo.getDifficulty().name
        val construct = rules.handicapValue(difficulty, "AIConstructPercent") ?: DEFAULT_AI_PERCENT
        val train = rules.handicapValue(difficulty, "AITrainPercent") ?: DEFAULT_AI_PERCENT
        val difficultyBonus = max(1, 200 - (construct + train) / 2)
        val estimatedTurns = max(1, civ.gameInfo.speed.numTotalTurns() * 90 / difficultyBonus)
        return (civ.gameInfo.turns * 1000 / estimatedTurns).coerceIn(100, 900)
    }

    /**
     *  CvTechAI::PropagateWeights (Vox Populi): the weight is added to the tech itself (a sum never goes below 0), then, unless the call is at
     *  TECH_WEIGHT_PROPAGATION_LEVELS or deeper, TECH_WEIGHT_PROPAGATION_PERCENT of it, divided evenly among the prerequisites (not for a
     *  repeatable tech), goes to each prerequisite in turn. As in the original, the depth count is post-incremented per prerequisite (the first
     *  prerequisite recurses at the same level, the second one level deeper, ...). All prerequisites count (NUM_AND_TECH_PREREQS, 6, is more
     *  than a tech here has).
     */
    private fun propagateWeights(data: TechData, weights: IntArray, tech: Int, weight: Int, percent: Int, level: Int, maxLevels: Int) {
        if (weight == 0) return
        weights[tech] = max(0, weights[tech] + weight)
        if (level >= maxLevels) return
        val prereqs = data.prereqs[tech]
        if (prereqs.isEmpty()) return
        var propagated = weight * percent / 100
        if (!data.repeatable[tech]) propagated /= prereqs.size
        var nextLevel = level
        for (prereq in prereqs) {
            propagateWeights(data, weights, prereq, propagated, percent, nextLevel, maxLevels)
            nextLevel++
        }
    }

    /**
     *  CvPlayerTechs::Reset (CivTechPriority, Vox Populi): for a major civ, 1 plus TECH_PRIORITY_UNIQUE_ITEM on the tech of each of its unique
     *  buildings, units and improvements, and TECH_PRIORITY_MAYA_CALENDAR_BONUS on the tech of a Maya calendar trait. Not ported: the same
     *  bonus for the building whose construction triggers the ideology choice, and the bonus for the free-unit and free-building techs of
     *  Venice-like traits (CvTraitEntry's prerequisite techs).
     */
    private fun civTechPriorities(civ: Civilization, data: TechData): IntArray {
        val priorities = IntArray(data.techs.size) { 1 }
        if (!civ.isMajorCiv) return priorities
        val rules = civ.gameInfo.ruleset.ai
        val uniqueItem = rules.define(AiDefine.TECH_PRIORITY_UNIQUE_ITEM)
        fun add(techName: String?, amount: Int) {
            val index = data.index[techName ?: return] ?: return
            priorities[index] += amount
        }
        for (building in civ.cache.uniqueBuildings) add(building.requiredTech, uniqueItem)
        for (unit in civ.cache.uniqueUnits) add(unit.requiredTech, uniqueItem)
        for (improvement in civ.cache.uniqueImprovements) add(improvement.techRequired, uniqueItem)
        val mayaCalendar = rules.define(AiDefine.TECH_PRIORITY_MAYA_CALENDAR_BONUS)
        for (unique in civ.nation.getMatchingUniques(UniqueType.MayanGainGreatPerson, GameContext.IgnoreConditionals))
            add(unique.params[1], mayaCalendar)
        return priorities
    }

    /**
     *  CvPlayerTechs::SetLocalePriorities (Vox Populi): for each land tile with a resource we can see, among the tiles our cities could work
     *  that we own or that are unowned within 4 of the city, whose connecting improvement we cannot build yet, the tech of that improvement gets
     *  (3 − distance to the city) + 1 when we have none of the resource + 1 when the improvement can stand on the tile + 1 for not being able
     *  to build it, added to its 1. (The original adds the first terms for tiles whose improvement we can build too; the tech is known then and
     *  never weighed.) One more when our share of the copies of the resource on the map is above the strategic and at most the global monopoly threshold
     *  (V:CvTechClasses.cpp:1128-1131; read only where the ruleset has monopolies, so the rules without them keep their numbers).
     */
    private fun localeTechPriorities(civ: Civilization, data: TechData): IntArray {
        val priorities = IntArray(data.techs.size) { 1 }
        val range = civ.gameInfo.ruleset.modOptions.constants.cityWorkRange
        val monopolyConstants = civ.gameInfo.ruleset.modOptions.constants.monopoly
        for (city in civ.cities) {
            val center = city.getCenterTile()
            center.forEachTileInDistance(range) { tile ->
                val owner = tile.getOwner()
                val distance = tile.aerialDistanceTo(center)
                if (owner != civ && (owner != null || distance > LOCALE_UNOWNED_TILE_RANGE)) return@forEachTileInDistance
                if (tile.isWater) return@forEachTileInDistance
                val resource = tile.tileResource ?: return@forEachTileInDistance
                if (!civ.tech.isRevealed(resource)) return@forEachTileInDistance
                val improvement = data.connectingImprovementOf(resource) ?: return@forEachTileInDistance
                val tech = data.connectingTechOf(resource)
                if (tech < 0 || civ.tech.isResearched(data.techs[tech].name)) return@forEachTileInDistance
                var multiplier = 3 - distance
                if (civ.getResourceAmount(resource) <= 0) multiplier++
                if (data.ruleset.hasMonopolyResources) {
                    // "somehow close on monopolies?" V:CvTechClasses.cpp:1128-1131
                    val share = civ.monopolies.percent(resource)
                    if (share > monopolyConstants.strategicThreshold && share <= monopolyConstants.globalThreshold) multiplier++
                }
                if (tile.improvementFunctions.canImprovementBeBuiltHere(improvement, resourceIsVisible = true)) multiplier++
                priorities[tech] += multiplier + 1
            }
        }
        return priorities
    }

    /**
     *  CvPlayerTechs::SetGSPriorities (Vox Populi): 1 for a major civ, plus one for each flavor of the tech that is among those of a focus
     *  the civilization has - diplomacy: Diplomacy, Gold, Espionage; conquest: the military flavors; culture: Culture, Great People, Wonder;
     *  science: Science, Spaceship - and for each of its leader's trait categories - Smaller: Growth, Naval Growth, City Defense; Religious:
     *  Religion, Great People; Expansionist: Infrastructure, Expansion. A focus is a victory the civilization is serious about and going
     *  for, the trait category of that victory, or being close to that victory. One more for each building the tech unlocks that
     *  [BuildingValueAi.grandStrategyValue] values (asked without a city), and for each unit it unlocks: [max(1, 10 − cities)] for a city
     *  founder, one for a combat unit with the conquest focus, a spaceship part with the science focus, a trade unit with the diplomacy
     *  focus or a unit that earns gold from a trade mission, an archaeologist with the culture focus. Techs we know are left at 1: they are
     *  not weighed. Not ported: the unit flavors of VP's AI_UNIT_PRODUCTION option, messengers (VP's own units).
     */
    private fun gsTechPriorities(civ: Civilization, data: TechData): IntArray {
        val priorities = IntArray(data.techs.size) { 1 }
        if (civ.isCityState) return priorities
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val serious = VictoryPursuitAi.isSeriousAboutVictory(civ)
        val conquestFocus = serious && ai.isGoingForWorldConquest() || ai.hasTraitCategory(TraitCategory.Warmonger) ||
            VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        val diploFocus = serious && ai.isGoingForDiploVictory() || ai.hasTraitCategory(TraitCategory.Diplomat) ||
            VictoryProgressAi.isCloseToDiploVictory(civ, rules)
        val scienceFocus = serious && ai.isGoingForSpaceshipVictory() || ai.hasTraitCategory(TraitCategory.Nerd) ||
            VictoryProgressAi.isCloseToSpaceshipVictory(civ, rules)
        val cultureFocus = serious && ai.isGoingForCultureVictory() || ai.hasTraitCategory(TraitCategory.Tourism) ||
            VictoryProgressAi.isCloseToCultureVictory(civ, rules)
        val smaller = ai.hasTraitCategory(TraitCategory.Smaller)
        val religious = ai.hasTraitCategory(TraitCategory.Religious)
        val expansionist = ai.hasTraitCategory(TraitCategory.Expansionist)
        val foundersBonus = max(1, FOUNDER_CITIES_BASE - civ.cities.size)
        for (t in data.techs.indices) {
            if (civ.tech.isResearched(data.techs[t].name)) continue
            for (flavor in Flavor.entries) {
                if (data.flavors[t][flavor.ordinal] <= 0) continue
                if (diploFocus && flavor in DIPLOMACY_FLAVORS) priorities[t]++
                if (conquestFocus && flavor in CONQUEST_FLAVORS) priorities[t]++
                if (cultureFocus && flavor in CULTURE_FLAVORS) priorities[t]++
                if (scienceFocus && flavor in SCIENCE_FLAVORS) priorities[t]++
                if (smaller && flavor in SMALLER_FLAVORS) priorities[t]++
                if (religious && flavor in RELIGIOUS_FLAVORS) priorities[t]++
                if (expansionist && flavor in EXPANSIONIST_FLAVORS) priorities[t]++
            }
            for (building in data.buildings[t])
                if (BuildingValueAi.grandStrategyValue(civ, null, building) > 0) priorities[t]++
            for (unit in data.units[t]) {
                val unitAi = UnitAiTypes.defaultOf(unit, rules)
                if (unit.isCityFounder()) priorities[t] += foundersBonus
                if (conquestFocus && (unit.strength > 0 || unit.rangedStrength > 0)) priorities[t]++
                if (scienceFocus && unit.hasUnique(UniqueType.SpaceshipPart)) priorities[t]++
                if (diploFocus && unitAi == UnitAiType.TRADE_UNIT || unit.hasUnique(UniqueType.CanTradeWithCityStateForGoldAndInfluence)) priorities[t]++
                if (cultureFocus && unitAi == UnitAiType.ARCHAEOLOGIST) priorities[t]++
            }
        }
        return priorities
    }

    /** CvTechAI::GetWeight */
    fun weightOf(civ: Civilization, techName: String): Int = civ.tech.aiTechWeights[techName] ?: 0

    /**
     *  CvTechAI::GetTechRatio: [civ]'s position among the living major civilizations ranked by number of known technologies (stable in the
     *  order of the civilizations), 0 for the first and 1 for the last; 0 when [civ] is alone.
     */
    fun techRatio(civ: Civilization): Float {
        val ranked = civ.gameInfo.getAliveMajorCivs().sortedByDescending { it.tech.researchedTechnologies.size }
        if (ranked.size < 2) return 0f
        return ranked.indexOf(civ).coerceAtLeast(0) / (ranked.size - 1).toFloat()
    }
    //endregion

    //region Choice (CvTechAI::ChooseNextTech, CvPlayerAI::AI_chooseResearch / AI_chooseFreeTech)

    /**
     *  CvPlayerAI::AI_chooseFreeTech for each pending free tech (clears the research queue, then takes CvTechAI::ChooseNextTech(bFreeTech)),
     *  then CvPlayerAI::AI_chooseResearch when nothing is being researched. A free tech is chosen here, on the AI's turn, where the original
     *  chooses it the moment it is granted; when no tech has weight it stays pending (the original's is lost).
     *  The original's adoption of a teammate's research does not apply: Unciv has no teams.
     */
    fun chooseTechToResearch(civ: Civilization) {
        if (civ.tech.freeTechs > 0) {
            val random = civ.ai.random("TechAutomation.freeTech")
            while (civ.tech.freeTechs > 0) {
                val tech = chooseNextTech(civ, freeTech = true, random) ?: break
                civ.tech.techsToResearch.clear()
                civ.tech.getFreeTechnology(tech.name)
            }
        }
        if (civ.tech.techsToResearch.isEmpty()) {
            val tech = chooseNextTech(civ, freeTech = false, civ.ai.random("TechAutomation.research")) ?: return
            civ.tech.techsToResearch.add(tech.name)
        }
    }

    /**
     *  CvTechAI::ChooseNextTech: CvWeightedVector::ChooseFromTopChoices among the best HandicapInfos.TechNumOptionsConsidered (of the game's
     *  handicap) of [researchCandidates]. Null when no tech has weight.
     */
    fun chooseNextTech(civ: Civilization, freeTech: Boolean, random: Random): Technology? {
        val candidates = researchCandidates(civ, freeTech)
        if (candidates.sumOf { it.second.toLong() } <= 0L) return null
        val numOptions = civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, "TechNumOptionsConsidered")
            ?: DEFAULT_NUM_OPTIONS_CONSIDERED
        val top = min(numOptions, candidates.size)
        var topTotal = 0
        for (i in 0 until top) topTotal += candidates[i].second
        var roll = random.nextInt(topTotal)
        var chosen = candidates[top - 1].first
        for (i in 0 until top) {
            roll -= candidates[i].second
            if (roll < 0) {
                chosen = candidates[i].first
                break
            }
        }
        AiDecisionLog.log(civ) {
            "${if (freeTech) "free tech" else "researches"} ${chosen.name} of " +
                candidates.take(top).joinToString { "${it.first.name} ${it.second}" } +
                " (science ${civ.stats.statsForNextTurn.science.toInt()})"
        }
        return chosen
    }

    /**
     *  CvTechAI::ChooseNextTech's m_ResearchableTechs: the techs we can research with their weights, divided by the research time
     *  ([ReweightByCost][reweightByCost]) unless the tech is free, sorted by weight (highest first; ties keep the ruleset order).
     */
    fun researchCandidates(civ: Civilization, freeTech: Boolean): List<Pair<Technology, Int>> {
        val data = data(civ.gameInfo.ruleset)
        if (civ.tech.aiTechWeightsStrategies == null) updateFlavorWeights(civ)
        val rules = civ.gameInfo.ruleset.ai
        val baseMod = rules.defineFloat(AiDefine.AI_RESEARCH_WEIGHT_BASE_MOD).toDouble()
        val modPerTurn = rules.defineFloat(AiDefine.AI_RESEARCH_WEIGHT_MOD_PER_TURN_LEFT).toDouble()
        // CvTechAI::ReweightByCost (SDK source, April 2014 balance patch): with lots of science overflow the AI wants an expensive tech
        val needExpensiveTechs = civ.tech.getOverflowScience() * 100 > (civ.stats.statsForNextTurn.science * 100).toInt() * 2
        val candidates = ArrayList<Pair<Technology, Int>>()
        for (tech in data.techs) {
            if (!civ.tech.canBeResearched(tech.name)) continue
            var weight = weightOf(civ, tech.name)
            if (!freeTech) weight = reweightByCost(weight, researchTurnsLeft(civ, tech), baseMod, modPerTurn, needExpensiveTechs)
            candidates.add(tech to weight)
        }
        candidates.sortByDescending { it.second }
        return candidates
    }

    /**
     *  CvPlayerTechs::GetResearchTurnsLeft(bOverflow = true): the science still needed (cost − progress − overflow) over the science per turn,
     *  rounded up, at least 1; Int.MAX_VALUE without science.
     */
    private fun researchTurnsLeft(civ: Civilization, tech: Technology): Int {
        val science = civ.stats.statsForNextTurn.science
        if (science <= 0f) return Int.MAX_VALUE
        return max(1, ceil(civ.tech.remainingScienceToTech(tech.name) / science).toInt())
    }

    /** CvTechAI::ReweightByCost: weight / turns^(AI_RESEARCH_WEIGHT_BASE_MOD + AI_RESEARCH_WEIGHT_MOD_PER_TURN_LEFT × turns) */
    private fun reweightByCost(weight: Int, turnsLeft: Int, baseMod: Double, modPerTurn: Double, needExpensiveTechs: Boolean): Int {
        val factor = turnsLeft.toDouble().pow(baseMod + modPerTurn * turnsLeft)
        return (if (needExpensiveTechs) weight * factor else weight / factor).coerceIn(0.0, MAX_REWEIGHTED_WEIGHT.toDouble()).toInt()
    }
    //endregion
}
