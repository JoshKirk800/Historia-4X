package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min

/**
 *  Port of CvGrandStrategyAI.cpp (Brave New World): each turn a major re-scores the four grand strategies (Conquest, Culture,
 *  United Nations, Spaceship) and, at most every AI_GRAND_STRATEGY_NUM_TURNS_STRATEGY_MUST_BE_ACTIVE turns, adopts the best one.
 *  The grand strategies are the AI.json `grandStrategies` (XML order); each pursues an Unciv victory ([GrandStrategyDef.victoryType]),
 *  and a grand strategy whose victory is not available to the civilization in this game scores -100 for itself.
 *
 *  Inputs the original keeps elsewhere, and where they come from in Unciv:
 *  - GetMilitaryMight / GetWorldMilitaryStrengthAverage: [CivAi.militaryMight] (CvPlayer::calculateMilitaryMight on Unciv's unit power).
 *  - IsCramped: [CivAi.updateCramped] (CvPlayer::DoUpdateCramped), run before this each turn.
 *  - getNumNukeUnits: the civilization's units with the nuclear weapon unique.
 *  - CvTeam::GetNumMinorCivsAttacked: Unciv's count of direct war declarations on city-states ([Civilization.numMinorCivsAttacked]).
 *  - CvDiplomacyAI::GetOtherPlayerNum{Minors,Majors}{Attacked,Conquered}: [CivAi.aggressionMemory], fed by [onWarDeclared] and [onCivDestroyed].
 *  - CvDiplomacyAI::GetPlayerNumMajorsConquered (our own, for the conquest priority): [VictoryProgressAi.numMajorsConquered], from
 *    [Civilization.killedBy] and the original capitals held.
 *  - League votes: Unciv's World Congress ([com.unciv.logic.league.League.getDelegates], votes needed = getVotesNeededForWorldLeader);
 *    "no active league" = no World Congress founded yet.
 *  - Traits: CityStateFriendshipModifier = 100 for the unique "City-State Influence recovers at twice the normal rate" (the original's +100%
 *    friendship rate); CityStateBonusModifier = the largest "[x]% [stat] from City-States"; CityStateCombatModifier = the sum of the
 *    "[x]% Strength <vs [City-States]>" uniques.
 *  - Apollo Program: the civilization has the unique "Enables construction of Spaceship parts" (the Apollo Program building in Unciv).
 *  - Techs known: researched technologies; culture per turn: the civilization's culture for next turn; lifetime culture: total culture
 *    generated ([Civilization.totalCultureForContests]); tourism: [CivAi.tourism]; influential count: CultureManager.getNumCivsInfluentialOn.
 */
object GrandStrategyAi {
    const val CONQUEST = "AIGRANDSTRATEGY_CONQUEST"
    const val CULTURE = "AIGRANDSTRATEGY_CULTURE"
    const val UNITED_NATIONS = "AIGRANDSTRATEGY_UNITED_NATIONS"
    const val SPACESHIP = "AIGRANDSTRATEGY_SPACESHIP"

    /** CvGrandStrategyAI::DoTurn */
    fun doTurn(civ: Civilization) {
        val ai = civ.ai
        val rules = ai.rules
        doGuessOtherPlayersActiveGrandStrategy(civ)

        val definitions = rules.getGrandStrategies()
        val random = ai.random("CvGrandStrategyAI::DoTurn")
        val randRoll = rules.define(AiDefine.AI_GS_RAND_ROLL)
        for (def in definitions) {
            var priority = baseGrandStrategyPriority(civ, def)
            priority += situationalPriority(civ, def)
            if (randRoll > 0) priority += random.nextInt(randRoll)
            if (ai.grandStrategy == def.name) priority += rules.define(AiDefine.AI_GRAND_STRATEGY_CURRENT_STRATEGY_WEIGHT)
            ai.grandStrategyPriorities[def.name] = priority
        }

        applyCrowding(civ, definitions)

        ai.turnsSinceGrandStrategySet++
        if (ai.grandStrategy == null
            || ai.turnsSinceGrandStrategySet >= rules.define(AiDefine.AI_GRAND_STRATEGY_NUM_TURNS_STRATEGY_MUST_BE_ACTIVE)) {
            var bestPriority = -1
            var best: String? = null
            for (def in definitions) {
                val priority = ai.grandStrategyPriority(def.name)
                if (priority > bestPriority) {
                    bestPriority = priority
                    best = def.name
                }
            }
            if (best != null && best != ai.grandStrategy) {
                ai.grandStrategy = best
                ai.turnsSinceGrandStrategySet = 0
            }
        }
    }

    /**
     *  The crowding step of CvGrandStrategyAI::DoTurn: each priority loses AI_GRAND_STRATEGY_OTHER_PLAYERS_GS_MULTIPLIER percent, scaled by
     *  the share of players we believe pursue it (guesses, counted over every major ever guessed, as the original keeps guesses of dead players)
     *  among the living majors we have met (counting ourselves).
     */
    fun applyCrowding(civ: Civilization, definitions: List<GrandStrategyDef>) {
        val ai = civ.ai
        val numPlayersAliveAndMet = civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() && (it == civ || civ.knows(it)) }
        if (numPlayersAliveAndMet == 0) return
        val multiplier = ai.rules.define(AiDefine.AI_GRAND_STRATEGY_OTHER_PLAYERS_GS_MULTIPLIER)
        for (def in definitions) {
            val numAdopted = ai.grandStrategyGuesses.values.count { it.grandStrategy == def.name }
            val priority = ai.grandStrategyPriority(def.name)
            var change = priority * multiplier
            change = change * numAdopted / numPlayersAliveAndMet
            change /= 100
            ai.grandStrategyPriorities[def.name] = priority - change
        }
    }

    /** The grand-strategy-specific term of the priority: GetConquestPriority, GetCulturePriority, GetUnitedNationsPriority, GetSpaceshipPriority */
    fun situationalPriority(civ: Civilization, def: GrandStrategyDef): Int = when (def.name) {
        CONQUEST -> conquestPriority(civ, def)
        CULTURE -> culturePriority(civ, def)
        UNITED_NATIONS -> unitedNationsPriority(civ, def)
        SPACESHIP -> spaceshipPriority(civ, def)
        else -> 0
    }

    /** CvGrandStrategyAI::GetBaseGrandStrategyPriority: Σ AIGrandStrategy_Flavors × personality flavor */
    fun baseGrandStrategyPriority(civ: Civilization, def: GrandStrategyDef): Int {
        val personality = civ.ai.personalityFlavorVector()
        val flavors = def.flavorVector
        var priority = 0
        for (i in 0 until Flavor.count) if (flavors[i] != 0) priority += flavors[i] * personality[i]
        return priority
    }

    /** Whether the game lets this civilization win [def]'s victory (CvGame::isVictoryValid) */
    private fun isVictoryValid(civ: Civilization, def: GrandStrategyDef): Boolean =
        civ.victoryManager.getAvailableVictories().any { it.name == def.victoryType }

    /**
     *  CvGrandStrategyAI::GetConquestPriority (Vox Populi 5.4.8; the file's one MOD_BALANCE_VP use is the paper resource of
     *  GetUnitedNationsPriority). Boldness, the approach biases and meanness weigh (10 - era), the era at least 1; +9000 when close to world
     *  conquest; -50 with nobody met; +50 for one major conquered, 125 for each when more ([VictoryProgressAi.numMajorsConquered]); the
     *  military ratio against the world average, which is reduced by 10 % per major conquered; +10 at war; land: more met land per player than
     *  ours adds the cramped weight, five times it when cramped; the danger of others' victories and an ideology of our own (the late game
     *  tree), times the era; -50 when others have nuclear weapons and we do not; and the bonus of [conquestFlavorBonus].
     *  The player's own blocked policies and the belief effects of the state religion (combat bonuses in enemy lands and cities, faith from
     *  kills, yields from conquest and kills) map to none: Unciv has no blocked policies and keeps belief effects as uniques without those columns.
     */
    fun conquestPriority(civ: Civilization, def: GrandStrategyDef): Int {
        if (!isVictoryValid(civ, def) && civ.victoryManager.getAvailableVictories().isNotEmpty()) return -100
        val ai = civ.ai
        val rules = ai.rules
        val gameInfo = civ.gameInfo
        val elapsedTurns = gameInfo.turns
        var priority = 0

        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        if (closeToConquest) priority += 9000

        val approachModifier = max(max(ai.majorCivApproachBias(MajorCivApproach.Deceptive), ai.majorCivApproachBias(MajorCivApproach.Hostile)),
            ai.majorCivApproachBias(MajorCivApproach.War)) - ai.majorCivApproachBias(MajorCivApproach.Friendly)
        val era = max(1, civ.getAiEraNumber())
        priority += (ai.diplomacyValue(DiplomacyPersonality.Boldness) + approachModifier + ai.diplomacyValue(DiplomacyPersonality.Meanness)) * (10 - era)

        // CvGrandStrategyAI::GetConquestPriority loops over the teams, so a major that has been met counts whether or not it is still alive
        if (elapsedTurns >= rules.define(AiDefine.AI_GS_CONQUEST_NOBODY_MET_FIRST_TURN)
            && gameInfo.civilizations.none { it.isMajorCiv && !it.isSpectator && it != civ && civ.knows(it) })
            priority += rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_NOBODY_MET_WEIGHT)

        val numConquests = VictoryProgressAi.numMajorsConquered(civ)
        if (numConquests == 1) priority += 50
        else if (numConquests > 1) priority += numConquests * 125

        if (elapsedTurns >= rules.define(AiDefine.AI_GS_CONQUEST_MILITARY_STRENGTH_FIRST_TURN)) {
            var worldMilitaryStrength = worldMilitaryStrengthAverage(civ)
            // Reduce the world average if we are rocking multiple capitals
            if (numConquests > 0) worldMilitaryStrength = worldMilitaryStrength * 100 / (100 + numConquests * 10)
            if (worldMilitaryStrength > 0) {
                var militaryRatio = (ai.militaryMight() - worldMilitaryStrength) *
                    rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_POWER_RATIO_MULTIPLIER) / worldMilitaryStrength
                // Becoming a warmonger is made less likely than dropping it
                if (militaryRatio > 0) militaryRatio /= 2
                priority += militaryRatio
            }
        }

        if (civ.isAtWar()) priority += rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_AT_WAR_WEIGHT)

        val hasIdeology = civ.policies.getIdeology() != null
        val desperate = hasIdeology && !closeToConquest && !VictoryProgressAi.isCloseToCultureVictory(civ, rules) &&
            !VictoryProgressAi.isCloseToDiploVictory(civ, rules) && !VictoryProgressAi.isCloseToSpaceshipVictory(civ, rules)
        var numDangerPlayers = 0
        var numPlayersMet = 1 // Include 1 for me!
        for (other in gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive() || !civ.knows(other)) continue
            numPlayersMet++
            if (!hasIdeology) continue
            numDangerPlayers += if (VictoryProgressAi.isCloseToCultureVictory(other, rules) || VictoryProgressAi.isCloseToDiploVictory(other, rules) ||
                VictoryProgressAi.isCloseToSpaceshipVictory(other, rules)) {
                // Close to nothing, and someone else is? Eek! Someone else is close, but we are too? Keep an eye on domination as an option
                if (desperate) 25 else 5
            } else if (desperate) 5 else 0 // They have an ideology, and we don't? Uh-oh
        }

        // If our neighbors are cramping our style, consider less... scrupulous means of obtaining more land
        var landMe = 0
        var landPlayersMet = 0
        for (tile in gameInfo.tileMap.values) {
            val owner = tile.getOwner() ?: continue
            if (owner == civ) {
                landPlayersMet++
                landMe++
            } else if (!owner.isCityState && civ.knows(owner)) landPlayersMet++
        }
        landPlayersMet /= numPlayersMet
        if (landMe > 0 && landPlayersMet / landMe > 1) {
            val crampedWeight = rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_CRAMPED_WEIGHT)
            priority += if (ai.isCramped) crampedWeight * 5 else crampedWeight
        }
        if (numDangerPlayers > 0) priority += numDangerPlayers * era

        if (numNukes(civ) == 0 && gameInfo.civilizations.any { it != civ && it.isMajorCiv && it.isAlive() && civ.knows(it) && numNukes(it) > 0 })
            priority -= 50

        return priority + conquestFlavorBonus(civ)
    }

    /**
     *  The priority bonus at the end of GetConquestPriority: the Offense, Mobile, Military Training, Naval, Naval Recon, Ranged and Air
     *  flavors of the adopted policies, a tenth of the production in each city's current construction, and of each building Offense twice,
     *  Military Training and Naval; an eighth of it; plus the leader's trait categories. The unit flavors that MOD_AI_UNIT_PRODUCTION adds
     *  (Archer, Siege, ...) are off in Community Patch and Vox Populi.
     */
    private fun conquestFlavorBonus(civ: Civilization): Int {
        var bonus = adoptedPolicyFlavors(civ, CONQUEST_POLICY_FLAVORS)
        for (city in civ.cities) {
            bonus += city.cityConstructions.getWorkDone(city.cityConstructions.currentConstructionName()) / 10
            bonus += builtBuildingFlavors(city, CONQUEST_BUILDING_FLAVORS)
        }
        return bonus / 8 + traitBonus(civ, warmonger = 100, smaller = -100, tourism = 25, diplomat = -25, nerd = 25, religious = 25, expansionist = 50)
    }

    private val CONQUEST_POLICY_FLAVORS = FlavorWeights(Flavor.Offense to 1, Flavor.Mobile to 1, Flavor.MilitaryTraining to 1, Flavor.Naval to 1,
        Flavor.NavalRecon to 1, Flavor.Ranged to 1, Flavor.Air to 1)
    private val CONQUEST_BUILDING_FLAVORS = FlavorWeights(Flavor.Offense to 2, Flavor.MilitaryTraining to 1, Flavor.Naval to 1)

    /** Flavors with the multiplier the grand strategy's priority gives each */
    private class FlavorWeights(vararg val weights: Pair<Flavor, Int>) {
        fun of(flavors: IntArray) = weights.sumOf { (flavor, weight) -> flavors[flavor.ordinal] * weight }
    }

    /** The weighted flavors of the policies the civilization adopted (not its branches: the original loops over the policies, whose blocked ones map to none) */
    private fun adoptedPolicyFlavors(civ: Civilization, weights: FlavorWeights): Int {
        val rules = civ.ai.rules
        var sum = 0
        for (policyName in civ.policies.getAdoptedPolicies()) {
            val policy = civ.gameInfo.ruleset.policies[policyName]?.takeIf { it !is PolicyBranch } ?: continue
            sum += weights.of(rules.flavorsOf(policy))
        }
        return sum
    }

    /** The weighted flavors of the buildings in [city] */
    private fun builtBuildingFlavors(city: City, weights: FlavorWeights): Int {
        val rules = city.civ.ai.rules
        return city.cityConstructions.getBuiltBuildings().sumOf { weights.of(rules.flavorsOf(it)) }
    }

    /** The trait category terms every priority of the original ends with: the amount of each category the leader has (0 for the others) */
    private fun traitBonus(civ: Civilization, warmonger: Int, smaller: Int, tourism: Int, diplomat: Int, nerd: Int, religious: Int, expansionist: Int): Int {
        val ai = civ.ai
        var bonus = 0
        if (ai.hasTraitCategory(TraitCategory.Warmonger)) bonus += warmonger
        if (ai.hasTraitCategory(TraitCategory.Smaller)) bonus += smaller
        if (ai.hasTraitCategory(TraitCategory.Tourism)) bonus += tourism
        if (ai.hasTraitCategory(TraitCategory.Diplomat)) bonus += diplomat
        if (ai.hasTraitCategory(TraitCategory.Nerd)) bonus += nerd
        if (ai.hasTraitCategory(TraitCategory.Religious)) bonus += religious
        if (ai.hasTraitCategory(TraitCategory.Expansionist)) bonus += expansionist
        return bonus
    }

    private fun numNukes(civ: Civilization) = civ.units.getCivUnits().count { it.isNuclearWeapon() }

    /** CvGame::GetWorldMilitaryStrengthAverage(player, bIncludeMe = true, bIncludeOnlyKnown = true) */
    fun worldMilitaryStrengthAverage(civ: Civilization): Int {
        var sum = 0
        var count = 0
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || !other.isAlive() || (other != civ && !civ.knows(other))) continue
            count++
            sum += other.ai.militaryMight()
        }
        return if (count > 0) sum / count else 0
    }

    /** CvGrandStrategyAI::GetCulturePriority */
    fun culturePriority(civ: Civilization, def: GrandStrategyDef): Int {
        if (!isVictoryValid(civ, def)) return -100
        val ai = civ.ai
        val rules = ai.rules
        var priority = (10 - civ.getAiEraNumber()) * ai.personality(Flavor.Culture) * 200 / 100

        val ourCulture = culturePerTurn(civ)
        val ourTourism = ai.tourism()
        var aheadCulture = 0
        var behindCulture = 0
        var aheadTourism = 0
        var behindTourism = 0
        var alive = 0
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive()) continue
            if (ourCulture > culturePerTurn(other)) aheadCulture++ else behindCulture++
            if (ourTourism > other.ai.tourism()) aheadTourism++ else behindTourism++
            alive++
        }
        if (alive > 0 && aheadCulture > behindCulture)
            priority += rules.define(AiDefine.AI_GS_CULTURE_AHEAD_WEIGHT) * (aheadCulture - behindCulture) / alive
        if (alive > 0 && aheadTourism > behindTourism)
            priority += rules.define(AiDefine.AI_GS_CULTURE_TOURISM_AHEAD_WEIGHT) * (aheadTourism - behindTourism) / alive

        priority += civ.culture.getNumCivsInfluentialOn() * rules.define(AiDefine.AI_GS_CULTURE_INFLUENTIAL_CIV_MOD)
        return priority
    }

    /** CvPlayer::GetTotalJONSCulturePerTurn */
    private fun culturePerTurn(civ: Civilization) = civ.stats.statsForNextTurn.culture.toInt()

    /** CvGrandStrategyAI::GetUnitedNationsPriority */
    fun unitedNationsPriority(civ: Civilization, def: GrandStrategyDef): Int {
        if (!isVictoryValid(civ, def)) return -100
        val ai = civ.ai
        val rules = ai.rules
        var priority = civ.numMinorCivsAttacked * rules.define(AiDefine.AI_GRAND_STRATEGY_UN_EACH_MINOR_ATTACKED_WEIGHT)

        val league = civ.gameInfo.league
        var votesNeeded = Int.MAX_VALUE
        var votesControlled = 0
        var votesControlledDelta = 0
        var unalliedCityStates = 0
        if (league == null) {
            // Before the World Congress, weight by flavor
            priority += (10 - civ.getAiEraNumber()) * ai.personality(Flavor.Diplomacy) * 150 / 100
        } else {
            votesNeeded = league.getVotesNeededForWorldLeader()
            votesControlled = league.getDelegates(civ)
            var highestOtherVotes = 0
            for (other in civ.gameInfo.civilizations) {
                if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
                if (other.isCityState) {
                    if (other.allyCiv == null) unalliedCityStates++
                } else highestOtherVotes = max(highestOtherVotes, league.getDelegates(other))
            }
            votesControlledDelta = votesControlled - highestOtherVotes
        }

        if (votesControlled >= votesNeeded) return 1000
        else if (votesControlled >= votesNeeded * 3 / 4) priority += 40

        if (votesControlledDelta > 0) priority += max(40, votesControlledDelta * 5)
        else {
            // Could the unallied city-states make up the difference?
            val potentialVotesDelta = unalliedCityStates * 2 + votesControlledDelta
            if (potentialVotesDelta > 0) priority += max(20, potentialVotesDelta * 5)
            else if (potentialVotesDelta < 0) priority += min(-40, potentialVotesDelta * -5)
        }

        priority += cityStateFriendshipModifier(civ)
        priority += cityStateBonusModifier(civ)
        priority -= cityStateCombatModifier(civ)
        return priority
    }

    /** CvPlayerTraits::GetCityStateFriendshipModifier: +100 % influence rate, Unciv's "recovers at twice the normal rate" */
    fun cityStateFriendshipModifier(civ: Civilization): Int =
        if (civ.hasUnique(UniqueType.CityStateInfluenceRecoversTwiceNormalRate)) 100 else 0

    /** CvPlayerTraits::GetCityStateBonusModifier: the largest bonus to what city-states give */
    fun cityStateBonusModifier(civ: Civilization): Int {
        var largest = 0
        civ.forEachMatchingUnique(UniqueType.BonusStatsFromCityStates) { largest = max(largest, it.params[0].toInt()) }
        return largest
    }

    /** CvPlayerTraits::GetCityStateCombatModifier: combat bonus against city-states */
    fun cityStateCombatModifier(civ: Civilization): Int {
        var sum = 0
        civ.forEachMatchingUnique(UniqueType.Strength, GameContext.IgnoreConditionals) { unique ->
            if (unique.modifiers.any { it.type == UniqueType.ConditionalVsCombatant && it.params[0] == "City-States" })
                sum += unique.params[0].toInt()
        }
        return sum
    }

    /** CvGrandStrategyAI::GetSpaceshipPriority */
    fun spaceshipPriority(civ: Civilization, def: GrandStrategyDef): Int {
        if (!isVictoryValid(civ, def)) return -100
        val ai = civ.ai
        var priority = civ.getAiEraNumber() * ai.personality(Flavor.Science) * 150 / 100
        if (hasApolloProgram(civ)) priority += ai.rules.define(AiDefine.AI_GS_SS_HAS_APOLLO_PROGRAM)
        return priority
    }

    /** GetTeam()->getProjectCount(PROJECT_APOLLO_PROGRAM) > 0: counts from the turn it was built, even if the city is lost later */
    fun hasApolloProgram(civ: Civilization) = civ.ai.apolloProgramBuilt || civ.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts)

    //region Guessing other players' grand strategies

    /** CvGrandStrategyAI::DoGuessOtherPlayersActiveGrandStrategy */
    fun doGuessOtherPlayersActiveGrandStrategy(civ: Civilization) {
        val ai = civ.ai
        val rules = ai.rules
        val gameInfo = civ.gameInfo
        val worldMilitaryAverage = worldMilitaryStrengthAverage(civ)

        val aliveMajors = gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() }
        if (aliveMajors.isEmpty()) return
        val worldCultureAverage = aliveMajors.sumOf { it.totalCultureForContests } / aliveMajors.size
        val worldTourismAverage = aliveMajors.sumOf { it.ai.tourism() } / aliveMajors.size
        val worldNumTechsAverage = aliveMajors.sumOf { it.tech.techsResearched.size } / aliveMajors.size

        val definitions = rules.getGrandStrategies()
        for (other in aliveMajors) {
            if (other == civ || !civ.knows(other)) continue
            var bestStrategy: String? = null
            var bestPriority = Int.MIN_VALUE
            for (def in definitions) {
                val priority = when (def.name) {
                    CONQUEST -> guessOtherPlayerConquestPriority(civ, other, worldMilitaryAverage)
                    CULTURE -> guessOtherPlayerCulturePriority(civ, def, other, worldCultureAverage, worldTourismAverage)
                    UNITED_NATIONS -> guessOtherPlayerUnitedNationsPriority(civ, def, other)
                    SPACESHIP -> guessOtherPlayerSpaceshipPriority(civ, def, other, worldNumTechsAverage)
                    else -> 0
                }
                // Highest first; ties keep the earlier grand strategy (the original sorts its 5 entries with an insertion sort)
                if (priority > bestPriority) {
                    bestPriority = priority
                    bestStrategy = def.name
                }
            }
            // "No grand strategy" in case there is not enough to go on: added last, so it loses ties
            if (rules.define(AiDefine.AI_GRAND_STRATEGY_GUESS_NO_CLUE_WEIGHT) > bestPriority) {
                bestPriority = rules.define(AiDefine.AI_GRAND_STRATEGY_GUESS_NO_CLUE_WEIGHT)
                bestStrategy = null
            }
            val confidence = if (bestStrategy == null) null else guessConfidence(civ, bestPriority)
            ai.grandStrategyGuesses[other.civID] = GrandStrategyGuess(bestStrategy, confidence)
        }
    }

    /** The confidence thresholds of DoGuessOtherPlayersActiveGrandStrategy for the winning priority of a guessed grand strategy */
    fun guessConfidence(civ: Civilization, priority: Int): GuessConfidence {
        val rules = civ.ai.rules
        return when {
            priority >= rules.define(AiDefine.AI_GRAND_STRATEGY_GUESS_POSITIVE_THRESHOLD) -> GuessConfidence.Positive
            priority >= rules.define(AiDefine.AI_GRAND_STRATEGY_GUESS_LIKELY_THRESHOLD) -> GuessConfidence.Likely
            else -> GuessConfidence.Unsure
        }
    }

    /** CvGrandStrategyAI::GetGuessOtherPlayerConquestPriority */
    fun guessOtherPlayerConquestPriority(civ: Civilization, other: Civilization, worldMilitaryAverage: Int): Int {
        val rules = civ.ai.rules
        var priority = 0
        if (worldMilitaryAverage > 0)
            priority += (other.ai.militaryMight() - worldMilitaryAverage) *
                rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_POWER_RATIO_MULTIPLIER) / worldMilitaryAverage
        val memory = civ.ai.aggressionMemory[other.civID] ?: return priority
        priority += memory.minorsAttacked * rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_WEIGHT_PER_MINOR_ATTACKED)
        priority += memory.minorsConquered * rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_WEIGHT_PER_MINOR_CONQUERED)
        priority += memory.majorsAttacked * rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_WEIGHT_PER_MAJOR_ATTACKED)
        priority += memory.majorsConquered * rules.define(AiDefine.AI_GRAND_STRATEGY_CONQUEST_WEIGHT_PER_MAJOR_CONQUERED)
        return priority
    }

    /**
     *  CvGrandStrategyAI::GetGuessOtherPlayerCulturePriority. As in the original, a ratio beyond ±AI_GS_CULTURE_RATIO_MULTIPLIER adds that
     *  bound and then the ratio itself as well (the clamp does not replace the value).
     */
    fun guessOtherPlayerCulturePriority(civ: Civilization, def: GrandStrategyDef, other: Civilization, worldCultureAverage: Int, worldTourismAverage: Int): Int {
        if (def.victoryType !in civ.gameInfo.ruleset.victories) return -100
        val rules = civ.ai.rules
        var priority = 0
        if (worldCultureAverage > 0) {
            val multiplier = rules.define(AiDefine.AI_GS_CULTURE_RATIO_MULTIPLIER)
            val ratio = (other.totalCultureForContests - worldCultureAverage) * multiplier / worldCultureAverage
            if (ratio > multiplier) priority += multiplier
            else if (ratio < -multiplier) priority -= multiplier
            priority += ratio
        }
        if (worldTourismAverage > 0) {
            val multiplier = rules.define(AiDefine.AI_GS_TOURISM_RATIO_MULTIPLIER)
            val ratio = (other.ai.tourism() - worldTourismAverage) * multiplier / worldTourismAverage
            if (ratio > multiplier) priority += multiplier
            else if (ratio < -multiplier) priority -= multiplier
            priority += ratio
        }
        return priority
    }

    /** CvGrandStrategyAI::GetGuessOtherPlayerUnitedNationsPriority */
    fun guessOtherPlayerUnitedNationsPriority(civ: Civilization, def: GrandStrategyDef, other: Civilization): Int {
        if (def.victoryType !in civ.gameInfo.ruleset.victories) return -100
        var allies = 0
        var friends = 0
        var cityStatesAlive = 0
        for (cityState in civ.gameInfo.civilizations) {
            if (cityState == other || !cityState.isCityState || !cityState.isAlive()) continue
            cityStatesAlive++
            if (cityState.allyCiv == other) allies++
            else if (cityState.getDiplomacyManager(other)?.isRelationshipLevelGE(RelationshipLevel.Friend) == true) friends++
        }
        cityStatesAlive = max(cityStatesAlive, 1)
        var priority = allies + friends / 3
        priority *= civ.ai.rules.define(AiDefine.AI_GS_UN_SECURED_VOTE_MOD)
        return priority / cityStatesAlive
    }

    /** CvGrandStrategyAI::GetGuessOtherPlayerSpaceshipPriority */
    fun guessOtherPlayerSpaceshipPriority(civ: Civilization, def: GrandStrategyDef, other: Civilization, worldNumTechsAverage: Int): Int {
        if (def.victoryType !in civ.gameInfo.ruleset.victories) return -100
        val rules = civ.ai.rules
        if (hasApolloProgram(other)) return rules.define(AiDefine.AI_GS_SS_HAS_APOLLO_PROGRAM)
        val average = if (worldNumTechsAverage == 0) 1 else worldNumTechsAverage
        return (other.tech.techsResearched.size - average) * rules.define(AiDefine.AI_GS_SS_TECH_PROGRESS_MOD) / average
    }
    //endregion

    //region Events the original's diplomacy AI records for the guesses

    /**
     *  CvTeam::declareWar's update of every major's view (CvDiplomacyAI::ChangeOtherPlayerNumMinorsAttacked / ...MajorsAttacked): a major
     *  declaring war, other than through a defensive pact, is remembered by every other living major that has met it - a city-state as
     *  "minor attacked", a major as "major attacked" unless the observer is itself at war with the attacked civilization.
     */
    fun onWarDeclared(declarer: Civilization, target: Civilization, isDefensivePact: Boolean) {
        if (!declarer.isMajorCiv || isDefensivePact) return
        for (observer in declarer.gameInfo.civilizations) {
            if (observer == declarer || !observer.isMajorCiv || !observer.isAlive() || !observer.knows(declarer)) continue
            if (target.isCityState) observer.ai.aggressionOf(declarer).minorsAttacked++
            else if (target.isMajorCiv && (observer == target || !observer.isAtWarWith(target)))
                observer.ai.aggressionOf(declarer).majorsAttacked++
        }
    }

    /**
     *  CvPlayer::acquireCity's notification when the old owner is gone (CvDiplomacyAI::DoPlayerKilledSomeone): every other living major that
     *  has met the conqueror (a major) counts one minor or major conquered.
     */
    fun onCivDestroyed(conqueror: Civilization, destroyed: Civilization) {
        if (!conqueror.isMajorCiv) return
        for (observer in conqueror.gameInfo.civilizations) {
            if (observer == conqueror || !observer.isMajorCiv || !observer.isAlive() || !observer.knows(conqueror)) continue
            if (destroyed.isCityState) observer.ai.aggressionOf(conqueror).minorsConquered++
            else if (destroyed.isMajorCiv) observer.ai.aggressionOf(conqueror).majorsConquered++
        }
    }
    //endregion

    /** Registers the economic strategies that follow the active grand strategy (EconomicAIHelpers::IsTestStrategy_GS_*) */
    internal fun registerStrategyTests(tests: StrategyTests) {
        tests.register("ECONOMICAISTRATEGY_GS_CULTURE") { civ, _ -> civ.ai.isGoingForCultureVictory() }
        tests.register("ECONOMICAISTRATEGY_GS_CONQUEST") { civ, _ -> civ.ai.isGoingForWorldConquest() }
        tests.register("ECONOMICAISTRATEGY_GS_DIPLOMACY") { civ, _ -> civ.ai.isGoingForDiploVictory() }
        tests.register("ECONOMICAISTRATEGY_GS_SPACESHIP") { civ, _ -> civ.ai.isGoingForSpaceshipVictory() }
        // Follows through once the Apollo Program is built, whatever the grand strategy
        tests.register("ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH") { civ, _ -> hasApolloProgram(civ) }
    }
}
