package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DiplomacyAi
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.VictoryPursuitAi
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.automation.unit.tactical.TacticalState
import com.unciv.logic.automation.unit.tactical.UnitDanger
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.EconomicStrategyDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MilitaryStrategyDef
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.StrategyDef
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.ai.VictoryPursuit
import com.unciv.utils.hashOf
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** How sure a player is of its guess of another player's grand strategy (the original's GuessConfidenceTypes) */
enum class GuessConfidence { Positive, Likely, Unsure }

/** What one player believes another one's grand strategy is (CvGrandStrategyAI::m_eGuessOtherPlayerActiveGrandStrategy and its confidence) */
class GrandStrategyGuess() : IsPartOfGameInfoSerialization {
    /** Grand strategy name, null = "no grand strategy" */
    var grandStrategy: String? = null
    /** null when [grandStrategy] is null */
    var confidence: GuessConfidence? = null

    constructor(grandStrategy: String?, confidence: GuessConfidence?) : this() {
        this.grandStrategy = grandStrategy
        this.confidence = confidence
    }

    fun clone() = GrandStrategyGuess(grandStrategy, confidence)
}

/** What a player remembers of another player's aggression (CvDiplomacyAI m_paiOtherPlayerNumMinorsAttacked/Conquered, ...MajorsAttacked/Conquered) */
class AggressionMemory : IsPartOfGameInfoSerialization {
    var minorsAttacked = 0
    var minorsConquered = 0
    var majorsAttacked = 0
    var majorsConquered = 0

    fun clone() = AggressionMemory().also {
        it.minorsAttacked = minorsAttacked
        it.minorsConquered = minorsConquered
        it.majorsAttacked = majorsAttacked
        it.majorsConquered = majorsConquered
    }
}

/**
 *  The strategic AI state of a civilization (`civ.ai`), serialized with it: the original's CvFlavorManager (personality and active flavors),
 *  the personality part of CvDiplomacyAI, CvGrandStrategyAI and the strategy state of CvEconomicAI / CvMilitaryAI.
 *  The logic lives in [FlavorManager], [GrandStrategyAi] and [runStrategyLoop]; this class holds the state and the API the ports use.
 *
 *  Old saves have no state: the personality is initialized on first use ([ensureInitialized]), the strategies start inactive.
 */
class CivAi : IsPartOfGameInfoSerialization {
    //region Persisted state

    /** CvFlavorManager::m_piPersonalityFlavor by [Flavor] name: leader flavors, randomized once, adjusted for the map */
    var personalityFlavors = LinkedHashMap<String, Int>()
    /** CvDiplomacyAI personality values by [DiplomacyPersonality] name (GetRandomPersonalityWeight applied once) */
    var diplomacyPersonality = LinkedHashMap<String, Int>()
    /** By [MajorCivApproach] name */
    var majorCivApproachBiases = LinkedHashMap<String, Int>()
    /** By [MinorCivApproach] name */
    var minorCivApproachBiases = LinkedHashMap<String, Int>()
    var personalityInitialized = false
    /**
     *  CvDiplomacyAI::m_ePrimaryVictoryPursuit and m_eSecondaryVictoryPursuit, picked once with the personality
     *  ([VictoryPursuitAi.selectDefaultVictoryPursuits]); null for none, and for city-states
     */
    var primaryVictoryPursuit: VictoryPursuit? = null
    var secondaryVictoryPursuit: VictoryPursuit? = null
    /** CvDiplomacyAI::m_eCurrentVictoryPursuit, what [isGoingForWorldConquest] and the others read; [VictoryPursuitAi.updateCurrentVictoryPursuit] */
    var currentVictoryPursuit: VictoryPursuit? = null

    /** The active grand strategy (AIGRANDSTRATEGY_* name), null before the first [GrandStrategyAi.doTurn] */
    var grandStrategy: String? = null
    var turnsSinceGrandStrategySet = 0
    /** The team's project count of the Apollo Program never goes down; the building can be lost with its city, this stays (set in [com.unciv.logic.city.CityConstructions.addBuilding]) */
    var apolloProgramBuilt = false
    /** Last computed priority per grand strategy name */
    var grandStrategyPriorities = LinkedHashMap<String, Int>()
    /** Guesses of other majors' grand strategies, by civID */
    var grandStrategyGuesses = HashMap<String, GrandStrategyGuess>()

    /** Active economic strategies: name -> game turn adopted */
    var economicStrategies = LinkedHashMap<String, Int>()
    /** Active military strategies: name -> game turn adopted */
    var militaryStrategies = LinkedHashMap<String, Int>()

    /** CvPlayer::m_bCramped, updated each turn by [updateCramped] */
    var isCramped = false
    /**
     *  CvPlayer::m_iCitiesNeedingTerrainImprovements: the cities whose [CityAi.terrainImprovementNeed] was above 0 at the start of this turn
     *  (CvPlayer::UpdateNumCitiesNeedingTerrainImprovements, run first in [doTurn] from the needs the cities computed last turn)
     */
    var citiesNeedingTerrainImprovements = 0
    /** Aggression of other majors this player has seen, by civID */
    var aggressionMemory = HashMap<String, AggressionMemory>()
    /** CvCitySpecializationAI state, see [CitySpecializationAi] */
    var citySpecialization = CitySpecializationState()
    /** CvDiplomacyAI::m_paeMinorCivApproach: our approach to each city-state by civID, missing = [MinorCivApproach.Ignore] */
    var minorCivApproaches = HashMap<String, MinorCivApproach>()
    /** CvDiplomacyAI::m_pabWantToRouteToMinor: civIDs of the city-states whose route quest our builders should work on, see [CityStateAi.contactMinorCivs] */
    var wantsRouteToMinors = HashSet<String>()
    /** CvDiplomacyAI war state memory (turns at war, war states, target values, military strength comparison), see [WarStateAi] */
    var warStates = WarStateMemory()
    /** CvCitySpecializationAI::m_iWonderCityID: the [com.unciv.logic.city.City.id] of the city chosen to build wonders, null if none */
    var wonderCityId: String? = null
    /** CvWonderProductionAI::m_WonderAIWeights by building name: the flavors added up once per flavor broadcast, see [WonderProductionAi]; empty in old saves */
    var wonderWeights = LinkedHashMap<String, Int>()
    /** CvEconomicAI's gold reservations (StartSaveForPurchase ...) */
    var purchaseSavings = PurchaseSavings()
    /** CvUnit::AI_setUnitAIType reassignments: unit id -> UNITAI_* name, see [UnitAiTypes] */
    var unitAiOverrides = HashMap<String, String>()
    /** CvEconomicAI state beyond the strategies, see [EconomicAi] */
    var economic = EconomicAi()
    /** CvMilitaryAI state beyond the strategies, see [MilitaryAi] */
    var military = MilitaryAi()
    /** CvPlayer's AI operations and armies, with the per-unit army and deploy turn, see [AiOperations] */
    var operations = AiOperations()
    /** CvDiplomacyAI state towards other players (opinions, approaches, war goals, disputes, memories), see [DiplomacyAi] */
    var diplomacy = DiplomacyAi()
    /** CvMinorCivAI::m_eStatus of a city-state, updated each turn by [MinorCivStatusAi.status] */
    var minorCivStatus = MinorCivStatus.Normal
    /** Deal and city-state AI memory: deal statements sent, expired deals to renew (see [com.unciv.logic.automation.civilization.DealsAiState]) */
    var deals = com.unciv.logic.automation.civilization.DealsAiState()
    /** CvTacticalAI state across turns (dominance zone postures, temporary zones), and the per-turn tactical analysis map, see [TacticalState] */
    var tactical = TacticalState()
    //endregion

    //region Transients

    @Transient lateinit var civ: Civilization

    @Transient private var personalityVector: IntArray? = null
    @Transient private var activeFlavorVector: IntArray? = null
    /** Increased whenever the active economic/military strategies change, so city flavor caches know to rebuild */
    @Transient internal var strategyVersion = 0
        private set

    @Transient @Cache private var militaryMightTurn = -1
    @Transient @Cache private var militaryMightCache = 0
    @Transient @Cache private var nuclearMightCache = 0
    @Transient private var tourismTurn = -1
    @Transient private var tourismCache = 0
    @Transient private var plotDangerTurn = -1
    @Transient private var plotDangerCache: PlotDanger? = null
    @Transient private var unitDangerTurn = -1
    @Transient private var unitDangerCache: UnitDanger? = null
    //endregion

    fun clone(): CivAi {
        val toReturn = CivAi()
        toReturn.personalityFlavors.putAll(personalityFlavors)
        toReturn.diplomacyPersonality.putAll(diplomacyPersonality)
        toReturn.majorCivApproachBiases.putAll(majorCivApproachBiases)
        toReturn.minorCivApproachBiases.putAll(minorCivApproachBiases)
        toReturn.personalityInitialized = personalityInitialized
        toReturn.primaryVictoryPursuit = primaryVictoryPursuit
        toReturn.secondaryVictoryPursuit = secondaryVictoryPursuit
        toReturn.currentVictoryPursuit = currentVictoryPursuit
        toReturn.grandStrategy = grandStrategy
        toReturn.turnsSinceGrandStrategySet = turnsSinceGrandStrategySet
        toReturn.apolloProgramBuilt = apolloProgramBuilt
        toReturn.grandStrategyPriorities.putAll(grandStrategyPriorities)
        for ((civId, guess) in grandStrategyGuesses) toReturn.grandStrategyGuesses[civId] = guess.clone()
        toReturn.economicStrategies.putAll(economicStrategies)
        toReturn.militaryStrategies.putAll(militaryStrategies)
        toReturn.isCramped = isCramped
        toReturn.citiesNeedingTerrainImprovements = citiesNeedingTerrainImprovements
        for ((civId, memory) in aggressionMemory) toReturn.aggressionMemory[civId] = memory.clone()
        toReturn.citySpecialization = citySpecialization.clone()
        toReturn.minorCivApproaches.putAll(minorCivApproaches)
        toReturn.wantsRouteToMinors.addAll(wantsRouteToMinors)
        toReturn.warStates = warStates.clone()
        toReturn.wonderCityId = wonderCityId
        toReturn.wonderWeights.putAll(wonderWeights)
        toReturn.purchaseSavings = purchaseSavings.clone()
        toReturn.unitAiOverrides.putAll(unitAiOverrides)
        toReturn.economic = economic.clone()
        toReturn.military = military.clone()
        toReturn.operations = operations.clone()
        toReturn.diplomacy = diplomacy.clone()
        toReturn.minorCivStatus = minorCivStatus
        toReturn.deals = deals.clone()
        toReturn.tactical = tactical.clone()
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        diplomacy.civ = civ
        operations.setTransients(civ)
        tactical.setTransients(civ)
        plotDangerCache = null
        unitDangerCache = null
        // BNW's protective approach, which Vox Populi's city-state approach does not have: friendly takes its place
        minorCivApproaches.replaceAll { _, approach -> if (approach == MinorCivApproach.Protective) MinorCivApproach.Friendly else approach }
        personalityVector = null
        activeFlavorVector = null
        strategyVersion++
    }

    internal val rules: AiRuleset get() = civ.gameInfo.ruleset.ai

    /**
     *  The danger this civilization sees on the map (CvPlayer's CvDangerPlots) for the current turn, built on first use and shared by
     *  everything that reads it: city threat for operations and purchases, safety moves, workers. Code that changed units since (tests)
     *  calls [invalidateTurnCaches].
     */
    val plotDanger: PlotDanger
        get() {
            val turn = civ.gameInfo.turns
            plotDangerCache?.let { if (plotDangerTurn == turn) return it }
            return PlotDanger(civ).also {
                plotDangerCache = it
                plotDangerTurn = turn
            }
        }

    /**
     *  What the enemies can do to one of our units on a tile (CvUnit::GetDanger, CvPlayer::GetPossibleAttackers) for the current turn, built on
     *  first use like [plotDanger] and forgotten with it by [invalidateTurnCaches].
     */
    val unitDanger: UnitDanger
        get() {
            val turn = civ.gameInfo.turns
            unitDangerCache?.let { if (unitDangerTurn == turn) return it }
            return UnitDanger(civ).also {
                unitDangerCache = it
                unitDangerTurn = turn
            }
        }

    /** Forgets the danger map and the tactical analysis map of this turn, so the next read sees the current units, borders and wars */
    fun invalidateTurnCaches() {
        plotDangerCache = null
        tactical.invalidateMap()
        unitDangerCache = null
    }

    /**
     *  CvArmyAI::CanTacticalAIInterruptUnit of the army the unit is in: false if the unit sits in an army the tactical AI must leave alone
     *  (CvTacticalAI::CommandeerUnits). Units in no army can always be interrupted.
     */
    fun canTacticalAiInterrupt(unit: MapUnit): Boolean = operations.canTacticalAiInterrupt(unit)

    /** CvUnit::GetDeployFromOperationTurn: the game turn an army released the unit at its target, -100 if it never did */
    fun deployFromOperationTurn(unit: MapUnit): Int = operations.deployFromOperationTurn(unit.id)

    //region Personality (CvFlavorManager, CvDiplomacyAI personality)

    /** Initializes the personality if this civ has none yet (old saves, civs added after game start) - see [FlavorManager.initializePersonality] */
    fun ensureInitialized() {
        if (personalityInitialized) return
        FlavorManager.initializePersonality(civ, adjustForMap = civ.isMajorCiv)
    }

    /** CvFlavorManager::GetAllPersonalityFlavors: the personality vector by [Flavor.ordinal] - shared, do not modify */
    fun personalityFlavorVector(): IntArray {
        personalityVector?.let { return it }
        ensureInitialized()
        val vector = Flavor.vectorOf(personalityFlavors, rules.define(AiDefine.DEFAULT_FLAVOR_VALUE))
        personalityVector = vector
        return vector
    }

    /** CvFlavorManager::GetPersonalityIndividualFlavor: the leader's flavor, randomized once per game (±2, 0..20) and adjusted for the map */
    fun personality(flavor: Flavor): Int = personalityFlavorVector()[flavor.ordinal]

    /** Sets a personality flavor directly (scenario setup and tests); the active flavors follow and the wonder weights start over at the new flavors added once */
    @VisibleForTesting
    fun setPersonalityFlavor(flavor: Flavor, value: Int) {
        ensureInitialized()
        personalityFlavors[flavor.name] = value
        personalityVector = null
        onStrategiesChanged()
        WonderProductionAi.reset(civ)
        WonderProductionAi.flavorUpdate(civ)
    }

    /** A CvDiplomacyAI personality value (GetVictoryCompetitiveness, GetBoldness, ...), randomized once per game by GetRandomPersonalityWeight */
    fun diplomacyValue(value: DiplomacyPersonality): Int {
        ensureInitialized()
        return diplomacyPersonality[value.name] ?: rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
    }

    /** CvDiplomacyAI::GetPersonalityMajorCivApproachBias */
    fun majorCivApproachBias(approach: MajorCivApproach): Int {
        ensureInitialized()
        return majorCivApproachBiases[approach.name] ?: rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
    }

    /** CvDiplomacyAI::GetPersonalityMinorCivApproachBias */
    fun minorCivApproachBias(approach: MinorCivApproach): Int {
        ensureInitialized()
        return minorCivApproachBiases[approach.name] ?: rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
    }

    /**
     *  CvPlayerTraits::IsWarmonger ... IsReligious: a major civilization's leader trait categories from its personality
     *  ([com.unciv.models.ruleset.nation.Personality.traitCategories]); Religious also with a state religion (BNW has no state religion
     *  apart from the one we founded: that one), Smaller also with fewer than 4 cities once the turn is over twice the game's
     *  estimated length (VP's getGameTurn() / 2 > getEstimateEndTurn()). VP evaluates the dynamic parts when it (re)initializes the
     *  traits (game start, religion and pantheon founding, ...); here at each query.
     */
    fun hasTraitCategory(category: TraitCategory): Boolean {
        if (civ.isMajorCiv) {
            val personality = civ.nation.personality?.let { civ.gameInfo.ruleset.personalities[it] }
            if ((personality?.traitCategories?.get(category.name) ?: 0) > 0) return true
        }
        return when (category) {
            TraitCategory.Religious -> civ.religionManager.religion?.isMajorReligion() == true
            TraitCategory.Smaller -> civ.gameInfo.turns / 2 > civ.gameInfo.speed.numTotalTurns() && civ.cities.size < 4
            else -> false
        }
    }

    /** Sets a diplomacy personality value directly (scenario setup and tests) */
    @VisibleForTesting
    fun setDiplomacyValue(value: DiplomacyPersonality, amount: Int) {
        ensureInitialized()
        diplomacyPersonality[value.name] = amount
    }

    /** Sets an approach bias directly (scenario setup and tests) */
    @VisibleForTesting
    fun setMajorCivApproachBias(approach: MajorCivApproach, amount: Int) {
        ensureInitialized()
        majorCivApproachBiases[approach.name] = amount
    }
    //endregion

    //region Active flavors

    /**
     *  CvFlavorManager::GetAllFlavors: the player's current flavors by [Flavor.ordinal] - the personality plus the player flavors of every active
     *  economic and military strategy, each kept within FLAVOR_MIN_VALUE..FLAVOR_MAX_VALUE. Values can be negative (as the original's
     *  active flavors; its recipients read them through GetLatestFlavorValue, which shows negatives as 0). Shared array, do not modify.
     */
    fun flavors(): IntArray {
        activeFlavorVector?.let { return it }
        val vector = personalityFlavorVector().copyOf()
        val min = rules.define(AiDefine.FLAVOR_MIN_VALUE)
        val max = rules.define(AiDefine.FLAVOR_MAX_VALUE)
        for (def in activeEconomicStrategyDefs()) addFlavorDelta(vector, def.playerFlavorVector, min, max)
        for (def in activeMilitaryStrategyDefs()) addFlavorDelta(vector, def.playerFlavorVector, min, max)
        activeFlavorVector = vector
        return vector
    }

    /** CvFlavorManager::GetIndividualFlavor - see [flavors] */
    fun flavor(flavor: Flavor): Int = flavors()[flavor.ordinal]

    internal fun onStrategiesChanged() {
        activeFlavorVector = null
        strategyVersion++
    }

    /** CvFlavorManager::ChangeFlavors to the player-level recipients after a strategy was adopted or dropped: the wonder AI adds its weights again */
    private fun onFlavorBroadcast() {
        WonderProductionAi.ensureWeights(civ)
        onStrategiesChanged()
        WonderProductionAi.flavorUpdate(civ)
    }

    internal fun activeEconomicStrategyDefs(): Sequence<EconomicStrategyDef> =
        rules.economicStrategies.asSequence().filter { it.name in economicStrategies }

    internal fun activeMilitaryStrategyDefs(): Sequence<MilitaryStrategyDef> =
        rules.militaryStrategies.asSequence().filter { it.name in militaryStrategies }
    //endregion

    //region Grand strategy (CvGrandStrategyAI) and victory pursuits (CvDiplomacyAI::IsGoingFor*)

    /** The active grand strategy's definition, null if none */
    fun grandStrategyDef(): GrandStrategyDef? = grandStrategy?.let { name -> rules.getGrandStrategies().firstOrNull { it.name == name } }

    /** CvGrandStrategyAI::GetGrandStrategyPriority: last computed priority, -1 before the first computation */
    fun grandStrategyPriority(grandStrategy: String): Int = grandStrategyPriorities[grandStrategy] ?: -1

    /**
     *  CvGrandStrategyAI::GetPersonalityAndGrandStrategy: personality flavor plus the active grand strategy's FlavorMod, at least 0. With
     *  [boostMainFlavor] (the tech weights' bBoostGSMainFlavor) a flavor the grand strategy lists as its own (AIGrandStrategy_Flavors) is
     *  moved halfway (rounded up) towards that value, at most 10.
     */
    fun personalityAndGrandStrategy(flavor: Flavor, boostMainFlavor: Boolean = false): Int {
        val active = grandStrategyDef() ?: return personality(flavor)
        val modded = max(0, active.flavorModVector[flavor.ordinal] + personality(flavor))
        val main = active.flavorVector[flavor.ordinal]
        return if (boostMainFlavor && main > 0) min(10, (main + modded + 1) / 2) else modded
    }

    /** CvGrandStrategyAI::GetGuessOtherPlayerActiveGrandStrategy and its confidence; (null, null) if no guess */
    fun guessGrandStrategy(other: Civilization): Pair<String?, GuessConfidence?> {
        val guess = grandStrategyGuesses[other.civID] ?: return Pair(null, null)
        return Pair(guess.grandStrategy, guess.confidence)
    }

    /** CvDiplomacyAI::IsGoingForWorldConquest: the current victory pursuit is Domination */
    @Readonly fun isGoingForWorldConquest() = currentVictoryPursuit == VictoryPursuit.Domination
    /** CvDiplomacyAI::IsGoingForDiploVictory: the current victory pursuit is Diplomacy */
    @Readonly fun isGoingForDiploVictory() = currentVictoryPursuit == VictoryPursuit.Diplomacy
    /** CvDiplomacyAI::IsGoingForCultureVictory: the current victory pursuit is Culture */
    @Readonly fun isGoingForCultureVictory() = currentVictoryPursuit == VictoryPursuit.Culture
    /** CvDiplomacyAI::IsGoingForSpaceshipVictory: the current victory pursuit is Science */
    @Readonly fun isGoingForSpaceshipVictory() = currentVictoryPursuit == VictoryPursuit.Science

    /** CvDiplomacyAI::IsConqueror, IsDiplomat, IsCultural, IsScientist: the leader's primary victory pursuit is Domination ... Science */
    @Readonly fun isConqueror() = primaryVictoryPursuit == VictoryPursuit.Domination
    @Readonly fun isDiplomat() = primaryVictoryPursuit == VictoryPursuit.Diplomacy
    @Readonly fun isCultural() = primaryVictoryPursuit == VictoryPursuit.Culture
    @Readonly fun isScientist() = primaryVictoryPursuit == VictoryPursuit.Science
    /** CvDiplomacyAI::IsSecondaryConqueror ... IsSecondaryScientist: the leader's secondary victory pursuit */
    @Readonly fun isSecondaryConqueror() = secondaryVictoryPursuit == VictoryPursuit.Domination
    @Readonly fun isSecondaryDiplomat() = secondaryVictoryPursuit == VictoryPursuit.Diplomacy
    @Readonly fun isSecondaryCultural() = secondaryVictoryPursuit == VictoryPursuit.Culture
    @Readonly fun isSecondaryScientist() = secondaryVictoryPursuit == VictoryPursuit.Science

    /** Sets the grand strategy and the current victory pursuit a serious AI takes from it, none without one (scenario setup and tests) */
    @VisibleForTesting
    fun setGrandStrategyAndPursuit(grandStrategy: String?) {
        this.grandStrategy = grandStrategy
        currentVictoryPursuit = when (grandStrategy) {
            GrandStrategyAi.CONQUEST -> VictoryPursuit.Domination
            GrandStrategyAi.UNITED_NATIONS -> VictoryPursuit.Diplomacy
            GrandStrategyAi.CULTURE -> VictoryPursuit.Culture
            GrandStrategyAi.SPACESHIP -> VictoryPursuit.Science
            else -> null
        }
    }
    //endregion

    //region Economic and military strategies

    /** CvEconomicAI::IsUsingStrategy */
    fun isEconomicStrategyActive(name: String) = name in economicStrategies
    /** CvMilitaryAI::IsUsingStrategy */
    fun isMilitaryStrategyActive(name: String) = name in militaryStrategies

    /** EconomicAIHelpers / MilitaryAIHelpers / CityStrategyAIHelpers::GetWeightThresholdModifier: Σ personality flavor × the strategy's
     *  PersonalityFlavorThresholdMods. A test compares against `def.weightThreshold` plus this. */
    fun weightThresholdModifier(def: StrategyDef): Int {
        val personality = personalityFlavorVector()
        val mods = def.thresholdModVector
        var sum = 0
        for (i in 0 until Flavor.count) sum += personality[i] * mods[i]
        return sum
    }
    //endregion

    //region Inputs the original keeps on CvPlayer

    /**
     * CvPlayer::calculateMilitaryMight and updateMightStatistics (Vox Populi 5.4.8): offensive unit strength,
     * with 10% per level above three, plus nuclear might. Civilian and nuclear units are excluded from the sum.
     * [NuclearMight.attackStrength] records the approximation used for VP's contextual combat strength.
     */
    @Readonly fun militaryMight(): Int {
        val turn = civ.gameInfo.turns
        if (militaryMightTurn == turn) return militaryMightCache
        var sum = 0
        for (unit in civ.units.getCivUnits()) {
            if (unit.isCivilian() || unit.isNuclearWeapon()) continue
            val level = 1 + unit.promotions.numberOfPromotions
            val power = NuclearMight.attackStrength(unit)
            sum += if (level > 3) power * (100 + (level - 3) * 10) / 100 else power
        }
        nuclearMightCache = NuclearMight.of(civ, null)
        militaryMightCache = sum + nuclearMightCache
        militaryMightTurn = turn
        return militaryMightCache
    }

    /** The global nuclear might in [militaryMight] (CvPlayer::GetNuclearMight): [NuclearMight.of] against all other living majors */
    @Readonly fun nuclearMight(): Int {
        militaryMight()
        return nuclearMightCache
    }

    /** CvPlayerCulture::GetTourism, computed once per turn */
    fun tourism(): Int {
        val turn = civ.gameInfo.turns
        if (tourismTurn == turn) return tourismCache
        tourismCache = civ.culture.getTourism()
        tourismTurn = turn
        return tourismCache
    }

    /**
     *  CvPlayer::DoUpdateCramped: within CRAMPED_RANGE_FROM_CITY of each city (tiles counted once per city), among the tiles we do not own,
     *  the share of unowned passable land (not impassable, not mountain, not water); cramped when that share is at most CRAMPED_USABLE_PLOT_PERCENT.
     *  Without such tiles the previous value stays.
     */
    fun updateCramped() {
        val range = rules.define(AiDefine.CRAMPED_RANGE_FROM_CITY)
        var total = 0
        var usable = 0
        for (city in civ.cities) {
            for (tile in city.getCenterTile().getTilesInDistance(range)) {
                val owner = tile.getOwner()
                if (owner == civ) continue
                total++
                if (owner == null && !tile.isImpassible() && !tile.isWater && tile.baseTerrain != Constants.mountain) usable++
            }
        }
        if (total > 0) isCramped = 100 * usable / total <= rules.define(AiDefine.CRAMPED_USABLE_PLOT_PERCENT)
    }

    /** The aggression of [other] as this player remembers it */
    fun aggressionOf(other: Civilization): AggressionMemory = aggressionMemory.getOrPut(other.civID) { AggressionMemory() }
    //endregion

    /** A random generator deterministic per game, civilization, purpose and turn (never Random.Default, so simulations stay reproducible) */
    internal fun random(purpose: String, turn: Int = civ.gameInfo.turns): Random =
        Random(hashOf(purpose.hashCode(), civ.civID.hashCode(), civ.gameInfo.gameId.hashCode(), turn))

    /**
     *  The strategic AI turn of this civilization, in CvPlayer::doTurn / doTurnPostDiplomacy order:
     *  DoUpdateCramped, CvGrandStrategyAI::DoTurn (majors), the current victory pursuit and the war states of CvDiplomacyAI::DoTurn
     *  (majors, [VictoryPursuitAi.updateCurrentVictoryPursuit], [WarStateAi.doTurn]), the rest of CvDiplomacyAI::DoTurn (majors, [DiplomacyTurn.doTurn]),
     *  then CvEconomicAI::DoTurn (recon state and antiquity sites, the strategy loop, then for AI players plot purchases and worker
     *  disbanding - [EconomicAi]), CvMilitaryAI::DoTurn (base data and defense states, the strategy loop, the attack targets, then for AI
     *  players offensive purchases and obsolete unit disbanding - [MilitaryAi]), CvCitySpecializationAI::DoTurn (as in doTurnPostDiplomacy), then each
     *  city's CvCityStrategyAI::DoTurn (in CvCity::doTurn).
     *  Runs for every living civilization except barbarians and spectators, human or AI, as in the original.
     */
    fun doTurn() {
        if (civ.isBarbarian || civ.isSpectator || civ.isDefeated()) return
        ensureInitialized()
        citiesNeedingTerrainImprovements = civ.cities.count { it.ai.terrainImprovementNeed > 0 }
        timeThis("AI.Cramped") { updateCramped() }
        if (civ.isMajorCiv || civ.isCityState) com.unciv.logic.automation.diplomacy.WarDamageAi.doTurn(civ)
        if (civ.isMajorCiv) timeThis("AI.GrandStrategy") { GrandStrategyAi.doTurn(civ) }
        if (civ.isMajorCiv) VictoryPursuitAi.updateCurrentVictoryPursuit(civ)
        if (civ.isMajorCiv) timeThis("AI.WarStates") { WarStateAi.doTurn(civ) }
        if (civ.isCityState) minorCivStatus = MinorCivStatusAi.status(civ)
        if (civ.isMajorCiv) timeThis("AI.Diplomacy") { DiplomacyTurn.doTurn(civ) }
        timeThis("AI.EconomicStrategies") {
            economic.doTurnBeforeStrategies(civ)
            runStrategyLoop(rules.economicStrategies, economicStrategies, civ, checkFirstTurn = true, onChange = ::onFlavorBroadcast) { StrategyTests.test(civ, it) }
        }
        if (!civ.isHuman()) timeThis("AI.EconomicDoTurn") { economic.doTurnAfterStrategies(civ) }
        timeThis("AI.MilitaryStrategies") {
            military.doTurnBeforeStrategies(civ)
            runStrategyLoop(rules.militaryStrategies, militaryStrategies, civ, checkFirstTurn = true, onChange = ::onFlavorBroadcast) { StrategyTests.test(civ, it) }
        }
        timeThis("AI.AttackTargets") { military.updateAttackTargets(civ) }
        if (!civ.isHuman()) timeThis("AI.MilitaryDoTurn") { military.doTurnAfterStrategies(civ) }
        timeThis("AI.TechFlavors") { com.unciv.logic.automation.civilization.TechAutomation.updateFlavorWeights(civ) }
        timeThis("AI.CitySpecialization") { CitySpecializationAi.doTurn(civ) }
        timeThis("AI.CityStrategies") {
            CityStrategyAi.clearTurnCache(civ)
            for (city in civ.cities) city.ai.doTurn()
        }
    }
}
